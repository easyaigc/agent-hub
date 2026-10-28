package com.agenthub.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.agenthub.core.*

@Composable fun AgentHubApp(session: MobileSession, onThemeChanged: (Boolean) -> Unit = {}) {
    val dark = session.appearance == "dark" || session.appearance == "system" && isSystemInDarkTheme()
    SideEffect { onThemeChanged(dark) }
    val colors = if (dark) darkColorScheme(background=Color.Black, surface=Color(0xFF1C1C1E), primary=Color.White, secondary=Color(0xFF98989D), onSurfaceVariant=Color(0xFF98989D))
        else lightColorScheme(background=Color.White,surface=Color(0xFFF2F2F7),primary=Color.Black,secondary=Color(0xFF8A8A8E),onSurfaceVariant=Color(0xFF8A8A8E))
    ProductTextProvider(session.language) {
    MaterialTheme(colorScheme=colors) {
        // Original OS results outlive the visible recovery page. Known decoded
        // format failures can seal bytes; crypto/I/O and in-flight restores keep
        // the original callback in the OS registry without consuming it early.
        if(session.canReceiveOriginalAttachmentResults) {
            NativeDocumentPickerResult(session.documentPicker)
            NativePhotoPickerResult(session.photoPicker)
            NativeCameraCaptureResult(session.cameraCapture)
        }
        if(session.localRecoveryRequired) {
            LocalRecoveryNotice(session.restoring,session::retryLocalRecovery)
            return@MaterialTheme
        }
        var sidebar by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf<String?>(null) }
        var editing by remember { mutableStateOf<MobileClient?>(null) }
        var composerFocused by remember { mutableStateOf(false) }
        var attachmentPreview by remember { mutableStateOf(false) }
        val effectiveSheet=if(session.migrationController?.presented==true)"migration"else if(session.aiSharingRequest!=null) "ai-sharing" else sheet
        val noticePresentation=session.capturePanelNoticePresentation()
        val noticeBinding=session.panelNoticeBinding
        DisposableEffect(session) { onDispose { session.coverPanelNotices();session.setPhoneReadingSurface(false) } }
        VoicePermission(session.voice)
        VoicePermission(session.questionVoice)
        NativePhotoAuthorizationResult(session)
        NativeRecentPhotoLifecycle(session.recentPhotos)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val nativeKeyboard = remember(view) { androidx.core.view.SoftwareKeyboardControllerCompat(view) }
        val mainPaneFocus=remember { FocusRequester() }
        var previousSidebar by remember { mutableStateOf(false) }
        LaunchedEffect(sidebar,effectiveSheet,session.error) {
            val closing=previousSidebar&&!sidebar
            previousSidebar=sidebar
            if(closing&&effectiveSheet==null&&session.error==null) {
                withFrameNanos { }
                mainPaneFocus.requestFocus();keyboard?.hide();nativeKeyboard.hide()
            }
        }
        val openSheet: (String) -> Unit = { session.coverPanelNotices();session.chatNotifications?.navigationWillChange();focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide(); sheet=it }
        LaunchedEffect(effectiveSheet,session.pendingChatSearchToken) {
            if(effectiveSheet==null&&session.pendingChatSearchToken!=null) { withFrameNanos { };session.applyPendingChatSearch() }
        }
        LaunchedEffect(sheet,session.error) {
            if(sheet==null || session.error!=null) {
                // A Dialog can restore focus in the Activity as its window leaves.
                // Dismiss the main-window IME after that composition has drawn.
                withFrameNanos { }
                focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide()
            }
        }
        val inbox=session.externalInbox
        val inboxSurface=remember(inbox) { java.util.UUID.randomUUID().toString() }
        DisposableEffect(inbox,inboxSurface) { inbox?.claimSurface(inboxSurface);onDispose { inbox?.releaseSurface(inboxSurface) } }
        val mainWindowFocused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(inbox?.wantsPresentation,inbox?.presentation?.id,mainWindowFocused,sidebar,effectiveSheet,session.error,session.voice?.active,attachmentPreview,session.importing) {
            if(sheet=="external-attachments"&&inbox?.presentation==null)sheet=null
            if(inbox!=null&&mainWindowFocused&&!sidebar&&effectiveSheet==null&&session.error==null&&session.voice?.active!=true&&!attachmentPreview&&!session.importing&&(inbox.presentation!=null||inbox.presentIfWanted(inboxSurface)))openSheet("external-attachments")
        }
        LaunchedEffect(session.incomingEasyWorkLink) { if(session.incomingEasyWorkLink!=null) { editing=null;openSheet("add") } }
        BackHandler(sidebar && sheet == null && session.error == null) { session.coverPanelNotices();session.chatNotifications?.navigationWillChange();sidebar=false; focus.clearFocus() }
        Surface(Modifier.fillMaxSize().focusRequester(mainPaneFocus).focusable(enabled=!sidebar&&effectiveSheet==null&&session.error==null), color=colors.background) {
            // Consume system/IME insets once for the entire product surface. The fixed header
            // stays in the resized viewport on API 26 as well as edge-to-edge API 37.
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().clipToBounds().testTag("product-surface")) {
                val width = minOf(maxWidth-MobileDesign.sidebarOutsideMinWidth.dp,MobileDesign.sidebarMaxWidth.dp)
                val density = LocalDensity.current
                val widthPx = with(density) { width.toPx() }
                val executionMaxHeight = (maxHeight-220.dp).coerceIn(80.dp,320.dp)
                val inlineToolApprovals=(maxWidth>maxHeight || density.fontScale>1.3f) && session.selectedThread.isNotEmpty()
                var drag by remember { mutableStateOf<Float?>(null) }
                val settled by animateFloatAsState(if(sidebar) 0f else -widthPx,label="sidebar")
                val offset = drag ?: settled
                Column(Modifier.fillMaxSize().pointerInput(sidebar,widthPx) {
                    var allowed=false
                    detectHorizontalDragGestures(onDragStart={ start -> allowed=sidebar || start.x < with(density) { 24.dp.toPx() }; if(allowed) { session.coverPanelNotices();session.chatNotifications?.navigationWillChange();focus.clearFocus(); keyboard?.hide(); drag=if(sidebar) 0f else -widthPx } },onHorizontalDrag={ change,amount -> if(allowed) { change.consume(); drag=((drag ?: settled)+amount).coerceIn(-widthPx,0f) } },onDragEnd={ if(allowed) sidebar=(drag ?: settled)>-widthPx/2; drag=null },onDragCancel={ drag=null })
                }) {
                    if(inbox?.receiving==true)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp).testTag("external-attachment-receiving"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp);Text(productText("正在导入附件…"),fontSize=12.sp,color=colors.onSurfaceVariant)
                    }
                    ChatHeader(session, openSidebar={ session.coverPanelNotices();session.chatNotifications?.navigationWillChange();focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide(); sidebar=true }, openClients={ openSheet("clients") }, newChat={ openSheet("projects") }, settings={ openSheet("settings") },generation={ openSheet("generation") },search={ openSheet("chat-search") },archived={ openSheet("archived-chats") },replyRecords={ openSheet("reply-records") },composerFocused=composerFocused,dismissKeyboard={ focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide() })
                    NativeChatNotificationOpenStatus(session.chatNotifications)
                    if (session.restoring) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else if (!session.hasClient) Onboarding { openSheet("add") }
                    else {
                        if (!session.selectedClient.isDemo && !session.selectedClient.online) Text(session.selectedClient.connectionLabel,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).testTag("offline-cache"),fontSize=12.sp,color=colors.onSurfaceVariant)
                        ConversationSyncBanner(session)
                        session.phoneReadingError?.let { original ->
                            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("phone-reading-error")) {
                                Text(productText("无法保存手机阅读状态，已有记录已保留，请稍后重试。"),fontSize=12.sp,color=colors.onSurfaceVariant)
                                Text(original,fontSize=12.sp,color=colors.onSurfaceVariant)
                                TextButton(onClick=session::retryPhoneReading,modifier=Modifier.testTag("phone-reading-retry")) { Text(productText("重试")) }
                            }
                        }
                        if (session.selectedThread.isEmpty() && session.pendingCreationMessage==null) Column(Modifier.weight(1f).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text(productText("开始一段新对话"),fontSize=22.sp,fontWeight=FontWeight.SemiBold); Text(productText("从侧栏选择项目或继续对话。"),Modifier.padding(top=10.dp),color=colors.onSurfaceVariant,fontSize=14.sp) }
                        else ChatMessages(session,dark,Modifier.weight(1f),inlineToolApprovals)
                        val bottomScroll=rememberScrollState()
                        LaunchedEffect(session.targetKey) { bottomScroll.scrollTo(0) }
                        // Keep natural control heights when the IME leaves a short
                        // viewport; every control can then be scrolled into view.
                        Column(Modifier.fillMaxWidth().verticalScroll(bottomScroll).testTag("bottom-controls")) {
                            if (!session.selectedClient.isDemo && !session.canCompose) Text(productText(if(session.currentChat?.archived==true) { if(session.supportsChatManagement) "对话已归档，仅支持查看，可在对话选项中恢复。" else "对话已归档，仅支持查看，可在电脑端恢复。" } else if(session.selectedClient.mode in listOf("history", "history-only")) "历史连接仅支持查看，草稿保存在手机。" else "等待电脑确认连接与控制权，草稿保存在手机。"),Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("readonly-notice"),fontSize=12.sp,color=colors.onSurfaceVariant)
                            if(!session.selectedClient.isDemo) { ExecutionPanels(session,executionMaxHeight,autoPresentQuestions=effectiveSheet==null && !sidebar && session.error==null,inlineToolApprovals=inlineToolApprovals,noticeVisibility=noticeBinding); GenerationControls(session,compactLayout=inlineToolApprovals) { openSheet("generation") } }
                            QueuedCommands(session,interactive=!sidebar && effectiveSheet==null && session.error==null)
                            StorageIdentityNotice(session)
                            CompositionLocalProvider(LocalExternalAttachmentSurface provides inboxSurface) { Composer(session,interactive=!sidebar && effectiveSheet==null && session.error==null,onAttachmentPreview={ if(it)session.coverPanelNotices();attachmentPreview=it }) { composerFocused=it } }
                        }
                    }
                }
                // Keep the drawer/list mounted: position changes do not destroy scroll/search state.
                val interactiveDrawer=sidebar || drag!=null
                val closingDrawer=!interactiveDrawer && offset > -widthPx+.5f
                val panelUncovered=!interactiveDrawer && !closingDrawer && effectiveSheet==null && session.error==null &&
                    !attachmentPreview && session.voice?.active!=true && session.incomingEasyWorkLink==null && !session.importing && inbox?.presentation==null
                SideEffect {
                    session.setPhoneReadingSurface(mainWindowFocused && panelUncovered)
                    session.presentPanelNotices(noticePresentation,panelUncovered)
                }
                // Retain the dimming surface for the same motion as the panel, as on iOS.
                // Its closing-only hits are absorbed until the visible transition finishes.
                if (interactiveDrawer || closingDrawer) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=MobileDesign.sidebarBackdropAlpha*((offset+widthPx)/widthPx).coerceIn(0f,1f))).clickable { focus.clearFocus(); sidebar=false }.testTag(if(interactiveDrawer) "sidebar-backdrop" else "sidebar-closing-backdrop").semantics { if(closingDrawer) hideFromAccessibility() })
                Box(Modifier.width(width).fillMaxHeight().offset { IntOffset(offset.toInt(),0) }.semantics { if(!sidebar && drag==null) hideFromAccessibility() }.pointerInput(sidebar,drag!=null) {
                    // The kept-mounted panel still draws while closing. Like iOS's disabled
                    // panel during settlement, consume its remaining hits before child controls.
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                        if(!sidebar && drag==null) {
                            down.consume()
                            do {
                                val event=awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while(event.changes.any { it.pressed })
                        }
                    }
                }.pointerInput(widthPx) {
                    detectHorizontalDragGestures(onDragStart={ session.coverPanelNotices();session.chatNotifications?.navigationWillChange();focus.clearFocus(); keyboard?.hide(); drag=0f },onHorizontalDrag={ change,amount -> change.consume(); drag=((drag ?: 0f)+amount).coerceIn(-widthPx,0f) },onDragEnd={ sidebar=(drag ?: 0f)>-widthPx/2; drag=null },onDragCancel={ drag=null })
                }) {
                    Sidebar(session,close={ sidebar=false; focus.clearFocus(); keyboard?.hide();nativeKeyboard.hide() },clients={ openSheet("clients") },newChat={ openSheet("projects") },settings={ openSheet("settings") },addProject={ openSheet("add-project") },active=sidebar&&effectiveSheet==null&&session.error==null)
                }
                if (effectiveSheet != null) CompositionLocalProvider(LocalExternalAttachmentSurface provides inboxSurface) { NativeSheetHost(effectiveSheet, session, editing, close={ session.coverPanelNotices();session.chatNotifications?.navigationWillChange();if(effectiveSheet=="migration"){session.migrationController?.close();sheet=null;editing=null} else if(effectiveSheet=="ai-sharing") session.dismissAISharing() else if(effectiveSheet=="external-attachments") { if(inbox?.dismiss(inboxSurface)!=false) { sheet=null;editing=null } } else { session.dismissIncomingLink(session.incomingEasyWorkLink?.presentation);sheet=null; editing=null } }, navigate={ page -> session.coverPanelNotices();session.chatNotifications?.navigationWillChange();if(effectiveSheet=="migration")session.migrationController?.close();if(page!="add")session.dismissIncomingLink(session.incomingEasyWorkLink?.presentation);editing=null; sheet=page }, edit={ client -> session.coverPanelNotices();session.chatNotifications?.navigationWillChange();editing=client; sheet="add" }) }
                session.error?.let { failure ->
                    androidx.compose.ui.window.Dialog(onDismissRequest={ session.error=null },properties=androidx.compose.ui.window.DialogProperties(dismissOnClickOutside=false)) {
                        Surface(shape=RoundedCornerShape(28.dp),color=colors.surface,modifier=Modifier.widthIn(max=320.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(productText(session.errorTitle),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                                Text(session.errorProductKey?.let { productText(it) } ?: failure,Modifier.padding(top=8.dp,bottom=16.dp).testTag("session-error"),fontSize=14.sp,lineHeight=18.sp,color=colors.onSurfaceVariant)
                                TextButton(onClick={ session.error=null },modifier=Modifier.fillMaxWidth().height(44.dp).testTag("dismiss-error"),shape=RoundedCornerShape(22.dp),colors=ButtonDefaults.textButtonColors(containerColor=colors.onSurface.copy(alpha=.08f))) { Text(productText("好"),fontSize=17.sp,color=colors.onSurface) }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
// Native rendering of the same floating outline/shadow and grouped toolbar as SwiftUI.
@Composable internal fun Modifier.floatingProduct(radius: Float = MobileDesign.floatingRadius): Modifier {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(radius.dp)
    val fill=if(colors.background==Color.Black) colors.surface else Color(0xFFF7F7F8)
    // Render the shared 8% shadow ourselves: API 26 cannot apply colored native elevation
    // shadows, which would otherwise make this toolbar much darker than SwiftUI.
    return drawWithCache {
        val blur=MobileDesign.floatingShadowRadius.dp.toPx()
        val padding=kotlin.math.ceil(blur*2).toInt()
        val bitmap=android.graphics.Bitmap.createBitmap(kotlin.math.ceil(size.width).toInt()+padding*2,kotlin.math.ceil(size.height).toInt()+padding*2,android.graphics.Bitmap.Config.ARGB_8888)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.argb((255*MobileDesign.floatingShadowAlpha).toInt(),0,0,0)
            maskFilter=android.graphics.BlurMaskFilter(blur,android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val r=radius.dp.toPx()
        android.graphics.Canvas(bitmap).drawRoundRect(android.graphics.RectF(padding.toFloat(),padding.toFloat(),padding+size.width,padding+size.height),r,r,paint)
        val image=bitmap.asImageBitmap()
        onDrawBehind { drawImage(image,Offset(-padding.toFloat(),-padding+MobileDesign.floatingShadowYOffset.dp.toPx())) }
    }
        .clip(shape).background(fill).border(MobileDesign.floatingStrokeWidth.dp,colors.onSurface.copy(alpha=MobileDesign.floatingStrokeAlpha),shape)
}
@Composable private fun ChatHeader(session: MobileSession,openSidebar: () -> Unit,openClients: () -> Unit,newChat: () -> Unit,settings: () -> Unit,generation: () -> Unit,search: ()->Unit,archived: ()->Unit,replyRecords: ()->Unit,composerFocused: Boolean,dismissKeyboard: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    val t=currentProductText()
    var menu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatManagementContext?>(null) }
    var endWaiting by remember { mutableStateOf<SavedOperation?>(null) }
    LaunchedEffect(session.targetKey) { menu=false;deleteTarget=null;endWaiting=null }
    Row(Modifier.fillMaxWidth().padding(horizontal=MobileDesign.headerHorizontalInset.dp).padding(top=MobileDesign.headerTopInset.dp,bottom=MobileDesign.headerBottomInset.dp).testTag("chat-header"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.floatingProduct()) { IconAction("menu",productText("打开侧栏"),"open-sidebar",onClick=openSidebar) }
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        Row(Modifier.widthIn(max=MobileDesign.clientHeaderMaxWidth.dp).heightIn(min=48.dp).floatingProduct().testTag("client-selector").clickable(onClick=openClients).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            if(session.hasClient && session.selectedClient.isConnecting) CircularProgressIndicator(Modifier.size(12.dp).testTag("client-connecting"),strokeWidth=1.5.dp)
            else Box(Modifier.size(6.dp).clip(CircleShape).background(if(session.hasClient && session.selectedClient.online) Color(0xFF34C759) else colors.secondary))
            Column(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(if(session.hasClient) session.selectedClient.name else productText("添加电脑"),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                val project=session.projects.firstOrNull { p -> session.threads.firstOrNull { it.id==session.selectedThread }?.belongs(p)==true }
                if(project!=null) Text(project.name,fontSize=10.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            ProductIcon("down",Modifier.size(12.dp),colors.onSurfaceVariant)
        }
        }
        Box {
            Row(Modifier.floatingProduct()) {
                if(composerFocused) IconAction("keyboard",productText("收起键盘"),"composer-dismiss-keyboard",onClick=dismissKeyboard)
                IconAction("new",productText("新聊天"),"new-chat",enabled=session.canCreateThread,onClick=newChat)
                IconAction("more",productText("对话选项"),"chat-menu") { menu=true }
            }
            DropdownMenu(expanded=menu,onDismissRequest={ menu=false }) {
                session.currentChat?.let { thread ->
                    Text(session.chatLifecycleStatus(thread,t),Modifier.padding(horizontal=16.dp,vertical=8.dp).widthIn(max=300.dp).testTag("chat-lifecycle-status"),fontSize=12.sp,color=colors.onSurfaceVariant)
                    if(session.supportsChatManagement) {
                        DropdownMenuItem(text={ Text(productText(if(session.currentChatPinned)"取消置顶" else "置顶")) },enabled=session.canManageChat(thread)&&thread.archived!=true,
                            leadingIcon={ ProductIcon(if(session.currentChatPinned)"unpin" else "pin",Modifier.testTag("chat-pin-icon"),if(session.canManageChat(thread)&&thread.archived!=true)colors.onSurface else colors.onSurface.copy(alpha=.38f)) },
                            onClick={ menu=false;session.manageChat(if(session.currentChatPinned)"unpin" else "pin") },modifier=Modifier.testTag("chat-pin"))
                    }
                    if(session.canSearchHistory)DropdownMenuItem(text={ Text(productText("在聊天中查找")) },leadingIcon={ ProductIcon("search",Modifier.testTag("chat-search-icon")) },onClick={ menu=false;search() },modifier=Modifier.testTag("chat-search-open"))
                    if(session.supportsChatManagement) {
                        DropdownMenuItem(text={ Text(productText(if(thread.archived==true)"恢复对话" else "归档")) },enabled=session.canManageChat(thread),
                            leadingIcon={ ProductIcon("archive",Modifier.testTag("chat-archive-icon"),if(session.canManageChat(thread))colors.onSurface else colors.onSurface.copy(alpha=.38f)) },
                            onClick={ menu=false;session.manageChat(if(thread.archived==true)"unarchive" else "archive") },modifier=Modifier.testTag("chat-archive"))
                        DropdownMenuItem(text={ Text(productText("删除"),color=if(session.canManageChat(thread))colors.error else Color.Unspecified) },enabled=session.canManageChat(thread),
                            leadingIcon={ ProductIcon("trash",Modifier.testTag("chat-delete-icon"),if(session.canManageChat(thread))colors.error else colors.onSurface.copy(alpha=.38f)) },
                            onClick={ menu=false;dismissKeyboard();deleteTarget=session.captureChatManagement() },modifier=Modifier.testTag("chat-delete"))
                    }
                }
                if(session.hasClient) DropdownMenuItem(text={ Text(productText("已归档对话")) },leadingIcon={ ProductIcon("archive") },onClick={ menu=false;archived() },modifier=Modifier.testTag("archived-chats"))
                session.pendingChatActions().forEach { record -> key(record.target,record.id) {
                    DropdownMenuItem(text={ Column { Text(session.chatActionRecordLabel(record,t));Text(productText("查询聊天操作记录"),fontSize=12.sp,color=colors.onSurfaceVariant) } },leadingIcon={ ProductIcon("refresh") },
                        onClick={ val original=record;session.queryChatAction(original);menu=false },
                        modifier=Modifier.testTag("chat-action-query-${record.id}").semantics { contentDescription=session.chatActionRecordDescription(record,t) })
                    if(com.agenthub.core.ChatManagementRules.pending(record.phase)&&!record.detached)
                        DropdownMenuItem(text={ Text(productText("结束等待 · ")+session.chatActionRecordLabel(record,t)) },onClick={ val original=record;menu=false;dismissKeyboard();endWaiting=original },modifier=Modifier.testTag("chat-action-end-${record.id}"))
                } }
                if(session.hasClient && !session.selectedClient.isDemo) DropdownMenuItem(text={ Text(productText("刷新历史")) },onClick={ menu=false; session.refresh(userInitiated=true); session.loadHistory(userInitiated=true) },modifier=Modifier.testTag("refresh-history"))
                if(session.supportsGeneration) DropdownMenuItem(text={ Text(productText("模型、推理与权限")) },onClick={ menu=false; generation() },modifier=Modifier.testTag("menu-generation"))
                if(session.detachedReplies.isNotEmpty()) DropdownMenuItem(text={ Text(productText("发送记录")) },leadingIcon={ ProductIcon("history") },
                    onClick={ menu=false;replyRecords() },modifier=Modifier.testTag("reply-delivery-records"))
                session.detachedCreations.forEach { record ->
                    DropdownMenuItem(text={ Text(productText("查询记录 · ")+when(record.phase) { "confirmed"->productText("已确认"); "rejected"->productText("未提交"); else->productText("待确认") }) },enabled=session.transferStatus==null,onClick={ menu=false; session.queryOperation(record) },modifier=Modifier.testTag("query-detached-${record.id}"))
                }
                DropdownMenuItem(text={ Text(productText("设置")) },onClick={ menu=false; settings() },modifier=Modifier.testTag("open-settings"))
            }
        }
    }
    deleteTarget?.let { context ->
        AlertDialog(onDismissRequest={ deleteTarget=null },title={ Text(productText("删除此对话？")) },text={
            Column { Text(context.thread.title);Text(session.chatLifecycleStatus(session.currentChat?.takeIf { it.id==context.thread.id } ?: context.thread,t));
                Text(productText("将同步删除电脑端的原始聊天及其子对话，无法恢复。仅想保留历史时请选择归档。")) }
        },confirmButton={ TextButton(onClick={ deleteTarget=null;session.manageChat("delete",context) },modifier=Modifier.testTag("chat-delete-confirm")) { Text(productText("删除"),color=colors.error) } },
            dismissButton={ TextButton(onClick={ deleteTarget=null },modifier=Modifier.testTag("chat-delete-cancel")) { Text(productText("取消")) } })
    }
    endWaiting?.let { record ->
        AlertDialog(onDismissRequest={ endWaiting=null },title={ Text(productText("结束等待？")) },text={ Text(productText("仅解除本机等待，原操作仍可查询，不会取消电脑端操作或重新提交。")) },
            confirmButton={ TextButton(onClick={ endWaiting=null;session.endChatActionWaiting(record) },modifier=Modifier.testTag("chat-action-end-confirm")) { Text(productText("结束等待")) } },
            dismissButton={ TextButton(onClick={ endWaiting=null }) { Text(productText("取消")) } })
    }

}
@Composable private fun Onboarding(add: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().testTag("onboarding"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        ProductIcon("laptop",Modifier.size(36.dp),colors.onSurfaceVariant)
        Text(productText("把电脑上的工作带在身边"),Modifier.padding(top=18.dp),fontSize=20.sp,fontWeight=FontWeight.Medium)
        Text(productText("切换客户端，查看项目和对话。"),Modifier.padding(top=12.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
        Button(onClick=add,shape=RoundedCornerShape(24.dp),modifier=Modifier.padding(top=16.dp).testTag("onboarding-add")) { Text(productText("添加客户端")) }
    }
}
@Composable private fun Composer(session: MobileSession,interactive: Boolean,onAttachmentPreview: (Boolean)->Unit,onFocus: (Boolean) -> Unit) {
    val colors=MaterialTheme.colorScheme; val decision=session.decision;val inboxSurface=LocalExternalAttachmentSurface.current
    val actionContext=session.composerActionContext()
    val voice=session.voice
    val context=LocalContext.current; val tasks=rememberCoroutineScope();val intakeText=currentProductText()
    var attachmentPanel by remember { mutableStateOf<NativePhotoRequest?>(null) }
    var attachmentAction by remember { mutableStateOf<NativeAttachmentPanelAction?>(null) }
    var recentPreview by remember { mutableStateOf(false) }
    val inputDestination=session.attachmentDestination()
    LaunchedEffect(session.targetKey,inputDestination.selection) {
        if(attachmentPanel?.let { !session.acceptsAttachmentAction(it) }==true)attachmentPanel=null
    }
    var actions by remember { mutableStateOf<ComposerActionContext?>(null) }
    val suggestionOwner=remember(session,session.targetKey,session.suggestionNavigation) { java.util.UUID.randomUUID().toString() }
    var preview by remember(session,session.targetKey,session.suggestionNavigation) { mutableStateOf<MobileSuggestionAction?>(null) }
    var focused by remember { mutableStateOf(false) }
    val inputFocus=remember { FocusRequester() };val keyboard=LocalSoftwareKeyboardController.current;val focus=LocalFocusManager.current
    var insertion by remember { mutableIntStateOf(0) }
    val nativeView=LocalView.current
    val inputNavigation=session.suggestionNavigation;val currentTextDraft=session.suggestionDraft
    var textOwner by remember(session.targetKey,inputNavigation) { mutableStateOf(NativeComposerTextOwner(currentTextDraft,inputNavigation)) }
    if(!DesktopSuggestionRules.sameDraft(textOwner.draft,currentTextDraft))textOwner=NativeComposerTextOwner(currentTextDraft,inputNavigation,
        focused&&androidx.core.view.ViewCompat.getRootWindowInsets(nativeView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
    val editor=textOwner;val field=editor.value
    DisposableEffect(suggestionOwner) {
        onDispose { session.releaseSuggestionSurface(suggestionOwner) }
    }
    // Read overlay state in composition so a popup's child recomposition cannot
    // leave the session reader running behind it.
    val suggestionInteractive=interactive&&!recentPreview&&attachmentPanel==null&&attachmentAction==null&&session.documentPicker?.pending==null&&session.photoPicker?.pending==null&&session.cameraCapture?.pending==null&&actions==null&&voice?.active!=true
    val suggestionPreviewOpen=preview!=null
    SideEffect { session.claimSuggestionSurface(suggestionOwner,inputNavigation);session.reconcileSuggestionSurface(suggestionOwner,suggestionInteractive,suggestionPreviewOpen) }
    val suggestion=session.availableSuggestion
    val presentedAction=session.nextSuggestionScope?.let { source -> suggestion?.let { MobileSuggestionAction(source,it,currentTextDraft) } }
    val ghost=session.text.isEmpty()&&suggestion!=null
    val undo=session.validSuggestionUndo
    LaunchedEffect(editor) { if(editor.restoreFocus) { withFrameNanos { };if(textOwner===editor&&inputNavigation==session.suggestionNavigation)inputFocus.requestFocus() } }
    LaunchedEffect(preview,session.suggestionNavigation,session.suggestionDraft.revision,session.text,suggestion,session.nextSuggestionScope?.sourceTurnId,interactive) {
        preview?.let { if(!session.canApplySuggestion(it))preview=null }
    }
    LaunchedEffect(insertion) { if(insertion>0) { withFrameNanos { };if(inputNavigation==session.suggestionNavigation) {
        textOwner.value=TextFieldValue(session.text,TextRange(session.text.length));textOwner.draft=session.suggestionDraft
        inputFocus.requestFocus();keyboard?.show()
    } } }
    fun adopt(action: MobileSuggestionAction,mode: String) {
        if(preview!=null&&preview!==action)return
        if(session.adoptSuggestion(action,mode)) { preview=null;insertion++ }
        else if(preview===action)preview=null
    }
    fun details() { presentedAction?.takeIf(session::suggestionActionCurrent)?.let { focus.clearFocus();keyboard?.hide();preview=it } }
    preview?.let { original -> DesktopSuggestionDetails(original,session.canApplySuggestion(original),
        close={ if(preview===original)preview=null },apply={ if(preview===original)adopt(original,it) },
        ignore={ if(preview===original&&session.ignoreSuggestion(original))preview=null }) }
    val resolver=context.applicationContext.contentResolver
    LaunchedEffect(actionContext) { if(actions?.let { !session.composerActionCurrent(it) }==true) actions=null }
    fun attachmentFlowIdle()=!session.attachmentRecoveryBusy&&session.documentPicker?.pending==null&&session.photoPicker?.pending==null&&session.cameraCapture?.pending==null
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    val queued=attachmentAction
    val released=queued?.original?.menuDismissed
    LaunchedEffect(queued,windowFocused,released) {
        if(queued==null||!windowFocused||released!=true)return@LaunchedEffect
        withFrameNanos { }
        if(attachmentAction!==queued)return@LaunchedEffect
        attachmentAction=null
        val original=queued.original
        if(!session.acceptsAttachmentAction(original)||!attachmentFlowIdle())return@LaunchedEffect
        when(queued.kind) {
            NativeAttachmentAction.CAMERA -> session.cameraCapture?.begin(original)
            NativeAttachmentAction.PHOTOS -> session.photoPicker?.begin(original)
            NativeAttachmentAction.FILES -> session.documentPicker?.begin(original)
            NativeAttachmentAction.RECEIVED -> session.externalInbox?.requestPresentation(inboxSurface)
            NativeAttachmentAction.CLEAR -> session.clearAttachmentDraft(original)
            NativeAttachmentAction.RECENT -> queued.photo?.let { photo ->
                val before=session.inboxFiles(original.destination).map { it.id }.toSet()
                val source=session.recentPhotos?.source ?: NativeRecentPhotos(context)
                val job=session.importAttachments(original.destination,listOf({
                    try { source.input(photo).copy(photoSourceId=photo.id) }
                    catch(failure: NativeFileIntakeFailure) { throw IllegalStateException(intakeText.tr(failure.key),failure) }
                }),errorAtStart=original.errorGeneration)
                if(job!=null)session.recentPhotos?.observeOriginalImport(job,session,original.destination,before,photo)
            }
            NativeAttachmentAction.PASTE -> try {
                val clipboard=context.getSystemService(android.content.ClipboardManager::class.java)
                val uris=NativeFileIntake.clipboardFiles(clipboard.primaryClip)
                session.importAttachments(original.destination,uris.map { uri -> {
                    try { NativeFileIntake.read(resolver,uri) }
                    catch(failure: NativeFileIntakeFailure) { throw IllegalStateException(intakeText.tr(failure.key),failure) }
                } },errorAtStart=original.errorGeneration)
            } catch(failure: NativeFileIntakeFailure) { session.photoSelectionFailure(original,intakeText.tr(failure.key)) }
              catch(failure: Exception) { session.photoSelectionFailure(original,intakeText.tr(NativeFileIntakeFailure.UNAVAILABLE)) }
        }
    }
    attachmentPanel?.let { original -> NativeAttachmentPanel(session,original,close={ if(attachmentPanel===original)attachmentPanel=null },action={ next ->
        if(attachmentPanel===original&&session.acceptsAttachmentAction(original)&&attachmentFlowIdle()) {
            attachmentAction=next;attachmentPanel=null
        }
    }) }
    if(voice?.active==true) { VoiceInputStrip(voice,session.text);return }
    actions?.let { original -> DropdownMenu(true,{ actions=null }) {
        if(session.canStop) DropdownMenuItem(text={ Text(productText("停止任务")) },onClick={ actions=null; session.stopTask(original) },modifier=Modifier.testTag("action-stop"))
        if(session.canRunAgain) {
            DropdownMenuItem(text={ Text(productText("继续任务")) },onClick={ actions=null; session.runAgain(false,original) },modifier=Modifier.testTag("action-resume"))
            DropdownMenuItem(text={ Text(productText("重新运行上一任务")) },onClick={ actions=null; session.runAgain(true,original) },modifier=Modifier.testTag("action-restart"))
        }
    } }
    val label=when(decision.action) { "stop"->"停止任务"; "resume"->"继续任务"; "busy"->"正在处理"; else->"发送消息" }
    val actionLabel=productText(label)
    val tag=when(decision.action) { "stop"->"stop-turn"; "resume"->"resume-task"; "busy"->"composer-busy"; else->"send-message" }
    Column {
    NativeDocumentRecoveryNotice(session,interactive)
    if(interactive&&attachmentPanel==null&&attachmentAction==null&&preview==null&&actions==null&&attachmentFlowIdle()&&session.canCompose) {
        session.recentPhotos?.let { library -> NativeRecentPhotoHintView(session,library) { open -> recentPreview=open;onAttachmentPreview(open) } }
    }
    if(session.attachments.isNotEmpty()) DraftAttachmentStrip(session,interactive)
    key(suggestionOwner,undo ?: presentedAction?.handledKey,currentTextDraft.revision) {
    if(undo!=null||suggestion!=null&&session.text.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
        if(undo!=null) {
            Text(productText("已填入输入框"),Modifier.weight(1f),fontSize=12.sp,color=colors.onSurfaceVariant)
            TextButton(onClick={ if(session.undoSuggestion(undo))insertion++ },enabled=interactive,modifier=Modifier.testTag("suggestion-undo")) { Text(productText("撤销")) }
        }else TextButton(onClick=::details,enabled=interactive&&session.nextSuggestionScope!=null,modifier=Modifier.testTag("suggestion-entry")) { Text(productText("下一步建议")) }
    }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal=MobileDesign.composerHorizontalInset.dp,vertical=MobileDesign.composerVerticalInset.dp).floatingProduct(MobileDesign.composerRadius).padding(4.dp).testTag("composer")) {
      val attachments: @Composable ()->Unit = {
            IconAction("plus",productText("添加附件"),"composer-add",interactive && session.canCompose && !session.importing && session.transferStatus==null && attachmentFlowIdle() && attachmentAction==null) {
                if(session.isCurrent(inputDestination)&&session.canCompose&&!session.importing&&attachmentFlowIdle()&&attachmentAction==null&&attachmentPanel==null) {
                    focus.clearFocus(force=true);keyboard?.hide()
                    attachmentPanel=NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,waitForMenu=true)
                }
            }
        }
      val controls: @Composable ()->Unit = {
        IconAction("mic",productText("语音输入"),"voice-input",interactive && session.canUseVoice && session.voice?.active==false) { session.beginVoice() }
        Surface(shape=CircleShape,color=if(decision.enabled) colors.onSurface else colors.onSurface.copy(alpha=.12f),modifier=Modifier.size(48.dp).testTag(tag).semantics { contentDescription=actionLabel; role=Role.Button; if(!decision.enabled) disabled() }.combinedClickable(enabled=interactive&&(decision.enabled || session.canStop || session.canRunAgain),onClick={ if(decision.enabled && (session.voice?.active!=true || decision.action=="stop")) session.performAction(actionContext) },onLongClick={ if(session.composerActionCurrent(actionContext) && (session.canStop || session.canRunAgain)) actions=actionContext })) {
            Box(contentAlignment=Alignment.Center) { ProductIcon(if(decision.action=="busy") "more" else decision.action,color=if(decision.enabled) colors.background else colors.onSurface.copy(alpha=.3f)) }
        }
      }
      Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        if(!ghost)attachments()
        Box(Modifier.weight(1f)) {
        if(ghost) Text(suggestion!!.text,color=colors.onSurfaceVariant,fontSize=16.sp,lineHeight=22.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=14.dp,vertical=10.dp).testTag("suggestion-text"))
        key(editor) {
        BasicTextField(field,{ next ->
            if(textOwner===editor)editor.input(next,session.suggestionDraft,session.suggestionNavigation) { changed ->
                if(session.text!=changed)session.text=changed
                session.suggestionDraft
            }
        },Modifier.fillMaxWidth().heightIn(min=if(ghost)62.dp else 40.dp,max=160.dp).padding(horizontal=if(ghost)14.dp else 0.dp,vertical=10.dp)
            .focusRequester(inputFocus).onFocusChanged { if(textOwner===editor&&inputNavigation==session.suggestionNavigation) { focused=it.isFocused;onFocus(it.isFocused) } }
            .onPreviewKeyEvent { event ->
                if(textOwner===editor&&inputNavigation==session.suggestionNavigation&&event.type==KeyEventType.KeyDown&&event.key==Key.Tab&&!event.isShiftPressed&&!event.isCtrlPressed&&!event.isAltPressed&&!event.isMetaPressed&&focused&&ghost) {
                    presentedAction?.let { adopt(it,"fill");true } ?: false
                }else false
            }.testTag("message-input"),enabled=interactive,maxLines=6,minLines=if(ghost)2 else 1,
            textStyle=TextStyle(color=colors.onSurface,fontSize=16.sp,lineHeight=22.sp),decorationBox={ inner ->
                Box {
                    if(!ghost&&session.text.isEmpty())Text(productText("询问 %@",session.agentTitle),color=colors.onSurfaceVariant.copy(alpha=.6f),fontSize=16.sp)
                    inner()
                }
            })
        }
        }
        if(!ghost)controls()
      }
      key(suggestionOwner,presentedAction?.handledKey,currentTextDraft.revision) {
      if(ghost) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        attachments()
        TextButton(onClick={ presentedAction?.let { adopt(it,"fill") } },enabled=interactive&&session.nextSuggestionScope!=null,modifier=Modifier.testTag("suggestion-adopt")) { Text(productText("采用")) }
        TextButton(onClick=::details,enabled=interactive&&session.nextSuggestionScope!=null,modifier=Modifier.testTag("suggestion-details")) { Text(productText("全文"),fontSize=12.sp,color=colors.onSurfaceVariant) }
        Spacer(Modifier.weight(1f));controls()
      }
      }
    }
    }
}
@Composable private fun ConversationSyncBanner(session: MobileSession) {
    val issue=session.conversationSyncIssue ?: return
    val colors=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surface).padding(10.dp).testTag("conversation-sync-banner"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top) {
        ProductIcon("conversation-sync",Modifier.padding(top=2.dp).size(16.dp),colors.onSurfaceVariant)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(issue.title,Modifier.testTag("conversation-sync-failure"),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
            Text(issue.message,fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        val enabled=!session.historyLoading
        Box(Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("conversation-sync-retry").clickable(
            interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,
            onClick={ session.loadHistory() }).padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
            Text(productText("重试"),fontSize=12.sp,color=if(enabled)colors.onSurface else colors.onSurfaceVariant.copy(alpha=.5f))
        }
    }
}

@Composable private fun ChatMessages(session: MobileSession,dark: Boolean,modifier: Modifier,inlineToolApprovals: Boolean=false) {
    val list=rememberLazyListState(); val scope=rememberCoroutineScope()
    val timeline=session.timeline; val messages=session.messages; val pending=session.pendingCreationMessage
    val reading=session.visiblePhoneReading
    var readingViewport by remember(session.targetKey) { mutableStateOf<Rect?>(null) }
    var readingMarker by remember(reading) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(reading,readingViewport,readingMarker) {
        val content=reading ?: return@LaunchedEffect
        val viewport=readingViewport;val marker=readingMarker
        val visible=viewport!=null && marker!=null && NativePhoneReadingRules.visible(viewport.left,viewport.top,viewport.right,viewport.bottom,
            marker.left,marker.top,marker.right,marker.bottom)
        // A layout callback from the old body is not a reading acknowledgement for its replacement.
        withFrameNanos { }
        session.observeVisiblePhoneReading(content,visible)
    }
    DisposableEffect(session.targetKey) { onDispose { session.phoneReading.lifecycle.leave() } }
    // In compact landscape, use the same history viewport as iOS instead of a
    // second capped scroller that clips the approval heading on button focus.
    val toolApprovals=if(inlineToolApprovals) session.runtime?.approvals.orEmpty().filter { it.kind!="question" } else emptyList()
    val messageCount=timeline.rows.size+(if(pending!=null)1 else 0)+toolApprovals.size
    var initiallyLoaded by remember(session.targetKey) { mutableStateOf(false) }
    var appliedSearchToken by remember(session.targetKey) { mutableStateOf<String?>(null) }
    var paging by remember(session.targetKey) { mutableStateOf(false) }
    fun loadEarlier() {
        if(paging || session.historyLoading || !(session.timeline.hasEarlierMessages || session.historyCursor!=null)) return
        val target=session.targetKey
        val boundary=session.timeline.rows.firstOrNull()
        val firstId=boundary?.indices?.firstOrNull()?.let { session.messages[it].id }
        // Set before launching so repeated pulls and taps cannot start the same page.
        paging=true
        scope.launch {
            try {
                session.earlierMessages()?.join()
                if(session.targetKey!=target || session.error!=null) return@launch
                val updated=session.timeline
                val anchor=updated.rows.indexOfFirst { row -> row.id==boundary?.id || row.kind in listOf("tools","errors") && row.indices.any { session.messages[it].id==firstId } }
                val control=if(updated.hasEarlierMessages || session.historyCursor!=null || session.revealedEarlier) 1 else 0
                if(anchor>=0) {
                    withFrameNanos { }
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        snapshotFlow { session.targetKey!=target || list.layoutInfo.totalItemsCount>=updated.rows.size+control+toolApprovals.size+1 }.first { it }
                    } ?: return@launch
                    if(session.targetKey==target) list.requestScrollToItem(anchor+control)
                }
            } finally { paging=false }
        }
    }
    val earlierAction by rememberUpdatedState(::loadEarlier)
    val density=LocalDensity.current
    LaunchedEffect(session.scrollToLatestRevision) {
        if(session.scrollToLatestRevision>0 && messageCount>0) {
            withFrameNanos { }
            val historyControl=if(timeline.hasEarlierMessages || session.historyCursor!=null || paging || session.revealedEarlier) 1 else 0
            list.requestScrollToItem(messageCount+historyControl)
        }
    }
    LaunchedEffect(session.targetKey,messages.size,messages.lastOrNull()?.text,pending?.message?.id,toolApprovals) {
        if(!paging && messageCount>0 && (session.chatSearchJump==null||session.chatSearchJump?.token==appliedSearchToken) && (!initiallyLoaded || !list.canScrollForward)) {
            // BoxWithConstraints may subcompose this effect during measurement.
            // Request the next layout instead of forcing a nested measure pass.
            list.requestScrollToItem(messageCount+if(timeline.hasEarlierMessages || session.historyCursor!=null || paging || session.revealedEarlier) 1 else 0)
            initiallyLoaded=true
        }
    }
    LaunchedEffect(session.targetKey,session.chatSearchJump?.token) {
        val jump=session.chatSearchJump ?: return@LaunchedEffect
        withFrameNanos { }
        if(session.chatSearchJump?.token!=jump.token)return@LaunchedEffect
        val index=session.timeline.rows.indexOfFirst { it.id==jump.message.id&&it.kind=="message" }
        if(index>=0) {
            val historyControl=if(session.timeline.hasEarlierMessages||session.historyCursor!=null||paging||session.revealedEarlier)1 else 0
            list.requestScrollToItem(index+historyControl);initiallyLoaded=true;appliedSearchToken=jump.token
        }
    }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalMessageActionEndInset provides 56.dp) {
        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { readingViewport=it.boundsInRoot() }.testTag("message-list").pointerInput(session.targetKey,density) {
            // Observe the native list gesture without consuming it. A gesture must
            // begin at the actual top and finish beyond 60dp, like the iOS scroll pan.
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                val startedAtTop=!list.canScrollBackward
                var single=true
                var moved=false
                var change=down
                do {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.changes.count { it.pressed }>1) single=false
                    change=event.changes.firstOrNull { it.id==down.id } ?: break
                    if(!moved && (change.position-down.position).getDistance()>viewConfiguration.touchSlop) {
                        moved=true
                        if(change.uptimeMillis-down.uptimeMillis>=viewConfiguration.longPressTimeoutMillis) single=false
                    }
                } while(change.pressed)
                val delta=change.position-down.position
                if(!change.pressed && single && startedAtTop && !list.canScrollBackward && delta.y>=with(density) { 60.dp.toPx() } && delta.y>kotlin.math.abs(delta.x)) earlierAction()
            }
        },state=list,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=if(inlineToolApprovals)0.dp else 10.dp,bottom=if(inlineToolApprovals)0.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(timeline.hasEarlierMessages || session.historyCursor != null || paging) item(key="history-more") { TextButton(onClick=::loadEarlier,enabled=!session.historyLoading && !paging,modifier=Modifier.fillMaxWidth().testTag(if(timeline.hasEarlierMessages) "history-local-more" else "history-more")) { if(paging) { CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(6.dp)) }; Text(productText(if(paging) "正在加载更早消息…" else "显示更早消息"),fontSize=12.sp) } }
            else if(session.revealedEarlier) item { Text(productText("已显示全部消息"),Modifier.fillMaxWidth().testTag("history-start"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(timeline.rows,key={ session.targetKey+":"+it.kind+":"+it.id }) { row ->
                val located=session.chatSearchJump?.takeIf { it.message.id==row.id&&row.kind=="message" }
                if(located!=null)LocatedChatMessage(located) else when(row.kind) {
                "replyActions" -> ReplyActions(session,row.id,row.text.orEmpty(),row.runId)
                "tools" -> ToolGroup(row.id,row.indices.map { messages[it] })
                "errors" -> Column { row.indices.forEach { Text(messages[it].text,color=MaterialTheme.colorScheme.error,fontSize=14.sp) } }
                "compaction" -> Text(productText(ToolPresentation.compaction(messages[row.indices.first()].status, session.running)),Modifier.fillMaxWidth().testTag("compaction-${row.id}"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else -> row.indices.forEach { index ->
                    val message=messages[index]
                    MessageContent(session,message,dark,session.targetKey,reading?.takeIf { it.body.matches(message) }) { readingMarker=it }
                }
            } }
            pending?.let { original -> item(key=session.targetKey+":pending-creation:"+original.message.id) {
                PendingCreationMessageBubble(original,session.targetKey,dark)
            } }
            items(toolApprovals,key={ session.targetKey+":approval:"+it.turnId+":"+it.id }) { approval ->
                // Keep the48dp return-to-latest target plus its inset outside
                // the approval card, including when reading a tall large-text card.
                Box(Modifier.padding(end=56.dp)) { InlineToolApproval(session,approval,session.panelNoticeBinding) }
            }
            // Like the iOS bottom anchor, target the true end even when the last
            // message or approval is taller than the viewport.
            item(key=session.targetKey+":history-bottom") { Spacer(Modifier.height(1.dp).testTag("history-bottom")) }
        }
        }
        if(!atBottom && maxHeight>=72.dp) Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp,modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp)) { IconAction("down",productText("回到最新"),"scroll-latest") { scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount-1) } } }
    }
}
@Composable private fun MessageContent(session: MobileSession,message: MobileMessage,dark: Boolean,targetKey: String,reading: NativeVisiblePhoneReading?=null,onReadingMarker: (Rect)->Unit={}) = MessageLinkOpening(session,message) {
    val colors=MaterialTheme.colorScheme
    if(message.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        UserMessageBubble(message,targetKey,dark) { AttachmentNames(message) }
    } else Column(Modifier.fillMaxWidth().testTag("message-${message.id}")) {
        if(message.kind=="turnFailure") Column(Modifier.testTag("task-failure-history-${message.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(productText("任务失败记录"),fontSize=12.sp,color=colors.onSurfaceVariant)
            SelectionContainer { Text(message.text,fontSize=12.sp,color=colors.onSurfaceVariant) }
        }
        else if(message.kind=="plan") ProposedPlanMessage(message,targetKey,session.panelNoticeBinding,session.runtime?.proposedPlan)
        else if(message.kind=="planImplementation") Surface(shape=RoundedCornerShape(16.dp),color=colors.surface) { Column(Modifier.padding(12.dp)) { Text(productText(if(message.kind=="plan") "执行计划" else "正在执行计划"),fontWeight=FontWeight.SemiBold,fontSize=14.sp); MarkdownContent(message.text,"markdown-${message.id}") } }
        else MarkdownContent(message.text,"markdown-${message.id}")
        if(reading!=null) Box(Modifier.fillMaxWidth().height(1.dp).testTag("phone-reading-content-end")
            .onGloballyPositioned { onReadingMarker(it.boundsInRoot()) })
        AttachmentNames(message)
    }
}
@Composable private fun AttachmentNames(message: MobileMessage) { MessageResourceCards(message) }
@Composable private fun ReplyActions(session: MobileSession,id: String,text: String,turnId: String?) {
    val clipboard=LocalClipboardManager.current; val context=LocalContext.current
    val shareLabel=productText("分享回复")
    Row(Modifier.testTag("reply-actions-$id")) {
        IconAction("copy",productText("复制回复"),"copy-reply-$id") { clipboard.setText(AnnotatedString(text)) }
        ThreadForkMenu(session,turnId)
        IconAction("share",shareLabel,"share-reply-$id") { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,text) },shareLabel)) }
    }
}

@Composable internal fun LocalRecoveryNotice(restoring: Boolean,retry: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("local-recovery-required"),color=colors.background) {
        Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            ProductIcon("storage-warning",Modifier.size(32.dp).padding(bottom=4.dp).testTag("local-recovery-icon"))
            Spacer(Modifier.height(16.dp))
            Text(productText("本地数据暂时无法读取"),fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Text(productText("原有连接、草稿和聊天已保留。请重试读取，恢复前不会保存空数据。"),Modifier.padding(vertical=20.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=colors.onSurfaceVariant)
            Button(onClick=retry,enabled=!restoring,modifier=Modifier.testTag("retry-local-recovery")) { Text(productText(if(restoring) "正在读取…" else "重试读取")) }
        }
    }
}
