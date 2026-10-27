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

/** Physical native sidebar and complete-history locate of the sealed original chat; every product POST is forbidden. */
class MobileSidebarSearchReadLiveTest {
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
    private fun waitOwnedDrawerClosed() {
        ui.waitUntil(15_000) {
            ui.onAllNodesWithTag("sidebar-backdrop").fetchSemanticsNodes().isEmpty() &&
                runCatching { ui.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot.right<=1f }.getOrDefault(true)
        }
        ui.waitForIdle()
    }
    private fun verifyOwnedNativeDrawerAndImeGestures() {
        val source=session.targetKey;val thread=session.selectedThread;val draft=session.text;val density=i.targetContext.resources.displayMetrics.density
        val saved=session.operationRecords.single { it.id==fixture.getString("originalReplyId") };val intent=saved.intent;val intentHash=saved.intentSha256;val sourceVersion=saved.sourceVersion
        hideKeyboard()
        ui.onNodeWithTag("product-surface").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(4f*density,height*.35f),androidx.compose.ui.geometry.Offset(width*.82f,height*.35f),350)
        }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("sidebar-backdrop").fetchSemanticsNodes().isNotEmpty() };ui.onNodeWithTag("sidebar").assertIsDisplayed()
        ui.onNodeWithTag("sidebar-list").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width*.5f,height*.8f),androidx.compose.ui.geometry.Offset(width*.5f,height*.25f),350)
        }
        ui.onNodeWithTag("sidebar-backdrop").assertExists();assertEquals(source,session.targetKey);assertEquals(thread,session.selectedThread);assertEquals(draft,session.text)
        ui.onNodeWithTag("sidebar").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width*.88f,height*.45f),androidx.compose.ui.geometry.Offset(3f*density,height*.45f),350)
        }
        waitOwnedDrawerClosed()
        ui.onNodeWithTag("message-list").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width*.85f,height*.3f),androidx.compose.ui.geometry.Offset(width*.3f,height*.3f),300)
        }
        ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist();assertEquals(source,session.targetKey)
        val probe=draft+" NATIVE_IME_GESTURE_PROBE";val store=NativeSessionStorage(i.targetContext)
        ui.onNodeWithTag("message-input").assertIsEnabled().performClick()
        ui.onNodeWithTag("message-input").performTextReplacement(probe)
        try {
            ui.waitUntil(15_000) {
                var visible=false
                i.runOnMainSync { visible=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
                visible
            }
            ui.waitUntil(25_000) { session.text==probe&&store.metadata()?.optJSONObject("drafts")?.optString(source)==probe }
            ui.onNodeWithTag("message-input").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width*.85f,height*.5f),androidx.compose.ui.geometry.Offset(width*.25f,height*.5f),300)
            }
            ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist();assertEquals(probe,session.text)
        }finally{
            check(session.targetKey==source) { "Original editable draft target changed; do not overwrite another target" }
            if(ui.onAllNodesWithTag("sidebar-backdrop").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("close-sidebar").performClick()
            ui.onNodeWithTag("message-input").performTextReplacement(draft);hideKeyboard()
            ui.waitUntil(25_000) { store.metadata()?.optJSONObject("drafts")?.optString(source)==draft };localProof()
        }
        val current=session.operationRecords.single { it.id==saved.id }
        assertEquals(intent,current.intent);assertEquals(intentHash,current.intentSha256);assertEquals(sourceVersion,current.sourceVersion);assertTrue(current.replyRecovery!!.consumed);assertFalse(session.mayRetryReply(current))
        assertEquals(source,session.targetKey);assertEquals(thread,session.selectedThread);shot("owned-native-edge-drawer-scroll-and-ime-preserve-original")
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    @Test fun sameOriginalSidebarSearchAndNativeCompleteHistoryLocatePreserveDraft() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.runOnIdle { activity.readNetworkMonitor.stop();session.discovery?.stop();session.discovery=null;session.easyWorkDiscovery?.stop();session.easyWorkDiscovery=null }
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&session.hasClient&&session.selectedClient.online&&session.selectedClient.id==fixture.getString("hostId")&&session.selectedThread==fixture.getString("threadId")&&session.messages.isNotEmpty()&&session.currentChat!=null }
        assertFalse(session.localRecoveryRequired);assertEquals(fixture.getString("sourceKey"),session.targetKey)
        val project=fixture.getString("projectId");val thread=fixture.getString("threadId");val title=session.currentChat!!.title
        val originalSearch=session.search;assertEquals("",originalSearch)
        val originalDisplay=session.sidebarDisplay;val originalExpanded=session.projectExpanded(project);localProof()
        ui.waitUntil(30_000) { session.operationRecords.any { it.id==fixture.getString("originalReplyId") } }
        val original=session.operationRecords.single { it.id==fixture.getString("originalReplyId") };assertTrue(original.replyRecovery!!.consumed);assertFalse(session.mayRetryReply(original))
        ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-$project"))
        if(originalExpanded)ui.onNodeWithTag("project-$project").performClick()
        assertFalse(session.projectExpanded(project))
        ui.onNodeWithTag("sidebar-search-toggle").performClick();ui.onNodeWithTag("sidebar-search").performTextReplacement(title);hideKeyboard()
        val row="project-thread-$project-$thread"
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag(row));ui.onNodeWithTag(row).assertIsDisplayed()
        assertFalse(session.projectExpanded(project));assertEquals(originalDisplay,session.sidebarDisplay);shot("actual-sidebar-title-query-original-project-row")
        ui.onNodeWithTag("sidebar-search-clear").performClick();ui.waitUntil(10_000) { session.search.isEmpty() };assertFalse(session.projectExpanded(project))
        if(originalExpanded) { ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-$project"));ui.onNodeWithTag("project-$project").performClick() }
        assertEquals(originalExpanded,session.projectExpanded(project))
        ui.onNodeWithTag("sidebar-search").performTextReplacement(title);hideKeyboard();ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag(row));ui.onNodeWithTag(row).performClick()
        ui.waitUntil(30_000) { session.selectedThread==thread&&!session.historyLoading&&session.selectedClient.online }
        waitOwnedDrawerClosed()
        ui.onNodeWithTag("open-sidebar").assertIsDisplayed().performClick()
        ui.waitUntil(15_000) { runCatching { ui.onNodeWithTag("sidebar-search").assertIsEnabled();true }.getOrDefault(false) }
        ui.onNodeWithTag("sidebar-search").performTextReplacement(originalSearch);hideKeyboard();ui.onNodeWithTag("close-sidebar").performClick();waitOwnedDrawerClosed()
        assertEquals(originalSearch,session.search);assertEquals(originalDisplay,session.sidebarDisplay);assertEquals(originalExpanded,session.projectExpanded(project));localProof()
        val needle=fixture.getString("searchNeedle");val expected=session.messages.single { it.role=="assistant"&&it.text.trim()==needle }
        ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("chat-search-open").assertIsEnabled().performClick()
        ui.onNodeWithTag("chat-search-query").performTextReplacement(needle);hideKeyboard()
        ui.waitUntil(45_000) { ui.onAllNodesWithTag("chat-search-result-${expected.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chat-search-results").performScrollToNode(hasTestTag("chat-search-result-${expected.id}"));ui.onNodeWithTag("chat-search-result-${expected.id}").assertIsDisplayed();shot("actual-complete-history-result-own-dialog")
        ui.onNodeWithTag("chat-search-result-${expected.id}").performClick()
        ui.waitUntil(20_000) { session.pendingChatSearchToken==null&&session.chatSearchJump?.message?.id==expected.id }
        assertTrue(checkNotNull(session.chatSearchJump).complete);assertEquals(thread,session.selectedThread);localProof()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("chat-search-located-${expected.id}"));ui.onNodeWithTag("chat-search-located-${expected.id}").assertIsDisplayed()
        assertEquals(fixture.getJSONObject("originalReply").getString("intent"),session.operationRecords.single { it.id==original.id }.intent)
        assertTrue(session.operationRecords.single { it.id==original.id }.replyRecovery!!.consumed);assertFalse(session.mayRetryReply(original));assertNull(session.error)
        shot("actual-search-located-after-own-dialog-dismissed")
        verifyOwnedNativeDrawerAndImeGestures()
        control("read-complete",localProof().put("originalReply",session.operationRecords.single { it.id==original.id }.body()).put("sameDirectoryAvailable",JSONObject.NULL).put("sidebarAndSearchVerified",true).put("ownedNativeGesturesVerified",true))
    }
}
