package com.agenthub.android

import com.agenthub.core.CodexHttpContract
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject

data class GenerationSettings(val model: String? = null, val effort: String? = null,
    val permissionMode: String? = null, val speed: String? = null, val collaborationMode: String? = null) {
    fun append(body: JSONObject): JSONObject {
        model?.let { body.put("model", it) }; effort?.let { body.put("effort", it) }
        permissionMode?.let { body.put("permissionMode", it) }; speed?.let { body.put("speed", it) }
        collaborationMode?.let { body.put("collaborationMode", it) }; return body
    }
}
data class CodexModel(val id: String, val name: String, val isDefault: Boolean,
    val defaultEffort: String?, val supportedEfforts: List<String>, val defaultSpeed: String?, val supportedSpeeds: List<String>)
data class ReplyIntent(val hostId: String, val threadId: String, val operationId: String,
    val text: String, val attachmentIds: List<String>, val mode: String,
    val expectedTurnId: String? = null, val expectedPlanId: String? = null, val settings: GenerationSettings = GenerationSettings()) {
    fun body() = settings.append(JSONObject().put("hostId",hostId).put("threadId",threadId)
        .put("clientMessageId",operationId).put("text",text).put("attachmentIds",JSONArray(attachmentIds)).put("mode",mode)
        .apply { expectedTurnId?.let { put("expectedTurnId",it) }; expectedPlanId?.let { put("expectedPlanId",it) } })
}
data class CreationFile(val id: String, val name: String, val mediaType: String, val sizeBytes: Long, val sha256: String) {
    fun body() = JSONObject().put("id",id).put("name",name).put("mediaType",mediaType).put("sizeBytes",sizeBytes).put("sha256",sha256)
}
data class CreationIntent(val hostId: String, val operationId: String, val projectId: String?, val text: String,
    val files: List<CreationFile>, val settings: GenerationSettings = GenerationSettings()) {
    fun body() = settings.append(JSONObject().put("hostId",hostId).put("clientOperationId",operationId)
        .put("projectId",projectId ?: JSONObject.NULL).put("text",text).put("attachments",JSONArray(files.map { it.body() })))
}
data class DeliveryReceipt(val hostId: String, val threadId: String, val operationId: String, val state: String,
    val turnId: String?, val error: String?, val supersededByTurnId: String?, val text: String?, val attachmentIds: List<String>,
    val noticeContext: String? = null, val queueSource: String? = null, val queuePresent: Boolean? = null,
    val canExecuteNow: Boolean? = null, val queueDisposition: String? = null, val queueSyncError: String? = null, val laterTurnId: String? = null)
data class DesktopCreation(val hostId: String, val operationId: String, val projectId: String?, val phase: String,
    val threadId: String?, val error: String?, val uploaded: List<String>, val thread: MobileThread?, val detached: Boolean,
    val preparation: CreationPreparation? = null) {
    val diagnostic get()=preparation?.diagnostic
}
data class CreationPreparation(val preparedThreadId: String?,val preparationPhase: String,val diagnostic: CreationDiagnostic?) {
    fun body()=JSONObject().put("preparedThreadId",preparedThreadId ?: JSONObject.NULL).put("preparationPhase",preparationPhase)
        .apply { diagnostic?.let { put("diagnostic",it.body()) } }
}
data class CreationDiagnostic(val stage: String,val code: String,val disposition: String) {
    fun body()=JSONObject().put("stage",stage).put("code",code).put("disposition",disposition)
}
data class DesktopOpen(val hostId: String, val threadId: String, val operationId: String, val phase: String, val error: String?)
data class EventBatch(val hostId: String, val epoch: String, val cursor: Long, val reset: Boolean,
    val ready: Boolean?, val capabilities: List<String>?, val snapshots: List<MobileHistory>)

/** Provider wire stays here. Public schema rules are shared with Swift and Node. */
object CodexCommandCodec {
    fun target(value: TargetScope): JSONObject = JSONObject().put("agentId",value.agentId).put("hostId",value.hostId)
        .put("workspaceId",value.workspaceId).put("runtimeId",value.runtimeId).apply {
            if(value.accountAuthority!=null) put("account",JSONObject().put("authority",value.accountAuthority).put("subject",value.accountSubject))
        }.also { validate(it,"target") }
    fun validate(body: JSONObject, definition: String): JSONObject {
        require(CodexHttpContract.validate(body.toString(),definition)==null) { "电脑请求或返回格式无效（$definition）" }
        return body
    }
    fun decode(wire: String, definition: String) = validate(JSONObject(wire),definition)
    fun receipt(body: JSONObject): DeliveryReceipt {
        val v=validate(body,"deliveryReceipt")
        val context=v.opt("noticeContext").takeUnless { it==JSONObject.NULL }
        require(context==null || context is String) { "消息回执上下文格式无效" }
        return DeliveryReceipt(v.getString("hostId"),v.getString("threadId"),v.getString("clientMessageId"),v.getString("state"),
            v.optional("turnId"),v.optional("error"),v.optional("supersededByTurnId"),v.optional("text"),v.optJSONArray("attachmentIds")?.strings().orEmpty(),context as String?,v.optional("queueSource"),v.booleanOrNull("queuePresent"),v.booleanOrNull("canExecuteNow"),v.optional("queueDisposition"),v.optional("queueSyncError"),v.optional("laterTurnId"))
    }
    fun creation(body: JSONObject): DesktopCreation {
        val v=validate(body,"desktopCreation")
        return DesktopCreation(v.getString("hostId"),v.getString("clientOperationId"),v.optional("projectId"),v.getString("phase"),
            v.optional("threadId"),v.optional("error"),v.getJSONArray("uploaded").strings(),v.optJSONObject("thread")?.let(HostReadCodec::thread),v.optBoolean("detached"),
            v.optJSONObject("preparation")?.let { p->CreationPreparation(p.optional("preparedThreadId"),p.getString("preparationPhase"),
                p.optJSONObject("diagnostic")?.let { CreationDiagnostic(it.getString("stage"),it.getString("code"),it.getString("disposition")) }) })
    }
    fun open(body: JSONObject): DesktopOpen {
        val v=validate(body,"desktopOpen")
        return DesktopOpen(v.getString("hostId"),v.getString("threadId"),v.getString("clientOperationId"),v.getString("phase"),v.optional("error"))
    }
    fun models(body: JSONObject): List<CodexModel> = validate(body,"models").getJSONArray("models").objects().map {
        CodexModel(it.getString("id"),it.getString("name"),it.getBoolean("isDefault"),it.optional("defaultEffort"),
            it.optJSONArray("supportedEfforts")?.strings().orEmpty(),it.optional("defaultSpeed"),it.optJSONArray("supportedSpeeds")?.strings().orEmpty())
    }
    fun events(body: JSONObject): EventBatch {
        val v=validate(body,"events")
        val snapshots=v.getJSONArray("events").objects().map {
            HostReadCodec.history(it.getJSONObject("snapshot").toString()).also { page ->
                require(page.hostId==v.getString("hostId") && page.threadId==it.getString("threadId")) { "事件目标不匹配" }
            }
        }
        return EventBatch(v.getString("hostId"),v.getString("epoch"),v.getLong("cursor"),v.getBoolean("reset"),
            if(v.has("ready") && !v.isNull("ready")) v.getBoolean("ready") else null,
            v.optJSONArray("capabilities")?.strings(),snapshots)
    }
}

/** Commands have specific names and immutable target arguments; no arbitrary route or method API. */
interface AgentCommands {
    suspend fun archiveWorkspace(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt = error("此客户端尚未支持批量归档")
    suspend fun workspaceArchiveStatus(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt = error("此客户端尚未支持批量归档")
    suspend fun chatAction(client: MobileClient,request: ChatActionRequest): ChatActionReceipt = error("此客户端尚未支持聊天管理")
    suspend fun chatActionStatus(client: MobileClient,threadId: String,operationId: String): ChatActionReceipt = error("此客户端尚未支持聊天管理")
    suspend fun voiceReadiness(client: MobileClient): VoiceReadiness = error("此电脑尚未支持录音转写，请更新电脑连接程序。")
    suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt = error("此电脑尚未支持录音转写")
    suspend fun prepareVoice(client: MobileClient,request: VoiceManifest): VoiceReceipt = error("此电脑尚未支持录音转写")
    suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt = error("此电脑尚未支持录音转写")
    suspend fun createProject(client: MobileClient,name: String,directory: String,operationId: String): MobileProject=error("请先在桌面授权项目目录")
    suspend fun createdProject(client: MobileClient,operationId: String): MobileProject=error("请在电脑核对项目创建结果")
    fun agentTitle(client: MobileClient)="Codex"
    fun permissionOptions(client: MobileClient)=listOf(AgentPermissionOption("read-only","请求批准","编辑外部文件和使用互联网时始终询问",appOwnedCopy=true),AgentPermissionOption("guardian-approvals","帮我批准","仅对检测到的风险操作请求批准",appOwnedCopy=true),AgentPermissionOption("full-access","完全访问权限","可访问互联网和电脑上的任何文件",true,appOwnedCopy=true))
    fun creationOperationId(client: MobileClient,parent: String): String = parent
    suspend fun createdThread(client: MobileClient, operationId: String): MobileThread = error("此电脑未提供创建状态查询，请核对桌面历史")
    suspend fun forkOptions(client: MobileClient,threadId: String): ThreadForkOptions = error("此客户端尚未支持对话分支")
    suspend fun forkThread(client: MobileClient,request: ThreadForkRequest): ThreadForkOperation = error("此客户端尚未支持对话分支")
    suspend fun forkStatus(client: MobileClient,threadId: String,operationId: String): ThreadForkOperation = error("此客户端尚未支持对话分支")
    suspend fun models(client: MobileClient): List<CodexModel>
    suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long): EventBatch
    suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt
    suspend fun receipt(client: MobileClient, threadId: String, operationId: String): DeliveryReceipt
    suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String): JSONObject = error("此电脑未提供权威投递检查，请继续查询原回执")
    suspend fun createThread(client: MobileClient, projectId: String?, operationId: String): MobileThread
    suspend fun prepareCreation(client: MobileClient, intent: CreationIntent): DesktopCreation
    suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean = false): DesktopCreation
    suspend fun submitCreation(client: MobileClient, operationId: String): DesktopCreation
    suspend fun detachCreation(client: MobileClient, operationId: String): DesktopCreation
    suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean = false): DesktopOpen
    suspend fun stop(client: MobileClient, threadId: String, turnId: String)
    suspend fun executeQueue(client: MobileClient, threadId: String, operationId: String, expectedTurnId: String?): Unit = error("此客户端未开放立即执行队列")
    suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String)
    suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String,List<String>>)
    suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray): MobileFile
    suspend fun resolveMessageFile(client: MobileClient,threadId: String,messageId: String,reference: String): MobileFile = throw CodexCommandError("此电脑未提供逻辑资源下载",appOwnedCopy=true)
    suspend fun downloadResource(client: MobileClient,file: MobileFile,cache: java.io.File): java.io.File = throw CodexCommandError("此电脑未提供逻辑资源下载",appOwnedCopy=true)
    suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray)
}
typealias CodexCommands = AgentCommands
/** appOwnedCopy is native adapter metadata, never decoded from provider/wire content. */
data class AgentPermissionOption(val id: String,val title: String,val detail: String,val highPrivilege: Boolean=false,val appOwnedCopy: Boolean=false)
class AgentOperationNotSubmitted(message: String?,cause: Throwable?=null): Exception(message,cause)
