package com.agenthub.android

import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** IO-only catalog/preferences work, with a Main-only page projection and preview. No default-on-error. */
internal class NativeChatNotificationSoundSettingsState(private val scope: CoroutineScope,
    private val store: NativeChatNotificationStore, private val platform: NativeChatNotificationSystem,
    private val kind: NativeChatNoticeKind?, val preview: NativeChatNotificationSoundPreview) {
    var preferences by mutableStateOf<NativeChatNotificationPreferences?>(null)
        private set
    var items by mutableStateOf<List<NativeChatNotificationSound>>(emptyList())
        private set
    var unavailable by mutableStateOf(false)
        private set
    var busy by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(null)
    private val active = AtomicBoolean(false)
    private val mounted = AtomicBoolean(true)
    private val generation = AtomicLong()
    private var job: Job? = null
    private var pendingImport: Pair<Uri, String>? = null
    val writable get() = active.get() && !busy && !unavailable && preferences != null
    private fun current(token: Long) = mounted.get() && active.get() && generation.get() == token
    fun pause() { active.set(false); generation.incrementAndGet(); job?.cancel(); preview.stop() }
    fun dispose() { mounted.set(false); pendingImport = null; pause() }
    fun resume() { active.set(true); reload() }
    fun reload() {
        if (!mounted.get() || !active.get()) return
        val token = generation.incrementAndGet(); job?.cancel(); busy = true
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.preferences() to platform.sounds.catalog() }
                if (current(token)) { preferences = saved.first; items = saved.second; unavailable = false }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) unavailable = true }
            finally {
                if (current(token)) { busy = false; if (!unavailable) importPending() }
            }
        }
    }
    private fun edit(change: (() -> Boolean, NativeChatNotificationPreferences) -> NativeChatNotificationPreferences?) {
        if (!writable) return
        val original = requireNotNull(preferences); val token = generation.incrementAndGet()
        store.invalidatePreferences(); busy = true
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { if (!current(token)) null else change({ current(token) }, original) }
                if (current(token)) {
                    check(saved != null); preferences = saved; platform.cancelOwned()
                    val catalog = withContext(Dispatchers.IO) { platform.sounds.catalog() }
                    if (current(token)) items = catalog
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) { unavailable = true; message = "无法保存铃声设置。" } }
            finally { if (current(token)) busy = false }
        }
    }
    fun choose(id: String?) = edit { current, original -> platform.sounds.select(store, kind, id, original.revision, current) }
    fun category(enabled: Boolean) = edit { current, original -> store.update {
        check(current() && it.revision == original.revision)
        it.copy(kinds = if (enabled) it.kinds + requireNotNull(kind) else it.kinds - requireNotNull(kind))
    } }
    fun delete(id: String) {
        preview.stop()
        edit { current, _ -> platform.sounds.delete(id, store, current); store.preferences() }
    }
    /** Activity results may arrive before ON_RESUME. Queue the original URI/revision without opening it yet. */
    fun import(uri: Uri, revision: String) {
        if (!mounted.get() || kind != null) return
        pendingImport = uri to revision
        importPending()
    }
    private fun importPending() {
        if (!writable) return
        val pending = pendingImport ?: return; pendingImport = null
        val token = generation.incrementAndGet(); busy = true; preview.stop()
        job = scope.launch {
            try {
                val item = platform.sounds.import(pending.first)
                val saved = withContext(Dispatchers.IO) {
                    if (!current(token)) null else platform.sounds.select(store, null, item.id, pending.second) { current(token) }
                }
                if (current(token)) {
                    if (saved != null) { preferences = saved; platform.cancelOwned() } else message = "铃声已导入，当前选择已保留。"
                    val loaded = withContext(Dispatchers.IO) { store.preferences() to platform.sounds.catalog() }
                    if (current(token)) { preferences = loaded.first; items = loaded.second }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (current(token)) message = (failure as? NativeChatNotificationPcm.Failure)?.copy ?: "音频已损坏或无法转换。" }
            finally { if (current(token)) busy = false }
        }
    }
    fun play(id: String) {
        if (!writable) return
        if (preview.playingId == id) { preview.stop(); return }
        val token = generation.get()
        scope.launch {
            try { preview.play(id) { if (current(token)) message = "无法试听这段铃声。" } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) message = "无法试听这段铃声。" }
        }
    }
}
