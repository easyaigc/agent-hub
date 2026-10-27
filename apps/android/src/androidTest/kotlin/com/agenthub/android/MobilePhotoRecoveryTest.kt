package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Controlled native registry results, actual owned providers/normalization/Keystore repositories. */
class MobilePhotoRecoveryTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    private val paths=mutableListOf<File>()
    private val registrations=mutableListOf<Triple<NativeSelectionRecovery,ActivityResultRegistry,Any>>()
    private val text get()=NativeL10n(ui.activity,"en")
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private class Registry(private val context: android.content.Context): ActivityResultRegistry() {
        var launches=0;var code=0;var outgoing: android.content.Intent?=null
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) {
            launches++;code=requestCode;outgoing=contract.createIntent(context,input)
        }
    }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };a=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
        ui.waitUntil(15_000) { a.session.photoPicker?.recovery?.loading==false&&a.session.documentPicker?.recovery?.loading==false }
    }
    @After fun finish() {
        if(::a.isInitialized) { main { registrations.forEach { (r,registry,owner)->r.detach(registry,owner) } };a.finish() }
        paths.forEach { assertTrue(!it.exists()||it.delete()) }
    }
    private fun registry(recovery: NativeSelectionRecovery,value: Registry=Registry(ui.activity)): Registry {
        val owner=Any();main { recovery.attach(value,owner) };registrations+=Triple(recovery,value,owner);return value
    }
    private fun request()=NativePhotoRequest(a.session.attachmentImportDestination(),a.session.attachmentErrorGeneration,false)
    private fun file(name: String,bytes: ByteArray): Uri {
        val parent=File(ui.activity.cacheDir,"resources/photo-result-${UUID.randomUUID()}").apply { mkdirs() };paths+=parent
        val file=File(parent,name).apply { writeBytes(bytes) };paths.add(0,file)
        return FileProvider.getUriForFile(ui.activity,ui.activity.packageName+".resources",file)
    }
    private fun pixels(): ByteArray {
        val image=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
        try { image.setPixels(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffff00.toInt(),0xffff00ff.toInt(),0xff00ffff.toInt()),0,3,0,0,3,2)
            return ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() }
        finally { image.recycle() }
    }
    private fun assertPixels(expected: ByteArray,actual: ByteArray) {
        val before=checkNotNull(BitmapFactory.decodeByteArray(expected,0,expected.size));val after=checkNotNull(BitmapFactory.decodeByteArray(actual,0,actual.size))
        try { assertTrue(before.sameAs(after)) }finally { before.recycle();after.recycle() }
    }
    @Test fun originalPhotoIsNormalizedEncryptedAndReturnedOnlyToOriginalDraftOnce() {
        val picker=a.session.photoPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery);val bytes=pixels();val uri=file("original 100% %@.PNG",bytes)
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);assertEquals("image/*",registry.outgoing!!.type)
            a.session.selectThread(a.session.threads.first { it.id=="second" });a.session.text="second photo draft";registry.dispatchResult(registry.code,listOf(uri,uri)) }
        ui.waitUntil(20_000) { picker.pending==null&&!recovery.busy&&!a.session.historyLoading }
        assertEquals("second photo draft",a.session.text);assertTrue(a.session.attachments.isEmpty())
        main { a.session.selectThread(a.session.threads.first { it.id=="first" }) };ui.waitUntil(15_000) { !a.session.historyLoading&&a.session.attachments.size==1 }
        val actual=a.session.attachments.single().manifest;assertEquals("original 100% %@.png",actual.name);assertEquals("image/png",actual.mediaType)
        assertPixels(bytes,runBlocking { NativeDraftFiles(a.owned.storage).bytes(actual) });assertTrue(main { a.session.confirmInboxFiles(original.destination,listOf(actual)) })
        main { registry.dispatchResult(registry.code,listOf(uri)) };ui.waitForIdle();assertEquals(1,a.session.attachments.size);assertEquals(1,registry.launches)
        val result=runBlocking { NativeDocumentJournal(a.owned.storage.child(ui.activity,"photo-results")).records().single() };assertEquals("consumed",result.phase)
    }
    @Test fun originalPhotoRegistryKeyRestoresWithoutOpeningASecondSystemChooser() {
        val picker=a.session.photoPicker!!;val first=registry(picker.recovery!!);val saved=Bundle();lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));picker.recovery!!.launch(original,text);first.onSaveInstanceState(saved) }
        val next=NativePhotoPicker(ui.activity,a.session,a.owned.storage.child(ui.activity,"photo-results"));val recovery=next.recovery!!
        ui.waitUntil(15_000) { !recovery.loading };assertEquals(original.id,next.pending!!.id);assertTrue(recovery.restoredWait)
        val second=Registry(ui.activity);main { second.onRestoreInstanceState(saved) };registry(recovery,second)
        val bytes=pixels();val uri=file("restored.png",bytes)
        main { assertTrue(second.dispatchResult(first.code,listOf(uri))) }
        ui.waitUntil(20_000) { next.pending==null&&!recovery.busy&&a.session.attachments.size==1 }
        assertPixels(bytes,runBlocking { NativeDraftFiles(a.owned.storage).bytes(a.session.attachments.single().manifest) });assertEquals(0,second.launches)
    }
    @Test fun sixPhotoResultsRejectBeforeReadingProvidersOrAcquiringGrants() {
        val picker=a.session.photoPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery);lateinit var original: NativePhotoRequest
        val before=ui.activity.contentResolver.persistedUriPermissions.map { it.uri.toString() to it.isReadPermission }
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);registry.dispatchResult(registry.code,(1..6).map { Uri.parse("content://never-read/photo/$it") }) }
        ui.waitUntil(15_000) { !recovery.busy&&picker.pending==null&&recovery.failures.any { it.id==original.id } }
        assertEquals(text.tr("一次最多选择 5 张图片。"),a.session.error);assertTrue(a.session.attachments.isEmpty())
        val spool=NativeAttachmentInboxStore(a.owned.storage.child(ui.activity,"photo-results").child(ui.activity,"payloads"));assertTrue(runBlocking { spool.snapshot().records.isEmpty() })
        assertEquals(before,ui.activity.contentResolver.persistedUriPermissions.map { it.uri.toString() to it.isReadPermission });assertEquals(1,registry.launches)
    }
    @Test fun failedPhotoBytesSurviveSourceRemovalAndOldDiscardCannotEndNewDocumentWait() {
        val picker=a.session.photoPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery);val bytes=pixels();val uri=file("retained.png",bytes);lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);a.session.clearAttachmentDraft(original);a.session.text="new independent draft";registry.dispatchResult(registry.code,listOf(uri)) }
        ui.waitUntil(20_000) { !recovery.busy&&recovery.failures.any { it.id==original.id } }
        assertTrue(a.session.attachments.isEmpty());val spool=NativeAttachmentInboxStore(a.owned.storage.child(ui.activity,"photo-results").child(ui.activity,"payloads"))
        val batch=runBlocking { spool.snapshot().records.single() };assertEquals("pending",batch.phase);assertPixels(bytes,runBlocking { spool.payload(batch,batch.readyFiles.single()).bytes })
        assertTrue(paths.first { it.isFile }.delete())
        val document=a.session.documentPicker!!;val documentRegistry=registry(document.recovery!!);lateinit var current: NativePhotoRequest
        main { current=request();assertTrue(document.begin(current));document.recovery!!.launch(current,text);document.recovery!!.retryRestore();recovery.retry(recovery.failures.single(),text) }
        ui.waitUntil(20_000) { !recovery.busy&&recovery.failures.isNotEmpty()&&!document.recovery!!.loading }
        assertEquals(batch,runBlocking { spool.snapshot().records.single() });assertSame(current,document.pending)
        ui.onNodeWithTag("photo-recovery-discard").performScrollTo().assertIsDisplayed();a.owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"photo-failure-and-document-wait")
        ui.onNodeWithTag("photo-recovery-discard").performClick();ui.waitUntil(15_000) { recovery.failures.isEmpty() }
        assertSame(current,document.pending);assertEquals("new independent draft",a.session.text);assertTrue(a.session.attachments.isEmpty());assertEquals(1,registry.launches)
        main { documentRegistry.dispatchResult(documentRegistry.code,emptyList<Uri>()) };ui.waitUntil(15_000) { document.pending==null }
    }
    @Test fun endingUnknownPhotoWaitIgnoresLateOriginalResultAndDoesNotAffectReplacement() {
        val picker=a.session.photoPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery);lateinit var old: NativePhotoRequest
        main { old=request();assertTrue(picker.begin(old));recovery.launch(old,text);recovery.retryRestore() };val oldCode=registry.code
        ui.waitUntil(15_000) { !recovery.loading&&recovery.restoredWait }
        ui.onNodeWithTag("photo-recovery-end-wait").performScrollTo().assertIsDisplayed();a.owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"photo-unknown-wait")
        ui.onNodeWithTag("photo-recovery-end-wait").performClick();ui.waitUntil(15_000) { picker.pending==null&&recovery.canBegin }
        lateinit var current: NativePhotoRequest;main { current=request();assertTrue(picker.begin(current));recovery.launch(current,text) };val currentCode=registry.code
        val uri=file("late.png",pixels());main { registry.dispatchResult(oldCode,listOf(uri)) };ui.waitUntil(15_000) { !recovery.busy }
        assertSame(current,picker.pending);assertTrue(a.session.attachments.isEmpty());main { registry.dispatchResult(currentCode,emptyList<Uri>()) };ui.waitUntil(15_000) { picker.pending==null }
        assertEquals(2,registry.launches)
    }
    @Test fun corruptPhotoJournalPreservesItsBytesAndDoesNotDisableIndependentFileSelection() {
        val store=a.owned.storage.child(ui.activity,"photo-results");val raw="{\"version\":999,\"revision\":0,\"records\":[]}"
        store.write("document-requests-v1",raw);val before=File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() }
        main { a.session.photoPicker!!.recovery!!.retryRestore() };ui.waitUntil(15_000) { !a.session.photoPicker!!.recovery!!.loading }
        val recovery=a.session.photoPicker!!.recovery!!;assertTrue(recovery.failed);assertFalse(main { a.session.photoPicker!!.begin(request()) });assertFalse(a.session.canRemoveDraftAttachment)
        val document=a.session.documentPicker!!;val registry=registry(document.recovery!!)
        main { assertTrue(document.begin(request()));document.recovery!!.launch(document.pending!!,text);registry.dispatchResult(registry.code,emptyList<Uri>()) }
        ui.waitUntil(15_000) { document.pending==null };assertEquals(raw,store.read("document-requests-v1"));assertEquals(before,File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() })
        ui.onNodeWithTag("photo-recovery-retry-read").performScrollTo().assertIsDisplayed();a.owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"photo-record-read-failed")
    }
    @Test fun readyLocalResultResumesWhenParentDraftBecomesReadyWithoutFocusChangeOrNewPicker() {
        // Both local loaders share this restoration ordering. Parent restore is deliberately started later.
        val destination=main { a.session.attachmentImportDestination() };assertTrue(main { a.session.confirmAttachmentOrigin(destination) })
        main { a.session.leaveForeground() };runBlocking { a.owned.storage.awaitQueuedWrites() }
        val parentMetadata=checkNotNull(a.owned.storage.metadata()).toString()
        for(kind in listOf(NativeSelectionKind.DOCUMENT,NativeSelectionKind.PHOTO)) {
            val suffix=if(kind==NativeSelectionKind.DOCUMENT)"document-parent-order"else"photo-parent-order"
            val parent=a.owned.storage.child(ui.activity,suffix);parent.write("metadata",parentMetadata);val store=parent.child(ui.activity,"selected-result")
            val original=NativePhotoRequest(destination,Long.MIN_VALUE,false);val bytes=if(kind==NativeSelectionKind.PHOTO)pixels()else"parent waiting exact bytes".toByteArray()
            val uri=file(if(kind==NativeSelectionKind.PHOTO)"parent.png"else"parent.txt",bytes);val journal=NativeDocumentJournal(store)
            runBlocking { journal.restore();journal.prepare(original);journal.update(original.id) { it.copy(phase="selected",sources=listOf(uri.toString())) }
                NativeAttachmentInboxStore(store.child(ui.activity,"payloads")).stage(original.id,listOf(uri),ui.activity.contentResolver) { kind.read(ui.activity.contentResolver,it) }
                journal.update(original.id) { it.copy(phase="ready") } }
            val next=main { MobileSession(parent) };val picker: NativeSelectionOwner=if(kind==NativeSelectionKind.PHOTO)NativePhotoPicker(ui.activity,next,store)else NativeDocumentPicker(ui.activity,next,store)
            val recovery=if(picker is NativePhotoPicker)picker.recovery!!else(picker as NativeDocumentPicker).recovery!!
            val registry=Registry(ui.activity);val owner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
            main { ui.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { NativeDocumentRecoveryResult(picker,recovery) } } }
            ui.waitUntil(20_000) { !recovery.loading&&!recovery.busy&&runBlocking { journal.records().single().phase=="ready" } }
            assertFalse(recovery.parentReady);assertEquals(0,registry.launches)
            main { next.restore() };ui.waitUntil(20_000) { !next.restoring&&!recovery.busy&&runBlocking { journal.records().single().phase=="consumed" } }
            assertTrue(next.attachments.any { it.manifest.name==if(kind==NativeSelectionKind.PHOTO)"parent.png"else"parent.txt" });assertEquals(0,registry.launches)
            val actual=next.attachments.first { it.manifest.name==if(kind==NativeSelectionKind.PHOTO)"parent.png"else"parent.txt" }.manifest
            val actualBytes=runBlocking { NativeDraftFiles(parent).bytes(actual) };if(kind==NativeSelectionKind.PHOTO)assertPixels(bytes,actualBytes)else assertArrayEquals(bytes,actualBytes)
            main { next.leaveForeground() }
        }
    }
}
