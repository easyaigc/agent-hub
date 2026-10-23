package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun AISharingPage(session: MobileSession,settings: Boolean=false) {
    val colors=MaterialTheme.colorScheme
    val uri=LocalUriHandler.current
    val blue=Color(0xFF007AFF)
    @Composable fun FormTitle(text: String) {
        if(text.isEmpty()) Spacer(Modifier.height(20.dp))
        else Text(text,Modifier.padding(start=16.dp,end=16.dp,top=24.dp,bottom=12.dp),fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=colors.onSurfaceVariant)
    }
    @Composable fun FormCard(content: @Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).clip(RoundedCornerShape(24.dp)).background(colors.background),content=content) }
    @Composable fun Separator() { HorizontalDivider(Modifier.padding(start=16.dp),color=colors.onSurface.copy(alpha=0.12f)) }
    @Composable fun Explanation(text: String,secondary: Boolean=false) { Text(text,Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=if(secondary) 12.sp else 17.sp,color=if(secondary) colors.onSurfaceVariant else colors.onSurface) }
    @Composable fun Privacy(label: String) { TextButton(onClick={ uri.openUri("https://51easyai.com/agent-hub/privacy.html") },modifier=Modifier.fillMaxWidth().padding(horizontal=4.dp).testTag("ai-sharing-privacy")) { Text(label,Modifier.fillMaxWidth(),fontSize=17.sp,color=blue) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(if(settings) "ai-sharing-settings" else "ai-sharing-permission")) {
        if(settings) {
            FormCard { Explanation("发送前由你明确允许所选电脑及其 AI 配置处理文字、图片、文件和对话上下文。本地界面示例不访问 AI 服务。");Privacy("隐私政策") }
            FormTitle("已允许的电脑和模型")
            FormCard {
                if(session.aiSharing.grants.isEmpty()) Explanation("尚未允许共享",secondary=true)
                session.aiSharing.grants.forEachIndexed { index,grant->
                    if(index>0) Separator()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(grant.computerName,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                        Text(grant.agentName+" · "+grant.modelName,Modifier.padding(top=8.dp),fontSize=15.sp)
                        TextButton(onClick={ session.revokeAISharing(grant.id) },enabled=!session.aiSharing.busy,modifier=Modifier.testTag("ai-sharing-revoke-${grant.id}")) { Text("撤回许可",color=colors.error) }
                    }
                }
            }
            Explanation("撤回后，新发送、回答及继续执行操作会再次询问。已经提交的内容和电脑端任务不受撤回影响；请到对应电脑或 AI 服务处理删除和停止请求。",secondary=true)
        } else session.aiSharingRequest?.let { request->
            FormTitle("发送到哪里")
            FormCard {
                listOf("电脑" to request.computerName,"Agent" to request.agentName,"模型" to request.modelName).forEachIndexed { index,(label,value)->
                    if(index>0) Separator()
                    Row(Modifier.fillMaxWidth().padding(16.dp)) { Text(label,Modifier.weight(1f),fontSize=17.sp);Text(value,Modifier.weight(2f),fontSize=17.sp,color=colors.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.End) }
                }
            }
            FormTitle("第三方 AI 如何处理内容")
            FormCard {
                Explanation(if(request.scope.model=="codex-dictation") "录音结束后，原录音文件将发送至这台电脑，再使用 Codex 桌面当前 ChatGPT 账户提交给 OpenAI 的语音转写服务。手机只录音，返回的文字填入输入框，由你确认后发送。"
                    else "你的文字、图片、文件、问题回答及相关对话上下文将发送至这台电脑，并由电脑端实际配置的第三方 AI 服务处理，用于生成回复、分析附件和执行任务。")
                Separator()
                Explanation("模型名称不一定代表服务商；请在电脑端确认实际服务商、数据保留、训练用途和处理地区。切换电脑或模型后需要重新允许。选择跟随电脑时，你允许使用该电脑的当前 AI 配置。")
                Separator()
                Explanation("只发送你有权分享的内容。连接加密不影响 AI 服务读取已发送内容。你可以在设置中撤回许可；撤回不删除已经发送的内容，也不停止电脑上已有任务。")
                Separator()
                Privacy("阅读隐私政策")
            }
            FormTitle("")
            FormCard {
                TextButton(onClick=session::allowAISharing,enabled=!session.aiSharing.busy,modifier=Modifier.fillMaxWidth().padding(horizontal=4.dp).heightIn(min=48.dp).testTag("ai-sharing-allow")) { Text(if(session.aiSharing.busy) "正在保存…" else "允许此电脑和模型处理内容",Modifier.fillMaxWidth(),fontSize=17.sp,color=if(session.aiSharing.busy) colors.onSurfaceVariant else blue) }
                Separator()
                TextButton(onClick=session::dismissAISharing,enabled=!session.aiSharing.busy,modifier=Modifier.fillMaxWidth().padding(horizontal=4.dp).heightIn(min=48.dp).testTag("ai-sharing-decline")) { Text("暂不允许",Modifier.fillMaxWidth(),fontSize=17.sp,color=if(session.aiSharing.busy) colors.onSurfaceVariant else blue) }
            }
            Explanation("允许后返回原界面，再次点击发送或原操作按钮。当前草稿和附件会保留；本页不会自动发送。",secondary=true)
        }
        session.aiSharing.failure?.let { Text(it,Modifier.padding(16.dp).testTag("ai-sharing-storage-error"),color=colors.error) }
        Spacer(Modifier.height(24.dp))
    }
}
