package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Exact current owner only; independent gate rejects every write, test never sends/opens a chat. */
@RunWith(AndroidJUnit4::class)
class MobileLargeOwnerReadLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"large-owner-live.json").readText()) }
    private val folder by lazy { File(instrumentation.targetContext.filesDir,"acceptance/P2-large-owner/"+config.getString("run")).apply { mkdirs() } }
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance")
            require(InstrumentationRegistry.getArguments().getString("nativeLargeOwnerRead")=="1")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity")).bufferedReader().use { assertTrue(it.readText().contains("Status: ok")) }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    @get:Rule(order=2) val limit=org.junit.rules.Timeout.seconds(600)
    private data class EarlierResponse(val host: String,val thread: String,val windowed: Boolean,val kind: String?,val ids: List<String>)
    private val earlierResponses=java.util.concurrent.CopyOnWriteArrayList<EarlierResponse>()
    private lateinit var session: MobileSession
    private lateinit var storage: NativeSessionStorage
    private var radioMonitor: NativeReadNetworkMonitor?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Suppress("UNCHECKED_CAST") private fun page(): MobileHistory?=(MobileSession::class.java.getDeclaredField("historyPages").apply { isAccessible=true }.get(session) as Map<String,MobileHistory>)[session.targetKey]
    private fun shot(name: String) {
        ui.waitForIdle();val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
    }
    private fun tapEarlier(tag: String) {
        val limit=session.visibleMessageLimit;val responses=earlierResponses.size
        val deadline=android.os.SystemClock.uptimeMillis()+15_000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag))
            ui.waitUntil((deadline-android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) { ui.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size==1 }
            // A legitimate latest GET can disable the button between observation
            // and native touch. Retry only an unaccepted touch, never a started page.
            ui.onNodeWithTag(tag).performClick();ui.waitForIdle()
            if(session.visibleMessageLimit!=limit||earlierResponses.size!=responses||
                ui.onAllNodes(hasTestTag(tag) and hasText("正在加载更早消息…")).fetchSemanticsNodes().isNotEmpty())return
            assertNull("An actual earlier failure must not be retried by the examiner",session.error)
        }
        throw AssertionError("No earlier action accepted within the original15-second readiness budget")
    }
    @After fun preserve() {
        if(!::session.isInitialized)return
        runCatching { File(folder,"final-state.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("selectedThread",session.selectedThread).put("messages",session.messages.size).put("limit",session.visibleMessageLimit).put("historyLoading",session.historyLoading).put("error",session.error).put("historyRevision",page()?.historyRevision).put("hasHistoryCursor",session.historyCursor!=null).put("newOperations",session.operationRecords.size).toString(2)) }
        runCatching { File(folder,"semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString().take(262144)) }
        runCatching { if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==instrumentation.targetContext.packageName)shot("final-state") }
        main { radioMonitor?.stop();session.leaveForeground() };NativeReadAcceptanceTiming.configure(instrumentation.targetContext,Intent())
    }
    private fun actualRadioRecovery(draft: String) {
        require(config.getString("transport")=="physical-lan")
        val signals=java.util.concurrent.CopyOnWriteArrayList<String>();val ids=session.messages.map { it.id }.toSet();val key=session.targetKey;val limit=session.visibleMessageLimit
        main {
            radioMonitor=NativeReadNetworkMonitor(instrumentation.targetContext) { signature,baseline ->
                signals.add(signature);session.observeNetworkReadPath(signature,baseline)
            }.also { it.start() }
            assertTrue(radioMonitor!!.observing)
        }
        fun phase(name: String) { File(folder,"radio-$name.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",session.operationRecords.size).toString()) }
        phase("ready")
        ui.waitUntil(60_000) { !session.selectedClient.online&&!session.historyLoading }
        assertEquals(key,session.targetKey);assertEquals(draft,session.text);assertEquals(limit,session.visibleMessageLimit)
        assertTrue(session.messages.map { it.id }.containsAll(ids));assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("conversation-sync-banner").assertExists();shot("actual-wifi-loss-cache")
        phase("offline")
        ui.waitUntil(90_000) { session.selectedClient.online&&!session.historyLoading }
        assertEquals(key,session.targetKey);assertEquals(draft,session.text);assertEquals(limit,session.visibleMessageLimit)
        assertTrue(session.messages.map { it.id }.containsAll(ids));assertTrue(session.operationRecords.isEmpty())
        assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint)
        assertTrue("Real platform path signature must change",signals.distinct().size>=2)
        shot("actual-wifi-restored-cache")
        File(folder,"radio-proof.json").writeText(JSONObject().put("run",config.getString("run")).put("actualPlatformCallbacks",signals.size).put("distinctPlatformPaths",signals.distinct().size).put("sameTarget",true).put("originalExpandedIdsPreserved",true).put("draftPreserved",true).put("newOperations",0).put("automaticReadRecovery",true).toString(2))
        main { radioMonitor?.stop();radioMonitor=null }
    }
    @Test fun realLatestEarlierCompactionAndExpandedScrollSurviveFreshRead() {
        val run=config.getString("run");require(run.matches(Regex("[a-f0-9-]{36}")));val namespace=config.getString("namespace");require(namespace.matches(Regex("large-owner-[a-f0-9-]{36}")))
        storage=NativeSessionStorage(instrumentation.targetContext,namespace);assertNull("Fresh independent namespace only",storage.metadata())
        NativeReadAcceptanceTiming.configure(instrumentation.targetContext,Intent().putExtra(NativeReadAcceptanceTiming.ENABLED,true).putExtra(NativeReadAcceptanceTiming.HOST,config.getString("hostId")).putExtra(NativeReadAcceptanceTiming.RUN,run));assertNotNull(NativeReadAcceptanceTiming.capture())
        val native=NativeHostReads { config.getString("token") }
        val observer=object: HostReads by native {
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val incoming=native.history(client,threadId,cursor)
                if(cursor!=null)earlierResponses.add(EarlierResponse(incoming.hostId,incoming.threadId,incoming.windowed,incoming.historyWindowKind,incoming.messages.map { it.id }))
                return incoming
            }
        }
        session=MobileSession(storage,reader=observer);ui.setContent { AgentHubApp(session) };main {
            session.resetAcceptance(reader=observer)
            if(config.optString("transport")=="physical-lan") {
                require(android.os.Build.VERSION.SDK_INT==33&&java.net.URI(config.getString("endpoint")).scheme=="https")
                session.connectCodexQR(config.getString("pairing"),"原桌面局域网只读验收")
                assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint)
                assertEquals(config.getString("endpoint"),session.selectedClient.endpoint)
            } else session.connect("原桌面大历史只读验收",config.getString("endpoint"),config.getString("token"))
        }
        ui.waitUntil(60_000) { !session.loading&&session.selectedClient.id==config.getString("hostId")&&session.threads.any { it.id==config.getString("threadId") } }
        main { session.selectThread(session.threads.single { it.id==config.getString("threadId") }) }
        ui.waitUntil(60_000) { !session.historyLoading&&session.selectedThread==config.getString("threadId")&&session.messages.isNotEmpty() }
        assertTrue(page()!!.windowed);assertEquals("latest",page()!!.historyWindowKind);if(config.getBoolean("historyRevisionExpected"))assertNotNull(page()!!.historyRevision) else assertNull("Current versioned legacy Host does not supply optional revision",page()!!.historyRevision);assertNotNull(session.historyCursor);assertEquals(20,session.visibleMessageLimit)
        val firstCount=session.messages.size;val initialIds=session.messages.map { it.id };assertEquals(initialIds.size,initialIds.distinct().size)
        val draft="LARGE_OWNER_LOCAL_DRAFT_"+run.take(8);ui.onNodeWithTag("message-input").performTextReplacement(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
        tapEarlier("history-local-more");ui.waitUntil(10_000) { session.visibleMessageLimit==80 };assertEquals(draft,session.text)
        val known=config.getJSONArray("compactionIds").strings();var earlierPages=0;var loops=0
        while(earlierPages<1||session.messages.none { it.id in known&&it.kind=="contextCompaction"&&it.status=="completed" }) {
            check(++loops<=40) { "Exact original compaction not reached within bounded pagination" }
            if(session.timeline.hasEarlierMessages) {
                val prior=session.visibleMessageLimit;tapEarlier("history-local-more");ui.waitUntil(10_000) { session.visibleMessageLimit==prior+60 }
            } else {
                assertNotNull("Actual earlier cursor required",session.historyCursor);val priorIds=session.messages.map { it.id }.toSet();val priorCursor=session.historyCursor;val priorResponses=earlierResponses.size
                tapEarlier("history-more")
                ui.waitUntil(60_000) { !session.historyLoading&&session.messages.size>priorIds.size }
                assertNull(session.error);assertTrue(session.messages.map { it.id }.containsAll(priorIds));assertNotEquals(priorCursor,session.historyCursor);assertEquals(priorResponses+1,earlierResponses.size);val response=earlierResponses.last();assertTrue(response.windowed);assertEquals("earlier",response.kind);assertEquals(config.getString("hostId"),response.host);assertEquals(config.getString("threadId"),response.thread);assertTrue(session.messages.map { it.id }.containsAll(response.ids));assertTrue(response.ids.any { it !in priorIds });earlierPages++
            }
            assertEquals(session.messages.size,session.messages.map { it.id }.distinct().size);assertEquals(draft,session.text);assertEquals(config.getString("threadId"),session.selectedThread);assertTrue(session.operationRecords.isEmpty())
        }
        val compact=session.messages.last { it.id in known&&it.kind=="contextCompaction"&&it.status=="completed" };val tag="compaction-${compact.id}"
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals("已压缩上下文");shot("actual-earlier-compaction")
        val beforeTop=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value;val beforeScroll=ui.onNodeWithTag("message-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value();val limit=session.visibleMessageLimit;val expandedIds=session.messages.map { it.id }.toSet();val target=session.targetKey
        ui.waitUntil(45_000) { !session.historyLoading };main { session.loadHistory() };ui.waitUntil(60_000) { !session.historyLoading };ui.waitForIdle()
        assertEquals(target,session.targetKey);assertEquals(limit,session.visibleMessageLimit);assertEquals(draft,session.text);assertTrue(session.messages.map { it.id }.containsAll(expandedIds));assertEquals(session.messages.size,session.messages.map { it.id }.distinct().size)
        ui.onNodeWithTag(tag).assertIsDisplayed();val afterTop=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value;val afterScroll=ui.onNodeWithTag("message-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value();assertTrue("Original visible compaction anchor moved after latest GET",abs(afterTop-beforeTop)<=2f);shot("compaction-anchor-after-latest")
        val expectedIds=session.messages.map { it.id }.toSet();runBlocking(Dispatchers.IO) { withTimeout(45_000) { while(true) { val cached=NativeHistoryCache(storage).read(target);if(cached!=null&&cached.messages.map { it.id }.containsAll(expectedIds)&&cached.messages.any { it.id==compact.id&&it.kind=="contextCompaction" })break;delay(200) } } }
        if(config.optBoolean("radioCycle"))actualRadioRecovery(draft)
        assertTrue(session.operationRecords.isEmpty());File(folder,"native-proof.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("firstMessages",firstCount).put("finalMessages",session.messages.size).put("actualEarlierPages",earlierPages).put("nativeEarlierWireResponses",JSONArray(earlierResponses.map { JSONObject().put("windowed",it.windowed).put("kind",it.kind).put("messages",it.ids.size) })).put("historyRevisionPresent",page()?.historyRevision!=null).put("initialLimit",20).put("expandedLimit",limit).put("compactionId",compact.id).put("anchorTopBefore",beforeTop).put("anchorTopAfter",afterTop).put("scrollBefore",beforeScroll).put("scrollAfter",afterScroll).put("allExpandedIdsPreserved",true).put("diskCacheIdsPreserved",true).put("draftPreserved",true).put("newOperations",0).toString(2))
    }
}
