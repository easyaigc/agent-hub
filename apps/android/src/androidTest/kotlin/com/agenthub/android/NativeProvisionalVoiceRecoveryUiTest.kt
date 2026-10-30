package com.agenthub.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
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
import org.junit.runners.model.MultipleFailureException
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Fresh owned provisional rows/raw bytes only. No SharedFixture.begin, sealing, model, Host or mic.
 * This class is native/UI acceptance preparation, never a host or compile-only native pass. */
class NativeProvisionalVoiceRecoveryUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=2) val name=TestName()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var folder: File
    private val fixtures=mutableListOf<Fixture>()
    private val rows=JSONArray()
    private var passed=false
    private var permissionBefore=0
    private fun <T> main(block: suspend ()->T)=runBlocking {withContext(Dispatchers.Main){block()}}
    private suspend fun until(ready: ()->Boolean)=withTimeout(10_000){while(!ready())delay(5)}
    private inner class Fixture(val context: Context,val root: File,val bytes: ByteArray,val shared: Boolean) {
        // Override only this fixture's filesystem root; keep the real application context identity.
        val local=object: ContextWrapper(context) {
            override fun getNoBackupFilesDir()=File(root,"no-backup").apply {check(mkdirs()||isDirectory)}
        }
        val alias="p7-provisional-copy-"+UUID.randomUUID()
        val storage=NativeSessionStorage(local,"original",alias)
        val store=VoiceRecordingStore(local,storage)
        var hostCalls=0;var micCalls=0;var asrCalls=0;var applyCalls=0
        val controller=VoiceInputController(local,store){hostCalls++;error("No Host factory for a provisional original")}
        lateinit var original: PendingVoiceRecording
        lateinit var draft: VoiceDraft
        lateinit var originalRow: String
        lateinit var originalIndex: String
        lateinit var canonical: String
        suspend fun start() {
            val target=TargetScope("codex",null,null,"synthetic-provisional-host","synthetic-workspace","synthetic-runtime")
            draft=VoiceDraft(MobileClient(target.hostId,"Synthetic",target),AISharingScope(target,"codex-dictation"),"provisional-key","revision","draft",null,"Unfinished draft / 未完成原稿")
            withContext(Dispatchers.IO) {
                original=store.begin(draft.scope,draft.certificate,draft.key,draft.draftId,draft.threadId,draft.text)
                store.file(original.id).writeBytes(bytes) // Deliberately not sealed, even when these are valid PCM bytes.
                originalRow=checkNotNull(storage.read("voice-recording:${original.id}"))
                originalIndex=checkNotNull(storage.read("voice-index-v1"));canonical=easyCanonical(original.json())
            }
            controller.systemFactory={micCalls++;error("No microphone factory")}
            controller.savedFileFactory={asrCalls++;error("No saved-file ASR factory")}
            controller.start(draft,{shared},{_,_->applyCalls++;false},restoreOnly=true,localAllowed={true})
            until {controller.phase==VoiceInputController.Phase.FAILED}
        }
        suspend fun preserved(): JSONObject=withContext(Dispatchers.IO) {
            val current=store.current(original)
            assertEquals(canonical,easyCanonical(current.json()));assertNull(current.manifest)
            assertFalse(current.prepareAttempted);assertFalse(current.audioAttempted)
            assertEquals(originalRow,storage.read("voice-recording:${original.id}"))
            assertEquals(originalIndex,storage.read("voice-index-v1"))
            assertArrayEquals(bytes,store.file(original.id).readBytes())
            assertEquals(0,hostCalls);assertEquals(0,micCalls);assertEquals(0,asrCalls);assertEquals(0,applyCalls)
            JSONObject().put("id",original.id).put("canonicalSha256",voiceSHA(canonical.toByteArray()))
                .put("rowSha256",voiceSHA(originalRow.toByteArray())).put("rawSha256",voiceSHA(bytes))
                .put("rawBytes",bytes.size).put("exactRowIndexAndFullRawCompared",true).put("manifest",JSONObject.NULL)
                .put("hostMicAsrApplyCalls",0)
        }
        suspend fun stop(): JSONObject {
            // End waiting retains a restored provisional row/file. Never call cancel's remove path.
            if(controller.canEndWaiting)controller.endWaiting()
            until {controller.phase==VoiceInputController.Phase.IDLE}
            yield();controller.quiesceForMigration();controller.retireForMigration()
            val proof=preserved()
            withContext(Dispatchers.IO) {
                storage.awaitQueuedWrites();storage.retireWriter()
                val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(alias);check(!keys.containsAlias(alias))
                check(root.deleteRecursively());check(!root.exists())
            }
            bytes.fill(0);return proof.put("fixtureReleasedAfterProof",true)
        }
    }
    @Before fun claim() {
        require(instrumentation.targetContext.packageName==NativeRealFileAsrContract.PACKAGE)
        require(instrumentation.context.packageName==NativeRealFileAsrContract.PACKAGE+".test")
        val args=InstrumentationRegistry.getArguments()
        require(args.keySet().filter {it.startsWith("provisionalVoice")}.toSet()==setOf("provisionalVoiceRun"))
        val run=checkNotNull(args.getString("provisionalVoiceRun"));require(UUID.fromString(run).toString()==run)
        val root=NativeRealFileAsrContract.privateDirectory(instrumentation.targetContext.filesDir,"acceptance")
        val group=NativeRealFileAsrContract.privateDirectory(root,"P7-provisional-voice-copy")
        val owned=NativeRealFileAsrContract.privateDirectory(group,run)
        folder=File(owned,name.methodName);require(!folder.exists()){ "Preserve original evidence; no method replay" }
        folder=NativeRealFileAsrContract.privateDirectory(owned,name.methodName)
        permissionBefore=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject().put("run",run).put("method",name.methodName)
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("buildFingerprint",android.os.Build.FINGERPRINT)
            .put("appSha256",NativeRealFileAsrContract.sha(File(ui.activity.applicationInfo.sourceDir)))
            .put("runnerSha256",NativeRealFileAsrContract.sha(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("fontScale",ui.activity.resources.configuration.fontScale).put("realModelMicHost",false))
    }
    private fun fixture(bytes: ByteArray,shared: Boolean): Fixture {
        val root=NativeRealFileAsrContract.privateDirectory(folder,"fixture-${fixtures.size}")
        return main {Fixture(ui.activity,root,bytes,shared).also {fixtures.add(it);it.start()}}
    }
    private fun assertState(f: Fixture) {
        assertEquals(VoiceInputController.Phase.FAILED,f.controller.phase)
        assertFalse(f.controller.hasRetryableRecording);assertFalse(f.controller.canRetry)
        assertFalse(f.controller.canInspectSavedFile);assertFalse(f.controller.canTranscribeSavedFile)
        assertFalse(f.controller.canRerecordWithSystem);assertFalse(f.controller.canFillRecognizedText)
        assertEquals(f.shared,f.controller.canRecordAgain);assertTrue(f.controller.canEndWaiting)
        assertEquals("录音未完成",f.controller.failureTitleKey)
        assertEquals(COPY,f.controller.error);assertEquals(COPY,f.controller.statusCopyKey)
        assertNull(f.controller.permissionRequest)
    }
    @Test fun restoredProvisionalCannotFinishOrSealEvenWithCompletePcmBytesAndSharing() {
        for(raw in listOf(WavePCM.header(0),WavePCM.header(3200)+ByteArray(3200){7},ByteArray(257){it.toByte()}))for(shared in listOf(false,true)) {
            val f=fixture(raw.copyOf(),shared);assertState(f)
            main {f.controller.finish()};assertState(f) // Synchronous entry guard, not a delayed failure from seal/Host.
            main {f.controller.suspendRecording();yield()};assertState(f)
            rows.put(main {f.preserved()}.put("shared",shared).put("finishAndBackgroundHadNoEffect",true))
        }
        passed=true
    }
    @Test fun bilingualRecoveryCopyAndRerecordActionAgreeWithTheUnsealedOriginal() {
        for(shared in listOf(false,true)) {
            val f=fixture(WavePCM.header(3200)+ByteArray(3200){11},shared)
            for(language in listOf("zh-Hans","en"))for(dark in listOf(false,true)) {
                main {ui.activity.setContent {NativeTheme(dark,language) {Box(Modifier.width(360.dp).height(520.dp)){VoiceInputStrip(f.controller,f.draft.text)}}}}
                ui.waitForIdle();val text=NativeL10n(ui.activity,language);val expected=if(language=="en")ENGLISH else COPY
                assertEquals(expected,text.tr(COPY)) // The new English resource must exist, not fall back to the Chinese key.
                ui.onNodeWithTag("voice-failure-title").performScrollTo().assertIsDisplayed().assertTextEquals(text.tr("录音未完成"))
                ui.onNodeWithTag("voice-status").performScrollTo().assertIsDisplayed().assertTextEquals(expected)
                val primary=ui.onNodeWithTag("stop-dictation").performScrollTo().assertIsDisplayed().assertTextEquals(text.tr("重新录音"))
                if(shared)primary.assertIsEnabled()else primary.assertIsNotEnabled()
                ui.onNodeWithTag("saved-file-download").assertDoesNotExist()
                ui.onNodeWithTag("voice-rerecord-system").assertDoesNotExist()
                main {f.controller.finish()};assertState(f)
                rows.put(main {f.preserved()}.put("language",language).put("dark",dark).put("shared",shared))
            }
        }
        passed=true
    }
    @After fun cleanup() {
        if(!::folder.isInitialized)return
        val errors=mutableListOf<Throwable>();val released=JSONArray()
        fixtures.forEach {f->try {released.put(main {f.stop()})}catch(t: Throwable){errors.add(t)}}
        try {assertEquals(permissionBefore,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))}catch(t: Throwable){errors.add(t)}
        NativeRealFileAsrContract.atomic(File(folder,"result.json"),JSONObject().put("checksCompleted",passed).put("rows",rows)
            .put("cleanup",released).put("cleanupFailures",JSONArray(errors.map {it.javaClass.name}))
            .put("outcome",if(passed&&errors.isEmpty())"COMPONENT_ASSERTIONS_PASSED_REAL_DEV_SEPARATE"else"FAILED_OR_UNKNOWN"))
        MultipleFailureException.assertEmpty(errors)
    }
    companion object {
        const val COPY="已恢复此草稿未完成的录音记录，不能重试转写；不会自动提交。"
        const val ENGLISH="This draft's restored recording is incomplete. Transcription cannot be retried; nothing will be submitted automatically."
    }
}
