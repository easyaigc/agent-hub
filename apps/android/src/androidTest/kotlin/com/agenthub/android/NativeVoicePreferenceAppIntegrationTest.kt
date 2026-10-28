package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*

/** Actual Activity binding and settings navigation, isolated from the default phone profile. */
class NativeVoicePreferenceAppIntegrationTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var replacement: MobileSession
    private var previous: MainActivity.AcceptanceSessionState?=null
    private var defaultPreference: String?=null
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        defaultPreference=NativeSessionStorage(ui.activity).read(NativeVoicePreferenceRecord.RECORD)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"voice-preference-app")
            replacement=owned.session;replacement.resetAcceptance()
            previous=ui.activity.adoptSessionForAcceptance(replacement)
        }
    }
    @After fun finish() {
        if(::replacement.isInitialized)ui.runOnIdle { replacement.leaveForeground() }
        previous?.let { old->ui.runOnIdle { ui.activity.restoreSessionForAcceptance(old) } }
        if(::owned.isInitialized)ui.runOnIdle {
            owned.verifyDefault()
            assertEquals(defaultPreference,NativeSessionStorage(ui.activity).read(NativeVoicePreferenceRecord.RECORD))
        }
    }
    @Test fun realActivityBindsOnlyItsRestoredStoreAndRetainsTheExplicitDefaultOnRecreation() {
        ui.waitUntil(15_000) { replacement.voiceSettings.persisted }
        assertEquals(VoiceInputController.Provider.DESKTOP,replacement.voiceSettings.defaultProvider)
        assertNull(owned.storage.read(NativeVoicePreferenceRecord.RECORD))
        ui.onNodeWithTag("chat-menu").performClick()
        ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithTag("voice-transcription-picker").performScrollTo().performClick()
        ui.onNodeWithTag("voice-provider-system").performClick().assertIsSelected()
        ui.waitUntil(15_000) { replacement.voiceSettings.persisted && replacement.voiceSettings.defaultProvider==VoiceInputController.Provider.SYSTEM }
        val original=requireNotNull(owned.storage.read(NativeVoicePreferenceRecord.RECORD))
        assertEquals(VoiceInputController.Provider.SYSTEM,NativeVoicePreferenceRecord.decode(original))
        assertEquals(defaultPreference,NativeSessionStorage(ui.activity).read(NativeVoicePreferenceRecord.RECORD))
        ui.activityRule.scenario.recreate()
        ui.waitUntil(20_000) { ui.activity.session===replacement && replacement.voiceSettings.persisted }
        assertEquals(VoiceInputController.Provider.SYSTEM,replacement.voiceSettings.defaultProvider)
        assertEquals(original,owned.storage.read(NativeVoicePreferenceRecord.RECORD))
        assertFalse(replacement.voice?.active==true)
        assertFalse(replacement.questionVoice?.active==true)
        assertTrue(replacement.operationRecords.isEmpty())
    }
}
