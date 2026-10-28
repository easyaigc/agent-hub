package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeVoiceRecoveryTest {
    private class Engine: NativeSystemVoice {
        lateinit var listener: NativeSystemVoice.Listener
        var starts=0
        override fun start(listener: NativeSystemVoice.Listener) { starts++;this.listener=listener }
        override fun finish() {}
        override fun cancel() {}
    }
    @Test fun explicitNewSystemCapturePreservesTheUnknownOriginalRecordingAndNeverTranscribesIt()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-recovery-"+UUID.randomUUID()))
        val target=TargetScope("codex",null,null,"host","workspace","runtime")
        val draft=VoiceDraft(MobileClient("host","Fixture",target),AISharingScope(target,"codex-dictation"),"key","version","voice","thread","原草稿")
        var original=store.begin(draft.scope,draft.certificate,draft.key,draft.draftId,draft.threadId,draft.text)
        store.file(original.id).writeBytes(WavePCM.header(3200)+ByteArray(3200))
        original=store.seal(original);store.mark(original,prepareAttempted=true,audioAttempted=true)
        val bytes=store.bytes(original);val engine=Engine();val applied=mutableListOf<String>()
        val controller=VoiceInputController(context,store) { error("Unknown original must not query/prepare/upload automatically") }
        controller.systemFactory={engine}
        var marked: NativeVoiceDiscardRequest?=null
        controller.discardPort=NativeVoiceDiscardPort { request ->
            assertTrue(request.matches(store.current(original)));marked=request
            NativeVoiceDiscardReceipt(request.recordingId,request.fingerprint,true)
        }
        try {
            withContext(Dispatchers.Main) {
                controller.start(draft,{true},{_,text->applied.add(text);true})
                withTimeout(10_000) { while(controller.phase!=VoiceInputController.Phase.FAILED)delay(10) }
                assertTrue(controller.canRetry);assertTrue(controller.canRerecordWithSystem)
                controller.rerecordWith(VoiceInputController.Provider.SYSTEM)
                withTimeout(10_000) { while(engine.starts==0) { controller.permissionRequest?.let { controller.permissionResult(it,true) };delay(5) } }
                engine.listener.ready();assertEquals(1,engine.starts);assertEquals(original.id,marked!!.recordingId)
                engine.listener.result("新录音文字");assertEquals(listOf("新录音文字"),applied)
            }
            assertArrayEquals(bytes,store.bytes(original));val after=store.current(original)
            assertEquals(original.manifest,after.manifest);assertEquals(original.id,after.id);assertTrue(after.prepareAttempted);assertTrue(after.audioAttempted)
        }finally { withContext(Dispatchers.Main) { controller.cancel() };store.remove(original) /* only this synthetic fixture's UUID */ }
    }
}
