package com.agenthub.android

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Production Session/Compose/Keystore with synthetic authenticated snapshots and metadata acknowledgements only.
 * No real Host, model, microphone or device installation is performed by this fixture.
 */
class NativePhoneReadingSessionTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private var display by mutableStateOf(false)
    private var host="reading-a"
    private var latest=1L
    private var body=1L
    private var earlier=false
    private var foreignRead=false
    private val acknowledgements=mutableListOf<NativePendingRead>()
    private var ackGate: CompletableDeferred<Unit>?=null
    private var ackConflict=false
    private fun scope(id: String)=TargetScope("codex",null,null,id,"workspace","runtime")
    private fun position(n: Long)=ThreadReadPosition(n,"message-$n",ThreadReadPosition.contentVersion("message-$n","reply-$n 中文 😀","completed"))
    private fun thread(id: String,name: String)=JSONObject().put("hostId",id).put("id",name).put("title",name).put("state","idle").put("hasUnreadTurn",true)
        .put("readState",ThreadReadState(position(latest),if(foreignRead)"b".repeat(64) else null,if(foreignRead)position(latest) else null).json())
    private val reader=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            val id=client.id.ifEmpty { host }
            return HostReadCodec.workspace(JSONObject().put("hostId",id).put("name",id).put("readTarget",CodexCommandCodec.target(scope(id)))
                .put("connectionMode","history-only").put("capabilities",JSONArray().put("threads:read-state"))
                .put("projects",JSONArray()).put("threads",JSONArray().put(thread(id,"thread")).put(thread(id,"other-thread"))).toString())
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            val json=JSONObject().put("hostId",client.id).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(client.scope!!))
                .put("thread",thread(client.id,threadId)).put("messages",JSONArray().put(JSONObject().put("id","message-$body").put("role","assistant").put("text","reply-$body 中文 😀").put("status","completed")))
            if(earlier)json.put("historyWindow",JSONObject().put("version",1).put("kind","earlier")).put("historyRevision",1)
            return HostReadCodec.history(json.toString())
        }
    }
    private val commands=object: CodexCommands by NativeCodexCommands({ error("No unrelated native command is allowed") }) {
        override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead): JSONObject {
            acknowledgements.add(request);ackGate?.await()
            if(ackConflict)throw CodexCommandError("newer reply",409)
            return JSONObject().put("hostId",client.id).put("threadId",request.threadId).put("target",CodexCommandCodec.target(request.target))
                .put("readerId",request.readerId).put("read",request.position.json())
        }
    }
    private fun main(action: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { action() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"phone-reading-session") }
        session=MobileSession(owned.storage,reader,commands)
        main { session.resetAcceptance(reader,commands) }
        ui.runOnIdle { ui.activity.setContent { if(display)AgentHubApp(session) else Text("reading fixture hidden") } }
    }
    @After fun stop() { ackGate?.complete(Unit);if(::session.isInitialized)ui.runOnIdle { session.leaveForeground() };if(::owned.isInitialized)ui.runOnIdle { owned.verifyDefault() } }
    private fun connect(id: String="reading-a") {
        host=id;main { session.connect(id,"https://fixture.invalid","") }
        ui.waitUntil(10_000) { !session.loading && !session.phoneReading.busy }
    }
    private fun select() {
        ui.runOnIdle { session.selectThread(session.threads.first { it.id=="thread" });session.text="preserved draft" }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.isNotEmpty() && !session.phoneReading.busy }
    }
    @Test fun actualVisibleBodyRecordsPhoneReadAndOnlyMetadataAcknowledgement() {
        connect();select();ui.waitUntil(10_000) { session.phoneUnreadCount==2 }
        assertTrue(acknowledgements.isEmpty())
        ui.runOnIdle { display=true }
        ui.waitUntil(10_000) { session.phoneUnreadCount==1 && acknowledgements.size==1 }
        ui.onNodeWithTag("phone-reading-content-end").assertIsDisplayed()
        assertEquals(position(1),acknowledgements.single().position);assertEquals("preserved draft",session.text)
        ui.runOnIdle { session.refresh();session.loadHistory() }
        ui.waitUntil(10_000) { !session.loading && !session.historyLoading && !session.phoneReading.busy }
        assertEquals(1,session.phoneUnreadCount)
    }
    @Test fun newerHeadOldPageForeignReadAndBackgroundCannotClearPhone() {
        latest=2;body=1;foreignRead=true;connect();select()
        ui.runOnIdle { session.setPhoneReadingSurface(true) }
        assertNull(session.visiblePhoneReading);assertEquals(2,session.phoneUnreadCount);assertTrue(acknowledgements.isEmpty())
        body=2;main { session.loadHistory()?.join() };ui.waitUntil(10_000) { !session.phoneReading.busy }
        var captured: NativeVisiblePhoneReading?=null
        ui.runOnIdle { captured=session.visiblePhoneReading;assertNotNull(captured);session.leaveForeground();session.observeVisiblePhoneReading(captured!!,true) }
        assertTrue(acknowledgements.isEmpty());assertEquals(2,session.phoneUnreadCount)
    }
    @Test fun coveredSurfaceAndEarlierPageDoNotProduceALease() {
        connect();select()
        var old: NativeVisiblePhoneReading?=null
        ui.runOnIdle { session.setPhoneReadingSurface(true);old=session.visiblePhoneReading;assertNotNull(old);session.setPhoneReadingSurface(false) }
        assertNull(session.visiblePhoneReading);assertTrue(acknowledgements.isEmpty())
        earlier=true;main { session.loadHistory()?.join() }
        ui.runOnIdle { session.setPhoneReadingSurface(true);assertNull(session.visiblePhoneReading);session.observeVisiblePhoneReading(old!!,true) }
        assertTrue(acknowledgements.isEmpty());assertEquals(2,session.phoneUnreadCount)
        // A new accepted latest page restores authorization; merely uncovering the earlier replacement does not.
        earlier=false;main { session.loadHistory()?.join();session.phoneReading.awaitReconciliation() }
        ui.runOnIdle { assertNotNull(session.visiblePhoneReading) }
        assertEquals(2,session.phoneUnreadCount)
    }
    @Test fun countsIncludeOtherSavedComputerAndRemovingOnePreservesItsReadTombstone() {
        connect();select();ui.runOnIdle { session.setPhoneReadingSurface(true);session.observeVisiblePhoneReading(session.visiblePhoneReading!!,true) }
        ui.waitUntil(10_000) { session.phoneUnreadCount==1 }
        connect("reading-b");ui.waitUntil(10_000) { session.phoneUnreadCount==3 }
        val first=session.clients.first { it.id=="reading-a" }
        assertEquals(1,session.unreadConversationCount(first));assertEquals(2,session.unreadConversationCount(session.selectedClient))
        ui.runOnIdle { session.removeClient("reading-a") };ui.waitUntil(10_000) { session.phoneUnreadCount==2 }
        connect("reading-a");ui.waitUntil(10_000) { session.phoneUnreadCount==3 }
        assertEquals(1,session.unreadConversationCount(session.selectedClient))
    }
    @Test fun staleHost409SettlesOnlyOriginalPositionAndKeepsTheNewReplyUnread() {
        ackGate=CompletableDeferred();ackConflict=true;connect();select()
        ui.runOnIdle { session.setPhoneReadingSurface(true);session.observeVisiblePhoneReading(session.visiblePhoneReading!!,true) }
        ui.waitUntil(10_000) { acknowledgements.size==1 && session.phoneUnreadCount==1 }
        latest=2;body=2;main { session.refresh();session.loadHistory()?.join() }
        ui.waitUntil(10_000) { !session.loading && !session.phoneReading.busy && session.phoneUnreadCount==2 }
        ackGate!!.complete(Unit)
        ui.waitUntil(10_000) { runBlocking { withContext(Dispatchers.IO) { session.phoneReading.ledger!!.pendingReads().isEmpty() } } }
        assertEquals(2,session.phoneUnreadCount);assertEquals(1,acknowledgements.size);assertEquals("preserved draft",session.text)
    }
}
