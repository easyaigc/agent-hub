package com.agenthub.android

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual shared strip/native window with bounded composed viewport, injected callbacks only.
 * No physical font/display change, system permission dialog, microphone, recognizer, Host or model. */
class NativeSavedVoiceFallbackUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(180)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private var microphonePermission=0
    private val fixtures=mutableListOf<NativeSavedVoiceFixture>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) {block()} }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"saved-voice-fallback-ui");owned.session.resetAcceptance();microphonePermission=ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) }
    }
    @After fun cleanup() { main { fixtures.forEach {it.cleanup()};owned.session.leaveForeground();owned.verifyDefault();assertEquals(microphonePermission,ui.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)) } }
    private fun fixture(current: ()->Boolean={true},commands: AgentCommands?=null): NativeSavedVoiceFixture {
        check(fixtures.size<8)
        // The parent namespace already owns a fresh UUID; child suffixes are bounded to 32.
        val suffix="saved-voice-${fixtures.size}"
        var value: NativeSavedVoiceFixture?=null
        main { value=NativeSavedVoiceFixture.create(ui.activity,owned.storage.child(ui.activity,suffix),commands=commands,current=current) }
        return checkNotNull(value).also(fixtures::add)
    }
    private fun content(f: NativeSavedVoiceFixture,locale: String,night: Boolean,outerScroll: Boolean) {
        ui.runOnIdle { ui.activity.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                ProductTextProvider(locale) { MaterialTheme(colorScheme=if(night)darkColorScheme()else lightColorScheme()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { Surface(Modifier.width(280.dp).height(270.dp).testTag("saved-voice-viewport")) {
                        if(outerScroll)Column(Modifier.verticalScroll(rememberScrollState())) {VoiceInputStrip(f.controller,f.draft.text)}
                        else VoiceInputStrip(f.controller,f.draft.text)
                    } }
                } }
            }
        } };ui.waitForIdle()
    }
    private fun tap(tag: String) { ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick() }
    @Test fun phoneFallbackAndRecoveryActionsStayReachableInBilingualBoundedAndOuterScrollLayouts() {
        for(locale in listOf("zh-Hans","en"))for(night in listOf(false,true))for(outer in listOf(false,true)) {
            val f=fixture();content(f,locale,night,outer)
            val phone=ui.onNodeWithTag("voice-rerecord-system").performScrollTo().assertIsDisplayed().assertIsEnabled()
            phone.assertTextContains(NativeL10n(ui.activity,locale).tr("改用手机重新录音"))
            val bounds=phone.fetchSemanticsNode().boundsInRoot;val view=ui.onNodeWithTag("saved-voice-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top>=view.top && bounds.bottom<=view.bottom);assertTrue(bounds.height>=with(ui.activity.resources.displayMetrics){48*density})
            if(!outer)ui.onNodeWithTag("saved-voice-viewport").captureToImage().asAndroidBitmap().let { image ->
                try { File(owned.folder,"recovery-$locale-${if(night)"dark"else"light"}.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }finally {image.recycle()}
            }
            tap("voice-record-again");main { NativeSavedVoiceFixture.until { f.controller.discardFailed } }
            ui.onNodeWithTag("voice-record-again").performScrollTo().assertIsNotEnabled()
            // Restore this fixture presentation without touching its immutable original; explicit discard failed.
            main { f.controller.endWaiting();NativeSavedVoiceFixture.until { !f.controller.active }
                f.controller.start(f.draft,{true},{_,text->f.applied.add(text);true})
                NativeSavedVoiceFixture.until { f.controller.phase==VoiceInputController.Phase.FAILED } }
            tap("voice-rerecord-system");main { f.phoneReady();f.engine.listener.result("fresh phone") }
            assertEquals(listOf("fresh phone"),f.applied);main {f.preserved()};assertEquals(1,f.terminalCalls)
        }
    }
    @Test fun changedDraftDisablesRerecordAndEndWaitingDoesNotSubmitATerminal() {
        var current by mutableStateOf(true);val f=fixture(current={current});content(f,"en",false,false)
        ui.runOnIdle {current=false};ui.waitForIdle()
        ui.onNodeWithTag("voice-rerecord-system").assertDoesNotExist()
        ui.onNodeWithTag("voice-record-again").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("cancel-dictation").performScrollTo().assertContentDescriptionEquals(NativeL10n(ui.activity,"en").tr("丢弃录音"))
        tap("voice-end-waiting");main { NativeSavedVoiceFixture.until { !f.controller.active };f.preserved() }
        assertEquals(0,f.terminalCalls);assertEquals(0,f.engine.starts);assertTrue(f.applied.isEmpty())
    }
}
