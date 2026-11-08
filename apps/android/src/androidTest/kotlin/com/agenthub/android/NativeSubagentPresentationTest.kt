package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Native disclosure and selection on the production Compose card with synthetic data. */
@RunWith(AndroidJUnit4::class)
class NativeSubagentPresentationTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private var message by mutableStateOf(MobileMessage("child","tool","原始子任务 100% %@",status="completed",kind="subAgent"))
    private var target by mutableStateOf("original-target")
    private var language by mutableStateOf("zh-Hans")
    private val bodyTag get()="subagent-detail-${message.id}"
    private val toggleTag get()="subagent-expand-${message.id}"
    private val originalDraft="unsubmitted-subagent-draft"

    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"subagent-presentation")
            owned.session.resetAcceptance()
            owned.session.text=originalDraft
        }
    }
    @After fun finish() {
        ui.runOnIdle {
            assertEquals(originalDraft,owned.session.text)
            assertTrue(owned.session.operationRecords.isEmpty())
            owned.session.leaveForeground()
            owned.verifyDefault()
        }
    }
    private fun mount(width: Int=360,height: Int=640,fontScale: Float=1f) {
        ui.runOnIdle { ui.activity.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                ProductTextProvider(language) {
                    MaterialTheme(colorScheme=lightColorScheme()) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopStart) {
                                Column(Modifier.width(width.dp).height(height.dp).verticalScroll(rememberScrollState())
                                    .testTag("subagent-test-viewport")) {
                                    EasyWorkSubagentContent(message,target,false)
                                }
                            }
                        }
                    }
                }
            }
        } }
    }
    private fun toggle()=ui.onNodeWithTag(toggleTag).performScrollTo().performClick()
    private fun disclosure(value: String)=ui.onNodeWithTag(toggleTag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,value))
    private fun renderedBody(): String=ui.onNodeWithTag(bodyTag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun longBody()="HEAD 原始🙂 100% %@\n"+"0123456789".repeat(3500)+
        "\nMIDDLE 原始中段\n"+"abcdefghij".repeat(3500)+"\nTAIL 原始末尾🙂"

    @Test fun expandedLongDetailSupportsNativeSelectionAndSystemClipboardCopy() {
        org.junit.Assume.assumeTrue("Restoring an empty system clipboard requires API 28",android.os.Build.VERSION.SDK_INT>=28)
        val original=longBody();ui.runOnIdle { message=message.copy(detail=original) };mount();toggle()
        ui.onNodeWithTag("subagent-test-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,-1_000_000f) }
        val text=ui.onNodeWithTag(bodyTag);val layouts=mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val clipboard=ui.activity.getSystemService(android.content.ClipboardManager::class.java)
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(10_000) { ui.activity.hasWindowFocus() }
        var previous: android.content.ClipData?=null
        ui.runOnIdle { previous=clipboard.primaryClip }
        if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("c2Physical")=="1") {
            org.junit.Assume.assumeNotNull(previous)
        }
        val flags=automation.serviceInfo.flags
        try {
            ui.runOnIdle { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sentinel","C2 sentinel")) }
            ui.runOnIdle { assertEquals("C2 sentinel",clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            text.performTouchInput { longClick(layouts.single().getBoundingBox(2).center) }
            val handles=ui.onAllNodes(SemanticsMatcher("Visible selection handle") { n -> n.config.any { it.key.name=="SelectionHandleInfo" && it.value.toString().contains("visible=true") } },useUnmergedTree=true)
            ui.waitUntil(10_000) { handles.fetchSemanticsNodes().size==2 }
            var action:android.view.accessibility.AccessibilityNodeInfo?=null
            ui.waitUntil(10_000) {
                val candidates=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun visit(n:android.view.accessibility.AccessibilityNodeInfo) {
                    if(n.text?.toString() in listOf("Copy","复制") && n.viewIdResourceName.orEmpty().contains("floating_toolbar")) candidates+=n
                    for(i in 0 until n.childCount)n.getChild(i)?.let(::visit)
                }
                automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==ui.activity.packageName }.forEach(::visit)
                action=candidates.singleOrNull();action!=null
            }
            val item=checkNotNull(action)
            assertEquals(ui.activity.packageName,item.packageName?.toString())
            val rect=android.graphics.Rect();item.getBoundsInScreen(rect);assertFalse(rect.isEmpty)
            val now=android.os.SystemClock.uptimeMillis()
            for((kind,time) in listOf(android.view.MotionEvent.ACTION_DOWN to now,android.view.MotionEvent.ACTION_UP to now+80)) {
                val event=android.view.MotionEvent.obtain(now,time,kind,rect.exactCenterX(),rect.exactCenterY(),0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(automation.injectInputEvent(event,true)) } finally { event.recycle() }
            }
            ui.waitUntil(5_000) { var copied:String?=null;ui.runOnUiThread { copied=clipboard.primaryClip?.getItemAt(0)?.text?.toString() };copied=="HEAD" }
            ui.runOnIdle { assertEquals("HEAD",clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            assertEquals(original,message.detail)
        } finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags };ui.runOnIdle { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
    }

    @Test fun expandingLongDetailRendersOriginalHeadMiddleAndTailWithoutChangingSource() {
        val original=longBody();assertTrue(original.length>65536)
        ui.runOnIdle { message=message.copy(detail=original) };mount()
        val preview=renderedBody()
        assertTrue(preview.endsWith("TAIL 原始末尾🙂"));assertFalse(preview.contains("HEAD"));assertTrue(preview.length<original.length)
        disclosure("已折叠");toggle();disclosure("已展开")
        assertEquals(original,renderedBody())
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(bodyTag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout=layouts.single();assertFalse(layout.hasVisualOverflow)
        assertEquals(original.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=false))
        assertEquals(original,message.detail)
        toggle();disclosure("已折叠");assertEquals(preview,renderedBody());assertEquals(original,message.detail)
    }

    @Test fun sameChildSnapshotUpdateReplacesExpandedDetailAndStatus() {
        val first="first original body";ui.runOnIdle { message=message.copy(detail=first,status="inProgress") };mount();toggle()
        val final="HEAD final\n"+"original-content ".repeat(5000)+"\nTAIL final"
        ui.runOnIdle { message=message.copy(detail=final,status="failed") }
        disclosure("已展开");assertEquals(final,renderedBody())
        ui.onNodeWithTag("subagent-status-child").assertTextEquals("失败")
        ui.onNodeWithTag("subagent-progress-child").assertDoesNotExist()
        assertFalse(renderedBody().contains(first))
    }

    @Test fun anotherTargetWithSameChildIdStartsCollapsed() {
        val original=longBody();ui.runOnIdle { message=message.copy(detail=original) };mount();toggle()
        disclosure("已展开");assertEquals(original,renderedBody())
        ui.runOnIdle { target="another-complete-runtime-target" }
        disclosure("已折叠");assertFalse(renderedBody().contains("HEAD"));assertEquals(original,message.detail)
    }

    @Test fun emptyRunningChildAndCompletedSnapshotUseMatchingWaitingAndStateControls() {
        ui.runOnIdle { message=message.copy(detail=null,status="inProgress") };mount()
        ui.onNodeWithTag("subagent-waiting-child").assertTextEquals("等待子 Agent 更新…")
        ui.onNodeWithTag("subagent-progress-child").assertExists();ui.onNodeWithTag(toggleTag).assertDoesNotExist()
        ui.runOnIdle { message=message.copy(detail="completed original child output",status="completed") }
        ui.onNodeWithTag("subagent-waiting-child").assertDoesNotExist()
        ui.onNodeWithTag("subagent-progress-child").assertDoesNotExist()
        ui.onNodeWithTag("subagent-status-child").assertTextEquals("成功")
        ui.onNodeWithTag(bodyTag).assertTextEquals("completed original child output")
    }

    @Test fun shortViewportLargeTypeKeepsLongTitleAndDisclosureReachable() {
        ui.runOnIdle { message=message.copy(text="原始标题 100% %@ ".repeat(12),detail="first\nsecond\nthird\nfourth\nfifth\nsixth\nlast") }
        mount(width=280,height=220,fontScale=1.6f)
        ui.onNodeWithTag("subagent-test-viewport").assertWidthIsEqualTo(280.dp).assertHeightIsEqualTo(220.dp)
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("subagent-title-child").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.single().lineCount>1);assertFalse(layouts.single().hasVisualOverflow)
        ui.onNodeWithTag(toggleTag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        disclosure("已展开")
        ui.onNodeWithTag(toggleTag).performScrollTo().assertIsDisplayed().performTouchInput { click() }
        disclosure("已折叠")
    }

    @Test fun languageChangesOnlyProductCaptionsAndPreservesExpandedOriginalText() {
        val original="展开 原始正文 100% %@\n尾部🙂";ui.runOnIdle { message=message.copy(detail=original,status="failed") };mount();toggle()
        ui.runOnIdle { language="en" }
        val english=NativeL10n(ui.activity,"en")
        assertNotEquals("子 Agent",english.tr("子 Agent"))
        ui.onNodeWithTag("subagent-title-child").assertTextEquals(english.tr("子 Agent")+" · "+message.text)
        ui.onNodeWithTag("subagent-status-child").assertTextEquals("Failed")
        disclosure("Expanded");assertEquals(original,renderedBody())
        ui.runOnIdle { message=message.copy(detail=null) }
        assertNotEquals("等待子 Agent 更新…",english.tr("等待子 Agent 更新…"))
        ui.onNodeWithTag("subagent-waiting-child").assertTextEquals(english.tr("等待子 Agent 更新…"))
    }

    @Test fun childIsAStandaloneTimelineRowAndOriginalWireRoleIsRetained() {
        val child=message.copy(detail="original child detail",runId="root-run")
        val input=listOf(MobileMessage("ordinary-tool","tool","original tool",kind="commandExecution"),child,
            MobileMessage("assistant","assistant","original assistant",status="completed",runId="root-run"))
        val timeline=NativeTimelineCache().project("original-complete-target",input,100,"root-run",false,null)
        assertEquals("message",timeline.rows.single { it.id==child.id }.kind)
        assertTrue(timeline.rows.filter { it.kind=="tools" }.all { 1 !in it.indices })
        assertEquals("tool",child.role);assertEquals("tool",child.timelineMessage().role)
        assertEquals("original child detail",child.detail)
    }
}
