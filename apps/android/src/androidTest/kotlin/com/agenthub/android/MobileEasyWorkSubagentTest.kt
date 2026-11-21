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
            require(method in listOf("thread/sync","thread/history","runtime/context","thread/subscribe","thread/unsubscribe")) { "A display read must not execute a command" }
            if(method in listOf("runtime/context","thread/subscribe","thread/unsubscribe"))JSONObject() else JSONObject(wire.toString())
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
    @Test fun authenticatedEventRefreshReplacesGrowingChildWithoutCommands()=runBlocking {
        val first=snapshot("first checkpoint")
        val run=first.getJSONArray("subagentRunTrees").getJSONObject(0).getJSONArray("runs").getJSONObject(0)
        run.put("status","running").put("checkpoint",JSONObject().put("subagentTranscript",run.getJSONObject("result")))
        run.remove("result")
        val wire=frame(first)
        fixture(wire) { adapter,client,remote,peer,requests->
            val before=adapter.history(client,"thread",null).messages.single { it.kind=="subAgent" }
            assertEquals("inProgress",before.status);assertEquals("Original task\n\nfirst checkpoint",before.detail)
            val final="HEAD"+"正文🧭".repeat(20000)+"MIDDLE"+"tail ".repeat(12000)+"TAIL"
            wire.put("snapshot",snapshot(final))
            peer.emitThreadEvent(JSONObject().put("threadId","thread").put("threadSeq",2).put("payload",JSONObject().put("branchRevision",1)
                .put("event",JSONObject().put("type","subagent_run_event").put("event",JSONObject().put("childRunId","child")
                    .put("rootRunId","root").put("phase","completed")))))
            remote.request("runtime/context")
            val batch=adapter.events(client,"thread",remote.target.runtimeId+":"+remote.epoch,0)
            assertTrue(batch.cursor>0)
            val messages=batch.snapshots.single().messages
            val after=messages.single { it.kind=="subAgent" }
            assertEquals(before.id,after.id);assertEquals("root",after.runId);assertEquals("completed",after.status)
            assertEquals("Original task\n\n$final",after.detail)
            assertEquals("Parent output remains separate",messages.single { it.role=="assistant" }.text)
            assertTrue(requests.all { it in listOf("thread/sync","runtime/context","thread/subscribe") })
        }
    }
    @Test fun authenticatedConflictingEventCannotMoveSnapshotChildToAnotherVisibleRoot()=runBlocking {
        val original=snapshot("original child")
        original.getJSONArray("subagentRunTrees").put(JSONObject().put("root",JSONObject().put("id","other-root")
            .put("threadId","thread").put("branchRevision",1)).put("runs",JSONArray()))
        fixture(frame(original)) { adapter,client,remote,peer,_->
            val before=adapter.history(client,"thread",null).messages
            val parent=before.single { it.role=="assistant" }
            remote.request("runtime/context")
            peer.emitThreadEvent(JSONObject().put("threadId","thread").put("threadSeq",1).put("payload",JSONObject().put("branchRevision",1)
                .put("event",JSONObject().put("type","subagent_run_event").put("event",JSONObject().put("childRunId","child")
                    .put("rootRunId","other-root").put("phase","failed")))))
            remote.request("runtime/context")
            val after=adapter.history(client,"thread",null).messages
            val child=after.single { it.kind=="subAgent" }
            assertEquals(before.single { it.kind=="subAgent" }.id,child.id)
            assertEquals(parent,after.single { it.role=="assistant" })
            assertEquals(1,after.count { it.kind=="subAgent" })
            assertEquals("root",child.runId);assertEquals("completed",child.status)
            assertEquals("Original task\n\noriginal child",child.detail)
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
