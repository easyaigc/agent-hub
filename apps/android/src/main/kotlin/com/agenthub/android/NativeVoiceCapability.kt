package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.Locale

internal data class NativeVoiceCapability(val route: Route,val language: Language,val locale: String) {
    enum class Route { ON_DEVICE, SYSTEM_SERVICE, UNAVAILABLE, UNCONFIRMED }
    enum class Language { INSTALLED, DOWNLOAD_REQUIRED, ONLINE, UNSUPPORTED, UNCONFIRMED }
    val mayUseNetwork get()=route in listOf(Route.SYSTEM_SERVICE,Route.UNCONFIRMED)
    val canTranscribeSavedRecording get()=false
    companion object {
        fun unconfirmed()=NativeVoiceCapability(Route.UNCONFIRMED,Language.UNCONFIRMED,Locale.getDefault().toLanguageTag())
        fun language(requested: String,onDevice: Boolean,installed: List<String>,download: List<String>,online: List<String>): Language {
            // A base-language match does not prove this locale/region is installed.
            fun matches(values: List<String>)=values.any { it.equals(requested,true) }
            return when { matches(installed)->Language.INSTALLED;matches(download)->Language.DOWNLOAD_REQUIRED
                !onDevice && matches(online)->Language.ONLINE;else->Language.UNSUPPORTED }
        }
    }
}

internal fun nativeSystemSpeechIntent()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)

/** Opens a capability query only; never starts listening, asks permission or downloads a model. */
internal suspend fun inspectNativeSystemVoice(context: Context): NativeVoiceCapability = withContext(Dispatchers.Main.immediate) {
    val locale=Locale.getDefault().toLanguageTag()
    val onDevice=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available=onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    val route=when { onDevice->NativeVoiceCapability.Route.ON_DEVICE;available->NativeVoiceCapability.Route.SYSTEM_SERVICE
        Build.VERSION.SDK_INT>=30->NativeVoiceCapability.Route.UNCONFIRMED;else->NativeVoiceCapability.Route.UNAVAILABLE }
    val initial=NativeVoiceCapability(route,NativeVoiceCapability.Language.UNCONFIRMED,locale)
    if(!available || Build.VERSION.SDK_INT<33)return@withContext initial
    val engine=if(onDevice)SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
    try {
        engine.setRecognitionListener(object: RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {} ;override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(value: Float) {} ;override fun onBufferReceived(value: ByteArray?) {}
            override fun onEndOfSpeech() {} ;override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {} ;override fun onPartialResults(results: Bundle?) {}
            override fun onEvent(eventType: Int,params: Bundle?) {}
        })
        withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { continuation ->
                engine.checkRecognitionSupport(nativeSystemSpeechIntent(),context.mainExecutor,object: RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if(continuation.isActive)continuation.resume(initial.copy(language=NativeVoiceCapability.language(locale,onDevice,
                            support.installedOnDeviceLanguages,support.supportedOnDeviceLanguages+support.pendingOnDeviceLanguages,support.onlineLanguages)))
                    }
                    override fun onError(error: Int) { if(continuation.isActive)continuation.resume(initial) }
                })
            }
        } ?: initial
    }finally { engine.destroy() }
}
