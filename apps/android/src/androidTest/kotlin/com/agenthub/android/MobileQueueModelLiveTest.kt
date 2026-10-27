package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Fresh original-owner native queue, one immutable lease per input and exact middle cancellation. */
class MobileQueueModelLiveTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
            require(fixture.getString("hostId").matches(Regex("[a-f0-9-]{36}")))
            val stream=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")
            val result=ParcelFileDescriptor.AutoCloseInputStream(stream).bufferedReader().use { it.readText() }
            assertTrue("Activate exact acceptance App before ActivityScenario starts",result.contains("Status: ok"))
        }
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var isolated: MobileSession
    private val session get()=isolated
    private var defaultDrafts=emptyMap<String,String?>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-queue-model-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","origin-ready","queue-ready","cancel-ready","queue-cancelled","execute-ready","queue-executed","arm-sync-failure","sync-failure-visible","recover-sync","sync-recovered","case-complete","long-turn-complete","long-history-ready","long-cache-complete"))
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
        activity=ui.activity
        ui.waitUntil(30_000) { !activity.session.restoring }
        ui.runOnIdle {
            activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
            val metadata=NativeSessionStorage(i.targetContext).metadata();defaultDrafts=listOf("drafts","draftFiles","draftVersions").associateWith { metadata?.opt(it)?.toString() }
            val storage=NativeSessionStorage(i.targetContext,"P4-queue-model-"+nonce)
            if(!fixture.optBoolean("resume"))require(storage.metadata()==null) { "Fresh dedicated queue namespace only; never reset a prior submitted scene" }
            isolated=MobileSession(storage);activity.setContent { AgentHubApp(isolated) { } }
        }
    }
    @After fun preserveOriginalDrafts() {
        if(::activity.isInitialized)runCatching {
            File(folder(),"own-native-lifecycle.json").writeText(JSONObject().put("package",i.targetContext.packageName).put("activityDestroyed",activity.isDestroyed).put("activityFinishing",activity.isFinishing).put("attached",activity.window.decorView.isAttachedToWindow).put("focus",activity.window.decorView.hasWindowFocus()).put("threadId",if(::isolated.isInitialized)isolated.selectedThread else JSONObject.NULL).toString())
        }
        if(::isolated.isInitialized)main { isolated.leaveForeground() }
        val metadata=NativeSessionStorage(i.targetContext).metadata()
        for((key,value)in defaultDrafts)assertEquals("Real queue controls preserve default $key",value,metadata?.opt(key)?.toString())
        // The exact activation target may differ from ActivityScenario's handle.
        // Close only these owned handles after evidence/storage assertions, so
        // the rule cannot wait forever for an obscured paused Activity.
        val tracked=runCatching { ui.activity }.getOrNull()
        if(::activity.isInitialized)i.runOnMainSync {
            val owned=listOfNotNull(activity,tracked).distinct()
            File(folder(),"owned-activity-cleanup.json").writeText(JSONObject().put("package",i.targetContext.packageName).put("handles",owned.size).put("trackedEqualsBound",tracked===activity).put("onlyExactHandles",true).toString())
            owned.forEach { current->require(current.packageName==i.targetContext.packageName);if(!current.isDestroyed&&!current.isFinishing)current.finish() }
        }
    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-queue-model/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun state(name: String) {
        File(folder(),"$name-state.json").writeText(JSONObject().put("targetKey",session.targetKey).put("client",session.selectedClient.id).put("text",session.text).put("textLength",session.text.length).put("canCompose",session.canCompose).put("canCreate",session.canCreateThread).put("online",session.selectedClient.online).put("decision",session.decision.action).put("enabled",session.decision.enabled).put("transfer",session.transferStatus).put("error",session.error).put("loading",session.loading).put("thread",session.selectedThread).toString(2))
    }
    private fun ownComposerReady(label: String) {
        var focused=false
        i.runOnMainSync { focused=activity.window.decorView.isAttachedToWindow&&activity.window.decorView.hasWindowFocus() }
        if(!focused) {
            File(folder(),"$label-foreground-recovery.json").writeText(JSONObject().put("package",i.targetContext.packageName).put("method","SDK exact acceptance Activity activation").put("threadId",session.selectedThread).toString())
            val stream=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")
            val result=ParcelFileDescriptor.AutoCloseInputStream(stream).bufferedReader().use { it.readText() }
            assertTrue(result.contains("Status: ok"))
        }
        // Rebind only the resumed MainActivity from this instrumentation target.
        // The original editable session and its immutable records survive a
        // destroyed rule Activity; never treat its stale Window as the product.
        ui.waitUntil(20_000) {
            var ready=false
            i.runOnMainSync {
                val current=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                if(current!=null&&!current.isDestroyed&&!current.isFinishing&&!current.session.restoring) {
                    require(current.packageName==i.targetContext.packageName)
                    if(current!==activity) {
                        activity=current;activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
                        activity.setContent { AgentHubApp(isolated) { } }
                    }
                    ready=true
                }
            }
            ready
        }
        try { ui.waitUntil(20_000) {
            var own=false;i.runOnMainSync { own=!activity.isDestroyed&&activity.window.decorView.isAttachedToWindow&&activity.window.decorView.hasWindowFocus() }
            own&&try { ui.onAllNodesWithTag("composer").fetchSemanticsNodes().isNotEmpty() }
            catch(e: IllegalStateException) { if(e.message?.contains("No compose hierarchies")==true)false else throw e }
        } } catch(e: Throwable) { state("$label-not-ready");throw e }
    }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ownComposerReady("settings")
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        if(session.selectedCollaboration!="plan") { ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick() }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
        assertEquals("default",session.generationSettings.collaborationMode)
    }
    private fun shot(name: String) {
        ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,activity.window.decorView.context.packageName)
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
        // Keep this rule's Activity and the same isolated editable draft when
        // testing the product keyboard-dismiss control and its SDK IME transition.
        val current=if(activity.isDestroyed||activity.isFinishing)ui.activity else activity
        if(current!==activity)ui.runOnIdle {
            activity=current;activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
            activity.setContent { AgentHubApp(isolated) { } }
        }
        val originalTarget=session.targetKey;val originalDraft=session.text
        ownComposerReady("keyboard")
        ui.waitUntil(15_000) {
            try { ui.onAllNodesWithTag("composer").fetchSemanticsNodes().isNotEmpty() }
            catch(e: IllegalStateException) { if(e.message?.contains("No compose hierarchies")==true)false else throw e }
        }
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("composer-dismiss-keyboard").assertIsDisplayed().performTouchInput { click(center) }
        i.runOnMainSync { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var ready=false;var visibleHeight=0
            i.runOnMainSync {
                val view=activity.window.decorView;val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
                ready=view.isAttachedToWindow&&view.hasWindowFocus()&&visibleHeight>0&&androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
            }
            val now=android.os.SystemClock.uptimeMillis();if(!ready||lastHeight!=visibleHeight){stableSince=now;lastHeight=visibleHeight}
            ready&&now-stableSince>=700
        }
        ui.waitUntil(15_000) {
            try { ui.onAllNodesWithTag("composer").fetchSemanticsNodes().isNotEmpty() }
            catch(e: IllegalStateException) { if(e.message?.contains("No compose hierarchies")==true)false else throw e }
        }
        ui.waitForIdle();i.waitForIdleSync()
        assertEquals("App keyboard-dismiss preserves target",originalTarget,session.targetKey)
        assertEquals("App keyboard-dismiss preserves original editable content",originalDraft,session.text)
    }
    private fun allowSharing() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
        val text=session.text;val target=session.targetKey;val records=session.operationRecords.map { it.id }.toSet()
        state("sharing-before-ime");hideKeyboard();state("sharing-after-ime");shot("sharing-before-click")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(10_000) { session.aiSharingRequest!=null } }
        catch(e: Throwable) { shot("sharing-request-absent");File(folder(),"sharing-diagnostic.txt").writeText("error=${session.error};canCompose=${session.canCompose};text=${session.text}\n"+ui.onNodeWithTag("composer").printToString());throw e }
        assertEquals(target,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertEquals(text,session.text);assertEquals(target,session.targetKey);assertEquals(records,session.operationRecords.map { it.id }.toSet());assertNull(session.transferStatus)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }
    private fun claim(index: Int,mode: String) {
        val row=fixture.getJSONArray("cases").getJSONObject(index)
        control("claim-input",JSONObject().put("index",index).put("text",row.getString("text")).put("mode",mode).put("threadId",session.selectedThread).put("targetKey",session.targetKey).put("expectedTurnId",if(mode=="steer")session.runtime!!.turnId else JSONObject.NULL))
    }
    private fun input(index: Int,mode: String) {
        ui.waitUntil(45_000) { session.canCompose&&session.transferStatus==null&&session.running&&session.canSelectReplyMode&&mode in session.replyModes }
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(index).getString("text"));hideKeyboard()
        ui.onNodeWithTag("reply-mode-$mode").assertIsEnabled().performTouchInput { click(center) };assertEquals(mode,session.replyMode);claim(index,mode)
        ui.onNodeWithTag("send-message").assertIsEnabled().performTouchInput { click(center) }
    }
    @Test fun readOnlyOriginalQueueRecords() {
        bind();ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android queue original records read only",fixture.getString("endpoint"),fixture.getString("token")) }
        val thread=fixture.getString("threadId")
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==thread } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.runtime!=null&&session.operationRecords.any { it.id==fixture.getString("priorOperationId") } }
        val records=session.operationRecords
        assertTrue(records.all { JSONObject(it.intent).getString("hostId")==fixture.getString("hostId") })
        File(folder(),"original-records-readonly.json").writeText(JSONObject().put("hostId",fixture.getString("hostId")).put("threadId",thread).put("records",org.json.JSONArray(records.map { it.body() })).toString())
        shot("05-original-records-readonly")
    }
    @Test fun readOnlyOriginalCompletedNativeQueue() {
        bind();require(fixture.getBoolean("terminalReadOnly"))
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("Android original completed queue read only",fixture.getString("endpoint"),fixture.getString("token")) }
        val thread=fixture.getString("threadId");val originals=fixture.getJSONArray("originals")
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==thread } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(60_000) { !session.historyLoading&&session.runtime!=null&&!session.running&&session.queuedReplies.isEmpty()&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==fixture.getString("doneMarker") } }
        ownComposerReady("readonly-terminal")
        val originalStorage=NativeSessionStorage(i.targetContext,"P4-queue-model-"+fixture.getString("originalStorageNonce"))
        val originalMetadata=originalStorage.metadata()?.toString()
        var originalRecords=emptyList<SavedOperation>()
        main {
            val journal=NativeOperationJournal(originalStorage)
            originalRecords=(session.selectedClient.operationTargets(null)+session.selectedClient.operationTargets(thread)).distinct().flatMap { journal.records(it) }
        }
        for(index in 0 until originals.length()) {
            val expected=originals.getJSONObject(index);val id=expected.getString("id")
            val record=originalRecords.single { it.id==id }
            assertEquals(expected.getString("intentSha256"),record.intentSha256)
            assertEquals(record.intentSha256,java.security.MessageDigest.getInstance("SHA-256").digest(record.intent.toByteArray()).joinToString("") { "%02x".format(it) })
            if(expected.has("state"))assertEquals(expected.getString("state"),session.runtime!!.receipts.single { it.operationId==id }.state)
        }
        assertEquals(originalMetadata,originalStorage.metadata()?.toString());assertTrue(session.operationRecords.isEmpty())
        assertEquals("",session.text);ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(fixture.getString("doneMarker")));shot("readonly-original-queue-terminal")
        File(folder(),"readonly-terminal-proof.json").writeText(JSONObject().put("originalRecords",originals.length()).put("threadId",thread).put("queueCount",session.queuedReplies.size).put("running",session.running).put("newInputs",0).toString())
    }
    @Test fun originalNativeQueueCancellationAndSteer() {
        bind();val preflight=fixture.getBoolean("preflightOnly")
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android original native queue DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        val before: Set<String>;val record: SavedOperation;val originTurn: String?
        if(fixture.getBoolean("resume")) {
            val thread=fixture.getString("threadId");ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.runtime!=null }
            assertTrue("Only continue the original still-active task; never resend it",session.running)
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"))
            ui.onNodeWithTag("reply-mode-queue").assertIsEnabled().performClick();settings();allowSharing()
            record=session.operationRecords.single { it.id==fixture.getString("priorOperationId") }
            before=session.operationRecords.map { it.id }.toSet()-record.id;originTurn=session.runtime!!.turnId
        }else{
            ui.waitUntil(30_000) { session.canCreateThread };ui.runOnIdle { session.newChat(fixture.getString("projectId")) };state("after-new-chat");settings();state("after-settings")
            ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));state("after-first-input");assertEquals(fixture.getJSONArray("cases").getJSONObject(0).getString("text"),session.text);allowSharing()
            if(preflight){assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("native-queue-preflight-unsent");return}
            before=session.operationRecords.map { it.id }.toSet();claim(0,"next-turn");ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(180_000) { session.transferStatus==null&&session.running&&session.runtime?.turnId!=null }
            record=session.operationRecords.single { it.id !in before&&it.kind=="creation" };originTurn=session.runtime!!.turnId
        }
        control("origin-ready",JSONObject().put("threadId",session.selectedThread).put("record",record.body()).put("turnId",originTurn))
        if(!fixture.getBoolean("resume")) {
            // A confirmed creation resets its placeholder settings on both platforms.
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"));hideKeyboard()
            ui.onNodeWithTag("reply-mode-queue").assertIsEnabled().performClick();settings()
        }
        for(index in 1..3){input(index,"queue");ui.waitUntil(30_000) { session.queuedReplies.size==index&&session.transferStatus==null }}
        val queued=session.queuedReplies.toList();assertEquals(3,queued.size);assertTrue(queued.all { it.queueSource=="native"&&it.queuePresent==true })
        queued.forEachIndexed { index,r->ui.onNodeWithTag("queued-number-${r.operationId}").performScrollTo().assertTextEquals("${index+1}") }
        control("queue-ready",JSONObject().put("threadId",session.selectedThread).put("turnId",originTurn).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })))
        shot("01-native-numbered-queue");control("cancel-ready",JSONObject().put("threadId",session.selectedThread).put("operationId",queued[1].operationId))
        ui.onNodeWithTag("cancel-queued-${queued[1].operationId}").performScrollTo().assertIsEnabled().performTouchInput { click(center) }
        ui.waitUntil(30_000) { session.queuedReplies.size==2&&session.runtime?.receipts?.any { it.operationId==queued[1].operationId&&it.state=="cancelled" }==true }
        ui.onNodeWithTag("queued-number-${queued[2].operationId}").performScrollTo().assertTextEquals("2")
        control("queue-cancelled",JSONObject().put("threadId",session.selectedThread));shot("02-cancelled-middle-renumbered")
        input(4,"steer");ui.waitUntil(30_000) { session.transferStatus==null&&session.runtime?.receipts?.any { it.text==fixture.getJSONArray("cases").getJSONObject(4).getString("text")&&it.turnId==originTurn&&it.state in listOf("submitted","completed") }==true }
        shot("03-same-turn-steer")
        val marker=fixture.getString("doneMarker")
        try { ui.waitUntil(300_000) { !session.running&&session.transferStatus==null&&session.queuedReplies.isEmpty()&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker } } }
        catch(e: ComposeTimeoutException){shot("queue-original-outcomes-unresolved");throw AssertionError("Never replay original queued/input IDs: ${session.runtimeState}; ${session.error}",e)}
        ownComposerReady("queue-terminal");ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        control("case-complete",JSONObject().put("threadId",session.selectedThread).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })).put("pid",android.os.Process.myPid()))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));shot("04-original-fifo-outcomes")
    }
    @Test fun originalNativeQueueImmediateAppend() {
        bind();require(fixture.getString("scenario")=="immediate-append")
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android queue immediate append DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.waitUntil(30_000) { session.canCreateThread };ui.runOnIdle { session.newChat(fixture.getString("projectId")) };state("after-new-chat");settings();state("after-settings")
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));state("after-first-input");assertEquals(fixture.getJSONArray("cases").getJSONObject(0).getString("text"),session.text);allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("immediate-append-preflight-unsent");return
        }
        val before=session.operationRecords.map { it.id }.toSet();claim(0,"next-turn");ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.running&&session.runtime?.turnId!=null }
        val record=session.operationRecords.single { it.id !in before&&it.kind=="creation" };val originTurn=checkNotNull(session.runtime!!.turnId)
        control("origin-ready",JSONObject().put("threadId",session.selectedThread).put("record",record.body()).put("turnId",originTurn))
        // Confirmed creation clears placeholder preferences; choose them explicitly for the queued inputs.
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"));hideKeyboard()
        ui.onNodeWithTag("reply-mode-queue").assertIsEnabled().performClick();settings()
        for(index in 1..2) { input(index,"queue");ui.waitUntil(30_000) { session.queuedReplies.size==index&&session.transferStatus==null } }
        val queued=session.queuedReplies.toList();assertTrue(queued.all { it.queueSource=="native"&&it.queuePresent==true })
        queued.forEachIndexed { index,r->ui.onNodeWithTag("queued-number-${r.operationId}").performScrollTo().assertTextEquals("${index+1}") }
        val original=session.operationRecords.single { it.id==queued[0].operationId };assertEquals("queue",JSONObject(original.intent).getString("mode"))
        control("queue-ready",JSONObject().put("threadId",session.selectedThread).put("turnId",originTurn).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })))
        shot("01-immediate-append-original-queue")
        // A cached queue can remain visible while the current read/control proof
        // catches up. Wait for the same original row and Turn to be writable.
        state("append-before-control-readiness")
        ui.waitUntil(30_000) { session.running&&session.runtime?.turnId==originTurn&&session.canExecuteQueue(queued[0],session.targetKey,originTurn) }
        ownComposerReady("append-control")
        control("execute-ready",JSONObject().put("threadId",session.selectedThread).put("operationId",queued[0].operationId).put("turnId",originTurn))
        ui.onNodeWithTag("execute-queued-${queued[0].operationId}").performScrollTo().assertIsEnabled().assertTextContains("立即追加").performTouchInput { click(center) }
        ui.waitUntil(30_000) { session.queuedReplies.map { it.operationId }==listOf(queued[1].operationId)&&session.runtime?.receipts?.any { it.operationId==queued[0].operationId&&it.turnId==originTurn&&it.state in listOf("submitted","completed") }==true }
        val after=session.operationRecords.single { it.id==original.id };assertEquals(original.intent,after.intent);assertEquals(original.intentSha256,after.intentSha256)
        ui.onNodeWithTag("queued-number-${queued[1].operationId}").performScrollTo().assertTextEquals("1")
        control("queue-executed",JSONObject().put("threadId",session.selectedThread).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })))
        shot("02-original-row-appended-same-turn")
        val marker=fixture.getString("doneMarker")
        try { ui.waitUntil(300_000) { !session.running&&session.transferStatus==null&&session.queuedReplies.isEmpty()&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker } } }
        catch(e: ComposeTimeoutException) { shot("immediate-append-outcomes-unresolved");throw AssertionError("Never replay original queue IDs: ${session.runtimeState}; ${session.error}",e) }
        ownComposerReady("append-terminal");ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        control("case-complete",JSONObject().put("threadId",session.selectedThread).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })).put("pid",android.os.Process.myPid()))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));shot("03-immediate-append-original-outcomes")
    }
    @Test fun originalNativeQueueSyncFailureAndRecovery() {
        bind();require(fixture.getString("scenario")=="sync-recovery")
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android queue sync recovery DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.waitUntil(30_000) { session.canCreateThread };ui.runOnIdle { session.newChat(fixture.getString("projectId")) };state("after-new-chat");settings();state("after-settings")
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));state("after-first-input");assertEquals(fixture.getJSONArray("cases").getJSONObject(0).getString("text"),session.text);allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("sync-recovery-preflight-unsent");return
        }
        val before=session.operationRecords.map { it.id }.toSet();claim(0,"next-turn");ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.running&&session.runtime?.turnId!=null }
        val record=session.operationRecords.single { it.id !in before&&it.kind=="creation" };val originTurn=checkNotNull(session.runtime!!.turnId)
        control("origin-ready",JSONObject().put("threadId",session.selectedThread).put("record",record.body()).put("turnId",originTurn))
        // Confirmed creation clears placeholder preferences; choose them explicitly for the queued inputs.
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"));hideKeyboard()
        ui.onNodeWithTag("reply-mode-queue").assertIsEnabled().performClick();settings()
        for(index in 1..1) { input(index,"queue");ui.waitUntil(30_000) { session.queuedReplies.size==index&&session.transferStatus==null } }
        val queued=session.queuedReplies.toList();assertTrue(queued.all { it.queueSource=="native"&&it.queuePresent==true })
        queued.forEachIndexed { index,r->ui.onNodeWithTag("queued-number-${r.operationId}").performScrollTo().assertTextEquals("${index+1}") }
        val original=session.operationRecords.single { it.id==queued[0].operationId };assertEquals("queue",JSONObject(original.intent).getString("mode"))
        control("queue-ready",JSONObject().put("threadId",session.selectedThread).put("turnId",originTurn).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })))
        val draft="Independent unsent sync-recovery draft "+nonce
        ui.onNodeWithTag("message-input").performTextReplacement(draft);hideKeyboard()
        shot("01-sync-original-queue-and-draft")
        fun proof()=JSONObject().put("threadId",session.selectedThread).put("operationId",original.id).put("intentSha256",original.intentSha256).put("draft",session.text).put("turnId",originTurn)
        control("arm-sync-failure",proof())
        fun refresh() {
            ownComposerReady("sync-refresh");ui.onNodeWithTag("chat-menu").performClick()
            ui.onNodeWithTag("refresh-history").assertIsEnabled().performTouchInput { click(center) }
        }
        refresh()
        ui.waitUntil(30_000) { session.runtime?.receipts?.any { it.operationId==original.id&&!it.queueSyncError.isNullOrBlank() }==true }
        val failed=session.queuedReplies.single();assertEquals(original.id,failed.operationId);assertEquals(true,failed.queuePresent)
        assertFalse("Failed read cannot enable execute",session.canExecuteQueue(failed,session.targetKey,originTurn))
        ui.onNodeWithTag("receipt-"+original.id).performScrollTo().assertIsDisplayed();ui.onNodeWithText("客户端队列同步失败").assertIsDisplayed();shot("02-sync-error-card")
        ui.onNodeWithTag("queued-number-"+original.id).performScrollTo().assertTextEquals("1")
        ui.onNodeWithTag("execute-queued-"+original.id).assertIsNotEnabled()
        assertEquals(draft,session.text);assertEquals(original.intent,session.operationRecords.single { it.id==original.id }.intent)
        control("sync-failure-visible",proof());shot("02-sync-failed-original-retained")
        control("recover-sync",proof());refresh()
        ui.waitUntil(30_000) { session.running&&session.runtime?.turnId==originTurn&&session.queuedReplies.singleOrNull()?.let { it.operationId==original.id&&it.queueSyncError.isNullOrBlank()&&session.canExecuteQueue(it,session.targetKey,originTurn) }==true }
        ui.onNodeWithTag("receipt-"+original.id).assertDoesNotExist()
        assertEquals(draft,session.text);assertEquals(original.intentSha256,session.operationRecords.single { it.id==original.id }.intentSha256)
        control("sync-recovered",proof());shot("03-sync-recovered-original-row")
        ownComposerReady("sync-execute")
        control("execute-ready",JSONObject().put("threadId",session.selectedThread).put("operationId",queued[0].operationId).put("turnId",originTurn))
        ui.onNodeWithTag("execute-queued-${queued[0].operationId}").performScrollTo().assertIsEnabled().assertTextContains("立即追加").performTouchInput { click(center) }
        ui.waitUntil(30_000) { session.queuedReplies.isEmpty()&&session.runtime?.receipts?.any { it.operationId==queued[0].operationId&&it.turnId==originTurn&&it.state in listOf("submitted","completed") }==true }
        val after=session.operationRecords.single { it.id==original.id };assertEquals(original.intent,after.intent);assertEquals(original.intentSha256,after.intentSha256)
        control("queue-executed",JSONObject().put("threadId",session.selectedThread).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })))
        assertEquals(draft,session.text);shot("04-recovered-original-appended-same-turn")
        val marker=fixture.getString("doneMarker")
        try { ui.waitUntil(300_000) { !session.running&&session.transferStatus==null&&session.queuedReplies.isEmpty()&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker } } }
        catch(e: ComposeTimeoutException) { shot("sync-recovery-outcomes-unresolved");throw AssertionError("Never replay original queue IDs: ${session.runtimeState}; ${session.error}",e) }
        ownComposerReady("sync-terminal");assertEquals(draft,session.text);ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        control("case-complete",JSONObject().put("threadId",session.selectedThread).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })).put("pid",android.os.Process.myPid()))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));shot("05-sync-recovery-original-outcomes")
    }
    @Test fun originalLongHistoryRevisionAndReceipt() {
        bind();require(fixture.getString("scenario")=="long-receipt")
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android original long receipt DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        val thread=fixture.getString("threadId");val oldId=fixture.getString("oldReceiptId");val oldTurn=fixture.getString("oldTurnId")
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.threads.any { it.id==thread } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread }) }
        ui.waitUntil(45_000) { !session.historyLoading&&!session.running&&session.runtime!=null }
        fun originalReceipt() {
            var receipt: DeliveryReceipt?=null
            main { receipt=NativeCodexCommands { fixture.getString("token") }.receipt(session.selectedClient,thread,oldId) }
            assertEquals(oldId,receipt!!.operationId);assertEquals("completed",receipt!!.state);assertEquals(oldTurn,receipt!!.turnId)
        }
        originalReceipt()
        val originalStorage=NativeSessionStorage(i.targetContext,"P4-queue-model-"+fixture.getString("originalStorageNonce"));val oldMetadata=originalStorage.metadata()?.toString()
        fun originalIntents(): Map<String,String> {
            var result=emptyMap<String,String>()
            main { val journal=NativeOperationJournal(originalStorage);result=(session.selectedClient.operationTargets(null)+session.selectedClient.operationTargets(thread)).distinct().flatMap { journal.records(it) }.associate { it.id to it.intentSha256 } }
            return result
        }
        val oldIntents=originalIntents();assertEquals(2,oldIntents.size)
        if(fixture.has("oldIntentSha256"))assertEquals(fixture.getString("oldIntentSha256"),oldIntents[oldId])
        val cases=fixture.getJSONArray("cases")
        if(!fixture.optBoolean("longReadOnly")) {
            settings();ui.onNodeWithTag("message-input").performTextReplacement(cases.getJSONObject(0).getString("text"));allowSharing()
        } else { assertEquals(0,cases.length());assertFalse(fixture.getBoolean("preflightOnly")) }
        if(fixture.getBoolean("preflightOnly")) {
            control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("threadId",thread).put("targetKey",session.targetKey));shot("long-receipt-preflight-unsent");return
        }
        val before=session.operationRecords.map { it.id }.toSet()
        for(index in 0 until cases.length()) {
            ownComposerReady("long-input-$index")
            ui.waitUntil(45_000) { session.canCompose&&!session.running&&session.transferStatus==null }
            ui.onNodeWithTag("message-input").performTextReplacement(cases.getJSONObject(index).getString("text"));hideKeyboard()
            settings();hideKeyboard()
            claim(index,"next-turn");ui.onNodeWithTag("send-message").assertIsEnabled().performTouchInput { click(center) }
            val marker=cases.getJSONObject(index).getString("marker")
            ui.waitUntil(180_000) { !session.running&&session.transferStatus==null&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker } }
            val records=session.operationRecords.filter { it.id !in before };assertEquals(index+1,records.size)
            control("long-turn-complete",JSONObject().put("threadId",thread).put("index",index).put("records",org.json.JSONArray(records.map { it.body() })))
            if(index in listOf(0,28,30)) { ownComposerReady("long-completed-$index");shot("long-completed-$index") }
        }
        val records=session.operationRecords.filter { it.id !in before };val frozen=records.associate { it.id to it.intentSha256 }
        val draft="Independent unsent long receipt draft "+nonce
        ownComposerReady("long-draft");ui.onNodeWithTag("message-input").performTextReplacement(draft);hideKeyboard()
        control("long-history-ready",JSONObject().put("threadId",thread).put("records",org.json.JSONArray(records.map { it.body() })))
        ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("refresh-history").performTouchInput { click(center) }
        ui.waitUntil(45_000) { !session.historyLoading&&session.runtime?.receipts?.size==30 }
        originalReceipt();assertTrue(session.runtime!!.receipts.none { it.operationId==oldId })
        assertEquals(draft,session.text);assertTrue(session.queuedReplies.isEmpty());ui.onNodeWithTag("receipt-"+oldId).assertDoesNotExist()
        val target=session.targetKey
        ui.waitUntil(30_000) { NativeHistoryCache(NativeSessionStorage(i.targetContext,"P4-queue-model-"+nonce)).read(target)?.runtime?.receipts?.size==30 }
        shot("long-old-receipt-after-revision")
        main { session.leaveForeground() }
        ui.runOnIdle { isolated=MobileSession(NativeSessionStorage(i.targetContext,"P4-queue-model-"+nonce));activity.setContent { AgentHubApp(isolated) { } };session.restore() }
        ui.waitUntil(60_000) { !session.restoring&&!session.historyLoading&&session.selectedThread==thread&&session.text==draft&&session.runtime?.receipts?.size==30 }
        originalReceipt()
        assertEquals(target,session.targetKey);assertEquals(frozen,session.operationRecords.filter { it.id !in before }.associate { it.id to it.intentSha256 });assertEquals(oldMetadata,originalStorage.metadata()?.toString());assertEquals(oldIntents,originalIntents())
        File(folder(),"long-original-receipt-proof.json").writeText(JSONObject().put("threadId",thread).put("oldReceiptId",oldId).put("oldAcceptedTurnId",oldTurn).put("originalIntentHashes",JSONObject(oldIntents)).put("recentRuntimeReceipts",session.runtime!!.receipts.size).put("originalTypedReceiptReads",3).put("encryptedSessionReopened",true).put("originalMetadataPreserved",true).toString())
        assertTrue(session.queuedReplies.isEmpty());assertNull(session.transferStatus);ui.onNodeWithTag("stop-turn").assertDoesNotExist();ui.onNodeWithTag("receipt-"+oldId).assertDoesNotExist()
        control("long-cache-complete",JSONObject().put("threadId",thread).put("draft",session.text).put("records",org.json.JSONArray(session.operationRecords.filter { it.id !in before }.map { it.body() })).put("pid",android.os.Process.myPid()))
        ownComposerReady("long-terminal");shot("long-old-receipt-encrypted-reopen")
    }

}
