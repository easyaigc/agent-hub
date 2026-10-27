package com.agenthub.android

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real encrypted/index/provider boundaries, synthetic local files; no model/Host execution. */
class MobileInboxStorageTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var child: NativeSessionStorage
    private val paths=mutableListOf<File>()
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"inbox-storage");owned.session.resetAcceptance();child=owned.storage.child(activity,"external-inbox") }
    }
    @After fun finish() {
        ui.runOnIdle { owned.session.leaveForeground();owned.verifyDefault() }
        paths.sortedByDescending { it.path.length }.forEach { assertTrue(!it.exists()||it.delete()) }
    }
    private fun source(name: String="原文件 100% %@.txt",text: String="原字节 100% %@\r\n"): Pair<File,Uri> {
        val folder=File(activity.cacheDir,"resources/inbox-"+UUID.randomUUID()).apply { mkdirs() };paths+=folder
        val file=File(folder,name).apply { writeText(text) };paths+=file
        return file to FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
    }
    private suspend fun rejected(block: suspend ()->Unit) {
        var failure=false;try { block() }catch(_: Exception) { failure=true };assertTrue("Exact local boundary must reject",failure)
    }
    @Test fun restartRetainsImmutableSlotsNamesBytesAndSingleOriginalBinding()=runBlocking {
        val one=source();val two=source("第二.txt","第二原文");val id=UUID.randomUUID().toString();val first=NativeAttachmentInboxStore(child)
        val batch=first.stage(id,listOf(one.second,two.second),activity.contentResolver);assertEquals("pending",batch.phase)
        val restored=NativeAttachmentInboxStore(owned.storage.child(activity,"external-inbox"));val same=restored.snapshot().records.single();assertEquals(batch,same)
        val a=InboxBinding("original-full-target","original-generation");val bound=restored.bind(same,a)
        rejected { restored.bind(same,InboxBinding("another-target","another-generation")) }
        for((index,file)in bound.readyFiles.withIndex()) {
            val payload=restored.payload(bound,file);assertEquals(file.id,payload.fixedId)
            assertArrayEquals((if(index==0)one.first else two.first).readBytes(),payload.bytes)
        }
        assertEquals(a,restored.snapshot().records.single().binding);assertNull(owned.storage.read("external-inbox-index-v1"))
    }
    @Test fun partialStageResumesOnlyOriginalUriIntentAndFixedFileIdentity()=runBlocking {
        val first=source();val later=source("later.txt","later original");assertTrue(later.first.delete())
        val id=UUID.randomUUID().toString();val uris=listOf(first.second,later.second);val store=NativeAttachmentInboxStore(child)
        rejected { store.stage(id,uris,activity.contentResolver) }
        val partial=store.snapshot().records.single();assertEquals("failed",partial.phase);assertNotNull(partial.slots[0].file);assertNull(partial.slots[1].file)
        rejected { store.stage(id,listOf(later.second,first.second),activity.contentResolver) }
        later.first.writeText("later original")
        val resumed=NativeAttachmentInboxStore(child).stage(id,uris,activity.contentResolver)
        assertEquals("pending",resumed.phase);assertEquals(partial.slots.map { it.id },resumed.slots.map { it.id });assertEquals(partial.slots[0].file,resumed.slots[0].file)
    }
    @Test fun consumedReceiptNeverReopensSourceAndCleanupFailureNeverResurrectsInput()=runBlocking {
        val source=source();val id=UUID.randomUUID().toString();val store=NativeAttachmentInboxStore(child)
        val batch=store.stage(id,listOf(source.second),activity.contentResolver);val binding=InboxBinding("target","generation");val bound=store.bind(batch,binding)
        // Corrupt only this owned payload to force garbage cleanup to retain its manifest.
        child.write("draft-file:"+bound.readyFiles.single().id,"{}");store.consume(bound,binding)
        val receipt=store.snapshot().records.single();assertEquals("consumed",receipt.phase);assertTrue(receipt.slots.isNotEmpty())
        assertTrue(source.first.delete());assertEquals("consumed",store.stage(id,listOf(source.second),activity.contentResolver).phase)
        assertEquals(receipt,store.snapshot().records.single());rejected { store.payload(bound,bound.readyFiles.single()) }
    }
    @Test fun fixedFileLeaseSerializesPrepareCleanupAndPreservesExistingBytes()=runBlocking {
        val files=NativeDraftFiles(owned.storage);val id=UUID.randomUUID().toString();val bytes="原意图附件 100% %@".toByteArray()
        val first=files.prepare("original.txt","text/plain",bytes,id)
        val second=async(Dispatchers.IO) { files.prepare("original.txt","text/plain",bytes,id) }
        delay(100);assertFalse(second.isCompleted)
        withContext(Dispatchers.IO) { files.discardPrepared(first.file) };first.close()
        val next=withTimeout(10_000) { second.await() };assertTrue(next.disposable);next.close()
        val existing=files.prepare("original.txt","text/plain",bytes,id);assertFalse(existing.disposable);existing.close()
        rejected { files.prepare("changed.txt","text/plain","changed".toByteArray(),id) }
        assertArrayEquals(bytes,files.bytes(next.file.manifest))
        val held=files.prepare("original.txt","text/plain",bytes,id)
        val cancelled=async(Dispatchers.IO) { files.prepare("original.txt","text/plain",bytes,id) };delay(50);cancelled.cancelAndJoin();held.close()
        val after=withTimeout(10_000) { files.prepare("original.txt","text/plain",bytes,id) };try { assertFalse(after.disposable) }finally { after.close() }
    }
    @Test fun malformedEncryptedIndexIsPreservedAndNeverReset()=runBlocking {
        val raw="{\"version\":77,\"revision\":5,\"records\":[]}";child.write("external-inbox-index-v1",raw)
        rejected { NativeAttachmentInboxStore(child).snapshot() };assertEquals(raw,child.read("external-inbox-index-v1"))
        val source=source();rejected { NativeAttachmentInboxStore(child).stage(UUID.randomUUID().toString(),listOf(source.second),activity.contentResolver) }
        assertEquals(raw,child.read("external-inbox-index-v1"));assertTrue(owned.session.operationRecords.isEmpty())
    }
    @Test fun onlyNamedContentFileIntentsAreAcceptedAndOtherLinksKeepTheirRoute() {
        val uri=Uri.parse("content://controlled.external/source.txt")
        assertEquals(listOf(uri),NativeFileIntake.sharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,uri)))
        assertEquals(listOf(uri),NativeFileIntake.sharedFiles(Intent(Intent.ACTION_VIEW,uri)))
        assertEquals(listOf(uri,uri),NativeFileIntake.sharedFiles(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(uri,uri))))
        assertNull(NativeFileIntake.sharedFiles(Intent(Intent.ACTION_VIEW,Uri.parse("codex-host://pair?local=1"))))
        assertNull(NativeFileIntake.sharedFiles(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.invalid/file.txt"))))
        for(intent in listOf(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,"https://example.invalid/file.txt"),Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,Uri.parse("file:///ungranted/path")),Intent(Intent.ACTION_SEND).apply { clipData=ClipData.newPlainText("file","ordinary text") })) {
            var rejected=false;try { NativeFileIntake.sharedFiles(intent) }catch(_: NativeFileIntakeFailure) { rejected=true };assertTrue(rejected)
        }
    }
}
