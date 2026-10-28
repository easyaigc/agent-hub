package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions

/** Material3 1.4 initializes dialog bar appearance only when its window is created. */
@Composable internal fun SheetSystemBars() {
    val view=LocalView.current
    val light=MaterialTheme.colorScheme.background!=androidx.compose.ui.graphics.Color.Black
    SideEffect {
        var parent: android.view.ViewParent?=view.parent
        while(parent!=null && parent !is DialogWindowProvider) parent=parent.parent
        val window=(parent as? DialogWindowProvider)?.window
        if(window!=null) WindowInsetsControllerCompat(window,window.decorView).apply {
            isAppearanceLightStatusBars=light
            isAppearanceLightNavigationBars=light
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeSheetHost(page: String,session: MobileSession,editing: MobileClient?,close: () -> Unit,navigate: (String) -> Unit,edit: (MobileClient) -> Unit) {
    val t=currentProductText()
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    val colors=MaterialTheme.colorScheme
    val inboxBusy by rememberUpdatedState(page=="external-attachments"&&session.externalInbox?.adding==true)
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={ next -> !inboxBusy||next!=SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest={ if(!inboxBusy&&(page!="add"||!session.connectionBusy)&&(page!="add-project"||!session.projectBusy))close() },sheetState=state,containerColor=colors.surface,dragHandle={ BottomSheetDefaults.DragHandle() },contentWindowInsets={ WindowInsets.safeDrawing },
        properties=ModalBottomSheetProperties(isAppearanceLightStatusBars=colors.background!=androidx.compose.ui.graphics.Color.Black,isAppearanceLightNavigationBars=colors.background!=androidx.compose.ui.graphics.Color.Black)) {
        SheetSystemBars()
        // The sheet has its own window/focus owner. Clear it when a form leaves,
        // before that window can restore its old IME request over the main page.
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        DisposableEffect(page) { onDispose { focus.clearFocus(force=true); keyboard?.hide() } }
        LaunchedEffect(page) { focus.clearFocus(force=true); keyboard?.hide() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("sheet-$page")) {
            val title=t.tr(when(page) { "ai-sharing", "ai-sharing-settings"->"AI 数据共享"; "settings"->"设置"; "language"->"语言"; "clients"->"客户端"; "add"->if(editing==null) "添加客户端" else "编辑客户端"; "add-project"->"添加项目"; "projects"->"选择项目"; "generation"->"模型、推理与权限"; "reply-records"->"发送记录"; "chat-search"->"在聊天中查找"; "archived-chats"->"已归档对话"; "workspace-archive"->"批量归档对话"; "external-attachments"->"来自其他应用的附件"; else->"外观" })
            val back=page in listOf("appearance","language","ai-sharing-settings")
            val action=t.tr(if(page=="external-attachments") "稍后添加" else if(page=="ai-sharing" || page=="add" && editing==null) "取消" else "完成")
            val titleStyle=LocalTextStyle.current.copy(fontSize=17.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold)
            val measure=androidx.compose.ui.text.rememberTextMeasurer();val density=androidx.compose.ui.platform.LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
                val available=with(density) { maxWidth.toPx() }
                val titleWidth=measure.measure(title,titleStyle).size.width
                val label=MaterialTheme.typography.labelLarge
                val side=with(density) { maxOf(64.dp.toPx(),measure.measure(action,label).size.width+24.dp.toPx(),
                    if(back)measure.measure(t.tr("设置"),label).size.width+38.dp.toPx() else 0f) }
                val stacked=titleWidth+side*2+with(density) { 8.dp.toPx() }>available
                val controls: @Composable BoxScope.()->Unit = {
                    if(back) TextButton(onClick={ navigate("settings") },modifier=Modifier.align(Alignment.CenterStart).heightIn(min=48.dp).testTag("appearance-back")) { ProductIcon("chevron",Modifier.size(14.dp).rotate(180f)); Text(t.tr("设置")) }
                    TextButton(onClick=if(page=="add") { { navigate("clients") } } else close,enabled=!inboxBusy&&(page!="ai-sharing"||!session.aiSharing.busy)&&(page!="add"||!session.connectionBusy)&&(page!="add-project"||!session.projectBusy),modifier=Modifier.align(if(page=="ai-sharing") Alignment.CenterStart else Alignment.CenterEnd).heightIn(min=48.dp).testTag(if(page=="settings") "settings-done" else if(page=="add") "cancel-client-form" else if(page=="chat-search") "chat-search-close" else if(page=="external-attachments") "external-attachment-later" else "sheet-done")) { Text(action) }
                }
                Column {
                    Box(Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        if(!stacked) Text(title,Modifier.align(Alignment.Center).testTag("sheet-title"),style=titleStyle)
                        controls()
                    }
                    if(stacked) Text(title,Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=8.dp).testTag("sheet-title"),
                        style=titleStyle,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            HorizontalDivider(color=colors.onSurface.copy(alpha=.06f))
            when(page) {
                "ai-sharing" -> AISharingPage(session)
                "ai-sharing-settings" -> AISharingPage(session,settings=true)
                "settings" -> SettingsPage(session,navigate,uri::openUri)
                "clients" -> ClientsPage(session,navigate,edit,close)
                "add" -> key(editing?.id) { ConnectionPage(session,editing) { navigate("clients") } }
                "projects" -> ProjectsPage(session,close)
                "add-project" -> AddProjectPage(session,close)
                "appearance" -> AppearancePage(session)
                "language" -> LanguagePage(session)
                "generation" -> GenerationPage(session)
                "reply-records" -> ReplyDeliveryRecordsPage(session,close)
                "chat-search" -> ChatHistorySearchPage(session,close)
                "archived-chats" -> ArchivedChatsPage(session,close,navigate)
                "workspace-archive" -> WorkspaceArchivePage(session)
                "external-attachments" -> session.externalInbox?.let { ExternalAttachmentInboxPage(session,it) }
            }
        }
    }
}
@Composable internal fun GroupTitle(text: String) { Text(text,Modifier.padding(start=16.dp,top=20.dp,bottom=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background),content=content) }
@Composable private fun SettingsPage(session: MobileSession,navigate: (String) -> Unit,openUri: (String)->Unit) {
    val colors=MaterialTheme.colorScheme;val t=currentProductText()
    var linkFailure by remember { mutableStateOf(false) }
    fun open(url: String) { try { openUri(url) } catch(_: Exception) { linkFailure=true } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-content")) {
        GroupTitle(t.tr("连接"))
        GroupCard {
            SettingsRow(t.tr("客户端"),t.tr("%@ 个",session.clients.size),"settings-clients",icon="laptop") { navigate("clients") }
            SettingsRow(t.tr("添加客户端"),"","settings-add-client",icon="plus") { navigate("add") }
        }
        GroupTitle(t.tr("外观"))
        GroupCard { SettingsRow(t.tr("外观"),t.tr(when(session.appearance) { "light"->"浅色"; "dark"->"深色"; else->"跟随系统" }),"settings-appearance") { navigate("appearance") } }
        GroupTitle(t.tr("语言"))
        GroupCard { SettingsRow(t.tr("语言"),languageTitle(session.language,t),"language-picker") { navigate("language") } }
        Text(t.tr("跟随系统，或选择中文和英文。更改会立即生效。"),Modifier.padding(horizontal=32.dp,vertical=8.dp),fontSize=12.sp,color=colors.onSurfaceVariant)
        GroupTitle("")
        GroupCard { SettingsRow(t.tr("AI 数据共享"),"","settings-ai-sharing") { navigate("ai-sharing-settings") } }
        GroupTitle(t.tr("本地体验"))
        GroupCard { Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { Text(t.tr("界面示例"),Modifier.weight(1f)); Switch(session.demo,{ session.setDemoMode(it) },Modifier.testTag("settings-demo-toggle"),colors=SwitchDefaults.colors(checkedThumbColor=androidx.compose.ui.graphics.Color.White,checkedTrackColor=androidx.compose.ui.graphics.Color(0xFF34C759))) } }
        Text(t.tr("无需账号或连接电脑即可浏览示例、切换项目和体验对话。示例回复在手机本地生成，不会发送给电脑或 AI 服务。"),Modifier.padding(horizontal=32.dp,vertical=8.dp),fontSize=12.sp,color=colors.onSurfaceVariant)
        GroupTitle(t.tr("关于"))
        GroupCard {
            Row(Modifier.fillMaxWidth().padding(16.dp)) { Text("Agent Hub",Modifier.weight(1f)); Text("0.1.0",color=colors.onSurfaceVariant) }
            SettingsRow(t.tr("隐私政策"),"","settings-privacy-policy",icon="privacy") { open(t.privacyPolicyUrl) }
            SettingsRow(t.tr("帮助与支持"),"","settings-support",icon="help-circle") { open(t.supportUrl) }
            Text(t.tr("统一管理局域网内多台电脑上的 Agent 客户端，目前支持 Codex 和 Easy Work，后续可兼容更多客户端。\n\n一键切换客户端，随时发起任务，让 AI 自动完成并交付工作。"),Modifier.padding(horizontal=16.dp).padding(bottom=16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
    if(linkFailure) AlertDialog(onDismissRequest={ linkFailure=false },title={ Text(t.tr("无法打开链接")) },
        confirmButton={ TextButton(onClick={ linkFailure=false }) { Text(t.tr("好")) } })
}
private fun languageTitle(value: String,t: NativeL10n)=when(NativeAppLanguage.from(value)) {
    NativeAppLanguage.CHINESE->"中文";NativeAppLanguage.ENGLISH->"English";else->t.tr("跟随系统")
}
@Composable private fun LanguagePage(session: MobileSession) {
    val t=currentProductText()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GroupTitle(t.tr("语言"))
        GroupCard { NativeAppLanguage.entries.forEachIndexed { index,language ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("language-"+language.value)
                .semantics { selected=session.language==language.value }
                .clickable { session.changeLanguage(language.value) }.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(languageTitle(language.value,t),Modifier.weight(1f),fontSize=17.sp)
                if(session.language==language.value) ProductIcon("check",Modifier.size(18.dp),androidx.compose.ui.graphics.Color(0xFF007AFF))
            }
            if(index<2) HorizontalDivider(Modifier.padding(start=16.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))
        } }
        Text(t.tr("跟随系统，或选择中文和英文。更改会立即生效。"),Modifier.padding(horizontal=32.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun AppearancePage(session: MobileSession) {
    val t=currentProductText()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GroupTitle(t.tr("外观"))
        GroupCard { listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEachIndexed { index,(value,label) ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("appearance-$value").semantics { selected=session.appearance==value }.clickable { session.changeAppearance(value) }.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) { Text(t.tr(label),Modifier.weight(1f),fontSize=17.sp); if(session.appearance==value) ProductIcon("check",Modifier.size(18.dp),androidx.compose.ui.graphics.Color(0xFF007AFF)) }
            if(index<2) HorizontalDivider(Modifier.padding(start=16.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))
        } }
    }
}
@Composable internal fun SettingsRow(label: String,detail: String,tag: String,icon: String?=null,click: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    val labelColor=if(icon==null) colors.onSurface else androidx.compose.ui.graphics.Color(0xFF007AFF)
    val measure=androidx.compose.ui.text.rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val labelStyle=LocalTextStyle.current.copy(fontSize=16.sp)
    val detailStyle=LocalTextStyle.current.copy(fontSize=14.sp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room=with(density) { (maxWidth-32.dp-22.dp-if(icon==null)0.dp else 30.dp).toPx().coerceAtLeast(0f) }
        val needed=measure.measure(label,labelStyle,softWrap=false,maxLines=1).size.width+
            measure.measure(detail,detailStyle,softWrap=false,maxLines=1).size.width+with(density) { 8.dp.toPx() }
        val stacked=detail.isNotEmpty()&&needed>room
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).clickable(onClick=click).padding(horizontal=16.dp,vertical=if(stacked)10.dp else 0.dp),verticalAlignment=Alignment.CenterVertically) {
            if(icon!=null) { ProductIcon(icon,Modifier.testTag("$tag-icon"),color=labelColor); Spacer(Modifier.width(10.dp)) }
            if(stacked) Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(label,Modifier.fillMaxWidth().testTag("$tag-label"),style=labelStyle,color=labelColor)
                Text(detail,Modifier.fillMaxWidth().testTag("$tag-detail"),style=detailStyle,color=colors.onSurfaceVariant)
            } else {
                Text(label,Modifier.weight(1f).testTag("$tag-label"),style=labelStyle,color=labelColor)
                Spacer(Modifier.width(8.dp))
                Text(detail,Modifier.testTag("$tag-detail"),style=detailStyle,color=colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            ProductIcon("chevron",Modifier.size(14.dp),colors.onSurfaceVariant)
        }
    }
}
@Composable private fun ClientsPage(session: MobileSession,navigate: (String) -> Unit,edit: (MobileClient) -> Unit,close: () -> Unit) {
    var removing by remember { mutableStateOf<MobileClient?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=8.dp)) {
        GroupCard {
            session.clients.forEach { client ->
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min=48.dp).testTag("client-${client.id}").clickable { session.select(client); close() },verticalAlignment=Alignment.CenterVertically) { ProductIcon("laptop"); Column(Modifier.weight(1f).padding(horizontal=12.dp)) { Text(client.name,fontSize=16.sp); Text(clientSourceText(client),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); val running=session.runningConversationCount(client)
                        val unread=session.unreadConversationCount(client)
                        val runningDescription=clientRunningDescription(client,running)
                        Text(clientRunningText(client,running),Modifier.testTag("client-running-${client.id}").semantics {
                            contentDescription=runningDescription
                        },fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,style=LocalTextStyle.current.copy(fontFeatureSettings="tnum"))
                        Text(productText("未读 %@",unread?.toString() ?: "—"),Modifier.testTag("client-unread-${client.id}"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,style=LocalTextStyle.current.copy(fontFeatureSettings="tnum")) }; if(client.isConnecting) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp); if(session.hasClient && session.selectedClient.id==client.id) ProductIcon("check",Modifier.size(16.dp)) }
                    if(!client.isDemo) Column { TextButton(onClick={ edit(client) },modifier=Modifier.testTag("edit-client-${client.id}")) { Text(productText("编辑")) }; TextButton(onClick={ removing=client },modifier=Modifier.testTag("remove-client-${client.id}")) { Text(productText("移除"),color=MaterialTheme.colorScheme.error) } }
                }
            }
            SettingsRow(productText("添加客户端"),"","add-client",icon="plus") { navigate("add") }
        }
    }
    removing?.let { client -> AlertDialog(onDismissRequest={ removing=null },title={ Text(productText("移除连接")) },text={ Text(productText("移除 %@ 的连接？电脑上的项目和对话会保留。",client.name)) },confirmButton={ TextButton(onClick={ session.removeClient(client.id); removing=null },modifier=Modifier.testTag("confirm-remove-client")) { Text(productText("移除连接"),color=MaterialTheme.colorScheme.error) } },dismissButton={ TextButton(onClick={ removing=null }) { Text(productText("取消")) } }) }
}
/** Both provider discovery pages share the iOS desktop/name/detail/checkmark layout. */
@Composable internal fun DiscoveredComputerRow(name: String, detail: String, tag: String, connected: Boolean, enabled: Boolean, connect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).clickable(enabled=enabled,onClick=connect).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        ProductIcon("desktop",Modifier.size(22.dp).testTag("$tag-icon"))
        Column(Modifier.weight(1f).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(name,fontSize=17.sp)
            Text(detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(connected) ProductIcon("check",Modifier.size(18.dp).testTag("$tag-connected"),androidx.compose.ui.graphics.Color(0xFF34C759))
    }
}
@Composable internal fun CodexConnectionPage(session: MobileSession,editing: MobileClient?,saved: () -> Unit) {
    val initialName=productText("本机 Mac")
    var name by remember { mutableStateOf(editing?.name ?: initialName) }
    var address by remember { mutableStateOf(editing?.endpoint ?: "http://127.0.0.1:8788") }
    var token by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf("自动发现") }
    var methodMenu by remember { mutableStateOf(false) }
    var authorizationHost by remember { mutableStateOf<DiscoveredCodexHost?>(null) }
    var hostKey by remember { mutableStateOf("") }
    var failedKnownHost by remember { mutableStateOf<DiscoveredCodexHost?>(null) }
    val tasks=rememberCoroutineScope(); val colors=MaterialTheme.colorScheme
    val focus=LocalFocusManager.current; val keyboard=LocalSoftwareKeyboardController.current
    val discovery=session.discovery
    val localPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { discovery?.start() }
    LaunchedEffect(editing?.id) { if(editing != null) token=session.credential(editing.id) }
    fun connectQR(value: String) { connecting=true; session.connectionBusy=true;error=null; tasks.launch { try { session.connectCodexQR(value,name); saved() } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(failure: Exception) { error=failure.message ?: "连接失败" } finally { connecting=false;session.connectionBusy=false } } }
    fun connectDiscovered(host: DiscoveredCodexHost) {
        focus.clearFocus();keyboard?.hide()
        connecting=true;session.connectionBusy=true;error=null;failedKnownHost=null
        tasks.launch { try {
            if(host.known) session.connectKnownCodex(host) else session.connectCodexDiscovered(host,hostKey)
            hostKey="";authorizationHost=null;saved()
        } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch(failure: CodexHostKeyRequired) { authorizationHost=host;hostKey="";error=null }
        catch(failure: Exception) { error=failure.message ?: "连接失败，原连接已保留";if(host.known)failedKnownHost=host }
        finally { connecting=false;session.connectionBusy=false } }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(productText("默认使用局域网与 Tailscale HTTPS 双通道，自动切换并保留同一台电脑的连接和草稿。只在局域网使用可不安装 Tailscale；需要在外远程控制，请为手机和电脑配置 Tailscale 并保持在线。"),Modifier.padding(horizontal=32.dp,vertical=12.dp).testTag("codex-dual-channel-help"),fontSize=14.sp,color=colors.onSurfaceVariant)
        if(editing==null) {
            GroupCard { Box {
                SettingsRow(productText("连接方式"),productText(method),"codex-connection-method") { if(!connecting) methodMenu=true }
                DropdownMenu(methodMenu,{ methodMenu=false }) { listOf("自动发现","扫码连接","手动连接").forEach { choice ->
                    DropdownMenuItem(text={ Text(productText(choice)) },onClick={ method=choice; methodMenu=false; error=null; authorizationHost=null;hostKey="";focus.clearFocus(); keyboard?.hide() },modifier=Modifier.testTag("codex-method-$choice"))
                } }
            } }
        }
        if(editing==null && method=="自动发现") {
            authorizationHost?.let { host ->
                GroupTitle(productText("首次连接授权"))
                GroupCard {
                    Text(host.name,Modifier.padding(16.dp))
                    NativeFormField(hostKey,{ hostKey=it },productText("Host Key"),"codex-host-key",!connecting,KeyboardType.Password)
                    TextButton(onClick={ connectDiscovered(host) },enabled=!connecting&&hostKey.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("codex-authorize-connect")) { Text(productText("授权并连接")) }
                    TextButton(onClick={ authorizationHost=null;hostKey="";error=null },enabled=!connecting,modifier=Modifier.fillMaxWidth().testTag("codex-authorize-cancel")) { Text(productText("取消")) }
                }
                Text(productText("请从这台电脑的私有密钥文件读取 Host Key。授权后将安全保存设备凭据，后续自动重连。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
            }
            GroupTitle(productText("自动发现"))
            GroupCard {
                if(discovery?.needsPermission==true) TextButton(onClick={ localPermission.launch(CodexHostDiscovery.LOCAL_PERMISSION) },modifier=Modifier.fillMaxWidth().testTag("allow-local-network")) { Text(productText("允许发现局域网与 VPN 电脑")) }
                val known=session.clients.filter { !it.isDemo&&it.scope?.agentId=="codex" }
                val rows=known.map { client -> val found=discovery?.hosts?.firstOrNull { it.id==client.id }; DiscoveredCodexHost(client.id,client.name,found?.endpoint ?: client.endpoint.orEmpty(),true,found?.reachable==true,found?.networkKind,found?.target,found?.fingerprint) } + discovery?.hosts.orEmpty().filter { found -> known.none { it.id==found.id } }
                rows.forEach { host ->
                    val client=known.firstOrNull { it.id==host.id }
                    val network=if(host.networkKind=="vpn") "VPN · " else ""
                    val detail=if(client?.online==true) network+productText("已连接") else if(client!=null && !host.reachable) productText("未连接 · %@",clientConnectionText(client)) else network+productText("未连接 · 点选连接")
                    DiscoveredComputerRow(host.name,detail,"codex-discovered-${host.id}",client?.online==true,!connecting) {
                        authorizationHost=null;hostKey="";connectDiscovered(host)
                    }
                }
                if(rows.isEmpty()) Text(productText(discovery?.detail ?: "正在查找同一网络的电脑…"),Modifier.padding(16.dp),fontSize=15.sp,color=colors.onSurfaceVariant)
                TextButton(onClick={ discovery?.scan() },enabled=!connecting,modifier=Modifier.fillMaxWidth().testTag("codex-discovery-rescan")) { Text(productText("重新查找")) }
            }
            Text(productText("自动查找同一网络的电脑；需要密钥的电脑会在首次连接时要求输入 Host Key。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
            if(connecting) Row(Modifier.padding(16.dp).testTag("codex-discovery-connecting"),verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp));Text(productText("连接中…"),Modifier.padding(start=8.dp)) }
            error?.let { Text(it,Modifier.padding(16.dp).testTag("client-form-error"),color=colors.error,fontSize=14.sp) }
            failedKnownHost?.let { host -> TextButton(onClick={ authorizationHost=host.copy(known=false);hostKey="";failedKnownHost=null;error=null },enabled=!connecting,modifier=Modifier.testTag("codex-reauthorize")) { Text(productText("使用 Host Key 重新授权")) } }

        }
        if(editing==null && method=="扫码连接") {
            GroupTitle(productText("扫码连接"))
            GroupCard {
                TextButton(onClick={ scanning=true },enabled=!connecting,modifier=Modifier.fillMaxWidth().testTag("codex-scan-qr")) { Text(productText("扫描电脑二维码"),Modifier.weight(1f)) }
                NativeFormField(qr,{ qr=it },productText("或粘贴连接二维码内容"),"codex-qr-description",!connecting)
                error?.let { Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp).testTag("client-form-error"),color=colors.error,fontSize=14.sp) }
                TextButton(onClick={ connectQR(qr) },enabled=qr.isNotEmpty() && !connecting,modifier=Modifier.fillMaxWidth().testTag("codex-connect-qr")) { Text(productText("使用二维码连接"),Modifier.weight(1f)) }
                Text(productText("可扫描包含密钥的连接信息，或自动发现后输入 Host Key 授权。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
            }
        }
        if(editing!=null || method=="手动连接") {
        GroupTitle(productText("客户端"))
        GroupCard {
            NativeFormField(name,{ name=it },productText("客户端名称"),"client-name",!connecting)
            NativeFormField(address,{ address=it },productText("连接地址"),"client-address",!connecting,KeyboardType.Uri)
            NativeFormField(token,{ token=it },productText("客户端密钥"),"client-token",!connecting,KeyboardType.Password)
        }
        Text(productText("填写电脑上 Host Agent 的 HTTPS 地址和客户端密钥。密钥由系统 Keystore 保护。本机模拟器可使用回环地址；历史只读连接无需密钥。"),Modifier.padding(horizontal=32.dp,vertical=12.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
        error?.let { Text(it,Modifier.padding(horizontal=32.dp).testTag("client-form-error"),color=colors.error,fontSize=14.sp) }
        GroupCard { TextButton(onClick={ connecting=true;session.connectionBusy=true; error=null; tasks.launch { try { session.connect(name,address,token,editing); saved() } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(failure: Exception) { error=failure.message ?: "连接失败" } finally { connecting=false;session.connectionBusy=false } } },enabled=!connecting,modifier=Modifier.fillMaxWidth().testTag(if(editing==null) "connect-client" else "save-client")) { Text(productText(if(connecting) "正在连接…" else if(editing==null) "连接客户端" else "保存修改"),Modifier.weight(1f)) } }
        }
        Spacer(Modifier.height(24.dp))
    }
    if(scanning) NativeQRScanner("codex-host://pair?",{ scanning=false }) { value -> scanning=false; qr=value; connectQR(value) }
}
@Composable internal fun NativeFormField(value: String,change: (String) -> Unit,placeholder: String,tag: String,enabled: Boolean,type: KeyboardType=KeyboardType.Text) {
    val colors=MaterialTheme.colorScheme
    BasicTextField(value,change,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).padding(horizontal=16.dp,vertical=12.dp),enabled=enabled,singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(fontSize=17.sp,color=colors.onSurface),keyboardOptions=KeyboardOptions(keyboardType=type,autoCorrectEnabled=false,imeAction=ImeAction.Done),visualTransformation=if(type==KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox={ inner -> Box { if(value.isEmpty()) Text(placeholder,fontSize=17.sp,color=colors.onSurfaceVariant); inner() } })
    HorizontalDivider(Modifier.padding(start=16.dp),color=colors.onSurface.copy(alpha=.1f))
}
@Composable private fun ProjectsPage(session: MobileSession,close: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=8.dp)) {
        GroupCard {
            TextButton(onClick={ session.newChat(); close() },enabled=session.canCreateProjectless,modifier=Modifier.fillMaxWidth().testTag("projectless-new")) { Text(productText("不关联项目"),Modifier.weight(1f)) }
            session.projects.forEach { project -> SettingsRow(project.name,"","choose-project-${project.id}") { if(session.canCreateThread) { session.newChat(project.id); close() } } }
        }
        if(!session.selectedClient.isDemo && !session.canCreateThread) Text(productText("从侧栏选择已有项目和对话。历史只读连接无法创建新对话。"),Modifier.padding(32.dp),fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun AddProjectPage(session: MobileSession,close: ()->Unit) {
    var name by remember { mutableStateOf("") };var directory by remember { mutableStateOf(".") }
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    val tasks=rememberCoroutineScope();val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GroupTitle("项目名称")
        GroupCard { NativeFormField(name,{ name=it },"项目名称","project-name",!busy) }
        GroupTitle("电脑上的目录")
        GroupCard { NativeFormField(directory,{ directory=it },"相对于授权工作目录的路径","project-directory",!busy) }
        Text("选择电脑授权工作目录中的已有文件夹。",Modifier.padding(24.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
        GroupCard { TextButton(onClick={ busy=true;session.projectBusy=true;error=null;tasks.launch { try { if(session.createProject(name,directory))close() } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(failure: Exception) { error=failure.message } finally { busy=false;session.projectBusy=false } } },enabled=!busy&&name.isNotBlank()&&session.canCreateProject,modifier=Modifier.fillMaxWidth().testTag("confirm-project")) { Text(if(busy)"正在添加…" else "添加项目") } }
        error?.let { Text(it,Modifier.padding(24.dp).testTag("project-error"),color=colors.error) }
    }
}
