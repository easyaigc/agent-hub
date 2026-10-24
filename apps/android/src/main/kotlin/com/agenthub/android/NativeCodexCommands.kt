package com.agenthub.android

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CodexCommandError(message: String, val status: Int? = null, cause: Throwable? = null): Exception(message,cause)

/** Single-attempt HTTP transport. Recovery and durable intent belong to the session, never here. */
class NativeCodexCommands(private val token: (String)->String): CodexCommands {
    companion object { private val executor=Executors.newFixedThreadPool(4) }
    private suspend fun request(client: MobileClient, path: String, query: Map<String,String> = emptyMap(),
        body: JSONObject? = null, definition: String? = null, bytes: ByteArray? = null, mediaType: String? = null): JSONObject =
        exchange(client,path,query,body,definition,bytes,mediaType) { input,active -> JSONObject(NativeWireReader.read(input,if(path.startsWith("v1/voice/")) 2*1024*1024 else 64*1024*1024,active)) }
    private suspend fun <T> exchange(client: MobileClient, path: String, query: Map<String,String> = emptyMap(),
        body: JSONObject? = null, definition: String? = null, bytes: ByteArray? = null, mediaType: String? = null,decode: (java.io.InputStream,()->Boolean)->T): T {
        require(!client.isDemo && client.id.isNotBlank() && client.scope?.hostId==client.id) { "未绑定真实电脑目标" }
        require(path in ROUTES) { "未知电脑操作" }
        val post=body!=null || bytes!=null
        require(!post || path in POST_ROUTES) { "此接口仅可读取" }
        if(body!=null) {
            require(body.getString("hostId")==client.id) { "操作电脑身份不匹配" }
            require(definition!=null); CodexCommandCodec.validate(body,definition)
        }
        val base=NativeHostReads.baseAddress(client.endpoint ?: throw CodexCommandError("尚未配置电脑地址")).toString().trimEnd('/')
        val parameters=mapOf("hostId" to client.id)+query+if(path.startsWith("v1/voice/")) emptyMap() else mapOf("target" to CodexCommandCodec.target(client.scope!!).toString())
        val encoded=parameters.entries.joinToString("&") { (k,v)->URLEncoder.encode(k,"UTF-8")+"="+URLEncoder.encode(v,"UTF-8") }
        val url=URI("$base/$path?$encoded").toURL()
        return suspendCancellableCoroutine { continuation ->
            val active=AtomicReference<HttpURLConnection?>()
            val future=executor.submit {
                var connection: HttpURLConnection?=null
                try {
                    if(!continuation.isActive) return@submit
                    connection=url.openConnection() as HttpURLConnection; active.set(connection)
                    if(!continuation.isActive) return@submit
                    client.codexDirect?.let { paired ->
                        val endpoint=CodexEndpoints.local(client.endpoint!!)
                        require(connection is javax.net.ssl.HttpsURLConnection) { "地址不属于已配对电脑" }
                        PairedCodexTLS.configure(connection as javax.net.ssl.HttpsURLConnection,endpoint,paired)
                    }
                    connection.instanceFollowRedirects=false; connection.useCaches=false
                    connection.connectTimeout=10_000; connection.readTimeout=35_000
                    connection.requestMethod=if(post) "POST" else "GET"
                    connection.setRequestProperty("Accept","application/json")
                    token(client.id).takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Authorization","Bearer $it") }
                    if(post) {
                        val content=bytes ?: body!!.toString().toByteArray(Charsets.UTF_8)
                        connection.doOutput=true; connection.setFixedLengthStreamingMode(content.size)
                        connection.setRequestProperty("Content-Type",mediaType ?: "application/json; charset=utf-8")
                        if(bytes!=null) connection.setRequestProperty("X-Content-SHA256",hash(bytes))
                        connection.outputStream.use { output ->
                            var offset=0
                            while(offset<content.size) {
                                if(!continuation.isActive) return@submit
                                val size=minOf(16_384,content.size-offset); output.write(content,offset,size); offset+=size
                            }
                        }
                    }
                    val status=connection.responseCode
                    if(status!=200) {
                        val detail=try { connection.errorStream?.use { input ->
                            val buffer=ByteArray(8192); val n=input.read(buffer)
                            if(n>0) JSONObject(String(buffer,0,n,Charsets.UTF_8)).optional("error") else null
                        } } catch(_: Exception) { null }
                        throw CodexCommandError(detail ?: "电脑请求失败（$status）",status)
                    }
                    val value=connection.inputStream.use { input -> decode(input) { continuation.isActive } }
                    if(continuation.isActive) continuation.resume(value)
                } catch(failure: Exception) {
                    if(continuation.isActive) continuation.resumeWithException(if(failure is CodexCommandError) failure else CodexCommandError("电脑操作未确认，请查询原操作记录",cause=failure))
                } catch(failure: OutOfMemoryError) {
                    if(continuation.isActive) continuation.resumeWithException(CodexCommandError("手机内存不足，电脑操作结果未确认；请查询原操作记录"))
                } finally { active.getAndSet(null)?.disconnect(); connection?.disconnect() }
            }
            continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
        }
    }
    override suspend fun archiveWorkspace(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt {
        require(request.hostId==client.id&&sameScope(request.target,client.scope))
        return WorkspaceArchiveCodec.receipt(this.request(client,"v1/workspace-management",body=request.body(),definition="workspaceManagementRequest"))
    }
    override suspend fun workspaceArchiveStatus(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt {
        require(request.hostId==client.id&&sameScope(request.target,client.scope))
        return WorkspaceArchiveCodec.receipt(this.request(client,"v1/workspace-management",mapOf("id" to request.operationId)))
    }
    override suspend fun chatAction(client: MobileClient,request: ChatActionRequest): ChatActionReceipt {
        require(request.hostId==client.id&&sameScope(request.target,client.scope)) { "聊天操作目标不匹配" }
        return ChatActionCodec.receipt(request(client,"v1/threads/actions",body=request.body(),definition="chatActionRequest"))
    }
    override suspend fun chatActionStatus(client: MobileClient,threadId: String,operationId: String): ChatActionReceipt =
        ChatActionCodec.receipt(request(client,"v1/threads/actions",mapOf("threadId" to threadId,"id" to operationId)))
    private fun scoped(client: MobileClient,threadId: String) = JSONObject().put("hostId",client.id).put("threadId",threadId).also { require(threadId.isNotBlank()) }
    override suspend fun voiceReadiness(client: MobileClient)=VoiceCodec.readiness(request(client,"v1/voice/readiness")).also { require(it.hostId==client.id) }
    override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt {
        require(request.hostId==client.id)
        return VoiceCodec.receipt(this.request(client,"v1/voice/transcriptions",mapOf("id" to request.operationId)),request)
    }
    override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest)=VoiceCodec.receipt(this.request(client,"v1/voice/transcriptions",body=request.json(),definition="voiceManifest"),request)
    override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt {
        require(request.hostId==client.id);request.verify(bytes)
        return VoiceCodec.receipt(this.request(client,"v1/voice/transcriptions/audio",mapOf("id" to request.operationId),bytes=bytes,mediaType="audio/wav"),request)
    }
    private fun operation(client: MobileClient,id: String) = JSONObject().put("hostId",client.id).put("clientOperationId",id).also { require(id.isNotBlank()) }
    private fun verify(client: MobileClient,value: DeliveryReceipt,thread: String,id: String): DeliveryReceipt {
        require(value.hostId==client.id && value.threadId==thread && value.operationId==id) { "消息回执目标不匹配" }; return value
    }
    private fun verify(client: MobileClient,value: DesktopCreation,id: String): DesktopCreation {
        require(value.hostId==client.id && value.operationId==id) { "新建回执目标不匹配" }
        value.thread?.let { require(it.hostId==client.id && it.id==value.threadId && it.projectId==value.projectId) { "新建聊天身份不匹配" } }
        require(value.phase!="confirmed" || value.thread!=null) { "新建确认缺少准确聊天" }; return value
    }
    override suspend fun forkOptions(client: MobileClient,threadId: String)=ThreadForkCodec.options(request(client,"v1/threads/fork-options",mapOf("threadId" to threadId))).also {
        require(it.hostId==client.id && it.threadId==threadId) { "分支选项目标不匹配" }
    }
    override suspend fun forkThread(client: MobileClient,request: ThreadForkRequest): ThreadForkOperation {
        require(request.hostId==client.id)
        return ThreadForkCodec.operation(this.request(client,"v1/threads/fork",body=request.body(),definition="threadForkRequest")).also {
            require(it.request==request) { "分支回执原意图不匹配" }
        }
    }
    override suspend fun forkStatus(client: MobileClient,threadId: String,operationId: String)=ThreadForkCodec.operation(request(client,"v1/threads/fork",mapOf("threadId" to threadId,"id" to operationId))).also {
        require(it.request.hostId==client.id && it.request.threadId==threadId && it.request.operationId==operationId) { "分支回执目标不匹配" }
    }
    override suspend fun models(client: MobileClient) = CodexCommandCodec.models(request(client,"v1/models"))
    override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
        require(threadId.isNotBlank() && cursor in 0..9007199254740991L)
        val batch=exchange(client,"v1/events",mapOf("threadId" to threadId,"window" to "latest-v1","epoch" to epoch,"after" to cursor.toString())+(client.readerId?.let { mapOf("readerId" to nativeReaderId(it)) } ?: emptyMap()),decode=NativeHistoryWire::events)
        require(batch.hostId==client.id && batch.snapshots.all { it.threadId==threadId && (!client.requiresReadTarget || it.scope!=null) && (it.scope==null || sameScope(it.scope,client.scope)) }) { "事件运行时或聊天身份不匹配" }
        return batch
    }
    override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
        require(intent.hostId==client.id)
        return verify(client,CodexCommandCodec.receipt(request(client,"v1/replies",body=intent.body(),definition="replyRequest")),intent.threadId,intent.operationId)
    }
    override suspend fun receipt(client: MobileClient,threadId: String,operationId: String) = verify(client,
        CodexCommandCodec.receipt(request(client,"v1/receipts",mapOf("id" to operationId,"threadId" to threadId))),threadId,operationId)
    override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String): JSONObject {
        require(threadId.isNotBlank() && operationId.isNotBlank())
        return CodexCommandCodec.validate(request(client,"v1/replies/status",mapOf("id" to operationId,"threadId" to threadId)),"replyInspection").also {
            require(it.getString("hostId")==client.id && it.getString("threadId")==threadId && it.getString("clientMessageId")==operationId) { "原消息检查目标不匹配" }
            require(CodexCommandCodec.target(HostReadCodec.target(it.getJSONObject("target"))!!).toString()==CodexCommandCodec.target(client.scope!!).toString()) { "原消息检查运行目标不匹配" }
        }
    }
    override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread {
        val json=request(client,"v1/threads",body=operation(client,operationId).put("projectId",projectId ?: JSONObject.NULL),definition="createThreadRequest")
        val thread=HostReadCodec.thread(CodexCommandCodec.validate(json,"thread"))
        require(thread.hostId==client.id && thread.projectId==projectId) { "新建聊天归属不匹配" }; return thread
    }
    override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation {
        require(intent.hostId==client.id)
        val value=verify(client,CodexCommandCodec.creation(request(client,"v1/desktop/creations",body=intent.body(),definition="creationRequest")),intent.operationId)
        require(value.projectId==intent.projectId && value.uploaded.all { id->intent.files.any { it.id==id } }) { "新建意图不匹配" }; return value
    }
    override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean) = verify(client,
        CodexCommandCodec.creation(request(client,if(inspectOnly) "v1/desktop/creation-receipts" else "v1/desktop/creations",mapOf("id" to operationId))),operationId)
    override suspend fun submitCreation(client: MobileClient,operationId: String) = verify(client,
        CodexCommandCodec.creation(request(client,"v1/desktop/creations/submit",body=operation(client,operationId),definition="submitCreationRequest")),operationId)
    override suspend fun detachCreation(client: MobileClient,operationId: String) = verify(client,
        CodexCommandCodec.creation(request(client,"v1/desktop/creations/detach",body=operation(client,operationId).put("acknowledgeUncertain",true),definition="detachCreationRequest")),operationId)
    override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen {
        val value=CodexCommandCodec.open(if(queryOnly) request(client,"v1/desktop/open-status",mapOf("threadId" to threadId,"id" to operationId))
            else request(client,"v1/desktop/open",body=scoped(client,threadId).put("clientOperationId",operationId),definition="openRequest"))
        require(value.hostId==client.id && value.threadId==threadId && value.operationId==operationId) { "打开回执目标不匹配" }; return value
    }
    private suspend fun command(client: MobileClient,path: String,body: JSONObject,definition: String) {
        require(CodexCommandCodec.validate(request(client,path,body=body,definition=definition),"commandResult").getBoolean("ok")) { "电脑没有完成此操作" }
    }
    override suspend fun stop(client: MobileClient,threadId: String,turnId: String) = command(client,"v1/stop",scoped(client,threadId).put("turnId",turnId),"stopRequest")
    override suspend fun executeQueue(client: MobileClient,threadId: String,operationId: String,expectedTurnId: String?) {
        require(threadId.isNotBlank() && operationId.isNotBlank() && (expectedTurnId==null || expectedTurnId.isNotBlank()))
        command(client,"v1/queue/execute",scoped(client,threadId).put("clientMessageId",operationId)
            .apply { expectedTurnId?.let { put("expectedTurnId",it) } },"executeQueueRequest")
    }
    override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String) = command(client,"v1/queue/cancel",scoped(client,threadId).put("clientMessageId",operationId),"cancelQueueRequest")
    override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) = command(client,"v1/approvals",
        scoped(client,threadId).put("turnId",turnId).put("approvalId",approvalId).put("decision",decision).put("answers",JSONObject(answers.mapValues { JSONArray(it.value) })),"approvalRequest")
    private fun bytes(file: CreationFile,bytes: ByteArray) {
        CodexCommandCodec.validate(file.body(),"creationFile"); require(bytes.size.toLong()==file.sizeBytes && hash(bytes)==file.sha256) { "附件内容已改变，未上传" }
    }
    override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile {
        bytes(file,bytes)
        val v=CodexCommandCodec.validate(request(client,"v1/attachments",mapOf("threadId" to threadId,"name" to file.name),bytes=bytes,mediaType=file.mediaType),"attachment")
        require(v.getString("hostId")==client.id && v.getString("threadId")==threadId && v.getString("sha256")==file.sha256 && v.getLong("sizeBytes")==file.sizeBytes && v.getString("name")==file.name) { "附件回执不匹配" }
        return MobileFile(v.getString("id"),v.getString("name"),v.getString("mediaType"),v.getLong("sizeBytes"),client.id,threadId,v.getString("sha256"))
    }
    override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray) {
        bytes(file,bytes)
        val v=CodexCommandCodec.validate(request(client,"v1/desktop/creation-attachments",mapOf("id" to operationId,"attachmentId" to file.id),bytes=bytes,mediaType=file.mediaType),"creationUpload")
        require(v.getString("hostId")==client.id && v.getString("clientOperationId")==operationId && v.getString("id")==file.id && v.getString("sha256")==file.sha256 && v.getLong("sizeBytes")==file.sizeBytes) { "首条附件回执不匹配" }
    }
}
private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private val POST_ROUTES=setOf("v1/workspace-management","v1/threads/actions","v1/replies","v1/threads/fork","v1/threads","v1/desktop/creations","v1/desktop/creations/submit","v1/desktop/creations/detach","v1/desktop/open","v1/stop","v1/queue/execute","v1/queue/cancel","v1/approvals","v1/attachments","v1/desktop/creation-attachments","v1/voice/transcriptions","v1/voice/transcriptions/audio")
private val ROUTES=POST_ROUTES+setOf("v1/threads/fork-options","v1/models","v1/events","v1/receipts","v1/replies/status","v1/desktop/creation-receipts","v1/desktop/open-status","v1/voice/readiness")
