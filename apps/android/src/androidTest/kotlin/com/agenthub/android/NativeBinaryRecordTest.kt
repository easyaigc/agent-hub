package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.DataOutputStream
import java.security.KeyStore
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Real Keystore/AtomicFile, synthetic binary originals; no Host or microphone operations. */
class NativeBinaryRecordTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val name="voice-audio:"+UUID.randomUUID()
    private val bytes=ByteArray(131073){n->(n*37%256).toByte()}
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-binary")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun record(key: String=name)=File(owned.storage.coordinationKey,MigrationActivationFixtures.sha(key.toByteArray())+".bin")
    private fun source(data: ByteArray=bytes)=File(owned.folder,"source-${UUID.randomUUID()}.bin").apply{writeBytes(data)}
    private fun save(){owned.storage.writeBinary(name,source())}
    private fun rejected(block: ()->Unit){assertTrue("Corrupt original must reject",runCatching(block).isFailure)}
    private fun read()=owned.storage.reopen(ui.activity).readBinary(name,bytes.size)
    private fun legacy(data: ByteArray=bytes): ByteArray {
        val secret=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("agent-hub-mobile-v1",null) as SecretKey
        val buffer=java.io.ByteArrayOutputStream();DataOutputStream(buffer).use{out->
            out.writeInt(2);out.writeInt(data.size);var position=0;var index=0
            while(position<data.size){val count=minOf(65536,data.size-position);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret)
                cipher.updateAAD(name.toByteArray()+byteArrayOf(0)+ByteBuffer.allocate(8).putInt(data.size).putInt(index++).array())
                out.write(cipher.iv);var offset=0;while(offset<count){val n=minOf(16384,count-offset);cipher.update(data,position+offset,n)?.let{out.write(it)};offset+=n};out.write(cipher.doFinal());position+=count}
        };return buffer.toByteArray()
    }
    @Test fun authenticatedNameAndExactBinaryOriginalSurviveFreshHandleWithoutPlaintextIndex(){
        save();val encrypted=record().readBytes();assertEquals(4,ByteBuffer.wrap(encrypted).int);assertFalse(String(encrypted,Charsets.ISO_8859_1).contains(name))
        assertArrayEquals(bytes,read());assertArrayEquals(encrypted,record().readBytes())
        val other="voice-audio:"+UUID.randomUUID();record(other).writeBytes(encrypted)
        rejected{owned.storage.readBinary(other,bytes.size)};rejected{owned.storage.readBinary(name,bytes.size-1)}
        assertArrayEquals(encrypted,record().readBytes());rejected{owned.storage.read(name)}
        // Binary export remains explicitly unsupported until the typed archive/candidate is implemented.
        rejected{owned.storage.migrationNames()}
    }
    @Test fun oldChunkedFormatRemainsExactAndOnlyAnExplicitWriteReplacesIt(){
        save();val old=legacy();record().writeBytes(old);assertArrayEquals(bytes,read());assertArrayEquals(old,record().readBytes())
        val changed=bytes.reversedArray();owned.storage.writeBinary(name,source(changed));assertEquals(4,ByteBuffer.wrap(record().readBytes()).int)
        assertArrayEquals(changed,owned.storage.readBinary(name,changed.size));assertArrayEquals(changed,owned.storage.reopen(ui.activity).readBinary(name,changed.size))
    }
    @Test fun headerTamperingWrongLengthAndCrossVersionDowngradesNeverReturnPlaintext(){
        save();val original=record().readBytes();val headerLength=ByteBuffer.wrap(original,4,4).int
        for(change in listOf<(ByteArray)->Unit>(
            {it[20+headerLength-1]=(it[20+headerLength-1].toInt() xor 1).toByte()},
            {ByteBuffer.wrap(it,4,4).putInt(0)},
            {ByteBuffer.wrap(it,4,4).putInt(65561)},
            {ByteBuffer.wrap(it).putInt(2)})){
            val damaged=original.copyOf();change(damaged);record().writeBytes(damaged);rejected{read()};assertArrayEquals(damaged,record().readBytes())
        }
        val old=legacy();ByteBuffer.wrap(old).putInt(4);record().writeBytes(old);rejected{read()};assertArrayEquals(old,record().readBytes())
    }
    @Test fun missingRepeatedReorderedAndExtraChunksRejectTheWholeOriginal(){
        save();val original=record().readBytes();val start=20+ByteBuffer.wrap(original,4,4).int;val stride=65536+28
        val swapped=original.copyOf();System.arraycopy(original,start+stride,swapped,start,stride);System.arraycopy(original,start,swapped,start+stride,stride)
        val repeated=original.copyOf();System.arraycopy(original,start,repeated,start+stride,stride)
        val corrupt=original.copyOf();corrupt[start+12]=(corrupt[start+12].toInt() xor 1).toByte()
        save();val crossWrite=record().readBytes();System.arraycopy(original,start,crossWrite,start,stride)
        for(damaged in listOf(original.copyOf(original.size-1),original+byteArrayOf(0),swapped,repeated,corrupt,crossWrite)){
            record().writeBytes(damaged);rejected{read()};assertArrayEquals(damaged,record().readBytes())
        }
        record().writeBytes(original);assertArrayEquals(bytes,read())
    }
    @Test fun rejectedWriteKeepsOriginalCiphertextAndOriginalSource(){
        save();val original=record().readBytes();val input=source();val originalInput=input.readBytes()
        rejected{owned.storage.writeBinary(name,input,bytes.size-1)}
        rejected{owned.storage.writeBinary(name+"\ud800",input)}
        rejected{owned.storage.writeBinary(name,source(byteArrayOf()))}
        assertArrayEquals(originalInput,input.readBytes());assertArrayEquals(original,record().readBytes());assertArrayEquals(bytes,read())
    }
    @Test fun sealedVoiceUsesNewBinaryAndRetainsOriginalManifestAndAttemptFence()=runBlocking{
        val store=VoiceRecordingStore(ui.activity,owned.storage);val client=MigrationActivationFixtures.client(MigrationActivationFixtures.payload())
        val pending=store.begin(AISharingScope(client.scope!!,"codex-dictation"),"fixture-certificate","fixture-draft","voice-draft",null,"original 中文 😀")
        val wav=WavePCM.header(32000)+ByteArray(32000){(it%251).toByte()};store.file(pending.id).writeBytes(wav)
        val sealed=store.seal(pending);store.mark(sealed,true,true)
        assertEquals(4,ByteBuffer.wrap(record("voice-audio:${pending.id}").readBytes()).int)
        val fresh=VoiceRecordingStore(ui.activity,owned.storage.reopen(ui.activity));val restored=fresh.restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText)!!
        assertEquals(sealed.manifest,restored.manifest);assertEquals(pending.id,restored.id);assertTrue(restored.prepareAttempted&&restored.audioAttempted)
        assertArrayEquals(wav,fresh.bytes(restored));assertFalse(fresh.file(pending.id).exists());fresh.remove(restored)
    }
}
