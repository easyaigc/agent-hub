package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativePanelNoticeLedgerTest {
    @Test fun delayedStorageChecksBothGuardsInsideTheOriginalLedgerMutation() {
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"notice-delayed-"+UUID.randomUUID())
        val ledger=NativeReadingLedger(storage);val target=TargetScope("codex",null,null,"host","workspace","runtime")
        ledger.reconcile(listOf(target),emptyList());val event=NativeReadingEvent("event",target,"thread","question",runId="run",requestId="wire")
        ledger.receive(event)
        val lifecycle=NativeReadingLifecycle().apply { foreground(true);uncovered(true);presentNotices(target,"thread",setOf(event.id)) }
        val session=NativePanelNoticeSessionFence();val view=NativePanelNoticeSessionFence()
        val frame=session.open();val visible=view.open()
        val lease=lifecycle.visibleNotices(target,"thread",setOf(event.id),NativeCombinedNoticeGuard(frame,visible))!!
        val pool=Executors.newFixedThreadPool(2);val locked=CountDownLatch(1);val release=CountDownLatch(1);val started=CountDownLatch(1)
        try {
            val lock=pool.submit { storage.operationTransaction { locked.countDown();check(release.await(10,TimeUnit.SECONDS)) } }
            assertTrue(locked.await(5,TimeUnit.SECONDS))
            val read=pool.submit<NativeUnreadSnapshot?> { started.countDown();ledger.readVisible(target,"thread","a".repeat(64),lifecycle,lease) }
            assertTrue(started.await(5,TimeUnit.SECONDS));session.revoke();view.revoke();release.countDown()
            lock.get(10,TimeUnit.SECONDS);assertNull(read.get(10,TimeUnit.SECONDS));assertTrue(ledger.mayPresent(event))
            val next=lifecycle.visibleNotices(target,"thread",setOf(event.id),NativeCombinedNoticeGuard(session.open(),view.open()))!!
            assertNotNull(ledger.readVisible(target,"thread","a".repeat(64),lifecycle,next));assertFalse(ledger.mayPresent(event))
        }finally { release.countDown();pool.shutdownNow() }
    }
}
