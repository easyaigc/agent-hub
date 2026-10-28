package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Immutable saved/retained authorization. Never consults a live credential callback after capture. */
internal class NativeChatNotificationSnapshot(client: MobileClient, credential: String, val credentialEpoch: String) {
    val target = NativeChatNoticeTarget.from(requireNotNull(client.scope))
    private val authorization = credential.also { require(it.length in 32..512 && it.none(Char::isWhitespace) && it.none { c -> c.code < 32 }) }
    private val endpoint = NativeHostReads.baseAddress(requireNotNull(client.endpoint)).toString().trimEnd('/')
    private val paired = client.codexDirect?.let {
        require(NativeChatNoticeTarget.from(it.target) == target)
        PairedCodexConnection(TargetScope("codex", null, null, target.hostId, target.workspaceId, target.runtimeId), it.fingerprint.lowercase(), it.routes.map { r -> r.copy() }, it.systemEndpoints.toList())
    }
    val computer = client.name
    val authorizationFingerprint = NativeChatNotificationWire.hash(listOf("codex", target.hostId, target.workspaceId, target.runtimeId,
        credentialEpoch, paired?.fingerprint.orEmpty(), NativeChatNotificationWire.hash(authorization)).joinToString("\u0000"))
    init { require(!client.isDemo && client.id == target.hostId && chatNoticeId(credentialEpoch)) }
    internal fun configure(connection: HttpURLConnection) {
        paired?.let {
            require(connection is javax.net.ssl.HttpsURLConnection)
            PairedCodexTLS.configure(connection, endpoint, it)
        }
        connection.setRequestProperty("Authorization", "Bearer $authorization")
    }
    internal fun url(operation: NativeChatNotificationOperation, deviceId: String, after: String): java.net.URL {
        NativeChatNotificationWire.device(deviceId); require(after.isEmpty() || chatNoticeHash(after))
        val query = linkedMapOf("hostId" to target.hostId, "target" to NativeChatNotificationCodec.target(target).toString(), "deviceId" to deviceId)
        if (operation == NativeChatNotificationOperation.EVENTS) query["after"] = after
        return URI(endpoint + "/v1/notifications/" + operation.route + "?" + query.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }).toURL()
    }
    internal fun recoveryUrl(call: NativeChatRecoveryCall, deviceId: String, original: NativeChatRecoveryIntent? = null,
        page: NativeChatRecoveryQuery? = null): java.net.URL {
        NativeChatNotificationWire.device(deviceId)
        val fields = linkedMapOf("hostId" to target.hostId, "target" to NativeChatNotificationCodec.target(target).toString(), "deviceId" to deviceId)
        when (call) {
            NativeChatRecoveryCall.CAPABILITIES -> require(original == null && page == null)
            NativeChatRecoveryCall.SUBMIT, NativeChatRecoveryCall.QUERY -> {
                require(original != null && original.target == target && original.deviceId == deviceId && page == null)
                if (call == NativeChatRecoveryCall.QUERY) { fields["epoch"] = original.epoch; fields["operationId"] = original.operationId }
            }
            NativeChatRecoveryCall.FEED -> {
                require(original == null && page != null && page.deviceId == deviceId && page.generation in 1..9_007_199_254_740_991L && page.after in 0..9_007_199_254_740_991L)
                NativeChatNotificationWire.uuid(page.epoch)
                fields["epoch"] = page.epoch; fields["generation"] = page.generation.toString(); fields["after"] = page.after.toString()
                page.through?.let { require(it in page.after..9_007_199_254_740_991L); fields["through"] = it.toString() }
                page.resetId?.let { fields["resetId"] = NativeChatNotificationWire.uuid(it) }
            }
        }
        return URI(endpoint + "/v1/notifications/recovery-v2/" + call.route + "?" + fields.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }).toURL()
    }
    override fun toString() = "NativeChatNotificationSnapshot(redacted)"
}
internal enum class NativeChatNotificationOperation(val route: String, val post: Boolean) {
    REGISTER("register", true), PREFERENCES("preferences", true), UNREGISTER("unregister", true), STATUS("status", false), EVENTS("events", false)
}
/** Five named operations only. No path, method, IPC or shell forwarding surface. */
internal interface NativeChatNotificationCommands {
    suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus
    suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus
    suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus
    suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus
    suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed
}
/** Exactly one HTTP attempt. Unknown mutation recovery is owned by the durable foreground store. */
internal class NativeChatNotificationHttp : NativeChatNotificationCommands,
    NativeChatNotificationRecoveryCommands by NativeChatNotificationRecoveryHttp() {
    companion object { private val executor = Executors.newFixedThreadPool(2) }
    private suspend fun request(snapshot: NativeChatNotificationSnapshot, operation: NativeChatNotificationOperation,
        deviceId: String, body: JSONObject? = null, after: String = ""): JSONObject {
        require(operation.post == (body != null))
        val url = snapshot.url(operation, deviceId, after)
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        return suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()
            val future = executor.submit {
                try {
                    if (!continuation.isActive) return@submit
                    val connection = url.openConnection() as HttpURLConnection; active.set(connection)
                    if (!continuation.isActive) return@submit
                    snapshot.configure(connection)
                    connection.instanceFollowRedirects = false; connection.useCaches = false
                    connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                    connection.requestMethod = if (operation.post) "POST" else "GET"
                    connection.setRequestProperty("Accept", "application/json")
                    if (bytes != null) {
                        connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { if (continuation.isActive) it.write(bytes) }
                    }
                    if (!continuation.isActive) return@submit
                    check(connection.responseCode == 200) { "Notification request unconfirmed" }
                    val raw = connection.inputStream.use { NativeWireReader.read(it, NativeChatNotificationWire.MAX_BYTES) { continuation.isActive } }
                    val value = JSONObject(raw)
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Exception) {
                    // Do not copy remote error bodies, credentials, binding/management tokens into diagnostics.
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Notification request unconfirmed", failure))
                } finally { active.getAndSet(null)?.disconnect() }
            }
            continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
        }
    }
    override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences) = withContext(Dispatchers.IO) {
        NativeChatNotificationWire.status(request(snapshot, NativeChatNotificationOperation.REGISTER, identity.deviceId,
            JSONObject().put("hostId", snapshot.target.hostId).put("registration", NativeChatNotificationWire.registration(identity.deviceId, identity.publicKey, snapshot.target, preferences))), snapshot.target)
    }
    override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences) = withContext(Dispatchers.IO) {
        NativeChatNotificationWire.status(request(snapshot, NativeChatNotificationOperation.PREFERENCES, deviceId,
            JSONObject().put("hostId", snapshot.target.hostId).put("deviceId", NativeChatNotificationWire.device(deviceId))
                .put("target", NativeChatNotificationCodec.target(snapshot.target)).put("preferences", NativeChatNotificationCodec.preferences(preferences))), snapshot.target)
    }
    override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String) = withContext(Dispatchers.IO) {
        NativeChatNotificationWire.status(request(snapshot, NativeChatNotificationOperation.UNREGISTER, deviceId,
            JSONObject().put("hostId", snapshot.target.hostId).put("deviceId", NativeChatNotificationWire.device(deviceId))), snapshot.target)
    }
    override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String) = withContext(Dispatchers.IO) {
        NativeChatNotificationWire.status(request(snapshot, NativeChatNotificationOperation.STATUS, deviceId), snapshot.target)
    }
    override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String) = withContext(Dispatchers.IO) {
        NativeChatNotificationWire.feed(request(snapshot, NativeChatNotificationOperation.EVENTS, deviceId, after = after), snapshot.target, after)
    }
}
