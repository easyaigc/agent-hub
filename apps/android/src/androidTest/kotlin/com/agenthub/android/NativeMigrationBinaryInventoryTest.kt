package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual binary writer/authenticated inventory, both export paths and activation. No driver workaround. */
class NativeMigrationBinaryInventoryTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-binary-inventory")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun source(): Pair<NativeSessionStorage,MobileClient> {
        val source=owned.storage.child(ui.activity,"binary-source");val payload=MigrationActivationFixtures.payload();val records=payload.getJSONObject("records")
        records.keys().forEach{source.write(it,records.getString(it))};return source to MigrationActivationFixtures.client(payload)
    }
    @Test fun actualFourByteBinaryProducerRoundTripsTypedAndCurrentFramedExport()=runBlocking {
        val (source,client)=source();val voice=VoiceRecordingStore(ui.activity,source)
        val pending=voice.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"inventory-draft","synthetic-thread","owned exact source")
        val audio=WavePCM.header(131072)+ByteArray(131072){(it*37%256).toByte()};voice.file(pending.id).writeBytes(audio);val sealed=voice.seal(pending)
        val name="voice-audio:${pending.id}";val binary=File(source.coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin")
        assertArrayEquals(byteArrayOf(0,0,0,4),binary.inputStream().use{input->ByteArray(4).also{java.io.DataInputStream(input).readFully(it)}})
        val before=MigrationActivationFixtures.tree(source)
        val clear=NativeMigrationExport(ui.activity,source).capture()
        try {val archive=NativeMigrationCandidateStore(ui.activity,source).validateArchive(clear);assertArrayEquals(audio,NativeMigrationRecords.bytes(archive.binary.getValue(name)))}finally{clear.fill(0)}
        val envelope=File(owned.folder,"binary-framed.ahe");val password="owned binary inventory password"
        NativeMigrationExport(ui.activity,source).sealFile(envelope,password)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),envelope,password)
        NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt){records->assertEquals(4,records.version);assertEquals(audio.size,records.binary.getValue(name).size)}
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)
        val restored=VoiceRecordingStore(ui.activity,owned.storage.reopen(ui.activity));val recording=restored.restore(sealed.scope,sealed.certificate,sealed.draftKey,sealed.originalText)!!
        assertTrue(recording.migrationReadOnly);assertArrayEquals(audio,restored.bytes(recording));assertEquals(before,MigrationActivationFixtures.tree(source))
    }
    @Test fun authenticatedButUnsupportedBinaryAndPendingOriginalsStillRejectWholeExport() {
        val (source,_)=source();source.writeBinaryStream("unsupported-owned-binary",3){it.write(byteArrayOf(1,2,3))}
        val before=MigrationActivationFixtures.tree(source)
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).capture()}.isFailure)
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).sealFile(File(owned.folder,"unsupported.ahe"),"owned inventory reject password")}.isFailure)
        assertEquals(before,MigrationActivationFixtures.tree(source))
        val binary=File(source.coordinationKey,MigrationActivationFixtures.sha("unsupported-owned-binary".toByteArray())+".bin")
        File(binary.path+".bak").writeBytes(binary.readBytes());val pending=MigrationActivationFixtures.tree(source)
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).capture()}.isFailure)
        assertEquals(pending,MigrationActivationFixtures.tree(source))
    }
}
