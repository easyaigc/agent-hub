package com.agenthub.android

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URI
import java.time.Instant

data class EasyWorkHostDescriptor(val value: JSONObject) {
    val target get()=EasyWorkTarget.decode(value.getJSONObject("target"))
    val fingerprint get()=value.getString("fingerprint").lowercase()
    val publicKey get()=value.getString("publicKey")
    val endpoints get()=value.optJSONArray("endpoints")?.let { a->(0 until a.length()).map(a::getString) }.orEmpty()
    val hints get()=value.optJSONArray("endpointHints")?.objects().orEmpty()
}
data class EasyWorkLANOffer(val value: JSONObject) {
    val host get()=EasyWorkHostDescriptor(value)
    companion object {
        fun parse(text: String): EasyWorkLANOffer {
            val uri=Uri.parse(text.trim());require(uri.scheme=="easywork-host"&&uri.host=="pair") { "请扫描或粘贴桌面的局域网配对二维码" }
            val encoded=uri.getQueryParameter("descriptor")?:error("二维码缺少连接信息")
            require(encoded.length<22_000);val bytes=easyBytes(encoded);require(bytes.size<16_384)
            val value=JSONObject(String(bytes,Charsets.UTF_8));val host=EasyWorkHostDescriptor(value)
            require(host.fingerprint.matches(Regex("[a-f0-9]{64}"))&&easyBytes(host.publicKey).size==32)
            require(value.getString("pairingCode").matches(Regex("[0-9]{6}")))
            val expiry=Instant.parse(value.getString("expiresAt"))
            require(value.optString("connectionMode")=="automatic"||expiry>Instant.now()) { "连接信息无效或已过期，请在桌面重新生成" }
            require(host.endpoints.size in 1..32&&host.hints.size<=32)
            host.target.scope();EasyWorkPairing.directCandidates(host.endpoints,host.hints,host.fingerprint)
            return EasyWorkLANOffer(value)
        }
    }
}

/** Discovery cannot authorize a peer; claims require the QR's pinned identity. */
object EasyWorkPairing {
    private val probes=Semaphore(4)
    fun directCandidates(endpoints: List<String>,hints: List<JSONObject> = emptyList(),fingerprint: String): List<AgentCandidate> {
        require(endpoints.size<=96&&hints.size<=96&&fingerprint.matches(Regex("[a-fA-F0-9]{64}")))
        val normalizedHints=hints.associate { h->CodexEndpoints.local(h.getString("endpoint")) to h.getString("networkKind").also { require(it in listOf("lan","vpn","unknown")) } }
        return endpoints.map(CodexEndpoints::local).distinct().take(32).map { endpoint->
            val host=URI(endpoint).host
            val kind=normalizedHints[endpoint]?:if(host.endsWith(".ts.net")||host.startsWith("100.")||host.contains("fd7a:115c:a1e0:")) "vpn" else "unknown"
            AgentCandidate("direct-"+easyHash(endpoint.toByteArray()),"lan-wss",endpoint,fingerprint.lowercase(),kind)
        }
    }
    suspend fun readHost(endpoint: String,pin: String?=null,target: EasyWorkTarget?=null,publicKey: String?=null,timeout: Long=2): EasyWorkHostDescriptor = probes.withPermit {
        val base=CodexEndpoints.local(endpoint);var observed: String?=null
        val client=EasyWorkHttp.client(base,pin,if(pin==null) ({ observed=it }) else null)
        try {
            val host=EasyWorkHostDescriptor(EasyWorkHttp.request(client,"$base/host",limit=65_536,timeoutSeconds=timeout))
            require(host.value.getInt("version")==1&&host.fingerprint==(pin?.lowercase()?:observed)&&easyBytes(host.publicKey).size==32) { "电脑身份校验失败" }
            require(target==null||target==host.target);require(publicKey==null||publicKey==host.publicKey)
            require(host.endpoints.size<=32&&host.hints.size<=32);host.target.scope();directCandidates(host.endpoints,host.hints,host.fingerprint)
            host
        } finally { EasyWorkHttp.dispose(client) }
    }
    suspend fun lan(text: String,name: String,identity: EasyWorkIdentity): Pair<EasyWorkBinding,List<AgentCandidate>> {
        return connectOffer(EasyWorkLANOffer.parse(text),name,identity)
    }
    suspend fun discovered(endpoint: String,name: String,identity: EasyWorkIdentity,expectedDeviceId: String? = null, accept: () -> Unit = {}): Pair<EasyWorkBinding,List<AgentCandidate>> {
        val base=CodexEndpoints.local(endpoint)
        val host=readHost(base,timeout=5)
        require(expectedDeviceId==null||host.target.desktopDeviceId==expectedDeviceId) { "发现的电脑身份已变化，未申请凭据" }
        require(host.value.optString("connectionMode")=="automatic") { "请更新电脑端后使用直接连接" }
        val offer=EasyWorkLANOffer(JSONObject(host.value.toString()).put("endpoints",org.json.JSONArray(listOf(base))))
        return connectOffer(offer,name,identity,accept)
    }
    private suspend fun connectOffer(offer: EasyWorkLANOffer,name: String,identity: EasyWorkIdentity, accept: () -> Unit = {}): Pair<EasyWorkBinding,List<AgentCandidate>> {
        val expected=offer.host
        var failure: Exception=HostReadError("电脑暂无可用连接",transient=true)
        for(endpoint in if(offer.value.optString("connectionMode")=="automatic") expected.endpoints.take(1) else expected.endpoints) {
            try {
                val base=CodexEndpoints.local(endpoint)
                val host=readHost(base,expected.fingerprint,expected.target,expected.publicKey,5)
                val automatic=offer.value.optString("connectionMode")=="automatic"
                if(automatic)require(host.value.optString("connectionMode")=="automatic") { "请更新电脑端后使用直接连接" }
                accept()
                val body=JSONObject().put("mobileDeviceId",identity.deviceId).put("publicKey",identity.publicKey).put("name",name)
                if(!automatic)body.put("offerId",offer.value.getString("offerId")).put("code",offer.value.getString("pairingCode"))
                val client=EasyWorkHttp.client(base,host.fingerprint)
                val value=try { EasyWorkHttp.request(client,base+if(automatic) "/connections" else "/pairing/claim",body,timeoutSeconds=if(automatic)30 else 90) } finally { EasyWorkHttp.dispose(client) }
                val binding=EasyWorkBinding(value.getJSONObject("binding").toString());binding.verify(identity,host.target)
                require(binding.desktopPublicKey==expected.publicKey)
                return binding to directCandidates(listOf(base)+host.endpoints+expected.endpoints,host.hints+expected.hints,host.fingerprint)
            } catch(cancel: CancellationException) { throw cancel } catch(e: Exception) { failure=e }
        }
        throw failure
    }
    suspend fun relay(endpoint: String,code: String,identity: EasyWorkIdentity,token: suspend ()->String): EasyWorkBinding {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位电脑连接密码" }
        val transport=RelayFrameTransport(AgentCandidate("relay","relay-http",endpoint),null,token)
        try {
            val pending=EasyWorkBinding(transport.api("/pairing/claim",JSONObject().put("pairingCode",code)).toString())
            require(pending.mobileId==identity.deviceId&&pending.mobilePublicKey==identity.publicKey)
            if(pending.json.optString("status")=="active") { pending.verify(identity);return pending }
            return withTimeout(90_000) {
                while(true) {
                    val binding=transport.api("/bindings").getJSONArray("bindings").objects().firstOrNull { it.optString("bindingId")==pending.id }
                    if(binding!=null) {
                        require(binding.optString("status")!="revoked") { "桌面拒绝了配对" }
                        if(binding.optString("status")=="active") return@withTimeout EasyWorkBinding(binding.toString()).also { it.verify(identity) }
                    }
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") error("等待桌面确认超时")
            }
        } finally { transport.close() }
    }
    suspend fun reachable(candidates: List<AgentCandidate>,binding: EasyWorkBinding): List<AgentCandidate> = coroutineScope {
        val jobs=candidates.filter { it.transportId=="lan-wss"&&it.peerFingerprint!=null }.map { candidate->async {
            try { val host=readHost(candidate.endpoint,candidate.peerFingerprint,binding.target,binding.desktopPublicKey)
                directCandidates(listOf(candidate.endpoint)+host.endpoints,host.hints,host.fingerprint)
            } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { emptyList() }
        } }
        try {
            for(job in jobs) { val found=job.await();if(found.isNotEmpty())return@coroutineScope (found+candidates).distinctBy { it.endpoint } }
            candidates
        } finally { jobs.forEach { it.cancel() } }
    }
}
