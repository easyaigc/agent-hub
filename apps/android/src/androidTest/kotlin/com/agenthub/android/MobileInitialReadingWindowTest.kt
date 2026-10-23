package com.agenthub.android

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

/** Initial rendering budget differs from the sixty-message earlier-history increment. */
@RunWith(AndroidJUnit4::class)
class MobileInitialReadingWindowTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val requests=AtomicInteger()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup(tools: Boolean=false) {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId","initial-window-host").put("name","synthetic initial window").put("connectionMode","history-only")
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("window-a","window-b").map {
                    JSONObject().put("id",it).put("hostId","initial-window-host").put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                requests.incrementAndGet();return HostReadCodec.history(JSONObject().put("hostId",client.id).put("threadId",threadId)
                    .put("messages",JSONArray((0 until 120).map { JSONObject().put("id","$threadId-$it").put("role",if(tools&&it in 80..105)"tool" else "user")
                        .put("text","reading-row-$it").put("status","completed") })).toString())
            }
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reader) }
        main { session.connect("synthetic initial window","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="initial window draft" }
        ui.waitUntil(5_000) { !session.historyLoading&&session.messages.size==120 }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun firstRenderUsesLastTwentySourceMessagesWithoutDeletingHistory() {
        setup();assertEquals(20,session.visibleMessageLimit);assertEquals(120,session.messages.size)
        assertEquals((100 until 120).toList(),session.timeline.rows.flatMap { it.indices });assertTrue(session.timeline.hasEarlierMessages);assertEquals(1,requests.get())
    }
    @Test fun nativeEarlierControlAddsSixtyAndRetainsPreviousFirstMessageAnchor() {
        setup();assertEquals(20,session.visibleMessageLimit)
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("message-window-a-100").assertIsDisplayed()
        ui.onNodeWithTag("history-local-more").performClick();ui.waitUntil(5_000) { session.visibleMessageLimit==80 }
        ui.onNodeWithTag("message-window-a-100").assertIsDisplayed()
        assertEquals((40 until 120).toList(),session.timeline.rows.flatMap { it.indices });assertEquals(1,requests.get());assertEquals("initial window draft",session.text)
    }
    @Test fun initialBudgetKeepsAnIntersectingToolGroupWhole() {
        setup(tools=true);assertEquals(20,session.visibleMessageLimit)
        val first=session.timeline.rows.first();assertEquals("tools",first.kind);assertEquals((80..105).toList(),first.indices)
        assertEquals((80 until 120).toList(),session.timeline.rows.flatMap { it.indices });assertTrue(session.timeline.hasEarlierMessages)
    }
    @Test fun sameTargetRetainsExpansionAndChangedTargetResetsToTwenty() {
        setup();ui.runOnIdle { session.earlierMessages() };val expanded=session.visibleMessageLimit
        ui.runOnIdle { session.select(session.selectedClient) };ui.waitUntil(5_000) { !session.loading&&!session.historyLoading }
        assertEquals(expanded,session.visibleMessageLimit);assertEquals("initial window draft",session.text)
        ui.runOnIdle { session.selectThread(session.threads.last()) };ui.waitUntil(5_000) { !session.historyLoading }
        assertEquals(20,session.visibleMessageLimit);assertFalse(session.revealedEarlier);assertEquals("",session.text)
    }
}
