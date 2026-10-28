package com.agenthub.android

import com.agenthub.core.TargetScope
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeThreadReadingWireTest {
    private val target=TargetScope("codex",null,null,"read-host","workspace","runtime")
    private val position=ThreadReadPosition(2,"message",ThreadReadPosition.contentVersion("message","body 中文 😀","completed"))
    private val request=NativePendingRead(target,"thread","a".repeat(64),position)
    private fun response()=JSONObject().put("hostId",target.hostId).put("threadId","thread").put("target",CodexCommandCodec.target(target)).put("readerId",request.readerId).put("read",position.json())
    @Test fun exactActualProtocolRequestAndAcknowledgementRejectMismatchedOrExtraFields() {
        NativeThreadReadingWire.validateRequest(NativeThreadReadingWire.request(request))
        NativeThreadReadingWire.acknowledgement(request,response())
        for(value in listOf(response().put("threadId","other"),response().put("readerId","b".repeat(64)),
            response().put("extra",true),response().put("read",position.copy(revision=1).json()))) {
            assertThrows(IllegalArgumentException::class.java) { NativeThreadReadingWire.acknowledgement(request,value) }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeThreadReadingWire.validateRequest(NativeThreadReadingWire.request(request).put("arbitraryMethod","send")) }
    }
    @Test fun readStateSurvivesWorkspaceEncodingAndStreamingHistoryMetadata() {
        val state=ThreadReadState(position,request.readerId,position)
        val thread=JSONObject().put("id","thread").put("hostId",target.hostId).put("title","reading").put("state","idle").put("readState",state.json())
        val wire=JSONObject().put("hostId",target.hostId).put("name","reading").put("connectionMode","history-only").put("readTarget",CodexCommandCodec.target(target))
            .put("threads",JSONArray().put(thread)).put("projects",JSONArray())
        val workspace=HostReadCodec.workspace(wire.toString())
        assertEquals(state,HostReadCodec.workspace(encodeWorkspace(workspace).toString()).threads.single().readState)
        val history=JSONObject().put("hostId",target.hostId).put("threadId","thread").put("thread",thread).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
        assertEquals(state,HostReadCodec.history(history.toString()).readState)
        val wrong=JSONObject(history.toString()).put("thread",JSONObject(thread.toString()).put("id","another-thread"))
        assertThrows(IllegalArgumentException::class.java) { HostReadCodec.history(wrong.toString()) }
        assertEquals(state,NativeHistoryWire.history(history.toString().byteInputStream()) { true }.readState)
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"reading-cache-"+UUID.randomUUID())
        val client=MobileClient(target.hostId,"reading",target,"https://fixture.invalid",readerId=request.readerId)
        val page=HostReadCodec.history(history.toString());val cache=NativeHistoryCache(storage)
        cache.save(client.cacheKey("thread"),client,"thread",page,page.messages)
        assertEquals(state,cache.read(client.cacheKey("thread"))!!.readState)
    }
}
