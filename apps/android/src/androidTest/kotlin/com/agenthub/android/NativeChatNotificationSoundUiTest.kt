package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File

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

    @Test fun longImportedSoundActionsStayReachableInNarrowChineseLightFontTwo() = narrowImportedSoundActions("zh-Hans", false)
    @Test fun longImportedSoundActionsStayReachableInNarrowEnglishDarkFontTwo() = narrowImportedSoundActions("en", true)

    /** Actual production component at controlled Compose width/font; no OS font or audible-preview claim. */
    private fun narrowImportedSoundActions(language: String, dark: Boolean) {
        val title = if (language == "en") "LongImportedNotificationSound".repeat(4).take(80)
            else "这是一段用于独立验收的超长本机导入铃声".repeat(5).take(80)
        val item = library.importNormalized(title, "wav", NativeChatNotificationPcm.builtin("soft.caf"))
        assertEquals(title, item.title)
        notices.update { it.copy(sound = item.id, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to item.id)) }
        var renderedDensity = 0f
        var renderedFont = 0f
        mount {
            val inherited = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(inherited.density, 2f)) {
                val actual = LocalDensity.current
                SideEffect { renderedDensity = actual.density; renderedFont = actual.fontScale }
                ProductTextProvider(language) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Surface(Modifier.width(320.dp).fillMaxHeight().testTag("chat-sound-actions-viewport")) {
                            NativeChatNotificationSoundSettingsPage(notices, platform, null) { }
                        }
                    }
                }
            }
        }
        ready("chat-notification-sound-page")
        val remove = compose.onNodeWithTag("chat-sound-delete-" + item.id)
        val preview = compose.onNodeWithTag("chat-sound-preview-" + item.id)
        preview.performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        remove.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val deleteDescription = (if (language == "en") "Delete" else "删除") + " " + title
        remove.assertContentDescriptionEquals(deleteDescription)
        val viewport = compose.onNodeWithTag("chat-sound-actions-viewport").fetchSemanticsNode().boundsInRoot
        val previewBounds = preview.fetchSemanticsNode().boundsInRoot
        val deleteBounds = remove.fetchSemanticsNode().boundsInRoot
        assertEquals(2f, renderedFont, .01f)
        assertTrue(renderedDensity > 0f)
        assertEquals(320f, viewport.width / renderedDensity, 1f)
        for ((label, bounds) in listOf("preview" to previewBounds, "delete" to deleteBounds)) {
            assertTrue("$label has a visible 48dp click width", bounds.width + .5f >= 48f * renderedDensity)
            assertTrue("$label has a visible 48dp click height", bounds.height + .5f >= 48f * renderedDensity)
            assertTrue("$label stays inside the controlled viewport", bounds.left >= viewport.left - .5f && bounds.right <= viewport.right + .5f && bounds.top >= viewport.top - .5f && bounds.bottom <= viewport.bottom + .5f)
        }
        // Clipped semantics bounds alone cannot prove that the taller preview is fully visible.
        fun unclipped(node: SemanticsNodeInteraction): Rect = node.getUnclippedBoundsInRoot().let {
            Rect(it.left.value * renderedDensity, it.top.value * renderedDensity, it.right.value * renderedDensity, it.bottom.value * renderedDensity)
        }
        val previewUnclipped = unclipped(preview)
        val deleteUnclipped = unclipped(remove)
        for ((visible, raw) in listOf(previewBounds to previewUnclipped, deleteBounds to deleteUnclipped)) {
            assertEquals("Full left edge remains visible", raw.left, visible.left, 1.5f)
            assertEquals("Full top edge remains visible", raw.top, visible.top, 1.5f)
            assertEquals("Full right edge remains visible", raw.right, visible.right, 1.5f)
            assertEquals("Full bottom edge remains visible", raw.bottom, visible.bottom, 1.5f)
        }
        assertTrue("Preview and delete click bounds do not overlap", previewBounds.right <= deleteBounds.left)
        fun rect(value: Rect) = JSONObject().put("left", value.left).put("top", value.top).put("right", value.right).put("bottom", value.bottom)
        val proof = JSONObject().put("version", 1).put("language", language).put("dark", dark)
            .put("composeFontScale", renderedFont).put("controlledWidthDp", 320).put("density", renderedDensity)
            .put("viewport", rect(viewport)).put("preview", rect(previewBounds)).put("delete", rect(deleteBounds))
            .put("previewUnclipped", rect(previewUnclipped)).put("deleteUnclipped", rect(deleteUnclipped))
            .put("deleteDescription", deleteDescription).put("audioPlaybackVerified", false)
            .put("systemNotificationVerified", false).put("physicalDeviceOrRealHostModelAccepted", false)
        owned.nodeShot(compose.onNodeWithTag("chat-sound-actions-viewport"), "sound-actions-" + language)
        // This exact newly imported fixture is the only deletion target; no OS preview is started.
        remove.performClick()
        compose.waitUntil(10_000) { library.find(item.id) == null }
        saved { it.sound == "default" && it.categorySounds.isEmpty() }
        compose.onNodeWithTag("chat-sound-delete-" + item.id).assertDoesNotExist()
        proof.put("syntheticImportDeleted", true).put("matchingPreferencesReset", true)
        val output = File(owned.folder, "sound-actions-" + language + ".json")
        check(output.createNewFile()); output.writeText(proof.toString()); android.system.Os.chmod(output.path, 0x180)
        java.io.FileOutputStream(output, true).use { it.fd.sync() }
    }
}
