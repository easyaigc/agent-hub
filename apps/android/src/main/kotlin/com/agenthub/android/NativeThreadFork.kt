package com.agenthub.android

import com.agenthub.core.ForkAvailability
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.ThreadForkRules
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Product DTOs. Codex/Git implementation stays on the target Host. */
data class ThreadForkRequest(val hostId: String,val threadId: String,val turnId: String,
    val operationId: String,val environment: String) {
    fun body()=JSONObject().put("hostId",hostId).put("threadId",threadId).put("turnId",turnId)
        .put("clientOperationId",operationId).put("environment",environment)
}
data class ThreadForkOptions(val hostId: String,val threadId: String,val sameDirectoryAvailable: Boolean,
    val worktreeAvailable: Boolean,val reason: String?,val worktreeReason: String?) {
    fun availability()=ForkAvailability(sameDirectoryAvailable,worktreeAvailable,reason,worktreeReason)
}
data class ThreadForkOperation(val request: ThreadForkRequest,val phase: String,val forkedThreadId: String?,
    val projectId: String?,val error: String?) {
    fun body()=request.body().put("phase",phase).put("forkedThreadId",forkedThreadId ?: JSONObject.NULL)
        .put("projectId",projectId ?: JSONObject.NULL).put("error",error ?: JSONObject.NULL)
}
object ThreadForkCodec {
    fun request(value: JSONObject): ThreadForkRequest {
        CodexCommandCodec.validate(value,"threadForkRequest")
        return ThreadForkRequest(value.getString("hostId"),value.getString("threadId"),value.getString("turnId"),
            value.getString("clientOperationId"),value.getString("environment")).also {
            require(listOf(it.hostId,it.threadId,it.turnId).all(String::isNotBlank))
        }
    }
    fun options(value: JSONObject): ThreadForkOptions {
        CodexCommandCodec.validate(value,"threadForkOptions")
        return ThreadForkOptions(value.getString("hostId"),value.getString("threadId"),value.getBoolean("sameDirectoryAvailable"),
            value.getBoolean("worktreeAvailable"),value.optional("reason"),value.optional("worktreeReason"))
    }
    fun operation(value: JSONObject): ThreadForkOperation {
        CodexCommandCodec.validate(value,"threadForkOperation")
        val request=ThreadForkRequest(value.getString("hostId"),value.getString("threadId"),value.getString("turnId"),
            value.getString("clientOperationId"),value.getString("environment"))
        require(ThreadForkRules.outcomeMatches(request.body().toString(),value.toString())) { "分支回执缺少准确的新聊天" }
        return ThreadForkOperation(request,value.getString("phase"),value.optional("forkedThreadId"),value.optional("projectId"),value.optional("error"))
    }
}
/** Full identity and connection trust are captured beside the exact five-field wire intent. */
data class NativeForkContext(val target: String,val trust: String) {
    fun body()=JSONObject().put("target",JSONObject(target)).put("trust",trust)
    fun matches(client: MobileClient,record: SavedOperation): Boolean = runCatching {
        val scope=HostReadCodec.target(JSONObject(target))!!
        record.kind=="fork" && scope.agentId=="codex" && RuntimeIdentity.matches(scope,client.scope) &&
            trust==trust(client) && record.target in client.operationTargets(JSONObject(record.intent).getString("threadId"))
    }.getOrDefault(false)
    fun sameConnection(client: MobileClient): Boolean = runCatching {
        RuntimeIdentity.matches(HostReadCodec.target(JSONObject(target))!!,client.scope) && trust==trust(client)
    }.getOrDefault(false)
    companion object {
        private fun trust(client: MobileClient)=client.codexDirect?.let { "codex:"+it.fingerprint.lowercase() } ?: "endpoint:"+client.endpoint
        fun capture(client: MobileClient)=NativeForkContext(CodexCommandCodec.target(client.scope!!).toString(),trust(client))
        fun decode(value: JSONObject): NativeForkContext {
            require(value.keys().asSequence().toSet()==setOf("target","trust"))
            return NativeForkContext(CodexCommandCodec.validate(value.getJSONObject("target"),"target").toString(),value.getString("trust")).also { require(it.trust.isNotBlank()) }
        }
    }
}

/** One explicit submit, then status reads only. No fork/model replay on any failure. */
class NativeThreadForkFlow(private val journal: NativeOperationJournal,private val reads: HostReads,private val commands: AgentCommands) {
    suspend fun submit(client: MobileClient,request: ThreadForkRequest,origin: SubmissionOrigin,guard: ()->Boolean,sharingScope: String? = null): CodexFlowResult {
        ThreadForkCodec.request(request.body())
        require(request.hostId==client.id)
        check(guard()) { "源对话已改变，未提交分支" }
        val workspace=reads.workspace(client)
        require(workspace.hostId==client.id && sameScope(workspace.scope,client.scope)) { "分支源运行目标不匹配" }
        val base=ThreadForkRules.baseReason(!client.isDemo && client.scope?.agentId=="codex",
            workspace.mode=="desktop-runtime" && workspace.runtimeConnected!=false,workspace.capabilities,client.online)
        val options=commands.forkOptions(client,request.threadId)
        require(options.hostId==request.hostId && options.threadId==request.threadId) { "分支选项目标不匹配" }
        val reason=ThreadForkRules.reason(request.turnId,base,false,options.availability(),request.environment,
            "threads:fork:worktree" in workspace.capabilities,null)
        require(reason==null) { reason ?: "不能分支" }
        check(guard()) { "源对话已改变，未提交分支" }
        var record=journal.prepare(client,request.threadId,"fork",request.body(),origin.key,origin.version,sharingScope=sharingScope)
        require(record.forkContext?.matches(client,record)==true) { "原分支目标或连接身份已变化" }
        try {
            check(guard()) { "源对话已改变，未提交分支" }
            record=journal.markSubmitting(record.target,record.id)
            val outcome=commands.forkThread(client,request)
            record=journal.observed(record.target,record.id,outcome.phase,outcome.body(),outcome.error)
        } catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) { return CodexFlowResult(if(record.submissionAttempted) journal.uncertain(record.target,record.id,failure.message) else journal.forkNotSubmitted(record.target,record.id,failure.message)) }
        return resolve(client,record)
    }
    suspend fun query(client: MobileClient,record: SavedOperation): CodexFlowResult {
        require(record.forkContext?.matches(client,record)==true) { "原分支的目标或连接身份已改变" }
        val request=ThreadForkCodec.request(JSONObject(record.intent))
        val outcome=commands.forkStatus(client,request.threadId,record.id)
        val saved=journal.observed(record.target,record.id,outcome.phase,outcome.body(),outcome.error)
        return resolve(client,saved)
    }
    private suspend fun resolve(client: MobileClient,record: SavedOperation): CodexFlowResult {
        if(record.phase!="confirmed") return CodexFlowResult(record)
        val outcome=ThreadForkCodec.operation(JSONObject(record.result!!));val id=outcome.forkedThreadId!!
        val workspace=reads.workspace(client)
        require(workspace.hostId==client.id && sameScope(workspace.scope,client.scope) && workspace.mode=="desktop-runtime") { "分支工作区目标不匹配" }
        val history=reads.history(client,id)
        require(history.hostId==client.id && history.threadId==id && sameScope(history.scope,client.scope)) { "分支聊天运行目标不匹配" }
        val thread=workspace.threads.firstOrNull { it.id==id } ?: JSONObject(history.wire).optJSONObject("thread")?.let(HostReadCodec::thread)
            ?: error("分支已创建，聊天尚未出现在电脑列表；请查询原回执")
        require(thread.hostId==client.id && thread.id==id && thread.projectId==outcome.projectId) { "分支聊天归属不匹配" }
        return CodexFlowResult(record,thread)
    }
}
