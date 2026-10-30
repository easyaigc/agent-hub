package com.agenthub.android

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.util.UUID

/** Source-only contract selectors for parent fresh-candidate native execution. Empty owned Activity,
 * synthetic readiness/permission denial only; no MainActivity, discovery, mic, ASR or Host transport. */
class NativeVoiceInteractionUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=2) val name=TestName()
    private lateinit var folder: File
    private var permissionBefore=0
    private val controllers=mutableListOf<VoiceInputController>()
    private val fixtures=mutableListOf<NativeSavedVoicePanelFixture>()
    private var systemFactoryCalls=0;private var hostCalls=0;private var applyCalls=0
    private var completed=false
    private val proof=JSONObject()
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main){block()}}
    @Before fun claim() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        require(instrumentation.targetContext.packageName==NativeRealFileAsrContract.PACKAGE)
        require(instrumentation.context.packageName==NativeRealFileAsrContract.PACKAGE+".test")
        require(android.os.Build.VERSION.SDK_INT in setOf(26,37))
        val args=InstrumentationRegistry.getArguments()
        require(args.keySet().filter {it.startsWith("voiceInteraction")}.toSet()==setOf("voiceInteractionLease"))
        val lease=checkNotNull(args.getString("voiceInteractionLease"));require(UUID.fromString(lease).toString()==lease)
        val root=NativeRealFileAsrContract.privateDirectory(instrumentation.targetContext.filesDir,"acceptance")
        val group=NativeRealFileAsrContract.privateDirectory(root,"P7-voice-interaction")
        val owned=NativeRealFileAsrContract.privateDirectory(group,lease)
        folder=File(owned,name.methodName);require(!folder.exists());folder=NativeRealFileAsrContract.privateDirectory(owned,name.methodName)
        permissionBefore=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        proof.put("version",1).put("lease",lease).put("method",name.methodName).put("nativeExecuted",true)
            .put("appSha256",NativeRealFileAsrContract.sha(File(ui.activity.applicationInfo.sourceDir)))
            .put("runnerSha256",NativeRealFileAsrContract.sha(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("fontScale",ui.activity.resources.configuration.fontScale)
            .put("buildFingerprint",android.os.Build.FINGERPRINT).put("realMicAsrHostProof",false)
        NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject(proof.toString()))
    }
    @After fun cleanup() {
        if(!::folder.isInitialized)return
        val failures=mutableListOf<Throwable>();val originals=JSONArray()
        main {
            controllers.forEach {try {it.cancel();it.quiesceForMigration();it.retireForMigration()}catch(t: Throwable){failures.add(t)}}
            fixtures.forEach {try {originals.put(it.cleanup())}catch(t: Throwable){failures.add(t)}}
        }
        try {assertEquals(permissionBefore,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO));assertEquals(0,systemFactoryCalls);assertEquals(0,hostCalls);assertEquals(0,applyCalls)}catch(t: Throwable){failures.add(t)}
        proof.put("originals",originals).put("checksCompleted",completed).put("cleanupFailures",JSONArray(failures.map {it.javaClass.name}))
            .put("systemFactoryCalls",systemFactoryCalls).put("hostTransportCalls",hostCalls).put("draftApplyCalls",applyCalls)
            .put("outcome",if(completed&&failures.isEmpty())"COMPONENT_CONTRACT_PASSED_REAL_DEV_SEPARATE"else"FAILED_OR_UNKNOWN")
        NativeRealFileAsrContract.atomic(File(folder,"result.json"),proof)
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
    }
    private fun input(owner: ()->Any,enabled: ()->Boolean,start: ()->Boolean) {
        main {ui.activity.setContent {
            var value by remember {mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("Original draft",androidx.compose.ui.text.TextRange(14)))}
            Box(Modifier.fillMaxWidth().padding(16.dp).height(160.dp).testTag("interaction-entry")) {
                NativeComposerVoiceInput(owner(),"navigation",enabled(),{value.selection},"Touch and hold to start voice input","Voice input",start) { input ->
                    BasicTextField(value,{value=it},Modifier.fillMaxSize().testTag("interaction-text").then(input))
                }
            }
        }};ui.waitForIdle()
    }
    @Test fun shortTapEditsAndCompletedHoldStartsOnceWithoutStoppingOnRelease() {
        var starts=0;var recording=false
        input({"editor"},{true}){starts++;recording=true;true}
        ui.onNodeWithTag("interaction-text").performClick().performTextInput(" typed")
        assertEquals(0,starts);ui.onNodeWithTag("interaction-text").assertTextContains("typed",substring=true)
        ui.onNodeWithTag("interaction-entry").performTouchInput {down(center);advanceEventTime(450);up()}
        assertEquals(0,starts)
        ui.onNodeWithTag("interaction-entry").performTouchInput {longClick(center,durationMillis=650)}
        ui.runOnIdle {assertEquals(1,starts);assertTrue(recording)}
        completed=true;proof.put("shortTapEdited",true).put("under500msStarts",0).put("completedHoldStarts",starts).put("releaseKeptRecording",recording)
    }
    @Test fun movementDisabledAndChangedOwnerCancelHoldAndNamedActionsRemainAvailable() {
        var owner by mutableStateOf<Any>("original");var enabled by mutableStateOf(true);var starts=0
        input({owner},{enabled}){starts++;true}
        ui.onNodeWithTag("interaction-entry").performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(2000f,0f));advanceEventTime(650);up()}
        assertEquals(0,starts)
        ui.onNodeWithTag("interaction-entry").performTouchInput {down(center)}
        ui.runOnIdle {owner="replacement"}
        ui.onNodeWithTag("interaction-entry").performTouchInput {advanceEventTime(650);up()}
        assertEquals(0,starts)
        ui.runOnIdle {enabled=false}
        ui.onNodeWithTag("interaction-entry").performTouchInput {longClick(center,durationMillis=650)}
        assertEquals(0,starts)
        ui.runOnIdle {enabled=true}
        val node=ui.onNodeWithTag("interaction-text").fetchSemanticsNode()
        assertEquals("Touch and hold to start voice input",node.config[SemanticsActions.OnLongClick].label)
        val actions=node.config[SemanticsActions.CustomActions];assertEquals(listOf("Voice input"),actions.map {it.label})
        ui.runOnIdle {assertTrue(actions.single().action());assertEquals(1,starts)}
        completed=true;proof.put("movedDisabledOrStaleStarts",0).put("namedVoiceActionStarts",starts)
    }
    @Test fun deniedPreparationStaysInStripAcrossSuspendAndRerecordKeepsOriginalOwner() {
        val context=object: ContextWrapper(ui.activity) {
            override fun checkSelfPermission(permission: String)=if(permission==Manifest.permission.RECORD_AUDIO)PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val storage=NativeSessionStorage(context,"voice-interaction-"+UUID.randomUUID(),"voice-interaction-"+UUID.randomUUID())
        val controller=VoiceInputController(context,VoiceRecordingStore(context,storage)){hostCalls++;error("No Host commands")}
        controllers+=controller
        controller.systemFactory={systemFactoryCalls++;error("Permission denial must prevent microphone factory")}
        val scope=TargetScope("codex",null,null,"synthetic-host","synthetic-workspace","synthetic-runtime")
        val original=VoiceDraft(MobileClient(scope.hostId,"Synthetic",scope),AISharingScope(scope,"codex-dictation"),"original-key","original-revision","original-id",null,"Original draft")
        main {controller.start(original,{true},{_,_->applyCalls++;true},restore=false,provider=VoiceInputController.Provider.SYSTEM);yield()}
        val request=checkNotNull(controller.permissionRequest)
        main {controller.permissionResult(request,false);NativeSavedVoicePanelFixture.until {controller.phase==VoiceInputController.Phase.FAILED}}
        assertEquals("录音未完成",controller.failureTitleKey);assertFalse(controller.canRetry);assertTrue(controller.canRecordAgain)
        val error=controller.error
        main {controller.suspendRecording();yield();controller.start(original.copy(key="foreign",text="Foreign draft"),{true},{_,_->applyCalls++;true},provider=VoiceInputController.Provider.SYSTEM)}
        assertEquals(VoiceInputController.Phase.FAILED,controller.phase);assertEquals(error,controller.error);assertEquals(original.text,controller.capturedText)
        main {ui.activity.setContent {NativeTheme(false,"en"){VoiceInputStrip(controller,original.text)}}};ui.waitForIdle()
        ui.onNodeWithTag("voice-failure-title").assertIsDisplayed().assertTextContains("Recording incomplete",substring=true)
        ui.onNodeWithTag("voice-status").assertIsDisplayed().assertTextContains(NativeL10n(ui.activity,"en").tr(checkNotNull(controller.statusCopyKey)),substring=true)
        ui.onNodeWithTag("stop-dictation").assertTextContains(NativeL10n(ui.activity,"en").tr("重新录音"),substring=true).performClick()
        ui.waitUntil(5000){controller.permissionRequest!=null}
        assertEquals(original.text,controller.capturedText);assertNotEquals(request,controller.permissionRequest)
        main {controller.permissionResult(checkNotNull(controller.permissionRequest),false);NativeSavedVoicePanelFixture.until {controller.phase==VoiceInputController.Phase.FAILED}}
        completed=true;proof.put("failureErrorPreservedAcrossSuspend",controller.error==error).put("originalDraftPreserved",controller.capturedText==original.text).put("replacementStartRejected",true).put("rerecordRequestedNewPermission",true)
    }
    @Test fun savedOriginalHasVisibleTranscriptionFailureAndNativeCircleRetryWithoutReopeningCapture() {
        val f=main {NativeSavedVoicePanelFixture.create(ui.activity,folder)};fixtures+=f
        assertTrue(f.controller.hasRetryableRecording);assertEquals("转写失败",f.controller.failureTitleKey)
        assertEquals("已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。",f.controller.error)
        assertEquals(f.controller.error,f.controller.statusCopyKey)
        val error=f.controller.error
        main {f.controller.suspendRecording();yield()};assertEquals(error,f.controller.error);assertEquals(VoiceInputController.Phase.FAILED,f.controller.phase)
        for(locale in listOf("zh-Hans","en"))for(dark in listOf(false,true)) {
            main {ui.activity.setContent {NativeTheme(dark,locale){VoiceInputStrip(f.controller,f.draft.text)}}};ui.waitForIdle()
            val text=NativeL10n(ui.activity,locale)
            ui.onNodeWithTag("voice-failure-title").assertIsDisplayed().assertTextContains(text.tr("转写失败"),substring=true)
            ui.onNodeWithTag("stop-dictation").performScrollTo().assertIsDisplayed().assertTextContains(text.tr("重试转写"),substring=true)
            ui.onNodeWithTag("voice-record-again").performScrollTo().assertIsNotEnabled()
            main {f.preserved()}
        }
        completed=true;proof.put("savedFailureLanguages",2).put("savedFailureThemes",2).put("sameStripForComposerAndQuestion",true).put("originalPreservedBeforeAndAfter",true)
    }
    @Test fun unresolvedDiscardKeepsModelActionsGatedUntilExplicitReceiptRecovery() {
        val f=main {NativeSavedVoicePanelFixture.create(ui.activity,folder)};fixtures+=f
        main {f.controller.inspectSavedFile();NativeSavedVoicePanelFixture.until {!f.controller.savedFileBusy}}
        assertEquals(SavedVoiceAvailability.MISSING,f.controller.savedFileProbe?.availability)
        var attempts=0
        f.controller.discardPort=NativeVoiceDiscardPort { request->
            attempts++;val receipt=f.store.discardLocal(request){}
            if(attempts==1)throw java.io.IOException("Synthetic local receipt lost after catalog commit")
            receipt
        }
        main {f.controller.discard();NativeSavedVoicePanelFixture.until {f.controller.discardFailed&&!f.controller.discarding}}
        assertEquals(1,attempts);assertFalse(f.controller.canRetry);assertFalse(f.controller.canRecordAgain)
        assertFalse(f.controller.canRerecordWithSystem);assertFalse(f.controller.canInspectSavedFile);assertFalse(f.controller.canTranscribeSavedFile)
        val marker=NativeVoiceDiscardCatalog.read(f.storage).getValue(f.original.id)
        assertEquals(NativeVoiceDiscardRequest(f.original).fingerprint,marker.fingerprint)
        val originalLocale=f.controller.savedFileLocale
        main {
            f.controller.chooseSavedFileLocale(if(originalLocale=="en-US")"zh-CN"else"en-US")
            f.controller.inspectSavedFile();f.controller.downloadSavedFileModel();f.controller.transcribeSavedFile();yield()
        }
        assertEquals(originalLocale,f.controller.savedFileLocale);assertEquals(1,f.engine.probes)
        assertEquals(0,f.engine.installs);assertEquals(0,f.engine.decodes);assertEquals(1,attempts)
        main {f.preserved();ui.activity.setContent {NativeTheme(false,"en"){Box(Modifier.width(360.dp).height(520.dp)){VoiceInputStrip(f.controller,f.draft.text)}}}}
        ui.waitForIdle()
        for(tag in listOf("saved-file-asr","saved-file-probe","saved-file-download","saved-file-transcribe"))
            ui.onNodeWithTag(tag).assertDoesNotExist()
        ui.onNodeWithTag("stop-dictation").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .assertTextEquals(NativeL10n(ui.activity,"en").tr("重试丢弃")).performClick()
        main {NativeSavedVoicePanelFixture.until {!f.controller.active};f.preserved()}
        assertEquals(2,attempts);assertEquals(marker,NativeVoiceDiscardCatalog.read(f.storage).getValue(f.original.id))
        assertEquals(1,f.engine.probes);assertEquals(0,f.engine.installs);assertEquals(0,f.engine.decodes)
        completed=true;proof.put("discardCommitLostReceiptSynthetic",true).put("explicitReceiptRecoveryAttempts",attempts)
            .put("discardMarkerUnchangedOnExplicitRetry",true).put("modelActionsWhileDiscardUnknown",0)
    }

}
