package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest

/** Migration-owned immutable dedup index. It has no ticket, lease or active receipt capacity. */
internal object NativeMigrationNotificationTombstones {
    const val RECORD = "migration-notification-tombstones-v1"
    const val MAX_KEYS = 16 * 2048 // Existing audit generations * original receipt bound; never prune.
    private const val MAX_BYTES = 3 * 1024 * 1024
    fun key(event: NativeChatNotificationEvent): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("agent-hub/migrated-notification-event/v1\u0000".toByteArray(Charsets.UTF_8))
        // Full Codex machine/runtime target plus original event ID; account is historical metadata.
        for (part in listOf("codex", event.target.hostId, event.target.workspaceId, event.target.runtimeId, event.id)) {
            val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(part))
            val bytes = ByteArray(encoded.remaining()); encoded.get(bytes)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        val hash = digest.digest(); val hex = "0123456789abcdef"
        return CharArray(hash.size * 2) { index ->
            val byte = hash[index / 2].toInt() and 255
            hex[if (index % 2 == 0) byte ushr 4 else byte and 15]
        }.concatToString()
    }
    fun decode(raw: String): Set<String> {
        val bytes = raw.toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
        val value = StrictMigrationJson.parse(bytes)
        require(value.keys().asSequence().toSet() == setOf("version", "eventKeys") && value.get("version") == 1)
        val rows = value.getJSONArray("eventKeys"); require(rows.length() <= MAX_KEYS)
        val keys = (0 until rows.length()).map { rows.get(it) as String }
        require(keys.all(::chatNoticeHash) && keys == keys.distinct().sorted())
        return keys.toSet()
    }
    fun encode(keys: Set<String>): String {
        require(keys.size <= MAX_KEYS && keys.all(::chatNoticeHash))
        return JSONObject().put("version", 1).put("eventKeys", JSONArray(keys.sorted())).toString().also(::decode)
    }
    /** Caller has already validated every original audit entry with the closed source decoders. */
    fun fromAudit(audit: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        audit.getJSONArray("entries").objects().forEach { entry -> entry.optional("notifications")?.let { raw ->
            StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)).getJSONArray("receipts").objects().forEach { row ->
                keys.add(key(NativeChatNotificationCodec.event(row.getJSONObject("event"))))
                require(keys.size <= MAX_KEYS)
            }
        } }
        return keys
    }
    fun validate(records: Map<String, String>, audit: JSONObject?) {
        val raw = records[RECORD]
        if (raw == null) { require(audit?.get("policyVersion") != 3) { "Missing migration notification tombstones" }; return }
        require(audit != null && audit.get("policyVersion") == 3)
        require(decode(raw) == fromAudit(audit)) { "Migration notification tombstones disagree with originals" }
    }
    /** Read-only hook called while claim holds the same root transaction; no cleanup or write. */
    fun contains(storage: NativeSessionStorage, event: NativeChatNotificationEvent): Boolean {
        val raw = storage.read(RECORD)
        if (raw != null) return key(event) in decode(raw)
        // Read compatibility for the old failed policy2 candidate/installation. It does not compact
        // its active sentinel receipts; preparing that source again upgrades to policy3 atomically.
        val audit = storage.read(NativeMigrationDeviceState.AUDIT) ?: return false
        return key(event) in NativeMigrationDeviceState.legacyNotificationTombstones(audit)
    }
}
