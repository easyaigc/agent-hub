package com.agenthub.android

import com.agenthub.core.MobileRules
import com.agenthub.core.ReplyRecoveryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SavedOperation(val id: String, val kind: String, val target: String, val intent: String,
    val intentSha256: String, val phase: String, val result: String? = null, val error: String? = null,
    val detached: Boolean = false, val submissionAttempted: Boolean = false,
    val sourceKey: String? = null, val sourceVersion: String? = null,val parentOperationId: String? = null,
    val replyRecovery: NativeReplyRecovery? = null, val forkContext: NativeForkContext? = null,val sharingScope: String? = null,
    val migrationReadOnly: Boolean = false) {
    fun body()=JSONObject().put("id",id).put("kind",kind).put("target",target).put("intent",intent)
        .put("intentSha256",intentSha256).put("phase",phase).put("result",result ?: JSONObject.NULL)
        .put("error",error ?: JSONObject.NULL).put("detached",detached).put("submissionAttempted",submissionAttempted)
        .put("sourceKey",sourceKey ?: JSONObject.NULL).put("sourceVersion",sourceVersion ?: JSONObject.NULL)
        .put("parentOperationId",parentOperationId ?: JSONObject.NULL)
        .put("sharingScope",sharingScope?.let(::JSONObject) ?: JSONObject.NULL)
        .put("replyRecovery",replyRecovery?.body() ?: JSONObject.NULL).put("forkContext",forkContext?.body() ?: JSONObject.NULL)
        .apply {if(migrationReadOnly)put("migrationReadOnly",true)}
}

/** The journal is separate from editable drafts. Successful atomic storage precedes every submit. */
class NativeOperationJournal(private val storage: NativeSessionStorage) {
    private val mutex=Mutex()
    companion object {
        fun target(client: MobileClient,threadId: String?)=client.cacheKey(threadId)
        private val definitions=mapOf("reply" to "replyRequest","creation" to "creationRequest",
            "workspace-archive" to "workspaceManagementRequest","chat-action" to "chatActionRequest","fork" to "threadForkRequest","thread-create" to "createThreadRequest","open" to "openRequest","project-create" to "native-project-intent-v1")
        private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun decode(value: JSONObject): SavedOperation {
        val record=SavedOperation(value.getString("id"),value.getString("kind"),value.getString("target"),value.getString("intent"),
            value.getString("intentSha256"),value.getString("phase"),value.optional("result"),value.optional("error"),value.getBoolean("detached"),
            value.optBoolean("submissionAttempted",value.getString("phase") !in listOf("ready","preparing","prepared")),
            value.optional("sourceKey"),value.optional("sourceVersion"),value.optional("parentOperationId"),value.optJSONObject("replyRecovery")?.let(NativeReplyRecovery::decode),value.optJSONObject("forkContext")?.let(NativeForkContext::decode),value.optJSONObject("sharingScope")?.let { AISharingScope.decode(it);it.toString() },value.optBoolean("migrationReadOnly",false))
        require(record.intentSha256==sha(record.intent)) { "本地提交意图损坏，未重放" }
        val definition=definitions[record.kind] ?: error("未知本地操作类型")
        validateIntent(JSONObject(record.intent),definition)
        require(JSONObject(record.intent).getString(if(record.kind=="reply") "clientMessageId" else "clientOperationId")==record.id) { "本地操作身份损坏" }
        record.result?.let { verifyResult(record,JSONObject(it)) }
        return record
    }
    private fun read(target: String): List<SavedOperation> {
        val raw=storage.read("operations:$target") ?: return emptyList()
        return validateMigrationRecords(target,JSONObject(raw))
    }
    /** Decode the same immutable records without writing, observing or retrying an operation. */
    internal fun validateMigrationRecords(target: String,body: JSONObject): List<SavedOperation> {
        require(body.getInt("version")==1 && body.getString("target")==target) { "本地提交记录目标不匹配" }
        return body.getJSONArray("operations").objects().map(::decode).also { records ->
            require(records.all { it.target==target } && records.map { it.id }.distinct().size==records.size) { "本地提交记录身份冲突" }
        }
    }
    private fun save(target: String,records: List<SavedOperation>) {
        storage.write("operations:$target",JSONObject().put("version",1).put("target",target).put("operations",JSONArray(records.map { it.body() })).toString())
    }
    suspend fun records(target: String): List<SavedOperation> = withContext(Dispatchers.IO) { mutex.withLock { read(target) } }
    suspend fun prepare(client: MobileClient,threadId: String?,kind: String,intent: JSONObject,
        sourceKey: String? = null,sourceVersion: String? = null,parentOperationId: String? = null,sharingScope: String? = null): SavedOperation = withContext(Dispatchers.IO) {
        mutex.withLock {
          storage.operationTransaction transaction@{
            NativeMigrationEasyWorkGate.requireReady(client)
            val scope=client.scope
            require(scope!=null&&!client.isDemo&&intent.getString("hostId")==client.id&&
                (client.agentConnection?.let { sameScope(scope,it.target) }?: (scope.hostId==client.id))) { "提交记录缺少真实目标" }
            require(com.agenthub.core.DomainContract.validate(encodeTarget(scope).toString(),"target")==null) { "提交记录完整目标无效" }
            val definition=definitions[kind] ?: error("未知操作类型"); validateIntent(intent,definition)
            if(kind in listOf("reply","open","fork")) require(intent.getString("threadId")==threadId) { "提交记录聊天不匹配" }
            else require(threadId==null) { "创建意图不能绑定已有聊天" }
            val target=target(client,threadId); val id=intent.getString(if(kind=="reply") "clientMessageId" else "clientOperationId")
            val json=intent.toString(); val hash=sha(json); val records=read(target)
            records.firstOrNull { it.id==id }?.let {
                require(it.kind==kind && it.intentSha256==hash && it.intent==json && it.sourceKey==sourceKey && it.sourceVersion==sourceVersion && it.parentOperationId==parentOperationId && it.sharingScope==sharingScope) { "操作 ID 已绑定另一提交意图" }
                return@transaction it
            }
            sharingScope?.let { require(com.agenthub.core.RuntimeIdentity.matches(AISharingScope.decode(JSONObject(it)).target,client.scope)) }
            SavedOperation(id,kind,target,json,hash,"ready",sourceKey=sourceKey,sourceVersion=sourceVersion,parentOperationId=parentOperationId,sharingScope=sharingScope,
                replyRecovery=if(kind=="reply")NativeReplyRecovery.capture(client,json) else null, forkContext=if(kind=="fork")NativeForkContext.capture(client) else null).also { save(target,records+it) }
          }
        }
    }
    suspend fun markPreparing(target: String,id: String): SavedOperation = change(target,id) {
        require(!it.migrationReadOnly) { "迁入的原操作只能查询回执，不能重放" }
        require(it.kind=="creation" && it.phase=="ready" && !it.submissionAttempted) { "只能准备尚未提交的新建意图" }
        it.copy(phase="preparing")
    }
    suspend fun markSubmitting(target: String,id: String): SavedOperation = change(target,id) {
        require(!it.migrationReadOnly) { "迁入的原操作只能查询回执，不能重放" }
        require(!it.submissionAttempted && it.phase==if(it.kind=="creation") "prepared" else "ready") { "操作已尝试提交，只能查询原记录" }
        it.copy(phase="submitting",submissionAttempted=true,replyRecovery=it.replyRecovery?.copy(phase="uncertain",inspection=null))
    }
    suspend fun observeReplyInspection(client: MobileClient,target: String,id: String,inspection: JSONObject): SavedOperation = change(target,id) {
        require(!it.migrationReadOnly) { "迁入的原操作不能继承重试授权" }
        val recovery=it.replyRecovery ?: error("旧记录未捕获恢复授权，只能查询原回执")
        require(recovery.matches(client,it) && !recovery.consumed && it.result==null) { "原操作不能取得新的重试授权" }
        val candidate=recovery.copy(inspection=inspection.toString())
        require(candidate.strong(it)) { "电脑尚未权威确认原消息未接受" }
        it.copy(replyRecovery=candidate.copy(phase="strong-not-accepted"),error=null)
    }
    suspend fun consumeReplyRetry(client: MobileClient,target: String,id: String): SavedOperation = change(target,id) {
        require(!it.migrationReadOnly) { "迁入的原操作只能查询回执，不能重放" }
        val recovery=it.replyRecovery ?: error("原记录缺少恢复授权")
        require(recovery.mayRetry(client,it)) { "原消息结果待核对，不能重新提交" }
        it.copy(phase="submitting",submissionAttempted=true,replyRecovery=recovery.copy(phase="uncertain",inspection=null,consumed=true))
    }
    suspend fun invalidateReplyInspection(target: String,id: String): SavedOperation = change(target,id) {
        it.copy(replyRecovery=it.replyRecovery?.copy(phase="uncertain",inspection=null))
    }
    suspend fun markCreationRetrySubmitting(target: String,id: String): SavedOperation = change(target,id) {
        require(!it.migrationReadOnly) { "迁入的原操作只能查询回执，不能重放" }
        val original=it.result?.let { value->CodexCommandCodec.creation(JSONObject(value)) }
        require(it.kind=="creation" && it.submissionAttempted && original!=null &&
            ReplyRecoveryRules.mayContinueCreation(it.phase,true,original.diagnostic?.disposition,
                original.preparation?.preparationPhase,original.preparation?.preparedThreadId)) { "电脑尚未权威确认原首条未写入" }
        it.copy(phase="submitting")
    }
    suspend fun observed(target: String,id: String,phase: String,result: JSONObject?,error: String? = null): SavedOperation = change(target,id) {
        require(phase in setOf("received","queued","submitted","completed","cancelled","interrupted","failed","uncertain","prepared","confirmed","outcome-unknown","rejected","opening","opened")) { "未知回执状态" }
        require(result!=null) { "缺少可核对的远端回执" }
        verifyResult(it,result)
        if(it.kind=="workspace-archive") {
            val merged=com.agenthub.core.WorkspaceArchiveRules.merge(it.intent,it.result,result.toString()) ?: error("批量归档回执原意图不匹配")
            val receipt=WorkspaceArchiveCodec.receipt(JSONObject(merged))
            return@change it.copy(phase=if(receipt.pending)"outcome-unknown" else "completed",result=merged,error=null)
        }
        val receivedPhase=when(it.kind) { "reply"->result.getString("state"); "creation","open","fork","chat-action"->result.getString("phase"); else->"confirmed" }
        require(phase==receivedPhase) { "回执状态不匹配" }
        if(it.kind=="chat-action") require(com.agenthub.core.ChatManagementRules.mayAcceptOutcome(it.phase,phase)) { "终态聊天操作回执不能回退" }
        if(it.kind=="fork") {
            val prior=it.result?.let { value->ThreadForkCodec.operation(JSONObject(value)) }
            val next=ThreadForkCodec.operation(result)
            require(com.agenthub.core.ThreadForkRules.mayAcceptOutcome(it.phase,prior?.forkedThreadId,prior?.projectId,next.phase,next.forkedThreadId,next.projectId)) { "终态分支回执不能回退或改写" }
        }
        // A preparation read may finish after the first write boundary. It cannot
        // retire the original query observer or replace evidence of that attempt.
        if(it.kind=="creation" && it.submissionAttempted && phase=="prepared" ||
            preservesReplyReceipt(it,phase) || it.phase in listOf("confirmed","opened","completed","cancelled","interrupted") && it.phase!=phase) it
        else it.copy(phase=phase,result=result.toString(),error=error)
    }
    suspend fun uncertain(target: String,id: String,error: String?): SavedOperation = change(target,id) { if(preservesReplyReceipt(it,"uncertain") || it.kind in listOf("fork","chat-action") && it.phase=="rejected" || it.phase in listOf("confirmed","opened","completed","cancelled","interrupted")) it else it.copy(phase="uncertain",error=error) }
    suspend fun replyNotSubmitted(target: String,id: String,error: String?): SavedOperation = change(target,id) {
        require(it.kind=="reply" && !it.submissionAttempted && it.phase=="ready") { "仅首个回复提交前的本地失败可确认未提交" }
        it.copy(phase="failed",error=error)
    }
    suspend fun workspaceArchiveNotSubmitted(target: String,id: String): SavedOperation = change(target,id) {
        require(it.kind=="workspace-archive"&&it.phase in listOf("ready","submitting")&&it.result==null)
        val request=WorkspaceArchiveCodec.request(JSONObject(it.intent))
        val result=WorkspaceArchiveReceipt(request,request.ids.map { id->WorkspaceArchiveItem(id,"rejected","管理目标已变化，未提交") })
        it.copy(phase="completed",result=result.body().toString(),error=null)
    }
    suspend fun chatActionNotSubmitted(target: String,id: String,error: String?): SavedOperation = change(target,id) {
        require(it.kind=="chat-action"&&it.phase in listOf("ready","submitting")&&it.result==null)
        it.copy(phase="rejected",error=error)
    }
    suspend fun forkNotSubmitted(target: String,id: String,error: String?): SavedOperation = change(target,id) {
        require(it.kind=="fork" && !it.submissionAttempted && it.phase=="ready");it.copy(phase="rejected",error=error)
    }
    suspend fun projectNotSubmitted(target: String,id: String,error: String?): SavedOperation = change(target,id) {
        require(it.kind=="project-create"&&it.result==null);it.copy(phase="rejected",error=error)
    }
    suspend fun activateConfirmedFork(target: String,id: String): SavedOperation = change(target,id) {
        require(it.kind=="fork" && it.phase=="confirmed");it.copy(detached=false)
    }
    /** A local rejected presentation dismissal cannot notify or replay the Host. */
    suspend fun dismissRejectedCreation(original: SavedOperation): SavedOperation = change(original.target,original.id) {
        if(original.kind=="creation" && original.phase=="rejected" && it.kind=="creation" && it.intentSha256==original.intentSha256 && it.sourceKey==original.sourceKey &&
            it.phase=="rejected" && !it.detached)it.copy(detached=true) else it
    }
    suspend fun detach(target: String,id: String): SavedOperation = change(target,id) { it.copy(detached=true) }
    /** A failed detach notification belongs to its original row; it changes no execution outcome. */
    suspend fun detachedCreationFailure(original: SavedOperation,error: String): SavedOperation = change(original.target,original.id) {
        require(original.kind=="creation" && original.detached && it.kind=="creation" && it.detached && it.intentSha256==original.intentSha256)
        // A newer original receipt diagnostic wins over this earlier transport attempt.
        if(it.error!=original.error) it else it.copy(error=error)
    }
    private fun preservesReplyReceipt(record: SavedOperation,incoming: String): Boolean = record.kind=="reply" &&
        record.result?.let { CodexCommandCodec.receipt(JSONObject(it)) }?.let { ReplyRecoveryRules.preservesReceiptEvidence(it.state,it.queueSource,it.queuePresent,it.queueDisposition,incoming) }==true
    private fun verifyResult(record: SavedOperation,result: JSONObject) {
        val intent=JSONObject(record.intent); val host=intent.getString("hostId")
        when(record.kind) {
            "workspace-archive" -> require(com.agenthub.core.WorkspaceArchiveRules.merge(record.intent,null,result.toString())!=null) { "批量归档回执原意图不匹配" }
            "chat-action" -> require(com.agenthub.core.ChatManagementRules.outcomeMatches(record.intent,result.toString())) { "聊天操作回执不匹配原意图" }
            "project-create" -> { require(result.keys().asSequence().toSet()==setOf("id","hostId","name","roots")&&result.getString("hostId")==host&&result.getString("id").isNotBlank()&&result.getString("name")==intent.getString("name"));result.getJSONArray("roots").strings() }
            "reply" -> CodexCommandCodec.receipt(result).also { require(it.hostId==host && it.threadId==intent.getString("threadId") && it.operationId==record.id) { "保存回执目标不匹配" } }
            "creation" -> CodexCommandCodec.creation(result).also {
                require(it.hostId==host && it.operationId==record.id && it.projectId==intent.optional("projectId")) { "保存新建回执目标不匹配" }
                val fileIds=intent.getJSONArray("attachments").objects().map { it.getString("id") }
                require(it.uploaded.all { id->id in fileIds }) { "新建回执包含未知附件" }
                it.thread?.let { thread -> require(thread.id==it.threadId && thread.hostId==host && thread.projectId==it.projectId) { "保存新建聊天身份不匹配" } }
            }
            "fork" -> require(com.agenthub.core.ThreadForkRules.outcomeMatches(record.intent,result.toString())) { "分支回执原意图不匹配" }
            "open" -> CodexCommandCodec.open(result).also { require(it.hostId==host && it.threadId==intent.getString("threadId") && it.operationId==record.id) { "保存打开回执目标不匹配" } }
            "thread-create" -> HostReadCodec.thread(CodexCommandCodec.validate(result,"thread")).also { require(it.hostId==host && it.projectId==intent.optional("projectId")) { "保存聊天归属不匹配" } }
        }
    }
    private suspend fun change(target: String,id: String,transform: (SavedOperation)->SavedOperation): SavedOperation = withContext(Dispatchers.IO) {
        mutex.withLock {
          storage.operationTransaction {
            val records=read(target); val old=records.singleOrNull { it.id==id } ?: error("找不到原提交记录")
            val updated=transform(old)
            require(updated.id==old.id && updated.kind==old.kind && updated.intent==old.intent && updated.intentSha256==old.intentSha256 && updated.target==old.target && updated.sourceKey==old.sourceKey && updated.sourceVersion==old.sourceVersion && updated.parentOperationId==old.parentOperationId) { "提交意图不可修改" }
            require(updated.forkContext==old.forkContext) { "原分支目标与连接身份不可改写" }
            require(!old.submissionAttempted || updated.submissionAttempted) { "不得解除已提交边界" }
            require(!old.migrationReadOnly || updated.migrationReadOnly) { "不得恢复迁入原操作的执行权限" }
            if(old.replyRecovery==null) require(updated.replyRecovery==null) { "旧记录不得凭当前连接补造恢复授权" }
            else {
                val before=old.replyRecovery;val after=updated.replyRecovery ?: error("不得丢失原恢复记录")
                require(before.capturedTarget==after.capturedTarget && before.trust==after.trust && before.fingerprint==after.fingerprint &&
                    (!before.consumed || after.consumed)) { "原目标、指纹和已消费恢复授权不可改写" }
            }
            save(target,records.map { if(it.id==id) updated else it }); updated
          }
        }
    }
    private fun validateIntent(value: JSONObject,definition: String) {
        if(definition=="workspaceManagementRequest") { WorkspaceArchiveCodec.request(value);return }
        if(definition!="native-project-intent-v1") { CodexCommandCodec.validate(value,definition);return }
        require(value.keys().asSequence().toSet()==setOf("version","hostId","clientOperationId","name","directory")&&value.getInt("version")==1)
        require(listOf("hostId","clientOperationId","name","directory").all { value.getString(it).isNotBlank() })
        require(value.getString("name").length<=500&&value.getString("directory").length<=4096)
    }
}
