package com.agenthub.android

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable internal fun NativeComputerFilesPage(model: NativeComputerFileModel?) {
    val t=currentProductText();val context=LocalContext.current;val clipboard=LocalClipboardManager.current
    if(model==null) {Text(t.tr("尚未连接客户端"));return}
    val current=model.isCurrent
    LaunchedEffect(model,current) {if(current)model.refresh() else model.targetChanged()}
    var pickerOwner by rememberSaveable {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
        val owner=pickerOwner;pickerOwner=null
        if(uris.isNotEmpty()&&owner==model.presentationId&&model.isCurrent)model.importFiles(uris)
    }
    fun size(value: Long)=Formatter.formatFileSize(context,value)
    val completed=model.completed
    LazyColumn(Modifier.fillMaxSize().testTag("file-transfers"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("computer") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row {ProductIcon("laptop");Spacer(Modifier.width(8.dp));Text(model.client.name,style=MaterialTheme.typography.titleMedium)}
                model.catalog?.let {catalog->
                    SelectionContainer {Text(t.tr("保存到：%@",catalog.destinationDirectory),Modifier.testTag("file-transfer-destination"),style=MaterialTheme.typography.bodySmall)}
                    Text(t.tr("当前单文件额度：%@",size(model.maximum)),Modifier.testTag("file-transfer-limit"),style=MaterialTheme.typography.bodySmall)
                    Text(if(catalog.maxFileBytes==null)t.tr("电脑未设置文件上限；当前仍受 Android 本地额度限制。")
                        else t.tr("电脑公布的单文件额度：%@",size(catalog.maxFileBytes)),style=MaterialTheme.typography.bodySmall)
                    Text(t.tr("Android 本机单文件上限：%@；超过上限的文件暂无法上传。",size(ComputerFileCodec.MAX_FILE_BYTES)),style=MaterialTheme.typography.bodySmall)
                }
                Button(onClick={pickerOwner=model.presentationId;picker.launch(arrayOf("*/*"))},enabled=model.canChoose&&pickerOwner==null,
                    modifier=Modifier.fillMaxWidth().testTag("choose-transfer-files")) {
                    ProductIcon("file-upload");Spacer(Modifier.width(8.dp));Text(t.tr("选择文件上传"))
                }
                Text(t.tr("手机 → 电脑 · 同名文件会保留独立副本。"),style=MaterialTheme.typography.bodySmall)
                Text(t.tr("当前电脑目标最多保留 32 个待完成文件，累计本地额度：%@。",size(NativeComputerFileStore.MAX_TOTAL_BYTES)),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={model.refresh()},enabled=current&&!model.busy,modifier=Modifier.testTag("refresh-transfer-files")) {
                    ProductIcon("refresh");Spacer(Modifier.width(8.dp));Text(t.tr("刷新传输记录"))
                }
                if(model.busy) {
                    LinearProgressIndicator(modifier=Modifier.fillMaxWidth().testTag("file-transfer-progress"))
                    model.activity?.let {Text(t.tr(it,model.activityFile.orEmpty()),style=MaterialTheme.typography.bodySmall)}
                    if(model.total>0)Text("${size(model.progress)} / ${size(model.total)}",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=model::cancelLocalWait,modifier=Modifier.testTag("cancel-transfer-wait")) {Text(t.tr("结束本地等待"))}
                }
                if(ComputerFileFdOwners.hasUnresolved())Text(t.tr(ComputerFileCopy.RESOURCES_PENDING),Modifier.testTag("file-transfer-resource-pending"),color=MaterialTheme.colorScheme.error)
                model.error?.let {Text(t.tr(it),Modifier.testTag("file-transfer-error"),color=MaterialTheme.colorScheme.error)}
                model.detail?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
        }
        if(model.pending.isNotEmpty())item("pending-title") {Text(t.tr("待完成"),style=MaterialTheme.typography.titleMedium)}
        items(model.pending,key={"pending-"+it.id}) {pending->
            Column(Modifier.fillMaxWidth().testTag("pending-transfer-${pending.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(pending.name)
                val terminal=pending.phase==ComputerFilePhase.COMPLETED
                val received=model.received(pending)
                val queryOnly=pending.phase==ComputerFilePhase.UNKNOWN||received&&!terminal
                Text(t.tr(when {
                    terminal->"电脑已接收 · 本地清理待完成"
                    received->"电脑已确认接收 · 手机回执待核对"
                    pending.phase==ComputerFilePhase.UNKNOWN->"接收结果待确认 · 仅核对原传输"
                    pending.phase==ComputerFilePhase.READY->"待上传"
                    else->"原件读取未完成，已保存的内容保留在手机。"
                }),style=MaterialTheme.typography.bodySmall)
                pending.error?.let {Text(t.tr(if(terminal&&it==ComputerFileCopy.UNKNOWN)ComputerFileCopy.CLEANUP_PENDING else ComputerFileCopy.display(it)),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                if(pending.phase in setOf(ComputerFilePhase.UNKNOWN,ComputerFilePhase.READY,ComputerFilePhase.COMPLETED))
                    TextButton(onClick={model.retry(pending.id)},enabled=current&&!model.busy&&model.journalUsable&&
                        (terminal||queryOnly||model.catalog!=null&&!ComputerFileFdOwners.hasUnresolved()),
                        modifier=Modifier.testTag("retry-transfer-${pending.id}")) {
                        Text(t.tr(if(terminal)"重试本地清理" else if(received)"查询原回执" else if(pending.phase==ComputerFilePhase.READY)"上传到电脑" else "查询结果"))
                    }
                HorizontalDivider()
            }
        }
        items(model.localIssues.values.filter {issue->model.pending.none {it.id==issue.receipt.file.id}},key={"local-issue-"+it.receipt.file.id}) {issue->
            Column(Modifier.fillMaxWidth().testTag("transfer-local-issue-${issue.receipt.file.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(issue.receipt.file.name);Text(t.tr(issue.key),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={model.retry(issue.receipt.file.id)},enabled=current&&!model.busy&&model.journalUsable) {
                    Text(t.tr(if(issue.queryRequired)"查询原回执" else "重试本地清理"))
                }
            }
        }
        item("completed-title") {Text(t.tr("已上传"),style=MaterialTheme.typography.titleMedium)}
        if(completed.isEmpty())item("completed-empty") {Text(t.tr("上传完成后，文件和保存位置会显示在这里。"),style=MaterialTheme.typography.bodySmall)}
        items(completed,key={"completed-"+it.file.id}) {receipt->
            Column(Modifier.fillMaxWidth().testTag("completed-transfer-${receipt.file.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row {ProductIcon("check",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text(receipt.file.name)}
                Text(size(receipt.file.sizeBytes),style=MaterialTheme.typography.bodySmall)
                SelectionContainer {Text(receipt.destinationPath,style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={clipboard.setText(AnnotatedString(receipt.destinationPath))},modifier=Modifier.testTag("copy-transfer-path-${receipt.file.id}")) {Text(t.tr("复制电脑路径"))}
                HorizontalDivider()
            }
        }
    }
}
