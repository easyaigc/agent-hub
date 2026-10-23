package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real adapter + native encrypted request/session. Only the peer transport is an owned fixture. */
class MobileEasyWorkQueueReceiptTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @Test fun actualSendAndOriginalQueryPreserveTheSameQueueEvidenceAndRunIdentity()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation()
        val vector=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("fixture-relay","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val id=UUID.randomUUID().toString();val requests=CopyOnWriteArrayList<JSONObject>()
        val response=JSONObject().put("state","queued").put("threadId","original-thread").put("runId","original-native-run").put("turnId","old-turn")
            .put("queueSource","native").put("queuePresent",true).put("canExecuteNow",false).put("queueDisposition","future-opaque-value")
            .put("queueSyncError","original queue sync error").put("text","original native input").put("attachmentIds",JSONArray()).put("noticeContext","historical")
            .put("laterTurnId","later-native-run")
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_->NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) { request ->
            requests+=JSONObject(request.toString())
            when(request.getString("method")) {
                "turn/start","queue/status"->JSONObject(response.toString())
                "command/status"->JSONObject().put("state","completed").put("response",JSONObject().put("result",response))
                else->error("Unexpected fixture method")
            }
        } } }
        val remote=EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
        val client=MobileClient(connection.target.hostId,"native queue receipt",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        val adapter=EasyWorkAgentClient(EasyWorkChannels { remote })
        try {
            val sent=adapter.send(client,ReplyIntent(client.id,"original-thread",id,"original native input",emptyList(),"queue",null))
            val queried=adapter.receipt(client,"original-thread",id)
            val a=encodeReceipt(sent);val b=encodeReceipt(queried)
            for(field in listOf("queueSource","queuePresent","canExecuteNow","queueDisposition","queueSyncError","noticeContext","laterTurnId")) {
                assertEquals("Actual send must retain $field",response.get(field),a.opt(field))
                assertEquals("Actual original query must retain $field",response.get(field),b.opt(field))
            }
            assertEquals("original-native-run",sent.turnId);assertEquals(sent.turnId,queried.turnId)
            assertEquals(id,queried.operationId);assertEquals(client.id,queried.hostId);assertEquals("original-thread",queried.threadId)
            assertEquals(listOf("turn/start","command/status","queue/status"),requests.map { it.getString("method") })
            assertEquals(id,requests[0].getString("requestId"));assertEquals(id,requests[1].getJSONObject("params").getString("commandId"));assertEquals(id,requests[2].getJSONObject("params").getString("messageId"))
        } finally { remote.close() }
    }
    private suspend fun fixture(reply: suspend (JSONObject)->JSONObject,block: suspend (EasyWorkAgentClient,MobileClient,List<JSONObject>)->Unit) {
        val i=InstrumentationRegistry.getInstrumentation()
        val vector=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("fixture-relay","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val requests=CopyOnWriteArrayList<JSONObject>()
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_->NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) { request -> requests+=JSONObject(request.toString());reply(request) } } }
        val remote=EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
        val client=MobileClient(connection.target.hostId,"native queue receipt",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        try { block(EasyWorkAgentClient(EasyWorkChannels { remote }),client,requests) } finally { remote.close() }
    }
    @Test fun acceptedNativeQueueMarkerStillQueriesOnlyTheOriginalMessageAndPreservesTerminalState()=runBlocking {
        val id=UUID.randomUUID().toString()
        fixture({ request -> when(request.getString("method")) {
            "command/status"->JSONObject().put("state","completed").put("response",JSONObject().put("result",JSONObject()
                .put("state","submitted").put("threadId","original-thread").put("queueSource","native")))
            "queue/status"->JSONObject().put("state","completed").put("runId","native-original-run").put("threadId","original-thread")
                .put("queueSource","native").put("queuePresent",false).put("canExecuteNow",false).put("queueSyncError","completed sync issue").put("laterTurnId","later-run")
            else->error("Query must never send input")
        } }) { adapter,client,requests ->
            val result=adapter.receipt(client,"original-thread",id)
            assertEquals("completed",result.state);assertEquals("native-original-run",result.turnId);assertEquals(id,result.operationId)
            assertEquals("completed sync issue",result.queueSyncError);assertEquals(false,result.queuePresent);assertEquals("later-run",result.laterTurnId)
            assertEquals(listOf("command/status","queue/status"),requests.map { it.getString("method") })
            assertEquals(id,requests[1].getJSONObject("params").getString("messageId"));assertEquals("original-thread",requests[1].getJSONObject("params").getString("threadId"))
        }
    }
    @Test fun rawFailedOutcomeAndTurnAreNeverReplacedByOuterCompletedAcknowledgement()=runBlocking {
        fixture({ request ->
            require(request.getString("method")=="command/status")
            JSONObject().put("state","completed").put("response",JSONObject().put("error",JSONObject().put("message","outer failure"))
                .put("result",JSONObject().put("threadId","original-thread").put("state","failed").put("turnId","failed-native-turn").put("error","original native failure")))
        }) { adapter,client,requests ->
            val result=adapter.receipt(client,"original-thread","original-message")
            assertEquals("failed",result.state);assertEquals("failed-native-turn",result.turnId);assertEquals("original native failure",result.error)
            assertEquals(1,requests.size);assertEquals("command/status",requests.single().getString("method"))
        }
    }
    @Test fun sameVersionedReceiptWireVectorsMatchTheProductionSwiftDecoder() {
        val i=InstrumentationRegistry.getInstrumentation()
        val root=JSONObject(i.context.assets.open("easywork-receipt-v1.json").bufferedReader().use { it.readText() });assertEquals(1,root.getInt("version"))
        val routing=root.getJSONObject("routing")
        for(row in root.getJSONArray("cases").objects()) {
            val result=runCatching { EasyWorkReceiptNormalization.decode(row.getJSONObject("raw"),routing.getString("clientMessageId"),routing.getString("hostId"),routing.getString("threadId"),
                row.getString("defaultState"),row.optional("fallbackTurnId"),row.optional("fallbackError")) }
            if(!row.getBoolean("valid")) { assertTrue(row.getString("name"),result.isFailure);continue }
            val actual=encodeReceipt(result.getOrThrow());val expected=row.getJSONObject("expected")
            for(key in expected.keys())assertEquals(row.getString("name")+":"+key,easyCanonical(JSONObject().put("value",expected.get(key))),easyCanonical(JSONObject().put("value",actual.opt(key) ?: JSONObject.NULL)))
        }
    }

}
