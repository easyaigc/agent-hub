package com.agenthub.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Read-only saved-route scheduling. The session accepts only an unchanged original target. */
internal class CodexSavedRouteDiscovery(private val reads: CodexDiscoveryReads=NativeCodexDiscoveryReads,
    private val budget: Semaphore=Semaphore(4)) {
    data class Verified(val client: MobileClient,val endpoint: String,val descriptor: CodexDescriptor)
    suspend fun scan(clients: List<MobileClient>,hints: List<DiscoveredCodexHost>,credential: (String)->String,
        valid: ()->Boolean,accept: (Verified)->Boolean,unreachable: (MobileClient)->Unit)=coroutineScope {
        data class Probe(val client: MobileClient,val endpoint: String)
        data class Outcome(val probe: Probe,val verified: Verified?)
        val probes=clients.flatMap { client->savedDiscoveryEndpoints(client,hints).map { Probe(client,it) } }
        val resolved=mutableSetOf<String>();val currentCompleted=mutableSetOf<String>()
        val successes=mutableMapOf<String,Verified>();val jobs=mutableListOf<kotlinx.coroutines.Job>()
        val results=Channel<Outcome>(4);var index=0;var pending=0
        fun enqueue() {
            while(index<probes.size&&probes[index].client.id in resolved)index++
            if(index>=probes.size||!valid())return
            val probe=probes[index++];pending++
            jobs+=launch {
                val verified=budget.withPermit {
                    if(!valid())return@withPermit null
                    try {
                        val client=probe.client.copy(endpoint=probe.endpoint,codexDirect=probe.client.codexDirect?.copy(routes=probe.client.codexDirect.routes+CodexRoute(probe.endpoint)))
                        val key=if(client.codexDirect==null)withContext(Dispatchers.IO) { credential(client.id) } else ""
                        val descriptor=reads.savedDescriptor(client,key)
                        require(descriptor.target.hostId==client.id&&sameScope(descriptor.target,client.scope))
                        require(client.codexDirect==null||descriptor.fingerprint==client.codexDirect.fingerprint)
                        Verified(probe.client,probe.endpoint,descriptor)
                    } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { null }
                }
                results.send(Outcome(probe,verified))
            }
        }
        try {
            repeat(minOf(4,probes.size)) { enqueue() }
            while(pending>0) {
                val outcome=results.receive();pending--
                currentCoroutineContext().ensureActive();if(!valid())return@coroutineScope
                val client=outcome.probe.client;val id=client.id
                if(outcome.probe.endpoint==client.endpoint)currentCompleted.add(id)
                outcome.verified?.let { successes.putIfAbsent(id,it) }
                if(id !in resolved) {
                    val candidate=when {
                        outcome.verified?.endpoint==client.endpoint->outcome.verified
                        !client.online||id in currentCompleted->successes[id]
                        else->null
                    }
                    if(candidate!=null&&accept(candidate))resolved.add(id)
                }
                enqueue()
            }
            if(valid())for(client in clients)if(client.id !in resolved) {
                val result=successes[client.id]
                if(result==null||!accept(result))unreachable(client)
            }
        } finally { jobs.forEach { it.cancel() };results.cancel() }
    }
}

internal fun savedDiscoveryEndpoints(client: MobileClient,hints: List<DiscoveredCodexHost>): List<String> {
    val alternatives=client.codexDirect?.routes.orEmpty().map { it.endpoint }+if(client.codexDirect!=null)hints.filter {
        it.id==client.id&&(it.fingerprint==null||it.fingerprint==client.codexDirect.fingerprint)&&(it.target==null||sameScope(it.target,client.scope))
    }.map { it.endpoint } else emptyList()
    return (listOfNotNull(client.endpoint)+orderedDiscoveryEndpoints(alternatives.filter { it!=client.endpoint })).distinct().filter { it.startsWith("https://") }.take(64)
}

/** Keep the discovery row tied to the connection selected by the session. */
internal fun savedDiscoveryRow(client: MobileClient,publicHosts: List<DiscoveredCodexHost>,previous: List<DiscoveredCodexHost>): DiscoveredCodexHost {
    val found=publicHosts.firstOrNull { it.id==client.id&&it.endpoint==client.endpoint&&sameScope(it.target,client.scope)&&it.fingerprint==client.codexDirect?.fingerprint }
    val prior=previous.firstOrNull { it.id==client.id&&it.known&&it.endpoint==client.endpoint&&
        (it.target==null||sameScope(it.target,client.scope))&&(it.fingerprint==null||it.fingerprint==client.codexDirect?.fingerprint) }
    return (found ?: prior)?.copy(name=client.name,known=true) ?: DiscoveredCodexHost(client.id,client.name,client.endpoint.orEmpty(),true,false)
}

/** Chooses the original read intent for a newly validated public descriptor. */
internal fun publicDiscoveryOriginalClient(proof: CodexPublicDiscovery.Verified,originals: List<MobileClient>,current: List<MobileClient>): MobileClient? {
    val original=originals.firstOrNull { !it.isDemo&&!it.online&&it.id==proof.row.id&&
        sameScope(it.scope,proof.descriptor.target)&&it.codexDirect?.fingerprint==proof.descriptor.fingerprint } ?: return null
    val now=current.firstOrNull { it.id==original.id } ?: return null
    return original.takeIf { !now.online&&sameDiscoveryConnection(original,now) }
}

/** Account metadata and display/status changes do not define the Codex machine connection. */
internal fun sameDiscoveryConnection(original: MobileClient,current: MobileClient): Boolean =
    !original.isDemo&&!current.isDemo&&original.id==current.id&&sameScope(original.scope,current.scope)&&
        original.endpoint==current.endpoint&&original.codexDirect==current.codexDirect&&original.readerId==current.readerId&&
        original.requiresReadTarget==current.requiresReadTarget&&original.agentConnection==current.agentConnection
