package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real native draft/file storage with held local providers; transport is synthetic, no model. */
class MobileAttachmentImportOwnershipTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val scope=TargetScope("codex",null,null,"local-import-fixture","local-workspace",UUID.randomUUID().toString())
    private val capabilities=listOf("reply","attachments:upload","models:read","generation-settings","commands:target-bound:v1")
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private val jobs=mutableListOf<Job>()
    private val otherSessions=mutableListOf<MobileSession>()
    private val receipts=mutableMapOf<String,DeliveryReceipt>()
    private var writes=0
    private var expectedWrites=0
    private lateinit var reader: HostReads
    private lateinit var commands: CodexCommands
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Local import fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(capabilities)).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("import-a","import-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
                .put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope)).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                    .put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"low",listOf("low"),"standard",listOf("standard")))
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
                assertEquals(scope.hostId,client.id);assertEquals("import-a",intent.threadId);assertTrue(intent.attachmentIds.isEmpty())
                writes++
                return DeliveryReceipt(client.id,intent.threadId,intent.operationId,"completed","fixture-completed",null,null,intent.text,emptyList()).also { receipts[intent.operationId]=it }
            }
            override suspend fun receipt(client: MobileClient,threadId: String,operationId: String)=receipts.getValue(operationId)
        }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"local-import");session.resetAcceptance(reader,commands) }
        main { session.connect("Local import fixture","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading&&session.threads.size==2&&session.models.isNotEmpty() }
        select("import-a");main { allowSyntheticRegressionSharing(session) }
    }
    private fun select(id: String) {
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose&&session.selectedThread==id }
    }
    private fun gate()=CompletableDeferred<Unit>().also { gates.add(it) }
    private fun track(job: Deferred<Unit>?)=checkNotNull(job).also { jobs.add(it) }
    private fun input(name: String)=LocalAttachmentInput(name,"text/plain",("Exact local bytes:"+name).toByteArray())
    private fun shot(name: String)=owned.nodeShot(ui.onNodeWithTag("composer"),name)
    @After fun finish() {
        if(!::owned.isInitialized)return
        main { gates.forEach { it.complete(Unit) };jobs.forEach { it.cancel() };jobs.joinAll();otherSessions.forEach { it.leaveForeground() };session.leaveForeground();owned.verifyDefault() }
        assertEquals(expectedWrites,writes)
    }
    @Test fun lateProviderCommitsOnlyToItsValidOriginalDraft() {
        ui.onNodeWithTag("message-input").performTextReplacement("Original A draft")
        val original=main { session.attachmentImportDestination() };val gate=gate();lateinit var job: Deferred<Unit>
        ui.runOnIdle { job=track(session.importAttachments(original,listOf({ gate.await();input("a-origin.txt") }))) }
        ui.waitUntil(5_000) { session.importing };select("import-b")
        ui.onNodeWithTag("message-input").performTextReplacement("Independent B draft")
        assertFalse(session.importing);assertEquals("send",session.decision.action);assertTrue(session.decision.enabled);shot("other-draft-ready-during-original-import")
        main { gate.complete(Unit);job.await() };assertEquals("Independent B draft",session.text);assertTrue(session.attachments.isEmpty())
        select("import-a");assertEquals("Original A draft",session.text);assertEquals(listOf("a-origin.txt"),session.attachments.map { it.manifest.name })
        val file=session.attachments.single();assertArrayEquals(input("a-origin.txt").bytes,main { NativeDraftFiles(owned.storage).bytes(file.manifest) })
        shot("late-file-visible-only-on-original-draft");assertEquals(0,writes)
    }
    @Test fun navigationRoundTripAndDuplicateCallbackCannotImportTwice() {
        val original=main { session.attachmentImportDestination() };val gate=gate();var calls=0;lateinit var job: Deferred<Unit>
        val loader: suspend ()->LocalAttachmentInput={ calls++;gate.await();input("once.txt") }
        ui.runOnIdle { job=track(session.importAttachments(original,listOf(loader))) };select("import-b");select("import-a")
        ui.runOnIdle { assertNull(session.importAttachments(original,listOf(loader))) }
        main { gate.complete(Unit);job.await() }
        ui.runOnIdle { assertNull(session.importAttachments(original,listOf(loader))) }
        assertEquals(1,calls);assertEquals(listOf("once.txt"),session.attachments.map { it.manifest.name });shot("one-original-provider-result")
    }
    @Test fun ordinaryEditsKeepTheirTextWhenTheOriginalFileArrives() {
        val original=main { session.attachmentImportDestination() };val gate=gate();lateinit var job: Deferred<Unit>
        ui.runOnIdle { job=track(session.importAttachments(original,listOf({ gate.await();input("edited.txt") }))) }
        ui.onNodeWithTag("message-input").performTextReplacement("Newer text stays verbatim")
        main { gate.complete(Unit);job.await() }
        assertEquals("Newer text stays verbatim",session.text);assertEquals(listOf("edited.txt"),session.attachments.map { it.manifest.name });shot("edited-text-and-original-file")
    }
    @Test fun acceptedClearRejectsAnOlderPickerGenerationBeforeReadingIt() {
        val original=main { session.attachmentImportDestination() };ui.onNodeWithTag("message-input").performTextReplacement("One synthetic transport input")
        expectedWrites=1;ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(15_000) { writes==1&&session.text.isEmpty()&&session.transferStatus==null } }
        catch(failure: Throwable) {
            File(owned.folder,"accepted-clear-failure.json").writeText(JSONObject().put("error",session.error)
                .put("text",session.text).put("writes",writes).put("decision",session.decision.toString())
                .put("sharingRequest",session.aiSharingRequest?.toString()).put("transferStatus",session.transferStatus)
                .put("records",JSONArray(session.operationRecords.map { it.body() })).toString())
            shot("accepted-clear-failure");throw failure
        }
        ui.onNodeWithTag("message-input").performTextReplacement("New draft after accepted clear")
        var calls=0;ui.runOnIdle { assertNull(session.importAttachments(original,listOf({ calls++;input("stale.txt") }))) }
        assertEquals(0,calls);assertTrue(session.attachments.isEmpty());assertEquals("New draft after accepted clear",session.text);shot("accepted-clear-rejects-old-picker")
    }
    @Test fun completeBatchCapacityIsReservedBeforeAnyRejectedProviderReads() {
        val gate=gate();val first=main { session.attachmentImportDestination() };lateinit var a: Deferred<Unit>;lateinit var b: Deferred<Unit>
        ui.runOnIdle { a=track(session.importAttachments(first,(0..7).map { n -> suspend { gate.await();input("first-$n.txt") } })) }
        var rejectedReads=0
        ui.runOnIdle { assertNull(session.importAttachments(session.attachmentImportDestination(),(0..2).map { suspend { rejectedReads++;input("never.txt") } })) }
        assertEquals(0,rejectedReads)
        ui.runOnIdle { session.error=null;b=track(session.importAttachments(session.attachmentImportDestination(),(0..1).map { n -> suspend { input("second-$n.txt") } })) }
        main { gate.complete(Unit);a.await();b.await() }
        assertEquals(10,session.attachments.size);assertEquals(10,session.attachments.map { it.manifest.id }.distinct().size);assertFalse(session.importing);shot("ten-reserved-files-without-overflow")
    }
    @Test fun lateFailureCannotOverwriteAnErrorAfterNavigationRoundTrip() {
        val original=main { session.attachmentImportDestination() };val gate=gate();lateinit var job: Deferred<Unit>
        ui.runOnIdle { job=track(session.importAttachments(original,listOf({ gate.await();throw IllegalStateException("Old provider failure") }))) }
        select("import-b");select("import-a")
        ui.runOnIdle { session.text="Returned newer draft";session.error="Newer independent error" }
        main { gate.complete(Unit);assertTrue(runCatching { job.await() }.isFailure) }
        assertEquals("Newer independent error",session.error);assertEquals("Returned newer draft",session.text);assertTrue(session.attachments.isEmpty());assertFalse(session.importing)
        ui.runOnIdle { session.error=null };shot("old-provider-error-fenced")
    }
    @Test fun oneProviderFailureRetainsGoodFilesAndContinuesTheOriginalBatch() {
        ui.onNodeWithTag("message-input").performTextReplacement("Original batch text")
        val destination=main { session.attachmentImportDestination() }
        val job=main { track(session.importAttachments(destination,listOf(
            { input("before-good.txt") },{ throw IllegalStateException("One unavailable provider") },{ input("after-good.txt") }
        ))) }
        main { assertTrue(runCatching { job.await() }.isFailure) }
        assertEquals("Original batch text",session.text);assertEquals("One unavailable provider",session.error)
        assertEquals(listOf("before-good.txt","after-good.txt"),session.attachments.map { it.manifest.name })
        for(file in session.attachments)assertArrayEquals(input(file.manifest.name).bytes,main { NativeDraftFiles(owned.storage).bytes(file.manifest) })
        assertFalse(session.importing);ui.runOnIdle { session.error=null };shot("partial-provider-failure-keeps-original-good-files")
    }
    @Test fun encryptedOriginalGenerationSurvivesSessionReconstruction() {
        main { session.addAttachment("before.txt","text/plain",input("before.txt").bytes) }
        val destination=main { session.attachmentImportDestination() };ui.waitUntil(10_000) {
            val m=owned.storage.metadata();m?.optJSONObject("attachmentImportGenerations")?.optString(destination.key)==destination.generation&&m?.optJSONObject("draftFiles")?.optJSONArray(destination.key)?.length()==1
        }
        lateinit var restored: MobileSession
        main { session.leaveForeground();restored=MobileSession(owned.storage,reader,commands);otherSessions.add(restored);restored.restore() }
        ui.waitUntil(10_000) { !restored.restoring&&!restored.localRecoveryRequired&&restored.canCompose&&restored.selectedThread=="import-a" }
        main { track(restored.importAttachments(destination,listOf({ input("after.txt") }))).await() }
        assertEquals(listOf("before.txt","after.txt"),restored.attachments.map { it.manifest.name })
        ui.runOnIdle { ui.activity.setContent { AgentHubApp(restored) { } } };shot("same-process-encrypted-draft-reconstruction")
    }
    @Test fun cancellationAfterEncryptedWriteDiscardsOnlyTheUnpublishedFile() {
        val name="P4-cancel-unpublished-"+UUID.randomUUID();val store=NativeSessionStorage(ui.activity,name);val folder=File(ui.activity.noBackupFilesDir,name)
        val taskScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var job: Deferred<LocalDraftFile>
        try {
            i.runOnMainSync {
                job=taskScope.async { NativeDraftFiles(store).import("unpublished.txt","text/plain",ByteArray(256*1024){65}) }
                val deadline=android.os.SystemClock.uptimeMillis()+10_000
                while(folder.listFiles().orEmpty().none { it.name.endsWith(".bin") }&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(10)
                assertTrue("The real encrypted file must exist before the return is cancelled",folder.listFiles().orEmpty().any { it.name.endsWith(".bin") })
                job.cancel()
            }
            runBlocking { job.join() };assertTrue(job.isCancelled)
            assertTrue("No unpublished source or display cache remains",folder.listFiles().orEmpty().isEmpty());assertTrue(session.attachments.isEmpty())
        } finally { taskScope.cancel() }
    }
}
