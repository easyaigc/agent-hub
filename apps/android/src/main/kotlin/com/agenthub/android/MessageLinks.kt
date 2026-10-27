package com.agenthub.android

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

internal val LocalMessageLinkOpen=staticCompositionLocalOf<((String,String)->Unit)?> { null }
internal enum class MessageResourceAction { Preview, Share }
internal val LocalMessageResourceOpen=staticCompositionLocalOf<((String,String,MessageResourceAction)->Unit)?> { null }
internal val LocalOpeningMessageAnchor=staticCompositionLocalOf<String?> { null }
private class MessageResourceJob { var value: Job?=null;var active=true }
private data class MessageLinkFailure(val text: String,val appOwned: Boolean=true,val status: Int?=null)
private data class ResourcePresentation(val key: String,val selection: Long,val connection: Long,val message: String,val resources: List<String>)
private class MessageResourcePreview(val resource: MobileSession.PreparedMessageResource,val reference: String,val attachmentIdentity: String?)

/** All message links are handled here, including unsupported schemes. Never forwards Host auth. */
@Composable internal fun MessageLinkOpening(session: MobileSession,message: MobileMessage,content: @Composable ()->Unit) {
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val owner=session.messageLinkContext(message)
    val presentation=ResourcePresentation(owner.key,owner.selection,owner.connection,message.id,message.attachments.map { it.resourceIdentity })
    val tasks=rememberCoroutineScope()
    val read=remember(owner) { MessageResourceJob() }
    var selected by remember(owner) { mutableStateOf<String?>(null) }
    var failure by remember(owner) { mutableStateOf<MessageLinkFailure?>(null) }
    var loading by remember(owner) { mutableStateOf<String?>(null) }
    // A background version update must not destroy an already opened immutable preview.
    var preview by remember(presentation) { mutableStateOf<MessageResourcePreview?>(null) }
    // IME/inset changes and background history layout may recycle the source row.
    // Keep the opened snapshot's row composed until its window closes; this
    // grants no new target authority and is released on owner removal/disposal.
    val pinnable=LocalPinnableContainer.current
    val holdsSource=preview!=null||loading!=null||failure!=null
    DisposableEffect(pinnable,holdsSource) {
        val pinned=if(holdsSource)pinnable?.pin()else null
        onDispose { pinned?.release() }
    }
    DisposableEffect(owner) { onDispose { read.active=false;read.value?.cancel() } }
    val shareTitle by rememberUpdatedState(productText("保存或分享文件"))
    val openResource: (String,String,MessageResourceAction)->Unit=remember(owner) { { reference,anchor,action->
        if(read.active&&session.messageLinkCurrent(owner)&&loading==null) {
            selected=reference;failure=null
            val destination=MessageLinkDestination.resolve(reference)
            try {
                when(destination.kind) {
                    "web" -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,Uri.parse(reference))
                    "external" -> context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(reference)).addCategory(Intent.CATEGORY_BROWSABLE))
                    "computerWeb" -> failure=MessageLinkFailure("此网址指向电脑的本机地址，手机无法直接访问。请使用手机可连接的局域网或 VPN 网址。")
                    "attachment","computerFile" -> {
                        loading=anchor
                        read.value=tasks.launch(Dispatchers.Main.immediate) {
                            try {
                                val result=session.prepareMessageResource(owner,reference,File(context.cacheDir,"resources"))
                                if(isActive&&read.active&&session.messageLinkCurrent(owner)&&loading==anchor) {
                                    when(action) {
                                        MessageResourceAction.Preview -> preview=MessageResourcePreview(result,reference,
                                            if(destination.kind=="attachment")owner.message.attachments.firstOrNull {
                                                MessageLinkDestination.sameAttachmentId(it.id,destination.attachmentId)
                                            }?.resourceIdentity else null)
                                        MessageResourceAction.Share -> NativeResourceIntents.launch(context,result,true,shareTitle)
                                    }
                                }
                            } catch(cancel: CancellationException) { throw cancel }
                              catch(error: Exception) {
                                if(isActive&&read.active&&session.messageLinkCurrent(owner)&&loading==anchor) {
                                    val native=error as? CodexCommandError
                                    failure=when(error) {
                                        is android.content.ActivityNotFoundException,is SecurityException -> MessageLinkFailure("手机上没有可打开此链接的应用。")
                                        is IllegalArgumentException -> MessageLinkFailure("读取电脑资源失败，请重试。")
                                        else -> MessageLinkFailure(error.message ?: "读取电脑资源失败，请重试。",native?.appOwnedCopy ?: (error.message==null),native?.status)
                                    }
                                }
                            } finally { if(isActive&&read.active&&session.messageLinkCurrent(owner)&&loading==anchor) { loading=null;read.value=null } }
                        }
                    }
                    else -> failure=MessageLinkFailure("手机暂不支持此类链接，可复制后在对应应用中打开。")
                }
            } catch(_: android.content.ActivityNotFoundException) { failure=MessageLinkFailure("手机上没有可打开此链接的应用。") }
              catch(_: SecurityException) { failure=MessageLinkFailure("手机上没有可打开此链接的应用。") }
              catch(_: IllegalArgumentException) { failure=MessageLinkFailure("手机暂不支持此类链接，可复制后在对应应用中打开。") }
        }
    } }
    val openLink: (String,String)->Unit=remember(openResource) { { reference,anchor->openResource(reference,anchor,MessageResourceAction.Preview) } }
    CompositionLocalProvider(LocalMessageLinkOpen provides openLink,LocalMessageResourceOpen provides openResource,
        LocalOpeningMessageAnchor provides loading,content=content)
    failure?.let { error->
        val text=if(!error.appOwned) error.text else if(error.status!=null)productText("电脑请求失败（%@）",error.status) else productText(error.text)
        AlertDialog(onDismissRequest={ failure=null },modifier=Modifier.testTag("message-link-error"),
            title={ Text(productText("无法打开链接")) },text={ SelectionContainer { Text(text) } },
            confirmButton={ TextButton(onClick={ failure=null },modifier=Modifier.testTag("message-link-dismiss")) { Text(productText("好")) } },
            dismissButton={ TextButton(onClick={ if(read.active&&session.messageLinkCurrent(owner)) selected?.let { clipboard.setText(AnnotatedString(it)) };failure=null },modifier=Modifier.testTag("message-link-copy")) { Text(productText("复制链接")) } })
    }
    preview?.let { opened->key(opened) {
        NativeResourcePreview(opened.resource,close={ if(preview===opened) {
            preview=null;read.value?.cancel();read.value=null;loading=null
        } },current={
            val now=session.messageLinkContext(session.messages.firstOrNull { it.id==message.id } ?: message)
            read.active&&preview===opened&&ResourcePresentation(now.key,now.selection,now.connection,now.message.id,now.message.attachments.map { it.resourceIdentity })==presentation &&
                session.messages.any { it.id==message.id }
        },retry={
            if(read.active&&preview===opened&&loading==null&&session.messageLinkCurrent(owner)) {
                val reference=if(opened.attachmentIdentity==null)opened.reference else owner.message.attachments
                    .singleOrNull {it.resourceIdentity==opened.attachmentIdentity}?.let {file->
                        Uri.Builder().scheme("codex-attachment").authority("open").appendQueryParameter("id",file.id).build().toString()
                    }
                if(reference!=null)openResource(reference,"resource-preview-retry",MessageResourceAction.Preview)
                else {selected=opened.reference;preview=null;failure=MessageLinkFailure("此文件尚未由电脑提供，请刷新对话后重试。")}
            }
        },refreshing=loading!=null)
    } }
}

@Composable internal fun MessageLinkWait(tag: String) {
    if(LocalOpeningMessageAnchor.current?.startsWith(tag+":")==true) {
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp).testTag("$tag-link-wait"),horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
            Text(productText("正在下载并打开文件…"),style=MaterialTheme.typography.bodySmall)
        }
    }
}
