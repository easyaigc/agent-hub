package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Native wire/crypto/HTTP checks. All data and peers are synthetic, with no model execution. */
@RunWith(AndroidJUnit4::class)
class MobileReceiveTest {
    private fun asset(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun message(text: String)=JSONObject().put("id","message").put("role","assistant").put("status","complete")
        .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text)))
    private fun frame(thread: String,text: String,version: String="opaque-version"): JSONObject {
        val body=message(text)
        return JSONObject().put("snapshot",JSONObject().put("thread",JSONObject().put("id",thread)))
            .put("messages",JSONArray().put(body)).put("head",JSONArray().put(JSONObject().put("id","message").put("hash",version).put("textLength",text.length).put("textHash",hash(text))))
            .put("patches",JSONArray()).put("reset",false).put("branchRevision",1)
    }
    private fun text(value: JSONObject)=value.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
    private fun acknowledgement(sequence: Int=1,binding: String="receive-binding")=JSONObject().put("bindingId",binding)
        .put("senderDeviceId","desktop").put("senderSessionId","session").put("sequence",sequence).toString().toByteArray()
    private fun relay(endpoint: String)=RelayFrameTransport(AgentCandidate("relay","relay-http",endpoint),"receive-binding",{ "synthetic-receive-token" })

    @Test fun sameFrozenDesktopProducerVectorsPassOnAndroidAndPreserveOldState() {
        val vectors=asset("easywork-thread-sync-v1.json");assertEquals(1,vectors.getInt("version"))
        assertEquals("16e74edfdac3845b9b48988f7d6488c44177d480bb47117dff0ee08e4703bcb8",vectors.getString("producerSha256"))
        val caches=mutableMapOf<String,EasyWorkThreadSync>();val cases=vectors.getJSONArray("cases");assertEquals(15,cases.length())
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val scope=case.optString("scope","primary");val previous=caches[scope]?:EasyWorkThreadSync()
            val before=easyCanonical(previous.parameters("thread"));val result=runCatching { previous.merging(case.getJSONObject("result"),"thread") }
            if(case.optBoolean("shouldFail"))assertTrue(case.getString("name"),result.isFailure)
            else { val next=result.getOrThrow();assertEquals(case.getString("name"),easyCanonical(case.getJSONArray("expectedMessages")),easyCanonical(next.second.getJSONArray("messages")));caches[scope]=next.first }
            assertEquals("No partial update on either success or rejection",before,easyCanonical(previous.parameters("thread")))
        }
    }
    @Test fun badTextDigestCannotPublishACandidateOrPoisonThePreviousCache() {
        val original=EasyWorkThreadSync().merging(frame("thread","old"),"thread").first
        val before=easyCanonical(original.parameters("thread"));val corrupted=frame("thread","changed","changed-version")
        corrupted.getJSONArray("head").getJSONObject(0).put("textHash","0".repeat(64))
        assertTrue(runCatching { original.merging(corrupted,"thread") }.exceptionOrNull() is InvalidThreadDelta)
        assertEquals(before,easyCanonical(original.parameters("thread")))
        assertEquals("changed",text(original.merging(frame("thread","changed","changed-version"),"thread").second))
    }

    private fun session(reply: suspend (JSONObject)->JSONObject): EasyWorkRemoteSession {
        val vector=asset("easywork-crypto-vector.json");val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("fixture-relay","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_->NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation"),reply) } }
        return EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
    }
    @Test fun sessionSerializesReadsAndRepairsBadDeltaWithoutReplayingAnyInput()=runBlocking {
        val reads=java.util.concurrent.CopyOnWriteArrayList<JSONObject>();val count=AtomicInteger()
        val session=session { request ->
            reads.add(JSONObject(request.toString()));require(request.getString("method")=="thread/sync")
            when(count.incrementAndGet()) {
                1->frame("thread","first","one")
                2->frame("thread","second","two").put("messages",JSONArray()).put("patches",JSONArray().put(JSONObject().put("id","message").put("baseHash","wrong").put("append","second")))
                else->frame("thread","second","two").apply { if(request.getJSONObject("params").getJSONArray("known").length()>0)put("messages",JSONArray()) }
            }
        }
        try {
            val first=async { session.synchronizedThread("thread") };val second=async { session.synchronizedThread("thread") }
            assertEquals("first",text(first.await()));assertEquals("second",text(second.await()))
            assertEquals("second",text(session.synchronizedThread("thread")))
            assertEquals(listOf(0,1,0,1),reads.map { it.getJSONObject("params").getJSONArray("known").length() })
            assertEquals(4,reads.map { it.getString("requestId") }.toSet().size)
            assertTrue(reads.all { it.getString("method")=="thread/sync" })
        } finally { session.close() }
    }
    @Test fun sessionCacheIsBoundedToFourThreadsAndCloseRejectsLateReads()=runBlocking {
        val known=java.util.concurrent.CopyOnWriteArrayList<Int>();val waiting=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val session=session { request ->
            val params=request.getJSONObject("params");val thread=params.getString("threadId");known.add(params.getJSONArray("known").length())
            if(thread=="waiting") { waiting.complete(Unit);release.await() }
            frame(thread,if(thread=="large")"L".repeat(1_100_000) else "text-$thread")
        }
        try {
            for(i in 1..5)session.synchronizedThread("thread-$i")
            session.synchronizedThread("thread-1");assertEquals(listOf(0,0,0,0,0,0),known.toList())
            repeat(2) { assertEquals(1_100_000,text(session.synchronizedThread("large")).length) }
            assertEquals(listOf(0,0),known.takeLast(2))
            val late=async(Dispatchers.IO) { runCatching { session.synchronizedThread("waiting") } }
            withTimeout(5000) { waiting.await() };session.close();release.complete(Unit)
            assertTrue(withTimeout(5000) { late.await() }.isFailure)
            assertTrue(runCatching { session.synchronizedThread("thread-1") }.isFailure)
        } finally { release.complete(Unit);session.close() }
    }

    @Test fun relayBatchesValidatedAcknowledgementsAndDeduplicatesOneFrame()=runBlocking {
        CommandFixture { WireResponse() }.use { server ->
            val transport=relay(server.endpoint)
            try {
                for(i in 1..12)transport.acknowledge(acknowledgement(i));transport.acknowledge(acknowledgement(12))
                withTimeout(5000) { while(server.requests.sumOf { JSONObject(String(it.body)).getJSONArray("envelopes").length() }<12)delay(10) }
                assertEquals(1,server.requests.size);val frames=JSONObject(String(server.requests.single().body)).getJSONArray("envelopes")
                assertEquals(12,frames.length());assertEquals((1..12).toSet(),(0 until frames.length()).map { frames.getJSONObject(it).getInt("sequence") }.toSet())
                assertEquals("/v1/remote-access/relay/acknowledgements",server.requests.single().path)
                assertEquals("Bearer synthetic-receive-token",server.requests.single().headers["authorization"])
            } finally { transport.close() }
        }
    }
    @Test fun relayRejectsWrongBindingAndBoundsUnconfirmedQueue()=runBlocking {
        val release=CountDownLatch(1)
        CommandFixture { release.await(10,TimeUnit.SECONDS);WireResponse() }.use { server ->
            val transport=relay(server.endpoint)
            try {
                assertTrue(runCatching { transport.acknowledge(acknowledgement(binding="other-binding")) }.isFailure)
                for(i in 1..1000)transport.acknowledge(acknowledgement(i))
                assertTrue(runCatching { transport.acknowledge(acknowledgement(1001)) }.isFailure)
                assertTrue(runCatching { transport.acknowledge(acknowledgement(1002)) }.isFailure)
            } finally { transport.close();release.countDown() }
        }
    }
    @Test fun relayCloseCancelsPendingAcknowledgements()=runBlocking {
        CommandFixture { WireResponse() }.use { server ->
            val transport=relay(server.endpoint);transport.acknowledge(acknowledgement());transport.close()
            delay(150);assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun relayAcknowledgementFailureIsVisibleAndCannotStartMoreRequests()=runBlocking {
        CommandFixture { WireResponse(status=503) }.use { server ->
            val transport=relay(server.endpoint)
            try {
                transport.acknowledge(acknowledgement())
                withTimeout(5000) { while(runCatching { transport.acknowledge(acknowledgement()) }.isSuccess)delay(10) }
                val count=server.requests.size;assertEquals(1,count)
                assertTrue(runCatching { transport.receive() }.isFailure);assertEquals(count,server.requests.size)
            } finally { transport.close() }
        }
    }
    @Test fun closingDuringLegacyOrDeviceCredentialRefreshNeverStartsANewHTTPRequest()=runBlocking {
        for(device in listOf(false,true))CommandFixture { WireResponse() }.use { server ->
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val authorize: EasyWorkRequestHeaders?=if(device) { _,_,_->started.complete(Unit);release.await();mapOf("Authorization" to "synthetic-device-test") } else null
            val transport=RelayFrameTransport(AgentCandidate("relay","relay-http",server.endpoint),"receive-binding",{ started.complete(Unit);release.await();"synthetic" },authorize)
            try {
                val sender=async(Dispatchers.IO) { runCatching { transport.send(acknowledgement()) } }
                withTimeout(5000) { started.await() };transport.close();release.complete(Unit)
                assertTrue(withTimeout(5000) { sender.await() }.isFailure);assertTrue(server.requests.isEmpty())
            } finally { release.complete(Unit);transport.close() }
        }
    }
    @Test fun nativeCodexReaderTransparentlyDecompressesGzipAndStillRejectsWrongTargets()=runBlocking {
        val body="中文 😀 gzip 历史\n".repeat(4096);var wrong=false
        CommandFixture { request ->
            assertEquals("GET",request.method);assertTrue(request.headers["accept-encoding"].orEmpty().contains("gzip"))
            val json=JSONObject().put("hostId",if(wrong)"other-host" else "gzip-host").put("threadId","thread")
                .put("messages",JSONArray().put(JSONObject().put("id","message").put("role","assistant").put("text",body)))
            val compressed=ByteArrayOutputStream();GZIPOutputStream(compressed).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            WireResponse(headers=mapOf("Content-Encoding" to "gzip","Vary" to "Accept-Encoding"),raw=compressed.toByteArray())
        }.use { server ->
            val client=MobileClient("gzip-host","Gzip",endpoint=server.endpoint,isDemo=false,mode="history")
            val reader=NativeHostReads { "" };assertEquals(body,reader.history(client,"thread").messages.single().text)
            wrong=true;assertTrue(runCatching { reader.history(client,"thread") }.isFailure);assertEquals(2,server.requests.size)
        }
    }
}
