package com.agenthub.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class GatewayFailure(val code: String, val message: String, val status: Int?)
class HistoryReply(val targetKey: String, val json: String?, val failure: GatewayFailure?)
interface MobileCancellation { fun cancel() }
interface MobileHistoryGateway {
    fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation
}

/** Platform adapters own networking. The shared boundary owns cancellation and one delivery. */
object HistoryRequests {
    fun start(gateway: MobileHistoryGateway, targetKey: String, threadId: String, cursor: String?,
        completion: (HistoryReply) -> Unit): MobileCancellation {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var reply: HistoryReply? = null
        val job = scope.launch {
            try { reply = suspendCancellableCoroutine { continuation ->
                val delivered = Mutex()
                val request = gateway.read(targetKey, threadId, cursor) { received ->
                    val result = if (received.targetKey == targetKey) received else HistoryReply(targetKey, null,
                        GatewayFailure("wrong-target", "Response belongs to another target", null))
                    if (delivered.tryLock()) continuation.resumeWith(Result.success(result))
                }
                continuation.invokeOnCancellation { request.cancel() }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { reply = HistoryReply(targetKey, null, GatewayFailure("transport", failure.message ?: "History failed", null)) }
        }
        job.invokeOnCompletion { cause ->
            completion((if (cause is CancellationException) null else reply) ?: HistoryReply(targetKey, null, GatewayFailure(
                if (cause is CancellationException) "cancelled" else "transport", cause?.message ?: "Missing history result", null)))
            scope.cancel()
        }
        return object : MobileCancellation { override fun cancel() { job.cancel() } }
    }
}
