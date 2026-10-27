package com.agenthub.android

import android.icu.text.BreakIterator
import android.util.JsonReader
import android.util.JsonToken
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Password envelope only. Native payload identity/schema validation belongs to the importer. */
internal object NativeMigrationCrypto {
    const val MAX_FILE_BYTES = 256 * 1024 * 1024
    // Include base64 expansion, nonce/tag and JSON overhead in the file limit.
    const val MAX_PLAINTEXT_BYTES = (MAX_FILE_BYTES - 256) / 4 * 3 - 28
    private val context = "codex-manage/encrypted-migration/v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()
    internal class InvalidArchive : Exception("Invalid migration password or archive")

    fun acceptsPassword(password: String): Boolean {
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(password) }
        boundaries.first()
        repeat(12) { if (boundaries.next() == BreakIterator.DONE) return false }
        return true
    }

    // Raw strict UTF-8, including embedded NUL; no provider-dependent PBEKeySpec conversion.
    internal fun deriveKey(password: String, salt: ByteArray): ByteArray {
        require(acceptsPassword(password) && salt.size == 16)
        val input = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(input.remaining()).also { input.get(it) }
        try {
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(bytes, salt, 210_000)
            return (generator.generateDerivedParameters(256) as KeyParameter).key
        } finally { bytes.fill(0); if (input.hasArray()) input.array().fill(0) }
    }

    fun seal(plaintext: ByteArray, password: String): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(context)
            val encoded = JSONObject().put("version", 1)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("ciphertext", Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext)))
                .toString().toByteArray(Charsets.UTF_8)
            check(encoded.size <= MAX_FILE_BYTES)
            return encoded
        } finally { key.fill(0) }
    }

    fun open(data: ByteArray, password: String): ByteArray = try {
        require(data.size <= MAX_FILE_BYTES)
        var version: String? = null; var saltText: String? = null; var sealedText: String? = null
        JsonReader(InputStreamReader(ByteArrayInputStream(data), Charsets.UTF_8.newDecoder())).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val seen = mutableSetOf<String>()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(seen.add(field))
                when (field) {
                    "version" -> { require(reader.peek() == JsonToken.NUMBER); version = reader.nextString() }
                    "salt" -> { require(reader.peek() == JsonToken.STRING); saltText = reader.nextString() }
                    "ciphertext" -> { require(reader.peek() == JsonToken.STRING); sealedText = reader.nextString() }
                    else -> throw InvalidArchive()
                }
            }
            reader.endObject(); require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(version == "1")
        val salt = decodeBase64(requireNotNull(saltText)); require(salt.size == 16)
        val sealed = decodeBase64(requireNotNull(sealedText)); require(sealed.size >= 28)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(context)
            cipher.doFinal(sealed, 12, sealed.size - 12)
        } finally { key.fill(0) }
    } catch (_: Exception) { throw InvalidArchive() }

    private fun decodeBase64(text: String): ByteArray {
        val decoded = Base64.getDecoder().decode(text)
        require(Base64.getEncoder().encodeToString(decoded) == text)
        return decoded
    }
}
