package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Candidate publication only; actual Keystore/AtomicFile, no active profile or remote command. */
class NativeMigrationCandidateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "synthetic-candidate-password"
    private val namespace = "P7-migration-candidate-${UUID.randomUUID()}"
    private lateinit var parent: NativeSessionStorage
    private lateinit var store: NativeMigrationCandidateStore
    private lateinit var original: Map<String, String>
    private val client = MobileClient("synthetic-host", "synthetic", TargetScope("codex", null, null, "synthetic-host", "synthetic-workspace", "synthetic-runtime"),
        "http://127.0.0.1:1", false, "desktop-runtime", false)
    private val target get() = client.cacheKey("synthetic-thread")
    private val fileId = UUID.randomUUID().toString()
    private val operationId = UUID.randomUUID().toString()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(): Map<String, String> = context.noBackupFilesDir.walkTopDown().filter { it.isFile }
        .filterNot { it.relativeTo(context.noBackupFilesDir).path.let {path->path.startsWith("$namespace-migration-candidates/")||path.startsWith("$namespace-receipt-probe/")} }
        .associate { it.relativeTo(context.noBackupFilesDir).path to sha(it.readBytes()) }
    @Before fun prepare() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        parent = NativeSessionStorage(context, namespace)
        parent.write("metadata", "original active metadata remains untouched")
        parent.write("operations:original", "original immutable operation remains untouched")
        store = NativeMigrationCandidateStore(context, parent); original = snapshot()
    }
    @After fun preserve() {
        assertEquals("Candidate staging never changes any original encrypted file", original, snapshot())
        val run = InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: error("Explicit run required")
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        File(context.filesDir, "acceptance/P4-owner-callbacks/$run/migration-candidate").apply { mkdirs() }
            .resolve("preservation-${UUID.randomUUID()}.json").writeText(JSONObject().put("allOriginalBytesPreserved", true).put("fileCount", original.size).toString())
    }
    internal fun syntheticPayload(): JSONObject {
        val bytes = "synthetic attachment\n合成附件".toByteArray()
        val manifest = CreationFile(fileId, "synthetic.txt", "text/plain", bytes.size.toLong(), sha(bytes)).body()
        val intent = ReplyIntent(client.id, "synthetic-thread", operationId, " original unsent text ", listOf(fileId), "next-turn").body().toString()
        val operation = SavedOperation(operationId, "reply", target, intent, sha(intent.toByteArray()), "uncertain", submissionAttempted = true,
            sourceKey = target, sourceVersion = "original-version").body()
        val metadata = JSONObject().put("version", 1).put("appearance", "dark").put("language", "en").put("demo", false)
            .put("selected", client.id).put("clients", JSONArray().put(encodeClient(client))).put("drafts", JSONObject().put(target, "new local draft"))
            .put("draftFiles", JSONObject().put(target, JSONArray().put(manifest))).put("draftVersions", JSONObject().put(target, "new-version"))
            .put("selectedThreads", JSONObject()).put("expanded", JSONObject()).put("workspaces", JSONObject())
        val source = JSONObject().put("hostId", client.id).put("target", CodexCommandCodec.target(client.scope!!)).put("sourceKey", target)
            .put("sourceVersion", "original-version").put("text", " original unsent text ").put("files", JSONArray().put(manifest))
        val records = JSONObject().put("metadata", metadata.toString())
            .put("draft-file:$fileId", JSONObject().put("file", manifest).put("bytes", Base64.getEncoder().encodeToString(bytes)).toString())
            .put("operations:$target", JSONObject().put("version", 1).put("target", target).put("operations", JSONArray().put(operation)).toString())
            .put("submission:$operationId", source.toString())
        return JSONObject().put("format", "agent-hub/android-migration").put("version", 1).put("applicationId", context.packageName).put("records", records)
    }
    private fun encrypted(body: JSONObject) = NativeMigrationCrypto.seal(body.toString().toByteArray(), password)
    private fun stage(body: JSONObject, id: String = UUID.randomUUID().toString(), replacing: NativeMigrationCandidateStore.Receipt? = null) = store.stage(id, encrypted(body), password, replacing)
    private fun cipher() = File(parent.child(context, "migration-candidates").coordinationKey, sha("candidate-v1".toByteArray()) + ".bin").readBytes()
    private fun reject(body: JSONObject, replacing: NativeMigrationCandidateStore.Receipt) {
        val prior = cipher()
        try { stage(body, replacing = replacing); fail("Invalid candidate accepted") }
        catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
        assertArrayEquals(prior, cipher()); store.read(replacing)
    }

    @Test fun validCandidateColdObjectReadKeepsOriginalTextsIdsAndUnknownOperation() {
        val body = syntheticPayload(); val receipt = stage(body)
        val records = NativeMigrationCandidateStore(context, NativeSessionStorage(context, namespace)).read(receipt)
        for (name in body.getJSONObject("records").keys()) assertEquals(body.getJSONObject("records").getString(name), records[name])
        val row = JSONObject(records.getValue("operations:$target")).getJSONArray("operations").getJSONObject(0)
        assertEquals(operationId, row.getString("id")); assertEquals("uncertain", row.getString("phase")); assertTrue(row.getBoolean("submissionAttempted"))
        assertEquals("original active metadata remains untouched", parent.read("metadata"))
    }

    private fun photoPayload(): JSONObject {
        val body=syntheticPayload();val records=body.getJSONObject("records")
        val metadata=JSONObject(records.getString("metadata"));val manifest=metadata.getJSONObject("draftFiles").getJSONArray(target).getJSONObject(0)
        manifest.put("mediaType","image/png");metadata.put("photoSources",JSONObject().put(fileId,"content://media/external/images/media/42"));records.put("metadata",metadata.toString())
        val encryptedFile=JSONObject(records.getString("draft-file:$fileId")).put("file",manifest);records.put("draft-file:$fileId",encryptedFile.toString())
        val source=JSONObject(records.getString("submission:$operationId")).put("files",JSONArray().put(manifest)).put("photoSources",metadata.getJSONObject("photoSources"));records.put("submission:$operationId",source.toString())
        return body
    }
    @Test fun localPhotoHintsRemainSeparateFromWireAndOriginalReplyReceipt()=kotlinx.coroutines.runBlocking {
        val body=photoPayload();val receipt=stage(body);val records=store.read(receipt)
        assertEquals(body.getJSONObject("records").getString("metadata"),records.getValue("metadata"))
        val probe=parent.child(context,"receipt-probe")
        records.forEach {(name,text)->probe.write(name,text)}
        val flow=NativeCodexFlow(probe,NativeHostReads {""},NativeCodexCommands {""});val original=flow.journal.records(target).single()
        val snapshot=flow.replyRecordContent(client,original);val file=snapshot.files.single()
        assertEquals("content://media/external/images/media/42",file.photoSourceId)
        assertEquals(setOf("id","name","mediaType","sizeBytes","sha256"),file.manifest.body().keys().asSequence().toSet())
        assertEquals(original,flow.journal.records(target).single());assertEquals("uncertain",original.phase);assertTrue(original.submissionAttempted)
    }
    @Test fun invalidOrDanglingLocalPhotoHintsCannotReplaceCandidate() {
        val original=stage(photoPayload())
        for(value in listOf("file:///private/image.png","content://media/external/images/media/01",JSONObject.NULL)) {
            val body=photoPayload();val records=body.getJSONObject("records");val metadata=JSONObject(records.getString("metadata"));metadata.getJSONObject("photoSources").put(fileId,value);records.put("metadata",metadata.toString());reject(body,original)
        }
        val body=photoPayload();val records=body.getJSONObject("records");val source=JSONObject(records.getString("submission:$operationId"));source.getJSONObject("photoSources").put(UUID.randomUUID().toString(),"content://media/external/images/media/42");records.put("submission:$operationId",source.toString());reject(body,original)
    }

    @Test fun sameIdDigestReturnsOriginalCipherAndStaleReplacementCannotOverwriteNewCandidate() {
        val body = syntheticPayload(); val first = stage(body); val originalCipher = cipher()
        assertEquals(first, stage(body, first.id)); assertArrayEquals(originalCipher, cipher())
        val nextBody = syntheticPayload(); nextBody.getJSONObject("records").put("metadata", JSONObject(nextBody.getJSONObject("records").getString("metadata")).put("appearance", "light").toString())
        val second = stage(nextBody, replacing = first); val secondCipher = cipher()
        try { stage(body, replacing = first); fail("Stale replacement accepted") } catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
        try { stage(body, second.id, second); fail("Same ID changed its immutable content") } catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
        assertArrayEquals(secondCipher, cipher()); store.read(second)
        try { store.read(first); fail("Original receipt adopted a later candidate") } catch (_: IllegalArgumentException) { }
    }

    @Test fun wrongPasswordVersionOriginAndIosPayloadPreservePreviousCandidate() {
        val first = stage(syntheticPayload()); val before = cipher()
        try { store.stage(UUID.randomUUID().toString(), encrypted(syntheticPayload()), "wrong-synthetic-password", first); fail("Wrong password accepted") }
        catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
        assertArrayEquals(before, cipher())
        reject(syntheticPayload().put("version", 2), first); reject(syntheticPayload().put("applicationId", "com.easyai.agenthub"), first)
        reject(syntheticPayload().put("format", "ios-native-SavedSession"), first)
        reject(syntheticPayload().put("notificationBindings", JSONObject()), first)
    }

    @Test fun attachmentMissingChangedHashSizeOrRecordNameIsRejected() {
        val first = stage(syntheticPayload())
        reject(syntheticPayload().also { it.getJSONObject("records").remove("draft-file:$fileId") }, first)
        for (field in listOf("sha256", "sizeBytes", "id")) {
            val changed = syntheticPayload(); val records = changed.getJSONObject("records"); val file = JSONObject(records.getString("draft-file:$fileId"))
            file.getJSONObject("file").put(field, when (field) { "sha256" -> "0".repeat(64); "sizeBytes" -> 999; else -> "../unsafe" })
            records.put("draft-file:$fileId", file.toString()); reject(changed, first)
        }
        reject(syntheticPayload().also { it.getJSONObject("records").put("../../outside", "{}") }, first)
    }

    @Test fun journalHashIdScopeResultAndSubmissionMutationAreRejectedWithoutReplay() {
        val first = stage(syntheticPayload())
        for (field in listOf("intentSha256", "id", "target", "result")) {
            val body = syntheticPayload(); val records = body.getJSONObject("records"); val journal = JSONObject(records.getString("operations:$target"))
            val row = journal.getJSONArray("operations").getJSONObject(0)
            row.put(field, when (field) {
                "intentSha256" -> "0".repeat(64); "id" -> UUID.randomUUID().toString(); "target" -> "other-runtime"
                else -> JSONObject().put("hostId", "other-host").put("threadId", "synthetic-thread").put("clientMessageId", operationId)
                    .put("state", "received").put("turnId", JSONObject.NULL).put("error", JSONObject.NULL).toString()
            })
            records.put("operations:$target", journal.toString()); reject(body, first)
        }
        val body = syntheticPayload(); val records = body.getJSONObject("records"); val source = JSONObject(records.getString("submission:$operationId"))
        source.put("text", "changed snapshot"); records.put("submission:$operationId", source.toString()); reject(body, first)
        val duplicated = syntheticPayload(); val entries = duplicated.getJSONObject("records"); val journal = JSONObject(entries.getString("operations:$target"))
        val result = JSONObject().put("hostId", client.id).put("threadId", "synthetic-thread").put("clientMessageId", operationId)
            .put("state", "received").put("turnId", JSONObject.NULL).put("error", JSONObject.NULL).toString()
        journal.getJSONArray("operations").getJSONObject(0).put("result", result.replaceFirst("{", "{\"hostId\":\"other-host\","))
        entries.put("operations:$target", journal.toString()); reject(duplicated, first)
    }

    @Test fun duplicateNestedRecordFieldsMalformedUnicodeAndDepthFailBeforePublication() {
        val first = stage(syntheticPayload()); val before = cipher()
        val good = syntheticPayload().toString()
        val duplicated = syntheticPayload().also { body -> val records = body.getJSONObject("records"); records.put("metadata", records.getString("metadata").replaceFirst("{", "{\"version\":1,")) }.toString()
        val deep = "{\"extra\":".repeat(34) + "null" + "}".repeat(34)
        for (raw in listOf(good.replaceFirst("{", "{\"version\":1,"), duplicated, deep, good + " {}")) {
            try { store.stage(UUID.randomUUID().toString(), NativeMigrationCrypto.seal(raw.toByteArray(), password), password, first); fail("Invalid JSON accepted") }
            catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
            assertArrayEquals(before, cipher())
        }
        val invalid = good.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        try { store.stage(UUID.randomUUID().toString(), NativeMigrationCrypto.seal(invalid, password), password, first); fail("Malformed UTF-8 accepted") }
        catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
        assertArrayEquals(before, cipher())
    }

    @Test fun credentialsDeviceKeysAndUnsupportedProviderAreNotSilentlyImported() {
        val first = stage(syntheticPayload())
        for (name in listOf("codex-private-connections-v1", "token:synthetic-host", "easywork-credential:synthetic", "notification-key")) {
            reject(syntheticPayload().also { it.getJSONObject("records").put(name, "{}") }, first)
        }
        val body = syntheticPayload(); val records = body.getJSONObject("records"); val metadata = JSONObject(records.getString("metadata"))
        metadata.getJSONArray("clients").getJSONObject(0).getJSONObject("scope").put("agentId", "easywork")
        records.put("metadata", metadata.toString()); reject(body, first)
    }

    @Test fun unreadablePriorCandidateCannotBeOverwrittenByReplacement() {
        val first = stage(syntheticPayload())
        val file = File(parent.child(context, "migration-candidates").coordinationKey, sha("candidate-v1".toByteArray()) + ".bin")
        val good = file.readBytes(); val bad = good.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val atomic = android.util.AtomicFile(file)
        fun restore(bytes: ByteArray) { val out = atomic.startWrite(); out.write(bytes); atomic.finishWrite(out) }
        restore(bad)
        try {
            try { stage(syntheticPayload(), replacing = first); fail("Unreadable prior candidate overwritten") }
            catch (_: NativeMigrationCandidateStore.InvalidCandidate) { }
            assertArrayEquals(bad, file.readBytes())
        } finally { restore(good) }
        store.read(first)
    }

    @Test fun concurrentInitialStagesPublishOneCandidateWithoutOverwritingTheWinner() {
        val envelope = encrypted(syntheticPayload())
        val ready = java.util.concurrent.CountDownLatch(2); val begin = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..1).map {
                workers.submit<NativeMigrationCandidateStore.Receipt?> {
                    ready.countDown(); assertTrue(begin.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    try { NativeMigrationCandidateStore(context, parent).stage(UUID.randomUUID().toString(), envelope, password) }
                    catch (_: NativeMigrationCandidateStore.InvalidCandidate) { null }
                }
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); begin.countDown()
            val results = jobs.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            val winner = results.filterNotNull().single(); store.read(winner)
            assertEquals(1, results.count { it == null })
        } finally { begin.countDown(); workers.shutdownNow() }
    }
}
