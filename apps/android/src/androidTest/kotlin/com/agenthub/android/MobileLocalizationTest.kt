package com.agenthub.android

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Native resource decoding and owned settings; no original desktop or model writes. */
class MobileLocalizationTest {
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule(
        { args.getString("viewportOrientation") ?: "" },
        { java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P6-language-viewport/${args.getString("viewportRun") ?: "fixture"}").apply { mkdirs() } },{"settings"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val opened=mutableListOf<String>()
    private var failLink=false
    private val preferred=mutableStateOf("en-US")
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"localization")
            session.resetAcceptance();session.setDemoMode(true);session.changeLanguage("en")
            session.changeAppearance(args.getString("viewportTheme") ?: "light")
            ui.activity.setContent {
                val base=LocalConfiguration.current
                val config=remember(base,preferred.value) { Configuration(base).apply { setLocales(LocaleList.forLanguageTags(preferred.value)) } }
                CompositionLocalProvider(LocalConfiguration provides config,LocalUriHandler provides object: UriHandler {
                    override fun openUri(uri: String) { if(failLink)throw IllegalStateException("Synthetic unavailable browser");opened+=uri }
                }) { AgentHubApp(session) }
            }
            java.io.File(owned.folder,"actual-profile.json").writeText(JSONObject().put("appearance",session.appearance)
                .put("fontScale",ui.density.fontScale).put("orientation",ui.activity.resources.configuration.orientation).toString())
        }
        ui.waitForIdle()
    }
    @After fun finish() { if(::owned.isInitialized) { main { session.leaveForeground() };owned.verifyDefault() } }
    private fun tap(tag: String) {
        val node=ui.onNodeWithTag(tag)
        if(tag !in listOf("chat-menu","open-settings","appearance-back"))node.performScrollTo()
        node.assertIsDisplayed().performClick();ui.waitForIdle()
    }
    private fun assertDraft(expected: com.agenthub.core.SuggestionDraft) {
        val current=main { session.suggestionDraft }
        assertEquals(expected.targetKey,current.targetKey);assertEquals(expected.text,current.text);assertEquals(expected.revision,current.revision)
    }
    private fun verifyChoice(node: SemanticsNodeInteraction,name: String,page: String) {
        val frame=ui.onNodeWithTag(page).fetchSemanticsNode().boundsInRoot
        val actual=node.getUnclippedBoundsInRoot();val density=ui.density.density
        assertTrue("The complete choice must fit its product viewport",actual.top.value*density>=frame.top-1.5f&&actual.bottom.value*density<=frame.bottom+1.5f)
        var width=0;var height=0
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view,error ->
                if(error!=null)throw error
                width=view.rootView.width;height=view.rootView.height
            }
        val coordinates=node.fetchSemanticsNode().layoutInfo.coordinates
        var bounds: androidx.compose.ui.geometry.Rect?=null
        ui.runOnIdle {
            val offset=coordinates.positionInWindow()-coordinates.positionInRoot()
            bounds=androidx.compose.ui.geometry.Rect(actual.left.value*density+offset.x,actual.top.value*density+offset.y,
                actual.right.value*density+offset.x,actual.bottom.value*density+offset.y)
        }
        val rect=checkNotNull(bounds)
        java.io.File(owned.folder,"$page-$name-choice-bounds.json").writeText(JSONObject().put("windowWidth",width).put("windowHeight",height)
            .put("left",rect.left).put("top",rect.top).put("right",rect.right).put("bottom",rect.bottom).toString())
        assertTrue("The complete choice must fit the actual native window",rect.left>=-1.5f&&rect.top>=-1.5f&&rect.right<=width+1.5f&&rect.bottom<=height+1.5f)
    }
    private fun verifyHeader() {
        val title=ui.onNodeWithTag("sheet-title");val bounds=title.fetchSemanticsNode().boundsInRoot
        val back=ui.onNodeWithTag("appearance-back").fetchSemanticsNode().boundsInRoot
        val done=ui.onNodeWithTag("sheet-done").fetchSemanticsNode().boundsInRoot
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single();val right=(0 until layout.lineCount).maxOf { layout.getLineRight(it) };val bottom=layout.getLineBottom(layout.lineCount-1)
        java.io.File(owned.folder,"header-bounds.json").writeText(JSONObject().put("title",bounds.toString()).put("back",back.toString()).put("done",done.toString())
            .put("overflowWidth",layout.didOverflowWidth).put("overflowHeight",layout.didOverflowHeight).put("layoutWidth",layout.size.width).put("lineRight",right).put("layoutHeight",layout.size.height).put("lineBottom",bottom).toString())
        assertFalse("Title and Back must have separate visible areas",bounds.overlaps(back))
        assertFalse("Title and Done must have separate visible areas",bounds.overlaps(done))
        // TextLayout's floating width can exceed its integer size by a fraction
        // of a physical pixel. Keep that diagnostic and check actual line bounds.
        assertTrue("Full title width fits",right<=layout.size.width+.75f)
        assertTrue("Full title height fits",bottom<=layout.size.height+.75f)
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();var window: android.view.Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(i.targetContext.packageName,view.context.packageName)
                fun find(v: android.view.View) {
                    if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                    if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))
                };find(view.rootView);assertTrue(window!!.decorView.rootView===view.rootView)
            }
        val w=checkNotNull(window);var image: android.graphics.Bitmap?=null
        val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync { w.decorView.postOnAnimation { w.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS))
        i.runOnMainSync { assertTrue(w.decorView.hasWindowFocus());image=android.graphics.Bitmap.createBitmap(w.decorView.width,w.decorView.height,android.graphics.Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(w,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            java.io.File(owned.folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            java.io.File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-dialog-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).toString())
        }finally { bitmap.recycle() }
    }
    private fun settings() { tap("chat-menu");tap("open-settings");ui.onNodeWithTag("sheet-title").assertTextEquals("Settings") }
    private fun choose(value: String) { tap("language-picker");tap("language-$value");tap("appearance-back") }

    @Test fun compiledCatalogsPreserveAllBytesAndRuntimeValuesAreNeverTranslated() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization-catalog.json").bufferedReader().use { it.readText() })
        for(language in listOf("en","zh-Hans")) {
            val text=NativeL10n(context,language);val expected=fixture.getJSONObject(language)
            assertEquals(1699,expected.length())
            for(key in expected.keys())assertEquals("Compiled $language catalog key: $key",expected.getString(key),text.catalogValue(key))
        }
        val text=NativeL10n(context,"en");val value="复制 / a%@100%🙂\n<literal>&"
        assertEquals("$value, $value",text.tr("%@，%@",value,value))
        assertEquals("100% $value",NativeL10n.render("100%% %@",listOf(value)))
        assertEquals("unregistered literal",text.tr("unregistered literal"))
        java.io.File(owned.folder,"compiled-catalog-proof.json").writeText(JSONObject().put("keysPerLanguage",1661).put("languages",2).put("runtimeValuesLiteral",true).toString())
    }
    @Test fun followSystemChangesDisplayWithoutChangingTheDraftOrTarget() {
        val value="Unsent 100% 原文 %@ /path/设置";main { session.text=value };val draft=main { session.suggestionDraft };val target=session.targetKey
        settings();choose("system")
        ui.onNodeWithTag("sheet-title").assertTextEquals("Settings")
        ui.runOnIdle { preferred.value="zh-TW,en-US" };ui.waitForIdle()
        ui.onNodeWithTag("sheet-title").assertTextEquals("设置")
        shot("system-chinese-settings")
        ui.runOnIdle { preferred.value="fr-FR,en-GB,zh-CN" };ui.waitForIdle()
        ui.onNodeWithTag("sheet-title").assertTextEquals("Settings")
        assertEquals("en",NativeAppLanguage.SYSTEM.resolved(listOf("fr-FR")))
        assertEquals("zh-Hans",NativeAppLanguage.SYSTEM.resolved(listOf("fr-FR","zh-TW","en")))
        assertDraft(draft);assertEquals(target,session.targetKey);assertEquals(value,session.text)
        shot("system-english-settings")
    }
    @Test fun languageAppearanceAndSessionReconstructionKeepOriginalDraftFilesAndSettings() {
        val value="Draft 100% English 中文 %@";main {
            session.text=value
            session.importAttachments(session.attachmentImportDestination(),listOf({ LocalAttachmentInput("原文100%.txt","text/plain",value.toByteArray()) }))!!.await()
        }
        val draft=main { session.suggestionDraft };val files=session.attachments.map { it.manifest };val settings=session.generationSettings;val target=session.targetKey
        settings();choose("zh-Hans");ui.onNodeWithTag("sheet-title").assertTextEquals("设置")
        tap("settings-appearance");tap("appearance-dark");tap("appearance-back")
        ui.onNodeWithTag("sheet-title").assertTextEquals("设置")
        choose("en");ui.onNodeWithTag("sheet-title").assertTextEquals("Settings")
        assertDraft(draft);assertEquals(files,session.attachments.map { it.manifest });assertEquals(settings,session.generationSettings)
        ui.waitUntil(10_000) { owned.storage.metadata()?.let { it.optString("language")=="en"&&it.optString("appearance")=="dark" }==true }
        val restored=MobileSession(owned.storage)
        try {
            main { restored.restore() };ui.waitUntil(10_000) { !restored.restoring }
            assertFalse(restored.localRecoveryRequired);assertEquals("en",restored.language);assertEquals("dark",restored.appearance)
            assertEquals(value,restored.text);assertEquals(target,restored.targetKey);assertEquals(files,restored.attachments.map { it.manifest });assertEquals(settings,restored.generationSettings)
            assertArrayEquals(value.toByteArray(),main { NativeDraftFiles(owned.storage).bytes(files.single()) })
        }finally { main { restored.leaveForeground() } }
        shot("english-dark-settings")
    }
    @Test fun privacyAndSupportUseCurrentLanguageAndBrowserFailureKeepsTheDraft() {
        main { session.text="Original link-check draft" };val draft=main { session.suggestionDraft };settings()
        tap("settings-privacy-policy");tap("settings-support")
        ui.onNodeWithTag("settings-privacy-policy-icon",useUnmergedTree=true).assertExists()
        ui.onNodeWithTag("settings-support-icon",useUnmergedTree=true).assertExists()
        shot("english-about-icons")
        assertEquals(listOf("https://51easyai.com/agent-hub/privacy-en.html","https://51easyai.com/agent-hub/support-en.html"),opened)
        choose("zh-Hans");tap("settings-privacy-policy");tap("settings-support")
        assertEquals(listOf("https://51easyai.com/agent-hub/privacy.html","https://51easyai.com/agent-hub/support.html"),opened.takeLast(2))
        failLink=true;tap("settings-support");ui.onNodeWithText("无法打开链接").assertIsDisplayed()
        assertNull(session.error);assertDraft(draft);assertEquals(4,opened.size)
        ui.onNodeWithText("好").performClick()
    }
    @Test fun languageAndAppearanceChoicesStayReadableAndAtLeast48dp() {
        settings();tap("language-picker")
        for(value in listOf("system","zh-Hans","en")) {
            val node=ui.onNodeWithTag("language-$value").performScrollTo().assertIsDisplayed()
            assertTrue(node.getUnclippedBoundsInRoot().let { it.bottom.value-it.top.value }>=47.5f)
            verifyChoice(node,value,"sheet-language")
        }
        verifyHeader();shot("language-choices")
        tap("language-zh-Hans");ui.onNodeWithTag("sheet-title").assertTextEquals("语言")
        ui.onNodeWithTag("language-zh-Hans").assertIsSelected()
        tap("appearance-back");tap("settings-appearance")
        for(value in listOf("system","light","dark")) {
            val node=ui.onNodeWithTag("appearance-$value").performScrollTo().assertIsDisplayed()
            assertTrue(node.getUnclippedBoundsInRoot().let { it.bottom.value-it.top.value }>=47.5f)
            verifyChoice(node,value,"sheet-appearance")
        }
        shot("appearance-choices")
    }
    @Test fun olderMetadataAndUnknownDisplayPreferenceRestoreWithoutDiscardingTheDraft() {
        val value="Legacy metadata draft";main { session.text=value }
        ui.waitUntil(10_000) { owned.storage.metadata()?.optJSONObject("drafts")?.optString(session.targetKey)==value }
        val original=owned.storage.metadata()!!
        for(unknown in listOf(false,true)) {
            val copy=JSONObject(original.toString());if(unknown)copy.put("language","future-language") else copy.remove("language")
            val storage=NativeSessionStorage(ui.activity,"P6-legacy-language-${java.util.UUID.randomUUID()}")
            storage.write("metadata",copy.toString());val restored=MobileSession(storage)
            try {
                main { restored.restore() };ui.waitUntil(10_000) { !restored.restoring }
                assertFalse(restored.localRecoveryRequired);assertEquals("system",restored.language)
                assertEquals(value,restored.text);assertEquals(session.targetKey,restored.targetKey)
            }finally { main { restored.leaveForeground() } }
        }
    }
}
