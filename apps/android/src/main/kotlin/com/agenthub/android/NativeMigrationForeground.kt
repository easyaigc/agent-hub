package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Closed P7 reader of frozen P9 journals. It cannot register, recover, revoke or mint an identity. */
internal object NativeMigrationForeground {
    const val IDENTITY = "native-chat-notification-identity-v1"
    const val JOURNAL = "native-chat-notification-foreground-v1"
    const val RECOVERY = "native-chat-notification-recovery-v2"
    const val AUTHORIZATIONS = "native-chat-notification-authorizations-v1"
    val ACTIVE_NAMES = setOf(IDENTITY,JOURNAL,RECOVERY,AUTHORIZATIONS)
    private data class Identity(val device: String, val publicKey: String,val fingerprint: String, val bound: Boolean)
    private data class Journal(val device: String, val fingerprint: String, val subscriptions: Int,
        val operations: List<String>, val outcomes: List<String>,val references: Map<String,NativeChatNoticeTarget>)
    class ActiveState internal constructor(val context: JSONObject?,val identitySha256: String?,val references: Map<String,NativeChatNoticeTarget>,
        val available: Map<String,NativeChatNoticeTarget>,val nonempty: Boolean) {
        override fun toString()="NotificationOriginalState(redacted)"
    }
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
        return Identity(device(text(v,"deviceId",128)),public,sha(public),bound)
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
        val references=mutableMapOf<String,NativeChatNoticeTarget>()
        fun reference(id: String,target: NativeChatNoticeTarget){require(references[id]==null||references[id]==target);references[id]=target}
        for(s in subscriptions.objects()) {
            exact(s,setOf("target","authorization","preferences","revoked","cursor","mutations","batch","outcomes"))
            val target=target(s.getJSONObject("target"));require(targets.add(target))
            val authorization=if(s.get("authorization")==JSONObject.NULL)null else text(s,"authorization").also {require(chatNoticeHash(it))}
            val pref=if(s.get("preferences")==JSONObject.NULL)null else preferences(s.getJSONObject("preferences"))
            val revoked=s.get("revoked") as Boolean;val position=cursor(s,"cursor")
            require(pref==null||authorization!=null);require(revoked||pref!=null&&authorization!=null)
            val mutations=s.getJSONArray("mutations");val outcomes=s.getJSONArray("outcomes");require(mutations.length()<=64&&outcomes.length()<=2048)
            val operations=mutations.objects().map(::mutation);require(operations.map {it.id}.distinct().size==operations.size&&operations.count {it.state=="unknown"}<=1)
            authorization?.let {reference(it,target)};mutations.objects().forEach {reference(it.getString("authorization"),target)}
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
        return Journal(device(text(v,"deviceId",128)),fingerprint,subscriptions.length(),operationIds.toList(),outcomeIds.toList(),references)
    }
    /** Validates current installation records without loading its identity or constructing command authority. */
    fun validateActive(records: Map<String,String>): ActiveState {
        val id=records[IDENTITY]?.let(::identity)
        require(id?.bound!=true||JOURNAL in records||RECOVERY in records) {"Bound notification journals missing"}
        val context=id?.let {JSONObject().put("deviceId",it.device).put("publicKey",it.publicKey).put("fingerprint",it.fingerprint)}
        return validateArchived(context,records[IDENTITY]?.let(::sha),records)
    }
    /** Public installation context is evidence only; no private key or active identity is reconstructed. */
    fun validateArchived(context: JSONObject?,identitySha256: String?,records: Map<String,String>): ActiveState {
        context?.let {v->exact(v,setOf("deviceId","publicKey","fingerprint"));device(text(v,"deviceId",128));val public=text(v,"publicKey",64)
            key(public).fill(0);require(text(v,"fingerprint")==sha(public))}
        require((context==null)==(identitySha256==null));identitySha256?.let {require(chatNoticeHash(it))}
        val v1=records[JOURNAL]?.let(::journal)
        val v2=records[RECOVERY]?.let {raw->objectValue(raw,4*1024*1024);NativeMigrationForegroundV2.decodeEncoded(raw)}
        require(v1==null&&v2==null||context!=null) {"Original notification identity missing"}
        if(v1!=null)require(context!!.getString("deviceId")==v1.device&&context.getString("fingerprint")==v1.fingerprint)
        if(v2!=null)require(context!!.getString("deviceId")==v2.getString("deviceId")&&context.getString("fingerprint")==v2.getString("identity"))
        val refs=v1?.references.orEmpty().toMutableMap()
        v2?.getJSONArray("targets")?.objects()?.forEach {row->val target=target(row.getJSONObject("target"))
            row.getJSONArray("operations").objects().forEach {op->val hash=op.getString("authorization");require(refs[hash]==null||refs[hash]==target);refs[hash]=target}
        }
        val available=records[AUTHORIZATIONS]?.let(NativeMigrationNotificationAuthorizations::validate).orEmpty()
        refs.forEach {(hash,target)->available[hash]?.let {require(it==target)}}
        return ActiveState(context,identitySha256,refs,available,
            (v1?.subscriptions?:0)>0||(v2?.getJSONArray("targets")?.length()?:0)>0||available.isNotEmpty())
    }
    private fun inspect(records: Map<String,String>): SourceAudit {
        val state=validateActive(records);val journalRaw=records[JOURNAL];val journal=journalRaw?.let(::journal)
        return SourceAudit(state.identitySha256,journalRaw?.let(::sha),journalRaw,journal?.operations.orEmpty(),journal?.outcomes.orEmpty(),state.nonempty)
    }
    /** IO caller gets exact immutable journal bytes, not a recovered identity or command port. */
    fun audit(storage: NativeSessionStorage): SourceAudit=storage.snapshotTransaction {storage.operationTransaction {
        inspect(storage.migrationReadTextRecords(ACTIVE_NAMES))
    }}
    fun projectSource(records: Map<String,String>): Map<String,String> {
        return NativeMigrationNotificationArchives.projectSource(records)
    }
    fun requirePortable(records: Map<String,String>){NativeMigrationNotificationArchives.validatePortable(records)}
    /** Destination originals remain local authority; the activation lease gates their publication/rebind. */
    fun preserveDestination(storage: NativeSessionStorage): Map<String,String> {
        return NativeMigrationNotificationArchives.captureDestination(storage)
    }
}
