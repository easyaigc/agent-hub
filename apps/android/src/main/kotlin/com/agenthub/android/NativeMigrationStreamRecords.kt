package com.agenthub.android

import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest

/** A binary reference has no path/URI or execution authority. Each reader authenticates before delivery. */
internal data class NativeMigrationBlob(val size: Int, val sha256: String, val prefix: ByteArray, val copy: (OutputStream) -> Unit) {
    fun verifiedCopy(output: OutputStream) {
        val probe = NativeMigrationProbe(output)
        copy(probe)
        require(probe.size == size.toLong() && probe.sha256() == sha256 && probe.prefix().contentEquals(prefix))
    }
    fun json() = JSONObject().put("size", size).put("sha256", sha256)
        .put("prefix", java.util.Base64.getEncoder().encodeToString(prefix))
}

internal class NativeMigrationProbe(private val output: OutputStream? = null) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val head = java.io.ByteArrayOutputStream(44)
    var size = 0L; private set
    override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        require(count >= 0 && size + count <= NativeMigrationStreamRecords.MAX_TOTAL_BYTES)
        if (head.size() < 44) head.write(bytes, offset, minOf(count, 44 - head.size()))
        digest.update(bytes, offset, count); size += count; output?.write(bytes, offset, count)
    }
    fun prefix() = head.toByteArray()
    fun sha256() = digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class NativeMigrationStreamRecords(val text: Map<String, String>, val binary: Map<String, NativeMigrationBlob>) {
    val size get() = text.size + binary.size
    init {
        require(size in 1..1024 && text.keys.intersect(binary.keys).isEmpty())
        require(binary.values.all { it.size in 1..21 * 1024 * 1024 && it.sha256.matches(Regex("[a-f0-9]{64}")) && it.prefix.size == minOf(44, it.size) })
        require(binary.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES)
    }
    fun header(applicationId: String) = JSONObject().put("format", "agent-hub/android-migration-stream")
        .put("version", 3).put("applicationId", applicationId).put("records", JSONObject(text))
        .put("binaries", JSONObject().also { body -> binary.toSortedMap().forEach { (name, blob) -> body.put(name, blob.json()) } })
    companion object {
        // Raw binary budget is independent of the unchanged 8MiB legacy text/JSON budget.
        const val MAX_TOTAL_BYTES = 192L * 1024 * 1024
    }
}
