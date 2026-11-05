package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import org.json.JSONObject

/** One decoder for the live F59 journal and a future inert migration reader.
 * Decoding never repairs a phase, establishes a retry proof, or writes a record.
 * The exact binding JSON remains the source of truth, including historical account fields.
 */
internal object NativeComputerFileJournal {
    const val MAX_ROWS=32
    const val MAX_CHUNKS=4096
    const val MAX_JOURNAL_BYTES=900_000
    data class Decoded(val target: ComputerFileTarget,val binding: JSONObject,val files: List<PendingComputerFile>)

    fun decode(name: String,raw: String,expected: ComputerFileTarget?): Decoded {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_JOURNAL_BYTES)
        val value=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
        ComputerFileCodec.exact(value,setOf("schema","target","files"))
        require(value.get("schema")=="agent-hub/android-computer-files-v1")
        val bound=value.getJSONObject("target");ComputerFileCodec.exact(bound,setOf("scope","certificate"))
        val scope=requireNotNull(HostReadCodec.target(CodexCommandCodec.validate(bound.getJSONObject("scope"),"target")))
        val certificate=if(bound.get("certificate")==JSONObject.NULL)null else (bound.get("certificate") as String).also {
            require(it.isNotEmpty()&&it.length<=8192)
        }
        val target=ComputerFileTarget(scope,certificate)
        require(name=="computer-files-v1:"+target.key) {"保存的文件不属于当前电脑目标。"}
        if(expected!=null)require(RuntimeIdentity.matches(scope,expected.scope)&&certificate==expected.certificate) {
            "保存的文件不属于当前电脑目标。"
        }
        val list=value.getJSONArray("files");require(list.length()<=MAX_ROWS)
        val result=list.objects().map {v->
            ComputerFileCodec.exact(v,setOf("id","name","mediaType","sizeBytes","sha256","chunks","phase","error","receipt"))
            val id=v.get("id") as String;ComputerFileCodec.uuid(id)
            val chunks=v.getJSONArray("chunks");require(chunks.length()<=MAX_CHUNKS)
            val parsed=chunks.objects().map {chunk->
                ComputerFileCodec.exact(chunk,setOf("bytes","sha256"))
                ComputerFileChunk((chunk.get("bytes") as Int).also {require(it in 1..NativeComputerFileStore.CHUNK_BYTES)},
                    (chunk.get("sha256") as String).also {require(it.matches(Regex("[a-f0-9]{64}")))})
            }
            val row=PendingComputerFile(id,v.get("name") as String,v.get("mediaType") as String,ComputerFileCodec.integer(v.get("sizeBytes")),
                if(v.get("sha256")==JSONObject.NULL)null else v.get("sha256") as String,parsed,ComputerFilePhase.valueOf(v.get("phase") as String),
                if(v.get("error")==JSONObject.NULL)null else ComputerFileCodec.text(v.get("error"),8192).also {require(it in ComputerFileCopy.errors)},
                if(v.get("receipt")==JSONObject.NULL)null else ComputerFileCodec.receipt(v.getJSONObject("receipt"),scope.hostId))
            ComputerFileCodec.validName(row.name)
            require(ComputerFileCodec.validMediaType(row.mediaType)&&row.sizeBytes in 1..ComputerFileCodec.MAX_FILE_BYTES)
            require(row.chunks.sumOf {it.bytes.toLong()}<=row.sizeBytes)
            if(row.sha256!=null) {row.manifest(scope.hostId);require(row.chunks.sumOf {it.bytes.toLong()}==row.sizeBytes)}
            if(row.phase in setOf(ComputerFilePhase.READY,ComputerFilePhase.UNKNOWN,ComputerFilePhase.COMPLETED))require(row.sha256!=null)
            require(row.receipt==null||row.phase==ComputerFilePhase.COMPLETED)
            require((row.phase==ComputerFilePhase.COMPLETED)==(row.receipt?.phase=="completed"))
            row.receipt?.validate(row.manifest(scope.hostId));row
        }
        require(result.map {it.id}.distinct().size==result.size&&result.sumOf {it.chunks.size}<=MAX_CHUNKS&&
            result.sumOf {it.sizeBytes}<=NativeComputerFileStore.MAX_TOTAL_BYTES)
        return Decoded(target,JSONObject(bound.toString()),result)
    }
}
