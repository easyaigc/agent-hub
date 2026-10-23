package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileFoundationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun freshNativeState() { ui.runOnIdle { ui.activity.session.resetAcceptance() } }
    private fun demo() {
        ui.onNodeWithTag("chat-menu").performClick(); ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithTag("settings-demo-toggle").performClick()
        ui.onNodeWithTag("settings-done").performClick()
    }
    private fun shot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val automation = instrumentation.uiAutomation
        // Compose can be idle while the system is still removing a Dialog's dim layer.
        automation.waitForIdle(200, 5_000)
        awaitNativeFrames()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "A1"
        val directory = File(context.getExternalFilesDir(null), "acceptance/$phase")
        directory.mkdirs()
        automation.takeScreenshot().useBitmap { bitmap -> File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private fun awaitNativeFrames() {
        val frames=CountDownLatch(1)
        ui.activityRule.scenario.onActivity { activity -> activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { frames.countDown() }
        } }
        assertTrue("Native window must present the asserted state before capture",frames.await(5,TimeUnit.SECONDS))
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    @Test fun initialNativeScreenAndEmptyComposer() {
        ui.onNodeWithTag("onboarding").assertExists()
        ui.onNodeWithText("把电脑上的工作带在身边").assertIsDisplayed()
        shot("onboarding-light")
        demo()
        ui.onNodeWithTag("message-input").assertIsDisplayed()
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
        shot("demo-completed-light")
    }

    @Test fun sharedSendStopAndResumeReachNativeControls() {
        demo()
        ui.onNodeWithTag("message-input").performTextInput("安卓原生任务🙂")
        awaitNativeFrames()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("stop-turn").fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
        shot("demo-running")
        ui.onNodeWithTag("stop-turn").performClick()
        ui.onNodeWithTag("resume-task").assertIsEnabled()
        shot("demo-interrupted")
        ui.onNodeWithTag("resume-task").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("stop-turn").fetchSemanticsNodes().size==1 }
        ui.onNodeWithText("继续完成上一个任务。").assertExists()
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
    }

    @Test fun draftsAndRunStateStayOnOriginalClient() {
        demo()
        ui.onNodeWithTag("message-input").performTextInput("原电脑草稿")
        ui.onNodeWithTag("client-selector").performClick()
        ui.onNodeWithTag("client-demo-pc").performClick()
        ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))).performTextInput("第二台电脑")
        ui.onNodeWithTag("send-message").performClick()
        ui.onNodeWithTag("client-selector").performClick()
        ui.onNodeWithTag("client-demo-mac").performClick()
        ui.onNodeWithTag("message-input").assertTextEquals("原电脑草稿")
        ui.onNodeWithTag("send-message").assertIsEnabled()
        ui.onNodeWithTag("client-selector").performClick()
        ui.onNodeWithTag("client-demo-pc").performClick()
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
        ui.onNodeWithTag("new-chat").performClick()
        ui.onNodeWithTag("projectless-new").assertIsEnabled().performClick()
        ui.onNodeWithTag("message-input").performTextInput("第二台新聊天草稿")
        ui.onNodeWithTag("client-selector").performClick()
        ui.onNodeWithTag("client-demo-mac").performClick()
        ui.onNodeWithTag("message-input").assertTextEquals("原电脑草稿")
    }

    @Test fun rotationRetainsTargetDraftAndAppearance() {
        demo()
        ui.onNodeWithTag("message-input").assertIsEnabled().performClick().assertIsFocused()
            .performTextInput("旋转保留草稿")
        ui.onNodeWithTag("message-input").assertTextEquals("旋转保留草稿")
        ui.runOnIdle { assertEquals("Draft must be present before Activity recreation", "旋转保留草稿", ui.activity.session.text) }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("message-input").assertTextEquals("旋转保留草稿")
        ui.onNodeWithTag("chat-menu").performClick(); ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithTag("settings-appearance").performClick()
        ui.onNodeWithTag("appearance-dark").performClick()
        ui.onNodeWithTag("sheet-done").performClick()
        ui.onNodeWithTag("message-input").assertTextEquals("旋转保留草稿")
        shot("demo-draft-dark")
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-list").assertIsDisplayed()
        shot("sidebar-dark")
    }

    @Test fun exactGoldenVectorsRunInsideNativeAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = JSONObject(context.assets.open("core-v1.json").bufferedReader().use { it.readText() })
        fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
        val identities = root.getJSONArray("identities")
        for (index in 0 until identities.length()) {
            val test = identities.getJSONObject(index); val input = test.getJSONObject("input")
            val target = input.optJSONObject("target")?.let { TargetScope(it.getString("agentId"), it.stringOrNull("accountAuthority"), it.stringOrNull("accountSubject"), it.getString("hostId"), it.getString("workspaceId"), it.getString("runtimeId")) }
            assertEquals(test.getString("name"), test.getString("expected"), MobileRules.cacheKey(input.getString("clientId"), target, input.stringOrNull("threadId"), input.stringOrNull("projectId")))
        }
        val composer = root.getJSONArray("composer")
        for (index in 0 until composer.length()) {
            val test = composer.getJSONObject(index); val input = test.getJSONObject("input"); val values = input.getJSONArray("messages")
            val messages = (0 until values.length()).map { values.getJSONObject(it).let { ComposerMessage(it.getString("role"), it.stringOrNull("status")) } }
            val actual = MobileRules.composer(ComposerInput(input.getBoolean("stopping"), input.getBoolean("running"), input.getBoolean("sending"), input.getBoolean("creationPending"), input.getString("text"), input.getInt("attachmentCount"), input.getBoolean("canSend"), input.getBoolean("canStop"), input.getBoolean("canRunAgain"), input.stringOrNull("runtimeState"), input.stringOrNull("lastReceiptState"), messages))
            val expected = test.getJSONObject("expected")
            assertEquals(test.getString("name"), expected.getString("action"), actual.action)
            assertEquals(test.getString("name"), expected.getBoolean("enabled"), actual.enabled)
        }
        val content = root.getJSONArray("content")
        for (index in 0 until content.length()) { val test = content.getJSONObject(index); assertEquals(test.getBoolean("expected"), MobileRules.hasContent(test.getString("text"), test.getInt("count"))) }
    }
}
