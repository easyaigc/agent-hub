package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Read-only synthetic discovery scheduling. Fake routes never contact other computers. */
@RunWith(AndroidJUnit4::class)
class MobileDiscoveryReadTest {
    @get:Rule val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val pin="a".repeat(64)
    private fun target(id: String)=TargetScope("codex",null,null,id,"workspace-$id","runtime-$id")
    private fun source(id: String,vararg endpoints: String)=CodexPublicSource(target(id),pin,endpoints.toList())
    private fun host(id: String,endpoint: String)=CodexDescriptor(id,target(id),pin,listOf(CodexRoute(endpoint,"vpn")),connectionMode="automatic")
    private fun client(id: String,endpoint: String,paired: Boolean=true)=MobileClient(id,id,target(id),endpoint,false,"desktop-runtime",false,null,true,
        if(paired)PairedCodexConnection(target(id),pin,listOf(CodexRoute(endpoint,"vpn"))) else null)
    private fun cached(vararg sources: CodexPublicSource): NativeSessionStorage {
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"P2-discovery-"+UUID.randomUUID())
        storage.write("codex-public-discovery-sources-v1",JSONArray(sources.map { s->JSONObject().put("target",encodeTarget(s.target)).put("fingerprint",s.fingerprint).put("endpoints",JSONArray(s.endpoints)) }).toString())
        return storage
    }
    private class Reads: CodexDiscoveryReads {
        val descriptors=Collections.synchronizedList(mutableListOf<String>())
        val directories=Collections.synchronizedList(mutableListOf<String>())
        val legacy=Collections.synchronizedList(mutableListOf<String>())
        val easywork=Collections.synchronizedList(mutableListOf<String>())
        private val active=AtomicInteger();val maximum=AtomicInteger()
        var describe: suspend (String)->CodexDescriptor={ throw HostReadError("synthetic offline") }
        var directory: suspend (CodexPublicSource,String)->List<Pair<String,List<String>>> ={ _,_->throw HostReadError("synthetic no directory") }
        var legacyDirectory: suspend (MobileClient,String)->List<Pair<String,List<String>>> ={ _,_->throw HostReadError("synthetic no legacy directory") }
        private suspend fun <T> track(block: suspend ()->T): T {
            val n=active.incrementAndGet();maximum.updateAndGet { maxOf(it,n) }
            return try { block() } finally { active.decrementAndGet() }
        }
        override suspend fun savedDescriptor(client: MobileClient,token: String): CodexDescriptor=throw HostReadError("No saved-route read in public fixture")
        override suspend fun descriptor(endpoint: String): CodexDescriptor=track { descriptors.add(endpoint);describe(endpoint) }
        override suspend fun peers(source: CodexPublicSource,endpoint: String): List<Pair<String,List<String>>> =track { directories.add(endpoint);directory(source,endpoint) }
        override suspend fun legacyPeers(client: MobileClient,token: String): List<Pair<String,List<String>>> =track { legacy.add(client.id);legacyDirectory(client,token) }
        override suspend fun easyWork(endpoint: String): DiscoveredEasyWorkHost=track { easywork.add(endpoint);throw HostReadError("synthetic no EasyWork") }
    }
    private val peerEndpoint="https://100.64.0.1:8796"
    private val peers=listOf("peer" to listOf("100.64.0.1"))
    private fun peerReader()=Reads().apply { describe={ endpoint->if(endpoint==peerEndpoint)host("peer",endpoint) else throw HostReadError("synthetic offline") } }

    @Test fun savedPairedClientSeedsPublicDirectoryWithoutOptionalCacheOrCredential()=runBlocking {
        val endpoint="https://saved.ts.net:8796";val reads=peerReader().apply { directory={ s,e->assertEquals("saved",s.target.hostId);assertEquals(endpoint,e);peers } }
        var credentials=0;val result=CodexPublicDiscovery(null,reads).scan(emptyList(),listOf(client("saved",endpoint)),{credentials++;""}) { true }
        assertEquals(listOf("peer"),result.codex.map { it.id });assertEquals(0,credentials);assertTrue(reads.legacy.isEmpty())
    }
    @Test fun emptyPublicDirectoryContinuesToAnotherVerifiedSource()=runBlocking {
        val first="https://empty.ts.net:8796";val second="https://full.ts.net:8796"
        val reads=peerReader().apply { directory={ _,e->if(e==first)emptyList() else peers } }
        val result=CodexPublicDiscovery(cached(source("empty",first),source("full",second)),reads).scan(emptyList(),emptyList(),{error("No credential read")}) { true }
        assertEquals(listOf("peer"),result.codex.map { it.id });assertEquals(listOf(first,second),reads.directories)
    }
    @Test fun emptyLegacyDirectoryContinuesWithEachSavedTargetsOwnCredential()=runBlocking {
        val reads=peerReader().apply { legacyDirectory={ c,key->assertEquals("synthetic-"+c.id,key);if(c.id=="empty")emptyList() else peers } }
        val result=CodexPublicDiscovery(null,reads).scan(emptyList(),listOf(client("empty","https://empty.ts.net:8796",false),client("full","https://full.ts.net:8796",false)),{"synthetic-$it"}) { true }
        assertEquals(listOf("peer"),result.codex.map { it.id });assertEquals(listOf("empty","full"),reads.legacy)
    }
    @Test fun vpnAddressesAreTriedBeforeLongLanAndLocalHostnameTail()=runBlocking {
        val lan="https://192.168.1.80:8796";val local="https://machine.local:8796";val dns="https://source.ts.net:8796";val vpn="https://100.64.0.80:8796"
        val reads=peerReader().apply { directory={ _,e->if(e==vpn)peers else throw HostReadError("synthetic stale route") } }
        val result=CodexPublicDiscovery(cached(source("source",lan,local,dns,vpn)),reads).scan(emptyList(),emptyList(),{error("No credential read")}) { true }
        assertEquals(listOf("peer"),result.codex.map { it.id });assertEquals(vpn,reads.directories.first());assertTrue(reads.maximum.get()<=4)
    }
    @Test fun allThirtyTwoSourceRoutesRemainEligibleWithAtMostFourActiveReads()=runBlocking {
        val routes=(1..32).map { "https://100.64.1.$it:8796" }
        val reads=peerReader().apply { directory={ _,e->delay(15);if(e==routes.last())peers else throw HostReadError("synthetic stale route") } }
        val result=CodexPublicDiscovery(cached(source("source",*routes.toTypedArray())),reads).scan(emptyList(),emptyList(),{error("No credential read")}) { true }
        assertEquals(listOf("peer"),result.codex.map { it.id });assertEquals(routes.toSet(),reads.directories.toSet());assertTrue(reads.maximum.get() in 2..4)
    }
    @Test fun allPeerAddressesAndBothProtocolsStayBoundedWithoutDroppingHalfThePeers()=runBlocking {
        val seed="https://seed.ts.net:8796";val rows=(1..32).map { "peer-$it" to listOf("100.64.0.$it","100.64.1.$it") }
        val reads=Reads().apply { directory={ _,_->rows };describe={ delay(2);throw HostReadError("synthetic offline") } }
        val result=CodexPublicDiscovery(cached(source("seed",seed)),reads).scan(emptyList(),emptyList(),{error("No credential read")}) { true }
        assertTrue(result.codex.isEmpty());assertEquals(64,reads.descriptors.toSet().size);assertEquals(64,reads.easywork.toSet().size);assertTrue(reads.maximum.get()<=4)
    }
    @Test fun validatedFastHostPublishesBeforeUnrelatedSlowProbeCompletes()=runBlocking {
        val seed="https://seed.ts.net:8796";val held=CompletableDeferred<Unit>();val seen=CompletableDeferred<CodexPublicDiscovery.Result>()
        val reads=peerReader().apply { directory={ _,_->peers+listOf("slow" to listOf("100.64.0.2")) };describe={ e->if(e==peerEndpoint)host("peer",e) else { held.await();throw HostReadError("synthetic late failure") } } }
        val discovery=CodexPublicDiscovery(cached(source("seed",seed)),reads)
        val scan=async { discovery.scan(emptyList(),emptyList(),{error("No credential read")},{ value->if(value.codex.any { it.id=="peer" })seen.complete(value) }) { true } }
        try { val value=withTimeout(1_000) { seen.await() };assertTrue(value.codex.single().reachable);assertTrue(scan.isActive) }
        finally { held.complete(Unit);scan.await() }
    }
    @Test fun verifiedHintHasSeparateFifteenSecondReachabilityAndSixtySecondLifetime()=runBlocking {
        var now=0L;val reads=peerReader();val discovery=CodexPublicDiscovery(null,reads) { now }
        val hint=DiscoveredCodexHost("peer","peer",peerEndpoint,false,false)
        assertTrue(discovery.scan(listOf(hint),emptyList(),{error("No credential read")}) { true }.codex.single().reachable)
        reads.describe={throw HostReadError("synthetic transient miss")}
        for((time,reachable) in listOf(14_999L to true,15_000L to false,59_999L to false)) {
            now=time;val row=discovery.scan(emptyList(),emptyList(),{error("No credential read")}) { true }.codex.single();assertEquals(reachable,row.reachable);assertEquals(peerEndpoint,row.endpoint)
        }
        now=60_000;assertTrue(discovery.scan(emptyList(),emptyList(),{error("No credential read")}) { true }.codex.isEmpty())
    }
    @Test fun publicRowsRemainBoundedWhenBonjourAndVpnReturnDifferentHosts()=runBlocking {
        val hints=(1..8).map { DiscoveredCodexHost("lan-$it","lan-$it","https://lan-$it.local:8796",false,false) }
        val reads=Reads().apply { directory={ _,_->(1..32).map { "peer-$it" to listOf("100.64.0.$it") } };describe={ e->val name=java.net.URI(e).host;host(if(name.endsWith(".local"))name.removeSuffix(".local") else "peer-"+name.substringAfterLast('.'),e) } }
        val result=CodexPublicDiscovery(null,reads).scan(hints,emptyList(),{error("No credential read")}) { true }
        assertEquals(32,result.codex.size);assertEquals(result.codex.size,result.codex.map { it.id }.toSet().size)
    }
    @Test fun parentCancellationStopsHeldReadsWithoutPublishingLateRows()=runBlocking {
        val started=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();val reads=Reads().apply { describe={ e->started.complete(Unit);held.await();host("peer",e) } }
        var publications=0;val discovery=CodexPublicDiscovery(null,reads)
        val job=launch { discovery.scan(listOf(DiscoveredCodexHost("peer","peer",peerEndpoint,false,false)),emptyList(),{error("No credential read")},{publications++}) { true } }
        withTimeout(1_000) { started.await() };job.cancelAndJoin();held.complete(Unit);assertTrue(job.isCancelled);assertEquals(0,publications)
    }
    @Test fun invalidatedGenerationRejectsLateDescriptorAndAnyNewPublication()=runBlocking {
        var valid=true;var publications=0;val started=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>()
        val reads=Reads().apply { describe={ e->started.complete(Unit);held.await();host("peer",e) } };val discovery=CodexPublicDiscovery(null,reads)
        val job=async { discovery.scan(listOf(DiscoveredCodexHost("peer","peer",peerEndpoint,false,false)),emptyList(),{error("No credential read")},{publications++}) { valid } }
        withTimeout(1_000) { started.await() };valid=false;val before=publications;held.complete(Unit);assertTrue(job.await().codex.isEmpty());assertEquals(before,publications)
    }
    @Test fun mismatchedBonjourHostIdNeverBecomesAValidatedHintOrDirectorySource()=runBlocking {
        val reads=Reads().apply { describe={ e->host("wrong-host",e) } };val discovery=CodexPublicDiscovery(null,reads)
        val result=discovery.scan(listOf(DiscoveredCodexHost("expected","expected",peerEndpoint,false,false)),emptyList(),{error("No credential read")}) { true }
        assertTrue(result.codex.isEmpty());assertTrue(reads.directories.isEmpty());assertTrue(reads.legacy.isEmpty())
    }
    @Test fun foregroundResetDropsEphemeralRowsWithoutDiscardingDirectorySources()=runBlocking {
        val reads=peerReader();val discovery=CodexPublicDiscovery(null,reads)
        assertEquals(1,discovery.scan(listOf(DiscoveredCodexHost("peer","peer",peerEndpoint,false,false)),emptyList(),{error("No credential read")}) { true }.codex.size)
        val before=reads.directories.size;discovery.clearVerifiedHints();assertTrue(discovery.currentHints().isEmpty())
        reads.describe={throw HostReadError("synthetic miss")}
        assertTrue(discovery.scan(emptyList(),emptyList(),{error("No credential read")}) { true }.codex.isEmpty());assertTrue(reads.directories.size>before)
    }
    @Test fun changedHintTargetOrPinCannotReplaceOrRenewAnEarlierVerifiedHint()=runBlocking {
        var now=0L;val reads=peerReader();val discovery=CodexPublicDiscovery(null,reads) { now }
        val hint=DiscoveredCodexHost("peer","peer",peerEndpoint,false,false,target=target("peer"),fingerprint=pin)
        assertEquals(1,discovery.scan(listOf(hint),emptyList(),{error("No credential read")}) { true }.codex.size)
        now=10_000;reads.describe={ e->host("peer",e).copy(target=TargetScope("codex",null,null,"peer","different-workspace","different-runtime")) }
        val old=discovery.scan(listOf(hint),emptyList(),{error("No credential read")}) { true }.codex.single();assertTrue(sameScope(old.target,target("peer")))
        reads.describe={ e->host("peer",e).copy(fingerprint="b".repeat(64)) }
        assertEquals(pin,discovery.scan(listOf(hint),emptyList(),{error("No credential read")}) { true }.codex.single().fingerprint)
        now=15_000;assertFalse(discovery.currentHints().single().reachable)
    }

    @Test fun freshlyVerifiedDirectoryIsNotStarvedByFourOfflineSavedSources()=runBlocking {
        val fresh="https://fresh.local:8796";val reads=peerReader().apply {
            describe={ e->when(e) { fresh->host("fresh",e);peerEndpoint->host("peer",e);else->throw HostReadError("synthetic miss") } }
            directory={ source,_->if(source.target.hostId=="fresh")peers else throw HostReadError("synthetic stale saved source") }
        }
        val saved=(1..4).map { client("saved-$it","https://saved-$it.ts.net:8796") }
        val result=CodexPublicDiscovery(null,reads).scan(listOf(DiscoveredCodexHost("fresh","fresh",fresh,false,false)),saved,{""}) { true }
        assertTrue(result.codex.any { it.id=="peer" });assertEquals(fresh,reads.directories.first())
    }
    @Test fun authenticatedOnlineSavedSourceStillPrecedesNewUnpairedDirectory()=runBlocking {
        val fresh="https://fresh.local:8796";val online="https://online.ts.net:8796";val reads=peerReader().apply {
            describe={ e->if(e==fresh)host("fresh",e) else host("peer",e) }
            directory={ _,_->peers }
        }
        val result=CodexPublicDiscovery(null,reads).scan(listOf(DiscoveredCodexHost("fresh","fresh",fresh,false,false)),listOf(client("online",online).copy(online=true)),{""}) { true }
        assertTrue(result.codex.any { it.id=="peer" });assertEquals(online,reads.directories.first())
    }

    @Test fun retainedHintsNeverCarryNewDescriptorProofForAutomaticRouteAdoption()=runBlocking {
        var now=0L;val reads=peerReader();val discovery=CodexPublicDiscovery(null,reads) { now };val published=mutableListOf<CodexPublicDiscovery.Result>()
        val hint=DiscoveredCodexHost("peer","peer",peerEndpoint,false,false)
        discovery.scan(listOf(hint),emptyList(),{error("No credential read")},{published.add(it)}) { true }
        val proof=published.mapNotNull { it.verified }.single();assertEquals(peerEndpoint,proof.row.endpoint);assertTrue(sameScope(proof.descriptor.target,target("peer")));assertEquals(pin,proof.descriptor.fingerprint)
        reads.describe={throw HostReadError("synthetic miss")};now=1_000;published.clear()
        val retained=discovery.scan(emptyList(),emptyList(),{error("No credential read")},{published.add(it)}) { true }
        assertEquals(1,retained.codex.size);assertNull(retained.verified);assertTrue(published.all { it.verified==null })
    }

}
