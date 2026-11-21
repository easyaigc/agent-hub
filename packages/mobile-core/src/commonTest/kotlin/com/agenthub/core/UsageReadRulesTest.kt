package com.agenthub.core

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class UsageReadRulesTest {
    private val root get()=Json.parseToJsonElement(Base64.decode(usageVectorBase64).decodeToString()).jsonObject
    private fun projected():JsonObject=Json.parseToJsonElement(UsageReadRules.projectResponse(
        root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString(),
        root.getValue("target").toString(),root.getValue("nowMillis").jsonPrimitive.long)).jsonObject
    @Test fun anEndpointOrPriorScopeNeverAttestsCurrentWalletOnFailure() {
        val previous=projected()
        for(reason in listOf("offline","timeout","upstream-error")) {
            val failed=Json.parseToJsonElement(UsageReadRules.failed(previous.toString(),reason,null)).jsonObject
            assertEquals("source-unconfirmed",failed.getValue("reason").jsonPrimitive.content)
            assertFalse(failed.containsKey("balance"));assertEquals(reason,failed.getValue("failureReason").jsonPrimitive.content)
        }
        val proof=previous.getValue("source").toString()
        val retained=Json.parseToJsonElement(UsageReadRules.failed(previous.toString(),"offline",proof)).jsonObject
        assertEquals(previous["snapshotId"],retained["snapshotId"])
        assertEquals(previous["sampledAt"],retained["sampledAt"])
        assertEquals(previous["balance"],retained["balance"])
        assertEquals(JsonPrimitive(true),retained["cached"])
        val wrong=proof.replace("fixture-scope","other-wallet")
        assertFalse(Json.parseToJsonElement(UsageReadRules.failed(previous.toString(),"offline",wrong)).jsonObject.containsKey("balance"))
    }
    @Test fun denialInvalidDataOrWrongTargetCannotRetainEvenWithSourceProof() {
        val previous=projected();val proof=previous.getValue("source").toString()
        for(reason in listOf("http-unauthenticated","http-forbidden","forbidden","wrong-target","invalid-data","read-rejected")) {
            val result=Json.parseToJsonElement(UsageReadRules.failed(previous.toString(),reason,proof)).jsonObject
            assertEquals(reason,result.getValue("reason").jsonPrimitive.content);assertFalse(result.containsKey("metrics"))
        }
        assertEquals("http-unauthenticated",UsageReadRules.httpFailure(401))
        assertEquals("http-forbidden",UsageReadRules.httpFailure(403))
        assertEquals("wrong-target",UsageReadRules.httpFailure(409))
    }
    @Test fun invalidTargetAndVersionAreLocalFailuresNotFreshBalances() {
        val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
        fun reason(raw:String)=Json.parseToJsonElement(UsageReadRules.projectResponse(raw,root.getValue("target").toString(),root.getValue("nowMillis").jsonPrimitive.long)).jsonObject.getValue("reason").jsonPrimitive.content
        assertEquals("wrong-target",reason(response.replace("fixture-host","other-host")))
        assertEquals("unsupported-version",reason(response.replace("\"schemaVersion\":1","\"schemaVersion\":2")))
        assertEquals("invalid-data",reason("{"))
        val decimalVersion=response.replace("\"schemaVersion\":1","\"schemaVersion\":1.0")
        assertEquals("ok",Json.parseToJsonElement(UsageReadRules.projectResponse(decimalVersion,root.getValue("target").toString(),root.getValue("nowMillis").jsonPrimitive.long)).jsonObject.getValue("status").jsonPrimitive.content)
    }
    @Test fun manualAndAutomaticReadsShareTheFiveSecondBoundary() {
        assertTrue(UsageReadRules.mayRefresh(100,Long.MIN_VALUE))
        assertFalse(UsageReadRules.mayRefresh(5099,100));assertTrue(UsageReadRules.mayRefresh(5100,100))
        assertFalse(UsageReadRules.mayRefresh(99,100))
    }
    @Test fun exactNegativeBalanceCarriesDebtWithoutChangingNumericValue() {
        val response=root.getValue("cases").jsonArray.first().jsonObject.getValue("response").toString()
            .replace("9007199254740993.123456789012345678901234","-0.000000000000000000000001")
        val result=Json.parseToJsonElement(UsageReadRules.projectResponse(response,root.getValue("target").toString(),root.getValue("nowMillis").jsonPrimitive.long)).jsonObject
        assertEquals(JsonPrimitive(true),result["balanceDebt"])
        assertEquals("-0.000000000000000000000001",result.getValue("metrics").jsonArray.last().jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true),result.getValue("metrics").jsonArray.last().jsonObject["debt"])
    }
}
