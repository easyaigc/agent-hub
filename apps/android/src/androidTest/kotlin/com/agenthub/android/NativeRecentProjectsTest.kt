package com.agenthub.android

import android.graphics.Bitmap
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Auxiliary native rules/archive checks. No Main, network, storage, command or model execution. */
class NativeRecentProjectsTest {
    private fun scope(agent: String="codex",account: String="one",host: String="recent-host",
        workspace: String="recent-workspace",runtime: String="recent-runtime") =
        TargetScope(agent,"recent-authority",account,host,workspace,runtime)
    private fun client(target: TargetScope)=MobileClient("recent-host","Recent fixture",target,"https://127.0.0.1:1")
    private fun rejected(value: Any) {
        val metadata=JSONObject().put("recentProjects",value)
        assertTrue(runCatching { NativeRecentProjects.read(metadata) }.isFailure)
    }

    @Test fun explicitNavigationHistoryCapsMovesAndFiltersWithoutDeletingStoredIDs() {
        var ids=emptyList<String>()
        for(id in listOf("a","b","c","d","e","f")) ids=NativeRecentProjects.record(ids,id)
        assertEquals(listOf("f","e","d","c","b"),ids)
        ids=NativeRecentProjects.record(ids,"d")
        assertEquals(listOf("d","f","e","c","b"),ids)
        assertEquals(listOf("d","b"),NativeRecentProjects.visibleIDs(ids,setOf("d","b","a")))
        assertEquals(listOf("d","f","e","c","b"),ids)
        assertEquals(listOf("d","f"),NativeRecentProjects.visibleIDs(listOf("d","d","f"),setOf("d","f")))
    }

    @Test fun runtimeIdentityReusesCodexAccountPolicyAndSeparatesEasyWorkTargets() {
        val codex=NativeRecentProjects.scope(client(scope()))
        assertEquals(codex,NativeRecentProjects.scope(client(scope(account="two"))))
        assertNotEquals(codex,NativeRecentProjects.scope(client(scope(host="other-host"))))
        assertNotEquals(codex,NativeRecentProjects.scope(client(scope(workspace="other-workspace"))))
        assertNotEquals(codex,NativeRecentProjects.scope(client(scope(runtime="other-runtime"))))
        val easy=NativeRecentProjects.scope(client(scope(agent="easywork")))
        assertNotEquals(codex,easy)
        assertNotEquals(easy,NativeRecentProjects.scope(client(scope(agent="easywork",account="two"))))
        val original=client(scope())
        assertEquals(codex,NativeRecentProjects.scope(original.copy(name="Renamed",endpoint="https://127.0.0.1:2")))
        assertNull(NativeRecentProjects.scope(MobileClient("legacy","No complete target",endpoint="https://127.0.0.1:1")))
    }

    @Test fun optionalVersionedHistoryIsStrictAndRetainsUnavailableScopes() {
        assertEquals(emptyMap<String,List<String>>(),NativeRecentProjects.read(JSONObject()))
        val a=NativeRecentProjects.scope(client(scope()))!!
        val removed=NativeRecentProjects.scope(client(scope(runtime="removed-runtime")))!!
        val history=mapOf(a to listOf("new","old"),removed to listOf("former-project"))
        val wire=NativeRecentProjects.encode(history)
        assertEquals(history,NativeRecentProjects.read(JSONObject().put("recentProjects",wire)))
        assertEquals(emptyList<String>(),NativeRecentProjects.visibleIDs(history.getValue(removed),emptySet()))
        assertEquals(listOf("former-project"),history[removed])
        rejected(JSONObject.NULL)
        rejected(JSONObject(wire.toString()).put("version",2))
        rejected(JSONObject(wire.toString()).put("version","1"))
        rejected(JSONObject(wire.toString()).put("unexpected",true))
        rejected(JSONObject().put("version",1).put("projectIDsByScope",JSONArray()))
        fun rows(key: String,ids: JSONArray)=JSONObject().put("version",1)
            .put("projectIDsByScope",JSONObject().put(key,ids))
        rejected(rows("not-a-scope",JSONArray().put("a")))
        rejected(rows(a,JSONArray().put("a").put("a")))
        rejected(rows(a,JSONArray().put("")))
        rejected(rows(a,JSONArray().put(JSONObject.NULL)))
        rejected(rows(a,JSONArray().put(7)))
        rejected(rows(a,JSONArray((1..6).map { "p$it" })))
        rejected(rows(a,JSONArray().put("x".repeat(NativeRecentProjects.MAX_PROJECT_ID+1))))
        val oversized=JSONObject()
        for(i in 0..NativeRecentProjects.MAX_SCOPES) oversized.put("%064x".format(i),JSONArray())
        rejected(JSONObject().put("version",1).put("projectIDsByScope",oversized))
    }

    @Test fun actualArchiveGraphRetainsNavigationWithoutCreatingStorageOrCommandAliases() {
        val archive=MigrationActivationFixtures.payload()
        val records=archive.getJSONObject("records")
        val metadata=JSONObject(records.getString("metadata"))
        val target=decodeClient(metadata.getJSONArray("clients").getJSONObject(0))
        val key=NativeRecentProjects.scope(target)!!
        val orphan=NativeRecentProjects.scope(client(scope(runtime="removed-runtime")))!!
        val history=mapOf(key to listOf("chosen-project","unavailable-project"),orphan to listOf("older-project"))
        metadata.put("recentProjects",NativeRecentProjects.encode(history))
        records.put("metadata",metadata.toString())
        val original=archive.toString()
        val parsed=NativeMigrationArchiveValidation.archive(original.toByteArray(),archive.getString("applicationId"))
        assertEquals(history,NativeRecentProjects.read(JSONObject(parsed.text.getValue("metadata"))))
        val aliases=NativeMigrationExistingAliases.root(parsed.text::get)
        assertFalse("operations:$key" in aliases)
        assertFalse("operations:$orphan" in aliases)
        assertEquals(original,archive.toString())
        metadata.getJSONObject("recentProjects").put("unexpected",true)
        records.put("metadata",metadata.toString())
        assertTrue(runCatching {
            NativeMigrationArchiveValidation.archive(archive.toString().toByteArray(),archive.getString("applicationId"))
        }.isFailure)
    }
}

/** Actual Main/Compose and encrypted-storage auxiliary checks; no Host, model input or OS cold start. */
@RunWith(AndroidJUnit4::class)
class NativeRecentProjectsUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val evidenceRun by lazy {
        (InstrumentationRegistry.getArguments().getString("recentProjectsRun") ?: UUID.randomUUID().toString())
            .also { require(UUID.fromString(it).toString()==it) }
    }

    @Before fun prepare() {
        ui.waitUntil(10_000) { !session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop()
            session.resetAcceptance();session.changeLanguage("zh-Hans");session.changeAppearance("light")
            session.setDemoMode(true)
        }
    }
    private fun ids()=session.recentProjects.map { it.id }
    private fun openPicker() {
        ui.onNodeWithTag("new-chat").performClick()
        ui.onNodeWithTag("sheet-projects").assertIsDisplayed()
    }
    private fun capture(marker: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory=File(ui.activity.filesDir,"acceptance/recent-projects-ui/$evidenceRun")
        check(directory.mkdirs() || directory.isDirectory)
        val file=File(directory,"$marker.png")
        try {
            val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL,0x180)
            FileOutputStream(fd).use { check(image.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync() }
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            instrumentation.sendStatus(2,Bundle().apply {
                putString("recentProjectsRun",evidenceRun);putString("recentProjectsFrame",marker)
                putString("recentProjectsFrameSHA256",hash);putLong("recentProjectsFrameBytes",file.length())
            })
        } finally { image.recycle() }
    }

    @Test fun productionPickerOrdersBothGroupsAndCancelAndProjectlessDoNotChangeHistory() {
        ui.runOnIdle { assertTrue(ids().isEmpty());session.newChat("mobile");session.newChat("api") }
        ui.runOnIdle { assertEquals(listOf("api","mobile"),ids()) }
        openPicker()
        val cancel=ui.onNodeWithTag("sheet-done").assertTextEquals("取消").fetchSemanticsNode()
        val title=ui.onNodeWithTag("sheet-title").fetchSemanticsNode()
        assertTrue("iOS cancellation action remains on the leading side",cancel.boundsInRoot.center.x<title.boundsInRoot.center.x)
        val recent=ui.onNodeWithTag("choose-recent-project-api").assertIsDisplayed().fetchSemanticsNode()
        val full=ui.onNodeWithTag("choose-project-api").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Recent choices precede the complete project list",recent.boundsInRoot.top<full.boundsInRoot.top)
        assertTrue("Recent row retains the 48dp touch target",recent.boundsInRoot.height+1>=48*ui.activity.resources.displayMetrics.density)
        ui.onNodeWithTag("projectless-new").assertIsDisplayed()
        capture("picker-light-zh")
        ui.runOnIdle { session.changeAppearance("dark");session.changeLanguage("en") }
        ui.onNodeWithTag("sheet-done").assertTextEquals("Cancel")
        ui.onNodeWithTag("choose-recent-project-api").assertIsDisplayed()
        capture("picker-dark-en")
        ui.onNodeWithTag("sheet-done").performClick()
        ui.onNodeWithTag("sheet-projects").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("api","mobile"),ids()) }
        openPicker()
        ui.onNodeWithTag("choose-recent-project-mobile").performClick()
        ui.onNodeWithTag("sheet-projects").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("mobile","api"),ids()) }
        openPicker()
        ui.onNodeWithTag("projectless-new").performClick()
        ui.runOnIdle { assertEquals(listOf("mobile","api"),ids()) }
    }

    @Test fun capturedChoiceCannotNavigateOrCloseThePickerAfterTheClientChanges() {
        ui.runOnIdle { session.newChat("mobile") }
        openPicker()
        ui.onNodeWithTag("choose-recent-project-mobile").performTouchInput { down(center) }
        var before=""
        ui.runOnIdle { session.select(DemoMobileData.clients.single { it.id=="demo-pc" });before=session.selectedThread }
        ui.onNodeWithTag("sheet-projects").performTouchInput { up() }
        ui.runOnIdle {
            assertEquals("demo-pc",session.selectedClient.id);assertEquals(before,session.selectedThread)
            assertTrue(ids().isEmpty())
        }
        ui.onNodeWithTag("sheet-projects").assertIsDisplayed()
        ui.onNodeWithTag("choose-recent-project-mobile").assertDoesNotExist()
        capture("stale-choice-retains-current-picker")
        ui.onNodeWithTag("sheet-done").performClick()
    }

    @Test fun encryptedMetadataRestoresTheSameHistoryAndResetClearsTheFixtureState() {
        val suffix=UUID.randomUUID().toString()
        val storage=NativeSessionStorage(ui.activity,"recent-navigation-$suffix","recent-navigation-key-$suffix")
        val failures=AtomicReference<Exception>()
        fun drain() {
            val done=CountDownLatch(1)
            storage.enqueueSnapshot("recent-navigation-drain",{ done.countDown() },failures::set)
            assertTrue("Encrypted metadata queue drained",done.await(15,TimeUnit.SECONDS))
            failures.get()?.let { throw it }
        }
        var first: MobileSession?=null
        var restored: MobileSession?=null
        try {
            ui.runOnIdle {
                first=MobileSession(storage).also {
                    it.resetAcceptance();it.setDemoMode(true)
                    it.newChat("mobile");it.newChat("api");it.leaveForeground()
                }
            }
            drain()
            val original=requireNotNull(storage.metadata())
            NativeMigrationMetadata.validate(original)
            val expected=mapOf(NativeRecentProjects.scope(DemoMobileData.clients.first())!! to listOf("api","mobile"))
            assertEquals(expected,NativeRecentProjects.read(original))
            ui.runOnIdle { restored=MobileSession(storage).also { it.restore() } }
            ui.waitUntil(15_000) { restored?.restoring==false }
            ui.runOnIdle {
                assertEquals(listOf("api","mobile"),restored!!.recentProjects.map { it.id })
                restored!!.newChat()
                assertEquals(listOf("api","mobile"),restored!!.recentProjects.map { it.id })
                restored!!.resetAcceptance();restored!!.setDemoMode(true)
                assertTrue(restored!!.recentProjects.isEmpty())
            }
            drain()
            assertFalse("Empty navigation history is omitted from new metadata",requireNotNull(storage.metadata()).has("recentProjects"))
        } finally {
            ui.runOnIdle { first?.leaveForeground();restored?.leaveForeground() }
            drain();storage.retireWriter()
        }
    }
}
