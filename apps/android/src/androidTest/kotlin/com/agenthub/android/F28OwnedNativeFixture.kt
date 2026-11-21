package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fixed synthetic namespace only. No default reader, Activity, picker, child store, issuer or discovery. */
internal class F28OwnedNativeFixture {
    private val i = InstrumentationRegistry.getInstrumentation()
    val namespace = "p2-f28-" + UUID.randomUUID()
    private val root = File(i.targetContext.noBackupFilesDir, namespace)
    init {
        check(InstrumentationRegistry.getArguments().getString("p2F28Boundaries") == "1")
        check(i.targetContext.packageName == "com.easyai.agenthub.android.acceptance" &&
            i.context.packageName == "com.easyai.agenthub.android.acceptance.test")
        check(!root.exists() && !Files.isSymbolicLink(root.toPath()))
        check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(namespace))
    }
    val store = NativeSessionStorage(i.targetContext, namespace, namespace)
    val scope = TargetScope("codex", null, null, "f28-owned-host", "f28-owned-workspace", "desktop-runtime")
    val first = "f28-first"
    val other = "f28-other"
    @Volatile var reportedScope = scope
    @Volatile var supported = true
    @Volatile var runtimeConnected: Boolean? = true
    val owners = ConcurrentHashMap<String, Boolean>().apply { put(first, true); put(other, false) }
    val historyReads = AtomicInteger()
    @Volatile var beforeHistory: suspend (String) -> Unit = {}
    var session: MobileSession? = null
    val reads = object : HostReads {
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?) =
            HostReadCodec.workspace(JSONObject().put("hostId", scope.hostId).put("name", "Owned F28 fixture")
                .put("connectionMode", "desktop-runtime").put("readTarget", CodexCommandCodec.target(reportedScope))
                .put("agentConnectionState", JSONObject().put("runtimeConnected", runtimeConnected))
                .put("projects", JSONArray()).put("threads", JSONArray(listOf(first, other).map {
                    JSONObject().put("id", it).put("hostId", scope.hostId).put("title", it).put("state", "idle").put("archived", false)
                })).put("capabilities", JSONArray(listOf("reply", "commands:target-bound:v1") + if (supported) listOf("desktop:open") else emptyList())).toString())
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
            beforeHistory(threadId)
            historyReads.incrementAndGet()
            return HostReadCodec.history(JSONObject().put("hostId", scope.hostId).put("threadId", threadId)
                .put("readTarget", CodexCommandCodec.target(reportedScope)).put("messages", JSONArray())
                .put("runtime", JSONObject().put("state", "idle").put("turnId", JSONObject.NULL).put("plan", JSONArray())
                    .put("error", JSONObject.NULL).put("approvals", JSONArray()).put("receipts", JSONArray())
                    .put("canControl", owners[threadId] == true)).toString())
        }
    }
    fun client(endpoint: String) = MobileClient(scope.hostId, "Owned F28", scope, endpoint, true, "desktop-runtime", false, requiresReadTarget = true)
    fun commands() = object : AgentCommands by NativeCodexCommands({ "" }) {
        override suspend fun models(client: MobileClient) = emptyList<CodexModel>()
    }
    fun flow() = NativeCodexFlow(store, reads, commands())
    suspend fun readySession(endpoint: String): MobileSession {
        val value = withContext(Dispatchers.Main) {
            MobileSession(store, reads, commands()).also { it.resetAcceptance(reads, commands()) }
        }
        session = value
        withContext(Dispatchers.Main) {
            value.connect("Owned F28", endpoint, "")
            value.selectThread(value.threads.single { it.id == first })
        }
        eventually { !value.historyLoading && value.currentChat?.id == first && value.runtime != null }
        return value
    }
    suspend fun eventually(condition: () -> Boolean) = withTimeout(5000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(10)
    }
    fun busyTargetsEmpty(value: MobileSession): Boolean =
        (MobileSession::class.java.getDeclaredField("busyTargets").apply { isAccessible = true }.get(value) as Map<*, *>).isEmpty()
    suspend fun close() {
        session?.let { owned ->
            withContext(Dispatchers.Main) { owned.leaveForeground() }
            // Observe and normally cancel/join ONLY this synthetic Session's own scope.
            // No function replacement, global job, process stop or private state mutation.
            val tasks = MobileSession::class.java.getDeclaredField("tasks").apply { isAccessible = true }.get(owned) as CoroutineScope
            withTimeout(5000) { checkNotNull(tasks.coroutineContext[Job]).cancelAndJoin() }
        }
        store.awaitQueuedWrites()
        store.retireWriter()
        // On a failed join/flush the function exits before deleting evidence or keys.
        check(root.name == namespace && namespace.startsWith("p2-f28-") && root.parentFile.canonicalFile == i.targetContext.noBackupFilesDir.canonicalFile)
        check(!Files.isSymbolicLink(root.toPath()))
        Files.walk(root.toPath()).use { stream ->
            val paths = stream.toArray().map { it as java.nio.file.Path }
            check(paths.all { !Files.isSymbolicLink(it) })
            paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(namespace) }
        assertFalse(root.exists())
    }
}

internal fun f28OpenReceipt(id: String, phase: String = "opened", error: String? = null) = JSONObject()
    .put("hostId", "f28-owned-host").put("threadId", "f28-first").put("clientOperationId", id)
    .put("phase", phase).put("error", error ?: JSONObject.NULL)
internal fun f28Operation(request: WireRequest): String = if (request.method == "POST") JSONObject(String(request.body)).getString("clientOperationId")
    else java.net.URI(request.pathWithQuery).rawQuery.split('&').single { it.startsWith("id=") }.substringAfter('=').let { java.net.URLDecoder.decode(it, "UTF-8") }
internal fun f28OnlyNavigation(requests: List<WireRequest>, posts: Int) {
    assertEquals(posts, requests.count { it.method == "POST" })
    assertTrue(requests.isNotEmpty())
    requests.forEach {
        val query = java.net.URI(it.pathWithQuery).rawQuery.split('&').associate { pair ->
            java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        assertEquals("f28-owned-host", query["hostId"])
        val target = checkNotNull(HostReadCodec.target(JSONObject(checkNotNull(query["target"]))))
        assertEquals("codex", target.agentId); assertEquals("f28-owned-host", target.hostId)
        assertEquals("f28-owned-workspace", target.workspaceId); assertEquals("desktop-runtime", target.runtimeId)
        assertTrue(it.method == "POST" && it.path == "/v1/desktop/open" || it.method == "GET" && it.path == "/v1/desktop/open-status")
        if (it.method == "POST") assertEquals(setOf("hostId", "threadId", "clientOperationId"), JSONObject(String(it.body)).keys().asSequence().toSet())
    }
}
