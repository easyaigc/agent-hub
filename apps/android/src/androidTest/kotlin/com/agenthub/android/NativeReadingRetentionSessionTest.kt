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

/** Actual reading registration/storage over many synthetic large chats; no real Host/model or heap-size claim. */
class NativeReadingRetentionSessionTest {
    @get:Rule(order=0) val limit: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private val reply=LinkedHashMap<String,MobileMessage>()
    private val positions=LinkedHashMap<String,ThreadReadPosition>()
    private var host="retention-a"
    private var earlier=false
    private val ack=CompletableDeferred<Unit>()
    private var ackCalls=0
    private fun target(id: String)=TargetScope("codex",null,null,id,"workspace","runtime")
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            val id=client.id.ifEmpty { host }
            return HostReadCodec.workspace(JSONObject().put("hostId",id).put("name",id).put("connectionMode","history-only")
                .put("readTarget",CodexCommandCodec.target(target(id))).put("capabilities",JSONArray().put("threads:read-state"))
                .put("projects",JSONArray()).put("threads",JSONArray(reply.keys.map { thread -> JSONObject().put("hostId",id).put("id",thread)
                    .put("title",thread).put("state","idle").put("hasUnreadTurn",true).put("readState",ThreadReadState(positions.getValue(thread)).json()) })).toString())
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            val message=reply.getValue(threadId)
            // Deliberately large raw wire as well as messages; metadata-only copies must not retain this either.
            val wire=JSONObject().put("hostId",client.id).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(client.scope!!))
                .put("messages",JSONArray()).put("synthetic-wire-sentinel",message.text).toString()
            return MobileHistory(client.id,threadId,listOf(message),null,client.scope,"idle",null,wire,
                historyWindowKind=if(earlier)"earlier" else null,
                readState=if(threadId.removePrefix("thread-").toInt()%2==0)null else ThreadReadState(positions.getValue(threadId)))
        }
    }
    private val commands=object: CodexCommands by NativeCodexCommands({ error("No unrelated native command") }) {
        override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead): JSONObject {
            ackCalls++;ack.await()
            return JSONObject().put("hostId",client.id).put("threadId",request.threadId).put("target",CodexCommandCodec.target(request.target))
                .put("readerId",request.readerId).put("read",request.position.json())
        }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        repeat(12) { n ->
            val message=MobileMessage("message-$n","assistant","reply-$n "+"中文😀".repeat(65_536),"completed")
            reply["thread-$n"]=message;positions["thread-$n"]=ThreadReadPosition(1,message.id,ThreadReadPosition.contentVersion(message.id,message.text,"completed"))
        }
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"reading-retention");session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands)
            ui.activity.setContent { Text("Owned reading retention fixture") } }
        main { session.connect(host,"https://fixture.invalid","") }
        ui.waitUntil(15_000) { !session.loading && !session.phoneReading.busy }
    }
    @After fun finish() { ack.complete(Unit);if(::session.isInitialized)ui.runOnIdle { session.leaveForeground() };if(::owned.isInitialized)ui.runOnIdle { owned.verifyDefault() } }
    @Test fun manyLargeChatsKeepOnlyCurrentBodyAndDurableCountsReadsAndPendingAcknowledgement() {
        reply.keys.forEach { thread ->
            ui.runOnIdle { session.selectThread(session.threads.first { it.id==thread }) }
            ui.waitUntil(15_000) { !session.historyLoading && session.messages.singleOrNull()?.id==reply.getValue(thread).id && !session.phoneReading.busy }
            main { session.phoneReading.awaitReconciliation() }
            val retained=NativeReadingRetainedGraph.session(session)
            assertEquals("Reading roots must not retain any full history or wire",0,retained.histories)
            assertEquals(0,retained.messages);assertEquals(0,retained.largeStrings)
            assertTrue(session.phoneReading.heads.size<=1);assertTrue(session.phoneReading.legacyPositionCount<=64)
            assertEquals(12,session.phoneUnreadCount)
        }
        ui.runOnIdle { session.setPhoneReadingSurface(true);session.observeVisiblePhoneReading(session.visiblePhoneReading!!,true) }
        ui.waitUntil(15_000) { session.phoneUnreadCount==11 && ackCalls==1 }
        assertEquals(1,NativeReadingRetainedGraph.session(session).largeStrings)
        main { withContext(Dispatchers.IO) { assertEquals(1,session.phoneReading.ledger!!.pendingReads().size) } }
        earlier=true;main { session.loadHistory()?.join() }
        ui.runOnIdle { assertNull(session.visiblePhoneReading) }
        assertEquals(11,session.phoneUnreadCount);assertEquals(1,ackCalls)
        assertEquals(0,NativeReadingRetainedGraph.session(session).largeStrings)
        main { withContext(Dispatchers.IO) { assertEquals(1,session.phoneReading.ledger!!.pendingReads().size) } }
        earlier=false;main { session.loadHistory()?.join();session.phoneReading.awaitReconciliation() }
        ui.runOnIdle { assertNotNull(session.visiblePhoneReading) }
        ui.runOnIdle { session.setPhoneReadingSurface(false);session.leaveForeground() }
        assertEquals(0,NativeReadingRetainedGraph.session(session).largeStrings)
        main { session.phoneReading.reconcile(listOf(target(host),target("retention-offline")),listOf(
            NativeReadingSnapshot(target("retention-offline"),"offline-thread",ThreadReadPosition(1,"offline-message","a".repeat(64)),true)),emptyList());session.phoneReading.awaitReconciliation() }
        assertEquals(12,session.phoneUnreadCount)
        main { withContext(Dispatchers.IO) {
            val reopened=NativeReadingLedger(owned.storage)
            assertEquals(12,reopened.snapshot().count);assertEquals(1,reopened.snapshot().count(target("retention-offline")))
            assertEquals(1,reopened.pendingReads().size)
        } }
        assertEquals(0,NativeReadingRetainedGraph.session(session).largeStrings)
    }
}
