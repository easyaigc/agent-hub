package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

internal data class NativeMigrationRecoveryLocalOperation(val original: NativeMigrationRecoveryIntent, val authorization: String,
    val state: String, val receipt: NativeMigrationRecoveryReceipt?)
internal class NativeMigrationRecoveryPending(val id: String, val operationId: String, val page: NativeMigrationRecoveryPage, val decisions: Map<String, String>, val activeIds: Set<String>)
internal data class NativeMigrationRecoveryOutcome(val original: String, val fingerprint: String, val event: NativeChatNotificationEvent,
    val state: String, val at: Long) { val terminal get() = state !in setOf("pending", "waiting-host", "processing") }
internal class NativeMigrationRecoveryLocalView(val operations: List<NativeMigrationRecoveryLocalOperation>, val epoch: String?, val after: Long,
    val through: Long?, val resetId: String?, val resetAfter: Long, val pending: NativeMigrationRecoveryPending?,
    val outcomes: List<NativeMigrationRecoveryOutcome>, val gaps: List<JSONObject>) {
    val applied get() = operations.filter { it.state == "applied" && it.original.kind != "feed-reset" }.maxByOrNull { it.original.generation }
}

/** Frozen cc0b6ac8 decoder only; archive bytes cannot become active subscriptions. */
internal object NativeMigrationForegroundV2 {

        const val RECORD = "native-chat-notification-recovery-v2"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val results = setOf("pending", "waiting-host", "processing", "suppressed", "system-unavailable", "submitted-to-system", "unknown-no-replay",
            "unknown-interrupted-no-replay", "stale-target-or-generation", "expired-or-future", "duplicate-expired-or-read", "host-inactive", "host-expired")
        fun decodeEncoded(raw: String): JSONObject = decode(raw)
        /** Validate the full original, retain only installation/reference summaries for the next record. */
        fun decodeSummary(raw: String): JSONObject {
            val value=decode(raw)
            return JSONObject().put("deviceId",value.getString("deviceId")).put("identity",value.getString("identity"))
                .put("targets",JSONArray(value.getJSONArray("targets").objects().map {row->
                    JSONObject().put("target",row.getJSONObject("target")).put("operations",JSONArray(row.getJSONArray("operations").objects().map {op->
                        JSONObject().put("authorization",op.getString("authorization"))
                    }))
                }))
        }
        private fun optional(raw: JSONObject, key: String) = if (raw.get(key) == JSONObject.NULL) null else NativeChatNotificationWire.text(raw, key)
        private fun number(raw: JSONObject, key: String) = NativeChatNotificationWire.long(raw, key)
        private fun operation(raw: JSONObject): NativeMigrationRecoveryLocalOperation {
            NativeChatNotificationWire.keys(raw, setOf("original", "authorization", "state", "receipt"))
            val original = NativeMigrationRecoveryIntent.parse(raw.getJSONObject("original")); val authorization = NativeChatNotificationWire.text(raw, "authorization")
            require(chatNoticeHash(authorization)); val state = NativeChatNotificationWire.text(raw, "state"); require(state in setOf("unknown", "reserved", "applied", "rejected"))
            val receipt = if (raw.get("receipt") == JSONObject.NULL) null else NativeMigrationRecoveryWire.receipt(raw.getJSONObject("receipt"), original)
            require(if (state == "unknown") receipt == null else receipt?.stage == state)
            return NativeMigrationRecoveryLocalOperation(original, authorization, state, receipt)
        }
        private fun outcome(raw: JSONObject, target: NativeChatNoticeTarget): NativeMigrationRecoveryOutcome {
            NativeChatNotificationWire.keys(raw, setOf("original", "fingerprint", "state", "at"))
            val event = raw.getJSONObject("original"); val fingerprint = NativeChatNotificationWire.text(raw, "fingerprint"); val decoded = NativeChatNotificationCodec.event(event)
            val state = NativeChatNotificationWire.text(raw, "state")
            require(decoded.target == target && state in results && chatNoticeHash(fingerprint) && fingerprint == NativeMigrationRecoveryCanonical.hash(event))
            return NativeMigrationRecoveryOutcome(NativeMigrationRecoveryCanonical.value(event), fingerprint, decoded, state, number(raw, "at"))
        }
        private fun view(row: JSONObject): NativeMigrationRecoveryLocalView {
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
                val query = NativeMigrationRecoveryQuery(source.original.deviceId, NativeChatNotificationWire.text(page, "epoch"), number(page, "generation"), number(page, "after"), number(page, "through"), snapshotId)
                val parsed = requireNotNull(NativeMigrationRecoveryWire.feed(page, query, source.original, snapshot).page)
                require(if (parsed.mode == "reset") resetId == parsed.resetId && resetAfter == parsed.after else epoch == parsed.epoch && after == parsed.after)
                val decisions = batch.getJSONObject("decisions"); NativeChatNotificationWire.keys(decisions, parsed.items.map { it.event.id }.toSet())
                val latest = parsed.items.associate { item -> item.event.id to NativeChatNotificationWire.text(decisions, item.event.id).also { require(it in setOf("eligible", "inactive", "stale", "filtered")) } }
                require(parsed.items.all { item -> outcomes.any { it.event.id == item.event.id && it.fingerprint == item.fingerprint } })
                val ids = batch.getJSONArray("activeIds"); require(ids.length() <= 2048)
                val active = (0 until ids.length()).map { (ids.get(it) as? String ?: error("Invalid active ID")).also { id -> require(chatNoticeHash(id)) } }
                require(active.distinct().size == active.size)
                NativeMigrationRecoveryPending(id, opId, parsed, latest, active.toSet())
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
            return NativeMigrationRecoveryLocalView(operations, epoch, after, through, resetId, resetAfter, pending, outcomes, parsedGaps)
        }
        private fun decode(raw: String): JSONObject {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val state = StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)); NativeChatNotificationWire.keys(state, setOf("version", "deviceId", "identity", "targets"))
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
