package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun chatNotificationTargetTag(target: NativeChatNoticeTarget) =
    listOf(target.hostId, target.workspaceId, target.runtimeId).joinToString("|") { "${it.length}:$it" }

/** Actual settings entry/page. Shared NativeSheets only routes this component. */
@Composable internal fun NativeChatNotificationSettingsEntry(coordinator: NativeChatNotificationCoordinator?, navigate: (String) -> Unit) {
    GroupTitle(productText("通知"))
    GroupCard { SettingsRow(productText("通知"), if (coordinator?.unavailable == true) productText("暂时无法同步通知，原记录已保留。") else "",
        "settings-chat-notifications") { navigate("chat-notifications") } }
}

@Composable internal fun NativeChatNotificationsPage(coordinator: NativeChatNotificationCoordinator?) {
    if (coordinator == null) { Text(productText("暂时无法同步通知，原记录已保留。"), Modifier.padding(16.dp).testTag("chat-notification-coordinator-unavailable")); return }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(coordinator, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) coordinator.reconcile() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    NativeChatNotificationSettingsPage(coordinator.store, coordinator.system, coordinator.computers,
        onExplicitDisable = coordinator::explicitDisable, onChanged = coordinator::preferencesSaved, interactionReady=coordinator.migrationInteractionReady,
        status = {
            if(coordinator.migrationEnrollmentFailed)TextButton(onClick=coordinator::retryMigrationEnrollment,modifier=Modifier.testTag("chat-notification-migration-retry")){Text(productText("重试"))}
            if(coordinator.migrationNeedsSetup) Text(productText("迁移后需明确开启通知，原记录已保留。"),Modifier.testTag("chat-notification-migration-setup"))
            if (coordinator.unavailable) Text(productText("暂时无法同步通知，原记录已保留。"), Modifier.testTag("chat-notification-coordinator-unavailable"))
            val targets = (coordinator.computers.map { it.target } + coordinator.reports.keys + coordinator.originalReports.keys).distinct()
            for (target in targets) {
                Column(Modifier.fillMaxWidth().testTag("chat-notification-computer-status-" + chatNotificationTargetTag(target))) {
                    val current = coordinator.computers.firstOrNull { it.target == target }
                    Text((current?.name ?: target.hostId) + " · " + productText(if (current != null) "当前连接" else "原连接记录"))
                    coordinator.reports[target]?.let { report -> NativeChatNotificationRecoveryStatus(report,
                        { offer, id -> coordinator.acceptGap(target, offer, id) }, { id -> coordinator.disableComputer(target, id) }) }
                    coordinator.originalReports[target]?.let { report -> NativeChatNotificationRecoveryStatus(report,
                        { offer, id -> coordinator.acceptGap(target, offer, id) }, { id -> coordinator.disableComputer(target, id) }) }
                    TextButton(onClick = { coordinator.queryOriginals(target) }, enabled = target !in coordinator.querying,
                        modifier = Modifier.testTag("chat-notification-query-original-" + chatNotificationTargetTag(target))) { Text(productText("核对原通知记录")) }
                }
            }
        })
}

/** Mount in the existing product surface. It never supplies navigation/operation data from Intent extras. */
@Composable internal fun NativeChatNotificationOpenStatus(coordinator: NativeChatNotificationCoordinator?) {
    val state = coordinator?.openState ?: return
    if (state == "opened") return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("chat-notification-open-status")) {
        Text(productText(if (state == "waiting-for-restoration") "正在恢复原通知，请稍候。" else "无法打开原通知，对话或连接暂不可用。"))
        if (state == "unavailable" && coordinator.canRetryOpen) TextButton(onClick = coordinator::retryOpen, enabled = !coordinator.opening,
            modifier = Modifier.testTag("chat-notification-open-retry")) { Text(productText("重试")) }
        TextButton(onClick = coordinator::dismissOpenStatus, modifier = Modifier.testTag("chat-notification-open-dismiss")) { Text(productText("好")) }
    }
}
