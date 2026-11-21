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
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
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
    private fun mount(selected: State<NativeChatNotificationStore> = mutableStateOf(store), sound: Boolean = false,
        status: @Composable (() -> Unit)? = null, coordinator: NativeChatNotificationCoordinator? = null) {
        val args = InstrumentationRegistry.getArguments(); val language = args.getString("viewportLanguage") ?: "en"; val dark = args.getString("viewportTheme") == "dark"
        ui.runOnIdle { ui.activity.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                ProductTextProvider(language) { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (coordinator != null) NativeChatNotificationsPage(coordinator)
                        else if (sound) NativeChatNotificationSoundSettingsPage(selected.value, platform, null) { }
                        else NativeChatNotificationSettingsPage(selected.value, platform, emptyList(), status = status)
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
    @Test fun providerReportSuppressesUnavailableFallbackAndLegacyEntryKeepsIt() {
        val report = NativeChatNotificationMonitorReport("push-registered",
            NativeChatNoticeTarget("settings-owned-host", "settings-owned-workspace", "settings-owned-runtime"))
        mount(status = {
            NativeChatNotificationRecoveryStatus(report,
                { _, _ -> error("Status rendering must not submit an operation") },
                { _ -> error("Status rendering must not revoke an operation") })
        })
        ready("chat-notification-recovery-status")
        ui.onNodeWithTag("chat-notification-recovery-status").assertIsDisplayed()
        ui.onNodeWithTag("chat-notification-push-unavailable").assertDoesNotExist()
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))

        // The same production page still explains its legacy local-only entry when no report slot exists.
        mount()
        ready("chat-notification-push-unavailable")
        ui.onNodeWithTag("chat-notification-push-unavailable").assertIsDisplayed()
        ui.onNodeWithTag("chat-notification-recovery-status").assertDoesNotExist()
        assertEquals(original, owned.storage.read(NativeChatNotificationStore.RECORD))
    }
    @Test fun easyWorkHostToggleAndMetadataSurviveActualCoordinatorRefreshAndRestore() {
        val codex = TargetScope("codex", null, null, "settings-codex-host", "settings-codex-workspace", "desktop-runtime")
        val ew = TargetScope("easywork", "https://synthetic.invalid", "synthetic-subject", "settings-ew-host", "settings-ew-workspace", "local")
        val first = MobileClient(codex.hostId, "Owned Codex", codex, "https://synthetic.invalid", mode = "desktop-runtime")
        val second = MobileClient("owned-ew-client", "Owned EasyWork", ew, "https://synthetic.invalid", mode = "easywork-runtime",
            agentConnection = AgentConnection(ew, listOf(AgentCandidate("relay", "relay-http", "https://synthetic.invalid")), "unused-owned-ref"))
        fun persist(clients: List<MobileClient>) {
            owned.storage.write("metadata", JSONObject().put("version", 1).put("clients", JSONArray(clients.map(::encodeClient))).toString())
        }
        persist(listOf(first, second))
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        var sessionReady = true
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val isolatedSystem = object : NativeChatNotificationSystem {
            override val sounds = platform.sounds
            override fun languageChanged(selection: String) { }
            override fun permission() = NativeChatNotificationPermission(false, true, true)
            override fun channels() = emptyList<NativeChatNotificationChannelState>()
            override fun settings(channel: String?): android.content.Intent = error("No system settings in metadata test")
            override fun soundProviderRegistered() = false
            override fun channelSoundTitle(channel: NativeChatNotificationChannelState) = ""
            override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? = error("No system delivery in metadata test")
            override fun cancelOwned(target: NativeChatNoticeTarget?) { } // Never cancel original-profile OS notices.
            override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>) { error("No event delivery in metadata test") }
            override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) { error("No system delivery in metadata test") }
            override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean = error("No test notification in metadata test")
        }
        val port = object : NativeChatNotificationSessionPort {
            override val storage = owned.storage
            override val reading = object : NativeChatNotificationReading {
                override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult = error("No notification delivery in metadata test")
                override fun mayPresent(event: NativeChatNotificationEvent) = false
            }
            override fun state() = NativeChatNotificationSessionState(sessionReady, true, "owned-settings", false, readingReady = false)
            override suspend fun savedConnections(): List<NativeChatNotificationSavedConnection> {
                val values = withContext(Dispatchers.IO) {
                    storage.metadata()!!.getJSONArray("clients").objects().map(::decodeClient)
                        .filter { it.scope?.agentId == "codex" }.map { NativeChatNotificationSavedConnection.capture(it, "t".repeat(43)) }
                }
                refreshes.incrementAndGet(); return values
            }
            override suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean) { error("No reading dispatch in metadata test") }
            override fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean): Boolean = error("No navigation in metadata test")
        }
        lateinit var coordinator: NativeChatNotificationCoordinator
        var created: NativeChatNotificationCoordinator? = null
        fun publish() {
            val clients = owned.storage.metadata()!!.getJSONArray("clients").objects().map(::decodeClient)
            ui.runOnIdle { coordinator.updateComputers(clients); coordinator.reconcile() }
        }
        try {
            ui.runOnIdle { coordinator = NativeChatNotificationCoordinator(parent, port, isolatedSystem, applicationId = ui.activity.packageName).also { created = it } }
            publish(); ui.waitUntil(10_000) { refreshes.get() >= 1 }
            mount(coordinator = coordinator)
            ready("chat-notification-host-" + ew.hostId)
            val ewTag = "chat-notification-host-" + ew.hostId
            val codexTag = "chat-notification-host-" + codex.hostId
            val initial = store.preferences()
            ui.onNodeWithTag(ewTag).performScrollTo().assertIsOn().performClick()
            ui.waitUntil(10_000) { store.preferences().excludedHosts == setOf(ew.hostId) }
            ui.onNodeWithTag(ewTag).assertIsOff(); ui.onNodeWithTag(codexTag).assertIsOn()
            val off = store.preferences()
            assertEquals(initial.copy(excludedHosts = setOf(ew.hostId), revision = off.revision, generation = initial.generation + 1), off)
            ui.onNodeWithTag(ewTag).performScrollTo().performClick()
            ui.waitUntil(10_000) { store.preferences().excludedHosts.isEmpty() }
            val on = store.preferences()
            assertEquals(off.copy(excludedHosts = emptySet(), revision = on.revision, generation = off.generation + 1), on)
            ui.onNodeWithTag(ewTag).assertIsOn(); ui.onNodeWithTag(codexTag).assertIsOn()
            // The real refresh awaits a Codex-only authority inventory. It must not replace all-provider rows.
            val before = refreshes.get(); ui.runOnIdle { coordinator.reconcile() }
            ui.waitUntil(10_000) { refreshes.get() > before }
            ui.runOnIdle { assertEquals(listOf(codex.hostId, ew.hostId), coordinator.computers.map { it.hostId }) }
            // Restoration/rename publishes metadata, not stale authority names; deletion stays deleted after refresh.
            ui.runOnIdle { sessionReady = false; coordinator.reconcile() }
            persist(listOf(first, second.copy(name = "Renamed EasyWork"))); publish()
            val restored = refreshes.get()
            ui.runOnIdle { sessionReady = true; coordinator.reconcile() }
            ui.waitUntil(10_000) { refreshes.get() > restored }
            ui.onNodeWithTag(ewTag).assertTextContains("Renamed EasyWork")
            val migrated = second.copy(agentConnection = null, requiresReadTarget = true,
                migrationEasyWork = MigratedEasyWorkConnection.decode(JSONObject().put("version", 1).put("provider", "easywork")
                    .put("archiveId", "a".repeat(64)).put("target", CodexCommandCodec.target(ew)).put("requiresPairing", true)
                    .put("credentialState", "none").put("preference", "automatic").put("candidateOrigins", JSONArray(listOf("https://synthetic.invalid")))))
            persist(listOf(first, migrated)); publish()
            ui.runOnIdle { assertEquals(listOf(NativeChatNoticeTarget.from(codex)), coordinator.codexComputerTargets) }
            ui.onNodeWithTag(ewTag).assertIsOn() // Inert migrated descriptor stays a local preference row, not a Codex action target.
            persist(listOf(first)); val last = refreshes.get(); publish()
            ui.waitUntil(10_000) { refreshes.get() > last }
            ui.onNodeWithTag(ewTag).assertDoesNotExist(); ui.onNodeWithTag(codexTag).assertIsOn()
            assertNull(owned.storage.read(NativeChatNotificationIdentity.RECORD))
            assertNull(owned.storage.read(NativeChatNotificationAuthorizations.RECORD))
            assertNull(owned.storage.read(NativeChatNotificationPushState.RECORD))
        } finally {
            runBlocking {
                withContext(Dispatchers.Main.immediate) { created?.stopAndJoinForMigration() }
                parent.coroutineContext[Job]?.cancelAndJoin()
            }
        }
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
