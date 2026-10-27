package com.agenthub.core

/** Presentation rules only. A renderer never grants file access or runtime control. */
object FilePreviewRules {
    const val maximumMarkdownBytes: Long = 2L * 1024 * 1024

    fun renderer(fileName: String, mediaType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mime = mediaType.substringBefore(';').trim().lowercase()
        return if (extension in listOf("md", "markdown", "mdown", "mkd") ||
            mime in listOf("text/markdown", "text/x-markdown")) "markdown" else "system"
    }

    fun canParse(sizeBytes: Long): Boolean = sizeBytes in 0..maximumMarkdownBytes

    /** Relative computer references require a separately authorized resource resolver. */
    fun linkKind(reference: String): String {
        val value = reference.trim()
        if (value.startsWith("#") && value.length > 1) return "anchor"
        val scheme = value.substringBefore(':', "").lowercase()
        if (scheme in listOf("mailto", "tel")) return "external"
        if (scheme !in listOf("https", "http")) return "unavailable"
        if (!value.substringAfter(':').startsWith("//")) return "unavailable"
        val authority = value.substringAfter("://").takeWhile { it !in "/?#" }.lowercase()
        if (authority.isEmpty() || authority.any { it in "@%\\" || it.isWhitespace() }) return "unavailable"
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]"
            else authority.substringBefore(':').trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") ||
            host in listOf("0.0.0.0", "[::1]", "[::]", "[::ffff:127.0.0.1]")) return "unavailable"
        return "web"
    }
}
