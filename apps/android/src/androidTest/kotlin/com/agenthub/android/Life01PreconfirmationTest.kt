package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** An authenticated encrypted event deliberately precedes key confirmation. */
class Life01PreconfirmationTest {
    @Test fun unconfirmedEventIsAcknowledgedButNotApplied()=runBlocking {
        val v=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) }
        val c=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=c.binding.target
        val candidate=AgentCandidate("preconfirm","lan-wss","https://127.0.0.1:4001")
        val peer=NativeEasyWorkProtocolPeer(c,v.getString("confirmation")) { JSONObject().put("ok",true) }
        val acknowledged=AtomicInteger();val firstAck=CompletableDeferred<Unit>()
        val registry=AgentTransportRegistry().apply { register("lan-wss") {_,_,_,_->object:AgentFrameTransport {
            var firstSend=true;var firstReceive=true;var saved:ByteArray?=null
            override suspend fun send(bytes:ByteArray){
                peer.send(bytes)
                if(firstSend){firstSend=false;peer.emitThreadEvent(JSONObject().put("threadId","synthetic-thread").put("threadSeq",1).put("payload",JSONObject()))}
            }
            override suspend fun receive():ByteArray {
                if(firstReceive){firstReceive=false;val confirmation=peer.receive();val early=peer.receive();saved=confirmation;return early}
                val value=saved;if(value!=null){saved=null;return value};return peer.receive()
            }
            override suspend fun acknowledge(bytes:ByteArray){acknowledged.incrementAndGet();firstAck.complete(Unit);peer.acknowledge(bytes)}
            override fun close()=peer.close()
        } } }
        val session=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(candidate),"preconfirm","lan-only"),c,{error("no token")},registry)
        try {
            assertTrue(withTimeout(5_000){session.request("runtime/context")}.getBoolean("ok"))
            withTimeout(5_000){firstAck.await()};assertTrue(acknowledged.get()>=2)
            assertNull("unconfirmed event must not advance thread cursor",withTimeoutOrNull(1_000){session.events("synthetic-thread",0)})
            peer.emitThreadEvent(JSONObject().put("threadId","synthetic-thread").put("threadSeq",2).put("payload",JSONObject()))
            assertEquals(2L,withTimeout(5_000){session.events("synthetic-thread",0)})
        } finally {session.close()}
    }
}
