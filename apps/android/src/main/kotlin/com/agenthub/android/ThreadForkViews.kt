package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.ThreadForkRules
import org.json.JSONObject

internal fun forkEnvironmentTitle(value: String)=if(value=="same-directory") "在当前工作目录中分支" else "在新工作树中分支"

@Composable internal fun ThreadForkMenu(session: MobileSession,turnId: String?) {
    val key=session.targetKey;val t=currentProductText()
    var expanded by remember(key,turnId) { mutableStateOf(false) }
    LaunchedEffect(key,turnId) { if(!turnId.isNullOrBlank()) session.loadForkOptions() }
    Box {
        val waiting=session.forkBusy || session.forkRecords.any { !it.detached && ThreadForkRules.pending(it.phase) }
        Box(Modifier.size(48.dp).testTag("fork-reply").semantics { contentDescription=t.tr("分支对话");role=Role.Button }
            .clickable { expanded=true;session.loadForkOptions(refresh=true) },contentAlignment=Alignment.Center) {
            if(waiting) CircularProgressIndicator(Modifier.size(18.dp).testTag("fork-reply-progress"),strokeWidth=2.dp)
            else ProductIcon("fork",color=MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
            for(environment in listOf("same-directory","worktree")) {
                val reason=session.forkReason(turnId,environment)
                DropdownMenuItem(text={ Column { Text(productText(forkEnvironmentTitle(environment)));reason?.let { Text(it,fontSize=12.sp) } } },
                    enabled=reason==null,onClick={ expanded=false;session.forkThread(turnId,environment) },modifier=Modifier.testTag("fork-$environment"))
            }
        }
    }
}
@Composable internal fun ThreadForkReceipt(session: MobileSession,record: SavedOperation) {
    val title=when(record.phase) {
        "confirmed" -> "分支已创建"
        "rejected" -> "分支未创建"
        "uncertain","outcome-unknown" -> if(record.detached) "已结束等待 · 结果待确认" else "分支结果待确认"
        else -> if(record.detached) "已结束等待 · 原回执保留" else "正在等待电脑确认分支"
    }
    Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("fork-receipt-${record.id}")) {
        Row(Modifier.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(!record.detached && record.phase in listOf("ready","submitting","submitted") || session.forkBusy) CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp)
                    Text(productText(title),fontSize=12.sp)
                }
                Text(productText(forkEnvironmentTitle(JSONObject(record.intent).getString("environment"))),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(record.detached) Text(productText("仅结束本机等待；电脑端操作未取消，原回执保留。"),fontSize=12.sp)
                record.error?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(horizontalAlignment=Alignment.End) {
                TextButton(onClick={ session.queryFork(record) },enabled=!session.forkBusy,modifier=Modifier.testTag("query-fork-${record.id}")) { Text(productText(if(record.phase=="confirmed") "打开分支" else "查询原回执"),fontSize=12.sp) }
                if(ThreadForkRules.pending(record.phase) && !record.detached) TextButton(onClick={ session.detachOperation(record) },modifier=Modifier.testTag("detach-fork-${record.id}")) { Text(productText("结束等待"),fontSize=12.sp) }
            }
        }
    }
}
