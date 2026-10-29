package com.agenthub.core

import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Both native clients consume this projection. Monetary/credit values never pass through floating point. */
object UsageRules {
    private val validator by lazy { SchemaValidator(usageSchemaBase64) }
    private val kinds = setOf("balance", "window-limit", "capacity-limit", "extension")
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun time(value: JsonElement): Long = Instant.parse(value.jsonPrimitive.content).toEpochMilliseconds()
    private fun compare(a: String, b: String): Int = a.trimStart('0').let { x -> b.trimStart('0').let { y -> if(x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y) } }
    private fun multiply(value: String, factor: Int): String {
        var carry=0; val result=StringBuilder()
        for(c in value.reversed()) { val n=(c-'0')*factor+carry; result.append(n%10); carry=n/10 }
        while(carry>0) { result.append(carry%10); carry/=10 }
        return result.reverse().toString().trimStart('0').ifEmpty { "0" }
    }
    /** Bounded, exact decimal long division for a graph only. Original quantities remain strings. */
    private fun ratio(used: String, limit: String): Double? {
        if(limit=="0")return null
        val scale=maxOf(used.substringAfter('.',"").length,limit.substringAfter('.',"").length)
        fun integer(s:String)=s.substringBefore('.')+s.substringAfter('.',"").padEnd(scale,'0')
        val u=integer(used);val l=integer(limit)
        fun subtract(a:String,b:String):String {
            val x=a.padStart(maxOf(a.length,b.length),'0').toCharArray();val y=b.padStart(x.size,'0');var borrow=0
            for(i in x.lastIndex downTo 0){var n=(x[i]-'0')-(y[i]-'0')-borrow;borrow=if(n<0)1 else 0;if(n<0)n+=10;x[i]=('0'.code+n).toChar()}
            return x.concatToString().trimStart('0').ifEmpty {"0"}
        }
        var remainder="0";val quotient=StringBuilder()
        for(c in multiply(u,10000)) {
            remainder=(remainder+c).trimStart('0').ifEmpty {"0"};var digit=0
            while(digit<9 && compare(multiply(l,digit+1),remainder)<=0)digit++
            quotient.append(digit);remainder=subtract(remainder,multiply(l,digit))
        }
        return quotient.toString().trimStart('0').ifEmpty {"0"}.toDouble()/100.0
    }
    fun displayAmount(value: String): String {
        // At most two fractional digits, rounded with string arithmetic; retain full value in details.
        val negative=value.startsWith('-');val magnitude=value.removePrefix("-")
        val whole=magnitude.substringBefore('.');val fraction=magnitude.substringAfter('.',"")
        if(whole=="0" && fraction.isNotEmpty() && fraction.take(2).all { it=='0' } && (fraction.getOrNull(2) ?: '0')<'5' && fraction.any { it!='0' })return (if(negative)"−" else "")+"<0.01"
        var cents=whole+fraction.take(2).padEnd(2,'0')
        if(fraction.getOrNull(2)?.let { it>='5' }==true) {
            val digits=cents.toCharArray();var carry=1
            for(i in digits.lastIndex downTo 0) {val n=(digits[i]-'0')+carry;digits[i]=('0'.code+n%10).toChar();carry=n/10}
            cents=(if(carry==1)"1" else "")+digits.concatToString()
        }
        val roundedWhole=cents.dropLast(2).trimStart('0').ifEmpty {"0"};val roundedFraction=cents.takeLast(2).trimEnd('0')
        val grouped=roundedWhole.reversed().chunked(3).joinToString(",").reversed()
        return (if(negative && cents.any { it!='0' })"-" else "")+grouped+(if(roundedFraction.isEmpty())"" else ".$roundedFraction")
    }
    /** Throws for malformed known values or a wrong machine. Unknown v1 children are skipped locally. */
    @Throws(Exception::class)
    fun project(json: String, targetJson: String, nowMillis: Long): String {
        require(json.encodeToByteArray().size<=262144)
        val input=Json.parseToJsonElement(json).jsonObject
        val expected=Json.parseToJsonElement(targetJson).jsonObject
        require(validator.validate(targetJson,"target")==null && input["target"]==expected)
        var unknown=false
        val sanitized=if(input.text("status")=="ok") {
            val snapshot=input.getValue("snapshot").jsonObject
            val buckets=snapshot.getValue("buckets").jsonArray
            require(buckets.size<=32 && buckets.sumOf { it.jsonObject.getValue("metrics").jsonArray.size }<=64)
            val fixed=buckets.map { item ->
                val b=item.jsonObject;val ids=mutableSetOf<String>()
                val metrics=b.getValue("metrics").jsonArray
                var skipped=false
                val retained=metrics.filter { metric ->
                    val m=metric.jsonObject
                    require(validator.validate(m.toString(),"metricBase")==null && ids.add(m.text("id")!!))
                    require(m.text("id")!!.encodeToByteArray().size<=128 && time(m.getValue("validUntil"))>=time(m.getValue("observedAt")))
                    if(m.text("kind") !in kinds) { unknown=true;skipped=true;false } else true
                }
                val combination=b.getValue("combination").jsonObject
                require(validator.validate(combination.toString(),"combination")==null)
                combination["metricIds"]?.jsonArray?.let { require(it.all { ref -> ref.jsonPrimitive.content in ids }) }
                JsonObject(b+mapOf("metrics" to JsonArray(retained))+if(skipped)mapOf("combination" to buildJsonObject {put("kind","provider-defined")})else emptyMap())
            }
            JsonObject(input+mapOf("snapshot" to JsonObject(snapshot+mapOf("buckets" to JsonArray(fixed)))))
        } else input
        require(validator.validateDocument(sanitized.toString())==null)
        fun boundedIds(value:JsonElement) {
            if(value is JsonObject)for((key,child)in value) {
                if(key in setOf("id","requestId","snapshotId","agentId","hostId","workspaceId","runtimeId","adapterId","scopeRef","sharedPoolRef","namespace","bucketId","metricId") && child!=JsonNull)require(child.jsonPrimitive.content.encodeToByteArray().size<=128)
                if(key=="code")require(child.jsonPrimitive.content.encodeToByteArray().size<=64)
                boundedIds(child)
            } else if(value is JsonArray)value.forEach { boundedIds(it) }
        }
        boundedIds(sanitized)
        if(input.text("status")=="unavailable")return buildJsonObject { put("status","unavailable");put("reason",input.getValue("reason")) }.toString()
        val snapshot=sanitized.getValue("snapshot").jsonObject
        val sampled=time(snapshot.getValue("sampledAt"));require(sampled<=nowMillis+120000)
        val source=snapshot.getValue("source").jsonObject
        val buckets=snapshot.getValue("buckets").jsonArray
        require(buckets.map { it.jsonObject.text("id") }.distinct().size==buckets.size)
        var missing=snapshot.getValue("issues").jsonArray.isNotEmpty();var expires=Long.MAX_VALUE
        val metrics=mutableListOf<JsonObject>();val balances=mutableListOf<JsonObject>();var ring:JsonObject?=null
        for(bucket in buckets) for(item in bucket.jsonObject.getValue("metrics").jsonArray) {
            val m=item.jsonObject;val observed=time(m.getValue("observedAt"));val valid=time(m.getValue("validUntil"))
            require(valid>=observed && observed<=sampled+120000)
            expires=minOf(expires,valid)
            if(m["expiresAt"]!=null && m["expiresAt"]!=JsonNull)time(m.getValue("expiresAt"))
            val window=m["window"]?.jsonObject
            if(window?.get("resetsAt")!=null && window["resetsAt"]!=JsonNull)time(window.getValue("resetsAt"))
            val reading=m["reading"]?.jsonObject;val state=reading?.text("state")
            if(state=="unavailable")missing=true
            val usage=reading?.get("usage")?.jsonObject
            val percent=if(state=="measured") when(usage?.text("kind")) {
                "percentage" -> usage.getValue("usedPercent").jsonPrimitive.double
                "quantity" -> ratio(usage.text("used")!!,usage.text("limit")!!)
                else -> null
            } else null
            val value=when(state) {
                "amount" -> reading.text("amount")!!
                "measured" -> if(usage?.text("kind")=="percentage") usage.getValue("usedPercent").jsonPrimitive.content+"%" else "${usage?.text("used")} / ${usage?.text("limit")}"
                "unlimited" -> "unlimited"
                "unavailable" -> reading.text("reason")!!
                else -> m.text("valueText") ?: "—"
            }
            val unit=m["unit"]?.jsonObject ?: usage?.get("unit")?.jsonObject
            val projected=buildJsonObject {
                put("id",bucket.jsonObject.text("id")+"/"+m.text("id"));put("label",m.getValue("label"));put("bucket",bucket.jsonObject["label"] ?: JsonNull)
                put("kind",m.getValue("kind"));put("state",state ?: "extension");put("value",value)
                put("unit",unit?.text("label") ?: "");put("validUntil",m.getValue("validUntil"));put("observedAt",m.getValue("observedAt"))
                put("resetAt",window?.get("resetsAt") ?: JsonNull);put("expiresAt",m["expiresAt"] ?: JsonNull)
                put("appliesTo",m.getValue("appliesTo"));put("combination",bucket.jsonObject.getValue("combination"))
                put("entitlement",reading?.get("entitlement") ?: JsonNull)
                put("note",m["note"] ?: JsonNull)
                if(percent!=null)put("percent",percent)
            }
            metrics.add(projected)
            if(m.text("kind")=="balance" && state in setOf("amount","unlimited"))balances.add(projected)
            // Choose an actual provider-ordered metric, never add/average distinct pools or windows.
            if(percent!=null && m.text("kind") in setOf("window-limit","capacity-limit") && m["appliesTo"]?.jsonObject?.text("kind")=="all" && (ring==null || ring.text("kind")=="capacity-limit" && m.text("kind")=="window-limit"))ring=projected
        }
        require((snapshot.text("coverage")=="partial")==missing)
        return buildJsonObject {
            put("status","ok");put("partial",missing || unknown);put("stale",expires<nowMillis)
            put("snapshotId",snapshot.getValue("snapshotId"));put("sampledAt",snapshot.getValue("sampledAt"));put("source",source)
            put("metrics",JsonArray(metrics));put("issues",snapshot.getValue("issues"))
            put("ringLabel",ring?.text("label") ?: "usage");ring?.get("percent")?.let { put("percent",it) }
            balances.firstOrNull()?.let { b -> put("balance",if(b.text("state")=="amount")displayAmount(b.text("value")!!)else "∞");put("balanceUnit",b.getValue("unit")) }
            put("extraBalances",maxOf(0,balances.size-1))
        }.toString()
    }
}
