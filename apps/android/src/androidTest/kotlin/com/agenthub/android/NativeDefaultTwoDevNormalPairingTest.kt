package com.agenthub.android

import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Normal DEV API setup, not pairing GUI or FCM acceptance.
 * Root's reviewed Native parent must verify exact SDK/App/runner/signatures/private nonce
 * and full-original protection BEFORE invoking instrumentation with nativeForeground=1.
 * NativeTestForeground normally starts this acceptance App's shipping MainActivity;
 * this is normal App lifecycle + API pairing setup, not zero-Activity or GUI button proof.
 * Wait at most 30 seconds for the exact MainActivity to be RESUMED with restore finished.
 * Never constructs/replaces a Session or manually calls restore/reset.
 * Only a target-bearing production EasyWork LAN offer with one attempted claim is accepted:
 * automatic uses its first endpoint; a legacy offer must contain exactly one endpoint.
 * EWDAP/16-character relay codes are deliberately outside this selector's contract.
 */
@RunWith(AndroidJUnit4::class)
class NativeDefaultTwoDevNormalPairingTest {
    @get:Rule(order=0) val foreground: TestRule=NativeTestForeground()
    private var preStartupSnapshot: NativeSafeDevSetupObservation.Snapshot? = null
    private var preStartupRun: String? = null
    @get:Rule(order=-1) val preStartupObservation: TestRule = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                try {
                    val args = InstrumentationRegistry.getArguments()
                    admit(args.keySet().all { it in setOf("class", "ownerCallbacksRun", "nativeForeground", "debug", "log", "timeout_msec", "disableAnalytics") })
                    admit(args.getString("class") == selector && args.getString("nativeForeground") == "1" &&
                        instrumentation.targetContext.packageName == app)
                    val run = checkNotNull(args.getString("ownerCallbacksRun"))
                    admit(UUID.fromString(run).toString() == run)
                    preStartupSnapshot = runBlocking { withContext(Dispatchers.IO) {
                        NativeSafeDevSetupObservation.capture(instrumentation.targetContext.applicationContext, run, selector)
                    } }
                    preStartupRun = run
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("safeDevSetupObservationV1", NativeSafeDevSetupObservation.encoded(JSONObject()
                            .put("schema", 2).put("preStartup", checkNotNull(preStartupSnapshot).report)))
                    })
                } catch (_: Exception) {
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("safeDevSetupObservationV1", JSONObject().put("schema", 2)
                            .put("preStartup", NativeSafeDevSetupObservation.unavailable())
                            .put("foregroundAndApiNotEntered", true).toString())
                    })
                    throw NativeSafeDevSetupObservation.Unavailable()
                }
                // Deliberately outside the admission catch: preserve the original rule/test failure.
                base.evaluate()
            }
        }
    }
    private val selector = "com.agenthub.android.NativeDefaultTwoDevNormalPairingTest#appendTwoFixedDevConnectionsWithoutInput"
    private val app = "com.easyai.agenthub.android.acceptance"
    private val codexHash = "4edc39b469ffdfebdbf64b04e9a672decd1aa5037646336018bfb63581ebbf9b"
    private val ewHash = "6ee4a03911278315dcdb5ba58c21817eb321d2ea35175a191b17e957407d0f8d"
    private fun admit(ok: Boolean) { check(ok) { "PAIRING_CANDIDATE_GUARD" } }
    private fun exact(v: JSONObject, keys: Set<String>) = admit(v.keys().asSequence().toSet() == keys)
    private fun digest(v: Any) = NativeChatRecoveryCanonical.hash(v)
    private fun target(v: TargetScope) = digest(CodexCommandCodec.target(v))
    private fun sha(v: String) = v.also { admit(it.matches(Regex("[a-f0-9]{64}"))) }
    /** Android stream(FileDescriptor) wrappers borrow the raw descriptor. */
    private inline fun <T> withOwnedFd(fd: java.io.FileDescriptor, block: ()->T): T {
        var primary: Throwable?=null
        try { return block() }
        catch(failure: Throwable) { primary=failure;throw failure }
        finally {
            try { Os.close(fd) }
            catch(closeFailure: Throwable) {
                val original=primary
                if(original==null)throw closeFailure
                if(original!==closeFailure)original.addSuppressed(closeFailure)
            }
        }
    }
    private fun secure(file: File, directory: Boolean) {
        val s = Os.lstat(file.absolutePath)
        admit(s.st_uid == android.os.Process.myUid() &&
            (s.st_mode and 511) == if (directory) 448 else 384)
        admit(if (directory) OsConstants.S_ISDIR(s.st_mode) else OsConstants.S_ISREG(s.st_mode))
    }
    private fun syncDirectory(root: File) {
        val before = Os.lstat(root.absolutePath)
        val fd = Os.open(root.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val held = Os.fstat(fd)
            admit(OsConstants.S_ISDIR(held.st_mode) && held.st_uid == android.os.Process.myUid() &&
                (held.st_mode and 511) == 448 && held.st_dev == before.st_dev && held.st_ino == before.st_ino)
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    private fun privateJson(file: File): JSONObject {
        secure(file, false)
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        val raw = withOwnedFd(fd) { FileInputStream(fd).use { input ->
            val s = Os.fstat(fd)
            admit(OsConstants.S_ISREG(s.st_mode) && s.st_uid == android.os.Process.myUid() && (s.st_mode and 511) == 384 && s.st_size in 1..65_536)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                admit(output.size() + n <= 65_536); output.write(buffer, 0, n)
            }
            val bytes = output.toByteArray(); admit(bytes.size.toLong() == s.st_size)
            val after = Os.fstat(fd)
            admit(s.st_dev == after.st_dev && s.st_ino == after.st_ino && s.st_size == after.st_size && s.st_mtime == after.st_mtime)
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } }
        val v = JSONObject(raw)
        // Root writes canonical JSON, no duplicate keys/whitespace/trailing payload ambiguity.
        admit(NativeChatRecoveryCanonical.value(v) == raw)
        return v
    }
    /** Exclusive parent permits one cooperative writer for this private test directory.
     * O_EXCL reserves the consumed name, not an adversarial same-UID lock. Bounded
     * FD/path/body checks surround both directory barriers; failures retain the
     * original and any partial consumed copy for the same operation's diagnosis.
     */
    private fun consumeOriginalInput(root: File, input: File, consumed: File,
        expected: ByteArray, phase: (String)->Unit) {
        fun same(a: android.system.StructStat, b: android.system.StructStat) =
            a.st_dev==b.st_dev && a.st_ino==b.st_ino && a.st_uid==b.st_uid && a.st_gid==b.st_gid &&
                a.st_mode==b.st_mode && a.st_nlink==b.st_nlink && a.st_size==b.st_size &&
                a.st_mtime==b.st_mtime && a.st_ctime==b.st_ctime
        fun exactBody(fd: java.io.FileDescriptor, value: ByteArray) {
            Os.lseek(fd,0L,OsConstants.SEEK_SET)
            val probe=ByteArray(value.size)
            try {
                var offset=0
                while(offset<probe.size) { val n=Os.read(fd,probe,offset,probe.size-offset);admit(n>0);offset+=n }
                admit(Os.read(fd,ByteArray(1),0,1)==0 && probe.contentEquals(value))
            } finally { probe.fill(0) }
        }
        try {
            admit(expected.size in 1..65_536)
            phase("original-input-consume-open")
            secure(root,true);secure(input,false)
            val directory=Os.lstat(root.absolutePath)
            fun currentDirectory() {
                val current=Os.lstat(root.absolutePath)
                admit(OsConstants.S_ISDIR(current.st_mode) && current.st_dev==directory.st_dev &&
                    current.st_ino==directory.st_ino && current.st_uid==directory.st_uid &&
                    current.st_gid==directory.st_gid && current.st_mode==directory.st_mode)
            }
            val inputFd=Os.open(input.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC,0)
            withOwnedFd(inputFd) {
                val before=Os.fstat(inputFd)
                admit(OsConstants.S_ISREG(before.st_mode) && before.st_uid==android.os.Process.myUid() &&
                    (before.st_mode and 511)==384 && before.st_nlink==1L &&
                    before.st_size==expected.size.toLong() && before.st_dev==directory.st_dev &&
                    same(before,Os.lstat(input.absolutePath)))
                phase("original-input-consume-compare")
                exactBody(inputFd,expected)
                admit(same(before,Os.fstat(inputFd)) && same(before,Os.lstat(input.absolutePath)))
                phase("original-input-consume-exclusive-create")
                val outputFd=Os.open(consumed.absolutePath,
                    OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,384)
                withOwnedFd(outputFd) {
                    val reserved=Os.fstat(outputFd)
                    admit(OsConstants.S_ISREG(reserved.st_mode) && reserved.st_uid==before.st_uid &&
                        (reserved.st_mode and 511)==384 && reserved.st_nlink==1L && reserved.st_size==0L &&
                        reserved.st_dev==directory.st_dev && reserved.st_ino!=before.st_ino &&
                        same(reserved,Os.lstat(consumed.absolutePath)))
                    phase("original-input-consume-copy")
                    var offset=0
                    while(offset<expected.size) { val n=Os.write(outputFd,expected,offset,expected.size-offset);admit(n>0);offset+=n }
                    Os.fsync(outputFd)
                    val written=Os.fstat(outputFd)
                    admit(written.st_dev==reserved.st_dev && written.st_ino==reserved.st_ino &&
                        written.st_uid==reserved.st_uid && written.st_gid==reserved.st_gid && written.st_mode==reserved.st_mode &&
                        written.st_nlink==1L && written.st_size==expected.size.toLong())
                    fun currentConsumed() {
                        admit(same(written,Os.fstat(outputFd)) && same(written,Os.lstat(consumed.absolutePath)))
                        exactBody(outputFd,expected)
                        admit(same(written,Os.fstat(outputFd)) && same(written,Os.lstat(consumed.absolutePath)))
                    }
                    phase("original-input-consume-copy-sync")
                    currentConsumed();syncDirectory(root)
                    currentDirectory();currentConsumed()
                    exactBody(inputFd,expected)
                    admit(same(before,Os.fstat(inputFd)) && same(before,Os.lstat(input.absolutePath)))
                    phase("original-input-consume-unlink")
                    java.nio.file.Files.delete(input.toPath())
                    phase("original-input-consume-sync")
                    syncDirectory(root)
                    currentDirectory();currentConsumed()
                    val removed=Os.fstat(inputFd)
                    admit(removed.st_dev==before.st_dev && removed.st_ino==before.st_ino && removed.st_nlink==0L &&
                        removed.st_uid==before.st_uid && removed.st_gid==before.st_gid && removed.st_mode==before.st_mode &&
                        removed.st_size==before.st_size && removed.st_mtime==before.st_mtime)
                }
            }
        } finally { expected.fill(0) }
    }

    private fun metadata(context: android.content.Context): JSONObject =
        NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use {
            JSONObject(checkNotNull(it.optionalText("metadata", 1_048_576)))
        }
    /** Preserve each complete original journal ciphertext without interpreting old intents.
     * Presence and full cipher digest stay in memory; no query/replay/normalization.
     * A legacy journal need not satisfy the current submission schema to be preserved.
     */
    private fun originalOperationSnapshot(context: android.content.Context,
        targets: List<String>, slot: (Int)->Unit = {}): Map<String, String> =
        NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use { reader ->
            targets.sorted().mapIndexed { index, key ->
                slot(index)
                val cipherDigest=reader.opaqueTextDigest("operations:$key", 1_048_576)
                key to digest(JSONObject().put("present",cipherDigest!=null).put("cipherSHA256",cipherDigest ?: JSONObject.NULL))
            }.toMap()
        }

    private fun originalFields(before: JSONObject, after: JSONObject,
        boundTargets: List<TargetScope>, connected: List<MobileClient>) {
        admit(before.keys().asSequence().toSet() == after.keys().asSequence().toSet())
        admit(boundTargets.size == 2 && connected.size <= boundTargets.size)
        val rows = before.getJSONArray("clients"); val current = after.getJSONArray("clients")
        val oldIDs = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
        admit(oldIDs.size == rows.length() && connected.map { it.id }.distinct().size == connected.size)
        admit(current.length() == rows.length() + connected.size)
        val currentIDs = (0 until current.length()).map { current.getJSONObject(it).getString("id") }
        admit(currentIDs.distinct().size == current.length())
        for (i in 0 until rows.length()) {
            val old = rows.getJSONObject(i)
            admit((0 until current.length()).count { digest(current.getJSONObject(it)) == digest(old) } == 1)
        }
        val computerKeys = mutableSetOf<String>()
        val draftKeys = mutableSetOf<String>()
        val workspaceKeys = mutableSetOf<String>()
        connected.forEachIndexed { index, saved ->
            admit(saved.id !in oldIDs && !saved.isDemo && saved.storageIdentity == null && saved.migrationEasyWork == null)
            val row = (0 until current.length()).map { current.getJSONObject(it) }.single { it.getString("id") == saved.id }
            // These are the shipping codec and the shipping MobileClient.cacheKey extension.
            val decoded = decodeClient(row)
            admit(digest(encodeClient(decoded)) == digest(row))
            val roundTrip = decodeClient(encodeClient(saved))
            admit(digest(encodeClient(roundTrip)) == digest(encodeClient(saved)))
            admit(decoded.storageIdentity == null && decoded.migrationEasyWork == null && !decoded.isDemo)
            val scope = checkNotNull(decoded.scope)
            admit(sameScope(scope, boundTargets[index]) && sameScope(roundTrip.scope, boundTargets[index]))
            if (index == 0) {
                admit(decoded.codexDirect != null && decoded.agentConnection == null && roundTrip.codexDirect != null)
            } else {
                admit(decoded.agentConnection != null && decoded.codexDirect == null && roundTrip.agentConnection != null)
            }
            val computerKey = decoded.cacheKey(null)
            admit(computerKey == roundTrip.cacheKey(null) && computerKeys.add(computerKey))
            // No chat or project is selected by either normal connection method.
            for (snapshot in listOf(before, after)) {
                val selections = snapshot.getJSONObject("selectedThreads")
                if (selections.has(computerKey)) admit(selections.get(computerKey) == "")
                admit(snapshot.optJSONObject("newProjects")?.has(computerKey) != true)
            }
            val draftKey = decoded.cacheKey(null, null)
            admit(draftKey == roundTrip.cacheKey(null, null) && draftKeys.add(draftKey))
            // saveEasyWorkLeased publishes with MobileRules; Codex publishes with cacheKey.
            val workspaceKey = if (index == 0) decoded.cacheKey(null)
                else com.agenthub.core.MobileRules.cacheKey(decoded.id, scope, null, null)
            admit(workspaceKey == computerKey && workspaceKeys.add(workspaceKey))
        }
        val allowed = mapOf(
            "workspaces" to workspaceKeys,
            "generationPreferences" to computerKeys, "selectedThreads" to computerKeys,
            "expanded" to computerKeys, "sidebar" to computerKeys,
            "draftVersions" to draftKeys, "generation" to draftKeys, "replyModes" to draftKeys)
        for (key in before.keys()) {
            if (key == "clients" || key == "selected") continue
            val b = before.get(key); val a = after.get(key)
            if (key in allowed) {
                admit(b is JSONObject && a is JSONObject)
                val oldMap = b as JSONObject; val newMap = a as JSONObject
                for (oldKey in oldMap.keys()) admit(newMap.has(oldKey) && digest(oldMap.get(oldKey)) == digest(newMap.get(oldKey)))
                val additions = newMap.keys().asSequence().toSet() - oldMap.keys().asSequence().toSet()
                admit(additions.all { it in allowed.getValue(key) })
            } else admit(digest(b) == digest(a))
        }
    }
    /** Fixed task-wide, role-specific exclusive marker survives a changed run UUID.
     * Persisted BEFORE calling a product authorization method. An unknown/failed role
     * remains consumed: inspect the original product receipt, never rerun this test.
     */
    private fun once(root: File, role: String, run: String, p: JSONObject) {
        val fd = Os.open(File(root, "$role.once.json").absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        withOwnedFd(fd) { FileOutputStream(fd).use {
            val body = JSONObject().put("run", run).put("role", role).put("targetSha256", if (role == "codex") codexHash else ewHash)
                .put("appSha256", p.getString("appSha256")).put("runnerSha256", p.getString("runnerSha256"))
                .put("sourceSetSha256", p.getString("sourceSetSha256"))
            it.write(NativeChatRecoveryCanonical.value(body).toByteArray()); it.fd.sync()
        } }
        syncDirectory(root)
    }
    @Test fun appendTwoFixedDevConnectionsWithoutInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        var phase = "admission"; var originalProtected = false; var completed = false
        var metadataBeforeSha256: String? = null
        val originalFieldHashes = JSONObject()
        val admissionChecks = JSONObject()
        var originalOperationJournalSlotCount: Int? = null
        var originalOperationSnapshotSHA256: String? = null
        var originalOperationsProtected = false
        var failureType: String? = null
        var failureErrno: Int? = null
        val safeObservation = JSONObject().put("schema", 2)
        val afterApiObservation = JSONObject()
        safeObservation.put("afterAPI", afterApiObservation)
        var safeBefore: NativeSafeDevSetupObservation.Snapshot? = null
        var observationRun: String? = null
        try {
            runBlocking {
                val args = InstrumentationRegistry.getArguments()
                admit(args.keySet().all { it in setOf("class", "ownerCallbacksRun", "nativeForeground", "debug", "log", "timeout_msec", "disableAnalytics") })
                admit(args.getString("class") == selector && context.packageName == app && args.getString("nativeForeground") == "1")
                val run = checkNotNull(args.getString("ownerCallbacksRun"))
                admit(UUID.fromString(run).toString() == run)
                observationRun = run
                admit(preStartupRun == run && preStartupSnapshot != null)
                safeObservation.put("preStartup", checkNotNull(preStartupSnapshot).report)
                // Root creates these private directories and transmits this nonce file via stdin.
                // No arbitrary file, namespace, URL, secret, or method arrives in instrumentation args.
                val root = File(context.filesDir, "f52-default-two-dev-pairing-v1")
                secure(root, true)
                val input = File(root, "$run.json")
                val p = withContext(Dispatchers.IO) { privateJson(input) }
                exact(p, setOf("version", "action", "ownerCallbacksRun", "package", "appSha256", "runnerSha256",
                    "signerSetSha256", "sourceSetSha256", "codex", "easywork"))
                admit(p.getInt("version") == 1 && p.getString("action") == "append-two-fixed-devs-normal-pairing-api-setup" &&
                    p.getString("ownerCallbacksRun") == run && p.getString("package") == app)
                for (k in listOf("appSha256", "runnerSha256", "signerSetSha256", "sourceSetSha256")) sha(p.getString(k))
                admit(p.getString("signerSetSha256") == "4f719bb23e1366a472b42507d73147e69b005aaf84f8d12ae020208dc1618965")
                withContext(Dispatchers.IO) {
                    val installed = NativeChatNotificationNaturalStore.installed(context)
                    admit(installed.appHash == p.getString("appSha256") && installed.runnerHash == p.getString("runnerSha256") &&
                        installed.signerSetHash == p.getString("signerSetSha256"))
                }
                val c = p.getJSONObject("codex"); exact(c, setOf("target", "endpoint", "fingerprint", "hostKey"))
                val e = p.getJSONObject("easywork"); exact(e, setOf("target", "fingerprint", "lanQr"))
                for (rawTarget in listOf(c.getJSONObject("target"), e.getJSONObject("target"))) {
                    admit(com.agenthub.core.DomainContract.validate(rawTarget.toString(), "target") == null)
                }
                val cs = checkNotNull(HostReadCodec.target(c.getJSONObject("target")))
                val es = checkNotNull(HostReadCodec.target(e.getJSONObject("target")))
                admit(target(cs) == codexHash && cs.agentId == "codex" && cs.hostId == "13c81542-48e3-4df2-ab46-d45357b631a2" &&
                    cs.workspaceId == cs.hostId && cs.runtimeId == "desktop-runtime")
                admit(target(es) == ewHash && es.agentId == "easywork" && es.hostId == "device-6d96807c-157c-4e78-9bb7-628a59fe08bf" &&
                    es.workspaceId == "workspace-d20f172966704eff4d1261ba" && es.runtimeId == "local")
                val endpoint = CodexEndpoints.local(c.getString("endpoint")); sha(c.getString("fingerprint")); sha(e.getString("fingerprint"))
                admit(c.getString("hostKey").let { it.length in 32..512 && it.none(Char::isWhitespace) })
                val uri = android.net.Uri.parse(e.getString("lanQr"))
                admit(uri.queryParameterNames == setOf("descriptor") && uri.getQueryParameters("descriptor").size == 1 &&
                    uri.path.isNullOrEmpty() && uri.fragment == null)
                val offer = EasyWorkLANOffer.parse(e.getString("lanQr"))
                admit(target(offer.host.target.scope()) == ewHash && offer.host.fingerprint == e.getString("fingerprint") &&
                    (offer.value.optString("connectionMode") == "automatic" || offer.host.endpoints.size == 1))
                phase = "normal-startup-await-restored-main"
                var resumed: MainActivity? = null
                withTimeout(30_000) {
                    while (resumed == null) {
                        resumed = withContext(Dispatchers.Main) {
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filter { it.javaClass == MainActivity::class.java }.filterIsInstance<MainActivity>()
                                .singleOrNull()?.takeIf { !it.session.restoring }
                        }
                        if (resumed == null) delay(50)
                    }
                }
                val main = checkNotNull(resumed)
                val session = main.session
                phase = "original-profile-admission-after-normal-restore"
                val before = withContext(Dispatchers.IO) { metadata(context) }
                metadataBeforeSha256 = digest(before)
                phase = "original-profile-version"
                admit(before.getInt("version") == 1)
                for (field in listOf("drafts", "draftFiles", "draftVersions", "attachmentImportGenerations", "photoSources", "appearance", "language")) {
                    if (before.has(field)) originalFieldHashes.put(field, digest(before.get(field)))
                }
                phase = "original-profile-single-client"
                admit(before.getJSONArray("clients").length() == 1)
                originalFieldHashes.put("fixtureDescriptor", digest(before.getJSONArray("clients").getJSONObject(0)))
                val old = decodeClient(before.getJSONArray("clients").getJSONObject(0))
                val ewId = "easywork-$ewHash"
                phase = "original-profile-client-kind"
                admit(!old.isDemo && old.id != cs.hostId && old.id != ewId && old.agentConnection == null && old.codexDirect == null && old.migrationEasyWork == null)
                phase = "original-profile-no-dev-selection"
                for (key in listOf(MobileClient(cs.hostId, "", cs).cacheKey(null), MobileClient(ewId, "", es).cacheKey(null))) {
                    admit(!before.getJSONObject("selectedThreads").has(key) && before.optJSONObject("newProjects")?.has(key) != true)
                }
                withContext(Dispatchers.Main) {
                    // Fixed booleans distinguish a missing prerequisite from an API failure;
                    // no draft, key, target ID or exception text is added to the report.
                    admissionChecks.put("restoreComplete", !session.restoring)
                        .put("localRecoveryClear", !session.localRecoveryRequired)
                        .put("foreground", session.usageForeground)
                        .put("defaultStorage", session.migrationStorage.coordinationKey == File(context.noBackupFilesDir, "mobile-v1").canonicalPath)
                        .put("singleClient", session.clients.size == 1)
                        .put("selectedMatches", session.selectedClient.id == before.getString("selected"))
                        .put("incomingLinkAbsent", session.incomingEasyWorkLink == null)
                        .put("operationsEmpty", session.operationRecords.isEmpty())
                        .put("currentDraftVersionPresent", before.getJSONObject("draftVersions").has(session.targetKey))
                    phase = "original-session-lifecycle"
                    admit(main.javaClass == MainActivity::class.java && !session.restoring && !session.localRecoveryRequired && session.usageForeground)
                    phase = "original-session-default-storage"
                    admit(session.migrationStorage.coordinationKey == File(context.noBackupFilesDir, "mobile-v1").canonicalPath)
                    phase = "original-session-client-projection"
                    admit(session.clients.size == 1 && digest(encodeClient(session.clients.single())) == digest(before.getJSONArray("clients").getJSONObject(0)))
                    phase = "original-session-selected-client"
                    admit(session.selectedClient.id == before.getString("selected"))
                    phase = "original-session-no-incoming-link"
                    admit(session.incomingEasyWorkLink == null)
                    // connect reads version(sourceKey); require the old draft already owns its version before either role claim.
                    phase = "original-session-draft-version"
                    admit(before.getJSONObject("draftVersions").has(session.targetKey))
                }
                phase = "original-operation-targets"
                val originalTargets = withContext(Dispatchers.Main) {
                    (session.selectedClient.operationTargets(null) + session.selectedClient.operationTargets(
                        session.selectedThread.takeIf { it.isNotEmpty() })).distinct().also { admit(it.size <= 64) }
                }
                phase = "original-operation-await-writes"
                val originalOperations = withContext(Dispatchers.IO) {
                    session.migrationStorage.awaitQueuedWrites()
                    phase = "original-operation-open-readonly"
                    originalOperationSnapshot(context, originalTargets) { index ->
                        phase = "original-operation-readonly-slot-$index"
                    }
                }
                originalOperationJournalSlotCount = originalOperations.size
                originalOperationSnapshotSHA256 = digest(JSONObject().apply {
                    originalOperations.toSortedMap().forEach { (key, value) -> put(key, value) }
                })
                // Required existing-only admission precedes input consumption and BOTH role claims.
                safeObservation.put("beforeRole", NativeSafeDevSetupObservation.unavailable())
                safeBefore = withContext(Dispatchers.IO) {
                    NativeSafeDevSetupObservation.capture(context, run, selector)
                }.also { safeObservation.put("beforeRole", it.report) }
                // All admission and read-only observations precede either immutable role intent.
                // Exclusive copy publication refuses an existing consumed name; retain private recovery bytes.
                withContext(Dispatchers.IO) {
                    phase = "original-profile-unchanged-before-consume"
                    admit(digest(metadata(context)) == metadataBeforeSha256)
                    val consumed = File(root, "$run.consumed.json")
                    phase = "original-pair-markers-absent"
                    admit(!consumed.exists() && !File(root, "codex.once.json").exists() && !File(root, "easywork.once.json").exists())
                    consumeOriginalInput(root,input,consumed,
                        NativeChatRecoveryCanonical.value(p).toByteArray(Charsets.UTF_8)) { phase=it }
                }
                var connected = emptyList<MobileClient>()
                try {
                phase = "codex-original-intent-or-result"
                withContext(Dispatchers.IO) { once(root, "codex", run, p) }
                withTimeout(90_000) { withContext(Dispatchers.Main) {
                    val saved = session.connectCodexDiscovered(DiscoveredCodexHost(cs.hostId, "Codex DEV", endpoint, false, true,
                        target = cs, fingerprint = c.getString("fingerprint")), c.getString("hostKey"))
                    admit(target(checkNotNull(saved.scope)) == codexHash && session.selectedThread.isEmpty())
                    connected = connected + saved
                } }
                withContext(Dispatchers.IO) {
                    session.migrationStorage.awaitQueuedWrites()
                    originalFields(before, metadata(context), listOf(cs, es), connected)
                    admit(originalOperationSnapshot(context, originalTargets) == originalOperations)
                    afterApiObservation.put("codex", NativeSafeDevSetupObservation.after(context, run, selector,
                        checkNotNull(preStartupSnapshot), checkNotNull(safeBefore)))
                }
                phase = "easywork-original-intent-or-result"
                withContext(Dispatchers.IO) { once(root, "easywork", run, p) }
                withTimeout(180_000) { withContext(Dispatchers.Main) {
                    val saved = session.connectEasyWorkQR(e.getString("lanQr"), "EasyWork DEV")
                    admit(target(checkNotNull(saved.agentConnection).target) == ewHash && session.selectedThread.isEmpty())
                    connected = connected + saved
                } }
                withContext(Dispatchers.IO) {
                    session.migrationStorage.awaitQueuedWrites()
                    val after = metadata(context); originalFields(before, after, listOf(cs, es), connected)
                    admit(originalOperationSnapshot(context, originalTargets) == originalOperations)
                    admit(after.getJSONArray("clients").length() == 3)
                    val rows = after.getJSONArray("clients")
                    val targets = (0 until rows.length()).map { index ->
                        val v = decodeClient(rows.getJSONObject(index))
                        (v.agentConnection?.target ?: v.scope)?.let(::target)
                    }
                    admit(targets.count { it == codexHash } == 1 && targets.count { it == ewHash } == 1)
                    afterApiObservation.put("easywork", NativeSafeDevSetupObservation.after(context, run, selector,
                        checkNotNull(preStartupSnapshot), checkNotNull(safeBefore)))
                }
                withContext(Dispatchers.Main) { admit(session.operationRecords.isEmpty()); admit(session.clients.size == 3) }
                completed = true
                } finally {
                    // Both success and a settled exception return to the original fixture computer.
                    // No real chat selection, draft setter/reset, credential cleanup, or claim retry.
                    withContext(kotlinx.coroutines.NonCancellable) { withTimeout(30_000) {
                        withContext(Dispatchers.Main) { session.select(session.clients.single { it.id == old.id }) }
                        withContext(Dispatchers.IO) {
                            session.migrationStorage.awaitQueuedWrites()
                            val after = metadata(context); originalFields(before, after, listOf(cs, es), connected)
                            admit(originalOperationSnapshot(context, originalTargets) == originalOperations)
                            originalOperationsProtected = true
                            admit(after.getString("selected") == before.getString("selected"))
                        }
                    } }
                    originalProtected = true
                }
                phase = "api-setup-complete-external-post-required"
            }
        } catch (failure: Exception) {
            // Class and numeric errno only; messages/paths may contain private input.
            failureType=failure.javaClass.name
            failureErrno=(failure as? android.system.ErrnoException)?.errno
            completed = false
        } finally {
            // Observation failure cannot change the original API completion or protection fields.
            val observedBefore = safeBefore
            val observedRun = observationRun
            if (observedBefore != null && observedRun != null) {
                val finalObservation = try {
                    runBlocking { withContext(Dispatchers.IO) {
                        NativeSafeDevSetupObservation.after(context, observedRun, selector,
                            checkNotNull(preStartupSnapshot), observedBefore)
                    } }
                } catch (_: Exception) { NativeSafeDevSetupObservation.unavailable() }
                safeObservation.put("final", finalObservation)
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("defaultTwoDevPairing", JSONObject()
                .put("phase", phase).put("apiSetupCompleted", completed).put("originalMetadataProjectionProtected", originalProtected)
                .put("failureType",failureType ?: JSONObject.NULL).put("failureErrno",failureErrno ?: JSONObject.NULL)
                .put("metadataBeforeSha256", metadataBeforeSha256 ?: JSONObject.NULL)
                .put("originalFieldHashes", originalFieldHashes)
                .put("admissionChecks", admissionChecks)
                .put("originalOperationJournalSlotCount", originalOperationJournalSlotCount ?: JSONObject.NULL)
                .put("originalOperationSnapshotSHA256", originalOperationSnapshotSHA256 ?: JSONObject.NULL)
                .put("originalOperationJournalProjectionProtected", originalOperationsProtected)
                .put("guiPairingVerified", false).put("vendorPushVerified", false)
                .put("externalFullOriginalPostRequired", true).toString()) })
            instrumentation.sendStatus(2, Bundle().apply {
                putString("safeDevSetupObservationV1", NativeSafeDevSetupObservation.encoded(safeObservation))
            })
        }
        check(completed) { "PAIRING_NOT_CONFIRMED_INSPECT_ORIGINAL_RECEIPT_NO_RETRY" }
    }
}
