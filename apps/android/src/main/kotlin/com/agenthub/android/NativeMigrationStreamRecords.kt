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

internal data class NativeMigrationStreamRecords(val text: Map<String, String>, val binary: Map<String, NativeMigrationBlob>, val version: Int=3) {
    val size get() = text.size + binary.size
    init {
        require(version in setOf(3,4)&&size in 1..1024 && text.keys.intersect(binary.keys).isEmpty())
        require(binary.values.all { valid(it,21 * 1024 * 1024) })
        require(binary.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES)
        val frames=textFrames()
        require(frames.values.all {valid(it,NativeMigrationTextFrames.MAX_RECORD_BYTES)})
        val textBytes=frames.values.sumOf {it.size.toLong()}
        require(textBytes+binary.values.sumOf {it.size.toLong()}<=MAX_TOTAL_BYTES)
        if(version==3)require(textBytes<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
    }
    fun textFrames(): Map<String,NativeMigrationBlob> = if(text is NativeMigrationTextMap)text.frames() else text.mapValues {NativeMigrationTextFrames.retained(it.value)}
    fun frames(): Map<String,NativeMigrationBlob> = linkedMapOf<String,NativeMigrationBlob>().apply {
        if(version==4)putAll(textFrames().toSortedMap())
        putAll(binary.toSortedMap())
    }
    fun header(applicationId: String) = JSONObject().put("format", "agent-hub/android-migration-stream")
        .put("version", version).put("applicationId", applicationId)
        .put("records",if(version==3)JSONObject(text)else descriptors(textFrames()))
        .put("binaries",descriptors(binary))
    companion object {
        const val MAX_TOTAL_BYTES = 192L * 1024 * 1024
        private fun valid(blob: NativeMigrationBlob,maximum: Int)=blob.size in 1..maximum&&blob.sha256.matches(Regex("[a-f0-9]{64}"))&&blob.prefix.size==minOf(44,blob.size)
        private fun descriptors(values: Map<String,NativeMigrationBlob>)=JSONObject().also {body->values.toSortedMap().forEach {(name,blob)->body.put(name,blob.json())}}
    }
}
