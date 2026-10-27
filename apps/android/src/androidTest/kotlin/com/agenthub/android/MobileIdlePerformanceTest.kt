package com.agenthub.android

import kotlinx.coroutines.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MobileIdlePerformanceTest {
    @get:Rule val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @Test fun eventBeforeSuspensionIsNotLost() = runBlocking {
        val signal = EasyWorkEventSignal()
        val observed = signal.revision
        signal.signal()
        withTimeout(1_000) { signal.awaitChange(observed, 12_000) }
    }

    @Test fun realEventWakesAllReadersAndNextReadWaitsAgain() = runBlocking {
        val signal = EasyWorkEventSignal()
        val observed = signal.revision
        val readers = List(3) { async(start = CoroutineStart.UNDISPATCHED) { signal.awaitChange(observed, 12_000) } }
        assertTrue(readers.none { it.isCompleted })
        signal.signal()
        withTimeout(1_000) { readers.awaitAll() }
        val next = async(start = CoroutineStart.UNDISPATCHED) { signal.awaitChange(signal.revision, 12_000) }
        assertFalse(next.isCompleted)
        next.cancelAndJoin()
    }

    @Test fun quietDeadlineAndCancellationLeaveNoSpinningReader() = runBlocking {
        val signal = EasyWorkEventSignal()
        withTimeout(1_000) { signal.awaitChange(signal.revision, 40) }
        repeat(50) {
            val wait = launch(start = CoroutineStart.UNDISPATCHED) { signal.awaitChange(signal.revision, 12_000) }
            wait.cancelAndJoin()
        }
        signal.signal()
        val observed = signal.revision
        val wait = async(start = CoroutineStart.UNDISPATCHED) { signal.awaitChange(observed, 12_000) }
        signal.signal()
        withTimeout(1_000) { wait.await() }
    }

    private suspend fun remoteFixture(block: suspend (EasyWorkRemoteSession, MobileClient, List<JSONObject>, ConcurrentHashMap<String, CompletableDeferred<Unit>>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vector = JSONObject(instrumentation.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential = EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection = AgentConnection(credential.binding.target.scope(), listOf(AgentCandidate("idle-relay", "relay-http", "https://relay.invalid")), "synthetic-ref", "relay-only")
        val requests = CopyOnWriteArrayList<JSONObject>()
        val subscribed = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val transports = AgentTransportRegistry().apply { register("relay-http") { _, _, _, _ ->
            NativeEasyWorkProtocolPeer(credential, vector.getString("confirmation")) { request ->
                requests.add(JSONObject(request.toString()))
                when (request.getString("method")) {
                    "thread/subscribe" -> {
                        subscribed.computeIfAbsent(request.getJSONObject("params").getString("threadId")) { CompletableDeferred() }.complete(Unit)
                        JSONObject().put("subscribed", true)
                    }
                    "thread/unsubscribe" -> JSONObject().put("subscribed", false)
                    "runtime/context" -> JSONObject()
                    else -> error("Quiet reads must not fetch history or send input")
                }
            }
        } }
        val remote = EasyWorkRemoteSession(connection, credential, { "synthetic" }, transports)
        val client = MobileClient(connection.target.hostId, "idle native boundary", connection.target, "https://relay.invalid", true, "easywork-runtime", false, agentConnection = connection)
        try { remote.request("runtime/context"); block(remote, client, requests, subscribed) } finally { remote.close() }
    }

    @Test fun encryptedQuietHeartbeatNeverRequestsCanonicalHistory() = runBlocking {
        remoteFixture { remote, client, requests, _ ->
            val epoch = remote.target.runtimeId + ":" + remote.epoch
            val adapter = EasyWorkAgentClient(EasyWorkChannels { remote })
            val batch = withTimeout(25_000) { adapter.events(client, "quiet-thread", epoch, 0) }
            assertEquals(epoch, batch.epoch); assertEquals(0L, batch.cursor)
            assertFalse(batch.reset); assertTrue(batch.snapshots.isEmpty())
            assertEquals(listOf("runtime/context", "thread/subscribe"), requests.map { it.getString("method") })
        }
    }

    @Test fun cancelledSubscribeIntentIsRetiredWhenTheReaderSwitches() = runBlocking {
        remoteFixture { remote, _, requests, subscribed ->
            for (thread in listOf("first", "second", "third")) {
                val arrived = subscribed.computeIfAbsent(thread) { CompletableDeferred() }
                val reader = async { remote.events(thread, 0) }
                withTimeout(5_000) { arrived.await() }
                reader.cancelAndJoin()
            }
            assertEquals(listOf("first", "second"), requests.filter { it.getString("method") == "thread/unsubscribe" }
                .map { it.getJSONObject("params").getString("threadId") })
            assertFalse(requests.any { it.getString("method") in listOf("thread/read", "thread/sync", "turn/start") })
        }
    }
}
