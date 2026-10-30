package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Actual Keystore/FD/closed-node component selectors. No Activity, provider read or OS request. */
class NativeMigrationSelectionReaderTest {
    private class Fixture {
        val f=MigrationInboxFixture().also {it.fresh()}
        private val files=File(f.folder,"owned-source-files").also {check(it.mkdir());android.system.Os.chmod(it.path,0x1c0)}
        val context=object:ContextWrapper(f.context("source")){override fun getApplicationContext(): Context=this;override fun getFilesDir()=files}
        val root=f.storage("source")
        fun alias(kind: NativeMigrationSelectionJournal.Kind,node: NativeMigrationSelectionNode,role: String="source"): String {
            fun child(parent: String,suffix: String)="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((parent+"\u0000"+suffix).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
            val top=child(f.alias(role),kind.namespace)
            return if(node==NativeMigrationSelectionNode.JOURNAL)top else child(top,node.suffix.removePrefix("-"))
        }
        fun journal(kind: NativeMigrationSelectionJournal.Kind)=root.child(context,kind.namespace)
        fun index(requests: List<JSONObject>)=JSONObject().put("version",2).put("revision",23L).put("records",JSONArray(requests)).toString()
        fun request(id: String,phase: String,count: Int,maximum: Int)=JSONObject().put("id",id).put("key","original-source-target").put("generation",InboxContractFixture.id("reader-generation"))
            .put("selection",8).put("requestId",InboxContractFixture.id("request-$id")).put("errorGeneration",Long.MIN_VALUE).put("phase",phase)
            .put("sources",JSONArray(List(count){"content://owned.invalid/$id/$it"})).put("releaseReadGrants",JSONArray()).put("maximum",maximum)
        fun seedDocument(): InboxContractFixture.Vector {
            val kind=NativeMigrationSelectionJournal.Kind.DOCUMENT;val journal=journal(kind);val payloads=journal.child(context,"payloads")
            val original=InboxContractFixture.all();val index=JSONObject(original.text.getValue(NativeMigrationInboxGraph.INDEX));val requests=mutableListOf<JSONObject>()
            for(batch in index.getJSONArray("records").objects()) {
                val phase=when(batch.getString("phase")){"receiving"->"selected";"pending"->"ready";else->batch.getString("phase")}
                val request=request(batch.getString("id"),phase,batch.getJSONArray("slots").length(),batch.getInt("maximum"));requests+=request
                batch.put("binding",JSONObject().put("key",request.getString("key")).put("generation",request.getString("generation")))
            }
            val raw="\n "+this.index(requests)+" \n";journal.write(NativeMigrationSelectionJournal.RECORD,raw)
            val vector=InboxContractFixture.Vector(original.text+mapOf(NativeMigrationInboxGraph.INDEX to index.toString()),original.binary,original.bytes)
            vector.text.forEach {payloads.write(it.key,it.value)};vector.binary.forEach {(name,blob)->payloads.writeBinaryStream(name,blob.size,blob::verifiedCopy)}
            return vector
        }
        fun tree()=requireNotNull(root.coordinationKey.let {File(it).parentFile}).listFiles()!!.filter {it.name.startsWith("installation")}.flatMap {base->
            if(base.isDirectory)base.walkTopDown().filter {it.isFile}.toList()else listOf(base)
        }.associate {it.relativeTo(File(root.coordinationKey).parentFile).path to NativeMigrationInbox.sha(it.readBytes())}
    }
    @Test fun absentAndEmptyFixedNodesNeverCreateNamespaceOrAnyChildKey() {
        val f=Fixture();val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        for(kind in NativeMigrationSelectionJournal.Kind.entries) {
            val expected=f.root.migrationSelectionKeys(kind);expected.forEach {assertFalse(File(it).exists())}
            f.root.migrationSelectionSnapshot(f.context,kind){view->assertFalse(view.journal.present);assertFalse(view.payloads.present);assertNull(view.journal.records);view.raw?.let {assertFalse(it.present)}}
            expected.forEach {assertFalse(File(it).exists())}
            for(node in NativeMigrationSelectionNode.entries)if(node!=NativeMigrationSelectionNode.OUTPUTS||kind==NativeMigrationSelectionJournal.Kind.CAMERA)assertFalse(keys.containsAlias(f.alias(kind,node)))
            expected.forEach {check(File(it).mkdir());android.system.Os.chmod(it,0x1c0)}
            f.root.migrationSelectionSnapshot(f.context,kind){view->assertTrue(view.journal.present);assertTrue(view.payloads.present);assertNull(view.journal.records);assertNull(view.payloads.records)}
            for(node in NativeMigrationSelectionNode.entries)if(node!=NativeMigrationSelectionNode.OUTPUTS||kind==NativeMigrationSelectionJournal.Kind.CAMERA)assertFalse(keys.containsAlias(f.alias(kind,node)))
        }
        assertFalse(keys.containsAlias(f.f.alias("source")))
    }
    @Test fun exactJournalAndCommonPayloadBytesStayAuthenticatedThroughConsumerAndNoOrdinaryReaderIsUsed() {
        val f=Fixture();val vector=f.seedDocument();val before=f.tree();val journal=f.journal(NativeMigrationSelectionJournal.Kind.DOCUMENT)
        val raw=journal.read(NativeMigrationSelectionJournal.RECORD)
        f.root.migrationSelectionSnapshot(f.context,NativeMigrationSelectionJournal.Kind.DOCUMENT){view->
            assertEquals(raw,view.journal.records!!.text.getValue(NativeMigrationSelectionJournal.RECORD))
            val payload=view.payloads.records!!;assertEquals(vector.text,payload.text)
            for((name,blob)in payload.binary){val bytes=java.io.ByteArrayOutputStream();blob.verifiedCopy(bytes);assertArrayEquals(vector.bytes.getValue(name),bytes.toByteArray())}
            view.unchanged()
        }
        assertEquals(before,f.tree())
        val field=NativeSessionStorage.ExistingReader::class.java.getDeclaredField("source").apply {isAccessible=true}
        NativeSessionStorage.ExistingReader.open(f.context,File(journal.coordinationKey).name,f.alias(NativeMigrationSelectionJournal.Kind.DOCUMENT,NativeMigrationSelectionNode.JOURNAL)).use {reader->
            val source=field.get(reader) as NativeSessionStorage
            assertThrows(IllegalStateException::class.java){source.child(f.context,"payloads")}
            assertThrows(IllegalArgumentException::class.java){reader.selectionSnapshot {_,_->Unit}}
        }
    }
    @Test fun missingNestedKeyUnknownRecordLinksAndLateNamespaceSidecarRejectWithoutRecoveryOrOmission() {
        val f=Fixture();f.seedDocument();val before=f.tree();val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val kind=NativeMigrationSelectionJournal.Kind.DOCUMENT;val names=f.root.migrationSelectionKeys(kind)
        for(suffix in listOf(".bak",".new","-future")) {
            val extra=File(names[1]+suffix)
            assertTrue(runCatching {f.root.migrationSelectionSnapshot(f.context,kind){
                if(suffix.startsWith("-")){check(extra.mkdir());android.system.Os.chmod(extra.path,0x1c0)}else{extra.writeText("owned retained sidecar");android.system.Os.chmod(extra.path,0x180)}
            }}.isFailure)
            assertTrue(extra.exists());assertEquals(before,f.tree().filterKeys {it!=extra.name});check(extra.delete())
        }
        val unknown=File(names[1],"unknown-original");unknown.writeText("retained")
        assertTrue(runCatching {f.root.migrationSelectionSnapshot(f.context,kind){Unit}}.isFailure);assertEquals("retained",unknown.readText());check(unknown.delete())
        val index=File(names[0],NativeMigrationInbox.sha(NativeMigrationSelectionJournal.RECORD.toByteArray())+".bin");val held=File(f.f.folder,"owned-held-index")
        check(index.renameTo(held));android.system.Os.symlink(held.path,index.path)
        assertTrue(runCatching {f.root.migrationSelectionSnapshot(f.context,kind){Unit}}.isFailure);assertTrue(java.nio.file.Files.isSymbolicLink(index.toPath()));check(index.delete());check(held.renameTo(index))
        val payloadAlias=f.alias(kind,NativeMigrationSelectionNode.PAYLOADS);assertTrue(keys.containsAlias(payloadAlias))
        // A separate owned namespace has ciphertext but no matching key; no fallback key is generated.
        val other=f.f.storage("destination");val target=File(other.migrationSelectionKeys(kind)[1]);check(target.mkdir());android.system.Os.chmod(target.path,0x1c0)
        val top=File(other.migrationSelectionKeys(kind)[0]);check(top.mkdir());android.system.Os.chmod(top.path,0x1c0)
        File(names[1]).listFiles()!!.forEach {file->val copied=file.copyTo(File(target,file.name));android.system.Os.chmod(copied.path,0x180)}
        val otherContext=object:ContextWrapper(f.f.context("destination")){override fun getApplicationContext(): Context=this;override fun getFilesDir()=f.context.filesDir}
        val missingAlias=f.alias(kind,NativeMigrationSelectionNode.PAYLOADS,"destination");assertFalse(keys.containsAlias(missingAlias))
        val failure=runCatching {other.migrationSelectionSnapshot(otherContext,kind){Unit}}.exceptionOrNull();assertTrue(failure?.message.orEmpty().contains("Existing key unavailable"))
        assertFalse(keys.containsAlias(missingAlias))
        assertEquals(before,f.tree())
    }
    @Test fun cameraClaimsWithoutRawAreAuditOnlyButRawAndForeignOrphanScopesAlwaysRefuseAndRemain() {
        val f=Fixture();val kind=NativeMigrationSelectionJournal.Kind.CAMERA;val journal=f.journal(kind);journal.child(f.context,"payloads")
        val outputs=journal.child(f.context,"outputs");val id=InboxContractFixture.id("camera-reader-claim")
        journal.write(NativeMigrationSelectionJournal.RECORD,f.index(listOf(f.request(id,"consumed",1,5))))
        outputs.write("output-v1-$id",JSONObject().put("version",1).put("id",id).toString());val before=f.tree()
        f.root.migrationSelectionSnapshot(f.context,kind){view->assertNotNull(view.outputs!!.records);assertFalse(view.raw!!.present)}
        val parent=File(f.context.filesDir,"camera-output-v1");check(parent.mkdir());android.system.Os.chmod(parent.path,0x1c0)
        val scope=File(parent,NativeMigrationSelectionRawInventory.scope(outputs.coordinationKey));check(scope.mkdir());android.system.Os.chmod(scope.path,0x1c0)
        f.root.migrationSelectionSnapshot(f.context,kind){view->assertTrue(view.raw!!.present)}
        val raw=File(scope,"photo-$id.jpg");raw.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),7));android.system.Os.chmod(raw.path,0x180)
        val failure=runCatching {f.root.migrationSelectionSnapshot(f.context,kind){Unit}}.exceptionOrNull();assertTrue(failure is NativeMigrationExport.IncompleteScope);assertArrayEquals(byteArrayOf(0xff.toByte(),0xd8.toByte(),7),raw.readBytes());check(raw.delete())
        val other=File(parent,"b".repeat(32));check(other.mkdir());android.system.Os.chmod(other.path,0x1c0);val orphan=File(other,"photo-${UUID.randomUUID()}.jpg");orphan.writeBytes(byteArrayOf(3));android.system.Os.chmod(orphan.path,0x180)
        assertTrue(runCatching {f.root.migrationSelectionSnapshot(f.context,kind){Unit}}.exceptionOrNull() is NativeMigrationExport.IncompleteScope);assertTrue(orphan.exists());assertEquals(before,f.tree())
    }
}
