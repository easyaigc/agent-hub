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

/** Authoritative rejected creation return-to-draft through native UI and encrypted journal. Synthetic provider only. */
class MobileRejectedCreationTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"creation-isolation-fixture-host","fixture-workspace",java.util.UUID.randomUUID().toString())
    private val intents=mutableListOf<CreationIntent>()
    private val submits=mutableListOf<String>()
    private val detaches=mutableListOf<String>()
    private var rejectPreparation=true
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
        return DesktopCreation(scope.hostId,id,"fixture-project",phase,value?.id,if(phase=="rejected")"Synthetic refusal before model input"else if(phase=="outcome-unknown")"Fixture generation preflight failed"else null,emptyList(),value,detached,if(phase=="outcome-unknown")CreationPreparation("created-1","input-retryable",if(proofEnabled)CreationDiagnostic("generation","generation-preflight-failed","retry-original-preflight")else null)else if(phase=="confirmed")CreationPreparation("created-1","submitted",null)else null)
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
                intents.add(intent);return result(intent.operationId,if(rejectPreparation)"rejected"else"prepared")
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
                assertFalse(detaches.contains(operationId));detaches.add(operationId);return result(operationId,if(rejectPreparation)"rejected"else"outcome-unknown",true)
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
    private fun rejected(withFile: Boolean=false): SavedOperation {
        setup()
        if(withFile)main { session.addAttachment("原文件.txt","text/plain","Original local attachment".toByteArray()) }
        ui.onNodeWithTag("message-input").performTextReplacement("Rejected immutable creation");hideOwnKeyboard()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { intents.size==1&&session.transferStatus==null&&session.operationRecords.any { it.id==intents.single().operationId&&it.phase=="rejected" } }
        assertTrue(submits.isEmpty());assertTrue(detaches.isEmpty());assertNull(session.error)
        return session.operationRecords.single { it.id==intents.single().operationId }
    }
    private fun editDraft(value: String="Independent rejected draft") { ui.onNodeWithTag("message-input").performTextReplacement(value);hideOwnKeyboard() }
    private fun returnDraft(first: SavedOperation) {
        ui.onNodeWithTag("return-rejected-creation-${first.id}").performScrollTo().assertIsEnabled().assertTextEquals("返回草稿").performClick()
        ui.waitUntil(10_000) { row(first).detached&&session.transferStatus==null }
        assertEquals("rejected",row(first).phase);assertEquals(first.intent,row(first).intent);assertEquals(first.intentSha256,row(first).intentSha256)
        assertTrue(detaches.isEmpty());assertTrue(submits.isEmpty())
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
    }
    @Test fun rejectedOriginalBlocksAnotherSendUntilExplicitReturn() {
        val first=rejected();editDraft();ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("未发送")
        assertEquals("send",session.decision.action);assertFalse(session.decision.enabled);ui.onNodeWithTag("send-message").assertIsNotEnabled()
        ui.runOnIdle { session.performAction() };ui.waitForIdle();assertEquals(1,intents.size);assertTrue(submits.isEmpty());assertEquals(first.intent,row(first).intent)
        assertEquals("Independent rejected draft",session.text)
    }
    @Test fun returnPreservesCurrentTextFilesAndSettingsWithoutNotifyingHost() {
        val first=rejected(true);editDraft();main { session.addAttachment("新草稿文件.txt","text/plain","New independent local attachment".toByteArray()) };val files=session.attachments;val settings=session.generationSettings
        assertEquals(2,files.size);assertEquals(1,JSONObject(first.intent).getJSONArray("attachments").length())
        returnDraft(first);assertEquals("Independent rejected draft",session.text);assertEquals(files,session.attachments);assertEquals(settings,session.generationSettings)
        assertEquals("",session.selectedThread);assertEquals("send",session.decision.action);assertTrue(session.decision.enabled);assertEquals(1,intents.size)
        val cold=runBlocking { NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext)).records(first.target).single { it.id==first.id } }
        assertEquals(row(first).body().toString(),cold.body().toString())
    }
    @Test fun unknownOriginalNeverOffersRejectedReturnOrUnsentStatus() {
        rejectPreparation=false;setup();val first=send("Unknown immutable creation",0)
        ui.waitUntil(10_000) { session.transferStatus==null&&row(first).phase=="outcome-unknown" }
        ui.onAllNodesWithTag("return-rejected-creation-${first.id}").assertCountEquals(0)
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("发送中…");assertFalse(row(first).detached);assertEquals(listOf(first.id),submits)
    }
    @Test fun originalMetadataDoesNotRequireAttachmentBytesForLocalReturn() {
        setup();val journal=NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext));val id=java.util.UUID.randomUUID().toString()
        val intent=CreationIntent(scope.hostId,id,"fixture-project","Rejected metadata without file bytes",listOf(CreationFile(java.util.UUID.randomUUID().toString(),"缺少原字节.txt","text/plain",7,"a".repeat(64))),chosen)
        val first=runBlocking { journal.prepare(session.selectedClient,null,"creation",intent.body(),session.targetKey,java.util.UUID.randomUUID().toString()) }
        runBlocking { journal.observed(first.target,id,"rejected",encodeCreation(DesktopCreation(scope.hostId,id,"fixture-project","rejected",null,"Synthetic rejection",emptyList(),null,false))) }
        ui.runOnIdle { session.newChat("fixture-project") };ui.waitUntil(10_000) { session.operationRecords.any { it.id==id&&it.phase=="rejected" } }
        editDraft();returnDraft(first);assertEquals("Independent rejected draft",session.text);assertTrue(session.attachments.isEmpty());assertTrue(intents.isEmpty())
    }
    @Test fun rejectedOriginalAlsoKeepsExplicitEndWaitConfirmation() {
        val first=rejected();editDraft()
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-dialog").assertIsDisplayed();ui.onNodeWithTag("creation-end-wait-cancel").performClick()
        assertFalse(row(first).detached);assertTrue(detaches.isEmpty());assertFalse(session.decision.enabled)
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().performClick();ui.onNodeWithTag("creation-end-wait-confirm").performClick()
        ui.waitUntil(10_000) { row(first).detached&&session.transferStatus==null&&detaches==listOf(first.id) }
        assertEquals("rejected",row(first).phase);assertTrue(submits.isEmpty());assertEquals("Independent rejected draft",session.text)
    }
    @Test fun sendAfterReturnCreatesANewExplicitIntentAndNeverReplaysRejectedOriginal() {
        val first=rejected();editDraft("New explicitly submitted draft");returnDraft(first)
        ui.runOnIdle { rejectPreparation=false };ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { intents.size==2&&submits.size==1&&session.transferStatus==null }
        assertNotEquals(first.id,intents[1].operationId);assertEquals("New explicitly submitted draft",intents[1].text);assertEquals(listOf(intents[1].operationId),submits)
        assertTrue(row(first).detached);assertEquals(first.intent,row(first).intent);assertEquals("rejected",row(first).phase)
    }
    @Test fun oldReturnRequestCannotDismissAcrossAProjectRoundTrip() {
        val first=rejected();editDraft();val context=checkNotNull(session.creationWaitContext(first));val source=session.targetKey
        ui.runOnIdle { session.newChat("second-project");session.returnRejectedCreation(context) };ui.waitUntil(5_000) { session.targetKey!=source }
        assertFalse(row(first).detached);ui.runOnIdle { session.newChat("fixture-project") };ui.waitUntil(5_000) { session.targetKey==source }
        ui.runOnIdle { session.returnRejectedCreation(context) };ui.waitForIdle();assertFalse(row(first).detached)
        assertEquals("Independent rejected draft",session.text);assertTrue(detaches.isEmpty());returnDraft(first)
    }
    @Test fun aNewerConfirmedJournalReceiptWinsBeforeLocalReturnPersistence() {
        val first=rejected();editDraft();val context=checkNotNull(session.creationWaitContext(first))
        val journal=NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext))
        runBlocking { journal.observed(first.target,first.id,"confirmed",encodeCreation(result(first.id,"confirmed"))) }
        assertEquals("rejected",row(first).phase)
        ui.runOnIdle { session.returnRejectedCreation(context) }
        ui.waitUntil(10_000) { row(first).phase=="confirmed" }
        assertFalse(row(first).detached);assertEquals(first.intent,row(first).intent);assertEquals("Independent rejected draft",session.text)
        assertTrue(detaches.isEmpty());assertTrue(submits.isEmpty());assertEquals(1,intents.size)
    }
    @Test fun anOlderLegacyRejectionCannotBlockANewerConfirmedCreation() {
        val first=rejected();val journal=NativeOperationJournal(NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext));val id=java.util.UUID.randomUUID().toString()
        val intent=CreationIntent(scope.hostId,id,"fixture-project","Legacy newer accepted intent",emptyList(),chosen);val newerThread=thread(1)
        runBlocking {
            journal.prepare(session.selectedClient,null,"creation",intent.body(),session.targetKey,java.util.UUID.randomUUID().toString())
            journal.observed(first.target,id,"confirmed",encodeCreation(DesktopCreation(scope.hostId,id,"fixture-project","confirmed",newerThread.id,null,emptyList(),newerThread,false)))
        }
        ui.runOnIdle { session.newChat("fixture-project") };ui.waitUntil(10_000) { session.operationRecords.any { it.id==id&&it.phase=="confirmed" } }
        editDraft("New post-upgrade draft");ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        assertEquals("send",session.decision.action);assertTrue(session.decision.enabled);assertFalse(row(first).detached)
        ui.onNodeWithTag("return-rejected-creation-${first.id}").assertIsNotEnabled()
        ui.onNodeWithTag("detach-operation-${first.id}").assertIsNotEnabled()
        assertTrue(submits.isEmpty());assertTrue(detaches.isEmpty());assertEquals(1,intents.size)
    }

}
