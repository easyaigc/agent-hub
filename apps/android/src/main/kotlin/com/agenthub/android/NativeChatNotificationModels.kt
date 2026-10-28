package com.agenthub.android

import com.agenthub.core.TargetScope
import java.time.Instant

/** Ordinary Codex notices only. Task Hub / EasyWork business notices have a separate authority. */
internal enum class NativeChatNoticeKind(val wire: String, val title: String, val fallback: String) {
    APPROVAL("approval", "待审批", "有操作需要你审批"), QUESTION("question", "待回答", "任务正在等待你的回答"), PLAN("plan", "计划待确认", "任务计划等待确认"),
    PAUSED("paused", "任务暂停", "任务已暂停，请查看当前状态"), INTERRUPTED("interrupted", "异常中断", "任务异常中断，请查看原因"), FAILED("failed", "任务失败", "任务执行失败，请查看详情"),
    UNCERTAIN("uncertain", "操作结果待确认", "请打开对话核对已提交操作是否执行，避免重复提交"), COMPLETED("completed", "任务完成", "任务已完成"),
    CONNECTION("connection", "连接异常", "电脑连接发生变化"), STOPPED("stopped", "手机主动停止", "任务已主动停止");
    companion object { fun from(wire: String) = entries.single { it.wire == wire } }
}

internal fun chatNoticeId(value: String): Boolean = value.length in 1..256 && value.all { it.code >= 32 }
internal fun chatNoticeHash(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}"))
internal fun chatNoticeSound(value: String): Boolean = value.matches(Regex("default|none|[a-zA-Z0-9_-]{1,72}\\.caf"))

/** Account is legacy metadata for Codex and deliberately does not take part in this identity. */
internal data class NativeChatNoticeTarget(val hostId: String, val workspaceId: String, val runtimeId: String) {
    init { require(listOf(hostId, workspaceId, runtimeId).all(::chatNoticeId)) }
    companion object {
        fun from(scope: TargetScope): NativeChatNoticeTarget {
            require(scope.agentId == "codex")
            return NativeChatNoticeTarget(scope.hostId, scope.workspaceId, scope.runtimeId)
        }
    }
}

/** Same v1 preference fields/defaults as iOS and packages/protocol/src/notifications.ts. */
internal data class NativeChatNotificationPreferences(
    val version: Int = 1, val revision: String = "initial", val generation: Long = 0,
    val enabled: Boolean = false,
    val kinds: Set<NativeChatNoticeKind> = NativeChatNoticeKind.entries.take(7).toSet(),
    val excludedHosts: Set<String> = emptySet(), val foregroundBanner: Boolean = true,
    val soundEnabled: Boolean = true, val badgeEnabled: Boolean = true, val showThreadTitle: Boolean = true,
    val timeSensitive: Boolean = false, val sound: String = "default",
    val categorySounds: Map<NativeChatNoticeKind, String> = emptyMap(),
) {
    init {
        require(version == 1 && chatNoticeId(revision) && generation in 0..9_007_199_254_740_991L)
        require(excludedHosts.size <= 256 && excludedHosts.all(::chatNoticeId))
        require(chatNoticeSound(sound) && categorySounds.values.all(::chatNoticeSound))
    }
    fun soundFor(kind: NativeChatNoticeKind): String = if (soundEnabled) categorySounds[kind] ?: sound else "none"
    fun permits(event: NativeChatNotificationEvent): Boolean = enabled && event.kind in kinds && event.target.hostId !in excludedHosts
}

/** A transport value, not an unread policy. P7 owns reconciliation and read watermarks. */
internal data class NativeChatNoticeReadPosition(val revision: Long, val messageId: String, val contentVersion: String) {
    init { require(revision in 1..9_007_199_254_740_991L && messageId.length in 1..512 && chatNoticeHash(contentVersion)) }
}
internal data class NativeChatNotificationEvent(
    val id: String, val target: NativeChatNoticeTarget, val kind: NativeChatNoticeKind, val occurredAt: String,
    val threadId: String? = null, val runId: String? = null, val requestId: String? = null,
    val operationId: String? = null, val threadTitle: String? = null, val taskSummary: String? = null,
    val readTarget: NativeChatNoticeTarget? = null, val readPosition: NativeChatNoticeReadPosition? = null,
) {
    init {
        require(chatNoticeHash(id)); Instant.parse(occurredAt)
        require(listOfNotNull(threadId, runId, requestId, operationId).all(::chatNoticeId))
        require(threadTitle == null || threadTitle.length <= 100)
        require(taskSummary == null || taskSummary.length in 1..160 && taskSummary == taskSummary.trim() &&
            !taskSummary.contains("  ") && taskSummary.none { it.isWhitespace() && it != ' ' ||
                Character.getType(it) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) })
        require(readTarget == null || readTarget == target)
        require(readPosition == null || threadId != null && readTarget == target && kind == NativeChatNoticeKind.COMPLETED)
    }
}

internal data class NativeChatNoticeLease(val target: NativeChatNoticeTarget, val credentialEpoch: String,
    val registrationGeneration: String, val preferenceRevision: String)
internal data class NativeChatNoticeReceipt(val event: NativeChatNotificationEvent, val lease: NativeChatNoticeLease,
    val ticket: String, val expiresAt: Long, val state: String, val consumed: Boolean = false) {
    init { require(state in setOf("claimed", "posted", "unknown")) }
}

/** P7 adapter must durably reconcile the original version before returning. No default/noop adapter. */
internal interface NativeChatNotificationReading {
    suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult
    /** Synchronous final check against the current watermark after any suspension. */
    fun mayPresent(event: NativeChatNotificationEvent): Boolean
}
internal data class NativeChatNoticeReadingResult(val shouldAlert: Boolean, val unreadConversationCount: Int) {
    init { require(unreadConversationCount >= 0) }
}
internal data class NativeChatNoticePresentation(val title: String, val summary: String, val metadata: String, val publicTitle: String) {
    companion object {
        fun create(event: NativeChatNotificationEvent, computer: String, preview: Boolean, localize: (String) -> String): NativeChatNoticePresentation {
            fun visible(value: String) = value.filterNot { Character.getType(it) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) }.trim().take(100)
            val kind = localize(event.kind.title)
            val fallbackTitle = event.threadId?.let { localize("对话") + " · " + it.take(8) } ?: localize("任务")
            return NativeChatNoticePresentation(if (preview) event.threadTitle?.let(::visible)?.takeIf { it.isNotBlank() } ?: fallbackTitle else kind,
                if (preview) event.taskSummary ?: localize(event.kind.fallback) else "", visible(computer) + " · " + kind, kind)
        }
    }
}
/** Deliberately exposes no approval, input, resume or replay operation. */
internal interface NativeChatNotificationRoute {
    suspend fun refreshExactTarget(target: NativeChatNoticeTarget, credentialEpoch: String): Boolean
    fun openPreservingDraft(event: NativeChatNotificationEvent)
}
