package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Production ownership gate with a controlled writer; not Android disk/fsync evidence. */
class ConnectionWriteGateTest {
    private class Setup {
        val authority=EasyWorkConnectionAuthority();var origin=EasyWorkConnectionOrigin("target","1","original","a")
        val form=authority.form {origin};val action=form.begin(EasyWorkConnectionInput("code","name","endpoint","input"))
        val gate=EasyWorkConnectionWriteGate();val disk=ReentrantLock();var value: String?="original"
    }
    @Test fun mainRevocationAndNewDraftReturnWhileProductionGateWriterIsHeldAfterWrite() {
        val s=Setup();val entered=CountDownLatch(1);val allow=CountDownLatch(1);val revoked=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        var selected=false
        try {
            val write=pool.submit {s.disk.withLock {s.gate.write("key","candidate",s.action,{s.value}) {v->s.value=v;if(v=="candidate"){entered.countDown();check(allow.await(3,TimeUnit.SECONDS))}}}}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val main=pool.submit {s.form.close();s.origin=s.origin.copy(draftRevision="new-unsent-draft");revoked.countDown()}
            assertTrue("Main revocation must return before held writer is released",revoked.await(1,TimeUnit.SECONDS));assertFalse(write.isDone)
            allow.countDown();val failure=assertThrows(ExecutionException::class.java) {write.get(3,TimeUnit.SECONDS)};assertTrue(failure.cause is EasyWorkConnectionAbandoned);main.get(3,TimeUnit.SECONDS)
            assertEquals("original",s.value);assertEquals("new-unsent-draft",s.origin.draftRevision)
            assertThrows(EasyWorkConnectionAbandoned::class.java) {s.action.commit {selected=true}};assertFalse(selected)
        }finally {allow.countDown();pool.shutdownNow()}
    }
    @Test fun newerIdenticalWriteOwnsItsBytesAndOldRollbackCannotRestoreTheOldBaseline() {
        val s=Setup();val old=s.disk.withLock {s.gate.write("key","same",s.action,{s.value}) {s.value=it}}
        s.form.close();val next=s.authority.form {s.origin}.begin(s.action.input)
        val fresh=s.disk.withLock {s.gate.write("key","same",next,{s.value}) {s.value=it}}
        s.disk.withLock {s.gate.rollback(old,{s.value}) {s.value=it}}
        assertEquals("same",s.value);assertFalse(s.gate.release(old));assertTrue(s.gate.release(fresh));assertTrue(next.currentForUi());assertFalse(s.action.releaseBusy());assertTrue(next.releaseBusy())
    }
    @Test fun rollbackUsesTheActualTransactionalBaselineAndPreservesForeignChanges() {
        val s=Setup();s.value="actual-latest-original";val receipt=s.disk.withLock {s.gate.write("key","candidate",s.action,{s.value}) {s.value=it}}
        s.form.close();s.disk.withLock {s.gate.rollback(receipt,{s.value}) {s.value=it}};assertEquals("actual-latest-original",s.value)
        val next=s.authority.form {s.origin}.begin(s.action.input);val fresh=s.disk.withLock {s.gate.write("key","candidate",next,{s.value}) {s.value=it}}
        s.value="independent-newer-value";s.disk.withLock {s.gate.rollback(fresh,{s.value}) {s.value=it}};assertEquals("independent-newer-value",s.value)
    }
    @Test fun failedWriterAfterDurableReplacementRestoresOriginalWithoutActivating() {
        val s=Setup()
        assertThrows(IllegalStateException::class.java) {s.disk.withLock {s.gate.write("key","candidate",s.action,{s.value}) {v->s.value=v;if(v=="candidate")error("synthetic after-write failure")}}}
        assertEquals("original",s.value);assertFalse(s.action.committed)
    }
}
