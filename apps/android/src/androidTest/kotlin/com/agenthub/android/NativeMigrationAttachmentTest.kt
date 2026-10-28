package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Synthetic originals through production file storage/export/candidate/activation; no Host or upload. */
class NativeMigrationAttachmentTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned attachment migration password"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-attachment")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun sourceFile(size: Int): File=File(owned.folder,"source-${UUID.randomUUID()}.bin").also {file->
        file.outputStream().use {out->val bytes=ByteArray(65536){(it*37%251).toByte()};var remaining=size
            while(remaining>0){val count=minOf(bytes.size,remaining);out.write(bytes,0,count);remaining-=count}}
    }
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private suspend fun fullFixture(source: NativeSessionStorage,size: Int=20*1024*1024): Pair<LocalDraftFile,File> {
        val value=MigrationActivationFixtures.payload();val records=value.getJSONObject("records")
        val oldName=records.keys().asSequence().single{it.startsWith("draft-file:")};val old=JSONObject(records.getString(oldName)).getJSONObject("file")
        val raw=sourceFile(size);val prepared=NativeDraftFiles(source).prepareFile(old.getString("name"),old.getString("mediaType"),raw,old.getString("id"))
        val file=try{prepared.file}finally{prepared.close()};records.remove(oldName)
        val metadata=JSONObject(records.getString("metadata"));val drafts=metadata.getJSONObject("draftFiles");drafts.keys().forEach{drafts.put(it,JSONArray().put(file.manifest.body()))};records.put("metadata",metadata.toString())
        val submission=records.keys().asSequence().single{it.startsWith("submission:")};records.put(submission,JSONObject(records.getString(submission)).put("files",JSONArray().put(file.manifest.body())).toString())
        // The original synthetic intent already names this same UUID; no stored intent is edited.
        records.keys().forEach{source.write(it,records.getString(it))};return file to raw
    }
    private fun export(source: NativeSessionStorage)=File(owned.folder,"export-${UUID.randomUUID()}.migration").also {NativeMigrationExport(ui.activity,source).sealFile(it,password)}
    private suspend fun verify(storage: NativeSessionStorage,file: CreationFile) {
        val probe=NativeMigrationProbe();NativeDraftFiles(storage).copyTo(file,probe)
        assertEquals(file.sizeBytes,probe.size);assertEquals(file.sha256,probe.sha256())
        val record=JSONObject(storage.read("draft-file:${file.id}")!!);assertEquals(2,record.getInt("version"));assertFalse(record.has("bytes"));assertEquals(file.body().toString(),record.getJSONObject("file").toString())
    }
    @Test fun maximumTwentyMiBOriginalStreamsThroughMigrationWithExactUuidManifestIntentAndReceipt()=runBlocking {
        val start=android.os.SystemClock.elapsedRealtime();val source=owned.storage.child(ui.activity,"attachment-source");val (file,raw)=fullFixture(source)
        val before=tree(source);val metadata=source.read("draft-file:${file.manifest.id}")!!;assertTrue(metadata.length<2048)
        val repeated=NativeDraftFiles(source).prepareFile(file.manifest.name,file.manifest.mediaType,raw,file.manifest.id)
        try{assertFalse(repeated.disposable);assertEquals(file.manifest,repeated.file.manifest)}finally{repeated.close()};assertEquals(before,tree(source));verify(source,file.manifest)
        val envelope=export(source);assertTrue(envelope.length()>20*1024*1024)
        val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val receipt=store.stageFile(UUID.randomUUID().toString(),envelope,password)
        val archive=NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt){it.text};val local=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=tree(local)
        assertEquals(receipt,store.stageFile(receipt.id,envelope,password));assertEquals(candidateBefore,tree(local))
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity);verify(current,file.manifest)
        assertEquals(metadata,current.read("draft-file:${file.manifest.id}"))
        archive.filterKeys{it.startsWith("operations:")}.forEach {(name,original)->
            val old=JSONObject(original).getJSONArray("operations").getJSONObject(0);val imported=JSONObject(current.read(name)!!).getJSONArray("operations").getJSONObject(0)
            for(key in listOf("id","intent","intentSha256","result","phase","submissionAttempted","detached","sourceKey","sourceVersion"))assertEquals(old.get(key),imported.get(key))
            assertEquals("uncertain",imported.getString("phase"));assertTrue(imported.getBoolean("migrationReadOnly"))
        }
        current.write("later-edit","independent attachment draft");val after=tree(current)
        assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current));assertEquals(before,tree(source));assertEquals(candidateBefore,tree(local))
        File(owned.folder,"maximum-attachment.json").writeText(JSONObject().put("bytes",file.manifest.sizeBytes).put("id",file.manifest.id).put("sha256",file.manifest.sha256)
            .put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start).put("metadataBytes",metadata.toByteArray().size)
            .put("originalSourcePreserved",true).put("immutableIntentAndUnknownReceipt",true).put("sameIdCandidate",true).put("laterDraft",true).put("remoteWrites",0).toString())
    }
    @Test fun twoMaximumAttachmentsRespectAggregateStreamingAndPreserveOriginalOrderedManifests()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val (first,raw)=fullFixture(source)
        val second=NativeDraftFiles(source).importFile("second original.bin","application/octet-stream",raw)
        val metadata=source.metadata()!!;val drafts=metadata.getJSONObject("draftFiles");drafts.keys().forEach{drafts.put(it,JSONArray().put(first.manifest.body()).put(second.manifest.body()))};source.write("metadata",metadata.toString())
        val before=tree(source);val envelope=export(source);assertTrue(envelope.length()>40L*1024*1024)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),envelope,password)
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        verify(current,first.manifest);verify(current,second.manifest);assertEquals(metadata.getJSONObject("draftFiles").toString(),current.metadata()!!.getJSONObject("draftFiles").toString());assertEquals(before,tree(source))
    }
    @Test fun prepareInterruptionRemovesOnlyItsUnpublishedPayloadAndKeepsAllPriorOriginals()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");fullFixture(source,65536);val before=tree(source);val raw=sourceFile(131072)
        for(at in listOf(1,2,0)) {
            assertTrue(runCatching{NativeDraftFiles(source).prepareFile("interrupted.bin","application/octet-stream",raw,UUID.randomUUID().toString()){if(it==at)throw java.io.IOException("owned checkpoint")}}.isFailure)
            assertEquals(before,tree(source))
        }
        val over=File(owned.folder,"oversize.bin");RandomAccessFile(over,"rw").use{it.setLength(20L*1024*1024+1)}
        assertTrue(runCatching{NativeDraftFiles(source).importFile("too-large.bin","application/octet-stream",over)}.isFailure);assertEquals(before,tree(source))
    }
    @Test fun cancellationBeforeHandoffRetiresOnlyKnownFreshCommitAndReleasesFixedIdLease()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");fullFixture(source,65536);val before=tree(source);val raw=sourceFile(131072);val id=UUID.randomUUID().toString()
        val canceled=async {
            val job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            NativeDraftFiles(source).prepareFile("canceled.bin","application/octet-stream",raw,id){if(it==0)job.cancel()}
        }
        assertTrue(runCatching{canceled.await()}.exceptionOrNull() is kotlinx.coroutines.CancellationException);assertTrue(canceled.isCancelled);assertEquals(before,tree(source))
        val next=NativeDraftFiles(source).prepareFile("canceled.bin","application/octet-stream",raw,id)
        try{assertTrue(next.disposable);NativeDraftFiles(source).discardPrepared(next.file)}finally{next.close()};assertEquals(before,tree(source))
    }
    @Test fun corruptPayloadAndUnclaimedPayloadRefuseReadOrExportWithoutRepairingOriginals()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val (file,_)=fullFixture(source,131072)
        val payload=NativeDraftFiles.record(JSONObject(source.read("draft-file:${file.manifest.id}")!!)).payload!!
        val encrypted=File(source.coordinationKey,MigrationActivationFixtures.sha(payload.toByteArray())+".bin");val original=encrypted.readBytes()
        encrypted.writeBytes(original.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()});val damaged=tree(source)
        assertTrue(runCatching{NativeDraftFiles(source).copyTo(file.manifest,nullOutputStreamCompat())}.isFailure)
        val output=File(owned.folder,"previous-export.migration");output.writeText("previous export")
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).sealFile(output,password)}.isFailure);assertEquals("previous export",output.readText());assertEquals(damaged,tree(source))
        encrypted.writeBytes(original) // Only this deliberately damaged private fixture is restored.
        val orphan=NativeDraftFiles.payloadName(UUID.randomUUID().toString(),UUID.randomUUID().toString());source.writeBinaryBytes(orphan,byteArrayOf(1,2,3),3);val orphanBefore=tree(source)
        assertTrue(runCatching{NativeMigrationExport(ui.activity,source).sealFile(output,password)}.isFailure);assertEquals(orphanBefore,tree(source));assertEquals("previous export",output.readText())
    }
    @Test fun legacyInlineSourceReadsExactOriginalWithoutRewriteAndNewByteEntryUsesVersionTwo()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val value=MigrationActivationFixtures.payload().getJSONObject("records");value.keys().forEach{source.write(it,value.getString(it))}
        val name=value.keys().asSequence().single{it.startsWith("draft-file:")};val row=JSONObject(value.getString(name));val file=NativeDraftFiles.record(row).file
        val bytes=java.util.Base64.getDecoder().decode(row.getString("bytes"));val raw=File(owned.folder,"legacy-original.bin");raw.writeBytes(bytes);val before=tree(source)
        assertArrayEquals(bytes,NativeDraftFiles(source).bytes(file));val original=NativeDraftFiles(source).prepareFile(file.name,file.mediaType,raw,file.id)
        try{assertFalse(original.disposable);assertEquals(file,original.file.manifest)}finally{original.close()};assertEquals(before,tree(source))
        val local=owned.storage.child(ui.activity,"new-file-entry");val new=NativeDraftFiles(local).import("new.bin","application/octet-stream",bytes)
        assertEquals(2,JSONObject(local.read("draft-file:${new.manifest.id}")!!).getInt("version"));assertArrayEquals(bytes,NativeDraftFiles(local).bytes(new.manifest))
        val empty=NativeDraftFiles(local).import("empty.txt","text/plain",ByteArray(0));assertEquals("empty-v1",JSONObject(local.read("draft-file:${empty.manifest.id}")!!).getString("storage"));assertArrayEquals(ByteArray(0),NativeDraftFiles(local).bytes(empty.manifest))
    }
    @Test fun explicitPreparedCleanupRetiresOnlyItsExactVersionTwoPayload()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val store=NativeDraftFiles(source);val bytes=sourceFile(131072)
        val first=store.prepareFile("first.bin","application/octet-stream",bytes);val second=store.prepareFile("second.bin","application/octet-stream",bytes)
        val secondMetadata=source.read("draft-file:${second.file.manifest.id}")!!;val secondPayload=NativeDraftFiles.record(JSONObject(secondMetadata)).payload!!;val secondSize=source.storedBytes(secondPayload)
        store.discardPrepared(first.file);assertNull(source.read("draft-file:${first.file.manifest.id}"));assertEquals(secondMetadata,source.read("draft-file:${second.file.manifest.id}"));assertEquals(secondSize,source.storedBytes(secondPayload))
        verify(source,second.file.manifest);first.close();second.close()
    }
    @Test fun attachmentCandidateAndActivationFailuresPreserveOriginalSlotAndInstallation()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val (file,_)=fullFixture(source,131072);val envelope=export(source);val sourceBefore=tree(source)
        val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val receipt=store.stageFile(UUID.randomUUID().toString(),envelope,password)
        val local=owned.storage.child(ui.activity,"migration-candidates");val prior=tree(local)
        val corrupt=File(owned.folder,"corrupt.migration");envelope.copyTo(corrupt);RandomAccessFile(corrupt,"rw").use{out->out.seek(out.length()-1);val last=out.read();out.seek(out.length()-1);out.write(last xor 1)}
        assertTrue(runCatching{store.stageFile(UUID.randomUUID().toString(),corrupt,password,receipt)}.isFailure);assertEquals(prior,tree(local))
        owned.storage.write("existing-local-draft","must survive every failed activation");val activeBefore=tree(owned.storage)
        for(stop in listOf(1,receipt.recordCount,0)) {
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==stop)throw java.io.IOException("owned activation interruption")}}.isFailure)
            assertEquals(activeBefore,tree(owned.storage));assertEquals(prior,tree(local));assertEquals(sourceBefore,tree(source))
        }
        NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);verify(owned.storage.reopen(ui.activity),file.manifest)
    }
    @Test fun stageMaximumAttachmentForIndependentColdReader()=runBlocking {
        val source=owned.storage.child(ui.activity,"attachment-source");val (file,_)=fullFixture(source);val sourceBefore=tree(source)
        val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stageFile(UUID.randomUUID().toString(),export(source),password)
        val records=NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt){it.text};owned.verifyDefault()
        val namespace=JSONObject(File(owned.folder,"default-storage-proof.json").readText()).getString("namespace")
        File(owned.folder,"attachment-cold-writer.json").writeText(JSONObject().put("version",1).put("kind","migration-attachment-staged-cold")
            .put("namespace",namespace).put("writerPid",android.os.Process.myPid()).put("candidateId",receipt.id).put("sha256",receipt.sha256)
            .put("recordCount",receipt.recordCount).put("file",file.manifest.body()).put("records",JSONObject(records)).put("sourceCiphertext",JSONObject(sourceBefore))
            .put("candidateCiphertext",JSONObject(tree(owned.storage.child(ui.activity,"migration-candidates")))).toString())
    }
    @Test fun activateOriginalMaximumAttachmentInIndependentColdReader()=runBlocking {
        val name=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("migrationActivationFixture")?:error("Original attachment fixture required")
        require(name.matches(Regex("activation-cold-[a-f0-9]{16}\\.json")))
        val fixture=JSONObject(File(ui.activity.filesDir,"acceptance/$name").readText());require(fixture.get("version")==1&&fixture.get("kind")=="migration-attachment-staged-cold")
        assertNotEquals(fixture.getInt("writerPid"),android.os.Process.myPid());val namespace=fixture.getString("namespace");require(UUID.fromString(namespace).toString()==namespace)
        val parent=NativeSessionStorage(ui.activity,"P4-owned-control-$namespace");val receipt=NativeMigrationCandidateStore.Receipt(fixture.getString("candidateId"),fixture.getString("sha256"),fixture.getInt("recordCount"))
        val local=parent.child(ui.activity,"migration-candidates");assertEquals(fixture.getJSONObject("candidateCiphertext").toString(),JSONObject(tree(local)).toString())
        val generation=NativeMigrationActivation(ui.activity,parent).activate(receipt);val current=parent.reopen(ui.activity);val body=fixture.getJSONObject("file")
        val file=CreationFile(body.getString("id"),body.getString("name"),body.getString("mediaType"),body.getLong("sizeBytes"),body.getString("sha256"));assertEquals(20L*1024*1024,file.sizeBytes);verify(current,file)
        assertEquals(fixture.getJSONObject("records").getString("draft-file:${file.id}"),current.read("draft-file:${file.id}"))
        fixture.getJSONObject("records").let {records->records.keys().asSequence().filter{it.startsWith("operations:")}.forEach {key->
            val original=JSONObject(records.getString(key)).getJSONArray("operations").getJSONObject(0);val imported=JSONObject(current.read(key)!!).getJSONArray("operations").getJSONObject(0)
            for(field in listOf("id","intent","intentSha256","result","phase","submissionAttempted","detached","sourceKey","sourceVersion"))assertEquals(original.get(field),imported.get(field))
            assertEquals("uncertain",imported.getString("phase"));assertTrue(imported.getBoolean("migrationReadOnly"))
        }}
        current.write("later-edit","cold attachment later draft");val activeBefore=tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,parent).activate(receipt));assertEquals(activeBefore,tree(current))
        assertEquals(fixture.getJSONObject("candidateCiphertext").toString(),JSONObject(tree(local)).toString());assertEquals(fixture.getJSONObject("sourceCiphertext").toString(),JSONObject(tree(parent.child(ui.activity,"attachment-source"))).toString())
        File(owned.folder,"attachment-cold-reader.json").writeText(JSONObject().put("writerPid",fixture.getInt("writerPid")).put("readerPid",android.os.Process.myPid())
            .put("bytes",file.sizeBytes).put("uuidManifestAndDigest",true).put("originalUnknownIntent",true).put("originalCandidateUnchanged",true).put("laterDraftPreserved",true).put("sourcePreserved",true).put("remoteWrites",0).toString())
    }
}
