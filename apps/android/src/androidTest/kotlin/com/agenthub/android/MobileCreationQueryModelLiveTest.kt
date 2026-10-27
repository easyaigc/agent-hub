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

/** Actual read-only original detached creation query with strict navigation and input fences. */
class MobileCreationQueryModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-creation-query-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-query","await-query-start","release-query","query-complete","case-complete"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-creation-query/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
    private fun originalRecord(): SavedOperation {
        val rows=runBlocking { NativeOperationJournal(NativeSessionStorage(i.targetContext)).records(fixture.getString("originalTarget")) }
        val original=rows.singleOrNull { it.id==fixture.getString("operationId") } ?: error("Exact original device journal record is unavailable; no reconstruction is permitted")
        assertEquals("creation",original.kind);assertTrue(original.detached);assertEquals("confirmed",original.phase)
        assertEquals(fixture.getString("originalIntentSha256"),original.intentSha256);assertEquals(fixture.getString("originalSourceKey"),original.sourceKey)
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(original.intent.toByteArray()).joinToString("") { "%02x".format(it) };assertEquals(original.intentSha256,sha)
        assertEquals(fixture.getString("hostId"),JSONObject(original.intent).getString("hostId"));return original
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    private fun navigateOriginal() {
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) }
        ui.waitUntil(20_000) { session.selectedThread.isEmpty()&&session.targetKey==fixture.getString("originalSourceKey")&&session.operationRecords.any { it.id==fixture.getString("operationId") }&&session.transferStatus==null }
        assertNull(session.error)
    }
    private fun proof(): JSONObject {
        val file=session.attachments.single()
        var bytes=byteArrayOf()
        main { bytes=NativeDraftFiles(NativeSessionStorage(i.targetContext)).bytes(file.manifest) }
        assertArrayEquals(fixture.getString("nextFileText").toByteArray(),bytes)
        assertEquals(fixture.getString("independentDraft"),session.text)
        assertEquals(fixture.getString("originalSourceKey"),session.targetKey)
        assertEquals("",session.selectedThread)
        return JSONObject().put("targetKey",session.targetKey).put("localText",session.text).put("localFile",file.manifest.body())
            .put("intentSha256",originalRecord().intentSha256).put("originalId",originalRecord().id).put("newModelInputs",0)
    }
    // Read only our own busy map to await the original coroutine while another draft is shown.
    // This synchronization never changes product state or makes a transport result up.
    private fun awaitOriginalQueryFinished() {
        val field=MobileSession::class.java.getDeclaredField("busyTargets").apply { isAccessible=true }
        ui.waitUntil(20_000) {
            var finished=false
            i.runOnMainSync { finished=!(field.get(session) as Map<*,*>).containsKey(fixture.getString("originalSourceKey")) }
            finished
        }
        ui.waitForIdle()
    }
    private fun query(index: Int,mode: String) {
        control("claim-query",JSONObject().put("index",index).put("mode",mode).put("operationId",originalRecord().id).put("targetKey",session.targetKey))
        hideKeyboard();ui.onNodeWithTag("chat-menu").performClick()
        ui.onNodeWithTag("query-detached-${originalRecord().id}").performScrollTo().assertIsEnabled().performClick()
        control("await-query-start",JSONObject().put("index",index))
    }
    @Test fun originalDetachedCreationQueriesKeepTheirNavigationOwnerWithoutNewInput() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        originalRecord()
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("Android original creation read-only query DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        assertTrue(session.canCreateProjectless);navigateOriginal()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard()
        main { session.addAttachment(fixture.getString("nextFileName"),"text/plain",fixture.getString("nextFileText").toByteArray()) }
        val file=session.attachments.single();val chosen=session.generationSettings;ui.onNodeWithTag("draft-attachment-${file.manifest.id}").assertIsDisplayed();proof();shot("original-detached-record-independent-draft")
        if(!fixture.getBoolean("preflightOnly")) {
            query(0,"held-failure")
            ui.runOnIdle { session.newChat(null) };assertNotEquals(fixture.getString("originalSourceKey"),session.targetKey)
            ui.onNodeWithTag("message-input").performTextReplacement("Independent alternate draft "+nonce);hideKeyboard()
            control("release-query",JSONObject().put("index",0))
            awaitOriginalQueryFinished()
            assertEquals("Independent alternate draft "+nonce,session.text);assertTrue(session.attachments.isEmpty());assertNull(session.error);shot("late-query-failure-alternate-draft-unaffected")
            control("query-complete",JSONObject().put("index",0).put("errorAbsent",true).put("targetChanged",true))
            navigateOriginal();assertEquals(file,session.attachments.single());assertEquals(chosen,session.generationSettings);proof()
            query(1,"held-failure")
            ui.runOnIdle { session.newChat(null);session.newChat(fixture.getString("projectId")) }
            assertEquals(fixture.getString("originalSourceKey"),session.targetKey)
            control("release-query",JSONObject().put("index",1))
            awaitOriginalQueryFinished()
            assertNull(session.error);proof();shot("late-query-failure-roundtrip-unaffected")
            control("query-complete",proof().put("index",1).put("errorAbsent",true).put("roundtrip",true))
            query(2,"held-failure");control("release-query",JSONObject().put("index",2))
            ui.waitUntil(20_000) { session.transferStatus==null&&session.error!=null }
            ui.onNodeWithTag("session-error").assertIsDisplayed();shot("current-query-failure-visible")
            control("query-complete",proof().put("index",2).put("currentErrorVisible",true))
            ui.onNodeWithTag("dismiss-error").performClick();assertNull(session.error)
        }
        val index=if(fixture.getBoolean("preflightOnly"))0 else 3
        query(index,"actual-original-receipt")
        ui.waitUntil(30_000) { session.transferStatus==null };assertNull(session.error)
        assertEquals(file,session.attachments.single());assertEquals(chosen,session.generationSettings);proof();shot("actual-original-confirmed-receipt-keeps-independent-draft")
        control("query-complete",proof().put("index",index).put("realReceipt",true))
        File(folder(),"native-result.json").writeText(proof().put("status","passed").put("preflightOnly",fixture.getBoolean("preflightOnly")).toString())
        control(if(fixture.getBoolean("preflightOnly"))"preflight-complete" else "case-complete",proof())
    }
}
