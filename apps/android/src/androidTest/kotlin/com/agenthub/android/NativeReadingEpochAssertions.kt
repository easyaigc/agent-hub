package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executes the production ledger/identity/shared policy. Native uses real encrypted storage;
 * the host adapter exercises the same assertions with an explicitly synthetic transactional IO port. */
internal object NativeReadingEpochAssertions {
    private val a=TargetScope("codex","historical-account","historical-subject","epoch-host","workspace","runtime-a")
    private val b=TargetScope("codex",null,null,"epoch-host","workspace","runtime-b")
    private val reader="a".repeat(64)
    private fun position(n: Long)=ThreadReadPosition(n,"message-$n",ThreadReadPosition.contentVersion("message-$n","reply-$n","completed"))
    private fun row(target: TargetScope,n: Long)=NativeReadingSnapshot(target,"thread",position(n),true)
    private fun read(ledger: NativeReadingLedger,n: Long,notices: Set<String> = emptySet()) {
        val p=position(n);val view=NativeReadingLifecycle().apply {foreground(true);uncovered(true);present(a,"thread",p,notices)}
        assertNotNull(ledger.readVisible(a,"thread",reader,view,checkNotNull(view.visible(a,"thread",p.messageId,"reply-$n","completed",true))))
        if(notices.isNotEmpty())assertNotNull(ledger.readVisible(a,"thread",reader,view,checkNotNull(view.visibleNotices(a,"thread",notices))))
    }
    private fun queued(store: NativeSessionStorage,workers: NativeReadingEpochWorkers,action: ()->Unit,invalidateAndRestore: ()->Unit) {
        val attempted=CountDownLatch(1);val worker=workers.create();var failure: Throwable?=null
        try {
            val future=store.operationTransaction {
                val result=worker.submit {attempted.countDown();action()}
                assertTrue(attempted.await(5,TimeUnit.SECONDS));invalidateAndRestore();result
            }
            future.get(10,TimeUnit.SECONDS)
        }catch(error: Throwable) {failure=error;throw error}
        finally {worker.awaitExit(failure)} // outside operationTransaction: the non-interruptible lock is released
    }
    fun removedRuntimeScopeCannotBeRestoredByQueuedOwner(store: NativeSessionStorage,workers: NativeReadingEpochWorkers=NativeReadingEpochWorkers("host-removed-runtime")) {
        val ledger=NativeReadingLedger(store);val fence=NativeReadingWriteFence();val old=fence.capture()
        ledger.reconcile(listOf(a),listOf(row(a,1)));read(ledger,1)
        val pending=ledger.pendingReads().single()
        var restored: String?=null;var revision=0L
        queued(store,workers,{ledger.reconcileOwned(listOf(a),listOf(row(a,2)),old)}) {
            fence.invalidate()
            val snapshot=ledger.reconcileOwned(listOf(b),listOf(row(b,1)),fence.capture())
            assertEquals(1,snapshot.count);assertEquals(0,snapshot.count(a));assertEquals(1,snapshot.count(b))
            restored=store.read(NativeReadingLedger.STORAGE_NAME);revision=snapshot.revision
        }
        assertEquals("Stale input cannot change any persisted ledger bytes",restored,store.read(NativeReadingLedger.STORAGE_NAME))
        val reopened=NativeReadingLedger(store);assertEquals(revision,reopened.snapshot().revision)
        assertEquals(0,reopened.snapshot().count(a));assertEquals(1,reopened.snapshot().count(b))
        assertTrue(reopened.pendingReads().isEmpty()) // removed scope is excluded; the read is not deleted
        val active=reopened.reconcileOwned(listOf(a,b),emptyList(),fence.capture())
        assertEquals(1,active.count);assertFalse(active.isUnread(a,"thread"));assertTrue(active.isUnread(b,"thread"))
        val kept=reopened.pendingReads().single()
        assertEquals(pending.position,kept.position);assertEquals(pending.readerId,kept.readerId);assertEquals(pending.threadId,kept.threadId)
        assertEquals(ThreadReadingIdentity.key(pending.target,pending.threadId),ThreadReadingIdentity.key(kept.target,kept.threadId))
        assertEquals(1,reopened.receive(NativeReadingEvent("old-completion",a,"thread","completed",position(1))).count)
    }
    fun restoredReadAndSeenNoticeCannotBeRevivedByQueuedOwner(store: NativeSessionStorage,workers: NativeReadingEpochWorkers=NativeReadingEpochWorkers("host-restored-read")) {
        val ledger=NativeReadingLedger(store);val fence=NativeReadingWriteFence();val old=fence.capture()
        val event=NativeReadingEvent("original-question",a,"thread","question",runId="run",requestId="question")
        ledger.reconcile(listOf(a),listOf(row(a,1)));ledger.receive(event);read(ledger,1,setOf(event.id))
        var restored: String?=null;var revision=0L
        queued(store,workers,{ledger.reconcileOwned(listOf(a),listOf(row(a,3)),old)}) {
            fence.invalidate();ledger.reconcileOwned(listOf(a),listOf(row(a,2)),fence.capture());read(ledger,2)
            assertEquals(0,ledger.snapshot().count);restored=store.read(NativeReadingLedger.STORAGE_NAME);revision=ledger.snapshot().revision
        }
        assertEquals(restored,store.read(NativeReadingLedger.STORAGE_NAME))
        val reopened=NativeReadingLedger(store);assertEquals(revision,reopened.snapshot().revision)
        assertEquals(position(2),reopened.pendingReads().single().position)
        assertEquals(0,reopened.receive(event).count);assertFalse(reopened.mayPresent(event))
        assertEquals(0,reopened.receive(NativeReadingEvent("late-old",a,"thread","completed",position(1))).count)
        assertEquals(revision,reopened.snapshot().revision)
        assertEquals(1,reopened.receive(NativeReadingEvent("fresh",a,"thread","completed",position(3))).count)
        assertEquals(position(2),reopened.pendingReads().single().position)
        assertEquals(0,reopened.snapshot().badge(false));assertEquals(1,reopened.snapshot().count)
    }
}
