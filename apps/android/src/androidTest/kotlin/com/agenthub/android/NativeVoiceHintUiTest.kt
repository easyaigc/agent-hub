package com.agenthub.android

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** N067 semantics and click target; real microphone is deliberately outside this UI fixture. */
class NativeVoiceHintUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun centralHintStopsOnceInBothThemesAndLanguages() {
        var calls = 0
        var hint by androidx.compose.runtime.mutableStateOf("轻触停止录音")
        var dark by androidx.compose.runtime.mutableStateOf(false)
        ui.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            VoiceListeningActivity(listOf(0f, .5f, 1f), "listening", hint, { calls++ })
        } }
        for (isDark in listOf(false, true)) for (text in listOf("轻触停止录音", "Tap to stop recording")) {
            ui.runOnIdle { hint = text; dark = isDark }
            ui.onNodeWithTag("dictation-waveform").assertContentDescriptionEquals(text).assertHasClickAction().performClick()
        }
        ui.runOnIdle { assertEquals(4, calls) }
    }
}
