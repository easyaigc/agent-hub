package com.agenthub.android

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Native panel/draft/request boundaries, real providers and encrypted storage; GET-only fixture. */
class MobileAttachmentPanelTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var server: CommandFixture
    private val activity get()=ui.activity
    private val session get()=owned.session
    private val text get()=NativeL10n(activity,"en")
    private val paths=mutableListOf<File>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"attachment-panel");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light")
            session.configurePhotoPicker(activity);session.configureCameraCapture(activity);session.configureDocumentPicker(activity) }
        val target=JSONObject().put("agentId","codex").put("hostId","panel-host").put("workspaceId","panel-workspace").put("runtimeId","panel-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","panel-host").put("name","Panel original target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","panel-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","panel-host").put("threadId",Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        main { session.connect("Panel original target",server.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="原稿 100% %@ 😀\nsecond line" }
        ui.waitUntil(15_000) { !session.loading&&!session.historyLoading&&session.canCompose }
    }
    @After fun finish() {
        main { session.documentPicker?.pending?.let { session.documentPicker!!.complete(it,emptyList(),text) };session.photoPicker?.pending?.let { session.photoPicker!!.complete(it,emptyList(),text) };session.leaveForeground() }
        ui.waitUntil(15_000) { !session.importing }
        owned.verifyDefault();assertTrue(session.operationRecords.isEmpty());assertTrue(server.requests.all { it.method=="GET" });server.close()
        paths.forEach { assertTrue(!it.exists()||it.delete()) }
    }
    private fun request()=session.let { NativePhotoRequest(it.attachmentImportDestination(),it.attachmentErrorGeneration,true) }
    private fun file(name: String,bytes: ByteArray): Uri {
        val dir=File(activity.cacheDir,"resources/panel-${UUID.randomUUID()}").apply { mkdirs() };paths+=dir
        val file=File(dir,name).apply { writeBytes(bytes) };paths.add(0,file)
        return FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
    }
    private fun open() { ui.onNodeWithTag("composer-add").assertIsEnabled().performClick();ui.onNodeWithTag("attachment-panel").assertExists() }
    private fun row(tag: String): SemanticsNodeInteraction {
        ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag(tag))
        return ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun tap(tag: String) { row(tag).performClick() }
    @Test fun groupedPanelHasAccessibleIconsAndScrollableActionsWithoutReadingClipboardOrRequestingPermission() {
        val before=NativeRecentPhotos(activity).access();val draft=session.text;open()
        ui.onNodeWithTag("attachment-panel-title").assertTextEquals(text.tr("添加附件"));owned.dialogShot("panel-recent-section")
        val expected=listOf("take-photo" to "拍照","choose-photos" to "选择图片","choose-files" to "选择文件","paste-files" to "粘贴图片或文件","clear-composer-draft" to "清空草稿")
        for((tag,key)in expected) {
            val row=row(tag).assertIsEnabled().assertTextContains(text.tr(key))
            ui.onNodeWithTag("$tag-icon",useUnmergedTree=true).assertExists()
            assertTrue(row.fetchSemanticsNode().boundsInRoot.height>=with(ui.density){48.dp.toPx()}-1)
        }
        owned.dialogShot("panel-lower-actions");ui.onNodeWithTag("attachment-panel-close").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("attachment-panel").fetchSemanticsNodes().isEmpty() }
        assertEquals(before,NativeRecentPhotos(activity).access());assertEquals(draft,session.text);assertNull(session.error)
    }
    @Test fun clearRetiresOriginalImportGenerationWithoutChangingSettingsOrAnotherDraft() {
        val bytes="original file 100% %@\n".toByteArray();main { session.addAttachment("owned.txt","text/plain",bytes) }
        lateinit var original: NativePhotoRequest;main { original=request() };val settings=session.generationSettings
        open();tap("clear-composer-draft");ui.waitUntil(10_000) { session.text.isEmpty()&&session.attachments.isEmpty() }
        var read=false
        main { assertNull(session.importAttachments(original.destination,listOf({ read=true;LocalAttachmentInput("late.txt","text/plain",bytes) })));assertFalse(session.documentPicker!!.begin(original));assertFalse(session.photoPicker!!.begin(original));assertFalse(session.cameraCapture!!.begin(original)) }
        assertFalse(read);assertEquals(settings,session.generationSettings)
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="第二草稿" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose };main { session.clearAttachmentDraft(original) };assertEquals("第二草稿",session.text)
        runBlocking { owned.storage.awaitQueuedWrites() };assertTrue(owned.storage.metadata()!!.getJSONObject("draftFiles").getJSONArray(original.destination.key).length()==0)
    }
    @Test fun oldPanelRequestCannotClearOrLaunchIntoRevisitedSelection() {
        lateinit var old: NativePhotoRequest;main { old=request() };open()
        main { session.selectThread(session.threads.first { it.id=="second" });session.text="第二草稿" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose };main { session.selectThread(session.threads.first { it.id=="first" }) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
        main { assertFalse(session.acceptsAttachmentAction(old));session.clearAttachmentDraft(old);assertFalse(session.documentPicker!!.begin(old));assertFalse(session.photoPicker!!.begin(old));assertFalse(session.cameraCapture!!.begin(old)) }
        assertEquals("原稿 100% %@ 😀\nsecond line",session.text);assertTrue(session.attachments.isEmpty());assertNull(session.error)
        ui.onAllNodesWithTag("attachment-panel").assertCountEquals(0)
    }
    @Test fun documentResultUsesOriginalEncryptedDraftAfterNavigationAndDeduplicatesCallbacks() {
        val bytes="code 😀\r\n100% %@\n".toByteArray();val uri=file("原文件 100% %@.txt",bytes)
        lateinit var original: NativePhotoRequest;main { original=request();assertTrue(session.documentPicker!!.begin(original));session.selectThread(session.threads.first { it.id=="second" });session.text="第二草稿";session.documentPicker!!.complete(original,listOf(uri,uri),text) }
        ui.waitUntil(10_000) { !session.importing&&!session.historyLoading };assertTrue(session.attachments.isEmpty());assertEquals("第二草稿",session.text)
        main { session.selectThread(session.threads.first { it.id=="first" }) };ui.waitUntil(15_000) { !session.importing&&!session.historyLoading&&session.attachments.size==1 }
        val actual=session.attachments.single();assertEquals("原文件 100% %@.txt",actual.manifest.name);assertArrayEquals(bytes,runBlocking { NativeDraftFiles(owned.storage).bytes(actual.manifest) })
        assertTrue(runBlocking { session.confirmInboxFiles(original.destination,listOf(actual.manifest)) })
        main { session.documentPicker!!.complete(original,listOf(uri),text) };assertEquals(1,session.attachments.size)
    }
    @Test fun oldDocumentFailureCannotConsumeNewRequestOrReplaceNewerErrorAndAllOsEntriesAreMutual() {
        lateinit var old: NativePhotoRequest;lateinit var fresh: NativePhotoRequest
        main { old=request();assertTrue(session.documentPicker!!.begin(old));assertFalse(session.photoPicker!!.begin(request()));assertFalse(session.cameraCapture!!.begin(request()));session.documentPicker!!.complete(old,emptyList(),text);fresh=request();assertTrue(session.documentPicker!!.begin(fresh));session.error="newer 100% %@";session.documentPicker!!.launchFailed(old,text);session.documentPicker!!.complete(old,listOf(Uri.parse("content://missing/old")),text) }
        assertSame(fresh,session.documentPicker!!.pending);assertEquals("newer 100% %@",session.error)
        main { session.documentPicker!!.complete(fresh,listOf(Uri.parse("content://missing/fresh")),text) };ui.waitUntil(10_000) { !session.importing };assertEquals("newer 100% %@",session.error)
    }
    @Test fun fileChooserLaunchesOnlyAfterPanelDismissesAndNativeCancelPreservesDraft() {
        val i=InstrumentationRegistry.getInstrumentation();val outgoing=ActivityResultContracts.OpenMultipleDocuments().createIntent(activity,arrayOf("*/*"))
        val handler=activity.packageManager.resolveActivity(outgoing,0)!!;val pkg=handler.activityInfo.packageName;assertNotEquals(activity.packageName,pkg)
        assertTrue(handler.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0)
        val before=session.text;open();tap("choose-files")
        ui.waitUntil(10_000) { session.documentPicker!!.pending?.launched==true };assertTrue(session.documentPicker!!.pending!!.menuDismissed)
        val end=System.currentTimeMillis()+20_000;while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!=pkg&&System.currentTimeMillis()<end)Thread.sleep(100)
        assertEquals(pkg,i.uiAutomation.rootInActiveWindow?.packageName?.toString());ui.onAllNodesWithTag("attachment-panel").assertCountEquals(0)
        assertTrue(i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(20_000) { session.documentPicker!!.pending==null };assertEquals(before,session.text);assertTrue(session.attachments.isEmpty());assertNull(session.error)
        File(owned.folder,"native-document-cancel.json").writeText(JSONObject().put("systemHandler",pkg).put("panelReleasedBeforeLaunch",true).put("noSourceSelected",true).put("originalDraftPreserved",true).toString())
    }
}
