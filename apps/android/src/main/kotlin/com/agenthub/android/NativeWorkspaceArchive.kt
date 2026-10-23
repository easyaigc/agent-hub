package com.agenthub.android

import com.agenthub.core.TargetScope
import com.agenthub.core.WorkspaceArchiveRules
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceArchiveRequest(val hostId: String,val target: TargetScope,val operationId: String,val ids: List<String>) {
    fun body()=JSONObject().put("hostId",hostId).put("target",CodexCommandCodec.target(target)).put("clientOperationId",operationId)
        .put("action","threads:archive").put("ids",JSONArray(ids))
}
data class WorkspaceArchiveItem(val id: String,val state: String,val error: String?)
data class WorkspaceArchiveReceipt(val request: WorkspaceArchiveRequest,val items: List<WorkspaceArchiveItem>) {
    fun body()=request.body().put("items",JSONArray(items.map { JSONObject().put("id",it.id).put("state",it.state).put("error",it.error ?: JSONObject.NULL) }))
    val pending get()=items.any { WorkspaceArchiveRules.pending(it.state) }
}
object WorkspaceArchiveCodec {
    fun request(value: JSONObject): WorkspaceArchiveRequest {
        require(WorkspaceArchiveRules.requestValid(value.toString())&&value.getString("action")=="threads:archive") { "聊天批量归档请求无效" }
        return WorkspaceArchiveRequest(value.getString("hostId"),HostReadCodec.target(value.getJSONObject("target"))!!,value.getString("clientOperationId"),value.getJSONArray("ids").strings())
    }
    fun receipt(value: JSONObject): WorkspaceArchiveReceipt {
        CodexCommandCodec.validate(value,"workspaceManagementOperation")
        val request=request(JSONObject(value.toString()).apply { remove("items") })
        require(WorkspaceArchiveRules.merge(request.body().toString(),null,value.toString())!=null) { "批量归档回执目标或逐项顺序不匹配" }
        return WorkspaceArchiveReceipt(request,value.getJSONArray("items").objects().map { WorkspaceArchiveItem(it.getString("id"),it.getString("state"),it.optional("error")) })
    }
}
class NativeWorkspaceArchiveFlow(private val journal: NativeOperationJournal,private val commands: AgentCommands) {
    suspend fun submit(client: MobileClient,request: WorkspaceArchiveRequest,labels: Map<String,String>,guard: ()->Boolean): CodexFlowResult {
        require(request.hostId==client.id&&sameScope(request.target,client.scope)) { "批量归档目标不匹配" }
        check(guard()) { "管理目标已变化，未提交" }
        var record=journal.prepare(client,null,"workspace-archive",request.body(),client.cacheKey(null),JSONObject(labels).toString())
        require(record.phase=="ready"&&!record.submissionAttempted&&record.result==null) { "原批量归档只能查询" }
        if(!guard())return CodexFlowResult(journal.workspaceArchiveNotSubmitted(record.target,record.id))
        record=journal.markSubmitting(record.target,record.id)
        if(!guard())return CodexFlowResult(journal.workspaceArchiveNotSubmitted(record.target,record.id))
        return try { accept(record,commands.archiveWorkspace(client,request)) }
        catch(cancel: CancellationException) { journal.uncertain(record.target,record.id,"批量归档结果待核对，只能查询原操作");throw cancel }
        catch(failure: Exception) { CodexFlowResult(journal.uncertain(record.target,record.id,failure.message ?: "批量归档结果待核对，只能查询原操作")) }
    }
    suspend fun query(client: MobileClient,record: SavedOperation): CodexFlowResult {
        require(record.kind=="workspace-archive"&&record.target in client.operationTargets(null)) { "批量归档不属于当前电脑" }
        val original=WorkspaceArchiveCodec.request(JSONObject(record.intent))
        require(original.hostId==client.id&&sameScope(original.target,client.scope)) { "批量归档原目标已变化" }
        val value=try { commands.workspaceArchiveStatus(client,original) }
        catch(failure: CodexCommandError) { if(failure.status==404)throw CodexCommandError("未查到原批量归档记录，结果仍未确认；不会重新提交。",404,failure);throw failure }
        return accept(record,value)
    }
    private suspend fun accept(record: SavedOperation,value: WorkspaceArchiveReceipt): CodexFlowResult =
        CodexFlowResult(journal.observed(record.target,record.id,if(value.pending)"outcome-unknown" else "completed",value.body()))
}
