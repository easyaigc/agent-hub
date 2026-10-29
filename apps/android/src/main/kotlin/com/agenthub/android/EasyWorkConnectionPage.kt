package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun ConnectionPage(session: MobileSession,editing: MobileClient?,draftOverride: EasyWorkConnectionDraft?=null,saved: ()->Unit) {
    var provider by remember { mutableStateOf(editing?.scope?.agentId?:if(session.incomingEasyWorkLink!=null)"easywork" else "codex") }
    val rememberedDraft=remember(editing?.id) { EasyWorkConnectionDraft(editing?.name?:"EasyWork",editing?.agentConnection?.candidates?.firstOrNull { it.transportId=="relay-http" }?.endpoint?:"https://51easyai.com").apply {if(editing?.migrationEasyWork!=null)method="扫码连接"} }
    val easyWorkDraft=draftOverride?:rememberedDraft
    LaunchedEffect(session.incomingEasyWorkLink,session.connectionBusy) { if(session.incomingEasyWorkLink!=null&&!session.connectionBusy)provider="easywork" }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).testTag("connection-page")) {
        if(editing==null) {
            val colors=MaterialTheme.colorScheme
            val providerLabel=productText("Agent 客户端")
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=16.dp).clip(RoundedCornerShape(10.dp)).background(colors.onSurface.copy(alpha=.08f)).padding(3.dp).testTag("agent-provider").selectableGroup().semantics { contentDescription=providerLabel }) {
                listOf("codex" to "Codex","easywork" to "EasyWork").forEach { (id,title)->
                    Box(Modifier.weight(1f).heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(if(provider==id)colors.background else androidx.compose.ui.graphics.Color.Transparent).selectable(provider==id,enabled=!session.connectionBusy,role=Role.Tab,onClick={ if(!session.connectionBusy)provider=id }).testTag("agent-provider-$id"),contentAlignment=Alignment.Center) { Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium) }
                }
            }
        }
        key(provider) { when(provider) {
            "easywork"->EasyWorkConnectionPage(session,editing,easyWorkDraft,saved)
            "codex"->CodexConnectionPage(session,editing,saved)
            else->Text(productText("此 Agent 的配置界面尚未注册"),Modifier.padding(16.dp))
        } }
    }
}
@Composable private fun EasyWorkConnectionPage(session: MobileSession,editing: MobileClient?,draft: EasyWorkConnectionDraft,saved: ()->Unit) {
    var name by draft::name
    var method by draft::method
    var qr by draft::qr
    var address by draft::address
    var relay by draft::relay
    var code by draft::code
    var pairingStatus by remember { mutableStateOf<String?>(null) }
    var methodMenu by remember { mutableStateOf(false) };var preferenceMenu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<EasyWorkConnectionMessage?>(null) }
    val tasks=rememberCoroutineScope();val colors=MaterialTheme.colorScheme;val context=LocalContext.current
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val discovery=session.easyWorkDiscovery
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { discovery?.start() }
    LaunchedEffect(session.incomingEasyWorkLink,busy) { if(!busy)session.incomingEasyWorkLink?.let { qr=it.payload;method="扫码连接" } }
    val lifecycle=LocalLifecycleOwner.current
    val owner=remember(session,draft) {easyWorkConnectionForm(session)}
    SideEffect {draft.formOwner=owner}
    DisposableEffect(owner,lifecycle) {
        fun update(active: Boolean) {if(owner.lifecycle(active)){busy=false;session.connectionBusy=false}}
        update(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)update(true)else if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)update(false)}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);if(owner.close())session.connectionBusy=false;if(draft.formOwner===owner)draft.formOwner=null}
    }
    fun perform(input: EasyWorkConnectionInput,currentInput: ()->EasyWorkConnectionInput={input},work: suspend ((String)->Unit)->Unit) {
        if(busy||session.connectionBusy)return
        val action=try {owner.begin(input,currentInput)}catch(_: EasyWorkConnectionAbandoned){return}
        busy=true;session.connectionBusy=true;error=null;pairingStatus=null;focus.clearFocus();keyboard?.hide()
        tasks.launch {
            try { session.runEasyWorkConnectionFormAction(action) {work {if(action.currentForUi())pairingStatus=it};if(action.currentForUi())saved()} }
            catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch(failure: Exception) { if(action.currentForUi())error=EasyWorkConnectionMessage.failure(failure) }
            finally { if(action.releaseBusy()){busy=false;session.connectionBusy=false;pairingStatus=null} }
        }
    }
    fun qrInput()=EasyWorkConnectionInput("qr",name,"",qr)
    fun codeInput()=EasyWorkConnectionInput("code",name,relay,code)
    fun pair(value: String) { val input=EasyWorkConnectionInput("qr",name,"",value);perform(input,::qrInput) {pending->session.connectEasyWorkQR(input.value,input.name,pending)} }
    Column(Modifier.fillMaxWidth()) {
        if(editing?.migrationEasyWork!=null)Text(productText(NativeMigrationEasyWorkGate.MESSAGE),Modifier.padding(16.dp).testTag("easywork-migration-pairing-required"),color=colors.onSurfaceVariant)
        GroupTitle("EasyWork")
        GroupCard {
            EasyWorkFormField(name,{ name=it;editing?.let { client->session.renameEasyWork(client,it) } },productText("名称"),"easywork-name",!busy)
            if(editing!=null)Box {
                val preference=session.clients.firstOrNull { it.id==editing.id }?.let {it.agentConnection?.preference?:it.migrationEasyWork?.preference}?:"automatic"
                SettingsRow(productText("连接偏好"),productText(mapOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继")[preference]!!),"connection-preference",enabled=!busy) { if(!busy)preferenceMenu=true }
                DropdownMenu(preferenceMenu,{ preferenceMenu=false }) { listOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继").forEach { (id,title)->DropdownMenuItem(text={ Text(productText(title)) },onClick={ if(!busy){session.setEasyWorkPreference(editing,id);preferenceMenu=false} },enabled=!busy,trailingIcon={ if(preference==id)ProductIcon("check",Modifier.size(18.dp)) },modifier=Modifier.testTag("connection-preference-$id").semantics { selected=preference==id }) } }
            }
            Box {
                SettingsRow(productText("连接方式"),productText(method),"easywork-connection-method",enabled=!busy) { if(!busy)methodMenu=true }
                DropdownMenu(methodMenu,{ methodMenu=false }) { listOf("自动发现","扫码连接","手动连接","通过中继连接").forEach { choice->DropdownMenuItem(text={ Text(productText(choice)) },onClick={ if(!busy){method=choice;methodMenu=false;error=null;pairingStatus=null;focus.clearFocus();keyboard?.hide()} },enabled=!busy,trailingIcon={ if(method==choice)ProductIcon("check",Modifier.size(18.dp)) },modifier=Modifier.testTag("easywork-method-$choice").semantics { selected=method==choice }) } }
            }
            if(busy&&pairingStatus!=null)EasyWorkConnectionStatus(pairingStatus!!)
            error?.let { EasyWorkConnectionFailure(it) }
        }
        (editing?.agentConnection?.target?:editing?.migrationEasyWork?.target)?.let { target->
            GroupTitle(productText("连接目标"))
            GroupCard {
                val fields=listOf("agent" to (productText("Agent 客户端") to target.agentId),"authority" to (productText("账号发行方") to target.accountAuthority.orEmpty()),"subject" to (productText("账号") to target.accountSubject.orEmpty()),"host" to (productText("电脑") to target.hostId),"workspace" to (productText("工作区") to target.workspaceId),"runtime" to (productText("运行时") to target.runtimeId))
                fields.forEach { (id,row)->Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) { Text(row.first,Modifier.testTag("easywork-target-label-$id"),fontSize=12.sp,color=colors.onSurfaceVariant);Text(row.second,Modifier.fillMaxWidth().testTag("easywork-target-$id"),fontSize=14.sp) } }
            }
        }
        GroupTitle(productText(method))
        GroupCard {
            when(method) {
                "自动发现"-> {
                    if(discovery?.needsPermission==true)TextButton(onClick={ permission.launch(CodexHostDiscovery.LOCAL_PERMISSION) },modifier=Modifier.fillMaxWidth().testTag("easywork-allow-local-network")) { Text(productText("允许发现局域网与 VPN 电脑")) }
                    val known=session.clients.filter { !it.isDemo&&(it.agentConnection?.target?.agentId=="easywork"||it.migrationEasyWork?.target?.agentId=="easywork") }
                    // Discovery contributes routes; the current saved row owns its display name.
                    val rows=known.map { client->discovery?.hosts?.firstOrNull { it.id==client.id }?.copy(name=client.name)?:DiscoveredEasyWorkHost(client.id,client.name,client.endpoint.orEmpty(),client.scope?.hostId,true) }+discovery?.hosts.orEmpty().filter { !it.known }
                    if(rows.isEmpty())Text(productText("正在查找同一网络的电脑…"),Modifier.padding(16.dp),fontSize=15.sp,color=colors.onSurfaceVariant)
                    rows.forEach { host->
                        val client=known.firstOrNull { it.id==host.id }
                        val detail=(if(host.networkKind=="vpn") productText("VPN · ") else "") + if(client==null) productText("未连接 · 点选连接") else if(client.online) productText("已连接") else productText("未连接 · %@",clientConnectionText(client))
                        DiscoveredComputerRow(host.name,detail,"easywork-discovered-${host.id}",client?.online==true,!busy) {
                            if(client?.migrationEasyWork!=null) {
                                // Imported rows open explicit pairing UI; no old route, credential or action is used.
                                if(!busy&&!session.connectionBusy){name=client.name;method="扫码连接";error=null;pairingStatus=null;focus.clearFocus();keyboard?.hide()}
                            } else if(host.known)perform(EasyWorkConnectionInput("known","",host.endpoint,"")) { session.connectKnownEasyWork(host.endpoint) } else perform(EasyWorkConnectionInput("discovered",host.name,host.endpoint,host.deviceId.orEmpty())) { session.connectDiscoveredEasyWork(host) }
                        }
                    }
                    TextButton(onClick={ discovery?.scan() },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-discovery-rescan")) { Text(productText("重新查找")) }
                }
                "扫码连接"-> {
                    TextButton(onClick={ scanning=true },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-scan")) { Text(productText("扫描电脑二维码")) }
                    EasyWorkFormField(qr,{ qr=it },productText("或粘贴连接二维码内容"),"easywork-qr-description",!busy)
                    TextButton(onClick={ pair(qr) },enabled=qr.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-pair-lan")) { Text(productText("连接电脑")) }
                    Text(productText("首次连接扫描电脑二维码，之后自动发现局域网与 VPN 入口。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
                "手动连接"-> {
                    EasyWorkFormField(address,{ address=it },productText("电脑地址"),"easywork-manual-address",!busy,KeyboardType.Uri)
                    TextButton(onClick={ perform(EasyWorkConnectionInput("known","",address,"")) { session.connectKnownEasyWork(address) } },enabled=address.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-manual-connect")) { Text(productText("连接已保存的电脑")) }
                    Text(productText("填写已配对电脑的地址；首次接入请使用电脑提供的连接二维码。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
                else-> {
                    EasyWorkFormField(relay,{ relay=it },productText("中继服务地址"),"easywork-relay-url",!busy,KeyboardType.Uri)
                    EasyWorkFormField(code,{ code=it },productText("电脑连接码"),"easywork-relay-code",!busy,KeyboardType.Ascii)
                    TextButton(onClick={ run { val input=codeInput();perform(input,::codeInput) {pending->session.connectEasyWorkCode(input.name,input.endpoint,input.value,pending)} } },enabled=code.isNotBlank()&&relay.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().testTag("easywork-pair-relay")) { Text(productText("连接电脑")) }
                    Text(productText("输入电脑显示的连接码，再在已登录的电脑上确认此设备。手机无需登录账号。"),Modifier.padding(16.dp),fontSize=14.sp,color=colors.onSurfaceVariant)
                }
            }
        }
        if(busy&&pairingStatus==null)Text(productText("正在连接…"),Modifier.padding(16.dp).testTag("easywork-connecting"),color=colors.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if(scanning)NativeQRScanner("easywork-host://pair?",{ scanning=false },additionalPrefixes=listOf("easywork://pair?")) { value->scanning=false;qr=value;pair(value) }
}

/** Native app-owned copy; verification code is an argument, never a new lookup key. */
@Composable internal fun EasyWorkConnectionStatus(peerStatus: String) {
    val code=EasyWorkConnectionMessage.verificationCode(peerStatus)
    val message=if(code==null)productText(EasyWorkConnectionMessage.PENDING.key)else productText(EasyWorkConnectionMessage.VERIFY.key,code)
    Text(message,Modifier.padding(16.dp).testTag("easywork-connecting").semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun EasyWorkConnectionFailure(message: EasyWorkConnectionMessage) {
    Text(productText(message.key),Modifier.padding(16.dp).testTag("easywork-pair-error").semantics {liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.error)
}

/** Scoped to pairing: keep the field's native label in AX after its value is filled.
 * Single-line addresses retain native horizontal editing; Done releases the real IME.
 */
@Composable internal fun EasyWorkFormField(value: String,change: (String)->Unit,label: String,tag: String,enabled: Boolean,type: KeyboardType=KeyboardType.Text) {
    val colors=MaterialTheme.colorScheme
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    BasicTextField(value,change,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).semantics {contentDescription=label}.padding(horizontal=16.dp,vertical=12.dp),
        enabled=enabled,singleLine=true,textStyle=TextStyle(fontSize=17.sp,color=colors.onSurface),
        keyboardOptions=KeyboardOptions(keyboardType=type,autoCorrectEnabled=false,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}),visualTransformation=VisualTransformation.None,
        decorationBox={inner->Box {if(value.isEmpty())Text(label,fontSize=17.sp,color=colors.onSurfaceVariant);inner()}})
    HorizontalDivider(Modifier.padding(start=16.dp),color=colors.onSurface.copy(alpha=.1f))
}
