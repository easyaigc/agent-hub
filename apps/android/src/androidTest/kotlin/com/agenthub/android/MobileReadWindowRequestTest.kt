package com.agenthub.android

import android.net.Uri
import com.agenthub.core.TargetScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real native HTTP request negotiation, including cursor continuation; no user/model data. */
@RunWith(AndroidJUnit4::class)
class MobileReadWindowRequestTest {
    private fun <T> request(body: JSONObject,call: suspend (MobileClient)->T): Pair<T,Uri> {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));val executor=Executors.newSingleThreadExecutor()
        val host="window-host";val thread="window-thread";val reader="a".repeat(64)
        val bytesToSend=body.toString().toByteArray(Charsets.UTF_8)
        val request=executor.submit<String> {
            server.accept().use { socket ->
                socket.soTimeout=5_000
                val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII);val line=input.readLine();var bytes=line.length
                while(true) { val header=input.readLine() ?: error("Incomplete synthetic request");bytes+=header.length;check(bytes<16_384);if(header.isEmpty())break }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytesToSend.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));write(bytesToSend);flush() }
                line
            }
        }
        try {
            val client=MobileClient(host,"synthetic window computer",endpoint="http://127.0.0.1:${server.localPort}",isDemo=false,readerId=reader)
            val result=runBlocking { call(client) }
            val line=request.get(5,TimeUnit.SECONDS);assertTrue(line.startsWith("GET "));assertTrue(line.endsWith(" HTTP/1.1"))
            val uri=Uri.parse(line.removePrefix("GET ").removeSuffix(" HTTP/1.1"))
            assertEquals(host,uri.getQueryParameter("hostId"));assertEquals(thread,uri.getQueryParameter("threadId"));assertEquals(reader,uri.getQueryParameter("readerId"))
            return result to uri
        } finally { server.close();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }
    private fun history(cursor: String?)=JSONObject().put("hostId","window-host").put("threadId","window-thread")
        .put("messages",JSONArray().put(JSONObject().put("id","original-message").put("role","assistant").put("text","original synthetic source")))
        .put("historyWindow",JSONObject().put("version",1).put("kind",if(cursor==null)"latest" else "earlier"))
        .put("historyRevision",7).put("nextCursor",if(cursor==null)"synthetic-older" else JSONObject.NULL)
    @Test fun latestUsesBoundedWindowWithoutInventingCursor() {
        val (result,request)=request(history(null)) { NativeHostReads { "" }.history(it,"window-thread") }
        assertEquals("original synthetic source",result.messages.single().text);assertTrue(result.windowed);assertEquals("latest",result.historyWindowKind);assertEquals(7L,result.historyRevision)
        assertEquals("synthetic-older",result.nextCursor);assertEquals("/v1/history",request.path)
        assertEquals("latest-v1",request.getQueryParameter("window"));assertNull(request.getQueryParameter("cursor"))
    }
    @Test fun earlierContinuationKeepsWindowAndExactCursor() {
        val cursor="synthetic older /+?=🙂"
        val (result,request)=request(history(cursor)) { NativeHostReads { "" }.history(it,"window-thread",cursor) }
        assertTrue(result.windowed);assertEquals("earlier",result.historyWindowKind);assertNull(result.nextCursor);assertEquals("/v1/history",request.path)
        assertEquals("latest-v1",request.getQueryParameter("window"));assertEquals(cursor,request.getQueryParameter("cursor"))
    }
    @Test fun eventsKeepBoundedWindowWithOriginalEpochAndSequence() {
        val body=JSONObject().put("hostId","window-host").put("epoch","window-epoch").put("cursor",7).put("reset",false).put("ready",true).put("events",JSONArray())
        val (result,request)=request(body) { NativeCodexCommands { "" }.events(it.copy(scope=TargetScope("codex",null,null,it.id,it.id,"desktop-runtime"),mode="desktop-runtime"),"window-thread","window-epoch",7) }
        assertEquals("window-epoch",result.epoch);assertEquals(7L,result.cursor);assertTrue(result.snapshots.isEmpty());assertEquals("/v1/events",request.path)
        assertEquals("window-epoch",request.getQueryParameter("epoch"));assertEquals("7",request.getQueryParameter("after"));assertEquals("latest-v1",request.getQueryParameter("window"))
    }
}
