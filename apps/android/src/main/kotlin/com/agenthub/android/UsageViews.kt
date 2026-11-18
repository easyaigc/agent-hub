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
import com.agenthub.core.UsageRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.text.NumberFormat
internal fun usageRemaining(metric:JSONObject):String? {
    if(metric.optString("state")=="unlimited")return "无限额"
    if(metric.optString("state")=="amount" && metric.optString("kind")=="balance")return metricText(metric)
    if(metric.has("remainingValue"))return metric.getString("remainingValue")+metric.optString("unit").let { if(it.isEmpty())"" else " $it" }
    if(!metric.has("remainingPercent"))return null
    val percent=metric.getDouble("remainingPercent")
    if(percent>0 && percent<0.01)return "<0.01%"
    return NumberFormat.getNumberInstance().apply { maximumFractionDigits=2 }.format(percent)+"%"
}
internal fun usageResetCountdown(raw:String,now:Long,compact:Boolean=false):String {
    val seconds=UsageRules.resetSeconds(raw,now) ?: return "暂不可用"
    if(seconds<=0)return "已到重置时间，等待更新"
    return listOf(seconds/86400 to "天",seconds%86400/3600 to "小时",seconds%3600/60 to "分钟",seconds%60 to "秒")
        .filter { it.first>0 }.take(if(compact)2 else 4).joinToString(" "){"${it.first} ${it.second}"}
}

internal fun usageTarget(client: MobileClient): JSONObject {
    val scope=client.scope ?: error("电脑运行目标尚未就绪")
    return JSONObject().put("agentId",scope.agentId).put("hostId",scope.hostId).put("workspaceId",scope.workspaceId).put("runtimeId",scope.runtimeId)
}
class MobileUsageStore(private val read: suspend (MobileClient)->String) {
    private data class Entry(val raw:String,val value:JSONObject,val failed:Boolean,val attempted:Long)
    private val entries=mutableStateMapOf<String,Entry>();private val pending=mutableMapOf<String,CompletableDeferred<Unit>>()
    private fun key(client:MobileClient)="${client.id}|${client.endpoint}|${client.scope}|${client.codexDirect}|${client.agentConnection}"
    fun value(client:MobileClient):JSONObject {
        val entry=entries[key(client)] ?: return JSONObject().put("status","unavailable")
        return try { JSONObject(UsageRules.project(entry.raw,usageTarget(client).toString(),System.currentTimeMillis())) } catch(_:Exception){entry.value}
    }
    fun cached(client:MobileClient)=entries[key(client)]?.let { it.value.optString("status")=="ok" && (it.failed || value(client).optBoolean("stale")) } ?: false
    suspend fun refresh(client:MobileClient,force:Boolean=false) {
        if(client.isDemo || client.scope==null)return
        val key=key(client);val now=System.currentTimeMillis()
        pending[key]?.let { it.await();refresh(client,false);return }
        if(!force && now-(entries[key]?.attempted ?: 0)<5000)return
        val completion=CompletableDeferred<Unit>();pending[key]=completion
        try {
            val raw=read(client);require(raw.toByteArray().size<=262144)
            val value=JSONObject(UsageRules.project(raw,usageTarget(client).toString(),System.currentTimeMillis()))
            val old=entries[key]
            entries[key]=if(value.optString("status")=="unavailable" && value.optString("reason") in listOf("timeout","offline","upstream-error") && old?.value?.optString("status")=="ok")old.copy(failed=true,attempted=now)else Entry(raw,value,false,now)
        } catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){entries[key]=entries[key]?.copy(failed=true,attempted=now) ?: Entry("",JSONObject().put("status","unavailable"),true,now)}
        finally {pending.remove(key);completion.complete(Unit)}
        if(entries.size>64)entries.minByOrNull { it.value.attempted }?.key?.let { entries.remove(it) }
    }
    suspend fun poll(client:MobileClient) { while(true){refresh(client);delay(30000)} }
}
private fun metricText(m:JSONObject)=when(m.optString("state")){"unlimited"->"无限额";"unavailable"->"暂不可用";else->m.optString("value")+m.optString("unit").let { if(it.isEmpty())"" else " $it" }}
private fun usageResetSummary(metric:JSONObject,now:Long):String {
    if(metric.isNull("resetAt"))return "重置时间暂不可用"
    val value=usageResetCountdown(metric.getString("resetAt"),now,compact=true)
    return if(value in listOf("已到重置时间，等待更新","暂不可用"))value else value+"后重置"
}
private fun usageWindowSummary(metric:JSONObject,now:Long)=usageResetSummary(metric,now)+" · 剩余"+(usageRemaining(metric) ?: "暂不可用")
@Composable private fun usageSummaryTime(active:Boolean):Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { while(active) { now=System.currentTimeMillis();delay(1000) } }
    return now
}
@Composable internal fun ClientUsageSummary(session:MobileSession,client:MobileClient) {
    val view=LocalView.current
    var visible by remember(client) { mutableStateOf(false) }
    val data=session.usage.value(client);val metrics=data.optJSONArray("metrics")
    val limits=if(metrics==null)emptyList()else (0 until metrics.length()).map { metrics.getJSONObject(it) }.filter { it.optString("kind")=="window-limit" && it.optString("state")!="unlimited" }
    val now=usageSummaryTime(session.usageForeground && visible && limits.isNotEmpty())
    Column(Modifier.onGloballyPositioned { coordinates -> val bounds=coordinates.boundsInWindow();visible=bounds.width>0 && bounds.height>0 && bounds.bottom>0 && bounds.top<view.height }) {
        if(data.has("balance"))Text(data.optString("balance")+" "+data.optString("balanceUnit")+if(data.optInt("extraBalances")>0)" +${data.optInt("extraBalances")}" else "",fontSize=11.sp)
        for(metric in limits)Text((if(limits.size>1)metric.optString("label")+" · " else "")+usageWindowSummary(metric,now),modifier=Modifier.testTag("client-usage-window-"+metric.getString("id")),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
        if(data.optString("status")!="ok")Text("暂未提供额度",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(session.usage.cached(client))Text("缓存额度",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(data.optBoolean("partial"))Text("部分额度暂不可用",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(client,session.usageForeground,visible){if(session.usageForeground && visible)session.usage.poll(client)}
}
@Composable internal fun UsageFooter(session:MobileSession,client:MobileClient,active:Boolean) {
    val data=session.usage.value(client);val cached=session.usage.cached(client)
    var details by remember(client) { mutableStateOf(false) }
    val metrics=data.optJSONArray("metrics")
    val window=if(metrics==null) null else (0 until metrics.length()).map { metrics.getJSONObject(it) }.firstOrNull { it.optString("id")==data.optString("ringMetricId") && it.optString("kind")=="window-limit" }
    val percent=if(window==null)null else data.optDouble("percent",Double.NaN).takeIf { it.isFinite() }
    Row(Modifier.heightIn(min=48.dp).testTag("current-client-usage").clickable { details=true }.semantics { role=Role.Button;contentDescription=listOfNotNull("当前客户端额度",window?.let { usageRemaining(it) }?.let { "剩余$it" },if(data.has("balance"))data.optString("balance")+" "+data.optString("balanceUnit") else null,if(cached)"缓存额度" else null,if(window==null && !data.has("balance"))"暂不可用" else null).joinToString("，") },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(percent!=null)Box(Modifier.size(34.dp).testTag("usage-remaining-ring"),contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),strokeWidth=3.dp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f))
            CircularProgressIndicator(progress={ (percent/100).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxSize(),strokeWidth=3.dp,color=Color(0xFF007AFF),trackColor=Color.Transparent)
            Text("${percent.coerceIn(0.0,100.0).toInt()}%",fontSize=10.sp)
        }
        if(data.has("balance"))Column(horizontalAlignment=Alignment.End) {
            Text(data.optString("balance"),fontSize=12.sp,maxLines=1)
            Text(data.optString("balanceUnit")+(if(data.optInt("extraBalances")>0)" +${data.optInt("extraBalances")}" else "")+(if(cached)" · 缓存" else ""),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
        } else if(cached)Text("缓存",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(window==null)Text("当前客户端额度",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(client,session.usageForeground,active){if(session.usageForeground && active)session.usage.poll(client)}
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("当前客户端额度")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(client.name)
            if(cached)Text("缓存额度")
            if(data.optBoolean("partial"))Text("部分额度暂不可用")
            if(data.has("sampledAt"))Text("更新时间：${data.optString("sampledAt")}",fontSize=12.sp)
            val metrics=data.optJSONArray("metrics")
            if(metrics!=null)for(i in 0 until metrics.length()) {
                val metric=metrics.getJSONObject(i)
                HorizontalDivider()
                Text(metric.optString("bucket")+" · "+metric.optString("label"))
                SelectionContainerCompat(metricText(metric))
                val applies=metric.getJSONObject("appliesTo")
                Text("适用范围："+if(applies.optString("kind")=="all")"所有模型和功能" else applies.optJSONArray("ids")?.let { ids -> (0 until ids.length()).joinToString(" · "){ids.getString(it)} }.orEmpty(),fontSize=12.sp)
                Text(when(metric.getJSONObject("combination").optString("kind")){"all-of"->"全部限制同时生效";"any-of"->"任一限制可用";else->"按服务端规则"},fontSize=12.sp)
                Text("观察时间：${metric.optString("observedAt")}",fontSize=12.sp)
                if(!metric.isNull("note"))Text(metric.optString("note"),fontSize=12.sp)
                if(!metric.isNull("resetAt"))Text("重置时间：${metric.optString("resetAt")}",fontSize=12.sp)
                if(!metric.isNull("expiresAt"))Text("到期时间：${metric.optString("expiresAt")}",fontSize=12.sp)
            }
            if(data.optString("status")!="ok")Text("暂未提供额度")
            Text("百分比为已用用量，各窗口单独计算",fontSize=12.sp)
        }
    },confirmButton={TextButton(onClick={details=false}){Text("完成")}})
}
@Composable private fun SelectionContainerCompat(value:String){androidx.compose.foundation.text.selection.SelectionContainer {Text(value)}}
