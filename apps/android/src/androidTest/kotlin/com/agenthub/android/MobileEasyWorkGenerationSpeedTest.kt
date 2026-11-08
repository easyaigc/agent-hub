package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Production adapter and authenticated session against an independent synthetic peer. */
class MobileEasyWorkGenerationSpeedTest {
    private suspend fun fixture(
        reply: (JSONObject) -> JSONObject,
        check: suspend (EasyWorkAgentClient, MobileClient, List<JSONObject>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val vector = JSONObject(context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential = EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection = AgentConnection(credential.binding.target.scope(),
            listOf(AgentCandidate("speed-peer", "relay-http", "https://relay.invalid")), "speed-fixture", "relay-only")
        val requests = CopyOnWriteArrayList<JSONObject>()
        val transports = AgentTransportRegistry().apply {
            register("relay-http") { _, _, _, _ ->
                NativeEasyWorkProtocolPeer(credential, vector.getString("confirmation")) { request ->
                    requests += JSONObject(request.toString())
                    reply(request)
                }
            }
        }
        val remote = EasyWorkRemoteSession(connection, credential, { "synthetic" }, transports)
        val client = MobileClient(connection.target.hostId, "speed fixture", connection.target,
            "https://relay.invalid", true, "easywork-runtime", false, agentConnection = connection)
        try {
            check(EasyWorkAgentClient(EasyWorkChannels { remote }), client, requests)
        } finally {
            remote.close()
        }
    }

    @Test fun modelCatalogPreservesDeclaredDefaultsAndOptionalSpeedMetadata() = runBlocking {
        val models = JSONArray().put(JSONObject().put("id", "provider/fast-only").put("name", "Fast only")
            .put("isDefault", true).put("defaultSpeed", "fast").put("supportedSpeeds", JSONArray(listOf("fast"))))
            .put(JSONObject().put("id", "provider/legacy").put("name", "Legacy").put("isDefault", false))
        fixture({ JSONObject().put("models", models) }) { adapter, client, requests ->
            val decoded = adapter.models(client)
            assertEquals("fast", decoded.first().defaultSpeed)
            assertEquals(listOf("fast"), decoded.first().supportedSpeeds)
            assertNull(decoded.last().defaultSpeed)
            assertTrue(decoded.last().supportedSpeeds.isEmpty())
            assertEquals(listOf("model/list"), requests.map { it.getString("method") })
        }
    }

    @Test fun runtimeUsesRecordedSpeedAndLegacyRunDoesNotInheritTodaysDefault() = runBlocking {
        for (latest in listOf<JSONObject?>(null, JSONObject().put("id", "old").put("status", "completed"),
            JSONObject().put("id", "current").put("status", "completed").put("settings", JSONObject().put("speed", "fast")))) {
            val snapshot = JSONObject().put("thread", JSONObject().put("id", "thread")
                .put("generationPreferences", JSONObject().put("speed", "standard")))
            latest?.let { snapshot.put("latestRun", it) }
            fixture({ JSONObject().put("snapshot", snapshot).put("branchRevision", 1).put("messages", JSONArray()) }) { adapter, client, requests ->
                val runtime = adapter.history(client, "thread", null).runtime
                val expected = if (latest == null) "standard" else latest.optJSONObject("settings")?.optional("speed")
                assertEquals(expected, runtime?.settings?.speed)
                assertEquals(listOf("thread/sync"), requests.map { it.getString("method") })
            }
        }
    }

    @Test fun nextTurnQueueAndPlanKeepExplicitSpeedWhileSteerOmitsGenerationSettings() = runBlocking {
        fixture({ request -> JSONObject().put("state", "submitted").put("clientMessageId", request.getString("requestId"))
            .put("threadId", "thread").put("turnId", "run") }) { adapter, client, requests ->
            val settings = GenerationSettings(model = "provider/model", speed = "fast")
            for (mode in listOf("next-turn", "queue", "steer")) {
                adapter.send(client, ReplyIntent(client.id, "thread", "speed-$mode", "fixture", emptyList(),
                    mode, expectedTurnId = if (mode == "steer") "run" else null, settings = settings))
            }
            val plan = "easywork-plan." + easyBase64(JSONObject().put("id", "plan").put("version", 2).toString().toByteArray())
            adapter.send(client, ReplyIntent(client.id, "thread", "speed-plan", "fixture", emptyList(),
                "next-turn", expectedPlanId = plan, settings = settings))
            adapter.send(client, ReplyIntent(client.id, "thread", "speed-plan-revision", "fixture", emptyList(),
                "next-turn", expectedPlanId = plan, settings = settings.copy(collaborationMode = "plan")))
            assertEquals(listOf("turn/start", "turn/start", "turn/steer", "plan/action", "turn/start"), requests.map { it.getString("method") })
            for (index in listOf(0, 1, 3, 4)) {
                val submitted = requests[index].getJSONObject("params").getJSONObject("settings")
                assertEquals("fast", submitted.getString("speed"))
                assertEquals("provider/model", submitted.getString("model"))
            }
            assertFalse(requests[2].getJSONObject("params").has("settings"))
            assertEquals("run", requests[2].getJSONObject("params").getString("turnId"))
            assertEquals("speed-steer", requests[2].getJSONObject("params").getString("guidanceId"))
            assertFalse(requests[3].getJSONObject("params").getJSONObject("settings").has("mode"))
            assertEquals("plan", requests[4].getJSONObject("params").getString("revisionOfPlanId"))
            assertEquals(2, requests[4].getJSONObject("params").getInt("expectedPlanVersion"))
            assertEquals("plan", requests[4].getJSONObject("params").getJSONObject("settings").getString("mode"))
        }
    }

    @Test fun unsupportedSpeedIsRejectedBeforeAnyProtocolRequest() = runBlocking {
        fixture({ error("Invalid speed must not reach peer") }) { adapter, client, requests ->
            val intent = ReplyIntent(client.id, "thread", "invalid-speed", "fixture", emptyList(), "next-turn",
                settings = GenerationSettings(speed = "undeclared-speed"))
            assertTrue(runCatching { adapter.send(client, intent) }.isFailure)
            assertTrue(requests.isEmpty())
        }
    }
}
