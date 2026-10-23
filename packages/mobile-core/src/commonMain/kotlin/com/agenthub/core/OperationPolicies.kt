package com.agenthub.core

class CreationIdentity(val hostId: String, val operationId: String, val projectId: String?)
class EventDecision(val apply: Boolean, val reload: Boolean, val epoch: String, val cursor: Long)

object OperationPolicies {
    fun sameCreation(expected: CreationIdentity, received: CreationIdentity): Boolean =
        expected.hostId == received.hostId && expected.operationId == received.operationId && expected.projectId == received.projectId

    /** A transport error after the durable submit boundary never permits replay. */
    fun creationFailurePhase(current: String, mayHaveSubmitted: Boolean, rejectionStatus: Int?): String =
        if (!mayHaveSubmitted && rejectionStatus in listOf(400, 401, 403, 404, 405, 409, 413, 503)) "rejected" else current

    fun observeCreation(phase: String, currentOperation: String?, expectedOperation: String): Boolean =
        phase != "rejected" && currentOperation == expectedOperation

    /** A changed epoch requires authoritative repair, not sequence comparison across epochs. */
    fun event(previousEpoch: String, previousCursor: Long, epoch: String, cursor: Long, reset: Boolean, ready: Boolean, snapshotCount: Int = 1): EventDecision {
        if (cursor < 0) return EventDecision(false, false, previousEpoch, previousCursor)
        val changed = epoch != previousEpoch
        val apply = reset || changed || cursor > previousCursor
        return EventDecision(apply, ready && (reset || (changed && previousEpoch.isNotEmpty()) ||
            (apply && cursor > previousCursor && snapshotCount == 0)),
            if (apply) epoch else previousEpoch, if (apply) cursor else previousCursor)
    }
}
