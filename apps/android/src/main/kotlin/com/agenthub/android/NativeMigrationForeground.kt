package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Closed P7 reader of frozen P9 160b51ec v1. It cannot register, recover, revoke or mint an identity. */
internal object NativeMigrationForeground {
    const val IDENTITY = "native-chat-notification-identity-v1"
    const val JOURNAL = "native-chat-notification-foreground-v1"
    private data class Identity(val device: String, val fingerprint: String, val bound: Boolean)
    private data class Journal(val device: String, val fingerprint: String, val subscriptions: Int,
        val operations: List<String>, val outcomes: List<String>)
    /** Original source journal stays immutable under its original encryption; this view has no writer. */
    class SourceAudit internal constructor(val identitySha256: String?, val journalSha256: String?,
        private val originalJournal: String?, val operationIds: List<String>, val outcomeIds: List<String>,
        val requiresOriginalInstallation: Boolean) {
        fun readOnlyJournal(): String? = originalJournal
        override fun toString() = "ForegroundSourceAudit(redacted)"
    }
    class OriginalInstallationRequired(val audit: SourceAudit) : IllegalStateException(
        "前台通知原始操作仍需原安装身份与授权，资料已保留；当前迁移版本不能恢复或重放。")
    private fun exact(v: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys=v.keys().asSequence().toSet();require(keys.containsAll(required)&&(keys-required-optional).isEmpty())
    }
    private fun text(v: JSONObject,key: String,max: Int=256)=(v.get(key) as String).also {require(it.length in 1..max&&it.none {c->c.code<32})}
    private fun optional(v: JSONObject,key: String)=if(v.has(key))text(v,key)else null
    private fun integer(v: JSONObject,key: String): Long {
        val n=v.get(key);require(n is Int||n is Long);return (n as Number).toLong().also {require(it in 0..9_007_199_254_740_991L)}
    }
    private fun device(value: String)=value.also {require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))&&it !in setOf("__proto__","prototype","constructor"))}
    private fun uuid(value: String)=value.also {require(UUID.fromString(it).toString()==it)}
    private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun canonical(value: Any): String=when(value) {
        is JSONObject->value.keys().asSequence().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is String->JSONObject.quote(value)
        is Boolean,is Number->value.toString()
        JSONObject.NULL->"null"
        else->error("Invalid frozen foreground value")
    }
    private val kinds=setOf("completed","approval","question","plan","paused","interrupted","failed","uncertain","connection","stopped")
    private fun target(value: JSONObject): NativeChatNoticeTarget {
        exact(value,setOf("agentId","hostId","workspaceId","runtimeId"),setOf("account"))
        value.optJSONObject("account")?.let {exact(it,setOf("authority","subject"))}
        return NativeChatNotificationCodec.target(value)
    }
    private fun preferences(value: JSONObject): NativeChatNotificationPreferences {
        exact(value,setOf("version","revision","generation","enabled","kinds","excludedHosts","foregroundBanner","soundEnabled","badgeEnabled","showThreadTitle","timeSensitive","sound","categorySounds"))
        require(integer(value,"version")==1L&&value.getJSONArray("kinds").strings().all {it in kinds})
        require(value.getJSONObject("categorySounds").keys().asSequence().all {it in kinds})
        val sound=Regex("default|none|[a-zA-Z0-9_-]{1,72}\\.caf")
        require(text(value,"sound").matches(sound));value.getJSONObject("categorySounds").let {sounds->sounds.keys().forEach {require((sounds.get(it) as String).matches(sound))}}
        return NativeChatNotificationCodec.preferences(value)
    }
    private fun event(value: JSONObject): NativeChatNotificationEvent {
        exact(value,setOf("version","id","target","kind","occurredAt"),setOf("threadId","runId","requestId","operationId","threadTitle","taskSummary","readTarget","readPosition"))
        require(integer(value,"version")==1L&&text(value,"kind") in kinds);target(value.getJSONObject("target"))
        value.optJSONObject("readTarget")?.let(::target)
        value.optJSONObject("readPosition")?.let {exact(it,setOf("revision","messageId","contentVersion"))}
        return NativeChatNotificationCodec.event(value)
    }
    private fun preferenceHash(value: NativeChatNotificationPreferences)=sha(canonical(NativeChatNotificationCodec.preferences(value)))
    private fun key(raw: String): ByteArray {
        val bytes=Base64.getDecoder().decode(raw)
        try {require(bytes.size==32&&Base64.getEncoder().encodeToString(bytes)==raw);return bytes}
        catch(failure: Throwable){bytes.fill(0);throw failure}
    }
    private fun objectValue(raw: String,max: Int): JSONObject {
        val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=max);return StrictMigrationJson.parse(bytes)
    }
    private fun identity(raw: String): Identity {
        val v=objectValue(raw,2048);exact(v,setOf("version","deviceId","publicKey","privateKey","journalBound"));require(integer(v,"version")==1L)
        val bound=v.get("journalBound") as Boolean;val public=text(v,"publicKey",64)
        val secret=key(text(v,"privateKey",64));var publicBytes: ByteArray?=null
        try {publicBytes=key(public);require(X25519PrivateKeyParameters(secret,0).generatePublicKey().encoded.contentEquals(publicBytes))}
        finally {secret.fill(0);publicBytes?.fill(0)}
        return Identity(device(text(v,"deviceId",128)),sha(public),bound)
    }
    private fun cursor(v: JSONObject,key: String)=(v.get(key) as String).also {require(it.isEmpty()||chatNoticeHash(it))}
    private fun ids(v: JSONObject,key: String): List<String> {
        val a=v.getJSONArray(key);require(a.length()<=2048)
        return (0 until a.length()).map {a.get(it) as String}.also {require(it.all(::chatNoticeHash)&&it.distinct().size==it.size)}
    }
    private data class Mutation(val id: String,val state: String)
    private fun mutation(v: JSONObject): Mutation {
        exact(v,setOf("id","kind","authorization","preferences","state","at","fingerprint"),setOf("responseState","responseDelivery"))
        val kind=text(v,"kind");require(kind in setOf("register","preferences","unregister"))
        val pref=if(v.get("preferences")==JSONObject.NULL)null else preferences(v.getJSONObject("preferences"))
        require((kind=="unregister")== (pref==null))
        val authorization=text(v,"authorization");require(chatNoticeHash(authorization));integer(v,"at")
        require(text(v,"fingerprint")==sha(listOf(kind,authorization,pref?.let(::preferenceHash).orEmpty()).joinToString("\u0000")))
        val state=text(v,"state");require(state in setOf("unknown","confirmed"))
        val response=optional(v,"responseState");val delivery=optional(v,"responseDelivery")
        require(response==null||response in setOf("disabled","ready","pending","unavailable"));require(delivery==null||delivery in setOf("foreground-only","apns"))
        require((state=="confirmed")== (response!=null&&delivery!=null))
        if(state=="unknown")require(response==null&&delivery==null)
        return Mutation(uuid(text(v,"id")),state)
    }
    private fun journal(raw: String): Journal {
        val v=objectValue(raw,4*1024*1024);exact(v,setOf("version","deviceId","identity","subscriptions"));require(integer(v,"version")==1L)
        val fingerprint=text(v,"identity");require(chatNoticeHash(fingerprint));val subscriptions=v.getJSONArray("subscriptions");require(subscriptions.length()<=64)
        val targets=mutableSetOf<NativeChatNoticeTarget>();val operationIds=mutableListOf<String>();val outcomeIds=mutableListOf<String>()
        for(s in subscriptions.objects()) {
            exact(s,setOf("target","authorization","preferences","revoked","cursor","mutations","batch","outcomes"))
            val target=target(s.getJSONObject("target"));require(targets.add(target))
            val authorization=if(s.get("authorization")==JSONObject.NULL)null else text(s,"authorization").also {require(chatNoticeHash(it))}
            val pref=if(s.get("preferences")==JSONObject.NULL)null else preferences(s.getJSONObject("preferences"))
            val revoked=s.get("revoked") as Boolean;val position=cursor(s,"cursor")
            require(pref==null||authorization!=null);require(revoked||pref!=null&&authorization!=null)
            val mutations=s.getJSONArray("mutations");val outcomes=s.getJSONArray("outcomes");require(mutations.length()<=64&&outcomes.length()<=2048)
            val operations=mutations.objects().map(::mutation);require(operations.map {it.id}.distinct().size==operations.size&&operations.count {it.state=="unknown"}<=1)
            operationIds.addAll(operations.map {it.id});require(operationIds.size<=256)
            val events=outcomes.objects().map {o->
                exact(o,setOf("event","fingerprint","state","at"));integer(o,"at")
                val event=event(o.getJSONObject("event"));require(event.target==target)
                require(text(o,"fingerprint")==sha(canonical(NativeChatNotificationCodec.event(event))))
                require(text(o,"state") in setOf("pending","processing","suppressed","system-unavailable","submitted-to-system","unknown-no-replay",
                    "unknown-interrupted-no-replay","stale-target-or-generation","expired-or-future","duplicate-expired-or-read"));event
            }
            require(events.map {it.id}.distinct().size==events.size);outcomeIds.addAll(events.map {it.id});require(outcomeIds.size<=2048)
            if(s.get("batch")!=JSONObject.NULL) {
                val b=s.getJSONObject("batch");exact(b,setOf("id","before","after","revision","authorization","ids","activeIds"))
                uuid(text(b,"id"));val before=cursor(b,"before");val after=cursor(b,"after");val batchIds=ids(b,"ids");ids(b,"activeIds")
                require(chatNoticeHash(text(b,"authorization"))&&after==(batchIds.lastOrNull()?:before))
                require(operations.none {it.state=="unknown"}&&!revoked&&text(b,"authorization")==authorization&&text(b,"revision")==pref?.revision)
                require(before==position&&batchIds.all {id->events.any {it.id==id}})
            }
        }
        return Journal(device(text(v,"deviceId",128)),fingerprint,subscriptions.length(),operationIds.toList(),outcomeIds.toList())
    }
    private fun inspect(records: Map<String,String>): SourceAudit {
        val identityRaw=records[IDENTITY];val journalRaw=records[JOURNAL]
        val identity=identityRaw?.let(::identity);val journal=journalRaw?.let(::journal)
        require(journal==null||identity!=null) {"Foreground identity missing; original journal preserved"}
        require(identity?.bound!=true||journal!=null) {"Bound foreground journal missing; original identity preserved"}
        if(identity!=null&&journal!=null)require(identity.device==journal.device&&identity.fingerprint==journal.fingerprint)
        return SourceAudit(identityRaw?.let(::sha),journalRaw?.let(::sha),journalRaw,journal?.operations.orEmpty(),journal?.outcomes.orEmpty(),journal?.subscriptions?.let {it>0}==true)
    }
    /** IO caller gets exact immutable journal bytes, not a recovered identity or command port. */
    fun audit(storage: NativeSessionStorage): SourceAudit=storage.snapshotTransaction {storage.operationTransaction {
        inspect(storage.migrationReadTextRecords(setOf(IDENTITY,JOURNAL)))
    }}
    fun projectSource(records: Map<String,String>): Map<String,String> {
        val audit=inspect(records);if(audit.requiresOriginalInstallation)throw OriginalInstallationRequired(audit)
        return records-IDENTITY-JOURNAL
    }
    fun requirePortable(records: Map<String,String>)=require(IDENTITY !in records&&JOURNAL !in records) {"Installation notification identity/journal cannot be imported"}
    /** Only the destination's own validated unbound/empty pair can survive the atomic generation. */
    fun preserveDestination(storage: NativeSessionStorage): Map<String,String> {
        val records=storage.migrationReadTextRecords(setOf(IDENTITY,JOURNAL))
        val audit=inspect(records);if(audit.requiresOriginalInstallation)throw OriginalInstallationRequired(audit)
        return records
    }
}
