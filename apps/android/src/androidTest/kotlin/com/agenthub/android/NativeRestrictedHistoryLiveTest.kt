package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Native acceptance against an isolated Host, using its actual history and event APIs. */
class NativeRestrictedHistoryLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun restrictedHistoryDoesNotRetryAndAuthorizedChatCanCompose() {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val path=File(i.targetContext.filesDir,"acceptance/restricted-history-live.json")
        Assume.assumeTrue("Isolated Host configuration required",path.exists())
        val f=JSONObject(path.readText(Charsets.UTF_8))
        val endpoint=f.getString("endpoint")
        require(java.net.URI(endpoint).host=="127.0.0.1")
        val restricted=f.getString("restrictedThread")
        val authorized=f.getString("authorizedThread")
        val token=f.getString("token")
        val historyReads=AtomicInteger()
        val timeoutOnce=java.util.concurrent.atomic.AtomicBoolean(false)
        val base=NativeHostReads { token }
        val reader=object: HostReads by base {
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(threadId==restricted&&cursor==null) historyReads.incrementAndGet()
                if(threadId==authorized&&timeoutOnce.compareAndSet(true,false))
                    throw HostReadError("Acceptance timeout",java.net.SocketTimeoutException(),true)
                return base.history(client,threadId,cursor)
            }
        }
        val session=ui.activity.session
        ui.waitUntil(30_000) { !session.restoring }
        ui.runOnIdle { session.resetAcceptance(reader,NativeCodexCommands { token });session.changeLanguage("en") }
        try {
            runBlocking { withContext(Dispatchers.Main) { session.connect("USB isolated Host",endpoint,token) } }
            ui.waitUntil(45_000) { !session.loading&&session.threads.any { it.id==restricted }&&session.threads.any { it.id==authorized } }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id==restricted }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.conversationSyncIssue?.readOnly==true&&session.messages.isNotEmpty() }
            ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("History only")
            ui.onNodeWithTag("conversation-sync-retry").assertDoesNotExist()
            ui.onNodeWithTag("message-input").assertDoesNotExist()
            assertFalse(session.isWritable)
            assertFalse(session.canCompose)
            val restrictionReads=historyReads.get()
            // Exceeds the eight second initial grace and the active catch-up interval.
            Thread.sleep(20_000)
            ui.waitForIdle()
            assertEquals(restrictionReads,historyReads.get())
            assertTrue(session.messages.isNotEmpty())
            ui.onNodeWithTag("conversation-sync-retry").assertDoesNotExist()
            ui.runOnIdle { session.selectThread(session.threads.single { it.id==authorized }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.runtime?.canControl==true&&session.isWritable }
            ui.onNodeWithTag("conversation-sync-retry").assertDoesNotExist()
            ui.onNodeWithTag("message-input").assertIsEnabled().performTextInput("Unsaved acceptance draft")
            assertEquals("Unsaved acceptance draft",session.text)
            ui.runOnIdle { session.leaveForeground() }
            ui.runOnIdle { timeoutOnce.set(true);session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.conversationSyncIssue?.readOnly==false }
            ui.onNodeWithTag("conversation-sync-retry").assertIsEnabled().performClick()
            ui.waitUntil(30_000) { !session.historyLoading&&session.conversationSyncIssue==null&&session.runtime?.canControl==true }
            assertEquals("Unsaved acceptance draft",session.text)
            assertTrue(session.operationRecords.isEmpty())
        } finally {
            ui.runOnIdle { session.leaveForeground() }
        }
    }
}
