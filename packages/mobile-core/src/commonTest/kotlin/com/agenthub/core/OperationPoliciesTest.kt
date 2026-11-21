package com.agenthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationPoliciesTest {
    @Test fun quietEventHeartbeatsDoNotReadHistoryButEpochChangesAlwaysRepair() {
        assertFalse(OperationPolicies.eventNeedsHistory("runtime", "runtime", 7, 7))
        assertTrue(OperationPolicies.eventNeedsHistory("", "runtime", 0, 0))
        assertTrue(OperationPolicies.eventNeedsHistory("old", "new", 7, 7))
        assertTrue(OperationPolicies.eventNeedsHistory("runtime", "runtime", 7, 8))
        assertTrue(OperationPolicies.eventNeedsHistory("runtime", "runtime", 7, 0))
    }

    @Test fun failedAndStaleReadsCannotBecomeAOneSecondRetryLoop() {
        for (active in listOf(false, true)) {
            val interval = if (active) 2_000L else 10_000L
            var attempted = -1L
            val attempts = mutableListOf<Long>()
            // No useful snapshot ever arrives: errors/stale fallback must still be bounded.
            for (now in 0L..60_000L step 1_000) {
                if (OperationPolicies.historyCatchUpDue(now, -1, attempted, active, 0)) {
                    attempts.add(now); attempted = now
                }
            }
            assertTrue(attempts.zipWithNext().all { (a, b) -> b - a >= interval })
            assertTrue(attempts.size == if (active) 31 else 7)
        }
    }

    @Test fun liveProgressAndInitialGraceSuppressOnlyTheRedundantCatchUp() {
        assertFalse(OperationPolicies.historyCatchUpDue(7_999, -1, -1, true, 8_000))
        assertTrue(OperationPolicies.historyCatchUpDue(8_000, -1, -1, true, 8_000))
        assertFalse(OperationPolicies.historyCatchUpDue(10_000, 9_000, 0, true, 0))
        assertTrue(OperationPolicies.historyCatchUpDue(11_000, 9_000, 0, true, 0))
        assertFalse(OperationPolicies.historyCatchUpDue(11_000, 0, 10_000, true, 0))
    }

    @Test fun filteredGlobalCursorHeartbeatKeepsReadyHistory() {
        // RuntimeSession.poll uses one host sequence but filters snapshots by threadId.
        // Other chats can advance this cursor while this valid subscription stays quiet.
        var cursor = 70L
        for (next in listOf(71L, 72L)) {
            val decision = OperationPolicies.event("host-epoch", cursor, "host-epoch", next, false, true, 0, threadScoped = true)
            assertTrue(decision.apply)
            assertEquals("host-epoch", decision.epoch)
            assertEquals(next, decision.cursor)
            assertFalse(decision.reload, "A valid thread-filtered empty batch must not discard ready history")
            cursor = decision.cursor
        }
        val laterSnapshot = OperationPolicies.event("host-epoch", cursor, "host-epoch", 73, false, true, 1)
        assertTrue(laterSnapshot.apply)
        assertFalse(laterSnapshot.reload)
    }

    @Test fun emptyBatchStillRepairsExplicitResetAndChangedEpoch() {
        val reset = OperationPolicies.event("host-epoch", 70, "host-epoch", 72, true, true, 0)
        assertTrue(reset.apply)
        assertTrue(reset.reload)
        val restarted = OperationPolicies.event("host-epoch", 70, "new-epoch", 1, false, true, 0)
        assertTrue(restarted.apply)
        assertTrue(restarted.reload)
        val unavailable = OperationPolicies.event("host-epoch", 70, "new-epoch", 1, true, false, 0)
        assertTrue(unavailable.apply)
        assertFalse(unavailable.reload)
        val duplicate = OperationPolicies.event("host-epoch", 70, "host-epoch", 70, false, true, 0)
        assertFalse(duplicate.apply)
        assertFalse(duplicate.reload)
    }

}
