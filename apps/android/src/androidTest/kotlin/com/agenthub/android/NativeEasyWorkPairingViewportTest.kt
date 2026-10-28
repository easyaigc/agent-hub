package com.agenthub.android

import android.content.res.Configuration
import android.os.LocaleList
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual product ConnectionPage/NativeSheetHost, never a copied test UI.
 * Constrained component layout and real dialog/IME lanes are separately recorded.
 * All values are synthetic; submissions intentionally fail local parsing before networking.
 */
class NativeEasyWorkPairingViewportTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {override fun before() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        if(InstrumentationRegistry.getArguments().getString("p8UiForeground")!="1")return
        val fd=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
        assertTrue(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}.contains("Status: ok"))
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var session: MobileSession
    private lateinit var folder: File
    private var defaults=emptyMap<String,String>()
    private val originalDraft="Independent original draft 中文 😀 %@"
    private val clicks=mutableListOf<String>()
    private var layoutProbe=0
    private val publicName="Public synthetic name 中文 %@ "+"n".repeat(80)
    private val address="invalid://public-synthetic.invalid/"+"long-address-".repeat(18)
    private val relay="https://"+"long-synthetic-label.".repeat(8)+"invalid"
    private fun hash(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun snapshot()=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter {it.name=="mobile-v1"||it.name.startsWith("mobile-v1-")}.flatMap {root->root.walkTopDown().filter {it.isFile}.toList()}.associate {it.relativeTo(i.targetContext.noBackupFilesDir).path to hash(it)}
    @Before fun setup() {
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),hash(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),hash(File(i.context.applicationInfo.sourceDir)))
        defaults=snapshot();folder=File(i.targetContext.filesDir,"acceptance/p8-pairing-viewport/$id").apply {check(!exists());check(mkdirs())}
        ui.runOnIdle {session=MobileSession(NativeSessionStorage(i.targetContext,"p8-viewport-$id","p8-viewport-$id"));session.resetAcceptance();session.text=originalDraft}
    }
    @After fun finish() {
        if(::session.isInitialized)ui.runOnIdle {ui.activity.setContent {};session.leaveForeground();assertEquals(originalDraft,session.text);assertTrue(session.clients.isEmpty());assertTrue(session.operationRecords.isEmpty())}
        assertEquals(defaults,snapshot())
    }
    private fun visible(tag: String,scroll: Boolean=true): SemanticsNodeInteraction {
        val n=ui.onNodeWithTag(tag);if(scroll)n.performScrollTo();n.assertIsDisplayed()
        val raw=n.getUnclippedBoundsInRoot();val b=n.fetchSemanticsNode().boundsInRoot;val frame=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot
        assertTrue("Full touch target height must be visible: $tag",b.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
        assertTrue("Full touch target width must be visible: $tag",b.width>=(raw.right.value-raw.left.value)*ui.density.density-1.5f)
        assertTrue("Target must be inside actual scroll viewport: $tag",b.left>=frame.left-1&&b.right<=frame.right+1&&b.top>=frame.top-1&&b.bottom<=frame.bottom+1)
        return n
    }
    private fun touch(tag: String) {visible(tag).assertHasClickAction().assertIsEnabled().performTouchInput {click(center)};clicks+=tag;ui.waitForIdle()}
    private fun choose(choice: String) {
        touch("easywork-connection-method")
        val tag="easywork-method-$choice";val n=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHasClickAction().assertIsEnabled()
        val raw=n.getUnclippedBoundsInRoot();assertTrue(n.fetchSemanticsNode().boundsInRoot.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
        n.performTouchInput {click(center)};clicks+=tag;ui.waitForIdle()
    }
    private fun exactInput(n: SemanticsNodeInteraction,value: String): SemanticsNodeInteraction {
        val editable=n.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
        val input=n.fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText)
        File(folder,"input-${UUID.randomUUID()}.json").writeText(JSONObject().put("expectedHash",easyHash(value.toByteArray())).put("expectedUtf16Length",value.length)
            .put("channels",org.json.JSONArray(listOf(editable,input).map {a->JSONObject().put("present",a!=null).put("textHash",a?.text?.toByteArray()?.let(::easyHash)).put("utf16Length",a?.length)
                .put("spanStyleCount",a?.spanStyles?.size).put("paragraphStyleCount",a?.paragraphStyles?.size).put("equalsPlainAnnotatedString",a==AnnotatedString(value))})).toString())
        return n.assert(SemanticsMatcher("EditableText and InputText retain the complete original UTF-16 text") {node->
            p8ExactInput(value,node.config.getOrNull(SemanticsProperties.EditableText)?.text,node.config.getOrNull(SemanticsProperties.InputText)?.text)
        })
    }
    private fun field(tag: String,value: String,label: String) {
        visible(tag).assertContentDescriptionEquals(label).performTextReplacement(value)
        exactInput(ui.onNodeWithTag(tag),value).assertContentDescriptionEquals(label)
    }
    private fun noTextClip(n: SemanticsNodeInteraction,expected: String,multiline: Boolean=false) {
        val layouts=mutableListOf<TextLayoutResult>();n.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}
        val probe="layout-${++layoutProbe}";val node=n.fetchSemanticsNode();val raw=n.getUnclippedBoundsInRoot()
        val view=ui.activity.window.decorView;val insets=ViewCompat.getRootWindowInsets(view)
        val scroll=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot
        val viewport=ui.onNodeWithTag("p8-pairing-viewport").fetchSemanticsNode().boundsInRoot
        val originX=raw.left.value*ui.density.density;val originY=raw.top.value*ui.density.density
        val clip=P8TextRect(maxOf(node.boundsInRoot.left,scroll.left,viewport.left)-originX,maxOf(node.boundsInRoot.top,scroll.top,viewport.top)-originY,
            minOf(node.boundsInRoot.right,scroll.right,viewport.right)-originX,minOf(node.boundsInRoot.bottom,scroll.bottom,viewport.bottom)-originY)
        fun box(r: androidx.compose.ui.geometry.Rect)=P8TextRect(r.left,r.top,r.right,r.bottom)
        val samples=layouts.map {r->P8TextObservation(r.layoutInput.text.text,r.size.width,r.size.height,clip,
            (0 until r.lineCount).map {line->P8TextLine(r.getLineStart(line),r.getLineEnd(line,visibleEnd=false),P8TextRect(r.getLineLeft(line),r.getLineTop(line),r.getLineRight(line),r.getLineBottom(line)),r.isLineEllipsized(line))},
            r.layoutInput.text.indices.map {offset->P8TextCharacter(offset,r.getLineForOffset(offset),box(r.getBoundingBox(offset)))})}
        val failures=buildList {if(layouts.size!=1)add("Expected exactly one original text layout, got ${layouts.size}");samples.forEach {addAll(p8TextVisibilityFailures(expected,it,multiline))}}
        fun rect(r: androidx.compose.ui.geometry.Rect)=JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom)
        val observations=JSONObject().put("probe",probe).put("textHash",easyHash(layouts.joinToString("\n") {it.layoutInput.text.text}.toByteArray())).put("textLengths",org.json.JSONArray(layouts.map {it.layoutInput.text.length}))
            .put("nodeBoundsInRoot",rect(node.boundsInRoot)).put("nodeBoundsInWindow",rect(node.boundsInWindow)).put("unclippedDp",JSONObject().put("left",raw.left.value).put("top",raw.top.value).put("right",raw.right.value).put("bottom",raw.bottom.value))
            .put("scrollClipInRoot",rect(ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot)).put("syntheticViewportInRoot",rect(ui.onNodeWithTag("p8-pairing-viewport").fetchSemanticsNode().boundsInRoot))
            .put("imeVisible",insets?.isVisible(WindowInsetsCompat.Type.ime())==true).put("imeBottomPx",insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?:0).put("windowWidthPx",view.width).put("windowHeightPx",view.height)
            .put("expectedTextHash",easyHash(expected.toByteArray())).put("expectedUtf16Length",expected.length).put("coverageFailures",org.json.JSONArray(failures))
            .put("actualVisibleClipLocalPx",JSONObject().put("left",clip.left).put("top",clip.top).put("right",clip.right).put("bottom",clip.bottom))
            .put("layouts",org.json.JSONArray(layouts.map {r->JSONObject().put("widthPx",r.size.width).put("heightPx",r.size.height).put("paragraphWidthPx",r.multiParagraph.width).put("paragraphHeightPx",r.multiParagraph.height)
                .put("constraints",r.layoutInput.constraints.toString()).put("density",r.layoutInput.density.density).put("fontScale",r.layoutInput.density.fontScale).put("lineCount",r.lineCount)
                .put("didOverflowWidth",r.didOverflowWidth).put("didOverflowHeight",r.didOverflowHeight).put("hasVisualOverflow",r.hasVisualOverflow)
                .put("lines",org.json.JSONArray((0 until r.lineCount).map {line->JSONObject().put("start",r.getLineStart(line)).put("endIncludingWhitespace",r.getLineEnd(line,visibleEnd=false)).put("visibleEnd",r.getLineEnd(line,visibleEnd=true)).put("left",r.getLineLeft(line)).put("right",r.getLineRight(line)).put("top",r.getLineTop(line)).put("bottom",r.getLineBottom(line)).put("ellipsized",r.isLineEllipsized(line))}))
                .put("characters",org.json.JSONArray(r.layoutInput.text.indices.map {offset->JSONObject().put("utf16Offset",offset).put("line",r.getLineForOffset(offset)).put("bounds",rect(r.getBoundingBox(offset)))}))}))
        if(failures.isNotEmpty()||layoutProbe==1) {
            // Capture only this known synthetic component, never a full system screenshot.
            var bitmap: android.graphics.Bitmap?=null
            try {
                val crop=ui.onNodeWithTag("p8-pairing-viewport").fetchSemanticsNode().boundsInWindow
                val area=android.graphics.Rect(kotlin.math.floor(crop.left).toInt().coerceAtLeast(0),kotlin.math.floor(crop.top).toInt().coerceAtLeast(0),kotlin.math.ceil(crop.right).toInt().coerceAtMost(view.width),kotlin.math.ceil(crop.bottom).toInt().coerceAtMost(view.height))
                assertEquals("com.easyai.agenthub.android.acceptance",ui.activity.packageName);check(area.width() in 1..4096&&area.height() in 1..4096)
                bitmap=android.graphics.Bitmap.createBitmap(area.width(),area.height(),android.graphics.Bitmap.Config.ARGB_8888);val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
                ui.runOnIdle {android.view.PixelCopy.request(ui.activity.window,area,image,{code->result=code;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
                val finished=done.await(2,java.util.concurrent.TimeUnit.SECONDS);observations.put("pixelCopyFinished",finished).put("pixelCopyStatus",result).put("capture","owned synthetic viewport only")
                if(finished&&result==android.view.PixelCopy.SUCCESS)File(folder,"$probe.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }catch(error: Exception){observations.put("pixelCopyFailure",error.javaClass.simpleName)}finally {bitmap?.recycle()}
        }
        File(folder,"$probe.json").writeText(observations.toString())
        assertTrue("Original text/line/character/actual clip verification failed: $failures; see $probe.json and synthetic PixelCopy",failures.isEmpty())
    }
    private fun profile(language: String,font: Float,height: Int,dark: Boolean) {
        val draft=EasyWorkConnectionDraft("EasyWork","https://51easyai.com");val editing=mutableStateOf(false)
        val target=TargetScope("easywork","https://public-synthetic.invalid/"+"a".repeat(56),"subject-%@-"+"s".repeat(64),"host-"+"h".repeat(64),"workspace-"+"w".repeat(64),"runtime-"+"r".repeat(64))
        val client=MobileClient("display-only","Public metadata",target,"https://not-used.invalid",false,"easywork-runtime",false,agentConnection=AgentConnection(target,listOf(AgentCandidate("unused","relay-http","https://not-used.invalid")),"unused"))
        val config=Configuration(i.targetContext.resources.configuration).apply {setLocales(LocaleList.forLanguageTags(if(language=="en")"zh-CN" else "en-US"))};val text=NativeL10n(i.targetContext,language,config)
        ui.runOnIdle {ui.activity.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font),LocalConfiguration provides config) {
            ProductTextProvider(language) {MaterialTheme(colorScheme=if(dark)darkColorScheme()else lightColorScheme()) {Surface(Modifier.width(320.dp).height(height.dp).testTag("p8-pairing-viewport"),color=MaterialTheme.colorScheme.background) {
                ConnectionPage(session,if(editing.value)client else null,draft) {error("Local invalid fixture must never complete pairing")}
            }}}
        }}}
        visible("agent-provider").assertContentDescriptionEquals(text.tr("Agent 客户端"))
        touch("agent-provider-easywork");ui.onNodeWithTag("agent-provider-easywork").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
        noTextClip(ui.onNode(hasText("EasyWork") and hasAnyAncestor(hasTestTag("agent-provider-easywork")),useUnmergedTree=true).assertTextEquals("EasyWork"),"EasyWork")
        field("easywork-name",publicName,text.tr("名称"))
        touch("easywork-discovery-rescan") // No discovery instance/Host is configured.
        choose("扫码连接");field("easywork-qr-description","easywork://pair?invalid-public",text.tr("或粘贴连接二维码内容"));touch("easywork-pair-lan")
        ui.waitUntil(5000){ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty()};visible("easywork-pair-error")
        choose("手动连接");field("easywork-manual-address",address,text.tr("电脑地址"));touch("easywork-manual-connect") // Unsupported scheme fails before any transport.
        ui.waitUntil(5000){ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty()};visible("easywork-pair-error")
        choose("通过中继连接");field("easywork-relay-url",relay,text.tr("中继服务地址"));field("easywork-relay-code","short",text.tr("电脑连接码"));touch("easywork-pair-relay") // Code fails before any authorization/HTTP call.
        ui.waitUntil(5000){ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty()};visible("easywork-pair-error")
        touch("agent-provider-codex");ui.onNodeWithTag("agent-provider-codex").assertIsSelected();touch("agent-provider-easywork")
        exactInput(visible("easywork-name"),publicName)
        exactInput(visible("easywork-relay-url"),relay)
        exactInput(visible("easywork-relay-code"),"short")
        assertEquals(address,draft.address);assertEquals("easywork://pair?invalid-public",draft.qr)
        ui.runOnIdle {editing.value=true}
        for((key,value) in listOf("authority" to target.accountAuthority!!,"subject" to target.accountSubject!!,"host" to target.hostId,"workspace" to target.workspaceId,"runtime" to target.runtimeId)) {
            val node=visible("easywork-target-$key").assertTextEquals(value);noTextClip(node,value,true);node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        }
        val frame=ui.onNodeWithTag("connection-page").getUnclippedBoundsInRoot()
        File(folder,"viewport-observations.json").writeText(JSONObject().put("language",language).put("fontScale",font).put("dark",dark).put("lane","actual component in constrained layout; not physical screen or IME proof")
            .put("heightDp",frame.bottom.value-frame.top.value).put("clickTargets",org.json.JSONArray(clicks)).put("fullTargetBoundsAndUnclippedTextChecked",true).put("filledFieldsHaveAxLabels",true).put("providerRoundTripValuesPreserved",true).put("defaultEncryptedFilesUnchanged",defaults==snapshot()).put("networkInputs","local-invalid only").toString())
    }
    private fun dialogWindow(): Window {
        var window: Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check {view,error->
            if(error!=null)throw error;assertEquals(i.targetContext.packageName,view.context.packageName)
            fun find(v: android.view.View) {if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window;if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))}
            find(view.rootView);assertSame(view.rootView,window!!.decorView.rootView)
        }
        return checkNotNull(window)
    }
    private fun ime(language: String,font: Float) {
        ui.runOnIdle {session.changeLanguage(language);ui.activity.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
            ProductTextProvider(language) {MaterialTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {NativeSheetHost("add",session,null,{error("No test dismiss")},{error("No test navigation")},{error("No test edit")})}}}
        }}}
        touch("agent-provider-easywork");choose("通过中继连接")
        val text=NativeL10n(i.targetContext,language);field("easywork-relay-url",relay,text.tr("中继服务地址"));field("easywork-relay-code","short",text.tr("电脑连接码"))
        val window=dialogWindow();val view=window.decorView
        visible("easywork-relay-url").performTouchInput {click(center)}
        ui.waitUntil(8000){ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        visible("easywork-relay-url").assertIsFocused().performTextInputSelection(TextRange(relay.length))
        exactInput(ui.onNodeWithTag("easywork-relay-url"),relay).assertContentDescriptionEquals(text.tr("中继服务地址"))
        ui.onNodeWithTag("easywork-relay-url").performImeAction();ui.waitUntil(8000){ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())!=true};ui.onNodeWithTag("easywork-relay-url").assertIsNotFocused()
        visible("easywork-relay-code").performTouchInput {click(center)};ui.waitUntil(8000){ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        visible("easywork-relay-code").assertIsFocused().assertContentDescriptionEquals(text.tr("电脑连接码"))
        val insets=checkNotNull(ViewCompat.getRootWindowInsets(view));assertTrue(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom>0)
        val visibleFrame=android.graphics.Rect();view.getWindowVisibleDisplayFrame(visibleFrame)
        val button=visible("easywork-pair-relay").assertIsEnabled().assertHasClickAction();val node=button.fetchSemanticsNode();val bounds=node.boundsInWindow;val origin=IntArray(2);view.getLocationOnScreen(origin)
        assertTrue("Whole submit target is above actual IME",bounds.bottom<=visibleFrame.bottom-origin[1]+1)
        button.performTouchInput {click(center)};clicks+="easywork-pair-relay";ui.waitUntil(5000){ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty()};visible("easywork-pair-error")
        ui.waitUntil(8000){ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())!=true}
        exactInput(visible("easywork-relay-code"),"short")
        File(folder,"ime-observations.json").writeText(JSONObject().put("language",language).put("fontScale",font).put("lane","actual NativeSheetHost dialog + actual software IME")
            .put("imeBottomPx",insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).put("dialogWidthPx",view.width).put("dialogHeightPx",view.height).put("filledFieldLabelAndFocusVerified",true).put("nativeDoneReleasedIme",true).put("wholeSubmitAboveImeAndNativeTapDelivered",true).put("publicLongAddressRetained",true).put("defaultEncryptedFilesUnchanged",defaults==snapshot()).toString())
    }
    @Test(timeout=45_000) fun chineseShortViewportAtDefaultFontUsesActualProviderFieldsAndTargets()=profile("zh-Hans",1f,360,false)
    @Test(timeout=45_000) fun englishShortViewportAtLargeFontUsesActualProviderFieldsAndTargets()=profile("en",1.3f,320,true)
    @Test(timeout=45_000) fun chineseShortViewportAtDoubleFontKeepsEveryActionAndMetadataReachable()=profile("zh-Hans",2f,280,true)
    @Test(timeout=45_000) fun englishShortViewportAtDoubleFontKeepsEveryActionAndMetadataReachable()=profile("en",2f,280,false)
    @Test(timeout=45_000) fun chineseActualSheetImeKeepsFocusedFieldDoneAndSubmitReachable()=ime("zh-Hans",1.3f)
    @Test(timeout=45_000) fun englishActualSheetImeKeepsFocusedFieldDoneAndSubmitReachable()=ime("en",2f)
}
