package com.agenthub.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local secrets and cached reads are bound to this installation's Android Keystore key. */
class NativeSessionStorage private constructor(context: Context, private val namespace: String, private val keyAlias: String,
    private val existingKeyOnly: Boolean, private val existingNamespaceOnly: Boolean, private val existingReadOnly: Boolean,
    private val migrationReadOnly: Boolean,private val migrationKeys: ConcurrentHashMap<String,SecretKey>?=null) {
    constructor(context: Context,namespace: String="mobile-v1",keyAlias: String="agent-hub-mobile-v1",
        existingKeyOnly: Boolean=false,existingNamespaceOnly: Boolean=false,existingReadOnly: Boolean=false):
        this(context,namespace,keyAlias,existingKeyOnly,existingNamespaceOnly,existingReadOnly,false)
    private var migrationPolicy: NativeMigrationExistingPolicy?=null
    private var migrationRoot: NativeSessionStorage?=null
    private var migrationChildPath: String?=null
    private var migrationScope: String?=null
    private var migrationAccess: Any=this
    private var restoreVerified=false
    private var migrationPublicationIntent: String?=null
    private var creationCheck: (()->Unit)?=null
    private var capturedMigrationKey: SecretKey?=null
    private val migrationReads=NativeMigrationReadLifetime()
    private val strictMigration get()=migrationKeys!=null||migrationRoot!=null

    init { require(keyAlias.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) }
    private val root = File(context.noBackupFilesDir, namespace).apply {
        if(existingNamespaceOnly||existingReadOnly||migrationReadOnly)require(java.nio.file.Files.isDirectory(toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!java.nio.file.Files.isSymbolicLink(toPath())) {"Existing namespace unavailable; no directory is created"}
        else mkdirs()
    }
    private var inspection: NativeLocalInspectionFilesV2?=null
    internal val localInspectionOnly get()=inspection!=null
    internal val inspectionMutationAttempts=java.util.concurrent.atomic.AtomicInteger()
    internal fun rejectInspectionMutation(){if(localInspectionOnly){inspectionMutationAttempts.incrementAndGet();error("Read-only original inspection rejects mutation")}}
    internal fun assertLocalInspectionUnchanged(){check(localInspectionOnly);inspection!!.assertUnchanged()}
    private fun admitRestore(){if(localInspectionOnly){existingNamespaceFence();inspection!!.assertUnchanged()}else requireCurrentGeneration()}
    private val canonicalRoot=root.canonicalPath
    // AtomicFile's older reader may restore its backup while another instance
    // is writing. Coordinate all instances for this directory in this process.
    private val ioLock = IO_LOCKS.computeIfAbsent(canonicalRoot) { ReentrantLock(true) }
    private val recovery = RECOVERY.computeIfAbsent(canonicalRoot) { RecoveryFence() }
    private data class PreparedChild(val epoch: Long,val storage: NativeSessionStorage)
    private val preparedChildren=ConcurrentHashMap<String,PreparedChild>()
    private val writes = Executors.newSingleThreadExecutor()
    @Volatile private var retiredWriter=false
    private val snapshotLock=Any()
    private val snapshots=mutableMapOf<String,Pair<()->Unit,(Exception)->Unit>>()
    private val scheduledSnapshots=mutableSetOf<String>()
    private val queuedWriteFailures=ConcurrentHashMap<String,Exception>()
    private val key: SecretKey by lazy {
        synchronized(KEY_LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val found=if(migrationKeys!=null)migrationKeys[keyAlias] else store.getKey(keyAlias, null) as? SecretKey
            NativeMigrationKeyChoice.resolve(found,existingKeyOnly,{creationCheck?.invoke()},
                {if(migrationReadOnly||migrationPolicy!=null||creationCheck!=null)capturedMigrationKey=it}) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                }.generateKey()
            }
        }
    }
    private class RecoveryFence(@Volatile var blocked: Boolean=false,@Volatile var generation: Long=0) {
        val queuedGeneration=ThreadLocal<Long>()
        // No filesystem, crypto or storage transaction runs under this short publication monitor.
        val publicationLock=Any()
        @Volatile var publicationEpoch=0L
    }
    internal fun beginRestore(): Long = ioLock.withLock { admitRestore();synchronized(recovery.publicationLock){recovery.blocked=true;++recovery.generation} }
    internal fun finishRestore(generation: Long,publish: ()->Unit) = ioLock.withLock {
        admitRestore()
        synchronized(recovery.publicationLock) {
            check(recovery.generation==generation) { "本地读取已被新的恢复替代" }
            publish();recovery.blocked=false
        }
    }
    internal class RestorePublication internal constructor(internal val owner: NativeSessionStorage,internal val generation: Long,internal val epoch: Long) {
        internal var used=false
    }
    /** IO verifies the actual pointer and generation before Main receives this one-use permit. */
    internal fun prepareRestorePublication(generation: Long): RestorePublication = ioLock.withLock {
        requireCurrentGeneration()
        synchronized(recovery.publicationLock) {
            check(!retiredWriter&&recovery.blocked&&recovery.generation==generation)
            RestorePublication(this,generation,recovery.publicationEpoch)
        }
    }
    /** Main publishes only memory; it never obtains ioLock or reloads a pointer here. */
    internal fun finishPreparedRestore(permit: RestorePublication,publish: ()->Unit) = synchronized(recovery.publicationLock) {
        check(permit.owner===this&&!permit.used&&!retiredWriter&&migrationUnavailable==null&&writableGeneration.isSuccess)
        check(recovery.blocked&&recovery.generation==permit.generation&&recovery.publicationEpoch==permit.epoch) { "本地读取已被新的恢复替代" }
        permit.used=true;publish();recovery.blocked=false
    }
    private var migrationUnavailable: Throwable?=null
    private fun checkMigrationAccess() {
        check(!existingReadOnly&&!migrationReadOnly) { "Ordinary storage access is forbidden for an existing read-only view" }
        migrationUnavailable?.let {throw IllegalStateException("迁入数据尚未恢复，原记录已保留",it)}
        writableGeneration.exceptionOrNull()?.let {throw IllegalStateException("本机迁移结果待核对，原文件已保留",it)}
    }
    private fun requireCurrentGeneration() {
        checkMigrationAccess()
        check(writableGeneration.getOrThrow()==activeGeneration()) { "旧安装的保存不能覆盖迁入数据" }
    }
    private fun requireWritable() {
        rejectInspectionMutation()
        check(!retiredWriter) { "旧迁移会话已停止保存，请使用恢复后的会话" }
        requireCurrentGeneration()
        check(!recovery.blocked) { "本地数据恢复前不能保存，原文件已保留" }
        check(recovery.queuedGeneration.get()?.let { it==recovery.generation }!=false) { "旧的保存已被本地恢复替代" }
    }
    companion object {
        /** Source snapshot decoder only. Existing namespace/key, no ordinary pointer path. */
        internal fun existingComputerFileSourceDecoder(context: Context,namespace: String,keyAlias: String): NativeSessionStorage {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,255}")))
            return NativeSessionStorage(context,namespace,keyAlias,true,true,false,true)
        }
        internal fun existingMigration(context: Context,base: File,namespace: String,keyAlias: String): NativeMigrationExistingView =
            NativeMigrationExistingView.open(context,base,namespace,keyAlias)
        internal fun migrationDecoder(context: Context,namespace: String,keyAlias: String,lifetime: NativeMigrationReadLifetime): NativeSessionStorage =
            lifetime.own({NativeSessionStorage(context,namespace,keyAlias,true,true,false,true)},{it.retireWriter()}).also {it.key}

        internal fun inspectNaturalOriginal(context: Context,lease: String,router: Boolean=false): NativeSessionStorage =
            ExistingReader.inspectNaturalOriginal(context,lease,router)
        private val RECOVERY=ConcurrentHashMap<String,RecoveryFence>(); private val KEY_LOCK = Any(); private val SNAPSHOT_IO_LOCK=Any(); private val IO_LOCKS=ConcurrentHashMap<String,ReentrantLock>() }
    private val migrationPointerName="migration-active-generation-v1"
    // This non-UTF-8 prefix cannot equal the AAD of any legacy logical name.
    private val recordContext=byteArrayOf(0xff.toByte(),3)+"agent-hub/android-local-record/v3".toByteArray(Charsets.UTF_8)
    private fun digest(name: String)=MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun atomic(folder: File,name: String)=AtomicFile(File(folder,digest(name)+".bin"))
    private fun strictBytes(text: String): ByteArray=Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text)).let{buffer->ByteArray(buffer.remaining()).also{buffer.get(it)}}
    private fun decodeRecord(bytes: ByteArray,expectedName: String?,includeText: Boolean=true): Pair<String,String> {
        require(bytes.size>=29&&bytes[0] in listOf(1.toByte(),3.toByte())) { "本地缓存格式无效" }
        val version=bytes[0];val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(if(version==1.toByte())strictBytes(requireNotNull(expectedName))else recordContext)
        val clear=crypt(cipher,bytes,13)
        try {
            if(version==1.toByte())return requireNotNull(expectedName) to if(includeText)Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(clear)).toString()else ""
            val frame=java.nio.ByteBuffer.wrap(clear);val size=frame.int
            require(size in 1..65536&&frame.remaining()>=size)
            val nameBytes=ByteArray(size);frame.get(nameBytes)
            val name=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(nameBytes)).toString()
            require(name.length<=16384&&(expectedName==null||expectedName==name)) { "本地缓存逻辑名不匹配" }
            return name to if(includeText)Charsets.UTF_8.newDecoder().decode(frame).toString()else ""
        } finally {clear.fill(0)}
    }
    private fun decodeAtomic(atomic: AtomicFile,name: String): String? {
        if(strictMigration) {
            val file=atomic.baseFile;requireSettledMigrationFile(file)
            if(java.nio.file.Files.notExists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return null
            val ref=NativeMigrationReadOnlyFile(requireNotNull(file.parentFile),file,NativeMigrationTextFrames.MAX_RECORD_BYTES+65_569,migrationReads)
            val result=ref.read {input->val bytes=input.readBytes();try{decodeRecord(bytes,name).second}finally{bytes.fill(0)}}.value
            requireSettledMigrationFile(file);return result
        }
        val bytes=try {atomic.readFully()}catch(_: java.io.FileNotFoundException){return null}
        return decodeRecord(bytes,name).second
    }
    private fun encodeAtomic(atomic: AtomicFile,name: String,text: String) {
        val nameBytes=strictBytes(name);require(name.isNotEmpty()&&name.length<=16384&&nameBytes.size<=65536)
        val textBytes=strictBytes(text)
        val header=java.nio.ByteBuffer.allocate(4+nameBytes.size).putInt(nameBytes.size).put(nameBytes).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(recordContext)}
        val encrypted=try{byteArrayOf(3)+cipher.iv+crypt(cipher,textBytes,prefix=header)}finally{header.fill(0);textBytes.fill(0)}
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
        val stream=atomic.startWrite()
        try {stream.write(encrypted);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);atomic.finishWrite(stream)}catch(error: Throwable){atomic.failWrite(stream);throw error}
    }
    private fun activeGeneration(): String? {
        check(!existingReadOnly) { "Read-only views never resolve generations through AtomicFile" }
        val raw=decodeAtomic(atomic(root,migrationPointerName),migrationPointerName) ?: return null
        return generationFromRaw(raw)
    }
    private fun generationFromRaw(raw: String): String {
        require(raw.length<=1024)
        val value=JSONObject(raw);val version=value.get("version");require(version==1||version==2)
        require(value.keys().asSequence().toSet()==setOf("version","generation","candidateId","sha256","records","recordsSha256")+if(version==2)setOf("sourceRecords")else emptySet())
        if(version==2)require(value.get("sourceRecords") is Int&&value.getInt("sourceRecords") in 1..value.getInt("records"))
        val generation=value.getString("generation");require(java.util.UUID.fromString(generation).toString()==generation)
        require(java.util.UUID.fromString(value.getString("candidateId")).toString()==value.getString("candidateId"))
        require(value.getString("sha256").matches(Regex("[a-f0-9]{64}"))&&value.getString("recordsSha256").matches(Regex("[a-f0-9]{64}"))&&value.getInt("records") in 1..1024)
        val folder=File(root,"migration-generation-$generation");require(folder.isDirectory&&folder.canonicalFile==File(root.canonicalFile,folder.name)) { "迁入数据不可用，未回退到旧数据" }
        return generation
    }
    // The read-only constructor must not enter any ordinary pointer/AtomicFile path, even
    // when another process creates a backup after a previous inventory was captured.
    private val writableGeneration: Result<String?> = if(existingReadOnly||migrationReadOnly) Result.success(null)
        else ioLock.withLock {runCatching {requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);activeGeneration()}}
    init {
        if(existingReadOnly) {
            check(existingKeyOnly&&existingNamespaceOnly)
            existingNamespaceFence()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            require(keys.getKey(keyAlias,null) is SecretKey) {"Existing key unavailable; no fallback generation"}
        }
    }
    /** Bounded existing-key F59 codecs. No paths, writer/recovery helpers or keys escape. */
    internal fun requireExistingComputerFileSourceKey() {
        check(migrationReadOnly&&existingKeyOnly&&existingNamespaceOnly&&!retiredWriter);key
    }
    internal fun existingComputerFileSourcePointer(bytes: ByteArray): String {
        requireExistingComputerFileSourceKey()
        require(bytes.size in 29..70_000&&bytes[0]==3.toByte())
        val raw=decodeRecord(bytes,migrationPointerName).second
        val pointer=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
        require(pointer.get("version") is Int&&pointer.get("records") is Int)
        for(name in listOf("generation","candidateId","sha256","recordsSha256"))require(pointer.get(name) is String)
        if(pointer.has("sourceRecords"))require(pointer.get("sourceRecords") is Int)
        return raw
    }
    internal fun existingComputerFileSourceJournal(bytes: ByteArray): Pair<String,String> {
        requireExistingComputerFileSourceKey();require(namespace.endsWith("-"+NativeComputerFileStore.SCOPE))
        require(bytes.size in 29..1_048_576&&bytes[0]==3.toByte())
        return decodeRecord(bytes,null).also {(name,raw)->
            require(name.matches(Regex("computer-files-v1:[a-f0-9]{64}"))&&
                raw.toByteArray(Charsets.UTF_8).size<=NativeComputerFileJournal.MAX_JOURNAL_BYTES)
        }
    }
    internal fun existingComputerFileSourceChunk(bytes: ByteArray): Pair<String,ByteArray> {
        requireExistingComputerFileSourceKey();require(namespace.endsWith("-"+NativeComputerFileStore.SCOPE))
        require(bytes.size in 29..1_100_000&&java.nio.ByteBuffer.wrap(bytes).int==4)
        val decoded=decodeBinary(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)),null,null,NativeComputerFileStore.CHUNK_BYTES)
        try {
            val parts=decoded.first.name.split(':')
            require(parts.size==3&&parts[0]=="computer-file-original");ComputerFileCodec.uuid(parts[1])
            val index=requireNotNull(parts[2].toIntOrNull())
            require(index in 0 until com.agenthub.core.ComputerFileMigrationRules.MAX_ORIGINAL_CHUNKS&&index.toString()==parts[2])
            return decoded.first.name to decoded.second
        }catch(error: Throwable){decoded.second.fill(0);throw error}
    }
    internal fun existingMigrationText(file: NativeMigrationReadOnlyFile,name: String?=null): Pair<String,String> {
        check(migrationReadOnly&&!retiredWriter)
        val version=file.read(false){NativeMigrationLocalRecordFormat.read(it)}.value
        NativeMigrationLegacyGcm.limit(version,file.identity.size,NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES,Runtime.getRuntime().maxMemory())?.let {throw LegacyGcmAuthenticationLimit(it)}
        return file.read {input->val bytes=input.readBytes();try{decodeRecord(bytes,name)}finally{bytes.fill(0)}}.value
    }
    internal fun existingMigrationTextFrame(file: NativeMigrationReadOnlyFile,expected: String?=null): Pair<String,NativeMigrationBlob> {
        val(name,text)=existingMigrationText(file,expected)
        val bytes=NativeMigrationTextFrames.bytes(text)
        return try {
            val probe=NativeMigrationProbe();probe.write(bytes)
            name to NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()) {output->
                val raw=NativeMigrationTextFrames.bytes(existingMigrationText(file,name).second)
                try{output.write(raw)}finally{raw.fill(0)}
            }
        }finally{bytes.fill(0)}
    }
    internal fun existingMigrationBinary(file: NativeMigrationReadOnlyFile,name: String?=null): Pair<String,NativeMigrationBlob> {
        check(migrationReadOnly&&!retiredWriter)
        val probe=NativeMigrationProbe()
        val header=file.read {input->decodeBinaryTo(java.io.DataInputStream(input),name,null,probe)}.value
        return header.name to NativeMigrationBlob(header.size,probe.sha256(),probe.prefix()) {output->
            check(!retiredWriter)
            file.read {input->decodeBinaryTo(java.io.DataInputStream(input),header.name,header.size,output)}
        }
    }
    internal fun existingMigrationKey(): SecretKey {check(migrationReadOnly&&!retiredWriter);return key}
    internal fun existingMigrationGeneration(raw: String): String {check(migrationReadOnly);return generationFromRaw(raw)}
    internal fun migrationExistingWriter(context: Context,view: NativeMigrationExistingView): NativeSessionStorage {
        check(migrationReadOnly);view.unchanged()
        val current=NativeSessionStorage(context,namespace,keyAlias,true,true,false,false,ConcurrentHashMap(view.boundKeys()))
        try {
            current.key;current.writableGeneration.getOrThrow();view.unchanged()
            current.migrationPolicy=NativeMigrationExistingPolicy(current,view.generation)
            current.migrationRoot=current
            return current
        }catch(error: Throwable){try{current.retireWriter()}catch(close: Throwable){error.addSuppressed(close);NativeMigrationReadLifetime().hold(current,error)};throw error}
    }
    internal val needsMigrationGenerationGrant get()=migrationPolicy!=null
    internal fun authorizeMigrationGeneration(session: MobileSession,lease: NativeMigrationOwnerLease) {
        migrationPolicy?.let {policy->
            check(lease.storage===this)
            session.requireMigrationGenerationOwner(lease)
            policy.authorize(this,session,lease,lease.operationId,{
                session.requireMigrationGenerationOwner(lease);lease.requireRawWriters();session.requireMigrationGenerationOwner(lease)
            },{session.requireMigrationGenerationOwner(lease)})
        }
    }
    internal fun bindMigrationSession(session: MobileSession) {
        val policy=migrationPolicy?:return
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        val epoch=session.migrationAuthorityEpoch
        policy.bindSession(migrationAccess,this,session,{session.requireMigrationStorage(this)},
            {session.requireMigrationStorage(this);check(session.migrationAuthorityEpoch==epoch)})
    }
    internal fun invalidateMigrationSession(session: MobileSession){migrationPolicy?.invalidate(session)}
    internal fun retireMigrationSession(session: MobileSession){migrationPolicy?.revoke(session)}
    internal fun prepareMigrationRestore(lease: NativeMigrationOwnerLease): NativeMigrationExistingPolicy.Restore? = ioLock.withLock {
        val policy=migrationPolicy?:return@withLock null
        check(this===migrationRoot&&lease.storage===this)
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        val raw=requireNotNull(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)) {
            "Failed/unpublished generation cannot authorize normal restore"
        }
        val generation=generationFromRaw(raw)
        check(raw==requireNotNull(migrationPublicationIntent)) {"Restore must audit this original publication intent"}
        policy.confirmCommittedPublicationForRestore(this,lease,lease.operationId,generation)
        policy.prepareRestore(this,lease,lease.operationId,generation)
    }
    internal fun openMigrationRestore(context: Context,lease: NativeMigrationOwnerLease): NativeSessionStorage {
        if(migrationPolicy==null)return reopen(context)
        val ticket=requireNotNull(lease.strictRestore)
        val current=reopen(context)
        try {
            check(current.writableGeneration.getOrThrow()==ticket.generation)
            requireNotNull(migrationPolicy).attachRoot(ticket,current)
            current.migrationAccess=ticket
            return current
        }catch(error: Throwable){try{current.retireWriter()}catch(close: Throwable){error.addSuppressed(close);migrationReads.hold(current,error)};throw error}
    }
    internal fun requireBoundMigrationSession(session: MobileSession,lease: NativeMigrationOwnerLease) {
        requireNotNull(migrationPolicy).requireBoundSession(migrationAccess,this,session,lease)
    }
    internal fun verifyMigrationRestore(lease: NativeMigrationOwnerLease) = ioLock.withLock {
        migrationPolicy?.let {policy->
            policy.verifyPrepared(migrationAccess,this,lease,requireNotNull(activeGeneration()))
            check(writableGeneration.getOrThrow()==activeGeneration());restoreVerified=true
        }
    }
    internal fun completeMigrationRestore(session: MobileSession,lease: NativeMigrationOwnerLease) {
        migrationPolicy?.let {check(restoreVerified);it.complete(migrationAccess,this,session,lease);restoreVerified=false}
    }
    private fun existingMigrationChild(context: Context,suffix: String,policy: NativeMigrationExistingPolicy): NativeSessionStorage {
        policy.requireHandle(migrationAccess)
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val generation=if(migrationChildPath==null)activeGeneration()else migrationScope
        val localGeneration=if(migrationChildPath==null)generation else null
        val childAlias="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((keyAlias+"\u0000"+suffix).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
        val scoped=if(localGeneration==null)childAlias else "agent-hub-child-"+digest(childAlias+"\u0000"+localGeneration).take(48)
        val childNamespace=namespace+(localGeneration?.let {"-$it"}?:"")+"-$suffix"
        val path=listOfNotNull(migrationChildPath,suffix).joinToString("/")
        val folder=File(context.noBackupFilesDir,childNamespace)
        val present=java.nio.file.Files.exists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
        fun authority(){
            policy.requireNewChild(requireNotNull(migrationRoot),migrationAccess,requireNotNull(generation),path)
            if(java.nio.file.Files.exists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                require(!java.nio.file.Files.isSymbolicLink(folder.toPath())&&folder.canonicalFile.parentFile==context.noBackupFilesDir.canonicalFile)
                val stat=android.system.Os.lstat(folder.path)
                require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x3f==0)
            }
        }
        if(!present){
            authority();require(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};require(!keys.containsAlias(scoped)){"Orphan key is not fresh generation authority"}
            check(folder.mkdir()) {"New generation namespace raced; no repair"}
            try {
                NativeMigrationReadLifetime().use(
                    {android.system.Os.open(folder.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW,0)},
                    {fd->val stat=android.system.Os.fstat(fd);require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid())
                        policy.requireNewChild(requireNotNull(migrationRoot),migrationAccess,requireNotNull(generation),path)
                        android.system.Os.fchmod(fd,0x1c0)
                        val current=android.system.Os.lstat(folder.path);require(current.st_dev==stat.st_dev&&current.st_ino==stat.st_ino)
                    },{android.system.Os.close(it)})
                authority();require(requireNotNull(folder.listFiles()).isEmpty())
            }catch(error: Throwable){NativeMigrationReadLifetime().hold(folder,error)}
        }
        val keys=requireNotNull(migrationKeys?:migrationRoot?.migrationKeys)
        val child=try{NativeSessionStorage(context,childNamespace,scoped,present,true,false,false,if(present)keys else null)}
            catch(error: Throwable){if(!present)NativeMigrationReadLifetime().hold(folder,error);throw error}
        try {
            if(!present){child.creationCheck=::authority;val createdKey=child.key;authority();check(keys.putIfAbsent(scoped,createdKey)==null)}
            else if(requireNotNull(folder.listFiles()).isNotEmpty())child.key
            child.migrationPolicy=policy;child.migrationRoot=migrationRoot;child.migrationChildPath=path;child.migrationScope=generation;child.migrationAccess=migrationAccess
            return child
        }catch(error: Throwable){
            if(!present)NativeMigrationReadLifetime().hold(child,error)
            try{child.retireWriter()}catch(close: Throwable){error.addSuppressed(close);NativeMigrationReadLifetime().hold(child,error)}
            throw error
        }
    }
    private fun dataRoot(): File=activeGeneration()?.let {File(root,"migration-generation-$it")} ?: root
    private fun file(name: String): AtomicFile {require(name!=migrationPointerName);return atomic(dataRoot(),name)}
    fun read(name: String): String? = ioLock.withLock {if(existingReadOnly) existingText(name,1_048_576,true) else {checkMigrationAccess();decodeAtomic(file(name),name)}}
    fun write(name: String,text: String)=ioLock.withLock {
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
        requireWritable();encodeAtomic(file(name),name,text)
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
    }

    internal open class IncompleteMigrationInventory(message: String="本机迁移记录不完整或包含未支持资料，原数据未更改。"): Exception(message)
    internal class LegacyGcmAuthenticationLimit(val limit: NativeMigrationLegacyGcm.Limit):
        IncompleteMigrationInventory("旧版完整认证记录超出当前安全迁移边界，原密文已保留。")
    private fun readMigrationTextFile(source: File, expectedName: String?, maximumBytes: Int=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES): Pair<String,String> {
        if(strictMigration) {
            requireSettledMigrationFile(source)
            val ref=NativeMigrationReadOnlyFile(requireNotNull(source.parentFile),source,maximumBytes+65_569,migrationReads)
            val version=ref.read(false){NativeMigrationLocalRecordFormat.read(it)}.value
            NativeMigrationLegacyGcm.limit(version,ref.identity.size,maximumBytes,Runtime.getRuntime().maxMemory())?.let {throw LegacyGcmAuthenticationLimit(it)}
            val result=ref.read {input->val bytes=input.readBytes();try{decodeRecord(bytes,expectedName)}finally{bytes.fill(0)}}.value
            requireSettledMigrationFile(source);return result
        }
        val observed=source.inputStream().use {it.read()}
        NativeMigrationLegacyGcm.limit(observed,source.length(),maximumBytes,Runtime.getRuntime().maxMemory())?.let {throw LegacyGcmAuthenticationLimit(it)}
        require(source.length() in 29..(maximumBytes+65569L))
        return decodeRecord(source.readBytes(),expectedName)
    }
    private fun migrationFileExists(file: File)=java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
    private fun requireSettledMigrationFile(file: File) {
        require(!migrationFileExists(File(file.path+".bak"))&&!migrationFileExists(File(file.path+".new"))) {"Pending migration audit record; originals preserved"}
        if(migrationFileExists(file))require(file.isFile&&file.canonicalFile==File(requireNotNull(file.parentFile).canonicalFile,file.name))
    }
    /** Current local publication context only; no portable identity or notification authority. */
    internal fun migrationHasPublishedGeneration(): Boolean = ioLock.withLock {
        checkMigrationAccess();requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        requireWritable();activeGeneration()!=null
    }
    /** Exact last local publication, including an acknowledgement lost before Session replacement. */
    internal fun migrationPublicationReceipt(): NativeMigrationCandidateStore.Receipt? = ioLock.withLock {
        checkMigrationAccess();requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        if(activeGeneration()==null)return@withLock null
        val value=JSONObject(requireNotNull(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)))
        NativeMigrationCandidateStore.Receipt(value.getString("candidateId"),value.getString("sha256"),
            if(value.get("version")==2)value.getInt("sourceRecords")else value.getInt("records"))
    }
    /** Narrow migration audit: reject pending files before any AtomicFile read/recovery. */
    internal fun migrationReadTextRecords(names: Set<String>): Map<String,String> = ioLock.withLock {
        require(names.size in 1..16&&names.all {it.isNotEmpty()&&it.length<=8192&&!it.contains('\u0000')&&it!=migrationPointerName})
        if(existingReadOnly)return@withLock names.mapNotNull {name->existingText(name,1_048_576,true)?.let {name to it}}.toMap()
        checkMigrationAccess()
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        requireWritable();val folder=dataRoot()
        val sources=names.associateWith {name->atomic(folder,name).baseFile}
        sources.values.forEach(::requireSettledMigrationFile)
        sources.mapNotNull {(name,file)->if(migrationFileExists(file))name to readMigrationTextFile(file,name).second else null}.toMap()
    }
    /** Authenticate one text at a time; retain only exact file/name/digest references. No recovery. */
    internal fun migrationReadTextFrames(names: Set<String>): NativeMigrationTextMap = ioLock.withLock {
        checkMigrationAccess();require(names.size in 1..1024&&names.all {it.isNotEmpty()&&it.length<=8192&&!it.contains('\u0000')&&it!=migrationPointerName})
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
        val folder=dataRoot();val frames=linkedMapOf<String,NativeMigrationBlob>()
        for(name in names.sorted()) {
            val source=atomic(folder,name).baseFile;requireSettledMigrationFile(source)
            if(migrationFileExists(source))frames[name]=migrationTextFrame(source,name).second
        }
        NativeMigrationTextMap(frames)
    }
    private fun migrationTextFrame(source: File,expectedName: String?,fixed: NativeMigrationReadOnlyFile?=null): Pair<String,NativeMigrationBlob> {
        fixed?.unchanged()
        val decoded=readMigrationTextFile(source,expectedName);val name=decoded.first;val bytes=NativeMigrationTextFrames.bytes(decoded.second)
        val blob=try {fixed?.unchanged();val probe=NativeMigrationProbe();probe.write(bytes)
            NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()) {output->
                fixed?.unchanged();requireSettledMigrationFile(source)
                val value=NativeMigrationTextFrames.bytes(readMigrationTextFile(source,name).second)
                try {output.write(value);fixed?.unchanged()}finally {value.fill(0)}
            }
        }finally {bytes.fill(0)}
        // The closure captures only an authenticated name, never the decoded Pair/String.
        return name to blob
    }
    private fun migrationReadOnlyFile(source: File)=NativeMigrationReadOnlyFile(requireNotNull(source.parentFile),source,23*1024*1024,migrationReads)
    private fun <T> migrationReadInput(source: File,complete: Boolean=true,consume: (java.io.InputStream)->T): T {
        if(!strictMigration)return source.inputStream().buffered().use(consume)
        requireSettledMigrationFile(source)
        return migrationReadOnlyFile(source).read(complete,consume).value.also {requireSettledMigrationFile(source)}
    }
    /** Discover authenticated modern names before resolving exact legacy aliases. */
    private fun migrationModernNames(files: Map<String,File>): Set<String> = files.mapNotNull {(filename,source)->
        val version=migrationReadInput(source,false,NativeMigrationLocalRecordFormat::read)
        val name=when(version) {
            3->readMigrationTextFile(source,null).first
            4->migrationReadInput(source,false){binaryHeader(java.io.DataInputStream(it),null,null).name}
            1,2->null
            else->error("Unknown local migration format")
        }
        name?.also {require(it.isNotEmpty()&&it.length<=8192&&!it.contains('\u0000')&&it!=migrationPointerName&&filename==digest(it)+".bin")}
    }.toSet()
    private fun migrationAliases(files: Map<String,File>,resolveLegacyNames: ((String)->String?)->Set<String>,resolveModernAliases: (Set<String>,(String)->String?)->Set<String>): Map<String,String> {
        val modern=migrationModernNames(files)
        val read: (String)->String?={name->files[digest(name)+".bin"]?.let {readMigrationTextFile(it,name).second}}
        val names=modern+resolveLegacyNames(read)+resolveModernAliases(modern,read)
        require(names.size<=4096&&names.all {it.length in 1..8192&&!it.contains('\u0000')})
        return names.associateBy {digest(it)+".bin"}.also {require(it.size==names.size)}
    }
    /** Destination authority/audit subset, including modern unreferenced records, without recovery. */
    internal fun migrationSelectedTextFrames(include: (String)->Boolean,resolveAliases: (Set<String>,(String)->String?)->Set<String>): NativeMigrationTextMap = snapshotTransaction {ioLock.withLock {
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
        val files=migrationFiles(dataRoot()).associateBy {it.name};require(files.size<=1024)
        val aliases=migrationAliases(files,{setOf("metadata")},resolveAliases)
        val selected=linkedMapOf<String,NativeMigrationBlob>()
        for((filename,source)in files) {
            val version=migrationReadInput(source,false,NativeMigrationLocalRecordFormat::read)
            val name=aliases[filename]?:error("Unidentified legacy destination original")
            if(include(name)) {require(version==1||version==3);selected[name]=migrationTextFrame(source,name).second}
        }
        NativeMigrationTextMap(selected)
    }}
    /** Known system sub-stores live beside the root, not inside its hashed-record inventory.
     * Presence is audited without opening/recovering files or borrowing OS callback authority. */
    /** F59 only. Opening/refreshing an empty page never creates a child or key. */
    internal fun computerFileStorage(context: Context,create: Boolean): NativeSessionStorage? = ioLock.withLock {
        requireCurrentGeneration();check(!retiredWriter)
        val generation=activeGeneration();val name=namespace+(generation?.let {"-$it"}?:"")+"-computer-file-transfers"
        val folder=File(requireNotNull(root.parentFile),name)
        if(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!create)return@withLock null
        if(java.nio.file.Files.exists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(java.nio.file.Files.isDirectory(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&
                !java.nio.file.Files.isSymbolicLink(folder.toPath())&&folder.canonicalFile==File(root.parentFile!!.canonicalFile,name))
            val st=android.system.Os.lstat(folder.path)
            require(st.st_uid==android.os.Process.myUid()&&st.st_gid==android.os.Process.myUid()&&(st.st_mode and 0xfff)==0x1c0)
        }else require(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        prepareChild(context,"computer-file-transfers")
    }
    // F59 alone uses a strict ciphertext commit/delete port. Ordinary legacy writers
    // retain their current behavior; this is not a global AtomicFile replacement.
    private var computerFilesDurable: NativeComputerFileDurableFiles?=null
    private fun requireComputerFileWritable() {
        require(namespace.endsWith("-computer-file-transfers"))
        // Never let the ordinary pointer reader recover an unfinished child pointer.
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
        check(dataRoot()==root) {ComputerFileCopy.LOCAL_SAVE_PENDING}
    }
    private fun computerFilesPort(): NativeComputerFileDurableFiles {
        requireComputerFileWritable()
        return computerFilesDurable?:NativeComputerFileDurableFiles(root,::requireComputerFileWritable).also {computerFilesDurable=it}
    }
    internal fun <T> computerFileTransaction(work: ()->T): T=ioLock.withLock {computerFilesPort().transaction(work)}
    internal fun computerFileRead(name: String): String?=ioLock.withLock {
        require(name.matches(Regex("computer-files-v1:[a-f0-9]{64}")))
        val bytes=computerFilesPort().read(name,1_048_576)?:return@withLock null
        try {decodeRecord(bytes,name).second}finally {bytes.fill(0)}
    }
    internal fun computerFileWrite(name: String,text: String)=ioLock.withLock {
        require(name.matches(Regex("computer-files-v1:[a-f0-9]{64}")))
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
        val port=computerFilesPort();val nameBytes=strictBytes(name);val textBytes=strictBytes(text)
        require(textBytes.size<=900_000)
        val header=java.nio.ByteBuffer.allocate(4+nameBytes.size).putInt(nameBytes.size).put(nameBytes).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(recordContext)}
        val encrypted=try {byteArrayOf(3)+cipher.iv+crypt(cipher,textBytes,prefix=header)}finally {header.fill(0);textBytes.fill(0)}
        try {port.write(name,encrypted);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)}finally {encrypted.fill(0)}
    }
    internal fun computerFileWriteChunk(name: String,bytes: ByteArray)=ioLock.withLock {
        require(name.matches(Regex("computer-file-original:[a-f0-9-]{36}:[0-9]{1,4}"))&&bytes.size in 1..1_048_576)
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
        val port=computerFilesPort();binaryName(name)
        val buffer=java.io.ByteArrayOutputStream(bytes.size+2048)
        val out=java.io.DataOutputStream(buffer);val nonce=writeBinaryHeader(out,name,bytes.size)
        var offset=0;var index=0
        while(offset<bytes.size) {
            val count=minOf(65536,bytes.size-offset);val clear=bytes.copyOfRange(offset,offset+count)
            try {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(binaryAAD(name,bytes.size,index++,nonce))}
                out.write(cipher.iv);out.write(crypt(cipher,clear))
            }finally {clear.fill(0)}
            offset+=count
        }
        out.flush();val encrypted=buffer.toByteArray()
        try {port.write(name,encrypted);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)}finally {encrypted.fill(0)}
    }
    internal fun computerFileReadChunk(name: String,size: Int): ByteArray=ioLock.withLock {
        require(name.matches(Regex("computer-file-original:[a-f0-9-]{36}:[0-9]{1,4}"))&&size in 1..1_048_576)
        val bytes=requireNotNull(computerFilesPort().read(name,1_100_000)) {ComputerFileCopy.ORIGINAL_INVALID}
        try {decodeBinary(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)),name,size,1_048_576).second}finally {bytes.fill(0)}
    }
    internal fun computerFileRemoveChunk(name: String,size: Int,sha256: String)=ioLock.withLock {
        require(name.matches(Regex("computer-file-original:[a-f0-9-]{36}:[0-9]{1,4}"))&&size in 1..1_048_576&&sha256.matches(Regex("[a-f0-9]{64}")))
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
        val port=computerFilesPort();val encrypted=port.read(name,1_100_000)
        val cipherHash=encrypted?.let {bytes->
            try {
                val clear=decodeBinary(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)),name,size,1_048_576).second
                try {require(ComputerFileCodec.hash(clear)==sha256) {ComputerFileCopy.ORIGINAL_INVALID}}finally {clear.fill(0)}
                ComputerFileCodec.hash(bytes)
            }finally {bytes.fill(0)}
        }
        port.remove(name,cipherHash);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
    }
    /** One exact active-generation F59 source. The actual frozen root owns all
     * callbacks; no portable format, export exclusion or activation gate changes. */
    internal fun <T> migrationComputerFileSourceSnapshot(context: Context,owner: NativeMigrationOwnerLease,
        limits: NativeMigrationComputerFileSnapshot.Limits,consume: (NativeMigrationComputerFileSnapshot)->T): T = snapshotTransaction {ioLock.withLock {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        check(owner.storage===this&&migrationChildPath==null&&(migrationRoot==null||migrationRoot===this))
        check(!namespace.endsWith("-"+NativeComputerFileStore.SCOPE))
        check(!retiredWriter&&!existingReadOnly&&!migrationReadOnly&&!localInspectionOnly)
        checkMigrationAccess();ComputerFileFdOwners.requireReleased()
        val generation=writableGeneration.getOrThrow();val frozen=owner.requireRawWriters()
        fun authority() {
            check(!retiredWriter&&!existingReadOnly&&!migrationReadOnly&&!localInspectionOnly)
            checkMigrationAccess();migrationPolicy?.requireHandle(migrationAccess)
            check(writableGeneration.getOrThrow()==generation&&owner.storage===this)
            check(owner.requireRawWriters()===frozen);ComputerFileFdOwners.requireReleased()
        }
        authority()
        NativeMigrationComputerFileSnapshot.using(context,File(canonicalRoot),namespace,keyAlias,generation,owner,limits,::authority,consume)
    }}
    /** F59 originals from the legacy root and every retained generation. All
     * callbacks share the actual root Session/raw freeze; no activation grant. */
    internal fun <T> migrationComputerFileFamilySourceSnapshot(context: Context,owner: NativeMigrationOwnerLease,
        limits: NativeMigrationComputerFileFamilySnapshot.Limits,consume: (NativeMigrationComputerFileFamilySnapshot)->T): T = snapshotTransaction {ioLock.withLock {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        check(owner.storage===this&&migrationChildPath==null&&(migrationRoot==null||migrationRoot===this))
        check(!namespace.endsWith("-"+NativeComputerFileStore.SCOPE))
        check(!retiredWriter&&!existingReadOnly&&!migrationReadOnly&&!localInspectionOnly)
        checkMigrationAccess();ComputerFileFdOwners.requireReleased()
        val generation=writableGeneration.getOrThrow();val frozen=owner.requireRawWriters()
        fun authority() {
            check(!retiredWriter&&!existingReadOnly&&!migrationReadOnly&&!localInspectionOnly)
            checkMigrationAccess();migrationPolicy?.requireHandle(migrationAccess)
            check(writableGeneration.getOrThrow()==generation&&owner.storage===this)
            check(owner.requireRawWriters()===frozen);ComputerFileFdOwners.requireReleased()
        }
        authority()
        NativeMigrationComputerFileSnapshot.usingFamily(context,File(canonicalRoot),namespace,keyAlias,generation,owner,limits,::authority,consume)
    }}
    internal fun requireNoComputerFilesForMigration() = ioLock.withLock {
        checkMigrationAccess();requireCurrentGeneration()
        val generation=activeGeneration();val name=namespace+(generation?.let {"-$it"}?:"")+"-computer-file-transfers"
        val folder=File(requireNotNull(root.parentFile),name)
        if(ComputerFileFdOwners.hasUnresolved()||!java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw NativeMigrationExport.IncompleteScope(setOf("computer-file-transfers"))
    }
    internal fun migrationPendingChildScopes(): Set<String> = ioLock.withLock {
        checkMigrationAccess();requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
        val resourceScopes=if(ComputerFileFdOwners.hasUnresolved())setOf("computer-file-input-resource")else emptySet()
        val generation=activeGeneration();val prefix=namespace+(generation?.let {"-$it"}?:"")+"-"
        val systemScopes=setOf("external-inbox","recent-photos","photo-permission","document-results","photo-results","camera-results")
        val writerScopes=setOf("external-inbox","document-results","photo-results","camera-results","document-results-payloads","photo-results-payloads","camera-results-payloads","camera-results-outputs","recent-photos","photo-permission")
        val writerControls=writerScopes.map {prefix+it+".inbox-writer-v1.lock"}.toSet()
        val siblings=root.parentFile!!.listFiles()?:error("Migration child inventory unavailable")
        siblings.mapNotNull { folder ->
            if(folder.name in writerControls){NativeInboxOwnership.requireControlFile(folder);return@mapNotNull null}
            if(!folder.name.startsWith(prefix))return@mapNotNull null
            val suffix=folder.name.removePrefix(prefix)
            if(suffix=="computer-file-transfers")return@mapNotNull "computer-file-transfers"
            // Exact operational staging is not an active user original. No broad prefix exclusion:
            // a future child, nested staging node or sidecar must not silently leave the inventory.
            if(suffix=="migration-candidates") {
                require(folder.isDirectory&&folder.canonicalFile==File(root.parentFile!!.canonicalFile,folder.name))
                return@mapNotNull null
            }
            val scope=systemScopes.singleOrNull { suffix==it || suffix.startsWith("$it-") }
            if(scope==null)"unregistered-child" else {
                require(folder.canonicalFile==File(root.parentFile!!.canonicalFile,folder.name)&&folder.isDirectory)
                val children=folder.listFiles()?:error("Migration child inventory unavailable")
                if(children.isEmpty())null else scope
            }
        }.toSet()+resourceScopes
    }
    /** Resolve exact legacy references and capture all names/text under the same generation lock.
     * Reading for discovery never invokes AtomicFile recovery or changes a pending .bak/.new. */
    internal fun migrationArchiveSnapshot(resolveLegacyNames: ((String)->String?)->Set<String>): Map<String,String> = try {
        snapshotTransaction {ioLock.withLock {
            requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable();val files=migrationFiles(dataRoot()).associateBy{it.name}
            val aliases=resolveLegacyNames {name->
                files[digest(name)+".bin"]?.let{file->
                    readMigrationTextFile(file,name).second
                }
            }
            migrationSnapshot(migrationNames(aliases))
        }}
    }catch(error: LegacyGcmAuthenticationLimit){throw error}catch(_: Exception){throw IncompleteMigrationInventory()}
    /** Typed root snapshot; discovers authenticated format4 names and exact indexed legacy2 aliases.
     * Discovery never restores AtomicFile backups or silently skips an unsupported record. */
    internal fun migrationTypedArchiveSnapshot(resolveLegacyNames: ((String)->String?)->Set<String>,maximumBytes: Int=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES,resolveModernAliases: (Set<String>,(String)->String?)->Set<String> = {_,_->emptySet()}): NativeMigrationRecords = try {
        snapshotTransaction {ioLock.withLock {
            requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable();require(maximumBytes in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            val files=migrationFiles(dataRoot()).associateBy{it.name};require(files.size in 1..1024)
            val aliases=migrationAliases(files,resolveLegacyNames,resolveModernAliases)
            val text=linkedMapOf<String,String>();val binary=linkedMapOf<String,String>();var total=0L
            for((filename,source)in files.toSortedMap()) {
                require(source.length() in 29..(maximumBytes+65569L+28L*((maximumBytes+65535)/65536)))
                java.io.DataInputStream(source.inputStream().buffered()).use {input->
                    input.mark(4);val version=input.readUnsignedByte();input.reset()
                    val name: String;val value: String
                    if(version==1||version==3) {
                        val decoded=decodeRecord(source.readBytes(),aliases[filename]);name=decoded.first;value=decoded.second
                        require(name !in binary&&text.put(name,value)==null)
                    }else {
                        // Authenticate the small header and account for base64 expansion before
                        // allocating a complete binary value. Oversize originals remain untouched.
                        val header=binaryHeader(input,aliases[filename],null)
                        val expanded=((header.size.toLong()+2)/3)*4
                        require(total+expanded<=maximumBytes)
                        input.close()
                        val decoded=java.io.DataInputStream(source.inputStream().buffered()).use {decodeBinary(it,aliases[filename],null,maximumBytes)};name=decoded.first.name
                        try {value=java.util.Base64.getEncoder().encodeToString(decoded.second)}finally {decoded.second.fill(0)}
                        require(name !in text&&binary.put(name,value)==null)
                    }
                    require(name.isNotEmpty()&&name.length<=8192&&!name.contains('\u0000')&&name!=migrationPointerName&&filename==digest(name)+".bin")
                    total+=if(version==1||version==3)strictBytes(value).size else value.length;require(total<=maximumBytes)
                }
            }
            require("metadata" in text);NativeMigrationRecords(text,binary)
        }}
    }catch(error: LegacyGcmAuthenticationLimit){throw error}catch(_: Exception){throw IncompleteMigrationInventory()}
    private fun migrationFiles(folder: File): List<File> {
        val children=folder.listFiles()?:error("Migration inventory unavailable")
        return children.filterNot {entry->
            // Only the legacy root can contain uncommitted generation directories.
            folder==root&&entry.isDirectory&&entry.canonicalFile==File(folder.canonicalFile,entry.name)&&
                entry.name.removePrefix("migration-generation-").let {id->entry.name=="migration-generation-$id"&&runCatching {java.util.UUID.fromString(id).toString()==id}.getOrDefault(false)}
        }.also {files->require(files.all {it.isFile&&it.canonicalFile==File(folder.canonicalFile,it.name)&&it.name.matches(Regex("[a-f0-9]{64}\\.bin"))})}
    }
    /** New text records reveal their authenticated name only after Keystore decryption.
     * A legacy record still needs an exact caller-supplied name; binary/unknown records never disappear. */
    internal fun migrationNames(legacyNames: Set<String> = emptySet()): Set<String> = try {
        snapshotTransaction {ioLock.withLock {
            requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable();require(legacyNames.size<=1024)
            val aliases=legacyNames.associateBy {digest(it)+".bin"};require(aliases.size==legacyNames.size)
            val files=migrationFiles(dataRoot());require(files.size in 1..1024)
            val result=files.map {file->
                require(file.length() in 29..(NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES+65569).toLong())
                val name=decodeRecord(file.readBytes(),aliases[file.name],includeText=false).first
                require(name!=migrationPointerName&&file.name==digest(name)+".bin")
                name
            }
            require(result.distinct().size==result.size);result.toSet()
        }}
    }catch(_: Exception){throw IncompleteMigrationInventory()}
    /** Exact active-generation inventory, not a best-effort export or a guess from hashed filenames.
     * Callers must name every active record. Unknown/missing/binary/unfinished files reject explicitly. */
    internal fun migrationSnapshot(names: Set<String>,maximumBytes: Int=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES): Map<String,String> = try {
        snapshotTransaction {ioLock.withLock {
            requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
            require(names.size in 1..1024&&"metadata" in names&&maximumBytes in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            require(names.all {it.isNotEmpty()&&it.length<=8192&&!it.contains('\u0000')&&it!=migrationPointerName})
            val expected=names.associateBy {digest(it)+".bin"};require(expected.size==names.size)
            val folder=dataRoot();val files=migrationFiles(folder)
            require(files.map{it.name}.toSet()==expected.keys)
            require(files.sumOf {it.length()}<=maximumBytes.toLong()+33L*names.size+names.sumOf{strictBytes(it).size.toLong()})
            val result=names.sorted().associateWith {name->decodeAtomic(atomic(folder,name),name)?:error("Migration record missing")}
            require(result.values.sumOf {it.toByteArray(Charsets.UTF_8).size.toLong()}<=maximumBytes)
            result
        }}
    }catch(_: Exception){throw IncompleteMigrationInventory()}

    /** A retried activation reads its original commit receipt without republishing later edits. */
    internal fun publishedMigration(candidateId: String,sha256: String,records: Int): String? = ioLock.withLock {
        require(java.util.UUID.fromString(candidateId).toString()==candidateId&&sha256.matches(Regex("[a-f0-9]{64}"))&&records in 1..1024)
        checkMigrationAccess();requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        val generation=activeGeneration() ?: return@withLock null
        val pointer=JSONObject(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)!!)
        if(pointer.getString("candidateId")!=candidateId)return@withLock null
        require(pointer.getString("sha256")==sha256&&(if(pointer.get("version")==2)pointer.getInt("sourceRecords")else pointer.getInt("records"))==records)
        generation
    }

    /** Per-call observation only; no storage/owner authority or external command is granted. */
    internal enum class PublicationPoint { AFTER_POINTER,BEFORE_CONFIRMATION,AFTER_CONFIRMATION }
    internal data class PublicationIdentity(val receipt: NativeMigrationCandidateStore.Receipt,val generation: String,
        val actualRecords: Int,val recordsSha256: String)
    internal class PublicationObserver(val expected: NativeMigrationCandidateStore.Receipt,
        val observe: (PublicationPoint,PublicationIdentity)->Unit)

    /** One authenticated pointer is the commit point; staged generations are never active on failure. */
    internal fun publishMigration(candidateId: String,sha256: String,records: Map<String,String>,checkpoint: (Int)->Unit={}): String =
        publishMigrationRecords(candidateId,sha256,NativeMigrationRecords(records),checkpoint)

    internal fun publishMigrationRecords(candidateId: String,sha256: String,archive: NativeMigrationRecords,checkpoint: (Int)->Unit={}): String =
        publishPreparedMigrationRecords(candidateId,sha256,archive,archive.size,checkpoint)

    internal fun publishPreparedMigrationRecords(candidateId: String,sha256: String,archive: NativeMigrationRecords,sourceRecordCount: Int,checkpoint: (Int)->Unit={}): String = ioLock.withLock {
        require(sourceRecordCount in 1..archive.size&&archive.size<=1024)
        val records=archive.text;val binaries=archive.binary
        require(java.util.UUID.fromString(candidateId).toString()==candidateId&&sha256.matches(Regex("[a-f0-9]{64}")))
        require(archive.size in 1..1024&&records.containsKey("metadata")&&migrationPointerName !in records&&migrationPointerName !in binaries)
        require((records.keys+binaries.keys).all {it.isNotEmpty()&&it.length<=8192&&!it.contains('\u0000')}&&(records.values+binaries.values).sumOf {strictBytes(it).size.toLong()}<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val contents=MessageDigest.getInstance("SHA-256")
        for((name,text)in records.toSortedMap())for(value in listOf(name,text)) {val bytes=value.toByteArray(Charsets.UTF_8);contents.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());contents.update(bytes)}
        if(binaries.isNotEmpty()) {
            contents.update(byteArrayOf(0xff.toByte(),4));contents.update(java.nio.ByteBuffer.allocate(4).putInt(binaries.size).array())
            for((name,encoded)in binaries.toSortedMap())for(value in listOf(name,encoded)){val bytes=strictBytes(value);contents.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());contents.update(bytes)}
        }
        val recordsSha256=contents.digest().joinToString("") {"%02x".format(it)}
        publishMigrationGeneration(candidateId,sha256,sourceRecordCount,recordsSha256,archive.size,checkpoint) { folder ->
            records.toSortedMap().entries.forEachIndexed {index,(name,text)->val target=atomic(folder,name);encodeAtomic(target,name,text);check(decodeAtomic(target,name)==text);checkpoint(index+1)}
            binaries.toSortedMap().entries.forEachIndexed {index,(name,encoded)->
                val bytes=NativeMigrationRecords.bytes(encoded)
                try {
                    val target=atomic(folder,name);encodeBinaryAtomic(target,name,java.io.ByteArrayInputStream(bytes),bytes.size)
                    java.io.DataInputStream(target.openRead()).use {input->val observed=decodeBinary(input,name,bytes.size,NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES).second
                        try {check(MessageDigest.isEqual(bytes,observed))}finally {observed.fill(0)}}
                }finally {bytes.fill(0)}
                checkpoint(records.size+index+1)
            }
        }
    }

    internal fun publishStreamMigration(candidateId: String, sha256: String, archive: NativeMigrationStreamRecords, checkpoint: (Int) -> Unit = {}): String =
        publishPreparedStreamMigration(candidateId,sha256,archive,archive.size,checkpoint=checkpoint)

    internal fun publishPreparedStreamMigration(candidateId: String, sha256: String, archive: NativeMigrationStreamRecords,sourceRecordCount: Int, publicationObserver: PublicationObserver?=null, checkpoint: (Int) -> Unit = {}): String = ioLock.withLock {
        require(sourceRecordCount in 1..archive.size&&archive.size<=1024)
        require(java.util.UUID.fromString(candidateId).toString()==candidateId && sha256.matches(Regex("[a-f0-9]{64}")))
        require("metadata" in archive.text && migrationPointerName !in archive.text && migrationPointerName !in archive.binary)
        val digest=MessageDigest.getInstance("SHA-256")
        NativeMigrationStreamArchive.write(java.security.DigestOutputStream(nullOutputStreamCompat(),digest),archive,"local-generation-v3")
        val recordsSha256=digest.digest().joinToString(""){"%02x".format(it)}
        publishMigrationGeneration(candidateId,sha256,sourceRecordCount,recordsSha256,archive.size,checkpoint,publicationObserver) { folder ->
            archive.text.keys.sorted().forEachIndexed { index,name ->
                val text=archive.text.getValue(name);val target=atomic(folder,name);encodeAtomic(target,name,text);check(decodeAtomic(target,name)==text);checkpoint(index+1)
            }
            archive.binary.toSortedMap().entries.forEachIndexed { index,(name,blob) ->
                val target=atomic(folder,name);encodeBinaryStream(target,name,blob.size,blob::verifiedCopy)
                val probe=NativeMigrationProbe();java.io.DataInputStream(target.openRead().buffered()).use { decodeBinaryTo(it,name,blob.size,probe) }
                require(probe.size==blob.size.toLong() && probe.sha256()==blob.sha256 && probe.prefix().contentEquals(blob.prefix));checkpoint(archive.text.size+index+1)
            }
        }
    }

    private fun publishMigrationGeneration(candidateId: String,sha256: String,recordCount: Int,recordsSha256: String,actualRecordCount: Int,checkpoint: (Int)->Unit,publicationObserver: PublicationObserver?=null,write: (File)->Unit): String {
        val identity=publicationObserver?.let {
            check(migrationPolicy!=null) {"Publication observation requires the strict existing writer"}
            val receipt=NativeMigrationCandidateStore.Receipt(candidateId,sha256,recordCount)
            require(it.expected==receipt) {"Publication observation must name this exact receipt"}
            receipt
        }
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        val active=activeGeneration()
        if(active!=null) {
            val prior=JSONObject(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)!!)
            if(prior.getString("candidateId")==candidateId) {
                require(prior.getString("sha256")==sha256&&prior.getString("recordsSha256")==recordsSha256&&prior.getInt("records")==actualRecordCount&&
                    (if(prior.get("version")==2)prior.getInt("sourceRecords")else prior.getInt("records"))==recordCount)
                return active
            }
        }
        requireWritable()
        // Invalidate prepared Main publications/child handles before any generation file changes.
        synchronized(recovery.publicationLock){recovery.publicationEpoch++}
        val generation=java.util.UUID.randomUUID().toString();val folder=File(root,"migration-generation-$generation")
        migrationPolicy?.beforeGeneration(this,generation)
        require(!folder.exists()&&folder.mkdir()&&folder.canonicalFile==File(root.canonicalFile,folder.name))
        var published=false
        var authorityFailure=false
        var observationFailure=false
        fun observe(point: PublicationPoint) {
            val observer=publicationObserver?:return
            try {observer.observe(point,PublicationIdentity(requireNotNull(identity),generation,actualRecordCount,recordsSha256))}
            catch(error: Throwable){observationFailure=true;throw error}
        }
        fun authority(){try{migrationPolicy?.publication(this,generation)}catch(error: Throwable){authorityFailure=true;throw error}}
        return try {
            write(folder)
            checkpoint(0)
            val pointer=JSONObject().put("version",if(actualRecordCount==recordCount)1 else 2).put("generation",generation).put("candidateId",candidateId).put("sha256",sha256).put("records",actualRecordCount).put("recordsSha256",recordsSha256)
            if(actualRecordCount!=recordCount)pointer.put("sourceRecords",recordCount)
            authority()
            val pointerText=pointer.toString()
            if(migrationPolicy!=null)migrationPublicationIntent=pointerText
            encodeAtomic(atomic(root,migrationPointerName),migrationPointerName,pointerText)
            observe(PublicationPoint.AFTER_POINTER)
            authority()
            observe(PublicationPoint.BEFORE_CONFIRMATION)
            migrationPolicy?.confirmPublication(this,generation)
            observe(PublicationPoint.AFTER_CONFIRMATION)
            published=true;synchronized(recovery.publicationLock){recovery.generation++};generation
        } catch(failure: Throwable) {
            // A finish acknowledgement can fail after the atomic pointer reached disk. Never delete a committed generation.
            val observed=runCatching {activeGeneration()}
            if(observed.isSuccess&&observed.getOrNull()==generation&&!authorityFailure&&!observationFailure) {
                try{authority()}catch(authorityError: Throwable){if(authorityError!==failure)failure.addSuppressed(authorityError);throw failure}
                migrationPolicy?.confirmPublication(this,generation)
                published=true;synchronized(recovery.publicationLock){recovery.generation++};generation
            }
            else throw failure
        } finally {
            val after=runCatching {activeGeneration()}
            if(!published&&after.isSuccess&&after.getOrNull()!=generation) {
                val children=folder.listFiles().orEmpty()
                if(children.all {it.isFile&&it.canonicalFile==File(folder.canonicalFile,it.name)&&it.name.matches(Regex("[a-f0-9]{64}\\.bin(?:\\.bak|\\.new)?"))}) {
                    children.forEach {check(it.delete())};check(folder.delete())
                }
            }
        }
    }
    /** Read-only view of a pre-existing, unmigrated namespace. No AtomicFile recovery or key creation. */
    internal class ExistingReader private constructor(private val source: NativeSessionStorage,private val inboxOnly: Boolean=false,
        private val selectionView: Pair<NativeMigrationSelectionJournal.Kind,NativeMigrationSelectionNode>?=null,private val localPhotoView: NativeMigrationPhotoLocalKind?=null): java.io.Closeable {
        private var closed=false
        fun text(name: String,maximumBytes: Int): String=source.ioLock.withLock {
            check(!closed);requireNotNull(source.existingText(name,maximumBytes,false))
        }
        fun optionalText(name: String,maximumBytes: Int): String?=source.ioLock.withLock {
            check(!closed);source.existingText(name,maximumBytes,true)
        }
        fun binary(name: String,expectedSize: Int): ByteArray=source.ioLock.withLock {
            check(!closed);source.existingBinary(name,expectedSize,12*1024*1024)
        }
        internal fun <T> inboxSnapshot(consume: (NativeMigrationStreamRecords,()->Unit)->T): T {check(inboxOnly&&!closed);return source.existingInboxSnapshot(consume)}
        internal fun <T> selectionSnapshot(legacyOutputIds: Set<String> = emptySet(),consume: (NativeMigrationStreamRecords,()->Unit)->T): T {
            check(!closed);val (kind,node)=requireNotNull(selectionView)
            require(node==NativeMigrationSelectionNode.OUTPUTS||legacyOutputIds.isEmpty())
            legacyOutputIds.forEach {require(java.util.UUID.fromString(it).toString()==it)}
            return source.existingSelectionSnapshot(kind,node,legacyOutputIds,consume)
        }
        internal fun <T> localPhotoSnapshot(consume: (NativeMigrationStreamRecords,()->Unit)->T): T {
            check(!closed);val kind=requireNotNull(localPhotoView)
            return source.existingOriginalSnapshot({_->NativeMigrationPhotoLocals.names(kind)},{text,binary->NativeMigrationPhotoLocals.validateOriginal(kind,text,binary)},consume)
        }
        fun reopen(context: Context): ExistingReader=source.ioLock.withLock {
            check(!closed);ExistingReader(source.reopen(context),inboxOnly,selectionView,localPhotoView)
        }
        override fun close()=source.ioLock.withLock {if(!closed){closed=true;source.retireWriter()}}
        companion object {
            /** Fixed debug roots only. Hand ownership over after the unchanged Source51 admission. */
            internal fun inspectNaturalOriginal(context: Context,lease: String,router: Boolean): NativeSessionStorage {
                require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
                require(java.util.UUID.fromString(lease).toString()==lease)
                val namespace=if(router)NativeChatNotificationColdRouterFiles.NAMESPACE else "P9-native-system-$lease"
                val view=open(context,namespace,"agent-hub-mobile-v1")
                try {return view.source.ioLock.withLock {
                    check(!view.closed);val source=view.source
                    source.inspection=NativeLocalInspectionFilesV2(source.existingNamespaceFence(),android.os.Process.myUid())
                    view.closed=true;source
                }}catch(failure: Throwable){view.close();throw failure}
            }
            fun open(context: Context,namespace: String,keyAlias: String): ExistingReader=openView(context,namespace,keyAlias,false)
            internal fun openInbox(context: Context,namespace: String,keyAlias: String): ExistingReader {
                require(namespace.endsWith("-external-inbox"));return openView(context,namespace,keyAlias,true)
            }
            internal fun openSelection(context: Context,namespace: String,keyAlias: String,kind: NativeMigrationSelectionJournal.Kind,node: NativeMigrationSelectionNode): ExistingReader {
                require(node!=NativeMigrationSelectionNode.OUTPUTS||kind==NativeMigrationSelectionJournal.Kind.CAMERA)
                require(namespace.endsWith("-"+kind.namespace+node.suffix))
                return openView(context,namespace,keyAlias,false,kind to node)
            }
            internal fun openPhotoLocal(context: Context,namespace: String,keyAlias: String,kind: NativeMigrationPhotoLocalKind): ExistingReader {
                require(namespace.endsWith("-"+kind.namespace));return openView(context,namespace,keyAlias,false,localPhoto=kind)
            }
            private fun openView(context: Context,namespace: String,keyAlias: String,inbox: Boolean,selection: Pair<NativeMigrationSelectionJournal.Kind,NativeMigrationSelectionNode>?=null,localPhoto: NativeMigrationPhotoLocalKind?=null): ExistingReader {
                require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,128}"))&&keyAlias.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
                val base=context.noBackupFilesDir;require(!java.nio.file.Files.isSymbolicLink(base.toPath())&&java.nio.file.Files.isDirectory(base.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                val root=File(base,namespace);val path=root.toPath()
                require(!java.nio.file.Files.isSymbolicLink(path)&&java.nio.file.Files.isDirectory(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&root.canonicalFile.parentFile==base.canonicalFile)
                val stat=android.system.Os.lstat(root.path);require(stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x3f==0)
                val pointer=MessageDigest.getInstance("SHA-256").digest("migration-active-generation-v1".toByteArray()).joinToString(""){"%02x".format(it)}+".bin"
                for(suffix in listOf("",".bak",".new"))require(java.nio.file.Files.notExists(File(root,pointer+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {"Read-only namespace has unsupported migration state"}
                val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                require(keys.getKey(keyAlias,null) is SecretKey) {"Existing key unavailable; no fallback generation"}
                return ExistingReader(NativeSessionStorage(context,namespace,keyAlias,existingKeyOnly=true,existingNamespaceOnly=true,existingReadOnly=true),inbox,selection,localPhoto)
            }
        }
    }
    private fun existingNamespaceFence(): File {
        check(existingReadOnly&&existingKeyOnly&&existingNamespaceOnly&&!retiredWriter)
        val folder=File(canonicalRoot);require(root.canonicalPath==canonicalRoot&&!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val stat=android.system.Os.lstat(folder.path);require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x3f==0)
        val pointer=File(folder,digest(migrationPointerName)+".bin")
        for(suffix in listOf("",".bak",".new"))require(java.nio.file.Files.notExists(File(pointer.path+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return folder
    }
    private fun existingText(name: String,maximumBytes: Int,optional: Boolean): String? {
        require(maximumBytes in 1..1_048_576)
        val raw=existingCipher(name,maximumBytes+65_569,optional) ?: return null
        try {require(raw[0]==3.toByte());return decodeRecord(raw,name).second.also {require(it.toByteArray(Charsets.UTF_8).size<=maximumBytes)}}finally{raw.fill(0)}
    }
    private fun existingBinary(name: String,expectedSize: Int,maximumBytes: Int): ByteArray {
        require(maximumBytes in 1..12*1024*1024&&expectedSize in 1..maximumBytes)
        val raw=requireNotNull(existingCipher(name,expectedSize+131_072+(expectedSize/65_536+1)*28,false))
        try {require(raw.size>=4&&java.nio.ByteBuffer.wrap(raw).int==4)
            return java.io.DataInputStream(java.io.ByteArrayInputStream(raw)).use {decodeBinary(it,name,expectedSize,maximumBytes).second}
        }finally{raw.fill(0)}
    }
    private fun existingCipher(name: String,maximumBytes: Int,optional: Boolean): ByteArray? {
        require(name.isNotEmpty()&&name.length<=8192&&!name.contains('\u0000')&&name!=migrationPointerName)
        val folder=existingNamespaceFence()
        val file=File(folder,digest(name)+".bin")
        for(suffix in listOf(".bak",".new"))require(java.nio.file.Files.notExists(File(file.path+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val fd=try {android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW,0)}
            catch(error: android.system.ErrnoException) {
                if(!optional||error.errno!=android.system.OsConstants.ENOENT)throw error
                existingNamespaceFence()
                for(suffix in listOf("",".bak",".new"))require(java.nio.file.Files.notExists(File(file.path+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                inspection?.requireAbsent(file.name)
                return null
            }
        var bytes: ByteArray?=null
        try {
            val before=android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(before.st_mode)&&before.st_uid==android.os.Process.myUid()&&before.st_nlink==1L&&before.st_mode and 0x3f==0&&before.st_size in 29..maximumBytes.toLong())
            val raw=ByteArray(before.st_size.toInt());bytes=raw;var offset=0
            while(offset<raw.size){val count=android.system.Os.read(fd,raw,offset,raw.size-offset);require(count>0);offset+=count}
            require(android.system.Os.read(fd,ByteArray(1),0,1)==0)
            val after=android.system.Os.fstat(fd);val path=android.system.Os.lstat(file.path)
            require(after.st_dev==before.st_dev&&after.st_ino==before.st_ino&&after.st_size==before.st_size&&after.st_mtime==before.st_mtime)
            require(android.system.OsConstants.S_ISREG(path.st_mode)&&path.st_dev==before.st_dev&&path.st_ino==before.st_ino&&path.st_size==before.st_size&&path.st_nlink==1L&&path.st_uid==before.st_uid&&path.st_mode==before.st_mode)
            existingNamespaceFence()
            for(suffix in listOf(".bak",".new"))require(java.nio.file.Files.notExists(File(file.path+suffix).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            inspection?.requireOriginal(file.name,raw)
            return raw
        }catch(error: Throwable){bytes?.fill(0);throw error}finally{android.system.Os.close(fd)}
    }
    /** Named source child only. Parent generation is decoded without AtomicFile recovery. */
    private fun migrationChildSpec(suffix: String): Triple<String,String,String> {
        require(suffix in setOf("external-inbox","document-results","photo-results","camera-results","recent-photos","photo-permission"))
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        check(!retiredWriter);checkMigrationAccess()
        val pointer=atomic(root,migrationPointerName).baseFile;requireSettledMigrationFile(pointer)
        val generation=if(java.nio.file.Files.notExists(pointer.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))null else {
            val raw=NativeMigrationReadOnlyFile(root,pointer,70_000,migrationReads).read {input->
                val bytes=input.readBytes();try{decodeRecord(bytes,migrationPointerName).second}finally{bytes.fill(0)}
            }.value
            generationFromRaw(raw)
        }
        check(writableGeneration.getOrThrow()==generation)
        val alias="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((keyAlias+"\u0000"+suffix).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
        val scoped=if(generation==null)alias else "agent-hub-child-"+digest(alias+"\u0000"+generation).take(48)
        val name=namespace+(generation?.let {"-$it"}?:"")+"-$suffix"
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        return Triple(name,scoped,File(requireNotNull(root.parentFile).canonicalFile,name).path)
    }
    private fun migrationInboxSpec()=migrationChildSpec("external-inbox")
    /** Fixed producer handles only. This neither opens a child nor grants a reader capability. */
    internal fun migrationSelectionKeys(kind: NativeMigrationSelectionJournal.Kind): List<String> = ioLock.withLock {
        val spec=migrationChildSpec(kind.namespace)
        listOf(spec.third,spec.third+"-payloads")+if(kind==NativeMigrationSelectionJournal.Kind.CAMERA)listOf(spec.third+"-outputs")else emptyList()
    }
    internal fun migrationPhotoLocalKey(kind: NativeMigrationPhotoLocalKind): String=ioLock.withLock {migrationChildSpec(kind.namespace).third}
    internal fun migrationInboxKey(): String=ioLock.withLock {migrationInboxSpec().third}
    internal fun requireInboxWriterOwner(){check(!retiredWriter);checkMigrationAccess()}
    internal fun <T> migrationInboxSnapshot(context: Context,publication: NativeMigrationOriginals.Publication?=null,consume: (NativeMigrationInboxChild)->T): T=snapshotTransaction {ioLock.withLock {
        require(context.noBackupFilesDir.canonicalFile==root.parentFile.canonicalFile)
        val spec=migrationInboxSpec();val folder=File(spec.third)
        fun requireSupportedSiblings() {
            val siblings=requireNotNull(root.parentFile.listFiles())
            require(siblings.none {it.name.startsWith(spec.first+"-")||it.name==spec.first+".bak"||it.name==spec.first+".new"}) {"Unsupported inbox child or sidecar retained"}
        }
        fun parentUnchanged(){if(publication?.matches(this)!=true)require(migrationInboxSpec()==spec);requireSupportedSiblings()}
        parentUnchanged()
        if(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val unchanged={parentUnchanged();require(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));Unit}
            return@withLock consume(NativeMigrationInboxChild(spec.third,false,null,unchanged)).also {unchanged()}
        }
        val directory=NativeMigrationReadOnlyDirectory(folder,migrationReads)
        if(directory.files.isEmpty()) {
            val unchanged={parentUnchanged();directory.unchanged();Unit}
            return@withLock consume(NativeMigrationInboxChild(spec.third,true,null,unchanged)).also {unchanged()}
        }
        ExistingReader.openInbox(context,spec.first,spec.second).use {reader->reader.inboxSnapshot {records,childUnchanged->
            val unchanged={parentUnchanged();directory.unchanged();childUnchanged();Unit}
            consume(NativeMigrationInboxChild(spec.third,true,records,unchanged)).also {unchanged()}
        }}
    }}
    /** Fixed local bookkeeping node. Same FD/cipher engine, no source-key creation or permission action. */
    internal fun <T> migrationPhotoLocalSnapshot(context: Context,kind: NativeMigrationPhotoLocalKind,publication: NativeMigrationOriginals.Publication?=null,
        consume: (NativeMigrationPhotoLocalSource)->T): T=snapshotTransaction {ioLock.withLock {
        require(context.noBackupFilesDir.canonicalFile==root.parentFile.canonicalFile&&!java.nio.file.Files.isSymbolicLink(context.noBackupFilesDir.toPath()))
        val spec=migrationChildSpec(kind.namespace);val folder=File(spec.third)
        fun parentUnchanged() {
            if(publication?.matches(this)!=true)require(migrationChildSpec(kind.namespace)==spec)
            for(sibling in requireNotNull(root.parentFile.listFiles())) {
                if(sibling.name==spec.first+".inbox-writer-v1.lock"){NativeInboxOwnership.requireControlFile(sibling);continue}
                require(!(sibling.name.startsWith(spec.first+"-")||sibling.name.startsWith(spec.first+"."))) {"Unsupported local photo node retained"}
            }
        }
        parentUnchanged()
        if(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val unchanged={parentUnchanged();require(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));Unit}
            return@withLock consume(NativeMigrationPhotoLocalSource(kind,spec.third,false,null,unchanged)).also {unchanged()}
        }
        val directory=NativeMigrationReadOnlyDirectory(folder,migrationReads)
        if(directory.files.isEmpty()) {
            val unchanged={parentUnchanged();directory.unchanged();Unit}
            return@withLock consume(NativeMigrationPhotoLocalSource(kind,spec.third,true,null,unchanged)).also {unchanged()}
        }
        ExistingReader.openPhotoLocal(context,spec.first,spec.second,kind).use {reader->reader.localPhotoSnapshot {records,childChanged->
            val unchanged={parentUnchanged();directory.unchanged();childChanged();Unit}
            consume(NativeMigrationPhotoLocalSource(kind,spec.third,true,records,unchanged)).also {unchanged()}
        }}
    }}
    /** Fixed existing-only tree. Authentication/inventory component; caller still needs the actual producer lease to publish. */
    internal fun <T> migrationSelectionSnapshot(context: Context,kind: NativeMigrationSelectionJournal.Kind,publication: NativeMigrationOriginals.Publication?=null,consume: (NativeMigrationSelectionSource)->T): T = snapshotTransaction {ioLock.withLock {
        require(!java.nio.file.Files.isSymbolicLink(context.noBackupFilesDir.toPath()))
        val base=android.system.Os.lstat(context.noBackupFilesDir.path)
        require(android.system.OsConstants.S_ISDIR(base.st_mode)&&base.st_uid==android.os.Process.myUid())
        require(context.noBackupFilesDir.canonicalFile==root.parentFile.canonicalFile)
        val top=migrationChildSpec(kind.namespace)
        fun nested(suffix: String): Triple<String,String,String> {
            val name=top.first+"-"+suffix;require(name.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
            val alias="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((top.second+"\u0000"+suffix).toByteArray()).take(24).joinToString(""){"%02x".format(it)}
            return Triple(name,alias,File(requireNotNull(root.parentFile).canonicalFile,name).path)
        }
        val specs=linkedMapOf(NativeMigrationSelectionNode.JOURNAL to top,NativeMigrationSelectionNode.PAYLOADS to nested("payloads"))
        if(kind==NativeMigrationSelectionJournal.Kind.CAMERA)specs[NativeMigrationSelectionNode.OUTPUTS]=nested("outputs")
        val names=specs.values.map {it.first}.toSet();val controls=names.map {it+".inbox-writer-v1.lock"}.toSet()
        val raw=specs[NativeMigrationSelectionNode.OUTPUTS]?.let {NativeMigrationSelectionRawInventory(context,it.third).also {view->view.requirePortableEmpty()}}
        fun parentUnchanged() {
            if(publication?.matches(this)!=true)require(migrationChildSpec(kind.namespace)==top)
            for(sibling in requireNotNull(root.parentFile.listFiles())) {
                if(sibling.name in controls){NativeInboxOwnership.requireControlFile(sibling);continue}
                if(sibling.name==top.first||sibling.name.startsWith(top.first+"-")||sibling.name.startsWith(top.first+"."))require(sibling.name in names) {"Unsupported selection child or sidecar retained"}
            }
            raw?.requirePortableEmpty()
        }
        parentUnchanged()
        fun <R> node(node: NativeMigrationSelectionNode,ids: Set<String> = emptySet(),use: (NativeMigrationSelectionSourceNode,()->Unit)->R): R {
            val spec=specs.getValue(node);val folder=File(spec.third)
            if(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                val changed={parentUnchanged();require(java.nio.file.Files.notExists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));Unit}
                return use(NativeMigrationSelectionSourceNode(false,null),changed).also {changed()}
            }
            val directory=NativeMigrationReadOnlyDirectory(folder,migrationReads)
            if(directory.files.isEmpty()) {
                val changed={parentUnchanged();directory.unchanged();Unit}
                return use(NativeMigrationSelectionSourceNode(true,null),changed).also {changed()}
            }
            return ExistingReader.openSelection(context,spec.first,spec.second,kind,node).use {reader->
                reader.selectionSnapshot(ids){records,childChanged->
                    val changed={parentUnchanged();directory.unchanged();childChanged();Unit}
                    use(NativeMigrationSelectionSourceNode(true,records),changed).also {changed()}
                }
            }
        }
        node(NativeMigrationSelectionNode.JOURNAL){journal,journalChanged->
            val ids=journal.records?.text?.get(NativeMigrationSelectionJournal.RECORD)?.let {NativeMigrationSelectionJournal.decode(it,kind).requests.map {row->row.id}.toSet()}.orEmpty()
            node(NativeMigrationSelectionNode.PAYLOADS){payloads,payloadsChanged->
                fun withOutputs(outputs: NativeMigrationSelectionSourceNode?,outputsChanged: ()->Unit): T {
                    require(journal.present||!payloads.present&&outputs?.present!=true) {"Orphan selection namespace retained"}
                    NativeMigrationSelectionGraph.validate(kind,journal.records,payloads.records)
                    val changed={parentUnchanged();journalChanged();payloadsChanged();outputsChanged();Unit}
                    return consume(NativeMigrationSelectionSource(kind,journal,payloads,outputs,raw?.status(),changed)).also {changed()}
                }
                if(kind==NativeMigrationSelectionJournal.Kind.CAMERA)node(NativeMigrationSelectionNode.OUTPUTS,ids){outputs,changed->withOutputs(outputs,changed)}
                else withOutputs(null,{})
            }
        }
    }}
    /** Strict view discovery/copy is separate from ordinary migrationStreamSnapshot and child(). */
    private fun <T> existingInboxSnapshot(consume: (NativeMigrationStreamRecords,()->Unit)->T): T = existingOriginalSnapshot(NativeMigrationInboxGraph::legacyNames,NativeMigrationInboxGraph::validate,consume)
    private fun <T> existingSelectionSnapshot(kind: NativeMigrationSelectionJournal.Kind,node: NativeMigrationSelectionNode,legacyOutputIds: Set<String>,consume: (NativeMigrationStreamRecords,()->Unit)->T): T {
        val aliases: (((String)->String?)->Set<String>)=when(node) {
            NativeMigrationSelectionNode.JOURNAL->{_->setOf(NativeMigrationSelectionJournal.RECORD)}
            NativeMigrationSelectionNode.PAYLOADS->NativeMigrationInboxGraph::legacyNames
            NativeMigrationSelectionNode.OUTPUTS->{_->legacyOutputIds.map {"output-v1-$it"}.toSet()}
        }
        return existingOriginalSnapshot(aliases,{text,binary->when(node) {
            NativeMigrationSelectionNode.JOURNAL->{require(binary.isEmpty()&&text.keys==setOf(NativeMigrationSelectionJournal.RECORD));NativeMigrationSelectionJournal.decode(text.getValue(NativeMigrationSelectionJournal.RECORD),kind);Unit}
            NativeMigrationSelectionNode.PAYLOADS->NativeMigrationInboxGraph.validate(text,binary)
            NativeMigrationSelectionNode.OUTPUTS->NativeMigrationSelectionOutputClaims.validate(text,binary)
        }},consume)
    }
    /** One strict FD/ciphertext/alias engine for inbox and the fixed selection nodes. */
    private fun <T> existingOriginalSnapshot(resolveLegacy: ((String)->String?)->Set<String>,validate: (Map<String,String>,Map<String,NativeMigrationBlob>)->Unit,
        consume: (NativeMigrationStreamRecords,()->Unit)->T): T=ioLock.withLock {
        val directory=NativeMigrationReadOnlyDirectory(existingNamespaceFence(),migrationReads);val files=directory.files;require(files.isNotEmpty())
        fun version(source: NativeMigrationReadOnlyFile)=source.read(false,NativeMigrationLocalRecordFormat::read).value
        fun text(source: NativeMigrationReadOnlyFile,expected: String?): NativeMigrationReadOnlyFile.Read<Pair<String,String>> {
            existingNamespaceFence()
            return source.read {input->
                require(source.identity.size<=NativeMigrationTextFrames.MAX_RECORD_BYTES+65_569L)
                val bytes=input.readBytes()
                try {
                    NativeMigrationLegacyGcm.limit(bytes[0].toInt(),bytes.size.toLong(),NativeMigrationTextFrames.MAX_RECORD_BYTES,Runtime.getRuntime().maxMemory())?.let {throw LegacyGcmAuthenticationLimit(it)}
                    decodeRecord(bytes,expected)
                }finally{bytes.fill(0)}
            }.also {existingNamespaceFence()}
        }
        val versions=files.mapValues {version(it.value)}
        val modern=files.mapNotNull {(filename,source)->when(versions.getValue(filename)) {
            3->text(source,null).value.first
            4->source.read(false){binaryHeader(java.io.DataInputStream(it),null,null).name}.value
            1,2->null
            else->error("Unknown inbox ciphertext retained")
        }}.toSet()
        fun read(name: String)=files[digest(name)+".bin"]?.let {text(it,name).value.second}
        val aliases=(modern+resolveLegacy(::read)).associateBy {digest(it)+".bin"}
        require(aliases.size<=1024)
        val texts=linkedMapOf<String,NativeMigrationBlob>();val binaries=linkedMapOf<String,NativeMigrationBlob>()
        for((filename,source)in files.toSortedMap()) {
            val name=aliases.getValue(filename);require(filename==digest(name)+".bin")
            if(versions.getValue(filename) in setOf(1,3)) {
                val decoded=text(source,name);val bytes=NativeMigrationTextFrames.bytes(decoded.value.second)
                val probe=try {NativeMigrationProbe().also {it.write(bytes)}}finally{bytes.fill(0)}
                val sha=decoded.ciphertextSha256
                val blob=NativeMigrationBlob(probe.size.toInt(),probe.sha256(),probe.prefix()) {output->
                    val current=text(source,name);require(current.ciphertextSha256==sha)
                    val value=NativeMigrationTextFrames.bytes(current.value.second);try{output.write(value)}finally{value.fill(0)}
                }
                require(texts.put(name,blob)==null)
            }else {
                existingNamespaceFence();val probe=NativeMigrationProbe()
                val initial=source.read {decodeBinaryTo(java.io.DataInputStream(it),name,null,probe)}
                val size=initial.value.size;val sha=initial.ciphertextSha256
                val blob=NativeMigrationBlob(size,probe.sha256(),probe.prefix()) {output->
                    existingNamespaceFence();val observed=source.read {decodeBinaryTo(java.io.DataInputStream(it),name,size,output)}
                    require(observed.ciphertextSha256==sha);existingNamespaceFence()
                }
                require(binaries.put(name,blob)==null)
            }
        }
        val records=NativeMigrationStreamRecords(NativeMigrationTextMap(texts),binaries,4)
        validate(records.text,records.binary)
        val unchanged={existingNamespaceFence();directory.unchanged();Unit}
        consume(records,unchanged).also {unchanged()}
    }
    /** Only immutable native-return audit may follow the current root generation after publication.
     * This does not reopen ordinary writes, a child, a URI, an OS grant or a remote operation.
     * Global snapshot then root IO ordering matches export/activation. One AtomicFile is the receipt.
     */
    internal fun persistSelectionReturn(context: Context,event: NativeSelectionReturnAudit.Event): Boolean = snapshotTransaction {ioLock.withLock {
        check(!existingReadOnly)
        require(root.canonicalPath==canonicalRoot&&!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val stat=android.system.Os.lstat(root.path)
        require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x3f==0)
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile)
        // Existing key only, even if the original Session was retired or is restoring its model.
        val current=NativeSessionStorage(context,namespace,keyAlias,existingKeyOnly=true,existingNamespaceOnly=true)
        try {
            current.writableGeneration.getOrThrow();val folder=current.dataRoot()
            val currentStat=android.system.Os.lstat(folder.path)
            require(!java.nio.file.Files.isSymbolicLink(folder.toPath())&&android.system.OsConstants.S_ISDIR(currentStat.st_mode)&&currentStat.st_uid==android.os.Process.myUid()&&currentStat.st_mode and 0x3f==0)
            require(folder.canonicalFile==File(root.canonicalFile,folder.name)||folder.canonicalFile==root.canonicalFile)
            val record=NativeSelectionReturnAudit.RECORD;val target=current.atomic(folder,record)
            current.requireSettledMigrationFile(target.baseFile)
            fun readAudit(): String {
                val ref=NativeMigrationReadOnlyFile(folder,target.baseFile,NativeMigrationTextFrames.MAX_RECORD_BYTES+65_569)
                return ref.read {input->val bytes=input.readBytes();try{require(bytes[0]==3.toByte());current.decodeRecord(bytes,record).second}finally{bytes.fill(0)}}.value
            }
            val before=if(current.migrationFileExists(target.baseFile))readAudit()else null
            val admission=NativeSelectionReturnAudit.admit(before,event)
            if(before!=admission.raw)current.encodeAtomic(target,record,admission.raw)
            current.requireSettledMigrationFile(target.baseFile)
            require(readAudit()==admission.raw)
            admission.acceptsOriginalReturn
        }finally {current.retireWriter()}
    }}
    internal fun reopen(context: Context): NativeSessionStorage {
        rejectInspectionMutation()
        return NativeSessionStorage(context,namespace,keyAlias,existingKeyOnly,existingNamespaceOnly,existingReadOnly,false,migrationKeys?:migrationRoot?.migrationKeys).also {
            it.migrationPolicy=migrationPolicy;it.migrationRoot=migrationRoot;it.migrationChildPath=migrationChildPath;it.migrationScope=migrationScope;it.migrationAccess=migrationAccess
        }
    }
    /** Main calls only after joining producers and the serial writer, then replacing the Session. */
    internal fun retireWriter() {retiredWriter=true;writes.shutdown()}
    // Audio stays binary and bounded in memory. Each chunk authenticates its name,
    // total length and position, so truncation/reordering cannot publish plaintext.
    // Binary format4 authenticates an encrypted logical name and length before chunks.
    // The non-UTF-8 domain cannot be a legacy name; format2 stays read-only compatible.
    private val binaryContext=byteArrayOf(0xff.toByte(),4)+"agent-hub/android-local-binary/v4".toByteArray(Charsets.UTF_8)
    private data class BinaryHeader(val name: String,val size: Int,val nonce: ByteArray?)
    private fun binaryName(name: String): ByteArray {
        val bytes=strictBytes(name)
        require(name.isNotEmpty()&&name.length<=16384&&bytes.size<=65536)
        return bytes
    }
    private fun binaryHeader(input: java.io.DataInputStream,name: String?,expectedSize: Int?): BinaryHeader {
        val version=input.readInt()
        if(version==2) {val size=input.readInt();require(size in 1..21*1024*1024&&(expectedSize==null||size==expectedSize));return BinaryHeader(requireNotNull(name),size,null)}
        require(version==4) {"录音文件格式已变化"}
        val length=input.readInt();require(length in 25..65560)
        val iv=ByteArray(12);input.readFully(iv);val encrypted=ByteArray(length);input.readFully(encrypted)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));updateAAD(binaryContext)}
        val clear=crypt(cipher,encrypted)
        try {
            val frame=java.nio.ByteBuffer.wrap(clear);val count=frame.int
            require(count in 1..65536&&frame.remaining()==count+4)
            val encoded=ByteArray(count);frame.get(encoded)
            val actual=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(encoded)).toString()
            val size=frame.int
            require(binaryName(actual).contentEquals(encoded)&&(name==null||actual==name)&&size in 1..21*1024*1024&&(expectedSize==null||size==expectedSize))
            return BinaryHeader(actual,size,iv)
        }finally {clear.fill(0)}
    }
    private fun writeBinaryHeader(out: java.io.DataOutputStream,name: String,size: Int): ByteArray {
        val encoded=binaryName(name);val clear=java.nio.ByteBuffer.allocate(encoded.size+8).putInt(encoded.size).put(encoded).putInt(size).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(binaryContext)}
        val encrypted=try {crypt(cipher,clear)}finally {clear.fill(0)}
        out.writeInt(4);out.writeInt(encrypted.size);out.write(cipher.iv);out.write(encrypted);return cipher.iv
    }
    private fun encodeBinaryAtomic(atomic: AtomicFile,name: String,input: java.io.InputStream,size: Int) {
        require(size in 1..21*1024*1024);binaryName(name);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);val stream=atomic.startWrite()
        try {
            val out=java.io.DataOutputStream(stream);val nonce=writeBinaryHeader(out,name,size)
            val buffer=ByteArray(65536);var offset=0;var index=0
            while(offset<size) {
                val count=minOf(buffer.size,size-offset);java.io.DataInputStream(input).readFully(buffer,0,count)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(binaryAAD(name,size,index++,nonce))}
                out.write(cipher.iv);out.write(crypt(cipher,buffer.copyOf(count)));offset+=count
            }
            require(input.read()==-1) {"录音在保存期间发生变化"}
            out.flush();migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);atomic.finishWrite(stream)
        }catch(error: Throwable){atomic.failWrite(stream);throw error}
    }
    internal fun writeBinary(name: String,source: File,maximumBytes: Int=12*1024*1024)=ioLock.withLock {
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);requireWritable();binaryName(name);require(maximumBytes in 1..21*1024*1024)
        val size=source.length();require(size in 1..maximumBytes.toLong())
        source.inputStream().use {encodeBinaryAtomic(file(name),name,it,size.toInt())}
    }
    /** Bounded caller-owned bytes; no plaintext scratch file or text/base64 Keystore envelope. */
    internal fun writeBinaryBytes(name: String,bytes: ByteArray,maximumBytes: Int)=ioLock.withLock {
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);requireWritable();binaryName(name);require(maximumBytes in 1..21*1024*1024&&bytes.size in 1..maximumBytes)
        encodeBinaryAtomic(file(name),name,java.io.ByteArrayInputStream(bytes),bytes.size)
    }
    private fun decodeBinary(input: java.io.DataInputStream,name: String?,expectedSize: Int?,maximumBytes: Int): Pair<BinaryHeader,ByteArray> {
        val header=binaryHeader(input,name,expectedSize);require(header.size in 1..maximumBytes)
        val result=ByteArray(header.size);var offset=0;var index=0
        try {
            while(offset<header.size) {
                val count=minOf(65536,header.size-offset);val iv=ByteArray(12);input.readFully(iv)
                val encrypted=ByteArray(count+16);input.readFully(encrypted)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));updateAAD(binaryAAD(header.name,header.size,index++,header.nonce))}
                val clear=crypt(cipher,encrypted)
                try {require(clear.size==count);System.arraycopy(clear,0,result,offset,count);offset+=count}finally {clear.fill(0)}
            }
            require(input.read()==-1) {"录音文件含多余内容"};return header to result
        }catch(failure: Throwable){result.fill(0);throw failure}
    }
    internal fun readBinary(name: String,expectedSize: Int,maximumBytes: Int=12*1024*1024): ByteArray=ioLock.withLock {
        if(existingReadOnly)return@withLock existingBinary(name,expectedSize,maximumBytes)
        checkMigrationAccess();binaryName(name);require(maximumBytes in 1..21*1024*1024&&expectedSize in 1..maximumBytes)
        java.io.DataInputStream(file(name).openRead()).use {decodeBinary(it,name,expectedSize,maximumBytes).second}
    }
    /** Authenticated bounded binary delivery. The consumer publishes only after this returns. */
    private fun decodeBinaryTo(input: java.io.DataInputStream, name: String?, size: Int?, output: java.io.OutputStream): BinaryHeader {
        val header = binaryHeader(input, name, size); var offset = 0; var index = 0
        while (offset < header.size) {
            val count = minOf(65536, header.size - offset); val iv = ByteArray(12); input.readFully(iv)
            val encrypted = ByteArray(count + 16); input.readFully(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(binaryAAD(header.name, header.size, index++, header.nonce))
            }
            val clear = crypt(cipher, encrypted)
            try { require(clear.size == count); output.write(clear); offset += count } finally { clear.fill(0) }
        }
        require(input.read() == -1); return header
    }
    internal fun copyBinaryTo(name: String, expectedSize: Int, output: java.io.OutputStream) = ioLock.withLock {
        checkMigrationAccess(); require(expectedSize in 1..21 * 1024 * 1024)
        java.io.DataInputStream(file(name).openRead().buffered()).use { decodeBinaryTo(it, name, expectedSize, output) }; Unit
    }
    /** Candidate/audit originals never invoke AtomicFile recovery, including payload chunks. */
    internal fun migrationCopyBinaryTo(name: String,expectedSize: Int,output: java.io.OutputStream)=ioLock.withLock {
        checkMigrationAccess();require(expectedSize in 1..21*1024*1024)
        requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable()
        val source=file(name).baseFile;requireSettledMigrationFile(source)
        migrationReadInput(source){decodeBinaryTo(java.io.DataInputStream(it),name,expectedSize,output)};Unit
    }
    internal fun migrationReadBinary(name: String,expectedSize: Int,maximum: Int): ByteArray {
        require(expectedSize in 1..maximum&&maximum<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val output=java.io.ByteArrayOutputStream(expectedSize);migrationCopyBinaryTo(name,expectedSize,output);return output.toByteArray()
    }
    private fun encodeBinaryStream(atomic: AtomicFile, name: String, size: Int, produce: (java.io.OutputStream) -> Unit) {
        require(size in 1..21 * 1024 * 1024); migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);val stream = atomic.startWrite(); val buffer = ByteArray(65536)
        try {
            val out = java.io.DataOutputStream(stream); val nonce = writeBinaryHeader(out, name, size)
            var used = 0; var total = 0; var index = 0
            fun chunk() {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(binaryAAD(name, size, index++, nonce)) }
                val clear = buffer.copyOf(used)
                try { out.write(cipher.iv); out.write(crypt(cipher, clear)) } finally { clear.fill(0); buffer.fill(0) }; used = 0
            }
            produce(object : java.io.OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    require(offset >= 0 && count >= 0 && offset <= bytes.size - count && count <= size - total)
                    total += count; var position = offset; var remaining = count
                    while (remaining > 0) {
                        val n = minOf(buffer.size - used, remaining); System.arraycopy(bytes, position, buffer, used, n)
                        used += n; position += n; remaining -= n; if (used == buffer.size) chunk()
                    }
                }
            })
            require(total == size); if (used > 0) chunk(); out.flush(); migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error } finally { buffer.fill(0) }
    }
    internal fun writeBinaryStream(name: String, size: Int, produce: (java.io.OutputStream) -> Unit) = ioLock.withLock {
        migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);requireWritable(); encodeBinaryStream(file(name), name, size, produce);migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name)
    }
    /** Discovery, verification and export share one generation lock. No whole binary or Base64 value. */
    internal fun <T> migrationStreamSnapshot(resolveLegacyNames: ((String) -> String?) -> Set<String>, consume: (NativeMigrationStreamRecords) -> T): T =
        migrationStreamSnapshot(resolveLegacyNames,{_,_->emptySet()},consume)
    internal fun <T> migrationStreamSnapshot(resolveLegacyNames: ((String) -> String?) -> Set<String>,resolveModernAliases: (Set<String>,(String)->String?)->Set<String>, consume: (NativeMigrationStreamRecords) -> T): T =
        migrationOptionalStreamSnapshot(resolveLegacyNames,resolveModernAliases){consume(requireNotNull(it))}
    /** Same authenticated inventory engine; the destination may have no root records yet. */
    internal fun <T> migrationOptionalStreamSnapshot(resolveLegacyNames: ((String) -> String?) -> Set<String>,resolveModernAliases: (Set<String>,(String)->String?)->Set<String>, consume: (NativeMigrationStreamRecords?) -> T): T =
        snapshotTransaction { ioLock.withLock {
            requireSettledMigrationFile(atomic(root,migrationPointerName).baseFile);requireWritable(); val files = migrationFiles(dataRoot()).associateBy { it.name }; require(files.size<=1024)
            if(files.isEmpty())return@withLock consume(null)
            if(strictMigration) {
                val originalFolder=requireNotNull(files.values.first().parentFile)
                val pointerFile=digest(migrationPointerName)+".bin"
                val originalNames=files.keys-pointerFile
                val refs=files.mapValues {migrationReadOnlyFile(it.value)}
                fun unchanged(){check(migrationFiles(originalFolder).map {it.name}.toSet()-pointerFile==originalNames);refs.values.forEach {it.unchanged()}}
                val aliases=migrationAliases(files,resolveLegacyNames,resolveModernAliases)
                val text=linkedMapOf<String,NativeMigrationBlob>();val binaries=linkedMapOf<String,NativeMigrationBlob>()
                for((filename,ref)in refs.toSortedMap()) {
                    val version=ref.read(false){NativeMigrationLocalRecordFormat.read(it)}.value
                    val name=if(version==1||version==3) {
                        val (logical,blob)=migrationTextFrame(ref.file,aliases[filename],ref)
                        require(text.put(logical,blob)==null);logical
                    }else {
                        val probe=NativeMigrationProbe()
                        val header=ref.read {decodeBinaryTo(java.io.DataInputStream(it),aliases[filename],null,probe)}.value
                        val blob=NativeMigrationBlob(header.size,probe.sha256(),probe.prefix()){out->
                            requireSettledMigrationFile(ref.file)
                            ref.read {decodeBinaryTo(java.io.DataInputStream(it),header.name,header.size,out)}
                            requireSettledMigrationFile(ref.file)
                        }
                        require(binaries.put(header.name,blob)==null);header.name
                    }
                    require(name.isNotEmpty()&&name.length<=8192&&!name.contains('\u0000')&&name!=migrationPointerName&&filename==digest(name)+".bin")
                }
                unchanged()
                return@withLock consume(NativeMigrationStreamRecords(NativeMigrationTextMap(text),binaries,4)).also {unchanged()}
            }
            val aliases=migrationAliases(files,resolveLegacyNames,resolveModernAliases)
            val text = linkedMapOf<String, NativeMigrationBlob>(); val binaries = linkedMapOf<String, NativeMigrationBlob>()
            for ((filename, source) in files.toSortedMap()) {
                require(source.length()>=29)
                java.io.DataInputStream(source.inputStream().buffered()).use { input ->
                    input.mark(4); val version = input.readUnsignedByte(); input.reset()
                    val name: String
                    if (version == 1 || version == 3) {
                        val decoded=migrationTextFrame(source,aliases[filename]);name=decoded.first
                        require(text.put(name,decoded.second)==null)
                    } else {
                        require(source.length()<=23L * 1024 * 1024)
                        val probe = NativeMigrationProbe(); val header = decodeBinaryTo(input, aliases[filename], null, probe); name = header.name
                        val blob = NativeMigrationBlob(header.size, probe.sha256(), probe.prefix()) { output ->
                            // Use the discovered exact file, not AtomicFile recovery or a newly selected generation.
                            java.io.DataInputStream(source.inputStream().buffered()).use { decodeBinaryTo(it, name, header.size, output) }
                        }
                        require(binaries.put(name, blob) == null)
                    }
                    require(name.isNotEmpty() && name.length <= 8192 && !name.contains('\u0000') && name != migrationPointerName && filename == digest(name) + ".bin")
                }
            }
            consume(NativeMigrationStreamRecords(NativeMigrationTextMap(text),binaries,4))
        } }
    private fun binaryAAD(name: String,size: Int,index: Int,nonce: ByteArray?)=
        (if(nonce==null)byteArrayOf()else binaryContext+nonce+byteArrayOf(0))+strictBytes(name)+byteArrayOf(0)+java.nio.ByteBuffer.allocate(8).putInt(size).putInt(index).array()
    // Feed bounded updates to Keystore on older Android providers as well.
    // Decryption is published only after the final GCM authentication succeeds.
    private fun crypt(cipher: Cipher,bytes: ByteArray,start: Int=0,prefix: ByteArray?=null): ByteArray {
        val out=java.io.ByteArrayOutputStream()
        fun update(input: ByteArray,begin: Int) {
            var offset=begin
            while(offset<input.size) {
                val count=minOf(16_384,input.size-offset);cipher.update(input,offset,count)?.let {out.write(it)};offset+=count
            }
        }
        prefix?.let {update(it,0)};update(bytes,start)
        out.write(cipher.doFinal());return out.toByteArray()
    }
    // Immutable snapshots are queued in order. Callers receive storage failures on Main, never silently discard drafts.
    fun enqueue(name: String, text: String, failed: (Exception) -> Unit) {
        rejectInspectionMutation()
        val generation=recovery.generation
        writes.execute { try { ioLock.withLock {
            check(generation==recovery.generation) { "旧的保存已被本地恢复替代" };write(name,text)
        };queuedWriteFailures.remove(name) } catch (error: Exception) { queuedWriteFailures[name]=error;failed(error) } catch(_: OutOfMemoryError) { val error=IllegalStateException("本地存储内存不足，原文件已保留");queuedWriteFailures[name]=error;failed(error) } } }
    /** Exact voice destination acceptance, ordered with metadata producers. Cancellation joins the
     * actual executor write/readback; it cannot report an idle owner while this work still runs.
     */
    internal suspend fun writeVoiceDestinationMetadata(snapshot: String,checkpoint: (Int)->Unit={}): String {
        val generation=recovery.generation;val done=kotlinx.coroutines.CompletableDeferred<String>()
        writes.execute {
            try {
                val digest=ioLock.withLock {
                    check(generation==recovery.generation);requireWritable();checkpoint(0)
                    write("metadata",snapshot);checkpoint(1)
                    val raw=migrationReadTextRecords(setOf("metadata"))["metadata"] ?: error("Voice destination missing")
                    require(raw==snapshot);checkpoint(2);voiceSHA(raw.toByteArray(Charsets.UTF_8))
                }
                queuedWriteFailures.remove("metadata");done.complete(digest)
            }catch(failure: Exception){queuedWriteFailures["metadata"]=failure;done.completeExceptionally(failure)}
             catch(_: OutOfMemoryError){val failure=IllegalStateException("Voice destination persistence failed");queuedWriteFailures["metadata"]=failure;done.completeExceptionally(failure)}
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){done.await()}
    }
    // History reads may arrive faster than disk encryption. Keep the newest
    // immutable snapshot per target and serialize on the storage worker.
    // Submission intent persistence continues to use the synchronous write.
    fun enqueueSnapshot(name: String, save: ()->Unit, failed: (Exception)->Unit) {
        rejectInspectionMutation()
        val generation=recovery.generation
        val guardedSave: ()->Unit = {
            val previous=recovery.queuedGeneration.get()
            recovery.queuedGeneration.set(generation)
            try { ioLock.withLock { requireWritable() };save() }
            finally { if(previous==null) recovery.queuedGeneration.remove() else recovery.queuedGeneration.set(previous) }
        }
        val schedule=synchronized(snapshotLock) { snapshots[name]=guardedSave to failed; scheduledSnapshots.add(name) }
        if(!schedule) return
        writes.execute {
            while(true) {
                val next=synchronized(snapshotLock) { snapshots.remove(name).also { if(it==null) scheduledSnapshots.remove(name) } } ?: break
                try { next.first();queuedWriteFailures.remove(name) } catch(error: Exception) { queuedWriteFailures[name]=error;next.second(error) } catch(_: OutOfMemoryError) { val error=IllegalStateException("本地缓存内存不足，原文件已保留");queuedWriteFailures[name]=error;next.second(error) }
            }
        }
    }
    internal fun <T> snapshotTransaction(block: ()->T): T = synchronized(SNAPSHOT_IO_LOCK,block)
    // A retry fence must be consumed atomically across journal instances too.
    internal fun <T> operationTransaction(block: ()->T): T = ioLock.withLock(block)
    internal val coordinationKey get()=canonicalRoot
    /** Memory-only ownership admission; strict views and retired writers cannot create raw coordination files. */
    internal fun requireRawWriterOwner(){check(!retiredWriter);checkMigrationAccess()}
    /** IO prepares only named native runtime children. A later publication invalidates the cache. */
    internal fun prepareChild(context: Context,suffix: String): NativeSessionStorage = ioLock.withLock {
        requireCurrentGeneration();check(!retiredWriter)
        child(context,suffix).also {preparedChildren[suffix]=PreparedChild(recovery.publicationEpoch,it);migrationPolicy?.childPrepared(migrationAccess,listOfNotNull(migrationChildPath,suffix).joinToString("/"))}
    }
    internal fun child(context: Context,suffix: String): NativeSessionStorage {
        rejectInspectionMutation()
        check(!existingReadOnly) { "Read-only views cannot create child storage" }
        require(suffix.matches(Regex("[a-z0-9-]{1,32}")))
        preparedChildren[suffix]?.let {prepared->return synchronized(recovery.publicationLock) {
            check(!retiredWriter&&migrationUnavailable==null&&writableGeneration.isSuccess&&prepared.epoch==recovery.publicationEpoch) { "已准备的本地存储已被新的迁移替代" }
            migrationPolicy?.requireHandle(migrationAccess)
            prepared.storage
        }}
        migrationPolicy?.let{return existingMigrationChild(context,suffix,it)}
        val childAlias="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((keyAlias+"\u0000"+suffix).toByteArray()).take(24).joinToString("") { "%02x".format(it) }
        val observed=ioLock.withLock {runCatching {requireCurrentGeneration();activeGeneration()}}
        val generation=observed.getOrNull()
        val scopedAlias=if(generation==null)childAlias else "agent-hub-child-"+digest(childAlias+"\u0000"+generation).take(48)
        return NativeSessionStorage(context,namespace+(generation?.let {"-"+it} ?: "")+"-"+suffix,scopedAlias,existingKeyOnly).also {it.migrationUnavailable=observed.exceptionOrNull()}
    }
    /** Caller first gates producers; the actual serial writer also reports unresolved save failures. */
    internal suspend fun awaitQueuedWrites() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { waiting ->
        writes.execute { if(waiting.isActive)waiting.resumeWith(if(queuedWriteFailures.isEmpty())Result.success(Unit)
            else Result.failure(IllegalStateException("本机仍有保存失败的资料，原内容已保留。"))) }
    }
    internal fun storedBytes(name: String): Long = ioLock.withLock { checkMigrationAccess();file(name).baseFile.length() }
    fun remove(name: String) = ioLock.withLock { migrationPolicy?.requireWrite(migrationAccess,migrationChildPath,name);requireWritable();file(name).delete() }
    fun token(id: String) = CodexConnectionVault(this).token(id) ?: read("token:$id").orEmpty()
    fun setToken(id: String, value: String) = operationTransaction {
        if (value.isEmpty()) remove("token:$id") else write("token:$id", value)
        // Explicit manual/QR replacement supersedes the private connection record only after the write succeeds.
        CodexConnectionVault(this).remove(id)
    }
    fun metadata(): JSONObject? = read("metadata")?.let(::JSONObject)
}
