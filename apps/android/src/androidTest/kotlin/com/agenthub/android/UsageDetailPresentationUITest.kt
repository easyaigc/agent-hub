package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** New local native interactions only. Read fixture never sends HTTP or model input. */
class UsageDetailPresentationUITest {
    @get:Rule val ui = createComposeRule()

    @Test fun tickStopsOnBackgroundExitAndTargetReplacement() {
        val start = java.time.Instant.parse("2026-11-21T00:00:00Z").toEpochMilli()
        var time = start
        var clockReads = 0
        val clock = { clockReads++; time }
        var identity by mutableStateOf("target-a")
        var foreground by mutableStateOf(true)
        var open by mutableStateOf(true)
        val data = JSONObject().put("metrics", JSONArray().put(JSONObject().put("kind", "window-limit")
            .put("resetAt", "2026-11-21T00:01:00Z")))
        ui.mainClock.autoAdvance = false
        ui.setContent { if (open) {
            val now = usageDetailTime(identity, foreground, data, clock)
            Text("$identity:$now", Modifier.testTag("tick"))
        } }
        ui.mainClock.advanceTimeBy(32)
        fun advance() { ui.mainClock.advanceTimeBy(2100); ui.waitForIdle() }
        ui.runOnIdle { time += 2000 }
        advance()
        ui.onNodeWithTag("tick").assertTextEquals("target-a:${start + 2000}")
        ui.runOnIdle { foreground = false }
        ui.mainClock.advanceTimeBy(32); ui.waitForIdle()
        var stopped = 0
        ui.runOnIdle { stopped = clockReads; time += 2000 }
        advance()
        ui.runOnIdle { assertEquals(stopped, clockReads); foreground = true }
        ui.mainClock.advanceTimeBy(32); ui.waitForIdle()
        ui.onNodeWithTag("tick").assertTextEquals("target-a:${start + 4000}")
        ui.runOnIdle { identity = "target-b"; time += 2000 }
        ui.mainClock.advanceTimeBy(32); ui.waitForIdle()
        ui.onNodeWithTag("tick").assertTextEquals("target-b:${start + 6000}")
        ui.runOnIdle { time = start + 60000 }
        advance()
        ui.runOnIdle { stopped = clockReads }
        advance()
        ui.runOnIdle { assertEquals("No timer remains after every reset is due", stopped, clockReads) }
        ui.runOnIdle { open = false }
        ui.mainClock.advanceTimeBy(32); ui.waitForIdle()
        ui.runOnIdle { stopped = clockReads }
        advance()
        ui.runOnIdle { assertEquals(stopped, clockReads) }
        ui.onNodeWithTag("tick").assertDoesNotExist()
    }

    @Test(timeout = 30000) fun productionDetailsLifecycleDoesNotLeaveOrMultiplyPolling() {
        // Disable only the existing footer consumer, so the details' pre-existing
        // 30-second poll is observable without summary/manual refresh coverage.
        val start = System.currentTimeMillis()
        var now = start
        fun client(runtime: String) = MobileClient("detail-lifecycle", "Details $runtime",
            TargetScope("codex", null, null, "fixture-host", "fixture-workspace", runtime),
            "https://fixture.invalid", true, "desktop-runtime")
        var selected by mutableStateOf(client("runtime-a"))
        var foreground by mutableStateOf(true)
        val source = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("usage-v1.json")
            .bufferedReader().use { it.readText() }).getJSONArray("cases").getJSONObject(0).getJSONObject("response")
        val snapshot = source.getJSONObject("snapshot")
        snapshot.put("sampledAt", java.time.Instant.ofEpochMilli(start).toString())
        val buckets = snapshot.getJSONArray("buckets")
        for (b in 0 until buckets.length()) {
            val metrics = buckets.getJSONObject(b).getJSONArray("metrics")
            for (m in 0 until metrics.length()) {
                val metric = metrics.getJSONObject(m)
                metric.put("observedAt", java.time.Instant.ofEpochMilli(start).toString())
                    .put("validUntil", java.time.Instant.ofEpochMilli(start + 3600000).toString())
                metric.optJSONObject("window")?.put("resetsAt", java.time.Instant.ofEpochMilli(start + 600000).toString())
            }
        }
        val reads = mutableMapOf<String, Int>()
        val store = MobileUsageStore(clock = { now }) { c ->
            val runtime = requireNotNull(c.scope).runtimeId
            reads[runtime] = (reads[runtime] ?: 0) + 1
            JSONObject(source.toString()).put("target", usageTarget(c)).toString()
        }
        ui.mainClock.autoAdvance = false
        ui.setContent { ProductTextProvider("zh-Hans") { MaterialTheme {
            UsageFooterContent(store, selected, active = false, foreground = foreground)
        } } }
        fun advance(millis: Long) {
            ui.runOnIdle { now += millis }
            ui.mainClock.advanceTimeBy(millis)
            ui.waitForIdle()
        }
        fun count(a: Int, b: Int = 0) = ui.runOnIdle {
            assertEquals("runtime-a raw reads", a, reads["runtime-a"] ?: 0)
            assertEquals("runtime-b raw reads", b, reads["runtime-b"] ?: 0)
        }
        fun open() { ui.onNodeWithTag("current-client-usage").performClick(); advance(32) }
        fun close() { ui.onNodeWithText("完成").performClick(); advance(32) }
        advance(32); count(0)
        open(); count(1)
        // Positive control: virtual time really drives the existing poll. If
        // dispatchers do not share the test clock, this MUST fail, not skip.
        advance(31000); count(2)
        close(); advance(31000); count(2)
        ui.onNodeWithTag("usage-details-content").assertDoesNotExist()
        open(); count(3)
        ui.runOnIdle { foreground = false }
        advance(32); advance(31000); count(3)
        ui.runOnIdle { foreground = true }
        advance(32); count(4)
        ui.runOnIdle { selected = client("runtime-b") }
        advance(32)
        ui.onNodeWithTag("usage-details-content").assertDoesNotExist()
        advance(31000); count(4)
        open(); count(4, 1)
        close(); advance(31000); count(4, 1)
    }

    @Test(timeout = 20000) fun productionDetailsTickKeepsAllowanceAndReadCountAtLargeFont() {
        val at = System.currentTimeMillis()
        val client = MobileClient("detail-fixture", "Detail fixture", TargetScope("codex", null, null,
            "fixture-host", "fixture-workspace", "fixture-runtime"), "https://fixture.invalid", true, "desktop-runtime")
        val source = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("usage-v1.json")
            .bufferedReader().use { it.readText() }).getJSONArray("cases").getJSONObject(0).getJSONObject("response")
        source.put("target", usageTarget(client))
        val snapshot = source.getJSONObject("snapshot")
        val window = JSONObject(snapshot.getJSONArray("buckets").getJSONObject(0).getJSONArray("metrics").getJSONObject(0).toString())
        window.put("observedAt", java.time.Instant.ofEpochMilli(at).toString())
            .put("validUntil", java.time.Instant.ofEpochMilli(at + 60000).toString())
            .put("reading", JSONObject().put("state", "measured").put("usage", JSONObject().put("kind", "percentage").put("usedPercent", 100)))
        window.getJSONObject("window").put("resetsAt", java.time.Instant.ofEpochMilli(at + 6000).toString())
        snapshot.put("sampledAt", java.time.Instant.ofEpochMilli(at).toString())
        snapshot.getJSONArray("buckets").getJSONObject(0).put("metrics", JSONArray().put(window))
        var reads = 0
        val store = MobileUsageStore { reads++; source.toString() }
        ui.setContent { ProductTextProvider("zh-Hans") { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                Box(Modifier.width(280.dp)) { UsageFooterContent(store, client, true, true) }
            }
        } } }
        ui.waitUntil(5000) { store.value(client).optString("status") == "ok" }
        val projected = store.value(client).getJSONArray("metrics").getJSONObject(0)
        val id = projected.getString("id")
        ui.onNodeWithTag("current-client-usage").performClick()
        ui.onNodeWithTag("usage-remaining-$id", useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("0%")))
        ui.onNodeWithTag("usage-reset-countdown-$id", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        ui.waitUntil(9000) {
            ui.onAllNodesWithText("已到重置时间，等待更新").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("usage-remaining-$id", useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("0%")))
        ui.runOnIdle { assertEquals(1, reads); assertEquals(projected.toString(), store.value(client).getJSONArray("metrics").getJSONObject(0).toString()) }
        ui.onNodeWithText("完成").performClick()
        ui.onNodeWithTag("usage-details-content").assertDoesNotExist()
    }
}
