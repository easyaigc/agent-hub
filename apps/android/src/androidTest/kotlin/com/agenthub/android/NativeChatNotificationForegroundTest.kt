package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID

/** Real private Keystore/AtomicFile journal + synthetic command/reading ports. No Host/model/OS call. */
class NativeChatNotificationForegroundTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "desktop-runtime")
    private val now = 1_793_180_000_000L
    private lateinit var storage: NativeSessionStorage
    private lateinit var namespace: String
    private lateinit var notices: NativeChatNotificationStore
    private lateinit var journal: NativeChatNotificationForegroundStore
    private lateinit var commands: Commands
    private lateinit var delivery: Delivery
    private lateinit var scope: CoroutineScope
    private lateinit var monitor: NativeChatNotificationForegroundMonitor
    private val fence = NativeChatNotificationPresentationFence()
    private fun snapshot(token: String = "fixture-token-" + "x".repeat(32), epoch: String = "fixture-epoch", target: NativeChatNoticeTarget = this.target) =
        NativeChatNotificationSnapshot(MobileClient(target.hostId, "Fixture computer", TargetScope("codex", null, null, target.hostId, target.workspaceId, target.runtimeId), "https://fixture.example.invalid"), token, epoch)
    private fun event(id: Char = 'a') = NativeChatNotificationEvent(id.toString().repeat(64), target, NativeChatNoticeKind.APPROVAL, Instant.ofEpochMilli(now).toString(), "fixture-thread")
    private inner class Commands : NativeChatNotificationCommands {
        var registerCalls = 0; var preferenceCalls = 0; var unregisterCalls = 0; var statusCalls = 0; var feedCalls = 0
        var failRegister = false; var failPreferences = false; var failUnregister = false; var failRead = false
        var capturedRegistration: JSONObject? = null
        var statusValue = NativeChatNotificationStatus(target, "foreground-only", "disabled", null, null, false)
        var events = emptyList<NativeChatNotificationEvent>(); var active = emptySet<String>(); val devices = mutableSetOf<String>(); val cursors = mutableListOf<String>()
        var eventGate: CompletableDeferred<Unit>? = null; var eventEntered: CompletableDeferred<Unit>? = null
        override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus {
            registerCalls++; devices.add(identity.deviceId)
            capturedRegistration = NativeChatNotificationWire.registration(identity.deviceId, identity.publicKey, snapshot.target, preferences)
            statusValue = NativeChatNotificationStatus(snapshot.target, "foreground-only", "unavailable", preferences.revision, "手机尚未取得 APNs 令牌", false)
            if (failRegister) error("Fixture lost response")
            return statusValue
        }
        override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus {
            preferenceCalls++; devices.add(deviceId)
            statusValue = NativeChatNotificationStatus(snapshot.target, "foreground-only", if (preferences.enabled && snapshot.target.hostId !in preferences.excludedHosts) "unavailable" else "disabled", preferences.revision, null, false)
            if (failPreferences) error("Fixture lost response")
            return statusValue
        }
        override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus {
            unregisterCalls++; devices.add(deviceId); statusValue = NativeChatNotificationStatus(snapshot.target, "foreground-only", "disabled", statusValue.revision, null, false)
            if (failUnregister) error("Fixture lost response")
            return statusValue
        }
        override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus {
            statusCalls++; devices.add(deviceId); if (failRead) error("Fixture status offline"); return statusValue
        }
        override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed {
            feedCalls++; devices.add(deviceId); cursors.add(after); eventEntered?.complete(Unit); eventGate?.await()
            return NativeChatNotificationFeed(events, events.lastOrNull()?.id ?: after, active, statusValue)
        }
    }
    private inner class Delivery : NativeChatNotificationForegroundPort {
        val received = mutableListOf<NativeChatNotificationEvent>(); val activeSets = mutableListOf<Set<String>>(); var cancelled = 0
        var failOn: String? = null; var invalidateAfterReceive = false
        override suspend fun receive(event: NativeChatNotificationEvent, lease: NativeChatNoticeLease, computer: String, current: () -> Boolean): String {
            check(current() && event.target == lease.target); received.add(event)
            if (event.id == failOn) error("Fixture reading result lost")
            if (invalidateAfterReceive) fence.invalidate()
            return "suppressed"
        }
        override suspend fun reconcileActive(target: NativeChatNoticeTarget, activeIds: Set<String>, current: () -> Boolean) { if (current()) activeSets.add(activeIds) }
        override suspend fun cancel(target: NativeChatNoticeTarget) { cancelled++ }
    }
    @Before fun prepare() = runBlocking {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        namespace = "P9-foreground-${UUID.randomUUID()}"
        storage = NativeSessionStorage(context, namespace); notices = NativeChatNotificationStore(storage); journal = NativeChatNotificationForegroundStore(storage)
        withContext(Dispatchers.IO) { notices.update { it.copy(enabled = true) } }
        commands = Commands(); delivery = Delivery(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        monitor = NativeChatNotificationForegroundMonitor(scope, storage, notices, journal, commands, delivery) { now }
    }
    @After fun finish() { monitor.stop(); scope.cancel() }
    private suspend fun sync(value: NativeChatNotificationSnapshot = snapshot()) = monitor.synchronize(value, fence.capture())
    private suspend fun identity() = NativeChatNotificationIdentity.load(storage)
    private suspend fun saved() = withContext(Dispatchers.IO) { journal.view(identity(), target) }
    @Test fun defaultDisabledNeverRegistersOrCreatesIdentity() = runBlocking {
        withContext(Dispatchers.IO) { notices.update { it.copy(enabled = false) } }
        assertEquals("disabled", sync().state); assertEquals(0, commands.registerCalls); assertEquals(0, commands.statusCalls)
        assertNull(storage.read(NativeChatNotificationIdentity.RECORD)); assertNull(storage.read(NativeChatNotificationForegroundStore.RECORD))
    }
    @Test fun installationIdentityIsRealStableAndIndependentlyEncrypted() = runBlocking {
        val a = identity(); val b = NativeChatNotificationIdentity.load(NativeSessionStorage(context, namespace))
        assertEquals(a.deviceId, b.deviceId); assertEquals(a.publicKey, b.publicKey); assertEquals(32, NativeChatNotificationWire.key(a.publicKey).size)
        assertFalse(a.toString().contains(a.publicKey)); assertNull(storage.read("easywork-device-identity"))
    }
    @Test fun corruptIdentityIsPreservedWithoutRegeneration() = runBlocking {
        val bad = "{\"version\":99}"; storage.write(NativeChatNotificationIdentity.RECORD, bad)
        assertTrue(runCatching { identity() }.isFailure); assertEquals(bad, storage.read(NativeChatNotificationIdentity.RECORD))
        assertEquals("read-unavailable", sync().state); assertEquals(0, commands.registerCalls)
    }
    @Test fun missingIdentityWithPriorForegroundJournalNeverMintsNewDevice() = runBlocking {
        sync(); val raw = storage.read(NativeChatNotificationForegroundStore.RECORD); storage.remove(NativeChatNotificationIdentity.RECORD)
        assertEquals("read-unavailable", sync().state); assertEquals(1, commands.registerCalls)
        assertNull(storage.read(NativeChatNotificationIdentity.RECORD)); assertEquals(raw, storage.read(NativeChatNotificationForegroundStore.RECORD))
    }
    @Test fun missingBoundJournalCannotReplayExistingInstallationRegistration() = runBlocking {
        sync(); storage.remove(NativeChatNotificationForegroundStore.RECORD)
        assertEquals("read-unavailable", sync().state); assertEquals(1, commands.registerCalls)
        assertNull(storage.read(NativeChatNotificationForegroundStore.RECORD))
    }
    @Test fun nilTokenRegistrationDeliversOriginalEventsAndCommitsOnlyFinalOutcomes() = runBlocking {
        commands.events = listOf(event(), event('b')); commands.active = setOf(event().id)
        assertEquals("foreground-synchronized", sync().state)
        val state = saved(); assertEquals(event('b').id, state.cursor); assertNull(state.batch)
        assertTrue(state.outcomes.all { it.terminal && it.state == "suppressed" }); assertEquals(commands.events, delivery.received)
        assertFalse(commands.capturedRegistration!!.has("deviceToken")); assertFalse(commands.capturedRegistration!!.has("environment"))
        assertEquals(1, commands.devices.size); assertEquals(listOf(commands.active), delivery.activeSets)
    }
    @Test fun lostRegisterResponseUsesSameDeviceStatusWithoutReplayEvenWhenRevisionMatches() = runBlocking {
        commands.failRegister = true; val first = sync(); assertEquals("mutation-unknown-status-only", first.state)
        repeat(3) { assertEquals(first.operationId, sync().operationId) }
        assertEquals(1, commands.registerCalls); assertEquals(0, commands.preferenceCalls); assertEquals(0, commands.feedCalls)
        assertEquals(3, commands.statusCalls); assertEquals(1, commands.devices.size); assertEquals(first.operationId, saved().unknown!!.id)
    }
    @Test fun unknownPreferencesNeverRegisterOrReissueAfterRestart() = runBlocking {
        sync(); withContext(Dispatchers.IO) { notices.update { it.copy(showThreadTitle = false) } }; commands.failPreferences = true
        val first = sync(); assertEquals("mutation-unknown-status-only", first.state)
        val cold = NativeChatNotificationForegroundMonitor(scope, storage, notices, NativeChatNotificationForegroundStore(storage), commands, delivery) { now }
        assertEquals(first.operationId, cold.synchronize(snapshot(), fence.capture()).operationId)
        assertEquals(1, commands.registerCalls); assertEquals(1, commands.preferenceCalls); assertNotNull(saved().unknown)
    }
    @Test fun offlineUnknownStatusRemainsUnknownAndDoesNotSendAnotherMutation() = runBlocking {
        commands.failRegister = true; sync(); commands.failRead = true
        assertEquals("mutation-unknown-status-unavailable", sync().state); assertEquals(1, commands.registerCalls)
    }
    @Test fun unknownRegistrationCannotBeUnregisteredAsAbsenceProof() = runBlocking {
        commands.failRegister = true; sync()
        assertEquals("mutation-unknown-status-only", monitor.revoke(snapshot(), fence.capture()).state)
        assertEquals(0, commands.unregisterCalls); assertNotNull(saved().unknown)
    }
    @Test fun unknownMutationWithNewCredentialRequiresOriginalSnapshot() = runBlocking {
        commands.failRegister = true; sync(); val statuses = commands.statusCalls
        assertEquals("original-credential-recovery-required", sync(snapshot(epoch = "rotated")).state)
        assertEquals(statuses, commands.statusCalls); assertEquals(1, commands.registerCalls)
    }
    @Test fun confirmedCredentialRotationRequiresRevocationBeforeNewRegister() = runBlocking {
        sync(); assertEquals("original-credential-revocation-required", sync(snapshot(epoch = "rotated")).state)
        assertEquals(1, commands.registerCalls); assertEquals(0, commands.preferenceCalls)
    }
    @Test fun successfulUnregisterIsSingleAttemptAndRetainsOriginalOperation() = runBlocking {
        sync(); val result = monitor.revoke(snapshot(), fence.capture()); assertEquals("disabled", result.state)
        assertEquals("disabled", monitor.revoke(snapshot(), fence.capture()).state)
        val operation = saved().mutations.single { it.id == result.operationId }; assertEquals("confirmed", operation.state); assertNull(operation.preferences)
        assertEquals(1, commands.unregisterCalls)
    }
    @Test fun eventFailureKeepsCursorAndEveryAttemptHasDurableNoReplayResult() = runBlocking {
        commands.events = listOf(event(), event('b')); delivery.failOn = event('b').id
        assertEquals("read-unavailable", sync().state); assertEquals("", saved().cursor)
        assertEquals(listOf("suppressed", "unknown-interrupted-no-replay"), saved().outcomes.map { it.state })
        val recovered = sync(); assertEquals("foreground-outcome-unknown", recovered.state); assertEquals(1, recovered.unconfirmedEvents); assertEquals(event('b').id, saved().cursor)
        assertEquals(2, delivery.received.size); assertEquals(1, commands.feedCalls)
    }
    @Test fun repeatedOriginalEventsFromUnknownCursorAreNotDeliveredAgain() = runBlocking {
        commands.events = listOf(event()); sync(); sync()
        assertEquals(1, delivery.received.size); assertEquals(2, commands.feedCalls); assertEquals(1, saved().outcomes.size)
    }
    @Test fun reusedOriginalIdWithChangedContentDoesNotAdvanceCursor() = runBlocking {
        commands.events = listOf(event()); sync(); val raw = storage.read(NativeChatNotificationForegroundStore.RECORD)
        commands.events = listOf(event().copy(taskSummary = "Changed original"))
        assertEquals("read-unavailable", sync().state); assertEquals(raw, storage.read(NativeChatNotificationForegroundStore.RECORD)); assertEquals(1, delivery.received.size)
    }
    @Test fun lateFeedAfterFenceChangeCannotClaimOrAdvance() = runBlocking {
        commands.events = listOf(event()); commands.eventGate = CompletableDeferred(); commands.eventEntered = CompletableDeferred()
        val original = fence.capture(); val pending = async { monitor.synchronize(snapshot(), original) }
        commands.eventEntered!!.await(); fence.invalidate(); commands.eventGate!!.complete(Unit)
        assertEquals("stale-target-or-generation", pending.await().state); assertEquals("", saved().cursor)
        assertTrue(saved().outcomes.isEmpty()); assertTrue(delivery.received.isEmpty())
    }
    @Test fun lateReadingReturnStaysQueryableWithoutAdvancingOldCursor() = runBlocking {
        commands.events = listOf(event()); delivery.invalidateAfterReceive = true
        assertEquals("stale-target-or-generation", sync().state); assertEquals("", saved().cursor)
        assertEquals("suppressed", saved().outcomes.single().state)
        delivery.invalidateAfterReceive = false; sync(); assertEquals(1, delivery.received.size); assertEquals(event().id, saved().cursor)
    }
    @Test fun corruptFutureJournalIsPreservedAndNoHostRequestsRun() = runBlocking {
        identity(); val bad = "{\"version\":99}"; storage.write(NativeChatNotificationForegroundStore.RECORD, bad)
        assertEquals("read-unavailable", sync().state); assertEquals(bad, storage.read(NativeChatNotificationForegroundStore.RECORD))
        assertEquals(0, commands.registerCalls); assertEquals(0, commands.statusCalls)
    }
    @Test fun journalRejectsMainThreadStorageAndFutureArchive() = runBlocking {
        var failed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { failed = runCatching { journal.exists() }.isFailure }
        assertTrue(failed); assertTrue(runCatching { NativeChatNotificationForegroundStore.validateEncoded("{\"version\":7}") }.isFailure)
    }
    @Test fun interruptedStartedEventBecomesUnknownWhileUnstartedEventCanProceed() = runBlocking {
        sync(); val identity = identity(); val preferences = withContext(Dispatchers.IO) { notices.preferences() }
        withContext(Dispatchers.IO) {
            val batch = journal.beginBatch(identity, snapshot(), preferences, NativeChatNotificationFeed(listOf(event(), event('b')), event('b').id, emptySet(), commands.statusValue), now)
            assertNotNull(journal.startEvent(identity, target, batch, event().id, now))
        }
        assertEquals("foreground-outcome-unknown", sync().state)
        assertEquals(listOf(event('b')), delivery.received)
        assertEquals("unknown-interrupted-no-replay", saved().outcomes.single { it.event.id == event().id }.state)
        assertEquals(event('b').id, saved().cursor)
    }
    @Test fun oversizedBacklogKeepsPreviousCursorAndBytes() = runBlocking {
        sync(); val raw = storage.read(NativeChatNotificationForegroundStore.RECORD)
        commands.events = (1..2049).map { event().copy(id = "%064x".format(it)) }
        assertEquals("read-unavailable", sync().state); assertEquals(raw, storage.read(NativeChatNotificationForegroundStore.RECORD))
        assertTrue(delivery.received.isEmpty()); assertEquals("", saved().cursor)
    }
    @Test fun sharedStorePreferenceChangeImmediatelyInvalidatesMainPresentationToken() = runBlocking {
        val original = notices.presentationLease(); assertTrue(original())
        withContext(Dispatchers.IO) { NativeChatNotificationStore(NativeSessionStorage(context, namespace)).update { it.copy(sound = "none") } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertFalse(original()) }
        val next = notices.presentationLease(); assertTrue(next()); notices.invalidatePresentation(); assertFalse(next())
    }
    @Test fun parentAtomicMayPresentGuardRemainsInsideClaim() = runBlocking {
        withContext(Dispatchers.IO) {
            val lease = notices.bind(target, "epoch"); val before = storage.read(NativeChatNotificationStore.RECORD)
            assertNull(notices.claim(event(), lease, now) { false }); assertEquals(before, storage.read(NativeChatNotificationStore.RECORD))
            assertNotNull(notices.claim(event(), lease, now) { true })
        }
    }
    @Test fun anotherComputerBindingDoesNotInvalidateThisExactTargetPresentation() = runBlocking {
        withContext(Dispatchers.IO) { notices.bind(target, "epoch") }
        val exact = notices.presentationLease(target); val configuration = notices.configurationLease()
        withContext(Dispatchers.IO) { notices.bind(target.copy(hostId = "other-host"), "other-epoch") }
        assertTrue(exact()); assertTrue(configuration())
        withContext(Dispatchers.IO) { notices.revoke(target) }
        assertFalse(exact()); assertTrue(configuration())
    }
    @Test fun preferenceChangeWithoutNetworkReturnCannotAdvanceOldFeed() = runBlocking {
        sync(); commands.events = listOf(event()); commands.eventGate = CompletableDeferred(); commands.eventEntered = CompletableDeferred()
        val pending = async { sync() }; commands.eventEntered!!.await()
        withContext(Dispatchers.IO) { notices.update { it.copy(showThreadTitle = false) } }
        commands.eventGate!!.complete(Unit)
        assertEquals("stale-target-or-generation", pending.await().state)
        assertEquals("", saved().cursor); assertTrue(saved().outcomes.isEmpty()); assertTrue(delivery.received.isEmpty())
    }
}
