package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual native form layout and editable-name boundaries; no connection or model submission. */
class MobileSettingsRowTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val args get()=InstrumentationRegistry.getArguments()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"settings-row");session.resetAcceptance()
            session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light") }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ExecutionFixture) {
        f.computerName="本机 Mac"
        main { session.connect("本机 Mac",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.models.isNotEmpty() }
    }
    private fun tap(tag: String) {
        val node=ui.onNodeWithTag(tag)
        if(tag !in listOf("chat-menu","open-settings"))node.performScrollTo()
        node.assertIsDisplayed().performClick();ui.waitForIdle()
    }
    private fun settings() { tap("chat-menu");tap("open-settings") }
    private fun add() { settings();tap("settings-add-client");tap("codex-connection-method");tap("codex-method-手动连接") }
    private fun value(tag: String)=ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.InputText].text
    @Test fun methodLabelAndDetailKeepWholeWordsInTheNativeViewport() {
        ExecutionFixture().use { f ->
            connect(f);add()
            val row=ui.onNodeWithTag("codex-connection-method").performScrollTo().assertIsDisplayed()
            for(text in listOf("Connection method","Manual connection")) {
                val node=ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("codex-connection-method")),useUnmergedTree=true)
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout=layouts.single();val rect=node.fetchSemanticsNode().boundsInRoot;val outer=row.fetchSemanticsNode().boundsInRoot
                assertTrue("Complete text stays inside its row",rect.left>=outer.left-1&&rect.right<=outer.right+1)
                for(line in 0 until layout.lineCount) {
                    assertTrue("Every rendered line fits the native text width",layout.getLineRight(line)<=layout.size.width+1)
                    if(line<layout.lineCount-1) {
                        val end=layout.getLineEnd(line,visibleEnd=true)
                        assertTrue("A normal English word must not be split: $text at $end",end==text.length||text.getOrNull(end)?.isWhitespace()==true||text.getOrNull(end-1)?.isWhitespace()==true)
                    }
                }
                File(owned.folder,text.replace(' ','-')+"-layout.json").writeText(JSONObject().put("text",text).put("lines",layout.lineCount)
                    .put("width",layout.size.width).put("fontScale",ui.density.fontScale).put("wholeWords",true).toString())
            }
            owned.dialogShot("connection-row-readable")
            row.performClick();ui.onNodeWithTag("codex-method-扫码连接").assertIsDisplayed()
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun newSeedUsesCurrentLanguageAndEditedNameSurvivesLanguageChange() {
        ExecutionFixture().use { f ->
            connect(f);val original=session.selectedClient;add()
            assertEquals(NativeL10n(ui.activity,"en").tr("本机 Mac"),value("client-name"))
            val raw="本机 Mac 100% %@"
            ui.onNodeWithTag("client-name").performTextReplacement(raw)
            main { session.changeLanguage("zh-Hans") };ui.waitForIdle()
            assertEquals(raw,value("client-name"));assertEquals(original,session.selectedClient)
            owned.dialogShot("edited-name-is-raw-after-language-change")
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun existingClientNameThatEqualsAKeyIsNeverRetranslated() {
        ExecutionFixture().use { f ->
            connect(f);settings();tap("settings-clients");tap("edit-client-fixture-host")
            assertEquals("本机 Mac",value("client-name"))
            assertEquals(f.server.endpoint,value("client-address"))
            owned.dialogShot("existing-name-remains-raw")
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
}
