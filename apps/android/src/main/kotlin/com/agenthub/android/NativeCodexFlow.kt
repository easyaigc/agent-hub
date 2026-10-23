package com.agenthub.android

import com.agenthub.core.MobileRules
import com.agenthub.core.SessionAccess
import com.agenthub.core.SessionPolicies
import com.agenthub.core.ReplyRecoveryRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class LocalDraftFile(val manifest: CreationFile)
data class SubmissionOrigin(val key: String,val version: String)
data class CodexContent(val text: String,val files: List<LocalDraftFile>,val settings: GenerationSettings,val sharingScope: String? = null)
data class CodexFlowResult(val record: SavedOperation,val thread: MobileThread? = null,val project: MobileProject?=null)

/** Platform storage holds source bytes; submitted snapshots never follow later draft edits. */
class NativeDraftFiles(private val storage: NativeSessionStorage) {
    suspend fun import(name: String,mediaType: String,bytes: ByteArray): LocalDraftFile = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length<=512 && mediaType.isNotBlank()) { "附件名称或类型无效" }
        require(bytes.size<=20*1024*1024) { "单个附件不能超过 20 MiB" }
        val file=CreationFile(UUID.randomUUID().toString(),name,mediaType,bytes.size.toLong(),sha(bytes))
        CodexCommandCodec.validate(file.body(),"creationFile")
        storage.write("draft-file:${file.id}",JSONObject().put("file",file.body())
            .put("bytes",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).toString())
        LocalDraftFile(file)
    }
    suspend fun bytes(file: CreationFile): ByteArray = withContext(Dispatchers.IO) {
        val saved=JSONObject(storage.read("draft-file:${file.id}") ?: error("原附件不存在，未提交"))
        val original=saved.getJSONObject("file")
        require(original.toString()==file.body().toString()) { "原附件清单已改变" }
        android.util.Base64.decode(saved.getString("bytes"),android.util.Base64.NO_WRAP).also {
            require(it.size.toLong()==file.sizeBytes && sha(it)==file.sha256) { "原附件内容已改变，未提交" }
        }
    }
    companion object { private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
}

/** Executes one explicitly requested operation. Queries never replay a model input. */
class NativeCodexFlow(private val storage: NativeSessionStorage,private val reads: HostReads,
    private val commands: CodexCommands) {
    val journal=NativeOperationJournal(storage)
    val files=NativeDraftFiles(storage)
    private fun matches(client: MobileClient,workspace: MobileWorkspace) = workspace.hostId==client.id && sameScope(workspace.scope,client.scope)
    private suspend fun authority(client: MobileClient,thread: String?,projectId: String?,guard: ()->Boolean): Pair<MobileWorkspace,MobileHistory?> {
        check(guard()) { "目标或草稿已改变，未提交" }
        val workspace=reads.workspace(client)
        require(matches(client,workspace) && workspace.runtimeConnected!=false && "commands:target-bound:v1" in workspace.capabilities) { "电脑尚未提供可核对目标的执行连接" }
        require(projectId==null || workspace.projects.any { it.id==projectId }) { "项目已不存在" }
        val history=thread?.let { reads.history(client,it) }
        require(history==null || history.hostId==client.id && history.threadId==thread && sameScope(history.scope,client.scope)) { "聊天运行时身份不匹配" }
        check(guard()) { "目标或草稿已改变，未提交" }
        return workspace to history
    }
    private fun access(client: MobileClient,w: MobileWorkspace,h: MobileHistory?,project: String?) = SessionAccess(false,client.online,w.runtimeConnected,w.capabilities,h!=null,project!=null,project==null,h?.runtime?.canControl==true,false)
    private suspend fun source(client: MobileClient,id: String,origin: SubmissionOrigin,content: CodexContent) {
        require(MobileRules.hasContent(content.text,content.files.size) && content.text.length<=100_000 && content.files.size<=10) { "消息为空或超出文字／附件限制" }
        val body=content.settings.append(JSONObject().put("hostId",client.id).put("target",CodexCommandCodec.target(client.scope!!))
            .put("sourceKey",origin.key).put("sourceVersion",origin.version).put("text",content.text).put("files",JSONArray(content.files.map { it.manifest.body() })))
        require(body.toString().toByteArray(Charsets.UTF_8).size<=150_000) { "消息超出电脑请求大小限制" }
        withContext(Dispatchers.IO) { storage.write("submission:$id",body.toString()) }
    }
    suspend fun submit(client: MobileClient,threadId: String?,projectId: String?,origin: SubmissionOrigin,
        content: CodexContent,mode: String,expectedTurnId: String?,expectedPlanId: String? = null,
        guard: ()->Boolean,progress: (String)->Unit = {},operationId: String=UUID.randomUUID().toString()): CodexFlowResult {
        val id=operationId
        source(client,id,origin,content)
        progress("核对电脑与聊天…")
        var (workspace,history)=authority(client,threadId,projectId,guard)
        val policy=access(client,workspace,history,projectId)
        require(SessionPolicies.compose(policy) && (threadId!=null || SessionPolicies.createThread(policy)) &&
            (threadId!=null || projectId!=null || SessionPolicies.createProjectless(policy))) { "当前连接不能创建或发送" }
        validateReplyAuthority(workspace,history,mode,expectedTurnId,expectedPlanId)
        if(threadId==null && "threads:create:staged" in workspace.capabilities) {
            val intent=CreationIntent(client.id,id,projectId,content.text,content.files.map { it.manifest },content.settings)
            var record=journal.prepare(client,null,"creation",intent.body(),origin.key,origin.version,sharingScope=content.sharingScope)
            try {
                check(guard()); record=journal.markPreparing(record.target,id); progress("电脑准备聊天…")
                val prepared=commands.prepareCreation(client,intent)
                record=journal.observed(record.target,id,prepared.phase,encodeCreation(prepared),prepared.error)
                if(prepared.phase!="prepared") return CodexFlowResult(record,prepared.thread)
                return finishCreation(client,record,content.files,guard,progress)
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(record.target,id,failure.message)) }
        }
        var thread=threadId
        var created: MobileThread?=null
        if(thread==null) {
            val createId=commands.creationOperationId(client,id)
            val intent=JSONObject().put("hostId",client.id).put("clientOperationId",createId).put("projectId",projectId ?: JSONObject.NULL)
            var record=journal.prepare(client,null,"thread-create",intent,origin.key,origin.version,if(createId!=id)id else null,sharingScope=content.sharingScope)
            try {
                check(guard()); record=journal.markSubmitting(record.target,createId); progress("创建聊天…")
                created=commands.createThread(client,projectId,createId)
                journal.observed(record.target,createId,"confirmed",encodeThread(created)); thread=created.id
                val fresh=authority(client,thread,projectId,guard); workspace=fresh.first; history=fresh.second
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(record.target,createId,failure.message)) }
        }
        val exactThread=thread!!
        if("runtime:control-required" in workspace.capabilities && history?.runtime?.canControl!=true) {
            require(SessionPolicies.desktop(policy,"desktop:open")) { "原桌面尚未开放主动打开" }
            val openId=UUID.randomUUID().toString()
            var opening=journal.prepare(client,exactThread,"open",JSONObject().put("hostId",client.id).put("threadId",exactThread).put("clientOperationId",openId),origin.key,origin.version,id)
            try {
                check(guard()); opening=journal.markSubmitting(opening.target,openId); progress("正在原桌面打开…")
                var result=commands.open(client,exactThread,openId)
                opening=journal.observed(opening.target,openId,result.phase,encodeOpen(result),result.error)
                // Opening is an accepted asynchronous command. Observe that same
                // receipt without another POST; uncertain results remain query-only.
                val deadline=System.nanoTime()+35_000_000_000L
                while(result.phase=="opening" && guard() && System.nanoTime()<deadline) {
                    delay(350)
                    result=commands.open(client,exactThread,openId,queryOnly=true)
                    opening=journal.observed(opening.target,openId,result.phase,encodeOpen(result),result.error)
                }
                if(result.phase!="opened") return CodexFlowResult(opening,created)
                val fresh=authority(client,exactThread,projectId,guard); workspace=fresh.first; history=fresh.second
                require(history?.runtime?.canControl==true && SessionPolicies.writable(access(client,workspace,history,projectId))) { "原 owner 尚未确认控制权" }
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(opening.target,openId,failure.message),created) }
        }
        require(SessionPolicies.writable(access(client,workspace,history,projectId))) { "当前聊天不能执行" }
        val attachmentIds=content.files.mapIndexed { index,file ->
            check(guard()) { "目标或草稿已改变，未提交" }; progress("上传附件 ${index+1}/${content.files.size} · ${file.manifest.name}")
            commands.upload(client,exactThread,file.manifest,files.bytes(file.manifest)).id
        }
        // The final request is immutable, including the returned IDs of the exact source bytes.
        val reply=ReplyIntent(client.id,exactThread,id,content.text,attachmentIds,mode,expectedTurnId,expectedPlanId,content.settings)
        var record=journal.prepare(client,exactThread,"reply",reply.body(),origin.key,origin.version,sharingScope=content.sharingScope)
        try {
            check(guard()) { "目标或草稿已改变，未提交" }
            val fresh=authority(client,exactThread,projectId,guard)
            require(SessionPolicies.writable(access(client,fresh.first,fresh.second,projectId))) { "控制权已变化，未提交" }
            validateReplyAuthority(fresh.first,fresh.second,mode,expectedTurnId,expectedPlanId)
            record=journal.markSubmitting(record.target,id); progress("提交给 ${client.name}…")
            val receipt=commands.send(client,reply)
            return CodexFlowResult(journal.observed(record.target,id,receipt.state,encodeReceipt(receipt),receipt.error),created)
        } catch(failure: Exception) {
            val failed=if(record.submissionAttempted) journal.uncertain(record.target,id,failure.message)
                else journal.replyNotSubmitted(record.target,id,failure.message)
            return CodexFlowResult(failed,created)
        }
    }
    private fun validateReplyAuthority(workspace: MobileWorkspace,history: MobileHistory?,mode: String,expectedTurnId: String?,expectedPlanId: String?) {
        require(expectedPlanId==null || history?.runtime?.proposedPlan?.id==expectedPlanId && !SessionPolicies.running(history.runtime.turnId,history.runtime.state)) { "计划已变化，请重新查看" }
        if(mode in listOf("queue","steer")) require(mode in workspace.capabilities && expectedTurnId!=null && history?.turnId==expectedTurnId &&
            SessionPolicies.running(history.runtime?.turnId,history.runtime?.state)) { "任务或执行能力已变化，未排队或引导" }
    }
    private suspend fun finishCreation(client: MobileClient,record: SavedOperation,sourceFiles: List<LocalDraftFile>,
        guard: ()->Boolean,progress: (String)->Unit,explicitContinuation: Boolean=false): CodexFlowResult {
        require(record.kind=="creation" && mayContinueCreation(record) && (!record.submissionAttempted || explicitContinuation))
        require(record.target in client.operationTargets(null)) { "原创建记录不属于当前电脑目标" }
        var current=record
        try {
            creationAuthority(client,record,guard)
            val uploaded=record.result?.let { CodexCommandCodec.creation(JSONObject(it)).uploaded }.orEmpty()
            sourceFiles.filter { it.manifest.id !in uploaded }.forEachIndexed { index,file ->
                check(guard()); progress("上传首条附件 ${index+1}/${sourceFiles.size} · ${file.manifest.name}")
                commands.uploadCreation(client,record.id,file.manifest,files.bytes(file.manifest))
            }
            // Preparation and upload can outlive a capability or project change.
            // Recheck the complete target before making the first input attempt.
            creationAuthority(client,record,guard)
            if(record.submissionAttempted) {
                check(guard())
                val fresh=commands.creation(client,record.id,inspectOnly=true)
                current=journal.observed(record.target,record.id,fresh.phase,encodeCreation(fresh),fresh.error)
                if(!mayContinueCreation(current)) return CodexFlowResult(current,fresh.thread)
            }
            check(guard())
            current=if(current.submissionAttempted) journal.markCreationRetrySubmitting(record.target,record.id) else journal.markSubmitting(record.target,record.id)
            progress("提交新聊天…")
            val result=commands.submitCreation(client,record.id)
            return CodexFlowResult(journal.observed(record.target,record.id,result.phase,encodeCreation(result),result.error),result.thread)
        } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(current.target,current.id,failure.message)) }
    }
    private suspend fun creationAuthority(client: MobileClient,record: SavedOperation,guard: ()->Boolean) {
        val project=JSONObject(record.intent).optional("projectId")
        val (workspace,history)=authority(client,null,project,guard)
        val policy=access(client,workspace,history,project)
        require("threads:create:staged" in workspace.capabilities && SessionPolicies.compose(policy) &&
            SessionPolicies.createThread(policy) && (project!=null || SessionPolicies.createProjectless(policy))) { "电脑已撤回创建能力，原首条尚未提交" }
    }
    suspend fun query(client: MobileClient,record: SavedOperation): CodexFlowResult {
        require(record.target in client.operationTargets(if(record.kind in listOf("reply","open","fork")) JSONObject(record.intent).getString("threadId") else null))
        return when(record.kind) {
            "fork" -> NativeThreadForkFlow(journal,reads,commands).query(client,record)
            "project-create" -> commands.createdProject(client,record.id).let { CodexFlowResult(journal.observed(record.target,record.id,"confirmed",encodeProject(it)),project=it) }
            "reply" -> queryReply(client,record)
            "thread-create" -> commands.createdThread(client,record.id).let { CodexFlowResult(journal.observed(record.target,record.id,"confirmed",encodeThread(it)),it) }
            "creation" -> commands.creation(client,record.id,inspectOnly=true).let { CodexFlowResult(journal.observed(record.target,record.id,it.phase,encodeCreation(it),it.error),it.thread) }
            "open" -> commands.open(client,JSONObject(record.intent).getString("threadId"),record.id,queryOnly=true).let { CodexFlowResult(journal.observed(record.target,record.id,it.phase,encodeOpen(it),it.error)) }
            else -> throw CodexCommandError("此创建结果需在电脑核对；原操作不会重放")
        }
    }
    suspend fun continuePrepared(client: MobileClient,record: SavedOperation,guard: ()->Boolean,progress: (String)->Unit): CodexFlowResult {
        require(record.kind=="creation" && !record.detached && mayContinueCreation(record)) { "原首条结果待核对，只能查询原记录" }
        val manifest=JSONObject(record.intent).getJSONArray("attachments").objects().map { f -> LocalDraftFile(CreationFile(f.getString("id"),f.getString("name"),f.getString("mediaType"),f.getLong("sizeBytes"),f.getString("sha256"))) }
        return finishCreation(client,record,manifest,guard,progress,explicitContinuation=true)
    }
    fun mayContinueCreation(record: SavedOperation): Boolean {
        val result=record.result?.let { CodexCommandCodec.creation(JSONObject(it)) }
        return record.kind=="creation" && !record.detached &&
            ReplyRecoveryRules.mayContinueCreation(record.phase,record.submissionAttempted,result?.diagnostic?.disposition,
                result?.preparation?.preparationPhase,result?.preparation?.preparedThreadId)
    }
    private suspend fun queryReply(client: MobileClient,record: SavedOperation): CodexFlowResult {
        val thread=JSONObject(record.intent).getString("threadId")
        try {
            val receipt=commands.receipt(client,thread,record.id)
            return CodexFlowResult(journal.observed(record.target,record.id,receipt.state,encodeReceipt(receipt),receipt.error))
        } catch(cancel: CancellationException) { throw cancel }
        catch(missing: Exception) {
            val current=journal.records(record.target).single { it.id==record.id }
            val recovery=current.replyRecovery
            if(recovery==null || !recovery.matches(client,current) || recovery.consumed || current.result!=null) throw missing
            try {
                val inspection=commands.replyDeliveryStatus(recovery.capturedClient(client,current),thread,record.id)
                if(inspection.getString("status")=="accepted" && inspection.optJSONObject("receipt")!=null) {
                    val receipt=CodexCommandCodec.receipt(inspection.getJSONObject("receipt"))
                    return CodexFlowResult(journal.observed(record.target,record.id,receipt.state,encodeReceipt(receipt),receipt.error))
                }
                return CodexFlowResult(journal.observeReplyInspection(client,record.target,record.id,inspection))
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { journal.invalidateReplyInspection(record.target,record.id);throw failure }
        }
    }
    /** A user action only. A fresh authority read is followed by durable one-time consumption. */
    suspend fun retryReply(client: MobileClient,record: SavedOperation,guard: ()->Boolean,progress: (String)->Unit): CodexFlowResult {
        var current=journal.records(record.target).single { it.id==record.id }
        val recovery=current.replyRecovery ?: error("旧操作未捕获恢复授权，只能查询原回执")
        require(recovery.mayRetry(client,current)) { "原消息仍待核对，不能重新提交" }
        val captured=recovery.capturedClient(client,current)
        val input=CodexCommandCodec.validate(JSONObject(current.intent),"replyRequest")
        val intent=ReplyIntent(input.getString("hostId"),input.getString("threadId"),current.id,input.getString("text"),input.getJSONArray("attachmentIds").strings(),input.getString("mode"),
            input.optional("expectedTurnId"),input.optional("expectedPlanId"),GenerationSettings(input.optional("model"),input.optional("effort"),input.optional("permissionMode"),input.optional("speed"),input.optional("collaborationMode")))
        val (workspace,history)=authority(captured,intent.threadId,null,guard)
        require(SessionPolicies.writable(access(captured,workspace,history,workspace.threads.firstOrNull { it.id==intent.threadId }?.projectId))) { "当前原桌面尚不能执行" }
        validateReplyAuthority(workspace,history,intent.mode,intent.expectedTurnId,intent.expectedPlanId)
        if(recovery.phase=="strong-not-accepted") {
            try {
                val fresh=commands.replyDeliveryStatus(captured,intent.threadId,current.id)
                current=journal.observeReplyInspection(client,current.target,current.id,fresh)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { journal.invalidateReplyInspection(current.target,current.id);throw failure }
        }
        check(guard()) { "当前目标已变化，没有重试原消息" }
        current=journal.consumeReplyRetry(client,current.target,current.id)
        progress("提交原消息…")
        try {
            check(guard()) { "当前目标已变化，仅查询原消息" }
            val receipt=commands.send(captured,intent)
            return CodexFlowResult(journal.observed(current.target,current.id,receipt.state,encodeReceipt(receipt),receipt.error))
        } catch(cancel: CancellationException) { journal.uncertain(current.target,current.id,"原消息提交结果待核对");throw cancel }
        catch(failure: Exception) { return CodexFlowResult(journal.uncertain(current.target,current.id,failure.message)) }
    }
    suspend fun originalReplyContent(client: MobileClient,record: SavedOperation): CodexContent = withContext(Dispatchers.IO) {
        val current=journal.records(record.target).single { it.id==record.id }
        require(current.replyRecovery?.mayRetry(client,current)==true) { "原输入未提交尚未得到确认，原消息和当前草稿均已保留" }
        val source=JSONObject(storage.read("submission:${record.id}") ?: error("原始附件或草稿快照已不可用，原记录仍保留"))
        require(source.getString("hostId")==client.id && source.getString("sourceKey")==current.sourceKey && source.getString("sourceVersion")==current.sourceVersion)
        val capturedTarget=current.replyRecovery!!.capturedTarget
        require(CodexCommandCodec.target(HostReadCodec.target(source.getJSONObject("target"))!!).toString()==CodexCommandCodec.target(HostReadCodec.target(JSONObject(capturedTarget))!!).toString())
        val input=JSONObject(current.intent)
        val manifests=source.getJSONArray("files").objects().map { file ->
            CodexCommandCodec.validate(file,"creationFile")
            LocalDraftFile(CreationFile(file.getString("id"),file.getString("name"),file.getString("mediaType"),file.getLong("sizeBytes"),file.getString("sha256")))
        }
        require(manifests.size==input.getJSONArray("attachmentIds").length())
        val settings=GenerationSettings(source.optional("model"),source.optional("effort"),source.optional("permissionMode"),source.optional("speed"),source.optional("collaborationMode"))
        val captured=ReplyIntent(client.id,input.getString("threadId"),current.id,source.getString("text"),input.getJSONArray("attachmentIds").strings(),input.getString("mode"),
            input.optional("expectedTurnId"),input.optional("expectedPlanId"),settings)
        require(current.replyRecovery.matches(client,current.copy(intent=captured.body().toString()))) { "原内容与提交指纹不匹配，当前草稿未改变" }
        manifests.forEach { files.bytes(it.manifest) }
        CodexContent(captured.text,manifests,settings)
    }
    suspend fun detach(client: MobileClient,record: SavedOperation): SavedOperation {
        // Local persistence releases waiting even when the Host cannot acknowledge it.
        val detached=journal.detach(record.target,record.id)
        if(record.kind=="creation") try { val result=commands.detachCreation(client,record.id); return journal.observed(record.target,record.id,result.phase,encodeCreation(result),result.error) } catch(_: Exception) { }
        return detached
    }
}

internal fun encodeThread(t: MobileThread)=JSONObject().put("id",t.id).put("hostId",t.hostId).put("title",t.title).put("state",t.state).put("projectId",t.projectId ?: JSONObject.NULL).put("projectIds",JSONArray(t.projectIds)).put("hasUnreadTurn",t.unread)
internal fun encodeReceipt(r: DeliveryReceipt)=JSONObject().put("hostId",r.hostId).put("threadId",r.threadId).put("clientMessageId",r.operationId).put("state",r.state).put("turnId",r.turnId ?: JSONObject.NULL).put("error",r.error ?: JSONObject.NULL).put("supersededByTurnId",r.supersededByTurnId ?: JSONObject.NULL).put("text",r.text ?: JSONObject.NULL).put("attachmentIds",JSONArray(r.attachmentIds))
internal fun encodeCreation(r: DesktopCreation)=JSONObject().put("hostId",r.hostId).put("clientOperationId",r.operationId).put("projectId",r.projectId ?: JSONObject.NULL).put("phase",r.phase).put("threadId",r.threadId ?: JSONObject.NULL).put("error",r.error ?: JSONObject.NULL).put("uploaded",JSONArray(r.uploaded)).put("thread",r.thread?.let(::encodeThread) ?: JSONObject.NULL).put("detached",r.detached).apply { r.preparation?.let { put("preparation",it.body()) } }
internal fun encodeOpen(r: DesktopOpen)=JSONObject().put("hostId",r.hostId).put("threadId",r.threadId).put("clientOperationId",r.operationId).put("phase",r.phase).put("error",r.error ?: JSONObject.NULL)

internal fun encodeProject(p: MobileProject)=JSONObject().put("id",p.id).put("hostId",p.hostId).put("name",p.name).put("roots",JSONArray(p.roots))
