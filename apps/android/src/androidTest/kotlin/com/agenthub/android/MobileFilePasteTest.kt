package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Real ContentResolver/FileProvider and native clipboard, owned draft/storage only, never sends. */
class MobileFilePasteTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val local=mutableListOf<File>()
    private var server: CommandFixture?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"file-paste");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light") }
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { server?.close();local.forEach { assertTrue(!it.exists()||it.delete()) } }
    }
    private fun file(name: String,bytes: ByteArray): Uri {
        val dir=File(activity.cacheDir,"resources/paste-"+UUID.randomUUID()).apply { mkdirs() };local.add(dir)
        val file=File(dir,name).apply { writeBytes(bytes) };local.add(0,file)
        return FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
    }
    private fun fail(key: String,action: ()->Unit) {
        try { action();fail("Expected local intake rejection") }catch(f: NativeFileIntakeFailure) { assertEquals(key,f.key) }
    }
    private fun read(uri: Uri)=runBlocking { NativeFileIntake.read(activity.contentResolver,uri) }
    private fun connect() {
        val target=JSONObject().put("agentId","codex").put("hostId","paste-host").put("workspaceId","paste-workspace").put("runtimeId","paste-runtime")
        val fixture=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","paste-host").put("name","原电脑 100% %@").put("connectionMode","desktop-runtime")
                .put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1")))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","paste-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","paste-host").put("threadId",Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("原电脑 100% %@",fixture.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="原草稿 100% %@" }
        ui.waitUntil(10_000) { !session.loading&&!session.historyLoading&&session.runtime!=null };assertTrue(session.canCompose)
    }
    @Test fun clipboardSnapshotRejectsTextUrlsAndExcessItemsWithoutReadingAnyProvider() {
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(null) }
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(ClipData.newPlainText("file","https://example.invalid/file.txt")) }
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(ClipData.newRawUri("file",Uri.parse("https://example.invalid/file.txt"))) }
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(ClipData.newRawUri("file",Uri.parse("file:///ungranted/source"))) }
        val first=Uri.parse("content://owned.test/first");val second=Uri.parse("content://owned.test/second")
        val clip=ClipData.newRawUri("original",first);clip.addItem(ClipData.Item(second));val snapshot=NativeFileIntake.clipboardFiles(clip)
        clip.addItem(ClipData.Item("later text"));assertEquals(listOf(first,second),snapshot)
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(clip) }
        val tooMany=ClipData.newRawUri("original",first);repeat(10) { tooMany.addItem(ClipData.Item(first)) }
        fail(NativeFileIntakeFailure.UNSUPPORTED) { NativeFileIntake.clipboardFiles(tooMany) }
    }
    @Test fun realContentProviderPreservesExactTextImageBytesAndOriginalNames() {
        val text="原文件 100% %@\r\n原代码 😀\n".toByteArray();val name="展开 100% %@.txt";val uri=file(name,text)
        val payload=read(uri);assertEquals(name,payload.name);assertEquals("text/plain",payload.mediaType);assertArrayEquals(text,payload.bytes)
        val png=ByteArrayOutputStream().also { out -> val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);try { bitmap.compress(Bitmap.CompressFormat.PNG,100,out) }finally { bitmap.recycle() } }.toByteArray()
        val image=read(file("原图 100% %@.png",png));assertEquals("image/png",image.mediaType);assertArrayEquals(png,image.bytes)
    }
    @Test fun emptyOversizedUnavailableAndWebSourcesRejectBeforeAnyDraftMutation() {
        fail(NativeFileIntakeFailure.TOO_LARGE) { read(file("empty.txt",byteArrayOf())) }
        val uri=file("large.bin",byteArrayOf(1));val path=local.first();java.io.RandomAccessFile(path,"rw").use { it.setLength(NativeFileIntake.MAXIMUM_BYTES.toLong()+1) }
        fail(NativeFileIntakeFailure.TOO_LARGE) { read(uri) }
        fail(NativeFileIntakeFailure.UNAVAILABLE) { read(Uri.parse("content://com.agenthub.missing.provider/unavailable")) }
        fail(NativeFileIntakeFailure.UNSUPPORTED) { read(Uri.parse("https://example.invalid/file.txt")) }
        assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun explicitSystemClipboardActionOnlyPreparesOriginalDraftAndRestoresClipboard() {
        connect();lateinit var clipboard: ClipboardManager;var previous: ClipData?=null
        main { clipboard=activity.getSystemService(ClipboardManager::class.java);previous=clipboard.primaryClip }
        val eligible=previous!=null||android.os.Build.VERSION.SDK_INT>=28;var touched=false
        val original="原附件 100% %@\n".toByteArray();val uri=file("发送中… 100% %@.txt",original);val key=session.targetKey
        try {
            if(eligible) { main { clipboard.setPrimaryClip(ClipData.newRawUri("Owned file paste",uri));touched=true } }
            ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("paste-files").performScrollTo().assertTextContains("Paste images or files").performClick()
            if(eligible) {
                ui.waitUntil(15_000) { !session.importing&&session.attachments.size==1 }
                val file=session.attachments.single();assertEquals("发送中… 100% %@.txt",file.manifest.name)
                assertArrayEquals(original,runBlocking { NativeDraftFiles(owned.storage).bytes(file.manifest) })
                ui.waitUntil(10_000) { owned.storage.read("metadata")?.contains(file.manifest.id)==true }
                owned.nodeShot(ui.onNodeWithTag("product-surface"),"explicit-original-file-paste")
            } else {
                ui.onNodeWithText("No readable file or image is available. Use Share or Open in Another App in the source app.").assertExists()
                assertTrue(session.attachments.isEmpty())
            }
            assertEquals(key,session.targetKey);assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
            File(owned.folder,"explicit-native-clipboard-proof.json").writeText(JSONObject().put("systemClipboardAttempted",eligible).put("localFileBytesMatched",eligible)
                .put("api26EmptyClipboardProtected",!eligible).put("noUploadOrSubmit",true).put("originalDraftPreserved",true).toString())
        }finally { main { if(touched) { if(previous!=null)clipboard.setPrimaryClip(previous!!)else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() } } }
    }
    @Test fun attachmentMenuClosesOnTargetChangeWithoutApplyingToNewDraft() {
        connect();val firstKey=session.targetKey;ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("paste-files").performScrollTo().assertExists()
        main { session.selectThread(session.threads.single { it.id=="second" });session.text="第二草稿 100% %@" }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("paste-files").fetchSemanticsNodes().isEmpty() }
        assertTrue(session.attachments.isEmpty());assertEquals("第二草稿 100% %@",session.text)
        main { session.selectThread(session.threads.single { it.id=="first" }) };assertEquals(firstKey,session.targetKey);assertEquals("原草稿 100% %@",session.text)
        assertTrue(session.attachments.isEmpty());assertTrue(session.operationRecords.isEmpty())
    }
}
