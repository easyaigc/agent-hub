package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in fresh structural installation pair; no actual original operation or shared default is used. */
internal class MigrationProvisionalFixture {
    val app=InstrumentationRegistry.getInstrumentation().targetContext
    val run=checkNotNull(InstrumentationRegistry.getArguments().getString("migrationProvisionalRun")).also {require(UUID.fromString(it).toString()==it)}
    val folder=File(app.filesDir,"acceptance/P7-provisional-$run")
    init {NativeChatNotificationNaturalStore.guard(app)}
    fun fresh(){require(!folder.exists());check(folder.mkdirs());android.system.Os.chmod(folder.path,0x1c0);for(role in listOf("source","destination")){val p=File(folder,role);check(p.mkdir());android.system.Os.chmod(p.path,0x1c0)}}
    fun context(role: String): Context {
        require(role in setOf("source","destination"));val path=File(folder,role)
        require(path.isDirectory&&!java.nio.file.Files.isSymbolicLink(path.toPath()))
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=path;override fun getApplicationContext(): Context=this}
    }
    fun storage(role: String)=NativeSessionStorage(context(role),"installation","p7-provisional-$role-$run")
    fun originalBytes()=ByteArray(131_073){(it*37%251).toByte()} // Deliberately incomplete PCM; never sealed/repaired.
    suspend fun seed(): PendingVoiceRecording {
        val storage=storage("source");val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records");rows.keys().forEach {storage.write(it,rows.getString(it))}
        val client=MigrationActivationFixtures.client(value);val store=VoiceRecordingStore(context("source"),storage)
        return store.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,client.cacheKey("synthetic-thread"),"draft","synthetic-thread","原稿 provisional 😀").also {store.file(it.id).writeBytes(originalBytes())}
    }
    fun export(source: NativeSessionStorage,destination: File=envelope()) {
        val operation=UUID.randomUUID().toString()
        val parent=requireNotNull(File(source.coordinationKey).parentFile).canonicalFile
        val role=listOf("source","destination").single {context(it).noBackupFilesDir.canonicalFile==parent}
        NativeVoiceRawOwnership.freeze(source,operation).use {raw->
            NativeMigrationExport(context(role),source)
                .sealFile(destination,password,NativeMigrationOwnerLease(source,operation,raw){true})
        }
    }
    fun envelope()=File(folder,"original.migration")
    val password="owned provisional streaming migration"
    fun publish(name: String,value: JSONObject) {
        val f=File(folder,"$name.json");check(f.createNewFile());f.writeText(value.toString());java.io.FileOutputStream(f,true).use {it.fd.sync()}
    }
    fun proof(name: String)=StrictMigrationJson.parse(File(folder,"$name.json").readBytes())
    fun receipt(v: JSONObject)=NativeMigrationCandidateStore.Receipt(v.getString("candidateId"),v.getString("sha256"),v.getInt("records"))
    fun header(r: NativeMigrationCandidateStore.Receipt)=JSONObject().put("run",run).put("pid",Process.myPid()).put("candidateId",r.id).put("sha256",r.sha256).put("records",r.recordCount)
    fun hashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    fun sourceUnchanged(v: JSONObject) {
        val source=storage("source");assertEquals(hashes(v.getJSONObject("sourceTree")),MigrationActivationFixtures.tree(source))
        assertArrayEquals(originalBytes(),File(context("source").noBackupFilesDir,"voice-recordings-v1/${v.getString("id")}.wav").readBytes())
        assertEquals(v.getString("original"),source.read("voice-recording:${v.getString("id")}"))
    }
    suspend fun inspect(v: JSONObject): NativeSessionStorage {
        val ctx=context("destination");val current=storage("destination");val all=NativeMigrationProvisionalVoice.read(current);val original=all.single()
        assertEquals(v.getString("id"),original.id);assertEquals(v.getString("original"),original.row)
        val bytes=java.io.ByteArrayOutputStream();NativeMigrationProvisionalVoice.copy(current,original,bytes);assertArrayEquals(originalBytes(),bytes.toByteArray())
        val pending=NativeVoiceRecordingRecord.decode(JSONObject(original.row))
        assertNull(current.read("voice-recording:${original.id}"));assertEquals("[]",current.read("voice-index-v1"))
        assertNull(VoiceRecordingStore(ctx,current).restore(pending.scope,pending.certificate,pending.draftKey,pending.originalText))
        assertFalse(File(ctx.noBackupFilesDir,"voice-recordings-v1/${original.id}.wav").exists())
        sourceUnchanged(v);return current
    }
}

class NativeMigrationProvisionalVoiceTest {
    /** Fixed production flags only. A deadline observes unknown; it never interrupts an OS open. */
    @Test fun indexedFifoOriginalRejectsWithoutPeerAndRetainsSourceAndOwner()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source")
        val raw=File(f.context("source").noBackupFilesDir,"voice-recordings-v1/${pending.id}.wav")
        check(raw.delete()) // Only the fresh fixture's synthetic regular WAV is replaced.
        android.system.Os.mkfifo(raw.path,0x180)
        fun stamp(): List<Long> = android.system.Os.lstat(raw.path).let {s->
            listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)
        }
        fun statJson(values: List<Long>)=JSONObject().also {v->
            listOf("dev","ino","mode","nlink","uid","gid","size","mtime","ctime").forEachIndexed {n,key->v.put(key,values[n])}
        }
        val beforeStat=stamp()
        check(android.system.OsConstants.S_ISFIFO(beforeStat[2].toInt())&&beforeStat[3]==1L&&beforeStat[4]==Process.myUid().toLong()&&(beforeStat[2].toInt() and 0x1ff)==0x180&&beforeStat[6]==0L)
        /** Inspect this process's FD targets without opening, reading or supplying a FIFO peer. */
        fun matchingFds(): List<Int> = checkNotNull(File("/proc/self/fd").list()).map {name->
            checkNotNull(name.toIntOrNull())
        }.sorted().filter {number->
            val copy=try {android.os.ParcelFileDescriptor.fromFd(number)}catch(error: java.io.IOException) {
                // Only an independently absent FD may be the already-closed enumeration handle.
                check(java.nio.file.Files.notExists(File("/proc/self/fd/$number").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                null
            }
            val s=copy?.use {android.system.Os.fstat(it.fileDescriptor)} // Close only the duplicate, never the original.
            s!=null&&s.st_dev==beforeStat[0]&&s.st_ino==beforeStat[1]&&android.system.OsConstants.S_ISFIFO(s.st_mode)
        }
        fun evidence(name: String,value: JSONObject) {
            f.publish(name,value);android.system.Os.chmod(File(f.folder,"$name.json").path,0x180)
        }
        val beforeFds=matchingFds();check(beforeFds.isEmpty())
        val beforeRow=checkNotNull(source.read("voice-recording:${pending.id}"));val beforeIndex=checkNotNull(source.read("voice-index-v1"))
        val beforeRoot=MigrationActivationFixtures.tree(source)
        val destination=f.context("destination").noBackupFilesDir
        val beforeDestination=checkNotNull(destination.list()).sorted()
        val output=File(f.folder,"fifo-output.migration");val sentinel="fixture output remains unpublished".toByteArray(Charsets.UTF_8)
        check(output.createNewFile());output.writeBytes(sentinel);android.system.Os.chmod(output.path,0x180)
        val beforeOutput=voiceSHA(output.readBytes())
        evidence("fifo-intent",JSONObject().put("version",1).put("run",f.run).put("pid",Process.myPid()).put("recordingId",pending.id)
            .put("expectedProductionFlags","O_RDONLY|O_NONBLOCK|O_NOFOLLOW").put("fifoBefore",statJson(beforeStat))
            .put("fdsBefore",org.json.JSONArray(beforeFds)).put("rowBefore",beforeRow).put("indexBefore",beforeIndex)
            .put("sourceBefore",JSONObject(beforeRoot)).put("destinationBefore",org.json.JSONArray(beforeDestination))
            .put("outputBeforeSha256",beforeOutput).put("cleanupPolicy","Normal copy return, exact rejection, zero matching FDs, unchanged originals and released owner only; otherwise retain"))
        val started=System.nanoTime();val budgetNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        // Deliberately synchronous. No withTimeout/Future/thread interruption can certify this open exited.
        val failure=try {f.export(source,output);null}catch(error: Exception){error}
        val elapsed=System.nanoTime()-started // This line proves the exact production call returned.
        val afterFds=matchingFds();val afterStat=stamp()
        val afterRow=source.read("voice-recording:${pending.id}");val afterIndex=source.read("voice-index-v1")
        val afterRoot=MigrationActivationFixtures.tree(source);val afterDestination=checkNotNull(destination.list()).sorted()
        val afterOutput=voiceSHA(output.readBytes())
        val top=failure?.stackTrace?.firstOrNull()
        val rejectedInCopy=failure is IllegalArgumentException&&top!=null&&top.className=="com.agenthub.android.NativeMigrationProvisionalFiles"&&top.methodName=="copy"
        val noSidecars=listOf(".new",".bak").all {suffix->java.nio.file.Files.notExists(File(output.path+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)}
        evidence("fifo-return",JSONObject().put("version",1).put("run",f.run).put("pid",Process.myPid()).put("copyReturned",true)
            .put("elapsedNanos",elapsed).put("budgetNanos",budgetNanos).put("rejectedInCopy",rejectedInCopy)
            .put("errorClass",failure?.javaClass?.name ?: JSONObject.NULL).put("errorFrame",top?.let {"${it.className}.${it.methodName}"} ?: JSONObject.NULL)
            .put("fifoAfter",statJson(afterStat)).put("fdsAfter",org.json.JSONArray(afterFds)).put("rowAfter",afterRow).put("indexAfter",afterIndex)
            .put("sourceAfter",JSONObject(afterRoot)).put("destinationAfter",org.json.JSONArray(afterDestination))
            .put("outputAfterSha256",afterOutput).put("outputSidecarsAbsent",noSidecars).put("cleanupAuthorized",false))
        // Every failed check retains the FIFO/context/keys/evidence; there is no finally deletion.
        assertTrue("Only normal return inside the bound may pass",elapsed in 0..budgetNanos)
        assertTrue("Reject at the real raw copy type check",rejectedInCopy)
        assertEquals(beforeStat,afterStat);assertTrue("Exact FIFO FD must be closed",afterFds.isEmpty())
        assertEquals(beforeRow,afterRow);assertEquals(beforeIndex,afterIndex);assertEquals(beforeRoot,afterRoot)
        assertEquals(beforeDestination,afterDestination);assertEquals(beforeOutput,afterOutput);assertArrayEquals(sentinel,output.readBytes());assertTrue(noSidecars)
        NativeVoiceRawOwnership.freeze(source,f.run).use {held->NativeVoiceRawOwnership.requireFrozen(source,f.run,held)}
        val finalFds=matchingFds();assertTrue("No retained FIFO FD after owner release",finalFds.isEmpty());assertEquals(beforeStat,stamp())
        check(coroutineContext[kotlinx.coroutines.Job]?.isActive==true&&!Thread.currentThread().isInterrupted&&System.nanoTime()-started<=budgetNanos)
        evidence("fifo-verified",JSONObject().put("version",1).put("run",f.run).put("copyReturned",true).put("matchingFds",org.json.JSONArray(finalFds))
            .put("fifoUnchanged",true).put("originalsAndOutputUnchanged",true).put("ownerReleased",true).put("cleanupEligible",true))
        // Only this exact known-return/known-closed synthetic FIFO is reclaimed. Other evidence and roots remain.
        check(coroutineContext[kotlinx.coroutines.Job]?.isActive==true&&!Thread.currentThread().isInterrupted&&System.nanoTime()-started<=budgetNanos)
        assertEquals(beforeStat,stamp());check(matchingFds().isEmpty());check(System.nanoTime()-started<=budgetNanos);java.nio.file.Files.delete(raw.toPath())
        check(java.nio.file.Files.notExists(raw.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        evidence("fifo-cleanup",JSONObject().put("version",1).put("run",f.run).put("fifoRemovedAfterKnownReturnAndClose",true)
            .put("retained","Normal JSON evidence, sentinel output and exact synthetic encrypted roots; Root owns subsequent precise cleanup"))
    }
    /** A scoped reader-closure check; global process FD inspection remains a separate acceptance gate. */
    @Test fun indexedFifoOriginalRejectsWithoutPeerAndClosesReader()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source")
        val raw=File(f.context("source").noBackupFilesDir,"voice-recordings-v1/${pending.id}.wav")
        check(raw.delete());android.system.Os.mkfifo(raw.path,0x180)
        fun stamp()=android.system.Os.lstat(raw.path).let {v->listOf(v.st_dev,v.st_ino,v.st_mode.toLong(),v.st_nlink,v.st_uid.toLong(),v.st_gid.toLong(),v.st_size,v.st_mtime,v.st_ctime)}
        fun jsonStat(v: List<Long>)=JSONObject().also {j->listOf("dev","ino","mode","nlink","uid","gid","size","mtime","ctime").forEachIndexed {n,k->j.put(k,v[n])}}
        fun evidence(name: String,value: JSONObject){f.publish(name,value);android.system.Os.chmod(File(f.folder,"$name.json").path,0x180)}
        val before=stamp();check(android.system.OsConstants.S_ISFIFO(before[2].toInt())&&before[3]==1L&&before[4]==Process.myUid().toLong()&&before[6]==0L)
        val row=checkNotNull(source.read("voice-recording:${pending.id}"));val index=checkNotNull(source.read("voice-index-v1"));val cipher=MigrationActivationFixtures.tree(source)
        val destination=f.context("destination").noBackupFilesDir;val dest=checkNotNull(destination.list()).sorted()
        val output=File(f.folder,"fifo-output.migration");val sentinel="fixture output remains unpublished".toByteArray(Charsets.UTF_8)
        check(output.createNewFile());output.writeBytes(sentinel);android.system.Os.chmod(output.path,0x180)
        evidence("fifo-intent",JSONObject().put("version",1).put("run",f.run).put("pid",Process.myPid()).put("recordingId",pending.id)
            .put("expectedProductionFlags","O_RDONLY|O_NONBLOCK|O_NOFOLLOW").put("fifoBefore",jsonStat(before))
            .put("rowBefore",row).put("indexBefore",index).put("sourceBefore",JSONObject(cipher)).put("destinationBefore",org.json.JSONArray(dest))
            .put("outputBeforeSha256",voiceSHA(sentinel)).put("globalFdInspectionPerformed",false).put("noPeerBeforeOrDuringExport",true))
        val started=System.nanoTime();val budget=java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        val failure=try {f.export(source,output);null}catch(error: Exception){error}
        val elapsed=System.nanoTime()-started;val top=failure?.stackTrace?.firstOrNull()
        val rejected=failure is IllegalArgumentException&&top!=null&&top.className=="com.agenthub.android.NativeMigrationProvisionalFiles"&&top.methodName=="copy"
        evidence("fifo-return",JSONObject().put("version",1).put("run",f.run).put("pid",Process.myPid()).put("copyReturned",true)
            .put("elapsedNanos",elapsed).put("budgetNanos",budget).put("rejectedInCopy",rejected).put("fifoAfter",jsonStat(stamp()))
            .put("rowAfter",source.read("voice-recording:${pending.id}")).put("indexAfter",source.read("voice-index-v1"))
            .put("sourceAfter",JSONObject(MigrationActivationFixtures.tree(source))).put("destinationAfter",org.json.JSONArray(checkNotNull(destination.list()).sorted()))
            .put("outputAfterSha256",voiceSHA(output.readBytes())).put("errorClass",failure?.javaClass?.name ?: JSONObject.NULL)
            .put("errorFrame",top?.let {"${it.className}.${it.methodName}"} ?: JSONObject.NULL).put("cleanupAuthorized",false))
        assertTrue(elapsed in 0..budget);assertTrue(rejected);assertEquals(before,stamp());assertEquals(row,source.read("voice-recording:${pending.id}"))
        assertEquals(index,source.read("voice-index-v1"));assertEquals(cipher,MigrationActivationFixtures.tree(source));assertEquals(dest,checkNotNull(destination.list()).sorted());assertArrayEquals(sentinel,output.readBytes())
        assertTrue(listOf(".new",".bak").all {java.nio.file.Files.notExists(File(output.path+it).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)})
        // These attempts run only AFTER the exact synchronous export returned and its rejection was verified.
        // ENXIO opens no writer handle. A leaked read end makes open succeed, is closed here and fails the case.
        fun requireNoReader(): Int {
            assertEquals(before,stamp())
            val probe=try {android.system.Os.open(raw.path,android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW,0)}
                catch(error: android.system.ErrnoException){assertEquals(android.system.OsConstants.ENXIO,error.errno);return error.errno}
            try {error("The exact FIFO still has a reader; retain fixture")}finally{android.system.Os.close(probe)}
        }
        val first=requireNoReader();NativeVoiceRawOwnership.freeze(source,f.run).use {NativeVoiceRawOwnership.requireFrozen(source,f.run,it)}
        val second=requireNoReader();assertEquals(before,stamp())
        check(coroutineContext[kotlinx.coroutines.Job]?.isActive==true&&!Thread.currentThread().isInterrupted&&System.nanoTime()-started<=budget)
        evidence("fifo-verified",JSONObject().put("version",1).put("run",f.run).put("copyReturned",true).put("originalsAndOutputUnchanged",true)
            .put("fifoUnchanged",true).put("ownerReleased",true).put("readerAbsenceErrnos",org.json.JSONArray(listOf(first,second)))
            .put("readerChecksAfterKnownReturn",true).put("writerPeerCreated",false).put("globalFdInspectionPerformed",false).put("processFdClosureProven",false).put("cleanupEligible",true))
        check(coroutineContext[kotlinx.coroutines.Job]?.isActive==true&&!Thread.currentThread().isInterrupted&&System.nanoTime()-started<=budget)
        assertEquals(before,stamp());java.nio.file.Files.delete(raw.toPath());check(java.nio.file.Files.notExists(raw.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        evidence("fifo-cleanup",JSONObject().put("version",1).put("run",f.run).put("fifoRemovedAfterKnownReturnAndNoReader",true).put("globalFdInspectionPerformed",false))
    }
    @Test fun fullStreamCandidateAndAtomicActivationPreserveExactInertOriginalAndLaterDraft()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source");val before=MigrationActivationFixtures.tree(source)
        f.export(source)
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password);val candidateTree=MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates"))
        assertEquals(receipt,candidates.stageFile(receipt.id,f.envelope(),f.password));assertEquals(candidateTree,MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates")))
        val generation=NativeMigrationActivation(f.context("destination"),destination).activate(receipt)
        val v=f.header(receipt).put("id",pending.id).put("original",source.read("voice-recording:${pending.id}")).put("sourceTree",JSONObject(before))
        val current=f.inspect(v);val meta=current.metadata()!!;meta.getJSONObject("drafts").put(pending.draftKey,"preserve later draft");current.write("metadata",meta.toString());val tree=MigrationActivationFixtures.tree(current)
        assertEquals(generation,NativeMigrationActivation(f.context("destination"),current).activate(receipt));assertEquals(tree,MigrationActivationFixtures.tree(current))
        val repeatFile=File(f.folder,"reexport.migration");f.export(current,repeatFile)
        assertEquals("preserve later draft",current.metadata()!!.getJSONObject("drafts").getString(pending.draftKey));f.sourceUnchanged(v)
    }
    @Test fun corruptUnknownSchemaAndInterruptedActivationKeepCandidateAndOriginals()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();f.seed();val source=f.storage("source");val before=MigrationActivationFixtures.tree(source)
        f.export(source)
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password);val rootBefore=MigrationActivationFixtures.tree(destination)
        val bad=File(f.folder,"damaged.migration");val raw=f.envelope().readBytes();raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte();bad.writeBytes(raw)
        assertThrows(Exception::class.java){candidates.stageFile(UUID.randomUUID().toString(),bad,f.password,receipt)}
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            val text=NativeMigrationTextMap.copy(records.text);text[NativeMigrationProvisionalVoice.RECORD]=JSONObject(text.getValue(NativeMigrationProvisionalVoice.RECORD)).put("version",2).toString()
            assertThrows(Exception::class.java){candidates.validateStreamRecords(records.copy(text=text))}
        }
        assertThrows(Exception::class.java){NativeMigrationActivation(f.context("destination"),destination).activate(receipt){if(it==0)error("Owned interruption before publication")}}
        assertEquals(rootBefore,MigrationActivationFixtures.tree(destination));assertEquals(receipt,candidates.currentReceipt());assertEquals(before,MigrationActivationFixtures.tree(source))
    }
    @Test fun emptyAbsentLinkedUnindexedAndChangedRawInventoryHaveExplicitOutcomes()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source");val raw=File(f.context("source").noBackupFilesDir,"voice-recordings-v1/${pending.id}.wav")
        val before=MigrationActivationFixtures.tree(source)
        for(kind in listOf("empty","absent")) {
            if(kind=="empty")raw.writeBytes(byteArrayOf())else check(raw.delete())
            val output=File(f.folder,"$kind.migration");f.export(source,output)
            val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
            val prior=candidates.currentReceipt();val receipt=candidates.stageFile(UUID.randomUUID().toString(),output,f.password,prior)
            NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
                val original=JSONObject(records.text.getValue(NativeMigrationProvisionalVoice.RECORD)).getJSONArray("originals").getJSONObject(0)
                assertEquals(kind,original.getString("kind"));assertEquals(0,original.getInt("size"));assertFalse(records.binary.keys.any {it.startsWith(NativeMigrationProvisionalVoice.PREFIX)})
            }
        }
        raw.writeBytes(f.originalBytes());val op=UUID.randomUUID().toString();val frozen=NativeVoiceRawOwnership.freeze(source,op);val inventory=NativeMigrationProvisionalFiles(f.context("source"),source,setOf(pending.id),NativeMigrationOwnerLease(source,op,frozen){true})
        raw.writeBytes(f.originalBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
        assertThrows(Exception::class.java){inventory.unchanged()};frozen.close();raw.writeBytes(f.originalBytes())
        val extra=File(raw.parentFile,UUID.randomUUID().toString()+".wav");extra.writeBytes(byteArrayOf(1))
        assertThrows(Exception::class.java){f.export(source,File(f.folder,"unknown.migration"))}
        assertArrayEquals(byteArrayOf(1),extra.readBytes());check(extra.delete())
        check(raw.delete());java.nio.file.Files.createSymbolicLink(raw.toPath(),File(f.folder,"own-input").also {it.writeBytes(f.originalBytes())}.toPath())
        assertThrows(Exception::class.java){f.export(source,File(f.folder,"linked.migration"))}
        assertTrue(java.nio.file.Files.isSymbolicLink(raw.toPath()));assertEquals(before,MigrationActivationFixtures.tree(source))
    }
    @Test fun writeOriginalCandidateForAnotherPid()=runBlocking {
        val f=MigrationProvisionalFixture();f.fresh();val pending=f.seed();val source=f.storage("source");val before=MigrationActivationFixtures.tree(source)
        f.export(source)
        val destination=f.storage("destination");val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        f.publish("candidate",f.header(receipt).put("id",pending.id).put("original",source.read("voice-recording:${pending.id}")).put("sourceTree",JSONObject(before)).put("destinationTree",JSONObject(MigrationActivationFixtures.tree(destination))))
    }
    @Test fun activateOriginalCandidateInAnotherPidWithoutRestaging()=runBlocking {
        val f=MigrationProvisionalFixture();val v=f.proof("candidate");assertEquals(f.run,v.getString("run"));assertNotEquals(Process.myPid(),v.getInt("pid"))
        val destination=f.storage("destination");assertEquals(f.hashes(v.getJSONObject("destinationTree")),MigrationActivationFixtures.tree(destination))
        val generation=NativeMigrationActivation(f.context("destination"),destination).activate(f.receipt(v));val current=f.inspect(v)
        val meta=current.metadata()!!;meta.getJSONObject("drafts").put(JSONObject(v.getString("original")).getString("draftKey"),"cold later draft");current.write("metadata",meta.toString())
        f.publish("activation",f.header(f.receipt(v)).put("writerPid",v.getInt("pid")).put("generation",generation).put("tree",JSONObject(MigrationActivationFixtures.tree(current))).put("candidateProofSha256",NativeMigrationProvisionalVoice.sha(File(f.folder,"candidate.json").readBytes())))
    }
    @Test fun readExactOriginalAndIdempotentReceiptInThirdPidWithoutOldRecorder()=runBlocking {
        val f=MigrationProvisionalFixture();val v=f.proof("candidate");val a=f.proof("activation");assertNotEquals(Process.myPid(),v.getInt("pid"));assertNotEquals(Process.myPid(),a.getInt("pid"));assertEquals(v.getInt("pid"),a.getInt("writerPid"))
        assertEquals(a.getString("candidateProofSha256"),NativeMigrationProvisionalVoice.sha(File(f.folder,"candidate.json").readBytes()))
        val current=f.storage("destination");val before=MigrationActivationFixtures.tree(current);assertEquals(f.hashes(a.getJSONObject("tree")),before)
        f.inspect(v);assertEquals(a.getString("generation"),NativeMigrationActivation(f.context("destination"),current).activate(f.receipt(v)))
        assertEquals("cold later draft",current.metadata()!!.getJSONObject("drafts").getString(JSONObject(v.getString("original")).getString("draftKey")));assertEquals(before,MigrationActivationFixtures.tree(current))
        f.publish("cold-complete",f.header(f.receipt(v)).put("writerPid",v.getInt("pid")).put("activationPid",a.getInt("pid")).put("noOldRecorderOrOperation",true).put("sourceUnchanged",true))
    }
}
