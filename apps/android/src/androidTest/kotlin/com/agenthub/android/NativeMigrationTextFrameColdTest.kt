package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** New opt-in pair. Never invoke a previous writer or reuse its run UUID. No Host/model port. */
class NativeMigrationTextFrameColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-text-frame-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun runId()=requireNotNull(InstrumentationRegistry.getArguments().getString("textFrameColdRun")){"Fresh parent-owned textFrameColdRun UUID required"}.also {require(UUID.fromString(it).toString()==it)}
    private fun namespace(run: String,source: Boolean)=if(source)"P7-text-source-$run"else"P7-text-cold-$run"
    private fun storage(run: String,source: Boolean)=namespace(run,source).let {NativeSessionStorage(ui.activity,it,it)}
    private fun proof(run: String)=File(ui.activity.filesDir,"acceptance/text-frame-cold-$run.json").also {
        require(it.canonicalFile==File(File(ui.activity.filesDir,"acceptance").canonicalFile,it.name))
    }
    private fun hashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    private fun producer(name: String,raw: String) {
        val type=Class.forName("com.agenthub.android.$name");val companion=type.getField("Companion").get(null)
        companion.javaClass.getDeclaredMethod("validateEncoded",String::class.java).invoke(companion,raw)
    }
    @Test fun stageFreshMaximumTextCandidateForDifferentProcess() {
        val run=runId()
        for(source in listOf(false,true))require(!File(ui.activity.noBackupFilesDir,namespace(run,source)).exists()){ "Fresh run required; original writer must not be replayed" }
        require(!proof(run).exists())
        val source=storage(run,true);val destination=storage(run,false);val payload=MigrationActivationFixtures.payload();val rows=payload.getJSONObject("records")
        rows.keys().forEach {source.write(it,rows.getString(it))}
        val client=MigrationActivationFixtures.client(payload);val target=NativeChatNoticeTarget.from(client.scope!!);val identity=NotificationArchiveFixtures.identity();val authority=NotificationArchiveFixtures.authority(client)
        source.write(NativeMigrationForeground.IDENTITY,identity.raw);val rawDigests=JSONObject();var total=0L
        fun save(name: String,raw: String,limit: Int,decoder: String) {
            val text=raw+" ".repeat(limit-256-raw.toByteArray(Charsets.UTF_8).size);producer(decoder,text)
            rawDigests.put(name,NotificationArchiveFixtures.hash(text));total+=text.toByteArray(Charsets.UTF_8).size;source.write(name,text)
        }
        save(NativeMigrationForeground.JOURNAL,NotificationArchiveFixtures.v1(identity,target,authority.fingerprint),4*1024*1024,"NativeChatNotificationForegroundStore")
        save(NativeMigrationForeground.RECOVERY,NotificationArchiveFixtures.v2(identity,target,authority.fingerprint,"unknown"),4*1024*1024,"NativeChatNotificationRecoveryStore")
        save(NativeMigrationForeground.AUTHORIZATIONS,authority.raw,1024*1024,"NativeChatNotificationAuthorizations")
        assertTrue(total>8*1024*1024);val before=MigrationActivationFixtures.tree(source);val encrypted=File(owned.folder,"text-cold.encrypted")
        NativeMigrationExport(ui.activity,source).sealFile(encrypted,"owned maximum text cold password")
        val receipt=NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),encrypted,"owned maximum text cold password")
        NativeMigrationStreamCandidate(ui.activity,destination).read(receipt){assertEquals(4,it.version);assertTrue(it.text is NativeMigrationTextMap)}
        assertEquals(before,MigrationActivationFixtures.tree(source))
        proof(run).writeText(JSONObject().put("version",1).put("kind","p7-text-frame-cold-v1").put("run",run).put("writerPid",Process.myPid())
            .put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount).put("sourceBytes",total)
            .put("rawDigests",rawDigests).put("sourceCiphertext",JSONObject(before)).put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(destination)))
            .put("candidateCiphertext",JSONObject(MigrationActivationFixtures.tree(destination.child(ui.activity,"migration-candidates")))).toString())
        assertTrue(encrypted.delete());owned.verifyDefault()
    }
    @Test fun readAndActivateSameMaximumTextCandidateUnderDifferentPidWithoutReplacingIt() {
        val run=runId();val file=proof(run);require(file.isFile&&file.length() in 1..128*1024)
        val fixture=StrictMigrationJson.parse(file.readBytes())
        require(fixture.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","candidateId","candidateSha256","recordCount","sourceBytes","rawDigests","sourceCiphertext","rootCiphertext","candidateCiphertext"))
        require(fixture.get("version")==1&&fixture.get("kind")=="p7-text-frame-cold-v1"&&fixture.get("run")==run)
        assertNotEquals("Independent process required",fixture.getInt("writerPid"),Process.myPid());assertTrue(fixture.getLong("sourceBytes")>8*1024*1024)
        for(source in listOf(false,true))require(File(ui.activity.noBackupFilesDir,namespace(run,source)).isDirectory)
        val destination=storage(run,false);val source=storage(run,true);val candidate=destination.child(ui.activity,"migration-candidates")
        assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source))
        assertEquals(hashes(fixture.getJSONObject("rootCiphertext")),MigrationActivationFixtures.tree(destination))
        val candidateBefore=MigrationActivationFixtures.tree(candidate);assertEquals(hashes(fixture.getJSONObject("candidateCiphertext")),candidateBefore)
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"))
        assertEquals(receipt,NativeMigrationCandidateStore(ui.activity,destination).currentReceipt())
        val generation=NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt){true}).activate(receipt)
        val current=destination.reopen(ui.activity);val archived=NativeMigrationNotificationArchives.readOnlyArchives(current).single()
        for((name,kind)in listOf(NativeMigrationForeground.JOURNAL to NativeMigrationNotificationArchives.Original.FOREGROUND_V1,
            NativeMigrationForeground.RECOVERY to NativeMigrationNotificationArchives.Original.RECOVERY_V2,
            NativeMigrationForeground.AUTHORIZATIONS to NativeMigrationNotificationArchives.Original.AUTHORIZATIONS_V1))
            assertEquals(fixture.getJSONObject("rawDigests").getString(name),NotificationArchiveFixtures.hash(requireNotNull(archived.readOnly(kind))))
        val unknown=JSONObject(requireNotNull(archived.readOnly(NativeMigrationNotificationArchives.Original.RECOVERY_V2))).getJSONArray("targets").getJSONObject(0).getJSONArray("operations").getJSONObject(0)
        assertEquals("unknown",unknown.getString("state"));assertEquals(JSONObject.NULL,unknown.get("receipt"))
        NativeMigrationForeground.ACTIVE_NAMES.forEach {assertNull(current.read(it))}
        assertTrue(NativeMigrationNotificationArchives.requiresExplicitSetup(current));assertFalse(NativeChatNotificationStore(current).preferences().enabled)
        current.write("later-local-text-draft","later independent draft 中文 %@ SourceID")
        val after=MigrationActivationFixtures.tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,current).activate(receipt))
        assertEquals("later independent draft 中文 %@ SourceID",current.read("later-local-text-draft"));assertEquals(after,MigrationActivationFixtures.tree(current))
        assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidate));assertEquals(hashes(fixture.getJSONObject("sourceCiphertext")),MigrationActivationFixtures.tree(source))
        File(owned.folder,"text-frame-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid())
            .put("sameCandidate",true).put("exactOriginalText",true).put("sourceAndCandidateCiphertextUnchanged",true).put("laterDraftPreserved",true)
            .put("newIdentityOrRegistrationOrReplay",0).toString())
    }
}
