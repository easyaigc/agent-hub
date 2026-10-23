package com.agenthub.android

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

internal enum class CodexConnectionReadBudget(val millis: Int) {
    BACKGROUND_DISCOVERY(2_000), EXPLICIT_CONNECTION(8_000), PEER_DIRECTORY(6_000)
}

private fun connectionReadTimeout(cause: Throwable?=null)=HostReadError(
    "连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。",cause ?: SocketTimeoutException("Connection description deadline exceeded"),true)

/** GET-only connection/discovery budget; queueing and a delayed caller dispatcher count too. */
internal suspend fun <T: Any> codexConnectionRead(budget: CodexConnectionReadBudget,block: suspend ()->T): T {
    currentCoroutineContext().ensureActive()
    val deadline=SystemClock.elapsedRealtime()+budget.millis
    val result=try { withTimeoutOrNull(budget.millis.toLong()) { block() } }
    catch(cancel: CancellationException) { throw cancel }
    catch(failure: Exception) {
        val socketTimeout=failure is SocketTimeoutException || failure is HostReadError && failure.transient &&
            generateSequence(failure.cause) { it.cause }.take(8).any { it is SocketTimeoutException }
        if(socketTimeout)throw connectionReadTimeout(failure)
        throw failure
    }
    currentCoroutineContext().ensureActive()
    if(result==null || SystemClock.elapsedRealtime()>=deadline)throw connectionReadTimeout()
    return result
}
