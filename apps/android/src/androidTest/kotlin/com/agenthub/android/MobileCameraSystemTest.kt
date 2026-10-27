package com.agenthub.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Actual system-camera contract. Only owned emulators capture; physical camera scene cancels without taking a photo. */
class MobileCameraSystemTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private lateinit var server: CommandFixture
    private var prior: MainActivity.AcceptanceSessionState?=null
    private var cameraPackage: String?=null
    private var permissionOwner: String?=null
    private var permissionWasRequested=false
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun nodes(pkg: String): List<AccessibilityNodeInfo> {
        val root=i.uiAutomation.rootInActiveWindow?:return emptyList();if(root.packageName?.toString()!=pkg)return emptyList()
        return buildList { fun visit(n: AccessibilityNodeInfo) { if(n.packageName?.toString()!=pkg)return;add(n);for(k in 0 until n.childCount)n.getChild(k)?.let(::visit) };visit(root) }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var value: AccessibilityNodeInfo?=node;while(value!=null&&value.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })value=value.parent
        val target=checkNotNull(value);assertEquals(cameraPackage,target.packageName?.toString());assertTrue(target.isVisibleToUser&&target.isEnabled);assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun dump(name: String) { File(owned.folder,name).writeText(nodes(checkNotNull(cameraPackage)).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}|${it.isEnabled}|${it.isVisibleToUser}" }) }
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance");ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        owned=OwnedControlAcceptance(activity,"camera-system")
        val target=JSONObject().put("agentId","codex").put("hostId","camera-system-host").put("workspaceId","camera-system-workspace").put("runtimeId","camera-system-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","camera-system-host").put("name","Original camera system target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","original").put("hostId","camera-system-host").put("title","Original camera").put("state","idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","camera-system-host").put("threadId","original").put("readTarget",target).put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        main { session.resetAcceptance();session.changeLanguage("en");session.changeAppearance("light");prior=activity.adoptSessionForAcceptance(session);session.connect("Original camera system target",server.endpoint,"");session.selectThread(session.threads.single());session.text="系统拍照原草稿 100% %@" }
        // Camera evidence excludes the separately tested first-gallery permission UI.
        main { val claimed=session.photoAuthorization?.claim("camera-system-gallery-isolation",automatic=true)?:false;session.photoAuthorization?.complete();File(owned.folder,"camera-gallery-isolation.json").writeText(JSONObject().put("syntheticPriorGalleryAttempt",claimed).put("galleryPermissionEvidence",false).toString()) }
        ui.waitUntil(15_000) { !session.historyLoading&&session.canCompose&&!session.attachmentRecoveryBusy }
    }
    @After fun finish() {
        try {
            if(::owned.isInitialized) {
                val prompt=permissionOwner
                if(prompt!=null&&nodes(prompt).isNotEmpty())i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                val pkg=cameraPackage
                if(pkg!=null&&nodes(pkg).isNotEmpty())i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                main { session.cameraCapture?.pending?.let { session.cameraCapture!!.complete(it,false,NativeL10n(activity,"en")) } }
                ui.waitUntil(15_000) { session.cameraCapture?.pending==null&&!session.attachmentRecoveryBusy&&!session.importing }
                main { session.leaveForeground();prior?.let(activity::restoreSessionForAcceptance) }
                owned.verifyDefault();assertTrue(server.requests.all { it.method=="GET" })
            }
        }finally { if(::server.isInitialized)server.close() }
    }
    private fun launch(): NativeCameraRequest {
        val intent=Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);val handler=checkNotNull(activity.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY));val pkg=handler.activityInfo.packageName;cameraPackage=pkg
        assertNotEquals(activity.packageName,pkg);assertTrue(handler.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0);assertNotEquals(Process.myUid(),handler.activityInfo.applicationInfo.uid)
        ui.onNodeWithTag("composer-add").assertIsEnabled().performClick();ui.onNodeWithTag("take-photo").assertTextEquals("Take photo").performClick()
        // Sheet dismissal queues the action for a later frame; absence is not a launch failure.
        ui.waitUntil(10_000) { session.cameraCapture!!.pending!=null||session.error!=null }
        ui.waitUntil(10_000) { session.cameraCapture!!.pending?.let { it.launched||it.permissionRequested }==true||session.cameraCapture!!.pending==null }
        val waiting=checkNotNull(session.cameraCapture!!.pending) { "Native camera did not retain its request: ${session.error}; phases=${runBlocking { NativeDocumentJournal(owned.storage.child(activity,"camera-results")).records().map { it.phase } }}" }
        if(waiting.permissionRequested&&activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)allowOnlyThisTime(waiting)
        ui.waitUntil(10_000) { session.cameraCapture!!.pending?.launched==true||session.cameraCapture!!.pending==null }
        val original=checkNotNull(session.cameraCapture!!.pending) { "Native camera launch failed: ${session.error}" };assertTrue(original.photo.menuDismissed);assertNotNull(original.output)
        val end=System.currentTimeMillis()+20_000
        while(nodes(pkg).isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(80)
        if(nodes(pkg).isEmpty()) { File(owned.folder,"camera-owner-pending.txt").writeText("expected=$pkg;actual=${i.uiAutomation.rootInActiveWindow?.packageName}");throw AssertionError("Exact system camera owner not available; do not act on other windows") }
        i.uiAutomation.waitForIdle(400,8_000);dump("system-camera-controls.txt")
        return original
    }
    private fun allowOnlyThisTime(request: NativeCameraRequest) {
        require(Build.VERSION.SDK_INT>=30);assertTrue(request.permissionRequested)
        val allowed=setOf("com.android.permissioncontroller","com.google.android.permissioncontroller","com.lbe.security.miui","com.android.packageinstaller")
        val end=System.currentTimeMillis()+10_000;var owner: String?=null
        while(System.currentTimeMillis()<end) { owner=i.uiAutomation.rootInActiveWindow?.packageName?.toString();if(owner in allowed)break;Thread.sleep(80) }
        val pkg=checkNotNull(owner);assertTrue("Only the system runtime-permission UI may be operated",pkg in allowed)
        val state=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("dumpsys activity activities")).use { it.bufferedReader().readText() }
        File(owned.folder,"camera-permission-activities.txt").writeText(state)
        val records=state.split(Regex("\\* Hist\\s+#\\d+:"))
        val exact=records.firstOrNull { it.substringBefore('\n').contains("$pkg/")&&it.contains(Regex("launchedFromPackage="+Regex.escape(activity.packageName)+"(?:\\s|$)"))&&it.contains(Regex("launchedFromUid="+Process.myUid()+"(?:\\s|$)")) }
        File(owned.folder,"camera-permission-caller-proof.json").writeText(JSONObject().put("permissionOwner",pkg).put("exactAcceptanceCaller",exact!=null).put("callerUid",Process.myUid()).put("permission",Manifest.permission.CAMERA).toString())
        assertNotNull("Permission dialog must belong to this exact acceptance caller",exact);permissionOwner=pkg
        val controls=nodes(pkg);File(owned.folder,"camera-permission-controls.txt").writeText(controls.joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}" })
        val once=controls.filter { it.isVisibleToUser&&it.isEnabled&&(it.viewIdResourceName?.substringAfterLast('/')=="permission_allow_one_time_button"||it.text?.toString() in listOf("Only this time","仅此一次","仅限这一次","此次允许")) }
        assertEquals("Only an explicit one-time camera permission can be selected",1,once.size)
        var target: AccessibilityNodeInfo?=once.single();while(target!=null&&target.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })target=target.parent
        assertEquals(pkg,checkNotNull(target).packageName?.toString());assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));permissionWasRequested=true
        ui.waitUntil(10_000) { activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED }
    }
    @Test fun actualSystemCameraOpenAndCancelPreservesOriginalDraft() {
        val original=launch();val output=checkNotNull(original.output)
        assertTrue(i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(20_000) { session.cameraCapture!!.pending==null };ui.waitUntil(5000) { !output.file.exists() }
        assertEquals("系统拍照原草稿 100% %@",session.text);assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty());assertNull(session.error)
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"system-camera-cancel-original-draft")
        File(owned.folder,"system-camera-cancel-proof.json").writeText(JSONObject().put("actualSystemCameraOwner",cameraPackage).put("actualContractCallback",true).put("originalDraftPreserved",true).put("ownedOutputRemoved",true).put("nativeOneTimePermission",permissionWasRequested).put("photoTaken",false).put("modelInputs",0).toString())
    }
    @Test fun ownedEmulatedCameraPhotoReturnsThroughActualContractIntoOriginalDraft() {
        require(Build.HARDWARE in listOf("ranchu","goldfish")&&InstrumentationRegistry.getArguments().getString("ownedCameraCapture")=="1") { "Physical camera capture is outside this examiner" }
        val original=launch();val pkg=checkNotNull(cameraPackage);val end=System.currentTimeMillis()+15_000;var shutter: AccessibilityNodeInfo?=null
        while(shutter==null&&System.currentTimeMillis()<end) {
            shutter=nodes(pkg).firstOrNull { n -> n.isVisibleToUser&&n.isEnabled&&(n.viewIdResourceName?.substringAfterLast('/') in listOf("shutter_button","shutter_button_photo")||n.contentDescription?.toString() in listOf("Shutter","Shutter button","Take photo","Capture photo","拍照","快门")) }
            if(shutter==null)Thread.sleep(80)
        }
        dump("system-camera-ready-controls.txt");click(checkNotNull(shutter) { "Exact camera shutter unavailable" })
        val confirmEnd=System.currentTimeMillis()+20_000;var confirmed=false
        while(session.cameraCapture!!.pending!=null&&System.currentTimeMillis()<confirmEnd) {
            val done=nodes(pkg).filter { n->n.isVisibleToUser&&n.isEnabled&&(n.viewIdResourceName?.substringAfterLast('/') in listOf("done_button","btn_done","review_done")||n.contentDescription?.toString() in listOf("Done","Use photo","完成","使用照片")) }
            if(done.size==1&&!confirmed) { click(done.single());confirmed=true };Thread.sleep(100)
        }
        if(session.cameraCapture!!.pending!=null)dump("system-camera-after-shutter-controls.txt")
        ui.waitUntil(25_000) { session.cameraCapture!!.pending==null&&!session.importing&&session.attachments.size==1 }
        val file=session.attachments.single();assertEquals("image/jpeg",file.manifest.mediaType);assertEquals(checkNotNull(original.output).file.name,file.manifest.name)
        val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file.manifest) };val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        val width=image.width;val height=image.height;image.recycle();assertTrue(width>0&&height>0);assertTrue(bytes.isNotEmpty());assertTrue(runBlocking { session.confirmInboxFiles(original.photo.destination,listOf(file.manifest)) })
        ui.waitUntil(5000) { !checkNotNull(original.output).file.exists() };assertEquals("系统拍照原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
        File(owned.folder,"camera-produced-image.jpg").writeBytes(bytes);owned.nodeShot(ui.onNodeWithTag("product-surface"),"system-camera-photo-original-draft")
        File(owned.folder,"system-camera-capture-proof.json").writeText(JSONObject().put("actualSystemCameraOwner",pkg).put("actualShutterSdkAction",true).put("actualContractCallback",true).put("originalDraftPreserved",true).put("encryptedDraftConfirmed",true)
            .put("ownedOutputRemoved",true).put("width",width).put("height",height).put("bytes",bytes.size).put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("newModelInputs",0).put("uploadOrSubmit",false).toString())
    }
}
