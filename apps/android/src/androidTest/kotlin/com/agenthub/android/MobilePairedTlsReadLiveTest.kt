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

/** Current physical native TLS pin rejection/QR pairing/read-only preflight; no model operation. */
class MobilePairedTlsReadLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-paired-tls-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("certificate-rejected","preflight-complete"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-paired-tls/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    @Test fun realPinnedPairingRejectsWrongCertificateAndPreparesOnlyLocalDraft() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        ui.runOnIdle {
            activity.readNetworkMonitor.stop();session.discovery?.stop();session.discovery=null;session.easyWorkDiscovery?.stop();session.easyWorkDiscovery=null;session.resetAcceptance()
        }
        val storage=NativeSessionStorage(i.targetContext);val hostId=fixture.getString("hostId")
        assertTrue(storage.token(hostId).isNullOrEmpty());assertTrue(session.clients.isEmpty())
        var wrong: Throwable?=null
        try { main { session.connectCodexQR(fixture.getString("badPairing"),"Android TLS pin negative preflight") } }catch(failure: Exception) { wrong=failure }
        val failure=checkNotNull(wrong) { "Wrong certificate fingerprint must reject before publication" }
        val causes=generateSequence(failure) { it.cause }.take(12).toList()
        assertTrue("The wrong pin must fail actual TLS certificate validation",causes.any { it is javax.net.ssl.SSLException||it is java.security.cert.CertificateException })
        assertTrue(session.clients.none { it.id==hostId });assertTrue(storage.token(hostId).isNullOrEmpty());assertNull(session.transferStatus)
        control("certificate-rejected",JSONObject().put("failureClasses",JSONArray(causes.map { it.javaClass.name })).put("noClientPublished",true).put("noCredentialSaved",true))
        main { session.connectCodexQR(fixture.getString("pairing"),"Android paired TLS read-only DEV") }
        ui.waitUntil(45_000) { session.hasClient&&session.selectedClient.online&&!session.loading&&session.workspace?.hostId==hostId&&session.models.isNotEmpty() }
        assertEquals(hostId,session.selectedClient.id);assertEquals(fixture.getString("fingerprint"),session.selectedClient.codexDirect!!.fingerprint)
        assertEquals("https",URI(session.selectedClient.endpoint!!).scheme);assertTrue(storage.token(hostId).orEmpty().length>=32)
        assertTrue(sameScope(HostReadCodec.target(fixture.getJSONObject("target")),session.selectedClient.scope))
        var pages=0
        while(session.projects.none { it.id==fixture.getString("projectId") }) {
            val cursor=checkNotNull(session.workspace?.nextProjectCursor) { "Exact human-added acceptance project is absent" };check(++pages<=100)
            ui.runOnIdle { session.refresh(more=true) };ui.waitUntil(30_000) { !session.loading&&session.workspace?.nextProjectCursor!=cursor }
        }
        assertTrue(session.canCreateThread);assertTrue(session.operationRecords.isEmpty());assertNull(session.error);shot("paired-tls-valid-connection")
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard()
        val bytes=fixture.getString("localFileText").toByteArray();main { session.addAttachment(fixture.getString("localFileName"),"text/plain",bytes) }
        val file=session.attachments.single();ui.onNodeWithTag("draft-attachment-${file.manifest.id}").assertIsDisplayed();allowSharingWithoutInput();shot("paired-tls-grant-local-draft-unsent")
        assertEquals(fixture.getString("independentDraft"),session.text);assertEquals("",session.selectedThread);assertTrue(session.operationRecords.isEmpty());assertNull(session.error)
        var cold=byteArrayOf();main { cold=NativeDraftFiles(storage).bytes(file.manifest) };assertArrayEquals(bytes,cold)
        val out=JSONObject().put("status","passed").put("hostId",hostId).put("fingerprint",fixture.getString("fingerprint")).put("wrongCertificateRejected",true)
            .put("pairedTarget",CodexCommandCodec.target(checkNotNull(session.selectedClient.scope))).put("localText",session.text).put("localFile",file.manifest.body()).put("targetKey",session.targetKey)
            .put("model",session.generationSettings.model).put("effort",session.generationSettings.effort).put("speed",session.generationSettings.speed).put("permission",session.generationSettings.permissionMode).put("newModelInputs",0)
        File(folder(),"native-result.json").writeText(out.toString());control("preflight-complete",out)
    }
}
