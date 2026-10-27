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

/** Actual default encrypted creation restoration across owned app and Host process restart. */
class MobileCreationRestartModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-creation-restart-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","not-written-observed","claim-continuation","case-complete","restart-ready","restored-read-only","preflight-restored"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-creation-restart/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }

    private fun claim(index: Int) {
        control("claim-input",JSONObject().put("index",index).put("text",fixture.getJSONArray("cases").getJSONObject(index).getString("text"))
            .put("threadId",session.selectedThread).put("targetKey",session.targetKey))
    }
    private fun localProof(): JSONObject {
        val file=session.attachments.single()
        var bytes=byteArrayOf()
        main { bytes=NativeDraftFiles(NativeSessionStorage(i.targetContext)).bytes(file.manifest) }
        assertArrayEquals(fixture.getString("nextFileText").toByteArray(),bytes)
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return JSONObject().put("localText",session.text).put("localFiles",JSONArray().put(file.manifest.body())).put("localBytesSha256",sha)
    }
    private fun addNextLocalFile(): LocalDraftFile {
        assertTrue(session.attachments.isEmpty())
        main { session.addAttachment(fixture.getString("nextFileName"),"text/plain",fixture.getString("nextFileText").toByteArray()) }
        val file=session.attachments.single()
        ui.onNodeWithTag("draft-attachment-${file.manifest.id}").assertIsDisplayed()
        assertEquals(fixture.getString("independentDraft"),localProof().getString("localText"))
        return file
    }
    private fun withLocalProof(value: JSONObject): JSONObject {
        val proof=localProof();proof.keys().forEach { value.put(it,proof.get(it)) };return value.put("sourceKey",session.targetKey).put("generation",session.generationSettings.append(JSONObject()))
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    private fun awaitSavedDraft() {
        val store=NativeSessionStorage(i.targetContext)
        ui.waitUntil(20_000) {
            val meta=store.metadata()
            meta?.optString("selected")==fixture.getString("hostId")&&meta.optJSONObject("drafts")?.optString(session.targetKey)==fixture.getString("independentDraft")&&
                meta.optJSONObject("draftFiles")?.optJSONArray(session.targetKey)?.let { it.length()==1&&it.getJSONObject(0).toString()==session.attachments.single().manifest.body().toString() }==true&&
                meta.optJSONObject("generation")?.optJSONObject(session.targetKey)?.optString("model")==fixture.getString("model")
        }
    }
    @Test fun originalCreationSavesIndependentDraftBeforeRestart() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("Android creation restart DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();addNextLocalFile();awaitSavedDraft();shot("preflight-before-process-restart")
            assertTrue(session.operationRecords.isEmpty());control("preflight-complete",withLocalProof(JSONObject().put("pid",android.os.Process.myPid())));return
        }
        claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="creation"&&it.submissionAttempted&&it.phase=="outcome-unknown"&&session.mayContinueCreation(it) } }
        val first=session.operationRecords.single { it.kind=="creation" };val result=CodexCommandCodec.creation(JSONObject(checkNotNull(first.result)))
        assertEquals("input-retryable",result.preparation?.preparationPhase);assertEquals("retry-original-preflight",result.diagnostic?.disposition)
        assertFalse(first.detached);assertEquals("",session.selectedThread);assertTrue(session.aiSharing.permits(AISharingScope.decode(JSONObject(checkNotNull(first.sharingScope)))))
        File(folder(),"01-original-before-process-restart.json").writeText(first.body().toString())
        val observed=control("not-written-observed",JSONObject().put("record",first.body()).put("threadId",session.selectedThread));assertTrue(observed.getBoolean("zeroAcceptedInputsVerified"))
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();addNextLocalFile();awaitSavedDraft()
        val current=session.operationRecords.single { it.id==first.id };assertEquals(first.intent,current.intent);assertEquals(first.intentSha256,current.intentSha256);assertEquals(first.sharingScope,current.sharingScope)
        assertFalse(session.decision.enabled);ui.onNodeWithTag("user-message-text-${first.id}").assertTextEquals(JSONObject(first.intent).getString("text"));shot("01-original-and-independent-file-before-restart")
        control("restart-ready",withLocalProof(JSONObject().put("record",current.body()).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid())))
    }
    @Test fun originalCreationRestoresReadOnlyBeforeExplicitContinuation() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        // The normal installation restores its actual default encrypted files. No reset, reconnect or re-import.
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&session.hasClient&&session.selectedClient.id==fixture.getString("hostId")&&session.targetKey==fixture.getString("sourceKey") }
        assertFalse(session.localRecoveryRequired);assertEquals("",session.selectedThread);assertEquals(fixture.getString("independentDraft"),session.text)
        assertNotEquals(fixture.getInt("priorAppPid"),android.os.Process.myPid());assertEquals(fixture.getString("localFileId"),session.attachments.single().manifest.id)
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort);assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode);assertEquals("default",session.generationSettings.collaborationMode)
        hideKeyboard();localProof();ui.onNodeWithTag("draft-attachment-${session.attachments.single().manifest.id}").assertIsDisplayed()
        if(fixture.getBoolean("preflightOnly")) {
            assertTrue(session.operationRecords.isEmpty());shot("preflight-default-restoration-after-restart")
            control("preflight-restored",withLocalProof(JSONObject().put("pid",android.os.Process.myPid())));return
        }
        val id=fixture.getString("operationId")
        ui.waitUntil(45_000) { session.operationRecords.any { it.id==id&&it.kind=="creation" }&&session.transferStatus==null }
        val first=session.operationRecords.single { it.id==id };assertEquals(fixture.getString("originalIntentSha256"),first.intentSha256);assertTrue(first.submissionAttempted);assertFalse(first.detached)
        assertTrue(session.aiSharing.permits(AISharingScope.decode(JSONObject(checkNotNull(first.sharingScope)))))
        ui.onNodeWithTag("query-operation-$id").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.transferStatus==null&&session.operationRecords.single { it.id==id }.phase=="outcome-unknown" }
        val current=session.operationRecords.single { it.id==id };assertEquals(first.intent,current.intent);assertEquals(first.sharingScope,current.sharingScope);assertTrue(session.mayContinueCreation(current));assertFalse(session.decision.enabled)
        File(folder(),"02-default-restored-original-query-only.json").writeText(current.body().toString());shot("02-default-restored-original-and-local-file")
        control("restored-read-only",withLocalProof(JSONObject().put("record",current.body()).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid())))
        val chosen=session.generationSettings;val files=session.attachments
        val permit=control("claim-continuation",withLocalProof(JSONObject().put("record",current.body()).put("threadId",session.selectedThread)));assertTrue(permit.getBoolean("allowExplicitOriginal"))
        ui.onNodeWithTag("continue-creation-$id").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.operationRecords.single { it.id==id }.phase=="confirmed" }
        val confirmed=session.operationRecords.single { it.id==id };assertEquals(first.intent,confirmed.intent);assertEquals(first.intentSha256,confirmed.intentSha256);assertEquals(first.sharingScope,confirmed.sharingScope)
        assertFalse(confirmed.detached);assertEquals("",session.selectedThread);assertEquals(fixture.getString("independentDraft"),session.text);assertEquals(files,session.attachments);assertEquals(chosen,session.generationSettings);localProof()
        val cold=runBlocking { NativeOperationJournal(NativeSessionStorage(i.targetContext)).records(first.target).single { it.id==id } };assertEquals(confirmed.body().toString(),cold.body().toString())
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0);shot("03-explicit-original-confirmed-local-draft-retained")
        control("case-complete",withLocalProof(JSONObject().put("record",confirmed.body()).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid())))
    }
}
