package com.agenthub.core
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
class UsageRulesTest {
    private val vectors get()=Json.parseToJsonElement(Base64.decode(usageVectorBase64).decodeToString()).jsonObject
    @Test fun remainingAllowanceKeepsQuantitiesExactAndNeverResetsLocally() {
        val root=vectors;val target=root.getValue("target").toString();val now=root.getValue("nowMillis").jsonPrimitive.long
        val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
        fun metric(input:String)=Json.parseToJsonElement(UsageRules.project(input,target,now)).jsonObject.getValue("metrics").jsonArray.first().jsonObject
        val percentage=metric(response.replace("\"usedPercent\":23","\"usedPercent\":65"))
        assertEquals(35.0,percentage.getValue("remainingPercent").jsonPrimitive.double)
        assertEquals(0.0,metric(response.replace("\"usedPercent\":23","\"usedPercent\":125")).getValue("remainingPercent").jsonPrimitive.double)
        val quantity="\"kind\":\"quantity\",\"used\":\"9007199254740993.123456789012345678901233\",\"limit\":\"9007199254740993.123456789012345678901234\",\"unit\":{\"kind\":\"requests\",\"code\":\"request\",\"label\":\"requests\"}"
        val exact=metric(response.replace("\"kind\":\"percentage\",\"usedPercent\":23",quantity))
        assertEquals("0.000000000000000000000001",exact.getValue("remainingValue").jsonPrimitive.content)
        val exhausted=metric(response.replace("\"kind\":\"percentage\",\"usedPercent\":23",quantity.replace("9007199254740993.123456789012345678901234","1")))
        assertEquals("0",exhausted.getValue("remainingValue").jsonPrimitive.content)
        for(state in listOf("\"state\":\"unlimited\"","\"state\":\"unavailable\",\"reason\":\"not-returned\"")) {
            val input=response.replace("\"state\":\"measured\",\"usage\":{\"kind\":\"percentage\",\"usedPercent\":23}",state)
                .replace("\"coverage\":\"complete\"",if(state.contains("unavailable"))"\"coverage\":\"partial\"" else "\"coverage\":\"complete\"")
            val m=metric(input);assertFalse(m.containsKey("remainingPercent"));assertFalse(m.containsKey("remainingValue"))
            val projected=Json.parseToJsonElement(UsageRules.project(input,target,now)).jsonObject
            assertFalse(projected.containsKey("percent"));assertFalse(projected.containsKey("ringMetricId"))
        }
        val past=response.replace("2026-11-01T00:00:00.000Z","2026-10-27T00:00:00.000Z")
        assertEquals(77.0,metric(past).getValue("remainingPercent").jsonPrimitive.double)
    }
    @Test fun resetCountdownRoundsUpAndPreservesUnknownOrDueState() {
        val reset="2026-11-06T00:20:08.000Z"
        val at=kotlin.time.Instant.parse(reset).toEpochMilliseconds()
        assertEquals(1L,UsageRules.resetSeconds(reset,at-1))
        assertEquals(1L,UsageRules.resetSeconds(reset,at-1000))
        assertEquals(2L,UsageRules.resetSeconds(reset,at-1001))
        assertEquals(0L,UsageRules.resetSeconds(reset,at))
        assertEquals(0L,UsageRules.resetSeconds(reset,at+1000))
        assertNull(UsageRules.resetSeconds("not-a-date",at))
    }
    @Test fun summaryRingShowsOnlyRemainingPeriodicAllowance() {
        val root=vectors;val target=root.getValue("target").toString();val now=root.getValue("nowMillis").jsonPrimitive.long
        val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
        fun project(input:String)=Json.parseToJsonElement(UsageRules.project(input,target,now)).jsonObject
        val normal=project(response.replace("\"usedPercent\":23","\"usedPercent\":65"))
        assertEquals(35.0,normal.getValue("percent").jsonPrimitive.double)
        assertEquals(normal.getValue("metrics").jsonArray.first().jsonObject.getValue("id"),normal["ringMetricId"])
        assertEquals(100.0,project(response.replace("\"usedPercent\":23","\"usedPercent\":0")).getValue("percent").jsonPrimitive.double)
        assertEquals(0.0,project(response.replace("\"usedPercent\":23","\"usedPercent\":125")).getValue("percent").jsonPrimitive.double)
        val capacity=Json.parseToJsonElement(response).jsonObject.let { input ->
            val snapshot=input.getValue("snapshot").jsonObject
            val buckets=snapshot.getValue("buckets").jsonArray.map { bucket ->
                val b=bucket.jsonObject
                JsonObject(b+mapOf("metrics" to JsonArray(b.getValue("metrics").jsonArray.map { metric ->
                    val m=metric.jsonObject
                    if(m["kind"]==JsonPrimitive("window-limit"))JsonObject(m.minus("window")+mapOf("kind" to JsonPrimitive("capacity-limit")))else m
                })))
            }
            JsonObject(input+mapOf("snapshot" to JsonObject(snapshot+mapOf("buckets" to JsonArray(buckets))))).toString()
        }
        val noCycle=project(capacity)
        assertFalse(noCycle.containsKey("percent"));assertFalse(noCycle.containsKey("ringMetricId"))
        assertNotNull(noCycle["balance"])
        val quantity=response.replace("\"kind\":\"percentage\",\"usedPercent\":23","\"kind\":\"quantity\",\"used\":\"65\",\"limit\":\"100\",\"unit\":{\"kind\":\"requests\",\"code\":\"request\",\"label\":\"requests\"}")
        val measured=project(quantity)
        assertEquals(35.0,measured.getValue("percent").jsonPrimitive.double)
        assertEquals("35",measured.getValue("metrics").jsonArray.first().jsonObject.getValue("remainingValue").jsonPrimitive.content)
    }
    @Test fun sharedVectorsKeepPrecisionMissingWindowsAndUnknownChildren() {
        val root=vectors;val target=root.getValue("target").toString();val now=root.getValue("nowMillis").jsonPrimitive.long
        for(item in root.getValue("cases").jsonArray) {
            val row=item.jsonObject
            val actual=Json.parseToJsonElement(UsageRules.project(row.getValue("response").toString(),target,now)).jsonObject
            for((key,expected)in row.getValue("expected").jsonObject) {
                if(key=="percent")assertEquals(expected.jsonPrimitive.double,actual.getValue(key).jsonPrimitive.double)
                else assertEquals(expected,actual[key],"${row["name"]}:$key")
            }
            if(!row.getValue("expected").jsonObject.containsKey("percent"))assertFalse(actual.containsKey("percent"))
        }
    }
    @Test fun strictKnownFieldsTargetFreshnessAndDuplicateIds() {
        val root=vectors;val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
        val target=root.getValue("target").toString();val now=root.getValue("nowMillis").jsonPrimitive.long
        assertFails { UsageRules.project(response.replace("fixture-host","other-host"),target,now) }
        assertFails { UsageRules.project(response.replace("\"usedPercent\":23","\"usedPercent\":-1"),target,now) }
        assertFails { UsageRules.project(response.replace("9007199254740993.123456789012345678901234","01"),target,now) }
        val large=Json.parseToJsonElement(UsageRules.project(response.replace("\"usedPercent\":23","\"usedPercent\":1e308"),target,now)).jsonObject
        assertFalse(large.getValue("metrics").jsonArray.first().jsonObject.getValue("value").jsonPrimitive.content.contains("Infinity"))
        val stale=Json.parseToJsonElement(UsageRules.project(response,target,now+120000)).jsonObject
        assertEquals(JsonPrimitive(true),stale["stale"])
        assertEquals("9,007,199,254,740,993.12",UsageRules.displayAmount("9007199254740993.123456789012345678901234"))
        assertEquals("-0.01",UsageRules.displayAmount("-0.005"))
        assertEquals("1,000",UsageRules.displayAmount("999.999"))
        assertEquals("<0.01",UsageRules.displayAmount("0.00001"))
        assertEquals("−<0.01",UsageRules.displayAmount("-0.00001"))
    }
    @Test fun percentageRequiresAnActualNonnegativeFiniteJsonNumber() {
        val root=vectors
        val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
        val target=root.getValue("target").toString()
        val now=root.getValue("nowMillis").jsonPrimitive.long
        for(value in listOf("-1","true","null","\"23\"","1e309")) {
            assertFails(value) { UsageRules.project(response.replace("\"usedPercent\":23","\"usedPercent\":"+value),target,now) }
        }
        // Above 100 percent remains valid provider data; do not silently clamp it.
        val actual=Json.parseToJsonElement(UsageRules.project(response.replace("\"usedPercent\":23","\"usedPercent\":123.5"),target,now)).jsonObject
        assertEquals(123.5,actual.getValue("metrics").jsonArray.first().jsonObject.getValue("percent").jsonPrimitive.double)
        assertEquals(0.0,actual.getValue("percent").jsonPrimitive.double)
        assertEquals(0.0,actual.getValue("metrics").jsonArray.first().jsonObject.getValue("remainingPercent").jsonPrimitive.double)
    }

}
