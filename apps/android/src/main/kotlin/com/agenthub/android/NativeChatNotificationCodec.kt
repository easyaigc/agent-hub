package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Strict closed JSON: reject coercion, unknown keys, partial targets and future versions. */
internal object NativeChatNotificationCodec {
    private fun objectKeys(v: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = v.keys().asSequence().toSet()
        require(keys.containsAll(required) && (keys - required - optional).isEmpty())
    }
    private fun string(v: JSONObject, key: String): String = (v.get(key) as? String) ?: error("Invalid notification string")
    private fun optional(v: JSONObject, key: String) = if (v.has(key)) string(v, key) else null
    private fun bool(v: JSONObject, key: String): Boolean = (v.get(key) as? Boolean) ?: error("Invalid notification boolean")
    private fun number(v: JSONObject, key: String): Long {
        val n = v.get(key); require(n is Int || n is Long); return (n as Number).toLong()
    }
    fun target(v: JSONObject): NativeChatNoticeTarget {
        objectKeys(v, setOf("agentId", "hostId", "workspaceId", "runtimeId"), setOf("account"))
        require(string(v, "agentId") == "codex")
        if (v.has("account")) v.getJSONObject("account").let { account ->
            objectKeys(account, setOf("authority", "subject"))
            require(chatNoticeId(string(account, "authority")) && chatNoticeId(string(account, "subject")))
        }
        return NativeChatNoticeTarget(string(v, "hostId"), string(v, "workspaceId"), string(v, "runtimeId"))
    }
    fun target(v: NativeChatNoticeTarget): JSONObject = JSONObject().put("agentId", "codex")
        .put("hostId", v.hostId).put("workspaceId", v.workspaceId).put("runtimeId", v.runtimeId)
    fun preferences(v: JSONObject): NativeChatNotificationPreferences {
        objectKeys(v, setOf("version", "revision", "generation", "enabled", "kinds", "excludedHosts", "foregroundBanner",
            "soundEnabled", "badgeEnabled", "showThreadTitle", "timeSensitive", "sound", "categorySounds"))
        require(number(v, "version") == 1L)
        fun strings(key: String, max: Int): List<String> = v.getJSONArray(key).let { a ->
            require(a.length() <= max)
            (0 until a.length()).map { (a.get(it) as? String) ?: error("Invalid notification array") }
                .also { require(it.distinct().size == it.size) }
        }
        val sounds = v.getJSONObject("categorySounds")
        return NativeChatNotificationPreferences(revision = string(v, "revision"), generation = number(v, "generation"),
            enabled = bool(v, "enabled"), kinds = strings("kinds", 10).map(NativeChatNoticeKind::from).toSet(),
            excludedHosts = strings("excludedHosts", 256).toSet(), foregroundBanner = bool(v, "foregroundBanner"),
            soundEnabled = bool(v, "soundEnabled"), badgeEnabled = bool(v, "badgeEnabled"), showThreadTitle = bool(v, "showThreadTitle"),
            timeSensitive = bool(v, "timeSensitive"), sound = string(v, "sound"),
            categorySounds = sounds.keys().asSequence().associate { NativeChatNoticeKind.from(it) to string(sounds, it) })
    }
    fun preferences(v: NativeChatNotificationPreferences): JSONObject = JSONObject().put("version", 1)
        .put("revision", v.revision).put("generation", v.generation).put("enabled", v.enabled)
        .put("kinds", JSONArray(NativeChatNoticeKind.entries.filter { it in v.kinds }.map { it.wire }))
        .put("excludedHosts", JSONArray(v.excludedHosts.sorted())).put("foregroundBanner", v.foregroundBanner)
        .put("soundEnabled", v.soundEnabled).put("badgeEnabled", v.badgeEnabled).put("showThreadTitle", v.showThreadTitle)
        .put("timeSensitive", v.timeSensitive).put("sound", v.sound)
        .put("categorySounds", JSONObject(v.categorySounds.mapKeys { it.key.wire }))
    fun event(v: JSONObject): NativeChatNotificationEvent {
        objectKeys(v, setOf("version", "id", "target", "kind", "occurredAt"), setOf("threadId", "runId", "requestId",
            "operationId", "threadTitle", "taskSummary", "readTarget", "readPosition"))
        require(number(v, "version") == 1L)
        val read = if (v.has("readPosition")) v.getJSONObject("readPosition").let { p ->
            objectKeys(p, setOf("revision", "messageId", "contentVersion"))
            NativeChatNoticeReadPosition(number(p, "revision"), string(p, "messageId"), string(p, "contentVersion"))
        } else null
        return NativeChatNotificationEvent(string(v, "id"), target(v.getJSONObject("target")), NativeChatNoticeKind.from(string(v, "kind")),
            string(v, "occurredAt"), optional(v, "threadId"), optional(v, "runId"), optional(v, "requestId"),
            optional(v, "operationId"), optional(v, "threadTitle"), optional(v, "taskSummary"),
            if (v.has("readTarget")) target(v.getJSONObject("readTarget")) else null, read)
    }
    fun event(v: NativeChatNotificationEvent): JSONObject = JSONObject().put("version", 1).put("id", v.id)
        .put("target", target(v.target)).put("kind", v.kind.wire).put("occurredAt", v.occurredAt)
        .put("threadId", v.threadId).put("runId", v.runId).put("requestId", v.requestId).put("operationId", v.operationId)
        .put("threadTitle", v.threadTitle).put("taskSummary", v.taskSummary).put("readTarget", v.readTarget?.let(::target))
        .put("readPosition", v.readPosition?.let { JSONObject().put("revision", it.revision).put("messageId", it.messageId).put("contentVersion", it.contentVersion) })
    fun lease(v: NativeChatNoticeLease): JSONObject = JSONObject().put("target", target(v.target)).put("credentialEpoch", v.credentialEpoch)
        .put("registrationGeneration", v.registrationGeneration).put("preferenceRevision", v.preferenceRevision)
    fun lease(v: JSONObject): NativeChatNoticeLease {
        objectKeys(v, setOf("target", "credentialEpoch", "registrationGeneration", "preferenceRevision"))
        return NativeChatNoticeLease(target(v.getJSONObject("target")), string(v, "credentialEpoch"), string(v, "registrationGeneration"), string(v, "preferenceRevision")).also {
            require(chatNoticeId(it.credentialEpoch) && chatNoticeId(it.preferenceRevision))
            require(java.util.UUID.fromString(it.registrationGeneration).toString() == it.registrationGeneration)
        }
    }
    fun receipt(v: NativeChatNoticeReceipt): JSONObject = JSONObject().put("event", event(v.event)).put("lease", lease(v.lease))
        .put("ticket", v.ticket).put("expiresAt", v.expiresAt).put("state", v.state).put("consumed", v.consumed)
    fun receipt(v: JSONObject): NativeChatNoticeReceipt {
        objectKeys(v, setOf("event", "lease", "ticket", "expiresAt", "state", "consumed"))
        return NativeChatNoticeReceipt(event(v.getJSONObject("event")), lease(v.getJSONObject("lease")), string(v, "ticket"),
            number(v, "expiresAt"), string(v, "state"), bool(v, "consumed")).also {
            require(it.event.target == it.lease.target && java.util.UUID.fromString(it.ticket).toString() == it.ticket && it.expiresAt > 0)
        }
    }
}
