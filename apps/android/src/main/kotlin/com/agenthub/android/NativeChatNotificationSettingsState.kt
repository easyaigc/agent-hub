package com.agenthub.android

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** No default preferences stand in for an unreadable record. All storage work stays off Main. */
internal class NativeChatNotificationSettingsState(private val scope: CoroutineScope,
    private val store: NativeChatNotificationStore, private val platform: NativeChatNotificationPlatform) {
    var preferences by mutableStateOf<NativeChatNotificationPreferences?>(null)
        private set
    var permission by mutableStateOf<NativeChatNotificationPermission?>(null)
        private set
    var channels by mutableStateOf<List<NativeChatNotificationChannelState>>(emptyList())
        private set
    var channelTitles by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private val active = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var job: Job? = null
    val writable get() = active.get() && !loading && error == null && preferences != null
    private fun current(token: Long) = active.get() && generation.get() == token
    fun pause() { active.set(false); generation.incrementAndGet(); job?.cancel() }
    fun resume() { active.set(true); reload() }
    fun reload() {
        if (!active.get()) return
        val token = generation.incrementAndGet(); job?.cancel(); loading = true
        job = scope.launch {
            try {
                val systemPermission = platform.permission(); val systemChannels = platform.channels()
                val (saved, titles) = withContext(Dispatchers.IO) {
                    store.preferences() to systemChannels.associate { it.id to platform.channelSoundTitle(it) }
                }
                if (current(token)) { preferences = saved; permission = systemPermission; channels = systemChannels; channelTitles = titles; error = null }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) error = "无法读取通知资料，原文件已保留。" }
            finally { if (current(token)) loading = false }
        }
    }
    fun update(change: (NativeChatNotificationPreferences) -> NativeChatNotificationPreferences, afterSaved: (NativeChatNotificationPreferences) -> Unit = {}) {
        if (!writable) return
        store.invalidatePreferences()
        val original = requireNotNull(preferences)
        val token = generation.incrementAndGet(); loading = true
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    store.update { latest ->
                        check(current(token) && latest.revision == original.revision) { "Stale notification settings" }
                        change(latest)
                    }
                }
                if (current(token)) { preferences = saved; platform.cancelOwned(); afterSaved(saved) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) error = "无法保存通知设置，请重新读取资料。" }
            finally { if (current(token)) loading = false }
        }
    }
    fun test(failed: () -> Unit) {
        if (!writable) return
        val saved = requireNotNull(preferences); val token = generation.incrementAndGet(); val presentation = store.presentationLease(); loading = true
        job = scope.launch {
            try {
                val same = withContext(Dispatchers.IO) { store.preferences().revision == saved.revision }
                if (same && current(token) && presentation() && !platform.testAsync(saved) { current(token) && presentation() } && current(token)) failed()
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) failed() }
            finally { if (current(token)) { loading = false; reload() } }
        }
    }
}
