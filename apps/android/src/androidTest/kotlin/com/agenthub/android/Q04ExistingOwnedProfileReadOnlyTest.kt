package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey

/** Device-elapsed read-only contract. No wall clock, model-ready flags or source-specific hash. */
internal object Q04OwnedProfileReadOnlyContract {
    fun integer(value: Any?): Long {
        require(value is Int || value is Long) { "An exact JSON integer is required" }
        return (value as Number).toLong()
    }
    fun integer(value: JSONObject, key: String) = integer(value.get(key))
    fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    data class FileRef(val path: String, val bytes: Long, val sha256: String)
    fun reference(value: JSONObject): FileRef {
        require(keys(value) == setOf("path", "bytes", "sha256"))
        val path = value.getString("path"); val bytes = integer(value, "bytes"); val sha = value.getString("sha256")
        require(path.startsWith("/") && path.length <= 4096 && path.none { it.code < 32 } && path.split('/').none { it == "." || it == ".." })
        require(bytes in 1..134_217_728 && sha.matches(Regex("[a-f0-9]{64}")))
        return FileRef(path, bytes, sha)
    }
    fun reference(value: JSONObject, key: String) = reference(value.getJSONObject(key))
    class ElapsedLease(val notBefore: Long, val expires: Long) {
        private var last = -1L
        init { require(notBefore >= 0 && expires > notBefore && expires - notBefore <= 60_000) }
        fun check(now: Long) {
            require(now >= notBefore && now < expires && now >= last) { "Read-only device-elapsed lease inactive" }
            last = now
        }
    }
    fun clock(lease: JSONObject): ElapsedLease {
        require(lease.getString("clockDomain") == "android-device-elapsed-realtime-ms")
        return ElapsedLease(integer(lease, "notBeforeDeviceElapsedMs"), integer(lease, "expiresDeviceElapsedMs"))
    }
    val leaseKeys = setOf("kind", "inspectionRun", "rootReadOnlyApproved", "firstNativeLaunchRecorded", "executionAuthorized", "modelAuthorized",
        "newGrantAuthorized", "pairingAuthorized", "clockDomain", "notBeforeDeviceElapsedMs", "expiresDeviceElapsedMs", "nativeFailureFactRef",
        "originalClaimRef", "originalReservationRef", "originalPlanRef", "firstNativeIntentRef", "nativeResultRef", "existingProfileRef",
        "runtimeSourceBindingRef", "appRef", "runnerRef", "signerSha256", "uid", "api", "installationAndroidIdSha256", "target", "threadId",
        "endpoint", "certificateSha256", "controlHostId", "localMappingSha256", "allowedEndpoints", "pairingIntentSha256")
    fun validateFact(lease: JSONObject, fact: JSONObject, manifest: JSONObject, originalPlan: JSONObject, binding: JSONObject) {
        require(keys(lease) == leaseKeys) // In particular no old model/controller ready flags.
        require(fact.getString("kind") == "root-confirmed-native-failed-before-profile-open-no-business-v1" && fact.opt("rootConfirmed") == true)
        for (k in listOf("executionAuthorized", "recoveryAuthorized", "freshAuthorized", "replayAllowed", "currentAdmissionOrSecondNativeAllowed")) require(fact.opt(k) == false)
        for ((a, b) in listOf("originalClaimRef" to "originalClaimRef", "originalReservationRef" to "originalReservationRef",
            "originalPlanRef" to "originalPlanRef", "firstNativeIntentRef" to "firstNativeIntentRef", "nativeResultRef" to "firstNativeResultRef"))
            require(reference(lease, a) == reference(fact, b))
        require(fact.getJSONObject("nonzeroSideEffects").opt("permanentFirstNativeIntentPresent") == true && fact.opt("oldFilesMustRemainImmutable") == true)
        val zero = fact.getJSONObject("knownBusinessZero")
        require(keys(zero) == setOf("fourRemainingBodySubmissions", "newIndependentTurns", "newChildStarts", "queueCancelOrExecuteOrSteerOrStopFromThisAttempt"))
        keys(zero).forEach { require(integer(zero, it) == 0L) }
        val attempt = fact.getJSONObject("nativeAttempt")
        for ((key, count) in mapOf("clientPopen" to 1L, "methods" to 1L, "failures" to 1L, "unknown" to 0L)) require(integer(attempt, key) == count)
        require(attempt.opt("beforeOwnedProfileFileLock") == true && attempt.opt("beforeOwnedProfileKeyStore") == true && attempt.opt("beforeInspectSessionActivityAndBusiness") == true)
        require(manifest.getString("originalClaimSha256") == reference(lease, "originalClaimRef").sha256)
        require(originalPlan.getString("childOnceSha256") == reference(lease, "originalReservationRef").sha256)
        require(originalPlan.getString("existingProfileSha256") == reference(lease, "existingProfileRef").sha256)
        require(originalPlan.getString("rootAuthoritySha256") == reference(fact, "originalAuthorityRef").sha256)
        require(originalPlan.getString("signerSha256") == lease.getString("signerSha256"))
        require(binding.getString("kind") == "q04-owned-profile-readonly-runtime-source-binding-v1" && binding.opt("rootReviewed") == true && binding.opt("executionAuthorized") == false)
        for (key in listOf("appRef", "runnerRef", "nativeFailureFactRef", "existingProfileRef")) require(reference(binding, key) == reference(lease, key))
        require(binding.getString("signerSha256") == lease.getString("signerSha256"))
        require(binding.getString("admissionExpectation") in setOf("known-old-directory-rejection-only", "current-profile-read-and-lock"))
        require(reference(binding, "ownedProfileSourceRef").path.endsWith("/Q04ExistingOwnedProfile.kt"))
        require(reference(binding, "readOnlyTestSourceRef").path.endsWith("/Q04ExistingOwnedProfileReadOnlyTest.kt"))
        reference(binding, "sourceInputsRef"); reference(binding, "buildRef")
        // Target comparison uses exact four-machine-field values, with no account metadata.
        val targetKeys = setOf("agentId", "hostId", "workspaceId", "runtimeId")
        val target = lease.getJSONObject("target"); require(keys(target) == targetKeys)
        for (source in listOf(fact, manifest, originalPlan)) {
            val other = source.getJSONObject("target"); require(keys(other) == targetKeys)
            targetKeys.forEach { require(target.getString(it) == other.getString(it)) }
            require(source.getString("threadId") == lease.getString("threadId"))
        }
        require(fact.getString("run") == manifest.getString("continuationRun") && fact.getString("leaseId") == manifest.getString("leaseId"))
    }
}

/** PROPOSAL ONLY. A separately admitted, finite read-only invocation, not remaining-controls recovery.
 * No Activity/Profile preparation, Session, pairing, command, Clipboard, alias creation/deletion,
 * first-native/reservation marker, original installation, or persistent result output.
 * Root must separately freeze the new App/runner and enforce the device/read-only lease lifetime.
 */
@RunWith(AndroidJUnit4::class)
class Q04ExistingOwnedProfileReadOnlyTest {
    @get:Rule val timeout = Timeout.seconds(60)

    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
    private fun hex(value: String) = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
    private fun requireApk(context: Context, name: String, expected: Q04OwnedProfileReadOnlyContract.FileRef) {
        val file = File(context.packageManager.getApplicationInfo(name, 0).sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65_536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        require(file.length() == expected.bytes && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256)
    }
    private fun input(file: File, expectedSha: String, expectedBytes: Long? = null): JSONObject {
        require(file.absoluteFile == file.canonicalFile && !Files.isSymbolicLink(file.toPath()))
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val bytes = try {
            val before = Os.fstat(fd)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() &&
                before.st_nlink == 1L && before.st_mode and 0x1ff == 0x180 && before.st_size in 1..65536)
            if (expectedBytes != null) require(before.st_size == expectedBytes)
            val data = ByteArray(before.st_size.toInt()); var offset = 0
            while (offset < data.size) { val n = Os.read(fd, data, offset, data.size - offset); require(n > 0); offset += n }
            require(Os.read(fd, ByteArray(1), 0, 1) == 0)
            val after = Os.fstat(fd); val path = Os.lstat(file.path)
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size &&
                before.st_mtime == after.st_mtime && before.st_dev == path.st_dev && before.st_ino == path.st_ino && before.st_size == path.st_size)
            data
        } finally { Os.close(fd) }
        try { require(sha(bytes) == hex(expectedSha)); return JSONObject(bytes.toString(Charsets.UTF_8)) }
        finally { bytes.fill(0) }
    }
    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val mode: Int, val links: Long, val bytes: Long)
    private fun stamp(file: File): Stamp = Os.lstat(file.path).let {
        Stamp(it.st_dev, it.st_ino, it.st_uid, it.st_mode, it.st_nlink, it.st_size)
    }
    private fun opaqueInventory(root: File, manifest: JSONObject): Map<String, Pair<Stamp, String>> =
        manifest.getJSONArray("inventory").objects().associate { row ->
            val relative = row.getString("path")
            require(relative.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin")))
            val file = File(root, relative); val before = stamp(file)
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                require(before.bytes in 1..1_048_576)
                val opened = Os.fstat(fd)
                require(opened.st_dev == before.device && opened.st_ino == before.inode && opened.st_size == before.bytes)
                val buffer = ByteArray(65_536); var count = 0L
                try {
                    while (true) {
                        val n = Os.read(fd, buffer, 0, buffer.size); if (n == 0) break
                        count += n; require(count <= before.bytes); digest.update(buffer, 0, n)
                    }
                    require(count == before.bytes)
                } finally { buffer.fill(0) }
            } finally { Os.close(fd) }
            require(stamp(file) == before)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == row.getString("sha256"))
            relative to (before to hash)
        }.also { require(it.size == 11) }
    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Throwable) { if (failure is T) return; throw failure }
        error("Expected the exact admission/closed-state failure")
    }
    private fun close(owned: Q04ExistingOwnedProfile, primary: Throwable?) {
        try { owned.release() } catch (failure: Throwable) {
            if (primary != null) primary.addSuppressed(failure) else throw failure
        }
    }
    private fun existingKey(alias: String) {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(keys.containsAlias(alias))
        val key = keys.getKey(alias, null)
        require(key is SecretKey && key.encoded == null)
    }

    private fun directoryDiagnostic(context: Context, manifest: JSONObject): JSONObject {
        val rows = org.json.JSONArray()
        fun probe(label: String, file: File, productionCall: Boolean, expectedDevice: Long? = null) {
            val row = JSONObject().put("label", label).put("absolute", file.absolutePath).put("productionDirectoryCall", productionCall)
            try {
                val canonical = file.canonicalFile
                row.put("canonical", canonical.path).put("absoluteEqualsCanonical", file.absoluteFile == canonical)
            } catch (failure: Throwable) { row.put("canonicalErrorClass", failure.javaClass.name) }
            try { row.put("isSymbolicLink", Files.isSymbolicLink(file.toPath())) }
            catch (failure: Throwable) { row.put("symlinkErrorClass", failure.javaClass.name) }
            try {
                val st = Os.lstat(file.path)
                row.put("mode", st.st_mode).put("uid", st.st_uid).put("device", st.st_dev).put("inode", st.st_ino)
                    .put("isDirectory", OsConstants.S_ISDIR(st.st_mode)).put("ownerMatches", st.st_uid == Process.myUid())
                    .put("modeIs0700", st.st_mode and 0x1ff == 0x1c0)
                if (expectedDevice != null) row.put("expectedDevice", expectedDevice).put("deviceMatches", st.st_dev == expectedDevice)
            } catch (failure: Throwable) { row.put("lstatErrorClass", failure.javaClass.name) }
            rows.put(row)
        }
        val raw = context.filesDir
        probe("targetContext.filesDir", raw, false)
        val canonical = runCatching { raw.canonicalFile }.getOrNull()
        if (canonical != null) {
            probe("trustedFilesRoot", canonical, false)
            val parent = File(canonical, "q02-real-dev-profiles-v1")
            probe("profileParent", parent, true)
            val device = runCatching { Os.lstat(parent.path).st_dev }.getOrNull()
            probe("ownedProfile", File(parent, manifest.getString("storageOwnerRun")), true, device)
            (rows.getJSONObject(rows.length() - 1)).put("parentDevicePredicateAvailable", device != null)
            probe("acceptanceParent.current", File(canonical, "acceptance"), true)
            probe("remainingParent.current", File(canonical, "acceptance/q04-remaining-v1"), true)
            probe("lockDir.current", File(canonical, "acceptance/q04-remaining-v1/" + manifest.getString("storageOwnerRun")), true)
        }
        // Observation only: old f015 used these two raw-Context parents. Never pass a
        // resolved child from this diagnostic into admission or relax an actual assertion.
        probe("acceptanceParent.oldRawContext", File(raw, "acceptance"), false)
        probe("remainingParent.oldRawContext", File(raw, "acceptance/q04-remaining-v1"), false)
        return JSONObject().put("kind", "owned-profile-directory-predicates-before-admission-v1")
            .put("sampledAtDeviceElapsedMs", SystemClock.elapsedRealtime()).put("paths", rows)
            .put("canonicalFilesRootAvailable", canonical != null)
    }
    private fun status(name: String, result: JSONObject, run: String, leaseSha: String) {
        // 73 is diagnostic only, never an AndroidJUnitRunner completion 0.
        runCatching {
            require(name in setOf("ownedProfileDirectoryAdmissionBefore", "ownedProfileAdmissionFailure", "ownedProfileReadOnlyAdmission"))
            result.put("inspectionRun", run).put("readOnlyLeaseSha256", leaseSha)
            require(result.toString().toByteArray().size <= 16_384)
            InstrumentationRegistry.getInstrumentation().sendStatus(73, Bundle().apply { putString(name, result.toString()) })
        }
    }

    /** A new current inventory is not the inventory admitted by the historical A/B test.
     * This method can only authenticate cache structure under the existing lock. It never
     * prepares a Profile, opens a writer, creates a key, or grants remaining-controls execution.
     */
    @Test fun inspectCurrentOwnedCacheGraphWithoutActivityOrWrites() {
        val started=SystemClock.elapsedRealtime()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("readCurrentOwnedCacheGraph")=="true")
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
        require(instrumentation.context.packageName==context.packageName+".test"&&context.applicationInfo.uid==Process.myUid())
        val run=uuid(checkNotNull(args.getString("ownedProfileReadRun")))
        val folder=File(context.filesDir.canonicalFile,"acceptance/q04-current-cache-graph-readonly-v1/$run")
        val leaseSha=hex(checkNotNull(args.getString("ownedProfileReadLeaseSha256")))
        val lease=input(File(folder,"read-only-lease.json"),leaseSha)
        val keys=setOf("kind","inspectionRun","rootReadOnlyApproved","executionAuthorized","modelAuthorized","newGrantAuthorized",
            "pairingAuthorized","replayAllowed","clockDomain","notBeforeDeviceElapsedMs","expiresDeviceElapsedMs",
            "currentExistingProfileRef","originalExistingProfileRef","currentInventoryReviewRef","runtimeSourceBindingRef",
            "historicalReadOnlyBReviewRef","secondNativeResultRef","appRef","runnerRef","signerSha256","uid","api",
            "installationAndroidIdSha256","target","threadId","endpoint","certificateSha256","controlHostId",
            "localMappingSha256","allowedEndpoints","pairingIntentSha256","addedCandidateFilename","removedHistoricalFilename")
        require(Q04OwnedProfileReadOnlyContract.keys(lease)==keys)
        require(lease.getString("kind")=="q04-existing-owned-current-cache-graph-readonly-v1"&&lease.getString("inspectionRun")==run)
        require(lease.opt("rootReadOnlyApproved")==true)
        for(key in listOf("executionAuthorized","modelAuthorized","newGrantAuthorized","pairingAuthorized","replayAllowed"))require(lease.opt(key)==false)
        val clock=Q04OwnedProfileReadOnlyContract.clock(lease);clock.check(SystemClock.elapsedRealtime())
        fun document(key: String,name: String): JSONObject {
            val ref=Q04OwnedProfileReadOnlyContract.reference(lease,key)
            return input(File(folder,name),ref.sha256,ref.bytes)
        }
        val manifest=document("currentExistingProfileRef","current-existing-profile.json")
        val original=document("originalExistingProfileRef","original-existing-profile.json")
        val currentReview=document("currentInventoryReviewRef","current-inventory-review.json")
        val binding=document("runtimeSourceBindingRef","runtime-source-binding.json")
        // Historical references are not fresh execution authority and are never modified.
        for(key in listOf("historicalReadOnlyBReviewRef","secondNativeResultRef"))Q04OwnedProfileReadOnlyContract.reference(lease,key)
        require(currentReview.getString("kind")=="root-reviewed-q04-current-opaque-inventory-v1"&&currentReview.opt("rootReviewed")==true)
        require(currentReview.opt("executionAuthorized")==false&&currentReview.opt("modelAuthorized")==false)
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"currentExistingProfileRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"currentExistingProfileRef"))
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"originalExistingProfileRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"originalExistingProfileRef"))
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"secondNativeResultRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"secondNativeResultRef"))
        require(Q04OwnedProfileReadOnlyContract.keys(manifest)==Q04OwnedProfileReadOnlyContract.keys(original))
        fun historicalCore(value: JSONObject)=easyCanonical(JSONObject(value.toString()).apply {remove("inventory");remove("inventorySourceRef")})
        require(historicalCore(manifest)==historicalCore(original))
        require(currentReview.getString("currentInventorySha256")==sha(easyCanonical(manifest.getJSONArray("inventory")).toByteArray()))
        require(binding.getString("kind")=="q04-current-cache-graph-runtime-source-binding-v1"&&binding.opt("rootReviewed")==true&&binding.opt("executionAuthorized")==false)
        for(key in listOf("appRef","runnerRef","currentExistingProfileRef","originalExistingProfileRef"))
            require(Q04OwnedProfileReadOnlyContract.reference(binding,key)==Q04OwnedProfileReadOnlyContract.reference(lease,key))
        for(key in listOf("readOnlyTestSourceRef","cacheGraphSourceRef","sourceInputsRef","buildRef"))Q04OwnedProfileReadOnlyContract.reference(binding,key)
        require(binding.getString("signerSha256")==lease.getString("signerSha256"))
        val app=Q04OwnedProfileReadOnlyContract.reference(lease,"appRef")
        val runner=Q04OwnedProfileReadOnlyContract.reference(lease,"runnerRef")
        requireApk(context,context.packageName,app);requireApk(context,context.packageName+".test",runner)
        val signer=hex(lease.getString("signerSha256"))
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val signatures=checkNotNull(context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size==1&&sha(signatures.single().toByteArray())==signer)
        }
        val uid=Q04OwnedProfileReadOnlyContract.integer(lease,"uid")
        val api=Q04OwnedProfileReadOnlyContract.integer(lease,"api")
        require(uid==Process.myUid().toLong()&&api==android.os.Build.VERSION.SDK_INT.toLong())
        val device=hex(lease.getString("installationAndroidIdSha256"))
        require(sha(checkNotNull(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)).toByteArray())==device)
        val target=checkNotNull(HostReadCodec.target(lease.getJSONObject("target")))
        require(Q04OwnedProfileReadOnlyContract.keys(lease.getJSONObject("target"))==setOf("agentId","hostId","workspaceId","runtimeId"))
        require(target.agentId=="codex"&&sameScope(target,HostReadCodec.target(manifest.getJSONObject("target"))))
        require(lease.getString("threadId")==manifest.getString("threadId"))
        require(lease.getString("endpoint")==manifest.getString("endpoint")&&lease.getString("certificateSha256")==manifest.getString("certificateSha256"))
        require(run!=manifest.getString("storageOwnerRun")&&run!=manifest.getString("continuationRun"))
        val permit=Q02RealReadInstallation.Permit(manifest.getString("continuationRun"),manifest.getString("leaseId"),device,
            app.sha256,runner.sha256,signer,api.toInt(),0L,uuid(lease.getString("controlHostId")),hex(lease.getString("localMappingSha256")),
            target,lease.getString("endpoint"),lease.getJSONArray("allowedEndpoints").strings(),hex(lease.getString("certificateSha256")),
            hex(lease.getString("pairingIntentSha256")),null)
        instrumentation.runOnMainSync {
            val lifecycle=ActivityLifecycleMonitorRegistry.getInstance()
            require(Stage.values().filter {it!=Stage.DESTROYED}.all {s->lifecycle.getActivitiesInStage(s).none {it is MainActivity}})
        }
        clock.check(SystemClock.elapsedRealtime())
        val owned=Q04ExistingOwnedProfile.admit(context,permit,manifest)
        var primary: Throwable?=null
        var stage="opaque-inventory"
        var before: Map<String,Pair<Stamp,String>>?=null
        val lock=File(context.filesDir.canonicalFile,"acceptance/q04-remaining-v1/${owned.ownerRun}/writer.lock")
        var beforeLock: Stamp?=null
        lateinit var result: JSONObject
        try {
            val snapshot=opaqueInventory(owned.root,manifest)
            before=snapshot
            val lockStamp=stamp(lock)
            beforeLock=lockStamp
            fun unchanged() {require(opaqueInventory(owned.root,manifest)==snapshot&&stamp(lock)==lockStamp);owned.assertProtected()}
            stage="inspect-current-owner"
            owned.inspect(Q02RealReadInstallation.FilesContext(context,owned.root));unchanged();clock.check(SystemClock.elapsedRealtime())
            expectFailure<OverlappingFileLockException> {Q04ExistingOwnedProfile.admit(context,permit,manifest).also {it.release()}}
            existingKey(owned.alias)
            val entries=manifest.getJSONArray("inventory").objects().associate {row->
                val path=row.getString("path")
                path to Q04CurrentOwnedCacheGraph.CipherEntry(snapshot.getValue(path).first.bytes,row.getString("sha256"))
            }
            stage="production-cache-graph"
            result=Q04CurrentOwnedCacheGraph.inspect(context,owned,target,lease.getString("threadId"),entries,
                lease.getString("addedCandidateFilename"),lease.getString("removedHistoricalFilename"),
                {clock.check(SystemClock.elapsedRealtime())},::unchanged)
            unchanged();clock.check(SystemClock.elapsedRealtime())
            result.put("overlapRejected",true).put("currentOwnerInspectPassed",true).put("existingNonexportableKey",true)
        }catch(failure: Throwable) {
            primary=failure
            instrumentation.sendStatus(73,Bundle().apply {putString("ownedCurrentCacheGraphFailure",JSONObject()
                .put("kind","q04-current-cache-graph-readonly-failure-v1").put("inspectionRun",run).put("stage",stage)
                .put("exceptionClass",failure.javaClass.name).put("readOnlyLeaseSha256",leaseSha).toString())})
            throw AssertionError("Current owned cache graph failed at $stage (${failure.javaClass.name}); no payload exported")
        }finally {
            try {
                before?.let {require(opaqueInventory(owned.root,manifest)==it)}
                beforeLock?.let {require(stamp(lock)==it)}
                owned.assertProtected()
            }catch(fenceFailure: Throwable) {
                if(primary!=null)primary.addSuppressed(fenceFailure)else throw fenceFailure
            }finally {close(owned,primary)}
        }
        expectFailure<IllegalArgumentException> {owned.inspect(Q02RealReadInstallation.FilesContext(context,owned.root))}
        val reacquired=Q04ExistingOwnedProfile.admit(context,permit,manifest)
        try {
            reacquired.inspect(Q02RealReadInstallation.FilesContext(context,reacquired.root))
            require(opaqueInventory(reacquired.root,manifest)==before&&stamp(lock)==beforeLock)
        }
        finally {reacquired.release()}
        clock.check(SystemClock.elapsedRealtime());require(SystemClock.elapsedRealtime()-started<30_000)
        result.put("inspectionRun",run).put("readOnlyLeaseSha256",leaseSha).put("releaseAndReacquirePassed",true)
            .put("closedInspectRejected",true).put("currentExistingProfileRef",lease.getJSONObject("currentExistingProfileRef"))
            .put("appSha256",app.sha256).put("runnerSha256",runner.sha256).put("elapsedMs",SystemClock.elapsedRealtime()-started)
        require(result.toString().toByteArray().size<=16_384)
        instrumentation.sendStatus(73,Bundle().apply {putString("ownedCurrentCacheGraph",result.toString())})
    }

    @Test fun inspectCurrentOwnedRecordsAndLedgerCompatibilityWithoutActivityOrWrites() {
        val started=SystemClock.elapsedRealtime()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("readCurrentOwnedRecordCompatibility")=="true")
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
        require(instrumentation.context.packageName==context.packageName+".test"&&context.applicationInfo.uid==Process.myUid())
        val run=uuid(checkNotNull(args.getString("ownedProfileReadRun")))
        val folder=File(context.filesDir.canonicalFile,"acceptance/q04-current-record-compatibility-readonly-v1/$run")
        val leaseSha=hex(checkNotNull(args.getString("ownedProfileReadLeaseSha256")))
        val lease=input(File(folder,"read-only-lease.json"),leaseSha)
        val keys=setOf("kind","enabled","inspectionRun","rootReadOnlyApproved","executionAuthorized","modelAuthorized","newGrantAuthorized",
            "pairingAuthorized","replayAllowed","clockDomain","notBeforeDeviceElapsedMs","expiresDeviceElapsedMs",
            "currentExistingProfileRef","originalExistingProfileRef","currentInventoryReviewRef","runtimeSourceBindingRef",
            "historicalReadOnlyBReviewRef","secondNativeResultRef","appRef","runnerRef","signerSha256","uid","api",
            "installationAndroidIdSha256","target","threadId","endpoint","certificateSha256","controlHostId",
            "localMappingSha256","allowedEndpoints","pairingIntentSha256","currentUnresolvedFilename","removedHistoricalFilename")
        require(Q04OwnedProfileReadOnlyContract.keys(lease)==keys)
        require(lease.getString("kind")=="q04-existing-owned-current-record-compatibility-readonly-v1"&&lease.getString("inspectionRun")==run)
        require(lease.opt("rootReadOnlyApproved")==true&&lease.opt("enabled")==true)
        for(key in listOf("executionAuthorized","modelAuthorized","newGrantAuthorized","pairingAuthorized","replayAllowed"))require(lease.opt(key)==false)
        val clock=Q04OwnedProfileReadOnlyContract.clock(lease);clock.check(SystemClock.elapsedRealtime())
        fun document(key: String,name: String): JSONObject {
            val ref=Q04OwnedProfileReadOnlyContract.reference(lease,key)
            return input(File(folder,name),ref.sha256,ref.bytes)
        }
        val manifest=document("currentExistingProfileRef","current-existing-profile.json")
        val original=document("originalExistingProfileRef","original-existing-profile.json")
        val currentReview=document("currentInventoryReviewRef","current-inventory-review.json")
        val binding=document("runtimeSourceBindingRef","runtime-source-binding.json")
        // Historical references are not fresh execution authority and are never modified.
        for(key in listOf("historicalReadOnlyBReviewRef","secondNativeResultRef"))Q04OwnedProfileReadOnlyContract.reference(lease,key)
        require(currentReview.getString("kind")=="root-reviewed-q04-current-opaque-inventory-v1"&&currentReview.opt("rootReviewed")==true)
        require(currentReview.opt("executionAuthorized")==false&&currentReview.opt("modelAuthorized")==false)
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"currentExistingProfileRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"currentExistingProfileRef"))
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"originalExistingProfileRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"originalExistingProfileRef"))
        require(Q04OwnedProfileReadOnlyContract.reference(currentReview,"secondNativeResultRef")==Q04OwnedProfileReadOnlyContract.reference(lease,"secondNativeResultRef"))
        require(Q04OwnedProfileReadOnlyContract.keys(manifest)==Q04OwnedProfileReadOnlyContract.keys(original))
        fun historicalCore(value: JSONObject)=easyCanonical(JSONObject(value.toString()).apply {remove("inventory");remove("inventorySourceRef")})
        require(historicalCore(manifest)==historicalCore(original))
        require(currentReview.getString("currentInventorySha256")==sha(easyCanonical(manifest.getJSONArray("inventory")).toByteArray()))
        require(binding.getString("kind")=="q04-current-record-compatibility-runtime-source-binding-v1"&&binding.opt("rootReviewed")==true&&binding.opt("executionAuthorized")==false)
        for(key in listOf("appRef","runnerRef","currentExistingProfileRef","originalExistingProfileRef"))
            require(Q04OwnedProfileReadOnlyContract.reference(binding,key)==Q04OwnedProfileReadOnlyContract.reference(lease,key))
        for(key in listOf("readOnlyTestSourceRef","cacheGraphSourceRef","compatibilitySourceRef","ownedProfileSourceRef","sessionStorageSourceRef","ledgerSourceRef","readingScopeSourceRef","claimProducerSourceRef","sourceInputsRef","buildRef"))Q04OwnedProfileReadOnlyContract.reference(binding,key)
        require(binding.getString("signerSha256")==lease.getString("signerSha256"))
        val app=Q04OwnedProfileReadOnlyContract.reference(lease,"appRef")
        val runner=Q04OwnedProfileReadOnlyContract.reference(lease,"runnerRef")
        requireApk(context,context.packageName,app);requireApk(context,context.packageName+".test",runner)
        val signer=hex(lease.getString("signerSha256"))
        for(name in listOf(context.packageName,context.packageName+".test")) {
            val signatures=checkNotNull(context.packageManager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size==1&&sha(signatures.single().toByteArray())==signer)
        }
        val uid=Q04OwnedProfileReadOnlyContract.integer(lease,"uid")
        val api=Q04OwnedProfileReadOnlyContract.integer(lease,"api")
        require(uid==Process.myUid().toLong()&&api==android.os.Build.VERSION.SDK_INT.toLong())
        val device=hex(lease.getString("installationAndroidIdSha256"))
        require(sha(checkNotNull(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)).toByteArray())==device)
        val target=checkNotNull(HostReadCodec.target(lease.getJSONObject("target")))
        require(Q04OwnedProfileReadOnlyContract.keys(lease.getJSONObject("target"))==setOf("agentId","hostId","workspaceId","runtimeId"))
        require(target.agentId=="codex"&&sameScope(target,HostReadCodec.target(manifest.getJSONObject("target"))))
        require(lease.getString("threadId")==manifest.getString("threadId"))
        require(lease.getString("endpoint")==manifest.getString("endpoint")&&lease.getString("certificateSha256")==manifest.getString("certificateSha256"))
        require(run!=manifest.getString("storageOwnerRun")&&run!=manifest.getString("continuationRun"))
        val permit=Q02RealReadInstallation.Permit(manifest.getString("continuationRun"),manifest.getString("leaseId"),device,
            app.sha256,runner.sha256,signer,api.toInt(),0L,uuid(lease.getString("controlHostId")),hex(lease.getString("localMappingSha256")),
            target,lease.getString("endpoint"),lease.getJSONArray("allowedEndpoints").strings(),hex(lease.getString("certificateSha256")),
            hex(lease.getString("pairingIntentSha256")),null)
        instrumentation.runOnMainSync {
            val lifecycle=ActivityLifecycleMonitorRegistry.getInstance()
            require(Stage.values().filter {it!=Stage.DESTROYED}.all {s->lifecycle.getActivitiesInStage(s).none {it is MainActivity}})
        }
        clock.check(SystemClock.elapsedRealtime())
        val owned=Q04ExistingOwnedProfile.admit(context,permit,manifest)
        var primary: Throwable?=null
        var stage="opaque-inventory"
        var before: Map<String,Pair<Stamp,String>>?=null
        val lock=File(context.filesDir.canonicalFile,"acceptance/q04-remaining-v1/${owned.ownerRun}/writer.lock")
        var beforeLock: Stamp?=null
        lateinit var result: JSONObject
        try {
            val snapshot=opaqueInventory(owned.root,manifest)
            before=snapshot
            val lockStamp=stamp(lock)
            beforeLock=lockStamp
            fun unchanged() {require(opaqueInventory(owned.root,manifest)==snapshot&&stamp(lock)==lockStamp);owned.assertProtected()}
            stage="inspect-current-owner"
            owned.inspect(Q02RealReadInstallation.FilesContext(context,owned.root));unchanged();clock.check(SystemClock.elapsedRealtime())
            expectFailure<OverlappingFileLockException> {Q04ExistingOwnedProfile.admit(context,permit,manifest).also {it.release()}}
            existingKey(owned.alias)
            val entries=manifest.getJSONArray("inventory").objects().associate {row->
                val path=row.getString("path")
                path to Q04CurrentOwnedCacheGraph.CipherEntry(snapshot.getValue(path).first.bytes,row.getString("sha256"))
            }
            stage="production-current11-and-ledger-compatibility"
            result=Q04CurrentOwnedRecordCompatibility.inspect(context,owned,target,lease.getString("threadId"),entries,
                lease.getString("currentUnresolvedFilename"),lease.getString("removedHistoricalFilename"),
                manifest.getString("originalOperationId"),manifest.getString("originalTurnId"),
                {clock.check(SystemClock.elapsedRealtime())},::unchanged)
            unchanged();clock.check(SystemClock.elapsedRealtime())
            result.put("overlapRejected",true).put("currentOwnerInspectPassed",true).put("existingNonexportableKey",true)
        }catch(failure: Throwable) {
            primary=failure
            instrumentation.sendStatus(73,Bundle().apply {putString("ownedCurrentRecordCompatibilityFailure",JSONObject()
                .put("kind","q04-current-record-compatibility-readonly-failure-v1").put("inspectionRun",run).put("stage",stage)
                .put("exceptionClass",failure.javaClass.name).put("readOnlyLeaseSha256",leaseSha).toString())})
            throw AssertionError("Current owned record compatibility failed at $stage (${failure.javaClass.name}); no payload exported")
        }finally {
            try {
                before?.let {require(opaqueInventory(owned.root,manifest)==it)}
                beforeLock?.let {require(stamp(lock)==it)}
                owned.assertProtected()
            }catch(fenceFailure: Throwable) {
                if(primary!=null)primary.addSuppressed(fenceFailure)else throw fenceFailure
            }finally {close(owned,primary)}
        }
        expectFailure<IllegalArgumentException> {owned.inspect(Q02RealReadInstallation.FilesContext(context,owned.root))}
        val reacquired=Q04ExistingOwnedProfile.admit(context,permit,manifest)
        try {
            reacquired.inspect(Q02RealReadInstallation.FilesContext(context,reacquired.root))
            require(opaqueInventory(reacquired.root,manifest)==before&&stamp(lock)==beforeLock)
        }
        finally {reacquired.release()}
        clock.check(SystemClock.elapsedRealtime());require(SystemClock.elapsedRealtime()-started<30_000)
        result.put("inspectionRun",run).put("readOnlyLeaseSha256",leaseSha).put("releaseAndReacquirePassed",true)
            .put("closedInspectRejected",true).put("currentExistingProfileRef",lease.getJSONObject("currentExistingProfileRef"))
            .put("originalLockUnchanged",true).put("originalInventoryUnchanged",true)
            .put("protectedRecordCount",3).put("originalProtectedRecordsUnchanged",true)
            .put("existingKeyStillPresent",true).put("keyStoreExistingAliasReadOnly",true)
            .put("profileWrites",0).put("activityLaunches",0).put("keyCreateDeleteActions",0)
            .put("appSha256",app.sha256).put("runnerSha256",runner.sha256).put("elapsedMs",SystemClock.elapsedRealtime()-started)
        require(result.toString().toByteArray().size<=16_384)
        instrumentation.sendStatus(73,Bundle().apply {putString("ownedCurrentRecordCompatibility",result.toString())})
    }

    @Test fun inspectExistingOwnedProfileAdmissionAndCloseWithoutActivityOrWrites() {
        val started = SystemClock.elapsedRealtime()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("readExistingOwnedProfile") == "true")
        require(args.getString("nativeForeground") == "0" && args.getString("nativeThreadSnapshots") == "0")
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance")
        require(instrumentation.context.packageName == context.packageName + ".test" && context.applicationInfo.uid == Process.myUid())
        val inspectionRun = uuid(checkNotNull(args.getString("ownedProfileReadRun")))
        val folder = File(context.filesDir.canonicalFile, "acceptance/q04-owned-profile-readonly-v1/$inspectionRun")
        val leaseSha = hex(checkNotNull(args.getString("ownedProfileReadLeaseSha256")))
        val lease = input(File(folder, "read-only-lease.json"), leaseSha)
        require(Q04OwnedProfileReadOnlyContract.keys(lease) == Q04OwnedProfileReadOnlyContract.leaseKeys)
        require(lease.getString("kind") == "q04-existing-owned-profile-readonly-admission-v1" && lease.getString("inspectionRun") == inspectionRun)
        require(lease.opt("rootReadOnlyApproved") == true && lease.opt("firstNativeLaunchRecorded") == true)
        for (key in listOf("executionAuthorized", "modelAuthorized", "newGrantAuthorized", "pairingAuthorized")) require(lease.opt(key) == false)
        val clock = Q04OwnedProfileReadOnlyContract.clock(lease); clock.check(SystemClock.elapsedRealtime())
        fun document(key: String, name: String): JSONObject {
            val ref = Q04OwnedProfileReadOnlyContract.reference(lease, key)
            return input(File(folder, name), ref.sha256, ref.bytes)
        }
        val fact = document("nativeFailureFactRef", "native-failure-fact.json")
        val manifest = document("existingProfileRef", "existing-profile.json")
        val originalPlan = document("originalPlanRef", "original-plan.json")
        val binding = document("runtimeSourceBindingRef", "runtime-source-binding.json")
        Q04OwnedProfileReadOnlyContract.validateFact(lease, fact, manifest, originalPlan, binding)
        require(inspectionRun != manifest.getString("continuationRun") && inspectionRun != manifest.getString("storageOwnerRun"))
        val appRef = Q04OwnedProfileReadOnlyContract.reference(lease, "appRef")
        val runnerRef = Q04OwnedProfileReadOnlyContract.reference(lease, "runnerRef")
        requireApk(context, context.packageName, appRef); requireApk(context, context.packageName + ".test", runnerRef)
        val appSha = appRef.sha256; val runnerSha = runnerRef.sha256
        val signer = hex(lease.getString("signerSha256"))
        for (name in listOf(context.packageName, context.packageName + ".test")) {
            val signatures = checkNotNull(context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
            require(signatures.size == 1 && sha(signatures.single().toByteArray()) == signer)
        }
        val uid = Q04OwnedProfileReadOnlyContract.integer(lease, "uid")
        val api = Q04OwnedProfileReadOnlyContract.integer(lease, "api")
        require(uid == Process.myUid().toLong() && api == android.os.Build.VERSION.SDK_INT.toLong())
        val deviceSha = hex(lease.getString("installationAndroidIdSha256"))
        require(sha(checkNotNull(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)).toByteArray()) == deviceSha)
        val manifestSha = Q04OwnedProfileReadOnlyContract.reference(lease, "existingProfileRef").sha256
        require(lease.getString("endpoint") == manifest.getString("endpoint") && lease.getString("certificateSha256") == manifest.getString("certificateSha256"))
        val target = checkNotNull(HostReadCodec.target(manifest.getJSONObject("target"))); require(target.agentId == "codex")
        // No business permit renewal or global Profile/Session preparation. 0L remains invalid.
        val permit = Q02RealReadInstallation.Permit(manifest.getString("continuationRun"), manifest.getString("leaseId"), deviceSha,
            appSha, runnerSha, signer, api.toInt(), 0L, uuid(lease.getString("controlHostId")), hex(lease.getString("localMappingSha256")),
            target, lease.getString("endpoint"), lease.getJSONArray("allowedEndpoints").strings(), hex(lease.getString("certificateSha256")),
            hex(lease.getString("pairingIntentSha256")), null)
        instrumentation.runOnMainSync {
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            require(Stage.values().filter { it != Stage.DESTROYED }.all { stage -> lifecycle.getActivitiesInStage(stage).none { it is MainActivity } })
        }
        val directoryState = runCatching { directoryDiagnostic(context, manifest) }.getOrElse {
            JSONObject().put("diagnosticErrorClass", it.javaClass.name)
        }
        status("ownedProfileDirectoryAdmissionBefore", directoryState, inspectionRun, leaseSha)
        val expectOldDirectoryRejection = binding.getString("admissionExpectation") == "known-old-directory-rejection-only"
        if (expectOldDirectoryRejection) {
            // Only call the old implementation when its exact pre-lock directory rejection is observed.
            // An unexpectedly canonical path must not enter its old lock/open/re-entrant path.
            val rows = directoryState.getJSONArray("paths").objects()
            for ((oldLabel, currentLabel) in listOf("acceptanceParent.oldRawContext" to "acceptanceParent.current",
                "remainingParent.oldRawContext" to "remainingParent.current")) {
                val old = rows.single { it.getString("label") == oldLabel }
                val current = rows.single { it.getString("label") == currentLabel }
                require(old.opt("absoluteEqualsCanonical") == false && old.opt("isSymbolicLink") == false)
                require(old.opt("isDirectory") == true && old.opt("ownerMatches") == true && old.opt("modeIs0700") == true)
                require(current.opt("absoluteEqualsCanonical") == true && current.opt("isSymbolicLink") == false)
                require(old.getString("canonical") == current.getString("absolute") && old.getLong("device") == current.getLong("device"))
            }
        }
        clock.check(SystemClock.elapsedRealtime())
        val owned = try { Q04ExistingOwnedProfile.admit(context, permit, manifest) }
        catch (failure: Throwable) {
            val frames = org.json.JSONArray()
            failure.stackTrace.filter { it.className.startsWith("com.agenthub.android.Q04ExistingOwnedProfile") }.take(8).forEach {
                frames.put(JSONObject().put("class", it.className).put("method", it.methodName).put("line", it.lineNumber))
            }
            status("ownedProfileAdmissionFailure", JSONObject().put("failureClass", failure.javaClass.name).put("productionFrames", frames), inspectionRun, leaseSha)
            throw failure
        }
        if (expectOldDirectoryRejection) {
            // Never inspect or re-enter the legacy implementation after unexpected success.
            val failure = IllegalStateException("Old directory admission unexpectedly succeeded; read-only comparison inconclusive")
            status("ownedProfileAdmissionFailure", JSONObject().put("failureClass", failure.javaClass.name)
                .put("productionFrames", org.json.JSONArray()).put("oldAdmissionUnexpectedlySucceeded", true), inspectionRun, leaseSha)
            close(owned, failure)
            throw failure
        }
        var primary: Throwable? = null
        val lock: File
        val originalLock: Stamp
        val snapshot: Map<String, Pair<Stamp, String>>
        try {
            lock = File(context.filesDir.canonicalFile, "acceptance/q04-remaining-v1/${owned.ownerRun}/writer.lock")
            originalLock = stamp(lock)
            snapshot = opaqueInventory(owned.root, manifest)
            clock.check(SystemClock.elapsedRealtime())
            expectFailure<OverlappingFileLockException> {
                Q04ExistingOwnedProfile.admit(context, permit, manifest).also { it.release() }
            }
            existingKey(owned.alias)
            clock.check(SystemClock.elapsedRealtime())
            owned.inspect(Q02RealReadInstallation.FilesContext(context, owned.root))
            require(owned.protectedRecords.keys == Q04ExistingOwnedProfile.protectedNames && owned.protectedRecords.size == 3)
            require(owned.original.id == manifest.getString("originalOperationId") && owned.original.phase == "completed")
            require(sameScope(owned.client.scope, target))
            require(opaqueInventory(owned.root, manifest) == snapshot && stamp(lock) == originalLock)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { close(owned, primary) }
        require(owned.protectedRecords.isEmpty())
        clock.check(SystemClock.elapsedRealtime())
        expectFailure<IllegalArgumentException> { owned.inspect(Q02RealReadInstallation.FilesContext(context, owned.root)) }
        // Actual re-admission proves release dropped the original lock; no copied canonical-root oracle.
        clock.check(SystemClock.elapsedRealtime())
        val reopened = Q04ExistingOwnedProfile.admit(context, permit, manifest)
        primary = null
        try {
            require(reopened.protectedRecords.isEmpty() && reopened.root == owned.root && reopened.ownerRun == owned.ownerRun)
            require(opaqueInventory(reopened.root, manifest) == snapshot && stamp(lock) == originalLock)
            existingKey(reopened.alias)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { close(reopened, primary) }
        existingKey(owned.alias)
        require(stamp(lock) == originalLock && opaqueInventory(owned.root, manifest) == snapshot)
        clock.check(SystemClock.elapsedRealtime())
        require(SystemClock.elapsedRealtime() - started < 30_000)
        val result = JSONObject().put("kind", "q04-owned-profile-readonly-native-result-v1").put("inspectionRun", inspectionRun)
            .put("appSha256", appSha).put("runnerSha256", runnerSha).put("manifestSha256", manifestSha)
            .put("inventoryCount", snapshot.size).put("protectedRecordCount", 3).put("productionDecodePassed", true)
            .put("overlappingAdmissionRejected", true).put("closedInspectionRejected", true).put("reAdmissionAfterRelease", true)
            .put("originalLockUnchanged", stamp(lock) == originalLock).put("originalInventoryUnchanged", true).put("existingKeyStillPresent", true)
        status("ownedProfileReadOnlyAdmission", result, inspectionRun, leaseSha)
    }
}
