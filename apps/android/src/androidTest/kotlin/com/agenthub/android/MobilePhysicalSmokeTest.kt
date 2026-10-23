package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.*

/** Foreground-only device acceptance without granting OEM background-start permissions. */
@RunWith(AndroidJUnit4::class)
class MobilePhysicalSmokeTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val limit = Timeout.seconds(120)

    private fun foregroundActivity(): MainActivity {
        val i = InstrumentationRegistry.getInstrumentation()
        require(InstrumentationRegistry.getArguments().getString("physicalAcceptance") == "true")
        val command = "am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity"
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        var activity: MainActivity? = null
        ui.waitUntil(20_000) {
            i.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
            }
            activity != null
        }
        val main = activity!!
        return main
    }

    @Test fun foregroundNativeControls() {
        val i = InstrumentationRegistry.getInstrumentation()
        val main = foregroundActivity()
        i.runOnMainSync { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        fun waitTag(tag: String) = ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        fun shot(name: String) {
            i.runOnMainSync { assertFalse("Owned foreground Activity must remain alive", main.isDestroyed) }
            ui.waitForIdle()
            val folder = File(i.targetContext.getExternalFilesDir(null), "acceptance/A4-physical-smoke").apply { mkdirs() }
            val bitmap = i.uiAutomation.takeScreenshot()
            try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        try {
            ui.runOnIdle { main.session.resetAcceptance() }
            waitTag("onboarding"); ui.onNodeWithText("把电脑上的工作带在身边").assertIsDisplayed()
            shot("physical-onboarding")
            ui.onNodeWithTag("chat-menu").performClick(); waitTag("open-settings")
            ui.onNodeWithTag("open-settings").performClick(); waitTag("settings-demo-toggle")
            ui.onNodeWithTag("settings-demo-toggle").performClick(); waitTag("settings-done")
            ui.onNodeWithTag("settings-done").performClick(); waitTag("message-input")
            ui.onNodeWithTag("send-message").assertIsNotEnabled()
            ui.onNodeWithTag("message-input").performTextReplacement("真机原生任务🙂")
            ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            waitTag("stop-turn"); ui.onNodeWithTag("stop-turn").assertIsEnabled()
            shot("physical-running-demo")
            ui.onNodeWithTag("stop-turn").performClick(); waitTag("resume-task")
            ui.onNodeWithTag("resume-task").assertIsEnabled(); shot("physical-interrupted-demo")
            val continuation="继续完成上一个任务。"
            val previousCount=ui.runOnIdle { main.session.messages.count { it.role=="user" && it.text==continuation } }
            ui.onNodeWithTag("resume-task").performClick(); waitTag("stop-turn")
            ui.runOnIdle {
                assertEquals("Resume must add exactly one original continuation",previousCount+1,
                    main.session.messages.count { it.role=="user" && it.text==continuation })
                assertTrue(main.session.running)
            }
            // The stop control can compose before LazyColumn places the newly
            // appended row on a taller OEM screen. Locate the real row, then
            // require it to be visibly rendered, rather than accepting data only.
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(continuation))
            ui.onNodeWithText(continuation).assertIsDisplayed()
            assertTrue(main.session.selectedClient.isDemo)
            shot("physical-resumed-demo")
        } finally {
            i.runOnMainSync { if (!main.isDestroyed) main.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    @Test fun actualNativeModelTask() {
        val i=InstrumentationRegistry.getInstrumentation()
        val configPath=InstrumentationRegistry.getArguments().getString("physicalModelConfig") ?: error("Dedicated original-owner examiner required")
        val config=JSONObject(File(configPath).readText())
        val main=foregroundActivity();val session=main.session
        i.runOnMainSync{main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-physical-model").apply{mkdirs()}
        fun shot(name:String){ui.waitForIdle();val bitmap=i.uiAutomation.takeScreenshot();try{File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
        try {
            ui.runOnIdle{session.resetAcceptance()}
            runBlocking{withContext(Dispatchers.Main){session.connect("真机真实任务验收",config.getString("endpoint"),config.getString("token"))}}
            ui.waitUntil(60_000){session.hasClient&&!session.loading&&session.threads.any{it.id==config.getString("threadId")}}
            ui.runOnIdle{session.selectThread(session.threads.single{it.id==config.getString("threadId")});session.retryModels()}
            ui.waitUntil(60_000){!session.historyLoading&&session.runtime?.canControl==true&&session.models.isNotEmpty()}
            val normal=session.models.single{it.isDefault}
            ui.runOnIdle{session.configure(GenerationSettings(model=normal.id,effort="low",speed="standard",permissionMode="read-only",collaborationMode="default"))}
            val marker="PHONE_READY_"+config.getString("nonce")
            val text="专用真机实际模型任务：只回复 $marker，不调用工具或修改文件。"
            ui.onNodeWithTag("message-input").performTextReplacement(text);ui.waitUntil(15_000){session.decision.enabled};ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(120_000){!session.running&&session.transferStatus==null&&session.messages.any{it.role=="assistant"&&it.text.trim()==marker}}
            val receipt=session.runtime!!.receipts.single{it.text==text}
            assertEquals("completed",receipt.state);assertFalse(receipt.turnId.isNullOrBlank());assertEquals("",session.text)
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));ui.onNodeWithText(marker).assertIsDisplayed();shot("physical-actual-model-reply")
            val draft="真机独立本地草稿";ui.onNodeWithTag("message-input").performTextReplacement(draft)
            val replacement=MobileSession(NativeSessionStorage(i.targetContext));ui.runOnIdle{replacement.restore()}
            ui.waitUntil(60_000){replacement.hasClient&&replacement.runtime!=null&&!replacement.historyLoading}
            assertEquals(draft,replacement.text);assertEquals(session.selectedThread,replacement.selectedThread)
            ui.runOnIdle{replacement.leaveForeground()};shot("physical-actual-reply-restored-draft")
            File(folder,"native-proof.json").writeText(JSONObject().put("threadId",session.selectedThread).put("operationId",receipt.operationId).put("turnId",receipt.turnId).put("marker",marker).put("draftRestored",true).toString())
        }finally{i.runOnMainSync{session.leaveForeground();if(!main.isDestroyed)main.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    }

}
