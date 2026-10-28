package com.agenthub.android

import android.os.Looper
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

/** Exact supplied coordinator/enrollment/owner sources; JVM Main/storage/archive-result seams, no native proof. */
private class EnrollmentMain : MainCoroutineDispatcher(), java.io.Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var thread: Thread? = null
    override val immediate get() = this
    override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread
    override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute { thread = Thread.currentThread(); Looper.setMainForTest(true); block.run() } }
    override fun close() { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
}
private class EnrollmentSystem : NativeChatNotificationSystem {
    var posts = 0; var preparations = 0
    override val sounds: NativeChatNotificationSoundLibrary get() = error("Sound UI not exercised")
    override fun languageChanged(selection: String) {}
    override fun permission() = NativeChatNotificationPermission(false, false, false)
    override fun settings(channel: String?): android.content.Intent = error("No system settings")
    override fun channels() = emptyList<NativeChatNotificationChannelState>()
    override fun soundProviderRegistered() = false
    override fun channelSoundTitle(channel: NativeChatNotificationChannelState) = ""
    override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? { preparations++; error("Closed enrollment cannot prepare a post") }
    override fun cancelOwned(target: NativeChatNoticeTarget?) {}
    override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>) {}
    override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) { posts++; error("No post permitted in this fixture") }
    override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean) = false
}
private class EnrollmentPort(override val storage: NativeSessionStorage, var connections: List<NativeChatNotificationSavedConnection>) : NativeChatNotificationSessionPort {
    var captures = 0; var readingCalls = 0
    override val reading = object : NativeChatNotificationReading {
        override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult { readingCalls++; error("No feed delivery permitted") }
        override fun mayPresent(event: NativeChatNotificationEvent) = false
    }
    override fun state() = NativeChatNotificationSessionState(true, true, "unchanged-target", false, true)
    override suspend fun savedConnections(): List<NativeChatNotificationSavedConnection> { captures++; return connections }
    override suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean) {}
    override fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean): Boolean = error("No navigation")
}
private class EnrollmentCase(val f: RecoveryNativeFixture, val source: NativeChatNotificationSavedConnection) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val port = EnrollmentPort(f.storage, listOf(source)); val system = EnrollmentSystem()
    var v1 = 0
    var queryGate: CountDownLatch? = null
    var queryEntered: CountDownLatch? = null
    val commands: NativeChatNotificationCommands = object : NativeChatNotificationCommands, NativeChatNotificationRecoveryCommands by f.commands {
        private fun forbidden(): Nothing { v1++; error("No v1 fallback/enrollment") }
        override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = forbidden()
        override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = forbidden()
        override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = forbidden()
        override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = forbidden()
        override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed = forbidden()
        override suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt? {
            queryGate?.let { gate -> withContext(Dispatchers.IO) { queryEntered?.countDown(); check(gate.await(5, TimeUnit.SECONDS)) } }
            return f.commands.recoveryQuery(snapshot, original)
        }
    }
    lateinit var coordinator: NativeChatNotificationCoordinator
    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        coordinator = NativeChatNotificationCoordinator(scope, port, system, commands, applicationId = "com.easyai.agenthub.android.acceptance", migrationEnrollmentCheck = true)
        coordinator.updateComputers(listOf(source.client)); coordinator.reconcile()
    }
    suspend fun finish() {
        queryGate?.countDown()
        if(::coordinator.isInitialized)withContext(Dispatchers.Main.immediate) { coordinator.stopAndJoinForMigration() }
        scope.cancel()
    }
}
private suspend fun eventually(message: String, check: suspend () -> Boolean) {
    try { withTimeout(5_000) { while(!check())delay(5) } } catch(failure: TimeoutCancellationException) { error(message) }
}
private fun source(f: RecoveryNativeFixture) = NativeChatNotificationSavedConnection.capture(
    MobileClient(f.target.hostId, "Fixture", TargetScope("codex", null, null, f.target.hostId, f.target.workspaceId, f.target.runtimeId), "http://127.0.0.1:8765"),
    "fixture-token-" + "x".repeat(32))
private suspend fun pendingRevoke(): EnrollmentCase {
    val f = RecoveryNativeFixture(NativeSessionStorage()); val saved = source(f)
    check(f.sync(snapshot = saved.snapshot)?.state == "foreground-synchronized")
    val off = f.notices.update { it.copy(enabled = false) }
    f.loseAck = true
    val action = NativeChatNotificationRecoveryController.targetActionId(UUID.randomUUID().toString(), f.target)
    check(f.controller().explicitDisable(saved.snapshot, off.revision, action) { true }.state == "mutation-unknown-original-query")
    NativeChatNotificationAuthorizations(f.storage).retain(saved)
    f.storage.write(NativeMigrationNotificationArchives.FIXTURE_BOUNDARY, "required")
    f.submitted.clear(); f.queried.clear(); f.queries.clear(); f.delivered.clear(); f.capabilityCalls = 0
    return EnrollmentCase(f, saved)
}
@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    val dispatcher = EnrollmentMain(); Dispatchers.setMain(dispatcher)
    try { runBlocking {
        var passed = 0
        suspend fun test(name: String, action: suspend () -> Unit) { action(); passed++; println("PASS $name") }
        suspend fun initialized(c: EnrollmentCase) = eventually("Enrollment did not initialize") { withContext(Dispatchers.Main.immediate) { c.coordinator.migrationInteractionReady && c.coordinator.migrationNeedsSetup } }
        test("closed enrollment exposes retained local unknown inventory without auto capability submit feed or capture") {
            val c = pendingRevoke()
            try {
                val before = c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD); c.start(); initialized(c)
                eventually("closed enrollment hid retained original inventory") { withContext(Dispatchers.Main.immediate) { c.f.target in c.coordinator.originalReports } }
                check(c.f.submitted.isEmpty() && c.f.capabilityCalls == 0 && c.f.queries.isEmpty() && c.port.captures == 0 && c.v1 == 0)
                check(c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before && c.system.posts == 0 && c.system.preparations == 0)
            } finally { c.finish() }
        }
        test("closed enrollment can query the exact retained revoke receipt without resubmitting or changing authority") {
            val c = pendingRevoke()
            try {
                val identity = c.f.storage.read(NativeChatNotificationIdentity.RECORD); val old = c.f.view().operations.single { it.state == "unknown" }.original
                c.start(); initialized(c); withContext(Dispatchers.Main.immediate) { c.coordinator.queryOriginals(c.f.target) }
                eventually("exact original revoke was not queried") { c.f.queried.any { it.operationId == old.operationId } }
                eventually("exact receipt did not settle") { c.f.view().operations.single { it.original.operationId == old.operationId }.state == "applied" }
                check(c.f.queried.all { it.encoded() == old.encoded() } && c.f.submitted.isEmpty() && c.f.capabilityCalls == 0 && c.f.queries.isEmpty())
                check(c.f.storage.read(NativeChatNotificationIdentity.RECORD) == identity && c.f.view().operations.size == 2 && c.system.posts == 0)
            } finally { c.finish() }
        }
        test("closed explicit off only queries retained unknown originals and never reserves a new revoke") {
            val c = pendingRevoke(); c.f.absent = true
            try {
                val before = c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD); val original = c.f.view().operations.single { it.state == "unknown" }.original
                c.start(); initialized(c)
                withContext(Dispatchers.Main.immediate) { c.coordinator.explicitDisable(null, c.f.notices.preferences().revision, UUID.randomUUID().toString()) }
                eventually("closed off callback lost original query") { c.f.queried.any { it.operationId == original.operationId } }
                check(c.f.submitted.isEmpty() && c.f.capabilityCalls == 0 && c.f.queries.isEmpty() && c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
            } finally { c.finish() }
        }
        test("closed original query cannot replace missing authority with rotated current credentials") {
            val c = pendingRevoke()
            try {
                c.f.storage.write(NativeChatNotificationAuthorizations.RECORD, "{\"version\":1,\"authorizations\":[]}")
                c.port.connections = listOf(NativeChatNotificationSavedConnection.capture(c.source.client, "r".repeat(64)))
                val before = c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD)
                c.start(); initialized(c); withContext(Dispatchers.Main.immediate) { c.coordinator.queryOriginals(c.f.target) }
                eventually("missing original authority was not reported") { withContext(Dispatchers.Main.immediate) { c.coordinator.originalReports[c.f.target]?.state == "original-credential-recovery-required" } }
                check(c.f.queried.isEmpty() && c.f.submitted.isEmpty() && c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
            } finally { c.finish() }
        }
        test("archive boundary alone never makes an identity or activates a connection before durable enable") {
            val f = RecoveryNativeFixture(NativeSessionStorage(), false); val c = EnrollmentCase(f, source(f))
            f.storage.write(NativeMigrationNotificationArchives.FIXTURE_BOUNDARY, "required")
            try {
                c.start(); initialized(c)
                withContext(Dispatchers.Main.immediate) {
                    c.coordinator.queryOriginals(f.target); c.coordinator.explicitDisable(null, f.notices.preferences().revision, UUID.randomUUID().toString())
                    c.coordinator.acceptGap(f.target, NativeChatRecoveryGapOffer(UUID.randomUUID().toString(), f.epoch, 0, 2, 2, f.notices.preferences().revision), UUID.randomUUID().toString())
                }
                eventually("local empty query did not finish") { withContext(Dispatchers.Main.immediate) { c.coordinator.querying.isEmpty() } }
                check(f.storage.read(NativeChatNotificationIdentity.RECORD) == null && f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == null && f.submitted.isEmpty() && f.capabilityCalls == 0)
                f.notices.update { it.copy(enabled = true) }
                withContext(Dispatchers.Main.immediate) { c.coordinator.preferencesSaved() }
                eventually("durable explicit enable did not release automatic enrollment") { f.submitted.size == 1 }
                check(f.submitted.single().kind == "register")
            } finally { c.finish() }
        }
        test("failed archive validation stays closed for original queries and only explicit successful retry releases reads") {
            val c = pendingRevoke(); c.f.absent = true
            try {
                val before = c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD)
                c.f.storage.write(NativeMigrationNotificationArchives.FIXTURE_BOUNDARY, "invalid")
                c.start()
                eventually("archive failure was not exposed") { withContext(Dispatchers.Main.immediate) { c.coordinator.migrationEnrollmentFailed } }
                withContext(Dispatchers.Main.immediate) { c.coordinator.queryOriginals(c.f.target); c.coordinator.explicitDisable(null, c.f.notices.preferences().revision, UUID.randomUUID().toString()) }
                check(c.f.queried.isEmpty() && c.f.submitted.isEmpty() && c.f.capabilityCalls == 0 && c.port.captures == 0)
                check(c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
                c.f.storage.write(NativeMigrationNotificationArchives.FIXTURE_BOUNDARY, "required")
                withContext(Dispatchers.Main.immediate) { c.coordinator.retryMigrationEnrollment() }
                initialized(c); withContext(Dispatchers.Main.immediate) { c.coordinator.queryOriginals(c.f.target) }
                eventually("successful explicit retry did not release an original read") { c.f.queried.isNotEmpty() }
                check(c.f.submitted.isEmpty() && c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before)
            } finally { c.finish() }
        }
        test("durable enable never skips a still-unknown original revoke or creates another operation") {
            val c = pendingRevoke(); c.f.absent = true
            try {
                val before = c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD)
                val original = c.f.view().operations.single { it.state == "unknown" }.original
                c.start(); initialized(c); c.f.notices.update { it.copy(enabled = true) }
                withContext(Dispatchers.Main.immediate) { c.coordinator.preferencesSaved() }
                eventually("enabled worker skipped the original revoke query") { c.f.queried.any { it.operationId == original.operationId } }
                eventually("unknown original revoke was not retained in the report") { withContext(Dispatchers.Main.immediate) { c.coordinator.reports[c.f.target]?.state == "mutation-unknown-original-query" } }
                check(c.f.submitted.isEmpty() && c.f.queries.isEmpty() && c.system.posts == 0)
                check(c.f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == before && c.f.view().operations.size == 2)
            } finally { c.finish() }
        }
        test("disposed coordinator joins held original IO while keeping its parent scope responsive") {
            val c = pendingRevoke(); c.queryGate = CountDownLatch(1); c.queryEntered = CountDownLatch(1)
            try {
                c.start(); initialized(c); withContext(Dispatchers.Main.immediate) { c.coordinator.queryOriginals(c.f.target) }
                withContext(Dispatchers.IO) { check(c.queryEntered!!.await(5, TimeUnit.SECONDS)) }
                val stopped = async(Dispatchers.Main.immediate) { c.coordinator.stopAndJoinForMigration() }
                withContext(Dispatchers.Main.immediate) { check(c.scope.isActive) }
                check(!stopped.isCompleted)
                c.queryGate!!.countDown(); withTimeout(5_000) { stopped.await() }
                withContext(Dispatchers.Main.immediate) { check(c.scope.isActive); check(c.coordinator.openState == null) }
                check(c.f.submitted.isEmpty())
            } finally { c.finish() }
        }
        println("$passed exact supplied coordinator/enrollment auxiliary vectors passed; archive-result/Main/storage/display-value seams, native/Host proof not run")
    } } catch(failure: Throwable) { failure.printStackTrace(); exitProcess(1) }
    finally { Dispatchers.resetMain(); dispatcher.close() }
    exitProcess(0)
}
