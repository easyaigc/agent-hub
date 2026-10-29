package com.agenthub.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/** File-backed v3/v4 payloads share one candidate slot and immutable receipt protocol. */
internal class NativeMigrationStreamCandidate(private val context: Context, parent: NativeSessionStorage) {
    private val storage = parent.child(context, "migration-candidates")
    private val legacy = NativeMigrationCandidateStore(context, parent)
    private fun saved() = storage.migrationReadTextRecords(setOf("candidate-v1"))["candidate-v1"]?.let { StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8)) }
    private fun name(generation: String, index: Int) = "migration-stream:$generation:$index"
    private fun matches(value: JSONObject, receipt: NativeMigrationCandidateStore.Receipt) =
        value.getString("id") == receipt.id && value.getString("sha256") == receipt.sha256
    fun isStream(receipt: NativeMigrationCandidateStore.Receipt) = storage.operationTransaction {
        val value = requireNotNull(saved()); require(matches(value, receipt)); value.get("version") in setOf(3,4)
    }
    fun <T> read(receipt: NativeMigrationCandidateStore.Receipt, consume: (NativeMigrationStreamRecords) -> T): T = storage.operationTransaction {
        val value = requireNotNull(saved()); require(matches(value, receipt) && value.get("version") in setOf(3,4))
        require(value.keys().asSequence().toSet() == setOf("version", "id", "sha256", "records", "generation", "header"))
        require(UUID.fromString(receipt.id).toString() == receipt.id && receipt.sha256.matches(Regex("[a-f0-9]{64}")))
        val generation = value.getString("generation"); require(UUID.fromString(generation).toString() == generation)
        val header=value.getJSONObject("header");require(header.get("version")==value.get("version"))
        val names=(if(value.get("version")==4)header.getJSONObject("records").keys().asSequence().sorted().toList()else emptyList())+
            header.getJSONObject("binaries").keys().asSequence().sorted().toList()
        val records = NativeMigrationStreamArchive.header(value.getJSONObject("header"), context.packageName) { logical, size ->
            { output -> storage.migrationCopyBinaryTo(name(generation, names.indexOf(logical)), size, output) }
        }
        require(records.size == receipt.recordCount && value.get("records") == receipt.recordCount)
        legacy.validateStreamRecords(records)
        val digest = MessageDigest.getInstance("SHA-256")
        NativeMigrationStreamArchive.write(java.security.DigestOutputStream(nullOutputStreamCompat(), digest), records, context.packageName)
        require(digest.digest().joinToString("") { "%02x".format(it) } == receipt.sha256)
        consume(records)
    }
    fun stage(id: String, envelope: File, password: String, replacing: NativeMigrationCandidateStore.Receipt?, checkpoint: (Int) -> Unit): NativeMigrationCandidateStore.Receipt = try {
        require(UUID.fromString(id).toString() == id && envelope.length() in 1..NativeMigrationCrypto.MAX_FILE_BYTES.toLong())
        storage.operationTransaction {
            val prior = saved(); val sameId = prior?.getString("id") == id
            if (!sameId) {
                require(if (prior == null) replacing == null else replacing != null && matches(prior, replacing))
                if (replacing != null) {
                    if (prior!!.get("version") in setOf(3,4)) read(replacing) {} else legacy.readArchive(replacing)
                }
            }
            val generation = UUID.randomUUID().toString(); val created = mutableListOf<String>(); var published = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val records = envelope.inputStream().buffered().use { source -> NativeMigrationStreamCrypto.read(source, password) { clear ->
                    val input = DigestInputStream(clear, digest); val header = NativeMigrationStreamArchive.readHeader(input, context.packageName)
                    // v4 values arrive as authenticated frames, never Strings in the header.
                    if(header.version==3)legacy.validateStreamRecords(header,verifySoundBytes=false)
                    header.frames().entries.forEachIndexed { index, (_, blob) ->
                        val local = name(generation, index); created.add(local)
                        storage.writeBinaryStream(local, blob.size) { output -> NativeMigrationStreamArchive.transfer(input, blob, output) }
                        val check = NativeMigrationProbe(); storage.migrationCopyBinaryTo(local, blob.size, check)
                        require(check.size == blob.size.toLong() && check.sha256() == blob.sha256 && check.prefix().contentEquals(blob.prefix))
                        checkpoint(index + 1)
                    }
                    require(input.read() == -1); header
                } }
                val names=records.frames().keys.toList()
                val local=NativeMigrationStreamArchive.header(records.header(context.packageName),context.packageName) { logical,size ->
                    {output->storage.migrationCopyBinaryTo(name(generation,names.indexOf(logical)),size,output)}
                }
                // Strict UTF-8/closed graph and actual sound bytes validate before the sole slot write.
                legacy.validateStreamRecords(local)
                val receipt = NativeMigrationCandidateStore.Receipt(id, digest.digest().joinToString("") { "%02x".format(it) }, records.size)
                if (sameId) {
                    require(prior!!.getString("sha256") == receipt.sha256 && prior.get("version") in setOf(3,4))
                    read(receipt) {}; receipt
                } else {
                    val value = JSONObject().put("version", records.version).put("id", id).put("sha256", receipt.sha256).put("records", records.size)
                        .put("generation", generation).put("header", records.header(context.packageName))
                    checkpoint(0); storage.write("candidate-v1", value.toString()); published = true; receipt
                }
            } finally {
                if (!published) {
                    val observed = runCatching { saved()?.optString("generation") }
                    if (observed.isSuccess && observed.getOrNull() != generation) created.forEach(storage::remove)
                }
            }
        }
    } catch (_: Exception) { throw NativeMigrationCandidateStore.InvalidCandidate() }
}

// Android API26 has no OutputStream.nullOutputStream().
internal fun nullOutputStreamCompat() = object : java.io.OutputStream() {
    override fun write(value: Int) {}
    override fun write(bytes: ByteArray, offset: Int, length: Int) {}
}
