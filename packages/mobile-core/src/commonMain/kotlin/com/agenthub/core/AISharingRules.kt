package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Permission selects an exact computer/model. It never stores or replays an action. */
object AISharingRules {
    fun model(value: String?): String = value?.takeIf { it.isNotEmpty() } ?: "follow-computer"
    fun matches(a: TargetScope, aModel: String, b: TargetScope, bModel: String): Boolean =
        RuntimeIdentity.matches(a, b) && aModel == bModel
    fun requiresContentGrant(decision: String): Boolean = decision !in listOf("decline", "deny", "cancel", "abort")
    fun key(target: TargetScope, model: String): String {
        require(model.isNotEmpty() && model.length <= 2048)
        val identity = if (target.agentId == "codex") listOf(target.agentId, target.hostId, target.workspaceId, target.runtimeId)
            else listOf(target.agentId, target.hostId, target.workspaceId, target.runtimeId, target.accountAuthority, target.accountSubject)
        return "ai-sharing-v1:" + Base64.encode(JsonArray((identity + model).map { it?.let(::JsonPrimitive) ?: JsonNull }).toString().encodeToByteArray())
    }
}

/** Original recording manifests remain stricter than connection identity. */
object VoiceTranscriptionRules {
    const val maxBytes: Int = 12 * 1024 * 1024
    const val maxDurationMs: Int = 300000
    private val fields = setOf("hostId", "threadId", "draftId", "operationId", "mediaType", "sizeBytes", "sha256", "durationMs")
    private fun manifest(value: JsonObject): Boolean = value.keys == fields &&
        listOf("hostId", "draftId").all { name -> value[name]?.let { it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 2048 } == true } &&
        value["threadId"]?.let { it == JsonNull || it is JsonPrimitive && it.isString && it.content.isNotEmpty() && it.content.length <= 256 } == true &&
        value["operationId"]?.let { it is JsonPrimitive && it.isString && Regex("^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$").matches(it.content) } == true &&
        value["mediaType"] == JsonPrimitive("audio/wav") &&
        value["sha256"]?.let { it is JsonPrimitive && it.isString && Regex("^[a-f0-9]{64}$").matches(it.content) } == true &&
        integer(value["sizeBytes"], 44, maxBytes) && integer(value["durationMs"], 1, maxDurationMs)
    private fun integer(v: JsonElement?, low: Int, high: Int): Boolean = v is JsonPrimitive && !v.isString && v.doubleOrNull?.let { it.isFinite() && it % 1.0 == 0.0 && it >= low && it <= high } == true
    fun validateManifest(json: String): Boolean = runCatching { json.length <= 16384 && manifest(Json.parseToJsonElement(json).jsonObject) }.getOrDefault(false)
    fun receiptMatches(expected: String, receipt: String): Boolean = runCatching {
        require(expected.length <= 16384 && receipt.length <= 2_000_000)
        val original = Json.parseToJsonElement(expected).jsonObject
        val response = Json.parseToJsonElement(receipt).jsonObject
        require(manifest(original) && response.keys == fields + setOf("phase", "text", "error", "expiresAt"))
        require(manifest(JsonObject(response.filterKeys { it in fields })))
        require(fields.all { name -> if(name in listOf("sizeBytes", "durationMs")) response[name]?.jsonPrimitive?.double == original[name]?.jsonPrimitive?.double else response[name] == original[name] })
        require(response["phase"]?.jsonPrimitive?.content in listOf("awaiting-audio", "transcribing", "completed", "failed", "outcome-unknown"))
        require(listOf("text", "error").all { name -> response[name]?.let { it == JsonNull || it is JsonPrimitive && it.isString } == true })
        require(response["phase"] != JsonPrimitive("completed") || response["text"] is JsonPrimitive && response["text"] != JsonNull)
        kotlin.time.Instant.parse(response.getValue("expiresAt").jsonPrimitive.content)
        true
    }.getOrDefault(false)
}
