package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Same command fixture for native scenarios and the JVM Main-to-IO regression. No Host/OS access. */
internal class RecoveryNativeFixture(val storage: NativeSessionStorage) {
    constructor(context: android.content.Context, namespace: String) : this(NativeSessionStorage(context, namespace))
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime")
    val notices = NativeChatNotificationStore(storage)
    val legacy = NativeChatNotificationForegroundStore(storage)
    val journal = NativeChatNotificationRecoveryStore(storage)
    val now = 1_793_180_000_000L
    @Volatile var epoch = UUID.randomUUID().toString()
    @Volatile var current = true
    @Volatile var supported = true
    @Volatile var loseAck = false
    @Volatile var saveReceipt = true
    @Volatile var stage = "applied"
    @Volatile var absent = false
    @Volatile var gap = false
    @Volatile var expired = false
    @Volatile var pageSize = 32
    @Volatile var badSnapshotHash = false
    @Volatile var rawFeed: ((JSONObject) -> JSONObject)? = null
    val submitted = CopyOnWriteArrayList<NativeChatRecoveryIntent>()
    val queried = CopyOnWriteArrayList<NativeChatRecoveryIntent>()
    val queries = CopyOnWriteArrayList<NativeChatRecoveryQuery>()
    val receipts = ConcurrentHashMap<String, NativeChatRecoveryReceipt>()
    val events = CopyOnWriteArrayList<NativeChatNotificationEvent>()
    val disposition = ConcurrentHashMap<String, String>()
    @Volatile var active = emptySet<String>()
    val delivered = mutableListOf<String>()
    val activeSets = mutableListOf<Set<String>>()
    @Volatile var onReceive: (suspend (NativeChatNotificationEvent) -> Unit)? = null
    @Volatile var failReceive = false
    @Volatile var capabilityCalls = 0
    @Volatile var cancellations = 0
    init { notices.update { it.copy(enabled = true) } }
    fun snapshot(token: String = "fixture-token-" + "x".repeat(32), credentialEpoch: String = "fixture-credential") = NativeChatNotificationSnapshot(
        MobileClient(target.hostId, "Fixture", TargetScope("codex", null, null, target.hostId, target.workspaceId, target.runtimeId), "http://127.0.0.1:8765"), token, credentialEpoch)
    fun event(index: Int = 1, kind: NativeChatNoticeKind = NativeChatNoticeKind.APPROVAL) = NativeChatNotificationEvent(
        index.toString(16).padStart(64, '0'), target, kind, Instant.ofEpochMilli(now).toString(), "fixture-thread-$index")
    suspend fun identity() = NativeChatNotificationIdentity.load(storage)
    suspend fun view() = journal.view(identity(), target)
    fun receipt(original: NativeChatRecoveryIntent, state: String = stage): NativeChatRecoveryReceipt {
        val value = JSONObject().put("version", 2).put("epoch", original.epoch).put("intent", original.json()).put("stage", state)
        if (state == "rejected") value.put("reason", "fixture-rejected")
        if (state == "applied") {
            if (original.kind == "feed-reset") value.put("snapshot", JSONObject().put("id", original.operationId)
                .put("hash", if (badSnapshotHash) "f".repeat(64) else NativeChatRecoveryCanonical.hash(JSONArray(events.map(NativeChatNotificationCodec::event))))
                .put("count", events.size).put("baseline", events.size).put("expiresAt", now + 300_000))
            else value.put("status", JSONObject().put("version", 1).put("target", NativeChatNotificationCodec.target(target))
                .put("delivery", "foreground-only").put("state", if (original.kind == "revoke") "disabled" else "unavailable").put("revision", original.preferences.revision))
        }
        return NativeChatRecoveryWire.receipt(value, original)
    }
    fun page(original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery, reset: NativeChatRecoverySnapshot?): JSONObject {
        val common = JSONObject().put("version", 2).put("epoch", epoch).put("target", NativeChatNotificationCodec.target(target))
            .put("deviceId", original.deviceId).put("generation", query.generation)
        if (query.epoch != epoch || gap && query.resetId == null || expired && query.resetId != null)
            return common.put("kind", "reset-required").put("reason", if (query.epoch != epoch) "epoch-mismatch" else if (query.resetId != null) "snapshot-expired-or-missing" else "retention-gap")
                .put("floor", 2).put("head", events.size.coerceAtLeast(1))
        val through = query.through ?: events.size.toLong()
        val next = minOf(through, query.after + pageSize)
        val items = JSONArray()
        for (seq in query.after + 1..next) {
            val event = events[(seq - 1).toInt()]; val wire = NativeChatNotificationCodec.event(event)
            items.put(JSONObject().put("sequence", seq).put("event", wire).put("fingerprint", NativeChatRecoveryCanonical.hash(wire))
                .put("disposition", disposition[event.id] ?: "eligible"))
        }
        return common.put("kind", "page").put("mode", if (reset != null) "reset" else "live")
            .put("resetId", reset?.id).put("snapshotHash", reset?.hash).put("baseline", reset?.baseline ?: through)
            .put("after", query.after).put("next", next).put("through", through).put("hasMore", next < through)
            .put("items", items).put("activeIds", JSONArray(active.toList())).put("publicKey", original.publicKey)
            .put("preferencesFingerprint", NativeChatRecoveryCanonical.hash(original.json().getJSONObject("preferences")))
    }
    // The shipping HTTP transport owns its IO switch; this fake must keep the same caller contract.
    val commands = object : NativeChatNotificationRecoveryCommands {
        override suspend fun recoveryCapabilities(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatRecoveryCapabilities? = withContext(Dispatchers.IO) {
            capabilityCalls++; if (supported) NativeChatRecoveryCapabilities(epoch) else null
        }
        override suspend fun recoverySubmit(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt = withContext(Dispatchers.IO) {
            submitted.add(original)
            val saved = journal.view(identity(), target).operations.single { it.original.operationId == original.operationId }
            check(saved.state == "unknown" && saved.original.encoded() == original.encoded())
            val receipt = receipt(original); if (saveReceipt) receipts[original.operationId] = receipt
            if (loseAck) error("Fixture lost acknowledgement")
            receipt
        }
        override suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt? = withContext(Dispatchers.IO) {
            queried.add(original); if (absent) null else receipts[original.operationId]
        }
        override suspend fun recoveryFeed(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery, reset: NativeChatRecoverySnapshot?): NativeChatRecoveryFeed = withContext(Dispatchers.IO) {
            queries.add(query); val raw = page(original, query, reset)
            NativeChatRecoveryWire.feed(rawFeed?.invoke(raw) ?: raw, query, original, reset)
        }
    }
    val delivery = object : NativeChatNotificationForegroundPort {
        override suspend fun receive(event: NativeChatNotificationEvent, lease: NativeChatNoticeLease, computer: String, current: () -> Boolean): String {
            check(current()); delivered.add(event.id); onReceive?.invoke(event)
            if (failReceive) error("Fixture uncertain reading result")
            return "suppressed"
        }
        override suspend fun reconcileActive(target: NativeChatNoticeTarget, activeIds: Set<String>, current: () -> Boolean) { check(current()); activeSets.add(activeIds) }
        override suspend fun cancel(target: NativeChatNoticeTarget) { cancellations++ }
    }
    fun controller() = NativeChatNotificationRecoveryController(storage, notices, legacy, commands, delivery) { now }
    suspend fun sync(controller: NativeChatNotificationRecoveryController = controller(), snapshot: NativeChatNotificationSnapshot = snapshot()) = controller.synchronize(snapshot) { current }
    suspend fun off(controller: NativeChatNotificationRecoveryController = controller(), action: String = UUID.randomUUID().toString()): NativeChatNotificationMonitorReport {
        val p = notices.preferences(); return controller.explicitDisable(snapshot(), p.revision, action) { current }
    }
}

