package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Native consent/settings copy, raw grant names and language-specific public-link boundary. */
class MobileSharingCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val links=mutableListOf<String>()
    private val privacyMonitor=object: android.app.Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: android.content.Intent?): android.app.Instrumentation.ActivityResult? {
            val url=intent?.data
            if(intent?.action==android.content.Intent.ACTION_VIEW&&url?.scheme=="https"&&url.host=="51easyai.com"&&
                url.path in listOf("/agent-hub/privacy.html","/agent-hub/privacy-en.html")) {
                assertNull(url.userInfo);assertNull(url.query)
                links.add(url.toString())
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
            return null
        }
    }
    private val args get()=InstrumentationRegistry.getArguments()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        InstrumentationRegistry.getInstrumentation().addMonitor(privacyMonitor)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"sharing-copy")
            session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(args.getString("viewportTheme") ?: "light")
            session.configureVoice(ui.activity)

        }
    }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(privacyMonitor)
        ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } }
    }
    private fun t()=NativeL10n(ui.activity,session.language)
    private fun connect(f: ExecutionFixture) {
        f.computerName="设置 100% %@"
        main { session.connect("设置 100% %@",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.models.isNotEmpty() }
        main { session.text="Independent original draft 100% %@" }
    }
    private fun copy(key: String) {
        ui.onNodeWithText(t().tr(key)).performScrollTo().assertIsDisplayed()
    }
    private var tapIndex=0
    private fun tap(tag: String) {
        val node=ui.onNodeWithTag(tag)
        if(tag !in listOf("send-message","voice-input","chat-menu","open-settings"))node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var previous="";var since=0L
        ui.waitUntil(10_000) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root==null) { since=0L;previous="";false } else {
                assertEquals(instrumentation.targetContext.packageName,root.packageName?.toString())
                val bounds=node.fetchSemanticsNode().boundsInRoot
                val screen=android.graphics.Rect();root.getBoundsInScreen(screen)
                val value=bounds.toString()+screen.toString()
                val now=android.os.SystemClock.uptimeMillis()
                if(value!=previous) { previous=value;since=now }
                bounds.width>0&&bounds.height>0&&now-since>=700L
            }
        }
        File(owned.folder,"tap-"+(++tapIndex)+"-"+tag+".json").writeText(JSONObject().put("nativeRootPackage",instrumentation.targetContext.packageName)
            .put("bounds",previous).put("stableMs",700).put("attempts",1).put("action","native SDK touch").toString())
        node.performClick()
    }
    private fun openConsent() {
        tap("send-message")
        ui.waitUntil(10_000) { session.aiSharingRequest!=null }
        ui.onNodeWithTag("sheet-ai-sharing").assertExists()
    }
    private fun mainWindow() {
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("sheet-ai-sharing").fetchSemanticsNodes().isEmpty()&&ui.activity.window.decorView.hasWindowFocus()
        }
    }
    @Test fun permissionCopyAndLocaleLinkKeepRawNamesAndOriginalDraft() {
        ExecutionFixture().use { f ->
            connect(f);val original=session.targetKey;val draft=session.text;openConsent()
            val request=checkNotNull(session.aiSharingRequest)
            copy("发送到哪里");copy("电脑");copy("模型")
            ui.onNode(hasText("设置 100% %@") and hasAnyAncestor(hasTestTag(if(session.aiSharingRequest!=null) "ai-sharing-permission" else "ai-sharing-settings"))).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("合成模型").performScrollTo().assertIsDisplayed()
            for(key in listOf("第三方 AI 如何处理内容",
                "你的文字、图片、文件、问题回答及相关对话上下文将发送至这台电脑，并由电脑端实际配置的第三方 AI 服务处理，用于生成回复、分析附件和执行任务。",
                "模型名称不一定代表服务商；请在电脑端确认实际服务商、数据保留、训练用途和处理地区。切换电脑或模型后需要重新允许。选择跟随电脑时，你允许使用该电脑的当前 AI 配置。",
                "只发送你有权分享的内容。连接加密不影响 AI 服务读取已发送内容。你可以在设置中撤回许可；撤回不删除已经发送的内容，也不停止电脑上已有任务。"))copy(key)
            owned.dialogShot("english-sharing-explanation")
            tap("ai-sharing-privacy")
            assertEquals(listOf("https://51easyai.com/agent-hub/privacy-en.html"),links)
            main { session.changeLanguage("zh-Hans") }
            copy("发送到哪里");assertEquals(request,session.aiSharingRequest)
            ui.onNode(hasText("设置 100% %@") and hasAnyAncestor(hasTestTag(if(session.aiSharingRequest!=null) "ai-sharing-permission" else "ai-sharing-settings"))).performScrollTo().assertIsDisplayed()
            tap("ai-sharing-privacy")
            assertEquals("https://51easyai.com/agent-hub/privacy.html",links.last())
            copy("允许后返回原界面，再次点击发送或原操作按钮。当前草稿和附件会保留；本页不会自动发送。")
            owned.dialogShot("chinese-sharing-footer")
            tap("ai-sharing-decline");mainWindow()
            assertEquals(original,session.targetKey);assertEquals(draft,session.text)
            assertTrue(session.aiSharing.grants.isEmpty());assertTrue(f.server.requests.none { it.method=="POST" })
            File(owned.folder,"public-link-proof.json").writeText(JSONObject().put("urls",org.json.JSONArray(links))
                .put("nativeActionViewIntercepted",true).put("systemBrowserVerified",false).put("rawNamesPreserved",true).put("originalRequestUnchanged",true).put("noPost",true).toString())
        }
    }
    @Test fun settingsCopyAndRevokePreserveStoredRawNamesWithoutSending() {
        ExecutionFixture().use { f ->
            connect(f);val draft=session.text;openConsent()
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().assertTextContains(t().tr("允许此电脑和模型处理内容"));tap("ai-sharing-allow")
            ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy };mainWindow()
            assertTrue(f.server.requests.none { it.method=="POST" })
            val grant=session.aiSharing.grants.single()
            tap("chat-menu");tap("open-settings")
            tap("settings-ai-sharing")
            copy("发送前由你明确允许所选电脑及其 AI 配置处理文字、图片、文件和对话上下文。本地界面示例不访问 AI 服务。")
            copy("已允许的电脑和模型")
            ui.onNode(hasText("设置 100% %@") and hasAnyAncestor(hasTestTag(if(session.aiSharingRequest!=null) "ai-sharing-permission" else "ai-sharing-settings"))).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Codex · 合成模型").performScrollTo().assertIsDisplayed()
            copy("撤回后，新发送、回答及继续执行操作会再次询问。已经提交的内容和电脑端任务不受撤回影响；请到对应电脑或 AI 服务处理删除和停止请求。")
            owned.dialogShot("english-sharing-settings")
            tap("ai-sharing-privacy")
            assertEquals("https://51easyai.com/agent-hub/privacy-en.html",links.single())
            main { session.changeLanguage("zh-Hans") };assertEquals(grant,session.aiSharing.grants.single())
            tap("ai-sharing-revoke-"+grant.id)
            ui.waitUntil(10_000) { session.aiSharing.grants.isEmpty()&&!session.aiSharing.busy }
            copy("尚未允许共享");owned.dialogShot("chinese-sharing-revoked")
            assertEquals(draft,session.text);assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun providerModelNamedLikeProductCopyStaysLiteralInPermissionAndStoredGrant() {
        ExecutionFixture().use { f ->
            f.modelName="Codex 语音转写";connect(f);openConsent()
            assertEquals("fixture-model",session.aiSharingRequest!!.scope.model)
            ui.onNode(hasText("Codex 语音转写") and hasAnyAncestor(hasTestTag("ai-sharing-permission"))).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Codex transcription").assertDoesNotExist()
            tap("ai-sharing-allow");ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy };mainWindow()
            val grant=session.aiSharing.grants.single();assertEquals("Codex 语音转写",grant.modelName)
            tap("chat-menu");tap("open-settings");tap("settings-ai-sharing")
            ui.onNodeWithText("Codex · Codex 语音转写").performScrollTo().assertIsDisplayed()
            owned.dialogShot("provider-model-key-is-raw")
            main { session.changeLanguage("zh-Hans");session.changeLanguage("en") }
            assertEquals(grant,session.aiSharing.grants.single());assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun voiceConsentExplainsSavedAudioWithoutOpeningMicOrTranscription() {
        ExecutionFixture().use { f ->
            connect(f);val draft=session.text
            tap("voice-input")
            ui.waitUntil(10_000) { session.aiSharingRequest!=null }
            assertEquals("codex-dictation",session.aiSharingRequest!!.scope.model)
            copy("模型");ui.onNodeWithText(t().tr("Codex 语音转写")).performScrollTo().assertIsDisplayed()
            copy("录音结束后，原录音文件将发送至这台电脑，再使用 Codex 桌面当前 ChatGPT 账户提交给 OpenAI 的语音转写服务。手机只录音，返回的文字填入输入框，由你确认后发送。")
            owned.dialogShot("english-voice-sharing")
            tap("ai-sharing-allow")
            ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy };mainWindow()
            assertFalse(session.voice!!.active);assertEquals(draft,session.text)
            assertTrue(f.server.requests.none { it.method=="POST"||it.path.startsWith("/v1/voice/") })
        }
    }
}
