package com.agenthub.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.ToolPresentation

/** Display-only child row. Preview limits never modify the original detail. */
@Composable internal fun EasyWorkSubagentContent(message: MobileMessage,targetKey: String,dark: Boolean) {
    var expanded by rememberSaveable(targetKey,message.id) { mutableStateOf(false) }
    val detail=message.detail.orEmpty()
    val text=currentProductText()
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(MobileDesign.toolDetail(dark),RoundedCornerShape(12.dp))
        .padding(12.dp).testTag("subagent-${message.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            // Reuse the app's native person geometry for the two-person meaning.
            Box(Modifier.size(24.dp)) {
                ProductIcon("person",Modifier.size(16.dp).align(Alignment.TopEnd),colors.onSurfaceVariant)
                ProductIcon("person",Modifier.size(18.dp).align(Alignment.BottomStart),colors.onSurfaceVariant)
            }
            Text(text.tr("子 Agent")+" · "+message.text,Modifier.weight(1f).testTag("subagent-title-${message.id}"),
                fontSize=16.sp,fontWeight=FontWeight.Medium)
            if(message.status=="inProgress") CircularProgressIndicator(Modifier.size(14.dp).testTag("subagent-progress-${message.id}"),strokeWidth=1.5.dp)
            Text(text.tr(ToolPresentation.status(message.status)),Modifier.widthIn(max=96.dp).testTag("subagent-status-${message.id}"),
                fontSize=12.sp,color=colors.onSurfaceVariant)
        }
        if(detail.isNotEmpty()) {
            val visible=if(expanded)detail else remember(detail) {
                val lastLines=detail.lineSequence().toList().takeLast(6).joinToString("\n")
                val count=lastLines.codePointCount(0,lastLines.length)
                // Keep preview boundaries valid for Unicode supplementary characters.
                if(count<=1200)lastLines else lastLines.substring(lastLines.offsetByCodePoints(lastLines.length,-1200))
            }
            SelectionContainer {
                Text(visible,Modifier.testTag("subagent-detail-${message.id}"),fontSize=16.sp,
                    maxLines=if(expanded)Int.MAX_VALUE else 5,overflow=TextOverflow.Ellipsis)
            }
            TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)
                .testTag("subagent-expand-${message.id}").semantics { stateDescription=text.tr(if(expanded)"已展开" else "已折叠") }) {
                Text(text.tr(if(expanded)"收起" else "展开"),fontSize=12.sp)
            }
        } else Text(text.tr("等待子 Agent 更新…"),Modifier.testTag("subagent-waiting-${message.id}"),fontSize=12.sp,color=colors.onSurfaceVariant)
    }
}
