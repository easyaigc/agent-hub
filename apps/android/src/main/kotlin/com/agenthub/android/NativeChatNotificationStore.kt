package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One authenticated atomic record; no plaintext events, tickets or credential material in Intents. */
internal class NativeChatNotificationStore(private val storage: NativeSessionStorage) {
    companion object {
        const val RECORD = "native-chat-notifications-v1"
        const val TTL_MILLIS = 86_400_000L
        private class Epochs {
            val presentation = java.util.concurrent.atomic.AtomicLong()
            val preferences = java.util.concurrent.atomic.AtomicLong()
            val targets = java.util.concurrent.ConcurrentHashMap<NativeChatNoticeTarget, java.util.concurrent.atomic.AtomicLong>()
        }
        private val presentationEpochs = java.util.concurrent.ConcurrentHashMap<String, Epochs>()
        /** P7 migration calls the same production decoder; this never reads or changes active storage. */
        fun validateEncoded(raw: String) { decode(raw) }
        private fun decode(raw: String): State {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
            val v = JSONObject(raw)
            require(v.keys().asSequence().toSet() == setOf("version", "preferences", "leases", "receipts") && v.get("version") == 1)
            val leases = v.getJSONArray("leases"); val receipts = v.getJSONArray("receipts")
            require(leases.length() <= 256 && receipts.length() <= 2048)
            return State(NativeChatNotificationCodec.preferences(v.getJSONObject("preferences")),
                (0 until leases.length()).map { NativeChatNotificationCodec.lease(leases.getJSONObject(it)) },
                (0 until receipts.length()).map { NativeChatNotificationCodec.receipt(receipts.getJSONObject(it)) }).also { state ->
                require(state.leases.map { it.target }.distinct().size == state.leases.size)
                require(state.receipts.map { it.ticket }.distinct().size == state.receipts.size)
                require(state.receipts.map { it.event.target to it.event.id }.distinct().size == state.receipts.size)
            }
        }
    }
    private data class State(val preferences: NativeChatNotificationPreferences,
        val leases: List<NativeChatNoticeLease>, val receipts: List<NativeChatNoticeReceipt>)
    private val epochs = presentationEpochs.computeIfAbsent(storage.coordinationKey) { Epochs() }
    fun invalidatePresentation(target: NativeChatNoticeTarget? = null) {
        if (target == null) epochs.presentation.incrementAndGet()
        else epochs.targets.computeIfAbsent(target) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
    }
    fun invalidatePreferences() { epochs.preferences.incrementAndGet(); invalidatePresentation() }
    /** Final Main check is in-memory; it must not reload encrypted JSON after a claim. */
    fun presentationLease(target: NativeChatNoticeTarget? = null): () -> Boolean {
        val original = epochs.presentation.get(); val exact = target?.let { epochs.targets.computeIfAbsent(it) { java.util.concurrent.atomic.AtomicLong() } }; val generation = exact?.get()
        return { epochs.presentation.get() == original && (exact == null || exact.get() == generation) }
    }
    fun configurationLease(): () -> Boolean { val original = epochs.preferences.get(); return { epochs.preferences.get() == original } }
    private fun load(): State {
        val raw = storage.read(RECORD) ?: return State(NativeChatNotificationPreferences(), emptyList(), emptyList())
        return decode(raw)
    }
    private fun save(state: State) {
        val raw = JSONObject().put("version", 1)
        .put("preferences", NativeChatNotificationCodec.preferences(state.preferences))
        .put("leases", JSONArray(state.leases.map(NativeChatNotificationCodec::lease)))
        .put("receipts", JSONArray(state.receipts.map(NativeChatNotificationCodec::receipt))).toString()
        validateEncoded(raw); storage.write(RECORD, raw)
    }
    fun preferences(): NativeChatNotificationPreferences = storage.snapshotTransaction { load().preferences }
    fun update(change: (NativeChatNotificationPreferences) -> NativeChatNotificationPreferences): NativeChatNotificationPreferences {
        invalidatePreferences()
        return storage.snapshotTransaction {
        val old = load()
        val requested = change(old.preferences).copy(revision = UUID.randomUUID().toString(), generation = old.preferences.generation + 1)
        val next = NativeChatNotificationCodec.preferences(NativeChatNotificationCodec.preferences(requested))
        // Preference changes invalidate all captured deliveries and taps. Reading data is owned by P7 and untouched.
        save(State(next, emptyList(), old.receipts)); next
    } }
    fun bind(target: NativeChatNoticeTarget, credentialEpoch: String): NativeChatNoticeLease = storage.snapshotTransaction {
        require(chatNoticeId(credentialEpoch))
        val old = load(); require(old.preferences.enabled && target.hostId !in old.preferences.excludedHosts)
        old.leases.singleOrNull { it.target == target && it.credentialEpoch == credentialEpoch && it.preferenceRevision == old.preferences.revision }
            ?: NativeChatNoticeLease(target, credentialEpoch, UUID.randomUUID().toString(), old.preferences.revision).also { lease ->
                invalidatePresentation(target)
                val next = old.leases.filterNot { it.target == target } + lease; require(next.size <= 256)
                save(old.copy(leases = next))
            }
    }
    fun current(lease: NativeChatNoticeLease): Boolean = storage.snapshotTransaction {
        val state = load(); state.preferences.enabled && state.preferences.revision == lease.preferenceRevision &&
            lease.target.hostId !in state.preferences.excludedHosts && lease in state.leases
    }
    fun revoke(target: NativeChatNoticeTarget) { invalidatePresentation(target); storage.snapshotTransaction {
        val old = load(); save(old.copy(leases = old.leases.filterNot { it.target == target }))
    } }
    /** Claim before the OS call. A crash/failure is unknown and never automatically replays the alert. */
    fun claim(event: NativeChatNotificationEvent, lease: NativeChatNoticeLease, now: Long,
        mayPresent: () -> Boolean = { true }): NativeChatNoticeReceipt? = storage.snapshotTransaction { storage.operationTransaction {
        val old = load()
        val occurred = java.time.Instant.parse(event.occurredAt).toEpochMilli()
        if (!current(lease) || event.target != lease.target || !old.preferences.permits(event) ||
            occurred > now + 60_000 || occurred <= now - TTL_MILLIS) return@operationTransaction null
        val live = old.receipts.filter { it.expiresAt > now }
        // Credential/preference rotation cannot make an original event alert again.
        if (live.any { it.event.target == event.target && it.event.id == event.id }) return@operationTransaction null
        if (NativeMigrationNotificationTombstones.contains(storage, event)) return@operationTransaction null
        // P7 uses this same storage root lock. Recheck after OS channel preparation, atomically
        // with the claim; a read that completed in between cannot authorize an old alert.
        if (!mayPresent()) return@operationTransaction null
        require(live.size < 2048) { "Notification receipt capacity reached" }
        NativeChatNoticeReceipt(event, lease, UUID.randomUUID().toString(), occurred + TTL_MILLIS, "claimed").also {
            save(old.copy(receipts = live + it))
        }
    } }
    fun inactiveActionable(target: NativeChatNoticeTarget, activeIds: Set<String>): Set<String> = storage.snapshotTransaction {
        load().receipts.filter { it.event.target == target && it.event.kind in setOf(NativeChatNoticeKind.APPROVAL,
            NativeChatNoticeKind.QUESTION, NativeChatNoticeKind.PLAN, NativeChatNoticeKind.UNCERTAIN, NativeChatNoticeKind.PAUSED) && it.event.id !in activeIds }
            .map { it.event.id }.toSet()
    }
    fun finish(ticket: String, posted: Boolean) = storage.snapshotTransaction {
        val old = load(); save(old.copy(receipts = old.receipts.map {
            if (it.ticket == ticket) it.copy(state = if (posted) "posted" else "unknown") else it
        }))
    }
    fun ticket(ticket: String, now: Long): NativeChatNoticeReceipt? = storage.snapshotTransaction {
        load().receipts.singleOrNull { it.ticket == ticket && !it.consumed && it.expiresAt > now && current(it.lease) }
    }
    fun consume(ticket: String, now: Long, current: () -> Boolean = { true }): NativeChatNoticeReceipt? = storage.snapshotTransaction {
        val receipt = ticket(ticket, now) ?: return@snapshotTransaction null
        val old = load(); if (!current()) return@snapshotTransaction null
        save(old.copy(receipts = old.receipts.map { if (it.ticket == ticket) it.copy(consumed = true) else it })); receipt
    }
}
