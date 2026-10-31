package com.agenthub.android

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Exact seeded post-binary/pre-row state only. No real seal crash, device UI or execution is proved. */
class NativeMigrationUncommittedAudioTest {
    private data class Seed(val fixture: MigrationProvisionalFixture,val pending: PendingVoiceRecording,val source: NativeSessionStorage,val raw: File,val audio: ByteArray) {
        val name get()="voice-audio:${pending.id}"
    }
    private suspend fun seed(): Seed {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source")
        val raw=File(f.context("source").noBackupFilesDir,"voice-recordings-v1/${pending.id}.wav")
        val pcm=ByteArray(131_072){(it*37%251).toByte()};val audio=WavePCM.header(pcm.size)+pcm
        raw.writeBytes(audio);source.writeBinary("voice-audio:${pending.id}",raw)
        check(NativeVoiceRecordingRecord.decode(JSONObject(requireNotNull(source.read("voice-recording:${pending.id}")))).manifest==null)
        return Seed(f,pending,source,raw,audio)
    }
    private fun stamp(file: File)=android.system.Os.lstat(file.path).let {s->
        listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)
    }
    private fun copied(blob: NativeMigrationBlob)=ByteArrayOutputStream().also(blob::verifiedCopy).toByteArray()
    private fun unchanged(seed: Seed,tree: Map<String,String>,rawStat: List<Long>,rawSha: String) {
        assertEquals(tree,MigrationActivationFixtures.tree(seed.source));assertEquals(rawStat,stamp(seed.raw))
        assertEquals(rawSha,NativeMigrationProvisionalVoice.sha(seed.raw.readBytes()))
    }
    @Test fun seededUncommittedAudioRoundTripKeepsBothOriginalsInertAndReexportsWithoutExecution()=runBlocking {
        val seed=seed();val f=seed.fixture;val id=seed.pending.id;val source=seed.source
        val row=requireNotNull(source.read("voice-recording:$id"));val index=requireNotNull(source.read("voice-index-v1"))
        val tree=MigrationActivationFixtures.tree(source);val rawStat=stamp(seed.raw);val digest=NativeMigrationProvisionalVoice.sha(seed.audio)
        val drafts=source.metadata()!!.getJSONObject("drafts").toString();f.export(source)
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        lateinit var catalog: String
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            catalog=records.text.getValue(NativeMigrationProvisionalVoice.RECORD)
            val value=JSONObject(catalog);assertEquals(2,value.getInt("version"));val original=value.getJSONArray("originals").getJSONObject(0)
            assertEquals(row,original.getString("row"));assertEquals(index,original.getString("index"))
            assertEquals(setOf(NativeMigrationProvisionalVoice.PREFIX+id,NativeMigrationProvisionalVoice.AUDIO_PREFIX+id),records.binary.keys)
            assertEquals(seed.audio.size.toLong()*2,records.binary.values.sumOf {it.size.toLong()})
            records.binary.values.forEach {assertArrayEquals(seed.audio,copied(it))}
            assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse("voice-recording:$id" in records.text)
        }
        NativeMigrationActivation(f.context("destination"),destination).activate(receipt)
        val current=f.storage("destination");val original=NativeMigrationProvisionalVoice.read(current).single()
        assertEquals(row,original.row);assertEquals(index,original.index)
        assertEquals(NativeMigrationProvisionalVoice.UncommittedAudio(seed.audio.size,digest),original.uncommittedAudio)
        assertArrayEquals(seed.audio,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copy(current,original,it)}.toByteArray())
        assertArrayEquals(seed.audio,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copyUncommittedAudio(current,original,it)}.toByteArray())
        assertNull(current.read("voice-recording:$id"));assertEquals("[]",current.read("voice-index-v1"))
        assertThrows(Exception::class.java){current.migrationCopyBinaryTo(seed.name,seed.audio.size,ByteArrayOutputStream())}
        assertEquals(drafts,current.metadata()!!.getJSONObject("drafts").toString())
        val store=VoiceRecordingStore(f.context("destination"),current)
        assertNull(store.restore(seed.pending.scope,seed.pending.certificate,seed.pending.draftKey,seed.pending.originalText))
        assertTrue(java.nio.file.Files.notExists(store.file(id).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val metadata=current.metadata()!!;metadata.getJSONObject("drafts").put(seed.pending.draftKey,"later independent draft");current.write("metadata",metadata.toString())
        val targetTree=MigrationActivationFixtures.tree(current);val repeat=File(f.folder,"uncommitted-repeat.migration");f.export(current,repeat)
        val audit=NativeSessionStorage(f.context("destination"),"repeat-audit","p7-provisional-repeat-${f.run}")
        val repeated=NativeMigrationCandidateStore(f.context("destination"),audit).stageFile(UUID.randomUUID().toString(),repeat,f.password)
        NativeMigrationStreamCandidate(f.context("destination"),audit).read(repeated){records->
            assertEquals(catalog,records.text.getValue(NativeMigrationProvisionalVoice.RECORD))
            assertEquals(setOf(NativeMigrationProvisionalVoice.PREFIX+id,NativeMigrationProvisionalVoice.AUDIO_PREFIX+id),records.binary.keys)
            records.binary.values.forEach {assertArrayEquals(seed.audio,copied(it))}
            assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse("voice-recording:$id" in records.text)
        }
        assertEquals(targetTree,MigrationActivationFixtures.tree(current));assertEquals("later independent draft",current.metadata()!!.getJSONObject("drafts").getString(seed.pending.draftKey))
        unchanged(seed,tree,rawStat,digest)
        f.publish("uncommitted-roundtrip",JSONObject().put("version",1).put("run",f.run).put("pid",android.os.Process.myPid())
            .put("stateSeeded",true).put("realSealCrashProved",false).put("row",row).put("index",index).put("size",seed.audio.size).put("sha256",digest)
            .put("sourceCiphertextsUnchanged",true).put("rawStatUnchanged",true).put("bothInertCopiesCompared",true).put("activeVoiceRestored",false)
            .put("runtimeOrUiAccepted",false).put("NativeAcceptance",false))
    }
    @Test fun seededV2SchemaFramesAndActiveCollisionsRemainClosed()=runBlocking {
        val seed=seed();val f=seed.fixture;val tree=MigrationActivationFixtures.tree(seed.source);val rawStat=stamp(seed.raw);val digest=NativeMigrationProvisionalVoice.sha(seed.audio)
        f.export(seed.source);val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            fun changed(edit: (JSONObject)->Unit): NativeMigrationStreamRecords {
                val text=NativeMigrationTextMap.copy(records.text);val value=JSONObject(text.getValue(NativeMigrationProvisionalVoice.RECORD));edit(value)
                text[NativeMigrationProvisionalVoice.RECORD]=value.toString();return records.copy(text=text)
            }
            fun rejected(value: NativeMigrationStreamRecords){assertThrows(Exception::class.java){candidates.validateStreamRecords(value)}}
            rejected(changed {it.put("version",1)})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).remove("uncommittedAudio")})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).put("uncommittedAudio",JSONObject.NULL)})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).getJSONObject("uncommittedAudio").put("size",seed.audio.size+1)})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).getJSONObject("uncommittedAudio").put("sha256","0".repeat(64))})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).getJSONObject("uncommittedAudio").put("retryAllowed",true)})
            rejected(changed {val rows=it.getJSONArray("originals");rows.put(JSONObject(rows.getJSONObject(0).toString()))})
            rejected(changed {it.getJSONArray("originals").getJSONObject(0).apply {put("kind","absent");put("size",0);put("sha256",NativeMigrationProvisionalVoice.sha(byteArrayOf()))}})
            val raw=NativeMigrationProvisionalVoice.PREFIX+seed.pending.id;val audio=NativeMigrationProvisionalVoice.AUDIO_PREFIX+seed.pending.id
            rejected(records.copy(binary=records.binary-audio));rejected(records.copy(binary=records.binary-raw))
            rejected(records.copy(binary=records.binary+(("unsupported-original:"+UUID.randomUUID()) to records.binary.getValue(audio))))
            rejected(records.copy(binary=records.binary+(seed.name to records.binary.getValue(audio))))
            rejected(records.copy(binary=(records.binary-audio)+((NativeMigrationProvisionalVoice.AUDIO_PREFIX+UUID.randomUUID()) to records.binary.getValue(audio))))
            val collision=NativeMigrationTextMap.copy(records.text)
            collision["voice-index-v1"]=JSONArray().put(seed.pending.id).toString();collision["voice-recording:${seed.pending.id}"]=requireNotNull(seed.source.read("voice-recording:${seed.pending.id}"))
            rejected(records.copy(text=collision))
        }
        unchanged(seed,tree,rawStat,digest)
        f.publish("uncommitted-closed-schema",JSONObject().put("version",1).put("run",f.run).put("pid",android.os.Process.myPid()).put("stateSeeded",true).put("realSealCrashProved",false).put("closedGraphRejections",14).put("NativeAcceptance",false))
    }
    @Test fun mismatchedSeededRawAndWrittenAudioRejectWithoutPublishingOrMutatingEither()=runBlocking {
        val seed=seed();val f=seed.fixture;val changed=seed.audio.copyOf();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();seed.raw.writeBytes(changed)
        val tree=MigrationActivationFixtures.tree(seed.source);val rawStat=stamp(seed.raw);val rawSha=NativeMigrationProvisionalVoice.sha(changed)
        val output=File(f.folder,"uncommitted-mismatch.migration");val sentinel="existing output".toByteArray();output.writeBytes(sentinel)
        assertThrows(Exception::class.java){f.export(seed.source,output)}
        assertArrayEquals(sentinel,output.readBytes());unchanged(seed,tree,rawStat,rawSha)
        f.publish("uncommitted-mismatch",JSONObject().put("version",1).put("run",f.run).put("pid",android.os.Process.myPid()).put("stateSeeded",true).put("realSealCrashProved",false).put("outputUnchanged",true).put("NativeAcceptance",false))
    }
    @Test fun authenticatedLegacyV2AudioWithoutCommittedRowAliasStillRejectsWithoutGuessing()=runBlocking {
        val seed=seed();val f=seed.fixture;val bytes=seed.audio;val name=seed.name
        val key=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("p7-provisional-source-${f.run}",null) as javax.crypto.SecretKey
        val legacy=ByteArrayOutputStream();java.io.DataOutputStream(legacy).use {out->
            out.writeInt(2);out.writeInt(bytes.size);var offset=0;var index=0
            while(offset<bytes.size) {
                val count=minOf(65_536,bytes.size-offset);val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key);cipher.updateAAD(name.toByteArray(Charsets.UTF_8)+byteArrayOf(0)+java.nio.ByteBuffer.allocate(8).putInt(bytes.size).putInt(index++).array())
                out.write(cipher.iv);var used=0
                while(used<count) {val n=minOf(16_384,count-used);cipher.update(bytes,offset+used,n)?.let {out.write(it)};used+=n}
                out.write(cipher.doFinal());offset+=count
            }
        }
        // Exact fresh fixture only; this is a valid legacy original, not a corrupt-header refusal.
        val file=File(seed.source.coordinationKey,NativeMigrationProvisionalVoice.sha(name.toByteArray(Charsets.UTF_8))+".bin");file.writeBytes(legacy.toByteArray());android.system.Os.chmod(file.path,0x180)
        val verified=ByteArrayOutputStream();seed.source.copyBinaryTo(name,bytes.size,verified);assertArrayEquals(bytes,verified.toByteArray())
        val tree=MigrationActivationFixtures.tree(seed.source);val rawStat=stamp(seed.raw);val digest=NativeMigrationProvisionalVoice.sha(bytes)
        val output=File(f.folder,"uncommitted-legacy.migration");val sentinel="existing output".toByteArray();output.writeBytes(sentinel)
        assertThrows(Exception::class.java){f.export(seed.source,output)}
        assertArrayEquals(sentinel,output.readBytes());unchanged(seed,tree,rawStat,digest)
        f.publish("uncommitted-legacy-refusal",JSONObject().put("version",1).put("run",f.run).put("pid",android.os.Process.myPid()).put("stateSeeded",true).put("realSealCrashProved",false)
            .put("legacyAudioIndependentlyAuthenticated",true).put("outputUnchanged",true).put("NativeAcceptance",false))
    }
}
