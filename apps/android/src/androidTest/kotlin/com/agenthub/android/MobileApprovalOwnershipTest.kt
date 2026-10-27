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

/** Native UI and session with held synthetic approval answers; no model or execution service. */
class MobileApprovalOwnershipTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val scope=TargetScope("codex",null,null,"approval-ownership-fixture-host","fixture-workspace",java.util.UUID.randomUUID().toString())
    private val gates=mutableMapOf<String,CompletableDeferred<Unit>>()
    private val calls=mutableListOf<Triple<String,String,String>>()
    private val finished=mutableSetOf<String>()
    private var approvalTurn="original-turn"
    private var requestId="reused-request-id"
    private var projectionRevision=0
    private var sourceItemId: String?=null
    private var notificationRequestId: String?=null
    private fun gateKey(turn: String,id: String)=if(id=="reused-request-id")turn else "$turn/$id"
    private var runtimeTurn="original-turn"
    private var question=false
    private var async=false
    private var historyReads=0
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup(isQuestion: Boolean=false,isAsync: Boolean=false) {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        question=isQuestion;async=isAsync;gates[approvalTurn]=CompletableDeferred()
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Synthetic approval ownership").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("approval-a","approval-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","waiting-for-approval") }))
                .put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1","models:read"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                historyReads++
                val request=JSONObject().put("id",requestId).put("threadId",threadId).put("turnId",approvalTurn)
                    .put("kind",if(question)"question" else "command").put("title","Synthetic request $approvalTurn/$projectionRevision").put("detail","Only the original request may own its pending state")
                    .put("decisions",JSONArray(if(question)emptyList() else listOf("decline")))
                    .put("questions",if(question)JSONArray().put(JSONObject().put("id","q").put("question","Choose for $approvalTurn/$projectionRevision").put("multiple",false).put("custom",false).put("isSecret",false)
                        .put("options",JSONArray().put(JSONObject().put("label","One").put("description","Synthetic original choice")))) else JSONArray())
                if(async)request.put("interactionMode","async")
                sourceItemId?.let { request.put("sourceItemId",it) }
                notificationRequestId?.let { request.put("notificationRequestId",it) }
                return HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                    .put("messages",JSONArray()).put("runtime",JSONObject().put("state","waiting-for-approval").put("turnId",runtimeTurn).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray().put(request)).put("receipts",JSONArray()).put("canControl",true)).toString())
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("high"),"standard",listOf("standard")))
            override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) {
                assertEquals(scope.hostId,client.id);assertEquals("approval-a",threadId);assertEquals(requestId,approvalId)
                if(question) { assertEquals("",decision);assertEquals(mapOf("q" to listOf("One")),answers) } else assertEquals("decline",decision)
                calls.add(Triple(threadId,turnId,approvalId))
                try { gates.getValue(gateKey(turnId,approvalId)).await() } finally { finished.add(turnId) }
            }
        }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"answer");session.resetAcceptance(reads,commands) }
        main { session.connect("Synthetic approval ownership fixture","https://fixture.invalid","") }
        ui.waitUntil(5_000) { !session.loading&&session.threads.size==2&&session.models.isNotEmpty() }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime?.approvals?.size==1 }
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun submit() {
        if(question) { ui.onNodeWithTag("question-option-q-One").performScrollTo().assertIsEnabled().performClick();ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled().performClick() }
        else ui.onNodeWithTag("approval-decline").performScrollTo().assertIsEnabled().performClick()
    }
    private fun newerTurn() {
        ui.runOnIdle { approvalTurn="newer-turn";runtimeTurn=approvalTurn;gates[approvalTurn]=CompletableDeferred();session.loadHistory(userInitiated=true) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single().turnId=="newer-turn" }
    }
    @After fun release() { ui.runOnIdle { if(::owned.isInitialized) { gates.values.forEach { it.complete(Unit) };session.leaveForeground();owned.verifyDefault() } } }
    @Test fun oldPendingAnswerCannotDisableANewerTurnQuestionWithTheSameRequestId() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 };newerTurn()
        ui.onNodeWithTag("question-option-q-One").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist();submit()
        ui.waitUntil(5_000) { calls.size==2 };assertEquals(listOf("original-turn","newer-turn"),calls.map { it.second })
    }
    @Test fun oldCompletionCannotUnlockTheNewerTurnAnswer() {
        setup();submit();ui.waitUntil(5_000) { calls.size==1 };newerTurn();submit();ui.waitUntil(5_000) { calls.size==2 }
        ui.runOnIdle { gates.getValue("original-turn").complete(Unit) };ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        ui.onNodeWithTag("approval-pending-progress").assertExists();ui.onNodeWithTag("approval-decline").assertIsNotEnabled();owned.nodeShot(ui.onNodeWithTag("approval-pending-progress").onParent(),"pending-original-request")
        ui.runOnIdle { val current=session.runtime!!.approvals.single();session.answer(current,"decline",emptyMap(),session.approvalActionDestination()) };assertEquals(2,calls.size)
        ui.runOnIdle { gates.getValue("newer-turn").complete(Unit) };ui.waitUntil(5_000) { "newer-turn" in finished&&!session.isAnswering(session.runtime!!.approvals.single()) }
        ui.onNodeWithTag("approval-decline").assertIsEnabled()
    }
    @Test fun oldFailureCannotRefreshOrOverwriteTheNewerRequest() {
        setup();submit();ui.waitUntil(5_000) { calls.size==1 };newerTurn();val reads=historyReads
        ui.runOnIdle { session.text="Keep newer local draft";session.error="Newer independent error";gates.getValue("original-turn").completeExceptionally(IllegalStateException("Old answer failure")) }
        ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        assertEquals(reads,historyReads);assertEquals("Newer independent error",session.error);assertEquals("Keep newer local draft",session.text)
        ui.onNodeWithTag("approval-decline").assertIsEnabled();assertEquals(1,calls.size)
    }
    @Test fun sameOriginalRequestRemainsDeduplicatedAcrossNavigationRoundTrip() {
        setup();submit();ui.waitUntil(5_000) { calls.size==1 }
        ui.runOnIdle { val original=session.threads.first();session.selectThread(session.threads.last());session.selectThread(original) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single().threadId=="approval-a" }
        ui.onNodeWithTag("approval-decline").performScrollTo().assertIsNotEnabled()
        ui.runOnIdle { val current=session.runtime!!.approvals.single();session.answer(current,"decline",emptyMap(),session.approvalActionDestination()) };assertEquals(1,calls.size)
        val reads=historyReads;ui.runOnIdle { gates.getValue("original-turn").complete(Unit) };ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle();assertEquals(reads,historyReads)
    }
    @Test fun activeTurnAdvanceDoesNotUnlockTheSameOriginalAsyncQuestion() {
        setup(true,true)
        val original=session.runtime!!.approvals.single()
        ui.runOnIdle { session.answer(original,"",mapOf("q" to listOf("One")),session.approvalActionDestination()) };ui.waitUntil(5_000) { calls.size==1 }
        ui.runOnIdle { runtimeTurn="newer-active-task";session.loadHistory(userInitiated=true) };ui.waitUntil(5_000) { !session.historyLoading&&session.runtime?.turnId=="newer-active-task" }
        assertTrue(session.isAnswering(original));assertFalse(session.canAnswer(original));ui.runOnIdle { session.answer(original,"",mapOf("q" to listOf("One")),session.approvalActionDestination()) };assertEquals(1,calls.size)
    }
    @Test fun oldQuestionCompletionCannotEnableTheNewerQuestionFields() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 };newerTurn();submit();ui.waitUntil(5_000) { calls.size==2 }
        ui.runOnIdle { gates.getValue("original-turn").complete(Unit) };ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        ui.onNodeWithTag("question-option-q-One").assertIsNotEnabled();ui.onNodeWithTag("answer-question").assertIsNotEnabled()
        ui.onNodeWithTag("approval-pending-progress").assertExists();owned.nodeShot(ui.onNodeWithTag("answer-question").onParent(),"newer-question-remains-pending")
        assertEquals(listOf("original-turn","newer-turn"),calls.map { it.second })
        ui.runOnIdle { gates.getValue("newer-turn").complete(Unit) };ui.waitUntil(5_000) { "newer-turn" in finished&&!session.isAnswering(session.runtime!!.approvals.single()) }
    }
    @Test fun submittedQuestionAnswerCopiesMutableCallerChoicesBeforeDispatch() {
        setup(true)
        val choices=mutableListOf("One");val answers=mutableMapOf("q" to choices)
        ui.runOnIdle {
            val original=session.runtime!!.approvals.single()
            session.answer(original,"",answers,session.approvalActionDestination())
            choices[0]="Later caller replacement";answers.clear()
        }
        ui.waitUntil(5_000) { calls.size==1 };assertEquals(listOf("original-turn"),calls.map { it.second })
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertExists()
        ui.runOnIdle { gates.getValue("original-turn").complete(Unit) };ui.waitUntil(5_000) { "original-turn" in finished }
    }


    private fun replaceRequestInSameTurn() {
        ui.runOnIdle { requestId="replacement-request-id";gates[gateKey(approvalTurn,requestId)]=CompletableDeferred();session.loadHistory(userInitiated=true) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single().id==requestId }
    }
    @Test fun oldCompletionCannotUnlockANewRequestInTheSameTurn() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 };replaceRequestInSameTurn()
        ui.onNodeWithTag("question-option-q-One").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist()
        submit();ui.waitUntil(5_000) { calls.size==2 }
        ui.runOnIdle { gates.getValue("original-turn").complete(Unit) };ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        ui.onNodeWithTag("question-option-q-One").assertIsNotEnabled();ui.onNodeWithTag("answer-question").assertIsNotEnabled()
        ui.onNodeWithTag("approval-pending-progress").assertExists();assertEquals(listOf("reused-request-id","replacement-request-id"),calls.map { it.third })
        owned.nodeShot(ui.onNodeWithTag("answer-question").onParent(),"new-id-same-turn-keeps-pending")
        ui.runOnIdle { gates.getValue(gateKey(approvalTurn,requestId)).complete(Unit) }
        ui.waitUntil(5_000) { !session.isAnswering(session.runtime!!.approvals.single()) }
    }
    @Test fun oldFailureCannotAffectANewRequestInTheSameTurn() {
        setup();submit();ui.waitUntil(5_000) { calls.size==1 };replaceRequestInSameTurn();submit();ui.waitUntil(5_000) { calls.size==2 }
        val reads=historyReads
        ui.runOnIdle { session.text="Keep replacement draft";session.error="Replacement independent error";gates.getValue("original-turn").completeExceptionally(IllegalStateException("Retired request failure")) }
        ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        assertEquals(reads,historyReads);assertEquals("Keep replacement draft",session.text);assertEquals("Replacement independent error",session.error)
        ui.onNodeWithTag("approval-pending-progress").assertExists();ui.onNodeWithTag("approval-decline").assertIsNotEnabled();assertEquals(2,calls.size)
        owned.nodeShot(ui.onNodeWithTag("approval-pending-progress").onParent(),"new-id-same-turn-old-error-ignored")
    }
    @Test fun oldFailureAfterNavigationCannotOverwriteTheReturnedQuestion() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 }
        ui.runOnIdle { val a=session.threads.first();session.selectThread(session.threads.last());session.selectThread(a) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single().threadId=="approval-a" }
        val reads=historyReads
        ui.runOnIdle { session.text="Returned local draft";session.error="Returned independent error";gates.getValue("original-turn").completeExceptionally(IllegalStateException("Old navigation failure")) }
        ui.waitUntil(5_000) { "original-turn" in finished };ui.waitForIdle()
        assertEquals(reads,historyReads);assertEquals("Returned local draft",session.text);assertEquals("Returned independent error",session.error)
        ui.onNodeWithTag("question-option-q-One").assertIsEnabled();assertEquals(1,calls.size)
    }
    @Test fun changedProjectionKeepsWireDeduplicationWithoutAcquiringOldCallbackAuthority() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 };val original=session.runtime!!.approvals.single()
        ui.runOnIdle { projectionRevision++;session.loadHistory(userInitiated=true) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single()!=original }
        val replacement=session.runtime!!.approvals.single();assertEquals(original.id,replacement.id);assertEquals(original.turnId,replacement.turnId)
        ui.runOnIdle { session.answer(replacement,"",mapOf("q" to listOf("One")),session.approvalActionDestination()) }
        assertEquals("Same remote identity must not authorize another in-flight write",1,calls.size)
        val reads=historyReads
        ui.runOnIdle { session.error="Projection independent error";gates.getValue("original-turn").completeExceptionally(IllegalStateException("Old projection failure")) }
        ui.waitUntil(5_000) { "original-turn" in finished&&!session.isAnswering(replacement) };ui.waitForIdle()
        assertEquals(reads,historyReads);assertEquals("Projection independent error",session.error);assertEquals(1,calls.size)
        ui.onNodeWithTag("question-option-q-One").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled()
    }

    @Test fun sourceIdentifiersCannotGrantAnOldProjectionCallbackAuthority() {
        setup(true);submit();ui.waitUntil(5_000) { calls.size==1 };val original=session.runtime!!.approvals.single()
        ui.runOnIdle { sourceItemId="replacement-source-item";notificationRequestId="replacement-notification";session.loadHistory(userInitiated=true) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!!.approvals.single().sourceItemId==sourceItemId }
        val replacement=session.runtime!!.approvals.single()
        assertEquals(notificationRequestId,replacement.notificationRequestId);assertNotEquals(original,replacement)
        assertFalse(session.canEditApproval(original));assertTrue(session.isAnswering(replacement))
        ui.runOnIdle { session.answer(replacement,"",mapOf("q" to listOf("One")),session.approvalActionDestination()) }
        assertEquals("Projection metadata never grants another in-flight wire write",1,calls.size)
        val reads=historyReads
        ui.runOnIdle { session.text="Independent current draft";session.error="Independent current error";gates.getValue("original-turn").completeExceptionally(IllegalStateException("Old source callback")) }
        ui.waitUntil(5_000) { "original-turn" in finished&&!session.isAnswering(replacement) };ui.waitForIdle()
        assertEquals(reads,historyReads);assertEquals("Independent current error",session.error);assertEquals("Independent current draft",session.text)
        ui.onNodeWithTag("question-option-q-One").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled()
        owned.nodeShot(ui.onNodeWithTag("answer-question").onParent(),"source-identifiers-old-callback-fenced")
    }

}
