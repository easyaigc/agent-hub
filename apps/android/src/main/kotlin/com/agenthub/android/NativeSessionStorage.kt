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
class NativeSessionStorage(context: Context, namespace: String = "mobile-v1", private val keyAlias: String = "agent-hub-mobile-v1") {
    init { require(keyAlias.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) }
    private val root = File(context.noBackupFilesDir, namespace).apply { mkdirs() }
    // AtomicFile's older reader may restore its backup while another instance
    // is writing. Coordinate all instances for this directory in this process.
    private val ioLock = IO_LOCKS.computeIfAbsent(root.canonicalPath) { ReentrantLock(true) }
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
    companion object { private val KEY_LOCK = Any(); private val SNAPSHOT_IO_LOCK=Any(); private val IO_LOCKS=ConcurrentHashMap<String,ReentrantLock>() }
    private fun file(name: String): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(root, "$hash.bin"))
    }
    fun read(name: String): String? = ioLock.withLock {
        val bytes = try { file(name).readFully() } catch (_: java.io.FileNotFoundException) { return@withLock null }
        require(bytes.size > 13 && bytes[0] == 1.toByte()) { "本地缓存格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(crypt(cipher,bytes,13))).toString()
    }
    fun write(name: String, text: String) = ioLock.withLock {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(name.toByteArray(Charsets.UTF_8)) }
        val encrypted = byteArrayOf(1) + cipher.iv + crypt(cipher,text.toByteArray(Charsets.UTF_8))
        val atomic = file(name); val stream = atomic.startWrite()
        try { stream.write(encrypted); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    // Audio stays binary and bounded in memory. Each chunk authenticates its name,
    // total length and position, so truncation/reordering cannot publish plaintext.
    internal fun writeBinary(name: String, source: File, maximumBytes: Int=12*1024*1024) = ioLock.withLock {
        require(maximumBytes in 1..21*1024*1024)
        val size=source.length();require(size in 1..maximumBytes.toLong())
        val atomic=file(name);val stream=atomic.startWrite()
        try {
            val out=java.io.DataOutputStream(stream);out.writeInt(2);out.writeInt(size.toInt())
            source.inputStream().use { input->
                val buffer=ByteArray(65536);var offset=0;var index=0
                while(offset<size) {
                    val count=minOf(buffer.size,size.toInt()-offset);java.io.DataInputStream(input).readFully(buffer,0,count)
                    val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key);updateAAD(binaryAAD(name,size.toInt(),index++)) }
                    out.write(cipher.iv);out.write(crypt(cipher,buffer.copyOf(count)));offset+=count
                }
                require(input.read()==-1) { "录音在保存期间发生变化" }
            }
            out.flush();atomic.finishWrite(stream)
        } catch(error: Throwable) { atomic.failWrite(stream);throw error }
    }
    internal fun readBinary(name: String, expectedSize: Int, maximumBytes: Int=12*1024*1024): ByteArray = ioLock.withLock {
        require(maximumBytes in 1..21*1024*1024 && expectedSize in 1..maximumBytes)
        java.io.DataInputStream(file(name).openRead()).use { input->
            require(input.readInt()==2 && input.readInt()==expectedSize) { "录音文件格式或长度已变化" }
            val result=ByteArray(expectedSize);var offset=0;var index=0
            while(offset<expectedSize) {
                val count=minOf(65536,expectedSize-offset);val iv=ByteArray(12);input.readFully(iv)
                val encrypted=ByteArray(count+16);input.readFully(encrypted)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));updateAAD(binaryAAD(name,expectedSize,index++)) }
                val clear=crypt(cipher,encrypted);require(clear.size==count);System.arraycopy(clear,0,result,offset,count);offset+=count
            }
            require(input.read()==-1) { "录音文件含多余内容" };result
        }
    }
    private fun binaryAAD(name: String,size: Int,index: Int)=name.toByteArray(Charsets.UTF_8)+byteArrayOf(0)+java.nio.ByteBuffer.allocate(8).putInt(size).putInt(index).array()
    // Feed bounded updates to Keystore on older Android providers as well.
    // Decryption is published only after the final GCM authentication succeeds.
    private fun crypt(cipher: Cipher,bytes: ByteArray,start: Int=0): ByteArray {
        val out=java.io.ByteArrayOutputStream();var offset=start
        while(offset<bytes.size) {
            val count=minOf(16_384,bytes.size-offset);cipher.update(bytes,offset,count)?.let { out.write(it) };offset+=count
        }
        out.write(cipher.doFinal());return out.toByteArray()
    }
    // Immutable snapshots are queued in order. Callers receive storage failures on Main, never silently discard drafts.
    fun enqueue(name: String, text: String, failed: (Exception) -> Unit) { writes.execute { try { write(name, text) } catch (error: Exception) { failed(error) } catch(_: OutOfMemoryError) { failed(IllegalStateException("本地存储内存不足，原文件已保留")) } } }
    // History reads may arrive faster than disk encryption. Keep the newest
    // immutable snapshot per target and serialize on the storage worker.
    // Submission intent persistence continues to use the synchronous write.
    fun enqueueSnapshot(name: String, save: ()->Unit, failed: (Exception)->Unit) {
        val schedule=synchronized(snapshotLock) { snapshots[name]=save to failed; scheduledSnapshots.add(name) }
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
    internal fun storedBytes(name: String): Long = ioLock.withLock { file(name).baseFile.length() }
    fun remove(name: String) = ioLock.withLock { file(name).delete() }
    fun token(id: String) = CodexConnectionVault(this).token(id) ?: read("token:$id").orEmpty()
    fun setToken(id: String, value: String) = operationTransaction {
        if (value.isEmpty()) remove("token:$id") else write("token:$id", value)
        // Explicit manual/QR replacement supersedes the private connection record only after the write succeeds.
        CodexConnectionVault(this).remove(id)
    }
    fun metadata(): JSONObject? = read("metadata")?.let(::JSONObject)
}
