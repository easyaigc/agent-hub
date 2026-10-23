package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.*

internal fun MobileMessage.timelineMessage() = TimelineMessage(id,role,text,kind,status,runId,replyPhase,replyMessageId)

@Composable internal fun ToolGroup(id: String,messages: List<MobileMessage>) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val colors=MaterialTheme.colorScheme
    val summary=ToolPresentation.summary(messages.map { it.timelineMessage() })
    Column(Modifier.fillMaxWidth().testTag("tool-group-$id")) {
        if(messages.size>1) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("tool-group-toggle-$id").semantics { contentDescription=summary; stateDescription=if(expanded) "已展开" else "已折叠" }.clickable { expanded=!expanded },verticalAlignment=Alignment.CenterVertically) {
            ProductIcon("tools",Modifier.size(18.dp),colors.onSurfaceVariant); Spacer(Modifier.width(8.dp))
            Text(summary,fontSize=16.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp)); ProductIcon(if(expanded) "down" else "chevron",Modifier.size(11.dp),colors.onSurfaceVariant)
        }
        if(messages.size==1 || expanded) {
            val modifier=if(messages.size>6) Modifier.heightIn(max=264.dp).verticalScroll(rememberScrollState()) else Modifier
            Column(modifier.padding(top=if(messages.size>1) 4.dp else 0.dp)) { messages.forEach { ToolItem(it,messages.size==1) } }
        }
    }
}
@Composable private fun ToolItem(message: MobileMessage,single: Boolean) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val colors=MaterialTheme.colorScheme; val clipboard=LocalClipboardManager.current
    val style=ToolPresentation.style(message.kind); val title=ToolPresentation.title(message.timelineMessage(),single)
    val status=ToolPresentation.status(message.status)
    val emphasized=!single && expanded
    val dark=colors.background==Color.Black
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("tool-toggle-${message.id}").semantics { contentDescription=title; stateDescription="$status，${if(expanded) "已展开" else "已折叠"}" }.clickable { expanded=!expanded },verticalAlignment=Alignment.CenterVertically) {
            ProductIcon(when(message.kind) { "commandExecution"->"command"; "fileChange"->"new"; "webSearch"->"search"; else->"tools" },Modifier.size(18.dp),colors.onSurfaceVariant)
            Text(title,Modifier.weight(1f).padding(start=8.dp),fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(single || emphasized) FontWeight.SemiBold else FontWeight.Normal,color=if(emphasized) colors.onSurface else colors.onSurfaceVariant)
            if(single || expanded) ProductIcon(if(expanded) "down" else "chevron",Modifier.size(11.dp),colors.onSurfaceVariant)
        }
        if(expanded) Column(Modifier.fillMaxWidth().padding(bottom=8.dp).clip(RoundedCornerShape(12.dp)).background(MobileDesign.toolDetail(dark)).border(1.dp,colors.onSurface.copy(alpha=.1f),RoundedCornerShape(12.dp)).padding(12.dp).testTag("tool-detail-${message.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(style.detailTitle,fontSize=15.sp,color=colors.onSurfaceVariant)
            Row(verticalAlignment=Alignment.Top) {
                Text((if(message.kind=="commandExecution") "$ " else "")+message.text,Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=15.sp,maxLines=2)
                IconAction("copy",if(message.kind=="commandExecution") "复制命令" else "复制工具详情","tool-copy-${message.id}") { clipboard.setText(AnnotatedString(if(message.kind=="commandExecution") message.text else message.detail.orEmpty())) }
            }
            if(message.detail.isNullOrEmpty()) Text(if(message.status=="inProgress") "等待工具输出…" else "暂无详细输出",fontSize=12.sp,color=colors.onSurfaceVariant)
            else Box(Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()).testTag(if(message.kind=="fileChange") "tool-${message.id}-code" else "tool-output-${message.id}")) {
                Text(message.detail,Modifier.horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,fontSize=15.sp,softWrap=false,color=colors.onSurfaceVariant.copy(alpha=.75f))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) { if(message.status=="inProgress") CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.dp) else ProductIcon(if(message.status=="completed") "check" else if(message.status=="failed") "close" else "stop",Modifier.size(14.dp),colors.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(status,fontSize=15.sp,color=colors.onSurfaceVariant) }
        }
    }
}
