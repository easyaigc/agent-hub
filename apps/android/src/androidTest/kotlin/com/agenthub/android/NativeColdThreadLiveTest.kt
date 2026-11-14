package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Opt-in physical native continuation of one dedicated, previously unloaded original chat. */
class NativeColdThreadLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @After fun stop() { if(::owned.isInitialized)ui.runOnIdle { session.leaveForeground();owned.verifyDefault() } }
    @Test fun oldOriginalChatOpensOnceAndReceivesOneNativeReply() {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val file=File(i.targetContext.filesDir,"acceptance/cold-thread-live.json")
        Assume.assumeTrue("Private cold-thread fixture required",file.exists())
        val f=JSONObject(file.readText(Charsets.UTF_8))
        require(java.net.URI(f.getString("endpoint")).host=="127.0.0.1")
        require(f.getBoolean("independentOwnerAbsentBefore"))
        val id=f.getString("threadId");val marker=f.getString("marker")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"cold-original-chat");session.resetAcceptance();session.changeLanguage("en") }
        main { session.connect("USB isolated original desktop",f.getString("endpoint"),f.getString("token")) }
        ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==id } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.messages.any { it.role=="assistant"&&it.text.contains(f.getString("priorMarker")) } }
        val prior=ui.runOnIdle { session.messages.toList() }
        assertEquals("Initial canonical history must not contain duplicate IDs",prior.size,prior.map { it.id }.toSet().size)
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"old-history-before-send")
        ui.onNodeWithTag("message-input").performTextReplacement("This is a dedicated old-chat connection test. Reply only $marker. Do not use tools or modify files.")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest!=null||session.transferStatus!=null||session.operationRecords.isNotEmpty() }
        if(session.aiSharingRequest!=null) {
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        }
        try { ui.waitUntil(180_000) { session.messages.any { it.role=="assistant"&&it.text.trim()==marker }&&!session.running&&session.transferStatus==null } }
        catch(failure: Throwable) { throw AssertionError("Cold original chat: error=${session.error}, loading=${session.historyLoading}, writable=${session.isWritable}, running=${session.running}, operations=${session.operationRecords.map { it.kind+":"+it.phase }}",failure) }
        assertEquals(id,session.selectedThread);assertTrue(session.isWritable);assertFalse(session.running)
        assertEquals(1,session.messages.count { it.role=="assistant"&&it.text.trim()==marker })
        assertEquals(1,session.operationRecords.count { it.kind=="reply" })
        assertEquals(1,session.operationRecords.count { it.kind=="open"&&it.phase=="opened" })
        for(message in prior) {
            assertEquals("Historical row must survive once: ${message.id}",1,session.messages.count { it.id==message.id })
            assertEquals(message.text,session.messages.single { it.id==message.id }.text)
        }
        assertEquals("Live transition must append only the new prompt and reply",prior.size+2,session.messages.size)
        assertEquals(session.messages.size,session.messages.map { it.id }.toSet().size)
        androidx.test.espresso.Espresso.closeSoftKeyboard();ui.waitForIdle()
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"old-original-reply-completed")
        File(i.targetContext.filesDir,"acceptance/cold-thread-live-result.json").writeText(JSONObject().put("threadId",id).put("marker",marker)
            .put("hostId",session.selectedClient.id).put("runtimeIdle",!session.running).put("writable",session.isWritable)
            .put("priorRows",prior.size).put("priorRowsOnce",true).put("finalRows",session.messages.size)
            .put("openId",session.operationRecords.single { it.kind=="open" }.id).put("replyId",session.operationRecords.single { it.kind=="reply" }.id)
            .put("folder",owned.folder.absolutePath).toString(),Charsets.UTF_8)
    }
}
