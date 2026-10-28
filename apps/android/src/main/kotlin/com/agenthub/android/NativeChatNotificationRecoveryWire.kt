package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

internal object NativeChatRecoveryCanonical {
    fun value(input: Any?): String = when (input) {
        null, JSONObject.NULL -> "null"
        is Boolean -> input.toString()
        is Int, is Long -> (input as Number).toLong().also { require(it in -9_007_199_254_740_991L..9_007_199_254_740_991L) }.toString()
        is String -> quote(input)
        is JSONArray -> (0 until input.length()).joinToString(",", "[", "]") { value(input.get(it)) }
        is JSONObject -> input.keys().asSequence().sorted().joinToString(",", "{", "}") { quote(it) + ":" + value(input.get(it)) }
        else -> error("Invalid recovery canonical value")
    }
    private fun quote(value: String): String = buildString {
        append('"'); var index = 0
        while (index < value.length) {
            val c = value[index++]
            when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> when {
                    c.code < 32 -> append("\\u" + c.code.toString(16).padStart(4, '0'))
                    c.isHighSurrogate() -> { require(index < value.length && value[index].isLowSurrogate()); append(c); append(value[index++]) }
                    c.isLowSurrogate() -> error("Invalid recovery Unicode")
                    else -> append(c)
                }
            }
        }
        append('"')
    }
    fun hash(input: Any?) = NativeChatNotificationWire.hash(value(input))
}
internal data class NativeChatRecoveryGap(val epoch: String, val after: Long, val floor: Long, val head: Long, val acceptanceId: String) {
    fun json() = JSONObject().put("epoch", epoch).put("after", after).put("floor", floor).put("head", head).put("acceptanceId", acceptanceId)
}
internal class NativeChatRecoveryIntent private constructor(private val wire: String, val operationId: String, val epoch: String,
    val deviceId: String, val target: NativeChatNoticeTarget, val publicKey: String, val preferences: NativeChatNotificationPreferences,
    val generation: Long, val kind: String, val fingerprint: String, val gap: NativeChatRecoveryGap?) {
    fun json() = JSONObject(wire)
    fun encoded() = wire
    override fun toString() = "NativeChatRecoveryIntent(redacted)"
    companion object {
        fun create(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, preferences: NativeChatNotificationPreferences,
            epoch: String, generation: Long, kind: String, operationId: String = java.util.UUID.randomUUID().toString(), gap: NativeChatRecoveryGap? = null): NativeChatRecoveryIntent {
            val raw = JSONObject().put("version", 2).put("operationId", operationId).put("epoch", epoch).put("deviceId", identity.deviceId)
                .put("target", NativeChatNotificationCodec.target(target)).put("publicKey", identity.publicKey).put("preferences", NativeChatNotificationCodec.preferences(preferences))
                .put("generation", generation).put("kind", kind).put("gap", gap?.json())
            raw.put("fingerprint", NativeChatRecoveryCanonical.hash(raw))
            return parse(raw)
        }
        fun parse(raw: JSONObject): NativeChatRecoveryIntent {
            NativeChatNotificationWire.keys(raw, setOf("version", "operationId", "epoch", "deviceId", "target", "publicKey", "preferences", "generation", "kind", "fingerprint"), setOf("gap"))
            require(NativeChatNotificationWire.long(raw, "version") == 2L)
            val operationId = NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(raw, "operationId"))
            val epoch = NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(raw, "epoch"))
            val deviceId = NativeChatNotificationWire.device(NativeChatNotificationWire.text(raw, "deviceId", 128))
            val key = NativeChatNotificationWire.text(raw, "publicKey", 64); NativeChatNotificationWire.key(key).fill(0)
            val preferences = NativeChatNotificationCodec.preferences(raw.getJSONObject("preferences"))
            val target = NativeChatNotificationCodec.target(raw.getJSONObject("target"))
            val generation = NativeChatNotificationWire.long(raw, "generation"); require(generation > 0)
            val kind = NativeChatNotificationWire.text(raw, "kind"); require(kind in setOf("register", "preferences", "revoke", "feed-reset"))
            require(kind != "revoke" || !preferences.enabled)
            val gap = if (raw.has("gap")) raw.getJSONObject("gap").let {
                NativeChatNotificationWire.keys(it, setOf("epoch", "after", "floor", "head", "acceptanceId"))
                NativeChatRecoveryGap(NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(it, "epoch")), NativeChatNotificationWire.long(it, "after"),
                    NativeChatNotificationWire.long(it, "floor"), NativeChatNotificationWire.long(it, "head"), NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(it, "acceptanceId")))
                    .also { g -> require(g.floor >= 1 && g.head >= g.floor - 1) }
            } else null
            require((kind == "feed-reset") == (gap != null))
            val fingerprint = NativeChatNotificationWire.text(raw, "fingerprint"); require(chatNoticeHash(fingerprint))
            val content = JSONObject(raw.toString()).apply { remove("fingerprint") }
            require(fingerprint == NativeChatRecoveryCanonical.hash(content))
            return NativeChatRecoveryIntent(NativeChatRecoveryCanonical.value(raw), operationId, epoch, deviceId, target, key, preferences, generation, kind, fingerprint, gap)
        }
    }
}
internal data class NativeChatRecoveryCapabilities(val epoch: String)
internal class NativeChatRecoveryReceipt(val intent: NativeChatRecoveryIntent, val stage: String, val reason: String?,
    val snapshot: NativeChatRecoverySnapshot?, private val wire: String) {
    fun json() = JSONObject(wire)
    override fun toString() = "NativeChatRecoveryReceipt($stage)"
}
internal data class NativeChatRecoverySnapshot(val id: String, val hash: String, val count: Long, val baseline: Long, val expiresAt: Long)
internal data class NativeChatRecoveryQuery(val deviceId: String, val epoch: String, val generation: Long, val after: Long,
    val through: Long? = null, val resetId: String? = null)
internal class NativeChatRecoveryItem(val sequence: Long, val event: NativeChatNotificationEvent, val original: String, val fingerprint: String, val disposition: String) {
    override fun toString() = "NativeChatRecoveryItem($sequence,$disposition)"
}
internal class NativeChatRecoveryPage(val epoch: String, val generation: Long, val mode: String, val resetId: String?, val snapshotHash: String?,
    val baseline: Long, val after: Long, val next: Long, val through: Long, val hasMore: Boolean,
    val items: List<NativeChatRecoveryItem>, val activeIds: Set<String>, private val wire: String) {
    fun json() = JSONObject(wire)
    override fun toString() = "NativeChatRecoveryPage($mode,${items.size})"
}
internal class NativeChatRecoveryFeed(val kind: String, val page: NativeChatRecoveryPage?, val reason: String?, val epoch: String,
    val floor: Long? = null, val head: Long? = null)

internal object NativeChatRecoveryWire {
    const val MAX_PAGE_BYTES = 256 * 1024
    val limits = mapOf("slots" to 128L, "operations" to 1024L, "logEvents" to 2048L, "logBytes" to 4L * 1024 * 1024,
        "snapshots" to 4L, "snapshotEvents" to 2048L, "snapshotBytes" to 2L * 1024 * 1024, "totalBytes" to 16L * 1024 * 1024,
        "pageEvents" to 32L, "pageBytes" to MAX_PAGE_BYTES.toLong(), "activeIds" to 2048L, "snapshotLifetimeMs" to 300_000L)
    private fun common(raw: JSONObject, target: NativeChatNoticeTarget): String {
        require(NativeChatNotificationWire.long(raw, "version") == 2L)
        require(NativeChatNotificationCodec.target(raw.getJSONObject("target")) == target)
        return NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(raw, "epoch"))
    }
    fun capabilities(raw: JSONObject, target: NativeChatNoticeTarget): NativeChatRecoveryCapabilities {
        NativeChatNotificationWire.keys(raw, setOf("version", "epoch", "target", "operations", "limits"))
        val epoch = common(raw, target); val operations = raw.getJSONArray("operations")
        require(operations.length() == 4 && (0 until 4).map { operations.get(it) as? String ?: error("Invalid operation") }.toSet() == setOf("register", "preferences", "revoke", "feed-reset"))
        val bounds = raw.getJSONObject("limits"); NativeChatNotificationWire.keys(bounds, limits.keys)
        require(limits.all { (key, value) -> NativeChatNotificationWire.long(bounds, key) == value })
        return NativeChatRecoveryCapabilities(epoch)
    }
    fun receipt(raw: JSONObject, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt {
        NativeChatNotificationWire.keys(raw, setOf("version", "epoch", "intent", "stage"), setOf("reason", "status", "snapshot"))
        require(NativeChatNotificationWire.long(raw, "version") == 2L && NativeChatNotificationWire.text(raw, "epoch") == original.epoch)
        val intent = NativeChatRecoveryIntent.parse(raw.getJSONObject("intent"))
        require(intent.encoded() == original.encoded())
        val stage = NativeChatNotificationWire.text(raw, "stage"); require(stage in setOf("reserved", "applied", "rejected"))
        val reason = NativeChatNotificationWire.optional(raw, "reason", 128)
        if (stage == "rejected") require(reason != null)
        else require(reason == null)
        if (raw.has("status")) {
            require(stage == "applied" && original.kind != "feed-reset")
            val status = NativeChatNotificationWire.status(raw.getJSONObject("status"), original.target)
            require(status.revision == original.preferences.revision)
            NativeChatNotificationWire.optional(raw.getJSONObject("status"), "publicKey", 64)?.let { require(it == original.publicKey) }
            if (original.kind == "revoke") require(status.state == "disabled")
            if (stage == "applied" && original.kind in setOf("register", "preferences") && original.preferences.enabled) require(status.foreground(original.preferences.revision))
        }
        if (stage == "applied" && original.kind != "feed-reset") require(raw.has("status"))
        val snapshot = if (raw.has("snapshot")) raw.getJSONObject("snapshot").let {
            NativeChatNotificationWire.keys(it, setOf("id", "hash", "count", "baseline", "expiresAt"))
            NativeChatRecoverySnapshot(NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(it, "id")), NativeChatNotificationWire.text(it, "hash"),
                NativeChatNotificationWire.long(it, "count"), NativeChatNotificationWire.long(it, "baseline"), NativeChatNotificationWire.long(it, "expiresAt"))
                .also { s -> require(s.id == original.operationId && chatNoticeHash(s.hash) && s.count <= 2048) }
        } else null
        require(snapshot == null || original.kind == "feed-reset" && stage == "applied")
        if (original.kind == "feed-reset" && stage == "applied") require(snapshot != null)
        if (stage == "reserved") require(reason == null && !raw.has("status") && snapshot == null)
        return NativeChatRecoveryReceipt(intent, stage, reason, snapshot, NativeChatRecoveryCanonical.value(raw))
    }
    /** null means still unknown. Absence/new epoch never turns an attempted operation into retry permission. */
    fun query(raw: JSONObject, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt? {
        val found = raw.get("found") as? Boolean ?: error("Invalid found flag")
        NativeChatNotificationWire.keys(raw, setOf("version", "epoch", "target", "deviceId", "operationId", "found", if (found) "receipt" else "reason"))
        val epoch = common(raw, original.target)
        require(NativeChatNotificationWire.text(raw, "deviceId") == original.deviceId && NativeChatNotificationWire.text(raw, "operationId") == original.operationId)
        if (!found) {
            require(NativeChatNotificationWire.text(raw, "reason") in setOf("absent-unknown", "epoch-mismatch"))
            return null
        }
        require(epoch == original.epoch)
        return receipt(raw.getJSONObject("receipt"), original)
    }
    fun feed(raw: JSONObject, query: NativeChatRecoveryQuery, original: NativeChatRecoveryIntent, snapshot: NativeChatRecoverySnapshot? = null): NativeChatRecoveryFeed {
        require(NativeChatRecoveryCanonical.value(raw).toByteArray(Charsets.UTF_8).size <= MAX_PAGE_BYTES)
        val kind = NativeChatNotificationWire.text(raw, "kind")
        val fields = setOf("version", "epoch", "deviceId", "target", "generation", "kind")
        val epoch = common(raw, original.target)
        require(NativeChatNotificationWire.text(raw, "deviceId") == query.deviceId && query.deviceId == original.deviceId)
        val generation = NativeChatNotificationWire.long(raw, "generation"); require(generation == query.generation && generation == original.generation)
        if (kind == "reset-required") {
            NativeChatNotificationWire.keys(raw, fields + setOf("reason", "floor", "head"))
            val reason = NativeChatNotificationWire.text(raw, "reason"); require(reason in setOf("epoch-mismatch", "subscription-mismatch", "snapshot-expired-or-missing", "retention-gap", "invalid-page-range", "non-contiguous-source"))
            val floor = NativeChatNotificationWire.long(raw, "floor"); val head = NativeChatNotificationWire.long(raw, "head"); require(floor >= 1 && head >= floor - 1)
            return NativeChatRecoveryFeed(kind, null, reason, epoch, floor, head)
        }
        if (kind == "capacity") {
            NativeChatNotificationWire.keys(raw, fields + "reason"); val reason = NativeChatNotificationWire.text(raw, "reason"); require(reason in setOf("active-ids", "page-bytes"))
            return NativeChatRecoveryFeed(kind, null, reason, epoch)
        }
        require(kind == "page" && epoch == query.epoch && epoch == original.epoch)
        NativeChatNotificationWire.keys(raw, fields + setOf("mode", "baseline", "after", "next", "through", "hasMore", "items", "activeIds", "publicKey", "preferencesFingerprint"), setOf("resetId", "snapshotHash"))
        require(NativeChatNotificationWire.text(raw, "publicKey", 64) == original.publicKey)
        require(NativeChatNotificationWire.text(raw, "preferencesFingerprint") == NativeChatRecoveryCanonical.hash(original.json().getJSONObject("preferences")))
        val mode = NativeChatNotificationWire.text(raw, "mode"); val resetId = NativeChatNotificationWire.optional(raw, "resetId"); val snapshotHash = NativeChatNotificationWire.optional(raw, "snapshotHash")
        val baseline = NativeChatNotificationWire.long(raw, "baseline"); val after = NativeChatNotificationWire.long(raw, "after"); val next = NativeChatNotificationWire.long(raw, "next"); val through = NativeChatNotificationWire.long(raw, "through")
        require(after == query.after && next in after..through && (query.through == null || query.through == through))
        if (mode == "reset") require(snapshot != null && resetId == query.resetId && resetId == snapshot.id && snapshotHash == snapshot.hash && through == snapshot.count && baseline == snapshot.baseline)
        else require(mode == "live" && query.resetId == null && resetId == null && snapshotHash == null && baseline == through)
        val more = raw.get("hasMore") as? Boolean ?: error("Invalid page flag"); require(more == (next < through))
        val items = raw.getJSONArray("items"); require(items.length() <= 32 && next - after == items.length().toLong() && (items.length() > 0 || !more))
        val events = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index); NativeChatNotificationWire.keys(item, setOf("sequence", "event", "fingerprint", "disposition"))
            val sequence = NativeChatNotificationWire.long(item, "sequence"); require(sequence == after + index + 1)
            val event = item.getJSONObject("event"); val hash = NativeChatNotificationWire.text(item, "fingerprint"); require(chatNoticeHash(hash) && hash == NativeChatRecoveryCanonical.hash(event))
            val decoded = NativeChatNotificationCodec.event(event); require(decoded.target == original.target)
            val disposition = NativeChatNotificationWire.text(item, "disposition"); require(disposition in setOf("eligible", "inactive", "stale", "filtered"))
            NativeChatRecoveryItem(sequence, decoded, NativeChatRecoveryCanonical.value(event), hash, disposition)
        }
        require(events.map { it.event.id }.distinct().size == events.size)
        val ids = raw.getJSONArray("activeIds"); require(ids.length() <= 2048)
        val active = (0 until ids.length()).map { (ids.get(it) as? String ?: error("Invalid active ID")).also { id -> require(chatNoticeHash(id)) } }; require(active.distinct().size == active.size)
        return NativeChatRecoveryFeed(kind, NativeChatRecoveryPage(epoch, generation, mode, resetId, snapshotHash, baseline, after, next, through, more, events, active.toSet(), NativeChatRecoveryCanonical.value(raw)), null, epoch)
    }
}
