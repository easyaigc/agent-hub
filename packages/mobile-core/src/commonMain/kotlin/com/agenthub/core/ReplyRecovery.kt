package com.agenthub.core

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.serialization.json.*

/** Immutable payload equality is deliberately stricter than machine/storage identity. */
object ReplyRecoveryRules {
    private val targetFields = listOf("agentId", "hostId", "workspaceId", "runtimeId")
    private val requiredReply = listOf("hostId", "threadId", "clientMessageId", "text", "attachmentIds", "mode")
    private val optionalReply = listOf("expectedTurnId", "model", "effort", "permissionMode", "speed", "collaborationMode", "expectedPlanId")
    private val digest = Regex("^[a-f0-9]{64}$")
    private val uuid = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")

    /** Exact ordered v2 JSON consumed by Host SHA-256; hashing stays in platform crypto. */
    fun canonicalPayload(targetJson: String, replyJson: String): String? = runCatching {
        require(targetJson.length <= 16_384 && replyJson.length <= 1_000_000)
        require(CodexHttpContract.validate(targetJson, "target") == null)
        require(CodexHttpContract.validate(replyJson, "replyRequest") == null)
        val target = Json.parseToJsonElement(targetJson).jsonObject
        val reply = Json.parseToJsonElement(replyJson).jsonObject
        require(target["hostId"] == reply["hostId"])
        require(optionalReply.all { name -> reply[name]?.let { it is JsonPrimitive && it.isString } != false })
        val orderedTarget = buildJsonObject {
            for (name in targetFields) put(name, target.getValue(name))
            target["account"]?.jsonObject?.let { account ->
                put("account", buildJsonObject { put("authority", account.getValue("authority")); put("subject", account.getValue("subject")) })
            }
        }
        val orderedReply = buildJsonObject {
            for (name in requiredReply) put(name, reply.getValue(name))
            for (name in optionalReply) reply[name]?.let { put(name, it) }
        }
        buildJsonObject { put("version", 2); put("target", orderedTarget); put("reply", orderedReply) }.toString()
    }.getOrNull()

    /** Receipt absence, HTTP status and current owner readiness never constitute this proof. */
    fun strongNotAccepted(inspectionJson: String, capturedTargetJson: String,
        hostId: String, threadId: String, operationId: String, fingerprint: String, nowEpochMillis: Long): Boolean = runCatching {
        require(inspectionJson.length <= 65_536 && digest.matches(fingerprint))
        require(CodexHttpContract.validate(inspectionJson, "replyInspection") == null)
        require(CodexHttpContract.validate(capturedTargetJson, "target") == null)
        val expected = Json.parseToJsonElement(capturedTargetJson).jsonObject
        val value = Json.parseToJsonElement(inspectionJson).jsonObject
        fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
        require(expected.text("agentId") == "codex" && expected.text("hostId") == hostId)
        require(value.text("hostId") == hostId && value.text("threadId") == threadId && value.text("clientMessageId") == operationId)
        require(value["target"] == expected && value["capturedTarget"] == expected)
        require(value.text("status") == "not-accepted" && value["receipt"] == JsonNull)
        require(value.getValue("fingerprintVersion").jsonPrimitive.doubleOrNull == 2.0 && value.text("fingerprint") == fingerprint)
        val evidence = value.getValue("evidence").jsonObject
        require(evidence.text("kind") == "operator-fenced-checkpoint-v2" && evidence.text("fingerprint") == fingerprint)
        val oldPid = evidence.getValue("oldPid").jsonPrimitive.double.toLong()
        val newPid = evidence.getValue("newPid").jsonPrimitive.double.toLong()
        require(oldPid > 0 && newPid > 0 && oldPid != newPid)
        require(uuid.matches(evidence.text("ledgerId")))
        require(listOf("ledgerSha256", "fenceSha256").all { digest.matches(evidence.text(it)) })
        val observed = Instant.parse(value.text("observedAt")).toEpochMilliseconds()
        require(abs(nowEpochMillis.toDouble() - observed.toDouble()) <= 60_000)
        true
    }.getOrDefault(false)

    fun mayRetry(phase: String, consumed: Boolean, intentMatches: Boolean, strongProof: Boolean): Boolean =
        !consumed && intentMatches && (phase == "not-written" || phase == "strong-not-accepted" && strongProof)

    fun mayContinueCreation(phase: String, submissionAttempted: Boolean, diagnosticDisposition: String?,
        preparationPhase: String? = null, preparedThreadId: String? = null): Boolean =
        phase == "prepared" && !submissionAttempted ||
            phase == "outcome-unknown" && submissionAttempted && diagnosticDisposition == "retry-original-preflight" &&
            preparationPhase == "input-retryable" && !preparedThreadId.isNullOrBlank()
}
