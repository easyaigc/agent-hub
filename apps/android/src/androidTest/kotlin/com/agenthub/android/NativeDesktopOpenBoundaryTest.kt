package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real production flow/journal/HTTP codec, independent loopback peer; not real DEV ownership acceptance. */
class NativeDesktopOpenBoundaryTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(30)
    private var owned: F28OwnedNativeFixture? = null
    private val f get() = checkNotNull(owned)
    @Before fun setup() { owned = F28OwnedNativeFixture() }
    @After fun cleanup(): Unit { runBlocking { owned?.close() } }
    private suspend fun open(flow: NativeCodexFlow, client: MobileClient, guard: () -> Boolean = { true }) =
        flow.openOnDesktop(client, f.first, client.cacheKey(f.first), guard, {})
    private suspend fun originals(flow: NativeCodexFlow, client: MobileClient): Triple<String, LocalDraftFile, ByteArray> {
        val bytes = "Owned unsubmitted attachment 中文".toByteArray()
        val file = flow.files.import("same name.txt", "text/plain", bytes)
        val key = client.cacheKey(f.first)
        val metadata = JSONObject().put("version", 1).put("drafts", JSONObject().put(key, "Original unsent text"))
            .put("draftVersions", JSONObject().put(key, "original-version"))
            .put("draftFiles", JSONObject().put(key, org.json.JSONArray().put(file.manifest.body()))).toString()
        f.store.write("metadata", metadata)
        return Triple(metadata, file, bytes)
    }
    @Test fun independentOpenHasOneImmutableNavigationAndPreservesDraftAndAttachment() = runBlocking {
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request)).toString()) }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow(); val (metadata, file, bytes) = originals(flow, client)
            val originalFileRecord = f.store.read("draft-file:${file.manifest.id}")
            val result = open(flow, client)
            assertTrue(result.ownerConfirmed)
            val record = flow.journal.records(result.record.target).single()
            assertEquals("open", record.kind); assertTrue(record.submissionAttempted)
            assertEquals(record.id, JSONObject(record.intent).getString("clientOperationId"))
            assertEquals(setOf("hostId", "threadId", "clientOperationId"), JSONObject(record.intent).keys().asSequence().toSet())
            assertEquals(metadata, f.store.read("metadata")); assertArrayEquals(bytes, flow.files.bytes(file.manifest))
            assertEquals(originalFileRecord, f.store.read("draft-file:${file.manifest.id}"))
            assertNull(f.store.read("submission:${record.id}"))
            assertEquals(record.intent, NativeOperationJournal(f.store).records(record.target).single().intent)
            f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun unknownAnd404RecreatedFlowOnlyQueriesOriginalDespiteReadableOwner() = runBlocking {
        CommandFixture { request ->
            if (request.method == "POST") WireResponse(body = f28OpenReceipt(f28Operation(request), "outcome-unknown", "owned unknown").toString())
            else WireResponse(status = 404, body = "{\"error\":\"not found\"}")
        }.use { server ->
            val client = f.client(server.endpoint); val first = open(f.flow(), client)
            assertFalse(first.ownerConfirmed); assertEquals(0, f.historyReads.get())
            repeat(2) {
                val queried = open(f.flow(), client)
                assertFalse(queried.ownerConfirmed); assertEquals(first.record.id, queried.record.id)
                assertEquals(first.record.intent, queried.record.intent); assertEquals(first.record.intentSha256, queried.record.intentSha256)
            }
            assertTrue(server.requests.all { f28Operation(it) == first.record.id })
            f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun pendingUnknownCannotReachUploadOrInputEvenWhenCanControlIsTrue() = runBlocking {
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request), "outcome-unknown", "owned unknown").toString()) }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow(); val first = open(flow, client)
            val (_, file, bytes) = originals(flow, client)
            val submitted = flow.submit(client, f.first, null, SubmissionOrigin(client.cacheKey(f.first), "owned-version"),
                CodexContent("Inert boundary-only input; must never reach send", listOf(file), GenerationSettings()), "next-turn", null,
                guard = { true })
            assertEquals(first.record.id, submitted.record.id)
            assertFalse(submitted.record.phase == "opened")
            assertArrayEquals(bytes, flow.files.bytes(file.manifest))
            assertTrue(flow.journal.records(first.record.target).all { it.kind == "open" })
            f28OnlyNavigation(server.requests, 1)
        }
    }
    private suspend fun wrong(field: String) {
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request)).put(field, "foreign-value").toString()) }.use { server ->
            val flow = f.flow(); val client = f.client(server.endpoint); val result = open(flow, client)
            assertFalse(result.ownerConfirmed); assertEquals("uncertain", result.record.phase); assertNull(result.record.result)
            assertEquals(result.record.intent, flow.journal.records(result.record.target).single().intent)
            assertEquals(0, f.historyReads.get()); f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun foreignHostReceiptCannotConfirmOwner() = runBlocking { wrong("hostId") }
    @Test fun foreignThreadReceiptCannotConfirmOwner() = runBlocking { wrong("threadId") }
    @Test fun foreignOperationReceiptCannotConfirmOwner() = runBlocking { wrong("clientOperationId") }
    @Test fun openedReceiptWithoutConfirmedOwnerNeverReachesUploadOrInput() = runBlocking {
        f.owners[f.first] = false
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request)).toString()) }.use { server ->
            val flow = f.flow(); val client = f.client(server.endpoint); val (_, file, bytes) = originals(flow, client)
            val result = flow.submit(client, f.first, null, SubmissionOrigin(client.cacheKey(f.first), "owned-version"),
                CodexContent("Inert boundary input", listOf(file), GenerationSettings()), "next-turn", null, guard = { true })
            assertEquals("opened", result.record.phase); assertTrue(f.historyReads.get() >= 2)
            assertTrue(flow.journal.records(result.record.target).all { it.kind == "open" })
            assertArrayEquals(bytes, flow.files.bytes(file.manifest)); f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun sameTargetBusyRejectsSecondNavigationBeforeAnotherPost() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        CommandFixture { request ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            WireResponse(body = f28OpenReceipt(f28Operation(request)).toString())
        }.use { server ->
            val flow = f.flow(); val client = f.client(server.endpoint)
            val first = async(Dispatchers.Default) { open(flow, client) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val failure = runCatching { withTimeout(1000) { open(flow, client) } }.exceptionOrNull()
                assertTrue(failure is IllegalStateException && failure !is CancellationException)
                assertEquals(1, server.requests.size)
                release.countDown(); assertTrue(first.await().ownerConfirmed)
            } finally { release.countDown(); first.cancelAndJoin() }
            f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun foreignRuntimeMetadataCannotAuthorizeNavigation() = runBlocking {
        f.reportedScope = com.agenthub.core.TargetScope(f.scope.agentId, f.scope.accountAuthority,
            f.scope.accountSubject, f.scope.hostId, f.scope.workspaceId, "foreign-runtime")
        CommandFixture { error("Wrong runtime must stop before dispatch") }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow()
            assertTrue(runCatching { open(flow, client) }.isFailure)
            assertTrue(server.requests.isEmpty()); assertTrue(flow.journal.records(client.cacheKey(f.first)).isEmpty())
        }
    }
    @Test fun missingCapabilityRefusesNewOperationBeforePost() = runBlocking {
        f.supported = false
        CommandFixture { error("Unsupported target must not dispatch") }.use { server ->
            val client = f.client(server.endpoint); val flow = f.flow()
            assertTrue(runCatching { open(flow, client) }.isFailure)
            assertTrue(server.requests.isEmpty()); assertTrue(flow.journal.records(client.cacheKey(f.first)).isEmpty())
        }
    }
}
