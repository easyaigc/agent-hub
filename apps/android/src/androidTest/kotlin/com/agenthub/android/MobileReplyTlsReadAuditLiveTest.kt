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

/** Read-only diagnosis and current-source accepted original witness; every product POST is forbidden. */
class MobileReplyTlsReadAuditLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-reply-tls-read-audit-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("read-observed","read-complete"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-reply-tls-read-audit/$nonce/${fixture.getString("evidenceRun")}/phase-${fixture.getInt("nativePhase")}").apply { mkdirs() }
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
    private fun localProof(): JSONObject {
        val file=session.attachments.single();var bytes=byteArrayOf();main { bytes=NativeDraftFiles(NativeSessionStorage(i.targetContext)).bytes(file.manifest) }
        assertArrayEquals(fixture.getString("localFileText").toByteArray(),bytes);assertEquals(fixture.getString("independentDraft"),session.text)
        return JSONObject().put("localText",session.text).put("localFile",file.manifest.body()).put("sourceKey",session.targetKey).put("threadId",session.selectedThread)
            .put("generation",session.generationSettings.append(JSONObject())).put("fingerprint",session.selectedClient.codexDirect!!.fingerprint).put("pid",android.os.Process.myPid())
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    @Test fun coldRestoreAndAuditAcceptedOriginalReadRoutes() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.runOnIdle { activity.readNetworkMonitor.stop();session.discovery?.stop();session.discovery=null;session.easyWorkDiscovery?.stop();session.easyWorkDiscovery=null }
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&session.hasClient&&session.selectedClient.online&&session.selectedClient.id==fixture.getString("hostId")&&session.selectedThread==fixture.getString("threadId")&&session.messages.isNotEmpty() }
        assertFalse(session.localRecoveryRequired);assertEquals(fixture.getString("sourceKey"),session.targetKey)
        assertEquals(fixture.getString("fingerprint"),session.selectedClient.codexDirect!!.fingerprint)
        ui.waitUntil(30_000) { session.operationRecords.any { it.id==fixture.getString("originalReplyId") } }
        val original=session.operationRecords.single { it.id==fixture.getString("originalReplyId") };val saved=fixture.getJSONObject("originalReply")
        assertEquals(saved.getString("intent"),original.intent);assertEquals(saved.getString("intentSha256"),original.intentSha256);assertTrue(original.replyRecovery!!.consumed);assertFalse(session.mayRetryReply(original))
        assertEquals(fixture.getString("localFileId"),session.attachments.single().manifest.id);localProof()
        assertTrue(session.aiSharing.permits(AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"))))
        val deadline=android.os.SystemClock.uptimeMillis()+45_000
        do { if(control("read-observed",JSONObject()).getBoolean("observed"))break;Thread.sleep(200) }while(android.os.SystemClock.uptimeMillis()<deadline)
        assertTrue(control("read-observed",JSONObject()).getBoolean("observed"))
        val options=if(fixture.getBoolean("diagnosticOnly"))null else runBlocking { NativeCodexCommands { NativeSessionStorage(i.targetContext).token(it) }.forkOptions(session.selectedClient,session.selectedThread) }
        options?.let { assertEquals(session.selectedThread,it.threadId);assertEquals(session.selectedClient.id,it.hostId);assertTrue(it.sameDirectoryAvailable) }
        if(!fixture.getBoolean("diagnosticOnly")) {
            ui.runOnIdle { session.loadForkOptions(true) }
            ui.waitUntil(30_000) { session.forkReason(fixture.getString("lastTurnId"),"same-directory")==null }
        }
        localProof();shot(if(fixture.getBoolean("diagnosticOnly"))"read-route-diagnostic" else "accepted-original-current-paired-read-only")
        control("read-complete",localProof().put("originalReply",original.body()).put("sameDirectoryAvailable",(options?.sameDirectoryAvailable ?: false)))
    }
}
