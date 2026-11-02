package com.agenthub.android

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Source-only Stage 69 proposal: a bounded, real producer -> export -> Session restore case.
 * The dedicated SessionTest exposes only FIRST_METHOD through closed native/host admission.
 * Cold is not implemented here; exact-original-PID exit and a separate cold contract remain pending.
 * Ordinary owners only; this does not prove Controller/strict-generation handoff or OS delivery.
 */
internal object NativeMigrationNonemptyDeviceCase {
    const val SCHEMA = "p7-nonempty-device-session-v1"
    const val FIRST_METHOD = "nonemptyDeviceProducersRestoreActualSession"
    private const val SOURCE = "source"
    private const val DESTINATION = "installation"
    private const val PASSWORD = "owned nonempty native device migration password"
    private const val THREAD = "synthetic-thread"
    private const val DRAFT = "Independent draft after nonempty Session migration"
    private val now = java.time.Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
    private val shaPattern = Regex("[a-f0-9]{64}")

    /** Values are admitted by the future host wrapper, then independently checked on device. */
    data class Binding(val appApkSha256: String, val testApkSha256: String,
        val caseSourceSha256: String, val wrapperSourceSha256: String,
        val sourceGuardSha256: String, val sourceAdmissionSha256: String,
        val bootUuid: String, val packageName: String, val uid: Int) {
        fun requireCurrent() {
            listOf(appApkSha256, testApkSha256, caseSourceSha256, wrapperSourceSha256,
                sourceGuardSha256, sourceAdmissionSha256).forEach { require(it.matches(shaPattern)) }
            require(packageName == "com.easyai.agenthub.android.acceptance")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            require(instrumentation.targetContext.packageName == packageName && Process.myUid() == uid)
            require(instrumentation.targetContext.applicationInfo.uid == uid)
            require(UUID.fromString(bootUuid).toString() == bootUuid && currentBoot() == bootUuid)
            require(apkSha(instrumentation.targetContext) == appApkSha256)
            require(apkSha(instrumentation.context) == testApkSha256)
        }
    }

    /** guard must revalidate NOFOLLOW inode/owner/mode ancestry; it is never a no-op admission. */
    class Scope(val context: Context, val run: String, val binding: Binding, val guard: () -> Unit) {
        private val admittedBinding by lazy { binding.requireCurrent(); true }
        val alias = "p7-nonempty-$run"
        val sourceAlias = "$alias-source"
        val archive: File get() { checkScope(); return File(context.filesDir, "sealed.migration") }
        fun checkScope() {
            require(UUID.fromString(run).toString() == run)
            require(admittedBinding && currentBoot() == binding.bootUuid); guard()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(target.filesDir.canonicalFile, "P7-nonempty-$run")
            require(context.applicationContext === context)
            require(context.noBackupFilesDir.canonicalFile == File(root, "no_backup"))
            require(context.filesDir.canonicalFile == File(root, "files"))
            require(context.cacheDir.canonicalFile == File(root, "cache"))
            require(context.noBackupFilesDir.canonicalFile != target.noBackupFilesDir.canonicalFile)
        }
        fun view(source: Boolean = false): NativeMigrationExistingView {
            checkScope()
            val opened = NativeSessionStorage.existingMigration(context, context.noBackupFilesDir,
                if (source) SOURCE else DESTINATION, if (source) sourceAlias else alias)
            try { guard(); return opened } catch (error: Throwable) {
                try { opened.using<Unit> { throw error } } catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
                throw error
            }
        }
    }

    /** First-case wrapper persists this as a closed object; cold decoding/admission remains pending. */
    data class Proof(val schema: String, val run: String, val binding: Binding, val pid: Int,
        val startTicks: String, val receipt: NativeMigrationCandidateStore.Receipt, val generation: String,
        val archiveSha256: String, val sourceCiphertext: Map<String, String>,
        val originalCandidateCiphertext: Map<String, String>, val destinationCiphertext: Map<String, String>,
        val originalTextSha256: Map<String, String>, val originalBinarySha256: Map<String, String>,
        val draftKey: String, val acquiredStores: Int, val acquiredSessions: Int,
        val actualSessionJoinReturns: Int, val actualWriterRetireReturns: Int,
        val actualLeaseReleaseReturns: Int, val unknownAcquisitions: Int, val forbiddenPortAttempts: Int)

    private class Ports : HostReads, CodexCommands {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        private fun deny(name: String): Nothing { attempts.incrementAndGet(); error("nonempty migration forbids $name") }
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace = deny("workspace")
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory = deny("history")
        override suspend fun models(client: MobileClient): List<CodexModel> = deny("models")
        override suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long): EventBatch = deny("events")
        override suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt = deny("send")
        override suspend fun receipt(client: MobileClient, threadId: String, operationId: String): DeliveryReceipt = deny("receipt")
        override suspend fun createThread(client: MobileClient, projectId: String?, operationId: String): MobileThread = deny("createThread")
        override suspend fun prepareCreation(client: MobileClient, intent: CreationIntent): DesktopCreation = deny("prepareCreation")
        override suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean): DesktopCreation = deny("creation")
        override suspend fun submitCreation(client: MobileClient, operationId: String): DesktopCreation = deny("submitCreation")
        override suspend fun detachCreation(client: MobileClient, operationId: String): DesktopCreation = deny("detachCreation")
        override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen = deny("open")
        override suspend fun stop(client: MobileClient, threadId: String, turnId: String): Unit = deny("stop")
        override suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String): Unit = deny("cancelQueue")
        override suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String, List<String>>): Unit = deny("answer")
        override suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray): MobileFile = deny("upload")
        override suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray): Unit = deny("uploadCreation")
        override suspend fun acknowledgeRead(client: MobileClient, request: NativePendingRead): JSONObject = deny("acknowledgeRead")
    }

    /** Track attempted acquisitions before opening. A partial helper remains UNKNOWN until PID exit. */
    private class Owners {
        val stores = mutableListOf<NativeSessionStorage>()
        val sessions = mutableListOf<MobileSession>()
        private val retired = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage, Boolean>())
        private val stopped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MobileSession, Boolean>())
        private val attemptedRetire = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage, Boolean>())
        private val attemptedStop = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MobileSession, Boolean>())
        private val sessionRoots = java.util.IdentityHashMap<MobileSession, NativeSessionStorage>()
        private val constructingRoots = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage, Boolean>())
        private val childrenByRoot = java.util.IdentityHashMap<NativeSessionStorage, MutableSet<NativeSessionStorage>>()
        private val unknown = linkedSetOf<String>()
        private var serial = 0
        class Held(val lease: NativeMigrationOwnerLease) { var attempted = false; var returned = false }
        private val leases = mutableListOf<Held>()
        /** The production helper owns both ends until its complete successful return.
         * Capture actual objects here without granting the outer registry cleanup rights. */
        private class DelegatedRestore(val original: MobileSession, val oldRoot: NativeSessionStorage,
            val held: Held, val attempt: String) {
            val openedRoots = mutableListOf<NativeSessionStorage>()
            val createdSessions = mutableListOf<MobileSession>()
            var constructorAttempts = 0
            var helperReturned = false
            var transferred = false
            var failure: Throwable? = null
            fun owns(store: NativeSessionStorage) = !transferred &&
                (oldRoot === store || openedRoots.any { it === store })
            fun owns(session: MobileSession) = !transferred &&
                (original === session || createdSessions.any { it === session })
        }
        private val delegations = mutableListOf<DelegatedRestore>()
        private fun delegated(store: NativeSessionStorage) = delegations.any { it.owns(store) }
        private fun delegated(session: MobileSession) = delegations.any { it.owns(session) }
        private fun actualRoot(store: NativeSessionStorage) =
            childrenByRoot.entries.firstOrNull { it.value.contains(store) }?.key ?: store
        private fun unconfirmedWriterGraph(store: NativeSessionStorage): Boolean {
            val root = actualRoot(store)
            return delegated(root) || constructingRoots.contains(root) ||
                sessionRoots.entries.any { it.value === root && !stopped.contains(it.key) }
        }
        var joined = 0; private set
        var retiredReturns = 0; private set
        var released = 0; private set
        val unknownCount get() = unknown.size
        fun own(store: NativeSessionStorage, root: NativeSessionStorage = store): NativeSessionStorage {
            if (stores.none { it === store }) stores.add(store)
            if (store !== root) {
                check(stores.any { it === root })
                check(actualRoot(store).let { it === store || it === root })
                childrenByRoot.getOrPut(root) {
                    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage, Boolean>())
                }.add(store)
            }
            return store
        }
        private fun begin(label: String) = "${++serial}:$label".also { unknown.add(it) }
        suspend fun open(label: String, root: NativeSessionStorage? = null, block: () -> NativeSessionStorage): NativeSessionStorage = withContext(NonCancellable + Dispatchers.IO) {
            val attempt = begin(label); val opened = block()
            own(opened, root ?: opened).also { unknown.remove(attempt) }
        }
        fun session(store: NativeSessionStorage, ports: Ports): MobileSession {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            check(stores.any { it === store } && actualRoot(store) === store)
            val attempt = begin("actual-Session")
            check(constructingRoots.add(store)) // Before construction; no returned Session is assumed.
            return MobileSession(store, ports, ports).also {
                sessionRoots[it] = store; sessions.add(it)
                constructingRoots.remove(store); unknown.remove(attempt)
            }
        }
        suspend fun freeze(session: MobileSession): Held = withContext(NonCancellable + Dispatchers.Main.immediate) {
            val attempt = begin("actual-quiesce+Frozen")
            Held(session.quiesceForMigration(UUID.randomUUID().toString())).also { leases.add(it); unknown.remove(attempt) }
        }
        suspend fun release(held: Held) = withContext(NonCancellable + Dispatchers.IO) {
            check(!held.attempted); held.attempted = true
            held.lease.prepareAndReleaseForRestore(); held.returned = true; released++
        }
        suspend fun restore(scope: Scope, original: MobileSession, held: Held, ports: Ports): Pair<MobileSession, NativeSessionStorage> =
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                check(held.returned)
                val attempt = begin("production-restore-root+children")
                val delegated = DelegatedRestore(original, held.lease.storage, held, attempt)
                delegations.add(delegated) // Includes original owner/root BEFORE invoking production.
                try {
                    val fresh = restoreNativeMigrationSession(scope.context, original, held.lease, create = { opened ->
                        delegated.openedRoots.add(opened) // Exact root retained even when the constructor throws.
                        check(delegated.openedRoots.size == 1)
                        delegated.constructorAttempts++
                        MobileSession(opened, ports, ports).also { delegated.createdSessions.add(it) }
                    })
                    delegated.helperReturned = true
                    val storage = delegated.openedRoots.single()
                    check(delegated.createdSessions.single() === fresh && held.lease.restoredStorage === storage)
                    // There is no suspension between full production return and this cleanup-rights transfer.
                    // If bookkeeping itself fails, the still-delegated group remains strongly captured.
                    own(storage); sessionRoots[fresh] = storage; sessions.add(fresh)
                    retired.add(held.lease.storage); attemptedRetire.add(held.lease.storage)
                    delegated.transferred = true
                    withContext(Dispatchers.IO) { cacheGraph(scope.context, storage) }
                    // All acquisition ownership is now resolved. Later assertion/await failures
                    // do not become UNKNOWN merely because the case did not pass.
                    unknown.remove(attempt)
                    fresh.awaitMigrationRestore(); original.requireCompletedMigrationTransfer(held.lease)
                    require(fresh.chatNotifications == null)
                    fresh to storage
                } catch (error: Throwable) {
                    // Production may already have attempted/partly completed cleanup on either end.
                    // A failure before transfer never grants outer stop/retire permission to this group.
                    delegated.failure = error
                    throw error
                }
            }
        fun cacheGraph(context: Context, root: NativeSessionStorage) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            val children = linkedMapOf<String, NativeSessionStorage>()
            for (path in NativeMigrationExistingPolicy.paths.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })) {
                val parentPath = path.substringBeforeLast('/', "")
                val parent = if (parentPath.isEmpty()) root else children.getValue(parentPath)
                val attempt = begin("child:$path")
                children[path] = own(parent.prepareChild(context, path.substringAfterLast('/')), root)
                unknown.remove(attempt)
            }
        }
        suspend fun stop(session: MobileSession) = withContext(NonCancellable + Dispatchers.Main.immediate) {
            check(!delegated(session)) { "Production restore still owns this Session; no outer cleanup retry" }
            if (stopped.contains(session)) return@withContext
            check(attemptedStop.add(session)) { "Never retry an unconfirmed Session stop" }
            session.abandonMigrationRestore(); stopped.add(session); joined++
        }
        suspend fun close(primary: Throwable? = null): Unit = withContext(NonCancellable) {
            var failure: Throwable? = null
            suspend fun attempt(action: suspend () -> Unit) { try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            } }
            leases.asReversed().forEach { held -> if (!held.attempted) attempt { release(held) } }
            sessions.asReversed().forEach { session -> if (!delegated(session) && !attemptedStop.contains(session)) attempt { stop(session) } }
            // A Session stop that did not return does not prove its producers joined. Skip its
            // exact root and registered child handles; same-path reopened roots stay distinct.
            stores.asReversed().forEach { store -> if (!unconfirmedWriterGraph(store) && !attemptedRetire.contains(store)) {
                attempt { withContext(Dispatchers.IO) { store.awaitQueuedWrites() } }
                attempt { withContext(Dispatchers.IO) {
                    check(attemptedRetire.add(store)); store.retireWriter(); retired.add(store); retiredReturns++
                } }
            } }
            if (unknown.isNotEmpty() || constructingRoots.isNotEmpty() || delegations.any { !it.transferred } || leases.any { !it.returned } ||
                stopped.size != sessions.size || retired.size != stores.size)
                attempt { error("Unresolved owner; retain exact fixture/keys until original PID exit; no cold") }
            failure?.let { cleanup ->
                val retained = primary ?: cleanup
                if (retained !== cleanup) retained.addSuppressed(cleanup)
                // Other known independent cleanup has finished. Production keeps a static strong
                // reference to this exact registry, its delegated old/new roots/Sessions, leases,
                // and every captured prepared-child cache. This deliberately cannot return.
                // An unreturned constructor object is UNKNOWN, never claimed as captured/closed.
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        android.util.Log.e("P7NonemptyDevice", "cleanup=UNKNOWN; no success proof; originalPid=${Process.myPid()}; " +
                            "unresolvedAcquisitions=${unknown.size}; delegatedRestores=${delegations.count { !it.transferred }}; " +
                            "primaryType=${retained.javaClass.name}; original PID exit required")
                    } catch (diagnostic: Throwable) {
                        if (diagnostic !== retained) retained.addSuppressed(diagnostic)
                    } finally {
                        NativeMigrationReadLifetime().hold(this@Owners, retained)
                    }
                }
            }
        }
    }

    private fun seed(context: Context, source: NativeSessionStorage, owner: MobileSession) {
        val client = CodexConnectionVault(source).clients().single()
        val target = requireNotNull(client.scope)
        val ledger = requireNotNull(owner.phoneReading.ledger)
        val read = ThreadReadPosition(2, "source-visible", ThreadReadPosition.contentVersion("source-visible", "Old visible reply", "completed"))
        val latest = ThreadReadPosition(3, "source-latest", ThreadReadPosition.contentVersion("source-latest", "New unread reply", "completed"))
        val seen = NativeChatNotificationEvent(readingSHA("${UUID.randomUUID()}:seen"), NativeChatNoticeTarget.from(target),
            NativeChatNoticeKind.UNCERTAIN, java.time.Instant.ofEpochMilli(now).toString(), THREAD,
            operationId = UUID.randomUUID().toString(), taskSummary = "Synthetic original unknown result")
        val unseen = seen.copy(id = readingSHA("${UUID.randomUUID()}:unseen"), operationId = UUID.randomUUID().toString())
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, THREAD, read, true)))
        ledger.receive(NativeReadingEvent(seen.id, target, THREAD, seen.kind.wire, operationId = seen.operationId))
        val lifecycle = NativeReadingLifecycle()
        lifecycle.present(target, THREAD, read, setOf(seen.id)); lifecycle.foreground(true); lifecycle.uncovered(true)
        val visible = requireNotNull(lifecycle.visible(target, THREAD, read.messageId, "Old visible reply", "completed", true))
        ledger.readVisible(target, THREAD, readingSHA("${UUID.randomUUID()}:reader"), lifecycle, visible)
        val noticeVisible = requireNotNull(lifecycle.visibleNotices(target, THREAD, setOf(seen.id)))
        requireNotNull(ledger.readVisible(target, THREAD, readingSHA("${UUID.randomUUID()}:notice-reader"), lifecycle, noticeVisible))
        ledger.receive(NativeReadingEvent(readingSHA("${UUID.randomUUID()}:latest"), target, THREAD, "completed", latest))
        ledger.receive(NativeReadingEvent(unseen.id, target, THREAD, unseen.kind.wire, operationId = unseen.operationId))
        val library = NativeChatNotificationSoundLibrary(context, source)
        val bytes = NativeChatNotificationPcm.builtin("soft.caf")
        val sound = try { library.importNormalized("Synthetic migrated normalized sound", "wav", bytes) } finally { bytes.fill(0) }
        val notices = NativeChatNotificationStore(source)
        notices.update { it.copy(enabled = true, timeSensitive = true, foregroundBanner = false,
            sound = sound.id, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to sound.id)) }
        val lease = notices.bind(seen.target, "synthetic-${UUID.randomUUID()}")
        for (event in listOf(seen, unseen)) notices.finish(requireNotNull(notices.claim(event, lease, now)).ticket, false)
        requireSourceNonempty(source)
    }

    private fun requireSourceNonempty(source: NativeSessionStorage) {
        val reading = JSONObject(requireNotNull(source.read(NativeMigrationDeviceState.READING)))
        require(reading.getJSONArray("scopes").length() == 1 && reading.getJSONArray("rows").length() == 1)
        val row = reading.getJSONArray("rows").getJSONObject(0)
        require(row.has("pendingReader") && row.getJSONObject("read").getLong("revision") == 2L && row.getJSONObject("latest").getLong("revision") == 3L)
        val notices = row.getJSONObject("notices"); val seen = notices.keys().asSequence().map { notices.getJSONObject(it).getBoolean("seen") }.toList()
        require(seen.any { it } && seen.any { !it } && NativeReadingLedger(source).pendingReads().isNotEmpty())
        val notifications = JSONObject(requireNotNull(source.read(NativeMigrationDeviceState.NOTIFICATIONS)))
        require(notifications.getJSONObject("preferences").getBoolean("enabled"))
        require(notifications.getJSONArray("leases").length() == 1 && notifications.getJSONArray("receipts").length() == 2)
        val store = NativeChatNotificationStore(source)
        require(store.preferences().enabled && NativeReadingLedger(source).snapshot().count == 1)
        for (i in 0 until notifications.getJSONArray("receipts").length()) {
            val receipt = NativeChatNotificationCodec.receipt(notifications.getJSONArray("receipts").getJSONObject(i))
            require(receipt.state == "unknown" && store.current(receipt.lease) && store.ticket(receipt.ticket, now) != null)
        }
        val catalog = JSONObject(requireNotNull(source.read(NativeMigrationSounds.RECORD)))
        require(catalog.getJSONArray("items").length() == 1 && catalog.getJSONArray("staged").length() == 0 && catalog.getJSONArray("retired").length() == 0)
    }

    private fun verifyDomain(context: Context, current: NativeSessionStorage, original: Map<String, String>,
        receipt: NativeMigrationCandidateStore.Receipt, afterReconciliation: Boolean) {
        val sourceReading = JSONObject(original.getValue(NativeMigrationDeviceState.READING))
        val reading = JSONObject(requireNotNull(current.read(NativeMigrationDeviceState.READING)))
        require(reading.getLong("revision") >= sourceReading.getLong("revision") + 1)
        if (!afterReconciliation) require(reading.getJSONArray("scopes").length() == 0 &&
            reading.getLong("revision") == sourceReading.getLong("revision") + 1)
        val sourceRow = sourceReading.getJSONArray("rows").getJSONObject(0)
        val row = reading.getJSONArray("rows").getJSONObject(0)
        require(reading.getJSONArray("rows").length() == 1 && !row.has("pendingReader"))
        for (field in listOf("target", "threadId", "read", "latest", "replyUnread", "notices"))
            assertEquals(field, canonical(sourceRow.get(field)), canonical(row.get(field)))
        val notifications = JSONObject(requireNotNull(current.read(NativeMigrationDeviceState.NOTIFICATIONS)))
        val sourceNotifications = JSONObject(original.getValue(NativeMigrationDeviceState.NOTIFICATIONS))
        val preferences = notifications.getJSONObject("preferences"); val prior = sourceNotifications.getJSONObject("preferences")
        require(!preferences.getBoolean("enabled") && preferences.getLong("generation") == prior.getLong("generation") + 1)
        require(preferences.getString("revision") != prior.getString("revision"))
        for (field in prior.keys().asSequence().filter { it !in setOf("enabled", "generation", "revision") })
            assertEquals(field, canonical(prior.get(field)), canonical(preferences.get(field)))
        require(notifications.getJSONArray("leases").length() == 0 && notifications.getJSONArray("receipts").length() == 0)
        val audit = JSONObject(requireNotNull(current.read(NativeMigrationDeviceState.AUDIT))).getJSONArray("entries")
        require(audit.length() == 1)
        val entry = audit.getJSONObject(0)
        assertEquals(receipt.id, entry.getString("candidateId")); assertEquals(receipt.sha256, entry.getString("archiveSha256"))
        assertEquals(original.getValue(NativeMigrationDeviceState.READING), entry.getString("reading"))
        assertEquals(original.getValue(NativeMigrationDeviceState.NOTIFICATIONS), entry.getString("notifications"))
        val store = NativeChatNotificationStore(current)
        for (i in 0 until sourceNotifications.getJSONArray("receipts").length()) {
            val old = NativeChatNotificationCodec.receipt(sourceNotifications.getJSONArray("receipts").getJSONObject(i))
            require(!store.current(old.lease) && store.ticket(old.ticket, now) == null)
            require(NativeMigrationNotificationTombstones.contains(current, old.event))
        }
        val ledger = NativeReadingLedger(current)
        require(ledger.pendingReads().isEmpty())
        if (afterReconciliation) require(ledger.snapshot().count == 1 && reading.getJSONArray("scopes").length() == 1)
        assertEquals(original.getValue(NativeMigrationSounds.RECORD), current.read(NativeMigrationSounds.RECORD))
        val item = JSONObject(original.getValue(NativeMigrationSounds.RECORD)).getJSONArray("items").getJSONObject(0)
        val library = NativeChatNotificationSoundLibrary(context, current)
        val found = requireNotNull(library.find(item.getString("id")))
        require(found.imported && found.title == item.getString("title") && found.sha256 == item.getString("sha256") &&
            found.bytes == item.getInt("bytes") && found.frames == item.getInt("frames") && found.sourceExtension == item.getString("sourceExtension"))
        val bytes = library.read(found.id)
        try { require(bytes.size == found.bytes && sha(bytes) == found.sha256); NativeChatNotificationSoundLibrary.validateBinary(found, bytes) }
        finally { bytes.fill(0) }
    }

    suspend fun first(scope: Scope): Proof {
        scope.checkScope()
        require(!scope.archive.exists())
        for (namespace in listOf(SOURCE, DESTINATION)) require(!File(scope.context.noBackupFilesDir, namespace).exists())
        val owners = Owners(); val ports = Ports(); var result: Proof? = null; var failure: Throwable? = null
        try {
            val source = owners.open("normal-source") { NativeSessionStorage(scope.context, SOURCE, scope.sourceAlias) }
            val destination = owners.open("normal-destination") { NativeSessionStorage(scope.context, DESTINATION, scope.alias) }
            withContext(NonCancellable + Dispatchers.IO) {
                val records = MigrationActivationFixtures.payload().getJSONObject("records")
                for (name in records.keys()) source.write(name, records.getString(name))
            }
            owners.open("source-candidate-cache", source) { source.prepareChild(scope.context, "migration-candidates") }
            owners.open("destination-candidate-cache", destination) { destination.prepareChild(scope.context, "migration-candidates") }
            val sourceOwner = withContext(NonCancellable + Dispatchers.Main.immediate) {
                owners.session(source, ports).also {
                    it.restoreForMigration(); require(it.chatNotifications == null && it.migrationReady)
                    it.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(source))
                    it.voiceSettings.resumePreferences()?.join(); require(it.voiceSettings.persisted)
                }
            }
            withContext(Dispatchers.IO) { seed(scope.context, source, sourceOwner) }
            val destinationOwner = withContext(NonCancellable + Dispatchers.Main.immediate) {
                owners.session(destination, ports).also {
                    it.restoreForMigration(); require(it.chatNotifications == null && it.migrationCanImport)
                    it.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(destination))
                    it.voiceSettings.resumePreferences()?.join(); require(it.voiceSettings.persisted)
                }
            }
            // Publish a real local empty draft before existing-only candidate inspection.
            // A pristine no-root-key installation is a distinct pending first-install case.
            withContext(Dispatchers.Main.immediate) {
                destinationOwner.text = "Owned local setup draft; never submitted"
                destinationOwner.text = ""
                require(destinationOwner.text.isEmpty() && destinationOwner.migrationCanImport)
            }
            withContext(Dispatchers.IO) {
                destination.awaitQueuedWrites()
                requireNotNull(destination.metadata()) { "Actual destination Session setup was not persisted" }
            }
            val sourceHeld = owners.freeze(sourceOwner)
            val sourceCipher = withContext(Dispatchers.IO) {
                scope.guard(); sourceHeld.lease.requireRawWriters(); requireSourceNonempty(source)
                scope.view(true).using { it.familyCiphertext() }
            }
            val receipt = withContext(Dispatchers.IO) {
                NativeMigrationExport(scope.context, source).sealFile(scope.archive, PASSWORD, sourceHeld.lease)
                NativeMigrationCandidateStore(scope.context, destination).stageFile(UUID.randomUUID().toString(), scope.archive, PASSWORD)
            }
            lateinit var original: Map<String, String>; lateinit var textPins: Map<String, String>; lateinit var binaryPins: Map<String, String>
            val candidateCipher = withContext(Dispatchers.IO) { scope.view().using { saved ->
                saved.originalCandidate(null, receipt) { records ->
                    require(records.version == 4); original = materialize(records); textPins = textDigests(original); binaryPins = binaryDigests(records)
                    require(original.keys.intersect(NativeMigrationForeground.ACTIVE_NAMES).isEmpty())
                    require(NativeMigrationNotificationArchives.RECORD !in original)
                    for (name in listOf(NativeMigrationDeviceState.READING, NativeMigrationDeviceState.NOTIFICATIONS, NativeMigrationSounds.RECORD))
                        assertEquals(source.read(name), original.getValue(name))
                }
                saved.originalCandidateCiphertext(null)
            } }
            withContext(Dispatchers.IO) { assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() }) }
            // Release the source's real lock before the destination attempts its independent real lock.
            owners.release(sourceHeld)
            val (sourceFresh, _) = owners.restore(scope, sourceOwner, sourceHeld, ports)
            owners.stop(sourceFresh)
            withContext(Dispatchers.IO) { assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() }) }
            val destinationHeld = owners.freeze(destinationOwner)
            val noticeLease = withContext(Dispatchers.Main.immediate) { destinationHeld.lease.notification(receipt) }
            val generation = withContext(Dispatchers.IO) {
                destinationHeld.lease.requireRawWriters()
                NativeMigrationActivation(scope.context, destination, noticeLease, destinationHeld.lease).activate(receipt)
            }
            // A fresh ordinary readback records the reset state before actual Session reconciliation.
            val reset = owners.open("pre-Session-reset-readback") { destination.reopen(scope.context) }
            withContext(Dispatchers.IO) { verifyDomain(scope.context, reset, original, receipt, false) }
            owners.release(destinationHeld)
            val (fresh, current) = owners.restore(scope, destinationOwner, destinationHeld, ports)
            withContext(Dispatchers.Main.immediate) {
                fresh.awaitMigrationRestore(); require(fresh.phoneReading.ready && fresh.phoneReading.failure == null)
                require(fresh.chatNotifications == null && fresh.aiSharing.grants.isEmpty())
            }
            withContext(Dispatchers.IO) {
                verifyDomain(scope.context, current, original, receipt, true)
                require(current.migrationPublicationReceipt() == receipt)
                verifyUnknown(current, original)
                scope.view().using { saved ->
                    assertEquals(generation, saved.published(receipt)); assertEquals(candidateCipher, saved.originalCandidateCiphertext(null))
                    saved.originalCandidate(null, receipt) { require(textPins == textDigests(materialize(it)) && binaryPins == binaryDigests(it)) }
                }
                assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() })
            }
            val draftKey = withContext(Dispatchers.Main.immediate) { fresh.text = DRAFT; fresh.targetKey }
            withContext(Dispatchers.IO) { current.awaitQueuedWrites(); require(current.metadata()!!.getJSONObject("drafts").getString(draftKey) == DRAFT) }
            require(ports.attempts.get() == 0)
            owners.close()
            require(owners.stores.size == 27 && owners.sessions.size == 4 && owners.joined == 4 &&
                owners.retiredReturns == 25 && owners.released == 2 && owners.unknownCount == 0)
            val destinationCipher = withContext(Dispatchers.IO) { scope.view().using { it.familyCiphertext() } }
            withContext(Dispatchers.IO) { assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() }) }
            scope.binding.requireCurrent()
            result = Proof(SCHEMA, scope.run, scope.binding, Process.myPid(), startTicks(Process.myPid()), receipt, generation,
                sha(scope.archive), sourceCipher, candidateCipher, destinationCipher, textPins, binaryPins, draftKey,
                owners.stores.size, owners.sessions.size, owners.joined, owners.retiredReturns, owners.released, owners.unknownCount, ports.attempts.get())
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { owners.close(failure) } catch (close: Throwable) { if (failure == null) throw close else if (failure !== close) failure.addSuppressed(close) }
        }
        return requireNotNull(result)
    }

    private suspend fun verifyUnknown(current: NativeSessionStorage, original: Map<String, String>) {
        val name = original.keys.single { it.startsWith("operations:") }
        val before = JSONObject(original.getValue(name)).getJSONArray("operations").getJSONObject(0)
        val row = NativeOperationJournal(current).records(name.removePrefix("operations:")).single()
        require(row.id == before.getString("id") && row.intent == before.getString("intent") && row.intentSha256 == before.getString("intentSha256"))
        require(row.phase == "uncertain" && row.submissionAttempted && row.migrationReadOnly)
        require(row.sourceKey == before.getString("sourceKey") && row.sourceVersion == before.getString("sourceVersion"))
        val client = CodexConnectionVault(current).clients().single()
        require(row.replyRecovery?.mayRetry(client, row) == false)
        assertEquals(original.getValue("submission:${row.id}"), current.read("submission:${row.id}"))
        verifyUnknownRecords(original.keys.associateWith { name -> requireNotNull(current.read(name)) }, original)
    }
    private fun verifyUnknownRecords(active: Map<String, String>, original: Map<String, String>) {
        val key = original.keys.single { it.startsWith("operations:") }; val target = key.removePrefix("operations:")
        val prior = NativeOperationJournal.validateExistingMigrationRecords(target, JSONObject(original.getValue(key))).single()
        val row = NativeOperationJournal.validateExistingMigrationRecords(target, JSONObject(active.getValue(key))).single()
        for (field in listOf("id", "kind", "target", "intent", "intentSha256", "phase", "result", "error", "detached",
            "submissionAttempted", "sourceKey", "sourceVersion", "parentOperationId", "forkContext", "sharingScope"))
            assertEquals(field, canonical(prior.body().get(field)), canonical(row.body().get(field)))
        require(row.migrationReadOnly && row.phase == "uncertain" && row.submissionAttempted)
        val client = decodeClient(JSONObject(active.getValue("metadata")).getJSONArray("clients").getJSONObject(0))
        val recovery = requireNotNull(row.replyRecovery)
        require(recovery.matchesObservation(client, row) && !recovery.mayRetry(client, row))
        require(recovery.phase == "uncertain" && recovery.inspection == null)
        assertEquals(original.getValue("submission:${row.id}"), active.getValue("submission:${row.id}"))
        for (name in original.keys.filter { it.startsWith("draft-file:") || it.startsWith("draft-thumbnail-v1:") })
            assertEquals(original.getValue(name), active.getValue(name))
    }
    private fun materialize(records: NativeMigrationStreamRecords): Map<String, String> = records.text.keys.associateWith { records.text.getValue(it) }
    private fun textDigests(records: Map<String, String>) = records.mapValues { sha(it.value.toByteArray(Charsets.UTF_8)) }
    private fun binaryDigests(records: NativeMigrationStreamRecords): Map<String, String> = records.binary.mapValues { (_, blob) ->
        blob.verifiedCopy(object : java.io.OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, count: Int) {} })
        blob.sha256
    }
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Int, is Long -> value.toString()
        JSONObject.NULL -> "null"
        else -> error("Unsupported fixed-case scalar")
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input ->
        val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }; return digest.digest().joinToString("") { "%02x".format(it) } }
    private fun apkSha(context: Context): String { require(context.applicationInfo.splitSourceDirs.isNullOrEmpty()); return sha(File(context.applicationInfo.sourceDir)) }
    private fun currentBoot() = File("/proc/sys/kernel/random/boot_id").readText().trim()
    private fun startTicks(pid: Int): String {
        val raw = File("/proc/$pid/stat").readText(); require(raw.length in 1..8192 && raw.startsWith("$pid ("))
        return raw.substring(raw.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))[19].also { require(it.matches(Regex("[0-9]+"))) }
    }
}
