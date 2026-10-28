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
class NativeSessionStorage(context: Context, private val namespace: String = "mobile-v1", private val keyAlias: String = "agent-hub-mobile-v1") {
    init { require(keyAlias.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) }
    private val root = File(context.noBackupFilesDir, namespace).apply { mkdirs() }
    // AtomicFile's older reader may restore its backup while another instance
    // is writing. Coordinate all instances for this directory in this process.
    private val ioLock = IO_LOCKS.computeIfAbsent(root.canonicalPath) { ReentrantLock(true) }
    private val recovery = RECOVERY.computeIfAbsent(root.canonicalPath) { RecoveryFence() }
    private val writes = Executors.newSingleThreadExecutor()
    private val snapshotLock=Any()
    private val snapshots=mutableMapOf<String,Pair<()->Unit,(Exception)->Unit>>()
    private val scheduledSnapshots=mutableSetOf<String>()
    private val key: SecretKey by lazy {
        synchronized(KEY_LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private class RecoveryFence(var blocked: Boolean=false,@Volatile var generation: Long=0) { val queuedGeneration=ThreadLocal<Long>() }
    internal fun beginRestore(): Long = ioLock.withLock { requireCurrentGeneration();recovery.blocked=true;++recovery.generation }
    internal fun finishRestore(generation: Long,publish: ()->Unit) = ioLock.withLock {
        requireCurrentGeneration()
        check(recovery.generation==generation) { "本地读取已被新的恢复替代" }
        publish()
        recovery.blocked=false
    }
    private var migrationUnavailable: Throwable?=null
    private fun checkMigrationAccess() {migrationUnavailable?.let {throw IllegalStateException("迁入数据尚未恢复，原记录已保留",it)}}
    private fun requireCurrentGeneration() {
        checkMigrationAccess()
        check(writableGeneration.getOrThrow()==activeGeneration()) { "旧安装的保存不能覆盖迁入数据" }
    }
    private fun requireWritable() {
        requireCurrentGeneration()
        check(!recovery.blocked) { "本地数据恢复前不能保存，原文件已保留" }
        check(recovery.queuedGeneration.get()?.let { it==recovery.generation }!=false) { "旧的保存已被本地恢复替代" }
    }
    companion object { private val RECOVERY=ConcurrentHashMap<String,RecoveryFence>(); private val KEY_LOCK = Any(); private val SNAPSHOT_IO_LOCK=Any(); private val IO_LOCKS=ConcurrentHashMap<String,ReentrantLock>() }
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
        val bytes=try {atomic.readFully()}catch(_: java.io.FileNotFoundException){return null}
        return decodeRecord(bytes,name).second
    }
    private fun encodeAtomic(atomic: AtomicFile,name: String,text: String) {
        val nameBytes=strictBytes(name);require(name.isNotEmpty()&&name.length<=16384&&nameBytes.size<=65536)
        val textBytes=strictBytes(text)
        val header=java.nio.ByteBuffer.allocate(4+nameBytes.size).putInt(nameBytes.size).put(nameBytes).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(recordContext)}
        val encrypted=try{byteArrayOf(3)+cipher.iv+crypt(cipher,textBytes,prefix=header)}finally{header.fill(0);textBytes.fill(0)}
        val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)}catch(error: Throwable){atomic.failWrite(stream);throw error}
    }
    private fun activeGeneration(): String? {
        val raw=decodeAtomic(atomic(root,migrationPointerName),migrationPointerName) ?: return null
        require(raw.length<=1024)
        val value=JSONObject(raw);require(value.keys().asSequence().toSet()==setOf("version","generation","candidateId","sha256","records","recordsSha256")&&value.get("version")==1)
        val generation=value.getString("generation");require(java.util.UUID.fromString(generation).toString()==generation)
        require(java.util.UUID.fromString(value.getString("candidateId")).toString()==value.getString("candidateId"))
        require(value.getString("sha256").matches(Regex("[a-f0-9]{64}"))&&value.getString("recordsSha256").matches(Regex("[a-f0-9]{64}"))&&value.getInt("records") in 1..1024)
        val folder=File(root,"migration-generation-$generation");require(folder.isDirectory&&folder.canonicalFile==File(root.canonicalFile,folder.name)) { "迁入数据不可用，未回退到旧数据" }
        return generation
    }
    private val writableGeneration=ioLock.withLock {runCatching {activeGeneration()}}
    private fun dataRoot(): File=activeGeneration()?.let {File(root,"migration-generation-$it")} ?: root
    private fun file(name: String): AtomicFile {require(name!=migrationPointerName);return atomic(dataRoot(),name)}
    fun read(name: String): String? = ioLock.withLock {checkMigrationAccess();decodeAtomic(file(name),name)}
    fun write(name: String,text: String)=ioLock.withLock {requireWritable();encodeAtomic(file(name),name,text)}

    internal class IncompleteMigrationInventory: Exception("本机迁移记录不完整或包含未支持资料，原数据未更改。")
    /** Known system sub-stores live beside the root, not inside its hashed-record inventory.
     * Presence is audited without opening/recovering files or borrowing OS callback authority. */
    internal fun migrationPendingChildScopes(): Set<String> = ioLock.withLock {
        requireWritable()
        val generation=activeGeneration();val prefix=namespace+(generation?.let {"-$it"}?:"")+"-"
        val systemScopes=setOf("external-inbox","recent-photos","photo-permission","document-results","photo-results","camera-results")
        val siblings=root.parentFile!!.listFiles()?:error("Migration child inventory unavailable")
        siblings.mapNotNull { folder ->
            val suffix=folder.name.removePrefix(prefix)
            val scope=systemScopes.singleOrNull { suffix==it || suffix.startsWith("$it-") }
            if(!folder.name.startsWith(prefix)||scope==null)null else {
                require(folder.canonicalFile==File(root.parentFile!!.canonicalFile,folder.name)&&folder.isDirectory)
                val children=folder.listFiles()?:error("Migration child inventory unavailable")
                if(children.isEmpty())null else scope
            }
        }.toSet()
    }
    /** Resolve exact legacy references and capture all names/text under the same generation lock.
     * Reading for discovery never invokes AtomicFile recovery or changes a pending .bak/.new. */
    internal fun migrationArchiveSnapshot(resolveLegacyNames: ((String)->String?)->Set<String>): Map<String,String> = try {
        snapshotTransaction {ioLock.withLock {
            requireWritable();val files=migrationFiles(dataRoot()).associateBy{it.name}
            val aliases=resolveLegacyNames {name->
                files[digest(name)+".bin"]?.let{file->
                    require(file.length() in 29..(NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES+65569).toLong())
                    decodeRecord(file.readBytes(),name).second
                }
            }
            migrationSnapshot(migrationNames(aliases))
        }}
    }catch(_: Exception){throw IncompleteMigrationInventory()}
    /** Typed root snapshot; discovers authenticated format4 names and exact indexed legacy2 aliases.
     * Discovery never restores AtomicFile backups or silently skips an unsupported record. */
    internal fun migrationTypedArchiveSnapshot(resolveLegacyNames: ((String)->String?)->Set<String>,maximumBytes: Int=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES): NativeMigrationRecords = try {
        snapshotTransaction {ioLock.withLock {
            requireWritable();require(maximumBytes in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            val files=migrationFiles(dataRoot()).associateBy{it.name};require(files.size in 1..1024)
            val aliases=resolveLegacyNames {name->files[digest(name)+".bin"]?.let {source->
                require(source.length() in 29..(maximumBytes+65569L));decodeRecord(source.readBytes(),name).second
            }}.associateBy{digest(it)+".bin"}
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
    }catch(_: Exception){throw IncompleteMigrationInventory()}
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
            requireWritable();require(legacyNames.size<=1024)
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
            requireWritable()
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
        checkMigrationAccess()
        val generation=activeGeneration() ?: return@withLock null
        val pointer=JSONObject(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)!!)
        if(pointer.getString("candidateId")!=candidateId)return@withLock null
        require(pointer.getString("sha256")==sha256&&pointer.getInt("records")==records)
        generation
    }

    /** One authenticated pointer is the commit point; staged generations are never active on failure. */
    internal fun publishMigration(candidateId: String,sha256: String,records: Map<String,String>,checkpoint: (Int)->Unit={}): String =
        publishMigrationRecords(candidateId,sha256,NativeMigrationRecords(records),checkpoint)

    internal fun publishMigrationRecords(candidateId: String,sha256: String,archive: NativeMigrationRecords,checkpoint: (Int)->Unit={}): String = ioLock.withLock {
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
        publishMigrationGeneration(candidateId,sha256,archive.size,recordsSha256,checkpoint) { folder ->
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

    internal fun publishStreamMigration(candidateId: String, sha256: String, archive: NativeMigrationStreamRecords, checkpoint: (Int) -> Unit = {}): String = ioLock.withLock {
        require(java.util.UUID.fromString(candidateId).toString()==candidateId && sha256.matches(Regex("[a-f0-9]{64}")))
        require("metadata" in archive.text && migrationPointerName !in archive.text && migrationPointerName !in archive.binary)
        val digest=MessageDigest.getInstance("SHA-256")
        NativeMigrationStreamArchive.write(java.security.DigestOutputStream(nullOutputStreamCompat(),digest),archive,"local-generation-v3")
        val recordsSha256=digest.digest().joinToString(""){"%02x".format(it)}
        publishMigrationGeneration(candidateId,sha256,archive.size,recordsSha256,checkpoint) { folder ->
            archive.text.toSortedMap().entries.forEachIndexed { index,(name,text) ->
                val target=atomic(folder,name);encodeAtomic(target,name,text);check(decodeAtomic(target,name)==text);checkpoint(index+1)
            }
            archive.binary.toSortedMap().entries.forEachIndexed { index,(name,blob) ->
                val target=atomic(folder,name);encodeBinaryStream(target,name,blob.size,blob::verifiedCopy)
                val probe=NativeMigrationProbe();java.io.DataInputStream(target.openRead().buffered()).use { decodeBinaryTo(it,name,blob.size,probe) }
                require(probe.size==blob.size.toLong() && probe.sha256()==blob.sha256 && probe.prefix().contentEquals(blob.prefix));checkpoint(archive.text.size+index+1)
            }
        }
    }

    private fun publishMigrationGeneration(candidateId: String,sha256: String,recordCount: Int,recordsSha256: String,checkpoint: (Int)->Unit,write: (File)->Unit): String {
        val active=activeGeneration()
        if(active!=null) {
            val prior=JSONObject(decodeAtomic(atomic(root,migrationPointerName),migrationPointerName)!!)
            if(prior.getString("candidateId")==candidateId) {
                require(prior.getString("sha256")==sha256&&prior.getString("recordsSha256")==recordsSha256&&prior.getInt("records")==recordCount)
                return active
            }
        }
        requireWritable()
        val generation=java.util.UUID.randomUUID().toString();val folder=File(root,"migration-generation-$generation")
        require(!folder.exists()&&folder.mkdir()&&folder.canonicalFile==File(root.canonicalFile,folder.name))
        var published=false
        return try {
            write(folder)
            checkpoint(0)
            val pointer=JSONObject().put("version",1).put("generation",generation).put("candidateId",candidateId).put("sha256",sha256).put("records",recordCount).put("recordsSha256",recordsSha256).toString()
            encodeAtomic(atomic(root,migrationPointerName),migrationPointerName,pointer)
            published=true;recovery.generation++;generation
        } catch(failure: Throwable) {
            // A finish acknowledgement can fail after the atomic pointer reached disk. Never delete a committed generation.
            val observed=runCatching {activeGeneration()}
            if(observed.isSuccess&&observed.getOrNull()==generation) {published=true;recovery.generation++;generation}
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
    internal fun reopen(context: Context)=NativeSessionStorage(context,namespace,keyAlias)
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
        require(size in 1..21*1024*1024);binaryName(name);val stream=atomic.startWrite()
        try {
            val out=java.io.DataOutputStream(stream);val nonce=writeBinaryHeader(out,name,size)
            val buffer=ByteArray(65536);var offset=0;var index=0
            while(offset<size) {
                val count=minOf(buffer.size,size-offset);java.io.DataInputStream(input).readFully(buffer,0,count)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key);updateAAD(binaryAAD(name,size,index++,nonce))}
                out.write(cipher.iv);out.write(crypt(cipher,buffer.copyOf(count)));offset+=count
            }
            require(input.read()==-1) {"录音在保存期间发生变化"}
            out.flush();atomic.finishWrite(stream)
        }catch(error: Throwable){atomic.failWrite(stream);throw error}
    }
    internal fun writeBinary(name: String,source: File,maximumBytes: Int=12*1024*1024)=ioLock.withLock {
        requireWritable();binaryName(name);require(maximumBytes in 1..21*1024*1024)
        val size=source.length();require(size in 1..maximumBytes.toLong())
        source.inputStream().use {encodeBinaryAtomic(file(name),name,it,size.toInt())}
    }
    /** Bounded caller-owned bytes; no plaintext scratch file or text/base64 Keystore envelope. */
    internal fun writeBinaryBytes(name: String,bytes: ByteArray,maximumBytes: Int)=ioLock.withLock {
        requireWritable();binaryName(name);require(maximumBytes in 1..21*1024*1024&&bytes.size in 1..maximumBytes)
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
    private fun encodeBinaryStream(atomic: AtomicFile, name: String, size: Int, produce: (java.io.OutputStream) -> Unit) {
        require(size in 1..21 * 1024 * 1024); val stream = atomic.startWrite(); val buffer = ByteArray(65536)
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
            require(total == size); if (used > 0) chunk(); out.flush(); atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error } finally { buffer.fill(0) }
    }
    internal fun writeBinaryStream(name: String, size: Int, produce: (java.io.OutputStream) -> Unit) = ioLock.withLock {
        requireWritable(); encodeBinaryStream(file(name), name, size, produce)
    }
    /** Discovery, verification and export share one generation lock. No whole binary or Base64 value. */
    internal fun <T> migrationStreamSnapshot(resolveLegacyNames: ((String) -> String?) -> Set<String>, consume: (NativeMigrationStreamRecords) -> T): T =
        snapshotTransaction { ioLock.withLock {
            requireWritable(); val files = migrationFiles(dataRoot()).associateBy { it.name }; require(files.size in 1..1024)
            val aliases = resolveLegacyNames { name -> files[digest(name) + ".bin"]?.let { source ->
                require(source.length() in 29..(NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES + 65569L)); decodeRecord(source.readBytes(), name).second
            } }.associateBy { digest(it) + ".bin" }
            val text = linkedMapOf<String, String>(); val binaries = linkedMapOf<String, NativeMigrationBlob>(); var totalText = 0L
            for ((filename, source) in files.toSortedMap()) {
                require(source.length() in 29..23L * 1024 * 1024)
                java.io.DataInputStream(source.inputStream().buffered()).use { input ->
                    input.mark(4); val version = input.readUnsignedByte(); input.reset()
                    val name: String
                    if (version == 1 || version == 3) {
                        require(source.length() <= NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES + 65569L)
                        val decoded = decodeRecord(source.readBytes(), aliases[filename]); name = decoded.first
                        totalText += strictBytes(decoded.second).size; require(totalText <= NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
                        require(text.put(name, decoded.second) == null)
                    } else {
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
            consume(NativeMigrationStreamRecords(text, binaries))
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
        val generation=recovery.generation
        writes.execute { try { ioLock.withLock {
            check(generation==recovery.generation) { "旧的保存已被本地恢复替代" };write(name,text)
        } } catch (error: Exception) { failed(error) } catch(_: OutOfMemoryError) { failed(IllegalStateException("本地存储内存不足，原文件已保留")) } } }
    // History reads may arrive faster than disk encryption. Keep the newest
    // immutable snapshot per target and serialize on the storage worker.
    // Submission intent persistence continues to use the synchronous write.
    fun enqueueSnapshot(name: String, save: ()->Unit, failed: (Exception)->Unit) {
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
                try { next.first() } catch(error: Exception) { next.second(error) } catch(_: OutOfMemoryError) { next.second(IllegalStateException("本地缓存内存不足，原文件已保留")) }
            }
        }
    }
    internal fun <T> snapshotTransaction(block: ()->T): T = synchronized(SNAPSHOT_IO_LOCK,block)
    // A retry fence must be consumed atomically across journal instances too.
    internal fun <T> operationTransaction(block: ()->T): T = ioLock.withLock(block)
    internal val coordinationKey get()=root.canonicalPath
    internal fun child(context: Context,suffix: String): NativeSessionStorage {
        require(suffix.matches(Regex("[a-z0-9-]{1,32}")))
        val childAlias="agent-hub-child-"+MessageDigest.getInstance("SHA-256").digest((keyAlias+"\u0000"+suffix).toByteArray()).take(24).joinToString("") { "%02x".format(it) }
        val observed=ioLock.withLock {runCatching {requireCurrentGeneration();activeGeneration()}}
        val generation=observed.getOrNull()
        val scopedAlias=if(generation==null)childAlias else "agent-hub-child-"+digest(childAlias+"\u0000"+generation).take(48)
        return NativeSessionStorage(context,namespace+(generation?.let {"-"+it} ?: "")+"-"+suffix,scopedAlias).also {it.migrationUnavailable=observed.exceptionOrNull()}
    }
    internal suspend fun awaitQueuedWrites() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { waiting ->
        writes.execute { if(waiting.isActive)waiting.resumeWith(Result.success(Unit)) }
    }
    internal fun storedBytes(name: String): Long = ioLock.withLock { checkMigrationAccess();file(name).baseFile.length() }
    fun remove(name: String) = ioLock.withLock { requireWritable();file(name).delete() }
    fun token(id: String) = CodexConnectionVault(this).token(id) ?: read("token:$id").orEmpty()
    fun setToken(id: String, value: String) = operationTransaction {
        if (value.isEmpty()) remove("token:$id") else write("token:$id", value)
        // Explicit manual/QR replacement supersedes the private connection record only after the write succeeds.
        CodexConnectionVault(this).remove(id)
    }
    fun metadata(): JSONObject? = read("metadata")?.let(::JSONObject)
}
