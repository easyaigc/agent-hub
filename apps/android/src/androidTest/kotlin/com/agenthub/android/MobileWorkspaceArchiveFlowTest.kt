package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class MobileWorkspaceArchiveFlowTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun journal(name: String)=NativeOperationJournal(NativeSessionStorage(context,name))
    private fun flow(j: NativeOperationJournal)=NativeWorkspaceArchiveFlow(j,NativeCodexCommands { "" })
    private fun request(f: ChatManagementFixture,ids: List<String> = listOf(ChatManagementFixture.first,ChatManagementFixture.other))=
        WorkspaceArchiveRequest(f.target.hostId,f.target,UUID.randomUUID().toString(),ids)
    private fun client(f: ChatManagementFixture)=MobileClient(f.target.hostId,"batch fixture",f.target,f.server.endpoint,true,"desktop-runtime",false)
    private fun same(a: Any?,b: Any?): Boolean = when {
        a is JSONObject&&b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet()&&a.keys().asSequence().all { same(a.get(it),b.get(it)) }
        a is org.json.JSONArray&&b is org.json.JSONArray -> a.length()==b.length()&&(0 until a.length()).all { same(a.get(it),b.get(it)) }
        else -> a==b
    }
    private suspend fun fails(block: suspend ()->Unit) { assertTrue(runCatching { block() }.isFailure) }
    @Test fun exactDurableIntentAndSubmissionMarkerPrecedeOnlyNamedPost()=runBlocking {
        ChatManagementFixture().use { f ->
            val name="batch-flow-"+UUID.randomUUID();val j=journal(name);val c=client(f);val req=request(f)
            f.batchObserve={ wire -> runBlocking {
                val saved=journal(name).records(c.cacheKey(null)).single()
                assertEquals(req.body().toString(),saved.intent);assertEquals("submitting",saved.phase);assertTrue(saved.submissionAttempted);assertEquals(req.ids,wire.getJSONArray("ids").strings())
            } }
            val result=flow(j).submit(c,req,mapOf(req.ids.first() to "Original label")) { true }
            assertEquals("completed",result.record.phase);assertEquals(1,f.posts.size)
            assertEquals("/v1/workspace-management",f.posts.single().path)
            assertEquals(2,WorkspaceArchiveCodec.receipt(JSONObject(result.record.result!!)).items.count { it.state=="completed" })
            fails { flow(j).submit(c,req,emptyMap()) { true } };assertEquals(1,f.posts.size)
        }
    }
    @Test fun partialAndUnknownRestoreQuery404AndTerminalMergeNeverReplay()=runBlocking {
        ChatManagementFixture().use { f ->
            val name="batch-flow-"+UUID.randomUUID();val c=client(f);val req=request(f);f.batchStates[req.ids.last()]="outcome-unknown"
            val first=flow(journal(name)).submit(c,req,emptyMap()) { true };assertEquals("outcome-unknown",first.record.phase)
            f.batchGetStatus=404;fails { flow(journal(name)).query(c,first.record) };assertEquals(first.record.result,journal(name).records(c.cacheKey(null)).single().result)
            f.batchGetStatus=200;f.batchStates[req.ids.first()]="rejected";f.batchStates[req.ids.last()]="rejected"
            val next=flow(journal(name)).query(c,first.record);val items=WorkspaceArchiveCodec.receipt(JSONObject(next.record.result!!)).items
            assertEquals(listOf("completed","rejected"),items.map { it.state });assertNull(items.first().error);assertEquals("completed",next.record.phase)
            f.batchStates.clear();val late=flow(journal(name)).query(c,next.record)
            assertEquals(listOf("completed","rejected"),WorkspaceArchiveCodec.receipt(JSONObject(late.record.result!!)).items.map { it.state });assertEquals(1,f.posts.size)
        }
    }
    @Test fun serverFailureAndWrongReceiptRetainOriginalUnknownIntent()=runBlocking {
        ChatManagementFixture().use { f ->
            val name="batch-flow-"+UUID.randomUUID();val c=client(f);val req=request(f);f.batchPostStatus=500
            val original=flow(journal(name)).submit(c,req,emptyMap()) { true };assertEquals("uncertain",original.record.phase)
            for(field in listOf("clientOperationId","ids","runtimeId","items")) {
                f.batchPatch={ value ->when(field) {
                    "runtimeId"->value.getJSONObject("target").put("runtimeId","wrong-runtime")
                    "ids"->value.put("ids",org.json.JSONArray(req.ids.reversed()))
                    "items"->value.put("items",org.json.JSONArray(value.getJSONArray("items").objects().reversed()))
                    else->value.put(field,"wrong-operation-id")
                };Unit }
                fails { flow(journal(name)).query(c,original.record) };assertNull(journal(name).records(c.cacheKey(null)).single().result)
            }
            assertEquals(1,f.posts.size);assertEquals(original.record.intent,journal(name).records(c.cacheKey(null)).single().intent)
        }
    }
    @Test fun selectionAndPersistenceGuardsProduceNoPost()=runBlocking {
        ChatManagementFixture().use { f ->
            val c=client(f);val req=request(f);val j=journal("batch-flow-"+UUID.randomUUID());var calls=0
            val result=flow(j).submit(c,req,emptyMap()) { ++calls<3 }
            assertTrue(f.posts.isEmpty());assertEquals("completed",result.record.phase)
            assertTrue(WorkspaceArchiveCodec.receipt(JSONObject(result.record.result!!)).items.all { it.state=="rejected" })
            val brokenName="batch-broken-"+UUID.randomUUID();val collision=java.io.File(context.noBackupFilesDir,brokenName)
            collision.writeText("synthetic unavailable atomic store")
            try { fails { flow(journal(brokenName)).submit(c,request(f),emptyMap()) { true } };assertTrue(f.posts.isEmpty()) }
            finally { assertTrue(collision.delete()) }

        }
    }
    @Test fun invalidAndOver100RequestsNeverReachHttpAndSharedVectorsRunNatively()=runBlocking {
        ChatManagementFixture().use { f ->
            val c=client(f);val j=journal("batch-flow-"+UUID.randomUUID())
            for(ids in listOf(emptyList(),listOf("same","same"),(1..101).map { "chat-$it" }))fails { flow(j).submit(c,request(f,ids),emptyMap()) { true } }
            assertTrue(f.posts.isEmpty())
            val rows=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() }).getJSONArray("workspaceArchive").objects()
            assertEquals(12,rows.size)
            for(row in rows) {
                val result=com.agenthub.core.WorkspaceArchiveRules.merge(row.getJSONObject("original").toString(),row.optJSONObject("previous")?.toString(),row.getJSONObject("incoming").toString())
                if(row.isNull("expected"))assertNull(result)else assertTrue(same(row.getJSONObject("expected"),JSONObject(result!!)))
            }
        }
    }
}
