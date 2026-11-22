package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agenthub.core.ToolPresentation

internal val LocalSubagentMessageContext=staticCompositionLocalOf<MobileSession.MessageLinkContext?> {null}

internal val LocalSubagentHistoryAvailable=staticCompositionLocalOf {false}

private val LocalSubagentHistoryOpen=staticCompositionLocalOf<((MobileMessage)->Unit)?> {null}

@Composable internal fun SubagentHistoryAction(message: MobileMessage) {
    val open=LocalSubagentHistoryOpen.current
    if(open!=null && message.kind=="subAgent") TextButton(onClick={open(message)},
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("subagent-history-${message.id}")) {
        ProductIcon("person",Modifier.size(18.dp),MaterialTheme.colorScheme.onSurfaceVariant)
        Text(productText("子 Agent")+" · "+message.text,Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(productText(ToolPresentation.status(message.status)),style=MaterialTheme.typography.labelSmall)
        ProductIcon("chevron",Modifier.size(12.dp),MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun NativeSubagentHistoryHost(session: MobileSession,content: @Composable ()->Unit) {
    var selection by remember(session) {mutableStateOf<MobileSession.SubagentHistoryOwner?>(null)}
    val open: ((MobileMessage)->Unit)?=if(session.supportsSubagentHistory) {message ->
        session.captureSubagentHistory(message)?.let {session.coverPanelNotices();selection=it}
    } else null
    CompositionLocalProvider(LocalSubagentHistoryOpen provides open,LocalSubagentHistoryAvailable provides (open!=null)) {content()}
    selection?.let { owner ->
        val valid=session.subagentHistoryCurrent(owner)
        LaunchedEffect(owner,valid) {if(!valid && selection===owner)selection=null}
        if(valid) key(owner) { NativeSubagentHistoryDialog(session,owner) {if(selection===owner)selection=null} }
    }
}

@Composable private fun NativeSubagentHistoryDialog(session: MobileSession,owner: MobileSession.SubagentHistoryOwner,close: ()->Unit) {
    val scope=rememberCoroutineScope()
    var lineage by remember(owner) {mutableStateOf(false)}
    val parentLineage=lineage
    val valid=if(parentLineage)session.parentLineageCurrent(owner) else session.subagentHistoryCurrent(owner)
    LaunchedEffect(valid) {if(!valid)close()}
    val model=remember(owner,parentLineage) {NativeSubagentHistoryModel(scope,{
        if(parentLineage)session.parentLineageCurrent(owner) else session.subagentHistoryCurrent(owner)
    }) {child,cursor ->
        session.readSubagentHistory(owner,child,cursor,parentLineage)
    }}
    LaunchedEffect(model) {model.refresh()}
    DisposableEffect(model) {onDispose {model.close()}}
    // Child records are read-only. Never inherit a parent's attachment/open callback.
    CompositionLocalProvider(LocalSubagentHistoryOpen provides null,LocalSubagentHistoryAvailable provides false,LocalMessageResourceOpen provides null,
        LocalMessageLinkOpen provides null,LocalMarkdownLinkHandler provides null) {
        Dialog(onDismissRequest=close) {
            Surface(shape=MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(16.dp).testTag("subagent-detail-${owner.messageId}")) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(productText("子 Agent"),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=close,modifier=Modifier.testTag("subagent-detail-close")) {Text(productText("完成"))}
                    }
                    model.page?.let { page ->
                        Text(productText(if(page.association=="parent-thread-lineage")"本聊天的子任务 · 最近保存的记录" else if(page.source=="saved-history")"最近保存的记录" else "实时记录"),
                            modifier=Modifier.testTag("subagent-history-source"),style=MaterialTheme.typography.bodySmall)
                        Text(productText(ToolPresentation.status(page.children.first {it.id==page.childId}.status)),
                            style=MaterialTheme.typography.bodySmall)
                    }
                    // Keep the last authenticated child catalog while switching or after a read
                    // failure. Choosing another child retires the in-flight generation.
                    if(model.children.size>1) Row(Modifier.fillMaxWidth()) {
                            var expanded by remember {mutableStateOf(false)}
                            Box {
                                TextButton(onClick={expanded=true},modifier=Modifier.testTag("subagent-child-picker")) {
                                    Text(model.children.firstOrNull {it.id==model.selectedChild}?.title.orEmpty())
                                }
                                DropdownMenu(expanded,onDismissRequest={expanded=false}) {
                                    model.children.forEach {child -> DropdownMenuItem(text={Text(child.title)},onClick={
                                        expanded=false;model.choose(child.id)
                                    },modifier=Modifier.testTag("subagent-child-${child.id}"))}
                                }
                            }
                        } else model.children.singleOrNull()?.let {Text(it.title,style=MaterialTheme.typography.titleSmall)}
                    Row {
                        TextButton(onClick=model::refresh,enabled=!model.busy,modifier=Modifier.testTag("subagent-history-refresh")) {Text(productText("刷新"))}
                        if(model.nextCursor!=null)TextButton(onClick=model::earlier,enabled=!model.busy,
                            modifier=Modifier.testTag("subagent-history-more")) {Text(productText("显示更早消息"))}
                    }
                    if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("subagent-history-loading"))
                    if(!lineage && model.unavailable && session.supportsParentLineageHistory)
                        TextButton(onClick={lineage=true},modifier=Modifier.testTag("subagent-parent-lineage")) {
                            Text(productText("查看本聊天的子任务"))
                        }
                    if(!lineage && model.unavailable && !session.supportsParentLineageHistory)
                        Text(productText("这台电脑尚不支持按父聊天查看子任务，可返回查看原工具摘要。"),modifier=Modifier.testTag("subagent-parent-lineage-unsupported"))
                    model.error?.let {Text(productText(if(lineage)parentLineageFailureKey(model.failureStatus) else it),modifier=Modifier.testTag("subagent-history-error"),color=MaterialTheme.colorScheme.error)}
                    LazyColumn(Modifier.weight(1f).testTag("subagent-history-body"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(model.messages,key={it.id}) { message ->
                            val page=model.page
                            val resourceThread=if(owner.client.scope?.agentId=="easywork")owner.parentThreadId else page?.childId
                            val context=remember(owner,model,page,message,model.requestGeneration) {
                                MessageResourceOwnerResolver.captureChild(
                                    MobileSession.MessageLinkContext(owner.key,owner.selection,owner.connection,message,resourceThread),model,page) {
                                    if(parentLineage)session.parentLineageCurrent(owner)else session.subagentHistoryCurrent(owner)
                                }
                            }
                            CompositionLocalProvider(LocalSubagentMessageContext provides context) {
                                Column(Modifier.fillMaxWidth().testTag("subagent-history-message-${message.id}")) {
                                    if(message.role=="tool" && message.kind!="turnFailure") MessageLinkOpening(session,message) {
                                        ToolGroup("subagent-${owner.messageId}-${message.id}",listOf(message));MessageResourceCards(message)
                                    } else MessageContent(session,message,MaterialTheme.colorScheme.background==androidx.compose.ui.graphics.Color.Black,
                                        "${owner.key}:subagent:${page?.childId}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
