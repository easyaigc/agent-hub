package com.agenthub.android

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Actual original-message route and native reader; synthetic HTTP files, scoped read-only URI witness. */
class MobileFilePreviewUiTest {
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=-1)val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule(
        {args.getString("viewportOrientation") ?: ""},
        {java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-owner-callbacks/${args.getString("ownerCallbacksRun") ?: "fixture"}/reader-viewport").apply {mkdirs()}},{"reader"})
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val text get()=NativeL10n(ui.activity,"en")
    private val clipboard get()=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var originalClipboard: ClipData?=null
    private val shared=mutableListOf<ByteArray>()
    private var expectedName=""
    private val external=mutableListOf<String>()
    private val monitor=object: Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent?): Instrumentation.ActivityResult? {
            if(intent?.action !in listOf(Intent.ACTION_CHOOSER,"miui.intent.action.MIUI_CHOOSER",Intent.ACTION_VIEW))return null
            if(intent?.action==Intent.ACTION_VIEW) { external.add(intent.dataString.orEmpty());return Instrumentation.ActivityResult(0,null) }
            @Suppress("DEPRECATION") val send=intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
            if(send.action!=Intent.ACTION_SEND)return null
            @Suppress("DEPRECATION") val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return null
            assertEquals(ui.activity.packageName+".resources",uri.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertEquals(uri,send.clipData!!.getItemAt(0).uri);assertFalse(send.hasExtra(android.provider.Browser.EXTRA_HEADERS))
            ui.activity.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst());assertEquals(expectedName,cursor.getString(0))
            }
            shared.add(ui.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            return Instrumentation.ActivityResult(0,null)
        }
    }
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        assertEquals(if(args.getString("viewportOrientation")=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,ui.activity.resources.configuration.orientation)
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        main {
            owned=OwnedControlAcceptance(ui.activity,"file-preview");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light")
            originalClipboard=clipboard.primaryClip
        }
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        if(::owned.isInitialized)main {
            session.leaveForeground();owned.verifyDefault();assertTrue(session.operationRecords.isEmpty())
            originalClipboard?.let(clipboard::setPrimaryClip) ?: if(Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("",""))
        }
    }
    private fun fixture(source: String="# Start\n\nOriginal native Markdown\n")=ResourceUiFixture().also {
        it.name="owned-reader.md";it.mediaType="text/markdown";it.bytes=source.toByteArray()
    }
    private fun connect(f: ResourceUiFixture) {
        main { session.connect("Owned Markdown",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.id=="old-assistant" } }
        f.client=session.selectedClient;expectedName=f.name;main { session.text="Unsent source draft 100% %@" }
    }
    private fun open(ready: Boolean=true) {
        ui.onNodeWithTag("resource-card-preview-ui-one").performScrollTo().performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().size==1 }
        if(ready)ui.waitUntil(15_000) { ui.onAllNodesWithTag("file-preview-content").fetchSemanticsNodes().size==1 }
    }
    private fun close() {
        ui.onNodeWithTag("file-preview-close").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty() }
    }
    private fun verify(f: ResourceUiFixture) {
        assertEquals("Unsent source draft 100% %@",session.text);assertTrue(f.server.requests.all { it.method=="GET" });assertTrue(session.operationRecords.isEmpty())
    }
    private fun callback(tag: String)=checkNotNull(ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun retired(vararg callbacks: ()->Boolean) {
        val dispatch=org.json.JSONArray()
        for(callback in callbacks)try { main {callback()};dispatch.put("original-app-callback-invoked") }
        catch(error: IllegalStateException) {
            assertEquals("Cannot read CompositionLocal because the Modifier node is not currently attached.",error.message)
            assertTrue(error.stackTrace.any {it.className=="androidx.compose.foundation.AbstractClickableNode"&&it.methodName=="playClickSound"})
            dispatch.put("retired-native-node-rejected-before-app-callback")
        }
        java.io.File(owned.folder,"retired-dispatch.json").writeText(dispatch.toString())
    }
    private fun copied()=main { clipboard.primaryClip!!.getItemAt(0).text.toString() }
    private fun rawView(check: (android.widget.TextView)->Unit) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TextView::class.java))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check {view,error ->
                if(error!=null)throw error
                assertEquals(ui.activity.packageName,view.context.packageName);check(view as android.widget.TextView)
            }
    }
    private fun scrollableSource(message: MobileMessage): Pair<androidx.compose.foundation.lazy.LazyListState,java.util.concurrent.atomic.AtomicBoolean> {
        val mounted=java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        main {ui.activity.setContent {ProductTextProvider("en") {androidx.compose.material3.MaterialTheme {
            list=rememberLazyListState()
            LazyColumn(state=list,modifier=Modifier.testTag("reader-source-list")) {
                items(200,key={it}) {index ->
                    if(index==0) {
                        DisposableEffect(Unit) {mounted.set(true);onDispose {mounted.set(false)}}
                        MessageLinkOpening(session,message) {MessageResourceCards(message)}
                    } else androidx.compose.material3.Text("Owned row $index",Modifier.height(64.dp))
                }
            }
        }}}}
        ui.waitForIdle();return list to mounted
    }
    @Test fun guardedResourceCardOpensReaderAndCopiesExactCrlfSource() {
        val source="# 原文\r\n\r\n```text\r\nfirst 100% %@\r\nsecond\r\n```\r\n"
        fixture(source).use { f ->
            connect(f);open();ui.onNodeWithTag("file-preview-name").assertTextEquals(f.name)
            ui.onNodeWithTag("file-preview-raw").performClick();ui.onNodeWithTag("file-preview-source").assertIsDisplayed()
            ui.onNodeWithTag("file-preview-copy-source").performClick();assertEquals(source,copied())
            owned.dialogShot("exact-original-source");verify(f);assertEquals(1,f.binaries())
        }
    }
    @Test fun outlineSearchAndNativeWideTableRemainInteractive() {
        val columns=(1..8).map { "wide column $it" }
        val table=columns.joinToString(" | ","| "," |\n")+columns.joinToString(" | ","| "," |\n") {"---"}+
            (1..16).joinToString("\n") { row -> columns.indices.joinToString(" | ","| "," |") { column -> "row-$row-column-$column" } }
        fixture("# Start\n\n$table\n\nMATCH_NEEDLE alpha\n\n# 结尾\n\nMATCH_NEEDLE beta\n").use { f ->
            connect(f);open();val tableNode=ui.onNodeWithTag("file-preview-table-scroll-1").performScrollTo()
            val horizontal=tableNode.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            tableNode.performTouchInput { swipeLeft() };assertTrue(horizontal.value()>0f)
            ui.onNodeWithTag("file-preview-outline").performClick()
            ui.onNodeWithTag("file-preview-heading-结尾").performScrollTo().performClick()
            ui.onNodeWithText("结尾").assertIsDisplayed();ui.waitForIdle();owned.dialogShot("outline-tail-position")
            ui.onNodeWithTag("file-preview-search").performClick();ui.onNodeWithTag("file-preview-query").performTextInput("MATCH_NEEDLE")
            ui.waitUntil(10_000) { runCatching { ui.onNodeWithTag("file-preview-hit-count").assertTextEquals("1/2");true }.getOrDefault(false) }
            ui.onNodeWithTag("file-preview-next-hit").performClick();ui.onNodeWithTag("file-preview-hit-count").assertTextEquals("2/2");verify(f)
        }
    }
    @Test fun reopenedVersionRetainsReadingPositionAndChangedVersionStartsAtNewSource() {
        fixture("# Start\n\n"+(0..50).joinToString("\n\n") { "position-$it long native paragraph text for this owned file" }).use { f ->
            connect(f);open();val content=ui.onNodeWithTag("file-preview-content")
            content.performScrollToNode(hasText("position-45",substring=true))
            val before=content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value();assertTrue(before>0)
            close();open();ui.waitUntil(10_000) { runCatching {ui.onNodeWithText("position-45",substring=true).assertIsDisplayed();true}.getOrDefault(false) }
            val after=ui.onNodeWithTag("file-preview-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value();assertEquals(before,after,.02f)
            owned.dialogShot("same-file-reading-position");close()
            f.version="two";f.bytes="# Updated source\n\nOnly new content".toByteArray();open()
            ui.onNodeWithText("Updated source").assertIsDisplayed();assertEquals(0f,ui.onNodeWithTag("file-preview-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),.02f);verify(f)
        }
    }
    @Test fun lateOriginalCloseCopyAndShareCannotAffectReplacementPreview() {
        fixture("# Original\n\nold original source").use { f ->
            connect(f);open();val oldClose=callback("file-preview-close");val oldShare=callback("file-preview-share")
            ui.onNodeWithTag("file-preview-raw").performClick();val oldCopy=callback("file-preview-copy-source");close()
            f.version="two";val source="# Replacement\n\nnew source";f.bytes=source.toByteArray();open()
            ui.onNodeWithTag("file-preview-raw").performClick();ui.onNodeWithTag("file-preview-copy-source").performClick();assertEquals(source,copied())
            retired(oldClose,oldShare,oldCopy);ui.onNodeWithTag("file-preview-screen").assertExists();assertEquals(source,copied());assertTrue(shared.isEmpty());verify(f)
        }
    }
    @Test fun originalEncodingFailureRetriesThroughSameAuthorizedResourceAndOldRetryIsInert() {
        fixture().use { f ->
            f.bytes=byteArrayOf(0xc3.toByte(),0x28);connect(f);open(false)
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("file-preview-error").fetchSemanticsNodes().isNotEmpty() }
            val oldRetry=callback("file-preview-retry");f.version="two";f.bytes="# Recovered\n\nverified new version".toByteArray()
            ui.onNodeWithTag("file-preview-retry").performClick()
            ui.waitUntil(15_000) { runCatching {ui.onNodeWithText("Recovered").assertIsDisplayed();true}.getOrDefault(false) }
            val reads=f.resourceReads();retired(oldRetry);ui.waitForIdle();assertEquals(reads,f.resourceReads());ui.onNodeWithText("Recovered").assertIsDisplayed();verify(f)
        }
    }
    private fun tapLink(block: Int) {
        val node=ui.onNodeWithTag("file-preview-native-$block").performScrollTo();var layout: androidx.compose.ui.text.TextLayoutResult?=null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get -> val list=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();get(list);layout=list.single() }
        node.performTouchInput { click(checkNotNull(layout).getBoundingBox(1).center) }
    }
    @Test fun relativeAndActiveReferencesCannotReadAnotherResourceOrLaunchAnActivity() {
        fixture("[Relative](../private.txt)\n\n[Active](javascript:synthetic())\n").use { f ->
            connect(f);open();val reads=f.resourceReads()
            tapLink(0);ui.onNodeWithTag("file-preview-link-error").assertExists();ui.onNodeWithText(text.tr("好")).performClick()
            tapLink(1);ui.onNodeWithTag("file-preview-link-error").assertExists()
            assertEquals(reads,f.resourceReads());assertTrue(external.isEmpty());assertTrue(shared.isEmpty());verify(f)
        }
    }
    @Test fun activeShareUsesExactPinnedBytesAndOnlyReadPermission() {
        fixture("# Shared original\r\n\r\nNative unchanged 100% %@\r\n").use { f ->
            connect(f);open();ui.onNodeWithTag("file-preview-share").performClick()
            ui.waitUntil(5_000) {shared.size==1};assertArrayEquals(f.bytes,shared.single());assertTrue(external.isEmpty());owned.dialogShot("native-reader-share-return");verify(f)
        }
    }
    @Test fun longRawNameAndHeaderControlsRemainReachableInShortViewport() {
        fixture().use { f ->
            fun mark(phase: String) { java.io.File(owned.folder,"long-name-steps.jsonl").appendText(phase+"\n") }
            f.name="关闭 100% %@ "+"original-long-name-".repeat(18)+".md";mark("before-connect");connect(f);mark("connected");open();mark("reader-open")
            val name=ui.onNodeWithTag("file-preview-name");name.assertTextEquals(f.name);mark("original-name-verified")
            val config=name.fetchSemanticsNode().config
            val scroll=if(config.contains(SemanticsProperties.VerticalScrollAxisRange))config[SemanticsProperties.VerticalScrollAxisRange] else null
            if(scroll!=null&&scroll.maxValue()>0) {
                val distance=scroll.maxValue()
                name.performSemanticsAction(SemanticsActions.ScrollBy) { action -> assertTrue(action(0f,distance)) }
                ui.waitUntil(5_000) {scroll.value()>0}
                mark("native-scroll-semantics")
            }
            mark("raw-scroll-completed");val density=ui.activity.resources.displayMetrics.density
            for(tag in listOf("file-preview-close","file-preview-search","file-preview-outline","file-preview-refresh","file-preview-share")) {
                mark("before-"+tag);val node=ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode();assertTrue(node.boundsInRoot.height/density>=47.5f);mark("after-"+tag)
            }
            mark("header-geometry-verified");owned.dialogShot("long-name-native-controls");mark("screenshot-completed");verify(f)
        }
    }
    @Test fun openedReaderPinsOnlyItsOriginalLazyRowAndReleasesItOnDismiss() {
        fixture().use { f ->
            connect(f);val message=session.messages.single {it.id=="old-assistant"}
            val (list,mounted)=scrollableSource(message)
            open();assertTrue(mounted.get())
            main {list.scrollToItem(199)};ui.waitForIdle()
            assertTrue(list.firstVisibleItemIndex>100)
            assertTrue("An open reader must retain its original lazy item outside the viewport",mounted.get())
            ui.onNodeWithTag("file-preview-raw").performClick();ui.onNodeWithTag("file-preview-copy-source").performClick()
            assertEquals(String(f.bytes,Charsets.UTF_8),copied());owned.dialogShot("reader-source-row-pinned")
            close();ui.waitUntil(10_000) {!mounted.get()}
            assertTrue(session.messageLinkCurrent(session.messageLinkContext(message)));verify(f)
        }
    }
    @Test fun sourceModeKeepsItsOwnNativePositionAcrossDisplayModeChanges() {
        fixture("# Source position\n\n"+(0..220).joinToString("\n\n") {"source-line-$it unchanged native text"}).use {f ->
            connect(f);open();ui.onNodeWithTag("file-preview-raw").performClick()
            var offset=0;rawView {it.scrollTo(0,500);offset=it.scrollY;assertTrue(offset>0)}
            ui.onNodeWithTag("file-preview-rendered").performClick();ui.onNodeWithTag("file-preview-raw").performClick();ui.waitForIdle()
            rawView {assertEquals("Source position must survive a preview/source toggle",offset,it.scrollY)}
            ui.onNodeWithTag("file-preview-copy-source").performClick();assertEquals(String(f.bytes,Charsets.UTF_8),copied())
            f.version="two";f.bytes=("# Source version reset\n\n"+java.util.UUID.randomUUID()).toByteArray()
            ui.onNodeWithTag("file-preview-refresh").performClick()
            ui.waitUntil(15_000) {runCatching {ui.onNodeWithText("Source version reset").assertIsDisplayed();true}.getOrDefault(false)}
            ui.onNodeWithTag("file-preview-raw").performClick();rawView {assertEquals(0,it.scrollY)};verify(f)
        }
    }
    @Test fun explicitRefreshUsesSameResourceAfterBackgroundMessageAndVersionUpdate() {
        fixture("# Original reader\n\nOriginal immutable snapshot").use {f ->
            connect(f);open()
            f.version="two";f.bytes="# Updated reader\n\nOnly the new authorized version".toByteArray();f.updateLinks()
            main {session.loadHistory(userInitiated=true)}
            ui.waitUntil(15_000) {!session.historyLoading&&session.messages.any {message->message.attachments.any {it.id=="ui-two"}}}
            ui.onNodeWithText("Original reader").assertIsDisplayed()
            ui.onNodeWithTag("file-preview-refresh").assertIsEnabled().performClick()
            ui.waitUntil(8_000) {runCatching {ui.onNodeWithText("Updated reader").assertIsDisplayed();true}.getOrDefault(false)}
            assertEquals(2,f.binaries());verify(f)
        }
    }
    @Test fun refreshKeepsOriginalSourcePinnedWhileTheNextVersionReadIsPending() {
        fixture("# Original pending source\n\n"+java.util.UUID.randomUUID()).use {f ->
            connect(f);val (list,mounted)=scrollableSource(session.messages.single {it.id=="old-assistant"});open()
            main {list.scrollToItem(199)};ui.waitForIdle();assertTrue(mounted.get())
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            try {
                f.version="two";f.bytes=("# Refreshed pinned source\n\n"+java.util.UUID.randomUUID()).toByteArray()
                f.binaryGate={entered.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS))}
                ui.onNodeWithTag("file-preview-refresh").performClick()
                ui.waitUntil(10_000) {entered.count==0L};ui.waitForIdle()
                ui.onNodeWithTag("file-preview-refreshing").assertIsDisplayed()
                ui.onNodeWithTag("file-preview-refresh").assertIsNotEnabled()
                ui.onNodeWithText("Original pending source").assertIsDisplayed()
                owned.dialogShot("original-reader-refresh-progress")
                assertTrue("An explicit refresh keeps its source alive while loading",mounted.get())
                release.countDown();ui.waitUntil(15_000) {runCatching {ui.onNodeWithText("Refreshed pinned source").assertIsDisplayed();true}.getOrDefault(false)}
                close();ui.waitUntil(10_000) {!mounted.get()};verify(f)
            } finally {release.countDown()}
        }
    }
    @Test fun removedResourceClosesOriginalPreviewAndCannotBeReadByItsRetiredRefresh() {
        fixture().use {f ->
            connect(f);open();val oldRefresh=callback("file-preview-refresh")
            f.includeAttachment=false;main {session.loadHistory(userInitiated=true)}
            ui.waitUntil(10_000) {!session.historyLoading&&session.messages.single {it.id=="old-assistant"}.attachments.isEmpty()}
            ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().isEmpty()}
            val reads=f.resourceReads();retired(oldRefresh);ui.waitForIdle();assertEquals(reads,f.resourceReads());verify(f)
        }
    }
    @Test fun sameVersionRefreshRetiresTheOriginalWindowCloseCopyAndShare() {
        fixture("# Same version window\n\n"+java.util.UUID.randomUUID()).use {f ->
            connect(f);open();val oldClose=callback("file-preview-close");val oldShare=callback("file-preview-share")
            ui.onNodeWithTag("file-preview-raw").performClick();val oldCopy=callback("file-preview-copy-source");val binaries=f.binaries()
            ui.onNodeWithTag("file-preview-refresh").performClick()
            ui.waitUntil(15_000) {ui.onAllNodesWithTag("file-preview-content").fetchSemanticsNodes().size==1}
            val marker="New independent clipboard marker";main {clipboard.setPrimaryClip(ClipData.newPlainText("",marker))}
            retired(oldClose,oldShare,oldCopy);ui.waitForIdle();ui.onNodeWithTag("file-preview-screen").assertExists()
            assertEquals(marker,copied());assertTrue(shared.isEmpty());assertEquals(binaries,f.binaries());verify(f)
        }
    }
    @Test fun closingReaderDuringRefreshCannotReopenItOrRetainItsOriginalSource() {
        fixture("# Original closing source\n\n"+java.util.UUID.randomUUID()).use {f ->
            connect(f);val (list,mounted)=scrollableSource(session.messages.single {it.id=="old-assistant"});open()
            main {list.scrollToItem(199)};ui.waitForIdle()
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            try {
                f.version="two";f.bytes=("# Closed late source\n\n"+java.util.UUID.randomUUID()).toByteArray()
                f.binaryGate={entered.countDown();release.await(15,java.util.concurrent.TimeUnit.SECONDS)}
                ui.onNodeWithTag("file-preview-refresh").performClick();ui.waitUntil(10_000) {entered.count==0L}
                ui.onNodeWithTag("file-preview-refreshing").assertIsDisplayed();close()
                ui.waitUntil(10_000) {!mounted.get()};release.countDown();ui.waitForIdle()
                ui.onNodeWithTag("file-preview-screen").assertDoesNotExist();verify(f)
            } finally {release.countDown()}
        }
    }
}
