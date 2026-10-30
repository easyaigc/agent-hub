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
