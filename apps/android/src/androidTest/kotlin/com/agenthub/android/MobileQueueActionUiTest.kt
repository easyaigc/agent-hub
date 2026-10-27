package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual native UI/session/HTTP with only synthetic, original client queue items. */
class MobileQueueActionUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var isolated: MobileSession
    private val session get()=isolated
    private var originalDraftMetadata: Map<String,String?> = emptyMap()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val prior=NativeSessionStorage(context).metadata();originalDraftMetadata=listOf("drafts","draftFiles","draftVersions").associateWith { prior?.opt(it)?.toString() }
            isolated=MobileSession(NativeSessionStorage(context,"P4-queue-actions-"+UUID.randomUUID()));isolated.resetAcceptance()
            ui.activity.setContent { AgentHubApp(isolated) { } }
        }
    }
    @After fun preserveDefaultRealDraft() {
        if(::isolated.isInitialized)main { isolated.leaveForeground() }
        val current=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).metadata()
        for((key,value)in originalDraftMetadata)assertEquals("Queue controls preserve default $key",value,current?.opt(key)?.toString())
    }
    private fun connect(f: QueueActionFixture,sharing: Boolean=true) {
        main { session.connect("queue actions fixture",f.server.endpoint,"") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { session.runtime!=null&&!session.historyLoading&&session.canCompose }
        if(sharing)main { allowSyntheticRegressionSharing(session) }
    }
    private fun read(f: QueueActionFixture) {
        main { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading&&session.messages.singleOrNull()?.text==f.marker }
    }
    private fun actions(f: QueueActionFixture)=f.server.requests.filter { it.method=="POST" }
    private fun folder(): File {
        val run=InstrumentationRegistry.getArguments().getString("managementRunId")?:"queue-actions-local"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        return File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-queue-controls/$run").apply { mkdirs() }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
        val frames=CountDownLatch(1);i.runOnMainSync { window.decorView.postOnAnimation { window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());assertEquals(i.targetContext.packageName,window.decorView.context.packageName);bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val copied=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(copied.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-main-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).toString())
        }finally{image.recycle()}
    }
    @Test fun independentOriginalActionsDeduplicateWithoutLockingOrClearingTheDraft() {
        QueueActionFixture().use { f ->
            connect(f);val first=session.queuedReplies.first();val second=session.queuedReplies[1]
            f.hold(first.operationId);f.hold(second.operationId)
            try {
                ui.onNodeWithTag("cancel-queued-${first.operationId}").performClick()
                ui.waitUntil(5_000) { actions(f).size==1&&session.queueActionPending(first) }
                main { session.cancelQueue(first,session.targetKey) }
                ui.onNodeWithTag("execute-queued-${first.operationId}").assertIsNotEnabled()
                ui.onNodeWithTag("cancel-queued-${first.operationId}").assertIsNotEnabled()
                ui.onNodeWithTag("execute-queued-${second.operationId}").assertIsEnabled().performClick()
                ui.waitUntil(5_000) { actions(f).size==2&&session.queueActionPending(second) }
                main { session.executeQueue(second,session.targetKey,session.runtime?.turnId);session.text="new independent draft while two originals wait" }
                assertNull(session.transferStatus);assertTrue(session.canCompose)
                assertEquals(2,actions(f).size);assertEquals(setOf("/v1/queue/cancel","/v1/queue/execute"),actions(f).map { it.path }.toSet())
                f.release(first.operationId);ui.waitUntil(5_000) { !session.queueActionPending(first) }
                assertTrue(session.queueActionPending(second));assertEquals("new independent draft while two originals wait",session.text)
                f.release(second.operationId);ui.waitUntil(5_000) { !session.queueActionPending(second)&&!session.historyLoading }
                assertEquals(2,actions(f).size);assertTrue(actions(f).none { it.path=="/v1/replies" })
                assertEquals("new independent draft while two originals wait",session.text)
            } finally { f.releaseAll();main { session.leaveForeground() } }
        }
    }
    @Test fun currentMembershipHostTargetCapabilityAndTurnAreRecheckedBeforeDispatch() {
        QueueActionFixture().use { f ->
            connect(f);val first=session.queuedReplies.first();val key=session.targetKey
            main {
                session.cancelQueue(first.copy(hostId="foreign-host"),key)
                session.cancelQueue(first.copy(threadId="foreign-thread"),key)
                session.cancelQueue(first.copy(queueSource="host"),key)
                session.cancelQueue(first.copy(queuePresent=false),key)
                session.executeQueue(first,key+":previous-runtime",null)
                session.executeQueue(first,key,"stale-turn")
            }
            assertTrue(actions(f).isEmpty())
            f.blockFirst=true;f.marker="current-blocked";read(f)
            main { session.executeQueue(first,key,null) };assertTrue(actions(f).isEmpty())
            f.blockFirst=false;f.turn="current-run";f.marker="current-running";read(f)
            main { session.executeQueue(first,key,null) };assertTrue(actions(f).isEmpty())
            f.remove(first.operationId);f.marker="current-removed";read(f)
            main { session.cancelQueue(first,key);session.executeQueue(first,key,"current-run") }
            assertTrue(actions(f).isEmpty())
            main { session.leaveForeground() }
        }
    }
    @Test fun lateNavigationRoundTripAndNewerErrorCannotReceiveAnOldQueueFailure() {
        QueueActionFixture().use { f ->
            connect(f);val first=session.queuedReplies.first();f.hold(first.operationId);f.fail=true
            try {
                main { session.cancelQueue(first,session.targetKey) };ui.waitUntil(5_000) { actions(f).size==1 }
                val original=session.threads.first();val other=session.threads.last()
                main { session.selectThread(other) };ui.waitUntil(5_000) { !session.historyLoading }
                main { session.selectThread(original) };ui.waitUntil(5_000) { !session.historyLoading }
                main { session.text="new navigation draft";session.error="newer navigation error" }
                val reads=f.server.requests.count { it.path=="/v1/history" }
                f.release(first.operationId);ui.waitUntil(5_000) { !session.queueActionPending(first) }
                assertEquals("newer navigation error",session.error);assertEquals("new navigation draft",session.text)
                assertEquals(reads,f.server.requests.count { it.path=="/v1/history" });assertEquals(1,actions(f).size)
            } finally { f.releaseAll();main { session.error=null;session.leaveForeground() } }
        }
    }
    @Test fun newerSameTargetErrorSurvivesAndUnknownActionRefreshesWithoutReplay() {
        QueueActionFixture().use { f ->
            connect(f);val first=session.queuedReplies.first();f.hold(first.operationId);f.fail=true
            try {
                main { session.cancelQueue(first,session.targetKey) };ui.waitUntil(5_000) { actions(f).size==1 }
                main { session.error="newer same target error";session.text="independent same target draft" }
                val before=f.server.requests.count { it.path=="/v1/history" }
                f.release(first.operationId);ui.waitUntil(5_000) { !session.queueActionPending(first)&&f.server.requests.count { it.path=="/v1/history" }>before&&!session.historyLoading }
                assertEquals("newer same target error",session.error);assertEquals("independent same target draft",session.text)
                assertEquals(1,actions(f).size)
            } finally { f.releaseAll();main { session.error=null;session.leaveForeground() } }
        }
    }
    @Test fun consentRequiresAnotherExplicitTapAndRunningExecutionKeepsOriginalRun() {
        QueueActionFixture().use { f ->
            f.turn="original-run";connect(f,sharing=false)
            ui.onNodeWithTag("execute-queued-q1").assertIsEnabled().performClick()
            ui.waitUntil(5_000) { session.aiSharingRequest!=null };assertTrue(actions(f).isEmpty())
            main { session.allowAISharing() };ui.waitUntil(5_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            assertTrue(actions(f).isEmpty())
            ui.onNodeWithTag("execute-queued-q1").assertIsEnabled().performClick()
            ui.waitUntil(5_000) { actions(f).size==1&&!session.historyLoading&&!session.queueActionPending(f.original("q1")) }
            val body=JSONObject(String(actions(f).single().body,Charsets.UTF_8))
            assertEquals("q1",body.getString("clientMessageId"));assertEquals("original-run",body.getString("expectedTurnId"))
            assertEquals(setOf("hostId","threadId","clientMessageId","expectedTurnId"),body.keys().asSequence().toSet())
            main { session.leaveForeground() }
        }
    }
    @Test fun compactRowsKeepOrderAttachmentSummaryBoundedScrollAndCapabilityPresentation() {
        QueueActionFixture().use { f ->
            connect(f)
            ui.onNodeWithTag("queued-number-q1").assertTextEquals("1")
            ui.onNodeWithTag("queued-text-q1").assertTextEquals("Original first input")
            ui.onNodeWithTag("queued-text-file-only").assertTextEquals("附件指令")
            ui.onNodeWithTag("execute-queued-q1").assertTextContains("立即执行")
            ui.onNodeWithTag("cancel-queued-q1").assertContentDescriptionEquals("取消第 1 条排队指令")
            val bounds=ui.onNodeWithTag("queued-commands").fetchSemanticsNode().boundsInRoot
            val density=ui.activity.resources.displayMetrics.density
            assertTrue("Queue scroll remains bounded at 180dp",bounds.height/density<=181)
            shot("compact-idle-original-order")
            ui.onNodeWithTag("queued-q10").performScrollTo().assertIsDisplayed();shot("compact-last-original-scroll")
            f.turn="current-run";f.marker="running-layout";read(f)
            ui.onNodeWithTag("queued-q1").performScrollTo();ui.onNodeWithTag("execute-queued-q1").assertTextContains("立即追加");shot("compact-running-append")
            f.capability=false;main { session.refresh() };ui.waitUntil(5_000) { !session.loading&&!session.historyLoading&&!session.supportsQueueExecution }
            ui.onNodeWithTag("execute-queued-q1").assertDoesNotExist();ui.onNodeWithTag("cancel-queued-q1").assertIsEnabled()
            assertTrue(actions(f).isEmpty());main { session.leaveForeground() }
        }
    }
    @Test fun sdkEdgeVerticalQueueScrollReachesAndCancelsOnlyTheLastOriginal() {
        QueueActionFixture().use { f ->
            connect(f,sharing=false)
            val original=session.queuedReplies.map { it.operationId };assertEquals(12,original.size)
            ui.runOnIdle { session.text="Independent SDK queue gesture draft" }
            val viewport=ui.onNodeWithTag("queued-commands")
            fun range()=viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            fun position()=range().value()
            assertEquals(0f,position(),1f);shot("queue-before-sdk-scroll")
            val density=ui.activity.resources.displayMetrics.density
            val edgeX=viewport.fetchSemanticsNode().boundsInRoot.left+2f
            val productLeft=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot.left
            assertTrue("Use the actual drawer edge band",edgeX>=productLeft&&edgeX-productLeft<24f*density)
            repeat(12) {
                if(position()<range().maxValue()-1f)viewport.performTouchInput { swipe(Offset(2f,height*.85f),Offset(2f,height*.15f),400) }
            }
            ui.waitUntil(10_000) { position()>=range().maxValue()-1f }
            ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
            ui.onNodeWithTag("queued-number-q10").assertTextEquals("12")
            ui.onNodeWithTag("cancel-queued-q10").assertIsDisplayed();shot("queue-sdk-last-original")
            assertEquals(original,session.queuedReplies.map { it.operationId });assertTrue(actions(f).isEmpty())
            ui.onNodeWithTag("cancel-queued-q10").performClick()
            ui.waitUntil(10_000) { actions(f).size==1&&!session.historyLoading&&session.queuedReplies.none { it.operationId=="q10" } }
            val command=actions(f).single();assertEquals("/v1/queue/cancel",command.path)
            val body=JSONObject(String(command.body,Charsets.UTF_8));assertEquals("q10",body.getString("clientMessageId"));assertEquals("original-thread",body.getString("threadId"))
            assertEquals(original.dropLast(1),session.queuedReplies.map { it.operationId })
            assertEquals("Independent SDK queue gesture draft",session.text);assertNull(session.aiSharingRequest);assertTrue(session.operationRecords.isEmpty())
            assertTrue(actions(f).none { it.path=="/v1/replies" });shot("queue-last-only-cancelled")
            File(folder(),"queue-sdk-pointer-proof.json").writeText(JSONObject().put("originalCount",12).put("sdkVerticalSwipes",true).put("edgeBandPx",24f*density).put("edgeDownX",edgeX).put("productLeft",productLeft).put("cancelledOriginal","q10").put("syntheticQueueCommands",1).put("newInputs",0).toString())
        }
    }
}

private class QueueActionFixture: java.io.Closeable {
    val server=CommandFixture(::response)
    private val epoch=UUID.randomUUID().toString()
    @Volatile var turn: String?=null
    @Volatile var blockFirst=false
    @Volatile var capability=true
    @Volatile var fail=false
    @Volatile var marker="initial queue history"
    private val removed=ConcurrentHashMap.newKeySet<String>()
    private val gates=ConcurrentHashMap<String,CountDownLatch>()
    fun hold(id: String) { gates[id]=CountDownLatch(1) }
    fun release(id: String) { gates[id]?.countDown() }
    fun releaseAll() { gates.values.forEach { it.countDown() } }
    fun remove(id: String) { removed+=id }
    private fun target()=JSONObject().put("agentId","codex").put("hostId","queue-action-fixture-host").put("workspaceId","queue-action-workspace").put("runtimeId",epoch)
    fun original(id: String)=DeliveryReceipt("queue-action-fixture-host","original-thread",id,"queued",null,null,null,"Original first input",if(id=="file-only")listOf("original-file") else emptyList(),queueSource="native",queuePresent=true,canExecuteNow=true)
    private fun response(r: WireRequest): WireResponse {
        if(r.method=="POST") {
            require(r.path in listOf("/v1/queue/execute","/v1/queue/cancel"))
            val body=JSONObject(String(r.body,Charsets.UTF_8));val id=body.getString("clientMessageId")
            gates[id]?.let { check(it.await(20,TimeUnit.SECONDS)) }
            if(fail)return WireResponse(status=409,body="{\"error\":\"old queue action failure\"}")
            removed+=id;return WireResponse(body="{\"ok\":true}")
        }
        val query=r.pathWithQuery.substringAfter('?').split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
        val result=when(r.path) {
            "/v1/workspace"->JSONObject().put("hostId","queue-action-fixture-host").put("name","queue fixture").put("connectionMode","desktop-runtime").put("readTarget",target())
                .put("agentConnectionState",JSONObject().put("target",target()).put("phase","connected").put("transportId","fixture-http").put("processHealthy",true).put("runtimeConnected",true).put("historyReadable",true))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("original-thread","other-thread").map { JSONObject().put("id",it).put("hostId","queue-action-fixture-host").put("title",it).put("state","idle") }))
                .put("capabilities",JSONArray(listOf("reply","queue","commands:target-bound:v1")+if(capability)listOf("queue:execute") else emptyList()))
            "/v1/history"-> {
                val thread=query.getValue("threadId")
                JSONObject().put("hostId","queue-action-fixture-host").put("threadId",thread).put("readTarget",target()).put("messages",JSONArray().put(JSONObject().put("id","queue-history-marker").put("role","assistant").put("text",marker)))
                    .put("runtime",JSONObject().put("state",if(turn==null)"idle" else "running").put("turnId",turn ?: JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("canControl",true)
                        .put("receipts",JSONArray(if(thread=="original-thread")listOf("q1","q2","file-only","blocked")+(3..10).map { "q$it" } else emptyList<String>()).let { ids -> JSONArray().apply {
                            for(i in 0 until ids.length()) { val id=ids.getString(i);put(encodeReceipt(original(id)).put("queuePresent",id !in removed).put("canExecuteNow",id!="blocked"&&!(id=="q1"&&blockFirst))
                                .put("text",if(id=="q1")"  Original first input  " else if(id=="file-only")" \n " else id)) }
                        } }))
            }
            "/v1/events"->JSONObject().put("hostId","queue-action-fixture-host").put("epoch",epoch).put("cursor",0).put("reset",false).put("ready",true).put("events",JSONArray())
            else->return WireResponse(status=404)
        }
        return WireResponse(body=result.toString())
    }
    override fun close() { releaseAll();server.close() }
}
