package com.agenthub.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Disclosure state belongs to this original target and document, never command authority. */
@Composable private fun PlanDisclosure(title: String,tag: String,stateKey: Any,icon: String?=null,content: @Composable ()->Unit) {
    var expanded by remember(stateKey,tag) { mutableStateOf(false) };val t=currentProductText()
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable(role=Role.Button) { expanded=!expanded }
            .testTag(tag).semantics(mergeDescendants=true) { stateDescription=t.tr(if(expanded)"已展开" else "已折叠") },
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            icon?.let { ProductIcon(it,Modifier.size(18.dp)) }
            Text(title,Modifier.weight(1f),fontSize=14.sp)
            ProductIcon("chevron",Modifier.size(14.dp).rotate(if(expanded)90f else 0f),MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(expanded)content()
    }
}

@Composable internal fun ProposedPlanMessage(message: MobileMessage,targetKey: String,
    noticeVisibility: NativePanelNoticeVisibility?=null,originalPlan: MobileProposedPlan?=null) {
    val clipboard=LocalClipboardManager.current
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
        modifier=Modifier.fillMaxWidth().testTag("proposed-plan-message-${message.id}")) {
        Column(Modifier.padding(12.dp)) {
            PlanDisclosure(productText(if(message.status in setOf("inProgress","in_progress","in-progress","streaming"))"正在制定计划…" else "计划"),
                "proposed-plan-toggle-${message.id}",targetKey+":"+message.id,icon="lightbulb") {
                val reading=noticeVisibility?.takeIf { it.renderedKey==targetKey && originalPlan!=null &&
                    message.role=="assistant" && message.kind=="plan" && message.runId==originalPlan.turnId &&
                    message.text==originalPlan.text && message.status !in setOf("inProgress","in_progress","in-progress","running","streaming","unknown") }
                NativeNoticeVisibilityPanel(reading,originalPlan?.let(NativeNoticePanelOriginal::Plan)) {
                    MarkdownContent(message.text,"markdown-${message.id}")
                }
                TextButton(onClick={ clipboard.setText(AnnotatedString(message.text)) },modifier=Modifier.testTag("copy-plan-${message.id}")) {
                    ProductIcon("copy",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(productText("复制计划"),fontSize=12.sp)
                }
            }
        }
    }
}

@Composable internal fun RuntimePlanProgress(steps: List<MobilePlanStep>,targetKey: String) {
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("runtime-plan")) {
        Column(Modifier.padding(10.dp)) {
            PlanDisclosure(productText("执行计划 · %@/%@",steps.count { it.status=="completed" },steps.size),"runtime-plan-toggle",targetKey) {
                steps.forEachIndexed { index,step -> Row(Modifier.fillMaxWidth().padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    PlanStepStatus(step.status,Modifier.size(16.dp).testTag("runtime-plan-status-$index"))
                    Text(step.step,fontSize=12.sp,modifier=Modifier.testTag("runtime-plan-step-$index"))
                } }
            }
        }
    }
}

@Composable private fun PlanStepStatus(status: String,modifier: Modifier) {
    val foreground=MaterialTheme.colorScheme.onSurface;val background=MaterialTheme.colorScheme.surface
    val active=status in setOf("in_progress","inProgress","in-progress");val t=currentProductText()
    Canvas(modifier.semantics { contentDescription=t.tr(if(status=="completed")"已完成" else if(active)"进行中" else "未完成") }) {
        val u=size.width/24f;val center=Offset(12f*u,12f*u)
        if(status=="completed") {
            drawCircle(foreground,10f*u,center)
            drawLine(background,Offset(6f*u,12f*u),Offset(10f*u,16f*u),1.7f*u)
            drawLine(background,Offset(10f*u,16f*u),Offset(18f*u,8f*u),1.7f*u)
        } else if(active) {
            for(n in 0..11) { val a=n*Math.PI/6;drawCircle(foreground,1.15f*u,Offset(center.x+9f*u*kotlin.math.cos(a).toFloat(),center.y+9f*u*kotlin.math.sin(a).toFloat())) }
        } else drawCircle(foreground,9f*u,center,style=Stroke(1.7f*u))
    }
}

@Composable internal fun ProposedPlanReview(session: MobileSession,plan: MobileProposedPlan,noticeVisibility: NativePanelNoticeVisibility?=null) {
    val destination=session.planActionDestination()
    key(destination,plan) {
        var adjusting by remember { mutableStateOf(false) };var feedback by remember { mutableStateOf("") }
        val enabled=session.canRespondToPlan(plan,destination);val colors=MaterialTheme.colorScheme
        Surface(shape=RoundedCornerShape(18.dp),color=colors.background,border=BorderStroke(1.dp,colors.onSurface.copy(alpha=.1f)),
            modifier=Modifier.fillMaxWidth().testTag("plan-review")) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(productText("实施此计划？"),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                if(session.messages.none { it.kind=="plan"&&it.text==plan.text }) {
                    PlanDisclosure(productText("查看计划"),"review-plan-toggle",destination to plan) {
                        NativeNoticeVisibilityPanel(noticeVisibility?.forTarget(session.selectedClient.scope,session.selectedThread),NativeNoticePanelOriginal.Plan(plan)) {
                            MarkdownContent(plan.text,"proposed-plan-text")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(colors.surface,RoundedCornerShape(12.dp))
                    .clickable(enabled=enabled,role=Role.Button) { session.implementPlan(plan,destination) }
                    .testTag("implement-plan").semantics(mergeDescendants=true) { }.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    val color=colors.onSurface.copy(alpha=if(enabled)1f else .38f)
                    ProductIcon("resume",Modifier.size(18.dp),color);Text(productText("是，实施此计划"),Modifier.weight(1f),fontSize=14.sp,color=color)
                    ProductIcon("send",Modifier.size(16.dp).rotate(90f),color)
                }
                if(adjusting) {
                    OutlinedTextField(feedback,{ feedback=it },placeholder={ Text(productText("告诉助手应该如何调整计划")) },minLines=2,maxLines=5,
                        modifier=Modifier.fillMaxWidth().testTag("plan-feedback"))
                    TextButton(onClick={ session.revisePlan(plan,feedback,destination) },enabled=enabled&&feedback.isNotBlank(),modifier=Modifier.testTag("send-plan-feedback")) {
                        ProductIcon("send",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(productText("发送调整"),fontSize=14.sp)
                    }
                } else TextButton(onClick={ adjusting=true },enabled=enabled,modifier=Modifier.testTag("revise-plan")) {
                    ProductIcon("pencil",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(productText("调整计划"),fontSize=14.sp)
                }
            }
        }
    }
}
