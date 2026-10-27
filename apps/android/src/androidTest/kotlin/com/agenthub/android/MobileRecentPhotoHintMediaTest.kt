package com.agenthub.android

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Owned SDK only; independent source UID, real MediaStore change -> native hint -> encrypted local draft. */
class MobileRecentPhotoHintMediaTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun independentNewPhotoReturnsThroughRealLibraryHintWithoutUploadOrSubmit() {
        require(Build.VERSION.SDK_INT>=29&&Build.HARDWARE in listOf("ranchu","goldfish"))
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity;val i=InstrumentationRegistry.getInstrumentation()
        require(activity.packageName=="com.easyai.agenthub.android.acceptance")
        val metadata=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("content query --uri content://media/external/images/media --projection _id")).use { it.bufferedReader().readText() }
        assertEquals("Only the owned empty SDK photo library is eligible", "No result found.",metadata.trim())
        val reader=NativeRecentPhotos(activity);assertEquals(NativePhotoAccess.FULL,reader.access())
        val a=RecentPhotoHintAcceptance(activity,{ condition -> ui.waitUntil(20_000,condition) },reader)
        var source: OwnedPhotoSelection?=null
        try {
            assertNull(a.library.suggestion);assertTrue(a.library.recent.isEmpty())
            source=OwnedPhotoSelection(activity,ui,a.owned);source.prepare()
            val report=source.report;val id=Uri.parse(report.getString("uri")).lastPathSegment
            ui.waitUntil(20_000) { a.library.suggestion?.photo?.uri?.lastPathSegment==id }
            ui.onNodeWithTag("recent-attachment-hint").assertIsDisplayed();assertTrue(a.session.attachments.isEmpty())
            a.owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-source-new-photo-hint")
            ui.onNodeWithTag("recent-attachment-preview-open").performClick();assertTrue(a.session.attachments.isEmpty());ui.waitUntil(15_000) {ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty()};a.owned.dialogShot("actual-source-new-photo-preview")
            ui.onNodeWithTag("attachment-image-done").performClick()
            ui.onNodeWithTag("composer-add").performClick()
            ui.waitUntil(10_000) {ui.onAllNodesWithTag("recent-photo-$id").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("recent-photo-$id").performClick()
            ui.waitUntil(15_000) {ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("photo-preview-select").performClick();a.owned.dialogShot("actual-source-panel-selected")
            ui.onNodeWithTag("photo-preview-add").assertIsDisplayed().performClick()
            ui.waitUntil(20_000) { !a.session.importing&&a.session.attachments.size==1&&!a.library.saving&&a.preferences.read(NativeRecentPhotoLibrary.PREFERENCES)?.contains("/"+id)==true }
            val file=a.session.attachments.single();assertEquals("content://media/external/images/media/$id",file.photoSourceId);val bytes=runBlocking { NativeDraftFiles(a.owned.storage).bytes(file.manifest) };val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                assertEquals(report.getInt("width"),image.width);assertEquals(report.getInt("height"),image.height)
                val values=IntArray(image.width*image.height);image.getPixels(values,0,image.width,0,0,image.width,image.height)
                val buffer=ByteBuffer.allocate(values.size*4);values.forEach(buffer::putInt)
                assertEquals(report.getString("pixelSha256"),MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString(""){"%02x".format(it)})
            }finally { image.recycle() }
            assertEquals(report.getString("fileName"),file.manifest.name);assertEquals("image/png",file.manifest.mediaType);assertEquals("最近图片原稿 100% %@ 😀",a.session.text)
            assertTrue(runBlocking { a.session.confirmInboxFiles(a.session.attachmentImportDestination(),listOf(file.manifest)) });assertNull(a.library.suggestion)
            File(a.owned.folder,"independent-native-recent-hint-proof.json").writeText(JSONObject().put("differentSourceUid",true).put("mediaObserverAndForegroundReturn",true).put("initialBaselineDidNotSuggest",true)
                .put("nativePreviewAndExplicitAdd",true).put("originalPixelsAndDraftMetadataMatched",true).put("ignoredPreferenceDurable",true).put("fullReadPermissionPreparedOnOwnedSdkOnly",true).put("noPhotoUriExplicitGrant",true).put("newModelInputs",0).put("uploadOrSubmit",false).toString())
        }finally {
            try { source?.cleanup() }finally { a.finish() }
        }
    }
}
