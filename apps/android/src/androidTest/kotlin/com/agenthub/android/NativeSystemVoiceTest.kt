package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Injected recognizer callbacks only. The system microphone/acoustic provider remains a separate real gate. */
class NativeSystemVoiceTest {
    private class Engine : NativeSystemVoice {
        lateinit var listener: NativeSystemVoice.Listener
        var stops = 0; var cancels = 0
        var failure: Exception? = null
        var onFinish: (() -> Unit)? = null
        override fun start(listener: NativeSystemVoice.Listener) { this.listener = listener; failure?.let { throw it } }
        override fun finish() { stops++; onFinish?.invoke() }
        override fun cancel() { cancels++ }
    }
    private fun main(action: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main) { action() } }
    private fun controller(engine: Engine): VoiceInputController {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return VoiceInputController(context, VoiceRecordingStore(context, NativeSessionStorage(context, "system-voice-" + UUID.randomUUID()))) {
            error("Local speech must not call Host commands")
        }.also { it.systemFactory = { engine } }
    }
    private fun draft(): VoiceDraft {
        val scope = TargetScope("codex", null, null, "host", "workspace", "runtime")
        return VoiceDraft(MobileClient("client", "fixture", scope), AISharingScope(scope, "codex-dictation"), "draft", "version", "voice", "thread", "原草稿")
    }
    @Test fun localSpeechOnlyFillsOriginalDraftAndFinishIsSingleShot() {
        val engine = Engine(); val controller = controller(engine); val applied = mutableListOf<String>()
        main {
            controller.start(draft(), { true }, { _, text -> applied.add(text); true }, provider = VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) }; yield()
            engine.listener.ready(); engine.listener.partial("中文 😀")
            assertEquals("中文 😀", controller.transcript)
            assertEquals("正在本地听写", controller.statusCopyKey)
            controller.finish(); controller.finish(); assertEquals(1, engine.stops)
            engine.listener.result("完整结果")
            assertEquals(listOf("完整结果"), applied); assertEquals(VoiceInputController.Phase.IDLE, controller.phase)
            engine.listener.result("late duplicate"); assertEquals(1, applied.size)
            assertEquals(1, engine.cancels)
        }
    }
    @Test fun synchronousFinishResultCannotStartAnIdleTimeoutOrApplyDuplicateText() {
        val engine=Engine();val controller=controller(engine);val applied=mutableListOf<String>()
        try { main {
            controller.start(draft(),{true},{_,text->applied.add(text);true},provider=VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) };yield()
            engine.listener.ready();engine.onFinish={engine.listener.result("synchronous final")}
            controller.finish();controller.finish()
            assertEquals(1,engine.stops);assertEquals(1,engine.cancels)
            assertEquals(listOf("synchronous final"),applied);assertEquals(VoiceInputController.Phase.IDLE,controller.phase)
            engine.listener.result("late duplicate");engine.listener.failed(7)
            // Observe the real 15-second finish deadline, rather than inspecting a private Job.
            delay(16_000)
            assertEquals(VoiceInputController.Phase.IDLE,controller.phase);assertNull(controller.error)
            assertEquals(listOf("synchronous final"),applied)
        } } finally { main {controller.cancel()} }
    }
    @Test fun synchronousFinishFailureThenThrowCannotReplaceTheOriginalTerminalReceipt() {
        val engine=Engine();val controller=controller(engine);var applies=0
        try { main {
            controller.start(draft(),{true},{_,_->applies++;true},provider=VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) };yield()
            engine.listener.ready();engine.listener.partial("original partial")
            engine.onFinish={engine.listener.failed(7);throw IllegalStateException("late finish exception")}
            controller.finish()
            assertEquals(VoiceInputController.Phase.FAILED,controller.phase)
            assertEquals("没有识别到语音，请重新录音。",controller.error)
            assertEquals(controller.error,controller.statusCopyKey)
            assertEquals("original partial",controller.transcript);assertTrue(controller.canFillRecognizedText)
            engine.listener.result("old final");assertEquals(0,applies)
            assertEquals(1,engine.stops);assertEquals(1,engine.cancels)
            assertTrue(controller.fillRecognizedText());assertEquals(1,applies)
            assertEquals(VoiceInputController.Phase.IDLE,controller.phase)
        } } finally { main {controller.cancel()} }
    }
    @Test fun backgroundAndChangedDraftFencePartialFinalAndOldErrors() {
        for (background in listOf(true, false)) {
            val engine = Engine(); val controller = controller(engine); var allowed = true; var applies = 0
            main {
                controller.start(draft(), { allowed }, { _, _ -> applies++; true }, provider = VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) }; yield()
                engine.listener.ready(); if (background) controller.suspendRecording() else allowed = false
                engine.listener.partial("late partial"); engine.listener.result("late final"); engine.listener.failed(7)
                assertEquals(0, applies); assertEquals("", controller.transcript)
                controller.cancel(); assertEquals(VoiceInputController.Phase.IDLE, controller.phase)
            }
        }
    }
    @Test fun knownProductFailureIsLocalizedButLiteralServiceErrorsAreNot() {
        val engine = Engine(); val controller = controller(engine)
        main {
            controller.start(draft(), { true }, { _, _ -> true }, provider = VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) }; yield()
            engine.listener.failed(7)
            assertEquals("没有识别到语音，请重新录音。", controller.statusCopyKey)
            assertFalse(controller.canRetry); controller.cancel()
        }
        val external = Engine().apply { failure = IllegalStateException("正在本地听写") }; val second = controller(external)
        main {
            second.start(draft(), { true }, { _, _ -> true }, provider = VoiceInputController.Provider.SYSTEM)
            second.permissionRequest?.let { second.permissionResult(it,true) }; yield()
            assertEquals("正在本地听写", second.error); assertNull(second.statusCopyKey)
            assertEquals(1, external.cancels); second.cancel()
        }
    }
    @Test fun cancelledOldResultCannotAffectANewCapture() {
        val old = Engine(); val fresh = Engine(); val controller = controller(old); var calls = 0
        main {
            controller.start(draft(), { true }, { _, _ -> calls++; true }, provider = VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) }; yield()
            old.listener.ready(); controller.cancel(); controller.systemFactory = { fresh }
            controller.start(draft(), { true }, { _, _ -> calls++; true }, provider = VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) }; yield()
            fresh.listener.ready(); old.listener.result("old"); old.listener.failed(3)
            assertEquals(VoiceInputController.Phase.LISTENING, controller.phase); assertEquals(0, calls)
            fresh.listener.result("new"); assertEquals(1, calls)
        }
    }
    @Test fun backgroundPartialTextNeedsExplicitCurrentDraftActionAndLateFinalCannotOverwriteIt() {
        val engine=Engine();val controller=controller(engine);var foreground=true;val applied=mutableListOf<String>()
        main {
            controller.start(draft(),{foreground},{_,text->applied.add(text);true},provider=VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) };yield()
            engine.listener.ready();engine.listener.partial("保留的文字 😀");foreground=false;controller.suspendRecording()
            assertEquals("保留的文字 😀",controller.transcript);assertFalse(controller.canFillRecognizedText);assertFalse(controller.fillRecognizedText())
            engine.listener.result("迟到结果");assertTrue(applied.isEmpty())
            foreground=true;assertTrue(controller.canFillRecognizedText);assertTrue(controller.fillRecognizedText())
            assertEquals(listOf("保留的文字 😀"),applied);assertFalse(controller.fillRecognizedText())
            engine.listener.result("重复迟到");assertEquals(1,applied.size)
        }
    }
    @Test fun recognizerErrorPreservesPartialButChangedDraftRejectsExplicitFill() {
        val engine=Engine();val controller=controller(engine);var current=true;var applies=0
        main {
            controller.start(draft(),{current},{_,_->applies++;true},provider=VoiceInputController.Provider.SYSTEM)
            controller.permissionRequest?.let { controller.permissionResult(it,true) };yield()
            engine.listener.ready();engine.listener.partial("未提交部分");engine.listener.failed(8)
            assertTrue(controller.canFillRecognizedText);current=false
            assertFalse(controller.canFillRecognizedText);assertFalse(controller.fillRecognizedText());assertEquals(0,applies)
            assertEquals("未提交部分",controller.transcript);controller.cancel()
        }
    }
}
