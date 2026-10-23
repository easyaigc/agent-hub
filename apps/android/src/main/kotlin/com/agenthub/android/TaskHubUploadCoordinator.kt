package com.agenthub.android

import android.content.Context
import android.util.AtomicFile
import com.agenthub.core.TaskHubContract
import com.agenthub.core.TaskHubRules
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class TaskHubUpload(val scope: String,val projectId: String,val taskId: String?,val roundId: String?,
    val draftId: String,val evidenceId: String,val action: String,val operationId: String,val prepareId: String,val ticketId: String?,
    val fileSha256: String,val size: Int,val name: String,val mediaType: String,val boundary: String,val bodySha256: String,val bodySize: Int,
    val phase: String="prepared",val attempted: Boolean=false,val response: String?=null,val waitEnded: Boolean=false) {
    init {
        require(scope.startsWith("task-hub-v1:") && phase in listOf("prepared","pending","unknown","confirmed","rejected"))
        require(size in 1..20*1024*1024 && bodySize in size..21*1024*1024)
        require(listOf(fileSha256,bodySha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(boundary.matches(Regex("task-hub-[a-f0-9-]{36}")))
        require(phase!="confirmed" || response!=null)
    }
    fun json()=JSONObject().put("scope",scope).put("projectId",projectId).put("taskId",taskId ?: JSONObject.NULL).put("roundId",roundId ?: JSONObject.NULL)
        .put("draftId",draftId).put("evidenceId",evidenceId).put("action",action).put("operationId",operationId).put("prepareId",prepareId).put("ticketId",ticketId ?: JSONObject.NULL)
        .put("fileSha256",fileSha256).put("size",size).put("name",name).put("mediaType",mediaType).put("boundary",boundary).put("bodySha256",bodySha256).put("bodySize",bodySize)
        .put("phase",phase).put("attempted",attempted).put("response",response ?: JSONObject.NULL).put("waitEnded",waitEnded)
    companion object {
        fun decode(v: JSONObject)=TaskHubUpload(v.getString("scope"),taskHubUUID(v.getString("projectId")),v.optional("taskId")?.let(::taskHubUUID),v.optional("roundId")?.let(::taskHubUUID),
            taskHubUUID(v.getString("draftId")),taskHubUUID(v.getString("evidenceId")),v.getString("action"),taskHubUUID(v.getString("operationId")),taskHubUUID(v.getString("prepareId")),v.optional("ticketId")?.let(::taskHubUUID),
            v.getString("fileSha256"),v.getInt("size"),v.getString("name"),v.getString("mediaType"),v.getString("boundary"),v.getString("bodySha256"),v.getInt("bodySize"),v.getString("phase"),v.getBoolean("attempted"),v.optional("response"),v.getBoolean("waitEnded"))
    }
}

/** Android owns a single process transfer, independent of the waiting Activity.
 * Process recovery only queries the durable original receipt; it never re-enqueues a POST. */
internal class TaskHubUploadCoordinator(context: Context,private val repository: TaskHubRepository,namespace: String="task-hub-upload-v1") {
    init { require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val root=File(context.noBackupFilesDir.canonicalFile,namespace)
    private val encrypted=NativeSessionStorage(context,namespace+"-encrypted","agent-hub-business-upload-v1")
    private val database=repository.database
    private val scope=repository.scope
    private val transport=repository.transport as? TaskHubUploadTransport ?: error("缺少原生文件传输")
    init { check(!Files.isSymbolicLink(root.toPath()));check(root.mkdirs() || root.isDirectory) }
    companion object {
        private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val flights=ConcurrentHashMap<String,Deferred<String>>()
        fun detach(scope: String) { flights.entries.filter { it.key.startsWith(scope+"/") }.forEach { it.value.cancel() } }
    }
    private fun key(id: String)="upload:$scope/$id"
    private fun fileKey(record: TaskHubUpload)="task-hub:$scope/${record.operationId}/${record.bodySha256}"
    private fun file(id: String): File { taskHubUUID(id);val file=File(root,"$id.body");check(!Files.isSymbolicLink(file.toPath()));return file }
    private fun read(id: String): TaskHubUpload = database.readAll()[key(id)]?.let { TaskHubUpload.decode(JSONObject(it)) }?.also { require(it.scope==scope && it.operationId==id) } ?: error("没有当前账户的原上传记录")
    suspend fun records(): List<TaskHubUpload> = withContext(Dispatchers.IO) { database.readAll().filterKeys { it.startsWith("upload:$scope/") }.map { (key,value) -> TaskHubUpload.decode(JSONObject(value)).also { require(it.scope==scope && key==this@TaskHubUploadCoordinator.key(it.operationId)) } } }
    private fun update(next: TaskHubUpload) = database.transaction { values ->
        val old=values[key(next.operationId)]?.let { TaskHubUpload.decode(JSONObject(it)) } ?: error("原上传记录缺失")
        check(old.copy(phase=next.phase,attempted=next.attempted,response=next.response,waitEnded=next.waitEnded)==next) { "原上传意图已变化" }
        if(old.phase!="prepared" && !TaskHubRules.acceptsPhase(old.phase,next.phase)) return@transaction
        values[key(next.operationId)]=next.copy(waitEnded=next.waitEnded || old.waitEnded).json().toString()
    }
    suspend fun prepare(projectId: String,taskId: String?,roundId: String?,draftId: String,evidenceId: String,action: String,
        name: String,mediaType: String,data: ByteArray): TaskHubUpload = withContext(Dispatchers.IO) {
        taskHubUUID(projectId);taskId?.let(::taskHubUUID);roundId?.let(::taskHubUUID);taskHubUUID(draftId);taskHubUUID(evidenceId)
        require(repository.binding.service.capabilities.contains("business-operation-receipts")) { "原主服务尚未提供可恢复业务回执" }
        require(action in listOf("create","feedback","response","progress","self_check","delivery","acceptance","finalization"))
        require(data.size in 1..20*1024*1024 && name.isNotEmpty() && name.length<=1024 && mediaType.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")))
        val context=repository.verifiedContext()
        val digest=taskHubSha(data)
        val id=UUID.randomUUID().toString();val prepareId=UUID.randomUUID().toString()
        val ticket=if(repository.binding.service.capabilities.contains("attachment-upload-tickets")) UUID.randomUUID().toString() else null
        val boundary="task-hub-"+UUID.randomUUID()
        val safeName=name.replace('"','_').replace('\r','_').replace('\n','_')
        val prefix="--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: $mediaType\r\n\r\n".toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        val bodySize=prefix.size+data.size+suffix.size
        val bodyDigest=java.security.MessageDigest.getInstance("SHA-256")
        val target=file(id);val atomic=AtomicFile(target);val out=atomic.startWrite()
        try {
            // Keep the 20 MiB attachment boundary usable in small Android heaps:
            // spool the multipart once instead of allocating two whole-body copies.
            val stream=java.security.DigestOutputStream(out,bodyDigest)
            stream.write(prefix)
            val contentDigest=java.security.MessageDigest.getInstance("SHA-256")
            val buffer=ByteArray(65536);var position=0
            while(position<data.size) {
                val count=minOf(buffer.size,data.size-position)
                data.copyInto(buffer,0,position,position+count)
                contentDigest.update(buffer,0,count);stream.write(buffer,0,count);position+=count
            }
            stream.write(suffix);stream.flush()
            require(contentDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==digest) { "原上传文件已变化" }
            atomic.finishWrite(out)
        } catch(error: Throwable) { atomic.failWrite(out);throw error }
        val bodySha256=bodyDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val record=TaskHubUpload(scope,projectId,taskId,roundId,draftId,evidenceId,action,id,prepareId,ticket,digest,data.size,name,mediaType,boundary,bodySha256,bodySize)
        var registered=false
        try {
            encrypted.writeBinary(fileKey(record),target,21*1024*1024)
            repository.ensureContext(context)
            database.transaction { values ->
                val old=values.filterKeys { it.startsWith("upload:$scope/") }.values.map { TaskHubUpload.decode(JSONObject(it)) }
                require(old.none { it.phase in listOf("pending","unknown") }) { "请先核对原上传回执，不能重复上传" }
                require(old.none { it.draftId==draftId && it.evidenceId==evidenceId && it.phase!="rejected" }) { "原附件已关联，请先查询原回执" }
                values[key(id)]=record.json().toString()
            }
            registered=true
            record
        } finally { target.delete();if(!registered) encrypted.remove(fileKey(record)) }
    }
    suspend fun submit(id: String): String {
        taskHubUUID(id);val context=repository.verifiedContext();val original=withContext(Dispatchers.IO) { read(id) }
        require(original.phase=="prepared" && !original.attempted) { "原上传已登记，请查询原 ID" }
        val flightKey="$scope/upload"
        val candidate=tasks.async(start=CoroutineStart.LAZY) {
            try {
                if(original.ticketId!=null) {
                    val input=JSONObject().put("ticketId",original.ticketId).put("operationId",id).put("projectId",original.projectId)
                        .put("scope",JSONObject().put("draftId",original.draftId).put("evidenceId",original.evidenceId).put("action",original.action)
                            .put("taskId",original.taskId ?: JSONObject.NULL).put("roundId",original.roundId ?: JSONObject.NULL))
                        .put("name",original.name).put("mediaType",original.mediaType).put("sizeBytes",original.size).put("sha256",original.fileSha256).toString()
                    val previous=repository.operations().firstOrNull { it.id==original.prepareId }
                    if(previous==null) repository.submit(TaskHubOperation(original.prepareId,scope,original.projectId,original.taskId,original.roundId,null,"attachments/prepare","POST",input,taskHubSha(input.toByteArray())))
                    else require(previous.phase=="confirmed" && previous.body==input) { "原票据尚未确认，请查询原准备回执" }
                }
                repository.ensureContext(context)
                val bytes=encrypted.readBinary(fileKey(original),original.bodySize,21*1024*1024)
                require(taskHubSha(bytes)==original.bodySha256)
                val target=file(id);val atomic=AtomicFile(target);val out=atomic.startWrite()
                try { out.write(bytes);atomic.finishWrite(out) } catch(error: Throwable) { atomic.failWrite(out);throw error }
                try {
                    repository.ensureContext(context);update(original.copy(phase="pending",attempted=true))
                    val response=transport.uploadFile(original.projectId,original.ticketId,id,target,"multipart/form-data; boundary=${original.boundary}",original.bodySha256)
                    validateResult(original,response);update(read(id).copy(phase="confirmed",response=response));encrypted.remove(fileKey(original))
                    repository.ensureContext(context);response
                } finally { target.delete() }
            } catch(error: Exception) {
                withContext(NonCancellable) { val latest=read(id)
                    if(latest.attempted) update(latest.copy(phase=TaskHubRules.failurePhase(true,(error as? TaskHubHTTPError)?.status ?: 0))) }
                throw error
            }
        }
        val active=flights.putIfAbsent(flightKey,candidate)
        if(active!=null) { candidate.cancel();error("原上传正在执行，请查询原回执") }
        candidate.invokeOnCompletion { flights.remove(flightKey,candidate) }
        candidate.start();return candidate.await()
    }
    private fun validateResult(record: TaskHubUpload,wire: String) {
        val value=JSONObject(wire)
        require(value.getInt("sizeBytes")==record.size && (value.isNull("sha256") || value.getString("sha256")==record.fileSha256)) { "原上传回执的附件大小或摘要不一致" }
        taskHubUUID(value.getString("id"))
    }
    suspend fun query(id: String): TaskHubUpload = withContext(Dispatchers.IO) {
        val context=repository.verifiedContext();val old=read(id)
        val wire=transport.request("operations/$id")
        require(TaskHubContract.validate(wire,"receipt")==null && TaskHubRules.receiptMatches(id,wire))
        val receipt=JSONObject(wire);val response=receipt.optJSONObject("result")?.toString()
        if(receipt.getString("phase")=="confirmed") validateResult(old,response ?: error("原回执缺少结果"))
        repository.ensureContext(context);update(old.copy(phase=receipt.getString("phase"),response=response))
        if(receipt.getString("phase")=="confirmed") encrypted.remove(fileKey(old))
        read(id)
    }
    suspend fun endWaiting(id: String) = withContext(Dispatchers.IO) { val old=read(id);update(old.copy(phase=if(old.phase=="pending") "unknown" else old.phase,waitEnded=true)) }
}
