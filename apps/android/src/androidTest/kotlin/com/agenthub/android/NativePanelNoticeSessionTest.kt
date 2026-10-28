package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real AgentHubApp -> MobileSession -> original phoneReading ledger. Synthetic owner reads only;
 * this suite requires the shared Session/UI integration overlay, not the optional fixture bridge. */
class NativePanelNoticeSessionTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(150)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    // The public narrow port keeps this test compilable before the parent adopts the shared hunks.
    private val actor get()=(session as Any) as NativePanelNoticeSession
    private val ledger get()=checkNotNull(actor.panelNoticeLedger)
    private val target=TargetScope("codex",null,null,"panel-session-host","workspace","runtime")
    private var planMode=false
    private var messageRun="run"
    private var calls=0
    private val planText="Actual original session plan."
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
            .put("hostId",target.hostId).put("name","Synthetic panel Session").put("connectionMode","desktop-runtime")
            .put("readTarget",CodexCommandCodec.target(target)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
            .put("projects",JSONArray()).put("threads",JSONArray(listOf("thread","other").map {
                JSONObject().put("hostId",target.hostId).put("id",it).put("title",it).put("state","idle") }))
            .put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            val question=JSONObject().put("id","question-ui").put("threadId",threadId).put("turnId","run").put("kind","question")
                .put("notificationRequestId","wire-question").put("title","Original Session question").put("detail","")
                .put("decisions",JSONArray()).put("questions",JSONArray().put(JSONObject().put("id","q").put("question","Original prompt")
                    .put("custom",true).put("isSecret",false).put("options",JSONArray())))
            return HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId",threadId)
                .put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray().apply {
                    if(planMode)put(JSONObject().put("id","plan-message").put("role","assistant").put("kind","plan")
                        .put("status","completed").put("runId",messageRun).put("text",planText))
                }).put("runtime",JSONObject().put("state","idle").put("turnId","run").put("plan",JSONArray())
                    .put("error",JSONObject.NULL).put("approvals",JSONArray().apply { if(!planMode)put(question) })
                    .put("receipts",JSONArray()).put("canControl",true).apply {
                        if(planMode)put("proposedPlan",JSONObject().put("id","wire-plan").put("turnId","run").put("text",planText))
                    }).toString())
        }
    }
    private val commands=object: CodexCommands by NativeCodexCommands({ calls++;error("Panel reading must not issue a command") }) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
    }
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun <T> io(block: ()->T): T=runBlocking { withContext(Dispatchers.IO) { block() } }
    @Before fun begin() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        main { owned=OwnedControlAcceptance(ui.activity,"notice-session");session.resetAcceptance(reads,commands);session.changeLanguage("en");session.changeAppearance("light") }
    }
    private fun connect() {
        main { session.connect("Synthetic panel Session","https://fixture.invalid","");session.selectThread(session.threads.first { it.id=="thread" }) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
        main { actor.awaitPanelNoticeReading() }
        ui.waitUntil(10_000) { actor.hasPanelNoticeBinding }
        assertSame("Session exposes its one ledger instance",ledger,actor.panelNoticeLedger)
    }
    /** Fixture event enters the actual Session ledger. Only the ordinary render-frame API refreshes
     * its off-main projection; no manual view report, extra ledger or notification consumer. */
    private fun arrive(vararg events: NativeReadingEvent) {
        io { events.forEach(ledger::receive) }
        main { actor.coverPanelNotices();actor.presentPanelNotices(actor.capturePanelNoticePresentation(),true) }
        ui.waitUntil(10_000) { actor.hasPanelNoticeBinding };ui.waitForIdle()
    }
    private fun frames() {
        val done=java.util.concurrent.CountDownLatch(1)
        ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { done.countDown() } } } }
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));ui.waitForIdle()
    }
    @After fun end() {
        if(::owned.isInitialized)main { session.leaveForeground();owned.verifyDefault();assertTrue(session.operationRecords.isEmpty()) }
        assertEquals(0,calls)
    }
    @Test fun mountedQuestionConsumesExactLateNoticeWithoutRecreatingDraftOrReadingReply() {
        connect();val field=ui.onNodeWithTag("question-custom-q").performScrollTo().assertIsDisplayed()
        val draft="Retained answer 100% %@"
        field.performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(draft))) }
        val position=ThreadReadPosition(1,"unrendered-reply",ThreadReadPosition.contentVersion("unrendered-reply","Other body","completed"))
        val reply=NativeReadingEvent("reply",target,"thread","completed",position)
        val exact=NativeReadingEvent("question",target,"thread","question",runId="run",requestId="wire-question")
        val wrong=exact.copy(id="wrong-request",requestId="question-ui")
        arrive(reply,wrong,exact)
        ui.waitUntil(10_000) { !io { ledger.mayPresent(exact) } }
        field.assertTextEquals(draft);assertTrue(io { ledger.mayPresent(wrong) });assertTrue(io { ledger.mayPresent(reply) })
        assertTrue(ledger.pendingReads().isEmpty())
    }
    @Test fun actualMessagePlanRequiresExpandedTextAndExactOriginalRun() {
        planMode=true;messageRun="other-run";connect()
        val event=NativeReadingEvent("plan",target,"thread","plan",runId="run",requestId="wire-plan")
        arrive(event);frames();assertTrue(io { ledger.mayPresent(event) })
        ui.onNodeWithTag("proposed-plan-toggle-plan-message").performScrollTo().performClick();frames()
        assertTrue("Different run cannot witness this original plan",io { ledger.mayPresent(event) })
        ui.onNodeWithTag("proposed-plan-toggle-plan-message").performScrollTo().performClick()
        main { messageRun="run";session.loadHistory()?.join() };ui.waitForIdle()
        val toggle=ui.onNodeWithTag("proposed-plan-toggle-plan-message").performScrollTo()
        frames();assertTrue(io { ledger.mayPresent(event) });toggle.performClick()
        ui.waitUntil(10_000) { !io { ledger.mayPresent(event) } }
        assertEquals(planText,session.runtime!!.proposedPlan!!.text)
    }
    @Test fun capturedPresentationCannotRearmAfterCoverNavigationOrCredentialReplacement() {
        connect();val old=main { checkNotNull(actor.capturePanelNoticePresentation()) }
        main { actor.coverPanelNotices();actor.presentPanelNotices(old,true);assertFalse(actor.hasPanelNoticeBinding) }
        main { session.selectThread(session.threads.first { it.id=="other" });actor.presentPanelNotices(old,true);assertFalse(actor.hasPanelNoticeBinding) }
        val replacementToken=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        assertTrue(replacementToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        main {
            val replaced=session.connect("Synthetic panel Session","https://fixture.invalid",replacementToken)
            assertEquals(target.hostId,replaced.id)
            assertEquals(replacementToken,withContext(Dispatchers.IO) { owned.storage.token(replaced.id) })
            actor.presentPanelNotices(old,true);assertFalse(actor.hasPanelNoticeBinding)
        }
    }
}
