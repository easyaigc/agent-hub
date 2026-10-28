package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A captured read descriptor. Accepting it is a separate human action, never automatic recovery. */
internal data class NativeChatRecoveryGapOffer(val operationId: String, val epoch: String, val after: Long,
    val floor: Long, val head: Long, val revision: String)

/** Optional Host v2 foreground path. All effects still pass through the required P7/platform port. */
internal class NativeChatNotificationRecoveryController(private val storage: NativeSessionStorage,
    private val notices: NativeChatNotificationStore, private val legacy: NativeChatNotificationForegroundStore,
    private val commands: NativeChatNotificationRecoveryCommands, private val delivery: NativeChatNotificationForegroundPort,
    private val now: () -> Long = System::currentTimeMillis) {
    // Includes separate monitor instances/retained-credential actions. No lock is held by the storage layer across HTTP.
    companion object {
        private val gate = Mutex()
        /** One global settings action can cover several full targets; each target keeps its own stable ID. */
        fun targetActionId(actionId: String, target: NativeChatNoticeTarget): String {
            NativeChatNotificationWire.uuid(actionId)
            return java.util.UUID.nameUUIDFromBytes(listOf("agent-hub.notification-off.v2", actionId,
                target.hostId, target.workspaceId, target.runtimeId).joinToString("\u0000").toByteArray(Charsets.UTF_8)).toString()
        }
    }
    private val journal = NativeChatNotificationRecoveryStore(storage)
    private val legacyUnknown = mutableMapOf<NativeChatNoticeTarget, String?>()
    private suspend fun <T> io(action: () -> T) = withContext(Dispatchers.IO) { action() }
    private fun effective(p: NativeChatNotificationPreferences, target: NativeChatNoticeTarget) = p.copy(enabled = p.enabled && target.hostId !in p.excludedHosts)
    private fun report(snapshot: NativeChatNotificationSnapshot, state: String, view: NativeChatRecoveryLocalView? = null,
        operation: String? = null, gap: NativeChatRecoveryGapOffer? = null) = NativeChatNotificationMonitorReport(state, snapshot.target,
        operation, view?.let { "${it.epoch.orEmpty()}:${it.after}" }, view?.outcomes?.count { it.state.startsWith("unknown") } ?: 0,
        view?.outcomes?.count { it.state == "waiting-host" } ?: 0,
        (view?.operations?.count { it.state in setOf("unknown", "reserved") } ?: 0) + if (legacyUnknown[snapshot.target] != null) 1 else 0, gap)
    private fun uncovered(view: NativeChatRecoveryLocalView, epoch: String) = view.operations.filter {
        it.original.kind != "feed-reset" && it.state in setOf("unknown", "reserved") &&
            it.original.generation > (view.applied?.takeIf { a -> a.original.epoch == epoch }?.original?.generation ?: 0L)
    }
    /** A read response may settle only its own immutable intent, including after cancellation of presentation. */
    private suspend fun query(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity,
        operation: NativeChatRecoveryLocalOperation): Boolean {
        if (operation.authorization != snapshot.authorizationFingerprint) return false
        val receipt = commands.recoveryQuery(snapshot, operation.original) ?: return false
        withContext(NonCancellable + Dispatchers.IO) { journal.settle(identity, snapshot.target, operation.original, receipt) }
        return receipt.stage in setOf("applied", "rejected")
    }
    private suspend fun submit(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity,
        original: NativeChatRecoveryIntent, current: () -> Boolean) {
        if (!current()) return // Still saved as unknown: a later caller can only query.
        val receipt = try { commands.recoverySubmit(snapshot, original) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return }
        withContext(NonCancellable + Dispatchers.IO) { journal.settle(identity, snapshot.target, original, receipt) }
    }
    /** Explicit read-only recovery. Never negotiates a new subscription, posts an intent or processes a feed. */
    suspend fun queryOriginals(snapshot: NativeChatNotificationSnapshot, current: () -> Boolean): NativeChatNotificationMonitorReport = gate.withLock {
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation")
        if (!io { journal.exists() }) return@withLock report(snapshot, "original-query-complete")
        val identity = NativeChatNotificationIdentity.load(storage)
        var view = io { journal.view(identity, snapshot.target) }
        legacyUnknown[snapshot.target] = io { legacy.view(identity, snapshot.target).unknown?.id }
        for (operation in view.operations.filter { it.state in setOf("unknown", "reserved") && it.authorization == snapshot.authorizationFingerprint }) {
            if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
            query(snapshot, identity, operation)
        }
        view = io { journal.view(identity, snapshot.target) }
        report(snapshot, if (view.operations.any { it.state in setOf("unknown", "reserved") } || legacyUnknown[snapshot.target] != null)
            "mutation-unknown-original-query" else "original-query-complete", view)
    }
    /** null permits the unchanged v1 path only before any v2 adoption/attempt. */
    suspend fun synchronize(snapshot: NativeChatNotificationSnapshot, current: () -> Boolean): NativeChatNotificationMonitorReport? = gate.withLock {
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation")
        val desired = effective(io { notices.preferences() }, snapshot.target)
        val exists = io { journal.exists() }
        if (!desired.enabled && !exists) return@withLock null
        val identity = NativeChatNotificationIdentity.load(storage)
        var view = io { journal.view(identity, snapshot.target) }
        val old = io { legacy.view(identity, snapshot.target) }
        legacyUnknown[snapshot.target] = old.unknown?.id
        if (view.operations.isEmpty() && (old.unknown != null || old.batch != null)) return@withLock null
        val capabilities = commands.recoveryCapabilities(snapshot, identity.deviceId)
        if (capabilities == null) return@withLock if (exists) report(snapshot, "recovery-unavailable", view) else null
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
        if (!desired.enabled) delivery.cancel(snapshot.target)
        // No v2 status/revision can certify a v1 unknown mutation. A separate explicit off may fence it.
        old.unknown?.let { if (view.applied == null) return@withLock report(snapshot, "legacy-mutation-unknown", view, it.id) }
        val newest = view.operations.lastOrNull { it.original.kind != "feed-reset" }
        if (newest != null && newest.original.epoch != capabilities.epoch)
            return@withLock report(snapshot, "epoch-recovery-required", view, newest.original.operationId)
        for (operation in uncovered(view, capabilities.epoch)) {
            if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
            if (operation.authorization != snapshot.authorizationFingerprint)
                return@withLock report(snapshot, "original-credential-recovery-required", view, operation.original.operationId)
            if (!query(snapshot, identity, operation)) return@withLock report(snapshot, "mutation-unknown-original-query", view, operation.original.operationId)
        }
        view = io { journal.view(identity, snapshot.target) }
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
        val applied = view.applied
        if (applied != null && applied.authorization != snapshot.authorizationFingerprint) {
            delivery.cancel(snapshot.target); return@withLock report(snapshot, "original-credential-revocation-required", view)
        }
        val latestAttempt = view.operations.lastOrNull { it.original.kind != "feed-reset" }
        val desiredHash = io { NativeChatNotificationWire.preferenceHash(desired) }
        val sameDesired = io { latestAttempt?.original?.preferences?.let(NativeChatNotificationWire::preferenceHash) == desiredHash }
        latestAttempt?.takeIf { sameDesired && it.state == "rejected" }?.let {
            return@withLock report(snapshot, "subscription-rejected", view, it.original.operationId)
        }
        val changed = io { applied?.original?.preferences?.let(NativeChatNotificationWire::preferenceHash) != desiredHash }
        if (changed) {
            if (desired.enabled && applied?.original?.kind == "revoke" && desired.generation <= applied.original.preferences.generation)
                return@withLock report(snapshot, "reenable-preferences-required", view)
            val kind = if (!desired.enabled) "revoke" else if (applied == null || applied.original.kind == "revoke") "register" else "preferences"
            view.pending?.let { io { journal.abandon(identity, snapshot.target, it.id, current) } }
            val original = io { journal.reserve(identity, snapshot, capabilities.epoch, desired, kind, current) }
            submit(snapshot, identity, original, current)
            view = io { journal.view(identity, snapshot.target) }
            val saved = view.operations.single { it.original.operationId == original.operationId }
            if (saved.state != "applied") return@withLock report(snapshot, if (saved.state == "rejected") "subscription-rejected" else "mutation-unknown-original-query", view, original.operationId)
        }
        val source = requireNotNull(view.applied)
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
        // Check the original receipt on restoration; no revision inference or new ID on absence.
        if (!query(snapshot, identity, source)) return@withLock report(snapshot, "mutation-unknown-original-query", view, source.original.operationId)
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
        if (source.original.kind == "revoke" || !desired.enabled) return@withLock report(snapshot, "disabled", view, source.original.operationId)
        for (reset in listOfNotNull(view.operations.lastOrNull { it.original.kind == "feed-reset" && it.original.generation == source.original.generation && it.original.epoch == source.original.epoch })
            .filter { it.state in setOf("unknown", "reserved") }) {
            if (!query(snapshot, identity, reset)) return@withLock report(snapshot, "reset-unknown-original-query", view, reset.original.operationId)
        }
        view = io { journal.view(identity, snapshot.target) }
        val pending = view.pending
        if (pending != null && pending.operationId != source.original.operationId) {
            io { journal.abandon(identity, snapshot.target, pending.id, current) }; view = io { journal.view(identity, snapshot.target) }
        }
        process(snapshot, identity, source.original, view, old, current)
    }
    private fun queryFor(view: NativeChatRecoveryLocalView, source: NativeChatRecoveryIntent): Pair<NativeChatRecoveryQuery, NativeChatRecoverySnapshot?> {
        val reset = view.resetId?.let { id -> requireNotNull(view.operations.single { it.original.operationId == id }.receipt?.snapshot) }
        return if (reset == null) NativeChatRecoveryQuery(source.deviceId, view.epoch ?: source.epoch, source.generation, view.after, view.through) to null
        else NativeChatRecoveryQuery(source.deviceId, source.epoch, source.generation, view.resetAfter, reset.count, reset.id) to reset
    }
    private fun blocked(snapshot: NativeChatNotificationSnapshot, feed: NativeChatRecoveryFeed, view: NativeChatRecoveryLocalView,
        source: NativeChatRecoveryIntent): NativeChatNotificationMonitorReport {
        val offer = if (feed.kind == "reset-required" && feed.reason in setOf("retention-gap", "epoch-mismatch"))
            NativeChatRecoveryGapOffer(source.operationId, view.epoch ?: source.epoch, view.after, requireNotNull(feed.floor), requireNotNull(feed.head), source.preferences.revision) else null
        return report(snapshot, if (offer != null) "feed-gap-acceptance-required" else "feed-${feed.kind}", view, source.operationId, offer)
    }
    private suspend fun process(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, source: NativeChatRecoveryIntent,
        initial: NativeChatRecoveryLocalView, legacy: NativeChatNotificationSubscription, current: () -> Boolean): NativeChatNotificationMonitorReport {
        var view = initial
        val (query, reset) = queryFor(view, source)
        var pending = view.pending
        if (pending == null) {
            val feed = commands.recoveryFeed(snapshot, source, query, reset)
            if (!current()) return report(snapshot, "stale-target-or-generation", view)
            val page = feed.page ?: return blockOrExpired(snapshot, feed, view, source, current)
            pending = io { journal.preparePage(identity, snapshot.target, source, page, now(), current) }
        } else {
            // Refresh only the captured original interval. The durable original page/high-water stays unchanged.
            var after = pending.page.after
            do {
                val q = NativeChatRecoveryQuery(source.deviceId, pending.page.epoch, source.generation, after,
                    if (pending.page.mode == "reset") pending.page.through else pending.page.next, pending.page.resetId)
                val feed = commands.recoveryFeed(snapshot, source, q, reset)
                if (!current()) return report(snapshot, "stale-target-or-generation", view)
                val page = feed.page ?: return blockOrExpired(snapshot, feed, view, source, current)
                io { journal.refresh(identity, snapshot.target, requireNotNull(pending).id, page, current) }
                after = page.next
            } while (after < pending.page.next)
            pending = requireNotNull(io { journal.view(identity, snapshot.target) }.pending)
        }
        val batch = requireNotNull(pending)
        val lease = io { check(current()); notices.bind(snapshot.target, snapshot.credentialEpoch) }
        for (item in batch.page.items) {
            if (!current()) return report(snapshot, "stale-target-or-generation", view)
            val outcome = io { journal.view(identity, snapshot.target).outcomes.single { it.event.id == item.event.id } }
            if (outcome.terminal) continue
            if (outcome.state == "processing") { io { journal.start(identity, snapshot.target, batch.id, item.event.id, now(), current) }; continue }
            val previous = legacy.outcomes.singleOrNull { it.event.id == item.event.id }
            if (previous != null) {
                io { require(NativeChatNotificationWire.eventHash(previous.event) == NativeChatNotificationWire.eventHash(item.event)) }
                if (previous.terminal || previous.state == "processing") {
                    io { journal.result(identity, snapshot.target, batch.id, item.event.id, if (previous.state == "processing") "unknown-interrupted-no-replay" else previous.state, now()) }; continue
                }
            }
            when (batch.decisions.getValue(item.event.id)) {
                "inactive" -> { io { journal.result(identity, snapshot.target, batch.id, item.event.id, "host-inactive", now()) }; continue }
                "stale" -> {
                    val actionable = item.event.kind in setOf(NativeChatNoticeKind.APPROVAL, NativeChatNoticeKind.QUESTION, NativeChatNoticeKind.PLAN, NativeChatNoticeKind.PAUSED, NativeChatNoticeKind.UNCERTAIN)
                    io { journal.result(identity, snapshot.target, batch.id, item.event.id, if (actionable) "waiting-host" else "host-expired", now()) }; continue
                }
            }
            val original = io { journal.start(identity, snapshot.target, batch.id, item.event.id, now(), current) } ?: continue
            try {
                val result = delivery.receive(original.event, lease, snapshot.computer, current)
                withContext(NonCancellable + Dispatchers.IO) { journal.result(identity, snapshot.target, batch.id, item.event.id, result, now()) }
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { journal.result(identity, snapshot.target, batch.id, item.event.id, "unknown-interrupted-no-replay", now()) }
                throw failure
            }
        }
        view = io { journal.view(identity, snapshot.target) }
        if (!current()) return report(snapshot, "stale-target-or-generation", view)
        if (batch.page.items.any { item -> !view.outcomes.single { it.event.id == item.event.id }.terminal })
            return report(snapshot, "waiting-host-confirmation", view)
        delivery.reconcileActive(snapshot.target, batch.activeIds, current)
        io { journal.complete(identity, snapshot.target, batch.id, current) }
        view = io { journal.view(identity, snapshot.target) }
        return report(snapshot, if (view.outcomes.any { it.state.startsWith("unknown") }) "foreground-outcome-unknown" else "foreground-synchronized", view)
    }
    private suspend fun blockOrExpired(snapshot: NativeChatNotificationSnapshot,
        feed: NativeChatRecoveryFeed, view: NativeChatRecoveryLocalView, source: NativeChatRecoveryIntent,
        current: () -> Boolean): NativeChatNotificationMonitorReport {
        if (feed.reason != "snapshot-expired-or-missing") return blocked(snapshot, feed, view, source)
        // Never replay an expired reset. Read the original live cursor to obtain a fresh gap offer.
        val live = commands.recoveryFeed(snapshot, source, NativeChatRecoveryQuery(source.deviceId, view.epoch ?: source.epoch, source.generation, view.after))
        if (!current()) return report(snapshot, "stale-target-or-generation", view)
        // Original reset receipt/cursor/outcomes stay intact until a distinct accepted reset replaces it.
        return if (live.kind == "reset-required") blocked(snapshot, live, view, source) else report(snapshot, "snapshot-expired-recovery-required", view)
    }
    /** A distinct human off action may install a new fence; it never confirms the earlier unknown. */
    suspend fun explicitDisable(snapshot: NativeChatNotificationSnapshot, revision: String, actionId: String,
        current: () -> Boolean): NativeChatNotificationMonitorReport = gate.withLock {
        NativeChatNotificationWire.uuid(actionId)
        val desired = effective(io { notices.preferences() }, snapshot.target)
        check(current() && desired.revision == revision && !desired.enabled)
        delivery.cancel(snapshot.target)
        val identity = NativeChatNotificationIdentity.load(storage); var view = io { journal.view(identity, snapshot.target) }
        legacyUnknown[snapshot.target] = io { legacy.view(identity, snapshot.target).unknown?.id }
        view.operations.singleOrNull { it.original.operationId == actionId }?.let {
            require(it.original.kind == "revoke" && it.original.preferences.revision == revision && it.authorization == snapshot.authorizationFingerprint)
            query(snapshot, identity, it); view = io { journal.view(identity, snapshot.target) }
            return@withLock report(snapshot, if (view.operations.single { op -> op.original.operationId == actionId }.state == "applied") "disabled" else "mutation-unknown-original-query", view, actionId)
        }
        val capabilities = commands.recoveryCapabilities(snapshot, identity.deviceId) ?: return@withLock report(snapshot, "recovery-unavailable", view)
        if (!current()) return@withLock report(snapshot, "stale-target-or-generation", view)
        view.pending?.let { io { journal.abandon(identity, snapshot.target, it.id, current) } }
        val original = io { journal.reserve(identity, snapshot, capabilities.epoch, desired, "revoke", current, explicit = true, operationId = actionId) }
        submit(snapshot, identity, original, current)
        view = io { journal.view(identity, snapshot.target) }
        report(snapshot, if (view.operations.single { it.original.operationId == actionId }.state == "applied") "disabled" else "mutation-unknown-original-query", view, actionId)
    }
    suspend fun acceptGap(snapshot: NativeChatNotificationSnapshot, offer: NativeChatRecoveryGapOffer, actionId: String,
        current: () -> Boolean): NativeChatNotificationMonitorReport = gate.withLock {
        NativeChatNotificationWire.uuid(actionId)
        val identity = NativeChatNotificationIdentity.load(storage); var view = io { journal.view(identity, snapshot.target) }
        legacyUnknown[snapshot.target] = io { legacy.view(identity, snapshot.target).unknown?.id }
        val desired = effective(io { notices.preferences() }, snapshot.target)
        check(current() && desired.enabled && desired.revision == offer.revision)
        view.operations.singleOrNull { it.original.operationId == actionId }?.let {
            require(it.original.kind == "feed-reset" && it.original.gap == NativeChatRecoveryGap(offer.epoch, offer.after, offer.floor, offer.head, actionId) && it.authorization == snapshot.authorizationFingerprint)
            query(snapshot, identity, it); return@withLock report(snapshot, "reset-original-query", io { journal.view(identity, snapshot.target) }, actionId)
        }
        val source = requireNotNull(view.applied)
        require(source.original.operationId == offer.operationId && source.authorization == snapshot.authorizationFingerprint && source.original.kind != "revoke")
        val caps = commands.recoveryCapabilities(snapshot, identity.deviceId) ?: return@withLock report(snapshot, "recovery-unavailable", view)
        require(caps.epoch == source.original.epoch && uncovered(view, caps.epoch).isEmpty())
        require((view.epoch ?: source.original.epoch) == offer.epoch && view.after == offer.after)
        val gap = NativeChatRecoveryGap(offer.epoch, offer.after, offer.floor, offer.head, actionId)
        val original = io { journal.reserve(identity, snapshot, caps.epoch, source.original.preferences, "feed-reset", current, explicit = true, gap = gap, operationId = actionId) }
        submit(snapshot, identity, original, current)
        view = io { journal.view(identity, snapshot.target) }
        report(snapshot, if (view.operations.single { it.original.operationId == actionId }.state == "applied") "reset-snapshot-ready" else "reset-unknown-original-query", view, actionId)
    }
}
