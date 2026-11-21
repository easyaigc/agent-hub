package com.agenthub.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import com.agenthub.core.UsageRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual App footer/dialog + actual Store; only the read boundary is a synthetic fixture. */
class MobileUsageUITest {
    @get:Rule val ui=createComposeRule()
    @Test fun pairedMetadataRecompositionKeepsSummaryFooterAndDetailsFlight() {
        val epoch=1793149200000L
        fun paired(display:String):MobileClient {
            val scope=TargetScope("codex","legacy-display",display,"fixture-host","fixture-workspace","fixture-runtime")
            return MobileClient("fixture-host","Metadata $display",scope,"https://fixture.invalid",true,"desktop-runtime",
                codexDirect=PairedCodexConnection(scope,"a".repeat(64),listOf(CodexRoute("https://fixture.invalid","lan")),listOf("https://system.fixture.invalid")))
        }
        var client by mutableStateOf(paired("old"))
        val release=CompletableDeferred<Unit>();var reads=0;var cancelled=0
        val original=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("usage-v1.json").bufferedReader().readText()).getJSONArray("cases").getJSONObject(0).getJSONObject("response")
        val store=MobileUsageStore(clock={epoch}) { c ->
            reads++
            try { release.await();JSONObject(original.toString()).put("target",usageTarget(c)).toString() }
            catch(cancel:CancellationException){cancelled++;throw cancel}
        }
        try {
            ui.setContent { ProductTextProvider("en") { MaterialTheme { Column {
                ClientUsageSummaryContent(store,client,true)
                UsageFooterContent(store,client,true,true)
            } } } }
            ui.waitUntil(5000){reads==1}
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("Metadata old").assertIsDisplayed()
            repeat(3) { version ->
                ui.runOnIdle { client=paired("rebuilt-$version") }
                ui.onNodeWithText("Metadata rebuilt-$version").assertIsDisplayed()
                ui.runOnIdle { assertEquals("Metadata must not cancel the paired read",0,cancelled);assertEquals(1,reads) }
            }
            ui.runOnIdle { release.complete(Unit) }
            ui.waitUntil(5000){store.value(client).has("balance")}
            ui.onNodeWithText("Done").performClick()
            ui.runOnIdle { assertEquals(1,reads);assertEquals(0,cancelled) }
        } finally { release.complete(Unit) }
    }
    @Test fun footerDetailsRefreshReasonsSourcePlanAndDebt() {
        val epoch=1793149200000L
        var now by mutableLongStateOf(epoch)
        var phase=0;var reads=0
        val exact="-0.000000000000000000000001"
        val client=MobileClient("fixture-host","Usage fixture",TargetScope("codex",null,null,"fixture-host","fixture-workspace","fixture-runtime"),"https://fixture.invalid",true,"desktop-runtime")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("usage-v1.json").bufferedReader().readText()).getJSONArray("cases").getJSONObject(0).getJSONObject("response")
        val store=MobileUsageStore(clock={now}) {
            reads++
            if(phase==2)throw CodexCommandError("fixture authentication denial",401)
            if(phase==1)JSONObject().put("schemaVersion",1).put("target",usageTarget(it)).put("requestId","denied").put("status","unavailable").put("reason","forbidden").toString()
            else JSONObject(original.toString()).apply {
                val snapshot=getJSONObject("snapshot")
                snapshot.getJSONObject("source").put("scopeLabel","Personal").put("planLabel","Pro")
                val metrics=snapshot.getJSONArray("buckets").getJSONObject(0).getJSONArray("metrics")
                metrics.getJSONObject(1).getJSONObject("reading").put("amount",exact)
                if(phase==3 || phase==4) snapshot.getJSONArray("buckets").getJSONObject(0).put("combination",JSONObject().put("kind",if(phase==3)"any-of" else "all-of").put("metricIds",JSONArray((0 until metrics.length()).map {index->metrics.getJSONObject(index).getString("id")})))
                if(phase==3) {
                    metrics.getJSONObject(0).put("reading",JSONObject().put("state","unavailable").put("reason","forbidden"))
                    snapshot.put("coverage","partial").put("issues",JSONArray())
                }
            }.toString()
        }
        ui.setContent { ProductTextProvider("en") { MaterialTheme { UsageFooterContent(store,client,true,true) } } }
        ui.waitUntil(5000){store.value(client).has("balance")}
        ui.onNodeWithTag("current-client-usage").performClick()
        ui.onNodeWithText("Current client usage").assertIsDisplayed()
        ui.onNodeWithText("Updated：2026-10-28T01:00:00.000Z").assertIsDisplayed()
        ui.onAllNodesWithText("Applies to：All models and features").onFirst().performScrollTo().assertIsDisplayed()
        ui.onAllNodesWithText("Provider rules apply").onFirst().performScrollTo().assertIsDisplayed()
        ui.onAllNodesWithText("Observed：2026-10-28T01:00:00.000Z").onFirst().performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Resets：2026-11-01T00:00:00.000Z").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Percentages show usage consumed; each window is independent").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Usage source：Personal").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Plan：Pro").assertIsDisplayed()
        ui.onNodeWithText("$exact credits · Amount owed").performScrollTo().assertIsDisplayed()
        screenshot("source-plan-debt")
        ui.onNodeWithText("Refresh usage").performClick()
        ui.runOnIdle { assertEquals(1,reads);now=epoch+5000;phase=1 }
        ui.onNodeWithText("Refresh usage").performClick()
        ui.waitUntil(5000){store.value(client).optString("reason")=="forbidden"}
        ui.onNodeWithText("The usage source denied this read").assertIsDisplayed()
        ui.onNodeWithText("$exact credits · Amount owed").assertDoesNotExist()
        ui.onNodeWithText("Usage source：Personal").assertDoesNotExist()
        ui.runOnIdle { now=epoch+10000;phase=2 }
        ui.onNodeWithText("Refresh usage").performClick()
        ui.waitUntil(5000){store.value(client).optString("reason")=="http-unauthenticated"}
        ui.onNodeWithText("Usage read authentication failed (HTTP 401)").assertIsDisplayed()
        screenshot("http401-withdrawn")
        ui.runOnIdle { now=epoch+15000;phase=3 }
        ui.onNodeWithText("Refresh usage").performClick()
        ui.waitUntil(5000){store.value(client).optBoolean("partial")}
        ui.onNodeWithText("Some usage is unavailable").performScrollTo().assertIsDisplayed()
        ui.onAllNodesWithText("Any limit may be available").onFirst().performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Unavailable").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("The usage source denied this read").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("$exact credits · Amount owed").performScrollTo().assertIsDisplayed()
        screenshot("partial-valid-sibling")
        ui.runOnIdle { assertEquals(4,reads);now=epoch+20000;phase=4 }
        ui.onNodeWithText("Refresh usage").performClick()
        ui.waitUntil(5000){reads==5 && !store.value(client).optBoolean("partial")}
        ui.onAllNodesWithText("All limits apply").onFirst().performScrollTo().assertIsDisplayed()
        ui.runOnIdle { assertEquals(5,reads);now=epoch+120000 }
        ui.waitUntil(5000){store.cached(client)}
        ui.onNodeWithText("Cached usage").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Done").performClick()
    }
    private fun incrementClient(runtime:String="runtime-a")=MobileClient("shared-display-id","Usage $runtime",
        TargetScope("codex",null,null,"fixture-host","fixture-workspace",runtime),"https://fixture.invalid",true,"desktop-runtime")
    private fun incrementResponse(client:MobileClient,amount:String="7",partial:Boolean=false):String {
        val at="2026-10-28T01:00:00.000Z";val until="2026-10-28T01:01:00.000Z"
        fun metric(id:String,value:String)=JSONObject().put("id",id).put("label",id).put("kind","balance")
            .put("observedAt",at).put("validUntil",until).put("appliesTo",JSONObject().put("kind","all"))
            .put("unit",JSONObject().put("kind","credits").put("code","credit").put("label","credits"))
            .put("reading",JSONObject().put("state","amount").put("amount",value).put("entitlement","finite"))
            .put("expiresAt",JSONObject.NULL)
        val sibling=metric("secondary","19")
        if(partial)sibling.put("reading",JSONObject().put("state","unavailable").put("reason","forbidden"))
        return JSONObject().put("schemaVersion",1).put("target",usageTarget(client)).put("requestId","p5-ui")
            .put("status","ok").put("snapshot",JSONObject().put("snapshotId","$amount-$partial")
                .put("sampledAt",at).put("source",JSONObject().put("adapterId","codex").put("scopeRef","wallet")
                    .put("scopeLabel","Confirmed fixture wallet").put("planLabel","Fixture"))
                .put("coverage",if(partial)"partial" else "complete").put("issues",JSONArray())
                .put("buckets",JSONArray().put(JSONObject().put("id","pool").put("label",JSONObject.NULL)
                    .put("sharedPoolRef",JSONObject.NULL).put("combination",JSONObject().put("kind","provider-defined"))
                    .put("metrics",JSONArray().put(metric("primary",amount)).put(sibling))))).toString()
    }

    /** Real list summary, sidebar footer and details; only usage/read and clock are controlled. */
    @Test fun sharedEntriesLoadingThrottleAndPartialSiblingUseProductionComponents() {
        val c=incrementClient();var now by mutableLongStateOf(1793149200000L)
        fun detail(value:String)=ui.onNode(hasText(value) and hasAnyAncestor(isDialog()))
        var reads=0;val initial=CompletableDeferred<Unit>();val refresh=CompletableDeferred<Unit>()
        val store=MobileUsageStore(clock={now}) {
            reads++
            when(reads) {1->initial.await();2->refresh.await();else->throw java.io.IOException("synthetic offline")}
            incrementResponse(it,partial=reads==2)
        }
        try {
            ui.setContent {ProductTextProvider("en") {MaterialTheme {Column {
                ClientUsageSummaryContent(store,c,true)
                UsageFooterContent(store,c,true,true)
            }}}}
            ui.waitUntil(5000){reads==1}
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("Refresh usage").assertIsNotEnabled()
            ui.onNodeWithTag("usage-refresh-loading").assertIsDisplayed()
            ui.runOnIdle {assertTrue(store.refreshing(c));assertEquals(1,reads);initial.complete(Unit)}
            ui.waitUntil(5000){store.value(c).has("balance")&&!store.refreshing(c)}
            ui.onNodeWithTag("usage-refresh-loading").assertDoesNotExist()
            ui.onNodeWithText("Refresh usage").assertIsEnabled()
            ui.onNodeWithText("19 credits").performScrollTo().assertIsDisplayed()
            ui.runOnIdle {now+=4999}
            ui.onNodeWithText("Refresh usage").performClick()
            ui.runOnIdle {assertEquals(1,reads);now+=1}
            ui.onNodeWithText("Refresh usage").performClick()
            ui.waitUntil(5000){reads==2}
            ui.onNodeWithText("Refresh usage").assertIsNotEnabled()
            ui.onNodeWithTag("usage-refresh-loading").assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithTag("client-usage-summary-${c.id}",useUnmergedTree=true)
                .assert(hasAnyDescendant(hasText("7 credits +1")))
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.runOnIdle {assertEquals("Opening details joins, never duplicates refresh",2,reads);refresh.complete(Unit)}
            ui.waitUntil(5000){store.value(c).optBoolean("partial")&&!store.refreshing(c)}
            ui.onNodeWithText("Refresh usage").assertIsEnabled()
            detail("Some usage is unavailable").performScrollTo().assertIsDisplayed()
            detail("7 credits").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("19 credits").assertDoesNotExist()
            ui.onNodeWithText("The usage source denied this read").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithTag("client-usage-summary-${c.id}",useUnmergedTree=true)
                .assert(hasAnyDescendant(hasText("7 credits")))
            ui.onNodeWithTag("client-usage-summary-${c.id}",useUnmergedTree=true)
                .assert(hasAnyDescendant(hasText("Some usage is unavailable")))
            ui.onNodeWithTag("current-client-usage").assert(hasContentDescription("7 credits",substring=true))
            // Unattested whole failure must remove wallet values, even after a partial success.
            ui.runOnIdle {now+=5000}
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("Refresh usage").performClick()
            ui.waitUntil(5000){store.value(c).optString("reason")=="source-unconfirmed"}
            ui.onNodeWithText("7 credits").assertDoesNotExist()
            ui.onNodeWithText("19 credits").assertDoesNotExist()
            ui.runOnIdle {assertEquals(3,reads);assertFalse(store.value(c).has("metrics"));assertFalse(store.value(c).has("balance"))}
            ui.onNodeWithText("Done").performClick()
        } finally {initial.complete(Unit);refresh.complete(Unit)}
    }

    @Test fun switchRuntimeClosesOldDetailsAndLateValueCannotEnterNewEntries() {
        val a=incrementClient();val b=incrementClient("runtime-b")
        var selected by mutableStateOf(a);var reads=0;var returned=false
        val old=CompletableDeferred<Unit>()
        val store=MobileUsageStore(clock={1793149200000L}) { c ->
            reads++
            if(c.scope!!.runtimeId=="runtime-a")withContext(NonCancellable) {
                old.await();returned=true;incrementResponse(c,"111")
            } else incrementResponse(c,"9")
        }
        try {
            ui.setContent {ProductTextProvider("en") {MaterialTheme {Column {
                ClientUsageSummaryContent(store,selected,true)
                UsageFooterContent(store,selected,true,true)
            }}}}
            ui.waitUntil(5000){reads==1}
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("Usage runtime-a").assertIsDisplayed()
            ui.runOnIdle {selected=b}
            ui.onNodeWithText("Current client usage").assertDoesNotExist()
            ui.waitUntil(5000){store.value(b).optString("balance")=="9"}
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("Usage runtime-b").assertIsDisplayed()
            ui.runOnIdle {old.complete(Unit)}
            ui.waitUntil(5000){returned&&!store.refreshing(a)}
            ui.onNodeWithText("111 credits").assertDoesNotExist()
            ui.onNodeWithText("9 credits").performScrollTo().assertIsDisplayed()
            ui.runOnIdle {assertEquals(2,reads);assertEquals("9",store.value(b).getString("balance"))}
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithTag("client-usage-summary-${b.id}",useUnmergedTree=true)
                .assert(hasAnyDescendant(hasText("9 credits +1")))
            ui.onNodeWithTag("current-client-usage").assert(hasContentDescription("9 credits",substring=true))
        } finally {old.complete(Unit)}
    }


    /** Only the usage/read boundary is synthetic; the shared projection and both views are shipping code. */
    private fun remainingFixture(client:MobileClient,at:Long,withWindows:Boolean=true,partial:Boolean=false,
        firstReset:Long=at+18_000_000L,amount:String?=null):String {
        val original=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("usage-v1.json").bufferedReader().use { it.readText() })
            .getJSONArray("cases").getJSONObject(0).getJSONObject("response")
        val response=JSONObject(original.toString()).put("target",usageTarget(client)).put("requestId",java.util.UUID.randomUUID().toString())
        val snapshot=response.getJSONObject("snapshot");val pool=snapshot.getJSONArray("buckets").getJSONObject(0)
        val old=pool.getJSONArray("metrics");val window=JSONObject(old.getJSONObject(0).toString());val wallet=JSONObject(old.getJSONObject(1).toString())
        val observed=java.time.Instant.ofEpochMilli(at).toString();val valid=java.time.Instant.ofEpochMilli(at+3_600_000L).toString()
        fun stamp(metric:JSONObject)=metric.put("observedAt",observed).put("validUntil",valid)
        fun period(id:String,label:String,used:Int,duration:Long,reset:Long)=stamp(JSONObject(window.toString()))
            .put("id",id).put("label",label).put("window",JSONObject().put("durationSeconds",duration)
                .put("resetsAt",java.time.Instant.ofEpochMilli(reset).toString()).put("resetPolicy","provider-defined"))
            .put("reading",JSONObject().put("state","measured").put("usage",JSONObject().put("kind","percentage").put("usedPercent",used)))
        val capacity=stamp(JSONObject(window.toString())).apply { remove("window") }.put("id","capacity-first").put("label","Capacity")
            .put("kind","capacity-limit").put("reading",JSONObject().put("state","measured").put("usage",JSONObject()
                .put("kind","quantity").put("used","9").put("limit","10").put("unit",JSONObject().put("kind","credits").put("code","slots").put("label","slots"))))
        val metrics=JSONArray().put(capacity)
        if(withWindows)metrics.put(period("fast","5h",25,18000,firstReset)).put(period("weekly","7d",60,604800,at+604_800_000L))
        stamp(wallet);if(amount!=null)wallet.getJSONObject("reading").put("amount",amount)
        metrics.put(wallet)
        if(partial)metrics.put(stamp(JSONObject(wallet.toString())).put("id","unavailable-wallet").put("label","Unavailable wallet")
            .put("reading",JSONObject().put("state","unavailable").put("reason","forbidden")))
        pool.put("label","Fixture limits").put("metrics",metrics)
        snapshot.put("snapshotId",java.util.UUID.randomUUID().toString()).put("sampledAt",observed)
            .put("coverage",if(partial)"partial" else "complete").put("issues",JSONArray())
        snapshot.getJSONObject("source").put("scopeLabel","Fixture source").put("planLabel","Fixture plan")
        return response.toString()
    }
    private fun remainingMetricId(id:String)=JSONArray(listOf("pool",id)).toString()
    private fun projectedMetric(value:JSONObject,id:String):JSONObject {
        val metrics=value.getJSONArray("metrics")
        return (0 until metrics.length()).map { metrics.getJSONObject(it) }.single { it.getString("id")==remainingMetricId(id) }
    }

    @Test(timeout=30_000) fun remainingRingUsesSelectedWindowAndPreservesUsedMetrics() {
        val c=incrementClient("remaining-ring");val at=System.currentTimeMillis();val raw=remainingFixture(c,at)
        val projected=JSONObject(UsageRules.project(raw,usageTarget(c).toString(),at))
        assertEquals(remainingMetricId("fast"),projected.getString("ringMetricId"));assertEquals(75.0,projected.getDouble("percent"),0.0)
        assertEquals(25.0,projectedMetric(projected,"fast").getDouble("percent"),0.0)
        assertEquals("25%",projectedMetric(projected,"fast").getString("value"))
        assertEquals(40.0,projectedMetric(projected,"weekly").getDouble("remainingPercent"),0.0)
        val overdrawn=JSONObject(raw)
        overdrawn.getJSONObject("snapshot").getJSONArray("buckets").getJSONObject(0).getJSONArray("metrics")
            .getJSONObject(1).getJSONObject("reading").getJSONObject("usage").put("usedPercent",125)
        val exhausted=JSONObject(UsageRules.project(overdrawn.toString(),usageTarget(c).toString(),at))
        assertEquals(125.0,projectedMetric(exhausted,"fast").getDouble("percent"),0.0)
        assertEquals("125%",projectedMetric(exhausted,"fast").getString("value"))
        assertEquals(0.0,exhausted.getDouble("percent"),0.0)
        val owner=SupervisorJob();var shown by mutableStateOf(true);var reads=0
        val store=MobileUsageStore(clock={at},reads=CoroutineScope(owner+Dispatchers.Main.immediate)) { reads++;raw }
        try {
            ui.setContent {ProductTextProvider("zh-Hans") {MaterialTheme {if(shown)Column {
                ClientUsageSummaryContent(store,c,true);UsageFooterContent(store,c,true,true)
            }}}}
            ui.waitUntil(5000){store.value(c).has("ringMetricId")}
            ui.onNodeWithTag("current-client-usage").assert(hasContentDescription("剩余75%",substring=true))
            ui.onNodeWithTag("usage-remaining-ring",useUnmergedTree=true).assert(hasAnyDescendant(hasText("75%")))
            ui.onAllNodes(hasAnyAncestor(hasTestTag("usage-remaining-ring")) and SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(0.75f,0f..1f)),useUnmergedTree=true).assertCountEquals(1)
            ui.onNodeWithTag("client-usage-window-"+remainingMetricId("fast"),useUnmergedTree=true)
                .assert(hasText("5h",substring=true)).assert(hasText("剩余75%",substring=true))
            ui.onNodeWithTag("client-usage-window-"+remainingMetricId("weekly"),useUnmergedTree=true)
                .assert(hasText("7d",substring=true)).assert(hasText("剩余40%",substring=true))
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("25%").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("60%").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("百分比为已用用量，各窗口单独计算").performScrollTo().assertIsDisplayed()
            ui.runOnIdle {assertEquals(1,reads)}
        } finally {ui.runOnIdle {shown=false;owner.cancel()};ui.waitForIdle()}
    }

    /** The production visible ticker reads wall time, not Store.clock or the Compose virtual clock. */
    @Test(timeout=40_000) fun visibleResetClockCountsDownThenWaitsWithoutRefilling() {
        val c=incrementClient("visible-reset");val at=System.currentTimeMillis();val raw=remainingFixture(c,at,firstReset=at+12000)
        val projected=JSONObject(UsageRules.project(raw,usageTarget(c).toString(),at));val originalSnapshot=projected.getString("snapshotId")
        val owner=SupervisorJob();var shown by mutableStateOf(true);var reads=0
        val store=MobileUsageStore(clock={at},reads=CoroutineScope(owner+Dispatchers.Main.immediate)) {reads++;raw}
        fun summaryText():String=ui.onNodeWithTag("client-usage-window-"+remainingMetricId("fast"),useUnmergedTree=true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        try {
            ui.setContent {ProductTextProvider("zh-Hans") {MaterialTheme {if(shown)Column {
                ClientUsageSummaryContent(store,c,true);UsageFooterContent(store,c,true,true)
            }}}}
            ui.waitUntil(5000){store.value(c).has("ringMetricId")}
            val first=summaryText();assertTrue(first.contains("后重置"));assertTrue(first.contains("剩余75%"))
            ui.waitUntil(4000){val next=summaryText();next!=first&&next.contains("后重置")}
            ui.waitUntil(16000){summaryText().contains("已到重置时间，等待更新")}
            ui.onNodeWithTag("client-usage-window-"+remainingMetricId("fast"),useUnmergedTree=true)
                .assert(hasText("已到重置时间，等待更新",substring=true)).assert(hasText("剩余75%",substring=true))
            ui.onNodeWithTag("current-client-usage").assert(hasContentDescription("剩余75%",substring=true))
            ui.runOnIdle {
                val after=store.value(c);assertEquals(originalSnapshot,after.getString("snapshotId"))
                assertEquals(75.0,after.getDouble("percent"),0.0);assertEquals("25%",projectedMetric(after,"fast").getString("value"))
                assertEquals("Reset time alone must not invent a provider refresh or a new allowance",1,reads)
            }
        } finally {ui.runOnIdle {shown=false;owner.cancel()};ui.waitForIdle()}
    }

    @Test(timeout=30_000) fun nonWindowMetricsNeverInventRingAndPreservePartialDebtPrecision() {
        val c=incrementClient("no-window");val at=System.currentTimeMillis();val exact="-9007199254740993.123456789012345678901234"
        val raw=remainingFixture(c,at,withWindows=false,partial=true,amount=exact)
        val projected=JSONObject(UsageRules.project(raw,usageTarget(c).toString(),at))
        assertFalse(projected.has("ringMetricId"));assertFalse(projected.has("percent"))
        assertEquals(90.0,projectedMetric(projected,"capacity-first").getDouble("percent"),0.0)
        assertEquals(exact,projectedMetric(projected,"credits").getString("value"))
        assertEquals("-9,007,199,254,740,993.12",projected.getString("balance"));assertTrue(projected.getBoolean("balanceDebt"));assertTrue(projected.getBoolean("partial"))
        val owner=SupervisorJob();var shown by mutableStateOf(true);var reads=0
        val store=MobileUsageStore(clock={at},reads=CoroutineScope(owner+Dispatchers.Main.immediate)) {reads++;raw}
        try {
            ui.setContent {ProductTextProvider("zh-Hans") {MaterialTheme {if(shown)Column {
                ClientUsageSummaryContent(store,c,true);UsageFooterContent(store,c,true,true)
            }}}}
            ui.waitUntil(5000){store.value(c).has("balance")}
            ui.onNodeWithTag("usage-remaining-ring",useUnmergedTree=true).assertDoesNotExist()
            ui.onNodeWithTag("current-client-usage").assert(!hasContentDescription("剩余",substring=true))
                .assert(hasContentDescription("-9,007,199,254,740,993.12 credits",substring=true))
                .assert(hasContentDescription("欠额",substring=true)).assert(hasContentDescription("部分额度暂不可用",substring=true))
            ui.onNodeWithTag("client-usage-window-"+remainingMetricId("capacity-first"),useUnmergedTree=true).assert(hasText("9 / 10 slots",substring=true))
            ui.onNodeWithTag("current-client-usage").performClick()
            ui.onNodeWithText("额度来源：Fixture source").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("套餐：Fixture plan").assertIsDisplayed()
            ui.onNodeWithText("$exact credits · 欠额").performScrollTo().assertIsDisplayed()
            ui.onNode(hasText("部分额度暂不可用") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("额度来源不允许读取").performScrollTo().assertIsDisplayed()
            ui.runOnIdle {assertEquals(1,reads);assertEquals(exact,projectedMetric(store.value(c),"credits").getString("value"))}
        } finally {ui.runOnIdle {shown=false;owner.cancel()};ui.waitForIdle()}
    }

    private fun screenshot(name:String) {
        val directory=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/f73-r2").apply {mkdirs()}
        java.io.File(directory,"$name.png").outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }
}
