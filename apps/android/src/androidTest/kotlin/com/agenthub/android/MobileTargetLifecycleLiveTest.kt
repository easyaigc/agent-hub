package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Opt-in native attachment and unloaded-owner acceptance; no fixture model output. */
@RunWith(AndroidJUnit4::class)
class MobileTargetLifecycleLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"target-lifecycle-live.json").readText()) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,data: JSONObject=JSONObject()) {
        require(route in listOf("assert-unloaded","observe"))
        val base=URI(config.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=180_000
            http.setRequestProperty("Authorization","Bearer "+config.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=data.toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Independent exact original owner",200,http.responseCode);http.inputStream.close()
        } finally { http.disconnect() }
    }
    private fun shot(name: String) {
        runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() };ui.waitForIdle()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"acceptance/P1-target-live/"+config.getString("nonce")).apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun files(suffix: String): Pair<String,String> {
        val textCode="TXT-"+UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        val imageCode="IMG-"+UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        main {
            session.addAttachment("owned-$suffix.txt","text/plain","code=$textCode\n中文内容\n".toByteArray())
            val image=Bitmap.createBitmap(1100,260,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE)
            Canvas(image).drawText(imageCode,60f,165f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=100f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD) })
            val bytes=java.io.ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);image.recycle()
            session.addAttachment("owned-$suffix.png","image/png",bytes.toByteArray())
        }
        return textCode to imageCode
    }
    private fun send(text: String) {
        assertEquals(config.getString("hostId"),session.selectedClient.id)
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        val before=session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) }
        val attachments=session.attachments.map { it.manifest.sha256 }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest!=null || session.transferStatus!=null || session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) }!=before }
        if(session.aiSharingRequest!=null) {
            assertEquals(config.getString("hostId"),session.aiSharingRequest!!.scope.target.hostId)
            assertEquals(before,session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) })
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.aiSharingRequest==null && !session.aiSharing.busy }
            assertEquals(text,session.text);assertEquals(attachments,session.attachments.map { it.manifest.sha256 })
            assertEquals(before,session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) })
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        }
    }
    private fun complete(suffix: String,kind: String) {
        val codes=files(suffix);val originalFiles=session.attachments.map { it.manifest.name to it.manifest.sha256 }
        val marker="P1_${suffix}_"+config.getString("nonce")
        val expected="$marker|${codes.first}|${codes.second}"
        val text="安卓专用附件验收：实际读取本轮 owned-$suffix.txt 的 code 值和 owned-$suffix.png 中的字符，只回复 $marker|TXT的code值|图片字符，不加格式。不猜测，不更改任何文件或其他聊天。随机码只在附件中。"
        assertFalse(text.contains(codes.first));assertFalse(text.contains(codes.second))
        send(text)
        ui.waitUntil(180_000) { session.messages.any { it.role=="assistant" && it.replyPhase=="final_answer" && it.text.trim()==expected } && !session.running && session.transferStatus==null }
        val record=session.operationRecords.single { it.kind==kind && JSONObject(it.intent).optString("text")==text }
        val intent=record.intent;val hash=record.intentSha256
        if(kind=="creation") {
            assertEquals("confirmed",record.phase);assertNull(JSONObject(intent).optional("projectId"))
            val manifest=JSONObject(intent).getJSONArray("attachments").objects().map { it.getString("name") to it.getString("sha256") }
            assertEquals(originalFiles,manifest)
        }
        control("observe",JSONObject().put("kind",kind).put("threadId",session.selectedThread).put("operationId",record.id).put("expected",expected))
        ui.onNodeWithTag("message-input").performTextReplacement("下一条独立草稿 $suffix")
        assertEquals(intent,session.operationRecords.single { it.id==record.id }.intent)
        assertEquals(hash,session.operationRecords.single { it.id==record.id }.intentSha256)
        shot("completed-$suffix")
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun projectlessMixedFirstInputAndUnloadedOwnerReply() {
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("P1 独立目标验收",config.getString("endpoint"),config.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.canCreateProjectless }
        require(!(config.optBoolean("projectlessOnly",false)&&config.optBoolean("unloadedOnly",false)))
        if(!config.optBoolean("unloadedOnly",false)) {
            ui.onNodeWithTag("new-chat").performClick();ui.onNodeWithTag("projectless-new").performScrollTo().performClick()
            ui.runOnIdle { session.configure(GenerationSettings(model="gpt-6.1-sol",effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
            complete("PROJECTLESS","creation")
        }
        if(config.optBoolean("projectlessOnly",false))return
        control("assert-unloaded")
        val source=config.getString("sourceThreadId")
        ui.runOnIdle { session.refresh(userInitiated=true) }
        ui.waitUntil(45_000) { !session.loading && session.threads.any { it.id==source } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==source }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.runtime!=null && session.canCompose }
        assertFalse("Read-only history cannot load an execution owner",session.runtime!!.canControl==true)
        control("assert-unloaded");shot("unloaded-history-before-send")
        ui.runOnIdle { session.configure(GenerationSettings(model="gpt-6.1-sol",effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
        complete("UNLOADED","reply")
        assertTrue(session.runtime!!.canControl==true)
        val open=session.operationRecords.single { it.kind=="open" && JSONObject(it.intent).getString("threadId")==source }
        assertEquals("opened",open.phase)
        assertEquals(source,session.selectedThread)
    }
}
