package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

data class DiscoveredEasyWorkHost(val id: String,val name: String,val endpoint: String,val deviceId: String?,val known: Boolean=false,val reachable: Boolean=false,val networkKind: String?=null)

/** TXT records only suggest routes. Saved routes are refreshed by pinned target-bound reads. */
class EasyWorkDiscovery(context: Context,private val session: MobileSession,private val storage: NativeSessionStorage) {
    private val context=context.applicationContext
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val network=context.getSystemService(ConnectivityManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var active=false
    private var generation=UUID.randomUUID()
    private var listener: NsdManager.DiscoveryListener?=null
    private var networkListener: ConnectivityManager.NetworkCallback?=null
    private var scanner: Job?=null
    private val advertised=mutableMapOf<String,DiscoveredEasyWorkHost>()
    private var vpnHosts=emptyList<DiscoveredEasyWorkHost>()
    internal fun offerVPN(rows: List<DiscoveredEasyWorkHost>) { vpnHosts=rows.take(32);rebuild();if(active)scan() }
    private val pending=java.util.ArrayDeque<NsdServiceInfo>()
    private var resolver: NsdManager.ResolveListener?=null
    var hosts by mutableStateOf<List<DiscoveredEasyWorkHost>>(emptyList());private set
    val needsPermission get()=Build.VERSION.SDK_INT>=37&&context.checkSelfPermission(CodexHostDiscovery.LOCAL_PERMISSION)!=PackageManager.PERMISSION_GRANTED
    private fun post(epoch: UUID,body: ()->Unit) { main.post { if(active&&generation==epoch)body() } }
    fun start() {
        stop();active=true;val epoch=generation;rebuild();if(needsPermission)return
        val discovery=object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String,code: Int) {}
            override fun onStopDiscoveryFailed(type: String,code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { post(epoch) { if(pending.size<32) { pending.add(info);resolve(epoch) } } }
            override fun onServiceLost(info: NsdServiceInfo) { post(epoch) { advertised.remove(info.serviceName);rebuild() } }
        }
        listener=discovery;try { nsd.discoverServices("_easywork-host._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery) } catch(_: Exception) {}
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { post(epoch) { scan() } }
            override fun onLost(n: Network) { post(epoch) { scan() } }
            override fun onLinkPropertiesChanged(n: Network,p: android.net.LinkProperties) { post(epoch) { scan() } }
        }
        networkListener=callback;try { network.registerNetworkCallback(NetworkRequest.Builder().build(),callback) } catch(_: Exception) { networkListener=null }
        tasks.launch { while(active&&generation==epoch) { scan();delay(10_000) } }
    }
    fun stop() {
        active=false;generation=UUID.randomUUID();tasks.coroutineContext.cancelChildren();scanner=null;pending.clear();advertised.clear()
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch(_: Exception) {} };listener=null
        networkListener?.let { try { network.unregisterNetworkCallback(it) } catch(_: Exception) {} };networkListener=null
        if(Build.VERSION.SDK_INT>=34)resolver?.let { try { nsd.stopServiceResolution(it) } catch(_: Exception) {} }
    }
    @Suppress("DEPRECATION") private fun resolve(epoch: UUID) {
        if(resolver!=null||pending.isEmpty()||generation!=epoch)return
        val info=pending.removeFirst();var expired=false
        val resolve=object: NsdManager.ResolveListener {
            private fun done(body: ()->Unit={}) { main.post {
                if(resolver!==this)return@post;resolver=null
                if(active&&generation==epoch&&!expired)body()
                if(active)resolve(generation)
            } }
            override fun onResolveFailed(info: NsdServiceInfo,code: Int)=done()
            override fun onResolutionStopped(info: NsdServiceInfo)=done()
            override fun onStopResolutionFailed(info: NsdServiceInfo,code: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo)=done {
                try {
                    val address=info.host?.hostAddress?:error("缺少地址");require(info.port in 1..65535)
                    val endpoint=CodexEndpoints.local("https://"+if(':' in address)"[$address]:${info.port}" else "$address:${info.port}")
                    val id=info.attributes["deviceId"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank()&&it.length<=512 }
                    advertised[info.serviceName]=DiscoveredEasyWorkHost(info.serviceName,info.serviceName.take(256),endpoint,id);rebuild();scan()
                } catch(_: Exception) {}
            }
        }
        resolver=resolve;main.postDelayed({ if(resolver===resolve) { expired=true;if(Build.VERSION.SDK_INT>=34)try { nsd.stopServiceResolution(resolve) } catch(_: Exception) {} } },6_000)
        try { nsd.resolveService(info,resolve) } catch(_: Exception) { resolver=null;resolve(epoch) }
    }
    private fun rebuild() {
        val saved=session.clients.filter { it.agentConnection?.target?.agentId=="easywork" }
        hosts=saved.map { c->hosts.firstOrNull { it.known&&it.id==c.id }?.copy(name=c.name)?:DiscoveredEasyWorkHost(c.id,c.name,c.agentConnection!!.candidates.firstOrNull { it.transportId=="lan-wss" }?.endpoint.orEmpty(),c.scope?.hostId,true) }+(vpnHosts+advertised.values).distinctBy { it.deviceId ?: it.id }.filter { h->saved.none { it.scope?.hostId==h.deviceId||it.agentConnection!!.candidates.any { c->c.endpoint==h.endpoint } } }.sortedBy { it.name }
    }
    fun scan() {
        if(!active||needsPermission||scanner?.isActive==true)return
        val epoch=generation;rebuild()
        scanner=tasks.launch {
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
                    if(active&&epoch==generation&&session.updateEasyWorkCandidates(client,credential.binding,reachable))hosts=hosts.map { if(it.id==client.id)it.copy(endpoint=first.endpoint,reachable=true) else it }
                } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { if(active&&epoch==generation)hosts=hosts.map { if(it.id==client.id)it.copy(reachable=false) else it } }
            } }.awaitAll()
        }
    }
}
