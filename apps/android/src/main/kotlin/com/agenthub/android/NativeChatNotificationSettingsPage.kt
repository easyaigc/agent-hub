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
    store: NativeChatNotificationStore, platform: NativeChatNotificationPlatform,
    computers: List<NativeChatNotificationComputer>,
) {
    val context = LocalContext.current
    var preferences by remember(store) { mutableStateOf(store.preferences()) }
    var permission by remember(platform) { mutableStateOf(platform.permission()) }
    var channels by remember(platform) { mutableStateOf(platform.channels()) }
    var error by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    fun refresh() { preferences = store.preferences(); permission = platform.permission(); channels = platform.channels() }
    fun change(update: (NativeChatNotificationPreferences) -> NativeChatNotificationPreferences) {
        try { preferences = store.update(update); platform.cancelOwned(); refresh() } catch (_: Exception) { error = true }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    DisposableEffect(store, platform, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun settings(channel: String? = null) {
        try { context.startActivity(platform.settings(channel)) } catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(productText("通知"), style = MaterialTheme.typography.titleLarge)
        ChatNoticeToggle("消息通知", preferences.enabled, "chat-notifications-enabled") { enabled ->
            change { it.copy(enabled = enabled) }
            if (enabled && permission.declared && !permission.granted && Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Text(productText(if (permission.canPost) "已开启" else "已关闭"), Modifier.testTag("chat-notification-permission"))
        Text(productText("后台推送尚未就绪"), Modifier.testTag("chat-notification-push-unavailable"))
        TextButton(onClick = { settings() }) { Text(productText("打开系统通知设置")) }
        HorizontalDivider()
        Text(productText("通知类型"), style = MaterialTheme.typography.titleMedium)
        NativeChatNoticeKind.entries.forEach { kind ->
            ChatNoticeToggle(kind.title, kind in preferences.kinds, "chat-notification-kind-" + kind.wire) { enabled ->
                change { it.copy(kinds = if (enabled) it.kinds + kind else it.kinds - kind) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(null to "跟随默认铃声", "default" to "系统默认", "none" to "静音").forEach { (value, label) ->
                    FilterChip(selected = preferences.categorySounds[kind] == value, onClick = {
                        change { it.copy(categorySounds = if (value == null) it.categorySounds - kind else it.categorySounds + (kind to value)) }
                    }, label = { Text(productText(label)) })
                }
            }
        }
        HorizontalDivider()
        Text(productText("电脑"), style = MaterialTheme.typography.titleMedium)
        computers.distinctBy { it.target.hostId }.forEach { computer ->
            ChatNoticeToggle(computer.name, computer.target.hostId !in preferences.excludedHosts,
                "chat-notification-host-" + computer.target.hostId, literal = true) { enabled ->
                change { it.copy(excludedHosts = if (enabled) it.excludedHosts - computer.target.hostId else it.excludedHosts + computer.target.hostId) }
            }
        }
        HorizontalDivider()
        ChatNoticeToggle("前台显示横幅", preferences.foregroundBanner, "chat-notification-foreground") { value -> change { it.copy(foregroundBanner = value) } }
        ChatNoticeToggle("显示会话标题与待处理概要", preferences.showThreadTitle, "chat-notification-preview") { value -> change { it.copy(showThreadTitle = value) } }
        ChatNoticeToggle("显示 App 图标角标", preferences.badgeEnabled, "chat-notification-badge") { value -> change { it.copy(badgeEnabled = value) } }
        ChatNoticeToggle("通知声音", preferences.soundEnabled, "chat-notification-sound") { value -> change { it.copy(soundEnabled = value) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to "系统默认", "none" to "静音").forEach { (value, label) ->
                FilterChip(selected = preferences.sound == value, onClick = { change { it.copy(sound = value) } }, label = { Text(productText(label)) })
            }
        }
        Text(productText("通知渠道由系统管理；声音与角标以系统实际设置为准。"))
        Text(productText("内置与导入铃声尚未就绪"))
        Text(productText("Android 不支持及时通知。"))
        channels.forEach { channel ->
            TextButton(onClick = { settings(channel.id) }) {
                Text(channel.name + " · " + productText(if (channel.enabled) "已开启" else "已关闭"))
            }
        }
        Button(onClick = { try { if (!platform.test(preferences)) error = true; refresh() } catch (_: Exception) { error = true } },
            enabled = preferences.enabled && permission.canPost, modifier = Modifier.testTag("chat-notification-test")) { Text(productText("发送测试通知")) }
    }
    if (error) AlertDialog(onDismissRequest = { error = false }, confirmButton = { TextButton(onClick = { error = false }) { Text(productText("好")) } },
        title = { Text(productText("通知")) }, text = { Text(productText("无法显示通知，请检查系统通知设置。")) })
}

@Composable private fun ChatNoticeToggle(label: String, value: Boolean, tag: String, literal: Boolean = false, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}
        .toggleable(value = value, role = Role.Switch, onValueChange = change), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (literal) label else productText(label), Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = value, onCheckedChange = null)
    }
}
