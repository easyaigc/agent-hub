package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.time.Instant
import java.util.UUID

/** Compiled native auxiliary scenarios use real isolated Keystore/AtomicFile; execution is parent-lease only. */
private class RecoveryNativeFixture(context: android.content.Context, namespace: String) {
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime")
    val storage = NativeSessionStorage(context, namespace)
    val notices = NativeChatNotificationStore(storage)
    val legacy = NativeChatNotificationForegroundStore(storage)
    val journal = NativeChatNotificationRecoveryStore(storage)
    val now = 1_793_180_000_000L
    var epoch = UUID.randomUUID().toString()
    var current = true
    var supported = true
    var loseAck = false
    var saveReceipt = true
    var stage = "applied"
    var absent = false
    var gap = false
    var expired = false
    var pageSize = 32
    var badSnapshotHash = false
    var rawFeed: ((JSONObject) -> JSONObject)? = null
    val submitted = mutableListOf<NativeChatRecoveryIntent>()
    val queried = mutableListOf<NativeChatRecoveryIntent>()
    val queries = mutableListOf<NativeChatRecoveryQuery>()
    val receipts = mutableMapOf<String, NativeChatRecoveryReceipt>()
    val events = mutableListOf<NativeChatNotificationEvent>()
    val disposition = mutableMapOf<String, String>()
    var active = emptySet<String>()
    val delivered = mutableListOf<String>()
    val activeSets = mutableListOf<Set<String>>()
    var onReceive: (suspend (NativeChatNotificationEvent) -> Unit)? = null
    var failReceive = false
    var capabilityCalls = 0
    var cancellations = 0
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
    val commands = object : NativeChatNotificationRecoveryCommands {
        override suspend fun recoveryCapabilities(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatRecoveryCapabilities? {
            capabilityCalls++; return if (supported) NativeChatRecoveryCapabilities(epoch) else null
        }
        override suspend fun recoverySubmit(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt {
            submitted.add(original)
            val saved = journal.view(identity(), target).operations.single { it.original.operationId == original.operationId }
            check(saved.state == "unknown" && saved.original.encoded() == original.encoded())
            val receipt = receipt(original); if (saveReceipt) receipts[original.operationId] = receipt
            if (loseAck) error("Fixture lost acknowledgement")
            return receipt
        }
        override suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt? {
            queried.add(original); return if (absent) null else receipts[original.operationId]
        }
        override suspend fun recoveryFeed(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery, reset: NativeChatRecoverySnapshot?): NativeChatRecoveryFeed {
            queries.add(query); val raw = page(original, query, reset)
            return NativeChatRecoveryWire.feed(rawFeed?.invoke(raw) ?: raw, query, original, reset)
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

class NativeChatNotificationRecoveryTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var namespace: String
    private lateinit var f: RecoveryNativeFixture
    @Before fun prepare() = runBlocking {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        namespace = "P9-recovery-v2-${UUID.randomUUID()}"
        f = withContext(Dispatchers.IO) { RecoveryNativeFixture(context, namespace) }
    }
    @After fun cleanup() { if (::namespace.isInitialized) java.io.File(context.noBackupFilesDir, namespace).deleteRecursively() }
    private fun scenario(action: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { action() } }
    @Test fun frozenCrossLanguageCanonicalVectors() = scenario {
        val vectors = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("notification-recovery-v2.json").bufferedReader().use { it.readText() }).getJSONArray("vectors")
        for (index in 0 until vectors.length()) { val v=vectors.getJSONObject(index); check(NativeChatRecoveryIntent.parse(v.getJSONObject("intent")).fingerprint==v.getString("expectedFingerprint")) }
    }
    @Test fun lostAckColdRestartQueriesOriginalOnly() = scenario {
        f.loseAck=true; f.sync(); val original=f.submitted.single(); f.loseAck=false; f.sync()
        check(f.submitted.size==1 && f.queried.all{it.encoded()==original.encoded()})
        val reopened=NativeSessionStorage(context,namespace); val view=NativeChatNotificationRecoveryStore(reopened).view(NativeChatNotificationIdentity.load(reopened),f.target)
        check(view.operations.single().state=="applied")
    }
    @Test fun journalIdentityAndOriginalRemainEncrypted() = scenario {
        f.loseAck=true; f.sync(); val original=f.submitted.single()
        val bytes=java.io.File(context.noBackupFilesDir,namespace).walkTopDown().filter{it.isFile}.map{it.readBytes().toString(Charsets.ISO_8859_1)}.toList()
        check(bytes.isNotEmpty() && bytes.none{it.contains(original.operationId)||it.contains(original.publicKey)||it.contains("fixture-token")})
    }
    @Test fun absentDoesNotAuthorizeNewWrite() = scenario {
        f.loseAck=true; f.saveReceipt=false; f.sync(); f.notices.update{it.copy(soundEnabled=false)}
        repeat(2){f.sync()}; check(f.submitted.size==1 && f.view().operations.single().state=="unknown")
    }
    @Test fun separateOffPreservesUnknownV1Record() = scenario {
        f.legacy.beginMutation(f.identity(),f.snapshot(),"register",f.notices.preferences(),f.now)
        val raw=f.storage.read(NativeChatNotificationForegroundStore.RECORD); f.notices.update{it.copy(enabled=false)}
        check(f.off().state=="disabled" && f.storage.read(NativeChatNotificationForegroundStore.RECORD)==raw)
    }
    @Test fun duplicateHumanOffUsesOneOriginalId() = scenario {
        f.notices.update{it.copy(enabled=false)}; val id=UUID.randomUUID().toString(); f.off(action=id); f.off(action=id)
        check(f.submitted.size==1 && f.queried.single().operationId==id)
    }
    @Test fun oldCredentialAndNewEpochCannotResubmit() = scenario {
        f.loseAck=true; f.sync(); check(f.sync(snapshot=f.snapshot(credentialEpoch="rotated"))?.state=="original-credential-recovery-required")
        f.epoch=UUID.randomUUID().toString(); check(f.sync()?.state=="epoch-recovery-required" && f.submitted.size==1)
    }
    @Test fun actionableStaleWaitsWithoutAdvancing() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="stale";f.sync()
        check(f.view().after==0L && f.view().outcomes.single().state=="waiting-host" && f.delivered.isEmpty())
    }
    @Test fun restoredOwnerRefreshesOnceWithFreshActiveSet() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="stale";f.sync();f.disposition[e.id]="eligible";f.active=setOf(e.id);f.sync();f.sync()
        check(f.delivered==listOf(e.id)&&f.activeSets.first()==setOf(e.id))
    }
    @Test fun filteredOriginalStillUsesP7Port() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="filtered";f.sync();check(f.delivered==listOf(e.id))
    }
    @Test fun interruptedPortOutcomeIsNeverReplayed() = scenario {
        f.events.add(f.event());f.failReceive=true;check(runCatching{f.sync()}.isFailure);f.failReceive=false;f.sync()
        check(f.delivered.size==1&&f.view().outcomes.single().state=="unknown-interrupted-no-replay")
    }
    @Test fun paginationKeepsCapturedHighWater() = scenario {
        f.pageSize=1;f.events.addAll(listOf(f.event(1),f.event(2)));f.sync();f.events.add(f.event(3));f.sync()
        check(f.queries.last().through==2L&&f.view().after==2L)
    }
    @Test fun acceptedGapPagedResetKeepsOldCursorUntilComplete() = scenario {
        f.events.addAll(listOf(f.event(1),f.event(2)));f.pageSize=1;f.gap=true;val offer=requireNotNull(f.sync()?.gap)
        val action=UUID.randomUUID().toString();f.controller().acceptGap(f.snapshot(),offer,action){true};f.sync()
        check(f.view().after==0L&&f.view().resetAfter==1L);f.sync();check(f.view().after==2L&&f.view().resetId==null)
    }
    @Test fun snapshotHashMismatchDoesNotPublishBaseline() = scenario {
        f.events.add(f.event());f.gap=true;f.badSnapshotHash=true;val offer=requireNotNull(f.sync()?.gap)
        f.controller().acceptGap(f.snapshot(),offer,UUID.randomUUID().toString()){true};check(runCatching{f.sync()}.isFailure);check(f.view().after==0L)
    }
    @Test fun unknownSchemaIsPreservedAndNotActivated() = scenario {
        f.sync();val raw=JSONObject(requireNotNull(f.storage.read(NativeChatNotificationRecoveryStore.RECORD))).put("version",99).toString()
        f.storage.write(NativeChatNotificationRecoveryStore.RECORD,raw);check(runCatching{f.sync()}.isFailure);check(f.storage.read(NativeChatNotificationRecoveryStore.RECORD)==raw)
    }
}
