package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Portable, inert evidence only. No loader reads this as the live upload journal.
 * Never follows target/source IDs, copies binaries, opens auth or dispatches any operation.
 */
internal object NativeMigrationUploadArchives {
    const val RECORD="migration-upload-audit-v1"
    const val MAX_ENTRIES=8
    const val MAX_AUDIT_BYTES=4*1024*1024
    val SOURCE_NAMES=setOf(NativeUploadJournalCodec.RECORD,RECORD)
    private fun hash(raw:String)=ComputerFileCodec.hash(raw.toByteArray(Charsets.UTF_8))
    private fun entries(raw:String?):Map<String,String> {
        if(raw==null)return emptyMap()
        val buffer=Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(raw))
        require(buffer.remaining() in 1..MAX_AUDIT_BYTES)
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
        val value=try {StrictMigrationJson.parse(bytes)}finally {bytes.fill(0)}
        require(value.keys().asSequence().toSet()==setOf("version","entries")&&value.get("version")==1)
        val rows=value.getJSONArray("entries");require(rows.length() in 1..MAX_ENTRIES)
        val found=linkedMapOf<String,String>()
        for(row in rows.objects()) {
            require(row.keys().asSequence().toSet()==setOf("sha256","journal"))
            val digest=row.get("sha256") as String;val journal=row.get("journal") as String
            require(digest.matches(Regex("[a-f0-9]{64}"))&&hash(journal)==digest)
            NativeUploadJournalCodec.decode(journal)
            require(found.put(digest,journal)==null) // Even identical duplicate rows are not a second event.
        }
        return found
    }
    private fun catalog(values:Map<String,String>):String {
        require(values.size in 1..MAX_ENTRIES)
        val raw=JSONObject().put("version",1).put("entries",JSONArray(values.toSortedMap().map {(sha,journal)->
            JSONObject().put("sha256",sha).put("journal",journal)
        })).toString()
        entries(raw) // Check actual encoded bytes (including escaping), not an optimistic raw sum.
        return raw
    }
    fun validatePortable(records:Map<String,String>):Set<String> {
        require(NativeUploadJournalCodec.RECORD !in records) {"Live uploads cannot be portable authority"}
        val raw=records[RECORD]?:return emptySet()
        entries(raw)
        return setOf(RECORD) // No dynamic aliases / source references acquire migration ownership.
    }
    /** Copy-only projection. Source maps/frames and their original stored bytes remain untouched. */
    fun projectSource(records:Map<String,String>):Map<String,String> {
        val result=NativeMigrationTextMap.copy(records).apply {forget(setOf(NativeUploadJournalCodec.RECORD))}
        validatePortable(result)
        val raw=records[NativeUploadJournalCodec.RECORD]?:return result
        NativeUploadJournalCodec.decode(raw)
        val digest=hash(raw);val all=entries(result[RECORD]).toMutableMap()
        all[digest]?.let {require(it==raw);return result} // Keep even the existing audit envelope bytes exact.
        all[digest]=raw
        result[RECORD]=catalog(all) // Size/count failure is atomic with respect to the original map.
        validatePortable(result)
        return result
    }
    /** Retain destination audit/history on an explicit import; never reactivate its old live key. */
    fun mergeDestination(source:Map<String,String>,destination:Map<String,String>):Map<String,String> {
        validatePortable(source)
        val local=projectSource(destination)
        val all=entries(source[RECORD]).toMutableMap();var changed=false
        for((digest,raw) in entries(local[RECORD])) {
            val old=all[digest]
            if(old==null){all[digest]=raw;changed=true}else require(old==raw)
        }
        if(!changed)return source
        return NativeMigrationTextMap.copy(source).also {it[RECORD]=catalog(all);validatePortable(it)}
    }
    fun captureDestination(storage:NativeSessionStorage):Map<String,String> =
        storage.migrationReadTextFrames(SOURCE_NAMES).also {projectSource(it)}
}
