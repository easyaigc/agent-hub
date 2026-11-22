package com.agenthub.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Auxiliary presentation boundaries, not provider/DEV or native interaction acceptance. */
class UsageDetailPresentationTest {
    private val tr: (String) -> String = { it }
    private fun metric(kind: String, state: String = "measured") =
        JSONObject().put("kind", kind).put("state", state)

    @Test fun remainingKeepsExactQuantityDebtUnlimitedAndUnknown() {
        val quantity = metric("capacity-limit").put("remainingValue", "9007199254740993.1234567890123456789").put("unit", "tokens")
        assertEquals("9007199254740993.1234567890123456789 tokens", UsageDetailPresentation.remaining(quantity, tr))
        assertEquals("75%", UsageDetailPresentation.remaining(metric("window-limit").put("remainingPercent", 75), tr))
        val debt = metric("balance", "amount").put("value", "-0.000000000000000000000001").put("unit", "credits").put("debt", true)
        assertEquals("-0.000000000000000000000001 credits · 欠额", UsageDetailPresentation.remaining(debt, tr))
        assertEquals("无限额", UsageDetailPresentation.remaining(metric("window-limit", "unlimited"), tr))
        assertEquals("暂不可用", UsageDetailPresentation.remaining(metric("window-limit", "unavailable").put("value", "forbidden"), tr))
        assertEquals("暂不可用", UsageDetailPresentation.remaining(metric("capacity-limit"), tr))
        assertNull(UsageDetailPresentation.remaining(metric("extension", "extension").put("value", "custom"), tr))
    }

    @Test fun countdownNeverReplenishesAnObservedReading() {
        val metric = metric("window-limit").put("resetAt", "2026-11-21T00:00:02Z").put("remainingPercent", 0)
        val before = metric.toString()
        val at = java.time.Instant.parse("2026-11-21T00:00:00Z").toEpochMilli()
        assertEquals("2 秒", UsageDetailPresentation.countdown(metric, at, tr))
        assertEquals("1 秒", UsageDetailPresentation.countdown(metric, at + 1001, tr))
        assertEquals("已到重置时间，等待更新", UsageDetailPresentation.countdown(metric, at + 2000, tr))
        assertEquals("已到重置时间，等待更新", UsageDetailPresentation.countdown(metric, at + 9000, tr))
        assertEquals("0%", UsageDetailPresentation.remaining(metric, tr))
        assertEquals(before, metric.toString())
    }

    @Test fun nonPeriodicAndMissingDatesDoNotInventAReset() {
        assertNull(UsageDetailPresentation.countdown(metric("capacity-limit").put("resetAt", "2026-11-21T00:00:02Z"), 0, tr))
        assertNull(UsageDetailPresentation.countdown(metric("balance", "amount"), 0, tr))
        assertNull(UsageDetailPresentation.countdown(metric("extension"), 0, tr))
        assertEquals("重置时间暂不可用", UsageDetailPresentation.countdown(metric("window-limit"), 0, tr))
        assertEquals("暂不可用", UsageDetailPresentation.countdown(metric("window-limit").put("resetAt", "invalid"), 0, tr))
        assertFalse(UsageDetailPresentation.needsTick(listOf("invalid"), 0))
        assertFalse(UsageDetailPresentation.needsTick(emptyList(), 0))
    }
}
