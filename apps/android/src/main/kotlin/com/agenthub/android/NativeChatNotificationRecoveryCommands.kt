package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class NativeChatRecoveryCall(val route: String, val post: Boolean) {
    CAPABILITIES("capabilities", false), SUBMIT("operations", true), QUERY("operations", false), FEED("feed", false)
}
internal interface NativeChatNotificationRecoveryCommands {
    suspend fun recoveryCapabilities(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatRecoveryCapabilities?
    suspend fun recoverySubmit(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt
    suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt?
    suspend fun recoveryFeed(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery,
        reset: NativeChatRecoverySnapshot? = null): NativeChatRecoveryFeed
}
/** Fixed v2 operations, single HTTP attempt; original-ID recovery is solely in the durable controller. */
internal class NativeChatNotificationRecoveryHttp : NativeChatNotificationRecoveryCommands {
    companion object { private val executor = Executors.newFixedThreadPool(2) }
    private suspend fun request(snapshot: NativeChatNotificationSnapshot, call: NativeChatRecoveryCall, deviceId: String,
        original: NativeChatRecoveryIntent? = null, query: NativeChatRecoveryQuery? = null): JSONObject? {
        val url = snapshot.recoveryUrl(call, deviceId, original, query)
        val bytes = if (call.post) JSONObject().put("hostId", snapshot.target.hostId).put("intent", requireNotNull(original).json()).toString().toByteArray(Charsets.UTF_8) else null
        return suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()
            val future = NativeMigrationIoWork.submit(executor,continuation.context) {
                try {
                    if (!continuation.isActive) return@submit
                    val connection = url.openConnection() as HttpURLConnection; active.set(connection)
                    if (!continuation.isActive) return@submit
                    snapshot.configure(connection)
                    connection.instanceFollowRedirects = false; connection.useCaches = false
                    connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                    connection.requestMethod = if (call.post) "POST" else "GET"; connection.setRequestProperty("Accept", "application/json")
                    if (bytes != null) {
                        connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { if (continuation.isActive) it.write(bytes) }
                    }
                    if (!continuation.isActive) return@submit
                    val code = connection.responseCode
                    if (call == NativeChatRecoveryCall.CAPABILITIES && code == 404) { if (continuation.isActive) continuation.resume(null); return@submit }
                    check(code == 200) { "Notification recovery request unconfirmed" }
                    val maximum = if (call == NativeChatRecoveryCall.FEED) NativeChatRecoveryWire.MAX_PAGE_BYTES else 512 * 1024
                    val value = connection.inputStream.use { JSONObject(NativeWireReader.read(it, maximum) { continuation.isActive }) }
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Notification recovery request unconfirmed", failure))
                } finally { active.getAndSet(null)?.disconnect() }
            }
            continuation.invokeOnCancellation { NativeMigrationIoWork.cancelHttp(continuation.context,active,future) }
        }
    }
    override suspend fun recoveryCapabilities(snapshot: NativeChatNotificationSnapshot, deviceId: String) = withContext(Dispatchers.IO) {
        request(snapshot, NativeChatRecoveryCall.CAPABILITIES, deviceId)?.let { NativeChatRecoveryWire.capabilities(it, snapshot.target) }
    }
    override suspend fun recoverySubmit(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent) = withContext(Dispatchers.IO) {
        NativeChatRecoveryWire.receipt(requireNotNull(request(snapshot, NativeChatRecoveryCall.SUBMIT, original.deviceId, original)), original)
    }
    override suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent) = withContext(Dispatchers.IO) {
        NativeChatRecoveryWire.query(requireNotNull(request(snapshot, NativeChatRecoveryCall.QUERY, original.deviceId, original)), original)
    }
    override suspend fun recoveryFeed(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery,
        reset: NativeChatRecoverySnapshot?) = withContext(Dispatchers.IO) {
        require(query.deviceId == original.deviceId && query.generation == original.generation && original.target == snapshot.target)
        NativeChatRecoveryWire.feed(requireNotNull(request(snapshot, NativeChatRecoveryCall.FEED, query.deviceId, query = query)), query, original, reset)
    }
}
