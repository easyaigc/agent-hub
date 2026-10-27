package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Immutable creation presentation through native UI and encrypted metadata. Synthetic provider only. */
class MobilePendingCreationMessageTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"creation-isolation-fixture-host","fixture-workspace",java.util.UUID.randomUUID().toString())
    private val intents=mutableListOf<CreationIntent>()
    private val submits=mutableListOf<String>()
    private val detaches=mutableListOf<String>()
    private var proofEnabled=true
    private var authorityGate: CompletableDeferred<Unit>?=null
    private var authorityStarted=false
    private val responses=mutableMapOf<String,DesktopCreation>()
    private val queryJobs=mutableListOf<Job>()
    private var queryCount=0
    private val jobs=mutableMapOf<String,Job>()
    private val created=mutableMapOf<String,MobileThread>()
    private val chosen=GenerationSettings("fixture-model","high","read-only","fast","default")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun thread(index: Int)=HostReadCodec.thread(JSONObject().put("id","created-${index+1}").put("hostId",scope.hostId).put("title","Created ${index+1}").put("projectId","fixture-project").put("state","idle"))
    private fun result(id: String,phase: String,detached: Boolean=false): DesktopCreation {
        val index=intents.indexOfFirst { it.operationId==id };check(index>=0)
        val value=if(phase=="confirmed")thread(index) else null
        return DesktopCreation(scope.hostId,id,"fixture-project",phase,value?.id,if(phase=="outcome-unknown")"Fixture generation preflight failed"else null,emptyList(),value,detached,if(phase=="outcome-unknown")CreationPreparation("created-1","input-retryable",if(proofEnabled)CreationDiagnostic("generation","generation-preflight-failed","retry-original-preflight")else null)else if(phase=="confirmed")CreationPreparation("created-1","submitted",null)else null)
    }
    private fun setup() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                authorityGate?.let { authorityStarted=true;it.await() }
                return HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Synthetic independent creations").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("projects",JSONArray().put(JSONObject().put("id","fixture-project").put("hostId",scope.hostId).put("name","Fixture project").put("roots",JSONArray())).put(JSONObject().put("id","second-project").put("hostId",scope.hostId).put("name","Second project").put("roots",JSONArray())))
                .put("threads",JSONArray(created.values.map { encodeThread(it) }))
                .put("capabilities",JSONArray(listOf("reply","desktop:creations","commands:target-bound:v1","models:read","generation-settings","generation-speed"))).toString())
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("low","high"),"standard",listOf("standard","fast")))
            override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation {
                assertEquals(scope.hostId,client.id);assertEquals("fixture-project",intent.projectId);assertFalse(intents.any { it.operationId==intent.operationId })
                intents.add(intent);return result(intent.operationId,"prepared")
            }
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                submits.add(operationId);jobs[operationId]=currentCoroutineContext().job
                val response=result(operationId,if(submits.count { it==operationId }==1)"outcome-unknown"else"confirmed")
                responses[operationId]=response;response.thread?.let { created[it.id]=it };return response
            }
            override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation {
                assertTrue("Every passive observation is read-only",inspectOnly);queryCount++;queryJobs.add(currentCoroutineContext().job);return responses.getValue(operationId)
            }
            override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation {
                assertFalse(detaches.contains(operationId));detaches.add(operationId);return result(operationId,"outcome-unknown",true)
            }
        }
        ui.waitUntil(10_000) { !session.restoring }
        assertFalse("Only reset the fixture after the default encrypted restore has completed",session.localRecoveryRequired)
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reads,commands) }
        main { session.connect("Synthetic creation isolation fixture","https://fixture.invalid","") }
        ui.waitUntil(5_000) { session.hasClient&&!session.loading&&session.projects.size==2&&session.models.isNotEmpty() }
        ui.runOnIdle { session.newChat("fixture-project");session.configure(chosen) };main { allowSyntheticRegressionSharing(session) }
    }
    private fun hideOwnKeyboard() {
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("composer-dismiss-keyboard").assertIsDisplayed().performClick()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var hidden=false;var height=0
            ui.runOnUiThread { val view=ui.activity.window.decorView;hidden=androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true;val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);height=rect.height() }
            val now=android.os.SystemClock.uptimeMillis();if(!hidden||height!=lastHeight) { stableSince=now;lastHeight=height };hidden&&now-stableSince>=700
        }
        ui.waitForIdle()
    }
    private fun send(text: String,index: Int): SavedOperation {
        ui.onNodeWithTag("message-input").performTextReplacement(text);hideOwnKeyboard()
        ui.waitUntil(5_000) { session.decision.action=="send"&&session.decision.enabled&&session.text==text }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(10_000) { submits.size==index+1&&session.operationRecords.any { it.id==submits[index]&&it.submissionAttempted } } }
        catch(e: ComposeTimeoutException) { throw AssertionError("Synthetic creation send $index: prepared=${intents.size}, submitted=${submits.size}, error=${session.error}, wait=${session.transferStatus}, action=${session.decision.action}, text=${session.text}, records=${session.operationRecords.map { it.kind+":"+it.phase }}",e) }
        return session.operationRecords.single { it.id==submits[index] }
    }
    private fun original(): SavedOperation {
        setup();val first=send("First immutable creation",0)
        ui.waitUntil(10_000) { session.transferStatus==null&&jobs.getValue(first.id).isCompleted&&session.operationRecords.single { it.id==first.id }.phase=="outcome-unknown" }
        val saved=session.operationRecords.single { it.id==first.id };assertEquals(proofEnabled,session.mayContinueCreation(saved));return saved
    }
    private fun row(first: SavedOperation)=session.operationRecords.single { it.id==first.id }
    private fun waitConfirmed(first: SavedOperation) {
        ui.waitUntil(10_000) { submits.size==2&&session.transferStatus==null&&row(first).phase=="confirmed"&&jobs.getValue(first.id).isCompleted }
        assertEquals(listOf(first.id,first.id),submits);assertEquals(listOf("First immutable creation"),intents.map { it.text });assertTrue(detaches.isEmpty())
        val saved=row(first);assertEquals(first.intent,saved.intent);assertEquals(first.intentSha256,saved.intentSha256);assertEquals(first.sharingScope,saved.sharingScope);assertFalse(saved.detached)
        val cold=runBlocking { NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext)).records(first.target).single { it.id==first.id } }
        assertEquals(saved.body().toString(),cold.body().toString())
    }
    private fun continueOriginal(first: SavedOperation) { ui.onNodeWithTag("continue-creation-${first.id}").performScrollTo().assertIsEnabled().performClick();waitConfirmed(first) }
    @After fun release() { authorityGate?.complete(Unit);ui.runOnIdle { session.leaveForeground() } }
    private fun assertOriginal(id: String,text: String) {
        ui.onNodeWithTag("desktop-creation-message").assertIsDisplayed()
        ui.onNodeWithTag("user-message-text-$id").assertTextEquals(text)
    }
    @Test fun pendingMessageAppearsBeforeInitialAuthorityReturns() {
        setup();authorityGate=CompletableDeferred()
        ui.onNodeWithTag("message-input").performTextReplacement("Original before initial authority");hideOwnKeyboard()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try {
            ui.waitUntil(10_000) { authorityStarted&&session.transferStatus!=null }
            assertTrue(session.operationRecords.isEmpty());assertTrue(intents.isEmpty());assertTrue(submits.isEmpty())
            ui.onNodeWithTag("desktop-creation-message").assertIsDisplayed()
            ui.onNodeWithTag("desktop-creation-message-content").assert(hasAnyDescendant(hasText("Original before initial authority")))
            ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("发送中…")
        } finally { authorityGate?.complete(Unit) }
    }
    @Test fun independentDraftNeverReplacesTheOriginalMessage() {
        val first=original();assertOriginal(first.id,"First immutable creation")
        ui.onNodeWithTag("message-input").performTextReplacement("Independent editable draft");hideOwnKeyboard()
        assertOriginal(first.id,"First immutable creation")
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("发送中…")
        ui.onNodeWithTag("desktop-creation-message-loading").assertExists()
        assertEquals(first.intent,row(first).intent);assertEquals(listOf(first.id),submits)
    }
    @Test fun switchingProjectHidesOnlyTheOtherProjectsOriginalMessage() {
        val first=original();assertOriginal(first.id,"First immutable creation");val source=session.targetKey
        ui.runOnIdle { session.newChat("second-project") };ui.waitUntil(5_000) { session.targetKey!=source&&session.transferStatus==null }
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        ui.onNodeWithTag("message-input").performTextReplacement("Second project draft");hideOwnKeyboard()
        ui.runOnIdle { session.newChat("fixture-project") };ui.waitUntil(5_000) { session.targetKey==source }
        assertOriginal(first.id,"First immutable creation");assertEquals("First immutable creation",session.text)
        ui.runOnIdle { session.newChat("second-project") };ui.waitUntil(5_000) { session.targetKey!=source }
        assertEquals("Second project draft",session.text);assertEquals(listOf(first.id),submits)
    }
    @Test fun confirmedOriginalDisappearsWithoutClearingTheNewDraft() {
        val first=original();assertOriginal(first.id,"First immutable creation")
        ui.onNodeWithTag("message-input").performTextReplacement("Independent confirmed draft");hideOwnKeyboard();continueOriginal(first)
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        assertEquals("Independent confirmed draft",session.text);assertEquals("",session.selectedThread)
    }
    @Test fun detachedOriginalDisappearsWithoutRewritingTheDraft() {
        val first=original();assertOriginal(first.id,"First immutable creation")
        ui.onNodeWithTag("message-input").performTextReplacement("Draft after detached original");hideOwnKeyboard()
        ui.runOnIdle { session.detachOperation(first) }
        ui.waitUntil(10_000) { row(first).detached&&session.transferStatus==null&&detaches.size==1 }
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        assertEquals("Draft after detached original",session.text);assertEquals(first.intent,row(first).intent);assertEquals(listOf(first.id),submits)
    }
    @Test fun rejectedMessageReadsOnlyOriginalMetadataAndKeepsDuplicateNames() {
        setup();val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext);val journal=NativeOperationJournal(storage)
        val id=java.util.UUID.randomUUID().toString();val files=listOf("原附件.txt","原附件.txt","image.png").map { CreationFile(java.util.UUID.randomUUID().toString(),it,"text/plain",7,"a".repeat(64)) }
        val intent=CreationIntent(scope.hostId,id,"fixture-project","Rejected original metadata only",files,chosen)
        val first=runBlocking { journal.prepare(session.selectedClient,null,"creation",intent.body(),session.targetKey,java.util.UUID.randomUUID().toString()) }
        runBlocking { journal.observed(first.target,id,"rejected",encodeCreation(DesktopCreation(scope.hostId,id,"fixture-project","rejected",null,"Synthetic refused input",emptyList(),null,false)),"Synthetic refused input") }
        ui.runOnIdle { session.newChat("fixture-project") }
        ui.waitUntil(10_000) { session.operationRecords.any { it.id==id&&it.phase=="rejected" } }
        ui.onNodeWithTag("message-input").performTextReplacement("New draft with no local original file bytes");hideOwnKeyboard()
        assertOriginal(id,intent.text);ui.onAllNodesWithText("文件 · 原附件.txt").assertCountEquals(2);ui.onNodeWithText("文件 · image.png").assertExists()
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("未发送")
        ui.onAllNodesWithTag("desktop-creation-message-loading").assertCountEquals(0)
        assertEquals(intent.body().toString(),session.operationRecords.single { it.id==id }.intent)
        assertTrue(intents.isEmpty());assertTrue(submits.isEmpty());assertTrue(detaches.isEmpty());assertTrue(session.attachments.isEmpty())
    }
}
