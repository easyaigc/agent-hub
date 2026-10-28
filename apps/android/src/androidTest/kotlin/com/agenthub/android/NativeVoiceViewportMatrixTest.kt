package com.agenthub.android

import android.Manifest
import android.graphics.Bitmap
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Prepared native gates. The constrained product viewport is inside an actual owned Activity window;
 * it is not a physical display resize, system font change, real recognizer or storage migration test. */
class NativeVoiceViewportMatrixTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(180)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private var microphone=0
    private var language by mutableStateOf("zh-Hans")
    private var dark by mutableStateOf(false)
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController?=null
    private val fontScale=1.2f
    private fun palette()=if(dark)darkColorScheme(background=Color.Black,surface=Color(0xFF1C1C1E),primary=Color.White,secondary=Color(0xFF98989D),onSurfaceVariant=Color(0xFF98989D))
        else lightColorScheme(background=Color.White,surface=Color(0xFFF2F2F7),primary=Color.Black,secondary=Color(0xFF8A8A8E),onSurfaceVariant=Color(0xFF8A8A8E))
    private val preferences=Preferences()
    private class Preferences: NativeVoicePreferenceStore {
        val value=AtomicReference(VoiceInputController.Provider.DESKTOP)
        val loads=AtomicInteger();val saves=AtomicInteger()
        @Volatile var failReads=false
        override suspend fun load(): VoiceInputController.Provider { loads.incrementAndGet();check(!failReads) { "Synthetic read failure" };return value.get() }
        override suspend fun save(value: VoiceInputController.Provider) { saves.incrementAndGet();this.value.set(value) }
    }
    private class Engine: NativeSystemVoice {
        lateinit var listener: NativeSystemVoice.Listener
        val started get()=::listener.isInitialized
        var stops=0;var cancels=0
        override fun start(listener: NativeSystemVoice.Listener) { this.listener=listener }
        override fun finish() { stops++ }
        override fun cancel() { cancels++ }
    }
    private val engines=mutableListOf<Engine>()
    private var titleVersion=0
    private var answers=0;private var hostVoiceRequests=0
    private val target=TargetScope("codex",null,null,"viewport-host","workspace","runtime")
    private fun approval()=JSONObject().put("id","approval").put("threadId","thread").put("turnId","turn").put("kind","question")
        .put("title","Synthetic multi-field question $titleVersion / 多字段问题").put("detail","").put("decisions",JSONArray())
        .put("questions",JSONArray(listOf("q1","q2","q3","secret").map { id -> JSONObject().put("id",id)
            .put("question","$id · Describe the next step / 请说明下一步").put("custom",true).put("isSecret",id=="secret").put("options",JSONArray()) }))
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
            .put("hostId","viewport-host").put("name","Fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
            .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("hostId","viewport-host").put("id","thread").put("title","Synthetic thread").put("state","idle")))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
            .put("hostId","viewport-host").put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
            .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                .put("approvals",JSONArray().put(approval())).put("receipts",JSONArray()).put("canControl",true)).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({error("No unrelated command")}) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness { hostVoiceRequests++;error("No Host voice request") }
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) { this@NativeVoiceViewportMatrixTest.answers++;error("No implicit submission") }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun fakeInspector() { session.voiceSettings.inspector={ NativeVoiceCapability(NativeVoiceCapability.Route.SYSTEM_SERVICE,NativeVoiceCapability.Language.INSTALLED,"en-US") } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"voice-viewport-matrix");microphone=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands)
            // Bind this fake before configureVoice; parent production adapters must remain lazy.
            assertTrue(session.bindVoicePreferencesIfMissing { preferences })
            session.configureVoice(ui.activity);fakeInspector()
            session.questionVoice!!.systemFactory={ Engine().also { engines+=it } }
        }
        ui.waitUntil(10_000) { session.voiceSettings.persisted }
    }
    @After fun finish() {
        if(::session.isInitialized)ui.runOnIdle { session.questionVoice?.cancel();session.voice?.cancel();session.leaveForeground() }
        if(::owned.isInitialized)ui.runOnIdle { owned.session.leaveForeground();owned.verifyDefault();assertEquals(microphone,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)) }
    }
    @Composable private fun Viewport(content: @Composable (androidx.compose.ui.unit.Dp)->Unit) {
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
            ProductTextProvider(language) {
                val colors=palette()
                MaterialTheme(colorScheme=colors) {
                    focus=LocalFocusManager.current;keyboard=LocalSoftwareKeyboardController.current
                    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),contentAlignment=Alignment.Center) {
                        val width=minOf(320.dp,maxWidth-32.dp);val height=minOf(300.dp,maxHeight-32.dp)
                        // NativeSheetHost's ModalBottomSheet supplies contentColorFor(surface).
                        // A background-only Box leaves LocalContentColor at its unrelated default.
                        Surface(Modifier.size(width,height).testTag("voice-test-viewport"),color=colors.surface,contentColor=colors.onSurface) {
                            Box { content(height) }
                        }
                    }
                }
            }
        }
    }
    private fun matrix(block: (String)->Unit) { for(locale in listOf("zh-Hans","en"))for(night in listOf(false,true)) {
        ui.runOnIdle { language=locale;dark=night };ui.waitForIdle();block("${locale}-${if(night)"dark" else "light"}")
    } }
    private fun text(key: String)=NativeL10n(ui.activity,language).tr(key)
    private fun tap(tag: String) {
        val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val bounds=node.fetchSemanticsNode().boundsInRoot;val viewport=ui.onNodeWithTag("voice-test-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Native tap center must be inside the constrained viewport",viewport.contains(bounds.center))
        node.performTouchInput { click(center) };ui.waitForIdle()
    }
    private data class PrimaryInk(val key: String,val text: String,val color: Color,val expected: Color,val background: Color,val windowBounds: Rect,val fullRootBounds: Rect,val viewportBounds: Rect)
    private fun primaryInk(node: SemanticsNodeInteraction,key: String): PrimaryInk {
        val layouts=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("One actual primary Text layout for $key",1,layouts.size)
        val n=node.fetchSemanticsNode();val window=n.positionInWindow;val root=n.positionInRoot
        return PrimaryInk(key,layouts.single().layoutInput.text.text,layouts.single().layoutInput.style.color,palette().onSurface,palette().background,
            Rect(window.x,window.y,window.x+n.size.width,window.y+n.size.height),Rect(root.x,root.y,root.x+n.size.width,root.y+n.size.height),
            ui.onNodeWithTag("voice-test-viewport").fetchSemanticsNode().boundsInRoot)
    }
    private fun providerInk(): List<PrimaryInk> {
        ui.onNodeWithTag("voice-provider-group").performScrollTo().assertIsDisplayed()
        return VoiceInputController.Provider.entries.map { provider ->
            primaryInk(ui.onNodeWithText(text(voiceProviderTitle(provider)),useUnmergedTree=true).assertIsDisplayed(),"provider-${provider.name.lowercase()}")
        }
    }
    private fun serviceInk(): List<PrimaryInk> {
        val node=ui.onNodeWithTag("voice-service-capability").performScrollTo().assertIsDisplayed()
        return listOf(primaryInk(node,"voice-service-capability"))
    }
    private fun checkTextFits(tag: String,capture: String,primary: ()->List<PrimaryInk>) {
        val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds=node.fetchSemanticsNode().boundsInRoot
        val details=JSONObject().put("tag",tag).put("language",language).put("dark",dark).put("composedFontScale",fontScale)
            .put("semanticBoundsPx",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
            .put("layouts",JSONArray(layouts.map { layout -> JSONObject().put("text",layout.layoutInput.text.text)
                .put("sizePx",JSONArray(listOf(layout.size.width,layout.size.height)))
                .put("paragraphSizePx",JSONArray(listOf(layout.multiParagraph.width,layout.multiParagraph.height)))
                .put("constraints",layout.layoutInput.constraints.toString()).put("softWrap",layout.layoutInput.softWrap)
                .put("density",layout.layoutInput.density.density).put("fontScale",layout.layoutInput.density.fontScale)
                .put("fontSize",layout.layoutInput.style.fontSize.toString()).put("lineHeight",layout.layoutInput.style.lineHeight.toString())
                .put("didOverflowWidth",layout.didOverflowWidth).put("didOverflowHeight",layout.didOverflowHeight)
                .put("lines",JSONArray((0 until layout.lineCount).map { line -> JSONObject().put("start",layout.getLineStart(line)).put("end",layout.getLineEnd(line))
                    .put("ellipsized",layout.isLineEllipsized(line)).put("left",layout.getLineLeft(line)).put("right",layout.getLineRight(line))
                    .put("top",layout.getLineTop(line)).put("bottom",layout.getLineBottom(line)) })) }))
        details.put("captureFocus","Primary labels are scrolled into view after this caption's layout check")
        File(owned.folder,"$capture-text-layout.json").writeText(details.toString())
        // Save the real failure window before an assertion aborts the matrix; keep the same 17-shot budget.
        shot(capture,primary())
        val summary="$tag/$language/${if(dark)"dark" else "light"}: "+layouts.joinToString { "size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, overflowWidth=${it.didOverflowWidth}, overflowHeight=${it.didOverflowHeight}, lines=${it.lineCount}, constraints=${it.layoutInput.constraints}" }
        assertTrue("Product text must expose its actual layout",layouts.isNotEmpty())
        assertTrue("Product text must wrap without clipping: $summary",layouts.none { it.hasVisualOverflow })
        assertTrue("Product text must retain every character without ellipsis: $summary",layouts.all { layout ->
            layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1)==layout.layoutInput.text.length && (0 until layout.lineCount).none { layout.isLineEllipsized(it) }
        })
    }
    private fun shot(name: String,primary: List<PrimaryInk> = emptyList()) {
        val i=InstrumentationRegistry.getInstrumentation();ui.waitForIdle();val frames=CountDownLatch(1)
        i.runOnMainSync { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue("Owned window must draw two frames",frames.await(5,TimeUnit.SECONDS))
        val viewport=ui.onNodeWithTag("voice-test-viewport").fetchSemanticsNode().boundsInRoot
        var bitmap: Bitmap?=null
        i.runOnMainSync {
            assertEquals("com.easyai.agenthub.android.acceptance",ui.activity.packageName);assertTrue(ui.activity.window.decorView.hasWindowFocus())
            bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888)
        }
        val image=checkNotNull(bitmap);val done=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { PixelCopy.request(ui.activity.window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            File(owned.folder,"$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-activity-window").put("package",ui.activity.packageName)
                .put("width",image.width).put("height",image.height).put("language",language).put("dark",dark).put("composedFontScale",fontScale)
                .put("viewportKind","composed-constraint-inside-actual-window").put("viewportBoundsPx",JSONArray(listOf(viewport.left,viewport.top,viewport.right,viewport.bottom)))
                .put("physicalDisplayResized",false).put("systemFontChanged",false).put("systemBarsVerified",false).put("fullSystemComposite",false).toString())
            val evidence=JSONArray();val failures=mutableListOf<String>()
            for(probe in primary) {
                val foreground=probe.expected.toArgb();val measured=if(probe.color==Color.Unspecified)null else probe.color.toArgb()
                val bounds=probe.windowBounds;val full=probe.fullRootBounds;val visible=probe.viewportBounds
                val inside=full.left>=visible.left && full.top>=visible.top && full.right<=visible.right && full.bottom<=visible.bottom &&
                    bounds.left>=0 && bounds.top>=0 && bounds.right<=image.width && bounds.bottom<=image.height
                val left=kotlin.math.floor(bounds.left).toInt().coerceIn(0,image.width);val right=kotlin.math.ceil(bounds.right).toInt().coerceIn(left,image.width)
                val top=kotlin.math.floor(bounds.top).toInt().coerceIn(0,image.height);val bottom=kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top,image.height)
                var ink=0
                // Check actual pixels inside the Text box; adjacent checkmarks/buttons cannot count.
                // A small channel tolerance permits native glyph antialiasing and a fading pressed ripple.
                for(y in top until bottom)for(x in left until right) {
                    val pixel=image.getPixel(x,y)
                    if(listOf(16,8,0).all { shift -> kotlin.math.abs((pixel ushr shift and 255)-(foreground ushr shift and 255))<=40 })ink++
                }
                val minimum=maxOf(12,((right-left)*(bottom-top)*.002f).toInt())
                val a=probe.expected.luminance();val b=probe.background.luminance();val contrast=(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)
                val matches=measured==foreground
                evidence.put(JSONObject().put("key",probe.key).put("text",probe.text).put("resolvedTextColorArgb",measured).put("expectedOnSurfaceArgb",foreground)
                    .put("cardBackgroundArgb",probe.background.toArgb()).put("contrastRatio",contrast).put("windowBoundsPx",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
                    .put("fullyInsideViewportAndWindow",inside).put("matchingForegroundPixels",ink).put("minimumForegroundPixels",minimum).put("channelTolerance",40))
                if(!inside || !matches || contrast<4.5f || ink<minimum)failures+="${probe.key}: inside=$inside, colorMatches=$matches, contrast=$contrast, pixels=$ink/$minimum"
            }
            File(owned.folder,"$name-primary-ink.json").writeText(JSONObject().put("method","actual-TextLayoutResult-and-owned-window-PixelCopy").put("language",language).put("dark",dark).put("labels",evidence).toString())
            assertTrue("Primary labels must have production content color, readable contrast and actual foreground pixels: ${failures.joinToString()}",failures.isEmpty())
        }finally { image.recycle() }
    }
    @Test fun settingsRemainReachableAndExposeSelectionAndFailureInShortViewportMatrix() {
        ui.runOnIdle { ui.activity.setContent { Viewport { NativeVoiceSettingsPage(session) } } }
        matrix { name ->
            preferences.failReads=true
            ui.runOnIdle { session.voiceSettings.refreshPreferences() }
            ui.waitUntil(10_000) { session.voiceSettings.preferenceStatus==NativeVoiceSettings.PreferenceStatus.FAILED }
            tap("voice-provider-system")
            ui.waitUntil(10_000) { session.voiceSettings.preferenceStatus==NativeVoiceSettings.PreferenceStatus.FAILED }
            ui.onNodeWithTag("voice-provider-system").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
            ui.onNodeWithTag("voice-provider-desktop").assertIsNotSelected()
            ui.onNodeWithTag("voice-provider-group").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton)).assertCountEquals(2)
            ui.onAllNodes(isSelected()).assertCountEquals(1)
            ui.onNodeWithTag("voice-provider-system").assertTextEquals(if(language=="en")"System dictation" else "系统听写")
            ui.onNodeWithTag("voice-preference-persistence").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
            checkTextFits("voice-preference-persistence","settings-providers-$name",::providerInk)
            preferences.failReads=false;tap("voice-preference-retry")
            ui.waitUntil(10_000) { session.voiceSettings.persisted }
            assertEquals(VoiceInputController.Provider.SYSTEM,preferences.value.get())
            tap("voice-capability-refresh");ui.waitUntil(10_000) { !session.voiceSettings.checking }
            checkTextFits("voice-saved-recording-capability","settings-capability-$name",::serviceInk)
            assertFalse(session.voiceSettings.capabilities!!.canTranscribeSavedRecording);assertTrue(session.voiceSettings.capabilities!!.mayUseNetwork)
        }
    }
    private fun field(id: String)=ui.onNodeWithTag("question-custom-$id")
    private fun assertField(id: String,value: String) { field(id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value))) }
    private fun startQuestion(id: String): Engine {
        tap("question-voice-$id")
        main { session.questionVoice!!.permissionRequest?.let { session.questionVoice!!.permissionResult(it,true) };yield() }
        ui.waitUntil(10_000) { engines.isNotEmpty() && engines.last().started }
        val engine=engines.last();main { engine.listener.ready() };ui.waitForIdle();return engine
    }
    private fun checkCentralStop() {
        ui.onNodeWithTag("voice-compact-controls").assertExists()
        val central=ui.onNodeWithTag("dictation-waveform").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        central.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).assertHasClickAction()
        central.assertContentDescriptionEquals(text("轻触停止录音"))
        central.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,text(session.questionVoice!!.statusCopyKey ?: session.questionVoice!!.status)))
        ui.onAllNodesWithContentDescription(text("轻触停止录音")).assertCountEquals(1)
        val children=listOf("dictation-waveform-leading","dictation-stop-hint","dictation-waveform-trailing").map { tag ->
            val node=ui.onNodeWithTag(tag,useUnmergedTree=true)
            node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            node.fetchSemanticsNode().boundsInRoot
        }
        assertTrue(children[0].width>0f && children[2].width>0f)
        assertTrue(children[0].right<=children[1].left && children[1].right<=children[2].left)
        assertTrue(kotlin.math.abs(children[0].width-children[2].width)<2f)
    }
    @Test fun firstAndLastQuestionVoiceControlsRemainReachableWithoutAnsweringInShortViewportMatrix() {
        ui.runOnIdle { session.voiceSettings.select(VoiceInputController.Provider.SYSTEM);ui.activity.setContent { Viewport { height -> ExecutionPanels(session,maxHeight=height,autoPresentQuestions=false) } } }
        main { session.connect("Fixture","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single()) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime?.approvals?.size==1 }
        matrix { name ->
            titleVersion++;main { session.loadHistory()?.join() };ui.waitForIdle()
            ui.runOnIdle { session.text="composer preserved" }
            for(id in listOf("q1","q2","q3","secret"))field(id).performScrollTo().performTextInput("draft-$id")
            ui.runOnIdle { focus.clearFocus();keyboard?.hide() };ui.waitForIdle()
            ui.onNodeWithTag("question-voice-secret").assertDoesNotExist()
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled()
            val first=startQuestion("q1");checkCentralStop();shot("question-first-listening-$name")
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsNotEnabled()
            main { session.beginVoice();assertFalse(session.voice!!.active) }
            tap("dictation-waveform");assertEquals(1,first.stops)
            assertEquals(VoiceInputController.Phase.FINISHING,session.questionVoice!!.phase)
            main { first.listener.result("speech-$name") }
            assertField("q1","draft-q1\nspeech-$name");assertField("q2","draft-q2");assertField("q3","draft-q3")
            val last=startQuestion("q3");main { first.listener.result("stale first result") };checkCentralStop();shot("question-last-listening-$name")
            tap("cancel-dictation");assertFalse(session.questionVoice!!.active);assertTrue(last.cancels>0)
            assertField("q3","draft-q3");assertField("q2","draft-q2");assertField("q1","draft-q1\nspeech-$name")
            assertEquals("composer preserved",session.text);assertEquals(0,answers);assertEquals(0,hostVoiceRequests)
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled()
        }
    }
    @Test fun retainedActivityReusesThePreferenceBindingAndBothControllers() {
        val normal=session.voice;val question=session.questionVoice;val settings=session.voiceSettings;var factories=0
        ui.runOnIdle { session.voiceSettings.select(VoiceInputController.Provider.SYSTEM);ui.activity.adoptSessionForAcceptance(session);fakeInspector() }
        ui.waitUntil(10_000) { settings.persisted }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) { ui.activity.session===session && settings.persisted }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();session.discovery?.stop();session.easyWorkDiscovery?.stop();fakeInspector()
            assertFalse(session.bindVoicePreferencesIfMissing { factories++;error("Retained Session cannot rebind") })
            assertSame(settings,session.voiceSettings);assertSame(normal,session.voice);assertSame(question,session.questionVoice)
            assertEquals(0,factories);assertEquals(VoiceInputController.Provider.SYSTEM,settings.defaultProvider)
            ui.activity.setContent { Viewport { NativeVoiceSettingsPage(session) } }
        }
        ui.onNodeWithTag("voice-provider-system").performScrollTo().assertIsSelected();shot("settings-retained-activity")
    }
}
