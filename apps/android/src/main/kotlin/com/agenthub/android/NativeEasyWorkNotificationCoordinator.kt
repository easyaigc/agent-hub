package com.agenthub.android

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*

internal data class NativeEasyWorkNoticeReport(val first: String, val second: String, val operationId: String? = null)

internal interface NativeEasyWorkNotificationSessionPort {
    val notifications: AgentTaskNotifications
    val reads: AgentReads
    fun ready(): Boolean
    fun clients(): List<MobileClient>
    /** Main-only, verified exact-account workspace and original thread; preserves all drafts. */
    fun open(client: MobileClient, workspace: MobileWorkspace, thread: MobileThread, current: () -> Boolean): Boolean
}

/** Fixed local observation points only; production defaults never alter a claim or execute a command. */
internal class NativeEasyWorkNavigationObservation(
    val claimed: suspend (ticket: String, claim: String, owner: Job) -> Unit = { _, _, _ -> },
    val acknowledging: suspend (ticket: String, claim: String) -> Unit = { _, _ -> },
)

internal class NativeEasyWorkNotificationCoordinator(context: Context, private val scope: CoroutineScope,
    private val storage: NativeSessionStorage, private val port: NativeEasyWorkNotificationSessionPort,
    private val navigation: NativeEasyWorkNavigationObservation = NativeEasyWorkNavigationObservation()) {
    private val runtime = NativeEasyWorkNotificationRuntime(context, storage)
    private val notices = NativeEasyWorkNotificationStore(storage)
    private var job: Job? = null
    @Volatile private var stopped = false
    @Volatile private var active = false
    private val generation = java.util.concurrent.atomic.AtomicLong()
    val reports = mutableStateMapOf<String, NativeEasyWorkNoticeReport>()
    @Volatile var receiptPending = false; private set
    fun reconcile(allowed: Boolean) {
        if (stopped) return
        if (!allowed || !port.ready()) { active = false; job?.cancel(); job = null; return }
        active = true
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive && port.ready()) {
                val clients = port.clients().filter { !it.isDemo && it.scope?.agentId == "easywork" && it.migrationEasyWork == null }
                for (client in clients) {
                    val epoch = generation.get()
                    val current = { generation.get() == epoch && !stopped && active }
                    val result = try { runtime.synchronize(client, port.notifications, false, current) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { "push-read-unavailable" }
                    val originalId = withContext(Dispatchers.IO) { notices.row(requireNotNull(client.scope))?.optJSONObject("intent")?.optString("operationId") }
                    if (current()) reports[NativeEasyWorkNotificationStore.key(requireNotNull(client.scope))] = NativeEasyWorkNoticeReport(client.name, result, originalId)
                }
                delay(10_000)
            }
        }
    }
    fun invalidate() { generation.incrementAndGet(); active = false; NativeEasyWorkNotificationFence.invalidate(storage); job?.cancel(); job = null }
    suspend fun open(ticket: String, current: () -> Boolean): Boolean {
        // Capture before the first IO suspension: a rotated token may not authorize this old tap.
        val presentation = NativeEasyWorkNotificationFence.lease(storage)
        receiptPending = false
        val row = withContext(Dispatchers.IO) { notices.ticket(ticket) } ?: return false
        val target = NativeTaskNotificationCodec.target(row.getJSONObject("target"))
        val binding = row.getJSONObject("binding")
        val client = port.clients().singleOrNull { it.id == binding.getString("clientId") && sameScope(it.scope, target) && it.agentConnection?.credentialRef == binding.getString("credentialRef") } ?: return false
        val authority = withContext(Dispatchers.IO) { NativeEasyWorkNoticeAuthority.capture(storage, client) }
        val preferences = NativeChatNotificationStore(storage)
        val configuration = preferences.configurationLease()
        val routeCurrent = { current() && configuration() && presentation() }
        fun validIo() = routeCurrent() && notices.current(target, binding) && authority.current(storage) &&
            authority.epoch == binding.getString("credentialEpoch") &&
            NativeChatNotificationPushState(storage).matches(binding.getString("tokenHash"), binding.getString("configuration")) && preferences.preferences().let {
                it.enabled && it.revision == binding.getString("revision") && target.hostId !in it.excludedHosts &&
                    row.getJSONObject("event").getString("kind") in it.kinds.map { kind -> kind.wire }
            }
        if (!withContext(Dispatchers.IO) { validIo() }) return false
        val event = NativeTaskNotificationCodec.notice(row.getJSONObject("event"), target)
        val threadId = event.threadId ?: return false
        var cursor: String? = null; val seen = mutableSetOf<String>()
        repeat(256) {
            if (!routeCurrent()) return false
            val workspace = port.reads.workspace(client, threadCursor = cursor)
            if (!sameScope(workspace.scope, target)) return false
            val thread = workspace.threads.singleOrNull { it.id == threadId && it.hostId == client.id }
            if (thread != null) {
                val owner = requireNotNull(currentCoroutineContext()[Job])
                val claim = withContext(Dispatchers.IO) {
                    val original = notices.claimNavigation(ticket, ::validIo) ?: return@withContext null
                    navigation.claimed(ticket, original, owner)
                    original
                } ?: return false
                // Main checks only captured in-memory epochs; no encrypted storage read here.
                val opened = withContext(Dispatchers.Main.immediate) { routeCurrent() && port.open(client, workspace, thread, routeCurrent) }
                if (!opened) return false
                // Navigation may cancel its owner and invalidate its own route. Keep the known
                // success on the original dispatcher; cancellation returning from ACK IO cannot requeue it.
                return withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        try { navigation.acknowledging(ticket, claim); notices.acknowledgeNavigation(ticket, claim) }
                        catch (_: Exception) { receiptPending = true }
                    }
                    true
                }
            }
            cursor = workspace.nextThreadCursor ?: return false
            if (!seen.add(requireNotNull(cursor))) return false
        }
        return false
    }
    fun endWaiting(key: String, operationId: String) {
        val client = port.clients().singleOrNull { it.scope?.agentId == "easywork" && NativeEasyWorkNotificationStore.key(requireNotNull(it.scope)) == key } ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val row = notices.row(requireNotNull(client.scope)) ?: return@withContext
                if (row.optString("phase") == "unknown" && row.optJSONObject("intent")?.optString("operationId") == operationId) notices.save(requireNotNull(client.scope), row.put("waitingEnded", true))
            }
            if (reports[key]?.operationId == operationId) reports[key] = NativeEasyWorkNoticeReport(client.name, "easywork-wait-ended-original-retained", operationId)
        }
    }
    fun queryOriginal(key: String) {
        val client = port.clients().singleOrNull { it.scope?.agentId == "easywork" && NativeEasyWorkNotificationStore.key(requireNotNull(it.scope)) == key } ?: return
        scope.launch {
            try {
                val identity = NativeChatNotificationIdentity.existing(storage)
                port.notifications.taskNotificationStatus(client, identity.deviceId)
                reports[key]?.let { reports[key] = it.copy(second = "easywork-original-unconfirmed-no-replay") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { reports[key]?.let { reports[key] = it.copy(second = "push-read-unavailable") } }
        }
    }
    fun dispose() { stopped = true; invalidate() }
}
