package com.agenthub.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class NativeChatNotificationComputer(val target: NativeChatNoticeTarget, val name: String)

/** Independent ordinary-chat settings page; navigation/manifest registration belongs to the integrator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeChatNotificationSettingsPage(
    store: NativeChatNotificationStore, platform: NativeChatNotificationSystem,
    computers: List<NativeChatNotificationComputer>,
    onExplicitDisable: ((hostId: String?, savedRevision: String, actionId: String) -> Unit)? = null,
    onChanged: () -> Unit = {}, interactionReady: Boolean=true, status: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tasks = rememberCoroutineScope()
    val state = remember(store, platform) { NativeChatNotificationSettingsState(tasks, store, platform) }
    var error by remember { mutableStateOf(false) }
    var soundPage by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.reload() }
    fun change(update: (NativeChatNotificationPreferences) -> NativeChatNotificationPreferences) = state.update(update) { onChanged() }
    DisposableEffect(state, lifecycle) {
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state.resume() else state.pause()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.resume()
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) state.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { state.pause(); lifecycle.lifecycle.removeObserver(observer) }
    }
    if (state.error != null || state.preferences == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(productText("通知"), style = MaterialTheme.typography.titleLarge)
            if (state.loading) CircularProgressIndicator(Modifier.testTag("chat-notification-loading"))
            if (state.error != null) {
                Text(productText(requireNotNull(state.error)), Modifier.testTag("chat-notification-unavailable"))
                Button(onClick = { state.reload() }, enabled = !state.loading, modifier = Modifier.testTag("chat-notification-retry")) { Text(productText("重试")) }
            }
        }
        return
    }
    if (soundPage != null) {
        key(soundPage) { NativeChatNotificationSoundSettingsPage(store, platform,
            soundPage?.takeUnless { it == "default" }?.let(NativeChatNoticeKind::from)) { soundPage = null; state.reload(); onChanged() } }
        return
    }
    val preferences = requireNotNull(state.preferences)
    val writable = state.writable&&interactionReady
    fun settings(channel: String? = null) {
        try { context.startActivity(platform.settings(channel)) } catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(productText("通知"), style = MaterialTheme.typography.titleLarge)
        status?.invoke()
        ChatNoticeToggle("消息通知", preferences.enabled, "chat-notifications-enabled", enabled = writable) { enabled ->
            val actionId = java.util.UUID.randomUUID().toString()
            state.update({ it.copy(enabled = enabled) }) { saved ->
                if (!enabled && onExplicitDisable != null) onExplicitDisable(null, saved.revision, actionId) else onChanged()
                val permission = platform.permission()
                if (enabled && permission.declared && !permission.granted && Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Text(productText(if (state.permission?.canPost == true) "已开启" else "已关闭"), Modifier.testTag("chat-notification-permission"))
        Text(productText("后台推送尚未就绪"), Modifier.testTag("chat-notification-push-unavailable"))
        TextButton(onClick = { settings() }) { Text(productText("打开系统通知设置")) }
        HorizontalDivider()
        Text(productText("通知类型"), style = MaterialTheme.typography.titleMedium)
        if (state.loading) CircularProgressIndicator(Modifier.testTag("chat-notification-loading"))
        NativeChatNoticeKind.entries.forEach { kind ->
            ChatNoticeToggle(kind.title, kind in preferences.kinds, "chat-notification-kind-" + kind.wire, enabled = writable) { enabled ->
                change { it.copy(kinds = if (enabled) it.kinds + kind else it.kinds - kind) }
            }
            TextButton(onClick = { soundPage = kind.wire }, enabled = writable, modifier = Modifier.testTag("chat-notification-category-sound-" + kind.wire)) { Text(productText("铃声")) }
        }
        HorizontalDivider()
        Text(productText("电脑"), style = MaterialTheme.typography.titleMedium)
        computers.distinctBy { it.target.hostId }.forEach { computer ->
            ChatNoticeToggle(computer.name, computer.target.hostId !in preferences.excludedHosts,
                "chat-notification-host-" + computer.target.hostId, literal = true, enabled = writable) { enabled ->
                val actionId = java.util.UUID.randomUUID().toString()
                state.update({ it.copy(excludedHosts = if (enabled) it.excludedHosts - computer.target.hostId else it.excludedHosts + computer.target.hostId) }) { saved ->
                    if (!enabled && onExplicitDisable != null) onExplicitDisable(computer.target.hostId, saved.revision, actionId) else onChanged()
                }
            }
        }
        HorizontalDivider()
        ChatNoticeToggle("前台显示横幅", preferences.foregroundBanner, "chat-notification-foreground", enabled = writable) { value -> change { it.copy(foregroundBanner = value) } }
        ChatNoticeToggle("显示会话标题与待处理概要", preferences.showThreadTitle, "chat-notification-preview", enabled = writable) { value -> change { it.copy(showThreadTitle = value) } }
        ChatNoticeToggle("显示 App 图标角标", preferences.badgeEnabled, "chat-notification-badge", enabled = writable) { value -> change { it.copy(badgeEnabled = value) } }
        ChatNoticeToggle("通知声音", preferences.soundEnabled, "chat-notification-sound", enabled = writable) { value -> change { it.copy(soundEnabled = value) } }
        TextButton(onClick = { soundPage = "default" }, enabled = writable, modifier = Modifier.testTag("chat-notification-default-sound")) { Text(productText("默认铃声")) }
        Text(productText("通知渠道由系统管理；声音与角标以系统实际设置为准。"))
        Text(productText("Android 不支持及时通知。"))
        state.channels.forEach { channel ->
            TextButton(onClick = { settings(channel.id) }) {
                Text(channel.name + " · " + productText(if (channel.enabled) "已开启" else "已关闭") + " · " + state.channelTitles[channel.id].orEmpty())
            }
        }
        Button(onClick = { state.test { error = true } },
            enabled = writable && preferences.enabled && state.permission?.canPost == true, modifier = Modifier.testTag("chat-notification-test")) { Text(productText("发送测试通知")) }
    }
    if (error) AlertDialog(onDismissRequest = { error = false }, confirmButton = { TextButton(onClick = { error = false }) { Text(productText("好")) } },
        title = { Text(productText("通知")) }, text = { Text(productText("无法显示通知，请检查系统通知设置。")) })
}

@Composable private fun ChatNoticeToggle(label: String, value: Boolean, tag: String, literal: Boolean = false, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}
        .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (literal) label else productText(label), Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}
