package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/** Event failure provenance and ordering through the actual native session; zero input POSTs. */
@RunWith(AndroidJUnit4::class)
class MobileEventFailureTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val catalogs=AtomicInteger()
    private val eventThrows=AtomicInteger()
    private val scope get()=TargetScope("codex",null,null,"event-failure-host","event-workspace","desktop-runtime")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun workspace()=HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","synthetic event failure")
        .put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
        .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("events","commands:target-bound:v1")))
        .put("projects",JSONArray()).put("threads",JSONArray(listOf("event-a","event-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") })).toString())
    private fun history(thread: String)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",thread).put("readTarget",CodexCommandCodec.target(scope))
        .put("messages",JSONArray().put(JSONObject().put("id",thread+"-row").put("role","assistant").put("text","synthetic cached event content").put("status","completed"))).toString())
    private fun setup(failure: Exception,started: CompletableDeferred<Unit>,release: CompletableDeferred<Unit>,nonCancellable: Boolean=false) {
        val first=AtomicBoolean()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { catalogs.incrementAndGet();return workspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId)
        }
        val command=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                if(!first.compareAndSet(false,true))awaitCancellation()
                if(nonCancellable)withContext(NonCancellable) { started.complete(Unit);release.await() }
                else { started.complete(Unit);release.await() }
                eventThrows.incrementAndGet();throw failure
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader,command) }
        main { session.connect("synthetic event failure","https://fixture.invalid","") }
        ui.waitUntil(10_000) { !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="event wait draft" }
        runBlocking { withTimeout(10_000) { started.await() } }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.isNotEmpty() }
    }
    private fun refreshAndAwait() {
        val before=catalogs.get();ui.runOnIdle { session.refresh() }
        ui.waitUntil(10_000) { catalogs.get()>before&&!session.loading }
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
        try { java.io.File(i.targetContext.getExternalFilesDir(null),"acceptance/P2-events").apply { mkdirs() }.resolve("$name-window.png").outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { pixels.recycle() }
        val image=i.uiAutomation.takeScreenshot()
        try { java.io.File(i.targetContext.getExternalFilesDir(null),"acceptance/P2-events").apply { mkdirs() }.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    private fun failedClosed(failure: Exception,newerWorkspace: Boolean=false) {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(failure,started,release)
        val cached=session.messages
        try {
            if(newerWorkspace) { refreshAndAwait();assertTrue(session.selectedClient.online) }
            release.complete(Unit)
            ui.waitUntil(10_000) { !session.selectedClient.online }
            assertEquals(ConnectionPhase.FAILED,session.selectedClient.connectionPhase)
            assertNull("Background event failures stay quiet like iOS; connection phase carries failure",session.error);assertEquals(cached,session.messages);assertEquals("event wait draft",session.text)
        } finally { release.complete(Unit) }
    }
    @Test fun typedAuthenticationFailureKeepsItsNonTransientMeaning()=failedClosed(HostReadError("synthetic credential rejected"))
    @Test fun httpUnauthorizedIsNotReclassifiedAsTemporaryReconnect()=failedClosed(CodexCommandError("synthetic unauthorized",401))
    @Test fun certificateFailureInCommandWrapperStillFailsClosed()=failedClosed(CodexCommandError("read was not confirmed",cause=SSLHandshakeException("synthetic wrong peer").apply { initCause(CertificateException("synthetic pin changed")) }))
    @Test fun invalidEventTargetStillFailsClosed()=failedClosed(IllegalArgumentException("synthetic snapshot belongs to another runtime"))
    @Test fun unknownFailureIsNotAssumedToBeARecoverableNetworkError()=failedClosed(IllegalStateException("synthetic unsupported event state"))
    @Test fun newerWorkspaceNeverMasksHttpForbidden()=failedClosed(CodexCommandError("synthetic forbidden",403),true)
    @Test fun newerWorkspaceDisprovesAnOlderEventTransportTimeout() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(CodexCommandError("read was not confirmed",cause=SocketTimeoutException("synthetic delayed event")),started,release)
        try {
            refreshAndAwait();assertTrue(session.selectedClient.online)
            release.complete(Unit);ui.waitUntil(10_000) { eventThrows.get()>0 };ui.runOnIdle {}
            assertTrue("A later validated workspace defeats only the old transport inference",session.selectedClient.online)
            assertEquals("event wait draft",session.text);assertEquals("event-a-row",session.messages.single().id)
        } finally { release.complete(Unit) }
    }
    @Test fun httpServiceUnavailableRemainsTransientRatherThanAuthenticationFailure() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();setup(CodexCommandError("synthetic service unavailable",503),started,release)
        try { release.complete(Unit);ui.waitUntil(10_000) { !session.selectedClient.online };assertEquals(ConnectionPhase.WAITING,session.selectedClient.connectionPhase);assertEquals("event wait draft",session.text) }
        finally { release.complete(Unit) }
    }
    @Test fun differentConversationRejectsLateNonCancellableEventFailure() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();setup(HostReadError("synthetic old event rejection"),started,release,true)
        try {
            ui.runOnIdle { session.selectThread(session.threads.last());session.text="new event target draft" }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.singleOrNull()?.id=="event-b-row" }
            release.complete(Unit);ui.waitForIdle();assertTrue(session.selectedClient.online);assertNull(session.error);assertEquals("new event target draft",session.text)
        } finally { release.complete(Unit) }
    }
    @Test fun foregroundCancellationDoesNotBecomeAConnectionFailure() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();setup(CancellationException("synthetic foreground exit"),started,release)
        try { ui.runOnIdle { session.leaveForeground() };release.complete(Unit);ui.waitForIdle();assertNull(session.error);assertEquals("event wait draft",session.text) }
        finally { release.complete(Unit) }
    }
    @Test fun transientWrapperCannotHideCertificateFailure()=failedClosed(HostReadError("synthetic temporary wrapper",SSLHandshakeException("synthetic pin mismatch"),true))
    @Test fun httpInternalErrorIsNotIncludedInTheThreeTransientGatewayStatuses()=failedClosed(CodexCommandError("synthetic server error",500))
    @Test fun nestedCancellationStillDoesNotBecomeConnectionFailure() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(CodexCommandError("synthetic wrapper",cause=CancellationException("synthetic read cancelled")),started,release)
        try { release.complete(Unit);ui.waitUntil(10_000) { eventThrows.get()>0 };ui.runOnIdle {};assertTrue(session.selectedClient.online);assertNull(session.error);assertEquals("event wait draft",session.text) }
        finally { release.complete(Unit) }
    }
    @Test fun transportTimeoutWithoutNewerSuccessEntersWaitingAndKeepsDraft() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(CodexCommandError("synthetic wrapper",cause=SocketTimeoutException("synthetic timeout")),started,release)
        try { release.complete(Unit);ui.waitUntil(10_000) { !session.selectedClient.online };assertEquals(ConnectionPhase.WAITING,session.selectedClient.connectionPhase);assertNull(session.error);assertEquals("event wait draft",session.text) }
        finally { release.complete(Unit) }
    }

    @Test fun backgroundAuthenticationFailureHasFailedCacheStateWithoutGlobalDialog() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(CodexCommandError("synthetic unauthorized",401),started,release)
        try {
            release.complete(Unit);ui.waitUntil(10_000) { session.selectedClient.connectionPhase==ConnectionPhase.FAILED }
            ui.onNodeWithTag("offline-cache").assertTextEquals("连接未完成 · 查看缓存")
            ui.onAllNodes(isDialog()).assertCountEquals(0)
            ui.onNodeWithText("synthetic cached event content").assertIsDisplayed();ui.onNodeWithText("event wait draft").assertExists()
            shot("event-auth-failed")
        } finally { release.complete(Unit) }
    }
    @Test fun transientEventFailureHasWaitingCacheStateWithoutGlobalDialog() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        setup(CodexCommandError("synthetic timeout",cause=SocketTimeoutException()),started,release)
        try {
            release.complete(Unit);ui.waitUntil(10_000) { session.selectedClient.connectionPhase==ConnectionPhase.WAITING }
            ui.onNodeWithTag("offline-cache").assertTextEquals("正在重新连接…")
            ui.onAllNodes(isDialog()).assertCountEquals(0)
            ui.onNodeWithText("synthetic cached event content").assertIsDisplayed();ui.onNodeWithText("event wait draft").assertExists()
            shot("event-transient-waiting")
        } finally { release.complete(Unit) }
    }

}
