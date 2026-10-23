// Generated from packages/protocol/fixtures/mobile/reading-policy.json; edit the definition, then regenerate.
package com.agenthub.core

object ReadingPolicy {
    fun nextRevision(previousRevision: Long, sameVersion: Boolean): Long = (if (sameVersion) previousRevision else (previousRevision + 1))
    fun reconciledUnread(previousUnread: Boolean, hadLatest: Boolean, latestAccepted: Boolean, latestChanged: Boolean, sourceUnread: Boolean, readSettles: Boolean): Boolean = (if (readSettles) false else (if (latestAccepted) (if (hadLatest) (latestChanged || previousUnread) else (previousUnread || sourceUnread)) else previousUnread))
    fun newPushUnread(readSame: Boolean, readRevision: Long, latestRevision: Long): Boolean = ((!readSame) && (readRevision < latestRevision))
    fun preserveHistoryPrefix(windowed: Boolean, hasPaged: Boolean, sameGeneration: Boolean, sameRevision: Boolean): Boolean = ((windowed && hasPaged) && (sameGeneration && sameRevision))
    fun initialMessageLimit(): Long = 20
    fun earlierMessageIncrement(): Long = 60
}
