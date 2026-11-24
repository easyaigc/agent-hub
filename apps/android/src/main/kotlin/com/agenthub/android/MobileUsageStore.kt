package com.agenthub.android

import androidx.compose.runtime.mutableStateMapOf
import com.agenthub.core.UsageReadRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal fun usageTarget(client: MobileClient): JSONObject {
    val scope=client.scope ?: error("电脑运行目标尚未就绪")
    return JSONObject().put("agentId",scope.agentId).put("hostId",scope.hostId).put("workspaceId",scope.workspaceId).put("runtimeId",scope.runtimeId)
}

internal fun usageFailureReason(error: Exception): String = when {
    error is CodexCommandError && error.status!=null -> UsageReadRules.httpFailure(error.status)
    error is EasyWorkRejection -> UsageReadRules.remoteFailure(error.code.orEmpty())
    error is SSLException || error.cause is SSLException -> "connection-unverified"
    error is java.net.SocketTimeoutException || error.cause is java.net.SocketTimeoutException -> "timeout"
    error is IOException || error.cause is IOException -> "offline"
    error is IllegalArgumentException || error.cause is IllegalArgumentException || error is org.json.JSONException || error.cause is org.json.JSONException -> "invalid-data"
    else -> "read-failed"
}

/** Every visible usage consumer shares the same target flight and process-wide two-read budget. */
class MobileUsageStore(
    private val clock: ()->Long = System::currentTimeMillis,
    private val reads: CoroutineScope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate),
    private val read: suspend (MobileClient)->String
) {
    private data class Entry(val raw:String?,val value:JSONObject)
    private class Flight { lateinit var task: Deferred<Unit>; val consumers=AtomicInteger() }
    private val entries=mutableStateMapOf<String,Entry>()
    private val attempts=mutableMapOf<String,Long>()
    private val pending=mutableStateMapOf<String,Flight>()
    // Value identity must survive persistence/reconstruction. Codex account is legacy display metadata;
    // pairing trust and routes remain part of the binding. EasyWork retains its complete account/grant.
    fun identity(client:MobileClient):String {
        fun machine(scope:com.agenthub.core.TargetScope)=JSONArray(listOf(scope.agentId,scope.hostId,scope.workspaceId,scope.runtimeId))
        val direct=client.codexDirect?.let { connection ->
            JSONObject().put("target",machine(connection.target)).put("fingerprint",connection.fingerprint)
                .put("routes",JSONArray(connection.routes.map { route -> JSONObject().put("endpoint",route.endpoint).put("networkKind",route.networkKind) }))
                .put("systemEndpoints",JSONArray(connection.systemEndpoints))
        }
        return easyCanonical(JSONArray().put(client.id).put(client.endpoint ?: JSONObject.NULL)
            .put(client.scope?.let(::machine) ?: JSONObject.NULL).put(direct ?: JSONObject.NULL)
            .put(client.agentConnection?.json() ?: JSONObject.NULL))
    }
    private fun key(client:MobileClient)=identity(client)
    fun value(client:MobileClient):JSONObject {
        val entry=entries[key(client)] ?: return JSONObject(UsageReadRules.unavailable("not-returned"))
        return entry.raw?.let { JSONObject(UsageReadRules.projectResponse(it,usageTarget(client).toString(),clock())) } ?: entry.value
    }
    fun cached(client:MobileClient)=value(client).let { it.optString("status")=="ok" && (it.optBoolean("cached") || it.optBoolean("stale")) }
    // Include queued/lazy reads so every entry observes the same loading interval.
    fun refreshing(client:MobileClient)=pending[key(client)]?.task?.let { !it.isCompleted && !it.isCancelled }==true

    // force means a user requested refresh; it never bypasses the common five-second minimum.
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(client:MobileClient,force:Boolean=false) = withContext(reads.coroutineContext.minusKey(Job)) {
        refreshOnMain(client)
    }
    private suspend fun refreshOnMain(client:MobileClient) {
        if(client.isDemo || client.scope==null || client.migrationEasyWork!=null || client.connectionPhase==ConnectionPhase.FAILED)return
        currentCoroutineContext().ensureActive()
        val key=key(client)
        val existing=pending[key]?.takeUnless { it.task.isCancelled }
        val flight=existing ?: run {
            if(!UsageReadRules.mayRefresh(clock(),attempts[key] ?: Long.MIN_VALUE))return
            val created=Flight()
            created.task=reads.async(start=CoroutineStart.LAZY) {
                gate.withPermit {
                    ensureActive()
                    if(created.consumers.get()==0 || pending[key]!==created)return@withPermit
                    attempts[key]=clock()
                    val projected=try {
                        val raw=read(client)
                        ensureActive()
                        val value=JSONObject(UsageReadRules.projectResponse(raw,usageTarget(client).toString(),clock()))
                        Entry(if(value.optString("status")=="ok")raw else null,value)
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(failure:Exception) { Entry(null,JSONObject(UsageReadRules.unavailable(usageFailureReason(failure)))) }
                    ensureActive()
                    if(pending[key]!==created)return@withPermit
                    entries[key]=if(projected.value.optString("status")=="ok")projected else {
                        // Current transports cannot attest a source on failure. Do not infer it from endpoint/grant.
                        Entry(null,JSONObject(UsageReadRules.failed(entries[key]?.value?.toString(),projected.value.optString("reason"),null)))
                    }
                    if(entries.size>64)entries.keys.firstOrNull { it!=key && pending[it]==null }?.let { entries.remove(it);attempts.remove(it) }
                }
            }
            pending[key]=created
            created.task.invokeOnCompletion { reads.launch { if(pending[key]===created)pending.remove(key) } }
            created
        }
        // Cancellation is registered synchronously, before a queued flight can acquire a slot.
        suspendCancellableCoroutine<Unit> { continuation ->
            flight.consumers.incrementAndGet()
            val released=AtomicBoolean(false)
            fun release() {
                if(released.compareAndSet(false,true) && flight.consumers.decrementAndGet()==0 && !flight.task.isCompleted)flight.task.cancel()
            }
            val handle=flight.task.invokeOnCompletion { failure ->
                release()
                if(failure is CancellationException)continuation.cancel(failure)
                else if(continuation.isActive)continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { handle.dispose();release() }
            flight.task.start()
        }
    }
    suspend fun poll(client:MobileClient) { while(currentCoroutineContext().isActive){refresh(client);delay(30000)} }
    companion object { private val gate=Semaphore(2) }
}
