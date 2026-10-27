package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Each named phase is a separate real instrumentation process; the controller verifies distinct PIDs. */
class MobileInboxColdProcessTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private val config by lazy { JSONObject(File(context.filesDir,"mobile-inbox-cold-config.json").readText()) }
    private val nonce get()=config.getString("nonce").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    private val root get()=File(context.filesDir,"acceptance/P7-inbox-cold/$nonce").apply { mkdirs() }
    private val phase get()=InstrumentationRegistry.getArguments().getString("coldPhase")!!.also { require(it.matches(Regex("[a-z-]+"))) }
    private val folder get()=File(root,phase).apply { mkdirs() }
    private val storage get()=NativeSessionStorage(context,"P7-inbox-cold-$nonce")
    private var activity: MainActivity?=null
    private var session: MobileSession?=null
    private var prior: MainActivity.AcceptanceSessionState?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun baseline()=File(root,"default-before.json")
    private fun draftSnapshot(): JSONObject {
        val saved=NativeSessionStorage(context).metadata()
        return JSONObject().apply { for(key in listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations"))put(key,saved?.opt(key)?.toString() ?: JSONObject.NULL) }
    }
    private fun preserveDefault() {
        val before=JSONObject(baseline().readText());val after=draftSnapshot()
        for(key in listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations"))assertEquals("Cold phase preserves default $key",before.get(key),after.get(key))
        File(folder,"default-storage-proof.json").writeText(JSONObject().put("draftsPreserved",true).put("draftFilesPreserved",true).put("draftVersionsPreserved",true).put("attachmentImportGenerationsPreserved",true).put("pid",Process.myPid()).toString())
    }
    @Before fun requireScope() { require(context.packageName=="com.easyai.agenthub.android.acceptance");nonce;phase }
    @After fun finish() {
        try { main { session?.leaveForeground();prior?.let { activity!!.restoreSessionForAcceptance(it) } };if(baseline().exists())preserveDefault() }
        finally { File(folder,"process.json").writeText(JSONObject().put("pid",Process.myPid()).put("uid",Process.myUid()).put("phase",phase).toString()) }
    }
    private fun bind() {
        val descriptor=i.uiAutomation.executeShellCommand("am start -W -n com.easyai.agenthub.android.acceptance/com.agenthub.android.MainActivity")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        ui.waitUntil(20_000) { i.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };activity!=null }
        ui.waitUntil(30_000) { !activity!!.session.restoring }
    }
    private fun showOwned(value: MobileSession) {
        bind();main { prior=activity!!.adoptSessionForAcceptance(value) }
    }
    private fun restoreOwned(): MobileSession {
        val value=MobileSession(storage);session=value;main { value.restore() }
        ui.waitUntil(30_000) { !value.restoring&&!value.historyLoading&&!value.loading&&value.canCompose }
        assertFalse(value.localRecoveryRequired);return value
    }
    private fun picture(name: String) {
        ui.waitForIdle();i.waitForIdleSync();assertEquals(context.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=checkNotNull(i.uiAutomation.takeScreenshot());try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
    }
    @Test fun captureDefaultBeforeIndependentColdShare() {
        check(!baseline().exists());baseline().writeText(draftSnapshot().toString())
        val store=NativeAttachmentInboxStore(NativeSessionStorage(context).child(context,"external-inbox"))
        val before=runBlocking { store.snapshot() };File(root,"default-inbox-before.json").writeText(JSONObject().apply { for(batch in before.records)put(batch.id,sha(batch.toString().toByteArray())) }.toString())
    }
    @Test fun verifyColdSystemInboxBytesAndCleanOnlyThisIndependentBatch() {
        val store=NativeAttachmentInboxStore(NativeSessionStorage(context).child(context,"external-inbox"))
        val before=JSONObject(File(root,"default-inbox-before.json").readText());val snapshot=runBlocking { store.snapshot() }
        for(id in before.keys())assertEquals(before.getString(id),sha(snapshot.records.single { it.id==id }.toString().toByteArray()))
        val batch=snapshot.records.single { it.readyFiles.any { file -> file.name.startsWith("inbox-source-$nonce-") } }
        assertEquals("pending",batch.phase);assertNull(batch.binding);assertEquals(2,batch.readyFiles.size)
        val child=NativeSessionStorage(context).child(context,"external-inbox")
        for((n,file)in batch.readyFiles.withIndex()) {
            assertEquals("inbox-source-$nonce-${n+1}.txt",file.name)
            val expected="ANDROID_EXTERNAL_SOURCE_${nonce}_${n+1}\n原文件 100% %@\n".toByteArray()
            assertArrayEquals(expected,runBlocking { NativeDraftFiles(child).bytes(file) });assertEquals(sha(expected),file.sha256)
        }
        runBlocking { store.discard(batch.id) };val after=runBlocking { store.snapshot() }
        assertTrue(after.records.single { it.id==batch.id }.let { it.phase=="consumed"&&it.slots.isEmpty() })
        for(id in before.keys())assertEquals(before.getString(id),sha(after.records.single { it.id==id }.toString().toByteArray()))
        File(folder,"cold-system-bytes.json").writeText(JSONObject().put("decryptedNamesOrderAndBytesMatched",true).put("unboundOriginalInbox",true).put("onlyOwnBatchDiscarded",true).put("newModelInputs",0).toString())
    }
    @Test fun preparePartialOwnedDraftBeforeRealProcessDeath() {
        val value=MobileSession(storage);session=value;main { value.resetAcceptance();value.changeLanguage("en");value.changeAppearance("light");value.connect("Owned cold target",config.getString("endpoint"),"") }
        ui.waitUntil(20_000) { value.threads.isNotEmpty()&&!value.loading }
        main { value.selectThread(value.threads.single());value.text="跨进程原草稿 100% %@" };ui.waitUntil(15_000) { value.canCompose&&!value.historyLoading }
        val store=NativeAttachmentInboxStore(storage.child(context,"external-inbox"));val parent=File(context.cacheDir,"resources/inbox-cold-$nonce").apply { mkdirs() }
        val files=(1..2).map { n -> File(parent,"cold-owned-$n.txt").apply { writeText("COLD_OWNED_${nonce}_$n\n原字节 100% %@\n") } }
        try {
            val uris=files.map { FileProvider.getUriForFile(context,context.packageName+".resources",it) }
            val batch=runBlocking { store.stage(java.util.UUID.randomUUID().toString(),uris,context.contentResolver) }
            lateinit var destination: AttachmentImportDestination;main { destination=value.attachmentImportDestination() }
            val bound=runBlocking { store.bind(batch,InboxBinding(destination.key,destination.generation)) }
            main { value.importAttachments(destination,listOf({ store.payload(bound,bound.readyFiles.first()) }))!!.await() }
            assertTrue(runBlocking { value.confirmInboxFiles(destination,listOf(bound.readyFiles.first())) });assertEquals(1,value.attachments.size)
            File(root,"owned-before.json").writeText(JSONObject().put("pid",Process.myPid()).put("batchId",batch.id).put("targetKey",value.targetKey).put("generation",destination.generation)
                .put("text",value.text).put("settings",value.generationSettings.append(JSONObject())).put("files",JSONArray(bound.readyFiles.map { it.body() })).toString())
            assertTrue(value.operationRecords.isEmpty())
        }finally { files.forEach { assertTrue(it.delete()) };assertTrue(parent.delete()) }
    }
    @Test fun completeOnlyMissingFixedIdAfterActualProcessRestart() {
        val original=JSONObject(File(root,"owned-before.json").readText());assertNotEquals(original.getInt("pid"),Process.myPid())
        val value=restoreOwned();assertEquals(original.getString("targetKey"),value.targetKey);assertEquals(original.getString("text"),value.text);assertEquals(1,value.attachments.size)
        assertEquals(original.getJSONArray("files").getJSONObject(0).toString(),value.attachments.single().manifest.body().toString())
        assertEquals(original.getJSONObject("settings").toString(),value.generationSettings.append(JSONObject()).toString())
        showOwned(value);ui.waitUntil(15_000) { value.externalInbox?.presentation!=null }
        val before=value.externalInbox!!.presentation!!.batches.single();assertEquals(original.getString("batchId"),before.id);assertEquals(original.getString("generation"),before.binding!!.generation)
        picture("cold-partial-original-files");ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(20_000) { !value.externalInbox!!.adding&&value.externalInbox!!.pending.isEmpty()&&value.attachments.size==2 }
        assertEquals(original.getJSONArray("files").toString(),JSONArray(value.attachments.map { it.manifest.body() }).toString());assertEquals(original.getString("text"),value.text);assertTrue(value.operationRecords.isEmpty())
        File(root,"owned-completed.json").writeText(JSONObject().put("pid",Process.myPid()).put("ids",JSONArray(value.attachments.map { it.manifest.id })).toString());picture("cold-complete-original-draft")
    }
    @Test fun restoreConsumedInboxAndCompleteDraftAfterAnotherProcessDeath() {
        val original=JSONObject(File(root,"owned-before.json").readText());val completed=JSONObject(File(root,"owned-completed.json").readText());assertNotEquals(original.getInt("pid"),Process.myPid());assertNotEquals(completed.getInt("pid"),Process.myPid())
        val value=restoreOwned();showOwned(value);ui.waitUntil(15_000) { value.externalInbox?.loading==false }
        assertTrue(value.externalInbox!!.pending.isEmpty());assertNull(value.externalInbox!!.presentation);assertEquals(original.getString("targetKey"),value.targetKey);assertEquals(original.getString("text"),value.text)
        assertEquals(original.getJSONArray("files").toString(),JSONArray(value.attachments.map { it.manifest.body() }).toString());assertEquals(completed.getJSONArray("ids").toString(),JSONArray(value.attachments.map { it.manifest.id }).toString())
        for((n,file)in value.attachments.withIndex())assertArrayEquals("COLD_OWNED_${nonce}_${n+1}\n原字节 100% %@\n".toByteArray(),runBlocking { NativeDraftFiles(storage).bytes(file.manifest) })
        val receipt=runBlocking { NativeAttachmentInboxStore(storage.child(context,"external-inbox")).snapshot() }.records.single { it.id==original.getString("batchId") }
        assertEquals("consumed",receipt.phase);assertTrue(receipt.slots.isEmpty());assertTrue(value.operationRecords.isEmpty());picture("cold-consumed-original-draft")
    }
}
