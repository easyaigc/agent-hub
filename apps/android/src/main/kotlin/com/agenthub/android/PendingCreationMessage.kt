package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Only immutable original content and authoritative rejection affect this local bubble. */
internal data class PendingCreationMessage(val message: MobileMessage,val phase: String)

@Composable internal fun PendingCreationMessageBubble(original: PendingCreationMessage,targetKey: String,dark: Boolean) {
    val rejected=original.phase=="rejected";val t=currentProductText()
    Column(Modifier.fillMaxWidth().testTag("desktop-creation-message"),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.widthIn(min=24.dp).weight(1f))
            if(!rejected) CircularProgressIndicator(Modifier.size(16.dp).testTag("desktop-creation-message-loading").semantics { contentDescription=t.tr("消息正在发送") },strokeWidth=1.5.dp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(8f,fill=false).testTag("desktop-creation-message-content")) {
                UserMessageBubble(original.message,targetKey,dark) {
                    // Metadata remains readable even if the original local file bytes are unavailable.
                    original.message.files.forEach { name -> Text(productText("文件 · %@",name),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text(productText(if(rejected)"未发送"else"发送中…"),Modifier.testTag("desktop-creation-message-status"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
