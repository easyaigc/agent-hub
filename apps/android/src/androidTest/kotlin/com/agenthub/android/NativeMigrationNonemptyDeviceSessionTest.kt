package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.UUID

/** Three closed test-only selectors; pristine Controller and paired initialized/cold scopes stay distinct. */
class NativeMigrationNonemptyDeviceSessionTest {
    private class Fixture {
        private val arguments = InstrumentationRegistry.getArguments()
        val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val run = requireNotNull(arguments.getString("nonemptyRun")).also { require(UUID.fromString(it).toString() == it) }
        private val filesRoot = if(arguments.getString("nonemptyMode") == "readonly-cold")
            File(File(app.applicationInfo.dataDir).canonicalFile,"files") else app.filesDir.canonicalFile
        val folder = File(filesRoot, "P7-nonempty-$run")
        private var expectedAnchor: Map<String, String>? = null
        private fun argument(name: String) = requireNotNull(arguments.getString(name))
        val binding = NativeMigrationNonemptyDeviceCase.Binding(argument("nonemptyAppApkSha256"), argument("nonemptyTestApkSha256"),
            argument("nonemptyCaseSourceSha256"), argument("nonemptyWrapperSourceSha256"), argument("nonemptySourceGuardSha256"),
            argument("nonemptySourceAdmissionSha256"), argument("nonemptyBootUuid"), app.packageName,
            argument("nonemptyUid").toInt().also { require(it.toString() == argument("nonemptyUid")) })
        val mode = argument("nonemptyMode")
        val pristine get() = mode == "pristine-first"
        val selectedMethod get() = when (mode) {
            "pristine-first" -> NativeMigrationNonemptyDeviceCase.PRISTINE_METHOD
            "first" -> NativeMigrationNonemptyDeviceCase.FIRST_METHOD
            else -> NativeMigrationNonemptyDeviceCase.COLD_METHOD
        }
        init {
            val common = setOf("class", "nonemptyMode", "nonemptyRun", "nonemptyAppApkSha256", "nonemptyTestApkSha256",
                "nonemptyCaseSourceSha256", "nonemptyWrapperSourceSha256", "nonemptySourceGuardSha256",
                "nonemptySourceAdmissionSha256", "nonemptyBootUuid", "nonemptyUid")
            val extras = setOf("nonemptyFirstIntentSha256", "nonemptyFirstProofSha256", "nonemptyExitAdmissionSha256", "nonemptyExitAdmissionJson")
            require(mode in setOf("first", "readonly-cold", "pristine-first"))
            require(arguments.keySet() == if(mode == "readonly-cold") common + extras else common)
            require(argument("class") == "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + selectedMethod)
            require(app.packageName == "com.easyai.agenthub.android.acceptance" && app.applicationInfo.uid == Process.myUid())
            require(filesRoot == File(File(app.applicationInfo.dataDir).canonicalFile, "files"))
        }
        private fun <T> withFd(file: File, flags: Int, mode: Int = 0, block: (java.io.FileDescriptor) -> T): T =
            NativeMigrationReadLifetime().use({ Os.open(file.path,flags,mode) },block,{ Os.close(it) })
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
            require(mode in setOf("first", "pristine-first"))
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
            val intent = JSONObject().put("schema", if(pristine) NativeMigrationNonemptyDeviceCase.PRISTINE_SCHEMA else NativeMigrationNonemptyDeviceCase.SCHEMA).put("run", run)
                .put("binding", bindingJson(binding)).put("pid", Process.myPid()).put("startTicks", raw.substringAfterLast(") ").split(' ')[19])
                .put("selectedMethod", selectedMethod).put("anchor", JSONObject(expectedAnchor!!))
            if(pristine) intent.put("scenario", NativeMigrationNonemptyDeviceCase.pristineScenario(
                NativeMigrationNonemptyDeviceCase.Scope(context, run, binding, this::guard)))
            publish("first-intent", intent)
        }
        fun publish(name: String, value: JSONObject) {
            require(name in setOf("first-intent", "first-proof", "cold-intent", "cold-proof")); guard()
            val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..131072)
            withFd(File(folder, "$name.json"), OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) { fd ->
                Os.fchmod(fd,0x180); var offset=0
                while(offset<bytes.size) { val count=Os.write(fd,bytes,offset,bytes.size-offset); require(count>0); offset+=count }
                Os.fsync(fd)
            }
            guard()
        }
        private fun document(name: String): ByteArray {
            require(name in setOf("first-intent", "first-proof")); guard()
            return NativeMigrationReadOnlyFile(folder,File(folder,"$name.json"),131072,minimum=1).read { it.readBytes() }.value
        }
        fun requireNames(coldWritten: Boolean, proofWritten: Boolean = false) {
            val expected = setOf("no_backup", "cache", "files", "first-intent.json", "first-proof.json") +
                (if(coldWritten)setOf("cold-intent.json")else emptySet()) + (if(proofWritten)setOf("cold-proof.json")else emptySet())
            require(requireNotNull(folder.list()).toSet() == expected); guard()
        }
        fun existing(): Triple<NativeMigrationNonemptyDeviceCase.Proof, JSONObject, String> {
            require(mode == "readonly-cold"); binding.requireCurrent()
            expectedAnchor = snapshot(); guard(); requireNames(false)
            val intentBytes = document("first-intent"); val proofBytes = document("first-proof")
            require(NativeMigrationNonemptyPairContract.sha(intentBytes) == argument("nonemptyFirstIntentSha256"))
            require(NativeMigrationNonemptyPairContract.sha(proofBytes) == argument("nonemptyFirstProofSha256"))
            val intent = NativeMigrationNonemptyPairContract.parse(intentBytes)
            NativeMigrationNonemptyPairContract.exact(intent,setOf("schema","run","binding","pid","startTicks","selectedMethod","anchor"))
            val raw = NativeMigrationNonemptyPairContract.parse(proofBytes)
            val first = NativeMigrationNonemptyPairContract.first(raw)
            require(first.run == run) { "Original run mismatch" }
            require(first.binding == binding) { "Original SDK binding mismatch" }
            val priorAnchor = NativeMigrationNonemptyPairContract.anchor(raw.getJSONObject("anchor"))
            val currentAnchor = requireNotNull(expectedAnchor)
            val changedAnchorKeys = (priorAnchor.keys + currentAnchor.keys).filter { priorAnchor[it] != currentAnchor[it] }.sorted()
            require(changedAnchorKeys.isEmpty()) {
                changedAnchorKeys.joinToString(prefix="Original anchor mismatch: ", separator="; ") {
                    "$it: ${priorAnchor[it]} -> ${currentAnchor[it]}"
                }.take(16384)
            }
            for (name in intent.keys()) require(canonical(intent.get(name)) == canonical(raw.get(name)))
            val exitBytes = argument("nonemptyExitAdmissionJson").toByteArray(Charsets.UTF_8); require(exitBytes.size <= 8192)
            val exitSha = NativeMigrationNonemptyPairContract.sha(exitBytes)
            require(exitSha == argument("nonemptyExitAdmissionSha256"))
            val exit = NativeMigrationNonemptyPairContract.parse(exitBytes)
            NativeMigrationNonemptyPairContract.exit(exit,first,argument("nonemptyFirstIntentSha256"),argument("nonemptyFirstProofSha256"))
            NativeMigrationNonemptyPairContract.requireOriginalExited(first)
            return Triple(first,exit,exitSha)
        }
        fun coldDocument(first: NativeMigrationNonemptyDeviceCase.Proof,exitSha: String): JSONObject = JSONObject()
            .put("schema",NativeMigrationNonemptyPairContract.COLD_SCHEMA).put("run",run).put("binding",bindingJson(binding))
            .put("pid",Process.myPid()).put("startTicks",NativeMigrationNonemptyPairContract.startTicks(Process.myPid()))
            .put("selectedMethod",NativeMigrationNonemptyDeviceCase.COLD_METHOD).put("anchor",JSONObject(expectedAnchor!!))
            .put("firstIntentSha256",argument("nonemptyFirstIntentSha256")).put("firstProofSha256",argument("nonemptyFirstProofSha256"))
            .put("exitAdmissionSha256",exitSha).put("firstPid",first.pid).put("firstStartTicks",first.startTicks)
        fun proof(value: NativeMigrationNonemptyDeviceCase.Proof): JSONObject = JSONObject()
            .put("schema", value.schema).put("run", value.run).put("binding", bindingJson(value.binding))
            .put("pid", value.pid).put("startTicks", value.startTicks).put("selectedMethod", selectedMethod)
            .put("receipt", JSONObject().put("candidateId", value.receipt.id).put("sha256", value.receipt.sha256).put("records", value.receipt.recordCount))
            .put("generation", value.generation).put("archiveSha256", value.archiveSha256)
            .put("sourceCiphertext", JSONObject(value.sourceCiphertext)).put("originalCandidateCiphertext", JSONObject(value.originalCandidateCiphertext))
            .put("destinationCiphertext", JSONObject(value.destinationCiphertext)).put("originalTextSha256", JSONObject(value.originalTextSha256))
            .put("originalBinarySha256", JSONObject(value.originalBinarySha256)).put("draftKey", value.draftKey)
            .put("draftTextSha256", value.draftTextSha256).put("sourceTextSha256", JSONObject(value.sourceTextSha256))
            .put("sourceBinarySha256", JSONObject(value.sourceBinarySha256)).put("activeTextSha256", JSONObject(value.activeTextSha256))
            .put("activeBinarySha256", JSONObject(value.activeBinarySha256))
            .put("acquiredStores", value.acquiredStores).put("acquiredSessions", value.acquiredSessions)
            .put("actualSessionJoinReturns", value.actualSessionJoinReturns).put("actualWriterRetireReturns", value.actualWriterRetireReturns)
            .put("actualLeaseReleaseReturns", value.actualLeaseReleaseReturns).put("unknownAcquisitions", value.unknownAcquisitions)
            .put("forbiddenPortAttempts", value.forbiddenPortAttempts).put("anchor", JSONObject(expectedAnchor!!))
    }
    @Test fun nonemptyDeviceProducersRestoreActualSession() = runBlocking {
        val fixture = withContext(Dispatchers.IO) { Fixture().also { it.fresh() } }
        withContext(Dispatchers.IO) { delay(NativeMigrationNonemptyPairContract.WINDOW_MILLIS) }
        val scope = withContext(Dispatchers.IO) { NativeMigrationNonemptyDeviceCase.Scope(fixture.context, fixture.run, fixture.binding, fixture::guard) }
        val proof = NativeMigrationNonemptyDeviceCase.first(scope)
        withContext(Dispatchers.IO) { fixture.publish("first-proof", fixture.proof(proof)) }
    }
    @Test fun pristineNonemptyControllerImportsWithoutPrecreatedRootKey() = runBlocking {
        val fixture = withContext(Dispatchers.IO) { Fixture().also { require(it.pristine); it.fresh() } }
        withContext(Dispatchers.IO) { delay(NativeMigrationNonemptyPairContract.WINDOW_MILLIS) }
        val scope = withContext(Dispatchers.IO) { NativeMigrationNonemptyDeviceCase.Scope(fixture.context, fixture.run, fixture.binding, fixture::guard) }
        val value = NativeMigrationNonemptyDeviceCase.pristine(scope)
        withContext(Dispatchers.IO) {
            val proof = fixture.proof(value.completed).put("scenario", NativeMigrationNonemptyDeviceCase.pristineScenario(scope))
                .put("keyWindows", org.json.JSONArray(value.keyWindows.map { window -> JSONObject()
                    .put("point", window.point).put("rootAliasPresent", window.rootAliasPresent).put("rootCiphertextCount", window.rootCiphertextCount)
                    .put("destinationCiphertext", JSONObject(window.destinationCiphertext)) }))
                .put("restorePoints", org.json.JSONArray(value.restorePoints))
                .put("controllerQuiesceReturns", value.controllerQuiesceReturns).put("controllerRestoreReturns", value.controllerRestoreReturns)
                .put("newSessionPublished", value.newSessionPublished).put("freshReady", value.freshReady).put("controllerReady", value.controllerReady)
                .put("explicitEnrollmentRequired", value.explicitEnrollmentRequired).put("phase", value.phase).put("result", value.result)
                .put("preSessionResetObserved", false).put("directKeyGeneratorObserved", false)
                .put("controllerWorkerJoinClaimed", false).put("physicalDescriptorExitClaimed", false)
            fixture.publish("first-proof", proof)
            fixture.requireNames(false)
        }
    }
    @Test fun nonemptyReadOnlyColdAfterExactOriginalExit() = runBlocking {
        withContext(NonCancellable + Dispatchers.IO) {
            val fixture = Fixture(); val (first, _, exitSha) = fixture.existing()
            val scope = NativeMigrationNonemptyDeviceCase.Scope(fixture.context,fixture.run,fixture.binding,fixture::guard,existingOnly=true)
            val before = NativeMigrationNonemptyPairContract.protected(fixture.folder)
            val aliases = NativeMigrationNonemptyPairContract.aliasPins(scope,first.generation)
            val intent = fixture.coldDocument(first,exitSha)
            fixture.publish("cold-intent",intent); fixture.requireNames(true)
            delay(NativeMigrationNonemptyPairContract.WINDOW_MILLIS) // Bounded independent host-observation window, no ack/file exemption.
            NativeMigrationNonemptyPairContract.requireOriginalExited(first)
            val pins = NativeMigrationNonemptyDeviceCase.cold(scope,first) // Includes all successful reader closes.
            require(before == NativeMigrationNonemptyPairContract.protected(fixture.folder))
            require(aliases == NativeMigrationNonemptyPairContract.aliasPins(scope,first.generation))
            fixture.binding.requireCurrent(); NativeMigrationNonemptyPairContract.requireOriginalExited(first)
            val proof = fixture.coldDocument(first,exitSha)
                .put("clearFramingSha256",pins.clearSha256).put("sealedArchiveSha256",pins.sealedSha256).put("generation",pins.generation)
                .put("draftKey",pins.draftKey).put("draftTextSha256",pins.draftTextSha256)
                .put("activeTextSha256",JSONObject(pins.activeTextSha256)).put("activeBinarySha256",JSONObject(pins.activeBinarySha256))
                .put("protectedDataSha256",NativeMigrationNonemptyPairContract.sha(canonical(JSONObject(before)).toByteArray(Charsets.UTF_8)))
                .put("fixtureAliasesSha256",NativeMigrationNonemptyPairContract.sha(canonical(JSONObject(aliases)).toByteArray(Charsets.UTF_8)))
                .put("readerCloseReturns",pins.readerCloseReturns).put("protectedDataUnchanged",true).put("fixtureAliasesUnchanged",true)
            for(name in intent.keys()) require(canonical(intent.get(name)) == canonical(proof.get(name)))
            fixture.publish("cold-proof",proof); fixture.requireNames(true,true)
            require(before == NativeMigrationNonemptyPairContract.protected(fixture.folder))
        }
    }
    private companion object {
        fun canonical(value: Any): String = when(value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
            is org.json.JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Boolean, is Int, is Long -> value.toString()
            JSONObject.NULL -> "null"
            else -> error("Invalid paired scalar")
        }

        fun bindingJson(value: NativeMigrationNonemptyDeviceCase.Binding) = JSONObject()
            .put("appApkSha256", value.appApkSha256).put("testApkSha256", value.testApkSha256)
            .put("caseSourceSha256", value.caseSourceSha256).put("wrapperSourceSha256", value.wrapperSourceSha256)
            .put("sourceGuardSha256", value.sourceGuardSha256).put("sourceAdmissionSha256", value.sourceAdmissionSha256)
            .put("bootUuid", value.bootUuid).put("package", value.packageName).put("uid", value.uid)
    }
}
