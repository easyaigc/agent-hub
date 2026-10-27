package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Bounded native image preparation, original-draft callbacks, and actual system selector cancellation. */
class MobilePhotoPickerTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val paths=mutableListOf<File>()
    private var server: CommandFixture?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"photo-picker");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light") }
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { server?.close();paths.sortedByDescending { it.path.length }.forEach { assertTrue(!it.exists()||it.delete()) } }
    }
    private fun file(name: String,bytes: ByteArray): Pair<Uri,File> {
        val dir=File(activity.cacheDir,"resources/photo-"+UUID.randomUUID()).apply { mkdirs() };paths+=dir
        val file=File(dir,name).apply { writeBytes(bytes) };paths+=file
        return FileProvider.getUriForFile(activity,activity.packageName+".resources",file) to file
    }
    private fun png(): ByteArray {
        val b=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
        try { b.setPixels(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffff00.toInt(),0xffff00ff.toInt(),0xff00ffff.toInt()),0,3,0,0,3,2)
            return ByteArrayOutputStream().also { assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() }
        finally { b.recycle() }
    }
    private fun connect() {
        val target=JSONObject().put("agentId","codex").put("hostId","photo-host").put("workspaceId","photo-workspace").put("runtimeId","photo-runtime")
        val fixture=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","photo-host").put("name","Original photo target").put("connectionMode","desktop-runtime").put("readTarget",target)
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","photo-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","photo-host").put("threadId",Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("Original photo target",fixture.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="照片原草稿 100% %@" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null };assertTrue(session.canCompose)
    }
    private fun fail(key: String,block: suspend ()->Unit) {
        try { runBlocking { block() };fail("Expected native photo rejection") }catch(error: NativeFileIntakeFailure) { assertEquals(key,error.key) }
    }
    @Test fun pngPixelsNameAndMimeSurviveRealProviderNormalization() {
        val raw=png();val uri=file("原图 100% %@.PNG",raw).first;val input=runBlocking { NativePhotoIntake.read(activity.contentResolver,uri) }
        assertEquals("原图 100% %@.png",input.name);assertEquals("image/png",input.mediaType)
        val before=BitmapFactory.decodeByteArray(raw,0,raw.size);val after=BitmapFactory.decodeByteArray(input.bytes,0,input.bytes.size)
        try { assertTrue(before.sameAs(after)) }finally { before.recycle();after.recycle() }
        assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun nativeExifQuarterTurnsAndMirroringBecomeUprightPixelsWithoutChangingOrdinaryFiles() {
        for(orientation in listOf(2,6,8)) {
            val raw=png();val (uri,path)=file("转向-$orientation.png",raw)
            ExifInterface(path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes() }
            val source=path.readBytes();val original=runBlocking { NativeFileIntake.read(activity.contentResolver,uri) };assertArrayEquals(source,original.bytes)
            val input=runBlocking { NativePhotoIntake.read(activity.contentResolver,uri) }
            val actual=BitmapFactory.decodeByteArray(input.bytes,0,input.bytes.size)
            val pixels=when(orientation) {
                2->intArrayOf(0xff0000ff.toInt(),0xff00ff00.toInt(),0xffff0000.toInt(),0xff00ffff.toInt(),0xffff00ff.toInt(),0xffffff00.toInt())
                6->intArrayOf(0xffffff00.toInt(),0xffff0000.toInt(),0xffff00ff.toInt(),0xff00ff00.toInt(),0xff00ffff.toInt(),0xff0000ff.toInt())
                else->intArrayOf(0xff0000ff.toInt(),0xff00ffff.toInt(),0xff00ff00.toInt(),0xffff00ff.toInt(),0xffff0000.toInt(),0xffffff00.toInt())
            }
            try {
                assertEquals(if(orientation==2)3 else 2,actual.width);assertEquals(if(orientation==2)2 else 3,actual.height)
                val values=IntArray(6);actual.getPixels(values,0,actual.width,0,0,actual.width,actual.height);assertArrayEquals("Independent orientation $orientation pixel vector",pixels,values)
                assertTrue(ExifInterface(ByteArrayInputStream(input.bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,0)in listOf(0,1))
            }finally { actual.recycle() }
        }
    }
    @Test fun nonPngIsPreparedAsJpegAndUnreadableSourcesRemainTypedFailures() {
        val input=runBlocking { NativePhotoIntake.normalize(LocalAttachmentInput("保留 100% %@.gif","image/gif",png())) }
        assertEquals("保留 100% %@.jpg",input.name);assertEquals("image/jpeg",input.mediaType);assertEquals(0xff,input.bytes[0].toInt() and 255);assertEquals(0xd8,input.bytes[1].toInt() and 255)
        fail(NativePhotoIntake.UNREADABLE) { NativePhotoIntake.normalize(LocalAttachmentInput("source.png","text/plain",png())) }
        fail(NativePhotoIntake.UNREADABLE) { NativePhotoIntake.normalize(LocalAttachmentInput("source.png","image/png","not an image".toByteArray())) }
        fail(NativePhotoIntake.UNREADABLE) { NativePhotoIntake.read(activity.contentResolver,Uri.parse("https://example.invalid/image.png")) }
        fail(NativePhotoIntake.UNREADABLE) { NativePhotoIntake.read(activity.contentResolver,Uri.parse("content://missing.photo/image")) }
        fail(NativeFileIntakeFailure.TOO_LARGE) { NativePhotoIntake.normalize(LocalAttachmentInput("empty.png","image/png",byteArrayOf())) }
    }
    @Test fun originalRequestCannotConsumeOrFailANewerRequestAndDuplicateUrisDoNotDuplicateFiles() {
        connect();val picker=NativePhotoPicker(activity,session);val text=NativeL10n(activity,"en");val uri=file("原图 100% %@.png",png()).first
        lateinit var old: NativePhotoRequest;lateinit var fresh: NativePhotoRequest
        main { assertTrue(picker.begin(session.attachmentDestination(),waitForMenu=true));old=picker.pending!!;assertFalse(old.menuDismissed);picker.menuDidDismiss(old.destination.presentation.copy(selection=old.destination.presentation.selection+1));assertFalse(old.menuDismissed);picker.menuDidDismiss(old.destination.presentation);assertTrue(old.menuDismissed);assertFalse(picker.begin(session.attachmentDestination()));picker.complete(old,emptyList(),text)
            assertTrue(picker.begin(session.attachmentDestination()));fresh=picker.pending!!;picker.complete(old,listOf(uri),text);picker.launchFailed(old,text);assertTrue(picker.pending===fresh);assertTrue(session.attachments.isEmpty())
            picker.complete(fresh,listOf(uri,uri),text) }
        ui.waitUntil(15_000) { !session.importing&&session.attachments.size==1 };assertEquals("照片原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
        val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(session.attachments.single().manifest) };assertEquals("image/png",session.attachments.single().manifest.mediaType);assertTrue(bytes.isNotEmpty())
    }
    @Test fun selectedPhotoCommitsOnlyToItsOriginalGenerationAfterNavigation() {
        connect();val picker=NativePhotoPicker(activity,session);val uri=file("原图.png",png()).first;lateinit var original: NativePhotoRequest
        main { assertTrue(picker.begin(session.attachmentDestination()));original=picker.pending!!;session.selectThread(session.threads.single { it.id=="second" });session.text="第二草稿";picker.complete(original,listOf(uri),NativeL10n(activity,"en")) }
        ui.waitUntil(15_000) { !session.importing };assertEquals("second",session.selectedThread);assertEquals("第二草稿",session.text);assertTrue(session.attachments.isEmpty())
        main { session.selectThread(session.threads.single { it.id=="first" }) };ui.waitUntil(15_000) { !session.importing&&session.attachments.size==1 }
        assertEquals("照片原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun fallbackOverLimitAndLateReadFailureDoNotReplaceNewerError() {
        connect();val picker=NativePhotoPicker(activity,session);val text=NativeL10n(activity,"en")
        main { picker.begin(session.attachmentDestination());picker.complete(picker.pending!!,(1..6).map { Uri.parse("content://missing.photo/$it") },text) }
        assertEquals("Choose up to 5 photos at a time.",session.error);assertFalse(session.importing)
        main { session.error=null;picker.begin(session.attachmentDestination());val original=picker.pending!!;session.error="Newer raw failure 100% %@";picker.complete(original,listOf(Uri.parse("content://missing.photo/image")),text) }
        ui.waitUntil(15_000) { !session.importing };assertEquals("Newer raw failure 100% %@",session.error);assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun actualSystemImageOnlyPickerCancellationPreservesOriginalDraftAndFiles() {
        connect();main { session.configurePhotoPicker(activity) }
        val contract=ActivityResultContracts.PickMultipleVisualMedia(5);val request=PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).setMaxItems(5).build()
        val outgoing=contract.createIntent(activity,request);assertEquals("image/*",outgoing.type)
        val handler=activity.packageManager.resolveActivity(outgoing,0)!!;val pkg=handler.activityInfo.packageName;assertNotEquals(activity.packageName,pkg);assertTrue(handler.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0)
        val target=session.targetKey;val text=session.text
        ui.onNodeWithTag("composer-add").performClick()
        val entry=if(NativeRecentPhotos(activity).access()==NativePhotoAccess.NONE)"choose-photos"else"choose-system-photos"
        ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag(entry))
        ui.onNodeWithTag(entry).assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.photoPicker!!.pending?.launched==true||session.error!=null }
        val i=InstrumentationRegistry.getInstrumentation();val end=System.currentTimeMillis()+20_000
        while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!=pkg&&System.currentTimeMillis()<end)Thread.sleep(100)
        File(owned.folder,"picker-launch-state.json").writeText(JSONObject().put("expected",pkg).put("actual",i.uiAutomation.rootInActiveWindow?.packageName?.toString()).put("pending",session.photoPicker!!.pending!=null).put("launched",session.photoPicker!!.pending?.launched).put("error",session.error).put("launchFailure",session.photoPicker!!.lastLaunchFailure?.toString()).toString())
        assertEquals(pkg,i.uiAutomation.rootInActiveWindow?.packageName?.toString());assertTrue(session.photoPicker!!.pending!=null);assertTrue(session.photoPicker!!.pending!!.menuDismissed)
        ui.onAllNodesWithTag("choose-photos").assertCountEquals(0)
        ui.waitForIdle();i.uiAutomation.waitForIdle(400,8_000)
        assertEquals(pkg,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertTrue(checkNotNull(i.uiAutomation.rootInActiveWindow).childCount>0)
        val image=checkNotNull(i.uiAutomation.takeScreenshot());try { File(owned.folder,"system-image-picker.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
        assertTrue(i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(20_000) { session.photoPicker!!.pending==null };assertEquals(target,session.targetKey);assertEquals(text,session.text);assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty())
        File(owned.folder,"system-picker-cancel.json").writeText(JSONObject().put("systemImageOnlyRoute",true).put("action",outgoing.action).put("handler",pkg).put("cancelPreservesOriginalDraft",true).put("noPhotoSelected",true).put("newModelInputs",0).toString())
    }
}
