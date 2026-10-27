package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** English product actions retain literal project/file/draft values and never submit them. */
class MobileActionCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val args get()=InstrumentationRegistry.getArguments()
    private val rawName="设置 100% %@ 目录"
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"action-copy") }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ExecutionFixture) {
        val native=NativeHostReads { "" }
        val reads=object: HostReads by native {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val value=native.workspace(client,projectCursor,threadCursor)
                return value.copy(projects=value.projects.map { it.copy(name=rawName) })
            }
        }
        main { session.resetAcceptance(reads);session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light")
            session.connect("Synthetic literal action copy",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.models.isNotEmpty() }
        main { allowSyntheticRegressionSharing(session) }
    }
    @Test fun englishActionsKeepOriginalMessageAndUnsentDraft() {
        ExecutionFixture().use { f ->
            connect(f)
            ui.onNodeWithTag("open-sidebar").assertContentDescriptionEquals("Open sidebar")
            ui.onNodeWithTag("new-chat").assertContentDescriptionEquals("New chat")
            ui.onNodeWithTag("chat-menu").assertContentDescriptionEquals("Conversation options")
            ui.onNodeWithTag("composer-add").assertContentDescriptionEquals("Add attachment")
            ui.onNodeWithTag("voice-input").assertContentDescriptionEquals("Voice input")
            ui.onNodeWithText("Ask "+session.agentTitle).assertExists()
            ui.onNodeWithTag("copy-reply-old-assistant").assertContentDescriptionEquals("Copy reply")
            ui.onNodeWithTag("share-reply-old-assistant").assertContentDescriptionEquals("Share reply")
            val original=session.messages.map { it.text }
            main { session.text="设置 / 复制 100% %@\nOriginal raw draft" }
            ui.onNodeWithTag("message-input").assertTextEquals("设置 / 复制 100% %@\nOriginal raw draft")
            ui.onNodeWithTag("send-message").assertContentDescriptionEquals("Send message")
            owned.nodeShot(ui.onNodeWithTag("composer"),"english-raw-draft")
            assertEquals(original,session.messages.map { it.text })
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun fileAndProjectTemplatesKeepLiteralNamesAcrossNavigation() {
        ExecutionFixture().use { f ->
            connect(f);val filename="设置 100% %@.txt"
            main { session.text=rawName;session.addAttachment(filename,"text/plain","original bytes".toByteArray()) }
            val id=session.attachments.single().manifest.id
            ui.onNodeWithTag("draft-attachment-remove-"+id).assertContentDescriptionEquals("Remove "+filename)
            owned.nodeShot(ui.onNodeWithTag("draft-attachment-"+id),"literal-file-removal-label")
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("project-empty-project").performScrollTo().assertTextEquals(rawName)
            ui.onNodeWithTag("new-in-empty-project").assertContentDescriptionEquals("New conversation in "+rawName)
            owned.nodeShot(ui.onNodeWithTag("project-empty-project").onParent(),"literal-project-action")
            ui.onNodeWithTag("close-sidebar").assertContentDescriptionEquals("Close sidebar").performClick()
            assertEquals(rawName,session.text);assertEquals(filename,session.attachments.single().manifest.name)
            ui.onNodeWithTag("draft-attachment-remove-"+id).performScrollTo().performClick()
            assertTrue(session.attachments.isEmpty());assertEquals(rawName,session.text)
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun newDraftAndFileMenuChangeLanguageWithoutSending() {
        ExecutionFixture().use { f ->
            connect(f);main { session.newChat() };ui.waitForIdle()
            ui.onNodeWithText("Start a new conversation").assertExists()
            ui.onNodeWithText("Choose a project or continue a conversation from the sidebar.").assertExists()
            ui.onNodeWithTag("composer-add").performClick()
            ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag("choose-files"));ui.onNodeWithTag("choose-files").performScrollTo().assertTextEquals("Choose file")
            if(android.os.Build.VERSION.SDK_INT<28)owned.dialogShot("english-choose-file-panel")else owned.nodeShot(ui.onNodeWithTag("choose-files"),"english-add-files")
            main { session.changeLanguage("zh-Hans") };ui.waitForIdle()
            ui.onNodeWithText("开始一段新对话").assertExists()
            ui.onNodeWithTag("composer-add").assertContentDescriptionEquals("添加附件")
            ui.onNodeWithTag("choose-files").performScrollTo().assertTextEquals("选择文件")
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
}
