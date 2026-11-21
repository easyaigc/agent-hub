package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Push enrollment uses the same v2 intent/generation/receipt log as foreground recovery.
 * Neither a status match nor a JobScheduler retry ever re-submits an attempted operation.
 */
internal class NativeChatNotificationPushController(private val context: Context, private val storage: NativeSessionStorage,
    private val system: NativeChatNotificationSystem, private val commands: NativeChatNotificationCommands,
    private val recover: NativeChatNotificationRecoveryCommands = NativeChatNotificationRecoveryHttp()) {
    companion object { private val gate = Mutex() }
    private val provider = NativeChatNotificationPushProvider(context)
    private val state = NativeChatNotificationPushState(storage)
    private val notices = NativeChatNotificationStore(storage)
    private val journal = NativeChatNotificationRecoveryStore(storage)
    private val legacy = NativeChatNotificationForegroundStore(storage)
    private suspend fun <T> io(block: () -> T) = withContext(Dispatchers.IO) { block() }
    suspend fun synchronize(snapshot: NativeChatNotificationSnapshot, current: () -> Boolean,
        background: Boolean = false, disableId: String? = null, queryOnly: Boolean = false): NativeChatNotificationMonitorReport? = gate.withLock {
        fun report(value: String, operation: String? = null) = NativeChatNotificationMonitorReport(value, snapshot.target, operation)
        val previous = io { state.row(snapshot.target) }
        val config = try { NativeChatFirebaseConfiguration.current(context) } catch (_: Exception) { return@withLock report("push-invalid-configuration") }
        if (config == null && (previous == null || disableId != null)) return@withLock null
        if (config == null) return@withLock report("push-not-configured")
        if (!current()) return@withLock report("stale-target-or-generation")
        val identity = if (background) NativeChatNotificationIdentity.existing(storage) else NativeChatNotificationIdentity.load(storage)
        val capabilities = try { recover.recoveryPushCapabilities(snapshot, identity.deviceId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return@withLock if (previous == null) null else report("push-capability-read-unavailable") }
        if (capabilities == null || "fcm" !in capabilities.pushProviders) {
            // Old servers continue foreground operation, including recovery of any old intent.
            return@withLock if (previous == null || disableId != null) null else report("push-original-recovery-host-upgrade-required")
        }
        val prefs = io { notices.preferences() }
        val allowed = system.permission().canPost
        val enabled = prefs.enabled && snapshot.target.hostId !in prefs.excludedHosts && allowed && disableId == null
        val desired = prefs.copy(enabled = enabled)
        val old = io { legacy.view(identity, snapshot.target) }
        var view = io { journal.view(identity, snapshot.target) }
        if (view.operations.any { it.original.epoch != capabilities.epoch }) return@withLock if (disableId != null) null else report("epoch-recovery-required")
        val appliedFloor = view.applied?.original?.generation ?: 0L
        val awaited = previous?.optString("operationId")?.let { id -> view.operations.singleOrNull { it.original.operationId == id } }
        val legacyUnfenced = (old.unknown != null || previous?.let { it.optInt("protocol", 1) == 1 && it.optString("stage") == "unknown" } == true) &&
            view.applied?.original?.kind != "revoke"
        if (previous?.optBoolean("waitingEnded") == true && !queryOnly && disableId == null &&
            (legacyUnfenced || awaited != null && (awaited.state in setOf("unknown", "reserved") && awaited.original.generation > appliedFloor ||
                awaited.state == "applied" && previous.optString("stage") == "pending")))
            return@withLock report("push-wait-ended-original-retained", previous.getString("operationId"))
        // Query original v2 IDs with their retained authority before reserving a new generation.
        for (operation in view.operations.filter { it.state in setOf("unknown", "reserved") && (queryOnly || it.original.generation > appliedFloor) }) {
            if (!current()) return@withLock report("stale-target-or-generation")
            if (operation.authorization != snapshot.authorizationFingerprint) return@withLock report("original-credential-recovery-required", operation.original.operationId)
            val receipt = recover.recoveryQuery(snapshot, operation.original)
            if (receipt != null) withContext(NonCancellable + Dispatchers.IO) {
                journal.settle(identity, snapshot.target, operation.original, receipt)
                if (receipt.stage in setOf("applied", "rejected")) state.resumeWaiting(snapshot.target, operation.original.operationId)
            }
        }
        view = io { journal.view(identity, snapshot.target) }
        if (queryOnly) {
            if (old.unknown != null || previous?.let { it.optInt("protocol", 1) == 1 && it.optString("stage") == "unknown" } == true) {
                commands.status(snapshot, identity.deviceId)
                return@withLock report("push-legacy-original-unconfirmed", old.unknown?.id ?: previous?.optString("operationId"))
            }
            val awaitedId = previous?.optString("operationId")
            view.operations.singleOrNull { it.original.operationId == awaitedId && it.state in setOf("applied", "rejected") }?.let {
                io { state.resumeWaiting(snapshot.target, it.original.operationId) }
            }
            val unknown = view.operations.lastOrNull { it.state in setOf("unknown", "reserved") }
            return@withLock report(if (unknown != null) "push-mutation-unknown-original-query" else "original-query-complete", unknown?.original?.operationId)
        }
        if (old.authorization != null && old.authorization != snapshot.authorizationFingerprint && !old.revoked)
            return@withLock report("original-credential-revocation-required")
        val floor = view.applied?.original?.generation ?: 0L
        val unresolved = view.operations.lastOrNull { it.state in setOf("unknown", "reserved") && it.original.generation > floor }
        if (unresolved != null && disableId == null) return@withLock report("push-mutation-unknown-original-query", unresolved.original.operationId)
        val checkpointUnknown = previous?.takeIf { it.optInt("protocol", 1) == 1 && it.optString("stage") == "unknown" }
        // v1 has no receipt API. A separate explicit v2 revoke can fence it, never confirm it.
        val fenced = view.applied?.original?.kind == "revoke"
        if (!NativeChatPushLifecycle.mayMigrate((old.unknown != null || checkpointUnknown != null) && !fenced, false, false, disableId != null)) {
            commands.status(snapshot, identity.deviceId) // Read-only diagnostic, never settles a v1 intent.
            return@withLock report(if (previous?.optBoolean("waitingEnded") == true) "push-wait-ended-original-retained" else "push-legacy-original-unconfirmed",
                old.unknown?.id ?: checkpointUnknown?.getString("operationId"))
        }
        // Finish an already captured foreground batch before switching its delivery mode. No journal deletion.
        if (!NativeChatPushLifecycle.mayMigrate(false, old.batch != null || view.pending != null, false, disableId != null)) return@withLock null
        val sync = io { state.pendingSync() }
        val syncCurrent = { current() && state.pendingSync()?.id == sync?.id }
        if (enabled && !provider.available()) return@withLock report("push-google-services-or-token-unavailable")
        val token = if (!enabled) null else if (background) {
            if (sync == null || sync.configuration != config.fingerprint) return@withLock report("push-no-pending-token")
            sync.token
        } else provider.token() ?: return@withLock report("push-google-services-or-token-unavailable")
        if (!syncCurrent()) return@withLock report("stale-target-or-generation")
        val tokenHash = token?.let(NativeChatNotificationWire::hash).orEmpty()
        if (!background && token != null && previous != null && previous.optString("tokenHash") != tokenHash && sync == null) {
            val queued = io { storage.snapshotTransaction { state.pendingSync() == null && state.enqueueToken(token, config.fingerprint) } }
            if (queued) { NativeChatNotificationPushJobs.schedule(context); return@withLock report("stale-target-or-generation") }
        }
        suspend fun queuePending() {
            if (!background && token != null && io { storage.snapshotTransaction { state.matches(tokenHash, config.fingerprint) && state.pendingSync()?.id == sync?.id && state.enqueueToken(token, config.fingerprint, force = true) } }) NativeChatNotificationPushJobs.schedule(context)
        }
        if (token != null) io { if (syncCurrent()) state.token(tokenHash, config.fingerprint) }
        fun matches(intent: NativeChatRecoveryIntent) = intent.preferences == desired && intent.deviceToken == token &&
            intent.kind == if (enabled) "register" else "revoke"
        val last = view.operations.lastOrNull { it.original.kind != "feed-reset" }
        if (last?.state == "rejected" && matches(last.original)) return@withLock report("subscription-rejected", last.original.operationId)
        var applied = view.applied
        if (applied != null && applied.authorization != snapshot.authorizationFingerprint) return@withLock report("original-credential-revocation-required")
        if (applied == null || !matches(applied.original)) {
            if (background && !enabled && previous == null) return@withLock report("disabled")
            if (enabled && applied?.original?.kind == "revoke" && desired.generation <= applied.original.preferences.generation)
                return@withLock report("reenable-preferences-required")
            val id = disableId ?: java.util.UUID.randomUUID().toString()
            val existing = view.operations.singleOrNull { it.original.operationId == id }
            if (existing != null) return@withLock report("push-mutation-unknown-original-query", id)
            val original = io {
                check(syncCurrent())
                notices.revoke(snapshot.target)
                journal.reserve(identity, snapshot, capabilities.epoch, desired, if (enabled) "register" else "revoke",
                    syncCurrent, explicit = disableId != null, operationId = id, deviceToken = token)
            }
            val checkpoint = org.json.JSONObject().put("protocol", 2).put("stage", "unknown").put("operationId", original.operationId)
                .put("target", NativeChatNotificationCodec.target(snapshot.target)).put("authorization", snapshot.authorizationFingerprint)
                .put("revision", prefs.revision).put("configuration", config.fingerprint).put("tokenHash", tokenHash)
                .put("syncId", sync?.id.orEmpty())
            io { state.save(snapshot.target, checkpoint) }
            if (!syncCurrent()) return@withLock report("push-mutation-unknown-original-query", id)
            val receipt = try { recover.recoverySubmit(snapshot, original) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { queuePending(); return@withLock report("push-mutation-unknown-original-query", id) }
            withContext(NonCancellable + Dispatchers.IO) { journal.settle(identity, snapshot.target, original, receipt) }
            view = io { journal.view(identity, snapshot.target) }
            val saved = view.operations.single { it.original.operationId == id }
            if (saved.state != "applied") return@withLock report(if (saved.state == "rejected") "subscription-rejected" else "push-mutation-unknown-original-query", id)
            applied = view.applied
        }
        val original = requireNotNull(applied).original
        if (!syncCurrent()) return@withLock report("stale-target-or-generation", original.operationId)
        // Receipt proves the exact operation. Status only tells us whether its provider binding is now usable.
        val status = commands.status(snapshot, identity.deviceId)
        val ready = enabled && status.delivery == "fcm" && status.state == "ready" && status.pushProvider == "fcm" &&
            status.revision == desired.revision && status.tokenSha256 == tokenHash && status.bindingId != null && status.peerPublicKey != null
        val revoked = !enabled && status.state == "disabled" && !status.hasBinding
        val saved = io { storage.snapshotTransaction {
            if (!syncCurrent() || notices.preferences() != prefs || system.permission().canPost != allowed) return@snapshotTransaction false
            val row = org.json.JSONObject().put("protocol", 2).put("operationId", original.operationId)
                .put("target", NativeChatNotificationCodec.target(snapshot.target)).put("authorization", snapshot.authorizationFingerprint)
                .put("revision", prefs.revision).put("configuration", config.fingerprint).put("tokenHash", tokenHash)
                .put("stage", if (ready) "ready" else if (revoked) "revoked" else "pending")
            if (ready) row.put("lease", NativeChatNotificationCodec.lease(notices.bind(snapshot.target, snapshot.credentialEpoch)))
                .put("bindingId", status.bindingId).put("peerPublicKey", status.peerPublicKey)
            else notices.revoke(snapshot.target)
            state.save(snapshot.target, row); true
        } }
        if (!saved || enabled && !ready) queuePending()
        report(if (!saved) "stale-target-or-generation" else if (ready) "push-registered" else if (revoked) "disabled" else "push-registration-pending", original.operationId)
    }
    suspend fun endWaiting(target: NativeChatNoticeTarget, operationId: String) = io {
        state.endWaiting(target, operationId)
    }
}
