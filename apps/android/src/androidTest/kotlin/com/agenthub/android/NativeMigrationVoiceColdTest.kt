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

/** Real process boundary after typed voice export, staged validation and generation activation. */
class NativeMigrationVoiceColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-voice-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun wave()=WavePCM.header(524288)+ByteArray(524288){(it*37%256).toByte()}
    private fun candidateProof(): JSONObject {
        val header=JSONObject(owned.storage.child(ui.activity,"migration-candidates").read("candidate-v1")!!)
        assertEquals(2,header.getInt("version"));assertTrue(header.getJSONArray("chunks").length()>=3)
        return JSONObject().put("localVersion",header.getInt("version")).put("chunks",header.getJSONArray("chunks").length())
    }
    private fun tree(folder: File)=folder.walkTopDown().filter{it.isFile}.associate{it.relativeTo(folder).path to MigrationActivationFixtures.sha(it.readBytes())}
    @Test fun exportAndActivateOriginalVoiceForSeparateProcessRead()=runBlocking{
        val source=owned.storage.child(ui.activity,"voice-cold-source");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload);val rows=payload.getJSONObject("records");rows.keys().forEach{source.write(it,rows.getString(it))}
        val store=VoiceRecordingStore(ui.activity,source);val key=client.cacheKey("synthetic-thread");val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,key,"voice-v1:original-cold","synthetic-thread","original imported cold 中文 😀")
        store.file(pending.id).writeBytes(wave());val sealed=store.seal(pending);store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val originalSource=MigrationActivationFixtures.tree(source);val password="owned voice cold migration";val envelope=NativeMigrationExport(ui.activity,source).seal(password)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),envelope,password);val archive=NativeMigrationCandidateStore(ui.activity,owned.storage).readArchive(receipt)
        val chunkProof=candidateProof();val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        val metadata=current.metadata()!!;metadata.getJSONObject("drafts").put(key,"edited after voice import 中文 😀 100% %@");current.write("metadata",metadata.toString())
        val imported=VoiceRecordingStore(ui.activity,current).restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText)!!;assertTrue(imported.migrationReadOnly&&imported.prepareAttempted&&imported.audioAttempted)
        assertArrayEquals(wave(),VoiceRecordingStore(ui.activity,current).bytes(imported));assertEquals(originalSource,MigrationActivationFixtures.tree(source))
        val expected=archive.text.keys.associateWith{current.read(it)!!};owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"migration-voice-writer.json").writeText(JSONObject().put("version",1).put("kind","migration-voice-cold").put("namespace",namespace).put("writerPid",Process.myPid())
            .put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount).put("generation",generation).put("candidateChunkProof",chunkProof).put("voice",imported.json())
            .put("records",JSONObject(expected)).put("originalCiphertext",JSONObject(MigrationActivationFixtures.tree(current))).put("sourceCiphertext",JSONObject(originalSource)).toString())
    }
    @Test fun recoverOriginalImportReceiptAndObserveOnlyVoiceInFreshProcess()=runBlocking{
        val name=InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original voice import fixture required");require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1&&fixture.getString("kind")=="migration-voice-cold");assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        assertEquals(2,fixture.getJSONObject("candidateChunkProof").getInt("localVersion"));assertTrue(fixture.getJSONObject("candidateChunkProof").getInt("chunks")>=3)
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace);val current=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=MigrationActivationFixtures.tree(current)
        assertEquals(fixture.getJSONObject("originalCiphertext").toString(),JSONObject(before).toString());val rows=fixture.getJSONObject("records");for(key in rows.keys())assertEquals(rows.getString(key),current.read(key))
        val original=fixture.getJSONObject("voice");val store=VoiceRecordingStore(ui.activity,current);val restored=store.restore(AISharingScope.decode(original.getJSONObject("scope")),original.optional("certificate"),original.getString("draftKey"),original.getString("originalText"))!!
        assertEquals(original.toString(),restored.json().toString());assertTrue(restored.migrationReadOnly&&restored.prepareAttempted&&restored.audioAttempted);assertArrayEquals(wave(),store.bytes(restored));assertEquals("outcome-unknown",JSONObject(current.read("voice-recording:${restored.id}")!!).getString("phase"))
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"));assertEquals(fixture.getString("generation"),NativeMigrationActivation(ui.activity,current).activate(receipt))
        assertEquals("edited after voice import 中文 😀 100% %@",current.metadata()!!.getJSONObject("drafts").getString(restored.draftKey));assertEquals(before,MigrationActivationFixtures.tree(current))
        val sourceFolder=File(ui.activity.noBackupFilesDir,"P4-owned-control-$namespace-voice-cold-source");assertEquals(fixture.getJSONObject("sourceCiphertext").toString(),JSONObject(tree(sourceFolder)).toString())
        File(owned.folder,"migration-voice-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("differentProcess",true).put("sameDeviceKeystore",true)
            .put("originalImportedAudio",true).put("originalImportReceipt",true).put("originalManifestAndAttemptFences",true).put("importedVoiceObserveOnly",true).put("laterDraftPreserved",true).put("importedCiphertextUnchanged",true).put("sourceCiphertextUnchanged",true).put("remoteWrites",0).toString())
    }
    @Test fun stageOriginalVoiceCandidateForSeparateProcessActivation()=runBlocking{
        val source=owned.storage.child(ui.activity,"voice-cold-source");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload);val rows=payload.getJSONObject("records");rows.keys().forEach{source.write(it,rows.getString(it))}
        val store=VoiceRecordingStore(ui.activity,source);val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"voice-v1:staged-cold","synthetic-thread","original staged 中文 😀")
        store.file(pending.id).writeBytes(wave());val sealed=store.seal(pending);store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val sourceBefore=MigrationActivationFixtures.tree(source);val password="owned staged voice migration";val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),NativeMigrationExport(ui.activity,source).seal(password),password)
        val chunkProof=candidateProof();val archive=NativeMigrationCandidateStore(ui.activity,owned.storage).readArchive(receipt);assertEquals(sourceBefore,MigrationActivationFixtures.tree(source));owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"staged-migration-voice-writer.json").writeText(JSONObject().put("version",1).put("kind","migration-voice-staged-cold").put("namespace",namespace).put("writerPid",Process.myPid())
            .put("candidateId",receipt.id).put("candidateSha256",receipt.sha256).put("recordCount",receipt.recordCount).put("candidateChunkProof",chunkProof).put("voice",sealed.json()).put("records",JSONObject(archive.text)).put("binary",JSONObject(archive.binary))
            .put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(owned.storage))).put("sourceCiphertext",JSONObject(sourceBefore)).toString())
    }
    @Test fun activateOriginalStagedVoiceInFreshProcessWithoutReplacingCandidate()=runBlocking{
        val name=InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original staged voice fixture required");require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1&&fixture.getString("kind")=="migration-voice-staged-cold");assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        assertEquals(2,fixture.getJSONObject("candidateChunkProof").getInt("localVersion"));assertTrue(fixture.getJSONObject("candidateChunkProof").getInt("chunks")>=3)
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace);val destination=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=MigrationActivationFixtures.tree(destination)
        assertEquals(fixture.getJSONObject("rootCiphertext").toString(),JSONObject(before).toString());val candidateRoot=destination.child(ui.activity,"migration-candidates");val candidateBefore=MigrationActivationFixtures.tree(candidateRoot)
        val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("candidateSha256"),fixture.getInt("recordCount"));val archive=NativeMigrationCandidateStore(ui.activity,destination).readArchive(receipt)
        assertEquals(fixture.getJSONObject("records").toString(),JSONObject(archive.text).toString());assertEquals(fixture.getJSONObject("binary").toString(),JSONObject(archive.binary).toString());assertEquals(before,MigrationActivationFixtures.tree(destination))
        val generation=NativeMigrationActivation(ui.activity,destination).activate(receipt);val current=destination.reopen(ui.activity);val voice=fixture.getJSONObject("voice");val store=VoiceRecordingStore(ui.activity,current)
        val restored=store.restore(AISharingScope.decode(voice.getJSONObject("scope")),voice.optional("certificate"),voice.getString("draftKey"),voice.getString("originalText"))!!
        assertTrue(restored.migrationReadOnly&&restored.prepareAttempted&&restored.audioAttempted);assertEquals(voice.getString("id"),restored.manifest!!.operationId);assertArrayEquals(wave(),store.bytes(restored));assertEquals("outcome-unknown",JSONObject(current.read("voice-recording:${restored.id}")!!).getString("phase"))
        assertEquals(generation,NativeMigrationActivation(ui.activity,current).activate(receipt));assertEquals(candidateBefore,MigrationActivationFixtures.tree(candidateRoot));assertEquals(fixture.getJSONObject("sourceCiphertext").toString(),JSONObject(tree(File(ui.activity.noBackupFilesDir,"P4-owned-control-$namespace-voice-cold-source"))).toString())
        File(owned.folder,"staged-migration-voice-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("differentProcess",true).put("sameDeviceKeystore",true)
            .put("originalCandidateMatched",true).put("noCandidateReplacement",true).put("originalAudioAndOperation",true).put("originalUnknownReceipt",true).put("importedVoiceObserveOnly",true).put("originalImportReceipt",true).put("sourceCiphertextUnchanged",true).put("remoteWrites",0).toString())
    }

}
