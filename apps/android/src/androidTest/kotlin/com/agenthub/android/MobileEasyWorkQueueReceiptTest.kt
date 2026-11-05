package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
        val response=JSONObject().put("clientMessageId",id).put("state","queued").put("threadId","original-thread").put("runId","original-native-run").put("turnId","old-turn")
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
            "queue/status"->JSONObject().put("clientMessageId",id).put("state","completed").put("runId","native-original-run").put("threadId","original-thread")
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

    /** Real encrypted Session + actual adapter; only the peer returns public synthetic outcomes. */
    private suspend fun outcomeFixture(body: suspend (JSONObject)->JSONObject,
        block: suspend (EasyWorkAgentClient,MobileClient,List<JSONObject>,EasyWorkRemoteSession)->Unit) {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        val vector=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use {it.readText()})
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("receipt-only","relay-http","https://relay.invalid")),"receipt-synthetic-ref","relay-only")
        val requests=CopyOnWriteArrayList<JSONObject>()
        val transports=AgentTransportRegistry().apply {register("relay-http") {_,_,_,_->
            NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) {error("Explicit response body required")}
                .withResponseBody {request->requests+=JSONObject(request.toString());body(request)}
        }}
        val remote=EasyWorkRemoteSession(connection,credential,{"synthetic"},transports)
        val client=MobileClient(connection.target.hostId,"synthetic original receipt",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        try {withTimeout(15_000){block(EasyWorkAgentClient(EasyWorkChannels {remote}),client,requests,remote)}}finally {remote.close()}
    }
    private fun success(value: Any?)=JSONObject().put("ok",true).put("result",value?:JSONObject.NULL)
    private fun rejection(code: String)=JSONObject().put("ok",false).put("error",JSONObject().put("code",code).put("message","synthetic original rejection"))
    private fun unknown(request: JSONObject,state: String="uncertain",method: String="turn/start")=JSONObject()
        .put("operationId",request.getJSONObject("params").getString("commandId")).put("target",request.getJSONObject("target"))
        .put("threadId","original-thread").put("method",method).put("state",state).put("response",JSONObject().put("result",JSONObject.NULL))
    private fun queue(request: JSONObject)=JSONObject().put("clientMessageId",request.getJSONObject("params").getString("messageId"))
        .put("threadId","original-thread").put("state","uncertain").put("queueSource","native").put("queuePresent",true)
        .put("canExecuteNow",false).put("text","frozen original queued input").put("attachmentIds",JSONArray())
    private fun onlyOriginalReads(requests: List<JSONObject>,id: String) {
        assertTrue(requests.isNotEmpty());assertEquals("command/status",requests.first().getString("method"))
        for(request in requests)when(request.getString("method")) {
            "command/status"->assertEquals(id,request.getJSONObject("params").getString("commandId"))
            "queue/status"->{assertEquals(id,request.getJSONObject("params").getString("messageId"));assertEquals("original-thread",request.getJSONObject("params").getString("threadId"))}
            else->fail("Query published a non-read method")
        }
    }
    @Test(timeout=30_000) fun actualSteerRejectionsAndOriginalQueryCannotBecomeSubmittedOrBorrowTheOldRun()=runBlocking {
        for(status in listOf("stale","inactive")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->when(request.getString("method")) {
                "turn/steer"->success(JSONObject().put("accepted",false).put("status",status).put("runId","raw-old-run"))
                "command/status"->success(JSONObject().put("operationId",id).put("method","turn/steer").put("state","completed")
                    .put("threadId","original-thread").put("response",JSONObject().put("result",JSONObject().put("accepted",false).put("status",status))))
                else->error("Unexpected method")
            }}) {adapter,client,requests,_->
                val original=ReplyIntent(client.id,"original-thread",id,"public synthetic guidance",emptyList(),"steer","expected-original-run")
                val before=original.body().toString();val sent=adapter.send(client,original);val observed=adapter.receipt(client,"original-thread",id)
                for(value in listOf(sent,observed)) {assertEquals("failed",value.state);assertNull(value.turnId);assertEquals("原任务已变化或结束，追加未提交。",value.error);assertEquals(id,value.operationId)}
                assertEquals(before,original.body().toString());assertEquals(listOf("turn/steer","command/status"),requests.map {it.getString("method")})
                assertEquals(id,requests[0].getJSONObject("params").getString("guidanceId"))
            }
        }
    }
    @Test(timeout=45_000) fun unknownAndSubmittingCommandsReadOnlyTheirOriginalQueueWithoutReplayingInput()=runBlocking {
        for(state in listOf("uncertain","submitting")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->success(when(request.getString("method")) {"command/status"->unknown(request,state);"queue/status"->queue(request);else->error("No write allowed")})}) {adapter,client,requests,_->
                val receipt=adapter.receipt(client,"original-thread",id)
                assertEquals("uncertain",receipt.state);assertNull(receipt.turnId);assertEquals(true,receipt.queuePresent);assertEquals("native",receipt.queueSource)
                assertEquals("frozen original queued input",receipt.text);onlyOriginalReads(requests,id);assertEquals(2,requests.size)
            }
        }
    }
    @Test(timeout=20_000) fun missingCommandAcknowledgementCanRecoverAnAuthoritativeQueuedAdmission()=runBlocking {
        val id=UUID.randomUUID().toString()
        outcomeFixture({request->success(when(request.getString("method")) {"command/status"->unknown(request);"queue/status"->queue(request).put("state","queued");else->error("No write allowed")})}) {adapter,client,requests,_->
            val receipt=adapter.receipt(client,"original-thread",id)
            assertEquals("queued",receipt.state);assertEquals(id,receipt.operationId);assertEquals("native",receipt.queueSource);assertEquals(true,receipt.queuePresent)
            assertEquals(false,receipt.canExecuteNow);assertNull(receipt.turnId);onlyOriginalReads(requests,id);assertEquals(2,requests.size)
        }
    }
    @Test(timeout=45_000) fun authenticatedNullAndOnlyTypedOldPortAbsenceKeepTheOriginalUnknown()=runBlocking {
        for(state in listOf("uncertain","submitting"))for(code in listOf<String?>(null,"method_unavailable","queue_unavailable")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->when(request.getString("method")) {"command/status"->success(unknown(request,state));"queue/status"->if(code==null)success(JSONObject.NULL) else rejection(code);else->error("No write allowed")}}) {adapter,client,requests,_->
                val receipt=adapter.receipt(client,"original-thread",id)
                assertEquals("uncertain",receipt.state);assertNull(receipt.turnId);assertNull(receipt.queueSource);assertNull(receipt.queuePresent)
                onlyOriginalReads(requests,id);assertEquals(2,requests.size)
            }
        }
    }
    @Test(timeout=45_000) fun authorizationAndUnclassifiedNotFoundAreNotSwallowedOrMadeRetryable()=runBlocking {
        for(code in listOf("device_not_authorized","grant_revoked","scope_denied","not_found")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->when(request.getString("method")) {"command/status"->success(unknown(request));"queue/status"->rejection(code);else->error("No write allowed")}}) {adapter,client,requests,_->
                val error=runCatching {adapter.receipt(client,"original-thread",id)}.exceptionOrNull()
                assertTrue(error is EasyWorkRejection);assertEquals(code,(error as EasyWorkRejection).code)
                onlyOriginalReads(requests,id);assertEquals(2,requests.size)
            }
        }
    }
    @Test(timeout=45_000) fun wrongOriginalQueueIdentityCannotSettleTheCapturedInput()=runBlocking {
        for(field in listOf("clientMessageId","threadId","target")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->when(request.getString("method")) {
                "command/status"->success(unknown(request))
                "queue/status"->success(queue(request).put(field,if(field=="target")JSONObject(request.getJSONObject("target").toString()).put("runtimeId","other-runtime") else "other-original"))
                else->error("No write allowed")
            }}) {adapter,client,requests,_->assertTrue(runCatching {adapter.receipt(client,"original-thread",id)}.isFailure);onlyOriginalReads(requests,id)}
        }
    }
    @Test(timeout=45_000) fun alteredCommandMetadataIsRejectedBeforeAnyQueueRead()=runBlocking {
        for(field in listOf("operationId","clientMessageId","threadId","hostId","target")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->
                require(request.getString("method")=="command/status")
                success(unknown(request).put(field,if(field=="target")JSONObject(request.getJSONObject("target").toString()).put("runtimeId","other-runtime") else "other-original"))
            }) {adapter,client,requests,_->
                assertTrue(runCatching {adapter.receipt(client,"original-thread",id)}.isFailure);onlyOriginalReads(requests,id);assertEquals(1,requests.size)
            }
        }
    }
    @Test(timeout=30_000) fun aDifferentOriginalResultCannotBeRelabeledAsTheCapturedReceipt()=runBlocking {
        for(field in listOf("operationId","clientMessageId")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->
                require(request.getString("method")=="command/status")
                success(unknown(request,"completed").put("response",JSONObject().put("result",JSONObject().put("threadId","original-thread")
                    .put("state","failed").put(field,"other-original-input"))))
            }) {adapter,client,requests,_->
                assertTrue(runCatching {adapter.receipt(client,"original-thread",id)}.isFailure);onlyOriginalReads(requests,id);assertEquals(1,requests.size)
            }
        }
    }
    @Test(timeout=45_000) fun malformedCommandResultsDoNotTriggerAnOriginalQueueQuery()=runBlocking {
        for(value in listOf<Any>(JSONArray(),"not-an-object",false,42)) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->
                require(request.getString("method")=="command/status")
                success(unknown(request).put("response",JSONObject().put("result",value)))
            }) {adapter,client,requests,_->
                assertTrue(runCatching {adapter.receipt(client,"original-thread",id)}.isFailure);onlyOriginalReads(requests,id);assertEquals(1,requests.size)
            }
        }
    }
    @Test(timeout=45_000) fun nullCompletedResultAndOtherMethodsNeverInventSubmissionOrBorrowAQueue()=runBlocking {
        for(pair in listOf("completed" to "turn/start","uncertain" to "thread/create")) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->require(request.getString("method")=="command/status");success(unknown(request,pair.first,pair.second))}) {adapter,client,requests,_->
                val receipt=adapter.receipt(client,"original-thread",id);assertEquals("uncertain",receipt.state);assertNull(receipt.turnId);assertNull(receipt.queueSource)
                onlyOriginalReads(requests,id);assertEquals(1,requests.size)
            }
        }
    }
    @Test(timeout=45_000) fun malformedOrEmptyQueueObjectsAreNotTheSameAsAuthenticatedNull()=runBlocking {
        for(value in listOf<Any>(JSONObject(),JSONArray(),"not-an-object",false,42)) {
            val id=UUID.randomUUID().toString()
            outcomeFixture({request->when(request.getString("method")) {"command/status"->success(unknown(request));"queue/status"->success(value);else->error("No write allowed")}}) {adapter,client,requests,_->
                assertTrue(runCatching {adapter.receipt(client,"original-thread",id)}.isFailure);onlyOriginalReads(requests,id)
                assertTrue("Existing bounded read retry may issue at most two queue reads",requests.count {it.getString("method")=="queue/status"}<=2)
            }
        }
    }
    @Test(timeout=20_000) fun refreshedCandidatePathsDoNotChangeTheOriginalConnectionIdentity()=runBlocking {
        val id=UUID.randomUUID().toString()
        outcomeFixture({request->success(when(request.getString("method")) {"command/status"->unknown(request);"queue/status"->queue(request);else->error("No write allowed")})}) {adapter,client,requests,_->
            val routed=client.copy(agentConnection=client.agentConnection!!.copy(candidates=listOf(AgentCandidate("refreshed-route","relay-http","https://relay-new.invalid"))))
            val receipt=adapter.receipt(routed,"original-thread",id)
            assertEquals(id,receipt.operationId);assertEquals("uncertain",receipt.state);assertEquals(true,receipt.queuePresent);onlyOriginalReads(requests,id)
        }
    }
    @Test(timeout=20_000) fun replacedCredentialOwnerIsRejectedBeforeAnyStatusRead()=runBlocking {
        outcomeFixture({error("Wrong owner must not publish")}) {adapter,client,requests,_->
            val replacement=client.copy(agentConnection=client.agentConnection!!.copy(credentialRef="new-synthetic-credential-ref"))
            assertTrue(runCatching {adapter.receipt(replacement,"original-thread",UUID.randomUUID().toString())}.isFailure);assertTrue(requests.isEmpty())
        }
    }
    @Test(timeout=20_000) fun delayedOriginalQueueResultCannotCompleteAfterItsOriginalOwnerCloses()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val id=UUID.randomUUID().toString()
        outcomeFixture({request->when(request.getString("method")) {
            "command/status"->success(unknown(request))
            "queue/status"->{entered.complete(Unit);release.await();success(queue(request))}
            else->error("No write allowed")
        }}) {adapter,client,requests,remote->
            coroutineScope {
                val observation=async {runCatching {adapter.receipt(client,"original-thread",id)}}
                try {withTimeout(5_000){entered.await()};remote.close();release.complete(Unit);assertTrue(withTimeout(5_000){observation.await()}.isFailure)}
                finally {release.complete(Unit);observation.cancelAndJoin()}
                onlyOriginalReads(requests,id)
            }
        }
    }

}
