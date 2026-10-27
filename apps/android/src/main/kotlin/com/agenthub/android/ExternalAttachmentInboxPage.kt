package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The exact presented batch snapshot stays fixed while new files arrive independently. */
@Composable internal fun ExternalAttachmentInboxPage(session: MobileSession,inbox: NativeExternalAttachments) {
    val original=inbox.presentation ?: return
    val owner=LocalExternalAttachmentSurface.current
    val context=LocalContext.current;val colors=MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxWidth().testTag("external-attachment-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        for(batch in original.batches) {
            items(batch.readyFiles,key={ batch.id+":"+it.id }) { file ->
                Row(Modifier.fillMaxWidth().testTag("external-attachment-file-"+file.id),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ProductIcon(if(file.mediaType.startsWith("image/"))"photo"else"file",Modifier.size(22.dp),colors.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(file.name,fontSize=16.sp)
                        Text(android.text.format.Formatter.formatShortFileSize(context,file.sizeBytes),fontSize=12.sp,color=colors.onSurfaceVariant)
                    }
                }
            }
            if(batch.phase!="pending")item(key=batch.id+":failed") { Text(productText(NativeFileIntakeFailure.CHANGED),color=colors.error,fontSize=14.sp) }
        }
        item { Text(productText("选择好聊天后添加到当前草稿，发送消息时才上传。"),fontSize=14.sp,color=colors.onSurfaceVariant) }
        (inbox.failureKey ?: original.notice)?.let { failure -> item { Text(productText(failure),Modifier.testTag("external-attachment-error"),color=colors.error,fontSize=14.sp) } }
        if(inbox.adding||inbox.receiving)item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.5.dp);Text(productText("正在导入附件…"),fontSize=14.sp)
        } }
        item { Button(onClick={ inbox.add(original,session,owner) },enabled=!inbox.adding&&!session.restoring&&!session.localRecoveryRequired&&!session.importing&&session.canCompose&&original.batches.any { it.phase=="pending" },modifier=Modifier.fillMaxWidth().testTag("external-attachment-add")) {
            Text(productText("添加到当前草稿"))
        } }
        if(original.notice!=null||original.batches.any { it.phase!="pending"&&inbox.canRetry(it.id) })item { TextButton(onClick={ inbox.retry(original,owner) },enabled=!inbox.adding&&!inbox.receiving,modifier=Modifier.fillMaxWidth().testTag("external-attachment-retry")) { Text(productText("重试")) } }
        item { TextButton(onClick={ inbox.discard(original,owner) },enabled=!inbox.adding&&!inbox.receiving&&original.batches.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("external-attachment-discard")) {
            Text(productText("丢弃共享文件"),color=colors.error)
        } }
    }
}
