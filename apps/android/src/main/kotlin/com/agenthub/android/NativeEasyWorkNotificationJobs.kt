package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject

internal object NativeEasyWorkNotificationJobs {
    data class Outcome(val complete: Boolean, val retry: Boolean)
    suspend fun synchronize(context: Context, storage: NativeSessionStorage, sync: NativeChatPushSync): Outcome {
        val notices = NativeEasyWorkNotificationStore(storage)
        val rows = notices.rows()
        if (rows.isEmpty()) return Outcome(true, false)
        // The session/login helpers may use existing keys but must not provision any missing original key here.
        val raw = JSONObject(requireNotNull(storage.read("easywork-device-identity")))
        EasyWorkDPoP.loadExisting(EasyWorkIdentity(raw.getString("deviceId"), raw.getString("privateKey"), raw.getString("publicKey")))
        val sessions = EasyWorkSessions(storage, EasyWorkLogin(storage))
        val owned = mutableSetOf<EasyWorkRemoteSession>()
        val adapter = EasyWorkAgentClient(EasyWorkChannels { client -> sessions.session(client).also { owned.add(it) } })
        val runtime = NativeEasyWorkNotificationRuntime(context, storage)
        val push = NativeChatNotificationPushState(storage)
        val prefs = NativeChatNotificationStore(storage)
        var complete = true; var retry = false
        try {
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (!push.syncCurrent(sync.id)) return Outcome(false, true)
                val target = NativeTaskNotificationCodec.target(row.getJSONObject("target"))
                val client = NativeEasyWorkNoticeAuthority.saved(storage).singleOrNull {
                    it.id == row.getString("clientId") && sameScope(it.scope, target) && it.agentConnection?.credentialRef == row.getString("credentialRef")
                } ?: continue
                val authority = NativeEasyWorkNoticeAuthority.capture(storage, client)
                val preferences = prefs.preferences()
                val current = { push.syncCurrent(sync.id) && authority.current(storage) && prefs.preferences() == preferences }
                val result = runtime.synchronize(client, adapter, true, current)
                complete = complete && result in setOf("push-registered", "disabled")
                retry = retry || (result !in setOf("push-registered", "disabled", "easywork-original-unconfirmed-no-replay", "push-provider-unsupported", "push-not-configured", "push-invalid-configuration"))
            }
        } finally { withContext(NonCancellable) { sessions.closeAll(); owned.forEach { it.closeAndJoinForMigration() } } }
        return Outcome(complete, retry)
    }
}
