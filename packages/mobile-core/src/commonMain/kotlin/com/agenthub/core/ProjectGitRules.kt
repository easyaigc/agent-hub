package com.agenthub.core

import kotlinx.serialization.json.*

/** Four-field domain identity; the iOS provider separately fences the full EasyWork authorization target. No Android consumer is added. */
object ProjectGitRules {
    private val validator by lazy { SchemaValidator(projectGitSchemaBase64) }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun validate(json: String, targetJson: String, projectId: String, definition: String): JsonObject {
        require(json.encodeToByteArray().size <= 131072)
        require(validator.validate(json, definition) == null)
        val input = Json.parseToJsonElement(json).jsonObject
        val target = Json.parseToJsonElement(targetJson).jsonObject
        require(validator.validate(targetJson, "target") == null && input["target"] == target)
        require(input.text("projectId") == projectId)
        return input
    }
    @Throws(Exception::class)
    fun project(json: String, targetJson: String, projectId: String): String {
        val input = validate(json, targetJson, projectId, "response")
        val repos = input.getValue("repositories").jsonArray
        val ids = mutableSetOf<String>()
        var count = 0
        val rows = repos.map { repo ->
            val r = repo.jsonObject
            val worktrees = r.getValue("worktrees").jsonArray.map { item ->
                val tree = item.jsonObject
                require(ids.add(tree.text("id")!!) && ++count <= 32)
                val changes = tree["changes"] as? JsonObject
                if(tree.text("state") == "ready") require(changes != null)
                for(name in listOf("upstream", "publish", "baseline")) {
                    val comparison = tree.getValue(name).jsonObject
                    if(comparison.text("state") == "ready") require(comparison["ahead"] != JsonNull && comparison["behind"] != JsonNull && comparison["reason"] == JsonNull && comparison["ref"] != JsonNull)
                    else require(comparison["ahead"] == JsonNull && comparison["behind"] == JsonNull && comparison["reason"] != JsonNull)
                }
                val metrics = mutableListOf<JsonElement>()
                fun metric(kind: String, symbol: String, value: JsonElement?) {
                    metrics += buildJsonObject { put("kind",kind);put("symbol",symbol);put("value",value?.jsonPrimitive?.contentOrNull ?: "—") }
                }
                if(changes != null) {
                    metric("changed","square.and.pencil",changes["changed"])
                    metric("unpublished","icloud.and.arrow.up",tree.getValue("publish").jsonObject["ahead"])
                    val conflicts = changes.getValue("conflicts").jsonPrimitive.int
                    if(conflicts > 0) metric("conflicts","exclamationmark.triangle",changes["conflicts"])
                    else metric("ahead","arrow.up",tree.getValue("baseline").jsonObject["ahead"])
                    metric("behind","arrow.down",tree.getValue("baseline").jsonObject["behind"])
                }
                JsonObject(tree + ("metrics" to JsonArray(metrics)))
            }
            JsonObject(r + ("worktrees" to JsonArray(worktrees)))
        }
        if(input.text("kind") == "non-git") require(input.getValue("complete").jsonPrimitive.boolean && repos.isEmpty() && input.getValue("issues").jsonArray.isEmpty())
        return JsonObject(input + ("repositories" to JsonArray(rows))).toString()
    }
    @Throws(Exception::class)
    fun files(json: String, targetJson: String, projectId: String, snapshotId: String, worktreeId: String): String {
        val input = validate(json,targetJson,projectId,"filesResponse")
        require(input.text("snapshotId") == snapshotId && input.text("worktreeId") == worktreeId)
        val ids = input.getValue("files").jsonArray.map { it.jsonObject.text("id") }
        require(ids.toSet().size == ids.size)
        return input.toString()
    }
}
