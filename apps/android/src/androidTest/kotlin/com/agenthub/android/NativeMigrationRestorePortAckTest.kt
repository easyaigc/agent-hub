package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Real Keystore, encrypted storage, candidate and activation for restore-port handoff.
 * Only the existing typed restore callback is held/failed. This is NOT a
 * NativeMigrationExistingPolicy postpointer authority/confirmPublication test.
 * No Activity, network, model, OS notification, or default storage is operated.
 * Owns every Os.open FD and tracks Frozen across dispatcher cancellation.
 * Cleanup joins only test-owned callbacks; private controller scope is not exposed.
 * Run the two handoff methods separately in distinct owned instrumentation processes.
 * Failed fixtures/keys remain for exact collection and typed acknowledgement.
 */
class NativeMigrationRestorePortAckTest {
    @Test fun readOnlyLegacyCandidatesKeepReceiptAndChunkValidation() {
        val appId=InstrumentationRegistry.getInstrumentation().targetContext.packageName
        require(appId=="com.easyai.agenthub.android.acceptance")
        val payload=MigrationActivationFixtures.payload()
        val entries=payload.getJSONObject("records");val metadata=JSONObject(entries.getString("metadata"))
        val drafts=metadata.getJSONObject("drafts")
        val client=MigrationActivationFixtures.client(payload)
        for(index in 0..2)drafts.put(client.cacheKey("legacy-reader-draft-$index"),"x".repeat(90_000))
        entries.put("metadata",metadata.toString())
        val clear=payload.toString().toByteArray(Charsets.UTF_8)
        val r=NativeMigrationCandidateStore.Receipt(UUID.randomUUID().toString(),MigrationActivationFixtures.sha(clear),entries.length())
        val expected=entries.keys().asSequence().associateWith {entries.getString(it)}
        val generation=UUID.randomUUID().toString();val chunks=JSONArray();val blobs=linkedMapOf<String,NativeMigrationBlob>()
        val owned=mutableListOf<ByteArray>()
        try {
            var offset=0
            while(offset<clear.size) {
                val count=minOf(NativeMigrationCandidateStore.CANDIDATE_CHUNK_BYTES,clear.size-offset)
                val bytes=clear.copyOfRange(offset,offset+count);owned.add(bytes)
                val sha=MigrationActivationFixtures.sha(bytes)
                blobs["migration-candidate:$generation:${chunks.length()}"]=NativeMigrationBlob(count,sha,bytes.take(44).toByteArray()) {it.write(bytes)}
                chunks.put(JSONObject().put("size",count).put("sha256",sha));offset+=count
            }
            assertTrue(chunks.length()>1)
            val v1=JSONObject().put("version",1).put("id",r.id).put("sha256",r.sha256)
                .put("payload",java.util.Base64.getEncoder().encodeToString(clear))
            val v2=JSONObject().put("version",2).put("id",r.id).put("sha256",r.sha256).put("size",clear.size)
                .put("records",r.recordCount).put("generation",generation).put("chunks",chunks)
            fun read(saved: JSONObject,receipt: NativeMigrationCandidateStore.Receipt=r,
                     binaries: Map<String,NativeMigrationBlob> = blobs): Int {
                var callbacks=0
                NativeMigrationExistingCandidate.read(appId,NativeMigrationStreamRecords(mapOf("candidate-v1" to saved.toString()),binaries),receipt) {
                    assertEquals(expected,it.text);assertTrue(it.binary.isEmpty());callbacks++
                }
                return callbacks
            }
            fun reject(saved: JSONObject,receipt: NativeMigrationCandidateStore.Receipt=r,
                       binaries: Map<String,NativeMigrationBlob> = blobs) {
                var consumed=false
                try {
                    NativeMigrationExistingCandidate.read(appId,NativeMigrationStreamRecords(mapOf("candidate-v1" to saved.toString()),binaries),receipt) {consumed=true}
                    fail("Invalid original legacy candidate reached consumer")
                }catch(_: Exception) {assertFalse(consumed)}
            }
            for(saved in listOf(v1,v2)) {
                assertEquals(1,read(saved))
                reject(JSONObject(saved.toString()).put("unexpected",true))
                reject(saved,r.copy(id=UUID.randomUUID().toString()))
                reject(saved,r.copy(recordCount=r.recordCount+1))
                reject(saved,r.copy(sha256="0".repeat(64)))
            }
            reject(JSONObject(v2.toString()).apply {getJSONArray("chunks").getJSONObject(0).put("sha256","0".repeat(64))})
            reject(JSONObject(v2.toString()).apply {getJSONArray("chunks").getJSONObject(0).put("size",1)})
            reject(JSONObject(v2.toString()).put("generation","not-a-generation"))
            reject(v2,binaries=emptyMap())
            val first=blobs.keys.first();val original=blobs.getValue(first)
            reject(v2,binaries=blobs+(first to original.copy(copy={output->
                val damaged=owned.first().copyOf();damaged[0]=(damaged[0].toInt() xor 1).toByte()
                try {output.write(damaged)}finally {damaged.fill(0)}
            })))
        }finally {clear.fill(0);owned.forEach {it.fill(0)}}
    }

    private class Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("migrationRestorePortRun"))
            .also { require(UUID.fromString(it).toString() == it) }
        // Resolve only the trusted Context root. All fixture paths below use this
        // canonical root, and are lstat/NOFOLLOW checked before any fixture open.
        private val filesRoot = app.filesDir.canonicalFile
        val folder = File(filesRoot, "P7-restore-port-" + run)
        val alias = "p7-restore-port-" + run
        val namespace = "installation"
        private var expectedAnchor: Map<String, String>? = null
        init {
            require(app.packageName == "com.easyai.agenthub.android.acceptance")
            require(app.applicationInfo.uid == Process.myUid())
            require(filesRoot == File(File(app.applicationInfo.dataDir).canonicalFile, "files"))
        }
        /** Os.open owns the raw FD; public Android streams only borrow it. */
        private inline fun <T> withOwnedFd(file: File, flags: Int, mode: Int,
            block: (java.io.FileDescriptor) -> T): T {
            val fd = android.system.Os.open(file.path, flags, mode)
            var primary: Throwable? = null
            try { return block(fd) }
            catch (error: Throwable) { primary = error; throw error }
            finally {
                try { android.system.Os.close(fd) }
                catch (close: Throwable) {
                    val original = primary
                    if (original == null) throw close
                    if (original !== close) original.addSuppressed(close)
                }
            }
        }
        private fun statIdentity(s: android.system.StructStat) =
            listOf(s.st_dev, s.st_ino, s.st_uid.toLong(), s.st_mode.toLong()).joinToString(":")
        private fun directory(file: File, owned: Boolean, fixturePrivate: Boolean): String {
            val before = android.system.Os.lstat(file.path)
            require(android.system.OsConstants.S_ISDIR(before.st_mode))
            if (owned) {
                require(before.st_uid == Process.myUid())
                val mode = before.st_mode and 0x1ff
                require(if (fixturePrivate) mode == 0x1c0 else mode in setOf(0x1c0, 0x1f9))
                withOwnedFd(file, android.system.OsConstants.O_RDONLY or
                    android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW, 0) { fd ->
                    require(statIdentity(android.system.Os.fstat(fd)) == statIdentity(before))
                }
            }
            require(statIdentity(android.system.Os.lstat(file.path)) == statIdentity(before))
            return statIdentity(before)
        }
        private fun rootChain(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            var current = File("/")
            result["ancestor:/"] = directory(current, false, false)
            for (part in filesRoot.path.split('/').filter { it.isNotEmpty() }) {
                require(part != "." && part != "..")
                current = File(current, part)
                result["ancestor:" + current.path] = directory(current, current == filesRoot, false)
            }
            require(current == filesRoot && filesRoot.canonicalFile == filesRoot)
            return result
        }
        private fun snapshot(): Map<String, String> = linkedMapOf<String, String>().apply {
            putAll(rootChain()); put("package", app.packageName); put("uid", Process.myUid().toString())
            put("run", run); put("filesRoot", filesRoot.path)
            put("runDirectory", directory(folder, true, true))
            require(folder.canonicalFile == File(filesRoot, "P7-restore-port-" + run))
            for (name in listOf("no_backup", "cache", "files")) {
                val child = File(folder, name)
                put(name, directory(child, true, true)); require(child.canonicalFile == child)
            }
        }
        fun guard() { require(snapshot() == requireNotNull(expectedAnchor)) { "Owned fixture anchor changed" } }
        fun anchor(): JSONObject { guard(); return JSONObject(requireNotNull(expectedAnchor)) }
        private fun ownedDirectory(name: String): File { guard(); return File(folder, name) }
        val context: Context get() { guard(); return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = ownedDirectory("no_backup")
            override fun getCacheDir(): File = ownedDirectory("cache")
            override fun getFilesDir(): File = ownedDirectory("files")
        } }
        fun fresh() {
            rootChain()
            require(!java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            require(java.nio.file.Files.notExists(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            check(folder.mkdir()); android.system.Os.chmod(folder.path, 0x1c0)
            for (name in listOf("no_backup", "cache", "files")) {
                val child = File(folder, name); check(child.mkdir()); android.system.Os.chmod(child.path, 0x1c0)
            }
            expectedAnchor = snapshot()
            publish("fixture-intent", JSONObject().put("run", run).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("package", app.packageName).put("namespace", namespace)
                .put("keyAlias", alias).put("scope", "typed-restore-port-only").put("anchor", anchor()))
        }
        fun store(existing: Boolean = false): NativeSessionStorage {
            guard(); val opened = NativeSessionStorage(context, namespace, alias,
                existingKeyOnly = existing, existingNamespaceOnly = existing)
            try { guard(); return opened } catch (error: Throwable) { opened.retireWriter(); throw error }
        }
        fun view(): NativeMigrationExistingView {
            guard(); val opened = NativeSessionStorage.existingMigration(context, context.noBackupFilesDir, namespace, alias)
            try { guard(); return opened } catch (error: Throwable) { opened.close(); throw error }
        }
        fun publish(name: String, value: JSONObject) {
            require(name in setOf("fixture-intent", "handoff-complete", "case1-cleanup", "cold-audit-complete", "case2-cleanup"))
            guard(); val file = File(folder, name + ".json")
            val bytes = value.toString().toByteArray(); require(bytes.size in 1..131072)
            withOwnedFd(file, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or
                android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW, 0x180) { fd ->
                // Constructor/use/write/fsync/borrowed-close are all inside raw-FD finally.
                java.io.FileOutputStream(fd).use { output ->
                    android.system.Os.fchmod(fd, 0x180); output.write(bytes); output.fd.sync()
                }
            }
            guard()
        }
        private fun read(name: String): JSONObject {
            require(name in setOf("fixture-intent", "handoff-complete", "case1-cleanup"))
            val beforeAnchor = snapshot()
            expectedAnchor?.let { require(it == beforeAnchor) }
            val file = File(folder, name + ".json"); val before = android.system.Os.lstat(file.path)
            require(android.system.OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() &&
                before.st_nlink == 1L && (before.st_mode and 0x1ff) == 0x180 && before.st_size in 1L..131072L)
            val bytes = withOwnedFd(file, android.system.OsConstants.O_RDONLY or
                android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK, 0) { fd ->
                java.io.FileInputStream(fd).use { input ->
                    require(statIdentity(android.system.Os.fstat(fd)) == statIdentity(before))
                    require(android.system.Os.fstat(fd).st_size == before.st_size)
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 131072); output.write(buffer, 0, count)
                    }
                    val result = output.toByteArray(); require(result.size.toLong() == before.st_size)
                    require(statIdentity(android.system.Os.fstat(fd)) == statIdentity(before) &&
                        android.system.Os.fstat(fd).st_size == before.st_size)
                    result
                }
            }
            require(statIdentity(android.system.Os.lstat(file.path)) == statIdentity(before) && android.system.Os.lstat(file.path).st_size == before.st_size)
            require(snapshot() == beforeAnchor)
            return StrictMigrationJson.parse(bytes)
        }
        fun proof(): JSONObject {
            // No writer handle before trusted Context -> all ancestors -> retained anchor validation.
            val current = snapshot(); val intent = read("fixture-intent")
            require(intent.getString("run") == run && intent.getInt("uid") == Process.myUid() &&
                intent.getString("package") == app.packageName && intent.getString("namespace") == namespace && intent.getString("keyAlias") == alias)
            require(intent.getJSONObject("anchor").keys().asSequence().associateWith { intent.getJSONObject("anchor").getString(it) } == current)
            expectedAnchor = current; guard()
            val proof = read("handoff-complete")
            require(proof.getString("run") == run && proof.getInt("pid") == intent.getInt("pid"))
            require(proof.getJSONObject("anchor").keys().asSequence().associateWith { proof.getJSONObject("anchor").getString(it) } == current)
            val cleanup = read("case1-cleanup")
            require(cleanup.getString("run") == run && cleanup.getInt("pid") == proof.getInt("pid") &&
                cleanup.getInt("uid") == Process.myUid() && cleanup.getString("package") == app.packageName &&
                cleanup.getInt("ownedWriterRetireInvocations") > 0)
            require(cleanup.getBoolean("bodyCompleted") && cleanup.getBoolean("callbackWorkersJoined") &&
                cleanup.getBoolean("controllerSettled") && cleanup.getBoolean("frozenOwnershipResolved") &&
                !cleanup.getJSONObject("frozen").getBoolean("acquisitionPending") &&
                !cleanup.getJSONObject("frozen").getBoolean("closeFailureObserved") &&
                !cleanup.getJSONObject("frozen").getBoolean("acquisitionFailureObserved") && !cleanup.getBoolean("fallbackRequired"))
            guard(); return proof
        }
    }

    /** Only the existing typed lifecycle callback is synthetic, never storage success. */
    private class OwnerPort(private val context: Context, initial: NativeSessionStorage) : NativeMigrationLifecycle {
        var current = initial
        var quiesces = 0
        var restores = 0
        var held: NativeMigrationOwnerLease? = null
        private val callbackJob = SupervisorJob()
        private val callbackScope = CoroutineScope(callbackJob + Dispatchers.Main.immediate)
        private var firstCallback: Deferred<Unit>? = null
        private var closing = false
        @Volatile private var acquisitionPending = false
        @Volatile private var acquiredFrozen: NativeVoiceRawGate.Frozen? = null
        @Volatile private var frozenStorage: NativeSessionStorage? = null
        @Volatile private var frozenOperation: String? = null
        @Volatile private var transferredToLease = false
        @Volatile private var acquisitionFailure: Throwable? = null
        @Volatile private var frozenCloseFailure: Throwable? = null
        @Volatile private var frozenCloseAttempts = 0
        @Volatile private var frozenCloseReturns = 0
        @Volatile private var releaseReachedRestorePort = false
        @Volatile private var frozenResolution = "NO_ACQUISITION_ATTEMPTED"
        val entered = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        override val ready = true
        override val canImport = true
        override fun currentStorage() = current
        /** Call only when this exact case owns the recorded handle and no work uses it. */
        private fun closeRecorded(raw: NativeVoiceRawGate.Frozen, resolution: String) {
            frozenCloseAttempts++
            try {
                raw.close(); frozenCloseReturns++; acquiredFrozen = null; frozenResolution = resolution
            } catch (close: Throwable) {
                frozenCloseFailure = close; frozenResolution = "CLOSE_FAILED"; throw close
            }
        }
        override suspend fun quiesce(operationId: String): NativeMigrationOwnerLease {
            check(!closing && held == null && quiesces == 0 && acquiredFrozen == null && !acquisitionPending)
            quiesces++; val store = current
            frozenStorage = store; frozenOperation = operationId; acquisitionPending = true
            frozenResolution = "ACQUIRING"
            var acquired: NativeVoiceRawGate.Frozen? = null
            try {
                // Match MobileSession: assign ownership BEFORE a cancellable dispatcher return.
                withContext(NonCancellable + Dispatchers.IO) {
                    acquired = NativeVoiceRawOwnership.freeze(store, operationId)
                    acquiredFrozen = acquired; frozenResolution = "ACQUIRED_NOT_TRANSFERRED"
                }
                currentCoroutineContext().ensureActive(); check(!closing)
                val lease = NativeMigrationOwnerLease(store, operationId, requireNotNull(acquired)) { true }
                held = lease; transferredToLease = true; frozenResolution = "TRANSFERRED_TO_TYPED_LEASE"
                return lease
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    val raw = acquired
                    if (raw == null) {
                        acquisitionFailure = failure; frozenResolution = "ACQUISITION_FAILED_WITHOUT_RECORDED_HANDLE"
                    } else {
                        try { closeRecorded(raw, "OWN_CLOSE_AFTER_QUIESCE_FAILURE_RETURNED") }
                        catch (close: Throwable) { if (close !== failure) failure.addSuppressed(close) }
                    }
                }
                throw failure
            } finally { acquisitionPending = false }
        }
        override suspend fun restore(lease: NativeMigrationOwnerLease, requiresExplicitEnrollment: Boolean) {
            check(lease === held && transferredToLease && lease.rawWriters === acquiredFrozen)
            // This actual typed callback is reached only after current Controller's
            // prepareAndReleaseForRestore returned. It is not an independent physical-FD proof.
            releaseReachedRestorePort = true
            if (closing) throw CancellationException("case is closing after controller release boundary")
            restores++
            if (restores == 1) {
                val callback = callbackScope.async(start = CoroutineStart.LAZY) {
                    entered.complete(Unit)
                    withTimeout(15_000) { acknowledge.await() }
                    throw IOException("owned typed restore acknowledgement lost after real pointer commit")
                }
                firstCallback = callback; callback.start(); callback.await()
            } else {
                check(restores == 2)
                current = withContext(Dispatchers.IO) { lease.storage.reopen(context) }
            }
        }
        suspend fun stopOwnedCallbacks() {
            withContext(Dispatchers.Main.immediate) { closing = true }
            acknowledge.cancel(CancellationException("case finally releases only owned ACK"))
            firstCallback?.cancelAndJoin()
            callbackJob.cancelAndJoin()
            entered.cancelAndJoin(); acknowledge.cancelAndJoin()
        }
        suspend fun resolveFrozenAfterSettlement() = withContext(NonCancellable + Dispatchers.IO) {
            check(!acquisitionPending) { "Frozen acquisition is still in flight" }
            acquisitionFailure?.let { throw IOException("Acquisition failed without a recorded result; process fallback required", it) }
            frozenCloseFailure?.let { throw IOException("A prior actual close failed; never infer release from FAILED", it) }
            val raw = acquiredFrozen
            if (raw != null) {
                if (releaseReachedRestorePort) {
                    acquiredFrozen = null; frozenResolution = "CONTROLLER_RELEASE_RETURN_OBSERVED_AT_TYPED_PORT"
                } else {
                    // A failed exact requireFrozen is UNKNOWN, never evidence that close succeeded:
                    // gate removal can precede a throwing native close in the real implementation.
                    try { NativeVoiceRawOwnership.requireFrozen(requireNotNull(frozenStorage), requireNotNull(frozenOperation), raw) }
                    catch (unknown: Throwable) {
                        frozenResolution = "RELEASE_UNCONFIRMED"
                        throw IOException("Original Frozen release is unconfirmed; retain and verify original process exit", unknown)
                    }
                    closeRecorded(raw, "OWN_CLOSE_AFTER_SETTLEMENT_RETURNED")
                }
            }
            frozenResolution
        }
        fun frozenFacts() = JSONObject().put("acquisitionPending", acquisitionPending)
            .put("transferredToTypedLease", transferredToLease).put("closeAttempts", frozenCloseAttempts)
            .put("closeReturns", frozenCloseReturns).put("closeFailureObserved", frozenCloseFailure != null)
            .put("acquisitionFailureObserved", acquisitionFailure != null)
            .put("controllerReleaseReachedRestorePort", releaseReachedRestorePort).put("resolution", frozenResolution)
    }

    private suspend fun finishCase(f: Fixture, label: String, controller: NativeMigrationController?, owner: OwnerPort?,
        handles: List<NativeSessionStorage>, bodyCompleted: Boolean) = withContext(NonCancellable) {
        var joined = owner == null; var settled = controller == null
        var frozenResolved = owner == null; var failure: Throwable? = null
        try {
            withTimeout(10_000) {
                owner?.stopOwnedCallbacks(); joined = true
                if (controller != null) {
                    withContext(Dispatchers.Main.immediate) { if (controller.busy) controller.cancel() }
                    awaitState { !controller.busy }
                    val unknown = withContext(Dispatchers.Main.immediate) { controller.phase == NativeMigrationPhase.UNKNOWN }
                    check(!unknown) { "Unknown publication is retained; exact-process fallback required, never resume/reimport for cleanup" }
                    settled = true
                }
                owner?.resolveFrozenAfterSettlement(); frozenResolved = true
            }
        } catch (error: Throwable) { failure = error }
        val phase = withContext(Dispatchers.Main.immediate) {
            if (controller != null && owner != null) controller.detach(owner)
            val observed = controller?.phase?.name ?: "NOT_CREATED"
            if (settled) controller?.close(); observed
        }
        var retired = 0
        if (joined && settled && frozenResolved) {
            val unique = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage, Boolean>())
            for (store in handles + listOfNotNull(owner?.current)) if (unique.add(store)) {
                retired++
                try { store.retireWriter() }
                catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
            }
        }
        try {
            f.publish(label, JSONObject().put("run", f.run).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("package", f.app.packageName).put("bodyCompleted", bodyCompleted)
                .put("callbackWorkersJoined", joined).put("controllerSettled", settled).put("controllerPhaseObserved", phase)
                .put("frozenOwnershipResolved", frozenResolved).put("frozen", owner?.frozenFacts() ?: JSONObject())
                .put("controllerPrivateScopeJoined", false).put("ownedWriterRetireInvocations", retired)
                .put("fallbackRequired", failure != null || !joined || !settled || !frozenResolved).put("exactProcessExitRequiredByRunner", true))
        } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        failure?.let { throw IOException("Owned cleanup incomplete: retain fixture/keys; runner must verify and close only original process", it) }
    }

    private fun hashes(root: File): Map<String, String> {
        require(root.isDirectory && !java.nio.file.Files.isSymbolicLink(root.toPath()))
        return root.walkTopDown().onEnter { require(!java.nio.file.Files.isSymbolicLink(it.toPath())); true }
            .filter { it.isFile }.associate { file ->
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            require(file.length() <= 1_048_576L)
            file.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
    }
    private fun map(value: JSONObject) = value.keys().asSequence().associateWith { value.getString(it) }
    private fun receipt(value: JSONObject) = NativeMigrationCandidateStore.Receipt(value.getString("candidateId"),
        value.getString("sha256"), value.getInt("records"))
    private suspend fun awaitState(block: () -> Boolean) = withTimeout(30_000) {
        while (!withContext(Dispatchers.Main.immediate) { block() }) delay(20)
    }
    private suspend fun originalOperation(store: NativeSessionStorage, value: JSONObject) {
        val records = value.getJSONObject("records")
        val name = records.keys().asSequence().single { it.startsWith("operations:") }
        val original = JSONObject(records.getString(name)).getJSONArray("operations").getJSONObject(0)
        val row = NativeOperationJournal(store).records(name.removePrefix("operations:")).single()
        for (field in listOf("id", "intent", "intentSha256", "result", "phase", "submissionAttempted",
            "detached", "sourceKey", "sourceVersion")) assertEquals(original.get(field), row.body().get(field))
        assertEquals(name.removePrefix("operations:"), row.target)
        assertTrue(row.migrationReadOnly)
        val client = CodexConnectionVault(store).clients().single()
        assertEquals("a".repeat(43), store.token(client.id))
        val sourceMetadata = JSONObject(records.getString("metadata"))
        val activeMetadata = requireNotNull(store.metadata())
        for (field in listOf("drafts", "draftFiles", "draftVersions", "selectedThreads", "expanded", "workspaces")) {
            val expected = sourceMetadata.getJSONObject(field)
            for (key in expected.keys()) assertEquals(expected.get(key).toString(), activeMetadata.getJSONObject(field).get(key).toString())
        }
        assertTrue(requireNotNull(row.replyRecovery).matchesObservation(client, row))
        assertFalse(requireNotNull(row.replyRecovery).mayRetry(client, row))
        assertTrue(runCatching { NativeOperationJournal(store).consumeReplyRetry(client, row.target, row.id) }.isFailure)
        assertTrue(runCatching { NativeOperationJournal(store).markSubmitting(row.target, row.id) }.isFailure)
        assertEquals(records.getString("submission:" + row.id), store.read("submission:" + row.id))
        for (key in records.keys()) if (key.startsWith("draft-file:")) assertEquals(records.getString(key), store.read(key))
    }

    private class CaseResources {
        val handles = mutableListOf<NativeSessionStorage>()
        var owner: OwnerPort? = null
        var controller: NativeMigrationController? = null
        fun own(store: NativeSessionStorage) = store.also { handles.add(it) }
    }
    private suspend fun inOwnedCase(f: Fixture, cleanupName: String, block: suspend (CaseResources) -> Unit) {
        val resources = CaseResources(); var completed = false; var originalFailure: Throwable? = null
        try { block(resources); completed = true }
        catch (error: Throwable) { originalFailure = error; throw error }
        finally {
            try { finishCase(f, cleanupName, resources.controller, resources.owner, resources.handles, completed) }
            catch (cleanupFailure: Throwable) {
                if (originalFailure == null) throw cleanupFailure else originalFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    @Test fun realCommitThenPendingRestoreLostAckAndSameOwnerResume() = runBlocking {
        val f = Fixture(); f.fresh()
        inOwnedCase(f, "case1-cleanup") { resources ->
            val ctx = f.context
            val bootstrap = resources.own(f.store())
            val oldGeneration = withContext(Dispatchers.IO) { bootstrap.publishMigration(UUID.randomUUID().toString(),
                "a".repeat(64), mapOf("metadata" to JSONObject().put("clients", JSONArray()).toString(),
                    "ai-sharing-v1" to "old inert installation consent")) }
            bootstrap.retireWriter()
            val old = resources.own(f.store())
            val oldFolder = File(File(old.coordinationKey), "migration-generation-" + oldGeneration)
            val oldCiphertext = hashes(oldFolder)
            val payload = MigrationActivationFixtures.payload()
            val r = withContext(Dispatchers.IO) { MigrationActivationFixtures.stage(ctx, old, payload) }
            val candidateFolder = File(ctx.noBackupFilesDir, f.namespace + "-" + oldGeneration + "-migration-candidates")
            val candidateCiphertext = hashes(candidateFolder)
            val owner = OwnerPort(ctx, old).also { resources.owner = it }
            val controller = withContext(Dispatchers.Main.immediate) {
                NativeMigrationController(ctx).also { resources.controller = it; it.attach(owner); it.show() }
            }
            awaitState { controller.phase == NativeMigrationPhase.PREVIEW && controller.canImport }
            withContext(Dispatchers.Main.immediate) { controller.confirmImport() }
            withTimeout(30_000) { owner.entered.await() }
            withContext(Dispatchers.Main.immediate) {
                assertEquals(NativeMigrationPhase.RESTORING, controller.phase)
                assertEquals(NativeMigrationResult.IMPORTED, controller.result)
                assertTrue(controller.needsOwnerRestore); assertFalse(controller.ready); assertFalse(controller.canDismiss)
            }
            val generation = f.view().using { saved -> saved.published(r) }
            assertNotEquals(oldGeneration, generation)
            assertEquals(r, old.migrationPublicationReceipt())
            assertEquals(oldCiphertext, hashes(oldFolder)); assertEquals(candidateCiphertext, hashes(candidateFolder))
            owner.acknowledge.complete(Unit)
            awaitState { controller.phase == NativeMigrationPhase.FAILED && controller.result == NativeMigrationResult.RESTORE_FAILED }
            withContext(Dispatchers.Main.immediate) { assertTrue(controller.needsOwnerRestore); assertFalse(controller.ready); controller.resumeOwner() }
            awaitState { controller.phase == NativeMigrationPhase.DONE && controller.result == NativeMigrationResult.IMPORTED }
            assertEquals(1, owner.quiesces); assertEquals(2, owner.restores)
            // These are measured owner-port callbacks. Current resumeOwner -> finishOwner
            // has no activation re-entry by SOURCE inspection, not an observed activate count.
            assertEquals(r, owner.current.migrationPublicationReceipt())
            assertEquals(generation, owner.current.publishedMigration(r.id, r.sha256, r.recordCount))
            originalOperation(owner.current, payload)
            assertNull(owner.current.read("ai-sharing-v1"))
            val client = CodexConnectionVault(owner.current).clients().single()
            val newTarget = client.cacheKey("synthetic-new-target")
            val metadata = requireNotNull(owner.current.metadata())
            metadata.getJSONObject("drafts").put(newTarget, "independent new draft after handoff")
            metadata.getJSONObject("draftVersions").put(newTarget, "after-handoff")
            metadata.getJSONObject("selectedThreads").put(client.id, "synthetic-new-target")
            owner.current.write("metadata", metadata.toString())
            assertTrue(runCatching { old.write("metadata", "late old owner callback") }.isFailure)
            assertEquals("independent new draft after handoff", owner.current.metadata()!!.getJSONObject("drafts").getString(newTarget))
            assertEquals("synthetic-new-target", owner.current.metadata()!!.getJSONObject("selectedThreads").getString(client.id))
            assertEquals(oldCiphertext, hashes(oldFolder)); assertEquals(candidateCiphertext, hashes(candidateFolder))
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = listOf(f.alias, NativeMigrationExistingView.childAlias(f.alias, "migration-candidates", oldGeneration),
                NativeMigrationExistingView.childAlias(f.alias, "migration-candidates", generation)).filter { keys.containsAlias(it) }
            check(f.alias in aliases)
            f.publish("handoff-complete", JSONObject().put("run", f.run).put("pid", Process.myPid())
                .put("candidateId", r.id).put("sha256", r.sha256).put("records", r.recordCount)
                .put("oldGeneration", oldGeneration).put("generation", generation).put("anchor", f.anchor())
                .put("newTarget", newTarget).put("newClientId", client.id)
                .put("oldCiphertext", JSONObject(oldCiphertext)).put("candidateCiphertext", JSONObject(candidateCiphertext))
                .put("payload", payload).put("aliases", JSONArray(aliases))
                .put("ownerQuiesces", owner.quiesces).put("ownerRestores", owner.restores))
        }
    }

    @Test fun freshPidControllerAuditsOriginalReceiptAndPreservesGeneration() = runBlocking {
        val f = Fixture(); val proof = f.proof(); assertNotEquals(proof.getInt("pid"), Process.myPid())
        inOwnedCase(f, "case2-cleanup") { resources ->
            val r = receipt(proof); val ctx = f.context
            f.view().using { saved ->
                assertEquals(proof.getString("generation"), saved.published(r))
                saved.originalCandidate(proof.getString("oldGeneration"), r)
            }
            val current = resources.own(f.store(existing = true))
            val before = hashes(File(current.coordinationKey))
            val owner = OwnerPort(ctx, current).also { resources.owner = it }
            val controller = withContext(Dispatchers.Main.immediate) {
                NativeMigrationController(ctx).also { resources.controller = it; it.attach(owner); it.show() }
            }
            awaitState { controller.phase == NativeMigrationPhase.IDLE && controller.result == NativeMigrationResult.PREVIOUS_IMPORT }
            withContext(Dispatchers.Main.immediate) { controller.confirmImport() }
            assertEquals(0, owner.quiesces); assertEquals(0, owner.restores)
            assertEquals(r, current.migrationPublicationReceipt())
            assertEquals(proof.getString("generation"), current.publishedMigration(r.id, r.sha256, r.recordCount))
            assertEquals("independent new draft after handoff", current.metadata()!!.getJSONObject("drafts").getString(proof.getString("newTarget")))
            assertEquals("synthetic-new-target", current.metadata()!!.getJSONObject("selectedThreads").getString(proof.getString("newClientId")))
            originalOperation(current, proof.getJSONObject("payload"))
            assertEquals(map(proof.getJSONObject("oldCiphertext")), hashes(File(File(current.coordinationKey), "migration-generation-" + proof.getString("oldGeneration"))))
            assertEquals(map(proof.getJSONObject("candidateCiphertext")), hashes(File(ctx.noBackupFilesDir,
                f.namespace + "-" + proof.getString("oldGeneration") + "-migration-candidates")))
            assertEquals(before, hashes(File(current.coordinationKey)))
            f.publish("cold-audit-complete", JSONObject().put("run", f.run).put("pid", Process.myPid())
                .put("writerPid", proof.getInt("pid")).put("candidateId", r.id).put("sha256", r.sha256)
                .put("generation", proof.getString("generation"))
                .put("ownerQuiesces", owner.quiesces).put("ownerRestores", owner.restores)
                .put("activationReentryEvidence", "source-derived current confirmImport/resumeOwner path; no actual-boundary counter"))
        }
    }
}
