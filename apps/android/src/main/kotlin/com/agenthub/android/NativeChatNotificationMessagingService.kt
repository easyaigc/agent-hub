package com.agenthub.android

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Only encrypted data-only payloads. No remote command, pairing, history read or fallback delivery. */
class NativeChatNotificationMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        try {
            val config = NativeChatFirebaseConfiguration.current(applicationContext) ?: return
            require(token.length in 1..4096 && token.all { it.code in 33..126 })
            val storage = NativeSessionStorage(applicationContext, existingKeyOnly = true, existingNamespaceOnly = true)
            if (NativeChatNotificationPushState(storage).enqueueToken(token, config.fingerprint))
                NativeChatNotificationPushJobs.schedule(applicationContext)
        } catch (_: Exception) { /* Preserve original encrypted state; never log vendor tokens. */ }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        try {
            val config = NativeChatFirebaseConfiguration.current(applicationContext) ?: return
            if (message.notification != null || message.data.keys != setOf("agent_hub_notification")) return
            val raw = message.data.getValue("agent_hub_notification")
            if (raw.toByteArray(Charsets.UTF_8).size !in 1..4096) return
            val storage = NativeSessionStorage(applicationContext, existingKeyOnly = true, existingNamespaceOnly = true)
            runBlocking { NativeChatNotificationPushDispatcher.receive(applicationContext, storage, config.fingerprint, JSONObject(raw)) }
        } catch (_: Exception) { /* No plaintext fallback or automatic retry after an uncertain post. */ }
    }
}
