package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** A sealed original answer: the independent gate permits exact-target GETs only. */
class MobileOriginalReceiptReadLiveTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(i.targetContext.filesDir,"original-receipt-read.json").readText()) }
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private var owned: OwnedControlAcceptance?=null
    private val session get()=checkNotNull(owned).session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @After fun preserve() {
        owned?.let { examiner ->
            main { session.leaveForeground();session.discovery?.stop();session.easyWorkDiscovery?.stop() }
            examiner.verifyDefault()
        }
    }
    @Test fun coldHostKeepsExactOriginalTerminalReceiptWithoutReplay() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(InstrumentationRegistry.getArguments().getString("originalReceiptReadOnly")=="1")
        require(config.getBoolean("readOnly")&&config.getInt("expectedOriginalInputs")==3)
        val endpoint=java.net.URI(config.getString("endpoint"))
        require(endpoint.scheme=="http"&&endpoint.host=="127.0.0.1"&&endpoint.rawPath.isEmpty())
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"original-receipt-read");session.resetAcceptance();session.appearance="dark" }
        val host=config.getString("hostId");val thread=config.getString("threadId");val id=config.getString("operationId")
        main { session.connect("原回答回执只读验收",config.getString("endpoint"),config.getString("token")) }
        ui.waitUntil(45_000) { !session.loading&&session.selectedClient.id==host&&session.threads.any { it.id==thread } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.selectedThread==thread&&session.messages.any { it.role=="user"&&it.text==config.getString("answerBody") } }
        val draft="ORIGINAL_RECEIPT_LOCAL_DRAFT_"+config.getString("run").takeLast(8)
        ui.onNodeWithTag("message-input").performTextReplacement(draft)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val commands=NativeCodexCommands { config.getString("token") }
        var receipt: DeliveryReceipt?=null;var status: JSONObject?=null
        main {
            receipt=commands.receipt(session.selectedClient,thread,id)
            status=commands.replyDeliveryStatus(session.selectedClient,thread,id)
        }
        val exact=checkNotNull(receipt);val inspected=checkNotNull(status)
        assertEquals(host,exact.hostId);assertEquals(thread,exact.threadId);assertEquals(id,exact.operationId)
        assertEquals("completed",exact.state);assertEquals(config.getString("turnId"),exact.turnId)
        assertEquals(config.getString("wireAnswer"),exact.text);assertTrue(exact.attachmentIds.isEmpty());assertNull(exact.error)
        assertEquals("accepted",inspected.getString("status"));assertEquals(config.getString("fingerprint"),inspected.getString("fingerprint"))
        assertEquals("completed",inspected.getJSONObject("receipt").getString("state"))
        assertTrue(sameScope(HostReadCodec.target(inspected.getJSONObject("target")),session.selectedClient.scope))
        ui.waitUntil(30_000) { session.runtime?.receipts?.singleOrNull { it.operationId==id }?.state=="completed" }
        assertEquals(1,session.messages.count { it.role=="user"&&it.text==config.getString("answerBody") })
        assertEquals(config.getInt("expectedOriginalInputs"),session.messages.count { it.role=="user" })
        val ids=session.messages.map { it.id };assertEquals(ids.size,ids.distinct().size)
        ui.runOnIdle { session.refresh(userInitiated=true) }
        ui.waitUntil(30_000) { !session.loading&&!session.historyLoading&&session.messages.map { it.id }==ids }
        assertEquals(draft,session.text);assertNull(session.error);assertTrue(session.operationRecords.isEmpty())
        assertFalse(session.runtime!!.approvals.any { it.questions.isNotEmpty() })
        ui.onNodeWithTag("receipt-$id").assertDoesNotExist()
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(config.getString("answerBody")))
        ui.onNodeWithText(config.getString("answerBody")).assertIsDisplayed()
        ui.waitForIdle();i.waitForIdleSync()
        ui.waitUntil(10_000) { i.uiAutomation.rootInActiveWindow?.packageName?.toString()==i.targetContext.packageName }
        checkNotNull(owned).nodeShot(ui.onRoot(),"original-answer-history-and-independent-draft")
        File(checkNotNull(owned).folder,"original-receipt-read-proof.json").writeText(JSONObject()
            .put("hostId",host).put("threadId",thread).put("operationId",id).put("turnId",exact.turnId)
            .put("state",exact.state).put("fingerprint",inspected.getString("fingerprint"))
            .put("exactTarget",true).put("typedReceiptAndInspectionAgree",true).put("originalAnswerVisibleOnce",true)
            .put("draftPreserved",true).put("noFalseUncertainCard",true).put("newOperations",0)
            .put("nativeSessionInputs",session.messages.count { it.role=="user" }).toString(2))
    }
}
