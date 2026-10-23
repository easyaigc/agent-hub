package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Opt-in original Electron/model acceptance. No fixture receipt or synthetic model output. */
@RunWith(AndroidJUnit4::class)
class MobileReplyRecoveryLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"reply-recovery-live.json").readText()) }
    private val nonce get()=config.getString("nonce")
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            if(InstrumentationRegistry.getArguments().getString("recoveryForeground")=="1") {
                val descriptor=instrumentation.uiAutomation.executeShellCommand("am start -W -n com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity")
                val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                assertTrue(result.contains("Status: ok"))
            }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    private lateinit var session: MobileSession
    private var completed=false
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject=JSONObject()): JSONObject {
        require(route in listOf("preflight","first-completed","fence-reply","reply-completed"))
        val base=URI(config.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=240_000
            http.setRequestProperty("Authorization","Bearer "+config.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Independent original owner check",200,http.responseCode)
            return http.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { http.disconnect() }
    }
    private fun text(value: String) { ui.onNodeWithTag("message-input").performTextReplacement(value);ui.waitForIdle() }
    private fun dismissError() { if(ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("dismiss-error").performClick() }
    private fun shot(name: String) {
        if(session.error==null)runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        ui.waitForIdle();instrumentation.uiAutomation.waitForIdle(500,5_000)
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"acceptance/UM02-live/$nonce").apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(directory,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @After fun stop() { if(::session.isInitialized) { if(!completed)runCatching { shot("failure-current-state") };ui.runOnIdle { session.leaveForeground();session.resetAcceptance() } } }
    @Test fun originalOwnerFirstInputContinuationAndFencedReplyRetry() {
        val storage=NativeSessionStorage(instrumentation.targetContext,"recovery-live-"+UUID.randomUUID())
        session=MobileSession(storage);ui.setContent { AgentHubApp(session) }
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connectCodexQR(config.getString("pairing"),"独立恢复验收电脑") }
        ui.waitUntil(30_000) { session.selectedClient.online && session.canCreateThread && session.projects.any { it.id==config.getString("projectId") } && session.transferStatus==null }
        ui.runOnIdle {
            session.newChat(config.getString("projectId"))
            session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="read-only",collaborationMode="default"))
        }
        text(config.getString("firstText"));ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(120_000) { session.operationRecords.any { it.kind=="creation"&&session.mayContinueCreation(it)&&it.submissionAttempted }&&session.transferStatus==null }
        val first=session.operationRecords.single { it.kind=="creation" }
        assertEquals("retry-original-preflight",CodexCommandCodec.creation(JSONObject(first.result!!)).diagnostic?.disposition)
        control("preflight",JSONObject().put("operationId",first.id))
        dismissError();ui.onNodeWithTag("continue-creation-${first.id}").assertIsDisplayed();shot("first-preflight-zero-input")
        text("首条恢复期间的新草稿 $nonce")
        ui.onNodeWithTag("continue-creation-${first.id}").performScrollTo().performClick()
        ui.waitUntil(120_000) { session.operationRecords.any { it.id==first.id&&it.phase=="confirmed" }&&session.transferStatus==null }
        val confirmed=session.operationRecords.single { it.id==first.id }
        assertEquals(first.intent,confirmed.intent);assertEquals("首条恢复期间的新草稿 $nonce",session.text)
        val threadId=JSONObject(confirmed.result!!).getString("threadId")
        control("first-completed",JSONObject().put("operationId",first.id).put("threadId",threadId))
        shot("first-completed-new-draft-retained")
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==threadId }) }
        ui.waitUntil(30_000) { session.canCompose && !session.historyLoading && !session.running }
        ui.runOnIdle { session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="full-access",collaborationMode="default")) }
        text(config.getString("replyText"));ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(30_000) { session.operationRecords.any { it.kind=="reply"&&it.submissionAttempted }&&session.transferStatus==null }
        val reply=session.operationRecords.single { it.kind=="reply" }
        assertFalse(session.mayRetryReply(reply));dismissError()
        ui.onNodeWithTag("retry-operation-${reply.id}").assertDoesNotExist();shot("reply-not-forwarded-query-only")
        text("回复恢复期间的新草稿 $nonce")
        // Both native clients lock generation settings while an immutable reply is pending.
        assertFalse(session.canConfigureGeneration)
        ui.runOnIdle { session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
        assertEquals("full-access",session.generationSettings.permissionMode)
        control("fence-reply",JSONObject().put("operationId",reply.id).put("threadId",threadId))
        ui.onNodeWithTag("query-operation-${reply.id}").performScrollTo().performClick()
        ui.waitUntil(30_000) { session.operationRecords.singleOrNull { it.id==reply.id }?.let { session.mayRetryReply(it) }==true && session.transferStatus==null }
        // The editable next draft cannot change the already submitted operation's permission.
        shot("reply-authoritative-not-accepted")
        ui.onNodeWithTag("retry-operation-${reply.id}").performScrollTo().performClick()
        ui.waitUntil(120_000) { session.operationRecords.any { it.id==reply.id&&it.phase in listOf("submitted","completed") }&&session.transferStatus==null }
        val retried=session.operationRecords.single { it.id==reply.id }
        assertEquals(reply.intent,retried.intent);assertEquals(reply.intentSha256,retried.intentSha256);assertTrue(retried.replyRecovery!!.consumed)
        assertEquals("回复恢复期间的新草稿 $nonce",session.text);assertEquals("full-access",session.generationSettings.permissionMode)
        control("reply-completed",JSONObject().put("operationId",reply.id).put("threadId",threadId))
        ui.runOnIdle { session.loadHistory(userInitiated=true) }
        ui.waitUntil(30_000) { session.messages.any { it.role=="assistant"&&it.text.trim()=="UM02_REPLY_$nonce" }&&!session.running&&!session.historyLoading }
        ui.waitUntil(30_000) { session.canConfigureGeneration }
        ui.runOnIdle { session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
        assertEquals("read-only",session.generationSettings.permissionMode)
        ui.onNodeWithTag("retry-operation-${reply.id}").assertDoesNotExist();shot("reply-completed-exact-input-new-draft")
        assertEquals("回复恢复期间的新草稿 $nonce",session.text)
        assertEquals(first.intent,storage.read("operations:${first.target}")?.let(::JSONObject)?.getJSONArray("operations")?.objects()?.single { it.getString("id")==first.id }?.getString("intent"))
        completed=true
    }
}
