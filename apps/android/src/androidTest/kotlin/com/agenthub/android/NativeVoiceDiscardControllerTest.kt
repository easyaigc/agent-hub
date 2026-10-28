package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID

/** Real encrypted synthetic original, injected terminal port. This does not claim a production terminal Schema. */
class NativeVoiceDiscardControllerTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private fun gate()=CompletableDeferred<Unit>().also(gates::add)
    private class Engine: NativeSystemVoice {
        lateinit var listener: NativeSystemVoice.Listener
        var starts=0
        override fun start(listener: NativeSystemVoice.Listener) { starts++;this.listener=listener }
        override fun finish() {}
        override fun cancel() {}
    }
    private data class Fixture(val store: VoiceRecordingStore,val original: PendingVoiceRecording,val controller: VoiceInputController,
        val engine: Engine,val draft: VoiceDraft,val applied: MutableList<String>,val bytes: ByteArray)
    private suspend fun fixture(commands: AgentCommands?=null): Fixture {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"discard-controller-"+UUID.randomUUID()))
        val scope=AISharingScope(TargetScope("codex",null,null,"host","workspace","runtime"),"codex-dictation")
        val draft=VoiceDraft(MobileClient("host","Fixture",scope.target),scope,"key","version","voice","thread","原草稿")
        val initial=store.begin(scope,null,draft.key,draft.draftId,draft.threadId,draft.text)
        store.file(initial.id).writeBytes(WavePCM.header(3200)+ByteArray(3200))
        val sealed=store.seal(initial);store.mark(sealed,true,true);val original=store.current(sealed)
        val applied=mutableListOf<String>();val engine=Engine()
        val controller=VoiceInputController(context,store) { commands ?: error("No implicit Host/remote cancellation/prepare/upload") }
        controller.systemFactory={engine};controller.start(draft,{true},{_,text->applied.add(text);true})
        until { controller.phase==VoiceInputController.Phase.FAILED }
        return Fixture(store,original,controller,engine,draft,applied,store.bytes(original))
    }
    private suspend fun until(value: ()->Boolean)=withTimeout(10_000) { while(!value())delay(5) }
    private fun scenario(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { try { block() }finally { gates.forEach { it.complete(Unit) } } } }
    private suspend fun cleanup(f: Fixture) {
        f.controller.suspendRecording();gates.forEach { it.complete(Unit) };until { !f.controller.discarding }
        f.controller.cancel();until { f.controller.phase==VoiceInputController.Phase.IDLE };f.store.remove(f.original) // fixture UUID only
    }
    private suspend fun preserved(f: Fixture) {
        val current=f.store.current(f.original)
        assertEquals(f.original.id,current.id);assertEquals(f.original.manifest,current.manifest)
        assertTrue(current.prepareAttempted);assertTrue(current.audioAttempted);assertArrayEquals(f.bytes,f.store.bytes(current))
    }
    @Test fun explicitCloseWaitsForTheMatchingTerminalBeforeClearingWithoutTouchingOriginalEvidence()=scenario {
        val f=fixture();val entered=gate();val released=gate();var request: NativeVoiceDiscardRequest?=null
        f.controller.discardPort=NativeVoiceDiscardPort { value -> request=value;assertTrue(value.matches(f.store.current(f.original)));entered.complete(Unit);released.await();NativeVoiceDiscardReceipt(value.recordingId,value.fingerprint,true) }
        try {
            f.controller.discard();entered.await()
            assertTrue(f.controller.discarding);assertEquals(VoiceInputController.Phase.FINISHING,f.controller.phase);assertFalse(f.controller.canRetry)
            f.controller.finish();assertTrue(f.applied.isEmpty());assertEquals(0,f.engine.starts);preserved(f)
            released.complete(Unit);until { f.controller.phase==VoiceInputController.Phase.IDLE }
            assertEquals(f.original.id,request!!.recordingId);preserved(f)
        }finally { cleanup(f) }
    }
    @Test fun missingFailedOrForeignTerminalNeverClosesOrStartsANewCapture()=scenario {
        val f=fixture();val requests=mutableListOf<NativeVoiceDiscardRequest>()
        try {
            f.controller.discard();until { f.controller.discardFailed };assertFalse(f.controller.canRetry)
            f.controller.discardPort=NativeVoiceDiscardPort { value -> requests+=value;throw IOException("synthetic local terminal write failure") }
            f.controller.rerecordWith(VoiceInputController.Provider.SYSTEM);until { f.controller.discardFailed }
            assertEquals(0,f.engine.starts);assertEquals(VoiceInputController.Phase.FAILED,f.controller.phase)
            f.controller.discardPort=NativeVoiceDiscardPort { value -> requests+=value;NativeVoiceDiscardReceipt("wrong-id",value.fingerprint,true) }
            f.controller.discard();until { f.controller.discardFailed };assertEquals(0,f.engine.starts)
            f.controller.discardPort=NativeVoiceDiscardPort { value -> requests+=value;NativeVoiceDiscardReceipt(value.recordingId,value.fingerprint,true) }
            f.controller.discard();until { f.controller.phase==VoiceInputController.Phase.IDLE }
            assertEquals(1,requests.map { it.fingerprint }.distinct().size);assertTrue(f.applied.isEmpty());preserved(f)
        }finally { cleanup(f) }
    }
    @Test fun explicitRerecordCannotOpenTheNewSystemProviderUntilTerminalAcknowledgement()=scenario {
        val f=fixture();val entered=gate();val released=gate();var marked=false
        f.controller.discardPort=NativeVoiceDiscardPort { value -> entered.complete(Unit);released.await();marked=true;NativeVoiceDiscardReceipt(value.recordingId,value.fingerprint,true) }
        try {
            f.controller.rerecordWith(VoiceInputController.Provider.SYSTEM);entered.await();assertEquals(0,f.engine.starts)
            released.complete(Unit)
            until { f.controller.permissionRequest?.let { f.controller.permissionResult(it,true) };f.engine.starts==1 }
            assertTrue(marked);f.engine.listener.ready();f.engine.listener.result("fresh audio text")
            assertEquals(listOf("fresh audio text"),f.applied);preserved(f)
        }finally { cleanup(f) }
    }
    @Test fun backgroundOrNavigationRevokesDelayedRerecordEvenAfterReturningToTheSameDraft()=scenario {
        for(navigation in listOf(false,true)) {
            val f=fixture();val entered=gate();val released=gate()
            f.controller.discardPort=NativeVoiceDiscardPort { value -> entered.complete(Unit);released.await();NativeVoiceDiscardReceipt(value.recordingId,value.fingerprint,true) }
            try {
                f.controller.rerecordWith(VoiceInputController.Provider.SYSTEM);entered.await()
                if(navigation)f.controller.cancel() else f.controller.suspendRecording()
                released.complete(Unit);until { f.controller.phase==VoiceInputController.Phase.IDLE }
                assertEquals(0,f.engine.starts);assertTrue(f.applied.isEmpty());preserved(f)
            }finally { cleanup(f) }
        }
    }
    @Test fun lateOriginalReceiptCannotFillTheDraftAndDiscardDoesNotReplayItsQueryOrAudio()=scenario {
        val entered=gate();val released=gate();var queries=0;var marked=false
        val commands=object: AgentCommands by NativeCodexCommands({error("No write or remote cancel")}) {
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt {
                queries++;entered.complete(Unit);withContext(NonCancellable) { released.await() }
                return VoiceReceipt(request,"completed","late original result",null,"2030-01-01T00:00:00Z")
            }
        }
        val f=fixture(commands)
        f.controller.discardPort=NativeVoiceDiscardPort { value -> marked=true;NativeVoiceDiscardReceipt(value.recordingId,value.fingerprint,true) }
        try {
            f.controller.finish();entered.await();f.controller.discard();assertFalse(marked)
            released.complete(Unit);until { f.controller.phase==VoiceInputController.Phase.IDLE }
            assertTrue(marked);assertEquals(1,queries);assertTrue(f.applied.isEmpty());preserved(f)
        }finally { cleanup(f) }
    }
    @Test fun navigationDuringAFailedTerminalOnlyDetachesPresentationAndKeepsTheOriginalRecoverable()=scenario {
        val f=fixture();val entered=gate();val released=gate()
        f.controller.discardPort=NativeVoiceDiscardPort { entered.complete(Unit);released.await();throw IOException("synthetic marker failure after navigation") }
        try {
            f.controller.discard();entered.await();f.controller.cancel();released.complete(Unit)
            until { f.controller.phase==VoiceInputController.Phase.IDLE }
            val restored=f.store.restore(f.original.scope,f.original.certificate,f.original.draftKey,f.original.originalText)
            assertEquals(f.original.id,restored!!.id);assertEquals(0,f.engine.starts);assertTrue(f.applied.isEmpty());preserved(f)
        }finally { cleanup(f) }
    }
    @Test fun discardFingerprintBindsImmutableOriginalAndIgnoresMutableReceiptAttemptFlags()=scenario {
        val f=fixture()
        try {
            val request=NativeVoiceDiscardRequest(f.original)
            assertEquals(request.fingerprint,NativeVoiceDiscardRequest(f.original.copy(prepareAttempted=false,audioAttempted=false)).fingerprint)
            assertFalse(request.matches(f.original.copy(draftKey="another draft")))
            assertFalse(request.matches(f.original.copy(originalText="changed original")))
            assertFalse(request.matches(f.original.copy(manifest=f.original.manifest!!.copy(sha256="b".repeat(64)))))
            val later=AISharingScope(TargetScope("codex","history","other-account","host","workspace","runtime"),"codex-dictation")
            assertEquals(request.fingerprint,NativeVoiceDiscardRequest(f.original.copy(scope=later)).fingerprint)
            assertTrue(request.matches(f.original.copy(scope=later)))
        }finally { cleanup(f) }
    }
}
