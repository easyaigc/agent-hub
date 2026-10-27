package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
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

/** Fresh original-owner stop and explicit recovery gestures with immutable input leases. */
class MobileStopModelLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private var tracked: MainActivity?=null
    private var owned: OwnedControlAcceptance?=null
    private val session get()=owned?.session ?: activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-stop-model-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","origin-ready","stop-ready","stopped","case-complete","stop-held","release-stop","late-stop-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Connection","close");http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original queue witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        activity=ui.activity;tracked=activity

    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-stop-model/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun menuShot(tag: String,name: String) {
        owned!!.nodeShot(ui.onNodeWithTag(tag).onParent(),name)
        for(extension in listOf(".png","-window.json"))File(owned!!.folder,name+extension).copyTo(File(folder(),name+extension))
    }
    private fun clickOwnedMenu(label: String,name: String) {
        val context=session.composerActionContext()
        if(fixture.optBoolean("coordinateMenu")) {
            assertTrue(session.composerActionCurrent(context))
            File(folder(),"$name-context.json").writeText(JSONObject().put("contextCurrent",true).put("action",session.decision.action).put("enabled",session.decision.enabled).put("turnId",session.runtime?.turnId).put("selection",context.selection).put("gesture","SDK screen-coordinate pointer").toString())
            touchOwnedMenu(label,folder(),name);return
        }
        val root=checkNotNull(i.uiAutomation.rootInActiveWindow)
        assertEquals(i.targetContext.packageName,root.packageName?.toString())
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.packageName?.toString()!=i.targetContext.packageName)return null
            if(node.text?.toString()==label)return node
            for(n in 0 until node.childCount)node.getChild(n)?.let { child->find(child)?.let { return it } }
            return null
        }
        var node=find(root)
        while(node!=null&&!node.isClickable)node=node.parent
        assertNotNull("Exact app-owned menu accessibility action",node)
        assertEquals(i.targetContext.packageName,node!!.packageName?.toString())
        File(folder(),"$name-context.json").writeText(JSONObject().put("contextCurrent",session.composerActionCurrent(context)).put("action",session.decision.action).put("enabled",session.decision.enabled).put("state",session.runtimeState).put("turnId",session.runtime?.turnId).put("selection",context.selection).put("gesture","SDK accessibility ACTION_CLICK on exact owned popup row").toString())
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun ownComposerReady(label: String) {
        fun ready(): Boolean {
            var focused=false
            i.runOnMainSync { focused=!activity.isDestroyed&&!activity.isFinishing&&activity.window.decorView.isAttachedToWindow&&activity.window.decorView.hasWindowFocus() }
            return focused&&runCatching { ui.onAllNodesWithTag("composer").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        if(runCatching { ui.waitUntil(5_000) { ready() } }.isSuccess)return
        val sourceTarget=session.targetKey
        val command="am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity"
        val result=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        assertTrue(result.contains("Status: ok"))
        ui.waitUntil(20_000) {
            var rebound=false
            i.runOnMainSync {
                val current=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                if(current!=null&&!current.isDestroyed&&!current.session.restoring) {
                    require(current.packageName==i.targetContext.packageName)
                    if(current!==activity) {
                        activity=current;activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
                        activity.setContent { AgentHubApp(session) { } }
                    }
                    rebound=true
                }
            }
            rebound&&ready()
        }
        assertEquals(sourceTarget,session.targetKey)
        File(folder(),"$label-owned-frame-recovery.json").writeText(JSONObject().put("method","SDK exact acceptance MAIN/LAUNCHER activation; same isolated session").put("targetKey",sourceTarget).put("package",i.targetContext.packageName).toString())
    }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        if(session.selectedCollaboration!="plan") { ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick() }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
        assertEquals("default",session.generationSettings.collaborationMode);ownComposerReady("settings-return")
    }
    private fun shot(name: String) {
        ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        var image: Bitmap?=null
        i.runOnMainSync { assertTrue(activity.window.decorView.hasWindowFocus());image=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(activity.window,bitmap,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("evidenceRun",fixture.getString("evidenceRun")).put("method","PixelCopy.window").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height)
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
        assertTrue(session.aiSharing.permits(scope));shot("native-stop-sharing-granted-unsent")
    }
    private fun claim(index: Int) {
        val row=fixture.getJSONArray("cases").getJSONObject(index)
        control("claim-input",JSONObject().put("index",index).put("text",row.getString("text")).put("mode","next-turn").put("threadId",session.selectedThread).put("targetKey",session.targetKey).put("expectedTurnId",JSONObject.NULL))
    }
    private fun records(before: Set<String>)=org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })
    private fun finalCount()=session.messages.count { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==fixture.getString("doneMarker") }
    private fun lateStop() {
        val a=session.selectedThread;val original=session.threads.single { it.id==a }
        control("stop-held",JSONObject().put("threadId",a))
        ui.runOnIdle { session.newChat(fixture.getString("projectId"));session.text="Owned independent B draft $nonce" }
        val b=session.targetKey
        shot("late-stop-independent-B")
        ui.runOnIdle { session.selectThread(original) }
        ui.waitUntil(30_000) { !session.historyLoading&&!session.running&&session.runtime!=null }
        val draft="Owned revisited A draft $nonce";val newer="Owned newer error $nonce"
        ui.runOnIdle { session.text=draft;session.error=newer }
        control("release-stop",JSONObject().put("threadId",a))
        val until=System.currentTimeMillis()+1500
        while(System.currentTimeMillis()<until) { ui.waitForIdle();assertEquals(draft,session.text);assertEquals(newer,session.error);Thread.sleep(50) }
        ui.onNodeWithTag("session-error").assertIsDisplayed();menuShot("session-error","late-stop-keeps-newer-error")
        ui.onNodeWithTag("dismiss-error").performClick();ui.onNodeWithTag("message-input").performTextReplacement("")
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) }
        assertEquals(b,session.targetKey);assertEquals("Owned independent B draft $nonce",session.text)
        ui.runOnIdle { session.selectThread(original) }
        ui.waitUntil(30_000) { !session.historyLoading&&session.canCompose }
        assertEquals("",session.text);assertNull(session.error)
        control("late-stop-complete",JSONObject().put("threadId",a).put("draftProtected",true).put("newerErrorProtected",true).put("bDraftProtected",true))
        shot("late-stop-original-ready-for-new-gesture")
    }
    @Test fun readOnlyOriginalCreationRecovery() {
        bind();ui.runOnIdle { session.resetAcceptance() };main { session.connect("Original creation read only recovery",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) }
        val id=fixture.getString("priorOperationId")
        ui.waitUntil(45_000) { session.operationRecords.any { it.id==id } }
        val original=session.operationRecords.single { it.id==id }
        assertEquals("creation",original.kind);assertEquals(fixture.getString("intentSha256"),original.intentSha256);assertTrue(original.submissionAttempted)
        ui.runOnIdle { session.queryOperation(original) }
        ui.waitUntil(45_000) { session.transferStatus==null&&session.operationRecords.any { it.id==id&&it.phase=="confirmed" } }
        val confirmed=session.operationRecords.single { it.id==id };assertEquals(original.intent,confirmed.intent);assertEquals(original.intentSha256,confirmed.intentSha256);assertTrue(confirmed.submissionAttempted)
        assertEquals(fixture.getString("threadId"),JSONObject(confirmed.result!!).getString("threadId"))
        ui.waitUntil(45_000) { session.threads.any { it.id==fixture.getString("threadId") } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.runtime!=null&&!session.running&&session.messages.any { it.role=="user" } }
        assertEquals("idle",session.runtimeState);assertNull(session.runtime!!.turnId)
        val users=session.messages.filter { it.role=="user" };assertEquals(1,users.size)
        val textSha=java.security.MessageDigest.getInstance("SHA-256").digest(users.single().text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getString("firstTextSha256"),textSha)
        File(folder(),"original-creation-recovery.json").writeText(JSONObject().put("before",original.body()).put("after",confirmed.body()).put("threadId",session.selectedThread).put("runtimeState",session.runtimeState).toString())
        shot("05-original-creation-readonly-recovery")
    }
    @After fun preserveDefault() {
        if(::activity.isInitialized) {
            main { session.leaveForeground();owned?.verifyDefault() }
            i.runOnMainSync { listOfNotNull(activity,tracked).distinct().forEach { current->require(current.packageName==i.targetContext.packageName);if(!current.isDestroyed&&!current.isFinishing)current.finish() } }
        }
    }
    @Test fun originalNativeStopResumeAndRestart() {
        bind();ui.waitUntil(30_000) { !activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"real-owner") }
        val preflight=fixture.getBoolean("preflightOnly")
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android original stop DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
        if(preflight){control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("native-stop-preflight-unsent");return}
        val before=session.operationRecords.map { it.id }.toSet()
        try {
            claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(180_000) { session.transferStatus==null&&session.running&&session.runtime?.turnId!=null }
            val creation=session.operationRecords.single { it.id !in before&&it.kind=="creation" };val originTurn=session.runtime!!.turnId
            control("origin-ready",JSONObject().put("threadId",session.selectedThread).put("record",creation.body()).put("turnId",originTurn))
            shot("01-owned-original-running")
            val nonemptyDraft=fixture.optBoolean("nonemptyDraft")
            val retainedDraft=fixture.optString("retainedDraft")
            if(nonemptyDraft) {
                assertTrue(retainedDraft.isNotEmpty())
                ui.onNodeWithTag("message-input").performTextReplacement(retainedDraft)
                ui.waitUntil(10_000) { session.text==retainedDraft&&session.decision.action=="send" }
                assertEquals(originTurn,session.runtime?.turnId);assertTrue(session.canStop)
            }
            control("stop-ready",JSONObject().put("threadId",session.selectedThread).put("turnId",originTurn))
            hideKeyboard();ui.onNodeWithTag(if(nonemptyDraft)"send-message" else "stop-turn").assertIsEnabled().performTouchInput { longClick() }
            ui.onNodeWithTag("action-stop").assertIsDisplayed();menuShot("action-stop","original-stop-menu")
            clickOwnedMenu("停止任务","stop-menu-click")
            if(fixture.optBoolean("lateStop"))lateStop()
            ui.waitUntil(45_000) { !session.running&&session.transferStatus==null&&session.decision.action==if(nonemptyDraft)"send" else "resume" }
            if(nonemptyDraft)assertEquals(retainedDraft,session.text)
            control("stopped",JSONObject().put("threadId",session.selectedThread).put("records",records(before)).put("retainedDraft",session.text).put("composerAction",session.decision.action).put("originalTurnId",originTurn))
            shot(if(nonemptyDraft)"02-original-interrupted-draft-retained" else "02-original-interrupted-resume")
            if(nonemptyDraft) {
                // Explicitly discard only this examiner's synthetic local draft before fresh recovery gestures.
                ui.onNodeWithTag("message-input").performTextReplacement("")
                ui.waitUntil(10_000) { session.text.isEmpty()&&session.decision.action=="resume" }
            }
            // Confirmed creation resets settings; explicitly choose recovery settings as a real user would.
            settings();assertEquals("",session.text);assertTrue(session.attachments.isEmpty())
            assertEquals(com.agenthub.core.MobileRules.runAgainText(false),fixture.getJSONArray("cases").getJSONObject(1).getString("text"))
            hideKeyboard();ui.onNodeWithTag("resume-task").assertIsEnabled().performTouchInput { longClick() }
            ui.onNodeWithTag("action-resume").assertIsDisplayed();ui.onNodeWithTag("action-restart").assertIsDisplayed()
            menuShot("action-resume","original-continue-menu")
            claim(1);clickOwnedMenu("继续任务","continue-menu-click")
            ui.waitUntil(240_000) { !session.running&&session.transferStatus==null&&finalCount()==1 }
            control("case-complete",JSONObject().put("index",1).put("threadId",session.selectedThread).put("records",records(before)).put("pid",android.os.Process.myPid()));shot("03-resumed-original-context")
            settings();hideKeyboard();ownComposerReady("restart-menu")
            assertTrue(session.canRunAgain);assertEquals(com.agenthub.core.MobileRules.runAgainText(true),fixture.getJSONArray("cases").getJSONObject(2).getString("text"))
            ui.onNodeWithTag("send-message").assertIsNotEnabled().performTouchInput { longClick() }
            ui.onNodeWithTag("action-restart").assertIsDisplayed();menuShot("action-restart","original-restart-menu");claim(2);clickOwnedMenu("重新运行上一任务","restart-menu-click")
            ui.waitUntil(240_000) { !session.running&&session.transferStatus==null&&finalCount()==2 }
            control("case-complete",JSONObject().put("index",2).put("threadId",session.selectedThread).put("records",records(before)).put("pid",android.os.Process.myPid()))
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(fixture.getString("doneMarker")));shot("04-explicit-new-restart")
            ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        }finally{
            File(folder(),"final-native-records.json").writeText(JSONObject().put("threadId",session.selectedThread).put("records",records(before)).toString())
        }
    }
}
