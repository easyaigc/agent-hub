package com.agenthub.android

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NativeChatNotificationMutation(val id: String, val kind: String, val authorization: String,
    val preferences: NativeChatNotificationPreferences?, val state: String, val at: Long,
    val responseState: String? = null, val responseDelivery: String? = null) {
    val fingerprint get() = NativeChatNotificationWire.hash(listOf(kind, authorization,
        preferences?.let(NativeChatNotificationWire::preferenceHash).orEmpty()).joinToString("\u0000"))
}
internal data class NativeChatNotificationOutcome(val event: NativeChatNotificationEvent, val state: String, val at: Long) {
    val terminal get() = state !in setOf("pending", "processing")
}
internal data class NativeChatNotificationBatch(val id: String, val before: String, val after: String,
    val revision: String, val authorization: String, val ids: List<String>, val activeIds: Set<String>)
internal data class NativeChatNotificationSubscription(val target: NativeChatNoticeTarget, val authorization: String?,
    val preferences: NativeChatNotificationPreferences?, val revoked: Boolean, val cursor: String,
    val mutations: List<NativeChatNotificationMutation>, val batch: NativeChatNotificationBatch?, val outcomes: List<NativeChatNotificationOutcome>) {
    val unknown get() = mutations.singleOrNull { it.state == "unknown" }
}

/** IO-only, authenticated journal. This is processing/transport state, never a second unread ledger. */
internal class NativeChatNotificationForegroundStore(private val storage: NativeSessionStorage) {
    companion object {
        const val RECORD = "native-chat-notification-foreground-v1"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val states = setOf("pending", "processing", "suppressed", "system-unavailable", "submitted-to-system", "unknown-no-replay",
            "unknown-interrupted-no-replay", "stale-target-or-generation", "expired-or-future", "duplicate-expired-or-read")
        /** Migration can validate an archived record, but must not activate identities/subscriptions/cursors. */
        fun validateEncoded(raw: String) { decode(raw) }
        private fun strings(v: JSONObject, key: String): List<String> {
            val a = v.getJSONArray(key); require(a.length() <= NativeChatNotificationWire.MAX_EVENTS)
            return (0 until a.length()).map { (a.get(it) as? String ?: error("Invalid event ID")).also { id -> require(chatNoticeHash(id)) } }
                .also { require(it.distinct().size == it.size) }
        }
        private fun cursor(v: JSONObject, key: String): String = (v.get(key) as? String ?: error("Invalid cursor")).also { require(it.isEmpty() || chatNoticeHash(it)) }
        private fun mutation(v: JSONObject): NativeChatNotificationMutation {
            NativeChatNotificationWire.keys(v, setOf("id", "kind", "authorization", "preferences", "state", "at", "fingerprint"), setOf("responseState", "responseDelivery"))
            val kind = NativeChatNotificationWire.text(v, "kind"); require(kind in setOf("register", "preferences", "unregister"))
            val preferences = if (v.get("preferences") == JSONObject.NULL) null else NativeChatNotificationCodec.preferences(v.getJSONObject("preferences"))
            require((kind == "unregister") == (preferences == null))
            val value = NativeChatNotificationMutation(NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(v, "id")), kind,
                NativeChatNotificationWire.text(v, "authorization"), preferences, NativeChatNotificationWire.text(v, "state"), NativeChatNotificationWire.long(v, "at"),
                NativeChatNotificationWire.optional(v, "responseState"), NativeChatNotificationWire.optional(v, "responseDelivery"))
            require(chatNoticeHash(value.authorization) && value.state in setOf("unknown", "confirmed") && value.fingerprint == NativeChatNotificationWire.text(v, "fingerprint"))
            require(value.responseState == null || value.responseState in setOf("disabled", "ready", "pending", "unavailable"))
            require(value.responseDelivery == null || value.responseDelivery in setOf("foreground-only", "apns"))
            require((value.state == "confirmed") == (value.responseState != null && value.responseDelivery != null))
            if (value.state == "unknown") require(value.responseState == null && value.responseDelivery == null)
            return value
        }
        private fun mutation(v: NativeChatNotificationMutation) = JSONObject().put("id", v.id).put("kind", v.kind).put("authorization", v.authorization)
            .put("preferences", v.preferences?.let(NativeChatNotificationCodec::preferences) ?: JSONObject.NULL).put("state", v.state).put("at", v.at)
            .put("fingerprint", v.fingerprint).put("responseState", v.responseState).put("responseDelivery", v.responseDelivery)
        private fun batch(v: JSONObject): NativeChatNotificationBatch {
            NativeChatNotificationWire.keys(v, setOf("id", "before", "after", "revision", "authorization", "ids", "activeIds"))
            return NativeChatNotificationBatch(NativeChatNotificationWire.uuid(NativeChatNotificationWire.text(v, "id")), cursor(v, "before"), cursor(v, "after"),
                NativeChatNotificationWire.text(v, "revision"), NativeChatNotificationWire.text(v, "authorization"), strings(v, "ids"), strings(v, "activeIds").toSet()).also {
                require(chatNoticeHash(it.authorization) && it.after == (it.ids.lastOrNull() ?: it.before))
            }
        }
        private fun batch(v: NativeChatNotificationBatch) = JSONObject().put("id", v.id).put("before", v.before).put("after", v.after)
            .put("revision", v.revision).put("authorization", v.authorization).put("ids", JSONArray(v.ids)).put("activeIds", JSONArray(v.activeIds.sorted()))
        private fun outcome(v: JSONObject): NativeChatNotificationOutcome {
            NativeChatNotificationWire.keys(v, setOf("event", "fingerprint", "state", "at"))
            return NativeChatNotificationOutcome(NativeChatNotificationCodec.event(v.getJSONObject("event")), NativeChatNotificationWire.text(v, "state"), NativeChatNotificationWire.long(v, "at")).also {
                require(it.state in states && NativeChatNotificationWire.eventHash(it.event) == NativeChatNotificationWire.text(v, "fingerprint"))
            }
        }
        private fun outcome(v: NativeChatNotificationOutcome) = JSONObject().put("event", NativeChatNotificationCodec.event(v.event))
            .put("fingerprint", NativeChatNotificationWire.eventHash(v.event)).put("state", v.state).put("at", v.at)
        private fun subscription(v: JSONObject): NativeChatNotificationSubscription {
            NativeChatNotificationWire.keys(v, setOf("target", "authorization", "preferences", "revoked", "cursor", "mutations", "batch", "outcomes"))
            val mutations = v.getJSONArray("mutations"); val outcomes = v.getJSONArray("outcomes")
            require(mutations.length() <= 64 && outcomes.length() <= 2048)
            val record = NativeChatNotificationSubscription(NativeChatNotificationCodec.target(v.getJSONObject("target")),
                if (v.get("authorization") == JSONObject.NULL) null else NativeChatNotificationWire.text(v, "authorization"),
                if (v.get("preferences") == JSONObject.NULL) null else NativeChatNotificationCodec.preferences(v.getJSONObject("preferences")),
                v.get("revoked") as? Boolean ?: error("Invalid revocation state"), cursor(v, "cursor"),
                (0 until mutations.length()).map { mutation(mutations.getJSONObject(it)) },
                if (v.get("batch") == JSONObject.NULL) null else batch(v.getJSONObject("batch")),
                (0 until outcomes.length()).map { outcome(outcomes.getJSONObject(it)) })
            require(record.authorization == null || chatNoticeHash(record.authorization))
            require((record.preferences == null) || record.authorization != null)
            require(record.revoked || record.preferences != null && record.authorization != null)
            require(record.mutations.map { it.id }.distinct().size == record.mutations.size && record.mutations.count { it.state == "unknown" } <= 1)
            require(record.outcomes.all { it.event.target == record.target } && record.outcomes.map { it.event.id }.distinct().size == record.outcomes.size)
            record.batch?.let { b ->
                require(record.unknown == null && !record.revoked && b.authorization == record.authorization && b.revision == record.preferences?.revision)
                require(b.before == record.cursor && b.ids.all { id -> record.outcomes.any { it.event.id == id } })
            }
            return record
        }
        private fun subscription(v: NativeChatNotificationSubscription) = JSONObject().put("target", NativeChatNotificationCodec.target(v.target))
            .put("authorization", v.authorization ?: JSONObject.NULL).put("preferences", v.preferences?.let(NativeChatNotificationCodec::preferences) ?: JSONObject.NULL)
            .put("revoked", v.revoked).put("cursor", v.cursor).put("mutations", JSONArray(v.mutations.map(::mutation)))
            .put("batch", v.batch?.let(::batch) ?: JSONObject.NULL).put("outcomes", JSONArray(v.outcomes.map(::outcome)))
        private fun decode(raw: String): State {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val v = JSONObject(raw); NativeChatNotificationWire.keys(v, setOf("version", "deviceId", "identity", "subscriptions"))
            require(NativeChatNotificationWire.long(v, "version") == 1L)
            val a = v.getJSONArray("subscriptions"); require(a.length() <= 64)
            return State(NativeChatNotificationWire.device(NativeChatNotificationWire.text(v, "deviceId", 128)), NativeChatNotificationWire.text(v, "identity"),
                (0 until a.length()).map { subscription(a.getJSONObject(it)) }).also {
                require(chatNoticeHash(it.identity) && it.subscriptions.map { s -> s.target }.distinct().size == it.subscriptions.size)
                require(it.subscriptions.sumOf { s -> s.outcomes.size } <= 2048 && it.subscriptions.sumOf { s -> s.mutations.size } <= 256)
            }
        }
    }
    private data class State(val deviceId: String, val identity: String, val subscriptions: List<NativeChatNotificationSubscription>)
    private fun io() = check(Looper.myLooper() != Looper.getMainLooper()) { "Foreground notification storage requires IO" }
    private fun load(): State? = storage.read(RECORD)?.let(::decode)
    private fun save(v: State) {
        val raw = JSONObject().put("version", 1).put("deviceId", v.deviceId).put("identity", v.identity).put("subscriptions", JSONArray(v.subscriptions.map(::subscription))).toString()
        validateEncoded(raw); storage.write(RECORD, raw)
    }
    private fun <T> transaction(identity: NativeChatNotificationIdentity, action: (State) -> T): T {
        io(); return storage.snapshotTransaction { storage.operationTransaction {
            val state = load() ?: State(identity.deviceId, identity.fingerprint, emptyList())
            require(state.deviceId == identity.deviceId && state.identity == identity.fingerprint) { "Foreground notification identity mismatch" }
            action(state)
        } }
    }
    private fun State.record(target: NativeChatNoticeTarget) = subscriptions.singleOrNull { it.target == target }
        ?: NativeChatNotificationSubscription(target, null, null, true, "", emptyList(), null, emptyList())
    private fun State.replace(record: NativeChatNotificationSubscription) = copy(subscriptions = subscriptions.filterNot { it.target == record.target } + record)
    fun exists(): Boolean { io(); return storage.read(RECORD) != null }
    /** Read-only settings inventory; decoding never creates an identity or changes an original. */
    fun pendingByTarget(): Map<NativeChatNoticeTarget, List<String>> {
        io(); return storage.snapshotTransaction { load()?.subscriptions?.associate { row ->
            row.target to row.mutations.filter { it.state == "unknown" }.map { it.authorization }
        }.orEmpty() }
    }
    fun view(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget) = transaction(identity) { it.record(target) }
    fun beginMutation(identity: NativeChatNotificationIdentity, snapshot: NativeChatNotificationSnapshot, kind: String,
        preferences: NativeChatNotificationPreferences?, now: Long): NativeChatNotificationMutation = transaction(identity) { state ->
        val old = state.record(snapshot.target); check(old.unknown == null) { "Unconfirmed notification mutation; status query only" }
        require(kind in setOf("register", "preferences", "unregister") && (kind == "unregister") == (preferences == null))
        check(old.authorization == null || old.authorization == snapshot.authorizationFingerprint || old.revoked) { "Original notification credential must be revoked first" }
        if (preferences != null && old.preferences != null) {
            require(preferences.generation >= old.preferences.generation)
            if (preferences.generation == old.preferences.generation) require(NativeChatNotificationWire.preferenceHash(preferences) == NativeChatNotificationWire.preferenceHash(old.preferences))
            if (old.revoked && preferences.enabled) require(preferences.generation > old.preferences.generation)
        }
        check(old.batch == null) { "Resolve original notification batch first" }
        val retained = old.mutations.filter { it.state == "unknown" || it.at > now - NativeChatNotificationStore.TTL_MILLIS }
        check(retained.size < 64) { "Notification operation capacity reached" }
        val operation = NativeChatNotificationMutation(UUID.randomUUID().toString(), kind, snapshot.authorizationFingerprint, preferences, "unknown", now)
        NativeChatNotificationIdentity.bind(storage, identity)
        save(state.replace(old.copy(mutations = retained + operation))); operation
    }
    /** Only a validated direct response to this exact in-flight call can confirm Host v1 mutations. */
    fun confirm(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, operation: NativeChatNotificationMutation,
        status: NativeChatNotificationStatus) = transaction(identity) { state ->
        val old = state.record(target); check(old.unknown == operation && status.target == target)
        if (operation.preferences != null) require(status.revision == operation.preferences.revision)
        else require(status.state == "disabled")
        val revoked = operation.kind == "unregister" || operation.preferences?.let { !it.enabled || target.hostId in it.excludedHosts } == true
        if (revoked) require(status.state == "disabled")
        save(state.replace(old.copy(authorization = operation.authorization, preferences = operation.preferences ?: old.preferences, revoked = revoked,
            mutations = old.mutations.map { if (it.id == operation.id) it.copy(state = "confirmed", responseState = status.state, responseDelivery = status.delivery) else it })))
    }
    fun beginBatch(identity: NativeChatNotificationIdentity, snapshot: NativeChatNotificationSnapshot, preferences: NativeChatNotificationPreferences,
        feed: NativeChatNotificationFeed, now: Long): NativeChatNotificationBatch = transaction(identity) { state ->
        val old = state.record(snapshot.target)
        check(old.unknown == null && old.batch == null && !old.revoked && old.authorization == snapshot.authorizationFingerprint)
        check(old.preferences?.let(NativeChatNotificationWire::preferenceHash) == NativeChatNotificationWire.preferenceHash(preferences))
        require(feed.status.target == snapshot.target && feed.status.foreground(preferences.revision))
        require(feed.events.size <= NativeChatNotificationWire.MAX_EVENTS && feed.activeIds.size <= NativeChatNotificationWire.MAX_EVENTS && feed.activeIds.all(::chatNoticeHash))
        require(feed.events.all { it.target == snapshot.target } && feed.events.map { it.id }.distinct().size == feed.events.size)
        require(feed.cursor == (feed.events.lastOrNull()?.id ?: old.cursor))
        val ids = feed.events.map { it.id }
        // Completed outcomes remain independent of preference/credential generations and an unknown Host cursor.
        val retained = old.outcomes.filter { !it.terminal || it.at > now - NativeChatNotificationStore.TTL_MILLIS }
        val next = retained.toMutableList()
        feed.events.forEach { event ->
            val existing = next.singleOrNull { it.event.id == event.id }
            if (existing != null) require(NativeChatNotificationWire.eventHash(existing.event) == NativeChatNotificationWire.eventHash(event)) { "Notification event ID reused" }
            else next.add(NativeChatNotificationOutcome(event, "pending", now))
        }
        val batch = NativeChatNotificationBatch(UUID.randomUUID().toString(), old.cursor, feed.cursor, preferences.revision, snapshot.authorizationFingerprint, ids, feed.activeIds)
        save(state.replace(old.copy(batch = batch, outcomes = next))); batch
    }
    /** A previously started event is ambiguous after interruption, so it is never called again. */
    fun startEvent(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batch: NativeChatNotificationBatch, id: String,
        now: Long): NativeChatNotificationEvent? = transaction(identity) { state ->
        val old = state.record(target); check(old.batch == batch && id in batch.ids)
        val entry = old.outcomes.single { it.event.id == id }; if (entry.terminal) return@transaction null
        val next = if (entry.state == "processing") "unknown-interrupted-no-replay" else "processing"
        save(state.replace(old.copy(outcomes = old.outcomes.map { if (it.event.id == id) it.copy(state = next, at = now) else it })))
        entry.event.takeIf { next == "processing" }
    }
    fun finishEvent(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batch: NativeChatNotificationBatch,
        id: String, result: String, now: Long) = transaction(identity) { state ->
        require(result in states - setOf("pending", "processing"))
        val old = state.record(target); check(old.batch == batch && id in batch.ids)
        val entry = old.outcomes.single { it.event.id == id }; check(entry.state == "processing")
        save(state.replace(old.copy(outcomes = old.outcomes.map { if (it.event.id == id) it.copy(state = result, at = now) else it })))
    }
    fun completeBatch(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, batch: NativeChatNotificationBatch,
        current: () -> Boolean) = transaction(identity) { state ->
        val old = state.record(target); check(old.batch == batch && old.cursor == batch.before && current())
        check(batch.ids.all { id -> old.outcomes.single { it.event.id == id }.terminal })
        save(state.replace(old.copy(batch = null, cursor = batch.after)))
    }
    /** Do not advance an old stream after navigation/preferences/credentials change. Original results survive. */
    fun abandonBatch(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget) = transaction(identity) { state ->
        val old = state.record(target); val batch = old.batch ?: return@transaction
        save(state.replace(old.copy(batch = null, outcomes = old.outcomes.map {
            if (it.event.id !in batch.ids || it.terminal) it else it.copy(state = if (it.state == "processing") "unknown-interrupted-no-replay" else "stale-target-or-generation")
        })))
    }
    fun outcome(identity: NativeChatNotificationIdentity, target: NativeChatNoticeTarget, id: String) = transaction(identity) { it.record(target).outcomes.singleOrNull { o -> o.event.id == id } }
}
