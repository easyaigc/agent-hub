package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Re-entry proof window against an isolated loopback server. */
class Life01MatrixProofTest {
    @get:Rule val foreground: org.junit.rules.TestRule = NativeTestForeground()
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(12_000) { while(!withContext(Dispatchers.Main) { predicate() }) delay(20) }
    @Test fun emptyEventsHealthAndReceiptCannotGrantControl() = runBlocking {
        ChatManagementFixture().use { base ->
            val target=base.target; val workspaceStarted=CountDownLatch(1);val historyStarted=CountDownLatch(1)
            val workspaceRelease=CountDownLatch(1);val historyRelease=CountDownLatch(1)
            val hold=Hold()
            fun workspace()=base.workspace().put("capabilities",JSONArray(listOf("events","commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
            fun history()=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(target))
                .put("messages",JSONArray()).put("runtime",JSONObject().put("state","running").put("turnId","matrix-turn").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
            CommandFixture { request -> when(request.path) {
                "/host" -> WireResponse(body=workspace().toString())
                "/v1/workspace" -> { if(hold.active) {workspaceStarted.countDown();workspaceRelease.await(15,TimeUnit.SECONDS)};WireResponse(body=workspace().toString()) }
                "/v1/history" -> { if(hold.active) {historyStarted.countDown();historyRelease.await(15,TimeUnit.SECONDS)};WireResponse(body=history().toString()) }
                "/v1/events" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("epoch","matrix").put("cursor",0).put("reset",false).put("ready",true).put("events",JSONArray()).toString())
                "/v1/receipts" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("clientMessageId","matrix-original").put("state","submitted").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL).toString())
                "/v1/models" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
                "/health" -> WireResponse(body="{\"ok\":true}")
                else -> WireResponse(404)
            } }.use { server ->
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val session=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,"matrix-proof-"+UUID.randomUUID()),NativeHostReads { "" }).also { it.restore() } }
                try {
                    waitFor { !session.restoring }
                    withContext(Dispatchers.Main) { session.enterForeground();session.connect("matrix",server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
                    waitFor { session.canStop && session.canCreateProjectless && !session.loading && !session.historyLoading }
                    val eventsBefore=server.requests.count { it.path=="/v1/events" }
                    withContext(Dispatchers.Main) { session.text="proof draft";session.leaveForeground();hold.active=true;session.enterForeground() }
                    assertTrue(withContext(Dispatchers.IO) { workspaceStarted.await(5,TimeUnit.SECONDS) });assertTrue(withContext(Dispatchers.IO) { historyStarted.await(5,TimeUnit.SECONDS) })
                    withTimeout(5_000) { while(server.requests.count { it.path=="/v1/events" }<=eventsBefore) delay(20) }
                    val client=withContext(Dispatchers.Main) { session.selectedClient }
                    assertEquals("submitted",NativeCodexCommands { "" }.receipt(client,ChatManagementFixture.first,"matrix-original").state)
                    assertEquals("{\"ok\":true}",withContext(Dispatchers.IO) { java.net.URL(server.endpoint+"/health").readText() })
                    withContext(Dispatchers.Main) {
                        assertTrue(session.selectedClient.online);assertFalse(session.canStop);assertFalse(session.canCreateProjectless)
                        session.performAction();session.stopTask();assertFalse(session.newChat());assertEquals("proof draft",session.text)
                    }
                    assertTrue(server.requests.none { it.method=="POST" })
                    historyRelease.countDown();waitFor { session.canStop }
                    withContext(Dispatchers.Main) { assertFalse(session.canCreateProjectless) }
                    workspaceRelease.countDown();waitFor { session.canCreateProjectless }
                    withContext(Dispatchers.Main) { assertTrue(session.canStop);assertEquals("proof draft",session.text) }
                    assertTrue(server.requests.none { it.method=="POST" })
                } finally {workspaceRelease.countDown();historyRelease.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
            }
        }
    }
    private class Hold { @Volatile var active=false }
}
