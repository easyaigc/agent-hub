package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Ordinary EasyWork notification adapter only. No Task Hub/F67 or execution command is exposed. */
internal class NativeEasyWorkNotificationRuntime(private val context: Context, private val storage: NativeSessionStorage) {
    companion object { private val gate = Mutex() }
    private val store = NativeEasyWorkNotificationStore(storage)
    private val preferences = NativeChatNotificationStore(storage)
    private val push = NativeChatNotificationPushState(storage)
    private val provider = NativeChatNotificationPushProvider(context)
    private val platform = NativeChatNotificationPlatform(context, NativeL10n(context, "system"), NativeChatNotificationSoundLibrary(context, storage))
    suspend fun synchronize(client: MobileClient, adapter: AgentTaskNotifications, background: Boolean, current: () -> Boolean): String = gate.withLock {
        val scope = requireNotNull(client.scope); require(scope.agentId == "easywork")
        val prefs = withContext(Dispatchers.IO) { preferences.preferences() }
        val permission = platform.permission().canPost
        val enabled = prefs.enabled && scope.hostId !in prefs.excludedHosts && permission
        val old = withContext(Dispatchers.IO) { store.row(scope) }
        if (!enabled && (old == null || old.optString("phase") == "revoked")) return@withLock "disabled"
        val configured = try { NativeChatFirebaseConfiguration.current(context) }
            catch (_: Exception) { if (enabled) return@withLock "push-invalid-configuration" else null }
        val configuration = configured?.fingerprint ?: if (!enabled) requireNotNull(old).getString("configuration") else return@withLock "push-not-configured"
        val authority = withContext(Dispatchers.IO) { NativeEasyWorkNoticeAuthority.capture(storage, client) }
        if (!enabled) withContext(Dispatchers.Main.immediate) { platform.cancelEasyWork(authority.scope) }
        val desired = NativeTaskNotificationCodec.preferences(NativeChatNotificationCodec.preferences(prefs.copy(enabled = enabled)))
        if (enabled && !adapter.supportsTaskPushRegistration(client)) return@withLock "push-provider-unsupported"
        val identity = if (background) NativeChatNotificationIdentity.existing(storage) else NativeChatNotificationIdentity.load(storage)
        val pending = withContext(Dispatchers.IO) { push.pendingSync() }
        val token = if (!enabled) null else if (background) pending?.takeIf { it.configuration == configuration }?.token
            else provider.token()
        if (enabled && (!provider.available() || token == null)) return@withLock "push-google-services-or-token-unavailable"
        val tokenHash = token?.let(NativeChatNotificationWire::hash).orEmpty()
        if (!background && token != null && old != null && old.optString("tokenHash") != tokenHash && pending == null &&
            withContext(Dispatchers.IO) { storage.snapshotTransaction { push.pendingSync() == null && push.enqueueToken(token, configuration) } }) {
            NativeChatNotificationPushJobs.schedule(context); return@withLock "stale-target-or-generation"
        }
        suspend fun queuePending() {
            if (!background && token != null && withContext(Dispatchers.IO) { storage.snapshotTransaction { push.matches(tokenHash, configuration) && push.pendingSync()?.id == pending?.id && push.enqueueToken(token, configuration, force = true) } }) NativeChatNotificationPushJobs.schedule(context)
        }
        val exact = { current() && authority.current(storage) && preferences.preferences() == prefs &&
            push.pendingSync()?.id == pending?.id && platform.permission().canPost == permission }
        if (!withContext(Dispatchers.IO) { exact() }) return@withLock "stale-target-or-generation"
        if (old?.optString("phase") == "unknown" && (enabled || old.optJSONObject("intent")?.optString("kind") != "register")) {
            if (old.optBoolean("waitingEnded")) return@withLock "easywork-wait-ended-original-retained"
            adapter.taskNotificationStatus(client, identity.deviceId) // Diagnostic only; v1 has no immutable operation receipt.
            return@withLock "easywork-original-unconfirmed-no-replay"
        }
        val same = old != null && old.optString("revision") == prefs.revision && old.optString("tokenHash") == tokenHash &&
            old.optString("credentialEpoch") == authority.epoch && old.optString("configuration") == configuration
        var status: NativeTaskNotificationStatus
        if (same && old?.optString("phase") in setOf("ready", "acknowledged", "revoking")) {
            status = adapter.taskNotificationStatus(client, identity.deviceId)
        } else {
            val operation = UUID.randomUUID().toString()
            val registration = NativeTaskNotificationRegistration(identity.deviceId, identity.publicKey, desired,
                token?.let { NativeTaskPushRegistration(it, configuration) })
            val intent = JSONObject().put("operationId", operation).put("kind", if (enabled) "register" else "preferences-off")
                .put("deviceId", identity.deviceId).put("preferences", desired.json()).put("tokenHash", tokenHash)
                .put("publicKey", identity.publicKey).put("deviceToken", token)
            val row = JSONObject().put("target", CodexCommandCodec.target(authority.scope)).put("clientId", client.id)
                .put("credentialRef", requireNotNull(client.agentConnection).credentialRef).put("credentialEpoch", authority.epoch)
                .put("configuration", configuration).put("tokenHash", tokenHash).put("revision", prefs.revision)
                .put("phase", "unknown").put("intent", intent).put("lease", UUID.randomUUID().toString())
            withContext(Dispatchers.IO) { storage.snapshotTransaction { check(exact()); NativeChatNotificationIdentity.bind(storage, identity)
                if (token != null) push.token(tokenHash, configuration); store.save(authority.scope, row) } }
            status = try {
                if (enabled) adapter.registerTaskNotifications(client, registration)
                else adapter.updateTaskNotificationPreferences(client, identity.deviceId, desired)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { queuePending(); return@withLock "easywork-original-unconfirmed-no-replay" }
            withContext(NonCancellable + Dispatchers.IO) { store.save(authority.scope, row.put("phase", "acknowledged").put("ack", JSONObject(status.raw))) }
        }
        if (!withContext(Dispatchers.IO) { exact() }) return@withLock "stale-target-or-generation"
        val raw = JSONObject(status.raw)
        if (!enabled) {
            if (status.state != "disabled") return@withLock "push-revocation-pending"
            val row = requireNotNull(withContext(Dispatchers.IO) { store.row(authority.scope) })
            if (raw.has("bindingId") && row.optString("phase") != "revoking") {
                row.put("intent", JSONObject().put("operationId", UUID.randomUUID().toString()).put("kind", "unregister").put("deviceId", identity.deviceId)).put("phase", "unknown")
                withContext(Dispatchers.IO) { check(exact()); store.save(authority.scope, row) }
                try { adapter.unregisterTaskNotifications(client, identity.deviceId) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { return@withLock "easywork-original-unconfirmed-no-replay" }
                withContext(NonCancellable + Dispatchers.IO) { store.save(authority.scope, row.put("phase", "revoking")) }
                return@withLock "push-revocation-pending"
            }
            withContext(Dispatchers.IO) { check(exact()); store.save(authority.scope, row.put("phase", if (raw.has("bindingId")) "revoking" else "revoked")) }
            return@withLock if (raw.has("bindingId")) "push-revocation-pending" else "disabled"
        }
        if (status.state != "ready" || status.delivery != "fcm" || raw.optString("pushProvider") != "fcm" ||
            raw.optString("deviceTokenSha256") != tokenHash || raw.optString("revision") != prefs.revision) { queuePending(); return@withLock "push-registration-pending" }
        NativeChatNotificationWire.key(raw.getString("publicKey")).fill(0)
        withContext(Dispatchers.IO) { storage.snapshotTransaction {
            check(exact()); val row = requireNotNull(store.row(authority.scope))
            store.save(authority.scope, row.put("phase", "ready").put("bindingId", raw.getString("bindingId")).put("peerPublicKey", raw.getString("publicKey")))
        } }
        "push-registered"
    }
}
