package com.agenthub.android

import com.agenthub.core.TaskHubContract
import com.agenthub.core.TaskHubRules
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal fun taskHubSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun taskHubUUID(value: String): String = value.also { require(UUID.fromString(it).toString().equals(it,true)) { "任务身份无效" } }
internal class TaskHubIdentityChanged: Exception("任务服务或人类身份已变化，请重新核对；原草稿已保留")

internal data class TaskHubService(val deploymentId: String, val serverInstanceId: String, val primaryHostId: String,
    val name: String, val capabilities: List<String>) {
    fun same(other: TaskHubService) = deploymentId == other.deploymentId && serverInstanceId == other.serverInstanceId && primaryHostId == other.primaryHostId
    fun scope(actorId: String, deviceId: String) = TaskHubRules.scope(deploymentId, serverInstanceId, primaryHostId, actorId, deviceId)
    fun json() = JSONObject().put("version",1).put("contractVersion","1").put("deploymentId",deploymentId)
        .put("serverInstanceId",serverInstanceId).put("primaryHostId",primaryHostId).put("name",name).put("capabilities",org.json.JSONArray(capabilities))
    companion object {
        fun decode(wire: String): TaskHubService {
            require(TaskHubContract.validate(wire,"service") == null) { "主服务尚未完成配置或版本不兼容" }
            val v=JSONObject(wire)
            return TaskHubService(v.getString("deploymentId"),v.getString("serverInstanceId"),v.getString("primaryHostId"),v.getString("name"),v.getJSONArray("capabilities").strings())
        }
    }
}
internal data class TaskHubBinding(val service: TaskHubService, val endpoint: String, val fingerprint: String) {
    init { require(CodexEndpoints.local(endpoint)==endpoint.trimEnd('/'));require(fingerprint.matches(Regex("[a-fA-F0-9]{64}"))) }
    fun json()=JSONObject().put("service",service.json()).put("endpoint",endpoint).put("fingerprint",fingerprint)
    companion object { fun decode(v: JSONObject)=TaskHubBinding(TaskHubService.decode(v.getJSONObject("service").toString()),v.getString("endpoint"),v.getString("fingerprint")) }
}
internal data class TaskHubHumanSession(val actorId: String, val actorName: String, val role: String, val token: String, val expiresAt: String) {
    companion object {
        fun decode(wire: String): TaskHubHumanSession {
            require(TaskHubContract.validate(wire,"login")==null) { "人类设备会话响应无效" }
            val v=JSONObject(wire);val actor=v.getJSONObject("actor")
            require(actor.getString("kind")=="human") { "请使用人类账号登录任务中心" }
            java.time.Instant.parse(v.getString("expiresAt"))
            return TaskHubHumanSession(actor.getString("id"),actor.getString("name"),actor.getString("role"),v.getString("token"),v.getString("expiresAt"))
        }
    }
    fun json()=JSONObject().put("actor",JSONObject().put("id",actorId).put("name",actorName).put("role",role).put("kind","human"))
        .put("token",token).put("expiresAt",expiresAt)
}

internal data class TaskHubDeviceSession(val id: String,val deviceId: String,val deviceName: String,
    val createdAt: String,val expiresAt: String,val revokedAt: String?,val status: String,val current: Boolean) {
    companion object { fun decode(v: JSONObject): TaskHubDeviceSession {
        val revoked=v.optional("revokedAt")
        for(time in listOfNotNull(v.getString("createdAt"),v.getString("expiresAt"),revoked)) java.time.Instant.parse(time)
        val status=v.getString("status");require(status in listOf("active","expired","revoked"))
        return TaskHubDeviceSession(taskHubUUID(v.getString("id")),taskHubUUID(v.getString("deviceId")),v.getString("deviceName"),
            v.getString("createdAt"),v.getString("expiresAt"),revoked,status,v.getBoolean("current"))
    } }
}

/** The exact submitted bytes are kept outside the editable draft. */
internal data class TaskHubOperation(val id: String, val scope: String, val projectId: String?, val taskId: String?, val roundId: String?,
    val revision: Long?, val path: String, val method: String, val body: String, val bodySha256: String,
    val phase: String="pending", val attempted: Boolean=false, val response: String?=null, val waitEnded: Boolean=false) {
    init {
        taskHubUUID(id); projectId?.let(::taskHubUUID);taskId?.let(::taskHubUUID);roundId?.let(::taskHubUUID)
        require(projectId!=null || path=="projects" && method=="POST" && taskId==null && roundId==null) { "业务操作缺少项目作用域" }
        require(scope.startsWith("task-hub-v1:") && TaskHubRules.validPath(path) && method in listOf("POST","PUT","PATCH","DELETE"))
        require(revision==null || revision in 1..9007199254740991L);require(body.toByteArray(Charsets.UTF_8).size<=4*1024*1024)
        require(bodySha256==taskHubSha(body.toByteArray(Charsets.UTF_8))) { "任务提交原文已损坏" }
        require(TaskHubRules.acceptsPhase(phase,phase))
        require(phase!="confirmed" || response!=null) { "确认回执缺少原结果" }
        val input=JSONObject(body)
        input.optional("projectId")?.let { require(it==projectId) }
        val parts=path.split('/')
        if(parts.first()=="tasks" && parts.size>1) {
            require(parts[1]==taskId && roundId!=null && revision!=null) { "任务操作缺少原任务、轮次或版本" }
            val value=input.get("revision")
            require(value is Number && value.toDouble().isFinite() && value.toDouble()==revision.toDouble()) { "提交版本与原草稿不一致" }
        }
        if(parts.first()=="projects" && parts.size>1) require(parts[1]==projectId)
    }
    fun sameIntent(other: TaskHubOperation)=id==other.id && scope==other.scope && projectId==other.projectId && taskId==other.taskId &&
        roundId==other.roundId && revision==other.revision && path==other.path && method==other.method && body==other.body && bodySha256==other.bodySha256
    fun json()=JSONObject().put("id",id).put("scope",scope).put("projectId",projectId ?: JSONObject.NULL).put("taskId",taskId ?: JSONObject.NULL)
        .put("roundId",roundId ?: JSONObject.NULL).put("revision",revision ?: JSONObject.NULL).put("path",path).put("method",method).put("body",body)
        .put("bodySha256",bodySha256).put("phase",phase).put("attempted",attempted).put("response",response ?: JSONObject.NULL).put("waitEnded",waitEnded)
    companion object {
        fun decode(v: JSONObject)=TaskHubOperation(v.getString("id"),v.getString("scope"),v.optional("projectId"),v.optional("taskId"),v.optional("roundId"),
            if(v.isNull("revision")) null else v.getLong("revision"),v.getString("path"),v.getString("method"),v.getString("body"),v.getString("bodySha256"),
            v.getString("phase"),v.getBoolean("attempted"),v.optional("response"),v.getBoolean("waitEnded"))
    }
}

internal data class TaskHubTask(val id: String,val projectId: String,val currentRound: Int,val revision: Long,val wire: String) {
    companion object {
        fun decode(wire: String): TaskHubTask {
            require(TaskHubContract.validate(wire,"taskHeader")==null) { "任务领域响应无效" }
            val v=JSONObject(wire); val current=v.getInt("currentRound")
            require(current>0 && v.getLong("revision")>0)
            return TaskHubTask(taskHubUUID(v.getString("id")),taskHubUUID(v.getString("projectId")),current,v.getLong("revision"),wire)
        }
    }
}
internal data class TaskHubReadSnapshot(val token: String,val taskId: String,val projectId: String,val currentRoundId: String?,
    val revision: Long,val syncCursor: String,val expiresAt: String) {
    companion object { fun decode(v: JSONObject): TaskHubReadSnapshot {
        require(TaskHubContract.validate(v.toString(),"readSnapshot")==null && TaskHubRules.validCursor(v.getString("syncCursor")))
        java.time.Instant.parse(v.getString("expiresAt"))
        return TaskHubReadSnapshot(v.getString("token"),v.getString("taskId"),v.getString("projectId"),v.optional("currentRoundId")?.let(::taskHubUUID),v.getLong("revision"),v.getString("syncCursor"),v.getString("expiresAt"))
    } }
}
