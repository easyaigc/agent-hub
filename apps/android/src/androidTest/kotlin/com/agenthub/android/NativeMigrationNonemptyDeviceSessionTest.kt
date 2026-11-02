package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.UUID

/** First-case native entry only. Cold is intentionally not exposed by this wrapper. */
class NativeMigrationNonemptyDeviceSessionTest {
    private class Fixture {
        private val arguments = InstrumentationRegistry.getArguments()
        val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val run = requireNotNull(arguments.getString("nonemptyRun")).also { require(UUID.fromString(it).toString() == it) }
        private val filesRoot = app.filesDir.canonicalFile
        val folder = File(filesRoot, "P7-nonempty-$run")
        private var expectedAnchor: Map<String, String>? = null
        private fun argument(name: String) = requireNotNull(arguments.getString(name))
        val binding = NativeMigrationNonemptyDeviceCase.Binding(argument("nonemptyAppApkSha256"), argument("nonemptyTestApkSha256"),
            argument("nonemptyCaseSourceSha256"), argument("nonemptyWrapperSourceSha256"), argument("nonemptySourceGuardSha256"),
            argument("nonemptySourceAdmissionSha256"), argument("nonemptyBootUuid"), app.packageName,
            argument("nonemptyUid").toInt().also { require(it.toString() == argument("nonemptyUid")) })
        init {
            val required = setOf("class", "nonemptyMode", "nonemptyRun", "nonemptyAppApkSha256", "nonemptyTestApkSha256",
                "nonemptyCaseSourceSha256", "nonemptyWrapperSourceSha256", "nonemptySourceGuardSha256",
                "nonemptySourceAdmissionSha256", "nonemptyBootUuid", "nonemptyUid")
            require(arguments.keySet() == required)
            require(argument("nonemptyMode") == "first")
            require(argument("class") == "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + NativeMigrationNonemptyDeviceCase.FIRST_METHOD)
            require(app.packageName == "com.easyai.agenthub.android.acceptance" && app.applicationInfo.uid == Process.myUid())
            require(filesRoot == File(File(app.applicationInfo.dataDir).canonicalFile, "files"))
        }
        private inline fun <T> withFd(file: File, flags: Int, mode: Int = 0, block: (java.io.FileDescriptor) -> T): T {
            val fd = Os.open(file.path, flags, mode); var primary: Throwable? = null
            try { return block(fd) } catch (error: Throwable) { primary = error; throw error }
            finally { try { Os.close(fd) } catch (close: Throwable) {
                val original = primary
                if (original == null) throw close else if (original !== close) original.addSuppressed(close)
            } }
        }
        private fun identity(stat: android.system.StructStat) = listOf(stat.st_dev, stat.st_ino, stat.st_uid.toLong(), stat.st_mode.toLong()).joinToString(":")
        private fun directory(file: File, owned: Boolean, privateScope: Boolean): String {
            val before = Os.lstat(file.path)
            require(OsConstants.S_ISDIR(before.st_mode) && file.canonicalFile == file)
            if (owned) {
                require(before.st_uid == Process.myUid())
                val mode = before.st_mode and 0x1ff
                require(if (privateScope) mode == 0x1c0 else mode in setOf(0x1c0, 0x1f9))
                withFd(file, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW) { fd ->
                    require(identity(Os.fstat(fd)) == identity(before))
                }
            }
            require(identity(Os.lstat(file.path)) == identity(before)); return identity(before)
        }
        private fun snapshot(): Map<String, String> = linkedMapOf<String, String>().apply {
            var current = File("/"); put("ancestor:/", directory(current, false, false))
            for (part in filesRoot.path.split('/').filter { it.isNotEmpty() }) {
                require(part != "." && part != ".."); current = File(current, part)
                put("ancestor:${current.path}", directory(current, current == filesRoot, false))
            }
            require(current == filesRoot && filesRoot.canonicalFile == filesRoot)
            val mounts = File("/proc/self/mountinfo").readText(); require(mounts.length <= 1024 * 1024)
            for (line in mounts.lineSequence().filter { it.isNotBlank() }) {
                val fields = line.split(' '); require(fields.size >= 10)
                val point = fields[4].replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")
                if (point == "/" || filesRoot.path == point || filesRoot.path.startsWith("$point/") || point.startsWith("${folder.path}/"))
                    put("observed-mount:$point", line)
            }
            put("package", app.packageName); put("uid", Process.myUid().toString()); put("run", run); put("filesRoot", filesRoot.path)
            put("dataScopeIdentity", directory(File(app.applicationInfo.dataDir).canonicalFile, true, false))
            require(folder.canonicalFile == File(filesRoot, "P7-nonempty-$run"))
            put("runDirectory", directory(folder, true, true))
            for (name in listOf("no_backup", "cache", "files")) put(name, directory(File(folder, name), true, true))
        }
        fun guard() { require(snapshot() == requireNotNull(expectedAnchor)) { "Owned nonempty fixture anchor changed" } }
        val context: Context get() { guard(); return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File { guard(); return File(folder, "no_backup") }
            override fun getCacheDir(): File { guard(); return File(folder, "cache") }
            override fun getFilesDir(): File { guard(); return File(folder, "files") }
            override fun getSystemService(name: String): Any = error("Fixture forbids system service: $name")
            override fun getContentResolver(): android.content.ContentResolver = error("Fixture forbids ContentResolver")
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = error("Fixture forbids SharedPreferences")
            override fun startService(service: android.content.Intent): android.content.ComponentName = error("Fixture forbids service")
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName = error("Fixture forbids foreground service")
        } }
        fun fresh() {
            binding.requireCurrent()
            require(java.nio.file.Files.notExists(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "p7-nonempty-$run"
            for (base in listOf(alias, "$alias-source")) {
                require(!keys.containsAlias(base))
                val children = linkedMapOf<String, String>()
                for (path in (NativeMigrationExistingPolicy.paths + "migration-candidates").sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })) {
                    val parent = path.substringBeforeLast('/', ""); val parentAlias = if (parent.isEmpty()) base else children.getValue(parent)
                    val child = NativeMigrationExistingView.childAlias(parentAlias, path.substringAfterLast('/'), null)
                    require(!keys.containsAlias(child)); children[path] = child
                }
            }
            check(folder.mkdir()); Os.chmod(folder.path, 0x1c0)
            for (name in listOf("no_backup", "cache", "files")) { val child = File(folder, name); check(child.mkdir()); Os.chmod(child.path, 0x1c0) }
            expectedAnchor = snapshot(); guard()
            val raw = File("/proc/${Process.myPid()}/stat").readText()
            publish("first-intent", JSONObject().put("schema", NativeMigrationNonemptyDeviceCase.SCHEMA).put("run", run)
                .put("binding", bindingJson(binding)).put("pid", Process.myPid()).put("startTicks", raw.substringAfterLast(") ").split(' ')[19])
                .put("selectedMethod", NativeMigrationNonemptyDeviceCase.FIRST_METHOD).put("anchor", JSONObject(expectedAnchor!!)))
        }
        fun publish(name: String, value: JSONObject) {
            require(name in setOf("first-intent", "first-proof")); guard()
            val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..131072)
            withFd(File(folder, "$name.json"), OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) { fd ->
                java.io.FileOutputStream(fd).use { output -> Os.fchmod(fd, 0x180); output.write(bytes); output.fd.sync() }
            }
            guard()
        }
        fun proof(value: NativeMigrationNonemptyDeviceCase.Proof): JSONObject = JSONObject()
            .put("schema", value.schema).put("run", value.run).put("binding", bindingJson(value.binding))
            .put("pid", value.pid).put("startTicks", value.startTicks).put("selectedMethod", NativeMigrationNonemptyDeviceCase.FIRST_METHOD)
            .put("receipt", JSONObject().put("candidateId", value.receipt.id).put("sha256", value.receipt.sha256).put("records", value.receipt.recordCount))
            .put("generation", value.generation).put("archiveSha256", value.archiveSha256)
            .put("sourceCiphertext", JSONObject(value.sourceCiphertext)).put("originalCandidateCiphertext", JSONObject(value.originalCandidateCiphertext))
            .put("destinationCiphertext", JSONObject(value.destinationCiphertext)).put("originalTextSha256", JSONObject(value.originalTextSha256))
            .put("originalBinarySha256", JSONObject(value.originalBinarySha256)).put("draftKey", value.draftKey)
            .put("acquiredStores", value.acquiredStores).put("acquiredSessions", value.acquiredSessions)
            .put("actualSessionJoinReturns", value.actualSessionJoinReturns).put("actualWriterRetireReturns", value.actualWriterRetireReturns)
            .put("actualLeaseReleaseReturns", value.actualLeaseReleaseReturns).put("unknownAcquisitions", value.unknownAcquisitions)
            .put("forbiddenPortAttempts", value.forbiddenPortAttempts).put("anchor", JSONObject(expectedAnchor!!))
    }
    @Test fun nonemptyDeviceProducersRestoreActualSession() = runBlocking {
        val fixture = Fixture(); fixture.fresh()
        val scope = NativeMigrationNonemptyDeviceCase.Scope(fixture.context, fixture.run, fixture.binding, fixture::guard)
        val proof = NativeMigrationNonemptyDeviceCase.first(scope)
        fixture.publish("first-proof", fixture.proof(proof))
    }
    private companion object {
        fun bindingJson(value: NativeMigrationNonemptyDeviceCase.Binding) = JSONObject()
            .put("appApkSha256", value.appApkSha256).put("testApkSha256", value.testApkSha256)
            .put("caseSourceSha256", value.caseSourceSha256).put("wrapperSourceSha256", value.wrapperSourceSha256)
            .put("sourceGuardSha256", value.sourceGuardSha256).put("sourceAdmissionSha256", value.sourceAdmissionSha256)
            .put("bootUuid", value.bootUuid).put("package", value.packageName).put("uid", value.uid)
    }
}
