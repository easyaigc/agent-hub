package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Host capability/race checks only; no Android disk, HTTP, Activity or Desktop result. */
class ConnectionFormLeaseTest {
    private class Setup {
        val authority=EasyWorkConnectionAuthority()
        var origin=EasyWorkConnectionOrigin("original-target","1","draft-1","a".repeat(64))
        val form=authority.form {origin}
        var input=EasyWorkConnectionInput("code","name","http://127.0.0.1:1","0123456789ABCDEF")
        val action=form.begin(input) {input}
        fun refused()=assertThrows(EasyWorkConnectionAbandoned::class.java) {action.checkpoint()}
    }
    @Test fun closeRevokesEvenWhenAllOriginalValuesAreUnchanged() {val s=Setup();s.form.close();s.refused();assertFalse(s.action.currentForUi())}
    @Test fun pauseResumeCannotReviveAnOldAction() {val s=Setup();s.form.lifecycle(false);s.form.lifecycle(true);s.refused()}
    @Test fun authorityEpochRejectsNullableLinkABAWithoutDifferentFinalValues() {val s=Setup();s.authority.invalidate();s.authority.invalidate();s.refused()}
    @Test fun targetSnapshotChangeIsRejectedWithoutRelyingOnUiCallback() {val s=Setup();s.origin=s.origin.copy(targetKey="other");s.refused()}
    @Test fun draftEpochRejectsChangeAndRestore() {val s=Setup();s.authority.invalidate();s.origin=s.origin.copy(draftRevision="2");s.authority.invalidate();s.origin=s.origin.copy(draftRevision="draft-1");s.refused()}
    @Test fun formInputABAIsRejectedByItsMonotonicEpoch() {val s=Setup();val old=s.input;s.form.inputsChanged();s.input=old.copy(value="changed");s.form.inputsChanged();s.input=old;s.refused();assertEquals(old.sha256,s.action.input.sha256)}
    @Test fun changedInputCannotUseTheOriginalCapability() {val s=Setup();s.input=s.input.copy(endpoint="http://127.0.0.1:2");s.refused()}
    @Test fun revokedFailureCannotOwnANewUiError() {val s=Setup();s.form.close();val next=s.authority.form {s.origin};val action=next.begin(s.input);assertFalse(s.action.currentForUi());assertTrue(action.currentForUi())}
    @Test fun validCommitMaySelectItsOwnResultWithoutRevivingTheOriginalPermission() {val s=Setup();s.action.commit {s.authority.invalidate();s.origin=s.origin.copy(targetKey="connected-target",navigation="2")};assertTrue(s.action.currentForUi());s.refused()}
    @Test fun closeAfterCommitKeepsCommittedResultButSuppressesLateCallback() {val s=Setup();var saved=false;s.action.commit {saved=true};s.form.close();assertTrue(saved);assertTrue(s.action.committed);assertFalse(s.action.currentForUi())}
    @Test fun oldFinalizerCannotReleaseAnotherFormBusyOwner() {val s=Setup();s.form.close();val next=s.authority.form {s.origin}.begin(s.input);assertFalse(s.action.releaseBusy());assertTrue(next.releaseBusy())}
    @Test fun releasedActionCannotOwnUiWhileAnIdenticalNewActionIsBusy() {val s=Setup();assertTrue(s.action.releaseBusy());val next=s.form.begin(s.input);assertFalse(s.action.currentForUi());s.refused();assertTrue(next.currentForUi())}
    @Test fun aFailedCommitIsNotReportedAsCommitted() {val s=Setup();assertThrows(IllegalStateException::class.java) {s.action.commit {error("synthetic fault before selection")}};assertFalse(s.action.committed)}
    @Test fun heldWriteDoesNotPreventRevocationAndFailsItsPostcheck() {
        val s=Setup();val entered=CountDownLatch(1);val allow=CountDownLatch(1);val closeStarted=CountDownLatch(1);val closed=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val write=pool.submit {s.action.write {entered.countDown();check(allow.await(3,TimeUnit.SECONDS))}}
            assertTrue(entered.await(3,TimeUnit.SECONDS));val close=pool.submit {closeStarted.countDown();s.form.close();closed.countDown()};assertTrue(closeStarted.await(3,TimeUnit.SECONDS));assertTrue("Revocation returns before IO is released",closed.await(1,TimeUnit.SECONDS));allow.countDown();assertThrows(java.util.concurrent.ExecutionException::class.java) {write.get(3,TimeUnit.SECONDS)};close.get(3,TimeUnit.SECONDS);s.refused()
        }finally {allow.countDown();pool.shutdownNow()}
    }
}
