package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/** Common tests execute the same production rules consumed by Swift and Android. */
class V9SessionRulesTest {
    private val core = Json.parseToJsonElement(Base64.decode(vectorBase64).decodeToString()).jsonObject
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.contentOrNull
    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean

    @Test fun onlyCurrentActiveTurnEnablesRunningAndStop() {
        data class RuntimeCase(val name: String, val turnId: String?, val state: String?, val expected: Boolean)
        val cases = listOf(
            RuntimeCase("current-running", "turn-current", "running", true),
            RuntimeCase("current-approval", "turn-current", "waiting-for-approval", true),
            RuntimeCase("running-missing-turn", null, "running", false),
            RuntimeCase("running-empty-turn", "", "running", false),
            RuntimeCase("approval-missing-turn", null, "waiting-for-approval", false),
            RuntimeCase("approval-empty-turn", "", "waiting-for-approval", false),
            RuntimeCase("idle-old-turn", "turn-old", "idle", false),
            RuntimeCase("completed-old-turn", "turn-old", "completed", false),
            RuntimeCase("failed-old-turn", "turn-old", "failed", false),
            RuntimeCase("interrupted-old-turn", "turn-old", "interrupted", false),
            RuntimeCase("paused-old-turn", "turn-old", "paused", false),
            RuntimeCase("cancelled-old-turn", "turn-old", "cancelled", false),
            RuntimeCase("unknown-old-turn", "turn-old", "unknown", false),
            RuntimeCase("missing-state-old-turn", "turn-old", null, false),
            RuntimeCase("empty-state-old-turn", "turn-old", "", false),
            RuntimeCase("unrecognized-state-old-turn", "turn-old", "busy", false),
        )
        for (test in cases) {
            val running = SessionPolicies.running(test.turnId, test.state)
            assertEquals(test.expected, running, test.name)
            assertEquals(test.expected, SessionPolicies.stop(running, true, true, false), test.name)
            assertFalse(SessionPolicies.stop(running, true, false, false), test.name)
            assertFalse(SessionPolicies.stop(running, false, true, false), test.name)
            assertFalse(SessionPolicies.stop(running, true, true, true), test.name)
        }
    }

    @Test fun capabilityWithdrawalAndRestorationNeverChangeCapturedReplyMode() {
        for (preferred in listOf("queue", "steer")) {
            val other = if (preferred == "queue") "steer" else "queue"
            fun access(modes: List<String>) = SessionAccess(false, true, true,
                listOf("reply") + modes, true, true, false, true, false)
            val state = SubmissionState(false, false, false, false, false, false, true, preferred)
            for ((modes, expectedSubmit) in listOf(
                listOf(preferred, other) to true,
                listOf(other) to false,
                emptyList<String>() to false,
                listOf(preferred) to true,
            )) {
                val current = access(modes)
                assertEquals(preferred, SessionPolicies.activeReplyMode(current, preferred))
                assertEquals(expectedSubmit, SessionPolicies.submit(current, state, "original draft", 0))
                assertEquals(expectedSubmit, SessionPolicies.submit(current, state, "", 1))
                assertFalse(SessionPolicies.submit(current, state, "", 0))
            }
        }
    }

    @Test fun disabledRunningDraftNeverBecomesStopOrRequestsRecoveryHistory() {
        val cases = core.getValue("composer").jsonArray.map { it.jsonObject }
            .filter { it.text("name")!!.startsWith("v9-") }
        assertTrue(cases.isNotEmpty())
        for (test in cases) {
            val i = test.getValue("input").jsonObject
            val input = ComposerInput(i.flag("stopping"), i.flag("running"), i.flag("sending"),
                i.flag("creationPending"), i.text("text")!!, i.getValue("attachmentCount").jsonPrimitive.int,
                i.flag("canSend"), i.flag("canStop"), i.flag("canRunAgain"),
                i.text("runtimeState"), i.text("lastReceiptState"), emptyList())
            val expected = test.getValue("expected").jsonObject
            val decision = MobileRules.composer(input)
            assertEquals(expected.text("action"), decision.action, test.text("name"))
            assertEquals(expected.flag("enabled"), decision.enabled, test.text("name"))
            assertFalse(MobileRules.needsRecoveryHistory(input), test.text("name"))
            // Adding stale failure history must not alter any immediate running/stopping decision.
            val withOldFailure = input.withMessages(listOf(ComposerMessage("user", null),
                ComposerMessage("assistant", "interrupted")))
            assertEquals(decision.action, MobileRules.composer(withOldFailure).action, test.text("name"))
            assertEquals(decision.enabled, MobileRules.composer(withOldFailure).enabled, test.text("name"))
        }
    }

    @Test fun completedOldTurnReturnsToDisabledSendRatherThanStop() {
        val running = SessionPolicies.running("completed-turn", "idle")
        assertFalse(running)
        val input = ComposerInput(false, running, false, false, "", 0,
            false, SessionPolicies.stop(running, true, true, false), false, "idle", "completed", emptyList())
        val decision = MobileRules.composer(input)
        assertEquals("send", decision.action)
        assertFalse(decision.enabled)
        assertFalse(MobileRules.needsRecoveryHistory(input))
        assertTrue(SessionPolicies.running("next-turn", "running"))
    }
}
