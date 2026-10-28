package com.agenthub.android

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Actual question fields and the Session route, with an injected recognizer and synthetic runtime only. */
class NativeQuestionVoiceUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private var microphone=0
    private var titleVersion=0
    private var answers=0
    private var voiceRequests=0
    private var starts=0
    private lateinit var listener: NativeSystemVoice.Listener
    private val target=TargetScope("codex",null,null,"question-host","workspace","runtime")
    private fun approval()=JSONObject().put("id","approval").put("threadId","thread").put("turnId","turn").put("kind","question")
        .put("title","Question $titleVersion").put("detail","").put("decisions",JSONArray()).put("questions",JSONArray(listOf(false,true).map { secret ->
            JSONObject().put("id",if(secret)"secret" else "q").put("question",if(secret)"Secret" else "Question").put("custom",true).put("isSecret",secret).put("options",JSONArray()) }))
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
            .put("hostId","question-host").put("name","Fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
            .put("projects",JSONArray()).put("threads",JSONArray(listOf("thread","other").map { id ->JSONObject().put("hostId","question-host").put("id",id).put("title",id).put("state","idle") }))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
            .put("hostId","question-host").put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
            .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                .put("approvals",JSONArray().apply { if(threadId=="thread")put(approval()) }).put("receipts",JSONArray()).put("canControl",true)).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({error("No unrelated command")}) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness { voiceRequests++;error("System speech must not call Host voice") }
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) { this@NativeQuestionVoiceUiTest.answers++;error("No implicit question submission") }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"question-voice");microphone=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands);session.configureVoice(ui.activity)
            session.voiceSettings.select(VoiceInputController.Provider.SYSTEM)
            session.questionVoice!!.systemFactory={object: NativeSystemVoice {
                override fun start(listener: NativeSystemVoice.Listener) { starts++;this@NativeQuestionVoiceUiTest.listener=listener };override fun finish() {};override fun cancel() {}
            }}
            // No VoicePermission composable: the fake callback gate does not request or grant an OS permission.
            ui.activity.setContent { ProductTextProvider("zh-Hans") { ExecutionPanels(session,autoPresentQuestions=false) } }
        }
        main { session.connect("Fixture","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first { it.id=="thread" }) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime?.approvals?.isNotEmpty()==true }
        ui.runOnIdle { session.text="composer draft" }
    }
    @After fun finish() { if(::session.isInitialized)ui.runOnIdle { session.questionVoice?.cancel();session.leaveForeground() };if(::owned.isInitialized)ui.runOnIdle { owned.verifyDefault();assertEquals(microphone,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)) } }
    private fun start() {
        ui.onNodeWithTag("question-custom-secret").performScrollTo().performTextInput("synthetic secret answer")
        ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("question draft")
        ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("question-voice-q").performScrollTo().performClick()
        main { session.questionVoice!!.permissionRequest?.let { session.questionVoice!!.permissionResult(it,true) };yield();listener.ready() }
    }
    @Test fun nonSecretQuestionUsesSelectedProviderAndOnlyFillsItsOwnDraft() {
        ui.onNodeWithTag("question-voice-secret").assertDoesNotExist()
        ui.runOnIdle { val approval=session.runtime!!.approvals.single();val secret=approval.questions.first { it.secret };val destination=session.approvalActionDestination()
            assertFalse(session.canBeginQuestionVoice(approval,secret,destination));session.beginQuestionVoice(approval,secret,destination,{error("Do not read a secret for voice")},{error("Do not change a secret through voice")});assertEquals(0,starts) }
        start()
        ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
        main { session.beginVoice();assertFalse(session.voice!!.active);session.voiceSettings.select(VoiceInputController.Provider.DESKTOP)
            assertEquals(VoiceInputController.Provider.SYSTEM,session.questionVoice!!.provider);listener.result("question speech") }
        ui.onNodeWithTag("question-custom-q").performScrollTo().assertTextEquals("question draft\nquestion speech")
        assertEquals("composer draft",session.text);assertEquals(0,answers);assertEquals(0,voiceRequests);assertEquals(1,starts)
        assertFalse(session.questionVoice!!.active);ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled()
    }
    @Test fun backgroundRetainsPartialButNeverAppliesOrAnswersFromALateCallback() {
        start();main { listener.partial("partial");session.leaveForeground();listener.result("late result") }
        ui.onNodeWithTag("question-custom-q").performScrollTo().assertTextEquals("question draft")
        assertEquals("partial",session.questionVoice!!.transcript);assertFalse(session.questionVoice!!.canFillRecognizedText)
        assertEquals("composer draft",session.text);assertEquals(0,answers);assertEquals(0,voiceRequests)
    }
    @Test fun replacedQuestionAndNavigationDisposeTheOriginalOwnerWithoutChangingAnotherDraft() {
        start();titleVersion++
        main { session.loadHistory()?.join() };ui.waitForIdle()
        main { listener.result("old approval result") }
        ui.onNodeWithTag("question-custom-q").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        assertFalse(session.questionVoice!!.active);assertEquals(0,answers)
        ui.runOnIdle { session.selectThread(session.threads.first { it.id=="other" });session.text="other composer draft" }
        ui.waitUntil(15_000) { !session.historyLoading }
        main { listener.result("old thread result") }
        assertEquals("other composer draft",session.text);assertEquals(0,answers);assertEquals(0,voiceRequests)
    }
}
