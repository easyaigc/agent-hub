package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real Session/store and Keystore; all directory data and suspended transports are synthetic. */
class NativeReadingDirectorySessionTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private var offlineA=false
    private var extraB=false
    private var blockB=false
    private val entered=CompletableDeferred<Unit>()
    private val gate=CompletableDeferred<Unit>()
    private val ackGate=CompletableDeferred<Unit>()
    private var histories=0
    private var acknowledgements=0
    private fun target(id: String)=TargetScope("codex",null,null,id,"workspace","runtime")
    private fun position(id: String)=ThreadReadPosition(1,id,ThreadReadPosition.contentVersion(id,"body","completed"))
    private val reader=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            assertNull("Unread traversal must not paginate projects",projectCursor)
            val id=client.id.ifEmpty { if(client.endpoint!!.contains("directory-a"))"a" else "b" }
            if(id=="a" && offlineA)throw HostReadError("offline fixture")
            if(id=="b" && blockB && threadCursor=="page-2") { entered.complete(Unit);withContext(NonCancellable) { gate.await() } }
            val index=when(threadCursor) { null->0;"page-2"->1;"page-3"->2;else->error("Cursor escaped its traversal") }
            val ids=if(id=="b" && extraB && index==2)listOf(2,3) else listOf(index)
            val threads=JSONArray(ids.map { n -> val thread="$id-thread-$n";JSONObject().put("hostId",id).put("id",thread)
                .put("title",thread).put("state","idle").put("hasUnreadTurn",true).put("readState",ThreadReadState(position(thread)).json()) })
            return HostReadCodec.workspace(JSONObject().put("hostId",id).put("name",id).put("connectionMode","history-only")
                .put("readTarget",CodexCommandCodec.target(target(id))).put("capabilities",JSONArray().put("threads:read-state"))
                .put("projects",JSONArray()).put("threads",threads).put("nextThreadCursor",if(index<2)"page-${index+2}" else JSONObject.NULL).toString())
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory { histories++;error("Directory cannot request a transcript") }
    }
    private val commands=object: CodexCommands by NativeCodexCommands({ error("No unrelated command") }) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead): JSONObject {
            acknowledgements++;ackGate.await();error("Leave original metadata ACK pending")
        }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"reading-directory");session=MobileSession(owned.storage,reader,commands);session.resetAcceptance(reader,commands)
            ui.activity.setContent { Text("Owned directory fixture") } }
        main { session.connect("A","https://directory-a.invalid","");session.refreshPhoneReadingDirectory(force=true)?.join()
            session.connect("B","https://directory-b.invalid","");session.refreshPhoneReadingDirectory(force=true)?.join();session.phoneReading.awaitReconciliation() }
        ui.runOnIdle { session.text="preserved directory draft" }
        assertEquals(6,session.phoneUnreadCount)
    }
    @After fun finish() { gate.complete(Unit);ackGate.complete(Unit);if(::session.isInitialized)ui.runOnIdle { session.leaveForeground() };if(::owned.isInitialized)ui.runOnIdle { owned.verifyDefault() } }
    @Test fun unseenPagesAcrossSavedComputersCountWithoutHistoryAndOfflineReadPendingSurvives() {
        assertEquals(1,session.threads.size);assertEquals("b",session.selectedClient.id);assertEquals("",session.selectedThread)
        val a=session.clients.first { it.id=="a" };val lifecycle=NativeReadingLifecycle()
        lifecycle.foreground(true);lifecycle.uncovered(true);lifecycle.present(target("a"),"a-thread-2",position("a-thread-2"))
        val lease=lifecycle.visible(target("a"),"a-thread-2","a-thread-2","body","completed",true)!!
        main { val value=withContext(Dispatchers.IO) { session.phoneReading.ledger!!.readVisible(target("a"),"a-thread-2",nativeReaderId(a.readerId!!),lifecycle,lease)!! };session.phoneReading.publish(value) }
        offlineA=true;extraB=true
        main { session.refreshPhoneReadingDirectory(force=true)?.join();session.phoneReading.awaitReconciliation() }
        assertEquals(6,session.phoneUnreadCount);assertEquals(2,session.unreadConversationCount(a));assertEquals(4,session.unreadConversationCount(session.selectedClient))
        assertTrue(session.phoneReadingDirectoryFailures.containsKey(ThreadReadingIdentity.scopeKey(target("a"))))
        assertEquals(3,session.phoneReadingDirectoryCompleted[ThreadReadingIdentity.scopeKey(target("b"))])
        assertEquals(0,histories);assertEquals("b",session.selectedClient.id);assertEquals("",session.selectedThread);assertEquals("preserved directory draft",session.text)
        main { withContext(Dispatchers.IO) { assertEquals(1,session.phoneReading.ledger!!.pendingReads().size);assertEquals(6,NativeReadingLedger(owned.storage).snapshot().count) } }
    }
    @Test fun concurrentTriggersCoalesceAndBackgroundRejectsLatePage() {
        blockB=true;var job: Job?=null
        main { job=session.refreshPhoneReadingDirectory(force=true) }
        ui.waitUntil(15_000) { entered.isCompleted }
        main { assertSame(job,session.refreshPhoneReadingDirectory(force=true));session.leaveForeground();gate.complete(Unit);job!!.join() }
        assertEquals(6,session.phoneUnreadCount);assertTrue(session.phoneReadingDirectoryCompleted.isEmpty());assertTrue(session.phoneReadingDirectoryFailures.isEmpty())
        assertEquals(0,histories);assertEquals(0,acknowledgements);assertEquals("preserved directory draft",session.text)
    }
}
