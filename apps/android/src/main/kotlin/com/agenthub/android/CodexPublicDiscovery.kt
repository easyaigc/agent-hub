package com.agenthub.android

import com.agenthub.core.DomainContract
import com.agenthub.core.PrivateConnectionRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Bounded verified address hints are independent from authenticated clients and credentials. */
internal class CodexPublicDiscovery(private val storage: NativeSessionStorage?,
    private val reads: CodexDiscoveryReads=NativeCodexDiscoveryReads,
    private val budget: Semaphore=Semaphore(4),
    private val clock: ()->Long=android.os.SystemClock::elapsedRealtime) {
    private var loaded=false
    private val sources=mutableListOf<CodexPublicSource>()
    private val scanLock=Mutex()
    private data class Seen(val row: DiscoveredCodexHost,val at: Long)
    private val seen=linkedMapOf<String,Seen>()
    data class Verified(val row: DiscoveredCodexHost,val descriptor: CodexDescriptor)
    data class Result(val codex: List<DiscoveredCodexHost>,val easywork: List<DiscoveredEasyWorkHost>,val verified: Verified?=null)

    /** These lifetimes describe a hint, never authenticated online or execution readiness. */
    fun currentHints(): List<DiscoveredCodexHost> {
        val now=clock();seen.entries.removeAll { now-it.value.at !in 0 until 60_000 }
        return seen.values.sortedBy { it.row.name }.take(PrivateConnectionRules.peerLimit).map { value->
            value.row.copy(reachable=now-value.at<15_000)
        }
    }
    fun clearVerifiedHints() { seen.clear() }
    private suspend fun load() {
        if(loaded)return
        try {
            val raw=withContext(Dispatchers.IO) { storage?.read("codex-public-discovery-sources-v1") } ?: run { loaded=true;return }
            require(raw.length<=65_536)
            val rows=JSONArray(raw).objects();require(rows.size<=PrivateConnectionRules.sourceLimit)
            val decoded=rows.map { row->
                val target=row.getJSONObject("target");require(DomainContract.validate(target.toString(),"target")==null)
                val scope=HostReadCodec.target(target)!!;require(scope.agentId=="codex")
                val fingerprint=row.getString("fingerprint");require(fingerprint.matches(Regex("[a-f0-9]{64}")))
                val endpoints=row.getJSONArray("endpoints").strings().map(CodexEndpoints::local)
                require(endpoints.size in 1..PrivateConnectionRules.sourceEndpointLimit)
                CodexPublicSource(scope,fingerprint,orderedDiscoveryEndpoints(endpoints))
            }
            sources.addAll(decoded.distinctBy { it.target.hostId });loaded=true
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { loaded=true /* Optional hints never replace or remove a credential. */ }
    }
    private fun remember(host: CodexDescriptor,endpoint: String) {
        sources.removeAll { it.target.hostId==host.target.hostId }
        sources.add(0,CodexPublicSource(host.target,host.fingerprint,orderedDiscoveryEndpoints(listOf(endpoint)+host.routes.map { it.endpoint }).take(PrivateConnectionRules.sourceEndpointLimit)))
        while(sources.size>PrivateConnectionRules.sourceLimit)sources.removeAt(sources.lastIndex)
        // Keep the optional persisted cache readable at its existing byte limit.
        val cached=sources.toMutableList()
        fun encode()=JSONArray(cached.map { JSONObject().put("target",encodeTarget(it.target)).put("fingerprint",it.fingerprint).put("endpoints",JSONArray(it.endpoints)) }).toString()
        var wire=encode()
        while(wire.toByteArray(Charsets.UTF_8).size>65_536&&cached.isNotEmpty()) {
            val last=cached.last()
            if(last.endpoints.size>1)cached[cached.lastIndex]=last.copy(endpoints=last.endpoints.dropLast(1)) else cached.removeAt(cached.lastIndex)
            wire=encode()
        }
        storage?.enqueue("codex-public-discovery-sources-v1",wire) { /* Credentials remain untouched. */ }
    }
    private fun record(row: DiscoveredCodexHost) {
        seen.remove(row.id);seen[row.id]=Seen(row,clock())
        while(seen.size>PrivateConnectionRules.peerLimit)seen.remove(seen.entries.minBy { it.value.at }.key)
    }
    private fun directorySources(clients: List<MobileClient>,fresh: Set<String>): List<CodexPublicSource> {
        val saved=clients.filter { !it.isDemo&&it.scope?.agentId=="codex"&&it.codexDirect!=null }.sortedByDescending { it.online }.take(PrivateConnectionRules.sourceLimit).mapNotNull { client->
            val direct=client.codexDirect!!
            if(!sameScope(direct.target,client.scope)||!direct.fingerprint.matches(Regex("[a-f0-9]{64}")))return@mapNotNull null
            val routes=(listOfNotNull(client.endpoint)+direct.routes.map { it.endpoint }).mapNotNull { runCatching { CodexEndpoints.local(it) }.getOrNull() }
            val prior=sources.firstOrNull { sameScope(it.target,direct.target)&&it.fingerprint==direct.fingerprint }
            val endpoints=orderedDiscoveryEndpoints(routes+prior?.endpoints.orEmpty()).take(PrivateConnectionRules.sourceEndpointLimit)
            if(endpoints.isEmpty())null else CodexPublicSource(direct.target,direct.fingerprint,endpoints)
        }
        val online=clients.filter { it.online }.map { it.id }.toSet()
        // Prefer a healthy saved source, then this scan's real descriptor proof,
        // before offline saved sources. Optional old hints cannot crowd out either.
        return (saved.filter { it.target.hostId in online }+sources.filter { it.target.hostId in fresh }+
            saved.filter { it.target.hostId !in online }+sources).distinctBy { it.target.hostId }.take(PrivateConnectionRules.sourceLimit)
    }
    private suspend fun readDirectory(source: CodexPublicSource,valid: ()->Boolean): List<Pair<String,List<String>>> = coroutineScope {
        val endpoints=orderedDiscoveryEndpoints(source.endpoints).take(PrivateConnectionRules.sourceEndpointLimit)
        val results=Channel<List<Pair<String,List<String>>>?>(endpoints.size.coerceAtLeast(1))
        val jobs=endpoints.map { endpoint->launch {
            val result=budget.withPermit {
                if(!valid())return@withPermit null
                try { reads.peers(source,endpoint) }
                catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { null }
            }
            results.send(result)
        } }
        try {
            repeat(endpoints.size) {
                val rows=results.receive()
                if(!valid())return@coroutineScope emptyList()
                if(!rows.isNullOrEmpty())return@coroutineScope rows.take(PrivateConnectionRules.peerLimit)
            }
            emptyList()
        } finally { jobs.forEach { it.cancel() };results.cancel() }
    }
    suspend fun scan(hints: List<DiscoveredCodexHost>,clients: List<MobileClient>,credential: (String)->String,valid: ()->Boolean): Result=
        scan(hints,clients,credential,{},valid)
    suspend fun scan(hints: List<DiscoveredCodexHost>,clients: List<MobileClient>,credential: (String)->String,
        publish: (Result)->Unit,valid: ()->Boolean): Result=scanLock.withLock { coroutineScope {
        val empty=Result(emptyList(),emptyList())
        if(!valid())return@coroutineScope empty
        load();currentCoroutineContext().ensureActive()
        val easywork=linkedMapOf<String,DiscoveredEasyWorkHost>()
        fun snapshot()=Result(currentHints(),easywork.values.sortedBy { it.name })
        fun emit(verified: Verified?=null) { if(valid())publish(snapshot().copy(verified=verified)) }
        val freshSources=mutableSetOf<String>()
        if(seen.isNotEmpty())emit()
        for(hint in hints.take(8)) {
            if(!valid())return@coroutineScope empty
            try {
                val descriptor=budget.withPermit { reads.descriptor(hint.endpoint) }
                require(descriptor.target.hostId==hint.id)
                require(hint.target==null||sameScope(hint.target,descriptor.target))
                require(hint.fingerprint==null||hint.fingerprint==descriptor.fingerprint)
                currentCoroutineContext().ensureActive();if(!valid())return@coroutineScope empty
                remember(descriptor,hint.endpoint)
                val row=hint.copy(name=descriptor.name,reachable=true,networkKind=hint.networkKind ?: "lan",target=descriptor.target,fingerprint=descriptor.fingerprint)
                record(row);freshSources.add(row.id);emit(Verified(row,descriptor))
            } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) {}
        }
        var peers=emptyList<Pair<String,List<String>>>()
        for(source in directorySources(clients,freshSources)) {
            if(!valid())return@coroutineScope empty
            val rows=readDirectory(source,valid)
            if(rows.isNotEmpty()) { peers=rows;break }
        }
        if(peers.isEmpty())for(client in clients.filter { !it.isDemo&&it.scope?.agentId=="codex" }.sortedByDescending { it.online }.take(PrivateConnectionRules.sourceLimit)) {
            if(!valid())return@coroutineScope empty
            try {
                val key=withContext(Dispatchers.IO) { credential(client.id) }
                if(key.isEmpty())continue
                val rows=budget.withPermit { reads.legacyPeers(client,key) }
                if(rows.isNotEmpty()) { peers=rows;break }
            } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) {}
        }
        data class Probe(val key: String,val endpoint: String,val codex: Boolean)
        data class Found(val probe: Probe,val codex: CodexDescriptor?=null,val easywork: DiscoveredEasyWorkHost?=null)
        val probes=peers.take(PrivateConnectionRules.peerLimit).flatMap { (_,addresses)->
            listOf(8796 to true,4319 to false).flatMap { (port,codex)->addresses.take(2).map { address->
                Probe(addresses.first()+":"+port,"https://"+(if(':' in address)"[$address]" else address)+":$port",codex)
            } }
        }.distinctBy { it.endpoint }.take(PrivateConnectionRules.probeLimit)
        val resolved=ConcurrentHashMap.newKeySet<String>()
        val results=Channel<Found?>(probes.size.coerceAtLeast(1))
        val jobs=probes.map { probe->launch {
            val result=budget.withPermit {
                if(!valid()||probe.key in resolved)return@withPermit null
                try { if(probe.codex)Found(probe,codex=reads.descriptor(probe.endpoint)) else Found(probe,easywork=reads.easyWork(probe.endpoint)) }
                catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { null }
            }
            results.send(result)
        } }
        try {
            repeat(probes.size) {
                val found=results.receive()
                currentCoroutineContext().ensureActive();if(!valid())return@coroutineScope empty
                if(found!=null&&resolved.add(found.probe.key)) {
                    val verified=found.codex?.let { host->
                        remember(host,found.probe.endpoint)
                        val row=DiscoveredCodexHost(host.target.hostId,host.name,found.probe.endpoint,false,true,"vpn",host.target,host.fingerprint)
                        record(row);Verified(row,host)
                    }
                    found.easywork?.let { host->host.deviceId?.let { id->easywork.putIfAbsent(id,host) } }
                    emit(verified)
                }
            }
        } finally { jobs.forEach { it.cancel() };results.cancel() }
        if(!valid())empty else snapshot().also { publish(it) }
    } }
}

/** Stable iOS route preference: VPN IP, private DNS, other LAN, then local hostname. */
internal fun orderedDiscoveryEndpoints(endpoints: List<String>): List<String> = endpoints.distinct().withIndex().sortedWith(compareBy<IndexedValue<String>> {
    val host=runCatching { URI(it.value).host.orEmpty().removeSurrounding("[","]").lowercase() }.getOrDefault("")
    when { PrivateConnectionRules.isVPNAddress(host)->0;host.endsWith(".ts.net")->1;host.endsWith(".local")->3;else->2 }
}.thenBy { it.index }).map { it.value }
