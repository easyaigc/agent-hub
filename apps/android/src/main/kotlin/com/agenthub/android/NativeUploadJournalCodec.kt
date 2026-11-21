package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** The one live-journal decoder, shared by normal restore and inert migration audits.
 * Validates descriptions only. Never follows a source reference or opens a credential.
 */
internal object NativeUploadJournalCodec {
    const val RECORD="native-upload-jobs-v1"
    const val MAX_BYTES=1024*1024
    const val MAX_JOBS=32
    fun decode(raw:String):List<NativeUploadRow> {
        val buffer=Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(raw))
        require(buffer.remaining() in 1..MAX_BYTES)
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
        val value=try {StrictMigrationJson.parse(bytes)}finally {bytes.fill(0)}
        require(value.keys().asSequence().toSet()==setOf("version","jobs")&&value.get("version")==1)
        val jobs=value.getJSONArray("jobs");require(jobs.length()<=MAX_JOBS)
        return jobs.objects().map(NativeUploadRow::decode).also {rows->require(rows.map {it.key}.distinct().size==rows.size)}
    }
    fun encode(rows:Collection<NativeUploadRow>):String {
        require(rows.size<=MAX_JOBS)
        val raw=JSONObject().put("version",1).put("jobs",JSONArray(rows.map {it.json()})).toString()
        decode(raw) // Producers cannot persist a weaker contract than readers/audits accept.
        return raw
    }
    /** Exact normalized result descriptors only; no opaque auth/command fields in a journal.
     * Original wire-result validation remains in NativeUploadReceipt / validateNativeUploadResult.
     * A pre-existing journal outside this closed set fails intact; export never strips its bytes.
     */
    fun resultFields(manifest:NativeUploadManifest,value:JSONObject) {
        val keys=when(manifest.purpose) {
            "attachment"->setOf("id","hostId","threadId","name","mediaType","sizeBytes","sha256")
            "creation-attachment"->setOf("id","hostId","clientOperationId","sizeBytes","sha256")
            "file-transfer"->setOf("id","hostId","name","mediaType","sizeBytes","sha256","phase","destinationPath","createdAt")
            else->error("Unsupported upload purpose")
        }
        val actual=value.keys().asSequence().toSet()
        // Codex Host includes this public alias in completed attachment receipts.
        // Retain its original bytes, while keeping auth/command fields outside the journal.
        val resourceAlias=manifest.purpose=="attachment"&&actual==keys+"resourceId"
        val creationDescription=manifest.purpose=="creation-attachment"&&actual==keys+setOf("name","mediaType")
        require(actual==keys||resourceAlias||creationDescription)
        if(resourceAlias)require(value.get("resourceId") is String&&value.getString("resourceId")==value.getString("id"))
        if(creationDescription)require(value.get("name") is String&&value.getString("name")==manifest.file.name&&
            value.get("mediaType") is String&&value.getString("mediaType")==manifest.file.mediaType)
    }
}
