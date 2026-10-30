package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/** Actual fixed-node FD/Keystore component. No library, OS permission or owner authority is inferred. */
class NativeMigrationPhotoLocalReaderTest {
    private class Fixture {
        val f=MigrationInboxFixture().also {it.fresh()}
        val context=f.context("source")
        val root=f.storage("source")
        fun alias(kind: NativeMigrationPhotoLocalKind)="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((f.alias("source")+"\u0000"+kind.namespace).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
        fun seed(kind: NativeMigrationPhotoLocalKind,text: Map<String,String>): NativeSessionStorage = root.child(context,kind.namespace).also {store->text.forEach {store.write(it.key,it.value)}}
        fun tree(): Map<String,String> = File(root.coordinationKey).parentFile!!.listFiles()!!.filter {it.name.startsWith("installation")}.flatMap {folder->if(folder.isDirectory)folder.walkTopDown().filter {it.isFile}.toList()else listOf(folder)}.associate {it.path to NativeMigrationInertArchive.sha(it.readBytes())}
    }
    @Test fun absentEmptyAndExactHistoricalNodesNeverCreateFallbackKeysOrReinterpretAuthority() {
        val f=Fixture();val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        for(kind in NativeMigrationPhotoLocalKind.values()) {
            val key=f.root.migrationPhotoLocalKey(kind);assertFalse(File(key).exists());assertFalse(keys.containsAlias(f.alias(kind)))
            f.root.migrationPhotoLocalSnapshot(f.context,kind){assertFalse(it.present);assertNull(it.records)}
            assertFalse(File(key).exists());assertFalse(keys.containsAlias(f.alias(kind)))
            check(File(key).mkdir());android.system.Os.chmod(key,0x1c0)
            f.root.migrationPhotoLocalSnapshot(f.context,kind){assertTrue(it.present);assertNull(it.records)}
            assertFalse(keys.containsAlias(f.alias(kind)))
            val text=if(kind==NativeMigrationPhotoLocalKind.RECENTS)mapOf("preferences" to PhotoLocalContractFixture.preferences())else PhotoLocalContractFixture.permissions()
            val store=f.seed(kind,text)
            if(kind==NativeMigrationPhotoLocalKind.PERMISSION) {
                val name="attempt-images";val secret=keys.getKey(f.alias(kind),null) as javax.crypto.SecretKey
                val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.ENCRYPT_MODE,secret);updateAAD(name.toByteArray())}
                val file=File(store.coordinationKey,NativeMigrationInertArchive.sha(name.toByteArray())+".bin")
                file.writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(text.getValue(name).toByteArray()));android.system.Os.chmod(file.path,0x180)
            }
            val before=f.tree();f.root.migrationPhotoLocalSnapshot(f.context,kind){view->assertEquals(text,view.records!!.text);assertTrue(view.records.binary.isEmpty());view.unchanged()};assertEquals(before,f.tree())
        }
        assertFalse(keys.containsAlias(f.f.alias("source")))
    }
    @Test fun defaultReaderGuardUnknownOriginalLateSidecarAndMissingKeyRefuseWithoutRecovery() {
        val f=Fixture();val kind=NativeMigrationPhotoLocalKind.RECENTS;val store=f.seed(kind,mapOf("preferences" to PhotoLocalContractFixture.preferences()))
        val sourceField=NativeSessionStorage.ExistingReader::class.java.getDeclaredField("source").apply {isAccessible=true}
        NativeSessionStorage.ExistingReader.open(f.context,File(store.coordinationKey).name,f.alias(kind)).use {reader->
            assertThrows(IllegalArgumentException::class.java){reader.localPhotoSnapshot {_,_->Unit}}
            assertThrows(IllegalStateException::class.java){(sourceField.get(reader) as NativeSessionStorage).child(f.context,"payloads")}
        }
        store.write("unrecognized-local-original","{}");val unknown=f.tree()
        assertTrue(runCatching {f.root.migrationPhotoLocalSnapshot(f.context,kind){error("Unknown original reached consumer")}}.isFailure);assertEquals(unknown,f.tree());store.remove("unrecognized-local-original")
        for(suffix in listOf(".bak",".new","-future")) {
            val sidecar=File(store.coordinationKey+suffix)
            assertTrue(runCatching {f.root.migrationPhotoLocalSnapshot(f.context,kind){
                sidecar.writeText("owned retained late sidecar");android.system.Os.chmod(sidecar.path,0x180)
            }}.isFailure)
            assertEquals("owned retained late sidecar",sidecar.readText());check(sidecar.delete())
        }
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(f.alias(kind));val before=f.tree()
        assertTrue(runCatching {f.root.migrationPhotoLocalSnapshot(f.context,kind){error("Missing key reached consumer")}}.isFailure)
        assertEquals(before,f.tree());assertFalse(keys.containsAlias(f.alias(kind)))
    }
}
