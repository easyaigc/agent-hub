package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.core.UsageReadRules
import com.agenthub.core.UsageRules
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.NumberFormat
internal fun usageRemaining(metric:JSONObject,translate:(String)->String={it}):String? {
    if(metric.optString("state")=="unlimited")return translate("无限额")
    if(metric.optString("state")=="amount" && metric.optString("kind")=="balance")return usageMetricText(metric,translate)
    if(metric.has("remainingValue"))return metric.getString("remainingValue")+metric.optString("unit").let { if(it.isEmpty())"" else " $it" }
    if(!metric.has("remainingPercent"))return null
    val percent=metric.getDouble("remainingPercent")
    if(percent>0 && percent<0.01)return "<0.01%"
    return NumberFormat.getNumberInstance().apply { maximumFractionDigits=2 }.format(percent)+"%"
}
internal fun usageResetCountdown(raw:String,now:Long,compact:Boolean=false,translate:(String)->String={it}):String {
    val seconds=UsageRules.resetSeconds(raw,now) ?: return translate("暂不可用")
    if(seconds<=0)return translate("已到重置时间，等待更新")
    return listOf(seconds/86400 to "天",seconds%86400/3600 to "小时",seconds%3600/60 to "分钟",seconds%60 to "秒")
        .filter { it.first>0 }.take(if(compact)2 else 4).joinToString(" "){"${it.first} ${translate(it.second)}"}
}

internal fun usageMetricText(m:JSONObject,translate:(String)->String={it}):String {
    val value=when(m.optString("state")){"unlimited"->translate("无限额");"unavailable"->translate("暂不可用");else->m.optString("value")+m.optString("unit").let { if(it.isEmpty())"" else " $it" }}
    return value+(if(m.optBoolean("debt"))" · "+translate("欠额") else "")+(if(m.optString("state")=="amount" && m.optString("entitlement")=="unlimited")" · "+translate("无限额") else "")
}
internal fun usageMetricTitle(m:JSONObject):String =
    if(m.isNull("bucket"))m.optString("label") else m.optString("bucket")+" · "+m.optString("label")
private fun usageResetSummary(metric:JSONObject,now:Long,translate:(String)->String,format:(String,String)->String):String {
    if(metric.isNull("resetAt"))return translate("重置时间暂不可用")
    val value=usageResetCountdown(metric.getString("resetAt"),now,compact=true,translate=translate)
    return if(value in listOf(translate("已到重置时间，等待更新"),translate("暂不可用")))value else format("%@后重置",value)
}
private fun usageWindowSummary(metric:JSONObject,now:Long,translate:(String)->String,format:(String,String)->String)=
    usageResetSummary(metric,now,translate,format)+" · "+format("剩余%@",usageRemaining(metric,translate) ?: translate("暂不可用"))
@Composable private fun usageSummaryTime(active:Boolean):Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { while(active) { now=System.currentTimeMillis();delay(1000) } }
    return now
}
@Composable internal fun ClientUsageSummary(session:MobileSession,client:MobileClient) {
    ClientUsageSummaryContent(session.usage,client,session.usageForeground)
}
@Composable internal fun ClientUsageSummaryContent(usage:MobileUsageStore,client:MobileClient,foreground:Boolean) {
    val text=currentProductText()
    val identity=usage.identity(client)
    val view=LocalView.current
    var visible by remember(identity) { mutableStateOf(false) }
    val data=usage.value(client);val metrics=data.optJSONArray("metrics")
    val limits=if(metrics==null)emptyList()else (0 until metrics.length()).map { metrics.getJSONObject(it) }.filter { it.optString("kind")=="capacity-limit" || it.optString("kind")=="window-limit" && it.optString("state")!="unlimited" }
    val now=usageSummaryTime(foreground && visible && limits.any { it.optString("kind")=="window-limit" && it.optString("state")!="unlimited" })
    Column(Modifier.testTag("client-usage-summary-${client.id}").onGloballyPositioned { coordinates -> val bounds=coordinates.boundsInWindow();visible=bounds.width>0 && bounds.height>0 && bounds.bottom>0 && bounds.top<view.height }) {
        if(data.has("balance"))Text(data.optString("balance")+" "+data.optString("balanceUnit")+(if(data.optBoolean("balanceDebt"))" · "+text.tr("欠额") else "")+if(data.optInt("extraBalances")>0)" +${data.optInt("extraBalances")}" else "",fontSize=11.sp)
        for(metric in limits) {
            val value=if(metric.optString("kind")=="window-limit")usageWindowSummary(metric,now,{text.tr(it)},{key,arg->text.tr(key,arg)}) else usageMetricText(metric){text.tr(it)}
            Text((if(limits.size>1)usageMetricTitle(metric)+" · " else "")+value,
                modifier=Modifier.testTag("client-usage-window-"+metric.getString("id")),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
        }
        if(data.optString("status")!="ok")Text(text.tr("暂未提供额度"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(usage.cached(client))Text(text.tr("缓存额度"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(data.optBoolean("partial"))Text(text.tr("部分额度暂不可用"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(usage,identity,foreground,visible){if(foreground && visible)usage.poll(client)}
}
@Composable internal fun UsageFooter(session:MobileSession,client:MobileClient,active:Boolean) {
    UsageFooterContent(session.usage,client,active,session.usageForeground)
}
@Composable internal fun UsageFooterContent(usage:MobileUsageStore,client:MobileClient,active:Boolean,foreground:Boolean) {
    val text=currentProductText()
    val identity=usage.identity(client)
    val refreshing=usage.refreshing(client)
    val data=usage.value(client);val cached=usage.cached(client)
    var details by remember(identity) { mutableStateOf(false) }
    // A manual refresh belongs to this visible target, so closing only its
    // details does not cancel the shared result still shown by the footer.
    val scope=rememberCoroutineScope()
    var request by remember(usage,identity) { mutableStateOf<Job?>(null) }
    DisposableEffect(usage,identity,foreground,active) { onDispose { request?.cancel() } }
    val metrics=data.optJSONArray("metrics")
    val window=if(metrics==null)null else (0 until metrics.length()).map { metrics.getJSONObject(it) }
        .firstOrNull { it.optString("id")==data.optString("ringMetricId") && it.optString("kind")=="window-limit" }
    val percent=if(window==null)null else data.optDouble("percent",Double.NaN).takeIf { it.isFinite() }
    Row(Modifier.heightIn(min=48.dp).testTag("current-client-usage").clickable { details=true }.semantics { role=Role.Button;contentDescription=listOfNotNull(text.tr("当前客户端额度"),window?.let { usageRemaining(it){key->text.tr(key)} }?.let { text.tr("剩余%@",it) },if(data.has("balance"))data.optString("balance")+" "+data.optString("balanceUnit") else null,if(data.optBoolean("balanceDebt"))text.tr("欠额") else null,if(cached)text.tr("缓存额度") else null,if(data.optBoolean("partial"))text.tr("部分额度暂不可用") else null,if(percent==null && !data.has("balance"))text.tr("暂不可用") else null).joinToString("，") },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(percent!=null)Box(Modifier.size(34.dp).testTag("usage-remaining-ring"),contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),strokeWidth=3.dp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f))
            CircularProgressIndicator(progress={ (percent/100).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxSize(),strokeWidth=3.dp,color=Color(0xFF007AFF),trackColor=Color.Transparent)
            Text("${percent.coerceIn(0.0,100.0).toInt()}%",fontSize=10.sp)
        }
        if(data.has("balance"))Column(horizontalAlignment=Alignment.End) {
            Text(data.optString("balance")+(if(data.optBoolean("balanceDebt"))" · "+text.tr("欠额") else ""),fontSize=12.sp,maxLines=1)
            Text(data.optString("balanceUnit")+(if(data.optInt("extraBalances")>0)" +${data.optInt("extraBalances")}" else "")+(if(cached)" · "+text.tr("缓存") else ""),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
        } else if(cached)Text(text.tr("缓存"),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(window==null && !data.has("balance"))Text(text.tr("当前客户端额度"),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(usage,identity,foreground,active){if(foreground && active)usage.poll(client)}
    if(details) {
    LaunchedEffect(usage,identity,foreground) { if(foreground)usage.poll(client) }
    AlertDialog(onDismissRequest={details=false},title={Text(text.tr("当前客户端额度"))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(client.name)
            data.optJSONObject("source")?.let { source ->
                if(!source.isNull("scopeLabel"))Text(text.tr("额度来源")+"："+source.optString("scopeLabel"))
                if(!source.isNull("planLabel"))Text(text.tr("套餐")+"："+source.optString("planLabel"))
            }
            if(cached)Text(text.tr("缓存额度"))
            if(data.optBoolean("partial"))Text(text.tr("部分额度暂不可用"))
            if(data.has("sampledAt"))Text(text.tr("更新时间")+"："+data.optString("sampledAt"),fontSize=12.sp)
            val metrics=data.optJSONArray("metrics")
            if(metrics!=null)for(i in 0 until metrics.length()) {
                val metric=metrics.getJSONObject(i)
                HorizontalDivider()
                Text(usageMetricTitle(metric))
                SelectionContainerCompat(usageMetricText(metric){key->text.tr(key)})
                if(metric.optString("state")=="unavailable")Text(text.tr(UsageReadRules.reasonKey(metric.optString("value"))),fontSize=12.sp)
                val applies=metric.getJSONObject("appliesTo")
                Text(text.tr("适用范围")+"："+if(applies.optString("kind")=="all")text.tr("所有模型和功能") else applies.optJSONArray("ids")?.let { ids -> (0 until ids.length()).joinToString(" · "){ids.getString(it)} }.orEmpty(),fontSize=12.sp)
                Text(text.tr(when(metric.getJSONObject("combination").optString("kind")){"all-of"->"全部限制同时生效";"any-of"->"任一限制可用";else->"按服务端规则"}),fontSize=12.sp)
                Text(text.tr("观察时间")+"："+metric.optString("observedAt"),fontSize=12.sp)
                if(!metric.isNull("note"))Text(metric.optString("note"),fontSize=12.sp)
                if(!metric.isNull("resetAt"))Text(text.tr("重置时间")+"："+metric.optString("resetAt"),fontSize=12.sp)
                if(!metric.isNull("expiresAt"))Text(text.tr("到期时间")+"："+metric.optString("expiresAt"),fontSize=12.sp)
            }
            if(data.optString("status")!="ok") {
                Text(text.tr("暂未提供额度"))
                Text(text.tr(UsageReadRules.reasonKey(data.optString("reason"))))
                if(data.has("failureReason") && data.optString("failureReason")!=data.optString("reason"))Text(text.tr(UsageReadRules.reasonKey(data.optString("failureReason"))))
            }
            Text(text.tr("百分比为已用用量，各窗口单独计算"),fontSize=12.sp)
        }
    },dismissButton={TextButton(enabled=!refreshing && foreground,onClick={
        request=scope.launch { usage.refresh(client,force=true) }
    }){
        if(refreshing)CircularProgressIndicator(Modifier.size(16.dp).testTag("usage-refresh-loading"),strokeWidth=2.dp)
        Text(text.tr("刷新额度"))
    }},confirmButton={TextButton(onClick={details=false}){Text(text.tr("完成"))}})
    }
}
@Composable private fun SelectionContainerCompat(value:String){androidx.compose.foundation.text.selection.SelectionContainer {Text(value)}}
