package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Installation-local identity; separate from EasyWork and excluded from profile migration. */
internal class NativeChatNotificationIdentity private constructor(val deviceId: String, val publicKey: String) {
    val fingerprint = NativeChatNotificationWire.hash(publicKey)
    override fun toString() = "NativeChatNotificationIdentity(redacted)"
    companion object {
        const val RECORD = "native-chat-notification-identity-v1"
        // Fixed installation record IDs avoid an identity -> controller/store dependency cycle.
        const val FOREGROUND_RECORD = "native-chat-notification-foreground-v1"
        const val RECOVERY_RECORD = "native-chat-notification-recovery-v2"
        const val PUSH_RECORD = "native-chat-notification-push-v1"
        suspend fun load(storage: NativeSessionStorage): NativeChatNotificationIdentity = withContext(Dispatchers.IO) {
            storage.snapshotTransaction { storage.operationTransaction {
                storage.read(RECORD)?.let {
                    val identity = decode(it)
                    check(!JSONObject(it).getBoolean("journalBound") || storage.read(FOREGROUND_RECORD) != null || storage.read(RECOVERY_RECORD) != null || storage.read(PUSH_RECORD) != null || storage.read("native-easywork-notifications-v1") != null) { "Notification journal missing; identity preserved" }
                    return@operationTransaction identity
                }
                check(storage.read(FOREGROUND_RECORD) == null && storage.read(RECOVERY_RECORD) == null && storage.read(PUSH_RECORD) == null && storage.read("native-easywork-notifications-v1") == null) { "Notification identity missing; foreground state preserved" }
                val key = X25519PrivateKeyParameters(SecureRandom())
                val privateBytes = key.encoded
                try {
                    val raw = JSONObject().put("version", 1).put("deviceId", "android-" + UUID.randomUUID())
                        .put("journalBound", false)
                        .put("privateKey", Base64.getEncoder().encodeToString(privateBytes))
                        .put("publicKey", Base64.getEncoder().encodeToString(key.generatePublicKey().encoded)).toString()
                    val identity = decode(raw); storage.write(RECORD, raw); identity
                } finally { privateBytes.fill(0) }
            } }
        }
        internal suspend fun existing(storage: NativeSessionStorage): NativeChatNotificationIdentity = withContext(Dispatchers.IO) {
            storage.snapshotTransaction { decode(requireNotNull(storage.read(RECORD))) }
        }
        /** Read existing installation key only; receiving never creates or rotates an identity. */
        internal fun openPush(storage: NativeSessionStorage, envelope: NativeChatPushEnvelope,
            peerPublicKey: String, target: NativeChatNoticeTarget): NativeChatNotificationEvent = storage.snapshotTransaction {
            val raw = requireNotNull(storage.read(RECORD)); decode(raw)
            val bytes = NativeChatNotificationWire.key(JSONObject(raw).getString("privateKey"))
            try { NativeChatPushEnvelopeCodec.open(envelope, bytes, peerPublicKey, target) }
            finally { bytes.fill(0) }
        }
        internal fun openScopedPush(storage: NativeSessionStorage, envelope: NativeChatPushEnvelope, peerPublicKey: String): JSONObject = storage.snapshotTransaction {
            val raw = requireNotNull(storage.read(RECORD)); decode(raw)
            val bytes = NativeChatNotificationWire.key(JSONObject(raw).getString("privateKey"))
            try { NativeChatPushEnvelopeCodec.openPayload(envelope, bytes, peerPublicKey) } finally { bytes.fill(0) }
        }
        /** Mark before the first journal publication. A crash between files blocks, never silently re-registers. */
        internal fun bind(storage: NativeSessionStorage, identity: NativeChatNotificationIdentity) {
            val raw = requireNotNull(storage.read(RECORD)); val saved = decode(raw)
            check(saved.deviceId == identity.deviceId && saved.fingerprint == identity.fingerprint)
            val value = JSONObject(raw)
            if (!value.getBoolean("journalBound")) storage.write(RECORD, value.put("journalBound", true).toString())
        }
        private fun decode(raw: String): NativeChatNotificationIdentity {
            require(raw.toByteArray(Charsets.UTF_8).size <= 2048)
            val v = JSONObject(raw); NativeChatNotificationWire.keys(v, setOf("version", "deviceId", "publicKey", "privateKey", "journalBound"))
            require(NativeChatNotificationWire.long(v, "version") == 1L)
            require(v.get("journalBound") is Boolean)
            val public = NativeChatNotificationWire.text(v, "publicKey", 64)
            val privateBytes = NativeChatNotificationWire.key(NativeChatNotificationWire.text(v, "privateKey", 64))
            val publicBytes = NativeChatNotificationWire.key(public)
            try { require(X25519PrivateKeyParameters(privateBytes, 0).generatePublicKey().encoded.contentEquals(publicBytes)) }
            finally { privateBytes.fill(0); publicBytes.fill(0) }
            return NativeChatNotificationIdentity(NativeChatNotificationWire.device(NativeChatNotificationWire.text(v, "deviceId", 128)), public)
        }
    }
}
