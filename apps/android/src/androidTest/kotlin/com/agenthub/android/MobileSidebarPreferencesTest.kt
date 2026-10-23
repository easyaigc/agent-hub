package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileSidebarPreferencesTest {
    @Test fun encryptedSessionRestoreKeepsSeparateSectionsAndLegacyDefaults()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val namespace="sidebar-preferences-"+UUID.randomUUID();val storage=NativeSessionStorage(context,namespace)
        val target=TargetScope("codex",null,null,"sidebar-pref-host","workspace","runtime")
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",target.hostId).put("name","sidebar preferences").put("connectionMode","history-only")
                .put("readTarget",CodexCommandCodec.target(target)).put("projects",JSONArray()).put("threads",JSONArray()).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=error("No history requested")
        }
        lateinit var original: MobileSession
        withContext(Dispatchers.Main) { original=MobileSession(storage,reader);original.restore() }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { original.restoring })delay(20) }
        withContext(Dispatchers.Main) {
            original.connect("sidebar preferences","https://fixture.invalid","")
            original.toggleSidebarPinnedSection();original.toggleSidebarRecentSection();original.toggleSidebarProjects();original.toggleSidebarRecent();original.toggleSidebarList("project-one")
            original.leaveForeground()
        }
        val fence=CountDownLatch(1)
        storage.enqueueSnapshot("sidebar-fence",{ fence.countDown() },{ throw AssertionError(it) })
        assertTrue(fence.await(10,TimeUnit.SECONDS))
        suspend fun restoreAndCheck(expectedSections: Boolean) {
            lateinit var session: MobileSession
            withContext(Dispatchers.Main) { session=MobileSession(NativeSessionStorage(context,namespace),reader);session.restore() }
            try {
                withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(20) }
                withContext(Dispatchers.Main) {
                    assertEquals(expectedSections,session.sidebarDisplay.pinnedSectionExpanded);assertEquals(expectedSections,session.sidebarDisplay.recentSectionExpanded)
                    assertFalse(session.sidebarDisplay.projectsExpanded);assertTrue(session.sidebarDisplay.recentExpanded)
                    assertTrue("project-one" in session.sidebarDisplay.expandedLists)
                }
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
        restoreAndCheck(false)
        // The original v1 cache has no independent section fields. Keep its
        // list/folder settings while defaulting the two newly supported sections open.
        val snapshot=storage.metadata()!!
        snapshot.getJSONObject("sidebar").let { states -> states.keys().forEach { key -> states.getJSONObject(key).remove("pinnedSection");states.getJSONObject(key).remove("recentSection") } }
        val drained=CountDownLatch(1);storage.enqueueSnapshot("sidebar-fence-two",{ drained.countDown() },{ throw AssertionError(it) });assertTrue(drained.await(10,TimeUnit.SECONDS))
        storage.write("metadata",snapshot.toString())
        restoreAndCheck(true)
    }
}
