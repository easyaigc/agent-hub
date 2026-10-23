package com.agenthub.android

import android.content.Intent
import android.provider.OpenableColumns
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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
    MaterialTheme(colorScheme=colors) {
        var sidebar by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf<String?>(null) }
        var editing by remember { mutableStateOf<MobileClient?>(null) }
        var composerFocused by remember { mutableStateOf(false) }
        val effectiveSheet=if(session.aiSharingRequest!=null) "ai-sharing" else sheet
        VoicePermission(session.voice)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val nativeKeyboard = remember(view) { androidx.core.view.SoftwareKeyboardControllerCompat(view) }
        val openSheet: (String) -> Unit = { focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide(); sheet=it }
        LaunchedEffect(sheet,session.error) {
            if(sheet==null || session.error!=null) {
                // A Dialog can restore focus in the Activity as its window leaves.
                // Dismiss the main-window IME after that composition has drawn.
                withFrameNanos { }
                focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide()
            }
        }
        LaunchedEffect(session.incomingEasyWorkLink) { if(session.incomingEasyWorkLink!=null) { editing=null;openSheet("add") } }
        BackHandler(sidebar && sheet == null && session.error == null) { sidebar=false; focus.clearFocus() }
        Surface(Modifier.fillMaxSize(), color=colors.background) {
            // Consume system/IME insets once for the entire product surface. The fixed header
            // stays in the resized viewport on API 26 as well as edge-to-edge API 37.
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().clipToBounds().testTag("product-surface")) {
                val width = minOf(maxWidth-MobileDesign.sidebarOutsideMinWidth.dp,MobileDesign.sidebarMaxWidth.dp)
                val density = LocalDensity.current
                val widthPx = with(density) { width.toPx() }
                val executionMaxHeight = (maxHeight-220.dp).coerceIn(80.dp,320.dp)
                var drag by remember { mutableStateOf<Float?>(null) }
                val settled by animateFloatAsState(if(sidebar) 0f else -widthPx,label="sidebar")
                val offset = drag ?: settled
                Column(Modifier.fillMaxSize().pointerInput(sidebar,widthPx) {
                    var allowed=false
                    detectHorizontalDragGestures(onDragStart={ start -> allowed=sidebar || start.x < with(density) { 24.dp.toPx() }; if(allowed) { focus.clearFocus(); keyboard?.hide(); drag=if(sidebar) 0f else -widthPx } },onHorizontalDrag={ change,amount -> if(allowed) { change.consume(); drag=((drag ?: settled)+amount).coerceIn(-widthPx,0f) } },onDragEnd={ if(allowed) sidebar=(drag ?: settled)>-widthPx/2; drag=null },onDragCancel={ drag=null })
                }) {
                    ChatHeader(session, openSidebar={ focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide(); sidebar=true }, openClients={ openSheet("clients") }, newChat={ openSheet("projects") }, settings={ openSheet("settings") },generation={ openSheet("generation") },composerFocused=composerFocused,dismissKeyboard={ focus.clearFocus(force=true); keyboard?.hide(); nativeKeyboard.hide() })
                    if (session.restoring) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else if (!session.hasClient) Onboarding { openSheet("add") }
                    else {
                        if (!session.selectedClient.isDemo && !session.selectedClient.online) Text(session.selectedClient.connectionLabel,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).testTag("offline-cache"),fontSize=12.sp,color=colors.onSurfaceVariant)
                        if (session.selectedThread.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Text("开始一段新对话",fontSize=22.sp,fontWeight=FontWeight.SemiBold); Text("从侧栏选择项目或继续对话。",Modifier.padding(top=10.dp),color=colors.onSurfaceVariant,fontSize=14.sp) }
                        else ChatMessages(session,dark,Modifier.weight(1f))
                        if (!session.selectedClient.isDemo && !session.canCompose) Text(if(session.selectedClient.mode in listOf("history", "history-only")) "历史连接仅支持查看，草稿保存在手机。" else "等待电脑确认连接与控制权，草稿保存在手机。",Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("readonly-notice"),fontSize=12.sp,color=colors.onSurfaceVariant)
                        if(!session.selectedClient.isDemo) { ExecutionPanels(session,executionMaxHeight,autoPresentQuestions=effectiveSheet==null && !sidebar && session.error==null); GenerationControls(session) { openSheet("generation") } }
                        StorageIdentityNotice(session)
                        Composer(session,interactive=!sidebar && effectiveSheet==null && session.error==null) { composerFocused=it }
                    }
                }
                // Keep the drawer/list mounted: position changes do not destroy scroll/search state.
                if (sidebar || drag != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=MobileDesign.sidebarBackdropAlpha*((offset+widthPx)/widthPx))).clickable { focus.clearFocus(); sidebar=false }.testTag("sidebar-backdrop"))
                Box(Modifier.width(width).fillMaxHeight().offset { IntOffset(offset.toInt(),0) }.semantics { if(!sidebar && drag==null) hideFromAccessibility() }.pointerInput(widthPx) {
                    detectHorizontalDragGestures(onDragStart={ focus.clearFocus(); keyboard?.hide(); drag=0f },onHorizontalDrag={ change,amount -> change.consume(); drag=((drag ?: 0f)+amount).coerceIn(-widthPx,0f) },onDragEnd={ sidebar=(drag ?: 0f)>-widthPx/2; drag=null },onDragCancel={ drag=null })
                }) {
                    Sidebar(session,close={ sidebar=false; focus.clearFocus(); keyboard?.hide() },clients={ openSheet("clients") },newChat={ openSheet("projects") },settings={ openSheet("settings") },addProject={ openSheet("add-project") })
                }
                if (effectiveSheet != null) NativeSheetHost(effectiveSheet, session, editing, close={ if(effectiveSheet=="ai-sharing") session.dismissAISharing() else { session.dismissIncomingLink(session.incomingEasyWorkLink?.presentation);sheet=null; editing=null } }, navigate={ page -> if(page!="add")session.dismissIncomingLink(session.incomingEasyWorkLink?.presentation);editing=null; sheet=page }, edit={ client -> editing=client; sheet="add" })
                session.error?.let { failure ->
                    androidx.compose.ui.window.Dialog(onDismissRequest={ session.error=null }) {
                        Surface(shape=RoundedCornerShape(28.dp),color=colors.surface,modifier=Modifier.widthIn(max=320.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("无法完成操作",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                                Text(failure,Modifier.padding(top=8.dp,bottom=16.dp).testTag("session-error"),fontSize=14.sp,lineHeight=18.sp,color=colors.onSurfaceVariant)
                                TextButton(onClick={ session.error=null },modifier=Modifier.fillMaxWidth().height(44.dp).testTag("dismiss-error"),shape=RoundedCornerShape(22.dp),colors=ButtonDefaults.textButtonColors(containerColor=colors.onSurface.copy(alpha=.08f))) { Text("好",fontSize=17.sp,color=colors.onSurface) }
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
@Composable private fun ChatHeader(session: MobileSession,openSidebar: () -> Unit,openClients: () -> Unit,newChat: () -> Unit,settings: () -> Unit,generation: () -> Unit,composerFocused: Boolean,dismissKeyboard: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(session.targetKey) { menu=false }
    Row(Modifier.fillMaxWidth().padding(horizontal=MobileDesign.headerHorizontalInset.dp).padding(top=MobileDesign.headerTopInset.dp,bottom=MobileDesign.headerBottomInset.dp).testTag("chat-header"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.floatingProduct()) { IconAction("menu","打开侧栏","open-sidebar",onClick=openSidebar) }
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        Row(Modifier.widthIn(max=MobileDesign.clientHeaderMaxWidth.dp).heightIn(min=48.dp).floatingProduct().testTag("client-selector").clickable(onClick=openClients).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            if(session.hasClient && session.selectedClient.isConnecting) CircularProgressIndicator(Modifier.size(12.dp).testTag("client-connecting"),strokeWidth=1.5.dp)
            else Box(Modifier.size(6.dp).clip(CircleShape).background(if(session.hasClient && session.selectedClient.online) Color(0xFF34C759) else colors.secondary))
            Column(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(if(session.hasClient) session.selectedClient.name else "添加电脑",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                val project=session.projects.firstOrNull { p -> session.threads.firstOrNull { it.id==session.selectedThread }?.belongs(p)==true }
                if(project!=null) Text(project.name,fontSize=10.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            ProductIcon("down",Modifier.size(12.dp),colors.onSurfaceVariant)
        }
        }
        Box {
            Row(Modifier.floatingProduct()) {
                if(composerFocused) IconAction("keyboard","收起键盘","composer-dismiss-keyboard",onClick=dismissKeyboard)
                IconAction("new","新聊天","new-chat",enabled=session.canCreateThread,onClick=newChat)
                IconAction("more","对话选项","chat-menu") { menu=true }
            }
            DropdownMenu(expanded=menu,onDismissRequest={ menu=false }) {
                if(session.hasClient && !session.selectedClient.isDemo) DropdownMenuItem(text={ Text("刷新历史") },onClick={ menu=false; session.refresh(userInitiated=true); session.loadHistory(userInitiated=true) },modifier=Modifier.testTag("refresh-history"))
                if(session.supportsGeneration) DropdownMenuItem(text={ Text("模型、推理与权限") },onClick={ menu=false; generation() },modifier=Modifier.testTag("menu-generation"))
                session.detachedCreations.forEach { record ->
                    DropdownMenuItem(text={ Text("查询记录 · "+when(record.phase) { "confirmed"->"已确认"; "rejected"->"未提交"; else->"待确认" }) },enabled=session.transferStatus==null,onClick={ menu=false; session.queryOperation(record) },modifier=Modifier.testTag("query-detached-${record.id}"))
                }
                DropdownMenuItem(text={ Text("设置") },onClick={ menu=false; settings() },modifier=Modifier.testTag("open-settings"))
            }
        }
    }
}
@Composable private fun Onboarding(add: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().testTag("onboarding"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        ProductIcon("laptop",Modifier.size(36.dp),colors.onSurfaceVariant)
        Text("把电脑上的工作带在身边",Modifier.padding(top=18.dp),fontSize=20.sp,fontWeight=FontWeight.Medium)
        Text("切换客户端，查看项目和对话。",Modifier.padding(top=12.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
        Button(onClick=add,shape=RoundedCornerShape(24.dp),modifier=Modifier.padding(top=16.dp).testTag("onboarding-add")) { Text("添加客户端") }
    }
}
@Composable private fun Composer(session: MobileSession,interactive: Boolean,onFocus: (Boolean) -> Unit) {
    val colors=MaterialTheme.colorScheme; val decision=session.decision
    val voice=session.voice
    if(voice?.active==true) { VoiceInputStrip(voice,session.text);return }
    val context=LocalContext.current; val tasks=rememberCoroutineScope()
    var destination by remember { mutableStateOf<DraftDestination?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    val document=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val original=destination
        if(original!=null && session.isCurrent(original)) tasks.launch {
            try { uris.forEach { uri ->
                val file= kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else null } ?: "附件"
                    val media=context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
                        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(16_384)
                        while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=20*1024*1024) { "单个附件不能超过 20 MiB" }; out.write(buffer,0,n) }; out.toByteArray()
                    } ?: error("无法读取所选附件")
                    Triple(name,media,bytes)
                }
                session.addAttachment(file.first,file.second,file.third,original)
            } } catch(failure: Exception) { if(session.isCurrent(original)) session.error=failure.message ?: "附件读取失败，原草稿已保留" }
        }
    }
    if(actions) DropdownMenu(true,{ actions=false }) {
        if(decision.action=="stop") DropdownMenuItem(text={ Text("停止任务") },onClick={ actions=false; session.performAction() },modifier=Modifier.testTag("action-stop"))
        if(session.canRunAgain) {
            DropdownMenuItem(text={ Text("继续任务") },onClick={ actions=false; session.runAgain(false) },modifier=Modifier.testTag("action-resume"))
            DropdownMenuItem(text={ Text("重新运行上一任务") },onClick={ actions=false; session.runAgain(true) },modifier=Modifier.testTag("action-restart"))
        }
    }
    val label=when(decision.action) { "stop"->"停止任务"; "resume"->"继续任务"; "busy"->"正在处理"; else->"发送消息" }
    val tag=when(decision.action) { "stop"->"stop-turn"; "resume"->"resume-task"; "busy"->"composer-busy"; else->"send-message" }
    Column {
    if(session.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp)) { session.attachments.forEach { file -> InputChip(selected=false,onClick={ session.removeAttachment(file.manifest.id) },label={ Text(file.manifest.name) },trailingIcon={ Text("×") },modifier=Modifier.padding(end=4.dp).testTag("draft-attachment-${file.manifest.id}")) } }
    Row(Modifier.fillMaxWidth().padding(horizontal=MobileDesign.composerHorizontalInset.dp,vertical=MobileDesign.composerVerticalInset.dp).floatingProduct(MobileDesign.composerRadius).padding(4.dp).testTag("composer"),verticalAlignment=Alignment.CenterVertically) {
        Box {
            IconAction("plus","添加附件","composer-add",interactive && session.canCompose && !session.importing && session.transferStatus==null) { addMenu=true }
            DropdownMenu(addMenu,{ addMenu=false }) {
                DropdownMenuItem(text={ Text("添加文件") },onClick={ addMenu=false; destination=session.attachmentDestination(); document.launch(arrayOf("*/*")) },modifier=Modifier.testTag("choose-files"))
                DropdownMenuItem(text={ Text(if(session.selectedCollaboration=="plan") "关闭计划模式" else "计划模式") },enabled=session.canSelectPlan,onClick={ addMenu=false; session.setPlanMode(session.selectedCollaboration!="plan") },modifier=Modifier.testTag("toggle-plan-mode"))
            }
        }
        BasicTextField(session.text,{ session.text=it },Modifier.weight(1f).heightIn(min=40.dp,max=144.dp).padding(vertical=10.dp).onFocusChanged { onFocus(it.isFocused) }.testTag("message-input"),enabled=interactive,maxLines=6,textStyle=TextStyle(color=colors.onSurface,fontSize=16.sp,lineHeight=22.sp),decorationBox={ inner -> if(session.text.isEmpty()) Text("询问 ${session.agentTitle}",color=colors.onSurfaceVariant.copy(alpha=.6f),fontSize=16.sp); inner() })
        IconAction("mic","语音输入","voice-input",interactive && session.canUseVoice && session.voice?.active==false) { session.beginVoice() }
        Surface(shape=CircleShape,color=if(decision.enabled) colors.onSurface else colors.onSurface.copy(alpha=.12f),modifier=Modifier.size(48.dp).testTag(tag).semantics { contentDescription=label; role=Role.Button; if(!decision.enabled) disabled() }.combinedClickable(enabled=decision.enabled || session.canRunAgain,onClick={ if(decision.enabled && (session.voice?.active!=true || decision.action=="stop")) session.performAction() },onLongClick={ if(decision.action=="stop" || session.canRunAgain) actions=true })) {
            Box(contentAlignment=Alignment.Center) { ProductIcon(if(decision.action=="busy") "more" else decision.action,color=if(decision.enabled) colors.background else colors.onSurface.copy(alpha=.3f)) }
        }
    }
    }
}
@Composable private fun ChatMessages(session: MobileSession,dark: Boolean,modifier: Modifier) {
    val list=rememberLazyListState(); val scope=rememberCoroutineScope()
    val timeline=session.timeline; val messages=session.messages
    var initiallyLoaded by remember(session.targetKey) { mutableStateOf(false) }
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
                        snapshotFlow { session.targetKey!=target || list.layoutInfo.totalItemsCount>=updated.rows.size+control }.first { it }
                    } ?: return@launch
                    if(session.targetKey==target) list.requestScrollToItem(anchor+control)
                }
            } finally { paging=false }
        }
    }
    val earlierAction by rememberUpdatedState(::loadEarlier)
    val density=LocalDensity.current
    LaunchedEffect(session.scrollToLatestRevision) {
        if(session.scrollToLatestRevision>0 && timeline.rows.isNotEmpty()) {
            withFrameNanos { }
            val historyControl=if(timeline.hasEarlierMessages || session.historyCursor!=null || session.historyLoading || session.revealedEarlier) 1 else 0
            list.requestScrollToItem(timeline.rows.lastIndex+historyControl)
        }
    }
    LaunchedEffect(session.targetKey,messages.size,messages.lastOrNull()?.text) {
        if(!paging && timeline.rows.isNotEmpty() && (!initiallyLoaded || !list.canScrollForward)) {
            // BoxWithConstraints may subcompose this effect during measurement.
            // Request the next layout instead of forcing a nested measure pass.
            list.requestScrollToItem(timeline.rows.lastIndex+if(timeline.hasEarlierMessages || session.historyCursor!=null || session.historyLoading || session.revealedEarlier) 1 else 0)
            initiallyLoaded=true
        }
    }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().testTag("message-list").pointerInput(session.targetKey,density) {
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
        },state=list,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=10.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(timeline.hasEarlierMessages || session.historyCursor != null || session.historyLoading) item(key="history-more") { TextButton(onClick=::loadEarlier,enabled=!session.historyLoading && !paging,modifier=Modifier.fillMaxWidth().testTag(if(timeline.hasEarlierMessages) "history-local-more" else "history-more")) { if(session.historyLoading) { CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(6.dp)) }; Text(if(session.historyLoading) "正在加载更早消息…" else "显示更早消息",fontSize=12.sp) } }
            else if(session.revealedEarlier) item { Text("已显示全部消息",Modifier.fillMaxWidth().testTag("history-start"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(timeline.rows,key={ session.targetKey+":"+it.kind+":"+it.id }) { row -> when(row.kind) {
                "replyActions" -> ReplyActions(session,row.id,row.text.orEmpty(),row.runId)
                "tools" -> ToolGroup(row.id,row.indices.map { messages[it] })
                "errors" -> Column { row.indices.forEach { Text(messages[it].text,color=MaterialTheme.colorScheme.error,fontSize=14.sp) } }
                "compaction" -> Text(ToolPresentation.compaction(messages[row.indices.first()].status, session.running),Modifier.fillMaxWidth().testTag("compaction-${row.id}"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else -> row.indices.forEach { MessageContent(messages[it],dark) }
            } }
        }
        if(!atBottom && maxHeight>=72.dp) Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp,modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp)) { IconAction("down","回到最新","scroll-latest") { scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount-1) } } }
    }
}
@Composable private fun MessageContent(message: MobileMessage,dark: Boolean) {
    val colors=MaterialTheme.colorScheme
    if(message.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        Column(Modifier.widthIn(max=330.dp).clip(RoundedCornerShape(20.dp)).background(MobileDesign.userBubble(dark)).padding(horizontal=14.dp,vertical=10.dp).testTag("message-${message.id}")) { Text(message.text,fontSize=17.sp,lineHeight=24.sp,color=MobileDesign.userBubbleText(dark)); AttachmentNames(message) }
    } else Column(Modifier.fillMaxWidth().testTag("message-${message.id}")) {
        if(message.kind=="turnFailure") Column(Modifier.testTag("task-failure-history-${message.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("任务失败记录",fontSize=12.sp,color=colors.onSurfaceVariant)
            SelectionContainer { Text(message.text,fontSize=12.sp,color=colors.onSurfaceVariant) }
        }
        else if(message.kind in listOf("plan","planImplementation")) Surface(shape=RoundedCornerShape(16.dp),color=colors.surface) { Column(Modifier.padding(12.dp)) { Text(if(message.kind=="plan") "执行计划" else "正在执行计划",fontWeight=FontWeight.SemiBold,fontSize=14.sp); MarkdownContent(message.text,"markdown-${message.id}") } }
        else MarkdownContent(message.text,"markdown-${message.id}")
        AttachmentNames(message)
    }
}
@Composable private fun AttachmentNames(message: MobileMessage) { (message.files + message.attachments.map { it.name }).distinct().forEach { Text("文件 · $it",Modifier.padding(top=6.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun ReplyActions(session: MobileSession,id: String,text: String,turnId: String?) {
    val clipboard=LocalClipboardManager.current; val context=LocalContext.current
    Row(Modifier.testTag("reply-actions-$id")) {
        IconAction("copy","复制回复","copy-reply-$id") { clipboard.setText(AnnotatedString(text)) }
        ThreadForkMenu(session,turnId)
        IconAction("share","分享回复","share-reply-$id") { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,text) },"分享回复")) }
    }
}
