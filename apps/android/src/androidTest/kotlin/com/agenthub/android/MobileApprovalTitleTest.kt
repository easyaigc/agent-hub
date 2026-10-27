package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Long synthetic original titles, SDK gestures and scoped native copy; no model execution. */
class MobileApprovalTitleTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val folder get()=File(i.targetContext.filesDir,"acceptance/P4-approval-title/${args.getString("viewportRun")!!}").apply {mkdirs()}
    @get:Rule(order=-1) val viewportRule: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation")!!},{folder},{"title"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val question get()=args.getString("viewportTitleKind")=="question"
    private var owned: OwnedControlAcceptance?=null
    private val session get()=checkNotNull(owned).session
    private fun main(block: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main){block()}}
    private fun handles()=ui.onAllNodes(SemanticsMatcher("Visible native title selection handle") {n->n.config.any {it.key.name=="SelectionHandleInfo"&&it.value.toString().contains("visible=true")}},useUnmergedTree=true)
    private fun shot(name: String) {
        i.waitForIdleSync();val window=ui.activity.window;var bitmap: Bitmap?=null
        i.runOnMainSync {assertTrue(window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)}
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {i.runOnMainSync {android.view.PixelCopy.request(window,image,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))};assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result);File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    @After fun protect() {if(owned!=null){try{shot("final-state")}finally{main {session.leaveForeground();owned!!.verifyDefault()};File(owned!!.folder,"default-storage-proof.json").copyTo(File(folder,"default-storage-proof.json"))}}}
    @Test fun longOriginalTitleRemainsReadableSelectableAndBoundToOriginalRequest() {
        assertEquals(if(args.getString("viewportOrientation")=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,ui.activity.resources.configuration.orientation)
        ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"approval-title");session.resetAcceptance();session.changeAppearance(args.getString("viewportTheme") ?: "dark")}
        File(folder,"profile.json").writeText(JSONObject().put("fontScale",ui.density.fontScale).put("theme",session.appearance).put("kind",if(question)"question"else"command").toString())
        val title="ALPHA source 原命令😀\r\n"+(1..9).joinToString("\r\n") {"long-path-$it "+"/workspace/Android原文/".repeat(3)+" END_$it"}+"\r\nOMEGA_END"
        ExecutionFixture().use {f ->
            fun request(turn: String,text: String): JSONObject {
                val questions=if(question) JSONArray().put(JSONObject().put("id","q").put("question","Select or enter the current answer").put("custom",true)
                    .put("options",JSONArray().put(JSONObject().put("label","One").put("description","Current answer option")))) else JSONArray()
                return JSONObject().put("id","title-original").put("threadId","fixture-thread").put("turnId",turn).put("kind",if(question)"question"else"command")
                    .put("title",text).put("detail","Read the full original request before deciding").put("questions",questions)
                    .put("decisions",if(question)JSONArray()else JSONArray(listOf("accept","acceptForSession","decline","cancel")))
            }
            f.state="running";f.turn="title-turn-1";f.approvals=JSONArray().put(request(f.turn!!,title))
            main {session.connect("Synthetic original title",f.server.endpoint,"")};ui.waitUntil(15_000){session.hasClient&&!session.loading};ui.runOnIdle{session.selectThread(session.threads.first())};ui.waitUntil(15_000){!session.historyLoading&&session.runtime?.approvals?.size==1};ui.runOnIdle{session.text="Independent title draft"}
            val original=session.runtime!!.approvals.single();val destination=session.approvalActionDestination();val text=ui.onNodeWithText(title,useUnmergedTree=true)
            val inline=ui.onAllNodes(hasText(title)and hasAnyAncestor(hasTestTag("message-list")),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
            val viewport=if(inline)ui.onNodeWithTag("message-list")else ui.onNode(hasScrollAction()and hasAnyDescendant(hasText(title))and !hasTestTag("bottom-controls"),useUnmergedTree=true)
            fun top() {if(ui.onAllNodesWithTag("bottom-controls").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("bottom-controls").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-1_000_000f)};if(inline)viewport.performScrollToIndex(session.timeline.rows.size)else viewport.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-1_000_000f)};ui.waitForIdle()}
            top();val layouts=mutableListOf<TextLayoutResult>();text.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)};val layout=layouts.single();assertEquals(title,layout.layoutInput.text.text);assertTrue(layout.lineCount>3);File(folder,"text-layout.json").writeText(JSONObject().put("lines",layout.lineCount).put("height",layout.size.height).put("width",layout.size.width).put("paragraphHeight",layout.multiParagraph.height).put("paragraphWidth",layout.multiParagraph.width).put("overflowWidth",layout.didOverflowWidth).put("overflowHeight",layout.didOverflowHeight).put("maxLines",layout.layoutInput.maxLines).toString());assertFalse("Full original title layout must not overflow: ${File(folder,"text-layout.json").readText()}",layout.hasVisualOverflow)
            val covered=mutableSetOf<Int>();var scans=0
            while(covered.size<layout.lineCount&&scans<64) {
                val frame=viewport.fetchSemanticsNode().boundsInRoot;val origin=text.getUnclippedBoundsInRoot().top.value*ui.density.density
                for(line in 0 until layout.lineCount)if(origin+layout.getLineTop(line)>=frame.top-1.5f&&origin+layout.getLineBottom(line)<=frame.bottom+1.5f)covered+=line
                File(folder,"read-progress.json").writeText(JSONObject().put("lines",layout.lineCount).put("covered",JSONArray(covered.sorted())).put("scans",scans).toString());shot("read-${scans++}")
                if(covered.size<layout.lineCount){viewport.performTouchInput {swipe(Offset(width*.4f,height*.8f),Offset(width*.4f,height*.35f),300)};ui.waitForIdle()}
            }
            assertEquals("Every original title line must be fully readable through native scrolling",layout.lineCount,covered.size)
            if(question){ui.onNodeWithTag("question-option-q-One").performScrollTo().performTouchInput {click(center)};ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled()};for(choice in original.decisions){val button=ui.onNodeWithTag("approval-$choice").performScrollTo().assertIsEnabled();val rect=button.fetchSemanticsNode().boundsInRoot;assertTrue(ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().none {it.boundsInRoot.overlaps(rect)});assertTrue(button.getUnclippedBoundsInRoot().let{it.bottom.value-it.top.value>=43.5f})};shot("all-decisions-reachable")
            top();text.performTouchInput {longClick(layout.getBoundingBox(2).center)};ui.waitUntil(10_000){handles().fetchSemanticsNodes().size==2};shot("title-native-selection")
            if(args.getString("viewportCopy")=="1") {
                require(android.os.Build.VERSION.SDK_INT==37)
                val clipboard=ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)as android.content.ClipboardManager
                var previous: android.content.ClipData?=null;ui.runOnIdle{previous=clipboard.primaryClip}
                try {copyOwnedSelectionMenu("title-copy");ui.waitUntil(5_000){var matches=false;i.runOnMainSync{matches=clipboard.primaryClip?.getItemAt(0)?.text?.toString()=="ALPHA"};matches};ui.runOnIdle{assertEquals("ALPHA",clipboard.primaryClip?.getItemAt(0)?.text?.toString())};File(folder,"clipboard-proof.json").writeText(JSONObject().put("systemClipboard",true).put("copiedExactOriginalWord",true).put("previousOpaqueClipRestoredInFinally",true).toString())}
                finally {ui.runOnIdle{previous?.let(clipboard::setPrimaryClip)?:clipboard.clearPrimaryClip()}}
            }
            viewport.performTouchInput {click(Offset(width-2f,height*.15f))};ui.waitForIdle();top();text.performTouchInput {longClick(layout.getBoundingBox(2).center)};ui.waitUntil(10_000){handles().fetchSemanticsNodes().size==2};shot("selected-before-replacement")
            f.turn="title-turn-2";f.approvals=JSONArray().put(request(f.turn!!,"BETA replacement original request"));main {session.loadHistory(userInitiated=true)?.join()};ui.waitUntil(15_000){!session.historyLoading&&session.runtime!!.approvals.single().turnId==f.turn};ui.waitForIdle();assertTrue(handles().fetchSemanticsNodes().isEmpty());ui.runOnIdle{session.answer(original,if(question)""else"cancel",if(question)mapOf("q" to listOf("One"))else emptyMap(),destination)};assertTrue(f.server.requests.none{it.method=="POST"})
            if(question) {
                main {allowSyntheticRegressionSharing(session)}
                ui.onNodeWithTag("question-option-q-One").assertIsNotSelected();ui.onNodeWithTag("answer-question").assertIsNotEnabled()
                ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
                ui.onNodeWithTag("question-option-q-One").performScrollTo().performTouchInput {click(center)}
                ui.onNodeWithTag("question-custom-q").performScrollTo().performClick()
                ui.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
                ui.onNodeWithTag("question-custom-q").performTextInput("New answer");ui.waitForIdle();assertEquals("New answer",ui.onNodeWithTag("question-custom-q").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text);shot("current-question-keyboard")
                if(args.getString("viewportBottom")=="1") {
                    if(ui.onAllNodesWithTag("bottom-controls").fetchSemanticsNodes().isNotEmpty()) {
                        ui.onNode(hasScrollAction()and hasAnyDescendant(hasTestTag("answer-question"))and !hasTestTag("bottom-controls"),useUnmergedTree=true).performSemanticsAction(SemanticsActions.ScrollBy){it(0f,1_000_000f)};ui.waitForIdle()
                        val bottom=ui.onNodeWithTag("bottom-controls");bottom.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-1_000_000f)}
                        val range=bottom.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                        ui.waitForIdle();val maximum=range.maxValue();val before=range.value();val frame=bottom.fetchSemanticsNode().boundsInRoot;val origin=bottom.fetchSemanticsNode().positionInRoot
                        if(maximum>0f) {
                            val up=before<maximum/2f;val x=frame.left+frame.width*.85f-origin.x
                            val start=Offset(x,frame.top+frame.height*(if(up).85f else .15f)-origin.y);val end=Offset(x,frame.top+frame.height*(if(up).15f else .85f)-origin.y)
                            bottom.performTouchInput {swipe(start,end,400)};ui.waitForIdle()
                        }
                        File(folder,"bottom-native-scroll.json").writeText(JSONObject().put("maximumPx",maximum).put("beforePx",before).put("afterPx",range.value()).put("nativeScrollIfOverflow",maximum<=0f||kotlin.math.abs(range.value()-before)>.9f).put("visibleFrame",frame.toString()).put("innerPanelReadToEndBeforeParentDrag",true).put("noPost",f.server.requests.none{it.method=="POST"}).put("draftProtected",session.text=="Independent title draft").toString());shot("bottom-native-scroll");assertTrue("Native drag within the clipped product viewport scrolls overflowing controls",maximum<=0f||kotlin.math.abs(range.value()-before)>.9f)
                    }
                    val geometries=JSONArray()
                    val tags=listOf("permission-settings","generation-settings","reply-mode-queue","reply-mode-steer","composer-add","voice-input","message-input")
                    fun full(node: SemanticsNodeInteraction,label: String,minHeight: Float) {
                        if(ui.onAllNodesWithTag("bottom-controls").fetchSemanticsNodes().isNotEmpty())node.performScrollTo()
                        val raw=node.getUnclippedBoundsInRoot();val rect=node.fetchSemanticsNode().boundsInRoot;val height=raw.bottom.value-raw.top.value
                        geometries.put(JSONObject().put("label",label).put("rawHeightDp",height).put("visibleHeightPx",rect.height).put("density",ui.density.density).put("minimumHeightDp",minHeight).put("rawWidthDp",raw.right.value-raw.left.value).put("visibleWidthPx",rect.width))
                        File(folder,"bottom-keyboard-geometry.json").writeText(geometries.toString())
                        assertTrue("$label minimum natural height: $height",height>=minHeight-.5f);assertTrue("$label fully reachable above the IME",rect.height>=height*ui.density.density-1.5f&&rect.width>=(raw.right.value-raw.left.value)*ui.density.density-1.5f);shot("bottom-keyboard-$label")
                    }
                    for(tag in tags)full(ui.onNodeWithTag(tag),tag,if(tag=="message-input")1f else 44f)
                    val draftLayouts=mutableListOf<TextLayoutResult>();ui.onNodeWithTag("message-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(draftLayouts)};val draftLayout=draftLayouts.single()
                    assertEquals("Independent title draft",draftLayout.layoutInput.text.text);assertFalse(draftLayout.hasVisualOverflow)
                    File(folder,"draft-text-layout.json").writeText(JSONObject().put("paragraphHeight",draftLayout.multiParagraph.height).put("layoutHeight",draftLayout.size.height).put("noVisualOverflow",!draftLayout.hasVisualOverflow).put("scope","inner editable glyph bounds exclude the outer vertical input padding").toString())
                    val action=ui.onNode(hasAnyAncestor(hasTestTag("composer"))and (hasTestTag("send-message")or hasTestTag("stop-turn")or hasTestTag("resume-task")or hasTestTag("composer-busy")))
                    full(action,"composer-action",48f)
                    assertEquals("Independent title draft",ui.onNodeWithTag("message-input").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text);assertTrue(f.server.requests.none{it.method=="POST"})
                    if(ui.onAllNodesWithTag("bottom-controls").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("bottom-controls").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-1_000_000f)}
                }
                val button=ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled();ui.waitForIdle()
                if(ui.onAllNodesWithTag("bottom-controls").fetchSemanticsNodes().isNotEmpty())repeat(2) {
                    val outer=ui.onNodeWithTag("bottom-controls");val visible=outer.fetchSemanticsNode().boundsInRoot;val node=button.fetchSemanticsNode()
                    val top=node.positionInRoot.y;val bottom=top+node.size.height
                    val correction=if(bottom>visible.bottom)bottom-visible.bottom else if(top<visible.top)top-visible.top else 0f
                    if(kotlin.math.abs(correction)>1f)outer.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,correction)};ui.waitForIdle()
                }
                val raw=button.getUnclippedBoundsInRoot();val rect=button.fetchSemanticsNode().boundsInRoot;val frame=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot
                val imeVisible=androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
                File(folder,"keyboard-proof.json").writeText(JSONObject().put("imeVisible",imeVisible).put("buttonFullHeightDp",raw.bottom.value-raw.top.value).put("buttonVisibleHeightPx",rect.height).put("density",ui.density.density).put("insideSafeProductViewport",rect.top>=frame.top&&rect.bottom<=frame.bottom).put("syntheticOnlySharingSetup",true).toString())
                assertEquals("New answer",ui.onNodeWithTag("question-custom-q").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text);assertTrue(imeVisible);assertTrue(raw.bottom.value-raw.top.value>=43.5f);assertTrue(rect.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f);assertTrue(rect.top>=frame.top&&rect.bottom<=frame.bottom);shot("current-question-submit-keyboard");button.performTouchInput {click(center)}
            }else ui.onNodeWithTag("approval-cancel").performScrollTo().performTouchInput {click(center)}
            ui.waitUntil(15_000){f.server.requests.any{it.method=="POST"}};val posts=f.server.requests.filter{it.method=="POST"};assertEquals(1,posts.size);val body=JSONObject(String(posts.single().body));assertEquals("title-turn-2",body.getString("turnId"));assertEquals("title-original",body.getString("approvalId"));assertEquals("fixture-thread",body.getString("threadId"));assertEquals(if(question)""else"cancel",body.getString("decision"));if(question)assertEquals(listOf("One","New answer"),body.getJSONObject("answers").getJSONArray("q").strings());assertEquals("Independent title draft",session.text)
            File(folder,"native-proof.json").writeText(JSONObject().put("originalUtf16Length",title.length).put("renderedLines",layout.lineCount).put("allLinesRead",true).put("sdkSelectionHandles",2).put("systemCopyVerified",args.getString("viewportCopy")=="1").put("replacementClearsOldSelection",true).put("oldCallbackNoPost",true).put("replacementExactPost",1).put("draftProtected",true).put("question",question).put("keyboardAnswerVerified",question).put("wholeBottomKeyboardVerified",args.getString("viewportBottom")=="1").toString())
        }
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
            File(folder,name+"-owned-menu.txt").writeText(diagnostic)
            val item=checkNotNull(action)
            // Native SystemUI RemoteViews are descendants of this verified own-App
            // root; use an SDK touch on that exact Copy label, never a SystemUI root.
            assertTrue(item.packageName?.toString() in listOf(InstrumentationRegistry.getInstrumentation().targetContext.packageName,"com.android.systemui"))
            assertTrue(item.viewIdResourceName.orEmpty().contains("floating_toolbar"))
            val rect=android.graphics.Rect();item.getBoundsInScreen(rect);assertFalse(rect.isEmpty)
            File(folder,name+"-menu-action.json").writeText(JSONObject().put("rootPackage",InstrumentationRegistry.getInstrumentation().targetContext.packageName).put("actionPackage",item.packageName?.toString()).put("actionId",item.viewIdResourceName).put("copyLabel",item.text?.toString()).put("sdkTargetedTouch",true).toString())
            val now=android.os.SystemClock.uptimeMillis()
            for((kind,time)in listOf(android.view.MotionEvent.ACTION_DOWN to now,android.view.MotionEvent.ACTION_UP to now+80)) {
                val event=android.view.MotionEvent.obtain(now,time,kind,rect.exactCenterX(),rect.exactCenterY(),0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue("SDK touch of the exact owned Copy label",automation.injectInputEvent(event,true)) }finally { event.recycle() }
            }

        }finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags } }
    }

}
