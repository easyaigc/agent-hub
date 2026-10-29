package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** A separate opt-in cold pair. Old residual/frame writers are never reused. */
class NativeMigrationEasyWorkBusinessColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned fresh EasyWork business cold password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-ew-business-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun runId()=requireNotNull(InstrumentationRegistry.getArguments().getString("easyWorkBusinessColdRun")){"Fresh parent-owned business UUID required"}.also {require(UUID.fromString(it).toString()==it)}
    private fun namespace(run: String,source: Boolean)="P7-ew-business-"+(if(source)"source-"else"cold-")+run
    private fun storage(run: String,source: Boolean)=namespace(run,source).let {NativeSessionStorage(ui.activity,it,it)}
    private fun proof(run: String)=File(ui.activity.filesDir,"acceptance/easywork-business-cold-$run.json").also {require(it.canonicalFile==File(File(ui.activity.filesDir,"acceptance").canonicalFile,it.name))}
    private fun hashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    @Test fun stageFreshOrdinaryBusinessCandidateForDifferentProcess() {
        val run=runId();require(!proof(run).exists());for(source in listOf(true,false))require(!File(ui.activity.noBackupFilesDir,namespace(run,source)).exists()){ "Original business writer must not be replayed" }
        val source=storage(run,true);val destination=storage(run,false);NativeMigrationEasyWorkBusinessFixtures.seed(ui.activity,source)
        val originals=source.migrationTypedArchiveSnapshot({emptySet()}).text;val rawHashes=originals.mapValues {NativeChatNotificationWire.hash(it.value)}
        val sourceBefore=MigrationActivationFixtures.tree(source);val encrypted=File(owned.folder,"fresh-business.encrypted")
        NativeMigrationExport(ui.activity,source).sealFile(encrypted,password)
        val receipt=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),encrypted,password)
        assertEquals(sourceBefore,MigrationActivationFixtures.tree(source))
        proof(run).writeText(JSONObject().put("version",1).put("kind","p7-easywork-business-cold-v1").put("run",run).put("writerPid",Process.myPid())
            .put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount)
            .put("rawHashes",JSONObject(rawHashes)).put("sourceCiphertext",JSONObject(sourceBefore)).put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(destination)))
            .put("candidateCiphertext",JSONObject(MigrationActivationFixtures.tree(destination.child(ui.activity,"migration-candidates")))).toString())
        assertTrue(encrypted.delete());owned.verifyDefault()
    }
    @Test fun readSameBusinessCandidateUnderDifferentPidWithoutAuthorityOrReplay()=runBlocking {
        val run=runId();val file=proof(run);require(file.isFile&&file.length() in 1..256*1024);val fixture=StrictMigrationJson.parse(file.readBytes())
        require(fixture.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","candidateId","candidateSha256","recordCount","rawHashes","sourceCiphertext","rootCiphertext","candidateCiphertext"))
        require(fixture.get("version")==1&&fixture.get("kind")=="p7-easywork-business-cold-v1"&&fixture.get("run")==run);assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        for(source in listOf(true,false))require(File(ui.activity.noBackupFilesDir,namespace(run,source)).isDirectory)
        val source=storage(run,true);val destination=storage(run,false);val candidate=destination.child(ui.activity,"migration-candidates")
        assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source));assertEquals(hashes(fixture.getJSONObject("rootCiphertext")),MigrationActivationFixtures.tree(destination))
        val candidateBefore=MigrationActivationFixtures.tree(candidate);assertEquals(hashes(fixture.getJSONObject("candidateCiphertext")),candidateBefore)
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"));assertEquals(receipt,NativeMigrationCandidateStore(ui.activity,destination).currentReceipt())
        val generation=NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt){true}).activate(receipt)
        val current=destination.reopen(ui.activity);val client=decodeClient(current.metadata()!!.getJSONArray("clients").getJSONObject(0));assertNotNull(client.migrationEasyWork);assertNull(client.agentConnection);assertFalse(client.online)
        assertEquals("none",client.migrationEasyWork!!.credentialState);assertTrue(client.migrationEasyWork!!.requiresPairing);assertEquals("Original cached body",NativeHistoryCache(current).read(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD))!!.messages.single().text)
        val originalClient=decodeClient(source.metadata()!!.getJSONArray("clients").getJSONObject(0));assertTrue(sameScope(client.scope,originalClient.scope));assertNotEquals(client.readerId,originalClient.readerId)
        val rawHashes=fixture.getJSONObject("rawHashes");val archives=NativeMigrationEasyWorkArchives.readOnlyArchives(current).single()
        for(name in archives.names()){assertEquals(rawHashes.getString(name),NativeChatNotificationWire.hash(requireNotNull(archives.readOnly(name))));assertNull(current.read(name))}
        val originalJournal=NativeOperationJournal(source);val journal=NativeOperationJournal(current)
        for(target in listOf(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD),client.cacheKey(null))) {
            val originals=originalJournal.records(target).associateBy {it.id}
            for(row in journal.records(target)){val old=originals.getValue(row.id);assertTrue(row.migrationReadOnly);assertEquals(old.intent,row.intent);assertEquals(old.intentSha256,row.intentSha256);assertEquals(old.result,row.result);assertEquals(old.phase,row.phase);assertTrue(runCatching {journal.markSubmitting(row.target,row.id)}.isFailure)}
        }
        val metadata=current.metadata()!!;metadata.getJSONObject("drafts").put(client.cacheKey(NativeMigrationEasyWorkBusinessFixtures.THREAD),"later independent business draft");current.write("metadata",metadata.toString());val after=MigrationActivationFixtures.tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,current).activate(receipt));assertEquals(after,MigrationActivationFixtures.tree(current))
        assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidate));assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source))
        File(owned.folder,"business-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("sameCandidate",true)
            .put("sourceAndCandidateUnchanged",true).put("exactCacheAndImmutableOperations",true).put("incomingAuthority",0).put("requestsSignerRefreshRevokeOrReplay",0).toString())
    }
}
