package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Original encrypted records, never the current editable draft or a replacement command. */
@Composable internal fun ReplyDeliveryRecordsPage(session: MobileSession,close: ()->Unit) {
    val target=remember { session.targetKey }
    LaunchedEffect(session.targetKey) { if(session.targetKey!=target)close() }
    val records=if(session.targetKey==target)session.detachedReplies.asReversed() else emptyList()
    LazyColumn(Modifier.fillMaxSize().testTag("reply-record-list"),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(records.isEmpty())item { Text("暂无发送记录",Modifier.padding(20.dp)) }
        items(records,key={ it.target+":"+it.id }) { record ->
            var content by remember(record.target,record.id,record.intentSha256) { mutableStateOf<CodexContent?>(null) }
            var unavailable by remember(record.target,record.id) { mutableStateOf(false) }
            LaunchedEffect(record.target,record.id,record.intentSha256) {
                try { content=session.replyRecordContent(record,target) }
                catch(cancel: CancellationException) { throw cancel }
                catch(_: Exception) { unavailable=true }
            }
            val intent=remember(record.intent) { JSONObject(record.intent) }
            val receipt=remember(record.result) { record.result?.let { CodexCommandCodec.receipt(JSONObject(it)) } }
            val attachments=intent.getJSONArray("attachmentIds").length()
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("reply-record-${record.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(intent.getString("text"),Modifier.testTag("reply-record-text-${record.id}")) }
                    content?.files?.forEach { file -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        ProductIcon(if(file.manifest.mediaType.startsWith("image/"))"image" else "document",Modifier.size(16.dp))
                        Text(file.manifest.name,fontSize=12.sp,modifier=Modifier.testTag("reply-record-file-${record.id}-${file.manifest.id}"))
                    } }
                    if(content==null && attachments>0)Text(if(unavailable)"原附件信息暂不可用，$attachments 个附件的原记录已保留" else "正在读取 $attachments 个原附件的信息…",fontSize=12.sp)
                    Text(when(receipt?.state ?: record.phase) { "failed"->if(receipt?.turnId.isNullOrEmpty())"消息提交失败" else "任务执行失败";"cancelled","interrupted"->"已停止";"completed"->"已完成";"submitted"->"已提交";"queued"->"已排队";else->"已结束等待 · 结果待确认" },fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("reply-record-state-${record.id}"))
                    record.error?.let { SelectionContainer { Text(it,fontSize=12.sp) } }
                    session.replyRecordQueryError(record)?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("reply-record-query-error-${record.id}")) }
                    TextButton(onClick={ session.queryReplyRecord(record,target) },enabled=session.canQueryReplyRecord(record,target),modifier=Modifier.testTag("query-detached-reply-${record.id}")) {
                        if(session.replyRecordQuerying(record))CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
                        Text("查询原回执")
                    }
                }
            }
        }
    }
}
