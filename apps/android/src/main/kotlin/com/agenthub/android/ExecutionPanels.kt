package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import com.agenthub.core.SessionPolicies

@Composable
fun StorageIdentityNotice(session: MobileSession) {
    val conflict=session.codexStorageConflictMessage
    val choices=session.codexLegacyStorageChoices
    if(conflict==null && choices.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)) {
        if(conflict!=null) Text(conflict,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("storage-identity-conflict"))
        if(choices.isNotEmpty()) Box {
            TextButton(onClick={ expanded=true },modifier=Modifier.testTag("storage-identity-choices")) { Text("核对原草稿/回执",fontSize=12.sp) }
            DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
                choices.forEachIndexed { index,key ->
                    DropdownMenuItem(text={ Text("查看第 ${index+1} 份原记录") },onClick={ expanded=false;session.selectCodexLegacyStorage(key) },modifier=Modifier.testTag("storage-identity-choice-$index"))
                }
            }
        }
    }
}
@Composable internal fun ExecutionPanels(session: MobileSession,maxHeight: androidx.compose.ui.unit.Dp=320.dp,autoPresentQuestions: Boolean=true) {
    val key=session.targetKey
    Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        session.transferStatus?.let { Row(Modifier.testTag("transfer-status")) { CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(8.dp)); Text(it,fontSize=12.sp) } }
        session.runtime?.plan?.takeIf { it.isNotEmpty() }?.let { steps ->
            ExecutionCard("任务进度","runtime-plan") { steps.forEach { Text((if(it.status=="completed") "✓ " else if(it.status=="in-progress") "◉ " else "○ ")+it.step,fontSize=13.sp) } }
        }
        session.runtime?.proposedPlan?.let { plan ->
            var revising by remember(key,plan.id) { mutableStateOf(false) }
            var feedback by remember(key,plan.id) { mutableStateOf("") }
            ExecutionCard("执行计划","proposed-plan-${plan.id}") {
                MarkdownContent(plan.text,"proposed-plan-text")
                Row {
                    TextButton(onClick={ session.implementPlan(plan) },enabled=session.canRespondToPlan(plan),modifier=Modifier.testTag("implement-plan")) { Text("实施计划") }
                    TextButton(onClick={ revising=!revising },enabled=session.canRespondToPlan(plan),modifier=Modifier.testTag("revise-plan")) { Text("修改计划") }
                }
                if(revising) {
                    OutlinedTextField(feedback,{ feedback=it },label={ Text("修改建议") },modifier=Modifier.fillMaxWidth().testTag("plan-feedback"))
                    TextButton(onClick={ session.revisePlan(plan,feedback) },enabled=feedback.isNotBlank() && session.canRespondToPlan(plan),modifier=Modifier.testTag("send-plan-feedback")) { Text("提交修改建议") }
                }
            }
        }
        val approvals=session.runtime?.approvals.orEmpty()
        val async=approvals.filter { it.kind=="question" && it.interactionMode=="async" }
        if(async.isNotEmpty()) key(key) { AsyncQuestionDeck(session,async,key,autoPresentQuestions) }
        approvals.filterNot { it.kind=="question" && it.interactionMode=="async" }.forEach { approval -> key(key,approval.id,approval.turnId) { ApprovalPanel(session,approval,key) } }
        session.queuedReplies.forEachIndexed { index,receipt -> ExecutionCard("排队中的消息","queued-${receipt.operationId}") {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text("${index+1}",modifier=Modifier.testTag("queued-number-${receipt.operationId}"),fontSize=12.sp); Text(receipt.text?.takeIf { it.isNotBlank() } ?: "附件指令",fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis) }
            TextButton(onClick={ session.cancelQueue(receipt) },enabled=session.canCompose,modifier=Modifier.testTag("cancel-queued-${receipt.operationId}")) { Text("取消排队") }
        } }
        session.receiptNotices.forEach { receipt -> ExecutionCard(if(receipt.state=="uncertain") "消息执行结果待确认" else if(receipt.turnId.isNullOrEmpty()) "消息提交失败" else "任务执行失败","receipt-${receipt.operationId}") { Text(receipt.error ?: "请查询电脑上的原记录，内容已保留。",fontSize=13.sp); if(receipt.state=="failed") TextButton(onClick={ session.dismissReceiptFailure(receipt,key) },modifier=Modifier.testTag("dismiss-receipt-${receipt.operationId}")) { Text("关闭提示") } } }
        session.forkRecords.forEach { ThreadForkReceipt(session,it) }
        session.activeOperationRecords.takeLast(12).forEach { record ->
            val notSubmitted=record.kind=="reply" && record.phase=="failed" && !record.submissionAttempted
            val canRetry=session.mayRetryReply(record)
            val strongNotAccepted=record.replyRecovery?.phase=="strong-not-accepted"
            ExecutionCard(if(notSubmitted || strongNotAccepted) "未提交" else if(record.detached) "已结束等待 · 原记录保留" else if(record.kind=="creation") "新聊天提交记录" else "操作待确认","operation-${record.id}") {
                Text(if(strongNotAccepted) "电脑已明确原输入未接受；可显式重试同一原消息。" else if(notSubmitted) "内容已保留，请检查当前任务后重新发送。" else if(session.mayContinueCreation(record)) "电脑已准备，可以提交原内容" else when(record.phase) { "ready"->"本地意图已保存"; "preparing"->"电脑准备中"; "prepared"->if(session.mayContinueCreation(record)) "电脑已准备，可以提交原内容" else "原首条结果待核对，请查询原记录"; "submitting"->"提交中"; "received"->"电脑已接收"; "opening"->"原桌面正在打开"; "failed","rejected"->"操作未完成"; else->"结果待确认，查询原记录后再继续" },fontSize=13.sp)
                record.error?.let { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow {
                    if(notSubmitted) TextButton(onClick={ session.detachOperation(record) },modifier=Modifier.testTag("dismiss-operation-${record.id}")) { Text("关闭提示") }
                    else TextButton(onClick={ session.queryOperation(record) },enabled=session.transferStatus==null,modifier=Modifier.testTag("query-operation-${record.id}")) { Text("查询记录") }
                    if(canRetry) TextButton(onClick={ session.retryReply(record) },modifier=Modifier.testTag("retry-operation-${record.id}")) { Text("重试原消息") }
                    if(canRetry) TextButton(onClick={ session.editOriginalReply(record) },modifier=Modifier.testTag("edit-operation-${record.id}")) { Text("修改原消息") }
                    if(session.mayContinueCreation(record)) TextButton(onClick={ session.continueCreation(record) },enabled=session.transferStatus==null,modifier=Modifier.testTag("continue-creation-${record.id}")) { Text("提交原内容") }
                    if(!record.detached && record.phase !in listOf("failed","rejected")) TextButton(onClick={ session.detachOperation(record) },modifier=Modifier.testTag("detach-operation-${record.id}")) { Text("结束等待") }
                }
                if(!record.detached && !notSubmitted) Text("结束等待保留原操作与附件，不代表电脑任务已取消。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        session.runtime?.error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.testTag("runtime-error")) }
    }
}

@Composable private fun ExecutionCard(title: String,tag: String,content: @Composable ColumnScope.()->Unit) {
    Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { if(title.isNotEmpty()) Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold); content() }
    }
}

private class ApprovalDraft {
    val selected=mutableStateMapOf<String,List<String>>()
    val custom=mutableStateMapOf<String,String>()
}
private data class ApprovalIdentity(val id: String,val turnId: String)
private val MobileApproval.identity get()=ApprovalIdentity(id,turnId)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AsyncQuestionDeck(session: MobileSession,approvals: List<MobileApproval>,target: String,autoPresent: Boolean) {
    val ids=approvals.map { it.identity }
    var previousIds by remember { mutableStateOf(ids) }
    var selectedId by remember { mutableStateOf(ids.first()) }
    var presented by remember { mutableStateOf(autoPresent) }
    val drafts=remember { mutableStateMapOf<ApprovalIdentity,ApprovalDraft>() }
    val index=ids.indexOf(selectedId).coerceAtLeast(0)
    val current=approvals[index]
    val orange=Color(0xFFFF9500)
    val density=LocalDensity.current
    LaunchedEffect(ids) {
        drafts.keys.filter { it !in ids }.forEach { drafts.remove(it) }
        if(selectedId !in ids) selectedId=ids[(previousIds.indexOf(selectedId).coerceAtLeast(0)).coerceAtMost(ids.lastIndex)]
        previousIds=ids
    }
    fun move(offset: Int) { if(!session.isAnswering(current) && index+offset in approvals.indices) selectedId=ids[index+offset] }
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("open-async-questions").semantics { role=Role.Button }.clickable { presented=true }.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            ProductIcon("question",Modifier.size(18.dp),orange)
            Text("${approvals.size} 个问题待回答",Modifier.weight(1f).padding(start=8.dp),fontSize=14.sp,color=orange)
            ProductIcon("chevron",Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(presented) {
        val state=rememberModalBottomSheetState(skipPartiallyExpanded=false)
        ModalBottomSheet(onDismissRequest={ presented=false },sheetState=state,containerColor=MaterialTheme.colorScheme.background,
            shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),contentWindowInsets={ WindowInsets.safeDrawing },
            properties=ModalBottomSheetProperties(isAppearanceLightStatusBars=MaterialTheme.colorScheme.background!=Color.Black,isAppearanceLightNavigationBars=MaterialTheme.colorScheme.background!=Color.Black)) {
            SheetSystemBars()
            val focus=LocalFocusManager.current; val keyboard=LocalSoftwareKeyboardController.current
            DisposableEffect(Unit) { onDispose { focus.clearFocus(force=true); keyboard?.hide() } }
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("async-question-sheet")) {
                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    ProductIcon("question",Modifier.size(18.dp),orange)
                    Text("问题",Modifier.weight(1f).padding(start=6.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=orange)
                    Box(Modifier.size(48.dp).testTag("async-question-prev").semantics { contentDescription="上一题"; role=Role.Button }.clickable(enabled=index>0 && !session.isAnswering(current)) { move(-1) },contentAlignment=Alignment.Center) {
                        ProductIcon("chevron",Modifier.rotate(180f),MaterialTheme.colorScheme.onSurface.copy(alpha=if(index>0 && !session.isAnswering(current)) 1f else .3f))
                    }
                    Text("${index+1} / ${approvals.size}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("async-question-page"))
                    IconAction("chevron","下一题","async-question-next",index<approvals.lastIndex && !session.isAnswering(current)) { move(1) }
                    IconAction("close","收起问题","close-async-questions") { presented=false }
                }
                key(current.identity) {
                    DisposableEffect(Unit) { onDispose { focus.clearFocus(force=true); keyboard?.hide() } }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).pointerInput(current.identity,density) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                            var change=down; var single=true
                            do {
                                val event=awaitPointerEvent(PointerEventPass.Initial)
                                if(event.changes.count { it.pressed }>1) single=false
                                change=event.changes.firstOrNull { it.id==down.id } ?: break
                            } while(change.pressed)
                            val delta=change.position-down.position
                            if(!change.pressed && single && kotlin.math.abs(delta.x)>with(density) { 60.dp.toPx() } && kotlin.math.abs(delta.x)>kotlin.math.abs(delta.y)*1.5f) move(if(delta.x<0) 1 else -1)
                        }
                    }.padding(horizontal=16.dp).padding(bottom=16.dp)) {
                        ApprovalPanel(session,current,target,drafts.getOrPut(current.identity) { ApprovalDraft() },onQuestionFocus={ state.expand() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ApprovalPanel(session: MobileSession,approval: MobileApproval,target: String,externalDraft: ApprovalDraft?=null,onQuestionFocus: suspend ()->Unit={}) {
    val local=remember(target,approval.id,approval.turnId) { ApprovalDraft() }
    val selected=(externalDraft ?: local).selected
    val custom=(externalDraft ?: local).custom
    val async=approval.interactionMode=="async"
    val answers=approval.questions.associate { question ->
        val extra=custom[question.id].orEmpty().trim()
        question.id to if(async && extra.isNotEmpty()) listOf(extra) else selected[question.id].orEmpty()+if(extra.isEmpty()) emptyList() else listOf(extra)
    }
    ExecutionCard(if(async) "" else if(approval.kind=="question") "需要回复" else "需要审批","approval-${approval.id}") {
        if(async) Text(if(session.runtime?.turnId==approval.turnId) "任务仍在继续，可随时回答或跳过" else "任务已结束，回答将继续此对话",fontSize=12.sp,modifier=Modifier.testTag("async-question-status"))
        else Text(approval.title,fontSize=14.sp)
        if(approval.detail.isNotEmpty()) Text(approval.detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(session.isAnswering(approval)) Text("正在提交…",fontSize=12.sp,modifier=Modifier.testTag("approval-pending"))
        approval.questions.forEach { question ->
            Text(question.question,fontSize=14.sp,modifier=Modifier.testTag("question-title-${question.id}"))
            question.options.forEach { option ->
                val chosen=option.label in selected[question.id].orEmpty()
                TextButton(onClick={
                    selected[question.id]=if(question.multiple) { if(chosen) selected[question.id].orEmpty()-option.label else selected[question.id].orEmpty()+option.label } else listOf(option.label)
                    if(async) custom[question.id]=""
                },enabled=session.canAnswer(approval),modifier=Modifier.fillMaxWidth().testTag("question-option-${question.id}-${option.label}")) {
                    Text((if(chosen) "● " else "○ ")+option.label,Modifier.weight(1f))
                }
                if(option.description.isNotEmpty()) Text(option.description,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(question.custom) {
                val requester=remember { BringIntoViewRequester() }
                var focused by remember { mutableStateOf(false) }
                val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
                LaunchedEffect(focused,imeBottom) {
                    if(focused) { onQuestionFocus(); withFrameNanos { }; requester.bringIntoView() }
                }
                OutlinedTextField(custom[question.id].orEmpty(),{ custom[question.id]=it },
                    label={ Text(if(question.secret) "输入回复" else "输入回复或补充说明") },enabled=session.canAnswer(approval),
                    visualTransformation=if(question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier=Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged { focused=it.isFocused }.testTag("question-custom-${question.id}"))
            }
        }
        Row {
            if(approval.kind=="question") {
                if(async) TextButton(onClick={ session.answer(approval,"skip",emptyMap(),target) },enabled=session.canAnswer(approval),modifier=Modifier.testTag("skip-question")) { Text("跳过") }
                TextButton(onClick={ session.answer(approval,"",answers,target) },enabled=answers.isNotEmpty() && answers.values.all { it.isNotEmpty() } && session.canAnswer(approval),modifier=Modifier.testTag("answer-question")) { Text("提交回复") }
            } else approval.decisions.forEach { decision ->
                TextButton(onClick={ session.answer(approval,decision,emptyMap(),target) },enabled=session.canAnswer(approval),modifier=Modifier.testTag("approval-$decision")) {
                    Text(when(decision) { "accept","approve"->"允许一次"; "acceptForSession"->"本会话允许"; "decline","deny"->"拒绝"; else->"停止任务" },fontSize=12.sp)
                }
            }
        }
    }
}

internal fun effortLabel(value: String?)=when(value) { "none"->"无"; "minimal"->"最小"; "low"->"低"; "medium"->"中"; "high"->"高"; "xhigh"->"极高"; "max"->"最高"; "ultra"->"超高"; null->"默认强度"; else->value }
internal fun speedLabel(value: String?)=when(value) { "fast"->"快速"; "ultrafast"->"极速"; "standard"->"标准"; else->"跟随电脑" }
internal fun permissionLabel(value: String?)=when(value) { "read-only"->"请求批准"; "guardian-approvals"->"帮我批准"; "full-access"->"完全访问权限"; "confirm_every_operation"->"逐项确认"; "confirm_high_risk"->"高风险确认"; "allow_all"->"允许已授权操作"; else->"跟随电脑权限" }

@Composable internal fun GenerationControls(session: MobileSession,onSettings: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    var replyMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        val compact=maxWidth<370.dp
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(session.selectedCollaboration=="plan") ToolbarChip("计划模式","plan-mode-badge",session.canSelectPlan,"lightbulb") { session.setPlanMode(false) }
            if(session.supportsGeneration) ToolbarChip(if(compact) permissionLabel(session.selectedPermission).replace("权限","") else permissionLabel(session.selectedPermission),"permission-settings",session.canConfigureGeneration,"shield",if(session.permissionOptions.any { it.id==session.selectedPermission&&it.highPrivilege }) Color(0xFFFF9500) else colors.onSurfaceVariant,onSettings)
            Spacer(Modifier.weight(1f))
            if(session.supportsGeneration) {
                val label=if(compact) session.modelLabel.removePrefix("GPT-").take(10) else session.modelLabel
                ToolbarChip(label+" "+effortLabel(session.selectedEffort),"generation-settings",session.canConfigureGeneration,"bolt",if(session.selectedSpeed in listOf("fast","ultrafast")) Color(0xFF007AFF) else colors.onSurface,onSettings)
            }
        }
    }
    if(com.agenthub.core.MobileRules.showsReplyMode(session.running, session.canCompose, session.replyModes.isNotEmpty(), session.text, session.attachments.size)) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.End) { Box {
        TextButton(onClick={ replyMenu=true },modifier=Modifier.testTag("reply-mode")) { Text(if(session.replyMode=="steer") "引导当前任务" else "排队",fontSize=12.sp) }
        DropdownMenu(replyMenu,{ replyMenu=false }) { session.replyModes.forEach { mode -> DropdownMenuItem(text={ Text(if(mode=="steer") "引导当前任务" else "排队") },onClick={ session.setReplyMode(mode); replyMenu=false },modifier=Modifier.testTag("reply-mode-$mode")) } }
    } }
}
@Composable private fun ToolbarChip(label: String,tag: String,enabled: Boolean,icon: String,color: Color=MaterialTheme.colorScheme.onSurfaceVariant,click: ()->Unit) {
    Box(Modifier.heightIn(min=44.dp).testTag(tag).semantics { contentDescription=label; role=Role.Button }.clickable(enabled=enabled,onClick=click),contentAlignment=Alignment.Center) {
        Row(Modifier.heightIn(min=34.dp).floatingProduct(20f).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            ProductIcon(icon,Modifier.size(14.dp),color.copy(alpha=if(enabled) 1f else .5f))
            Text(label,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=color.copy(alpha=if(enabled) 1f else .5f),modifier=Modifier.widthIn(max=150.dp))
            ProductIcon("down",Modifier.size(9.dp),color)
        }
    }
}

@Composable internal fun GenerationPage(session: MobileSession) {
    val settings=session.generationSettings
    val blue=Color(0xFF007AFF)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GenerationSection("选择模型") {
            GenerationChoice("跟随电脑",settings.model==null,"model-default",session.canConfigureGeneration) { session.configure(settings.copy(model=null,effort=null,speed=null)) }
            session.models.forEach { model -> GenerationChoice(model.name+if(model.isDefault) " · 推荐" else "",session.selectedModel?.id==model.id,"model-${model.id}",session.canConfigureGeneration) {
                val next=SessionPolicies.changeModel(session.selectedEffort,settings.speed,model.supportedEfforts,model.defaultEffort,model.supportedSpeeds)
                session.configure(settings.copy(model=model.id,effort=next.effort,speed=next.speed))
            } }
            if(session.models.isEmpty()) Text(session.modelError ?: "正在读取电脑的模型列表…",Modifier.padding(16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(session.modelError!=null) TextButton(onClick={ session.retryModels() },modifier=Modifier.testTag("retry-models")) { Text("重试") }
        }
        GenerationSection("推理强度") {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                ProductIcon("bolt",color=blue); Text(effortLabel(session.selectedEffort),Modifier.weight(1f).padding(start=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=blue)
                IconAction("refresh","跟随电脑","reset-effort",session.canConfigureGeneration) { session.configure(settings.copy(effort=null)) }
            }
            val efforts=session.selectedModel?.supportedEfforts.orEmpty()
            if(efforts.size>1) Slider(value=(efforts.indexOf(session.selectedEffort).coerceAtLeast(0)).toFloat(),onValueChange={ session.configure(settings.copy(effort=efforts[it.toInt().coerceIn(efforts.indices)])) },valueRange=0f..(efforts.size-1).toFloat(),steps=(efforts.size-2).coerceAtLeast(0),enabled=session.canConfigureGeneration,colors=SliderDefaults.colors(thumbColor=blue,activeTrackColor=blue),modifier=Modifier.padding(horizontal=16.dp).testTag("effort-slider"))
            GenerationChoice("跟随电脑",settings.effort==null,"effort-default",session.canConfigureGeneration) { session.configure(settings.copy(effort=null)) }
            efforts.forEach { effort -> GenerationChoice(effortLabel(effort),session.selectedEffort==effort,"effort-$effort",session.canConfigureGeneration) { session.configure(settings.copy(effort=effort)) } }
        }
        if(session.supportsGenerationSpeed) GenerationSection("速度") {
            GenerationChoice("跟随电脑速度",settings.speed==null,"reset-speed",session.canConfigureGeneration) { session.configure(settings.copy(speed=null)) }
            session.selectedModel?.supportedSpeeds.orEmpty().forEach { speed -> GenerationChoice(speedLabel(speed),session.selectedSpeed==speed,"speed-$speed",session.canConfigureGeneration) { session.configure(settings.copy(speed=speed)) } }
            Text(if("fast" in session.selectedModel?.supportedSpeeds.orEmpty()) "加快模型响应，增加用量消耗。" else "当前模型未提供加速模式。",Modifier.padding(16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GenerationSection("应如何批准操作？") {
            GenerationChoice("跟随电脑权限",session.selectedPermission==null,"permission-default",session.canConfigureGeneration) { session.configure(settings.copy(permissionMode=null)) }
            session.permissionOptions.forEach { option ->
                GenerationChoice(option.title,session.selectedPermission==option.id,"permission-${option.id}",session.canConfigureGeneration,option.detail,option.highPrivilege) { session.configure(settings.copy(permissionMode=option.id)) }
            }
        }
        Text("选择会记住，供此客户端的新对话使用。设置随下一条消息或排队消息提交，补充指令沿用当前任务设置。",Modifier.padding(24.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(session.targetKey) { session.retryModels() }
}
@Composable private fun GenerationSection(title: String,content: @Composable ColumnScope.()->Unit) {
    Text(title,Modifier.padding(start=32.dp,top=20.dp,bottom=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.background) { Column(content=content) }
}
@Composable private fun GenerationChoice(label: String,selected: Boolean,tag: String,enabled: Boolean,detail: String?=null,highPrivilege: Boolean=false,click: ()->Unit) {
    val color=if(highPrivilege) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).semantics { this.selected=selected; role=Role.Button }.clickable(enabled=enabled,onClick=click).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label,fontSize=16.sp,color=color.copy(alpha=if(enabled) 1f else .5f)); detail?.let { Text(it,Modifier.padding(top=4.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(selected) ProductIcon("check",Modifier.size(18.dp),Color(0xFF007AFF))
    }
}
