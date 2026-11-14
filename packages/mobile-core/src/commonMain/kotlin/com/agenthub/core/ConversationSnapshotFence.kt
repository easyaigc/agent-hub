package com.agenthub.core

/** Main-thread ordering for one complete target and navigation/connection generation. */
class ConversationSnapshotFence {
    private var revision = 0L
    private var epoch = ""
    private var cursor = -1L

    fun beginRead(): Long = revision

    /** Optional wire versions use an empty epoch / -1 cursor. Epochs are not ordered. */
    fun accepts(readRevision: Long, incomingEpoch: String, incomingCursor: Long): Boolean {
        if (revision == 0L) return true
        if (epoch.isNotEmpty() && epoch == incomingEpoch && cursor >= 0 && incomingCursor >= 0) {
            return incomingCursor >= cursor
        }
        // Legacy or restarted providers cannot prove a concurrent response is newer.
        // A subsequent bounded read can still establish the new checkpoint.
        return readRevision == revision
    }

    /** Called only after target validation and successful latest-snapshot publication. */
    fun accepted(incomingEpoch: String, incomingCursor: Long) {
        revision++
        epoch = incomingEpoch
        cursor = incomingCursor
    }
}
