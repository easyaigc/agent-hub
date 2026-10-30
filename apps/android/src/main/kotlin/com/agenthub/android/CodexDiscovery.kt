package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.*
import com.agenthub.core.DomainContract
import com.agenthub.core.CodexDiscoveryContract
import com.agenthub.core.CodexRouteRules
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.*

data class CodexRoute(val endpoint: String, val networkKind: String = "unknown")
data class PairedCodexConnection(val target: TargetScope, val fingerprint: String, val routes: List<CodexRoute>, val systemEndpoints: List<String> = emptyList()) {
    override fun equals(other: Any?) = other is PairedCodexConnection && sameScope(target, other.target) && fingerprint == other.fingerprint && routes == other.routes && systemEndpoints == other.systemEndpoints
    override fun hashCode() = com.agenthub.core.MobileRules.cacheKey("", target, "", "").hashCode() * 31 + fingerprint.hashCode() * 17 + routes.hashCode() + systemEndpoints.hashCode()
}
data class CodexDescriptor(val name: String, val target: TargetScope, val fingerprint: String,
    val routes: List<CodexRoute>, val token: String? = null, val connectionMode: String? = null, val systemEndpoints: List<String> = emptyList()) {
    companion object {
        fun parsePairing(text: String): CodexDescriptor {
            require(text.length <= 24_000) { "连接二维码内容过大" }
            val uri = URI(text.trim())
            require(uri.scheme == "codex-host" && uri.host == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null && uri.rawUserInfo == null) { "请使用电脑提供的 Codex 连接二维码" }
            val query = uri.rawQuery.orEmpty()
            require(query.startsWith("descriptor=") && !query.contains('&')) { "连接二维码格式无效" }
            val encoded = query.removePrefix("descriptor=")
            require(encoded.matches(Regex("[A-Za-z0-9_-]+={0,2}"))) { "连接二维码格式无效" }
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
            require(bytes.size <= 16_384) { "连接二维码内容过大" }
            val wire = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val result = decode(wire, allowToken = true)
            require(result.token != null && result.token.length in 32..512 && result.token.none { it.isWhitespace() }) { "连接二维码缺少有效客户端密钥" }
            return result
        }
        fun decode(wire: String, allowToken: Boolean = false): CodexDescriptor {
            val json = JSONObject(wire)
            val descriptor = JSONObject(wire).apply { if(allowToken) remove("token") }
            require(CodexDiscoveryContract.validate(descriptor.toString()) == null) { "电脑连接信息格式无效" }
            val name = json.get("name") as? String ?: throw HostReadError("电脑名称无效")
            val targetJSON = json.getJSONObject("target")
            require(DomainContract.validate(targetJSON.toString(), "target") == null && targetJSON.getString("agentId") == "codex") { "电脑运行时身份无效" }
            val target = HostReadCodec.target(targetJSON)!!
            val fingerprint = (json.get("fingerprint") as? String).orEmpty().lowercase()
            require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 256 && fingerprint.matches(Regex("[a-f0-9]{64}"))) { "电脑连接信息无效" }
            val endpoints = json.getJSONArray("endpoints")
            require(endpoints.length() in 1..32) { "电脑连接地址无效" }
            val raw = endpoints.strings()
            require(raw.distinct().size == raw.size && raw.all { it.length <= 512 }) { "电脑连接地址无效" }
            val hints = json.optJSONArray("endpointHints") ?: JSONArray()
            require(hints.length() <= 32) { "电脑连接地址过多" }
            val kinds = hints.objects().associate { hint ->
                require(hint.keys().asSequence().toSet() == setOf("endpoint", "networkKind")) { "网络路径信息无效" }
                val kind = hint.getString("networkKind")
                require(kind in listOf("lan", "vpn", "unknown")) { "网络路径信息无效" }
                CodexEndpoints.local(hint.getString("endpoint")) to kind
            }
            val routes = raw.map { address ->
                val endpoint = CodexEndpoints.local(address)
                val host = URI(endpoint).host.lowercase()
                CodexRoute(endpoint, kinds[endpoint] ?: if (host.endsWith(".ts.net") || host.startsWith("100.") || host.contains("fd7a:115c:a1e0:")) "vpn" else "unknown")
            }.distinctBy { it.endpoint }
            val token = if (json.has("token")) json.get("token") as? String ?: throw HostReadError("客户端密钥无效") else null
            return CodexDescriptor(name, target, fingerprint, routes, token, json.optional("connectionMode"))
        }
    }
}

/** Address validation never resolves a DNS name to decide whether it is private. */
object CodexEndpoints {
    private fun private4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
        return a == 10 || a == 127 || a == 100 && b in 64..127 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254
    }
    fun local(value: String): String {
        require(value.length <= 512) { "电脑地址过长" }
        val base = NativeHostReads.baseAddress(if (value.contains("://")) value else "https://$value")
        require(base.scheme == "https" && (base.port == -1 || base.port in 1..65535)) { "请使用局域网或 VPN 电脑的 HTTPS 地址" }
        val host = base.host.lowercase().removeSurrounding("[", "]").trimEnd('.')
        var canonical = host
        val private = if (':' in host) {
            val literal = host.substringBefore('%')
            require(literal.matches(Regex("[a-f0-9:.]+"))) { "电脑地址无效" }
            val address = InetAddress.getByName(literal)
            canonical = address.hostAddress!!.substringBefore('%') + if ('%' in host) "%" + host.substringAfter('%') else ""
            val bytes = address.address
            private4(bytes) || address.isLoopbackAddress || bytes.size == 16 && ((bytes[0].toInt() and 0xfe) == 0xfc || (bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80)
        } else {
            val parts = host.split('.')
            val octets = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            parts.size == 4 && octets.size == 4 && private4(octets.map { it.toByte() }.toByteArray()) || host == "localhost" || host.endsWith(".local") || host.endsWith(".ts.net")
        }
        require(private) { "请填写局域网或 VPN 电脑地址" }
        val authority = if (':' in canonical) "[$canonical]" else canonical
        return "https://$authority" + if (base.port == -1 || base.port == 443) "" else ":${base.port}"
    }
}

/** Trust is scoped to one explicit pairing, endpoint and exact leaf DER hash. */
internal object PairedCodexTLS {
    fun configure(connection: HttpsURLConnection, endpoint: String, paired: PairedCodexConnection) {
        val normalized = CodexEndpoints.local(endpoint)
        if (paired.routes.any { it.endpoint == normalized }) configure(connection, normalized, paired.fingerprint)
        else require(CodexRouteRules.systemEndpoint(endpoint)?.let { it in paired.systemEndpoints } == true) { "连接地址不属于已配对电脑" }
        // A saved Serve route keeps the platform's CA and hostname verification.
    }
    fun configure(connection: HttpsURLConnection, endpoint: String, fingerprint: String) {
        val expectedHost = URI(endpoint).host
        val expected = fingerprint.lowercase()
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("Client TLS is unsupported") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing peer certificate")
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) throw java.security.cert.CertificateException("Paired certificate changed")
            }
        }
        val ssl = SSLContext.getInstance("TLS"); ssl.init(null, arrayOf(manager), java.security.SecureRandom())
        connection.sslSocketFactory = ssl.socketFactory
        connection.hostnameVerifier = HostnameVerifier { host, _ -> host.removeSurrounding("[", "]").equals(expectedHost.removeSurrounding("[", "]"), ignoreCase = true) }
    }
}

data class DiscoveredCodexHost(val id: String, val name: String, val endpoint: String, val known: Boolean, val reachable: Boolean, val networkKind: String? = null, val target: TargetScope? = null, val fingerprint: String? = null)

/** Foreground NSD and saved VPN routes; TXT data is only a discovery hint. */
class CodexHostDiscovery internal constructor(context: Context, private val session: MobileSession, storage: NativeSessionStorage?,
    private val nativePlatform: NativeNsdPlatform<NsdServiceInfo>,private val nativeQueue: NativeNsdQueue,dispatcher: CoroutineDispatcher) {
    constructor(context: Context,session: MobileSession,storage: NativeSessionStorage?=null):
        this(context,session,storage,AndroidNsdPlatform(context),AndroidNsdQueue(),Dispatchers.Main.immediate)
    companion object { const val LOCAL_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK" }
    private val context = context.applicationContext
    private val tasks = CoroutineScope(SupervisorJob() + dispatcher + requireNotNull(session.codexMigrationContext[NativeMigrationIoWork]))
    private val budget = Semaphore(4)
    private var revision = UUID.randomUUID()
    private var active = false
    private var networkRevision = UUID.randomUUID()
    private val publicDiscovery = CodexPublicDiscovery(storage,budget=budget)
    private val savedDiscovery=CodexSavedRouteDiscovery(budget=budget)
    private var publicHosts = emptyList<DiscoveredCodexHost>()
    // Retain the exact old handle through real stop terminals, including cancelled waits.
    private var native: NativeNsdLifetime<NsdServiceInfo>? = null
    private var scanning: Job? = null
    private var rescanRequested=false
    private val advertised = mutableMapOf<String, DiscoveredCodexHost>()
    var hosts by mutableStateOf<List<DiscoveredCodexHost>>(emptyList())
        private set
    var needsPermission by mutableStateOf(false)
        private set
    var detail by mutableStateOf<String?>(null)
        private set
    /** A discovery provider supplies a private address hint; this never authorizes or saves a client. */
    internal fun offerHint(endpoint: String, hostId: String, name: String) {
        if (!current(revision) || !permitted()) return
        require(hostId.isNotBlank() && hostId.length <= 256 && name.isNotBlank() && name.length <= 256)
        val address=CodexEndpoints.local(endpoint);val key="$hostId@$address"
        if(advertised.containsKey(key)||advertised.size<32) advertised[key]=DiscoveredCodexHost(hostId,name,address,false,false)
        rebuild();scan()
    }
    fun permitted() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(LOCAL_PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun checkMain(){check(Looper.myLooper()==Looper.getMainLooper())}
    private fun current(generation: UUID)=active&&revision==generation&&session.codexDiscoveryAllowed&&!migrationStopPending
    fun start() {
        checkMain();stop()
        if(!session.codexDiscoveryAllowed)return
        active=true;revision=UUID.randomUUID()
        needsPermission=!permitted()
        if(needsPermission){detail="允许本地网络访问后可发现局域网与 VPN 电脑";rebuild();return}
        detail=null
        val generation=revision
        tasks.launch {
            // Starting another SDK registration before this terminal would lose the old listener.
            native?.awaitStopped()
            ensureActive();if(!current(generation))return@launch
            val lifetime=NativeNsdLifetime(nativePlatform,nativeQueue,"_codex-host._tcp.",
                {service->if(current(generation))resolved(service)},
                {service->if(current(generation)){advertised.remove(service.serviceName);rebuild()}},
                {if(current(generation))networkChanged()},
                {event,_->if(current(generation))when(event) {
                    "discovery-start-failed"->detail="暂时无法发现局域网电脑，可使用连接二维码或已保存地址"
                    "discovery-start-threw"->detail="局域网发现暂不可用"
                    else->Unit
                }})
            native=lifetime;lifetime.start()
            while(current(generation)){scan();delay(10_000)}
        }
    }
    private fun networkChanged() {
        networkRevision=UUID.randomUUID();scanning?.cancel();scanning=null
        publicHosts=publicDiscovery.currentHints();session.easyWorkDiscovery?.offerVPN(emptyList());rebuild();scan()
    }
    fun stop() {
        checkMain()
        active=false;revision=UUID.randomUUID();tasks.coroutineContext.cancelChildren();scanning=null;rescanRequested=false
        native?.stop() // Request only; the exact listener remains owned until awaitStopped.
        publicDiscovery.clearVerifiedHints()
        advertised.clear();publicHosts=emptyList();session.easyWorkDiscovery?.offerVPN(emptyList());rebuild()
    }
    internal val migrationStopped get()=!active&&tasks.coroutineContext[Job]!!.children.none()&&native?.isStopped!=false
    internal val migrationStopPending get()=native?.let {it.isRetiring&&!it.isStopped}==true
    internal suspend fun stopAndJoinForMigration() {
        checkMain();stop()
        val original=native
        val children=tasks.coroutineContext[Job]!!.children.toList()
        children.forEach {it.join()}
        original?.awaitStopped() // Job.join alone is not an NSD/resolve/network terminal.
        check(!active&&native===original&&migrationStopped)
        // Root awaits the shared session NativeMigrationIoWork after this returns.
    }
    private fun resolved(service: NsdServiceInfo) {
        try {
            val id=service.attributes["hostId"]?.let {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it)).toString()}.orEmpty()
            val host=service.host?.hostAddress?:throw HostReadError("Missing discovered host")
            require(id.isNotBlank()&&id.length<=256&&service.port in 1..65535)
            val endpoint=CodexEndpoints.local("https://"+if(':' in host)"[$host]:${service.port}" else "$host:${service.port}")
            if(advertised.containsKey(service.serviceName)||advertised.size<32)advertised[service.serviceName]=DiscoveredCodexHost(id,service.serviceName.take(256),endpoint,false,false)
            rebuild();scan()
        }catch(_: Exception){}
    }
    private fun rebuild() {
        val known = session.clients.filter { !it.isDemo && it.scope?.agentId == "codex" }
        hosts = known.map { client -> savedDiscoveryRow(client,publicHosts,hosts) } + (publicHosts + advertised.values).distinctBy { it.id }.sortedBy { it.name }.filter { row -> known.none { it.id == row.id } }
    }
    fun scan() {
        if (!current(revision) || !permitted()) return
        publicHosts=publicDiscovery.currentHints();rebuild()
        if(scanning?.isActive==true) { rescanRequested=true;return }
        val generation = revision; val path=networkRevision
        val clients = session.clients.filter { !it.isDemo && it.scope?.agentId == "codex" }.sortedByDescending { it.id==session.selectedClient.id }
        scanning = tasks.launch {
          try {
            val current={ current(generation) && networkRevision==path }
            kotlinx.coroutines.coroutineScope {
              launch {
                savedDiscovery.scan(clients,(publicHosts+advertised.values).toList(),session::storedCredential,current,accept@ { route->
                    if(!current())return@accept false
                    val now=session.clients.firstOrNull { it.id==route.client.id } ?: return@accept false
                    if(!sameDiscoveryConnection(route.client,now))return@accept false
                    // A newer healthy current route defeats an older offline scan's alternate.
                    if(!route.client.online&&now.online&&route.endpoint!=now.endpoint)return@accept false
                    val accepted=session.updateCodexRoute(route.client,route.endpoint,route.descriptor)
                    if(accepted) { hosts=hosts.map { if(it.id==route.client.id&&it.known)it.copy(endpoint=route.endpoint,reachable=true) else it };rebuild() }
                    accepted
                },{ original->
                    val now=session.clients.firstOrNull { it.id==original.id }
                    if(current()&&now!=null&&!now.online&&sameDiscoveryConnection(original,now))
                        hosts=hosts.map { if(it.id==original.id&&it.known)it.copy(reachable=false) else it }
                })
              }
              launch {
                val public=publicDiscovery.scan(advertised.values.toList(),session.clients.toList(),session::storedCredential,{ update->
                    if(current()) {
                        publicHosts=update.codex
                        // Only this newly completed descriptor, never a retained row,
                        // can supply an offline saved client's first authenticated route.
                        update.verified?.let { verified->
                            val client=publicDiscoveryOriginalClient(verified,clients,session.clients)
                            if(client!=null)session.updateCodexRoute(client,verified.row.endpoint,verified.descriptor)
                        }
                        session.easyWorkDiscovery?.offerVPN(update.easywork);rebuild()
                    }
                },current)
                if(current()) { publicHosts=public.codex;session.easyWorkDiscovery?.offerVPN(public.easywork);rebuild() }
              }
            }
          } finally {
            if(current(generation)&&networkRevision==path) {
                scanning=null
                if(rescanRequested) { rescanRequested=false;scan() }
            }
          }
        }
    }
}

internal fun encodeCodexDirect(value: PairedCodexConnection) = JSONObject().put("target", encodeTarget(value.target)).put("fingerprint", value.fingerprint).put("routes", JSONArray(value.routes.map { JSONObject().put("endpoint", it.endpoint).put("networkKind", it.networkKind) })).put("systemEndpoints", JSONArray(value.systemEndpoints))
internal fun decodeCodexDirect(json: JSONObject) = PairedCodexConnection(HostReadCodec.target(json.getJSONObject("target"))!!, json.getString("fingerprint"), json.getJSONArray("routes").objects().map { CodexRoute(CodexEndpoints.local(it.getString("endpoint")), it.getString("networkKind")) }, json.optJSONArray("systemEndpoints")?.strings().orEmpty()).also { require(it.fingerprint.matches(Regex("[a-f0-9]{64}")) && it.routes.size in 1..32 && it.systemEndpoints.size <= 4 && it.systemEndpoints.all { endpoint -> CodexRouteRules.systemEndpoint(endpoint) == endpoint }) }
internal fun encodeTarget(s: TargetScope) = JSONObject().put("agentId", s.agentId).put("hostId", s.hostId).put("workspaceId", s.workspaceId).put("runtimeId", s.runtimeId).apply { if (s.accountAuthority != null) put("account", JSONObject().put("authority", s.accountAuthority).put("subject", s.accountSubject)) }
