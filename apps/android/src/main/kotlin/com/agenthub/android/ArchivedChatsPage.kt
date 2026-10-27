package com.agenthub.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Reading an archived row performs only ordinary history GETs. Restore stays a named chat action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ArchivedChatsPage(session: MobileSession,close: ()->Unit,navigate: (String)->Unit) {
    val t=currentProductText()
    val client=session.selectedClient
    val rows=session.threads.filter { it.hostId==client.id&&it.archived==true }.distinctBy { it.id }
    key(client.cacheKey(null)) {
        val list=rememberLazyListState()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(client.online)client.name else client.name+" · "+t.tr("电脑离线"),Modifier.weight(1f))
                TextButton(onClick={ session.refresh(userInitiated=true) },enabled=!client.isDemo&&!session.loading,modifier=Modifier.testTag("archived-refresh")) { Text(productText("刷新")) }
            }
            if(session.supportsBatchArchive)TextButton(onClick={ navigate("workspace-archive") },modifier=Modifier.testTag("batch-archive-open")) { Text(productText("批量归档对话")) }
            PullToRefreshBox(isRefreshing=session.loading,onRefresh={ session.refresh(userInitiated=true) },modifier=Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().testTag("archived-list"),state=list,contentPadding=PaddingValues(bottom=24.dp)) {
                    if(rows.isEmpty())item { Text(productText("没有已归档对话"),Modifier.padding(16.dp).testTag("archived-empty")) }
                    items(rows,key={ it.id }) { thread ->
                        Column(Modifier.fillMaxWidth().testTag("archived-chat-${thread.id}").clickable {
                            if(session.selectedClient.cacheKey(null)==client.cacheKey(null)&&session.selectedClient.endpoint==client.endpoint)session.threads.firstOrNull { it.hostId==client.id&&it.id==thread.id&&it.archived==true }?.let {
                                session.selectThread(it);close()
                            }
                        }.padding(horizontal=16.dp,vertical=12.dp)) {
                            Text(thread.title,style=MaterialTheme.typography.bodyLarge)
                            Text(session.chatLifecycleStatus(thread,t),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(session.workspace?.let { it.nextProjectCursor!=null||it.nextThreadCursor!=null }==true)item {
                        TextButton(onClick={ session.refresh(more=true,userInitiated=true) },enabled=!client.isDemo&&!session.loading,modifier=Modifier.testTag("archived-load-more")) { Text(productText("加载更多")) }
                    }
                }
            }
        }
    }
}
