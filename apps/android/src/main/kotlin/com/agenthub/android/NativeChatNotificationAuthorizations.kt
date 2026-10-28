package com.agenthub.android

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Installation-local encrypted retained authority. Never export/activate in migration or silently replace an original. */
internal class NativeChatNotificationAuthorizations(private val storage: NativeSessionStorage) {
    companion object {
        const val RECORD = "native-chat-notification-authorizations-v1"
        fun validateEncoded(raw: String) { decode(raw) }
        private fun decode(raw: String): List<NativeChatNotificationSavedConnection> {
            require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
            val value = JSONObject(raw); NativeChatNotificationWire.keys(value, setOf("version", "authorizations"))
            require(NativeChatNotificationWire.long(value, "version") == 1L)
            val list = value.getJSONArray("authorizations"); require(list.length() <= 128)
            return (0 until list.length()).map { NativeChatNotificationSavedConnection.decode(list.getJSONObject(it)) }.also { rows ->
                require(rows.map { it.snapshot.authorizationFingerprint }.distinct().size == rows.size)
            }
        }
    }
    private fun io() = check(Looper.myLooper() != Looper.getMainLooper())
    fun all(): List<NativeChatNotificationSavedConnection> { io(); return storage.snapshotTransaction { storage.read(RECORD)?.let(::decode).orEmpty() } }
    fun retain(connection: NativeChatNotificationSavedConnection) {
        io(); storage.snapshotTransaction { storage.operationTransaction {
            val old = storage.read(RECORD)?.let(::decode).orEmpty()
            val found = old.singleOrNull { it.snapshot.authorizationFingerprint == connection.snapshot.authorizationFingerprint }
            // Verified route/name refresh may change transport hints, never the retained original authorization.
            if (found != null) return@operationTransaction
            val encoded = NativeChatRecoveryCanonical.value(JSONObject().put("version", 1).put("authorizations", JSONArray((old + connection).map { it.json() })))
            validateEncoded(encoded); storage.write(RECORD, encoded)
        } }
    }
}
