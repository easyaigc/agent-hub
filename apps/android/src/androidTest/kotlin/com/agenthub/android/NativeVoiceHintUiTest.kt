package com.agenthub.android

import android.Manifest
import android.graphics.Bitmap
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owned MainActivity window and real product catalogs; no controller, microphone or Host task is started. */
class NativeVoiceHintUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private var microphonePermission=0
    @Before fun prepareOwnedWindow() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"voice-hint-ui") }
        microphonePermission=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    }
    @After fun preserveDefaultData() {
        if(::owned.isInitialized)ui.runOnIdle {
            owned.session.leaveForeground();owned.verifyDefault()
            assertEquals(microphonePermission,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        }
    }
    private fun shot(name: String,language: String,dark: Boolean) {
        val i=InstrumentationRegistry.getInstrumentation();ui.waitForIdle()
        val frames=CountDownLatch(1)
        i.runOnMainSync { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue("Owned window must draw two frames",frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null
        i.runOnMainSync {
            assertEquals("com.easyai.agenthub.android.acceptance",ui.activity.packageName)
            assertTrue("Exact acceptance Activity must have focus",ui.activity.window.decorView.hasWindowFocus())
            bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888)
        }
        val image=checkNotNull(bitmap);val done=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { PixelCopy.request(ui.activity.window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            File(owned.folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-activity-window").put("package",ui.activity.packageName)
                .put("width",image.width).put("height",image.height).put("language",language).put("dark",dark).put("fullSystemComposite",false).toString())
        }finally { image.recycle() }
    }
    @Test fun centralHintStopsOnceInBothThemesAndLanguages() {
        var calls=0
        var language by mutableStateOf("zh-Hans")
        var dark by mutableStateOf(false)
        ui.runOnIdle { ui.activity.setContent {
            ProductTextProvider(language) {
                MaterialTheme(colorScheme=if(dark)darkColorScheme()else lightColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=Alignment.Center) {
                        VoiceListeningActivity(listOf(0f,.3f,.8f,1f,.5f,.2f,0f),productText("正在录音，结束后由电脑转写"),productText("轻触停止录音"),
                            { calls++ },Modifier.fillMaxWidth().padding(horizontal=16.dp))
                    }
                }
            }
        } }
        var expectedCalls=0
        for(isDark in listOf(false,true))for(locale in listOf("zh-Hans","en")) {
            ui.runOnIdle { language=locale;dark=isDark }
            val text=NativeL10n(ui.activity,locale)
            val hint=text.tr("轻触停止录音")
            assertEquals("Requires parent N067 catalog union",if(locale=="en")"Tap to stop recording"else "轻触停止录音",hint)
            val root=ui.onNodeWithTag("dictation-waveform")
            root.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertContentDescriptionEquals(hint).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,text.tr("正在录音，结束后由电脑转写")))
            val leading=ui.onNodeWithTag("dictation-waveform-leading",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode()
            val center=ui.onNodeWithTag("dictation-stop-hint",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode()
            val trailing=ui.onNodeWithTag("dictation-waveform-trailing",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode()
            for(child in listOf(leading,center,trailing)) {
                assertFalse("Decorative children must not duplicate speech",child.config.contains(SemanticsProperties.ContentDescription))
                assertFalse("Only the parent is a stop button",child.config.contains(SemanticsActions.OnClick))
            }
            val bounds=root.fetchSemanticsNode().boundsInRoot
            assertTrue(leading.boundsInRoot.width>0 && trailing.boundsInRoot.width>0)
            assertTrue(leading.boundsInRoot.right<=center.boundsInRoot.left && center.boundsInRoot.right<=trailing.boundsInRoot.left)
            assertEquals(bounds.center.x,center.boundsInRoot.center.x,1f)
            assertEquals(leading.boundsInRoot.width,trailing.boundsInRoot.width,1f)
            root.performTouchInput { click(this.center) };expectedCalls++
            ui.runOnIdle { assertEquals(expectedCalls,calls) }
            shot("voice-hint-${locale}-${if(isDark)"dark"else "light"}",locale,isDark)
        }
        ui.runOnIdle { assertEquals(4,calls) }
        File(owned.folder,"voice-hint-proof.json").writeText(JSONObject().put("cases",4).put("stopCallbacks",calls)
            .put("minimumHitHeightDp",48).put("twoDecorativeWaveformSides",true).put("oneAccessibleParentButton",true)
            .put("catalogLanguages",2).put("themes",2).put("fixtureMicrophoneCalls",0).put("fixtureHostCalls",0).toString())
    }
}
