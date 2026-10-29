package com.agenthub.core
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
class UsageRulesTest {
    private val vectors get()=Json.parseToJsonElement(Base64.decode(usageVectorBase64).decodeToString()).jsonObject
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
}
