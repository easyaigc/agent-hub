package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.content.ClipboardManager
import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Opt-in real native examiner. Missing private configuration is a failure, never a skip. */
@RunWith(AndroidJUnit4::class)
class MobileLiveCodexTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(2400)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val fixture by lazy { JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-live-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private var lastInputAt=0L
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,data: JSONObject=JSONObject()) {
        require(route in listOf("arm-submit-loss","await-first-input","restart-host","observation"))
        val base=URI(fixture.getString("control")); require(base.scheme=="http" && base.host=="127.0.0.1" && base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST"; http.instanceFollowRedirects=false; http.connectTimeout=10_000; http.readTimeout=180_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey")); http.setRequestProperty("Content-Type","application/json")
            val bytes=data.put("nonce",nonce).toString().toByteArray(); http.doOutput=true; http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) }; assertEquals("Independent native examiner",200,http.responseCode); http.inputStream.close()
        } finally { http.disconnect() }
    }
    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
        ui.waitForIdle(); val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();i.uiAutomation.waitForIdle(1000,10_000)
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-live").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun input(text: String) {
        val gap=fixture.optLong("minimumModelGapMillis",0).also { require(it in 0L..60_000L) }
        val remaining=gap-(android.os.SystemClock.elapsedRealtime()-lastInputAt)
        if(remaining>0)android.os.SystemClock.sleep(remaining)
        ui.waitUntil(30_000) { session.canCompose&&session.transferStatus==null }
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(text)
        ui.waitUntil(10_000) { session.text==text&&session.decision.action=="send"&&session.decision.enabled }
        ui.onNodeWithTag("message-input").assertTextEquals(text)
        ui.onNodeWithTag("send-message").performClick()
        lastInputAt=android.os.SystemClock.elapsedRealtime()
    }
    private fun answer(marker: String) {
        try { ui.waitUntil(180_000) { session.messages.any { it.role=="assistant" && it.text.trim()==marker } && !session.running && session.transferStatus==null } }
        catch(failure: ComposeTimeoutException) {
            shot("failure-answer");val phases=session.operationRecords.map { it.kind+":"+it.phase }
            throw AssertionError("Native answer unavailable: state=${session.runtimeState}, pending=${session.transferStatus}, error=${session.error}, phases=$phases",failure)
        }
    }
    private fun observe(thread: String,operation: String,marker: String) = control("observation",JSONObject().put("hostId",fixture.getString("hostId")).put("threadId",thread).put("operationId",operation).put("marker",marker))
    private fun mixedFiles(suffix: String): Pair<String,String> {
        val textCode="TXT-"+UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        val imageCode="IMG-"+UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        main {
            session.addAttachment("native-$suffix-$nonce.txt","text/plain","code=$textCode\n中文附件内容验证\n".toByteArray())
            val image=Bitmap.createBitmap(1100,260,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE)
            Canvas(image).drawText(imageCode,60f,165f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=100f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD) })
            val bytes=java.io.ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);image.recycle()
            session.addAttachment("native-$suffix-$nonce.png","image/png",bytes.toByteArray())
        }
        return textCode to imageCode
    }
    private fun outputTask(marker: String,suffix: String)="专属安卓附件内容验收：先调用 list_projects 只读工具。实际读取本轮 native-$suffix-$nonce.txt 中的 code 值和 native-$suffix-$nonce.png 中的随机字符，不猜测。用 apply_patch 工具在本聊天所属的专用测试项目根目录添加 native-result-$suffix-$nonce.txt，正文第一行 TXT 的 code 值、第二行图片中的字符，末尾换行；不要更改已有文件或其他聊天。最后只回复 $marker|TXT的code值|图片中的字符，竖线分隔，不加格式。随机码仅在本轮附件中。"
    private fun waitForClipboardPreview() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val oldFlags=automation.serviceInfo.flags
        fun containsMarker(node: AccessibilityNodeInfo): Boolean {
            if(node.text?.toString()?.startsWith("ANDROID_A4_")==true || node.contentDescription?.toString()?.startsWith("ANDROID_A4_")==true) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsMarker)==true }
        }
        try {
            automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            Thread.sleep(700)
            ui.waitUntil(20_000) { automation.windows.none { window -> window.root?.let { root -> root.packageName=="com.android.systemui" && containsMarker(root) }==true } }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { flags=oldFlags } }
    }
    private fun verifyOutput(expected: String,suffix: String) {
        val reply=session.messages.last { it.role=="assistant" && it.text.trim()==expected && it.replyPhase=="final_answer" }
        val patch=session.messages.last { it.runId==reply.runId && it.kind=="fileChange" }
        assertEquals("completed",patch.status);assertTrue(patch.text.contains("native-result-$suffix-$nonce.txt"))
        val group=session.timeline.rows.single { it.kind=="tools" && it.indices.any { index->session.messages[index].id==patch.id } }
        if(group.indices.size>1) {
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-group-toggle-${group.id}"))
            ui.onNodeWithTag("tool-group-toggle-${group.id}").performClick()
        }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-toggle-${patch.id}"))
        ui.onNodeWithTag("tool-toggle-${patch.id}").performClick()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-${patch.id}-code"))
        ui.onNodeWithTag("tool-${patch.id}-code").assertIsDisplayed();shot("native-$suffix-real-diff")
        ui.onNodeWithTag("tool-toggle-${patch.id}").performClick()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("copy-reply-${reply.id}"))
        ui.onAllNodesWithText(expected).onLast().assertIsDisplayed()
        ui.onNodeWithTag("copy-reply-${reply.id}").performClick()
        val clipboard=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.runOnIdle { assertEquals("Only the actual final body is copied",expected,clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        waitForClipboardPreview()
    }
    private fun creation(projectId: String?,suffix: String,attachments: Boolean=false): Pair<String,String> {
        val marker="ANDROID_A4_"+suffix+"_"+nonce
        ui.runOnIdle { session.newChat(projectId); session.configure(GenerationSettings(permissionMode=if(attachments) "full-access" else "read-only",collaborationMode="default")) }
        assertEquals("",session.selectedThread)
        val codes=if(attachments) mixedFiles(suffix) else null
        val expected=if(codes!=null) "$marker|${codes.first}|${codes.second}" else marker
        input(if(attachments) outputTask(marker,suffix) else "Agent Hub 安卓原生开发验收：请调用 list_projects 只读工具，然后只回复 $marker。不要更改文件或其他聊天。")
        ui.waitUntil(90_000) { session.transferStatus==null&&(session.selectedThread.isNotEmpty()||session.operationRecords.any { it.kind=="creation"&&it.phase in listOf("uncertain","outcome-unknown")&&JSONObject(it.intent).getString("text").contains(marker) }) }
        if(session.selectedThread.isEmpty()) {
            val unknown=session.operationRecords.single { it.kind=="creation"&&JSONObject(it.intent).getString("text").contains(marker) }
            val originalHash=unknown.intentSha256
            // Explicitly inspect/continue the same durable operation; never tap send
            // or construct another first-input intent after an uncertain handoff.
            ui.onNodeWithTag("query-operation-${unknown.id}").performScrollTo().performClick()
            ui.waitUntil(90_000) { session.selectedThread.isNotEmpty()&&session.transferStatus==null }
            assertEquals(originalHash,session.operationRecords.single { it.id==unknown.id }.intentSha256)
        }
        answer(expected)
        val record=session.operationRecords.single { it.kind=="creation" && JSONObject(it.intent).getString("text").contains(marker) }
        assertEquals("confirmed",record.phase);assertTrue(record.submissionAttempted);assertEquals(projectId,JSONObject(record.intent).optional("projectId"))
        if(attachments) verifyOutput(expected,suffix)
        val completedThread=session.selectedThread
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(expected))
        ui.onAllNodesWithText(expected).onLast().assertIsDisplayed()
        observe(completedThread,record.id,expected)
        ui.onAllNodesWithText(expected).onLast().assertIsDisplayed()
        shot("native-$suffix")
        return completedThread to record.id
    }
    @Test fun originalOwnerFirstInputsReplyAndIsolatedHostRecovery() {
        val i=InstrumentationRegistry.getInstrumentation()
        // Starting through the shell from inside instrumentation also handles
        // OEM systems that do not foreground ActivityScenario's in-app launch.
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var resumed: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };resumed!=null }
        activity=resumed!!
        val keptAwake=activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0
        i.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
        // Cancel restored connection work before awaiting Compose idle. A saved
        // live connection may still be loading when this opt-in examiner starts.
        main { session.resetAcceptance() }
        main { session.connect("原桌面开发验收",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.canCreateThread && session.canCreateProjectless }
        assertEquals(fixture.getString("hostId"),session.selectedClient.id)
        assertTrue(session.workspace!!.capabilities.containsAll(listOf("runtime:control-required","threads:create:staged","commands:target-bound:v1")))
        val projects=fixture.getJSONArray("projects").objects();ui.runOnIdle { session.refresh() };ui.waitUntil(45_000) { !session.loading && projects.all { p->session.projects.any { it.id==p.getString("id") } } }
        val first=creation(projects.single { it.getString("kind")=="project" }.getString("id"),"PROJECT",attachments=true)
        creation(projects.single { it.getString("kind")=="empty" }.getString("id"),"EMPTY")
        control("arm-submit-loss")
        val marker="RECOVERY_"+nonce
        ui.runOnIdle { session.newChat(); session.configure(GenerationSettings(permissionMode="read-only")) }
        input("安卓 Host 重启恢复验收：调用 list_projects 只读工具，然后只回复 $marker。不要修改任何文件或其他聊天。")
        ui.waitUntil(90_000) { session.operationRecords.any { it.kind=="creation" && it.phase=="uncertain" && JSONObject(it.intent).getString("text").contains(marker) } && session.transferStatus==null }
        val unknown=session.operationRecords.first { it.kind=="creation" && JSONObject(it.intent).getString("text").contains(marker) }
        assertTrue(unknown.submissionAttempted)
        // This scenario verifies recovery of an already submitted first input.
        // Keep the native receipt unavailable, but independently prove the write
        // before ending its wait; pre-input detach has a separate contract.
        control("await-first-input")
        ui.runOnIdle { session.detachOperation(unknown) }
        ui.waitUntil(10_000) { session.operationRecords.any { it.id==unknown.id && it.detached } }
        ui.runOnIdle { session.text="后来的可编辑草稿" };shot("native-recovery-unknown-detached")
        control("restart-host")
        ui.runOnIdle { session.refresh(userInitiated=true) }
        ui.waitUntil(45_000) { !session.loading };ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("query-detached-${unknown.id}").performClick()
        ui.waitUntil(120_000) { session.operationRecords.any { it.id==unknown.id && it.phase=="confirmed" } && session.transferStatus==null }
        assertEquals("",session.selectedThread);assertEquals("后来的可编辑草稿",session.text)
        val recovered=session.operationRecords.single { it.id==unknown.id };assertTrue(recovered.detached);assertEquals(unknown.intentSha256,recovered.intentSha256)
        val thread=JSONObject(recovered.result!!).getString("threadId");ui.waitUntil(45_000) { session.threads.any { it.id==thread } }
        observe(thread,recovered.id,marker);shot("native-recovery-keeps-new-draft")
        ui.waitUntil(45_000) { session.canCreateProjectless }
        creation(null,"AFTER_RESTART")
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==first.first }) }
        ui.waitUntil(45_000) { session.runtime?.canControl==true && !session.historyLoading && session.canCompose }
        val replyMarker="ANDROID_A4_REPLY_"+nonce
        ui.runOnIdle { session.configure(GenerationSettings(permissionMode="full-access",collaborationMode="default")) }
        val codes=mixedFiles("REPLY");val expected="$replyMarker|${codes.first}|${codes.second}"
        input(outputTask(replyMarker,"REPLY"));answer(expected);verifyOutput(expected,"REPLY")
        val reply=session.operationRecords.single { it.kind=="reply" && JSONObject(it.intent).getString("text").contains(replyMarker) }
        assertEquals(first.first,JSONObject(reply.intent).getString("threadId"));observe(first.first,reply.id,expected);shot("native-owner-reply-after-host-restart")
        } finally { i.runOnMainSync { session.leaveForeground();if(!keptAwake)activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    }
}
