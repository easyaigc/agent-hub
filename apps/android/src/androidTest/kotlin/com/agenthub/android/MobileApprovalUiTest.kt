package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Native product window, real session/HTTP/Keystore, only synthetic approval/question inputs. */
class MobileApprovalUiTest {
    private val args get()=InstrumentationRegistry.getArguments()
    private fun evidenceFolder()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-approval-ui/${args.getString("approvalUiRunId") ?: "fixture"}").apply { mkdirs() }
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation") ?: ""},{evidenceFolder()},{"question-actions"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val failure=newFailureWatcher()
    private fun newFailureWatcher()=object: TestWatcher() { override fun failed(error: Throwable,description: Description) { runCatching { shot("failed-${description.methodName}") } } }
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        args.getString("viewportOrientation")?.let { orientation ->
            val expected=if(orientation=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
            ui.waitUntil(10_000) { ui.activity.resources.configuration.orientation==expected }
        }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"approval-ui");session.resetAcceptance();args.getString("viewportTheme")?.let(session::changeAppearance) }
    }
    @After fun preserveDefault() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ExecutionFixture) {
        main { session.connect("synthetic approval parity",f.server.endpoint,"") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null&&session.models.isNotEmpty() }
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun read() { main { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading } }
    private fun posts(f: ExecutionFixture)=f.server.requests.filter { it.method=="POST" }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync { val view=ui.activity.window.decorView;view.postOnAnimation(object: Runnable { var remaining=3;override fun run() { if(--remaining==0)frames.countDown() else view.postOnAnimation(this) } }) }
        assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS));var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(ui.activity.window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(ui.activity.window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            val run=InstrumentationRegistry.getArguments().getString("approvalUiRunId") ?: "fixture"
            val folder=File(i.targetContext.filesDir,"acceptance/P4-approval-ui/$run").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.window").put("package",i.targetContext.packageName)
                .put("width",image.width).put("height",image.height).put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{image.recycle()}
    }
    private fun request(title: String="原操作审批")=JSONObject().put("id","approval-id").put("threadId","fixture-thread").put("turnId","approval-turn")
        .put("kind","command").put("title",title).put("detail","逐项核对原请求").put("questions",JSONArray()).put("decisions",JSONArray(listOf("accept","acceptForSession","decline","cancel")))
    private fun question(custom: Boolean=true)=JSONObject(request().toString()).put("kind","question").put("title","请选择原问题").put("decisions",JSONArray()).put("questions",JSONArray().put(JSONObject()
        .put("id","q").put("question","选择一个选项").put("multiple",false).put("custom",custom).put("isSecret",false).put("options",JSONArray()
            .put(JSONObject().put("label","One").put("description","第一项完整说明"))
            .put(JSONObject().put("label","Two").put("description","第二项说明")))))
    private fun approvals(f: ExecutionFixture)=posts(f).filter { it.path=="/v1/approvals" }
    private fun questionWindow(): android.view.Window {
        var window: android.view.Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object: androidx.test.espresso.ViewAction {
                override fun getDescription()="Find this acceptance process's question dialog window"
                override fun getConstraints()=androidx.test.espresso.matcher.ViewMatchers.isRoot()
                override fun perform(controller: androidx.test.espresso.UiController,view: android.view.View) {
                    assertEquals(InstrumentationRegistry.getInstrumentation().targetContext.packageName,view.context.packageName)
                    fun find(v: android.view.View) {
                        if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                        if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))
                    }
                    find(view);assertTrue(checkNotNull(window).decorView.rootView===view.rootView)
                }
            })
        return checkNotNull(window)
    }
    private fun questionShot(window: android.view.Window,name: String) {
        val i=InstrumentationRegistry.getInstrumentation();var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(owned.folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-question-dialog").put("package",i.targetContext.packageName).put("width",image.width).put("height",image.height).put("pixelCopyResult",result).toString())
        }finally{image.recycle()}
    }
    @Test fun asyncQuestionsOpenLargeAndKeepEachLocalDraftAcrossPagingAndCollapse() {
        ExecutionFixture().use { f ->
            fun async(id: String,qid: String)=question().put("id",id).put("interactionMode","async").apply {
                getJSONArray("questions").getJSONObject(0).put("id",qid)
            }
            f.approvals=JSONArray().put(async("first","q1")).put(async("second","q2"));connect(f)
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2")
            ui.onNodeWithTag("async-question-prev").assertIsNotEnabled()
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            fun large(name: String) {
                val window=questionWindow()
                ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
                ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
                ui.waitForIdle()
                val bounds=ui.onNodeWithTag("async-question-sheet").fetchSemanticsNode().boundsInRoot
                var height=0;ui.runOnUiThread { height=window.decorView.height }
                val visible=(minOf(bounds.bottom,height.toFloat())-maxOf(bounds.top,0f)).coerceAtLeast(0f)
                File(owned.folder,"$name-geometry.json").writeText(JSONObject().put("reference","owned-question-dialog-content").put("imeHidden",true).put("windowHeight",height).put("contentTop",bounds.top).put("visibleContentHeightRatio",visible/height).toString())
                questionShot(window,name)
                // Content excludes the drag handle and safe insets (larger on API26).
                // A partially expanded sheet fails both bounds even with the IME hidden.
                assertTrue("Initial and reopened large question content: height=$height bounds=$bounds ratio=${visible/height}",visible/height>=.75f&&bounds.top<=height*.2f)
            }
            large("async-large-initial")
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
            val submit=ui.onNodeWithTag("answer-question").getUnclippedBoundsInRoot()
            val skip=ui.onNodeWithTag("skip-question").getUnclippedBoundsInRoot()
            val card=ui.onNodeWithTag("approval-first").getUnclippedBoundsInRoot()
            val contentWidth=card.right.value-card.left.value-24f
            assertTrue("Async skip remains at least44dp",skip.right.value-skip.left.value>=43.5f&&skip.bottom.value-skip.top.value>=43.5f)
            assertTrue("Async submit fills remaining width",kotlin.math.abs(submit.right.value-skip.left.value-contentWidth)<=1f)
            assertTrue("Async actions keep12dp gap",kotlin.math.abs(submit.left.value-skip.right.value-12f)<=1f)
            File(owned.folder,"async-action-geometry.json").writeText(JSONObject().put("contentWidthDp",contentWidth).put("skipWidthDp",skip.right.value-skip.left.value).put("submitWidthDp",submit.right.value-submit.left.value).put("gapDp",submit.left.value-skip.right.value).put("minimumHeightDp",minOf(submit.bottom.value-submit.top.value,skip.bottom.value-skip.top.value)).toString())
            ui.onNodeWithTag("question-option-q1-One").performClick()
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("question-custom-q2").performScrollTo().performTextReplacement("第二题本地草稿")
            ui.onNodeWithTag("answer-question").assertIsEnabled()
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.onNodeWithTag("open-async-questions").performClick()
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
            ui.onNodeWithTag("async-question-next").assertIsNotEnabled()
            ui.onNodeWithTag("question-custom-q2").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("第二题本地草稿")))
            large("async-large-reopened")
            ui.onNodeWithTag("async-question-sheet").performTouchInput { swipeRight() }
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2")
            ui.onNodeWithTag("question-option-q1-One").assertIsSelected()
            ui.onNodeWithTag("async-question-sheet").performTouchInput { swipeLeft() }
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
            ui.onNodeWithTag("question-custom-q2").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("第二题本地草稿")))
            assertTrue("Local editing, paging and collapse never submit an answer",posts(f).isEmpty())
        }
    }
    private fun captured(tag: String): ()->Unit {
        ui.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
        val original=session.runtime!!.approvals.single();val destination=session.approvalActionDestination()
        // A removed Compose modifier cannot be invoked. Exercise the captured business boundary;
        // the current control below is still submitted through the actual native UI.
        return { session.answer(original,"decline",emptyMap(),destination) }
    }
    @Test fun asyncQuestionKeyboardPreservesDraftAcrossCollapseAndPaging() {
        ExecutionFixture().use { f ->
            fun async(id: String,qid: String)=question().put("id",id).put("interactionMode","async").apply {
                getJSONArray("questions").getJSONObject(0).put("id",qid).put("question",(1..16).joinToString("\n") { "原问题第${it}行 / Original question line$it" })
            }
            f.approvals=JSONArray().put(async("keyboard-first","keyboard-one")).put(async("keyboard-second","keyboard-two"));connect(f)
            ui.runOnIdle { session.text="Independent async keyboard draft" }
            val field=ui.onNodeWithTag("question-custom-keyboard-one").performScrollTo()
            field.performTouchInput { click(center) }
            val window=questionWindow()
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            field.performTextReplacement("Original local answer / 原问题草稿")
            ui.onNodeWithTag("question-custom-keyboard-one").assertTextContains("Original local answer / 原问题草稿")
            val geometry=JSONArray()
            for(tag in listOf("question-custom-keyboard-one","skip-question","answer-question","async-question-prev","async-question-next","close-async-questions")) {
                val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val raw=node.getUnclippedBoundsInRoot();val visible=node.fetchSemanticsNode().boundsInRoot
                val rawHeight=raw.bottom.value-raw.top.value
                geometry.put(JSONObject().put("tag",tag).put("rawHeightDp",rawHeight).put("visibleHeightPx",visible.height).put("density",ui.density.density))
                File(owned.folder,"async-keyboard-geometry.json").writeText(JSONObject().put("imeVisible",true).put("controls",geometry).put("noPost",posts(f).isEmpty()).toString())
                questionShot(window,"async-keyboard-control-$tag")
                assertTrue("Entire question control remains visible above the keyboard: $tag",visible.height>=rawHeight*ui.density.density-1.5f)
                assertTrue("Question action minimum height: $tag",rawHeight>=43.5f)
            }
            assertTrue(androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
            File(owned.folder,"async-keyboard-geometry.json").writeText(JSONObject().put("imeVisible",true).put("controls",geometry).put("noPost",posts(f).isEmpty()).toString())
            questionShot(window,"async-keyboard-controls")
            ui.onNodeWithTag("close-async-questions").performTouchInput { click(center) }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
            ui.onNodeWithTag("open-async-questions").performScrollTo().performClick()
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2")
            ui.onNodeWithTag("question-custom-keyboard-one").performScrollTo().assertTextContains("Original local answer / 原问题草稿")
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
            ui.onNodeWithTag("question-custom-keyboard-two").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText,AnnotatedString("")))
            ui.onNodeWithTag("async-question-prev").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("question-custom-keyboard-one").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2")
            ui.onNodeWithTag("question-custom-keyboard-one").performScrollTo().assertTextContains("Original local answer / 原问题草稿")
            assertEquals("Independent async keyboard draft",session.text);assertTrue(posts(f).isEmpty())
            questionShot(questionWindow(),"async-keyboard-draft-reopened")
            ui.onNodeWithTag("async-question-sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { assertTrue(it()) }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.onNodeWithTag("open-async-questions").performScrollTo().performClick()
            ui.onNodeWithTag("question-custom-keyboard-one").performScrollTo().assertTextContains("Original local answer / 原问题草稿")
            assertTrue(posts(f).isEmpty())
            File(owned.folder,"async-keyboard-proof.json").writeText(JSONObject().put("imeWasVisible",true).put("collapseHidesKeyboard",true).put("originalAnswerDraftPreserved",true).put("otherQuestionEmpty",true).put("chatDraftPreserved",true).put("nativeDismissPreservesDraft",true).put("noPost",true).toString())
        }
    }
    @Test fun fullOptionRowIncludesDescriptionAndSelectionSemantics() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question());connect(f)
            ui.onNodeWithTag("question-option-q-One").performScrollTo().assertTextContains("第一项完整说明")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton)).assertIsNotSelected().performClick()
            ui.onNodeWithTag("question-option-q-One").assertIsSelected();ui.onNodeWithTag("answer-question").assertIsEnabled()
            assertTrue(posts(f).isEmpty());shot("option-full-row-selected")
        }
    }
    @Test fun fullWidthQuestionActionAndNativeAccessibilityKeepExactAnswers() {
        ExecutionFixture().use { f ->
            val original=question()
            original.getJSONArray("questions")
                .put(JSONObject().put("id","multi").put("question","选择多项").put("multiple",true).put("custom",false).put("isSecret",false)
                    .put("options",JSONArray().put(JSONObject().put("label","Multi Alpha").put("description","First multiple description")).put(JSONObject().put("label","Multi Beta").put("description","Second multiple description"))))
                .put(JSONObject().put("id","secret").put("question","输入保密回复").put("custom",true).put("isSecret",true).put("options",JSONArray()))
            f.approvals=JSONArray().put(original);connect(f)
            ui.runOnIdle { session.text="Independent accessibility draft" }
            val submit=ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
            val card=ui.onNodeWithTag("approval-approval-id").getUnclippedBoundsInRoot()
            val bounds=submit.getUnclippedBoundsInRoot();val width=bounds.right.value-bounds.left.value
            val expected=card.right.value-card.left.value-24f
            File(evidenceFolder(),"submit-width.json").writeText(JSONObject().put("widthDp",width).put("expectedContentWidthDp",expected).put("heightDp",bounds.bottom.value-bounds.top.value).put("theme",session.appearance).put("fontScale",ui.density.fontScale).toString())
            shot("question-action-before-answer")
            assertTrue("Question primary action fills the card content width: $width / $expected",width>=expected-1f)
            assertTrue(bounds.bottom.value-bounds.top.value>=43.5f)
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            fun native(label: String): android.view.accessibility.AccessibilityNodeInfo {
                var result: android.view.accessibility.AccessibilityNodeInfo?=null
                var snapshot=JSONArray()
                try { ui.waitUntil(10_000) {
                    snapshot=JSONArray()
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                        if(node.packageName?.toString()!=InstrumentationRegistry.getInstrumentation().targetContext.packageName)return
                        snapshot.put(JSONObject().put("class",node.className).put("text",node.text).put("description",node.contentDescription).put("clickable",node.isClickable).put("checkable",node.isCheckable).put("checked",node.isChecked).put("selected",node.isSelected))
                        if(node.text?.toString()?.contains(label)==true||node.contentDescription?.toString()?.contains(label)==true) {
                            var action: android.view.accessibility.AccessibilityNodeInfo?=node
                            while(action!=null&&action.packageName?.toString()==InstrumentationRegistry.getInstrumentation().targetContext.packageName&&!action.isCheckable)action=action.parent
                            if(action?.isCheckable==true&&action.packageName?.toString()==InstrumentationRegistry.getInstrumentation().targetContext.packageName)result=action
                        }
                        for(n in 0 until node.childCount)node.getChild(n)?.let(::visit)
                    }
                    automation.rootInActiveWindow?.let(::visit);result!=null
                } }catch(error: Throwable){File(evidenceFolder(),"native-choice-lookup.json").writeText(snapshot.toString());throw error}
                return checkNotNull(result)
            }
            val states=JSONArray()
            fun choose(tag: String,label: String,selected: Boolean,multiple: Boolean) {
                ui.onNodeWithTag(tag).performScrollTo().assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,if(multiple)androidx.compose.ui.semantics.Role.Checkbox else androidx.compose.ui.semantics.Role.RadioButton))
                val before=native(label)
                assertTrue(before.isEnabled&&before.isCheckable);assertEquals(!selected,before.isChecked)
                assertTrue(before.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                ui.waitUntil(10_000) { val node=native(label);node.isChecked==selected }
                states.put(JSONObject().put("label",label).put("class",before.className).put("checkable",true).put("checkedAfterSdkAction",selected).put("enabled",true))
            }
            choose("question-option-q-One","One",true,false)
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
            choose("question-option-multi-Multi Alpha","Multi Alpha",true,true)
            choose("question-option-multi-Multi Alpha","Multi Alpha",false,true)
            choose("question-option-multi-Multi Beta","Multi Beta",true,true)
            choose("question-option-multi-Multi Alpha","Multi Alpha",true,true)
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
            val secret=ui.onNodeWithTag("question-custom-secret").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            secret.performClick()
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            secret.performTextReplacement("  synthetic-private-answer  ")
            assertEquals("  synthetic-private-answer  ",secret.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
            File(evidenceFolder(),"secret-input-ready-before-done.json").writeText(JSONObject().put("nativeImeVisible",true).put("rawInputMatchedBeforeDone",true).toString())
            secret.performImeAction()
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
            ui.onNodeWithTag("question-custom-secret").performScrollTo().assertIsDisplayed()
            assertEquals("Done retains exact original secret", "  synthetic-private-answer  ",secret.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
            assertTrue("Done never submits an approval",approvals(f).isEmpty())
            var password: android.view.accessibility.AccessibilityNodeInfo?=null
            fun passwordNode(node: android.view.accessibility.AccessibilityNodeInfo) {
                if(node.packageName?.toString()!=InstrumentationRegistry.getInstrumentation().targetContext.packageName)return
                if(node.isPassword)password=node
                for(n in 0 until node.childCount)node.getChild(n)?.let(::passwordNode)
            }
            ui.waitUntil(5_000) { password=null;automation.rootInActiveWindow?.let(::passwordNode);password!=null }
            assertNotNull("Native accessibility exposes secure input",password)
            assertFalse("Native accessibility must not expose the synthetic secret",password!!.text?.toString()?.contains("synthetic-private-answer")==true)
            File(evidenceFolder(),"native-accessibility.json").writeText(JSONObject().put("choiceStates",states).put("secureInputMasked",true).put("nativeSdkActions",true).put("systemTalkBackSpeechVerified",false).toString())
            val ready=ui.onNodeWithTag("answer-question").fetchSemanticsNode().config
            File(evidenceFolder(),"question-ready-state.json").writeText(JSONObject()
                .put("singleSelected",ui.onNodeWithTag("question-option-q-One").fetchSemanticsNode().config[SemanticsProperties.Selected])
                .put("alphaState",ui.onNodeWithTag("question-option-multi-Multi Alpha").fetchSemanticsNode().config[SemanticsProperties.ToggleableState].toString())
                .put("betaState",ui.onNodeWithTag("question-option-multi-Multi Beta").fetchSemanticsNode().config[SemanticsProperties.ToggleableState].toString())
                .put("secretInputPresent",ui.onNodeWithTag("question-custom-secret").fetchSemanticsNode().config[SemanticsProperties.InputText].text=="  synthetic-private-answer  ")
                .put("requestCanAnswer",session.canAnswer(session.runtime!!.approvals.single()))
                .put("buttonDisabled",ready.contains(SemanticsProperties.Disabled)).toString())
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled();shot("question-action-ready")
            ui.onNodeWithTag("answer-question").performTouchInput { click(center) };ui.waitUntil(10_000) { approvals(f).size==1 }
            val body=JSONObject(String(approvals(f).single().body))
            assertEquals("fixture-host",body.getString("hostId"));assertEquals("fixture-thread",body.getString("threadId"));assertEquals("approval-turn",body.getString("turnId"));assertEquals("approval-id",body.getString("approvalId"))
            assertEquals(listOf("One"),body.getJSONObject("answers").getJSONArray("q").strings())
            assertEquals(listOf("Multi Beta","Multi Alpha"),body.getJSONObject("answers").getJSONArray("multi").strings())
            assertEquals(listOf("synthetic-private-answer"),body.getJSONObject("answers").getJSONArray("secret").strings())
            assertEquals("Independent accessibility draft",session.text)
            File(evidenceFolder(),"exact-answer-proof.json").writeText(JSONObject().put("oneExactOriginalHttp",true).put("allRequiredQuestionsValidated",true).put("multipleOrderPreserved",true).put("secretTrimmed",true).put("draftProtected",true).toString())
        }
    }
    @Test fun longAsyncAnswerKeepsWholeFieldVisibleAndNativeSelectionEditable() {
        ExecutionFixture().use { f ->
            val original=question().put("id","long-viewport").put("interactionMode","async")
            original.getJSONArray("questions").getJSONObject(0).put("options",JSONArray()).put("question","Long original answer")
            f.approvals=JSONArray().put(original);connect(f)
            ui.runOnIdle { session.text="Independent long-viewport composer" }
            val field=ui.onNodeWithTag("question-custom-q").performScrollTo().performClick()
            val window=questionWindow()
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            val value=(1..24).joinToString("\n") { "Original answer line $it" };field.performTextReplacement(value)
            field.performScrollTo().assertIsDisplayed();ui.waitForIdle()
            val raw=field.getUnclippedBoundsInRoot();val visible=field.fetchSemanticsNode().boundsInRoot
            val geometry=JSONObject().put("rawHeightDp",raw.bottom.value-raw.top.value).put("visibleHeightPx",visible.height).put("density",ui.density.density).put("fontScale",ui.density.fontScale).put("imeVisible",true)
            File(owned.folder,"long-field-geometry.json").writeText(geometry.toString());questionShot(window,"long-field-before-selection")
            assertTrue("Whole long field must fit in the short IME viewport: $geometry",visible.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
            assertTrue(raw.bottom.value-raw.top.value>=43.5f)
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            fun editable(): android.view.accessibility.AccessibilityNodeInfo {
                var found: android.view.accessibility.AccessibilityNodeInfo?=null
                var snapshot=JSONArray()
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    if(node.packageName?.toString()!=InstrumentationRegistry.getInstrumentation().targetContext.packageName)return
                    snapshot.put(JSONObject().put("editable",node.isEditable).put("password",node.isPassword).put("enabled",node.isEnabled).put("textLength",node.text?.length).put("description",node.contentDescription).put("selectionStart",node.textSelectionStart).put("selectionEnd",node.textSelectionEnd))
                    if(node.isEditable&&!node.isPassword&&node.isEnabled&&node.text?.toString()==value)found=node
                    for(n in 0 until node.childCount)node.getChild(n)?.let(::visit)
                }
                try { ui.waitUntil(10_000) { snapshot=JSONArray();found=null;automation.rootInActiveWindow?.let(::visit);found!=null } }finally { File(owned.folder,"native-editable-snapshot.json").writeText(snapshot.toString()) };return checkNotNull(found)
            }
            fun select(start: Int,end: Int,name: String) {
                val args=android.os.Bundle().apply {putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end)}
                val actionNeeded=field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]!=androidx.compose.ui.text.TextRange(start,end)
                if(actionNeeded)assertTrue(editable().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,args))
                var stable=0L
                try { ui.waitUntil(10_000) {
                    val actual=field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange];val node=editable()
                    val nativeRangeKnown=node.textSelectionStart>=0&&node.textSelectionEnd>=0
                    val matching=actual==androidx.compose.ui.text.TextRange(start,end)&&(!nativeRangeKnown||node.textSelectionStart==start&&node.textSelectionEnd==end)
                    if(!matching)stable=0L else if(stable==0L)stable=android.os.SystemClock.uptimeMillis()
                    matching&&android.os.SystemClock.uptimeMillis()-stable>=300L
                } }catch(error: Throwable) {
                    val actual=field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange];val node=editable()
                    File(owned.folder,"long-field-selection-failure.json").writeText(JSONObject().put("wantedStart",start).put("wantedEnd",end).put("composeStart",actual.start).put("composeEnd",actual.end).put("nativeStart",node.textSelectionStart).put("nativeEnd",node.textSelectionEnd).put("nodeFocused",node.isFocused).put("nativeRawLength",node.text?.length).put("package",node.packageName).toString())
                    questionShot(window,"long-field-selection-failure");throw error
                }
                assertEquals(value,field.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
                File(owned.folder,"long-field-"+name+".json").writeText(JSONObject().put("method","native SDK ACTION_SET_SELECTION").put("start",start).put("end",end).put("rawCharacters",value.length).put("actionNeeded",actionNeeded).put("nativeSelectionStart",editable().textSelectionStart).put("nativeSelectionEnd",editable().textSelectionEnd).put("composeSelectionStableMs",300).put("noPost",posts(f).isEmpty()).toString())
                questionShot(window,"long-field-"+name)
            }
            select(0,value.length,"all-selected");select(0,0,"first-line");select(value.length,value.length,"last-line")
            field.performTouchInput { swipeDown() };assertEquals(value,field.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
            select(value.length,value.length,"end-after-scroll");field.performTextInput("\nNative final edit")
            assertEquals(value+"\nNative final edit",field.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
            assertTrue(posts(f).isEmpty());assertEquals("Independent long-viewport composer",session.text)
            questionShot(window,"long-field-edited-no-submit")
            ui.onNodeWithTag("close-async-questions").performScrollTo().performClick();ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.onNodeWithTag("open-async-questions").performScrollTo().performClick()
            assertEquals(value+"\nNative final edit",ui.onNodeWithTag("question-custom-q").fetchSemanticsNode().config[SemanticsProperties.InputText].text)
            assertTrue(posts(f).isEmpty())
        }
    }
    @Test fun longCustomAnswersKeepBoundedFieldsAndDoneNeverSubmits() {
        ExecutionFixture().use { f ->
            val proof=JSONArray()
            for(secret in listOf(false,true)) {
                val id=if(secret)"long-secret"else"long-normal"
                val value=if(secret)"synthetic-private-"+"x".repeat(1600)else(1..24).joinToString("\n") { "Original reply line $it / 原回复正文" }
                val q=JSONObject().put("id",id).put("question",if(secret)"保密回复"else"长回复")
                    .put("multiple",false).put("custom",true).put("isSecret",secret).put("options",JSONArray())
                f.approvals=JSONArray().put(JSONObject(question().toString()).put("questions",JSONArray().put(q)))
                if(secret)read()else{connect(f);ui.runOnIdle { session.text="Independent bounded-field draft" }}
                val field=ui.onNodeWithTag("question-custom-$id")
                field.performTextReplacement(value)
                assertEquals(value,field.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
                val font=ui.activity.resources.configuration.fontScale
                val bounds=field.getUnclippedBoundsInRoot();val raw=bounds.bottom.value-bounds.top.value
                val maximum=40f+24f*font*(if(secret)1 else 4)+20f*font
                val row=JSONObject().put("secret",secret).put("fontScale",font).put("rawHeightDp",raw).put("maximumHeightDp",maximum)
                proof.put(row);File(evidenceFolder(),"bounded-field-geometry.json").writeText(proof.toString())
                assertTrue("Reply field stays within the iOS line budget: $row",raw<=maximum)
                field.performScrollTo().assertIsDisplayed()
                if(secret) {
                    field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                    field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,androidx.compose.ui.text.input.ImeAction.Done))
                    field.performClick().performImeAction()
                    ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
                }
                assertEquals(value,field.fetchSemanticsNode().config[SemanticsProperties.InputText].text)
                assertTrue(approvals(f).isEmpty());assertEquals("Independent bounded-field draft",session.text)
                field.performScrollTo();shot("bounded-$id")
            }
            File(evidenceFolder(),"bounded-field-proof.json").writeText(JSONObject().put("plainFourLineBudget",true).put("secretSingleLineBudget",true).put("longRawAnswersPreserved",true).put("doneDoesNotSubmit",true).put("independentDraftPreserved",true).toString())
        }
    }
    @Test fun applicationApprovalShowsOriginalScopesAndSubmitsExactDecision() {
        ExecutionFixture().use { f ->
            f.approvals=JSONArray().put(request("允许使用 Calculator？")
                .put("decisions",JSONArray(listOf("accept","acceptAlways","decline","cancel")))
                .put("decisionLabels",JSONObject().put("accept","本会话允许").put("acceptAlways","始终允许").put("decline","拒绝").put("cancel","取消")))
            connect(f)
            ui.onNodeWithTag("approval-accept").assertTextEquals("本会话允许")
            ui.onNodeWithTag("approval-cancel").assertTextEquals("取消")
            ui.onNodeWithTag("approval-acceptAlways").performScrollTo().assertTextEquals("始终允许").performClick()
            ui.waitUntil(10_000) { approvals(f).size==1 }
            val body=JSONObject(String(approvals(f).single().body))
            assertEquals("acceptAlways",body.getString("decision"))
            assertEquals("approval-id",body.getString("approvalId"))
            assertEquals("fixture-thread",body.getString("threadId"))
            assertEquals("approval-turn",body.getString("turnId"))
            assertEquals("mcp-v1",approvals(f).single().headers["x-agent-hub-approvals"])
            assertEquals(0,body.getJSONObject("answers").length())
        }
    }
    @Test fun capturedApprovalCannotAnswerChangedContentsWithReusedIdAndTurn() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(request());connect(f);val originalRequest=session.runtime!!.approvals.single();val old=captured("approval-decline")
            f.approvals=JSONArray().put(request("同ID但新的操作正文"));read();ui.runOnIdle { old();assertFalse("Old contents must not acquire submission ownership",session.isAnswering(originalRequest)) }
            ui.waitForIdle();assertTrue("An old callback cannot authorize changed original content",approvals(f).isEmpty())
            ui.onNodeWithTag("approval-decline").performScrollTo().performClick();ui.waitUntil(10_000) { approvals(f).size==1 }
            shot("changed-request-current-only")
        }
    }
    @Test fun capturedApprovalCannotSurviveNavigationAwayAndBack() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(request());connect(f);val originalRequest=session.runtime!!.approvals.single();val old=captured("approval-decline");val original=session.threads.first()
            ui.runOnIdle { session.newChat(null);session.selectThread(original) };ui.waitUntil(10_000) { !session.historyLoading&&session.runtime?.approvals?.size==1 }
            ui.runOnIdle { old();assertFalse("Old navigation must not acquire submission ownership",session.isAnswering(originalRequest)) };ui.waitForIdle();assertTrue("Navigation invalidates captured authority",approvals(f).isEmpty())
            ui.onNodeWithTag("approval-decline").performScrollTo().performClick();ui.waitUntil(10_000) { approvals(f).size==1 };shot("revisit-current-request-only")
        }
    }
    @Test fun cachedQuestionKeepsEditableLocalAnswersWithoutCommandAuthority() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question());connect(f);ui.runOnIdle { session.leaveForeground() }
            ui.onNodeWithTag("question-option-q-Two").performScrollTo().assertIsEnabled().performClick()
            ui.onNodeWithTag("question-custom-q").performScrollTo().assertIsEnabled().performTextReplacement("仅本地保留")
            ui.onNodeWithTag("answer-question").assertIsNotEnabled();assertTrue(posts(f).isEmpty());shot("cached-local-question-edit-only")
        }
    }
    @Test fun pendingAnswerDisablesFieldsAndShowsNativeProgressUntilOriginalCompletes() {
        ExecutionFixture().use { f ->
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native {
                override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) {
                    started.complete(Unit);release.await();native.answer(client,threadId,turnId,approvalId,decision,answers)
                }
            }
            try {
                ui.runOnIdle { session.resetAcceptance(commandProvider=commands) };f.approvals=JSONArray().put(question());connect(f)
                ui.onNodeWithTag("question-option-q-One").performScrollTo().performClick();ui.onNodeWithTag("answer-question").performScrollTo().performClick()
                runBlocking { withTimeout(5_000) { started.await() } }
                ui.onNodeWithTag("approval-pending-progress").assertExists();ui.onNodeWithTag("question-option-q-One").assertIsNotEnabled()
                ui.onNodeWithTag("question-custom-q").assertIsNotEnabled();ui.onNodeWithTag("answer-question").assertIsNotEnabled();shot("pending-answer-all-controls-disabled")
                release.complete(Unit);ui.waitUntil(10_000) { approvals(f).size==1&&!session.isAnswering(session.runtime!!.approvals.firstOrNull() ?: MobileApproval("approval-id","fixture-thread","approval-turn","question","","",emptyList(),emptyList(),null)) }
            }finally{release.complete(Unit)}
        }
    }
    @Test fun changedQuestionSchemaResetsSelectionBeforeAnswering() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question());connect(f)
            ui.onNodeWithTag("question-option-q-One").performScrollTo().performClick()
            val changed=question();changed.getJSONArray("questions").getJSONObject(0).put("question","新的问题正文")
            f.approvals=JSONArray().put(changed);read();ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            assertTrue(posts(f).isEmpty());shot("changed-schema-local-answer-reset")
        }
    }    @Test fun multipleSelectionTogglesAndSecretCustomAnswerKeepOriginalOrderAndScope() {
        ExecutionFixture().use { f ->
            val original=question();original.getJSONArray("questions").getJSONObject(0).put("multiple",true).put("isSecret",true)
            f.approvals=JSONArray().put(original);connect(f)
            ui.onNodeWithTag("question-option-q-One").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,androidx.compose.ui.semantics.Role.Checkbox)).assertIsOff().performClick()
            ui.onNodeWithTag("question-option-q-One").assertIsOn().performClick();ui.onNodeWithTag("question-option-q-One").assertIsOff()
            ui.onNodeWithTag("question-option-q-Two").performScrollTo().performClick();ui.onNodeWithTag("question-option-q-One").performScrollTo().performClick()
            ui.onNodeWithTag("question-custom-q").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextReplacement("  synthetic-only-private-answer  ")
            ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText,AnnotatedString("  synthetic-only-private-answer  ")))
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled().performClick();ui.waitUntil(10_000) { approvals(f).size==1 }
            val body=JSONObject(String(approvals(f).single().body));assertEquals("fixture-host",body.getString("hostId"));assertEquals("fixture-thread",body.getString("threadId"));assertEquals("approval-turn",body.getString("turnId"));assertEquals("approval-id",body.getString("approvalId"))
            assertEquals(listOf("Two","One","synthetic-only-private-answer"),body.getJSONObject("answers").getJSONArray("q").strings())
        }
    }

}
