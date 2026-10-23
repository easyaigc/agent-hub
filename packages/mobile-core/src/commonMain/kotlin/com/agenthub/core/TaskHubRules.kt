package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Business identity is independent of the route, chat runtime and editable draft. */
object TaskHubRules {
    private val phases = setOf("pending", "unknown", "confirmed", "rejected")
    fun scope(deployment: String, instance: String, host: String, actor: String, device: String): String {
        require(listOf(deployment, instance, host, actor, device).all { it.isNotEmpty() && it.length <= 256 })
        return "task-hub-v1:" + Base64.encode(JsonArray(listOf(deployment, instance, host, actor, device).map(::JsonPrimitive)).toString().encodeToByteArray())
    }
    fun currentSession(expectedScope: String, scope: String, expectedGeneration: String, generation: String,
        actorKind: String?, needsLogin: Boolean): Boolean = expectedScope == scope && expectedGeneration == generation && actorKind == "human" && !needsLogin
    fun acceptsPhase(current: String, next: String): Boolean = current in phases && next in phases &&
        !(current == "confirmed" && next != "confirmed") && !(current == "rejected" && next in setOf("pending", "unknown"))
    fun failurePhase(attempted: Boolean, status: Int): String =
        if (!attempted || status in 400..499 && status != 409) "rejected" else "unknown"
    fun validCursor(value: String): Boolean = value.isEmpty() || value.length <= 16 &&
        value.all { it in '0'..'9' } && (value.toLongOrNull() ?: Long.MAX_VALUE) <= 9007199254740991L
    fun validPath(value: String): Boolean = value.isNotEmpty() && value.length <= 2048 &&
        value.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "_-" } }
    fun receiptMatches(id: String, wire: String): Boolean = runCatching {
        if (wire.length > 4 * 1024 * 1024) return false
        val value = Json.parseToJsonElement(wire).jsonObject
        value["operationId"]?.jsonPrimitive?.content == id && value["phase"]?.jsonPrimitive?.content in phases &&
            (value["phase"] != JsonPrimitive("confirmed") || value["result"] is JsonObject)
    }.getOrDefault(false)
}

object TaskHubContract {
    private val validator by lazy { SchemaValidator(taskHubSchemaBase64) }
    fun validate(json: String, definition: String): String? =
        if (json.length > 4 * 1024 * 1024) "document-size" else validator.validate(json, definition)
}

data class TaskHubStreamEvent(val cursor: String?, val data: String)

/** Byte framing preserves blank lines and split UTF-8; a partial EOF is never an event. */
class TaskHubSSEFramer {
    private val line = ArrayList<Byte>()
    private val lines = ArrayList<String>()
    private var frameBytes = 0
    private var cursor: String? = null
    private var hasData = false
    fun consume(byte: Byte): TaskHubStreamEvent? {
        if (byte.toInt() != 10) {
            require(line.size < 256 * 1024) { "sse-line-size" }
            line.add(byte); return null
        }
        frameBytes += line.size + 1
        require(frameBytes <= 512 * 1024) { "sse-frame-size" }
        if (line.lastOrNull()?.toInt() == 13) line.removeAt(line.lastIndex)
        val value = line.toByteArray().decodeToString(throwOnInvalidSequence = true)
        line.clear()
        if (value.isEmpty()) {
            val event = if (hasData) TaskHubStreamEvent(cursor, lines.joinToString("\n")) else null
            cursor = null; hasData = false; frameBytes = 0; lines.clear()
            return event
        }
        if (value.startsWith("id:")) {
            // Match Foundation CharacterSet.whitespaces used by the existing iOS framer.
            val id = value.drop(3).trim { it.code in 0x2000..0x200a || it.code in listOf(9,32,0xa0,0x1680,0x202f,0x205f,0x3000) }
            require(TaskHubRules.validCursor(id)) { "sse-cursor" }
            cursor = id.takeIf { it.isNotEmpty() }
        }
        if (value == "data" || value.startsWith("data:")) {
            hasData = true
            lines.add(if (value == "data") "" else value.drop(5).removePrefix(" "))
        }
        return null
    }
}
