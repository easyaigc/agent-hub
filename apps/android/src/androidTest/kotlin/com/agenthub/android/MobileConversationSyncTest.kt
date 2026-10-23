package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import com.agenthub.core.RuntimeIdentity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MobileConversationSyncTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun workspace(host: String="sync-fixture",mode: String="history-only",events: Boolean=false,target: TargetScope?=null): MobileWorkspace {
        val value=JSONObject().put("hostId",host).put("name","synthetic synchronization").put("connectionMode",mode)
            .put("projects",JSONArray()).put("threads",JSONArray(listOf("old","new").map { JSONObject().put("id",it).put("hostId",host).put("title",it).put("state","idle") }))
        if(events)value.put("readTarget",CodexCommandCodec.target(eventScope)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
            .put("capabilities",JSONArray(listOf("events","commands:target-bound:v1")))
        if(target!=null)value.put("readTarget",CodexCommandCodec.target(target))
        return HostReadCodec.workspace(value.toString())
    }
    private fun history(thread: String,state: String="live",count: Int=1,host: String="sync-fixture",unknownRuntime: Boolean=false,events: Boolean=false,target: TargetScope?=null,nextCursor: String?=null): MobileHistory {
        val value=JSONObject().put("hostId",host).put("threadId",thread)
            .put("messages",JSONArray((0 until count).map { JSONObject().put("id","$thread-$it").put("role","assistant").put("text","$thread $state row $it").put("status","completed") }))
            .put("synchronization",JSONObject().put("state",state).put("checkedAt","2026-10-22T00:00:00Z"))
        if(unknownRuntime)value.put("runtime",JSONObject().put("state","unknown").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",false))
        if(events)value.put("readTarget",CodexCommandCodec.target(eventScope)).put("epoch","sync-epoch").put("cursor",0)
        if(target!=null)value.put("readTarget",CodexCommandCodec.target(target))
        if(nextCursor!=null)value.put("nextCursor",nextCursor)
        return HostReadCodec.history(value.toString())
    }
    private fun connect(reader: HostReads,commands: CodexCommands?=null) {
        ui.runOnIdle { session.resetAcceptance(reader,commands) }
        runBlocking { withContext(Dispatchers.Main) { session.connect("synthetic synchronization","https://fixture.invalid","") } }
        ui.waitUntil(10_000) { session.hasClient && !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(20_000) { session.hasClient && !session.loading && !session.historyLoading && session.messages.isNotEmpty() }
    }
    private fun reload(expected: String) {
        ui.runOnIdle { session.loadHistory() }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.firstOrNull()?.text?.contains(expected)==true }
    }
    private fun shot(name: String) {
        ui.waitUntil(10_000) { ui.activity.hasWindowFocus() }
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();i.uiAutomation.waitForIdle(200,5_000)
        val drawn=java.util.concurrent.CountDownLatch(1)
        // Android 8 can focus the new Activity while its prior window is still
        // transitioning. Sample actual native frames before capturing/clicking.
        ui.runOnUiThread {
            var remaining=18
            val frame=object: Runnable { override fun run() { if(--remaining==0)drawn.countDown() else ui.activity.window.decorView.postOnAnimation(this) } }
            ui.activity.window.decorView.postOnAnimation(frame)
        }
        assertTrue("Native window presents the current banner before capture",drawn.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val window=ui.activity.window;val pixels=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
        val copied=java.util.concurrent.CountDownLatch(1);var copyResult=-1
        ui.runOnUiThread { android.view.PixelCopy.request(window,pixels,{ result->copyResult=result;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
        assertTrue("Exact native window is copied",copied.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,copyResult)
        try { File(i.targetContext.getExternalFilesDir(null),"acceptance/P2-sync").apply { mkdirs() }.resolve("$name-window.png").outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { pixels.recycle() }
        val image=i.uiAutomation.takeScreenshot()
        try { File(i.targetContext.getExternalFilesDir(null),"acceptance/P2-sync").apply { mkdirs() }.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }

    @Test fun historyLiveWindowUnavailableAndLiveHaveDistinctNativePresentation() {
        var state="history"
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId,state)
        })
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时状态暂不可用")
        ui.onNodeWithTag("conversation-sync-retry").assertIsEnabled();shot("history-synced")
        state="live-window";reload(state)
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时进度已同步");shot("live-window-synced")
        state="unavailable";reload(state)
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败");shot("sync-unavailable")
        state="live";reload(state)
        ui.onNodeWithTag("conversation-sync-banner").assertDoesNotExist()
    }
    @Test fun retryRetainsExpandedRowsAndDraftAndCannotStartDuplicateReads() {
        val requests=AtomicInteger();var mode="live";val release=CompletableDeferred<Unit>()
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                requests.incrementAndGet()
                if(mode=="fail")throw HostReadError("synthetic timeout",SocketTimeoutException(),true)
                if(mode=="hold")release.await()
                return history(threadId,"live",120)
            }
        })
        try {
            ui.runOnIdle { session.earlierMessages();session.text="重试保留🙂草稿" }
            assertEquals(80,session.visibleMessageLimit)
            mode="fail";ui.runOnIdle { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading && !session.selectedClient.online }
            ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败")
            ui.onNodeWithText("连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。").assertExists();shot("read-timeout")
            val before=requests.get();val original=session.messages.map { it.id };val journal=session.operationRecords.map { it.id }
            mode="hold";ui.onNodeWithTag("conversation-sync-retry").performClick()
            ui.waitUntil(10_000) { session.historyLoading && requests.get()==before+1 }
            ui.onNodeWithTag("conversation-sync-retry").assertIsNotEnabled();shot("retry-inflight")
            assertEquals("重试保留🙂草稿",session.text);assertEquals(original,session.messages.map { it.id });assertEquals(80,session.visibleMessageLimit)
            release.complete(Unit);ui.waitUntil(10_000) { !session.historyLoading && session.selectedClient.online }
            ui.onNodeWithTag("conversation-sync-banner").assertDoesNotExist()
            assertEquals(before+1,requests.get());assertEquals(journal,session.operationRecords.map { it.id });assertEquals("重试保留🙂草稿",session.text)
        } finally { release.complete(Unit) }
    }
    @Test fun differentThreadRejectsLateFailureAndKeepsItsOwnBannerAndDraft() {
        var hold=false;val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(threadId=="old" && hold)withContext(NonCancellable) { started.complete(Unit);release.await();throw HostReadError("old failure",SocketTimeoutException(),true) }
                return history(threadId,if(threadId=="old") "unavailable" else "live-window")
            }
        })
        try {
            ui.runOnIdle { session.text="原聊天草稿";hold=true;session.loadHistory() }
            runBlocking { withTimeout(10_000) { started.await() } }
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="new" });session.text="新聊天草稿" }
            ui.waitUntil(10_000) { !session.historyLoading && session.messages.firstOrNull()?.id=="new-0" }
            release.complete(Unit);ui.waitForIdle()
            ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时进度已同步")
            assertEquals("新聊天草稿",session.text);assertNull(session.error)
            hold=false;ui.runOnIdle { session.selectThread(session.threads.first { it.id=="old" }) }
            ui.waitUntil(10_000) { !session.historyLoading && session.messages.firstOrNull()?.id=="old-0" }
            ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败");assertEquals("原聊天草稿",session.text)
        } finally { release.complete(Unit) }
    }
    @Test fun identicalThreadIdsOnDifferentHostsDoNotShareSyncFailures() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace(if(client.endpoint.orEmpty().contains("second")) "sync-fixture-second" else "sync-fixture")
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId,if(client.id=="sync-fixture") "unavailable" else "history",host=client.id)
        }
        connect(reader);val first=session.selectedClient;ui.runOnIdle { session.text="第一台草稿" }
        runBlocking { withContext(Dispatchers.Main) { session.connect("second synthetic","https://second.invalid","") } }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && session.selectedClient.id=="sync-fixture-second" && session.messages.isNotEmpty() }
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时状态暂不可用")
        ui.runOnIdle { session.select(first) };ui.waitUntil(10_000) { !session.historyLoading && !session.loading }
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败");assertEquals("第一台草稿",session.text)
    }
    @Test fun unknownDesktopRuntimeShowsUnavailableWithoutGrantingControl() {
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace(mode="desktop-runtime")
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId,unknownRuntime=true)
        })
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败");assertFalse(session.canCompose)
    }
    @Test fun identicalHostAndThreadOnDifferentWorkspacesKeepIndependentSyncAndDrafts() {
        val first=eventScope;val second=TargetScope("codex",null,null,"sync-fixture","sync-workspace-second","desktop-runtime")
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace(target=if(client.endpoint.orEmpty().contains("second"))second else first)
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId,if(RuntimeIdentity.matches(client.scope,first))"unavailable" else "history",target=client.scope)
        })
        ui.runOnIdle { session.text="原工作区草稿" }
        runBlocking { withContext(Dispatchers.Main) { session.connect("second workspace","https://second.invalid","") } }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && RuntimeIdentity.matches(session.selectedClient.scope,second) && session.messages.isNotEmpty() }
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时状态暂不可用");assertEquals("",session.text)
        runBlocking { withContext(Dispatchers.Main) { session.connect("first workspace","https://fixture.invalid","") } }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && RuntimeIdentity.matches(session.selectedClient.scope,first) && session.messages.isNotEmpty() }
        ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("对话同步失败");assertEquals("原工作区草稿",session.text)
    }
    private val eventScope get()=TargetScope("codex",null,null,"sync-fixture","sync-workspace","desktop-runtime")
    private fun slowHistoryAfterFreshEvent(failClosed: Boolean,unknownRuntime: Boolean=false) {
        var hold=false;val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val eventRelease=CompletableDeferred<Unit>();var delivered=false
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace(mode="desktop-runtime",events=true)
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(hold) { started.complete(Unit);release.await();throw if(failClosed)HostReadError("认证失败",null,false) else HostReadError("late timeout",SocketTimeoutException(),true) }
                return history(threadId,events=true)
            }
        }
        val command=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                eventRelease.await();if(delivered)awaitCancellation();delivered=true
                return EventBatch(client.id,"sync-epoch",1,false,true,null,listOf(history(threadId,"live-window",unknownRuntime=unknownRuntime,events=true)))
            }
        }
        try {
            connect(reader,command)
            ui.runOnIdle { session.text="事件后保留草稿";hold=true;session.loadHistory() }
            runBlocking { withTimeout(10_000) { started.await() } };eventRelease.complete(Unit)
            ui.waitUntil(10_000) { session.messages.firstOrNull()?.text?.contains("live-window")==true }
            release.complete(Unit);ui.waitUntil(10_000) { !session.historyLoading }
            if(failClosed) { assertFalse(session.selectedClient.online);assertTrue(session.error?.contains("认证失败")==true) }
            else { ui.onNodeWithTag("conversation-sync-failure").assertTextEquals("实时进度已同步");assertTrue(session.selectedClient.online);assertNull(session.error) }
            assertEquals("事件后保留草稿",session.text)
        } finally { release.complete(Unit);eventRelease.complete(Unit) }
    }
    @Test fun newerSameConversationEventSupersedesOldTransientBannerInference()=slowHistoryAfterFreshEvent(false)
    @Test fun newerConversationSnapshotNeverMasksAuthenticationFailure()=slowHistoryAfterFreshEvent(true)
    @Test fun freshHistoryFallbackSurvivesSlowTimeoutWhenDesktopStateIsUnknown()=slowHistoryAfterFreshEvent(false,true)
    @Test fun earlierPageFailureDoesNotReplaceLatestSynchronizationStatus() {
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(cursor!=null)throw HostReadError("synthetic earlier-page timeout",SocketTimeoutException(),true)
                return history(threadId,"live-window",nextCursor="older")
            }
        })
        ui.runOnIdle { session.text="更早页失败保留草稿";session.earlierMessages() }
        ui.waitUntil(10_000) { !session.historyLoading && session.error!=null }
        assertEquals("实时进度已同步",session.conversationSyncIssue?.title)
        assertEquals("连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。",session.error);assertEquals("暂时无法连接电脑",session.errorTitle);assertEquals("older",session.historyCursor)
        assertEquals("更早页失败保留草稿",session.text);assertEquals(listOf("old-0"),session.messages.map { it.id })
    }
}
