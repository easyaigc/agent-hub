package com.agenthub.android

import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.snapshotFlow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** SOURCE CANDIDATE ONLY. Normal existing mobile-v1; no reset, pairing, UI send or model.
 * Root must bind fresh installed pins, complete original-profile protection, a quiet
 * normal-startup window and a precise Host synthetic-publication retention/cleanup plan.
 * The two selectors are upload API + real SharedCenter acceptance, NOT picker/button UI.
 * NativeTestForeground launches shipping Main normally, AFTER the read-only input guard.
 * Its ordinary background reads/notification lifecycle are not suppressed or called zero IO.
 * Default Session/center/writer/IO belong to MainActivity; never close or retire them here.
 */
@RunWith(AndroidJUnit4::class)
class NativeDefaultAttachmentUploadOnlyTest {
    private val app = "com.easyai.agenthub.android.acceptance"
    private val host = "13c81542-48e3-4df2-ab46-d45357b631a2"
    private val thread = "01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
    private val targetHash = "4edc39b469ffdfebdbf64b04e9a672decd1aa5037646336018bfb63581ebbf9b"
    // A separately fenced case for the corrected assertion/readiness boundary; V2 stays consumed.
    private val repairedUserDataSelector = "attachmentUploadOnlyIndependentCancelPreservesOriginalUserDataV3"
    private val existingDraftSelector = "attachmentCancelPreservesExistingDraftAttachmentsAndReadingState"
    private val afterMainCloseSelector = "attachmentCancelPreservesExistingDraftAttachmentsReadingAfterMainCloses"
    private val existingDraftAction get() = if (afterMainCloseCase) "f31-cancel-existing-draft-reading-after-main-close-v2"
        else "f31-cancel-with-existing-draft-and-reading-v1"
    private val selectors = setOf("attachmentUploadOnlyCompletesWithRealReceipt", "attachmentUploadOnlyCancelsOriginalWhenSupported", F31UserDataRulesV2.SELECTOR, repairedUserDataSelector, existingDraftSelector, afterMainCloseSelector)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var plan: JSONObject
    private lateinit var root: File
    private lateinit var run: String
    private lateinit var method: String
    private lateinit var initialMetadataHash: String
    private lateinit var initialUserMetadata: Map<String, String>
    private val newUserDataCase get() = method == F31UserDataRulesV2.SELECTOR || method == repairedUserDataSelector
    private val afterMainCloseCase get() = method == afterMainCloseSelector
    private val existingDraftCase get() = method == existingDraftSelector || afterMainCloseCase
    private lateinit var initialUserDataV2: F31UserDataSnapshotV2
    private val initialOriginalOperationsV2 = mutableListOf<SavedOperation>()
    private lateinit var initialUploadJournalLogicalHash: String
    private lateinit var session: MobileSession
    private lateinit var storage: NativeSessionStorage
    private lateinit var center: NativeUploadCenter
    private lateinit var client: MobileClient
    private lateinit var port: AgentCommands
    private lateinit var presentation: DraftDestination
    private lateinit var presentationHash: String
    private lateinit var descriptorHash: String
    private lateinit var credentialHash: String
    private lateinit var operationHashes: Map<String, String>
    private lateinit var operationTargets: Set<String>
    private lateinit var preStartupOperations: Map<String, String>
    private lateinit var oldRows: Map<String, String>
    private val io = NativeMigrationIoWork()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.Main.immediate + io)
    private var caller: Deferred<Result<JSONObject>>? = null
    private var local: LocalDraftFile? = null
    private var manifest: NativeUploadManifest? = null
    private var rowKey: String? = null
    private var ownedSourceId: String? = null
    private var ownedPayloadKey: String? = null
    private var admitted = false
    private var consumed = false
    private var bodyOutcome = "NOT_RUN"
    private var phase = "admission"
    private val evidence = JSONObject()
    private enum class PositiveStage {
        BEFORE_MAIN, READY_ORIGINAL, FIXTURE_STAGED, BEFORE_CANCEL, AFTER_CANCEL,
        FIXTURE_RESTORED, FINAL_ORIGINAL
    }
    private val positiveStages = JSONObject()
    private var positivePhaseBytes = 0L
    private var positiveClosure: JSONObject? = null
    private var positiveBeforeCancel: F31UserDataSnapshotV2? = null
    private var positiveReady: F31UserDataSnapshotV2? = null
    private var positiveStage = PositiveStage.BEFORE_MAIN
    private var positiveFixtureStaged = false
    private var positiveFixtureRestored = false
    private var positiveFixtureAttempted = false
    private var positiveFixture: F31OwnedDraftFixture? = null
    private var positiveOriginalText: String? = null
    private var positiveOriginalAttachments: List<CreationFile>? = null
    private var positiveOriginalPresentationHash: String? = null
    private var positiveReadyVersion: String? = null
    private var positiveCancelVersion: String? = null
    private data class PositiveRecordCipher(val sha256: String, val stat: List<Long>)
    private data class PositiveCaptureFence(val metadata: PositiveRecordCipher, val journal: PositiveRecordCipher?)
    private var positiveMetadataKey: ByteArray? = null
    private var positiveMetadataKeyRetired = false
    private var positiveMetadataKeyId: String? = null
    private var positiveMetadataQaKey: String? = null
    private val positiveMetadataWitnesses = java.util.IdentityHashMap<F31UserDataSnapshotV2, JSONObject>()
    private var positiveOriginalJournalWitness: JSONObject? = null
    private var positiveBorrowedMain: MainActivity? = null
    private var positiveDeferredFlags: JSONObject? = null
    private var positiveDeferredFailures: MutableList<String>? = null
    private var positivePostCloseResultAttempted = false

    private fun requirePositiveMetadataKey(): ByteArray {
        requireBound(existingDraftCase && plan.getString("action") == existingDraftAction && !positiveMetadataKeyRetired)
        positiveMetadataKey?.let { return it }
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        positiveMetadataKeyId = UUID.randomUUID().toString() // Public correlation ID, never derived from the key.
        positiveMetadataKey = key
        return key
    }

    private fun clearPositiveMetadataKey(): Unit {
        if (!::method.isInitialized || !existingDraftCase) return
        positiveMetadataKey?.fill(0)
        positiveMetadataKey = null
        positiveMetadataKeyRetired = true
    }

    private fun positiveWitnessValue(value: Any?): Any = F31MetadataWitnessValues.value(value)

    private fun positiveMetadataMac(label: String, value: Any?): String {
        val input = ("f31-positive-metadata-witness-v1\u0000$run\u0000$method\u0000$label\u0000" +
            NativeChatRecoveryCanonical.value(positiveWitnessValue(value))).toByteArray(Charsets.UTF_8)
        try { return javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(requirePositiveMetadataKey(), "HmacSHA256"))
        }.doFinal(input).joinToString("") { "%02x".format(it) }
        } finally { input.fill(0) }
    }

    private fun positiveRecordCipher(record: BoundRecord): PositiveRecordCipher? {
        requireBound(existingDraftCase)
        val folder = defaultRecordsRoot()
        val logicalHash = ComputerFileCodec.hash(record.logicalName.toByteArray(Charsets.UTF_8))
        val file = File(folder, "$logicalHash.bin")
        fun observed(): android.system.StructStat? = try { Os.lstat(file.path) }
            catch (error: android.system.ErrnoException) {
                if (record != BoundRecord.UPLOAD_JOURNAL || error.errno != OsConstants.ENOENT) throw error
                requireBound(java.nio.file.Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                null
            }
        val before = observed()
        // Reuse the exact original bounded FD/NOFOLLOW/nonblock/sidecar/EOF/hash/fstat guard.
        val digest = encryptedRecordHash(record)
        val after = observed()
        requireBound(defaultRecordsRoot() == folder)
        for (suffix in listOf(".bak", ".new")) requireBound(java.nio.file.Files.notExists(
            File(file.path + suffix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        if (before == null || after == null) {
            requireBound(record == BoundRecord.UPLOAD_JOURNAL && before == null && after == null && digest == null)
            return null // Explicit settled absence; never an empty-file digest or synthesized rows.
        }
        requireBound(digest != null && fileIdentity(before) == fileIdentity(after) &&
            OsConstants.S_ISREG(after.st_mode) && after.st_uid == android.os.Process.myUid() &&
            after.st_gid == after.st_uid && after.st_nlink == 1L && (after.st_mode and 511) == 384)
        return PositiveRecordCipher(checkNotNull(digest), fileIdentity(after))
    }

    private fun positiveMetadataCipher(): PositiveRecordCipher = checkNotNull(positiveRecordCipher(BoundRecord.METADATA))
    private fun positiveCaptureFence() = PositiveCaptureFence(positiveMetadataCipher(), positiveRecordCipher(BoundRecord.UPLOAD_JOURNAL))

    private fun positiveJournalSemanticsSame(a: JSONObject, z: JSONObject): Boolean =
        a.getString("state") == z.getString("state") && a.getString("rawHmac") == z.getString("rawHmac") &&
            a.getString("orderedRowsHmac") == z.getString("orderedRowsHmac") &&
            a.getInt("rowCount") == z.getInt("rowCount") &&
            a.getJSONArray("rowHmacs").strings() == z.getJSONArray("rowHmacs").strings() &&
            a.get("allTerminal") == true && z.get("allTerminal") == true

    private fun positiveJournalPhysicalSame(current: PositiveRecordCipher?, witness: JSONObject): Boolean =
        if (current == null) witness.getString("state") == "ABSENT" && witness.isNull("cipherSHA256") &&
            witness.isNull("stat") && witness.isNull("bytes")
        else witness.getString("state") == "PRESENT" && current.sha256 == witness.getString("cipherSHA256") &&
            current.stat[6] == witness.getLong("bytes") && JSONArray(current.stat).toString() == witness.getJSONArray("stat").toString()

    private fun positiveUploadJournalWitness(raw: String?, rows: List<NativeUploadRow>,
        cipher: PositiveRecordCipher?): JSONObject {
        requireBound((raw == null) == (cipher == null) && rows.all { it.terminal })
        val logicalHash = ComputerFileCodec.hash(NativeUploadCenter.RECORD.toByteArray(Charsets.UTF_8))
        val value = JSONObject().put("version", 1).put("status", "OBSERVED")
            .put("logicalNameHash", logicalHash).put("relativePath", "no_backup/mobile-v1/$logicalHash.bin")
            .put("state", if (cipher == null) "ABSENT" else "PRESENT")
            .put("cipherSHA256", cipher?.sha256 ?: JSONObject.NULL)
            .put("bytes", cipher?.stat?.get(6) ?: JSONObject.NULL)
            .put("stat", cipher?.stat?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("rawHmac", positiveMetadataMac("upload-journal-raw", raw))
            .put("orderedRowsHmac", positiveMetadataMac("upload-journal-encoded-rows", NativeUploadJournalCodec.encode(rows)))
            .put("rowCount", rows.size).put("rowHmacs", JSONArray(rows.mapIndexed { index, row ->
                positiveMetadataMac("upload-journal-row:$index", NativeUploadJournalCodec.encode(listOf(row)))
            })).put("allTerminal", true).put("externalActualPostJournalMatchRequired", true)
        if (positiveOriginalJournalWitness == null) {
            requireBound(positiveMetadataWitnesses.isEmpty())
            positiveOriginalJournalWitness = JSONObject(value.toString())
        }
        return value.put("originalSemanticsSame", positiveJournalSemanticsSame(checkNotNull(positiveOriginalJournalWitness), value))
    }

    private fun positiveCapturedMetadata(value: JSONObject,
        before: PositiveCaptureFence): F31UserDataSnapshotV2 {
        val decodedHmac = positiveMetadataMac("decoded-metadata", value)
        val journalRaw = NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use {
            it.optionalText(NativeUploadCenter.RECORD, NativeUploadJournalCodec.MAX_BYTES)
        }
        val journalRows = journalRaw?.let(NativeUploadJournalCodec::decode).orEmpty()
        val captured = F31UserDataSnapshotV2.capture(context, value, initialOriginalOperationsV2)
        val after = positiveCaptureFence()
        requireBound(before == after && decodedHmac == positiveMetadataMac("decoded-metadata", value) &&
            positiveMetadataWitnesses.size < PositiveStage.values().size)
        val journal = positiveUploadJournalWitness(journalRaw, journalRows, after.journal)
        val owner = value.getJSONArray("clients").objects().map(::decodeClient).single { it.id == host &&
            it.scope?.agentId == "codex" && hash(CodexCommandCodec.target(checkNotNull(it.scope))) == targetHash }
        val qaKey = owner.cacheKey(thread)
        requireBound(positiveMetadataQaKey == null || positiveMetadataQaKey == qaKey)
        if (::presentation.isInitialized) requireBound(qaKey == presentation.key)
        positiveMetadataQaKey = qaKey
        val protected = captured.protectedMetadata
        requireBound(protected.size <= 32)
        val fields = JSONObject()
        for ((field, item) in protected.toSortedMap()) fields.put(field,
            positiveMetadataMac("protected-field:$field", item))
        val composer = JSONObject()
        for (field in listOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations")) {
            val map = F31UserDataRulesV2.obj(checkNotNull(protected[field]))
            val present = map.containsKey(qaKey)
            val item = map[qaKey]
            val kind = when {
                !present -> "absent"
                item == null -> "null"
                item is String -> if (item.isEmpty()) "empty-string" else "string"
                item is List<*> -> if (item.isEmpty()) "empty-array" else "array"
                else -> error("F31_METADATA_WITNESS_COMPOSER_TYPE")
            }
            val entry = JSONObject().put("present", present).put("value", positiveWitnessValue(item))
            composer.put(field, JSONObject().put("otherKeysHmac",
                positiveMetadataMac("composer-other:$field", map.filterKeys { it != qaKey }))
                .put("qa", JSONObject().put("present", present).put("kind", kind)
                    .put("hmac", positiveMetadataMac("composer-qa:$field", entry))))
        }
        val logicalHash = ComputerFileCodec.hash("metadata".toByteArray(Charsets.UTF_8))
        val witness = JSONObject().put("version", 1).put("status", "OBSERVED")
            .put("contract", "f31-default-metadata-typed-witness-v1")
            .put("run", run).put("selector", method).put("captureIndex", positiveMetadataWitnesses.size + 1)
            .put("logicalNameHash", logicalHash).put("relativePath", "no_backup/mobile-v1/$logicalHash.bin")
            .put("cipherSHA256", after.metadata.sha256).put("bytes", after.metadata.stat[6]).put("stat", JSONArray(after.metadata.stat))
            .put("statLayout", JSONArray(listOf("dev", "ino", "uid", "gid", "mode", "nlink", "bytes", "mtimeSeconds", "ctimeSeconds")))
            .put("hmacAlgorithm", "HmacSHA256").put("keyScope", "ONE_CASE_RAM_ONLY")
            .put("decodedMetadataHmac", decodedHmac)
            .put("protectedMetadataHmac", positiveMetadataMac("protected-metadata", protected))
            .put("protectedFields", fields).put("qaKeyHmac", positiveMetadataMac("qa-key", qaKey))
            .put("qaComposerMaps", composer).put("snapshotEvidenceHmac", positiveMetadataMac("snapshot-evidence", captured.evidence()))
            .put("uploadJournalWitness", journal)
            .put("keyId", checkNotNull(positiveMetadataKeyId)).put("sameSettledCipherAcrossDecodeAndSnapshot", true)
            .put("transactionFence", if (::storage.isInitialized) "EXISTING_STORAGE_IO_LOCK" else "BEFORE_MAIN_NO_WRITER")
            .put("externalActualPostMetadataMatchRequired", true).put("completeUserDataProtectionPassed", JSONObject.NULL)
        requireBound(NativeChatRecoveryCanonical.value(witness).toByteArray().size <= 16384)
        positiveMetadataWitnesses[captured] = witness
        return captured
    }

    private fun positiveEvidence(stage: PositiveStage, snapshot: F31UserDataSnapshotV2,
        details: JSONObject = JSONObject()): JSONObject {
        fun writeCapturedPhase(): JSONObject {
            requireBound(existingDraftCase && !positiveStages.has(stage.name))
            val witness = checkNotNull(positiveMetadataWitnesses[snapshot])
            requireBound(witness.getInt("captureIndex") == stage.ordinal + 1 &&
                witness.getString("keyId") == positiveMetadataKeyId && witness.getString("run") == run &&
                witness.getString("selector") == method)
            val cipher = positiveMetadataCipher()
            requireBound(cipher.sha256 == witness.getString("cipherSHA256") &&
                JSONArray(cipher.stat).toString() == witness.getJSONArray("stat").toString() &&
                witness.getString("protectedMetadataHmac") == positiveMetadataMac("protected-metadata", snapshot.protectedMetadata) &&
                witness.getString("snapshotEvidenceHmac") == positiveMetadataMac("snapshot-evidence", snapshot.evidence()))
            val journal = witness.getJSONObject("uploadJournalWitness")
            requireBound(positiveJournalPhysicalSame(positiveRecordCipher(BoundRecord.UPLOAD_JOURNAL), journal))
            positiveStage = stage
            val value = JSONObject().put("version", 1).put("action", existingDraftAction)
                .put("run", run).put("selector", method).put("stage", stage.name)
                .put("planSha256", hash(plan)).put("sourceSetSha256", plan.getString("sourceSetSha256"))
                .put("externalProtectionRefSha256", plan.getString("externalProtectionRefSha256"))
                .put("status", "OBSERVED").put("userData", snapshot.evidence())
                .put("readingAndHistory", snapshot.derived.proofV2()).put("metadataWitness", witness).put("details", details)
                .put("externalFullProtectionRequired", true)
            // Each completed phase has its own immutable receipt; a later failure cannot replace it.
            val name = "$run.positive-${stage.name}.json"
            val bytes = NativeChatRecoveryCanonical.value(value).toByteArray()
            // New action only; all size checks precede O_CREAT. No old writer is widened.
            requireBound(plan.getString("action") == existingDraftAction &&
                positiveStages.length() < PositiveStage.values().size &&
                bytes.size in 1..(9 * 1024 * 1024 / 4) && positivePhaseBytes + bytes.size <= 16L * 1024 * 1024)
            secure(root, true)
            val fd = Os.open(File(root, name).path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            FileOutputStream(fd).use { out -> out.write(bytes); out.fd.sync() }
            val directory = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(directory) } finally { Os.close(directory) }
            positivePhaseBytes += bytes.size
            val receipt = JSONObject().put("status", "OBSERVED").put("basename", name)
                .put("bytes", bytes.size).put("sha256", ComputerFileCodec.hash(bytes))
            positiveStages.put(stage.name, receipt)
            // Persist the full evidence first; raw/presence/ordered-row drift still fails the Native gate.
            requireBound(positiveJournalSemanticsSame(checkNotNull(positiveOriginalJournalWitness), journal))
            return receipt
        }
        return if (::storage.isInitialized) storage.operationTransaction { writeCapturedPhase() }
            else writeCapturedPhase() // BeforeMain never constructs a writer for this fence.
    }

    private fun positiveVersion(metadata: JSONObject): String? =
        metadata.optJSONObject("draftVersions")?.optString(presentation.key)?.takeIf { it.isNotEmpty() }

    private fun positiveVersionEvidence(before: String?, after: String?, api: String): JSONObject =
        JSONObject().put("operation", api).put("beforePresent", before != null)
            .put("afterPresent", after != null).put("beforeHash", before?.let(::hash) ?: JSONObject.NULL)
            .put("afterHash", after?.let(::hash) ?: JSONObject.NULL)
            .put("same", before == after)

    private fun positiveVersionChain(fixture: JSONObject, metadata: JSONObject, restoring: Boolean): Unit {
        requireBound(fixture.getString("ownedRun") == run && fixture.getString("ownedSourceId") ==
            UUID.nameUUIDFromBytes("f31-owned-composer:$run".toByteArray()).toString())
        val operations = listOf("attachmentImportDestination", "importAttachments.await", "MobileSession.text") +
            (if (restoring) listOf("MobileSession.text.restore", "removeAttachment") else emptyList())
        val transitions = fixture.getJSONArray("versionTransitions")
        requireBound(fixture.getInt("transitionCount") == operations.size && transitions.length() == operations.size)
        if (restoring) {
            val staged = evidence.getJSONObject("positiveFixture").getJSONArray("versionTransitions")
            requireBound(staged.length() == 3 && hash(staged) ==
                hash(JSONArray((0 until 3).map { transitions.getJSONObject(it) })))
        }
        var expected = sha(transitions.getJSONObject(0).getString("beforeHash"))
        for ((index, operation) in operations.withIndex()) {
            val transition = transitions.getJSONObject(index)
            exact(transition, setOf("operationHash", "beforeHash", "afterHash"))
            val after = sha(transition.getString("afterHash"))
            requireBound(transition.getString("operationHash") == ComputerFileCodec.hash(operation.toByteArray()) &&
                transition.getString("beforeHash") == expected)
            // Destination preparation does not edit the draft version; the four
            // actual text/attachment mutations produce distinct opaque UUIDs.
            requireBound(if (index == 0) after == expected else after != expected)
            expected = after
        }
        // P3 uses a per-fixture HMAC key kept only in RAM/encrypted recovery storage.
        // Never read/export that key or compare a public digest to its HMAC.
        val actual = checkNotNull(positiveVersion(metadata))
        requireBound(UUID.fromString(actual).toString() == actual && actual != positiveReadyVersion)
    }

    private fun sameOriginalWithFixtureClosure(before: F31UserDataSnapshotV2,
        after: F31UserDataSnapshotV2, closure: JSONObject): Boolean {
        exact(closure, setOf("policyVersion", "matched", "ownedRun", "ownedSourceId", "fieldObservations",
            "productionChain", "cancelBeforeHash", "cancelAfterHash", "cancelExact", "otherComposerKeysExact",
            "originalCipherUnchanged", "ownedSourceRetired", "externalFullProtectionRequired", "restoredPersisted"))
        if (closure.getInt("policyVersion") != 1 || closure.getString("ownedRun") != run ||
            closure.getString("ownedSourceId") != UUID.nameUUIDFromBytes("f31-owned-composer:$run".toByteArray()).toString() ||
            listOf("matched", "cancelExact", "otherComposerKeysExact", "originalCipherUnchanged",
                "ownedSourceRetired", "externalFullProtectionRequired", "restoredPersisted").any { closure.get(it) != true }) return false
        if (sha(closure.getString("cancelBeforeHash")) != sha(closure.getString("cancelAfterHash"))) return false
        val operations = listOf("attachmentImportDestination", "importAttachments.await", "MobileSession.text",
            "MobileSession.text.restore", "removeAttachment")
        val chain = closure.getJSONArray("productionChain")
        if (chain.length() != operations.size) return false
        var previous: String? = null
        for ((index, operation) in operations.withIndex()) {
            val step = chain.getJSONObject(index)
            exact(step, setOf("operation", "beforeHash", "afterHash"))
            val first = sha(step.getString("beforeHash")); val last = sha(step.getString("afterHash"))
            if (step.getString("operation") != operation || previous?.let { it != first } == true) return false
            previous = last
        }
        val fields = setOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations")
        val observed = closure.getJSONArray("fieldObservations")
        if (observed.length() != fields.size) return false
        val seen = mutableSetOf<String>()
        fun entry(value: Map<String, Any?>, field: String): Pair<Boolean, Any?> {
            val map = F31UserDataRulesV2.obj(checkNotNull(value[field]))
            return map.containsKey(presentation.key) to map[presentation.key]
        }
        fun kind(value: Pair<Boolean, Any?>): String = when {
            !value.first -> "absent"
            value.second is List<*> -> if ((value.second as List<*>).isEmpty()) "empty-array" else "array"
            value.second == "" -> "empty-string"
            else -> "string"
        }
        for (index in 0 until observed.length()) {
            val item = observed.getJSONObject(index)
            exact(item, setOf("field", "beforePresent", "afterPresent", "beforeKind", "afterKind",
                "beforeHash", "afterHash", "exactlyEqual"))
            val field = item.getString("field")
            if (field !in fields || !seen.add(field)) return false
            val a = entry(before.protectedMetadata, field); val z = entry(after.protectedMetadata, field)
            if (item.get("beforePresent") != a.first || item.get("afterPresent") != z.first ||
                item.getString("beforeKind") != kind(a) || item.getString("afterKind") != kind(z) ||
                item.get("exactlyEqual") != (a == z) ||
                (sha(item.getString("beforeHash")) == sha(item.getString("afterHash"))) != (a == z)) return false
            // Recheck the exact observed endpoints as well as P3's actual closed API chain.
            val allowed = when (field) {
                "drafts" -> if (a.first) a == z else z.first && z.second == ""
                "draftFiles" -> if (a.first) a == z else z.first && z.second == emptyList<Any?>()
                "attachmentImportGenerations" -> if (a.first) a == z else
                    z.first && z.second is String && UUID.fromString(z.second as String).toString() == z.second
                "draftVersions" -> z.first && z.second is String && z.second != a.second &&
                    UUID.fromString(z.second as String).toString() == z.second
                else -> false
            }
            if (!allowed) return false
        }
        val a = before.protectedMetadata; val z = after.protectedMetadata
        if (a.keys != z.keys || a.keys.any { field ->
            if (field in fields) F31UserDataRulesV2.obj(a[field]).filterKeys { it != presentation.key } !=
                F31UserDataRulesV2.obj(z[field]).filterKeys { it != presentation.key }
            else a[field] != z[field]
        }) return false
        return before.cipher == after.cipher && before.localReferences == after.localReferences &&
            before.remoteReferences == after.remoteReferences
    }

    private fun positiveFixtureTargetCurrent(): Boolean =
        session.attachmentStorageReady && !session.migrationIngressPaused && session.usageForeground &&
            session.isCurrent(presentation) && session.selectedClient.id == host && session.selectedThread == thread &&
            session.clients.any { it.id == host && hash(encodeClient(it)) == descriptorHash } &&
            hash(storage.token(client.id)) == credentialHash

    private suspend fun positiveSnapshot(): Pair<JSONObject, F31UserDataSnapshotV2> =
        withContext(Dispatchers.IO) {
            storage.awaitQueuedWrites()
            storage.operationTransaction {
                val before = positiveCaptureFence()
                val value = metadata()
                value to positiveCapturedMetadata(value, before)
            }
        }

    private fun positiveReadingProtection(before: F31UserDataSnapshotV2,
        after: F31UserDataSnapshotV2): JSONObject {
        return before.derived.preservationV2(after.derived)
    }

    private fun positiveReadingProofAccepted(proof: JSONObject): Boolean {
        exact(proof, setOf("version", "contract", "status", "beforeProofSHA256", "afterProofSHA256",
            "ledger", "history", "originalRecordsPreserved", "completeUserDataProtectionPassed"))
        if (proof.getInt("version") != 2 ||
            proof.getString("contract") != "f31-derived-original-records-preservation-v2" ||
            proof.getString("status") != "PRESERVED" || proof.get("originalRecordsPreserved") != true ||
            !proof.isNull("completeUserDataProtectionPassed")) return false
        sha(proof.getString("beforeProofSHA256")); sha(proof.getString("afterProofSHA256"))
        val ledger = proof.getJSONObject("ledger")
        val history = proof.getJSONObject("history")
        val qa = ledger.getJSONObject("qa")
        return ledger.getString("status") == "OBSERVED" &&
            listOf("originalRowsPresent", "originalProtectedFieldsPreserved", "nonSelectedProtectedPreserved",
                "originalHeadsNoRegression", "scopeSetPreserved", "ledgerRevisionNondecreasing")
                .all { ledger.get(it) == true } &&
            qa.getString("status") in setOf("UNCHANGED", "NO_ORIGINAL_ROW") &&
            qa.getString("readingAcknowledgement") == "NOT_PROVED" &&
            history.getString("status") == "OBSERVED" &&
            history.getString("coverage") == "CAPTURED_ORIGINAL_MESSAGES_ONLY" &&
            listOf("originalMessagesPresent", "originalMessagesUnchanged", "originalOrderPreserved")
                .all { history.get(it) == true }
    }

    private suspend fun positiveOriginalRowsAndOperations(): Unit = withContext(Dispatchers.Main) {
        requireBound(positiveFixtureTargetCurrent() && operations() == preStartupOperations)
        requireBound(center.rows.keys.toSet() == oldRows.keys.toSet() &&
            center.rows.keys.toList() == oldRows.keys.toList() &&
            oldRows.all { (key, digest) -> center.rows[key]?.let { hash(it.json()) } == digest })
    }

    private suspend fun prepareExistingDraftCancellation(): Unit {
        phase = "positive-ready-original-protection"
        val (readyMetadata, ready) = positiveSnapshot()
        positiveReady = ready
        val startupUserDataSame = initialUserDataV2.same(ready)
        val startupReading = positiveReadingProtection(initialUserDataV2, ready)
        val readyRef = positiveEvidence(PositiveStage.READY_ORIGINAL, ready, JSONObject()
            .put("originalUserDataSame", startupUserDataSame).put("readingPreservation", startupReading))
        evidence.put("positiveStartupReadingV2", readyRef)
        requireBound(startupUserDataSame && positiveReadingProofAccepted(startupReading))
        positiveOriginalRowsAndOperations()
        val raw = withContext(Dispatchers.IO) { storage.read(NativeUploadCenter.RECORD) }
        requireBound(hash(raw ?: JSONObject.NULL) == initialUploadJournalLogicalHash &&
            raw?.let(NativeUploadJournalCodec::decode).orEmpty().all { it.terminal })
        withContext(Dispatchers.Main) {
            requireBound(current())
            positiveOriginalText = session.text
            positiveOriginalAttachments = session.attachments.map { it.manifest }
            positiveOriginalPresentationHash = presentationHash
            positiveReadyVersion = positiveVersion(readyMetadata)
        }
        phase = "positive-fixture-stage"
        positiveStage = PositiveStage.FIXTURE_STAGED
        withContext(Dispatchers.IO) {
            writeNew("$method.fixture-once.json", JSONObject().put("version", 1)
                .put("action", existingDraftAction).put("run", run).put("selector", method)
                .put("planSha256", hash(plan)).put("phase", "BEFORE_FIXTURE_STAGE"))
            positiveFixtureAttempted = true
        }
        val fixture = try {
            F31OwnedDraftFixture.stage(context, session, storage, run, ::positiveFixtureTargetCurrent)
        } catch (held: F31OwnedDraftFixture.Held) {
            positiveFixture = held.fixture
            evidence.put("positiveFixtureStageHold", true)
            throw held // Unknown stage effects never grant restore or a second import.
        }
        positiveFixture = fixture
        positiveFixtureStaged = true
        val (stagedMetadata, staged) = positiveSnapshot()
        val fixtureEvidence = fixture.positiveEvidence()
        evidence.put("positiveFixture", fixtureEvidence)
        positiveEvidence(PositiveStage.FIXTURE_STAGED, staged, JSONObject().put("fixture", fixtureEvidence)
            .put("versionTransition", positiveVersionEvidence(positiveReadyVersion,
                positiveVersion(stagedMetadata), "fixture production text/import APIs")))
        positiveVersionChain(fixtureEvidence, stagedMetadata, restoring = false)
        withContext(Dispatchers.Main) {
            requireBound(positiveFixtureTargetCurrent())
            val originalFiles = checkNotNull(positiveOriginalAttachments)
            val files = session.attachments.map { it.manifest }
            requireBound(session.text.isNotBlank() && session.text.startsWith(checkNotNull(positiveOriginalText)) &&
                session.text.contains(run) && files.size == originalFiles.size + 1 &&
                files.take(originalFiles.size) == originalFiles && files.last().sizeBytes == 8192L)
            val cancelledSourceId = UUID.nameUUIDFromBytes("f31:$run:$method".toByteArray()).toString()
            requireBound(files.last().id != cancelledSourceId && staged.localReferences >= 1)
            presentationHash = memoryProjection() // New positive baseline, never an old user's restoration.
            requireBound(current())
        }
        positiveOriginalRowsAndOperations()
        val (beforeCancelMetadata, beforeCancel) = positiveSnapshot()
        val stagedSame = staged.same(beforeCancel)
        val stagedReading = positiveReadingProtection(staged, beforeCancel)
        positiveBeforeCancel = beforeCancel
        positiveCancelVersion = positiveVersion(beforeCancelMetadata)
        operationHashes = withContext(Dispatchers.Main) { operations() }
        phase = "positive-before-cancel"
        positiveEvidence(PositiveStage.BEFORE_CANCEL, beforeCancel, JSONObject()
            .put("nonEmptyDraft", true).put("localReferencesAtLeastOne", beforeCancel.localReferences >= 1)
            .put("stagedUserDataSame", stagedSame).put("readingPreservation", stagedReading)
            .put("opaqueDraftVersionHash", positiveCancelVersion?.let(::hash) ?: JSONObject.NULL))
        requireBound(stagedSame && beforeCancel.localReferences >= 1 && positiveReadingProofAccepted(stagedReading))
    }
    // Additive bounded metadata only. No diagnostics re-dispatch Main/IO or read product state after failure.
    private enum class DiagnosticSubphase {
        UNOBSERVED, RESTORED_SESSION, READINESS_WAIT, READINESS_DRAIN, READINESS_RECHECK,
        BEFORE_BODY_DRAIN, USERDATA_CAPTURE, JOURNAL_CHECK, OPERATIONS_CHECK, ORIGINAL_ROWS_CHECK,
        AFTER_CALLER, AFTER_IO, AFTER_ROW, AFTER_TERMINAL, AFTER_SOURCE, AFTER_DRAIN,
        AFTER_CURRENT, AFTER_OPERATIONS, AFTER_ROWS, AFTER_METADATA, AFTER_USERDATA,
        AFTER_SOURCE_PRESENCE, AFTER_RESULT, AFTER_OTHER
    }
    private enum class DiagnosticRole { BEFORE, AFTER, AFTER_OPERATIONS, RULE }
    private enum class DiagnosticBoolean {
        CURRENT, SELECTED_CLIENT, SELECTED_THREAD, VISIBLE_PRESENT, NETWORK_SNAPSHOT_PRESENT, WORKSPACE_IDLE, HISTORY_IDLE,
        VISIBLE_KEY, VISIBLE_THREAD, VISIBLE_CLIENT, VISIBLE_TARGET, ACCEPTED,
        READINESS_DRAIN_FINISHED, FINAL_CURRENT, FINAL_WORKSPACE_IDLE, FINAL_HISTORY_IDLE,
        FINAL_PROOF_EQUAL, BEFORE_BODY_DRAIN_FINISHED
    }
    @Volatile private var diagnosticSubphase = DiagnosticSubphase.UNOBSERVED
    @Volatile private var diagnosticKnown = emptyMap<DiagnosticBoolean, Boolean>()
    private val diagnosticFirst = mutableMapOf<DiagnosticRole, JSONObject>()
    private fun diagnosticBoolean(name: DiagnosticBoolean, value: Boolean): Boolean {
        runCatching { diagnosticKnown = diagnosticKnown + (name to value) }
        return value
    }
    private fun diagnosticVisible(value: NativeVisiblePhoneReading?): NativeVisiblePhoneReading? {
        diagnosticBoolean(DiagnosticBoolean.VISIBLE_PRESENT, value != null)
        return value
    }
    private fun diagnosticCause(error: Throwable): String = when (error) {
        is TimeoutCancellationException -> "TIMEOUT"
        is CancellationException -> "CANCELLED"
        is UninitializedPropertyAccessException -> "UNINITIALIZED_PROPERTY"
        is org.json.JSONException -> "JSON"
        is android.system.ErrnoException -> "ERRNO"
        is java.io.IOException -> "IO"
        is SecurityException -> "SECURITY"
        is org.junit.runners.model.MultipleFailureException -> "MULTIPLE_FAILURE"
        is AssertionError -> "ASSERTION"
        is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
        is IllegalStateException -> "ILLEGAL_STATE"
        else -> "OTHER"
    }
    private fun diagnosticFailure(role: DiagnosticRole, error: Throwable): Unit {
        runCatching {
            if (role !in diagnosticFirst) {
                val causes = JSONArray()
                val first = JSONObject().put("subphase", diagnosticSubphase.name).put("causes", causes)
                    .put("causeProjection", "UNAVAILABLE")
                diagnosticFirst[role] = first // Reserve the first role even if a cause accessor fails.
                var cause: Throwable? = error
                var unavailable = false
                repeat(4) {
                    cause?.let { item ->
                        causes.put(diagnosticCause(item))
                        cause = try { item.cause } catch (_: Throwable) { unavailable = true; null }
                    }
                }
                first.put("causeProjection", if (unavailable) "UNAVAILABLE" else if (cause != null) "LIMIT_REACHED" else "OBSERVED")
            }
        } // Best effort; the original Throwable is always handled/rethrown by the original path.
    }
    private fun safeDiagnosticV2(): JSONObject {
        val observed = JSONObject()
        for (key in DiagnosticBoolean.values()) observed.put(key.name, diagnosticKnown[key] ?: JSONObject.NULL)
        val failures = JSONObject()
        for (role in DiagnosticRole.values()) failures.put(role.name, diagnosticFirst[role] ?: JSONObject.NULL)
        return JSONObject().put("version", 2).put("subphase", diagnosticSubphase.name)
            .put("firstFailureByRole", failures).put("lastKnownBooleans", observed)
            .put("observation", "LAST_KNOWN_ONLY_NO_FAILURE_RECHECK")
    }
    private fun requireBound(ok: Boolean) { check(ok) { "F31_UPLOAD_ONLY_GUARD" } }
    private fun hash(v: Any) = NativeChatRecoveryCanonical.hash(v)
    private fun sha(v: String): String = v.also { requireBound(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun exact(v: JSONObject, names: Set<String>) = requireBound(v.keys().asSequence().toSet() == names)
    private fun secure(file: File, directory: Boolean) {
        val s = Os.lstat(file.absolutePath)
        requireBound(s.st_uid == android.os.Process.myUid() && (s.st_mode and 511) == if (directory) 448 else 384)
        requireBound(if (directory) OsConstants.S_ISDIR(s.st_mode) else OsConstants.S_ISREG(s.st_mode))
    }
    private fun readPlan(file: File): JSONObject {
        secure(root, true); secure(file, false)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val bytes = FileInputStream(fd).use { input ->
            val s = Os.fstat(fd)
            requireBound(OsConstants.S_ISREG(s.st_mode) && s.st_uid == android.os.Process.myUid() &&
                (s.st_mode and 511) == 384 && s.st_size in 1..16384)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                requireBound(out.size() + n <= 16384); out.write(buffer, 0, n)
            }
            requireBound(out.size().toLong() == s.st_size)
            out.toByteArray()
        }
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return JSONObject(raw).also { requireBound(NativeChatRecoveryCanonical.value(it) == raw) }
    }
    private fun writeNew(name: String, value: JSONObject) {
        secure(root, true)
        val fd = Os.open(File(root, name).path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        FileOutputStream(fd).use { out ->
            val bytes = NativeChatRecoveryCanonical.value(value).toByteArray()
            requireBound(bytes.size <= 65536); out.write(bytes); out.fd.sync()
        }
        val directory = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(directory) } finally { Os.close(directory) }
    }
    private fun metadata(): JSONObject = NativeSessionStorage.ExistingReader.open(context,
        "mobile-v1", "agent-hub-mobile-v1").use {
        JSONObject(checkNotNull(it.optionalText("metadata", 1048576)))
    }
    // Diagnostic categories; legacy selectors retain all four required predicates.
    // Retain missing/null, array order, exact targets/pins/credential refs/preferences.
    private fun userMetadataProjection(value: JSONObject): Map<String, String> {
        NativeMigrationMetadata.validate(value, allowSourceEasyWork = true)
        val groups = linkedMapOf(
            "draftTextAndVersions" to listOf("drafts", "draftVersions", "attachmentImportGenerations"),
            "attachmentReferences" to listOf("draftFiles", "photoSources"),
            "intentContext" to listOf("newProjects", "dismissedReceiptFailures"),
            "preferences" to listOf("appearance", "language", "demo", "generation", "generationPreferences", "replyModes"),
            "navigation" to listOf("selected", "selectedThreads", "expanded", "sidebar", "recentProjects"),
            "targetAuthorization" to listOf("version", "clients"),
            "refreshWorkspaces" to listOf("workspaces"))
        requireBound(value.keys().asSequence().all { it in groups.values.flatten() })
        val stableClients = JSONArray()
        val refreshConnections = JSONArray()
        for (original in value.getJSONArray("clients").objects()) {
            val stable = JSONObject(original.toString())
            val refresh = JSONObject().put("id", original.getString("id"))
                .put("mode", stable.remove("mode"))
            stable.optJSONObject("agentConnection")?.let { connection ->
                if (connection.has("candidates")) refresh.put("candidates", connection.remove("candidates"))
            }
            stableClients.put(stable)
            refreshConnections.put(refresh)
        }
        return groups.mapValues { (name, fields) ->
            val projection = JSONObject()
            for (field in fields) if (value.has(field)) {
                projection.put(field, if (name == "targetAuthorization" && field == "clients")
                    stableClients else value.get(field))
            }
            hash(projection)
        } + ("refreshConnections" to hash(refreshConnections))
    }

    private enum class BoundRecord(val logicalName: String, val maximumPlainBytes: Int) {
        METADATA("metadata", 1048576),
        UPLOAD_JOURNAL(NativeUploadCenter.RECORD, NativeUploadJournalCodec.MAX_BYTES)
    }
    /** NativeSessionStorage.digest/atomic/file: UTF-8 logical-name SHA256 + .bin.
     * ExistingReader supports only the unmigrated default namespace. Reject its
     * generation pointer and AtomicFile sidecars; never resolve/recover them here.
     * No caller-supplied record name/path, key read, decryption or plaintext output.
     */
    private fun defaultRecordsRoot(): File {
        val base = context.noBackupFilesDir
        // Android owns this parent; API 37 uses 0771 here. The owned plan and
        // encrypted-record namespace still require their private 0700 modes.
        val parent = Os.lstat(base.absolutePath)
        requireBound(OsConstants.S_ISDIR(parent.st_mode) &&
            parent.st_uid == android.os.Process.myUid() && parent.st_gid == parent.st_uid &&
            (parent.st_mode and 511) in setOf(448, 505) &&
            base.canonicalFile == File(context.applicationInfo.dataDir, "no_backup").canonicalFile)
        val folder = File(base, "mobile-v1")
        secure(folder, true)
        requireBound(folder.canonicalFile == File(base.canonicalFile, "mobile-v1"))
        val pointer = java.security.MessageDigest.getInstance("SHA-256")
            .digest("migration-active-generation-v1".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".bin"
        for (suffix in listOf("", ".bak", ".new")) requireBound(java.nio.file.Files.notExists(
            File(folder, pointer + suffix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return folder
    }
    private fun fileIdentity(s: android.system.StructStat): List<Long> = listOf(
        s.st_dev, s.st_ino, s.st_uid.toLong(), s.st_gid.toLong(), s.st_mode.toLong(),
        s.st_nlink, s.st_size, s.st_mtime, s.st_ctime)
    private fun encryptedRecordHash(record: BoundRecord): String? {
        val folder = defaultRecordsRoot()
        val name = java.security.MessageDigest.getInstance("SHA-256")
            .digest(record.logicalName.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".bin"
        val file = File(folder, name)
        fun settled(): Unit {
            requireBound(defaultRecordsRoot() == folder)
            for (suffix in listOf(".bak", ".new")) requireBound(java.nio.file.Files.notExists(
                File(folder, name + suffix).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
        fun valid(s: android.system.StructStat): Unit {
            requireBound(OsConstants.S_ISREG(s.st_mode) && s.st_uid == android.os.Process.myUid() &&
                (s.st_mode and 511) == 384 && s.st_nlink == 1L && s.st_size in 29..(record.maximumPlainBytes + 65569L))
        }
        settled()
        val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0) }
        catch (error: android.system.ErrnoException) {
            if (record != BoundRecord.UPLOAD_JOURNAL || error.errno != OsConstants.ENOENT) throw error
            settled()
            requireBound(java.nio.file.Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return null // Exact absent journal, not an unknown hash or a digest of empty bytes.
        }
        try {
            val before = Os.fstat(fd); valid(before)
            val pathBefore = Os.lstat(file.path); valid(pathBefore)
            requireBound(fileIdentity(pathBefore) == fileIdentity(before))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var count = 0L
            while (count < before.st_size) {
                val n = Os.read(fd, buffer, 0, minOf(buffer.size.toLong(), before.st_size - count).toInt())
                requireBound(n > 0); digest.update(buffer, 0, n); count += n
            }
            requireBound(Os.read(fd, ByteArray(1), 0, 1) == 0 && count == before.st_size)
            val after = Os.fstat(fd); valid(after)
            val pathAfter = Os.lstat(file.path); valid(pathAfter)
            requireBound(fileIdentity(after) == fileIdentity(before) && fileIdentity(pathAfter) == fileIdentity(before))
            settled()
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { Os.close(fd) }
    }
    private fun verifyCipherBindings(): Unit {
        phase = "cipher-metadata-binding"
        requireBound(encryptedRecordHash(BoundRecord.METADATA) == plan.getString("metadataFileSha256"))
        phase = "cipher-upload-journal-binding"
        val expected = if (plan.isNull("uploadJournalFileSha256")) null else plan.getString("uploadJournalFileSha256")
        requireBound(encryptedRecordHash(BoundRecord.UPLOAD_JOURNAL) == expected)
    }
    private fun safeRuleFailure(): Unit {
        // Admission/Before/After may throw JSON/IO/product errors containing private
        // content. Emit fixed phase/role only, never exception text, cause or stack.
        if (::root.isInitialized && ::run.isInitialized && method in selectors) try {
            writeNew("$run.rule-failure.json", JSONObject().put("version", 1).put("run", run)
                .put("selector", method).put("phase", phase).put("outcome", "FAILED_OR_UNKNOWN")
                .put("safeDiagnosticV2", safeDiagnosticV2()))
        } catch (_: Throwable) { /* Original files remain; JUnit still fails with a fixed code. */ }
    }
    /** Runs before normal Main activation: no default writer, credential or network construction. */
    @get:Rule(order = 0) val inputBeforeForeground: TestRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate(): Unit {
                try {
                method = description.methodName
                requireBound(method in selectors && context.packageName == app)
                val args = InstrumentationRegistry.getArguments()
                requireBound(args.keySet().all { it in setOf("class", "f31UploadOnlyRun", "nativeForeground", "debug", "log", "timeout_msec", "disableAnalytics") })
                requireBound(args.getString("class") == "com.agenthub.android.NativeDefaultAttachmentUploadOnlyTest#$method" && args.getString("nativeForeground") == "1")
                run = checkNotNull(args.getString("f31UploadOnlyRun"))
                requireBound(UUID.fromString(run).toString() == run)
                root = File(context.filesDir, "f31-default-upload-only-v1")
                plan = readPlan(File(root, "$run.json"))
                exact(plan, setOf("version", "action", "run", "selector", "package", "appSha256", "runnerSha256", "signerSetSha256", "sourceSetSha256", "target", "endpoint", "fingerprint", "threadId", "metadataFileSha256", "uploadJournalFileSha256", "externalProtectionRefSha256", "quietOriginalOperationsRefSha256", "hostSyntheticRetentionBindingSha256"))
                requireBound(plan.getInt("version") == 1 && plan.getString("action") ==
                    (if (existingDraftCase) existingDraftAction else "f31-current-default-attachment-upload-only") && plan.getString("run") == run &&
                    plan.getString("selector") == args.getString("class") && plan.getString("package") == app && plan.getString("threadId") == thread)
                for (k in listOf("appSha256", "runnerSha256", "signerSetSha256", "sourceSetSha256", "fingerprint", "metadataFileSha256", "externalProtectionRefSha256", "quietOriginalOperationsRefSha256", "hostSyntheticRetentionBindingSha256")) {
                    requireBound(plan.get(k) is String); sha(plan.getString(k))
                }
                requireBound(plan.isNull("uploadJournalFileSha256") || plan.get("uploadJournalFileSha256") is String)
                if (!plan.isNull("uploadJournalFileSha256")) sha(plan.getString("uploadJournalFileSha256"))
                requireBound(hash(plan.getJSONObject("target")) == targetHash)
                requireBound(com.agenthub.core.DomainContract.validate(plan.getJSONObject("target").toString(), "target") == null)
                requireBound(plan.getString("signerSetSha256") == "4f719bb23e1366a472b42507d73147e69b005aaf84f8d12ae020208dc1618965")
                val installed = NativeChatNotificationNaturalStore.installed(context)
                requireBound(installed.appHash == plan.getString("appSha256") && installed.runnerHash == plan.getString("runnerSha256") && installed.signerSetHash == plan.getString("signerSetSha256"))
                verifyCipherBindings()
                phase = "logical-profile-capture"
                val originalMetadataFence = if (existingDraftCase) positiveCaptureFence() else null
                val original = metadata()
                initialMetadataHash = hash(original)
                initialUserMetadata = userMetadataProjection(original)
                val originals = original.getJSONArray("clients").objects().map(::decodeClient)
                requireBound(originals.size in 2..16)
                operationTargets = originals.flatMap { c ->
                    c.operationTargets(null) + c.operationTargets(original.getJSONObject("selectedThreads").optString(c.cacheKey(null), "").takeIf { it.isNotEmpty() }) +
                        if (c.id == host) c.operationTargets(thread) else emptyList()
                }.toSet()
                requireBound(operationTargets.size <= 128)
                NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use { existing ->
                    val raw = existing.optionalText(NativeUploadCenter.RECORD, NativeUploadJournalCodec.MAX_BYTES)
                    initialUploadJournalLogicalHash = hash(raw ?: JSONObject.NULL)
                    requireBound(raw?.let(NativeUploadJournalCodec::decode).orEmpty().all { it.terminal })
                    preStartupOperations = operationTargets.associateWith { key ->
                        val body = existing.optionalText("operations:$key", 1048576)
                        val records = body?.let { NativeOperationJournal.validateExistingMigrationRecords(key, JSONObject(it)) }.orEmpty()
                        quiet(records)
                        if (newUserDataCase || existingDraftCase) initialOriginalOperationsV2.addAll(records)
                        hash(JSONArray(records.map { it.body() }))
                    }
                    if (newUserDataCase || existingDraftCase) {
                        initialUserDataV2 = if (existingDraftCase)
                            positiveCapturedMetadata(original, checkNotNull(originalMetadataFence))
                        else F31UserDataSnapshotV2.capture(context, original, initialOriginalOperationsV2)
                        evidence.put("userDataV2BeforeMain", initialUserDataV2.evidence())
                            .put("userDataV2ExternalProtectionRefSha256", plan.getString("externalProtectionRefSha256"))
                    }
                }
                verifyCipherBindings() // Same encrypted pins also fence the logical capture interval.
                // No fresh run can silently retry this task's already consumed role.
                phase = "original-method-once-admission"
                requireBound(!File(root, "$method.once.json").exists())
                if (existingDraftCase) {
                    requireBound(java.nio.file.Files.notExists(File(root, "$method.once.json").toPath(),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    requireBound(java.nio.file.Files.notExists(File(root, "$method.fixture-once.json").toPath(),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS))
                }
                if (existingDraftCase) positiveEvidence(PositiveStage.BEFORE_MAIN, initialUserDataV2)
                phase = "normal-main-activation"
                if (afterMainCloseCase) {
                    var primary: Throwable? = null
                    try { base.evaluate() } catch (error: Throwable) { primary = error }
                    var finalization: Throwable? = null
                    try { runBlocking { finalizePositiveAfterMainCloses(primary != null) } }
                    catch (error: Throwable) { finalization = error }
                    primary?.let { failure ->
                        finalization?.takeIf { it !== failure }?.let { failure.addSuppressed(it) }
                        throw failure
                    }
                    finalization?.let { throw it }
                } else base.evaluate()
                } catch (_: AssumptionViolatedException) {
                    throw AssumptionViolatedException("F31_UPLOAD_ONLY_NOT_COVERED")
                } catch (error: Throwable) {
                    diagnosticFailure(DiagnosticRole.RULE, error)
                    safeRuleFailure()
                    throw AssertionError("F31_UPLOAD_ONLY_FAILED_AT_$phase")
                } finally {
                    clearPositiveMetadataKey()
                }
            }
        }
    }
    @get:Rule(order = 1) val foreground: TestRule = NativeTestForeground()

    private fun memoryProjection(): String = hash(JSONObject().put("selected", session.selectedClient.id)
        .put("thread", session.selectedThread).put("targetKey", session.targetKey).put("textHash", hash(session.text))
        .put("attachments", JSONArray(session.attachments.map { it.manifest.body() })))
    private fun current(): Boolean = session.attachmentStorageReady && !session.migrationIngressPaused && session.usageForeground &&
        session.isCurrent(presentation) && memoryProjection() == presentationHash &&
        session.clients.any { it.id == host && hash(encodeClient(it)) == descriptorHash } &&
        hash(storage.token(client.id)) == credentialHash
    private suspend fun operations(): Map<String, String> {
        val journal = NativeOperationJournal(storage)
        return operationTargets.associateWith { key ->
            val records = journal.records(key)
            quiet(records)
            hash(JSONArray(records.map { it.body() }))
        }
    }
    private fun quiet(records: List<SavedOperation>): Unit {
        requireBound(records.none { !it.detached && it.phase in setOf("ready", "preparing", "prepared", "submitting", "received", "uncertain", "outcome-unknown", "opening", "submitted", "queued") })
    }
    private fun positivePostCloseIdentity(): Boolean {
        val main = positiveBorrowedMain ?: return false
        return main.isDestroyed && main.session === session && session.migrationStorage === storage &&
            storage.coordinationKey == File(context.noBackupFilesDir, "mobile-v1").canonicalPath &&
            session.uploadJobs === center && !session.usageForeground && session.attachmentStorageReady &&
            !session.migrationIngressPaused && session.isCurrent(presentation) &&
            session.selectedClient.id == host && session.selectedThread == thread &&
            hash(CodexCommandCodec.target(checkNotNull(session.selectedClient.scope))) == targetHash &&
            session.clients.any { it.id == host && hash(encodeClient(it)) == descriptorHash } &&
            memoryProjection() == presentationHash && hash(storage.token(client.id)) == credentialHash
    }

    private suspend fun positivePostCloseOriginalRows(): Unit = withContext(Dispatchers.Main) {
        requireBound(positivePostCloseIdentity() && operations() == preStartupOperations)
        requireBound(center.storageError == null && center.rows.keys.toList() == oldRows.keys.toList() &&
            oldRows.all { (key, digest) -> center.rows[key]?.let { hash(it.json()) } == digest })
        requireBound(positivePostCloseIdentity())
    }

    /** Normal Activity finish only; the borrowed Session, writer and IO stay product-owned. */
    private suspend fun closeExactPositiveMain(): Unit {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = checkNotNull(positiveBorrowedMain)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity === main && stage == Stage.DESTROYED) destroyed.set(true)
        }
        var registered = false
        try {
            withContext(Dispatchers.Main) {
                requireBound(afterMainCloseCase && admitted && positiveFixtureRestored &&
                    main.javaClass == MainActivity::class.java && main.packageName == app &&
                    main.session === session && session.migrationStorage === storage &&
                    session.uploadJobs === center && !main.isFinishing && !main.isDestroyed && current())
                val live = Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
                    .filter { it.javaClass == MainActivity::class.java && it.packageName == app }.toSet()
                requireBound(live == setOf(main))
                monitor.addLifecycleCallback(callback); registered = true
                main.finish()
            }
            withTimeout(45000) {
                while (!destroyed.get()) delay(25)
                withContext(Dispatchers.Main) {
                    requireBound(main.isDestroyed && positivePostCloseIdentity())
                    requireBound(Stage.values().filter { it != Stage.DESTROYED }
                        .flatMap { monitor.getActivitiesInStage(it) }
                        .none { it.javaClass == MainActivity::class.java && it.packageName == app })
                }
                withContext(Dispatchers.IO) {
                    val idle = CompletableDeferred<Unit>()
                    try {
                        // The callback owns no Session/key and cannot block retirement after timeout.
                        instrumentation.waitForIdle { idle.complete(Unit) }
                        idle.await()
                        storage.awaitQueuedWrites()
                    } finally { idle.cancel() }
                }
                positivePostCloseOriginalRows()
            }
        } finally {
            if (registered) withContext(NonCancellable + Dispatchers.Main) { monitor.removeLifecycleCallback(callback) }
        }
    }

    private suspend fun publishPositivePostCloseResult(flags: JSONObject, failures: MutableList<String>): Unit {
        requireBound(afterMainCloseCase && !positivePostCloseResultAttempted)
        var sourcePresent: Boolean? = null
        if (admitted) try {
            ownedSourceId?.let { id -> sourcePresent = withContext(Dispatchers.IO) { storage.read("draft-file:$id") != null } }
            flags.put("positiveOwnedSourcePresenceObserved", true)
        } catch (error: Throwable) {
            diagnosticFailure(DiagnosticRole.AFTER, error)
            flags.put("positiveOwnedSourcePresenceObserved", false); failures.add("positiveOwnedSourcePresenceObserved")
        }
        val value = JSONObject().put("version", 3).put("action", existingDraftAction)
            .put("acceptanceContract", "f31-existing-draft-reading-after-main-close-parent-required-v2")
            .put("run", run).put("selector", method).put("phase", phase)
            .put("positiveStage", positiveStage.name).put("positiveStages", positiveStages)
            .put("positivePhaseTotalBytes", positivePhaseBytes)
            .put("safeDiagnosticV2", safeDiagnosticV2()).put("bodyOutcome", bodyOutcome)
            .put("cleanupOutcome", if (failures.isEmpty()) "DRAINED" else "FAILED_OR_UNKNOWN")
            .put("consumed", consumed).put("planSha256", hash(plan)).put("flags", flags)
            .put("failedSteps", JSONArray(failures)).put("evidence", evidence)
            .put("ownedSourceId", ownedSourceId ?: JSONObject.NULL)
            .put("ownedPayloadKey", ownedPayloadKey ?: JSONObject.NULL)
            .put("ownedSourceMetadataPresent", sourcePresent ?: JSONObject.NULL)
            .put("fixtureStaged", positiveFixtureStaged).put("fixtureRestored", positiveFixtureRestored)
            .put("fixtureAttempted", positiveFixtureAttempted)
            .put("fixtureRecoveryRequired", positiveFixtureAttempted && !positiveFixtureRestored)
            .put("externalFullProtectionRequired", true).put("completeUserDataProtectionPassed", JSONObject.NULL)
            .put("externalProtectionRefSha256", plan.getString("externalProtectionRefSha256"))
            .put("borrowedAppSessionRetained", admitted).put("androidGuiAcceptance", false)
        // One attempt precedes O_CREAT; a partial or unknown write is never overwritten.
        positivePostCloseResultAttempted = true
        withContext(Dispatchers.IO) {
            requireBound(NativeChatRecoveryCanonical.value(value).toByteArray().size <= 65536)
            writeNew("$run.result.json", value)
        }
    }

    private suspend fun finalizePositiveAfterMainCloses(baseFailed: Boolean): Unit = withContext(NonCancellable) {
        val flags = positiveDeferredFlags ?: JSONObject()
        val failures = positiveDeferredFailures ?: mutableListOf("positiveDeferredCleanupUnavailable")
        if (baseFailed) failures.add("positiveBaseEvaluationFailed")
        suspend fun attempt(name: String, action: suspend () -> Unit): Unit {
            try { withTimeout(45000) { action() }; flags.put(name, true) }
            catch (error: Throwable) {
                diagnosticFailure(DiagnosticRole.AFTER, error); flags.put(name, false); failures.add(name)
            }
        }
        if (admitted && positiveFixtureRestored && failures.isEmpty()) {
            phase = "positive-exact-main-close"
            attempt("positiveMainClosedAndExistingWritesDrained") { closeExactPositiveMain() }
            if (failures.isEmpty()) attempt("positiveFinalOriginalProtection") {
                phase = "positive-final-original-after-main-close"
                positivePostCloseOriginalRows()
                val (_, final) = positiveSnapshot()
                val userData = sameOriginalWithFixtureClosure(initialUserDataV2, final, checkNotNull(positiveClosure))
                val reading = positiveReadingProtection(initialUserDataV2, final)
                val receipt = positiveEvidence(PositiveStage.FINAL_ORIGINAL, final, JSONObject()
                    .put("originalUserDataWithActualFixtureClosureSame", userData)
                    .put("readingPreservation", reading).put("externalFullProtectionRequired", true)
                    .put("exactMainDestroyedBeforeCapture", true).put("existingWritesDrainedAfterMainClose", true))
                evidence.put("positiveFinalReadingV2", receipt)
                requireBound(userData && positiveReadingProofAccepted(reading))
                positivePostCloseOriginalRows()
            }
        } else {
            flags.put("positiveMainClosedAndExistingWritesDrained", false)
            failures.add("positivePostClosePreconditionsUnavailable")
        }
        if (!positiveStages.has(PositiveStage.FINAL_ORIGINAL.name)) failures.add("positiveFinalProtectionUnconfirmed")
        try { publishPositivePostCloseResult(flags, failures) }
        catch (error: Throwable) { diagnosticFailure(DiagnosticRole.AFTER, error); failures.add("positiveImmutableResultNotConfirmed") }
        if (failures.isNotEmpty()) throw AssertionError("F31_POST_CLOSE_PROTECTION_FAILED_OR_UNKNOWN")
    }

    @Before fun borrowRestoredNormalSession(): Unit { runBlocking<Unit> {
        try {
        diagnosticSubphase = DiagnosticSubphase.RESTORED_SESSION
        phase = "normal-restored-session"
        withTimeout(30000) {
            while (!admitted) {
                withContext(Dispatchers.Main) {
                    val main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filter { it.javaClass == MainActivity::class.java }.filterIsInstance<MainActivity>().singleOrNull()
                    if (main != null && !main.session.restoring) {
                        session = main.session; storage = session.migrationStorage
                        requireBound(session.attachmentStorageReady && !session.localRecoveryRequired && session.usageForeground && !storage.localInspectionOnly)
                        requireBound(storage.coordinationKey == File(context.noBackupFilesDir, "mobile-v1").canonicalPath)
                        storage.requireRawWriterOwner()
                        client = session.clients.single { it.scope?.hostId == host && it.scope?.agentId == "codex" }
                        requireBound(!client.isDemo && client.id == host && hash(CodexCommandCodec.target(checkNotNull(client.scope))) == targetHash)
                        requireBound(session.clients.count { it.scope?.agentId == "easywork" && hash(CodexCommandCodec.target(checkNotNull(it.scope))) == "6ee4a03911278315dcdb5ba58c21817eb321d2ea35175a191b17e957407d0f8d" } == 1)
                        requireBound(client.endpoint == plan.getString("endpoint") && NativeUploadTarget.capture(client).certificate == plan.getString("fingerprint"))
                        val uri = java.net.URI(client.endpoint)
                        requireBound(uri.scheme == "https" && uri.port in setOf(56318, 56319) && uri.userInfo == null && uri.query == null && uri.fragment == null)
                        requireBound(storage.token(client.id).isNotBlank()) // Loaded only by product; never persisted/reported here.
                        credentialHash = hash(storage.token(client.id)) // In-memory generation fence only; never report.
                        center = checkNotNull(session.uploadJobs)
                        center.restore() // Join the existing journal restore; never starts network.
                        requireBound(center.storageError == null && center.rows.values.all { it.terminal } && center.rows.size < NativeUploadJournalCodec.MAX_JOBS)
                        oldRows = center.rows.mapValues { hash(it.value.json()) }
                        presentation = session.attachmentDestination(); presentationHash = memoryProjection(); descriptorHash = hash(encodeClient(client))
                        // Full production delegation, not a fixture/deny-only client. The two
                        // upload overrides change ONLY the IO accounting context. This makes
                        // actual executor/disconnect exit observable without closing or waiting
                        // on the App's unrelated perpetual history/event requests.
                        val production = AgentRegistry(NativeHostReads { storage.token(it) }, NativeCodexCommands { storage.token(it) })
                        port = object : AgentCommands by production {
                            override suspend fun uploadCapability(client: MobileClient, manifest: NativeUploadManifest) =
                                withContext(io) { production.uploadCapability(client, manifest) }
                            override suspend fun uploadRequest(client: MobileClient, manifest: NativeUploadManifest,
                                action: NativeUploadAction, offset: Long, bytes: ByteArray?) =
                                withContext(io) { production.uploadRequest(client, manifest, action, offset, bytes) }
                        }
                        if (afterMainCloseCase) positiveBorrowedMain = main
                        admitted = true
                    }
                }
                if (!admitted) delay(50)
            }
        }
        if (newUserDataCase || existingDraftCase) {
            phase = "selected-network-snapshot-before-body"
            withTimeout(30000) {
                diagnosticSubphase = DiagnosticSubphase.READINESS_WAIT
                // Restored rows alone cannot prove that the selected network snapshot is ready.
                val accepted = checkNotNull(withContext(Dispatchers.Main) {
                    snapshotFlow {
                        requireBound(diagnosticBoolean(DiagnosticBoolean.CURRENT, current()) &&
                            diagnosticBoolean(DiagnosticBoolean.SELECTED_CLIENT, session.selectedClient.id == host) &&
                            diagnosticBoolean(DiagnosticBoolean.SELECTED_THREAD, session.selectedThread == thread))
                        // Read acknowledgements also need a completed assistant body and an
                        // uncovered viewport. Observe them without gating attachment operations.
                        diagnosticVisible(session.visiblePhoneReading)?.let { visible ->
                            diagnosticBoolean(DiagnosticBoolean.VISIBLE_KEY, visible.key == session.targetKey)
                            diagnosticBoolean(DiagnosticBoolean.VISIBLE_THREAD, visible.threadId == thread)
                            diagnosticBoolean(DiagnosticBoolean.VISIBLE_CLIENT, visible.client.id == host)
                            diagnosticBoolean(DiagnosticBoolean.VISIBLE_TARGET, hash(CodexCommandCodec.target(checkNotNull(visible.client.scope))) == targetHash)
                        }
                        val network=session.selectedConversationNetworkSnapshot
                        diagnosticBoolean(DiagnosticBoolean.NETWORK_SNAPSHOT_PRESENT, network!=null)
                        network?.takeIf {
                            diagnosticBoolean(DiagnosticBoolean.WORKSPACE_IDLE, !session.loading) &&
                                diagnosticBoolean(DiagnosticBoolean.HISTORY_IDLE, !session.historyLoading)
                        }?.also {
                            requireBound(it.key==session.targetKey && it.threadId==thread && it.client.id==host &&
                                hash(CodexCommandCodec.target(checkNotNull(it.client.scope)))==targetHash)
                        }
                    }.first { it != null }
                })
                diagnosticBoolean(DiagnosticBoolean.ACCEPTED, true)
                // Drain existing writes, then recheck the same proof. Future writers remain subject to the external guard.
                diagnosticSubphase = DiagnosticSubphase.READINESS_DRAIN
                withContext(Dispatchers.IO) { storage.awaitQueuedWrites() }
                diagnosticBoolean(DiagnosticBoolean.READINESS_DRAIN_FINISHED, true)
                diagnosticSubphase = DiagnosticSubphase.READINESS_RECHECK
                withContext(Dispatchers.Main) {
                    requireBound(diagnosticBoolean(DiagnosticBoolean.FINAL_CURRENT, current()) &&
                        diagnosticBoolean(DiagnosticBoolean.FINAL_WORKSPACE_IDLE, !session.loading) &&
                        diagnosticBoolean(DiagnosticBoolean.FINAL_HISTORY_IDLE, !session.historyLoading) &&
                        diagnosticBoolean(DiagnosticBoolean.FINAL_PROOF_EQUAL, session.selectedConversationNetworkSnapshot == accepted))
                }
            }
        }
        if (existingDraftCase) {
            withTimeout(90000) { prepareExistingDraftCancellation() }
            return@runBlocking
        }
        diagnosticSubphase = DiagnosticSubphase.BEFORE_BODY_DRAIN
        withContext(Dispatchers.IO) {
            storage.awaitQueuedWrites()
            diagnosticBoolean(DiagnosticBoolean.BEFORE_BODY_DRAIN_FINISHED, true)
            diagnosticSubphase = DiagnosticSubphase.USERDATA_CAPTURE
            if (newUserDataCase) {
                val observed = metadata()
                val captured = F31UserDataSnapshotV2.capture(context, observed, initialOriginalOperationsV2)
                evidence.put("userDataV2BeforeBody", captured.evidence())
                    .put("fullMetadataEqualBeforeBodyDiagnostic", hash(observed) == initialMetadataHash)
                    .put("derivedRecordBeforeBodyV1",initialUserDataV2.derived.preservation(captured.derived))
                requireBound(initialUserDataV2.same(captured))
            } else requireBound(hash(metadata()) == initialMetadataHash)
            diagnosticSubphase = DiagnosticSubphase.JOURNAL_CHECK
            val raw = storage.read(NativeUploadCenter.RECORD)
            requireBound(hash(raw ?: JSONObject.NULL) == initialUploadJournalLogicalHash)
            requireBound(raw?.let(NativeUploadJournalCodec::decode).orEmpty().all { it.terminal })
        }
        diagnosticSubphase = DiagnosticSubphase.OPERATIONS_CHECK
        withContext(Dispatchers.Main) {
            operationHashes = operations(); requireBound(operationHashes == preStartupOperations && current())
            diagnosticSubphase = DiagnosticSubphase.ORIGINAL_ROWS_CHECK
            if (newUserDataCase) requireBound(center.rows.keys.toList() == oldRows.keys.toList() &&
                oldRows.all { (key, digest) -> center.rows[key]?.let { hash(it.json()) } == digest })
        }
        } catch (error: Throwable) {
            diagnosticFailure(DiagnosticRole.BEFORE, error)
            throw error
        }
    } }

    @Test fun attachmentUploadOnlyCompletesWithRealReceipt(): Unit { runBody(false) }
    @Test fun attachmentUploadOnlyCancelsOriginalWhenSupported(): Unit { runBody(true) }
    @Test fun attachmentUploadOnlyIndependentCancelPreservesOriginalUserDataV2(): Unit { runBody(true) }
    @Test fun attachmentUploadOnlyIndependentCancelPreservesOriginalUserDataV3(): Unit { runBody(true) }
    @Test fun attachmentCancelPreservesExistingDraftAttachmentsAndReadingState(): Unit { runBody(true) }
    @Test fun attachmentCancelPreservesExistingDraftAttachmentsReadingAfterMainCloses(): Unit { runBody(true) }
    private fun runBody(cancel: Boolean): Unit {
        try { runBlocking<Unit> { withTimeout(90000) { withContext(Dispatchers.Main + io) {
            requireBound(admitted && current())
            val sourceId = UUID.nameUUIDFromBytes("f31:$run:$method".toByteArray()).toString()
            val bytes = ByteArray(128 * 1024) { ((it * 31 + run[it % run.length].code) and 255).toByte() }
            val file = CreationFile(sourceId, "f31-$run.bin", "application/octet-stream", bytes.size.toLong(), ComputerFileCodec.hash(bytes))
            val m = NativeUploadManifest.attachment(client, thread, file); manifest = m
            val key = NativeUploadRow.key(NativeUploadTarget.capture(client), m); rowKey = key
            requireBound(key !in center.rows && current())
            phase = "capability-only"
            val cap = checkNotNull(port.uploadCapability(client, m)) { "F31_RESUMABLE_CAPABILITY_REQUIRED" }
            requireBound(cap.protocol == "codex-upload-v1")
            evidence.put("capability", JSONObject().put("protocol", cap.protocol).put("remoteCancel", cap.remoteCancel).put("maxChunkBytes", cap.maxChunkBytes))
            if (cancel && !cap.remoteCancel) {
                evidence.put("notCoveredReason", "REMOTE_CANCEL_UNSUPPORTED")
                throw AssumptionViolatedException("REMOTE_CANCEL_UNSUPPORTED_NOT_ACCEPTED")
            }
            withContext(Dispatchers.IO) {
                requireBound(storage.read("draft-file:$sourceId") == null)
                writeNew("$method.once.json", JSONObject().put("run", run).put("selector", method).put("planSha256", hash(plan))
                    .put("sourceId", sourceId).put("manifest", m.json()).put("rowKey", key))
                consumed = true
                ownedSourceId = sourceId
            }
            phase = "owned-source"
            val prepared = NativeDraftFiles(storage).prepare(file.name, file.mediaType, bytes, sourceId)
            try { requireBound(prepared.disposable && prepared.file.manifest == file); local = prepared.file } finally { prepared.close() }
            withContext(Dispatchers.IO) {
                val saved = NativeDraftFiles.record(JSONObject(checkNotNull(storage.read("draft-file:$sourceId"))))
                requireBound(saved.file == file && !saved.legacy)
                ownedPayloadKey = saved.payload
            }
            val actual = NativeDraftFiles(storage).bytes(file)
            requireBound(actual.size == bytes.size && ComputerFileCodec.hash(actual) == file.sha256 && current())
            phase = "real-shared-center-transfer"
            val origin = NativeUploadOrigin(run, 0, 1, file.sizeBytes) { current() }
            caller = scope.async(origin) { runCatching {
                center.transfer(client, m, sourceId, { offset, count ->
                    requireBound(offset >= 0 && count > 0 && offset + count <= actual.size)
                    actual.copyOfRange(offset.toInt(), offset.toInt() + count)
                }, port) { error("NO_LEGACY_ATTACHMENT_FALLBACK") }
            } }
            if (cancel) {
                val first = checkNotNull(snapshotFlow { center.rows[key] }.first { it?.receipt != null || it?.error != null })
                requireBound(first.error == null && !first.legacy)
                if (!first.terminal) { requireBound(current()); center.control(key, "cancel"); evidence.put("cancelRequested", true) }
            }
            val terminal = checkNotNull(snapshotFlow { center.rows[key] }.first { it?.terminal == true || it?.error != null })
            requireBound(terminal.terminal && terminal.error == null && !terminal.legacy)
            val result = checkNotNull(caller).await()
            val receipt = checkNotNull(terminal.receipt)
            phase = "same-original-status-once"
            requireBound(current())
            val queried = port.uploadRequest(client, m, NativeUploadAction.STATUS, 0, null)
            requireBound(queried.manifest == m && queried.phase == receipt.phase && queried.committedBytes == receipt.committedBytes)
            recordReceipt(queried)
            if (queried.phase == "completed") {
                requireBound(result.isSuccess && queried.committedBytes == file.sizeBytes)
                val value = checkNotNull(queried.result); validateNativeUploadResult(m, value)
                requireBound(value.getString("id") == m.id && hash(result.getOrThrow()) == hash(value))
                evidence.put("publishedSyntheticAttachment", JSONObject().put("hostId", host).put("threadId", thread).put("id", m.id)
                    .put("sourceSha256", file.sha256).put("bytes", file.sizeBytes).put("retentionBindingSha256", plan.getString("hostSyntheticRetentionBindingSha256")))
                if (cancel) {
                    evidence.put("notCoveredReason", "COMPLETED_BEFORE_CANCEL_TERMINAL")
                    throw AssumptionViolatedException("COMPLETED_BEFORE_CANCEL_TERMINAL_NOT_CANCEL_ACCEPTANCE")
                }
            } else {
                requireBound(cancel && queried.phase == "cancelled" && result.isFailure && queried.result == null)
            }
            bodyOutcome = "PASS"
        } } } }
        catch (skip: AssumptionViolatedException) { bodyOutcome = "NOT_COVERED"; throw skip }
        catch (failure: Throwable) { bodyOutcome = "FAILED_OR_UNKNOWN"; throw AssertionError("F31_UPLOAD_ONLY_FAILED_AT_$phase") }
    }
    private fun recordReceipt(receipt: NativeUploadReceipt, name: String = "statusReceipt") {
        // This exact synthetic record only: no response/error text, headers or user metadata.
        evidence.put(name, JSONObject().put("manifest", receipt.manifest.json()).put("phase", receipt.phase)
            .put("committedBytes", receipt.committedBytes).put("wireSha256", ComputerFileCodec.hash(receipt.wire.toByteArray())))
    }
    private suspend fun drainExistingDraftCancellation(): Unit {
        val failures = mutableListOf<String>()
        val flags = JSONObject()
        suspend fun attempt(name: String, action: suspend () -> Unit): Boolean = try {
            withTimeout(45000) { action() }
            flags.put(name, true)
            true
        } catch (error: Throwable) {
            diagnosticFailure(DiagnosticRole.AFTER, error)
            flags.put(name, false)
            failures.add(name)
            false
        }
        attempt("positiveCallerJoined") { withContext(Dispatchers.Main) {
            val key = rowKey
            if (admitted && key != null && center.rows[key]?.terminal == false) center.control(key, "stop")
            caller?.cancelAndJoin()
            parent.cancelAndJoin()
        } }
        attempt("positiveOwnIoClosedAndIdle") { io.close(); io.awaitIdle() }
        if (admitted && positiveFixtureStaged) {
            attempt("positiveTerminalRowInactive") { withContext(Dispatchers.Main) {
                rowKey?.let { key -> center.rows[key]?.let { row ->
                    evidence.put("originalSyntheticRow", JSONObject().put("key", key).put("manifest", row.manifest.json())
                        .put("phase", row.phase).put("pending", row.pending ?: JSONObject.NULL)
                        .put("paused", row.paused).put("cancelRequested", row.cancelRequested)
                        .put("stop", row.stop).put("legacy", row.legacy))
                    row.receipt?.let { recordReceipt(it, "sharedCenterReceipt") }
                    requireBound(row.terminal && row.error == null)
                    withTimeout(5000) { while (key in center.rows) { center.dismissTerminal(key); delay(25) } }
                } }
                requireBound(center.storageError == null)
            } }
            attempt("positiveCancelledSourceReclaimed") {
                requireBound(bodyOutcome == "PASS" && failures.isEmpty())
                local?.let { NativeDraftFiles(storage).discardInboxOwned(it) }
                ownedSourceId?.let { id -> requireBound(withContext(Dispatchers.IO) { storage.read("draft-file:$id") } == null) }
            }
            val cancelPreserved = attempt("positiveBeforeCancelAfterCancelPreserved") {
                requireBound(bodyOutcome == "PASS" && failures.isEmpty())
                phase = "positive-after-cancel"
                val expected = checkNotNull(positiveBeforeCancel)
                val (afterMetadata, after) = positiveSnapshot()
                val same = expected.same(after)
                val sameVersion = positiveCancelVersion == positiveVersion(afterMetadata)
                val reading = positiveReadingProtection(expected, after)
                val afterRef = positiveEvidence(PositiveStage.AFTER_CANCEL, after, JSONObject()
                    .put("positiveUserDataSame", same).put("opaqueDraftVersionSame", sameVersion)
                    .put("versionTransition", positiveVersionEvidence(positiveCancelVersion,
                        positiveVersion(afterMetadata), "cancel no draft mutation permitted"))
                    .put("readingPreservation", reading))
                evidence.put("positiveCancelReadingV2", afterRef)
                evidence.put("positiveFixtureAfterCancel", checkNotNull(positiveFixture).positiveEvidence())
                // This interval has no fixture version or generation exception.
                requireBound(same && sameVersion && positiveReadingProofAccepted(reading))
                positiveOriginalRowsAndOperations()
                requireBound(withContext(Dispatchers.Main) { current() })
                val receipt = evidence.getJSONObject("statusReceipt")
                requireBound(receipt.getString("phase") == "cancelled" &&
                    hash(receipt.getJSONObject("manifest")) == hash(checkNotNull(manifest).json()))
            }
            // Unknown/failed cancellation never grants restoration. Keep the encrypted
            // fixture recovery record and any original/owned files for Root's next decision.
            if (cancelPreserved && failures.isEmpty()) {
                attempt("positiveFixtureCASRestored") {
                    phase = "positive-fixture-cas-restore"
                    val restoration = checkNotNull(positiveFixture).restore()
                    evidence.put("positiveFixtureRestoration", restoration)
                    val (restoredMetadata, restored) = positiveSnapshot()
                    positiveEvidence(PositiveStage.FIXTURE_RESTORED, restored, JSONObject()
                        .put("fixture", restoration).put("versionTransition",
                            positiveVersionEvidence(positiveCancelVersion, positiveVersion(restoredMetadata),
                                "fixture production removeAttachment/text APIs")))
                    positiveVersionChain(restoration, restoredMetadata, restoring = true)
                    requireBound(!restoration.optBoolean("hold", false) &&
                        restoration.optBoolean("originalComposerContentRestored", false) &&
                        restoration.optBoolean("ownedSourceRetired", false) &&
                        restoration.optBoolean("originalCipherUnchanged", false) &&
                        !restoration.optBoolean("recoveryLogRetained", true) &&
                        !restoration.optBoolean("rootClosureRequired", true))
                    // UUID versions are opaque. Do not order them or put an old UUID back.
                    requireBound(positiveVersion(restoredMetadata) != positiveCancelVersion)
                    withContext(Dispatchers.Main) {
                        requireBound(positiveFixtureTargetCurrent() && session.text == positiveOriginalText &&
                            session.attachments.map { it.manifest } == positiveOriginalAttachments)
                    }
                    val closure = restoration.getJSONObject("closedNormalization")
                    requireBound(sameOriginalWithFixtureClosure(checkNotNull(positiveReady), restored, closure))
                    positiveClosure = JSONObject(closure.toString())
                    positiveFixtureRestored = true
                    presentationHash = checkNotNull(positiveOriginalPresentationHash)
                }
                if (!afterMainCloseCase && positiveFixtureRestored && failures.isEmpty()) attempt("positiveFinalOriginalProtection") {
                    phase = "positive-final-original-protection"
                    positiveOriginalRowsAndOperations()
                    requireBound(withContext(Dispatchers.Main) { current() })
                    val (_, final) = positiveSnapshot()
                    val userData = sameOriginalWithFixtureClosure(initialUserDataV2, final, checkNotNull(positiveClosure))
                    val reading = positiveReadingProtection(initialUserDataV2, final)
                    val finalRef = positiveEvidence(PositiveStage.FINAL_ORIGINAL, final, JSONObject()
                        .put("originalUserDataWithActualFixtureClosureSame", userData)
                        .put("readingPreservation", reading)
                        .put("externalFullProtectionRequired", true))
                    evidence.put("positiveFinalReadingV2", finalRef)
                    requireBound(userData && positiveReadingProofAccepted(reading))
                }
            }
        }
        if (!positiveFixtureRestored || (!afterMainCloseCase && !positiveStages.has(PositiveStage.FINAL_ORIGINAL.name))) {
            failures.add("positiveFixtureRetainedOrFinalProtectionUnconfirmed")
        }
        if (afterMainCloseCase) {
            positiveDeferredFlags = flags
            positiveDeferredFailures = failures
        }
        if (!afterMainCloseCase && ::root.isInitialized && ::plan.isInitialized) {
            var sourcePresent: Boolean? = null
            if (admitted) attempt("positiveOwnedSourcePresenceObserved") {
                ownedSourceId?.let { id -> sourcePresent = withContext(Dispatchers.IO) { storage.read("draft-file:$id") != null } }
            }
            val value = JSONObject().put("version", 3).put("action", existingDraftAction)
                .put("acceptanceContract", "f31-existing-draft-reading-parent-required-v1")
                .put("run", run).put("selector", method).put("phase", phase)
                .put("positiveStage", positiveStage.name).put("positiveStages", positiveStages)
                .put("positivePhaseTotalBytes", positivePhaseBytes)
                .put("safeDiagnosticV2", safeDiagnosticV2()).put("bodyOutcome", bodyOutcome)
                .put("cleanupOutcome", if (failures.isEmpty()) "DRAINED" else "FAILED_OR_UNKNOWN")
                .put("consumed", consumed).put("planSha256", hash(plan)).put("flags", flags)
                .put("failedSteps", JSONArray(failures)).put("evidence", evidence)
                .put("ownedSourceId", ownedSourceId ?: JSONObject.NULL)
                .put("ownedPayloadKey", ownedPayloadKey ?: JSONObject.NULL)
                .put("ownedSourceMetadataPresent", sourcePresent ?: JSONObject.NULL)
                .put("fixtureStaged", positiveFixtureStaged).put("fixtureRestored", positiveFixtureRestored)
                .put("fixtureAttempted", positiveFixtureAttempted)
                .put("fixtureRecoveryRequired", positiveFixtureAttempted && !positiveFixtureRestored)
                .put("externalFullProtectionRequired", true).put("completeUserDataProtectionPassed", JSONObject.NULL)
                .put("externalProtectionRefSha256", plan.getString("externalProtectionRefSha256"))
                .put("borrowedAppSessionRetained", admitted).put("androidGuiAcceptance", false)
            try { withContext(Dispatchers.IO) {
                requireBound(NativeChatRecoveryCanonical.value(value).toByteArray().size <= 65536)
                writeNew("$run.result.json", value)
            } }
            catch (error: Throwable) {
                diagnosticFailure(DiagnosticRole.AFTER, error)
                failures.add("positiveImmutableResultNotConfirmed")
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("F31_POSITIVE_CANCEL_PROTECTION_FAILED_OR_UNKNOWN")
    }
    @After fun drainAndReleaseExactOwnedResources(): Unit { runBlocking<Unit> {
        if (existingDraftCase) {
            drainExistingDraftCancellation()
            return@runBlocking
        }
        try {
        diagnosticSubphase = DiagnosticSubphase.AFTER_OTHER
        val failures = mutableListOf<String>()
        val flags = JSONObject()
        suspend fun attempt(name: String, action: suspend () -> Unit) {
            diagnosticSubphase = when (name) {
                "callerJoined" -> DiagnosticSubphase.AFTER_CALLER
                "ownIoClosedAndIdle" -> DiagnosticSubphase.AFTER_IO
                "originalSyntheticRowEvidenceSaved" -> DiagnosticSubphase.AFTER_ROW
                "terminalRowInactiveAndDismissed" -> DiagnosticSubphase.AFTER_TERMINAL
                "ownedSourceReclaimedOrRetained" -> DiagnosticSubphase.AFTER_SOURCE
                "defaultWritesDrained" -> DiagnosticSubphase.AFTER_DRAIN
                "exactOwnedSourcePresenceObserved" -> DiagnosticSubphase.AFTER_SOURCE_PRESENCE
                else -> DiagnosticSubphase.AFTER_OTHER
            }
            try { withTimeout(45000) { action() }; flags.put(name, true) }
            catch (error: Throwable) { diagnosticFailure(DiagnosticRole.AFTER, error); flags.put(name, false); failures.add(name) }
        }
        attempt("callerJoined") { withContext(Dispatchers.Main) {
            val key = rowKey
            if (admitted && key != null && center.rows[key]?.terminal == false) center.control(key, "stop")
            caller?.cancelAndJoin()
            parent.cancelAndJoin()
        } }
        attempt("ownIoClosedAndIdle") { io.close(); io.awaitIdle() }
        if (admitted) {
            attempt("originalSyntheticRowEvidenceSaved") { withContext(Dispatchers.Main) {
                rowKey?.let { key -> center.rows[key]?.let { row ->
                    evidence.put("originalSyntheticRow", JSONObject().put("key", key).put("manifest", row.manifest.json())
                        .put("phase", row.phase).put("pending", row.pending ?: JSONObject.NULL).put("paused", row.paused)
                        .put("cancelRequested", row.cancelRequested).put("stop", row.stop).put("legacy", row.legacy))
                    row.receipt?.let { recordReceipt(it, "sharedCenterReceipt") }
                } }
            } }
            attempt("terminalRowInactiveAndDismissed") { withContext(Dispatchers.Main) {
                val key = rowKey
                if (key != null && key in center.rows) {
                    requireBound(center.rows[key]?.terminal == true)
                    // Local cleanup only, no HTTP, resume, prepare or retry. Active owner must be absent.
                    withTimeout(5000) { while (key in center.rows) { center.dismissTerminal(key); delay(25) } }
                }
                requireBound(center.storageError == null)
            } }
            attempt("ownedSourceReclaimedOrRetained") {
                if (failures.isNotEmpty()) error("RETAIN_ORIGINAL_SYNTHETIC_SOURCE")
                local?.let { NativeDraftFiles(storage).discardInboxOwned(it) }
                ownedSourceId?.let { id -> requireBound(withContext(Dispatchers.IO) { storage.read("draft-file:$id") } == null) }
            }
            attempt("defaultWritesDrained") { storage.awaitQueuedWrites() }
            val projectionChecks = JSONObject()
            for (name in listOf("current", "operations", "oldRows", "metadata")) {
                projectionChecks.put(name, JSONObject().put("status", "NOT_OBSERVED")
                    .put("matched", JSONObject.NULL))
            }
            evidence.put("originalProjectionChecks", projectionChecks)
            if (newUserDataCase) projectionChecks.put("userDataV2", JSONObject()
                .put("status", "NOT_OBSERVED").put("matched", JSONObject.NULL))
            val userMetadataChecks = JSONObject()
            for (name in initialUserMetadata.keys) userMetadataChecks.put(name,
                JSONObject().put("status", "NOT_OBSERVED").put("matched", JSONObject.NULL)
                    .put("refreshDiagnosticOnly", name in setOf("refreshWorkspaces", "refreshConnections")))
            evidence.put("userMetadataChecks", userMetadataChecks)
            attempt(if (newUserDataCase) "userDataLocalGateV2" else "originalProjectionUnchanged") { withContext(Dispatchers.Main) {
                // Observe independently; none of these read-only results can waive a guard.
                // No exception text, credential digest, row key or metadata value escapes.
                suspend fun observe(name: String, check: suspend () -> Boolean): Boolean? {
                    val item = projectionChecks.getJSONObject(name)
                    diagnosticSubphase = when (name) {
                        "current" -> DiagnosticSubphase.AFTER_CURRENT
                        "operations" -> DiagnosticSubphase.AFTER_OPERATIONS
                        "oldRows" -> DiagnosticSubphase.AFTER_ROWS
                        "metadata" -> DiagnosticSubphase.AFTER_METADATA
                        "userDataV2" -> DiagnosticSubphase.AFTER_USERDATA
                        else -> DiagnosticSubphase.AFTER_OTHER
                    }
                    return try {
                        val matched = check()
                        item.put("status", "OBSERVED").put("matched", matched)
                        matched
                    } catch (cancel: CancellationException) {
                        diagnosticFailure(DiagnosticRole.AFTER, cancel)
                        if (name == "operations") diagnosticFailure(DiagnosticRole.AFTER_OPERATIONS, cancel)
                        item.put("status", "CANCELLED_OR_TIMED_OUT").put("matched", JSONObject.NULL)
                        throw cancel // Preserve the original shared 45-second attempt bound.
                    } catch (error: Throwable) {
                        diagnosticFailure(DiagnosticRole.AFTER, error)
                        if (name == "operations") diagnosticFailure(DiagnosticRole.AFTER_OPERATIONS, error)
                        item.put("status", "UNAVAILABLE_EXCEPTION").put("matched", JSONObject.NULL)
                        null // An unavailable observation must fail the aggregate, never pass.
                    }
                }
                val currentMatches = observe("current") { current() }
                val operationsMatch = observe("operations") {
                    val beforeBody=::operationHashes.isInitialized
                    projectionChecks.getJSONObject("operations").put("baseline", if(beforeBody) "BEFORE_BODY" else "BEFORE_MAIN")
                    (if(beforeBody) operationHashes else preStartupOperations) == operations()
                }
                val rowsMatch = observe("oldRows") {
                    val bodiesMatch = oldRows.all { (key, digest) -> center.rows[key]?.let { hash(it.json()) } == digest }
                    val keysMatch = center.rows.keys.toSet() == oldRows.keys.toSet()
                    projectionChecks.getJSONObject("oldRows").put("bodiesMatch", bodiesMatch)
                        .put("keysMatch", keysMatch).put("expectedCount", oldRows.size)
                        .put("observedCount", center.rows.size)
                    if (newUserDataCase) {
                        val orderMatches = center.rows.keys.toList() == oldRows.keys.toList()
                        projectionChecks.getJSONObject("oldRows").put("orderMatch", orderMatches)
                        bodiesMatch && keysMatch && orderMatches
                    } else bodiesMatch && keysMatch
                }
                var finalMetadataV2: JSONObject? = null
                val metadataMatches = observe("metadata") {
                    val finalMetadata = withContext(Dispatchers.IO) { metadata() }
                    if (newUserDataCase) finalMetadataV2 = finalMetadata
                    try {
                        val finalProjection = userMetadataProjection(finalMetadata)
                        for ((name, expected) in initialUserMetadata) userMetadataChecks.getJSONObject(name)
                            .put("status", "OBSERVED").put("matched", finalProjection[name] == expected)
                    } catch (cancel: CancellationException) {
                        for (name in initialUserMetadata.keys) userMetadataChecks.getJSONObject(name)
                            .put("status", "CANCELLED_OR_TIMED_OUT").put("matched", JSONObject.NULL)
                        throw cancel
                    } catch (_: Throwable) {
                        for (name in initialUserMetadata.keys) userMetadataChecks.getJSONObject(name)
                            .put("status", "UNAVAILABLE_EXCEPTION").put("matched", JSONObject.NULL)
                    }
                    // No normalization is applied to the original required equality.
                    hash(finalMetadata) == initialMetadataHash
                }
                if (newUserDataCase) {
                    val userDataMatches = observe("userDataV2") {
                        val captured = withContext(Dispatchers.IO) {
                            F31UserDataSnapshotV2.capture(context, checkNotNull(finalMetadataV2), initialOriginalOperationsV2)
                        }
                        evidence.put("userDataV2After", captured.evidence())
                            .put("fullMetadataEqualAfterDiagnostic", metadataMatches ?: JSONObject.NULL)
                            .put("derivedRecordAfterV1",initialUserDataV2.derived.preservation(captured.derived))
                        initialUserDataV2.same(captured)
                    }
                    requireBound(currentMatches == true && operationsMatch == true && rowsMatch == true &&
                        metadataMatches != null && userDataMatches == true)
                } else requireBound(currentMatches == true && operationsMatch == true &&
                    rowsMatch == true && metadataMatches == true)
            } }
        }
        if (::root.isInitialized && ::plan.isInitialized) {
            var sourcePresent: Boolean? = null
            if (admitted) attempt("exactOwnedSourcePresenceObserved") {
                ownedSourceId?.let { id -> sourcePresent = withContext(Dispatchers.IO) { storage.read("draft-file:$id") != null } }
            }
            val retained = local?.takeIf { sourcePresent != false }?.manifest
            val value = JSONObject().put("version", if (newUserDataCase) 2 else 1).put("run", run).put("selector", method).put("phase", phase)
                .put("safeDiagnosticV2", safeDiagnosticV2())
                .put("bodyOutcome", bodyOutcome).put("cleanupOutcome", if (failures.isEmpty()) "DRAINED" else "FAILED_OR_UNKNOWN")
                .put("consumed", consumed).put("planSha256", hash(plan)).put("flags", flags).put("failedSteps", JSONArray(failures))
                .put("evidence", evidence).put("retainedSource", retained?.body() ?: JSONObject.NULL)
                .put("ownedSourceId", ownedSourceId ?: JSONObject.NULL)
                .put("ownedSourceMetadataPresent", sourcePresent ?: JSONObject.NULL).put("ownedPayloadKey", ownedPayloadKey ?: JSONObject.NULL)
                .put("payloadRemovalNeedsExternalExactInventory", ownedPayloadKey != null)
            if (newUserDataCase) {
                value.put("acceptanceContract", "f31-user-data-v2-parent-required")
                    .put("externalFullProtectionRequired", true).put("completeUserDataProtectionPassed", JSONObject.NULL)
                    .put("externalProtectionRefSha256", plan.getString("externalProtectionRefSha256"))
            }
            value.put("originalRowKey", rowKey ?: JSONObject.NULL)
                .put("borrowedAppSessionRetained", admitted).put("androidGuiAcceptance", false)
            diagnosticSubphase = DiagnosticSubphase.AFTER_RESULT
            try { withContext(Dispatchers.IO) { writeNew("$run.result.json", value) } }
            catch (error: Throwable) { diagnosticFailure(DiagnosticRole.AFTER, error); failures.add("immutableResultNotConfirmed") }
        }
        if (failures.isNotEmpty()) throw AssertionError("F31_UPLOAD_ONLY_CLEANUP_FAILED_OR_UNKNOWN")
        } catch (error: Throwable) {
            diagnosticFailure(DiagnosticRole.AFTER, error)
            throw error
        }
    } }
}
