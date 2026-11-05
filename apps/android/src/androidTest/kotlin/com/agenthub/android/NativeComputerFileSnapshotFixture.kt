package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID

/** Synthetic installation only. No default namespace, provider registration,
 * Main Activity, microphone, external content URI or real computer is configured.
 */
internal class NativeComputerFileSnapshotFixture {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    val run=UUID.randomUUID().toString()
    private val parent=app.cacheDir.canonicalFile
    val folder=File(parent,"f59-snapshot-native-$run")
    val noBackup=File(folder,"no_backup")
    private val cache=File(folder,"cache")
    private val localFiles=File(folder,"files")
    val namespace="installation"
    val alias="f59-snapshot-$run"
    var generation: String?=null;private set
    val childAlias get()=NativeMigrationExistingView.childAlias(alias,NativeComputerFileStore.SCOPE,generation)
    val wrongAlias="$alias-wrong"
    private val plannedAliases=mutableSetOf(alias,childAlias,wrongAlias)
    private var originalAliases: Set<String>?=null
    private var aliasesReserved=false
    private var folderOwned=false
    private val storages=mutableListOf<NativeSessionStorage>()
    private val deny=P8MainDenyPorts()
    private var session: MobileSession?=null
    private var freezeStarted=false
    private var held: NativeMigrationOwnerLease?=null
    lateinit var storage: NativeSessionStorage;private set
    val target=ComputerFileTarget(TargetScope("codex",null,null,"f59-host-$run","f59-workspace-$run","f59-runtime-$run"),null)
    val journalName="computer-files-v1:"+target.key
    val childName get()=namespace+(generation?.let {"-$it"}?:"")+"-"+NativeComputerFileStore.SCOPE
    val childFolder get()=File(noBackup,childName)
    val bytes=ByteArray(257){((it*17+31) and 255).toByte()}
    val id=UUID.randomUUID().toString()
    val binaryName="computer-file-original:$id:0"
    val limits=NativeMigrationComputerFileSnapshot.Limits(NativeMigrationComputerFileGraph.Limits(4,16,4L*1024*1024),8L*1024*1024)
    private val preferences=File(File(app.applicationInfo.dataDir).canonicalFile,"shared_prefs")
    val context: Context=object: ContextWrapper(app) {
        override fun getApplicationContext(): Context=this
        override fun getNoBackupFilesDir(): File=owned(noBackup)
        override fun getCacheDir(): File=owned(cache)
        override fun getFilesDir(): File=owned(localFiles)
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences=error("Fixture forbids SharedPreferences")
        override fun getSystemService(name: String): Any=error("Fixture forbids system service: $name")
        override fun getContentResolver(): android.content.ContentResolver=error("Fixture forbids ContentResolver")
        override fun startService(service: android.content.Intent): android.content.ComponentName=error("Fixture forbids service")
        override fun startForegroundService(service: android.content.Intent): android.content.ComponentName=error("Fixture forbids foreground service")
    }
    private fun owned(value: File): File {
        check(folderOwned&&value.parentFile==folder&&value.absoluteFile==value.canonicalFile)
        val s=Os.lstat(value.path);check(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&(s.st_mode and 0xfff)==0x1c0)
        return value
    }
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun aliases(): Set<String> {
        val result=linkedSetOf<String>();val values=keys().aliases()
        while(values.hasMoreElements())result.add(values.nextElement())
        return result
    }
    fun assertNoDefaultPreferences() {check(Files.notExists(preferences.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {"Default SharedPreferences unexpectedly exists"}}
    fun prepare() {
        check(app.packageName=="com.easyai.agenthub.android.acceptance"&&app.applicationInfo.uid==Process.myUid())
        ComputerFileFdOwners.requireReleased();assertNoDefaultPreferences()
        check(Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&folder.parentFile==parent)
        originalAliases=aliases();check(plannedAliases.none {it in originalAliases!!});aliasesReserved=true
        check(folder.mkdir());folderOwned=true;Os.chmod(folder.path,0x1c0)
        for(dir in listOf(noBackup,cache,localFiles)){check(dir.mkdir());Os.chmod(dir.path,0x1c0)}
        assertSame(context,context.applicationContext)
        storage=ownStorage(namespace,alias)
        storage.write("metadata",JSONObject().put("version",1).put("appearance","system").put("language","zh-Hans")
            .put("demo",false).put("selected","synthetic-no-client").put("clients",JSONArray())
            .put("drafts",JSONObject()).put("selectedThreads",JSONObject()).put("expanded",JSONObject())
            .put("workspaces",JSONObject()).toString())
    }
    private fun ownStorage(name: String,key: String): NativeSessionStorage =NativeSessionStorage(context,name,key).also {storages.add(it)}
    fun prepareGeneration() {
        check(session==null&&generation==null&&Files.notExists(childFolder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val raw=requireNotNull(storage.read("metadata"))
        // Real production local-generation publication, while this synthetic
        // installation has no F59 child. No Activation/export gate is changed.
        generation=storage.publishStreamMigration(UUID.randomUUID().toString(),ComputerFileCodec.hash("synthetic-$run".toByteArray()),
            NativeMigrationStreamRecords(mapOf("metadata" to raw),emptyMap(),4))
        check(!keys().containsAlias(childAlias));plannedAliases.add(childAlias)
        // Reopen the ordinary producer: its existing root key is retained,
        // while a genuinely new F59 child may prepare its own key before freeze.
        // The migration snapshot still uses its separate existing-only decoder.
        check(keys().containsAlias(alias))
        storage=ownStorage(namespace,alias)
    }
    /** Synthetic historical installation only: use the existing production
     * local publication primitive before a Session/freeze exists. This does
     * not enter MigrationActivation or weaken its F59 refusal gate. */
    fun prepareNextFamilyGeneration(): String {
        check(session==null&&!freezeStarted&&held==null)
        val raw=requireNotNull(storage.read("metadata"))
        generation=storage.publishStreamMigration(UUID.randomUUID().toString(),ComputerFileCodec.hash("family-$run-${UUID.randomUUID()}".toByteArray()),
            NativeMigrationStreamRecords(mapOf("metadata" to raw),emptyMap(),4))
        check(!keys().containsAlias(childAlias));plannedAliases.add(childAlias)
        check(keys().containsAlias(alias))
        storage=ownStorage(namespace,alias)
        return requireNotNull(generation)
    }
    fun generationFolder(id: String): File {
        check(UUID.fromString(id).toString()==id)
        return File(File(noBackup,namespace),"migration-generation-$id")
    }
    fun unindexedFamilyPeer(): File {
        check(session==null&&!freezeStarted&&held==null)
        val id=UUID.randomUUID().toString();check(Files.notExists(generationFolder(id).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return File(noBackup,"$namespace-$id-"+NativeComputerFileStore.SCOPE).also {check(it.mkdir());Os.chmod(it.path,0x1c0)}
    }
    fun assertAllFamilyChildAliasesAbsent() {
        for(name in plannedAliases.filter {it.startsWith("agent-hub-child-")})assertFalse(keys().containsAlias(name))
    }
    fun <T> family(limits: NativeMigrationComputerFileFamilySnapshot.Limits=NativeMigrationComputerFileFamilySnapshot.Limits(this.limits),
        work: (NativeMigrationComputerFileFamilySnapshot)->T): T=
        storage.migrationComputerFileFamilySourceSnapshot(context,owner(),limits,work)
    fun child(): NativeSessionStorage=requireNotNull(storage.computerFileStorage(context,true)).also {if(it !in storages)storages.add(it)}
    private fun <T> producer(work: ()->T): T=storage.snapshotTransaction {
        NativeVoiceRawOwnership.computerFilesWriter(storage).use {writer->writer.requireCurrent();work()}
    }
    fun row(phase: ComputerFilePhase=ComputerFilePhase.UNKNOWN,badReceipt: Boolean=false,payload: ByteArray=bytes): PendingComputerFile {
        val hash=ComputerFileCodec.hash(payload)
        val original=PendingComputerFile(id,"synthetic-original.bin","application/octet-stream",payload.size.toLong(),hash,
            listOf(ComputerFileChunk(payload.size,hash)),phase)
        if(phase!=ComputerFilePhase.COMPLETED)return original
        val file=original.manifest(target.scope.hostId).let {if(badReceipt)it.copy(id=UUID.randomUUID().toString())else it}
        return original.copy(receipt=ComputerFileReceipt(file,"completed","/synthetic/F59/received.bin","2026-11-05T00:00:00Z"))
    }
    private fun journal(value: PendingComputerFile)=JSONObject().put("schema","agent-hub/android-computer-files-v1")
        .put("target",target.json()).put("files",JSONArray().put(value.json())).toString()
    fun produce(phase: ComputerFilePhase=ComputerFilePhase.UNKNOWN,retireChunk: Boolean=false,badReceipt: Boolean=false,payload: ByteArray=bytes): PendingComputerFile=producer {
        val child=child();val value=row(phase,badReceipt,payload)
        child.computerFileTransaction {
            child.computerFileWriteChunk(binaryName,payload);child.computerFileWrite(journalName,journal(value))
            if(retireChunk)child.computerFileRemoveChunk(binaryName,payload.size,ComputerFileCodec.hash(payload))
        };value
    }
    fun producePartialRetirement(first: ByteArray, remaining: ByteArray): PendingComputerFile = producer {
        require(first.size == NativeComputerFileStore.CHUNK_BYTES && remaining.size in 1 until first.size)
        val firstPart = ComputerFileChunk(first.size, ComputerFileCodec.hash(first))
        val remainingPart = ComputerFileChunk(remaining.size, ComputerFileCodec.hash(remaining))
        val value = row(ComputerFilePhase.COMPLETED, payload = first + remaining)
            .copy(chunks = listOf(firstPart, remainingPart))
        val child = child()
        child.computerFileTransaction {
            child.computerFileWriteChunk(binaryName, first)
            child.computerFileWriteChunk("computer-file-original:$id:1", remaining)
            child.computerFileWrite(journalName, journal(value))
            child.computerFileRemoveChunk(binaryName, firstPart.bytes, firstPart.sha256)
        }
        value
    }
    fun orphan()=producer {
        val child=child();child.computerFileTransaction {
            child.computerFileWriteChunk("computer-file-original:${UUID.randomUUID()}:0",bytes)
        }
    }
    fun overwriteJournalWithWrongInstalledKey()=producer {
        val other=ownStorage(childName,wrongAlias)
        other.computerFileTransaction {other.computerFileWrite(journalName,journal(row()))}
    }
    suspend fun freeze(): NativeMigrationOwnerLease {
        check(held==null&&!freezeStarted)
        return withContext(NonCancellable+Dispatchers.Main.immediate) {
            val actual=MobileSession(storage,deny,deny).also {session=it}
            actual.resetAcceptance(deny,deny,acceptanceEasyWorkPorts=deny to deny)
            actual.restoreForMigration()
            assertTrue(actual.migrationReady);assertNull(actual.chatNotifications);assertNull(actual.discovery);assertNull(actual.easyWorkDiscovery)
            assertNull(actual.voice);assertNull(actual.questionVoice);assertNull(actual.computerFiles)
            actual.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(storage))
            actual.voiceSettings.resumePreferences()?.join();assertTrue(actual.voiceSettings.persisted)
            freezeStarted=true
            actual.quiesceForMigration(UUID.randomUUID().toString()).also {
                held=it;assertTrue(actual.ownsMigrationLease(it));assertSame(storage,it.storage)
            }
        }.also {withContext(Dispatchers.IO){it.requireRawWriters()};assertNoPorts()}
    }
    fun invalidateActualOwner() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {checkNotNull(session).invalidateMigrationLease()}
    }
    fun owner()=checkNotNull(held)
    fun <T> snapshot(work: (NativeMigrationComputerFileSnapshot)->T): T=
        storage.migrationComputerFileSourceSnapshot(context,owner(),limits,work)
    fun assertNoPorts() {assertEquals(0,deny.overflow.get());assertTrue(deny.snapshot().isEmpty())}
    fun assertPlannedAliasAbsent(name: String) {check(name in plannedAliases);assertFalse(keys().containsAlias(name))}
    fun assertOnlyPlannedAliasesAdded() {check(originalAliases==aliases()-plannedAliases) {"Nonfixture Keystore alias set changed"}}
    fun writeOwned(file: File,data: ByteArray) {
        check(file.parentFile==cache||file.parentFile==childFolder)
        check(file.createNewFile());file.outputStream().use {it.write(data)};Os.chmod(file.path,0x180)
    }
    fun probeFolder(): File=File(cache,"probe").also {check(it.mkdir());Os.chmod(it.path,0x1c0)}
    fun probeFile(folder: File,name: String,data: ByteArray): File=File(folder,name).also {
        check(folder.parentFile==cache&&it.createNewFile());it.outputStream().use {out->out.write(data)};Os.chmod(it.path,0x180)
    }
    fun fixtureFds(): Map<String,String> =linkedMapOf<String,String>().apply {
        for(link in requireNotNull(File("/proc/self/fd").listFiles())) {
            val target=try {Os.readlink(link.path)}catch(error: ErrnoException){if(error.errno==OsConstants.ENOENT)continue else throw error}
            val plain=target.removeSuffix(" (deleted)")
            if(plain==folder.path||plain.startsWith(folder.path+"/"))put(link.name,target)
        }
    }
    data class State(val dev: Long,val inode: Long,val uid: Int,val gid: Int,val mode: Int,val links: Long,
        val size: Long,val modified: Long,val changed: Long,val sha256: String?)
    fun state(): Map<String,State> =linkedMapOf<String,State>().apply {
        fun scan(file: File) {
            check(file==folder||file.path.startsWith(folder.path+"/"));val s=Os.lstat(file.path)
            check(s.st_uid==Process.myUid()&&!OsConstants.S_ISLNK(s.st_mode))
            val control=file.name in setOf("voice-recordings-writer-v1.lock","computer-files-writer-v1.lock")
            // Never open a second FD for a record-lock inode, even to hash its zero bytes.
            if(control)check(s.st_size==0L)
            val hash=if(OsConstants.S_ISREG(s.st_mode)&&!control)ComputerFileCodec.hash(file.readBytes())else null
            put(file.relativeTo(folder).path,State(s.st_dev,s.st_ino,s.st_uid,s.st_gid,s.st_mode,s.st_nlink,s.st_size,s.st_mtime,s.st_ctime,hash))
            if(OsConstants.S_ISDIR(s.st_mode))requireNotNull(file.listFiles()).forEach(::scan)
        };scan(folder)
    }
    fun childCipherPins(): Map<String,String> =requireNotNull(childFolder.listFiles()).filter {it.name.matches(Regex("[a-f0-9]{64}\\.bin"))}
        .associate {it.name to ComputerFileCodec.hash(it.readBytes())}
    suspend fun cleanup() {
        if(!folderOwned)return
        withContext(NonCancellable+Dispatchers.Main.immediate) {
            session?.abandonMigrationRestore()
            held?.rawWriters?.let {checkNotNull(session).releaseVoiceForMigration(it)}
        }
        withContext(NonCancellable+Dispatchers.IO) {
            for(store in storages.asReversed()){store.awaitQueuedWrites();store.retireWriter()}
            check(!freezeStarted||held!=null) {"Quiescence outcome unknown; retain exact fixture and aliases"}
            ComputerFileFdOwners.requireReleased();check(fixtureFds().isEmpty()) {"Actual fixture FD remains open; retain fixture"}
            assertNoPorts();assertNoDefaultPreferences();assertOnlyPlannedAliasesAdded()
            if(aliasesReserved)for(name in plannedAliases){val store=keys();if(store.containsAlias(name))store.deleteEntry(name);check(!keys().containsAlias(name))}
            check(originalAliases==aliases()) {"Keystore alias set not restored after exact fixture cleanup"}
            fun remove(file: File) {
                check(file==folder||file.path.startsWith(folder.path+"/"));check(file.absoluteFile==file.canonicalFile)
                val s=Os.lstat(file.path);check(s.st_uid==Process.myUid()&&!OsConstants.S_ISLNK(s.st_mode))
                if(OsConstants.S_ISDIR(s.st_mode))requireNotNull(file.listFiles()).forEach(::remove)
                Files.delete(file.toPath())
            };remove(folder);check(Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }
}
