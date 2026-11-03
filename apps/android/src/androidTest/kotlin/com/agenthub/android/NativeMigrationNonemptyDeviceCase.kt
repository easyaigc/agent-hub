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
 * Bounded paired real producer -> export -> Session restore -> existing-only cold case.
 * The dedicated SessionTest keeps paired first/cold and pristine Controller selectors separate.
 * Host admission must independently establish exact original-process exit and the fresh cold tuple.
 * Ordinary owners only; this does not prove Controller/strict-generation handoff or OS delivery.
 */
internal object NativeMigrationNonemptyDeviceCase {
    const val SCHEMA = "p7-nonempty-device-session-v2"
    const val COLD_METHOD = "nonemptyReadOnlyColdAfterExactOriginalExit"
    const val FIRST_METHOD = "nonemptyDeviceProducersRestoreActualSession"
    const val PRISTINE_SCHEMA = "p7-nonempty-pristine-controller-v1"
    const val PRISTINE_METHOD = "pristineNonemptyControllerImportsWithoutPrecreatedRootKey"
    const val FOREGROUND_SCHEMA = "p7-nonempty-foreground-controller-v1"
    const val FOREGROUND_METHOD = "nonemptyForegroundControllerProducesPortableArchives"
    const val FOREGROUND_COLD_METHOD = "nonemptyForegroundReadOnlyColdAfterExactOriginalExit"
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
    class Scope(val context: Context, val run: String, val binding: Binding, val guard: () -> Unit, val existingOnly: Boolean = false) {
        private val admittedBinding by lazy { binding.requireCurrent(); true }
        val alias = "p7-nonempty-$run"
        val sourceAlias = "$alias-source"
        val archive: File get() { checkScope(); return File(context.filesDir, "sealed.migration") }
        fun checkScope() {
            require(UUID.fromString(run).toString() == run)
            require(admittedBinding && currentBoot() == binding.bootUuid); guard()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val targetData = File(target.applicationInfo.dataDir).canonicalFile
            val targetFiles = if(existingOnly) File(targetData,"files") else target.filesDir.canonicalFile
            val targetNoBackup = if(existingOnly) File(targetData,"no_backup") else target.noBackupFilesDir.canonicalFile
            val root = File(targetFiles, "P7-nonempty-$run")
            require(context.applicationContext === context)
            require(context.noBackupFilesDir.canonicalFile == File(root, "no_backup"))
            require(context.filesDir.canonicalFile == File(root, "files"))
            require(context.cacheDir.canonicalFile == File(root, "cache"))
            require(context.noBackupFilesDir.canonicalFile != targetNoBackup)
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

    /** Persisted pins describe actual final readback, including the independent new draft. */
    data class Proof(val schema: String, val run: String, val binding: Binding, val pid: Int,
        val startTicks: String, val receipt: NativeMigrationCandidateStore.Receipt, val generation: String,
        val archiveSha256: String, val sourceCiphertext: Map<String, String>,
        val originalCandidateCiphertext: Map<String, String>, val destinationCiphertext: Map<String, String>,
        val originalTextSha256: Map<String, String>, val originalBinarySha256: Map<String, String>,
        val draftKey: String, val draftTextSha256: String,
        val sourceTextSha256: Map<String, String>, val sourceBinarySha256: Map<String, String>,
        val activeTextSha256: Map<String, String>, val activeBinarySha256: Map<String, String>, val acquiredStores: Int, val acquiredSessions: Int,
        val actualSessionJoinReturns: Int, val actualWriterRetireReturns: Int,
        val actualLeaseReleaseReturns: Int, val unknownAcquisitions: Int, val forbiddenPortAttempts: Int)

    /** IO observations bracket Controller confirm/restore; first KEY trigger is unobserved. */
    data class KeyWindow(val point: String, val rootAliasPresent: Boolean, val rootCiphertextCount: Int,
        val destinationCiphertext: Map<String, String>)
    data class PristineProof(val completed: Proof, val keyWindows: List<KeyWindow>,
        val restorePoints: List<String>, val controllerQuiesceReturns: Int, val controllerRestoreReturns: Int,
        val newSessionPublished: Boolean, val freshReady: Boolean, val controllerReady: Boolean,
        val explicitEnrollmentRequired: Boolean, val phase: String, val result: String,
        val existing: ExistingEvidence? = null)
    data class ExistingEvidence(val destinationText: Map<String, String>, val destinationBinary: Map<String, String>,
        val destinationBeforeCiphertext: Map<String, String>, val delegatedChildRetirements: Int)

    fun pristineScenario(scope: Scope): JSONObject = JSONObject()
        .put("entryPath", "already-staged-production-candidate-controller")
        .put("rootAlias", scope.alias).put("sourceAlias", scope.sourceAlias)
        .put("rootNamespace", DESTINATION).put("sourceNamespace", SOURCE)
        .put("safPickerCovered", false).put("uiCovered", false).put("coldProcessCovered", false)

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
        /** A formal actor retires one or two exact cached writers. Its attempt owns the entire
         * group before the call; a partial throw never grants the outer registry retry rights.
         */
        private class ActorRetirement(val name: String, val actor: Any, val handles: List<NativeSessionStorage>,
            val operation: String, val attempt: String) {
            var actorReturned = false
            var bookkeepingReturned = false
        }
        private val actorRetirements = mutableListOf<ActorRetirement>()
        private var actorRetireReturns = 0
        private var actorWriterRetireReturns = 0
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
                actorRetirements.any { !it.bookkeepingReturned && it.handles.any { handle -> handle === store } } ||
                sessionRoots.entries.any { it.value === root && !stopped.contains(it.key) }
        }
        var joined = 0; private set
        var retiredReturns = 0; private set
        var released = 0; private set
        var delegatedChildRetirements = 0; private set
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
        /** Test-only accounting around the original formal call; never closes a producer itself.
         * A returned document/photo call confirms its two sequential retireWriter returns; inbox
         * confirms one. These are not direct outer await/retire calls or actor Job.join evidence.
         */
        fun retireChildActor(name: String, actor: Any, handles: List<NativeSessionStorage>, operation: String, retire: () -> Unit) {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            val count = when (name) { "external-inbox" -> 1; "document-results", "photo-results" -> 2; else -> error("Unknown child retirement group") }
            require(UUID.fromString(operation).toString() == operation && handles.size == count)
            check(handles.indices.all { i -> handles.indices.all { j -> i == j || handles[i] !== handles[j] } })
            check(handles.all { handle -> stores.any { it === handle } && actualRoot(handle) !== handle &&
                !attemptedRetire.contains(handle) && !retired.contains(handle) })
            check(actorRetirements.none { it.name == name || it.actor === actor })
            val held = ActorRetirement(name, actor, handles.toList(), operation, begin("formal-child-retire:$name"))
            actorRetirements.add(held) // Exact actor, all handles and attempt survive any later failure.
            held.handles.forEach { check(attemptedRetire.add(it)) } // All attempts precede the actual formal call.
            retire() // Production sealed/ownedJobs/return-audit checks stay authoritative; exactly once.
            held.actorReturned = true
            actorRetireReturns++
            held.handles.forEach { check(retired.add(it)) } // No group member is marked before the formal return.
            retiredReturns += held.handles.size
            actorWriterRetireReturns += held.handles.size
            check(unknown.remove(held.attempt))
            held.bookkeepingReturned = true
        }
        fun requireChildActorRetirements() {
            check(actorRetirements.map { it.name } == listOf("document-results", "photo-results", "external-inbox"))
            check(actorRetirements.all { it.actorReturned && it.bookkeepingReturned && it.handles.all(retired::contains) })
            check(actorRetireReturns == 3 && actorWriterRetireReturns == 5)
        }
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
        suspend fun freeze(session: MobileSession, operationId: String = UUID.randomUUID().toString()): Held = withContext(NonCancellable + Dispatchers.Main.immediate) {
            val attempt = begin("actual-quiesce+Frozen")
            Held(session.quiesceForMigration(operationId)).also { leases.add(it); unknown.remove(attempt) }
        }
        /** Controller's scalar callback is called only after the production release returned. */
        fun controllerPrepared(held: Held) {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            check(leases.any { it === held } && !held.attempted && !held.returned)
            held.attempted = true; held.returned = true; released++
        }
        suspend fun release(held: Held) = withContext(NonCancellable + Dispatchers.IO) {
            check(!held.attempted); held.attempted = true
            held.lease.prepareAndReleaseForRestore(); held.returned = true; released++
        }
        suspend fun restore(scope: Scope, original: MobileSession, held: Held, ports: Ports,
            retireOldChildren: Map<String, NativeSessionStorage>? = null): Pair<MobileSession, NativeSessionStorage> =
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                check(held.returned)
                val attempt = begin("production-restore-root+children")
                val delegated = DelegatedRestore(original, held.lease.storage, held, attempt)
                delegations.add(delegated) // Includes original owner/root BEFORE invoking production.
                // New purpose only: the old generation owns these exact children until the whole
                // formal restore returns. Partial errors remain delegated; outer cleanup cannot retry.
                val oldChildren = retireOldChildren?.also { children ->
                    require(children.keys == NativeMigrationChildOriginalsProfile.paths.toSet())
                    require(children.values.all { child -> stores.any { it === child } && actualRoot(child) === held.lease.storage &&
                        !attemptedRetire.contains(child) && !retired.contains(child) })
                    val inbox = requireNotNull(held.lease.inboxWriters)
                    require(original.externalInbox === inbox.owner && inbox.childKey == children.getValue("external-inbox").coordinationKey)
                    for (kind in listOf(NativeMigrationSelectionJournal.Kind.DOCUMENT, NativeMigrationSelectionJournal.Kind.PHOTO)) {
                        val ticket = held.lease.selectionWriters.single { it.kind == kind }
                        require(ticket.keys == listOf(children.getValue(kind.namespace).coordinationKey, children.getValue(kind.namespace + "/payloads").coordinationKey))
                    }
                }?.values?.toList().orEmpty()
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
                    if (oldChildren.isNotEmpty()) {
                        // New generation keys made takeInbox/takeSelections refuse old actor
                        // transfer. Original.retireForMigration retired them before helper return.
                        // These are delegated confirmations, never outer direct writer-return counts.
                        require(fresh.externalInbox == null && fresh.documentPicker == null && fresh.photoPicker == null)
                        oldChildren.forEach { child -> check(attemptedRetire.add(child) && retired.add(child)) }
                        delegatedChildRetirements += oldChildren.size
                    }
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
            if (unknown.isNotEmpty() || constructingRoots.isNotEmpty() || delegations.any { !it.transferred } ||
                actorRetirements.any { !it.bookkeepingReturned } || leases.any { !it.returned } ||
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
                            "unresolvedActorRetirements=${actorRetirements.count { !it.bookkeepingReturned }}; " +
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

    private suspend fun seed(context: Context, source: NativeSessionStorage, owner: MobileSession, foreground: Boolean = false) {
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
        if (foreground) seedForeground(source)
    }

    /** Real local producers only. No notification command/HTTP implementation is constructed. */
    private suspend fun seedForeground(source: NativeSessionStorage) {
        val vault = CodexConnectionVault(source); val client = vault.clients().single()
        require(client.endpoint == "https://127.0.0.1:1" && !client.isDemo)
        val saved = NativeChatNotificationSavedConnection.capture(client, requireNotNull(vault.token(client.id)))
        NativeChatNotificationAuthorizations(source).retain(saved)
        val identity = NativeChatNotificationIdentity.load(source)
        val preferences = NativeChatNotificationStore(source).preferences()
        val operation = NativeChatNotificationForegroundStore(source).beginMutation(identity, saved.snapshot, "register", preferences, now)
        val recovery = NativeChatNotificationRecoveryStore(source).reserve(identity, saved.snapshot, UUID.randomUUID().toString(),
            preferences, "register", current = { true })
        // Intentionally no confirm/settle/query/replay. Both originals remain immutable unknown.
        val v1 = NativeChatNotificationForegroundStore(source).view(identity, saved.target)
        val v2 = NativeChatNotificationRecoveryStore(source).view(identity, saved.target)
        require(v1.unknown == operation && v1.mutations.size == 1 && v1.outcomes.isEmpty())
        require(v2.operations.single().let { it.state == "unknown" && it.receipt == null && it.original.encoded() == recovery.encoded() })
        requireForegroundSource(NativeMigrationForeground.ACTIVE_NAMES.associateWith { requireNotNull(source.read(it)) })
    }

    /** Raw inspection never loads an identity, reconstructs a command port or claims a Host response. */
    private fun requireForegroundSource(source: Map<String, String>) {
        require(NativeMigrationForeground.ACTIVE_NAMES.all { it in source })
        val state = NativeMigrationForeground.validateActive(source)
        require(state.nonempty && state.references.size == 1 && state.available == state.references)
        val identity = JSONObject(source.getValue(NativeMigrationForeground.IDENTITY)); require(identity.getBoolean("journalBound"))
        val v1 = JSONObject(source.getValue(NativeMigrationForeground.JOURNAL)).getJSONArray("subscriptions")
        require(v1.length() == 1)
        val mutation = v1.getJSONObject(0).getJSONArray("mutations").also { require(it.length() == 1) }.getJSONObject(0)
        require(mutation.getString("state") == "unknown" && v1.getJSONObject(0).getJSONArray("outcomes").length() == 0)
        val v2 = NativeMigrationForegroundV2.decodeEncoded(source.getValue(NativeMigrationForeground.RECOVERY)).getJSONArray("targets")
        require(v2.length() == 1)
        val operation = v2.getJSONObject(0).getJSONArray("operations").also { require(it.length() == 1) }.getJSONObject(0)
        require(operation.getString("state") == "unknown" && operation.isNull("receipt"))
        require(JSONObject(source.getValue(NativeMigrationForeground.AUTHORIZATIONS)).getJSONArray("authorizations").length() == 1)
    }

    private fun verifyForegroundArchives(source: Map<String, String>, portable: Map<String, String>) {
        requireForegroundSource(source)
        require(NativeMigrationForeground.ACTIVE_NAMES.none { it in portable })
        require(NativeMigrationNotificationArchives.validatePortable(portable).size == 4)
        val rows = JSONObject(portable.getValue(NativeMigrationNotificationArchives.RECORD)).getJSONArray("installations")
        require(rows.length() == 1); val row = rows.getJSONObject(0)
        require(row.getBoolean("requiresOriginalInstallation") && row.getJSONArray("missingAuthorizations").length() == 0)
        val identity = JSONObject(source.getValue(NativeMigrationForeground.IDENTITY))
        require(row.getString("originalIdentitySha256") == sha(source.getValue(NativeMigrationForeground.IDENTITY).toByteArray(Charsets.UTF_8)))
        val context = row.getJSONObject("installation")
        require(context.getString("deviceId") == identity.getString("deviceId") && context.getString("publicKey") == identity.getString("publicKey"))
        for ((kind, name) in listOf("foreground-v1" to NativeMigrationForeground.JOURNAL,
            "recovery-v2" to NativeMigrationForeground.RECOVERY, "authorizations-v1" to NativeMigrationForeground.AUTHORIZATIONS)) {
            val original = source.getValue(name)
            require(row.getString(kind) == sha(original.toByteArray(Charsets.UTF_8)))
            require(portable.getValue(NativeMigrationNotificationArchives.PREFIX + row.getString("archiveId") + ":" + kind) == original)
        }
    }

    private fun verifyForegroundDestination(storage: NativeSessionStorage, source: Map<String, String>, original: Map<String, String>) {
        val active = original.keys.associateWith { requireNotNull(storage.read(it)) }
        verifyForegroundArchives(source, active)
        require(NativeMigrationForeground.ACTIVE_NAMES.all { storage.read(it) == null })
        require(NativeMigrationNotificationArchives.requiresExplicitSetup(storage))
        val audit = NativeMigrationNotificationArchives.readOnlyArchives(storage).single()
        require(audit.requiresOriginalInstallation && audit.missingAuthorizations.isEmpty())
        for ((kind, name) in listOf(NativeMigrationNotificationArchives.Original.FOREGROUND_V1 to NativeMigrationForeground.JOURNAL,
            NativeMigrationNotificationArchives.Original.RECOVERY_V2 to NativeMigrationForeground.RECOVERY,
            NativeMigrationNotificationArchives.Original.AUTHORIZATIONS_V1 to NativeMigrationForeground.AUTHORIZATIONS))
            require(audit.readOnly(kind) == source.getValue(name))
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
        verifyDomainRecords(original.keys.plus(listOf(NativeMigrationDeviceState.AUDIT, NativeMigrationNotificationTombstones.RECORD))
            .associateWith { requireNotNull(current.read(it)) }, original, receipt, afterReconciliation)
        val sourceNotifications = JSONObject(original.getValue(NativeMigrationDeviceState.NOTIFICATIONS))
        val reading = JSONObject(requireNotNull(current.read(NativeMigrationDeviceState.READING)))
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

    private fun verifyDomainRecords(active: Map<String, String>, original: Map<String, String>,
        receipt: NativeMigrationCandidateStore.Receipt, afterReconciliation: Boolean) {
        val sourceReading = JSONObject(original.getValue(NativeMigrationDeviceState.READING))
        val reading = JSONObject(active.getValue(NativeMigrationDeviceState.READING))
        require(reading.getLong("revision") >= sourceReading.getLong("revision") + 1)
        if (!afterReconciliation) require(reading.getJSONArray("scopes").length() == 0 &&
            reading.getLong("revision") == sourceReading.getLong("revision") + 1)
        val sourceRow = sourceReading.getJSONArray("rows").getJSONObject(0)
        val row = reading.getJSONArray("rows").getJSONObject(0)
        require(reading.getJSONArray("rows").length() == 1 && !row.has("pendingReader"))
        for (field in listOf("target", "threadId", "read", "latest", "replyUnread", "notices"))
            assertEquals(field, canonical(sourceRow.get(field)), canonical(row.get(field)))
        val notifications = JSONObject(active.getValue(NativeMigrationDeviceState.NOTIFICATIONS))
        val sourceNotifications = JSONObject(original.getValue(NativeMigrationDeviceState.NOTIFICATIONS))
        val preferences = notifications.getJSONObject("preferences"); val prior = sourceNotifications.getJSONObject("preferences")
        require(!preferences.getBoolean("enabled") && preferences.getLong("generation") == prior.getLong("generation") + 1)
        require(preferences.getString("revision") != prior.getString("revision"))
        for (field in prior.keys().asSequence().filter { it !in setOf("enabled", "generation", "revision") })
            assertEquals(field, canonical(prior.get(field)), canonical(preferences.get(field)))
        require(notifications.getJSONArray("leases").length() == 0 && notifications.getJSONArray("receipts").length() == 0)
        val audit = JSONObject(active.getValue(NativeMigrationDeviceState.AUDIT)).getJSONArray("entries")
        require(audit.length() == 1)
        val entry = audit.getJSONObject(0)
        assertEquals(receipt.id, entry.getString("candidateId")); assertEquals(receipt.sha256, entry.getString("archiveSha256"))
        assertEquals(original.getValue(NativeMigrationDeviceState.READING), entry.getString("reading"))
        assertEquals(original.getValue(NativeMigrationDeviceState.NOTIFICATIONS), entry.getString("notifications"))
        NativeReadingLedger.validateEncoded(active.getValue(NativeMigrationDeviceState.READING))
        NativeChatNotificationStore.validateEncoded(active.getValue(NativeMigrationDeviceState.NOTIFICATIONS))
        val auditRoot = JSONObject(active.getValue(NativeMigrationDeviceState.AUDIT))
        NativeMigrationNotificationTombstones.validate(active, auditRoot)
        val expectedKeys = (0 until sourceNotifications.getJSONArray("receipts").length()).map {
            NativeMigrationNotificationTombstones.key(NativeChatNotificationCodec.receipt(sourceNotifications.getJSONArray("receipts").getJSONObject(it)).event)
        }.toSet()
        require(NativeMigrationNotificationTombstones.decode(active.getValue(NativeMigrationNotificationTombstones.RECORD)) == expectedKeys)
        assertEquals(original.getValue(NativeMigrationSounds.RECORD), active.getValue(NativeMigrationSounds.RECORD))
        if (afterReconciliation) {
            val client = decodeClient(JSONObject(active.getValue("metadata")).getJSONArray("clients").getJSONObject(0))
            val scope = ThreadReadingIdentity.scopeKey(requireNotNull(client.scope))
            require(reading.getJSONArray("scopes").length() == 1 && reading.getJSONArray("scopes").getString(0) == scope)
            require(row.getBoolean("replyUnread") || row.getJSONObject("notices").keys().asSequence().any { !row.getJSONObject("notices").getJSONObject(it).getBoolean("seen") })
        }
    }

    /** Task-owned ciphertext inventory only: never asks an existing-only root reader to open a missing KEY. */
    private fun pristineWindow(scope: Scope, point: String, existingOriginals: Boolean = false): KeyWindow {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()); scope.checkScope()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val present = keys.containsAlias(scope.alias)
        val base = scope.context.noBackupFilesDir
        val inventory = linkedMapOf<String, String>(); var directories = 0; var totalBytes = 0L
        val generations = linkedSetOf<String>()
        fun identity(stat: android.system.StructStat) = listOf(stat.st_dev, stat.st_ino, stat.st_uid.toLong(), stat.st_mode.toLong(), stat.st_mtime, stat.st_ctime)
        fun walk(folder: File) {
            require(++directories <= 64)
            val before = android.system.Os.lstat(folder.path)
            val directory = android.system.OsConstants.S_ISDIR(before.st_mode)
            val owner = before.st_uid == Process.myUid()
            val privateMode = before.st_mode and 0x1ff == 0x1c0
            val canonical = folder.canonicalFile == folder
            require(directory && owner && privateMode && canonical) {
                "Owned ciphertext directory rejected at $point/${folder.name}: directory=$directory, owner=$owner, private=$privateMode, canonical=$canonical"
            }
            NativeMigrationReadLifetime().use({ android.system.Os.open(folder.path,
                android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW, 0) },
                { require(identity(android.system.Os.fstat(it)) == identity(before)) }, { android.system.Os.close(it) })
            val names = requireNotNull(folder.list()).sorted(); require(names.size <= 128)
            for (name in names) {
                val file = File(folder, name); val stat = android.system.Os.lstat(file.path)
                if (android.system.OsConstants.S_ISDIR(stat.st_mode)) {
                    require(folder.name == DESTINATION && name.startsWith("migration-generation-"))
                    val generation = name.removePrefix("migration-generation-"); require(UUID.fromString(generation).toString() == generation)
                    generations.add(generation)
                    walk(file)
                } else {
                    require(name.matches(Regex("[a-f0-9]{64}\\.bin")) && inventory.size < 256)
                    val ref = NativeMigrationReadOnlyFile(folder, file, 23 * 1024 * 1024)
                    require(ref.identity.mode and 0x1ff == 0x180)
                    totalBytes += ref.identity.size; require(totalBytes <= 64L * 1024 * 1024)
                    inventory[file.relativeTo(base).path] = ref.read { it.copyTo(nullOutputStreamCompat()) }.ciphertextSha256
                }
            }
            require(requireNotNull(folder.list()).sorted() == names && identity(android.system.Os.lstat(folder.path)) == identity(before))
        }
        val beforeNames = requireNotNull(base.list()).sorted()
        val controls = mutableListOf<File>()
        beforeNames.filter { it == DESTINATION || it.startsWith("$DESTINATION-") }.forEach { name ->
            val file = File(base, name)
            if (existingOriginals && (name.endsWith(".inbox-writer-v1.lock") ||
                !android.system.OsConstants.S_ISDIR(android.system.Os.lstat(file.path).st_mode))) controls.add(file)
            else walk(file)
        }
        // These five producers use separate, empty coordination files, never ciphertext records.
        // Generation names must already have passed the directory inventory above.
        val controlNamespaces = setOf(DESTINATION) + generations.map { "$DESTINATION-$it" }
        val expectedControls = if (existingOriginals && point != "BEFORE_CONSTRUCTION") controlNamespaces.flatMap { namespace ->
            NativeMigrationChildOriginalsProfile.paths.map { path ->
                "$namespace-${path.replace('/', '-')}.inbox-writer-v1.lock"
            }
        }.toSet() else emptySet()
        for (file in controls) {
            require(file.name in expectedControls && file.canonicalFile == file) { "Unexpected existing-purpose control at $point/${file.name}" }
            // Never open or close a coordination-file FD: that can disturb process record locks.
            val before = android.system.Os.lstat(file.path)
            NativeInboxOwnership.requireControlFile(file)
            require(android.system.OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() &&
                before.st_mode and 0x3f == 0 && before.st_size == 0L && before.st_nlink == 1L)
            NativeInboxOwnership.requireControlFile(file)
            val after = android.system.Os.lstat(file.path)
            require(identity(after) == identity(before) && after.st_size == before.st_size && after.st_nlink == before.st_nlink)
        }
        require(requireNotNull(base.list()).sorted() == beforeNames && keys.containsAlias(scope.alias) == present)
        scope.guard()
        val rootCount = inventory.keys.count { it.startsWith("$DESTINATION/") && it.removePrefix("$DESTINATION/").count { c -> c == '/' } == 0 }
        return KeyWindow(point, present, rootCount, inventory)
    }

    /** Focused normal Controller path. No MainActivity, chooser URI, remote call, strict writer or OS-cold claim. */
    suspend fun pristine(scope: Scope): PristineProof = controllerFirst(scope, false)
    suspend fun foregroundFirst(scope: Scope): PristineProof = controllerFirst(scope, true)
    suspend fun childOriginalsFirst(scope: Scope): PristineProof = controllerFirst(scope, false, childOriginals = true)
    suspend fun existingOriginalsFirst(scope: Scope): PristineProof = controllerFirst(scope, false, childOriginals = true, existingOriginals = true)
    private suspend fun controllerFirst(scope: Scope, foreground: Boolean, childOriginals: Boolean = false, existingOriginals: Boolean = false): PristineProof {
        require((!foreground || !childOriginals) && (!existingOriginals || childOriginals))
        require(!scope.existingOnly)
        val owners = Owners(); val ports = Ports(); val windows = mutableListOf<KeyWindow>()
        val restorePoints = java.util.Collections.synchronizedList(mutableListOf<String>())
        var controller: NativeMigrationController? = null; var lifecycle: NativeMigrationLifecycle? = null
        var controllerHeld: Owners.Held? = null; var controllerQuiesces = 0; var controllerRestores = 0
        var enrollmentRequired: Boolean? = null; var failure: Throwable? = null
        var sourceForeground: Map<String, String> = emptyMap()
        var sourceChildren: NativeMigrationChildOriginalsProfile.Source? = null
        var destinationChildren: NativeMigrationChildOriginalsProfile.Source? = null
        var destinationBaseline: KeyWindow? = null
        var destinationActorsResolved = true
        var destinationActorOwner: MobileSession? = null
        var childActorOwner: MobileSession? = null
        var childActorsRetired = true
        suspend fun before(point: String) = withContext(Dispatchers.IO) {
            pristineWindow(scope, point, existingOriginals).also { window ->
                if (!existingOriginals || point == "BEFORE_CONSTRUCTION") require(!window.rootAliasPresent && window.rootCiphertextCount == 0)
                else {
                    require(window.rootAliasPresent && window.rootCiphertextCount > 0)
                    destinationBaseline?.destinationCiphertext?.forEach { (path, hash) -> require(window.destinationCiphertext.getValue(path) == hash) }
                    if (point == "AFTER_EXISTING_SESSION_SETUP") { check(destinationBaseline == null); destinationBaseline = window }
                }
                windows.add(window)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                scope.checkScope(); require(!scope.archive.exists())
                for (namespace in listOf(SOURCE, DESTINATION)) require(java.nio.file.Files.notExists(
                    File(scope.context.noBackupFilesDir, namespace).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
            before("BEFORE_CONSTRUCTION")
            val source = owners.open("pristine-normal-source") { NativeSessionStorage(scope.context, SOURCE, scope.sourceAlias) }
            val destination = owners.open("pristine-normal-destination") { NativeSessionStorage(scope.context, DESTINATION, scope.alias) }
            withContext(NonCancellable + Dispatchers.IO) {
                val records = MigrationActivationFixtures.payload().getJSONObject("records")
                for (name in records.keys()) source.write(name, records.getString(name))
            }
            owners.open("pristine-source-candidate-cache", source) { source.prepareChild(scope.context, "migration-candidates") }
            owners.open("pristine-destination-candidate-cache", destination) { destination.prepareChild(scope.context, "migration-candidates") }
            val sourceChildStores = linkedMapOf<String, NativeSessionStorage>()
            if (childOriginals) for (path in NativeMigrationChildOriginalsProfile.paths) {
                val parentPath = path.substringBeforeLast('/', "")
                val parent = if (parentPath.isEmpty()) source else sourceChildStores.getValue(parentPath)
                sourceChildStores[path] = owners.open("child-original-producer:$path", source) { parent.prepareChild(scope.context, path.substringAfterLast('/')) }
            }
            val destinationChildStores = linkedMapOf<String, NativeSessionStorage>()
            if (existingOriginals) for (path in NativeMigrationChildOriginalsProfile.paths) {
                val parentPath = path.substringBeforeLast('/', "")
                val parent = if (parentPath.isEmpty()) destination else destinationChildStores.getValue(parentPath)
                destinationChildStores[path] = owners.open("existing-destination-producer:$path", destination) { parent.prepareChild(scope.context, path.substringAfterLast('/')) }
            }
            val sourceOwner = withContext(NonCancellable + Dispatchers.Main.immediate) {
                owners.session(source, ports).also {
                    it.restoreForMigration(); require(it.chatNotifications == null && it.migrationReady)
                    it.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(source))
                    it.voiceSettings.resumePreferences()?.join(); require(it.voiceSettings.persisted)
                }
            }
            withContext(Dispatchers.IO) { seed(scope.context, source, sourceOwner, foreground) }
            if (childOriginals) {
                childActorOwner = sourceOwner // Capture the exact Session before any actor constructor can fail.
                childActorsRetired = false
                NativeMigrationChildOriginalsProfile.configure(scope.context, sourceOwner)
                NativeMigrationChildOriginalsProfile.produce(scope.context, sourceOwner, sourceChildStores, scope.run)
            }
            val destinationOwner = withContext(NonCancellable + Dispatchers.Main.immediate) {
                owners.session(destination, ports).also {
                    it.restoreForMigration(); require(it.chatNotifications == null && it.migrationCanImport)
                    it.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(destination))
                    it.voiceSettings.resumePreferences()?.join(); require(it.voiceSettings.persisted)
                }
            }
            if (existingOriginals) {
                destinationActorOwner = destinationOwner // Before independent actor constructors can throw.
                destinationActorsResolved = false
                withContext(Dispatchers.Main.immediate) {
                    destinationOwner.text = "Owned destination local setup; never submitted"; destinationOwner.text = ""
                    require(destinationOwner.migrationCanImport)
                }
                withContext(Dispatchers.IO) { destination.awaitQueuedWrites(); require(destination.metadata()!!.getJSONArray("clients").length() == 0) }
                NativeMigrationChildOriginalsProfile.configure(scope.context, destinationOwner)
                NativeMigrationChildOriginalsProfile.produce(scope.context, destinationOwner, destinationChildStores, scope.run, variant = 1)
                withContext(Dispatchers.IO) { destinationChildren = NativeMigrationChildOriginalsProfile.source(scope.context, destination) }
                before("AFTER_EXISTING_SESSION_SETUP")
            } else {
                // Old purposes still cannot preinitialize the destination KEY.
                before("AFTER_EMPTY_SESSION_RESTORE")
            }
            val sourceHeld = owners.freeze(sourceOwner)
            val sourceCipher = withContext(Dispatchers.IO) {
                sourceHeld.lease.requireRawWriters(); requireSourceNonempty(source)
                if (foreground) sourceForeground = NativeMigrationForeground.ACTIVE_NAMES.associateWith { requireNotNull(source.read(it)) }
                    .also(::requireForegroundSource)
                if (childOriginals) sourceChildren = NativeMigrationChildOriginalsProfile.source(scope.context, source)
                if (existingOriginals) NativeMigrationExistingOriginalsWitness.requireDistinct(
                    NativeMigrationExistingOriginalsWitness.capture(requireNotNull(sourceChildren)),
                    NativeMigrationExistingOriginalsWitness.capture(requireNotNull(destinationChildren)))
                scope.view(true).using { it.familyCiphertext() }
            }
            val receipt = withContext(Dispatchers.IO) {
                NativeMigrationExport(scope.context, source).sealFile(scope.archive, PASSWORD, sourceHeld.lease)
                NativeMigrationCandidateStore(scope.context, destination).stageFile(UUID.randomUUID().toString(), scope.archive, PASSWORD)
            }
            lateinit var original: Map<String, String>; lateinit var textPins: Map<String, String>; lateinit var binaryPins: Map<String, String>
            val candidateCipher = withContext(Dispatchers.IO) {
                // Ordinary prepared candidate child needs only its own KEY. Never call scope.view() here.
                NativeMigrationStreamCandidate(scope.context, destination).read(receipt) { records ->
                    if (childOriginals) NativeMigrationChildOriginalsProfile.requireSource(requireNotNull(sourceChildren), records)
                    else if (foreground) NativeMigrationNonemptyPairContract.requireForeground(records)
                    else NativeMigrationNonemptyPairContract.requireNine(records)
                    original = materialize(records); textPins = textDigests(original); binaryPins = binaryDigests(records)
                    require(original.keys.intersect(NativeMigrationForeground.ACTIVE_NAMES).isEmpty())
                    if (foreground) verifyForegroundArchives(sourceForeground, original)
                    else require(NativeMigrationNotificationArchives.RECORD !in original)
                    for (name in listOf(NativeMigrationDeviceState.READING, NativeMigrationDeviceState.NOTIFICATIONS, NativeMigrationSounds.RECORD))
                        assertEquals(source.read(name), original.getValue(name))
                }
                val folder = File(scope.context.noBackupFilesDir, "$DESTINATION-migration-candidates")
                val directory = NativeMigrationReadOnlyDirectory(folder)
                directory.files.mapValues { (_, file) -> file.read { it.copyTo(nullOutputStreamCompat()) }.ciphertextSha256 }.also { directory.unchanged() }
            }
            before("AFTER_CANDIDATE_STAGE")
            owners.release(sourceHeld)
            val (sourceFresh, _) = owners.restore(scope, sourceOwner, sourceHeld, ports)
            if (childOriginals) {
                childActorOwner = sourceFresh // Full production transfer returned; these are the same sealed actors.
                NativeMigrationChildOriginalsProfile.retireTransferredSourceActors(sourceFresh, sourceHeld.lease, sourceChildStores,
                    owners::retireChildActor)
                owners.requireChildActorRetirements()
                childActorsRetired = true
            }
            owners.stop(sourceFresh)
            withContext(Dispatchers.IO) { assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() }) }
            var activeSession = destinationOwner; var activeStorage = destination
            val ownedController = withContext(Dispatchers.Main.immediate) {
                NativeMigrationController(scope.context, restoreObserver = { point ->
                    // Synchronous scalar bookkeeping only; no IO or wait at either observer boundary.
                    restorePoints.add(point.name)
                    if (point == NativeMigrationControllerRestorePoint.PREPARE_RETURNED) owners.controllerPrepared(requireNotNull(controllerHeld))
                }).also { controller = it; destinationOwner.migrationController = it }
            }
            val ownedLifecycle = object : NativeMigrationLifecycle {
                override val ready get() = activeSession.migrationReady
                override val canImport get() = activeSession.migrationCanImport
                override fun currentStorage() = activeStorage
                override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
                    check(controllerHeld == null && activeSession === destinationOwner && activeStorage === destination)
                    return owners.freeze(activeSession, operationId).also { controllerHeld = it; controllerQuiesces++ }.lease
                }
                override suspend fun restore(lease: NativeMigrationOwnerLease, requiresExplicitEnrollment: Boolean) {
                    val held = requireNotNull(controllerHeld); check(held.lease === lease && held.returned)
                    check(lease.requiresExplicitEnrollment == requiresExplicitEnrollment && activeSession.ownsMigrationLease(lease))
                    enrollmentRequired = requiresExplicitEnrollment
                    val (fresh, store) = owners.restore(scope, activeSession, held, ports,
                        retireOldChildren = if (existingOriginals) destinationChildStores else null)
                    if (existingOriginals) {
                        require(owners.delegatedChildRetirements == 5)
                        destinationActorsResolved = true
                    }
                    // Register before Controller borrows this same prepared child on return.
                    owners.open("pristine-fresh-candidate-cache", store) { store.prepareChild(scope.context, "migration-candidates") }
                    fresh.migrationController = ownedController
                    activeSession = fresh; activeStorage = store; fresh.resumeSelectionsAfterMigration(); controllerRestores++
                }
            }
            lifecycle = ownedLifecycle
            suspend fun awaitController(wanted: NativeMigrationPhase) = withContext(Dispatchers.Main.immediate) {
                withTimeout(30_000) {
                    while (ownedController.busy || !ownedController.canDismiss) {
                        if (ownedController.phase == NativeMigrationPhase.UNKNOWN ||
                            ownedController.phase == NativeMigrationPhase.FAILED && ownedController.needsOwnerRestore)
                            error("Controller owner unresolved; original operation is retained, never repeated")
                        delay(10)
                    }
                    require(ownedController.phase == wanted) { "Controller phase=${ownedController.phase}; result=${ownedController.result}" }
                }
            }
            withContext(Dispatchers.Main.immediate) { ownedController.attach(ownedLifecycle); ownedController.show() }
            awaitController(NativeMigrationPhase.PREVIEW)
            withContext(Dispatchers.Main.immediate) {
                require(ownedController.ready && ownedController.canImport && ownedController.result == NativeMigrationResult.READY)
                val preview = requireNotNull(ownedController.preview)
                require(preview.computers == 1 && preview.operations == 1 && preview.attachments == 1 && preview.notificationArchives == (if (foreground) 1 else 0) &&
                    preview.drafts == JSONObject(original.getValue("metadata")).getJSONObject("drafts").length())
            }
            before("AFTER_CONTROLLER_PREVIEW")
            before("BEFORE_CONFIRM_IMPORT")
            withContext(Dispatchers.Main.immediate) { ownedController.confirmImport() } // Exactly once; UNKNOWN is never reissued.
            awaitController(NativeMigrationPhase.DONE)
            val ready = withContext(Dispatchers.Main.immediate) {
                require(ownedController.result == NativeMigrationResult.IMPORTED && ownedController.ready && !ownedController.needsOwnerRestore)
                require(activeSession !== destinationOwner && activeStorage !== destination && activeSession.migrationStorage === activeStorage)
                activeSession.awaitMigrationRestore()
                require(activeSession.migrationReady && activeSession.phoneReading.ready && activeSession.phoneReading.failure == null)
                require(activeSession.chatNotifications == null && activeSession.aiSharing.grants.isEmpty())
                activeSession.migrationReady to ownedController.ready
            }
            val after = withContext(Dispatchers.IO) { pristineWindow(scope, "AFTER_CONTROLLER_DONE", existingOriginals).also { windows.add(it) } }
            require(after.rootAliasPresent && after.rootCiphertextCount > 0)
            require(restorePoints.toList() == NativeMigrationControllerRestorePoint.entries.map { it.name })
            require(controllerQuiesces == 1 && controllerRestores == 1 && ports.attempts.get() == 0)
            val generation = withContext(Dispatchers.IO) {
                require(activeStorage.migrationPublicationReceipt() == receipt)
                verifyDomain(scope.context, activeStorage, original, receipt, true); verifyUnknown(activeStorage, original)
                if (foreground) verifyForegroundDestination(activeStorage, sourceForeground, original)
                if (existingOriginals) {
                    require(activeStorage.migrationPendingChildScopes().isEmpty())
                    require(NativeMigrationInbox.read(activeStorage).size == 2)
                    for (kind in listOf(NativeMigrationSelectionJournal.Kind.DOCUMENT, NativeMigrationSelectionJournal.Kind.PHOTO))
                        require(NativeMigrationSelections.read(activeStorage, kind).size == 2)
                    scope.view().using { current -> NativeMigrationExistingOriginalsWitness.requireMerged(
                        NativeMigrationExistingOriginalsWitness.capture(requireNotNull(sourceChildren)),
                        NativeMigrationExistingOriginalsWitness.capture(requireNotNull(destinationChildren)), current.records) }
                } else if (childOriginals) NativeMigrationChildOriginalsProfile.requireDestination(activeStorage, original)
                // The first destination existing-only open occurs strictly after normal Controller publication and restore.
                scope.view().using { saved ->
                    assertEquals(candidateCipher, saved.originalCandidateCiphertext(null))
                    saved.originalCandidate(null, receipt) { require(textPins == textDigests(materialize(it)) && binaryPins == binaryDigests(it)) }
                    saved.published(receipt)
                }
            }
            val draftKey = withContext(Dispatchers.Main.immediate) { activeSession.text = DRAFT; activeSession.targetKey }
            withContext(Dispatchers.IO) { activeStorage.awaitQueuedWrites(); require(activeStorage.metadata()!!.getJSONObject("drafts").getString(draftKey) == DRAFT) }
            withContext(Dispatchers.Main.immediate) { ownedController.detach(ownedLifecycle); ownedController.close() }
            owners.close()
            // Formula follows the actual two restore graphs plus the Controller's retained candidate cache.
            require(owners.stores.size == 4 + 2 * (1 + NativeMigrationExistingPolicy.paths.size) + 1 +
                (if (childOriginals) NativeMigrationChildOriginalsProfile.paths.size else 0) +
                (if (existingOriginals) NativeMigrationChildOriginalsProfile.paths.size else 0) && owners.sessions.size == 4)
            require(owners.joined == owners.sessions.size && owners.retiredReturns + 2 + owners.delegatedChildRetirements == owners.stores.size && owners.released == 2 && owners.unknownCount == 0)
            val finalSource = withContext(Dispatchers.IO) { scope.view(true).using {
                NativeMigrationSounds.validate(materialize(it.records), emptyMap(), it.records.binary)
                Triple(it.familyCiphertext(), textDigests(materialize(it.records)), binaryDigests(it.records))
            } }
            val finalActive = withContext(Dispatchers.IO) { scope.view().using {
                verifyDomainRecords(materialize(it.records), original, receipt, true); verifyUnknownRecords(materialize(it.records), original)
                require(JSONObject(it.records.text.getValue("metadata")).getJSONObject("drafts").getString(draftKey) == DRAFT)
                Triple(it.familyCiphertext(), textDigests(materialize(it.records)), binaryDigests(it.records))
            } }
            val expectedBinary = binaryPins + if (existingOriginals) requireNotNull(destinationChildren).binary.mapValues { sha(it.value) } else emptyMap()
            require(finalSource.first == sourceCipher && finalActive.third == expectedBinary && ports.attempts.get() == 0)
            require(finalSource.third == if (childOriginals) binaryPins.filterKeys { it.startsWith("native-chat-notification-sound-v1:") } else binaryPins)
            if (childOriginals) withContext(Dispatchers.IO) { scope.view().using {
                if (existingOriginals) NativeMigrationExistingOriginalsWitness.requireMerged(
                    NativeMigrationExistingOriginalsWitness.capture(requireNotNull(sourceChildren)),
                    NativeMigrationExistingOriginalsWitness.capture(requireNotNull(destinationChildren)), it.records)
                else NativeMigrationChildOriginalsProfile.requireActive(it.records)
            } }
            if (foreground) {
                require(enrollmentRequired == true)
                withContext(Dispatchers.IO) { scope.view(true).using { sourceView ->
                    scope.view().using { activeView -> verifyForegroundArchives(materialize(sourceView.records), materialize(activeView.records)) }
                } }
            }
            withContext(Dispatchers.IO) { scope.binding.requireCurrent() }
            val completed = withContext(Dispatchers.IO) { Proof(if (existingOriginals) NativeMigrationExistingOriginalsWitness.SCHEMA else if (childOriginals) NativeMigrationChildOriginalsProfile.SCHEMA else if (foreground) FOREGROUND_SCHEMA else PRISTINE_SCHEMA, scope.run, scope.binding, Process.myPid(), startTicks(Process.myPid()), receipt,
                generation, sha(scope.archive), sourceCipher, candidateCipher, finalActive.first, textPins, binaryPins, draftKey,
                sha(DRAFT.toByteArray(Charsets.UTF_8)), finalSource.second, finalSource.third, finalActive.second, finalActive.third,
                owners.stores.size, owners.sessions.size, owners.joined, owners.retiredReturns, owners.released, owners.unknownCount, ports.attempts.get()) }
            return PristineProof(completed, windows.toList(), restorePoints.toList(), controllerQuiesces, controllerRestores,
                true, ready.first, ready.second, requireNotNull(enrollmentRequired), NativeMigrationPhase.DONE.name, NativeMigrationResult.IMPORTED.name,
                if (existingOriginals) ExistingEvidence(textDigests(requireNotNull(destinationChildren).text),
                    requireNotNull(destinationChildren).binary.mapValues { sha(it.value) }, requireNotNull(destinationBaseline).destinationCiphertext,
                    owners.delegatedChildRetirements) else null)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val unresolved = withContext(NonCancellable + Dispatchers.Main.immediate) {
                controller?.let { it.busy || !it.canDismiss || it.needsOwnerRestore || it.phase == NativeMigrationPhase.UNKNOWN } == true
            }
            if (unresolved) withContext(NonCancellable + Dispatchers.IO) {
                // Do not cancel Controller work, release its unknown lease, stop its Session or erase the original candidate.
                val lifetime = if (childOriginals) NativeMigrationChildOriginalsProfile.retainingLifetime(scope.run) else NativeMigrationReadLifetime()
                lifetime.hold(listOf(owners, requireNotNull(controller), lifecycle, controllerHeld, scope),
                    failure ?: IllegalStateException("Unresolved pristine Controller owner; retain exact run until original PID exit"))
            }
            sourceChildren?.close(); destinationChildren?.close()
            if (!destinationActorsResolved) withContext(NonCancellable + Dispatchers.IO) {
                NativeMigrationChildOriginalsProfile.retainingLifetime(scope.run).hold(listOf(owners, destinationActorOwner, controllerHeld, scope),
                    failure ?: IllegalStateException("Unresolved existing destination producer/restore owner; retain exact original PID"))
            }
            if (!childActorsRetired) withContext(NonCancellable + Dispatchers.IO) {
                // A partial independent actor construction/transfer grants no fabricated close receipt.
                val lifetime = if (childOriginals) NativeMigrationChildOriginalsProfile.retainingLifetime(scope.run) else NativeMigrationReadLifetime()
                lifetime.hold(listOf(owners, childActorOwner, scope),
                    failure ?: IllegalStateException("Unresolved child producer actor retirement; retain exact original PID"))
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) { controller?.let { value -> lifecycle?.let(value::detach); value.close() } }
            try { owners.close(failure) } catch (close: Throwable) { if (failure == null) throw close else if (failure !== close) failure.addSuppressed(close) }
        }
    }

    suspend fun first(scope: Scope): Proof {
        withContext(Dispatchers.IO) {
            scope.checkScope(); require(!scope.archive.exists())
            for (namespace in listOf(SOURCE, DESTINATION)) require(!File(scope.context.noBackupFilesDir, namespace).exists())
        }
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
                    NativeMigrationNonemptyPairContract.requireNine(records); original = materialize(records); textPins = textDigests(original); binaryPins = binaryDigests(records)
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
            val finalSource = withContext(Dispatchers.IO) { scope.view(true).using {
                NativeMigrationSounds.validate(materialize(it.records), emptyMap(), it.records.binary)
                Triple(it.familyCiphertext(), textDigests(materialize(it.records)), binaryDigests(it.records))
            } }
            val finalActive = withContext(Dispatchers.IO) { scope.view().using {
                verifyDomainRecords(materialize(it.records), original, receipt, true)
                verifyUnknownRecords(materialize(it.records), original)
                require(JSONObject(it.records.text.getValue("metadata")).getJSONObject("drafts").getString(draftKey) == DRAFT)
                Triple(it.familyCiphertext(), textDigests(materialize(it.records)), binaryDigests(it.records))
            } }
            val destinationCipher = finalActive.first
            require(finalSource.first == sourceCipher && finalSource.third == binaryPins && finalActive.third == binaryPins)
            withContext(Dispatchers.IO) { assertEquals(sourceCipher, scope.view(true).using { it.familyCiphertext() }) }
            withContext(Dispatchers.IO) { scope.binding.requireCurrent() }
            val sealedHash = withContext(Dispatchers.IO) { sha(scope.archive) }
            result = withContext(Dispatchers.IO) { Proof(SCHEMA, scope.run, scope.binding, Process.myPid(), startTicks(Process.myPid()), receipt, generation,
                sealedHash, sourceCipher, candidateCipher, destinationCipher, textPins, binaryPins, draftKey,
                sha(DRAFT.toByteArray(Charsets.UTF_8)), finalSource.second, finalSource.third, finalActive.second, finalActive.third,
                owners.stores.size, owners.sessions.size, owners.joined, owners.retiredReturns, owners.released, owners.unknownCount, ports.attempts.get()) }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { owners.close(failure) } catch (close: Throwable) { if (failure == null) throw close else if (failure !== close) failure.addSuppressed(close) }
        }
        return requireNotNull(result)
    }

    /** IO only. Returned ExistingViews are cleaned once; a constructor that never returned remains UNKNOWN. */
    suspend fun cold(scope: Scope, first: Proof): NativeMigrationNonemptyPairContract.ColdPins = coldRead(scope, first, false)
    suspend fun foregroundCold(scope: Scope, first: Proof): NativeMigrationNonemptyPairContract.ColdPins = coldRead(scope, first, true)
    suspend fun childOriginalsCold(scope: Scope, first: Proof): NativeMigrationNonemptyPairContract.ColdPins = coldRead(scope, first, false, childOriginals = true)
    suspend fun existingOriginalsCold(scope: Scope, first: Proof): NativeMigrationNonemptyPairContract.ColdPins = coldRead(scope, first, false, childOriginals = true, existingOriginals = true)
    private suspend fun coldRead(scope: Scope, first: Proof, foreground: Boolean, childOriginals: Boolean = false, existingOriginals: Boolean = false): NativeMigrationNonemptyPairContract.ColdPins =
        withContext(NonCancellable + Dispatchers.IO) {
            scope.checkScope(); require(scope.existingOnly); require(first.schema == (if (existingOriginals) NativeMigrationExistingOriginalsWitness.SCHEMA else if (childOriginals) NativeMigrationChildOriginalsProfile.SCHEMA else if (foreground) FOREGROUND_SCHEMA else SCHEMA) && first.run == scope.run && first.binding == scope.binding)
            val owners = NativeMigrationNonemptyPairContract.ReadOwners(scope)
            var primary: Throwable? = null
            var result: NativeMigrationNonemptyPairContract.ColdPins? = null
            try {
                val archived = NativeMigrationNonemptyPairContract.readArchive(scope, PASSWORD, first)
                val original = archived.text
                var sourceForeground: Map<String, String> = emptyMap()
                owners.read(true) { source ->
                    assertEquals(first.sourceCiphertext, source.familyCiphertext())
                    assertEquals(first.sourceTextSha256, textDigests(materialize(source.records)))
                    assertEquals(first.sourceBinarySha256, binaryDigests(source.records))
                    NativeMigrationSounds.validate(materialize(source.records), emptyMap(), source.records.binary)
                    for (name in listOf(NativeMigrationDeviceState.READING, NativeMigrationDeviceState.NOTIFICATIONS, NativeMigrationSounds.RECORD))
                        assertEquals(original.getValue(name), source.records.text.getValue(name))
                    if (foreground) sourceForeground = materialize(source.records).also { verifyForegroundArchives(it, original) }
                    source.unchanged()
                } // Original assertion is passed to source close; its real close returns before destination acquisition.
                result = owners.read(false) { current ->
                    assertEquals(first.generation, current.published(first.receipt))
                    assertEquals(first.originalCandidateCiphertext, current.originalCandidateCiphertext(null))
                    current.originalCandidate(null, first.receipt) { records ->
                        if (childOriginals) NativeMigrationChildOriginalsProfile.requirePortable(records)
                        else if (foreground) NativeMigrationNonemptyPairContract.requireForeground(records)
                        else NativeMigrationNonemptyPairContract.requireNine(records)
                        assertEquals(first.originalTextSha256, textDigests(materialize(records)))
                        assertEquals(first.originalBinarySha256, binaryDigests(records))
                        assertEquals(original, materialize(records))
                }
                val active = materialize(current.records)
                assertEquals(first.destinationCiphertext, current.familyCiphertext())
                assertEquals(first.activeTextSha256, textDigests(active))
                assertEquals(first.activeBinarySha256, binaryDigests(current.records))
                verifyDomainRecords(active, original, first.receipt, true); verifyUnknownRecords(active, original)
                if (foreground) verifyForegroundArchives(sourceForeground, active)
                if (existingOriginals) NativeMigrationExistingOriginalsWitness.requireActive(current.records)
                else if (childOriginals) NativeMigrationChildOriginalsProfile.requireActive(current.records)
                val draft = JSONObject(active.getValue("metadata")).getJSONObject("drafts").get(first.draftKey) as String
                require(draft == DRAFT && sha(draft.toByteArray(Charsets.UTF_8)) == first.draftTextSha256)
                NativeMigrationSounds.validate(active, emptyMap(), current.records.binary)
                current.unchanged()
                NativeMigrationNonemptyPairContract.ColdPins(archived.clearSha256, archived.sealedSha256,
                    first.generation, first.draftKey, first.draftTextSha256, textDigests(active), binaryDigests(current.records))
                }
            } catch (error: Throwable) { primary = error; throw error }
            finally { owners.close(primary) }
            require(owners.closeReturns == 2)
            requireNotNull(result).copy(readerCloseReturns=owners.closeReturns)
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
