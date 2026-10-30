package com.agenthub.android

import kotlinx.coroutines.*

internal enum class SavedVoiceAction(val busyKey: String) {
    PROBE("正在检查离线模型…"),INSTALL("正在下载并校验离线模型…"),TRANSCRIBE("正在识别原录音…");
    fun failureKey(timeout: Boolean)=when(this) {
        PROBE->if(timeout)"离线模型检查超时，请重新检查。"else"离线模型检查未完成，请重新检查。"
        INSTALL->if(timeout)"离线模型安装超时，请检查模型状态后重试。"else"离线模型安装未完成，请检查模型状态后重试。"
        TRANSCRIBE->if(timeout)"本机原录音识别超时，原录音和已有结果已保留。"else"本机原录音识别未完成，录音和已有结果已保留。"
    }
}
internal sealed interface SavedVoiceAttempt<out T> {
    data class Complete<T>(val value: T): SavedVoiceAttempt<T>
    data class Failed(val error: Exception,val timedOut: Boolean): SavedVoiceAttempt<Nothing>
}
/** A nested operation budget is a recoverable failure only while its original caller is active.
 * Parent cancellation and late uncooperative returns must not become success/failure UI updates. */
internal suspend fun <T> attemptSavedVoiceAction(work: suspend ()->T): SavedVoiceAttempt<T> = try {
    currentCoroutineContext().ensureActive()
    val value=work();currentCoroutineContext().ensureActive();SavedVoiceAttempt.Complete(value)
}catch(timeout: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive();SavedVoiceAttempt.Failed(timeout,true)
}catch(cancel: CancellationException) {throw cancel}
catch(error: Exception) {
    currentCoroutineContext().ensureActive();SavedVoiceAttempt.Failed(error,false)
}
