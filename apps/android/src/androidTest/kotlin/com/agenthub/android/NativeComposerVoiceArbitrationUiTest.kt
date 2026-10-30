package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.util.UUID

/** Actual nonempty BasicTextField, native selection/tap/IME handlers and real ancestor scroll.
 * The accepted start callback only records a decision; no controller, mic, Host or model. */
class NativeComposerVoiceArbitrationUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=2) val name=TestName()
    private lateinit var folder: File
    private val proof=JSONObject()
    private val observations=JSONArray()
    private var complete=false
    private val original="alpha beta gamma delta"
    private var value by mutableStateOf(TextFieldValue(original,TextRange(original.length)))
    private var owner by mutableStateOf<Any>("original-editor")
    private var navigation by mutableStateOf("original-navigation")
    private var enabled by mutableStateOf(true)
    private var initialClaim by mutableStateOf(false)
    private var laterClaim by mutableStateOf(false)
    private var starts=0
    private var recording=false
    private var nativeRangeChanges=0
    private var parentClaims=0
    private var focused=false
    private var nativeTimeout=0L
    private var fieldTimeout=0L
    private lateinit var layout: TextLayoutResult
    private lateinit var scroll: ScrollState
    @Before fun claim() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName==NativeRealFileAsrContract.PACKAGE)
        require(i.context.packageName==NativeRealFileAsrContract.PACKAGE+".test");require(android.os.Build.VERSION.SDK_INT in setOf(26,37))
        val args=InstrumentationRegistry.getArguments();require(args.keySet().filter {it.startsWith("voiceArbitration")}.toSet()==setOf("voiceArbitrationLease"))
        val lease=checkNotNull(args.getString("voiceArbitrationLease"));require(UUID.fromString(lease).toString()==lease)
        val root=NativeRealFileAsrContract.privateDirectory(i.targetContext.filesDir,"acceptance")
        val group=NativeRealFileAsrContract.privateDirectory(root,"P7-voice-arbitration")
        val owned=NativeRealFileAsrContract.privateDirectory(group,lease);folder=File(owned,name.methodName);require(!folder.exists())
        folder=NativeRealFileAsrContract.privateDirectory(owned,name.methodName)
        proof.put("lease",lease).put("case",name.methodName).put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("appSha256",NativeRealFileAsrContract.sha(File(ui.activity.applicationInfo.sourceDir)))
            .put("runnerSha256",NativeRealFileAsrContract.sha(File(i.context.applicationInfo.sourceDir)))
            .put("actualBasicTextField",true).put("microphoneHostModelCalls",0)
        NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject(proof.toString()))
    }
    @After fun report() {
        if(!::folder.isInitialized)return
        NativeRealFileAsrContract.atomic(File(folder,"result.json"),proof.put("checksCompleted",complete).put("observations",observations)
            .put("voiceStarts",starts).put("nativeRangeChanges",nativeRangeChanges).put("parentClaims",parentClaims)
            .put("outcome",if(complete)"NATIVE_GESTURE_COMPONENT_PASSED_COMPOSER_AND_REAL_DEV_SEPARATE"else"FAILED_OR_UNKNOWN"))
    }
    private fun content() {
        ui.runOnIdle {ui.activity.setContent {
            val native=LocalViewConfiguration.current;SideEffect {nativeTimeout=native.longPressTimeoutMillis}
            val scroller=rememberScrollState();SideEffect {scroll=scroller}
            val focus=remember {FocusRequester()}
            Column(Modifier.width(320.dp).height(220.dp).verticalScroll(scroller).pointerInput(initialClaim,laterClaim) {
                awaitEachGesture {
                    val down=awaitFirstDown(false,PointerEventPass.Initial)
                    if(initialClaim){down.consume();parentClaims++}
                    do {
                        val event=awaitPointerEvent(PointerEventPass.Main)
                        if(laterClaim)event.changes.filter {it.position!=it.previousPosition}.forEach {it.consume();parentClaims++}
                    }while(event.changes.any {it.pressed})
                }
            }.testTag("arbitration-scroll")) {
                key(owner,navigation) {
                    NativeComposerVoiceInput(owner,navigation,enabled,{value.selection},"Hold for voice","Voice input",{
                        if(!enabled||recording)false else {starts++;recording=true;true}
                    }) { input ->
                        val configuration=LocalViewConfiguration.current
                        SideEffect {fieldTimeout=configuration.longPressTimeoutMillis}
                        BasicTextField(value,{next->
                            if(next.selection!=value.selection&&!next.selection.collapsed)nativeRangeChanges++
                            value=next
                        },Modifier.fillMaxWidth().height(96.dp).then(input).focusRequester(focus)
                            .onFocusChanged {focused=it.isFocused}.testTag("arbitration-field"),
                            textStyle=TextStyle(fontSize=18.sp),maxLines=4,onTextLayout={layout=it})
                    }
                }
                Spacer(Modifier.height(640.dp))
            }
        }};ui.waitForIdle()
    }
    private fun word(index: Int)=layout.getBoundingBox(index).center
    private fun row(label: String)=observations.put(JSONObject().put("stage",label).put("selectionStart",value.selection.start)
        .put("selectionEnd",value.selection.end).put("fieldLongPressMs",fieldTimeout).put("voiceStarts",starts)
        .put("nativeRangeChanges",nativeRangeChanges).put("scroll",scroll.value).put("focused",focused))
    @Test fun nonemptyNativeTapCursorTypingAndSingle500msHoldHaveOneOwner() {
        content();assertEquals(Long.MAX_VALUE,fieldTimeout)
        ui.onNodeWithTag("arbitration-field").performTouchInput {click(word(2))}
        ui.waitForIdle();assertTrue(focused);assertTrue(value.selection.collapsed);assertTrue(value.selection.start<original.length)
        ui.onNodeWithTag("arbitration-field").performTextInput("X");assertEquals(original.length+1,value.text.length);assertEquals(0,starts)
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);down(word(7));advanceEventTime(450);up()}
        ui.waitForIdle();assertEquals(0,starts);val before=value.selection;val ranges=nativeRangeChanges
        row("native-edit-and-under500ms")
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);longClick(word(7),650)}
        ui.waitForIdle();assertEquals(1,starts);assertTrue(recording);assertEquals(before,value.selection);assertEquals(ranges,nativeRangeChanges)
        row("voice-hold-no-native-word-selection")
        val actions=ui.onNodeWithTag("arbitration-field").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        ui.runOnIdle {assertFalse(actions.single {it.label=="Voice input"}.action());assertEquals(1,starts)}
        complete=true
    }
    @Test fun existingRangeAndNativeDoubleTapDragYieldWithoutVoiceOrMidDragReset() {
        value=TextFieldValue(original,TextRange(0,5));content();assertEquals(nativeTimeout,fieldTimeout)
        ui.onNodeWithTag("arbitration-field").performTouchInput {longClick(word(7),650)}
        ui.waitForIdle();assertEquals(0,starts);assertFalse(value.selection.collapsed);assertTrue(nativeRangeChanges>0)
        row("existing-range-native-long-selection")
        ui.runOnIdle {value=value.copy(selection=TextRange(value.text.length))};ui.waitForIdle();assertEquals(Long.MAX_VALUE,fieldTimeout)
        val before=nativeRangeChanges
        ui.onNodeWithTag("arbitration-field").performTouchInput {
            advanceEventTime(600);click(word(7));advanceEventTime(100);down(word(7));moveTo(word(17),120)
        }
        ui.waitForIdle();assertFalse(value.selection.collapsed);assertTrue(nativeRangeChanges>before);assertEquals(0,starts)
        assertEquals("Configuration must remain latched during a native double-tap drag",Long.MAX_VALUE,fieldTimeout)
        row("native-double-tap-drag-still-down")
        ui.onNodeWithTag("arbitration-field").performTouchInput {up()};ui.waitForIdle();assertEquals(nativeTimeout,fieldTimeout)
        row("range-restores-native-configuration-after-up");complete=true
    }
    @Test fun ancestorInitialAndLaterMainConsumptionAndRealScrollCancelVoice() {
        initialClaim=true;content()
        ui.onNodeWithTag("arbitration-field").performTouchInput {longClick(word(7),650)}
        assertEquals(0,starts);assertTrue(parentClaims>0);row("ancestor-initial-owner")
        ui.runOnIdle {initialClaim=false;laterClaim=true};ui.waitForIdle();val before=parentClaims
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);down(word(7));moveBy(Offset(1f,0f),100);advanceEventTime(650);up()}
        assertEquals(0,starts);assertTrue(parentClaims>before);row("later-main-consumption-final-yield")
        ui.runOnIdle {laterClaim=false};ui.waitForIdle()
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);down(center);moveBy(Offset(0f,-120f),150);advanceEventTime(650);up()}
        ui.waitForIdle();assertEquals(0,starts);assertTrue("Actual ancestor verticalScroll must take the drag",scroll.value>0)
        row("actual-scroll-won");complete=true
    }
    @Test fun multiplePointersSelectionAndOriginalEditorOrNavigationChangeCancelPermanently() {
        content()
        ui.onNodeWithTag("arbitration-field").performTouchInput {down(0,word(7));down(1,word(8));advanceEventTime(650);up(1);up(0)}
        assertEquals(0,starts);row("multitouch-yield")
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);down(word(7))}
        ui.runOnIdle {value=value.copy(selection=TextRange(0,5))}
        ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(650);up()};assertEquals(0,starts)
        row("selection-changed-while-down")
        for(changeOwner in listOf(true,false)) {
            ui.runOnIdle {value=value.copy(selection=TextRange(value.text.length))};ui.waitForIdle()
            ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(600);down(word(7))}
            ui.runOnIdle {if(changeOwner)owner=Any()else navigation="replacement-navigation"}
            ui.onNodeWithTag("arbitration-field").performTouchInput {advanceEventTime(650);up()};assertEquals(0,starts)
            row(if(changeOwner)"editor-replaced"else"navigation-replaced")
        }
        assertEquals(original,value.text);complete=true
    }
}
