package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Session-owned foreground lifecycle, settings and opaque-ticket ingress. No Activity, owner or execution lookup. */
internal class NativeChatNotificationCoordinator(parentScope: CoroutineScope, private val session: NativeChatNotificationSessionPort,
    val system: NativeChatNotificationSystem, private val commands: NativeChatNotificationCommands = NativeChatNotificationHttp(),
    private val locator: NativeChatNotificationThreadLocator = NativeChatNotificationThreadLocator(),
    private val now: () -> Long = System::currentTimeMillis, private val pollMillis: Long = 10_000,
    private val applicationId: String, migrationEnrollmentCheck: Boolean=false) {
    companion object {
        fun create(context: Context, scope: CoroutineScope, session: NativeChatNotificationSessionPort, language: String): NativeChatNotificationCoordinator {
            val app = context.applicationContext
            val system = NativeChatNotificationPlatform(app, NativeL10n(app, language), NativeChatNotificationSoundLibrary(app, session.storage))
            return NativeChatNotificationCoordinator(scope, session, system, applicationId = app.packageName, migrationEnrollmentCheck=true)
        }
    }
    private val ownerJob=SupervisorJob(parentScope.coroutineContext[Job])
    private val migrationIo=NativeMigrationIoWork()
    private val scope=CoroutineScope(parentScope.coroutineContext+ownerJob+migrationIo)
    val store = NativeChatNotificationStore(session.storage)
    private val legacy = NativeChatNotificationForegroundStore(session.storage)
    private val recovery = NativeChatNotificationRecoveryStore(session.storage)
    private val authorities = NativeChatNotificationAuthorizations(session.storage)
    private val presentation = NativeChatNotificationPresentationFence()
    private val navigation = NativeChatNotificationPresentationFence()
    private val originalReads = NativeChatNotificationPresentationFence()
    private val delivery = NativeChatNotificationForegroundDelivery(store, system, session.reading, session::reconcileActive, now)
    private class Worker(var connection: NativeChatNotificationSavedConnection, val monitor: NativeChatNotificationForegroundMonitor) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
    }
    private val workers = mutableMapOf<NativeChatNoticeTarget, Worker>()
    val reports = mutableStateMapOf<NativeChatNoticeTarget, NativeChatNotificationMonitorReport>()
    val originalReports = mutableStateMapOf<NativeChatNoticeTarget, NativeChatNotificationMonitorReport>()
    val querying = mutableStateListOf<NativeChatNoticeTarget>()
    var computers by mutableStateOf<List<NativeChatNotificationComputer>>(emptyList()); private set
    var unavailable by mutableStateOf(false); private set
    var opening by mutableStateOf(false); private set
    var canRetryOpen by mutableStateOf(false); private set
    var openState by mutableStateOf<String?>(null); private set
    private var state = session.state()
    @Volatile private var readyForeground = state.ready && state.foreground
    private var refreshJob: Job? = null
    private var refreshOwner: Any? = null
    private var refreshWanted = false
    private var openJob: Job? = null
    private var openOwner: Any? = null
    private val actionJobs = mutableMapOf<String, Job>()
    @Volatile private var blocked = false
    private data class OpenRequest(val ticket: String, val generation: Long)
    private val pendingTickets = ArrayDeque<OpenRequest>()
    private var openGeneration = 0L
    private var activeOpenRequest: OpenRequest? = null
    private val observedTickets = linkedSetOf<String>()
    private var lastFailedTicket: String? = null
    private val migrationEnrollment=if(migrationEnrollmentCheck)NativeMigrationNotificationEnrollment(session.storage,scope)else null
    internal val migrationNeedsSetup get()=migrationEnrollment?.required==true
    internal val migrationInteractionReady get()=migrationEnrollment?.initialized!=false
    internal val migrationEnrollmentFailed get()=migrationEnrollment?.failed==true
    internal fun retryMigrationEnrollment(){main();invalidateBeforeChange();stopWorkers();migrationEnrollment?.refresh {if(!blocked)reconcile()}}
    private var migrationEnrollmentStarted=false
    private fun main() = check(Looper.myLooper() == Looper.getMainLooper())
    init { main() }
    private suspend fun <T> io(action: () -> T) = withContext(Dispatchers.IO) { action() }
    private fun originalsAvailable() = !blocked && readyForeground && migrationEnrollment?.initialized!=false && migrationEnrollment?.failed!=true
    private fun available() = originalsAvailable() && migrationEnrollment?.allowed!=false
    private fun observeState(): NativeChatNotificationSessionState {
        main(); val value = session.state()
        if (state.navigation != value.navigation || state.ready != value.ready || state.foreground != value.foreground) navigation.invalidate()
        if (state != value) { invalidateBeforeChange(); state = value }
        readyForeground = value.ready && value.foreground
        if (!readyForeground) originalReads.invalidate()
        return value
    }
    /** Called synchronously BEFORE navigation/reading visibility/credentials/preferences/foreground/migration mutation. */
    fun invalidateBeforeChange() { main(); presentation.invalidate(); store.invalidatePresentation() }
    fun navigationWillChange() { main(); invalidateBeforeChange(); navigation.invalidate() }
    /** Stop captured-authority workers BEFORE a token/certificate/target/storage generation changes. */
    fun connectionsWillChange() { main(); navigationWillChange(); stopWorkers() }
    /** Repeated ordinary workspace/history publications do not restart workers or shorten their poll interval. */
    fun reconcile() {
        main(); if (blocked) return
        val value = observeState()
        if(value.ready&&value.foreground&&migrationEnrollment!=null&&!migrationEnrollmentStarted){
            migrationEnrollmentStarted=true;migrationEnrollment.refresh {if(!blocked)reconcile()}
        }
        if(migrationEnrollment?.failed==true)unavailable=true
        if (!value.ready || !value.foreground || !originalsAvailable()) {
            stopWorkers(); refreshJob?.cancel(); refreshJob = null
            refreshOwner = null; openJob?.cancel(); openJob = null; openOwner = null; opening = false
            return
        }
        if (!available()) {
            stopWorkers();openJob?.cancel();openJob=null;openOwner=null;opening=false
        }
        if (!value.readingReady) {
            stopWorkers()
        }
        refreshWanted = true
        if (refreshJob?.isActive != true) {
            val owner = Any(); refreshOwner = owner
            refreshJob = scope.launch(Dispatchers.Main.immediate) {
            var attempt: NativeChatNotificationPresentationFence.Lease? = null
            try {
                while (refreshWanted && originalsAvailable()) {
                    refreshWanted = false
                    val lease = presentation.capture()
                    attempt = lease
                    val preferences = io { store.preferences() }
                    val history = io { legacy.exists() || recovery.exists() }
                    if (!lease.current() || !originalsAvailable()) continue
                    if (history) refreshOriginalInventory(lease)
                    // Closed enrollment still permits local original inventory; never captures a new connection or starts a worker.
                    if (!available()) {stopWorkers();unavailable=false;continue}
                    // No token/identity/journal/registration work for an untouched disabled installation.
                    if (!preferences.enabled && !history) { stopWorkers(); unavailable = false; continue }
                    val saved = session.savedConnections()
                    require(saved.size <= 64 && saved.map { it.target }.distinct().size == saved.size)
                    if (!lease.current() || !available()) continue
                    computers = saved.map { NativeChatNotificationComputer(it.target, it.client.name) }
                    if (!session.state().readingReady) { stopWorkers(); unavailable = false; continue }
                    applyConnections(saved) { lease.current() && available() }
                    if (!lease.current() || !available()) continue
                    unavailable = false
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (refreshOwner === owner && originalsAvailable()) {
                    if (attempt?.current() == true) { unavailable = true; invalidateBeforeChange(); stopWorkers() } else refreshWanted = true
                }
            }
            finally { if (refreshOwner === owner) {
                refreshOwner = null; refreshJob = null; drainTickets()
                if (refreshWanted && originalsAvailable()) scope.launch(Dispatchers.Main.immediate) { yield(); reconcile() }
            } }
            }
        }
        drainTickets()
    }
    /** Metadata-only settings list; supplied from actual realClients without reading credentials or making an identity. */
    fun updateComputers(clients: List<MobileClient>) {
        main(); computers = clients.filter { !it.isDemo && it.scope?.agentId == "codex" && it.agentConnection == null }
            .map { NativeChatNotificationComputer(NativeChatNoticeTarget.from(requireNotNull(it.scope)), it.name) }.distinctBy { it.target }
    }
    private fun monitor() = NativeChatNotificationForegroundMonitor(scope, session.storage, store, legacy, commands, delivery, now)
    private suspend fun refreshOriginalInventory(lease: NativeChatNotificationPresentationFence.Lease) {
        val inventory = io {
            val old = legacy.pendingByTarget(); val modern = recovery.pendingByTarget()
            (old.keys + modern.keys).associateWith { old[it].orEmpty() + modern[it].orEmpty() }
        }
        if (!lease.current() || !originalsAvailable()) return
        for ((target, originals) in inventory) {
            val old = originalReports[target]
            originalReports[target] = if (old == null) NativeChatNotificationMonitorReport("original-records-retained", target, unconfirmedOperations = originals.size)
                else old.copy(unconfirmedOperations = originals.size,
                    state = if (originals.isEmpty() && old.state.contains("unknown")) "original-records-retained" else old.state)
        }
    }
    private suspend fun recoveryConnections(): List<NativeChatNotificationSavedConnection> = try { session.savedConnections() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() } // A missing current credential never substitutes for an original retained one.
    private suspend fun applyConnections(saved: List<NativeChatNotificationSavedConnection>, current: () -> Boolean) {
        if (!current()) return
        val next = saved.associateBy { it.target }
        val changed = workers.filter { (target, worker) -> next[target]?.workerKey != worker.connection.workerKey }.keys
        if (changed.isNotEmpty()) {
            connectionsWillChange(); changed.forEach(system::cancelOwned); refreshWanted = true; return
        }
        for (connection in saved) {
            if (!current()) return
            val existing = workers[connection.target]
            if (existing != null) { existing.connection = connection; continue }
            // Must succeed BEFORE synchronize can prepare its first unknown mutation/HTTP write.
            io { authorities.retain(connection) }
            if (!current()) return
            val worker = Worker(connection, monitor())
            // Publish a cancellable owner before Main.immediate can synchronously reenter stopWorkers.
            val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                while (isActive && available() && session.state().readingReady && workers[connection.target] === worker) {
                    val lease = presentation.capture()
                    val value = try { worker.monitor.synchronize(worker.connection.snapshot, lease) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { NativeChatNotificationMonitorReport("read-unavailable", connection.target) }
                    if (!isActive || !available() || !session.state().readingReady || workers[connection.target] !== worker) break
                    if (lease.current()) reports[connection.target] = value
                    val signal = withTimeoutOrNull(pollMillis) { worker.wake.receiveCatching() }
                    if (signal?.isClosed == true) {
                        signal.exceptionOrNull()?.let { throw it }
                        break
                    }
                }
            }
            worker.job = job; workers[connection.target] = worker
            job.start()
        }
    }
    private fun stopWorkers() {
        workers.values.forEach { it.monitor.stop(); it.job?.cancel(); it.wake.close() }; workers.clear()
    }
    fun preferencesSaved() { main(); invalidateBeforeChange();
        val gate=migrationEnrollment
        if(gate!=null){stopWorkers();gate.refresh {if(!blocked)reconcile()}}
        else {workers.values.forEach {it.wake.trySend(Unit)};reconcile()}
    }
    fun languageChanged(selection: String) { main(); invalidateBeforeChange(); system.languageChanged(selection) }
    /** Original-ID/status reads only, including encrypted retained credentials after rotation/removal. */
    fun queryOriginals(target: NativeChatNoticeTarget) {
        main(); observeState(); if (!originalsAvailable() || target in querying) return
        querying.add(target)
        scope.launch(Dispatchers.Main.immediate) {
            val lease = originalReads.capture()
            try {
                val required = originalAuthorizations(target)
                val retained = io { authorities.all() }.filter { it.target == target }.associateBy { it.snapshot.authorizationFingerprint }
                val captured = if ((required - retained.keys).isEmpty()) emptyMap() else recoveryConnections().filter { it.target == target }.associateBy { it.snapshot.authorizationFingerprint }
                val sources = retained + captured
                if ((required - sources.keys).isNotEmpty()) {
                    originalReports[target] = NativeChatNotificationMonitorReport("original-credential-recovery-required", target, unconfirmedOperations = required.size)
                    return@launch
                }
                for (authorization in required) {
                    if (!lease.current() || !originalsAvailable()) return@launch
                    val value = monitor().queryOriginals(requireNotNull(sources[authorization]).snapshot, lease)
                    if (lease.current() && originalsAvailable()) originalReports[target] = value
                }
                if (required.isEmpty() && lease.current()) originalReports[target] = NativeChatNotificationMonitorReport("original-query-complete", target)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (lease.current()) originalReports[target] = NativeChatNotificationMonitorReport("original-query-unavailable", target) }
            finally { querying.remove(target) }
        }
    }
    private suspend fun originalAuthorizations(target: NativeChatNoticeTarget): Set<String> {
        if (!io { legacy.exists() || recovery.exists() }) return emptySet()
        val identity = NativeChatNotificationIdentity.load(session.storage)
        return io {
            legacy.view(identity, target).unknown?.authorization?.let(::setOf).orEmpty() +
                recovery.view(identity, target).operations.filter { it.state in setOf("unknown", "reserved") }.map { it.authorization }
        }
    }
    private fun action(id: String, enrollment: Boolean = true, body: suspend (NativeChatNotificationPresentationFence.Lease) -> Unit) {
        main(); observeState(); NativeChatNotificationWire.uuid(id); if (!(if(enrollment)available() else originalsAvailable()) || actionJobs[id]?.isActive == true) return
        actionJobs[id] = scope.launch(Dispatchers.Main.immediate) {
            val lease = presentation.capture()
            try { body(lease) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (lease.current()) unavailable = true }
            finally { actionJobs.remove(id) }
        }
    }
    /** Callback from the actual settings page AFTER its durable off save, with the same root UUID on redelivery. */
    fun explicitDisable(hostId: String?, savedRevision: String, actionId: String) {
        main(); invalidateBeforeChange()
        action(actionId, enrollment=false) { lease ->
            if(available())performDisable(hostId, savedRevision, actionId, lease)
            else queryRetainedOriginals(hostId,lease)
        }
    }
    /** With enrollment closed an off callback can only inspect retained local originals, never reserve a new revoke. */
    private suspend fun queryRetainedOriginals(hostId: String?,lease: NativeChatNotificationPresentationFence.Lease) {
        val targets=io {(legacy.pendingByTarget().keys+recovery.pendingByTarget().keys).toSet()}
        if(!lease.current()||!originalsAvailable())return
        targets.filter {hostId==null||it.hostId==hostId}.forEach(::queryOriginals)
    }
    private suspend fun performDisable(hostId: String?, savedRevision: String, actionId: String, lease: NativeChatNotificationPresentationFence.Lease) {
            val preferences = io { store.preferences() }
            require(preferences.revision == savedRevision && (!preferences.enabled || hostId in preferences.excludedHosts))
            val sources = io { authorities.all() } + recoveryConnections()
            val targets = (sources.map { it.target } + originalReports.keys + computers.map { it.target }).distinct().filter { hostId == null || it.hostId == hostId }
            for (target in targets) {
                val choices = sources.filter { it.target == target }
                if (!lease.current() || !available()) return
                val id = NativeChatNotificationRecoveryController.targetActionId(actionId, target)
                val original = if (io { recovery.exists() }) {
                    val identity = NativeChatNotificationIdentity.load(session.storage)
                    io { recovery.view(identity, target).operations.singleOrNull { it.original.operationId == id } }
                } else null
                val connection = if (original == null) choices.lastOrNull() else choices.lastOrNull { it.snapshot.authorizationFingerprint == original.authorization }
                if (connection == null) {
                    originalReports[target] = NativeChatNotificationMonitorReport("original-credential-recovery-required", target, id, unconfirmedOperations = 1); continue
                }
                io { authorities.retain(connection) }
                val value = monitor().explicitDisable(connection.snapshot, savedRevision, id, lease)
                if (lease.current() && available()) reports[target] = value
            }
            if (lease.current()) reconcile()
    }
    fun disableComputer(target: NativeChatNoticeTarget, actionId: String) {
        main(); invalidateBeforeChange()
        action(actionId, enrollment=false) { lease ->
            if(!available()){queryOriginals(target);return@action}
            val id = NativeChatNotificationRecoveryController.targetActionId(actionId, target)
            if (io { recovery.exists() }) {
                val identity = NativeChatNotificationIdentity.load(session.storage)
                val existing = io { recovery.view(identity, target).operations.singleOrNull { it.original.operationId == id } }
                if (existing != null) { queryOriginals(target); return@action }
            }
            val saved = io { check(lease.current()); store.update { it.copy(excludedHosts = it.excludedHosts + target.hostId) } }
            if (lease.current() && available()) performDisable(target.hostId, saved.revision, actionId, lease)
        }
    }
    fun acceptGap(target: NativeChatNoticeTarget, offer: NativeChatRecoveryGapOffer, actionId: String) = action(actionId) { lease ->
        val identity = NativeChatNotificationIdentity.load(session.storage)
        val original = io { recovery.view(identity, target).operations.single { it.original.operationId == offer.operationId } }
        val sources = io { authorities.all() } + recoveryConnections()
        val connection = sources.lastOrNull { it.target == target && it.snapshot.authorizationFingerprint == original.authorization }
        if (connection == null) { originalReports[target] = NativeChatNotificationMonitorReport("original-credential-recovery-required", target); return@action }
        val value = monitor().acceptGap(connection.snapshot, offer, actionId, lease)
        if (lease.current() && available()) { reports[target] = value; workers[target]?.wake?.trySend(Unit) }
    }
    /** Recognized invalid notification Intents are consumed here; never fall through to pairing/attachment links. */
    fun receiveIntent(intent: Intent): Boolean {
        main(); if (intent.action != NativeChatNotificationPlatform.ACTION) return false
        observeState()
        val ticket = runCatching {
            require(intent.clipData == null && intent.selector == null && intent.categories.isNullOrEmpty())
            require(intent.extras?.keySet() == setOf(NativeChatNotificationPlatform.TICKET))
            val value = NativeChatNotificationWire.uuid(requireNotNull(intent.getStringExtra(NativeChatNotificationPlatform.TICKET)))
            require(intent.data?.toString() == "agenthub-chat-notice://$applicationId/$value")
            intent.component?.let { require(it.packageName == applicationId && it.className == MainActivity::class.java.name) }
            value
        }.getOrNull()
        if (ticket != null && ticket in observedTickets) return true
        // A newer user tap supersedes an older lookup; its late result cannot navigate or replace the new status.
        navigationWillChange(); openGeneration++
        activeOpenRequest?.let { observedTickets.remove(it.ticket) }
        pendingTickets.forEach { observedTickets.remove(it.ticket) }; pendingTickets.clear()
        openJob?.cancel(); openJob = null; openOwner = null; activeOpenRequest = null; opening = false
        lastFailedTicket = null; canRetryOpen = false
        if (ticket == null) { openState = "unavailable"; return true }
        observedTickets.add(ticket)
        while (observedTickets.size > 64) observedTickets.remove(observedTickets.first())
        pendingTickets.addLast(OpenRequest(ticket, openGeneration)); openState = "waiting-for-restoration"; drainTickets(); return true
    }
    fun retryOpen() {
        main(); observeState(); val ticket = lastFailedTicket ?: return
        if (!available() || pendingTickets.any { it.ticket == ticket }) return
        navigationWillChange(); openGeneration++
        pendingTickets.addLast(OpenRequest(ticket, openGeneration)); lastFailedTicket = null; canRetryOpen = false; drainTickets()
    }
    fun dismissOpenStatus() { main(); openState = null }
    private fun drainTickets() {
        main(); if (!available() || openJob?.isActive == true || pendingTickets.isEmpty()) return
        val owner = Any(); openOwner = owner
        openJob = scope.launch(Dispatchers.Main.immediate) {
            opening = true
            try {
                while (available() && pendingTickets.isNotEmpty()) {
                    val request = pendingTickets.removeFirst(); activeOpenRequest = request; val lease = navigation.capture()
                    val opened = try { openTicket(request.ticket, lease) }
                    catch (cancel: CancellationException) { if (request.generation == openGeneration && !blocked) pendingTickets.addFirst(request); throw cancel }
                    catch (_: Exception) { false }
                    if (request.generation == openGeneration) {
                        lastFailedTicket = request.ticket.takeUnless { opened }; canRetryOpen = !opened
                        openState = if (opened) "opened" else "unavailable"
                    }
                    if (activeOpenRequest === request) activeOpenRequest = null
                }
            } finally {
                if (openOwner === owner) { opening = false; openJob = null; openOwner = null; activeOpenRequest = null }
                if (available() && pendingTickets.isNotEmpty() && openJob?.isActive != true)
                    scope.launch(Dispatchers.Main.immediate) { yield(); drainTickets() }
            }
        }
    }
    private suspend fun openTicket(ticket: String, lease: NativeChatNotificationPresentationFence.Lease): Boolean {
        val current = { available() && lease.current() }
        val receipt = io { if (current()) store.ticket(ticket, now()) else null } ?: return false
        val source = session.savedConnections().singleOrNull { it.target == receipt.event.target && it.snapshot.credentialEpoch == receipt.lease.credentialEpoch } ?: return false
        if (!current()) return false
        val location = locator.locate(source, receipt.event, current) ?: return false
        val original = io { if (!current() || !store.preferences().permits(receipt.event)) null else store.consume(ticket, now(), current) } ?: return false
        return withContext(Dispatchers.Main.immediate) { current() && original.event == receipt.event && session.openExistingThread(location, current) }
    }
    /** Migration must replace the coordinator with the newly active Session/storage/ledger. */
    /** Joins every owned worker/query/action and its actual IO completion; cancellation alone is not completion. */
    internal suspend fun stopAndJoinForMigration(){main();dispose();ownerJob.join();migrationIo.awaitIdle()}
    fun dispose() {
        main(); blocked = true;migrationEnrollment?.dispose(); navigationWillChange(); stopWorkers(); refreshJob?.cancel(); openJob?.cancel()
        originalReads.invalidate()
        actionJobs.values.toList().forEach { it.cancel() }; actionJobs.clear(); pendingTickets.clear();migrationIo.close();ownerJob.cancel()
    }
}
