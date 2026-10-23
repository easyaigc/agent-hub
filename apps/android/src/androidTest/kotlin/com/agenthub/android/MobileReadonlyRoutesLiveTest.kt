package com.agenthub.android

import android.graphics.Bitmap
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real owner reads and fork-option menu only. Never clicks a fork or send action. */
@RunWith(AndroidJUnit4::class)
class MobileReadonlyRoutesLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"readonly-routes-live.json").readText()) }
    private val phase get()=InstrumentationRegistry.getArguments().getString("nativeReadonlyRoutesPhase")!!
    private val folder by lazy { File(instrumentation.targetContext.filesDir,"acceptance/P2-readonly-routes/"+config.getString("run")).apply { mkdirs() } }
    @get:Rule(order=0) val guard=object: ExternalResource() {
        override fun before() {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance")
            require(InstrumentationRegistry.getArguments().getString("nativeReadonlyRoutes")=="1")
            require(phase in listOf("warm","cold"))
            require(config.getString("run").matches(Regex("[a-f0-9-]{36}")))
        }
    }
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=3) val limit=org.junit.rules.Timeout.seconds(240)
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle();instrumentation.waitForIdleSync()
        val frames=CountDownLatch(1)
        ui.runOnUiThread { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue("Actual native frame callbacks before capture",frames.await(5,TimeUnit.SECONDS))
        instrumentation.uiAutomation.waitForIdle(300,5_000)
        assertEquals(BuildConfig.APPLICATION_ID,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$phase-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun showOriginalFinal() {
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(config.getString("marker")))
        ui.onAllNodesWithText(config.getString("marker")).onLast().assertIsDisplayed()
    }
    @Test fun actualDefaultActivityReadsNamedRoutesAndRestoresCacheWithoutWrites() {
        activity=ui.activity;assertTrue(activity.readNetworkMonitor.observing)
        val reference=File(folder,"warm-reference.json")
        if(phase=="warm") {
            require(!reference.exists());ui.waitUntil(30_000) { !session.restoring }
            main { session.resetAcceptance();session.connectCodexQR(config.getString("pairing"),"原桌面辅助只读路由验收") }
            ui.waitUntil(45_000) { !session.loading&&session.selectedClient.id==config.getString("hostId")&&session.threads.any { it.id==config.getString("threadId") } }
            main { session.selectThread(session.threads.single { it.id==config.getString("threadId") }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.messages.any { it.text==config.getString("marker") }&&session.runtime?.canControl==true }
            val draft="READONLY_ROUTE_LOCAL_DRAFT_"+config.getString("run").take(8)
            ui.onNodeWithTag("message-input").performTextReplacement(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
            val target=session.targetKey;val storage=NativeSessionStorage(instrumentation.targetContext)
            runBlocking(Dispatchers.IO) { withTimeout(20_000) { while(storage.metadata()?.optJSONObject("drafts")?.optString(target)!=draft||NativeHistoryCache(storage).read(target)?.messages?.none { it.text==config.getString("marker") }!=false)delay(100) } }
            showOriginalFinal();shot("default-cache-and-original-final")
            reference.writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("targetKey",target).put("draft",draft).put("messageIds",JSONArray(session.messages.map { it.id })).toString(2))
        } else {
            val original=JSONObject(reference.readText());assertEquals(config.getString("run"),original.getString("run"));assertNotEquals(original.getInt("pid"),android.os.Process.myPid())
            val ids=original.getJSONArray("messageIds").strings().toSet();val draft=original.getString("draft")
            ui.waitUntil(60_000) { !session.restoring&&session.selectedClient.id==config.getString("hostId")&&session.selectedThread==config.getString("threadId")&&session.messages.map { it.id }.containsAll(ids)&&session.text==draft }
            assertEquals(original.getString("targetKey"),session.targetKey);assertFalse(session.canCompose);assertTrue(session.operationRecords.isEmpty())
            ui.onNodeWithTag("message-input").assertTextEquals(draft);showOriginalFinal();shot("default-cache-before-data")
            File(folder,"cold-cache-visible.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("cachedIds",ids.size).put("newOperations",0).toString(2))
            val ack=File(folder,"fresh-history-ack.json")
            ui.waitUntil(60_000) { ack.exists()&&session.selectedClient.online&&!session.loading&&!session.historyLoading&&session.runtime?.canControl==true&&session.canCompose }
            val receipt=JSONObject(ack.readText());assertEquals(config.getString("run"),receipt.getString("run"));assertTrue(receipt.getBoolean("actualHistory200"))
            assertEquals(original.getString("targetKey"),session.targetKey);assertEquals(draft,session.text);assertTrue(session.messages.map { it.id }.containsAll(ids))
            ui.onNodeWithTag("send-message").assertIsEnabled()
            showOriginalFinal();shot("default-cold-recovered")
        }
        assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint)
        assertTrue(session.messages.any { it.text==config.getString("marker")&&it.runId==config.getString("turnId") })
        ui.onAllNodesWithTag("fork-reply").onLast().performClick()
        ui.waitUntil(15_000) { session.forkReason(config.getString("turnId"),"same-directory")==null }
        ui.onNodeWithTag("fork-same-directory").assertIsDisplayed().assertIsEnabled()
        assertTrue(session.operationRecords.isEmpty());shot("readonly-fork-options-menu")
        File(folder,"$phase-proof.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("productionActivity",activity.javaClass.name).put("defaultStore",true).put("originalFinalVisible",true).put("forkChoiceEnabledWithoutClick",true).put("draft",session.text).put("newOperations",0).toString(2))
    }
    @After fun preserve() {
        if(!::activity.isInitialized)return
        runCatching { File(folder,"$phase-state.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("targetKey",session.targetKey).put("historyLoading",session.historyLoading).put("canCompose",session.canCompose).put("error",session.error).put("draft",session.text).put("operations",JSONArray(session.operationRecords.map { it.kind+":"+it.phase })).toString(2)) }
        main { session.leaveForeground() }
    }
}
