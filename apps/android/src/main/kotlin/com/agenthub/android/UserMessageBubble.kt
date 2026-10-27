package com.agenthub.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Native bounded presentation; the original source text and attachments remain intact. */
@Composable internal fun UserMessageBubble(message: MobileMessage,targetKey: String,dark: Boolean,attachments: @Composable ()->Unit) {
    key(targetKey,message.id) {
        var expanded by remember { mutableStateOf(false) }
        var overflow by remember { mutableStateOf(false) }
        val preview=MobileDesign.userBubblePreviewLines.toInt();val t=currentProductText()
        val foreground=MobileDesign.userBubbleText(dark)
        Column(Modifier.widthIn(max=330.dp).clip(RoundedCornerShape(MobileDesign.userBubbleRadius.dp))
            .background(MobileDesign.userBubble(dark)).padding(horizontal=MobileDesign.userBubbleHorizontalInset.dp,vertical=MobileDesign.userBubbleVerticalInset.dp)
            .testTag("message-${message.id}"),verticalArrangement=Arrangement.spacedBy(MobileDesign.userBubbleAttachmentSpacing.dp)) {
            if(message.text.isNotEmpty()) SelectionContainer {
                Text(message.text,Modifier.testTag("user-message-text-${message.id}"),fontSize=17.sp,lineHeight=24.sp,color=foreground,
                    maxLines=if(expanded) Int.MAX_VALUE else preview,overflow=TextOverflow.Ellipsis,
                    onTextLayout={ overflow=it.lineCount>preview || it.hasVisualOverflow })
            }
            attachments()
            if(overflow) Row(Modifier.fillMaxWidth().heightIn(min=MobileDesign.androidIconTouchSize.dp)
                .clickable(role=Role.Button) { expanded=!expanded }.testTag("user-message-toggle-${message.id}")
                .semantics(mergeDescendants=true) { stateDescription=t.tr(if(expanded) "已展开" else "已折叠") },
                horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f));Text(t.tr(if(expanded) "收起" else "展开"),fontSize=12.sp,fontWeight=FontWeight.Medium,color=foreground)
                ProductIcon("down",Modifier.size(10.dp).rotate(if(expanded)180f else 0f),foreground)
            }
        }
    }
}
