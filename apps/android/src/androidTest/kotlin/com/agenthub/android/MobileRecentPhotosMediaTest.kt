package com.agenthub.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Owned emulator only; exact synthetic MediaStore rows, native strip and encrypted draft, never model input. */
class MobileRecentPhotosMediaTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun boundedNativeLibraryRefreshesAndExplicitRecentTapPreparesOnlyOriginalLocalDraft() {
        require(Build.VERSION.SDK_INT>=29&&Build.HARDWARE in listOf("ranchu","goldfish"))
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity;val i=InstrumentationRegistry.getInstrumentation()
        require(activity.packageName=="com.easyai.agenthub.android.acceptance")
        val library=NativeRecentPhotos(activity);assertEquals(NativePhotoAccess.FULL,library.access())
        val metadata=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("content query --uri content://media/external/images/media --projection _id")).use { it.bufferedReader().readText() }
        assertTrue("Do not mix or remove unknown media from this owned SDK",metadata.trim()=="No result found.")
        val owned=OwnedControlAcceptance(activity,"recent-photos");val session=owned.session;val resolver=activity.contentResolver
        val target=JSONObject().put("agentId","codex").put("hostId","recent-host").put("workspaceId","recent-workspace").put("runtimeId","recent-runtime")
        val server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","recent-host").put("name","Recent original target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","original").put("hostId","recent-host").put("title","Original").put("state","idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","recent-host").put("threadId","original").put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        val nonce=UUID.randomUUID().toString();val photos=mutableListOf<Uri>()
        fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
        fun seed(index: Int): Uri {
            val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"recent-$nonce-$index.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AgentHubRecent-$nonce");put(MediaStore.Images.Media.IS_PENDING,1) }
            val uri=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values));photos.add(uri)
            val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(index*5%256,60,190)) }
            try { resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }finally { bitmap.recycle() }
            assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null));return uri
        }
        try {
            main { session.resetAcceptance();session.changeLanguage("en");session.connect("Recent original target",server.endpoint,"");session.selectThread(session.threads.single());session.text="最近图片原草稿 100% %@" }
            ui.waitUntil(15_000) { session.canCompose&&!session.historyLoading }
            repeat(35) { seed(it) }
            val metadataRows=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DATE_ADDED),null,null,null)!!.use { cursor ->
                buildList { while(cursor.moveToNext())add(cursor.getLong(0) to cursor.getLong(1)) }
            }
            assertEquals(photos.map { it.lastPathSegment!!.toLong() }.toSet(),metadataRows.map { it.first }.toSet())
            val expected=metadataRows.sortedWith(compareByDescending<Pair<Long,Long>> { it.second }.thenByDescending { it.first }).take(30).map { it.first.toString() }
            val snapshot=runBlocking { library.snapshot() };assertEquals(30,snapshot.size)
            assertEquals("Independent DATE_ADDED then stable ID descending oracle",expected,snapshot.map { it.uri.lastPathSegment })
            File(owned.folder,"recent-order-oracle.json").writeText(JSONObject().put("metadata",JSONArray(metadataRows.map { JSONObject().put("id",it.first).put("dateAdded",it.second) })).put("expectedIds",JSONArray(expected)).toString())
            val thumbnail=checkNotNull(runBlocking { library.thumbnail(snapshot.first()) });try { assertTrue(thumbnail.width<=180&&thumbnail.height<=180&&thumbnail.width>0&&thumbnail.height>0) }finally { thumbnail.recycle() }
            ui.onNodeWithTag("composer-add").performClick();ui.waitUntil(20_000) { ui.onAllNodesWithTag("recent-photo-"+photos.last().lastPathSegment).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(session.attachments.isEmpty());assertEquals("最近图片原草稿 100% %@",session.text);owned.dialogShot("recent-thirty-native-strip")
            val fresh=seed(35)
            ui.waitUntil(20_000) { ui.onAllNodesWithTag("recent-photo-"+fresh.lastPathSegment).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(session.attachments.isEmpty());ui.onNodeWithTag("recent-photo-"+fresh.lastPathSegment).performScrollTo().assertIsDisplayed().performClick()
            ui.waitUntil(20_000) { !session.importing&&session.attachments.size==1 }
            val file=session.attachments.single();val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file.manifest) }
            val actual=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size));try { assertEquals(32,actual.width);assertEquals(24,actual.height);assertEquals(Color.rgb(175,60,190),actual.getPixel(10,10)) }finally { actual.recycle() }
            assertTrue(runBlocking { session.confirmInboxFiles(session.attachmentImportDestination(),listOf(file.manifest)) })
            assertEquals("最近图片原草稿 100% %@",session.text);ui.onAllNodesWithTag("attachment-panel").assertCountEquals(0)
            assertEquals(30,runBlocking { library.snapshot() }.size)
            // A removed original source cannot fall back to another row or draft.
            assertEquals(1,resolver.delete(snapshot.last().uri,null,null));photos.removeAll { it.lastPathSegment==snapshot.last().uri.lastPathSegment }
            try { runBlocking { library.input(snapshot.last()) };fail("Removed original source must fail") }catch(expected: NativeFileIntakeFailure) { }
            assertEquals(listOf(file),session.attachments)
            File(owned.folder,"recent-library-proof.json").writeText(JSONObject().put("boundedRows",30).put("syntheticRows",36).put("refreshObserved",true).put("nativeExplicitTap",true).put("originalPixelAndMetadataMatched",true).put("noUploadOrSubmit",true).put("sourceOwnership","same acceptance App UID MediaStore; independent source system picker separately verified").toString())
        }finally {
            for(uri in photos)assertEquals("Delete only this method's exact inserted URI",1,resolver.delete(uri,null,null))
            main { session.leaveForeground() };owned.verifyDefault();assertTrue(session.operationRecords.isEmpty());assertTrue(server.requests.all { it.method=="GET" });server.close()
        }
    }
}
