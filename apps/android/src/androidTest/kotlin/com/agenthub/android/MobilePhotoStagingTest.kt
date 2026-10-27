package com.agenthub.android

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real native panel and encrypted local files; owned synthetic photo URI/content sources. */
class MobilePhotoStagingTest {
    private val args get()=androidx.test.platform.app.InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation") ?: ""},{java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/staging-viewport").apply {mkdirs()}},{"photo-staging"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    private val session get()=a.session
    private val source get()=a.fake!!
    private var restored: MobileSession?=null
    @Before fun setup() {
        ui.waitUntil(30_000) {!ui.activity.session.restoring}
        a=RecentPhotoHintAcceptance(ui.activity,{condition->ui.waitUntil(20_000,condition)})
        a.main {source.publish((1..7).map {source.photo(it)})}
        ui.waitUntil(20_000) {a.library.recent.size==7}
    }
    @After fun finish() {if(::a.isInitialized) {a.main {restored?.leaveForeground()};a.finish()}}
    private fun open() {ui.onNodeWithTag("composer-add").performClick();ui.waitUntil(10_000) {ui.onAllNodesWithTag("recent-photo-1").fetchSemanticsNodes().isNotEmpty()}}
    private fun choose(id: Int) {ui.onNodeWithTag("recent-attachments-strip").performScrollToNode(hasTestTag("recent-photo-select-$id"));ui.onNodeWithTag("recent-photo-select-$id").performClick()}
    private fun add() {ui.onNodeWithTag("photo-selection-add").performClick()}
    private fun closed()=ui.waitUntil(20_000) {ui.onAllNodesWithTag("attachment-panel").fetchSemanticsNodes().isEmpty()&&!session.importing}
    @Test fun imageTapPreviewsIndependentOrderedCirclesAndAddsInChosenOrder() {
        open();choose(3);choose(1)
        ui.onNodeWithTag("recent-photo-2").performClick();ui.waitUntil(10_000) {ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty())
        ui.onNodeWithTag("photo-preview-select").performClick();ui.onNodeWithTag("photo-preview-select").assertTextContains("3",substring=true)
        a.owned.dialogShot("ordered-preview-selection")
        ui.onNodeWithTag("attachment-image-done").performClick();choose(1);choose(1)
        add();closed();assertEquals(listOf(3,2,1),session.attachments.map {Uri.parse(it.photoSourceId).lastPathSegment!!.toInt()})
        session.attachments.zip(listOf(3,2,1)).forEach {(file,id)->a.assertPixels(file.manifest,id)}
        val destination=session.attachmentImportDestination();a.main {assertTrue(session.confirmPhotoFiles(destination,session.attachments))}
        assertEquals(3,source.inputCalls.get());assertEquals("最近图片原稿 100% %@ 😀",session.text)
    }
    @Test fun cancellingSelectionOrPreviewDoesNotPrepareOrChangeDraft() {
        val text=session.text;open();choose(1);choose(2)
        ui.onNodeWithTag("recent-photo-3").performClick();ui.onNodeWithTag("attachment-image-done").performClick()
        ui.onNodeWithTag("attachment-panel-close").performClick();closed()
        assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty());assertEquals(text,session.text)
        open();ui.onNodeWithTag("photo-selection-add").assertIsNotEnabled();ui.onNodeWithTag("attachment-panel-close").performClick();closed()
    }
    @Test fun partialFailureKeepsOnlyUnaddedSelectionAndRetryDoesNotDuplicateSuccessfulFiles() {
        source.failOnceId=source.photo(2).id
        open();choose(1);choose(2);choose(3);add()
        ui.waitUntil(20_000) {!session.importing&&ui.onAllNodesWithTag("photo-selection-error").fetchSemanticsNodes().isNotEmpty()&&ui.onNodeWithTag("photo-selection-add").fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}.any {it.text.contains("(1)")}}
        assertNull("Batch failure stays inline and cannot block the panel with a global alert",session.error)
        assertEquals(listOf("1","3"),session.attachments.map {Uri.parse(it.photoSourceId).lastPathSegment});val original=session.attachments.toList()
        ui.onNodeWithTag("photo-selection-add").assertTextContains("1",substring=true);a.owned.dialogShot("partial-keeps-one-selected")
        add();closed();assertEquals(original,session.attachments.take(2));assertEquals(listOf("1","3","2"),session.attachments.map {Uri.parse(it.photoSourceId).lastPathSegment})
        assertEquals(4,source.inputCalls.get());assertNull(source.failOnceId)
    }
    @Test fun fiveItemSelectionQuotaPreservesExistingFilesAndAlreadyAddedSurvivesLocalRestore() {
        a.main {repeat(4){session.addAttachment("existing-$it.txt","text/plain","old $it".toByteArray())}}
        val originals=session.attachments.toList();open();choose(1);choose(2)
        ui.onNodeWithTag("photo-selection-error").assertExists();ui.onNodeWithTag("photo-selection-add").assertTextContains("1",substring=true)
        add();closed();assertEquals(originals,session.attachments.take(4));assertEquals(5,session.attachments.size)
        val photo=session.attachments.last();assertEquals(source.photo(1).id,photo.photoSourceId)
        a.main {assertTrue(session.confirmPhotoFiles(session.attachmentImportDestination(),listOf(photo)))}
        open();ui.onNodeWithTag("recent-photo-select-1").assertIsNotEnabled().assertIsOn();ui.onNodeWithTag("recent-photo-1").performClick()
        ui.onNodeWithTag("photo-preview-select").assertIsNotEnabled();ui.onNodeWithTag("attachment-image-done").performClick();ui.onNodeWithTag("attachment-panel-close").performClick();closed()
        a.main {session.leaveForeground();restored=MobileSession(a.owned.storage).also {it.restore()}}
        ui.waitUntil(20_000) {restored?.restoring==false};assertEquals(session.attachments,restored!!.attachments)
        assertEquals(photo.photoSourceId,restored!!.attachments.last().photoSourceId)
        val metadata=a.owned.storage.metadata()!!;val rows=metadata.getJSONObject("draftFiles").getJSONArray(session.targetKey)
        assertFalse(rows.getJSONObject(rows.length()-1).has("photoSourceId"));assertEquals(photo.photoSourceId,metadata.getJSONObject("photoSources").getString(photo.manifest.id))
    }
    @Test fun originalAddCanFinishAfterNavigationWithoutMutatingNewDraftOrReopeningPanel() {
        val gate=CompletableDeferred<Unit>();source.gate=gate;open();choose(1);add()
        ui.waitUntil(10_000) {source.inputCalls.get()==1};val original=session.threads.first {it.id=="first"}
        a.main {session.selectThread(session.threads.first {it.id=="second"})};ui.waitUntil(10_000) {!session.historyLoading}
        a.main {session.text="New independent draft";gate.complete(Unit)};ui.waitUntil(20_000) {!session.importing}
        assertTrue(session.attachments.isEmpty());assertEquals("New independent draft",session.text);ui.onAllNodesWithTag("attachment-panel").assertCountEquals(0)
        a.main {session.selectThread(original)};ui.waitUntil(10_000) {!session.historyLoading};assertEquals(1,session.attachments.size);a.assertPixels(session.attachments.single().manifest,1)
    }
    @Test fun deviceLocalOriginMetadataNeverChangesWireManifestAndRejectsUnknownOrInvalidOrigins() {
        a.main {session.addAttachment("one.png","image/png",byteArrayOf(1,2,3))}
        val file=session.attachments.single();val original=file.manifest.body().toString();val withOrigin=file.copy(photoSourceId=source.photo(1).id)
        val encoded=NativePhotoOrigins.encode(listOf(withOrigin));assertEquals(withOrigin,NativePhotoOrigins.attach(file,encoded));assertEquals(original,withOrigin.manifest.body().toString())
        for(value in listOf("file:///tmp/private.png","content://other/external/images/media/1","content://media/external/images/media/01","content://media/external/images/media/1?token=secret")) {
            assertFalse(NativePhotoOrigins.valid(value));assertThrows(IllegalArgumentException::class.java) {NativePhotoOrigins.attach(file,JSONObject().put(file.manifest.id,value))}
        }
        assertThrows(IllegalStateException::class.java) {NativePhotoOrigins.validate(JSONObject().put("unknown",source.photo(1).id),listOf(file))}
    }
}
