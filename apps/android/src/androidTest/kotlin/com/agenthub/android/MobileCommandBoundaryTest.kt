package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.OperationPolicies
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class MobileCommandBoundaryTest {
    private val commands=NativeCodexCommands { "synthetic-fixture-token" }
    private fun client(server: CommandFixture,host: String="fixture-host",runtime: String="fixture-runtime") = MobileClient(host,"fixture",
        TargetScope("codex",null,null,host,"fixture-workspace",runtime),server.endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
    private fun reply(host: String="fixture-host",thread: String="fixture-thread",id: String="fixture-operation") = ReplyIntent(host,thread,id,"  中文 😀\n",emptyList(),"next-turn")
    private fun receipt(intent: ReplyIntent,state: String="received") = JSONObject().put("hostId",intent.hostId).put("threadId",intent.threadId)
        .put("clientMessageId",intent.operationId).put("state",state).put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
    private fun journal()=NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"command-test-"+UUID.randomUUID()))
    @Test fun lostReplyIsQueriedWithSameIdAndNeverReplayed() = runBlocking {
        val intent=reply()
        CommandFixture { request -> if(request.path=="/v1/replies") WireResponse(disconnect=true) else WireResponse(body=receipt(intent,"submitted").toString()) }.use { server ->
            val client=client(server); val journal=journal(); val saved=journal.prepare(client,intent.threadId,"reply",intent.body())
            journal.markSubmitting(saved.target,saved.id)
            expectFailure { commands.send(client,intent) }
            journal.uncertain(saved.target,saved.id,"transport disconnected")
            val observed=commands.receipt(client,intent.threadId,intent.operationId)
            assertEquals("submitted",observed.state)
            journal.observed(saved.target,saved.id,observed.state,receipt(intent,observed.state))
            expectFailure { journal.markSubmitting(saved.target,saved.id) }
            val records=journal.records(saved.target); assertEquals(intent.text,JSONObject(records.single().intent).getString("text"))
            assertEquals(1,server.requests.count { it.path=="/v1/replies" })
            assertEquals(1,server.requests.count { it.path=="/v1/receipts" && it.pathWithQuery.contains("id=fixture-operation") })
            val parameters=server.requests.first().pathWithQuery.substringAfter('?').split('&').associate {
                java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8")
            }
            val bound=JSONObject(parameters.getValue("target"))
            assertEquals(client.id,bound.getString("hostId")); assertEquals("fixture-runtime",bound.getString("runtimeId"))
            assertEquals("fixture-workspace",bound.getString("workspaceId")); assertEquals("codex",bound.getString("agentId"))
        }
    }
    @Test fun cancelledQueueReceiptPersistsAndCannotRegressOrReplay() = runBlocking {
        val intent=reply().copy(mode="queue",expectedTurnId="fixture-turn")
        CommandFixture { request -> WireResponse(body=if(request.path=="/v1/queue/cancel") "{\"ok\":true}" else receipt(intent,"cancelled").toString()) }.use { server ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="cancelled-queue-"+UUID.randomUUID()
            val journal=NativeOperationJournal(NativeSessionStorage(context,namespace));val saved=journal.prepare(client(server),intent.threadId,"reply",intent.body())
            journal.markSubmitting(saved.target,saved.id);journal.observed(saved.target,saved.id,"queued",receipt(intent,"queued"))
            commands.cancelQueue(client(server),intent.threadId,intent.operationId)
            val observed=commands.receipt(client(server),intent.threadId,intent.operationId)
            assertEquals("cancelled",observed.state);journal.observed(saved.target,saved.id,observed.state,receipt(intent,observed.state))
            journal.observed(saved.target,saved.id,"queued",receipt(intent,"queued"));journal.uncertain(saved.target,saved.id,"late transport error")
            val restored=NativeOperationJournal(NativeSessionStorage(context,namespace)).records(saved.target).single()
            assertEquals("cancelled",restored.phase);assertEquals(saved.intentSha256,restored.intentSha256);assertTrue(restored.submissionAttempted)
            expectFailure { journal.markSubmitting(saved.target,saved.id) }
            assertEquals(listOf("/v1/queue/cancel","/v1/receipts"),server.requests.map { it.path })
        }
    }
    @Test fun originalReceiptEvidenceSurvivesLateAcknowledgementAndTimeoutAcrossEncryptedReopen()=runBlocking {
        CommandFixture { error("Journal transition must not dispatch input") }.use { server ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="receipt-progress-"+UUID.randomUUID()
            val store=NativeSessionStorage(context,namespace);val journal=NativeOperationJournal(store)
            for(state in listOf("failed","submitted","queued")) {
                val intent=reply().copy(operationId="original-"+state+"-"+UUID.randomUUID())
                val original=journal.prepare(client(server),intent.threadId,"reply",intent.body());journal.markSubmitting(original.target,original.id)
                journal.observed(original.target,original.id,state,receipt(intent,state))
                val incoming=if(state=="failed")"submitted" else "received"
                val late=journal.observed(original.target,original.id,incoming,receipt(intent,incoming));assertEquals(state,late.phase)
                assertEquals(state,journal.uncertain(original.target,original.id,"late lost response").phase)
                val restored=NativeOperationJournal(NativeSessionStorage(context,namespace)).records(original.target).single { it.id==original.id }
                assertEquals(state,restored.phase);assertEquals(state,JSONObject(restored.result!!).getString("state"));assertEquals(original.intentSha256,restored.intentSha256)
            }
            assertEquals(0,server.requests.size)
        }
    }
    @Test fun changedIntentAndRuntimeCannotReuseOriginalRecord() = runBlocking {
        CommandFixture { WireResponse() }.use { server ->
            val original=client(server); val intent=reply(); val journal=journal()
            val saved=journal.prepare(original,intent.threadId,"reply",intent.body())
            expectFailure { journal.prepare(original,intent.threadId,"reply",intent.copy(text="edited").body()) }
            expectFailure { journal.observed(saved.target,saved.id,"received",receipt(intent.copy(threadId="other-thread"))) }
            expectFailure { journal.observed(saved.target,saved.id,"completed",receipt(intent,"uncertain")) }
            assertEquals(saved.intent,journal.records(saved.target).single().intent)
            val another=NativeOperationJournal.target(client(server,runtime="other-runtime"),intent.threadId)
            assertTrue(journal.records(another).isEmpty()); assertNotEquals(saved.target,another)
            assertEquals(0,server.requests.size)
        }
    }
    @Test fun atomicJournalSurvivesNewStorageAndDetachedWaitKeepsIntent() = runBlocking {
        CommandFixture { WireResponse() }.use { server ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext; val namespace="command-restart-"+UUID.randomUUID()
            val first=NativeOperationJournal(NativeSessionStorage(context,namespace)); val intent=CreationIntent("fixture-host","creation-operation",null,"first draft",emptyList())
            val saved=first.prepare(client(server),null,"creation",intent.body()); first.markPreparing(saved.target,saved.id)
            val prepared=JSONObject().put("hostId",intent.hostId).put("clientOperationId",intent.operationId).put("projectId",JSONObject.NULL)
                .put("phase","prepared").put("threadId",JSONObject.NULL).put("error",JSONObject.NULL).put("uploaded",JSONArray())
            first.observed(saved.target,saved.id,"prepared",prepared); first.markSubmitting(saved.target,saved.id)
            first.uncertain(saved.target,saved.id,"late outcome"); first.detach(saved.target,saved.id)
            val restored=NativeOperationJournal(NativeSessionStorage(context,namespace)).records(saved.target).single()
            assertEquals(saved.intent,restored.intent); assertEquals("uncertain",restored.phase); assertTrue(restored.detached)
            assertTrue(restored.submissionAttempted)
            first.observed(saved.target,saved.id,"prepared",prepared)
            expectFailure { first.markSubmitting(saved.target,saved.id) }
            assertEquals(0,server.requests.size)
        }
    }
    @Test fun forgedReceiptAndMismatchedWriteTargetAreRejected() = runBlocking {
        val intent=reply()
        CommandFixture { WireResponse(body=receipt(intent.copy(threadId="other-thread")).toString()) }.use { server ->
            expectFailure { commands.send(client(server),intent.copy(hostId="other-host")) }; assertEquals(0,server.requests.size)
            expectFailure { commands.send(client(server),intent) }; assertEquals(1,server.requests.size)
        }
    }
    @Test fun redirectsDoNotForwardCredentialsOrReplayWrites() = runBlocking {
        CommandFixture { WireResponse() }.use { target ->
            CommandFixture { WireResponse(status=307,headers=mapOf("Location" to target.endpoint+"/v1/replies")) }.use { source ->
                val error=expectFailure { commands.send(client(source),reply()) }
                assertEquals(307,(error as CodexCommandError).status); assertEquals(1,source.requests.size); assertEquals(0,target.requests.size)
            }
        }
    }
    @Test fun eventScopeAndCursorAreCheckedBeforeApplication() = runBlocking {
        val history=JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("messages",JSONArray())
            .put("readTarget",JSONObject().put("agentId","codex").put("hostId","fixture-host").put("workspaceId","fixture-workspace").put("runtimeId","other-runtime"))
        val batch=JSONObject().put("hostId","fixture-host").put("epoch","new-epoch").put("cursor",1).put("reset",false)
            .put("events",JSONArray().put(JSONObject().put("threadId","fixture-thread").put("snapshot",history)))
        CommandFixture { WireResponse(body=batch.toString()) }.use { server ->
            expectFailure { commands.events(client(server),"fixture-thread","old-epoch",-1) }; assertEquals(0,server.requests.size)
            expectFailure { commands.events(client(server),"fixture-thread","old-epoch",5) }
            val repair=OperationPolicies.event("old-epoch",5,"new-epoch",1,false,true,0)
            assertTrue(repair.apply); assertTrue(repair.reload); assertEquals(1L,repair.cursor)
        }
    }
    @Test fun attachmentHashAndReturnedTargetAreChecked() = runBlocking {
        val bytes="附件字节".toByteArray(); val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file=CreationFile("local-file","fixture.txt","text/plain",bytes.size.toLong(),hash)
        val remote=JSONObject().put("id","remote-file").put("hostId","fixture-host").put("threadId","other-thread")
            .put("name",file.name).put("mediaType",file.mediaType).put("sizeBytes",file.sizeBytes).put("sha256",hash)
        CommandFixture { WireResponse(body=remote.toString()) }.use { server ->
            expectFailure { commands.upload(client(server),"fixture-thread",file,"changed".toByteArray()) }; assertEquals(0,server.requests.size)
            expectFailure { commands.upload(client(server),"fixture-thread",file,bytes) }
            assertArrayEquals(bytes,server.requests.single().body); assertEquals(hash,server.requests.single().headers["x-content-sha256"])
        }
    }
    @Test fun malformedUtf8IsRejectedWithoutSilentReplacement() = runBlocking {
        CommandFixture { WireResponse(raw=byteArrayOf(0xC3.toByte(),0x28)) }.use { server ->
            expectFailure { commands.models(client(server)) }; assertEquals(1,server.requests.size)
        }
    }
    private fun creationWorkspace(allowed: Boolean)=JSONObject().put("hostId","fixture-host").put("name","fixture")
        .put("connectionMode","desktop-runtime").put("readTarget",JSONObject().put("agentId","codex").put("hostId","fixture-host")
            .put("workspaceId","fixture-workspace").put("runtimeId","fixture-runtime"))
        .put("projects",JSONArray()).put("threads",JSONArray()).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
        .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1")+if(allowed) listOf("desktop:creations","desktop:creations:projectless") else emptyList()))
    private suspend fun prepared(flow: NativeCodexFlow,client: MobileClient,intent: CreationIntent): SavedOperation {
        val record=flow.journal.prepare(client,null,"creation",intent.body())
        flow.journal.markPreparing(record.target,record.id)
        return flow.journal.observed(record.target,record.id,"prepared",JSONObject().put("hostId",intent.hostId)
            .put("clientOperationId",intent.operationId).put("projectId",JSONObject.NULL).put("phase","prepared")
            .put("threadId",JSONObject.NULL).put("error",JSONObject.NULL).put("uploaded",JSONArray()))
    }
    private fun creationFlow()=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"creation-authority-"+UUID.randomUUID())
    @Test fun preparedCreationCannotUploadIntoAnotherRuntime() = runBlocking {
        CommandFixture { WireResponse(body=creationWorkspace(true).toString()) }.use { server ->
            val flow=NativeCodexFlow(creationFlow(),NativeHostReads { "" },commands)
            val intent=CreationIntent("fixture-host","prepared-operation",null,"first",emptyList())
            val original=prepared(flow,client(server),intent)
            expectFailure { flow.continuePrepared(client(server,runtime="other-runtime"),original,{true},{}) }
            assertTrue(server.requests.isEmpty());assertFalse(flow.journal.records(original.target).single().submissionAttempted)
        }
    }
    @Test fun revokedCreationCapabilityDoesNotAttemptOriginalInput() = runBlocking {
        CommandFixture { WireResponse(body=creationWorkspace(false).toString()) }.use { server ->
            val flow=NativeCodexFlow(creationFlow(),NativeHostReads { "" },commands);val client=client(server)
            val original=prepared(flow,client,CreationIntent(client.id,"prepared-operation",null,"first",emptyList()))
            val result=flow.continuePrepared(client,original,{true},{})
            assertFalse(result.record.submissionAttempted);assertEquals(original.intent,result.record.intent)
            assertEquals(listOf("/v1/workspace"),server.requests.map { it.path });assertTrue(server.requests.all { it.method=="GET" })
        }
    }
    @Test fun capabilityRevokedAfterUploadPreventsFirstInputAttempt() = runBlocking {
        var workspaceReads=0;var file: CreationFile?=null;val bytes="attachment original bytes".toByteArray()
        CommandFixture { request ->
            when(request.path) {
                "/v1/workspace"->WireResponse(body=creationWorkspace(++workspaceReads==1).toString())
                "/v1/desktop/creation-attachments"->WireResponse(body=file!!.body().put("hostId","fixture-host").put("clientOperationId","prepared-operation").toString())
                else->WireResponse(status=500)
            }
        }.use { server ->
            val flow=NativeCodexFlow(creationFlow(),NativeHostReads { "" },commands);val client=client(server)
            file=flow.files.import("original.txt","text/plain",bytes).manifest
            val original=prepared(flow,client,CreationIntent(client.id,"prepared-operation",null,"first",listOf(file!!)))
            val result=flow.continuePrepared(client,original,{true},{})
            assertEquals(2,workspaceReads);assertFalse(result.record.submissionAttempted);assertEquals(original.intent,result.record.intent)
            val upload=server.requests.single { it.method=="POST" };assertEquals("/v1/desktop/creation-attachments",upload.path);assertArrayEquals(bytes,upload.body)
            assertTrue(server.requests.none { it.path=="/v1/desktop/creations/submit" })
        }
    }
    private suspend fun finalReplyAuthorityRejectsChangedRunCapabilityOrPlan(change: String,confirmed: Boolean=false) {
        var workspaceReads=0;var historyReads=0;var file: CreationFile?=null
        val proof=java.util.concurrent.atomic.AtomicReference<Pair<MobileWorkspace,MobileHistory>?>(null)
        val isPlan=change=="plan";val mode=if(isPlan) "next-turn" else if(change=="capability") "queue" else "steer"
        CommandFixture { request -> when(request.path) {
            "/v1/workspace" -> {
                workspaceReads++;val caps=listOf("reply","commands:target-bound:v1","plan-mode")+if(change!="capability" || workspaceReads==1) listOf("queue","steer") else emptyList()
                WireResponse(body=creationWorkspace(true).put("capabilities",JSONArray(caps)).toString())
            }
            "/v1/history" -> {
                historyReads++;val runtime=JSONObject().put("state",if(isPlan) "idle" else "running").put("turnId",if(isPlan) JSONObject.NULL else if(change=="turn" && historyReads>1) "new-turn" else "original-turn")
                    .put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray()).put("error",JSONObject.NULL)
                if(isPlan) runtime.put("proposedPlan",JSONObject().put("id",if(historyReads==1) "original-plan" else "new-plan").put("turnId","plan-turn").put("text","full plan"))
                WireResponse(body=JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("readTarget",JSONObject(creationWorkspace(true).getJSONObject("readTarget").toString()))
                    .put("messages",JSONArray()).put("runtime",runtime).toString())
            }
            "/v1/attachments" -> {
                proof.get()?.let { (workspace,history) ->
                    proof.set(when(change) {
                        "capability" -> workspace.copy(capabilities=workspace.capabilities.filterNot { it=="queue" }) to history
                        "turn" -> workspace to history.copy(runtime=history.runtime!!.copy(turnId="new-turn"))
                        else -> workspace to history.copy(runtime=history.runtime!!.copy(proposedPlan=history.runtime.proposedPlan!!.copy(id="new-plan")))
                    })
                }
                WireResponse(body=file!!.body().put("id","remote-file").put("hostId","fixture-host").put("threadId","fixture-thread").toString())
            }
            else -> WireResponse(status=500)
        } }.use { server ->
            val reads=NativeHostReads { "" };val flow=NativeCodexFlow(creationFlow(),reads,commands);val client=client(server)
            if(confirmed) { proof.set(reads.workspace(client) to reads.history(client,"fixture-thread"));flow.confirmedReplyReads={_,_->proof.get()} }
            val source=flow.files.import("original.txt","text/plain","immutable original attachment".toByteArray());file=source.manifest
            val result=flow.submit(client,"fixture-thread",null,SubmissionOrigin("source","version"),CodexContent("original intent",listOf(source),GenerationSettings()),mode,
                if(isPlan) null else "original-turn",if(isPlan) "original-plan" else null,{true},operationId="final-authority-operation")
            assertFalse(result.record.submissionAttempted);assertEquals("failed",result.record.phase);assertEquals("original intent",JSONObject(result.record.intent).getString("text"))
            assertEquals(if(confirmed)1 else 2,workspaceReads);assertEquals(if(confirmed)1 else 2,historyReads)
            assertEquals(if(confirmed)listOf("/v1/workspace","/v1/history","/v1/attachments") else listOf("/v1/workspace","/v1/history","/v1/attachments","/v1/workspace","/v1/history"),server.requests.map { it.path })
            assertTrue(server.requests.none { it.path=="/v1/replies" })
        }
    }
    @Test fun changedTurnAfterAttachmentUploadNeverSubmitsStaleSteer()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("turn") }
    @Test fun revokedQueueAfterAttachmentUploadNeverAttemptsReply()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("capability") }
    @Test fun changedPlanAfterAttachmentUploadNeverImplementsOldVersion()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("plan") }
    @Test fun currentOwnerProofRechecksChangedTurnAfterUpload()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("turn",true) }
    @Test fun currentOwnerProofRechecksRevokedQueueAfterUpload()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("capability",true) }
    @Test fun currentOwnerProofRechecksChangedPlanAfterUpload()=runBlocking { finalReplyAuthorityRejectsChangedRunCapabilityOrPlan("plan",true) }
    private suspend fun expectFailure(body: suspend ()->Unit): Exception {
        try { body() } catch(failure: Exception) { return failure }
        throw AssertionError("Expected boundary rejection")
    }
}
internal data class WireRequest(val method: String,val pathWithQuery: String,val headers: Map<String,String>,val body: ByteArray) {
    val path get()=pathWithQuery.substringBefore('?')
}
internal data class WireResponse(val status: Int=200,val body: String="{}",val headers: Map<String,String> = emptyMap(),val disconnect: Boolean=false,val raw: ByteArray?=null)
internal class CommandFixture(private val response: (WireRequest)->WireResponse): java.io.Closeable {
    private val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
    private val workers=Executors.newCachedThreadPool()
    val endpoint="http://127.0.0.1:${socket.localPort}"
    val requests=CopyOnWriteArrayList<WireRequest>()
    init { workers.execute {
        while(!socket.isClosed) {
            val peer=try { socket.accept() } catch(_: Exception) { break }
            try { workers.execute { peer.use {
                try {
                    peer.soTimeout=5000; val input=java.io.DataInputStream(peer.getInputStream())
                    fun line(): String { val out=java.io.ByteArrayOutputStream(); while(true) { val b=input.read(); if(b<0) throw java.io.EOFException(); if(b==10) break; if(b!=13) out.write(b) }; return out.toString("UTF-8") }
                    val start=line().split(' '); val headers=mutableMapOf<String,String>()
                    while(true) { val line=line(); if(line.isEmpty()) break; headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim() }
                    val body=ByteArray(headers["content-length"]?.toInt() ?: 0); input.readFully(body)
                    val request=WireRequest(start[0],start[1],headers,body); requests.add(request); val result=response(request)
                    if(!result.disconnect) {
                        val bytes=result.raw ?: result.body.toByteArray(Charsets.UTF_8)
                        val head="HTTP/1.1 ${result.status} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n"+result.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }+"\r\n"
                        peer.getOutputStream().write(head.toByteArray(Charsets.UTF_8)); peer.getOutputStream().write(bytes); peer.getOutputStream().flush()
                    }
                } catch(_: Exception) { }
            } } } catch(failure: java.util.concurrent.RejectedExecutionException) {
                peer.close()
                // close() may retire the pool after accept() has returned a peer.
                if(!workers.isShutdown)throw failure
            }
        }
    } }
    override fun close() { socket.close(); workers.shutdownNow() }
}
