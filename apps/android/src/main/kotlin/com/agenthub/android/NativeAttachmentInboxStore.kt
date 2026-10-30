package com.agenthub.android

import android.content.ContentResolver
import android.net.Uri
import com.agenthub.core.AttachmentPreparationRules
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class InboxSlot(val id: String,val file: CreationFile?=null)
internal data class InboxBinding(val key: String,val generation: String)
internal data class InboxBatch(val id: String,val phase: String,val sourceHash: String,val slots: List<InboxSlot>,val binding: InboxBinding?=null,val maximum: Int=10) {
    init {require(maximum==5||maximum==10)}
    val readyFiles get()=slots.mapNotNull { it.file }
}
internal data class InboxSnapshot(val revision: Long,val records: List<InboxBatch>)

/** This installation-local encrypted inbox has no Host, upload, command or model interface. */
internal class NativeAttachmentInboxStore(private val storage: NativeSessionStorage) {
    private val files=NativeDraftFiles(storage)
    private val index="external-inbox-index-v1"
    private fun validId(id: String) { require(UUID.fromString(id).toString()==id) { NativeFileIntakeFailure.CHANGED } }
    private fun decodeFile(value: JSONObject): CreationFile {
        CodexCommandCodec.validate(value,"creationFile")
        return CreationFile(value.getString("id"),value.getString("name"),value.getString("mediaType"),value.getLong("sizeBytes"),value.getString("sha256"))
    }
    private fun decode(): InboxSnapshot {
        val raw=storage.read(index) ?: return InboxSnapshot(0,emptyList())
        val root=JSONObject(raw);require(root.get("version") in listOf(1,2));val version=root.get("version") as Int
        val revision=root.getLong("revision");require(revision>=0)
        val records=root.getJSONArray("records").let { array -> (0 until array.length()).map { n ->
            val item=array.getJSONObject(n);val maximum=if(version==1)10 else (item.get("maximum") as Int).also {require(it==5||it==10)};val id=item.getString("id");validId(id)
            val phase=item.getString("phase");require(phase in listOf("receiving","failed","pending","consumed"))
            val hash=item.getString("sourceHash");require(hash.matches(Regex("[a-f0-9]{64}")))
            val slots=item.getJSONArray("slots").let { rows -> (0 until rows.length()).map { i ->
                val slot=rows.getJSONObject(i);val fileId=slot.getString("id");validId(fileId)
                val file=slot.optJSONObject("file")?.let(::decodeFile)
                require(file==null||file.id==fileId&&file.sizeBytes in 1..NativeFileIntake.MAXIMUM_BYTES.toLong())
                InboxSlot(fileId,file)
            } }
            require((phase=="consumed"&&slots.isEmpty())||AttachmentPreparationRules.canReserveWithinLimit(0,0,slots.size,maximum))
            require(phase!="pending"||slots.all { it.file!=null })
            val binding=item.optJSONObject("binding")?.let { row -> InboxBinding(row.getString("key"),row.getString("generation")).also { require(it.key.isNotEmpty()&&it.generation.isNotEmpty()) } }
            InboxBatch(id,phase,hash,slots,binding,maximum)
        } }
        require(records.map { it.id }.distinct().size==records.size)
        val ids=records.flatMap { it.slots }.map { it.id };require(ids.distinct().size==ids.size)
        return InboxSnapshot(revision,records)
    }
    private fun write(before: InboxSnapshot,records: List<InboxBatch>): InboxSnapshot {
        require(before.revision<Long.MAX_VALUE)
        val next=InboxSnapshot(before.revision+1,records)
        val rows=records.map { batch -> JSONObject().put("id",batch.id).put("phase",batch.phase).put("sourceHash",batch.sourceHash)
            .put("slots",JSONArray(batch.slots.map { slot -> JSONObject().put("id",slot.id).put("file",slot.file?.body() ?: JSONObject.NULL) }))
            .put("maximum",batch.maximum).put("binding",batch.binding?.let { JSONObject().put("key",it.key).put("generation",it.generation) } ?: JSONObject.NULL) }
        storage.write(index,JSONObject().put("version",2).put("revision",next.revision).put("records",JSONArray(rows)).toString())
        return next
    }
    private suspend fun snapshotOriginal(): InboxSnapshot = withContext(Dispatchers.IO) { storage.operationTransaction { decode() } }
    private suspend fun update(id: String,change: (InboxBatch)->InboxBatch): InboxBatch = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val before=decode();val old=before.records.single { it.id==id };val next=change(old)
            require(next.id==old.id&&next.sourceHash==old.sourceHash&&next.maximum==old.maximum)
            if(next!=old)write(before,before.records.map { if(it.id==id)next else it });next
        }
    }
    private suspend fun <T> batchLease(id: String,block: suspend ()->T): T {
        validId(id);val release=FixedFilePreparationLeases.acquire(storage.coordinationKey+":inbox-batch",id)
        return try { block() }finally { release() }
    }
    private suspend fun stageOriginal(id: String,sources: List<Uri>,resolver: ContentResolver,maximum: Int=5,read: suspend (Uri)->LocalAttachmentInput={ NativeFileIntake.read(resolver,it) }): InboxBatch = batchLease(id) {
        require(AttachmentPreparationRules.canReserve(0,0,sources.size))
        val sourceHash=sha(JSONArray(sources.map { it.toString() }).toString().toByteArray())
        val batch=withContext(Dispatchers.IO) { storage.operationTransaction {
            val before=decode();val old=before.records.firstOrNull { it.id==id }
            if(old!=null) { require(old.sourceHash==sourceHash);old }
            else {
                require(maximum==5||maximum==10)
                if(!AttachmentPreparationRules.canReserveWithinLimit(0,0,sources.size,maximum))throw NativeFileIntakeFailure("最多添加 5 个附件，请先移除部分附件。")
                InboxBatch(id,"receiving",sourceHash,sources.map { InboxSlot(UUID.randomUUID().toString()) },maximum=maximum).also {write(before,before.records+it)}
            }
        } }
        if(batch.phase in listOf("pending","consumed"))return@batchLease batch
        var current=update(id) { it.copy(phase="receiving") }
        try {
            for((position,uri)in sources.withIndex()) {
                currentCoroutineContext().ensureActive();val slot=current.slots[position]
                if(slot.file!=null)try { files.bytes(slot.file);continue }catch(_: LocalDraftSourceMissing) { /* Finish only missing bytes under the original manifest. */ }
                val input=read(uri)
                val expected=CreationFile(slot.id,input.name,input.mediaType,input.bytes.size.toLong(),sha(input.bytes))
                require(slot.file==null||slot.file==expected) { NativeFileIntakeFailure.CHANGED }
                // Slot identity and exact manifest reach the atomic index before any payload write.
                current=update(id) { old -> old.copy(slots=old.slots.mapIndexed { n,item -> if(n==position)item.copy(file=expected)else item }) }
                val prepared=files.prepare(input.name,input.mediaType,input.bytes,slot.id)
                try { require(prepared.file.manifest==expected) }finally { prepared.close() }
            }
            update(id) { it.copy(phase="pending") }
        } catch(failure: Throwable) {
            withContext(NonCancellable) { runCatching { update(id) { it.copy(phase="failed") } } }
            throw failure
        }
    }
    private suspend fun bindOriginal(expected: InboxBatch,destination: InboxBinding): InboxBatch = batchLease(expected.id) {
        update(expected.id) { current ->
            if(current.phase=="consumed")current else {
                require(current.phase=="pending"&&current.maximum==expected.maximum&&current.slots==expected.slots) { NativeFileIntakeFailure.CHANGED }
                require(current.binding==null||current.binding==destination) { NativeFileIntakeFailure.CHANGED }
                current.copy(binding=destination)
            }
        }
    }
    private suspend fun payloadOriginal(batch: InboxBatch,file: CreationFile): LocalAttachmentInput {
        val current=snapshot().records.single { it.id==batch.id }
        require(current.phase=="pending"&&current.maximum==batch.maximum&&current.slots==batch.slots&&current.binding==batch.binding&&current.readyFiles.any { it==file }) { NativeFileIntakeFailure.CHANGED }
        return LocalAttachmentInput(file.name,file.mediaType,files.bytes(file),file.id)
    }
    private suspend fun consumeOriginal(batch: InboxBatch,binding: InboxBinding) = batchLease(batch.id) {
        val consumed=update(batch.id) { current ->
            if(current.phase=="consumed")current else {
                require(current.phase=="pending"&&current.maximum==batch.maximum&&current.slots==batch.slots&&current.binding==binding) { NativeFileIntakeFailure.CHANGED }
                current.copy(phase="consumed")
            }
        }
        cleanup(consumed)
    }
    private suspend fun discardOriginal(id: String) = batchLease(id) {
        cleanup(update(id) { it.copy(phase="consumed") })
    }
    private suspend fun cleanupConsumedOriginal() {
        val ids=snapshot().records.filter { it.phase=="consumed"&&it.slots.isNotEmpty() }.map { it.id }
        for(id in ids)batchLease(id) { snapshot().records.firstOrNull { it.id==id&&it.phase=="consumed" }?.let { cleanup(it) } }
    }
    private suspend fun cleanup(consumed: InboxBatch) {
        // Consumption is already durable. A cleanup failure retains these exact owned IDs for retry.
        try {
            for(slot in consumed.slots) {
                val file=slot.file?.let(::LocalDraftFile) ?: files.inboxOwnedFile(slot.id)
                file?.let { files.discardInboxOwned(it) }
            }
            update(consumed.id) { require(it.phase=="consumed");it.copy(slots=emptyList(),binding=null) }
        } catch(cancel: CancellationException) { throw cancel }
          catch(_: Exception) { /* Preserve the consumed receipt and cleanup manifest; never resurrect input. */ }
    }
    suspend fun snapshot(): InboxSnapshot=NativeInboxOwnership.writing(storage){snapshotOriginal()}
    suspend fun stage(id: String,sources: List<Uri>,resolver: ContentResolver,maximum: Int=5,read: suspend (Uri)->LocalAttachmentInput={NativeFileIntake.read(resolver,it)}): InboxBatch=
        NativeInboxOwnership.writing(storage){stageOriginal(id,sources,resolver,maximum,read)}
    suspend fun bind(expected: InboxBatch,destination: InboxBinding): InboxBatch=NativeInboxOwnership.writing(storage){bindOriginal(expected,destination)}
    suspend fun payload(batch: InboxBatch,file: CreationFile): LocalAttachmentInput=NativeInboxOwnership.writing(storage){payloadOriginal(batch,file)}
    suspend fun consume(batch: InboxBatch,binding: InboxBinding)=NativeInboxOwnership.writing(storage){consumeOriginal(batch,binding)}
    suspend fun discard(id: String)=NativeInboxOwnership.writing(storage){discardOriginal(id)}
    suspend fun cleanupConsumed()=NativeInboxOwnership.writing(storage){cleanupConsumedOriginal()}
    companion object {
        private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
