package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Two separate instrumentation processes, with the exact owned acceptance package stopped between them. */
class NativeMigrationCandidateColdTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val run get() = InstrumentationRegistry.getArguments().getString("migrationColdRun")!!.also { require(it.matches(Regex("migration-cold-[a-f0-9]{24}"))) }
    private val namespace get() = "P7-$run"
    private val folder get() = File(context.filesDir, "acceptance/P7-migration-cold/$run").apply { mkdirs() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun activeFiles(): Map<String, String> = context.noBackupFilesDir.walkTopDown().filter { it.isFile }
        .filterNot { it.relativeTo(context.noBackupFilesDir).path.startsWith(namespace) }
        .associate { it.relativeTo(context.noBackupFilesDir).path to sha(it.readBytes()) }
    @Test fun seedOriginalCandidateBeforeOwnedProcessStop() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val case = File(folder, "case.json"); assertFalse("Never replay a cold seed", case.exists())
        val before = activeFiles(); val parent = NativeSessionStorage(context, namespace)
        parent.write("metadata", "original cold metadata")
        val body = NativeMigrationCandidateTest().syntheticPayload().toString()
        val store = NativeMigrationCandidateStore(context, parent)
        val receipt = store.stage(UUID.randomUUID().toString(), NativeMigrationCrypto.seal(body.toByteArray(), "synthetic-cold-password"), "synthetic-cold-password")
        assertEquals(before, activeFiles())
        case.writeText(JSONObject().put("seedPid", android.os.Process.myPid()).put("id", receipt.id).put("sha256", receipt.sha256)
            .put("recordCount", receipt.recordCount).put("body", body).put("otherEncryptedFiles", JSONObject(before)).put("seedPreserved", true).toString())
    }
    @Test fun readSameOriginalCandidateInNewProcessWithoutActivationOrReplay() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val case = JSONObject(File(folder, "case.json").readText())
        assertFalse("Original cold read evidence is immutable", File(folder, "read.json").exists())
        assertNotEquals(case.getInt("seedPid"), android.os.Process.myPid())
        val parent = NativeSessionStorage(context, namespace)
        val receipt = NativeMigrationCandidateStore.Receipt(case.getString("id"), case.getString("sha256"), case.getInt("recordCount"))
        val records = NativeMigrationCandidateStore(context, parent).read(receipt)
        val expected = JSONObject(case.getString("body")).getJSONObject("records")
        for (name in expected.keys()) assertEquals(expected.getString(name), records[name])
        assertEquals("original cold metadata", parent.read("metadata"))
        val prior = case.getJSONObject("otherEncryptedFiles"); val now = activeFiles()
        assertEquals(prior.keys().asSequence().associateWith { prior.getString(it) }, now)
        val journal = JSONObject(records.entries.single { it.key.startsWith("operations:") }.value).getJSONArray("operations").getJSONObject(0)
        assertEquals("uncertain", journal.getString("phase")); assertTrue(journal.getBoolean("submissionAttempted"))
        File(folder, "read.json").writeText(JSONObject().put("seedPid", case.getInt("seedPid")).put("readPid", android.os.Process.myPid())
            .put("id", receipt.id).put("sha256", receipt.sha256).put("sameOriginalRecords", true).put("allOtherBytesPreserved", true)
            .put("unknownOriginalNotReplayed", true).put("activeOriginalMetadataPreserved", true).toString())
    }
}
