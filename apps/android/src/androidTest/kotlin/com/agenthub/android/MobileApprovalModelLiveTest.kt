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

/** Fresh original-desktop tool-approval inputs, one immutable independent lease per native action. */
class MobileApprovalModelLiveTest {
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({fixture.optString("viewportOrientation")},{folder()},{"case-${fixture.getInt("activeCase")}"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private var owned: OwnedControlAcceptance?=null
    private val session get()=owned?.session ?: activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-approval-model-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("cold-approval-native","cold-new-approval","stale-conflict-refresh","replacement-ready","replacement-approval-ready","replacement-release","replacement-complete","callback-held","release-callback","callback-complete","preflight-complete","claim-case","approval-ready","case-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original approval witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        activity=ui.activity
        fixture.optString("viewportOrientation").takeIf {it.isNotEmpty()}?.let { value ->
            assertEquals(if(value=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,activity.resources.configuration.orientation)
        }
        if(fixture.optBoolean("lateCallback")||fixture.optBoolean("staleConflict")||fixture.optString("viewportOrientation").isNotEmpty()) {
            ui.waitUntil(30_000) { !activity.session.restoring }
            ui.runOnIdle { owned=OwnedControlAcceptance(activity,"real-approval");session.resetAcceptance() }
        }
    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-approval-model/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
        ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
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
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var hidden=false;var visibleHeight=0
            i.runOnMainSync {
                val view=activity.window.decorView
                hidden=androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
                val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
            }
            val now=android.os.SystemClock.uptimeMillis()
            if(!hidden||lastHeight!=visibleHeight) { stableSince=now;lastHeight=visibleHeight }
            hidden&&now-stableSince>=700
        }
        ui.waitForIdle();i.waitForIdleSync()
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
    private fun activateApproval(choice: String) {
        val button=ui.onNodeWithTag("approval-$choice").assertIsEnabled()
        if(fixture.optBoolean("nativeHit")) {
            val bounds=button.fetchSemanticsNode().boundsInRoot
            assertTrue(ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().none {it.boundsInRoot.overlaps(bounds)})
            button.performTouchInput {click(center)}
            File(folder(),"case-${fixture.getInt("activeCase")}-native-hit.json").writeText(JSONObject().put("coordinateActivated",true).put("choice",choice).put("floatingOverlap",false).toString())
        } else button.performClick()
    }
    private fun approvalJson(a: MobileApproval)=JSONObject().put("id",a.id).put("threadId",a.threadId).put("turnId",a.turnId).put("kind",a.kind)
        .put("title",a.title).put("detail",a.detail).put("decisions",org.json.JSONArray(a.decisions))
    @After fun protectDefaults() {
        if(owned!=null) {
            main { session.leaveForeground();owned!!.verifyDefault() }
            File(owned!!.folder,"default-storage-proof.json").copyTo(File(folder(),"default-storage-proof-${fixture.getInt("activeCase")}.json"))
        }
    }
    private fun verifyReplacementCallback(original: MobileApproval): String {
        val mode=fixture.getString("replacementMode");require(mode in listOf("success","failure"))
        val thread=session.selectedThread;val draft=fixture.getString("independentDraft")
        fun message(id: String=original.id)=JSONObject().put("threadId",thread).put("approvalId",id)
        val ready=control("replacement-ready",message())
        ui.runOnIdle { session.loadHistory() }
        ui.waitUntil(20_000) { !session.historyLoading&&session.runtime?.approvals?.singleOrNull()?.let { it.id!=original.id&&it.turnId==original.turnId }==true }
        val replacement=session.runtime!!.approvals.single();val choice=ready.getString("choice")
        require(choice in listOf("decline","deny","cancel"));assertTrue(choice in replacement.decisions)
        assertEquals(original.turnId,replacement.turnId);assertNotEquals(original.id,replacement.id)
        assertTrue(session.isAnswering(original));assertTrue(session.canAnswer(replacement));assertNull(session.error)
        ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist()
        ui.onNodeWithText(replacement.title).performScrollTo().assertIsDisplayed()
        shot("replacement-second-original-command-title")
        replacement.decisions.forEach { ui.onNodeWithTag("approval-$it").assertIsEnabled() }
        ui.onNodeWithTag("approval-$choice").performScrollTo().assertIsEnabled()
        control("replacement-approval-ready",message(replacement.id).put("approval",approvalJson(replacement)).put("choice",choice))
        shot("replacement-same-turn-new-approval-ready")
        activateApproval(checkNotNull(choice))
        ui.waitUntil(10_000) { session.isAnswering(replacement) }
        val released=control("replacement-release",message().put("newApprovalId",replacement.id))
        assertEquals(if(mode=="failure")503 else 200,released.getInt("status"))
        ui.waitUntil(10_000) { !session.isAnswering(original) };ui.waitForIdle()
        assertTrue(session.isAnswering(replacement));assertEquals(replacement,session.runtime!!.approvals.single())
        assertEquals(draft,session.text);assertNull(session.error)
        replacement.decisions.forEach { ui.onNodeWithTag("approval-$it").assertIsNotEnabled() }
        ui.onNodeWithTag("approval-$choice").assertIsNotEnabled();ui.onNodeWithTag("approval-pending-progress").assertExists()
        shot("replacement-new-approval-still-pending-after-old-"+mode)
        control("replacement-release",message(replacement.id))
        ui.waitUntil(30_000) { !session.isAnswering(replacement)&&session.runtime?.approvals?.isEmpty()==true }
        assertEquals(draft,session.text);assertNull(session.error)
        control("replacement-complete",message().put("newApprovalId",replacement.id).put("newTurnId",replacement.turnId)
            .put("newerPendingProtected",true).put("draftProtected",true).put("noOldError",true).put("noReplay",true))
        return choice
    }
    @Test fun originalToolApprovalCase() {
        bind();val index=InstrumentationRegistry.getArguments().getString("approvalModelCase")!!.toInt();assertEquals(index,fixture.getInt("activeCase"));require(index in -1..1)
        if(index<=0) {
            ui.runOnIdle { session.resetAcceptance();fixture.optString("viewportTheme").takeIf {it.isNotEmpty()}?.let(session::changeAppearance) };main { session.connect("Android original tool approval DEV",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
            ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings();assertEquals("default",session.selectedCollaboration)
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
            if(index<0) { assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("native-approval-preflight-unsent");return }
        } else {
            fixture.optString("viewportTheme").takeIf {it.isNotEmpty()}?.let { theme ->ui.runOnIdle {session.changeAppearance(theme)} }
            if(owned!=null) {
                main { session.connect("Android isolated callback DEV",fixture.getString("endpoint"),fixture.getString("token")) }
                ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==fixture.getString("threadId") } }
                ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
            }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.selectedThread==fixture.getString("threadId")&&session.canCompose }
            settings();ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(index).getString("text"));hideKeyboard()
            if(owned!=null)allowSharing()
        }
        val row=fixture.getJSONArray("cases").getJSONObject(index);val before=session.operationRecords.map { it.id }.toSet()
        control("claim-case",JSONObject().put("index",index).put("targetKey",session.targetKey).put("threadId",session.selectedThread).put("text",row.getString("text")))
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(180_000) { session.transferStatus==null&&session.runtime?.approvals?.singleOrNull()?.let { it.kind!="question"&&session.canAnswer(it) }==true } }
        catch(e: ComposeTimeoutException) { shot("case-$index-missing-approval");throw AssertionError("Original model approval unresolved; never replay: ${session.runtimeState}; ${session.error}",e) }
        val approval=session.runtime!!.approvals.single();val record=session.operationRecords.single { it.id !in before&&it.kind==if(index==0)"creation" else "reply" };assertTrue(record.submissionAttempted)
        val required=row.optString("requiredDecision")
        require(required.isEmpty()||required in listOf("decline","accept","cancel","acceptForSession"))
        val choice=(if(required.isNotEmpty())listOf(required) else if(index==0&&fixture.optString("replacementMode").isEmpty())listOf("decline","deny","cancel") else listOf("accept","approve")).firstOrNull { it in approval.decisions }
        assertNotNull("Use only a decision declared by this original request",choice);assertEquals(session.selectedThread,approval.threadId)
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard()
        if(fixture.optString("replacementMode").isNotEmpty()) {
            ui.onNodeWithText(approval.title).performScrollTo().assertIsDisplayed();shot("replacement-first-original-command-title")
        }
        if(fixture.optString("viewportOrientation")=="landscape") {
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("approval-${approval.id}"))
        }
        ui.onNodeWithTag("approval-$choice").performScrollTo().assertIsEnabled();shot("case-$index-original-approval")
        fixture.optString("viewportTheme").takeIf {it.isNotEmpty()}?.let {theme ->
            assertEquals(theme,session.appearance);File(folder(),"case-$index-theme.json").writeText(JSONObject().put("theme",session.appearance).put("scope","owned session rendering, not settings interaction proof").toString())
        }
        if(fixture.optString("viewportOrientation").isNotEmpty()) {
            verifyApprovalViewport(ui,approval,folder(),"case-$index-full-approval")
            shot("case-$index-full-approval-viewport")
        }
        val destination=session.approvalActionDestination()
        control("approval-ready",JSONObject().put("index",index).put("threadId",session.selectedThread).put("record",record.body()).put("approval",approvalJson(approval)).put("choice",choice).put("pid",android.os.Process.myPid()))
        if(fixture.optBoolean("coldHostRestart")&&index==1) {
            assertNull(session.error);assertFalse(session.isAnswering(approval))
            approval.decisions.forEach { ui.onNodeWithTag("approval-$it").assertIsEnabled() }
            ui.onNodeWithTag("approval-pending-progress").assertDoesNotExist();shot("cold-fresh-original-approval-ready")
            control("cold-new-approval",JSONObject().put("threadId",session.selectedThread).put("approvalId",approval.id).put("turnId",approval.turnId).put("buttonsEnabled",true).put("draftProtected",session.text==fixture.getString("independentDraft")).put("noReplay",true))
        }
        if(fixture.optBoolean("staleConflict")&&index==0) {
            assertTrue(session.canAnswer(approval));assertEquals(approval,session.runtime!!.approvals.single())
            activateApproval(checkNotNull(choice))
            ui.waitUntil(20_000) { !session.isAnswering(approval)&&session.error?.contains("审批已处理或已过期")==true }
            assertEquals(fixture.getString("independentDraft"),session.text)
            ui.onNodeWithTag("dismiss-error").assertIsDisplayed();shot("stale-original-actual-host409-dialog")
            ui.onNodeWithTag("dismiss-error").performClick();assertNull(session.error)
            control("stale-conflict-refresh",JSONObject().put("threadId",session.selectedThread).put("approvalId",approval.id).put("draftProtected",true))
            if(fixture.optBoolean("coldHostRestart")) {
                main { session.refresh(userInitiated=true) }
                ui.waitUntil(45_000) { !session.loading&&session.selectedClient.online }
            }
            main { session.loadHistory(userInitiated=true) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.runtime?.approvals?.none { it.id==approval.id }==true }
            ui.onNodeWithTag("approval-$choice").assertDoesNotExist()
            ui.runOnIdle { session.answer(approval,checkNotNull(choice),emptyMap(),destination);assertFalse(session.isAnswering(approval)) }
            assertEquals(fixture.getString("independentDraft"),session.text);shot("stale-original-refresh-recovered")
            if(fixture.optBoolean("coldHostRestart")) {
                ui.waitUntil(45_000) { session.runtime?.canControl==true&&session.canCompose }
                assertFalse(session.isAnswering(approval));assertNull(session.error)
                control("cold-approval-native",JSONObject().put("threadId",session.selectedThread).put("oldAbsent",true).put("canControl",true).put("draftProtected",true).put("noReplay",true))
            }
        }else if(fixture.optBoolean("externalFirst")&&index==0) {
            // A second named HTTP client has already answered the exact original request once.
            // Refresh authoritative state before exercising the retained business callback;
            // no duplicate approval may reach the command transport.
            main { session.loadHistory(userInitiated=true) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.runtime?.approvals?.none { it.id==approval.id }==true }
            ui.onNodeWithTag("approval-$choice").assertDoesNotExist()
            ui.runOnIdle { session.answer(approval,checkNotNull(choice),emptyMap(),destination);assertFalse(session.isAnswering(approval)) }
            File(folder(),"case-$index-original-invalidated.json").writeText(JSONObject().put("originalApprovalId",approval.id).put("originalTurnId",approval.turnId)
                .put("currentApprovalCount",session.runtime!!.approvals.size).put("independentDraftPreserved",session.text==fixture.getString("independentDraft")).put("source","second named HTTP client; no desktop UI claim").toString())
            shot("case-$index-original-request-invalidated")
        }else activateApproval(checkNotNull(choice))
        val replacementDecision=if(fixture.optString("replacementMode").isNotEmpty())verifyReplacementCallback(approval) else {
            if(fixture.optBoolean("lateCallback")&&index==0)verifyOwnedLateCallback(ui,checkNotNull(owned),fixture.getString("projectId"),nonce,approval,folder(),::control)
            null
        }
        try { ui.waitUntil(180_000) {
            session.transferStatus==null&&!session.running&&session.runtime?.approvals?.none { it.id==approval.id }==true&&
                if(choice=="cancel"||replacementDecision=="cancel")session.decision.action=="send" else session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==row.getString("doneMarker") }
        } }catch(e: ComposeTimeoutException) { shot("case-$index-decision-unresolved");throw AssertionError("Original decision unresolved; never replay: ${session.runtimeState}; ${session.error}",e) }
        assertEquals(fixture.getString("independentDraft"),session.text)
        control("case-complete",JSONObject().put("index",index).put("threadId",session.selectedThread).put("record",record.body()).put("approval",approvalJson(approval)).put("choice",choice).put("pid",android.os.Process.myPid()))
        if(choice!="cancel"&&replacementDecision!="cancel")ui.onNodeWithTag("message-list").performScrollToNode(hasText(row.getString("doneMarker")))
        shot("case-$index-original-outcome")
    }
}
