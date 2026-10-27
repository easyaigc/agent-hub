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

/** Held independent creation results through the real native UI/flow/encrypted journal. Synthetic provider only. */
class MobileCreationIsolationTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"creation-isolation-fixture-host","fixture-workspace",java.util.UUID.randomUUID().toString())
    private val intents=mutableListOf<CreationIntent>()
    private val submits=mutableListOf<String>()
    private val detaches=mutableListOf<String>()
    private val gates=mutableMapOf<String,CompletableDeferred<DesktopCreation>>()
    private val jobs=mutableMapOf<String,Job>()
    private val created=mutableMapOf<String,MobileThread>()
    private val chosen=GenerationSettings("fixture-model","high","read-only","fast","default")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun thread(index: Int)=HostReadCodec.thread(JSONObject().put("id","created-${index+1}").put("hostId",scope.hostId).put("title","Created ${index+1}").put("projectId","fixture-project").put("state","idle"))
    private fun result(id: String,phase: String,detached: Boolean=false): DesktopCreation {
        val index=intents.indexOfFirst { it.operationId==id };check(index>=0)
        val value=if(phase=="confirmed")thread(index) else null
        return DesktopCreation(scope.hostId,id,"fixture-project",phase,value?.id,null,emptyList(),value,detached)
    }
    private fun setup() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Synthetic independent creations").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("projects",JSONArray().put(JSONObject().put("id","fixture-project").put("hostId",scope.hostId).put("name","Fixture project").put("roots",JSONArray())))
                .put("threads",JSONArray(created.values.map { encodeThread(it) }))
                .put("capabilities",JSONArray(listOf("reply","desktop:creations","commands:target-bound:v1","models:read","generation-settings","generation-speed"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("low","high"),"standard",listOf("standard","fast")))
            override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation {
                assertEquals(scope.hostId,client.id);assertEquals("fixture-project",intent.projectId);assertFalse(intents.any { it.operationId==intent.operationId })
                intents.add(intent);gates[intent.operationId]=CompletableDeferred();return result(intent.operationId,"prepared")
            }
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                submits.add(operationId);jobs[operationId]=currentCoroutineContext().job
                val response=gates.getValue(operationId).await();response.thread?.let { created[it.id]=it };return response
            }
            override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation {
                assertTrue("Background observation is read-only",inspectOnly);return result(operationId,"outcome-unknown",operationId in detaches)
            }
            override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation {
                assertFalse(detaches.contains(operationId));detaches.add(operationId);return result(operationId,"outcome-unknown",true)
            }
        }
        ui.waitUntil(10_000) { !session.restoring }
        assertFalse("Only reset the fixture after the default encrypted restore has completed",session.localRecoveryRequired)
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reads,commands) }
        main { session.connect("Synthetic creation isolation fixture","https://fixture.invalid","") }
        ui.waitUntil(5_000) { session.hasClient&&!session.loading&&session.projects.size==1&&session.models.isNotEmpty() }
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
    private fun detachedFirst(): SavedOperation {
        setup();val first=send("First immutable creation",0)
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-confirm").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { session.operationRecords.any { it.id==first.id&&it.detached }&&session.transferStatus==null&&detaches.size==1 }
        ui.runOnIdle { session.newChat("fixture-project");session.configure(chosen) }
        assertEquals("",session.selectedThread);return first
    }
    private fun finish(record: SavedOperation,success: Boolean=true) {
        ui.runOnIdle { if(success)gates.getValue(record.id).complete(result(record.id,"confirmed")) else gates.getValue(record.id).completeExceptionally(IllegalStateException("Old first creation failed")) }
        ui.waitUntil(10_000) { jobs.getValue(record.id).isCompleted };ui.waitForIdle()
    }
    private fun originalStillImmutable(first: SavedOperation,second: SavedOperation) {
        assertEquals(listOf(first.id,second.id),submits);assertEquals(listOf(first.id),detaches)
        assertEquals(listOf("First immutable creation","Second immutable creation"),intents.map { it.text })
        assertEquals(2,intents.map { it.operationId }.distinct().size)
        for(original in listOf(first,second)) {
            val saved=session.operationRecords.single { it.id==original.id };assertEquals(original.intent,saved.intent);assertEquals(original.intentSha256,saved.intentSha256);assertTrue(saved.submissionAttempted)
        }
        assertTrue(session.operationRecords.single { it.id==first.id }.detached)
    }
    @After fun release() { ui.runOnIdle { gates.values.forEach { it.completeExceptionally(CancellationException("Synthetic fixture finished")) };session.leaveForeground() } }
    @Test fun lateFirstSuccessCannotReleaseTheSecondCreationWaitOrClearItsDraft() {
        val first=detachedFirst();val second=send("Second immutable creation",1);assertNotNull(session.transferStatus)
        finish(first);assertNotNull("The second original still owns the wait",session.transferStatus);assertEquals("",session.selectedThread);assertEquals("Second immutable creation",session.text)
        assertTrue(session.operationRecords.single { it.id==second.id }.phase in listOf("submitting","outcome-unknown"));originalStillImmutable(first,second)
        finish(second);ui.waitUntil(10_000) { session.selectedThread=="created-2"&&session.transferStatus==null };assertEquals("",session.text)
    }
    @Test fun lateFirstSuccessCannotNavigateAwayFromTheSecondConfirmedTargetOrReplaceItsNewDraft() {
        val first=detachedFirst();val second=send("Second immutable creation",1);finish(second)
        ui.waitUntil(10_000) { session.selectedThread=="created-2"&&session.transferStatus==null }
        val newer=chosen.copy(effort="low");ui.runOnIdle { session.text="New draft on the second actual target";session.configure(newer) }
        finish(first);assertEquals("created-2",session.selectedThread);assertEquals("New draft on the second actual target",session.text);assertEquals(newer,session.generationSettings)
        originalStillImmutable(first,second);assertEquals(setOf("created-1","created-2"),session.threads.map { it.id }.toSet())
    }
    @Test fun lateFirstFailureCannotReplaceTheSecondTargetErrorOrItsNewDraft() {
        val first=detachedFirst();val second=send("Second immutable creation",1);finish(second)
        ui.waitUntil(10_000) { session.selectedThread=="created-2"&&session.transferStatus==null }
        ui.runOnIdle { session.text="Newer second target draft";session.error="Newer second target error" }
        finish(first,false);assertEquals("created-2",session.selectedThread);assertEquals("Newer second target draft",session.text);assertEquals("Newer second target error",session.error)
        originalStillImmutable(first,second)
    }
}
