package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Explicit product copy must not reinterpret source content as translation keys. */
class MobileLocalizedContentTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val payload="val label = \"设置\" // 100% %@\r\n"
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"localized-content")
            session.resetAcceptance();session.setDemoMode(true);session.changeLanguage("en");session.changeAppearance("light")
            session.text="复制 100% %@ 中文"
            session.installAcceptanceHistory(listOf(MobileMessage("raw-user","user","设置"),MobileMessage("raw-body","assistant","复制"),
                MobileMessage("raw-code","assistant","```设置\n"+payload+"```")))
        }
        ui.waitForIdle()
    }
    @After fun finish() { if(::owned.isInitialized) { main { session.leaveForeground() };owned.verifyDefault() } }
    private fun reveal(tag: String) {
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag));ui.waitForIdle()
    }
    @Test fun originalUserAndModelTextRemainLiteralWhileTheMenuUsesEnglish() {
        val original=session.messages.toList();val target=session.targetKey
        reveal("message-raw-user");ui.onNodeWithTag("user-message-text-raw-user").assertTextEquals("设置")
        reveal("message-raw-body");ui.waitUntil(5_000) { ui.onAllNodesWithTag("markdown-raw-body-0").fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("markdown-raw-body-0").assertTextEquals("复制")
        ui.onNodeWithTag("message-input").assertTextEquals("复制 100% %@ 中文")
        ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("open-settings").assertTextEquals("Settings")
        assertEquals(original,session.messages);assertEquals(target,session.targetKey);assertEquals("复制 100% %@ 中文",session.text)
    }
    @Test fun englishCodeCopyKeepsOriginalFenceLanguageAndExactCrLfBytes() {
        reveal("message-raw-code")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("markdown-raw-code-0-copy-code").fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("markdown-raw-code-0-copy-code").assertTextEquals("Copy")
        ui.onNode(hasText("设置") and hasAnyAncestor(hasTestTag("markdown-raw-code-0-code")),useUnmergedTree=true).assertExists()
        val clipboard=main { ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
        val previous=main { clipboard.primaryClip }
        try {
            ui.onNodeWithTag("markdown-raw-code-0-copy-code").performClick();ui.waitForIdle()
            val actual=main { clipboard.primaryClip?.getItemAt(0)?.coerceToText(ui.activity)?.toString() }
            assertTrue("Product Copy returns the exact synthetic CRLF source",actual==payload)
            owned.nodeShot(ui.onNodeWithTag("message-raw-code"),"english-copy-original-code-language")
        }finally { main { if(previous!=null)clipboard.setPrimaryClip(previous) else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("","")) } }
    }
    @Test fun fileNamesAndTextRevisionsRemainOriginalAcrossDisplayLanguageChanges() {
        val draft=main { session.suggestionDraft };val target=session.targetKey
        main { session.importAttachments(session.attachmentImportDestination(),listOf({ LocalAttachmentInput("设置","text/plain",payload.toByteArray()) }))!!.await() }
        val files=session.attachments.map { it.manifest };assertEquals(1,files.size)
        main { session.changeLanguage("zh-Hans");session.changeLanguage("en") };ui.waitForIdle()
        ui.onNode(hasText("设置") and hasAnyAncestor(hasTestTag("draft-attachment-"+files.single().id)),useUnmergedTree=true).assertExists()
        val current=main { session.suggestionDraft };assertEquals(draft.text,current.text);assertEquals(draft.revision,current.revision)
        assertEquals(target,session.targetKey);assertEquals(files,session.attachments.map { it.manifest })
        assertArrayEquals(payload.toByteArray(),main { NativeDraftFiles(owned.storage).bytes(files.single()) })
        owned.nodeShot(ui.onNodeWithTag("bottom-controls"),"english-original-file-name-and-draft")
    }
}
