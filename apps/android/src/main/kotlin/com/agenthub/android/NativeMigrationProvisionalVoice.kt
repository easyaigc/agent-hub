package com.agenthub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Exact unfinished originals, outside the active voice index. This archive has no execution interface. */
internal object NativeMigrationProvisionalVoice {
    const val RECORD="migration-provisional-voice-v1"
    const val PREFIX="migration-provisional-raw-v1:"
    const val MAX_RAW_BYTES=9_600_044
    private const val MAX_ENTRIES=128
    data class Original(val id: String,val index: String,val row: String,val kind: String,val size: Int,val sha256: String) {
        fun json()=JSONObject().put("id",id).put("index",index).put("row",row).put("kind",kind).put("size",size).put("sha256",sha256)
    }
    data class Projection(val records: NativeMigrationStreamRecords,val unchanged: ()->Unit)
    internal fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun exact(v: JSONObject,keys: Set<String>)=require(v.keys().asSequence().toSet()==keys)
    private fun index(raw: String): List<String> {
        val a=StrictMigrationJson.value(raw.toByteArray(Charsets.UTF_8)) as JSONArray
        require(a.length()<=32)
        return (0 until a.length()).map {a.get(it) as String}.also {ids->
            require(ids.distinct().size==ids.size);ids.forEach {require(UUID.fromString(it).toString()==it)}
        }
    }
    fun liveIds(records: Map<String,String>): List<String> {
        val markers=records[NativeVoiceDiscardCatalog.RECORD]?.let(NativeVoiceDiscardCatalog::decode).orEmpty()
        return index(records["voice-index-v1"]?:"[]").filter {id->
            val row=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(records.getValue("voice-recording:$id").toByteArray(Charsets.UTF_8)))
            row.manifest==null&&id !in markers
        }
    }
    private fun decode(records: Map<String,String>,clients: List<MobileClient>): List<Original> {
        val raw=records[RECORD]?:return emptyList()
        val root=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));exact(root,setOf("version","originals"));require(root.get("version")==1)
        val rows=root.getJSONArray("originals");require(rows.length() in 1..MAX_ENTRIES)
        val active=index(records["voice-index-v1"]?:"[]").toSet()
        return (0 until rows.length()).map {n->
            val v=rows.getJSONObject(n);exact(v,setOf("id","index","row","kind","size","sha256"))
            val id=v.get("id") as String;require(UUID.fromString(id).toString()==id&&id !in active&&"voice-recording:$id" !in records)
            val originalIndex=v.get("index") as String;require(id in index(originalIndex))
            val row=v.get("row") as String;val body=StrictMigrationJson.parse(row.toByteArray(Charsets.UTF_8))
            val original=NativeMigrationVoice.original(body,clients)
            require(original.id==id&&original.manifest==null&&!original.migrationReadOnly&&!original.prepareAttempted&&!original.audioAttempted&&!body.has("phase"))
            val kind=v.get("kind") as String;val size=v.get("size") as Int;val digest=v.get("sha256") as String
            require(digest.matches(Regex("[a-f0-9]{64}")))
            require(if(kind=="raw")size in 1..MAX_RAW_BYTES else kind in setOf("absent","empty")&&size==0&&digest==sha(byteArrayOf()))
            Original(id,originalIndex,row,kind,size,digest)
        }.also {require(it.map {r->r.id}.distinct().size==it.size)}
    }
    fun validate(records: Map<String,String>,binary: Map<String,NativeMigrationBlob>,clients: List<MobileClient>): Set<String> {
        val originals=decode(records,clients);val expected=originals.filter {it.kind=="raw"}.associateBy {PREFIX+it.id}
        require(binary.keys==expected.keys)
        for((name,original)in expected) {val blob=binary.getValue(name);require(blob.size==original.size&&blob.sha256==original.sha256)}
        return expected.keys
    }
    fun descriptors(binary: Map<String,String>)=binary.mapValues {(_,encoded)->
        val bytes=NativeMigrationRecords.bytes(encoded)
        try {val probe=NativeMigrationProbe();probe.write(bytes);NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){error("Descriptor only")}}
        finally{bytes.fill(0)}
    }
    private fun clients(records: Map<String,String>)=StrictMigrationJson.parse(records.getValue("metadata").toByteArray(Charsets.UTF_8)).getJSONArray("clients").objects().map(::decodeClient)
    /** Full inventory is frozen before publication and checked again after envelope production. */
    fun project(context: Context,storage: NativeSessionStorage,source: NativeMigrationStreamRecords,owner: NativeMigrationOwnerLease?): Projection {
        fun writerFrozen(){val held=checkNotNull(owner);check(held.storage===storage);held.requireRawWriters()}
        val clientRows=clients(source.text);val existing=decode(source.text,clientRows)
        validate(source.text,source.binary.filterKeys {it.startsWith(PREFIX)},clientRows)
        val active=index(source.text["voice-index-v1"]?:"[]")
        val markers=source.text[NativeVoiceDiscardCatalog.RECORD]?.let(NativeVoiceDiscardCatalog::decode).orEmpty()
        val results=source.text[NativeVoiceLocalResults.RECORD]?.let(NativeVoiceLocalResults::decode).orEmpty()
        val known=(active+markers.keys+results.map {it.recordingId}).toSet()
        val live=liveIds(source.text);if(live.isNotEmpty())writerFrozen()
        val files=NativeMigrationProvisionalFiles(context,storage,known,owner)
        val text=NativeMigrationTextMap.copy(source.text);val binary=LinkedHashMap(source.binary)
        val added=live.map {id->
            val raw=source.text.getValue("voice-recording:$id");val body=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));val original=NativeMigrationVoice.original(body,clientRows)
            require(original.manifest==null&&!original.migrationReadOnly&&!original.prepareAttempted&&!original.audioAttempted&&!body.has("phase")&&"voice-audio:$id" !in binary)
            val file=files.original(id)
            file.blob?.let {require(binary.put(PREFIX+id,it)==null)}
            Original(id,source.text.getValue("voice-index-v1"),raw,file.kind,file.size,file.sha256)
        }
        // Leftover raw files for already sealed originals must equal their authenticated manifest.
        for(id in files.ids-live.toSet()) {
            val row=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(source.text.getValue("voice-recording:$id").toByteArray(Charsets.UTF_8)))
            val evidence=files.original(id)
            if(row.manifest!=null)require(evidence.kind=="raw"&&row.manifest.sizeBytes==evidence.size&&row.manifest.sha256==evidence.sha256)
            else {
                val marker=markers.getValue(id);require(marker.evidence.kind==evidence.kind&&marker.evidence.size==evidence.size&&(evidence.kind!="raw"||marker.evidence.sha256==evidence.sha256))
            }
        }
        if(added.isNotEmpty()) {
            require(existing.size+added.size<=MAX_ENTRIES&&(existing+added).map {it.id}.distinct().size==existing.size+added.size)
            live.forEach {text.remove("voice-recording:$it")}
            text["voice-index-v1"]=JSONArray(active.filterNot {it in live}).toString()
            text[RECORD]=JSONObject().put("version",1).put("originals",JSONArray((existing+added).sortedBy {it.id}.map {it.json()})).toString()
        }
        val records=NativeMigrationStreamRecords(text,binary,source.version)
        validate(records.text,records.binary.filterKeys {it.startsWith(PREFIX)},clientRows)
        return Projection(records) {if(live.isNotEmpty())writerFrozen();files.unchanged()}
    }
    fun read(storage: NativeSessionStorage): List<Original> = storage.operationTransaction {
        val names=setOf(RECORD,"metadata","voice-index-v1");val records=storage.migrationReadTextRecords(names)
        decode(records,clients(records))
    }
    /** A caller can copy preserved bytes, never obtain a PendingVoiceRecording for an old recorder. */
    fun copy(storage: NativeSessionStorage,original: Original,output: OutputStream)=storage.operationTransaction {
        require(original in read(storage));require(original.kind=="raw")
        val probe=NativeMigrationProbe(output);storage.migrationCopyBinaryTo(PREFIX+original.id,original.size,probe)
        require(probe.size==original.size.toLong()&&probe.sha256()==original.sha256)
    }
}
