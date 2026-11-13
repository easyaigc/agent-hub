package com.agenthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSnapshotFenceTest {
    private data class Page(val cursor: Long, val state: String, val rows: Int, val control: Boolean = true)
    private class Conversation {
        val fence = ConversationSnapshotFence()
        var page = Page(10, "idle", 5)
            private set
        fun apply(read: Long, candidate: Page): Boolean {
            if (!fence.accepts(read, "host-epoch", candidate.cursor)) return false
            page = candidate
            fence.accepted("host-epoch", candidate.cursor)
            return true
        }
        val decision get(): ComposerDecision {
            val running = SessionPolicies.running(if (page.state == "running") "turn" else null, page.state)
            return MobileRules.composer(ComposerInput(false, running, false, false, "", 0,
                false, running && page.control, false, page.state, null, emptyList()))
        }
        val earlier get() = MessageProjection.project((0 until page.rows).map {
            TimelineMessage("row-$it", "assistant", "row $it", null, "completed", null, null, null)
        }, 40, null, page.state == "running").hasEarlierMessages
    }

    @Test fun lateHistorySuccessCannotUndoRunningSnapshotOrHideEarlierMessages() {
        val conversation = Conversation()
        assertTrue(conversation.apply(conversation.fence.beginRead(), conversation.page))
        repeat(28) { cycle ->
            val historyRead = conversation.fence.beginRead()
            val delayed = conversation.page.copy(state = "idle", rows = 5, control = false)
            assertTrue(conversation.apply(conversation.fence.beginRead(), Page(11L + cycle, "running", 120)))
            assertFalse(conversation.apply(historyRead, delayed))
            assertEquals("stop", conversation.decision.action)
            assertTrue(conversation.decision.enabled)
            assertTrue(conversation.earlier)
        }
    }

    @Test fun newerCompletionAppliesAndLateRunningEventCannotRestoreStop() {
        val conversation = Conversation()
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(20, "running", 120)))
        val eventRead = conversation.fence.beginRead()
        val historyRead = conversation.fence.beginRead()
        assertTrue(conversation.apply(historyRead, Page(22, "idle", 121)))
        assertFalse(conversation.apply(eventRead, Page(21, "running", 120)))
        assertEquals("send", conversation.decision.action)
        assertEquals(121, conversation.page.rows)
    }

    @Test fun historyNewerThanConcurrentEventCanStillCompleteTask() {
        val conversation = Conversation()
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(10, "running", 100)))
        val historyRead = conversation.fence.beginRead()
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(11, "running", 101)))
        assertTrue(conversation.apply(historyRead, Page(12, "idle", 102)))
        assertEquals("send", conversation.decision.action)
    }

    @Test fun equalCheckpointIsAcceptedButLowerCursorIsRejectedEvenWithoutConcurrency() {
        val fence = ConversationSnapshotFence()
        fence.accepted("epoch", 20)
        assertTrue(fence.accepts(fence.beginRead(), "epoch", 20))
        assertTrue(fence.accepts(fence.beginRead(), "epoch", 21))
        assertFalse(fence.accepts(fence.beginRead(), "epoch", 19))
    }

    @Test fun epochRestartDoesNotCompareUnrelatedCountersOrAcceptDelayedOldEpoch() {
        val fence = ConversationSnapshotFence()
        fence.accepted("old", 1_000)
        val delayed = fence.beginRead()
        assertTrue(fence.accepts(fence.beginRead(), "new", 1))
        fence.accepted("new", 1)
        assertFalse(fence.accepts(delayed, "old", 2_000))
        // A new bounded request may verify another genuine restart.
        assertTrue(fence.accepts(fence.beginRead(), "next", 0))
    }

    @Test fun unversionedConcurrentSuccessWaitsForANewReadRatherThanReplacingLiveProgress() {
        val fence = ConversationSnapshotFence()
        fence.accepted("", -1)
        val read = fence.beginRead()
        fence.accepted("", -1)
        assertFalse(fence.accepts(read, "", -1))
        assertTrue(fence.accepts(fence.beginRead(), "", -1))
        val versionedRead = fence.beginRead()
        fence.accepted("epoch", 10)
        assertFalse(fence.accepts(versionedRead, "", -1))
    }

    @Test fun newTargetOrNavigationFenceDoesNotCompareAnotherTargetsCursor() {
        val first = ConversationSnapshotFence()
        first.accepted("first", 1_000)
        val second = ConversationSnapshotFence()
        assertTrue(second.accepts(second.beginRead(), "second", 1))
        assertTrue(first.accepts(first.beginRead(), "first", 1_001))
    }

    @Test fun genuineCurrentReadinessLossIsNotSmoothedOver() {
        val conversation = Conversation()
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(20, "running", 120)))
        assertTrue(conversation.decision.enabled)
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(21, "running", 120, control = false)))
        assertEquals("stop", conversation.decision.action)
        assertFalse(conversation.decision.enabled)
        assertTrue(conversation.apply(conversation.fence.beginRead(), Page(22, "running", 121)))
        assertTrue(conversation.decision.enabled)
    }
}
