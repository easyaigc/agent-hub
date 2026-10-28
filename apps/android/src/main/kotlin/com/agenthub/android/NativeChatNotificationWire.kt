package com.agenthub.android

import org.json.JSONObject
import java.time.Instant
import java.util.Base64

/** A notification status never proves an ambiguous mutation: Host v1 has no intent fingerprint. */
internal class NativeChatNotificationStatus(val target: NativeChatNoticeTarget, val delivery: String,
    val state: String, val revision: String?, val reason: String?, val hasBinding: Boolean) {
    fun foreground(revision: String) = this.revision == revision && delivery == "foreground-only" && !hasBinding && state != "disabled"
    override fun toString() = "NativeChatNotificationStatus($delivery,$state)"
}
internal data class NativeChatNotificationFeed(val events: List<NativeChatNotificationEvent>, val cursor: String,
    val activeIds: Set<String>, val status: NativeChatNotificationStatus)

/** Closed Host v1 contracts, independent of the generated Codex command schema. */
internal object NativeChatNotificationWire {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_EVENTS = 2048
    fun keys(v: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = v.keys().asSequence().toSet()
        require(keys.containsAll(required) && (keys - required - optional).isEmpty()) { "Invalid notification object" }
    }
    fun text(v: JSONObject, key: String, maximum: Int = 256): String = ((v.get(key) as? String)
        ?: error("Invalid notification string")).also { require(it.length in 1..maximum && it.none { c -> c.code < 32 }) }
    fun optional(v: JSONObject, key: String, maximum: Int = 256) = if (v.has(key)) text(v, key, maximum) else null
    fun long(v: JSONObject, key: String): Long {
        val value = v.get(key); require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0..9_007_199_254_740_991L) }
    }
    fun uuid(value: String): String = value.also { require(java.util.UUID.fromString(it).toString() == it) }
    fun device(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9._-]{1,128}")) && it !in setOf("__proto__", "prototype", "constructor"))
    }
    fun key(value: String): ByteArray {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == value)
        return bytes
    }
    fun status(v: JSONObject, expected: NativeChatNoticeTarget): NativeChatNotificationStatus {
        keys(v, setOf("version", "target", "delivery", "state"), setOf("reason", "revision", "bindingId", "publicKey", "relayURL", "managementToken", "lastDelivery"))
        require(long(v, "version") == 1L)
        val target = NativeChatNotificationCodec.target(v.getJSONObject("target")); require(target == expected)
        val delivery = text(v, "delivery"); val state = text(v, "state")
        require(delivery in setOf("foreground-only", "apns") && state in setOf("disabled", "ready", "pending", "unavailable"))
        optional(v, "publicKey", 64)?.let { key(it).fill(0) }
        optional(v, "bindingId"); optional(v, "managementToken", 512)
        optional(v, "relayURL", 2048)?.let { require(java.net.URI(it).let { uri -> uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null }) }
        if (v.has("lastDelivery")) v.getJSONObject("lastDelivery").let {
            keys(it, setOf("state", "occurredAt"), setOf("reason"))
            require(text(it, "state") in setOf("pending", "submitting", "accepted", "uncertain", "rejected"))
            Instant.parse(text(it, "occurredAt")); optional(it, "reason", 4096)
        }
        return NativeChatNotificationStatus(target, delivery, state, optional(v, "revision"), optional(v, "reason", 4096), v.has("bindingId"))
    }
    fun feed(v: JSONObject, expected: NativeChatNoticeTarget, after: String): NativeChatNotificationFeed {
        keys(v, setOf("events", "cursor", "activeIds", "status"))
        val raw = v.getJSONArray("events"); require(raw.length() <= MAX_EVENTS)
        val events = (0 until raw.length()).map { NativeChatNotificationCodec.event(raw.getJSONObject(it)).also { e -> require(e.target == expected) } }
        require(events.map { it.id }.distinct().size == events.size)
        val cursor = v.get("cursor") as? String ?: error("Invalid cursor")
        require(cursor.isEmpty() || chatNoticeHash(cursor))
        val ids = v.getJSONArray("activeIds"); require(ids.length() <= MAX_EVENTS)
        val active = (0 until ids.length()).map { (ids.get(it) as? String ?: error("Invalid event ID")).also { id -> require(chatNoticeHash(id)) } }
        require(active.distinct().size == active.size)
        val status = status(v.getJSONObject("status"), expected)
        // Host can advance a cursor without payload for APNs; that is not a usable foreground feed.
        if (status.delivery == "foreground-only") require(cursor == (events.lastOrNull()?.id ?: after))
        return NativeChatNotificationFeed(events, cursor, active.toSet(), status)
    }
    fun registration(deviceId: String, publicKey: String, target: NativeChatNoticeTarget, preferences: NativeChatNotificationPreferences): JSONObject {
        device(deviceId); key(publicKey).fill(0)
        return JSONObject().put("version", 1).put("deviceId", deviceId).put("target", NativeChatNotificationCodec.target(target))
            .put("publicKey", publicKey).put("preferences", NativeChatNotificationCodec.preferences(preferences))
    }
    fun hash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        JSONObject.NULL -> "null"
        else -> error("Invalid canonical notification value")
    }
    fun preferenceHash(value: NativeChatNotificationPreferences): String = hash(canonical(NativeChatNotificationCodec.preferences(value)))
    fun eventHash(value: NativeChatNotificationEvent): String = hash(canonical(NativeChatNotificationCodec.event(value)))
}
