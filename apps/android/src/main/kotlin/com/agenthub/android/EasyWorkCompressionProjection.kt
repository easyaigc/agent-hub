package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Read-only committed watermark. Malformed or mismatched optional metadata is ignored atomically. */
internal object EasyWorkCompressionProjection {
    private fun text(v: JSONObject,key: String)=(v.opt(key) as? String)?.takeIf { it.isNotEmpty() }
    private fun integer(v: Any?): Long? {
        val number=(v as? Number)?.toString()?.toBigDecimalOrNull()?:return null
        return if(number>=java.math.BigDecimal.ZERO && number<=java.math.BigDecimal("9007199254740991") && number.stripTrailingZeros().scale()<=0)number.longValueExact() else null
    }
    fun applying(snapshot: JSONObject,threadId: String,revision: Long,messages: List<MobileMessage>): List<MobileMessage> {
        val state=snapshot.optJSONObject("contextCompressionState")?:return messages
        val thread=snapshot.optJSONObject("thread")?:return messages
        val active=snapshot.optJSONObject("activeRun")?:return messages
        val run=text(active,"runId")?:return messages
        if(thread.opt("id")!=threadId || active.opt("threadId")!=threadId || state.opt("threadId")!=threadId)return messages
        if(listOf(thread,active,state).any { integer(it.opt("branchRevision"))!=revision })return messages
        if(state.opt("runId")!=run)return messages
        val cursor=state.optJSONObject("cursor")?:return messages
        if(cursor.opt("source") !in listOf("protocol","runtime") || integer(cursor.opt("sequence"))==null)return messages
        val pending=state.optJSONArray("pending")?:return messages
        fun entry(raw: Any?): MobileMessage? {
            val value=raw as? JSONObject?:return null
            val created=text(value,"createdAt")?:return null
            val event=value.optJSONObject("event")?:return null
            val type=text(event,"type")?:return null
            if(type !in listOf("context_compression_started","context_compression_block_created","context_compression_block_rejected"))return null
            if(listOf("compressionOperationId","generationId").any { event.has(it)&&text(event,it)==null })return null
            val identity=text(event,"compressionOperationId")?.let { listOf("operation",it) }
                ?:text(event,"generationId")?.let { listOf("generation",it) }
                ?:listOf("legacy",text(event,"traceId").orEmpty(),created,type)
            val id="easywork-compaction."+easyBase64(easyCanonical(JSONArray(listOf(threadId,run)+identity)).toByteArray(Charsets.UTF_8))
            return MobileMessage(id,"tool","上下文压缩",status(event),"contextCompaction",easyCanonical(event),runId=run)
        }
        val entries=mutableListOf<MobileMessage>()
        for(i in 0 until pending.length()) {
            val message=entry(pending.opt(i))?:return messages
            if(message.status!="inProgress" || entries.any { it.id==message.id })return messages
            entries.add(message)
        }
        if(state.has("latestEvent")) {
            val latest=entry(state.opt("latestEvent"))?:return messages
            if(latest.status!="inProgress") {
                if(entries.any { it.id==latest.id })return messages
                entries.add(latest)
            }
        }
        val currentIds=entries.map { it.id }.toSet()
        val result=messages.filterNot { it.kind=="contextCompaction"&&it.runId==run&&it.status=="inProgress"&&it.id !in currentIds }.toMutableList()
        for(message in entries) {
            val index=result.indexOfFirst { it.id==message.id }
            if(index<0)result.add(message) else result[index]=message
        }
        return result
    }
    internal fun status(event: JSONObject): String = when(event.optString("type")) {
        "context_compression_started"->"inProgress"
        "context_compression_block_rejected"->when {
            event.has("status")&&text(event,"status")==null->"unknown"
            !event.has("status")->"failed"
            else->when(event.optString("status")) { "cancelled"->"cancelled";"skipped"->"skipped";"failed"->"failed";else->"unknown" }
        }
        "context_compression_block_created"->when {
            listOf("logicalStatus","persistenceState","resultStatus","generationStatus").any { event.has(it)&&text(event,it)==null }->"unknown"
            event.optString("logicalStatus")=="failed"||event.optString("persistenceState")=="degraded"||event.optString("resultStatus")=="model_failed"->"failed"
            event.optString("logicalStatus")=="skipped"||event.optString("resultStatus")=="skipped"->"skipped"
            text(event,"blockRef")==null || (event.has("logicalStatus")&&event.optString("logicalStatus") !in listOf("installed","reused")) || (event.has("persistenceState")&&event.optString("persistenceState")!="durable")->"unknown"
            else->"completed"
        }
        else->"unknown"
    }
}
