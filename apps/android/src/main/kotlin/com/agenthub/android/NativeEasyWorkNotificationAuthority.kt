package com.agenthub.android

import org.json.JSONObject

internal class NativeEasyWorkNoticeAuthority(val client: MobileClient, val epoch: String) {
    val scope get() = requireNotNull(client.scope)
    override fun toString() = "NativeEasyWorkNoticeAuthority(redacted)"
    companion object {
        fun saved(storage: NativeSessionStorage): List<MobileClient> = storage.metadata()?.optJSONArray("clients")?.objects().orEmpty()
            .map(::decodeClient).filter { !it.isDemo && it.scope?.agentId == "easywork" && it.agentConnection != null && it.migrationEasyWork == null }
        fun capture(storage: NativeSessionStorage, client: MobileClient): NativeEasyWorkNoticeAuthority {
            require(!client.isDemo && client.scope?.agentId == "easywork" && client.migrationEasyWork == null)
            val connection = requireNotNull(client.agentConnection)
            require(sameScope(client.scope, connection.target))
            val credential = EasyWorkCredential.decode(JSONObject(requireNotNull(storage.read("easywork-credential:${connection.credentialRef}"))))
            require(sameScope(client.scope, credential.binding.target.scope()))
            val rawIdentity = JSONObject(requireNotNull(storage.read("easywork-device-identity")))
            val identity = EasyWorkIdentity(rawIdentity.getString("deviceId"), rawIdentity.getString("privateKey"), rawIdentity.getString("publicKey"))
            credential.binding.verify(identity)
            credential.grantRef?.let { EasyWorkAuthorizationCoordinator(storage).assertAuthorized(it, credential.binding.json) }
            val epoch = NativeChatRecoveryCanonical.hash(JSONObject().put("target", CodexCommandCodec.target(requireNotNull(client.scope)))
                .put("credentialRef", connection.credentialRef).put("binding", credential.binding.json)
                .put("key", NativeChatNotificationWire.hash(credential.privateKey)).put("grantRef", credential.grantRef))
            return NativeEasyWorkNoticeAuthority(client, epoch)
        }
    }
    fun current(storage: NativeSessionStorage): Boolean = saved(storage).singleOrNull {
        it.id == client.id && sameScope(it.scope, client.scope) && it.agentConnection?.credentialRef == client.agentConnection?.credentialRef
    }?.let { capture(storage, it).epoch == epoch } == true
}
