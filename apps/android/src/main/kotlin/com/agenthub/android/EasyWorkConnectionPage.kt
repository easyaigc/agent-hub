package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

@Composable internal fun ConnectionPage(session: MobileSession,editing: MobileClient?,saved: ()->Unit) {
    var provider by remember { mutableStateOf(editing?.scope?.agentId?:if(session.incomingEasyWorkLink!=null)"easywork" else "codex") }
    LaunchedEffect(session.incomingEasyWorkLink) { if(session.incomingEasyWorkLink!=null)provider="easywork" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if(editing==null) {
            val colors=MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=16.dp).clip(RoundedCornerShape(10.dp)).background(colors.onSurface.copy(alpha=.08f)).padding(3.dp).testTag("agent-provider").semantics { contentDescription="Agent 客户端" }) {
                listOf("codex" to "Codex","easywork" to "EasyWork").forEach { (id,title)->
                    Box(Modifier.weight(1f).heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(if(provider==id)colors.background else androidx.compose.ui.graphics.Color.Transparent).selectable(provider==id,enabled=!session.connectionBusy,role=Role.Tab,onClick={ provider=id }).testTag("agent-provider-$id"),contentAlignment=Alignment.Center) { Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium) }
                }
            }
        }
        key(provider) { when(provider) {
            "easywork"->EasyWorkConnectionPage(session,editing,saved)
            "codex"->CodexConnectionPage(session,editing,saved)
            else->Text("此 Agent 的配置界面尚未注册",Modifier.padding(16.dp))
        } }
    }
}
@Composable private fun EasyWorkConnectionPage(session: MobileSession,editing: MobileClient?,saved: ()->Unit) {
    var name by remember { mutableStateOf(editing?.name?:"EasyWork") }
    var method by remember { mutableStateOf(if(session.incomingEasyWorkLink!=null)"扫码连接" else "自动发现") }
    var qr by remember { mutableStateOf(session.incomingEasyWorkLink?.payload.orEmpty()) }
    var address by remember { mutableStateOf("") }
    var relay by remember { mutableStateOf(editing?.agentConnection?.candidates?.firstOrNull { it.transportId=="relay-http" }?.endpoint?:"https://51easyai.com") }
    var code by remember { mutableStateOf("") };var pairingStatus by remember { mutableStateOf<String?>(null) }
    var methodMenu by remember { mutableStateOf(false) };var preferenceMenu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    val tasks=rememberCoroutineScope();val colors=MaterialTheme.colorScheme;val context=LocalContext.current
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val discovery=session.easyWorkDiscovery
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { discovery?.start() }
    LaunchedEffect(session.incomingEasyWorkLink) { session.incomingEasyWorkLink?.let { qr=it.payload;method="扫码连接" } }
    fun perform(work: suspend ()->Unit) { if(busy)return;busy=true;session.connectionBusy=true;error=null;pairingStatus=null;focus.clearFocus();keyboard?.hide();tasks.launch { try { work() } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(failure: Exception) { error=failure.message?:"连接失败" } finally { busy=false;session.connectionBusy=false } } }
    fun pair(value: String) { perform { session.connectEasyWorkQR(value,name) { pairingStatus=it };saved() } }
    Column(Modifier.fillMaxWidth()) {
        GroupTitle("EasyWork")
        GroupCard {
            NativeFormField(name,{ name=it;editing?.let { client->session.renameEasyWork(client,it) } },"名称","easywork-name",!busy)
            if(editing!=null)Box {
                val preference=session.clients.firstOrNull { it.id==editing.id }?.agentConnection?.preference?:"automatic"
                SettingsRow("连接偏好",mapOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继")[preference]!!,"connection-preference") { if(!busy)preferenceMenu=true }
                DropdownMenu(preferenceMenu,{ preferenceMenu=false }) { listOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继").forEach { (id,title)->DropdownMenuItem(text={ Text(title) },onClick={ session.setEasyWorkPreference(editing,id);preferenceMenu=false },modifier=Modifier.testTag("connection-preference-$id")) } }
            }
            Box {
                SettingsRow("连接方式",method,"easywork-connection-method") { if(!busy)methodMenu=true }
                DropdownMenu(methodMenu,{ methodMenu=false }) { listOf("自动发现","扫码连接","手动连接","通过中继连接").forEach { choice->DropdownMenuItem(text={ Text(choice) },onClick={ method=choice;methodMenu=false;error=null;pairingStatus=null;focus.clearFocus();keyboard?.hide() },modifier=Modifier.testTag("easywork-method-$choice")) } }
            }
            if(busy&&pairingStatus!=null)Text(pairingStatus!!,Modifier.padding(16.dp).testTag("easywork-connecting"),fontSize=14.sp,color=colors.onSurfaceVariant)
            error?.let { Text(it,Modifier.padding(16.dp).testTag("easywork-pair-error"),color=colors.error) }
        }
        GroupTitle(method)
        GroupCard {
            when(method) {
                "自动发现"-> {
                    if(discovery?.needsPermission==true)TextButton(onClick={ permission.launch(CodexHostDiscovery.LOCAL_PERMISSION) },modifier=Modifier.fillMaxWidth().testTag("easywork-allow-local-network")) { Text("允许发现局域网与 VPN 电脑") }
                    val known=session.clients.filter { it.agentConnection?.target?.agentId=="easywork" }
                    val rows=known.map { client->discovery?.hosts?.firstOrNull { it.id==client.id }?:DiscoveredEasyWorkHost(client.id,client.name,client.endpoint.orEmpty(),client.scope?.hostId,true) }+discovery?.hosts.orEmpty().filter { !it.known }
                    if(rows.isEmpty())Text("正在查找同一网络的电脑…",Modifier.padding(16.dp),fontSize=15.sp,color=colors.onSurfaceVariant)
                    rows.forEach { host->
                        val client=known.firstOrNull { it.id==host.id }
                        val detail=(if(host.networkKind=="vpn") "VPN · " else "") + if(client==null) "未连接 · 点选连接" else if(client.online) "已连接" else "未连接 · ${client.connectionLabel}"
                        DiscoveredComputerRow(host.name,detail,"easywork-discovered-${host.id}",client?.online==true,!busy) {
                            if(host.known)perform { session.connectKnownEasyWork(host.endpoint);saved() } else perform { session.connectDiscoveredEasyWork(host);saved() }
                        }
                    }
                    TextButton(onClick={ discovery?.scan() },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-discovery-rescan")) { Text("重新查找") }
                }
                "扫码连接"-> {
                    TextButton(onClick={ scanning=true },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-scan")) { Text("扫描电脑二维码") }
                    NativeFormField(qr,{ qr=it },"或粘贴连接二维码内容","easywork-qr-description",!busy)
                    TextButton(onClick={ pair(qr) },enabled=qr.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-pair-lan")) { Text("连接电脑") }
                    Text("首次连接扫描电脑二维码，之后自动发现局域网与 VPN 入口。",Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
                "手动连接"-> {
                    NativeFormField(address,{ address=it },"电脑地址","easywork-manual-address",!busy,KeyboardType.Uri)
                    TextButton(onClick={ perform { session.connectKnownEasyWork(address);saved() } },enabled=address.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-manual-connect")) { Text("连接已保存的电脑") }
                    Text("填写已配对电脑的地址；首次接入请使用电脑提供的连接二维码。",Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
                else-> {
                    NativeFormField(relay,{ relay=it },"中继服务地址","easywork-relay-url",!busy,KeyboardType.Uri)
                    NativeFormField(code,{ code=it },"电脑连接码","easywork-relay-code",!busy,KeyboardType.Ascii)
                    TextButton(onClick={ perform { session.connectEasyWorkCode(name,relay,code) { pairingStatus=it };saved() } },enabled=code.isNotBlank()&&relay.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-pair-relay")) { Text("连接电脑") }
                    Text("输入电脑显示的连接码，再在已登录的电脑上确认此设备。手机无需登录账号。",Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
            }
        }
        if(busy&&pairingStatus==null)Text("正在连接…",Modifier.padding(16.dp).testTag("easywork-connecting"),color=colors.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if(scanning)NativeQRScanner("easywork-host://pair?",{ scanning=false },additionalPrefixes=listOf("easywork://pair?")) { value->scanning=false;qr=value;pair(value) }
}
