package com.agenthub.android

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Collections

/** Owned Activity, fake capability/persistence ports only. No recognizer, microphone, Host or model. */
class NativeVoiceSettingsUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private var page by mutableStateOf("settings")
    private var microphone=0
    private val loadGate=CompletableDeferred<VoiceInputController.Provider?>()
    private val saveGate=CompletableDeferred<Unit>()
    private val probeGate=CompletableDeferred<Unit>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"voice-settings");microphone=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            session=MobileSession(owned.storage);session.resetAcceptance()
            session.voiceSettings.inspector={ NativeVoiceCapability(NativeVoiceCapability.Route.SYSTEM_SERVICE,NativeVoiceCapability.Language.INSTALLED,"en-US") }
            ui.activity.setContent { ProductTextProvider("zh-Hans") { NativeSheetHost(page,session,null,{}, {page=it},{}) } }
        }
    }
    @After fun finish() {
        loadGate.complete(null);saveGate.complete(Unit);probeGate.complete(Unit)
        if(::session.isInitialized)ui.runOnIdle { session.leaveForeground() }
        if(::owned.isInitialized)ui.runOnIdle { owned.verifyDefault();assertEquals(microphone,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)) }
    }
    @Test fun actualSettingsNavigationChangesNextDefaultAndShowsUnconfirmedPersistenceAndNetworkBoundary() {
        ui.onNodeWithTag("voice-transcription-picker").performScrollTo().performClick()
        ui.onNodeWithTag("voice-provider-system").performClick().assertIsSelected()
        ui.waitUntil(10_000) { !session.voiceSettings.checking && session.voiceSettings.capabilities!=null }
        assertEquals(VoiceInputController.Provider.SYSTEM,session.voiceSettings.defaultProvider)
        ui.onNodeWithTag("voice-preference-persistence").assertTextEquals("此选择仅在本次打开期间生效。")
        ui.onNodeWithTag("voice-service-capability").performScrollTo().assertTextEquals("已发现系统语音服务，可能需要联网")
        ui.onNodeWithTag("voice-saved-recording-capability").performScrollTo().assertIsDisplayed()
        assertTrue(session.voiceSettings.capabilities!!.mayUseNetwork);assertFalse(session.voiceSettings.capabilities!!.canTranscribeSavedRecording)
    }
    @Test fun latePreferenceLoadCannotOverrideSelectionAndWritesCoalesceInOrder() {
        val saves=Collections.synchronizedList(mutableListOf<VoiceInputController.Provider>())
        val backend=object: NativeVoicePreferenceStore {
            override suspend fun load()=loadGate.await()
            override suspend fun save(value: VoiceInputController.Provider) { saves+=value;if(saves.size==1)saveGate.await() }
        }
        ui.runOnIdle { session.voiceSettings.bind(backend);session.voiceSettings.select(VoiceInputController.Provider.DESKTOP) }
        loadGate.complete(VoiceInputController.Provider.SYSTEM)
        ui.waitUntil(10_000) { saves.size==1 }
        assertEquals(VoiceInputController.Provider.DESKTOP,session.voiceSettings.defaultProvider)
        ui.runOnIdle { session.voiceSettings.select(VoiceInputController.Provider.SYSTEM);session.voiceSettings.select(VoiceInputController.Provider.DESKTOP);session.voiceSettings.select(VoiceInputController.Provider.SYSTEM) }
        saveGate.complete(Unit)
        ui.waitUntil(10_000) { session.voiceSettings.persisted }
        assertEquals(listOf(VoiceInputController.Provider.DESKTOP,VoiceInputController.Provider.SYSTEM),saves.toList())
        assertEquals(VoiceInputController.Provider.SYSTEM,session.voiceSettings.defaultProvider)
    }
    @Test fun lateCapabilityAfterLeavingCannotPublishAndConcurrentQueriesCoalesce() {
        val entered=CompletableDeferred<Unit>();var job: Job?=null
        main {
            session.voiceSettings.inspector={ entered.complete(Unit);withContext(NonCancellable) { probeGate.await() };NativeVoiceCapability(NativeVoiceCapability.Route.ON_DEVICE,NativeVoiceCapability.Language.INSTALLED,"en-US") }
            job=session.voiceSettings.refreshCapabilities();assertSame(job,session.voiceSettings.refreshCapabilities())
        }
        ui.waitUntil(10_000) { entered.isCompleted }
        main { session.voiceSettings.leave();probeGate.complete(Unit);job!!.join() }
        assertNull(session.voiceSettings.capabilities);assertFalse(session.voiceSettings.checking)
    }
    @Test fun selectedDefaultRoutesTheRealSessionAndChangingItCannotRetargetAnActiveCapture() {
        val target=TargetScope("codex",null,null,"voice-host","workspace","runtime");var voiceRequests=0
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId","voice-host").put("name","Fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
                .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("hostId","voice-host").put("id","thread").put("title","Thread").put("state","idle")))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
                .put("hostId","voice-host").put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                    .put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({error("No unrelated command")}) {
            override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
            override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness { voiceRequests++;error("System route must not call Host voice") }
        }
        var listener: NativeSystemVoice.Listener?=null
        val engine=object: NativeSystemVoice {
            override fun start(value: NativeSystemVoice.Listener) { listener=value }
            override fun finish() {}
            override fun cancel() {}
        }
        main { session.resetAcceptance(reader,commands);session.configureVoice(ui.activity);session.voice!!.systemFactory={engine}
            session.connect("Fixture","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single()) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
        main {
            session.text="原草稿";session.voiceSettings.select(VoiceInputController.Provider.SYSTEM);assertTrue(session.canUseVoice);session.beginVoice()
            session.voice!!.permissionRequest?.let { session.voice!!.permissionResult(it,true) };yield()
            assertNull(session.aiSharingRequest);listener!!.ready();session.voiceSettings.select(VoiceInputController.Provider.DESKTOP)
            assertEquals(VoiceInputController.Provider.SYSTEM,session.voice!!.provider)
            listener!!.result("系统结果");assertEquals("原草稿\n系统结果",session.text);assertEquals(0,voiceRequests)
            session.beginVoice();assertNotNull(session.aiSharingRequest);assertFalse(session.voice!!.active)
        }
    }
    @Test fun failedPartialTextIsFilledByTheExplicitNativeActionOnly() {
        var listener: NativeSystemVoice.Listener?=null;val applied=mutableListOf<String>()
        val target=TargetScope("codex",null,null,"voice-host","workspace","runtime")
        val draft=VoiceDraft(MobileClient("voice-host","Fixture",target),AISharingScope(target,"codex-dictation"),"key","version","voice","thread","原草稿")
        ui.runOnIdle {
            session.configureVoice(ui.activity);session.voice!!.systemFactory={ object: NativeSystemVoice {
                override fun start(value: NativeSystemVoice.Listener) { listener=value };override fun finish() {};override fun cancel() {}
            } }
            ui.activity.setContent { ProductTextProvider("zh-Hans") { VoiceInputStrip(session.voice!!,"原草稿") } }
        }
        main {
            session.voice!!.start(draft,{true},{_,text->applied.add(text);true},provider=VoiceInputController.Provider.SYSTEM)
            session.voice!!.permissionRequest?.let { session.voice!!.permissionResult(it,true) };yield()
            listener!!.ready();listener!!.partial("保留部分");listener!!.failed(8)
        }
        assertTrue(applied.isEmpty());ui.onNodeWithTag("voice-fill-recognized-text").performClick()
        assertEquals(listOf("保留部分"),applied);assertEquals(VoiceInputController.Phase.IDLE,session.voice!!.phase)
    }
}
