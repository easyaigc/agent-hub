package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.AISharingRules
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AISharingScope(val target: TargetScope, val model: String) {
    val key get() = AISharingRules.key(target, model)
    fun json() = JSONObject().put("version",1).put("target",CodexCommandCodec.target(target)).put("model",model)
    fun matches(other: AISharingScope) = AISharingRules.matches(target,model,other.target,other.model)
    companion object {
        fun decode(v: JSONObject): AISharingScope {
            require(v.keys().asSequence().toSet()==setOf("version","target","model") && v.getInt("version")==1)
            val target=HostReadCodec.target(CodexCommandCodec.validate(v.getJSONObject("target"),"target"))!!
            val model=v.getString("model");require(model.isNotEmpty() && model.length<=2048)
            return AISharingScope(target,model)
        }
    }
}
data class AISharingRequest(val scope: AISharingScope,val targetKey: String,val computerName: String,val agentName: String,val modelName: String)
data class AISharingGrant(val id: String,val scope: AISharingScope,val computerName: String,val agentName: String,val modelName: String,val grantedAt: Long) {
    fun json()=JSONObject().put("id",id).put("scope",scope.json()).put("computerName",computerName).put("agentName",agentName).put("modelName",modelName).put("grantedAt",grantedAt)
}

/** A successful durable permission write precedes publication. No operation/callback is captured. */
class AISharingConsent(private val storage: NativeSessionStorage?) {
    val grants=mutableStateListOf<AISharingGrant>()
    var busy by mutableStateOf(false);private set
    var failure by mutableStateOf<String?>(null);private set
    private val mutex=Mutex()
    var revision by mutableStateOf(0L);private set
    fun permits(scope: AISharingScope)=!busy && failure==null && grants.any { it.scope.matches(scope) }
    suspend fun restore() {
        try {
            val loaded=withContext(Dispatchers.IO) {
                storage?.read("ai-sharing-v1")?.let { text ->
                    val v=JSONObject(text);require(v.getInt("version")==1);val list=v.getJSONArray("grants");require(list.length()<=1000)
                    list.objects().map { g ->
                        require(g.getString("id")==UUID.fromString(g.getString("id")).toString())
                        AISharingGrant(g.getString("id"),AISharingScope.decode(g.getJSONObject("scope")),g.getString("computerName"),g.getString("agentName"),g.getString("modelName"),g.getLong("grantedAt"))
                    }
                }.orEmpty()
            }
            grants.clear();grants.addAll(loaded);failure=null;revision++
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { grants.clear();failure="共享许可无法读取，原记录已保留。";revision++ }
    }
    private suspend fun replace(next: List<AISharingGrant>)=mutex.withLock {
        busy=true;revision++
        try {
            require(next.size<=1000) { "共享许可数量超出本机限制" }
            val store=storage ?: error("本地安全存储不可用")
            val body=JSONObject().put("version",1).put("grants",JSONArray(next.map { it.json() })).toString()
            withContext(Dispatchers.IO) { store.write("ai-sharing-v1",body) }
            grants.clear();grants.addAll(next);failure=null
        } catch(e: Exception) { failure="共享许可未能保存，请重试；当前内容不会继续提交。";throw e }
        finally { busy=false;revision++ }
    }
    suspend fun allow(request: AISharingRequest) {
        check(!busy)
        replace(grants.filterNot { it.scope.matches(request.scope) }+AISharingGrant(UUID.randomUUID().toString(),request.scope,request.computerName,request.agentName,request.modelName,System.currentTimeMillis()))
    }
    suspend fun revoke(id: String) { check(!busy);replace(grants.filterNot { it.id==id }) }
    internal fun clearAcceptance() { grants.clear();failure=null;busy=false;revision++ }
}
