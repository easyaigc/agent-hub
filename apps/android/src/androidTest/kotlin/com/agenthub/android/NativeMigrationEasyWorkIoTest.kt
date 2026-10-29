package com.agenthub.android

import kotlinx.coroutines.*
import okhttp3.*
import okio.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual OkHttp callback/body and production transport registry. Interceptors never open a socket.
 * These scenarios are synthetic auxiliary boundaries, not Host/model, TLS or device acceptance.
 */
class NativeMigrationEasyWorkIoTest {
    private suspend fun CountDownLatch.entered(){withContext(Dispatchers.IO){check(await(5,TimeUnit.SECONDS))}}
    private suspend fun unfinished(value: Deferred<*>) {assertNull(withTimeoutOrNull(100){value.await();true})}
    @Test(timeout=15_000) fun cancelledCoroutineStillWaitsForActualResponseReadAndClose()=runBlocking {
        val io=NativeMigrationIoWork();val body=NativeMigrationHeldResponse();val intercepted=AtomicInteger()
        val client=EasyWorkHttp.isolatedClient(OkHttpClient.Builder().addInterceptor {chain->
            intercepted.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body).build()
        })
        val request=launch(Dispatchers.IO+io){EasyWorkHttp.request(client,"https://synthetic.invalid/read")}
        try {
            body.reading.entered();request.cancelAndJoin()
            val disposed=EasyWorkHttp.dispose(client)
            val idle=async(Dispatchers.Default){io.awaitIdle()}
            unfinished(idle);unfinished(disposed)
            body.releaseRead.countDown();body.closing.entered()
            unfinished(idle);unfinished(disposed)
            body.releaseClose.countDown()
            withTimeout(5_000){idle.await();disposed.await()}
            assertEquals(1,intercepted.get());assertEquals(1,body.closeCount.get())
            assertEquals(0,client.dispatcher.runningCallsCount());assertTrue(client.dispatcher.executorService.isTerminated)
        }finally {body.releaseRead.countDown();body.releaseClose.countDown();request.cancel();EasyWorkHttp.dispose(client)}
    }
    @Test(timeout=15_000) fun actualRelayAcknowledgementScopeRemainsOwnedUntilAuthorizationReturns()=runBlocking {
        val io=NativeMigrationIoWork();val owners=NativeEasyWorkMigrationOwners();val context=io+owners
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val authorizations=AtomicInteger()
        val binding=EasyWorkBinding(JSONObject().put("bindingId","migration-ack").toString())
        val candidate=AgentCandidate("synthetic-relay","relay-http","https://relay.invalid")
        val transport=withContext(context){AgentTransportRegistry().make(candidate,binding,{
            authorizations.incrementAndGet();entered.complete(Unit)
            withContext(NonCancellable){release.await()};"synthetic-unused-token"
        })}
        try {
            transport.acknowledge(JSONObject().put("bindingId",binding.id).put("senderDeviceId","synthetic").put("senderSessionId","synthetic").put("sequence",1).toString().toByteArray())
            withTimeout(5_000){entered.await()}
            val closing=async(Dispatchers.Default){owners.stopAndJoin();io.awaitIdle()}
            unfinished(closing);assertTrue(owners.stopping)
            assertTrue(runCatching {owners.register({},{})}.isFailure)
            release.complete(Unit);withTimeout(5_000){closing.await()}
            assertEquals(1,authorizations.get());owners.reopen();assertFalse(owners.stopping)
            // A cancelled ACK cannot reach EasyWorkHttp after the suspended token returns.
            assertTrue(runCatching {transport.send("{}".toByteArray())}.isFailure)
        }finally {release.complete(Unit);transport.close()}
    }
    @Test(timeout=15_000) fun cancelledJoinKeepsTheSameIndependentTransportUntilItsProofDrains()=runBlocking {
        val owners=NativeEasyWorkMigrationOwners();val io=NativeMigrationIoWork();val stopped=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val closes=AtomicInteger()
        val registry=AgentTransportRegistry().apply {register("held-independent") {_,_,_,_->object: AgentFrameTransport {
            override suspend fun send(bytes: ByteArray){}
            override suspend fun receive(): ByteArray=error("No source input")
            override fun close(){closes.incrementAndGet();stopped.complete(Unit)}
            override suspend fun awaitClosedForMigration(){release.await()}
        }}}
        val transport=withContext(io+owners){registry.make(AgentCandidate("held","held-independent","https://synthetic.invalid"),EasyWorkBinding("{}"),{"unused"})}
        try {
            val first=async(Dispatchers.Default){owners.stopAndJoin()};stopped.await();unfinished(first)
            first.cancelAndJoin();assertTrue(owners.stopping)
            val second=async(Dispatchers.Default){owners.stopAndJoin();io.awaitIdle()};unfinished(second)
            assertEquals(1,closes.get());release.complete(Unit);second.await();owners.reopen();assertFalse(owners.stopping)
            transport.close();assertEquals(1,closes.get())
        }finally {release.complete(Unit);transport.close()}
    }
    @Test(timeout=15_000) fun actualRemotePrivateScopeDoesNotBorrowParentJobAndKeepsItsCancelledChildUntilExit()=runBlocking {
        val io=NativeMigrationIoWork();val owners=NativeEasyWorkMigrationOwners();val parent=SupervisorJob()
        val target=EasyWorkTarget("https://synthetic.invalid","synthetic-subject","synthetic-host","synthetic-workspace","synthetic-runtime")
        val binding=EasyWorkBinding(JSONObject().put("target",target.json()).put("bindingId","synthetic-binding").toString())
        val remote=EasyWorkRemoteSession(AgentConnection(target.scope(),emptyList(),"unused"),EasyWorkCredential(binding,"unused"),{"unused"},ownerContext=io+owners+parent)
        val field=EasyWorkRemoteSession::class.java.getDeclaredField("tasks").apply {isAccessible=true}
        val actual=field.get(remote) as CoroutineScope;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        assertNotSame(parent,actual.coroutineContext[Job]);assertSame(io,actual.coroutineContext[NativeMigrationIoWork])
        actual.launch {started.complete(Unit);try {awaitCancellation()}finally {withContext(NonCancellable){entered.complete(Unit);release.await()}}}
        try {
            started.await();parent.cancel();assertTrue(actual.coroutineContext[Job]!!.isActive)
            val closing=async(Dispatchers.Default){owners.stopAndJoin();io.awaitIdle()};entered.await();unfinished(closing)
            assertTrue(actual.coroutineContext[Job]!!.isCancelled);assertFalse(actual.coroutineContext[Job]!!.isCompleted)
            release.complete(Unit);closing.await();assertTrue(actual.coroutineContext[Job]!!.isCompleted)
            owners.reopen();assertFalse(owners.stopping)
        }finally {release.complete(Unit);remote.close();parent.cancel()}
    }
    @Test(timeout=15_000) fun missingCustomTransportCloseProofFailsClosedAndCannotReopen()=runBlocking {
        val owners=NativeEasyWorkMigrationOwners();val io=NativeMigrationIoWork()
        val registry=AgentTransportRegistry().apply {register("unproved") {_,_,_,_->object: AgentFrameTransport {
            override suspend fun send(bytes: ByteArray){}
            override suspend fun receive(): ByteArray=error("No source input")
            override fun close(){}
        }}}
        withContext(io+owners){registry.make(AgentCandidate("unproved","unproved","https://synthetic.invalid"),EasyWorkBinding("{}"),{"unused"})}
        assertTrue(runCatching {owners.stopAndJoin()}.isFailure);assertTrue(owners.stopping)
        assertTrue(runCatching {owners.reopen()}.isFailure)
    }
}

internal class NativeMigrationHeldResponse: ResponseBody() {
    val reading=CountDownLatch(1);val releaseRead=CountDownLatch(1)
    val closing=CountDownLatch(1);val releaseClose=CountDownLatch(1)
    val closeCount=AtomicInteger()
    private val input=object: Source {
        private var sent=false
        override fun timeout()=Timeout.NONE
        override fun read(sink: Buffer,byteCount: Long): Long {
            reading.countDown();wait(releaseRead)
            if(sent)return -1
            sent=true;sink.writeUtf8("{}");return 2
        }
        override fun close(){closing.countDown();wait(releaseClose);closeCount.incrementAndGet()}
    }.buffer()
    override fun contentType(): MediaType?=null
    override fun contentLength()=-1L
    override fun source()=input
    companion object {private fun wait(latch: CountDownLatch){while(true)try {latch.await();return}catch(_: InterruptedException){}}}
}
