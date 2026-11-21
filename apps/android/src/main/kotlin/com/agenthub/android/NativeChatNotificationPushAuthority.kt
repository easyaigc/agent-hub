package com.agenthub.android

/** Re-read the installation's saved credential and verified metadata, never a retained token alone. */
internal class NativeChatNotificationPushAuthority(private val storage: NativeSessionStorage) {
    fun current(authority: NativeChatNotificationSavedConnection): Boolean {
        val vault = CodexConnectionVault(storage)
        val privateClients = vault.clients()
        val metadata = storage.metadata()?.optJSONArray("clients")?.let { array ->
            (0 until array.length()).map { decodeClient(array.getJSONObject(it)) }
        }.orEmpty().filter { client -> privateClients.none { it.id == client.id } }
        return (privateClients + metadata).any { client -> authority.matches(client) &&
            NativeChatNotificationSavedConnection.capture(client, storage.token(client.id)).snapshot.credentialEpoch == authority.snapshot.credentialEpoch }
    }
}
