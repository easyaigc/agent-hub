package com.agenthub.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** One installation-owned durable network job. Tokens and target credentials never enter JobInfo extras. */
internal object NativeChatNotificationPushJobs {
    private const val ID = 0x43485034
    fun schedule(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).schedule(
        JobInfo.Builder(ID, ComponentName(context, NativeChatNotificationPushJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
    ) == JobScheduler.RESULT_SUCCESS
    fun resume(context: Context, scope: CoroutineScope, storage: NativeSessionStorage) {
        scope.launch(Dispatchers.IO) {
            if (runCatching { NativeChatNotificationPushState(storage).pendingSync() != null }.getOrDefault(false)) schedule(context)
        }
    }
}

class NativeChatNotificationPushJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Pair<JobParameters, Job>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try { retry = synchronize() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { retry = true } // State remains encrypted and original intents remain query-only.
            finally { withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (active?.first === params) { active = null; jobFinished(params, retry) }
            } }
        }
        active = params to job; job.start(); return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (active?.first === params) { val original = active; active = null; original?.second?.cancel() }
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private suspend fun synchronize(): Boolean {
        val context = applicationContext
        val config = NativeChatFirebaseConfiguration.current(context) ?: return false
        // No fallback/new namespace, Keystore identity or notification identity on a background callback.
        val storage = NativeSessionStorage(context, existingKeyOnly = true, existingNamespaceOnly = true)
        val state = NativeChatNotificationPushState(storage)
        val sync = state.pendingSync() ?: return false
        if (sync.configuration != config.fingerprint) return false
        NativeChatNotificationIdentity.existing(storage)
        val platform = NativeChatNotificationPlatform(context, NativeL10n(context, "system"), NativeChatNotificationSoundLibrary(context, storage))
        val controller = NativeChatNotificationPushController(context, storage, platform, NativeChatNotificationHttp())
        val authority = NativeChatNotificationPushAuthority(storage)
        val notices = NativeChatNotificationStore(storage)
        val sources = NativeChatNotificationAuthorizations(storage).all()
        var retry = false
        var complete = true
        for (row in state.rows()) {
            currentCoroutineContext().ensureActive()
            if (!state.syncCurrent(sync.id)) return true
            val target = NativeChatNotificationCodec.target(row.getJSONObject("target"))
            val source = sources.singleOrNull { it.target == target && it.snapshot.authorizationFingerprint == row.optString("authorization") } ?: continue
            if (!authority.current(source)) { notices.revoke(target); continue }
            val preferences = notices.preferences()
            val permission = platform.permission().canPost
            val presentation = notices.presentationLease(target)
            val current = { state.syncCurrent(sync.id) && authority.current(source) && notices.preferences() == preferences &&
                platform.permission().canPost == permission && presentation() }
            // The controller's own revoke/bind changes its presentation epoch. Only external facts fence HTTP/settlement.
            val enrollmentCurrent = { state.syncCurrent(sync.id) && authority.current(source) && notices.preferences() == preferences && platform.permission().canPost == permission }
            if (!current()) return true
            val result = controller.synchronize(source.snapshot, enrollmentCurrent, background = true)
            retry = retry || NativeChatPushLifecycle.retryBackground(result?.state)
            complete = complete && result?.state in setOf("push-registered", "disabled")
        }
        val easyWork = NativeEasyWorkNotificationJobs.synchronize(context, storage, sync)
        complete = complete && easyWork.complete; retry = retry || easyWork.retry
        if (complete && state.syncCurrent(sync.id)) state.finishSync(sync.id)
        return retry
    }
}
