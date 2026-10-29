package com.agenthub.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic original bytes and callbacks only; no recognizer, microphone, OS permission or Host I/O. */
internal class NativeSavedVoiceFixture private constructor(
    val store: VoiceRecordingStore,val original: PendingVoiceRecording,val draft: VoiceDraft,
    val controller: VoiceInputController,val bytes: ByteArray
) {
    class Engine: NativeSystemVoice {
        lateinit var listener: NativeSystemVoice.Listener
        var starts=0;var cancels=0
        override fun start(listener: NativeSystemVoice.Listener) { starts++;this.listener=listener }
        override fun finish() {}
        override fun cancel() { cancels++ }
    }
    val engine=Engine()
    val applied=mutableListOf<String>()
    var terminalCalls=0
    suspend fun phoneReady() {
        until { controller.permissionRequest!=null || engine.starts>0 }
        controller.permissionRequest?.let { controller.permissionResult(it,true) }
        until { engine.starts==1 };engine.listener.ready()
    }
    suspend fun preserved() {
        val current=store.current(original)
        assertEquals(original.id,current.id);assertEquals(original.manifest,current.manifest)
        assertEquals(original.prepareAttempted,current.prepareAttempted);assertEquals(original.audioAttempted,current.audioAttempted)
        assertEquals(original.id,store.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!.id)
        assertArrayEquals(bytes,if(original.manifest!=null)store.bytes(current)else store.file(original.id).readBytes())
    }
    suspend fun cleanup() {
        controller.cancel();until { controller.phase==VoiceInputController.Phase.IDLE }
        store.remove(original) // This fixture's exact synthetic UUID only.
    }
    companion object {
        suspend fun until(value: ()->Boolean)=withTimeout(10_000) { while(!value())delay(5) }
        suspend fun create(context: Context=InstrumentationRegistry.getInstrumentation().targetContext,
            storage: NativeSessionStorage=NativeSessionStorage(context,"saved-voice-fallback-${UUID.randomUUID()}"),
            commands: AgentCommands?=null,sealed: Boolean=true,current: ()->Boolean={true}): NativeSavedVoiceFixture {
            val scope=AISharingScope(TargetScope("codex",null,null,"saved-voice-host","workspace","runtime"),"codex-dictation")
            val draft=VoiceDraft(MobileClient("host","Fixture",scope.target),scope,"key","version","voice","thread","Original draft / 原草稿")
            val store=VoiceRecordingStore(context,storage)
            var record=store.begin(scope,null,draft.key,draft.draftId,draft.threadId,draft.text)
            store.file(record.id).writeBytes(WavePCM.header(3200)+ByteArray(3200) { (it%127).toByte() })
            if(sealed) { record=store.seal(record);store.mark(record,true,true);record=store.current(record) }
            val denied=object: ContextWrapper(context) {
                override fun checkSelfPermission(permission: String)=if(permission==Manifest.permission.RECORD_AUDIO)PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            }
            val controller=VoiceInputController(denied,store) { commands ?: error("No implicit query/prepare/upload or Host I/O") }
            val f=NativeSavedVoiceFixture(store,record,draft,controller,if(sealed)store.bytes(record)else store.file(record.id).readBytes())
            controller.systemFactory={f.engine}
            controller.discardPort=NativeVoiceDiscardPort { f.terminalCalls++;error("Preserving fallback/ending wait must not retire the original") }
            controller.start(draft,current,{original,text->assertEquals(draft,original);f.applied.add(text);true})
            until { controller.phase==VoiceInputController.Phase.FAILED }
            return f
        }
    }
}

class NativeSavedVoiceFallbackTest {
    @get:org.junit.Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    private fun scenario(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Test fun freshPhoneCaptureKeepsTheUnknownOriginalOfferableWithoutATerminalOrFileTranscription()=scenario {
        val f=NativeSavedVoiceFixture.create()
        try {
            assertTrue(f.controller.canRerecordWithSystem);f.controller.recordAgainWithSystem();f.phoneReady()
            assertEquals(0,f.terminalCalls);f.engine.listener.result("new microphone text")
            f.engine.listener.result("duplicate");assertEquals(listOf("new microphone text"),f.applied)
            assertEquals(VoiceInputController.Phase.IDLE,f.controller.phase);f.preserved()
        }finally { f.cleanup() }
    }
    @Test fun endWaitingPreservesTheOriginalAndLateReceiptCannotOverwriteANewPhoneDraft()=scenario {
        val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>();var queries=0
        val commands=object: AgentCommands by NativeCodexCommands({error("No real command")}) {
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt {
                queries++;entered.complete(Unit);withContext(NonCancellable) { released.await() }
                returned.complete(Unit);return VoiceReceipt(request,"completed","late original text",null,"2030-01-01T00:00:00Z")
            }
        }
        val f=NativeSavedVoiceFixture.create(commands=commands);val fresh=mutableListOf<String>()
        try {
            f.controller.finish();withTimeout(10_000) { entered.await() };f.controller.endWaiting()
            NativeSavedVoiceFixture.until { f.controller.phase==VoiceInputController.Phase.IDLE };f.preserved()
            f.controller.start(f.draft.copy(text="Fresh draft"),{true},{_,text->fresh.add(text);true},provider=VoiceInputController.Provider.SYSTEM)
            f.phoneReady();released.complete(Unit);withTimeout(10_000) {returned.await()};yield()
            assertEquals(VoiceInputController.Phase.LISTENING,f.controller.phase);assertTrue(f.applied.isEmpty());assertTrue(fresh.isEmpty())
            f.engine.listener.result("fresh phone text");assertEquals(listOf("fresh phone text"),fresh)
            assertEquals(1,queries);assertEquals(0,f.terminalCalls);f.preserved()
        }finally { released.complete(Unit);f.cleanup() }
    }
    @Test fun backgroundAndChangedAuthorityFencePhoneCallbacksAndPreserveOriginalBytes()=scenario {
        var current=true;val f=NativeSavedVoiceFixture.create(current={current})
        try {
            f.controller.recordAgainWithSystem();f.phoneReady();f.engine.listener.partial("partial only")
            current=false;f.controller.suspendRecording();f.engine.listener.result("late final");f.engine.listener.failed(7)
            assertTrue(f.applied.isEmpty());assertFalse(f.controller.canRecordAgain);assertFalse(f.controller.canFillRecognizedText)
            f.controller.recordAgainWithSystem();assertEquals(1,f.engine.starts);f.preserved()
        }finally { f.cleanup() }
    }
    @Test fun preservingPhoneActionCannotBypassAProvisionalOriginalOrAFailedDiscard()=scenario {
        for(sealed in listOf(false,true)) {
            val f=NativeSavedVoiceFixture.create(sealed=sealed)
            try {
                if(sealed) { f.controller.discard();NativeSavedVoiceFixture.until { f.controller.discardFailed } }
                assertFalse(f.controller.canRerecordWithSystem);f.controller.recordAgainWithSystem()
                assertEquals(0,f.engine.starts);assertEquals(if(sealed)1 else 0,f.terminalCalls);f.preserved()
            }finally { f.cleanup() }
        }
    }
    @Test fun endWaitingKeepsAProvisionalOriginalAndItsPrivatePcmWithoutATerminal()=scenario {
        val f=NativeSavedVoiceFixture.create(sealed=false)
        try {
            f.controller.endWaiting();NativeSavedVoiceFixture.until { !f.controller.active }
            assertEquals(0,f.terminalCalls);assertEquals(0,f.engine.starts);f.preserved()
        }finally { f.cleanup() }
    }
}
