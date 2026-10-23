package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MobileWorkspaceMetadataTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=TargetScope("codex",null,null,"metadata-host","workspace-one","runtime-one")
    private fun thread(id: String)=JSONObject().put("id",id).put("hostId",scope.hostId).put("title",id).put("state","running")
    private fun sidebar()=JSONObject().put("version",1)
        .put("pinned",JSONArray().put(JSONObject().put("kind","project").put("id","project-one"))
            .put(JSONObject().put("kind","thread").put("id","thread-one")))
        .put("recentThreadIds",JSONArray(listOf("thread-two","thread-one"))).put("projectIds",JSONArray(listOf("project-one")))
        .put("projectThreadIds",JSONObject().put("project-one",JSONArray(listOf("thread-two","thread-one"))))
    private fun page()=JSONObject().put("hostId",scope.hostId).put("name","metadata fixture").put("connectionMode","history-only")
        .put("readTarget",CodexCommandCodec.target(scope)).put("projects",JSONArray())
        .put("threads",JSONArray().put(thread("thread-one").put("archived",true).put("lastTurnStatus","completed")
            .put("pendingAsyncQuestionCount",2).put("archiveUnavailableReason","official detail")))
        .put("pinnedThreadIds",JSONArray(listOf("thread-one"))).put("sidebar",sidebar()).put("nextThreadCursor","next")
    private fun decode(row: JSONObject)=HostReadCodec.workspace(row.toString())
    private fun rejected(block: ()->Unit) { try { block();fail("Invalid workspace metadata must fail") } catch(_: Exception) { } }

    @Test fun nativeHttpAndReopenedEncryptedStoreKeepLifecycleAndOrderedMetadata()=runBlocking {
        CommandFixture { WireResponse(body=page().toString()) }.use { server ->
            val value=NativeHostReads { "" }.workspace(MobileClient(scope.hostId,"fixture",scope,server.endpoint))
            val namespace="workspace-metadata-"+UUID.randomUUID()
            NativeSessionStorage(context,namespace).write("workspace",encodeWorkspace(value).toString())
            val restored=HostReadCodec.workspace(NativeSessionStorage(context,namespace).read("workspace")!!)
            assertEquals(value.threads,restored.threads);assertEquals(value.pinnedThreadIds,restored.pinnedThreadIds)
            assertEquals(value.sidebar,restored.sidebar)
            assertEquals(listOf("project","thread"),restored.sidebar!!.pinned.map { it.kind })
            assertEquals(listOf("thread-two","thread-one"),restored.sidebar!!.projectThreadIds["project-one"])
            assertEquals("running",restored.threads.single().state);assertEquals("completed",restored.threads.single().lastTurnStatus)
            assertEquals(true,restored.threads.single().archived);assertEquals(2,restored.threads.single().pendingAsyncQuestionCount)
            assertEquals("official detail",restored.threads.single().archiveUnavailableReason)
            assertNull(restored.runtimeConnected);assertTrue(restored.capabilities.isEmpty());assertEquals(1,server.requests.size)
        }
    }
    @Test fun paginationRetainsMissingProjectionWithoutReopeningExhaustedCursor() {
        val first=decode(page())
        val next=decode(page().removeProjection().put("threads",JSONArray().put(thread("thread-one")).put(thread("thread-two")))
            .put("nextProjectCursor","must-not-reopen").put("nextThreadCursor",JSONObject.NULL))
        val merged=HostReadCodec.workspace(encodeWorkspace(MobileWorkspaceMetadata.mergePage(first,next)).toString())
        assertEquals(listOf("thread-one","thread-two"),merged.threads.map { it.id })
        assertNull(merged.nextProjectCursor);assertNull(merged.nextThreadCursor)
        assertEquals(first.sidebar,merged.sidebar);assertEquals(first.pinnedThreadIds,merged.pinnedThreadIds)
        assertEquals(true,merged.threads.first().archived)
    }
    @Test fun explicitEmptyProjectionClearsWhereMissingMetadataInheritsOnlyOnPaging() {
        val first=decode(page())
        val cleared=decode(page().put("pinnedThreadIds",JSONArray()).put("sidebar",sidebar().put("pinned",JSONArray())
            .put("recentThreadIds",JSONArray()).put("projectIds",JSONArray()).put("projectThreadIds",JSONObject())))
        val merged=HostReadCodec.workspace(encodeWorkspace(MobileWorkspaceMetadata.mergePage(first,cleared)).toString())
        assertTrue(merged.pinnedThreadIds!!.isEmpty());assertTrue(merged.sidebar!!.pinned.isEmpty())
        val fresh=decode(page().removeProjection())
        assertNull(fresh.pinnedThreadIds);assertNull(fresh.sidebar)
    }
    @Test fun futureSidebarVersionIsOpaqueAndNeverReusesEarlierVersionOneOrder() {
        val future=decode(page().put("sidebar",JSONObject().put("version",2).put("future",JSONArray(listOf("opaque-data")))))
        val merged=MobileWorkspaceMetadata.mergePage(decode(page()),future)
        assertEquals(2,merged.sidebar!!.version);assertTrue(merged.sidebar!!.pinned.isEmpty())
        val wire=encodeWorkspace(merged).getJSONObject("sidebar")
        assertEquals("opaque-data",wire.getJSONArray("future").getString(0))
    }
    @Test fun foreignTargetPagesFailWhileHistoricalAccountMetadataDoesNotChangeMachineIdentity() {
        val first=decode(page())
        rejected { MobileWorkspaceMetadata.mergePage(first,first.copy(hostId="other-host")) }
        rejected { MobileWorkspaceMetadata.mergePage(first,first.copy(scope=TargetScope("codex",null,null,scope.hostId,scope.workspaceId,"other-runtime"))) }
        val changedAccount=first.copy(scope=TargetScope("codex","other-authority","other-subject",scope.hostId,scope.workspaceId,scope.runtimeId))
        assertEquals(first.threads,MobileWorkspaceMetadata.mergePage(first,changedAccount).threads)
        rejected { decode(page().put("threads",JSONArray().put(thread("thread-one").put("hostId","other-host")))) }
    }
    @Test fun oldPayloadsRemainReadableAndMalformedKnownMetadataIsRejected() {
        val old=decode(page().removeProjection().put("threads",JSONArray().put(thread("old"))))
        assertNull(old.threads.single().archived);assertNull(old.threads.single().lastTurnStatus)
        try { decode(page().put("threads",JSONArray().put(thread("bad").put("archived","true"))));fail("Reject malformed metadata") }
        catch(failure: IllegalStateException) { assertEquals("电脑返回的数据格式无效",NativeOperationFailure.describe(failure,"读取失败").message) }
        listOf<Any>("true",1).forEach { value -> rejected { decode(page().put("threads",JSONArray().put(thread("bad").put("archived",value)))) } }
        listOf<Any>(-1,1.5,"2").forEach { value -> rejected { decode(page().put("threads",JSONArray().put(thread("bad").put("pendingAsyncQuestionCount",value)))) } }
        rejected { decode(page().put("pinnedThreadIds",JSONArray().put(42))) }
        rejected { decode(page().put("sidebar",sidebar().put("version",1.5))) }
        rejected { decode(page().put("sidebar",sidebar().put("recentThreadIds",JSONArray().put(42)))) }
    }
    @Test fun productionSessionPagesAndRestoresOriginalMetadataWithoutGrantingCompose()=runBlocking {
        val namespace="workspace-session-metadata-"+UUID.randomUUID();val storage=NativeSessionStorage(context,namespace)
        var calls=0
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                calls++
                if(calls==1)return decode(page())
                assertNull(projectCursor);assertEquals("next",threadCursor)
                return decode(page().removeProjection().put("threads",JSONArray().put(thread("thread-two"))).put("nextThreadCursor",JSONObject.NULL))
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=error("No history navigation requested")
        }
        lateinit var session: MobileSession
        withContext(Dispatchers.Main) { session=MobileSession(storage,reader);session.restore() }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(20) }
        withContext(Dispatchers.Main) { session.connect("metadata session","https://fixture.invalid","") }
        try {
            withContext(Dispatchers.Main) { session.refresh(more=true) }
            withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.loading||session.threads.size!=2 })delay(20) }
            withTimeout(5_000) { while(storage.metadata()?.getJSONObject("workspaces")?.let { w -> w.keys().asSequence().any { w.getJSONObject(it).getJSONArray("threads").length()==2 } }!=true)delay(20) }
            val restoreReadStarted=CompletableDeferred<Unit>();val releaseRestoreRead=CompletableDeferred<Unit>()
            val stalledReader=object: HostReads {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    restoreReadStarted.complete(Unit);releaseRestoreRead.await();throw CancellationException("End auxiliary stalled read")
                }
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=error("No history navigation requested")
            }
            lateinit var restored: MobileSession
            withContext(Dispatchers.Main) { session.leaveForeground();restored=MobileSession(NativeSessionStorage(context,namespace),stalledReader);restored.restore() }
            try {
                withTimeout(5_000) { restoreReadStarted.await() }
                withTimeout(5_000) { while(withContext(Dispatchers.Main) { restored.threads.size!=2 })delay(20) }
                withContext(Dispatchers.Main) {
                    assertEquals(true,restored.threads.first().archived);assertEquals("completed",restored.threads.first().lastTurnStatus)
                    assertEquals(listOf("thread-one"),restored.workspace!!.pinnedThreadIds)
                    assertEquals(listOf("thread-two","thread-one"),restored.workspace!!.sidebar!!.recentThreadIds)
                    assertFalse(restored.canCompose);assertFalse(restored.selectedClient.online);assertEquals(2,calls)
                }
            } finally { releaseRestoreRead.complete(Unit);withContext(Dispatchers.Main) { restored.leaveForeground() } }
        } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
    }
    private fun JSONObject.removeProjection(): JSONObject { remove("pinnedThreadIds");remove("sidebar");return this }
}
