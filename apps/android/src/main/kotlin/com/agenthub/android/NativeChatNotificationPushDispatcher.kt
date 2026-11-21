package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One FCM service and one exact binding owner. Ambiguous IDs are rejected, never broadcast. */
internal object NativeChatNotificationPushDispatcher {
    suspend fun receive(context: Context, storage: NativeSessionStorage, configuration: String, value: JSONObject): String = withContext(Dispatchers.IO) {
        val envelope = NativeChatPushEnvelopeCodec.decode(value)
        val codex = NativeChatNotificationPushState(storage).rows().count { it.optString("stage") == "ready" && it.optString("bindingId") == envelope.bindingId }
        val easyWork = NativeEasyWorkNotificationStore(storage).rows().count { it.optString("phase") == "ready" && it.optString("bindingId") == envelope.bindingId }
        when (NativeChatPushLifecycle.owner(codex, easyWork)) {
            NativeChatPushOwner.NONE -> "unknown-or-ambiguous-binding"
            NativeChatPushOwner.CODEX -> NativeChatNotificationPushReceiver(context, storage, configuration).receive(value)
            NativeChatPushOwner.EASYWORK -> NativeEasyWorkNotificationReceiver(context, storage, configuration).receive(envelope)
        }
    }
}
