package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Invalidate BEFORE preference/credential/read-visibility/navigation/migration changes. Checks never read disk. */
internal class NativeChatNotificationPresentationFence {
    private val generation = AtomicLong()
    fun invalidate() { generation.incrementAndGet() }
    fun capture(): Lease = Lease(this, generation.get())
    class Lease internal constructor(private val owner: NativeChatNotificationPresentationFence, private val generation: Long) {
        fun current() = owner.generation.get() == generation
    }
}
internal data class NativeChatNotificationMonitorReport(val state: String, val target: NativeChatNoticeTarget,
    val operationId: String? = null, val cursor: String? = null, val unconfirmedEvents: Int = 0,
    val waitingEvents: Int = 0, val unconfirmedOperations: Int = 0, val gap: NativeChatRecoveryGapOffer? = null)

/** One foreground saved-target worker. No background service, token/provider readiness or network failover. */
internal class NativeChatNotificationForegroundMonitor(private val scope: CoroutineScope, private val storage: NativeSessionStorage,
    private val notices: NativeChatNotificationStore, private val journal: NativeChatNotificationForegroundStore,
    private val commands: NativeChatNotificationCommands, private val delivery: NativeChatNotificationForegroundPort,
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val recovery = (commands as? NativeChatNotificationRecoveryCommands)?.let {
        NativeChatNotificationRecoveryController(storage, notices, journal, it, delivery, now)
    }
    private val generation = AtomicLong()
    private var job: Job? = null
    private val reportValue = MutableStateFlow<NativeChatNotificationMonitorReport?>(null)
    val report = reportValue.asStateFlow()
    fun stop() { generation.incrementAndGet(); job?.cancel(); job = null; reportValue.value = null }
    fun start(snapshot: NativeChatNotificationSnapshot, fence: NativeChatNotificationPresentationFence.Lease) {
        stop(); val token = generation.get()
        job = scope.launch {
            while (isActive && generation.get() == token && fence.current()) {
                val value = synchronize(snapshot, fence)
                if (generation.get() == token && fence.current()) reportValue.value = value
                delay(if (value.state.startsWith("mutation-unknown") || value.state == "read-unavailable") 30_000 else 10_000)
            }
        }
    }
    /** Read failures are retryable; any attempted mutation is already durably unknown and never reissued. */
    suspend fun synchronize(snapshot: NativeChatNotificationSnapshot, fence: NativeChatNotificationPresentationFence.Lease): NativeChatNotificationMonitorReport {
        val token = generation.get()
        val current = { token == generation.get() && fence.current() }
        return mutex.withLock {
            try { synchronize(snapshot, current) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { NativeChatNotificationMonitorReport(if (current()) "read-unavailable" else "stale-target-or-generation", snapshot.target) }
        }
    }
    private suspend fun synchronize(snapshot: NativeChatNotificationSnapshot, outerCurrent: () -> Boolean): NativeChatNotificationMonitorReport {
        val configuration = notices.configurationLease()
        val current = { outerCurrent() && configuration() }
        fun result(state: String, operation: String? = null, cursor: String? = null) = NativeChatNotificationMonitorReport(
            if (current()) state else "stale-target-or-generation", snapshot.target, operation, cursor)
        if (!current()) return result("stale-target-or-generation")
        if (recovery == null && withContext(Dispatchers.IO) { NativeChatNotificationRecoveryStore(storage).exists() }) return result("recovery-unavailable")
        recovery?.synchronize(snapshot, current)?.let { return it }
        val preferences = withContext(Dispatchers.IO) { notices.preferences() }
        if (!current()) return result("stale-target-or-generation")
        val enabled = preferences.enabled && snapshot.target.hostId !in preferences.excludedHosts
        val exists = withContext(Dispatchers.IO) { journal.exists() }
        if (!enabled && !exists) { delivery.cancel(snapshot.target); return result("disabled") }
        val identity = NativeChatNotificationIdentity.load(storage)
        var saved = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target) }
        if (!current()) return result("stale-target-or-generation")
        if (!enabled) delivery.cancel(snapshot.target)
        val batch = saved.batch
        if (batch != null && (batch.revision != preferences.revision || batch.authorization != snapshot.authorizationFingerprint)) {
            withContext(Dispatchers.IO) { journal.abandonBatch(identity, snapshot.target) }
            saved = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target) }
        }
        saved.unknown?.let {
            // Same deviceId and captured target only. Host v1 status cannot certify this immutable intent.
            if (it.authorization != snapshot.authorizationFingerprint) { delivery.cancel(snapshot.target); return result("original-credential-recovery-required", it.id, saved.cursor) }
            if (!current()) return result("stale-target-or-generation", it.id)
            try { commands.status(snapshot, identity.deviceId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return result("mutation-unknown-status-unavailable", it.id, saved.cursor) }
            return result(if (current()) "mutation-unknown-status-only" else "stale-target-or-generation", it.id, saved.cursor)
        }
        if (saved.authorization != null && saved.authorization != snapshot.authorizationFingerprint && !saved.revoked) {
            delivery.cancel(snapshot.target)
            return result("original-credential-revocation-required", cursor = saved.cursor)
        }
        if (!current()) return result("stale-target-or-generation")
        val changed = withContext(Dispatchers.IO) { saved.preferences?.let(NativeChatNotificationWire::preferenceHash) != NativeChatNotificationWire.preferenceHash(preferences) }
        if (!current()) return result("stale-target-or-generation")
        if (saved.preferences == null && !enabled) return result("disabled")
        if (enabled && saved.revoked && saved.preferences != null && preferences.generation <= saved.preferences.generation) {
            return result("reenable-preferences-required", cursor = saved.cursor)
        }
        if (changed || enabled && saved.revoked) {
            val kind = if (saved.preferences == null || saved.revoked) "register" else "preferences"
            val operation = withContext(Dispatchers.IO) {
                check(current()); journal.beginMutation(identity, snapshot, kind, preferences, now())
            }
            if (!current()) return result("mutation-unknown-status-only", operation.id)
            val status = try { if (kind == "register") commands.register(snapshot, identity, preferences) else commands.preferences(snapshot, identity.deviceId, preferences) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return result("mutation-unknown-status-only", operation.id, saved.cursor) }
            // Store a direct response only against its original intent; never update a newer cursor or draft.
            try { withContext(NonCancellable + Dispatchers.IO) { journal.confirm(identity, snapshot.target, operation, status) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return result("mutation-unknown-status-only", operation.id, saved.cursor) }
            if (!current()) return result("stale-target-or-generation", operation.id)
            saved = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target) }
        }
        if (!enabled || saved.revoked) {
            if (!current()) return result("stale-target-or-generation")
            // A local off state is not evidence that a prior APNs binding was removed at the relay.
            val status = commands.status(snapshot, identity.deviceId)
            if (!current()) return result("stale-target-or-generation")
            return result(if (status.state == "disabled" && !status.hasBinding) "disabled" else "revocation-remote-pending", cursor = saved.cursor)
        }
        val status = commands.status(snapshot, identity.deviceId)
        if (!current()) return result("stale-target-or-generation")
        if (!status.foreground(preferences.revision)) {
            delivery.cancel(snapshot.target)
            return result(if (status.delivery == "apns") "foreground-feed-unavailable" else "subscription-status-mismatch", cursor = saved.cursor)
        }
        val lease = withContext(Dispatchers.IO) { check(current()); notices.bind(snapshot.target, snapshot.credentialEpoch) }
        if (!current()) return result("stale-target-or-generation")
        val pending = saved.batch ?: run {
            val feed = commands.events(snapshot, identity.deviceId, saved.cursor)
            if (!current()) return result("stale-target-or-generation")
            withContext(Dispatchers.IO) { check(current()); journal.beginBatch(identity, snapshot, preferences, feed, now()) }
        }
        for (id in pending.ids) {
            if (!current()) return result("stale-target-or-generation")
            val event = withContext(Dispatchers.IO) { check(current()); journal.startEvent(identity, snapshot.target, pending, id, now()) } ?: continue
            try {
                val outcome = delivery.receive(event, lease, snapshot.computer, current)
                withContext(NonCancellable + Dispatchers.IO) { journal.finishEvent(identity, snapshot.target, pending, id, outcome, now()) }
            } catch (failure: Exception) {
                // An uncertain ledger/platform call cannot be replayed just because the feed cursor stayed behind.
                withContext(NonCancellable + Dispatchers.IO) { journal.finishEvent(identity, snapshot.target, pending, id, "unknown-interrupted-no-replay", now()) }
                if (failure is CancellationException) throw failure
                throw failure
            }
        }
        if (!current()) return result("stale-target-or-generation")
        delivery.reconcileActive(snapshot.target, pending.activeIds, current)
        withContext(Dispatchers.IO) { journal.completeBatch(identity, snapshot.target, pending, current) }
        val uncertain = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target).outcomes.count { it.state.startsWith("unknown") } }
        if (!current()) return result("stale-target-or-generation")
        return NativeChatNotificationMonitorReport(if (uncertain == 0) "foreground-synchronized" else "foreground-outcome-unknown", snapshot.target,
            cursor = pending.after, unconfirmedEvents = uncertain)
    }
    /** Explicit parent-owned revocation with the original retained credentials. Unknown register is FG02, not proof of absence. */
    suspend fun revoke(snapshot: NativeChatNotificationSnapshot, fence: NativeChatNotificationPresentationFence.Lease): NativeChatNotificationMonitorReport = mutex.withLock {
        delivery.cancel(snapshot.target)
        if (withContext(Dispatchers.IO) { NativeChatNotificationRecoveryStore(storage).exists() })
            return@withLock NativeChatNotificationMonitorReport("v2-explicit-disable-required", snapshot.target)
        val identity = NativeChatNotificationIdentity.load(storage)
        var saved = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target) }
        saved.unknown?.let {
            check(it.authorization == snapshot.authorizationFingerprint) { "Original notification credentials required" }
            if (fence.current()) commands.status(snapshot, identity.deviceId)
            return@withLock NativeChatNotificationMonitorReport("mutation-unknown-status-only", snapshot.target, it.id, saved.cursor)
        }
        require(saved.authorization == snapshot.authorizationFingerprint) { "Original notification credentials required" }
        if (saved.batch != null) { withContext(Dispatchers.IO) { journal.abandonBatch(identity, snapshot.target) }; saved = withContext(Dispatchers.IO) { journal.view(identity, snapshot.target) } }
        if (saved.revoked) return@withLock NativeChatNotificationMonitorReport("disabled", snapshot.target, cursor = saved.cursor)
        check(fence.current())
        val operation = withContext(Dispatchers.IO) { check(fence.current()); journal.beginMutation(identity, snapshot, "unregister", null, now()) }
        if (!fence.current()) return@withLock NativeChatNotificationMonitorReport("mutation-unknown-status-only", snapshot.target, operation.id)
        val status = commands.unregister(snapshot, identity.deviceId)
        withContext(NonCancellable + Dispatchers.IO) { journal.confirm(identity, snapshot.target, operation, status) }
        NativeChatNotificationMonitorReport(if (!fence.current()) "stale-target-or-generation" else if (status.hasBinding) "revocation-remote-pending" else "disabled", snapshot.target, operation.id)
    }
    /** Parent passes the persisted settings revision and one stable UUID per human action. */
    suspend fun explicitDisable(snapshot: NativeChatNotificationSnapshot, revision: String, actionId: String,
        fence: NativeChatNotificationPresentationFence.Lease): NativeChatNotificationMonitorReport = mutex.withLock {
        val configuration = notices.configurationLease(); val current = { fence.current() && configuration() }
        val value = requireNotNull(recovery).explicitDisable(snapshot, revision, actionId, current)
        if (current()) value else value.copy(state = "stale-target-or-generation")
    }
    suspend fun acceptGap(snapshot: NativeChatNotificationSnapshot, offer: NativeChatRecoveryGapOffer, actionId: String,
        fence: NativeChatNotificationPresentationFence.Lease): NativeChatNotificationMonitorReport = mutex.withLock {
        val configuration = notices.configurationLease(); val current = { fence.current() && configuration() }
        val value = requireNotNull(recovery).acceptGap(snapshot, offer, actionId, current)
        if (current()) value else value.copy(state = "stale-target-or-generation")
    }
}
