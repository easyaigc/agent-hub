package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Migration-owned policy v3. Production ledgers keep their existing strict v1 schemas. */
internal object NativeMigrationDeviceState {
    const val READING="mobile-reading-ledger-v1"
    const val NOTIFICATIONS="native-chat-notifications-v1"
    const val AUDIT="migration-device-audit-v1"
    private val noticeKinds=setOf("completed","approval","question","plan","paused","interrupted","failed","uncertain","connection","stopped")
    private fun targetShape(value: JSONObject) {
        val keys=value.keys().asSequence().toSet();require(keys.containsAll(setOf("agentId","hostId","workspaceId","runtimeId"))&&keys.all {it in setOf("agentId","hostId","workspaceId","runtimeId","account")})
        value.optJSONObject("account")?.let {exact(it,setOf("authority","subject"))}
    }
    private fun uuid(value: String)=require(UUID.fromString(value).toString()==value)
    private fun objectValue(raw: String)=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
    private fun exact(value: JSONObject,keys: Set<String>)=require(value.keys().asSequence().toSet()==keys)
    private fun identity(receipt: NativeMigrationCandidateStore.Receipt,kind: String)=UUID.nameUUIDFromBytes(
        ("android-migration-device-policy-v2\u0000"+receipt.id+"\u0000"+kind).toByteArray(Charsets.UTF_8)).toString()
    private fun sizeUpper(raw: String,quoted: Boolean): Long {
        var size=if(quoted)2L else 0L
        for(c in raw)size+=when {
            quoted&&(c=='"'||c=='\\'||c=='/') ->2
            quoted&&(c<' '||c=='\u2028'||c=='\u2029'||Character.isSurrogate(c)) ->6
            c.code<=127 ->1
            c.code<=2047 ->2
            else ->3
        }
        return size
    }
    private fun reading(raw: String,clients: List<MobileClient>?=null) {
        val value=objectValue(raw);NativeReadingLedger.validateEncoded(raw)
        exact(value,setOf("version","revision","scopes","rows"));require(value.get("version")==1)
        val scopes=value.getJSONArray("scopes").strings();require(scopes.distinct().size==scopes.size)
        if(clients!=null)require(scopes.all {key->clients.any {client->client.scope?.let(ThreadReadingIdentity::scopeKey)==key}})
        value.getJSONArray("rows").objects().forEach {row->
            require(row.keys().asSequence().all {it in setOf("target","threadId","latest","read","pendingReader","replyUnread","notices")})
            targetShape(row.getJSONObject("target"))
            val target=HostReadCodec.target(row.getJSONObject("target"))!!;require(target.agentId=="codex")
            ThreadReadingIdentity.key(target,row.getString("threadId"))
            for(field in listOf("latest","read"))row.optJSONObject(field)?.let {exact(it,setOf("revision","messageId","contentVersion"))}
            row.getJSONObject("notices").let {notices->notices.keys().forEach {id->
                val notice=notices.getJSONObject(id);require(notice.keys().asSequence().all {it in setOf("id","kind","runId","requestId","operationId","seen")})
                require(notice.getString("kind") in noticeKinds-"connection")
            }}
        }
    }
    private fun notifications(raw: String,clients: List<MobileClient>?=null) {
        val value=objectValue(raw);NativeChatNotificationStore.validateEncoded(raw)
        exact(value,setOf("version","preferences","leases","receipts"));require(value.get("version")==1)
        val prefs=value.getJSONObject("preferences")
        exact(prefs,setOf("version","revision","generation","enabled","kinds","excludedHosts","foregroundBanner","soundEnabled","badgeEnabled","showThreadTitle","timeSensitive","sound","categorySounds"));require(prefs.get("version")==1)
        val sound=Regex("default|none|[a-zA-Z0-9_-]{1,72}\\.caf")
        require(prefs.getString("sound").matches(sound)&&prefs.getJSONArray("kinds").strings().all {it in noticeKinds})
        prefs.getJSONObject("categorySounds").let {sounds->sounds.keys().forEach {require(it in noticeKinds&&sounds.getString(it).matches(sound))}}
        fun leaseShape(lease: JSONObject) {exact(lease,setOf("target","credentialEpoch","registrationGeneration","preferenceRevision"));targetShape(lease.getJSONObject("target"))}
        value.getJSONArray("leases").objects().forEach(::leaseShape)
        value.getJSONArray("receipts").objects().forEach {receipt->
            exact(receipt,setOf("event","lease","ticket","expiresAt","state","consumed"));leaseShape(receipt.getJSONObject("lease"))
            val event=receipt.getJSONObject("event");require(event.keys().asSequence().all {it in setOf("version","id","target","kind","occurredAt","threadId","runId","requestId","operationId","threadTitle","taskSummary","readTarget","readPosition")})
            require(event.get("version")==1&&event.getString("kind") in noticeKinds);targetShape(event.getJSONObject("target"))
            event.optJSONObject("readTarget")?.let(::targetShape);event.optJSONObject("readPosition")?.let {exact(it,setOf("revision","messageId","contentVersion"))}
        }
        val preferences=NativeChatNotificationCodec.preferences(value.getJSONObject("preferences"))
        value.getJSONArray("leases").objects().forEach {require(NativeChatNotificationCodec.lease(it).preferenceRevision==preferences.revision)}
        if(clients!=null)value.getJSONArray("leases").objects().forEach {row->
            val target=NativeChatNotificationCodec.lease(row).target
            require(clients.any {client->client.scope?.let {it.agentId=="codex"&&NativeChatNoticeTarget.from(it)==target}==true})
        }
    }
    private fun cross(reading: String?,notifications: String?) {
        if(reading==null||notifications==null)return
        val rows=objectValue(reading).getJSONArray("rows").objects().associateBy {row->
            ThreadReadingIdentity.key(HostReadCodec.target(row.getJSONObject("target"))!!,row.getString("threadId"))
        }
        objectValue(notifications).getJSONArray("receipts").objects().forEach {row->
            val event=NativeChatNotificationCodec.receipt(row).event
            val thread=event.threadId?:return@forEach
            val target=HostReadCodec.target(NativeChatNotificationCodec.target(event.target))!!
            val ledger=rows[ThreadReadingIdentity.key(target,thread)]?:return@forEach
            ledger.getJSONObject("notices").optJSONObject(event.id)?.let {notice->
                require(notice.getString("kind")==event.kind.wire)
                require(notice.optional("runId")==event.runId&&notice.optional("requestId")==event.requestId&&notice.optional("operationId")==event.operationId)
            }
            event.readPosition?.let {position->for(field in listOf("latest","read"))ledger.optJSONObject(field)?.let {saved->
                val value=ThreadReadPosition.decode(saved)
                if(value.revision==position.revision)require(value.messageId==position.messageId&&value.contentVersion==position.contentVersion)
            }}
        }
    }
    private fun audit(raw: String): JSONObject {
        val value=objectValue(raw);exact(value,setOf("version","policyVersion","entries"));require(value.get("version")==1&&value.get("policyVersion") in setOf(2,3))
        val entries=value.getJSONArray("entries").objects();require(entries.size in 1..16)
        val ids=mutableSetOf<String>()
        entries.forEach {entry->
            val keys=entry.keys().asSequence().toSet();require(keys.containsAll(setOf("candidateId","archiveSha256"))&&keys.all {it in setOf("candidateId","archiveSha256","reading","notifications")})
            val id=entry.getString("candidateId");uuid(id);require(ids.add(id)&&entry.getString("archiveSha256").matches(Regex("[a-f0-9]{64}")))
            require(entry.has("reading")||entry.has("notifications"))
            if(entry.has("reading"))reading(entry.get("reading") as String)
            if(entry.has("notifications"))notifications(entry.get("notifications") as String)
            cross(entry.optional("reading"),entry.optional("notifications"))
        }
        return value
    }
    internal fun legacyNotificationTombstones(raw: String): Set<String> = audit(raw).let {
        require(it.get("policyVersion")==2) { "Missing policy3 migration notification index" }
        NativeMigrationNotificationTombstones.fromAudit(it)
    }
    fun validate(records: Map<String,String>,clients: List<MobileClient>): Set<String> {
        records[READING]?.let {reading(it,clients)};records[NOTIFICATIONS]?.let {notifications(it,clients)}
        NativeMigrationNotificationTombstones.validate(records,records[AUDIT]?.let(::audit))
        cross(records[READING],records[NOTIFICATIONS])
        return setOf(READING,NOTIFICATIONS,AUDIT,NativeMigrationNotificationTombstones.RECORD).intersect(records.keys)
    }
    /** Original authority stays only in an immutable audit; active data cannot use old readers/tickets. */
    fun prepare(receipt: NativeMigrationCandidateStore.Receipt,original: Map<String,String>,prepared: MutableMap<String,String>) {
        if(READING !in original&&NOTIFICATIONS !in original)return
        // Bound the additional quoted audit before serializing a second copy of source text.
        val auditBound=(original[AUDIT]?.let {sizeUpper(it,false)}?:128L)+
            (original[READING]?.let {sizeUpper(it,true)}?:0L)+(original[NOTIFICATIONS]?.let {sizeUpper(it,true)}?:0L)+512
        require(auditBound<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val entries=original[AUDIT]?.let(::audit)?.getJSONArray("entries")?:JSONArray()
        require(entries.length()<16)
        val entry=JSONObject().put("candidateId",receipt.id).put("archiveSha256",receipt.sha256)
        original[READING]?.let {raw->
            entry.put("reading",raw)
            val value=objectValue(raw);value.put("revision",Math.addExact(readingInteger(value,"revision"),1));value.put("scopes",JSONArray())
            value.getJSONArray("rows").objects().forEach {it.remove("pendingReader")}
            val next=value.toString();NativeReadingLedger.validateEncoded(next);prepared[READING]=next
        }
        original[NOTIFICATIONS]?.let {raw->
            entry.put("notifications",raw)
            val value=objectValue(raw);val preferences=NativeChatNotificationCodec.preferences(value.getJSONObject("preferences"))
            val nextPreferences=preferences.copy(enabled=false,revision=identity(receipt,"preferences"),generation=Math.addExact(preferences.generation,1))
            value.put("preferences",NativeChatNotificationCodec.preferences(nextPreferences));value.put("leases",JSONArray())
            // Exact originals stay in the audit. Dedup belongs to a separate migration record,
            // never permanent active receipts that could exhaust all2048 normal claim slots.
            value.put("receipts",JSONArray())
            val next=value.toString();NativeChatNotificationStore.validateEncoded(next);prepared[NOTIFICATIONS]=next
        }
        entries.put(entry)
        val raw=JSONObject().put("version",1).put("policyVersion",3).put("entries",entries).toString()
        require(raw.toByteArray(Charsets.UTF_8).size<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        prepared[NativeMigrationNotificationTombstones.RECORD]=NativeMigrationNotificationTombstones.encode(NativeMigrationNotificationTombstones.fromAudit(audit(raw)))
        prepared[AUDIT]=raw
    }
}
