package com.agenthub.android

import com.agenthub.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun JSONObject.optional(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

data class MobileProject(val id: String, val hostId: String, val name: String, val roots: List<String>)
data class MobileThread(val id: String, val hostId: String, val title: String, val state: String,
    val projectId: String? = null, val projectIds: List<String> = emptyList(), val unread: Boolean = false) {
    fun belongs(project: MobileProject) = hostId == project.hostId && (projectId == project.id || project.id in projectIds)
    val showsUnread get() = unread && state !in listOf("running", "waiting-for-approval")
}
data class MobileFile(val id: String, val name: String, val mediaType: String, val sizeBytes: Long,
    val hostId: String, val threadId: String, val sha256: String)
data class MobileWorkspace(val hostId: String, val name: String, val mode: String,
    val projects: List<MobileProject>, val threads: List<MobileThread>,
    val nextProjectCursor: String?, val nextThreadCursor: String?, val scope: TargetScope?,
    val capabilities: List<String>, val wire: String, val runtimeConnected: Boolean? = null)
data class MobileHistory(val hostId: String, val threadId: String, val messages: List<MobileMessage>,
    val nextCursor: String?, val scope: TargetScope?, val state: String, val turnId: String?, val wire: String,
    val runtimePresent: Boolean = false, val runtime: MobileRuntime? = null, val epoch: String? = null, val cursor: Long? = null,
    val windowed: Boolean = false, val historyRevision: Long? = null,
    val historyWindowKind: String? = null, val synchronization: MobileHistorySynchronization? = null)
data class MobileHistorySynchronization(val state: String, val checkedAt: String? = null)

/** Bounded pre-revision V9 compatibility only; it is not a history stability proof. */
internal object MobileHistoryWindowPolicy {
    fun knownRevisionMatches(previous: MobileHistory?, incoming: MobileHistory): Boolean =
        incoming.historyRevision!=null && previous?.historyRevision==incoming.historyRevision
    private fun legacyWindow(page: MobileHistory): Boolean = page.windowed && page.historyRevision==null &&
        page.historyWindowKind in listOf("latest","earlier") && page.synchronization!=null
    private fun sameLegacyWindow(previous: MobileHistory?, incoming: MobileHistory, sameReadGeneration: Boolean): Boolean =
        sameReadGeneration && previous!=null && legacyWindow(previous) && legacyWindow(incoming) &&
        previous.hostId==incoming.hostId && previous.threadId==incoming.threadId && previous.epoch==incoming.epoch
    fun acceptsEarlierPage(previous: MobileHistory?, incoming: MobileHistory, sameReadGeneration: Boolean): Boolean =
        !incoming.windowed || knownRevisionMatches(previous,incoming) ||
        sameLegacyWindow(previous,incoming,sameReadGeneration) && incoming.historyWindowKind=="earlier"
    fun compatiblePrefix(previous: MobileHistory?, incoming: MobileHistory,
        previousMessages: List<MobileMessage>, sameReadGeneration: Boolean): Boolean {
        if(knownRevisionMatches(previous,incoming))return true
        if(!sameLegacyWindow(previous,incoming,sameReadGeneration) || incoming.historyWindowKind!="latest")return false
        val incomingIds=incoming.messages.map { it.id }.toSet()
        return previousMessages.any { it.id in incomingIds }
    }
}


/** HTTP wire decoding stays at the provider boundary; shared schema validates before decoding. */
object HostReadCodec {
    fun target(json: JSONObject?): TargetScope? = json?.let {
        val account = it.optJSONObject("account")
        TargetScope(it.getString("agentId"), account?.getString("authority"), account?.getString("subject"),
            it.getString("hostId"), it.getString("workspaceId"), it.getString("runtimeId"))
    }
    fun thread(it: JSONObject) = MobileThread(it.getString("id"), it.getString("hostId"),
        it.getString("title"), it.getString("state"), it.optional("projectId"),
        it.optJSONArray("projectIds")?.strings().orEmpty(), it.optBoolean("hasUnreadTurn"))
    fun message(it: JSONObject): MobileMessage = MobileMessage(it.getString("id"), it.getString("role"),
        NativeHistoryStrings.share(it.getString("text")), it.optional("status"), it.optional("kind"), it.optional("detail")?.let(NativeHistoryStrings::share),
        it.optional("runId"), it.optional("replyPhase"), it.optional("replyMessageId"),
        it.optJSONArray("files")?.strings().orEmpty(), it.optJSONArray("attachments")?.objects()?.map { file ->
            MobileFile(file.getString("id"), file.getString("name"), file.getString("mediaType"),
                file.getLong("sizeBytes"), file.getString("hostId"), file.getString("threadId"), file.getString("sha256"))
        }.orEmpty(), it.optJSONArray("blocks")?.toString()?.let(NativeHistoryStrings::share))
    private fun validated(wire: String, definition: String): JSONObject {
        require(CodexHttpContract.validate(wire, definition) == null) { "电脑返回的数据格式无效" }
        return JSONObject(wire)
    }
    fun workspace(wire: String): MobileWorkspace {
        val it = validated(wire, "workspace")
        val host = it.getString("hostId")
        val projects = it.getJSONArray("projects").objects().map { p -> MobileProject(p.getString("id"),
            p.getString("hostId"), p.getString("name"), p.getJSONArray("roots").strings()) }
        val threads = it.getJSONArray("threads").objects().map(::thread)
        val scope = target(it.optJSONObject("readTarget"))
        require(projects.all { p -> p.hostId == host } && threads.all { t -> t.hostId == host } &&
            (scope == null || scope.hostId == host)) { "电脑身份不匹配" }
        return MobileWorkspace(host, it.getString("name"), it.getString("connectionMode"), projects,
            threads, it.optional("nextProjectCursor"), it.optional("nextThreadCursor"), scope,
            SessionPolicies.codexCapabilities(it.optJSONArray("capabilities")?.strings().orEmpty(),it.getString("connectionMode")),wire,it.optJSONObject("agentConnectionState")?.booleanOrNull("runtimeConnected"))
    }
    fun history(wire: String): MobileHistory {
        val it = validated(wire, "history")
        val windowed = MobileSnapshotCodec.decodeHistoryWindow(it.opt("historyWindow"))
        val revision = MobileSnapshotCodec.decodeHistoryRevision(it.opt("historyRevision"))
        val windowKind = MobileSnapshotCodec.decodeHistoryWindowKind(it.opt("historyWindow"))
        val synchronization = MobileSnapshotCodec.decodeSynchronization(it.opt("synchronization"))
        val scope = target(it.optJSONObject("readTarget"))
        val host = it.getString("hostId"); val thread = it.getString("threadId")
        val messages = it.getJSONArray("messages").objects().map(::message)
        require((scope == null || scope.hostId == host) && messages.flatMap { m -> m.attachments }.all { f ->
            f.hostId == host && f.threadId == thread }) { "电脑或附件身份不匹配" }
        val runtime = it.optJSONObject("runtime")
        // Decoded messages own the content. Retaining the full raw report as
        // well doubles a long live thread in memory; the wire is cache metadata.
        it.put("messages", JSONArray())
        return MobileHistory(host, thread, messages, it.optional("nextCursor"), scope,
            runtime?.optional("state") ?: "idle", runtime?.optional("turnId"), it.toString(), runtime != null,
            runtime?.let { MobileRuntime.decode(it,host,thread) },it.optional("epoch"),if(it.has("cursor") && !it.isNull("cursor")) it.getLong("cursor") else null,windowed,revision,windowKind,synchronization)
    }
}

/** Deliberately exposes reads only. Adding execution will use a separate capability boundary. */
interface AgentReads {
    suspend fun workspace(client: MobileClient, projectCursor: String? = null, threadCursor: String? = null): MobileWorkspace
    suspend fun history(client: MobileClient, threadId: String, cursor: String? = null): MobileHistory
}
typealias HostReads = AgentReads

class HostReadError(message: String, cause: Throwable? = null, val transient: Boolean = false): Exception(message, cause)
class NativeHostReads(private val token: (String) -> String) : HostReads {
    companion object {
        private val executor = Executors.newFixedThreadPool(4)
        fun baseAddress(value: String): URI {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw HostReadError("连接地址无效") }
            require(uri.scheme == "https" || uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost", "[::1]", "::1")) { "请使用 HTTPS 地址；本机开发可使用回环地址" }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/")) { "连接地址无效" }
            return uri
        }
        fun url(endpoint: String, path: String, query: Map<String, String>): URI {
            require(path in listOf("v1/workspace", "v1/history", "host"))
            val base = baseAddress(endpoint).toString().trimEnd('/')
            val encoded = query.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            return URI("$base/$path" + if (encoded.isEmpty()) "" else "?$encoded")
        }
    }
    private suspend fun read(client: MobileClient, path: String, query: Map<String, String>): String =
        readResponse(client,path,query) { input,active -> NativeWireReader.read(input,if(path=="host") 65_536 else 64*1024*1024,active) }
    private suspend fun <T> readResponse(client: MobileClient, path: String, query: Map<String, String>,decode: (java.io.InputStream,()->Boolean)->T): T = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = executor.submit {
            var connection: HttpURLConnection? = null
            try {
                if (!continuation.isActive) return@submit
                connection = url(client.endpoint ?: throw HostReadError("尚未配置连接"), path, query).toURL().openConnection() as HttpURLConnection
                active.set(connection)
                if (!continuation.isActive) return@submit
                client.codexDirect?.let { paired ->
                    val endpoint = CodexEndpoints.local(client.endpoint!!)
                    require(paired.routes.any { it.endpoint == endpoint } && connection is javax.net.ssl.HttpsURLConnection) { "连接地址不属于已配对电脑" }
                    PairedCodexTLS.configure(connection as javax.net.ssl.HttpsURLConnection, endpoint, paired.fingerprint)
                }
                connection.instanceFollowRedirects = false
                connection.connectTimeout = if(path == "host") 2_000 else 10_000
                connection.readTimeout = if(path == "host") 2_000 else 35_000
                connection.useCaches = false; connection.requestMethod = "GET"
                token(client.id).takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status != 200) throw HostReadError(when (status) {
                    401, 403 -> "客户端密钥无效或未获得读取权限"
                    in 300..399 -> "连接地址发生重定向，请核对电脑地址"
                    else -> "电脑请求失败（$status）"
                }, transient = status in listOf(502,503,504))
                val value = connection.inputStream.use { input -> decode(input) { continuation.isActive } }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                val transient = error is java.net.SocketException || error is java.net.SocketTimeoutException || error is java.net.UnknownHostException || error is java.net.NoRouteToHostException
                if (continuation.isActive) continuation.resumeWithException(if (error is HostReadError || error is IllegalArgumentException) error else HostReadError("无法连接电脑，请检查网络或稍后重试", error, transient))
            } catch (failure: OutOfMemoryError) {
                if(continuation.isActive) continuation.resumeWithException(HostReadError("手机内存不足，未能读取完整历史；原缓存和草稿已保留"))
            } finally { active.getAndSet(null)?.disconnect(); connection?.disconnect() }
        }
        continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
    override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
        val query = buildMap { projectCursor?.let { put("projectCursor", it) }; threadCursor?.let { put("threadCursor", it) }; client.readerId?.let { put("readerId", nativeReaderId(it)) } }
        val wire=read(client, "v1/workspace", query)
        val result = withContext(Dispatchers.Default) { decode { HostReadCodec.workspace(wire) } }
        if (client.id.isNotEmpty() && result.hostId != client.id) throw HostReadError("电脑身份不匹配，未保存连接")
        if (client.requiresReadTarget && result.scope == null || client.scope != null && result.scope != null && !sameScope(client.scope, result.scope)) throw HostReadError("电脑运行时身份已改变，请重新连接")
        return result
    }
    suspend fun descriptor(client: MobileClient): CodexDescriptor {
        val descriptor = CodexDescriptor.decode(read(client, "host", emptyMap()))
        require(descriptor.target.hostId == client.id && sameScope(descriptor.target, client.scope)) { "电脑运行时身份不匹配" }
        client.codexDirect?.let { require(descriptor.fingerprint == it.fingerprint) { "已配对电脑证书发生变化，请重新配对" } }
        return descriptor
    }
    override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
        val query = buildMap { put("hostId", client.id); put("threadId", threadId); cursor?.let { put("cursor", it) }; client.readerId?.let { put("readerId", nativeReaderId(it)) } }
        val result = readResponse(client, "v1/history", query,NativeHistoryWire::history)
        if (result.hostId != client.id || result.threadId != threadId || client.requiresReadTarget && result.scope == null || client.scope != null && result.scope != null && !sameScope(client.scope, result.scope)) throw HostReadError("电脑或对话身份不匹配")
        return result
    }
    private fun <T> decode(block: ()->T): T = try { block() } catch(_: OutOfMemoryError) {
        throw HostReadError("手机内存不足，未能读取完整历史；原缓存和草稿已保留")
    }
}

internal fun sameScope(a: TargetScope?, b: TargetScope?): Boolean = com.agenthub.core.RuntimeIdentity.matches(a, b)

internal fun nativeReaderId(value: String): String = if(value.matches(Regex("[a-f0-9]{64}"))) value else java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
