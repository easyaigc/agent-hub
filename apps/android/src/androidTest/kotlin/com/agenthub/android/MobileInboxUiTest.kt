package com.agenthub.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full native App with owned Session/storage and real file provider; Host transport remains synthetic. */
class MobileInboxUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val paths=mutableListOf<File>()
    private var server: CommandFixture?=null
    private var release: CountDownLatch?=null
    private var restored: MobileSession?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"inbox-ui");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light") }
    }
    @After fun finish() {
        release?.countDown()
        try { ui.runOnIdle { session.leaveForeground();restored?.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { server?.close();paths.sortedByDescending { it.path.length }.forEach { assertTrue(!it.exists()||it.delete()) } }
    }
    private fun connect(configure: Boolean=true) {
        val target=JSONObject().put("agentId","codex").put("hostId","inbox-ui-host").put("workspaceId","inbox-workspace").put("runtimeId","inbox-runtime")
        val fixture=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","inbox-ui-host").put("name","原电脑 100% %@").put("connectionMode","desktop-runtime")
                .put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1")))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","inbox-ui-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","inbox-ui-host").put("threadId",android.net.Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("原电脑 100% %@",fixture.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="原草稿 100% %@"
            if(configure)session.configureExternalInbox(activity) }
        ui.waitUntil(10_000) { !session.loading&&!session.historyLoading&&session.runtime!=null };assertTrue(session.canCompose)
    }
    private fun source(name: String="共享原文 100% %@.txt",text: String="原字节 100% %@\r\n"): Pair<Intent,ByteArray> {
        val folder=File(activity.cacheDir,"resources/inbox-ui-"+UUID.randomUUID()).apply { mkdirs() };paths+=folder
        val bytes=text.toByteArray();val file=File(folder,name).apply { writeBytes(bytes) };paths+=file
        val uri=FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
        return Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,uri).setType("text/plain") to bytes
    }
    private fun receive(inbox: NativeExternalAttachments,payload: Pair<Intent,ByteArray>): String {
        val id=UUID.randomUUID().toString();main { assertTrue(inbox.receive(payload.first,id)) };return id
    }
    private fun sheet() {
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("sheet-external-attachments").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-title").assertTextEquals("Files from other apps")
    }
    @Test fun laterReopensExactFilesThenAddsOnlyToTheExplicitlyChosenOriginalDraft() {
        connect();val inbox=session.externalInbox!!;val source=source();receive(inbox,source);sheet()
        val original=inbox.presentation!!;val ids=original.batches.single().readyFiles.map { it.id }
        ui.onNodeWithText("共享原文 100% %@.txt").assertExists();assertTrue(session.attachments.isEmpty())
        owned.dialogShot("received-original-files")
        ui.onNodeWithTag("external-attachment-later").performClick();ui.waitUntil(5_000) { inbox.presentation==null }
        main { session.selectThread(session.threads.single { it.id=="second" });session.text="第二草稿 100% %@" }
        ui.waitUntil(10_000) { !session.historyLoading }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("open-external-attachments").performClick();sheet()
        assertEquals(ids,inbox.presentation!!.batches.single().readyFiles.map { it.id })
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(15_000) { !inbox.adding&&inbox.pending.isEmpty()&&session.attachments.size==1 }
        assertEquals(ids,session.attachments.map { it.manifest.id });assertEquals("第二草稿 100% %@",session.text)
        assertArrayEquals(source.second,runBlocking { NativeDraftFiles(owned.storage).bytes(session.attachments.single().manifest) })
        main { session.selectThread(session.threads.single { it.id=="first" }) };assertTrue(session.attachments.isEmpty());assertEquals("原草稿 100% %@",session.text)
        assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun newArrivalDoesNotChangePresentedSnapshotAndDiscardCannotConsumeIt() {
        connect();val inbox=session.externalInbox!!;val first=receive(inbox,source("first.txt"));sheet();val presented=inbox.presentation!!
        val second=receive(inbox,source("second.txt"));ui.waitUntil(15_000) { !inbox.receiving&&inbox.pending.size==2 }
        assertEquals(presented,inbox.presentation);ui.onNodeWithText("first.txt").assertExists();ui.onNodeWithText("second.txt").assertDoesNotExist()
        ui.onNodeWithTag("external-attachment-discard").performScrollTo().performClick()
        ui.waitUntil(15_000) { inbox.presentation?.batches?.map { it.id }==listOf(second)&&!inbox.adding }
        ui.onNodeWithText("second.txt").assertExists();ui.onNodeWithText("first.txt").assertDoesNotExist()
        val state=runBlocking { NativeAttachmentInboxStore(owned.storage.child(activity,"external-inbox")).snapshot() }
        assertEquals("consumed",state.records.single { it.id==first }.phase);assertEquals("pending",state.records.single { it.id==second }.phase)
        assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty());ui.onNodeWithTag("external-attachment-later").performClick()
    }
    @Test fun batchCannotBeConsumedBeforeQueuedExactDraftMetadataIsDurable() {
        connect();val inbox=session.externalInbox!!;val id=receive(inbox,source());sheet()
        val started=CountDownLatch(1);val gate=CountDownLatch(1);release=gate
        owned.storage.enqueueSnapshot("owned-inbox-confirm-gate",{ started.countDown();check(gate.await(20,TimeUnit.SECONDS)) },{ throw AssertionError(it) })
        assertTrue(started.await(5,TimeUnit.SECONDS))
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.attachments.size==1&&inbox.adding&&!session.importing }
        val file=session.attachments.single();val child=NativeAttachmentInboxStore(owned.storage.child(activity,"external-inbox"))
        assertEquals("pending",runBlocking { child.snapshot() }.records.single { it.id==id }.phase)
        assertFalse(owned.storage.metadata()?.toString()?.contains(file.manifest.id)==true)
        ui.onNodeWithTag("external-attachment-later").assertIsNotEnabled();ui.onNodeWithTag("external-attachment-discard").assertIsNotEnabled()
        owned.dialogShot("waiting-original-durable-metadata")
        gate.countDown();ui.waitUntil(15_000) { !inbox.adding&&inbox.pending.isEmpty() }
        assertTrue(owned.storage.metadata()!!.toString().contains(file.manifest.id));assertEquals("consumed",runBlocking { child.snapshot() }.records.single { it.id==id }.phase)
        assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun retiredSurfaceAndBackgroundActionsRejectBeforeChangingTheInbox() {
        connect(configure=false)
        var foreground=true;val runtime=NativeExternalAttachments(activity,owned.storage.child(activity,"external-inbox")) { foreground }
        val source=source();receive(runtime,source);ui.waitUntil(10_000) { !runtime.loading&&!runtime.receiving&&runtime.pending.size==1 }
        assertEquals("pending",runtime.pending.single().phase)
        main { runtime.claimSurface("original-owner");assertTrue(runtime.presentIfWanted("original-owner")) }
        val original=runtime.presentation!!;val store=NativeAttachmentInboxStore(owned.storage.child(activity,"external-inbox"));val before=runBlocking { store.snapshot() }
        main { runtime.claimSurface("new-owner");runtime.add(original,session,"original-owner");runtime.discard(original,"original-owner");runtime.retry(original,"original-owner");assertFalse(runtime.dismiss("original-owner")) }
        assertEquals(before,runBlocking { store.snapshot() });assertTrue(session.attachments.isEmpty());assertEquals(original,runtime.presentation)
        main { foreground=false;runtime.add(original,session,"new-owner");runtime.discard(original,"new-owner");assertFalse(runtime.dismiss("new-owner"));foreground=true;runtime.add(original,session,"new-owner") }
        try { ui.waitUntil(15_000) { !runtime.adding&&runtime.pending.isEmpty()&&session.attachments.size==1 } }
        finally {
            File(owned.folder,"owner-surface-state.json").writeText(JSONObject().put("adding",runtime.adding).put("pending",JSONArray(runtime.pending.map { it.phase }))
                .put("attachments",session.attachments.size).put("canCompose",session.canCompose).put("importing",session.importing).put("restoring",session.restoring)
                .put("presentedPhases",JSONArray(original.batches.map { it.phase })).put("failureKey",runtime.failureKey).put("failureType",runtime.lastFailure?.javaClass?.name).put("failureMessage",runtime.lastFailure?.message).put("sessionError",session.error).toString())
        }
        assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun warmReadRetryKeepsOriginalBatchAndSlotUntilUserExplicitlyAdds() {
        connect();val inbox=session.externalInbox!!;val payload=source("later.txt","原重试内容 100% %@");val file=paths.last();assertTrue(file.delete())
        val id=receive(inbox,payload);sheet();val before=inbox.presentation!!.batches.single();assertEquals("failed",before.phase)
        ui.onNodeWithTag("external-attachment-add").assertIsNotEnabled();assertTrue(session.attachments.isEmpty())
        file.writeBytes(payload.second);ui.onNodeWithTag("external-attachment-retry").performScrollTo().performClick()
        ui.waitUntil(15_000) { inbox.presentation?.batches?.singleOrNull()?.let { it.id==id&&it.phase=="pending" }==true }
        assertEquals(before.slots.map { it.id },inbox.presentation!!.batches.single().slots.map { it.id });assertTrue(session.attachments.isEmpty())
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(15_000) { !inbox.adding&&inbox.pending.isEmpty()&&session.attachments.size==1 }
        assertArrayEquals(payload.second,runBlocking { NativeDraftFiles(owned.storage).bytes(session.attachments.single().manifest) });assertEquals("原草稿 100% %@",session.text)
    }
    @Test fun missingOriginalPayloadRepairsAtFullCapacityWithoutAddingOrDuplicatingIds() {
        connect(configure=false);val payload=source();val store=NativeAttachmentInboxStore(owned.storage.child(activity,"external-inbox"))
        @Suppress("DEPRECATION") val uri=payload.first.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val batch=runBlocking { store.stage(UUID.randomUUID().toString(),listOf(uri),activity.contentResolver,maximum=10) }
        lateinit var destination: AttachmentImportDestination;main { destination=session.attachmentImportDestination().copy(maximum=10) }
        val bound=runBlocking { store.bind(batch,InboxBinding(destination.key,destination.generation)) }
        main { session.importAttachments(destination,bound.readyFiles.map { file -> { store.payload(bound,file) } })!!.await()
            repeat(9) { n -> session.addAttachment("original-$n.txt","text/plain",byteArrayOf(n.toByte()),destination.copy(requestId=UUID.randomUUID().toString())) } }
        assertEquals(10,session.attachments.size);val original=session.attachments.map { it.manifest };runBlocking { owned.storage.awaitQueuedWrites() }
        owned.storage.remove("draft-file:"+bound.readyFiles.single().id)
        main { session.configureExternalInbox(activity) };sheet();val inbox=session.externalInbox!!
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(15_000) { !inbox.adding&&inbox.pending.isEmpty() }
        assertEquals(original,session.attachments.map { it.manifest });assertEquals(10,session.attachments.size)
        assertArrayEquals(payload.second,runBlocking { NativeDraftFiles(owned.storage).bytes(bound.readyFiles.single()) });assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun originalPendingReceiptAfterSessionReconstructionSkipsAlreadyDurableFixedId() {
        connect(configure=false);val source=source();val child=owned.storage.child(activity,"external-inbox");val store=NativeAttachmentInboxStore(child)
        val uri=source.first.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val batch=runBlocking { store.stage(UUID.randomUUID().toString(),listOf(uri),activity.contentResolver) }
        lateinit var destination: AttachmentImportDestination
        main { destination=session.attachmentImportDestination() }
        val bound=runBlocking { store.bind(batch,InboxBinding(destination.key,destination.generation)) }
        main { session.importAttachments(destination,bound.readyFiles.map { file -> { store.payload(bound,file) } })!!.await() }
        assertTrue(runBlocking { session.confirmInboxFiles(destination,bound.readyFiles) })
        // The original draft is durable; its old inbox payload may already have been removed by a prior cleanup attempt.
        child.remove("draft-file:"+bound.readyFiles.single().id)
        main { session.leaveForeground();restored=MobileSession(owned.storage);restored!!.restore() }
        ui.waitUntil(20_000) { !restored!!.restoring&&!restored!!.historyLoading };assertFalse(restored!!.localRecoveryRequired)
        main { restored!!.configureExternalInbox(activity);activity.setContent { AgentHubApp(restored!!) } }
        sheet();val inbox=restored!!.externalInbox!!;assertEquals(bound.readyFiles.map { it.id },restored!!.attachments.map { it.manifest.id })
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(15_000) { !inbox.adding&&inbox.pending.isEmpty() }
        assertEquals(1,restored!!.attachments.size);assertArrayEquals(source.second,runBlocking { NativeDraftFiles(owned.storage).bytes(restored!!.attachments.single().manifest) })
        assertTrue(restored!!.operationRecords.isEmpty());assertEquals("原草稿 100% %@",restored!!.text)
    }
}
