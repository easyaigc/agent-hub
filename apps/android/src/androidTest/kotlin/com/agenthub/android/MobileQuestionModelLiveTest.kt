package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Fresh original-owner asynchronous questions. Each input and answer has its own immutable lease. */
class MobileQuestionModelLiveTest {
    // Bound this callback/receipt scenario to one viewport; configuration-change recovery is a separate gate.
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=object: org.junit.rules.ExternalResource() {
        private var held=false
        override fun before() {
            if(!fixture.optBoolean("unknownAfterWrite"))return
            val automation=i.uiAutomation
            assertTrue("Freeze only this examiner viewport",automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0));held=true
            try {
                val until=android.os.SystemClock.uptimeMillis()+10_000
                while(i.targetContext.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(50)
                assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT,i.targetContext.resources.configuration.orientation)
                File(folder(),"viewport-frozen.json").writeText(JSONObject().put("portrait",true).put("scope","test-only viewport; not rotation acceptance").toString())
            } catch(failure: Throwable) {
                val restored=automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);held=false
                File(folder(),"viewport-restored.json").writeText(JSONObject().put("automaticRotationRestored",restored).put("beforeFailed",true).toString())
                throw failure
            }
        }
        override fun after() {
            if(held) {
                val restored=i.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
                File(folder(),"viewport-restored.json").writeText(JSONObject().put("automaticRotationRestored",restored).toString())
                assertTrue("Release this examiner rotation lock",restored)
            }
        }
    }
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private var owned: OwnedControlAcceptance?=null
    private val session get()=owned?.session ?: activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-question-model-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("unknown-receipt-native","unknown-recovery-complete","cold-receipt-native","stale-question-start","stale-question-refresh","replacement-ready","replacement-release","replacement-complete","pending-recovery-complete","question-current","callback-held","release-callback","callback-complete","preflight-complete","claim-case","questions-ready","answer-ready","case-complete","restart","recovery-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original question witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        activity=ui.activity
        if(fixture.optBoolean("lateCallback")||fixture.optBoolean("isolatedSession")) {
            ui.waitUntil(30_000) { !activity.session.restoring }
            ui.runOnIdle { owned=OwnedControlAcceptance(activity,"real-question");session.resetAcceptance() }
        }
    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-question-model/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        if(session.selectedCollaboration!="plan") { ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick() }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
        assertEquals("default",session.generationSettings.collaborationMode)
    }
    private fun focusedWindow(): android.view.Window {
        var found: android.view.Window?=null
        i.runOnMainSync {
            val roots=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow&&it.hasWindowFocus()&&it.context.packageName==i.targetContext.packageName }
            assertEquals("One focused window owned by this acceptance process",1,roots.size)
            val root=roots.single()
            fun find(v: android.view.View) {
                if(v is androidx.compose.ui.window.DialogWindowProvider) found=v.window
                if(v is android.view.ViewGroup) for(n in 0 until v.childCount) find(v.getChildAt(n))
            }
            find(root)
            if(found==null&&activity.window.decorView.rootView===root)found=activity.window
            assertTrue(checkNotNull(found).decorView.rootView===root)
        }
        return checkNotNull(found)
    }
    private fun shot(name: String) {
        hideKeyboard();ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val window=focusedWindow();var image: Bitmap?=null
        i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());image=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,bitmap,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("evidenceRun",fixture.getString("evidenceRun")).put("method","PixelCopy.owned-focused-window").put("window",if(window===activity.window)"main" else "dialog").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height)
                .put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{bitmap.recycle()}
    }
    private fun hideKeyboard() {
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("composer-dismiss-keyboard").assertIsDisplayed().performClick()
        var window=focusedWindow()
        var stableSince=0L;var lastHeight=-1;var lastHide=0L;var windowChanges=0
        ui.waitUntil(15_000) {
            val latest=runCatching { focusedWindow() }.getOrNull() ?: return@waitUntil false
            if(latest!==window) { window=latest;stableSince=0L;lastHeight=-1;windowChanges++ }
            var hidden=false;var visibleHeight=0
            i.runOnMainSync {
                val view=window.decorView
                val now=android.os.SystemClock.uptimeMillis()
                if(view.isAttachedToWindow&&view.hasWindowFocus()&&now-lastHide>=500) {
                    androidx.core.view.WindowInsetsControllerCompat(window,view).hide(androidx.core.view.WindowInsetsCompat.Type.ime());lastHide=now
                }
                hidden=view.isAttachedToWindow&&view.hasWindowFocus()&&androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
                val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
            }
            val now=android.os.SystemClock.uptimeMillis()
            if(!hidden||lastHeight!=visibleHeight) { stableSince=now;lastHeight=visibleHeight }
            hidden&&now-stableSince>=700
        }
        File(folder(),"keyboard-hide-current-window.json").writeText(JSONObject().put("attachedFocusedOwnedWindow",true).put("hiddenStable700ms",true).put("windowChanges",windowChanges).toString())
        ui.waitForIdle();i.waitForIdleSync()
    }
    private fun replaceComposer(text: String) {
        val input=ui.onNodeWithTag("message-input");input.performClick()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
        input.performTextReplacement(text)
        assertEquals(text,input.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.InputText].text)
        assertEquals(text,session.text)
    }
    private fun allowSharing() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
        val text=session.text;val target=session.targetKey;val records=session.operationRecords.map { it.id }.toSet()
        hideKeyboard();shot("sharing-before-click")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(10_000) { session.aiSharingRequest!=null } }
        catch(e: Throwable) { shot("sharing-request-absent");File(folder(),"sharing-diagnostic.txt").writeText("error=${session.error};canCompose=${session.canCompose};text=${session.text}\n"+ui.onNodeWithTag("composer").printToString());throw e }
        assertEquals(target,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertEquals(text,session.text);assertEquals(target,session.targetKey);assertEquals(records,session.operationRecords.map { it.id }.toSet());assertNull(session.transferStatus)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }
    private fun asyncApprovals()=session.runtime?.approvals.orEmpty().filter { it.kind=="question"&&it.interactionMode=="async" }
    private fun approvalJson(a: MobileApproval): JSONObject {
        val questions=org.json.JSONArray()
        a.questions.forEach { q -> questions.put(JSONObject().put("id",q.id).put("question",q.question).put("options",org.json.JSONArray(q.options.map { it.label }))) }
        return JSONObject().put("id",a.id).put("threadId",a.threadId).put("turnId",a.turnId).put("kind",a.kind).put("interactionMode",a.interactionMode).put("questions",questions)
    }
    private fun lease(a: MobileApproval,answer: String?,skip: Boolean=false) {
        control("answer-ready",JSONObject().put("index",fixture.getInt("activeCase")).put("approval",approvalJson(a)).put("answer",answer ?: JSONObject.NULL).put("skip",skip))
    }
    private fun answer(marker: String) {
        try { ui.waitUntil(240_000) { session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker }&&!session.running&&session.transferStatus==null } }
        catch(e: ComposeTimeoutException) { shot("original-outcome-unresolved");throw AssertionError("Never replay original input: ${session.runtimeState}; ${session.error}",e) }
    }
    private fun editable(tag: String,value: String) { ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(value))) }
    @Test fun originalAsyncReadOnlyRecovery() {
        bind();ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("Android async original read-only recovery",fixture.getString("endpoint"),fixture.getString("token")) }
        val threadId=fixture.getString("threadId")
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==threadId } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==threadId }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.runtime!=null&&session.messages.any { it.role=="assistant"&&it.text.trim()==fixture.getString("recoveryMarker") }&&!session.running }
        assertTrue(asyncApprovals().isEmpty());assertNull(session.transferStatus)
        val saved=session.operationRecords.single { it.id==fixture.getString("priorOperationId") }
        control("recovery-complete",JSONObject().put("index",0).put("threadId",threadId).put("record",saved.body()).put("pid",android.os.Process.myPid()))
        shot("00-original-active-case-readonly-recovery")
    }
    @After fun protectDefaults() {
        if(owned!=null) {
            main { session.leaveForeground();owned!!.verifyDefault() }
            File(owned!!.folder,"default-storage-proof.json").copyTo(File(folder(),"default-storage-proof-${fixture.getInt("activeCase")}.json"))
        }
    }
    @Test fun readOnlyPendingOriginalQuestion() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);assertNotNull(owned)
        main { session.connect("Original pending question read only",fixture.getString("endpoint"),fixture.getString("token")) }
        val thread=fixture.getString("threadId")
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.threads.any { it.id==thread } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(45_000) { !session.historyLoading&&asyncApprovals().singleOrNull()?.let { it.id==fixture.getString("approvalId")&&it.turnId==fixture.getString("originalTurnId")&&session.canAnswer(it) }==true }
        val original=asyncApprovals().single();val question=original.questions.single()
        ui.onNodeWithTag("question-title-${question.id}").assertTextEquals(fixture.getString("questionTitle"))
        ui.onNodeWithTag("async-question-status").assertTextEquals("任务已结束，回答将继续此对话")
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.waitForIdle()
        val bounds=ui.onNodeWithTag("async-question-sheet").fetchSemanticsNode().boundsInRoot
        val pendingWindow=focusedWindow();var height=0;i.runOnMainSync { height=pendingWindow.decorView.height }
        val visibleHeight=(minOf(bounds.bottom,height.toFloat())-maxOf(bounds.top,0f)).coerceAtLeast(0f)
        val ratio=visibleHeight/height.toFloat()
        File(folder(),"pending-sheet-geometry.json").writeText(JSONObject().put("sheetHeight",bounds.height).put("sheetTop",bounds.top).put("windowHeight",height).put("visibleSheetHeight",visibleHeight).put("heightRatio",ratio).toString())
        if(fixture.optBoolean("requireLargeSheet"))assertTrue("Large sheet matches iOS large-only presentation: $ratio",ratio>=0.8f&&bounds.top<=height*0.15f)
        shot("pending-original-question-restored")
        ui.onNodeWithTag("question-option-${question.id}-${question.options.first().label}").performClick()
        val custom="Local pending answer $nonce";val tag="question-custom-${question.id}"
        ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(custom);hideKeyboard();editable(tag,custom)
        ui.onNodeWithTag("answer-question").assertIsEnabled();ui.onNodeWithTag("close-async-questions").performClick()
        ui.onNodeWithTag("answer-question").assertDoesNotExist();ui.onNodeWithTag("open-async-questions").performClick()
        editable(tag,custom);shot("pending-original-local-answer-retained-unsent")
        assertEquals(original,asyncApprovals().single())
        control("pending-recovery-complete",JSONObject().put("threadId",thread).put("approvalId",original.id).put("turnId",original.turnId).put("heightRatio",ratio).put("localAnswerRetained",true).put("pid",android.os.Process.myPid()))
    }
    private fun verifyReplacementCallback(original: MobileApproval) {
        val mode=fixture.getString("replacementMode");require(mode in listOf("success","failure"))
        val thread=session.selectedThread
        fun message(id: String=original.id)=JSONObject().put("threadId",thread).put("approvalId",id)
        control("replacement-ready",message())
        ui.runOnIdle { session.loadHistory() }
        ui.waitUntil(20_000) { !session.historyLoading&&asyncApprovals().singleOrNull()?.questions?.singleOrNull()?.question=="替换后的原问题" }
        val replacement=asyncApprovals().single();val q=replacement.questions.single()
        assertNotEquals(original.id,replacement.id);assertNotEquals(original.turnId,replacement.turnId)
        assertTrue(session.isAnswering(original));assertTrue(session.canAnswer(replacement))
        ui.onNodeWithTag("question-option-${q.id}-完成验收").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist()
        val draft="Replacement draft $nonce";val newer=if(mode=="success")"Replacement independent error $nonce"else null
        ui.runOnIdle { session.text=draft;if(newer!=null)session.error=newer else assertNull(session.error) }
        shot("replacement-new-request-editable-old-response-held")
        ui.onNodeWithTag("question-option-${q.id}-完成验收").performClick();lease(replacement,"完成验收")
        ui.onNodeWithTag("answer-question").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.isAnswering(replacement) }
        val released=control("replacement-release",message().put("newApprovalId",replacement.id))
        assertEquals(if(mode=="failure")503 else 200,released.getInt("status"))
        ui.waitUntil(10_000) { !session.isAnswering(original) };ui.waitForIdle()
        assertTrue("Old completion cannot release the new answer owner",session.isAnswering(replacement))
        assertEquals(replacement,asyncApprovals().single());assertEquals(draft,session.text);assertEquals(newer,session.error)
        ui.onNodeWithTag("question-option-${q.id}-完成验收").assertIsNotEnabled()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertExists()
        shot("replacement-new-answer-still-pending-after-old-"+mode)
        control("replacement-release",message(replacement.id))
        ui.waitUntil(30_000) { !session.isAnswering(replacement)&&asyncApprovals().isEmpty() }
        assertEquals(draft,session.text);assertEquals(newer,session.error)
        ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
        shot("replacement-new-answer-completed-original-draft-kept")
        control("replacement-complete",message().put("newApprovalId",replacement.id).put("newTurnId",replacement.turnId)
            .put("newerPendingProtected",true).put("draftProtected",true).put("newerErrorProtected",true).put("noReplay",true))
    }
    private fun verifyVisibleReplacementSuccessCallback(original: MobileApproval) {
        val mode=fixture.getString("replacementMode");require(mode=="success")
        val thread=session.selectedThread
        fun message(id: String=original.id)=JSONObject().put("threadId",thread).put("approvalId",id)
        control("replacement-ready",message())
        ui.runOnIdle { session.loadHistory() }
        ui.waitUntil(20_000) { !session.historyLoading&&asyncApprovals().singleOrNull()?.questions?.singleOrNull()?.question=="替换后的原问题" }
        val replacement=asyncApprovals().single();val q=replacement.questions.single()
        assertNotEquals(original.id,replacement.id);assertNotEquals(original.turnId,replacement.turnId)
        assertTrue(session.isAnswering(original));assertTrue(session.canAnswer(replacement))
        ui.onNodeWithTag("question-option-${q.id}-完成验收").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist()
        val draft="Replacement draft $nonce";val newer="Replacement independent error $nonce"
        ui.runOnIdle { session.text=draft;assertNull(session.error) }
        shot("replacement-new-request-editable-old-response-held")
        ui.onNodeWithTag("question-option-${q.id}-完成验收").performClick();lease(replacement,"完成验收")
        ui.onNodeWithTag("answer-question").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.isAnswering(replacement) }
        ui.runOnIdle { session.error=newer }
        val released=control("replacement-release",message().put("newApprovalId",replacement.id))
        assertEquals(if(mode=="failure")503 else 200,released.getInt("status"))
        ui.waitUntil(10_000) { !session.isAnswering(original) };ui.waitForIdle()
        assertTrue("Old completion cannot release the new answer owner",session.isAnswering(replacement))
        assertEquals(replacement,asyncApprovals().single());assertEquals(draft,session.text);assertEquals(newer,session.error)
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed().performClick();assertNull(session.error)
        ui.onNodeWithTag("question-option-${q.id}-完成验收").assertIsNotEnabled()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertExists()
        shot("replacement-new-answer-still-pending-after-old-"+mode)
        control("replacement-release",message(replacement.id))
        ui.waitUntil(30_000) { !session.isAnswering(replacement)&&asyncApprovals().isEmpty() }
        assertEquals(draft,session.text);assertNull(session.error)
        ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
        shot("replacement-new-answer-completed-original-draft-kept")
        control("replacement-complete",message().put("newApprovalId",replacement.id).put("newTurnId",replacement.turnId)
            .put("newerErrorDismissedExplicitly",true).put("newerPendingProtected",true).put("draftProtected",true).put("newerErrorProtected",true).put("noReplay",true))
    }
    private fun verifyStaleQuestionConflict(original: MobileApproval) {
        val thread=session.selectedThread;val q=original.questions.single();val draft="Stale question draft $nonce"
        ui.runOnIdle { session.text=draft };hideKeyboard()
        val destination=session.approvalActionDestination()
        control("stale-question-start",JSONObject().put("threadId",thread).put("approval",approvalJson(original)))
        assertEquals(original,asyncApprovals().single());assertTrue(session.canAnswer(original))
        ui.onNodeWithTag("question-option-${q.id}-稍后处理").assertIsEnabled().performClick()
        ui.onNodeWithTag("answer-question").assertIsEnabled().performClick()
        ui.waitUntil(20_000) { !session.isAnswering(original)&&session.error?.contains("此问题已有不同的提交")==true }
        assertEquals(draft,session.text);ui.onNodeWithTag("dismiss-error").assertIsDisplayed();shot("stale-question-actual-host409-dialog")
        ui.onNodeWithTag("dismiss-error").performClick();assertNull(session.error)
        val recovered=control("stale-question-refresh",JSONObject().put("threadId",thread).put("approvalId",original.id).put("draftProtected",true))
        if(fixture.optBoolean("coldHostRestart")) {
            main { session.refresh(userInitiated=true) }
            ui.waitUntil(45_000) { !session.loading&&session.selectedClient.online }
        }
        main { session.loadHistory(userInitiated=true) }
        ui.waitUntil(45_000) { !session.historyLoading&&asyncApprovals().singleOrNull()?.questions?.singleOrNull()?.question=="替换后的原问题" }
        val current=asyncApprovals().single();val next=current.questions.single()
        if(fixture.optBoolean("coldHostRestart"))ui.waitUntil(45_000) { session.canAnswer(current) }
        assertNotEquals(original.id,current.id);assertNotEquals(original.turnId,current.turnId)
        ui.onNodeWithTag("question-title-${q.id}").assertDoesNotExist()
        ui.onNodeWithTag("question-title-${next.id}").assertTextEquals("替换后的原问题")
        ui.onNodeWithTag("question-option-${next.id}-完成验收").assertIsEnabled().assertIsNotSelected()
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();assertEquals(draft,session.text)
        ui.runOnIdle { session.answer(original,"",mapOf(q.id to listOf("稍后处理")),destination);assertFalse(session.isAnswering(original)) }
        shot("stale-question-refresh-new-request-unselected")
        if(fixture.optBoolean("coldHostRestart")) {
            val expected=recovered.getJSONObject("coldReceipt");val id=expected.getString("clientMessageId")
            var status: JSONObject?=null
            main { status=NativeCodexCommands { fixture.getString("token") }.replyDeliveryStatus(session.selectedClient,thread,id) }
            assertEquals("accepted",status!!.getString("status"));assertEquals(expected.getString("fingerprint"),status!!.getString("fingerprint"))
            val receipt=session.runtime!!.receipts.single { it.operationId==id }
            assertEquals(thread,receipt.threadId);assertTrue(receipt.state in listOf("submitted","completed","interrupted"))
            assertEquals(draft,session.text);assertTrue(session.messages.any { it.role=="user"&&it.text=="结束后继续对话\n继续验收" })
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("message-list").performScrollToNode(hasText("结束后继续对话\n继续验收"))
            ui.onNodeWithText("结束后继续对话\n继续验收").assertIsDisplayed();shot("cold-original-answer-history-and-draft")
            ui.onNodeWithTag("open-async-questions").performClick()
            ui.onNodeWithTag("question-option-${next.id}-完成验收").assertIsNotSelected();ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            File(folder(),"cold-original-answer-receipt.json").writeText(checkNotNull(status).toString())
            control("cold-receipt-native",JSONObject().put("threadId",thread).put("receipt",status).put("draftProtected",true).put("acceptedAnswerVisible",true).put("pendingNewQuestionUnselected",true))
        }
        ui.onNodeWithTag("question-option-${next.id}-完成验收").performClick();lease(current,"完成验收")
        ui.onNodeWithTag("answer-question").assertIsEnabled().performClick()
        answer("ASYNC_FOLLOWUP_$nonce");assertEquals(draft,session.text);assertNull(session.error)
        shot("stale-question-fresh-answer-recovered")
    }
    private fun verifyUnknownAnswerReceipt(original: MobileApproval) {
        val thread=session.selectedThread;val question=original.questions.single();val destination=session.approvalActionDestination()
        val draft="Unknown answer independent draft $nonce"
        val id="async-question-"+java.security.MessageDigest.getInstance("SHA-256").digest(original.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val immutable=JSONObject().put("hostId",fixture.getString("hostId")).put("threadId",thread).put("approvalId",original.id).put("turnId",original.turnId).put("clientMessageId",id).put("answer","继续验收")
        File(folder(),"unknown-original-intent-before-submit.json").writeText(immutable.toString())
        ui.runOnIdle { session.text=draft };hideKeyboard()
        ui.onNodeWithTag("question-option-${question.id}-继续验收").assertIsNotSelected().performClick();lease(original,"继续验收")
        ui.onNodeWithTag("answer-question").assertIsEnabled().performClick()
        ui.waitUntil(20_000) { !session.isAnswering(original)&&session.error?.contains("回答结果待核对")==true }
        assertEquals(draft,session.text);assertEquals(original,asyncApprovals().single())
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed();shot("unknown-written-answer-client-error")
        var receipt: JSONObject?=null
        main { receipt=NativeCodexCommands { fixture.getString("token") }.replyDeliveryStatus(session.selectedClient,thread,id) }
        assertEquals(id,receipt!!.getString("clientMessageId"));assertEquals("accepted",receipt!!.getString("status"))
        assertTrue(receipt!!.getString("fingerprint").isNotEmpty());assertEquals(draft,session.text)
        File(folder(),"unknown-original-answer-receipt.json").writeText(checkNotNull(receipt).toString())
        control("unknown-receipt-native",JSONObject().put("threadId",thread).put("derivedBeforeSubmitId",id).put("receipt",receipt).put("unknownErrorShown",true).put("draftProtected",true).put("noReplay",true))
        ui.onNodeWithTag("dismiss-error").performClick();assertNull(session.error)
        main { session.refresh(userInitiated=true) }
        ui.waitUntil(45_000) { !session.loading&&session.selectedClient.online }
        main { session.loadHistory(userInitiated=true) }
        ui.waitUntil(45_000) { !session.historyLoading&&asyncApprovals().none { it.id==original.id }&&session.runtime?.receipts?.any { it.operationId==id&&it.state in listOf("submitted","completed","interrupted") }==true }
        ui.onNodeWithTag("answer-question").assertDoesNotExist();ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
        ui.runOnIdle { session.answer(original,"",mapOf(question.id to listOf("继续验收")),destination);assertFalse(session.isAnswering(original)) }
        assertEquals(draft,session.text);assertNull(session.error)
        val body="结束后继续对话\n继续验收"
        assertEquals(1,session.messages.count { it.role=="user"&&it.text==body })
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(body));ui.onNodeWithText(body).assertIsDisplayed();shot("unknown-original-accepted-answer-and-draft-recovered")
        control("unknown-recovery-complete",JSONObject().put("threadId",thread).put("originalAbsent",true).put("acceptedAnswerVisible",true).put("draftProtected",true).put("noReplay",true))
    }
    @Test fun originalAsyncQuestionCase() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        val index=InstrumentationRegistry.getArguments().getString("questionModelCase")!!.toInt();assertEquals(index,fixture.getInt("activeCase"));require(index in -1..1)
        val active=index==0&&!fixture.getBoolean("idleOnly")
        if(index<=0) {
            ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android original async DEV",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
            ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
            replaceComposer(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
            if(index<0) { assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("preflight-unsent");return }
        }else {
            if(owned!=null) {
                main { session.connect("Android isolated callback DEV",fixture.getString("endpoint"),fixture.getString("token")) }
                ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==fixture.getString("threadId") } }
                ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
            }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.selectedThread==fixture.getString("threadId")&&session.canCompose }
            settings();replaceComposer(fixture.getJSONArray("cases").getJSONObject(index).getString("text"));hideKeyboard()
            if(owned!=null)allowSharing()
        }
        val row=fixture.getJSONArray("cases").getJSONObject(index);val before=session.operationRecords.map { it.id }.toSet()
        control("claim-case",JSONObject().put("index",index).put("targetKey",session.targetKey).put("threadId",session.selectedThread).put("text",row.getString("text")))
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&asyncApprovals().size==if(active)3 else 1 }
        val original=asyncApprovals();val record=session.operationRecords.single { it.id !in before&&it.kind==if(index==0)"creation" else "reply" }
        assertTrue(record.submissionAttempted)
        if(!active)answer("ASYNC_IDLE_$nonce")
        control("questions-ready",JSONObject().put("index",index).put("threadId",session.selectedThread).put("record",record.body()).put("approvals",org.json.JSONArray(original.map { approvalJson(it) })).put("pid",android.os.Process.myPid()))
        if(active) {
            val format=original[0].questions.single();val code=original[1].questions.single();val channel=original[2].questions.single()
            assertEquals("请选择交付格式",format.question);assertEquals("请输入验收暗号",code.question);assertEquals("可选的发布渠道",channel.question)
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 3");ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            shot("01-three-questions-task-continues")
            ui.onNodeWithTag("async-question-next").performClick();val field="question-custom-${code.id}"
            ui.onNodeWithTag(field).performScrollTo().performTextInput("PHONE_$nonce");hideKeyboard()
            ui.onNodeWithTag("async-question-next").performClick();ui.onNodeWithTag("async-question-page").assertTextEquals("3 / 3")
            ui.onNodeWithTag("question-title-${channel.id}").performTouchInput { swipeRight() }
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 3");editable(field,"PHONE_$nonce")
            ui.onNodeWithTag("async-question-prev").performClick();ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("answer-question").assertDoesNotExist();shot("02-collapsed-running-question")
            ui.onNodeWithTag("open-async-questions").performClick();ui.onNodeWithTag("question-option-${format.id}-简洁版").performClick()
            lease(original[0],"简洁版");ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(30_000) { asyncApprovals().size==2 };ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2");editable(field,"PHONE_$nonce");shot("03-retained-real-draft")
            lease(original[1],"PHONE_$nonce");ui.onNodeWithTag("answer-question").performScrollTo().performClick();ui.waitUntil(30_000) { asyncApprovals().size==1 }
            ui.onNodeWithTag("question-title-${channel.id}").assertIsDisplayed();lease(original[2],null,true)
            ui.onNodeWithTag("skip-question").performScrollTo().performClick();answer("ASYNC_DONE_$nonce")
            assertTrue(asyncApprovals().isEmpty());ui.onNodeWithTag("async-question-sheet").assertDoesNotExist();shot("04-active-answers-completed")
        }else {
            val pending=original.single();ui.onNodeWithTag("async-question-status").assertTextEquals("任务已结束，回答将继续此对话");shot(if(fixture.optBoolean("lateCallback"))"05-idle-before-late-answer"else"05-idle-before-restart")
            if(fixture.optBoolean("unknownAfterWrite"))verifyUnknownAnswerReceipt(pending) else if(fixture.optBoolean("staleConflict"))verifyStaleQuestionConflict(pending) else {
            control(if(fixture.optBoolean("lateCallback"))"question-current"else"restart",JSONObject().put("threadId",session.selectedThread).put("approval",approvalJson(pending)))
            ui.runOnIdle { session.refresh(userInitiated=true) };ui.waitUntil(45_000) { !session.loading }
            ui.runOnIdle { session.loadHistory(userInitiated=true) };ui.waitUntil(45_000) { !session.historyLoading&&asyncApprovals().singleOrNull()?.id==pending.id&&session.canAnswer(pending) }
            shot(if(fixture.optBoolean("lateCallback"))"06-same-original-question-before-answer"else"06-same-question-after-host-restart")
            ui.onNodeWithTag("question-option-${pending.questions.single().id}-继续验收").performClick();lease(pending,"继续验收")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            if(fixture.optString("replacementMode")=="success")verifyVisibleReplacementSuccessCallback(pending)
            else if(fixture.optString("replacementMode").isNotEmpty())verifyReplacementCallback(pending)
            else if(fixture.optBoolean("lateCallback"))verifyOwnedLateCallback(ui,checkNotNull(owned),fixture.getString("projectId"),nonce,pending,folder(),::control)
            }
            answer("ASYNC_FOLLOWUP_$nonce");assertTrue(asyncApprovals().isEmpty());shot("07-idle-followup-completed")
        }
        control("case-complete",JSONObject().put("index",index).put("threadId",session.selectedThread).put("record",record.body()).put("pid",android.os.Process.myPid()))
    }
}
