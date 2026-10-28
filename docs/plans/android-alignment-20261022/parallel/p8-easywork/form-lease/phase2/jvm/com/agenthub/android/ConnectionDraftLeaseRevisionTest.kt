package com.agenthub.android
import org.junit.Assert.*
import org.junit.Test
/** Full draft revision supplements text/target snapshots, without moving any draft data. */
class ConnectionDraftLeaseRevisionTest {
    @Test fun fullDraftRevisionChangeRefusesAWriteEvenWhenTextAndTargetAreUnchanged() {
        val authority=EasyWorkConnectionAuthority();val origin=EasyWorkConnectionOrigin("target","1","text-1","a");val action=authority.form {origin}.begin(EasyWorkConnectionInput("code","name","endpoint","original"));var revision="full-draft-1";action.bindDraftRevision {revision};revision="full-draft-2";var wrote=false
        assertThrows(EasyWorkConnectionAbandoned::class.java) {action.write {wrote=true}};assertFalse(wrote)
    }
    @Test fun anAuthorizedSelectionCapturesItsCommittedDraftWithoutRestoringOldPermission() {
        val authority=EasyWorkConnectionAuthority();var origin=EasyWorkConnectionOrigin("old","1","text-1","a");val action=authority.form {origin}.begin(EasyWorkConnectionInput("code","name","endpoint","original"));var revision="full-old";action.bindDraftRevision {revision};action.commit {origin=origin.copy(targetKey="new");revision="full-new";authority.invalidate()};assertTrue(action.currentForUi());revision="newer-full-draft";assertFalse(action.currentForUi())
    }
}
