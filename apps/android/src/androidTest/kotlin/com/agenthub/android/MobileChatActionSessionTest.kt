package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileChatActionSessionTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun ready(fixture: ChatManagementFixture,name: String): MobileSession {
        val session=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,name),NativeHostReads { "" }).also { it.restore() } }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
        withContext(Dispatchers.Main) {
            session.enterForeground()
            session.connect("management fixture",fixture.server.endpoint,"")
            session.selectThread(session.threads.first { it.id==ChatManagementFixture.first })
        }
        wait { session.currentChat!=null&&session.canManageChat(session.currentChat)&&!session.historyLoading }
        return session
    }
    private suspend fun wait(predicate: ()->Boolean)=withTimeout(5_000) { while(!withContext(Dispatchers.Main) { predicate() })delay(10) }
    @Test fun pendingClientScopedReceiptRestoresWhileAnotherChatAndDraftAreSelected()=runBlocking {
        ChatManagementFixture().use { f ->
            val name="action-session-"+UUID.randomUUID();val session=ready(f,name)
            try {
                f.postStatus=500
                withContext(Dispatchers.Main) { session.text="first draft";session.manageChat("archive") }
                wait { session.chatActionRecords.any { it.phase=="uncertain" } }
                val original=withContext(Dispatchers.Main) { session.chatActionRecords.single() }
                withContext(Dispatchers.Main) {
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="other current draft"
                    session.endChatActionWaiting(original)
                }
                wait { session.chatActionRecords.single().detached }
                withTimeout(5_000) { while(!NativeSessionStorage(context,name).metadata()?.toString()?.contains("other current draft").orFalse())delay(10) }
                withContext(Dispatchers.Main) { session.leaveForeground() }
                val restored=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,name),NativeHostReads { "" }).also { it.restore() } }
                try {
                    wait { !restored.restoring&&restored.chatActionRecords.isNotEmpty() }
                    withContext(Dispatchers.Main) { restored.enterForeground() }
                    assertEquals(ChatManagementFixture.other,restored.selectedThread);assertEquals("other current draft",restored.text)
                    val record=restored.chatActionRecords.single();assertEquals(original.intent,record.intent);assertTrue(record.detached)
                    f.getStatus=404;withContext(Dispatchers.Main) { restored.queryChatAction(record) };delay(150)
                    assertEquals(1,f.posts.size);assertEquals("uncertain",restored.chatActionRecords.single().phase)
                    f.getStatus=200;f.phase="confirmed"
                    withContext(Dispatchers.Main) { restored.queryChatAction(record) }
                    wait { restored.chatActionRecords.single().phase=="confirmed" }
                    assertEquals(ChatManagementFixture.other,restored.selectedThread);assertEquals("other current draft",restored.text)
                    assertEquals(1,f.posts.size);assertEquals(original.intent,restored.chatActionRecords.single().intent)
                } finally { withContext(Dispatchers.Main) { restored.leaveForeground() } }
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun lateDeleteConfirmationDoesNotNavigateOrClearAnotherChatsNewDraft()=runBlocking {
        ChatManagementFixture().use { f ->
            val session=ready(f,"action-session-"+UUID.randomUUID());val started=CountDownLatch(1);val release=CountDownLatch(1)
            f.started=started;f.release=release
            try {
                withContext(Dispatchers.Main) { session.text="original draft";session.manageChat("delete");session.manageChat("delete") }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="new other draft" }
                release.countDown();wait { session.chatActionRecords.any { it.phase=="confirmed" } }
                assertEquals(ChatManagementFixture.other,session.selectedThread);assertEquals("new other draft",session.text)
                assertNull(session.error);assertEquals(1,f.posts.size)
            } finally { release.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun capturedDeleteContextIsInvalidAfterThreadSwitchOrCapabilityWithdrawal()=runBlocking {
        ChatManagementFixture().use { f ->
            val session=ready(f,"action-session-"+UUID.randomUUID())
            try {
                val captured=withContext(Dispatchers.Main) { session.captureChatManagement()!! }
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="new draft";session.manageChat("delete",captured) }
                delay(100);assertTrue(f.posts.isEmpty());assertEquals("new draft",session.text)
                f.supported=false;withContext(Dispatchers.Main) { session.refresh(userInitiated=true) }
                wait { !session.supportsChatManagement&&!session.loading }
                withContext(Dispatchers.Main) { session.manageChat("archive") }
                delay(100);assertTrue(f.posts.isEmpty());assertTrue(session.chatActionRecords.isEmpty())
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun oldCatalogCannotUndoConfirmedArchiveAndNewReadStartsBeforeOldRelease()=runBlocking {
        ChatManagementFixture().use { f ->
            val session=ready(f,"action-session-"+UUID.randomUUID());val started=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                f.workspaceStarted=started;f.workspaceRelease=release
                withContext(Dispatchers.Main) { session.refresh(userInitiated=true) }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { session.manageChat("archive") }
                wait { session.chatActionRecords.any { it.phase=="confirmed" } }
                // A fresh post-confirmation catalog must not wait behind the held old one.
                wait { session.currentChat?.archived==true&&!session.loading&&f.workspaceReadsAfterHold>0 }
                release.countDown();delay(150)
                assertTrue(session.currentChat?.archived==true);assertFalse(session.canCompose)
                assertEquals(1,f.posts.size)
            } finally { release.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun lateRejectedActionCannotOverwriteErrorAfterRoundTripNavigation()=runBlocking {
        ChatManagementFixture().use { f ->
            val session=ready(f,"action-session-"+UUID.randomUUID());val started=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                f.phase="rejected";f.started=started;f.release=release
                withContext(Dispatchers.Main) { session.manageChat("archive") }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.other })
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.first })
                    session.text="new visit draft";session.error="new visit notice"
                }
                release.countDown();wait { session.chatActionRecords.any { it.phase=="rejected" } }
                delay(150);assertEquals("new visit notice",session.error);assertEquals("new visit draft",session.text);assertEquals(1,f.posts.size)
            } finally { release.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun lateOriginalQueriesCannotOverwriteErrorAfterRoundTripNavigation()=runBlocking {
        for(status in listOf(200,404))ChatManagementFixture().use { f ->
            val session=ready(f,"action-session-"+UUID.randomUUID());val started=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                f.phase="outcome-unknown";withContext(Dispatchers.Main) { session.manageChat("archive") }
                wait { session.chatActionRecords.any { it.phase=="outcome-unknown" } }
                val original=session.chatActionRecords.single();f.phase="rejected";f.getStatus=status;f.queryStarted=started;f.queryRelease=release
                val query=withContext(Dispatchers.Main) { session.queryChatAction(original)!! }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.other })
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.first })
                    session.text="new query visit draft";session.error="new query visit notice"
                }
                release.countDown();withTimeout(5_000) { query.join() }
                if(status==200)wait { session.chatActionRecords.single().phase=="rejected" }
                else assertEquals("outcome-unknown",session.chatActionRecords.single().phase)
                assertEquals("new query visit notice",session.error);assertEquals("new query visit draft",session.text)
                assertEquals(original.intent,session.chatActionRecords.single().intent);assertEquals(1,f.posts.size)
            } finally { release.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    private fun Boolean?.orFalse()=this==true
}

internal class ChatManagementFixture: java.io.Closeable {
    companion object { const val first="thread-actions-01";const val other="thread-actions-02" }
    val target=TargetScope("codex",null,null,"host-session-actions","workspace-actions","runtime-actions-"+UUID.randomUUID())
    @Volatile var extraThreads: List<String> = emptyList()
    @Volatile var writableHistory=false
    @Volatile var archivePaging=false;@Volatile var rejectArchivePage=false
    @Volatile var batchSupported=false;@Volatile var batchPostStatus=200;@Volatile var batchGetStatus=200
    @Volatile var batchPatch: ((JSONObject)->Unit)?=null
    @Volatile var batchObserve: ((JSONObject)->Unit)?=null
    val batchStates=ConcurrentHashMap<String,String>()
    val batchArchived=ConcurrentHashMap.newKeySet<String>()
    private val batches=ConcurrentHashMap<String,String>()
    @Volatile var supported=true;@Volatile var archived=false;@Volatile var deleted=false;@Volatile var pinned=false
    @Volatile var state="running";@Volatile var lastTurnStatus: String?=null
    @Volatile var phase="confirmed";@Volatile var postStatus=200;@Volatile var getStatus=200
    @Volatile var started: CountDownLatch?=null;@Volatile var release: CountDownLatch?=null
    @Volatile var queryStarted: CountDownLatch?=null;@Volatile var queryRelease: CountDownLatch?=null
    @Volatile var workspaceStarted: CountDownLatch?=null
    @Volatile var workspaceRelease: CountDownLatch?=null
    @Volatile var workspaceReadsAfterHold=0
    private val actions=ConcurrentHashMap<String,String>()
    private fun parameter(request: WireRequest,key: String)=URI(request.pathWithQuery).rawQuery?.split('&')?.map { it.split('=',limit=2) }?.firstOrNull { it[0]==key }?.let { URLDecoder.decode(it[1],"UTF-8") }
    fun workspace()=JSONObject().put("hostId",target.hostId).put("name","management fixture").put("readTarget",CodexCommandCodec.target(target))
        .put("connectionMode","desktop-runtime").put("runtimeConnected",true).put("capabilities",JSONArray((if(supported)listOf("threads:manage") else emptyList<String>())+(if(writableHistory)listOf("reply","commands:target-bound:v1","queue","stop","generation-settings","plan-mode")else emptyList<String>())+(if(batchSupported)listOf("threads:archive:unloaded")else emptyList<String>())))
        .put("projects",JSONArray()).put("pinnedThreadIds",JSONArray(if(pinned)listOf(first) else emptyList<String>()))
        .put("threads",JSONArray((listOf(first,other)+extraThreads).filter { !deleted||it!=first }.map { id ->
            JSONObject().put("id",id).put("hostId",target.hostId).put("title",if(id==first)"Original managed chat" else "Other managed chat")
                .put("state",if(id==first)state else "idle").put("archived",id==first&&archived||id in batchArchived).also { if(id==first&&lastTurnStatus!=null)it.put("lastTurnStatus",lastTurnStatus) }
        }))
    val server=CommandFixture { request -> when(request.path) {
        "/v1/workspace","/host"->{
            val page=archivePaging&&parameter(request,"threadCursor")!=null
            val snapshot=workspace().apply {
                if(archivePaging&&!page)put("nextThreadCursor","archive-page-two")
                if(page)put("threads",JSONArray().put(JSONObject().put("id","thread-archive-page-two").put("hostId",target.hostId).put("title","Older archived chat").put("state","unknown").put("archived",true).put("lastTurnStatus","failed")))
            }
            val body=snapshot.toString()
            if(page&&rejectArchivePage)return@CommandFixture WireResponse(500,JSONObject().put("error","分页暂时不可用").toString())
            val hold=workspaceRelease
            if(request.path=="/v1/workspace"&&hold!=null) {
                workspaceRelease=null;workspaceStarted?.countDown();check(hold.await(15,TimeUnit.SECONDS))
            } else if(workspaceStarted?.count==0L)workspaceReadsAfterHold++
            WireResponse(body=body)
        }
        "/v1/history"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId",parameter(request,"threadId"))
            .put("readTarget",CodexCommandCodec.target(target)).put("state","idle").put("messages",JSONArray().put(JSONObject().put("id","message-actions").put("role","assistant").put("text","Original visible history"))).apply { if(writableHistory)put("runtime",JSONObject().put("state",state).put("turnId","turn-archive-authority").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL)) }.toString())
        "/v1/models"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
        "/v1/workspace-management"->{
            val post=request.method=="POST";val id=if(post)JSONObject(String(request.body)).getString("clientOperationId")else parameter(request,"id")!!
            if(post) { batches[id]=String(request.body);batchObserve?.invoke(JSONObject(String(request.body)));started?.countDown();release?.let { check(it.await(15,TimeUnit.SECONDS)) } }
            else { queryStarted?.countDown();queryRelease?.let { check(it.await(15,TimeUnit.SECONDS)) } }
            val status=if(post)batchPostStatus else batchGetStatus
            if(status!=200)WireResponse(status,JSONObject().put("error","归档结果待核对").toString()) else {
                val result=JSONObject(batches[id] ?: error("unknown original batch"))
                result.put("items",JSONArray(result.getJSONArray("ids").strings().map { item ->
                    val state=batchStates[item] ?: "completed";if(state=="completed")batchArchived.add(item)
                    JSONObject().put("id",item).put("state",state).put("error",if(state=="rejected")"请在桌面端操作"else JSONObject.NULL)
                }));batchPatch?.invoke(result);WireResponse(body=result.toString())
            }
        }
        "/v1/threads/actions"->{
            val post=request.method=="POST"
            val id=if(post)JSONObject(String(request.body)).getString("clientOperationId") else parameter(request,"id")!!
            if(post) { actions[id]=String(request.body);started?.countDown();release?.let { check(it.await(15,TimeUnit.SECONDS)) } }
            if(!post) { queryStarted?.countDown();queryRelease?.let { check(it.await(15,TimeUnit.SECONDS)) } }
            val status=if(post)postStatus else getStatus
            if(status!=200)WireResponse(status,JSONObject().put("error","电脑端拒绝了此次操作：请在桌面端操作。对话未改变。").toString())
            else { val value=JSONObject(actions[id]!!)
                if(phase=="confirmed")when(value.getString("action")) { "archive"->archived=true;"unarchive"->archived=false;"pin"->pinned=true;"unpin"->pinned=false;"delete"->deleted=true }
                WireResponse(body=value.put("phase",phase).put("error",if(phase=="rejected")"电脑端拒绝了此次操作：对话未改变。" else JSONObject.NULL).toString())
            }
        }
        else->WireResponse(404)
    } }
    val posts get()=server.requests.filter { it.method=="POST" }
    override fun close() { release?.countDown();queryRelease?.countDown();workspaceRelease?.countDown();server.close() }
}
