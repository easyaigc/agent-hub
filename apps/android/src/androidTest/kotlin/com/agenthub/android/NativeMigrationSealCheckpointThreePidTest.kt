package com.agenthub.android

import android.os.Process
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Three named invocations only. Distinct PIDs never imply process exit, crash, power loss or DEV/UI. */
class NativeMigrationSealCheckpointThreePidTest {
    private companion object {
        const val KIND="named-seal-three-pid-v1"
        const val WRITER="source78-writer"
        const val INTENT="source78-activation-intent"
        const val ACTIVATION="source78-activation"
        const val AUDIT="source78-audit"
    }
    private fun sha(bytes: ByteArray)=NativeMigrationProvisionalVoice.sha(bytes)
    private fun alias(f: MigrationProvisionalFixture,role: String)="p7-provisional-$role-${f.run}"
    private fun view(f: MigrationProvisionalFixture,role: String)=NativeSessionStorage.existingMigration(f.context(role),File(f.folder,role),"installation",alias(f,role))
    private fun evidenceFile(f: MigrationProvisionalFixture,name: String)=File(f.folder,"$name.json")
    private fun proofHash(f: MigrationProvisionalFixture,name: String)=sha(evidenceFile(f,name).readBytes())
    private fun stamps(file: File)=Os.lstat(file.path).let {s->
        listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)
    }
    private fun stampJSON(file: File)=JSONArray(stamps(file))
    private fun requireStamp(expected: JSONArray,file: File) {
        assertEquals(stamps(file),(0 until expected.length()).map {expected.getLong(it)})
    }
    private fun base(storage: NativeSessionStorage,name: String)=File(storage.coordinationKey,sha(name.toByteArray())+".bin")
    private fun map(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    private fun strings(value: JSONArray)=(0 until value.length()).map {value.getString(it)}
    private fun pcm()=ByteArray(131_072){(it*43%251).toByte()}
    private fun audio()=pcm().let {WavePCM.header(it.size)+it}
    private fun raw(f: MigrationProvisionalFixture,id: String)=File(f.context("source").noBackupFilesDir,"voice-recordings-v1/$id.wav")
    private fun process(): JSONObject {
        // Own process only. This identity is evidence, never an instruction to stop an old process.
        val text=File("/proc/self/stat").readText();val end=text.lastIndexOf(") ");require(end>0)
        require(text.substringBefore(' ').toInt()==Process.myPid())
        val fields=text.substring(end+2).trim().split(Regex("\\s+"));require(fields.size>19)
        return JSONObject().put("pid",Process.myPid()).put("startTicks",fields[19].toLong().also {require(it>0)})
    }
    private fun header(f: MigrationProvisionalFixture,phase: String,operation: String)=JSONObject()
        .put("version",1).put("kind",KIND).put("run",f.run).put("phase",phase).put("operationId",operation).put("process",process())
        .put("syntheticPCM",true).put("realSealFunction",true).put("namedCheckpoint",true).put("knownInjectedInterruption",true)
        .put("realRowIoFailure",false).put("realPowerLoss",false).put("realProcessDeath",false).put("oldProcessExitProved",false)
        .put("runtimeOrUiAccepted",false).put("physicalDevAccepted",false).put("NativeAcceptance",false)
    private fun checked(f: MigrationProvisionalFixture,name: String,phase: String): JSONObject {
        val v=f.proof(name);require(v.get("version")==1&&v.getString("kind")==KIND&&v.getString("run")==f.run&&v.getString("phase")==phase)
        require(UUID.fromString(v.getString("operationId")).toString()==v.getString("operationId"))
        for(key in listOf("syntheticPCM","realSealFunction","namedCheckpoint","knownInjectedInterruption"))require(v.get(key)==true)
        for(key in listOf("realRowIoFailure","realPowerLoss","realProcessDeath","oldProcessExitProved","runtimeOrUiAccepted","physicalDevAccepted","NativeAcceptance"))require(v.get(key)==false)
        val p=v.getJSONObject("process");require(p.keys().asSequence().toSet()==setOf("pid","startTicks"));require(p.getInt("pid")>0&&p.getLong("startTicks")>0)
        return v
    }
    private fun writerProof(f: MigrationProvisionalFixture)=checked(f,WRITER,"writer").also {v->
        require(v.get("stageCalls")==1&&v.get("checkpointCalls")==1&&v.get("activationAttempted")==false&&v.get("rowWriteSubmitted")==false)
        require(v.get("candidateOriginGeneration")==JSONObject.NULL&&v.get("audioFormat")==4&&v.get("rawOwnerFreezeReacquired")==true)
        require(v.getString("exceptionClass")==IOException::class.java.name&&v.getString("exceptionMessage")=="P7 three-PID writer named checkpoint: audio committed; row not submitted")
        require(v.getString("exceptionStack").contains("com.agenthub.android.VoiceRecordingStore"))
    }
    private fun receipt(v: JSONObject): NativeMigrationCandidateStore.Receipt {
        val r=v.getJSONObject("receipt");require(r.keys().asSequence().toSet()==setOf("id","sha256","recordCount"))
        val id=r.getString("id");require(UUID.fromString(id).toString()==id)
        val digest=r.getString("sha256");require(digest.matches(Regex("[a-f0-9]{64}")))
        val count=r.getInt("recordCount");require(count in 1..1024)
        return NativeMigrationCandidateStore.Receipt(id,digest,count)
    }
    private fun receiptJSON(r: NativeMigrationCandidateStore.Receipt)=JSONObject().put("id",r.id).put("sha256",r.sha256).put("recordCount",r.recordCount)
    private fun operations(records: NativeMigrationStreamRecords): Map<String,String> {
        val immutable=listOf("id","kind","target","intent","intentSha256","phase","result","error","detached","submissionAttempted","sourceKey","sourceVersion","parentOperationId","forkContext","sharingScope")
        return records.text.filterKeys {it.startsWith("operations:")}.flatMap {(name,raw)->
            val rows=StrictMigrationJson.parse(raw.toByteArray()).getJSONArray("operations")
            (0 until rows.length()).map {i->val row=rows.getJSONObject(i);val selected=JSONObject()
                immutable.forEach {key->if(row.has(key))selected.put(key,row.get(key))}
                "$name:${row.getString("id")}" to sha(selected.toString().toByteArray())
            }
        }.toMap().also {require(it.isNotEmpty())}
    }
    private fun protectedText(records: NativeMigrationStreamRecords)=records.text.filterKeys {it.startsWith("submission:")||it.startsWith("draft-file:")}
    private fun blobs(records: NativeMigrationStreamRecords,id: String) {
        val expected=setOf(NativeMigrationProvisionalVoice.PREFIX+id,NativeMigrationProvisionalVoice.AUDIO_PREFIX+id)
        assertEquals(expected,records.binary.keys)
        records.binary.values.forEach {blob->assertArrayEquals(audio(),ByteArrayOutputStream().also(blob::verifiedCopy).toByteArray())}
    }
    private fun candidate(records: NativeMigrationStreamRecords,v: JSONObject) {
        val id=v.getString("id");assertEquals(v.getString("catalog"),records.text.getValue(NativeMigrationProvisionalVoice.RECORD))
        assertEquals(map(v.getJSONObject("protectedOperations")),operations(records));assertEquals(map(v.getJSONObject("protectedText")),protectedText(records))
        assertEquals(v.getString("sourceDrafts"),JSONObject(records.text.getValue("metadata")).getJSONObject("drafts").toString())
        assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse("voice-recording:$id" in records.text);blobs(records,id)
    }
    private fun sourceUnchanged(f: MigrationProvisionalFixture,v: JSONObject) {
        val id=v.getString("id");require(UUID.fromString(id).toString()==id)
        assertEquals(audio().size,v.getInt("size"));assertEquals(sha(audio()),v.getString("audioSha256"))
        view(f,"source").using {source->
            assertEquals(map(v.getJSONObject("sourceCiphertext")),source.familyCiphertext())
            assertEquals(strings(v.getJSONArray("sourceKeys")).toSet(),source.boundKeys().keys)
            assertEquals(v.getString("row"),source.records.text.getValue("voice-recording:$id"));assertEquals(v.getString("index"),source.records.text.getValue("voice-index-v1"))
            val original=NativeVoiceRecordingRecord.decode(JSONObject(v.getString("row")));assertNull(original.manifest)
            assertFalse(original.prepareAttempted);assertFalse(original.audioAttempted)
            val audioBlob=source.records.binary.getValue("voice-audio:$id")
            assertArrayEquals(audio(),ByteArrayOutputStream().also(audioBlob::verifiedCopy).toByteArray())
            val folder=File(f.context("source").noBackupFilesDir,"installation")
            requireStamp(v.getJSONArray("rowStat"),File(folder,sha("voice-recording:$id".toByteArray())+".bin"))
            requireStamp(v.getJSONArray("indexStat"),File(folder,sha("voice-index-v1".toByteArray())+".bin"))
            requireStamp(v.getJSONArray("audioStat"),File(folder,sha("voice-audio:$id".toByteArray())+".bin"))
            requireStamp(v.getJSONArray("rawStat"),raw(f,id));assertArrayEquals(audio(),raw(f,id).readBytes());source.unchanged()
        }
    }
    private fun imported(f: MigrationProvisionalFixture,destination: NativeMigrationExistingView,v: JSONObject,expectedDrafts: String) {
        val records=destination.records;NativeMigrationArchiveValidation.stream(records)
        val id=v.getString("id");assertEquals(v.getString("catalog"),records.text.getValue(NativeMigrationProvisionalVoice.RECORD));blobs(records,id)
        assertEquals("[]",records.text.getValue("voice-index-v1"));assertFalse("voice-recording:$id" in records.text);assertFalse("voice-audio:$id" in records.binary)
        assertTrue(Files.notExists(File(f.context("destination").noBackupFilesDir,"voice-recordings-v1/$id.wav").toPath(),NOFOLLOW_LINKS))
        assertEquals(map(v.getJSONObject("protectedOperations")),operations(records));assertEquals(map(v.getJSONObject("protectedText")),protectedText(records))
        val clientRows=JSONObject(records.text.getValue("metadata")).getJSONArray("clients");assertEquals(1,clientRows.length())
        val client=decodeClient(clientRows.getJSONObject(0))
        records.text.filterKeys {it.startsWith("operations:")}.forEach {(name,raw)->
            val body=StrictMigrationJson.parse(raw.toByteArray());val rows=body.getJSONArray("operations")
            // Reuse the production storage-free decoder; this fixture always seeds reply recovery.
            val saved=NativeOperationJournal.validateExistingMigrationRecords(name.removePrefix("operations:"),body)
            saved.forEachIndexed {i,record->
                assertTrue(record.migrationReadOnly)
                val wire=rows.getJSONObject(i).getJSONObject("replyRecovery")
                assertEquals("uncertain",wire.get("phase"));assertEquals(JSONObject.NULL,wire.get("inspection"));assertEquals(false,wire.get("consumed"))
                val recovery=requireNotNull(record.replyRecovery)
                assertEquals("uncertain",recovery.phase);assertNull(recovery.inspection);assertFalse(recovery.consumed)
                assertFalse(recovery.matches(client,record));assertFalse(recovery.mayRetry(client,record))
            }
        }
        assertEquals(expectedDrafts,JSONObject(records.text.getValue("metadata")).getJSONObject("drafts").toString())
        destination.originalCandidate(null,receipt(v)){candidate(it,v)}
        assertEquals(map(v.getJSONObject("candidateCiphertext")),destination.originalCandidateCiphertext(null));destination.unchanged()
    }
    @Test fun writerRealSealKnownCheckpointExportsAndStagesOriginalCandidateOnly()=runBlocking(Dispatchers.IO) {
        val f=MigrationProvisionalFixture();f.fresh();val operation=UUID.randomUUID().toString();val pending=f.seed();val source=f.storage("source");val id=pending.id
        var destinationToRetire: NativeSessionStorage?=null
        try {
            val raw=raw(f,id);raw.writeBytes(ByteArray(44)+pcm()+byteArrayOf(0x55))
            val row=requireNotNull(source.migrationReadTextRecords(setOf("voice-recording:$id"))["voice-recording:$id"])
            val index=requireNotNull(source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
            val rowFile=base(source,"voice-recording:$id");val indexFile=base(source,"voice-index-v1");val audioFile=base(source,"voice-audio:$id")
            val rowStat=stampJSON(rowFile);val indexStat=stampJSON(indexFile);val before=MigrationActivationFixtures.tree(source)
            require(Files.notExists(audioFile.toPath(),NOFOLLOW_LINKS));var calls=0;var marker: IOException?=null
            val store=VoiceRecordingStore(f.context("source"),source,NativeVoiceSealAudioCommittedCheckpoint {reached->
                assertEquals(id,reached);assertEquals(1,++calls);assertArrayEquals(audio(),raw.readBytes())
                assertEquals(row,source.migrationReadTextRecords(setOf("voice-recording:$id"))["voice-recording:$id"])
                assertEquals(index,source.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"])
                requireStamp(rowStat,rowFile);requireStamp(indexStat,indexFile)
                val probe=NativeMigrationProbe();source.migrationCopyBinaryTo("voice-audio:$id",audio().size,probe)
                assertEquals(audio().size.toLong(),probe.size);assertEquals(sha(audio()),probe.sha256())
                val failure=IOException("P7 three-PID writer named checkpoint: audio committed; row not submitted");marker=failure;throw failure
            })
            val failure=try{store.seal(pending);throw AssertionError("Expected exact known named checkpoint exception")}catch(error: IOException){error}
            assertNotNull(marker);assertSame(marker,failure);assertEquals(1,calls)
            assertTrue(failure.stackTrace.any {it.className.startsWith("com.agenthub.android.VoiceRecordingStore")})
            assertArrayEquals(audio(),raw.readBytes());requireStamp(rowStat,rowFile);requireStamp(indexStat,indexFile)
            val afterRow=JSONObject(requireNotNull(source.read("voice-recording:$id")));assertNull(NativeVoiceRecordingRecord.decode(afterRow).manifest)
            assertFalse(afterRow.has("audioStorage"));assertFalse(afterRow.has("audio"))
            assertEquals(before+(audioFile.name to sha(audioFile.readBytes())),MigrationActivationFixtures.tree(source))
            java.io.DataInputStream(audioFile.inputStream()).use {assertEquals(4,it.readInt())}
            val v=header(f,"writer",operation).put("id",id).put("row",row).put("index",index).put("draftKey",pending.draftKey)
                .put("size",audio().size).put("audioSha256",sha(audio())).put("rawStat",stampJSON(raw)).put("rowStat",rowStat).put("indexStat",indexStat).put("audioStat",stampJSON(audioFile))
                .put("checkpointCalls",calls).put("exceptionClass",failure.javaClass.name).put("exceptionMessage",failure.message).put("exceptionStack",failure.stackTraceToString())
                .put("sourceDrafts",source.metadata()!!.getJSONObject("drafts").toString()).put("rowWriteSubmitted",false)
            view(f,"source").using {saved->v.put("sourceCiphertext",JSONObject(saved.familyCiphertext())).put("sourceKeys",JSONArray(saved.boundKeys().keys.sorted()))}
            val exportOperation=UUID.randomUUID().toString();require(exportOperation!=operation)
            NativeVoiceRawOwnership.freeze(source,exportOperation).use {frozen->
                NativeVoiceRawOwnership.requireFrozen(source,exportOperation,frozen)
                NativeMigrationExport(f.context("source"),source).sealFile(f.envelope(),f.password,NativeMigrationOwnerLease(source,exportOperation,frozen){true})
            }
            v.put("exportOperationId",exportOperation).put("rawOwnerFreezeReacquired",true)
            val destination=f.storage("destination").also {destinationToRetire=it}
            // Writer-owned fresh setup only: staging a child alone does not provision this future existing-only root key.
            destination.write("metadata",JSONObject().put("version",1).put("clients",JSONArray()).put("drafts",JSONObject()).toString())
            val r=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
            require(setOf(operation,exportOperation,r.id).size==3)
            v.put("receipt",receiptJSON(r)).put("candidateOriginGeneration",JSONObject.NULL).put("audioFormat",4)
            view(f,"destination").using {saved->
                assertNull(saved.generation);saved.originalCandidate(null,r){records->
                    val catalog=records.text.getValue(NativeMigrationProvisionalVoice.RECORD);val body=JSONObject(catalog)
                    assertEquals(2,body.getInt("version"));assertEquals(1,body.getJSONArray("originals").length())
                    val original=body.getJSONArray("originals").getJSONObject(0);assertEquals(id,original.getString("id"));assertEquals(row,original.getString("row"));assertEquals(index,original.getString("index"))
                    v.put("catalog",catalog).put("protectedOperations",JSONObject(operations(records))).put("protectedText",JSONObject(protectedText(records)));candidate(records,v)
                }
                v.put("destinationCiphertext",JSONObject(saved.familyCiphertext())).put("candidateCiphertext",JSONObject(saved.originalCandidateCiphertext(null)))
                    .put("destinationKeys",JSONArray(saved.boundKeys().keys.sorted())).put("destinationNamespaces",JSONArray(saved.namespaces()))
            }
            sourceUnchanged(f,v);f.publish(WRITER,v.put("activationAttempted",false).put("stageCalls",1))
        } finally {source.retireWriter();destinationToRetire?.retireWriter()}
    }
    @Test fun activateExactWriterCandidateInSecondPidUsingOnlyExistingKeys()=runBlocking(Dispatchers.IO) {
        val f=MigrationProvisionalFixture();val writer=writerProof(f);val writerHash=proofHash(f,WRITER)
        assertNotEquals(Process.myPid(),writer.getJSONObject("process").getInt("pid"))
        require(Files.notExists(evidenceFile(f,INTENT).toPath(),NOFOLLOW_LINKS)&&Files.notExists(evidenceFile(f,ACTIVATION).toPath(),NOFOLLOW_LINKS))
        sourceUnchanged(f,writer);val r=receipt(writer)
        view(f,"destination").using {saved->
            assertNull(saved.generation);assertEquals(map(writer.getJSONObject("destinationCiphertext")),saved.familyCiphertext())
            assertEquals(strings(writer.getJSONArray("destinationKeys")).toSet(),saved.boundKeys().keys)
            assertEquals(strings(writer.getJSONArray("destinationNamespaces")),saved.namespaces())
            assertEquals(map(writer.getJSONObject("candidateCiphertext")),saved.originalCandidateCiphertext(null));saved.originalCandidate(null,r){candidate(it,writer)}
        }
        val operation=UUID.randomUUID().toString();require(operation !in setOf(writer.getString("operationId"),writer.getString("exportOperationId"),r.id))
        // This immutable local intent prevents a second invocation from replaying an unknown activation result.
        f.publish(INTENT,header(f,"activate-intent",operation).put("writerProofSha256",writerHash).put("receipt",receiptJSON(r)).put("existingKeysOnly",true))
        val destination=NativeSessionStorage(f.context("destination"),"installation",alias(f,"destination"),existingKeyOnly=true,existingNamespaceOnly=true)
        try {
            assertNull(destination.publishedMigration(r.id,r.sha256,r.recordCount))
            assertEquals(r,NativeMigrationCandidateStore(f.context("destination"),destination).currentReceipt())
            val generation=NativeMigrationActivation(f.context("destination"),destination).activate(r)
            val current=destination.reopen(f.context("destination"))
            try {
                assertEquals(r,current.migrationPublicationReceipt());assertEquals(generation,current.publishedMigration(r.id,r.sha256,r.recordCount))
                view(f,"destination").using {saved->assertEquals(generation,saved.published(r));imported(f,saved,writer,writer.getString("sourceDrafts"))}
                val metadata=current.metadata()!!;val later="Source78 independent later draft ${f.run}"
                metadata.getJSONObject("drafts").put(writer.getString("draftKey"),later);current.write("metadata",metadata.toString())
                val expectedDrafts=JSONObject(writer.getString("sourceDrafts")).put(writer.getString("draftKey"),later).toString()
                val result=header(f,"activate",operation).put("receipt",receiptJSON(r)).put("generation",generation).put("writerProofSha256",writerHash)
                    .put("intentProofSha256",proofHash(f,INTENT)).put("writerProcess",writer.getJSONObject("process")).put("draftsAfter",expectedDrafts).put("laterDraft",later)
                    .put("existingKeysOnly",true).put("activationCalls",1).put("restageCalls",0)
                view(f,"destination").using {saved->
                    assertEquals(generation,saved.published(r));imported(f,saved,writer,expectedDrafts)
                    assertEquals(strings(writer.getJSONArray("destinationKeys")).toSet(),saved.boundKeys().keys)
                    result.put("destinationCiphertext",JSONObject(saved.familyCiphertext())).put("destinationNamespaces",JSONArray(saved.namespaces()))
                }
                sourceUnchanged(f,writer);assertEquals(writerHash,proofHash(f,WRITER));f.publish(ACTIVATION,result)
            } finally {current.retireWriter()}
        } finally {destination.retireWriter()}
    }
    @Test fun auditSameCandidateAndPublicationInThirdPidWithoutActivationOrKeyCreation()=runBlocking(Dispatchers.IO) {
        val f=MigrationProvisionalFixture();val writer=writerProof(f);val intent=checked(f,INTENT,"activate-intent");val activation=checked(f,ACTIVATION,"activate")
        require(Files.notExists(evidenceFile(f,AUDIT).toPath(),NOFOLLOW_LINKS))
        val writerHash=proofHash(f,WRITER);val activationHash=proofHash(f,ACTIVATION)
        assertEquals(writerHash,intent.getString("writerProofSha256"));assertEquals(writerHash,activation.getString("writerProofSha256"));assertEquals(proofHash(f,INTENT),activation.getString("intentProofSha256"))
        assertEquals(intent.getString("operationId"),activation.getString("operationId"));assertEquals(intent.getJSONObject("process").toString(),activation.getJSONObject("process").toString())
        assertEquals(writer.getJSONObject("process").toString(),activation.getJSONObject("writerProcess").toString())
        val r=receipt(writer);assertEquals(r,receipt(intent));assertEquals(r,receipt(activation))
        val pids=listOf(writer.getJSONObject("process").getInt("pid"),activation.getJSONObject("process").getInt("pid"),Process.myPid())
        assertEquals(3,pids.distinct().size);assertTrue(activation.getBoolean("existingKeysOnly"));assertEquals(1,activation.getInt("activationCalls"));assertEquals(0,activation.getInt("restageCalls"))
        sourceUnchanged(f,writer)
        view(f,"destination").using {saved->
            assertEquals(activation.getString("generation"),saved.published(r));assertEquals(map(activation.getJSONObject("destinationCiphertext")),saved.familyCiphertext())
            assertEquals(strings(activation.getJSONArray("destinationNamespaces")),saved.namespaces());assertEquals(strings(writer.getJSONArray("destinationKeys")).toSet(),saved.boundKeys().keys)
            imported(f,saved,writer,activation.getString("draftsAfter"));saved.unchanged()
        }
        sourceUnchanged(f,writer);assertEquals(writerHash,proofHash(f,WRITER));assertEquals(activationHash,proofHash(f,ACTIVATION))
        val operation=UUID.randomUUID().toString();require(operation !in setOf(writer.getString("operationId"),writer.getString("exportOperationId"),activation.getString("operationId"),r.id))
        f.publish(AUDIT,header(f,"audit",operation).put("receipt",receiptJSON(r)).put("generation",activation.getString("generation"))
            .put("writerProofSha256",writerHash).put("activationProofSha256",activationHash).put("intentProofSha256",proofHash(f,INTENT))
            .put("writerProcess",writer.getJSONObject("process")).put("activationProcess",activation.getJSONObject("process"))
            .put("differentInvocationPids",true).put("existingKeysOnly",true).put("activationCalls",0).put("restageCalls",0)
            .put("sourceOriginalsUnchanged",true).put("originalCandidateCiphertextUnchanged",true).put("bothInertCopiesCompared",true)
            .put("independentDraftPreserved",true).put("activeVoiceRestored",false))
    }
}
