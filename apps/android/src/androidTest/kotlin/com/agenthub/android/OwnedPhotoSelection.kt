package com.agenthub.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

/** Select only an exact nonce album, or the sole globally verified original media row on an owned emulator. */
internal class OwnedPhotoSelection(private val activity: MainActivity,private val ui: ComposeContentTestRule,private val owned: OwnedControlAcceptance) {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val session get()=owned.session
    val nonce=UUID.randomUUID().toString().replace("-","")
    private val sourcePackage=i.context.packageName
    private val permissions=mutableMapOf<String,Boolean>()
    private var created=false
    private var pickerPackage: String?=null
    lateinit var report: JSONObject
        private set
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun source(mode: String) {
        main { activity.startActivity(Intent().setComponent(ComponentName(sourcePackage,"com.agenthub.android.OwnedPhotoMediaActivity"))
            .putExtra("nonce",nonce).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun receipt(suffix: String): JSONObject {
        val uri=DocumentsContract.buildDocumentUri(sourcePackage+".owned-documents","photo-source-$nonce-$suffix.txt")
        var data: JSONObject?=null
        ui.waitUntil(25_000) { try { data=activity.contentResolver.openInputStream(uri)!!.use { JSONObject(it.bufferedReader().readText()) };true }catch(_: Exception) { false } }
        return checkNotNull(data).also { assertFalse(it.optString("message"),it.optString("status")=="failed") }
    }
    private fun permissionCommand(mode: String,permission: String) {
        require(mode in listOf("grant","revoke")&&permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("pm $mode $sourcePackage $permission")).use { it.readBytes() }
        assertEquals(mode=="grant",activity.packageManager.checkPermission(permission,sourcePackage)==PackageManager.PERMISSION_GRANTED)
    }
    fun prepare() {
        require(activity.packageName=="com.easyai.agenthub.android.acceptance"&&sourcePackage==activity.packageName+".test")
        if(Build.VERSION.SDK_INT<29)for(permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            val granted=activity.packageManager.checkPermission(permission,sourcePackage)==PackageManager.PERMISSION_GRANTED;permissions[permission]=granted
            if(!granted)permissionCommand("grant",permission)
        }
        created=true;source("prepare");report=receipt("report");assertEquals("prepared",report.getString("status"))
        assertEquals(sourcePackage,report.getString("sourcePackage"));assertEquals(activity.packageManager.getApplicationInfo(sourcePackage,0).uid,report.getInt("sourceUid"))
        assertNotEquals(Process.myUid(),report.getInt("sourceUid"));assertNotEquals(Process.myPid(),report.getInt("sourcePid"));assertFalse(report.getBoolean("photoUriExplicitlyGranted"))
        assertEquals(PackageManager.PERMISSION_DENIED,activity.checkUriPermission(Uri.parse(report.getString("uri")),Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION))
        File(owned.folder,"owned-photo-source.json").writeText(report.toString())
        val metadata=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("content query --uri ${report.getString("uri")} --projection _id:mime_type:width:height:_size:date_added:media_type")).use { it.bufferedReader().readText() }
        File(owned.folder,"owned-media-metadata.txt").writeText(metadata)
        ui.waitUntil(20_000) { session.canCompose&&!session.loading&&!session.historyLoading&&session.error==null }
    }
    private fun nodes(pkg: String): List<AccessibilityNodeInfo> {
        val root=i.uiAutomation.rootInActiveWindow ?: return emptyList();if(root.packageName?.toString()!=pkg)return emptyList()
        return buildList { fun visit(node: AccessibilityNodeInfo) { if(node.packageName?.toString()!=pkg)return;add(node);for(n in 0 until node.childCount)node.getChild(n)?.let(::visit) };visit(root) }
    }
    private fun click(pkg: String,node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo?=node
        while(target!=null&&target.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })target=target.parent
        assertNotNull("Exact native control advertises CLICK",target);assertEquals(pkg,target!!.packageName?.toString());assertTrue(target.isVisibleToUser&&target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun find(pkg: String,label: String,scroll: Boolean=false): AccessibilityNodeInfo {
        val end=System.currentTimeMillis()+15_000;var scrolled=0;var nextScroll=System.currentTimeMillis()+800
        while(System.currentTimeMillis()<end) {
            val visible=nodes(pkg);visible.firstOrNull { it.text?.toString()==label||it.contentDescription?.toString()?.contains(label)==true }?.let { return it }
            if(scroll&&scrolled<8&&System.currentTimeMillis()>=nextScroll) {
                visible.firstOrNull { it.isScrollable&&it.className?.toString()?.let { name->name.contains("RecyclerView")||name.contains("GridView")||name.contains("ScrollView") }==true }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);scrolled++;nextScroll=System.currentTimeMillis()+700
            }
            Thread.sleep(80)
        }
        File(owned.folder,"owned-album-controls.txt").writeText(nodes(pkg).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}|${it.actionList.map { a->a.id }}" })
        throw AssertionError("Exact synthetic album/control unavailable: $label")
    }
    fun choose(): LocalDraftFile {
        val original=session.attachmentDestination();val draft=session.text;val files=session.attachments
        val contract=ActivityResultContracts.PickMultipleVisualMedia(5);val intent=contract.createIntent(activity,PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).setMaxItems(5).build())
        val pkg=activity.packageManager.resolveActivity(intent,0)!!.activityInfo.packageName;pickerPackage=pkg
        ui.onNodeWithTag("composer-add").assertIsEnabled().performClick();ui.onNodeWithTag("choose-photos").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.photoPicker?.pending?.launched==true }
        val request=session.photoPicker!!.pending!!;assertTrue(request.menuDismissed);assertEquals(original,request.destination.presentation)
        val end=System.currentTimeMillis()+20_000;while(nodes(pkg).isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(100);assertTrue(nodes(pkg).isNotEmpty());i.uiAutomation.waitForIdle(350,8000)
        if(intent.action==Intent.ACTION_OPEN_DOCUMENT) {
            val drawer=nodes(pkg).firstOrNull { it.contentDescription?.toString() in listOf("Show roots","显示根目录","Open navigation drawer","打开导航抽屉","显示导航栏") }
            if(drawer!=null)click(pkg,drawer)
            val rootEnd=System.currentTimeMillis()+10_000;var images: AccessibilityNodeInfo?=null
            while(images==null&&System.currentTimeMillis()<rootEnd) { images=nodes(pkg).firstOrNull { it.text?.toString() in listOf("Images","图片") };if(images==null)Thread.sleep(100) }
            if(images==null)File(owned.folder,"system-images-controls.txt").writeText(nodes(pkg).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}" })
            click(pkg,checkNotNull(images));click(pkg,find(pkg,report.getString("album"),scroll=true));click(pkg,find(pkg,report.getString("fileName"),scroll=true))
        } else {
            var albums: AccessibilityNodeInfo?=null
            val albumEnd=System.currentTimeMillis()+10_000
            while(albums==null&&System.currentTimeMillis()<albumEnd) {
                albums=nodes(pkg).firstOrNull { it.text?.toString() in listOf("Albums","相册","影集")||it.contentDescription?.toString() in listOf("Albums","相册","影集") }
                if(albums==null)Thread.sleep(100)
            }
            if(albums==null)File(owned.folder,"system-albums-controls.txt").writeText(nodes(pkg).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}" })
            click(pkg,checkNotNull(albums))
            val ownAlbum=nodes(pkg).firstOrNull { it.text?.toString()==report.getString("album") }
            if(ownAlbum!=null)click(pkg,ownAlbum) else {
                require(InstrumentationRegistry.getArguments().getString("ownedPhotoOnlyMedia")=="1"&&Build.HARDWARE in listOf("ranchu","goldfish")) { "Custom album absent; physical device selection stays pending" }
                val media=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("content query --uri content://media/external/images/media --projection _id")).use { it.bufferedReader().readText() }
                val ids=Regex("_id=(\\d+)").findAll(media).map { it.groupValues[1] }.toList()
                File(owned.folder,"sole-media-proof.json").writeText(JSONObject().put("onlyGlobalMediaId",ids.singleOrNull()).put("sourceMediaId",Uri.parse(report.getString("uri")).lastPathSegment).toString())
                assertEquals(listOf(Uri.parse(report.getString("uri")).lastPathSegment),ids)
                val photos=nodes(pkg).first { it.text?.toString() in listOf("Photos","照片")||it.contentDescription?.toString() in listOf("Photos","照片") }
                click(pkg,photos)
            }
            i.uiAutomation.waitForIdle(350,8000)
            val thumbEnd=System.currentTimeMillis()+10_000;var thumbs=emptyList<AccessibilityNodeInfo>()
            while(System.currentTimeMillis()<thumbEnd) {
                val all=nodes(pkg);val exact=all.filter { it.viewIdResourceName?.substringAfterLast('/') in listOf("icon_thumbnail","thumbnail","picker_item_thumbnail") }
                thumbs=if(exact.isNotEmpty())exact else all.filter { it.contentDescription?.toString()?.let { label->label.startsWith("Photo taken")||label.startsWith("照片拍摄")||label.startsWith("图片拍摄") }==true }
                thumbs=thumbs.distinctBy { val rect=Rect();it.getBoundsInScreen(rect);rect.flattenToString() }
                if(thumbs.size==1)break;Thread.sleep(100)
            }
            if(thumbs.size!=1) { File(owned.folder,"owned-photo-controls.txt").writeText(nodes(pkg).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}" });throw AssertionError("Only the single known synthetic photo may be selected") }
            // AX can precede compositor pixels. Observe the known synthetic text in its exact thumbnail bounds before saving visual evidence.
            val bounds=Rect();thumbs.single().getBoundsInScreen(bounds);var settled: Bitmap?=null
            val renderEnd=System.currentTimeMillis()+8_000
            while(settled==null&&System.currentTimeMillis()<renderEnd) {
                val frame=checkNotNull(i.uiAutomation.takeScreenshot());var dark=0
                for(y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(frame.height) step 2)
                    for(x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(frame.width) step 2) {
                        val pixel=frame.getPixel(x,y);if(android.graphics.Color.red(pixel)<80&&android.graphics.Color.green(pixel)<80&&android.graphics.Color.blue(pixel)<80)dark++
                    }
                if(dark>100)settled=frame else { frame.recycle();Thread.sleep(120) }
            }
            if(settled==null) {
                val diagnostic=checkNotNull(i.uiAutomation.takeScreenshot());try { File(owned.folder,"owned-system-render-pending.png").outputStream().use { diagnostic.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { diagnostic.recycle() }
                File(owned.folder,"owned-system-render-pending.json").writeText(JSONObject().put("thumbnailBounds",bounds.flattenToString()).put("systemPackage",pkg).put("viewport",nodes(pkg).joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}|${it.isVisibleToUser}" }).toString())
            }
            val image=checkNotNull(settled) { "Known synthetic thumbnail pixels have not settled" }
            try { File(owned.folder,"system-owned-photo-settled.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
            click(pkg,thumbs.single())
        }
        val confirmed=System.currentTimeMillis()+12_000
        while(session.photoPicker!!.pending!=null&&System.currentTimeMillis()<confirmed) {
            val buttons=nodes(pkg).filter { it.isEnabled&&it.text?.toString()?.matches(Regex("(Add|添加|Done|完成|OPEN|Open|打开|选择)(\\s*[（(]1[）)])?",RegexOption.IGNORE_CASE))==true }
            if(buttons.size==1)click(pkg,buttons.single());Thread.sleep(100)
        }
        ui.waitUntil(20_000) { session.photoPicker!!.pending==null&&!session.importing&&session.attachments.size==files.size+1 }
        assertTrue(session.isCurrent(original));assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
        val selected=session.attachments.single { candidate->files.none { it.manifest.id==candidate.manifest.id } };val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(selected.manifest) }
        val bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try {
            assertEquals(report.getInt("width"),bitmap.width);assertEquals(report.getInt("height"),bitmap.height)
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);val encoded=ByteBuffer.allocate(pixels.size*4);pixels.forEach(encoded::putInt)
            assertEquals(report.getString("pixelSha256"),sha(encoded.array()))
        }finally { bitmap.recycle() }
        val originalName=report.getString("fileName")
        val privacyName=Uri.parse(report.getString("uri")).lastPathSegment+".png"
        assertTrue("System photo alias must retain this exact original media ID or original name",selected.manifest.name==originalName||selected.manifest.name==privacyName)
        assertEquals("image/png",selected.manifest.mediaType)
        assertTrue(runBlocking { session.confirmInboxFiles(request.destination,listOf(selected.manifest)) })
        File(owned.folder,"system-positive-photo-proof.json").writeText(JSONObject().put("independentSourceUid",true).put("noPhotoReadGrantBeforeSelection",true).put("actualSystemPicker",true)
            .put("onlyOriginalOwnedPhotoSelected",true).put("actualCallbackOriginalRequest",true).put("nameMimePixelsEncryptedBytesMatched",true).put("durableOriginalDraft",true).put("markerAbsentFromFilename",!selected.manifest.name.contains(report.getString("marker"),true))
            .put("sourceName",originalName).put("systemResultName",selected.manifest.name).put("platformPrivacyAlias",selected.manifest.name==privacyName).put("sourceReportSha256",sha(report.toString().toByteArray())).put("newModelInputs",0).put("uploadOrSubmit",false).toString())
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"system-selected-photo-original-draft");return selected
    }
    fun cleanup() {
        try {
            pickerPackage?.let { pkg -> if(nodes(pkg).isNotEmpty())i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            if(created) {
                main { activity.startActivity(Intent(activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                ui.waitUntil(10_000) { activity.hasWindowFocus() }
                source("cleanup");val result=receipt("cleanup");assertEquals("cleaned",result.getString("status"));assertTrue(result.getBoolean("onlyOriginalOwnedPhotoRemoved"));File(owned.folder,"photo-source-cleanup.json").writeText(result.toString()) }
        }finally {
            for((permission,granted)in permissions)if(!granted&&activity.packageManager.checkPermission(permission,sourcePackage)==PackageManager.PERMISSION_GRANTED)permissionCommand("revoke",permission)
        }
    }
}
