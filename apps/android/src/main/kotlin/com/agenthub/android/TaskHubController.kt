package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Account secrets live only in the independent encrypted business database. */
internal class TaskHubController(context: Context, namespace: String="task-hub-v1",
    private val client: (TaskHubBinding,String,String)->TaskHubClient = { binding,device,token -> TaskHubClient(binding,device,token) }) {
    private val database=TaskHubSQLitePersistence(context.applicationContext,namespace)
    private val mutex=Mutex()
    private var repository: TaskHubRepository?=null
    private fun deviceId(): String {
        return database.transaction { values -> values["device-id"]?.also(::taskHubUUID) ?: UUID.randomUUID().toString().also { values["device-id"]=it } }
    }
    suspend fun login(binding: TaskHubBinding,username: String,password: String,deviceName: String,
        authorizer: TaskHubActionAuthorizer): TaskHubRepository = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(username.isNotBlank() && password.isNotEmpty() && deviceName.isNotBlank())
            val device=deviceId();val probe=client(binding,device,"")
            probe.verify()
            val body=JSONObject().put("username",username).put("password",password).put("deviceId",device).put("deviceName",deviceName).toString()
            // Explicit login only. Credentials are neither saved nor reused on another service.
            val session=TaskHubHumanSession.decode(probe.request("auth/mobile/login","POST",body))
            require(Instant.parse(session.expiresAt).isAfter(Instant.now())) { "设备会话已过期" }
            val scope=binding.service.scope(session.actorId,device)
            currentCoroutineContext().ensureActive()
            database.transaction { values ->
                values["selection"]=JSONObject().put("binding",binding.json()).put("scope",scope).toString()
                values["session:$scope"]=session.json().toString()
            }
            repository?.detach()
            TaskHubRepository(database,device,binding,session,client(binding,device,session.token),authorizer).also { repository=it }
        }
    }
    suspend fun restore(authorizer: TaskHubActionAuthorizer): TaskHubRepository? = withContext(Dispatchers.IO) {
        mutex.withLock {
            repository?.let { return@withLock it }
            val values=database.readWhere { it=="selection" || it=="device-id" || it.startsWith("session:") };val selection=values["selection"]?.let(::JSONObject) ?: return@withLock null
            val binding=TaskHubBinding.decode(selection.getJSONObject("binding"));val device=values["device-id"] ?: error("任务设备身份缺失")
            taskHubUUID(device)
            val scope=selection.getString("scope");val session=values["session:$scope"]?.let(TaskHubHumanSession::decode) ?: return@withLock null
            require(scope==binding.service.scope(session.actorId,device)) { "任务账户与主服务不一致" }
            if(!Instant.parse(session.expiresAt).isAfter(Instant.now())) return@withLock null
            // Restore never logs in, sends a task or resumes an unknown write.
            TaskHubRepository(database,device,binding,session,client(binding,device,session.token),authorizer).also { repository=it }
        }
    }
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val values=database.readWhere { it=="selection" || it=="device-id" || it.startsWith("session:") };val selection=values["selection"]?.let(::JSONObject) ?: return@withLock true
            val scope=selection.getString("scope");val binding=TaskHubBinding.decode(selection.getJSONObject("binding"))
            val session=values["session:$scope"]?.let(TaskHubHumanSession::decode)
            val device=values["device-id"] ?: error("任务设备身份缺失")
            repository?.detach();repository=null
            // Local revocation precedes the one explicit remote attempt.
            database.transaction { it.remove("selection");it.remove("session:$scope") }
            if(session==null) return@withLock true
            try {
                val connection=client(binding,device,session.token);connection.verify()
                connection.request("auth/mobile/logout","POST","{}")
                true
            } catch(_: Exception) { false } // Caller must report that remote revocation is unconfirmed.
        }
    }
}
