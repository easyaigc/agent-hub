package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Selecting a destination prepares a local draft; the first send owns creation. */
@Composable internal fun NewChatDestination(session: MobileSession,choose: ()->Unit,modifier: Modifier=Modifier) {
    val project=session.draftProject
    Column(modifier.fillMaxWidth().padding(24.dp).testTag("new-chat-destination"),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Text(productText("开始一段新对话"),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
        Text(when {
            project!=null -> productText("在 %@ 中开始新对话",project.name)
            session.isProjectlessDraft -> productText("开始一个无项目对话")
            session.isNewChatDraft -> productText("项目已不可用，请重新选择。")
            else -> productText("请先选择对话所属的项目或文件夹。")
        },Modifier.padding(top=10.dp).testTag("new-chat-destination-name"),fontSize=14.sp,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f))
        project?.roots?.forEach { root -> Text(root,Modifier.padding(top=4.dp).testTag("new-chat-destination-root"),fontSize=12.sp,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f)) }
        TextButton(onClick=choose,modifier=Modifier.heightIn(min=48.dp).testTag("new-chat-change-destination")) { Text(productText("选择项目")) }
        if(session.isNewChatDraft) Text(productText("发送第一条消息后创建对话。"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f))
    }
}
