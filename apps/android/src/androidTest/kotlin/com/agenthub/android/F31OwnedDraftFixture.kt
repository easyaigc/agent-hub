package com.agenthub.android

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Test-only local composer fixture. No commands, upload, send, new Session, key or store.
 * currentGuard must fence the caller's REAL target/authority/readiness, NOT the pre-fixture
 * text/version. All three entry points are suspending and may be called off Main.
 * Parent/V2 full-data/operation/history/authorization protection is still mandatory.
 */
internal class F31OwnedDraftFixture private constructor(
    private val context: Context, private val session: MobileSession,
    private val storage: NativeSessionStorage, val run: String,
    private val currentGuard: () -> Boolean,
) {
    private val gate = Mutex()
    private val folder = File(context.noBackupFilesDir, "mobile-v1")
    private val sourceId = UUID.nameUUIDFromBytes("f31-owned-composer:$run".toByteArray()).toString()
    private val logName = "f31-owned-composer-recovery-v1:$run"
    private val sourceName = "draft-file:$sourceId"
    private val thumbnailName = "draft-thumbnail-v1:$sourceId"
    private val marker = "\n[F31-owned-draft:$run]"
    private lateinit var destination: DraftDestination
    private lateinit var target: NativeUploadTarget
    private lateinit var original: JSONObject
    private lateinit var expected: JSONObject
    private var originalPins = JSONArray()
    private var ownPins = JSONArray()
    private val transitions = JSONArray()
    private var phase = "UNSTAGED"
    private var journal: String? = null
    private var beforeCancel: String? = null
    private var cancelBefore: JSONObject? = null
    private var cancelAfter: JSONObject? = null
    // Per-fixture evidence MAC key only; never a copy/rotation of the Android AES key.
    private var evidenceKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var restored = false
    private var recoveryReserveBytes = 0L
    private var lastRecoveryBytes = 0
    private var journalRemovalAttempted = false

    /** A stage failure returns no PASS. This handle can inspect/restore only known receipts;
     * an INTENT without its after receipt stays HOLD, even if its effect looks plausible.
     */
    internal class Held(val fixture: F31OwnedDraftFixture) : IllegalStateException("F31_OWNED_DRAFT_HOLD")

    private suspend fun guard() = withContext(Dispatchers.Main.immediate) {
        check(currentGuard()) { "F31_FIXTURE_CALLER_FENCE" }
        val main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().singleOrNull()
        check(main?.session === session && !session.restoring && session.usageForeground &&
            session.attachmentStorageReady && !session.localRecoveryRequired &&
            !session.migrationIngressPaused && !storage.localInspectionOnly)
        session.requireMigrationStorage(storage); storage.requireRawWriterOwner()
        check(storage.coordinationKey == folder.canonicalPath)
        check(session.selectedClient.scope?.agentId == "codex" &&
            session.selectedThread == QA && session.selectedClient.id == HOST)
        if (::destination.isInitialized) {
            check(session.isCurrent(destination) && target.matches(session.selectedClient))
        } else check(session.selectedConversationNetworkSnapshot != null)
    }

    private fun physical(name: String) = File(folder, sha(name) + ".bin")
    private fun settled(name: String, absent: Boolean = false) {
        val dir = Os.lstat(folder.path)
        check(!Files.isSymbolicLink(folder.toPath()) && OsConstants.S_ISDIR(dir.st_mode) &&
            dir.st_uid == Process.myUid() && dir.st_mode and 0x3f == 0)
        val file = physical(name)
        for (suffix in listOf(".bak", ".new")) check(Files.notExists(File(file.path + suffix).toPath(), NOFOLLOW_LINKS))
        if (absent) check(Files.notExists(file.toPath(), NOFOLLOW_LINKS))
        else check(!Files.isSymbolicLink(file.toPath()))
        // This helper supports only the actual default unmigrated store.
        check(Files.notExists(physical("migration-active-generation-v1").toPath(), NOFOLLOW_LINKS))
    }

    private fun read(name: String): String? {
        settled(name)
        return NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use {
            it.optionalText(name, 1_048_576)
        }
    }

    private suspend fun metadata(): JSONObject {
        storage.awaitQueuedWrites()
        return withContext(Dispatchers.IO) { JSONObject(checkNotNull(read("metadata"))) }
    }

    private fun composer(m: JSONObject): JSONObject = JSONObject().apply {
        for (field in FIELDS) {
            val map = m.getJSONObject(field)
            put(field, JSONObject().put("present", map.has(destination.key))
                .put("value", if (map.has(destination.key)) map.get(destination.key) else JSONObject.NULL))
        }
        val files = m.getJSONObject("draftFiles").optJSONArray(destination.key) ?: JSONArray()
        val photos = JSONObject()
        for (i in 0 until files.length()) {
            val id = files.getJSONObject(i).getString("id")
            m.optJSONObject("photoSources")?.let { if (it.has(id)) photos.put(id, it.get(id)) }
        }
        put("photoSources", photos)
        put("otherKeys", JSONObject().apply {
            for (field in FIELDS) {
                val other = JSONObject(m.getJSONObject(field).toString()).apply { remove(destination.key) }
                put(field, sha(easyCanonical(other)))
            }
            val otherPhotos = JSONObject(m.optJSONObject("photoSources")?.toString() ?: "{}")
            for (i in 0 until files.length()) otherPhotos.remove(files.getJSONObject(i).getString("id"))
            put("photoSources", sha(easyCanonical(otherPhotos)))
        })
    }

    private fun text(c: JSONObject) = c.getJSONObject("drafts").let {
        if (it.getBoolean("present")) it.getString("value") else ""
    }
    private fun files(c: JSONObject): JSONArray = c.getJSONObject("draftFiles").let {
        if (it.getBoolean("present")) it.getJSONArray("value") else JSONArray()
    }
    private fun version(c: JSONObject) = c.getJSONObject("draftVersions").toString()
    private fun generation(c: JSONObject) = c.getJSONObject("attachmentImportGenerations")
    private fun same(a: Any?, b: Any?) = easyCanonical(a) == easyCanonical(b)

    /** Main-only immediate CAS, including the live opaque version (no reflection). */
    private fun checkMemory(c: JSONObject) {
        check(currentGuard() && session.isCurrent(destination) && target.matches(session.selectedClient))
        val v = c.getJSONObject("draftVersions")
        check(session.composerActionContext().draftVersion == if (v.getBoolean("present")) v.getString("value") else null)
        check(session.text == text(c) && same(JSONArray(session.attachments.map { it.manifest.body() }), files(c)))
        check(same(NativePhotoOrigins.encode(session.attachments), c.getJSONObject("photoSources")))
    }

    private suspend fun observe(): JSONObject {
        guard()
        val c = composer(metadata())
        withContext(Dispatchers.Main.immediate) {
            checkMemory(c)
        }
        return c
    }

    private fun pin(name: String): JSONObject {
        settled(name)
        val file = physical(name)
        fun stat() = Os.lstat(file.path).let { s -> listOf(s.st_dev, s.st_ino, s.st_uid.toLong(),
            s.st_gid.toLong(), s.st_mode.toLong(), s.st_nlink, s.st_size, s.st_mtime, s.st_ctime) }
        val before = stat()
        val port = NativeMigrationReadOnlyFile(folder, file, 23 * 1024 * 1024)
        val value = port.read { input ->
            val buffer = ByteArray(65536)
            try { while (input.read(buffer) >= 0) { /* ciphertext stream only */ } }
            finally { buffer.fill(0) }
        }
        check(before == stat()); settled(name)
        return JSONObject().put("name", name).put("sha256", value.ciphertextSha256).put("stat", JSONArray(before))
    }

    private fun pins(c: JSONObject): JSONArray = JSONArray().apply {
        val fs = files(c)
        for (i in 0 until fs.length()) {
            val body = fs.getJSONObject(i)
            val name = "draft-file:${body.getString("id")}"
            val saved = NativeDraftFiles.record(JSONObject(checkNotNull(read(name))))
            check(same(saved.file.body(), body))
            put(pin(name)); saved.payload?.let { put(pin(it)) }
        }
    }
    private fun unchanged(pins: JSONArray) {
        for (i in 0 until pins.length()) {
            val p = pins.getJSONObject(i)
            check(same(p, pin(p.getString("name")))) { "F31_FIXTURE_ORIGINAL_CIPHER_CHANGED" }
        }
    }

    private suspend fun log(next: String) {
        val value = JSONObject().put("version", 2).put("run", run).put("sourceId", sourceId)
            .put("target", target.json()).put("thread", QA).put("key", destination.key)
            .put("selection", destination.selection).put("original", original).put("expected", expected)
            .put("originalPins", originalPins).put("ownPins", ownPins).put("transitions", transitions)
            .put("cancelBefore", cancelBefore ?: JSONObject.NULL).put("cancelAfter", cancelAfter ?: JSONObject.NULL)
            .put("evidenceKey", android.util.Base64.encodeToString(evidenceKey, android.util.Base64.NO_WRAP))
            .put("phase", next).toString()
        val utf8Bytes = value.toByteArray(Charsets.UTF_8).size
        check(F31OwnedComposerPolicy.journalFits(utf8Bytes)) { "F31_RECOVERY_LOG_CAPACITY" }
        withContext(Dispatchers.IO) { storage.operationTransaction {
            if (journal == null) settled(logName, absent = true)
            else check(read(logName) == journal)
            storage.write(logName, value)
            check(read(logName) == value)
        } }
        journal = value; phase = next; lastRecoveryBytes = utf8Bytes
    }

    private suspend fun checkpoint(next: String, operation: String) {
        val previous = expected
        val observed = observe()
        check(transitions.length() < 5)
        check(same(previous.getJSONObject("otherKeys"), observed.getJSONObject("otherKeys")))
        check(same(generation(original), generation(observed)) || !generation(original).getBoolean("present"))
        val unchangedFiles = same(files(previous), files(observed)) &&
            same(previous.getJSONObject("photoSources"), observed.getJSONObject("photoSources"))
        when (operation) {
            "attachmentImportDestination" -> check(unchangedFiles && text(previous) == text(observed) &&
                version(previous) == version(observed) && generation(observed).getBoolean("present"))
            "importAttachments.await" -> {
                check(text(previous) == text(observed) && same(generation(previous), generation(observed)))
                check(files(observed).length() == files(previous).length() + 1)
                for (i in 0 until files(previous).length()) check(same(files(previous).get(i), files(observed).get(i)))
                check(files(observed).getJSONObject(files(observed).length() - 1).getString("id") == sourceId)
                check(same(previous.getJSONObject("photoSources"), observed.getJSONObject("photoSources")))
                check(version(previous) != version(observed))
            }
            "MobileSession.text", "MobileSession.text.restore" -> check(unchangedFiles &&
                same(generation(previous), generation(observed)) && version(previous) != version(observed) &&
                text(observed) == if (operation == "MobileSession.text") text(original) + marker else text(original))
            "removeAttachment" -> check(text(previous) == text(observed) && same(files(observed), files(original)) &&
                same(observed.getJSONObject("photoSources"), original.getJSONObject("photoSources")) &&
                same(generation(previous), generation(observed)) && version(previous) != version(observed))
            else -> error("F31_FIXTURE_UNKNOWN_TRANSITION")
        }
        transitions.put(JSONObject().put("operation", operation)
            .put("before", JSONObject(previous.toString())).put("after", JSONObject(observed.toString())))
        expected = observed
        log(next)
    }

    private suspend fun stageOwned() = gate.withLock {
        guard()
        withContext(Dispatchers.Main.immediate) {
            check(session.canCompose && session.canRemoveDraftAttachment && session.attachments.size < 5)
            destination = session.attachmentDestination(); target = NativeUploadTarget.capture(session.selectedClient)
        }
        original = observe(); expected = JSONObject(original.toString())
        check(!text(original).contains(marker))
        check(!metadata().getJSONObject("draftFiles").toString().contains(sourceId))
        withContext(Dispatchers.IO) {
            for (name in listOf(logName, sourceName, thumbnailName)) settled(name, absent = true)
            check(files(original).objects().none { it.getString("id") == sourceId })
            originalPins = pins(original)
        }
        // Reserve the complete five-call lifecycle, both cancellation endpoints and
        // fixed source-pin/envelope overhead BEFORE any import-generation/text edit.
        recoveryReserveBytes = F31OwnedComposerPolicy.stageRecoveryBytes(original, originalPins)
        check(recoveryReserveBytes <= F31OwnedComposerPolicy.MAX_JOURNAL_BYTES) { "F31_RECOVERY_PREFLIGHT_CAPACITY" }
        log("IMPORT_INTENT") // Durable encrypted recovery before the first composer mutation.
        val importDestination = withContext(Dispatchers.Main.immediate) {
            checkMemory(expected)
            session.attachmentImportDestination().copy(requestId = UUID.nameUUIDFromBytes("f31-import:$run".toByteArray()).toString())
        }
        checkpoint("DESTINATION_READY", "attachmentImportDestination")
        log("ATTACH_INTENT")
        val job = withContext(Dispatchers.Main.immediate) {
            checkMemory(expected)
            checkNotNull(session.importAttachments(importDestination, listOf({
                LocalAttachmentInput("f31-owned-$run.bin", "application/octet-stream",
                    ByteArray(8192) { (it % 251).toByte() }, fixedId = sourceId)
            }), reportFailure = false))
        }
        job.await()
        val attached = observe()
        check(text(attached) == text(original) && files(attached).length() == files(original).length() + 1)
        for (i in 0 until files(original).length()) check(same(files(original).get(i), files(attached).get(i)))
        val own = files(attached).getJSONObject(files(attached).length() - 1)
        check(own.getString("id") == sourceId && own.getLong("sizeBytes") == 8192L)
        withContext(Dispatchers.IO) {
            unchanged(originalPins)
            val saved = NativeDraftFiles.record(JSONObject(checkNotNull(read(sourceName))))
            check(same(saved.file.body(), own) && checkNotNull(saved.payload).startsWith("draft-payload-v2:$sourceId:"))
            ownPins = JSONArray().put(pin(sourceName)).put(pin(checkNotNull(saved.payload)))
        }
        checkpoint("ATTACHED", "importAttachments.await")
        log("TEXT_INTENT")
        withContext(Dispatchers.Main.immediate) {
            checkMemory(expected)
            check(session.text == text(original))
            session.text = text(original) + marker
        }
        checkpoint("STAGED", "MobileSession.text")
        check(text(expected) == text(original) + marker)
    }

    /** First call is BeforeCancel; following calls compare exact same text/refs/UUID/ciphers.
     * Counts include the actual persisted current composer, not invented operation references.
     */
    suspend fun positiveEvidence(): JSONObject = gate.withLock {
        check(phase == "STAGED" && !restored)
        check(same(expected, observe()))
        withContext(Dispatchers.IO) { unchanged(originalPins); unchanged(ownPins); check(read(logName) == journal) }
        val now = easyCanonical(expected)
        if (beforeCancel == null) {
            beforeCancel = now
            cancelBefore = JSONObject(expected.toString())
        } else cancelAfter = JSONObject(expected.toString())
        check(beforeCancel == now) { "F31_FIXTURE_CANCEL_COMPOSER_CHANGED" }
        evidence().put("positiveTextNonempty", text(expected).isNotEmpty())
            .put("localReferences", files(expected).length()).put("beforeCancelMatches", beforeCancel == now)
            .put("originalCipherUnchanged", true).put("fixtureCipherUnchanged", true)
    }

    /** No replay of an ambiguous INTENT. Failures retain the encrypted journal and originals.
     * Only the current fixture is CAS-removed; concurrent composer edits cause HOLD.
     */
    suspend fun restore(): JSONObject = gate.withLock {
        if (restored) return@withLock evidence().put("alreadyRestored", true)
        try {
            check(phase in setOf("STAGED", "ATTACHED", "TEXT_RESTORED", "COMPOSER_RESTORED"))
            check(same(expected, observe()))
            withContext(Dispatchers.IO) { unchanged(originalPins); unchanged(ownPins); check(read(logName) == journal) }
            if (phase in setOf("STAGED", "ATTACHED")) {
                check(text(expected) == text(original) + marker || text(expected) == text(original))
                if (text(expected) != text(original)) {
                    log("RESTORE_TEXT_INTENT")
                    withContext(Dispatchers.Main.immediate) {
                        checkMemory(expected)
                        check(session.text == text(original) + marker)
                        session.text = text(original)
                    }
                    checkpoint("TEXT_RESTORED", "MobileSession.text.restore")
                }
            }
            if (phase != "COMPOSER_RESTORED") {
                check(same(expected, observe()))
                log("REMOVE_ATTACHMENT_INTENT")
                withContext(Dispatchers.Main.immediate) {
                    checkMemory(expected)
                    check(session.canRemoveDraftAttachment)
                    check(session.attachments.count { it.manifest.id == sourceId } == 1)
                    session.removeAttachment(sourceId, destination)
                }
                checkpoint("COMPOSER_RESTORED", "removeAttachment")
            }
            check(text(expected) == text(original) && same(files(expected), files(original)) &&
                same(expected.getJSONObject("photoSources"), original.getJSONObject("photoSources")))
            withContext(Dispatchers.Main.immediate) {
                checkMemory(expected)
                check(session.operationRecords.none { it.body().toString().contains(sourceId) })
            }
            val currentFiles = metadata().getJSONObject("draftFiles")
            check(currentFiles.keys().asSequence().all { key ->
                currentFiles.getJSONArray(key).objects().none { it.getString("id") == sourceId }
            })
            withContext(Dispatchers.IO) { unchanged(originalPins); unchanged(ownPins) }
            log("RETIRE_SOURCE_INTENT")
            withContext(Dispatchers.IO) {
                val record = NativeDraftFiles.record(JSONObject(checkNotNull(read(sourceName))))
                check(record.file.id == sourceId && record.file.sizeBytes == 8192L && ownPins.length() == 2)
                val owned = LocalDraftFile(record.file)
                settled(thumbnailName, absent = true)
                NativeDraftFiles(storage).discardInboxOwned(owned)
                for (i in 0 until ownPins.length()) settled(ownPins.getJSONObject(i).getString("name"), absent = true)
                unchanged(originalPins)
            }
            check(same(expected, observe()))
            val generationSame = same(generation(original), generation(expected))
            val presenceSame = FIELDS.filter { it != "draftVersions" }.all {
                original.getJSONObject(it).getBoolean("present") == expected.getJSONObject(it).getBoolean("present")
            }
            val closure = F31OwnedComposerPolicy.evaluate(original, expected, transitions, run, sourceId,
                cancelBefore, cancelAfter, evidenceKey, originalCipherUnchanged = true, ownedSourceRetired = true)
            if (!closure.getBoolean("matched")) {
                log("RESTORED_HOLD")
                return@withLock evidence().put("hold", true).put("closedNormalization", closure)
                    .put("originalComposerContentRestored", true).put("ownedSourceRetired", true)
                    .put("recoveryLogRetained", true).put("rootClosureRequired", true)
            }
            log("RESTORED") // Exact successful policy + actual retirement precede this durable receipt.
            check(same(expected, observe()))
            val result = evidence().put("originalComposerContentRestored", true).put("ownedSourceRetired", true)
                .put("originalCipherUnchanged", true).put("importGenerationUnchanged", generationSame)
                .put("originalFieldPresenceRestored", presenceSame)
                .put("closedNormalization", closure.put("restoredPersisted", true))
                .put("recoveryLogRetained", false).put("rootClosureRequired", false)
            withContext(Dispatchers.IO) { storage.operationTransaction {
                unchanged(originalPins)
                for (i in 0 until ownPins.length()) settled(ownPins.getJSONObject(i).getString("name"), absent = true)
                check(phase == "RESTORED" && read(logName) == journal)
                journalRemovalAttempted = true
                storage.remove(logName); settled(logName, absent = true)
            } }
            restored = true
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            // Never print exception messages/locals, overwrite unknown user edits or auto-clean.
            evidence().put("hold", true)
                .put("recoveryLogRetained", if (journalRemovalAttempted) JSONObject.NULL else true)
                .put("recoveryLogRetentionUnknown", journalRemovalAttempted)
        }
    }

    private fun evidence() = JSONObject().put("schemaVersion", 1).put("ownedRun", run).put("ownedSourceId", sourceId)
        .put("targetHash", if (::target.isInitialized) sha(easyCanonical(target.json())) else JSONObject.NULL)
        .put("composerKeyHash", if (::destination.isInitialized) sha(destination.key) else JSONObject.NULL)
        .put("threadHash", sha(QA))
        .put("recoveryKeyHash", sha(logName)).put("phaseHash", sha(phase))
        .put("recoveryReserveBytes", recoveryReserveBytes).put("lastRecoveryBytes", lastRecoveryBytes)
        .put("externalFullProtectionRequired", true).put("transitionCount", transitions.length())
        .put("versionTransitions", JSONArray((0 until transitions.length()).map { i ->
            val t = transitions.getJSONObject(i)
            JSONObject().put("operationHash", sha(t.getString("operation")))
                .put("beforeHash", F31OwnedComposerPolicy.mac(evidenceKey, t.getJSONObject("before").getJSONObject("draftVersions")))
                .put("afterHash", F31OwnedComposerPolicy.mac(evidenceKey, t.getJSONObject("after").getJSONObject("draftVersions")))
        }))

    companion object {
        private const val QA = "01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
        private const val HOST = "13c81542-48e3-4df2-ab46-d45357b631a2"
        private val FIELDS = listOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations")
        private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        suspend fun stage(context: Context, session: MobileSession, storage: NativeSessionStorage,
            run: String, currentGuard: () -> Boolean): F31OwnedDraftFixture {
            require(UUID.fromString(run).toString() == run)
            val fixture = F31OwnedDraftFixture(context.applicationContext, session, storage, run, currentGuard)
            try { fixture.stageOwned(); return fixture }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { throw Held(fixture) }
        }

        /** Restoration only: never restages/imports or retries an ambiguous intent.
         * A changed selection/target/UUID, process recovery with different presentation,
         * missing original cipher or unknown phase remains HOLD with the journal intact.
         */
        suspend fun recoverForRestore(context: Context, session: MobileSession, storage: NativeSessionStorage,
            run: String, currentGuard: () -> Boolean): F31OwnedDraftFixture {
            require(UUID.fromString(run).toString() == run)
            val f = F31OwnedDraftFixture(context.applicationContext, session, storage, run, currentGuard)
            val raw = withContext(Dispatchers.IO) { checkNotNull(f.read(f.logName)) }
            val j = JSONObject(raw)
            check(j.getInt("version") == 2 && j.getString("run") == run &&
                j.getString("sourceId") == f.sourceId && j.getString("thread") == QA)
            f.target = NativeUploadTarget.decode(j.getJSONObject("target"))
            f.destination = DraftDestination(j.getString("key"), j.getLong("selection"))
            f.original = j.getJSONObject("original"); f.expected = j.getJSONObject("expected")
            f.originalPins = j.getJSONArray("originalPins"); f.ownPins = j.getJSONArray("ownPins")
            f.cancelBefore = j.optJSONObject("cancelBefore"); f.cancelAfter = j.optJSONObject("cancelAfter")
            f.beforeCancel = f.cancelBefore?.let(::easyCanonical)
            f.evidenceKey.fill(0)
            f.evidenceKey = android.util.Base64.decode(j.getString("evidenceKey"), android.util.Base64.NO_WRAP)
            check(f.evidenceKey.size == 32)
            j.getJSONArray("transitions").objects().forEach { f.transitions.put(it) }
            f.phase = j.getString("phase"); f.journal = raw
            f.guard()
            return f
        }
    }
}

/** Pure closed policy for THIS fixture only, not a general metadata filter or IO authority.
 * Inputs are actual encrypted-journal checkpoints; external Parent must bind them to this
 * run, real cancellation, original ciphertext and whole user-data protection.
 */
internal object F31OwnedComposerPolicy {
    const val MAX_JOURNAL_BYTES = 1_048_576
    fun journalFits(utf8Bytes: Int) = utf8Bytes in 1..MAX_JOURNAL_BYTES
    fun stageRecoveryBytes(original: JSONObject, originalPins: JSONArray): Long =
        16L * original.toString().toByteArray(Charsets.UTF_8).size +
            originalPins.toString().toByteArray(Charsets.UTF_8).size + 65_536L
    private val fields = listOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations")
    private val operations = listOf("attachmentImportDestination", "importAttachments.await", "MobileSession.text",
        "MobileSession.text.restore", "removeAttachment")
    private fun equal(a: Any?, b: Any?) = easyCanonical(a) == easyCanonical(b)
    private fun copy(a: JSONObject) = JSONObject(a.toString())
    private fun keys(a: JSONObject) = a.keys().asSequence().toSet()
    private fun uuid(s: String) = UUID.fromString(s).toString() == s
    private fun entry(present: Boolean, value: Any?) = JSONObject().put("present", present)
        .put("value", if (present) value else JSONObject.NULL)
    private fun value(s: JSONObject, f: String): Any? = s.getJSONObject(f).let {
        if (it.getBoolean("present")) it.get("value") else null
    }
    private fun files(s: JSONObject) = value(s, "draftFiles") as? JSONArray ?: JSONArray()
    private fun text(s: JSONObject) = value(s, "drafts") as? String ?: ""
    private fun validate(s: JSONObject) {
        require(keys(s) == fields.toSet() + setOf("photoSources", "otherKeys"))
        for (f in fields) {
            val e = s.getJSONObject(f)
            require(keys(e) == setOf("present", "value") && e.get("present") is Boolean)
            if (!e.getBoolean("present")) require(e.get("value") == JSONObject.NULL)
            else if (f == "draftFiles") require(e.get("value") is JSONArray)
            else require(e.get("value") is String)
            if (e.getBoolean("present") && f in setOf("draftVersions", "attachmentImportGenerations"))
                require(uuid(e.getString("value")))
        }
        s.getJSONObject("photoSources")
        val other = s.getJSONObject("otherKeys")
        require(keys(other) == fields.toSet() + "photoSources")
        require(other.keys().asSequence().all { other.getString(it).matches(Regex("[0-9a-f]{64}")) })
    }
    internal fun mac(key: ByteArray, value: Any?): String {
        require(key.size == 32)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(("f31-owned-composer-field-v1\u0000" + easyCanonical(value)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun kind(e: JSONObject): String = when {
        !e.getBoolean("present") -> "absent"
        e.get("value") is JSONArray -> if (e.getJSONArray("value").length() == 0) "empty-array" else "array"
        e.getString("value").isEmpty() -> "empty-string"
        else -> "string"
    }
    fun evaluate(original: JSONObject, restored: JSONObject, transitions: JSONArray, run: String, sourceId: String,
        cancelBefore: JSONObject?, cancelAfter: JSONObject?, evidenceKey: ByteArray,
        originalCipherUnchanged: Boolean, ownedSourceRetired: Boolean): JSONObject {
        try {
            require(uuid(run) && sourceId == UUID.nameUUIDFromBytes("f31-owned-composer:$run".toByteArray()).toString())
            require(originalCipherUnchanged && ownedSourceRetired)
            validate(original); validate(restored)
            require(transitions.length() == operations.size)
            var current = copy(original)
            var staged: JSONObject? = null
            val marker = "\n[F31-owned-draft:$run]"
            val calls = JSONArray()
            for (i in operations.indices) {
                val t = transitions.getJSONObject(i)
                require(keys(t) == setOf("operation", "before", "after") && t.getString("operation") == operations[i])
                val before = t.getJSONObject("before"); val after = t.getJSONObject("after")
                validate(before); validate(after); require(equal(before, current))
                val predicted = copy(before)
                when (i) {
                    0 -> {
                        if (value(original, "attachmentImportGenerations") == null) {
                            val generated = after.getJSONObject("attachmentImportGenerations")
                            require(generated.getBoolean("present") && uuid(generated.getString("value")))
                            predicted.put("attachmentImportGenerations", copy(generated))
                        }
                    }
                    1 -> {
                        val actualFiles = files(after)
                        require(actualFiles.length() == files(original).length() + 1)
                        val own = actualFiles.getJSONObject(actualFiles.length() - 1)
                        require(keys(own) == setOf("id", "name", "mediaType", "sizeBytes", "sha256"))
                        val bytes = ByteArray(8192) { (it % 251).toByte() }
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        require(own.getString("id") == sourceId && own.getString("name") == "f31-owned-$run.bin" &&
                            own.getString("mediaType") == "application/octet-stream" && own.getLong("sizeBytes") == 8192L && own.getString("sha256") == hash)
                        val expectedFiles = JSONArray(files(original).toString()).put(copy(own))
                        predicted.put("draftFiles", entry(true, expectedFiles))
                    }
                    2 -> predicted.put("drafts", entry(true, text(original) + marker))
                    3 -> predicted.put("drafts", entry(true, text(original)))
                    4 -> predicted.put("draftFiles", entry(true, JSONArray(files(original).toString())))
                }
                if (i > 0) {
                    val next = after.getJSONObject("draftVersions")
                    require(next.getBoolean("present") && uuid(next.getString("value")) &&
                        !equal(next, before.getJSONObject("draftVersions")))
                    predicted.put("draftVersions", copy(next)) // Actual opaque UUID, never sorted or restored.
                }
                require(equal(predicted, after)) // Includes every other key hash, reference/order and photo origin.
                calls.put(JSONObject().put("operation", operations[i]).put("beforeHash", mac(evidenceKey, before))
                    .put("afterHash", mac(evidenceKey, after)))
                current = copy(after)
                if (i == 2) staged = copy(after)
            }
            require(equal(current, restored) && cancelBefore != null && cancelAfter != null)
            validate(cancelBefore); validate(cancelAfter)
            require(equal(staged, cancelBefore) && equal(cancelBefore, cancelAfter)) // NO cancellation normalization.
            val observations = JSONArray(fields.map { f ->
                val a = original.getJSONObject(f); val b = restored.getJSONObject(f)
                JSONObject().put("field", f).put("beforePresent", a.getBoolean("present"))
                    .put("afterPresent", b.getBoolean("present")).put("beforeKind", kind(a)).put("afterKind", kind(b))
                    .put("beforeHash", mac(evidenceKey, a)).put("afterHash", mac(evidenceKey, b))
                    .put("exactlyEqual", equal(a, b))
            })
            return JSONObject().put("policyVersion", 1).put("matched", true).put("ownedRun", run)
                .put("ownedSourceId", sourceId).put("fieldObservations", observations).put("productionChain", calls)
                .put("cancelBeforeHash", mac(evidenceKey, cancelBefore)).put("cancelAfterHash", mac(evidenceKey, cancelAfter))
                .put("cancelExact", true).put("otherComposerKeysExact", true)
                .put("originalCipherUnchanged", true).put("ownedSourceRetired", true)
                .put("externalFullProtectionRequired", true)
        } catch (_: Exception) {
            return JSONObject().put("policyVersion", 1).put("matched", false).put("hold", true)
                .put("externalFullProtectionRequired", true)
        }
    }
}
