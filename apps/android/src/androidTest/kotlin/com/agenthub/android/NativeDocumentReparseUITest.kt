package com.agenthub.android

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Production reader with owned local files, touch gestures and visible/TextView witnesses.
 * No MainActivity/session, resolver callback, network, clipboard, production switches or DEV claim.
 */
class NativeDocumentReparseUITest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val opened=mutableStateOf<MobileSession.PreparedMessageResource?>(null)
    private val run=UUID.randomUUID().toString()
    private lateinit var folder: File
    private var ownsFolder=false
    private val files=mutableListOf<File>()
    private val source="# Owned reparse\n\n"+(0 until 240).joinToString("\n\n") {
        "paragraph-${it.toString().padStart(3,'0')} Owned Markdown reading witness with enough text to scroll through the production reader."
    }
    private val originalIdentity get()=identity("target-$run","message-$run")
    private fun identity(target: String,message: String)=PreviewReadingIdentity.message(target,message,"docs/owned.md")

    @Before fun setup() {
        folder=File(ui.activity.cacheDir,"native-document-reparse-$run")
        check(folder.mkdir());ownsFolder=true
        ui.setContent {
            ProductTextProvider("en") { MaterialTheme {
                opened.value?.let { resource -> key(resource) {
                    NativeMarkdownFilePreview(resource,current={opened.value===resource},
                        onDismiss={if(opened.value===resource)opened.value=null},onRetry=null)
                } }
            } }
        }
    }
    @After fun cleanup() {
        ui.runOnIdle {opened.value=null}
        ui.waitForIdle()
        files.forEach {file->assertTrue("Remove only this test's owned Markdown copy",file.delete()||!file.exists())}
        if(ownsFolder) {
            assertTrue("No unexpected files may be removed",folder.listFiles().orEmpty().isEmpty())
            assertTrue(folder.delete()||!folder.exists())
        }
    }
    private fun resource(identity: String=originalIdentity,text: String=source): MobileSession.PreparedMessageResource {
        val id=UUID.randomUUID().toString()
        val file=File(folder,"copy-$id.md");files.add(file);file.writeText(text)
        val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        val remote=MobileFile(id,"owned.md","text/markdown",file.length(),"owned-host","owned-thread",hash)
        return MobileSession.PreparedMessageResource(remote.name,remote.mediaType,file,sourceFile=remote,readingIdentity=identity)
    }
    private fun open(resource: MobileSession.PreparedMessageResource) {
        ui.runOnIdle {check(opened.value==null);opened.value=resource}
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("file-preview-content").fetchSemanticsNodes().isNotEmpty()||
                ui.onAllNodesWithTag("file-preview-source").fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitForIdle()
        ui.onNodeWithTag("file-preview-screen").assertIsDisplayed()
    }
    private fun close() {
        ui.onNodeWithTag("file-preview-close").performTouchInput {click()}
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty()}
        ui.waitForIdle() // Disposal saves the actual reader position before the next instance loads.
        ui.runOnIdle {assertNull(opened.value)}
    }
    private data class VisiblePosition(val first: Int,val second: Int,val secondTop: Float)
    private fun renderedPosition(): VisiblePosition {
        val viewport=ui.onNodeWithTag("file-preview-content").fetchSemanticsNode().boundsInRoot
        val blocks=ui.onAllNodes(SemanticsMatcher("native Markdown blocks") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("file-preview-block-[0-9]+"))==true
        },useUnmergedTree=true).fetchSemanticsNodes().filter {
            it.boundsInRoot.bottom>viewport.top+1f&&it.boundsInRoot.top<viewport.bottom-1f
        }.sortedBy {it.config[SemanticsProperties.TestTag].substringAfterLast('-').toInt()}
        assertTrue("At least two actual paragraphs must be visible",blocks.size>=2)
        fun index(i: Int)=blocks[i].config[SemanticsProperties.TestTag].substringAfterLast('-').toInt()
        return VisiblePosition(index(0),index(1),blocks[1].boundsInRoot.top-viewport.top)
    }
    private fun assertRenderedPosition(expected: VisiblePosition) {
        val now=renderedPosition()
        assertEquals("Same leading visible block after a fresh parse",expected.first,now.first)
        assertEquals(expected.second,now.second)
        assertEquals("Same visible paragraph pixel offset",expected.secondTop,now.secondTop,2f)
    }
    private fun scrollRendered(): VisiblePosition {
        repeat(3) {ui.onNodeWithTag("file-preview-content").performTouchInput {swipeUp()};ui.waitForIdle()}
        return renderedPosition().also {assertTrue("Touch must move beyond the opening heading",it.first>0)}
    }
    private fun sourceOffset(): Int {
        var offset=-1
        onView(isAssignableFrom(TextView::class.java)).inRoot(isDialog()).check {view,error ->
            if(error!=null)throw error
            val text=view as TextView
            assertEquals("Observe only the owned source document",source.substringBefore('\n'),text.text.toString().substringBefore('\n'))
            offset=text.scrollY
        }
        return offset
    }
    private fun scrollSource(): Int {
        repeat(3) {ui.onNodeWithTag("file-preview-source").performTouchInput {swipeUp()};ui.waitForIdle()}
        return sourceOffset().also {assertTrue("Touch must scroll the native source TextView",it>0)}
    }
    private fun assertSourcePosition(offset: Int) {
        ui.onNodeWithTag("file-preview-source").assertIsDisplayed()
        ui.onNodeWithTag("file-preview-raw").assertIsSelected()
        assertEquals("Same native source scroll offset after fresh local copy",offset,sourceOffset())
    }

    @Test fun freshResourceAndLocalCopyRestoreRenderedPositionAndNativeSourceModeAndScroll() {
        val first=resource();open(first)
        val position=scrollRendered();close()
        val second=resource()
        assertNotEquals(first.sourceFile!!.id,second.sourceFile!!.id)
        assertNotEquals(first.local.canonicalPath,second.local.canonicalPath)
        assertEquals(first.readingIdentity,second.readingIdentity)
        assertEquals(first.sourceFile!!.sha256,second.sourceFile!!.sha256)
        open(second);assertRenderedPosition(position)
        ui.onNodeWithTag("file-preview-raw").performTouchInput {click()}
        val offset=scrollSource();close()
        val third=resource()
        assertNotEquals(second.sourceFile!!.id,third.sourceFile!!.id)
        assertNotEquals(second.local.canonicalPath,third.local.canonicalPath)
        open(third);assertSourcePosition(offset)
        ui.onNodeWithTag("file-preview-rendered").performTouchInput {click()}
        ui.waitForIdle();assertRenderedPosition(position);close()
    }

    @Test fun changedDigestTargetOrMessageStartsAtTopWithoutErasingOriginalReadingPosition() {
        open(resource());val position=scrollRendered()
        ui.onNodeWithTag("file-preview-raw").performTouchInput {click()}
        val offset=scrollSource();close()
        val variants=listOf(
            resource(text=source+"\n\nDifferent verified digest for the same original message."),
            resource(identity=identity("other-target-$run","message-$run")),
            resource(identity=identity("target-$run","other-message-$run"))
        )
        for(next in variants) {
            open(next)
            ui.onNodeWithTag("file-preview-rendered").assertIsSelected()
            ui.onNodeWithTag("file-preview-block-0").assertIsDisplayed()
            assertEquals("Changed identity/content must start at the opening block",0,renderedPosition().first)
            ui.onNodeWithTag("file-preview-raw").performTouchInput {click()}
            assertEquals("Changed identity/content must not inherit the native source offset",0,sourceOffset())
            close()
        }
        open(resource());assertSourcePosition(offset)
        ui.onNodeWithTag("file-preview-rendered").performTouchInput {click()}
        ui.waitForIdle();assertRenderedPosition(position);close()
    }
}
