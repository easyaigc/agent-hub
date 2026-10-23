package com.agenthub.android

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal class EasyWorkDeviceAuthorizationError(val status: Int,val code: String): Exception(when(code) {
    "offer_not_found"->"连接码无效，请在电脑上重新生成"
    "offer_expired","refresh_expired"->"设备授权已过期，请在电脑上重新配对"
    "offer_unavailable","claim_unavailable"->"连接码已使用，请在电脑上重新生成"
    "grant_inactive","binding_inactive","grant_revoked"->"电脑已撤销设备授权，请重新配对"
    "unauthorized","credential_rotated","invalid_dpop"->"设备授权校验失败，请重新连接电脑"
    "scope_denied","feature_disabled"->"电脑尚未允许此设备操作"
    else->"设备授权请求被拒绝（$status）"
})

internal data class EasyWorkDeviceOffer(val origin: String,val code: String) {
    companion object {
        fun origin(value: String): String {
            val url=value.trim().toHttpUrl()
            require(url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null&&url.encodedPath=="/") { "请填写电脑显示的中继服务地址" }
            require(url.isHttps||BuildConfig.DEBUG&&url.host in setOf("127.0.0.1","localhost","::1")) { "设备授权需要 HTTPS" }
            return url.toString().trimEnd('/')
        }
        fun code(value: String): String = value.replace(" ","").replace("-","").uppercase().also {
            require(it.matches(Regex("[0-9A-HJKMNP-TV-Z]{16}"))) { "请输入电脑显示的 16 位连接码" }
        }
        fun parse(text: String): EasyWorkDeviceOffer {
            val uri=Uri.parse(text.trim())
            require(uri.scheme=="easywork"&&uri.host=="pair"&&uri.path.isNullOrEmpty()&&uri.fragment==null&&uri.queryParameterNames==setOf("version","relay","code")&&uri.getQueryParameters("version")==listOf("1")) { "请扫描电脑提供的设备授权二维码" }
            require(uri.getQueryParameters("relay").size==1&&uri.getQueryParameters("code").size==1)
            val origin=origin(uri.getQueryParameter("relay")!!)
            val url=origin.toHttpUrl()
            // A scanned link cannot select an arbitrary credential recipient.
            require(url.host=="51easyai.com"&&url.isHttps&&(url.port==443)||BuildConfig.DEBUG&&url.host in setOf("localhost","127.0.0.1","::1")) { "二维码的中继服务未受信任，请核对电脑上的服务地址" }
            return EasyWorkDeviceOffer(origin,code(uri.getQueryParameter("code")!!))
        }
    }
}

internal data class EasyWorkDeviceGrant(val value: String) {
    val json get()=JSONObject(value)
    val binding get()=EasyWorkBinding(json.getJSONObject("binding").toString())
    val id get()=json.getString("grantId")
    val accessToken get()=json.getString("accessToken")
    val refreshToken get()=json.getString("refreshToken")
    val accessExpiry get()=Instant.parse(json.getString("accessExpiresAt"))
    val refreshExpiry get()=Instant.parse(json.getString("refreshExpiresAt"))
    fun verify(identity: EasyWorkIdentity,signer: EasyWorkDPoP,expected: EasyWorkBinding?=null): EasyWorkDeviceGrant {
        val v=json;val b=binding.verify(identity,expected?.target)
        val metadata=b.json.getJSONObject("deviceAuthorization")
        require(v.getString("protocol")=="EWDAP/1"&&v.getString("deviceId")==identity.deviceId&&EasyWorkTarget.decode(v.getJSONObject("target"))==b.target)
        require(metadata.getString("protocol")=="EWDAP/1"&&metadata.getString("grantId")==id&&metadata.getString("authKeyThumbprint")==signer.thumbprint&&metadata.getInt("keyVersion")==v.getInt("keyVersion")&&v.getInt("keyVersion")>0)
        require(expected==null||b==expected) { "设备授权目标或密钥已改变，请重新配对" }
        require(listOf(accessToken,refreshToken).all { it.matches(Regex("[A-Za-z0-9_-]{43}")) }&&accessExpiry>Instant.EPOCH&&refreshExpiry>Instant.EPOCH)
        val scopes=v.getJSONArray("scopes").strings()
        require(scopes.size==scopes.distinct().size&&scopes.all { it in SCOPES }&&scopes.containsAll(listOf("relay:read","relay:write","runtime:read","runtime:control")))
        return this
    }
    companion object { val SCOPES=setOf("relay:read","relay:write","runtime:read","runtime:control","files:read","files:write") }
}

/** Phone proves a device key; only the already signed-in Desktop approves an account grant. */
internal class EasyWorkDeviceAuthorization(private val storage: NativeSessionStorage) {
    private val claims=Mutex()
    private val refreshes=Mutex()
    private suspend fun read(key: String)=withContext(Dispatchers.IO) { storage.read(key)?.let(::JSONObject) }
    private suspend fun write(key: String,value: JSONObject)=withContext(Dispatchers.IO) { storage.write(key,value.toString()) }
    private suspend fun remove(key: String)=withContext(Dispatchers.IO) { storage.remove(key) }
    private fun endpoint(origin: String,path: String)=EasyWorkDeviceOffer.origin(origin)+"/v2/remote-access"+path
    private suspend fun request(origin: String,path: String,method: String,signer: EasyWorkDPoP,token: String?=null,body: String?=null): JSONObject {
        val url=endpoint(origin,path);val bytes=body?.toByteArray(Charsets.UTF_8)
        val headers=mutableMapOf("DPoP" to signer.proof(method,url,token,body=bytes))
        token?.let { require(it.matches(Regex("[A-Za-z0-9_-]{43}")));headers["Authorization"]="DPoP $it" }
        val client=EasyWorkHttp.client(url)
        return try { EasyWorkHttp.request(client,url,limit=65_536,method=method,rawBody=bytes,headers=headers) } finally { EasyWorkHttp.dispose(client) }
    }
    suspend fun pair(offer: EasyWorkDeviceOffer,onPending: (String)->Unit = {}): Pair<EasyWorkBinding,String> = claims.withLock {
        val identity=EasyWorkIdentity.load(storage);val signer=EasyWorkDPoP.load(identity)
        val key="easywork-device-claim:"+easyHash((offer.origin+"\n"+signer.thumbprint+"\n"+offer.code).toByteArray())
        val state=read(key)?:run {
            val body=JSONObject().put("operationId",UUID.randomUUID().toString()).put("connectionCode",offer.code).put("device",JSONObject().put("deviceId",identity.deviceId).put("platform","android").put("name",android.os.Build.MODEL.take(100)).put("publicKey",identity.publicKey).put("authPublicKey",signer.jwk))
            val fresh=JSONObject().put("origin",offer.origin).put("body",easyCanonical(body))
            write(key,fresh) // Persist the exact intent before its first attempted submission.
            fresh
        }
        require(state.getString("origin")==offer.origin)
        if(!state.has("claimId")) {
            val result=request(offer.origin,"/pairing/claims","POST",signer,body=state.getString("body"))
            require(result.getString("protocol")=="EWDAP/1"&&result.getString("status")=="pending")
            val id=result.getString("claimId");UUID.fromString(id)
            val pending=EasyWorkDeviceDelivery.open(identity,result.getString("sealedDelivery"),id)
            require(pending.getString("claimId")==id&&pending.getString("pendingToken").matches(Regex("[A-Za-z0-9_-]{43}"))&&Instant.parse(pending.getString("expiresAt"))>Instant.now())
            state.put("claimId",id).put("offerId",result.getString("offerId")).put("pendingToken",pending.getString("pendingToken")).put("expiresAt",result.getString("expiresAt"))
            result.optional("verificationCode")?.let { state.put("verificationCode",it) }
            write(key,state)
        }
        onPending(state.optional("verificationCode")?.let { "请在电脑上确认此设备，核对码：$it" }?:"请在已登录的电脑上确认此设备")
        val id=state.getString("claimId")
        val grant=withTimeout(360_000) {
            while(true) {
                val result=request(offer.origin,"/pairing/claims/$id","GET",signer,state.getString("pendingToken"))
                require(result.getString("protocol")=="EWDAP/1"&&result.getString("claimId")==id&&result.getString("offerId")==state.getString("offerId"))
                when(result.getString("status")) {
                    "approved"->return@withTimeout EasyWorkDeviceGrant(EasyWorkDeviceDelivery.open(identity,result.getString("sealedDelivery"),id).toString()).verify(identity,signer)
                    "pending"->require(Instant.parse(result.getString("expiresAt"))>Instant.now()) { "连接码已过期，请在电脑上重新生成" }
                    "rejected"-> { remove(key);error("电脑拒绝了此设备，请重新配对") }
                    "expired","revoked"-> { remove(key);error("设备授权已过期或撤销，请重新配对") }
                    else->error("设备授权状态无效")
                }
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE") error("等待电脑确认超时")
        }
        val ref="easywork-device-grant:"+easyHash((offer.origin+"\n"+grant.id).toByteArray())
        write(ref,JSONObject().put("origin",offer.origin).put("grant",grant.json))
        // Keep this immutable operation receipt so an interrupted save can resume
        // the same claim without consuming a second offer or issuing another grant.
        state.put("grantRef",ref);write(key,state)
        grant.binding to ref
    }
    suspend fun headers(ref: String,binding: EasyWorkBinding,method: String,url: String,body: ByteArray?=null): Map<String,String> {
        val token=validAccess(ref,binding);val identity=EasyWorkIdentity.load(storage);val signer=EasyWorkDPoP.load(identity)
        return mapOf("Authorization" to "DPoP $token","DPoP" to signer.proof(method,url,token,body=body))
    }
    private suspend fun validAccess(ref: String,binding: EasyWorkBinding): String = refreshes.withLock {
        val saved=read(ref)?:error("设备授权已移除，请重新配对")
        val identity=EasyWorkIdentity.load(storage);val signer=EasyWorkDPoP.load(identity)
        var grant=EasyWorkDeviceGrant(saved.getJSONObject("grant").toString()).verify(identity,signer,binding)
        require(grant.refreshExpiry>Instant.now()) { "设备授权已过期，请在电脑上重新配对" }
        var pending=saved.optJSONObject("refreshOperation")
        if(pending==null&&grant.accessExpiry>Instant.now().plusSeconds(60))return@withLock grant.accessToken
        if(pending==null) {
            pending=JSONObject().put("operationId",UUID.randomUUID().toString()).put("token",grant.refreshToken)
            saved.put("refreshOperation",pending);write(ref,saved)
        }
        val id=pending.getString("operationId");val body=easyCanonical(JSONObject().put("operationId",id))
        val result=request(saved.getString("origin"),"/device-grants/refresh","POST",signer,pending.getString("token"),body)
        require(result.getString("protocol")=="EWDAP/1"&&result.getString("grantId")==grant.id)
        grant=EasyWorkDeviceGrant(EasyWorkDeviceDelivery.open(identity,result.getString("sealedDelivery"),id).toString()).verify(identity,signer,binding)
        require(grant.accessExpiry>Instant.now())
        saved.put("grant",grant.json).remove("refreshOperation");write(ref,saved)
        grant.accessToken
    }
    suspend fun revoke(ref: String,binding: EasyWorkBinding) {
        val saved=read(ref)?:return
        val identity=EasyWorkIdentity.load(storage);val signer=EasyWorkDPoP.load(identity)
        val grant=EasyWorkDeviceGrant(saved.getJSONObject("grant").toString()).verify(identity,signer,binding)
        val id=saved.optional("revokeOperationId")?:UUID.randomUUID().toString().also { saved.put("revokeOperationId",it) }
        write(ref,saved)
        val body=easyCanonical(JSONObject().put("operationId",id))
        val result=request(saved.getString("origin"),"/device-grants/${grant.id}","DELETE",signer,grant.accessToken,body)
        require(result.getString("protocol")=="EWDAP/1"&&result.getString("grantId")==grant.id&&result.getString("bindingId")==binding.id&&result.getString("status")=="revoked")
        remove(ref)
    }
}
