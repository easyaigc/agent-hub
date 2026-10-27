package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Native viewer lifecycle/gesture fixture; never a real photo-library or model substitute. */
class MobileAttachmentImageViewerTest {
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation") ?: ""},{java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/image-viewport").apply{mkdirs()}},{"image-viewer"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: ()->Unit)=ui.runOnIdle(block)
    @Before fun setup() {
        ui.waitUntil(30_000) {!ui.activity.session.restoring}
        main {owned=OwnedControlAcceptance(ui.activity,"image-viewer");session.resetAcceptance();session.changeLanguage("en")}
    }
    @After fun finish() { if(::owned.isInitialized)main {session.leaveForeground();owned.verifyDefault();assertTrue(session.operationRecords.isEmpty())} }
    private fun image()=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
    private fun ready()=ui.waitUntil(10_000) {ui.onAllNodesWithTag("attachment-image-pixels").fetchSemanticsNodes().isNotEmpty()}
    @Test fun onlyActiveOriginalLoadsAndPageChangeResetsZoom() {
        val calls=intArrayOf(0,0);val bitmap=image()
        main {ui.activity.setContent { NativeAttachmentImageViewer(listOf(NativeAttachmentImage("a","first.png",{calls[0]++;bitmap}),NativeAttachmentImage("b","second.png",{calls[1]++;bitmap})),"a",{true},{}) }}
        ready();assertArrayEquals(intArrayOf(1,0),calls)
        ui.onNodeWithTag("attachment-image-zoom-in").performClick();ui.onNodeWithTag("attachment-image-zoom").assertTextEquals("150%")
        owned.dialogShot("image-zoomed")
        ui.onNodeWithTag("attachment-image-page-b").performClick();ready()
        ui.onNodeWithTag("attachment-image-name").assertTextEquals("second.png");ui.onNodeWithTag("attachment-image-zoom").assertTextEquals("100%")
        assertArrayEquals(intArrayOf(1,1),calls);owned.dialogShot("image-second-page")
    }
    @Test fun doubleTapAndPinchZoomThenSwipeAtOneChangesPage() {
        val bitmap=image()
        main {ui.activity.setContent {NativeAttachmentImageViewer(listOf(NativeAttachmentImage("a","first.png",{bitmap}),NativeAttachmentImage("b","second.png",{bitmap})),"a",{true},{})}}
        ready();ui.onNodeWithTag("attachment-image-pixels").performTouchInput {doubleClick()}
        ui.onNodeWithTag("attachment-image-zoom").assertTextEquals("200%")
        ui.onNodeWithTag("attachment-image-pixels").performTouchInput {doubleClick()}
        ui.onNodeWithTag("attachment-image-zoom").assertTextEquals("100%")
        ui.onNodeWithTag("attachment-image-pixels").performTouchInput {pinch(androidx.compose.ui.geometry.Offset(center.x-20,center.y),androidx.compose.ui.geometry.Offset(center.x+20,center.y),topLeft,bottomRight)}
        assertNotEquals("100%",ui.onNodeWithTag("attachment-image-zoom").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text)
        // Reset through explicit out control before checking pager's single-finger ownership.
        repeat(10) {if(ui.onAllNodesWithTag("attachment-image-zoom-out").fetchSemanticsNodes().isNotEmpty())try {ui.onNodeWithTag("attachment-image-zoom-out").performClick()}catch(_: AssertionError){}}
        ui.onNodeWithTag("attachment-image-zoom").assertTextEquals("100%")
        ui.onNodeWithTag("attachment-image-pixels").performTouchInput {swipeLeft()}
        ui.waitUntil(10_000) {ui.onNodeWithTag("attachment-image-name").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text=="second.png"}
    }
    @Test fun failedOriginalReadRetriesAndCloseRejectsLateNonCancellablePixels() {
        var calls=0;val late=CompletableDeferred<Bitmap?>();val visible=mutableStateOf(true)
        main {ui.activity.setContent {if(visible.value)NativeAttachmentImageViewer(listOf(NativeAttachmentImage("a","original.png",{calls++;if(calls==1)throw IllegalStateException("fixture");withContext(NonCancellable){late.await()}})),"a",{visible.value},{visible.value=false})}}
        ui.waitUntil(10_000) {ui.onAllNodesWithTag("attachment-image-error").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("attachment-image-retry").performClick();ui.waitUntil(10_000) {calls==2}
        ui.onNodeWithTag("attachment-image-done").performClick();main {late.complete(image())}
        ui.onAllNodesWithTag("attachment-image-viewer").assertCountEquals(0);assertEquals(2,calls)
    }
    @Test fun retainedFooterActionCannotAffectNewPageOrClosedWindow() {
        val bitmap=image();val actions=mutableMapOf<String,()->Unit>();val removed=mutableListOf<String>();val visible=mutableStateOf(true)
        main {ui.activity.setContent {if(visible.value)NativeAttachmentImageViewer(listOf(NativeAttachmentImage("a","first.png",{bitmap}),NativeAttachmentImage("b","second.png",{bitmap})),"a",{visible.value},{visible.value=false}) { item,valid ->
            val action={if(valid())removed.add(item.id);Unit};SideEffect {actions[item.id]=action}
            TextButton(onClick=action,modifier=Modifier.testTag("fixture-image-remove")){Text("Remove")}
        }}}
        ready();val original=actions.getValue("a");ui.onNodeWithTag("attachment-image-page-b").performClick();ready()
        main {original()};assertTrue(removed.isEmpty())
        ui.onNodeWithTag("fixture-image-remove").performClick();assertEquals(listOf("b"),removed)
        val second=actions.getValue("b");ui.onNodeWithTag("attachment-image-done").performClick();main {second()};assertEquals(listOf("b"),removed)
    }
    @Test fun boundedDecodeRejectsInvalidAndLimitsLargePixels()=runBlocking {
        assertNull(NativeAttachmentImageDecoder.decode("invalid".toByteArray()))
        val bitmap=Bitmap.createBitmap(5000,1000,Bitmap.Config.ARGB_8888);val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val decoded=checkNotNull(NativeAttachmentImageDecoder.decode(bytes));assertTrue(decoded.width<=4096);assertTrue(decoded.width.toLong()*decoded.height<=8_000_000)
    }
}
