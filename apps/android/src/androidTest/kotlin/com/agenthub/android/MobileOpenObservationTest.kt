package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HTTP observers exercise the durable original receipt, never a second open POST. */
@RunWith(AndroidJUnit4::class)
class MobileOpenObservationTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(40)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun client(endpoint: String)=MobileClient("fixture-host","fixture",TargetScope("codex",null,null,"fixture-host","fixture-workspace","fixture-runtime"),endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
    private fun flow(storage: NativeSessionStorage)=NativeCodexFlow(storage,NativeHostReads { "synthetic-token" },NativeCodexCommands { "synthetic-token" })
    private fun result(id: String,phase: String="opened")=JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("clientOperationId",id).put("phase",phase).put("error",JSONObject.NULL)
    private fun operation(request: WireRequest): String {
        require(request.method=="GET" && request.path=="/v1/desktop/open-status")
        return java.net.URI(request.pathWithQuery).query.split('&').single { it.startsWith("id=") }.substringAfter('=')
    }
    private suspend fun opening(flow: NativeCodexFlow,client: MobileClient): SavedOperation {
        val id=UUID.randomUUID().toString()
        val record=flow.journal.prepare(client,"fixture-thread","open",result(id).apply { remove("phase");remove("error") })
        flow.journal.markSubmitting(record.target,id)
        return flow.journal.observed(record.target,id,"opening",result(id,"opening"))
    }
    @Test fun concurrentReadersAndRecreatedFlowReuseDurableOpenedReceipt()=runBlocking {
        CommandFixture { request -> Thread.sleep(200);WireResponse(body=result(operation(request)).toString()) }.use { server ->
            val storage=NativeSessionStorage(context,"p1-open-"+UUID.randomUUID());val flow=flow(storage);val client=client(server.endpoint)
            val saved=opening(flow,client);val start=CompletableDeferred<Unit>()
            val readers=(0..7).map { async(Dispatchers.Default) { start.await();flow.query(client,saved) } };start.complete(Unit)
            assertTrue(readers.awaitAll().all { it.record.phase=="opened" && it.record.intent==saved.intent })
            assertEquals(1,server.requests.size)
            assertEquals("opened",flow(storage).query(client,saved).record.phase)
            assertEquals("Recreated observers reuse the durable terminal receipt",1,server.requests.size)
        }
    }
    @Test fun unrelatedOriginalOperationDoesNotWaitForBlockedRead()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var blocked=""
        CommandFixture { request -> val id=operation(request);if(id==blocked) { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) };WireResponse(body=result(id).toString()) }.use { server ->
            try {
                val flow=flow(NativeSessionStorage(context,"p1-open-"+UUID.randomUUID()));val client=client(server.endpoint)
                val first=opening(flow,client);val second=opening(flow,client);blocked=first.id
                val slow=async(Dispatchers.Default) { flow.query(client,first) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                assertEquals("opened",withTimeout(7000) { flow.query(client,second) }.record.phase)
                release.countDown();assertEquals("opened",slow.await().record.phase);assertEquals(2,server.requests.size)
            } finally { release.countDown() }
        }
    }
    @Test fun cancelledWaitingObserverDoesNotBlockRemainingReader()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        CommandFixture { request -> entered.countDown();check(release.await(15,TimeUnit.SECONDS));WireResponse(body=result(operation(request)).toString()) }.use { server ->
            try {
                val flow=flow(NativeSessionStorage(context,"p1-open-"+UUID.randomUUID()));val client=client(server.endpoint);val saved=opening(flow,client)
                val active=async(Dispatchers.Default) { flow.query(client,saved) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                val waiting=async(start=CoroutineStart.UNDISPATCHED) { flow.query(client,saved) }
                waiting.cancelAndJoin();release.countDown();assertEquals("opened",active.await().record.phase)
                assertEquals("opened",flow.query(client,saved).record.phase);assertEquals(1,server.requests.size)
            } finally { release.countDown() }
        }
    }
    @Test fun openingReceiptIsObservedAgainUntilItActuallyOpens()=runBlocking {
        var reads=0
        CommandFixture { request -> WireResponse(body=result(operation(request),if(++reads==1) "opening" else "opened").toString()) }.use { server ->
            val flow=flow(NativeSessionStorage(context,"p1-open-"+UUID.randomUUID()));val client=client(server.endpoint);val saved=opening(flow,client)
            assertEquals("opening",flow.query(client,saved).record.phase)
            assertEquals("opened",flow.query(client,saved).record.phase)
            assertEquals("opened",flow.query(client,saved).record.phase);assertEquals(2,server.requests.size)
        }
    }
    @Test fun wrongReceiptIsRejectedAndNextReadCanRecoverOriginalOperation()=runBlocking {
        var reads=0
        CommandFixture { request -> val id=operation(request);WireResponse(body=result(if(++reads==1) UUID.randomUUID().toString() else id).toString()) }.use { server ->
            val flow=flow(NativeSessionStorage(context,"p1-open-"+UUID.randomUUID()));val client=client(server.endpoint);val saved=opening(flow,client)
            assertTrue(runCatching { flow.query(client,saved) }.isFailure)
            val unchanged=flow.journal.records(saved.target).single();assertEquals("opening",unchanged.phase);assertEquals(saved.intentSha256,unchanged.intentSha256)
            assertEquals("opened",flow.query(client,saved).record.phase);assertEquals(2,server.requests.size)
        }
    }
    @Test fun changedMachineRuntimeCannotReadOriginalReceipt()=runBlocking {
        CommandFixture { request -> WireResponse(body=result(operation(request)).toString()) }.use { server ->
            val flow=flow(NativeSessionStorage(context,"p1-open-"+UUID.randomUUID()));val client=client(server.endpoint);val saved=opening(flow,client)
            assertTrue(runCatching { flow.query(client.copy(scope=TargetScope("codex",null,null,"fixture-host","fixture-workspace","changed-runtime")),saved) }.isFailure)
            assertTrue(server.requests.isEmpty());assertEquals(saved.intent,flow.journal.records(saved.target).single().intent)
        }
    }
}
