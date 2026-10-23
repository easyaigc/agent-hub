package com.agenthub.core

import kotlin.io.encoding.Base64

/** Identity is independent of route, endpoint and runtime connection epoch. */
class TargetScope(
    val agentId: String,
    val accountAuthority: String?,
    val accountSubject: String?,
    val hostId: String,
    val workspaceId: String,
    val runtimeId: String,
)

class ComposerMessage(val role: String, val status: String?)

class ComposerInput(
    val stopping: Boolean,
    val running: Boolean,
    val sending: Boolean,
    val creationPending: Boolean,
    val text: String,
    val attachmentCount: Int,
    val canSend: Boolean,
    val canStop: Boolean,
    val canRunAgain: Boolean,
    val runtimeState: String?,
    val lastReceiptState: String?,
    val messages: List<ComposerMessage>,
) {
    fun withMessages(messages: List<ComposerMessage>) = ComposerInput(stopping, running, sending,
        creationPending, text, attachmentCount, canSend, canStop, canRunAgain,
        runtimeState, lastReceiptState, messages)
}

class ComposerDecision(val action: String, val enabled: Boolean)

/** Small Objective-C-compatible facade. No UI, transport or platform services. */
object MobileRules {
    fun readingKey(target: TargetScope, threadId: String?): String = Base64.encode(listOf(
        "mobile-reading-v1", target.agentId, target.accountAuthority, target.accountSubject,
        target.hostId, target.workspaceId, target.runtimeId, threadId,
    ).joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }.encodeToByteArray())

    // Hashing stays in CryptoKit/MessageDigest; byte framing is shared.
    fun contentVersionPreimage(messageId: String, text: String, status: String): String =
        listOf(messageId, text, status).joinToString("") { "${it.encodeToByteArray().size}:$it" }

    fun cacheKey(clientId: String, target: TargetScope?, threadId: String?, projectId: String?): String {
        // Local demos may omit a target. Production callers supply complete scope.
        if (target == null) return clientId + ":" + (threadId ?: "new:" + (projectId ?: "none"))
        val tuple = listOf(
            target.agentId, target.accountAuthority, target.accountSubject,
            target.hostId, target.workspaceId, target.runtimeId,
            if (threadId == null) "draft" else "thread", threadId ?: projectId,
        )
        val json = tuple.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }
        return "agent-v1:" + Base64.encode(json.encodeToByteArray())
    }

    fun showsReplyMode(running: Boolean, composable: Boolean, modesAvailable: Boolean,
        text: String, attachmentCount: Int): Boolean = running && composable && modesAvailable &&
        hasContent(text, attachmentCount)

    fun composer(input: ComposerInput): ComposerDecision {
        val action = immediateAction(input) ?: run {
            val lastUser = input.messages.indexOfLast { it.role == "user" }.takeIf { it >= 0 }
                ?: input.messages.size
            val latest = input.messages.drop(lastUser + 1)
                .lastOrNull { it.role == "assistant" || it.status == "interrupted" }
            when {
                latest != null && recoverable(latest.status) -> "resume"
                latest?.role == "assistant" && latest.status == "completed" -> "send"
                input.lastReceiptState in listOf("interrupted", "failed") -> "resume"
                else -> "send"
            }
        }
        val enabled = when (action) {
            "send" -> input.canSend
            "stop" -> input.canStop
            "resume" -> input.canRunAgain
            else -> false
        }
        return ComposerDecision(action, enabled)
    }

    // Native platforms can avoid allocating a cross-language message list on
    // every text edit. The decision to request history stays in the core.
    fun needsRecoveryHistory(input: ComposerInput): Boolean = immediateAction(input) == null

    private fun immediateAction(input: ComposerInput): String? = when {
            input.stopping -> "busy"
            // Draft presence selects the action; canSend only controls its enabled state.
            input.running -> if (hasContent(input.text, input.attachmentCount)) "send" else "stop"
            input.sending || input.creationPending -> "busy"
            hasContent(input.text, input.attachmentCount) -> "send"
            !input.canRunAgain -> "send"
            recoverable(input.runtimeState) -> "resume"
            else -> null
        }

    fun hasContent(text: String, attachmentCount: Int): Boolean =
        attachmentCount > 0 || text.any { !isBlankCharacter(it) }

    fun runAgainText(restart: Boolean): String =
        if (restart) "请重新执行上一个任务。" else "继续完成上一个任务。"

    private fun recoverable(state: String?) = state in listOf("interrupted", "failed", "paused", "cancelled")

    // Foundation's .whitespacesAndNewlines includes U+200B; Kotlin's trim does
    // not. Preserve the deployed iOS semantics in both native applications.
    private fun isBlankCharacter(c: Char): Boolean = c.code in 0x09..0x0D ||
        c.code in 0x2000..0x200B || c.code in listOf(0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

    // This is the persisted eight-string tuple codec, not a general wire JSON
    // implementation. Slash escaping matches Foundation's existing bytes.
    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '/' -> append("\\/")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
                }
            }
            append('"')
        }
    }
}
