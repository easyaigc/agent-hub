package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** One leased new input per invocation. Unknown or already consumed cases never send again. */
@RunWith(AndroidJUnit4::class)
class MobilePermissionLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(360)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-permission-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,body: JSONObject): JSONObject {
        require(route in listOf("claim-case","case-complete","preflight-complete","original-read-complete"))
        val uri=URI(fixture.getString("control"));require(uri.scheme=="http"&&uri.host=="127.0.0.1"&&uri.rawPath.isEmpty())
        val http=uri.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=body.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Independent permission witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun shot(name: String) {
        ui.waitForIdle();i.waitForIdleSync()
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val folder=File(i.targetContext.filesDir,"acceptance/P4-permission/$nonce").apply { mkdirs() }
        val image=checkNotNull(i.uiAutomation.takeScreenshot())
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally{image.recycle()}
    }
    private fun settings(permission: String) {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").assertIsEnabled().performClick()
        ui.selectNativeGeneration(fixture.getString("model"),fixture.getString("effort"),fixture.getString("speed"),permission)
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(GenerationSettings(fixture.getString("model"),fixture.getString("effort"),permission.takeUnless { it=="default" },fixture.getString("speed")),session.generationSettings)
    }
    private fun allowOriginalSharing(text: String) {
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        ui.waitUntil(10_000) { session.text==text&&session.decision.enabled }
        val target=session.targetKey
        val records=session.operationRecords.map { it.id }.toSet()
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"))
        assertFalse(session.aiSharing.permits(scope))
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { session.aiSharingRequest!=null }
        assertEquals(target,session.aiSharingRequest!!.targetKey)
        assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        assertEquals(text,session.text);assertEquals(records,session.operationRecords.map { it.id }.toSet())
        shot("sharing-confirmation-before-any-submission")
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertTrue(session.aiSharing.permits(scope))
        assertEquals(target,session.targetKey);assertEquals(text,session.text)
        assertEquals(records,session.operationRecords.map { it.id }.toSet())
        assertNull(session.transferStatus)
        shot("sharing-granted-with-original-unsent-draft")
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var resumed: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };resumed!=null }
        activity=checkNotNull(resumed)
    }
    private fun originals()=JSONArray(session.operationRecords.filter { it.intent.contains(nonce)&&it.kind in listOf("reply","creation") }.map {
        JSONObject().put("id",it.id).put("target",it.target).put("sourceKey",it.sourceKey).put("intentSha256",it.intentSha256)
    })
    /** Read-only continuation: no reset, editing, settings change, consent or send path. */
    @Test fun originalOwnerPermissionReadOnlyRecovery() {
        bind()
        require(fixture.getBoolean("readOnlyRecovery"))
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.selectedThread==fixture.getString("threadId")&&session.canCompose }
        assertEquals(fixture.getString("hostId"),session.workspace!!.hostId)
        assertTrue(session.text.isEmpty());assertTrue(session.attachments.isEmpty());assertFalse(session.running);assertNull(session.transferStatus)
        // Accepted inputs clear the editable draft; the actual runtime supplies its effective values.
        assertEquals(GenerationSettings(),session.generationSettings)
        ui.waitUntil(30_000) { session.runtime!=null }
        assertEquals(fixture.getString("model"),session.runtime!!.settings.model)
        assertEquals(fixture.getString("effort"),session.runtime!!.settings.effort)
        assertEquals(fixture.getString("speed"),session.runtime!!.settings.speed)
        assertEquals("full-access",session.runtime!!.settings.permissionMode)
        val expected=fixture.getJSONArray("originalOperationIds")
        val records=session.operationRecords.filter { it.intent.contains(nonce)&&it.kind in listOf("reply","creation") }
        assertEquals(4,expected.length());assertEquals(4,records.size)
        assertEquals((0 until expected.length()).map { expected.getString(it) }.toSet(),records.map { it.id }.toSet())
        val warm=JSONArray(File(i.targetContext.filesDir,"permission-warm-$nonce.json").readText());assertEquals(3,warm.length())
        for(n in 0 until warm.length()) {
            val old=warm.getJSONObject(n);val now=records.single { it.id==old.getString("id") }
            assertEquals(old.getString("target"),now.target);assertEquals(old.getString("sourceKey"),now.sourceKey);assertEquals(old.getString("intentSha256"),now.intentSha256)
        }
        val rows=fixture.getJSONArray("cases")
        for(n in 0 until rows.length()) {
            val marker=rows.getJSONObject(n).getString("marker")
            ui.waitUntil(30_000) { session.messages.any { it.role=="assistant"&&it.text.trim()==marker } }
            val record=records.single { it.intent.contains(marker) };assertTrue(record.submissionAttempted)
        }
        control("original-read-complete",JSONObject().put("threadId",session.selectedThread).put("pid",android.os.Process.myPid()).put("originals",JSONArray(records.map { JSONObject().put("id",it.id).put("target",it.target).put("sourceKey",it.sourceKey).put("intent",it.intent).put("intentSha256",it.intentSha256) })))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(rows.getJSONObject(3).getString("marker")))
        shot("original-four-readonly-recovery")
    }
    @Test fun originalOwnerPermissionCase() {
        bind()
        val index=InstrumentationRegistry.getArguments().getString("permissionCase")!!.toInt()
        assertEquals(index,fixture.getInt("activeCase"));require(index in -1..3)
        if(index<=0) {
            ui.runOnIdle { session.resetAcceptance() }
            main { session.connect("Android 权限 DEV",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
            ui.runOnIdle { session.newChat(fixture.getString("projectId")) }
        } else {
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.selectedThread==fixture.getString("threadId")&&session.canCompose }
        }
        if(index==3) {
            assertNotEquals("A distinct Android process must restore disk state",fixture.getInt("warmProcessId"),android.os.Process.myPid())
            assertEquals(fixture.getString("coldDraft"),session.text)
            assertEquals(GenerationSettings(fixture.getString("model"),fixture.getString("effort"),"read-only",fixture.getString("speed")),session.generationSettings)
            val before=JSONArray(File(i.targetContext.filesDir,"permission-warm-$nonce.json").readText())
            for(n in 0 until before.length()) {
                val old=before.getJSONObject(n);val now=session.operationRecords.single { it.id==old.getString("id") }
                assertEquals(old.getString("target"),now.target);assertEquals(old.getString("sourceKey"),now.sourceKey);assertEquals(old.getString("intentSha256"),now.intentSha256)
            }
            shot("cold-restored-originals-and-draft")
        }
        settings(if(index<0) "default" else fixture.getJSONArray("cases").getJSONObject(index).getString("choice"))
        if(index<=0) {
            val unsent=if(index<0) "Android permission preflight unsent "+nonce else fixture.getJSONArray("cases").getJSONObject(0).getString("text")
            allowOriginalSharing(unsent)
            if(index<0) ui.onNodeWithTag("message-input").performTextReplacement("")
        } else {
            assertTrue(session.aiSharing.permits(AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"))))
        }
        if(index<0) {
            assertTrue(session.text.isEmpty());assertTrue(session.selectedThread.isEmpty());assertTrue(session.canCompose)
            control("preflight-complete",JSONObject().put("projectId",fixture.getString("projectId")).put("pid",android.os.Process.myPid()));shot("native-preflight");return
        }
        val row=fixture.getJSONArray("cases").getJSONObject(index);val text=row.getString("text");val marker=row.getString("marker")
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(text)
        ui.waitUntil(10_000) { session.text==text&&session.decision.action=="send"&&session.decision.enabled }
        ui.onNodeWithTag("message-input").assertTextEquals(text)
        val target=session.targetKey
        control("claim-case",JSONObject().put("index",index).put("target",target).put("text",text))
        ui.runOnIdle { assertEquals(target,session.targetKey);assertEquals(text,session.text) }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(180_000) { session.messages.any { it.role=="assistant"&&it.text.trim()==marker }&&!session.running&&session.transferStatus==null } }
        catch(failure: ComposeTimeoutException) { shot("case-$index-unresolved");throw AssertionError("Original input unresolved; never rerun this leased case: state=${session.runtimeState}, error=${session.error}",failure) }
        val record=session.operationRecords.single { it.intent.contains(marker)&&it.kind in listOf("reply","creation") }
        assertTrue(record.submissionAttempted);assertTrue(session.selectedThread.isNotEmpty())
        control("case-complete",JSONObject().put("index",index).put("threadId",session.selectedThread).put("operationId",record.id).put("intentSha256",record.intentSha256).put("pid",android.os.Process.myPid()))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker))
        ui.onAllNodesWithText(marker).onLast().assertIsDisplayed();shot("case-$index-native-complete")
        if(index==2) {
            settings("read-only")
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("coldDraft"))
            ui.waitUntil(5_000) { session.text==fixture.getString("coldDraft") }
            File(i.targetContext.filesDir,"permission-warm-$nonce.json").writeText(originals().toString())
            shot("warm-originals-and-unsent-readonly-draft")
        }
    }
}
