package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** P7 sound graph through production Keystore/export/candidate/activation. No player/OS post/Host. */
class NativeMigrationSoundTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned sound migration password"
    private data class Seed(val source: NativeSessionStorage,val item: NativeChatNotificationSound,val catalog: String,val notices: String)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-sound")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun seed(maximum: Boolean=false): Seed {
        val source=owned.storage.child(ui.activity,"sound-source-${UUID.randomUUID().toString().take(8)}");val payload=MigrationActivationFixtures.payload();val rows=payload.getJSONObject("records");rows.keys().forEach{source.write(it,rows.getString(it))}
        val library=NativeChatNotificationSoundLibrary(ui.activity,source)
        val bytes=if(maximum)NativeChatNotificationPcm.wav(ByteArray(NativeChatNotificationPcm.MAX_FRAMES*2){(it*17%251).toByte()})else NativeChatNotificationPcm.builtin("soft.caf")
        val item=try{library.importNormalized("Original synthetic sound","wav",bytes)}finally{bytes.fill(0)}
        val notices=NativeChatNotificationStore(source);notices.update{it.copy(enabled=true,sound=item.id,categorySounds=mapOf(NativeChatNoticeKind.APPROVAL to item.id,NativeChatNoticeKind.QUESTION to "chime.caf"))}
        val target=NativeChatNoticeTarget.from(MigrationActivationFixtures.client(payload).scope!!)
        val event=NativeChatNotificationEvent(readingSHA("migration-original-sound-event"),target,NativeChatNoticeKind.UNCERTAIN,"2026-10-28T12:00:00Z","synthetic-thread",operationId="sound-original-unknown-operation")
        val lease=notices.bind(target,"original-sound-credential-ref");val receipt=notices.claim(event,lease,java.time.Instant.parse(event.occurredAt).toEpochMilli())!!;notices.finish(receipt.ticket,false)
        return Seed(source,item,source.read(NativeMigrationSounds.RECORD)!!,source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!)
    }
    private fun pending(seed: Seed,present: Boolean) {
        val value=JSONObject(seed.catalog);val original=value.getJSONArray("items").getJSONObject(0)
        for(key in listOf("staged","retired"))value.getJSONArray(key).put(JSONObject(original.toString()).put("id",UUID.randomUUID().toString()+".caf"))
        seed.source.write(NativeMigrationSounds.RECORD,value.toString())
        if(present){val bytes=NativeChatNotificationSoundLibrary(ui.activity,seed.source).read(seed.item.id);try{
            for(key in listOf("staged","retired"))seed.source.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(value.getJSONArray(key).getJSONObject(0).getString("id")),bytes,NativeChatNotificationPcm.OUTPUT_LIMIT)
        }finally{bytes.fill(0)}}
    }
    private fun export(source: NativeSessionStorage)=File(owned.folder,"sound-${UUID.randomUUID()}.migration").also{NativeMigrationExport(ui.activity,source).sealFile(it,password)}
    private fun stage(source: NativeSessionStorage)=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),export(source),password)
    @Test fun maximumNormalizedSoundAndHalfStatesMigrateColdWithDestinationUriAndImmutableOriginalAudit() {
        val seed=seed(true);pending(seed,true);val catalog=seed.source.read(NativeMigrationSounds.RECORD)!!;val before=tree(seed.source)
        val file=export(seed.source);val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val receipt=store.stageFile(UUID.randomUUID().toString(),file,password)
        val candidate=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=tree(candidate)
        assertEquals(receipt,store.stageFile(receipt.id,file,password));assertEquals(candidateBefore,tree(candidate))
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(catalog,current.read(NativeMigrationSounds.RECORD));val library=NativeChatNotificationSoundLibrary(ui.activity,current)
        assertEquals(seed.item,library.find(seed.item.id));assertEquals(4,library.catalog().size)
        val bytes=library.read(seed.item.id);try{NativeChatNotificationSoundLibrary.validateBinary(seed.item,bytes);assertEquals(NativeChatNotificationPcm.OUTPUT_LIMIT,bytes.size)}finally{bytes.fill(0)}
        val prefs=NativeChatNotificationStore(current).preferences();assertFalse(prefs.enabled);assertEquals(seed.item.id,prefs.sound);assertEquals(seed.item.id,prefs.categorySounds[NativeChatNoticeKind.APPROVAL])
        val uri=library.uri(seed.item);assertEquals(ui.activity.packageName+".chat-notification-sounds",uri.authority)
        assertEquals(seed.item.sha256,NativeChatNotificationSoundProvider.hash(uri,ui.activity.packageName+".chat-notification-sounds"))
        assertFalse(uri.toString().contains(seed.item.id));assertNull(uri.query);assertNull(uri.fragment)
        val audit=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!).getJSONArray("entries").getJSONObject(0)
        assertEquals(seed.notices,audit.getString("notifications"));assertEquals(before,tree(seed.source));assertEquals(candidateBefore,tree(candidate))
        current.write("later-sound-draft","keep newer draft");val after=tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current))
        File(owned.folder,"sound-migration.json").writeText(JSONObject().put("normalizedBytes",seed.item.bytes).put("sha256",seed.item.sha256).put("exactCatalog",true)
            .put("pendingNotPromoted",true).put("notificationsOff",true).put("originalUnknownAudit",true).put("destinationUriConstructed",true).put("systemProviderReadVerified",false).put("remoteWrites",0).toString())
    }
    @Test fun absentStagedAndRetiredBytesRemainExplicitAcrossActivation() {
        val seed=seed();pending(seed,false);val raw=seed.source.read(NativeMigrationSounds.RECORD)!!;val receipt=stage(seed.source)
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(raw,current.read(NativeMigrationSounds.RECORD));val library=NativeChatNotificationSoundLibrary(ui.activity,current)
        assertEquals(4,library.catalog().size);val value=JSONObject(raw)
        for(key in listOf("staged","retired")){val id=value.getJSONArray(key).getJSONObject(0).getString("id");assertNull(library.find(id));assertTrue(runCatching{library.read(id)}.isFailure)}
    }
    @Test fun missingActiveCorruptPendingAndUnindexedBytesRejectWithoutSourceRewrite() {
        for(kind in listOf("missing-active","corrupt-pending","unindexed")) {
            val seed=seed();pending(seed,true)
            when(kind){
                "missing-active"->seed.source.remove(NativeChatNotificationSoundLibrary.binaryName(seed.item.id))
                "corrupt-pending"->{val value=JSONObject(seed.source.read(NativeMigrationSounds.RECORD)!!);val id=value.getJSONArray("retired").getJSONObject(0).getString("id");seed.source.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(id),NativeChatNotificationPcm.builtin("chime.caf"),NativeChatNotificationPcm.OUTPUT_LIMIT)}
                else->seed.source.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(UUID.randomUUID().toString()+".caf"),NativeChatNotificationPcm.builtin("chime.caf"),NativeChatNotificationPcm.OUTPUT_LIMIT)
            }
            val before=tree(seed.source);assertTrue(kind,runCatching{export(seed.source)}.isFailure);assertEquals(before,tree(seed.source))
        }
    }
    @Test fun authenticatedInvalidNormalizedBytesNeverPublishOrReplaceOriginalCandidate() {
        val seed=seed();val first=stage(seed.source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val before=tree(candidate)
        val wrong=NativeChatNotificationSoundLibrary(ui.activity,seed.source).read(seed.item.id);wrong[24]=0 // Invalid rate, but exact descriptor hash and authenticated envelope.
        val catalog=JSONObject(seed.catalog);catalog.getJSONArray("items").getJSONObject(0).put("sha256",NativeChatNotificationSoundLibrary.sha(wrong))
        seed.source.write(NativeMigrationSounds.RECORD,catalog.toString());seed.source.writeBinaryBytes(NativeChatNotificationSoundLibrary.binaryName(seed.item.id),wrong,NativeChatNotificationPcm.OUTPUT_LIMIT);wrong.fill(0)
        val sourceBefore=tree(seed.source);val file=File(owned.folder,"invalid-normalized.migration")
        seed.source.migrationStreamSnapshot({emptySet()}) {records->file.outputStream().use {out->NativeMigrationStreamCrypto.write(out,password){NativeMigrationStreamArchive.write(it,records,ui.activity.packageName)}}}
        assertTrue(runCatching{NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),file,password,first)}.isFailure)
        assertEquals(before,tree(candidate));assertEquals(sourceBefore,tree(seed.source))
        assertTrue(NativeMigrationStreamCandidate(ui.activity,owned.storage).read(first){it.text.containsKey(NativeMigrationSounds.RECORD)})
    }
    @Test fun missingRetiredOrUnknownSoundReferencesRequireExplicitRepairWithoutChangingReceipts() {
        val seed=seed();pending(seed,false);val notices=NativeChatNotificationStore(seed.source);val originalReceipts=JSONObject(seed.notices).getJSONArray("receipts").toString()
        val retired=JSONObject(seed.source.read(NativeMigrationSounds.RECORD)!!).getJSONArray("retired").getJSONObject(0).getString("id")
        for(id in listOf(UUID.randomUUID().toString()+".caf","unknown.caf",retired)) {
            notices.update{it.copy(sound=id)};val before=tree(seed.source);val failure=runCatching{export(seed.source)}.exceptionOrNull()
            assertTrue(failure is NativeMigrationSounds.MissingReferences);assertEquals(before,tree(seed.source))
            assertEquals(originalReceipts,JSONObject(seed.source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).getJSONArray("receipts").toString())
        }
        // Explicit user-equivalent choice in the producer is the repair; migration performs no substitution.
        notices.update{it.copy(sound="default",categorySounds=emptyMap())};assertTrue(export(seed.source).isFile)
        assertEquals(originalReceipts,JSONObject(seed.source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).getJSONArray("receipts").toString())
    }
    @Test fun futureCatalogAndPermissionFieldsRejectWithoutReplacingCandidate() {
        val seed=seed();val first=stage(seed.source);val candidate=owned.storage.child(ui.activity,"migration-candidates");val before=tree(candidate)
        val clear=NativeMigrationExport(ui.activity,seed.source).capture();val original=try{JSONObject(String(clear,Charsets.UTF_8))}finally{clear.fill(0)}
        for(kind in listOf("version","uri","duplicate")) {
            val value=JSONObject(original.toString());val rows=value.getJSONObject("records");val catalog=JSONObject(rows.getString(NativeMigrationSounds.RECORD))
            when(kind){"version"->catalog.put("version",2);"uri"->catalog.getJSONArray("items").getJSONObject(0).put("sourceUri","content://source-provider/original");else->catalog.getJSONArray("retired").put(catalog.getJSONArray("items").getJSONObject(0))}
            rows.put(NativeMigrationSounds.RECORD,catalog.toString());val envelope=NativeMigrationCrypto.seal(value.toString().toByteArray(),password)
            assertTrue(kind,runCatching{NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),envelope,password,first)}.isFailure);assertEquals(before,tree(candidate))
        }
    }
    @Test fun eachActivationInterruptionRollsBackSoundGraphThenOriginalReceiptPreservesLaterEdits() {
        val seed=seed();pending(seed,true);val receipt=stage(seed.source);val before=tree(owned.storage);val original=tree(seed.source)
        for(at in listOf(1,receipt.recordCount,receipt.recordCount+1,receipt.recordCount+2,0)) {
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==at)throw java.io.IOException("owned sound activation interruption")}}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(original,tree(seed.source))
        }
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        NativeChatNotificationStore(current).update{it.copy(sound="none")};val after=tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current));assertEquals("none",NativeChatNotificationStore(current).preferences().sound)
    }
}
