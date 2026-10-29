package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Closed original voice graph. Unsealed originals are portable only behind a validated no-offer terminal. */
internal object NativeMigrationVoice {
    private fun uuid(value: String){require(UUID.fromString(value).toString()==value)}
    private fun bounded(value: JSONObject,key: String,maximum: Int,nullable: Boolean=false) {
        val item=value.get(key)
        require(nullable&&item==JSONObject.NULL||item is String&&item.length<=maximum)
    }
    fun validate(values: Map<String,Any>,binary: Map<String,String>,clients: List<MobileClient>,rawRecords: Map<String,String>): Set<String> =
        validateStreams(values, binary.mapValues { (_, encoded) ->
            val bytes = NativeMigrationRecords.bytes(encoded)
            try { val probe = NativeMigrationProbe(); probe.write(bytes); NativeMigrationBlob(bytes.size, probe.sha256(), probe.prefix()) { error("Legacy validation descriptor") } }
            finally { bytes.fill(0) }
        }, clients, rawRecords)
    fun validateStreams(values: Map<String,Any>,binary: Map<String,NativeMigrationBlob>,clients: List<MobileClient>,rawRecords: Map<String,String>): Set<String> {
        val claimed=mutableSetOf<String>();val expectedBinary=mutableSetOf<String>()
        val index=values["voice-index-v1"]?.let {it as JSONArray}
        val markers=rawRecords[NativeVoiceDiscardCatalog.RECORD]?.let(NativeVoiceDiscardCatalog::decode).orEmpty()
        if(NativeVoiceDiscardCatalog.RECORD in values)claimed.add(NativeVoiceDiscardCatalog.RECORD)
        val localResults=rawRecords[NativeVoiceLocalResults.RECORD]?.let(NativeVoiceLocalResults::decode).orEmpty()
        if(NativeVoiceLocalResults.RECORD in values)claimed.add(NativeVoiceLocalResults.RECORD)
        val active=if(index==null)emptyList()else {
            require(index.length()<=32);claimed.add("voice-index-v1")
            (0 until index.length()).map {index.get(it) as String}.also {ids->require(ids.distinct().size==ids.size)}
        }
        for(id in (active+markers.keys+localResults.map {it.recordingId}).distinct()) {
                uuid(id);val name="voice-recording:$id";require(claimed.add(name));val row=values[name] as JSONObject
                val current=NativeVoiceRecordingRecord.decode(row)
                localResults.filter {it.recordingId==id}.forEach {it.validate(current)}
                val marker=markers[id]
                if(marker!=null) {
                    val original=rawRecords[marker.auditName]?:error("Discard original audit missing")
                    NativeVoiceDiscardCatalog.original(marker,original,current);require(claimed.add(marker.auditName))
                }
                val required=setOf("version","id","scope","certificate","draftKey","draftId","threadId","originalText","createdAt","manifest","prepareAttempted","audioAttempted")
                val optional=setOf("audioStorage","audio","phase","expiresAt","migrationReadOnly")
                val keys=row.keys().asSequence().toSet();require(keys.containsAll(required)&&keys.all {it in required||it in optional})
                require(row.get("version")==1&&row.get("id")==id)
                for(key in listOf("prepareAttempted","audioAttempted","migrationReadOnly"))if(row.has(key))require(row.get(key) is Boolean)
                require(row.get("createdAt") is Int||row.get("createdAt") is Long);require(row.getLong("createdAt")>=0)
                bounded(row,"certificate",8192,true);bounded(row,"threadId",256,true);bounded(row,"draftKey",8192);bounded(row,"draftId",2048);bounded(row,"originalText",100_000)
                val scope=AISharingScope.decode(row.getJSONObject("scope"));require(scope.model=="codex-dictation")
                val client=clients.single {it.id==scope.target.hostId&&RuntimeIdentity.matches(it.scope,scope.target)}
                require(row.optional("certificate")==client.codexDirect?.fingerprint&&NativeVoiceDestinationKeys.matches(client.operationTargets(row.optional("threadId")).toSet(),row.getString("draftKey")))
                if(current.manifest==null) {
                    require(marker!=null&&!current.prepareAttempted&&!current.audioAttempted&&!row.has("phase")&&!row.has("audio")&&!row.has("audioStorage"))
                    if(marker.evidence.kind=="raw") {
                        val key=marker.rawName;require(expectedBinary.add(key));val blob=binary.getValue(key)
                        require(blob.size==marker.evidence.size&&blob.sha256==marker.evidence.sha256)
                    }else require(marker.evidence.kind in setOf("absent","empty"))
                    continue
                }
                val manifest=current.manifest
                require(com.agenthub.core.VoiceTranscriptionRules.validateManifest(manifest.json().toString()))
                require(manifest.operationId==id&&manifest.hostId==client.id&&manifest.threadId==row.optional("threadId")&&manifest.draftId==row.getString("draftId"))
                require(row.has("phase")==row.has("expiresAt"))
                if(row.has("phase")) {
                    require(row.get("phase") in listOf("awaiting-audio","transcribing","completed","failed","outcome-unknown"))
                    bounded(row,"expiresAt",256);java.time.Instant.parse(row.getString("expiresAt"))
                }
                val blob=if(row.has("audioStorage")) {
                    require(row.get("audioStorage")=="binary-chunks-v1"&&!row.has("audio"))
                    val key="voice-audio:$id";require(expectedBinary.add(key));binary.getValue(key)
                }else {
                    require(row.get("audio") is String);val bytes=NativeMigrationRecords.bytes(row.getString("audio"))
                    try { val probe=NativeMigrationProbe();probe.write(bytes);NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){error("Inline validation descriptor")} }
                    finally {bytes.fill(0)}
                }
                val pcm=blob.size-44
                require(manifest.sizeBytes==blob.size&&manifest.sha256==blob.sha256)
                require(pcm in 2..9_600_000&&pcm%2==0&&blob.prefix.contentEquals(WavePCM.header(pcm)))
                require(manifest.durationMs==(pcm*1000L/32000).toInt().coerceAtLeast(1))
        }
        require(values.keys.filter {it=="voice-index-v1"||it.startsWith("voice-recording:")||it==NativeVoiceDiscardCatalog.RECORD||it==NativeVoiceLocalResults.RECORD||it.startsWith(NativeVoiceDiscardCatalog.AUDIT_PREFIX)}.all {it in claimed})
        require(binary.keys==expectedBinary)
        return claimed
    }
}
