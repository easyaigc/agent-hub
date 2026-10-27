package com.agenthub.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Synthetic Android registry callbacks, real encrypted bytes and draft repositories; not a process-death proof. */
class MobileDocumentRecoveryTest {
    private val args get()=androidx.test.platform.app.InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule(
        { args.getString("viewportOrientation") ?: "" },
        { File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/document-viewport").apply { mkdirs() } },{"document"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    private val paths=mutableListOf<File>()
    private val registrations=mutableListOf<Triple<NativeDocumentRecovery,ActivityResultRegistry,Any>>()
    private val text get()=NativeL10n(ui.activity,"en")
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private class Registry: ActivityResultRegistry() {
        var launches=0;var code=0
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) { launches++;code=requestCode }
    }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };a=RecentPhotoHintAcceptance(ui.activity,{ condition -> ui.waitUntil(20_000,condition) })
        ui.waitUntil(15_000) { a.session.documentPicker?.recovery?.loading==false }
    }
    @After fun finish() {
        if(::a.isInitialized) {
            main { registrations.forEach { (r,registry,owner)->r.detach(registry,owner) } }
            a.finish()
        }
        paths.forEach { assertTrue(!it.exists()||it.delete()) }
    }
    private fun registry(recovery: NativeDocumentRecovery,value: Registry=Registry()): Registry {
        val owner=Any();main { recovery.attach(value,owner) };registrations+=Triple(recovery,value,owner);return value
    }
    private fun request(session: MobileSession=a.session)=NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,false)
    private fun file(name: String,bytes: ByteArray): Uri {
        val parent=File(ui.activity.cacheDir,"resources/document-${UUID.randomUUID()}").apply { mkdirs() };paths+=parent
        val file=File(parent,name).apply { writeBytes(bytes) };paths.add(0,file)
        return FileProvider.getUriForFile(ui.activity,ui.activity.packageName+".resources",file)
    }
    @Test fun selectionIsEncryptedAndCommittedOnlyToOriginalDraftAndDuplicateResultCannotAppendAgain() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        val bytes="原文件 😀 100% %@\r\n".toByteArray();val uri=file("original 100% %@.txt",bytes)
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);assertTrue(original.launched);assertEquals(1,registry.launches) }
        main { a.session.selectThread(a.session.threads.first { it.id=="second" });a.session.text="第二草稿";assertTrue(registry.dispatchResult(registry.code,listOf(uri,uri))) }
        ui.waitUntil(20_000) { picker.pending==null&&!a.session.importing&&!a.session.historyLoading }
        assertEquals("第二草稿",a.session.text);assertTrue(a.session.attachments.isEmpty())
        main { a.session.selectThread(a.session.threads.first { it.id=="first" }) }
        ui.waitUntil(15_000) { !a.session.historyLoading&&a.session.attachments.size==1 }
        val actual=a.session.attachments.single();assertArrayEquals(bytes,runBlocking { NativeDraftFiles(a.owned.storage).bytes(actual.manifest) })
        assertTrue(runBlocking { a.session.confirmInboxFiles(original.destination,listOf(actual.manifest)) })
        main { registry.dispatchResult(registry.code,listOf(uri)) };ui.waitForIdle();assertEquals(1,a.session.attachments.size);assertEquals(1,registry.launches)
    }
    @Test fun restoredJournalAndRestoredRegistryRouteTheOriginalResultWithoutLaunchingAnotherPicker() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val first=registry(recovery)
        val bytes="registry restoration 原字节\n".toByteArray();val uri=file("restored.txt",bytes);val saved=Bundle()
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);first.onSaveInstanceState(saved) }
        val replacement=NativeDocumentPicker(ui.activity,a.session,a.owned.storage.child(ui.activity,"document-results"));val restored=replacement.recovery!!
        ui.waitUntil(15_000) { !restored.loading };assertFalse(restored.failed);assertTrue(restored.restoredWait);assertEquals(original.id,replacement.pending!!.id)
        val second=Registry();main { second.onRestoreInstanceState(saved) };registry(restored,second)
        main { restored.recoverReady(text) };assertEquals(0,second.launches)
        main { a.session.error="newer error preserved";assertTrue(second.dispatchResult(first.code,listOf(uri))) }
        ui.waitUntil(20_000) { replacement.pending==null&&a.session.attachments.size==1&&!a.session.importing }
        val actual=a.session.attachments.single();assertArrayEquals(bytes,runBlocking { NativeDraftFiles(a.owned.storage).bytes(actual.manifest) })
        assertEquals("newer error preserved",a.session.error);assertEquals(0,second.launches)
    }
    @Test fun endedOriginalWaitCannotConsumeReplacementOrImportLateOriginalUris() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text) };val oldCode=registry.code
        main { recovery.retire(original.id) };ui.waitUntil(15_000) { picker.pending==null&&recovery.canBegin }
        lateinit var current: NativePhotoRequest
        main { current=request();assertTrue(picker.begin(current));recovery.launch(current,text) };val currentCode=registry.code;assertNotEquals(oldCode,currentCode)
        val uri=file("late.txt","late original".toByteArray())
        main { registry.dispatchResult(oldCode,listOf(uri));a.session.error="current error" }
        ui.waitUntil(15_000) { recovery.canBegin };assertSame(current,picker.pending);assertTrue(a.session.attachments.isEmpty());assertEquals("current error",a.session.error)
        main { registry.dispatchResult(currentCode,emptyList<Uri>()) };ui.waitUntil(15_000) { picker.pending==null };assertTrue(a.session.attachments.isEmpty())
    }
    @Test fun unknownOrCorruptJournalIsPreservedAndDoesNotBecomeAnEmptyWritableLedger() {
        val store=a.owned.storage.child(ui.activity,"document-boundary");val journal=NativeDocumentJournal(store)
        runBlocking { assertTrue(journal.restore().isEmpty()) }
        val original=main { request() };runBlocking { journal.prepare(original) }
        val raw=store.read("document-requests-v1")!!;assertTrue(raw.contains(original.id))
        val uri=file("acknowledgement-gap.txt","already selected".toByteArray())
        runBlocking { journal.update(original.id) { it.copy(phase="selected",sources=listOf(uri.toString())) };journal.update(original.id) { it.copy(phase="ready") } }
        val restored=main { NativeDocumentPicker(ui.activity,a.session,store).recovery!! }
        ui.waitUntil(15_000) { !restored.loading };assertFalse(restored.failed);assertFalse(restored.isProcessing(original.id))
        assertTrue(restored.preparing(original.destination.key,original.destination.generation))
        assertTrue(restored.blocksRemoval(original.destination.key,original.destination.generation))
        assertFalse(restored.preparing(original.destination.key,UUID.randomUUID().toString()))
        assertFalse(restored.blocksRemoval(original.destination.key,UUID.randomUUID().toString()))
        runBlocking { journal.update(original.id) { it.copy(phase="retired") } }
        store.write("document-requests-v1","{\"version\":999,\"revision\":0,\"records\":[]}")
        val before=File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() }
        val unreadable=main { NativeDocumentPicker(ui.activity,a.session,store).recovery!! }
        ui.waitUntil(15_000) { !unreadable.loading };assertTrue(unreadable.failed);assertTrue(unreadable.blocksRemoval(original.destination.key,original.destination.generation))
        assertNotNull(runCatching { runBlocking { NativeDocumentJournal(store).restore() } }.exceptionOrNull())
        assertNotNull(runCatching { runBlocking { journal.prepare(main { request() }) } }.exceptionOrNull())
        val after=File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() };assertEquals(before,after)
    }
    @Test fun recoveredUnknownWaitHasExplicitAccessibleEndAndLateFilesCannotChangeTheDraft() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);recovery.retryRestore() }
        ui.waitUntil(15_000) { !recovery.loading&&recovery.restoredWait }
        ui.onNodeWithTag("document-recovery-notice").assertIsDisplayed()
        ui.onNodeWithTag("document-recovery-end-wait").performScrollTo().assertIsDisplayed().assertIsEnabled()
        a.owned.nodeShot(ui.onNodeWithTag("document-recovery-notice"),"recovered-unknown-wait")
        ui.onNodeWithTag("document-recovery-end-wait").performScrollTo().performClick();ui.waitUntil(15_000) { picker.pending==null }
        val uri=file("late-ended.txt","not imported".toByteArray());main { registry.dispatchResult(registry.code,listOf(uri)) }
        ui.waitUntil(15_000) { recovery.canBegin };assertTrue(a.session.attachments.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",a.session.text)
        assertEquals(1,registry.launches)
    }
    @Test fun failedOriginalGenerationKeepsEncryptedBytesAndExplicitDiscardCannotAlterTheNewDraft() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        val bytes="retained selected bytes %@ 100%\n".toByteArray();val uri=file("retained.txt",bytes)
        lateinit var original: NativePhotoRequest
        main {
            original=request();assertTrue(picker.begin(original));recovery.launch(original,text)
            a.session.clearAttachmentDraft(original);a.session.text="new draft preserved";a.session.error="newer error";registry.dispatchResult(registry.code,listOf(uri))
        }
        ui.waitUntil(20_000) { !recovery.isProcessing(original.id)&&recovery.failures.any { it.id==original.id } }
        assertTrue(a.session.attachments.isEmpty());assertEquals("new draft preserved",a.session.text);assertEquals("newer error",a.session.error)
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed().performClick();assertNull(a.session.error)
        val payloads=NativeAttachmentInboxStore(a.owned.storage.child(ui.activity,"document-results").child(ui.activity,"payloads"))
        val before=runBlocking { payloads.snapshot().records.single { it.id==original.id } };assertEquals("pending",before.phase)
        assertArrayEquals(bytes,runBlocking { payloads.payload(before,before.readyFiles.single()).bytes })
        ui.onNodeWithTag("document-recovery-retry-files").performScrollTo().assertIsDisplayed().assertIsEnabled();ui.onNodeWithTag("document-recovery-discard").performScrollTo().assertIsDisplayed().assertIsEnabled()
        a.owned.nodeShot(ui.onNodeWithTag("document-recovery-notice"),"failed-original-file-recovery")
        // The provider file disappears; retry still uses the exact encrypted local result, not a new picker.
        val originalFile=paths.first { it.isFile };assertTrue(originalFile.delete())
        ui.onNodeWithTag("document-recovery-retry-files").performScrollTo().performClick()
        ui.waitUntil(20_000) { !recovery.isProcessing(original.id)&&recovery.failures.any { it.id==original.id } }
        assertEquals(before,runBlocking { payloads.snapshot().records.single { it.id==original.id } });assertEquals(1,registry.launches)
        ui.onNodeWithTag("document-recovery-discard").performScrollTo().assertIsEnabled().performClick();ui.waitUntil(15_000) { recovery.failures.isEmpty() }
        assertEquals("consumed",runBlocking { payloads.snapshot().records.single { it.id==original.id } }.phase)
        assertTrue(a.session.attachments.isEmpty());assertEquals("new draft preserved",a.session.text);assertNull(a.session.error)
    }
    @Test fun oldFailedFileRetryAndDiscardCannotHideOrEndTheNewOriginalWait() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        val uri=file("old-failed.txt","old result".toByteArray());lateinit var old: NativePhotoRequest
        main { old=request();assertTrue(picker.begin(old));recovery.launch(old,text);a.session.clearAttachmentDraft(old);a.session.text="new draft";registry.dispatchResult(registry.code,listOf(uri)) }
        ui.waitUntil(20_000) { !a.session.importing&&recovery.failures.any { it.id==old.id } }
        lateinit var current: NativePhotoRequest
        main { current=request();assertTrue(picker.begin(current));recovery.launch(current,text);recovery.retryRestore() }
        ui.waitUntil(15_000) { !recovery.loading&&recovery.restoredWait };val newCode=registry.code
        ui.onNodeWithTag("document-recovery-end-wait").performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("document-recovery-retry-files").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitUntil(20_000) { !recovery.isProcessing(old.id)&&recovery.failures.any { it.id==old.id } }
        assertTrue(recovery.restoredWait);assertSame(current,picker.pending)
        ui.onNodeWithTag("document-recovery-end-wait").performScrollTo().assertIsDisplayed().assertIsEnabled()
        a.owned.nodeShot(ui.onNodeWithTag("document-recovery-notice"),"old-file-and-new-original-wait")
        ui.onNodeWithTag("document-recovery-discard").performScrollTo().performClick();ui.waitUntil(15_000) { recovery.failures.isEmpty() }
        assertTrue(recovery.restoredWait);assertSame(current,picker.pending);assertEquals("new draft",a.session.text)
        main { registry.dispatchResult(newCode,emptyList<Uri>()) };ui.waitUntil(15_000) { picker.pending==null }
        assertFalse(recovery.restoredWait);assertTrue(a.session.attachments.isEmpty());assertEquals(2,registry.launches)
    }
    @Test fun excessiveSystemSelectionShowsTheAttachmentLimitWithoutReadingOrPersistingAnyProviderFile() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        lateinit var original: NativePhotoRequest
        val prior=ui.activity.contentResolver.persistedUriPermissions.map { it.uri.toString() to it.isReadPermission }
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text);registry.dispatchResult(registry.code,(1..6).map { Uri.parse("content://never-read/document/$it") }) }
        ui.waitUntil(15_000) { picker.pending==null&&!a.session.importing&&recovery.failures.any { it.id==original.id } }
        assertEquals(text.tr("最多添加 5 个附件，请先移除部分附件。"),a.session.error)
        val payloads=NativeAttachmentInboxStore(a.owned.storage.child(ui.activity,"document-results").child(ui.activity,"payloads"))
        assertTrue(runBlocking { payloads.snapshot().records.isEmpty() });assertTrue(a.session.attachments.isEmpty())
        assertEquals(prior,ui.activity.contentResolver.persistedUriPermissions.map { it.uri.toString() to it.isReadPermission })
        ui.onNodeWithTag("dismiss-error").performClick();ui.onNodeWithTag("document-recovery-retry-files").assertDoesNotExist()
        ui.onNodeWithTag("document-recovery-discard").performScrollTo().performClick();ui.waitUntil(15_000) { recovery.failures.isEmpty() }
        assertEquals(1,registry.launches);assertEquals("最近图片原稿 100% %@ 😀",a.session.text)
    }
    @Test fun originalDraftRemovalWaitsForItsDocumentTransactionAndBecomesAvailableAfterReceipt() {
        main { a.session.addAttachment("existing.txt","text/plain","existing original".toByteArray()) }
        val existing=a.session.attachments.single().manifest.id;val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        lateinit var original: NativePhotoRequest
        main { original=request();assertTrue(picker.begin(original));recovery.launch(original,text) }
        val store=a.owned.storage.child(ui.activity,"document-results");val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val lock=Thread { store.operationTransaction { entered.countDown();check(release.await(15,java.util.concurrent.TimeUnit.SECONDS)) } }.apply { start() }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));val uri=file("selected.txt","selected original".toByteArray())
            main {
                registry.dispatchResult(registry.code,listOf(uri));assertTrue(a.session.importing);assertFalse(a.session.canRemoveDraftAttachment)
                a.session.removeAttachment(existing);assertEquals(listOf(existing),a.session.attachments.map { it.manifest.id })
            }
        }finally { release.countDown();lock.join(5_000);assertFalse(lock.isAlive) }
        ui.waitUntil(20_000) { picker.pending==null&&!a.session.importing&&a.session.attachments.size==2 }
        assertTrue(a.session.canRemoveDraftAttachment);main { a.session.removeAttachment(existing) }
        assertEquals(1,a.session.attachments.size);assertEquals("selected.txt",a.session.attachments.single().manifest.name)
    }
    @Test fun oldDocumentGenerationCannotKeepTheNewDraftBusyOrPreventItsExplicitRemoval() {
        val picker=a.session.documentPicker!!;val recovery=picker.recovery!!;val registry=registry(recovery)
        lateinit var old: NativePhotoRequest
        main {
            old=request();assertTrue(picker.begin(old));recovery.launch(old,text);a.session.clearAttachmentDraft(old);a.session.text="new generation"
            a.session.addAttachment("new-generation.txt","text/plain","new independent data".toByteArray())
        }
        val existing=a.session.attachments.single().manifest.id;val generation=main { a.session.attachmentImportDestination().generation };assertNotEquals(old.destination.generation,generation)
        val store=a.owned.storage.child(ui.activity,"document-results");val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val lock=Thread { store.operationTransaction { entered.countDown();check(release.await(15,java.util.concurrent.TimeUnit.SECONDS)) } }.apply { start() }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));val uri=file("old-generation.txt","old selected bytes".toByteArray())
            main { registry.dispatchResult(registry.code,listOf(uri));assertFalse(a.session.importing);assertTrue(a.session.canRemoveDraftAttachment);a.session.removeAttachment(existing);assertTrue(a.session.attachments.isEmpty()) }
        }finally { release.countDown();lock.join(5_000);assertFalse(lock.isAlive) }
        ui.waitUntil(20_000) { !recovery.isProcessing(old.id)&&recovery.failures.any { it.id==old.id } }
        assertEquals(generation,main { a.session.attachmentImportDestination().generation });assertEquals("new generation",a.session.text);assertTrue(a.session.attachments.isEmpty());assertNull(a.session.error)
    }
}
