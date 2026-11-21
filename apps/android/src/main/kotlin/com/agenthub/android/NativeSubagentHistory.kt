package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.CodexHttpContract
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject

data class MobileSubagentHistoryRequest(val parentThreadId: String,val messageId: String,
    val childId: String?=null,val cursor: String?=null,val association: String?=null) {
    init {
        require(association==null || association=="parent-thread-lineage")
        require(parentThreadId.length in 1..512 && messageId.length in 1..512)
        require(childId==null || childId.length in 1..512)
        require(cursor==null || cursor.length in 1..4096 && childId!=null)
    }
}
data class MobileSubagentChild(val id: String,val title: String,val status: String)
data class MobileSubagentHistory(val hostId: String,val target: TargetScope,val parentThreadId: String,
    val messageId: String,val children: List<MobileSubagentChild>,val childId: String,val source: String,
    val messages: List<MobileMessage>,val nextCursor: String?,val association: String="typed-tool")

/** Only the existing, authenticated Codex GET response. No owner/control state is published. */
internal object NativeSubagentHistoryWire {
    fun decode(wire: String,client: MobileClient,request: MobileSubagentHistoryRequest): MobileSubagentHistory {
        val expected=checkNotNull(client.scope)
        require(expected.agentId=="codex" && expected.hostId==client.id && client.agentConnection==null)
        val value=JSONObject(wire)
        val lineage=request.association=="parent-thread-lineage"
        require(value.get("version") is Number && value.getDouble("version")== (if(lineage)2.0 else 1.0))
        val association=value.optional("association")?:"typed-tool"
        require(association==(if(lineage)"parent-thread-lineage" else "typed-tool"))
        val target=checkNotNull(HostReadCodec.target(value.getJSONObject("readTarget")))
        require(target.agentId=="codex" && sameScope(target,expected) && value.getString("hostId")==client.id)
        require(value.getString("parentThreadId")==request.parentThreadId && value.getString("messageId")==request.messageId)
        val childRows=value.getJSONArray("children");require(childRows.length() in 1..32)
        val children=childRows.objects().map {
            MobileSubagentChild(it.getString("id"),it.getString("title"),it.getString("status"))
        }
        require(children.size in 1..32 && children.all {it.id.length in 1..512} && children.map {it.id}.distinct().size==children.size)
        val child=value.getString("childId")
        require(value.getString("threadId")==child && children.any {it.id==child} && (request.childId==null || child==request.childId))
        val source=value.getString("source");require(source in setOf("saved-history","desktop-runtime") && (!lineage || source=="saved-history"))
        val messageRows=value.getJSONArray("messages");require(messageRows.length()<=80)
        val rows=messageRows.objects()
        val messages=rows.map {
            require(CodexHttpContract.validate(it.toString(),"message")==null)
            HostReadCodec.message(it)
        }
        require(messages.map {it.id}.distinct().size==messages.size)
        require(messages.flatMap {it.attachments}.all {it.hostId==client.id && it.threadId==child})
        val next=value.optional("nextCursor")
        require(next==null || next.length in 1..4096 && next!=request.cursor)
        return MobileSubagentHistory(client.id,target,request.parentThreadId,request.messageId,children,child,source,messages,next,association)
    }
}

internal fun parentLineageFailureKey(status: Int?): String=when(status) {
    401,403 -> "电脑未允许读取所选子任务，请刷新连接后重试"
    404 -> "电脑当前没有可读取的本聊天子任务"
    400,409 -> "本聊天的子任务记录不可用或已变化，请刷新后重试"
    else -> "无法读取本聊天的子任务，请核对连接或刷新后重试"
}

/** Owned by one dialog; every refresh, child choice, earlier page and close retires the old request. */
internal class NativeSubagentHistoryModel(private val scope: CoroutineScope,private val current: ()->Boolean,
    private val read: suspend (String?,String?)->MobileSubagentHistory) {
    var page by mutableStateOf<MobileSubagentHistory?>(null);private set
    var messages by mutableStateOf<List<MobileMessage>>(emptyList());private set
    var nextCursor by mutableStateOf<String?>(null);private set
    var children by mutableStateOf<List<MobileSubagentChild>>(emptyList());private set
    var selectedChild by mutableStateOf<String?>(null);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var unavailable by mutableStateOf(false);private set
    var failureStatus by mutableStateOf<Int?>(null);private set
    private var generation=0L
    private var closed=false
    private var job: Job?=null
    fun refresh() = load(null)
    fun choose(id: String) {
        if(closed || !current() || children.none {it.id==id} || selectedChild==id)return
        selectedChild=id;page=null;messages=emptyList();nextCursor=null;load(null)
    }
    fun earlier() { val cursor=nextCursor; if(!busy && cursor!=null)load(cursor) }
    private fun load(cursor: String?) {
        if(closed || !current())return
        val before=page
        val requestedChild=selectedChild?:before?.childId
        if(cursor!=null && (before==null || before.childId!=requestedChild))return
        val ticket=++generation;job?.cancel();busy=true;error=null;unavailable=false;failureStatus=null
        job=scope.launch {
            try {
                val incoming=read(requestedChild,cursor)
                if(ticket!=generation || !current())return@launch
                require(requestedChild==null || requestedChild==incoming.childId)
                if(cursor!=null) {
                    check(before!=null && before.childId==incoming.childId && before.source==incoming.source &&
                        before.hostId==incoming.hostId && sameScope(before.target,incoming.target) &&
                        before.parentThreadId==incoming.parentThreadId && before.messageId==incoming.messageId && before.association==incoming.association) {"电脑返回的数据格式无效"}
                    val ids=messages.map {it.id}.toSet()
                    messages=incoming.messages.filterNot {it.id in ids}+messages
                } else messages=incoming.messages
                selectedChild=incoming.childId;children=incoming.children;page=incoming;nextCursor=incoming.nextCursor
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(ticket==generation && current()) {
                error=failure.message?:"电脑返回的数据格式无效"
                failureStatus=(failure as? HostReadError)?.status
                unavailable=failureStatus==404
            } }
            finally { if(ticket==generation)busy=false }
        }
    }
    fun close() { closed=true;generation++;job?.cancel();job=null;busy=false }
}
