package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Fresh private original/candidate pair. Not a default namespace, actual user file or remote operation. */
internal class MigrationInboxFixture {
    val app=InstrumentationRegistry.getInstrumentation().targetContext
    val run=checkNotNull(InstrumentationRegistry.getArguments().getString("migrationInboxRun")).also {require(UUID.fromString(it).toString()==it)}
    val folder=File(app.filesDir,"acceptance/P7-inbox-$run")
    val password="owned inert inbox streaming migration"
    init {NativeChatNotificationNaturalStore.guard(app)}
    fun fresh(){require(!folder.exists());check(folder.mkdirs());android.system.Os.chmod(folder.path,0x1c0);for(role in listOf("source","destination")){val f=File(folder,role);check(f.mkdir());android.system.Os.chmod(f.path,0x1c0)}}
    fun context(role: String): Context {
        require(role in setOf("source","destination"));val path=File(folder,role)
        require(path.isDirectory&&!java.nio.file.Files.isSymbolicLink(path.toPath()))
        return object: ContextWrapper(app){override fun getNoBackupFilesDir()=path;override fun getApplicationContext(): Context=this}
    }
    fun alias(role: String)="p7-inbox-$role-$run"
    fun childAlias(role: String)="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((alias(role)+"\u0000external-inbox").toByteArray()).take(24).joinToString(""){"%02x".format(it)}
    fun storage(role: String)=NativeSessionStorage(context(role),"installation",alias(role))
    fun seedRoot(){val root=storage("source");val rows=MigrationActivationFixtures.payload().getJSONObject("records");rows.keys().forEach {root.write(it,rows.getString(it))}}
    suspend fun seedChild(root: NativeSessionStorage=storage("source"),vector: InboxContractFixture.Vector=InboxContractFixture.all()) {
        val child=root.child(context("source"),"external-inbox")
        NativeInboxOwnership.writing(child){withContext(Dispatchers.IO){
            vector.text.forEach {child.write(it.key,it.value)}
            vector.binary.forEach {(name,blob)->child.writeBinaryStream(name,blob.size,blob::verifiedCopy)}
        }}
    }
    fun pngAndInline(): InboxContractFixture.Vector {
        val base=InboxContractFixture.all();val text=base.text.toMutableMap();val binary=base.binary.toMutableMap();val bytes=base.bytes.toMutableMap()
        val id=InboxContractFixture.id("pending-file-0");val original=JSONObject(text.getValue("draft-file:$id"));val payload=NativeDraftFiles.record(original).payload!!
        text["draft-file:$id"]=JSONObject().put("file",original.getJSONObject("file")).put("bytes",java.util.Base64.getEncoder().encodeToString(bytes.getValue(payload))).toString()
        binary.remove(payload);bytes.remove(payload)
        val thumbnail="draft-thumbnail-v1:${InboxContractFixture.id("consumed-file-0")}"
        val bitmap=android.graphics.Bitmap.createBitmap(2,3,android.graphics.Bitmap.Config.ARGB_8888)
        val png=try {java.io.ByteArrayOutputStream().also {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}finally{bitmap.recycle()}
        text[thumbnail]=JSONObject(text.getValue(thumbnail)).put("png",java.util.Base64.getEncoder().encodeToString(png)).put("thumbnailSha256",NativeMigrationInbox.sha(png)).toString()
        return InboxContractFixture.Vector(text,binary,bytes)
    }
    /** Synthetic old local tag1 with this exact private existing key; never obtains encoded key bytes. */
    fun legacyText(root: NativeSessionStorage,name: String,raw: String) {
        val child=root.child(context("source"),"external-inbox")
        val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(childAlias("source"),null) as javax.crypto.SecretKey
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.ENCRYPT_MODE,key);updateAAD(name.toByteArray(Charsets.UTF_8))}
        val file=File(child.coordinationKey,NativeMigrationInbox.sha(name.toByteArray(Charsets.UTF_8))+".bin")
        file.writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(raw.toByteArray(Charsets.UTF_8)));android.system.Os.chmod(file.path,0x180)
    }
    fun envelope(name: String="original")=File(folder,"$name.migration")
    fun childTree(root: NativeSessionStorage=storage("source")): Map<String,String> {
        val directory=File(root.migrationInboxKey())
        if(!directory.exists())return emptyMap()
        return requireNotNull(directory.listFiles()).associate {file->
            require(file.isFile&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val probe=NativeMigrationProbe();file.inputStream().use {it.copyTo(probe)};file.name to probe.sha256()
        }
    }
    /** Lower-level stream fixture owns actual kernel leases. Actual Activity owner is tested separately. */
    suspend fun export(root: NativeSessionStorage=storage("source"),role: String="source",output: File=envelope(),beforePublication: ()->Unit={}) {
        val operation=UUID.randomUUID().toString();var raw: NativeVoiceRawGate.Frozen?=null
        var owner: NativeExternalAttachments?=null;var inbox: NativeInboxOwnership.Lease?=null
        try {
            withContext(Dispatchers.IO){raw=NativeVoiceRawOwnership.freeze(root,operation)}
            val present=withContext(Dispatchers.IO){root.migrationInboxSnapshot(context(role)){it.present}}
            if(present) {
                val child=withContext(Dispatchers.IO){root.child(context(role),"external-inbox")}
                withContext(Dispatchers.Main.immediate){owner=NativeExternalAttachments(context(role),child,{true},true);owner!!.sealMigrationInputs(operation)}
                inbox=withContext(Dispatchers.Main.immediate){owner!!.freezeForMigration(root,operation)}
            }
            val lease=NativeMigrationOwnerLease(root,operation,raw,inbox){true}
            withContext(Dispatchers.IO){NativeMigrationExport(context(role),root).sealFile(output,password,lease,beforePublication)}
        }finally {
            withContext(NonCancellable+Dispatchers.IO){try{inbox?.close()}finally{raw?.close()}}
            withContext(NonCancellable+Dispatchers.Main.immediate){owner?.let {it.resumeMigrationInputs(operation);it.retireForMigration()}}
        }
    }
    fun publish(name: String,value: JSONObject) {
        val f=File(folder,"$name.json");check(f.createNewFile());f.writeText(value.toString());java.io.FileOutputStream(f,true).use {it.fd.sync()}
    }
    fun proof(name: String)=StrictMigrationJson.parse(File(folder,"$name.json").readBytes())
    fun receipt(value: JSONObject)=NativeMigrationCandidateStore.Receipt(value.getString("candidateId"),value.getString("sha256"),value.getInt("records"))
    fun header(receipt: NativeMigrationCandidateStore.Receipt)=JSONObject().put("run",run).put("pid",Process.myPid()).put("candidateId",receipt.id).put("sha256",receipt.sha256).put("records",receipt.recordCount)
    fun hashes(value: JSONObject)=value.keys().asSequence().associateWith {value.getString(it)}
    fun inspect(root: NativeSessionStorage=storage("destination"),vector: InboxContractFixture.Vector=InboxContractFixture.all()) {
        val original=NativeMigrationInbox.read(root).single()
        original.entries.forEachIndexed {n,entry->
            if(entry.kind=="text")assertEquals(vector.text.getValue(entry.name),NativeMigrationInbox.text(root,original,n))
            else if(entry.size>1024*1024){val probe=NativeMigrationProbe();NativeMigrationInbox.copy(root,original,n,probe);assertEquals(entry.size.toLong(),probe.size);assertEquals(vector.binary.getValue(entry.name).sha256,probe.sha256())}
            else {val bytes=java.io.ByteArrayOutputStream();NativeMigrationInbox.copy(root,original,n,bytes);assertArrayEquals(vector.bytes.getValue(entry.name),bytes.toByteArray())}
        }
        assertFalse(java.nio.file.Files.exists(File(root.migrationInboxKey()).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }
    fun laterDraft(root: NativeSessionStorage,text: String) {
        val metadata=root.metadata()!!;val key=MigrationActivationFixtures.client(MigrationActivationFixtures.payload()).cacheKey("synthetic-thread")
        metadata.getJSONObject("drafts").put(key,text);root.write("metadata",metadata.toString())
    }
    fun sourceUnchanged(value: JSONObject){assertEquals(hashes(value.getJSONObject("sourceChild")),childTree())}
}

class NativeMigrationInboxStorageTest {
    @Test fun exactStreamCandidateActivationAndReexportKeepAllFourPhasesInertAndLaterDraft()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();f.seedRoot();val vector=f.pngAndInline();val source=f.storage("source");f.seedChild(source,vector)
        for(name in listOf(NativeMigrationInboxGraph.INDEX,"draft-file:${InboxContractFixture.id("pending-file-0")}"))f.legacyText(source,name,vector.text.getValue(name))
        val before=f.childTree(source)
        f.export(source);assertEquals(before,f.childTree(source))
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password);val candidate=MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates"))
        assertEquals(receipt,candidates.stageFile(receipt.id,f.envelope(),f.password));assertEquals(candidate,MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates")))
        val generation=NativeMigrationActivation(f.context("destination"),destination).activate(receipt);val current=f.storage("destination");f.inspect(current,vector)
        f.laterDraft(current,"later inbox-independent draft");val tree=MigrationActivationFixtures.tree(current)
        assertEquals(generation,NativeMigrationActivation(f.context("destination"),current).activate(receipt));assertEquals(tree,MigrationActivationFixtures.tree(current))
        f.export(current,"destination",f.envelope("reexport"));f.inspect(current,vector);assertEquals(before,f.childTree(source))
        assertEquals(tree,MigrationActivationFixtures.tree(current))
    }
    @Test fun fullTwentyMiBBinaryUsesStreamCopiesAndRetainsSourceCiphertext()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();f.seedRoot();val base=InboxContractFixture.all()
        val index=JSONObject(base.text.getValue(NativeMigrationInboxGraph.INDEX));val row=index.getJSONArray("records").getJSONObject(2).getJSONArray("slots").getJSONObject(0)
        val fileId=row.getString("id");val metadata=JSONObject(base.text.getValue("draft-file:$fileId"));val old=NativeDraftFiles.record(metadata);val payload=old.payload!!
        val bytes=ByteArray(NativeDraftFiles.MAXIMUM_BYTES){(it%251).toByte()};val file=old.file.copy(sizeBytes=bytes.size.toLong(),sha256=NativeMigrationInbox.sha(bytes))
        row.put("file",file.body());metadata.put("file",file.body())
        val vector=InboxContractFixture.Vector(base.text+mapOf(NativeMigrationInboxGraph.INDEX to index.toString(),"draft-file:$fileId" to metadata.toString()),base.binary+mapOf(payload to InboxContractFixture.blob(bytes)),base.bytes+mapOf(payload to bytes))
        f.seedChild(vector=vector);val before=f.childTree();f.export()
        val destination=f.storage("destination");val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        NativeMigrationActivation(f.context("destination"),destination).activate(receipt);f.inspect(vector=vector);assertEquals(before,f.childTree())
    }
    @Test fun unknownCatalogCorruptEnvelopeAndInterruptedPublicationKeepOriginalCandidate()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();f.seedRoot();f.seedChild();val before=f.childTree();f.export()
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password);val tree=MigrationActivationFixtures.tree(destination)
        val damaged=f.envelope("damaged");val bytes=f.envelope().readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();damaged.writeBytes(bytes)
        assertThrows(Exception::class.java){candidates.stageFile(UUID.randomUUID().toString(),damaged,f.password,receipt)}
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            val text=NativeMigrationTextMap.copy(records.text);text[NativeMigrationInbox.RECORD]=JSONObject(text.getValue(NativeMigrationInbox.RECORD)).put("version",2).toString()
            assertThrows(Exception::class.java){candidates.validateStreamRecords(records.copy(text=text))}
        }
        assertThrows(Exception::class.java){NativeMigrationActivation(f.context("destination"),destination).activate(receipt){if(it==0)error("Owned pre-publication interruption")}}
        assertEquals(tree,MigrationActivationFixtures.tree(destination));assertEquals(receipt,candidates.currentReceipt());assertEquals(before,f.childTree())
    }
    @Test fun absentEmptyMissingKeySidecarsLinksAndUnknownChildAreNeverCreatedRecoveredOrOmitted()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();val root=f.storage("source");val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val folder=File(root.migrationInboxKey());assertFalse(folder.exists());assertFalse(keys.containsAlias(f.alias("source")));assertFalse(keys.containsAlias(f.childAlias("source")))
        root.migrationInboxSnapshot(f.context("source")){assertFalse(it.present);assertNull(it.records)}
        assertFalse(folder.exists());assertFalse(keys.containsAlias(f.alias("source")));assertFalse(keys.containsAlias(f.childAlias("source")))
        check(folder.mkdir());android.system.Os.chmod(folder.path,0x1c0)
        root.migrationInboxSnapshot(f.context("source")){assertTrue(it.present);assertNull(it.records)};assertFalse(keys.containsAlias(f.childAlias("source")))
        f.seedRoot();f.seedChild(root);val before=f.childTree(root)
        val destination=f.storage("destination");val missing=File(destination.migrationInboxKey());check(missing.mkdir());android.system.Os.chmod(missing.path,0x1c0)
        requireNotNull(folder.listFiles()).forEach {file->val copied=file.copyTo(File(missing,file.name));android.system.Os.chmod(copied.path,0x180)}
        assertFalse(keys.containsAlias(f.childAlias("destination")));val missingBefore=f.childTree(destination)
        val keyFailure=assertThrows(Exception::class.java){destination.migrationInboxSnapshot(f.context("destination")){Unit}}
        assertTrue(keyFailure.message.orEmpty().contains("Existing key unavailable"))
        assertFalse(keys.containsAlias(f.childAlias("destination")));assertEquals(missingBefore,f.childTree(destination))
        val index=File(folder,NativeMigrationInbox.sha(NativeMigrationInboxGraph.INDEX.toByteArray())+".bin")
        for(suffix in listOf(".bak",".new")) {
            val side=File(index.path+suffix);index.copyTo(side)
            assertThrows(Exception::class.java){root.migrationInboxSnapshot(f.context("source")){Unit}}
            assertTrue(side.exists());assertArrayEquals(index.readBytes(),side.readBytes());check(side.delete())
        }
        val held=File(f.folder,"held-owned-index.bin");check(index.renameTo(held))
        try {
            android.system.Os.symlink(held.path,index.path)
            assertThrows(Exception::class.java){root.migrationInboxSnapshot(f.context("source")){Unit}}
            assertTrue(java.nio.file.Files.isSymbolicLink(index.toPath()));check(index.delete())
            android.system.Os.link(held.path,index.path)
            assertThrows(Exception::class.java){root.migrationInboxSnapshot(f.context("source")){Unit}}
            assertEquals(2L,android.system.Os.lstat(held.path).st_nlink);check(index.delete())
        }finally {if(java.nio.file.Files.exists(index.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))check(index.delete());check(held.renameTo(index))}
        val unknown=File(folder,"unrecognized-original").also {it.writeText("retained")}
        assertThrows(Exception::class.java){root.migrationInboxSnapshot(f.context("source")){Unit}};assertEquals("retained",unknown.readText());check(unknown.delete())
        val nested=File(folder.parentFile,folder.name+"-future");check(nested.mkdir())
        assertThrows(Exception::class.java){root.migrationInboxSnapshot(f.context("source")){Unit}};assertTrue(nested.isDirectory);check(nested.delete())
        assertEquals(before,f.childTree(root))
    }
    @Test fun siblingSidecarOrChildCreatedAfterStreamWriteRejectsPublicationAndRetainsOriginals()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();f.seedRoot();val source=f.storage("source");f.seedChild(source);f.export(source)
        val beforeChild=f.childTree(source);val beforeRoot=MigrationActivationFixtures.tree(source)
        val destination=f.storage("destination");val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        val beforeDestination=MigrationActivationFixtures.tree(destination)
        val child=File(source.migrationInboxKey());val preserved="previous published original".toByteArray()
        for(suffix in listOf(".bak",".new","-late-child")) {
            val sibling=File(child.path+suffix);assertFalse(sibling.exists())
            val output=f.envelope("publication-${suffix.removePrefix(".")}");output.writeBytes(preserved)
            var reached=false
            val failure=runCatching {f.export(source,output=output,beforePublication={
                reached=true
                if(suffix.startsWith("-")){check(sibling.mkdir());android.system.Os.chmod(sibling.path,0x1c0)}
                else {check(sibling.createNewFile());sibling.writeBytes(preserved);android.system.Os.chmod(sibling.path,0x180)}
            })}.exceptionOrNull()
            assertTrue(reached);assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("Unsupported inbox child or sidecar retained"))
            assertTrue(sibling.exists());if(sibling.isFile)assertArrayEquals(preserved,sibling.readBytes())
            assertArrayEquals(preserved,output.readBytes());assertEquals(beforeChild,f.childTree(source));assertEquals(beforeRoot,MigrationActivationFixtures.tree(source))
            assertEquals(beforeDestination,MigrationActivationFixtures.tree(destination));assertEquals(receipt,candidates.currentReceipt())
            // Only remove the test's injection after proving the production failure retained it.
            check(sibling.delete())
        }
    }
    @Test fun writeOriginalInboxCandidateForAnotherPid()=runBlocking {
        val f=MigrationInboxFixture();f.fresh();f.seedRoot();f.seedChild();val before=f.childTree();f.export()
        val destination=f.storage("destination");val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),f.envelope(),f.password)
        f.publish("candidate",f.header(receipt).put("sourceChild",JSONObject(before)).put("candidateTree",JSONObject(MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates")))))
    }
    @Test fun activateOriginalInboxCandidateInSecondPidWithoutRestaging()=runBlocking {
        val f=MigrationInboxFixture();val v=f.proof("candidate");assertEquals(f.run,v.getString("run"));assertNotEquals(Process.myPid(),v.getInt("pid"));f.sourceUnchanged(v)
        val destination=f.storage("destination");assertEquals(f.hashes(v.getJSONObject("candidateTree")),MigrationActivationFixtures.tree(destination.child(f.context("destination"),"migration-candidates")))
        val generation=NativeMigrationActivation(f.context("destination"),destination).activate(f.receipt(v));val current=f.storage("destination");f.inspect(current);f.laterDraft(current,"cold later inbox draft")
        f.publish("activation",f.header(f.receipt(v)).put("writerPid",v.getInt("pid")).put("generation",generation).put("tree",JSONObject(MigrationActivationFixtures.tree(current))).put("candidateProofSha256",NativeMigrationInbox.sha(File(f.folder,"candidate.json").readBytes())))
    }
    @Test fun thirdPidReadsAndCopiesOriginalInboxWithoutCleanupAndRepeatsExactReceipt()=runBlocking {
        val f=MigrationInboxFixture();val v=f.proof("candidate");val a=f.proof("activation")
        assertNotEquals(Process.myPid(),v.getInt("pid"));assertNotEquals(Process.myPid(),a.getInt("pid"));assertEquals(v.getInt("pid"),a.getInt("writerPid"))
        assertEquals(a.getString("candidateProofSha256"),NativeMigrationInbox.sha(File(f.folder,"candidate.json").readBytes()));val current=f.storage("destination");val before=MigrationActivationFixtures.tree(current)
        assertEquals(f.hashes(a.getJSONObject("tree")),before);f.inspect(current);f.sourceUnchanged(v)
        assertEquals(a.getString("generation"),NativeMigrationActivation(f.context("destination"),current).activate(f.receipt(v)));assertEquals(before,MigrationActivationFixtures.tree(current))
        f.export(current,"destination",f.envelope("cold-reexport"));f.inspect(current)
        f.publish("cold-complete",f.header(f.receipt(v)).put("writerPid",v.getInt("pid")).put("activationPid",a.getInt("pid")).put("noActiveInboxOrOriginalUri",true).put("sourceUnchanged",true))
    }
}
