package com.agenthub.android

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class ComputerFilePhase { CAPTURING,CAPTURE_FAILED,READY,UNKNOWN,COMPLETED }
internal data class ComputerFileChunk(val bytes: Int,val sha256: String) {
    fun json()=JSONObject().put("bytes",bytes).put("sha256",sha256)
}
internal data class PendingComputerFile(val id: String,val name: String,val mediaType: String,val sizeBytes: Long,
    val sha256: String?,val chunks: List<ComputerFileChunk>,val phase: ComputerFilePhase,val error: String?=null,
    val receipt: ComputerFileReceipt?=null) {
    fun manifest(host: String)=ComputerFileManifest(host,id,name,mediaType,sizeBytes,requireNotNull(sha256)).also {ComputerFileCodec.manifest(it.json())}
    fun json()=JSONObject().put("id",id).put("name",name).put("mediaType",mediaType).put("sizeBytes",sizeBytes)
        .put("sha256",sha256?:JSONObject.NULL).put("chunks",JSONArray(chunks.map {it.json()})).put("phase",phase.name)
        .put("error",error?:JSONObject.NULL).put("receipt",receipt?.json()?:JSONObject.NULL)
}

/** Installation encrypted, Long-size F59 originals built from bounded existing binary records.
 * Every local mutation uses the parent snapshot fence. Presence of this named child rejects
 * both export and destination activation until a portable F59 schema exists. */
internal class NativeComputerFileStore private constructor(private val context: Context,private val root: NativeSessionStorage,
    val target: ComputerFileTarget) {
    companion object {
        const val SCOPE="computer-file-transfers"
        const val CHUNK_BYTES=1024*1024
        const val MAX_TOTAL_BYTES=4_294_967_296L
        private const val MAX_ROWS=NativeComputerFileJournal.MAX_ROWS
        private const val MAX_CHUNKS=NativeComputerFileJournal.MAX_CHUNKS
        private val inputWorkers=java.util.concurrent.Executors.newFixedThreadPool(2)
        private val cancellationWorkers=java.util.concurrent.Executors.newSingleThreadExecutor {work->
            Thread(work,"computer-file-input-cancel").apply {isDaemon=true}
        }
        fun open(context: Context,root: NativeSessionStorage,target: ComputerFileTarget)=
            NativeComputerFileStore(context.applicationContext,root,target)
    }
    private var opened: NativeSessionStorage?=null
    private val storage get()=requireNotNull(opened)
    private val name="computer-files-v1:"+target.key
    private var originalBinding: JSONObject?=null
    private fun binary(id: String,index: Int)="computer-file-original:$id:$index"
    private fun <T> installationWriter(work: ()->T): T=ComputerFileFdOwners.resource(
        {NativeVoiceRawOwnership.computerFilesWriter(root)},{it.close()}) {owner->
        owner.requireCurrent();work().also {owner.requireCurrent()}
    }
    private fun <T> transaction(create: Boolean=false,absent: ()->T={error("手机保存的原件校验失败，请保留原记录。")},work: ()->T): T=root.snapshotTransaction {
        installationWriter {
            val current=root.computerFileStorage(context,create)?:return@installationWriter absent()
            opened?.let {check(it.coordinationKey==current.coordinationKey)};opened=current
            current.computerFileTransaction(work)
        }
    }
    private fun rows(): List<PendingComputerFile> {
        val raw=storage.computerFileRead(name)?:return emptyList()
        val decoded=NativeComputerFileJournal.decode(name,raw,target)
        originalBinding=decoded.binding // Preserve original account history and exact target fields.
        return decoded.files
    }
    private fun save(rows: List<PendingComputerFile>) {
        require(rows.size<=MAX_ROWS&&rows.sumOf {it.chunks.size}<=MAX_CHUNKS&&rows.sumOf {it.sizeBytes}<=MAX_TOTAL_BYTES)
        val raw=JSONObject().put("schema","agent-hub/android-computer-files-v1").put("target",originalBinding?:target.json()).put("files",JSONArray(rows.map {it.json()})).toString()
        require(raw.toByteArray(Charsets.UTF_8).size<=900_000);storage.computerFileWrite(name,raw)
        if(originalBinding==null)originalBinding=JSONObject(target.json().toString())
    }
    fun load(): List<PendingComputerFile> = transaction(absent={emptyList()}) {
        val current=rows();val restored=current.map {row->when(row.phase) {
            ComputerFilePhase.CAPTURING->row.copy(phase=ComputerFilePhase.CAPTURE_FAILED,error="原件读取未完成，已保存的内容保留在手机。")
            else->row // READY is proven never submitted; UNKNOWN never becomes READY on cold start.
        }}
        if(restored!=current)save(restored);restored
    }
    suspend fun capture(uri: Uri,maximum: Long,progress: (Long)->Unit,describe: (String,Long)->Unit): PendingComputerFile = suspendCancellableCoroutine {waiting->
        val signal=CancellationSignal()
        val future=NativeMigrationIoWork.submit(inputWorkers,waiting.context) {
            try {
                if(!waiting.isActive)return@submit
                val row=captureOriginal(uri,maximum,{waiting.isActive},progress,describe,signal)
                if(waiting.isActive)waiting.resume(row)
            }catch(failure: Exception){if(waiting.isActive)waiting.resumeWithException(failure)}
            catch(failure: OutOfMemoryError){if(waiting.isActive)waiting.resumeWithException(IllegalStateException(ComputerFileCopy.UNKNOWN,failure))}
        }
        waiting.invokeOnCancellation {
            // Main only enqueues. Actual provider cancellation/worker exit is tracked
            // separately; cancelling Future is never reported as FD/worker closure.
            NativeMigrationIoWork.cleanup(cancellationWorkers,waiting.context) {
                try {future.cancel(true)}finally {signal.cancel()}
            }
        }
    }
    private fun captureOriginal(uri: Uri,maximum: Long,active: ()->Boolean,progress: (Long)->Unit,describe: (String,Long)->Unit,signal: CancellationSignal): PendingComputerFile = root.snapshotTransaction {installationWriter {
        ComputerFileFdOwners.requireReleased()
        check(active());signal.throwIfCanceled()
        require(uri.scheme=="content"&&maximum in 1..ComputerFileCodec.MAX_FILE_BYTES)
        var filename: String?=null;var declared: Long?=null
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null,signal)?.use {cursor->
            require(cursor.moveToFirst());filename=cursor.getString(0);if(!cursor.isNull(1))declared=cursor.getLong(1)
        }
        val name=requireNotNull(filename);ComputerFileCodec.validName(name)
        val media=context.contentResolver.getType(uri)?.takeIf {ComputerFileCodec.validMediaType(it)}?:"application/octet-stream"
        val captured=ComputerFileFdOwners.source({requireNotNull(context.contentResolver.openFileDescriptor(uri,"r",signal))}) {fd->
            val before=Os.fstat(fd.fileDescriptor);val size=before.st_size
            require(OsConstants.S_ISREG(before.st_mode)&&size in 1..maximum&&(declared==null||declared==size)) {"请选择额度以内、大小明确且非空的普通文件。"}
            check(active()) {"本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。"}
            describe(name,size)
            transaction(create=true) {
            var values=rows();require(values.size<MAX_ROWS&&values.sumOf {it.sizeBytes}+size<=MAX_TOTAL_BYTES&&values.sumOf {it.chunks.size}+((size+CHUNK_BYTES-1)/CHUNK_BYTES)<=MAX_CHUNKS) {"手机保存的待传文件已达到额度，请先核对已有传输。"}
            require(File(storage.coordinationKey).usableSpace>=size+16*1024*1024) {"手机存储空间不足，未开始传输。"}
            var row=PendingComputerFile(UUID.randomUUID().toString(),name,media,size,null,emptyList(),ComputerFilePhase.CAPTURING)
            values=values+row;save(values) // Claim precedes every original; no unlisted original is uploaded.
            try {
                val hash=MessageDigest.getInstance("SHA-256");var count=0L
                ComputerFileFdOwners.readDuplicate(fd.fileDescriptor) {input->
                    while(count<size) {
                        check(active()) {"本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。"}
                        val bytes=ByteArray(minOf(CHUNK_BYTES.toLong(),size-count).toInt())
                        try {var used=0;while(used<bytes.size){val n=input.read(bytes,used,bytes.size-used);require(n>0);used+=n}
                            hash.update(bytes);storage.computerFileWriteChunk(binary(row.id,row.chunks.size),bytes)
                            row=row.copy(chunks=row.chunks+ComputerFileChunk(bytes.size,ComputerFileCodec.hash(bytes)));count+=bytes.size
                            values=values.map {if(it.id==row.id)row else it};save(values);progress(count)
                        }finally {bytes.fill(0)}
                    };require(input.read()==-1)
                }
                val after=Os.fstat(fd.fileDescriptor)
                require(before.st_dev==after.st_dev&&before.st_ino==after.st_ino&&before.st_size==after.st_size&&before.st_mtime==after.st_mtime&&before.st_ctime==after.st_ctime) {"文件在读取时发生变化，请重新选择。"}
                // READY is deferred until both the duplicate and original PFD owners
                // have confirmed close. A crash/close error remains non-uploadable.
                row=row.copy(sha256=hash.digest().joinToString("") {"%02x".format(it)},phase=ComputerFilePhase.CAPTURING)
                save(values.map {if(it.id==row.id)row else it});row
            }catch(failure: Throwable) {
                try {save(values.map {if(it.id==row.id)row.copy(phase=ComputerFilePhase.CAPTURE_FAILED,error="原件读取未完成，已保存的内容保留在手机。") else it})}catch(saveFailure: Throwable){failure.addSuppressed(saveFailure)}
                throw failure
            }
            }
        }
        ComputerFileFdOwners.requireReleased()
        transaction {
            val current=rows();val original=current.single {it.id==captured.id}
            check(original==captured&&original.phase==ComputerFilePhase.CAPTURING)
            val ready=original.copy(phase=ComputerFilePhase.READY)
            save(current.map {if(it.id==ready.id)ready else it});ready
        }
    }}
    fun claim(id: String): PendingComputerFile = transaction {
        ComputerFileFdOwners.requireReleased()
        val current=rows();val row=current.single {it.id==id};check(row.phase==ComputerFilePhase.READY&&!ComputerFileCopy.receiptSavePending(row.error))
        // Claim is the final pre-POST fence: authenticate, hash and actually sync
        // every original again under the same cross-process exclusive transaction.
        val hash=MessageDigest.getInstance("SHA-256");var count=0L
        row.chunks.forEachIndexed {index,chunk->
            val bytes=storage.computerFileReadChunk(binary(id,index),chunk.bytes)
            try {require(ComputerFileCodec.hash(bytes)==chunk.sha256);hash.update(bytes);count+=bytes.size}finally {bytes.fill(0)}
        }
        require(count==row.sizeBytes&&hash.digest().joinToString("") {"%02x".format(it)}==row.sha256) {ComputerFileCopy.ORIGINAL_INVALID}
        val next=row.copy(phase=ComputerFilePhase.UNKNOWN,error=null);save(current.map {if(it.id==id)next else it});next
    }
    fun error(id: String,text: String) = transaction {
        val current=rows();val original=current.singleOrNull {it.id==id}?:return@transaction
        if(original.phase==ComputerFilePhase.COMPLETED&&text==ComputerFileCopy.UNKNOWN)return@transaction
        save(current.map {if(it.id==id)it.copy(error=text.takeIf {value->value.isNotBlank()}?.take(2000)?:"文件传输未确认，请查询原结果。") else it})
    }
    fun apply(receipt: ComputerFileReceipt): PendingComputerFile? = transaction(absent={null}) {
        val current=rows();val row=current.singleOrNull {it.id==receipt.file.id}?:return@transaction null
        receipt.validate(row.manifest(target.scope.hostId))
        // A prepared/404 query cannot prove that an earlier content request did not run.
        if(row.phase==ComputerFilePhase.COMPLETED) {
            if(receipt.phase=="completed")require(row.receipt==receipt) {ComputerFileCopy.CONFIRMATION_CONFLICT}
            return@transaction row // rows() already re-established the exact terminal file barrier.
        }
        val next=if(receipt.phase=="completed")row.copy(phase=ComputerFilePhase.COMPLETED,error=null,receipt=receipt) else row
        if(next!=row)save(current.map {if(it.id==row.id)next else it});next
    }
    fun releaseCompleted(id: String) {
        var terminalBarrierConfirmed=false
        try {transaction {
            ComputerFileCodec.uuid(id)
            val current=rows();val row=current.singleOrNull {it.id==id}
            if(row==null)return@transaction // Strict journal read confirmed a durable index without this row.
            check(row.phase==ComputerFilePhase.COMPLETED&&row.receipt?.phase=="completed")
            // rows() authenticated and fsynced the exact COMPLETED ciphertext and both directories.
            terminalBarrierConfirmed=true
            row.chunks.forEachIndexed {index,chunk->storage.computerFileRemoveChunk(binary(id,index),chunk.bytes,chunk.sha256)}
            save(current.filterNot {it.id==id})
        }}catch(failure: Throwable){throw ComputerFileRetirementFailure(terminalBarrierConfirmed,failure)}
    }
    fun upload(row: PendingComputerFile)=ComputerFileUpload(row.manifest(target.scope.hostId)) {output,active,progress->
        var total=0L;val hash=MessageDigest.getInstance("SHA-256")
        row.chunks.forEachIndexed {index,chunk->
            root.requireRawWriterOwner();check(active()) {"本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。"}
            val bytes=transaction {storage.computerFileReadChunk(binary(row.id,index),chunk.bytes)}
            try {require(ComputerFileCodec.hash(bytes)==chunk.sha256);hash.update(bytes)
                var offset=0;while(offset<bytes.size){check(active());val n=minOf(65536,bytes.size-offset);output.write(bytes,offset,n);offset+=n}
                total+=bytes.size;require(total<=row.sizeBytes);progress(total)
            }finally {bytes.fill(0)}
        }
        require(total==row.sizeBytes&&hash.digest().joinToString("") {"%02x".format(it)}==row.sha256) {"手机保存的原件校验失败，请保留原记录。"}
    }
}

/** Worker/Future completion is not descriptor release. A failed close retains the
 * exact Java owner until process exit; it is never retried (including EINTR). */
internal class ComputerFileRetirementFailure(val terminalBarrierConfirmed: Boolean,cause: Throwable):
    IllegalStateException(ComputerFileCopy.CLEANUP_PENDING,cause)
internal class ComputerFileResourceCloseUnknown(cause: Throwable?=null):
    IllegalStateException(ComputerFileCopy.RESOURCES_PENDING,cause)
internal object ComputerFileFdOwners {
    private val retained=java.util.concurrent.ConcurrentHashMap.newKeySet<Owner<*>>()
    private class Owner<T: Any>(private val release: (T)->Unit) {
        var value: T?=null
        @Volatile var closeAttempted=false;private set
        @Volatile var closed=false;private set
        @Volatile var closeFailed=false;private set
        init {retained.add(this)}
        @Synchronized fun closeOnce() {
            if(closeAttempted){if(!closed)throw ComputerFileResourceCloseUnknown();return}
            closeAttempted=true
            try {value?.let(release);closed=true;retained.remove(this)}
            catch(failure: Throwable){closeFailed=true;throw ComputerFileResourceCloseUnknown(failure)}
        }
    }
    fun hasUnresolved()=retained.any {it.closeFailed}
    fun requireReleased(){if(hasUnresolved())throw ComputerFileResourceCloseUnknown()}
    private fun <T: Any,R> using(acquire: ()->T,release: (T)->Unit,consume: (T)->R): R {
        val owner=Owner(release) // Before any native handle or stream can be acquired.
        var primary: Throwable?=null
        try {val value=acquire();owner.value=value;return consume(value)}
        catch(failure: Throwable){primary=failure;throw failure}
        finally {
            try {owner.closeOnce()}catch(closeFailure: Throwable) {
                val earlier=primary
                if(earlier==null)throw closeFailure
                earlier.addSuppressed(closeFailure)
            }
        }
    }
    fun <T> source(acquire: ()->android.os.ParcelFileDescriptor,consume: (android.os.ParcelFileDescriptor)->T): T=
        using(acquire,{it.close()},consume)
    fun <T> readDuplicate(source: java.io.FileDescriptor,consume: (java.io.InputStream)->T): T=
        using({Os.dup(source)},{Os.close(it)}) {owned->
            // Android's FD stream constructor borrows owned. Wrapper/channel close
            // does not own its raw handle, even if construction/read/close throws.
            FileInputStream(owned).use(consume)
        }
    internal class Resource<T: Any>(val value: T,private val closeOwned: ()->Unit) {
        fun close()=closeOwned()
    }
    internal fun <T: Any> acquireResource(acquire: ()->T,release: (T)->Unit): Resource<T> {
        val owner=Owner(release) // Retained before acquire, including failure before a Writer can return.
        try {val value=acquire();owner.value=value;return Resource(value,owner::closeOnce)}
        catch(failure: Throwable){try{owner.closeOnce()}catch(close: Throwable){if(close!==failure)failure.addSuppressed(close)};throw failure}
    }
    internal fun <R: Any,T> resource(acquire: ()->R,release: (R)->Unit,consume: (R)->T): T=using(acquire,release,consume)
    internal fun <T> raw(path: String,flags: Int,mode: Int=0,consume: (java.io.FileDescriptor)->T): T=
        using({Os.open(path,flags,mode)},{Os.close(it)},consume)
    fun <T> directory(path: String,consume: (java.io.FileDescriptor)->T): T=
        using({Os.open(path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)},
            {Os.close(it)},consume)
}
