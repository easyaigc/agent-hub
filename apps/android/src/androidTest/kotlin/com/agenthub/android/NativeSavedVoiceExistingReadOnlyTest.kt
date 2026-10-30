package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions

/** Opt-in, one-use observation. Only its own private intent/completion are written. */
class NativeSavedVoiceExistingReadOnlyTest {
    @Test fun latePendingFilesCannotRecoverDuringOptionalReadReopenOrConstruction()=runBlocking {withContext(Dispatchers.IO){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        NativeChatNotificationNaturalStore.guard(context);require(android.os.Build.VERSION.SDK_INT==26)
        val run=NativeSavedVoiceExistingPlan.uuid(checkNotNull(InstrumentationRegistry.getArguments().getString("savedVoiceExistingNoRecoveryRun")))
        val alias="agent-hub-mobile-v1"
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val beforeKeys=keys.aliases().asSequence().toSet();require(keys.getKey(alias,null) is javax.crypto.SecretKey)
        val before=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(before.complete)
        val root=File(context.cacheDir,"P7-no-recovery-"+run);require(Files.notExists(root.toPath(),NOFOLLOW_LINKS))
        fun directory(f: File)=Files.createDirectory(f.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        directory(root);val namespace=File(root,"original");directory(namespace)
        val local=object: android.content.ContextWrapper(context){override fun getNoBackupFilesDir()=root}
        val created=mutableListOf<File>()
        fun filename(name: String)=voiceSHA(name.toByteArray(Charsets.UTF_8))+".bin"
        try {
            NativeSessionStorage.ExistingReader.open(local,"original",alias).use {reader->
                org.junit.Assert.assertNull(reader.optionalText("metadata",1024))
                reader.reopen(local).use {org.junit.Assert.assertNull(it.optionalText("metadata",1024))}
                val pending=listOf("migration-active-generation-v1" to "","migration-active-generation-v1" to ".bak",
                    "migration-active-generation-v1" to ".new","metadata" to ".bak","metadata" to ".new","metadata" to "")
                for((name,suffix)in pending) {
                    val f=File(namespace,filename(name)+suffix);val raw=ByteArray(40){(it+3).toByte()}
                    Files.write(f.toPath(),raw,CREATE_NEW,WRITE);Files.setPosixFilePermissions(f.toPath(),PosixFilePermissions.fromString("rw-------"));created.add(f)
                    val names=namespace.list()!!.toSet()
                    org.junit.Assert.assertThrows(Exception::class.java){reader.optionalText("metadata",1024)}
                    if(name=="migration-active-generation-v1") {
                        org.junit.Assert.assertThrows(Exception::class.java){reader.reopen(local)}
                        org.junit.Assert.assertThrows(Exception::class.java){NativeSessionStorage(local,"original",alias,true,true,true)}
                    }
                    org.junit.Assert.assertEquals(names,namespace.list()!!.toSet());org.junit.Assert.assertArrayEquals(raw,f.readBytes())
                    Files.delete(f.toPath());created.remove(f)
                    org.junit.Assert.assertNull(reader.optionalText("metadata",1024))
                }
            }
            require(keys.aliases().asSequence().toSet()==beforeKeys)
            val after=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(after.complete&&after.digest==before.digest)
        }finally {
            // Only exact files introduced in this fresh structural fixture; no recursive source cleanup.
            for(f in created)if(Files.isRegularFile(f.toPath(),NOFOLLOW_LINKS)&&f.parentFile==namespace)Files.delete(f.toPath())
            if(namespace.list()!!.isEmpty())Files.delete(namespace.toPath())
            if(root.list()!!.isEmpty())Files.delete(root.toPath())
        }
    }}
    @Test fun missingNamespaceMissingKeyAndSymlinkCannotCreateOrRecoverSource()=runBlocking {withContext(Dispatchers.IO){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        NativeChatNotificationNaturalStore.guard(context);require(android.os.Build.VERSION.SDK_INT==26)
        val run=NativeSavedVoiceExistingPlan.uuid(checkNotNull(InstrumentationRegistry.getArguments().getString("savedVoiceExistingNegativeRun")))
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)};val beforeKeys=keys.aliases().asSequence().toSet()
        val missing="p7-readonly-absent-"+run;require(missing !in beforeKeys)
        val defaultBefore=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(defaultBefore.complete)
        val root=File(context.cacheDir,"P7-existing-negative-"+run);require(Files.notExists(root.toPath(),NOFOLLOW_LINKS))
        Files.createDirectory(root.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val local=object: android.content.ContextWrapper(context){override fun getNoBackupFilesDir()=root}
        val namespace=File(root,"original")
        try {
            org.junit.Assert.assertThrows(Exception::class.java){NativeSessionStorage.ExistingReader.open(local,"original",missing)}
            require(Files.notExists(namespace.toPath(),NOFOLLOW_LINKS))
            Files.createDirectory(namespace.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            org.junit.Assert.assertThrows(Exception::class.java){NativeSessionStorage.ExistingReader.open(local,"original",missing)}
            require(namespace.list()!!.isEmpty());Files.delete(namespace.toPath())
            Files.createSymbolicLink(namespace.toPath(),root.toPath())
            org.junit.Assert.assertThrows(Exception::class.java){NativeSessionStorage.ExistingReader.open(local,"original",missing)}
            require(Files.isSymbolicLink(namespace.toPath()));Files.delete(namespace.toPath())
            require(keys.aliases().asSequence().toSet()==beforeKeys)
            val after=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(after.complete&&after.digest==defaultBefore.digest)
        }finally {
            // Only this fresh empty negative fixture; never follow its link or remove unknown residue.
            if(Files.isSymbolicLink(namespace.toPath()))Files.delete(namespace.toPath())
            else if(namespace.isDirectory&&namespace.list()!!.isEmpty())Files.delete(namespace.toPath())
            if(root.list()!!.isEmpty())Files.delete(root.toPath())
        }
    }}
    @Test fun authenticateExistingDefaultAndRetainedOriginalsWithoutProductWrites()=runBlocking {withContext(Dispatchers.IO){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        NativeChatNotificationNaturalStore.guard(context);require(android.os.Build.VERSION.SDK_INT==26)
        val guard=checkNotNull(NativeSavedVoiceExistingProtection.fromArguments(context)) {"Explicit actual Root plan required"}
        val run=NativeSavedVoiceExistingPlan.uuid(checkNotNull(InstrumentationRegistry.getArguments().getString("savedVoiceExistingObservationRun")))
        require(run !in listOf(guard.plan.lease,guard.plan.postwriteRun,guard.plan.value.getString("currentObservationId"),guard.plan.value.getString("currentBootId")))
        var folder=context.filesDir.canonicalFile
        for(name in listOf("acceptance","P7-existing-source-proof")) {
            val f=File(folder,name)
            if(Files.notExists(f.toPath(),NOFOLLOW_LINKS))Files.createDirectory(f.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(f.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(f.toPath()));folder=f
        }
        val own=File(folder,run);require(Files.notExists(own.toPath(),NOFOLLOW_LINKS)) {"Prior observation exists; reconcile it without replay"}
        Files.createDirectory(own.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        fun publish(name: String,v: JSONObject) {
            val raw=v.toString().toByteArray(Charsets.UTF_8);require(raw.size<=32_768)
            FileChannel.open(File(own,name).toPath(),setOf<java.nio.file.OpenOption>(CREATE_NEW,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use {c->
                val bytes=ByteBuffer.wrap(raw);while(bytes.hasRemaining())check(c.write(bytes)>0);c.force(true)
            };NativeNaturalPreflightEvidence.sync(own)
        }
        publish("intent.json",JSONObject().put("version",1).put("run",run).put("planSha256",guard.plan.sha256).put("sourceWrites",0).put("originalOperationInvoked",false))
        val result=guard.verify();publish("complete.json",result.put("observationRun",run).put("passed",true))
    }}
}
