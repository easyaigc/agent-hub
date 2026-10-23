package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Connection/storage identity only. Never rewrites a captured command or claim payload. */
object RuntimeIdentity {
    fun matches(a: TargetScope?, b: TargetScope?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.agentId == b.agentId && a.hostId == b.hostId &&
            a.workspaceId == b.workspaceId && a.runtimeId == b.runtimeId &&
            (a.agentId == "codex" || a.accountAuthority == b.accountAuthority && a.accountSubject == b.accountSubject)
    }

    private fun encoded(values: List<String?>): String = Base64.encode(JsonArray(values.map {
        it?.let(::JsonPrimitive) ?: JsonNull
    }).toString().replace("/", "\\/").encodeToByteArray())

    private fun machine(target: TargetScope) = listOf(target.agentId, target.hostId, target.workspaceId, target.runtimeId)

    fun cacheKey(clientId: String, target: TargetScope?, threadId: String?, projectId: String?): String =
        if (target?.agentId != "codex") MobileRules.cacheKey(clientId, target, threadId, projectId)
        else "codex-machine-v2:" + encoded(machine(target) + listOf(if (threadId == null) "draft" else "thread", threadId ?: projectId))

    fun readingKey(target: TargetScope, threadId: String?): String =
        if (target.agentId != "codex") MobileRules.readingKey(target, threadId)
        else "codex-reading-v2:" + encoded(machine(target) + threadId)

    fun readerScope(target: TargetScope): String = "codex-machine-v2:" + encoded(machine(target))

    fun compatibleCacheKeys(target: TargetScope, threadId: String?, projectId: String?, keys: List<String>): List<String> {
        if (target.agentId != "codex") return emptyList()
        val canonical = cacheKey("", target, threadId, projectId)
        return keys.distinct().filter { key ->
            if (key == canonical) true else {
                val row = tuple(key, "agent-v1:")
                row != null && row.size == 8 && listOf(row[0], row[3], row[4], row[5]) == machine(target) &&
                    row[6] == (if (threadId == null) "draft" else "thread") && row[7] == (threadId ?: projectId)
            }
        }.sorted()
    }

    fun preferredKey(canonical: String, candidates: List<String>, selectedLegacy: String?): String = when {
        selectedLegacy != null && selectedLegacy in candidates -> selectedLegacy
        canonical in candidates -> canonical
        candidates.distinct().size == 1 -> candidates[0]
        else -> canonical
    }

    private fun tuple(key: String, prefix: String): List<String?>? = runCatching {
        if (!key.startsWith(prefix) || key.length > 12_000) return null
        val bytes = Base64.decode(key.removePrefix(prefix))
        if (bytes.size > 8192) return null
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonArray.map {
            if (it == JsonNull) null else it.jsonPrimitive.takeIf { p -> p.isString }?.content
                ?: error("Storage tuple requires strings or null")
        }
    }.getOrNull()
}
