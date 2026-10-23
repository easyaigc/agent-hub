package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** Owned HTTP peer and independent encrypted peer; these commands never reach a model. */
class MobileQueueCommandTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private fun client(server: CommandFixture)=MobileClient("queue-host","queue command fixture",
        TargetScope("codex",null,null,"queue-host","original-workspace","original-runtime"),server.endpoint,true,"desktop-runtime",false)
    private val http=NativeCodexCommands { "synthetic-queue-token" }
    private suspend fun rejected(block: suspend ()->Unit) { assertTrue("Expected command rejection",runCatching { block() }.isFailure) }
    @Test fun httpExecuteBindsOriginalMessageAndRunWithoutResubmittingInput()=runBlocking {
        CommandFixture { WireResponse(body="{\"ok\":true}") }.use { peer ->
            val host=client(peer)
            val registry=AgentRegistry(NativeHostReads { "" },http)
            registry.executeQueue(host,"original-thread","original-message","original-turn")
            registry.executeQueue(host,"original-thread","idle-original-message",null)
            registry.cancelQueue(host,"original-thread","cancel-original-message")
            assertEquals(listOf("/v1/queue/execute","/v1/queue/execute","/v1/queue/cancel"),peer.requests.map { it.path })
            for((index,request) in peer.requests.withIndex()) {
                assertEquals("POST",request.method);assertEquals("Bearer synthetic-queue-token",request.headers["authorization"])
                val query=request.pathWithQuery.substringAfter('?').split('&').associate { URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('='),"UTF-8") }
                assertEquals("queue-host",query["hostId"]);assertEquals(easyCanonical(CodexCommandCodec.target(host.scope!!)),easyCanonical(JSONObject(query.getValue("target"))))
                val body=JSONObject(String(request.body,Charsets.UTF_8))
                assertEquals("queue-host",body.getString("hostId"));assertEquals("original-thread",body.getString("threadId"))
                assertEquals(listOf("original-message","idle-original-message","cancel-original-message")[index],body.getString("clientMessageId"))
                assertEquals(if(index==0)setOf("hostId","threadId","clientMessageId","expectedTurnId") else setOf("hostId","threadId","clientMessageId"),body.keys().asSequence().toSet())
                if(index==0)assertEquals("original-turn",body.getString("expectedTurnId"))
            }
        }
    }
    @Test fun httpFalseWrongTypeAndLostResponsesNeverBecomeSuccessOrAutomaticRetry()=runBlocking {
        for(response in listOf(WireResponse(body="{\"ok\":false}"),WireResponse(body="{\"ok\":\"true\"}"),WireResponse(status=409),WireResponse(disconnect=true))) {
            CommandFixture { response }.use { peer ->
                rejected { http.executeQueue(client(peer),"original-thread","original-message",null) }
                assertEquals(1,peer.requests.size);assertEquals("/v1/queue/execute",peer.requests.single().path)
            }
        }
    }
    @Test fun invalidExecuteIdentityAndRuntimeAreRejectedBeforeAnyHttpWrite()=runBlocking {
        CommandFixture { WireResponse(body="{\"ok\":true}") }.use { peer ->
            val host=client(peer)
            rejected { http.executeQueue(host," ","original-message",null) }
            rejected { http.executeQueue(host,"original-thread","",null) }
            rejected { http.executeQueue(host,"original-thread","original-message"," ") }
            rejected { http.executeQueue(host.copy(scope=TargetScope("codex",null,null,"other-host","original-workspace","original-runtime")),"original-thread","original-message",null) }
            assertEquals(0,peer.requests.size)
        }
    }
    private suspend fun encrypted(reply: suspend (JSONObject)->JSONObject,block: suspend (EasyWorkAgentClient,MobileClient,List<JSONObject>)->Unit) {
        val vector=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("owned-peer","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val requests=CopyOnWriteArrayList<JSONObject>()
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_-> NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) { request -> requests+=JSONObject(request.toString());reply(request) } } }
        val remote=EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
        val host=MobileClient(connection.target.hostId,"encrypted queue fixture",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        try { block(EasyWorkAgentClient(EasyWorkChannels { remote }),host,requests) } finally { remote.close() }
    }
    @Test fun encryptedNamedActionsUseOriginalIdsAndOnlyExplicitExecutionAcknowledgements()=runBlocking {
        val states=listOf<Any>("submitted","completed","queued","uncertain","failed",true,JSONObject.NULL)
        for(state in states)encrypted({ JSONObject().put("state",state) }) { adapter,host,requests ->
            val registry=AgentRegistry(NativeHostReads { "" },http).apply { register("easywork",adapter,adapter) }
            val result=runCatching { registry.executeQueue(host,"original-thread","original-message","original-run") }
            assertEquals(state in listOf("submitted","completed"),result.isSuccess)
            val request=requests.single();assertEquals("queue/execute",request.getString("method"))
            val params=request.getJSONObject("params")
            assertEquals(setOf("threadId","messageId","expectedRunId"),params.keys().asSequence().toSet())
            assertEquals("original-thread",params.getString("threadId"));assertEquals("original-message",params.getString("messageId"));assertEquals("original-run",params.getString("expectedRunId"))
        }
    }
    @Test fun encryptedIdleExecuteOmitsRunAndCancellationRequiresTrueBoolean()=runBlocking {
        for(cancelled in listOf<Any>(true,false,"true",1,JSONObject.NULL))encrypted({ request ->
            if(request.getString("method")=="queue/execute")JSONObject().put("state","completed") else JSONObject().put("cancelled",cancelled)
        }) { adapter,host,requests ->
            adapter.executeQueue(host,"original-thread","idle-message",null)
            assertEquals(cancelled==true,runCatching { adapter.cancelQueue(host,"original-thread","cancel-message") }.isSuccess)
            assertEquals(listOf("queue/execute","queue/cancel"),requests.map { it.getString("method") })
            assertEquals(setOf("threadId","messageId"),requests[0].getJSONObject("params").keys().asSequence().toSet())
            assertEquals("idle-message",requests[0].getJSONObject("params").getString("messageId"));assertEquals("cancel-message",requests[1].getJSONObject("params").getString("messageId"))
        }
    }
    @Test fun lostEncryptedExecuteQueriesExactFingerprintAndNeverReplaysOriginalCommand()=runBlocking {
        for(mismatch in listOf(false,true)) {
            var original: JSONObject?=null
            encrypted({ request ->
                if(request.getString("method")=="queue/execute") { original=JSONObject(request.toString());throw java.io.IOException("Owned lost execute acknowledgement") }
                assertEquals("command/status",request.getString("method"))
                val captured=checkNotNull(original)
                assertEquals(captured.getString("requestId"),request.getJSONObject("params").getString("commandId"))
                val hash=easyHash(easyCanonical(JSONObject().put("target",captured.getJSONObject("target")).put("method",captured.getString("method")).put("params",captured.getJSONObject("params"))).toByteArray())
                JSONObject().put("state","completed").put("operationId",captured.getString("requestId")).put("method","queue/execute")
                    .put("target",captured.getJSONObject("target")).put("requestHash",if(mismatch)"wrong-fingerprint" else hash)
                    .put("response",JSONObject().put("ok",true).put("result",JSONObject().put("state","completed")))
            }) { adapter,host,requests ->
                assertEquals(!mismatch,runCatching { adapter.executeQueue(host,"original-thread","original-message","original-run") }.isSuccess)
                assertEquals(listOf("queue/execute","command/status"),requests.map { it.getString("method") })
                assertEquals(1,requests.count { it.getString("method")=="queue/execute" })
            }
        }
    }
    @Test fun invalidEncryptedActionIdentityNeverReachesPeer()=runBlocking {
        encrypted({ error("Invalid identity must not dispatch") }) { adapter,host,requests ->
            rejected { adapter.executeQueue(host,"","original-message",null) }
            rejected { adapter.executeQueue(host,"original-thread","original-message"," ") }
            rejected { adapter.cancelQueue(host,"original-thread","") }
            assertTrue(requests.isEmpty())
        }
    }
}
