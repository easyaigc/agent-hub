package com.agenthub.core

/** Restores reading cache only. Never constitutes runtime or command evidence. */
object HistoryCacheRecovery {
    fun canKeepPrefix(cachedWindowed: Boolean, incomingWindowed: Boolean,
        cachedRevision: Long, incomingRevision: Long, hasOverlap: Boolean): Boolean =
        hasOverlap && cachedWindowed == incomingWindowed &&
            (!incomingWindowed || cachedRevision >= 0 && cachedRevision == incomingRevision)

    fun visibleLimit(saved: Long, messageCount: Long): Long =
        saved.coerceAtLeast(ReadingPolicy.initialMessageLimit()).coerceAtMost(
            messageCount.coerceAtLeast(ReadingPolicy.initialMessageLimit()))
}
