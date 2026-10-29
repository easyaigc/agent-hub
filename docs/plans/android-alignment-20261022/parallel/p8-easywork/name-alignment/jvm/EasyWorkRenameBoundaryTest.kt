package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exact extracted production update/replace methods and models, synthetic outer state/store.
 * This host harness is not MobileSession, Android Keystore, discovery/network or UI acceptance.
 */
class EasyWorkRenameBoundaryTest {
    private class Fixture {
        val credential=JSONObject(File(System.getProperty("p8.asset")).readText()).getJSONObject("credential")
        val binding=EasyWorkCredential.decode(credential).binding
        val old=AgentCandidate("old","lan-wss","https://192.168.42.1:7443","a".repeat(64))
        val next=AgentCandidate("next","lan-wss","https://192.168.42.2:7443","a".repeat(64))
        val relay=AgentCandidate("relay","relay-http","https://relay.invalid")
        val connection=AgentConnection(binding.target.scope(),listOf(old,relay),"ref-original")
        val client=MobileClient("original","Original",connection.target,old.endpoint,false,"easywork-runtime",false,agentConnection=connection)
        val store=HeldCredentialStore().apply {rows["easywork-credential:ref-original"]=credential.toString();rows["original-unknown-journal"]="immutable original unknown operation";rows["original-grant"]="original grant"}
        val session=CandidateUpdateSession(mutableListOf(client),store)
        val original=store.rows.toMap()
        fun live()=session.realClients.single()
        fun mutate(connection: AgentConnection,revision: Boolean=true) {session.realClients[0]=live().copy(scope=connection.target,agentConnection=connection);if(revision)session.revisions[client.id]=(session.revisions[client.id]?:0L)+1}
        fun protected() {assertEquals(original,store.rows);assertEquals("untouched original draft",session.draft);assertTrue(session.connectionBusy)}
    }
    private suspend fun Fixture.race(change: Fixture.()->Unit): Boolean=coroutineScope {
        val work=async(start=CoroutineStart.UNDISPATCHED) {session.updateEasyWorkCandidates(client,binding,listOf(next))}
        try {
            withContext(Dispatchers.IO) {assertTrue("actual extracted method reached its credential read",store.entered.await(2,java.util.concurrent.TimeUnit.SECONDS))}
            change();store.release.countDown();withTimeout(3000) {work.await()}
        } finally {store.release.countDown();work.cancelAndJoin()}
    }
    @Test fun removedConnectionCannotBeResurrected()=runBlocking {
        val f=Fixture();val result=f.race {session.realClients.clear();session.revisions[client.id]=1}
        assertFalse(result);assertTrue(f.session.realClients.isEmpty());assertEquals(0,f.session.publishes);f.protected()
    }
    @Test fun replacementCredentialPointerCannotBeRolledBack()=runBlocking {
        val f=Fixture();val result=f.race {mutate(connection.copy(credentialRef="ref-new-grant"))}
        assertFalse(result);assertEquals("ref-new-grant",f.live().agentConnection!!.credentialRef);assertEquals(0,f.session.publishes);f.protected()
    }
    @Test fun changedPreferenceCannotBeReverted()=runBlocking {
        val f=Fixture();assertFalse(f.race {mutate(connection.copy(preference="relay-only"))})
        assertEquals("relay-only",f.live().agentConnection!!.preference);assertEquals(0,f.session.publishes);f.protected()
    }
    @Test fun preferenceRoundTripCannotReviveAnOldProbe()=runBlocking {
        val f=Fixture();assertFalse(f.race {mutate(connection.copy(preference="relay-only"));mutate(connection)})
        assertEquals(f.connection,f.live().agentConnection);assertEquals(0,f.session.publishes);f.protected()
    }
    @Test fun changedPointerIsRejectedEvenWithoutGenerationAdvance()=runBlocking {
        val f=Fixture();assertFalse(f.race {mutate(connection.copy(credentialRef="ref-other"),false)})
        assertEquals("ref-other",f.live().agentConnection!!.credentialRef);f.protected()
    }
    @Test fun everyCompleteTargetCoordinateIsRecheckedAfterIo()=runBlocking {
        val f0=Fixture();val t=f0.connection.target
        val variants=listOf(TargetScope("other",t.accountAuthority,t.accountSubject,t.hostId,t.workspaceId,t.runtimeId),TargetScope(t.agentId,"https://other.invalid",t.accountSubject,t.hostId,t.workspaceId,t.runtimeId),TargetScope(t.agentId,t.accountAuthority,"other-subject",t.hostId,t.workspaceId,t.runtimeId),TargetScope(t.agentId,t.accountAuthority,t.accountSubject,"other-host",t.workspaceId,t.runtimeId),TargetScope(t.agentId,t.accountAuthority,t.accountSubject,t.hostId,"other-workspace",t.runtimeId),TargetScope(t.agentId,t.accountAuthority,t.accountSubject,t.hostId,t.workspaceId,"other-runtime"))
        for(target in variants) {val f=Fixture();assertFalse(f.race {mutate(connection.copy(target=target),false)});assertTrue(sameScope(target,f.live().agentConnection!!.target));assertEquals(0,f.session.publishes);f.protected()}
    }
    @Test fun changedDisplayFieldsSurviveAValidRouteRepair()=runBlocking {
        val raw="  Human renamed 中文 😀 %@  ";val f=Fixture();assertTrue(f.race {session.realClients[0]=live().copy(online=true);session.renameEasyWork(client,raw)})
        assertEquals(raw,f.live().name);assertTrue(f.live().online);assertEquals(listOf(f.next,f.old,f.relay),f.live().agentConnection!!.candidates);assertEquals(2,f.session.publishes);assertTrue(f.session.persistedNames.all {it.single()==raw});f.protected()
    }
    @Test fun concurrentRouteRepairIsMergedFromTheLatestConnection()=runBlocking {
        val f=Fixture();val concurrent=AgentCandidate("concurrent","lan-wss","https://192.168.42.3:7443","a".repeat(64))
        assertTrue(f.race {mutate(connection.copy(candidates=listOf(concurrent)+connection.candidates),false)})
        assertEquals(listOf(f.next,concurrent,f.old,f.relay),f.live().agentConnection!!.candidates);assertEquals(1,f.session.publishes);f.protected()
    }
    @Test fun normalReadOnlyRepairKeepsOriginalTargetPointerPreferenceAndRelay()=runBlocking {
        val f=Fixture();f.store.release.countDown();assertTrue(f.session.updateEasyWorkCandidates(f.client,f.binding,listOf(f.next)))
        val c=f.live().agentConnection!!;assertTrue(sameScope(f.connection.target,c.target));assertEquals(f.connection.credentialRef,c.credentialRef);assertEquals(f.connection.preference,c.preference);assertTrue(f.relay in c.candidates);assertEquals(1,f.session.publishes);f.protected()
    }
    @Test fun unchangedCandidatesDoNotPersistMetadata()=runBlocking {
        val f=Fixture();f.store.release.countDown();assertTrue(f.session.updateEasyWorkCandidates(f.client,f.binding,f.connection.candidates));assertEquals(0,f.session.publishes);f.protected()
    }
    @Test fun missingOrMismatchedCredentialNeverPublishes()=runBlocking {
        for(raw in listOf<String?>(null,JSONObject(Fixture().credential.toString()).apply {getJSONObject("binding").put("bindingId","other-original-binding")}.toString())) {
            val f=Fixture();if(raw==null)f.store.rows.remove("easywork-credential:ref-original")else f.store.rows["easywork-credential:ref-original"]=raw;f.store.release.countDown();assertFalse(f.session.updateEasyWorkCandidates(f.client,f.binding,listOf(f.next)));assertEquals(0,f.session.publishes);assertEquals(f.connection,f.live().agentConnection)
        }
    }
    @Test fun cancellationWhileReadingCannotPublishOldMetadata()=runBlocking {
        val f=Fixture();val job=async(start=CoroutineStart.UNDISPATCHED) {f.session.updateEasyWorkCandidates(f.client,f.binding,listOf(f.next))}
        try {withContext(Dispatchers.IO) {assertTrue(f.store.entered.await(2,java.util.concurrent.TimeUnit.SECONDS))};job.cancel();f.store.release.countDown();job.join();assertTrue(job.isCancelled);assertEquals(0,f.session.publishes);assertEquals(f.connection,f.live().agentConnection);f.protected()}finally {f.store.release.countDown();job.cancelAndJoin()}
    }
    @Test fun allWhitespaceIsRejectedWithoutPublishingMetadata() {
        val f=Fixture();val before=f.live()
        for(blank in listOf("","   ","\t\r\n","\u00a0\u2003")){f.session.renameEasyWork(f.client,blank);assertEquals(before,f.live());assertEquals(0,f.session.publishes)}
        f.protected()
    }
    @Test fun lengthBoundaryPreservesEveryAcceptedCharacter() {
        val f=Fixture();val raw=" "+"n".repeat(254)+" ";assertEquals(256,raw.length)
        f.session.renameEasyWork(f.client,raw);assertEquals(raw,f.live().name);assertEquals(listOf(listOf(raw)),f.session.persistedNames)
        f.session.renameEasyWork(f.client,raw+"x");assertEquals(raw,f.live().name);assertEquals(1,f.session.publishes);f.protected()
    }
    @Test fun staleEditorUsesLatestClientAndKeepsItsOtherFields() {
        val f=Fixture();f.mutate(f.connection.copy(preference="relay-only"));val current=f.live().copy(online=true);f.session.realClients[0]=current
        val raw=" \u00a0 Full 中文 😀 %@ \u2003 ";f.session.renameEasyWork(f.client,raw)
        assertEquals(current.copy(name=raw),f.live());assertEquals(listOf(listOf(raw)),f.session.persistedNames);f.protected()
    }
    @Test fun importedMarkerRenamePreservesMarkerAndRejectsAllBlank() {
        val f=Fixture();val marker=MigratedEasyWorkConnection.decode(JSONObject().put("version",1).put("provider","easywork").put("archiveId","9".repeat(64))
            .put("target",CodexCommandCodec.target(f.connection.target)).put("requiresPairing",true).put("credentialState","none").put("preference","relay-only")
            .put("candidateOrigins",org.json.JSONArray(listOf("https://relay.invalid"))))
        val imported=f.client.copy(agentConnection=null,requiresReadTarget=true,migrationEasyWork=marker);f.session.realClients[0]=imported
        val raw="  Migrated 中文 😀 %@  ";f.session.renameEasyWork(imported,raw);assertEquals(imported.copy(name=raw),f.live());assertNull(f.live().agentConnection)
        for(blank in listOf("","  ","\t\n","\u00a0\u2003")){f.session.renameEasyWork(imported,blank);assertEquals(raw,f.live().name)}
        assertEquals(listOf(listOf(raw)),f.session.persistedNames);f.protected()
    }
    @Test fun removedOrNonEasyWorkClientIsNeverCreatedByRename() {
        val f=Fixture();f.session.realClients.clear();f.session.renameEasyWork(f.client,"  Renamed  ");assertTrue(f.session.realClients.isEmpty());assertEquals(0,f.session.publishes)
        val other=f.client.copy(agentConnection=null);f.session.realClients.add(other);f.session.renameEasyWork(other,"  Renamed  ");assertEquals(other,f.live());assertEquals(0,f.session.publishes);f.protected()
    }
}
