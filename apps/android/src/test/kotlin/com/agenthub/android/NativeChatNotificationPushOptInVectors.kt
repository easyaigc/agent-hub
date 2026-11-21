package com.agenthub.android

import com.agenthub.core.TargetScope
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Owned loopback HTTP fixture only. Checks the shipping GET builder/fallback, never a real Host or provider. */
fun main() = runBlocking<Unit> {
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "fixture-runtime")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val opted = AtomicInteger(); val legacy = AtomicInteger(); val posts = AtomicInteger()
    val capabilities = JSONObject().put("version", 2).put("epoch", "4bec2232-d100-4691-915b-87c5356ab783")
        .put("target", NativeChatNotificationCodec.target(target)).put("operations", JSONArray(listOf("register", "preferences", "revoke", "feed-reset")))
        .put("limits", JSONObject(NativeChatRecoveryWire.limits))
    server.createContext("/v1/notifications/recovery-v2/capabilities") { exchange ->
        if (exchange.requestMethod != "GET") posts.incrementAndGet()
        val query = exchange.requestURI.rawQuery.split('&').filter { it.startsWith("pushProviders=") }
        val code: Int
        val body: String
        if (query.isEmpty()) { legacy.incrementAndGet(); code = 200; body = capabilities.toString() }
        else { check(query == listOf("pushProviders=1")); opted.incrementAndGet(); code = 400; body = "{}" }
        val bytes = body.toByteArray(); exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    server.start()
    try {
        val scope = TargetScope("codex", null, null, target.hostId, target.workspaceId, target.runtimeId)
        val snapshot = NativeChatNotificationSnapshot(MobileClient(target.hostId, "Fixture", scope, "http://127.0.0.1:${server.address.port}"),
            "fixture-token-" + "x".repeat(32), "fixture-epoch")
        val transport = NativeChatNotificationRecoveryHttp()
        check(transport.recoveryPushCapabilities(snapshot, "android-fixture") == null)
        check(transport.recoveryCapabilities(snapshot, "android-fixture")?.pushProviders?.isEmpty() == true)
        check(opted.get() == 1 && legacy.get() == 1 && posts.get() == 0)
        check(!snapshot.recoveryUrl(NativeChatRecoveryCall.CAPABILITIES, "android-fixture").query.contains("pushProviders"))
        check(snapshot.recoveryUrl(NativeChatRecoveryCall.PUSH_CAPABILITIES, "android-fixture").query.split('&').count { it == "pushProviders=1" } == 1)
        println("PASS explicit single opt-in GET / old Host 400 fallback / unchanged legacy GET / zero mutation")
    } finally { server.stop(0) }
}
