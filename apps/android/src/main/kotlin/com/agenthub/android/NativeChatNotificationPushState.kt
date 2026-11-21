package com.agenthub.android

import org.json.JSONObject

internal class NativeChatPushSync(val id: String, val token: String, val configuration: String) {
    override fun toString() = "NativeChatPushSync(redacted)"
}

/** Installation-only encrypted state. No token, binding or key enters an Intent or a diagnostic. */
internal class NativeChatNotificationPushState(private val storage: NativeSessionStorage) {
    companion object { const val RECORD = NativeChatNotificationIdentity.PUSH_RECORD }
    private fun load(): JSONObject = storage.read(RECORD)?.let { raw ->
        require(raw.toByteArray().size <= 1024 * 1024)
        JSONObject(raw).also {
            NativeChatNotificationWire.keys(it, setOf("version", "tokenHash", "configuration", "targets"), setOf("pendingSync"))
            require(it.getInt("version") == 1 && it.getJSONObject("targets").length() <= 64)
        }
    } ?: JSONObject().put("version", 1).put("tokenHash", "").put("configuration", "").put("targets", JSONObject())
    private fun id(target: NativeChatNoticeTarget) = NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.target(target))
    fun row(target: NativeChatNoticeTarget): JSONObject? = storage.snapshotTransaction { load().getJSONObject("targets").optJSONObject(id(target)) }
    fun rows(): List<JSONObject> = storage.snapshotTransaction {
        val rows = load().getJSONObject("targets"); rows.keys().asSequence().map { rows.getJSONObject(it) }.toList()
    }
    fun token(hash: String, configuration: String, rotation: Boolean = false) = storage.snapshotTransaction {
        require(chatNoticeHash(hash) && chatNoticeHash(configuration))
        val state = load()
        // An unsolicited callback cannot enroll a different Firebase project.
        if (!rotation || state.getString("configuration") == configuration) {
            storage.write(RECORD, state.put("tokenHash", hash).put("configuration", configuration).toString())
        }
    }
    fun matches(hash: String, configuration: String): Boolean = storage.snapshotTransaction {
        val value = load(); value.getString("tokenHash") == hash && value.getString("configuration") == configuration
    }
    fun save(target: NativeChatNoticeTarget, row: JSONObject) = storage.snapshotTransaction {
        val state = load(); val rows = state.getJSONObject("targets")
        val old = rows.optJSONObject(id(target))
        // Preserve the original v1 checkpoint independently of every new v2 operation.
        old?.optJSONObject("legacyOriginal")?.let { row.put("legacyOriginal", it) }
        if (old != null && old.optInt("protocol", 1) == 1 && row.optInt("protocol", 1) == 2)
            row.put("legacyOriginal", JSONObject(old.toString()))
        rows.put(id(target), row); require(rows.length() <= 64)
        storage.write(RECORD, state.toString())
    }
    /** Persist the actual pending token encrypted before scheduling. Callback admission is exact-project only. */
    fun enqueueToken(token: String, configuration: String, force: Boolean = false): Boolean = storage.snapshotTransaction {
        require(token.length in 1..4096 && token.all { it.code in 33..126 } && chatNoticeHash(configuration))
        val state = load()
        if (state.getString("configuration") != configuration || (state.getJSONObject("targets").length() == 0 && NativeEasyWorkNotificationStore(storage).rows().isEmpty())) return@snapshotTransaction false
        val hash = NativeChatNotificationWire.hash(token)
        val previous = state.optJSONObject("pendingSync")
        if (state.getString("tokenHash") == hash && previous == null && !force) return@snapshotTransaction false
        if (previous?.getString("token") == token && previous.getString("configuration") == configuration) return@snapshotTransaction true
        NativeEasyWorkNotificationFence.invalidate(storage)
        val notices = NativeChatNotificationStore(storage)
        state.getJSONObject("targets").let { targets -> targets.keys().forEach { key ->
            notices.revoke(NativeChatNotificationCodec.target(targets.getJSONObject(key).getJSONObject("target")))
        } }
        state.put("tokenHash", hash).put("pendingSync", JSONObject().put("id", java.util.UUID.randomUUID().toString())
            .put("token", token).put("configuration", configuration))
        storage.write(RECORD, state.toString()); true
    }
    fun pendingSync(): NativeChatPushSync? = storage.snapshotTransaction {
        load().optJSONObject("pendingSync")?.let {
            NativeChatNotificationWire.keys(it, setOf("id", "token", "configuration"))
            val token = NativeChatNotificationWire.text(it, "token", 4096); require(token.all { c -> c.code in 33..126 })
            val config = NativeChatNotificationWire.text(it, "configuration"); require(chatNoticeHash(config))
            NativeChatPushSync(NativeChatNotificationWire.uuid(it.getString("id")), token, config)
        }
    }
    fun syncCurrent(id: String) = pendingSync()?.id == id
    fun finishSync(id: String) = storage.snapshotTransaction {
        val state = load()
        if (state.optJSONObject("pendingSync")?.optString("id") == id) {
            state.remove("pendingSync"); storage.write(RECORD, state.toString())
        }
    }
    fun resumeWaiting(target: NativeChatNoticeTarget, operationId: String) = storage.snapshotTransaction {
        val row = row(target) ?: return@snapshotTransaction
        if (row.optString("operationId") == operationId) save(target, row.apply { remove("waitingEnded") })
    }
    fun endWaiting(target: NativeChatNoticeTarget, operationId: String) = storage.snapshotTransaction {
        val row = row(target) ?: JSONObject().put("target", NativeChatNotificationCodec.target(target))
            .put("operationId", operationId).put("stage", "unknown")
        require(row.getString("operationId") == operationId)
        save(target, row.put("waitingEnded", true))
    }

}
