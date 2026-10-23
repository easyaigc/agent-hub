package com.agenthub.core

import kotlinx.serialization.json.*

/** Version-one bytes are immutable even when Codex's account is only metadata. */
object PrivateConnectionRules {
    const val sourceLimit = 4
    const val sourceEndpointLimit = 32
    const val peerLimit = 32
    const val probeLimit = 128

    fun context(target: TargetScope, publicKey: String): String = listOf(
        "agent-hub-codex-private-connection-v1", target.agentId, target.hostId,
        target.workspaceId, target.runtimeId, target.accountAuthority.orEmpty(),
        target.accountSubject.orEmpty(), publicKey,
    ).joinToString("") { "${it.length}:$it" }

    fun capturedTargetMatches(a: TargetScope, b: TargetScope): Boolean =
        RuntimeIdentity.matches(a, b) && a.accountAuthority == b.accountAuthority && a.accountSubject == b.accountSubject

    fun replyMatches(json: String, captured: TargetScope, publicKey: String): Boolean = try {
        if (CodexDiscoveryContract.validateNamed(json, "privateReply") != null) false else {
            val reply=Json.parseToJsonElement(json).jsonObject
            val target=reply.getValue("target").jsonObject
            val account=target["account"]?.jsonObject
            val scope=TargetScope(target.getValue("agentId").jsonPrimitive.content,account?.get("authority")?.jsonPrimitive?.content,account?.get("subject")?.jsonPrimitive?.content,
                target.getValue("hostId").jsonPrimitive.content,target.getValue("workspaceId").jsonPrimitive.content,target.getValue("runtimeId").jsonPrimitive.content)
            capturedTargetMatches(scope,captured) && reply.getValue("publicKey").jsonPrimitive.content==publicKey
        }
    } catch (_: Exception) { false }

    fun isVPNAddress(value: String): Boolean {
        val ipv4 = value.split('.')
        if (ipv4.size == 4 && ipv4.all { it.matches(Regex("[0-9]{1,3}")) && it.toIntOrNull()?.let { n -> n in 0..255 } == true })
            return ipv4[0].toInt() == 100 && ipv4[1].toInt() in 64..127
        if (!value.contains(':') || value.contains('%') || !value.matches(Regex("[0-9a-fA-F:]+"))) return false
        val halves = value.lowercase().split("::")
        if (halves.size > 2) return false
        if (halves.any { it.isNotEmpty() && !it.matches(Regex("[0-9a-f]{1,4}(:[0-9a-f]{1,4})*")) }) return false
        val left = halves[0].split(':').filter(String::isNotEmpty)
        val right = if (halves.size == 2) halves[1].split(':').filter(String::isNotEmpty) else emptyList()
        if ((left + right).any { it.length !in 1..4 || it.toIntOrNull(16) == null }) return false
        if (halves.size == 1 && left.size != 8 || halves.size == 2 && left.size + right.size >= 8) return false
        if (halves.size == 1 && (value.startsWith(':') || value.endsWith(':'))) return false
        val words = left.map { it.toInt(16) } + List(if (halves.size == 2) 8 - left.size - right.size else 0) { 0 } + right.map { it.toInt(16) }
        return words.take(3) == listOf(0xfd7a, 0x115c, 0xa1e0)
    }
}
