package com.agenthub.core

/** Routes never participate in machine identity or submitted-operation identity. */
object CodexRouteRules {
    fun systemEndpoint(value: String): String? {
        if (value.length > 512) return null
        val match = Regex("^https://((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+ts\\.net)(?::([0-9]{1,5}))?/?$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
        val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (port != null && port !in 1..65535) return null
        return "https://" + match.groupValues[1].lowercase() + if (port == null || port == 443) "" else ":$port"
    }
    fun mergeSystemEndpoints(existing: List<String>, supplied: List<String>, legacy: String?): List<String> =
        (existing + listOfNotNull(legacy) + supplied).mapNotNull(::systemEndpoint).distinct().take(4)
}
