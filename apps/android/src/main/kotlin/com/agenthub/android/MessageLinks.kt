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
internal val LocalMessageDownloadProgress=staticCompositionLocalOf<ResourceDownloadProgress?> { null }
private class MessageResourceJob { var value: Job?=null;var thumbnail: Job?=null;var active=true;var progressOwner: Any?=null }
private data class MessageLinkFailure(val text: String,val appOwned: Boolean=true,val status: Int?=null)
private data class ResourcePresentation(val key: String,val selection: Long,val connection: Long,val thread: String?,val message: String,val resources: List<String>)
private fun resourcePresentation(owner: MobileSession.MessageLinkContext)=ResourcePresentation(owner.key,owner.selection,owner.connection,
    owner.resourceThread,owner.message.replyMessageId?:owner.message.id,owner.message.attachments.map { it.resourceIdentity })
private class MessageResourcePreview(val resource: MobileSession.PreparedMessageResource,val reference: String,val attachmentIdentity: String?,val owner: MobileSession.MessageLinkContext)

/** All message links are handled here, including unsupported schemes. Never forwards Host auth. */
@Composable internal fun MessageLinkOpening(session: MobileSession,message: MobileMessage,content: @Composable ()->Unit) {
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val owner=LocalSubagentMessageContext.current?:session.messageLinkContext(message)
    val presentation=resourcePresentation(owner)
    val tasks=rememberCoroutineScope()
    val read=remember(owner) { MessageResourceJob() }
    val mediaSnapshots=remember(owner) { NativeMessageMediaSnapshots() }
    var selected by remember(owner) { mutableStateOf<String?>(null) }
    var failure by remember(owner) { mutableStateOf<MessageLinkFailure?>(null) }
    var loading by remember(owner) { mutableStateOf<String?>(null) }
    var downloadProgress by remember(owner) { mutableStateOf<ResourceDownloadProgress?>(null) }
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
    DisposableEffect(owner) { onDispose { read.active=false;read.progressOwner=null;read.value?.cancel();read.thumbnail?.cancel() } }
    val shareTitle by rememberUpdatedState(productText("保存或分享文件"))
    val openOwnedResource: (MobileSession.MessageLinkContext,String,String,MessageResourceAction)->Unit=remember(owner,presentation) { { requestOwner,reference,anchor,action->
        if(read.active&&session.messageLinkCurrent(requestOwner)&&loading==null) {
            selected=reference;failure=null
            val destination=MessageLinkDestination.resolve(reference)
            try {
                when(destination.kind) {
                    "web" -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,Uri.parse(reference))
                    "external" -> context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(reference)).addCategory(Intent.CATEGORY_BROWSABLE))
                    "computerWeb" -> failure=MessageLinkFailure("此网址指向电脑的本机地址，手机无法直接访问。请使用手机可连接的局域网或 VPN 网址。")
                    "attachment","computerFile" -> {
                        loading=anchor;downloadProgress=ResourceDownloadProgress()
                        val progressOwner=Any();read.progressOwner=progressOwner
                        read.value=tasks.launch(Dispatchers.Main.immediate) {
                            try {
                                val result=session.prepareMessageResource(requestOwner,reference,File(context.cacheDir,"resources")) { value ->
                                    tasks.launch(Dispatchers.Main.immediate) {
                                        if(read.progressOwner===progressOwner&&read.active&&session.messageLinkCurrent(requestOwner)&&loading==anchor)downloadProgress=value
                                    }
                                }
                                if(isActive&&read.active&&session.messageLinkCurrent(requestOwner)&&loading==anchor) {
                                    when(action) {
                                        MessageResourceAction.Preview -> {
                                            val opened=MessageResourcePreview(result,reference,
                                            if(destination.kind=="attachment")requestOwner.message.attachments.firstOrNull {
                                                MessageLinkDestination.sameAttachmentId(it.id,destination.attachmentId)
                                            }?.resourceIdentity else null,requestOwner)
                                            preview=opened
                                            read.thumbnail?.cancel()
                                            // Optional local work must never keep the normal preview/download busy.
                                            if(result.mediaType.startsWith("image/"))read.thumbnail=tasks.launch {
                                                NativeMessageMediaThumbnails.publishOpened(
                                                    decode={ NativeMessageMediaThumbnails.decode(result.local) },
                                                    current={ read.active&&preview===opened&&session.messageLinkCurrent(requestOwner) },
                                                    publish={ mediaSnapshots.put(reference,it) })
                                            }
                                        }
                                        MessageResourceAction.Share -> NativeResourceIntents.launch(context,result,true,shareTitle)
                                    }
                                }
                            } catch(cancel: CancellationException) { throw cancel }
                              catch(error: Exception) {
                                if(isActive&&read.active&&session.messageLinkCurrent(requestOwner)&&loading==anchor) {
                                    val native=error as? CodexCommandError
                                    failure=when(error) {
                                        is android.content.ActivityNotFoundException,is SecurityException -> MessageLinkFailure("手机上没有可打开此链接的应用。")
                                        is IllegalArgumentException -> MessageLinkFailure("读取电脑资源失败，请重试。")
                                        else -> MessageLinkFailure(error.message ?: "读取电脑资源失败，请重试。",native?.appOwnedCopy ?: (error.message==null),native?.status)
                                    }
                                }
                            } finally { if(isActive&&read.active&&session.messageLinkCurrent(requestOwner)&&loading==anchor) { loading=null;downloadProgress=null;read.progressOwner=null;read.value=null } }
                        }
                    }
                    else -> failure=MessageLinkFailure("手机暂不支持此类链接，可复制后在对应应用中打开。")
                }
            } catch(_: android.content.ActivityNotFoundException) { failure=MessageLinkFailure("手机上没有可打开此链接的应用。") }
              catch(_: SecurityException) { failure=MessageLinkFailure("手机上没有可打开此链接的应用。") }
              catch(_: IllegalArgumentException) { failure=MessageLinkFailure("手机暂不支持此类链接，可复制后在对应应用中打开。") }
        }
    } }
    val openResource: (String,String,MessageResourceAction)->Unit=remember(owner,openOwnedResource) { { reference,anchor,action ->
        openOwnedResource(owner,reference,anchor,action)
    } }
    val openLink: (String,String)->Unit=remember(openResource) { { reference,anchor->openResource(reference,anchor,MessageResourceAction.Preview) } }
    CompositionLocalProvider(LocalNativeMessageMediaSnapshots provides mediaSnapshots,LocalMessageLinkOpen provides openLink,LocalMessageResourceOpen provides openResource,
        LocalOpeningMessageAnchor provides loading,LocalMessageDownloadProgress provides downloadProgress,content=content)
    failure?.let { error->
        val text=if(!error.appOwned) error.text else if(error.status!=null)productText("电脑请求失败（%@）",error.status) else productText(error.text)
        AlertDialog(onDismissRequest={ failure=null },modifier=Modifier.testTag("message-link-error"),
            title={ Text(productText("无法打开链接")) },text={ SelectionContainer { Text(text) } },
            confirmButton={ TextButton(onClick={ failure=null },modifier=Modifier.testTag("message-link-dismiss")) { Text(productText("好")) } },
            dismissButton={ TextButton(onClick={ if(read.active&&session.messageLinkCurrent(owner)) selected?.let { clipboard.setText(AnnotatedString(it)) };failure=null },modifier=Modifier.testTag("message-link-copy")) { Text(productText("复制链接")) } })
    }
    preview?.let { opened->key(opened) {
        NativeResourcePreview(opened.resource,close={ if(preview===opened) {
            preview=null;read.progressOwner=null;read.value?.cancel();read.value=null;read.thumbnail?.cancel();read.thumbnail=null;loading=null;downloadProgress=null
        } },current={
            val now=session.resolveMessageResourceOwner(opened.owner)
            read.active&&preview===opened&&now!=null&&resourcePresentation(now)==presentation
        },retry={
            val now=session.resolveMessageResourceOwner(opened.owner)
            if(read.active&&preview===opened&&loading==null&&now!=null&&resourcePresentation(now)==presentation) {
                val reference=if(opened.attachmentIdentity==null)opened.reference else now.message.attachments
                    .singleOrNull {it.resourceIdentity==opened.attachmentIdentity}?.let {file->
                        Uri.Builder().scheme("codex-attachment").authority("open").appendQueryParameter("id",file.id).build().toString()
                    }
                if(reference!=null)openOwnedResource(now,reference,"resource-preview-retry",MessageResourceAction.Preview)
                else {selected=opened.reference;preview=null;failure=MessageLinkFailure("此文件尚未由电脑提供，请刷新对话后重试。")}
            }
        },refreshing=loading!=null,resolveDocument={ source,reference,digest,progress ->
            val now=session.resolveMessageResourceOwner(opened.owner)
                ?: throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
            if(!read.active||preview!==opened||resourcePresentation(now)!=presentation)
                throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
            session.prepareDocumentResource(now,source,reference,digest,File(context.cacheDir,"resources"),progress)
        })
    } }
}

@Composable internal fun MessageLinkWait(tag: String) {
    if(LocalOpeningMessageAnchor.current?.startsWith(tag+":")==true) {
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp).testTag("$tag-link-wait"),horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
            Box(Modifier.weight(1f)) { NativeResourceDownloadProgress(LocalMessageDownloadProgress.current,"$tag-link-progress") }
        }
    }
}
