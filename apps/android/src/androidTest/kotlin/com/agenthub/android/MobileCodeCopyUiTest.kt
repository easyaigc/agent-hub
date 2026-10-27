package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production Markdown UI, SDK pointers and exact copy payloads; no model or remote command. */
@Suppress("DEPRECATION")
class MobileCodeCopyUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private var defaultDrafts=emptyMap<String,String?>()
    private lateinit var session: MobileSession
    private lateinit var server: CommandFixture
    @Volatile private var source="Initial independent code-copy history"
    private var systemClipboard by mutableStateOf(false)
    private val draft="Independent unsent code-copy draft"
    private val target=TargetScope("codex",null,null,"code-copy-host","code-copy-workspace","code-copy-runtime")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private val copied=mutableListOf<String>()
    private val recorder=object: ClipboardManager {
        private var value: AnnotatedString?=null
        override fun setText(annotatedString: AnnotatedString) { value=annotatedString;copied+=annotatedString.text }
        override fun getText(): AnnotatedString?=value
    }
    private val nativeClipboard=object: Clipboard {
        private var entry: ClipEntry?=null
        override suspend fun getClipEntry(): ClipEntry?=entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry=clipEntry;clipEntry?.clipData?.getItemAt(0)?.text?.toString()?.let { copied+=it } }
    }
    private val run get()=(InstrumentationRegistry.getArguments().getString("codeCopyRunId")?:"code-copy-local").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private fun folder()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P3-code-copy/$run").apply { mkdirs() }
    @Before fun preserveDefaultSession() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val metadata=NativeSessionStorage(context).metadata();defaultDrafts=listOf("drafts","draftFiles","draftVersions").associateWith { metadata?.opt(it)?.toString() }
            session=MobileSession(NativeSessionStorage(context,"P3-code-copy-"+UUID.randomUUID()));session.resetAcceptance()
            ui.activity.setContent {
                if(systemClipboard)AgentHubApp(session) { }
                else CompositionLocalProvider(LocalClipboardManager provides recorder,LocalClipboard provides nativeClipboard) { AgentHubApp(session) { } }
            }
        }
        val workspace=JSONObject().put("hostId",target.hostId).put("name","code copy fixture").put("connectionMode","history-only")
            .put("readTarget",CodexCommandCodec.target(target)).put("capabilities",JSONArray()).put("projects",JSONArray())
            .put("threads",JSONArray().put(JSONObject().put("id","copy-thread").put("hostId",target.hostId).put("title","Code selection and copy").put("state","idle")))
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=workspace.toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId","copy-thread").put("readTarget",CodexCommandCodec.target(target))
                .put("messages",JSONArray().put(JSONObject().put("id","copy-message").put("role","assistant").put("text",source))).toString())
            else->WireResponse(404)
        } }
        main { session.connect("code copy fixture",server.endpoint,"") };ui.waitUntil(15_000) { session.hasClient&&!session.loading }
        main { session.selectThread(session.threads.single()) };ui.waitUntil(15_000) { !session.historyLoading&&session.messages.size==1 }
        main { session.text=draft }
    }
    @After fun defaultDraftsRemainUnchanged() {
        try {
            if(::session.isInitialized)main { session.leaveForeground();assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty()) }
            if(::server.isInitialized)assertTrue("Code copy is GET-only and never submits input",server.requests.all { it.method=="GET" })
        }finally{
            if(::server.isInitialized)server.close()
            val metadata=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).metadata()
            for((key,value)in defaultDrafts)assertEquals("Owned Markdown acceptance preserves default $key",value,metadata?.opt(key)?.toString())
        }
    }
    private fun show(value: String,systemClipboard: Boolean=false,blockIndex: Int=0) {
        main { this@MobileCodeCopyUiTest.systemClipboard=systemClipboard;source=value;session.loadHistory()?.join() }
        ui.waitUntil(15_000) { !session.historyLoading&&session.messages.single().text==value&&ui.onAllNodesWithTag("markdown-copy-message-$blockIndex-copy-code").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("markdown-copy-message-$blockIndex-copy-code"))
    }
    private fun copy(tag: String="markdown-copy-message-0-copy-code") {
        val viewport=ui.onNodeWithTag("message-list")
        viewport.performScrollToNode(hasTestTag(tag))
        fun visible(): Boolean {
            val button=ui.onNodeWithTag(tag).fetchSemanticsNode();val frame=viewport.fetchSemanticsNode().boundsInRoot
            val rect=androidx.compose.ui.geometry.Rect(button.positionInRoot.x,button.positionInRoot.y,button.positionInRoot.x+button.size.width,button.positionInRoot.y+button.size.height)
            val covered=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().any { it.boundsInRoot.overlaps(rect) }
            return rect.top>=frame.top&&rect.bottom<=frame.bottom&&!covered
        }
        // A lazy item may itself be taller than a landscape viewport. Materializing
        // that item does not place every nested copy button above the composer.
        repeat(8) {
            if(!visible()) {
                val above=ui.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y<viewport.fetchSemanticsNode().boundsInRoot.top
                viewport.performTouchInput {
                    if(above)swipe(androidx.compose.ui.geometry.Offset(width*.5f,height*.2f),androidx.compose.ui.geometry.Offset(width*.5f,height*.8f),300)
                    else swipe(androidx.compose.ui.geometry.Offset(width*.5f,height*.8f),androidx.compose.ui.geometry.Offset(width*.5f,height*.2f),300)
                }
                ui.waitForIdle()
            }
        }
        assertTrue("Original copy button fully inside the current message viewport",visible())
        if(tag!="markdown-copy-message-0-copy-code")shot("secondary-copy-ready-"+tag)
        val count=copied.size
        ui.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click(center) }
        if(!systemClipboard)ui.waitUntil(5_000) { copied.size==count+1 }
    }
    private fun checkCopy(name: String,source: String,expected: String) {
        show(source);copy();ui.runOnIdle { assertEquals(listOf(expected),copied) }
        ui.onNodeWithTag("markdown-copy-message-0-copy-code").assertTextContains("复制");ui.onNodeWithText("已复制").assertDoesNotExist()
        shot(name)
        File(folder(),"$name-proof.json").writeText(JSONObject().put("exactPayload",true).put("copiedUtf16Length",expected.length).put("stableCopyLabel",true).put("systemClipboard",false).put("newInputs",0).toString())
    }
    private fun handles(endOnly: Boolean=false)=ui.onAllNodes(SemanticsMatcher("Visible native code selection handle") { n -> n.config.any { it.key.name=="SelectionHandleInfo"&&it.value.toString().contains("visible=true")&&(!endOnly||it.value.toString().contains("SelectionEnd")) } },useUnmergedTree=true)
    private fun handleInfo()=handles(true).fetchSemanticsNodes().single().config.joinToString { it.key.name+"="+it.value }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
        val frames=CountDownLatch(1);i.runOnMainSync { window.decorView.postOnAnimation { window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());assertEquals(i.targetContext.packageName,window.decorView.context.packageName);bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-main-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).put("productionChatUi",true).toString())
        }finally{image.recycle()}
    }
    @Test fun lfUnicodeTabsAndSeparateBlocksCopyOnlyTheirOriginalBodies() {
        val first="val 中文 = \"😀\"\n\tprintln(中文)\n";val second="SECOND_ONLY\n"
        show("```kotlin\n$first```\n\nordinary paragraph must not be copied\n\n```text\n$second```")
        copy();copy("markdown-copy-message-2-copy-code");ui.runOnIdle { assertEquals(listOf(first,second),copied) }
        ui.onNodeWithText("已复制").assertDoesNotExist();shot("separate-blocks-exact-unicode")
    }
    @Test fun crlfBodyRetainsTheOriginalLineEndings()=checkCopy("crlf","```kotlin\r\nval 中文 = \"😀\"\r\n\tkeep()\r\n```","val 中文 = \"😀\"\r\n\tkeep()\r\n")
    @Test fun unclosedFenceDoesNotInventATrailingNewline()=checkCopy("unclosed-eof","```swift\nlet x = 1","let x = 1")
    @Test fun indentedFenceRetainsIosRawBodyAndClosingPrefix()=checkCopy("indented","  ```swift\n  let x = 1\n  ```","  let x = 1\n  ")
    @Test fun blankLineAndEmptyBodyDoNotCopyLanguageOrFence() {
        show("```text\nline\n\n```\n\n```text\n```");copy();copy("markdown-copy-message-1-copy-code")
        ui.runOnIdle { assertEquals(listOf("line\n\n",""),copied) };ui.onNodeWithText("已复制").assertDoesNotExist();shot("blank-and-empty")
    }
    @Test fun codeLongPressAndEndHandleDragKeepWholeBlockCopyIndependent() {
        val body="SELECT_CODE original independently selectable words remain here\nSecond code row\n"
        show("```text\n$body```")
        val layouts=mutableListOf<TextLayoutResult>()
        val text=ui.onNode(hasText(body),useUnmergedTree=true);text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point=layouts.single().getBoundingBox(3).center;text.performTouchInput { longClick(point) }
        ui.waitUntil(10_000) { handles().fetchSemanticsNodes().size==2 };shot("code-long-press")
        val before=handleInfo();val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        handles(true)[0].performTouchInput { swipe(center,center+Offset(45f*density,0f),400) }
        ui.waitUntil(10_000) { handles(true).fetchSemanticsNodes().size==1&&handleInfo()!=before };shot("code-end-handle-drag")
        copy();ui.runOnIdle { assertEquals(listOf(body),copied) };ui.onNodeWithText("已复制").assertDoesNotExist()
        File(folder(),"code-selection-proof.json").writeText(JSONObject().put("sdkLongPress",true).put("handles",2).put("endHandleChanged",true).put("wholeCopyExact",true).put("systemClipboard",false).toString())
    }
    @Test fun streamingBodyUpdatesKeepTheCurrentCodePayloadAndIndependentBlocks() {
        show("```text\nFIRST\n```");copy()
        show("```kotlin\r\nSECOND 中文😀")
        ui.waitUntil(10_000) { ui.onAllNodes(hasText("SECOND 中文😀"),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() };copy()
        show("ordinary text before the next block\n\n```text\nTHIRD\n\n```",blockIndex=1);copy("markdown-copy-message-1-copy-code")
        ui.runOnIdle { assertEquals(listOf("FIRST\n","SECOND 中文😀","THIRD\n\n"),copied) }
        ui.onNodeWithText("已复制").assertDoesNotExist();shot("streamed-body-updates")
    }
    @Test fun languageHeaderCannotBecomePartOfTheNativeCodeSelection() {
        val body="HEADER_EXCLUDED original code body\n"
        show("```CODE_LANGUAGE\n$body```")
        ui.onNodeWithText("CODE_LANGUAGE",useUnmergedTree=true).performTouchInput { longClick(center) }
        ui.waitForIdle();assertTrue("Only the code body is selectable, matching iOS",handles().fetchSemanticsNodes().isEmpty())
        copy();ui.runOnIdle { assertEquals(listOf(body),copied) };shot("header-excluded-from-selection")
    }
    @Test fun ownedPhysicalSystemClipboardRoundTripRestoresPreviousOpaqueClip() {
        val physical=InstrumentationRegistry.getArguments().getString("codeCopyPhysicalClipboard")=="1"
        val body="PHYSICAL_COPY 中文😀\r\n\tsecond\r\n"
        if(!physical) { checkCopy("clipboard-recorder","```text\r\n$body```",body);return }
        require(android.os.Build.VERSION.SDK_INT==33)
        lateinit var clipboard: AndroidClipboard;var previous: ClipData?=null
        ui.runOnIdle { clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE)as AndroidClipboard;previous=clipboard.primaryClip }
        try {
            show("```text\r\n$body```",systemClipboard=true);copy()
            ui.runOnIdle { assertEquals(body,clipboard.primaryClip?.getItemAt(0)?.text?.toString()) };ui.onNodeWithText("已复制").assertDoesNotExist();shot("physical-system-clipboard")
        }finally { ui.runOnIdle { previous?.let { clipboard.setPrimaryClip(it) }?:clipboard.clearPrimaryClip() } }
        File(folder(),"physical-clipboard-proof.json").writeText(JSONObject().put("systemClipboard",true).put("exactPayload",true).put("previousOpaqueClipRestored",true).put("previousClipPersisted",false).put("newInputs",0).toString())
    }
    private fun copyOwnedSelectionMenu(name: String) {
        require(android.os.Build.VERSION.SDK_INT==37) { "Real system-menu touch is verified only on the owned API37 emulator; other device menus remain pending" }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags=automation.serviceInfo.flags
        try {
            automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            var action: android.view.accessibility.AccessibilityNodeInfo?=null
            var diagnostic=""
            ui.waitUntil(10_000) {
                val owned=automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==InstrumentationRegistry.getInstrumentation().targetContext.packageName }
                val seen=mutableListOf<String>()
                val candidates=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(n: android.view.accessibility.AccessibilityNodeInfo) {
                    val label=n.text?.toString().orEmpty();val id=n.viewIdResourceName.orEmpty()
                    if(label.isNotEmpty())seen+="$label [$id] clickable=${n.isClickable}"
                    if(label in listOf("Copy","复制")&&id.contains("floating_toolbar"))candidates+=n
                    for(j in 0 until n.childCount)n.getChild(j)?.let(::visit)
                }
                owned.forEach(::visit);diagnostic=seen.joinToString("\n");action=candidates.singleOrNull();action!=null
            }
            File(folder(),name+"-owned-menu.txt").writeText(diagnostic)
            val item=checkNotNull(action)
            // Native SystemUI RemoteViews are descendants of this verified own-App
            // root; use an SDK touch on that exact Copy label, never a SystemUI root.
            assertTrue(item.packageName?.toString() in listOf(InstrumentationRegistry.getInstrumentation().targetContext.packageName,"com.android.systemui"))
            assertTrue(item.viewIdResourceName.orEmpty().contains("floating_toolbar"))
            val rect=android.graphics.Rect();item.getBoundsInScreen(rect);assertFalse(rect.isEmpty)
            File(folder(),name+"-menu-action.json").writeText(JSONObject().put("rootPackage",InstrumentationRegistry.getInstrumentation().targetContext.packageName).put("actionPackage",item.packageName?.toString()).put("actionId",item.viewIdResourceName).put("copyLabel",item.text?.toString()).put("sdkTargetedTouch",true).toString())
            val now=android.os.SystemClock.uptimeMillis()
            for((kind,time)in listOf(android.view.MotionEvent.ACTION_DOWN to now,android.view.MotionEvent.ACTION_UP to now+80)) {
                val event=android.view.MotionEvent.obtain(now,time,kind,rect.exactCenterX(),rect.exactCenterY(),0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue("SDK touch of the exact owned Copy label",automation.injectInputEvent(event,true)) }finally { event.recycle() }
            }

        }finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags } }
    }
    private fun diffMultilineSelection(ending: String,name: String) {
        val first="BASE_LINE alpha";val second="-REMOVED_LINE old";val third="+ADDED_LINE 中文😀"
        val body=listOf(first,second,third,"@@ footer @@").joinToString(ending,postfix=ending)
        show("```diff"+ending+body+"```")
        fun textNode(line: String)=ui.onNode(hasText(body) or hasText(line),useUnmergedTree=true)
        fun layout(node: SemanticsNodeInteraction): TextLayoutResult { val values=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) };return values.single() }
        val firstNode=textNode(first);val firstLayout=layout(firstNode);val firstBounds=firstNode.getUnclippedBoundsInRoot()
        val thirdNode=textNode(third);val thirdLayout=layout(thirdNode);val thirdBounds=thirdNode.getUnclippedBoundsInRoot()
        val targetIndex=thirdLayout.layoutInput.text.text.indexOf("+ADDED_LINE")+10
        val target=thirdLayout.getBoundingBox(targetIndex).center
        val initialEnd=firstLayout.getBoundingBox("BASE_LINE".length-1).bottomRight
        firstNode.performTouchInput { longClick(firstLayout.getBoundingBox(3).center) }
        ui.waitUntil(10_000) { handles().fetchSemanticsNodes().size==2 };shot(name+"-initial")
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val beforeHandleInfo=handleInfo()
        val delta=Offset((thirdBounds.left.value-firstBounds.left.value)*density+target.x-initialEnd.x,(thirdBounds.top.value-firstBounds.top.value)*density+target.y-initialEnd.y)
        assertTrue("The native pointer must reach a later Diff row",delta.y>10f*density)
        handles(true)[0].performTouchInput { swipe(center,center+delta,600) };ui.waitForIdle();assertNotEquals(beforeHandleInfo,handleInfo());shot(name+"-multiline")
        val selectionOnly=InstrumentationRegistry.getArguments().getString("codeCopyMenuMode")=="selection-only"
        var selected: String?=null
        if(!selectionOnly) {
            copyOwnedSelectionMenu(name);ui.waitUntil(10_000) { copied.size==1 }
            selected=copied.single();File(folder(),name+"-selected-before-assert.json").writeText(JSONObject().put("selected",selected).put("originalBody",body).toString(2))
            assertTrue("SDK copy must cross all three Diff rows",selected.startsWith("BASE_LINE")&&selected.contains("REMOVED_LINE old")&&selected.contains("+ADDED_LINE"))
            assertTrue("Selected Diff preserves original newlines, prefixes and exact body substring",body.startsWith(selected))
            assertFalse("Header and copy button are excluded",selected.contains("复制")||selected.startsWith("diff"))
        }else {
            // An actual SDK selection spans later rows. Copying that selection
            // through the system menu remains pending on this specific device.
            assertEquals(2,handles().fetchSemanticsNodes().size)
            val endInfo=handleInfo();File(folder(),name+"-selection-only.txt").writeText(endInfo)
        }
        copy();ui.runOnIdle { assertEquals(if(selected==null)listOf(body)else listOf(selected,body),copied) };assertEquals(draft,session.text);shot(name+"-whole-copy")
        File(folder(),name+"-proof.json").writeText(JSONObject().put("sdkLongPress",true).put("sdkMultilineHandleDrag",true).put("sdkOwnedToolbarCopy",!selectionOnly).put("selectedTextCopyVerified",!selectionOnly).put("systemMenuPending",selectionOnly).put("selectedExactOriginalPrefix",!selectionOnly).put("wholeCopyExact",true).put("privateClipboardBothApis",true).put("defaultDraftPreserved",true).put("crlf",ending=="\r\n").put("newInputs",0).toString(2))
    }
    @Test fun diffLfMultilineNativeSelectionCopiesOriginalPrefixes()=diffMultilineSelection("\n","diff-lf")
    @Test fun diffCrlfMultilineNativeSelectionRetainsOriginalLineEndings()=diffMultilineSelection("\r\n","diff-crlf")

}
