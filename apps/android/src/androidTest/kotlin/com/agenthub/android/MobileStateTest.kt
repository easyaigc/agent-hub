package com.agenthub.android

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileStateTest {
    private val vectors: JSONObject get() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() })
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else getString(name)
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.access() = SessionAccess(getBoolean("demo"), getBoolean("online"), if (isNull("runtimeConnected")) null else getBoolean("runtimeConnected"),
        getJSONArray("capabilities").strings(), getBoolean("threadSelected"), getBoolean("hasProject"), getBoolean("projectlessDraft"), getBoolean("canControl"), getBoolean("testing"))
    private fun JSONObject.state() = SubmissionState(getBoolean("historyLoading"), getBoolean("sending"), getBoolean("stopping"), getBoolean("replyPending"),
        getBoolean("creationPending"), getBoolean("importing"), getBoolean("running"), getString("preferredReplyMode"))

    @Test fun advertisedCapabilitiesAndOwnerControl() {
        for (test in vectors.getJSONArray("policy").objects()) {
            val a = test.getJSONObject("access").access(); val s = test.getJSONObject("state").state(); val e = test.getJSONObject("expected"); val name = test.getString("name")
            assertEquals(name, e.getBoolean("writable"), SessionPolicies.writable(a))
            assertEquals(name, e.getBoolean("compose"), SessionPolicies.compose(a))
            assertEquals(name, e.getBoolean("submit"), SessionPolicies.submit(a, s, test.getString("text"), test.getInt("attachmentCount")))
            assertEquals(name, e.getBoolean("configure"), SessionPolicies.configureGeneration(a, s))
            assertEquals(name, e.getBoolean("plan"), SessionPolicies.selectPlan(a, s))
            assertEquals(name, e.getString("mode"), SessionPolicies.activeReplyMode(a, s.preferredReplyMode))
        }
    }

    @Test fun finalRepliesAndContinuousToolGroups() {
        for (test in vectors.getJSONArray("timeline").objects()) {
            val messages = test.getJSONArray("messages").objects().map { m -> TimelineMessage(m.getString("id"), m.getString("role"), m.getString("text"),
                m.text("kind"), m.text("status"), m.text("runId"), m.text("replyPhase"), m.text("replyMessageId")) }
            val actual = MessageProjection.project(messages, test.getInt("limit"), test.text("activeRunId"), test.getBoolean("running"))
            val expected = test.getJSONObject("expected"); val name = test.getString("name")
            assertEquals(name, expected.getBoolean("hasEarlierMessages"), actual.hasEarlierMessages)
            val rows = expected.getJSONArray("rows").objects().map { r -> listOf(r.getString("kind"), r.getString("id"),
                r.getJSONArray("indices").let { a -> (0 until a.length()).map { a.getInt(it) } }, r.text("text")) }
            assertEquals(name, rows, actual.rows.map { listOf(it.kind, it.id, it.indices, it.text) })
        }
    }

    @Test fun unknownReceiptsRemainQueryable() {
        for (test in vectors.getJSONArray("receipts").objects()) {
            val receipts = test.getJSONArray("receipts").objects().map { ReceiptState(it.getString("id"), it.getString("state"), it.text("supersededByTurnId")) }
            val expected = test.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getInt(it) } }
            assertEquals(test.getString("name"), expected, SessionPolicies.noticeIndices(receipts, test.getJSONArray("dismissed").strings(), test.text("pendingId")))
        }
    }

    @Test fun strictDomainContractNullAndPrecision() {
        for (test in vectors.getJSONArray("contracts").objects()) {
            assertEquals(test.getString("name"), test.getBoolean("valid"), DomainContract.validate(test.getString("json"), test.getString("definition")) == null)
        }
    }

    @Test fun actualCodexHttpReadContract() {
        for (test in vectors.getJSONArray("http").objects()) {
            assertEquals(test.getString("name"), test.getBoolean("valid"), CodexHttpContract.validate(test.getString("json"), test.getString("definition")) == null)
        }
    }

    @Test fun utf8ReadingHashAndLatePush() {
        for (test in vectors.getJSONArray("reading").objects()) {
            val t = test.getJSONObject("target"); val scope = TargetScope(t.getString("agentId"), t.text("accountAuthority"), t.text("accountSubject"), t.getString("hostId"), t.getString("workspaceId"), t.getString("runtimeId"))
            assertEquals(test.getString("name"), test.getString("key"), MobileRules.readingKey(scope, test.getString("threadId")))
            val preimage = MobileRules.contentVersionPreimage(test.getString("messageId"), test.getString("text"), test.getString("status"))
            assertEquals(test.getString("preimage"), preimage)
            assertEquals(test.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        }
        assertFalse(ReadingPolicy.newPushUnread(false, 5, 4))
        assertFalse(ReadingPolicy.reconciledUnread(true, true, true, true, true, true))
    }

    @Test fun creationRecoveryAndEventEpoch() {
        assertEquals("outcome-unknown", OperationPolicies.creationFailurePhase("outcome-unknown", true, 503))
        assertEquals("rejected", OperationPolicies.creationFailurePhase("prepared", false, 503))
        assertFalse(OperationPolicies.sameCreation(CreationIdentity("host", "old", null), CreationIdentity("host", "new", null)))
        assertFalse(OperationPolicies.event("epoch", 5, "epoch", 5, false, true).apply)
        val reset = OperationPolicies.event("epoch", 50, "new", 1, false, true)
        assertTrue(reset.apply && reset.reload); assertEquals(1L, reset.cursor)
    }

    @Test fun draftAndPlanGuardsPreserveSubmittedIntent() {
        assertTrue(SessionPolicies.inheritDefaults(false, false, false, "", 0, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, false, " ", 0, null))
        assertFalse(SessionPolicies.inheritDefaults(false, false, false, "", 0, "submitted"))
        assertFalse(SessionPolicies.inheritDefaults(false, false, true, "", 0, null))
        assertFalse(SessionPolicies.respondToPlan("new", "old", false, true, true, true))
        assertTrue(SessionPolicies.respondToPlan("plan", "plan", false, true, true, true))
        assertFalse(SessionPolicies.recoveryPrerequisites(true, false, "unknown", true))
        assertFalse(OperationPolicies.observeCreation("rejected", "op", "op"))
        assertTrue(OperationPolicies.observeCreation("outcome-unknown", "op", "op"))
    }

    @Test fun asynchronousBoundaryDeliversOnceOnMainLooper() {
        val done = CountDownLatch(1); val deliveries = AtomicInteger(); val value = AtomicReference<String>()
        HistoryRequests.start(object : MobileHistoryGateway {
            override fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation {
                completion(HistoryReply(targetKey, "first", null)); completion(HistoryReply(targetKey, "duplicate", null))
                return object : MobileCancellation { override fun cancel() {} }
            }
        }, "synthetic-target", "thread", null) { reply ->
            Handler(Looper.getMainLooper()).post {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                deliveries.incrementAndGet(); value.set(reply.json); done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, deliveries.get()); assertEquals("first", value.get())
    }

    @Test fun cancellationCancelsAdapterAndDoesNotAcceptLateReply() {
        val ready = CountDownLatch(1); val cancelled = CountDownLatch(1); val done = CountDownLatch(1)
        val callback = AtomicReference<(HistoryReply) -> Unit>(); val result = AtomicReference<HistoryReply>(); val deliveries = AtomicInteger()
        val operation = HistoryRequests.start(object : MobileHistoryGateway {
            override fun read(targetKey: String, threadId: String, cursor: String?, completion: (HistoryReply) -> Unit): MobileCancellation {
                callback.set(completion); ready.countDown()
                return object : MobileCancellation { override fun cancel() { cancelled.countDown() } }
            }
        }, "synthetic-target", "thread", null) { result.set(it); deliveries.incrementAndGet(); done.countDown() }
        assertTrue(ready.await(5, TimeUnit.SECONDS)); operation.cancel()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertTrue(done.await(5, TimeUnit.SECONDS))
        callback.get()(HistoryReply("synthetic-target", "late", null))
        assertEquals("cancelled", result.get().failure?.code); assertEquals(1, deliveries.get())
    }
}
