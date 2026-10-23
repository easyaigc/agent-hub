package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MobileClientCountTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val servers=mutableListOf<CommandFixture>()
    private val activity: MainActivity get() { var value: MainActivity?=null;ui.activityRule.scenario.onActivity { value=it };return checkNotNull(value) }
    private val session get()=activity.session
    @Before fun fresh() { ui.runOnIdle { session.resetAcceptance() } }
    @After fun finish() {
        try { servers.forEach { assertTrue(it.requests.all { request -> request.method=="GET" }) } }
        finally { ui.runOnIdle { session.leaveForeground() };servers.forEach { it.close() } }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();i.uiAutomation.waitForIdle(300,5_000)
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("countRunId") ?: "local";require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(i.targetContext.filesDir,"acceptance/P3-running-count/$run").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot();try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
    }
    @Test fun allGoldenCountsRetainUnknownAndDeduplicateAfterActiveFilter() {
        val vectors=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() }).getJSONArray("runningCounts")
        for(row in vectors.objects()) {
            val input=row.getJSONObject("input");val threads=input.getJSONArray("conversations").objects().map { ConversationActivity(it.getString("id"),it.getString("hostId"),it.getString("state")) }
            val expected=if(row.isNull("expected"))null else row.getInt("expected")
            assertEquals(row.getString("name"),expected,WorkspaceSidebarRules.runningCount(input.getString("hostId"),input.getBoolean("online"),input.getBoolean("demo"),input.getBoolean("workspaceAvailable"),input.optional("connectionMode"),threads))
        }
        assertEquals(11,vectors.length())
    }
    @Test fun demoClientsShowOneZeroAndUnknownWithoutChangingTheDraft() {
        ui.runOnIdle { session.setDemoMode(true);session.text="COUNT_UNSENT_DEMO" }
        ui.onNodeWithTag("client-selector").performClick()
        ui.onNodeWithTag("client-running-demo-mac",useUnmergedTree=true).assertTextEquals("已连接 · 进行中 1")
        ui.onNodeWithTag("client-running-demo-pc",useUnmergedTree=true).assertTextEquals("已连接 · 进行中 0")
        ui.onNodeWithTag("client-running-demo-home",useUnmergedTree=true).assertTextEquals("离线 · 查看缓存 · 进行中 —")
        shot("demo-one-zero-unknown")
        ui.onNodeWithTag("client-demo-pc").performClick();assertEquals(0,session.runningConversationCount(session.selectedClient))
        ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("client-demo-mac").performClick();assertEquals("COUNT_UNSENT_DEMO",session.text)
    }
    @Test fun liveSnapshotUpdatesKeepArchiveIndependentOfflineUnknownAndExactRuntimeScope() {
        val active=AtomicBoolean(true);val available=AtomicBoolean(true);val host="count-native-host"
        val target=TargetScope("codex",null,null,host,"count-workspace","count-runtime")
        fun thread(id: String,state: String,archived: Boolean=false)=JSONObject().put("id",id).put("hostId",host).put("title",id).put("state",if(active.get())state else "idle").put("archived",archived)
        val server=CommandFixture { request -> when {
            request.method!="GET" -> WireResponse(405)
            request.path=="/v1/workspace" -> if(!available.get())WireResponse(503)else WireResponse(body=JSONObject().put("hostId",host).put("name","Count fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target)).put("projects",JSONArray()).put("capabilities",JSONArray(listOf("health","history"))).put("threads",JSONArray(listOf(thread("same","running"),thread("same","waiting-for-approval"),thread("archived-active","running",true),thread("approval","waiting-for-approval"),thread("done","idle")))).toString())
            else -> WireResponse(404)
        } };servers+=server
        runBlocking { withContext(Dispatchers.Main) { session.connect("Count fixture",server.endpoint,"") } }
        ui.waitUntil(10_000) { !session.loading&&session.selectedClient.online }
        val client=session.selectedClient;assertEquals(3,session.runningConversationCount(client))
        assertNull(session.runningConversationCount(client.copy(scope=TargetScope("codex",null,null,host,"count-workspace","other-runtime"))))
        ui.runOnIdle { session.text="COUNT_UNSENT_REAL" }
        ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("client-running-$host",useUnmergedTree=true).assertTextEquals("已连接 · 进行中 3");shot("live-three-including-archived")
        active.set(false);ui.runOnIdle { session.refresh() };ui.waitUntil(10_000) { !session.loading&&session.runningConversationCount(session.clients.first())==0 }
        ui.onNodeWithTag("client-running-$host",useUnmergedTree=true).assertTextEquals("已连接 · 进行中 0");shot("live-settled-zero")
        available.set(false);ui.runOnIdle { session.refresh() };ui.waitUntil(10_000) { !session.loading&&!session.clients.first().online }
        assertNull(session.runningConversationCount(session.clients.first()));ui.onNodeWithTag("client-running-$host",useUnmergedTree=true).assertTextContains("进行中 —",substring=true);shot("offline-unknown-keeps-cache")
        assertTrue(session.threads.isNotEmpty());assertEquals("COUNT_UNSENT_REAL",session.text)
        available.set(true);active.set(true);ui.runOnIdle { session.refresh() };ui.waitUntil(10_000) { !session.loading&&session.runningConversationCount(session.clients.first())==3 }
        ui.onNodeWithTag("client-running-$host",useUnmergedTree=true).assertTextEquals("已连接 · 进行中 3");assertEquals("COUNT_UNSENT_REAL",session.text)
    }
}
