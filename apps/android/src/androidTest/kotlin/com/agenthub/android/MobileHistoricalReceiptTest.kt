package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/** Historical uncertainty is a retained original receipt, never a current wait or replay action. */
@RunWith(AndroidJUnit4::class)
class MobileHistoricalReceiptTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var session: MobileSession
    private lateinit var storage: NativeSessionStorage
    private lateinit var namespace: String
    private var server: CommandFixture?=null
    private var originalDraftMetadata: Map<String,String?> = emptyMap()
    private val offline=AtomicBoolean(false)
    private val queueError=AtomicReference<String?>(null)
    private val includeForeign=AtomicBoolean(false)
    private val historyBodies=CopyOnWriteArrayList<String>()
    private val target=TargetScope("codex",null,null,"historical-host","historical-workspace","historical-runtime")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun original(thread: String)="synthetic original input for $thread"
    @Before fun isolate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val prior=NativeSessionStorage(context).metadata();originalDraftMetadata=listOf("drafts","draftFiles","draftVersions").associateWith { prior?.opt(it)?.toString() }
            namespace="P4-historical-receipt-"+UUID.randomUUID();storage=NativeSessionStorage(context,namespace);session=MobileSession(storage);session.resetAcceptance()
            ui.activity.setContent { AgentHubApp(session) { } }
        }
    }
    private fun setup(context: String?="historical",state: String="uncertain",error: String?=null,extraCompleted: Int=0,syncError: String?=null) {
        queueError.set(syncError)
        val workspace=JSONObject().put("hostId",target.hostId).put("name","synthetic historical receipts").put("connectionMode","history-only")
            .put("readTarget",CodexCommandCodec.target(target)).put("capabilities",JSONArray())
            .put("projects",JSONArray()).put("threads",JSONArray(listOf("notice-a","notice-b").map { JSONObject().put("id",it).put("hostId",target.hostId).put("title",it).put("state","idle") }))
        val fixture=CommandFixture { request ->
            if(offline.get())WireResponse(503)
            else when(request.path) {
            "/v1/workspace"->WireResponse(body=workspace.toString())
            "/v1/history"->{
                val threadId=if("threadId=notice-b" in request.pathWithQuery) "notice-b" else "notice-a"
                val receipt=JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("clientMessageId","original-id").put("state",state)
                    .put("turnId",JSONObject.NULL).put("error",error ?: JSONObject.NULL).put("text",original(threadId)).apply { if(context!=null)put("noticeContext",context) }
                queueError.get()?.let { receipt.put("queueSyncError",it) }
                val receipts=JSONArray().put(receipt)
                repeat(extraCompleted) { index -> receipts.put(JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("clientMessageId","later-$index").put("state","completed").put("turnId","later-turn-$index").put("error",JSONObject.NULL)) }
                if(includeForeign.get()) {
                    receipts.put(JSONObject(receipt.toString()).put("threadId","foreign-thread").put("clientMessageId","foreign-thread-unknown").put("state","uncertain").put("text","FOREIGN_THREAD_BODY").put("error","FOREIGN_THREAD_DIAGNOSTIC"))
                    receipts.put(JSONObject(receipt.toString()).put("hostId","foreign-host").put("clientMessageId","foreign-host-queue").put("state","uncertain").put("text","FOREIGN_HOST_BODY").put("queueSyncError","FOREIGN_HOST_QUEUE_DIAGNOSTIC"))
                    receipts.put(JSONObject(receipt.toString()).put("threadId","foreign-thread").put("clientMessageId","foreign-thread-completed").put("state","completed").put("turnId","foreign-run").put("error",JSONObject.NULL))
                    receipts.put(JSONObject(receipt.toString()).put("hostId","foreign-host").put("clientMessageId","foreign-host-completed").put("state","completed").put("turnId","foreign-run").put("error",JSONObject.NULL))
                }
                val wire=JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target))
                    .put("messages",JSONArray().put(JSONObject().put("id","current-body").put("role","assistant").put("text","later task completed").put("status","completed")))
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray()).put("receipts",receipts).put("canControl",false)).toString()
                historyBodies.add(wire);WireResponse(body=wire)
            }
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("synthetic historical receipts",fixture.endpoint,"") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="unsubmitted draft" }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!=null }
    }
    private fun toggle(expanded: Boolean) {
        val control=ui.onNodeWithTag("historical-receipt-toggle-original-id").performScrollTo().assertIsDisplayed()
        control.performTouchInput { click(center) }
        ui.waitUntil(5_000) { control.fetchSemanticsNode().config[SemanticsProperties.StateDescription]==if(expanded) "已展开" else "已折叠" }
    }
    @After fun stop() {
        try { if(::session.isInitialized)main { session.leaveForeground() };assertTrue("History notices cannot dispatch commands",server?.requests.orEmpty().all { it.method=="GET" }) }
        finally {
            server?.close()
            val current=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).metadata()
            for((key,value)in originalDraftMetadata)assertEquals("Historical receipt UI must preserve default real $key",value,current?.opt(key)?.toString())
        }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
        val run=InstrumentationRegistry.getArguments().getString("historicalReceiptRunId")?:"historical-receipt-local";require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(i.targetContext.filesDir,"acceptance/P4-historical-receipts/$run").apply { mkdirs() }
        val frames=CountDownLatch(1);i.runOnMainSync { window.decorView.postOnAnimation { window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());assertEquals(i.targetContext.packageName,window.decorView.context.packageName);bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val copied=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) };assertTrue(copied.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-main-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).toString())
        }finally{image.recycle()}
    }
    @Test fun historicalUnknownStartsCollapsedAndRevealsOnlyOriginalBody() {
        setup();ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        shot("historical-unknown-collapsed");toggle(true)
        ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("电脑已继续后续任务。这条旧消息是否执行仍缺少确认，不代表当前任务在等待；不会自动重发。").assertExists()
        shot("historical-unknown-expanded");toggle(false);ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        assertEquals("unsubmitted draft",session.text);assertNull(session.transferStatus);assertTrue(session.operationRecords.isEmpty());assertEquals("idle",session.runtime?.state)
    }
    @Test fun currentUnknownKeepsCurrentNoticeWithoutHistoricalBodyOrReplay() {
        setup(context=null);ui.onNodeWithText("消息执行结果待确认").assertIsDisplayed()
        ui.onNodeWithTag("historical-receipt-toggle-original-id").assertDoesNotExist();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        ui.onNodeWithTag("dismiss-receipt-original-id").assertDoesNotExist();assertEquals("unsubmitted draft",session.text);assertTrue(session.operationRecords.isEmpty())
        shot("current-unknown-remains-distinct")
    }
    @Test fun sameOperationIdOnAnotherTargetCannotInheritDisclosureOrOriginalText() {
        setup();toggle(true);ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed()
        ui.runOnIdle { session.selectThread(session.threads.last()) };ui.waitUntil(5_000) { !session.historyLoading }
        ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist();ui.onNodeWithText(original("notice-b")).assertDoesNotExist()
        toggle(true);ui.onNodeWithText(original("notice-b")).performScrollTo().assertIsDisplayed()
        shot("same-id-other-target-separate-disclosure")
        assertEquals("notice-b",session.selectedThread);assertEquals("",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun historicalUnknownPreservesErrorOutsideDisclosureAndCannotBeDismissedAsFailure() {
        setup(error="synthetic original transport diagnostic");ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed()
        ui.onNodeWithText("synthetic original transport diagnostic").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        ui.onNodeWithTag("dismiss-receipt-original-id").assertDoesNotExist();assertEquals("uncertain",session.runtime!!.receipts.single().state)
        shot("historical-error-outside-disclosure")
    }
    @Test fun rejectedReceiptRetainsSubmissionFailureAndExplicitDismissal() {
        setup(context=null,state="failed",error="synthetic rejection");ui.onNodeWithText("消息提交失败").assertIsDisplayed()
        shot("rejected-receipt-explicit-dismissal");ui.onNodeWithTag("dismiss-receipt-original-id").performScrollTo().assertIsDisplayed().performTouchInput { click(center) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("receipt-original-id").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("receipt-original-id").assertDoesNotExist()
        assertEquals("failed",session.runtime!!.receipts.single().state);assertEquals("unsubmitted draft",session.text)
    }
    @Test fun malformedReceiptContextCannotBeCoercedIntoPresentationMetadata() {
        val base=JSONObject().put("hostId","historical-host").put("threadId","notice-a").put("clientMessageId","original-id")
            .put("state","uncertain").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
        for(value in listOf<Any>(42,true,JSONArray().put("historical"),JSONObject().put("value","historical"))) {
            try { CodexCommandCodec.receipt(JSONObject(base.toString()).put("noticeContext",value));fail("Malformed context must be rejected without a receipt") }
            catch(expected: IllegalArgumentException) { /* no operation or presentation produced */ }
        }
    }

    @Test fun oldestHistoricalUnknownSurvivesThirtyFiveNewerReceiptsAndEncryptedOfflineReopen() {
        setup(extraCompleted=35);assertEquals(36,session.runtime!!.receipts.size);assertEquals("original-id",session.runtime!!.receipts.first().operationId)
        assertEquals(listOf("original-id"),session.receiptNotices.map { it.operationId });assertNull(session.transferStatus);assertTrue(session.operationRecords.isEmpty())
        val key=session.targetKey
        ui.waitUntil(15_000) { NativeHistoryCache(storage).read(key)?.runtime?.receipts?.size==36&&storage.metadata()?.optJSONObject("drafts")?.optString(key)=="unsubmitted draft" }
        val cached=checkNotNull(NativeHistoryCache(storage).read(key));val old=cached.runtime!!.receipts.first();assertEquals("historical",old.noticeContext);assertEquals(original("notice-a"),old.text);assertEquals("uncertain",old.state)
        offline.set(true)
        ui.runOnIdle { session.leaveForeground();session=MobileSession(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,namespace));ui.activity.setContent { AgentHubApp(session) { } };session.restore() }
        ui.waitUntil(30_000) { !session.restoring&&!session.historyLoading&&session.selectedThread=="notice-a"&&session.runtime?.receipts?.size==36 }
        assertEquals(key,session.targetKey);assertEquals("unsubmitted draft",session.text);assertEquals(listOf("original-id"),session.receiptNotices.map { it.operationId });assertFalse(session.canCompose);assertNull(session.transferStatus);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("historical-receipt-toggle-original-id").assertExists();ui.onNodeWithText(original("notice-a")).assertDoesNotExist();toggle(true);ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed();shot("oldest-of36-encrypted-offline-reopen")
    }
    @Test fun queueSyncErrorPrecedesHistoricalDisclosureThenRevealsOriginalUnknownWhenResolved() {
        setup(syncError="original client queue synchronization diagnostic")
        ui.onNodeWithText("客户端队列同步失败").assertIsDisplayed();ui.onNodeWithTag("historical-receipt-toggle-original-id").assertDoesNotExist();ui.onNodeWithText("original client queue synchronization diagnostic").assertIsDisplayed();assertEquals("historical",session.runtime!!.receipts.single().noticeContext);assertEquals("uncertain",session.runtime!!.receipts.single().state);shot("queue-error-precedes-historical-unknown")
        queueError.set(null);main { session.loadHistory() }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime?.receipts?.single()?.queueSyncError==null }
        ui.onNodeWithText("客户端队列同步失败").assertDoesNotExist();ui.onNodeWithTag("historical-receipt-toggle-original-id").assertExists();toggle(true);ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed();assertEquals("unsubmitted draft",session.text);assertNull(session.transferStatus);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun foreignReceiptResponseIsRejectedBeforePublicationAndKeepsCurrentFailure() {
        setup(context=null,state="failed",error="CURRENT_TARGET_ORIGINAL_FAILURE")
        val originalRuntime=session.runtime;assertEquals(1,originalRuntime!!.receipts.size)
        includeForeign.set(true);main { session.loadHistory(userInitiated=true) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.error!=null&&historyBodies.size>=2 }
        val rejected=historyBodies.last();assertNull(com.agenthub.core.CodexHttpContract.validate(rejected,"history"))
        try { HostReadCodec.history(rejected);fail("Mixed-target runtime receipt must be rejected") }catch(expected: IllegalArgumentException) { assertEquals("运行时回执目标不匹配",expected.message) }
        assertEquals(originalRuntime,session.runtime);assertEquals(listOf("original-id"),session.receiptNotices.map { it.operationId })
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed().performTouchInput { click(center) }
        ui.onNodeWithText("CURRENT_TARGET_ORIGINAL_FAILURE").assertIsDisplayed();ui.onNodeWithText("消息提交失败").assertIsDisplayed()
        for(text in listOf("FOREIGN_THREAD_BODY","FOREIGN_THREAD_DIAGNOSTIC","FOREIGN_HOST_BODY","FOREIGN_HOST_QUEUE_DIAGNOSTIC"))ui.onNodeWithText(text).assertDoesNotExist()
        for(id in listOf("foreign-thread-unknown","foreign-host-queue","foreign-thread-completed","foreign-host-completed"))ui.onNodeWithTag("receipt-$id").assertDoesNotExist()
        shot("foreign-response-rejected-current-failure-retained")
        ui.onNodeWithTag("dismiss-receipt-original-id").performScrollTo().performTouchInput { click(center) }
        ui.waitUntil(5_000) { session.receiptNotices.isEmpty() };assertEquals(1,session.runtime!!.receipts.size);assertEquals("unsubmitted draft",session.text);assertTrue(session.operationRecords.isEmpty());assertNull(session.transferStatus)
    }

}
