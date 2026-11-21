package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.UUID

/** Independent status/action surface. Parent owns navigation, exact retained target and action execution. */
@Composable internal fun NativeChatNotificationRecoveryStatus(report: NativeChatNotificationMonitorReport,
    onAcceptGap: (NativeChatRecoveryGapOffer, String) -> Unit, onExplicitDisable: (String) -> Unit,
    onEndPushWaiting: ((String) -> Unit)? = null) {
    var confirm by remember(report.target, report.gap) { mutableStateOf(false) }
    var submitted by remember(report.target, report.gap) { mutableStateOf(false) }
    val actionId = remember(report.target, report.gap) { UUID.randomUUID().toString() }
    val offId = remember(report.target, report.state) { UUID.randomUUID().toString() }
    var offSubmitted by remember(report.target, report.state) { mutableStateOf(false) }
    val message = when (report.state) {
        "push-registered" -> "后台通知已开启。"
        "push-registration-pending" -> "正在启用后台通知，请稍候。"
        "push-pending-original-change-required" -> "原推送登记仍在处理中，设置变更尚未提交。"
        "push-invalid-configuration" -> "后台通知配置无效。"
        "push-not-configured" -> "后台通知尚未配置。"
        "push-system-permission-unavailable" -> "系统通知权限已关闭。"
        "push-google-services-or-token-unavailable" -> "Google Play 服务或推送登记暂不可用。"
        "push-original-recovery-host-upgrade-required" -> "请先升级电脑端以恢复后台通知，原通知记录已保留。"
        "push-legacy-original-unconfirmed" -> "旧通知设置结果仍待确认，可核对当前状态或关闭这台电脑的通知。"
        "push-wait-ended-original-retained" -> "已结束等待，通知设置记录已保留；电脑端操作仍可能完成。"
        "push-local-binding-revoked" -> "本机后台通知登记已失效，请核对通知设置。"
        "push-registration-unconfirmed" -> "电脑未确认后台推送登记，原操作不会重复提交。"
        "disabled" -> "这台电脑的通知已关闭。"
        "waiting-host-confirmation" -> "正在等待电脑确认待处理事项，恢复后继续核对。"
        "feed-gap-acceptance-required" -> "部分通知历史已不可恢复，需要确认后刷新现有通知。"
        "foreground-synchronized" -> "前台通知已同步，后台推送尚未就绪。"
        "original-query-complete" -> "原通知记录已核对。"
        "original-records-retained" -> "原通知记录已保留，可核对原操作。"
        "foreground-outcome-unknown" -> "部分通知的显示结果待确认，不会重复显示。"
        "epoch-recovery-required" -> "电脑的通知资料已更换，请先确认关闭，再重新开启通知。"
        "original-credential-recovery-required", "original-credential-revocation-required" -> "请使用原连接核对通知设置。"
        "subscription-rejected" -> "电脑未接受本次通知设置，请核对连接和通知设置。"
        else -> if (report.state.contains("unknown")) "通知设置结果待确认，正在核对原操作。" else "暂时无法同步通知，原记录已保留。"
    }
    Column(Modifier.fillMaxWidth().testTag("chat-notification-recovery-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(productText(message))
        if (report.unconfirmedOperations > 0) Text(productText("此前仍有通知设置结果待确认。"), Modifier.testTag("chat-notification-original-unknown"))
        if (report.state in setOf("push-mutation-unknown-original-query", "push-registration-pending", "push-legacy-original-unconfirmed") && report.operationId != null && onEndPushWaiting != null)
            TextButton(onClick = { onEndPushWaiting(report.operationId) }, modifier = Modifier.testTag("chat-notification-end-push-wait")) {
                Text(productText("结束等待并保留记录"))
            }
        if (report.gap != null) TextButton(onClick = { confirm = true }, enabled = !submitted,
            modifier = Modifier.testTag("chat-notification-accept-gap")) { Text(productText("刷新现有通知")) }
        if (report.state.contains("unknown") || report.state == "epoch-recovery-required" || report.state in setOf("push-legacy-original-unconfirmed", "push-wait-ended-original-retained")) TextButton(onClick = { offSubmitted = true; onExplicitDisable(offId) }, enabled = !offSubmitted,
            modifier = Modifier.testTag("chat-notification-explicit-off")) { Text(productText("确认关闭这台电脑的通知")) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(productText("刷新现有通知")) },
        text = { Text(productText("已丢失的历史不会恢复。确认后将核对电脑仍保留的通知，不会重新提交对话操作。")) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(productText("取消")) } },
        confirmButton = { TextButton(onClick = { confirm = false; submitted = true; onAcceptGap(requireNotNull(report.gap), actionId) },
            modifier = Modifier.testTag("chat-notification-confirm-gap")) { Text(productText("确认刷新")) } })
}
