package com.agenthub.android

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.ReplyRecoveryRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** An authenticated candidate is never an active installation or permission to repeat a command. */
internal class NativeMigrationCandidateStore(private val context: Context, private val parent: NativeSessionStorage) {
    private val applicationId = context.packageName
    private val storage = parent.child(context, "migration-candidates")
    internal data class Receipt(val id: String, val sha256: String, val recordCount: Int)
    internal class InvalidCandidate : Exception("Invalid migration candidate; active data was not changed")
    /** Cold UI recovery names the existing candidate. Validation does not activate or replay it. */
    fun currentReceipt(): Receipt? {
        val stream=NativeMigrationStreamCandidate(context,parent)
        return parent.operationTransaction {storage.operationTransaction candidate@{
            val raw=storage.migrationReadTextRecords(setOf(SLOT))[SLOT]?:return@candidate null
            val saved=objectValue(raw);val id=saved.getString("id");require(UUID.fromString(id).toString()==id)
            val digest=saved.getString("sha256");require(digest.matches(Regex("[a-f0-9]{64}")))
            val count=if(saved.get("version")==1) {
                val bytes=legacyPayload(saved)
                try {require(sha(bytes)==digest);validateArchive(bytes).size}finally{bytes.fill(0)}
            }else {require(saved.get("version") in setOf(2,3,4));saved.get("records") as Int}
            val receipt=Receipt(id,digest,count);require(count in 1..1024)
            if(saved.get("version") in setOf(3,4))stream.read(receipt){}else readArchive(receipt)
            receipt
        }}
    }
    fun stageFile(candidateId: String, envelope: java.io.File, password: String, replacing: Receipt? = null, checkpoint: (Int) -> Unit = {}): Receipt = try {
        if (NativeMigrationStreamCrypto.isEnvelope(envelope)) NativeMigrationStreamCandidate(context, parent).stage(candidateId, envelope, password, replacing, checkpoint)
        else {
            require(envelope.length() in 1..MAX_ENVELOPE_BYTES.toLong())
            // Existing portable v1 remains bounded and read-compatible through the same file entry.
            val bytes=envelope.readBytes();try { stage(candidateId,bytes,password,replacing,checkpoint) } finally { bytes.fill(0) }
        }
    } catch (_: Exception) { throw InvalidCandidate() }
    companion object {
        // Existing archive quota remains until file-based streaming covers low-memory devices.
        // Chunked local candidate publication does not enlarge the whole-archive heap budget.
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 12 * 1024 * 1024
        internal const val CANDIDATE_CHUNK_BYTES = 256 * 1024
        private const val SLOT = "candidate-v1"
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun exact(value: JSONObject, keys: Set<String>) = require(value.keys().asSequence().toSet() == keys)
        private fun objectValue(raw: String) = StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
        private fun legacyPayload(saved: JSONObject): ByteArray {
            exact(saved,setOf("version","id","sha256","payload"));val encoded=saved.getString("payload")
            require(encoded.length<=((MAX_PAYLOAD_BYTES+2)/3)*4);return NativeMigrationRecords.bytes(encoded)
        }
    }

    /** Small authenticated header is the sole publication; encrypted chunks are immutable per generation. */
    fun stage(candidateId: String, envelope: ByteArray, password: String, replacing: Receipt? = null,checkpoint: (Int)->Unit={}): Receipt = try {
        require(UUID.fromString(candidateId).toString() == candidateId)
        require(envelope.size <= MAX_ENVELOPE_BYTES)
        val clear = NativeMigrationCrypto.open(envelope, password)
        try {
            val records = validateArchive(clear)
            val receipt = Receipt(candidateId, sha(clear), records.size)
            // Acquire child handles before the candidate lock; activation locks parent then child.
            val streamBackend = if (replacing != null) NativeMigrationStreamCandidate(context,parent) else null
            storage.operationTransaction {
                val prior = storage.migrationReadTextRecords(setOf(SLOT))[SLOT]?.let(::objectValue)
                if (prior?.getString("id") == candidateId) {
                    require(prior.getString("sha256") == receipt.sha256)
                    readArchive(receipt) // Exact original result; no ciphertext rewrite.
                } else {
                    require(if (prior == null) replacing == null else replacing != null &&
                        prior.getString("id") == replacing.id && prior.getString("sha256") == replacing.sha256)
                    if (replacing != null) {
                        if (prior!!.get("version") in setOf(3,4)) streamBackend!!.read(replacing) {} else readArchive(replacing)
                    }
                    val generation=UUID.randomUUID().toString();val chunks=JSONArray();val created=mutableListOf<String>()
                    var published=false
                    try {
                        var offset=0
                        while(offset<clear.size) {
                            val count=minOf(CANDIDATE_CHUNK_BYTES,clear.size-offset)
                            val bytes=clear.copyOfRange(offset,offset+count);val name=chunkName(generation,chunks.length())
                            created.add(name)
                            try {
                                storage.writeBinaryBytes(name,bytes,CANDIDATE_CHUNK_BYTES)
                                val observed=storage.readBinary(name,count,CANDIDATE_CHUNK_BYTES)
                                try {require(MessageDigest.isEqual(bytes,observed))}finally {observed.fill(0)}
                                chunks.put(JSONObject().put("size",count).put("sha256",sha(bytes)))
                            }finally {bytes.fill(0)}
                            offset+=count;checkpoint(chunks.length())
                        }
                        val sealed=JSONObject().put("version",2).put("id",receipt.id).put("sha256",receipt.sha256)
                            .put("size",clear.size).put("records",receipt.recordCount).put("generation",generation).put("chunks",chunks)
                        checkpoint(0);storage.write(SLOT,sealed.toString());published=true
                    }finally {
                        // An acknowledgement may fail after publication. Keep chunks if the pointer is
                        // ours or cannot be read; never destroy a possibly committed candidate.
                        if(!published) {
                            val observed=runCatching {storage.migrationReadTextRecords(setOf(SLOT))[SLOT]?.let(::objectValue)?.optString("generation")}
                            if(observed.isSuccess&&observed.getOrNull()!=generation)created.forEach(storage::remove)
                        }
                    }
                    // Previous/unreferenced generations are preserved for explicit recovery/retirement.
                }
            }
            receipt
        } finally { clear.fill(0) }
    } catch (_: Exception) { throw InvalidCandidate() }

    private fun chunkName(generation: String,index: Int)="migration-candidate:$generation:$index"

    /** A cold reader names the original ID/digest and reads all chunks under one pointer lock. */
    fun read(receipt: Receipt): Map<String,String> = readArchive(receipt).also {require(it.binary.isEmpty())}.text

    internal fun readArchive(receipt: Receipt): NativeMigrationRecords = storage.operationTransaction {
        require(UUID.fromString(receipt.id).toString() == receipt.id && receipt.sha256.matches(Regex("[a-f0-9]{64}"))&&receipt.recordCount in 1..1024)
        val saved = objectValue(storage.migrationReadTextRecords(setOf(SLOT))[SLOT] ?: error("Migration candidate unavailable"))
        require(saved.getString("id") == receipt.id && saved.getString("sha256") == receipt.sha256)
        val clear=when(saved.get("version")) {
            1 -> {
                legacyPayload(saved)
            }
            2 -> {
                exact(saved,setOf("version","id","sha256","size","records","generation","chunks"))
                val size=saved.get("size") as Int;require(size in 1..MAX_PAYLOAD_BYTES&&saved.get("records")==receipt.recordCount)
                val generation=saved.getString("generation");require(UUID.fromString(generation).toString()==generation)
                val chunks=saved.getJSONArray("chunks");require(chunks.length()==(size+CANDIDATE_CHUNK_BYTES-1)/CANDIDATE_CHUNK_BYTES)
                val bytes=ByteArray(size)
                try {
                    var offset=0
                    for(index in 0 until chunks.length()) {
                        val row=chunks.getJSONObject(index);exact(row,setOf("size","sha256"))
                        val count=minOf(CANDIDATE_CHUNK_BYTES,size-offset);require(row.get("size")==count)
                        val digest=row.getString("sha256");require(digest.matches(Regex("[a-f0-9]{64}")))
                        val chunk=storage.migrationReadBinary(chunkName(generation,index),count,CANDIDATE_CHUNK_BYTES)
                        try {require(sha(chunk)==digest);System.arraycopy(chunk,0,bytes,offset,count)}finally {chunk.fill(0)}
                        offset+=count
                    }
                    bytes
                }catch(failure: Throwable){bytes.fill(0);throw failure}
            }
            else -> error("Unsupported candidate version")
        }
        try { require(sha(clear) == receipt.sha256); validateArchive(clear).also { require(it.size == receipt.recordCount) } }
        finally { clear.fill(0) }
    }

    internal fun validate(clear: ByteArray): Map<String,String> = validateArchive(clear).also {require(it.binary.isEmpty())}.text
    internal fun validateArchive(clear: ByteArray)=NativeMigrationArchiveValidation.archive(clear,applicationId)
    internal fun validateStreamRecords(archive: NativeMigrationStreamRecords,verifySoundBytes: Boolean=true)=
        NativeMigrationArchiveValidation.stream(archive,verifySoundBytes)

}

/** Strict bounded local archive parser; strings that hold original records are checked again at their own boundary. */
internal object StrictMigrationJson {
    fun parse(bytes: ByteArray): JSONObject = value(bytes) as JSONObject
    fun value(bytes: ByteArray): Any {
        require(bytes.size <= NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES * 2)
        var nodes = 0
        JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder())).use { reader ->
            reader.isLenient = false
            fun value(depth: Int): Any {
                require(depth <= 32 && ++nodes <= 200_000)
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val result = JSONObject(); val names = mutableSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName(); require(names.add(name))
                            Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(name))
                            result.put(name, value(depth + 1))
                        }
                        reader.endObject(); result
                    }
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); val result = JSONArray(); while (reader.hasNext()) result.put(value(depth + 1)); reader.endArray(); result }
                    JsonToken.STRING -> reader.nextString().also { Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(it)) }
                    JsonToken.NUMBER -> {
                        val text = reader.nextString(); val integer = text.toLongOrNull()
                        if (integer != null) { if (integer in Int.MIN_VALUE..Int.MAX_VALUE) integer.toInt() else integer }
                        else text.toDouble().also { require(it.isFinite()) }
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                    else -> error("Invalid migration JSON")
                }
            }
            val result = value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT); return result
        }
    }
}
