package com.agenthub.android

import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID

/** Real Keystore/chunk/pointer boundaries; synthetic PCM only, no microphone or Host. */
class NativeMigrationCapacityTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned capacity migration password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-capacity")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun seed(storage: NativeSessionStorage): MobileClient {
        val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records");rows.keys().forEach{storage.write(it,rows.getString(it))};return MigrationActivationFixtures.client(value)
    }
    private suspend fun voice(storage: NativeSessionStorage,pcm: Int): PendingVoiceRecording {
        val client=seed(storage);val store=VoiceRecordingStore(ui.activity,storage)
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"voice-v1:capacity","synthetic-thread","original capacity 中文 😀 100% %@")
        RandomAccessFile(store.file(pending.id),"rw").use {out->out.write(WavePCM.header(pcm));val chunk=ByteArray(65536){(it%251).toByte()};var n=0;while(n<pcm){val count=minOf(chunk.size,pcm-n);out.write(chunk,0,count);n+=count};out.fd.sync()}
        return store.seal(pending)
    }
    private fun header(parent: NativeSessionStorage)=JSONObject(parent.child(ui.activity,"migration-candidates").read("candidate-v1")!!)
    private fun receipt(id: String,clear: ByteArray)=NativeMigrationCandidateStore.Receipt(id,MigrationActivationFixtures.sha(clear),JSONObject(String(clear)).getJSONObject("records").length())
    private fun candidateFile(parent: NativeSessionStorage,name: String)=File(parent.child(ui.activity,"migration-candidates").coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin")
    private fun chunkName(header: JSONObject,index: Int)="migration-candidate:${header.getString("generation")}:$index"
    private fun rejected(block: ()->Unit){assertTrue(runCatching(block).isFailure)}

    @Test fun maximumFiveMinuteWavRejectsBeforeWholeValueAllocationAndKeepsEveryOriginal()=runBlocking {
        val start=SystemClock.elapsedRealtime();val source=owned.storage.child(ui.activity,"capacity-source");val original=voice(source,9_600_000);val store=VoiceRecordingStore(ui.activity,source)
        store.mark(original,true,true,VoiceReceipt(original.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val before=MigrationActivationFixtures.tree(source)
        val failure=runCatching {NativeMigrationExport(ui.activity,source).seal(password)}.exceptionOrNull()
        assertTrue("Oversize source fails explicitly without OOM",failure is NativeSessionStorage.IncompleteMigrationInventory)
        assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(original.manifest,store.current(original).manifest)
        assertFalse(File(owned.storage.child(ui.activity,"migration-candidates").coordinationKey).listFiles().orEmpty().isNotEmpty())
        File(owned.folder,"maximum-voice-capacity.json").writeText(JSONObject().put("wavBytes",9_600_044).put("durationMs",300_000).put("base64Bytes",12_800_060).put("payloadLimitBytes",NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            .put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("elapsedMs",SystemClock.elapsedRealtime()-start).put("remoteWrites",0).put("maximumVoiceImportPending",true).put("explicitRejectBeforeWholeValue",true).put("sourceCiphertextUnchanged",true).toString())
    }
    @Test fun canonicalExpansionRejectsBelowStoredFileLimitWithoutAllocatingWholeAudio()=runBlocking {
        val source=owned.storage.child(ui.activity,"expansion-source");val original=voice(source,6_600_000)
        val file=File(source.coordinationKey,MigrationActivationFixtures.sha("voice-audio:${original.id}".toByteArray())+".bin")
        assertTrue(file.length()<NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        assertTrue(((original.manifest!!.sizeBytes.toLong()+2)/3)*4>NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val before=MigrationActivationFixtures.tree(source)
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).capture()}.exceptionOrNull() is NativeSessionStorage.IncompleteMigrationInventory)
        assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(original.manifest,VoiceRecordingStore(ui.activity,source).current(original).manifest)
    }
    @Test fun boundedMultiChunkVoiceActivatesOriginalWithReadOnlyReceiptAndNoCandidateRewrite()=runBlocking {
        val source=owned.storage.child(ui.activity,"bounded-source");val original=voice(source,400_000);val store=VoiceRecordingStore(ui.activity,source)
        store.mark(original,true,true,VoiceReceipt(original.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val before=MigrationActivationFixtures.tree(source);val envelope=NativeMigrationExport(ui.activity,source).seal(password);val candidates=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val receipt=candidates.stage(UUID.randomUUID().toString(),envelope,password);val saved=header(owned.storage)
        assertEquals(2,saved.getInt("version"));assertFalse(saved.has("payload"));assertTrue(saved.getJSONArray("chunks").length()>=2)
        val candidateBefore=MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates"));assertEquals(receipt,candidates.stage(receipt.id,envelope,password));assertEquals(candidateBefore,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")))
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity);val fresh=VoiceRecordingStore(ui.activity,current)
        val restored=fresh.restore(original.scope,original.certificate,original.draftKey,original.originalText)!!;assertEquals(original.manifest,restored.manifest);assertTrue(restored.migrationReadOnly&&restored.prepareAttempted&&restored.audioAttempted)
        val bytes=fresh.bytes(restored);try{assertEquals(400_044,bytes.size);original.manifest!!.verify(bytes)}finally{bytes.fill(0)}
        current.write("later-edit","new draft survives retry");val after=MigrationActivationFixtures.tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,MigrationActivationFixtures.tree(current));assertEquals(before,MigrationActivationFixtures.tree(source))
    }
    @Test fun interruptedChunkAndFinalHeaderPublicationKeepOriginalCandidateAndInstallation()=runBlocking {
        val source=owned.storage.child(ui.activity,"interrupt-source");voice(source,400_000)
        val envelope=NativeMigrationExport(ui.activity,source).seal(password);val clear=NativeMigrationCrypto.open(envelope,password);val count=(clear.size+NativeMigrationCandidateStore.CANDIDATE_CHUNK_BYTES-1)/NativeMigrationCandidateStore.CANDIDATE_CHUNK_BYTES;clear.fill(0);assertTrue(count>=2)
        val candidates=NativeMigrationCandidateStore(ui.activity,owned.storage);val prior=MigrationActivationFixtures.stage(ui.activity,owned.storage,MigrationActivationFixtures.payload());val before=MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates"));val activeBefore=MigrationActivationFixtures.tree(owned.storage)
        for(at in listOf(1,count,0)) {
            rejected {candidates.stage(UUID.randomUUID().toString(),envelope,password,prior){if(it==at)throw java.io.IOException("owned candidate interruption")}}
            assertEquals(before,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")));assertEquals(activeBefore,MigrationActivationFixtures.tree(owned.storage));candidates.read(prior)
        }
    }
    @Test fun missingChangedAndReorderedChunksAreRejectedWithoutReplacingOrRepairingOriginal()=runBlocking {
        val source=owned.storage.child(ui.activity,"chunk-source");voice(source,400_000);val envelope=NativeMigrationExport(ui.activity,source).seal(password)
        val candidates=NativeMigrationCandidateStore(ui.activity,owned.storage);val receipt=candidates.stage(UUID.randomUUID().toString(),envelope,password);val saved=header(owned.storage);assertTrue(saved.getJSONArray("chunks").length()>=2)
        val a=candidateFile(owned.storage,chunkName(saved,0));val b=candidateFile(owned.storage,chunkName(saved,1));val original=a.readBytes();val second=b.readBytes()
        for(kind in listOf("missing","changed","reordered")) {
            when(kind){"missing"->assertTrue(a.delete());"changed"->a.writeBytes(original.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()});else->a.writeBytes(second)}
            val before=MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates"));rejected{candidates.readArchive(receipt)};rejected{candidates.stage(UUID.randomUUID().toString(),envelope,password,receipt)};assertEquals(before,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")))
            a.writeBytes(original) // Restore only this test's deliberately damaged private fixture.
        };candidates.readArchive(receipt);Unit
    }
    @Test fun legacyInlineCandidateColdReadRemainsExactAndExplicitReplacementUsesChunks() {
        val clear=MigrationActivationFixtures.payload().toString().toByteArray();val id=UUID.randomUUID().toString();val receipt=receipt(id,clear)
        val child=owned.storage.child(ui.activity,"migration-candidates");child.write("candidate-v1",JSONObject().put("version",1).put("id",id).put("sha256",receipt.sha256).put("payload",Base64.getEncoder().encodeToString(clear)).toString())
        val before=MigrationActivationFixtures.tree(child);val candidates=NativeMigrationCandidateStore(ui.activity,owned.storage.reopen(ui.activity));val expected=candidates.validate(clear)
        assertEquals(expected,candidates.read(receipt));assertEquals(before,MigrationActivationFixtures.tree(child));assertEquals(receipt,candidates.stage(id,NativeMigrationCrypto.seal(clear,password),password));assertEquals(before,MigrationActivationFixtures.tree(child))
        val next=candidates.stage(UUID.randomUUID().toString(),NativeMigrationCrypto.seal(clear,password),password,receipt);assertEquals(2,header(owned.storage).getInt("version"));assertEquals(expected,candidates.read(next));rejected{candidates.read(receipt)};clear.fill(0)
    }
    @Test fun chunkHeaderTypesCountsAndHashesCannotBeCoercedOrPartiallyAccepted() {
        val receipt=MigrationActivationFixtures.stage(ui.activity,owned.storage,MigrationActivationFixtures.payload());val child=owned.storage.child(ui.activity,"migration-candidates");val original=child.read("candidate-v1")!!
        val changes=listOf<(JSONObject)->Unit>({it.put("size","1")},{it.put("size",NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES+1)},{it.put("records",receipt.recordCount+1)},{it.put("generation","../other")},{it.put("chunks",JSONArray())},{it.getJSONArray("chunks").getJSONObject(0).put("size","1")},{it.getJSONArray("chunks").getJSONObject(0).put("sha256","0".repeat(64))},{it.put("unexpected",true)})
        for(change in changes){val row=JSONObject(original);change(row);child.write("candidate-v1",row.toString());val before=MigrationActivationFixtures.tree(child);rejected{NativeMigrationCandidateStore(ui.activity,owned.storage).readArchive(receipt)};assertEquals(before,MigrationActivationFixtures.tree(child))};child.write("candidate-v1",original)
    }
    @Test fun payloadAndEnvelopeQuotasRejectBeforeCandidateReplacement() {
        val candidates=NativeMigrationCandidateStore(ui.activity,owned.storage);val prior=MigrationActivationFixtures.stage(ui.activity,owned.storage,MigrationActivationFixtures.payload());val before=MigrationActivationFixtures.tree(owned.storage)
        rejected{candidates.validateArchive(ByteArray(NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES+1))};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        rejected{candidates.stage(UUID.randomUUID().toString(),ByteArray(NativeMigrationCandidateStore.MAX_ENVELOPE_BYTES+1),password,prior)};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));candidates.read(prior)
    }
}
