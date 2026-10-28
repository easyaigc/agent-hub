package com.agenthub.android

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NativeChatRecoveryLocalOperation(val original: NativeChatRecoveryIntent, val authorization: String,
    val state: String, val receipt: NativeChatRecoveryReceipt?)
internal class NativeChatRecoveryPending(val id: String, val operationId: String, val page: NativeChatRecoveryPage, val decisions: Map<String, String>, val activeIds: Set<String>)
internal data class NativeChatRecoveryOutcome(val original: String, val fingerprint: String, val event: NativeChatNotificationEvent,
    val state: String, val at: Long) { val terminal get() = state !in setOf("pending", "waiting-host", "processing") }
internal class NativeChatRecoveryLocalView(val operations: List<NativeChatRecoveryLocalOperation>, val epoch: String?, val after: Long,
    val through: Long?, val resetId: String?, val resetAfter: Long, val pending: NativeChatRecoveryPending?,
    val outcomes: List<NativeChatRecoveryOutcome>, val gaps: List<JSONObject>) {
    val applied get() = operations.filter { it.state == "applied" && it.original.kind != "feed-reset" }.maxByOrNull { it.original.generation }
}

/** IO-only original operation/event journal. It does not own unread rules or portable identities. */
internal class NativeChatNotificationRecoveryStore(private val storage: NativeSessionStorage) {
    companion object {
        const val RECORD = "native-chat-notification-recovery-v2"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val results = setOf("pending", "waiting-host", "processing", "suppressed", "system-unavailable", "submitted-to-system", "unknown-no-replay",
            "unknown-interrupted-no-replay", "stale-target-or-generation", "expired-or-future", "duplicate-expired-or-read", "host-inactive", "host-expired")
        fun validateEncoded(raw: String) { decode(raw) }
        private fun optional(raw: JSONObject, key: String) = if (raw.get(key) == JSONObject.NULL) null else NativeChatNotificationWire.text(raw, key)
        private fun number(raw: JSONObject, key: String) = NativeChatNotificationWire.long(raw, key)
        private fun operation(raw: JSONObject): NativeChatRecoveryLocalOperation {
            NativeChatNotificationWire.keys(raw, setOf("original", "authorization", "state", "receipt"))
            val original = NativeChatRecoveryIntent.parse(raw.getJSONObject("original")); val authorization = NativeChatNotificationWire.text(raw, "authorization")
            require(chatNoticeHash(authorization)); val state = NativeChatNotificationWire.text(raw, "state"); require(state in setOf("unknown", "reserved", "applied", "rejected"))
            val receipt = if (raw.get("receipt") == JSONObject.NULL) null else NativeChatRecoveryWire.receipt(raw.getJSONObject("receipt"), original)
            require(if (state == "unknown") receipt == null else receipt?.stage == state)
            return NativeChatRecoveryLocalOperation(original, authorization, state, receipt)
        }
        private fun outcome(raw: JSONObject, target: NativeChatNoticeTarget): NativeChatRecoveryOutcome {
            NativeChatNotificationWire.keys(raw, setOf("original", "fingerprint", "state", "at"))
            val event = raw.getJSONObject("original"); val fingerprint = NativeChatNotificationWire.text(raw, "fingerprint"); val decoded = NativeChatNotificationCodec.event(event)
            val state = NativeChatNotificationWire.text(raw, "state")
            require(decoded.target == target && state in results && chatNoticeHash(fingerprint) && fingerprint == NativeChatRecoveryCanonical.hash(event))
            return NativeChatRecoveryOutcome(NativeChatRecoveryCanonical.value(event), fingerprint, decoded, state, number(raw, "at"))
        }
        private fun view(row: JSONObject): NativeChatRecoveryLocalView {
            val target = NativeChatNotificationCodec.target(row.getJSONObject("target")); val rawOps = row.getJSONArray("operations"); val rawOutcomes = row.getJSONArray("outcomes")
            require(rawOps.length() <= 256 && rawOutcomes.length() <= 2048)
            val operations = (0 until rawOps.length()).map { operation(rawOps.getJSONObject(it)) }
            require(operations.all { it.original.target == target } && operations.map { it.original.operationId }.distinct().size == operations.size)
            val outcomes = (0 until rawOutcomes.length()).map { outcome(rawOutcomes.getJSONObject(it), target) }
            require(outcomes.map { it.event.id }.distinct().size == outcomes.size)
            val epoch = optional(row, "epoch")?.let(NativeChatNotificationWire::uuid); val after = number(row, "after")
            val through = if (row.get("through") == JSONObject.NULL) null else number(row, "through").also { require(it >= after) }
            val resetId = optional(row, "resetId")?.let(NativeChatNotificationWire::uuid); val resetAfter = number(row, "resetAfter")
            val lastResetId = optional(row, "lastResetId")?.let(NativeChatNotificationWire::uuid)
            val sequence = row.getJSONArray("resetSequence"); require(sequence.length() <= 2048)
            val sequenceIds = (0 until sequence.length()).map { (sequence.get(it) as? String ?: error("Invalid reset original")).also { id -> require(chatNoticeHash(id)) } }
            require(sequenceIds.distinct().size == sequenceIds.size && sequenceIds.all { id -> outcomes.any { it.event.id == id } })
            if (epoch == null) require(after == 0L && through == null)
            val pending = if (row.get("batch") == JSONObject.NULL) null else row.getJSONObject("batch").let { batch ->
                NativeChatNotificationWire.keys(batch, setOf("id", "operationId", "page", "decisions", "activeIds"))
                val id = NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(batch, "id")); val opId = NativeChatNotificationWire.text(batch, "operationId")
                val source = operations.single { it.original.operationId == opId && it.state == "applied" && it.original.kind in setOf("register", "preferences") }
                val page = batch.getJSONObject("page"); val snapshotId = NativeChatNotificationWire.optional(page, "resetId")
                val snapshot = snapshotId?.let { sid -> operations.single { it.original.operationId == sid && it.state == "applied" }.receipt?.snapshot }
                val query = NativeChatRecoveryQuery(source.original.deviceId, NativeChatNotificationWire.text(page, "epoch"), number(page, "generation"), number(page, "after"), number(page, "through"), snapshotId)
                val parsed = requireNotNull(NativeChatRecoveryWire.feed(page, query, source.original, snapshot).page)
                require(if (parsed.mode == "reset") resetId == parsed.resetId && resetAfter == parsed.after else epoch == parsed.epoch && after == parsed.after)
                val decisions = batch.getJSONObject("decisions"); NativeChatNotificationWire.keys(decisions, parsed.items.map { it.event.id }.toSet())
                val latest = parsed.items.associate { item -> item.event.id to NativeChatNotificationWire.text(decisions, item.event.id).also { require(it in setOf("eligible", "inactive", "stale", "filtered")) } }
                require(parsed.items.all { item -> outcomes.any { it.event.id == item.event.id && it.fingerprint == item.fingerprint } })
                val ids = batch.getJSONArray("activeIds"); require(ids.length() <= 2048)
                val active = (0 until ids.length()).map { (ids.get(it) as? String ?: error("Invalid active ID")).also { id -> require(chatNoticeHash(id)) } }
                require(active.distinct().size == active.size)
                NativeChatRecoveryPending(id, opId, parsed, latest, active.toSet())
            }
            val gaps = row.getJSONArray("gaps"); require(gaps.length() <= 64)
            val parsedGaps = (0 until gaps.length()).map { gaps.getJSONObject(it).also { g ->
                NativeChatNotificationWire.keys(g, setOf("epoch", "after", "floor", "head", "acceptanceId", "operationId"))
                NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(g, "epoch")); NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(g, "acceptanceId")); NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(g, "operationId"))
                require(number(g, "floor") >= 1 && number(g, "head") >= number(g, "floor") - 1); number(g, "after")
            } }
            if (resetId != null) require(operations.any { it.original.operationId == resetId && it.state == "applied" && it.receipt?.snapshot?.count?.let { count -> resetAfter <= count } == true })
            if (lastResetId != null) require(operations.any { it.original.operationId == lastResetId && it.state == "applied" && it.receipt?.snapshot != null })
            require(sequenceIds.size.toLong() == if (pending?.page?.mode == "reset") pending.page.next else if (resetId != null) resetAfter else 0L)
            return NativeChatRecoveryLocalView(operations, epoch, after, through, resetId, resetAfter, pending, outcomes, parsedGaps)
        }
        private fun decode(raw: String): JSONObject {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val state = JSONObject(raw); NativeChatNotificationWire.keys(state, setOf("version", "deviceId", "identity", "targets"))
            require(number(state, "version") == 2L); val device = NativeChatNotificationWire.device(NativeChatNotificationWire.text(state, "deviceId", 128)); val identity = NativeChatNotificationWire.text(state, "identity"); require(chatNoticeHash(identity))
            val rows = state.getJSONArray("targets"); require(rows.length() <= 64)
            val views = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index); NativeChatNotificationWire.keys(row, setOf("target", "operations", "epoch", "after", "through", "resetId", "lastResetId", "resetAfter", "resetSequence", "batch", "outcomes", "gaps"))
                view(row).also { v -> require(v.operations.all { it.original.deviceId == device && NativeChatNotificationWire.hash(it.original.publicKey) == identity }) }
            }
            require((0 until rows.length()).map { NativeChatNotificationCodec.target(rows.getJSONObject(it).getJSONObject("target")) }.distinct().size == rows.length())
            require(views.sumOf { it.operations.size } <= 256 && views.sumOf { it.outcomes.size } <= 2048)
            return state
        }
    }
    private fun io() = check(Looper.myLooper() != Looper.getMainLooper()) { "Recovery journal requires IO" }
    fun exists(): Boolean { io(); return storage.read(RECORD) != null }
    fun pendingByTarget(): Map<NativeChatNoticeTarget, List<String>> {
        io(); return storage.snapshotTransaction {
            val state = storage.read(RECORD)?.let(::decode) ?: return@snapshotTransaction emptyMap()
            val rows = state.getJSONArray("targets")
            (0 until rows.length()).associate { index ->
                val row = rows.getJSONObject(index); val operations = row.getJSONArray("operations")
                NativeChatNotificationCodec.target(row.getJSONObject("target")) to (0 until operations.length()).map { operations.getJSONObject(it) }
                    .filter { it.getString("state") in setOf("unknown", "reserved") }.map { it.getString("authorization") }
            }
        }
    }
    private fun <T> transaction(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, write: Boolean = false, action: (JSONObject, JSONObject) -> T): T {
        io(); return storage.snapshotTransaction { storage.operationTransaction {
            val state = storage.read(RECORD)?.let(::decode) ?: JSONObject().put("version", 2).put("deviceId", identity.deviceId).put("identity", identity.fingerprint).put("targets", JSONArray())
            require(state.getString("deviceId") == identity.deviceId && state.getString("identity") == identity.fingerprint)
            val rows = state.getJSONArray("targets"); val found = (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { NativeChatNotificationCodec.target(it.getJSONObject("target")) == target }
            val row = found ?: JSONObject().put("target", NativeChatNotificationCodec.target(target)).put("operations", JSONArray()).put("epoch", JSONObject.NULL).put("after", 0L).put("through", JSONObject.NULL)
                .put("resetId", JSONObject.NULL).put("lastResetId", JSONObject.NULL).put("resetAfter", 0L).put("resetSequence", JSONArray()).put("batch", JSONObject.NULL).put("outcomes", JSONArray()).put("gaps", JSONArray())
            val value = action(state, row)
            if (write) {
                if (found == null) rows.put(row)
                val encoded = NativeChatRecoveryCanonical.value(state); validateEncoded(encoded)
                NativeChatNotificationIdentity.bind(storage, identity); storage.write(RECORD, encoded)
            }
            value
        } }
    }
    fun view(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget) = transaction(identity, target) { _, row -> view(row) }
    /** Persists attempted/unknown and complete raw original before any HTTP write. Explicit revoke can coexist with older unknown. */
    fun reserve(identity: NativeChatNotificationIdentity, snapshot: NativeChatNotificationSnapshot, epoch: String,
        preferences: NativeChatNotificationPreferences, kind: String, current: () -> Boolean, explicit: Boolean = false,
        gap: NativeChatRecoveryGap? = null, operationId: String = UUID.randomUUID().toString()): NativeChatRecoveryIntent = transaction(identity, snapshot.target, true) { _, row ->
        val view = view(row); check(current())
        require(view.operations.none { it.original.operationId == operationId }) { "Original action already saved; query it" }
        val floor = view.applied?.takeIf { it.original.epoch == epoch }?.original?.generation ?: 0L
        val pending = view.operations.filter { it.state in setOf("unknown", "reserved") && it.original.kind != "feed-reset" && it.original.generation > floor }
        if (!explicit) check(pending.isEmpty()) { "Original operation remains unknown" }
        require(!explicit || kind in setOf("revoke", "feed-reset"))
        val generation = if (kind == "feed-reset") requireNotNull(view.applied).original.generation else (view.operations.filter { it.original.kind != "feed-reset" }.maxOfOrNull { it.original.generation } ?: 0) + 1
        require(generation in 1..9_007_199_254_740_991L)
        val original = NativeChatRecoveryIntent.create(identity, snapshot.target, preferences, epoch, generation, kind, operationId, gap)
        row.getJSONArray("operations").put(JSONObject().put("original", original.json()).put("authorization", snapshot.authorizationFingerprint).put("state", "unknown").put("receipt", JSONObject.NULL))
        if (gap != null) row.getJSONArray("gaps").put(gap.json().put("operationId", original.operationId))
        original
    }
    fun settle(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, original: NativeChatRecoveryIntent, receipt: NativeChatRecoveryReceipt) = transaction(identity, target, true) { _, row ->
        require(receipt.intent.encoded() == original.encoded())
        val operations = row.getJSONArray("operations"); val saved = (0 until operations.length()).map { operations.getJSONObject(it) }.single { it.getJSONObject("original").getString("operationId") == original.operationId }
        require(NativeChatRecoveryIntent.parse(saved.getJSONObject("original")).encoded() == original.encoded())
        val previousGeneration = view(row).applied?.original?.generation ?: 0L
        val existing = operation(saved)
        if (existing.state in setOf("applied", "rejected")) require(existing.receipt?.json()?.let(NativeChatRecoveryCanonical::value) == NativeChatRecoveryCanonical.value(receipt.json()))
        saved.put("state", receipt.stage).put("receipt", receipt.json())
        if (original.kind != "feed-reset" && receipt.stage == "applied" && original.generation > previousGeneration) {
            abandonRow(row); row.put("resetId", JSONObject.NULL).put("resetAfter", 0L).put("resetSequence", JSONArray())
        }
        val active = view(row).applied?.original
        if (original.kind == "feed-reset" && receipt.stage == "applied" && active?.generation == original.generation && active.epoch == original.epoch && active.kind != "revoke") {
            val last = optional(row, "lastResetId")
            val ids = (0 until operations.length()).map { operations.getJSONObject(it).getJSONObject("original").getString("operationId") }
            if (last == null || ids.indexOf(original.operationId) > ids.indexOf(last)) {
                abandonRow(row); row.put("resetId", original.operationId).put("lastResetId", original.operationId).put("resetAfter", 0L).put("resetSequence", JSONArray())
            }
        }
    }
    private fun abandonRow(row: JSONObject) {
        val outcomes = row.getJSONArray("outcomes")
        for (index in 0 until outcomes.length()) outcomes.getJSONObject(index).let { if (it.getString("state") == "processing") it.put("state", "unknown-interrupted-no-replay") }
        row.put("batch", JSONObject.NULL).put("through", JSONObject.NULL)
        row.getJSONArray("resetSequence").let { sequence -> while (sequence.length() > row.getLong("resetAfter")) sequence.remove(sequence.length() - 1) }
    }
    fun abandon(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batchId: String, current: () -> Boolean) = transaction(identity, target, true) { _, row ->
        check(current() && view(row).pending?.id == batchId); abandonRow(row)
    }
    fun preparePage(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, source: NativeChatRecoveryIntent, page: NativeChatRecoveryPage,
        now: Long, current: () -> Boolean): NativeChatRecoveryPending = transaction(identity, target, true) { _, row ->
        check(current()); val view = view(row); check(view.pending == null)
        require(view.applied?.original?.operationId == source.operationId && page.generation == source.generation && page.epoch == source.epoch)
        if (page.mode == "live") {
            check(view.epoch == null || view.epoch == page.epoch); require(view.after == page.after && (view.through == null || view.through == page.through))
            row.put("epoch", page.epoch).put("through", page.through)
        } else require(view.resetId == page.resetId && view.resetAfter == page.after)
        val resetIds = row.getJSONArray("resetSequence").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val retained = view.outcomes.filter { !it.terminal || it.event.id in resetIds || it.state.startsWith("unknown") || it.at > now - NativeChatNotificationStore.TTL_MILLIS }
        val outcomes = JSONArray(retained.map { JSONObject().put("original", JSONObject(it.original)).put("fingerprint", it.fingerprint).put("state", it.state).put("at", it.at) })
        page.items.forEach { item ->
            val existing = retained.singleOrNull { it.event.id == item.event.id }
            if (existing != null) require(existing.fingerprint == item.fingerprint) { "Original notification ID changed" }
            else outcomes.put(JSONObject().put("original", JSONObject(item.original)).put("fingerprint", item.fingerprint).put("state", "pending").put("at", now))
        }
        val batch = JSONObject().put("id", UUID.randomUUID().toString()).put("operationId", source.operationId).put("page", page.json())
            .put("decisions", JSONObject(page.items.associate { it.event.id to it.disposition })).put("activeIds", JSONArray(page.activeIds.toList()))
        row.put("outcomes", outcomes).put("batch", batch)
        if (page.mode == "reset") {
            val sequence = row.getJSONArray("resetSequence"); require(sequence.length().toLong() == page.after)
            page.items.forEach { sequence.put(it.event.id) }
        }
        requireNotNull(view(row).pending)
    }
    fun refresh(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batchId: String, fresh: NativeChatRecoveryPage, current: () -> Boolean) = transaction(identity, target, true) { _, row ->
        check(current()); val pending = requireNotNull(view(row).pending); check(pending.id == batchId)
        require(fresh.epoch == pending.page.epoch && fresh.generation == pending.page.generation && fresh.mode == pending.page.mode && fresh.resetId == pending.page.resetId)
        val decisions = row.getJSONObject("batch").getJSONObject("decisions")
        fresh.items.forEach { incoming -> pending.page.items.singleOrNull { it.sequence == incoming.sequence }?.let { original ->
            require(original.event.id == incoming.event.id && original.fingerprint == incoming.fingerprint); decisions.put(original.event.id, incoming.disposition)
        } }
        row.getJSONObject("batch").put("activeIds", JSONArray(fresh.activeIds.toList()))
    }
    fun start(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batchId: String, id: String, now: Long, current: () -> Boolean): NativeChatRecoveryOutcome? = transaction(identity, target, true) { _, row ->
        check(current()); val view = view(row); require(view.pending?.id == batchId && view.pending.page.items.any { it.event.id == id })
        val found = view.outcomes.single { it.event.id == id }; if (found.terminal) return@transaction null
        val value = row.getJSONArray("outcomes").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.single { it.getJSONObject("original").getString("id") == id } }
        if (found.state == "processing") { value.put("state", "unknown-interrupted-no-replay").put("at", now); return@transaction null }
        value.put("state", "processing").put("at", now); found
    }
    fun result(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batchId: String, id: String, state: String, now: Long) = transaction(identity, target, true) { _, row ->
        require(state in results - setOf("pending", "processing")); val view = view(row); check(view.pending?.id == batchId)
        val outcome = view.outcomes.single { it.event.id == id }
        if (outcome.terminal) { require(outcome.state == state); return@transaction }
        if (state == "waiting-host") require(outcome.state in setOf("pending", "waiting-host"))
        row.getJSONArray("outcomes").let { array -> (0 until array.length()).map { array.getJSONObject(it) }.single { it.getJSONObject("original").getString("id") == id }.put("state", state).put("at", now) }
    }
    fun complete(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batchId: String, current: () -> Boolean) = transaction(identity, target, true) { _, row ->
        check(current()); val view = view(row); val pending = requireNotNull(view.pending); require(pending.id == batchId)
        require(view.applied?.original?.operationId == pending.operationId && pending.page.items.all { item -> view.outcomes.single { it.event.id == item.event.id }.terminal })
        val page = pending.page
        if (page.mode == "reset") {
            row.put("resetAfter", page.next)
            if (!page.hasMore) {
                val snapshot = requireNotNull(view.operations.single { it.original.operationId == page.resetId }.receipt?.snapshot)
                val sequence = row.getJSONArray("resetSequence"); require(sequence.length().toLong() == snapshot.count)
                val digest = java.security.MessageDigest.getInstance("SHA-256"); digest.update('['.code.toByte())
                for (index in 0 until sequence.length()) {
                    if (index > 0) digest.update(','.code.toByte())
                    digest.update(view.outcomes.single { it.event.id == sequence.getString(index) }.original.toByteArray(Charsets.UTF_8))
                }
                digest.update(']'.code.toByte()); require(digest.digest().joinToString("") { "%02x".format(it) } == snapshot.hash)
                row.put("epoch", page.epoch).put("after", page.baseline).put("through", JSONObject.NULL).put("resetId", JSONObject.NULL).put("resetAfter", 0L).put("resetSequence", JSONArray())
            }
        } else row.put("after", page.next).put("through", if (page.hasMore) page.through else JSONObject.NULL)
        row.put("batch", JSONObject.NULL)
    }
}
