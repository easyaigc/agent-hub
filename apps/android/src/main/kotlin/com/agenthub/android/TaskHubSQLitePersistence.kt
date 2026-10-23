package com.agenthub.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/** One authenticated snapshot per SQLite transaction. Corrupt/unknown stores are never reset. */
internal class TaskHubSQLitePersistence(context: Context, namespace: String="task-hub-v1") {
    init { require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    // Normalize the OS-owned anchor once; never follow a caller-created child link.
    private val root=File(context.noBackupFilesDir.canonicalFile,namespace)
    private val file=File(root,"business.sqlite")
    private val location=file.absolutePath
    private val lock=locks.computeIfAbsent(location) { ReentrantLock(true) }
    private val alias="agent-hub-task-hub-v1-"+taskHubSha(namespace.toByteArray()).take(32)
    companion object {
        private val locks=ConcurrentHashMap<String,ReentrantLock>(); private val keyLock=Any()
        private const val chunkBytes=65536
        private const val maximumSnapshotBytes=64*1024*1024
    }
    private fun key(existing: Boolean): SecretKey = synchronized(keyLock) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return@synchronized it }
        check(!existing) { "任务数据库密钥不可用，原库已保留" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun crypt(key: SecretKey, bytes: ByteArray, aad: String, encrypt: Boolean): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        if(encrypt) cipher.init(Cipher.ENCRYPT_MODE,key) else {
            require(bytes.size>=29 && bytes[0]==1.toByte()) { "任务数据库密文无效" }
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        }
        cipher.updateAAD((location+"\u0000"+aad).toByteArray(Charsets.UTF_8))
        val iv=if(encrypt) cipher.iv.copyOf() else null
        val out=java.io.ByteArrayOutputStream();var offset=if(encrypt) 0 else 13
        // API26 Keystore and CursorWindow both require bounded input. Never
        // publish decrypted output before the final GCM tag has been checked.
        while(offset<bytes.size) {
            val count=minOf(16384,bytes.size-offset)
            cipher.update(bytes,offset,count)?.let(out::write);offset+=count
        }
        out.write(cipher.doFinal())
        return if(encrypt) byteArrayOf(1)+iv!!+out.toByteArray() else out.toByteArray()
    }
    private fun open(): Pair<SQLiteDatabase,SecretKey> {
        check(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(file.toPath())) { "任务数据库路径无效" }
        check(root.mkdirs() || root.isDirectory)
        check(root.canonicalFile==root.absoluteFile) { "任务数据库路径发生变化" }
        val exists=file.exists()
        val key=key(exists)
        val db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or if(exists) 0 else SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            if(!exists) {
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE frames (id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
                    db.execSQL("PRAGMA user_version=1")
                    saveSnapshot(db,key,0,emptyMap())
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            check(db.version==1) { "任务数据库版本不兼容，原库已保留" }
            return db to key
        } catch(e: Throwable) { db.close();throw e }
    }
    private data class Loaded(val generation: Long, val values: LinkedHashMap<String,String>)
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    private fun frame(db: SQLiteDatabase,id: String,size: Int): ByteArray {
        // Also read valid v1 large fields without placing one BLOB in a cursor.
        // Migration happens only after every original field authenticates.
        check(size in 29..32*1024*1024) { "任务数据库密文长度无效" }
        val out=java.io.ByteArrayOutputStream(size);var offset=0
        while(offset<size) {
            val count=minOf(chunkBytes,size-offset)
            db.rawQuery("SELECT substr(payload,?,?) FROM frames WHERE id=?",arrayOf((offset+1).toString(),count.toString(),id)).use { c ->
                check(c.moveToFirst());val bytes=c.getBlob(0);check(bytes.size==count);out.write(bytes);check(!c.moveToNext())
            }
            offset+=count
        }
        return out.toByteArray()
    }
    private fun load(db: SQLiteDatabase,key: SecretKey,include: (String)->Boolean = { true }): Loaded {
        val frames=linkedMapOf<String,ByteArray>()
        val sizes=linkedMapOf<String,Int>();var total=0L
        db.rawQuery("SELECT id,length(payload) FROM frames",null).use { c -> while(c.moveToNext()) {
            val id=c.getString(0);val size=c.getLong(1)
            check(id.length<=256 && size in 29..32L*1024*1024 && sizes.size<50002)
            total+=size;check(total<=2L*maximumSnapshotBytes) { "任务数据库超过可读取上限，原库已保留" }
            check(sizes.put(id,size.toInt())==null)
        } }
        val manifestSize=sizes.remove("manifest") ?: error("任务数据库清单缺失")
        val manifest=JSONObject(utf8(crypt(key,frame(db,"manifest",manifestSize),"manifest",false)))
        val generation=manifest.getLong("generation");check(generation>=0)
        if(manifest.getInt("version")==2) {
            val count=manifest.getInt("chunks");val size=manifest.getInt("bytes")
            check(size in 2..maximumSnapshotBytes && count==(size+chunkBytes-1)/chunkBytes && sizes.size==count)
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            var authenticated=false
            val input=object: java.io.InputStream() {
                private var index=0
                private var part=ByteArray(0)
                private var position=0
                private var consumed=0
                private fun ready(): Boolean {
                    if(position<part.size) return true
                    if(index==count) {
                        if(!authenticated) {
                            check(consumed==size)
                            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==manifest.getString("sha256")) { "任务数据库快照摘要不符" }
                            authenticated=true
                        }
                        return false
                    }
                    val id="chunk-$index";val expected=minOf(chunkBytes,size-index*chunkBytes)
                    check(sizes[id]==expected+29) { "任务数据库分块缺失或长度不符" }
                    part=crypt(key,frame(db,id,sizes.getValue(id)),"$generation/$id/$count/$size",false)
                    check(part.size==expected);digest.update(part);index++;position=0
                    return true
                }
                override fun read(): Int {
                    if(!ready()) return -1
                    consumed++;return part[position++].toInt() and 255
                }
                override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(length==0) return 0
                    if(!ready()) return -1
                    val n=minOf(length,part.size-position)
                    System.arraycopy(part,position,buffer,offset,n);position+=n;consumed+=n;return n
                }
            }
            val values=linkedMapOf<String,String>()
            val names=mutableSetOf<String>()
            // No full UTF-8 buffer, full decoded JSON string or JSONTokener tree:
            // legacy API26 processes can have a 48 MiB heap.
            android.util.JsonReader(java.io.InputStreamReader(input,Charsets.UTF_8.newDecoder())).use { reader ->
                reader.beginArray()
                while(reader.hasNext()) {
                    reader.beginObject();var name: String?=null;var value: String?=null;var skipped=false
                    val seen=mutableSetOf<String>()
                    while(reader.hasNext()) {
                        val field=reader.nextName();check(seen.add(field) && field in listOf("key","value"))
                        check(reader.peek()==android.util.JsonToken.STRING)
                        if(field=="key") name=reader.nextString()
                        else if(name!=null && !include(name)) { reader.skipValue();skipped=true }
                        else value=reader.nextString()
                    }
                    reader.endObject();check(name!=null && (value!=null || skipped) && names.add(name))
                    if(include(name)) values[name]=value ?: error("任务字段缺失")
                }
                reader.endArray();check(reader.peek()==android.util.JsonToken.END_DOCUMENT)
            }
            check(authenticated)
            return Loaded(generation,values)
        }
        check(manifest.getInt("version")==1) { "任务数据库记录版本不兼容，原库已保留" }
        // Original v1 snapshots are read-only until a successful v2 transaction.
        for((id,size) in sizes) frames[id]=frame(db,id,size)
        val keys=manifest.getJSONArray("keys").strings();check(keys.distinct().size==keys.size && keys.size==frames.size && keys.toSet()==frames.keys)
        val values=linkedMapOf<String,String>()
        for(id in keys) {
            val value=JSONObject(utf8(crypt(key,frames.getValue(id),"$generation/$id",false)))
            val name=value.getString("key");check(id==taskHubSha(name.toByteArray(Charsets.UTF_8)))
            check(!values.containsKey(name))
            if(include(name)) values[name]=value.getString("value")
        }
        return Loaded(generation,values)
    }
    private fun utf8Size(value: String): Long {
        var size=0L;var index=0
        while(index<value.length) {
            val char=value[index++]
            size+=when {
                char.code<128 -> 1
                char.code<2048 -> 2
                char.isHighSurrogate() -> { require(index<value.length && value[index++].isLowSurrogate());4 }
                else -> { require(!char.isLowSurrogate());3 }
            }
        }
        return size
    }
    private fun encode(values: Map<String,String>,output: java.io.OutputStream) {
        val encoder=java.io.OutputStreamWriter(output,Charsets.UTF_8.newEncoder())
        // JsonWriter may hand an entire long string to Writer.write(String).
        // Bound that conversion rather than allocating another full char array.
        val writer=object: java.io.Writer() {
            private val buffer=CharArray(4096)
            override fun write(chars: CharArray,offset: Int,length: Int) { encoder.write(chars,offset,length) }
            override fun write(value: String,offset: Int,length: Int) {
                var position=offset;val end=offset+length
                while(position<end) { val n=minOf(buffer.size,end-position);value.toCharArray(buffer,0,position,position+n);encoder.write(buffer,0,n);position+=n }
            }
            override fun flush()=encoder.flush()
            override fun close()=encoder.close()
        }
        android.util.JsonWriter(writer).use { json ->
            json.beginArray()
            for((name,value) in values) {
                json.beginObject();json.name("key").value(name);json.name("value").value(value);json.endObject()
            }
            json.endArray()
        }
    }
    private fun saveSnapshot(db: SQLiteDatabase,key: SecretKey,generation: Long,values: Map<String,String>) {
        for((name,value) in values) require(name.length<=8192 && utf8Size(name)<=32768 && utf8Size(value)<=8*1024*1024)
        val measured=object: java.io.OutputStream() {
            var size=0
            override fun write(value: Int) { size++;check(size<=maximumSnapshotBytes) { "任务本机存储已满，原库与草稿保留；未提交操作" } }
            override fun write(buffer: ByteArray,offset: Int,length: Int) { size+=length;check(size<=maximumSnapshotBytes) { "任务本机存储已满，原库与草稿保留；未提交操作" } }
        }
        encode(values,measured)
        val size=measured.size;val count=(size+chunkBytes-1)/chunkBytes
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        var index=0;var position=0;var written=0
        val buffer=ByteArray(chunkBytes)
        fun flushFrame() {
            if(position==0) return
            val id="chunk-$index";val part=buffer.copyOf(position);digest.update(part)
            db.execSQL("INSERT INTO frames VALUES (?,?)",arrayOf(id,crypt(key,part,"$generation/$id/$count/$size",true)))
            index++;position=0
        }
        val output=object: java.io.OutputStream() {
            override fun write(value: Int) { buffer[position++]=value.toByte();written++;if(position==buffer.size) flushFrame() }
            override fun write(bytes: ByteArray,offset: Int,length: Int) {
                var source=offset;val end=offset+length
                while(source<end) { val n=minOf(buffer.size-position,end-source);System.arraycopy(bytes,source,buffer,position,n);source+=n;position+=n;written+=n;if(position==buffer.size) flushFrame() }
            }
        }
        db.execSQL("DELETE FROM frames")
        encode(values,output);flushFrame();check(written==size && index==count)
        val sha256=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val manifest=JSONObject().put("version",2).put("generation",generation).put("chunks",count).put("bytes",size).put("sha256",sha256)
        db.execSQL("INSERT INTO frames VALUES (?,?)",arrayOf("manifest",crypt(key,manifest.toString().toByteArray(Charsets.UTF_8),"manifest",true)))
    }
    fun readAll(): Map<String,String> = readWhere { true }
    fun readValue(name: String): String? = readWhere { it==name }[name]
    fun readWhere(include: (String)->Boolean): Map<String,String> = lock.withLock {
        val (db,key)=open()
        db.use {
            db.beginTransactionNonExclusive()
            try { val values=load(db,key,include).values.toMap();db.setTransactionSuccessful();values }
            finally { db.endTransaction() }
        }
    }
    fun <T> transaction(block: (MutableMap<String,String>)->T): T = lock.withLock {
        val (db,key)=open()
        db.use {
            db.beginTransaction()
            try {
                val loaded=load(db,key);val values=loaded.values;val result=block(values)
                check(loaded.generation<Long.MAX_VALUE);val generation=loaded.generation+1
                saveSnapshot(db,key,generation,values)
                db.setTransactionSuccessful();result
            } finally { db.endTransaction() }
        }
    }
}
