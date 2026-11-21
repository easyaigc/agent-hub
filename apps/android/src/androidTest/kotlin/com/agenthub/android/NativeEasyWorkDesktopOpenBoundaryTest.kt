package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Shipping EasyWork adapter + encrypted RemoteSession + existing independent synthetic peer.
 * AgentTransportRegistry selects the in-memory peer; relay.invalid is never resolved or contacted.
 */
class NativeEasyWorkDesktopOpenBoundaryTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(30)
    @Before fun admission() {
        val i = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("p2F28Boundaries") == "1")
        check(i.targetContext.packageName == "com.easyai.agenthub.android.acceptance" && i.context.packageName == "com.easyai.agenthub.android.acceptance.test")
    }
    private suspend fun fixture(reply: suspend (JSONObject, EasyWorkTarget) -> JSONObject,
        block: suspend (EasyWorkAgentClient, MobileClient, List<JSONObject>, EasyWorkTarget) -> Unit) {
        val vector = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential = EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val target = credential.binding.target
        val connection = AgentConnection(target.scope(), listOf(AgentCandidate("owned-f28-peer", "relay-http", "https://relay.invalid")), "f28-inert-fixture", "relay-only")
        val requests = CopyOnWriteArrayList<JSONObject>()
        val transports = AgentTransportRegistry().apply {
            register("relay-http") { _, _, _, _ ->
                NativeEasyWorkProtocolPeer(credential, vector.getString("confirmation")) { error("Response body is explicit") }
                    .withResponseBody { request -> requests += JSONObject(request.toString()); reply(request, target) }
            }
        }
        val remote = EasyWorkRemoteSession(connection, credential, { "inert-synthetic-token" }, transports)
        val client = MobileClient("f28-local-projection", "Owned F28 EasyWork", target.scope(), "https://relay.invalid", true, "easywork-runtime", false, agentConnection = connection)
        try { block(EasyWorkAgentClient(EasyWorkChannels { remote }), client, requests, target) }
        finally { remote.close() }
    }
    private fun ok(result: JSONObject) = JSONObject().put("ok", true).put("result", result)
    private fun context(target: EasyWorkTarget, supported: Boolean = true) = JSONObject().put("target", target.json())
        .put("capabilities", JSONArray(if (supported) listOf("desktop:open") else emptyList<String>()))
    private fun receipt(request: JSONObject, target: EasyWorkTarget, phase: String = "opened") = JSONObject()
        .put("hostId", target.desktopDeviceId).put("threadId", request.getJSONObject("params").getString("threadId"))
        .put("clientOperationId", request.getJSONObject("params").getString("clientOperationId"))
        .put("phase", phase).put("error", if (phase == "outcome-unknown") "owned unknown" else JSONObject.NULL)
    private fun noInput(requests: List<JSONObject>, operation: String) {
        requests.forEach { request ->
            assertTrue(request.getString("method") in setOf("runtime/context", "thread/open", "thread/open-status"))
            if (request.getString("method") != "runtime/context") {
                val params = request.getJSONObject("params")
                assertEquals(setOf("threadId", "clientOperationId"), params.keys().asSequence().toSet())
                assertEquals("owned-thread", params.getString("threadId")); assertEquals(operation, params.getString("clientOperationId"))
            }
        }
    }
    @Test fun namedOpenAndStatusPreserveOriginalIdentityAndProviderHostProjection() = runBlocking {
        val operation = "ORIGINAL-OPEN-" + UUID.randomUUID().toString().uppercase()
        fixture({ request, target -> ok(if (request.getString("method") == "runtime/context") context(target) else receipt(request, target)) }) { adapter, client, requests, _ ->
            assertTrue(adapter.requiresConfirmedDesktopOpenReceipt(client))
            val opened = adapter.open(client, "owned-thread", operation)
            val queried = adapter.open(client, "owned-thread", operation, queryOnly = true)
            assertEquals(client.id, opened.hostId); assertEquals(operation, opened.operationId); assertEquals(opened, queried)
            assertEquals(listOf("runtime/context", "thread/open", "thread/open-status"), requests.map { it.getString("method") })
            assertEquals(operation, requests.single { it.getString("method") == "thread/open" }.getString("requestId"))
            noInput(requests, operation)
        }
    }
    @Test fun writeInterruptionQueriesStatusAndReadInterruptionRetriesOnlyStatus() = runBlocking {
        val operation = UUID.randomUUID().toString(); val openFailure = AtomicBoolean(); val statusFailure = AtomicBoolean()
        fixture({ request, target ->
            when (request.getString("method")) {
                "runtime/context" -> ok(context(target))
                "thread/open" -> { check(openFailure.compareAndSet(false, true)); throw IOException("Owned interrupted write") }
                "thread/open-status" -> { if (statusFailure.compareAndSet(false, true)) throw IOException("Owned interrupted read"); ok(receipt(request, target)) }
                else -> error("No business method")
            }
        }) { adapter, client, requests, _ ->
            val result = adapter.open(client, "owned-thread", operation)
            assertEquals("opened", result.phase)
            assertEquals(1, requests.count { it.getString("method") == "thread/open" })
            val reads = requests.filter { it.getString("method") == "thread/open-status" }
            assertEquals(2, reads.size); assertEquals(reads[0].getString("requestId"), reads[1].getString("requestId"))
            noInput(requests, operation)
        }
    }
    @Test fun unknownAndNotFoundAreNeverReplayedAsAnotherOpen() = runBlocking {
        val operation = UUID.randomUUID().toString()
        fixture({ request, target -> when (request.getString("method")) {
            "runtime/context" -> ok(context(target))
            "thread/open" -> ok(receipt(request, target, "outcome-unknown"))
            "thread/open-status" -> JSONObject().put("ok", false).put("error", JSONObject().put("code", "desktop_open_not_found").put("message", "Owned missing operation"))
            else -> error("No business method")
        } }) { adapter, client, requests, _ ->
            assertEquals("outcome-unknown", adapter.open(client, "owned-thread", operation).phase)
            repeat(2) { assertTrue(runCatching { adapter.open(client, "owned-thread", operation, queryOnly = true) }.isFailure) }
            assertEquals(1, requests.count { it.getString("method") == "thread/open" })
            assertEquals(2, requests.count { it.getString("method") == "thread/open-status" }); noInput(requests, operation)
        }
    }
    @Test fun foreignPayloadHostThreadOrOperationIsRejectedAfterValidEncryptedEnvelope() = runBlocking {
        for (field in listOf("hostId", "threadId", "clientOperationId")) {
            val operation = UUID.randomUUID().toString()
            fixture({ request, target -> ok(if (request.getString("method") == "runtime/context") context(target)
                else receipt(request, target).put(field, "foreign-value")) }) { adapter, client, requests, _ ->
                assertTrue(field, runCatching { adapter.open(client, "owned-thread", operation) }.isFailure)
                assertEquals(1, requests.count { it.getString("method") == "thread/open" }); noInput(requests, operation)
            }
        }
    }
    @Test fun missingCapabilityRefusesWriteButKeepsOriginalReadOnlyStatusAvailable() = runBlocking {
        val operation = UUID.randomUUID().toString()
        fixture({ request, target -> ok(if (request.getString("method") == "runtime/context") context(target, false) else receipt(request, target)) }) { adapter, client, requests, _ ->
            assertTrue(runCatching { adapter.open(client, "owned-thread", operation) }.isFailure)
            assertEquals("opened", adapter.open(client, "owned-thread", operation, queryOnly = true).phase)
            assertEquals(listOf("runtime/context", "thread/open-status"), requests.map { it.getString("method") })
            noInput(requests, operation)
        }
    }
}
