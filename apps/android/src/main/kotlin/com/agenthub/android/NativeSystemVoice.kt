package com.agenthub.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/** System speech is a separate selected provider. It may use the system provider's network service.
 * Never feed a saved recording through EXTRA_AUDIO_SOURCE: unsupported providers may silently open the mic.
 */
internal interface NativeSystemVoice {
    interface Listener {
        fun ready()
        fun level(value: Float)
        fun partial(text: String)
        fun ended()
        fun result(text: String)
        fun failed(code: Int)
    }
    fun start(listener: Listener)
    fun finish()
    fun cancel()
}

internal class AndroidSystemVoice(private val context: Context) : NativeSystemVoice {
    private var recognizer: SpeechRecognizer? = null
    private fun main() = check(Looper.myLooper() == Looper.getMainLooper())
    override fun start(listener: NativeSystemVoice.Listener) {
        main(); check(recognizer == null)
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) throw NativeVoiceFailure("当前语言的语音识别暂不可用，请稍后重试。")
        val opened = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = opened
        opened.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.level(((rmsdB + 2) / 12).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.ended()
            override fun onError(error: Int) = listener.failed(error)
            override fun onResults(results: Bundle?) = listener.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            override fun onPartialResults(results: Bundle?) = listener.partial(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        opened.startListening(nativeSystemSpeechIntent())
    }
    override fun finish() { main(); recognizer?.stopListening() }
    override fun cancel() {
        main(); val old = recognizer; recognizer = null
        if (old != null) try { old.cancel() } finally { old.destroy() }
    }
}

/** Only this typed local error is translated. Host/OS exception text is never interpreted as a copy key. */
internal class NativeVoiceFailure(val copyKey: String) : IllegalStateException(copyKey)
