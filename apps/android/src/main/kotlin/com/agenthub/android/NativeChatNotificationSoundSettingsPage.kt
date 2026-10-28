package com.agenthub.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Default/category choices use one real library. Only an explicit preview action opens an audio player. */
@Composable internal fun NativeChatNotificationSoundSettingsPage(store: NativeChatNotificationStore,
    platform: NativeChatNotificationSystem, kind: NativeChatNoticeKind?, back: () -> Unit) {
    val context = LocalContext.current; val tasks = rememberCoroutineScope(); val lifecycle = LocalLifecycleOwner.current
    val library = platform.sounds
    val preview = remember(library) { NativeChatNotificationSoundPreview(context, library) }
    val state = remember(store, platform, kind, preview) { NativeChatNotificationSoundSettingsState(tasks, store, platform, kind, preview) }
    var importRevision by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val captured = importRevision; importRevision = null
        if (uri != null && captured != null) state.import(uri, captured)
    }
    DisposableEffect(state, lifecycle) {
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state.resume() else state.pause()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) state.pause()
            if (event == Lifecycle.Event.ON_RESUME) state.resume()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { state.dispose(); lifecycle.lifecycle.removeObserver(observer) }
    }
    val preferences = state.preferences
    BackHandler { preview.stop(); back() }
    if (state.unavailable || preferences == null) {
        Column(Modifier.padding(16.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.testTag("chat-sound-loading"))
            if (state.unavailable) {
                Text(productText("无法读取本机铃声资料，原文件已保留。"), Modifier.testTag("chat-sound-unavailable"))
                TextButton(onClick = { state.reload() }, enabled = !state.busy, modifier = Modifier.testTag("chat-sound-retry")) { Text(productText("重试")) }
            }
            TextButton(onClick = { preview.stop(); back() }) { Text(productText("通知")) }
        }
        return
    }
    val busy = !state.writable
    val selected = if (kind == null) preferences.sound else preferences.categorySounds[kind]
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("chat-notification-sound-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { preview.stop(); back() }, modifier = Modifier.testTag("chat-sound-back")) { Text(productText("通知")) }
        Text(productText(kind?.title ?: "默认铃声"), style = MaterialTheme.typography.titleLarge)
        if (kind != null) {
            Row(Modifier.fillMaxWidth().testTag("chat-sound-kind-" + kind.wire).semantics(mergeDescendants = true) {}
                .toggleable(kind in preferences.kinds, enabled = !busy, role = Role.Switch, onValueChange = { enabled ->
                    state.category(enabled)
                }), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(productText("接收此类通知"), Modifier.weight(1f))
                Switch(kind in preferences.kinds, onCheckedChange = null, enabled = !busy)
            }
            ChatSoundChoice(productText("跟随默认铃声"), selected == null, "inherit", !busy) { state.choose(null) }
        }
        ChatSoundChoice(productText("静音"), selected == "none", "none", !busy) { preview.stop(); state.choose("none") }
        ChatSoundChoice(productText("系统默认"), selected == "default", "default", !busy) { state.choose("default") }
        TextButton(onClick = { state.play("default") }, enabled = !busy, modifier = Modifier.testTag("chat-sound-preview-default")) { Text(productText(if (preview.playingId == "default") "停止试听" else "试听")) }
        state.items.forEach { item ->
            val title = if (item.imported) item.title else productText(item.title)
            ChatSoundChoice(title, selected == item.id, item.id, !busy) { state.choose(item.id) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { state.play(item.id) }, enabled = !busy, modifier = Modifier.testTag("chat-sound-preview-" + item.id)) {
                    Text(productText(if (preview.playingId == item.id) "停止试听" else "试听") + " · " + title)
                }
                if (kind == null && item.imported) TextButton(onClick = {
                    if (!busy) state.delete(item.id)
                }, enabled = !busy, modifier = Modifier.testTag("chat-sound-delete-" + item.id)) { Text(productText("删除")) }
            }
        }
        if (kind == null) Button(onClick = {
            preview.stop(); importRevision = preferences.revision
            try { importer.launch(arrayOf("audio/*", "application/x-caf", "application/octet-stream")) }
            catch (_: Exception) { importRevision = null; state.message = "无法打开系统文件选择器。" }
        }, enabled = !busy && importRevision == null, modifier = Modifier.testTag("chat-notification-import-sound")) { Text(productText("导入铃声")) }
        if (busy) CircularProgressIndicator(Modifier.testTag("chat-sound-busy"))
        Text(productText("支持 WAV、AIFF、CAF、M4A 和 MP3，最多 10 MB、29 秒。铃声仅保存在这部手机。音量和振动遵循系统设置。"))
        Text(productText("部分音频编码取决于系统支持；无法解码时会明确提示。"))
        if (!platform.soundProviderRegistered()) Text(productText("本机通知声音入口尚未就绪；可以先导入与试听。"), Modifier.testTag("chat-sound-provider-unavailable"))
    }
    if (state.message != null) AlertDialog(onDismissRequest = { state.message = null }, confirmButton = { TextButton(onClick = { state.message = null }) { Text(productText("好")) } },
        title = { Text(productText("通知")) }, text = { Text(productText(requireNotNull(state.message))) })
}

@Composable private fun ChatSoundChoice(title: String, selected: Boolean, id: String, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("chat-sound-choice-" + id).semantics(mergeDescendants = true) {}
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, Modifier.weight(1f).padding(12.dp))
    }
}
