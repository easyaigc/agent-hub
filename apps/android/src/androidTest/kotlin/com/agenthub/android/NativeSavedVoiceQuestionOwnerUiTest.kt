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
class NativeSavedVoiceQuestionOwnerUiTest {
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
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) { this@NativeSavedVoiceQuestionOwnerUiTest.answers++;error("No implicit question submission") }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"question-voice");microphone=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands);session.configureVoice(ui.activity)
            session.voiceSettings.select(VoiceInputController.Provider.SYSTEM)
            session.questionVoice!!.systemFactory={object: NativeSystemVoice {
                override fun start(listener: NativeSystemVoice.Listener) { starts++;this@NativeSavedVoiceQuestionOwnerUiTest.listener=listener };override fun finish() {};override fun cancel() {}
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
    @Test fun savedQuestionFilePublishesAfterDurabilityAndRecreatedQuestionReadsItsActualOwner() {
        ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("question draft")
        lateinit var original: PendingVoiceRecording
        lateinit var beforeRow: String
        lateinit var bytes: ByteArray
        main {
            val approval=session.runtime!!.approvals.single();val question=approval.questions.single {it.id=="q"};val destination=session.approvalActionDestination()
            val client=session.selectedClient;val scope=AISharingScope(client.scope!!,"codex-dictation");val key=session.questionVoiceIdentity(approval,question,destination)
            val draftId="voice-v1:"+voiceSHA((scope.key+"\u0000"+key).toByteArray(Charsets.UTF_8));val store=VoiceRecordingStore(ui.activity,owned.storage)
            val pending=store.begin(scope,client.codexDirect?.fingerprint,key,draftId,approval.threadId,"question draft")
            store.file(pending.id).writeBytes(WavePCM.header(3200)+ByteArray(3200));original=store.seal(pending);store.mark(original,true,true)
            beforeRow=owned.storage.read("voice-recording:${original.id}")!!;bytes=store.bytes(original)
            session.questionVoice!!.savedFileFactory={object: NativeSavedVoiceSpeech {
                override suspend fun probe(locale: String)=SavedVoiceProbe(SavedVoiceAvailability.INSTALLED)
                override suspend fun install(locale: String,checkpoint: suspend ()->Unit){error("No model download in fixture")}
                override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText {
                    assertArrayEquals(bytes,wav);checkpoint();return SavedVoiceText("file transcript","vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
                }
            }}
        }
        ui.onNodeWithTag("question-voice-restore-q").performScrollTo().performClick()
        ui.waitUntil(10_000){session.questionVoice!!.phase==VoiceInputController.Phase.FAILED}
        main {session.questionVoice!!.chooseSavedFileLocale("en-US");session.questionVoice!!.inspectSavedFile()}
        ui.waitUntil(10_000){!session.questionVoice!!.savedFileBusy}
        ui.onNodeWithTag("saved-file-transcribe").performScrollTo().performClick()
        ui.waitUntil(10_000){!session.questionVoice!!.active&&!session.questionVoice!!.savedFileBusy}
        ui.onNodeWithTag("question-custom-q").performScrollTo().assertTextEquals("question draft\nfile transcript")
        main {
            owned.storage.awaitQueuedWrites();val receipt=NativeVoiceLocalResults.read(owned.storage).single();assertTrue(receipt.applied);assertNotNull(receipt.destination)
            val destination=receipt.destination!!;assertEquals("question draft\nfile transcript",owned.storage.reopen(ui.activity).metadata()!!.getJSONObject("drafts").getString(destination.ownerKey))
            assertEquals(beforeRow,owned.storage.read("voice-recording:${original.id}"));assertArrayEquals(bytes,VoiceRecordingStore(ui.activity,owned.storage).bytes(original))
        }
        titleVersion++;main{session.loadHistory()?.join()};ui.waitForIdle()
        ui.onNodeWithTag("question-custom-q").performScrollTo().assertTextEquals("question draft\nfile transcript")
        assertEquals("composer draft",session.text);assertEquals(0,starts);assertEquals(0,answers);assertEquals(0,voiceRequests)
        bytes.fill(0)
    }
}
