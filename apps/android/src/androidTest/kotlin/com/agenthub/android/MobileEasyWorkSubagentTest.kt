package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real adapter, authenticated encrypted session and shared projection; synthetic desktop peer. */
class MobileEasyWorkSubagentTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private fun snapshot(body: String,revision: Int=1,childRevision: Int=revision): JSONObject {
        val parts=JSONArray().put(JSONObject().put("type","text").put("text",body))
        val child=JSONObject().put("id","child").put("kind","child").put("threadId","thread")
            .put("branchRevision",childRevision).put("rootRunId","root").put("status","completed")
            .put("input",JSONObject().put("title","Original child").put("task","Original task"))
            .put("result",JSONObject().put("content",parts))
        val root=JSONObject().put("id","root").put("threadId","thread").put("branchRevision",revision)
        val tree=JSONObject().put("root",root).put("runs",JSONArray().put(child))
        return JSONObject().put("thread",JSONObject().put("id","thread").put("branchRevision",revision))
            .put("activeRun",JSONObject().put("runId","root").put("status","running"))
            .put("subagentRunTrees",JSONArray().put(tree))
    }
    private fun frame(snapshot: JSONObject): JSONObject {
        val content=JSONArray().put(JSONObject().put("type","text").put("text","Parent output remains separate"))
        val message=JSONObject().put("id","parent").put("role","assistant").put("status","partial").put("content",content)
        return JSONObject().put("snapshot",snapshot).put("branchRevision",1).put("messages",JSONArray().put(message))
    }
    private suspend fun fixture(wire: JSONObject,block: suspend (EasyWorkAgentClient,MobileClient,EasyWorkRemoteSession,NativeEasyWorkProtocolPeer,List<String>)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().context
        val vector=JSONObject(context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(vector.getJSONObject("credential"))
        val connection=AgentConnection(credential.binding.target.scope(),listOf(AgentCandidate("fixture","relay-http","https://relay.invalid")),"synthetic-ref","relay-only")
        val requests=CopyOnWriteArrayList<String>()
        val peer=NativeEasyWorkProtocolPeer(credential,vector.getString("confirmation")) { request->
            val method=request.getString("method");requests+=method
            require(method in listOf("thread/sync","thread/history","runtime/context")) { "A display read must not execute a command" }
            if(method=="runtime/context")JSONObject() else JSONObject(wire.toString())
        }
        val transports=AgentTransportRegistry().apply { register("relay-http") { _,_,_,_->peer } }
        val remote=EasyWorkRemoteSession(connection,credential,{ "synthetic" },transports)
        val client=MobileClient(connection.target.hostId,"Synthetic native subagent",connection.target,"https://relay.invalid",true,"easywork-runtime",false,agentConnection=connection)
        try { block(EasyWorkAgentClient(EasyWorkChannels { remote }),client,remote,peer,requests) } finally { remote.close() }
    }
    @Test fun authenticatedLatestHistoryPreservesFullChildWithoutChangingParent()=runBlocking {
        val body="HEAD"+"原文🧭".repeat(20000)+"MIDDLE"+"full ".repeat(12000)+"TAIL"
        fixture(frame(snapshot(body))) { adapter,client,_,_,requests->
            val messages=adapter.history(client,"thread",null).messages
            val child=messages.single { it.kind=="subAgent" }
            assertEquals("subagent:child",child.id);assertEquals("tool",child.role);assertEquals("root",child.runId)
            assertEquals("Original task\n\n$body",child.detail);assertEquals("completed",child.status)
            assertEquals("Parent output remains separate",messages.single { it.role=="assistant" }.text)
            assertEquals(listOf("thread/sync"),requests)
        }
    }
    @Test fun olderHistoryPageDoesNotDuplicateCurrentChildRows()=runBlocking {
        fixture(frame(snapshot("original child"))) { adapter,client,_,_,requests->
            assertEquals(1,adapter.history(client,"thread",null).messages.count { it.kind=="subAgent" })
            assertTrue(adapter.history(client,"thread","older-page").messages.none { it.kind=="subAgent" })
            assertEquals(listOf("thread/sync","thread/sync","thread/history","thread/sync"),requests)
        }
    }
    @Test fun foreignBranchChildIsExcludedByTheProductionHistoryPath()=runBlocking {
        fixture(frame(snapshot("foreign",childRevision=2))) { adapter,client,_,_,_->
            assertTrue(adapter.history(client,"thread",null).messages.none { it.kind=="subAgent" })
        }
    }
    @Test fun authenticatedChildEventsAreSessionLocalAndCloseRetiresThem()=runBlocking {
        val empty=snapshot("");empty.getJSONArray("subagentRunTrees").getJSONObject(0).put("runs",JSONArray())
        fixture(frame(empty)) { _,_,remote,peer,_->
            remote.request("runtime/context")
            peer.emitThreadEvent(JSONObject().put("threadId","thread").put("threadSeq",1).put("payload",JSONObject().put("branchRevision",1)
                .put("event",JSONObject().put("type","subagent_run_event").put("event",JSONObject().put("childRunId","child")
                    .put("rootRunId","root").put("phase","failed")))))
            remote.request("runtime/context") // Receive pump barrier after the encrypted event.
            assertEquals("failed",remote.subagentActivities(empty,"thread").single().status)
            remote.close()
            assertTrue(remote.subagentActivities(empty,"thread").isEmpty())
        }
    }
}
