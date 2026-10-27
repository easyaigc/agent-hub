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

/** Synthetic native late-creation receipt checks: immutable input and later editable draft are separate. */
class MobileCreationCallbackDraftTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"creation-callback-fixture-host","fixture-workspace",java.util.UUID.randomUUID().toString())
    private val originalText="Immutable first creation input"
    private val firstSettings=GenerationSettings("fixture-model","high","read-only","fast","default")
    private val nextSettings=firstSettings.copy(effort="low",speed="standard")
    private val intents=mutableListOf<CreationIntent>()
    private val submits=mutableListOf<String>()
    private var passive=false
    private var responseStarted=false
    private val responseGate=CompletableDeferred<Unit>()
    private var responseJob: Job?=null
    private var submissionJob: Job?=null
    private var originalThread: MobileThread?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun result(id: String,phase: String): DesktopCreation {
        val thread=if(phase=="confirmed")HostReadCodec.thread(JSONObject().put("id","created-original").put("hostId",scope.hostId).put("title","Original fixture creation").put("projectId","fixture-project").put("state","idle")).also { originalThread=it }else null
        return DesktopCreation(scope.hostId,id,"fixture-project",phase,thread?.id,null,emptyList(),thread,false,null)
    }
    private fun setup(observer: Boolean) {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        passive=observer
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Synthetic creation callbacks").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("projects",JSONArray().put(JSONObject().put("id","fixture-project").put("hostId",scope.hostId).put("name","Fixture project").put("roots",JSONArray())))
                .put("threads",JSONArray(listOfNotNull(originalThread).map(::encodeThread)))
                .put("capabilities",JSONArray(listOf("reply","desktop:creations","commands:target-bound:v1","models:read","generation-settings","generation-speed"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("low","high"),"standard",listOf("standard","fast")))
            override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation {
                assertEquals(scope.hostId,client.id);assertEquals("fixture-project",intent.projectId);assertTrue(intents.isEmpty());intents.add(intent)
                assertEquals(originalText,intent.text);assertTrue(intent.files.isEmpty());assertEquals(firstSettings,intent.settings);return result(intent.operationId,"prepared")
            }
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                assertEquals(intents.single().operationId,operationId);assertTrue(submits.isEmpty());submits.add(operationId);submissionJob=currentCoroutineContext().job
                if(passive)return result(operationId,"outcome-unknown")
                responseJob=currentCoroutineContext().job;responseStarted=true;responseGate.await();return result(operationId,"confirmed")
            }
            override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation {
                assertTrue(inspectOnly);assertEquals(intents.single().operationId,operationId)
                if(!passive||submissionJob?.isCompleted!=true)throw CodexCommandError("Synthetic non-observer boundary",404)
                if(responseStarted)return result(operationId,"confirmed")
                responseStarted=true;responseJob=currentCoroutineContext().job
                responseGate.await();return result(operationId,"confirmed")
            }
            override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=error("No detach is authorized by this fixture")
        }
        ui.waitUntil(15_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reads,commands) }
        main { session.connect("Synthetic creation callback fixture","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading&&session.models.isNotEmpty() }
        ui.runOnIdle { session.newChat("fixture-project");session.configure(firstSettings) };main { allowSyntheticRegressionSharing(session) }
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
    private fun original(observer: Boolean): SavedOperation {
        setup(observer);ui.onNodeWithTag("message-input").performTextReplacement(originalText);hideOwnKeyboard()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(25_000) { responseStarted&&submits.size==1&&session.operationRecords.any { it.id==submits.single()&&it.submissionAttempted } }
        if(observer)assertNull(session.transferStatus)else assertNotNull(session.transferStatus)
        return session.operationRecords.single { it.id==submits.single() }
    }
    private fun finish(first: SavedOperation) {
        responseGate.complete(Unit);ui.waitUntil(25_000) { responseJob?.isCompleted==true&&session.operationRecords.any { it.id==first.id&&it.phase=="confirmed" } };ui.waitForIdle()
        assertEquals(listOf(first.id),submits);assertEquals(1,intents.size)
        val cold=runBlocking { NativeOperationJournal(NativeSessionStorage(ui.activity)).records(first.target).single { it.id==first.id } }
        assertEquals("confirmed",cold.phase);assertEquals(first.intent,cold.intent);assertEquals(first.intentSha256,cold.intentSha256);assertEquals(first.sourceVersion,cold.sourceVersion);assertEquals(first.sharingScope,cold.sharingScope);assertFalse(cold.detached)
    }
    private fun newerText(observer: Boolean,reenterSame: Boolean) {
        val first=original(observer);val key=session.targetKey
        ui.onNodeWithTag("message-input").performTextReplacement("Later independently editable draft")
        if(reenterSame)ui.onNodeWithTag("message-input").performTextReplacement(originalText)
        hideOwnKeyboard();val expected=if(reenterSame)originalText else "Later independently editable draft"
        finish(first);assertEquals(key,session.targetKey);assertEquals("",session.selectedThread);assertEquals(expected,session.text);assertNull(session.error)
        ui.onNodeWithTag("message-input").assertTextEquals(expected)
    }
    private fun newerFile(onlyFile: Boolean) {
        val first=original(true);val key=session.targetKey
        ui.onNodeWithTag("message-input").performTextReplacement(if(onlyFile)""else"Independent text and file");hideOwnKeyboard()
        val bytes="Local later draft file, never part of original intent".toByteArray()
        main { session.addAttachment("later-draft.txt","text/plain",bytes) };val file=session.attachments.single()
        assertFalse("Generation controls remain unavailable during original creation wait",session.canConfigureGeneration)
        assertEquals(firstSettings,session.generationSettings)
        ui.runOnIdle { session.configure(nextSettings) };assertEquals("Rejected configuration never changed the draft settings",firstSettings,session.generationSettings);finish(first)
        assertEquals(key,session.targetKey);assertEquals("",session.selectedThread);assertEquals(if(onlyFile)""else"Independent text and file",session.text)
        assertEquals(listOf(file),session.attachments);assertEquals(firstSettings,session.generationSettings);assertNull(session.error)
        ui.onNodeWithTag("draft-attachment-${file.manifest.id}").assertIsDisplayed()
        var actual=byteArrayOf();main { actual=NativeDraftFiles(NativeSessionStorage(ui.activity)).bytes(file.manifest) };assertArrayEquals(bytes,actual)
        assertEquals(0,JSONObject(first.intent).getJSONArray("attachments").length());assertEquals(originalText,JSONObject(first.intent).getString("text"))
    }
    private fun currentUnedited(observer: Boolean) {
        val first=original(observer);finish(first);ui.waitUntil(10_000) { session.selectedThread=="created-original" }
        assertEquals("",session.text);assertTrue(session.attachments.isEmpty());assertNull(session.error)
    }
    @After fun release() { responseGate.complete(Unit);ui.runOnIdle { session.leaveForeground() } }
    @Test fun initialConfirmationPreservesLaterEditedText()=newerText(false,false)
    @Test fun initialConfirmationPreservesReenteredIdenticalTextAsANewDraft()=newerText(false,true)
    @Test fun initialCurrentUneditedConfirmationStillNavigatesAndReleasesDraft()=currentUnedited(false)
    @Test fun passiveConfirmationPreservesLaterTextFileAndSettings()=newerFile(false)
    @Test fun passiveConfirmationPreservesFileOnlyDraftAndSettings()=newerFile(true)
    @Test fun passiveConfirmationPreservesReenteredIdenticalTextAsANewDraft()=newerText(true,true)
    @Test fun passiveCurrentUneditedConfirmationStillNavigatesAndReleasesDraft()=currentUnedited(true)
}
