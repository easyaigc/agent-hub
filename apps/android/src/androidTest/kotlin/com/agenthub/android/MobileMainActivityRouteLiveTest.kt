package com.agenthub.android

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real foreground discovery switches an advertised route; no injected Session or reader. */
@RunWith(AndroidJUnit4::class)
class MobileMainActivityRouteLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val phase get()=InstrumentationRegistry.getArguments().getString("nativeMainActivityRoutePhase") ?: "route"
    private val moments=JSONArray()
    private fun mark(event: String,time: Long=android.os.SystemClock.uptimeMillis()) { if(moments.length()<32)moments.put(JSONObject().put("event",event).put("uptimeMS",time)) }
    @get:Rule(order=0) val guard=object: ExternalResource() {
        override fun before() {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance")
            require(InstrumentationRegistry.getArguments().getString("nativeMainActivityRoute")=="1")
            require(phase in listOf("route","cold","inspect","timing","radio"))
            if(phase=="timing") { mark("process-start-metadata",android.os.Process.getStartUptimeMillis());mark("native-observer-ready") }
        }
    }
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=3) val limit=org.junit.rules.Timeout.seconds(180)
    private val context get()=instrumentation.targetContext
    private val config by lazy { JSONObject(File(context.filesDir,"main-activity-route-live.json").readText()) }
    private val folder by lazy { File(context.filesDir,"acceptance/P2-main-route/"+config.getString("run")).apply { mkdirs() } }
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle();assertEquals(BuildConfig.APPLICATION_ID,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun expandNativeWindow() {
        val initial=session.visibleMessageLimit;val deadline=android.os.SystemClock.uptimeMillis()+15_000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
            ui.waitUntil((deadline-android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) { !session.historyLoading&&ui.onAllNodes(hasTestTag("history-local-more") and isEnabled()).fetchSemanticsNodes().size==1 }
            ui.onNodeWithTag("history-local-more").performClick();ui.waitForIdle()
            if(session.visibleMessageLimit!=initial)return
            assertNull("An actual earlier failure is not retried",session.error)
        }
        throw AssertionError("No local expansion accepted within the original15-second readiness budget")
    }
    @Test fun sameMachineAdvertisedRouteRecoversWithoutRePairOrInput() {
        val run=config.getString("run");require(run.matches(Regex("[a-f0-9-]{36}")))
        assertTrue(ui.activity.readNetworkMonitor.observing)
        assertTrue("Normal native discovery must be permitted",session.discovery?.permitted()==true)
        if(phase=="radio") { physicalRadioRecovery();return }
        if(phase=="timing") { observedReadTiming();return }
        if(phase=="inspect") {
            ui.waitUntil(45_000) { !session.restoring&&!session.historyLoading&&session.selectedClient.id==config.getString("hostId") }
            preserve();shot("inspect-current-native-reading-controls")
            File(folder,"inspect-proof.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("readonlyInspection",true).put("newOperations",session.operationRecords.size).toString(2))
            return
        }
        if(phase=="cold") { coldRevisit();return }
        ui.waitUntil(30_000) { !session.restoring }
        main { session.resetAcceptance();session.connectCodexQR(config.getString("pairing"),"MainActivity 原地址切换只读验收") }
        ui.waitUntil(45_000) { !session.loading&&session.selectedClient.id==config.getString("hostId")&&session.threads.any { it.id==config.getString("threadId") } }
        main { session.selectThread(session.threads.single { it.id==config.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.messages.size>=80 }
        assertEquals(config.getString("endpoint"),session.selectedClient.endpoint)
        assertTrue(session.selectedClient.codexDirect!!.routes.any { it.endpoint==config.getString("alternateEndpoint") })
        val target=session.targetKey;val pin=session.selectedClient.codexDirect!!.fingerprint
        val storage=NativeSessionStorage(context);val token=storage.token(session.selectedClient.id)
        val draft="MAIN_ROUTE_LOCAL_DRAFT_"+run.take(8)
        ui.onNodeWithTag("message-input").performTextReplacement(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
        expandNativeWindow()
        ui.waitUntil(10_000) { session.visibleMessageLimit==80 }
        val sample=session.messages.takeLast(60).first { it.role=="assistant"&&it.text.isNotBlank()&&(it.kind==null||it.kind in listOf("message","agentMessage")) }
        val tag="message-${sample.id}";ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed()
        val beforeTop=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
        runBlocking(Dispatchers.IO) { withTimeout(20_000) { while(storage.metadata()?.optJSONObject("drafts")?.optString(target)!=draft||NativeHistoryCache(storage).read(target)?.messages?.none { it.id==sample.id }!=false)delay(100) } }
        assertTrue(session.operationRecords.isEmpty());shot("original-primary-route-expanded-anchor")
        File(folder,"route-ready.json").writeText(JSONObject().put("run",run).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2))
        // Parent only closes the owned primary listener and enables its already advertised peer.
        // No explicit connect, scan, retry or network callback is injected here.
        ui.waitUntil(45_000) { session.selectedClient.endpoint==config.getString("alternateEndpoint")&&session.selectedClient.online&&!session.historyLoading&&!session.loading }
        assertEquals(target,session.targetKey);assertEquals(pin,session.selectedClient.codexDirect!!.fingerprint)
        assertEquals(draft,session.text);assertEquals(token,storage.token(session.selectedClient.id));assertEquals(80,session.visibleMessageLimit)
        assertTrue(session.messages.any { it.id==sample.id });assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("message-input").assertTextEquals(draft);ui.onNodeWithTag(tag).assertIsDisplayed()
        val afterTop=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
        assertTrue("Original visible anchor moved after automatic route switch",abs(afterTop-beforeTop)<=2f)
        shot("automatic-alternate-route-original-anchor")
        val cached=runBlocking(Dispatchers.IO) { withTimeout(20_000) {
            var result: MobileHistory?=null
            while(result==null) {
                val page=NativeHistoryCache(storage).read(target)
                if(page?.messages?.any { it.id==sample.id }==true&&storage.metadata()?.optJSONObject("drafts")?.optString(target)==draft)result=page else delay(100)
            }
            result
        } }
        val coldSample=cached.messages.takeLast(20).last { it.role=="assistant"&&it.text.isNotBlank()&&(it.kind==null||it.kind in listOf("message","agentMessage")) }
        val reference=File(folder,"route-reference.json");require(!reference.exists())
        reference.writeText(JSONObject().put("run",run).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("targetKey",target).put("pin",pin).put("draft",draft).put("sampleId",coldSample.id).put("messageIds",JSONArray(cached.messages.map { it.id })).toString(2))
        File(folder,"route-proof.json").writeText(JSONObject().put("run",run).put("productionActivity",ui.activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("automaticForegroundDiscovery",true).put("originalTargetPinCredentialDraftAndSample",true).put("expandedWindow",80).put("anchorDeltaDp",abs(afterTop-beforeTop)).put("newOperations",0).toString(2))
    }
    private fun coldRevisit() {
        val original=JSONObject(File(folder,"route-reference.json").readText())
        assertEquals(config.getString("run"),original.getString("run"));assertNotEquals(original.getInt("pid"),android.os.Process.myPid())
        val ids=original.getJSONArray("messageIds").let { array->(0 until array.length()).map(array::getString).toSet() }
        val target=original.getString("targetKey");val draft=original.getString("draft");val sample=original.getString("sampleId")
        ui.waitUntil(45_000) { !session.restoring&&session.selectedClient.id==original.getString("hostId")&&session.selectedThread==original.getString("threadId")&&session.messages.map { it.id }.containsAll(ids)&&session.text==draft }
        assertEquals(target,session.targetKey);assertEquals(config.getString("alternateEndpoint"),session.selectedClient.endpoint)
        assertEquals(original.getString("pin"),session.selectedClient.codexDirect!!.fingerprint)
        assertEquals(config.getString("token"),NativeSessionStorage(context).token(session.selectedClient.id))
        assertEquals(20,session.visibleMessageLimit);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("message-input").assertTextEquals(draft)
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-$sample"));ui.onNodeWithTag("message-$sample").assertIsDisplayed()
        shot("cold-persisted-alternate-cache-before-data-success")
        File(folder,"cold-route-cache-visible.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2))
        ui.waitUntil(90_000) { session.selectedClient.online&&!session.loading&&!session.historyLoading }
        assertEquals(target,session.targetKey);assertEquals(config.getString("alternateEndpoint"),session.selectedClient.endpoint)
        assertEquals(original.getString("pin"),session.selectedClient.codexDirect!!.fingerprint)
        assertEquals(draft,session.text);assertTrue(session.messages.any { it.id==sample });assertTrue(session.operationRecords.isEmpty())
        shot("cold-persisted-alternate-automatic-read-recovered")
        File(folder,"cold-proof.json").writeText(JSONObject().put("run",config.getString("run")).put("productionActivity",ui.activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("differentRouteProcess",true).put("persistedAlternateTargetPinCredentialCacheDraft",true).put("initialWindow",20).put("automaticForegroundReadRecovery",true).put("newOperations",0).toString(2))
    }
    private fun observedReadTiming() {
        val original=JSONObject(File(folder,"route-reference.json").readText());assertEquals(config.getString("run"),original.getString("run"));assertNotEquals(original.getInt("pid"),android.os.Process.myPid())
        val ids=original.getJSONArray("messageIds").let { array->(0 until array.length()).map(array::getString).toSet() }
        val target=original.getString("targetKey");val draft=original.getString("draft");val sample=original.getString("sampleId")
        mark("native-test-body-ready")
        ui.waitUntil(45_000) { !session.restoring&&session.selectedClient.id==original.getString("hostId")&&session.selectedThread==original.getString("threadId")&&session.messages.map { it.id }.containsAll(ids)&&session.text==draft }
        mark("original-cache-state-observed")
        assertEquals(target,session.targetKey);assertEquals(config.getString("alternateEndpoint"),session.selectedClient.endpoint)
        assertEquals(original.getString("pin"),session.selectedClient.codexDirect!!.fingerprint);assertEquals(20,session.visibleMessageLimit)
        assertEquals(config.getString("token"),NativeSessionStorage(context).token(session.selectedClient.id));assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("message-input").assertTextEquals(draft)
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-$sample"));ui.onNodeWithTag("message-$sample").assertIsDisplayed()
        mark("cached-body-and-draft-ui-observed")
        fun editAndRestore() {
            ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(draft+"_LOCAL_INPUT_CHECK")
            ui.onNodeWithTag("message-input").assertTextEquals(draft+"_LOCAL_INPUT_CHECK").performTextReplacement(draft)
            ui.onNodeWithTag("message-input").assertTextEquals(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
            assertTrue(session.operationRecords.isEmpty())
        }
        editAndRestore();mark("cached-input-edit-ui-observed");shot("timing-original-cache-and-editable-draft")
        fun signal(name: String) { File(folder,name).writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2)) }
        signal("timing-cache-input-visible.json")
        ui.waitUntil(45_000) { session.selectedClient.online&&session.historyLoading }
        assertEquals(target,session.targetKey);editAndRestore();mark("input-during-pending-history-ui-observed")
        signal("timing-pending-history-input-visible.json")
        val expected=config.getString("newVisibleText");val released=File(folder,"timing-history-window-ended.json")
        var observedNew=false
        fun observeNewContent() {
            if(observedNew)return
            val message=session.messages.firstOrNull { it.id !in ids&&it.role=="assistant"&&it.text.trim()==expected&&(it.kind==null||it.kind in listOf("message","agentMessage")) } ?: return
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-${message.id}"));ui.onNodeWithTag("message-${message.id}").assertIsDisplayed()
            mark("new-original-content-ui-observed");observedNew=true
        }
        val deadline=android.os.SystemClock.uptimeMillis()+90_000
        while(!released.exists()&&android.os.SystemClock.uptimeMillis()<deadline) { observeNewContent();Thread.sleep(50) }
        assertTrue("Bounded independent actual history timeout window",released.exists())
        val marker=JSONObject(released.readText());assertEquals(config.getString("run"),marker.getString("run"));assertTrue(marker.getBoolean("actualHeldHistoryEnded"))
        val fresh=File(folder,"timing-fresh-history-confirmed.json")
        ui.waitUntil(60_000) { observeNewContent();fresh.exists()&&session.selectedClient.online&&!session.loading&&!session.historyLoading&&observedNew }
        val freshMarker=JSONObject(fresh.readText());assertEquals(config.getString("run"),freshMarker.getString("run"));assertTrue(freshMarker.getBoolean("freshHistory200"))
        mark("fresh-original-read-ui-observed")
        assertEquals(target,session.targetKey);assertEquals(config.getString("alternateEndpoint"),session.selectedClient.endpoint)
        assertEquals(original.getString("pin"),session.selectedClient.codexDirect!!.fingerprint);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("message-input").assertTextEquals(draft);shot("timing-original-new-content-read-recovered")
        File(folder,"timing-proof.json").writeText(JSONObject().put("run",config.getString("run")).put("productionActivity",ui.activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("differentRouteProcess",true).put("originalTargetPinCredentialCacheDraft",true).put("nativeLocalInputEditedAndRestored",true).put("newOriginalContentDisplayed",observedNew).put("moments",moments).put("newOperations",0).toString(2))
    }
    private fun physicalRadioRecovery() {
        require(InstrumentationRegistry.getArguments().getString("nativeMainActivityPhysicalRadio")=="1")
        require(config.getString("transport")=="physical-lan")
        require(!android.os.Build.FINGERPRINT.startsWith("generic")&&android.os.Build.HARDWARE !in listOf("goldfish","ranchu"))
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        fun wifi()=connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
        val available=AtomicInteger();val lost=AtomicInteger()
        val observer=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.incrementAndGet() }
            override fun onLost(network: Network) { lost.incrementAndGet() }
        }
        // Passive platform observation; never calls the production monitor/session callback.
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),observer)
        try {
            ui.waitUntil(45_000) { !session.restoring&&session.selectedClient.id==config.getString("hostId")&&session.selectedThread==config.getString("threadId")&&session.selectedClient.online&&!session.historyLoading&&!session.loading }
            assertTrue(wifi());assertTrue(ui.activity.readNetworkMonitor.observing)
            val target=session.targetKey;val pin=session.selectedClient.codexDirect!!.fingerprint
            val storage=NativeSessionStorage(context);val token=storage.token(session.selectedClient.id)
            val draft="MAIN_RADIO_LOCAL_DRAFT_"+config.getString("run").take(8)
            ui.onNodeWithTag("message-input").performTextReplacement(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
            expandNativeWindow();ui.waitUntil(10_000) { session.visibleMessageLimit==80 }
            val sample=session.messages.takeLast(40).first { it.role=="assistant"&&it.text.isNotBlank()&&(it.kind==null||it.kind in listOf("message","agentMessage")) }
            val tag="message-${sample.id}";ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed()
            val top=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
            runBlocking(Dispatchers.IO) { withTimeout(20_000) { while(storage.metadata()?.optJSONObject("drafts")?.optString(target)!=draft||NativeHistoryCache(storage).read(target)?.messages?.none { it.id==sample.id }!=false)delay(100) } }
            assertTrue(session.operationRecords.isEmpty());shot("physical-radio-original-expanded-anchor")
            fun signal(name: String) { File(folder,name).writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2)) }
            signal("radio-ready.json")
            ui.waitUntil(45_000) { !wifi()&&!session.selectedClient.online }
            assertTrue(lost.get()>0)
            ui.onNodeWithTag("message-input").assertTextEquals(draft);ui.onNodeWithTag(tag).assertIsDisplayed()
            shot("physical-radio-offline-original-cache");signal("radio-offline-visible.json")
            val restored=File(folder,"radio-wifi-restored.json");val deadline=android.os.SystemClock.uptimeMillis()+60_000
            val offlineStart=android.os.SystemClock.uptimeMillis();var offlineSamples=0
            while(!restored.exists()&&!wifi()&&android.os.SystemClock.uptimeMillis()<deadline) {
                assertFalse(session.selectedClient.online);assertEquals(target,session.targetKey);assertEquals(pin,session.selectedClient.codexDirect!!.fingerprint)
                assertEquals(draft,session.text);assertEquals(80,session.visibleMessageLimit);assertTrue(session.messages.any { it.id==sample.id });assertTrue(session.operationRecords.isEmpty())
                offlineSamples++;Thread.sleep(250)
            }
            val offlineObservedMS=android.os.SystemClock.uptimeMillis()-offlineStart
            ui.waitUntil(10_000) { restored.exists() }
            val restoreMarker=JSONObject(restored.readText());assertEquals(config.getString("run"),restoreMarker.getString("run"));assertTrue(restoreMarker.getBoolean("originalWifiEnabledRestored"))
            val fresh=File(folder,"radio-fresh-history-confirmed.json")
            ui.waitUntil(60_000) { wifi()&&session.selectedClient.online&&!session.loading&&!session.historyLoading&&fresh.exists() }
            val freshMarker=JSONObject(fresh.readText());assertEquals(config.getString("run"),freshMarker.getString("run"));assertTrue(freshMarker.getBoolean("freshHistory200"))
            assertEquals(target,session.targetKey);assertEquals(pin,session.selectedClient.codexDirect!!.fingerprint);assertEquals(token,storage.token(session.selectedClient.id))
            assertEquals(draft,session.text);assertEquals(80,session.visibleMessageLimit);assertTrue(session.operationRecords.isEmpty())
            ui.onNodeWithTag("message-input").assertTextEquals(draft);ui.onNodeWithTag(tag).assertIsDisplayed()
            val delta=abs(ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value-top)
            assertTrue("Original expanded visible anchor moved after radio recovery",delta<=2f)
            assertTrue(available.get()>=2);shot("physical-radio-automatic-read-original-anchor")
            File(folder,"radio-proof.json").writeText(JSONObject().put("run",config.getString("run")).put("productionActivity",ui.activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("passiveWifiAvailableCallbacks",available.get()).put("passiveWifiLostCallbacks",lost.get()).put("offlineObservedMS",offlineObservedMS).put("offlineInvariantSamples",offlineSamples).put("sameTargetPinCredentialDraftSample",true).put("expandedWindow",80).put("anchorDeltaDp",delta).put("productionAutomaticReadRecovery",true).put("newOperations",0).toString(2))
        } finally { connectivity.unregisterNetworkCallback(observer) }
    }
    @After fun preserve() { runCatching {
        File(folder,"$phase-final-state.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("endpoint",session.selectedClient.endpoint).put("online",session.selectedClient.online).put("messages",session.messages.size).put("visibleMessageLimit",session.visibleMessageLimit).put("timelineRows",session.timeline.rows.size).put("localEarlier",session.timeline.hasEarlierMessages).put("serverCursor",session.historyCursor!=null).put("historyLoading",session.historyLoading).put("error",session.error).put("operations",session.operationRecords.size).toString(2))
        File(folder,"$phase-semantics.txt").writeText(ui.onRoot().printToString().take(262144))
        if(moments.length()>0)File(folder,"$phase-native-moments.json").writeText(JSONObject().put("clock","android.os.SystemClock.uptimeMillis").put("scope","positive native UI assertion observations; includes instrumentation/bootstrap and explicit scroll/edit overhead, not first physical pixels or release startup/model-completion lag").put("moments",moments).toString(2))
        shot("$phase-final-native-state")
    } }
}
