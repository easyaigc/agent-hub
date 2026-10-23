package com.agenthub.android

import com.agenthub.core.ReplyRecoveryRules
import com.agenthub.core.RuntimeIdentity
import org.json.JSONObject
import java.security.MessageDigest

/** Captured authority is metadata beside the immutable operation, never a replacement body. */
data class NativeReplyRecovery(val capturedTarget: String,val trust: String,val fingerprint: String,
    val phase: String="not-written",val consumed: Boolean=false,val inspection: String?=null) {
    fun body()=JSONObject().put("capturedTarget",JSONObject(capturedTarget)).put("trust",trust).put("fingerprint",fingerprint)
        .put("phase",phase).put("consumed",consumed).put("inspection",inspection?.let(::JSONObject) ?: JSONObject.NULL)
    fun matches(client: MobileClient,record: SavedOperation): Boolean = runCatching {
        val target=HostReadCodec.target(JSONObject(capturedTarget))!!
        record.kind=="reply" && !record.detached && client.codexDirect!=null && target.agentId=="codex" &&
            trust=="codex:"+client.codexDirect.fingerprint.lowercase() && RuntimeIdentity.matches(client.scope,target) && RuntimeIdentity.matches(client.codexDirect.target,target) &&
            record.target in client.operationTargets(JSONObject(record.intent).getString("threadId")) &&
            fingerprint(record.intent,capturedTarget)==fingerprint
    }.getOrDefault(false)
    fun strong(record: SavedOperation,now: Long=System.currentTimeMillis()): Boolean = inspection?.let {
        val intent=JSONObject(record.intent)
        ReplyRecoveryRules.strongNotAccepted(it,capturedTarget,intent.getString("hostId"),intent.getString("threadId"),record.id,fingerprint,now)
    } ?: false
    fun mayRetry(client: MobileClient,record: SavedOperation,now: Long=System.currentTimeMillis()): Boolean =
        ReplyRecoveryRules.mayRetry(phase,consumed,matches(client,record) && record.result==null,strong(record,now))
    fun capturedClient(client: MobileClient,record: SavedOperation): MobileClient {
        require(matches(client,record)) { "原消息的运行目标、证书或不可变内容已变化，只能查询原回执" }
        return client.copy(scope=HostReadCodec.target(JSONObject(capturedTarget)))
    }
    companion object {
        fun capture(client: MobileClient,intent: String): NativeReplyRecovery? {
            if(client.scope?.agentId!="codex" || client.codexDirect==null) return null
            val target=CodexCommandCodec.target(client.scope).toString()
            return NativeReplyRecovery(target,"codex:"+client.codexDirect.fingerprint.lowercase(),fingerprint(intent,target))
        }
        fun decode(value: JSONObject): NativeReplyRecovery {
            require(value.keys().asSequence().toSet()==setOf("capturedTarget","trust","fingerprint","phase","consumed","inspection"))
            return NativeReplyRecovery(CodexCommandCodec.validate(value.getJSONObject("capturedTarget"),"target").toString(),
                value.getString("trust"),value.getString("fingerprint"),value.getString("phase"),value.getBoolean("consumed"),value.optJSONObject("inspection")?.toString()).also {
                require(it.phase in listOf("not-written","uncertain","strong-not-accepted") && it.fingerprint.matches(Regex("[a-f0-9]{64}")))
            }
        }
        private fun fingerprint(intent: String,target: String): String {
            val wire=ReplyRecoveryRules.canonicalPayload(target,intent) ?: error("原回复内容不能生成版本化指纹")
            return MessageDigest.getInstance("SHA-256").digest(wire.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
