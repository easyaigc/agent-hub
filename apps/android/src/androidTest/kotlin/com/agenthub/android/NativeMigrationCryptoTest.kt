package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic bytes only; no session, Host, installation secret or application payload is loaded. */
class NativeMigrationCryptoTest {
    private val base64 = Base64.getDecoder()
    private val password = "synthetic-only-password"
    private lateinit var originalFiles: Map<String, String>
    private fun snapshot(): Map<String, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOf(context.noBackupFilesDir, File(context.filesDir, "camera-output-v1")).flatMapIndexed { index, root ->
            if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                "$index/${file.relativeTo(root).path}" to Base64.getEncoder().encodeToString(digest.digest())
            }.toList()
        }.toMap()
    }
    private fun evidence(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        require(instrumentation.targetContext.packageName == "com.easyai.agenthub.android.acceptance")
        val run = InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: error("Explicit evidence run required")
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        return File(instrumentation.targetContext.filesDir, "acceptance/P4-owner-callbacks/$run/migration-crypto").apply { mkdirs() }
    }
    @Before fun preserveOriginalInstallation() { evidence(); originalFiles = snapshot() }
    @After fun verifyOriginalInstallation() {
        assertEquals("No original encrypted files or camera outputs may change", originalFiles, snapshot())
        evidence().resolve("preservation-${java.util.UUID.randomUUID()}.json")
            .writeText(JSONObject().put("originalFileCount", originalFiles.size).put("allBytesPreserved", true).toString())
    }
    private fun rejected(bytes: ByteArray, pass: String = password) {
        try { NativeMigrationCrypto.open(bytes, pass); fail("Untrusted envelope accepted") }
        catch (error: NativeMigrationCrypto.InvalidArchive) { assertEquals("Invalid migration password or archive", error.message) }
    }
    private fun rejected(json: String) = rejected(json.toByteArray(Charsets.UTF_8))

    @Test fun swiftVectorsAuthenticateAndAndroidEnvelopesAreExportedForIndependentSwiftRead() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rows = JSONArray(instrumentation.context.assets.open("migration-envelope-v1.json").bufferedReader().use { it.readText() })
        val output = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val pass = row.getString("password")
            assertArrayEquals(base64.decode(row.getString("key")), NativeMigrationCrypto.deriveKey(pass, base64.decode(row.getString("salt"))))
            val clear = base64.decode(row.getString("plaintext"))
            assertArrayEquals(clear, NativeMigrationCrypto.open(base64.decode(row.getString("envelope")), pass))
            val sealed = NativeMigrationCrypto.seal(clear, pass)
            assertArrayEquals(clear, NativeMigrationCrypto.open(sealed, pass))
            output.put(JSONObject().put("password", pass).put("plaintext", row.getString("plaintext"))
                .put("envelope", Base64.getEncoder().encodeToString(sealed)))
        }
        evidence().resolve("android-envelopes.json").writeText(output.toString())
    }

    @Test fun wrongPasswordAndAuthenticatedFieldChangesNeverPublishPlaintext() {
        val envelope = NativeMigrationCrypto.seal("synthetic unsent draft".toByteArray(), password)
        rejected(envelope, "wrong-synthetic-password")
        val json = JSONObject(String(envelope, Charsets.UTF_8))
        for (field in listOf("salt", "ciphertext")) {
            val changed = JSONObject(json.toString()); val bytes = base64.decode(changed.getString(field))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            changed.put(field, Base64.getEncoder().encodeToString(bytes)); rejected(changed.toString())
        }
        val changed = JSONObject(json.toString()); val bytes = base64.decode(changed.getString("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte(); changed.put("ciphertext", Base64.getEncoder().encodeToString(bytes))
        rejected(changed.toString())
        assertArrayEquals("synthetic unsent draft".toByteArray(), NativeMigrationCrypto.open(envelope, password))
    }

    @Test fun differentAuthenticatedContextIsRejected() {
        val salt = ByteArray(16); val nonce = ByteArray(12)
        val key = NativeMigrationCrypto.deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("wrong synthetic context".toByteArray())
        val json = JSONObject().put("version", 1).put("salt", Base64.getEncoder().encodeToString(salt))
            .put("ciphertext", Base64.getEncoder().encodeToString(nonce + cipher.doFinal(byteArrayOf(1, 2, 3))))
        key.fill(0); rejected(json.toString())
    }

    @Test fun malformedVersionDuplicateFieldsEncodingAndLengthsAreRejected() {
        val valid = JSONObject(String(NativeMigrationCrypto.seal(byteArrayOf(1), password), Charsets.UTF_8))
        for (version in listOf<Any>(2, "1", true, JSONObject.NULL)) rejected(JSONObject(valid.toString()).put("version", version).toString())
        rejected(valid.toString().replaceFirst("{", "{\"version\":1,"))
        rejected(valid.toString().dropLast(1) + ",\"unknown\":1}")
        rejected(valid.toString() + " {}")
        rejected("[]"); rejected("{}"); rejected(valid.toString().replace("\"version\":1", "\"version\":1.0"))
        rejected(JSONObject(valid.toString()).put("salt", "AA==").toString())
        rejected(JSONObject(valid.toString()).put("ciphertext", "AA==").toString())
        rejected(JSONObject(valid.toString()).put("salt", valid.getString("salt").trimEnd('=')).toString())
        rejected(JSONObject(valid.toString()).put("ciphertext", " " + valid.getString("ciphertext")).toString())
        rejected(valid.toString().toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        rejected(valid.toString().replaceFirst("salt", "sa\uD800lt"))
    }

    @Test fun passwordUsesGraphemeCountAndExactUnicodeBytes() {
        assertFalse(NativeMigrationCrypto.acceptsPassword("e\u0301".repeat(11)))
        assertTrue(NativeMigrationCrypto.acceptsPassword("e\u0301".repeat(12)))
        assertFalse(NativeMigrationCrypto.acceptsPassword("👩‍💻".repeat(11)))
        assertTrue(NativeMigrationCrypto.acceptsPassword("👩‍💻".repeat(12)))
        val salt = ByteArray(16)
        assertFalse(NativeMigrationCrypto.deriveKey("e\u0301" + "12345678901", salt)
            .contentEquals(NativeMigrationCrypto.deriveKey("é" + "12345678901", salt)))
        rejected(NativeMigrationCrypto.seal(byteArrayOf(1), password), "short")
        try { NativeMigrationCrypto.seal(byteArrayOf(1), "12345678901\uD800"); fail("Malformed Unicode accepted") }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun saltAndNonceAreFreshAndEmptyBinaryRoundTrips() {
        val first = NativeMigrationCrypto.seal(byteArrayOf(), password)
        val second = NativeMigrationCrypto.seal(byteArrayOf(), password)
        val a = JSONObject(String(first, Charsets.UTF_8)); val b = JSONObject(String(second, Charsets.UTF_8))
        assertNotEquals(a.getString("salt"), b.getString("salt"))
        assertFalse(base64.decode(a.getString("ciphertext")).copyOf(12).contentEquals(base64.decode(b.getString("ciphertext")).copyOf(12)))
        assertArrayEquals(byteArrayOf(), NativeMigrationCrypto.open(first, password))
        assertArrayEquals(byteArrayOf(), NativeMigrationCrypto.open(second, password))
    }
}
