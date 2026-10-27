package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Color
import android.text.format.Formatter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** App-owned native attachment strip and real encrypted draft files, synthetic read-only computer. */
@RunWith(AndroidJUnit4::class)
class MobileDraftAttachmentUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val scope=TargetScope("codex",null,null,"attachment-fixture","attachment-workspace","attachment-runtime")
    private val capabilities=listOf("reply","commands:target-bound:v1","generation-settings","models:read","attachments:upload")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private var writes=0
    @Before fun setup() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","synthetic draft attachments").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("attachment-a","attachment-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") }))
                .put("capabilities",JSONArray(capabilities)).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId)
                .put("readTarget",CodexCommandCodec.target(scope)).put("messages",JSONArray().put(JSONObject().put("id","synthetic-body").put("role","assistant").put("text","synthetic attachment fixture")))
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray())
                    .put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","fixture-model",true,"low",listOf("low"),"standard",listOf("standard")))
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long)=EventBatch(scope.hostId,"attachment-fixture",0,false,true,capabilities,emptyList())
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt { writes++;error("This attachment strip examiner must never send") }
        }
        ui.waitUntil(30_000) {!ui.activity.session.restoring}
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"draft-images");session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic draft attachments","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
    }
    @After fun leave() { ui.runOnIdle { assertEquals(0,writes);session.leaveForeground();owned.verifyDefault() } }
    private fun image(): ByteArray {
        val b=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE)
        return try { java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray() }finally{b.recycle()}
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync {
            val view=ui.activity.window.decorView
            view.postOnAnimation(object: Runnable {
                private var remaining=3
                override fun run() { if(--remaining==0)frames.countDown() else view.postOnAnimation(this) }
            })
        }
        assertTrue("Current activity frames",frames.await(5,java.util.concurrent.TimeUnit.SECONDS))
        var bitmap: Bitmap?=null
        i.runOnMainSync {
            assertTrue("Capture only the focused owned activity window",ui.activity.window.decorView.hasWindowFocus())
            bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888)
        }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(ui.activity.window,image,{ status -> result=status;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue("Current owned window PixelCopy",done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            val run=InstrumentationRegistry.getArguments().getString("attachmentRunId") ?: "fixture"
            val folder=File(i.targetContext.filesDir,"acceptance/P4-attachment-strip/$run").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.window").put("package",i.targetContext.packageName)
                .put("activity",ui.activity.javaClass.name).put("width",image.width).put("height",image.height).put("pixelCopyResult",result)
                .put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{image.recycle()}
    }
    @Test fun tappingAttachmentNameKeepsDraftUntilItsSeparateRemoveButtonIsChosen() {
        main { session.addAttachment("original.txt","text/plain",ByteArray(2048){65});session.text="原正文保留" }
        val original=session.attachments.single()
        ui.onNodeWithTag("draft-attachment-${original.manifest.id}").assertIsDisplayed().performClick()
        assertEquals("Only the explicit remove button removes the original",listOf(original),session.attachments)
        ui.onNodeWithTag("draft-attachment-size-${original.manifest.id}").assertTextEquals(Formatter.formatShortFileSize(ui.activity,original.manifest.sizeBytes))
        shot("text-label-is-not-remove")
        ui.onNodeWithTag("draft-attachment-remove-${original.manifest.id}").assertContentDescriptionEquals("移除 ${original.manifest.name}").assertIsEnabled().performClick()
        assertTrue(session.attachments.isEmpty());assertEquals("原正文保留",session.text);assertEquals(0,writes)
    }
    @Test fun imageThumbnailAndFileSizeRemainVisibleAndRemoveOnlyTheirOwnFile() {
        main { session.addAttachment("image.png","image/png",image());session.addAttachment("another.txt","text/plain",ByteArray(4096){66}) }
        val image=session.attachments[0];val text=session.attachments[1]
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${image.manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("draft-attachment-image-${image.manifest.id}").assertIsDisplayed()
        ui.onNodeWithTag("draft-attachment-size-${image.manifest.id}").assertTextEquals(Formatter.formatShortFileSize(ui.activity,image.manifest.sizeBytes))
        ui.onNodeWithTag("draft-attachment-size-${text.manifest.id}").assertTextEquals(Formatter.formatShortFileSize(ui.activity,text.manifest.sizeBytes))
        shot("image-thumbnail-and-file-sizes")
        ui.onNodeWithTag("draft-attachment-remove-${image.manifest.id}").performClick()
        assertEquals(listOf(text),session.attachments);assertEquals("",session.text);assertEquals(0,writes)
    }
    @Test fun undecodableImageKeepsItsOriginalManifestAndDisplaysDocumentFallback() {
        val bytes="not an image; keep exact original bytes".toByteArray()
        main { session.addAttachment("unreadable.png","image/png",bytes) }
        val original=session.attachments.single()
        ui.onNodeWithTag("draft-attachment-document-${original.manifest.id}").assertIsDisplayed()
        ui.onNodeWithTag("draft-attachment-size-${original.manifest.id}").assertTextEquals(Formatter.formatShortFileSize(ui.activity,bytes.size.toLong()))
        assertEquals(listOf(original),session.attachments);assertEquals("image/png",original.manifest.mediaType)
        ui.onNodeWithTag("draft-attachment-${original.manifest.id}").performClick();assertEquals(listOf(original),session.attachments)
        assertEquals(0,writes);shot("undecodable-image-keeps-original-file")
    }
    @Test fun staleThumbnailReadAndRetainedRemoveActionCannotChangeARevisitedDraft() {
        val bytes=image()
        main { session.addAttachment("retained.png","image/png",bytes) }
        val original=session.attachments.single();val destination=session.attachmentDestination()
        ui.onNodeWithTag("draft-attachment-remove-${original.manifest.id}").assertIsDisplayed()
        // Keep the exact product action context; detached Compose node wrappers cannot be invoked.
        val remove={ session.removeAttachment(original.manifest.id,destination) }
        val a=session.threads.first();val b=session.threads.last()
        ui.runOnIdle { session.selectThread(b) };ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
        main { session.addAttachment("other.txt","text/plain","another draft".toByteArray());session.text="另一草稿" }
        val other=session.attachments.single()
        main { assertNull(session.draftAttachmentThumbnail(original,destination)) }
        ui.runOnIdle { remove() };assertEquals(listOf(other),session.attachments);assertEquals("另一草稿",session.text)
        ui.runOnIdle { session.selectThread(a) };ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
        assertEquals(listOf(original),session.attachments)
        ui.runOnIdle { remove() };assertEquals("A revisited same target still rejects the old navigation callback",listOf(original),session.attachments)
        main { assertNull(session.draftAttachmentThumbnail(original,destination));assertNotNull(session.draftAttachmentThumbnail(original,session.attachmentDestination()));assertArrayEquals(bytes,NativeDraftFiles(owned.storage).bytes(original.manifest)) }
        shot("revisited-original-rejects-old-remove")
        ui.onNodeWithTag("draft-attachment-remove-${original.manifest.id}").performClick();assertTrue(session.attachments.isEmpty())
        ui.runOnIdle { session.selectThread(b) };ui.waitUntil(10_000) { !session.historyLoading };assertEquals(listOf(other),session.attachments);assertEquals("另一草稿",session.text)
        assertEquals(0,writes)
    }

    @Test fun explicitDraftImagePreviewKeepsOriginalBytesAndOnlyRemovesCurrentPage() {
        val bytes=image()
        main { session.addAttachment("first.png","image/png",bytes);session.addAttachment("second.png","image/png",bytes);session.text="Unsent image draft" }
        val originals=session.attachments.toList()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${originals[0].manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("draft-attachment-image-${originals[0].manifest.id}").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("attachment-image-name").assertTextEquals("first.png")
        ui.onNodeWithTag("attachment-image-page-${originals[1].manifest.id}").performClick()
        ui.onNodeWithTag("attachment-image-name").assertTextEquals("second.png")
        ui.onNodeWithTag("draft-image-preview-remove").performClick()
        ui.waitUntil(10_000) { session.attachments.size==1 }
        assertEquals(listOf(originals[0]),session.attachments)
        ui.onNodeWithTag("attachment-image-name").assertTextEquals("first.png")
        ui.onNodeWithTag("attachment-image-done").performClick()
        ui.onAllNodesWithTag("attachment-image-viewer").assertCountEquals(0)
        main { assertArrayEquals(bytes,NativeDraftFiles(owned.storage).bytes(originals[0].manifest)) }
        assertEquals("Unsent image draft",session.text);assertEquals(0,writes)
    }
    @Test fun brokenDraftPreviewCanRetryWithoutMutatingOriginalAndRemovingLastCloses() {
        val bytes="broken original image".toByteArray()
        main { session.addAttachment("broken.png","image/png",bytes) }
        val original=session.attachments.single()
        ui.onNodeWithTag("draft-attachment-document-${original.manifest.id}").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attachment-image-error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("attachment-image-retry").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attachment-image-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(original),session.attachments)
        main { assertArrayEquals(bytes,NativeDraftFiles(owned.storage).bytes(original.manifest)) }
        ui.onNodeWithTag("draft-image-preview-remove").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attachment-image-viewer").fetchSemanticsNodes().isEmpty() }
        assertTrue(session.attachments.isEmpty());assertEquals(0,writes)
    }
    @Test fun draftPreviewClosesOnNavigationAndOldSourceCannotReadRevisitedDraft() {
        main { session.addAttachment("bound.png","image/png",image()) }
        val original=session.attachments.single();val destination=session.attachmentDestination();val a=session.threads.first()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${original.manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("draft-attachment-image-${original.manifest.id}").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle {session.selectThread(session.threads.last())}
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
        ui.onAllNodesWithTag("attachment-image-viewer").assertCountEquals(0)
        main {assertNull(session.draftAttachmentImage(original,destination))}
        ui.runOnIdle {session.selectThread(a)};ui.waitUntil(10_000) {!session.historyLoading&&session.canCompose}
        main {assertNull(session.draftAttachmentImage(original,destination));assertNotNull(session.draftAttachmentImage(original,session.attachmentDestination()))}
        assertEquals(listOf(original),session.attachments);assertEquals(0,writes)
    }

    @Test fun largeOriginalUsesSmallAuthenticatedCacheAndStillRequiresExactBytesForSubmission() {
        val bitmap=Bitmap.createBitmap(700,700,Bitmap.Config.ARGB_8888);val random=java.util.Random(42)
        bitmap.setPixels(IntArray(700*700) { Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) },0,700,0,0,700,700)
        val bytes=try { java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray() }finally{bitmap.recycle()}
        assertTrue(bytes.size>1024*1024)
        main { session.addAttachment("large-original.png","image/png",bytes) }
        val file=session.attachments.single();val store=owned.storage;val files=NativeDraftFiles(store)
        val cache=store.read("draft-thumbnail-v1:${file.manifest.id}")!!;assertTrue(cache.length<160*1024)
        assertEquals(file.manifest.body().toString(),JSONObject(cache).getJSONObject("file").toString())
        val source=store.read("draft-file:${file.manifest.id}")!!
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${file.manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        try {
            // A cached thumbnail is display evidence only; it cannot bypass the original hash check.
            store.write("draft-file:${file.manifest.id}",JSONObject(source).put("bytes",android.util.Base64.encodeToString("changed source".toByteArray(),android.util.Base64.NO_WRAP)).toString())
            main { assertNotNull(session.draftAttachmentThumbnail(file,session.attachmentDestination()));assertTrue(runCatching { files.bytes(file.manifest) }.isFailure) }
        }finally{store.write("draft-file:${file.manifest.id}",source)}
        main { assertArrayEquals(bytes,files.bytes(file.manifest)) }
        assertEquals(cache,store.read("draft-thumbnail-v1:${file.manifest.id}"))
        try {
            store.write("draft-thumbnail-v1:${file.manifest.id}",JSONObject(cache).put("file",file.manifest.body().put("sha256","0".repeat(64))).toString())
            main { assertNull("A wrong-source cache cannot display another file or force a large source read",session.draftAttachmentThumbnail(file,session.attachmentDestination())) }
            assertEquals(source,store.read("draft-file:${file.manifest.id}"))
        }finally{store.write("draft-thumbnail-v1:${file.manifest.id}",cache)}
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${file.manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("draft-attachment-image-${file.manifest.id}").assertIsDisplayed();shot("large-image-uses-bounded-cache")
        assertEquals(listOf(file),session.attachments);assertEquals(0,writes)
    }

    @Test fun jpegExifEightOrientationsAffectOnlyTheBoundedThumbnail() {
        val colors=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        val expected=listOf(listOf(0,1,2,3),listOf(1,0,3,2),listOf(3,2,1,0),listOf(2,3,0,1),listOf(0,2,1,3),listOf(2,0,3,1),listOf(3,1,2,0),listOf(1,3,0,2))
        val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);val canvas=android.graphics.Canvas(bitmap)
        for(n in 0..3)canvas.drawRect((n%2*40).toFloat(),(n/2*20).toFloat(),(n%2*40+40).toFloat(),(n/2*20+20).toFloat(),android.graphics.Paint().apply { color=colors[n] })
        val jpeg=try { java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }.toByteArray() }finally{bitmap.recycle()}
        val files=NativeDraftFiles(owned.storage)
        for(orientation in 1..8) {
            assertEquals(0xff,jpeg[0].toInt() and 0xff);assertEquals(0xd8,jpeg[1].toInt() and 0xff)
            // EXIF APP1: little-endian TIFF IFD0 with one inline SHORT orientation tag.
            val app1=byteArrayOf(0xff.toByte(),0xe1.toByte(),0,34,69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,orientation.toByte(),0,0,0,0,0,0,0)
            val source=jpeg.copyOfRange(0,2)+app1+jpeg.copyOfRange(2,jpeg.size)
            val run=InstrumentationRegistry.getArguments().getString("attachmentRunId") ?: "fixture"
            val fixtureFolder=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-attachment-strip/$run").apply { mkdirs() }
            File(fixtureFolder,"fixture-base.jpg").writeBytes(jpeg);File(fixtureFolder,"fixture-orientation-$orientation.jpg").writeBytes(source)
            java.io.ByteArrayInputStream(source).use {
                val exif=androidx.exifinterface.media.ExifInterface(it)
                File(fixtureFolder,"fixture-orientation-$orientation.json").writeText(JSONObject().put("expected",orientation).put("actual",exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)).put("raw",exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION)).put("width",exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH)).toString())
                assertEquals("Actual EXIF fixture orientation",orientation,exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1))
            }
            main { session.addAttachment("orientation-$orientation.jpg","image/jpeg",source) }
            val file=session.attachments.last();var thumbnail: ByteArray?=null
            main { thumbnail=session.draftAttachmentThumbnail(file,session.attachmentDestination());assertArrayEquals(source,files.bytes(file.manifest)) }
            val bytes=checkNotNull(thumbnail);val image=checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                assertEquals(if(orientation>=5)40 else 80,image.width);assertEquals(if(orientation>=5)80 else 40,image.height)
                val actual=(0..3).map { n->
                    val pixel=image.getPixel(image.width*(if(n%2==0)1 else 3)/4,image.height*(if(n/2==0)1 else 3)/4)
                    colors.indices.minBy { k->kotlin.math.abs(Color.red(colors[k])-Color.red(pixel))+kotlin.math.abs(Color.green(colors[k])-Color.green(pixel))+kotlin.math.abs(Color.blue(colors[k])-Color.blue(pixel)) }
                }
                assertEquals("Actual serialized JPEG EXIF orientation $orientation",expected[orientation-1],actual)
            }finally{image.recycle()}
        }
        val last=session.attachments.last()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("draft-attachment-image-${last.manifest.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("draft-attachment-image-${last.manifest.id}").performScrollTo().assertIsDisplayed();shot("jpeg-exif-thumbnail-original-bytes-unchanged")
        assertEquals(8,session.attachments.size);assertEquals(0,writes)
    }

}
