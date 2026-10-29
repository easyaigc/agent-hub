package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Injected file engine + real encrypted synthetic original. Zero microphone/Host/real acoustic proof. */
class NativeSavedVoiceControllerTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    private class Engine: NativeSavedVoiceSpeech {
        var probes=0;var downloads=0;var decodes=0;var closed=false
        var gate: CompletableDeferred<Unit>?=null
        override suspend fun probe(locale: String): SavedVoiceProbe {probes++;return SavedVoiceProbe(SavedVoiceAvailability.INSTALLED)}
        override suspend fun install(locale: String,checkpoint: suspend ()->Unit) {downloads++;error("No implicit model download")}
        override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText {
            decodes++;SavedVoicePcm.validate(wav)
            try {gate?.let {withContext(NonCancellable){it.await()}};return SavedVoiceText("synthetic file result","vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")}
            finally {closed=true}
        }
    }
    private suspend fun until(value: ()->Boolean)=withTimeout(10_000){while(!value())delay(5)}
    private fun scenario(block: suspend (android.content.Context,NativeSessionStorage)->Unit)=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val storage=NativeSessionStorage(context,"saved-file-fixture-${UUID.randomUUID()}")
        try {withContext(Dispatchers.Main){block(context,storage)}} finally {withContext(Dispatchers.IO){check(java.io.File(storage.coordinationKey).deleteRecursively())}}
    }
    private data class Fixture(val store: VoiceRecordingStore,val original: PendingVoiceRecording,val controller: VoiceInputController,val engine: Engine,val fills: MutableList<String>,val bytes: ByteArray,val destination: NativeSavedVoiceDestinationFixture,val storage: NativeSessionStorage,val originalRow: String)
    private suspend fun fixture(context: android.content.Context,storage: NativeSessionStorage,valid: ()->Boolean={true}): Fixture {
        val store=VoiceRecordingStore(context,storage);val scope=AISharingScope(TargetScope("codex",null,null,"fixture-host","workspace","runtime"),"codex-dictation")
        val client=MobileClient("fixture-host","Fixture",scope.target,endpoint="https://fixture.invalid")
        val draft=VoiceDraft(client,scope,client.cacheKey("thread"),"version","voice","thread","original draft")
        val record=store.begin(scope,null,draft.key,draft.draftId,draft.threadId,draft.text);store.file(record.id).writeBytes(WavePCM.header(3200)+ByteArray(3200))
        val sealed=store.seal(record);store.mark(sealed,true,true);val original=store.current(sealed);val fills=mutableListOf<String>();val engine=Engine()
        val controller=VoiceInputController(context,store){error("No desktop probe/prepare/query/upload or microphone fallback")}
        val destination=NativeSavedVoiceDestinationFixture(storage,draft){fills.add(it.removePrefix("original draft\n"))}
        controller.savedFileFactory={engine};controller.localResultPort=NativeEncryptedVoiceLocalResultPort(storage)
        val originalRow=checkNotNull(storage.read("voice-recording:${original.id}"))
        controller.start(draft,{false},{_,_->error("No desktop fill")},localAllowed=valid,localApply={_,_->error("A Boolean is not durable acceptance")},localDestination=destination.port)
        until{controller.phase==VoiceInputController.Phase.FAILED};controller.chooseSavedFileLocale("en-US")
        return Fixture(store,original,controller,engine,fills,store.bytes(original),destination,storage,originalRow)
    }
    private suspend fun probe(f: Fixture) {f.controller.inspectSavedFile();until{!f.controller.savedFileBusy};assertTrue(f.controller.canTranscribeSavedFile)}
    private suspend fun preserved(f: Fixture) {val current=f.store.current(f.original);assertEquals("Complete original metadata, including target history and all attempt fields",easyCanonical(f.original.json()),easyCanonical(current.json()));assertEquals("Exact persisted original row bytes",f.originalRow,f.storage.read("voice-recording:${f.original.id}"));assertArrayEquals(f.bytes,f.store.bytes(current))}
    private suspend fun close(f: Fixture) {f.engine.gate?.complete(Unit);f.controller.cancel();f.controller.quiesceForMigration();f.controller.retireForMigration()}
    @Test fun recoveryWithNoMatchingFileNeverStartsAMicrophoneOrDesktopRequest()=scenario {context,storage->
        val store=VoiceRecordingStore(context,storage);val scope=AISharingScope(TargetScope("codex",null,null,"fixture-host","workspace","runtime"),"codex-dictation")
        val draft=VoiceDraft(MobileClient("fixture-host","Fixture",scope.target),scope,"absent","version","voice","thread","draft")
        val controller=VoiceInputController(context,store){error("Recovery must not contact a Host")}
        controller.systemFactory={error("Recovery must not open a microphone")}
        try {
            controller.start(draft,{true},{_,_->error("No original to fill")},restoreOnly=true)
            until{controller.phase==VoiceInputController.Phase.FAILED};assertNull(controller.permissionRequest);assertFalse(controller.canRetry)
            assertEquals("没有与当前草稿匹配的已保存原录音。",controller.status)
        }finally{controller.cancel();controller.quiesceForMigration();controller.retireForMigration()}
    }
    @Test fun explicitFileActionStagesThenFillsWithoutDesktopConsentAndPreservesTheOriginal()=scenario {context,storage->
        val f=fixture(context,storage)
        try {
            assertEquals(0,f.engine.probes);assertEquals(0,f.engine.decodes);probe(f);assertEquals(0,f.engine.downloads)
            f.controller.transcribeSavedFile();until{f.controller.phase==VoiceInputController.Phase.IDLE&&!f.controller.savedFileBusy}
            assertEquals(listOf("synthetic file result"),f.fills);assertTrue(f.engine.closed);preserved(f)
            val result=NativeVoiceLocalResults.read(storage).single();assertTrue(result.applied);assertNotEquals(f.original.id,result.actionId);result.validate(f.original)
            assertNull(f.store.restore(f.original.scope,f.original.certificate,f.original.draftKey,f.original.originalText))
            f.store.remove(f.original);preserved(f) // Independent local result keeps original evidence exportable.
        }finally {close(f)}
    }
    @Test fun endWaitingRemainsBusyUntilTheActualWorkerExitAndNeverAppliesLateText()=scenario {context,storage->
        val f=fixture(context,storage);f.engine.gate=CompletableDeferred()
        try {
            probe(f);f.controller.transcribeSavedFile();until{f.engine.decodes==1};f.controller.endWaiting();delay(50)
            assertTrue(f.controller.savedFileBusy);assertEquals(VoiceInputController.Phase.FINISHING,f.controller.phase);assertFalse(f.controller.canRetry);assertFalse(f.controller.canRecordAgain)
            f.controller.transcribeSavedFile();f.controller.finish();f.controller.discard();assertEquals(1,f.engine.decodes);assertTrue(f.fills.isEmpty())
            f.engine.gate!!.complete(Unit);until{f.controller.phase==VoiceInputController.Phase.IDLE};assertTrue(f.engine.closed);assertTrue(f.fills.isEmpty());preserved(f)
        }finally {close(f)}
    }
    @Test fun laterDisposePromotesBackgroundCloseOnlyAfterActualEngineExit()=scenario {context,storage->
        val f=fixture(context,storage);f.engine.gate=CompletableDeferred()
        try {
            probe(f);f.controller.transcribeSavedFile();until{f.engine.decodes==1}
            f.controller.suspendRecording();f.controller.cancel();delay(50)
            assertTrue(f.controller.savedFileBusy);assertEquals(VoiceInputController.Phase.FINISHING,f.controller.phase)
            f.engine.gate!!.complete(Unit);until{f.controller.phase==VoiceInputController.Phase.IDLE};assertTrue(f.fills.isEmpty());preserved(f)
            f.controller.start(f.destination.draft,{false},{_,_->false},localAllowed={true},localDestination=f.destination.port)
            until{f.controller.phase==VoiceInputController.Phase.FAILED};assertTrue(f.controller.canInspectSavedFile)
        }finally{close(f)}
    }
    @Test fun actualDestinationWriteHoldKeepsStagedAndDisposeJoinsItsExit()=scenario {context,storage->
        val f=fixture(context,storage);val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        f.destination.checkpoint={if(it==0){entered.countDown();check(release.await(15,java.util.concurrent.TimeUnit.SECONDS))}}
        try {
            probe(f);f.controller.transcribeSavedFile();until{entered.count==0L};assertTrue(f.engine.closed);assertTrue(f.fills.isEmpty())
            f.controller.suspendRecording();f.controller.cancel();delay(50);assertTrue(f.controller.savedFileBusy);assertTrue(f.controller.active)
            release.countDown();until{f.controller.phase==VoiceInputController.Phase.IDLE};assertTrue(f.fills.isEmpty());preserved(f)
            val result=NativeVoiceLocalResults.read(storage).single();assertFalse(result.applied);assertNull(result.destination)
            val reopened=storage.reopen(context).metadata()!!;assertEquals("original draft\nsynthetic file result",reopened.getJSONObject("drafts").getString(result.recordingId.let {NativeVoiceDestinationKeys.owner(f.destination.draft.key)}))
        }finally{release.countDown();close(f)}
    }
    @Test fun aLaterQueuedOldSnapshotRevokesDestinationAcceptanceBeforePublication()=scenario {context,storage->
        val f=fixture(context,storage);val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        f.destination.checkpoint={if(it==0){entered.countDown();check(release.await(15,java.util.concurrent.TimeUnit.SECONDS))}}
        try {
            probe(f);f.controller.transcribeSavedFile();until{entered.count==0L};f.destination.enqueueUnchanged();release.countDown()
            until{!f.controller.savedFileBusy};storage.awaitQueuedWrites();assertTrue(f.fills.isEmpty());assertFalse(NativeVoiceLocalResults.read(storage).single().applied)
            assertEquals("original draft",storage.reopen(context).metadata()!!.getJSONObject("drafts").getString(f.destination.draft.key));preserved(f)
        }finally{release.countDown();close(f)}
    }
    @Test fun failedOrUnknownDestinationWriteNeverPublishesAnAppliedTerminal()=scenario {context,storage->
        for(phase in listOf(0,1)) {
            val f=fixture(context,storage)
            try {
                f.destination.checkpoint={if(it==phase)throw java.io.IOException("synthetic destination failure")}
                probe(f);f.controller.transcribeSavedFile();until{!f.controller.savedFileBusy};assertTrue(f.fills.isEmpty());preserved(f)
                val staged=NativeVoiceLocalResults.read(storage).last();assertFalse(staged.applied);assertNull(staged.destination)
                val reopened=storage.reopen(context);val text=reopened.metadata()!!.getJSONObject("drafts").getString(NativeVoiceDestinationKeys.owner(f.destination.draft.key))
                if(phase==0) {assertEquals("original draft",text);assertNotNull(VoiceRecordingStore(context,reopened).restore(f.original.scope,f.original.certificate,f.original.draftKey,f.original.originalText))}
                else assertEquals("original draft\nsynthetic file result",text)
            }finally{close(f)}
        }
    }
    @Test fun targetChangeRejectsLateResultsAndMigrationWaitsForNativeExit()=scenario {context,storage->
        var valid=true;val f=fixture(context,storage){valid};f.engine.gate=CompletableDeferred()
        try {
            probe(f);f.controller.transcribeSavedFile();until{f.engine.decodes==1};valid=false
            val join=CoroutineScope(currentCoroutineContext()).async {f.controller.quiesceForMigration()};delay(50);assertFalse(join.isCompleted)
            f.engine.gate!!.complete(Unit);join.await();assertTrue(f.engine.closed);assertTrue(f.fills.isEmpty());preserved(f)
            assertTrue(NativeVoiceLocalResults.read(storage).isEmpty())
        }finally {close(f)}
    }
    @Test fun catalogRejectsUnknownFieldsForeignOriginalAndUnknownModels()=scenario {context,storage->
        val f=fixture(context,storage)
        try {
            val result=NativeVoiceLocalResult(UUID.randomUUID().toString(),f.original.id,NativeVoiceDiscardRequest(f.original).fingerprint,f.original.manifest!!.sha256,"en-US","vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498","fixture",1)
            assertEquals(result,NativeVoiceLocalResults.decode(NativeVoiceLocalResults.encode(listOf(result))).single())
            val target=f.original.scope.target
            val foreign=f.destination.draft.copy(client=f.destination.draft.client.copy(scope=TargetScope(target.agentId,target.accountAuthority,target.accountSubject,target.hostId,target.workspaceId,"other-runtime")))
            assertTrue(runCatching{NativeVoiceDraftRequest(foreign,f.original,result)}.isFailure)
            assertTrue(runCatching{result.copy(fingerprint="0".repeat(64)).validate(f.original)}.isFailure)
            assertTrue(runCatching{NativeVoiceLocalResults.encode(listOf(result.copy(modelSha256="0".repeat(64))))}.isFailure)
            val legacy=org.json.JSONObject(NativeVoiceLocalResults.encode(listOf(result))).put("version",1)
            legacy.getJSONArray("results").getJSONObject(0).remove("destination");legacy.getJSONArray("results").getJSONObject(0).put("applied",true)
            assertFalse(NativeVoiceLocalResults.decode(legacy.toString()).single().applied)
            val raw=org.json.JSONObject(NativeVoiceLocalResults.encode(listOf(result)));raw.getJSONArray("results").getJSONObject(0).put("phase","completed")
            assertTrue(runCatching{NativeVoiceLocalResults.decode(raw.toString())}.isFailure);preserved(f)
        }finally {close(f)}
    }
}
