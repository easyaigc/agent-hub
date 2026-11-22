package com.agenthub.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native UI cases using intercepted open callbacks; no downloads or model turns. Not executed in R1. */
class NativeRemainingMediaCardsUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun bodyMediaIsInertUntilUserOpensTheExactOriginalReference() {
        val opened=mutableListOf<String>()
        val source="![photo](/media/photo.png) ![video](/media/video.mp4) ![audio](/media/audio.mp3)"
        ui.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMessageLinkOpen provides { reference,_ -> opened.add(reference) }) {
                    NativeMessageMediaCards(NativeMarkdown.parse(source),"body")
                }
            }
        }
        ui.waitForIdle();assertTrue(opened.isEmpty())
        ui.onNodeWithTag("body-media-1-video").performClick()
        ui.runOnIdle { assertEquals(listOf("/media/video.mp4"),opened) }
        ui.onNodeWithTag("body-media-2-audio").performClick()
        ui.runOnIdle { assertEquals("/media/audio.mp3",opened.last()) }
    }
    @Test fun openedLocalThumbnailIsBoundedAndOtherMessageOwnerSeesNone() {
        val snapshots=NativeMessageMediaSnapshots()
        val image=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        ui.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMessageLinkOpen provides { _,_ -> },LocalNativeMessageMediaSnapshots provides snapshots) {
                    NativeMessageMediaCards(NativeMarkdown.parse("![photo](/media/photo.png)"),"body")
                }
            }
        }
        ui.onNodeWithTag("body-media-0-thumbnail",useUnmergedTree=true).assertDoesNotExist()
        ui.runOnIdle { snapshots.put("/media/photo.png",image) }
        ui.onNodeWithTag("body-media-0-thumbnail",useUnmergedTree=true).assertExists()
        ui.runOnIdle {
            repeat(12) { snapshots.put("/media/$it.png",image) }
            assertEquals(8,snapshots.images.size)
            assertNull(snapshots.images["/media/photo.png"])
            assertTrue(NativeMessageMediaSnapshots().images.isEmpty())
        }
    }
    @Test fun repeatedThumbnailPublicationRefreshesOrderWithoutDuplicateCapacityOrRecycling() {
        val snapshots=NativeMessageMediaSnapshots()
        val original=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        val replacement=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        ui.runOnIdle {
            repeat(8) { snapshots.put("/media/$it.png",original) }
            snapshots.put("/media/0.png",replacement)
            snapshots.put("/media/8.png",original)
            assertEquals(8,snapshots.images.size)
            assertNull(snapshots.images["/media/1.png"])
            assertSame(replacement,snapshots.images["/media/0.png"])
            repeat(3) { snapshots.put("/media/0.png",replacement) }
            assertEquals(8,snapshots.images.size)
            for(index in 9..15)snapshots.put("/media/$index.png",original)
            assertNull(snapshots.images["/media/8.png"])
            assertSame(replacement,snapshots.images["/media/0.png"])
            snapshots.put("/media/16.png",original)
            assertNull(snapshots.images["/media/0.png"])
            assertEquals(8,snapshots.images.size)
            assertFalse(original.isRecycled)
            assertFalse(replacement.isRecycled)
        }
    }
    @Test fun plainParagraphAddsNoExtraGapBetweenParentTexts() {
        val plain=NativeMarkdown.parse("Ordinary paragraph without media.")
        ui.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMessageLinkOpen provides { _,_ -> error("Plain text must not open media") }) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Before",Modifier.testTag("plain-before"))
                        NativeMessageMediaCards(plain,"plain-body")
                        Text("After",Modifier.testTag("plain-after"))
                    }
                }
            }
        }
        ui.waitForIdle()
        val before=ui.onNodeWithTag("plain-before").fetchSemanticsNode().boundsInRoot
        val after=ui.onNodeWithTag("plain-after").fetchSemanticsNode().boundsInRoot
        val expectedPixels=with(ui.density) { 8.dp.roundToPx() }.toFloat()
        assertEquals("Plain body must preserve exactly one parent 8dp spacing",expectedPixels,after.top-before.bottom,0f)
    }

}
