package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Phone-local navigation only. IDs in this cache never authorize access or issue a command. */
internal object NativeRecentProjects {
    const val LIMIT = 5
    // Match the existing metadata map/opaque-ID limits. The metadata frame retains its 8 MiB cap.
    const val MAX_SCOPES = 10_000
    const val MAX_PROJECT_ID = 16_384
    private val scopePattern = Regex("[a-f0-9]{64}")

    fun scope(client: MobileClient): String? = if (client.isDemo) {
        readingSHA("recent-projects-demo:" + client.id)
    } else client.scope?.let(ThreadReadingIdentity::scopeKey)

    fun record(previous: List<String>, projectID: String): List<String> {
        require(projectID.isNotEmpty() && projectID.length <= MAX_PROJECT_ID)
        return (listOf(projectID) + previous.filterNot { it == projectID }).take(LIMIT)
    }

    fun visibleIDs(previous: List<String>, availableIDs: Set<String>): List<String> =
        previous.asSequence().filter { it in availableIDs }.distinct().take(LIMIT).toList()

    private fun validate(scope: String, ids: List<String>) {
        require(scopePattern.matches(scope))
        require(ids.size <= LIMIT && ids.distinct().size == ids.size)
        require(ids.all { it.isNotEmpty() && it.length <= MAX_PROJECT_ID })
    }

    fun encode(history: Map<String, List<String>>): JSONObject {
        require(history.size <= MAX_SCOPES)
        val rows = JSONObject()
        history.forEach { (scope, ids) -> validate(scope, ids); rows.put(scope, JSONArray(ids)) }
        return JSONObject().put("version", 1).put("projectIDsByScope", rows)
    }

    fun decode(value: JSONObject): Map<String, List<String>> {
        require(value.keys().asSequence().toSet() == setOf("version", "projectIDsByScope"))
        require(value.get("version") == 1)
        val rows = value.get("projectIDsByScope") as JSONObject
        require(rows.length() <= MAX_SCOPES)
        return rows.keys().asSequence().associateWith { scope ->
            val array = rows.get(scope) as JSONArray
            require(array.length() <= LIMIT)
            val ids = (0 until array.length()).map { array.get(it) as String }
            validate(scope, ids)
            ids
        }
    }

    /** Old Android metadata has no history field. Explicit null or malformed fields are rejected. */
    fun read(metadata: JSONObject): Map<String, List<String>> =
        if (metadata.has("recentProjects")) decode(metadata.get("recentProjects") as JSONObject) else emptyMap()
}
