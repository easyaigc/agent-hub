package com.agenthub.core

/** Native URL parsers supply components; the destination policy is shared by both apps. */
object MessageLinkRules {
    fun destination(scheme: String?, host: String?, hasCredentials: Boolean,
        attachmentId: String?, windowsDrive: Boolean, valid: Boolean): String {
        if (windowsDrive) return "computerFile"
        if (!valid) return "unsupported"
        return when (scheme?.lowercase()) {
            "codex-attachment" -> if (!attachmentId.isNullOrEmpty()) "attachment" else "unsupported"
            "http", "https" -> {
                if (host.isNullOrEmpty() || hasCredentials) "unsupported"
                else {
                    val normalized = host.lowercase().trim('[', ']', '.')
                    if (normalized == "localhost" || normalized.endsWith(".localhost") ||
                        normalized in listOf("::1", "::", "0.0.0.0") ||
                        normalized.startsWith("127.") || normalized.startsWith("::ffff:127.")) "computerWeb"
                    else "web"
                }
            }
            "mailto", "tel" -> "external"
            null, "file", "sandbox" -> "computerFile"
            else -> "unsupported"
        }
    }
}
