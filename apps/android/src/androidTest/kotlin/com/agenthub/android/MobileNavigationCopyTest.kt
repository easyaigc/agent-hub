package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** App-owned state words and form labels never become translations of metadata or submitted values. */
class MobileNavigationCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val args get()=InstrumentationRegistry.getArguments()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"navigation-copy");session.resetAcceptance()
            session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light") }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ExecutionFixture) {
        main { session.connect("原桌面连接",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.models.isNotEmpty() }
        main { allowSyntheticRegressionSharing(session);session.text="项目 100% %@ original draft" }
    }
    private fun tap(tag: String) { val node=ui.onNodeWithTag(tag);if(tag!="open-settings")node.performScrollTo();node.assertIsDisplayed().performClick();ui.waitForIdle() }
    @Test fun sidebarWordsAndExpansionPreserveRawNamesAndOrdering() {
        ExecutionFixture().use { f ->
            f.computerName="原桌面连接";f.projectName="项目";f.threadTitle="最近";connect(f)
            val threads=session.threads;val projects=session.projects;val draft=session.text
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("sidebar-recent-toggle").assertTextEquals("Recent")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
            ui.onNodeWithTag("sidebar-projects-toggle").assertTextEquals("Projects")
            ui.onNodeWithTag("project-empty-project").assertTextEquals("项目")
            ui.onNodeWithTag("recent-thread-fixture-thread").assertContentDescriptionEquals("最近, Read")
            ui.onNodeWithTag("sidebar-client-switch").assertContentDescriptionEquals("Switch client, 原桌面连接, Original desktop connection")
            owned.nodeShot(ui.onNodeWithTag("sidebar"),"english-sidebar-raw-metadata")
            tap("sidebar-recent-toggle")
            ui.onNodeWithTag("recent-thread-fixture-thread").assertDoesNotExist()
            ui.onNodeWithTag("sidebar-recent-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
            main { session.changeLanguage("zh-Hans") }
            ui.onNodeWithTag("sidebar-recent-toggle").assertTextEquals("最近")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已折叠"))
            tap("sidebar-recent-toggle");ui.onNodeWithTag("recent-thread-fixture-thread").assertContentDescriptionEquals("最近，已读")
            assertEquals(threads,session.threads);assertEquals(projects,session.projects);assertEquals(draft,session.text)
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun connectionMethodsAndFieldsKeepLiteralUnsubmittedValues() {
        ExecutionFixture().use { f ->
            connect(f);val original=session.selectedClient;val draft=session.text
            ui.onNodeWithTag("chat-menu").performClick();tap("open-settings");tap("settings-add-client")
            tap("codex-connection-method");tap("codex-method-手动连接")
            ui.onNodeWithTag("client-name").performScrollTo().performTextReplacement("")
            ui.onNodeWithTag("client-address").performScrollTo().performTextReplacement("")
            ui.onNodeWithText("Client name").assertExists()
            ui.onNodeWithText("Connection address").assertExists()
            ui.onNodeWithText("Client key").assertExists()
            val raw="名称 100% %@"
            ui.onNodeWithTag("client-name").performTextReplacement(raw)
            ui.onNodeWithTag("client-address").performTextReplacement("https://example.invalid/a%20b?literal=%40")
            ui.onNodeWithTag("client-token").performTextReplacement("synthetic-only 100% %@")
            assertEquals(raw,ui.onNodeWithTag("client-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            val secret=ui.onNodeWithTag("client-token").fetchSemanticsNode().config
            assertEquals("synthetic-only 100% %@",secret[SemanticsProperties.InputText].text)
            assertFalse(secret[SemanticsProperties.EditableText].text.contains("synthetic-only"))
            ui.onNodeWithTag("client-token").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            owned.dialogShot("literal-connection-name")
            tap("agent-provider-easywork");tap("easywork-connection-method");tap("easywork-method-手动连接")
            ui.onNodeWithText("Computer address").assertExists()
            ui.onNodeWithTag("easywork-manual-address").performTextReplacement("设置 100% %@")
            assertEquals("设置 100% %@",ui.onNodeWithTag("easywork-manual-address").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            owned.dialogShot("literal-provider-address")
            assertEquals(original,session.selectedClient);assertEquals(draft,session.text)
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun englishQuestionStateKeepsRawOptionAndExactOriginalAnswer() {
        ExecutionFixture().use { f ->
            f.state="waiting-for-approval";f.turn="approval-turn"
            f.approvals=JSONArray().put(JSONObject().put("id","approval-id").put("threadId","fixture-thread").put("turnId","approval-turn")
                .put("kind","question").put("title","最近").put("detail","原说明 100% %@").put("decisions",JSONArray())
                .put("questions",JSONArray().put(JSONObject().put("id","q").put("question","项目").put("multiple",false).put("custom",true).put("isSecret",false)
                    .put("options",JSONArray().put(JSONObject().put("label","设置").put("description","原选项 100% %@"))))))
            connect(f);val draft=session.text
            ui.onNodeWithText("Reply needed").assertExists()
            ui.onNodeWithTag("question-title-q").assertTextEquals("项目")
            val option=ui.onNodeWithTag("question-option-q-设置").performScrollTo()
            option.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Not selected")).performClick()
            option.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Selected"))
            ui.onNodeWithTag("question-custom-q").assertContentDescriptionEquals("Enter a reply or additional details")
            owned.nodeShot(ui.onNodeWithTag("approval-approval-id"),"english-question-original-data")
            tap("answer-question")
            ui.waitUntil(10_000) { f.server.requests.count { it.path=="/v1/approvals" }==1 }
            val posts=f.server.requests.filter { it.method=="POST" };assertEquals(1,posts.size)
            val body=JSONObject(String(posts.single().body));assertEquals("fixture-thread",body.getString("threadId"))
            assertEquals("approval-id",body.getString("approvalId"));assertEquals("approval-turn",body.getString("turnId"))
            assertEquals(listOf("设置"),body.getJSONObject("answers").getJSONArray("q").strings())
            assertEquals(draft,session.text)
        }
    }
}
