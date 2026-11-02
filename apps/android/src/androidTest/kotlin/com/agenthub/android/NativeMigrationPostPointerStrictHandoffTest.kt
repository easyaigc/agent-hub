package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val FIRST_METHOD="actualStrictPostPointerFirstPrepareReconcilesAndTransfersOnce"
private const val COLD_METHOD="distinctPidReadOnlyAuditsExactModernCandidateReceiptAndPostRestoreBaseline"
private fun JSONObject.p7String(name: String): String=(get(name) as? String) ?: error("Expected JSON string: $name")
private fun JSONObject.p7Boolean(name: String): Boolean=(get(name) as? Boolean) ?: error("Expected JSON boolean: $name")
private fun JSONObject.p7Int(name: String): Int {
    val v=get(name);require(v is Int || v is Long);val n=(v as Number).toLong();require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong());return n.toInt()
}
private fun processStartTicks(pid: Int): String {
    val value=File("/proc/$pid/stat").readText();require(value.length in 1..8192 && value.startsWith("$pid ("))
    return value.substring(value.lastIndexOf(')')+2).trim().split(Regex("\\s+")).get(19).also {require(it.matches(Regex("[0-9]+")))}
}
private fun p7Canonical(v: Any): String=when(v) {
    is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+p7Canonical(v.get(it))}
    is String -> JSONObject.quote(v)
    is Boolean -> if(v)"true" else "false"
    is Int, is Long -> v.toString()
    else -> error("Unsupported outer canonical scalar")
}

/** R2 PROPOSED: uncompiled/unexecuted; stable exclusive fixture only; requires Stage66 production B, never observer-only A.
 * Two individually selected instrumentation methods; explicit fresh canonical postPointerRun.
 * Actual ExistingView.writer + MobileSession + Frozen/lease + production restore helper.
 * No Activity, Controller, live Host/model, notification coordinator, global hook or reflection.
 * One modern streaming envelope/local v4 candidate; this does not claim legacy acceptance.
 * Retain this exact run/keys on success for cold audit and collection; retain failures forever
 * until Root acknowledges collection. No app clear, default family or alias enumeration.
 */
class NativeMigrationPostPointerStrictHandoffTest {
    private class Fixture {
        val args = InstrumentationRegistry.getArguments()
        val selectedMethod = requireNotNull(args.getString("postPointerSelectedMethod")).also {
            require(it in setOf(FIRST_METHOD, COLD_METHOD))
        }
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim().also { require(UUID.fromString(it).toString() == it) }
        private fun apkHash(context: Context): String {
            require(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "Split APK admission is not defined" }
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n=input.read(buffer); if(n<0)break; digest.update(buffer,0,n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val binding: JSONObject by lazy {
            fun pin(name: String) = requireNotNull(args.getString(name)).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val appSha=apkHash(app); val testSha=apkHash(InstrumentationRegistry.getInstrumentation().context)
            require(appSha==pin("postPointerAppApkSha256") && testSha==pin("postPointerTestApkSha256"))
            JSONObject().put("appApkSha256",appSha).put("testApkSha256",testSha)
                .put("testSourceSha256",pin("postPointerTestSourceSha256"))
                .put("sourceGuardSha256",pin("postPointerSourceGuardSha256"))
                .put("sourceAdmissionSha256",pin("postPointerSourceAdmissionSha256"))
                .put("bootUuid",boot).put("package",app.packageName).put("uid",Process.myUid()).put("run",run)
        }
        fun requireBinding(value: JSONObject) {
            require(value.keys().asSequence().toSet()==binding.keys().asSequence().toSet())
            require(p7Canonical(value)==p7Canonical(binding))
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("postPointerRun"))
            .also { require(UUID.fromString(it).toString() == it) }
        // Resolve only the trusted Context root. All fixture paths below use this
        // canonical root, and are lstat/NOFOLLOW checked before any fixture open.
        private val filesRoot = app.filesDir.canonicalFile
        val folder = File(filesRoot, "P7-postpointer-" + run)
        val alias = "p7-postpointer-" + run
        val namespace = "installation"
        val sourceNamespace = "source"
        val sourceAlias = alias + "-source"
        private var expectedAnchor: Map<String, String>? = null
        private val readDigests = mutableMapOf<String, String>()
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
            require(android.system.OsConstants.S_ISDIR(before.st_mode) && file.canonicalFile == file)
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
            // Passive, bounded mount observation only; no claim against transient hostile swaps.
            val mounts=File("/proc/self/mountinfo").readText();require(mounts.length<=1024*1024)
            for(line in mounts.lineSequence().filter {it.isNotBlank()}) {
                val fields=line.split(' ');require(fields.size>=10)
                val point=fields[4].replace("\\040"," ").replace("\\011","\t").replace("\\134","\\")
                if(point=="/"||filesRoot.path==point||filesRoot.path.startsWith(point+"/")||point.startsWith(folder.path+"/"))
                    result["observed-mount:"+point]=line
            }
            return result
        }
        private fun snapshot(): Map<String, String> = linkedMapOf<String, String>().apply {
            putAll(rootChain()); put("package", app.packageName); put("uid", Process.myUid().toString())
            put("dataScopeIdentity", directory(File(app.applicationInfo.dataDir).canonicalFile, true, false))
            put("run", run); put("filesRoot", filesRoot.path)
            put("runDirectory", directory(folder, true, true))
            require(folder.canonicalFile == File(filesRoot, "P7-postpointer-" + run))
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
            binding // Admission pins and both actual APK hashes precede all fixture writes.
            rootChain()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for(base in listOf(alias, sourceAlias)) {
                require(!keys.containsAlias(base))
                for(suffix in listOf("migration-candidates", "external-inbox", "recent-photos", "photo-permission", "document-results", "photo-results", "camera-results")) {
                    val child = NativeMigrationExistingView.childAlias(base, suffix, null)
                    require(!keys.containsAlias(child))
                    if(suffix.endsWith("-results")) require(!keys.containsAlias(NativeMigrationExistingView.childAlias(child, "payloads", null)))
                    if(suffix == "camera-results") require(!keys.containsAlias(NativeMigrationExistingView.childAlias(child, "outputs", null)))
                }
            }
            require(java.nio.file.Files.notExists(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            check(folder.mkdir()); android.system.Os.chmod(folder.path, 0x1c0)
            for (name in listOf("no_backup", "cache", "files")) {
                val child = File(folder, name); check(child.mkdir()); android.system.Os.chmod(child.path, 0x1c0)
            }
            expectedAnchor = snapshot()
            publish("fixture-intent", JSONObject().put("run", run).put("pid", Process.myPid()).put("startTicks", processStartTicks(Process.myPid()))
                .put("uid", Process.myUid()).put("package", app.packageName).put("namespace", namespace)
                .put("keyAlias", alias).put("scope", "actual-strict-postpointer-modern-v4-only").put("anchor", anchor()))
        }
        fun store(source: Boolean = false): NativeSessionStorage {
            guard(); val opened = NativeSessionStorage(context, if(source) sourceNamespace else namespace, if(source) sourceAlias else alias)
            try { guard(); return opened } catch (error: Throwable) {
                try { opened.retireWriter() } catch (close: Throwable) { if(close!==error)error.addSuppressed(close) }
                throw error
            }
        }
        fun view(source: Boolean = false): NativeMigrationExistingView {
            guard(); val opened = NativeSessionStorage.existingMigration(context, context.noBackupFilesDir, if(source) sourceNamespace else namespace, if(source) sourceAlias else alias)
            try { guard(); return opened } catch (error: Throwable) {
                // using passes the guard failure into the actual read lifetime before any native close/hold.
                try { opened.using<Unit> { throw error } } catch (close: Throwable) { if(close!==error)error.addSuppressed(close) }
                throw error
            }
        }
        fun publish(name: String, value: JSONObject) {
            require(name in setOf("fixture-intent", "handoff-complete", "case1-cleanup", "cold-audit-complete", "case2-cleanup", "failure", "publication-intent"))
            guard(); val file = File(folder, name + ".json")
            value.put("schemaVersion",2).put("binding",binding).put("selectedMethod",selectedMethod)
            val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..131072)
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
            require(name in setOf("fixture-intent", "handoff-complete", "case1-cleanup", "publication-intent"))
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
            val parsed = StrictMigrationJson.parse(bytes)
            require(parsed.p7Int("schemaVersion")==2); requireBinding(parsed.getJSONObject("binding"))
            readDigests[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return parsed
        }
        fun retainedSha(name: String) = requireNotNull(readDigests[name])
        fun retained(name: String): JSONObject = read(name)
        fun admitCold(): Pair<JSONObject, JSONObject> {
            val current = snapshot(); val intent = read("fixture-intent")
            require(intent.p7String("selectedMethod")==FIRST_METHOD)
            require(intent.p7String("run") == run && intent.p7Int("uid") == Process.myUid() &&
                intent.p7String("package") == app.packageName && intent.p7String("namespace") == namespace &&
                intent.p7String("keyAlias") == alias && strings(intent.getJSONObject("anchor")) == current)
            expectedAnchor = current; guard()
            val proof = read("handoff-complete"); val cleanup = read("case1-cleanup")
            require(proof.p7String("selectedMethod")==FIRST_METHOD && cleanup.p7String("selectedMethod")==FIRST_METHOD)
            require(proof.p7String("run") == run && proof.p7Int("pid") == intent.p7Int("pid") &&
                proof.p7Int("uid") == Process.myUid() && proof.p7String("package") == app.packageName &&
                strings(proof.getJSONObject("anchor")) == current)
            require(cleanup.p7String("run") == run && cleanup.p7Int("pid") == proof.p7Int("pid") &&
                cleanup.p7Int("uid") == Process.myUid() && cleanup.p7String("package") == app.packageName &&
                cleanup.p7String("startTicks") == proof.p7String("startTicks") &&
                cleanup.p7Boolean("bodyCompleted") && cleanup.p7Boolean("testWorkersJoined") &&
                cleanup.p7Boolean("sessionWorkersJoined") && cleanup.p7Boolean("frozenReleaseReturned") &&
                cleanup.p7Boolean("writersRetired") && !cleanup.p7Boolean("fallbackRequired") &&
                cleanup.p7Boolean("outerProcessExitRequired") && !cleanup.p7Boolean("controllerInstantiated") &&
                cleanup.p7Int("unknownAcquisitions")==0 && cleanup.p7Int("cleanupFailureCount")==0)
            return proof to cleanup
        }
        /** Inspect only the owned fixture; never enter a symlink or enumerate Keystore. */
        fun treeAnchors(): Map<String, String> {
            guard(); val result = linkedMapOf<String, String>(); var count = 0
            folder.walkTopDown().onEnter { dir ->
                require(++count <= 256); result[dir.relativeTo(folder).path] = directory(dir, true, true); true
            }.filter { !android.system.OsConstants.S_ISDIR(android.system.Os.lstat(it.path).st_mode) }.forEach { file ->
                require(++count <= 4096 && file.canonicalFile.parentFile == file.parentFile)
                val st = android.system.Os.lstat(file.path)
                require(android.system.OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() &&
                    st.st_nlink == 1L && st.st_mode and 0x3f == 0 && st.st_size <= 23L * 1024 * 1024)
            }
            guard(); return result
        }
        companion object {
            private fun strings(v: JSONObject) = v.keys().asSequence().associateWith { v.p7String(it) }
        }
    }

    /** Concrete typed boundaries. Unexpected reads/commands terminate locally; no native fallback. */
    private class Ports : HostReads, CodexCommands {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        private fun deny(name: String): Nothing { attempts.incrementAndGet(); error("postpointer fixture forbids $name") }
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace = deny("workspace")
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory = deny("history")
        override suspend fun models(client: MobileClient): List<CodexModel> = deny("models")
        override suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long): EventBatch = deny("events")
        override suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt = deny("send")
        override suspend fun receipt(client: MobileClient, threadId: String, operationId: String): DeliveryReceipt = deny("receipt")
        override suspend fun createThread(client: MobileClient, projectId: String?, operationId: String): MobileThread = deny("createThread")
        override suspend fun prepareCreation(client: MobileClient, intent: CreationIntent): DesktopCreation = deny("prepareCreation")
        override suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean): DesktopCreation = deny("creation")
        override suspend fun submitCreation(client: MobileClient, operationId: String): DesktopCreation = deny("submitCreation")
        override suspend fun detachCreation(client: MobileClient, operationId: String): DesktopCreation = deny("detachCreation")
        override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen = deny("open")
        override suspend fun stop(client: MobileClient, threadId: String, turnId: String): Unit = deny("stop")
        override suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String): Unit = deny("cancelQueue")
        override suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String, List<String>>): Unit = deny("answer")
        override suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray): MobileFile = deny("upload")
        override suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray): Unit = deny("uploadCreation")
        override suspend fun acknowledgeRead(client: MobileClient, request: NativePendingRead): JSONObject = deny("acknowledgeRead")
    }
    /** Supported typed system ports. Permission/camera decisions deny without querying OS.
     * Catalogue/content operations are unexpected and fail locally; these assertions are not OS port acceptance. */
    private class DeniedMedia: NativeRecentPhotoSource {
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        private fun deny(): Nothing {attempts.incrementAndGet();error("No media catalogue access in empty postpointer graph")}
        override fun access()=NativePhotoAccess.NONE
        override fun permissions()=arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        override fun observe(changed: ()->Unit): ()->Unit=deny()
        override suspend fun snapshot(limit: Int): List<NativeRecentPhoto> = deny()
        override suspend fun thumbnail(photo: NativeRecentPhoto): android.graphics.Bitmap?=deny()
        override suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput=deny()
    }
    private class DeniedCamera: NativeCameraAccess {
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        override fun granted(): Boolean {attempts.incrementAndGet();return false}
        override fun available(): Boolean {attempts.incrementAndGet();return false}
    }
    private class PostPointerFault : IOException("B1 owned AFTER_POINTER acknowledgement fault")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun strings(v: JSONObject) = v.keys().asSequence().associateWith { v.p7String(it) }
    private fun receipt(v: JSONObject) = NativeMigrationCandidateStore.Receipt(v.p7String("candidateId"), v.p7String("sha256"), v.p7Int("records"))
    private fun receiptJson(r: NativeMigrationCandidateStore.Receipt) = JSONObject().put("candidateId", r.id).put("sha256", r.sha256).put("records", r.recordCount)
    private fun startTicks(pid: Int): String = processStartTicks(pid)
    private fun originalExited(pid: Int, ticks: String): Boolean {
        val stat = File("/proc/$pid/stat")
        if (java.nio.file.Files.notExists(stat.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        // An unreadable/unknown proc entry throws; it never counts as a proved exit.
        return startTicks(pid) != ticks
    }
    private fun materialize(records: NativeMigrationStreamRecords): Map<String, String> {
        val text = records.text.keys.sorted().associateWith { records.text.getValue(it) }
        records.binary.values.forEach { it.verifiedCopy(nullOutputStreamCompat()) }
        require(text.size + records.binary.size == records.size); return text
    }
    private fun textDigests(text: Map<String, String>) = text.mapValues { sha(it.value.toByteArray(Charsets.UTF_8)) }
    private fun oldCipher(all: Map<String, String>, generation: String) = all.filterKeys { it.startsWith("migration-generation-$generation/") }
    private fun candidateHeader(f: Fixture, origin: String): Int {
        val name = f.namespace + "-" + origin + "-migration-candidates"
        val alias = NativeMigrationExistingView.childAlias(f.alias, "migration-candidates", origin)
        return NativeSessionStorage.existingMigration(f.context, f.context.noBackupFilesDir, name, alias).using {
            materialize(it.records); it.familyCiphertext()
            JSONObject(it.records.text.getValue("candidate-v1")).p7Int("version")
        }
    }
    private fun graph(active: Map<String, String>, original: Map<String, String>,afterLocalDraftSave: Boolean=false): SavedOperation {
        val key = original.keys.single { it.startsWith("operations:") }; val target = key.removePrefix("operations:")
        val prior = NativeOperationJournal.validateExistingMigrationRecords(target, JSONObject(original.getValue(key))).single()
        val row = NativeOperationJournal.validateExistingMigrationRecords(target, JSONObject(active.getValue(key))).single()
        // Full immutable body, target, IDs, intent string/fingerprint, uncertainty and source linkage.
        for (field in listOf("id", "kind", "target", "intent", "intentSha256", "phase", "result", "error",
            "detached", "submissionAttempted", "sourceKey", "sourceVersion", "parentOperationId", "forkContext", "sharingScope"))
            assertEquals(field, NativeMigrationRecoveryCanonical.value(JSONObject().put("v", prior.body().get(field))),
                NativeMigrationRecoveryCanonical.value(JSONObject().put("v", row.body().get(field))))
        assertTrue(row.migrationReadOnly); assertEquals("uncertain", row.phase); assertTrue(row.submissionAttempted)
        val originalClient = decodeClient(JSONObject(original.getValue("metadata")).getJSONArray("clients").getJSONObject(0))
        val currentClient = decodeClient(JSONObject(active.getValue("metadata")).getJSONArray("clients").getJSONObject(0))
        assertEquals(CodexCommandCodec.target(requireNotNull(originalClient.scope)).toString(), CodexCommandCodec.target(requireNotNull(currentClient.scope)).toString())
        assertEquals(originalClient.id, currentClient.id); assertEquals(originalClient.endpoint, currentClient.endpoint)
        assertEquals(originalClient.codexDirect?.fingerprint, currentClient.codexDirect?.fingerprint)
        assertTrue(requireNotNull(row.replyRecovery).matchesObservation(currentClient, row))
        assertFalse(requireNotNull(row.replyRecovery).mayRetry(currentClient, row))
        assertEquals("uncertain", row.replyRecovery!!.phase); assertNull(row.replyRecovery!!.inspection)
        val expectedRecovery = JSONObject(requireNotNull(prior.replyRecovery).body().toString()).put("phase", "uncertain").put("inspection", JSONObject.NULL)
        assertEquals(NativeMigrationRecoveryCanonical.value(expectedRecovery), NativeMigrationRecoveryCanonical.value(row.replyRecovery!!.body()))
        assertEquals(original.getValue("submission:" + row.id), active.getValue("submission:" + row.id))
        for (name in original.keys.filter { it.startsWith("draft-file:") || it.startsWith("draft-thumbnail-v1:") })
            assertEquals(original.getValue(name), active.getValue(name))
        val metadata = JSONObject(active.getValue("metadata")); val source = JSONObject(original.getValue("metadata"))
        for (field in listOf("drafts", "draftFiles", "draftVersions", "selectedThreads", "expanded", "workspaces")) {
            val wanted = source.getJSONObject(field); val found = metadata.getJSONObject(field)
            for (name in wanted.keys()) assertEquals(wanted.get(name).toString(), found.get(name).toString())
        }
        val beforeVault = JSONObject(original.getValue("codex-private-connections-v1")).getJSONArray("connections").getJSONObject(0)
        val afterVault = JSONObject(active.getValue("codex-private-connections-v1")).getJSONArray("connections").getJSONObject(0)
        // Credential bytes stay device-local. Assertion/proof must not print either credential.
        assertTrue("Synthetic credential retained exactly", beforeVault.p7String("token") == afterVault.p7String("token"))
        assertEquals(0, active["ai-sharing-v1"]?.let { JSONObject(it).getJSONArray("grants").length() } ?: 0)
        for(field in listOf("photoSources","attachmentImportGenerations")) {
            if(afterLocalDraftSave) {
                // Normal Session autosave regenerates empty local containers;
                // transferred device records must still be absent from them.
                assertEquals("$field must contain no transferred device records",0,metadata.getJSONObject(field).length())
            }else assertFalse("Imported metadata must omit $field",metadata.has(field))
        }
        return row
    }
    private suspend fun noReplay(store: NativeSessionStorage, original: Map<String, String>, ports: Ports,afterLocalDraftSave: Boolean=false) {
        val active = original.keys.associateWith { requireNotNull(store.read(it)) }
        val row = graph(active, original,afterLocalDraftSave); val client = CodexConnectionVault(store).clients().single()
        val journal = NativeOperationJournal(store)
        assertEquals(row, journal.records(row.target).single())
        check(runCatching { journal.consumeReplyRetry(client, row.target, row.id) }.isFailure)
        check(runCatching { journal.markSubmitting(row.target, row.id) }.isFailure)
        val flow = NativeCodexFlow(store, ports, ports)
        check(runCatching { flow.retryReply(client, row, { true }, {}) }.isFailure)
        check(runCatching { flow.submit(client, "synthetic-thread", null, SubmissionOrigin(requireNotNull(row.sourceKey), requireNotNull(row.sourceVersion)),
            CodexContent("must never replace immutable intent", emptyList(), GenerationSettings()), "next-turn", null, guard = { true }, operationId = row.id) }.isFailure)
        assertEquals(0, ports.attempts.get())
        assertEquals(original.getValue("submission:" + row.id), store.read("submission:" + row.id))
    }
    /** Exact allowlist derived from production childAlias; containsAlias only, never aliases(). */
    private fun knownAliases(f: Fixture, origin: String, generation: String): JSONObject {
        val names = linkedSetOf(f.alias, f.sourceAlias)
        for ((base, generations) in listOf(f.alias to listOf<String?>(null, origin, generation), f.sourceAlias to listOf<String?>(null))) {
            for (gen in generations) for (suffix in listOf("migration-candidates", "external-inbox", "recent-photos", "photo-permission", "document-results", "photo-results", "camera-results")) {
                val alias = NativeMigrationExistingView.childAlias(base, suffix, gen); names.add(alias)
                if (suffix.endsWith("-results")) names.add(NativeMigrationExistingView.childAlias(alias, "payloads", null))
                if (suffix == "camera-results") names.add(NativeMigrationExistingView.childAlias(alias, "outputs", null))
            }
        }
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(keyStore.containsAlias(f.alias) && keyStore.containsAlias(f.sourceAlias))
        return JSONObject().also { result -> names.sorted().forEach { result.put(it, keyStore.containsAlias(it)) } }
    }
    private fun namespaceInventory(f: Fixture, origin: String, generation: String) {
        val expected = mutableSetOf(f.namespace, f.sourceNamespace, "voice-recordings-writer-v1.lock", "voice-recordings-v1")
        for ((namespace, generations) in listOf(f.namespace to listOf<String?>(null, origin, generation), f.sourceNamespace to listOf<String?>(null)))
            for (gen in generations) for (suffix in listOf("migration-candidates", "external-inbox", "recent-photos", "photo-permission", "document-results", "photo-results", "camera-results")) {
                val child = namespace + (gen?.let { "-$it" } ?: "") + "-" + suffix
                expected.add(child)
                if(suffix.endsWith("-results")) {
                    expected.add(child + "-payloads");expected.add(child + "-payloads.inbox-writer-v1.lock")
                }
                if(suffix == "camera-results") {
                    expected.add(child + "-outputs");expected.add(child + "-outputs.inbox-writer-v1.lock")
                }
                expected.add(child + ".inbox-writer-v1.lock")
            }
        require(requireNotNull(f.context.noBackupFilesDir.listFiles()).all { it.name in expected }) { "Unexplained owned namespace retained" }
    }
    /** Identity ledger is synchronized; acquisition registration precedes every dispatcher return. */
    private class Resources {
        private val lock=Any()
        val job=SupervisorJob(); val scope=CoroutineScope(job+Dispatchers.Main.immediate)
        private val callbacks=mutableListOf<Deferred<Unit>>()
        private val sessions=mutableListOf<MobileSession>()
        private val stores=mutableListOf<NativeSessionStorage>()
        private val retired=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage,Boolean>())
        private val retireAttempted=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeSessionStorage,Boolean>())
        private val attempts=linkedMapOf<String,String>(); private var serial=0
        @Volatile var lease: NativeMigrationOwnerLease?=null
        @Volatile var quiesceAttempted=false
        @Volatile var releaseAttempted=false
        @Volatile var releaseReturned=false
        @Volatile var completed=false
        @Volatile var successfulGeneration: String?=null
        fun own(store: NativeSessionStorage)=synchronized(lock) { if(stores.none {it===store})stores.add(store); store }
        fun own(session: MobileSession)=synchronized(lock) { if(sessions.none {it===session})sessions.add(session); session }
        private fun begin(label: String)=synchronized(lock) { (++serial).toString()+":"+label }.also { synchronized(lock){attempts[it]="unknown"} }
        private fun resolved(id: String)=synchronized(lock) { attempts[id]="registered" }
        fun construct(label: String,create: ()->MobileSession): MobileSession {
            val id=begin(label);return own(create()).also {resolved(id)}
        }
        fun unknownCount()=synchronized(lock) { attempts.values.count {it=="unknown"} }
        fun attemptSnapshot()=synchronized(lock) { JSONObject(attempts.toMap()) }
        suspend fun acquire(label: String,open: ()->NativeSessionStorage): NativeSessionStorage = withContext(NonCancellable+Dispatchers.IO) {
            val id=begin(label); own(open()).also {resolved(id)}
        }
        /** Called inside an already non-cancellable IO acquisition block. */
        fun cacheGraph(context: Context,root: NativeSessionStorage,prepare: Boolean): Map<String,NativeSessionStorage> {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            val result=linkedMapOf<String,NativeSessionStorage>()
            for(path in NativeMigrationExistingPolicy.paths.sortedWith(compareBy<String> {it.count {c->c=='/'}}.thenBy {it})) {
                val parentPath=path.substringBeforeLast('/',""); val parent=if(parentPath.isEmpty())root else result.getValue(parentPath)
                val id=begin("child:$path")
                val child=if(prepare)parent.prepareChild(context,path.substringAfterLast('/')) else parent.child(context,path.substringAfterLast('/'))
                result[path]=own(child); resolved(id)
            }
            return result
        }
        suspend fun captureLease(block: suspend ()->NativeMigrationOwnerLease)=withContext(NonCancellable) {
            val id=begin("Frozen+actual-lease"); quiesceAttempted=true
            block().also {lease=it;resolved(id)}
        }
        suspend fun restore(block: suspend ()->Unit) {
            val id=begin("production-helper-root+children")
            block(); resolved(id) // Failure remains unknown even when a known root was captured.
        }
        fun markRetired(store: NativeSessionStorage)=synchronized(lock){retired.add(store);retireAttempted.add(store);Unit}
        fun retire(store: NativeSessionStorage) {
            val attempt=synchronized(lock){retireAttempted.add(store)}
            if(!attempt)return // Never retry an attempted native/serial writer close.
            store.retireWriter(); synchronized(lock){retired.add(store)}
        }
        fun isRetired(store: NativeSessionStorage)=synchronized(lock){store in retired}
        fun callbackSnapshot()=synchronized(lock){callbacks.toList()}
        fun sessionSnapshot()=synchronized(lock){sessions.toList().asReversed()}
        fun storeSnapshot()=synchronized(lock){stores.toList().asReversed()}
        suspend fun main(action: suspend ()->Unit) {
            val callback=scope.async(start=CoroutineStart.LAZY){action()}; synchronized(lock){callbacks.add(callback)}
            callback.start(); withTimeout(40_000){callback.await()}
        }
    }
    private suspend fun ownedCase(f: Fixture,cold: Boolean=false,body: suspend (Resources)->Unit) {
        val r=Resources(); var first: Throwable?=null
        try {body(r);r.completed=true}
        catch(error: Throwable) {
            first=error
            try {withContext(NonCancellable+Dispatchers.IO){f.publish("failure",JSONObject().put("run",f.run)
                .put("pid",Process.myPid()).put("startTicks",startTicks(Process.myPid())).put("failureType",error.javaClass.name)
                .put("suppressedCount",error.suppressed.size).put("cold",cold).put("noReplay",true).put("acquisitions",r.attemptSnapshot()))}}
            catch(proofError: Throwable){if(proofError!==error)error.addSuppressed(proofError)}
            throw error
        } finally {
            try {finish(f,r,cold)}catch(close: Throwable){if(first==null)throw close else if(close!==first)first.addSuppressed(close)}
        }
    }
    /** No coroutine timeout is represented as a native-close or read-lifetime-hold resolution.
     * External runner timeout means UNKNOWN + exact-original-process fallback, and forbids cold. */
    private suspend fun finish(f: Fixture,r: Resources,cold: Boolean)=withContext(NonCancellable) {
        var first: Throwable?=null; var failures=0; var testJoined=false; var sessionJoined=true; var retired=true
        var baseline: Map<String,String>?=null
        fun remember(error: Throwable){failures++;if(first==null)first=error else if(first!==error)first!!.addSuppressed(error)}
        suspend fun attempt(action: suspend ()->Unit): Boolean=try {action();true}catch(error: Throwable){remember(error);false}
        testJoined=attempt {r.callbackSnapshot().forEach {it.cancel()};r.callbackSnapshot().forEach {it.join()};r.job.cancelAndJoin()}
        // Release failure does not skip otherwise independent Session/store attempts.
        if(!r.releaseAttempted&&r.lease!=null)attempt {withContext(Dispatchers.IO){
            r.releaseAttempted=true;r.lease!!.releaseRawWriters();r.releaseReturned=true
        }}
        if(r.quiesceAttempted&&r.lease==null)remember(IOException("Actual quiesce unknown; exact original process fallback required"))
        if(r.releaseAttempted&&!r.releaseReturned)remember(IOException("Attempted raw close unconfirmed; never retry"))
        for(session in r.sessionSnapshot())if(!attempt {withContext(Dispatchers.Main.immediate){session.abandonMigrationRestore()}})sessionJoined=false
        for(store in r.storeSnapshot()) {
            // Queue wait failure must not skip that independent known writer's one close attempt.
            if(!r.isRetired(store))if(!attempt {withContext(Dispatchers.IO){store.awaitQueuedWrites()}})retired=false
            if(!attempt {withContext(Dispatchers.IO){r.retire(store)}})retired=false
            if(!r.isRetired(store))retired=false
        }
        if(r.unknownCount()!=0)remember(IOException("Unresolved acquisition; known cleanup cannot prove absence of unknown handles"))
        if(r.completed&&first==null)attempt {withContext(Dispatchers.IO){
            baseline=f.view().using {saved->r.successfulGeneration?.let {assertEquals(it,saved.generation)};materialize(saved.records);saved.familyCiphertext()}
            f.treeAnchors()
        }}
        val cleanup=JSONObject().put("run",f.run).put("pid",Process.myPid()).put("startTicks",startTicks(Process.myPid()))
            .put("uid",Process.myUid()).put("package",f.app.packageName).put("bodyCompleted",r.completed)
            .put("testWorkersJoined",testJoined).put("sessionWorkersJoined",sessionJoined).put("writersRetired",retired)
            .put("quiesceAttempted",r.quiesceAttempted).put("releaseAttempted",r.releaseAttempted)
            .put("frozenReleaseReturned",r.releaseReturned).put("controllerInstantiated",false)
            .put("outerProcessExitRequired",true).put("fallbackRequired",first!=null).put("cleanupFailureCount",failures)
            .put("unknownAcquisitions",r.unknownCount()).put("acquisitions",r.attemptSnapshot())
            .put("retainedFor","exact cold audit and Root collection acknowledgement; no replay")
        baseline?.let {cleanup.put("postRestoreCiphertext",JSONObject(it))}
        if(r.completed&&first==null)attempt {withContext(Dispatchers.IO){cleanup.put("directoryAnchors",JSONObject(f.treeAnchors()))}}
        // A guard failure above must not publish stale success booleans.
        cleanup.put("fallbackRequired",first!=null).put("cleanupFailureCount",failures)
        attempt {withContext(Dispatchers.IO){f.publish(if(cold)"case2-cleanup" else "case1-cleanup",cleanup)}}
        first?.let {throw IOException("Owned cleanup incomplete; preserve fixture/keys; exact original process fallback; no cold",it)}
    }

    @Test fun actualStrictPostPointerFirstPrepareReconcilesAndTransfersOnce() = runBlocking {
        val f = Fixture(); require(f.selectedMethod==FIRST_METHOD); f.fresh()
        ownedCase(f) { r ->
            val ctx = f.context; val ports = Ports(); val pid = Process.myPid(); val ticks = startTicks(pid)
            val payload = MigrationActivationFixtures.payload(); val entries = payload.getJSONObject("records")
            val client = MigrationActivationFixtures.client(payload)
            val image = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            val png = java.io.ByteArrayOutputStream()
            try { image.eraseColor(android.graphics.Color.CYAN); check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)) }
            finally { image.recycle() }
            val imageBytes = png.toByteArray(); val metadata = JSONObject(entries.p7String("metadata"))
            val fileId = metadata.getJSONObject("draftFiles").getJSONArray(client.cacheKey("synthetic-thread")).getJSONObject(0).p7String("id")
            val imageFile = CreationFile(fileId, "owned-postpointer.png", "image/png", imageBytes.size.toLong(), sha(imageBytes))
            metadata.getJSONObject("draftFiles").getJSONArray(client.cacheKey("synthetic-thread")).put(0, imageFile.body())
            entries.put("metadata", metadata.toString()).put("draft-file:$fileId", JSONObject().put("file", imageFile.body())
                .put("bytes", java.util.Base64.getEncoder().encodeToString(imageBytes)).toString())
            val submissionName = entries.keys().asSequence().single { it.startsWith("submission:") }
            entries.put(submissionName, JSONObject(entries.p7String(submissionName)).apply { getJSONArray("files").put(0, imageFile.body()) }.toString())
            val seedRecords = strings(entries)
            val consent = AISharingGrant(UUID.randomUUID().toString(), AISharingScope(requireNotNull(client.scope), "synthetic-model"),
                "Synthetic source", "Codex", "Synthetic model", 1L)
            val source = r.acquire("source-root") { f.store(source = true) }
            val sourceCandidate=r.acquire("source-candidate-cache") {source.prepareChild(ctx,"migration-candidates")}
            val envelope = File(ctx.cacheDir, "original-stream-v4.migration")
            withContext(Dispatchers.IO) {
                seedRecords.forEach { (name, text) -> source.write(name, text) }
                DraftAttachmentThumbnails.storeIfMissing(source, imageFile, imageBytes)
                val preview = DraftAttachmentThumbnails.read(source, imageFile)
                assertTrue(preview.found); assertNotNull(preview.png); imageBytes.fill(0)
                source.write("ai-sharing-v1", JSONObject().put("version", 1).put("grants", JSONArray().put(consent.json())).toString())
                source.awaitQueuedWrites()
                NativeMigrationExport(ctx, source).sealFile(envelope, "owned postpointer streaming password")
                assertTrue(NativeMigrationStreamCrypto.isEnvelope(envelope))
                source.awaitQueuedWrites(); sourceCandidate.awaitQueuedWrites(); r.retire(sourceCandidate); r.retire(source)
            }
            val sourceObserved = withContext(Dispatchers.IO) {
                f.view(source = true).using { saved -> materialize(saved.records) to saved.familyCiphertext() }
            }
            val sourceMap = sourceObserved.first; val sourceCipher = sourceObserved.second
            val seed = r.acquire("seed-root") { f.store() }
            val emptyMetadata = JSONObject(seedRecords.getValue("metadata")).apply {
                put("clients", JSONArray()); put("selected", ""); put("drafts", JSONObject()); put("draftFiles", JSONObject())
                put("draftVersions", JSONObject()); put("selectedThreads", JSONObject()); put("expanded", JSONObject()); put("workspaces", JSONObject())
                remove("attachmentImportGenerations")
            }
            val origin = withContext(NonCancellable + Dispatchers.IO) {
                val gen = seed.publishMigration(UUID.randomUUID().toString(), "b".repeat(64), mapOf("metadata" to emptyMetadata.toString()))
                val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                for (suffix in listOf("migration-candidates", "external-inbox", "recent-photos", "photo-permission", "document-results", "photo-results", "camera-results")) {
                    val child = NativeMigrationExistingView.childAlias(f.alias, suffix, gen)
                    require(!keys.containsAlias(child))
                    if(suffix.endsWith("-results")) require(!keys.containsAlias(NativeMigrationExistingView.childAlias(child, "payloads", null)))
                    if(suffix == "camera-results") require(!keys.containsAlias(NativeMigrationExistingView.childAlias(child, "outputs", null)))
                }
                seed.awaitQueuedWrites();r.retire(seed)
                gen
            }
            // Publication retires the old generation's writer; only a freshly
            // opened writer may prepare children in the committed generation.
            val publishedSeed=r.acquire("published-seed-root") {f.store()}
            val seedChildren=withContext(NonCancellable+Dispatchers.IO) {r.cacheGraph(ctx,publishedSeed,prepare = true)}
            val seedCandidate=r.acquire("seed-origin-candidate-cache") {publishedSeed.prepareChild(ctx,"migration-candidates")}
            val candidate = withContext(Dispatchers.IO) {
                NativeMigrationCandidateStore(ctx, publishedSeed).stageFile(UUID.randomUUID().toString(), envelope, "owned postpointer streaming password")
            }
            withContext(NonCancellable+Dispatchers.IO) {
                for(child in seedChildren.values+seedCandidate){child.awaitQueuedWrites();r.retire(child)}
                publishedSeed.awaitQueuedWrites();r.retire(publishedSeed);assertEquals(4,candidateHeader(f,origin))
            }
            // Full original source, destination family, exact origin candidate and ciphertext BEFORE writer admission.
            val old = r.acquire("strict-original-root") {
                f.view(source = true).using { assertEquals(sourceMap, materialize(it.records)); assertEquals(sourceCipher, it.familyCiphertext()) }
                f.view().using { saved ->
                    assertEquals(origin, saved.generation); materialize(saved.records)
                    saved.originalCandidate(origin, candidate) { records -> assertEquals(4, records.version); assertEquals(sourceMap, materialize(records)) }
                    saved.originalCandidateCiphertext(origin); saved.familyCiphertext(); r.own(saved.writer())
                }
            }
            assertTrue(old.needsMigrationGenerationGrant)
            lateinit var original: MobileSession
            var epochBefore = -1L; var epochFrozen = -1L
            r.main {
                withContext(NonCancellable) { original = r.construct("original-Session") {MobileSession(old, ports, ports)} }
                original.requireMigrationStorage(old); epochBefore = original.migrationAuthorityEpoch
                original.restoreForMigration()
                assertNull(original.chatNotifications); assertTrue(original.migrationReady); assertTrue(original.migrationCanImport)
                original.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(old))
                original.voiceSettings.resumePreferences()?.join(); assertTrue(original.voiceSettings.persisted)
            }
            // Real constructor above binds INITIAL. Cache every EXISTING strict path on IO;
            // never grant a missing old node authority or open an uncached strict child on Main.
            val oldPaths=withContext(NonCancellable+Dispatchers.IO) {
                for(path in NativeMigrationExistingPolicy.paths) {
                    val file=File(ctx.noBackupFilesDir,f.namespace+"-"+origin+"-"+path.replace('/','-'))
                    val st=android.system.Os.lstat(file.path)
                    require(android.system.OsConstants.S_ISDIR(st.st_mode)&&file.canonicalFile==file)
                }
                r.cacheGraph(ctx,old,prepare=true)
            }
            r.acquire("strict-origin-candidate-cache") {old.prepareChild(ctx,"migration-candidates")}
            val staleChild=oldPaths.getValue("external-inbox")
            val media=DeniedMedia(); val camera=DeniedCamera()
            r.main {
                original.configureExternalInbox(ctx)
                original.configureDocumentPicker(ctx); original.configurePhotoPicker(ctx)
                original.configureCameraCapture(ctx,camera); original.configureRecentPhotos(ctx,media)
                withTimeout(30_000) {
                    while(!original.migrationReady || original.externalInbox?.migrationIdle!=true ||
                        original.documentPicker?.recovery?.migrationIdle!=true || original.photoPicker?.recovery?.migrationIdle!=true ||
                        original.cameraCapture?.recovery?.migrationIdle!=true || original.recentPhotos?.migrationIdle!=true ||
                        original.photoAuthorization?.migrationIdle!=true)delay(10)
                }
                assertTrue(original.migrationCanImport)
                assertNull(original.documentPicker!!.pending);assertNull(original.photoPicker!!.pending);assertNull(original.cameraCapture!!.pending)
                assertFalse(original.externalInbox!!.available) // Empty producer graph; no deferred URI/OS return.
                r.captureLease { original.quiesceForMigration(f.run) }
                val lease = requireNotNull(r.lease); assertSame(old, lease.storage); assertEquals(f.run, lease.operationId)
                assertTrue(original.ownsMigrationLease(lease)); original.requireMigrationGenerationOwner(lease)
                epochFrozen = original.migrationAuthorityEpoch
            }
            val lease = requireNotNull(r.lease)
            withContext(Dispatchers.IO) {
                lease.requireRawWriters()
                assertSame(original.externalInbox,lease.requireInboxWriters(old.migrationInboxKey()).owner)
                val actualSelections=mapOf(NativeMigrationSelectionJournal.Kind.DOCUMENT to original.documentPicker!!.recovery,
                    NativeMigrationSelectionJournal.Kind.PHOTO to original.photoPicker!!.recovery, NativeMigrationSelectionJournal.Kind.CAMERA to original.cameraCapture!!.recovery)
                for((kind,owner) in actualSelections) {val held=lease.requireSelectionWriters(kind);assertSame(owner,held.owner);assertEquals(old.migrationSelectionKeys(kind),held.keys)}
                for(kind in NativeMigrationPhotoLocalKind.entries)lease.requireLocalPhotoWriters(kind,old.migrationPhotoLocalKey(kind))
                assertEquals(3,lease.selectionWriters.size);assertEquals(2,lease.localPhotoWriters.size)
            }
            val beforePublication = withContext(Dispatchers.IO) {
                f.view().using { saved ->
                    saved.originalCandidate(origin, candidate) { assertEquals(sourceMap, materialize(it)) }
                    oldCipher(saved.ciphertext(), origin) to saved.originalCandidateCiphertext(origin)
                }
            }
            val oldBefore = beforePublication.first; val candidateBefore = beforePublication.second
            withContext(Dispatchers.IO) {
                f.publish("publication-intent", receiptJson(candidate).put("run", f.run).put("pid", pid).put("startTicks", ticks)
                    .put("uid", Process.myUid()).put("package", f.app.packageName).put("anchor", f.anchor())
                    .put("originGeneration", origin).put("operationId", lease.operationId).put("candidateVersion", 4)
                    .put("sourceRecordsSha256", JSONObject(textDigests(sourceMap))).put("sourceCiphertext", JSONObject(sourceCipher))
                    .put("oldGenerationCiphertext", JSONObject(oldBefore)).put("originalCandidateCiphertext", JSONObject(candidateBefore))
                    .put("faultPoint", "AFTER_POINTER").put("activationBudget", 1).put("prepareBudget", 1)
                    .put("originalsRetainedIn", "test-owned authenticated source + exact original candidate; no plaintext proof export"))
            }
            val fault = PostPointerFault(); val points = IntArray(NativeSessionStorage.PublicationPoint.entries.size)
            var observerGeneration: String? = null; var observerRecords = 0; var observerSha: String? = null
            var faults = 0; var activationAttempts = 0
            val observer = NativeSessionStorage.PublicationObserver(candidate) { point, identity ->
                // Trusted bounded synchronous scalars ONLY. No IO, wait, re-entry, storage/lease mutation.
                points[point.ordinal]++; require(identity.receipt == candidate)
                observerGeneration = identity.generation; observerRecords = identity.actualRecords; observerSha = identity.recordsSha256
                if(point == NativeSessionStorage.PublicationPoint.AFTER_POINTER) { faults++; throw fault }
            }
            val noticeLease = withContext(Dispatchers.Main.immediate) { lease.notification(candidate) }
            val failure = withContext(Dispatchers.IO) {
                activationAttempts++
                runCatching { NativeMigrationActivation(ctx, old, noticeLease, lease, observer).activate(candidate) }.exceptionOrNull()
            }
            assertSame(fault, failure); assertEquals(0, fault.suppressed.size)
            assertEquals(1, activationAttempts); assertEquals(1, faults)
            assertEquals(1, points[NativeSessionStorage.PublicationPoint.AFTER_POINTER.ordinal])
            assertEquals(0, points[NativeSessionStorage.PublicationPoint.BEFORE_CONFIRMATION.ordinal])
            assertEquals(0, points[NativeSessionStorage.PublicationPoint.AFTER_CONFIRMATION.ordinal])
            // Independent existing-only pointer audit; NEVER retry Activation to discover generation.
            val generation = withContext(Dispatchers.IO) { f.view().using { it.published(candidate) } }
            assertEquals(observerGeneration, generation); assertNotEquals(origin, generation)
            r.successfulGeneration = generation
            val beforeRestoreRoot = withContext(Dispatchers.IO) { f.view().using { saved ->
                assertEquals(generation, saved.published(candidate)); assertEquals(oldBefore, oldCipher(saved.ciphertext(), origin))
                assertEquals(candidateBefore, saved.originalCandidateCiphertext(origin)); saved.ciphertext()
            } }
            withContext(Dispatchers.IO) { check(runCatching { old.write("metadata", "stale root before release") }.isFailure) }
            var prepareCalls = 0; var order = 0; var releaseOrder = 0; var createOrder = 0
            withContext(NonCancellable + Dispatchers.IO) {
                prepareCalls++; r.releaseAttempted = true
                lease.prepareAndReleaseForRestore(); r.releaseReturned = true; releaseOrder = ++order
            }
            assertEquals(1, prepareCalls); assertTrue(r.releaseReturned)
            val ticket = requireNotNull(lease.strictRestore)
            assertSame(lease, ticket.lease); assertEquals(f.run, ticket.operation); assertEquals(generation, ticket.generation)
            var constructions = 0; var beforeCommits = 0; var boundChecks = 0; var transfers = 0
            lateinit var fresh: MobileSession; lateinit var freshStore: NativeSessionStorage
            r.main {
                check(r.releaseReturned)
                r.restore {
                fresh = restoreNativeMigrationSession(ctx, original, lease, create = { store ->
                    constructions++; createOrder = ++order; freshStore = r.own(store)
                    r.construct("fresh-Session") {MobileSession(store, ports, ports)}.also { fresh = it }
                }, beforeCommit = {
                    beforeCommits++; assertTrue(releaseOrder < createOrder)
                    freshStore.requireBoundMigrationSession(fresh, lease); boundChecks++
                    assertEquals(NativeMigrationExistingPolicy.paths, ticket.prepared)
                    assertFalse(fresh.phoneUnreadKnown); assertNull(fresh.visiblePhoneReading)
                    withContext(NonCancellable+Dispatchers.IO) {
                        val bound=r.cacheGraph(ctx,freshStore,prepare=false)
                        val child = bound.getValue("external-inbox")
                        check(runCatching { child.write("metadata", "BOUND child must not write") }.isFailure)
                        check(runCatching { freshStore.write("metadata", "BOUND root must not write") }.isFailure)
                        assertEquals(beforeRestoreRoot, f.view().using { it.ciphertext() })
                    }
                })
                }
                fresh.awaitMigrationRestore(); assertTrue(fresh.phoneReading.ready); assertFalse(fresh.phoneReading.busy)
                assertNull(fresh.phoneReading.failure); assertNull(fresh.error); assertNull(fresh.chatNotifications)
                original.requireCompletedMigrationTransfer(lease); transfers++
                assertSame(freshStore, lease.restoredStorage); assertNotSame(original, fresh)
                r.markRetired(old); oldPaths.values.forEach {r.markRetired(it)} // Actual production retire returned for this entire old graph.
                assertTrue(fresh.aiSharing.grants.isEmpty())
            }
            assertEquals(1, constructions); assertEquals(1, beforeCommits); assertEquals(1, boundChecks); assertEquals(1, transfers)
            val preparedPaths = withContext(NonCancellable+Dispatchers.IO) {
                r.cacheGraph(ctx,freshStore,prepare=false).mapValues { (path,child) ->
                    val expected = File(ctx.noBackupFilesDir, f.namespace + "-" + generation + "-" + path.replace('/', '-')).canonicalFile
                    assertEquals(expected.path, child.coordinationKey); child.coordinationKey
                }
            }
            withContext(Dispatchers.IO) {
                assertEquals(candidate, freshStore.migrationPublicationReceipt())
                assertEquals(generation, freshStore.publishedMigration(candidate.id, candidate.sha256, candidate.recordCount))
                noReplay(freshStore, sourceMap, ports)
                assertEquals(oldBefore, f.view().using { oldCipher(it.ciphertext(), origin) })
                assertEquals(candidateBefore, f.view().using { it.originalCandidateCiphertext(origin) })
                f.view(source = true).using { assertEquals(sourceMap, materialize(it.records)); assertEquals(sourceCipher, it.familyCiphertext()) }
                check(runCatching { old.write("metadata", "late old root callback") }.isFailure)
                check(runCatching { staleChild.write("metadata", "late old child callback") }.isFailure)
            }
            var newTarget = ""
            r.main {
                newTarget = fresh.targetKey
                val oldTarget = sourceMap.keys.single { it.startsWith("operations:") }.removePrefix("operations:")
                assertNotEquals(oldTarget, newTarget); fresh.text = "independent draft after strict B1"
            }
            withContext(Dispatchers.IO) {
                freshStore.awaitQueuedWrites()
                assertEquals("independent draft after strict B1", freshStore.metadata()!!.getJSONObject("drafts").p7String(newTarget))
                noReplay(freshStore, sourceMap, ports,afterLocalDraftSave=true)
                assertEquals(4, candidateHeader(f, origin)); assertEquals(0, ports.attempts.get())
                assertEquals(0,media.attempts.get()+camera.attempts.get())
                val proof = receiptJson(candidate).put("run", f.run).put("pid", pid).put("startTicks", ticks)
                    .put("uid", Process.myUid()).put("package", f.app.packageName).put("anchor", f.anchor())
                    .put("originGeneration", origin).put("generation", generation).put("candidateVersion", 4)
                    .put("observerActualRecords", observerRecords).put("observerRecordsSha256", observerSha)
                    .put("afterPointer", points[0]).put("beforeConfirmation", points[1]).put("afterConfirmation", points[2])
                    .put("activationAttempts", activationAttempts).put("namedFaultThrows", faults).put("firstPrepareCalls", prepareCalls)
                    .put("freshConstructions", constructions).put("beforeCommitCalls", beforeCommits).put("boundValidationReturns", boundChecks)
                    .put("completedTransferReturns", transfers).put("releaseReturnedBeforeFreshConstruction", releaseOrder < createOrder)
                    .put("oldEpochBefore", epochBefore).put("oldEpochFrozen", epochFrozen)
                    .put("oldSessionReference", System.identityHashCode(original)).put("freshSessionReference", System.identityHashCode(fresh))
                    .put("oldStorageReference", System.identityHashCode(old)).put("freshStorageReference", System.identityHashCode(freshStore))
                    .put("sourceRecordsSha256", JSONObject(textDigests(sourceMap))).put("sourceCiphertext", JSONObject(sourceCipher))
                    .put("oldGenerationCiphertext", JSONObject(oldBefore)).put("originalCandidateCiphertext", JSONObject(candidateBefore))
                    .put("preparedPaths", JSONObject(preparedPaths)).put("newDraftTarget", newTarget)
                    .put("aliases", knownAliases(f, origin, generation)).put("portsAttempts", ports.attempts.get())
                    .put("controllerInstantiated", false).put("notificationCoordinatorInstantiated", false)
                    .put("localSystemPortAttempts",media.attempts.get()+camera.attempts.get()).put("realResolverExternalIsolationVerified",false)
                    .put("actualDataScope", File(ctx.applicationInfo.dataDir).canonicalPath)
                    .put("sourceDerived", "absence of later observer events is publication source order; no recovery.generation counter observed; Controller mapping not exercised")
                namespaceInventory(f, origin, generation); f.treeAnchors(); f.publish("handoff-complete", proof)
            }
        }
    }

    @Test fun distinctPidReadOnlyAuditsExactModernCandidateReceiptAndPostRestoreBaseline() = runBlocking {
        val f = Fixture(); require(f.selectedMethod==COLD_METHOD)
        val (proof, cleanup) = withContext(Dispatchers.IO) { f.admitCold() }
        val originalPid = proof.p7Int("pid"); val originalTicks = proof.p7String("startTicks")
        withContext(Dispatchers.IO) {
            val intent = f.retained("publication-intent")
            assertEquals(receipt(proof), receipt(intent)); assertEquals(proof.p7String("originGeneration"), intent.p7String("originGeneration"))
            assertEquals(originalPid, intent.p7Int("pid")); assertEquals(originalTicks, intent.p7String("startTicks"))
            assertEquals(f.run, intent.p7String("operationId")); assertEquals("AFTER_POINTER", intent.p7String("faultPoint"))
        }
        assertNotEquals(originalPid, Process.myPid())
        withContext(Dispatchers.IO) {
            val expectedOuter=requireNotNull(f.args.getString("postPointerOuterExitSha256"));require(expectedOuter.matches(Regex("[a-f0-9]{64}")))
            val raw=requireNotNull(f.args.getString("postPointerOuterReceipt"));require(raw.toByteArray(Charsets.UTF_8).size in 1..8192)
            val outer=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
            require(outer.keys().asSequence().toSet()==setOf("schemaVersion","binding","originalPid","originalStartTicks","firstMethod",
                "coldMethod","runnerRawSha256","fixtureIntentSha256","handoffSha256","cleanupSha256","sourceAdmissionSha256",
                "originalProcessExitObserved","bodyAndCleanupRunnerSucceeded","verification"))
            require(outer.p7Int("schemaVersion")==2 && raw==p7Canonical(outer) && sha(raw.toByteArray(Charsets.UTF_8))==expectedOuter)
            f.requireBinding(outer.getJSONObject("binding"))
            require(outer.p7Int("originalPid")==originalPid && outer.p7String("originalStartTicks")==originalTicks)
            require(outer.p7String("firstMethod")==FIRST_METHOD && outer.p7String("coldMethod")==COLD_METHOD)
            for(key in listOf("runnerRawSha256","fixtureIntentSha256","handoffSha256","cleanupSha256","sourceAdmissionSha256"))
                require(outer.p7String(key).matches(Regex("[a-f0-9]{64}")))
            require(outer.p7String("sourceAdmissionSha256")==requireNotNull(f.args.getString("postPointerSourceAdmissionSha256")))
            require(outer.p7String("fixtureIntentSha256")==f.retainedSha("fixture-intent") && outer.p7String("handoffSha256")==f.retainedSha("handoff-complete") &&
                outer.p7String("cleanupSha256")==f.retainedSha("case1-cleanup"))
            require(outer.p7Boolean("originalProcessExitObserved") && outer.p7Boolean("bodyAndCleanupRunnerSucceeded") &&
                outer.p7String("verification")=="exact-pid-start-exit-same-boot" && originalExited(originalPid,originalTicks))
        }
        ownedCase(f, cold = true) { r ->
            val candidate = receipt(proof); val origin = proof.p7String("originGeneration"); val generation = proof.p7String("generation")
            r.successfulGeneration = generation
            withContext(Dispatchers.IO) {
                val source = f.view(source = true).using { saved ->
                    val text = materialize(saved.records)
                    assertEquals(strings(proof.getJSONObject("sourceRecordsSha256")), textDigests(text))
                    assertEquals(strings(proof.getJSONObject("sourceCiphertext")), saved.familyCiphertext()); text
                }
                assertEquals(4, candidateHeader(f, origin)); namespaceInventory(f, origin, generation)
                f.view().using { saved ->
                    assertEquals(generation, saved.published(candidate)); assertEquals(generation, saved.generation)
                    var originalCandidateReads = 0
                    saved.originalCandidate(origin, candidate) { records ->
                        originalCandidateReads++; assertEquals(4, records.version); assertEquals(source, materialize(records))
                    }
                    assertEquals(1, originalCandidateReads)
                    val active = materialize(saved.records); graph(active, source,afterLocalDraftSave=true)
                    assertEquals("independent draft after strict B1", JSONObject(active.getValue("metadata")).getJSONObject("drafts").p7String(proof.p7String("newDraftTarget")))
                    assertEquals(strings(proof.getJSONObject("oldGenerationCiphertext")), oldCipher(saved.ciphertext(), origin))
                    assertEquals(strings(proof.getJSONObject("originalCandidateCiphertext")), saved.originalCandidateCiphertext(origin))
                    assertEquals(strings(cleanup.getJSONObject("postRestoreCiphertext")), saved.familyCiphertext())
                    saved.unchanged()
                }
                assertEquals(strings(cleanup.getJSONObject("directoryAnchors")), f.treeAnchors())
                assertEquals(proof.getJSONObject("aliases").toString(), knownAliases(f, origin, generation).toString())
                f.publish("cold-audit-complete", receiptJson(candidate).put("run", f.run).put("pid", Process.myPid())
                    .put("startTicks", startTicks(Process.myPid())).put("originalPid", originalPid).put("originalStartTicks", originalTicks)
                    .put("generation", generation).put("candidateVersion", 4).put("existingOnlyReadOnly", true)
                    .put("newWriterHandles", 0).put("newSessionConstructions", 0).put("newFrozenAcquisitions", 0)
                    .put("activationCalls", 0).put("prepareRestoreCalls", 0).put("controllerInstantiated", false)
                    .put("originalPidExitIndependentlyChecked", true).put("postRestoreCiphertextUnchanged", true))
            }
        }
    }
}
