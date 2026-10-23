package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Native HTTP and encrypted persistence. Synthetic Host responses do not prove real desktop/Git. */
@RunWith(AndroidJUnit4::class)
class MobileThreadForkTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace()="fork-boundary-"+UUID.randomUUID()
    private fun journal(name: String=namespace())=NativeOperationJournal(NativeSessionStorage(context,name))
    private fun flow(journal: NativeOperationJournal)=NativeThreadForkFlow(journal,NativeHostReads { "" },NativeCodexCommands { "" })
    private fun intent(environment: String="same-directory")=ThreadForkRequest("fork-host","source","earlier-terminal-turn",UUID.randomUUID().toString(),environment)
    private suspend fun fails(action: suspend ()->Unit) { assertTrue("Expected boundary rejection",runCatching { action() }.isFailure) }
    @Test fun exactFiveFieldPostUsesSelectedEarlierTurnAndNoReplyOrOpen()=runBlocking {
        ForkFixture().use { f -> val request=intent();val result=flow(journal()).submit(f.client,request,SubmissionOrigin("source-key","draft-version"),{true})
            assertEquals("confirmed",result.record.phase);assertEquals("fork-"+request.operationId,result.thread!!.id)
            val post=f.posts.single();assertEquals(setOf("hostId","threadId","turnId","clientOperationId","environment"),JSONObject(String(post.body)).keys().asSequence().toSet())
            assertEquals(request.body().toString(),JSONObject(String(post.body)).toString())
            assertTrue(f.server.requests.filter { it.method=="POST" }.all { it.path=="/v1/threads/fork" })
            assertTrue(f.server.requests.filter { it.path.startsWith("/v1/threads/fork") }.all { it.pathWithQuery.contains("target=") })
        }
    }
    @Test fun unknownSurvivesNewJournalAnd404NeverReplays()=runBlocking {
        ForkFixture().use { f -> f.disconnect=true;val name=namespace();val original=flow(journal(name)).submit(f.client,intent(),SubmissionOrigin("key","version"),{true})
            assertEquals("uncertain",original.record.phase);f.disconnect=false;f.statusCode=404
            val restored=journal(name).records(original.record.target).single();assertEquals(original.record.intent,restored.intent)
            fails { flow(journal(name)).query(f.client,restored) };assertEquals(1,f.posts.size)
            fails { journal(name).markSubmitting(restored.target,restored.id) }
            assertEquals(1,f.server.requests.count { it.path=="/v1/threads/fork" && it.method=="GET" })
        }
    }
    @Test fun changedEveryOriginalFieldRejectsForgedReceiptAndKeepsOriginal()=runBlocking {
        for((key,value) in mapOf("hostId" to "other","threadId" to "other","turnId" to "other","clientOperationId" to "other-operation","environment" to "worktree")) {
            ForkFixture().use { f -> f.patch={ it.put(key,value) };val j=journal();val result=flow(j).submit(f.client,intent(),SubmissionOrigin("key","v"),{true})
                assertEquals("uncertain",result.record.phase);assertNull(result.thread);assertNull(j.records(result.record.target).single().result);assertEquals(1,f.posts.size)
            }
        }
    }
    @Test fun missingFieldsUnknownPhaseAndSourceAsForkAreRejected()=runBlocking {
        for(patch in listOf<(JSONObject)->JSONObject>({it.apply { remove("forkedThreadId") }},{it.put("phase","done")},{it.put("forkedThreadId","source")},{it.put("extra",true)})) {
            ForkFixture().use { f -> f.patch=patch;val result=flow(journal()).submit(f.client,intent(),SubmissionOrigin("key","v"),{true})
                assertEquals("uncertain",result.record.phase);assertNull(result.thread);assertEquals(1,f.posts.size)
            }
        }
    }
    @Test fun pendingCanBeDetachedAndConfirmedByReadOnlyAfterRestart()=runBlocking {
        ForkFixture().use { f -> f.phase="outcome-unknown";val name=namespace();val j=journal(name)
            val first=flow(j).submit(f.client,intent("worktree"),SubmissionOrigin("key","v"),{true})
            j.detach(first.record.target,first.record.id);f.phase="confirmed"
            val restored=journal(name);val detached=restored.records(first.record.target).single();val queried=flow(restored).query(f.client,detached)
            assertEquals("confirmed",queried.record.phase);assertTrue(queried.record.detached);assertEquals(first.record.intent,queried.record.intent)
            assertEquals("fork-project",queried.thread!!.projectId);assertEquals(1,f.posts.size)
            assertEquals(1,f.server.requests.count { it.path=="/v1/threads/fork" && it.method=="GET" })
        }
    }
    @Test fun terminalReceiptCannotRollbackOrChangeForkAndProject()=runBlocking {
        ForkFixture().use { f -> val j=journal();val first=flow(j).submit(f.client,intent(),SubmissionOrigin("key","v"),{true})
            f.phase="submitted";fails { flow(j).query(f.client,first.record) };f.phase="confirmed"
            for(key in listOf("forkedThreadId","projectId")) { f.patch={it.put(key,"other")};fails { flow(j).query(f.client,first.record) } }
            assertEquals(first.record.result,j.records(first.record.target).single().result);assertEquals(1,f.posts.size)
        }
    }
    @Test fun runtimeAgentAndConnectionTrustChangesCannotQueryOriginal()=runBlocking {
        ForkFixture().use { f -> f.phase="outcome-unknown";val first=flow(journal()).submit(f.client,intent(),SubmissionOrigin("key","v"),{true})
            val scope=f.client.scope!!
            val changed=listOf(f.client.copy(scope=TargetScope("codex",null,null,"fork-host","fork-workspace","other")),f.client.copy(scope=TargetScope("easywork",null,null,"fork-host","fork-workspace",f.runtimeId)),f.client.copy(endpoint=f.client.endpoint+"/other"))
            val before=f.server.requests.size
            for(client in changed) fails { flow(journal()).query(client,first.record) }
            assertEquals(before,f.server.requests.size);assertTrue(first.record.forkContext!!.sameConnection(f.client));assertFalse(first.record.forkContext.sameConnection(changed.first()))
            val paired=f.client.copy(codexDirect=PairedCodexConnection(scope,"a".repeat(64),listOf(CodexRoute("https://127.0.0.1:1","lan"))))
            assertFalse(NativeForkContext.capture(paired).sameConnection(paired.copy(codexDirect=paired.codexDirect!!.copy(fingerprint="b".repeat(64)))))
        }
    }
    @Test fun historyOnlyAndRevokedGitCapabilityNeverPost()=runBlocking {
        for(mode in listOf("history","missing-capability","non-git")) ForkFixture().use { f -> f.mode=mode
            fails { flow(journal()).submit(f.client,intent("worktree"),SubmissionOrigin("key","v"),{true}) };assertTrue(f.posts.isEmpty())
        }
    }
    @Test fun selectionChangesAfterPersistenceRejectBeforeHttp()=runBlocking {
        ForkFixture().use { f -> var checks=0;val name=namespace();val result=flow(journal(name)).submit(f.client,intent(),SubmissionOrigin("key","v"),{++checks<3})
            assertEquals("rejected",result.record.phase);assertFalse(result.record.submissionAttempted);assertTrue(f.posts.isEmpty())
            assertEquals("rejected",journal(name).records(result.record.target).single().phase)
        }
    }
    @Test fun writeFailurePreventsAnyForkPost()=runBlocking {
        val name=namespace();val collision=File(context.noBackupFilesDir,name);collision.writeText("synthetic storage unavailable")
        try { ForkFixture().use { f -> fails { flow(journal(name)).submit(f.client,intent(),SubmissionOrigin("key","v"),{true}) };assertTrue(f.posts.isEmpty()) } }
        finally { assertTrue(collision.delete()) }
    }
    @Test fun concurrentMarkSubmittingAcrossInstancesHasOneWinner()=runBlocking {
        ForkFixture().use { f -> val name=namespace();val one=journal(name);val two=journal(name);val r=one.prepare(f.client,"source","fork",intent().body())
            val gate=CompletableDeferred<Unit>();val results=(0..<12).map { i->async(Dispatchers.IO) { gate.await();runCatching { (if(i%2==0)one else two).markSubmitting(r.target,r.id) }.isSuccess } };gate.complete(Unit)
            assertEquals(1,results.awaitAll().count { it });assertTrue(journal(name).records(r.target).single().submissionAttempted);assertTrue(f.posts.isEmpty())
        }
    }
    @Test fun confirmedNavigationReadRejectsWrongRuntimeWithoutLosingReceipt()=runBlocking {
        ForkFixture().use { f -> f.wrongHistory=true;val j=journal();fails { flow(j).submit(f.client,intent(),SubmissionOrigin("key","v"),{true}) }
            val record=j.records(NativeOperationJournal.target(f.client,"source")).single();assertEquals("confirmed",record.phase);assertNotNull(record.result);assertEquals(1,f.posts.size)
            f.wrongHistory=false;val recovered=flow(j).query(f.client,record);assertEquals("confirmed",recovered.record.phase);assertEquals(1,f.posts.size)
        }
    }
}
internal class ForkFixture: java.io.Closeable {
    @Volatile var phase="confirmed";@Volatile var disconnect=false;@Volatile var statusCode=200;@Volatile var mode="desktop-runtime";@Volatile var wrongHistory=false
    var patch: (JSONObject)->JSONObject={it}
    private val operations=ConcurrentHashMap<String,JSONObject>()
    val runtimeId="fork-runtime-"+UUID.randomUUID()
    val target=JSONObject().put("agentId","codex").put("hostId","fork-host").put("workspaceId","fork-workspace").put("runtimeId",runtimeId)
    private fun thread(id: String)=JSONObject().put("id",id).put("hostId","fork-host").put("title",if(id=="source") "Fork source" else "Fork result").put("state","idle").put("projectId","fork-project")
    private fun receipt(request: JSONObject)=patch(JSONObject(request.toString()).put("phase",phase).put("forkedThreadId",if(phase=="confirmed") "fork-"+request.getString("clientOperationId") else JSONObject.NULL)
        .put("projectId",if(phase=="confirmed") "fork-project" else JSONObject.NULL).put("error",if(phase=="rejected") "电脑拒绝了分支" else JSONObject.NULL))
    val server=CommandFixture { r -> when(r.path) {
        "/v1/workspace" -> WireResponse(body=JSONObject().put("hostId","fork-host").put("name","Fork computer").put("connectionMode",if(mode=="history") "history-reader" else "desktop-runtime")
            .put("readTarget",target).put("capabilities",JSONArray(if(mode=="missing-capability") emptyList<String>() else listOf("projects:read","threads:read","history:read","commands:target-bound:v1","reply","desktop:creations","desktop:creations:projectless","threads:fork","threads:fork:worktree")))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray().put(JSONObject().put("id","fork-project").put("hostId","fork-host").put("name","Fork project").put("roots",JSONArray())))
            .put("threads",JSONArray(listOf(thread("source"))+operations.values.filter { phase=="confirmed" }.map { thread("fork-"+it.getString("clientOperationId")) })).toString())
        "/v1/history" -> { val id=r.parameters["threadId"]!!;WireResponse(body=JSONObject().put("hostId","fork-host").put("threadId",id).put("thread",thread(id))
            .put("readTarget",if(wrongHistory) JSONObject(target.toString()).put("runtimeId","other") else target)
            .put("messages",JSONArray().put(JSONObject().put("id","first-reply").put("role","assistant").put("text","FIRST_COMPLETED_REPLY").put("replyPhase","final_answer").put("runId","earlier-terminal-turn").put("status","completed").put("files",JSONArray()))
                .put(JSONObject().put("id","second-reply").put("role","assistant").put("text","SECOND_COMPLETED_REPLY").put("replyPhase","final_answer").put("runId","later-terminal-turn").put("status","completed").put("files",JSONArray())))
            .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray()).put("error",JSONObject.NULL)).toString()) }
        "/v1/threads/fork-options" -> WireResponse(body=JSONObject().put("hostId","fork-host").put("threadId","source").put("sameDirectoryAvailable",true).put("worktreeAvailable",mode!="non-git").put("reason",JSONObject.NULL).put("worktreeReason",if(mode=="non-git") "此对话不在 Git 项目中，不能创建工作树" else JSONObject.NULL).toString())
        "/v1/threads/fork" -> if(r.method=="POST") { val request=JSONObject(String(r.body));operations[request.getString("clientOperationId")]=request;WireResponse(body=receipt(request).toString(),disconnect=disconnect) }
            else if(statusCode!=200) WireResponse(status=statusCode) else operations[r.parameters["id"]]?.let { WireResponse(body=receipt(it).toString()) } ?: WireResponse(status=404)
        "/v1/models" -> WireResponse(body=JSONObject().put("hostId","fork-host").put("models",JSONArray()).toString())
        else -> WireResponse(status=404)
    } }
    val client get()=MobileClient("fork-host","Fork computer",HostReadCodec.target(target),server.endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
    val posts get()=server.requests.filter { it.method=="POST" && it.path=="/v1/threads/fork" }
    override fun close()=server.close()
}
internal val WireRequest.parameters get()=pathWithQuery.substringAfter('?').split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
