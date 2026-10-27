package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Actual native read/codec/deadline, owned loopback server; no model, owner or UI pass. */
class MobileSuggestionSourceReadTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val target=TargetScope("codex","legacy","old","suggestion-fixture-host","workspace","runtime")
    private val scope=SuggestionScope(target,"thread","turn","navigation","connection",false,true,false)
    private var defaultMetadata: String?=null
    private val gets=AtomicInteger();private val posts=AtomicInteger()
    private val encryptedReads=AtomicInteger()
    private val text="Synthetic native suggestion 原文\nnext step"
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun response(): JSONObject=JSONObject().put("schemaVersion",1).put("target",CodexCommandCodec.target(target))
        .put("threadId","thread").put("sourceTurnId","turn").put("readOnly",true).put("source","native-trace")
        .put("suggestion",JSONObject().put("id","version1").put("text",text).put("contentSha256",hash(text.toByteArray()))
            .put("expiresAt","1970-01-01T00:00:02Z").put("validity","native-version"))
    @Before fun before() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        defaultMetadata=NativeSessionStorage(i.targetContext).metadata()?.toString()
    }
    @After fun after() {
        assertEquals("Read adapter must not change default state",defaultMetadata,NativeSessionStorage(i.targetContext).metadata()?.toString())
        assertEquals(0,posts.get())
        val run=InstrumentationRegistry.getArguments().getString("sourceReadsRun") ?: "source-read"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        File(i.targetContext.filesDir,"acceptance/P5-source-reads/$run").apply { mkdirs() }
            .resolve(UUID.randomUUID().toString()+".json").writeText(JSONObject().put("defaultMetadataUnchanged",true)
                .put("defaultMetadataSha256",defaultMetadata?.let { hash(it.toByteArray()) }).put("gets",gets.get())
                .put("posts",posts.get()).put("encryptedReads",encryptedReads.get()).put("newModelInputs",0).put("actualOwnerVerified",false).put("uiVerified",false).toString())
    }
    private inner class Server: Closeable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val executor=Executors.newSingleThreadExecutor()
        @Volatile var body=response().toString()
        @Volatile var requestPath=""
        @Volatile var authorized=false
        @Volatile var open=true
        val endpoint="http://127.0.0.1:${socket.localPort}"
        init { executor.submit {
            while(open)try { socket.accept().use { connection ->
                connection.soTimeout=10_000
                val input=connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request=input.readLine().split(' ');requestPath=request[1]
                if(request[0]=="GET")gets.incrementAndGet()else posts.incrementAndGet()
                var line=input.readLine();var total=0
                while(!line.isNullOrEmpty()) { total+=line.length;require(total<16_384)
                    if(line.substringBefore(':').equals("Authorization",true))authorized=line.substringAfter(':').trim()=="Bearer synthetic-source-token"
                    line=input.readLine()
                }
                val bytes=body.toByteArray(Charsets.UTF_8)
                connection.getOutputStream().use { out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes);out.flush() }
            } }catch(failure: Exception) { if(open)throw failure }
        } }
        override fun close() { open=false;socket.close();executor.shutdownNow() }
        fun client()=MobileClient(target.hostId,"Source fixture",target,endpoint,true,"desktop-runtime",requiresReadTarget=true)
    }
    @Test fun originalSourceReadUsesExactGetAndNeverSendsAccountOrCommand()=runBlocking {
        Server().use { server ->
            val originalExpiry=java.time.Instant.ofEpochMilli(System.currentTimeMillis()+6_000).toString()
            server.body=response().apply {
                getJSONObject("target").getJSONObject("account").put("subject","changed-display-metadata")
                getJSONObject("suggestion").put("expiresAt",originalExpiry)
            }.toString()
            val result=NativeHostReads { "synthetic-source-token" }.nextTurnSuggestion(server.client(),scope)
            assertTrue(result.matches(scope));assertEquals(text,result.suggestion!!.text);assertTrue(result.suggestion!!.isValid());assertTrue(server.authorized)
            assertEquals(originalExpiry,result.suggestion!!.expiresAt)
            val uri=URI(server.requestPath);assertEquals("/v1/threads/next-turn-suggestion",uri.path)
            val fields=uri.rawQuery.split('&').map { it.split('=',limit=2) }.associate { URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it[1],"UTF-8") }
            assertEquals(setOf("hostId","target","threadId","sourceTurnId"),fields.keys)
            assertEquals(target.hostId,fields["hostId"]);assertEquals("thread",fields["threadId"]);assertEquals("turn",fields["sourceTurnId"])
            val sent=JSONObject(fields.getValue("target"));assertFalse(sent.has("account"));assertEquals(target.runtimeId,sent.getString("runtimeId"))
            val before=gets.get();val wrong=SuggestionScope(TargetScope("codex",null,null,"other-host","workspace","runtime"),"thread","turn","navigation","connection",false,true,false)
            assertTrue(runCatching { NativeHostReads { "synthetic-source-token" }.nextTurnSuggestion(server.client(),wrong) }.isFailure)
            assertEquals(before,gets.get());assertEquals(1,gets.get())
        }
    }
    @Test fun changedThreadTurnRuntimeOrSourceCannotBecomeAResponse()=runBlocking {
        Server().use { server ->
            for(field in listOf("threadId","sourceTurnId","runtimeId","source")) {
                val v=response()
                if(field=="runtimeId")v.getJSONObject("target").put(field,"different")else v.put(field,"different")
                server.body=v.toString()
                assertTrue(field,runCatching { NativeHostReads { "synthetic-source-token" }.nextTurnSuggestion(server.client(),scope) }.isFailure)
            }
            assertEquals(4,gets.get())
        }
    }
    @Test fun sharedContractVectorsAndRuntimeSourceDecodeOnNative() {
        val vectors=JSONObject(i.context.assets.open("state-v1.json").bufferedReader().use { it.readText() }).getJSONArray("http").objects()
            .filter { it.getString("name").startsWith("suggestion-wire-") }
        assertEquals(15,vectors.size)
        for(v in vectors)assertEquals(v.getString("name"),v.getBoolean("valid"),CodexHttpContract.validate(v.getString("json"),v.getString("definition"))==null)
        val runtime=JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",org.json.JSONArray())
            .put("error",JSONObject.NULL).put("approvals",org.json.JSONArray()).put("receipts",org.json.JSONArray()).put("canControl",true).put("suggestionSourceTurnId","original-source")
        assertEquals("original-source",MobileRuntime.decode(runtime,target.hostId,"thread").suggestionSourceTurnId)
    }
    @Test fun nativeHashAndClockNeverRenewTheOriginalLease() {
        val value=MobileDesktopSuggestionResponse.decode(response(),scope).suggestion!!
        assertTrue(value.isValid(1000.0));assertFalse(value.isValid(2000.0));assertFalse(value.copy(contentSha256="0".repeat(64)).isValid(1000.0))
        assertFalse(value.copy(expiresAt="invalid").isValid(1000.0))
        val renewed=value.copy(expiresAt="1970-01-01T00:00:04Z")
        assertTrue(value.sameVersion(renewed));assertTrue(renewed.isValid(3000.0));assertFalse(value.isValid(3000.0))
    }
    @Test fun encryptedEasyWorkReadUsesItsOriginalAccountAndReadOnlyRecovery()=runBlocking {
        val vector=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("source-peer","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val requests=java.util.concurrent.CopyOnWriteArrayList<JSONObject>();var wrongSource=false
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_->NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) { request ->
            requests+=JSONObject(request.toString())
            if(request.getString("method")!="thread/suggestion/read") { posts.incrementAndGet();error("A read must never query or replay a command") }
            encryptedReads.incrementAndGet()
            assertEquals("thread",request.getJSONObject("params").getString("threadId"));assertEquals("turn",request.getJSONObject("params").getString("sourceTurnId"))
            if(encryptedReads.get()==1)throw HostReadError("Synthetic first read disconnect",transient=true)
            response().put("sourceTurnId",if(wrongSource) "wrong" else "turn").put("source","runtime").apply {
                getJSONObject("suggestion").put("validity","runtime-version").put("expiresAt",java.time.Instant.ofEpochMilli(System.currentTimeMillis()+6_000).toString())
            }
        } } }
        val remote=EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
        val client=MobileClient(connection.target.hostId,"Encrypted source fixture",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        val source=SuggestionScope(connection.target,"thread","turn","navigation","connection",false,false,true)
        val adapter=EasyWorkAgentClient(EasyWorkChannels { remote })
        val registry=AgentRegistry(NativeHostReads { "" },NativeCodexCommands { "" }).apply { register("easywork",adapter,adapter) }
        try {
            val result=registry.nextTurnSuggestion(client,source)
            assertTrue(result.matches(source));assertTrue(result.suggestion!!.isValid());assertEquals("runtime",result.source)
            assertEquals(2,requests.size);assertEquals(requests[0].getString("requestId"),requests[1].getString("requestId"))
            val wrong=SuggestionScope(TargetScope("easywork",connection.target.accountAuthority,"different-account",client.id,connection.target.workspaceId,connection.target.runtimeId),"thread","turn","navigation","connection",false,false,true)
            assertTrue(runCatching { registry.nextTurnSuggestion(client,wrong) }.isFailure);assertEquals(2,requests.size)
            wrongSource=true;assertTrue(runCatching { registry.nextTurnSuggestion(client,source) }.isFailure)
            assertEquals(3,requests.size);assertEquals(3,encryptedReads.get());assertTrue(requests.all { it.getString("method")=="thread/suggestion/read" })
        } finally { remote.close() }
    }
    @Test fun childReadTimeoutIsRecoverableAndParentCancellationStaysCancellation()=runBlocking {
        val start=android.os.SystemClock.elapsedRealtime()
        val timeout=runCatching { boundedDesktopSuggestionRead { delay(8_200);"late" } }.exceptionOrNull()
        assertTrue(timeout is HostReadError&&timeout.transient);assertTrue(currentCoroutineContext().isActive)
        assertTrue(android.os.SystemClock.elapsedRealtime()-start in 7_500..11_000)
        var reached=false
        val pending=async { boundedDesktopSuggestionRead { reached=true;delay(60_000);"never" } }
        yield();assertTrue(reached);pending.cancel()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
    }
}
