package com.agenthub.android

import android.app.UiModeManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID

/** Actual narrow themed controls, inherited OS font scale and native disabled owner. No controller,
 * Host, microphone, ASR or system-contrast change. Full recovery/native screenshots remain Root-owned. */
class NativeVoiceFailureStyleUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    private lateinit var folder: File
    private var flagsBefore: Int?=null
    private val cleanupErrors=mutableListOf<Throwable>()
    private val rows=JSONArray()
    private var complete=false
    @Before fun claim() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName==NativeRealFileAsrContract.PACKAGE)
        require(i.context.packageName==NativeRealFileAsrContract.PACKAGE+".test");require(Build.VERSION.SDK_INT in setOf(26,37))
        val args=InstrumentationRegistry.getArguments();require(args.keySet().filter {it.startsWith("voiceStyle")}.toSet()==setOf("voiceStyleLease","voiceStyleFontScale"))
        val lease=checkNotNull(args.getString("voiceStyleLease"));require(UUID.fromString(lease).toString()==lease)
        val font=checkNotNull(args.getString("voiceStyleFontScale")).toFloat();require(font in setOf(1f,2f));assertEquals(font,ui.activity.resources.configuration.fontScale,.01f)
        val root=NativeRealFileAsrContract.privateDirectory(i.targetContext.filesDir,"acceptance");val group=NativeRealFileAsrContract.privateDirectory(root,"P7-voice-failure-style")
        folder=File(group,lease);require(!folder.exists());folder=NativeRealFileAsrContract.privateDirectory(group,lease)
        val service=i.uiAutomation.serviceInfo;flagsBefore=service.flags;service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;i.uiAutomation.serviceInfo=service
        NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject().put("lease",lease).put("fontScale",font)
            .put("appSha256",NativeRealFileAsrContract.sha(File(ui.activity.applicationInfo.sourceDir)))
            .put("runnerSha256",NativeRealFileAsrContract.sha(File(i.context.applicationInfo.sourceDir))).put("sdk",Build.VERSION.SDK_INT).put("hostMicModelCalls",0))
    }
    @After fun report() {
        try{flagsBefore?.let {old->val a=InstrumentationRegistry.getInstrumentation().uiAutomation;val s=a.serviceInfo;s.flags=old;a.serviceInfo=s;check(a.serviceInfo.flags==old)}}catch(t: Throwable){cleanupErrors.add(t)}
        if(::folder.isInitialized)NativeRealFileAsrContract.atomic(File(folder,"result.json"),JSONObject().put("checksCompleted",complete).put("observations",rows)
            .put("cleanupFailures",JSONArray(cleanupErrors.map {it.javaClass.name})).put("flagsRestored",cleanupErrors.isEmpty()).put("nativeComponentPassed",complete&&cleanupErrors.isEmpty()).put("fullDevPassed",false))
        org.junit.runners.model.MultipleFailureException.assertEmpty(cleanupErrors)
    }
    private fun textLayout(tag: String,label: String): TextLayoutResult {
        val values=mutableListOf<TextLayoutResult>()
        ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(values)}
        assertEquals(1,values.size);val l=values.single();assertEquals(label,l.layoutInput.text.text);assertFalse(l.hasVisualOverflow)
        assertEquals(label.length,l.getLineEnd(l.lineCount-1));assertTrue((0 until l.lineCount).none {l.isLineEllipsized(it)})
        assertEquals(15.sp,l.layoutInput.style.fontSize);assertEquals(20.sp,l.layoutInput.style.lineHeight);return l
    }
    @Suppress("DEPRECATION") private fun native(label: String,enabled: Boolean) {
        val a=InstrumentationRegistry.getInstrumentation().uiAutomation;val root=checkNotNull(a.rootInActiveWindow)
        val children=IdentityHashMap<android.view.accessibility.AccessibilityNodeInfo,Boolean>();val owners=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>();val budget=NativeSavedVoiceReadBudget();var count=0
        fun visit(n: android.view.accessibility.AccessibilityNodeInfo) {
            check(budget.active()&&++count<=256);assertEquals(NativeRealFileAsrContract.PACKAGE,n.packageName?.toString());assertEquals(root.windowId,n.windowId)
            if(n.viewIdResourceName=="stop-dictation")owners+=n;require(n.childCount<=32)
            for(index in 0 until n.childCount){val child=budget.read {n.getChild(index)?.also {if(it!==root)children[it]=true}}?.value;visit(checkNotNull(child))}
        }
        try {
            require(root.windowId>=0);visit(root);assertEquals(1,owners.size);val n=owners.single()
            assertEquals(label,n.text?.toString());assertTrue(n.contentDescription.isNullOrEmpty());assertEquals(enabled,n.isEnabled);assertTrue(n.isVisibleToUser)
            assertEquals(enabled,n.actionList.any {it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK})
        }finally{children.keys.forEach {try{it.recycle()}catch(t: Throwable){cleanupErrors.add(t)}};try{root.recycle()}catch(t: Throwable){cleanupErrors.add(t)}}
    }
    @Test fun bilingualCircleRetryKeepsTitleTokensIconAndDisabledNativeName() {
        val manager=ui.activity.getSystemService(UiModeManager::class.java)
        val increased=Build.VERSION.SDK_INT>=34&&(manager?.contrast ?: 0f)>=.5f
        var callbacks=0
        for(language in listOf("zh-Hans","en"))for(dark in listOf(false,true))for(action in listOf("重试转写","重新录音","重试丢弃"))for(enabled in listOf(false,true)) {
            val text=NativeL10n(ui.activity,language);val title=text.tr(if(action=="重新录音")"录音未完成"else"转写失败");val label=text.tr(action)
            ui.runOnIdle {ui.activity.setContent {NativeTheme(dark,language) {
                Surface(Modifier.width(280.dp).semantics {testTagsAsResourceId=true}) {
                    Column(Modifier.padding(12.dp)) {
                        VoiceFailureTitle(title,Modifier.fillMaxWidth())
                        Box(Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=.06f),androidx.compose.foundation.shape.CircleShape)) {
                            VoiceFailurePrimaryAction(label,enabled){callbacks++}
                        }
                    }
                }
            }}};ui.waitForIdle()
            val titleLayout=textLayout("voice-failure-title",title);assertEquals(FontWeight.Medium,titleLayout.layoutInput.style.fontWeight)
            assertEquals(VoiceFailureStyle.tint(dark,increased),titleLayout.layoutInput.style.color)
            ui.onAllNodes(hasAnyAncestor(hasTestTag("stop-dictation")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).assertCountEquals(0)
            ui.onNodeWithTag("voice-failure-icon",useUnmergedTree=true).assertIsDisplayed();ui.onNodeWithTag("voice-retry-icon",useUnmergedTree=true).assertIsDisplayed().assertWidthIsEqualTo(17.dp).assertHeightIsEqualTo(17.dp)
            val control=ui.onNodeWithTag("stop-dictation").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).assertTextEquals(label)
            if(enabled)control.assertIsEnabled()else control.assertIsNotEnabled();native(label,enabled)
            val before=callbacks;control.performTouchInput {click(center)};ui.runOnIdle {assertEquals(before+if(enabled)1 else 0,callbacks)}
            rows.put(JSONObject().put("language",language).put("dark",dark).put("actualIncreasedContrast",increased).put("label",label).put("enabled",enabled)
                .put("titleLines",titleLayout.lineCount).put("actionHasVisibleText",false).put("titleWeight",500).put("iconDp",17).put("hitDp",48))
        }
        complete=true
    }
}
