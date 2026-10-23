package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Actual reader/owner history enrichment and a durable unknown operation; no fake failed Turn. */
@RunWith(AndroidJUnit4::class)
class MobileLiveHistoryIdentityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun shot(name: String) {
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(700,10_000)
        val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-history-identity").apply { mkdirs() };val bitmap=i.uiAutomation.takeScreenshot()
        try {File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun originalOwnerHasOneInputWhileEnrichedNativeHistoryHasOneBody() {
        val i=InstrumentationRegistry.getInstrumentation();val p=InstrumentationRegistry.getArguments().getString("identityConfig") ?: error("Use a dedicated actual-history examiner")
        i.uiAutomation
        val c=JSONObject(File(p).readText());val session=ui.activity.session;val nonce=c.getString("nonce")
        fun step(value:String) {File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-history-identity").apply{mkdirs()}.resolve("progress.json").writeText(JSONObject().put("step",value).toString())}
        fun dismissShownError() {
            ui.waitForIdle()
            ui.waitUntil(15_000){session.error==null||ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty()}
            if(ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("dismiss-error").performClick()
            ui.waitUntil(15_000){session.error==null}
        }
        fun status()=runBlocking(Dispatchers.IO) {
            val conn=URL(c.getString("endpoint")+"/acceptance/history-identity-status").openConnection() as HttpURLConnection
            try {conn.setRequestProperty("Authorization","Bearer "+c.getString("token"));conn.connectTimeout=3000;conn.readTimeout=3000;JSONObject(conn.inputStream.bufferedReader().readText()).getBoolean("directoryRestored")} finally {conn.disconnect()}
        }
        try {
            ui.runOnIdle { session.resetAcceptance() }
            runBlocking { withContext(Dispatchers.Main) {session.connect("真实历史身份验收",c.getString("endpoint"),c.getString("token"))} }
            ui.waitUntil(60_000){session.hasClient&&!session.loading}
            ui.runOnIdle{session.newChat(c.getString("projectId"));session.configure(GenerationSettings(permissionMode="read-only",collaborationMode="default"))}
            val baseline="BASELINE_$nonce";val first="专属新测试聊天：只回复 $baseline，不调用工具或修改文件。"
            ui.onNodeWithTag("message-input").performTextReplacement(first);ui.waitUntil(15_000){session.decision.enabled};ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(100_000){session.selectedThread.isNotEmpty()&&!session.running&&session.messages.any{it.role=="assistant"&&it.text.trim()==baseline}&&session.transferStatus==null}
            val thread=session.selectedThread
            step("baseline-completed")
            val input="专属目录可用性验收 $nonce：仅在当前聊天所属目录执行 pwd，然后说明结果，不修改任何文件或其他聊天。"
            ui.onNodeWithTag("message-input").performTextReplacement(input);ui.waitUntil(15_000){session.decision.enabled};ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(90_000){session.operationRecords.any{it.kind=="reply"&&it.submissionAttempted&&it.phase in listOf("uncertain","outcome-unknown")}&&session.transferStatus==null}
            val original=session.operationRecords.single{it.kind=="reply"};val sha=original.intentSha256
            step("unknown-operation-preserved")
            ui.waitUntil(60_000){status()}
            step("directory-restored")
            ui.runOnIdle{session.loadHistory()};ui.waitUntil(30_000){!session.historyLoading&&session.runtime?.canControl==true}
            step("history-control-refreshed")
            assertEquals(1,session.messages.count{it.role=="user"&&it.text==first})
            assertEquals(1,session.messages.count{it.role=="assistant"&&it.text.trim()==baseline})
            assertEquals(input,session.text)
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitForIdle();step("keyboard-hidden-before-history-scroll")
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(baseline));shot("reader-owner-one-native-body")
            ui.runOnIdle{session.queryOperation(original)};ui.waitUntil(30_000){session.transferStatus==null}
            assertEquals(sha,session.operationRecords.single{it.id==original.id}.intentSha256)
            dismissShownError()
            ui.runOnIdle{session.detachOperation(session.operationRecords.single{it.id==original.id})}
            ui.waitUntil(15_000){session.operationRecords.single{it.id==original.id}.detached&&session.canCompose}
            dismissShownError()
            val marker="RECOVERED_$nonce";val recovery="专用恢复验收：只回复 $marker，不修改文件或其他聊天。"
            ui.onNodeWithTag("message-input").performTextReplacement(recovery);ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(100_000){!session.running&&session.messages.any{it.role=="assistant"&&it.text.trim()==marker}&&session.transferStatus==null}
            assertEquals(thread,session.selectedThread);assertEquals(1,session.messages.count{it.role=="user"&&it.text==first});assertEquals(1,session.messages.count{it.role=="assistant"&&it.text.trim()==baseline})
            val retained=session.operationRecords.single{it.id==original.id};assertEquals(sha,retained.intentSha256);assertTrue(retained.detached);assertTrue(retained.phase in listOf("uncertain","outcome-unknown"))
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));shot("explicit-new-input-no-automatic-replay")
            val replacement=MobileSession(NativeSessionStorage(i.targetContext));ui.runOnIdle{replacement.restore()}
            ui.waitUntil(60_000){replacement.hasClient&&!replacement.historyLoading&&replacement.runtime!=null}
            val restored=replacement.operationRecords.single{it.id==original.id};assertEquals(sha,restored.intentSha256);assertTrue(restored.detached)
            ui.runOnIdle{replacement.leaveForeground()}
            val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-history-identity")
            File(dir,"native-proof.json").writeText(JSONObject().put("threadId",thread).put("unknownOperation",original.id).put("intentSha256",sha).put("restoredUnknown",true).put("baselineMarker",baseline).put("recoveredMarker",marker).put("nativeBaselineUsers",1).put("nativeBaselineBodies",1).toString())
        }catch(failure:Throwable){runCatching{shot("failed-native")}.exceptionOrNull()?.let{failure.addSuppressed(it)};throw failure}
    }
}
