package com.agenthub.android

import android.os.Process
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/** Test-only closed paired contract. No seed, ordinary storage, writer, Session or command port. */
internal object NativeMigrationNonemptyPairContract {
    const val EXIT_SCHEMA = "p7-nonempty-original-exit-v1"
    const val COLD_SCHEMA = "p7-nonempty-readonly-cold-v1"
    const val FOREGROUND_COLD_SCHEMA = "p7-nonempty-foreground-readonly-cold-v1"
    const val WINDOW_MILLIS = 15_000L
    private const val CAP = 1024 * 1024
    private val firstKeys = setOf("schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor",
        "receipt", "generation", "archiveSha256", "sourceCiphertext", "originalCandidateCiphertext", "destinationCiphertext",
        "originalTextSha256", "originalBinarySha256", "draftKey", "draftTextSha256", "sourceTextSha256", "sourceBinarySha256",
        "activeTextSha256", "activeBinarySha256", "acquiredStores", "acquiredSessions", "actualSessionJoinReturns",
        "actualWriterRetireReturns", "actualLeaseReleaseReturns", "unknownAcquisitions", "forbiddenPortAttempts")
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val textFixed = setOf("metadata", "codex-private-connections-v1", NativeMigrationDeviceState.READING,
        NativeMigrationDeviceState.NOTIFICATIONS, NativeMigrationSounds.RECORD)
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun parse(bytes: ByteArray): JSONObject { require(bytes.size in 1..131072); return StrictMigrationJson.parse(bytes) }
    fun exact(value: JSONObject, keys: Set<String>) { require(value.keys().asSequence().toSet() == keys) }
    fun string(value: JSONObject, key: String, maximum: Int = 512): String = (value.get(key) as String).also { require(it.length in 1..maximum && it.none { c -> c.code < 32 }) }
    fun hash(value: JSONObject, key: String): String = string(value, key, 64).also { require(it.matches(shaPattern)) }
    fun uuid(value: JSONObject, key: String): String = string(value, key, 36).also { require(UUID.fromString(it).toString() == it) }
    fun number(value: JSONObject, key: String, minimum: Int = 0, maximum: Int = Int.MAX_VALUE): Int = (value.get(key) as Int).also { require(it in minimum..maximum) }
    fun ticks(value: JSONObject, key: String): String = string(value, key, 24).also { require(it.matches(Regex("[0-9]{1,24}"))) }
    fun maps(value: JSONObject, key: String, maximum: Int, empty: Boolean = false): Map<String, String> {
        val body = value.get(key) as JSONObject; require(body.length() in (if (empty) 0 else 1)..maximum)
        return body.keys().asSequence().associateWith { name ->
            require(name.length in 1..512 && name.none { it.code < 32 }); hash(body, name)
        }
    }
    fun binding(value: JSONObject): NativeMigrationNonemptyDeviceCase.Binding {
        exact(value, setOf("appApkSha256", "testApkSha256", "caseSourceSha256", "wrapperSourceSha256", "sourceGuardSha256",
            "sourceAdmissionSha256", "bootUuid", "package", "uid"))
        return NativeMigrationNonemptyDeviceCase.Binding(hash(value,"appApkSha256"), hash(value,"testApkSha256"),
            hash(value,"caseSourceSha256"), hash(value,"wrapperSourceSha256"), hash(value,"sourceGuardSha256"),
            hash(value,"sourceAdmissionSha256"), uuid(value,"bootUuid"), string(value,"package"), number(value,"uid",1))
    }
    fun anchor(value: JSONObject): Map<String, String> {
        require(value.length() in 12..128)
        val fixed = setOf("package", "uid", "run", "filesRoot", "dataScopeIdentity", "runDirectory", "no_backup", "cache", "files")
        val keys = value.keys().asSequence().toSet()
        require(fixed.all { it in keys } && "ancestor:/" in keys && keys.all { it in fixed || it.startsWith("ancestor:/") || it.startsWith("observed-mount:/") })
        return keys.associateWith { string(value,it,16384) }
    }
    fun requireNineNames(text: Set<String>, binary: Set<String>) {
        require(text.size == 8 && binary.size == 1 && textFixed.all { it in text })
        val dynamic = text - textFixed
        require(dynamic.size == 3 && listOf("operations:", "submission:", "draft-file:").all { prefix -> dynamic.count { it.startsWith(prefix) } == 1 })
        val name = binary.single(); require(name.startsWith("native-chat-notification-sound-v1:") && name.endsWith(".caf"))
        val id = name.removePrefix("native-chat-notification-sound-v1:").removeSuffix(".caf")
        require(UUID.fromString(id).toString() == id)
    }
    fun requireNine(records: NativeMigrationStreamRecords) { require(records.version == 4 && records.size == 9); requireNineNames(records.text.keys, records.binary.keys) }
    fun requireForegroundNames(text: Set<String>, binary: Set<String>) {
        val archiveNames = text.filter { it == NativeMigrationNotificationArchives.RECORD || it.startsWith(NativeMigrationNotificationArchives.PREFIX) }.toSet()
        require(text.size == 12 && archiveNames.size == 4 && NativeMigrationNotificationArchives.RECORD in archiveNames)
        val originals = archiveNames - NativeMigrationNotificationArchives.RECORD
        val suffixes = setOf("foreground-v1", "recovery-v2", "authorizations-v1")
        require(originals.map { it.substringAfterLast(':') }.toSet() == suffixes)
        val ids = originals.map { name ->
            val body = name.removePrefix(NativeMigrationNotificationArchives.PREFIX)
            require(body.substringBefore(':').matches(shaPattern) && body.count { it == ':' } == 1)
            body.substringBefore(':')
        }
        require(ids.distinct().size == 1 && NativeMigrationForeground.ACTIVE_NAMES.none { it in text })
        requireNineNames(text - archiveNames, binary)
    }
    fun requireForeground(records: NativeMigrationStreamRecords) {
        require(records.version == 4 && records.size == 13); requireForegroundNames(records.text.keys, records.binary.keys)
    }
    fun first(value: JSONObject): NativeMigrationNonemptyDeviceCase.Proof = parseFirst(value, false)
    fun foregroundFirst(value: JSONObject): NativeMigrationNonemptyDeviceCase.Proof = parseFirst(value, true)
    fun childOriginalsFirst(value: JSONObject): NativeMigrationNonemptyDeviceCase.Proof = parseFirst(value, false, childOriginals = true)
    private fun parseFirst(value: JSONObject, foreground: Boolean, childOriginals: Boolean = false): NativeMigrationNonemptyDeviceCase.Proof {
        require(!foreground || !childOriginals)
        val controllerKeys = setOf("scenario", "keyWindows", "restorePoints", "controllerQuiesceReturns", "controllerRestoreReturns",
            "newSessionPublished", "freshReady", "controllerReady", "explicitEnrollmentRequired", "phase", "result",
            "preSessionResetObserved", "directKeyGeneratorObserved", "controllerWorkerJoinClaimed", "physicalDescriptorExitClaimed")
        exact(value, if (foreground || childOriginals) firstKeys + controllerKeys else firstKeys)
        require(string(value,"schema") == if (childOriginals) NativeMigrationChildOriginalsProfile.SCHEMA else if (foreground) NativeMigrationNonemptyDeviceCase.FOREGROUND_SCHEMA else NativeMigrationNonemptyDeviceCase.SCHEMA)
        require(string(value,"selectedMethod") == if (childOriginals) NativeMigrationChildOriginalsProfile.METHOD else if (foreground) NativeMigrationNonemptyDeviceCase.FOREGROUND_METHOD else NativeMigrationNonemptyDeviceCase.FIRST_METHOD)
        if (foreground || childOriginals) {
            val scenario = value.getJSONObject("scenario")
            exact(scenario, setOf("entryPath", "rootAlias", "sourceAlias", "rootNamespace", "sourceNamespace", "safPickerCovered", "uiCovered", "coldProcessCovered") +
                if (childOriginals) setOf("producerProfile", "inputBoundary", "providerReadCovered", "osGrantCovered", "cameraOutputsCovered", "producerNodes", "portableTextRecords", "portableBinaryRecords") else emptySet())
            if (childOriginals) {
                require(string(scenario, "producerProfile") == NativeMigrationChildOriginalsProfile.SCHEMA &&
                    string(scenario, "inputBoundary") == "synthetic-bytes-and-opaque-content-uris")
                for (name in listOf("providerReadCovered", "osGrantCovered", "cameraOutputsCovered")) require(scenario.get(name) == false)
                require(scenario.getJSONArray("producerNodes").strings() == NativeMigrationChildOriginalsProfile.paths)
                require(number(scenario, "portableTextRecords") == 26 && number(scenario, "portableBinaryRecords") == 7)
            }
            require(string(scenario,"entryPath") == "already-staged-production-candidate-controller" && string(scenario,"rootNamespace") == "installation" && string(scenario,"sourceNamespace") == "source")
            require(string(scenario,"rootAlias") == "p7-nonempty-${uuid(value,"run")}" && string(scenario,"sourceAlias") == string(scenario,"rootAlias") + "-source")
            for (name in listOf("safPickerCovered", "uiCovered", "coldProcessCovered")) require(scenario.get(name) == false)
            require(number(value,"controllerQuiesceReturns") == 1 && number(value,"controllerRestoreReturns") == 1)
            for (name in listOf("newSessionPublished", "freshReady", "controllerReady")) require(value.get(name) == true)
            require(value.get("explicitEnrollmentRequired") == foreground)
            for (name in listOf("preSessionResetObserved", "directKeyGeneratorObserved", "controllerWorkerJoinClaimed", "physicalDescriptorExitClaimed")) require(value.get(name) == false)
            require(string(value,"phase") == "DONE" && string(value,"result") == "IMPORTED")
            require(value.getJSONArray("restorePoints").strings() == NativeMigrationControllerRestorePoint.entries.map { it.name })
            val windows = value.getJSONArray("keyWindows")
            val points = listOf("BEFORE_CONSTRUCTION", "AFTER_EMPTY_SESSION_RESTORE", "AFTER_CANDIDATE_STAGE", "AFTER_CONTROLLER_PREVIEW", "BEFORE_CONFIRM_IMPORT", "AFTER_CONTROLLER_DONE")
            require(windows.length() == points.size)
            for ((index, point) in points.withIndex()) {
                val window = windows.getJSONObject(index); exact(window, setOf("point", "rootAliasPresent", "rootCiphertextCount", "destinationCiphertext"))
                require(string(window,"point") == point && window.get("rootAliasPresent") == (index == points.lastIndex))
                require(number(window,"rootCiphertextCount") == 0 || index == points.lastIndex)
                if (index == points.lastIndex) require(number(window,"rootCiphertextCount") > 0)
                maps(window,"destinationCiphertext",256,empty = index < 2)
            }
        }
        anchor(value.getJSONObject("anchor")); val receipt = value.getJSONObject("receipt")
        exact(receipt,setOf("candidateId", "sha256", "records")); val recordCount = if (childOriginals) 33 else if (foreground) 13 else 9; require(number(receipt,"records") == recordCount)
        val text = maps(value,"originalTextSha256",if (childOriginals) 26 else if (foreground) 12 else 8)
        val binary = maps(value,"originalBinarySha256",if (childOriginals) 7 else 1)
        if (childOriginals) NativeMigrationChildOriginalsProfile.requireNames(text.keys,binary.keys)
        else if (foreground) requireForegroundNames(text.keys,binary.keys) else requireNineNames(text.keys,binary.keys)
        val stores = if (childOriginals) 32 else 27
        for ((name,count) in mapOf("acquiredStores" to stores, "acquiredSessions" to 4, "actualSessionJoinReturns" to 4,
            "actualWriterRetireReturns" to stores - 2, "actualLeaseReleaseReturns" to 2, "unknownAcquisitions" to 0, "forbiddenPortAttempts" to 0))
            require(number(value,name) == count)
        val draft = string(value,"draftKey"); require(draft.startsWith("codex-machine-v2:"))
        return NativeMigrationNonemptyDeviceCase.Proof(string(value,"schema"),uuid(value,"run"),binding(value.getJSONObject("binding")),
            number(value,"pid",1),ticks(value,"startTicks"), NativeMigrationCandidateStore.Receipt(uuid(receipt,"candidateId"),hash(receipt,"sha256"),recordCount),
            uuid(value,"generation"),hash(value,"archiveSha256"), maps(value,"sourceCiphertext",64),maps(value,"originalCandidateCiphertext",64),
            maps(value,"destinationCiphertext",128),text,binary,draft,hash(value,"draftTextSha256"),maps(value,"sourceTextSha256",32),
            maps(value,"sourceBinarySha256",1),maps(value,"activeTextSha256",32),maps(value,"activeBinarySha256",if (childOriginals) 7 else 1),stores,4,4,stores - 2,2,0,0)
    }
    /** Worker wait + raw/JUnit + four completed joins are host facts, never inferred from this App. */
    fun exit(value: JSONObject, first: NativeMigrationNonemptyDeviceCase.Proof, intentSha: String, proofSha: String) {
        exact(value,setOf("schema", "run", "binding", "firstIntentSha256", "firstProofSha256", "firstPid", "firstStartTicks",
            "firstWorkerReceiptSha256", "firstRawSha256", "firstJUnitSha256", "sourceGuardSha256", "sourceAdmissionSha256",
            "adbSha256", "deviceSerialSha256", "sdkVersionSha256", "exitKind", "exitObservationSha256"))
        require(string(value,"schema") == EXIT_SCHEMA && uuid(value,"run") == first.run && binding(value.getJSONObject("binding")) == first.binding)
        require(hash(value,"firstIntentSha256") == intentSha && hash(value,"firstProofSha256") == proofSha)
        require(number(value,"firstPid",1) == first.pid && ticks(value,"firstStartTicks") == first.startTicks)
        require(hash(value,"sourceGuardSha256") == first.binding.sourceGuardSha256 && hash(value,"sourceAdmissionSha256") == first.binding.sourceAdmissionSha256)
        for (name in listOf("firstWorkerReceiptSha256", "firstRawSha256", "firstJUnitSha256", "adbSha256", "deviceSerialSha256", "sdkVersionSha256", "exitObservationSha256")) hash(value,name)
        require(string(value,"exitKind") in setOf("proc-enoent-and-directory-absent", "pid-start-replaced"))
    }
    fun startTicks(pid: Int): String {
        val raw = File("/proc/$pid/stat").readText(); require(raw.length in 1..8192 && raw.startsWith("$pid ("))
        return raw.substring(raw.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))[19].also { require(it.matches(Regex("[0-9]{1,24}"))) }
    }
    /** ENOENT is checked through two independent paths. EACCES/partial reads never mean exit. */
    fun requireOriginalExited(first: NativeMigrationNonemptyDeviceCase.Proof) {
        require(Process.myPid() != first.pid && File("/proc/sys/kernel/random/boot_id").readText().trim() == first.binding.bootUuid)
        try {
            val raw = NativeMigrationReadLifetime().use(
                { Os.open("/proc/${first.pid}/stat",OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0) }, { fd ->
                    val bytes = ByteArray(8193); val count = Os.read(fd,bytes,0,bytes.size); require(count in 1..8192)
                    String(bytes,0,count,Charsets.UTF_8)
                }, { Os.close(it) })
            require(raw.startsWith("${first.pid} (") && raw.lastIndexOf(')') > 0)
            val ticks = raw.substring(raw.lastIndexOf(')')+2).trim().split(Regex("\\s+"))[19]
            require(ticks.matches(Regex("[0-9]{1,24}")) && ticks != first.startTicks) { "Original App tuple is still live" }
        } catch (failure: android.system.ErrnoException) {
            require(failure.errno == OsConstants.ENOENT)
            try { Os.lstat("/proc/${first.pid}"); error("Original process directory still exists") }
            catch (absent: android.system.ErrnoException) { require(absent.errno == OsConstants.ENOENT) }
        }
        require(File("/proc/sys/kernel/random/boot_id").readText().trim() == first.binding.bootUuid)
    }
    data class ColdPins(val clearSha256: String,val sealedSha256: String,val generation: String,val draftKey: String,
        val draftTextSha256: String,val activeTextSha256: Map<String,String>,val activeBinarySha256: Map<String,String>,val readerCloseReturns: Int=0)
    class Archive(val text: Map<String,String>,val clearSha256: String,val sealedSha256: String)
    /** Sequential AEAD/framing consumption stays bounded in memory; no clear archive is written. */
    fun readArchive(scope: NativeMigrationNonemptyDeviceCase.Scope,password: String,first: NativeMigrationNonemptyDeviceCase.Proof): Archive {
        val lifetime = NativeMigrationReadLifetime()
        var failure: Throwable? = null
        try {
            val ref = NativeMigrationReadOnlyFile(scope.context.filesDir,scope.archive,CAP,lifetime,40)
            val read = ref.read { encrypted -> NativeMigrationStreamCrypto.read(encrypted,password) { clear ->
                val digest = MessageDigest.getInstance("SHA-256"); val input = DigestInputStream(clear,digest)
                val records = NativeMigrationStreamArchive.readHeader(input,scope.context.packageName)
                if (first.schema == NativeMigrationChildOriginalsProfile.SCHEMA) NativeMigrationChildOriginalsProfile.requireNames(records.text.keys, records.binary.keys)
                else if (first.schema == NativeMigrationNonemptyDeviceCase.FOREGROUND_SCHEMA) requireForeground(records) else requireNine(records)
                require(records.frames().values.sumOf { it.size.toLong() } <= CAP)
                val text = linkedMapOf<String,String>(); val binaries = linkedMapOf<String,ByteArray>()
                try {
                    for ((name,blob) in records.frames()) {
                        val out = ByteArrayOutputStream(blob.size)
                        NativeMigrationStreamArchive.transfer(input,blob,out)
                        val bytes = out.toByteArray()
                        require(sha(bytes) == (first.originalTextSha256[name] ?: first.originalBinarySha256.getValue(name)))
                        if (name in records.text) {
                            try { text[name] = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } finally { bytes.fill(0) }
                        } else binaries[name] = bytes
                    }
                    require(input.read() == -1)
                    val clearSha = digest.digest().joinToString("") { "%02x".format(it) }; require(clearSha == first.receipt.sha256)
                    if (first.schema == NativeMigrationNonemptyDeviceCase.FOREGROUND_SCHEMA)
                        require(NativeMigrationNotificationArchives.validatePortable(text).size == 4)
                    val inventory = NativeChatNotificationSoundLibrary.binaryInventory(text.getValue(NativeMigrationSounds.RECORD))
                    val childOriginals = first.schema == NativeMigrationChildOriginalsProfile.SCHEMA
                    require(inventory.keys == if (childOriginals) binaries.keys.filter { it.startsWith("native-chat-notification-sound-v1:") }.toSet() else binaries.keys)
                    for (name in inventory.keys) NativeChatNotificationSoundLibrary.validateBinary(inventory.getValue(name), binaries.getValue(name))
                    if (childOriginals) NativeMigrationChildOriginalsProfile.requirePortable(text, binaries)
                    Archive(text,clearSha,first.archiveSha256)
                } finally { binaries.values.forEach { it.fill(0) } }
            } }
            require(read.ciphertextSha256 == first.archiveSha256); ref.unchanged(); return read.value
        } catch (error: Throwable) { failure = error; throw error }
        finally { lifetime.closeOwned(failure) }
    }
    /** Holding pending context never claims to capture a constructor receiver that did not return. */
    class ReadOwners(private val scope: NativeMigrationNonemptyDeviceCase.Scope) {
        private class Pending(val scope: NativeMigrationNonemptyDeviceCase.Scope,val source: Boolean) { var returned: NativeMigrationExistingView? = null }
        private class Known(val view: NativeMigrationExistingView,var usingEntered: Boolean=false,var usingReturned: Boolean=false)
        private val known = mutableListOf<Known>()
        var closeReturns = 0; private set
        private var pending: Pending? = null
        private val lifetime = NativeMigrationReadLifetime()
        fun <T> read(source: Boolean,consume: (NativeMigrationExistingView)->T): T {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            check(pending == null && known.all { it.usingReturned })
            pending = Pending(scope,source)
            val view = scope.view(source) // Unreturned constructor receivers are never claimed captured.
            requireNotNull(pending).returned = view
            val item=Known(view); known.add(item); pending=null
            // Production using passes this exact block failure into lifetime.closeOwned(first).
            // A hold never returns here; a rethrown block assertion is not a new close error.
            item.usingEntered=true
            return view.using(consume).also { item.usingReturned=true; closeReturns++ }
        }
        fun close(primary: Throwable?) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            // Every entered using owns its one close attempt, including an exceptional return.
            // Never call close/using again on that receiver or replace its original assertion.
            if(pending != null || known.any { !it.usingEntered })
                lifetime.hold(this,primary ?: IllegalStateException("Unreturned read acquisition remains UNKNOWN"))
        }
    }
    /** Protected data only: run no_backup/cache/files and immutable first documents. atime excluded. */
    fun protected(folder: File): Map<String,String> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val result = linkedMapOf<String,String>(); val dev = Os.lstat(folder.path).st_dev
        var count = 0; var total = 0L
        val lifetime = NativeMigrationReadLifetime()
        fun <T> withFd(file: File, consume: (java.io.FileDescriptor)->T): T = lifetime.use(
            { Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0) },consume,{ Os.close(it) })
        fun id(s: android.system.StructStat) = listOf(s.st_dev,s.st_ino,s.st_uid.toLong(),s.st_mode.toLong(),s.st_nlink,s.st_size,s.st_mtime,s.st_ctime).joinToString(":")
        fun visit(file: File,relative: String) {
            require(++count <= 4096 && file.canonicalFile == file)
            val before = Os.lstat(file.path)
            require(before.st_dev == dev && before.st_uid == Process.myUid() && before.st_mode and 0x3f == 0)
            if (OsConstants.S_ISDIR(before.st_mode)) {
                val names = withFd(file) { fd ->
                    require(id(Os.fstat(fd)) == id(before)); val names = requireNotNull(file.list()).sorted()
                    require(names.size <= 1024); names.forEach { require(it !in setOf(".","..") && '/' !in it) }
                    for (name in names) visit(File(file,name),"$relative/$name")
                    require(requireNotNull(file.list()).sorted() == names && id(Os.fstat(fd)) == id(before)); names
                }
                result[relative] = id(before) + ":" + sha(names.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            } else {
                require(OsConstants.S_ISREG(before.st_mode) && before.st_nlink == 1L && before.st_size in 0..(32L*1024*1024))
                total += before.st_size; require(total <= 64L*1024*1024)
                val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                withFd(file) { fd ->
                    require(id(Os.fstat(fd)) == id(before)); val bytes = ByteArray(65536)
                    while (true) { val n=Os.read(fd,bytes,0,bytes.size); if(n==0)break; size+=n; require(size<=before.st_size); digest.update(bytes,0,n) }
                    require(size==before.st_size && id(Os.fstat(fd))==id(before))
                }
                result[relative] = id(before) + ":" + digest.digest().joinToString("") { "%02x".format(it) }
            }
            require(id(Os.lstat(file.path)) == id(before))
        }
        for (name in listOf("no_backup","cache","files","first-intent.json","first-proof.json")) visit(File(folder,name),name)
        return result.toSortedMap()
    }
    fun aliasPins(scope: NativeMigrationNonemptyDeviceCase.Scope,generation: String): Map<String,Boolean> {
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val names = linkedSetOf(scope.alias,scope.sourceAlias)
        for (base in listOf(scope.alias,scope.sourceAlias)) for (gen in listOf<String?>(null,generation)) {
            val children = linkedMapOf<String,String>()
            for (path in (NativeMigrationExistingPolicy.paths + "migration-candidates").sortedWith(compareBy<String> { it.count { c -> c=='/' } }.thenBy { it })) {
                val parent=path.substringBeforeLast('/',""); val alias=if(parent.isEmpty())base else children.getValue(parent)
                val child=NativeMigrationExistingView.childAlias(alias,path.substringAfterLast('/'),if(parent.isEmpty())gen else null)
                children[path]=child; names.add(child)
            }
        }
        return names.sorted().associateWith(keys::containsAlias)
    }
}
