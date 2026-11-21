package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Separate full-account EasyWork authority and receipts; shares only the user's ordinary-notice preferences. */
internal class NativeEasyWorkNotificationStore(private val storage: NativeSessionStorage) {
    companion object { const val RECORD = "native-easywork-notifications-v1"
        fun key(scope: TargetScope): String { require(scope.agentId == "easywork"); return NativeChatRecoveryCanonical.hash(CodexCommandCodec.target(scope)) }
    }
    private fun load() = storage.read(RECORD)?.let { raw ->
        require(raw.toByteArray().size <= 4 * 1024 * 1024)
        JSONObject(raw).also {
            NativeChatNotificationWire.keys(it, setOf("version", "targets", "receipts"))
            require(it.getInt("version") == 1 && it.getJSONObject("targets").length() <= 64 && it.getJSONArray("receipts").length() <= 2048)
        }
    } ?: JSONObject().put("version", 1).put("targets", JSONObject()).put("receipts", JSONArray())
    private fun write(value: JSONObject) {
        val raw = value.toString(); require(raw.toByteArray().size <= 4 * 1024 * 1024)
        storage.write(RECORD, raw)
    }
    fun rows(): List<JSONObject> = storage.snapshotTransaction { load().getJSONObject("targets").let { rows -> rows.keys().asSequence().map { rows.getJSONObject(it) }.toList() } }
    fun row(scope: TargetScope) = storage.snapshotTransaction { load().getJSONObject("targets").optJSONObject(key(scope)) }
    fun save(scope: TargetScope, row: JSONObject) = storage.snapshotTransaction {
        row.put("target", CodexCommandCodec.target(scope))
        val value = load(); val rows = value.getJSONObject("targets"); val old = rows.optJSONObject(key(scope))
        // Original attempted input is immutable even when a separate explicit revoke supersedes it.
        val history = old?.optJSONArray("originals") ?: JSONArray()
        val original = row.optJSONObject("intent")
        if (original != null && (0 until history.length()).none { history.getJSONObject(it).getString("operationId") == original.getString("operationId") }) history.put(original)
        require(history.length() <= 128); row.put("originals", history)
        if (old?.optString("lease") != row.optString("lease") || old?.optString("phase") != row.optString("phase") || old?.optString("revision") != row.optString("revision"))
            NativeEasyWorkNotificationFence.invalidate(storage)
        rows.put(key(scope), row); require(rows.length() <= 64); write(value)
    }
    fun current(scope: TargetScope, original: JSONObject): Boolean = row(scope)?.let {
        it.optString("lease") == original.optString("lease") && it.optString("phase") == "ready" &&
            it.optString("credentialEpoch") == original.optString("credentialEpoch") && it.optString("revision") == original.optString("revision")
    } == true
    fun claim(event: NativeTaskNotice, binding: JSONObject, current: () -> Boolean): String? = storage.snapshotTransaction { storage.operationTransaction {
        val now = System.currentTimeMillis(); val occurred = java.time.Instant.parse(event.occurredAt).toEpochMilli()
        if (!current() || !this.current(event.target, binding) || occurred > now + 60_000 || occurred <= now - NativeChatNotificationStore.TTL_MILLIS) return@operationTransaction null
        val value = load(); val live = value.getJSONArray("receipts").objects().filter { it.getLong("expiresAt") > now }
        if (live.any { it.getString("scope") == key(event.target) && it.getString("eventId") == event.id }) return@operationTransaction null
        require(live.size < 2048)
        val ticket = UUID.randomUUID().toString()
        val receipt = JSONObject().put("scope", key(event.target)).put("target", CodexCommandCodec.target(event.target)).put("eventId", event.id)
            .put("ticket", ticket).put("event", JSONObject(event.raw)).put("binding", binding).put("expiresAt", occurred + NativeChatNotificationStore.TTL_MILLIS)
            .put("phase", "claimed").put("consumed", false)
        value.put("receipts", JSONArray(live + receipt)); write(value); ticket
    } }
    fun finish(ticket: String, phase: String) = storage.snapshotTransaction {
        require(phase in setOf("posted", "unknown", "suppressed")); val value = load()
        value.getJSONArray("receipts").objects().singleOrNull { it.getString("ticket") == ticket }?.put("phase", phase)
        write(value)
    }
    fun ticket(ticket: String): JSONObject? = storage.snapshotTransaction {
        load().getJSONArray("receipts").objects().singleOrNull { it.getString("ticket") == ticket && !it.getBoolean("consumed") && it.getLong("expiresAt") > System.currentTimeMillis() }
    }
    fun claimNavigation(ticket: String, current: () -> Boolean): String? = storage.snapshotTransaction {
        val value = load(); val receipt = value.getJSONArray("receipts").objects().singleOrNull {
            it.getString("ticket") == ticket && !it.getBoolean("consumed") && it.getLong("expiresAt") > System.currentTimeMillis()
        } ?: return@snapshotTransaction null
        if (!current()) return@snapshotTransaction null
        val claim = receipt.optString("navigationClaim").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        receipt.put("navigationClaim", claim); write(value); claim
    }
    fun acknowledgeNavigation(ticket: String, claim: String) = storage.snapshotTransaction {
        val value = load(); val receipt = value.getJSONArray("receipts").objects().singleOrNull {
            it.getString("ticket") == ticket && it.optString("navigationClaim") == claim && !it.getBoolean("consumed")
        } ?: return@snapshotTransaction
        receipt.put("consumed", true); write(value)
    }
}
