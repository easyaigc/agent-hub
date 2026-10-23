package com.agenthub.android

import com.agenthub.core.TaskHubContract
import com.agenthub.core.TaskHubRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun interface TaskHubActionAuthorizer {
    /** Must resolve current future workflow destinations, independently of chat permissions. */
    suspend fun authorize(operation: TaskHubOperation, transport: TaskHubTransport)
}

/** UM06 domain adapter; UM07 supplies presentation and the business AI consent resolver. */
internal class TaskHubRepository(internal val database: TaskHubSQLitePersistence,val deviceId: String,
    val binding: TaskHubBinding,private val human: TaskHubHumanSession,internal val transport: TaskHubTransport,
    private val authorizer: TaskHubActionAuthorizer) {
    val scope=binding.service.scope(human.actorId,deviceId)
    private val guard=Any()
    private var generation=UUID.randomUUID().toString()
    private var needsLogin=false
    private var verified=false
    private var role: String?=null
    private var projects=emptySet<String>()
    private var readSequence=0L
    private val reads=mutableMapOf<String,Long>()
    private val snapshots=mutableMapOf<String,TaskHubReadSnapshot>()
    private val roundNumbers=mutableMapOf<String,Int>()
    private fun fence()=synchronized(guard) { generation }
    private fun current(context: String)=synchronized(guard) { TaskHubRules.currentSession(scope,scope,context,generation,"human",needsLogin) }
    private fun requireCurrent(context: String) { check(current(context)) { "任务账户或服务已变化，未应用旧结果" } }
    private fun beginRead(key: String): Long = synchronized(guard) { (++readSequence).also { reads[key]=it } }
    private fun requireRead(context: String,key: String,sequence: Long) { requireCurrent(context);check(reads[key]==sequence) { "读取已更新，未应用旧页面" } }
    internal suspend fun verifiedContext(): String { val context=fence();verify();requireCurrent(context);return context }
    internal fun ensureContext(context: String) = requireCurrent(context)
    private fun key(kind: String,projectId: String?,taskId: String?,roundId: String?,name: String): String =
        JSONArray(listOf(scope,kind,projectId,taskId,roundId,name)).toString()
    private fun operationKey(id: String)=key("operation",null,null,null,id)
    private fun record(values: Map<String,String>,id: String)=values[operationKey(id)]?.let { TaskHubOperation.decode(JSONObject(it)) }?.also { require(it.scope==scope && it.id==id) { "原操作记录作用域不符" } }
    private fun all(values: Map<String,String>)=values.mapNotNull { (key,value) ->
        val parts=runCatching { JSONArray(key) }.getOrNull()
        if(parts?.optString(0)==scope && parts.optString(1)=="operation") TaskHubOperation.decode(JSONObject(value)).also { require(it.scope==scope && parts.getString(5)==it.id) } else null
    }
    private fun replace(values: MutableMap<String,String>, next: TaskHubOperation) {
        val old=record(values,next.id) ?: error("原任务操作缺失")
        check(old.sameIntent(next)) { "原任务提交意图不一致" }
        if(!TaskHubRules.acceptsPhase(old.phase,next.phase)) return
        // End-wait is durable even if a later receipt confirms the original operation.
        values[operationKey(next.id)]=next.copy(waitEnded=old.waitEnded || next.waitEnded).json().toString()
    }
    suspend fun verify() = withContext(Dispatchers.IO) {
        val context=fence()
        try {
            if(!binding.service.same(TaskHubService.decode(transport.request("service")))) throw TaskHubIdentityChanged()
            val actor=JSONObject(transport.request("auth/me")).getJSONObject("actor")
            if(TaskHubContract.validate(actor.toString(),"actor")!=null || actor.getString("kind")!="human" || actor.getString("id")!=human.actorId) throw TaskHubIdentityChanged()
            val directory=JSONArray(transport.request("projects"))
            val ids=directory.objects().map { taskHubUUID(it.getString("id")) }.toSet()
            synchronized(guard) {
                requireCurrent(context)
                database.transaction { values ->
                    values[key("directory",null,null,null,"projects")]=directory.toString()
                    values.keys.filter { k -> val p=runCatching { JSONArray(k) }.getOrNull()
                        p?.optString(0)==scope && p.optString(1) in listOf("list","detail","snapshot","history") && p.optString(2) !in ids
                    }.toList().forEach(values::remove)
                }
                projects=ids;role=actor.getString("role");verified=true
            }
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    private fun authorizationFailure(error: Exception,context: String) {
        synchronized(guard) {
            if(!current(context)) return
            verified=false
            if(error is TaskHubIdentityChanged || error is TaskHubHTTPError && error.status in listOf(401,403)) {
                database.transaction { values ->
                    val remove=values.keys.filter { k ->
                        val p=runCatching { JSONArray(k) }.getOrNull()
                        p?.optString(0)==scope && p.optString(1) in listOf("directory","list","detail","snapshot","history")
                    }
                    remove.forEach(values::remove)
                }
                projects=emptySet();reads.clear();snapshots.clear();roundNumbers.clear();needsLogin=error is TaskHubIdentityChanged || (error as? TaskHubHTTPError)?.status==401;generation=UUID.randomUUID().toString()
            }
        }
    }
    suspend fun deviceSessions(): List<TaskHubDeviceSession> = withContext(Dispatchers.IO) {
        val context=verifiedContext()
        require(binding.service.capabilities.contains("mobile-device-sessions")) { "原主服务尚未支持设备会话管理" }
        try {
            val wire=transport.request("auth/mobile/sessions")
            requireCurrent(context)
            JSONArray(wire).objects().map(TaskHubDeviceSession::decode)
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun revokeDeviceSession(id: String) = withContext(Dispatchers.IO) {
        taskHubUUID(id);val context=verifiedContext()
        require(binding.service.capabilities.contains("mobile-device-sessions")) { "原主服务尚未支持设备会话管理" }
        try {
            // One explicit DELETE. Uncertain revocation is surfaced, never retried automatically.
            transport.request("auth/mobile/sessions/$id","DELETE")
            requireCurrent(context)
            verify()
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun saveDraft(projectId: String,taskId: String?,roundId: String?,name: String,body: String) = withContext(Dispatchers.IO) {
        taskHubUUID(projectId);taskId?.let(::taskHubUUID);roundId?.let(::taskHubUUID)
        database.transaction { it[key("draft",projectId,taskId,roundId,name)]=body }
    }
    suspend fun draft(projectId: String,taskId: String?,roundId: String?,name: String): String? = withContext(Dispatchers.IO) {
        database.readValue(key("draft",projectId,taskId,roundId,name))
    }
    suspend fun operations(): List<TaskHubOperation> = withContext(Dispatchers.IO) { all(database.readWhere { k -> val parts=runCatching { JSONArray(k) }.getOrNull();parts?.optString(0)==scope && parts.optString(1)=="operation" }) }
    suspend fun submit(operation: TaskHubOperation): String = withContext(Dispatchers.IO) {
        check(operation.scope==scope && operation.phase=="pending" && !operation.attempted)
        require(binding.service.capabilities.contains("business-operation-receipts")) { "原主服务尚未提供可恢复业务回执，未提交操作" }
        val context=fence()
        verify();requireCurrent(context)
        synchronized(guard) {
            check(verified && (if(operation.projectId==null) role=="admin" else operation.projectId in projects)) { "请连接原主服务并核对项目成员权限" }
        }
        if(operation.taskId!=null && operation.path.startsWith("tasks/")) {
            val detail=JSONObject(transport.request("tasks/${operation.taskId}"))
            val task=TaskHubTask.decode(detail.toString())
            val active=detail.getJSONArray("rounds").objects().firstOrNull { it.getInt("number")==task.currentRound }
            require(task.id==operation.taskId && task.projectId==operation.projectId && task.revision==operation.revision && active?.getString("id")==operation.roundId) { "任务轮次或版本已变化，请核对最新任务；原草稿保留" }
            requireCurrent(context)
        }
        authorizer.authorize(operation,transport); requireCurrent(context)
        var attempted=false
        synchronized(guard) {
            requireCurrent(context)
            database.transaction { values ->
                check(record(values,operation.id)==null) { "原操作已登记，请查询原 ID" }
                check(all(values).none { it.path==operation.path && it.method==operation.method && it.phase in listOf("pending","unknown") }) { "此操作结果未确认，请先查询原回执" }
                // Durable before transport. Recovery never resumes this POST automatically.
                values[operationKey(operation.id)]=operation.json().toString()
            }
        }
        try {
            synchronized(guard) {
                requireCurrent(context)
                database.transaction { values -> replace(values,operation.copy(attempted=true)) }
                attempted=true
            }
            val response=transport.request(operation.path,operation.method,operation.body,operation.id)
            database.transaction { values -> replace(values,operation.copy(attempted=true,phase="confirmed",response=response)) }
            requireCurrent(context)
            // Receipt confirmation cannot overwrite an independently edited draft.
            response
        } catch(error: Exception) {
            database.transaction { values ->
                val old=record(values,operation.id) ?: error("原任务记录缺失")
                replace(values,old.copy(attempted=attempted,phase=TaskHubRules.failurePhase(attempted,(error as? TaskHubHTTPError)?.status ?: 0)))
            }
            authorizationFailure(error,context);throw error
        }
    }
    suspend fun endWaiting(id: String) = withContext(Dispatchers.IO) {
        database.transaction { values -> val original=record(values,id) ?: error("没有原操作记录")
            if(original.phase=="pending") replace(values,original.copy(phase="unknown",waitEnded=true)) }
        // This detaches UI waiting only; it does not claim remote cancellation.
    }
    suspend fun queryReceipt(id: String): TaskHubOperation = withContext(Dispatchers.IO) {
        val context=fence();taskHubUUID(id)
        val original=record(database.readWhere { it==operationKey(id) },id) ?: error("没有当前账户的原操作记录")
        try {
            verify();requireCurrent(context)
            val wire=transport.request("operations/$id")
            require(TaskHubContract.validate(wire,"receipt")==null && TaskHubRules.receiptMatches(id,wire)) { "原操作回执无效" }
            val receipt=JSONObject(wire)
            synchronized(guard) {
                requireCurrent(context)
                database.transaction { values -> replace(values,original.copy(phase=receipt.getString("phase"),response=receipt.optJSONObject("result")?.toString())) }
            }
            record(database.readWhere { it==operationKey(id) },id)!!
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun readTasks(projectId: String,query: Map<String,String> = emptyMap()): String = withContext(Dispatchers.IO) {
        taskHubUUID(projectId);val context=fence();val cacheKey=key("list",projectId,null,null,JSONObject(query.toSortedMap()).toString());val sequence=beginRead(cacheKey)
        verify();requireCurrent(context)
        check(projectId in synchronized(guard) { projects })
        try {
            val wire=transport.request("tasks",query=query+mapOf("projectId" to projectId))
            val result=JSONObject(wire)
            require(result.getJSONArray("items").objects().all { TaskHubTask.decode(it.toString()).projectId==projectId })
            val cursor=result.optional("syncCursor");require(cursor==null || TaskHubRules.validCursor(cursor))
            synchronized(guard) { requireRead(context,cacheKey,sequence);database.transaction { values ->
                val applied=values[key("cursor",projectId,null,null,"events")] ?: "0"
                require(cursor==null || cursor.toLong()>=applied.toLong()) { "任务列表早于已收到的实时事件" }
                values[cacheKey]=wire
            } }
            wire
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun readDetail(projectId: String,taskId: String): String = withContext(Dispatchers.IO) {
        taskHubUUID(projectId);taskHubUUID(taskId);val context=fence();val cacheKey=key("detail",projectId,taskId,null,"detail");val sequence=beginRead(cacheKey)
        verify();requireCurrent(context)
        check(projectId in synchronized(guard) { projects })
        try {
            val wire=transport.request("tasks/$taskId");val task=TaskHubTask.decode(wire)
            require(task.id==taskId && task.projectId==projectId)
            synchronized(guard) { requireRead(context,cacheKey,sequence);database.transaction {
                val old=it[cacheKey]?.let(TaskHubTask::decode)
                require(old==null || task.revision>=old.revision) { "任务版本倒退，原缓存保留" };it[cacheKey]=wire
            } }
            wire
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun downloadAttachment(projectId: String,id: String,size: Int,sha256: String?): ByteArray {
        taskHubUUID(projectId);taskHubUUID(id);require(size in 1..20*1024*1024)
        require(sha256==null || sha256.matches(Regex("[a-f0-9]{64}")))
        val context=verifiedContext();check(projectId in synchronized(guard) { projects })
        try {
            val data=(transport as? TaskHubDownloadTransport ?: error("缺少原生附件读取接口")).downloadAttachment(id,size)
            requireCurrent(context);require(data.size==size && (sha256==null || taskHubSha(data)==sha256)) { "附件大小或摘要不一致" }
            return data
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun readSnapshot(projectId: String,taskId: String): TaskHubReadSnapshot = withContext(Dispatchers.IO) {
        taskHubUUID(projectId);taskHubUUID(taskId)
        val context=fence();val cacheKey=key("snapshot",projectId,taskId,null,"current");val sequence=beginRead(cacheKey)
        verify();requireCurrent(context);check(projectId in synchronized(guard) { projects })
        try {
            val wire=transport.request("tasks/$taskId/snapshot");val value=JSONObject(wire)
            val task=TaskHubTask.decode(wire);val snapshot=TaskHubReadSnapshot.decode(value.getJSONObject("snapshot"))
            require(task.id==taskId && task.projectId==projectId && snapshot.taskId==taskId && snapshot.projectId==projectId && snapshot.revision==task.revision)
            val round=value.optJSONObject("currentRoundDetail")
            require(round?.getString("id")==snapshot.currentRoundId && (round==null || round.getInt("number")==task.currentRound))
            synchronized(guard) { requireRead(context,cacheKey,sequence)
                database.transaction { it[cacheKey]=wire };snapshots[taskId]=snapshot
                if(round!=null) roundNumbers[snapshot.token+"/"+round.getString("id")]=round.getInt("number")
            }
            snapshot
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    suspend fun readHistory(snapshot: TaskHubReadSnapshot,roundId: String?=null,events: Boolean=false,cursor: String?=null): String = withContext(Dispatchers.IO) {
        roundId?.let(::taskHubUUID);require(!events || roundId!=null)
        val context=fence();val pageKind=if(events) "events" else if(roundId!=null) "round" else "rounds"
        val cacheKey=key("history",snapshot.projectId,snapshot.taskId,roundId,snapshot.token+"/"+pageKind+"/"+(cursor ?: ""));val sequence=beginRead(cacheKey)
        synchronized(guard) { requireCurrent(context);check(snapshots[snapshot.taskId]==snapshot && verified) { "任务快照已更新" } }
        require(java.time.Instant.parse(snapshot.expiresAt).isAfter(java.time.Instant.now())) { "任务快照已过期，请重新读取" }
        if(events && synchronized(guard) { roundNumbers[snapshot.token+"/"+roundId] }==null) readHistory(snapshot,roundId)
        val path="tasks/${snapshot.taskId}/rounds"+(roundId?.let { "/$it" } ?: "")+(if(events) "/events" else "")
        val query=mapOf("snapshot" to snapshot.token,"pageSize" to if(events) "50" else "30")+(cursor?.let { mapOf("cursor" to it) } ?: emptyMap())
        try {
            val wire=transport.request(path,query=query);val page=JSONObject(wire)
            require(TaskHubReadSnapshot.decode(page.getJSONObject("snapshot"))==snapshot) { "历史页不属于当前任务快照" }
            if(roundId!=null && !events) require(page.getJSONObject("round").getString("id")==roundId)
            if(events) {
                val number=synchronized(guard) { roundNumbers[snapshot.token+"/"+roundId] } ?: error("轮次已更新")
                require(page.getJSONArray("items").objects().all { it.getInt("roundNumber")==number }) { "历史事件不属于所选轮次" }
            }
            synchronized(guard) {
                requireRead(context,cacheKey,sequence);check(snapshots[snapshot.taskId]==snapshot)
                database.transaction { it[cacheKey]=wire }
                if(roundId!=null && !events) roundNumbers[snapshot.token+"/"+roundId]=page.getJSONObject("round").getInt("number")
            }
            wire
        } catch(error: Exception) {
            if(error is TaskHubHTTPError && error.status==409) synchronized(guard) {
                if(current(context) && snapshots[snapshot.taskId]==snapshot) snapshots.remove(snapshot.taskId)
            }
            authorizationFailure(error,context);throw error
        }
    }
    suspend fun observe(projectId: String,onChanged: (String,Long)->Unit) {
        taskHubUUID(projectId);val context=fence();verify();requireCurrent(context)
        check(projectId in synchronized(guard) { projects })
        val cursorKey=key("cursor",projectId,null,null,"events")
        val cursor=withContext(Dispatchers.IO) { database.readValue(cursorKey) }
        try {
            transport.events(projectId,cursor) { next,task,revision ->
                synchronized(guard) {
                    requireCurrent(context)
                    reads.clear();roundNumbers.clear();snapshots.entries.removeAll { it.value.projectId==projectId }
                    database.transaction { values ->
                        val old=values[cursorKey] ?: "0"
                        require(TaskHubRules.validCursor(next) && next.isNotEmpty() && next.toLong()>=old.toLong()) { "实时游标倒退" }
                        // Invalidate cached projections and commit the cursor together; no event is lost on crash.
                        values.keys.filter { k -> val p=runCatching { JSONArray(k) }.getOrNull();p?.optString(0)==scope && p.optString(2)==projectId && p.optString(1) in listOf("list","detail","snapshot","history") }.toList().forEach(values::remove)
                        values[cursorKey]=next
                    }
                    onChanged(task,revision)
                }
            }
        } catch(error: Exception) { authorizationFailure(error,context);throw error }
    }
    fun detach() { synchronized(guard) { generation=UUID.randomUUID().toString();verified=false;projects=emptySet();reads.clear();snapshots.clear() };TaskHubUploadCoordinator.detach(scope) }
}
