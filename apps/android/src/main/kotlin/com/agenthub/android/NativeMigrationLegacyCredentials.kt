package com.agenthub.android

/** Manual/QR credentials are raw storage strings, not JSON or private-claim authority. */
internal object NativeMigrationLegacyCredentials {
    const val PREFIX="token:"
    fun validate(records: Map<String,String>,clients: List<MobileClient>): Set<String> {
        val names=records.keys.filter {it.startsWith(PREFIX)}.toSet()
        for(name in names) {
            val id=name.removePrefix(PREFIX)
            val client=clients.single {it.id==id}
            require(!client.isDemo&&client.scope?.agentId=="codex")
            // Same nonempty credential boundary as manual connection; preserve exact bytes.
            val secret=records.getValue(name)
            require(secret.length in 32..512&&secret.none {it.isWhitespace()})
        }
        return names
    }
}
