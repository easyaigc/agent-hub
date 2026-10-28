package com.agenthub.android

import com.agenthub.core.TargetScope
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

/** Shipping Kotlin source with JVM-only memory/Looper ports. No Android/Keystore/Host/device proof. */
private class RecoveryFixture {
    val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime")
    val storage = NativeSessionStorage()
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

fun main() { runBlocking<Unit> {
    var passed = 0
    suspend fun test(name: String, action: suspend () -> Unit) { action(); passed++; println("PASS $name") }
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    val frozen = JSONObject(File("packages/protocol/fixtures/mobile/notification-recovery-v2.json").readText()).getJSONArray("vectors")
    for (index in 0 until frozen.length()) test("frozen canonical vector $index") {
        val v = frozen.getJSONObject(index); check(NativeChatRecoveryIntent.parse(v.getJSONObject("intent")).fingerprint == v.getString("expectedFingerprint"))
    }
    test("minimal Unicode rejects lone surrogate and floating number") {
        reject { NativeChatRecoveryCanonical.value("\ud800") }; reject { NativeChatRecoveryCanonical.value(1.0) }
        check(NativeChatRecoveryCanonical.value("</\u2028😀") == "\"</\u2028😀\"")
    }
    test("unknown future intent is not accepted") { val raw = frozen.getJSONObject(0).getJSONObject("intent"); reject { NativeChatRecoveryIntent.parse(JSONObject(raw.toString()).put("version", 3)) } }
    test("lost acknowledgement cold restart only queries exact immutable original") {
        val f = RecoveryFixture(); f.loseAck = true
        check(f.sync()?.state == "mutation-unknown-original-query"); val original = f.submitted.single()
        val before = f.view().operations.single().original.encoded(); f.loseAck = false
        check(f.sync()?.state == "foreground-synchronized"); check(f.submitted.size == 1)
        check(f.queried.all { it.encoded() == original.encoded() }); check(f.view().operations.single().original.encoded() == before)
    }
    test("absent receipt and new preference cannot allocate another ID") {
        val f = RecoveryFixture(); f.loseAck = true; f.saveReceipt = false; f.sync()
        val original = f.submitted.single(); f.notices.update { it.copy(soundEnabled = false) }
        repeat(3) { check(f.sync()?.state == "mutation-unknown-original-query") }
        check(f.submitted.size == 1 && f.queried.all { it.encoded() == original.encoded() }); check(f.view().operations.single().state == "unknown")
    }
    test("reserved receipt stays unknown and is never reposted") {
        val f = RecoveryFixture(); f.stage = "reserved"; f.sync(); repeat(2) { f.sync() }
        check(f.submitted.size == 1 && f.view().operations.single().state == "reserved")
        val original = f.submitted.single(); f.receipts[original.operationId] = f.receipt(original, "applied"); check(f.sync()?.state == "foreground-synchronized")
    }
    test("changed credential cannot query or write under old authority") {
        val f = RecoveryFixture(); f.loseAck = true; f.sync(); f.queried.clear()
        check(f.sync(snapshot = f.snapshot(credentialEpoch = "new-credential"))?.state == "original-credential-recovery-required")
        check(f.queried.isEmpty() && f.submitted.size == 1)
    }
    test("unknown v1 intent blocks adoption without capability inference") {
        val f = RecoveryFixture(); val id = f.identity(); f.legacy.beginMutation(id, f.snapshot(), "register", f.notices.preferences(), f.now)
        val before = f.storage.read(NativeChatNotificationForegroundStore.RECORD)
        check(f.sync() == null); check(f.submitted.isEmpty() && f.capabilityCalls == 0)
        check(f.storage.read(NativeChatNotificationForegroundStore.RECORD) == before)
    }
    test("explicit off fences absent registration but preserves v1 unknown bytes") {
        val f = RecoveryFixture(); f.legacy.beginMutation(f.identity(), f.snapshot(), "register", f.notices.preferences(), f.now)
        val before = f.storage.read(NativeChatNotificationForegroundStore.RECORD); f.notices.update { it.copy(enabled = false) }
        val report = f.off(); check(report.state == "disabled" && report.unconfirmedOperations == 1)
        check(f.submitted.single().kind == "revoke" && f.view().applied?.original?.generation == 1L)
        check(f.storage.read(NativeChatNotificationForegroundStore.RECORD) == before)
    }
    test("global human action derives stable separate IDs per full target") {
        val f=RecoveryFixture();val id=UUID.randomUUID().toString()
        val first=NativeChatNotificationRecoveryController.targetActionId(id,f.target)
        check(first==NativeChatNotificationRecoveryController.targetActionId(id,f.target))
        check(first!=NativeChatNotificationRecoveryController.targetActionId(id,f.target.copy(runtimeId="other-runtime")))
        NativeChatNotificationWire.uuid(first)
    }
    test("one explicit off action UUID survives duplicate callback without POST") {
        val f = RecoveryFixture(); f.notices.update { it.copy(enabled = false) }; val action = UUID.randomUUID().toString()
        f.off(action = action); f.off(action = action); check(f.submitted.size == 1 && f.queried.single().operationId == action)
    }
    test("new known off fences older unknown without rewriting it; new on advances generation") {
        val f = RecoveryFixture(); f.loseAck = true; f.saveReceipt = false; f.sync(); val first = f.view().operations.single().original
        f.loseAck = false; f.saveReceipt = true; f.notices.update { it.copy(enabled = false) }; f.off()
        f.notices.update { it.copy(enabled = true) }; check(f.sync()?.state == "foreground-synchronized")
        val view = f.view(); check(view.operations.first().state == "unknown" && view.operations.first().original.encoded() == first.encoded())
        check(view.applied?.original?.generation == 3L && f.submitted.map { it.kind } == listOf("register", "revoke", "register"))
    }
    test("rejected same desired settings never automatically retry new ID") {
        val f = RecoveryFixture(); f.stage = "rejected"; f.sync(); repeat(2) { check(f.sync()?.state == "subscription-rejected") }; check(f.submitted.size == 1)
    }
    test("unsupported capability falls back only before any attempt") {
        val f = RecoveryFixture(); f.supported = false; check(f.sync() == null); check(f.submitted.isEmpty())
        f.supported = true; f.loseAck = true; f.sync(); f.supported = false; check(f.sync()?.state == "recovery-unavailable"); check(f.submitted.size == 1)
    }
    test("new Host epoch never silently reenrolls or resets original cursor") {
        val f = RecoveryFixture(); f.sync(); val before = f.view().after; f.epoch = UUID.randomUUID().toString()
        check(f.sync()?.state == "epoch-recovery-required"); check(f.submitted.size == 1 && f.view().after == before)
    }
    test("all original page outcomes are durable before first port call") {
        val f = RecoveryFixture(); f.events.addAll(listOf(f.event(1), f.event(2))); f.onReceive = {
            val view = f.view(); check(view.after == 0L && view.pending?.page?.items?.size == 2 && view.outcomes.size == 2)
        }
        f.sync(); check(f.view().after == 2L && f.delivered.size == 2)
    }
    test("unverified actionable original stays nonterminal and cursor stays before batch") {
        val f = RecoveryFixture(); val e = f.event(); f.events.add(e); f.disposition[e.id] = "stale"; f.active = setOf(e.id)
        check(f.sync()?.state == "waiting-host-confirmation"); check(f.view().after == 0L && f.view().outcomes.single().state == "waiting-host")
        check(f.delivered.isEmpty() && f.activeSets.isEmpty())
    }
    test("owner restoration refreshes captured original; deferred event presented once") {
        val f = RecoveryFixture(); val e = f.event(); f.events.add(e); f.disposition[e.id] = "stale"; f.sync()
        val raw = f.view().pending?.page?.json()?.let(NativeChatRecoveryCanonical::value)
        f.disposition[e.id] = "eligible"; f.active = setOf(e.id); f.sync(); f.sync()
        check(f.delivered == listOf(e.id) && f.view().after == 1L && f.activeSets.first() == setOf(e.id))
        check(raw?.contains("stale") == true)
    }
    test("inactive and expired nonactionable originals are durable without port effects") {
        val f = RecoveryFixture(); val a = f.event(1); val b = f.event(2, NativeChatNoticeKind.COMPLETED); f.events.addAll(listOf(a,b))
        f.disposition[a.id] = "inactive"; f.disposition[b.id] = "stale"; f.sync()
        check(f.delivered.isEmpty() && f.view().after == 2L); check(f.view().outcomes.map { it.state } == listOf("host-inactive", "host-expired"))
    }
    test("filtered original still reaches required P7 reading port") {
        val f = RecoveryFixture(); val e = f.event(); f.events.add(e); f.disposition[e.id] = "filtered"; f.active = setOf(e.id); f.sync()
        check(f.delivered == listOf(e.id) && f.activeSets.single() == setOf(e.id))
    }
    test("uncertain port call is never replayed after restart") {
        val f = RecoveryFixture(); f.events.add(f.event()); f.failReceive = true
        check(runCatching { f.sync() }.isFailure); check(f.view().outcomes.single().state == "unknown-interrupted-no-replay" && f.view().after == 0L)
        f.failReceive = false; check(f.sync()?.state == "foreground-outcome-unknown"); check(f.delivered.size == 1 && f.view().after == 1L)
    }
    test("restored processing becomes unknown without calling port") {
        val f = RecoveryFixture(); f.events.add(f.event()); f.sync(); val raw = JSONObject(requireNotNull(f.storage.read(NativeChatNotificationRecoveryStore.RECORD)))
        val row = raw.getJSONArray("targets").getJSONObject(0); row.put("after", 0); val op = f.view().applied!!.original
        val q = NativeChatRecoveryQuery(op.deviceId, op.epoch, op.generation, 0)
        row.put("through", 1).put("batch", JSONObject().put("id", UUID.randomUUID().toString()).put("operationId", op.operationId)
            .put("page", f.page(op,q,null)).put("decisions", JSONObject().put(f.events.single().id,"eligible")).put("activeIds",JSONArray()))
        row.getJSONArray("outcomes").getJSONObject(0).put("state","processing"); f.storage.write(NativeChatNotificationRecoveryStore.RECORD, raw.toString())
        f.sync(); check(f.delivered.size == 1 && f.view().outcomes.single().state == "unknown-interrupted-no-replay")
    }
    test("presentation fence invalidation prevents remaining batch effects and advancement") {
        val f = RecoveryFixture(); f.events.addAll(listOf(f.event(1), f.event(2))); f.onReceive = { f.current = false }
        f.sync(); check(f.delivered.size == 1 && f.view().after == 0L)
        f.current = true; f.onReceive = null; f.sync(); check(f.delivered.size == 2 && f.view().after == 2L)
    }
    test("captured high water remains bounded while newer events arrive") {
        val f = RecoveryFixture(); f.pageSize = 1; f.events.addAll(listOf(f.event(1),f.event(2))); f.sync(); check(f.view().after == 1L && f.view().through == 2L)
        f.events.add(f.event(3)); f.sync(); check(f.queries.last().through == 2L && f.view().after == 2L); f.sync(); check(f.view().after == 3L)
    }
    test("forged sequence or raw fingerprint never publishes or advances") {
        val f = RecoveryFixture(); f.events.add(f.event()); f.rawFeed = { it.apply { getJSONArray("items").getJSONObject(0).put("sequence", 9) } }
        check(runCatching { f.sync() }.isFailure); check(f.delivered.isEmpty() && f.view().after == 0L && f.view().pending == null)
    }
    test("raw original fingerprint mismatch rejects before publication") {
        val f=RecoveryFixture(); f.events.add(f.event()); f.rawFeed={it.apply{getJSONArray("items").getJSONObject(0).put("fingerprint","f".repeat(64))}}
        check(runCatching{f.sync()}.isFailure);check(f.view().after==0L&&f.delivered.isEmpty())
    }
    test("duplicate active ID and oversized page fail closed") {
        val f=RecoveryFixture();f.events.add(f.event());f.rawFeed={it.apply{put("activeIds",JSONArray(listOf("a".repeat(64),"a".repeat(64))))}}
        check(runCatching{f.sync()}.isFailure);f.rawFeed={it.apply{put("extra","x".repeat(262144))}}
        check(runCatching{f.sync()}.isFailure);check(f.delivered.isEmpty()&&f.view().after==0L)
    }
    test("original account metadata is retained without changing Codex target identity") {
        val f=RecoveryFixture();f.events.add(f.event());f.rawFeed={it.apply{
            val item=getJSONArray("items").getJSONObject(0);val event=item.getJSONObject("event")
            event.getJSONObject("target").put("account",JSONObject().put("authority","fixture-old").put("subject","fixture-user"))
            item.put("fingerprint",NativeChatRecoveryCanonical.hash(event))
        }}
        f.sync();check(f.delivered.size==1&&f.view().outcomes.single().original.contains("fixture-old"))
    }
    test("resolved deferred event uses refreshed active set without old clearing or port call") {
        val f=RecoveryFixture();val e=f.event();f.events.add(e);f.active=setOf(e.id);f.disposition[e.id]="stale";f.sync()
        f.active=emptySet();f.disposition[e.id]="inactive";f.sync()
        check(f.delivered.isEmpty()&&f.activeSets.single().isEmpty()&&f.view().after==1L)
    }
    test("legacy terminal event outcome prevents upgrade replay") {
        val f=RecoveryFixture();val e=f.event();val id=f.identity();val p=f.notices.preferences()
        val mutation=f.legacy.beginMutation(id,f.snapshot(),"register",p,f.now)
        f.legacy.confirm(id,f.target,mutation,NativeChatNotificationStatus(f.target,"foreground-only","unavailable",p.revision,null,false))
        val batch=f.legacy.beginBatch(id,f.snapshot(),p,NativeChatNotificationFeed(listOf(e),e.id,setOf(e.id),NativeChatNotificationStatus(f.target,"foreground-only","unavailable",p.revision,null,false)),f.now)
        f.legacy.startEvent(id,f.target,batch,e.id,f.now);f.legacy.finishEvent(id,f.target,batch,e.id,"unknown-interrupted-no-replay",f.now)
        f.legacy.completeBatch(id,f.target,batch){true};f.events.add(e);f.sync()
        check(f.delivered.isEmpty()&&f.view().outcomes.single().state=="unknown-interrupted-no-replay"&&f.view().after==1L)
    }
    test("late obsolete reset proof cannot activate after newer explicit revoke") {
        val f=RecoveryFixture();f.events.add(f.event());f.gap=true;val offer=requireNotNull(f.sync()?.gap)
        f.loseAck=true;val action=UUID.randomUUID().toString();f.controller().acceptGap(f.snapshot(),offer,action){true}
        val reset=f.view().operations.single{it.original.operationId==action};f.loseAck=false;f.notices.update{it.copy(enabled=false)};f.off()
        f.journal.settle(f.identity(),f.target,reset.original,requireNotNull(f.receipts[action]));check(f.view().resetId==null&&f.view().applied?.original?.kind=="revoke")
    }
    test("gap requires explicit accepted UUID; cold paged snapshot verifies full original hash") {
        val f = RecoveryFixture(); f.events.addAll(listOf(f.event(1),f.event(2))); f.gap = true; f.pageSize = 1
        val offer = requireNotNull(f.sync()?.gap); check(f.submitted.size == 1 && f.view().after == 0L)
        val action = UUID.randomUUID().toString(); f.controller().acceptGap(f.snapshot(), offer, action) { true }
        f.controller().acceptGap(f.snapshot(), offer, action) { true }; check(f.submitted.size == 2)
        f.sync(); check(f.view().resetAfter == 1L && f.view().after == 0L); f.sync()
        check(f.view().resetId == null && f.view().after == 2L && f.delivered.size == 2)
        val saved = f.view().operations.single { it.original.operationId == action }; f.journal.settle(f.identity(), f.target, saved.original, saved.receipt!!)
        check(f.view().resetId == null) // Late duplicate proof cannot reactivate completed snapshot.
    }
    test("snapshot full-hash mismatch leaves live cursor unchanged") {
        val f = RecoveryFixture(); f.events.add(f.event()); f.gap = true; f.badSnapshotHash = true
        val offer = requireNotNull(f.sync()?.gap); f.controller().acceptGap(f.snapshot(), offer, UUID.randomUUID().toString()) { true }
        check(runCatching { f.sync() }.isFailure); check(f.view().after == 0L && f.view().pending != null)
    }
    test("expired reset is never reposted; a fresh gap needs another human acceptance") {
        val f = RecoveryFixture(); f.events.add(f.event()); f.gap = true; val offer = requireNotNull(f.sync()?.gap)
        f.controller().acceptGap(f.snapshot(), offer, UUID.randomUUID().toString()) { true }; f.expired = true
        check(f.sync()?.gap != null && f.submitted.size == 2 && f.view().after == 0L)
    }
    test("unknown schema preserves exact original bytes and performs no write") {
        val f = RecoveryFixture(); f.sync(); val raw = JSONObject(requireNotNull(f.storage.read(NativeChatNotificationRecoveryStore.RECORD))).put("version", 99).toString()
        f.storage.write(NativeChatNotificationRecoveryStore.RECORD, raw); check(runCatching { f.sync() }.isFailure)
        check(f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == raw && f.submitted.size == 1)
    }
    test("archive validator does not activate portable subscription or identity") {
        val f = RecoveryFixture(); f.sync(); val raw = requireNotNull(f.storage.read(NativeChatNotificationRecoveryStore.RECORD))
        NativeChatNotificationRecoveryStore.validateEncoded(raw); val fresh = NativeSessionStorage()
        check(fresh.read(NativeChatNotificationRecoveryStore.RECORD) == null && fresh.read(NativeChatNotificationIdentity.RECORD) == null)
    }
    test("real fixed HTTP operations target original ID/epoch and never retry POST") {
        val f = RecoveryFixture(); val identity = f.identity(); val original = NativeChatRecoveryIntent.create(identity, f.target, f.notices.preferences(), f.epoch, 1, "register")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0); val requests = mutableListOf<Pair<String,Map<String,String>>>()
        var postCount = 0; var cap404 = false; var badVersion = false; var failPost = false
        server.createContext("/v1/notifications/recovery-v2/") { exchange ->
            val fields = exchange.requestURI.rawQuery.split('&').associate { part -> val pair=part.split('=',limit=2); URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8") }
            requests.add(exchange.requestMethod to fields); check(exchange.requestHeaders.getFirst("Authorization") == "Bearer " + "t".repeat(40))
            val suffix = exchange.requestURI.path.substringAfterLast('/')
            val raw = when(suffix) {
                "capabilities" -> JSONObject().put("version",if(badVersion)3 else 2).put("epoch",f.epoch).put("target", NativeChatNotificationCodec.target(f.target))
                    .put("operations",JSONArray(listOf("register","preferences","revoke","feed-reset"))).put("limits",JSONObject(NativeChatRecoveryWire.limits))
                "operations" -> if(exchange.requestMethod=="POST") { postCount++; val body=JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8)); check(NativeChatRecoveryIntent.parse(body.getJSONObject("intent")).encoded()==original.encoded()); f.receipt(original).json() }
                    else JSONObject().put("version",2).put("epoch",f.epoch).put("target",NativeChatNotificationCodec.target(f.target)).put("deviceId",original.deviceId)
                        .put("operationId",original.operationId).put("found",true).put("receipt",f.receipt(original).json())
                else -> f.page(original,NativeChatRecoveryQuery(original.deviceId,original.epoch,1,0),null)
            }
            val bytes=raw.toString().toByteArray(); exchange.sendResponseHeaders(if(cap404 && suffix=="capabilities")404 else if(failPost && exchange.requestMethod=="POST")500 else 200,bytes.size.toLong()); exchange.responseBody.use{it.write(bytes)}
        }; server.start()
        try {
            val snapshot=NativeChatNotificationSnapshot(MobileClient(f.target.hostId,"Fixture",TargetScope("codex",null,null,f.target.hostId,f.target.workspaceId,f.target.runtimeId),"http://127.0.0.1:${server.address.port}"),"t".repeat(40),"fixture-credential")
            val http=NativeChatNotificationRecoveryHttp(); check(http.recoveryCapabilities(snapshot,original.deviceId)?.epoch==f.epoch)
            check(http.recoverySubmit(snapshot,original).intent.encoded()==original.encoded()); check(http.recoveryQuery(snapshot,original)?.intent?.encoded()==original.encoded())
            val q=requests.last().second; check(q["operationId"]==original.operationId && q["epoch"]==original.epoch && q["deviceId"]==original.deviceId)
            check(http.recoveryFeed(snapshot,original,NativeChatRecoveryQuery(original.deviceId,original.epoch,1,0)).page?.next==0L)
            cap404=true; check(http.recoveryCapabilities(snapshot,original.deviceId)==null); cap404=false; badVersion=true
            check(runCatching{http.recoveryCapabilities(snapshot,original.deviceId)}.isFailure && postCount==1)
            failPost=true;check(runCatching{http.recoverySubmit(snapshot,original)}.isFailure && postCount==2)
        } finally {server.stop(0)}
    }
    println("$passed shipping Kotlin recovery auxiliary vectors passed; native/real DEV not run")
}; exitProcess(0) }
