package com.agenthub.android

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Framed payload: one bounded strict header followed by sorted exact original binary values. */
internal object NativeMigrationStreamArchive {
    fun write(output: OutputStream, records: NativeMigrationStreamRecords, applicationId: String) {
        val header = records.header(applicationId).toString().toByteArray(Charsets.UTF_8)
        try {
            require(header.size in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            val out = DataOutputStream(output); out.writeInt(header.size); out.write(header)
            records.binary.toSortedMap().forEach { (_, blob) -> blob.verifiedCopy(out) }; out.flush()
        } finally { header.fill(0) }
    }
    fun header(value: JSONObject, applicationId: String, reader: (String, Int) -> ((OutputStream) -> Unit)): NativeMigrationStreamRecords {
        require(value.keys().asSequence().toSet() == setOf("format", "version", "applicationId", "records", "binaries"))
        require(value.get("format") == "agent-hub/android-migration-stream" && value.get("version") == 3 && value.get("applicationId") == applicationId)
        val rows = value.getJSONObject("records"); val text = rows.keys().asSequence().associateWith { rows.get(it) as String }
        val blobs = value.getJSONObject("binaries"); require(text.size + blobs.length() in 1..1024)
        require((text.keys + blobs.keys().asSequence()).all { it.isNotEmpty() && it.length <= 8192 && !it.contains('\u0000') })
        return NativeMigrationStreamRecords(text, blobs.keys().asSequence().associateWith { name ->
            val blob = blobs.getJSONObject(name); require(blob.keys().asSequence().toSet() == setOf("size", "sha256", "prefix"))
            val size = blob.get("size") as Int; val encoded = blob.getString("prefix"); require(encoded.length <= 60)
            val prefix = Base64.getDecoder().decode(encoded); require(Base64.getEncoder().encodeToString(prefix) == encoded)
            NativeMigrationBlob(size, blob.getString("sha256"), prefix, reader(name, size))
        })
    }
    fun readHeader(input: InputStream, applicationId: String): NativeMigrationStreamRecords {
        val data = DataInputStream(input); val size = data.readInt(); require(size in 1..NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val bytes = ByteArray(size); data.readFully(bytes)
        return try {
            val records = header(StrictMigrationJson.parse(bytes), applicationId) { _, _ -> { _ -> error("Unconsumed migration blob") } }
            // The receipt hashes the original framing; reject alternate JSON spellings rather than
            // publishing a candidate whose reconstruction would have a different digest.
            require(bytes.contentEquals(records.header(applicationId).toString().toByteArray(Charsets.UTF_8)))
            records
        } finally { bytes.fill(0) }
    }
    fun transfer(input: InputStream, blob: NativeMigrationBlob, output: OutputStream) {
        val buffer = ByteArray(65536); var remaining = blob.size; val data = DataInputStream(input)
        val actual = blob.copy(copy = { sink ->
            try { while (remaining > 0) { val count = minOf(buffer.size, remaining); data.readFully(buffer, 0, count); sink.write(buffer, 0, count); remaining -= count } }
            finally { buffer.fill(0) }
        })
        actual.verifiedCopy(output)
    }
}
