package com.agenthub.android

import com.agenthub.core.MobileRules
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject

/** Chooses original encrypted namespaces without renaming data or submitted intents. */
class MobileStorageIdentity(val legacyTarget: TargetScope?, keys: List<String>, selections: Map<String, String> = emptyMap()) {
    private val knownKeys = keys.distinct()
    private val candidates = mutableMapOf<String, List<String>>()
    private val selected = selections.toMutableMap()

    fun choices(client: MobileClient, thread: String?, project: String?): List<String> {
        val scope = client.scope ?: return emptyList()
        if (scope.agentId != "codex") return emptyList()
        val canonical = RuntimeIdentity.cacheKey(client.id, scope, thread, project)
        return candidates.getOrPut(canonical) {
            val legacy = legacyTarget?.takeIf { RuntimeIdentity.matches(it, scope) }
                ?.let { MobileRules.cacheKey(client.id, it, thread, project) }
            RuntimeIdentity.compatibleCacheKeys(scope, thread, project, knownKeys + listOfNotNull(legacy))
        }
    }

    fun key(client: MobileClient, thread: String?, project: String?): String {
        val canonical = RuntimeIdentity.cacheKey(client.id, client.scope, thread, project)
        return RuntimeIdentity.preferredKey(canonical, choices(client, thread, project), selected[canonical])
    }

    fun ambiguous(client: MobileClient, thread: String?, project: String?): Boolean {
        val canonical = RuntimeIdentity.cacheKey(client.id, client.scope, thread, project)
        val options = choices(client, thread, project)
        return canonical !in options && options.size > 1 && selected[canonical] !in options
    }

    fun selecting(client: MobileClient, thread: String?, project: String?, key: String): MobileStorageIdentity {
        require(key in choices(client, thread, project))
        return MobileStorageIdentity(legacyTarget, knownKeys,
            selected + (RuntimeIdentity.cacheKey(client.id, client.scope, thread, project) to key))
    }

    fun json() = JSONObject().put("version", 1).put("legacyTarget", legacyTarget?.let(::encodeTarget))
        .put("keys", JSONArray(knownKeys)).put("selections", JSONObject(selected.toMap()))

    fun withKeys(keys: List<String>) = MobileStorageIdentity(legacyTarget, knownKeys + keys, selected)

    companion object {
        fun decode(value: JSONObject): MobileStorageIdentity {
            require(value.getInt("version") == 1)
            val selections = value.getJSONObject("selections")
            return MobileStorageIdentity(HostReadCodec.target(value.optJSONObject("legacyTarget")), value.getJSONArray("keys").strings(),
                selections.keys().asSequence().associateWith { selections.getString(it) })
        }
    }
}

internal fun MobileClient.cacheKey(thread: String?, project: String? = null): String =
    storageIdentity?.key(this, thread, project) ?: RuntimeIdentity.cacheKey(id, scope, thread, project)

internal fun MobileClient.operationTargets(thread: String?): List<String> =
    (listOf(cacheKey(thread)) + storageIdentity?.choices(this, thread, null).orEmpty()).distinct()
