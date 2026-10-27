package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Reads an explicitly selected completed original model reply. Never submits a model input. */
class MobileMarkdownFileLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule(
        {"portrait"},{File(instrumentation.targetContext.filesDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/markdown-live-viewport").apply {mkdirs()}},{"markdown-live"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    private fun links(node: Node): List<Link> =buildList {
        if(node is Link)add(node)
        NativeMarkdown.children(node).forEach {addAll(links(it))}
    }
    private fun rawView(check: (android.widget.TextView)->Unit) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TextView::class.java))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check {view,error ->
                if(error!=null)throw error
                assertEquals(ui.activity.packageName,view.context.packageName);check(view as android.widget.TextView)
            }
    }
    private fun openOriginal(message: MobileMessage,reference: String) {
        val blocks=NativeMarkdown.children(NativeMarkdown.parse(message.text))
        val index=blocks.indexOfFirst {it is Paragraph&&links(it).any {link->link.destination==reference}}
        assertTrue("The original model reply must contain the exact file link",index>=0)
        val linkIndex=links(blocks[index]).indexOfFirst {it.destination==reference}
        val tag="markdown-${message.id}-$index"
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-"+message.id))
        ui.waitUntil(15_000) {ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1}
        val node=ui.onNodeWithTag(tag).performScrollTo();var layout: TextLayoutResult?=null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get->val result=mutableListOf<TextLayoutResult>();get(result);layout=result.single()}
        val text=checkNotNull(layout).layoutInput.text
        val range=text.getLinkAnnotations(0,text.length).single {(it.item as? LinkAnnotation.Clickable)?.tag=="$tag:$linkIndex"}
        node.performTouchInput {click(checkNotNull(layout).getBoundingBox(range.start).center)}
    }
    @Test fun originalModelMarkdownFileReaderCopiesSearchesSharesAndRestoresPosition() {
        val name=args.getString("markdownLiveFixture")
        Assume.assumeTrue("Requires one explicitly selected original completed model file reply",name!=null)
        require(name!!.matches(Regex("markdown-read-[a-f0-9]{8}\\.json")))
        ui.waitUntil(30_000) {!ui.activity.session.restoring}
        val activity=ui.activity;val owned=OwnedControlAcceptance(activity,"markdown-file-live");val session=owned.session
        val fixture=JSONObject(File(activity.filesDir,"acceptance/$name").readText())
        val token=fixture.getString("token");val thread=fixture.getString("threadId");val messageId=fixture.getString("messageId")
        val expectedSha=fixture.getString("sourceSha256");val clipboard=main {activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager}
        var oldClipboard: ClipData?=null;var previous: MainActivity.AcceptanceSessionState?=null;var shares=0
        main {oldClipboard=clipboard.primaryClip}
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): android.app.Instrumentation.ActivityResult? {
                if(intent?.action !in listOf(Intent.ACTION_CHOOSER,"miui.intent.action.MIUI_CHOOSER"))return null
                @Suppress("DEPRECATION")val send=intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
                @Suppress("DEPRECATION")val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return null
                assertEquals(activity.packageName+".resources",uri.authority)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                assertEquals(uri,send.clipData!!.getItemAt(0).uri);assertFalse(send.hasExtra(android.provider.Browser.EXTRA_HEADERS))
                val bytes=activity.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
                assertEquals(expectedSha,hash(bytes));assertEquals(fixture.getInt("sourceBytes"),bytes.size);shares++
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            main {
                session.resetAcceptance(NativeHostReads {token},NativeCodexCommands {token});session.changeLanguage("en");session.changeAppearance("light")
                previous=activity.adoptSessionForAcceptance(session);session.connect("Owned original Markdown read",fixture.getString("endpoint"),token)
            }
            ui.waitUntil(30_000) {!session.loading&&session.threads.any {it.id==thread}}
            main {session.selectThread(session.threads.single {it.id==thread})}
            ui.waitUntil(30_000) {!session.historyLoading&&session.messages.any {it.id==messageId}}
            assertEquals(fixture.getString("hostId"),session.selectedClient.id)
            assertTrue(sameScope(HostReadCodec.target(fixture.getJSONObject("target")),session.selectedClient.scope))
            val message=session.messages.single {it.id==messageId}
            assertEquals(fixture.getString("messageTextSha256"),hash(message.text.toByteArray()))
            val resource=message.attachments.single {it.sha256==expectedSha}
            assertEquals(fixture.getString("resourceName"),resource.name)
            main {session.text="Unsent original Markdown draft 100% %@"}
            val originalFiles=session.attachments
            openOriginal(message,fixture.getString("reference"))
            ui.waitUntil(30_000) {ui.onAllNodesWithTag("file-preview-content").fetchSemanticsNodes().size==1}
            ui.onNodeWithTag("file-preview-name").assertTextEquals(resource.name)
            val content=ui.onNodeWithTag("file-preview-content")
            content.performScrollToIndex(fixture.getInt("tableBlock"))
            val table=ui.onNodeWithTag("file-preview-table-scroll-"+fixture.getInt("tableBlock"))
            val horizontal=table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(horizontal.maxValue()>0)
            table.performSemanticsAction(SemanticsActions.ScrollBy) {action->assertTrue(action(500f,0f))}
            ui.waitUntil(5_000) {horizontal.value()>0}
            content.performSemanticsAction(SemanticsActions.ScrollBy) {action->assertTrue(action(0f,350f))}
            ui.waitForIdle()
            val originalPosition=content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            owned.dialogShot("original-model-markdown-wide-table")
            ui.onNodeWithTag("file-preview-close").performClick()
            ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty()}
            openOriginal(message,fixture.getString("reference"))
            ui.waitUntil(15_000) {runCatching {
                val position=ui.onNodeWithTag("file-preview-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                kotlin.math.abs(originalPosition-position)<.02f
            }.getOrDefault(false)}
            ui.onNodeWithTag("file-preview-outline").performClick()
            ui.onNodeWithTag("file-preview-heading-final-section").performScrollTo().performClick()
            ui.onNodeWithText(fixture.getString("endMarker")).assertIsDisplayed();owned.dialogShot("original-model-markdown-tail")
            ui.onNodeWithTag("file-preview-close").performClick()
            ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty()}
            openOriginal(message,fixture.getString("reference"))
            ui.waitUntil(15_000) {runCatching {ui.onNodeWithText(fixture.getString("endMarker")).assertIsDisplayed();true}.getOrDefault(false)}
            ui.onNodeWithTag("file-preview-search").performClick();ui.onNodeWithTag("file-preview-query").performTextInput(fixture.getString("query"))
            ui.waitUntil(10_000) {runCatching {ui.onNodeWithTag("file-preview-hit-count").assertTextEquals("1/2");true}.getOrDefault(false)}
            ui.onNodeWithTag("file-preview-next-hit").performClick();ui.onNodeWithTag("file-preview-hit-count").assertTextEquals("2/2")
            ui.onNodeWithTag("file-preview-search").performClick()
            ui.onNodeWithTag("file-preview-raw").performClick();var sourceOffset=0
            rawView {it.scrollTo(0,500);sourceOffset=it.scrollY;assertTrue(sourceOffset>0)}
            ui.onNodeWithTag("file-preview-rendered").performClick();ui.onNodeWithTag("file-preview-raw").performClick();ui.waitForIdle()
            rawView {assertEquals(sourceOffset,it.scrollY)}
            ui.onNodeWithTag("file-preview-copy-source").performClick()
            assertEquals(expectedSha,hash(main {clipboard.primaryClip!!.getItemAt(0).text.toString()}.toByteArray()))
            owned.dialogShot("original-model-markdown-exact-source")
            ui.onNodeWithTag("file-preview-share").performClick();ui.waitUntil(5_000) {shares==1}
            ui.onNodeWithTag("file-preview-close").performClick()
            ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty()}
            openOriginal(message,fixture.getString("plainReference"))
            ui.waitUntil(20_000) {ui.onAllNodesWithTag("resource-preview-text").fetchSemanticsNodes().size==1}
            ui.onNodeWithTag("resource-preview-text").assertTextEquals(fixture.getString("plainText"));owned.dialogShot("original-model-plain-file-fallback")
            assertEquals("Unsent original Markdown draft 100% %@",session.text);assertEquals(originalFiles,session.attachments);assertTrue(session.operationRecords.isEmpty())
            File(owned.folder,"original-markdown-native-proof.json").writeText(JSONObject().put("originalTargetAndMessageMatched",true)
                .put("originalFileLinkActivated",true).put("sourceSha256",expectedSha).put("exactSourceCopied",true).put("outlineAndSearch",true)
                .put("sameVersionPositionRestored",true).put("sourceModePositionRestored",true).put("wideTableHorizontalAndInsidePosition",true).put("controlledReadOnlyShare",true).put("plainFallback",true)
                .put("actualRecipientAccepted",false).put("newModelInputs",0).put("originalDraftAndFilesPreserved",true).toString())
        } catch(error: Throwable) {
            val snapshot=main {JSONObject().put("sameThread",session.selectedThread==thread).put("originalMessagePresent",session.messages.any {it.id==messageId})
                .put("restoring",session.restoring).put("historyLoading",session.historyLoading).put("localRecoveryRequired",session.localRecoveryRequired)
                .put("error",session.error).put("operationCount",session.operationRecords.size)}
            File(owned.folder,"original-reader-failure-state.json").writeText(snapshot.toString())
            runCatching {File(owned.folder,"original-reader-failure-tree.txt").writeText(ui.onRoot().printToString())}
            runCatching {if(ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isNotEmpty())owned.dialogShot("original-reader-failure")}
            throw error
        } finally {
            instrumentation.removeMonitor(monitor)
            main {
                session.leaveForeground();previous?.let(activity::restoreSessionForAcceptance)
                oldClipboard?.let(clipboard::setPrimaryClip) ?: if(Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("",""))
            }
            owned.verifyDefault()
        }
    }
}
