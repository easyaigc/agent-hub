package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic encrypted peer; no actual Host or model input. Production session and real deadlines. */
class Life01T14HandshakeTest {
    private val vector get()=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) }
    private fun candidates()=(1..4).map { AgentCandidate("t14-$it","lan-wss","https://127.0.0.1:${4000+it}") }
    @Test fun cancelledFirstReadPreservesOtherReadAndCapturedWrite()=runBlocking {
        val v=vector;val c=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=c.binding.target
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();val opens=AtomicInteger();val requests=CopyOnWriteArrayList<JSONObject>()
        val registry=AgentTransportRegistry().apply {register("lan-wss") {_,_,_,_->
            opens.incrementAndGet();val peer=NativeEasyWorkProtocolPeer(c,v.getString("confirmation")) { request -> requests.add(JSONObject(request.toString()));JSONObject().put("ok",true) }
            object:AgentFrameTransport {
                override suspend fun send(bytes:ByteArray){if(!started.isCompleted){started.complete(Unit);gate.await()};peer.send(bytes)}
                override suspend fun receive()=peer.receive()
                override suspend fun acknowledge(bytes:ByteArray)=peer.acknowledge(bytes)
                override fun close()=peer.close()
            }
        }}
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(candidates().first()),"t14","lan-only"),c,{error("no token")},registry)
        val params=JSONObject().put("threadId","synthetic-thread").put("turnId","synthetic-turn").put("clientRequestId","synthetic-operation")
        val expected=easyCanonical(params)
        try {
            val first=async {session.request("project/list")};withTimeout(5_000){started.await()}
            val second=async {session.request("thread/list",id="second-read")}
            val write=async {session.request("turn/cancel",params,"synthetic-request")}
            delay(100);first.cancelAndJoin();gate.complete(Unit)
            withTimeout(5_000){second.await();write.await()}
            assertEquals(1,opens.get());val writes=requests.filter {it.getString("method")=="turn/cancel"};assertEquals(1,writes.size)
            val actual=writes.single();assertEquals("synthetic-request",actual.getString("requestId"));assertEquals(expected,easyCanonical(actual.getJSONObject("params")))
            assertEquals(target,EasyWorkTarget.decode(actual.getJSONObject("target")))
        } finally {gate.complete(Unit);session.close()}
    }
    @Test fun thirtySecondConnectionDeadlineReleasesAllWaitersAndNextFlight()=runBlocking {
        val v=vector;val c=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=c.binding.target
        val opens=AtomicInteger();val closes=AtomicInteger();val mode=Flag()
        val registry=AgentTransportRegistry().apply {register("lan-wss") {_,_,_,_->
            opens.incrementAndGet()
            if(mode.good) NativeEasyWorkProtocolPeer(c,v.getString("confirmation")){JSONObject().put("ok",true)} else object:AgentFrameTransport {
                override suspend fun send(bytes:ByteArray):Unit=awaitCancellation()
                override suspend fun receive():ByteArray=awaitCancellation()
                override fun close(){closes.incrementAndGet()}
            }
        }}
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),candidates(),"t14","lan-only"),c,{error("no token")},registry)
        try {
            val start=System.nanoTime();val a=async {runCatching {session.request("project/list")}};val b=async {runCatching {session.request("thread/list")}}
            val both=withTimeout(35_000){a.await() to b.await()};val elapsed=(System.nanoTime()-start)/1_000_000
            assertTrue(both.first.isFailure);assertTrue(both.second.isFailure);assertTrue("actual producer deadline: $elapsed ms",elapsed in 29_000..34_500)
            assertTrue("four candidates attempted",opens.get()>=4);assertTrue(closes.get()>=3)
            mode.good=true
            assertTrue(withTimeout(5_000){session.request("model/list")}.getBoolean("ok"))
        } finally {session.close()}
    }
    @Test fun nonCooperativeOldFactoryCannotBlockDeadlineOrOverrideNewFlight()=runBlocking {
        val v=vector;val c=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=c.binding.target
        val opens=AtomicInteger();val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();val oldClosed=AtomicInteger();val newClosed=AtomicInteger()
        val registry=AgentTransportRegistry().apply {register("lan-wss") {_,_,_,_->
            val n=opens.incrementAndGet();val peer=NativeEasyWorkProtocolPeer(c,v.getString("confirmation")){JSONObject().put("ok",true)}
            if(n==1){entered.complete(Unit);withContext(NonCancellable){gate.await()}}
            object:AgentFrameTransport {
                override suspend fun send(bytes:ByteArray)=peer.send(bytes)
                override suspend fun receive()=peer.receive()
                override suspend fun acknowledge(bytes:ByteArray)=peer.acknowledge(bytes)
                override fun close(){if(n==1)oldClosed.incrementAndGet() else newClosed.incrementAndGet();peer.close()}
            }
        }}
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(candidates().first()),"t14","lan-only"),c,{error("no token")},registry)
        try {
            val first=async {runCatching {session.request("project/list")}};withTimeout(5_000){entered.await()}
            val waiting=async {runCatching {session.request("thread/list")}}
            val results=withTimeoutOrNull(32_000){first.await() to waiting.await()}
            assertNotNull("30s producer deadline must release waiters even if old factory ignores cancellation",results)
            assertTrue(results!!.first.isFailure);assertTrue(results.second.isFailure)
            val next=async {session.request("model/list")};assertTrue(withTimeout(5_000){next.await()}.getBoolean("ok"))
            val newEpoch=session.epoch;gate.complete(Unit);delay(500)
            assertEquals(newEpoch,session.epoch);assertEquals(0,newClosed.get());assertTrue(oldClosed.get()>0)
            assertTrue(withTimeout(5_000){session.request("usage/read")}.getBoolean("ok"))
        } finally {gate.complete(Unit);session.close()}
    }
    private class Flag {@Volatile var good=false}
}
