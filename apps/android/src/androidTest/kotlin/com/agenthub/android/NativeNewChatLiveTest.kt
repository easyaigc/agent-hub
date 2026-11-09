package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Opt-in, one original-desktop model turn. A private fixture is mandatory. */
class NativeNewChatLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle()
        val image=instrumentation.uiAutomation.takeScreenshot()
        val folder=File(ui.activity.filesDir,"acceptance/new-chat-live").apply { mkdirs() }
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun chooseProjectFolderAndCreateOnceInOriginalDesktop() {
        require(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val fixture=File(instrumentation.targetContext.filesDir,"acceptance/new-chat-live.json")
        org.junit.Assume.assumeTrue("Private original-desktop acceptance fixture required",fixture.exists())
        val config=JSONObject(fixture.readText(Charsets.UTF_8))
        val token=config.getString("token");val project=config.getString("projectId");val marker=config.getString("marker")
        require(java.net.URI(config.getString("endpoint")).host=="127.0.0.1")
        ui.waitUntil(30_000) { !session.restoring }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(NativeHostReads { token },NativeCodexCommands { token });session.changeLanguage("zh-Hans") }
        main { session.connect("New-chat isolated original desktop",config.getString("endpoint"),token) }
        ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("sidebar-new").performClick()
        ui.onNodeWithTag("new-chat-refresh").performScrollTo().performClick()
        try { ui.waitUntil(30_000) { !session.loading&&session.projects.any { it.id==project }&&session.canCreateThread } }
        catch(failure: ComposeTimeoutException) { shot("failure-projects");throw AssertionError("Live project picker: online=${session.selectedClient.online}, projects=${session.projects.map { it.id }}, creation=${session.canCreateThread}, unavailable=${session.newChatUnavailableReason}, error=${session.error}",failure) }
        ui.onNodeWithTag("choose-project-$project").performScrollTo().assertIsDisplayed();shot("project-picker")
        ui.onNodeWithTag("choose-project-$project").performClick()
        ui.onNodeWithTag("sheet-projects").assertDoesNotExist();ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
        ui.onNodeWithTag("new-chat-destination-root").assertTextEquals(config.getString("root"))
        ui.runOnIdle { assertEquals(project,session.draftProject?.id);assertTrue(session.selectedThread.isEmpty()) };shot("project-draft")
        ui.onNodeWithTag("message-input").performTextReplacement("This is an isolated Agent Hub project creation test. Reply only $marker. Do not use tools or modify files.")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest!=null||session.transferStatus!=null }
        if(session.aiSharingRequest!=null) {
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        }
        ui.waitUntil(180_000) { session.selectedThread.isNotEmpty()&&session.messages.any { it.role=="assistant"&&it.text.contains(marker) }&&session.transferStatus==null }
        val record=session.operationRecords.single { it.kind=="creation"&&JSONObject(it.intent).getString("text").contains(marker) }
        assertEquals(project,JSONObject(record.intent).getString("projectId"));assertEquals("confirmed",record.phase)
        File(ui.activity.filesDir,"acceptance/new-chat-live-result.json").writeText(JSONObject().put("hostId",session.selectedClient.id).put("threadId",session.selectedThread).put("projectId",project).put("operationId",record.id).put("marker",marker).toString(),Charsets.UTF_8)
        shot("original-desktop-reply")
    }
}
