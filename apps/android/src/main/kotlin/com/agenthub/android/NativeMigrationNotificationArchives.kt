package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Immutable archive graph, intentionally disjoint from every P9 active record name. */
internal object NativeMigrationNotificationArchives {
    const val RECORD="migration-notification-archives-v1"
    const val PREFIX="migration-notification-original-v1:"
    const val MAX_INSTALLATIONS=16
    private val originals=linkedMapOf("foreground-v1" to NativeMigrationForeground.JOURNAL,"recovery-v2" to NativeMigrationForeground.RECOVERY,
        "authorizations-v1" to NativeMigrationForeground.AUTHORIZATIONS)
    enum class Original(val wire: String) { FOREGROUND_V1("foreground-v1"),RECOVERY_V2("recovery-v2"),AUTHORIZATIONS_V1("authorizations-v1") }
    class ArchivedOriginals internal constructor(val archiveId: String,val deviceId: String?,val identityFingerprint: String?,
        val missingAuthorizations: List<String>,private val originals: Map<String,String>) {
        val requiresOriginalInstallation get()=true
        /** Exact audit text; never pass authorization snapshots to a live P9 connection/identity loader. */
        fun readOnly(kind: Original): String?=originals[kind.wire]
        override fun toString()="ArchivedNotificationOriginals(redacted)"
    }
    private fun hash(raw: String)=NativeChatNotificationWire.hash(raw)
    private fun parse(raw: String,max: Int=128*1024)=raw.toByteArray(Charsets.UTF_8).let {require(it.size<=max);StrictMigrationJson.parse(it)}
    private fun name(id: String,kind: String)="$PREFIX$id:$kind"
    private fun descriptor(state: NativeMigrationForeground.ActiveState,records: Map<String,String>): JSONObject {
        val body=JSONObject().put("installation",state.context?:JSONObject.NULL).put("originalIdentitySha256",state.identitySha256?:JSONObject.NULL)
            .put("requiresOriginalInstallation",true).put("missingAuthorizations",JSONArray((state.references.keys-state.available.keys).sorted()))
        originals.forEach {(kind,record)->body.put(kind,records[record]?.let(::hash)?:JSONObject.NULL)}
        return body.put("archiveId",NativeMigrationRecoveryCanonical.hash(body))
    }
    private fun catalog(entries: Collection<JSONObject>)=NativeMigrationRecoveryCanonical.value(JSONObject().put("version",1)
        .put("installations",JSONArray(entries.sortedBy {it.getString("archiveId")})))
    private fun entries(records: Map<String,String>): Map<String,JSONObject> {
        val raw=records[RECORD]?:return emptyMap();val value=parse(raw)
        NativeChatNotificationWire.keys(value,setOf("version","installations"));require(value.get("version")==1)
        val rows=value.getJSONArray("installations");require(rows.length() in 1..MAX_INSTALLATIONS)
        return rows.objects().associateBy {entry->
            NativeChatNotificationWire.keys(entry,setOf("archiveId","installation","originalIdentitySha256","requiresOriginalInstallation","missingAuthorizations")+originals.keys)
            val id=entry.get("archiveId") as String;require(chatNoticeHash(id));val body=JSONObject(entry.toString()).apply {remove("archiveId")}
            require(NativeMigrationRecoveryCanonical.hash(body)==id&&entry.get("requiresOriginalInstallation")==true)
            id
        }.also {require(it.size==rows.length())}
    }
    fun legacyNames(raw: String): Set<String> = entries(mapOf(RECORD to raw)).flatMap {(id,row)->
        originals.keys.filter {row.get(it)!=JSONObject.NULL}.map {name(id,it)}
    }.toSet()
    /** Claimed names become inert portable audit material; active identity/authority names always reject. */
    fun validatePortable(records: Map<String,String>): Set<String> {
        require(NativeMigrationForeground.ACTIVE_NAMES.none {it in records}) {"Installation authority cannot be imported as active records"}
        val rows=entries(records);val claimed=mutableSetOf<String>();if(rows.isNotEmpty())claimed.add(RECORD)
        for((id,entry)in rows) {
            val aliases=linkedMapOf<String,String>()
            for((kind,record)in originals) {
                val digest=entry.get(kind)
                if(digest!=JSONObject.NULL) {
                    require(digest is String&&chatNoticeHash(digest));val key=name(id,kind);require(claimed.add(key))
                    require(hash(records.getValue(key))==digest);aliases[record]=key
                }
            }
            val raw=NativeMigrationValueMap(aliases.keys){record->records.getValue(aliases.getValue(record))}
            val context=if(entry.get("installation")==JSONObject.NULL)null else entry.getJSONObject("installation")
            val identity=if(entry.get("originalIdentitySha256")==JSONObject.NULL)null else entry.get("originalIdentitySha256") as String
            val state=NativeMigrationForeground.validateArchived(context,identity,raw);require(state.nonempty)
            val missing=entry.getJSONArray("missingAuthorizations");val expected=(state.references.keys-state.available.keys).sorted()
            require((0 until missing.length()).map {missing.get(it) as String}==expected)
            require(NativeMigrationRecoveryCanonical.value(descriptor(state,raw))==NativeMigrationRecoveryCanonical.value(entry))
        }
        require(records.keys.filter {it==RECORD||it.startsWith(PREFIX)}.all {it in claimed}) {"Orphan notification archive original"}
        return claimed
    }
    /** Exact raw journals/authority are renamed into the archive; no source private identity survives. */
    fun projectSource(records: Map<String,String>): Map<String,String> {
        val portable=NativeMigrationTextMap.copy(records).apply {forget(NativeMigrationForeground.ACTIVE_NAMES)};validatePortable(portable)
        val state=NativeMigrationForeground.validateActive(records);if(!state.nonempty)return portable
        val row=descriptor(state,records);val id=row.getString("archiveId");val previous=entries(portable).toMutableMap()
        previous[id]?.let {require(NativeMigrationRecoveryCanonical.value(it)==NativeMigrationRecoveryCanonical.value(row))}
        previous[id]=row;require(previous.size<=MAX_INSTALLATIONS)
        originals.forEach {(kind,record)->if(record in records)portable.alias(records,record,name(id,kind))}
        portable[RECORD]=catalog(previous.values);validatePortable(portable);return portable
    }
    /** Read selected local records safely, including archive descendants, without AtomicFile recovery. */
    fun captureDestination(storage: NativeSessionStorage): Map<String,String> = storage.snapshotTransaction {storage.operationTransaction {
        val first=storage.migrationReadTextFrames(NativeMigrationForeground.ACTIVE_NAMES+RECORD)
        val names=entries(first).flatMap {(id,entry)->originals.keys.filter {entry.get(it)!=JSONObject.NULL}.map {name(id,it)}}
        names.chunked(16).forEach {chunk->val next=storage.migrationReadTextFrames(chunk.toSet());chunk.filter {it in next}.forEach {first.alias(next,it,it)}}
        validatePortable(NativeMigrationTextMap.copy(first).apply {forget(NativeMigrationForeground.ACTIVE_NAMES)});NativeMigrationForeground.validateActive(first);first
    }}
    /** Local original identities/journals remain active only under their original installation. */
    fun mergeDestination(source: Map<String,String>,destination: Map<String,String>): Map<String,String> {
        validatePortable(source);validatePortable(NativeMigrationTextMap.copy(destination).apply {forget(NativeMigrationForeground.ACTIVE_NAMES)});NativeMigrationForeground.validateActive(destination)
        val result=NativeMigrationTextMap.copy(source);val all=entries(source).toMutableMap()
        for((id,row)in entries(destination)) {
            all[id]?.let {require(NativeMigrationRecoveryCanonical.value(it)==NativeMigrationRecoveryCanonical.value(row))};all[id]=row
            originals.keys.filter {row.get(it)!=JSONObject.NULL}.forEach {kind->val key=name(id,kind);result.alias(destination,key,key)}
        }
        require(all.size<=MAX_INSTALLATIONS);if(all.isNotEmpty())result[RECORD]=catalog(all.values)
        validatePortable(result);NativeMigrationForeground.ACTIVE_NAMES.filter {it in destination}.forEach {result.alias(destination,it,it)};return result
    }
    fun requiresBoundary(source: Map<String,String>,destination: Map<String,String>)=RECORD in source||RECORD in destination||NativeMigrationForeground.validateActive(destination).nonempty
    /** Parent reads this after activation before creating/rebinding any notification coordinator. */
    fun requiresExplicitSetup(storage: NativeSessionStorage): Boolean = storage.snapshotTransaction {storage.operationTransaction {
        val records=captureDestination(storage)
        RECORD in records||storage.migrationHasPublishedGeneration()&&NativeMigrationForeground.validateActive(records).nonempty
    }}
    fun readOnlyArchives(storage: NativeSessionStorage): List<ArchivedOriginals> {
        val records=captureDestination(storage)
        return entries(records).map {(id,row)->val context=row.optJSONObject("installation")
            ArchivedOriginals(id,context?.getString("deviceId"),context?.getString("fingerprint"),row.getJSONArray("missingAuthorizations").strings(),
                NativeMigrationValueMap(originals.keys.filter {kind->name(id,kind) in records}.toSet()){kind->records.getValue(name(id,kind))})
        }
    }
}

/** Parent mints only after disposing old workers and gating new automatic enrollment. No UI/IO in callback. */
internal class NativeMigrationNotificationActivationLease(storage: NativeSessionStorage,receipt: NativeMigrationCandidateStore.Receipt,
    private val quiescent: ()->Boolean) {
    private val root=storage.coordinationKey
    private val id=receipt.id;private val sha=receipt.sha256;private val count=receipt.recordCount
    fun requireCurrent(storage: NativeSessionStorage,receipt: NativeMigrationCandidateStore.Receipt) {
        check(storage.coordinationKey==root&&receipt.id==id&&receipt.sha256==sha&&receipt.recordCount==count&&quiescent()) {"Notification migration requires a current quiescent owner boundary"}
    }
}
