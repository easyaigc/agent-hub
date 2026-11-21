package com.agenthub.core

import kotlin.test.*

class TaskNotificationRulesTest {
    private fun scope(agent: String, subject: String) = TargetScope(agent, "https://fixture.invalid", subject,
        "fixture-host", "fixture-workspace", "fixture-runtime")
    @Test fun readPositionsPreserveProviderAccountAndExactRuntime() {
        val easyA = scope("easywork", "subject-a")
        val easyB = scope("easywork", "subject-b")
        val position = DeviceReadingVersion(7, "fixture-message", "a".repeat(64))
        assertTrue(TaskNotificationRules.target(easyA))
        assertTrue(TaskNotificationRules.readPosition("completed", "fixture-thread", easyA, position, easyA))
        assertFalse(TaskNotificationRules.readPosition("completed", "fixture-thread", easyB, position, easyA))
        assertTrue(TaskNotificationRules.readPosition("completed", "fixture-thread", scope("codex", "old-account"), position, scope("codex", "new-account")))
        val otherRuntime = TargetScope("easywork", "https://fixture.invalid", "subject-a", "fixture-host", "fixture-workspace", "different-runtime")
        assertFalse(TaskNotificationRules.readPosition("completed", "fixture-thread", otherRuntime, position, easyA))
    }
    @Test fun malformedCompletionVersionsAndUntrustedPresentationFieldsAreRejected() {
        val target = scope("easywork", "subject-a")
        val valid = DeviceReadingVersion(1, "fixture-message", "b".repeat(64))
        assertFalse(TaskNotificationRules.readPosition("question", "fixture-thread", target, valid, target))
        assertFalse(TaskNotificationRules.readPosition("completed", null, target, valid, target))
        assertFalse(TaskNotificationRules.readPosition("completed", "fixture-thread", null, valid, target))
        assertFalse(TaskNotificationRules.readPosition("completed", "fixture-thread", target, valid.copy(revision = 0), target))
        assertFalse(TaskNotificationRules.readPosition("completed", "fixture-thread", target, valid.copy(contentVersion = "not-a-hash"), target))
        assertFalse(TaskNotificationRules.target(TargetScope("easywork", null, "subject-a", "fixture-host", "fixture-workspace", "fixture-runtime")))
        assertFalse(TaskNotificationRules.summary("summary\nwith control"))
        assertFalse(TaskNotificationRules.summary("summary\u202Ehidden"))
        assertTrue(TaskNotificationRules.summary("fixture summary"))
        assertTrue(TaskNotificationRules.sound("default"))
        assertFalse(TaskNotificationRules.sound("../fixture.caf"))
    }
}
