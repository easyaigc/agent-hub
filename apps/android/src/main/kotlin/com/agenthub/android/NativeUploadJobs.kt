package com.agenthub.android

import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Shared production projection for chat attachments, first attachments and file transfers. */
@Composable internal fun NativeUploadJobs(center:NativeUploadCenter?,client:MobileClient,purposes:Set<String>) {
    if(center==null||client.isDemo)return
    val context=LocalContext.current
    val jobs=center.rows.values.filter {it.target.matches(client)&&it.manifest.purpose in purposes}
        .sortedWith(compareBy<NativeUploadRow> {it.terminal}.thenBy {it.batchId}.thenBy {it.index})
    if(jobs.isEmpty()&&center.storageError==null)return
    var clock by remember {mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
    val observingSpeed=jobs.any {!it.terminal&&!it.paused&&!it.stop&&it.phase=="uploading"}
    LaunchedEffect(observingSpeed) {
        if(observingSpeed)while(true) {clock=android.os.SystemClock.elapsedRealtime();delay(500)}
    }
    fun size(n:Long)=Formatter.formatFileSize(context,n)
    Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()).padding(8.dp)
        .testTag("upload-jobs"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        center.storageError?.let {Text(productText(it),color=MaterialTheme.colorScheme.error)}
        for((batch,entries) in jobs.filter {it.batchId!=null}.groupBy {it.batchId}) {
            // A restored creation can already have confirmed files absent from this display journal.
            // Do not present their bytes as zero or infer an overall percentage from partial rows.
            val total=if(entries.size==entries.first().count)entries.first().batchBytes?:entries.sumOf {it.manifest.file.sizeBytes} else null
            if(entries.first().count>1) {
                Text(productText("文件")+" ${entries.maxOf {it.index}+1}/${entries.first().count}",Modifier.testTag("upload-batch-$batch"))
                if(total!=null&&total>0) {
                    val committed=entries.sumOf {if(it.legacy&&it.phase=="completed")it.manifest.file.sizeBytes else it.committed}.coerceAtMost(total)
                    LinearProgressIndicator(progress={committed.toFloat()/total},modifier=Modifier.fillMaxWidth())
                    Text("${size(committed)} / ${size(total)}",style=MaterialTheme.typography.bodySmall)
                } else Text(productText("其他文件的进度暂不可用。"),style=MaterialTheme.typography.bodySmall)
            }
        }
        for(row in jobs) {
            val m=row.manifest;val n=m.file.sizeBytes
            Card(Modifier.fillMaxWidth().testTag("upload-job-${row.key}")) {
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(m.file.name,style=MaterialTheme.typography.titleSmall)
                    Text(client.name,style=MaterialTheme.typography.bodySmall)
                    val phase=when {
                        row.legacy&&row.phase=="unknown"->"上传结果待确认，请在原任务中查看；此电脑暂不支持继续上传。"
                        row.phase=="cancel-pending"->"取消结果待确认，请查询原上传。"
                        row.phase=="completed"->"上传完成"
                        row.phase=="cancelled"->"电脑已确认取消，手机原件保留。"
                        row.phase=="paused"->"已暂停"
                        row.phase=="stopped"->"已停止本地等待；远端操作未取消。"
                        row.legacy&&!row.terminal->"正在上传；此电脑暂不支持暂停或继续上传。"
                        row.phase=="waiting"||row.phase=="unknown"->"原上传结果待核对"
                        row.phase=="verifying"->"正在校验和发布文件…"
                        row.phase=="queued"->"正在准备上传…"
                        else->"正在上传…"
                    }
                    Text(productText(phase),Modifier.testTag("upload-phase-${row.key}"))
                    if(row.legacy&&!row.terminal)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                    else if(n>0) {
                        val confirmed=if(row.legacy&&row.phase=="completed")n else row.committed
                        LinearProgressIndicator(progress={confirmed.toFloat()/n},modifier=Modifier.fillMaxWidth().testTag("upload-confirmed-${row.key}"))
                        Text("${(confirmed*100.0/n).toInt()}% · ${size(confirmed)} / ${size(n)}",style=MaterialTheme.typography.bodySmall)
                        if(row.sample>0&&!row.paused&&!row.stop&&!row.terminal&&row.phase=="uploading") {
                            val speed=if(clock-row.sample>=1500)0.0 else row.rate
                            Text(size(speed.toLong())+"/s"+(if(speed>0)" · "+productText("剩余")+" ${((n-confirmed)/speed).toLong()}s" else ""),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    row.error?.let {Text(productText(it),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    if(row.phase=="cancelled"&&m.purpose in setOf("attachment","creation-attachment"))
                        Text(productText("要重新上传，请移除后重新添加附件。"),style=MaterialTheme.typography.bodySmall)
                    if(row.terminal)TextButton(onClick={center.dismissTerminal(row.key)},modifier=Modifier.testTag("upload-dismiss-${row.key}")){Text(productText("移除已结束任务卡"))}
                    if(!row.terminal) {
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(!row.legacy) {
                                if(!row.paused&&!row.stop)TextButton(onClick={center.control(row.key,"pause")},modifier=Modifier.testTag("upload-pause-${row.key}")){Text(productText("暂停"))}
                                else if(!row.cancelRequested)TextButton(onClick={center.control(row.key,"resume")},modifier=Modifier.testTag("upload-resume-${row.key}")){Text(productText("继续"))}
                                TextButton(onClick={center.control(row.key,"query")},modifier=Modifier.testTag("upload-query-${row.key}")){Text(productText("查询原结果"))}
                            }
                            TextButton(onClick={center.control(row.key,"stop")},modifier=Modifier.testTag("upload-stop-${row.key}")){Text(productText("结束本地等待"))}
                        }
                        if(row.capability?.remoteCancel==true&&!row.cancelRequested)
                            TextButton(onClick={center.control(row.key,"cancel")},modifier=Modifier.testTag("upload-cancel-${row.key}")){Text(productText("取消电脑上传"))}
                        else if(row.capability?.remoteCancel==false)Text(productText("此类上传暂不支持取消电脑操作；结束等待仅影响手机。"),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
