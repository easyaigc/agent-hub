package com.agenthub.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable v2 file envelope. Independent AEAD frames bound to this stream, order and final EOF. */
internal object NativeMigrationStreamCrypto {
    private val magic = byteArrayOf(65, 72, 77, 73, 71, 50, 13, 10)
    private val context = "agent-hub/android-migration-file/v2".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()
    private const val CHUNK = 65536
    fun isEnvelope(file: java.io.File): Boolean = file.inputStream().use { input ->
        val prefix = ByteArray(magic.size); var count = 0
        while (count < prefix.size) { val n = input.read(prefix, count, prefix.size - count); if (n < 0) return@use false; count += n }
        prefix.contentEquals(magic)
    }
    private fun aad(salt: ByteArray, stream: ByteArray, index: Int, size: Int) =
        context + magic + salt + stream + ByteBuffer.allocate(8).putInt(index).putInt(size).array()

    fun write(destination: OutputStream, password: String, write: (OutputStream) -> Unit) {
        val salt = ByteArray(16).also(random::nextBytes); val stream = ByteArray(16).also(random::nextBytes)
        val key = NativeMigrationCrypto.deriveKey(password, salt)
        try {
            val out = DataOutputStream(destination); out.write(magic); out.write(salt); out.write(stream)
            val buffer = ByteArray(CHUNK); var used = 0; var index = 0; var total = 0L
            fun frame(size: Int) {
                val nonce = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad(salt, stream, index++, size))
                val encrypted = cipher.doFinal(buffer, 0, size)
                out.writeInt(size); out.write(nonce); out.write(encrypted)
            }
            val sink = object : OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                    total += length; require(total <= NativeMigrationStreamRecords.MAX_TOTAL_BYTES + NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
                    var position = offset; var remaining = length
                    while (remaining > 0) {
                        val count = minOf(CHUNK - used, remaining)
                        System.arraycopy(bytes, position, buffer, used, count); used += count; position += count; remaining -= count
                        if (used == CHUNK) { frame(used); buffer.fill(0); used = 0 }
                    }
                }
            }
            try { write(sink); if (used > 0) frame(used); frame(0); out.flush() } finally { buffer.fill(0) }
        } finally { key.fill(0) }
    }

    fun <T> read(source: InputStream, password: String, read: (InputStream) -> T): T {
        val input = DataInputStream(source); val observed = ByteArray(magic.size); input.readFully(observed); require(observed.contentEquals(magic))
        val salt = ByteArray(16); input.readFully(salt); val stream = ByteArray(16); input.readFully(stream)
        val key = NativeMigrationCrypto.deriveKey(password, salt)
        var clear = ByteArray(0)
        try {
            var offset = 0; var index = 0; var total = 0L; var ended = false; var shortFrame = false
            val reader = object : InputStream() {
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255 }
                override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                    require(start >= 0 && length >= 0 && start <= bytes.size - length); if (length == 0) return 0
                    if (offset == clear.size) {
                        clear.fill(0); if (ended) return -1
                        val size = input.readInt(); require(size in 0..CHUNK && (!shortFrame || size == 0))
                        val nonce = ByteArray(12); input.readFully(nonce); val encrypted = ByteArray(size + 16); input.readFully(encrypted)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                        cipher.updateAAD(aad(salt, stream, index++, size)); clear = cipher.doFinal(encrypted); require(clear.size == size); offset = 0
                        total += size; require(total <= NativeMigrationStreamRecords.MAX_TOTAL_BYTES + NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
                        if (size == 0) { require(input.read() == -1); ended = true; return -1 }
                        shortFrame = size < CHUNK
                    }
                    val count = minOf(length, clear.size - offset); System.arraycopy(clear, offset, bytes, start, count); offset += count; return count
                }
            }
            val result = read(reader); require(reader.read() == -1); return result
        } finally { clear.fill(0); key.fill(0) }
    }
}
