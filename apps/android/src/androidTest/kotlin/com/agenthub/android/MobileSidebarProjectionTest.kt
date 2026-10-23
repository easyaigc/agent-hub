package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.WorkspaceSidebarRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileSidebarProjectionTest {
    private val vectors get()=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() })
    @Test fun platformMappingUsesAllSharedGoldenOrdersWithoutCreatingRowsOrAuthority() {
        for(test in vectors.getJSONArray("sidebar").objects()) {
            val input=test.getJSONObject("input");val host=input.getString("hostId")
            val threads=input.getJSONArray("threads").objects().map { MobileThread(it.getString("id"),it.getString("hostId"),it.getString("id"),"unknown",
                it.optional("projectId"),it.getJSONArray("projectIds").strings(),archived=it.getBoolean("archived")) }
            val projects=input.getJSONArray("projects").objects().map { MobileProject(it.getString("id"),it.getString("hostId"),"same visible folder",listOf("/synthetic/shared-root")) }
            val workspace=MobileWorkspace(host,"fixture","history-only",projects,threads,null,null,null,emptyList(),"{}",
                pinnedThreadIds=input.getJSONArray("pinnedThreadIds").strings(),sidebar=MobileWorkspaceMetadata.sidebar(JSONObject().put("sidebar",input.opt("source"))))
            val actual=mobileSidebarOrder(MobileClient(host,"fixture"),threads,projects,workspace);val expected=test.getJSONObject("expected");val name=test.getString("name")
            assertEquals(name,expected.getJSONArray("pinned").objects().map { it.getString("kind") to it.getString("id") },actual.pinned.map { it.kind to it.id })
            assertEquals(name,expected.getJSONArray("recentThreadIds").strings(),actual.recentThreadIds)
            assertEquals(name,expected.getJSONArray("projectIds").strings(),actual.projectIds)
            val children=expected.getJSONObject("projectThreadIds")
            assertEquals(name,children.keys().asSequence().toSet(),actual.projectThreads.map { it.projectId }.toSet())
            actual.projectThreads.forEach { assertEquals(name,children.getJSONArray(it.projectId).strings(),it.threadIds) }
            assertTrue(workspace.capabilities.isEmpty());assertNull(workspace.runtimeConnected)
        }
    }
    @Test fun nativeVisibilityMatchesSharedSearchAndFiveRowGoldenCases() {
        for(test in vectors.getJSONArray("sidebarVisibility").objects()) {
            val name=test.getString("name");val expanded=test.getBoolean("expanded");val searching=test.getBoolean("searching")
            assertEquals(name,test.getBoolean("expectedExpanded"),WorkspaceSidebarRules.expanded(expanded,searching))
            assertEquals(name,test.getJSONArray("expected").strings(),WorkspaceSidebarRules.visibleIds(test.getJSONArray("ids").strings(),expanded,searching))
        }
    }
}
