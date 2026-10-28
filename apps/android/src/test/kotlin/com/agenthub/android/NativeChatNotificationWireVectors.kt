package com.agenthub.android

import com.agenthub.core.TargetScope
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Runs the shipping parser and single-attempt transport against an owned loopback fixture, never a Host. */
fun main() { runBlocking<Unit> {
    var passed = 0
    fun test(name: String, action: () -> Unit) { action(); passed++; println("PASS $name") }
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime")
    val preferences = NativeChatNotificationPreferences(enabled = true, revision = "fixture-revision", generation = 3)
    val event = NativeChatNotificationEvent("a".repeat(64), target, NativeChatNoticeKind.COMPLETED, Instant.ofEpochMilli(1_793_180_000_000).toString(), "original-thread",
        threadTitle = "Synthetic title", taskSummary = "Synthetic summary", readTarget = target, readPosition = NativeChatNoticeReadPosition(9, "original-message", "b".repeat(64)))
    fun status() = JSONObject().put("version", 1).put("target", NativeChatNotificationCodec.target(target))
        .put("delivery", "foreground-only").put("state", "unavailable").put("reason", "手机尚未取得 APNs 令牌").put("revision", preferences.revision)
    fun feed() = JSONObject().put("events", JSONArray().put(NativeChatNotificationCodec.event(event))).put("cursor", event.id)
        .put("activeIds", JSONArray().put("c".repeat(64))).put("status", status())
    test("nil-token status remains foreground-only") { check(NativeChatNotificationWire.status(status(), target).foreground(preferences.revision)) }
    test("status revision is not a mutation receipt") { check(!NativeChatNotificationWire.status(status(), target).foreground("newer-revision")) }
    test("disabled status cannot publish") { check(!NativeChatNotificationWire.status(status().put("state", "disabled"), target).foreground(preferences.revision)) }
    test("foreign runtime rejected") { reject { NativeChatNotificationWire.status(status().put("target", NativeChatNotificationCodec.target(target.copy(runtimeId = "foreign"))), target) } }
    test("future status rejected") { reject { NativeChatNotificationWire.status(status().put("version", 2), target) } }
    test("coerced version rejected") { reject { NativeChatNotificationWire.status(status().put("version", "1"), target) } }
    test("unknown status field rejected") { reject { NativeChatNotificationWire.status(status().put("deviceToken", "fake"), target) } }
    test("null revision rejected") { reject { NativeChatNotificationWire.status(status().put("revision", JSONObject.NULL), target) } }
    test("malformed public key rejected") { reject { NativeChatNotificationWire.status(status().put("publicKey", "bad"), target) } }
    test("relay diagnostic secrets redacted") {
        val value = NativeChatNotificationWire.status(status().put("bindingId", "binding-fixture").put("managementToken", "management-fixture"), target)
        check(!value.toString().contains("fixture") && !value.foreground(preferences.revision))
    }
    test("APNs payload cannot become foreground delivery") { check(!NativeChatNotificationWire.status(status().put("delivery", "apns"), target).foreground(preferences.revision)) }
    test("Host key must be canonical raw X25519") {
        val public = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        check(NativeChatNotificationWire.key(public).size == 32); reject { NativeChatNotificationWire.key(public.trimEnd('=')) }
    }
    test("foreground registration omits vendor token and environment") {
        val registration = NativeChatNotificationWire.registration("android-fixture", Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }), target, preferences)
        check(registration.keys().asSequence().toSet() == setOf("version", "deviceId", "target", "publicKey", "preferences"))
    }
    test("prototype device rejected") { reject { NativeChatNotificationWire.device("__proto__") } }
    test("device path injection rejected") { reject { NativeChatNotificationWire.device("../foreign") } }
    test("original completed read version retained") {
        val decoded = NativeChatNotificationWire.feed(feed(), target, ""); check(decoded.events.single() == event && decoded.cursor == event.id)
    }
    test("unknown event operation rejected") { reject { NativeChatNotificationWire.feed(feed().put("events", JSONArray().put(NativeChatNotificationCodec.event(event).put("shell", "fixture"))), target, "") } }
    test("foreign event rejected") { reject { NativeChatNotificationWire.feed(feed().put("events", JSONArray().put(NativeChatNotificationCodec.event(event.copy(target = target.copy(hostId = "foreign"), readTarget = null, readPosition = null)))), target, "") } }
    test("duplicate events rejected") { reject { NativeChatNotificationWire.feed(feed().put("events", JSONArray().put(NativeChatNotificationCodec.event(event)).put(NativeChatNotificationCodec.event(event))), target, "") } }
    test("cursor cannot skip an unseen original event") { reject { NativeChatNotificationWire.feed(feed().put("cursor", "e".repeat(64)), target, "") } }
    test("empty feed retains exact prior cursor") { check(NativeChatNotificationWire.feed(feed().put("events", JSONArray()).put("cursor", "d".repeat(64)), target, "d".repeat(64)).cursor == "d".repeat(64)) }
    test("empty feed cannot erase prior cursor") { reject { NativeChatNotificationWire.feed(feed().put("events", JSONArray()).put("cursor", ""), target, "d".repeat(64)) } }
    test("duplicate active IDs rejected") { reject { NativeChatNotificationWire.feed(feed().put("activeIds", JSONArray().put(event.id).put(event.id)), target, "") } }
    test("non-hash active ID rejected") { reject { NativeChatNotificationWire.feed(feed().put("activeIds", JSONArray().put("other")), target, "") } }
    test("future feed field rejected") { reject { NativeChatNotificationWire.feed(feed().put("epoch", "unimplemented"), target, "") } }
    test("oversized event count rejected before delivery") {
        val items = JSONArray(); repeat(2049) { items.put(NativeChatNotificationCodec.event(event)) }; reject { NativeChatNotificationWire.feed(feed().put("events", items), target, "") }
    }
    test("semantic preferences fingerprint ignores map order") {
        check(NativeChatNotificationWire.preferenceHash(preferences.copy(categorySounds = linkedMapOf(NativeChatNoticeKind.PLAN to "none", NativeChatNoticeKind.APPROVAL to "default"))) ==
            NativeChatNotificationWire.preferenceHash(preferences.copy(categorySounds = linkedMapOf(NativeChatNoticeKind.APPROVAL to "default", NativeChatNoticeKind.PLAN to "none"))))
    }
    test("fingerprint binds exact reply version") { check(NativeChatNotificationWire.eventHash(event) != NativeChatNotificationWire.eventHash(event.copy(readPosition = event.readPosition!!.copy(revision = 10)))) }
    test("wire reader rejects oversized UTF-8 bytes") { reject { NativeWireReader.read(java.io.ByteArrayInputStream("测".repeat(100).toByteArray()), 100) { true } } }
    test("wire reader rejects malformed UTF-8") { reject { NativeWireReader.read(java.io.ByteArrayInputStream(byteArrayOf(0xc0.toByte(), 0x80.toByte())), 100) { true } } }
    test("wire reader rejects cancellation") { reject { NativeWireReader.read(java.io.ByteArrayInputStream("{}".toByteArray()), 100) { false } } }
    fun journalRecord(): JSONObject {
        val outcome = JSONObject().put("event", NativeChatNotificationCodec.event(event)).put("fingerprint", NativeChatNotificationWire.eventHash(event)).put("state", "processing").put("at", 1_793_180_000_000L)
        val batch = JSONObject().put("id", "3cc08e98-ef3e-4577-a609-d26f5cc77f91").put("before", "").put("after", event.id)
            .put("revision", preferences.revision).put("authorization", "f".repeat(64)).put("ids", JSONArray().put(event.id)).put("activeIds", JSONArray())
        val subscription = JSONObject().put("target", NativeChatNotificationCodec.target(target)).put("authorization", "f".repeat(64))
            .put("preferences", NativeChatNotificationCodec.preferences(preferences)).put("revoked", false).put("cursor", "")
            .put("mutations", JSONArray()).put("batch", batch).put("outcomes", JSONArray().put(outcome))
        return JSONObject().put("version", 1).put("deviceId", "android-fixture").put("identity", "e".repeat(64)).put("subscriptions", JSONArray().put(subscription))
    }
    test("shipping journal decoder accepts pending original batch") { NativeChatNotificationForegroundStore.validateEncoded(journalRecord().toString()) }
    test("journal future version is unavailable") { reject { NativeChatNotificationForegroundStore.validateEncoded(journalRecord().put("version", 2).toString()) } }
    test("journal never accepts an old batch as a new preference stream") {
        val raw = journalRecord(); raw.getJSONArray("subscriptions").getJSONObject(0).getJSONObject("batch").put("revision", "newer-revision")
        reject { NativeChatNotificationForegroundStore.validateEncoded(raw.toString()) }
    }
    test("journal cursor cannot pass an original without durable processing record") {
        val raw = journalRecord(); raw.getJSONArray("subscriptions").getJSONObject(0).put("outcomes", JSONArray())
        reject { NativeChatNotificationForegroundStore.validateEncoded(raw.toString()) }
    }
    test("journal rejects changed original event bytes") {
        val raw = journalRecord(); raw.getJSONArray("subscriptions").getJSONObject(0).getJSONArray("outcomes").getJSONObject(0).getJSONObject("event").put("taskSummary", "Different original")
        reject { NativeChatNotificationForegroundStore.validateEncoded(raw.toString()) }
    }
    test("journal rejects foreign target outcome") {
        val raw = journalRecord(); raw.getJSONArray("subscriptions").getJSONObject(0).put("target", NativeChatNotificationCodec.target(target.copy(hostId = "foreign")))
        reject { NativeChatNotificationForegroundStore.validateEncoded(raw.toString()) }
    }
    test("journal rejects duplicate original receipts") {
        val raw = journalRecord(); val outcomes = raw.getJSONArray("subscriptions").getJSONObject(0).getJSONArray("outcomes"); outcomes.put(JSONObject(outcomes.getJSONObject(0).toString()))
        reject { NativeChatNotificationForegroundStore.validateEncoded(raw.toString()) }
    }
    val requests = AtomicInteger(); var failNext = false
    val token = "fixture-credential-" + "x".repeat(32)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/notifications/") { exchange ->
        requests.incrementAndGet()
        try {
            check(exchange.requestHeaders.getFirst("Authorization") == "Bearer $token")
            val query = exchange.requestURI.rawQuery.split('&').associate { part -> val pieces = part.split('=', limit = 2); URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces[1], "UTF-8") }
            check(query["hostId"] == target.hostId && query["deviceId"] == "android-fixture")
            val wireTarget = JSONObject(requireNotNull(query["target"])); check(NativeChatNotificationCodec.target(wireTarget) == target && !wireTarget.has("account"))
            val operation = exchange.requestURI.path.substringAfterLast('/')
            check(operation in setOf("register", "preferences", "unregister", "status", "events"))
            if (exchange.requestMethod == "POST") {
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                check(body.getString("hostId") == target.hostId)
                if (operation == "register") {
                    check(!body.getJSONObject("registration").has("deviceToken")); check(!body.getJSONObject("registration").has("environment"))
                }
            } else check(operation in setOf("status", "events"))
            val response = when (operation) {
                "events" -> { check(query["after"] == "d".repeat(64)); feed() }
                "unregister" -> status().put("state", "disabled")
                else -> status()
            }.toString().toByteArray()
            val code = if (failNext) 503 else 200; failNext = false
            exchange.sendResponseHeaders(code, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        } finally { exchange.close() }
    }
    server.start()
    try {
        val client = MobileClient(target.hostId, "Fixture computer", TargetScope("codex", "legacy-account", "legacy-subject", target.hostId, target.workspaceId, target.runtimeId), "http://127.0.0.1:${server.address.port}")
        val snapshot = NativeChatNotificationSnapshot(client, token, "epoch-fixture")
        val commands = NativeChatNotificationHttp()
        check(commands.status(snapshot, "android-fixture").foreground(preferences.revision)); passed++; println("PASS actual fixed status HTTP + captured authorization")
        check(commands.preferences(snapshot, "android-fixture", preferences).revision == preferences.revision); passed++; println("PASS actual fixed preferences HTTP")
        check(commands.unregister(snapshot, "android-fixture").state == "disabled"); passed++; println("PASS actual fixed unregister HTTP")
        check(commands.events(snapshot, "android-fixture", "d".repeat(64)).events.single() == event); passed++; println("PASS actual fixed feed HTTP + exact after/target")
        val before = requests.get(); failNext = true
        check(runCatching { commands.preferences(snapshot, "android-fixture", preferences) }.isFailure)
        check(requests.get() == before + 1); passed++; println("PASS failed mutation has exactly one HTTP attempt")
        test("snapshot redacts credential and ignores account/path metadata in authority") {
            val alternate = NativeChatNotificationSnapshot(client.copy(scope = TargetScope("codex", "another-account", "another-subject", target.hostId, target.workspaceId, target.runtimeId), endpoint = "http://localhost:${server.address.port}"), token, "epoch-fixture")
            check(snapshot.authorizationFingerprint == alternate.authorizationFingerprint && !snapshot.toString().contains(token))
        }
        test("snapshot isolates credential and epoch rotation") {
            check(snapshot.authorizationFingerprint != NativeChatNotificationSnapshot(client, token + "z", "epoch-fixture").authorizationFingerprint)
            check(snapshot.authorizationFingerprint != NativeChatNotificationSnapshot(client, token, "another-epoch").authorizationFingerprint)
        }
    } finally { server.stop(0) }
    println("Shipping notification wire/HTTP vectors: $passed passed; synthetic loopback only, no native/Host/model/OS proof")
    exitProcess(0)
} }
