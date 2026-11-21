package com.agenthub.android

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Component only: real coordinator/monitor/storage, synthetic Commands/OS, no Activity/HTTP/default store. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationConnectionWorkerIsolationTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireOwnedRun(): Unit {
        val run = InstrumentationRegistry.getArguments().getString("notificationInventoryRun")
        Assume.assumeTrue("Fresh parent-owned run required; skip is not acceptance", run != null)
        check(UUID.fromString(run).toString() == run)
        check(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance")
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey)
        check(!NativeChatNotificationColdRouterFiles.hasReadableMarker(context.noBackupFilesDir))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity })
        }
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    @Suppress("UNCHECKED_CAST") private fun workers(c: NativeChatNotificationCoordinator) = field(c, "workers") as Map<NativeChatNoticeTarget, Any>
    private fun job(worker: Any) = checkNotNull(field(worker, "job") as Job?)

    @Test fun unusableAStopsOnlyAWhileOriginalBRegistrationCompletes(): Unit = runBlocking {
        val run = checkNotNull(InstrumentationRegistry.getArguments().getString("notificationInventoryRun"))
        val owned = NativeChatNotificationAcceptanceScope.create(context, run)
        val root = File(context.noBackupFilesDir.canonicalFile, "P9-native-system-$run")
        val folder = File(context.filesDir, "acceptance/notification-inventory/$run")
        withContext(Dispatchers.IO) {
            NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, run)
            check(folder.canonicalFile == File(context.filesDir.canonicalFile, "acceptance/notification-inventory/$run"))
            check(folder.mkdirs()); android.system.Os.chmod(folder.absolutePath, 448)
            check(File(folder, "method.once").createNewFile()); android.system.Os.chmod(File(folder, "method.once").absolutePath, 384)
        }
        val bRelease = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Job>(); val aFeed = CompletableDeferred<Unit>(); val bFeed = CompletableDeferred<Unit>()
        val capturedInvalid = CompletableDeferred<Unit>(); val errors = CopyOnWriteArrayList<Throwable>()
        val owner = SupervisorJob(); val tasks = CoroutineScope(owner + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> errors.add(e) })
        lateinit var storage: NativeSessionStorage
        lateinit var coordinator: NativeChatNotificationCoordinator
        var failure: Throwable? = null; var bodyPassed = false
        var storageCreated = false; var coordinatorCreated = false
        var coordinatorJoined = false; var scopeJoined = false; var writerClosed = false; var removed = false
        try {
            storage = withContext(Dispatchers.IO) {
                NativeSessionStorage(context, "P9-native-system-$run", existingKeyOnly = true).also { owned.requireStorage(context, it) }
            }
            storageCreated = true
            val ownedStorage = storage
            fun client(id: String) = MobileClient(id, "Owned worker $id", TargetScope("codex", null, null, id, "inventory-workspace-$id", "desktop-runtime"), "https://inventory.example.invalid")
            val clients = listOf(client("worker-a"), client("worker-b"))
            val a = NativeChatNoticeTarget.from(clients[0].scope!!); val b = NativeChatNoticeTarget.from(clients[1].scope!!)
            val tokens = mutableMapOf(a.hostId to "synthetic-a-" + "a".repeat(48), b.hostId to "synthetic-b-" + "b".repeat(48))
            val expected = withContext(Dispatchers.IO) { NativeChatNotificationSavedConnection.inventory(clients, tokens::getValue).connections.associateBy { it.target } }
            val prefs = withContext(Dispatchers.IO) { NativeChatNotificationStore(storage).update { it.copy(enabled = true) } }
            val registrations = mutableMapOf<NativeChatNoticeTarget, Int>(); var bCancelled = false; var bCompleted = 0
            val cancelled = mutableListOf<NativeChatNoticeTarget?>()
            fun verify(s: NativeChatNotificationSnapshot) { check(Looper.myLooper() == Looper.getMainLooper()); check(s.authorizationFingerprint == expected.getValue(s.target).snapshot.authorizationFingerprint) }
            fun readyStatus(s: NativeChatNotificationSnapshot) = NativeChatNotificationStatus(s.target, "foreground-only", "ready", prefs.revision, null, false)
            val commands = object : NativeChatNotificationCommands {
                override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus {
                    verify(snapshot); check(preferences == prefs)
                    registrations[snapshot.target] = (registrations[snapshot.target] ?: 0) + 1
                    if (snapshot.target == b) {
                        bEntered.complete(checkNotNull(currentCoroutineContext()[Job]))
                        try { bRelease.await(); currentCoroutineContext().ensureActive(); bCompleted++ }
                        catch (cancel: CancellationException) { bCancelled = true; throw cancel }
                    }
                    return readyStatus(snapshot)
                }
                override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = error("No preference mutation")
                override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = error("No revoke")
                override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus { verify(snapshot); return readyStatus(snapshot) }
                override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed {
                    verify(snapshot); if (snapshot.target == a) aFeed.complete(Unit) else bFeed.complete(Unit)
                    awaitCancellation() // no fixture event delivery or polling race
                }
            }
            val port = object : NativeChatNotificationSessionPort {
                override val storage = ownedStorage
                override val reading = object : NativeChatNotificationReading {
                    override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult = error("No events")
                    override fun mayPresent(event: NativeChatNotificationEvent) = false
                }
                override fun state() = NativeChatNotificationSessionState(true, true, "unchanged-inventory-owner", false, true)
                override var unavailableConnectionTargets: Set<NativeChatNoticeTarget> = emptySet(); private set
                override suspend fun savedConnections(): List<NativeChatNotificationSavedConnection> {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    val frozen = tokens.toMap()
                    val inventory = withContext(Dispatchers.IO) { storage.snapshotTransaction { NativeChatNotificationSavedConnection.inventory(clients, frozen::getValue) } }
                    unavailableConnectionTargets = inventory.unavailableTargets
                    if (a in inventory.unavailableTargets) capturedInvalid.complete(Unit)
                    return inventory.connections
                }
                override suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean): Unit = error("No completed feed")
                override fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean): Boolean = error("No navigation")
            }
            val system = object : NativeChatNotificationSystem {
                override val sounds: NativeChatNotificationSoundLibrary get() = error("No sound")
                override fun languageChanged(selection: String): Unit = error("No language change")
                override fun permission() = NativeChatNotificationPermission(true, true, true)
                override fun settings(channel: String?): android.content.Intent = error("No settings")
                override fun channels() = emptyList<NativeChatNotificationChannelState>()
                override fun soundProviderRegistered() = false
                override fun channelSoundTitle(channel: NativeChatNotificationChannelState): String = error("No channel")
                override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? = error("No OS preparation")
                override fun cancelOwned(target: NativeChatNoticeTarget?) { cancelled.add(target) }
                override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>): Unit = error("No events")
                override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String): Unit = error("No OS post")
                override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean = error("No test notification")
            }
            withContext(Dispatchers.Main.immediate) {
                coordinator = NativeChatNotificationCoordinator(tasks, port, system, commands, pollMillis = 60_000, applicationId = context.packageName)
                coordinatorCreated = true
                coordinator.updateComputers(clients); coordinator.reconcile()
            }
            val bOwner = withTimeout(10_000) { bEntered.await() }; withTimeout(10_000) { aFeed.await() }
            val (aWorker, bWorker) = withContext(Dispatchers.Main.immediate) {
                check(workers(coordinator).keys == setOf(a, b)); check(job(workers(coordinator).getValue(b)) === bOwner)
                workers(coordinator).getValue(a) to workers(coordinator).getValue(b)
            }
            val originals = withContext(Dispatchers.IO) {
                val journal = NativeChatNotificationForegroundStore(storage)
                val identity = NativeChatNotificationIdentity.existing(storage)
                val pending = checkNotNull(journal.view(identity, b).unknown)
                check(pending.authorization == expected.getValue(b).snapshot.authorizationFingerprint)
                Triple(checkNotNull(storage.read(NativeChatNotificationAuthorizations.RECORD)), checkNotNull(storage.read(NativeChatNotificationForegroundStore.RECORD)), pending.id)
            }
            withContext(Dispatchers.Main.immediate) { tokens[a.hostId] = ""; coordinator.reconcile() }
            withTimeout(10_000) { capturedInvalid.await() }; withTimeout(10_000) { job(aWorker).join() }
            withContext(Dispatchers.Main.immediate) {
                check(workers(coordinator).keys == setOf(b) && workers(coordinator).getValue(b) === bWorker)
                check(job(bWorker) === bOwner && bOwner.isActive && !bOwner.isCancelled && !bCancelled)
                check(job(aWorker).isCancelled && coordinator.reports[a]?.state == "original-credential-recovery-required")
                check(cancelled == listOf(a) && registrations == mapOf(a to 1, b to 1) && bCompleted == 0)
            }
            withContext(Dispatchers.IO) {
                check(storage.read(NativeChatNotificationAuthorizations.RECORD) == originals.first)
                check(storage.read(NativeChatNotificationForegroundStore.RECORD) == originals.second)
                check(NativeChatNotificationAuthorizations(storage).all().associate { it.target to it.snapshot.authorizationFingerprint } == expected.mapValues { it.value.snapshot.authorizationFingerprint })
            }
            bRelease.complete(Unit); withTimeout(10_000) { bFeed.await() }
            withContext(Dispatchers.Main.immediate) {
                check(job(bWorker) === bOwner && bOwner.isActive && !bCancelled && bCompleted == 1)
                check(registrations == mapOf(a to 1, b to 1) && cancelled == listOf(a))
            }
            withContext(Dispatchers.IO) {
                val identity = NativeChatNotificationIdentity.existing(storage); val journal = NativeChatNotificationForegroundStore(storage)
                val mutation = journal.view(identity, b).mutations.single()
                check(mutation.id == originals.third && mutation.state == "confirmed" && mutation.authorization == expected.getValue(b).snapshot.authorizationFingerprint)
                check(journal.view(identity, a).mutations.single().state == "confirmed")
                check(storage.read(NativeChatNotificationAuthorizations.RECORD) == originals.first)
            }
            check(errors.isEmpty()); bodyPassed = true
        } catch (error: Throwable) { failure = error }
        finally {
            withContext(NonCancellable) {
                fun failed(e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
                try { withTimeout(10_000) { withContext(Dispatchers.Main.immediate) { if (coordinatorCreated) coordinator.stopAndJoinForMigration() } }; coordinatorJoined = true } catch (e: Throwable) { failed(e) }
                bRelease.complete(Unit)
                try { withTimeout(10_000) { owner.cancelAndJoin(); check(errors.isEmpty()) }; scopeJoined = true } catch (e: Throwable) { failed(e) }
                try { withContext(Dispatchers.IO) {
                    if (storageCreated && coordinatorJoined && scopeJoined) {
                        val writer = field(storage, "writes") as ExecutorService
                        try { withTimeout(10_000) { storage.awaitQueuedWrites() } } finally {
                            storage.retireWriter(); check(writer.awaitTermination(10, TimeUnit.SECONDS)); writerClosed = writer.isTerminated
                        }
                    }
                } } catch (e: Throwable) { failed(e) }
                try { withContext(Dispatchers.IO) {
                    if (bodyPassed && failure == null && coordinatorJoined && scopeJoined && writerClosed) {
                        check(root.canonicalFile == root.absoluteFile && root.parentFile.canonicalFile == context.noBackupFilesDir.canonicalFile)
                        check(root.walkTopDown().all { !Files.isSymbolicLink(it.toPath()) })
                        check(root.deleteRecursively() && !root.exists()); removed = true
                    }
                } } catch (e: Throwable) { failed(e) }
                try { withContext(Dispatchers.IO) {
                    val result = File(folder, "result.json"); check(result.createNewFile()); android.system.Os.chmod(result.absolutePath, 384)
                    result.writeText(JSONObject().put("run", run).put("bodyPassed", bodyPassed).put("coordinatorJoined", coordinatorJoined).put("scopeJoined", scopeJoined)
                        .put("writerClosed", writerClosed).put("ownedFixtureRemoved", removed).put("failedOrUnknown", failure != null || !removed)
                        .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL).put("boundary", "Synthetic component-only; no Activity/HTTP/provider/default store/shared alias mutation").toString())
                } } catch (e: Throwable) { failed(e) }
            }
        }
        failure?.let { throw it }; check(bodyPassed && removed); Unit
    }
}
