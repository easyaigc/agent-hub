package com.agenthub.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Controlled registry/permission status; real private outputs, FileProvider, Keystore and parent transaction. */
class MobileCameraRecoveryTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private class Access(var allowed: Boolean=true,var exists: Boolean=true): NativeCameraAccess {
        override fun granted()=allowed
        override fun available()=exists
    }
    private class Registry(private val context: android.content.Context): ActivityResultRegistry() {
        var launches=0;var code=0;var outgoing: Intent?=null
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) { launches++;code=requestCode;outgoing=contract.createIntent(context,input) }
    }
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var server: CommandFixture
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private val access=Access()
    private val session get()=owned.session
    private val capture get()=session.cameraCapture!!
    private val recovery get()=capture.recovery!!
    private val store get()=owned.storage.child(ui.activity,"camera-results")
    private val outputs get()=NativeCameraOutputs(ui.activity,store.child(ui.activity,"outputs"))
    private val text get()=NativeL10n(ui.activity,"en")
    private val ids=mutableListOf<String>()
    private val registrations=mutableListOf<Triple<NativeSelectionRecovery,ActivityResultRegistry,Any>>()
    private val manual=mutableListOf<Pair<File,ByteArray>>()
    private val extraOutputs=mutableListOf<Pair<NativeCameraOutputs,String>>()
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun failures(value: NativeSelectionRecovery=recovery)=main { value.failures }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };owned=OwnedControlAcceptance(ui.activity,"camera-recovery")
        val target=JSONObject().put("agentId","codex").put("hostId","camera-fixture").put("workspaceId","camera-workspace").put("runtimeId","camera-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace" -> WireResponse(body=JSONObject().put("hostId","camera-fixture").put("name","Camera original target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","camera-fixture").put("title",it).put("state","idle") })).toString())
            "/v1/history" -> WireResponse(body=JSONObject().put("hostId","camera-fixture").put("threadId",android.net.Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else -> WireResponse(404)
        } }
        main {
            session.resetAcceptance();session.changeLanguage("en");session.changeAppearance(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light")
            session.configureCameraCapture(ui.activity,access);previous=ui.activity.adoptSessionForAcceptance(session)
            session.connect("Camera original target",server.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="原拍照草稿 100% %@"
        }
        ui.waitUntil(20_000) { session.canCompose&&!session.historyLoading&&!session.attachmentRecoveryBusy };ui.waitForIdle()
    }
    @After fun finish() {
        if(!::owned.isInitialized)return
        main { registrations.forEach { (r,registry,owner)->r.detach(registry,owner) };capture.pending?.let { capture.complete(it,false,text) } }
        ui.waitUntil(20_000) { !recovery.busy&&!session.importing }
        runBlocking { ids.forEach { outputs.cleanup(it) } }
        runBlocking { extraOutputs.forEach { (outputs,id)->outputs.cleanup(id) } }
        manual.forEach { (file,bytes)->if(file.exists()) { assertArrayEquals(bytes,file.readBytes());assertTrue(file.delete()) } }
        main { session.leaveForeground();ui.activity.restoreSessionForAcceptance(previous) }
        owned.verifyDefault();assertTrue(session.operationRecords.isEmpty());assertTrue(server.requests.all { it.method=="GET" });server.close()
    }
    private fun registry(r: NativeSelectionRecovery=recovery,value: Registry=Registry(ui.activity)): Registry {
        val owner=Any();main { r.attach(value,owner) };registrations+=Triple(r,value,owner);return value
    }
    private fun start(): NativeCameraRequest {
        lateinit var value: NativeCameraRequest
        main { assertTrue(capture.begin(session.attachmentDestination(),waitForMenu=true));value=checkNotNull(capture.pending) };ids+=value.id;return value
    }
    private fun launch(registry: Registry): NativeCameraRequest {
        val original=start();val prior=registry.launches
        main { recovery.launch(original.photo,text);capture.menuDidDismiss(original.photo.destination.presentation) }
        ui.waitUntil(15_000) { registry.launches==prior+1&&!recovery.busy };return original
    }
    private fun jpeg(): ByteArray {
        val image=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888)
        try { image.eraseColor(0xff336699.toInt());return ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.JPEG,100,it)) }.toByteArray() }finally { image.recycle() }
    }
    private fun assertPixels(file: CreationFile) {
        val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file) };val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try { assertEquals(8,image.width);assertEquals(6,image.height);val color=image.getPixel(4,3);assertTrue(kotlin.math.abs(Color.red(color)-51)<=3&&kotlin.math.abs(Color.green(color)-102)<=3&&kotlin.math.abs(Color.blue(color)-153)<=3) }finally { image.recycle() }
    }
    @Test fun privateOutputReturnsOnlyToOriginalDraftAndIgnoresForeignIntentData() {
        val registry=registry();val original=launch(registry);val output=checkNotNull(original.output);output.file.writeBytes(jpeg())
        assertTrue(output.uri.path!!.startsWith("/camera-output/"));assertEquals(output.uri,registry.outgoing!!.clipData!!.getItemAt(0).uri)
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="second camera draft";registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent().setData(android.net.Uri.parse("content://never-read/foreign"))) }
        ui.waitUntil(20_000) { !recovery.busy&&capture.pending==null&&!session.historyLoading }
        assertEquals("second camera draft",session.text);assertTrue(session.attachments.isEmpty());assertFalse(output.file.exists())
        main { session.selectThread(session.threads.first { it.id=="first" }) };ui.waitUntil(15_000) { !session.historyLoading&&session.attachments.size==1 }
        val file=session.attachments.single().manifest;assertEquals(output.file.name,file.name);assertPixels(file);assertEquals("原拍照草稿 100% %@",session.text)
        main { registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent()) };ui.waitUntil(15_000) { !recovery.busy };assertEquals(1,session.attachments.size);assertEquals(1,registry.launches)
    }
    @Test fun savedCameraRegistryRestoresOriginalOutputWithoutOpeningAgain() {
        val first=registry();val original=launch(first);val saved=Bundle();main { first.onSaveInstanceState(saved) };checkNotNull(original.output).file.writeBytes(jpeg())
        val next=NativeCameraCapture(ui.activity,session,store,access);val r=next.recovery!!;ui.waitUntil(15_000) { !r.loading }
        assertEquals(original.id,next.pending!!.id);assertEquals(original.output!!.uri,next.pending!!.output!!.uri);assertTrue(r.restoredWait)
        val second=Registry(ui.activity);main { second.onRestoreInstanceState(saved) };registry(r,second)
        main { assertTrue(second.dispatchResult(first.code,Activity.RESULT_OK,Intent())) }
        ui.waitUntil(20_000) { !r.busy&&next.pending==null&&session.attachments.size==1 };assertPixels(session.attachments.single().manifest);assertEquals(0,second.launches);assertFalse(original.output!!.file.exists())
    }
    @Test fun permissionIntentIsDurableAndDenialCannotReplaceNewerError() {
        access.allowed=false;val registry=registry();val original=launch(registry)
        assertTrue(original.permissionRequested);assertNull(original.output);assertEquals("permission",runBlocking { NativeDocumentJournal(store).records().single().phase })
        main { session.error="newer camera error 100% %@";registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent().putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(Manifest.permission.CAMERA)).putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(PackageManager.PERMISSION_DENIED))) }
        ui.waitUntil(15_000) { capture.pending==null&&!recovery.busy };assertEquals("newer camera error 100% %@",session.error);assertTrue(session.attachments.isEmpty());assertEquals(1,registry.launches)
        ui.onNodeWithTag("camera-recovery-discard").performScrollTo().assertIsDisplayed();owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"camera-permission-denied")
    }
    @Test fun unavailableCameraDoesNotRequestPermissionOrCreateAnOutput() {
        access.exists=false;access.allowed=false;val registry=registry();val original=start()
        main { recovery.launch(original.photo,text) };ui.waitUntil(15_000) { !recovery.busy&&capture.pending==null }
        assertEquals(0,registry.launches);assertNull(original.output);assertTrue(runBlocking { NativeDocumentJournal(store).records().isEmpty() })
        assertEquals("The camera is unavailable on this device.",session.error);assertEquals("原拍照草稿 100% %@",session.text)
    }
    @Test fun canceledPreparationDoesNotLeaveOwnedOutputOrReplayCamera() {
        val registry=registry();val original=start();val claimStore=store.child(ui.activity,"outputs")
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val holder=kotlin.concurrent.thread(name="owned-camera-preparation-gate") { claimStore.operationTransaction { entered.countDown();release.await(45,java.util.concurrent.TimeUnit.SECONDS) } }
        var job: Job?=null
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            main { job=CoroutineScope(SupervisorJob()+Dispatchers.Main).launch { recovery.launch(original.photo,text) } }
            ui.waitUntil(10_000) { runBlocking { NativeDocumentJournal(store).records().any { it.id==original.id&&it.phase=="prepared" } } }
            main { checkNotNull(job).cancel() }
        }finally { release.countDown();holder.join(20_000) }
        runBlocking { job?.join() };ui.waitUntil(15_000) { !recovery.busy&&capture.pending==null }
        assertFalse(outputs.output(original.id).file.exists());assertNull(claimStore.read("output-v1-${original.id}"));assertEquals(0,registry.launches)
        assertEquals("retired",runBlocking { NativeDocumentJournal(store).records().single().phase })
    }
    @Test fun grantedPermissionContinuesFirstCameraLaunchWithoutRequestingPermissionAgain() {
        access.allowed=false;val registry=registry();val original=launch(registry);val permissionCode=registry.code;access.allowed=true
        main { registry.dispatchResult(permissionCode,Activity.RESULT_OK,Intent().putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(Manifest.permission.CAMERA)).putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(PackageManager.PERMISSION_GRANTED))) }
        ui.waitUntil(15_000) { registry.launches==2&&original.output!=null&&!recovery.busy }
        assertEquals("android.media.action.IMAGE_CAPTURE",registry.outgoing!!.action);assertTrue(original.output!!.file.exists())
        main { registry.dispatchResult(registry.code,Activity.RESULT_CANCELED,null) };ui.waitUntil(15_000) { capture.pending==null&&!recovery.busy };assertFalse(original.output!!.file.exists());assertTrue(session.attachments.isEmpty())
    }
    @Test fun restoredPermissionCallbackKeepsOriginalRecordWithoutRequestingAgain() {
        access.allowed=false;val first=registry();val original=launch(first);val saved=Bundle();main { first.onSaveInstanceState(saved) }
        val next=NativeCameraCapture(ui.activity,session,store,access);val r=next.recovery!!;ui.waitUntil(15_000) { !r.loading }
        assertEquals(original.id,next.pending!!.id);assertTrue(next.pending!!.permissionRequested);assertNull(next.pending!!.output)
        val second=Registry(ui.activity);main { second.onRestoreInstanceState(saved) };registry(r,second)
        main { session.error="new cold permission error";assertTrue(second.dispatchResult(first.code,Activity.RESULT_OK,Intent().putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(Manifest.permission.CAMERA)).putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(PackageManager.PERMISSION_DENIED)))) }
        ui.waitUntil(15_000) { !r.busy&&next.pending==null };assertEquals("new cold permission error",session.error);assertEquals("failed",runBlocking { NativeDocumentJournal(store).records().single().phase });assertEquals(0,second.launches);assertTrue(session.attachments.isEmpty())
    }
    @Test fun readyCameraBytesResumeAfterParentRestoreWithoutFocusOrCameraRelaunch() {
        val destination=main { session.attachmentImportDestination() };assertTrue(main { session.confirmAttachmentOrigin(destination) });main { session.leaveForeground() };runBlocking { owned.storage.awaitQueuedWrites() }
        val parent=owned.storage.child(ui.activity,"camera-parent-order");parent.write("metadata",checkNotNull(owned.storage.metadata()).toString());val store=parent.child(ui.activity,"camera-results")
        val original=NativePhotoRequest(destination,Long.MIN_VALUE,false);val journal=NativeDocumentJournal(store);val outputs=NativeCameraOutputs(ui.activity,store.child(ui.activity,"outputs"));extraOutputs+=outputs to original.id
        runBlocking {
            journal.restore();journal.prepare(original);val output=outputs.prepare(original.id);output.file.writeBytes(jpeg())
            journal.update(original.id) { it.copy(phase="selected",sources=listOf(output.uri.toString())) }
            NativeAttachmentInboxStore(store.child(ui.activity,"payloads")).stage(original.id,listOf(output.uri),ui.activity.contentResolver) { NativeSelectionKind.CAMERA.read(ui.activity.contentResolver,it) }
            journal.update(original.id) { it.copy(phase="ready") };outputs.cleanup(original.id)
        }
        val next=main { MobileSession(parent) };val camera=NativeCameraCapture(ui.activity,next,store,access);val r=camera.recovery!!;val registry=Registry(ui.activity);val owner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        main { ui.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { NativeCameraCaptureResult(camera) } } }
        ui.waitUntil(20_000) { !r.loading&&!r.busy&&runBlocking { journal.records().single().phase=="ready" } };assertFalse(r.parentReady);assertEquals(0,registry.launches)
        main { next.restore() };ui.waitUntil(20_000) { !next.restoring&&!r.busy&&runBlocking { journal.records().single().phase=="consumed" } }
        val file=next.attachments.single().manifest;assertEquals("photo-${original.id}.jpg",file.name);assertEquals(0,registry.launches);assertEquals("原拍照草稿 100% %@",next.text)
        val bytes=runBlocking { NativeDraftFiles(parent).bytes(file) };assertNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.also { it.recycle() });main { next.leaveForeground() }
    }
    @Test fun endingUnknownCameraWaitCannotConsumeReplacementOrLateResult() {
        val registry=registry();val old=launch(registry);old.output!!.file.writeBytes(jpeg());val oldCode=registry.code;main { recovery.retryRestore() };ui.waitUntil(15_000) { !recovery.loading&&recovery.restoredWait }
        ui.onNodeWithTag("camera-recovery-end-wait").performScrollTo().assertIsDisplayed();owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"camera-unknown-wait")
        ui.onNodeWithTag("camera-recovery-end-wait").performClick();ui.waitUntil(15_000) { capture.pending==null&&recovery.canBegin }
        assertTrue(old.output!!.file.exists());assertEquals("detached",runBlocking { NativeDocumentJournal(store).records().single().phase })
        val restored=NativeCameraCapture(ui.activity,session,store,access);ui.waitUntil(15_000) { !restored.recovery!!.loading };assertNull(restored.pending);assertEquals("detached",failures(restored.recovery!!).single().phase)
        val current=launch(registry);val currentCode=registry.code;current.output!!.file.writeBytes(jpeg())
        main { registry.dispatchResult(oldCode,Activity.RESULT_OK,Intent()) };ui.waitUntil(15_000) { !recovery.busy&&failures().any { it.id==old.id&&it.phase=="held" } };assertSame(current,capture.pending);assertTrue(current.output!!.file.exists());assertTrue(session.attachments.isEmpty());assertFalse(old.output!!.file.exists())
        assertEquals("held",failures().single().phase);ui.onNodeWithTag("camera-recovery-retry-files").performScrollTo().assertTextEquals("Add to original draft");owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"camera-ended-late-result-held")
        main { registry.dispatchResult(currentCode,Activity.RESULT_CANCELED,null) };ui.waitUntil(15_000) { capture.pending==null&&!recovery.busy }
        ui.onNodeWithTag("camera-recovery-retry-files").performClick();ui.waitUntil(15_000) { !recovery.busy&&failures().isEmpty()&&session.attachments.size==1 };assertPixels(session.attachments.single().manifest);assertEquals(2,registry.launches)
    }
    @Test fun cameraCallbackDuringEndWaitIsSavedWithoutAddingToDraft() {
        val registry=registry();val original=launch(registry);original.output!!.file.writeBytes(jpeg())
        main { recovery.endWait(original.id);registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent()) }
        ui.waitUntil(20_000) { !recovery.busy&&failures().any { it.id==original.id&&it.phase=="held" } }
        assertTrue(session.attachments.isEmpty());assertNull(capture.pending);assertFalse(original.output!!.file.exists());assertEquals(1,registry.launches)
        val batch=runBlocking { NativeAttachmentInboxStore(store.child(ui.activity,"payloads")).snapshot().records.single() };assertEquals("pending",batch.phase);assertEquals(1,batch.readyFiles.size)
        main { recovery.retire(original.id) };ui.waitUntil(15_000) { !recovery.busy&&failures().isEmpty() };assertEquals("原拍照草稿 100% %@",session.text)
    }
    @Test fun encryptedOriginalSurvivesRetiredGenerationAndLateCancellation() {
        val registry=registry();val original=launch(registry);original.output!!.file.writeBytes(jpeg())
        main { session.clearAttachmentDraft(original.photo);session.text="new camera draft";registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent()) }
        ui.waitUntil(20_000) { !recovery.busy&&failures().any { it.id==original.id } };assertFalse(original.output!!.file.exists());assertTrue(session.attachments.isEmpty())
        val spool=NativeAttachmentInboxStore(store.child(ui.activity,"payloads"));val batch=runBlocking { spool.snapshot().records.single() };assertEquals("pending",batch.phase)
        main { registry.dispatchResult(registry.code,Activity.RESULT_CANCELED,null) };ui.waitUntil(15_000) { !recovery.busy };assertEquals(batch,runBlocking { spool.snapshot().records.single() });assertEquals("failed",runBlocking { NativeDocumentJournal(store).records().single().phase })
        ui.onNodeWithTag("camera-recovery-discard").performScrollTo().assertIsDisplayed();owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"camera-original-failed")
        ui.onNodeWithTag("camera-recovery-discard").performClick();ui.waitUntil(15_000) { !recovery.busy&&failures().isEmpty() };assertEquals("new camera draft",session.text);assertEquals(1,registry.launches)
    }
    @Test fun missingOriginalOutputFailsWithoutReadingAnotherSource() {
        val registry=registry();val original=launch(registry);assertTrue(original.output!!.file.delete())
        main { registry.dispatchResult(registry.code,Activity.RESULT_OK,Intent()) };ui.waitUntil(15_000) { !recovery.busy&&capture.pending==null&&failures().isNotEmpty() }
        assertTrue(session.attachments.isEmpty());assertEquals(listOf(original.output!!.uri.toString()),failures().single().sources);assertEquals(1,registry.launches)
    }
    @Test fun unknownUnclaimedOutputIsNeverDeletedByRetiringItsRequest() {
        val registry=registry();val original=start();val output=outputs.output(original.id);output.file.parentFile!!.mkdirs();val bytes="unclaimed owned acceptance sentinel".toByteArray();output.file.writeBytes(bytes);manual+=output.file to bytes
        main { recovery.launch(original.photo,text) };ui.waitUntil(15_000) { !recovery.busy&&recovery.restoredWait };assertEquals(0,registry.launches);assertArrayEquals(bytes,output.file.readBytes())
        main { recovery.retire(original.id) };ui.waitUntil(15_000) { !recovery.busy&&capture.pending==null };assertArrayEquals(bytes,output.file.readBytes());assertTrue(session.attachments.isEmpty())
    }
    @Test fun unreadableCameraJournalPreservesBytesAndAllowsIndependentFileChoice() {
        val raw="{\"version\":999,\"revision\":0,\"records\":[]}";store.write("document-requests-v1",raw)
        val before=File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() }
        main { recovery.retryRestore() };ui.waitUntil(15_000) { !recovery.loading };assertTrue(recovery.failed);assertFalse(main { capture.begin(session.attachmentDestination()) });assertFalse(session.canRemoveDraftAttachment)
        val picker=session.documentPicker!!;val r=picker.recovery!!;val registry=registry(r)
        main { assertTrue(picker.begin(NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,false)));r.launch(picker.pending!!,text);registry.dispatchResult(registry.code,emptyList<android.net.Uri>()) }
        ui.waitUntil(15_000) { picker.pending==null&&!r.busy };assertEquals(raw,store.read("document-requests-v1"));assertEquals(before,File(store.coordinationKey).listFiles()!!.associate { it.name to it.readBytes().toList() })
        ui.onNodeWithTag("camera-recovery-retry-read").performScrollTo().assertIsDisplayed();owned.nodeShot(ui.onNodeWithTag("selection-recovery-notices"),"camera-record-read-failed")
    }
}
