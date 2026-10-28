package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

/** Real encrypted native storage; synthetic positions. This is auxiliary, never a live delivery claim. */
class NativeReadingLedgerTest {
    private val target = TargetScope("codex", "old-account", "old-user", "host-a", "workspace", "runtime")
    private fun storage() = NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext, "reading-" + UUID.randomUUID())
    private fun position(revision: Long, text: String = "reply $revision", id: String = "message-$revision") =
        ThreadReadPosition(revision, id, ThreadReadPosition.contentVersion(id, text, "completed"))
    private fun latest(ledger: NativeReadingLedger, p: ThreadReadPosition, unread: Boolean = true) =
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", p, unread)))
    private fun visible(ledger: NativeReadingLedger, p: ThreadReadPosition, text: String = "reply ${p.revision}", notices: Set<String> = emptySet()) {
        val view = NativeReadingLifecycle(); view.foreground(true); view.uncovered(true); view.present(target, "thread", p, notices)
        val lease = view.visible(target, "thread", p.messageId, text, "completed", true)!!
        assertTrue("Body reading cannot carry notice IDs",lease.noticeIds.isEmpty())
        assertNotNull(ledger.readVisible(target, "thread", "a".repeat(64), view, lease))
    }
    @Test fun phoneReadSurvivesServerUnreadRepetitionAndLatePushAcrossInstances() {
        val store = storage(); val ledger = NativeReadingLedger(store); val p = position(1)
        assertEquals(1, latest(ledger, p).count); visible(ledger, p)
        assertEquals(0, latest(NativeReadingLedger(store), p).count)
        val revision = ledger.snapshot().revision
        assertEquals(0, ledger.receive(NativeReadingEvent("late", target, "thread", "completed", p)).count)
        assertEquals(revision, ledger.snapshot().revision)
        assertEquals(1, latest(ledger, position(2), false).count)
    }
    @Test fun desktopReadDoesNotClearPhoneAndWrongReaderCannotAcknowledgePending() {
        val ledger = NativeReadingLedger(storage()); val p = position(1)
        latest(ledger, p)
        val foreign = ThreadReadState(p, "b".repeat(64), p)
        assertEquals(1, ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", p, false, "a".repeat(64), foreign))).count)
        visible(ledger, p)
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", p, true, "a".repeat(64), foreign)))
        assertEquals(1, ledger.pendingReads().size)
        val local = ThreadReadState(p, "a".repeat(64), p)
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", p, true, "a".repeat(64), local)))
        assertTrue(ledger.pendingReads().isEmpty())
        val revision = ledger.snapshot().revision
        visible(ledger, p)
        assertEquals(revision, ledger.snapshot().revision)
        assertTrue(ledger.pendingReads().isEmpty())
    }
    @Test fun readingOldVisibleVersionCannotClearNewerReplyOrSameRevisionConflict() {
        val ledger = NativeReadingLedger(storage()); val p = position(1); latest(ledger, p)
        val view = NativeReadingLifecycle(); view.foreground(true); view.uncovered(true); view.present(target, "thread", p)
        val lease = view.visible(target, "thread", p.messageId, "reply 1", "completed", true)!!
        val newer = position(2); latest(ledger, newer)
        assertEquals(1, ledger.readVisible(target, "thread", "a".repeat(64), view, lease)!!.count)
        visible(ledger, newer)
        assertEquals(0, latest(ledger, position(2, "conflicting bytes"), true).count)
        assertEquals(0, latest(ledger, p).count)
    }
    @Test fun allTargetsCountOnceAndCodexAccountMetadataDoesNotCreateAnotherRow() {
        val ledger = NativeReadingLedger(storage()); val p = position(1)
        val sameMachine = TargetScope("codex", "new-account", "new-user", "host-a", "workspace", "runtime")
        val second = TargetScope("codex", null, null, "host-b", "workspace", "runtime")
        val secondRuntime = TargetScope("codex", null, null, "host-a", "workspace", "runtime-2")
        val scopes = listOf(target, sameMachine, second, secondRuntime)
        val all = ledger.reconcile(scopes, scopes.map { NativeReadingSnapshot(it, "thread", p, true) })
        assertEquals(3, all.count); assertEquals(1, all.count(target)); assertEquals(0, all.badge(false)); assertEquals(3, ledger.snapshot().count)
        val approval=NativeReadingEvent("approval",target,"thread","approval",runId="run",requestId="request")
        ledger.receive(approval)
        assertEquals(3, ledger.snapshot().count)
        visible(ledger,p,notices=setOf(approval.id))
        assertEquals("Body read preserves the unseen approval on this same row",3,ledger.snapshot().count)
        assertTrue(ledger.mayPresent(approval))
        assertFalse(ledger.mayPresent(NativeReadingEvent("completed",target,"thread","completed",p)))
        val view=NativeReadingLifecycle().apply { foreground(true);uncovered(true);present(target,"thread",null,setOf(approval.id)) }
        val noticeLease=checkNotNull(view.visibleNotices(target,"thread",setOf(approval.id)))
        assertNull(noticeLease.position);assertEquals(setOf(approval.id),noticeLease.noticeIds)
        assertEquals(2,checkNotNull(ledger.readVisible(target,"thread","a".repeat(64),view,noticeLease)).count)
        assertFalse(ledger.mayPresent(approval));assertEquals(2,ledger.snapshot().count)
        ledger.reconcile(listOf(second), emptyList()); assertEquals(1, ledger.snapshot().count)
        ledger.reconcile(scopes, emptyList()); assertEquals(2, ledger.snapshot().count)
        val otherAccount = TargetScope("easywork", "authority", "user", "host-a", "workspace", "runtime")
        val thirdAccount = TargetScope("easywork", "authority", "other", "host-a", "workspace", "runtime")
        assertNotEquals(ThreadReadingIdentity.key(otherAccount, "thread"), ThreadReadingIdentity.key(thirdAccount, "thread"))
    }
    @Test fun seenNoticeTombstoneSurvivesDuplicateAndReopenWithoutApprovingAnything() {
        val store = storage(); val ledger = NativeReadingLedger(store); ledger.reconcile(listOf(target), emptyList())
        val event = NativeReadingEvent("original-approval", target, "thread", "approval")
        ledger.receive(event)
        val view = NativeReadingLifecycle(); view.foreground(true); view.uncovered(true); view.present(target, "thread", null, setOf(event.id))
        ledger.readVisible(target, "thread", "a".repeat(64), view, view.visibleNotices(target, "thread", setOf(event.id))!!)
        assertEquals(0, NativeReadingLedger(store).receive(event).count)
        assertEquals(1, ledger.receive(event.copy(id = "second-approval")).count)
    }
    @Test fun malformedStorageIsPreservedAndNeverResetToEmpty() {
        val store = storage(); store.write(NativeReadingLedger.STORAGE_NAME, "{bad json")
        assertThrows(Exception::class.java) { NativeReadingLedger(store).reconcile(listOf(target), emptyList()) }
        assertEquals("{bad json", store.read(NativeReadingLedger.STORAGE_NAME))
    }
    @Test fun exactAcknowledgementCannotClearANewerReplyAndSchemaUsesSameParser() {
        val store = storage(); val ledger = NativeReadingLedger(store); val p = position(1)
        latest(ledger,p); visible(ledger,p); val pending = ledger.pendingReads().single()
        latest(ledger,position(2))
        val ack = JSONObject().put("hostId",target.hostId).put("threadId","thread").put("target",CodexCommandCodec.target(target))
            .put("readerId",pending.readerId).put("read",p.json())
        assertEquals(1,ledger.acknowledge(pending,ack).count); assertTrue(ledger.pendingReads().isEmpty())
        NativeReadingLedger.validateEncoded(store.read(NativeReadingLedger.STORAGE_NAME)!!)
        ack.put("threadId","different")
        assertThrows(IllegalArgumentException::class.java) { ledger.acknowledge(pending,ack) }
        assertEquals(1,ledger.snapshot().count)
    }
    @Test fun presentationQueriesUseExactEventAndPreserveSeenTombstonesWhileThreadIsUnread() {
        val store = storage(); val ledger = NativeReadingLedger(store); val old = position(1); val current = position(2)
        latest(ledger,old); visible(ledger,old)
        val notice = NativeReadingEvent("approval",target,"thread","approval",runId="run",requestId="request")
        ledger.receive(notice)
        val lifecycle = NativeReadingLifecycle(); lifecycle.foreground(true); lifecycle.uncovered(true)
        lifecycle.present(target,"thread",old,setOf(notice.id))
        ledger.readVisible(target,"thread","a".repeat(64),lifecycle,lifecycle.visibleNotices(target,"thread",setOf(notice.id))!!)
        val newEvent = NativeReadingEvent("new-reply",target,"thread","completed",current)
        ledger.receive(newEvent); ledger.receive(notice.copy(id="new-approval"))
        assertEquals(1,ledger.snapshot().count)
        val before = store.read(NativeReadingLedger.STORAGE_NAME)
        assertFalse(ledger.mayPresent(NativeReadingEvent("late-old-reply",target,"thread","completed",old)))
        assertFalse(ledger.mayPresent(notice)); assertTrue(ledger.mayPresent(newEvent))
        assertTrue(ledger.mayPresent(notice.copy(id="new-approval")))
        assertFalse(ledger.mayPresent(newEvent.copy(position=position(2,"conflict"))))
        assertFalse(ledger.mayPresent(notice.copy(id="new-approval",requestId="another")))
        assertEquals(before,store.read(NativeReadingLedger.STORAGE_NAME))
        assertFalse(ledger.mayPresent(newEvent.copy(threadId="other-thread")))
        val other = TargetScope("codex",null,null,"other-host","workspace","runtime")
        assertFalse(ledger.mayPresent(newEvent.copy(target=other)))
        ledger.reconcile(emptyList(),emptyList()); assertFalse(ledger.mayPresent(newEvent))
    }
    @Test fun independentInstancesSerializeConcurrentEventsWithoutLosingRows() {
        val store = storage(); val ledger = NativeReadingLedger(store); ledger.reconcile(listOf(target), emptyList())
        val workers = Executors.newFixedThreadPool(4)
        try {
            (1..30).map { n -> workers.submit { NativeReadingLedger(store).receive(NativeReadingEvent("event-$n", target, "thread-$n", "approval")) } }.forEach { it.get() }
            assertEquals(30, ledger.snapshot().count)
        } finally { workers.shutdownNow() }
    }
    @Test fun strictPositionRejectsFloatingStringUnknownAndOutOfRangeFields() {
        val json = position(1).json()
        for (bad in listOf(json.toString().let(::JSONObject).put("revision", "1"),
            json.toString().let(::JSONObject).put("revision", 1.5),
            json.toString().let(::JSONObject).put("revision", 0),
            json.toString().let(::JSONObject).put("extra", true))) {
            assertThrows(Exception::class.java) { ThreadReadPosition.decode(bad) }
        }
        assertEquals(position(1), ThreadReadPosition.decode(json))
    }
}
