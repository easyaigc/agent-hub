package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Six new deltas only; depends on unchanged F28OwnedNativeFixture from v1.
 * Real production helper/journal/query flow and codecs. No Activity/default store/real Host.
 */
class NativeDesktopOpenIncrementalV2Test {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(30)
    private var owned: F28OwnedNativeFixture? = null
    private val f get() = checkNotNull(owned)
    @Before fun setup() { owned = F28OwnedNativeFixture() }
    @After fun cleanup(): Unit { runBlocking { owned?.close() } }
    private fun helper(flow: NativeCodexFlow, reads: HostReads, commands: AgentCommands, budget: Long = 35_000) =
        NativeDesktopOpenFlow(flow.journal, reads, commands, timeoutMillis = budget) { client, record -> flow.query(client, record).record }
    private suspend fun seedUnknown(flow: NativeCodexFlow, client: MobileClient): SavedOperation {
        val id = UUID.randomUUID().toString()
        val saved = flow.journal.prepare(client, f.first, "open", JSONObject().put("hostId", client.id)
            .put("threadId", f.first).put("clientOperationId", id), client.cacheKey(f.first), "original-owned-version")
        flow.journal.markSubmitting(saved.target, saved.id)
        return flow.journal.uncertain(saved.target, saved.id, "Owned prior UNKNOWN; not actually dispatched by this fixture")
    }
    private fun receipt(client: MobileClient, thread: String, id: String, phase: String = "opened") =
        CodexCommandCodec.open(JSONObject().put("hostId", client.id).put("threadId", thread).put("clientOperationId", id)
            .put("phase", phase).put("error", if (phase == "outcome-unknown") "Owned UNKNOWN" else JSONObject.NULL))
    private fun reads(runtime: Boolean?, owner: Boolean = true): HostReads = object : HostReads {
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
            val codex = client.scope?.agentId == "codex"
            val wire = (if (codex) JSONObject().put("hostId", client.id).put("readTarget", CodexCommandCodec.target(checkNotNull(client.scope)))
                else MobileSnapshotCodec.header(client)).put("name", "Owned compatibility fixture")
                .put("connectionMode", if (codex) "desktop-runtime" else "easywork-runtime")
                .put("projects", JSONArray()).put("threads", JSONArray()).put("capabilities", JSONArray(listOf("reply", "desktop:open", "commands:target-bound:v1")))
            // Omit the field for the real legacy-null codec path; do not copy an inconsistent model.
            if (runtime != null) {
                if (codex) wire.put("agentConnectionState", JSONObject().put("runtimeConnected", runtime)) else wire.put("runtimeConnected", runtime)
            }
            return MobileSnapshotCodec.workspace(wire.toString())
        }
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
            val wire = (if (client.scope?.agentId == "codex") JSONObject().put("hostId", client.id).put("readTarget", CodexCommandCodec.target(checkNotNull(client.scope)))
                else MobileSnapshotCodec.header(client)).put("threadId", threadId).put("messages", JSONArray())
                .put("runtime", JSONObject().put("state", "idle").put("turnId", JSONObject.NULL).put("plan", JSONArray())
                    .put("error", JSONObject.NULL).put("approvals", JSONArray()).put("receipts", JSONArray()).put("canControl", owner))
            return MobileSnapshotCodec.history(wire.toString())
        }
    }

    @Test fun existingUnknownSurvivesWorkspaceFailureWithoutNewPostOrNotSubmittedClassification() = runBlocking {
        CommandFixture { request ->
            assertEquals("GET", request.method)
            WireResponse(status = 404, body = "{\"error\":\"owned not found\"}")
        }.use { server ->
            val client = f.client(server.endpoint); val commands = f.commands(); val flow = NativeCodexFlow(f.store, f.reads, commands)
            val original = seedUnknown(flow, client)
            val failedReads = object : HostReads by f.reads {
                override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace = throw IOException("Owned preflight failure")
            }
            val outcome = runCatching { helper(flow, failedReads, commands).open(client, f.first, { true }) }
            assertTrue("Existing UNKNOWN must be returned, not reclassified as not submitted", outcome.isSuccess)
            val returned = outcome.getOrThrow()
            assertFalse(returned.ownerConfirmed); assertEquals(original.id, returned.record.id)
            assertEquals(original.intent, returned.record.intent); assertEquals(original.intentSha256, returned.record.intentSha256)
            assertTrue(returned.record.submissionAttempted); assertEquals("uncertain", returned.record.phase)
            assertEquals(1, flow.journal.records(original.target).size); assertTrue(server.requests.isEmpty())
            val later = helper(flow, f.reads, commands).open(client, f.first, { true })
            assertEquals(original.id, later.record.id); assertEquals(original.intent, later.record.intent)
            assertFalse(later.ownerConfirmed); assertEquals(1, server.requests.size)
            assertEquals(original.id, f28Operation(server.requests.single())); f28OnlyNavigation(server.requests, 0)
        }
    }

    @Test fun rejectedReceiptSurvivesLateGuardFailureAndObservationsAndTerminalQuery() = runBlocking {
        val current = AtomicBoolean(true)
        CommandFixture { request ->
            current.set(false)
            WireResponse(body = f28OpenReceipt(f28Operation(request), "rejected", "Owned rejection").toString())
        }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow()
            val failure = runCatching { helper(flow, f.reads, f.commands()).open(client, f.first, { current.get() }) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            val original = flow.journal.records(client.cacheKey(f.first)).single()
            assertEquals("rejected", original.phase); val frozen = original.body().toString()
            val unknown = flow.journal.uncertain(original.target, original.id, "Late cancellation")
            assertEquals(frozen, unknown.body().toString())
            for (phase in listOf("opening", "opened")) {
                val late = flow.journal.observed(original.target, original.id, phase, f28OpenReceipt(original.id, phase))
                assertEquals(frozen, late.body().toString())
            }
            val query = f.flow().query(client, original)
            assertEquals(frozen, query.record.body().toString())
            assertEquals("A durable rejected result must not perform another status read", 1, server.requests.size)
            f28OnlyNavigation(server.requests, 1)
        }
    }

    @Test fun callerCancellationAfterRejectedPublicationCannotDowngradeTerminalReceipt() = runBlocking {
        val cancelIssued = AtomicBoolean()
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request), "rejected", "Owned rejection").toString()) }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow(); val target = client.cacheKey(f.first)
            val dispatch = async(Dispatchers.Default) {
                val caller = checkNotNull(currentCoroutineContext()[Job])
                helper(flow, f.reads, f.commands()).open(client, f.first, {
                    val raw = f.store.read("operations:$target")
                    val rejected = raw?.let { JSONObject(it).getJSONArray("operations").objects().any { row -> row.getString("phase") == "rejected" } } == true
                    if (rejected && cancelIssued.compareAndSet(false, true)) caller.cancel(CancellationException("Owned caller cancelled after real rejected publication"))
                    true
                })
            }
            assertTrue(runCatching { dispatch.await() }.exceptionOrNull() is CancellationException)
            assertTrue("Cancellation must actually occur after the rejected journal write", cancelIssued.get())
            val row = flow.journal.records(target).single()
            assertEquals("rejected", row.phase); assertEquals("rejected", JSONObject(checkNotNull(row.result)).getString("phase"))
            assertEquals("Owned rejection", row.error); assertEquals(1, server.requests.size)
            f28OnlyNavigation(server.requests, 1)
        }
    }

    @Test fun nullableCodexRequiresOpenedReceiptAndFreshOwnerWhileFalseAndEasyWorkNullReject() = runBlocking {
        val codex = f.client("https://unused.invalid")
        val ewScope = TargetScope("easywork", "https://issuer.f28.invalid", "owned-account", "f28-easywork-host", "f28-easywork-workspace", "local")
        val easywork = MobileClient(ewScope.hostId, "Owned EW", ewScope, "https://relay.invalid", true, "easywork-runtime", false,
            agentConnection = AgentConnection(ewScope, listOf(AgentCandidate("unused", "relay-http", "https://relay.invalid")), "unused"))
        data class Case(val client: MobileClient, val connected: Boolean?, val owner: Boolean, val allowed: Boolean, val confirmed: Boolean, val phase: String = "opened")
        for (case in listOf(Case(codex, null, true, true, true), Case(codex, null, false, true, false),
            Case(codex, false, true, false, false), Case(easywork, null, true, false, false), Case(easywork, true, true, true, true),
            Case(codex, null, true, true, false, "outcome-unknown"))) {
            val posts = AtomicInteger(); val queryReads = AtomicInteger(); val ports = reads(case.connected, case.owner)
            val commands = object : AgentCommands by NativeCodexCommands({ error("No external transport in compatibility fixture") }) {
                override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen {
                    if (queryOnly) queryReads.incrementAndGet() else posts.incrementAndGet()
                    return receipt(client, threadId, operationId, case.phase)
                }
            }
            val flow = NativeCodexFlow(f.store, ports, commands); val target = case.client.cacheKey(f.first)
            val before = flow.journal.records(target).size
            val result = runCatching { helper(flow, ports, commands).open(case.client, f.first, { true }) }
            if (case.allowed) {
                val opened = result.getOrThrow()
                assertEquals(case.phase, opened.record.phase); assertEquals(case.confirmed, opened.ownerConfirmed)
                if (case.phase == "opened") {
                    assertEquals(case.connected, opened.workspace?.runtimeConnected); assertEquals(case.owner, opened.history?.runtime?.canControl)
                } else { assertNull(opened.workspace); assertNull(opened.history) }
                assertEquals(1, posts.get()); assertEquals(before + 1, flow.journal.records(target).size)
            } else {
                assertTrue(result.exceptionOrNull() is AgentOperationNotSubmitted)
                assertEquals(0, posts.get()); assertEquals(before, flow.journal.records(target).size)
            }
            assertEquals(0, queryReads.get())
        }
    }

    @Test fun oneTotalTimeoutCancelsFreshOwnerStageWithoutResetOrOriginalIdReplay() = runBlocking {
        val workspaceReads = AtomicInteger(); val historyReads = AtomicInteger(); val posts = AtomicInteger(); val queries = AtomicInteger()
        val freshCancelled = AtomicBoolean(); val normal = reads(true)
        val ports = object : HostReads by normal {
            override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
                val stage = workspaceReads.incrementAndGet()
                try { delay(550) } catch (cancel: CancellationException) { if (stage == 2) freshCancelled.set(true); throw cancel }
                return normal.workspace(client, projectCursor, threadCursor)
            }
            override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
                historyReads.incrementAndGet(); delay(550); return normal.history(client, threadId, cursor)
            }
        }
        val commands = object : AgentCommands by NativeCodexCommands({ error("No external transport in budget fixture") }) {
            override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen {
                if (queryOnly) queries.incrementAndGet() else posts.incrementAndGet()
                delay(550); return this@NativeDesktopOpenIncrementalV2Test.receipt(client, threadId, operationId)
            }
        }
        val client = f.client("https://unused.invalid"); val flow = NativeCodexFlow(f.store, ports, commands)
        val original = seedUnknown(flow, client)
        val result = helper(flow, ports, commands, budget = 1500).open(client, f.first, { true })
        assertFalse(result.ownerConfirmed); assertTrue("Total budget must cancel the in-progress fresh workspace, not restart another segment", freshCancelled.get())
        assertEquals(2, workspaceReads.get()); assertEquals(0, historyReads.get()); assertEquals(0, posts.get()); assertEquals(1, queries.get())
        assertEquals(original.id, result.record.id); assertEquals(original.intent, result.record.intent); assertEquals(original.intentSha256, result.record.intentSha256)
        // The original opened receipt was already durable; timeout does not manufacture UNKNOWN or owner success.
        assertEquals("opened", result.record.phase)
        assertEquals(original.id, NativeOperationJournal(f.store).records(original.target).single().id)
    }

    @Test fun coldSendPreflightIsReusedButFreshWorkspaceAndOwnerAreStillRead() = runBlocking {
        val workspaceReads = AtomicInteger(); val historyReads = AtomicInteger(); val posts = AtomicInteger(); val sends = AtomicInteger()
        val current = AtomicBoolean(true); val normal = reads(true)
        val ports = object : HostReads by normal {
            override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
                workspaceReads.incrementAndGet(); return normal.workspace(client, projectCursor, threadCursor)
            }
            override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
                val ordinal = historyReads.incrementAndGet()
                if (ordinal == 2) current.set(false) // Stop this inert submit after witnessing its fresh owner read, before upload/input.
                return reads(true, owner = ordinal > 1).history(client, threadId, cursor)
            }
        }
        val commands = object : AgentCommands by NativeCodexCommands({ error("No external transport in cold preflight fixture") }) {
            override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen {
                check(!queryOnly); posts.incrementAndGet(); return this@NativeDesktopOpenIncrementalV2Test.receipt(client, threadId, operationId)
            }
            override suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt { sends.incrementAndGet(); error("No input is permitted") }
        }
        val client = f.client("https://unused.invalid"); val flow = NativeCodexFlow(f.store, ports, commands)
        val failure = runCatching { flow.submit(client, f.first, null, SubmissionOrigin(client.cacheKey(f.first), "owned-original-version"),
            CodexContent("Inert boundary-only snapshot, never sent", emptyList(), GenerationSettings()), "next-turn", null, guard = { current.get() }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("Initial authority plus one fresh owner workspace, without a repeated preflight", 2, workspaceReads.get())
        assertEquals("Initial history plus a new owner read", 2, historyReads.get()); assertEquals(1, posts.get()); assertEquals(0, sends.get())
        val row = flow.journal.records(client.cacheKey(f.first)).single()
        assertEquals("open", row.kind); assertEquals("opened", row.phase)
    }
}
