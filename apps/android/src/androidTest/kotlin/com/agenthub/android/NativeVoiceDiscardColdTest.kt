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

/** Opt-in independent writer/reader PIDs, with actual encrypted migration and activation. */
class NativeVoiceDiscardColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"voice-discard-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun wave()=WavePCM.header(6400)+ByteArray(6400){(it*37%256).toByte()}
    @Test fun stageDiscardedOriginalAndActivateForIndependentProcessRead()=runBlocking {
        val source=owned.storage.child(ui.activity,"vd-cold-source");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload)
        val rows=payload.getJSONObject("records");rows.keys().forEach {source.write(it,rows.getString(it))};val store=VoiceRecordingStore(ui.activity,source)
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"voice-v1:cold","synthetic-thread","original discarded 中文 😀 100% %@")
        store.file(pending.id).writeBytes(wave());val sealed=store.seal(pending);store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        val request=NativeVoiceDiscardRequest(sealed);assertTrue(NativeEncryptedVoiceDiscardPort(store).discard(request).acknowledges(request));val original=MigrationActivationFixtures.tree(source)
        val password="owned discard cold migration";val envelope=File(owned.folder,"discard-cold.migration");NativeMigrationExport(ui.activity,source).sealFile(envelope,password)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),envelope,password)
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertNull(VoiceRecordingStore(ui.activity,current).restore(sealed.scope,sealed.certificate,sealed.draftKey,sealed.originalText));assertEquals(original,MigrationActivationFixtures.tree(source))
        val marker=NativeVoiceDiscardCatalog.read(current).getValue(sealed.id);owned.verifyDefault()
        val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"voice-discard-cold-writer.json").writeText(JSONObject().put("version",1).put("kind","voice-discard-cold-v1").put("writerPid",Process.myPid()).put("namespace",namespace)
            .put("record",sealed.json()).put("fingerprint",request.fingerprint).put("terminal",marker.json()).put("originalAudit",current.read(marker.auditName)).put("generation",generation)
            .put("ciphertexts",JSONObject(MigrationActivationFixtures.tree(current))).toString())
    }
    @Test fun originalTerminalSurvivesIndependentPidAndIdempotentAcknowledgementWithoutCiphertextRewrite()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("voiceDiscardFixture")?:error("Original discard writer fixture required")
        require(name.matches(Regex("voice-discard-cold-[a-f0-9]{16}\\.json")));val base=File(ui.activity.filesDir,"acceptance").canonicalFile;val file=File(base,name)
        require(file.canonicalFile==File(base,name)&&file.isFile&&file.length()<=256*1024)
        val fixture=StrictMigrationJson.parse(file.readBytes());require(fixture.keys().asSequence().toSet()==setOf("version","kind","writerPid","namespace","record","fingerprint","terminal","originalAudit","generation","ciphertexts"))
        require(fixture.get("version")==1&&fixture.getString("kind")=="voice-discard-cold-v1");assertNotEquals(fixture.getInt("writerPid"),Process.myPid())
        val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val storage=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val current=storage.reopen(ui.activity);val before=MigrationActivationFixtures.tree(current)
        val expected=fixture.getJSONObject("ciphertexts");assertEquals(expected.keys().asSequence().associateWith {expected.getString(it)},before)
        val pending=NativeVoiceRecordingRecord.decode(fixture.getJSONObject("record"));val request=NativeVoiceDiscardRequest(pending);assertEquals(fixture.getString("fingerprint"),request.fingerprint)
        val marker=NativeVoiceDiscardCatalog.read(current).getValue(pending.id);assertEquals(fixture.getJSONObject("terminal").toString(),marker.json().toString());assertEquals(fixture.getString("originalAudit"),current.read(marker.auditName))
        val store=VoiceRecordingStore(ui.activity,current);assertNull(store.restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText));assertTrue(store.current(pending).migrationReadOnly)
        assertArrayEquals(wave(),store.bytes(pending));assertTrue(NativeEncryptedVoiceDiscardPort(store).discard(request).acknowledges(request));assertEquals(before,MigrationActivationFixtures.tree(current))
        File(owned.folder,"voice-discard-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("sameOriginalTerminal",true)
            .put("exactAuditAndAudio",true).put("noOffer",true).put("readOnlyRetry",true).put("prepareUploadCancelReplay",0).toString())
    }
}
