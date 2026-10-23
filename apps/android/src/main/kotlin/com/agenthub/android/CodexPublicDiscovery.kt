package com.agenthub.android

import com.agenthub.core.DomainContract
import com.agenthub.core.PrivateConnectionRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded public hints are separate from authenticated clients and their credentials. */
internal class CodexPublicDiscovery(private val storage: NativeSessionStorage?) {
    private var loaded = false
    private val sources = mutableListOf<CodexPublicSource>()
    private val budget = Semaphore(4)
    private suspend fun load() {
        if (loaded) return
        try {
            val raw = withContext(Dispatchers.IO) { storage?.read("codex-public-discovery-sources-v1") } ?: run { loaded=true;return }
            require(raw.length <= 65_536)
            val rows = JSONArray(raw).objects(); require(rows.size <= PrivateConnectionRules.sourceLimit)
            val decoded = rows.map { row ->
                val target = row.getJSONObject("target")
                require(DomainContract.validate(target.toString(), "target") == null)
                val scope = HostReadCodec.target(target)!!; require(scope.agentId == "codex")
                val fingerprint = row.getString("fingerprint"); require(fingerprint.matches(Regex("[a-f0-9]{64}")))
                val endpoints = row.getJSONArray("endpoints").strings().map(CodexEndpoints::local)
                require(endpoints.size in 1..PrivateConnectionRules.sourceEndpointLimit)
                CodexPublicSource(scope, fingerprint, endpoints.distinct())
            }
            sources.addAll(decoded.distinctBy { it.target.hostId });loaded=true
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { loaded=true /* No credential/client is changed by a broken hint cache. */ }
    }
    private fun remember(host: CodexDescriptor, endpoint: String) {
        sources.removeAll { it.target.hostId == host.target.hostId }
        sources.add(0, CodexPublicSource(host.target, host.fingerprint, (listOf(endpoint) + host.routes.map { it.endpoint }).distinct().take(PrivateConnectionRules.sourceEndpointLimit)))
        while (sources.size > PrivateConnectionRules.sourceLimit) sources.removeAt(sources.lastIndex)
        val json = JSONArray(sources.map { JSONObject().put("target", encodeTarget(it.target)).put("fingerprint", it.fingerprint).put("endpoints", JSONArray(it.endpoints)) })
        storage?.enqueue("codex-public-discovery-sources-v1", json.toString()) { /* Hints are optional; credentials remain untouched. */ }
    }
    data class Result(val codex: List<DiscoveredCodexHost>, val easywork: List<DiscoveredEasyWorkHost>)
    suspend fun scan(hints: List<DiscoveredCodexHost>, clients: List<MobileClient>, credential: (String)->String, valid: ()->Boolean): Result = coroutineScope {
        load()
        val local = mutableListOf<DiscoveredCodexHost>()
        for (hint in hints.take(8)) {
            if (!valid()) return@coroutineScope Result(emptyList(), emptyList())
            try {
                val descriptor = budget.withPermit { CodexDiscoveryAPI.discover(hint.endpoint) }
                require(descriptor.target.hostId == hint.id)
                if (!valid()) return@coroutineScope Result(emptyList(), emptyList())
                remember(descriptor, hint.endpoint)
                local.add(hint.copy(name = descriptor.name, reachable = true, networkKind = "lan", target = descriptor.target, fingerprint = descriptor.fingerprint))
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
        }
        var peers: List<Pair<String,List<String>>> = emptyList()
        directory@ for (source in sources.toList().take(PrivateConnectionRules.sourceLimit)) {
            for (endpoint in source.endpoints.take(2)) {
                if (!valid()) return@coroutineScope Result(emptyList(), emptyList())
                try { peers = budget.withPermit { CodexDiscoveryAPI.peers(source, endpoint) }; break@directory }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
            }
        }
        if (peers.isEmpty()) for (client in clients.filter { !it.isDemo && it.scope?.agentId == "codex" }.sortedByDescending { it.online }.take(4)) {
            if (!valid()) return@coroutineScope Result(emptyList(), emptyList())
            try { peers = budget.withPermit { CodexDiscoveryAPI.legacyPeers(client, withContext(Dispatchers.IO) { credential(client.id) }) }; break }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
        }
        data class Probe(val endpoint: String, val codex: Boolean)
        val probes = peers.take(PrivateConnectionRules.peerLimit).flatMap { (_, addresses) ->
            listOf(8796 to true, 4319 to false).flatMap { (port, codex) -> addresses.map { address -> Probe("https://" + (if (':' in address) "[$address]" else address) + ":$port", codex) } }
        }.distinctBy { it.endpoint }.take(PrivateConnectionRules.probeLimit)
        val found = probes.map { probe -> async { budget.withPermit {
            if (!valid()) return@withPermit null
            try {
                if (probe.codex) {
                    val host = CodexDiscoveryAPI.discover(probe.endpoint)
                    DiscoveredCodexHost(host.target.hostId, host.name, probe.endpoint, false, true, "vpn", host.target, host.fingerprint) to null
                } else {
                    val host = EasyWorkPairing.readHost(probe.endpoint)
                    null to DiscoveredEasyWorkHost("vpn-" + host.target.desktopDeviceId, host.value.getString("name"), probe.endpoint, host.target.desktopDeviceId, networkKind = "vpn")
                }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        } } }.awaitAll().filterNotNull()
        if (!valid()) return@coroutineScope Result(emptyList(), emptyList())
        Result((found.mapNotNull { it.first } + local).distinctBy { it.id }, found.mapNotNull { it.second }.distinctBy { it.deviceId })
    }
}
