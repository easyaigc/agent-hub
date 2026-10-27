package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/** Actual native controls distinguish latest-read busy state from an earlier-page request. */
@RunWith(AndroidJUnit4::class)
class MobileEarlierLoadingUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val language get()=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("readingLanguage") ?: "zh-Hans"
    private val earlierLabel get()=if(language=="en")"Show earlier messages" else "显示更早消息"
    private val earlierLoadingLabel get()=if(language=="en")"Loading earlier messages…" else "正在加载更早消息…"
    @Before fun protectDefaultStorage() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"earlier-reading-copy") }
    }
    private val host="loading-ui-"+UUID.randomUUID().toString();private val thread="loading-thread-"+UUID.randomUUID().toString();private var rowCount=120
    private val calls=AtomicInteger();private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private var windowed=false;private var holdLatest: CompletableDeferred<Unit>?=null;private var holdEarlier: CompletableDeferred<Unit>?=null
    private fun gate()=CompletableDeferred<Unit>().also { gates.add(it) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun page(client: MobileClient,thread: String,cursor: String?): MobileHistory {
        val range=if(windowed)if(cursor==null)60 until 120 else 0 until 60 else 0 until rowCount
        val value=JSONObject().put("hostId",client.id).put("threadId",thread).put("messages",JSONArray(range.map { JSONObject().put("id","loading-ui-$it").put("role","assistant").put("text","synthetic original row $it").put("status","completed") }))
        if(windowed)value.put("historyWindow",JSONObject().put("version",1).put("kind",if(cursor==null)"latest" else "earlier")).put("historyRevision",1).put("nextCursor",if(cursor==null)"earlier" else JSONObject.NULL)
        return HostReadCodec.history(value.toString())
    }
    private fun setup(waitForHistory: Boolean=true) {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject().put("hostId",host).put("name","synthetic native reading state").put("connectionMode","history-only").put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id",thread).put("hostId",host).put("title","synthetic reading state").put("state","idle"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory { calls.incrementAndGet();if(cursor==null)holdLatest?.await() else holdEarlier?.await();return page(client,threadId,cursor) }
        }
        ui.runOnIdle { session.resetAcceptance(reader);session.changeLanguage(language)
            session.changeAppearance(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light") };main { session.connect("synthetic earlier state","https://fixture.invalid","") };ui.runOnIdle { session.selectThread(session.threads.single());session.text="unchanged local draft" }
        if(waitForHistory)ui.waitUntil(10_000) { !session.historyLoading&&session.messages.isNotEmpty() } else ui.waitUntil(10_000) { session.historyLoading&&calls.get()>0 }
    }
    @After fun stop() { gates.forEach { it.complete(Unit) };ui.runOnIdle { session.leaveForeground();owned.verifyDefault() } }
    @Test fun latestRefreshWithoutEarlierDataCannotInventEarlierControl() {
        rowCount=8;setup();holdLatest=gate();main { session.loadHistory() };ui.waitUntil(5_000) { session.historyLoading }
        assertEquals(8,session.messages.size);assertFalse(session.timeline.hasEarlierMessages);assertNull(session.historyCursor)
        ui.onNodeWithTag("history-local-more").assertDoesNotExist();ui.onNodeWithTag("history-more").assertDoesNotExist();ui.onNodeWithText(earlierLoadingLabel).assertDoesNotExist()
        assertEquals("unchanged local draft",session.text)
    }
    @Test fun latestRefreshKeepsEarlierLabelWithoutEarlierSpinner() {
        setup();val ids=session.messages.map { it.id };holdLatest=gate();main { session.loadHistory() };ui.waitUntil(5_000) { session.historyLoading }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"));ui.onNodeWithTag("history-local-more").assertTextEquals(earlierLabel).assertIsNotEnabled()
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(hasTestTag("history-local-more")),useUnmergedTree=true).assertCountEquals(0)
        assertEquals(ids,session.messages.map { it.id });assertEquals("unchanged local draft",session.text)
    }
    @Test fun actualEarlierPageShowsBusyControlAndRetainsOriginalData() {
        windowed=true;setup();main { session.earlierMessages() };assertFalse(session.timeline.hasEarlierMessages);holdEarlier=gate()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-more"));ui.onNodeWithTag("history-more").assertIsEnabled().performClick();ui.waitUntil(5_000) { session.historyLoading&&calls.get()==2 }
        ui.onNodeWithTag("history-more").assertTextEquals(earlierLoadingLabel).assertIsNotEnabled()
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(hasTestTag("history-more")),useUnmergedTree=true).assertCountEquals(1)
        assertEquals(60,session.messages.size);assertEquals("unchanged local draft",session.text);holdEarlier!!.complete(Unit);ui.waitUntil(10_000) { !session.historyLoading&&session.messages.size==120 };assertEquals(2,calls.get());assertEquals(120,session.messages.map { it.id }.distinct().size)
    }
}
