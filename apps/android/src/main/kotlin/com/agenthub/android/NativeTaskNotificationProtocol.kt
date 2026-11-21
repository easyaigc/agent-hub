package com.agenthub.android

import com.agenthub.core.DeviceReadingVersion
import com.agenthub.core.TargetScope
import com.agenthub.core.TaskNotificationRules as Rules
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class NativeTaskNotificationPreferences(
    val revision: String="initial",val generation: Long=0,val enabled: Boolean=false,
    val kinds: List<String> = listOf("approval","question","plan","paused","interrupted","failed","uncertain"),
    val excludedHosts: List<String> = emptyList(),val foregroundBanner: Boolean=true,val soundEnabled: Boolean=true,
    val badgeEnabled: Boolean=true,val showThreadTitle: Boolean=true,val timeSensitive: Boolean=false,
    val sound: String="default",val categorySounds: Map<String,String> = emptyMap()
) {
    fun json()=JSONObject().put("version",1).put("revision",revision).put("generation",generation).put("enabled",enabled)
        .put("kinds",JSONArray(kinds)).put("excludedHosts",JSONArray(excludedHosts)).put("foregroundBanner",foregroundBanner)
        .put("soundEnabled",soundEnabled).put("badgeEnabled",badgeEnabled).put("showThreadTitle",showThreadTitle)
        .put("timeSensitive",timeSensitive).put("sound",sound).put("categorySounds",JSONObject(categorySounds))
}
data class NativeTaskReadPosition(val revision: Long, val messageId: String, val contentVersion: String) {
    init { require(revision in 1..9_007_199_254_740_991L && messageId.length in 1..512 && chatNoticeHash(contentVersion)) }
    companion object {
        fun decode(value: JSONObject): NativeTaskReadPosition {
            NativeChatNotificationWire.keys(value, setOf("revision", "messageId", "contentVersion"))
            return NativeTaskReadPosition(NativeChatNotificationWire.long(value, "revision"), NativeChatNotificationWire.text(value, "messageId", 512), NativeChatNotificationWire.text(value, "contentVersion", 64))
        }
    }
}
data class NativeTaskNotice(val id: String,val target: TargetScope,val kind: String,val occurredAt: String,
    val threadId: String?,val runId: String?,val requestId: String?,val operationId: String?,val threadTitle: String?,
    val taskSummary: String?,val readTarget: TargetScope?,val readPosition: NativeTaskReadPosition?,val raw: String)
data class NativeTaskNotificationStatus(val target: TargetScope,val delivery: String,val state: String,val reason: String?,val raw: String) {
    val label get()=when {
        state=="disabled"->"已关闭"
        delivery=="foreground-only"->"仅前台提醒"
        state=="unavailable"->"后台推送不可用"
        delivery=="fcm"&&state=="ready"->"后台提醒已连接"
        else->"推送待同步"
    }
}
data class NativeTaskNotificationFeed(val events: List<NativeTaskNotice>,val cursor: String,val activeIds: Set<String>?,val status: NativeTaskNotificationStatus?)
data class NativeTaskNotificationRegistration(val deviceId: String,val publicKey: String,val preferences: NativeTaskNotificationPreferences,
    val push:NativeTaskPushRegistration?=null)

internal object NativeTaskNotificationCodec {
    private fun keys(value: JSONObject,required: Set<String>,optional: Set<String> = emptySet()) {
        val actual=value.keys().asSequence().toSet();require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "通知资料格式无效" }
    }
    private fun text(value: JSONObject,name: String): String { require(value.get(name) is String);return value.getString(name) }
    private fun bool(value: JSONObject,name: String): Boolean { require(value.get(name) is Boolean);return value.getBoolean(name) }
    private fun strings(value: JSONArray): List<String> = (0 until value.length()).map { require(value.get(it) is String);value.getString(it) }
    private fun number(value: JSONObject,name: String): Long {
        val raw=value.get(name);require(raw is Number);return raw.toString().toLongOrNull()?.also { require(it in 0..9_007_199_254_740_991L) } ?: error("通知版本格式无效")
    }
    private fun optional(value: JSONObject,name: String): String? = if(!value.has(name))null else text(value,name)
    fun target(value: JSONObject): TargetScope {
        keys(value,setOf("agentId","hostId","workspaceId","runtimeId"),setOf("account"))
        for(name in listOf("agentId","hostId","workspaceId","runtimeId"))text(value,name)
        value.optJSONObject("account")?.let { keys(it,setOf("authority","subject"));text(it,"authority");text(it,"subject") }
        require(!value.has("account") || value.get("account") is JSONObject)
        return HostReadCodec.target(value)!!.also { require(Rules.target(it));CodexCommandCodec.target(it) }
    }
    fun preferences(value: JSONObject): NativeTaskNotificationPreferences {
        keys(value,setOf("version","revision","generation","enabled","kinds","excludedHosts","foregroundBanner","soundEnabled","badgeEnabled","showThreadTitle","timeSensitive","sound","categorySounds"))
        require(number(value,"version")==1L)
        val revision=text(value,"revision");require(Rules.identifier(revision))
        val kinds=strings(value.getJSONArray("kinds"));require(kinds.distinct().size==kinds.size && kinds.all { it in Rules.kinds })
        val excluded=strings(value.getJSONArray("excludedHosts"));require(excluded.size<=256 && excluded.all(Rules::identifier))
        val sound=text(value,"sound");require(Rules.sound(sound))
        val categories=value.getJSONObject("categorySounds");val sounds=categories.keys().asSequence().associateWith { text(categories,it) }
        require(sounds.all { it.key in Rules.kinds && Rules.sound(it.value) })
        return NativeTaskNotificationPreferences(revision,number(value,"generation"),bool(value,"enabled"),kinds,excluded,
            bool(value,"foregroundBanner"),bool(value,"soundEnabled"),bool(value,"badgeEnabled"),bool(value,"showThreadTitle"),bool(value,"timeSensitive"),sound,sounds)
    }
    fun notice(value: JSONObject,expected: TargetScope): NativeTaskNotice {
        keys(value,setOf("version","id","target","kind","occurredAt"),setOf("threadId","runId","requestId","operationId","threadTitle","taskSummary","readTarget","readPosition"))
        require(number(value,"version")==1L)
        val id=text(value,"id");val kind=text(value,"kind");val scope=target(value.getJSONObject("target"))
        require(Rules.eventId(id) && kind in Rules.kinds && sameScope(scope,expected)) { "通知完整运行目标不匹配" }
        val occurred=text(value,"occurredAt");Instant.parse(occurred)
        val thread=optional(value,"threadId");val run=optional(value,"runId");val request=optional(value,"requestId");val operation=optional(value,"operationId")
        require(listOfNotNull(thread,run,request,operation).all(Rules::identifier))
        val title=optional(value,"threadTitle");require(title==null || title.length<=100)
        val summary=optional(value,"taskSummary");require(summary==null || Rules.summary(summary))
        val readTarget=if(value.has("readTarget"))target(value.getJSONObject("readTarget")) else null
        val position=if(value.has("readPosition"))NativeTaskReadPosition.decode(value.getJSONObject("readPosition")) else null
        require(Rules.readPosition(kind,thread,readTarget,position?.let { DeviceReadingVersion(it.revision,it.messageId,it.contentVersion) },scope))
        return NativeTaskNotice(id,scope,kind,occurred,thread,run,request,operation,title,summary,readTarget,position,value.toString())
    }
    fun status(value: JSONObject,expected: TargetScope): NativeTaskNotificationStatus {
        keys(value,setOf("version","target","delivery","state"),setOf("reason","revision","bindingId","publicKey","relayURL","managementToken","lastDelivery","pushProvider","deviceTokenSha256"))
        val target=target(value.getJSONObject("target"));require(number(value,"version")==1L && sameScope(target,expected)) { "通知状态运行目标不匹配" }
        val delivery=text(value,"delivery");val state=text(value,"state");require(delivery in listOf("foreground-only","apns","fcm") && state in listOf("disabled","ready","pending","unavailable"))
        require(value.has("pushProvider")==value.has("deviceTokenSha256"))
        if(value.has("pushProvider")) {
            require(text(value,"pushProvider") in listOf("apns","fcm")&&text(value,"deviceTokenSha256").matches(Regex("[a-f0-9]{64}")))
        }
        if(delivery=="fcm")require(value.opt("pushProvider")=="fcm")
        return NativeTaskNotificationStatus(target,delivery,state,optional(value,"reason"),value.toString())
    }
    fun feed(value: JSONObject,expected: TargetScope): NativeTaskNotificationFeed {
        keys(value,setOf("events","cursor"),setOf("activeIds","status"));val values=value.getJSONArray("events");require(values.length()<=1000)
        val events=values.objects().map { notice(it,expected) };require(events.map { it.id }.distinct().size==events.size)
        val cursor=text(value,"cursor");require(cursor.isEmpty() || Rules.eventId(cursor))
        val active=if(value.has("activeIds"))strings(value.getJSONArray("activeIds")).also { require(it.size<=1000 && it.distinct().size==it.size && it.all(Rules::eventId)) }.toSet() else null
        return NativeTaskNotificationFeed(events,cursor,active,if(value.has("status"))status(value.getJSONObject("status"),expected) else null)
    }
}

/** Named domain surface. Transport and provider wire stay behind the registered adapter. */
interface AgentTaskNotifications {
    suspend fun supportsTaskPushRegistration(client:MobileClient):Boolean=false
    suspend fun registerTaskNotifications(client: MobileClient,registration: NativeTaskNotificationRegistration): NativeTaskNotificationStatus
    suspend fun updateTaskNotificationPreferences(client: MobileClient,deviceId: String,preferences: NativeTaskNotificationPreferences): NativeTaskNotificationStatus
    suspend fun taskNotificationEvents(client: MobileClient,deviceId: String,after: String): NativeTaskNotificationFeed
    suspend fun taskNotificationStatus(client: MobileClient,deviceId: String): NativeTaskNotificationStatus
    suspend fun unregisterTaskNotifications(client: MobileClient,deviceId: String)
}

/** Installation-local provider token; never serialized to an Intent or diagnostic. */
class NativeTaskPushRegistration(val token: String, val configuration: String) {
    init { require(token.length in 1..4096 && token.all { it.code in 33..126 }); require(chatNoticeHash(configuration)) }
    val fingerprint get() = NativeChatNotificationWire.hash(token)
    override fun toString() = "NativeTaskPushRegistration(redacted)"
}
