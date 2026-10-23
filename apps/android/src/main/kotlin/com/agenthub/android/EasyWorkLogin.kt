package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Device-bound authorization code + PKCE. Browser credentials never enter this app. */
class EasyWorkLogin(private val storage: NativeSessionStorage) {
    private val refresh=Mutex()
    private var waiting: CompletableDeferred<Uri>?=null
    private var browserStarted=false
    private var browserBackground=false
    fun background() { if(browserStarted&&waiting!=null)browserBackground=true }
    suspend fun foreground() {
        val active=waiting?:return
        if(browserBackground) { browserBackground=false;delay(500);if(waiting===active&&!active.isCompleted)active.completeExceptionally(IllegalStateException("登录已取消，请重新登录 EasyAI")) }
    }
    private fun https(value: String): String {
        val url=URI(value);require(url.scheme=="https"&&url.host!=null&&url.userInfo==null&&url.fragment==null) { "请填写 HTTPS 登录服务地址" };return value
    }
    private suspend fun identity()=EasyWorkIdentity.load(storage)
    private fun fields(id: EasyWorkIdentity)=mapOf("device_id" to id.deviceId,"device_platform" to "android","device_public_key" to id.publicKey)
    private fun key(authority: String,subject: String)="easywork-oidc:"+easyHash(easyCanonical(org.json.JSONArray(listOf(authority,subject))).toByteArray())
    private fun claims(token: String): JSONObject?=try {
        val segments=token.split('.');if(segments.size!=3)null else JSONObject(String(easyBytes(segments[1]),Charsets.UTF_8))
    } catch(_: Exception) { null }
    suspend fun login(context: Context,issuer: String,clientId: String,audience: String="easyai:remote-access"): String {
        require(clientId.isNotBlank()&&clientId.length<=512);check(waiting==null) { "登录正在进行" }
        val base=https(issuer).trimEnd('/');val http=EasyWorkHttp.client(base)
        val deferred=CompletableDeferred<Uri>();waiting=deferred
        try {
            val discovery=EasyWorkHttp.request(http,"$base/.well-known/openid-configuration",limit=65_536)
            require(discovery.getString("issuer").trimEnd('/')==base) { "登录服务身份不匹配" }
            val authorization=https(discovery.getString("authorization_endpoint"));val tokenEndpoint=https(discovery.getString("token_endpoint"))
            val identity=identity();val verifier=easyBase64(ByteArray(48).also { SecureRandom().nextBytes(it) });val state=UUID.randomUUID().toString()
            val pending=JSONObject().put("state",state).put("expiresAt",System.currentTimeMillis()+600_000).put("tokenEndpoint",tokenEndpoint).put("clientId",clientId).put("audience",audience).put("issuer",discovery.getString("issuer")).put("verifier",verifier)
            withContext(Dispatchers.IO) { storage.write("easywork-oidc-pending",pending.toString()) }
            val parameters=fields(identity)+mapOf("response_type" to "code","client_id" to clientId,"redirect_uri" to CALLBACK,"scope" to "openid profile offline_access","audience" to audience,"state" to state,"code_challenge" to easyBase64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),"code_challenge_method" to "S256")
            val url=Uri.parse(authorization).buildUpon().apply { parameters.forEach { (k,v)->appendQueryParameter(k,v) } }.build()
            withContext(Dispatchers.Main) { browserStarted=true;context.startActivity(Intent(Intent.ACTION_VIEW,url)) }
            val callback=withTimeout(600_000) { deferred.await() };verifyCallback(callback,pending)
            val result=exchange(tokenEndpoint,fields(identity)+mapOf("grant_type" to "authorization_code","client_id" to clientId,"redirect_uri" to CALLBACK,"code" to callback.getQueryParameter("code")!!,"code_verifier" to verifier,"audience" to audience),pending)
            return result.getString("accessToken")
        } finally { waiting=null;browserStarted=false;browserBackground=false;deferred.cancel();withContext(NonCancellable+Dispatchers.IO) { storage.remove("easywork-oidc-pending") };EasyWorkHttp.dispose(http) }
    }
    private fun verifyCallbackState(uri: Uri,pending: JSONObject) {
        require(uri.scheme=="com.easyai.remote"&&uri.host=="oauth"&&uri.path=="/callback"&&uri.fragment==null&&uri.getQueryParameters("state").size==1&&uri.getQueryParameter("state")==pending.getString("state")&&pending.getLong("expiresAt")>System.currentTimeMillis()) { "登录状态校验失败" }
    }
    private fun verifyCallback(uri: Uri,pending: JSONObject) {
        verifyCallbackState(uri,pending)
        require(uri.getQueryParameter("error")==null&&uri.getQueryParameters("code").size==1&&!uri.getQueryParameter("code").isNullOrBlank()) { "EasyAI 登录未完成" }
    }
    suspend fun callback(uri: Uri): Boolean {
        val pending=withContext(Dispatchers.IO) { storage.read("easywork-oidc-pending")?.let(::JSONObject) }?:return false
        if(uri.getQueryParameter("error")!=null) {
            verifyCallbackState(uri,pending)
            require(uri.getQueryParameters("error").size==1&&uri.getQueryParameter("code")==null) { "登录状态校验失败" }
            waiting?.completeExceptionally(IllegalStateException("EasyAI 登录未完成"));return true
        }
        verifyCallback(uri,pending)
        val active=waiting
        if(active!=null) { active.complete(uri);return true }
        // A browser may return after Android recreated the process. Exchange once, then clear.
        refresh.withLock {
            try { exchange(pending.getString("tokenEndpoint"),fields(identity())+mapOf("grant_type" to "authorization_code","client_id" to pending.getString("clientId"),"redirect_uri" to CALLBACK,"code" to uri.getQueryParameter("code")!!,"code_verifier" to pending.getString("verifier"),"audience" to pending.getString("audience")),pending) }
            finally { withContext(NonCancellable+Dispatchers.IO) { storage.remove("easywork-oidc-pending") } }
        }
        return true
    }
    private suspend fun exchange(endpoint: String,fields: Map<String,String>,settings: JSONObject): JSONObject {
        val url=https(endpoint);val client=EasyWorkHttp.client(url)
        try {
            val response=EasyWorkHttp.request(client,url,limit=65_536,form=fields)
            val token=response.getString("access_token");require(token.isNotEmpty());val claims=claims(token)?:error("登录令牌缺少设备身份")
            val id=identity();require(claims.optString("iss")==settings.getString("issuer")&&claims.optString("sub").isNotBlank()&&claims.optString("device_id")==id.deviceId&&claims.optString("device_public_key")==id.publicKey&&claims.optString("device_platform")=="android") { "登录令牌设备绑定无效" }
            val audience=claims.opt("aud");require(audience==settings.getString("audience")||audience is org.json.JSONArray&&settings.getString("audience") in audience.strings()) { "登录令牌用途不匹配" }
            val now=System.currentTimeMillis();val expiry=claims.getLong("exp")*1000;require(expiry>now&&claims.optString("azp")==settings.getString("clientId")) { "登录令牌已失效" }
            settings.optional("accessToken")?.let { old->val previous=claims(old)?:error("保存的账号身份无效");require(previous.getString("iss")==claims.getString("iss")&&previous.getString("sub")==claims.getString("sub")) { "刷新后的登录账号不匹配" } }
            val duration=response.getLong("expires_in");require(duration in 1..86_400)
            val session=JSONObject(settings.toString()).put("accessToken",token).put("refreshToken",response.optional("refresh_token")?:settings.optional("refreshToken")).put("expiresAt",minOf(expiry,now+duration*1000))
            session.remove("verifier");session.remove("state")
            withContext(Dispatchers.IO) { storage.write(key(claims.getString("iss"),claims.getString("sub")),session.toString());storage.write("easywork-oidc-last",session.toString()) }
            return session
        } finally { EasyWorkHttp.dispose(client) }
    }
    suspend fun validToken(fallback: String?,target: EasyWorkTarget?=null): String=refresh.withLock {
        val saved=withContext(Dispatchers.IO) { storage.read(if(target!=null)key(target.authority,target.subject) else "easywork-oidc-last")?.let(::JSONObject) }
        if(saved==null) {
            require(!fallback.isNullOrBlank()) { "请先登录 EasyAI" }
            if(target!=null&&claims(fallback)!=null) { val c=claims(fallback)!!;require(c.optString("iss")==target.authority&&c.optString("sub")==target.subject) { "登录账户与配对电脑不匹配" } }
            return@withLock fallback
        }
        if(saved.getLong("expiresAt")>System.currentTimeMillis()+60_000)return@withLock saved.getString("accessToken")
        val token=saved.optional("refreshToken")?:error("请重新登录 EasyAI")
        exchange(saved.getString("tokenEndpoint"),fields(identity())+mapOf("grant_type" to "refresh_token","refresh_token" to token,"client_id" to saved.getString("clientId"),"audience" to saved.getString("audience")),saved).getString("accessToken")
    }
    companion object { const val CALLBACK="com.easyai.remote://oauth/callback" }
}
