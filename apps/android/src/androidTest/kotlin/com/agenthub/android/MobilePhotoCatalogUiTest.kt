package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*

/** Real native catalogue/selection UI and encrypted draft, synthetic photo source only. */
class MobilePhotoCatalogUiTest {
    private val args get()=androidx.test.platform.app.InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation") ?: ""},{java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/catalogue-viewport").apply{mkdirs()}},{"photo-catalogue"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var a: RecentPhotoHintAcceptance
    private val source get()=a.fake!!
    private val session get()=a.session
    @Before fun setup() {
        ui.waitUntil(30_000) {!ui.activity.session.restoring};a=RecentPhotoHintAcceptance(ui.activity,{condition->ui.waitUntil(20_000,condition)})
        a.main {source.publish((121 downTo 1).map {source.photo(it,1000.0)})};ui.waitUntil(20_000) {a.library.recent.size==30}
    }
    @After fun finish() {if(::a.isInitialized)a.finish()}
    private fun panel() {ui.onNodeWithTag("composer-add").performClick();ui.waitUntil(10_000) {ui.onAllNodesWithTag("recent-photo-121").fetchSemanticsNodes().isNotEmpty()}}
    private fun gallery() {ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag("choose-photos"));ui.onNodeWithTag("choose-photos").performClick();ui.waitUntil(15_000) {ui.onAllNodesWithTag("catalog-photo-121").fetchSemanticsNodes().isNotEmpty()}}
    private fun nextPage() {ui.onNodeWithTag("attachment-photo-grid").performScrollToIndex(59);ui.waitUntil(15_000) {source.pageCalls.get()>=2};ui.waitForIdle()}
    @Test fun beyondRecentThirtySharesOrderedSelectionPreviewAndAddWithThePanel() {
        panel();ui.onNodeWithTag("recent-photo-select-121").performClick();gallery();nextPage()
        ui.onNodeWithTag("attachment-photo-grid").performScrollToIndex(66);ui.onNodeWithTag("catalog-photo-select-55").performClick()
        ui.onNodeWithTag("catalog-photo-55").performClick();ui.waitUntil(10_000) {ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("attachment-image-counter").assertTextEquals("67 / 120");ui.onNodeWithTag("photo-preview-select").assertTextContains("(2)",substring=true)
        assertEquals(0,source.inputCalls.get());a.owned.dialogShot("catalogue-second-page-preview")
        ui.onNodeWithTag("attachment-image-done").performClick();ui.onNodeWithTag("photo-catalog-back").performClick()
        ui.onNodeWithTag("photo-selection-add").assertTextContains("(2)",substring=true);ui.onNodeWithTag("photo-selection-add").performClick()
        ui.waitUntil(20_000) {!session.importing&&session.attachments.size==2&&ui.onAllNodesWithTag("attachment-panel").fetchSemanticsNodes().isEmpty()}
        assertEquals(listOf("121","55"),session.attachments.map {android.net.Uri.parse(it.photoSourceId).lastPathSegment});a.assertPixels(session.attachments[0].manifest,121);a.assertPixels(session.attachments[1].manifest,55)
    }
    @Test fun backKeepsSharedSelectionAndCancelDiscardsItWithoutImport() {
        panel();gallery();ui.onNodeWithTag("catalog-photo-select-121").performClick();ui.onNodeWithTag("photo-catalog-back").performClick()
        ui.onNodeWithTag("photo-selection-add").assertTextContains("(1)",substring=true);gallery();ui.onNodeWithTag("catalog-photo-select-121").assertIsOn()
        ui.onNodeWithTag("photo-catalog-cancel").performClick();ui.waitUntil(10_000) {ui.onAllNodesWithTag("attachment-panel").fetchSemanticsNodes().isEmpty()}
        assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",session.text)
    }
    @Test fun readableLibraryRetainsSystemPickerAndCancellingDoesNotImportStagedPhotos() {
        val expected=NativeSelectionKind.PHOTO.contract().createIntent(ui.activity,Unit)
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();var launches=0
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent?): android.app.Instrumentation.ActivityResult? {
                if(intent==null||intent.action!=expected.action||intent.type!=expected.type||intent.component!=expected.component)return null
                launches++;assertTrue(session.photoPicker!!.pending!!.menuDismissed)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
        }
        i.addMonitor(monitor)
        try {
            panel();ui.onNodeWithTag("recent-photo-select-121").performClick()
            ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag("choose-system-photos"))
            ui.onNodeWithTag("choose-system-photos").assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitUntil(20_000) {launches==1&&session.photoPicker?.pending==null&&!session.attachmentRecoveryBusy}
            ui.onAllNodesWithTag("attachment-panel").assertCountEquals(0);ui.onAllNodesWithTag("attachment-photo-catalog").assertCountEquals(0)
            assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty());assertEquals("最近图片原稿 100% %@ 😀",session.text);assertNull(session.error)
        }finally {i.removeMonitor(monitor)}
    }
    @Test fun nextPageFailureRetainsFirstPageAndExplicitRetryContinuesWithoutImport() {
        source.pageFailOnceAfter=62;panel();gallery();nextPage()
        ui.waitUntil(15_000) {ui.onAllNodesWithTag("photo-catalog-retry").fetchSemanticsNodes().isNotEmpty()};a.owned.dialogShot("catalogue-page-error")
        assertEquals(0,source.inputCalls.get());assertTrue(session.attachments.isEmpty());val retry=ui.onNodeWithTag("photo-catalog-retry").assertIsDisplayed()
        assertTrue("Retry retains a fully visible 48dp touch target",retry.fetchSemanticsNode().boundsInRoot.height>=with(ui.density){48.dp.toPx()}-1)
        retry.performTouchInput {click()};ui.waitUntil(15_000) {source.pageCalls.get()>=3};ui.waitForIdle()
        ui.onNodeWithTag("attachment-photo-grid").performScrollToIndex(66);ui.onNodeWithTag("catalog-photo-55").assertIsDisplayed();ui.onNodeWithTag("photo-catalog-cancel").performClick()
        assertTrue(session.attachments.isEmpty());assertNull(session.error)
    }
}
