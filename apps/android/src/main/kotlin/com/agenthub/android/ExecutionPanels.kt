package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import com.agenthub.core.GenerationPreferenceEdit
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
/** Original client order and original IDs; trimming is presentation-only. */
@Composable internal fun QueuedCommands(session: MobileSession,interactive: Boolean) {
    val rows=session.queuedReplies
    if(rows.isEmpty()) return
    val target=session.targetKey;val turn=session.runtime?.turnId
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).height(minOf(rows.size*68,180).dp)
        .testTag("queued-commands").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { index,receipt -> key(target,receipt.operationId) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(10.dp))
                .padding(start=10.dp).testTag("queued-${receipt.operationId}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${index+1}",Modifier.width(18.dp).testTag("queued-number-${receipt.operationId}"),fontSize=12.sp,
                    fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(receipt.text?.trim()?.takeIf(String::isNotEmpty) ?: "附件指令",Modifier.weight(1f).testTag("queued-text-${receipt.operationId}"),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(session.supportsQueueExecution) TextButton(onClick={ session.executeQueue(receipt,target,turn) },
                    enabled=interactive&&session.canExecuteQueue(receipt,target,turn),contentPadding=PaddingValues(horizontal=6.dp),
                    modifier=Modifier.defaultMinSize(minWidth=44.dp,minHeight=44.dp).testTag("execute-queued-${receipt.operationId}")
                        .semantics { contentDescription="立即执行第 ${index+1} 条排队指令" }) {
                    if(session.queueActionPending(receipt)) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
                    else Text(if(session.running) "立即追加" else "立即执行",fontSize=12.sp)
                }
                val enabled=interactive&&session.canCancelQueue(receipt,target)
                Box(Modifier.size(44.dp).testTag("cancel-queued-${receipt.operationId}")
                    .semantics { contentDescription="取消第 ${index+1} 条排队指令";role=Role.Button }
                    .clickable(enabled=enabled) { session.cancelQueue(receipt,target) },contentAlignment=Alignment.Center) {
                    ProductIcon("close",Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=if(enabled)1f else .3f))
                }
            }
        } }
    }
}
@Composable internal fun ExecutionPanels(session: MobileSession,maxHeight: androidx.compose.ui.unit.Dp=320.dp,autoPresentQuestions: Boolean=true,inlineToolApprovals: Boolean=false) {
    val key=session.targetKey
    val request=session.creationWaitRequest
    Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        session.transferStatus?.let { Row(Modifier.testTag("transfer-status")) { CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(8.dp)); Text(it,fontSize=12.sp) } }
        session.runtime?.plan?.takeIf { it.isNotEmpty() }?.let { RuntimePlanProgress(it,key) }
        if(!session.running)session.runtime?.proposedPlan?.let { ProposedPlanReview(session,it) }
        val approvals=session.runtime?.approvals.orEmpty()
        val async=approvals.filter { it.kind=="question" && it.interactionMode=="async" }
        key(session.approvalActionDestination()) {
        if(async.isNotEmpty()) key(key) { AsyncQuestionDeck(session,async,key,autoPresentQuestions&&request==null) }
        approvals.filterNot { it.kind=="question" && it.interactionMode=="async" || inlineToolApprovals && it.kind!="question" }.forEach { approval -> key(key,approval.id,approval.turnId) { ApprovalPanel(session,approval,key) } }
        }
        session.receiptNotices.forEach { receipt -> ReceiptNotice(receipt,key) { session.dismissReceiptFailure(receipt,key) } }
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
                    if(record.kind=="creation" && record.phase=="rejected") {
                        val original=session.creationWaitContext(record)
                        TextButton(onClick={ original?.let(session::returnRejectedCreation) },enabled=original!=null&&session.transferStatus==null,
                            modifier=Modifier.testTag("return-rejected-creation-${record.id}")) { Text("返回草稿") }
                    }
                    if(canRetry) TextButton(onClick={ session.retryReply(record) },modifier=Modifier.testTag("retry-operation-${record.id}")) { Text("重试原消息") }
                    if(canRetry) TextButton(onClick={ session.editOriginalReply(record) },modifier=Modifier.testTag("edit-operation-${record.id}")) { Text("修改原消息") }
                    if(session.mayContinueCreation(record)) TextButton(onClick={ session.continueCreation(record) },enabled=session.transferStatus==null,modifier=Modifier.testTag("continue-creation-${record.id}")) { Text("提交原内容") }
                    if(!record.detached && record.phase!="failed" && (record.phase!="rejected" || record.kind=="creation")) TextButton(onClick={ if(record.kind=="creation")session.requestCreationWait(record) else session.detachOperation(record) },enabled=record.kind!="creation" || session.creationWaitContext(record)!=null,modifier=Modifier.testTag("detach-operation-${record.id}")) { Text("结束等待") }
                }
                if(!record.detached && !notSubmitted) Text("结束等待保留原操作与附件，不代表电脑任务已取消。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        session.runtime?.error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.testTag("runtime-error")) }
    }
    request?.let { original ->
        AlertDialog(modifier=Modifier.testTag("creation-end-wait-dialog"),
            onDismissRequest={ session.continueCreationWaiting(original) },
            title={ Text("结束等待？") },
            text={ Text("结束等待不会撤销可能已执行的请求。原记录仍可查询；再次发送将是一条新请求。") },
            confirmButton={ TextButton(onClick={ session.endCreationWait(original) },
                enabled=session.mayEndCreationWait(original),modifier=Modifier.testTag("creation-end-wait-confirm")) { Text("保留记录并返回编辑") } },
            dismissButton={ TextButton(onClick={ session.continueCreationWaiting(original) },modifier=Modifier.testTag("creation-end-wait-cancel")) { Text("继续等待") } })
    }
}

/** Presentation only. A historical unknown never creates a current operation or retry action. */
@Composable private fun ReceiptNotice(receipt: DeliveryReceipt,targetKey: String,dismiss: ()->Unit) {
    val queueError=receipt.queueSyncError?.takeIf { it.isNotEmpty() }
    val historical=queueError==null && receipt.state=="uncertain" && receipt.noticeContext=="historical"
    var expanded by remember(targetKey,receipt.operationId,historical) { mutableStateOf(false) }
    val title=if(queueError!=null) "客户端队列同步失败" else if(historical) "" else if(receipt.state=="uncertain") "消息执行结果待确认" else if(receipt.turnId.isNullOrEmpty()) "消息提交失败" else "任务执行失败"
    ExecutionCard(title,"receipt-${receipt.operationId}") {
        queueError?.let { SelectionContainer { Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(historical) {
            Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable(role=Role.Button) { expanded=!expanded }
                .testTag("historical-receipt-toggle-${receipt.operationId}").semantics(mergeDescendants=true) { stateDescription=if(expanded) "已展开" else "已折叠" },verticalAlignment=Alignment.CenterVertically) {
                Text("历史消息结果待核对",Modifier.weight(1f),fontSize=12.sp)
                ProductIcon(if(expanded) "down" else "chevron",Modifier.size(12.dp),MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(expanded) {
                Text("电脑已继续后续任务。这条旧消息是否执行仍缺少确认，不代表当前任务在等待；不会自动重发。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                receipt.text?.takeIf { it.isNotEmpty() }?.let { SelectionContainer { Text(it,fontSize=12.sp) } }
            }
        }
        receipt.error?.takeIf { it.isNotEmpty() }?.let { SelectionContainer { Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(receipt.state=="failed") TextButton(onClick=dismiss,modifier=Modifier.testTag("dismiss-receipt-${receipt.operationId}")) { Text("关闭提示") }
    }
}

@Composable private fun ExecutionCard(title: String,tag: String,compact: Boolean=false,content: @Composable ColumnScope.()->Unit) {
    Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(if(compact)8.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(if(compact)4.dp else 6.dp)) { if(title.isNotEmpty()) Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold); content() }
    }
}

private class ApprovalDraft {
    val selected=mutableStateMapOf<String,List<String>>()
    val custom=mutableStateMapOf<String,String>()
}
private data class ApprovalIdentity(val original: MobileApproval)
private val MobileApproval.identity get()=ApprovalIdentity(this)

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
        val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
        ModalBottomSheet(onDismissRequest={ presented=false },sheetState=state,containerColor=MaterialTheme.colorScheme.background,
            shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),contentWindowInsets={ WindowInsets.safeDrawing },dragHandle=null,
            properties=ModalBottomSheetProperties(isAppearanceLightStatusBars=MaterialTheme.colorScheme.background!=Color.Black,isAppearanceLightNavigationBars=MaterialTheme.colorScheme.background!=Color.Black)) {
            SheetSystemBars()
            val focus=LocalFocusManager.current; val keyboard=LocalSoftwareKeyboardController.current
            DisposableEffect(Unit) { onDispose { focus.clearFocus(force=true); keyboard?.hide() } }
            val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            Box(Modifier.fillMaxWidth().height(if(keyboardVisible)0.dp else 24.dp).semantics { hideFromAccessibility() },contentAlignment=Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.4f),RoundedCornerShape(2.dp)))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight().testTag("async-question-sheet").semantics { dismiss { presented=false;true } }) {
            // Keep a finite inner reader while short IME viewports can scroll the whole panel.
            // Reparenting focused answer fields when the keyboard appears would lose their focus.
            val answerViewportHeight=maxHeight
            val contentHeight=maxOf(maxHeight,200.dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("async-question-outer-scroll")) {
            Column(Modifier.fillMaxWidth().height(contentHeight)) {
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
                        ApprovalPanel(session,current,target,drafts.getOrPut(current.identity) { ApprovalDraft() },onQuestionFocus={ state.expand() },answerViewportHeight=answerViewportHeight)
                    }
                }
            }
            }
            }
        }
    }
}

@Composable internal fun InlineToolApproval(session: MobileSession,approval: MobileApproval) {
    ApprovalPanel(session,approval,session.targetKey,compact=true)
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ApprovalPanel(session: MobileSession,approval: MobileApproval,target: String,externalDraft: ApprovalDraft?=null,onQuestionFocus: suspend ()->Unit={},compact: Boolean=false,answerViewportHeight: androidx.compose.ui.unit.Dp?=null) {
    val destination=session.approvalActionDestination()
    if(destination.key!=target)return
    key(destination,approval) { ApprovalPanelContent(session,approval,destination,externalDraft,onQuestionFocus,compact,answerViewportHeight) }
}
@Composable private fun ApprovalPanelContent(session: MobileSession,approval: MobileApproval,destination: DraftDestination,externalDraft: ApprovalDraft?,onQuestionFocus: suspend ()->Unit,compact: Boolean,answerViewportHeight: androidx.compose.ui.unit.Dp?) {
    val local=remember { ApprovalDraft() }
    val editable=session.canEditApproval(approval,destination)
    val enabled=session.canAnswer(approval,destination)
    val selected=(externalDraft ?: local).selected
    val custom=(externalDraft ?: local).custom
    val async=approval.interactionMode=="async"
    val answers=approval.questions.associate { question ->
        val extra=custom[question.id].orEmpty().trim()
        question.id to if(async && extra.isNotEmpty()) listOf(extra) else selected[question.id].orEmpty()+if(extra.isEmpty()) emptyList() else listOf(extra)
    }
    ExecutionCard(if(async) "" else if(approval.kind=="question") "需要回复" else "需要审批","approval-${approval.id}",compact=compact) {
        if(async) Text(if(session.runtime?.turnId==approval.turnId) "任务仍在继续，可随时回答或跳过" else "任务已结束，回答将继续此对话",fontSize=12.sp,modifier=Modifier.testTag("async-question-status"))
        else SelectionContainer { Text(approval.title,fontSize=14.sp,modifier=Modifier.fillMaxWidth()) }
        if(approval.detail.isNotEmpty()) Text(approval.detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(session.isAnswering(approval)) Row(Modifier.testTag("approval-pending"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp).testTag("approval-pending-progress"),strokeWidth=1.5.dp);Text("正在提交…",fontSize=12.sp)
        }
        approval.questions.forEach { question ->
            Text(question.question,fontSize=14.sp,modifier=Modifier.testTag("question-title-${question.id}"))
            question.options.forEach { option ->
                val chosen=option.label in selected[question.id].orEmpty()
                val select={
                    selected[question.id]=if(question.multiple) { if(chosen) selected[question.id].orEmpty()-option.label else selected[question.id].orEmpty()+option.label } else listOf(option.label)
                    if(async) custom[question.id]=""
                }
                val choice=if(question.multiple) Modifier.toggleable(chosen,enabled=editable,role=Role.Checkbox) { select() }
                    else Modifier.selectable(chosen,enabled=editable,role=Role.RadioButton,onClick=select)
                Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("question-option-${question.id}-${option.label}")
                    .then(choice).semantics(mergeDescendants=true) { stateDescription=if(chosen)"已选择" else "未选择" }.padding(vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(question.multiple) Checkbox(chosen,onCheckedChange=null,enabled=editable,modifier=Modifier.size(20.dp).clearAndSetSemantics {})
                    else RadioButton(chosen,onClick=null,enabled=editable,modifier=Modifier.size(20.dp).clearAndSetSemantics {})
                    Column(Modifier.weight(1f)) {
                        Text(option.label,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(editable)1f else .38f))
                        if(option.description.isNotEmpty()) Text(option.description,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=if(editable)1f else .38f))
                    }
                }
            }
            if(question.custom) {
                val requester=remember { BringIntoViewRequester() }
                var focused by remember { mutableStateOf(false) }
                val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
                LaunchedEffect(focused) { if(focused) onQuestionFocus() }
                LaunchedEffect(focused,imeBottom) {
                    // The async reader already propagates the native TextField focus request
                    // through both scrollers. A second request races page disposal on API26.
                    if(focused && !async) { withFrameNanos { }; requester.bringIntoView() }
                }
                val value=custom[question.id].orEmpty()
                val hint=if(question.secret) "输入回复" else "输入回复或补充说明"
                val density=LocalDensity.current
                // The whole field remains reachable while its text scrolls inside the line budget.
                val lines=if(question.secret)1 else answerViewportHeight?.let { ((it.value-52f)/(20f*density.fontScale)).toInt().coerceIn(1,4) } ?: 4
                val shape=RoundedCornerShape(10.dp)
                BasicTextField(value,{ custom[question.id]=it },enabled=editable,
                    textStyle=TextStyle(fontSize=15.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(editable)1f else .38f)),
                    visualTransformation=if(question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    singleLine=question.secret,maxLines=lines,
                    keyboardOptions=if(question.secret) KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password,imeAction=ImeAction.Done) else KeyboardOptions.Default,
                    modifier=Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged { focused=it.isFocused }.semantics { contentDescription=hint }.testTag("question-custom-${question.id}"),
                    decorationBox={ inner ->
                        Box(Modifier.fillMaxWidth().heightIn(min=44.dp).background(MaterialTheme.colorScheme.surface,shape)
                            .border(1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),shape).padding(horizontal=10.dp,vertical=10.dp),contentAlignment=Alignment.CenterStart) {
                            if(value.isEmpty()) Text(hint,fontSize=15.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=if(editable).6f else .38f))
                            inner()
                        }
                    })
            }
        }
        if(approval.kind=="question") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(async) TextButton(onClick={ session.answer(approval,"skip",emptyMap(),destination) },enabled=enabled,modifier=Modifier.defaultMinSize(minWidth=44.dp,minHeight=44.dp).testTag("skip-question")) { Text("跳过") }
            Button(onClick={ session.answer(approval,"",answers,destination) },enabled=answers.isNotEmpty() && answers.values.all { it.isNotEmpty() } && enabled,
                modifier=Modifier.weight(1f).heightIn(min=44.dp).testTag("answer-question"),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp),
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.onSurface,contentColor=MaterialTheme.colorScheme.background,
                    disabledContainerColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant)) { Text("提交回复",fontSize=14.sp,fontWeight=FontWeight.SemiBold) }
        } else FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            approval.decisions.forEach { decision ->
                TextButton(onClick={ session.answer(approval,decision,emptyMap(),destination) },enabled=enabled,modifier=Modifier.heightIn(min=44.dp).testTag("approval-$decision")) {
                    Text(approval.decisionLabels[decision] ?: when(decision) { "accept","approve"->"允许一次"; "acceptForSession"->"本会话允许"; "decline","deny"->"拒绝"; else->"停止任务" },fontSize=12.sp)
                }
            }
        }
    }
}

internal fun effortLabel(value: String?)=when(value) { "none"->"无"; "minimal"->"最小"; "low"->"低"; "medium"->"中"; "high"->"高"; "xhigh"->"极高"; "max"->"最高"; "ultra"->"超高"; null->"默认强度"; else->value }
internal fun speedLabel(value: String?)=when(value) { "fast"->"快速"; "ultrafast"->"极速"; "standard"->"标准"; else->"跟随电脑" }
internal fun permissionLabel(value: String?)=when(value) { "read-only"->"请求批准"; "guardian-approvals"->"帮我批准"; "full-access"->"完全访问权限"; "confirm_every_operation"->"逐项确认"; "confirm_high_risk"->"高风险确认"; "allow_all"->"允许已授权操作"; else->"跟随电脑权限" }

@Composable internal fun GenerationControls(session: MobileSession,compactLayout: Boolean=false,onSettings: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    val showMode=com.agenthub.core.MobileRules.showsReplyMode(session.running,session.canCompose,session.replyModes.isNotEmpty(),session.text,session.attachments.size)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        val compact=maxWidth<370.dp
        val singleRow=compactLayout && maxWidth>=600.dp
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                if(session.selectedCollaboration=="plan") ToolbarChip("计划模式","plan-mode-badge",session.canSelectPlan,"lightbulb") { session.setPlanMode(false) }
                if(session.supportsGeneration) ToolbarChip(if(compact) permissionLabel(session.selectedPermission).replace("权限","") else permissionLabel(session.selectedPermission),"permission-settings",session.canConfigureGeneration,"shield",if(session.permissionOptions.any { it.id==session.selectedPermission&&it.highPrivilege }) Color(0xFFFF9500) else colors.onSurfaceVariant,onSettings)
                Spacer(Modifier.weight(1f))
                if(session.supportsGeneration) {
                    val label=if(compact) session.modelLabel.removePrefix("GPT-").take(10) else session.modelLabel
                    ToolbarChip(label+" "+effortLabel(session.selectedEffort),"generation-settings",session.canConfigureGeneration,"bolt",if(session.selectedSpeed in listOf("fast","ultrafast")) Color(0xFF007AFF) else colors.onSurface,onSettings)
                }
                if(singleRow && showMode) ReplyModeControls(session,Modifier.width(260.dp))
            }
            if(!singleRow && showMode) ReplyModeControls(session,Modifier.fillMaxWidth())
            if(showMode && session.replyMode=="queue"&&"queue" !in session.replyModes) Text("客户端队列不可用，消息未提交。可保留草稿，或自行选择立即追加。",fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.testTag("client-queue-unavailable"))
        }
    }
}
@Composable private fun ReplyModeControls(session: MobileSession,modifier: Modifier) {
    val target=session.targetKey;val modes=session.replyModes;val colors=MaterialTheme.colorScheme
    Row(modifier.background(colors.onSurface.copy(alpha=.08f),RoundedCornerShape(8.dp)).padding(3.dp).selectableGroup().testTag("reply-mode")) {
        ReplyModeSegment("排队执行","queue",session.replyMode=="queue",session.canSelectReplyMode&&"queue" in modes) {session.setReplyMode("queue",target)}
        if("steer" in modes) ReplyModeSegment("立即追加","steer",session.replyMode=="steer",session.canSelectReplyMode) {session.setReplyMode("steer",target)}
    }
}
@Composable private fun RowScope.ReplyModeSegment(label: String,mode: String,selected: Boolean,enabled: Boolean,choose: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    val fill=if(selected) { if(colors.background==Color.Black)Color(0xFF636366) else Color.White } else Color.Transparent
    Box(Modifier.weight(1f).heightIn(min=44.dp).clip(RoundedCornerShape(6.dp)).background(fill)
        .selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick=choose).testTag("reply-mode-$mode").padding(horizontal=8.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
        Text(label,fontSize=12.sp,maxLines=1,color=colors.onSurface.copy(alpha=if(enabled)1f else .38f))
    }
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
    val target=session.targetKey
    val blue=Color(0xFF007AFF)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        fun changeModel(id: String?) {
            session.updateGeneration(target,GenerationPreferenceEdit.MODEL) { current ->
                val latest=if(id==null) null else session.models.firstOrNull { it.id==id } ?: return@updateGeneration null
                val next=SessionPolicies.changeModel(session.selectedEffort,current.speed,latest?.supportedEfforts.orEmpty(),latest?.defaultEffort,latest?.supportedSpeeds.orEmpty())
                current.copy(model=id,effort=next.effort,speed=next.speed)
            }
        }
        GenerationSection("选择模型") {
            val selected=settings.model ?: session.runtime?.settings?.model ?: session.selectedModel?.id ?: ""
            GenerationMenu("模型",session.modelLabel,"model-picker",target,selected,session.canConfigureGeneration,
                listOf(GenerationMenuChoice("","跟随电脑","model-default"))+session.models.map { GenerationMenuChoice(it.id,it.name+if(it.isDefault) " · 推荐" else "","model-${it.id}") }) { changeModel(it.takeIf(String::isNotEmpty)) }
            if(session.models.isEmpty()) Text(session.modelError ?: "正在读取电脑的模型列表…",Modifier.padding(16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(session.modelError!=null) TextButton(onClick={ session.retryModels() },modifier=Modifier.testTag("retry-models")) { Text("重试") }
        }
        GenerationSection("推理强度") {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                ProductIcon("bolt",color=blue); Text(effortLabel(session.selectedEffort),Modifier.weight(1f).padding(start=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=blue)
                IconAction("refresh","跟随电脑","reset-effort",session.canConfigureGeneration) { session.updateGeneration(target,GenerationPreferenceEdit.EFFORT) { it.copy(effort=null) } }
            }
            val efforts=session.selectedModel?.supportedEfforts.orEmpty()
            if(efforts.size>1) Slider(value=(efforts.indexOf(session.selectedEffort).coerceAtLeast(0)).toFloat(),onValueChange={ val chosen=efforts[it.toInt().coerceIn(efforts.indices)];session.updateGeneration(target,GenerationPreferenceEdit.EFFORT) { current -> if(chosen in session.selectedModel?.supportedEfforts.orEmpty()) current.copy(effort=chosen) else null } },valueRange=0f..(efforts.size-1).toFloat(),steps=(efforts.size-2).coerceAtLeast(0),enabled=session.canConfigureGeneration,colors=SliderDefaults.colors(thumbColor=blue,activeTrackColor=blue,inactiveTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),activeTickColor=blue,inactiveTickColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f)),modifier=Modifier.padding(horizontal=16.dp).testTag("effort-slider"))
            GenerationMenu("强度",effortLabel(session.selectedEffort),"effort-picker",target,session.selectedEffort ?: "",session.canConfigureGeneration,
                listOf(GenerationMenuChoice("","跟随电脑","effort-default"))+efforts.map { GenerationMenuChoice(it,effortLabel(it),"effort-$it") }) { choice ->
                session.updateGeneration(target,GenerationPreferenceEdit.EFFORT) { current -> if(choice.isEmpty() || choice in session.selectedModel?.supportedEfforts.orEmpty()) current.copy(effort=choice.takeIf(String::isNotEmpty)) else null }
            }
        }
        if(session.supportsGenerationSpeed) GenerationSection("速度") {
            val speeds=session.selectedModel?.supportedSpeeds.orEmpty()
            if("ultrafast" in speeds) {
                GenerationMenu("速度模式",speedLabel(session.selectedSpeed),"speed-picker",target,session.selectedSpeed ?: "",session.canConfigureGeneration,
                    listOf(GenerationMenuChoice("","跟随电脑","reset-speed-choice"))+speeds.map { GenerationMenuChoice(it,speedLabel(it),"speed-$it") }) { choice ->
                    session.updateGeneration(target,GenerationPreferenceEdit.SPEED) { current -> if(session.supportsGenerationSpeed && (choice.isEmpty() || choice in session.selectedModel?.supportedSpeeds.orEmpty())) current.copy(speed=choice.takeIf(String::isNotEmpty)) else null }
                }
            } else {
                val enabled=session.canConfigureGeneration && "fast" in speeds
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("fast-mode-toggle").semantics(mergeDescendants=true) {}
                    .toggleable(session.selectedSpeed=="fast",enabled=enabled,role=Role.Switch) { on ->
                        session.updateGeneration(target,GenerationPreferenceEdit.SPEED) { current -> if(session.supportsGenerationSpeed && "fast" in session.selectedModel?.supportedSpeeds.orEmpty()) current.copy(speed=if(on) "fast" else "standard") else null }
                    }.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("快速模式",Modifier.weight(1f),fontSize=16.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .5f))
                    Switch(session.selectedSpeed=="fast",onCheckedChange=null,enabled=enabled,modifier=Modifier.clearAndSetSemantics {},
                        colors=SwitchDefaults.colors(checkedTrackColor=Color(0xFF34C759),checkedThumbColor=Color.White,uncheckedTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),uncheckedThumbColor=Color.White,uncheckedBorderColor=Color.Transparent,disabledUncheckedTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.06f),disabledUncheckedThumbColor=Color.White,disabledUncheckedBorderColor=Color.Transparent))
                }
            }
            TextButton(onClick={ session.updateGeneration(target,GenerationPreferenceEdit.SPEED) { if(session.supportsGenerationSpeed) it.copy(speed=null) else null } },enabled=session.canConfigureGeneration,colors=ButtonDefaults.textButtonColors(contentColor=blue),modifier=Modifier.testTag("reset-speed")) { Text("跟随电脑速度") }
            Text(if("fast" in speeds) "加快模型响应，增加用量消耗。" else "当前模型未提供加速模式。",Modifier.padding(16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GenerationSection("应如何批准操作？") {
            GenerationChoice("跟随电脑权限",session.selectedPermission==null,"permission-default",session.canConfigureGeneration) { session.updateGeneration(target,GenerationPreferenceEdit.PERMISSION) { it.copy(permissionMode=null) } }
            session.permissionOptions.forEach { option ->
                GenerationChoice(option.title,session.selectedPermission==option.id,"permission-${option.id}",session.canConfigureGeneration,option.detail,option.highPrivilege) { session.updateGeneration(target,GenerationPreferenceEdit.PERMISSION) { current -> if(session.permissionOptions.any { it.id==option.id }) current.copy(permissionMode=option.id) else null } }
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

private data class GenerationMenuChoice(val id: String,val title: String,val tag: String)
@Composable private fun GenerationMenu(label: String,value: String,tag: String,target: String,selected: String,enabled: Boolean,
    choices: List<GenerationMenuChoice>,choose: (String)->Unit) {
    var expanded by remember(target) { mutableStateOf(false) }
    val blue=Color(0xFF007AFF)
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).semantics { role=Role.Button }
            .clickable(enabled=enabled) { expanded=true }.padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,fontSize=16.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .5f))
            Spacer(Modifier.weight(1f))
            Text(value,Modifier.weight(1f,fill=false).padding(start=12.dp,end=6.dp),fontSize=16.sp,color=blue.copy(alpha=if(enabled) 1f else .5f),maxLines=1,overflow=TextOverflow.Ellipsis)
            ProductIcon("picker",Modifier.size(16.dp),blue.copy(alpha=if(enabled) 1f else .5f))
        }
        DropdownMenu(expanded=expanded&&enabled,onDismissRequest={ expanded=false },containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            choices.forEach { choice -> DropdownMenuItem(text={ Text(choice.title) },onClick={ expanded=false;choose(choice.id) },enabled=enabled,
                leadingIcon={ if(selected==choice.id) ProductIcon("check",Modifier.size(18.dp),blue) else Spacer(Modifier.size(18.dp)) },
                modifier=Modifier.testTag(choice.tag).semantics { this.selected=selected==choice.id;role=Role.RadioButton }) }
        }
    }
}
