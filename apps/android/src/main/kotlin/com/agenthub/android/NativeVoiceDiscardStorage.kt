package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NativeVoiceDiscardEvidence(val kind: String,val payloadId: String?=null,val size: Int=0,val sha256: String?=null) {
    fun json()=JSONObject().put("kind",kind).apply {if(kind=="raw")put("payloadId",payloadId).put("sizeBytes",size).put("sha256",sha256)}
}
internal data class NativeVoiceDiscardMarker(val id: String,val fingerprint: String,val manifestKnown: Boolean,val auditId: String,
    val originalSha256: String,val discardedAt: Long,val evidence: NativeVoiceDiscardEvidence) {
    val auditName get()="voice-discard-original-v1:$id:$auditId"
    val rawName get()="voice-discard-raw-v1:$id:${evidence.payloadId}"
    fun json()=JSONObject().put("recordingId",id).put("fingerprint",fingerprint).put("manifestKnown",manifestKnown)
        .put("auditId",auditId).put("originalSha256",originalSha256).put("discardedAt",discardedAt).put("evidence",evidence.json())
}

/** The catalog is the sole terminal publication. Originals/audits/raw snapshots never authorize a command. */
internal object NativeVoiceDiscardCatalog {
    const val RECORD="voice-discard-catalog-v1"
    const val RAW_PREFIX="voice-discard-raw-v1:"
    const val AUDIT_PREFIX="voice-discard-original-v1:"
    const val MAX_ENTRIES=256
    const val MAX_BYTES=256*1024
    private fun exact(v: JSONObject,keys: Set<String>)=require(v.keys().asSequence().toSet()==keys)
    private fun uuid(value: String)=require(UUID.fromString(value).toString()==value)
    fun decode(raw: String): Map<String,NativeVoiceDiscardMarker> {
        val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_BYTES)
        val v=StrictMigrationJson.parse(bytes);exact(v,setOf("version","entries"));require(v.get("version")==1)
        val entries=v.getJSONArray("entries");require(entries.length()<=MAX_ENTRIES)
        return entries.objects().map {row->
            exact(row,setOf("recordingId","fingerprint","manifestKnown","auditId","originalSha256","discardedAt","evidence"))
            val id=row.get("recordingId") as String;uuid(id);val audit=row.get("auditId") as String;uuid(audit)
            val fingerprint=row.get("fingerprint") as String;val original=row.get("originalSha256") as String
            require(chatNoticeHash(fingerprint)&&chatNoticeHash(original));val known=row.get("manifestKnown") as Boolean
            val at=row.get("discardedAt");require(at is Int||at is Long);require((at as Number).toLong() in 0..9_007_199_254_740_991L)
            val e=row.getJSONObject("evidence");val kind=e.get("kind") as String;require(kind in setOf("sealed","absent","empty","raw"))
            val evidence=if(kind=="raw") {
                exact(e,setOf("kind","payloadId","sizeBytes","sha256"));val payload=e.get("payloadId") as String;uuid(payload)
                val size=e.get("sizeBytes") as Int;require(size in 1..com.agenthub.core.VoiceTranscriptionRules.maxBytes)
                val sha=e.get("sha256") as String;require(chatNoticeHash(sha));NativeVoiceDiscardEvidence(kind,payload,size,sha)
            }else {exact(e,setOf("kind"));NativeVoiceDiscardEvidence(kind)}
            NativeVoiceDiscardMarker(id,fingerprint,known,audit,original,(at as Number).toLong(),evidence)
        }.also {require(it.map {m->m.id}.distinct().size==it.size)}.associateBy {it.id}
    }
    fun encode(entries: Collection<NativeVoiceDiscardMarker>): String {
        require(entries.size<=MAX_ENTRIES)
        return JSONObject().put("version",1).put("entries",JSONArray(entries.sortedBy {it.id}.map {it.json()})).toString().also {decode(it)}
    }
    fun read(storage: NativeSessionStorage): Map<String,NativeVoiceDiscardMarker> = storage.migrationReadTextRecords(setOf(RECORD))[RECORD]?.let(::decode).orEmpty()
    fun original(marker: NativeVoiceDiscardMarker,raw: String,current: PendingVoiceRecording): PendingVoiceRecording {
        require(voiceSHA(raw.toByteArray(Charsets.UTF_8))==marker.originalSha256)
        val snapshot=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)))
        require(snapshot.id==marker.id&&snapshot.manifest==current.manifest&&NativeVoiceDiscardRequest(snapshot).matches(current))
        require(!marker.manifestKnown||snapshot.manifest!=null)
        val request=NativeVoiceDiscardRequest(if(marker.manifestKnown)snapshot else snapshot.copy(manifest=null))
        require(request.fingerprint==marker.fingerprint)
        require((snapshot.manifest!=null)==(marker.evidence.kind=="sealed"))
        if(snapshot.manifest==null)require(!snapshot.prepareAttempted&&!snapshot.audioAttempted)
        return snapshot
    }
    fun validate(storage: NativeSessionStorage,marker: NativeVoiceDiscardMarker,current: PendingVoiceRecording) {
        val raw=storage.migrationReadTextRecords(setOf(marker.auditName))[marker.auditName]?:error("Discard original audit missing")
        original(marker,raw,current)
    }
}

/** Both ordinary and question controllers bind this same backend through the owner's narrow port. */
internal class NativeEncryptedVoiceDiscardPort(private val store: VoiceRecordingStore,private val checkpoint: (Int)->Unit={}): NativeVoiceDiscardPort {
    override suspend fun discard(request: NativeVoiceDiscardRequest)=store.discardLocal(request,checkpoint)
}
