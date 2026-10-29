package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Fresh isolated writer/reader pair. Exact original pending/confirmed authority stays archive-only. */
class NativeMigrationEasyWorkArchiveColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-easywork-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun runId()=requireNotNull(InstrumentationRegistry.getArguments().getString("easyWorkArchiveColdRun")){"Fresh parent-owned EasyWork cold UUID required"}.also {require(UUID.fromString(it).toString()==it)}
    private fun namespace(run: String,source: Boolean)=if(source)"P7-ew-source-$run"else"P7-ew-cold-$run"
    private fun storage(run: String,source: Boolean)=namespace(run,source).let {NativeSessionStorage(ui.activity,it,it)}
    private fun file(run: String)=File(ui.activity.filesDir,"acceptance/easywork-archive-cold-$run.json").also {require(it.canonicalFile==File(File(ui.activity.filesDir,"acceptance").canonicalFile,it.name))}
    private fun hashes(v: JSONObject)=v.keys().asSequence().associateWith {v.getString(it)}
    @Test fun stageFreshOriginalFenceAndUnactivatedCredentialForDifferentProcess() {
        val run=runId();for(source in listOf(false,true))require(!File(ui.activity.noBackupFilesDir,namespace(run,source)).exists()){ "Original writer must not be replayed" };require(!file(run).exists())
        val source=storage(run,true);val destination=storage(run,false);val rows=MigrationActivationFixtures.payload().getJSONObject("records");rows.keys().forEach {source.write(it,rows.getString(it))}
        val seed=NativeMigrationEasyWorkFixtures.seed(ui.activity,source,"confirmed-missing");val names=seed.originalNames+NativeMigrationEasyWorkFixtures.oidc(source,seed.identity)
        val rawHashes=names.associateWith {NativeChatNotificationWire.hash(requireNotNull(source.read(it)))};val sourceBefore=MigrationActivationFixtures.tree(source)
        val encrypted=File(owned.folder,"original-ew.encrypted");NativeMigrationExport(ui.activity,source).sealFile(encrypted,"owned original EasyWork cold password")
        val receipt=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),encrypted,"owned original EasyWork cold password")
        assertEquals(sourceBefore,MigrationActivationFixtures.tree(source))
        file(run).writeText(JSONObject().put("version",1).put("kind","p7-easywork-archive-cold-v1").put("run",run).put("writerPid",Process.myPid())
            .put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount).put("sourceDeviceId",seed.identity.deviceId)
            .put("rawHashes",JSONObject(rawHashes)).put("sourceCiphertext",JSONObject(sourceBefore)).put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(destination)))
            .put("candidateCiphertext",JSONObject(MigrationActivationFixtures.tree(destination.child(ui.activity,"migration-candidates")))).toString())
        assertTrue(encrypted.delete());owned.verifyDefault()
    }
    @Test fun readSameOriginalCandidateAndConfirmNoIncomingAuthorityUnderDifferentPid() {
        val run=runId();val proof=file(run);require(proof.isFile&&proof.length() in 1..128*1024);val fixture=StrictMigrationJson.parse(proof.readBytes())
        require(fixture.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","candidateId","candidateSha256","recordCount","sourceDeviceId","rawHashes","sourceCiphertext","rootCiphertext","candidateCiphertext"))
        require(fixture.get("version")==1&&fixture.get("kind")=="p7-easywork-archive-cold-v1"&&fixture.get("run")==run);assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        for(source in listOf(false,true))require(File(ui.activity.noBackupFilesDir,namespace(run,source)).isDirectory)
        val source=storage(run,true);val destination=storage(run,false);val candidate=destination.child(ui.activity,"migration-candidates")
        assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source));assertEquals(hashes(fixture.getJSONObject("rootCiphertext")),MigrationActivationFixtures.tree(destination))
        val candidateBefore=MigrationActivationFixtures.tree(candidate);assertEquals(hashes(fixture.getJSONObject("candidateCiphertext")),candidateBefore)
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"))
        assertEquals(receipt,NativeMigrationCandidateStore(ui.activity,destination).currentReceipt())
        val generation=NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt){true}).activate(receipt)
        val current=destination.reopen(ui.activity);val archive=NativeMigrationEasyWorkArchives.readOnlyArchives(current).single();val raw=fixture.getJSONObject("rawHashes")
        assertEquals(fixture.getString("sourceDeviceId"),archive.deviceId);assertEquals(raw.keys().asSequence().toSet(),archive.names())
        for(name in raw.keys()){assertEquals(raw.getString(name),NativeChatNotificationWire.hash(requireNotNull(archive.readOnly(name))));assertNull(current.read(name))}
        val fence=JSONObject(requireNotNull(archive.readOnly(archive.names().single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)}))).getJSONObject("revoke")
        assertEquals("confirmed",fence.getString("state"));assertEquals("original pending extension",JSONObject(fence.getString("saved")).getJSONObject("refreshOperation").getString("futurePending"))
        assertTrue(current.metadata()!!.getJSONArray("clients").objects().map(::decodeClient).all {it.agentConnection==null&&it.scope?.agentId=="codex"})
        current.write("later-local-ew-archive-draft","later independent draft");val after=MigrationActivationFixtures.tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,current).activate(receipt));assertEquals(after,MigrationActivationFixtures.tree(current))
        assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidate));assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source))
        File(owned.folder,"easywork-archive-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid())
            .put("sameCandidate",true).put("sourceAndCandidateUnchanged",true).put("originalFenceAndCredentialExact",true).put("incomingActiveAuthority",0)
            .put("newIdentitySignerRefreshRevokeOrReplay",0).toString())
    }
}
