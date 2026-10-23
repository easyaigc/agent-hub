package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.FailureCopy
import com.agenthub.core.MobileConnectionFailureKind
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

/** Actual production Activity/default store; cold phase never injects a Session, reader or cache. */
@RunWith(AndroidJUnit4::class)
class MobileMainActivityReadLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"main-activity-read-live.json").readText()) }
    private val phase get()=InstrumentationRegistry.getArguments().getString("nativeMainActivityPhase")!!
    private val folder by lazy { File(instrumentation.targetContext.filesDir,"acceptance/P2-main-activity/"+config.getString("run")).apply { mkdirs() } }
    @get:Rule(order=0) val guard=object: ExternalResource() {
        override fun before() {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance")
            require(InstrumentationRegistry.getArguments().getString("nativeMainActivityRead")=="1")
            require(phase in listOf("warm","cold","certificate"))
        }
    }
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=3) val limit=org.junit.rules.Timeout.seconds(300)
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle();assertEquals(BuildConfig.APPLICATION_ID,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun showSample(id: String) {
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-$id"))
        ui.onNodeWithTag("message-$id").assertIsDisplayed()
    }
    private fun certificateRefusal(original: JSONObject) {
        val draft=original.getString("draft");val target=original.getString("targetKey")
        ui.waitUntil(45_000) { session.error!=null&&!session.loading&&!session.historyLoading }
        ui.onNodeWithTag("session-error").assertTextEquals(FailureCopy.message(MobileConnectionFailureKind.CERTIFICATE))
        assertEquals(FailureCopy.connectionTitle,session.errorTitle)
        assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint)
        assertEquals(target,session.targetKey);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
        shot("paired-certificate-refused")
        ui.onNodeWithTag("dismiss-error").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.error==null&&ui.onAllNodes(hasTestTag("session-error")).fetchSemanticsNodes().isEmpty() }
        instrumentation.uiAutomation.waitForIdle(300,5_000)
        ui.onNodeWithTag("session-error").assertDoesNotExist()
        assertNull(session.error)
        ui.onNodeWithTag("message-input").assertTextEquals(draft);showSample(original.getString("sampleId"))
        shot("certificate-refusal-original-cache-visible")
        File(folder,"certificate-cache-visible.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2))
        val restored=File(folder,"certificate-server-restored.json")
        ui.waitUntil(45_000) { restored.exists() }
        val marker=JSONObject(restored.readText());assertEquals(config.getString("run"),marker.getString("run"));assertTrue(marker.getBoolean("originalCertificateRestored"))
        ui.waitUntil(15_000) { !session.historyLoading&&ui.onAllNodes(hasTestTag("conversation-sync-retry") and isEnabled()).fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("conversation-sync-retry").assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.selectedClient.online&&!session.historyLoading }
        assertEquals(target,session.targetKey);assertEquals(draft,session.text);assertTrue(session.messages.any { it.id==original.getString("sampleId") });assertTrue(session.operationRecords.isEmpty())
        assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint);shot("original-certificate-explicit-read-recovered")
        File(folder,"certificate-proof.json").writeText(JSONObject().put("productionActivity",activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("differentWarmProcess",true).put("originalPairedCertificateRejectedChange",true).put("sharedCertificateCopyVisible",true).put("cachedBodyAndDraftVisibleAfterConfirm",true).put("originalTargetAndPinRetained",true).put("explicitNativeRetryRecovered",true).put("newOperations",0).toString(2))
    }
    @After fun preserve() {
        if(::activity.isInitialized)runCatching {
            File(folder,"$phase-final-state.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("messages",session.messages.size).put("restoring",session.restoring).put("error",session.error).put("operations",session.operationRecords.size).toString(2))
        }
    }
    @Test fun productionDefaultCacheSurvivesColdProcessWithoutSuccessfulDataRead() {
        require(config.getString("run").matches(Regex("[a-f0-9-]{36}")))
        activity=ui.activity
        assertTrue(activity.readNetworkMonitor.observing)
        val storage=NativeSessionStorage(instrumentation.targetContext)
        val reference=File(folder,"warm-reference.json")
        if(phase=="warm") {
            require(!reference.exists())
            ui.waitUntil(30_000) { !session.restoring }
            main { session.resetAcceptance();session.connectCodexQR(config.getString("pairing"),"MainActivity 原桌面只读验收") }
            ui.waitUntil(45_000) { !session.loading&&session.selectedClient.id==config.getString("hostId")&&session.threads.any { it.id==config.getString("threadId") } }
            main { session.selectThread(session.threads.single { it.id==config.getString("threadId") }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.messages.isNotEmpty() }
            val draft="MAIN_ACTIVITY_LOCAL_DRAFT_"+config.getString("run").take(8)
            ui.onNodeWithTag("message-input").performTextReplacement(draft);androidx.test.espresso.Espresso.closeSoftKeyboard()
            val target=session.targetKey
            val cached=runBlocking(Dispatchers.IO) { withTimeout(45_000) {
                var result: MobileHistory?=null
                while(result==null) {
                    val metadata=storage.metadata();val page=NativeHistoryCache(storage).read(target)
                    if(metadata?.optJSONObject("drafts")?.optString(target)==draft&&page!=null&&page.messages.isNotEmpty())result=page else delay(100)
                }
                result
            } }
            val sample=cached.messages.takeLast(20).last { it.role=="assistant"&&it.text.isNotBlank()&&(it.kind==null||it.kind in listOf("message","agentMessage")) }
            showSample(sample.id);shot("warm-original-default-store")
            assertTrue(session.operationRecords.isEmpty())
            reference.writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("targetKey",target).put("draft",draft).put("sampleId",sample.id).put("messageIds",JSONArray(cached.messages.map { it.id })).toString(2))
            File(folder,"warm-proof.json").writeText(JSONObject().put("productionActivity",activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("cachedMessages",cached.messages.size).put("draftDurable",true).put("newOperations",0).toString(2))
        } else {
            val original=JSONObject(reference.readText());assertEquals(config.getString("run"),original.getString("run"));assertNotEquals(original.getInt("pid"),android.os.Process.myPid())
            val ids=original.getJSONArray("messageIds").strings().toSet();val draft=original.getString("draft")
            ui.waitUntil(60_000) { !session.restoring&&session.selectedClient.id==original.getString("hostId")&&session.selectedThread==original.getString("threadId")&&session.messages.map { it.id }.containsAll(ids)&&session.text==draft }
            assertEquals(original.getString("targetKey"),session.targetKey);assertTrue(session.operationRecords.isEmpty())
            if(phase=="certificate") { certificateRefusal(original);return }
            ui.onNodeWithTag("message-input").assertTextEquals(draft);showSample(original.getString("sampleId"));shot("cold-cache-before-successful-data")
            File(folder,"cold-cache-visible.json").writeText(JSONObject().put("run",config.getString("run")).put("pid",android.os.Process.myPid()).put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("newOperations",0).toString(2))
            ui.waitUntil(90_000) { session.selectedClient.online&&!session.historyLoading }
            assertEquals(original.getString("targetKey"),session.targetKey);assertEquals(draft,session.text);assertTrue(session.messages.any { it.id==original.getString("sampleId") });assertTrue(session.operationRecords.isEmpty())
            assertEquals(config.getString("fingerprint"),session.selectedClient.codexDirect?.fingerprint);shot("cold-automatic-network-recovered")
            File(folder,"cold-proof.json").writeText(JSONObject().put("productionActivity",activity.javaClass.name).put("defaultStore",true).put("pid",android.os.Process.myPid()).put("differentWarmProcess",true).put("originalIdsBeforeRecoveryPreserved",true).put("originalSampleAfterRecoveryPreserved",true).put("draftPreserved",true).put("sameTargetAndCertificate",true).put("automaticNetworkRecovery",true).put("newOperations",0).toString(2))
        }
    }
}
