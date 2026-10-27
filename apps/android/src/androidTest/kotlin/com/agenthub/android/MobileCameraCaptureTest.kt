package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayOutputStream

/** Real private output URI, normalization and encrypted drafts; synthetic GET-only Host, no system camera or model. */
class MobileCameraCaptureTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private lateinit var capture: NativeCameraCapture
    private lateinit var server: CommandFixture
    private lateinit var text: NativeL10n
    private val requests=mutableListOf<NativeCameraRequest>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity;owned=OwnedControlAcceptance(activity,"camera-capture");text=NativeL10n(activity,"en")
        val target=JSONObject().put("agentId","codex").put("hostId","camera-host").put("workspaceId","camera-workspace").put("runtimeId","camera-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","camera-host").put("name","Original camera target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","camera-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","camera-host").put("threadId",Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        main { session.resetAcceptance();session.changeLanguage("en");session.connect("Original camera target",server.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="拍照原草稿 100% %@" }
        ui.waitUntil(15_000) { !session.historyLoading&&session.canCompose };capture=NativeCameraCapture(activity,session)
    }
    @After fun finish() {
        main { capture.pending?.let { capture.complete(it,false,text) };session.leaveForeground() }
        ui.waitUntil(15_000) { !session.importing&&requests.mapNotNull { it.output }.none { it.file.exists() } };requests.mapNotNull { it.output }.forEach { assertFalse("Only owned camera cache is removed",it.file.exists()) }
        assertTrue(session.operationRecords.isEmpty());owned.verifyDefault();assertTrue(server.requests.all { it.method=="GET" });server.close()
    }
    private fun start(menu: Boolean=false): NativeCameraRequest {
        lateinit var value: NativeCameraRequest
        main { assertTrue(capture.begin(session.attachmentDestination(),waitForMenu=menu));value=capture.pending!! }
        requests+=value;return value
    }
    private fun output(request: NativeCameraRequest): NativeCameraOutput {
        var value: NativeCameraOutput?=null;main { value=capture.prepareOutput(request) };return checkNotNull(value)
    }
    private fun jpeg(): ByteArray {
        val image=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888);try { image.eraseColor(0xff336699.toInt());return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG,100,it) }.toByteArray() }finally { image.recycle() }
    }
    @Test fun outputIntentGrantsOnlyOriginalPrivateUriAndCancelDeletesIt() {
        val request=start(menu=true);assertFalse(request.photo.menuDismissed);main { capture.menuDidDismiss(request.photo.destination.presentation) };assertTrue(request.photo.menuDismissed)
        val out=output(request);assertEquals(activity.packageName+".resources",out.uri.authority);assertTrue(out.uri.path!!.startsWith("/camera-capture/photo-"))
        val intent=NativeCameraContract().createIntent(activity,out.uri);assertEquals("android.media.action.IMAGE_CAPTURE",intent.action)
        @Suppress("DEPRECATION")val actual=intent.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT)
        assertEquals(out.uri,actual);assertEquals(1,intent.clipData!!.itemCount);assertEquals(out.uri,intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0)
        main { capture.complete(request,false,text) };assertNull(capture.pending);assertFalse(out.file.exists());assertNull(session.error);assertTrue(session.attachments.isEmpty());assertEquals("拍照原草稿 100% %@",session.text)
    }
    @Test fun cameraOutputIsNormalizedToEncryptedOriginalDraftAndThenRemoved() {
        val request=start();val out=output(request);out.file.writeBytes(jpeg());main { capture.complete(request,true,text) }
        ui.waitUntil(15_000) { !session.importing&&session.attachments.size==1 };val file=session.attachments.single();assertEquals("image/jpeg",file.manifest.mediaType);assertEquals(out.file.name,file.manifest.name)
        val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file.manifest) };val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try { assertEquals(8,image.width);assertEquals(6,image.height);val pixel=image.getPixel(4,3);assertTrue(kotlin.math.abs(Color.red(pixel)-51)<=3&&kotlin.math.abs(Color.green(pixel)-102)<=3&&kotlin.math.abs(Color.blue(pixel)-153)<=3) }finally { image.recycle() }
        assertEquals("拍照原草稿 100% %@",session.text);assertTrue(runBlocking { session.confirmInboxFiles(request.photo.destination,listOf(file.manifest)) });ui.waitUntil(5000) { !out.file.exists() }
    }
    @Test fun cameraResultStaysWithOriginalDraftAfterNavigation() {
        val request=start();val out=output(request);out.file.writeBytes(jpeg());main { session.selectThread(session.threads.first { it.id=="second" });session.text="第二草稿";capture.complete(request,true,text) }
        ui.waitUntil(15_000) { !session.importing };assertEquals("second",session.selectedThread);assertEquals("第二草稿",session.text);assertTrue(session.attachments.isEmpty())
        main { session.selectThread(session.threads.first { it.id=="first" }) };ui.waitUntil(15_000) { !session.historyLoading&&session.attachments.size==1 };assertEquals("拍照原草稿 100% %@",session.text)
    }
    @Test fun deniedOrFailedOldRequestCannotReplaceNewerError() {
        val request=start();val out=output(request);main { session.error="Newer failure 100% %@";capture.permissionResult(request,false,text) };assertEquals("Newer failure 100% %@",session.error);assertFalse(out.file.exists())
        main { session.error=null };val denied=start();main { capture.permissionResult(denied,false,text) };assertEquals("Camera permission was not granted. No photo was added.",session.error)
        main { session.error=null };val failed=start();main { capture.launchFailed(failed,text,android.content.ActivityNotFoundException()) };assertEquals("The camera is unavailable on this device.",session.error)
    }
    @Test fun duplicateOldCallbackCannotConsumeOrRemoveNewOutput() {
        val old=start();val oldFile=output(old);main { capture.complete(old,false,text) };val fresh=start();val next=output(fresh);next.file.writeBytes(jpeg())
        main { capture.complete(old,true,text);capture.launchFailed(old,text);capture.permissionResult(old,false,text) }
        assertTrue(capture.pending===fresh);assertTrue(next.file.exists());assertFalse(oldFile.file.exists());assertNull(session.error)
        main { capture.complete(fresh,true,text) };ui.waitUntil(15_000) { !session.importing&&session.attachments.size==1 }
    }
    @Test fun staleMenuRetiresBeforeLaunchAndPhotoAndCameraCannotOverlap() {
        val request=start(menu=true);main { session.selectThread(session.threads.first { it.id=="second" });capture.menuDidDismiss(request.photo.destination.presentation) };assertNull(capture.pending);assertNull(request.output)
        ui.waitUntil(15_000) { !session.historyLoading&&session.canCompose }
        main { session.configureCameraCapture(activity);session.configurePhotoPicker(activity) }
        ui.waitUntil(15_000) { !session.attachmentRecoveryBusy }
        main { assertTrue(session.cameraCapture!!.begin(session.attachmentDestination(),waitForMenu=true));assertFalse(session.photoPicker!!.begin(session.attachmentDestination(),waitForMenu=true)) }
        ui.onNodeWithTag("composer-add").assertIsNotEnabled()
        main { session.cameraCapture!!.complete(session.cameraCapture!!.pending!!,false,text) }
        ui.waitUntil(15_000) { session.cameraCapture!!.pending==null&&!session.attachmentRecoveryBusy }
        main { assertTrue(session.photoPicker!!.begin(session.attachmentDestination(),waitForMenu=true));assertFalse(session.cameraCapture!!.begin(session.attachmentDestination(),waitForMenu=true)) }
        ui.onNodeWithTag("composer-add").assertIsNotEnabled()
        main { session.photoPicker!!.complete(session.photoPicker!!.pending!!,emptyList(),text) }
        ui.onNodeWithTag("composer-add").assertIsEnabled()
        assertTrue(session.attachments.isEmpty());assertEquals("",session.text)
    }
    @Test fun unreadableOrRetiredGenerationCannotAddFilesAndAlwaysRemovesOwnedOutput() {
        val empty=start();val emptyFile=output(empty);main { capture.complete(empty,true,text) };ui.waitUntil(15_000) { !session.importing&&!emptyFile.file.exists() };assertTrue(session.attachments.isEmpty());assertNotNull(session.error)
        main { session.error=null };val broken=start();val brokenFile=output(broken);brokenFile.file.writeText("not a camera image");main { session.error="Newer preserved error";capture.complete(broken,true,text) }
        ui.waitUntil(15_000) { !session.importing&&!brokenFile.file.exists() };assertTrue(session.attachments.isEmpty());assertEquals("Newer preserved error",session.error)
    }
}
