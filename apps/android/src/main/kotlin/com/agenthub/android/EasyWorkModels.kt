package com.agenthub.android

import com.agenthub.core.DomainContract
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun easyHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun easyBase64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
internal fun easyBytes(value: String): ByteArray {
    require(value.matches(Regex("[A-Za-z0-9_-]+"))) { "连接密钥格式无效" }
    return android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
}
/** Canonical JSON is also the authenticated byte framing used by the existing iOS peer. */
internal fun easyCanonical(value: Any?): String = when(value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { easyCanonical(it)+":"+easyCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { easyCanonical(value.get(it)) }
    is String -> JSONObject.quote(value).replace("\\/", "/")
    is Boolean, is Number -> value.toString()
    else -> error("无效连接数据")
}
data class AgentCandidate(val id: String, val transportId: String, val endpoint: String, val peerFingerprint: String? = null, val networkKind: String? = null) {
    fun json() = JSONObject().put("id",id).put("transportId",transportId).put("endpoint",endpoint).apply { peerFingerprint?.let { put("peerFingerprint",it) }; networkKind?.let { put("networkKind",it) } }
    companion object { fun decode(v: JSONObject) = AgentCandidate(v.getString("id"),v.getString("transportId"),v.getString("endpoint"),v.optional("peerFingerprint"),v.optional("networkKind")) }
}
data class AgentConnection(val target: TargetScope, val candidates: List<AgentCandidate>, val credentialRef: String, val preference: String = "automatic") {
    override fun equals(other: Any?)=other is AgentConnection&&sameScope(target,other.target)&&candidates==other.candidates&&credentialRef==other.credentialRef&&preference==other.preference
    override fun hashCode()=com.agenthub.core.MobileRules.cacheKey("",target,null,null).hashCode()*31+candidates.hashCode()+credentialRef.hashCode()+preference.hashCode()
    fun json() = JSONObject().put("target",CodexCommandCodec.target(target)).put("candidates",JSONArray(candidates.map { it.json() })).put("credentialRef",credentialRef).put("preference",preference)
    companion object { fun decode(v: JSONObject): AgentConnection {
        require(DomainContract.validate(v.toString(),"connection")==null) { "连接信息格式无效" }
        return AgentConnection(HostReadCodec.target(v.getJSONObject("target"))!!,v.getJSONArray("candidates").objects().map(AgentCandidate::decode),v.getString("credentialRef"),v.getString("preference"))
    } }
}
data class EasyWorkTarget(val authority: String, val subject: String, val desktopDeviceId: String, val workspaceId: String, val runtimeId: String) {
    fun json() = JSONObject().put("account",JSONObject().put("authority",authority).put("subject",subject)).put("desktopDeviceId",desktopDeviceId).put("workspaceId",workspaceId).put("runtimeId",runtimeId)
    fun scope() = TargetScope("easywork",authority,subject,desktopDeviceId,workspaceId,runtimeId)
    companion object { fun decode(v: JSONObject): EasyWorkTarget {
        val account=v.getJSONObject("account")
        return EasyWorkTarget(account.getString("authority"),account.getString("subject"),v.getString("desktopDeviceId"),v.getString("workspaceId"),v.getString("runtimeId")).also {
            require(listOf(it.authority,it.subject,it.desktopDeviceId,it.workspaceId,it.runtimeId).all { s->s.isNotBlank()&&s.length<=512 }) { "电脑身份无效" }
            CodexCommandCodec.target(it.scope())
        }
    } }
}
data class EasyWorkBinding(val value: String) {
    override fun equals(other: Any?)=other is EasyWorkBinding&&easyCanonical(json)==easyCanonical(other.json)
    override fun hashCode()=easyCanonical(json).hashCode()
    val json get()=JSONObject(value)
    val id get()=json.getString("bindingId")
    val target get()=EasyWorkTarget.decode(json.getJSONObject("target"))
    val desktopId get()=json.getString("desktopDeviceId")
    val mobileId get()=json.getString("mobileDeviceId")
    val desktopPublicKey get()=json.getString("desktopPublicKey")
    val mobilePublicKey get()=json.getString("mobilePublicKey")
    fun verify(identity: EasyWorkIdentity,expected: EasyWorkTarget? = null): EasyWorkBinding {
        require(json.getString("status")=="active" && mobileId==identity.deviceId && mobilePublicKey==identity.publicKey && desktopId==target.desktopDeviceId && easyBytes(desktopPublicKey).size==32 && (expected==null||target==expected)) { "配对设备或运行时身份不匹配" }
        require(easyCanonical(json.getJSONObject("account"))==easyCanonical(target.json().getJSONObject("account"))) { "配对账户不匹配" }
        return this
    }
}
data class EasyWorkCredential(val binding: EasyWorkBinding,val privateKey: String,val relayBinding: EasyWorkBinding?=null,val accessToken: String?=null,val grantRef: String?=null) {
    fun json()=JSONObject().put("binding",binding.json).put("privateKey",privateKey).apply { relayBinding?.let { put("relayBinding",it.json) };accessToken?.let { put("accessToken",it) };grantRef?.let { put("grantRef",it) } }
    companion object { fun decode(v: JSONObject)=EasyWorkCredential(EasyWorkBinding(v.getJSONObject("binding").toString()),v.getString("privateKey"),v.optJSONObject("relayBinding")?.let { EasyWorkBinding(it.toString()) },v.optional("accessToken"),v.optional("grantRef")) }
}
