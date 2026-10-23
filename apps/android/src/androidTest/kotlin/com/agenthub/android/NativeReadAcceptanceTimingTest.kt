package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Opt-in/privacy/cap/IO-failure boundaries using the actual debug recorder. */
@RunWith(AndroidJUnit4::class)
class NativeReadAcceptanceTimingTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val host=UUID.randomUUID().toString();private val run=UUID.randomUUID().toString()
    private val folder by lazy { File(context.cacheDir,"read-timing-test-$run").apply { mkdirs() } }
    private fun wrapped(cache: File=folder,reportedPackage: String=context.packageName)=object: ContextWrapper(context) {
        override fun getCacheDir()=cache
        override fun getPackageName()=reportedPackage
    }
    private fun intent(run: String=this.run)=Intent().putExtra(NativeReadAcceptanceTiming.ENABLED,true)
        .putExtra(NativeReadAcceptanceTiming.HOST,host).putExtra(NativeReadAcceptanceTiming.RUN,run)
    private fun file(run: String=this.run)=File(folder,"native-read-$run.jsonl")
    private fun rows(file: File,count: Int): List<JSONObject> {
        val until=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<until) {
            val rows=runCatching { file.readLines().filter(String::isNotEmpty).map(::JSONObject) }.getOrDefault(emptyList())
            if(rows.size==count)return rows
            Thread.sleep(20)
        }
        throw AssertionError("Bounded native recorder did not persist expected$count rows")
    }
    @After fun disable() { NativeReadAcceptanceTiming.configure(context,Intent()) }
    @Test fun absentOptInAndMalformedRunNeverCreateEvidence() {
        NativeReadAcceptanceTiming.configure(wrapped(),Intent());assertNull(NativeReadAcceptanceTiming.capture())
        NativeReadAcceptanceTiming.configure(wrapped(),intent("../not-a-run"));assertNull(NativeReadAcceptanceTiming.capture())
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
    @Test fun devOrFormalIdentityCannotEnableRecorder() {
        for(name in listOf("com.easyai.agenthub.android.debug","com.easyai.agenthub.android","foreign.acceptance")) {
            NativeReadAcceptanceTiming.configure(wrapped(reportedPackage=name),intent());assertNull(NativeReadAcceptanceTiming.capture())
        }
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
    @Test fun exactHostCapAndClosedMetadataExcludeContent() {
        NativeReadAcceptanceTiming.configure(wrapped(),intent());val recorder=checkNotNull(NativeReadAcceptanceTiming.capture())
        val before=SystemClock.elapsedRealtimeNanos()
        repeat(8) { recorder.record(NativeReadAcceptanceTiming.Point.HISTORY_RESPONSE,UUID.randomUUID().toString()) }
        repeat(300) { recorder.record(NativeReadAcceptanceTiming.Point.HISTORY_RESPONSE,host,"https://synthetic.invalid/?token=not-a-real-key",before) }
        val values=rows(file(),256);assertEquals("session-start",values.first().getString("event"))
        val allowed=setOf("event","uptime","utc","startedAt","elapsedMS","threadId")
        for(value in values) {
            assertTrue(value.keys().asSequence().all { it in allowed });assertFalse(value.has("threadId"))
            if(value.has("elapsedMS"))assertTrue(value.getDouble("elapsedMS")>=0)
        }
        assertFalse(file().readText().contains("synthetic.invalid"));assertFalse(file().readText().contains("not-a-real-key"))
        Thread.sleep(100);assertEquals(256,file().readLines().size)
    }
    @Test fun existingEvidenceIsNeverReplacedOrAppended() {
        file().writeText("original immutable proof\n")
        NativeReadAcceptanceTiming.configure(wrapped(),intent());assertNull(NativeReadAcceptanceTiming.capture())
        assertEquals("original immutable proof\n",file().readText())
    }
    @Test fun capturedRunCannotWriteIntoNewRun() {
        val second=UUID.randomUUID().toString();val thread=UUID.randomUUID().toString()
        NativeReadAcceptanceTiming.configure(wrapped(),intent());val firstRecorder=checkNotNull(NativeReadAcceptanceTiming.capture())
        NativeReadAcceptanceTiming.configure(wrapped(),intent(second));val secondRecorder=checkNotNull(NativeReadAcceptanceTiming.capture())
        firstRecorder.record(NativeReadAcceptanceTiming.Point.HISTORY_RESPONSE,host,thread)
        secondRecorder.record(NativeReadAcceptanceTiming.Point.CACHE_APPLYING,host,thread)
        assertEquals(listOf("session-start","history-response"),rows(file(),2).map { it.getString("event") })
        assertEquals(listOf("session-start","cache-applying"),rows(file(second),2).map { it.getString("event") })
    }
    @Test fun evidenceIoFailureCannotChangeNativeReadResultOrDraft() {
        val bad=File(folder,"not-a-directory").apply { writeText("keep original") }
        NativeReadAcceptanceTiming.configure(wrapped(cache=bad),intent());assertNotNull(NativeReadAcceptanceTiming.capture())
        val thread=UUID.randomUUID().toString()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",host).put("name","synthetic timing").put("connectionMode","history-only").put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id",thread).put("hostId",host).put("title","synthetic timing").put("state","idle"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",host).put("threadId",thread)
                .put("messages",JSONArray().put(JSONObject().put("id","timing-row").put("role","assistant").put("text","original synthetic result"))).toString())
        }
        runBlocking { withContext(Dispatchers.Main) {
            val session=MobileSession(reader=reader)
            try {
                session.connect("synthetic timing","https://fixture.invalid","");session.selectThread(session.threads.single());session.text="preserved timing draft"
                withTimeout(5_000) { while(session.messages.isEmpty())delay(20) }
                assertNull(session.error);assertEquals("original synthetic result",session.messages.single().text);assertEquals("preserved timing draft",session.text);assertTrue(session.operationRecords.isEmpty())
            } finally { session.leaveForeground() }
        } }
        assertEquals("keep original",bad.readText())
    }
    @Test fun actualNativeHttpResponseRecordsTimingWithoutPayload() {
        NativeReadAcceptanceTiming.configure(wrapped(),intent())
        val thread=UUID.randomUUID().toString();val marker="synthetic body is never timing metadata"
        val body=JSONObject().put("hostId",host).put("threadId",thread).put("messages",JSONArray().put(JSONObject().put("id","timed-http-row").put("role","assistant").put("text",marker))).toString().toByteArray(Charsets.UTF_8)
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));val executor=Executors.newSingleThreadExecutor()
        val served=executor.submit {
            server.accept().use { socket ->
                socket.soTimeout=5_000;val reader=socket.getInputStream().bufferedReader(Charsets.US_ASCII);var bytes=0
                while(true) { val line=reader.readLine() ?: error("Incomplete synthetic HTTP request");bytes+=line.length;check(bytes<16_384);if(line.isEmpty())break }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));write(body);flush() }
            }
        }
        try {
            val client=MobileClient(host,"synthetic timing HTTP",endpoint="http://127.0.0.1:${server.localPort}",isDemo=false)
            val result=runBlocking { NativeHostReads { "" }.history(client,thread) };served.get(5,TimeUnit.SECONDS)
            assertEquals(marker,result.messages.single().text)
            val response=rows(file(),2).last();assertEquals("history-response",response.getString("event"));assertEquals(thread,response.getString("threadId"));assertTrue(response.getDouble("elapsedMS")>=0)
            assertFalse(file().readText().contains(marker));assertFalse(file().readText().contains("127.0.0.1"))
        } finally { server.close();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }

}
