package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native UI, HTTP and Keystore storage with synthetic computer responses. */
@RunWith(AndroidJUnit4::class)
class MobileExecutionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() { ui.runOnIdle { session.resetAcceptance() } }
    private fun connect(fixture: ExecutionFixture,thread: Boolean=true) {
        main { session.connect("执行验收电脑",fixture.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading && session.hasClient }; ui.waitUntil(10_000) { session.operationRecords.isEmpty() }; hideKeyboard()
        if(thread) { ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(10_000) { !session.historyLoading && session.runtime!=null } }
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun shot(name: String,keepKeyboard: Boolean=false) {
        if(!keepKeyboard) hideKeyboard()
        ui.waitForIdle(); val i=InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync(); i.uiAutomation.waitForIdle(1000,10_000)
        val frames=CountDownLatch(1); ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS))
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4").apply { mkdirs() }; val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun hideKeyboard() {
        // Modal questions own a separate window; close the actual focused IME.
        ui.waitForIdle()
        if(androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
        ui.waitForIdle()
    }
    private fun sent(f: ExecutionFixture)=f.server.requests.filter { it.path=="/v1/replies" }.map { JSONObject(String(it.body,Charsets.UTF_8)) }

    @Test fun nativeSendPreservesSettingsAndClearsOnlyItsDraft() {
        ExecutionFixture().use { f -> connect(f)
            ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","guardian-approvals","fast","plan")); session.text="  中文 😀\n" }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null && session.text.isEmpty() }
            val request=sent(f).single(); assertEquals("  中文 😀\n",request.getString("text")); assertEquals("high",request.getString("effort")); assertEquals("guardian-approvals",request.getString("permissionMode")); assertEquals("plan",request.getString("collaborationMode")); assertEquals("next-turn",request.getString("mode"))
            assertTrue(f.server.requests.all { it.method=="GET" || it.path=="/v1/replies" })
            shot("native-send-settings")
        }
    }
    @Test fun selectingThreadDuringWorkspaceRefreshKeepsSameClientControlReady() {
        ExecutionFixture().use { fixture ->
            val native=NativeHostReads { "" }
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            var calls=0
            val reader=object: HostReads by native {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    calls++
                    if(calls==2) { started.complete(Unit);release.await() }
                    return native.workspace(client,projectCursor,threadCursor)
                }
            }
            ui.runOnIdle { session.resetAcceptance(reader) }
            try {
                main { session.connect("workspace selection race",fixture.server.endpoint,"") }
                runBlocking { withTimeout(10_000) { started.await() } }
                ui.runOnIdle { session.selectThread(session.threads.first()) }
                release.complete(Unit)
                ui.waitUntil(10_000) { !session.loading && !session.historyLoading && session.runtime?.canControl==true }
                assertTrue("A same-client workspace remains fresh after selecting its thread",session.canCompose)
                assertEquals(2,calls)
                assertTrue(fixture.server.requests.all { it.method=="GET" })
            } finally { release.complete(Unit) }
        }
    }
    @Test fun stagedProjectlessCreationUploadsExactMixedBytesOnce() {
        ExecutionFixture(staged=true).use { f -> connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="首条消息" }
            val one="中文\n".toByteArray(); val two=byteArrayOf(0,1,2,127,-1)
            main { session.addAttachment("说明.txt","text/plain",one); session.addAttachment("内容.bin","application/octet-stream",two) }
            shot("staged-mixed-draft")
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.selectedThread=="created-thread" && session.transferStatus==null && session.text.isEmpty() }
            val prepare=f.server.requests.single { it.path=="/v1/desktop/creations" && it.method=="POST" }
            val intent=JSONObject(String(prepare.body,Charsets.UTF_8)); assertTrue(intent.isNull("projectId")); assertEquals("首条消息",intent.getString("text")); assertEquals(2,intent.getJSONArray("attachments").length())
            val uploads=f.server.requests.filter { it.path=="/v1/desktop/creation-attachments" }; assertEquals(2,uploads.size); assertArrayEquals(one,uploads[0].body); assertArrayEquals(two,uploads[1].body)
            assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); assertTrue(sent(f).isEmpty()); shot("staged-mixed-confirmed")
        }
    }
    @Test fun uncertainReplyQueryDoesNotReplayOrClearEditedBackDraft() {
        ExecutionFixture().use { f -> f.loseReply=true; f.queryState="uncertain"; connect(f)
            ui.runOnIdle { session.text="原内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="reply" && it.phase=="uncertain" } && session.transferStatus==null }
            val record=session.operationRecords.first { it.kind=="reply" }; val id=record.id
            ui.runOnIdle { session.text="修改内容"; session.text="原内容" }; shot("reply-uncertain-preserved")
            f.queryState="submitted"
            ui.runOnIdle { session.queryOperation(record) }; ui.waitUntil(10_000) { session.operationRecords.any { it.id==id && it.phase=="submitted" } && session.transferStatus==null }
            assertEquals("原内容",session.text); assertEquals(1,sent(f).size); assertTrue(f.server.requests.filter { it.path=="/v1/receipts" }.all { it.query["id"]==id }); shot("late-reply-keeps-new-version")
        }
    }
    @Test fun detachedLateCreationKeepsNewDraftAndOriginalManifest() {
        ExecutionFixture(staged=true).use { f -> f.submitPhase="outcome-unknown"; connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="第一份内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="creation" && it.phase=="outcome-unknown" } && session.transferStatus==null }
            val original=session.operationRecords.first { it.kind=="creation" }
            ui.runOnIdle { session.detachOperation(original) }; ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.detached } }
            ui.runOnIdle { session.text="新的可编辑草稿" }; f.submitPhase="confirmed"
            ui.runOnIdle { session.queryOperation(session.operationRecords.first { it.id==original.id }) }
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.phase=="confirmed" } && session.transferStatus==null }
            assertEquals("",session.selectedThread); assertEquals("新的可编辑草稿",session.text); assertEquals("第一份内容",JSONObject(session.operationRecords.first { it.id==original.id }.intent).getString("text")); assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" })
            ui.onNodeWithTag("operation-${original.id}").assertDoesNotExist(); ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("query-detached-${original.id}").assertExists();ui.onNodeWithTag("query-detached-${original.id}").performClick()
            ui.waitUntil(10_000) { session.transferStatus==null };shot("detached-creation-keeps-draft")
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="fixture-thread" }) };ui.waitUntil(10_000) { !session.historyLoading }
            assertTrue(session.detachedCreations.isEmpty());ui.onNodeWithTag("operation-${original.id}").assertDoesNotExist()
        }
    }
    @Test fun desktopOwnerIsOpenedBeforeReplyAndHistoryCannotPretendControl() {
        ExecutionFixture(controlled=false).use { f -> f.openingQueries=3; connect(f)
            assertFalse(session.runtime!!.canControl==true); ui.runOnIdle { session.text="主动打开后回复" }; ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            val paths=f.server.requests.filter { it.method=="POST" }.map { it.path }; assertEquals(listOf("/v1/desktop/open","/v1/replies"),paths)
            assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/open" })
            val status=f.server.requests.filter { it.path=="/v1/desktop/open-status" }
            assertEquals(3,status.size); assertEquals(1,status.map { it.query["id"] }.distinct().size)
            assertTrue(status.all { it.method=="GET" && it.query["threadId"]=="fixture-thread" }); shot("desktop-open-before-reply")
            ui.runOnIdle { session.leaveForeground(); session.text="离线草稿" }; ui.onNodeWithTag("send-message").assertIsNotEnabled()
            assertEquals(1,sent(f).size); shot("cached-control-cannot-write")
        }
    }
    @Test fun queueSteerStopAndCancellationUseExactCurrentTurn() {
        ExecutionFixture(streaming=true).use { f -> f.state="running"; f.turn="fixture-turn"; connect(f)
            ui.runOnIdle { session.text="排队内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }; assertEquals("queue",sent(f)[0].getString("mode")); assertEquals("fixture-turn",sent(f)[0].getString("expectedTurnId"))
            ui.waitUntil(10_000) { session.queuedReplies.isNotEmpty() && !session.historyLoading }; val queued=session.queuedReplies.first(); ui.runOnIdle { session.cancelQueue(queued) }; ui.waitUntil(10_000) { session.transferStatus==null && f.server.requests.any { it.path=="/v1/queue/cancel" } }; assertEquals(sent(f)[0].getString("clientMessageId"),JSONObject(String(f.server.requests.first { it.path=="/v1/queue/cancel" }.body)).getString("clientMessageId"))
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==queued.operationId && it.phase=="cancelled" } && session.canCompose }
            assertTrue(session.activeOperationRecords.none { it.id==queued.operationId });assertTrue(session.selectedClient.online)
            ui.runOnIdle { session.setReplyMode("steer"); session.text="调整当前任务" }; ui.waitUntil(10_000) { session.decision.enabled && session.decision.action=="send" }; ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==2 && session.transferStatus==null }; assertEquals("steer",sent(f)[1].getString("mode")); assertFalse(sent(f)[1].has("model")); assertFalse(session.canConfigureGeneration)
            ui.runOnIdle { session.text="" }; ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { f.server.requests.any { it.path=="/v1/stop" } && !session.running }; assertEquals("fixture-turn",JSONObject(String(f.server.requests.first { it.path=="/v1/stop" }.body)).getString("turnId"))
            val interruptedId=sent(f)[1].getString("clientMessageId")
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==interruptedId && it.phase=="interrupted" } }
            assertTrue(session.activeOperationRecords.none { it.id==interruptedId });ui.onNodeWithTag("operation-$interruptedId").assertDoesNotExist()
            shot("queue-steer-stop")
        }
    }
    @Test fun asyncQuestionRequiresAnAnswerAndSupportsSecretCustomReply() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); connect(f)
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("question-option-q-One").performClick()
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("合成秘密回复")
            shot("async-secret-question",keepKeyboard=true)
            val field=ui.onNodeWithTag("question-custom-q").fetchSemanticsNode().boundsInRoot
            val screen=android.util.DisplayMetrics().also { ui.activity.windowManager.defaultDisplay.getRealMetrics(it) }
            val ime=androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            assertTrue("Focused question must stay fully above the native keyboard",field.bottom<=screen.heightPixels-ime+2)
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { f.server.requests.any { it.path=="/v1/approvals" } }
            val request=JSONObject(String(f.server.requests.first { it.path=="/v1/approvals" }.body)); assertEquals("approval-id",request.getString("approvalId")); assertEquals(listOf("合成秘密回复"),request.getJSONObject("answers").getJSONArray("q").strings())
            assertEquals("fixture-thread",request.getString("threadId")); assertEquals("approval-turn",request.getString("turnId"))
        }
    }
    @Test fun asyncDeckPreservesDraftsAndAdvancesOnlyTheResolvedQuestion() {
        ExecutionFixture().use { f ->
            f.approvals=JSONArray().put(question("first","q1",false)).put(question("second","q2",false)).put(question("third","q3",false)); connect(f)
            ui.onNodeWithTag("async-question-sheet").assertIsDisplayed()
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 3")
            ui.onNodeWithTag("async-question-prev").assertIsNotEnabled()
            ui.onNodeWithTag("question-option-q1-One").performClick()
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("question-custom-q2").performScrollTo().performTextInput("第二题草稿")
            hideKeyboard(); ui.onNodeWithTag("async-question-prev").performClick()
            ui.onNodeWithTag("question-option-q1-One").assertTextContains("● One")
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.onNodeWithTag("open-async-questions").performClick()
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("question-custom-q2").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("第二题草稿")))
            shot("async-deck-retained-draft")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.size==2 && !session.isAnswering(session.runtime!!.approvals.first()) }
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
            ui.onNodeWithTag("question-title-q3").assertIsDisplayed(); shot("async-deck-advanced-after-answer")
            ui.onNodeWithTag("skip-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.size==1 }
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 1")
            ui.onNodeWithTag("question-option-q1-One").assertTextContains("● One")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.isEmpty()==true }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            val requests=f.server.requests.filter { it.path=="/v1/approvals" }.map { JSONObject(String(it.body)) }
            assertEquals(listOf("second","third","first"),requests.map { it.getString("approvalId") })
            assertEquals(listOf("第二题草稿"),requests[0].getJSONObject("answers").getJSONArray("q2").strings())
            assertEquals("skip",requests[1].getString("decision"))
            assertEquals(listOf("One"),requests[2].getJSONObject("answers").getJSONArray("q1").strings())
        }
    }
    @Test fun asyncDraftCannotSurviveAnApprovalTurnOrTargetReplacement() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); connect(f)
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("旧任务秘密")
            hideKeyboard(); f.approvals=JSONArray().put(question().put("turnId","new-approval-turn"))
            ui.runOnIdle { session.loadHistory() }; ui.waitUntil(10_000) { session.runtime?.approvals?.singleOrNull()?.turnId=="new-approval-turn" }
            ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("新任务草稿")
            hideKeyboard(); ui.runOnIdle { session.newChat() }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(10_000) { !session.historyLoading && session.runtime?.approvals?.isNotEmpty()==true }
            ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            ui.runOnIdle { session.changeAppearance("dark") }
            assertTrue(f.server.requests.none { it.path=="/v1/approvals" }); shot("async-deck-target-and-turn-isolated")
        }
    }
    @Test fun planActionCarriesExpectedPlanWithoutClearingComposerDraft() {
        ExecutionFixture().use { f -> f.proposed=JSONObject().put("id","plan-id").put("turnId","old-turn").put("text","合成计划正文"); connect(f)
            ui.runOnIdle { session.text="另一份草稿" }
            ui.onNodeWithTag("implement-plan").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            assertEquals("PLEASE IMPLEMENT THIS PLAN:\n合成计划正文",sent(f).single().getString("text")); assertEquals("plan-id",sent(f).single().getString("expectedPlanId")); assertEquals("default",sent(f).single().getString("collaborationMode")); assertEquals("另一份草稿",session.text); shot("plan-preserves-composer-draft")
        }
    }
    @Test fun runtimeChangeDuringFreshAuthorityCheckProducesNoCommand() {
        ExecutionFixture().use { f -> connect(f); f.runtimeId="different-runtime"
            ui.runOnIdle { session.text="目标已变化" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.error!=null && session.transferStatus==null }
            assertTrue(f.server.requests.none { it.method=="POST" }); assertEquals("目标已变化",session.text); shot("runtime-change-refuses-command")
        }
    }
    @Test fun endingActiveQueryDoesNotReleaseOrReplaceANewerSubmit() {
        ExecutionFixture(staged=true).use { f -> f.submitPhase="outcome-unknown"; connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="旧提交" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="creation" && it.phase=="outcome-unknown" } && session.transferStatus==null }
            val original=session.operationRecords.first { it.kind=="creation" }; f.blockedOperation=original.id
            ui.runOnIdle { session.queryOperation(original) }; assertTrue(f.queryStarted.await(5,TimeUnit.SECONDS))
            ui.runOnIdle { session.detachOperation(original) }; ui.waitUntil(10_000) { session.transferStatus==null && session.operationRecords.any { it.id==original.id && it.detached } }
            ui.runOnIdle { session.text="新提交" }; ui.waitUntil(10_000) { session.decision.enabled }; ui.onNodeWithTag("send-message").performClick()
            assertTrue(f.secondSubmitStarted.await(5,TimeUnit.SECONDS)); f.queryRelease.countDown()
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.detached && it.phase=="confirmed" } }
            assertNotNull(session.transferStatus); assertEquals("新提交",session.text); assertEquals("",session.selectedThread); assertNull(session.error)
            assertEquals(2,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); shot("late-query-cannot-release-new-submit")
            f.secondSubmitRelease.countDown(); ui.waitUntil(10_000) { session.transferStatus==null }
        }
    }
    @Test fun generationSheetDefaultsFollowRuntimeAndStayWithinFullClient() {
        ExecutionFixture().use { f -> connect(f)
            ui.waitUntil(10_000) { session.models.isNotEmpty() }
            ui.onNodeWithTag("generation-settings").performClick()
            ui.onNodeWithTag("sheet-generation").assertIsDisplayed()
            ui.onNodeWithTag("model-fixture-model").performClick()
            ui.onNodeWithTag("effort-high").performScrollTo().performClick()
            ui.onNodeWithTag("speed-fast").performScrollTo().performClick()
            ui.onNodeWithTag("permission-guardian-approvals").performScrollTo().performClick()
            shot("generation-settings-permissions")
            ui.onNodeWithTag("sheet-done").performClick()
            ui.runOnIdle { session.newChat() }
            assertEquals(GenerationSettings("fixture-model","high","guardian-approvals","fast"),session.generationSettings)
            ui.runOnIdle { session.text=" "; session.newChat() }
            assertEquals(" ",session.text)
            assertEquals("fixture-model",session.generationSettings.model)
            ExecutionFixture().use { other -> connect(other,false); ui.runOnIdle { session.newChat() }; assertEquals(GenerationSettings(),session.generationSettings) }
            shot("generation-defaults-isolated")
        }
    }
    @Test fun delayedPickerResultCannotAttachToAnotherSelection() {
        ExecutionFixture().use { f -> connect(f)
            val original=session.attachmentDestination()
            ui.runOnIdle { session.newChat(); session.text="新位置草稿" }
            main { session.addAttachment("旧选择.txt","text/plain","old".toByteArray(),original) }
            assertTrue(session.attachments.isEmpty()); assertEquals("新位置草稿",session.text)
            val current=session.attachmentDestination(); ui.runOnIdle { session.text="当前编辑" }
            main { session.addAttachment("正确选择.txt","text/plain","exact".toByteArray(),current) }
            assertEquals("正确选择.txt",session.attachments.single().manifest.name); assertEquals("当前编辑",session.text); shot("picker-target-isolated")
        }
    }
    @Test fun cachedQuestionAndPlanCannotSendAfterConnectionBecomesStale() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); f.proposed=JSONObject().put("id","plan-id").put("turnId","old-turn").put("text","旧计划"); connect(f)
            ui.runOnIdle { session.leaveForeground() }
            assertFalse(session.canAnswer(session.runtime!!.approvals.single())); assertFalse(session.canRespondToPlan(session.runtime!!.proposedPlan!!))
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("implement-plan").assertIsNotEnabled()
            assertTrue(f.server.requests.none { it.method=="POST" }); shot("cached-approval-plan-disabled")
        }
    }
    @Test fun eventsStreamRepairAndReadinessNeverGrantCachedControl() {
        ExecutionFixture(streaming=true).use { f -> connect(f)
            f.streamText="第一段流式正文"; f.eventCursor=1
            ui.waitUntil(10_000) { session.messages.any { it.text=="第一段流式正文" } }; shot("streaming-partial-native")
            f.streamText="完整流式正文"; f.eventCursor=2
            ui.waitUntil(10_000) { session.messages.any { it.text=="完整流式正文" } }; shot("streaming-complete-native")
            f.eventReady=false
            ui.waitUntil(10_000) { !session.canCompose }; ui.runOnIdle { session.text="断线草稿" }; ui.onNodeWithTag("send-message").assertIsNotEnabled()
            f.eventReady=true; f.eventCursor=3
            ui.waitUntil(10_000) { f.server.requests.count { it.path=="/v1/events" }>=4 }
            assertFalse(session.canCompose)
            f.eventEpoch="restarted-epoch"; f.eventCursor=1
            ui.waitUntil(10_000) { session.canCompose && !session.historyLoading && f.server.requests.count { it.path=="/v1/history" }>=2 }
            assertEquals("断线草稿",session.text); assertTrue(f.server.requests.none { it.method=="POST" }); shot("event-repair-preserves-draft")
            ui.runOnIdle { session.leaveForeground() }; assertFalse(session.canCompose)
        }
    }
    @Test fun emptyProjectFirstInputRetainsExactProjectAndSettings() {
        ExecutionFixture(staged=true).use { f -> connect(f,false)
            ui.runOnIdle { session.newChat("empty-project"); session.text="空项目首条"; session.configure(GenerationSettings("fixture-model","high","read-only","standard")) }
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.selectedThread=="created-thread" && session.transferStatus==null }
            val data=JSONObject(String(f.server.requests.single { it.path=="/v1/desktop/creations" && it.method=="POST" }.body))
            assertEquals("empty-project",data.getString("projectId")); assertEquals("high",data.getString("effort")); assertEquals("空项目首条",data.getString("text"))
            assertEquals("empty-project",session.threads.first { it.id==session.selectedThread }.projectId); assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); shot("empty-project-first-input")
        }
    }
    @Test fun explicitRestartUsesNewOperationAndPreservesComposer() {
        ExecutionFixture().use { f -> f.state="interrupted"; connect(f)
            ui.onNodeWithTag("resume-task").performTouchInput { longClick() }; ui.onNodeWithTag("action-restart").performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            assertEquals(com.agenthub.core.MobileRules.runAgainText(true),sent(f).single().getString("text")); assertEquals("next-turn",sent(f).single().getString("mode")); assertFalse(sent(f).single().has("model"))
            f.state="idle";ui.runOnIdle { session.loadHistory(userInitiated=true) }
            ui.waitUntil(10_000) { !session.historyLoading && session.decision.action=="send" && session.canRunAgain }
            ui.onNodeWithTag("send-message").assertIsNotEnabled()
            ui.onNodeWithTag("send-message").performTouchInput { longClick() };ui.onNodeWithTag("action-restart").assertIsDisplayed().performClick()
            ui.waitUntil(10_000) { sent(f).size==2 && session.transferStatus==null }
            assertNotEquals(sent(f)[0].getString("clientMessageId"),sent(f)[1].getString("clientMessageId"))
            assertEquals(com.agenthub.core.MobileRules.runAgainText(true),sent(f)[1].getString("text"))
            shot("explicit-restart-new-operation")
        }
    }
    private fun question(id: String="approval-id",questionId: String="q",secret: Boolean=true)=JSONObject().put("id",id).put("threadId","fixture-thread").put("turnId","approval-turn").put("kind","question").put("title","合成问题").put("detail","").put("interactionMode","async").put("decisions",JSONArray()).put("questions",JSONArray().put(JSONObject().put("id",questionId).put("question","请选择或输入回复").put("custom",true).put("isSecret",secret).put("options",JSONArray().put(JSONObject().put("label","One").put("description","选项说明")))))
}

private class ExecutionFixture(val staged: Boolean=false,controlled: Boolean=true,val streaming: Boolean=false): java.io.Closeable {
    @Volatile var controlled=controlled
    @Volatile var openingQueries=0
    private var opening: JSONObject?=null
    @Volatile var runtimeId="fixture-runtime-"+UUID.randomUUID().toString()
    @Volatile var state="idle"
    @Volatile var turn: String?=null
    @Volatile var loseReply=false
    @Volatile var queryState="submitted"
    @Volatile var submitPhase="confirmed"
    @Volatile var approvals=JSONArray()
    @Volatile var proposed: JSONObject?=null
    @Volatile var streamText: String?=null
    @Volatile var eventEpoch="fixture-epoch"
    @Volatile var eventCursor=0L
    @Volatile var eventReady=true
    private var reply: JSONObject?=null
    private var cancelledReply: JSONObject?=null
    @Volatile private var creation: JSONObject?=null
    private val creations=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
    @Volatile var blockedOperation: String?=null
    val queryStarted=CountDownLatch(1); val queryRelease=CountDownLatch(1)
    val secondSubmitStarted=CountDownLatch(1); val secondSubmitRelease=CountDownLatch(1)
    private val uploaded=mutableListOf<String>()
    val server=CommandFixture(::response)
    private fun target()=JSONObject().put("agentId","codex").put("hostId","fixture-host").put("workspaceId","fixture-workspace").put("runtimeId",runtimeId)
    private fun thread(id: String="fixture-thread")=JSONObject().put("id",id).put("hostId","fixture-host").put("title",if(id=="created-thread") "新建合成聊天" else "合成聊天").put("state",state).put("projectId",if(id=="created-thread") creation?.get("projectId") ?: JSONObject.NULL else JSONObject.NULL)
    private fun workspace()=JSONObject().put("hostId","fixture-host").put("name","合成电脑").put("connectionMode","desktop-runtime").put("readTarget",target()).put("projects",JSONArray().put(JSONObject().put("id","empty-project").put("hostId","fixture-host").put("name","空项目").put("roots",JSONArray()))).put("threads",JSONArray().put(thread()).apply { if(creations.isNotEmpty() && submitPhase=="confirmed") put(thread("created-thread")) }).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","desktop:open","desktop:creations","desktop:creations:projectless","commands:target-bound:v1","queue","steer","stop","approvals","plan-mode","generation-settings","generation-speed","models:read")+if(streaming) listOf("events") else emptyList()))
    private fun receipt(data: JSONObject,phase: String=queryState)=JSONObject().put("hostId","fixture-host").put("threadId",data.getString("threadId")).put("clientMessageId",data.getString("clientMessageId")).put("state",phase).put("turnId",turn ?: JSONObject.NULL).put("error",JSONObject.NULL).put("text",data.getString("text")).put("attachmentIds",data.getJSONArray("attachmentIds"))
    private fun history(id: String)=JSONObject().put("hostId","fixture-host").put("threadId",id).put("readTarget",target()).put("epoch",eventEpoch).put("cursor",eventCursor).put("messages",JSONArray().put(JSONObject().put("id","old-user").put("role","user").put("text","先前任务")).put(JSONObject().put("id","old-assistant").put("role","assistant").put("text",streamText ?: "先前任务完成").put("status",if(streamText=="第一段流式正文") "streaming" else "completed"))).put("runtime",JSONObject().put("state",state).put("turnId",turn ?: JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",approvals).put("receipts",JSONArray().apply { cancelledReply?.let { put(receipt(it,"cancelled")) }; reply?.let { put(receipt(it,if(state=="running") { if(it.optString("mode")=="queue") "queued" else "submitted" } else if(state=="interrupted") "interrupted" else queryState)) } }).put("canControl",controlled).put("proposedPlan",proposed ?: JSONObject.NULL))
    private fun creationResult(phase: String,data: JSONObject=creation!!)=JSONObject().put("hostId","fixture-host").put("clientOperationId",data.getString("clientOperationId")).put("projectId",data.get("projectId")).put("phase",phase).put("threadId",if(phase=="confirmed") "created-thread" else JSONObject.NULL).put("error",JSONObject.NULL).put("uploaded",JSONArray(uploaded)).put("thread",if(phase=="confirmed") thread("created-thread") else JSONObject.NULL)
    private fun response(request: WireRequest): WireResponse {
        val query=request.query
        val result=when(request.path) {
            "/v1/workspace"->workspace()
            "/v1/history"->history(query.getValue("threadId"))
            "/v1/events"->JSONObject().put("hostId","fixture-host").put("epoch",eventEpoch).put("cursor",eventCursor).put("reset",false).put("ready",eventReady).put("events",JSONArray().apply { if(eventCursor>0) put(JSONObject().put("threadId",query.getValue("threadId")).put("snapshot",history(query.getValue("threadId")))) })
            "/v1/models"->JSONObject().put("models",JSONArray().put(JSONObject().put("id","fixture-model").put("name","合成模型").put("isDefault",true).put("defaultEffort","medium").put("supportedEfforts",JSONArray(listOf("low","medium","high"))).put("defaultSpeed","standard").put("supportedSpeeds",JSONArray(listOf("standard","fast","ultrafast")))))
            "/v1/replies"->{ reply=JSONObject(String(request.body,Charsets.UTF_8)); if(loseReply) return WireResponse(disconnect=true); receipt(reply!!,if(state=="running") "queued" else "submitted") }
            "/v1/receipts"->receipt(reply!!)
            "/v1/desktop/open"->{ controlled=openingQueries==0; val body=JSONObject(String(request.body)); JSONObject().put("hostId","fixture-host").put("threadId",body.getString("threadId")).put("clientOperationId",body.getString("clientOperationId")).put("phase",if(controlled) "opened" else "opening").put("error",JSONObject.NULL).also { opening=it } }
            "/v1/desktop/open-status"->{
                check(query["id"]==opening!!.getString("clientOperationId") && query["threadId"]==opening!!.getString("threadId"))
                openingQueries--; controlled=openingQueries<=0
                JSONObject(opening.toString()).put("phase",if(controlled) "opened" else "opening")
            }
            "/v1/desktop/creations"->{ creation=JSONObject(String(request.body)); creations[creation!!.getString("clientOperationId")]=creation!!; creationResult("prepared") }
            "/v1/desktop/creation-receipts"->{
                val id=query.getValue("id"); val data=creations.getValue(id)
                if(id==blockedOperation) { val result=creationResult("confirmed",data); queryStarted.countDown(); check(queryRelease.await(20,TimeUnit.SECONDS)); result }
                else creationResult(submitPhase,data)
            }
            "/v1/desktop/creation-attachments"->{ val id=query.getValue("attachmentId"); val file=creation!!.getJSONArray("attachments").objects().first { it.getString("id")==id }; uploaded.add(id); JSONObject(file.toString()).put("hostId","fixture-host").put("clientOperationId",query.getValue("id")) }
            "/v1/desktop/creations/submit"->{ val data=JSONObject(String(request.body)); val id=data.getString("clientOperationId")
                if(blockedOperation!=null && id!=blockedOperation) { secondSubmitStarted.countDown(); check(secondSubmitRelease.await(20,TimeUnit.SECONDS)) }
                creationResult(submitPhase,creations.getValue(id))
            }
            "/v1/desktop/creations/detach"->creationResult(submitPhase).put("detached",true)
            "/v1/queue/cancel"->{ cancelledReply=reply; reply=null; eventCursor++; JSONObject().put("ok",true) }
            "/v1/stop"->{ state="interrupted"; turn=null; eventCursor++; JSONObject().put("ok",true) }
            "/v1/approvals"->{
                val body=JSONObject(String(request.body)); val id=body.getString("approvalId")
                val approval=approvals.objects().single { it.getString("id")==id }
                check(body.getString("threadId")==approval.getString("threadId") && body.getString("turnId")==approval.getString("turnId"))
                approvals=JSONArray(approvals.objects().filter { it.getString("id")!=id }); JSONObject().put("ok",true)
            }
            else->return WireResponse(status=404,body="{\"error\":\"Unknown synthetic route\"}")
        }
        return WireResponse(body=result.toString())
    }
    override fun close() { queryRelease.countDown(); secondSubmitRelease.countDown(); server.close() }
}
private val WireRequest.query get()=pathWithQuery.substringAfter('?').split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
