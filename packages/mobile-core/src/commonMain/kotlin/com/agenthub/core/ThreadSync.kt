package com.agenthub.core

import kotlinx.serialization.json.*

internal data class SyncVersion(val id: String, val hash: String, val wire: JsonObject)

/** Immutable, session-local wire state. Platform code commits only after checking text digests. */
class ThreadSyncState internal constructor(
    internal val versions: List<SyncVersion>,
    internal val messages: Map<String, JsonObject>,
    internal val branchRevision: Long?,
)

class ThreadSyncTextCheck internal constructor(val text: String, val sha256: String)
class ThreadSyncMerge internal constructor(
    val next: ThreadSyncState,
    val wireJson: String,
    val textChecks: List<ThreadSyncTextCheck>,
)

/** EWDAP thread/sync reconciliation; account/transport, SHA-256 and locking stay native. */
object ThreadSyncRules {
    fun empty(): ThreadSyncState = ThreadSyncState(emptyList(), emptyMap(), null)

    fun parameters(state: ThreadSyncState, threadId: String): String = buildJsonObject {
        put("threadId", threadId)
        put("known", JsonArray(state.versions.map { it.wire }))
        state.branchRevision?.let { put("branchRevision", it) }
    }.toString()

    private fun JsonElement.string(): String = (this as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonElement.integer(): Long {
        val value = (this as JsonPrimitive).also { require(!it.isString) }.double
        require(value.isFinite() && value >= 0 && value <= 9007199254740991.0 && value % 1.0 == 0.0)
        return value.toLong()
    }

    fun merging(state: ThreadSyncState, resultJson: String, threadId: String): ThreadSyncMerge? = try {
        merge(state, Json.parseToJsonElement(resultJson).jsonObject, threadId)
    } catch (_: Exception) { null }

    private fun merge(state: ThreadSyncState, result: JsonObject, threadId: String): ThreadSyncMerge {
        require(result.getValue("snapshot").jsonObject.getValue("thread").jsonObject.getValue("id").string() == threadId)
        val changed = result.getValue("messages").jsonArray.map { it.jsonObject }
        if (!result.containsKey("head")) {
            require((result["patches"]?.jsonArray ?: JsonArray(emptyList())).isEmpty())
            return ThreadSyncMerge(empty(), result.toString(), emptyList())
        }
        val rawHead = result.getValue("head").jsonArray
        require(rawHead.size <= 500)
        val head = rawHead.map { item ->
            val value = item.jsonObject
            val id = value.getValue("id").string()
            val hash = value.getValue("hash").string()
            require(id.isNotEmpty() && hash.isNotEmpty() && hash.length <= 128)
            for (field in listOf("textHash", "shapeHash")) value[field]?.takeUnless { it is JsonNull }?.string()
            value["textLength"]?.takeUnless { it is JsonNull }?.integer()
            val fields = value.filterKeys { it in setOf("id", "hash", "textLength", "textHash", "shapeHash") }
            SyncVersion(id, hash, JsonObject(fields))
        }
        require(head.map { it.id }.distinct().size == head.size)
        val revision = result.getValue("branchRevision").integer()
        val resetValue = result.getValue("reset").jsonPrimitive
        require(!resetValue.isString)
        val reset = resetValue.boolean
        require(reset || state.branchRevision == null || state.branchRevision == revision)
        val patches = result.getValue("patches").jsonArray
        val ids = head.map { it.id }.toSet()
        val oldVersions = state.versions.associateBy { it.id }
        val resolved = if (reset) mutableMapOf() else state.messages.toMutableMap()
        val changedIds = mutableSetOf<String>()
        for (message in changed) {
            val id = message.getValue("id").string()
            require(id in ids && changedIds.add(id))
            resolved[id] = message
        }
        for (raw in patches) {
            val patch = raw.jsonObject
            val id = patch.getValue("id").string()
            require(!reset && id in ids && changedIds.add(id))
            require(oldVersions[id]?.hash == patch.getValue("baseHash").string())
            val append = patch.getValue("append").string()
            val message = resolved.getValue(id)
            val parts = message.getValue("content").jsonArray
            require(parts.size == 1)
            val part = parts.single().jsonObject
            require(part.getValue("type").string() == "text")
            val updatedPart = JsonObject(part + ("text" to JsonPrimitive(part.getValue("text").string() + append)))
            val updated = message.toMutableMap()
            updated["content"] = JsonArray(listOf(updatedPart))
            patch["status"]?.let { status ->
                require(status.string() in setOf("complete", "partial", "interrupted", "failed"))
                updated["status"] = status
            }
            resolved[id] = JsonObject(updated)
        }
        val checks = mutableListOf<ThreadSyncTextCheck>()
        val ordered = head.map { version ->
            val message = resolved.getValue(version.id)
            require(version.id in changedIds || oldVersions[version.id]?.hash == version.hash)
            val verifiedUnchanged = !reset && version.id !in changedIds && oldVersions[version.id] == version
            version.wire["textLength"]?.takeUnless { it is JsonNull || verifiedUnchanged }?.let { length ->
                val parts = message.getValue("content").jsonArray
                require(parts.size == 1)
                val part = parts.single().jsonObject
                require(part.getValue("type").string() == "text")
                val text = part.getValue("text").string()
                require(text.length.toLong() == length.integer())
                checks.add(ThreadSyncTextCheck(text, version.wire.getValue("textHash").string()))
            }
            message
        }
        val wire = JsonObject(result + ("messages" to JsonArray(ordered))).toString()
        val next = ThreadSyncState(head, head.map { it.id }.zip(ordered).toMap(), revision)
        return ThreadSyncMerge(next, wire, checks)
    }
}
