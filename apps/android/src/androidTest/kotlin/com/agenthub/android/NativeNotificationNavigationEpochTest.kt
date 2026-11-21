package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Shipping coordinators + encrypted stores + controlled native ports. No Activity/Host/FCM acceptance is implied. */
class NativeNotificationNavigationEpochTest {
    private class Fixture(private val easy: Boolean) {
        private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-notification-nav-" + UUID.randomUUID())
        val notices = NativeChatNotificationStore(storage)
        val easyNotices = NativeEasyWorkNotificationStore(storage)
        val push = NativeChatNotificationPushState(storage)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val target = TargetScope(if (easy) "easywork" else "codex", if (easy) "https://fixture.invalid" else null,
            if (easy) "fixture-subject" else null, "fixture-host", "fixture-workspace", "fixture-runtime")
        private val credentialRef = "p4-navigation-fixture-" + UUID.randomUUID()
        private val credential = "fixture-credential-" + "x".repeat(32)
        private val configuration = "c".repeat(64)
        private val firstToken = "fixture-token-t1"
        private val nextToken = "fixture-token-t2"
        private val thread = MobileThread("fixture-thread", target.hostId, "Fixture", "idle")
        private val workspace = MobileWorkspace(target.hostId, "Fixture", if (easy) "easywork-runtime" else "desktop-runtime",
            emptyList(), listOf(thread), null, null, target, emptyList(), "{}", true)
        private val client = MobileClient(target.hostId, "Fixture", target, "https://fixture.invalid", mode = if (easy) "easywork-runtime" else "desktop-runtime",
            agentConnection = if (easy) AgentConnection(target, listOf(AgentCandidate("fixture-lan", "lan-wss", "wss://fixture.invalid/socket", "f".repeat(64), "lan")), credentialRef) else null)
        private val saved = if (easy) null else NativeChatNotificationSavedConnection.capture(client, credential)
        private val releases = mutableListOf<CompletableDeferred<Unit>>()
        private var foreground = true
        private var navigation = 0
        var opened = 0; private set
        var commandCalls = 0; private set
        var selected = "fixture-original-thread"; private set
        val draft = "Original fixture draft"
        lateinit var ticket: String; private set
        lateinit var coordinator: NativeChatNotificationCoordinator; private set
        private fun denied(): Nothing { commandCalls++; error("No Host/provider mutation allowed") }
        private val commands = object : NativeChatNotificationCommands {
            override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = denied()
            override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = denied()
            override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = denied()
            override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = denied()
            override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed = denied()
        }
        private val taskCommands = object : AgentTaskNotifications {
            override suspend fun registerTaskNotifications(client: MobileClient, registration: NativeTaskNotificationRegistration): NativeTaskNotificationStatus = denied()
            override suspend fun updateTaskNotificationPreferences(client: MobileClient, deviceId: String, preferences: NativeTaskNotificationPreferences): NativeTaskNotificationStatus = denied()
            override suspend fun taskNotificationEvents(client: MobileClient, deviceId: String, after: String): NativeTaskNotificationFeed = denied()
            override suspend fun taskNotificationStatus(client: MobileClient, deviceId: String): NativeTaskNotificationStatus = denied()
            override suspend fun unregisterTaskNotifications(client: MobileClient, deviceId: String) { denied() }
        }
        private val reads = object : HostReads by NativeHostReads({ error("No real network fixture") }) {
            override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?) = this@Fixture.workspace
        }
        private fun navigate(current: () -> Boolean): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!current()) return false
            opened++; selected = thread.id; navigation++
            coordinator.navigationWillChange() // Real Session navigation invalidates its own route too.
            return true
        }
        private val easyPort = object : NativeEasyWorkNotificationSessionPort {
            override val notifications: AgentTaskNotifications = taskCommands
            override val reads: AgentReads = this@Fixture.reads
            override fun ready() = foreground
            override fun clients() = listOf(client)
            override fun open(client: MobileClient, workspace: MobileWorkspace, thread: MobileThread, current: () -> Boolean) = navigate(current)
        }
        private val port = object : NativeChatNotificationSessionPort {
            override val storage = this@Fixture.storage
            override val easyWork get() = if (easy) easyPort else null
            override val reading = object : NativeChatNotificationReading {
                override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult = denied()
                override fun mayPresent(event: NativeChatNotificationEvent) = false
            }
            override fun state() = NativeChatNotificationSessionState(true, foreground, navigation.toString(), false, readingReady = false)
            override suspend fun savedConnections() = listOfNotNull(saved)
            override suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean) { denied() }
            override fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean) = navigate(current)
        }
        private val system = object : NativeChatNotificationSystem {
            override val sounds = NativeChatNotificationSoundLibrary(context, storage)
            override fun languageChanged(selection: String) {}
            override fun permission() = NativeChatNotificationPermission(false, false, false)
            override fun settings(channel: String?) = Intent()
            override fun channels() = emptyList<NativeChatNotificationChannelState>()
            override fun soundProviderRegistered() = false
            override fun channelSoundTitle(channel: NativeChatNotificationChannelState) = "Fixture"
            override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? = denied()
            override fun cancelOwned(target: NativeChatNoticeTarget?) {}
            override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>) {}
            override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) { denied() }
            override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean = denied()
        }
        fun release() = CompletableDeferred<Unit>().also { releases.add(it) }
        suspend fun prepare(claimed: suspend (String, String, Job) -> Unit, acknowledging: suspend (String, String) -> Unit = { _, _ -> }) {
            withContext(Dispatchers.IO) {
                val preferences = notices.update { it.copy(enabled = true) }
                push.token(NativeChatNotificationWire.hash(firstToken), configuration)
                if (easy) {
                    check(com.agenthub.core.DomainContract.validate(requireNotNull(client.agentConnection).json().toString(), "connection") == null)
                    val identity = EasyWorkIdentity.load(storage) // Task-owned synthetic original identity only.
                    val easyTarget = EasyWorkTarget(requireNotNull(target.accountAuthority), requireNotNull(target.accountSubject), target.hostId, target.workspaceId, target.runtimeId)
                    val binding = EasyWorkBinding(JSONObject().put("bindingId", "fixture-device-binding").put("status", "active")
                        .put("target", easyTarget.json()).put("account", easyTarget.json().getJSONObject("account"))
                        .put("desktopDeviceId", target.hostId).put("mobileDeviceId", identity.deviceId)
                        .put("desktopPublicKey", easyBase64(ByteArray(32) { 17 })).put("mobilePublicKey", identity.publicKey).toString())
                    storage.write("easywork-credential:$credentialRef", EasyWorkCredential(binding, identity.privateKey).json().toString())
                    storage.write("metadata", JSONObject().put("version", 1).put("clients", JSONArray(listOf(encodeClient(client)))).toString())
                    val authority = NativeEasyWorkNoticeAuthority.capture(storage, client)
                    val row = JSONObject().put("phase", "ready").put("lease", UUID.randomUUID().toString()).put("clientId", client.id)
                        .put("credentialRef", credentialRef).put("credentialEpoch", authority.epoch).put("revision", preferences.revision)
                        .put("tokenHash", NativeChatNotificationWire.hash(firstToken)).put("configuration", configuration)
                    easyNotices.save(target, row)
                    val event = NativeTaskNotificationCodec.notice(JSONObject().put("version", 1).put("id", "e".repeat(64))
                        .put("target", CodexCommandCodec.target(target)).put("kind", "question").put("occurredAt", Instant.now().toString()).put("threadId", thread.id), target)
                    ticket = requireNotNull(easyNotices.claim(event, row) { true }); easyNotices.finish(ticket, "posted")
                } else {
                    val exact = NativeChatNoticeTarget.from(target)
                    val lease = notices.bind(exact, requireNotNull(saved).snapshot.credentialEpoch)
                    val event = NativeChatNotificationEvent("d".repeat(64), exact, NativeChatNoticeKind.QUESTION, Instant.now().toString(), thread.id)
                    ticket = requireNotNull(notices.claim(event, lease, System.currentTimeMillis())).ticket
                    notices.finish(ticket, true)
                    push.save(exact, JSONObject().put("target", NativeChatNotificationCodec.target(exact)).put("stage", "ready"))
                }
            }
            withContext(Dispatchers.Main.immediate) {
                coordinator = NativeChatNotificationCoordinator(scope, port, system, commands,
                    locator = NativeChatNotificationThreadLocator { reads }, applicationId = context.packageName,
                    navigationClaimPersisted = { receipt, owner -> claimed(receipt.ticket, requireNotNull(receipt.navigationClaim), owner) },
                    easyWorkContext = if (easy) context else null,
                    easyWorkNavigation = NativeEasyWorkNavigationObservation(claimed, acknowledging))
                coordinator.reconcile()
            }
        }
        suspend fun tap() = withContext(Dispatchers.Main.immediate) { assertTrue(coordinator.receiveIntent(NativeChatNotificationPlatform.openIntent(context, ticket))) }
        suspend fun background() = withContext(Dispatchers.Main.immediate) { foreground = false; coordinator.reconcile() }
        suspend fun foreground() = withContext(Dispatchers.Main.immediate) { foreground = true; coordinator.reconcile() }
        suspend fun rotate() = withContext(Dispatchers.IO) { assertTrue(push.enqueueToken(nextToken, configuration)) }
        suspend fun claim(): String? = withContext(Dispatchers.IO) {
            if (easy) easyNotices.ticket(ticket)?.optString("navigationClaim")
            else storage.read(NativeChatNotificationStore.RECORD)?.let { JSONObject(it).getJSONArray("receipts").objects().single { row -> row.getString("ticket") == ticket }.optString("navigationClaim") }
        }
        suspend fun waitState(value: String) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main.immediate) { coordinator.openState == value && !coordinator.opening }) delay(10)
        }
        suspend fun close() = withContext(NonCancellable) {
            releases.forEach { it.complete(Unit) }
            withContext(Dispatchers.Main.immediate) { if (::coordinator.isInitialized) coordinator.dispose() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.IO) {
                listOf("metadata", "easywork-device-identity", "easywork-credential:$credentialRef", NativeChatNotificationStore.RECORD,
                    NativeChatNotificationPushState.RECORD, NativeEasyWorkNotificationStore.RECORD).forEach { storage.remove(it) }
            }
        }
    }
    private suspend fun rotatedAfterClaimRejectsMain(easy: Boolean) {
        val fixture = Fixture(easy); val entered = CompletableDeferred<String>(); val release = fixture.release(); lateinit var owner: Job
        try {
            fixture.prepare({ _, claim, job -> owner = job; entered.complete(claim); release.await() })
            fixture.tap(); val original = withTimeout(10_000) { entered.await() }
            fixture.rotate(); release.complete(Unit); withTimeout(10_000) { owner.join() }
            fixture.waitState("unavailable")
            assertEquals(0, fixture.opened); assertEquals("fixture-original-thread", fixture.selected)
            assertEquals(original, fixture.claim()); assertEquals(0, fixture.commandCalls)
            assertEquals("Original fixture draft", fixture.draft)
        } finally { fixture.close() }
    }
    @Test fun codexRotatedAfterDurableClaimDoesNotOpenOldTicket() = runBlocking { rotatedAfterClaimRejectsMain(false) }
    @Test fun easyWorkRotatedAfterDurableClaimDoesNotOpenOldTicket() = runBlocking { rotatedAfterClaimRejectsMain(true) }

    private suspend fun openedThenCancelledAckDoesNotRequeue(failAck: Boolean) {
        val fixture = Fixture(true); val entered = CompletableDeferred<Unit>(); val claimed = CompletableDeferred<String>(); val release = fixture.release(); lateinit var owner: Job
        try {
            fixture.prepare({ _, claim, job -> owner = job; claimed.complete(claim) }, { _, _ -> entered.complete(Unit); release.await(); if (failAck) throw IOException("Owned pre-ACK failure fixture") })
            fixture.tap(); withTimeout(10_000) { entered.await() }
            assertEquals(1, fixture.opened)
            fixture.background(); release.complete(Unit); withTimeout(10_000) { owner.join() }
            fixture.waitState("opened")
            withContext(Dispatchers.Main.immediate) { assertEquals(failAck, fixture.coordinator.openReceiptPending) }
            fixture.foreground()
            fixture.waitState("opened") // Production drain is idle, with no second user tap.
            withContext(Dispatchers.Main.immediate) { assertEquals(1, fixture.opened) }
            assertEquals("fixture-thread", fixture.selected); assertEquals(0, fixture.commandCalls)
            if (failAck) assertEquals(claimed.await(), fixture.claim()) else assertNull(fixture.claim())
        } finally { fixture.close() }
    }
    @Test fun successfulEasyWorkOpenSurvivesOwnerCancellationWithAckFailure() = runBlocking { openedThenCancelledAckDoesNotRequeue(true) }
    @Test fun successfulEasyWorkOpenSurvivesOwnerCancellationWithAckCommit() = runBlocking { openedThenCancelledAckDoesNotRequeue(false) }

    @Test fun cancellationBeforeEasyWorkOpenRetainsOriginalClaimForForegroundResume() = runBlocking {
        val fixture = Fixture(true); val entered = CompletableDeferred<String>(); val release = fixture.release(); lateinit var owner: Job
        try {
            fixture.prepare({ _, claim, job -> owner = job; entered.complete(claim); release.await() })
            fixture.tap(); val original = withTimeout(10_000) { entered.await() }
            fixture.background(); withTimeout(10_000) { owner.join() }
            assertEquals(0, fixture.opened); assertEquals(original, fixture.claim())
            release.complete(Unit); fixture.foreground(); fixture.waitState("opened")
            assertEquals(1, fixture.opened); assertEquals(0, fixture.commandCalls)
            assertNull(fixture.claim())
        } finally { fixture.close() }
    }
}
