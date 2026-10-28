package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.agenthub.core.TargetScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** P14-combined Android test source. Real encrypted storage/enrollment/archive graph; synthetic backend/OS.
 * No Activity, Host transport, enrollment enable, grant/revoke, default-store access or operation replay.
 * Parent installs this source into androidTest only after applying the documented P14 prerequisites. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationEnrollmentOriginalsTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun arg(name: String) = checkNotNull(InstrumentationRegistry.getArguments().getString(name)).also { require(UUID.fromString(it).toString() == it) }
    @Before fun explicitIndependentAttempt() {
        Assume.assumeTrue("Explicit fresh lease/run required; skip is not acceptance", listOf("p9EnrollmentLease", "p9EnrollmentRun").all { InstrumentationRegistry.getArguments().getString(it) != null })
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance")
        require(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey) { "Existing installation AES key required" }
    }
    @Test fun closedMigrationGateQueriesOriginalUnknownRevokeWithRetainedAuthority() = execute("retained-revoke")
    @Test fun closedOffCallbacksQueryUnknownRegistrationWithoutCreatingRevoke() = execute("off-query-only")
    @Test fun closedQueryCannotBorrowRotatedOrArchivedAuthorityWhenOriginalMissing() = execute("missing-authority")
    private suspend fun main(action: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) { action() }
    private suspend fun io(action: suspend () -> Unit) = withContext(Dispatchers.IO) { action() }
    private suspend fun until(action: suspend () -> Boolean) { withTimeout(15_000) { while(!action())delay(10) } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun files(run: String): Map<String,String> {
        val root = File(context.noBackupFilesDir, "P9-native-system-$run")
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        val rows = checkNotNull(root.listFiles()); require(rows.size <= 32 && rows.sumOf { it.length() } <= 2_097_152)
        require(rows.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[a-f0-9]{64}\\.bin")) }) { "Unknown/partial fixture material preserved" }
        return rows.associate { it.name to hash(it.readBytes()) }
    }
    private fun source(f: RecoveryNativeFixture, token: String): NativeChatNotificationSavedConnection = NativeChatNotificationSavedConnection.capture(
        MobileClient(f.target.hostId, "P9 enrollment fixture", TargetScope("codex", null, null, f.target.hostId, f.target.workspaceId, f.target.runtimeId), "https://p9-enrollment.example.invalid"), token)
    private class Boundary {
        val forbidden = AtomicInteger(); val queries = CopyOnWriteArrayList<NativeChatRecoveryIntent>()
        val queryEntries = AtomicInteger()
        @Volatile var lastQueryState = "not-entered"; private set
        lateinit var expected: NativeChatRecoveryIntent
        lateinit var authority: String
        var receipt: NativeChatRecoveryReceipt? = null
        private fun forbidden(): Nothing { forbidden.incrementAndGet(); error("Closed enrollment permits only exact original receipt query") }
        val commands: NativeChatNotificationCommands = object : NativeChatNotificationCommands, NativeChatNotificationRecoveryCommands {
            override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = forbidden()
            override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = forbidden()
            override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = forbidden()
            override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = forbidden()
            override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed = forbidden()
            override suspend fun recoveryCapabilities(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatRecoveryCapabilities? = forbidden()
            override suspend fun recoverySubmit(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt = forbidden()
            override suspend fun recoveryFeed(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent, query: NativeChatRecoveryQuery, reset: NativeChatRecoverySnapshot?): NativeChatRecoveryFeed = forbidden()
            // Like the shipping transport, this suspend boundary owns its IO switch.
            override suspend fun recoveryQuery(snapshot: NativeChatNotificationSnapshot, original: NativeChatRecoveryIntent): NativeChatRecoveryReceipt? = withContext(Dispatchers.IO) {
                queryEntries.incrementAndGet(); lastQueryState = "entered-io"
                check(Looper.myLooper() != Looper.getMainLooper()) { lastQueryState = "rejected-main-thread"; "Fixture query must run off Main" }
                check(snapshot.target == expected.target) { lastQueryState = "rejected-original-target"; "Original target required" }
                check(snapshot.authorizationFingerprint == authority) { lastQueryState = "rejected-original-authority"; "Original authority required" }
                check(original.encoded() == expected.encoded()) { lastQueryState = "rejected-original-intent"; "Original intent required" }
                queries.add(original); lastQueryState = if(receipt == null) "original-receipt-absent" else "writer-receipt-returned"
                receipt // exact writer-recorded response, or absent; never reconstructed from local state
            }
        }
    }
    private class SystemPort : NativeChatNotificationSystem {
        val forbidden = AtomicInteger()
        private fun forbidden(): Nothing { forbidden.incrementAndGet(); error("No OS/sound boundary is allowed") }
        override val sounds: NativeChatNotificationSoundLibrary get() = forbidden()
        override fun languageChanged(selection: String) = forbidden()
        override fun permission() = NativeChatNotificationPermission(false, false, false)
        override fun settings(channel: String?): Intent = forbidden()
        override fun channels() = emptyList<NativeChatNotificationChannelState>()
        override fun soundProviderRegistered() = false
        override fun channelSoundTitle(channel: NativeChatNotificationChannelState): String = forbidden()
        override suspend fun prepareAsync(kind: NativeChatNoticeKind, sound: String, badge: Boolean, current: () -> Boolean): String? = forbidden()
        override fun cancelOwned(target: NativeChatNoticeTarget?) = forbidden()
        override fun cancelEvents(target: NativeChatNoticeTarget, ids: Set<String>) = forbidden()
        override fun post(receipt: NativeChatNoticeReceipt, preferences: NativeChatNotificationPreferences, computer: String, unread: Int, channel: String) = forbidden()
        override suspend fun testAsync(preferences: NativeChatNotificationPreferences, current: () -> Boolean): Boolean = forbidden()
    }
    private class Port(override val storage: NativeSessionStorage, val rotated: NativeChatNotificationSavedConnection) : NativeChatNotificationSessionPort {
        val captures = AtomicInteger(); val forbidden = AtomicInteger()
        private fun forbidden(): Nothing { forbidden.incrementAndGet(); error("No feed, reading or navigation allowed") }
        override val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult = forbidden()
            override fun mayPresent(event: NativeChatNotificationEvent): Boolean = forbidden()
        }
        override fun state() = NativeChatNotificationSessionState(true, true, "fixed-original-owner", false, true)
        override suspend fun savedConnections(): List<NativeChatNotificationSavedConnection> { check(Looper.myLooper() == Looper.getMainLooper()); captures.incrementAndGet(); return listOf(rotated) }
        override suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean) = forbidden()
        override fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean): Boolean = forbidden()
    }
    /** Fixed field assertions catch constructor parameter capture; no method-name or reflection dispatcher. */
    private fun scopeOwnership(coordinator: NativeChatNotificationCoordinator, parent: CoroutineScope) {
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        val job = field(coordinator, "ownerJob") as Job
        val own = field(coordinator, "scope") as CoroutineScope
        val enrollment = checkNotNull(field(coordinator, "migrationEnrollment"))
        check(job === own.coroutineContext[Job] && job !== parent.coroutineContext[Job])
        check((field(enrollment, "scope") as CoroutineScope).coroutineContext[Job] === job)
        check(parent.coroutineContext[Job]!!.children.any { it === job })
    }
    private fun execute(case: String): Unit = runBlocking {
        val lease = arg("p9EnrollmentLease"); val run = arg("p9EnrollmentRun")
        val archiveRun = UUID.nameUUIDFromBytes("p9-enrollment-archive:$run".toByteArray()).toString()
        // Refuse either old namespace BEFORE constructing storage or registering cleanup ownership.
        io { NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, run); NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, archiveRun) }
        val storage = NativeChatNotificationAcceptanceScope.create(context, run).storage(context)
        val archiveStorage = NativeChatNotificationAcceptanceScope.create(context, archiveRun).storage(context)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var coordinator: NativeChatNotificationCoordinator? = null
        val boundary = Boundary(); val system = SystemPort(); var port: Port? = null
        var failure: Throwable? = null; var stopped = false; var coordinatorState = JSONObject()
        var before = emptyMap<String,String>(); var after = emptyMap<String,String>(); var archiveBefore = emptyMap<String,String>(); var archiveAfter = emptyMap<String,String>()
        try {
            lateinit var f: RecoveryNativeFixture; lateinit var saved: NativeChatNotificationSavedConnection
            lateinit var originals: List<String>; var offRevision = ""
            io {
                f = RecoveryNativeFixture(storage); saved = source(f, "original-fixture-" + "a".repeat(48))
                if(case == "retained-revoke") {
                    check(f.sync(snapshot = saved.snapshot)?.state == "foreground-synchronized")
                    val off = f.notices.update { it.copy(enabled = false) }; f.loseAck = true
                    val action = NativeChatNotificationRecoveryController.targetActionId(UUID.nameUUIDFromBytes("revoke:$run".toByteArray()).toString(), f.target)
                    check(f.controller().explicitDisable(saved.snapshot, off.revision, action) { true }.state == "mutation-unknown-original-query")
                } else {
                    f.loseAck = true; check(f.sync(snapshot = saved.snapshot)?.state == "mutation-unknown-original-query")
                    f.notices.update { it.copy(enabled = false) }
                }
                boundary.expected = f.view().operations.single { it.state == "unknown" }.original
                boundary.authority = saved.snapshot.authorizationFingerprint
                if(case == "retained-revoke") boundary.receipt = checkNotNull(f.receipts[boundary.expected.operationId])
                if(case != "missing-authority") NativeChatNotificationAuthorizations(storage).retain(saved)
                originals = f.view().operations.map { it.original.encoded() }; offRevision = f.notices.preferences().revision
                // A second installation's real native writer produces a valid graph with different authority.
                // P7's actual projection removes its private identity and renames its originals into inert archives.
                val archived = RecoveryNativeFixture(archiveStorage); val old = source(archived, "archived-fixture-" + "b".repeat(48))
                archived.loseAck = true; check(archived.sync(snapshot = old.snapshot)?.state == "mutation-unknown-original-query")
                NativeChatNotificationAuthorizations(archiveStorage).retain(old)
                val raw = NativeMigrationForeground.ACTIVE_NAMES.mapNotNull { name -> archiveStorage.read(name)?.let { name to it } }.toMap()
                val projected = NativeMigrationNotificationArchives.projectSource(raw)
                check(projected.keys.none { it in NativeMigrationForeground.ACTIVE_NAMES })
                check(NativeMigrationNotificationArchives.validatePortable(projected) == projected.keys)
                projected.forEach { (name, value) -> storage.write(name, value) }
                check(NativeMigrationNotificationArchives.requiresExplicitSetup(storage) && !f.notices.preferences().enabled)
                check(NativeMigrationNotificationArchives.readOnlyArchives(storage).size == 1)
                storage.awaitQueuedWrites(); archiveStorage.awaitQueuedWrites()
                before = files(run); archiveBefore = files(archiveRun)
                port = Port(storage, source(f, "rotated-fixture-" + "c".repeat(48)))
            }
            val activePort = checkNotNull(port)
            main {
                coordinator = NativeChatNotificationCoordinator(parent, activePort, system, boundary.commands, now = { f.now }, applicationId = context.packageName, migrationEnrollmentCheck = true)
                scopeOwnership(checkNotNull(coordinator), parent); coordinator!!.updateComputers(listOf(saved.client)); coordinator!!.reconcile()
            }
            val c = checkNotNull(coordinator)
            until { withContext(Dispatchers.Main.immediate) { c.migrationInteractionReady && c.migrationNeedsSetup && !c.migrationEnrollmentFailed && f.target in c.originalReports } }
            check(boundary.queries.isEmpty() && activePort.captures.get() == 0 && boundary.forbidden.get() == 0)
            if(case == "off-query-only") {
                val callback = UUID.nameUUIDFromBytes("off-callback:$run".toByteArray()).toString()
                repeat(2) { index ->
                    main { c.explicitDisable(null, offRevision, callback) }
                    until { withContext(Dispatchers.Main.immediate) { boundary.queries.size > index && c.querying.isEmpty() } }
                }
            } else {
                main { c.queryOriginals(f.target) }
                until { withContext(Dispatchers.Main.immediate) { c.querying.isEmpty() && if(case == "missing-authority") c.originalReports[f.target]?.state == "original-credential-recovery-required" else boundary.queries.size == 1 } }
            }
            io {
                val view = f.view()
                check(view.operations.map { it.original.encoded() } == originals)
                val original = view.operations.single { it.original.operationId == boundary.expected.operationId }
                check(original.state == if(case == "retained-revoke") "applied" else "unknown")
                if(case != "retained-revoke")check(view.operations.size == 1 && original.original.kind == "register")
                check(!f.notices.preferences().enabled && f.notices.preferences().revision == offRevision)
                storage.awaitQueuedWrites(); after = files(run); archiveAfter = files(archiveRun)
                check(archiveBefore == archiveAfter)
                val receiptRecord = NativeChatNotificationWire.hash(NativeChatNotificationRecoveryStore.RECORD) + ".bin"
                check(if(case == "retained-revoke") before.filterKeys { it != receiptRecord } == after.filterKeys { it != receiptRecord } else before == after)
                if(case == "missing-authority")check(storage.read(NativeChatNotificationAuthorizations.RECORD) == null && boundary.queries.isEmpty())
                else check(boundary.queries.all { it.encoded() == boundary.expected.encoded() })
            }
            check(boundary.forbidden.get() == 0 && system.forbidden.get() == 0 && activePort.forbidden.get() == 0)
            check(boundary.queryEntries.get() == boundary.queries.size)
            check(activePort.captures.get() == if(case == "missing-authority") 1 else 0)
            main { check(c.migrationNeedsSetup && c.migrationInteractionReady && !c.migrationEnrollmentFailed && c.reports.isEmpty()) }
        } catch(error: Throwable) { failure = error }
        finally {
            try {
                main {
                    coordinator?.let { c ->
                        // Bounded diagnostic snapshot BEFORE stop clears the owner state. No raw errors/credentials.
                        coordinatorState = JSONObject().put("originalReportStates", JSONArray(c.originalReports.values.take(4).map { it.state.take(128) }))
                            .put("automaticReportStates", JSONArray(c.reports.values.take(4).map { it.state.take(128) })).put("queryingCount", c.querying.size)
                            .put("interactionReady", c.migrationInteractionReady).put("needsSetup", c.migrationNeedsSetup).put("enrollmentFailed", c.migrationEnrollmentFailed)
                        c.stopAndJoinForMigration()
                    }
                    check(parent.isActive); stopped = true
                }
                parent.coroutineContext[Job]!!.cancelAndJoin()
                io { storage.awaitQueuedWrites(); archiveStorage.awaitQueuedWrites(); storage.retireWriter(); archiveStorage.retireWriter() }
            } catch(error: Throwable) { if(failure == null)failure = error else failure!!.addSuppressed(error) }
            try { io {
                proof(context, lease, run, JSONObject().put("case", case).put("archiveRun", archiveRun).put("passed", failure == null).put("ownerStopped", stopped)
                    .put("failureType", failure?.javaClass?.name?.take(128) ?: JSONObject.NULL).put("queryCount", boundary.queries.size).put("forbiddenCommands", boundary.forbidden.get())
                    .put("queryEntries", boundary.queryEntries.get()).put("lastQueryState", boundary.lastQueryState.take(128)).put("coordinatorState", coordinatorState)
                    .put("forbiddenSystem", system.forbidden.get()).put("captures", port?.captures?.get() ?: 0).put("forbiddenReadingNavigation", port?.forbidden?.get() ?: 0)
                    .put("before", JSONObject(before)).put("after", JSONObject(after)).put("archiveBefore", JSONObject(archiveBefore)).put("archiveAfter", JSONObject(archiveAfter)))
            } } catch(error: Throwable) { if(failure == null)failure = error else failure!!.addSuppressed(error) }
        }
        failure?.let { throw it }
        Unit
    }
    private fun proof(context: Context, lease: String, run: String, details: JSONObject) {
        fun apk(runner: Boolean): String {
            val info = if(!runner)context.applicationInfo else context.packageManager.getApplicationInfo(context.packageName + ".test", 0).also {
                check(context.packageManager.checkSignatures(context.packageName, it.packageName) == android.content.pm.PackageManager.SIGNATURE_MATCH)
            }
            check(info.splitSourceDirs.isNullOrEmpty()); val file = File(info.sourceDir); check(file.length() in 1..128L * 1024 * 1024)
            return file.inputStream().use { input -> val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65_536); while(true) { val n = input.read(buffer); if(n < 0)break; digest.update(buffer, 0, n) }; digest.digest().joinToString("") { "%02x".format(it) } }
        }
        val folder = File(context.filesDir, "acceptance/P9-enrollment-originals/$lease").apply { check(mkdirs() || isDirectory); android.system.Os.chmod(absolutePath, 448) }
        val file = File(folder, "$run-${UUID.randomUUID()}.json")
        check(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 384)
        file.writeText(JSONObject().put("version", 1).put("lease", lease).put("run", run).put("api", android.os.Build.VERSION.SDK_INT)
            .put("package", context.packageName).put("appHash", apk(false)).put("runnerHash", apk(true)).put("details", details)
            .put("boundary", "Real Android storage/enrollment/archive graph; synthetic writer-recorded backend receipt and forbidden OS ports. No Host/model/system notification proof.")
            .put("retention", "Owned encrypted originals retained, including failures; no cleanup/replay of unknown input.").toString())
    }
}
