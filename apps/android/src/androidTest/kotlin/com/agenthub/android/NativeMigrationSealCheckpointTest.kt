package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Synthetic PCM + real seal + named injected interruption. No real row I/O failure/crash/DEV claim. */
class NativeMigrationSealCheckpointTest {
    private fun stamp(file: File)=android.system.Os.lstat(file.path).let {s->
        listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)
    }
    private fun sha(bytes: ByteArray)=NativeMigrationProvisionalVoice.sha(bytes)
    private fun base(storage: NativeSessionStorage,name: String)=File(storage.coordinationKey,sha(name.toByteArray(Charsets.UTF_8))+".bin")
    private fun copied(blob: NativeMigrationBlob)=ByteArrayOutputStream().also(blob::verifiedCopy).toByteArray()
    private fun settledFlatSource(storage: NativeSessionStorage) {
        val root=File(storage.coordinationKey)
        checkNotNull(root.listFiles()).forEach {file->
            assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.bin")))
            val stat=android.system.Os.lstat(file.path)
            assertTrue(android.system.OsConstants.S_ISREG(stat.st_mode));assertEquals(android.os.Process.myUid(),stat.st_uid)
            assertEquals(1L,stat.st_nlink);assertEquals(File(root.canonicalFile,file.name),file.canonicalFile)
        }
    }
    @Test fun realSealNamedAudioCommitInterruptionExportsBothOriginalsInertWithoutReplay()=runBlocking(Dispatchers.IO) {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source");val id=pending.id
        val ctx=f.context("source");val raw=File(ctx.noBackupFilesDir,"voice-recordings-v1/$id.wav")
        val pcm=ByteArray(131_072){(it*37%251).toByte()};val completed=WavePCM.header(pcm.size)+pcm
        // Only the test's synthetic input is seeded. The real seal must normalize its header and odd tail.
        val unfinished=ByteArray(44)+pcm+byteArrayOf(0x7f);raw.writeBytes(unfinished)
        val rowName="voice-recording:$id";val audioName="voice-audio:$id"
        val row=requireNotNull(source.migrationReadTextRecords(setOf(rowName))[rowName])
        val index=requireNotNull(source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
        val rowFile=base(source,rowName);val indexFile=base(source,"voice-index-v1");val audioFile=base(source,audioName)
        val rowStat=stamp(rowFile);val indexStat=stamp(indexFile);val before=MigrationActivationFixtures.tree(source)
        assertTrue(java.nio.file.Files.notExists(audioFile.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));settledFlatSource(source)
        val drafts=source.metadata()!!.getJSONObject("drafts").toString()
        var checkpointCalls=0;var injected: IOException?=null
        val store=VoiceRecordingStore(ctx,source,NativeVoiceSealAudioCommittedCheckpoint { reached->
            assertEquals(id,reached);assertEquals(1,++checkpointCalls)
            assertArrayEquals(completed,raw.readBytes())
            assertEquals(row,source.migrationReadTextRecords(setOf(rowName))[rowName])
            assertEquals(index,source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
            assertEquals(rowStat,stamp(rowFile));assertEquals(indexStat,stamp(indexFile))
            val original=NativeVoiceRecordingRecord.decode(JSONObject(row))
            assertNull(original.manifest);assertFalse(original.prepareAttempted);assertFalse(original.audioAttempted)
            val probe=NativeMigrationProbe();source.migrationCopyBinaryTo(audioName,completed.size,probe)
            assertEquals(completed.size.toLong(),probe.size);assertEquals(sha(completed),probe.sha256())
            // The checkpoint throws this exact known test marker; AtomicFile row writing is never claimed.
            val marker=IOException("P7 named checkpoint: audio committed; row not submitted")
            injected=marker;throw marker
        })
        val failure=try {store.seal(pending);throw AssertionError("Expected known named checkpoint interruption")}
            catch(error: IOException){error}
        assertNotNull(injected);assertSame(injected,failure);assertEquals(1,checkpointCalls)
        assertTrue(failure.stackTrace.any {it.className.startsWith("com.agenthub.android.VoiceRecordingStore")})
        assertArrayEquals(completed,raw.readBytes());assertFalse(unfinished.contentEquals(raw.readBytes()))
        assertEquals(row,source.migrationReadTextRecords(setOf(rowName))[rowName])
        assertEquals(index,source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
        assertEquals(rowStat,stamp(rowFile));assertEquals(indexStat,stamp(indexFile));settledFlatSource(source)
        val post=MigrationActivationFixtures.tree(source)
        assertEquals(before+(audioFile.name to sha(audioFile.readBytes())),post)
        val afterRow=JSONObject(requireNotNull(source.read(rowName)))
        assertNull(NativeVoiceRecordingRecord.decode(afterRow).manifest);assertFalse(afterRow.has("audioStorage"));assertFalse(afterRow.has("audio"))
        val audio=NativeMigrationProbe();source.migrationCopyBinaryTo(audioName,completed.size,audio)
        assertEquals(completed.size.toLong(),audio.size);assertEquals(sha(completed),audio.sha256())
        // Baselines are intentionally post-seal: its real WAV finish is part of the interrupted operation.
        val rawStat=stamp(raw);val audioStat=stamp(audioFile);val rawSha=sha(raw.readBytes())
        fun sourceUnchanged() {
            assertEquals(post,MigrationActivationFixtures.tree(source));assertEquals(rawStat,stamp(raw));assertEquals(audioStat,stamp(audioFile))
            assertEquals(rowStat,stamp(rowFile));assertEquals(indexStat,stamp(indexFile));assertEquals(rawSha,sha(raw.readBytes()))
            assertEquals(row,source.migrationReadTextRecords(setOf(rowName))[rowName]);assertEquals(index,source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
            settledFlatSource(source)
        }
        val freezeOperation=UUID.randomUUID().toString()
        NativeVoiceRawOwnership.freeze(source,freezeOperation).use {frozen->
            NativeVoiceRawOwnership.requireFrozen(source,freezeOperation,frozen);sourceUnchanged()
        }
        // No filesystem blocker exists, and no business original is removed/repaired before export.
        f.export(source);sourceUnchanged()
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        val expectedNames=setOf(NativeMigrationProvisionalVoice.PREFIX+id,NativeMigrationProvisionalVoice.AUDIO_PREFIX+id)
        lateinit var catalog: String
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            catalog=records.text.getValue(NativeMigrationProvisionalVoice.RECORD)
            val value=JSONObject(catalog);assertEquals(2,value.getInt("version"));assertEquals(1,value.getJSONArray("originals").length())
            val original=value.getJSONArray("originals").getJSONObject(0);assertEquals(row,original.getString("row"));assertEquals(index,original.getString("index"))
            assertEquals(expectedNames,records.binary.keys);records.binary.values.forEach {assertArrayEquals(completed,copied(it))}
            assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse(rowName in records.text)
        }
        val generation=NativeMigrationActivation(f.context("destination"),destination).activate(receipt)
        val current=f.storage("destination");val original=NativeMigrationProvisionalVoice.read(current).single()
        assertEquals(id,original.id);assertEquals(row,original.row);assertEquals(index,original.index)
        assertEquals(NativeMigrationProvisionalVoice.UncommittedAudio(completed.size,rawSha),original.uncommittedAudio)
        assertArrayEquals(completed,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copy(current,original,it)}.toByteArray())
        assertArrayEquals(completed,ByteArrayOutputStream().also {NativeMigrationProvisionalVoice.copyUncommittedAudio(current,original,it)}.toByteArray())
        assertNull(current.read(rowName));assertEquals("[]",current.read("voice-index-v1"))
        assertThrows(FileNotFoundException::class.java){current.copyBinaryTo(audioName,completed.size,ByteArrayOutputStream())}
        assertEquals(drafts,current.metadata()!!.getJSONObject("drafts").toString())
        val inactive=VoiceRecordingStore(f.context("destination"),current)
        assertNull(inactive.restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText))
        assertTrue(java.nio.file.Files.notExists(inactive.file(id).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val metadata=current.metadata()!!;metadata.getJSONObject("drafts").put(pending.draftKey,"later independent draft after named checkpoint import");current.write("metadata",metadata.toString())
        val importedTree=MigrationActivationFixtures.tree(current);val repeat=File(f.folder,"named-checkpoint-repeat.migration");f.export(current,repeat)
        val audit=NativeSessionStorage(f.context("destination"),"repeat-audit","p7-provisional-repeat-${f.run}")
        val repeated=NativeMigrationCandidateStore(f.context("destination"),audit).stageFile(UUID.randomUUID().toString(),repeat,f.password)
        NativeMigrationStreamCandidate(f.context("destination"),audit).read(repeated){records->
            assertEquals(catalog,records.text.getValue(NativeMigrationProvisionalVoice.RECORD));assertEquals(expectedNames,records.binary.keys)
            records.binary.values.forEach {assertArrayEquals(completed,copied(it))}
            assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse(rowName in records.text)
        }
        assertEquals(importedTree,MigrationActivationFixtures.tree(current));assertEquals("later independent draft after named checkpoint import",current.metadata()!!.getJSONObject("drafts").getString(pending.draftKey))
        sourceUnchanged()
        f.publish("named-seal-checkpoint",f.header(receipt).put("version",1).put("id",id).put("generation",generation).put("originalRow",row).put("originalIndex",index)
            .put("sourcePostFailureTree",JSONObject(post)).put("postFailureRawSha256",rawSha).put("size",completed.size).put("sourcePostFailureRawStat",org.json.JSONArray(rawStat))
            .put("sourcePostFailureAudioStat",org.json.JSONArray(audioStat)).put("freezeOperation",freezeOperation).put("checkpointCalls",checkpointCalls)
            .put("exceptionClass",failure.javaClass.name).put("exceptionMessage",failure.message).put("exceptionStack",failure.stackTraceToString())
            .put("syntheticPCM",true).put("realSealFunction",true).put("namedCheckpoint",true).put("knownInjectedInterruption",true)
            .put("realRowIoFailure",false).put("realPowerLoss",false).put("realProcessDeath",false).put("rowWriteSubmitted",false)
            .put("rawOwnerFreezeReacquired",true).put("sourceUnchangedAfterFailure",true).put("bothInertCopiesCompared",true).put("activeVoiceRestored",false)
            .put("runtimeOrUiAccepted",false).put("physicalDevAccepted",false).put("NativeAcceptance",false))
    }
}
