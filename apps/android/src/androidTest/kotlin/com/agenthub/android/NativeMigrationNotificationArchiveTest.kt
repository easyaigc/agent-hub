package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real encrypted graph/publication; synthetic originals and owner-lease port, no notification runtime. */
class NativeMigrationNotificationArchiveTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned original notification archive migration"
    private class Seed(val storage: NativeSessionStorage,val identity: NotificationArchiveFixtures.Identity,val original: Map<String,String>)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"notification-archives")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun child(prefix: String,parent: NativeSessionStorage=owned.storage)=parent.child(ui.activity,prefix+"-"+UUID.randomUUID().toString().take(8))
    private fun source(mode: String="reset",authority: Boolean=true): Seed {
        val storage=child("na-src");val payload=MigrationActivationFixtures.payload();val rows=payload.getJSONObject("records");rows.keys().forEach {storage.write(it,rows.getString(it))}
        val client=MigrationActivationFixtures.client(payload);val target=NativeChatNoticeTarget.from(client.scope!!);val id=NotificationArchiveFixtures.identity();val auth=NotificationArchiveFixtures.authority(client)
        val original=linkedMapOf(NativeMigrationForeground.IDENTITY to id.raw,NativeMigrationForeground.JOURNAL to NotificationArchiveFixtures.v1(id,target,auth.fingerprint),
            NativeMigrationForeground.RECOVERY to NotificationArchiveFixtures.v2(id,target,auth.fingerprint,mode))
        if(authority)original[NativeMigrationForeground.AUTHORIZATIONS]=auth.raw
        original.forEach {storage.write(it.key,it.value)};NativeChatNotificationStore(storage).update {it.copy(enabled=true)};return Seed(storage,id,original)
    }
    private fun tree(storage: NativeSessionStorage)=MigrationActivationFixtures.tree(storage)
    private fun archive(storage: NativeSessionStorage): JSONObject {
        val clear=NativeMigrationExport(ui.activity,storage).capture();try{return JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}
    }
    private fun portable(storage: NativeSessionStorage): Map<String,String> {
        val rows=archive(storage).getJSONObject("records");return rows.keys().asSequence().associateWith {rows.getString(it)}
    }
    private fun stage(storage: NativeSessionStorage,destination: NativeSessionStorage=owned.storage): NativeMigrationCandidateStore.Receipt {
        val file=File(owned.folder,"na-"+UUID.randomUUID().toString().take(8)+".migration");NativeMigrationExport(ui.activity,storage).sealFile(file,password)
        return NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),file,password)
    }
    private fun activate(destination: NativeSessionStorage,receipt: NativeMigrationCandidateStore.Receipt,current: ()->Boolean={true},checkpoint: (Int)->Unit={}): String =
        NativeMigrationActivation(ui.activity,destination,NativeMigrationNotificationActivationLease(destination,receipt,current)).activate(receipt,checkpoint)
    private fun assertOriginals(storage: NativeSessionStorage,seed: Seed) {
        val audit=NativeMigrationNotificationArchives.readOnlyArchives(storage).single {it.deviceId==seed.identity.device}
        assertEquals("ArchivedNotificationOriginals(redacted)",audit.toString());assertTrue(audit.requiresOriginalInstallation)
        assertEquals(seed.original[NativeMigrationForeground.JOURNAL],audit.readOnly(NativeMigrationNotificationArchives.Original.FOREGROUND_V1))
        assertEquals(seed.original[NativeMigrationForeground.RECOVERY],audit.readOnly(NativeMigrationNotificationArchives.Original.RECOVERY_V2))
        assertEquals(seed.original[NativeMigrationForeground.AUTHORIZATIONS],audit.readOnly(NativeMigrationNotificationArchives.Original.AUTHORIZATIONS_V1))
    }
    @Test fun bothOriginalProtocolsReceiptsAndCredentialsAreArchivedExactlyWithoutPortablePrivateIdentity() {
        val source=source();val before=tree(source.storage);val value=archive(source.storage);val records=portable(source.storage)
        assertTrue(NativeMigrationForeground.ACTIVE_NAMES.none {it in records});assertEquals(4,NativeMigrationNotificationArchives.validatePortable(records).size)
        assertFalse(value.toString().contains(JSONObject(source.identity.raw).getString("privateKey")))
        val index=JSONObject(records.getValue(NativeMigrationNotificationArchives.RECORD));val entry=index.getJSONArray("installations").getJSONObject(0)
        assertEquals(source.identity.device,entry.getJSONObject("installation").getString("deviceId"));assertEquals(0,entry.getJSONArray("missingAuthorizations").length())
        for((kind,record)in listOf("foreground-v1" to NativeMigrationForeground.JOURNAL,"recovery-v2" to NativeMigrationForeground.RECOVERY,"authorizations-v1" to NativeMigrationForeground.AUTHORIZATIONS))
            assertEquals(source.original.getValue(record),records.getValue(NativeMigrationNotificationArchives.PREFIX+entry.getString("archiveId")+":"+kind))
        NativeMigrationCandidateStore(ui.activity,owned.storage).validateArchive(value.toString().toByteArray());assertEquals(before,tree(source.storage))
    }
    @Test fun actualFileActivationRequiresOwnerBoundaryAndKeepsImportedOriginalsInertAfterReopen() {
        val source=source();val sourceBefore=tree(source.storage);val receipt=stage(source.storage);val before=tree(owned.storage)
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure);assertEquals(before,tree(owned.storage))
        activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity);assertOriginals(current,source)
        assertTrue(NativeMigrationNotificationArchives.requiresExplicitSetup(current));for(name in NativeMigrationForeground.ACTIVE_NAMES)assertNull(current.read(name))
        assertFalse(NativeChatNotificationStore(current).preferences().enabled);assertEquals(sourceBefore,tree(source.storage));assertEquals(portable(current),portable(current.reopen(ui.activity)))
        val after=tree(current);NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);assertEquals(after,tree(current))
    }
    @Test fun nonemptyDestinationIdentityJournalsAuthorityAndUnknownStayExactAcrossRollbackAndPublication() {
        val source=source();val local=source("unknown");local.original.forEach {owned.storage.write(it.key,it.value)}
        val receipt=stage(source.storage);val candidate=tree(owned.storage.child(ui.activity,"migration-candidates"));val before=tree(owned.storage)
        for(point in listOf(1,receipt.recordCount,receipt.recordCount+1,0)) {
            assertTrue(runCatching {activate(owned.storage,receipt,checkpoint={if(it==point)throw java.io.IOException("owned archive publish interruption")})}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(candidate,tree(owned.storage.child(ui.activity,"migration-candidates")))
        }
        activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity)
        local.original.forEach {assertEquals(it.value,current.read(it.key))};assertOriginals(current,source);assertNotEquals(source.identity.raw,current.read(NativeMigrationForeground.IDENTITY))
        val unknown=JSONObject(current.read(NativeMigrationForeground.RECOVERY)!!).getJSONArray("targets").getJSONObject(0).getJSONArray("operations").getJSONObject(0)
        assertEquals("unknown",unknown.getString("state"));assertEquals(JSONObject.NULL,unknown.get("receipt"))
        assertTrue(runCatching {owned.storage.write(NativeMigrationForeground.JOURNAL,"late retired coordinator write")}.isFailure)
        // No source archive exists in this branch: the destination's local publication still
        // requires explicit new enrollment, while an ordinary unmigrated installation does not.
        val empty=child("na-empty",current);val rows=MigrationActivationFixtures.payload().getJSONObject("records");rows.keys().forEach {empty.write(it,rows.getString(it))}
        val destination=child("na-local",current);local.original.forEach {destination.write(it.key,it.value)}
        assertFalse(NativeMigrationNotificationArchives.requiresExplicitSetup(destination));assertFalse(destination.migrationHasPublishedGeneration())
        activate(destination,stage(empty,destination));val localCurrent=destination.reopen(ui.activity)
        assertNull(localCurrent.read(NativeMigrationNotificationArchives.RECORD));assertTrue(localCurrent.migrationHasPublishedGeneration())
        assertTrue(NativeMigrationNotificationArchives.requiresExplicitSetup(localCurrent));local.original.forEach {assertEquals(it.value,localCurrent.read(it.key))}
    }
    @Test fun leaseIsBoundToExactRootCandidateAndQuiescentEpochIncludingFinalPublicationCheck() {
        val source=source();val receipt=stage(source.storage);val before=tree(owned.storage);var current=true
        val wrongRoot=NativeMigrationNotificationActivationLease(child("na-wrong"),receipt){true}
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage,wrongRoot).activate(receipt)}.isFailure)
        val wrongReceipt=NativeMigrationNotificationActivationLease(owned.storage,receipt.copy(id=UUID.randomUUID().toString())){true}
        assertTrue(runCatching {NativeMigrationActivation(ui.activity,owned.storage,wrongReceipt).activate(receipt)}.isFailure)
        assertTrue(runCatching {activate(owned.storage,receipt,current={false})}.isFailure)
        assertTrue(runCatching {activate(owned.storage,receipt,current={current},checkpoint={if(it==0)current=false})}.isFailure);assertEquals(before,tree(owned.storage))
        current=true;val generation=activate(owned.storage,receipt,current={current});val fresh=owned.storage.reopen(ui.activity);fresh.write("later-notification-draft","keep later edit");val after=tree(fresh)
        current=false;assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(fresh))
    }
    @Test fun destinationArchiveHistoryMergesAndSameOriginalReexportNeverDuplicatesOrRewritesItsPayload() {
        val a=source();val b=source("live");val prior=portable(b.storage).filterKeys {it==NativeMigrationNotificationArchives.RECORD||it.startsWith(NativeMigrationNotificationArchives.PREFIX)}
        prior.forEach {owned.storage.write(it.key,it.value)};val receipt=stage(a.storage);activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(2,NativeMigrationNotificationArchives.readOnlyArchives(current).size);assertOriginals(current,a);assertOriginals(current,b)
        val exported=portable(current);val merged=NativeMigrationNotificationArchives.mergeDestination(exported,NativeMigrationNotificationArchives.captureDestination(current))
        assertEquals(exported,merged);assertEquals(2,JSONObject(exported.getValue(NativeMigrationNotificationArchives.RECORD)).getJSONArray("installations").length())
    }
    @Test fun missingOriginalAuthorityIsMarkedWithoutBorrowingCurrentConnectionCredentialsOrInventingQueryPermission() {
        val source=source(authority=false);val receipt=stage(source.storage);activate(owned.storage,receipt);val current=owned.storage.reopen(ui.activity)
        val archived=NativeMigrationNotificationArchives.readOnlyArchives(current).single();assertEquals(1,archived.missingAuthorizations.size)
        assertNull(archived.readOnly(NativeMigrationNotificationArchives.Original.AUTHORIZATIONS_V1));assertTrue(archived.requiresOriginalInstallation)
        assertNotNull(current.read("codex-private-connections-v1"));assertNull(current.read(NativeMigrationForeground.AUTHORIZATIONS));assertOriginals(current,source)
    }
    @Test fun unknownReservedRejectedLiveAndResetJournalFormsRetainOriginalIntentsStatesPagesAndReceipts() {
        for(mode in listOf("unknown","reserved","rejected","live","reset")) {
            val source=source(mode);val raw=source.original.getValue(NativeMigrationForeground.RECOVERY);NativeMigrationForegroundV2.decodeEncoded(raw)
            val records=portable(source.storage);val row=JSONObject(records.getValue(NativeMigrationNotificationArchives.RECORD)).getJSONArray("installations").getJSONObject(0)
            assertEquals(raw,records.getValue(NativeMigrationNotificationArchives.PREFIX+row.getString("archiveId")+":recovery-v2"))
            val original=JSONObject(raw).getJSONArray("targets").getJSONObject(0);val expected=original.getJSONArray("operations").getJSONObject(0)
            assertEquals(if(mode in setOf("unknown","reserved","rejected"))mode else"applied",expected.getString("state"))
            if(mode=="reset"){assertEquals(1,original.getJSONArray("gaps").length());assertEquals(2,original.getJSONArray("resetSequence").length());assertEquals("reset",original.getJSONObject("batch").getJSONObject("page").getString("mode"))}
        }
    }
    @Test fun malformedOrMissingActiveOriginalsAndAuthorityFutureSchemasRefuseTheWholeExportUnchanged() {
        for(kind in listOf("identity","identity-key","bound-journal","v1-version","v2-version","intent-hash","receipt","authority-version","authority-token","authority-target")) {
            val source=source();val storage=source.storage
            when(kind) {
                "identity"->storage.remove(NativeMigrationForeground.IDENTITY)
                "identity-key"->storage.write(NativeMigrationForeground.IDENTITY,JSONObject(source.identity.raw).put("publicKey",NotificationArchiveFixtures.identity().publicKey).toString())
                "bound-journal"->{storage.remove(NativeMigrationForeground.JOURNAL);storage.remove(NativeMigrationForeground.RECOVERY)}
                "v1-version"->storage.write(NativeMigrationForeground.JOURNAL,JSONObject(source.original.getValue(NativeMigrationForeground.JOURNAL)).put("version",2).toString())
                "v2-version"->storage.write(NativeMigrationForeground.RECOVERY,JSONObject(source.original.getValue(NativeMigrationForeground.RECOVERY)).put("version",3).toString())
                "intent-hash","receipt"->{val value=JSONObject(source.original.getValue(NativeMigrationForeground.RECOVERY));val op=value.getJSONArray("targets").getJSONObject(0).getJSONArray("operations").getJSONObject(0)
                    if(kind=="intent-hash")op.getJSONObject("original").put("fingerprint","0".repeat(64))else op.getJSONObject("receipt").put("stage","reserved")
                    storage.write(NativeMigrationForeground.RECOVERY,value.toString())}
                else->{val value=JSONObject(source.original.getValue(NativeMigrationForeground.AUTHORIZATIONS));val row=value.getJSONArray("authorizations").getJSONObject(0)
                    when(kind){"authority-version"->value.put("version",2);"authority-token"->row.put("credential","changed-original-credential-"+UUID.randomUUID());else->row.getJSONObject("client").getJSONObject("scope").put("runtimeId","other-runtime")}
                    storage.write(NativeMigrationForeground.AUTHORIZATIONS,value.toString())}
            }
            val before=tree(storage);val file=File(owned.folder,"existing-na-export");file.writeBytes(byteArrayOf(7,4,2));assertTrue(kind,runCatching {NativeMigrationExport(ui.activity,storage).sealFile(file,password)}.isFailure)
            assertArrayEquals(byteArrayOf(7,4,2),file.readBytes());assertEquals(before,tree(storage))
        }
    }
    @Test fun forgedArchiveOrActiveAuthorityCannotReplaceAnOriginalCandidateEvenWithCorrectPassword() {
        val source=source();val value=archive(source.storage);val receipt=stage(source.storage);val candidate=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val before=tree(owned.storage.child(ui.activity,"migration-candidates"));val records=value.getJSONObject("records");val original=records.keys().asSequence().first {it.startsWith(NativeMigrationNotificationArchives.PREFIX)}
        val changes=listOf<(JSONObject)->Unit>(
            {it.remove(original)}, {it.put(original,it.getString(original)+" ")},
            {it.put(NativeMigrationNotificationArchives.PREFIX+"0".repeat(64)+":recovery-v2",source.original.getValue(NativeMigrationForeground.RECOVERY))},
            {it.put(NativeMigrationForeground.IDENTITY,source.identity.raw)}, {it.put(NativeMigrationForeground.AUTHORIZATIONS,source.original.getValue(NativeMigrationForeground.AUTHORIZATIONS))},
            {it.put(NativeMigrationNotificationArchives.RECORD,JSONObject(it.getString(NativeMigrationNotificationArchives.RECORD)).put("version",2).toString())})
        for(change in changes){val broken=JSONObject(value.toString());change(broken.getJSONObject("records"));assertTrue(runCatching {candidate.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(broken.toString().toByteArray(),password),password,receipt)}.isFailure);assertEquals(before,tree(owned.storage.child(ui.activity,"migration-candidates")))}
    }
    @Test fun originalDestinationPendingFilesNeverRecoverAndFrozenProducerDecodersAreIndependentPrerequisites() {
        val source=source();val receipt=stage(source.storage)
        for(name in NativeMigrationForeground.ACTIVE_NAMES)for(suffix in listOf(".bak",".new")) {
            source.original.forEach {owned.storage.write(it.key,it.value)};val file=File(owned.storage.coordinationKey,NotificationArchiveFixtures.hash(name)+".bin"+suffix);file.writeText("owned pending original")
            val before=tree(owned.storage);assertTrue(runCatching {activate(owned.storage,receipt)}.isFailure);assertEquals(before,tree(owned.storage));assertTrue(file.delete())
        }
        // Parent must include cc0b6ac8 producer classes. Missing classes fail this prerequisite, never skip.
        fun producer(name: String,raw: String) {
            val type=Class.forName("com.agenthub.android.$name");val companion=type.getField("Companion").get(null)
            companion.javaClass.getDeclaredMethod("validateEncoded",String::class.java).invoke(companion,raw)
        }
        producer("NativeChatNotificationForegroundStore",source.original.getValue(NativeMigrationForeground.JOURNAL))
        producer("NativeChatNotificationRecoveryStore",source.original.getValue(NativeMigrationForeground.RECOVERY))
        producer("NativeChatNotificationAuthorizations",source.original.getValue(NativeMigrationForeground.AUTHORIZATIONS))
    }
}
