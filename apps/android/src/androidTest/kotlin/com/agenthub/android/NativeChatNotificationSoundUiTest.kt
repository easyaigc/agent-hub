package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Standalone production pages. These scenarios never click preview/test-notification or open the OS picker. */
class NativeChatNotificationSoundUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule = NativeTestForeground()
    @get:Rule(order=2) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var notices: NativeChatNotificationStore
    private lateinit var library: NativeChatNotificationSoundLibrary
    private lateinit var platform: NativeChatNotificationPlatform
    @Before fun prepare() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        compose.waitUntil(30_000) { !compose.activity.session.restoring }
        compose.runOnIdle { owned = OwnedControlAcceptance(compose.activity, "chat-notification-sound-ui"); owned.session.resetAcceptance() }
        val storage = owned.storage
        notices = NativeChatNotificationStore(storage); library = NativeChatNotificationSoundLibrary(context, storage)
        platform = NativeChatNotificationPlatform(context, NativeL10n(context, "en"), library)
    }
    @After fun preserveDefault() {
        if (::owned.isInitialized) compose.runOnIdle { owned.session.leaveForeground(); owned.verifyDefault(); assertTrue(owned.session.operationRecords.isEmpty()) }
    }
    private fun mount(content: @Composable () -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val language = args.getString("viewportLanguage") ?: "en"
        val dark = args.getString("viewportTheme") == "dark"
        compose.runOnIdle { compose.activity.setContent {
            ProductTextProvider(language) { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
            } }
        } }
    }
    private fun ready(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun saved(predicate: (NativeChatNotificationPreferences) -> Boolean) {
        compose.waitUntil(10_000) { predicate(notices.preferences()) }
        if (compose.onAllNodesWithTag("chat-sound-choice-none").fetchSemanticsNodes().isNotEmpty()) {
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("chat-sound-choice-none").assertIsEnabled() }.isSuccess }
        }
    }
    @Test fun rootPageOpensDefaultSoundAndBackRetainsChoice() {
        mount { NativeChatNotificationSettingsPage(notices, platform, emptyList()) }; ready("chat-notification-default-sound")
        compose.onNodeWithTag("chat-notification-default-sound").performScrollTo().performClick()
        compose.onNodeWithTag("chat-notification-sound-page").assertExists()
        compose.onNodeWithTag("chat-sound-choice-soft.caf").performScrollTo().performClick()
        saved { it.sound == "soft.caf" }
        compose.onNodeWithTag("chat-sound-back").performScrollTo().performClick()
        compose.onNodeWithTag("chat-notification-sound-page").assertDoesNotExist()
        saved { it.sound == "soft.caf" }
    }
    @Test fun defaultAndSilentAreRealDistinctSavedChoices() {
        mount { NativeChatNotificationSoundSettingsPage(notices, platform, null) { } }; ready("chat-notification-sound-page")
        compose.onNodeWithTag("chat-sound-choice-none").performClick()
        saved { it.sound == "none" }
        compose.onNodeWithTag("chat-sound-choice-default").performClick()
        saved { it.sound == "default" }
    }
    @Test fun categoryOverrideAndInheritDoNotChangeGlobalSound() {
        notices.update { it.copy(sound = "chime.caf") }
        mount { NativeChatNotificationSoundSettingsPage(notices, platform, NativeChatNoticeKind.APPROVAL) { } }; ready("chat-notification-sound-page")
        compose.onNodeWithTag("chat-sound-choice-none").performClick()
        saved { it.categorySounds[NativeChatNoticeKind.APPROVAL] == "none" && it.sound == "chime.caf" }
        compose.onNodeWithTag("chat-sound-choice-inherit").performClick()
        saved { !it.categorySounds.containsKey(NativeChatNoticeKind.APPROVAL) && it.sound == "chime.caf" }
    }
    @Test fun deletingActiveImportThroughPageResetsAllMatchingReferences() {
        val item = library.importNormalized("synthetic UI sound", "wav", NativeChatNotificationPcm.builtin("soft.caf"))
        notices.update { it.copy(sound = item.id, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to item.id)) }
        mount { NativeChatNotificationSoundSettingsPage(notices, platform, null) { } }; ready("chat-notification-sound-page")
        compose.onNodeWithTag("chat-sound-delete-" + item.id).performScrollTo().performClick()
        compose.waitUntil(10_000) { library.find(item.id) == null }
        saved { it.sound == "default" && it.categorySounds.isEmpty() }
    }
}
