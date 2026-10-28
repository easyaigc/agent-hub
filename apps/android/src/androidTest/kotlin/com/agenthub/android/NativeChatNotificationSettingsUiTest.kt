package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual settings page in owned MainActivity; never clicks enable/picker/permission/preview/test notice. */
class NativeChatNotificationSettingsUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=2) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var store: NativeChatNotificationStore
    private lateinit var platform: NativeChatNotificationPlatform
    private lateinit var original: String
    private class PageLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var page: PageLifecycle
    @Before fun prepare() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned = OwnedControlAcceptance(ui.activity, "chat-notification-settings-ui"); owned.session.resetAcceptance()
            page = PageLifecycle().also { it.registry.currentState = Lifecycle.State.RESUMED }
        }
        store = NativeChatNotificationStore(owned.storage)
        store.update { it.copy(enabled = true, sound = "none", foregroundBanner = false, showThreadTitle = false) }
        original = requireNotNull(owned.storage.read(NativeChatNotificationStore.RECORD))
        platform = NativeChatNotificationPlatform(ui.activity, NativeL10n(ui.activity, "en"), NativeChatNotificationSoundLibrary(ui.activity, owned.storage))
    }
    @After fun preserve() {
        if (::owned.isInitialized) ui.runOnIdle { owned.session.leaveForeground(); owned.verifyDefault(); assertTrue(owned.session.operationRecords.isEmpty()) }
    }
    private fun mount(selected: State<NativeChatNotificationStore> = mutableStateOf(store), sound: Boolean = false) {
        val args = InstrumentationRegistry.getArguments(); val language = args.getString("viewportLanguage") ?: "en"; val dark = args.getString("viewportTheme") == "dark"
        ui.runOnIdle { ui.activity.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                ProductTextProvider(language) { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (sound) NativeChatNotificationSoundSettingsPage(selected.value, platform, null) { }
                        else NativeChatNotificationSettingsPage(selected.value, platform, emptyList())
                    }
                } }
            }
        } }
    }
    private fun ready(tag: String) = ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun brokenThenRetry(raw: String) {
        owned.storage.write(NativeChatNotificationStore.RECORD, raw); mount()
        ready("chat-notification-unavailable")
        ui.onNodeWithTag("chat-notifications-enabled").assertDoesNotExist()
        assertEquals(raw, owned.storage.read(NativeChatNotificationStore.RECORD))
        ui.onNodeWithTag("chat-notification-retry").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("chat-notification-loading").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("chat-notification-unavailable").assertIsDisplayed()
        assertEquals(raw, owned.storage.read(NativeChatNotificationStore.RECORD))
        owned.storage.write(NativeChatNotificationStore.RECORD, original)
        ui.onNodeWithTag("chat-notification-retry").performClick(); ready("chat-notifications-enabled")
        ui.onNodeWithTag("chat-notifications-enabled").assertIsOn()
        assertEquals("none", store.preferences().sound); assertFalse(store.preferences().showThreadTitle)
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun malformedRecordIsUnavailableAndRetryPreservesBytesThenRecovers() { brokenThenRetry("{not-json") }
    @Test fun futureRecordIsUnavailableWithoutWritableDefault() { brokenThenRetry("{\"version\":99}") }
    @Test fun resumeFailureDisablesLastSuccessfulControlsAndRetryRecovers() {
        mount(); ready("chat-notifications-enabled"); ui.onNodeWithTag("chat-notifications-enabled").assertIsOn()
        ui.runOnIdle { page.registry.currentState = Lifecycle.State.CREATED }
        val bad = "{\"version\":7}"; owned.storage.write(NativeChatNotificationStore.RECORD, bad)
        ui.runOnIdle { page.registry.currentState = Lifecycle.State.RESUMED }
        ready("chat-notification-unavailable"); ui.onNodeWithTag("chat-notifications-enabled").assertDoesNotExist()
        assertEquals(bad, owned.storage.read(NativeChatNotificationStore.RECORD))
        owned.storage.write(NativeChatNotificationStore.RECORD, original)
        ui.onNodeWithTag("chat-notification-retry").performClick(); ready("chat-notifications-enabled")
        ui.onNodeWithTag("chat-notifications-enabled").assertIsOn(); assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun replacedPageRejectsLateEncryptedReadFromOldStore() {
        val acquired = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = Thread { owned.storage.operationTransaction { acquired.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }.apply { start() }
        assertTrue(acquired.await(5, TimeUnit.SECONDS))
        val replacementStorage = NativeSessionStorage(ui.activity, "P9-settings-replacement-${java.util.UUID.randomUUID()}")
        val replacement = NativeChatNotificationStore(replacementStorage)
        val selected = mutableStateOf(store)
        try {
            mount(selected)
            ui.runOnIdle { selected.value = replacement }
        } finally { release.countDown(); blocker.join(5_000) }
        ready("chat-notifications-enabled"); ui.onNodeWithTag("chat-notifications-enabled").assertIsOff()
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
        assertNull(replacementStorage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun futureSoundCatalogShowsUnavailableWithoutWritableChoicesAndRetryRecovers() {
        val bad = "{\"version\":99}"; owned.storage.write(NativeChatNotificationSoundLibrary.RECORD, bad)
        mount(sound = true); ready("chat-sound-unavailable")
        ui.onNodeWithTag("chat-sound-choice-default").assertDoesNotExist()
        assertEquals(bad, owned.storage.read(NativeChatNotificationSoundLibrary.RECORD))
        owned.storage.remove(NativeChatNotificationSoundLibrary.RECORD)
        ui.onNodeWithTag("chat-sound-retry").performClick(); ready("chat-notification-sound-page")
        ui.onNodeWithTag("chat-sound-choice-none").assertIsSelected()
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun malformedPreferencesOnSoundPagePreserveOriginalBytesAndRetry() {
        val bad = "{bad-json"; owned.storage.write(NativeChatNotificationStore.RECORD, bad)
        mount(sound = true); ready("chat-sound-unavailable")
        ui.onNodeWithTag("chat-sound-choice-none").assertDoesNotExist()
        assertEquals(bad, owned.storage.read(NativeChatNotificationStore.RECORD))
        owned.storage.write(NativeChatNotificationStore.RECORD, original)
        ui.onNodeWithTag("chat-sound-retry").performClick(); ready("chat-notification-sound-page")
        ui.onNodeWithTag("chat-sound-choice-none").assertIsSelected()
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
    }
}
