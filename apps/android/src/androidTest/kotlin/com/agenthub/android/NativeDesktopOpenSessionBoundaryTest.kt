package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real isolated Session action, selection and draft storage. No MainActivity/default store. */
class NativeDesktopOpenSessionBoundaryTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(30)
    private var owned: F28OwnedNativeFixture? = null
    private val f get() = checkNotNull(owned)
    @Before fun setup() { owned = F28OwnedNativeFixture() }
    @After fun cleanup(): Unit { runBlocking { owned?.close() } }
    private suspend fun snapshot(key: String): String {
        f.store.awaitQueuedWrites()
        val metadata = JSONObject(checkNotNull(f.store.read("metadata")))
        return NativeChatRecoveryCanonical.value(JSONObject()
            .put("text", metadata.getJSONObject("drafts").getString(key))
            .put("version", metadata.getJSONObject("draftVersions").getString(key))
            .put("files", metadata.getJSONObject("draftFiles").getJSONArray(key)))
    }
    @Test fun independentSessionActionIsBusyOnceAndKeepsExactDraftAndAttachmentBytes() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        CommandFixture { request ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            WireResponse(body = f28OpenReceipt(f28Operation(request)).toString())
        }.use { server ->
            try {
                val session = f.readySession(server.endpoint)
                val bytes = "Only owned draft bytes".toByteArray()
                withContext(Dispatchers.Main) { session.text = "Unsubmitted original 中文"; session.addAttachment("owned.txt", "text/plain", bytes) }
                val key = withContext(Dispatchers.Main) { session.targetKey }
                val file = withContext(Dispatchers.Main) { session.attachments.single() }
                val before = snapshot(key)
                withContext(Dispatchers.Main) { assertTrue(session.supportsDesktopOpen); session.openCurrentChatOnDesktop(); session.openCurrentChatOnDesktop() }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertEquals(before, snapshot(key)); assertEquals(1, server.requests.size)
                release.countDown()
                f.eventually { f.busyTargetsEmpty(session) && session.isWritable }
                assertEquals(before, snapshot(key)); assertArrayEquals(bytes, NativeDraftFiles(f.store).bytes(file.manifest))
                assertTrue(NativeOperationJournal(f.store).records(key).all { it.kind == "open" })
                f28OnlyNavigation(server.requests, 1)
            } finally { release.countDown() }
        }
    }
    @Test fun lateOpenedReceiptCannotOverwriteNewTargetDraftOrGrantItsOwner() = runBlocking {
        f.owners[f.first] = false; f.owners[f.other] = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val otherReadGate = CompletableDeferred<Unit>()
        CommandFixture { request ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            WireResponse(body = f28OpenReceipt(f28Operation(request)).toString())
        }.use { server ->
            try {
                val session = f.readySession(server.endpoint)
                withContext(Dispatchers.Main) { session.text = "Original first draft"; session.addAttachment("first.txt", "text/plain", "first".toByteArray()) }
                val firstKey = withContext(Dispatchers.Main) { session.targetKey }
                val original = snapshot(firstKey)
                withContext(Dispatchers.Main) { session.openCurrentChatOnDesktop() }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { session.selectThread(session.threads.single { it.id == f.other }); session.text = "New current draft must survive" }
                f.eventually { !session.historyLoading && session.runtime != null }
                val bytes = "New target owned attachment".toByteArray()
                withContext(Dispatchers.Main) { session.addAttachment("other.txt", "text/plain", bytes) }
                val key = withContext(Dispatchers.Main) { session.targetKey }
                val file = withContext(Dispatchers.Main) { session.attachments.single() }
                val before = snapshot(key)
                // A stale old-owner apply must not be hidden by an immediate fresh B read.
                f.beforeHistory = { thread -> if (thread == f.other) otherReadGate.await() }
                f.owners[f.first] = true // Only the old target became controllable.
                release.countDown()
                f.eventually { f.busyTargetsEmpty(session) }
                val record = NativeOperationJournal(f.store).records(firstKey).single()
                assertEquals("opened", record.phase) // Original result persists, even after navigation.
                assertEquals(original, snapshot(firstKey)); assertEquals(before, snapshot(key))
                assertArrayEquals(bytes, NativeDraftFiles(f.store).bytes(file.manifest))
                withContext(Dispatchers.Main) {
                    assertEquals(f.other, session.selectedThread); assertEquals(key, session.targetKey)
                    assertEquals("New current draft must survive", session.text)
                    assertFalse(session.isWritable); assertFalse(session.runtime?.canControl == true)
                }
                assertTrue(NativeOperationJournal(f.store).records(key).isEmpty())
                f28OnlyNavigation(server.requests, 1)
            } finally { release.countDown(); otherReadGate.complete(Unit) }
        }
    }
    @Test fun omittedCodexRuntimeFlagStillAdvertisesAndOpensOriginalThread() = runBlocking {
        f.runtimeConnected = null
        CommandFixture { request -> WireResponse(body = f28OpenReceipt(f28Operation(request)).toString()) }.use { server ->
            val session = f.readySession(server.endpoint)
            val bytes = "Owned attachment with an omitted runtime flag".toByteArray()
            withContext(Dispatchers.Main) {
                session.text = "Keep the original draft"
                session.addAttachment("owned-omitted-runtime.txt", "text/plain", bytes)
            }
            val key = withContext(Dispatchers.Main) { session.targetKey }
            val file = withContext(Dispatchers.Main) { session.attachments.single() }
            val before = snapshot(key)
            withContext(Dispatchers.Main) { assertTrue(session.supportsDesktopOpen); session.openCurrentChatOnDesktop() }
            f.eventually { f.busyTargetsEmpty(session) && server.requests.isNotEmpty() }
            assertEquals(before, snapshot(key))
            assertEquals("opened", NativeOperationJournal(f.store).records(key).single().phase)
            assertArrayEquals(bytes, NativeDraftFiles(f.store).bytes(file.manifest))
            f28OnlyNavigation(server.requests, 1)
        }
    }
    @Test fun explicitDisconnectedWorkspaceDoesNotAdvertiseOrDispatchIndependentOpen() = runBlocking {
        f.runtimeConnected = false
        CommandFixture { error("Disconnected workspace must not dispatch") }.use { server ->
            val session = f.readySession(server.endpoint)
            withContext(Dispatchers.Main) { assertFalse(session.supportsDesktopOpen); session.openCurrentChatOnDesktop(); assertFalse(session.desktopOpenBusy) }
            assertTrue(server.requests.isEmpty())
            assertTrue(NativeOperationJournal(f.store).records(withContext(Dispatchers.Main) { session.targetKey }).isEmpty())
        }
    }
    @Test fun unsupportedSessionDoesNotAdvertiseOrDispatchIndependentOpen() = runBlocking {
        f.supported = false
        CommandFixture { error("No supported open capability") }.use { server ->
            val session = f.readySession(server.endpoint)
            withContext(Dispatchers.Main) { assertFalse(session.supportsDesktopOpen); session.openCurrentChatOnDesktop(); assertFalse(session.desktopOpenBusy) }
            assertTrue(server.requests.isEmpty())
            assertTrue(NativeOperationJournal(f.store).records(withContext(Dispatchers.Main) { session.targetKey }).isEmpty())
        }
    }
}
