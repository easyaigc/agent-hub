package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Scheduling only: no real peer, credential, claim, model or desktop action. */
@RunWith(AndroidJUnit4::class)
class MobileSavedRouteDiscoveryTest {
    @get:Rule val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val current="https://192.168.1.80:8796"
    private val alternate="https://100.64.0.80:8796"
    private val pin="a".repeat(64)
    private fun client(online: Boolean=false): MobileClient {
        val scope=TargetScope("codex",null,null,"saved","workspace","runtime")
        return MobileClient("saved","saved",scope,current,online,"desktop-runtime",false,null,true,
            PairedCodexConnection(scope,pin,listOf(CodexRoute(current,"lan"),CodexRoute(alternate,"vpn"))))
    }
    private fun descriptor(c: MobileClient)=CodexDescriptor(c.name,c.scope!!,pin,listOf(CodexRoute(c.endpoint!!)))
    private class Reads(val read: suspend (MobileClient,String)->CodexDescriptor): CodexDiscoveryReads {
        val active=AtomicInteger();val maximum=AtomicInteger()
        override suspend fun savedDescriptor(client: MobileClient,token: String): CodexDescriptor {
            val n=active.incrementAndGet();maximum.updateAndGet { maxOf(it,n) }
            return try { read(client,token) } finally { active.decrementAndGet() }
        }
        override suspend fun descriptor(endpoint: String): CodexDescriptor=error("No public read")
        override suspend fun peers(source: CodexPublicSource,endpoint: String): List<Pair<String,List<String>>> =error("No public directory")
        override suspend fun legacyPeers(client: MobileClient,token: String): List<Pair<String,List<String>>> =error("No legacy directory")
        override suspend fun easyWork(endpoint: String): DiscoveredEasyWorkHost=error("No EasyWork read")
    }
    @Test fun offlineFirstValidatedAlternateDoesNotWaitForDeadCurrentRoute()=runBlocking {
        val c=client();val held=CompletableDeferred<Unit>();val accepted=CompletableDeferred<String>()
        val reads=Reads { probe,key->assertEquals("",key);if(probe.endpoint==current)held.await();descriptor(probe) }
        val scan=async { CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{ value->accepted.complete(value.endpoint);true},{fail("Reachable saved target")}) }
        try { assertEquals(alternate,withTimeout(1_000) { accepted.await() });assertTrue(scan.isActive) }
        finally { held.complete(Unit);scan.await() }
        assertTrue(reads.maximum.get()<=4)
    }
    @Test fun healthyCurrentRoutePublishesBeforeItsDeadAlternativeCompletes()=runBlocking {
        val c=client(true);val held=CompletableDeferred<Unit>();val accepted=CompletableDeferred<String>()
        val reads=Reads { probe,_->if(probe.endpoint==alternate)held.await();descriptor(probe) }
        val scan=async { CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{ value->accepted.complete(value.endpoint);true},{fail("Reachable saved target")}) }
        try { assertEquals(current,withTimeout(1_000) { accepted.await() });assertTrue(scan.isActive) }
        finally { held.complete(Unit);scan.await() }
    }
    @Test fun healthyCurrentRouteRemainsPreferredWhenAlternateFinishesFirst()=runBlocking {
        val c=client(true);val held=CompletableDeferred<Unit>();val alternative=CompletableDeferred<Unit>();val accepted=CompletableDeferred<String>()
        val reads=Reads { probe,_->if(probe.endpoint==current)held.await() else alternative.complete(Unit);descriptor(probe) }
        val scan=async { CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{ value->accepted.complete(value.endpoint);true},{fail("Reachable saved target")}) }
        try {
            withTimeout(1_000) { alternative.await() };delay(50);assertFalse(accepted.isCompleted);held.complete(Unit)
            assertEquals(current,withTimeout(1_000) { accepted.await() })
        } finally { held.complete(Unit);scan.await() }
    }
    @Test fun failedCurrentRouteAllowsExactlyOneValidatedAlternateAdoption()=runBlocking {
        var adopted=0;val c=client(true);val reads=Reads { probe,_->if(probe.endpoint==current)throw HostReadError("synthetic offline");descriptor(probe) }
        CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{ value->adopted++;assertEquals(alternate,value.endpoint);true},{fail("Validated alternate exists")})
        assertEquals(1,adopted)
    }
    @Test fun wrongFullTargetAndPinCannotProduceAReachableRoute()=runBlocking {
        var adopted=0;var absent=0;val c=client();val reads=Reads { probe,_->
            if(probe.endpoint==current)descriptor(probe).copy(target=TargetScope("codex",null,null,"saved","other-workspace","other-runtime")) else descriptor(probe).copy(fingerprint="b".repeat(64))
        }
        CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{adopted++;true},{absent++})
        assertEquals(0,adopted);assertEquals(1,absent)
    }
    @Test fun cancelledScanStopsHeldReadsWithoutAdoptionOrOfflineInference()=runBlocking {
        var changes=0;val started=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();val c=client();val reads=Reads { probe,_->started.complete(Unit);held.await();descriptor(probe) }
        val scan=launch { CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{true},{changes++;true},{changes++}) }
        withTimeout(1_000) { started.await() };scan.cancelAndJoin();held.complete(Unit);assertEquals(0,changes);assertEquals(0,reads.active.get())
    }
    @Test fun changedGenerationFencesLateRouteAndOfflineResults()=runBlocking {
        var valid=true;var changes=0;val started=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();val c=client();val reads=Reads { probe,_->started.complete(Unit);held.await();descriptor(probe) }
        val scan=async { CodexSavedRouteDiscovery(reads).scan(listOf(c),emptyList(),{error("No credential read")},{valid},{changes++;true},{changes++}) }
        withTimeout(1_000) { started.await() };valid=false;held.complete(Unit);scan.await();assertEquals(0,changes)
    }
    @Test fun legacySavedRouteUsesOnlyItsOwnCredentialAndOriginalHttpsEndpoint()=runBlocking {
        var calls=0;val c=client().copy(codexDirect=null);val reads=Reads { probe,key->calls++;assertEquals(current,probe.endpoint);assertEquals("synthetic-saved",key);descriptor(probe) }
        val unrelated=DiscoveredCodexHost("saved","hint",alternate,false,true)
        CodexSavedRouteDiscovery(reads).scan(listOf(c),listOf(unrelated),{"synthetic-$it"},{true},{true},{fail("Saved route is reachable")});assertEquals(1,calls)
    }
    @Test fun retainedOldHintCannotReplaceTheActuallyAdoptedSavedRouteInTheList() {
        val c=client().copy(endpoint=alternate,name="saved alias")
        val retained=DiscoveredCodexHost(c.id,c.name,current,false,true,"lan",c.scope,pin)
        val adopted=DiscoveredCodexHost(c.id,"old alias",alternate,true,true,"vpn",c.scope,pin)
        val row=savedDiscoveryRow(c,listOf(retained),listOf(adopted))
        assertEquals(alternate,row.endpoint);assertEquals(c.name,row.name);assertTrue(row.known);assertTrue(row.reachable)
    }

    @Test fun concurrentPublicAndSavedRouteProducersShareFourActualReadSlots()=runBlocking {
        val active=AtomicInteger();val maximum=AtomicInteger()
        suspend fun <T> counted(block: suspend ()->T): T {
            val n=active.incrementAndGet();maximum.updateAndGet { maxOf(n,it) }
            return try { delay(15);block() } finally { active.decrementAndGet() }
        }
        val reads=object: CodexDiscoveryReads {
            override suspend fun savedDescriptor(client: MobileClient,token: String)=counted { assertEquals("",token);descriptor(client) }
            override suspend fun descriptor(endpoint: String)=counted {
                val id=if(endpoint.contains("seed.local"))"seed" else "public-"+java.net.URI(endpoint).host.substringAfterLast('.')
                CodexDescriptor(id,TargetScope("codex",null,null,id,"workspace-$id","runtime-$id"),pin,listOf(CodexRoute(endpoint,"vpn")))
            }
            override suspend fun peers(source: CodexPublicSource,endpoint: String): List<Pair<String,List<String>>> =counted { (1..8).map { "peer-$it" to listOf("100.64.0.$it") } }
            override suspend fun legacyPeers(client: MobileClient,token: String): List<Pair<String,List<String>>> =error("No legacy read")
            override suspend fun easyWork(endpoint: String): DiscoveredEasyWorkHost=counted { throw HostReadError("synthetic no EasyWork") }
        }
        val slots=kotlinx.coroutines.sync.Semaphore(4)
        val public=async { CodexPublicDiscovery(null,reads,slots).scan(listOf(DiscoveredCodexHost("seed","seed","https://seed.local:8796",false,false)),emptyList(),{error("No credential read")}) { true } }
        val saved=async { CodexSavedRouteDiscovery(reads,slots).scan(listOf(client()),emptyList(),{error("No credential read")},{true},{true},{fail("Saved route is reachable")}) }
        assertTrue(public.await().codex.isNotEmpty());saved.await();assertTrue(maximum.get() in 2..4);assertEquals(0,active.get())
    }

    private fun proof(c: MobileClient)=CodexPublicDiscovery.Verified(DiscoveredCodexHost(c.id,c.name,alternate,false,true,"vpn",c.scope,pin),descriptor(c).copy(routes=listOf(CodexRoute(alternate,"vpn"))))
    @Test fun latePublicDescriptorCannotBorrowEditedOrReplacedConnectionState() {
        val original=client().copy(readerId="original-reader")
        val edited=original.copy(endpoint="https://edited.ts.net:8796",name="edited alias")
        assertNull(publicDiscoveryOriginalClient(proof(original),listOf(original),listOf(edited)))
        assertNull(publicDiscoveryOriginalClient(proof(original),listOf(original),listOf(original.copy(readerId="replacement-reader"))))
        assertNull(publicDiscoveryOriginalClient(proof(original),listOf(original),emptyList()))
    }
    @Test fun accountMetadataAndAliasChangeKeepSameMachineDiscoveryEligible() {
        val original=client();val metadata=TargetScope("codex","historical-account","changed-subject",original.id,original.scope!!.workspaceId,original.scope!!.runtimeId)
        val now=original.copy(scope=metadata,name="renamed by user",codexDirect=original.codexDirect!!.copy(target=metadata))
        val eligible=publicDiscoveryOriginalClient(proof(original),listOf(original),listOf(now))
        assertNotNull(eligible);assertTrue(sameScope(original.scope,eligible!!.scope))
    }

}
