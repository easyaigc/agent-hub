package com.agenthub.android

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Pure presentation boundaries; no provider read, model input or UI acceptance. */
class MobileUsagePresentationTest {
    @Test fun actualBalanceAndUnlimitedEntitlementAreBothVisible() {
        val metric=JSONObject().put("state","amount").put("value","9007199254740993.123456789012345678901234")
            .put("unit","credits").put("entitlement","unlimited")
        assertEquals("9007199254740993.123456789012345678901234 credits · 无限额",usageMetricText(metric))
        metric.put("entitlement","finite").put("value","0")
        assertEquals("0 credits",usageMetricText(metric))
        metric.put("value","-0.000000000000000000000001")
        assertEquals("-0.000000000000000000000001 credits",usageMetricText(metric))
    }
    @Test fun unknownAndUnlimitedDoNotInventAmountsOrUnits() {
        assertEquals("无限额",usageMetricText(JSONObject().put("state","unlimited").put("unit","credits")))
        assertEquals("暂不可用",usageMetricText(JSONObject().put("state","unavailable").put("value","offline")))
    }
    @Test fun absentBucketLabelDoesNotPrintJsonNull() {
        val metric=JSONObject().put("label","Credits").put("bucket",JSONObject.NULL)
        assertEquals("Credits",usageMetricTitle(metric))
        metric.put("bucket","Codex")
        assertEquals("Codex · Credits",usageMetricTitle(metric))
    }
    @Test fun debtAnnotationKeepsTheExactNegativeValue() {
        val metric=JSONObject().put("state","amount").put("value","-0.000000000000000000000001").put("unit","credits").put("debt",true)
        assertEquals("-0.000000000000000000000001 credits · 欠额",usageMetricText(metric))
    }

}
