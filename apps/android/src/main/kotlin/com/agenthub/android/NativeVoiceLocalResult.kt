package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NativeVoiceLocalResult(val actionId: String,val recordingId: String,val fingerprint: String,val audioSha256: String,
    val locale: String,val modelId: String,val modelSha256: String,val text: String,val createdAt: Long,val applied: Boolean=false,val destination: NativeVoiceDraftReceipt?=null) {
    fun json()=JSONObject().put("actionId",actionId).put("recordingId",recordingId).put("fingerprint",fingerprint).put("audioSha256",audioSha256)
        .put("locale",locale).put("modelId",modelId).put("modelSha256",modelSha256).put("text",text).put("createdAt",createdAt).put("applied",applied).put("destination",destination?.json() ?: JSONObject.NULL)
    fun validate(original: PendingVoiceRecording) {
        require(recordingId==original.id && fingerprint==NativeVoiceDiscardRequest(original).fingerprint && audioSha256==original.manifest?.sha256)
        require(applied==(destination!=null));destination?.validate(original,this)
    }
}
/** Original desktop rows/attempt flags/receipts are outside this port's write authority. */
internal interface NativeVoiceLocalResultPort {
    suspend fun pending(original: PendingVoiceRecording,locale: String): NativeVoiceLocalResult?
    suspend fun stage(original: PendingVoiceRecording,result: NativeVoiceLocalResult)
    suspend fun applied(original: PendingVoiceRecording,result: NativeVoiceLocalResult,destination: NativeVoiceDraftReceipt)
}

internal object NativeVoiceLocalResults {
    const val RECORD="voice-local-results-v1"
    private const val MAX_BYTES=1_048_576
    fun decode(raw: String): List<NativeVoiceLocalResult> {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        val value=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
        require(value.keys().asSequence().toSet()==setOf("version","results") && value.get("version") in setOf(1,2))
        val legacy=value.getInt("version")==1
        val rows=value.getJSONArray("results");require(rows.length()<=128)
        val results=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i)
            require(row.keys().asSequence().toSet()==setOf("actionId","recordingId","fingerprint","audioSha256","locale","modelId","modelSha256","text","createdAt","applied")+(if(legacy)emptySet()else setOf("destination")))
            fun string(key: String)=row.get(key) as String
            for(key in listOf("actionId","recordingId"))require(UUID.fromString(string(key)).toString()==string(key))
            for(key in listOf("fingerprint","audioSha256","modelSha256"))require(string(key).matches(Regex("[a-f0-9]{64}")))
            val locale=string("locale");val model=when(locale){"en-US"->"vosk-model-small-en-us-0.15";"zh-CN"->"vosk-model-small-cn-0.22";else->error("Unsupported saved voice locale")}
            require(string("modelId")==model && string("modelSha256")==when(locale){"en-US"->"30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";else->"3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"})
            val text=string("text");require(text.isNotBlank()&&text.length<=32_000&&!text.contains('\u0000'))
            val created=row.get("createdAt");require((created is Long||created is Int)&&(created as Number).toLong() in 0..9_007_199_254_740_991L)
            val applied=row.get("applied") as Boolean
            val receipt=if(legacy||row.get("destination")==JSONObject.NULL)null else NativeVoiceDraftReceipt.decode(row.getJSONObject("destination"))
            require(legacy||applied==(receipt!=null))
            NativeVoiceLocalResult(string("actionId"),string("recordingId"),string("fingerprint"),string("audioSha256"),locale,model,string("modelSha256"),text,(created as Number).toLong(),!legacy&&applied,receipt)
        }
        require(results.map {it.actionId}.distinct().size==results.size)
        require(results.filter {it.applied}.map {it.recordingId}.distinct().size==results.count {it.applied})
        return results
    }
    fun encode(results: List<NativeVoiceLocalResult>): String=JSONObject().put("version",2).put("results",JSONArray().also {a->results.forEach {a.put(it.json())}}).toString().also(::decode)
    fun read(storage: NativeSessionStorage)=storage.migrationReadTextRecords(setOf(RECORD))[RECORD]?.let(::decode).orEmpty()
    fun resolved(storage: NativeSessionStorage,original: PendingVoiceRecording): Boolean {
        val rows=read(storage).filter {it.recordingId==original.id};rows.forEach {it.validate(original)};return rows.any {it.applied}
    }
}

/** One encrypted atomic catalog. A staged result survives interruption; filling always requires a new explicit UI action. */
internal class NativeEncryptedVoiceLocalResultPort(private val storage: NativeSessionStorage): NativeVoiceLocalResultPort {
    private fun verifyOriginal(expected: PendingVoiceRecording): PendingVoiceRecording {
        val name="voice-recording:${expected.id}";val raw=storage.migrationReadTextRecords(setOf(name))[name]?:error("Original recording missing")
        val current=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)))
        require(NativeVoiceDiscardRequest(expected).matches(current)&&current.manifest!=null)
        require(current.id !in NativeVoiceDiscardCatalog.read(storage));return current
    }
    override suspend fun pending(original: PendingVoiceRecording,locale: String)=withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val current=verifyOriginal(original);val rows=NativeVoiceLocalResults.read(storage);rows.filter {it.recordingId==current.id}.forEach {it.validate(current)}
            require(rows.none {it.recordingId==current.id&&it.applied})
            rows.lastOrNull {it.recordingId==current.id&&it.locale==locale&&!it.applied}
        }
    }
    private fun publish(rows: List<NativeVoiceLocalResult>) {
        val raw=NativeVoiceLocalResults.encode(rows)
        // A failed write may still have committed. Read the same immutable intent before reporting failure.
        try {storage.write(NativeVoiceLocalResults.RECORD,raw)}catch(failure: Exception){if(storage.migrationReadTextRecords(setOf(NativeVoiceLocalResults.RECORD))[NativeVoiceLocalResults.RECORD]!=raw)throw failure}
        require(storage.migrationReadTextRecords(setOf(NativeVoiceLocalResults.RECORD))[NativeVoiceLocalResults.RECORD]==raw)
    }
    override suspend fun stage(original: PendingVoiceRecording,result: NativeVoiceLocalResult)=withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val current=verifyOriginal(original);result.validate(current);require(!result.applied)
            val rows=NativeVoiceLocalResults.read(storage);rows.filter {it.recordingId==current.id}.forEach {it.validate(current)}
            require(rows.none {it.recordingId==current.id&&it.applied})
            rows.singleOrNull {it.actionId==result.actionId}?.let {require(it==result);return@operationTransaction}
            publish(rows+result)
        }
    }
    override suspend fun applied(original: PendingVoiceRecording,result: NativeVoiceLocalResult,destination: NativeVoiceDraftReceipt)=withContext(Dispatchers.IO) {
        storage.operationTransaction {
            result.validate(verifyOriginal(original));destination.validate(original,result)
            val persisted=storage.migrationReadTextRecords(setOf("metadata"))["metadata"] ?: error("Durable destination metadata missing")
            require(voiceSHA(persisted.toByteArray(Charsets.UTF_8))==destination.metadataSha256)
            val metadata=StrictMigrationJson.parse(persisted.toByteArray(Charsets.UTF_8))
            require(metadata.getJSONObject("draftVersions").getString(destination.ownerKey)==destination.acceptedVersion)
            require(voiceSHA(metadata.getJSONObject("drafts").getString(destination.ownerKey).toByteArray(Charsets.UTF_8))==destination.textSha256)
            val rows=NativeVoiceLocalResults.read(storage)
            val saved=rows.single {it.actionId==result.actionId};require(saved.copy(applied=false,destination=null)==result.copy(applied=false,destination=null))
            require(rows.none {it.recordingId==original.id&&it.applied&&it.actionId!=result.actionId})
            publish(rows.map {if(it.actionId==result.actionId)it.copy(applied=true,destination=destination)else it})
        }
    }
}
