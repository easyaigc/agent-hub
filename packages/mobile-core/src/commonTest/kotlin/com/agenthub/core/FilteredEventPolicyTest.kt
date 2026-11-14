package com.agenthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilteredEventPolicyTest {
    @Test fun otherThreadsCanAdvanceGlobalCursorWithoutReloadingSelectedHistory() {
        var cursor = 100L
        repeat(28) {
            val decision = OperationPolicies.event("host-epoch", cursor, "host-epoch", cursor + 7,
                reset = false, ready = true, snapshotCount = 0, threadScoped = true)
            assertTrue(decision.apply)
            assertFalse(decision.reload, "Cursor-only filtered batch $it must not invalidate control readiness")
            assertEquals(cursor + 7, decision.cursor)
            assertEquals("host-epoch", decision.epoch)
            cursor = decision.cursor
        }
    }

    @Test fun unfilteredEmptyGapStillRequestsAuthoritativeHistory() {
        assertTrue(OperationPolicies.event("epoch", 5, "epoch", 9, false, true, 0).reload)
    }

    @Test fun filteredResetAndEpochChangeStillRepairHistory() {
        val reset = OperationPolicies.event("epoch", 50, "epoch", 1, true, true, 0, true)
        assertTrue(reset.apply); assertTrue(reset.reload); assertEquals(1L, reset.cursor)
        val restart = OperationPolicies.event("epoch", 50, "new", 1, false, true, 0, true)
        assertTrue(restart.apply); assertTrue(restart.reload); assertEquals("new", restart.epoch)
        assertFalse(OperationPolicies.event("", 0, "epoch", 1, false, true, 0, true).reload)
    }

    @Test fun duplicateStaleAndInvalidCursorsCannotRegressSelectedHistory() {
        for (cursor in listOf(5L, 4L, -1L)) {
            val decision = OperationPolicies.event("epoch", 5, "epoch", cursor, false, true, 0, true)
            assertFalse(decision.apply); assertFalse(decision.reload)
            assertEquals("epoch", decision.epoch); assertEquals(5L, decision.cursor)
        }
        val invalidReset = OperationPolicies.event("epoch", 5, "new", -1, true, true, 0, true)
        assertFalse(invalidReset.apply); assertFalse(invalidReset.reload)
    }

    @Test fun unreadyResetWaitsForReadyRepairAndSnapshotBatchesStillApply() {
        assertFalse(OperationPolicies.event("epoch", 5, "epoch", 6, true, false, 0, true).reload)
        assertTrue(OperationPolicies.event("epoch", 6, "epoch", 6, true, true, 0, true).reload)
        val snapshot = OperationPolicies.event("epoch", 6, "epoch", 7, false, true, 1, true)
        assertTrue(snapshot.apply); assertFalse(snapshot.reload); assertEquals(7L, snapshot.cursor)
    }

    @Test fun cursorOnlyHeartbeatsDoNotRemoveBoundedHistoryCatchUp() {
        assertFalse(OperationPolicies.historyCatchUpDue(1_999, 0, 0, true, 0))
        assertTrue(OperationPolicies.historyCatchUpDue(2_000, 0, 0, true, 0))
        assertFalse(OperationPolicies.historyCatchUpDue(9_999, 0, 0, false, 0))
        assertTrue(OperationPolicies.historyCatchUpDue(10_000, 0, 0, false, 0))
    }
}
