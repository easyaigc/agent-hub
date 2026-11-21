package com.agenthub.android

/** Shared by blocking/async and plain/secret questions; nonblank text is submitted verbatim. */
internal fun approvalQuestionAnswers(selected: List<String>, customText: String, async: Boolean): List<String> {
    if (customText.isBlank()) return selected.toList()
    return if (async) listOf(customText) else selected + customText
}
