package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Native HTTP and Android Keystore persistence; no real desktop or model. */
class MobileChatActionFlowTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace()="chat-action-flow-"+UUID.randomUUID()
    private fun journal(name: String=namespace())=NativeOperationJournal(NativeSessionStorage(context,name))
    private fun flow(journal: NativeOperationJournal)=NativeChatActionFlow(journal,NativeCodexCommands { "" })
    private suspend fun fails(block: suspend ()->Unit) { assertTrue("Expected boundary rejection",runCatching { block() }.isFailure) }
    @Test fun lifecycleAndAvailabilityGoldensUseNativeProductionRules() {
        val json=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() })
        val lifecycle=json.getJSONArray("chatLifecycle").objects()
        for(row in lifecycle) {
            val status=com.agenthub.core.ChatManagementRules.taskStatus(row.getString("state"),row.optional("lastTurnStatus"),row.getBoolean("archived"),row.getBoolean("online"),row.getBoolean("unconfirmed"))
            assertEquals(row.getString("name"),row.getString("code"),status.code);assertEquals(row.getBoolean("lastKnown"),status.lastKnown)
        }
        assertEquals(17,lifecycle.size)
        val availability=json.getJSONArray("chatManagementAvailability").objects()
        for(row in availability)assertEquals(row.getString("name"),row.getBoolean("expected"),com.agenthub.core.ChatManagementRules.canManage(row.getBoolean("demo"),row.getBoolean("online"),row.getBoolean("supported"),row.getBoolean("knownTarget"),row.getBoolean("busy"),row.getBoolean("pending")))
        assertEquals(7,availability.size)
    }
    @Test fun fiveNamedActionsPersistExactIntentBeforeOnePostEach()=runBlocking {
        ChatActionFixture().use { fixture ->
            val name=namespace();val j=journal(name)
            for(action in listOf("pin","unpin","archive","unarchive","delete")) {
                val request=fixture.intent(action)
                fixture.beforeReply={ original ->
                    val record=runBlocking { journal(name).records(NativeOperationJournal.target(fixture.client,null)).single { it.id==request.operationId } }
                    assertTrue(record.submissionAttempted);assertEquals("submitting",record.phase)
                    assertEquals(request.body().toString(),record.intent);assertEquals(request.operationId,original.getString("clientOperationId"))
                }
                val result=flow(j).submit(fixture.client,request,"source-key","selection-1") { true }
                assertEquals("confirmed",result.record.phase)
            }
            assertEquals(5,fixture.posts.size);assertEquals(5,journal(name).records(NativeOperationJournal.target(fixture.client,null)).size)
            assertTrue(fixture.server.requests.all { it.path=="/v1/threads/actions"&&it.pathWithQuery.contains("target=") })
        }
    }
    @Test fun unknownRestoresAnd404CannotReplayEvenAfterWaitingEnds()=runBlocking {
        ChatActionFixture().use { fixture ->
            val name=namespace();fixture.disconnect=true
            val first=flow(journal(name)).submit(fixture.client,fixture.intent(),"source-key","selection-1") { true }
            assertEquals("uncertain",first.record.phase);fixture.disconnect=false
            val j=journal(name);j.detach(first.record.target,first.record.id);val record=j.records(first.record.target).single()
            assertTrue(record.detached);fixture.getStatus=404;fails { flow(j).query(fixture.client,record) }
            assertEquals(1,fixture.posts.size);assertEquals("uncertain",j.records(record.target).single().phase)
            fixture.getStatus=200;val result=flow(j).query(fixture.client,record)
            assertEquals("confirmed",result.record.phase);assertTrue(result.record.detached)
            assertEquals(first.record.intent,result.record.intent);assertEquals(first.record.intentSha256,result.record.intentSha256);assertEquals(1,fixture.posts.size)
        }
    }
    @Test fun definitiveHttpRefusalIsRejectedWhileServerFailureIsUnknown()=runBlocking {
        ChatActionFixture().use { fixture ->
            fixture.postStatus=409
            val rejected=flow(journal()).submit(fixture.client,fixture.intent(),"key","selection") { true }
            assertEquals("rejected",rejected.record.phase);assertTrue(rejected.record.error!!.contains("桌面端"))
            fixture.postStatus=500
            val unknown=flow(journal()).submit(fixture.client,fixture.intent(),"key","selection") { true }
            assertEquals("uncertain",unknown.record.phase);assertEquals(2,fixture.posts.size)
        }
    }
    @Test fun wrongActionOperationThreadAndRuntimeNeverReplaceOriginalReceipt()=runBlocking {
        ChatActionFixture().use { fixture ->
            val j=journal();val first=flow(j).submit(fixture.client,fixture.intent(),"key","selection") { true }
            for(field in listOf("action","clientOperationId","threadId","runtimeId")) {
                fixture.patch={ value -> if(field=="runtimeId")value.getJSONObject("target").put(field,"other-runtime") else value.put(field,if(field=="action")"delete" else "other-identity") }
                fails { flow(j).query(fixture.client,first.record) }
                assertEquals(first.record.result,j.records(first.record.target).single().result)
            }
            assertEquals(1,fixture.posts.size)
        }
    }
    @Test fun confirmedAndRejectedAreMonotonicAcrossJournalInstances()=runBlocking {
        ChatActionFixture().use { fixture ->
            for(terminal in listOf("confirmed","rejected")) {
                fixture.phase=terminal;val name=namespace();val first=flow(journal(name)).submit(fixture.client,fixture.intent(),"key","selection") { true }
                fixture.phase="outcome-unknown";fails { flow(journal(name)).query(fixture.client,first.record) }
                assertEquals(terminal,journal(name).records(first.record.target).single().phase)
            }
        }
    }
    @Test fun accountMetadataIsNotAGateButChangedMachineCannotQuery()=runBlocking {
        ChatActionFixture().use { fixture ->
            val j=journal();val first=flow(j).submit(fixture.client,fixture.intent(),"key","selection") { true }
            val metadata=fixture.client.copy(scope=TargetScope("codex","new-authority","new-metadata",fixture.client.id,"workspace-actions","runtime-actions"))
            fixture.patch={ it.getJSONObject("target").put("account",JSONObject().put("authority","new-authority").put("subject","new-metadata")) }
            val result=flow(j).query(metadata,first.record)
            assertEquals("confirmed",result.record.phase);assertEquals(first.record.intent,result.record.intent)
            val before=fixture.server.requests.size
            fails { flow(j).query(metadata.copy(scope=TargetScope("codex",null,null,fixture.client.id,"workspace-actions","changed-runtime")),result.record) }
            assertEquals(before,fixture.server.requests.size);assertEquals(1,fixture.posts.size)
        }
    }
    @Test fun targetChangesAtEitherPersistBoundaryPreventAllPosts()=runBlocking {
        ChatActionFixture().use { fixture ->
            for(limit in listOf(2,3)) {
                var checks=0
                val result=flow(journal()).submit(fixture.client,fixture.intent(),"key","selection") { ++checks<limit }
                assertEquals("rejected",result.record.phase);assertNull(result.record.result)
                assertTrue(result.record.error!!.contains("未发送"))
            }
            assertTrue(fixture.posts.isEmpty())
        }
    }
    @Test fun unavailableAtomicStoragePreventsHttpAndDuplicateIdHasOneWinner()=runBlocking {
        val name=namespace();val collision=File(context.noBackupFilesDir,name);collision.writeText("synthetic storage failure")
        try { ChatActionFixture().use { fixture -> fails { flow(journal(name)).submit(fixture.client,fixture.intent(),"key","selection") { true } };assertTrue(fixture.posts.isEmpty()) } }
        finally { assertTrue(collision.delete()) }
        ChatActionFixture().use { fixture ->
            val name2=namespace();val request=fixture.intent();val gate=CompletableDeferred<Unit>()
            val results=(0..<8).map { async(Dispatchers.IO) { gate.await();runCatching { flow(journal(name2)).submit(fixture.client,request,"key","selection") { true } } } }
            gate.complete(Unit);results.awaitAll();assertEquals(1,fixture.posts.size)
            assertEquals("confirmed",journal(name2).records(NativeOperationJournal.target(fixture.client,null)).single().phase)
        }
    }
}

internal class ChatActionFixture: java.io.Closeable {
    @Volatile var phase="confirmed";@Volatile var disconnect=false;@Volatile var postStatus=200;@Volatile var getStatus=200
    @Volatile var patch: ((JSONObject)->Unit)?=null
    @Volatile var beforeReply: ((JSONObject)->Unit)?=null
    private val requests=ConcurrentHashMap<String,String>()
    val target=TargetScope("codex","old-authority","old-metadata","host-actions-01","workspace-actions","runtime-actions")
    val server=CommandFixture { request ->
        require(request.path=="/v1/threads/actions")
        val status=if(request.method=="POST")postStatus else getStatus
        if(request.method=="POST") {
            val value=JSONObject(String(request.body));requests[value.getString("clientOperationId")]=value.toString();beforeReply?.invoke(value)
        }
        if(status!=200)WireResponse(status,JSONObject().put("error","电脑端拒绝了此次操作：请在桌面端操作。对话未改变。").toString())
        else {
            val id=if(request.method=="POST")JSONObject(String(request.body)).getString("clientOperationId") else URI(request.pathWithQuery).rawQuery.split('&').map { it.split('=',limit=2) }.first { it[0]=="id" }.let { URLDecoder.decode(it[1],"UTF-8") }
            val value=JSONObject(requests[id]!!).put("phase",phase).put("error",if(phase=="rejected")"电脑端拒绝了此次操作" else JSONObject.NULL)
            patch?.invoke(value);WireResponse(body=value.toString(),disconnect=disconnect)
        }
    }
    val client get()=MobileClient(target.hostId,"actions fixture",target,server.endpoint,true,"desktop-runtime",false)
    val posts get()=server.requests.filter { it.method=="POST" }
    fun intent(action: String="archive")=ChatActionRequest(target.hostId,target,"thread-actions-01",UUID.randomUUID().toString(),action)
    override fun close()=server.close()
}
