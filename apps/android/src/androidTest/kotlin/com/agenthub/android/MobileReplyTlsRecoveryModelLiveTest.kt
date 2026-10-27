package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Fresh owned paired-TLS original reply absence/handoff/explicit retry. Preflight-only never creates an operation. */
class MobileReplyTlsRecoveryModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-reply-tls-recovery-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-seed","seed-complete","claim-reply","reply-intercepted","restart-ready","claim-retry","case-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original creation witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var resumed: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };resumed!=null }
        activity=checkNotNull(resumed)
    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-reply-tls-recovery/$nonce/${fixture.getString("evidenceRun")}/phase-${fixture.getInt("nativePhase")}").apply { mkdirs() }
    private fun focusedWindow(): android.view.Window {
        var found: android.view.Window?=null
        var latest=""
        try {
            ui.waitUntil(10_000) {
                i.runOnMainSync {
                    val owned=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.context.packageName==i.targetContext.packageName }
                    latest=owned.joinToString("\n") { "attached=${it.isAttachedToWindow};focus=${it.hasWindowFocus()};shown=${it.isShown};type=${it.javaClass.name}" }
                    val roots=owned.filter { it.isAttachedToWindow&&it.hasWindowFocus() }
                    require(roots.size<=1) { "More than one focused acceptance window" }
                    found=null
                    roots.singleOrNull()?.let { root ->
                        fun find(v: android.view.View) {
                            if(v is androidx.compose.ui.window.DialogWindowProvider) found=v.window
                            if(v is android.view.ViewGroup) for(n in 0 until v.childCount) find(v.getChildAt(n))
                        }
                        find(root)
                        if(found==null&&activity.window.decorView.rootView===root)found=activity.window
                        if(found!=null)assertTrue(found!!.decorView.rootView===root)
                    }
                }
                found!=null
            }
        }catch(failure: Throwable) {
            File(folder(),"own-window-not-ready.txt").writeText(latest)
            throw failure
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
        val window=focusedWindow()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var hidden=false;var visibleHeight=0
            i.runOnMainSync {
                val view=window.decorView
                hidden=androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
                val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
            }
            val now=android.os.SystemClock.uptimeMillis()
            if(!hidden||lastHeight!=visibleHeight) { stableSince=now;lastHeight=visibleHeight }
            hidden&&now-stableSince>=700
        }
        ui.waitForIdle();i.waitForIdleSync()
    }
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
    private fun allowSharingWithoutInput() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
        val before=session.text;val key=session.targetKey;assertTrue(session.operationRecords.isEmpty());hideKeyboard()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick();ui.waitUntil(15_000) { session.aiSharingRequest!=null }
        assertEquals(key,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertEquals(before,session.text);assertEquals(key,session.targetKey);assertTrue(session.operationRecords.isEmpty());assertNull(session.transferStatus);assertTrue(session.aiSharing.permits(scope))
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
    }
    private fun connectFresh() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        ui.runOnIdle { activity.readNetworkMonitor.stop();session.discovery?.stop();session.discovery=null;session.easyWorkDiscovery?.stop();session.easyWorkDiscovery=null;session.resetAcceptance() }
        main { session.connectCodexQR(fixture.getString("pairing"),"Android original reply TLS DEV") }
        ui.waitUntil(45_000) { session.hasClient&&session.selectedClient.online&&!session.loading&&session.models.isNotEmpty() }
        assertEquals(fixture.getString("hostId"),session.selectedClient.id);assertEquals(fixture.getString("fingerprint"),session.selectedClient.codexDirect!!.fingerprint)
        assertTrue(sameScope(HostReadCodec.target(fixture.getJSONObject("target")),session.selectedClient.scope))
        assertTrue(session.projects.any { it.id==fixture.getString("projectId") });ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        assertTrue(session.operationRecords.isEmpty());ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("firstText"));hideKeyboard();allowSharingWithoutInput()
    }
    private fun localProof(): JSONObject {
        val file=session.attachments.single();var bytes=byteArrayOf();main { bytes=NativeDraftFiles(NativeSessionStorage(i.targetContext)).bytes(file.manifest) }
        assertArrayEquals(fixture.getString("localFileText").toByteArray(),bytes);assertEquals(fixture.getString("independentDraft"),session.text)
        return JSONObject().put("localText",session.text).put("localFile",file.manifest.body()).put("sourceKey",session.targetKey).put("threadId",session.selectedThread)
            .put("generation",session.generationSettings.append(JSONObject())).put("fingerprint",session.selectedClient.codexDirect!!.fingerprint).put("pid",android.os.Process.myPid())
    }
    private fun laterDraft() {
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard()
        assertTrue(session.attachments.isEmpty());main { session.addAttachment(fixture.getString("localFileName"),"text/plain",fixture.getString("localFileText").toByteArray()) }
        ui.onNodeWithTag("draft-attachment-${session.attachments.single().manifest.id}").assertIsDisplayed();localProof()
    }
    private fun awaitSavedDraft() {
        val store=NativeSessionStorage(i.targetContext)
        ui.waitUntil(25_000) {
            val meta=store.metadata()
            meta?.optString("selected")==fixture.getString("hostId")&&meta.optJSONObject("drafts")?.optString(session.targetKey)==fixture.getString("independentDraft")&&
                meta.optJSONObject("draftFiles")?.optJSONArray(session.targetKey)?.let { it.length()==1&&it.getJSONObject(0).toString()==session.attachments.single().manifest.body().toString() }==true&&
                meta.optJSONObject("generation")?.optJSONObject(session.targetKey)?.optString("model")==fixture.getString("model")
        }
    }
    private fun ready(route: String,value: JSONObject) {
        val deadline=android.os.SystemClock.uptimeMillis()+200_000
        do { if(control(route,value).optBoolean("ready"))return;Thread.sleep(750) }while(android.os.SystemClock.uptimeMillis()<deadline)
        error("Original owner/canonical completion remains unknown; never replay the original input")
    }
    private fun original(): SavedOperation=session.operationRecords.single { it.kind=="reply"&&it.id==fixture.getString("originalReplyId") }
    private fun sameOriginal(record: SavedOperation) {
        val saved=fixture.getJSONObject("originalReply")
        assertEquals(saved.getString("id"),record.id);assertEquals(saved.getString("target"),record.target);assertEquals(saved.getString("intent"),record.intent)
        assertEquals(saved.getString("intentSha256"),record.intentSha256);assertEquals(saved.getString("sharingScope"),record.sharingScope);assertEquals(saved.getString("sourceVersion"),record.sourceVersion)
        assertEquals("codex:"+fixture.getString("fingerprint"),record.replyRecovery!!.trust)
        val cold=runBlocking { NativeOperationJournal(NativeSessionStorage(i.targetContext)).records(record.target).single { it.id==record.id } }
        assertEquals(record.intent,cold.intent);assertEquals(record.intentSha256,cold.intentSha256)
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    @Test fun prepareOriginalReplyAndSaveLaterDraft() {
        connectFresh()
        if(fixture.getBoolean("preflightOnly")) {
            laterDraft();awaitSavedDraft();assertTrue(session.operationRecords.isEmpty());shot("preflight-paired-local-draft-before-cold-restore")
            control("restart-ready",localProof().put("preflightOnly",true));return
        }
        control("claim-seed",JSONObject().put("text",session.text).put("sourceKey",session.targetKey));ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(200_000) { session.transferStatus==null&&session.selectedThread.isNotEmpty()&&session.operationRecords.any { it.kind=="creation"&&it.phase=="confirmed" } }
        val seed=session.operationRecords.single { it.kind=="creation" };ready("seed-complete",JSONObject().put("record",seed.body()).put("threadId",session.selectedThread))
        ui.waitUntil(60_000) { !session.running&&session.runtime?.canControl==true&&session.canConfigureGeneration };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("replyText"));hideKeyboard()
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertTrue(session.aiSharing.permits(scope))
        control("claim-reply",JSONObject().put("text",session.text).put("sourceKey",session.targetKey).put("threadId",session.selectedThread));ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(60_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="reply"&&JSONObject(it.intent).getString("text")==fixture.getString("replyText") } }
        val record=session.operationRecords.single { it.kind=="reply" };assertFalse(record.detached);assertTrue(record.submissionAttempted)
        assertEquals("codex:"+fixture.getString("fingerprint"),record.replyRecovery!!.trust);assertFalse(session.mayRetryReply(record));assertEquals(0,JSONObject(record.intent).getJSONArray("attachmentIds").length())
        control("reply-intercepted",JSONObject().put("record",record.body()).put("threadId",session.selectedThread));shot("original-reply-unknown-before-handoff")
        laterDraft();awaitSavedDraft();assertFalse(session.decision.enabled);assertEquals(record.intent,session.operationRecords.single { it.id==record.id }.intent)
        File(folder(),"original-before-restart.json").writeText(record.body().toString());shot("original-reply-and-independent-later-file-before-restart")
        control("restart-ready",localProof().put("record",record.body()).put("preflightOnly",false))
    }
    @Test fun restoreOriginalReplyAndRetryOnlyExplicitly() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.runOnIdle { activity.readNetworkMonitor.stop();session.discovery?.stop();session.discovery=null;session.easyWorkDiscovery?.stop();session.easyWorkDiscovery=null }
        // Normal MainActivity/default encrypted restore. No reset, reconnect, record copy or file import.
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&session.hasClient&&session.selectedClient.online&&session.selectedClient.id==fixture.getString("hostId")&&session.targetKey==fixture.getString("sourceKey") }
        assertFalse(session.localRecoveryRequired);assertNotEquals(fixture.getInt("priorAppPid"),android.os.Process.myPid())
        assertEquals(fixture.getString("fingerprint"),session.selectedClient.codexDirect!!.fingerprint);assertEquals(fixture.getString("localFileId"),session.attachments.single().manifest.id)
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort);assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        assertTrue(session.aiSharing.permits(AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"))))
        hideKeyboard();localProof();ui.onNodeWithTag("draft-attachment-${session.attachments.single().manifest.id}").assertIsDisplayed()
        if(fixture.getBoolean("preflightOnly")) {
            assertEquals("",session.selectedThread);assertTrue(session.operationRecords.isEmpty());shot("preflight-default-paired-cold-restore-no-operation")
            control("preflight-complete",localProof().put("newModelInputs",0));return
        }
        assertEquals(fixture.getString("threadId"),session.selectedThread);ui.waitUntil(45_000) { session.operationRecords.any { it.id==fixture.getString("originalReplyId") } }
        sameOriginal(original());val sharing=AISharingScope.decode(JSONObject(checkNotNull(original().sharingScope)));assertTrue(session.aiSharing.permits(sharing))
        ui.waitUntil(60_000) { session.selectedClient.online&&session.transferStatus==null }
        ui.onNodeWithTag("query-operation-${original().id}").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(60_000) { session.transferStatus==null&&session.mayRetryReply(original()) };sameOriginal(original());localProof()
        assertEquals("strong-not-accepted",original().replyRecovery!!.phase);shot("original-reply-strong-absence-after-cold-restore")
        control("claim-retry",localProof().put("record",original().body()));ui.onNodeWithTag("retry-operation-${original().id}").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(200_000) { session.transferStatus==null&&original().phase in listOf("submitted","completed") }
        ready("case-complete",localProof().put("record",original().body()));sameOriginal(original());localProof();assertTrue(original().replyRecovery!!.consumed)
        assertFalse(session.mayRetryReply(original()));assertNull(session.error);shot("original-reply-accepted-once-later-file-and-draft-preserved")
        File(folder(),"original-after-retry.json").writeText(original().body().toString())
    }
}
