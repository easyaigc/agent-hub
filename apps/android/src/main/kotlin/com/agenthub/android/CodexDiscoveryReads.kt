package com.agenthub.android

/** Named, read-only discovery operations; authentication and TLS stay in native transports. */
internal interface CodexDiscoveryReads {
    suspend fun descriptor(endpoint: String): CodexDescriptor
    suspend fun peers(source: CodexPublicSource,endpoint: String): List<Pair<String,List<String>>>
    suspend fun legacyPeers(client: MobileClient,token: String): List<Pair<String,List<String>>>
    suspend fun savedDescriptor(client: MobileClient,token: String): CodexDescriptor
    suspend fun easyWork(endpoint: String): DiscoveredEasyWorkHost
}

internal object NativeCodexDiscoveryReads: CodexDiscoveryReads {
    override suspend fun descriptor(endpoint: String)=CodexDiscoveryAPI.discover(endpoint)
    override suspend fun peers(source: CodexPublicSource,endpoint: String)=CodexDiscoveryAPI.peers(source,endpoint)
    override suspend fun legacyPeers(client: MobileClient,token: String)=CodexDiscoveryAPI.legacyPeers(client,token)
    override suspend fun savedDescriptor(client: MobileClient,token: String)=NativeHostReads { token }.descriptor(client)
    override suspend fun easyWork(endpoint: String): DiscoveredEasyWorkHost {
        val host=EasyWorkPairing.readHost(endpoint)
        return DiscoveredEasyWorkHost("vpn-"+host.target.desktopDeviceId,host.value.getString("name"),endpoint,host.target.desktopDeviceId,networkKind="vpn")
    }
}
