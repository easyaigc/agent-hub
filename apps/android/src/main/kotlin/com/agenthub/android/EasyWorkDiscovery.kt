package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

data class DiscoveredEasyWorkHost(val id: String,val name: String,val endpoint: String,val deviceId: String?,val known: Boolean=false,val reachable: Boolean=false,val networkKind: String?=null)

/** TXT records only suggest routes. Saved routes are refreshed by pinned target-bound reads. */
class EasyWorkDiscovery(context: Context,private val session: MobileSession,private val storage: NativeSessionStorage) {
    private val context=context.applicationContext
    private val queue=AndroidNsdQueue()
    private val platform=AndroidNsdPlatform(context)
    @Volatile private var current: Generation?=null
    private val advertised=mutableMapOf<String,DiscoveredEasyWorkHost>()
    private var vpnHosts=emptyList<DiscoveredEasyWorkHost>()
    internal fun offerVPN(rows: List<DiscoveredEasyWorkHost>) {vpnHosts=rows.take(32);rebuild();if(current?.sdk?.isRetiring==false)scan()}
    var hosts by mutableStateOf<List<DiscoveredEasyWorkHost>>(emptyList());private set
    val needsPermission get()=Build.VERSION.SDK_INT>=37&&context.checkSelfPermission(CodexHostDiscovery.LOCAL_PERMISSION)!=PackageManager.PERMISSION_GRANTED
    private inner class Generation {
        val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate+session.easyWorkMigrationContext)
        var scanner: Job?=null
        var retirement: NativeEasyWorkMigrationOwners.Owner?=null
        val sdk: NativeNsdLifetime<NsdServiceInfo> =NativeNsdLifetime(platform,queue,"_easywork-host._tcp.",
            resolved={info->if(current===this&&!sdkRetiring())resolvedHost(info)},
            lost={info->if(current===this&&!sdkRetiring()){advertised.remove(info.serviceName);rebuild()}},
            networkChanged={if(current===this&&!sdkRetiring())scan()})
        private fun sdkRetiring()=sdk.isRetiring
        fun stop() {
            tasks.cancel();sdk.stop()
            queue.post {if(current===this){current=null;advertised.clear()}}
        }
        suspend fun join() {
            // Neither a cancelled Job nor a fenced projection proves SDK teardown.
            tasks.coroutineContext[Job]!!.join()
            sdk.awaitStopped()
        }
    }
    fun start() {
        stop()
        if(session.migrationPaused||session.easyWorkMigrationContext[NativeEasyWorkMigrationOwners]!!.stopping)return
        val generation=Generation()
        generation.retirement=session.easyWorkMigrationContext[NativeEasyWorkMigrationOwners]!!.register(generation::stop,generation::join)
        current=generation;advertised.clear();rebuild()
        if(needsPermission)return // No SDK request was made in this generation.
        generation.sdk.start()
        generation.tasks.launch {while(current===generation&&!generation.sdk.isRetiring){scan();delay(10_000)}}
    }
    fun stop() {current?.retirement?.retire()}
    private fun resolvedHost(info: NsdServiceInfo) {
        try {
            @Suppress("DEPRECATION") val address=info.host?.hostAddress?:error("缺少地址");require(info.port in 1..65535)
            val endpoint=CodexEndpoints.local("https://"+if(':' in address)"[$address]:${info.port}" else "$address:${info.port}")
            val id=info.attributes["deviceId"]?.toString(Charsets.UTF_8)?.takeIf {it.isNotBlank()&&it.length<=512}
            advertised[info.serviceName]=DiscoveredEasyWorkHost(info.serviceName,info.serviceName.take(256),endpoint,id);rebuild();scan()
        }catch(_: Exception){}
    }
    private fun rebuild() {
        val saved=session.clients.filter { it.agentConnection?.target?.agentId=="easywork" }
        hosts=saved.map { c->hosts.firstOrNull { it.known&&it.id==c.id }?.copy(name=c.name)?:DiscoveredEasyWorkHost(c.id,c.name,c.agentConnection!!.candidates.firstOrNull { it.transportId=="lan-wss" }?.endpoint.orEmpty(),c.scope?.hostId,true) }+(vpnHosts+advertised.values).distinctBy { it.deviceId ?: it.id }.filter { h->saved.none { it.scope?.hostId==h.deviceId||it.agentConnection!!.candidates.any { c->c.endpoint==h.endpoint } } }.sortedBy { it.name }
    }
    fun scan() {
        val generation=current?:return
        if(generation.sdk.isRetiring||needsPermission||generation.scanner?.isActive==true)return
        rebuild()
        generation.scanner=generation.tasks.launch {
            session.clients.filter { it.agentConnection?.target?.agentId=="easywork"&&it.agentConnection.preference!="relay-only" }.map { client->async {
                try {
                    val connection=client.agentConnection!!;val credential=withContext(Dispatchers.IO) { EasyWorkCredential.decode(JSONObject(storage.read("easywork-credential:${connection.credentialRef}")?:error("配对已失效"))) }
                    val pin=connection.candidates.firstOrNull { it.transportId=="lan-wss" }?.peerFingerprint
                    val suggested=if(pin!=null)advertised.values.filter { it.deviceId==connection.target.hostId }.flatMap { EasyWorkPairing.directCandidates(listOf(it.endpoint),fingerprint=pin) } else emptyList()
                    val candidates=(suggested+connection.candidates).distinctBy { it.endpoint }
                    val reachable=EasyWorkPairing.reachable(candidates,credential.binding)
                    val first=reachable.firstOrNull { it.transportId=="lan-wss" }?:return@async
                    // Recheck the winning candidate because unchanged candidates alone do not prove reachability.
                    EasyWorkPairing.readHost(first.endpoint,first.peerFingerprint,credential.binding.target,credential.binding.desktopPublicKey)
                    if(current===generation&&!generation.sdk.isRetiring&&session.updateEasyWorkCandidates(client,credential.binding,reachable))hosts=hosts.map { if(it.id==client.id)it.copy(endpoint=first.endpoint,reachable=true) else it }
                } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { if(current===generation&&!generation.sdk.isRetiring)hosts=hosts.map { if(it.id==client.id)it.copy(reachable=false) else it } }
            } }.awaitAll()
        }
    }
}
