package com.agenthub.core

import kotlinx.serialization.json.*

/** Local usage load policy, never an authorization rule for commands or conversation data. */
object UsageReadRules {
    fun mayRefresh(nowMillis: Long, attemptedMillis: Long): Boolean =
        attemptedMillis == Long.MIN_VALUE || nowMillis - attemptedMillis >= 5000

    fun httpFailure(status: Int): String = when (status) {
        401 -> "http-unauthenticated"
        403 -> "http-forbidden"
        409 -> "wrong-target"
        404, 405, 501 -> "unsupported"
        408, 504 -> "timeout"
        in 500..599 -> "upstream-error"
        else -> "read-rejected"
    }
    fun remoteFailure(code: String): String = when (code) {
        "target_mismatch", "wrong_target", "invalid_target" -> "wrong-target"
        "not_authenticated", "unauthorized" -> "not-authenticated"
        "forbidden", "authorization_revoked", "grant_revoked" -> "forbidden"
        "method_unavailable", "method_not_found" -> "unsupported"
        // An arbitrary rejection is not evidence of a temporary network failure.
        else -> "read-rejected"
    }
    fun reasonKey(reason: String): String = when (reason) {
        "not-returned" -> "来源暂未提供此项额度"
        "unsupported" -> "此来源暂不支持额度读取"
        "not-authenticated" -> "额度来源尚未登录"
        "forbidden" -> "额度来源不允许读取"
        "http-unauthenticated" -> "电脑额度读取认证失败（HTTP 401）"
        "http-forbidden" -> "电脑禁止读取额度（HTTP 403）"
        "wrong-target" -> "额度运行目标已变化"
        "offline" -> "网络离线，暂时无法读取额度"
        "timeout" -> "读取额度超时"
        "upstream-error" -> "额度来源暂时不可用"
        "invalid-data" -> "额度响应无效"
        "unsupported-version" -> "额度响应版本暂不支持"
        "source-unconfirmed" -> "额度来源连续性未确认"
        "connection-unverified" -> "额度连接身份无法确认"
        "read-rejected" -> "额度读取请求被拒绝"
        else -> "额度读取未完成"
    }
    fun unavailable(reason: String): String = buildJsonObject {
        put("status", "unavailable"); put("reason", reason)
    }.toString()

    /** Strict projection with a local failure reason; never repairs malformed provider data. */
    fun projectResponse(json: String, targetJson: String, nowMillis: Long): String = try {
        require(json.encodeToByteArray().size <= 262144)
        val input = Json.parseToJsonElement(json).jsonObject
        val version = input["schemaVersion"] as? JsonPrimitive
        if (version == null || version.isString || version.doubleOrNull == null) unavailable("invalid-data")
        else if (version.doubleOrNull != 1.0) unavailable("unsupported-version")
        else if (input["target"] != Json.parseToJsonElement(targetJson)) unavailable("wrong-target")
        else UsageRules.project(json, targetJson, nowMillis)
    } catch (_: Exception) { unavailable("invalid-data") }

    /** A failed response with no source is not evidence that a wallet remains continuous. */
    fun failed(previousProjection: String?, reason: String, confirmedSourceJson: String?): String {
        val previous = try { previousProjection?.let { Json.parseToJsonElement(it).jsonObject } } catch (_: Exception) { null }
        val source = previous?.get("source") as? JsonObject
        val proof = try { confirmedSourceJson?.let { Json.parseToJsonElement(it).jsonObject } } catch (_: Exception) { null }
        val scope = source?.get("scopeRef")
        val confirmed = scope != null && scope != JsonNull && scope.jsonPrimitive.content.isNotBlank() &&
            proof?.get("scopeRef") == scope && proof["adapterId"] == source["adapterId"]
        val transient = reason in setOf("timeout", "offline", "upstream-error")
        if (transient && confirmed && previous.get("status") == JsonPrimitive("ok")) {
            return JsonObject(previous + mapOf("cached" to JsonPrimitive(true), "failureReason" to JsonPrimitive(reason))).toString()
        }
        return buildJsonObject {
            put("status", "unavailable")
            put("reason", if (transient && previous?.get("status") == JsonPrimitive("ok") && !confirmed) "source-unconfirmed" else reason)
            put("failureReason", reason)
        }.toString()
    }
}
