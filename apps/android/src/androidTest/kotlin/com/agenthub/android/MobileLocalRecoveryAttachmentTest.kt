package com.agenthub.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.AtomicFile
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Full product recovery gate, controlled OS callbacks, actual encrypted journals/payloads/parent files. */
class MobileLocalRecoveryAttachmentTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private class Access(var allowed: Boolean=true): NativeCameraAccess {
        override fun granted()=allowed
        override fun available()=true
    }
    private class Registry(private val context: android.content.Context): ActivityResultRegistry() {
        var launches=0;var code=0;var action: String?=null
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) { launches++;code=requestCode;action=contract.createIntent(context,input).action }
    }
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var server: CommandFixture
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private lateinit var registry: Registry
    private val access=Access()
    private val session get()=owned.session
    private val storage get()=owned.storage
    private val text get()=NativeL10n(ui.activity,"en")
    private val metadataFile get()=File(storage.coordinationKey,MessageDigest.getInstance("SHA-256").digest("metadata".toByteArray()).joinToString("") { "%02x".format(it) }+".bin")
    private val sources=mutableListOf<File>()
    private val requests=mutableListOf<Pair<NativeSelectionRecovery,NativePhotoRequest>>()
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };owned=OwnedControlAcceptance(ui.activity,"local-recovery-attachments")
        val target=JSONObject().put("agentId","codex").put("hostId","local-gate-fixture").put("workspaceId","local-gate-workspace").put("runtimeId","local-gate-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace" -> WireResponse(body=JSONObject().put("hostId","local-gate-fixture").put("name","Local recovery fixture").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","local-gate-fixture").put("title",it).put("state","idle") })).toString())
            "/v1/history" -> WireResponse(body=JSONObject().put("hostId","local-gate-fixture").put("threadId",android.net.Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else -> WireResponse(404)
        } }
        main {
            session.resetAcceptance();session.changeLanguage("en");session.changeAppearance(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light")
            session.configureCameraCapture(ui.activity,access);previous=ui.activity.adoptSessionForAcceptance(session)
            registry=Registry(ui.activity);val owner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
            ui.activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { AgentHubApp(session) } }
            session.connect("Local recovery fixture",server.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="原本地恢复草稿 100% %@"
        }
        ui.waitUntil(20_000) { session.canCompose&&!session.historyLoading&&!session.attachmentRecoveryBusy }
        main { session.addAttachment("existing-gate.txt","text/plain","existing local gate\n".toByteArray());session.selectThread(session.threads.first { it.id=="second" });session.text="other original draft";session.selectThread(session.threads.first { it.id=="first" }) }
        ui.waitUntil(20_000) { !session.historyLoading&&!session.importing };runBlocking { storage.awaitQueuedWrites() }
    }
    @After fun finish() {
        if(!::owned.isInitialized)return
        main { requests.forEach { (recovery,request)->recovery.retire(request.id) } }
        ui.waitUntil(20_000) { main { requests.none { it.first.busy } } }
        sources.forEach { if(it.exists())assertTrue(it.delete()) }
        main { session.leaveForeground();ui.activity.restoreSessionForAcceptance(previous) }
        owned.verifyDefault();assertTrue(session.operationRecords.isEmpty());assertTrue(server.requests.all { it.method=="GET" });server.close()
    }
    private fun jpeg(): ByteArray {
        val image=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888)
        try { image.eraseColor(0xff336699.toInt());return ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.JPEG,100,it)) }.toByteArray() }finally { image.recycle() }
    }
    private fun begin(kind: NativeSelectionKind): Pair<NativeSelectionRecovery,NativePhotoRequest> {
        val pair=main {
            val original=NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,false)
            val recovery=when(kind) {
                NativeSelectionKind.DOCUMENT -> session.documentPicker!!.let { assertTrue(it.begin(original));it.recovery!! }
                NativeSelectionKind.PHOTO -> session.photoPicker!!.let { assertTrue(it.begin(original));it.recovery!! }
                NativeSelectionKind.CAMERA -> session.cameraCapture!!.let { assertTrue(it.begin(original));it.recovery!! }
            }
            requests+=recovery to original;recovery.launch(original,text);recovery to original
        }
        ui.waitUntil(15_000) { main { !pair.first.busy&&registry.launches==1 } };return pair
    }
    private fun breakParent(authenticated: Boolean=true): ByteArray {
        runBlocking { storage.awaitQueuedWrites() };val valid=metadataFile.readBytes()
        if(authenticated)storage.write("metadata","{P7_OWNED_PARENT_UNREADABLE")
        else restoreExactPriorCiphertext(valid.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })
        main { session.restore();assertTrue(session.restoring);assertFalse(session.canReceiveOriginalAttachmentResults) };ui.waitUntil(15_000) { session.localRecoveryRequired&&!session.restoring }
        assertEquals(authenticated,session.localRecoveryAllowsAttachmentStaging)
        ui.onNodeWithTag("local-recovery-required").assertIsDisplayed();ui.onNodeWithTag("composer").assertDoesNotExist();ui.onNodeWithTag("open-sidebar").assertDoesNotExist();return valid
    }
    private fun restoreExactPriorCiphertext(valid: ByteArray) {
        // Test-only external repair of the saved owned fixture. Product write fencing stays active.
        val atomic=AtomicFile(metadataFile);val out=atomic.startWrite();out.write(valid);atomic.finishWrite(out)
    }
    private fun returnWhileParentUnreadable(kind: NativeSelectionKind,authenticated: Boolean=true) {
        val (recovery,original)=begin(kind);val code=registry.code;val bytes=if(kind==NativeSelectionKind.DOCUMENT)"original late local document\n".toByteArray() else jpeg()
        val uri=if(kind==NativeSelectionKind.CAMERA)session.cameraCapture!!.pending!!.output!!.also { it.file.writeBytes(bytes) }.uri else {
            val file=File(ui.activity.cacheDir,"resources/local-gate-${UUID.randomUUID()}."+if(kind==NativeSelectionKind.DOCUMENT)"txt"else"jpg");file.parentFile!!.mkdirs();file.writeBytes(bytes);sources+=file;FileProvider.getUriForFile(ui.activity,ui.activity.packageName+".resources",file)
        }
        val valid=breakParent(authenticated);val corrupt=metadataFile.readBytes();main { registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)) }
        val child=storage.child(ui.activity,kind.registryName+"-results");val journal=NativeDocumentJournal(child)
        if(authenticated)ui.waitUntil(20_000) { main { !recovery.busy }&&runBlocking { journal.records().single().phase=="ready" } }
        else {
            ui.waitForIdle();ui.waitUntil(15_000) { main { !recovery.busy } }
            assertEquals("launched",runBlocking { journal.records().single().phase });assertTrue(runBlocking { NativeAttachmentInboxStore(child.child(ui.activity,"payloads")).snapshot().records.isEmpty() })
        }
        assertArrayEquals(corrupt,metadataFile.readBytes());assertEquals(1,session.attachments.size);assertTrue(session.localRecoveryRequired);assertEquals(1,registry.launches)
        if(authenticated) {
            val batch=runBlocking { NativeAttachmentInboxStore(child.child(ui.activity,"payloads")).snapshot().records.single() };assertEquals("pending",batch.phase);assertEquals(1,batch.readyFiles.size)
        }
        if(kind==NativeSelectionKind.CAMERA)assertEquals(!authenticated,NativeCameraOutputs(ui.activity,child.child(ui.activity,"outputs")).output(original.id).file.exists())
        val label=kind.registryName+if(authenticated)"-parent-format"else"-parent-authentication"
        owned.nodeShot(ui.onNodeWithTag("local-recovery-required"),label+"-unreadable-original-retained")
        restoreExactPriorCiphertext(valid);ui.onNodeWithTag("retry-local-recovery").performClick()
        ui.waitUntil(20_000) { !session.localRecoveryRequired&&!session.restoring&&main { !recovery.busy&&session.attachments.size==2 }&&runBlocking { journal.records().single().phase=="consumed" } }
        ui.onNodeWithTag("local-recovery-required").assertDoesNotExist();assertEquals("原本地恢复草稿 100% %@",session.text);assertEquals("existing-gate.txt",session.attachments.first().manifest.name)
        val attached=session.attachments.last().manifest;val actual=runBlocking { NativeDraftFiles(storage).bytes(attached) }
        if(kind==NativeSelectionKind.DOCUMENT)assertArrayEquals(bytes,actual) else assertNotNull(android.graphics.BitmapFactory.decodeByteArray(actual,0,actual.size)?.also { assertEquals(8,it.width);assertEquals(6,it.height);it.recycle() })
        main { registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)) };ui.waitForIdle();assertEquals(2,session.attachments.size);assertEquals(1,registry.launches)
        owned.nodeShot(ui.onNodeWithTag("product-surface"),label+"-original-result-after-parent-retry")
    }
    @Test fun originalDocumentResultStagesWhileParentIsUnreadableThenRetriesOnlyLocalImport()=returnWhileParentUnreadable(NativeSelectionKind.DOCUMENT)
    @Test fun originalPhotoResultStagesWhileParentIsUnreadableThenRetriesOnlyLocalImport()=returnWhileParentUnreadable(NativeSelectionKind.PHOTO)
    @Test fun originalCameraResultStagesWhileParentIsUnreadableThenRetriesOnlyLocalImport()=returnWhileParentUnreadable(NativeSelectionKind.CAMERA)
    @Test fun originalDocumentResultWaitsInRegistryWhenParentAuthenticationFails()=returnWhileParentUnreadable(NativeSelectionKind.DOCUMENT,false)
    @Test fun originalPhotoResultWaitsInRegistryWhenParentAuthenticationFails()=returnWhileParentUnreadable(NativeSelectionKind.PHOTO,false)
    @Test fun originalCameraResultWaitsInRegistryWhenParentAuthenticationFails()=returnWhileParentUnreadable(NativeSelectionKind.CAMERA,false)
    @Test fun originalCameraPermissionDenialIsRecordedBehindParentRecoveryWithoutNewLaunch() {
        access.allowed=false;val (recovery,original)=begin(NativeSelectionKind.CAMERA);val code=registry.code;assertEquals("androidx.activity.result.contract.action.REQUEST_PERMISSIONS",registry.action)
        val valid=breakParent();val corrupt=metadataFile.readBytes()
        main { registry.dispatchResult(code,Activity.RESULT_OK,Intent().putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(Manifest.permission.CAMERA)).putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(PackageManager.PERMISSION_DENIED))) }
        val journal=NativeDocumentJournal(storage.child(ui.activity,"camera-results"));ui.waitUntil(15_000) { main { !recovery.busy }&&runBlocking { journal.records().single().phase=="failed" } }
        assertArrayEquals(corrupt,metadataFile.readBytes());assertTrue(session.localRecoveryRequired);assertEquals(1,registry.launches);assertEquals(1,session.attachments.size)
        restoreExactPriorCiphertext(valid);ui.onNodeWithTag("retry-local-recovery").performClick();ui.waitUntil(15_000) { !session.localRecoveryRequired&&!session.restoring&&!session.historyLoading }
        assertEquals(1,registry.launches);assertEquals("原本地恢复草稿 100% %@",session.text);assertEquals(original.id,main { recovery.failures.single().id })
    }
}
