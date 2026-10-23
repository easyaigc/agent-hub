package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class SessionPoliciesTest {
    @Test fun codexDesktopRequirementsRemainAfterCapabilityReplacementWithoutGrantingExecution() {
        val readOnly=SessionPolicies.codexCapabilities(listOf("history:read"),"desktop-runtime")
        assertTrue("runtime:control-required" in readOnly && "threads:create:staged" in readOnly)
        assertFalse("reply" in readOnly || "desktop:creations" in readOnly || "desktop:open" in readOnly)
        assertEquals(readOnly,SessionPolicies.codexCapabilities(readOnly,"desktop-runtime"))
        assertEquals(listOf("reply"),SessionPolicies.codexCapabilities(listOf("reply"),"managed-runtime"))
    }
    private val vectors = Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.contentOrNull
    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.strings(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.access() = SessionAccess(flag("demo"), flag("online"), getValue("runtimeConnected").jsonPrimitive.booleanOrNull,
        strings("capabilities"), flag("threadSelected"), flag("hasProject"), flag("projectlessDraft"), flag("canControl"), flag("testing"))
    private fun JsonObject.state() = SubmissionState(flag("historyLoading"), flag("sending"), flag("stopping"), flag("replyPending"),
        flag("creationPending"), flag("importing"), flag("running"), text("preferredReplyMode")!!)

    @Test fun ownerCapabilitiesAndSubmissionPreconditions() {
        for (entry in vectors.getValue("policy").jsonArray) {
            val test = entry.jsonObject; val a = test.getValue("access").jsonObject.access(); val s = test.getValue("state").jsonObject.state()
            val expected = test.getValue("expected").jsonObject; val name = test.text("name")
            assertEquals(expected.flag("writable"), SessionPolicies.writable(a), name)
            assertEquals(expected.flag("compose"), SessionPolicies.compose(a), name)
            assertEquals(expected.flag("submit"), SessionPolicies.submit(a, s, test.text("text")!!, test.getValue("attachmentCount").jsonPrimitive.int), name)
            assertEquals(expected.flag("configure"), SessionPolicies.configureGeneration(a, s), name)
            assertEquals(expected.flag("plan"), SessionPolicies.selectPlan(a, s), name)
            assertEquals(expected.text("mode"), SessionPolicies.activeReplyMode(a, s.preferredReplyMode), name)
        }
        val changed = SessionPolicies.changeModel("xhigh", "fast", listOf("low"), "low", listOf("standard"))
        assertEquals("low", changed.effort); assertEquals("standard", changed.speed)
    }

    @Test fun runIdentityFinalFragmentsAndToolWindows() {
        for (entry in vectors.getValue("toolPresentation").jsonArray) {
            val test=entry.jsonObject
            val messages=test.getValue("messages").jsonArray.map { it.jsonObject.let { m -> TimelineMessage(m.text("id")!!,m.text("role")!!,m.text("text")!!,m.text("kind"),m.text("status"),m.text("runId"),m.text("replyPhase"),m.text("replyMessageId")) } }
            val actual=when(test.text("operation")) {
                "summary" -> ToolPresentation.summary(messages)
                "title" -> ToolPresentation.title(messages.single(),test.flag("flag"))
                "compaction" -> ToolPresentation.compaction(test.text("value"),test.flag("flag"))
                "style" -> ToolPresentation.style(test.text("value")).let { "${it.category}|${it.verb}|${it.detailTitle}" }
                "status" -> ToolPresentation.status(test.text("value"))
                else -> error("Unknown presentation operation")
            }
            assertEquals(test.text("expected"),actual,test.text("operation"))
        }
        for (entry in vectors.getValue("timeline").jsonArray) {
            val test = entry.jsonObject
            val messages = test.getValue("messages").jsonArray.map { it.jsonObject.let { m -> TimelineMessage(
                m.text("id")!!, m.text("role")!!, m.text("text")!!, m.text("kind"), m.text("status"), m.text("runId"), m.text("replyPhase"), m.text("replyMessageId")) } }
            val actual = MessageProjection.project(messages, test.getValue("limit").jsonPrimitive.int, test.text("activeRunId"), test.flag("running"))
            val expected = test.getValue("expected").jsonObject; val name = test.text("name")
            assertEquals(expected.flag("hasEarlierMessages"), actual.hasEarlierMessages, name)
            assertEquals(expected.getValue("rows").jsonArray.map { it.jsonObject.let { row ->
                listOf(row.text("kind"), row.text("id"), row.getValue("indices").jsonArray.map { it.jsonPrimitive.int }, row.text("text"))
            } }, actual.rows.map { listOf(it.kind, it.id, it.indices, it.text) }, name)
        }
    }

    @Test fun newerRepliesCannotSettleUnknownReceipts() {
        for (entry in vectors.getValue("receipts").jsonArray) {
            val test = entry.jsonObject
            val receipts = test.getValue("receipts").jsonArray.map { it.jsonObject.let { r -> ReceiptState(r.text("id")!!, r.text("state")!!, r.text("supersededByTurnId")) } }
            assertEquals(test.getValue("expected").jsonArray.map { it.jsonPrimitive.int }, SessionPolicies.noticeIndices(receipts, test.strings("dismissed"), test.text("pendingId")), test.text("name"))
        }
    }

    @Test fun strictSchemaNullUnknownFieldsAndIntegerPrecision() {
        for (entry in vectors.getValue("contracts").jsonArray) {
            val test = entry.jsonObject
            assertEquals(test.flag("valid"), DomainContract.validate(test.text("json")!!, test.text("definition")!!) == null, test.text("name"))
        }
    }

    @Test fun implementedCodexHttpReadContractsStaySeparateFromDomainV1() {
        for(entry in vectors.getValue("discovery").jsonArray) {
            val test = entry.jsonObject
            assertEquals(test.flag("valid"), CodexDiscoveryContract.validate(test.text("json")!!) == null, test.text("name"))
        }
        for (entry in vectors.getValue("http").jsonArray) {
            val test = entry.jsonObject
            assertEquals(test.flag("valid"), CodexHttpContract.validate(test.text("json")!!, test.text("definition")!!) == null, test.text("name"))
        }
        for (entry in vectors.getValue("reading").jsonArray) {
            val test = entry.jsonObject; val t = test.getValue("target").jsonObject
            val target = TargetScope(t.text("agentId")!!, t.text("accountAuthority"), t.text("accountSubject"), t.text("hostId")!!, t.text("workspaceId")!!, t.text("runtimeId")!!)
            assertEquals(test.text("key"), MobileRules.readingKey(target, test.text("threadId")), test.text("name"))
            assertEquals(test.text("preimage"), MobileRules.contentVersionPreimage(test.text("messageId")!!, test.text("text")!!, test.text("status")!!), test.text("name"))
        }
    }

    @Test fun staleReadAndLatePushDoNotReviveUnread() {
        assertEquals(4, ReadingPolicy.nextRevision(4, true))
        assertEquals(5, ReadingPolicy.nextRevision(4, false))
        assertFalse(ReadingPolicy.newPushUnread(false, 5, 4))
        assertFalse(ReadingPolicy.newPushUnread(true, 5, 5))
        assertTrue(ReadingPolicy.newPushUnread(false, 4, 5))
        assertFalse(ReadingPolicy.reconciledUnread(true, true, true, true, true, true))
        assertFalse(ReadingPolicy.reconciledUnread(false, true, false, true, true, false))
        assertTrue(ReadingPolicy.reconciledUnread(false, true, true, true, false, false))
    }

    @Test fun explicitDraftIntentAndPlanRecoveryGuards() {
        assertTrue(SessionPolicies.inheritDefaults(false, false, false, "", 0, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, false, " ", 0, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, false, "", 1, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, false, "", 0, "submitted"))
        assertFalse(SessionPolicies.inheritDefaults(false, true, false, "", 0, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, true, "", 0, null))
        assertFalse(SessionPolicies.recoveryPrerequisites(true, false, "unknown", true))
        assertFalse(SessionPolicies.recoveryPrerequisites(true, true, "running", true))
        assertTrue(SessionPolicies.recoveryPrerequisites(true, false, "interrupted", true))
        assertFalse(SessionPolicies.respondToPlan("new", "old", false, true, true, true))
        assertFalse(SessionPolicies.respondToPlan("plan", "plan", false, true, true, false))
        assertTrue(SessionPolicies.respondToPlan("plan", "plan", false, true, true, true))
        assertFalse(SessionPolicies.stop(true, true, false, false))
        assertTrue(SessionPolicies.stop(true, true, true, false))
        assertFalse(OperationPolicies.observeCreation("rejected", "op", "op"))
        assertTrue(OperationPolicies.observeCreation("outcome-unknown", "op", "op"))
    }

    @Test fun creationAndEventRecoveryNeverReplayUnknownInput() {
        assertEquals("outcome-unknown", OperationPolicies.creationFailurePhase("outcome-unknown", true, 503))
        assertEquals("rejected", OperationPolicies.creationFailurePhase("prepared", false, 503))
        assertFalse(OperationPolicies.sameCreation(CreationIdentity("host", "op", null), CreationIdentity("host", "other", null)))
        assertFalse(OperationPolicies.sameCreation(CreationIdentity("host", "op", null), CreationIdentity("host", "op", "")))
        assertFalse(OperationPolicies.observeCreation("submitted", "new", "old"))
        val duplicate = OperationPolicies.event("epoch", 5, "epoch", 5, false, true)
        assertFalse(duplicate.apply); assertFalse(duplicate.reload); assertEquals(5, duplicate.cursor)
        val restarted = OperationPolicies.event("epoch", 50, "new", 1, false, true)
        assertTrue(restarted.apply); assertTrue(restarted.reload); assertEquals(1, restarted.cursor)
        assertFalse(OperationPolicies.event("epoch", 5, "epoch", -1, false, true).apply)
    }

    @Test fun asyncDuplicateCallbackHasOneDelivery() = runBlocking {
        val result = CompletableDeferred<HistoryReply>()
        val gateway = object : MobileHistoryGateway {
            override fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation {
                completion(HistoryReply(targetKey, "first", null)); completion(HistoryReply(targetKey, "second", null))
                return object : MobileCancellation { override fun cancel() {} }
            }
        }
        HistoryRequests.start(gateway, "target", "thread", null) { assertTrue(result.complete(it)) }
        assertEquals("first", withTimeout(5000) { result.await() }.json)
    }

    @Test fun asyncCancellationCancelsPlatformAndIgnoresLateReply() = runBlocking {
        val ready = CompletableDeferred<(HistoryReply) -> Unit>(); val cancelled = CompletableDeferred<Unit>(); val result = CompletableDeferred<HistoryReply>()
        val request = HistoryRequests.start(object : MobileHistoryGateway {
            override fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation {
                ready.complete(completion)
                return object : MobileCancellation { override fun cancel() { cancelled.complete(Unit) } }
            }
        }, "target", "thread", null) { assertTrue(result.complete(it)) }
        val late = withTimeout(5000) { ready.await() }; request.cancel()
        withTimeout(5000) { cancelled.await() }
        assertEquals("cancelled", withTimeout(5000) { result.await() }.failure?.code)
        late(HistoryReply("target", "late", null))
    }

    @Test fun asyncWrongTargetAndThrownAdapterAreTypedErrors() = runBlocking {
        for (throws in listOf(false, true)) {
            val result = CompletableDeferred<HistoryReply>()
            HistoryRequests.start(object : MobileHistoryGateway {
                override fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation {
                    if (throws) throw IllegalStateException("adapter failure")
                    completion(HistoryReply("other", "wrong", null))
                    return object : MobileCancellation { override fun cancel() {} }
                }
            }, "target", "thread", null) { result.complete(it) }
            assertEquals(if (throws) "transport" else "wrong-target", withTimeout(5000) { result.await() }.failure?.code)
        }
    }
}
