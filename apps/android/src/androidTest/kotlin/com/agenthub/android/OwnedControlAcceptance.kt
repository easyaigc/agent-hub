package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** A fresh test session; default encrypted drafts are observed and never reset or restored over. */
internal class OwnedControlAcceptance(private val activity: MainActivity, private val category: String) {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val defaultStorage=NativeSessionStorage(activity)
    private val defaults=defaultStorage.metadata().let { metadata->listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations","photoSources").associateWith { metadata?.opt(it)?.toString() } }
    private val recentDefaults=defaultStorage.child(activity,"recent-photos").read(NativeRecentPhotoLibrary.PREFERENCES)
    private val permissionDefaults=listOf("attempt-external","attempt-images","attempt-selected").associateWith { defaultStorage.child(activity,"photo-permission").read(it) }
    private val documentDefaults=defaultStorage.child(activity,"document-results").read("document-requests-v1")
    private val photoDefaults=defaultStorage.child(activity,"photo-results").read("document-requests-v1")
    private val cameraDefaults=defaultStorage.child(activity,"camera-results").read("document-requests-v1")
    private fun fingerprint(folder: File)=if(!folder.exists())emptyMap()else folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private val defaultCameraClaims=File(defaultStorage.child(activity,"camera-results").child(activity,"outputs").coordinationKey)
    private val cameraClaims=fingerprint(defaultCameraClaims)
    private val cameraOutputRoot=File(activity.filesDir,"camera-output-v1")
    private val cameraOutputs=fingerprint(cameraOutputRoot)
    private val nonce=java.util.UUID.randomUUID().toString()
    val storage=NativeSessionStorage(activity,"P4-owned-control-$nonce")
    val session=MobileSession(storage)
    val folder: File
    init {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(!activity.session.restoring)
        val run=InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: "owned-model"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")));require(category.matches(Regex("[a-zA-Z0-9_-]+")))
        folder=File(activity.filesDir,"acceptance/P4-owner-callbacks/$run/$category-$nonce").apply { mkdirs() }
        activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
        activity.setContent { AgentHubApp(session) { } }
    }
    fun verifyDefault() {
        val now=defaultStorage.metadata()
        for((key,value)in defaults)assertEquals("Owned controls preserve default $key",value,now?.opt(key)?.toString())
        assertEquals("Owned controls preserve default recent photo preferences",recentDefaults,defaultStorage.child(activity,"recent-photos").read(NativeRecentPhotoLibrary.PREFERENCES))
        for((key,value)in permissionDefaults)assertEquals("Owned controls preserve default photo authorization",value,defaultStorage.child(activity,"photo-permission").read(key))
        assertEquals("Owned controls preserve default document requests",documentDefaults,defaultStorage.child(activity,"document-results").read("document-requests-v1"))
        assertEquals("Owned controls preserve default photo requests",photoDefaults,defaultStorage.child(activity,"photo-results").read("document-requests-v1"))
        assertEquals("Owned controls preserve default camera requests",cameraDefaults,defaultStorage.child(activity,"camera-results").read("document-requests-v1"))
        assertEquals("Owned controls preserve default camera claims",cameraClaims,fingerprint(defaultCameraClaims))
        assertEquals("Owned controls preserve original private outputs",cameraOutputs,fingerprint(cameraOutputRoot))
        File(folder,"default-storage-proof.json").writeText(JSONObject().put("draftsPreserved",true).put("draftFilesPreserved",true).put("draftVersionsPreserved",true).put("attachmentImportGenerationsPreserved",true).put("photoSourcesPreserved",true).put("recentPhotoPreferencesPreserved",true).put("photoAuthorizationPreserved",true).put("documentRequestsPreserved",true).put("photoRequestsPreserved",true).put("cameraRequestsPreserved",true).put("cameraOutputsPreserved",true).put("namespace",nonce).toString())
    }
    /** Explicit native Dialog capture, including API26 where Compose node capture is unsupported. */
    fun dialogShot(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        var window: android.view.Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(i.targetContext.packageName,view.context.packageName)
                fun find(v: android.view.View) {
                    if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                    if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))
                }
                find(view.rootView);assertTrue(window!!.decorView.rootView===view.rootView)
            }
        val w=checkNotNull(window);val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync { w.decorView.postOnAnimation { w.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS))
        var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(w.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(w.decorView.width,w.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(w,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-dialog-window").put("package",i.targetContext.packageName)
                .put("width",image.width).put("height",image.height).put("fullSystemComposite",false).toString())
        } finally { image.recycle() }
    }
    /** Capture only the app-owned semantic subtree, including a native popup's rows. */
    fun nodeShot(node: SemanticsNodeInteraction,name: String) {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        var owner=i.uiAutomation.rootInActiveWindow?.packageName?.toString()
        val deadline=android.os.SystemClock.elapsedRealtime()+2_000
        // A null AX root can briefly outlive a dismissed native dialog on API26.
        // Wait only for that missing root; a different owner still fails immediately.
        while(owner==null&&android.os.SystemClock.elapsedRealtime()<deadline) {
            i.waitForIdleSync();Thread.sleep(50)
            owner=i.uiAutomation.rootInActiveWindow?.packageName?.toString()
        }
        assertEquals(i.targetContext.packageName,owner)
        val bitmap=node.captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        File(folder,"$name-window.json").writeText(JSONObject().put("method","Compose.captureToImage.owned-semantic-subtree").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height).put("targetKey",session.targetKey).put("threadId",session.selectedThread).put("fullSystemComposite",false).toString())
    }
}
