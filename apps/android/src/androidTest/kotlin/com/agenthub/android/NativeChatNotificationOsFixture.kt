package com.agenthub.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Real installed platform/provider; no Activity launch, permission mutation, shell, Host or model. */
internal class NotificationOsFixture(val context: Context, val scope: NativeChatNotificationAcceptanceScope, val lease: String, val case: String) {
    val storage = scope.storage(context)
    val notices = NativeChatNotificationStore(storage)
    val platform = NativeChatNotificationPlatform.forAcceptance(context, NativeL10n(context, "en"), scope, storage)
    val manager = context.getSystemService(NotificationManager::class.java)
    val target = NativeChatNoticeTarget("p9-os-" + scope.runId, "synthetic-workspace", "synthetic-runtime")
    val received = CopyOnWriteArrayList<String>()
    private val pendingIntents = CopyOnWriteArraySet<PendingIntent>()
    val mayPresent = AtomicBoolean(true)
    val imported = mutableListOf<NativeChatNotificationSound>()
    val notes = JSONObject()
    var preserveData = false
    val reading = object : NativeChatNotificationReading {
        override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
            check(Looper.myLooper() != Looper.getMainLooper()); received.add(event.id)
            return NativeChatNoticeReadingResult(true, 7)
        }
        override fun mayPresent(event: NativeChatNotificationEvent) = mayPresent.get()
    }
    fun <T> io(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.IO) { action() } }
    fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { action() } }
    fun configure(preview: Boolean = true, badge: Boolean = true) = io { notices.update { it.copy(enabled = true, soundEnabled = false, showThreadTitle = preview, badgeEnabled = badge) } }
    fun event(index: Int, target: NativeChatNoticeTarget = this.target) = NativeChatNotificationEvent(
        NativeChatNotificationSoundLibrary.sha((scope.runId + ":" + index).toByteArray()), target, NativeChatNoticeKind.APPROVAL,
        Instant.now().toString(), "private-thread-$index", threadTitle = "Private title $index", taskSummary = "Private summary $index")
    fun delivery(now: () -> Long = System::currentTimeMillis) = NativeChatNotificationForegroundDelivery(notices, platform, reading, { _, _, _ -> }, now)
    fun deliver(event: NativeChatNotificationEvent): String {
        val captured = io { notices.bind(event.target, "owned-credential-" + scope.runId) }
        return main { delivery().receive(event, captured, "Owned computer", { true }) }
    }
    fun receipt(event: NativeChatNotificationEvent) = io {
        val rows = JSONObject(checkNotNull(storage.read(NativeChatNotificationStore.RECORD))).getJSONArray("receipts")
        (0 until rows.length()).map { NativeChatNotificationCodec.receipt(rows.getJSONObject(it)) }.single { it.event == event }
    }
    fun active() = manager.activeNotifications.filter { it.tag?.startsWith(scope.prefix) == true }.also { rows ->
        rows.mapNotNull { it.notification.contentIntent }.forEach { pendingIntents.add(it) }
    }
    fun awaitCount(count: Int) = io { withTimeout(10_000) { while (active().size != count) delay(20) } }
    fun imported(bytes: ByteArray, title: String = "Owned synthetic clip") = io {
        platform.sounds.importNormalized(title, "wav", bytes).also { imported.add(it) }
    }
    fun readUri(item: NativeChatNotificationSound): ByteArray = context.contentResolver.openInputStream(platform.sounds.uri(item))!!.use { it.readBytes() }
    fun cleanup(bodyPassed: Boolean) {
        var cleaned = false
        var dataRemoved = false
        try {
            active()
            main { platform.cancelOwned() }; awaitCount(0)
            pendingIntents.forEach { it.cancel() }; notes.put("ownedPendingIntentsCancelled", pendingIntents.size)
            val channels = platform.channels(); channels.forEach { check(it.id.startsWith(scope.prefix)); manager.deleteNotificationChannel(it.id) }
            check(platform.channels().isEmpty())
            if (bodyPassed && !preserveData) io {
                imported.forEach { if (platform.sounds.find(it.id) != null) platform.sounds.delete(it.id, notices) }
                for (record in listOf(NativeChatNotificationStore.RECORD, NativeChatNotificationSoundLibrary.RECORD,
                    NativeChatNotificationIdentity.RECORD, NativeChatNotificationForegroundStore.RECORD,
                    NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationAuthorizations.RECORD, CYCLE_RECORD)) storage.remove(record)
                val root = File(storage.coordinationKey)
                if (root.listFiles().orEmpty().isEmpty()) { check(root.delete() || !root.exists()); dataRemoved = true }
                else { notes.put("unrecognizedPrivateFilesPreserved", root.listFiles().orEmpty().size); error("Unrecognized owned storage files preserved for review") }
            }
            cleaned = true
        } finally {
            val permission = platform.permission()
            val value = JSONObject().put("version", 1).put("case", case).put("lease", lease).put("runId", scope.runId)
                .put("api", Build.VERSION.SDK_INT).put("package", context.packageName).put("bodyPassed", bodyPassed)
                .put("ownedSystemCleanupPassed", cleaned).put("privateDataRetained", !dataRemoved)
                .put("permissionDeclared", permission.declared).put("permissionGranted", permission.granted).put("appEnabled", permission.appEnabled)
                .put("readingPortCalls", received.size).put("details", notes)
            val folder = File(context.filesDir, "acceptance/P9-native-system/$lease").apply { mkdirs() }
            File(folder, scope.runId + "-" + UUID.randomUUID().toString() + ".json").writeText(value.toString())
        }
    }
    companion object {
        const val CYCLE_RECORD = NativeChatNotificationCycleEvidence.RECORD
        // Distinct from shipped builtins, so deleting the owned import cannot fall back to a builtin hash.
        fun tone() = NativeChatNotificationPcm.builtin("soft.caf").also { it[44] = 1; it[45] = 0 }
    }
}

abstract class NativeChatNotificationOsBase {
    @get:Rule(order = 0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    private val owned = mutableListOf<NotificationOsFixture>()
    private var passed = false
    private var case = ""
    protected lateinit var lease: String
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected open val permissionMode: String = "unchanged"
    @get:Rule(order = 1) val cleanup: TestWatcher = object : TestWatcher() {
        override fun starting(description: Description) { case = description.methodName }
        override fun succeeded(description: Description) { passed = true }
        override fun finished(description: Description) {
            val failures = owned.mapNotNull { runCatching { it.cleanup(passed) }.exceptionOrNull() }
            if (failures.isNotEmpty()) { failures.drop(1).forEach(failures.first()::addSuppressed); throw failures.first() }
        }
    }
    @Before fun explicitParentLeaseOnly() {
        val args = InstrumentationRegistry.getArguments()
        val value = args.getString("p9SystemLease")
        Assume.assumeTrue("OS notification cases need an explicit parent device lease; skip is NOT acceptance", value != null)
        lease = checkNotNull(value); require(UUID.fromString(lease).toString() == lease)
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance")
        require(args.getString("p9SystemPermission", "unchanged") == permissionMode) { "Parent permission phase does not match this class" }
    }
    internal fun fixture(runId: String = UUID.randomUUID().toString()): NotificationOsFixture {
        val f = NotificationOsFixture(context, NativeChatNotificationAcceptanceScope.create(context, runId), lease, case)
        val actual = f.platform.permission()
        if (permissionMode == "granted") require(actual.canPost) { "Parent must grant this acceptance package before the granted phase" }
        if (permissionMode == "denied") require(Build.VERSION.SDK_INT >= 33 && actual.declared && !actual.granted && !actual.canPost) { "Parent must deny this acceptance package before the denied phase" }
        owned.add(f)
        return f
    }
    internal data class RecordedCycle(val markerRaw: String, val identityHash: String, val operation: NativeChatRecoveryLocalOperation, val response: NativeChatRecoveryReceipt)
    /** Read-only preflight before this test registers any existing cycle as an owned cleanup resource. */
    internal fun recordedCycle(runId: String): RecordedCycle = runBlocking { withContext(Dispatchers.IO) {
        val scope = NativeChatNotificationAcceptanceScope.create(context, runId)
        val before = NativeChatNotificationCycleFiles.existing(context.noBackupFilesDir, runId)
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(keys.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey) { "Existing cycle key required; preflight never generates/imports a key" }
        val storage = scope.storage(context)
        val marker = checkNotNull(storage.read(NotificationOsFixture.CYCLE_RECORD))
        val identityHash = NativeChatNotificationWire.hash(checkNotNull(storage.read(NativeChatNotificationIdentity.RECORD)))
        val operation = NativeChatNotificationCycleEvidence.recordedOperation(checkNotNull(storage.read(NativeChatNotificationRecoveryStore.RECORD)))
        val response = NativeChatNotificationCycleEvidence.restore(marker, runId, lease, android.os.Process.myPid(), NativeChatNotificationCycleEvidence.processNonce, identityHash, operation)
        check(before == NativeChatNotificationCycleFiles.existing(context.noBackupFilesDir, runId)) { "Original cycle files changed during preflight; preserve result and do not execute" }
        RecordedCycle(marker, identityHash, operation, response)
    } }
    protected fun cycleId(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("p9SystemCycle")) { "A shared exact cycle UUID is required" }.also { require(UUID.fromString(it).toString() == it) }
    internal fun cycle(f: NotificationOsFixture, enable: Boolean): RecoveryNativeFixture = f.io {
        RecoveryNativeFixture(f.storage, enable).also { it.epoch = UUID.nameUUIDFromBytes(("p9-system-cycle:" + f.scope.runId).toByteArray()).toString() }
    }
    internal fun sync(f: NotificationOsFixture, source: RecoveryNativeFixture) = f.main {
        NativeChatNotificationRecoveryController(f.storage, source.notices, source.legacy, source.commands, f.delivery { source.now }) { source.now }
            .synchronize(source.snapshot()) { true }
    }
}
