package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in actual model output; preserves the default native session and original clipboard. */
@Suppress("DEPRECATION")
class MobileCodeModelLiveTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-code-model-config.json").readText()) }
    private lateinit var session: MobileSession
    private var drafts=emptyMap<String,String?>()
    private val copied=mutableListOf<String>()
    private lateinit var systemClipboard: AndroidClipboard
    private var previousClip: ClipData?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P3-code-model/"+config.getString("nonce")).apply { mkdirs() }
    @Before fun prepare() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(InstrumentationRegistry.getArguments().getString("nativeCodeModel")=="1")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val metadata=NativeSessionStorage(i.targetContext).metadata();drafts=listOf("drafts","draftFiles","draftVersions").associateWith { metadata?.opt(it)?.toString() }
            val storage=NativeSessionStorage(i.targetContext,"P3-code-model-"+config.getString("nonce"))
            require(storage.metadata()==null) { "Fresh model scene only; never replay a previously submitted input" }
            session=MobileSession(storage);session.resetAcceptance()
            systemClipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE)as AndroidClipboard;previousClip=systemClipboard.primaryClip
            ui.activity.setContent { AgentHubApp(session) { } }
        }
        main { session.connect("Real model code DEV",config.getString("endpoint"),config.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.projects.any { it.id==config.getString("projectId") } }
    }
    @After fun preserve() {
        if(::session.isInitialized) {
            runCatching { File(folder(),"native-state.json").writeText(JSONObject().put("threadId",session.selectedThread).put("error",session.error).put("records",JSONArray(session.operationRecords.map { JSONObject().put("id",it.id).put("kind",it.kind).put("phase",it.phase).put("intent",it.intent).put("intentSha256",it.intentSha256) })).toString(2)) }
            main { session.leaveForeground();previousClip?.let { systemClipboard.setPrimaryClip(it) }?:systemClipboard.clearPrimaryClip() }
        }
        val now=NativeSessionStorage(i.targetContext).metadata()
        for((k,v)in drafts)assertEquals("Default $k remains intact",v,now?.opt(k)?.toString())
    }
    private fun handles(end: Boolean=false)=ui.onAllNodes(SemanticsMatcher("Visible code selection handle") { n -> n.config.any { it.key.name=="SelectionHandleInfo"&&it.value.toString().contains("visible=true")&&(!end||it.value.toString().contains("SelectionEnd")) } },useUnmergedTree=true)
    private fun control(value: JSONObject) {
        val url=java.net.URI(config.getString("control"));require(url.scheme=="http"&&url.host=="127.0.0.1")
        val http=url.resolve("/complete").toURL().openConnection()as java.net.HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+config.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size);http.outputStream.use { it.write(bytes) }
            assertEquals(200,http.responseCode);http.inputStream.close()
        }finally{http.disconnect()}
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
        val frames=CountDownLatch(1);i.runOnMainSync { window.decorView.postOnAnimation { window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());assertEquals(i.targetContext.packageName,window.decorView.context.packageName);bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-main-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).put("productionChatUi",true).toString())
        }finally{image.recycle()}
    }
    @Test fun actualModelCodeReplySelectionAndWholeBodyCopy() {
        main { session.newChat(config.getString("projectId")) }
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(config.getString("model"),"xhigh","fast","read-only");ui.onNodeWithTag("sheet-done").performClick()
        main { session.configure(session.generationSettings.copy(collaborationMode="default")) }
        ui.onNodeWithTag("message-input").performTextReplacement(config.getString("text"))
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest!=null }
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertTrue(session.operationRecords.isEmpty());ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(180_000) { session.selectedThread.isNotEmpty()&&!session.running&&session.transferStatus==null&&session.messages.any { it.role=="assistant"&&it.text==config.getString("expected") } }
        val reply=session.messages.single { it.role=="assistant"&&it.text==config.getString("expected") }
        val record=session.operationRecords.single();assertEquals("creation",record.kind);assertEquals("confirmed",record.phase)
        verifyReply(record.id,record.intent,record.intentSha256)
    }
    @Test fun readOriginalActualModelCodeReplySelectionAndWholeBodyCopy() {
        assertTrue(config.getBoolean("readOnly"));val thread=config.getString("threadId")
        ui.waitUntil(45_000) { session.threads.any { it.id==thread } }
        main { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(60_000) { !session.historyLoading&&session.messages.any { it.role=="assistant"&&it.text==config.getString("expected") } }
        assertTrue(session.operationRecords.isEmpty())
        val r=config.getJSONObject("originalRecord");verifyReply(r.getString("operationId"),r.getString("intent"),r.getString("intentSha256"))
        assertTrue(session.operationRecords.isEmpty())
    }
    private fun verifyReply(operationId: String,intent: String,intentSha256: String) {
        val reply=session.messages.single { it.role=="assistant"&&it.text==config.getString("expected") }
        val independent="Unsent independent model-code draft "+config.getString("nonce");main { session.text=independent }
        val tag="markdown-${reply.id}-0";val body=config.getString("body")
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("$tag-copy-code"))
        val layouts=mutableListOf<TextLayoutResult>();val node=ui.onNode(hasText(body),useUnmergedTree=true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) };node.performTouchInput { longClick(layouts.single().getBoundingBox(3).center) }
        ui.waitUntil(10_000) { handles().fetchSemanticsNodes().size==2 };shot("actual-model-code-selection")
        val before=handles(true).fetchSemanticsNodes().single().config.toString();val density=i.targetContext.resources.displayMetrics.density
        handles(true)[0].performTouchInput { swipe(center,center+Offset(45f*density,0f),400) }
        ui.waitUntil(10_000) { handles(true).fetchSemanticsNodes().single().config.toString()!=before };shot("actual-model-code-handle-drag")
        ui.onNodeWithTag("$tag-copy-code").performClick();ui.runOnIdle { val actual=systemClipboard.primaryClip?.getItemAt(0)?.text?.toString();assertEquals(body,actual);copied+=checkNotNull(actual) }
        ui.onNodeWithText("已复制").assertDoesNotExist();assertEquals(independent,session.text);shot("actual-model-code-whole-copy")
        control(JSONObject().put("threadId",session.selectedThread).put("operationId",operationId).put("intent",intent).put("intentSha256",intentSha256).put("newNativeOperations",session.operationRecords.size).put("reply",reply.text).put("copied",copied.single()).put("draftPreserved",true).put("sdkSelection",true))
    }
}
