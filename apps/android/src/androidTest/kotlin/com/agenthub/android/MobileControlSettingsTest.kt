package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Native control gestures must retain chosen settings while leaving the editable draft alone. */
class MobileControlSettingsTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val chosen=GenerationSettings("fixture-model","high","read-only","fast","plan")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup(f: ExecutionFixture,settings: GenerationSettings?=chosen,existingThread: Boolean=true) {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        f.state="interrupted"
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance() }
        main { session.connect("Synthetic control settings",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.hasClient }
        if(existingThread) {
            ui.runOnIdle { session.selectThread(session.threads.first()) }
            ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null&&session.operationRecords.isEmpty() }
        }else{
            ui.runOnIdle { session.newChat("empty-project") };ui.waitUntil(10_000) { session.selectedThread.isEmpty()&&session.canCompose }
        }
        main { allowSyntheticRegressionSharing(session) }
        if(settings!=null)ui.runOnIdle { session.configure(settings) }
        assertEquals(settings ?: GenerationSettings(),session.generationSettings)
        assertTrue(if(existingThread)session.canRunAgain else session.canCompose)
    }
    private fun sent(f: ExecutionFixture)=f.server.requests.filter { it.path=="/v1/replies" }.map { JSONObject(String(it.body,Charsets.UTF_8)) }
    private fun verify(f: ExecutionFixture,restart: Boolean,text: String,files: List<String>) {
        ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null }
        val body=sent(f).single()
        assertEquals(com.agenthub.core.MobileRules.runAgainText(restart),body.getString("text"))
        assertEquals("next-turn",body.getString("mode"));assertEquals("fixture-thread",body.getString("threadId"));assertEquals("fixture-host",body.getString("hostId"))
        for((key,value)in listOf("model" to chosen.model,"effort" to chosen.effort,"permissionMode" to chosen.permissionMode,"speed" to chosen.speed,"collaborationMode" to chosen.collaborationMode))
            assertEquals("Control must retain chosen $key",value,body.optString(key,"[MISSING]"))
        assertEquals(0,body.getJSONArray("attachmentIds").length())
        assertEquals(text,session.text);assertEquals(files,session.attachments.map { it.manifest.id });assertEquals(chosen,session.generationSettings)
        val record=session.operationRecords.single { it.id==body.getString("clientMessageId") }
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(record.intent.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(record.intentSha256,digest)
        val saved=JSONObject(record.intent)
        for(key in listOf("text","mode","threadId","hostId","clientMessageId","model","effort","permissionMode","speed","collaborationMode"))assertEquals(body.get(key),saved.get(key))
        assertTrue(f.server.requests.all { it.method=="GET"||it.path=="/v1/replies" })
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun mainResumeRetainsChosenGeneration() {
        ExecutionFixture().use { f -> setup(f)
            ui.onNodeWithTag("resume-task").assertIsEnabled().performClick();verify(f,false,"",emptyList())
        }
    }
    @Test fun longPressContinueRetainsChosenGenerationAndDraft() {
        ExecutionFixture().use { f -> setup(f)
            ui.runOnIdle { session.text="Independent unsent composer" }
            ui.onNodeWithTag("send-message").performTouchInput { longClick() }
            ui.onNodeWithTag("action-resume").assertIsDisplayed().performClick();verify(f,false,"Independent unsent composer",emptyList())
        }
    }
    @Test fun longPressRestartRetainsChosenGenerationAndDraftFile() {
        ExecutionFixture().use { f -> setup(f)
            ui.runOnIdle { session.text="Independent draft with local file" }
            main { session.addAttachment("keep-local.txt","text/plain","Unsent local bytes".toByteArray(),session.attachmentImportDestination()) }
            val files=session.attachments.map { it.manifest.id };assertEquals(1,files.size)
            ui.onNodeWithTag("send-message").performTouchInput { longClick() }
            ui.onNodeWithTag("action-restart").assertIsDisplayed().performClick();verify(f,true,"Independent draft with local file",files)
        }
    }
    @Test fun unspecifiedSettingsStayUnspecifiedAndEachRestartGetsANewOperation() {
        ExecutionFixture().use { f -> setup(f,null)
            ui.onNodeWithTag("resume-task").performTouchInput { longClick() };ui.onNodeWithTag("action-restart").performClick()
            ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null }
            f.state="idle";ui.runOnIdle { session.loadHistory(userInitiated=true) }
            ui.waitUntil(10_000) { !session.historyLoading&&session.decision.action=="send"&&session.canRunAgain }
            ui.onNodeWithTag("send-message").assertIsNotEnabled().performTouchInput { longClick() };ui.onNodeWithTag("action-restart").performClick()
            ui.waitUntil(10_000) { sent(f).size==2&&session.transferStatus==null }
            assertNotEquals(sent(f)[0].getString("clientMessageId"),sent(f)[1].getString("clientMessageId"))
            for(body in sent(f)) {
                assertEquals(com.agenthub.core.MobileRules.runAgainText(true),body.getString("text"));assertEquals("next-turn",body.getString("mode"))
                for(key in listOf("model","effort","permissionMode","speed","collaborationMode"))assertFalse("Do not invent $key",body.has(key))
            }
            assertEquals("",session.text);assertTrue(session.attachments.isEmpty());assertEquals(GenerationSettings(),session.generationSettings)
        }
    }

    private fun setHistoryState(f: ExecutionFixture,state: String) {
        f.state=state;f.turn=if(state=="running")"fixture-turn" else null
        ui.runOnIdle { session.loadHistory(userInitiated=true) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtimeState==state }
    }
    private fun assertGeneration(body: JSONObject,settings: GenerationSettings) {
        for((key,value)in listOf("model" to settings.model,"effort" to settings.effort,"permissionMode" to settings.permissionMode,"speed" to settings.speed,"collaborationMode" to settings.collaborationMode))
            if(value==null)assertFalse("Do not invent $key",body.has(key))else assertEquals("Captured $key",value,body.optString(key,"[MISSING]"))
    }
    private fun queueText(text: String) {
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitForIdle();ui.onNodeWithTag("reply-mode-queue").assertIsEnabled().performClick();ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
    }
    @Test fun queuedAcknowledgementRetainsAllSettingsForTheNextQueuedInput() {
        ExecutionFixture().use { f ->
            val settings=chosen.copy(collaborationMode="default");setup(f,settings);setHistoryState(f,"running")
            for(index in 1..2) {
                queueText("Synthetic queued input $index")
                ui.waitUntil(10_000) { sent(f).size==index&&session.transferStatus==null&&session.text.isEmpty() }
                assertEquals("Queued acknowledgement retains chosen settings",settings,session.generationSettings)
            }
            for(body in sent(f)){assertEquals("queue",body.getString("mode"));assertGeneration(body,settings)}
            assertNotEquals(sent(f)[0].getString("clientMessageId"),sent(f)[1].getString("clientMessageId"))
        }
    }
    @Test fun submittedReplyStillReturnsToRuntimeDefaults() {
        ExecutionFixture().use { f -> setup(f);setHistoryState(f,"idle")
            ui.onNodeWithTag("message-input").performTextReplacement("Synthetic ordinary input");ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null&&session.text.isEmpty() }
            assertGeneration(sent(f).single(),chosen);assertEquals(GenerationSettings(),session.generationSettings)
            ui.onNodeWithTag("message-input").performTextReplacement("Synthetic follow-computer input");ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { sent(f).size==2&&session.transferStatus==null };assertGeneration(sent(f)[1],GenerationSettings())
        }
    }
    @Test fun confirmedFirstCreationStillResetsThePlaceholderSettings() {
        ExecutionFixture(staged=true).use { f ->
            // Preparation queries cannot claim confirmed before this fixture receives the first submit.
            f.submitPhase="prepared";setup(f,chosen,false);f.state="idle"
            ui.onNodeWithTag("message-input").performTextReplacement("Synthetic first creation")
            ui.waitUntil(10_000) { session.decision.action=="send"&&session.decision.enabled }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { f.server.requests.any { it.method=="POST"&&it.path=="/v1/desktop/creations/submit" } }
            ui.runOnIdle { f.submitPhase="confirmed" }
            ui.waitUntil(10_000) { session.selectedThread=="created-thread"&&session.transferStatus==null }
            assertEquals(1,f.server.requests.count { it.method=="POST"&&it.path=="/v1/desktop/creations/submit" })
            assertTrue(session.operationRecords.single { it.kind=="creation" }.submissionAttempted)
            val first=JSONObject(String(f.server.requests.single { it.method=="POST"&&it.path=="/v1/desktop/creations" }.body,Charsets.UTF_8));assertGeneration(first,chosen)
            assertEquals(GenerationSettings(),session.generationSettings)
            ui.onNodeWithTag("message-input").performTextReplacement("Synthetic created thread followup")
            ui.waitUntil(10_000) { !session.historyLoading&&session.decision.action=="send"&&session.decision.enabled }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null };assertGeneration(sent(f).single(),GenerationSettings());assertEquals("created-thread",sent(f).single().getString("threadId"))
        }
    }
    @Test fun lateQueuedAcknowledgementPreservesBothTargetSettings() {
        ExecutionFixture().use { f ->
            val old=chosen.copy(collaborationMode="default");val newer=chosen.copy(effort="low",permissionMode="full-access",speed="standard")
            f.additionalThread="retention-b";setup(f,old);setHistoryState(f,"running");f.holdReplyResponse=true;f.holdReplyReceipt=true
            try {
                queueText("Synthetic held queue acknowledgement")
                assertTrue(f.replyStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.runOnIdle { session.selectThread(session.threads.single { it.id=="retention-b" }) }
                ui.waitUntil(10_000) { !session.historyLoading&&session.canConfigureGeneration }
                ui.runOnIdle { session.configure(newer);session.text="Newer target draft" }
                f.replyRelease.countDown();f.replyQueryRelease.countDown()
                ui.runOnIdle { session.selectThread(session.threads.single { it.id=="fixture-thread" }) }
                ui.waitUntil(10_000) { !session.historyLoading&&session.transferStatus==null&&session.operationRecords.any { it.id==id&&it.result?.let { raw->JSONObject(raw).optString("state")=="queued" }==true } }
                assertEquals(old,session.generationSettings);assertEquals("",session.text)
                ui.runOnIdle { session.selectThread(session.threads.single { it.id=="retention-b" }) }
                ui.waitUntil(10_000) { !session.historyLoading };assertEquals(newer,session.generationSettings);assertEquals("Newer target draft",session.text)
                assertEquals(1,sent(f).size);assertGeneration(sent(f).single(),old)
            }finally{f.replyRelease.countDown();f.replyQueryRelease.countDown()}
        }
    }

}
