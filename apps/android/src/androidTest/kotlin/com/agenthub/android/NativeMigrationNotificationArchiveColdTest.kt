package com.agenthub.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Opt-in original writer/reader process boundary, encrypted publication and actual local identities. */
class NativeMigrationNotificationArchiveColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"notification-archive-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    @Test fun publishArchivedSourceAndPreservedLocalUnknownForIndependentProcessRead() {
        val source=owned.storage.child(ui.activity,"na-cold-source");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload);val target=NativeChatNoticeTarget.from(client.scope!!)
        val records=payload.getJSONObject("records");records.keys().forEach {source.write(it,records.getString(it))}
        val original=NotificationArchiveFixtures.identity();val authority=NotificationArchiveFixtures.authority(client)
        val raw=mapOf(NativeMigrationForeground.IDENTITY to original.raw,NativeMigrationForeground.JOURNAL to NotificationArchiveFixtures.v1(original,target,authority.fingerprint,true),
            NativeMigrationForeground.RECOVERY to NotificationArchiveFixtures.v2(original,target,authority.fingerprint,"reset"),NativeMigrationForeground.AUTHORIZATIONS to authority.raw)
        raw.forEach {source.write(it.key,it.value)};val sourceCipher=MigrationActivationFixtures.tree(source)
        val local=NotificationArchiveFixtures.identity();val localAuthority=NotificationArchiveFixtures.authority(client)
        val localRecords=mapOf(NativeMigrationForeground.IDENTITY to local.raw,NativeMigrationForeground.RECOVERY to NotificationArchiveFixtures.v2(local,target,localAuthority.fingerprint,"unknown"),NativeMigrationForeground.AUTHORIZATIONS to localAuthority.raw)
        localRecords.forEach {owned.storage.write(it.key,it.value)}
        val password="owned cold original notification archives";val file=File(owned.folder,"notification-archive-cold.migration");NativeMigrationExport(ui.activity,source).sealFile(file,password)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),file,password)
        NativeMigrationActivation(ui.activity,owned.storage,NativeMigrationNotificationActivationLease(owned.storage,receipt){true}).activate(receipt)
        val current=owned.storage.reopen(ui.activity);localRecords.forEach {assertEquals(it.value,current.read(it.key))};assertEquals(sourceCipher,MigrationActivationFixtures.tree(source))
        val archived=NativeMigrationNotificationArchives.readOnlyArchives(current).single();val exact=NativeMigrationNotificationArchives.Original.entries.associate {it.wire to archived.readOnly(it)}
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        // Local private identity itself never enters this exported proof; only its exact encrypted hash does.
        File(owned.folder,"notification-archive-cold-writer.json").writeText(JSONObject().put("version",1).put("kind","notification-archive-cold-v1").put("writerPid",Process.myPid()).put("namespace",namespace)
            .put("sourceDevice",original.device).put("localDevice",local.device).put("archiveId",archived.archiveId).put("originals",JSONObject(exact))
            .put("localRecordHashes",JSONObject(localRecords.mapValues {NotificationArchiveFixtures.hash(it.value)})).put("ciphertexts",JSONObject(MigrationActivationFixtures.tree(current))).toString())
    }
    @Test fun readOriginalArchivesAndLocalUnknownUnderDifferentPidWithoutAuthorityActivationOrCiphertextRewrite() {
        val name=InstrumentationRegistry.getArguments().getString("notificationArchiveFixture")?:error("Original archive writer fixture required")
        require(name.matches(Regex("notification-archive-cold-[a-f0-9]{16}\\.json")));val base=File(ui.activity.filesDir,"acceptance").canonicalFile;val file=File(base,name)
        require(file.canonicalFile==File(base,name)&&file.isFile&&file.length()<=256*1024)
        val fixture=StrictMigrationJson.parse(file.readBytes());require(fixture.keys().asSequence().toSet()==setOf("version","kind","writerPid","namespace","sourceDevice","localDevice","archiveId","originals","localRecordHashes","ciphertexts"))
        require(fixture.get("version")==1&&fixture.getString("kind")=="notification-archive-cold-v1");assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        // Match OwnedControlAcceptance's exact historical namespace; no substitute fresh records.
        val originalStorage=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace")
        val current=originalStorage.reopen(ui.activity);val expected=fixture.getJSONObject("ciphertexts");val before=MigrationActivationFixtures.tree(current)
        assertEquals(expected.keys().asSequence().associateWith {expected.getString(it)},before)
        val archived=NativeMigrationNotificationArchives.readOnlyArchives(current).single {it.archiveId==fixture.getString("archiveId")}
        assertEquals(fixture.getString("sourceDevice"),archived.deviceId);for(kind in NativeMigrationNotificationArchives.Original.entries)assertEquals(fixture.getJSONObject("originals").getString(kind.wire),archived.readOnly(kind))
        val local=fixture.getJSONObject("localRecordHashes");local.keys().forEach {assertEquals(local.getString(it),NotificationArchiveFixtures.hash(current.read(it)!!))}
        assertEquals(fixture.getString("localDevice"),JSONObject(current.read(NativeMigrationForeground.IDENTITY)!!).getString("deviceId"))
        val op=JSONObject(current.read(NativeMigrationForeground.RECOVERY)!!).getJSONArray("targets").getJSONObject(0).getJSONArray("operations").getJSONObject(0)
        assertEquals("unknown",op.getString("state"));assertEquals(JSONObject.NULL,op.get("receipt"));assertTrue(NativeMigrationNotificationArchives.requiresExplicitSetup(current));assertEquals(before,MigrationActivationFixtures.tree(current))
        File(owned.folder,"notification-archive-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("originalArchiveExact",true)
            .put("localIdentityAndUnknownExact",true).put("ciphertextsUnchanged",true).put("newIdentityOrRegisterOrReplay",0).toString())
    }
}
