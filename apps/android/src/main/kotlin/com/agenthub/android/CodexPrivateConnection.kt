package com.agenthub.android

import com.agenthub.core.CodexDiscoveryContract
import com.agenthub.core.PrivateConnectionRules
import com.agenthub.core.TargetScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CodexPrivateAuthorization(val descriptor: CodexDescriptor, val token: String, val workspace: MobileWorkspace)
internal data class CodexPublicSource(val target: TargetScope, val fingerprint: String, val endpoints: List<String>)

/** No caller credential enters an unpaired probe. A first certificate is bound to the returned descriptor. */
internal object CodexDiscoveryAPI {
    private val executor = Executors.newFixedThreadPool(4)
    private data class Response(val wire: String, val certificate: String?)
    private suspend fun request(endpoint: String, path: String, fingerprint: String? = null,
        body: JSONObject? = null, token: String = "", maximum: Int = 65_536, discover: Boolean = false, queryHost: String? = null,
        connectionBudget: CodexConnectionReadBudget? = null): Response = suspendCancellableCoroutine { c ->
        require(path in listOf("host", "discovery/peers", "connection/claim", "v1/workspace", "v1/discovery/peers"))
        require(connectionBudget==null || body==null && path in listOf("host","discovery/peers","v1/discovery/peers"))
        val base = if (discover || fingerprint != null) URI(CodexEndpoints.local(endpoint)) else NativeHostReads.baseAddress(endpoint)
        val active = AtomicReference<HttpURLConnection?>()
        val job = executor.submit {
            var connection: HttpURLConnection? = null
            try {
                if (!c.isActive) return@submit
                connection = URI(base.toString().trimEnd('/') + "/" + path + (queryHost?.let { "?hostId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")).toURL().openConnection() as HttpURLConnection
                active.set(connection)
                if (!c.isActive) return@submit
                val observed = AtomicReference<String?>()
                if (fingerprint != null) {
                    require(connection is HttpsURLConnection)
                    PairedCodexTLS.configure(connection, base.toString(), fingerprint)
                } else if (discover) {
                    require(connection is HttpsURLConnection && token.isEmpty() && body == null)
                    val manager = object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) { throw java.security.cert.CertificateException("Unsupported client certificate") }
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) {
                            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing certificate")
                            leaf.checkValidity()
                            observed.set(MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it.toInt() and 255) })
                        }
                    }
                    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
                    connection.sslSocketFactory = ssl.socketFactory
                    connection.hostnameVerifier = HostnameVerifier { host, _ -> host.removeSurrounding("[", "]").equals(base.host.removeSurrounding("[", "]"), true) }
                }
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectionBudget?.millis ?: if (body == null) 2_000 else 15_000
                connection.readTimeout = connectionBudget?.millis ?: if (body == null) 6_000 else 15_000
                connection.useCaches = false
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 8192)
                    connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                require(status == 200) { if (status == 401 && path == "connection/claim") "Host Key 不正确，请在电脑端核对后重试" else if (status == 403) "此设备已撤销或未获得连接权限" else if (status in 300..399) "电脑地址发生重定向，未保存连接" else "无法连接这台电脑（$status），原连接已保留" }
                val wire = connection.inputStream.use { NativeWireReader.read(it, maximum) { c.isActive } }
                if (c.isActive) c.resume(Response(wire, observed.get()))
            } catch (failure: Exception) { if (c.isActive) c.resumeWithException(failure) }
            finally { active.getAndSet(null)?.disconnect(); connection?.disconnect() }
        }
        c.invokeOnCancellation { active.getAndSet(null)?.disconnect(); job.cancel(true) }
    }
    suspend fun discover(endpoint: String, expected: CodexPublicSource? = null): CodexDescriptor =
        readDescriptor(endpoint,expected,CodexConnectionReadBudget.BACKGROUND_DISCOVERY)
    suspend fun discoverForConnection(endpoint: String, expected: CodexPublicSource? = null): CodexDescriptor =
        readDescriptor(endpoint,expected,CodexConnectionReadBudget.EXPLICIT_CONNECTION)
    private suspend fun readDescriptor(endpoint: String,expected: CodexPublicSource?,budget: CodexConnectionReadBudget): CodexDescriptor=codexConnectionRead(budget) {
        val response = request(endpoint, "host", expected?.fingerprint, discover = expected == null,connectionBudget=budget)
        val descriptor = CodexDescriptor.decode(response.wire)
        require(if (expected == null) descriptor.fingerprint == response.certificate else descriptor.fingerprint == expected.fingerprint && sameScope(descriptor.target, expected.target)) { "电脑证书或运行时身份不匹配，未保存连接" }
        descriptor
    }
    suspend fun peers(source: CodexPublicSource, endpoint: String): List<Pair<String, List<String>>> =codexConnectionRead(CodexConnectionReadBudget.PEER_DIRECTORY) {
        val response = request(endpoint, "discovery/peers", source.fingerprint, maximum = 32_768, queryHost = source.target.hostId,connectionBudget=CodexConnectionReadBudget.PEER_DIRECTORY)
        decodePeers(response.wire, source.target)
    }
    suspend fun legacyPeers(client: MobileClient, token: String): List<Pair<String, List<String>>> =codexConnectionRead(CodexConnectionReadBudget.PEER_DIRECTORY) {
        require(token.isNotEmpty() && client.scope?.agentId == "codex")
        // This authenticated compatibility read is limited to the already saved target and route.
        val response = request(client.endpoint ?: error("缺少地址"), "v1/discovery/peers", client.codexDirect?.takeIf { paired -> paired.routes.any { it.endpoint == client.endpoint } }?.fingerprint, token = token, maximum = 32_768, queryHost = client.id,connectionBudget=CodexConnectionReadBudget.PEER_DIRECTORY)
        decodePeers(response.wire, client.scope!!)
    }
    private fun decodePeers(wire: String, expected: TargetScope): List<Pair<String, List<String>>> {
        require(CodexDiscoveryContract.validateNamed(wire, "vpnSnapshot") == null) { "电脑发现目录格式无效" }
        val json = JSONObject(wire)
        require(sameScope(HostReadCodec.target(json.getJSONObject("target")), expected)) { "电脑发现目录目标不匹配" }
        return json.getJSONArray("peers").objects().map {
            val addresses = it.getJSONArray("addresses").strings()
            require(addresses.all(PrivateConnectionRules::isVPNAddress)) { "VPN 电脑地址无效" }
            it.getString("name") to addresses
        }
    }
    suspend fun authorize(host: DiscoveredCodexHost, identity: EasyWorkIdentity, storage: NativeSessionStorage, hostKey: String, accept: (CodexDescriptor)->Unit = {}): CodexPrivateAuthorization {
        val hostKey = hostKey.trim()
        require(hostKey.length in 32..512 && hostKey.none(Char::isWhitespace)) { "请输入这台电脑的 Host Key" }
        val endpoint = CodexEndpoints.local(host.endpoint)
        val descriptor = discoverForConnection(endpoint)
        require(descriptor.target.hostId == host.id && (host.target == null || sameScope(host.target, descriptor.target)) && (host.fingerprint == null || host.fingerprint == descriptor.fingerprint)) { "电脑身份已变化，请重新查找；原连接已保留" }
        require(descriptor.connectionMode == "host-key") { "请更新这台电脑的 Host 后使用密钥授权连接" }
        accept(descriptor)
        val target = descriptor.target
        val claim = JSONObject().put("version", 1).put("target", encodeTarget(target)).put("publicKey", identity.publicKey)
        require(CodexDiscoveryContract.validateNamed(claim.toString(), "privateClaim") == null)
        // Persist the exact key/target/certificate before the only POST. An unknown result is never automatically retried.
        val intent=JSONObject().put("claim",claim).put("endpoint",endpoint).put("fingerprint",descriptor.fingerprint).toString()
        val intentId=MessageDigest.getInstance("SHA-256").digest(intent.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        storage.operationTransaction {
            val saved=storage.read("codex-private-claim:$intentId")
            if(saved==null) storage.write("codex-private-claim:$intentId",intent)
            else require(saved==intent) { "原连接申请记录不一致，未重新提交" }
        }
        val response = request(endpoint, "connection/claim", descriptor.fingerprint, body = claim, token = hostKey, maximum = 8192)
        val token = try { CodexPrivateCrypto.open(response.wire, target, identity) }
        catch (failure: Exception) { throw HostReadError("电脑凭据校验失败，原连接已保留",failure) }
        val client = MobileClient(host.id, host.name, target, endpoint, false, "desktop-runtime", false, null, true, PairedCodexConnection(target, descriptor.fingerprint, (listOf(CodexRoute(endpoint)) + descriptor.routes).distinctBy { it.endpoint }.take(32)))
        val workspace = NativeHostReads { token }.workspace(client)
        require(workspace.mode == "desktop-runtime" && sameScope(workspace.scope, target)) { "电脑运行时身份不匹配，未保存连接" }
        val routes = NativeHostReads { token }.connectionRoutes(client, target)
        return CodexPrivateAuthorization(descriptor.copy(systemEndpoints = routes), token, workspace)
    }
}

internal object CodexPrivateCrypto {
    fun open(wire: String, target: TargetScope, identity: EasyWorkIdentity): String {
        require(CodexDiscoveryContract.validateNamed(wire, "privateReply") == null) { "电脑凭据响应格式无效" }
        val json = JSONObject(wire)
        require(PrivateConnectionRules.replyMatches(wire, target, identity.publicKey)) { "电脑凭据目标不匹配" }
        fun raw(value: String, size: Int? = null): ByteArray = easyBytes(value).also { require(easyBase64(it) == value && (size == null || it.size == size)) { "电脑凭据编码无效" } }
        val privateBytes = raw(identity.privateKey, 32)
        val secret = ByteArray(32)
        val key = X25519PrivateKeyParameters(privateBytes, 0)
        require(key.generatePublicKey().encoded.contentEquals(raw(identity.publicKey, 32))) { "设备身份密钥不匹配" }
        key.generateSecret(X25519PublicKeyParameters(raw(json.getString("ephemeralPublicKey"), 32), 0), secret, 0)
        require(secret.any { it.toInt() != 0 }) { "电脑密钥交换无效" }
        val context = PrivateConnectionRules.context(target, identity.publicKey).toByteArray(Charsets.UTF_8)
        fun hmac(k: ByteArray, v: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(v) }
        val prk = hmac(ByteArray(32), secret)
        val derived = hmac(prk, context + byteArrayOf(1))
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, raw(json.getString("nonce"), 12)))
            cipher.updateAAD(context)
            val plain = cipher.doFinal(raw(json.getString("ciphertext")))
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString().also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) { "电脑凭据格式无效" } }
        } finally { privateBytes.fill(0); secret.fill(0); prk.fill(0); derived.fill(0) }
    }
}
