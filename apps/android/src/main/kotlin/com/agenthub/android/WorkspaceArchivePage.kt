package com.agenthub.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenthub.core.WorkspaceArchiveRules
import org.json.JSONObject

@Composable internal fun WorkspaceArchivePage(session: MobileSession) {
    val context=session.captureWorkspaceArchive()
    key(context.key,context.navigation,context.revision) {
        var search by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(emptyList<String>()) }
        var confirmation by remember { mutableStateOf<Pair<MobileSession.WorkspaceArchiveContext,List<String>>?>(null) }
        val rows=session.threads.filter { it.hostId==session.selectedClient.id&&it.archived!=true }.distinctBy { it.id }
        fun detail(thread: MobileThread)=(session.projects.firstOrNull { thread.belongs(it) }?.name ?: "无项目")+" · "+session.chatLifecycleStatus(thread)
        val filtered=rows.filter { search.isEmpty()||it.title.contains(search,ignoreCase=true)||detail(it).contains(search,ignoreCase=true) }
        val eligible=filtered.filter(session::canBatchArchive).map { it.id }
        val all=eligible.isNotEmpty()&&eligible.all { it in selected }
        val available=session.supportsBatchArchive&&session.selectedClient.online&&!session.archiveBatchBusy
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        LaunchedEffect(rows,session.workspaceArchivePendingIds) { selected=selected.filter { id->rows.any { it.id==id&&session.canBatchArchive(it) } } }
        Column(Modifier.fillMaxSize()) {
            Text(session.selectedClient.name,Modifier.padding(horizontal=16.dp))
            OutlinedTextField(search,{ search=it },label={ Text("搜索对话") },singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("management-search"))
            if(!session.supportsBatchArchive)Text("此客户端尚未支持此操作，请更新电脑连接程序或切换客户端。",Modifier.padding(16.dp))
            else if(!session.selectedClient.online)Text("电脑离线，连接后可批量管理。",Modifier.padding(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={ selected=WorkspaceArchiveRules.select(selected,eligible,all) },enabled=available&&eligible.isNotEmpty(),modifier=Modifier.testTag("management-select-all")) { Text(if(all)"取消全选" else "全选") }
                Text("已选 ${selected.size} 项",Modifier.padding(16.dp).testTag("management-selected-count"))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("management-list")) {
                if(filtered.isEmpty())item { Text("没有找到相关对话",Modifier.padding(16.dp)) }
                items(filtered,key={ it.id }) { row ->
                    val chosen=row.id in selected
                    val enabled=available&&session.canBatchArchive(row)&&(chosen||selected.size<100)
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("management-item-${row.id}").semantics { this.selected=chosen;stateDescription=if(chosen)"已选择" else "未选择";role=Role.Checkbox }.clickable(enabled=enabled) {
                        selected=if(chosen)selected-row.id else selected+row.id
                    }.padding(horizontal=16.dp,vertical=10.dp)) {
                        Checkbox(chosen,null,enabled=enabled)
                        Column(Modifier.weight(1f).padding(start=8.dp)) { Text(row.title);Text(detail(row),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if(session.workspace?.let { it.nextProjectCursor!=null||it.nextThreadCursor!=null }==true)item {
                    TextButton(onClick={ session.refresh(more=true,userInitiated=true) },enabled=!session.loading&&!session.archiveBatchBusy,modifier=Modifier.testTag("management-load-more")) { Text("加载更多") }
                }
                item { Text("每批最多选择 100 项。全选仅选择当前搜索结果。",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall) }
                items(session.archiveBatchRecords,key={ it.id }) { record ->
                    val request=WorkspaceArchiveCodec.request(JSONObject(record.intent))
                    val result=record.result?.let { WorkspaceArchiveCodec.receipt(JSONObject(it)) }
                    val items=result?.items ?: request.ids.map { WorkspaceArchiveItem(it,"outcome-unknown",null) }
                    val completed=items.count { it.state=="completed" };val rejected=items.count { it.state=="rejected" }
                    val labels=record.sourceVersion?.let { runCatching { JSONObject(it) }.getOrNull() }
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("归档对话 · ${record.id}",style=MaterialTheme.typography.titleSmall)
                        Text("成功 $completed 项 · 失败 $rejected 项 · 待确认 ${items.size-completed-rejected} 项",Modifier.testTag("management-result-${record.id}"))
                        items.filter { it.error!=null }.forEach { item -> Text((labels?.optString(item.id,item.id) ?: item.id)+"："+item.error,style=MaterialTheme.typography.bodySmall) }
                        record.error?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                        if(result?.pending!=false)TextButton(onClick={ session.queryWorkspaceArchive(record) },enabled=session.selectedClient.online&&!session.archiveBatchBusy,modifier=Modifier.testTag("management-check-${record.id}")) { Text("核对结果") }
                    }
                }
            }
            if(session.archiveBatchBusy)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("management-busy"))
            Button(onClick={
                focus.clearFocus(force=true);keyboard?.hide()
                val ids=rows.filter { it.id in selected&&session.canBatchArchive(it) }.map { it.id }
                if(ids.isNotEmpty())confirmation=session.captureWorkspaceArchive() to ids
            },enabled=selected.isNotEmpty()&&available,modifier=Modifier.fillMaxWidth().padding(16.dp).testTag("management-submit")) { Text("归档 ${selected.size} 个对话") }
        }
        confirmation?.let { captured ->
            AlertDialog(onDismissRequest={ confirmation=null },title={ Text("归档选中的 ${captured.second.size} 个对话？") },
                text={ Text("所选对话将从活跃列表移除，历史会保留。") },confirmButton={
                    TextButton(onClick={ confirmation=null;if(session.archiveWorkspace(captured.first,captured.second)==null)session.error="管理目标或所选内容已变化，请重新选择。" },modifier=Modifier.testTag("management-confirm")) { Text("归档对话") }
                },dismissButton={ TextButton(onClick={ confirmation=null },modifier=Modifier.testTag("management-cancel")) { Text("取消") } })
        }
    }
}
