package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import java.io.File
import java.net.URL
import java.util.UUID

/** Actual production Compose/session/HTTP/Keystore, with isolated per-test storage. */
@RunWith(AndroidJUnit4::class)
class MobileReplyRecoveryUiTest {
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            if(InstrumentationRegistry.getArguments().getString("recoveryForeground")=="1") {
                val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity")
                val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                assertTrue("Foreground only the dedicated test Activity before each MIUI launch",result.contains("Status: ok"))
            }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    private val holder=mutableStateOf<MobileSession?>(null)
    private val session get()=holder.value!!
    private lateinit var storage: NativeSessionStorage
    private val args get()=InstrumentationRegistry.getArguments()
    private val control get()=args.getString("recoveryControl")!!
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(path: String): JSONObject=URL(control+path).openConnection().apply { connectTimeout=5000;readTimeout=5000 }.getInputStream().bufferedReader().use { JSONObject(it.readText()) }
    @Before fun setup() {
        storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"recovery-ui-"+UUID.randomUUID())
        holder.value=MobileSession(storage)
        ui.setContent { AgentHubApp(holder.value!!) }
        ui.runOnIdle { session.resetAcceptance() }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground();session.resetAcceptance() } }
    private fun connect(mode: String="unknown") {
        val pairing=control("/reset?mode=$mode").getString("pairing")
        main { session.connectCodexQR(pairing,"合成恢复验收电脑") }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(15_000) { session.runtime!=null && !session.historyLoading && session.canCompose }
        assertNotNull(session.selectedClient.codexDirect)
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun text(value: String) { ui.onNodeWithTag("message-input").performTextReplacement(value);ui.waitForIdle() }
    private fun send(): SavedOperation {
        ui.waitUntil(10_000) { session.transferStatus==null }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.kind=="reply" && it.submissionAttempted } && session.transferStatus==null }
        return session.operationRecords.single { it.kind=="reply" }
    }
    private fun dismissError() {
        if(ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("dismiss-error").performClick()
    }
    private fun grant(record: SavedOperation) {
        control("/state?mode=strong");dismissError()
        ui.onNodeWithTag("query-operation-${record.id}").performScrollTo().performClick()
        ui.waitUntil(15_000) { session.operationRecords.firstOrNull { it.id==record.id }?.let { session.mayRetryReply(it) }==true }
    }
    private fun shot(name: String) {
        if(session.error==null) runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.uiAutomation.waitForIdle(500,5_000)
        val run=args.getString("recoveryRun")!!;require(run.matches(Regex("[a-f0-9-]+")))
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"acceptance/UM02/$run").apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(directory,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun unknownThenExplicitRetryPreservesNewDraftAndImmutableInput() {
        connect();text("原始恢复消息 😀");val original=send()
        assertFalse(original.replyRecovery!!.mayRetry(session.selectedClient,original))
        ui.onNodeWithTag("retry-operation-${original.id}").assertDoesNotExist();shot("reply-unknown-query-only")
        text("独立的新草稿");grant(original)
        ui.onNodeWithTag("retry-operation-${original.id}").assertIsDisplayed();shot("reply-explicit-retry-ready")
        ui.onNodeWithTag("retry-operation-${original.id}").performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.id==original.id && it.phase=="completed" } && session.transferStatus==null }
        val restored=session.operationRecords.single { it.id==original.id }
        assertEquals(original.intent,restored.intent);assertEquals(original.intentSha256,restored.intentSha256)
        assertTrue(restored.replyRecovery!!.consumed);assertEquals("独立的新草稿",session.text)
        val result=control("/results");assertEquals(2,result.getInt("replyAttempts"));assertEquals(1,result.getInt("acceptedInputs"))
        assertEquals(1,result.getJSONArray("replyIds").strings().distinct().size);assertEquals(1,result.getJSONArray("replyFingerprints").strings().distinct().size)
        shot("reply-completed-new-draft-preserved")
    }
    @Test fun staleWrongMissingAndOldHostProofsRemainQueryOnly() {
        connect();text("不可重放的原消息");val original=send()
        for(mode in listOf("stale","wrong-target","missing-receipt","unsupported")) {
            control("/state?mode=$mode");dismissError()
            ui.onNodeWithTag("query-operation-${original.id}").performScrollTo().performClick()
            ui.waitUntil(15_000) { session.transferStatus==null }
            dismissError();ui.onNodeWithTag("retry-operation-${original.id}").assertDoesNotExist()
            assertEquals(1,control("/results").getInt("replyAttempts"))
        }
        shot("reply-invalid-proofs-query-only")
        ui.onNodeWithTag("detach-operation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.operationRecords.single { it.id==original.id }.detached }
        text("结束等待后的新草稿");assertEquals(0,control("/results").getInt("acceptedInputs"));shot("reply-detached-draft-retained")
    }
    @Test fun recreatedNativeSessionQueriesWithoutAutomaticReplay() {
        connect();text("重启前的原消息");val original=send();text("恢复后的独立草稿");grant(original)
        ui.waitUntil(10_000) { storage.metadata()?.toString()?.contains("恢复后的独立草稿")==true }
        ui.runOnIdle { session.leaveForeground();session.resetAcceptance();holder.value=MobileSession(storage);session.restore() }
        ui.waitUntil(15_000) { session.selectedClient.online && session.operationRecords.any { it.id==original.id } && session.transferStatus==null }
        assertEquals(1,control("/results").getInt("replyAttempts"));assertEquals("恢复后的独立草稿",session.text)
        grant(original);shot("reply-restored-query-only")
        ui.onNodeWithTag("retry-operation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.id==original.id && it.phase=="completed" } }
        assertEquals("恢复后的独立草稿",session.text);assertEquals(2,control("/results").getInt("replyAttempts"));shot("reply-restored-explicit-complete")
    }
    @Test fun editOriginalProtectsCurrentDraftAndDetachesOldOperation() {
        connect();text("需要编辑的原消息");val original=send();grant(original);text("不能覆盖的新草稿")
        ui.onNodeWithTag("edit-operation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.error!=null };assertEquals("不能覆盖的新草稿",session.text)
        ui.runOnIdle { session.refresh();session.loadHistory() }
        ui.waitUntil(10_000) { !session.loading && !session.historyLoading }
        ui.onNodeWithTag("session-error").assertIsDisplayed();shot("reply-edit-protects-new-draft")
        ui.onNodeWithTag("session-error").assertIsDisplayed()
        dismissError();text("");ui.onNodeWithTag("edit-operation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.text=="需要编辑的原消息" && session.transferStatus==null }
        val detached=session.operationRecords.single { it.id==original.id };assertTrue(detached.detached);assertEquals(original.intent,detached.intent)
        assertEquals(1,control("/results").getInt("replyAttempts"));assertEquals(0,control("/results").getInt("acceptedInputs"));shot("reply-edit-original-restored")
    }
    @Test fun preparedFirstInputRequiresExplicitSameOperationContinuation() {
        connect("creation-preflight");ui.runOnIdle { session.newChat() };text("不可变的首条内容")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.kind=="creation" && session.mayContinueCreation(it) && it.submissionAttempted } && session.transferStatus==null }
        val original=session.operationRecords.single { it.kind=="creation" }
        assertEquals(1,control("/results").getInt("creationSubmits"));assertEquals(0,control("/results").getInt("acceptedInputs"))
        ui.onNodeWithTag("continue-creation-${original.id}").assertIsDisplayed();shot("first-input-preflight-not-written")
        text("新建后的另一个草稿");ui.onNodeWithTag("continue-creation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.id==original.id && it.phase=="confirmed" } && session.transferStatus==null }
        assertEquals(original.intent,session.operationRecords.single { it.id==original.id }.intent);assertEquals("新建后的另一个草稿",session.text)
        val result=control("/results");assertEquals(2,result.getInt("creationSubmits"));assertEquals(1,result.getInt("acceptedInputs"));assertEquals(1,result.getJSONArray("creationIds").strings().distinct().size)
        shot("first-input-explicit-complete-new-draft")
    }
    @Test fun unknownFirstInputNeverExposesContinuationOrReplays() {
        connect("creation-unknown");ui.runOnIdle { session.newChat() };text("未知首条内容")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(15_000) { session.operationRecords.any { it.kind=="creation" && it.submissionAttempted } && session.transferStatus==null }
        val original=session.operationRecords.single { it.kind=="creation" }
        ui.onNodeWithTag("continue-creation-${original.id}").assertDoesNotExist()
        ui.onNodeWithTag("query-operation-${original.id}").performScrollTo().performClick();ui.waitUntil(10_000) { session.transferStatus==null }
        dismissError();ui.onNodeWithTag("continue-creation-${original.id}").assertDoesNotExist();shot("first-input-unknown-query-only")
        ui.onNodeWithTag("detach-operation-${original.id}").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.operationRecords.single { it.id==original.id }.detached }
        text("未知首条之后的新草稿");assertEquals(1,control("/results").getInt("creationSubmits"));assertEquals(0,control("/results").getInt("acceptedInputs"));shot("first-input-detached-draft-retained")
    }
}
