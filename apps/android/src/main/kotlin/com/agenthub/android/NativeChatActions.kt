package com.agenthub.android

import com.agenthub.core.ChatManagementRules
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class ChatActionRequest(val hostId: String,val target: com.agenthub.core.TargetScope,val threadId: String,
    val operationId: String,val action: String) {
    fun body()=JSONObject().put("hostId",hostId).put("target",CodexCommandCodec.target(target)).put("threadId",threadId)
        .put("clientOperationId",operationId).put("action",action)
}
data class ChatActionReceipt(val request: ChatActionRequest,val phase: String,val error: String?) {
    fun body()=request.body().put("phase",phase).put("error",error ?: JSONObject.NULL)
}
object ChatActionCodec {
    fun request(value: JSONObject): ChatActionRequest {
        CodexCommandCodec.validate(value,"chatActionRequest")
        val target=HostReadCodec.target(value.getJSONObject("target"))!!
        require(target.hostId==value.getString("hostId")) { "聊天操作电脑目标不匹配" }
        return ChatActionRequest(value.getString("hostId"),target,value.getString("threadId"),value.getString("clientOperationId"),value.getString("action"))
    }
    fun receipt(value: JSONObject): ChatActionReceipt {
        CodexCommandCodec.validate(value,"chatActionReceipt")
        val body=JSONObject(value.toString()).apply { remove("phase");remove("error") }
        return ChatActionReceipt(request(body),value.getString("phase"),value.optional("error"))
    }
}

/** Client-scoped journal contains exact thread-bound intents. GET recovery never submits. */
class NativeChatActionFlow(private val journal: NativeOperationJournal,private val commands: AgentCommands) {
    suspend fun submit(client: MobileClient,request: ChatActionRequest,sourceKey: String,selection: String,guard: ()->Boolean): CodexFlowResult {
        require(request.hostId==client.id&&sameScope(request.target,client.scope)) { "聊天操作目标不匹配" }
        check(guard()) { "目标已变化，未发送聊天操作" }
        var record=journal.prepare(client,null,"chat-action",request.body(),sourceKey,selection)
        // A saved ID, including a local rejection, can only be inspected afterward.
        require(record.phase=="ready"&&!record.submissionAttempted&&record.result==null) { "原聊天操作只能查询" }
        if(!guard())return CodexFlowResult(journal.chatActionNotSubmitted(record.target,record.id,"目标已变化，未发送聊天操作"))
        record=journal.markSubmitting(record.target,record.id)
        if(!guard())return CodexFlowResult(journal.chatActionNotSubmitted(record.target,record.id,"目标已变化，未发送聊天操作"))
        return try { accept(record,commands.chatAction(client,request)) }
        catch(cancel: CancellationException) { journal.uncertain(record.target,record.id,"操作结果待确认，请查询原记录");throw cancel }
        catch(failure: Exception) {
            if(failure is CodexCommandError&&failure.status in listOf(400,401,403,404,409,422))
                accept(record,ChatActionReceipt(request,"rejected",failure.message))
            else CodexFlowResult(journal.uncertain(record.target,record.id,failure.message ?: "操作结果待确认，请查询原记录"))
        }
    }
    suspend fun query(client: MobileClient,record: SavedOperation): CodexFlowResult {
        require(record.kind=="chat-action"&&record.target in client.operationTargets(null)) { "原聊天操作不属于当前电脑目标" }
        val request=ChatActionCodec.request(JSONObject(record.intent))
        require(request.hostId==client.id&&sameScope(request.target,client.scope)) { "原聊天操作目标已变化" }
        val receipt=try { commands.chatActionStatus(client,request.threadId,request.operationId) }
        catch(failure: CodexCommandError) {
            if(failure.status==404)throw CodexCommandError("未查到原聊天操作记录，结果仍未确认；不会重新提交。",404,failure)
            throw failure
        }
        return accept(record,receipt)
    }
    private suspend fun accept(record: SavedOperation,receipt: ChatActionReceipt): CodexFlowResult {
        require(ChatManagementRules.outcomeMatches(record.intent,receipt.body().toString())) { "聊天操作回执不匹配原意图" }
        return CodexFlowResult(journal.observed(record.target,record.id,receipt.phase,receipt.body(),receipt.error))
    }
}
