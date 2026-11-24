package com.agenthub.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled loopback fixture; never contacts a real Host or sends model input. */
class Life01Matrix2Test {
    @get:Rule val foreground: org.junit.rules.TestRule = NativeTestForeground()
    private suspend fun waitFor(timeout: Long=12_000, predicate: () -> Boolean) = withTimeout(timeout) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }
    @Test fun activityConfigurationRecreationRetainsSessionDraftAndProof() = runBlocking {
        ChatManagementFixture().use { fixture ->
            fixture.writableHistory=true
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val owned=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,"matrix2-"+UUID.randomUUID()),NativeHostReads { "" }).also { it.restore() } }
            waitFor { !owned.restoring }
            val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
            try {
                scenario.onActivity { it.adoptSessionForAcceptance(owned) }
                withContext(Dispatchers.Main) { owned.connect("matrix fixture",fixture.server.endpoint,"");owned.selectThread(owned.threads.first { t->t.id==ChatManagementFixture.first }) }
                waitFor { owned.canStop && !owned.loading && !owned.historyLoading }
                scenario.onActivity { owned.text="rotation preserves draft" }
                val before=fixture.server.requests.count { it.path=="/v1/history" }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertSame(owned,activity.session)
                    assertTrue(owned.selectedClient.online)
                    assertEquals("rotation preserves draft",owned.text)
                    assertEquals(ChatManagementFixture.first,owned.selectedThread)
                    assertTrue("configuration recreation must not revoke live proof",owned.canStop)
                }
                delay(1000)
                assertEquals("recreation must not restart history observer",before,fixture.server.requests.count { it.path=="/v1/history" })
                assertTrue(fixture.posts.isEmpty())
            } finally { scenario.close() }
        }
    }
    @Test fun authenticationAndWrongRuntimeAreRejectedByNativeReads() = runBlocking {
        val target=TargetScope("codex",null,null,"matrix-host","matrix-workspace","matrix-runtime")
        fun client(endpoint: String)=MobileClient(target.hostId,"matrix",target,endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
        val reads=NativeHostReads { "synthetic-token" }
        CommandFixture { WireResponse(401,"{\"error\":\"unauthorized\"}") }.use { server ->
            val failure=runCatching { reads.workspace(client(server.endpoint)) }.exceptionOrNull()
            assertTrue(failure is HostReadError)
            assertEquals("Bearer synthetic-token",server.requests.single().headers["authorization"])
        }
        CommandFixture { WireResponse(body=JSONObject().put("hostId",target.hostId).put("name","wrong")
            .put("readTarget",CodexCommandCodec.target(TargetScope("codex",null,null,"matrix-host","matrix-workspace","wrong-runtime")))
            .put("connectionMode","desktop-runtime").put("runtimeConnected",true).put("projects",JSONArray()).put("threads",JSONArray()).put("capabilities",JSONArray()).toString()) }.use { server ->
            assertNotNull(runCatching { reads.workspace(client(server.endpoint)) }.exceptionOrNull())
        }
    }
    @Test fun slowWorkspaceDoesNotBlockIndependentHistoryAndReceipt() = runBlocking {
        val target=TargetScope("codex",null,null,"matrix-host","matrix-workspace","matrix-runtime")
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        val workspace=JSONObject().put("hostId",target.hostId).put("name","fixture").put("readTarget",CodexCommandCodec.target(target))
            .put("connectionMode","desktop-runtime").put("runtimeConnected",true).put("projects",JSONArray()).put("threads",JSONArray()).put("capabilities",JSONArray())
        CommandFixture { request -> when(request.path) {
            "/v1/workspace" -> { started.countDown();release.await(45,TimeUnit.SECONDS);WireResponse(body=workspace.toString()) }
            "/v1/history" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId","matrix-thread").put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray()).toString())
            "/v1/receipts" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId","matrix-thread").put("clientMessageId","matrix-original").put("state","submitted").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL).toString())
            else -> WireResponse(404)
        } }.use { server ->
            val client=MobileClient(target.hostId,"matrix",target,server.endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
            val reads=NativeHostReads { "" };val commands=NativeCodexCommands { "" }
            try {
                val slow=async { runCatching { reads.workspace(client) } }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                delay(8_500)
                assertEquals("matrix-thread",withTimeout(5_000) { reads.history(client,"matrix-thread") }.threadId)
                assertEquals("submitted",withTimeout(5_000) { commands.receipt(client,"matrix-thread","matrix-original") }.state)
                delay(22_000)
                assertEquals("matrix-thread",withTimeout(5_000) { reads.history(client,"matrix-thread") }.threadId)
                assertEquals("submitted",withTimeout(5_000) { commands.receipt(client,"matrix-thread","matrix-original") }.state)
                release.countDown()
                assertTrue(withTimeout(7_000) { slow.await() }.isSuccess)
                assertTrue(server.requests.none { it.method=="POST" })
            } finally { release.countDown() }
        }
    }
}

class Life01Matrix2EasyWorkTest {
    @Test fun cancelledFirstWaiterDoesNotCancelSharedHandshake() = runBlocking {
        val v=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) }
        val credential=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=credential.binding.target
        val candidate=AgentCandidate("matrix-lan","lan-wss","https://127.0.0.1:1")
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();var opens=0
        val registry=AgentTransportRegistry().apply { register("lan-wss") { _,_,_,_ ->
            opens++
            val peer=NativeEasyWorkProtocolPeer(credential,v.getString("confirmation")) { JSONObject().put("value","ok") }
            object: AgentFrameTransport {
                override suspend fun send(bytes: ByteArray) { if(!started.isCompleted) { started.complete(Unit);gate.await() };peer.send(bytes) }
                override suspend fun receive()=peer.receive()
                override suspend fun acknowledge(bytes: ByteArray)=peer.acknowledge(bytes)
                override fun close()=peer.close()
            }
        } }
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(candidate),"matrix","lan-only"),credential,{ error("no token") },registry)
        try {
            val first=async { session.request("project/list") };withTimeout(5000) { started.await() }
            val second=async { session.request("thread/list") }
            first.cancelAndJoin();gate.complete(Unit)
            assertEquals("ok",withTimeout(5000) { second.await() }.getString("value"))
            assertEquals(1,opens)
        } finally { gate.complete(Unit);session.close() }
    }
}

class Life01MatrixTimeoutTest {
    @Test fun oneReadTimeoutDoesNotReconnectHealthySharedSession() = runBlocking {
        val v=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) }
        val credential=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=credential.binding.target
        val candidate=AgentCandidate("matrix-lan","lan-wss","https://127.0.0.1:1");var opens=0;val firstDropped=CompletableDeferred<Unit>()
        val registry=AgentTransportRegistry().apply { register("lan-wss") { _,_,_,_ ->
            opens++;val peer=NativeEasyWorkProtocolPeer(credential,v.getString("confirmation")) { JSONObject().put("value","ok") }
            object: AgentFrameTransport {
                var frames=0
                override suspend fun send(bytes: ByteArray)=peer.send(bytes)
                override suspend fun receive(): ByteArray {
                    val frame=peer.receive();frames++
                    if(frames==2) { firstDropped.complete(Unit);return peer.receive() }
                    return frame
                }
                override suspend fun acknowledge(bytes: ByteArray)=peer.acknowledge(bytes)
                override fun close()=peer.close()
            }
        } }
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(candidate),"matrix","lan-only"),credential,{ error("no token") },registry)
        try {
            val slow=async { runCatching { session.request("project/list") } }
            withTimeout(5_000) { firstDropped.await() }
            assertEquals("ok",withTimeout(5_000) { session.request("thread/list") }.getString("value"))
            assertTrue(withTimeout(36_000) { slow.await() }.isFailure)
            assertEquals(1,opens)
            assertEquals("ok",withTimeout(5_000) { session.request("model/list") }.getString("value"))
            assertEquals(1,opens)
        } finally {session.close()}
    }
}
