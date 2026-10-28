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

/** Opt-in real writer/reader process boundary; synthetic original audio, no Host/microphone. */
class NativeVoiceBinaryColdTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"voice-binary-cold")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun audio()=WavePCM.header(192000)+ByteArray(192000){n->(n*37%256).toByte()}
    @Test fun saveOriginalSealedVoiceForSeparateProcessRead()=runBlocking{
        val client=MigrationActivationFixtures.client(MigrationActivationFixtures.payload());val store=VoiceRecordingStore(ui.activity,owned.storage)
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),"synthetic-certificate","synthetic-draft","synthetic-voice-draft",null,"original cold 中文 😀 100% %@")
        store.file(pending.id).writeBytes(audio());val sealed=store.seal(pending);store.mark(sealed,true,true)
        val restored=store.current(sealed);assertTrue(restored.prepareAttempted&&restored.audioAttempted);sealed.manifest!!.verify(store.bytes(restored))
        owned.verifyDefault();val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"voice-binary-writer.json").writeText(JSONObject().put("version",1).put("kind","voice-binary-cold").put("namespace",namespace).put("writerPid",Process.myPid())
            .put("record",restored.json()).put("audioSha256",voiceSHA(audio())).put("originalCiphertext",JSONObject(MigrationActivationFixtures.tree(owned.storage))).toString())
    }
    @Test fun readOriginalVoiceAndAttemptFenceInFreshProcessWithoutChangingCiphertext()=runBlocking{
        val name=InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original voice fixture required");require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.getInt("version")==1&&fixture.getString("kind")=="voice-binary-cold")
        assertNotEquals(fixture.getInt("writerPid"),Process.myPid());val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val storage=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val before=MigrationActivationFixtures.tree(storage);assertEquals(fixture.getJSONObject("originalCiphertext").toString(),JSONObject(before).toString())
        val original=fixture.getJSONObject("record");val scope=AISharingScope.decode(original.getJSONObject("scope"));val store=VoiceRecordingStore(ui.activity,storage)
        val restored=store.restore(scope,original.optional("certificate"),original.getString("draftKey"),original.getString("originalText"))!!
        assertEquals(original.toString(),restored.json().toString());assertEquals(original.getString("id"),restored.manifest!!.operationId)
        assertTrue(restored.prepareAttempted&&restored.audioAttempted);assertFalse(store.file(restored.id).exists());assertArrayEquals(audio(),store.bytes(restored));assertEquals(fixture.getString("audioSha256"),voiceSHA(store.bytes(restored)))
        assertNull(store.restore(scope,"other-certificate",restored.draftKey,restored.originalText));assertNull(store.restore(scope,restored.certificate,restored.draftKey,"new draft"))
        assertEquals(before,MigrationActivationFixtures.tree(storage))
        File(owned.folder,"voice-binary-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",Process.myPid()).put("differentProcess",true).put("sameDeviceKeystore",true)
            .put("exactOriginalAudio",true).put("sameManifestAndOperationId",true).put("attemptFencesPreserved",true).put("wrongCertificateAndDraftRejected",true).put("allOriginalCiphertextUnchanged",true).put("remoteWrites",0).toString())
    }
}
