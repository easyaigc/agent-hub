package com.agenthub.android

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** One original failed synthetic lease only. No Activity, ticket dispatch, navigation, default store or replay.
 * Run inspect first. Parent reviews its immutable snapshot, then explicitly supplies that SHA to seal/release.
 * Neither method is a cold-route pass or a business recovery. Unknown/partial states fail closed. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationColdFailureObserverTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    companion object {
        private const val LEASE = "9fd0fac1-423e-4d6e-9de4-ae97d462e551"
        private const val PLAN = "bc8df29c6adb393d6fd2486f533280c0ada6797a0b8428f7fd2f861376dd520b"
        private const val APP = "9c97812eba8400588ad021240795aadc10ba2871d2aa138758e2ed4e22bc18dd"
        private const val RUNNER = "8f26c84db3917ceda60693e5874cedb60e46d3343551045038655c8f2ee62824"
        private const val WRITER_FILE = "writer-armed-498555a9-d8fa-4736-b5f9-6a46d9f88e88.json"
        private const val WRITER = "94ee247d00029106657c0218f21874b596a9557230318de516e91efc7be7e7d0"
        private const val FAILURE_FILE = "reader-failed-unresolved-adb46638-b336-4184-8446-41c55af8fe23.json"
        private const val FAILURE = "6bc88405d9c95d8c7e296beb38069edc8776892c29254a1deb686e75c0e527e8"
    }
    private fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private val folder get() = File(context.filesDir, "acceptance/P9-notification-cold/$LEASE")
    @Before fun fixedFreshObserver() {
        Assume.assumeTrue("Explicit parent maintenance lease required; skip is not acceptance", InstrumentationRegistry.getArguments().getString("p9ColdLease") != null)
        require(InstrumentationRegistry.getArguments().getString("p9ColdLease") == LEASE)
        NativeChatNotificationColdStart.requireHooks(context)
        require(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey)
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { require(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
        require(NativeChatNotificationColdStart.activityEntries.get() == 0 && NativeChatNotificationColdStart.defaultSelections.get() == 0 && NativeChatNotificationColdStart.active == null)
    }
    private fun inventory(namespace: String): Map<String,String> {
        val parent = context.noBackupFilesDir; val root = File(parent, namespace)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == parent.canonicalFile)
        val rows = checkNotNull(root.listFiles()); require(rows.size in 1..8 && rows.sumOf { it.length() } <= 1_048_576)
        require(rows.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[a-f0-9]{64}\\.bin")) && it.length() in 29..524_288 }) { "Unknown/partial original preserved before storage can recover sidecars" }
        return rows.associate { it.name to hash(it.readBytes()) }
    }
    private fun originalProof(name: String, expected: String): JSONObject {
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile == File(context.filesDir.canonicalFile, "acceptance/P9-notification-cold/$LEASE"))
        val file = File(folder, name); require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..16_384)
        val bytes = file.readBytes(); require(hash(bytes) == expected)
        return JSONObject(bytes.toString(Charsets.UTF_8)).also {
            require(it.getString("lease") == LEASE && it.getString("planHash") == PLAN && it.getString("appHash") == APP && it.getString("runnerHash") == RUNNER)
        }
    }
    private fun apk(runner: Boolean): String {
        val info = if(!runner)context.applicationInfo else context.packageManager.getApplicationInfo(context.packageName + ".test", 0).also {
            require(context.packageManager.checkSignatures(context.packageName, it.packageName) == android.content.pm.PackageManager.SIGNATURE_MATCH)
        }
        require(info.splitSourceDirs.isNullOrEmpty()); val file = File(info.sourceDir); require(file.length() in 1..128L * 1024 * 1024)
        return file.inputStream().use { input -> val sha = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65_536); while(true) { val n = input.read(bytes); if(n < 0)break; sha.update(bytes, 0, n) }; sha.digest().joinToString("") { "%02x".format(it) } }
    }
    private fun snapshot(): JSONObject {
        require(NativeChatNotificationColdRouterFiles.hasReadableMarker(context.noBackupFilesDir))
        val routerBefore = inventory(NativeChatNotificationColdRouterFiles.NAMESPACE)
        val originalsBefore = inventory("P9-native-system-$LEASE")
        // Only after exact regular-file preflight. No Session, Activity or asynchronous writer is created.
        val router = NativeSessionStorage(context, NativeChatNotificationColdRouterFiles.NAMESPACE)
        val rawMarker = checkNotNull(router.read(NativeChatNotificationColdRouterFiles.RECORD)); val marker = JSONObject(rawMarker)
        NativeChatNotificationWire.keys(marker, setOf("version", "original", "phase", "reader"))
        require(marker.getInt("version") == 1 && marker.getString("phase") == "selected")
        val plan = NativeChatNotificationColdPlan.decode(marker.getJSONObject("original"))
        require(plan.lease == LEASE && plan.case == NativeChatNotificationColdCase.ORIGINAL && plan.fingerprint == PLAN && plan.appHash == APP && plan.runnerHash == RUNNER)
        val writer = originalProof(WRITER_FILE, WRITER); val failed = originalProof(FAILURE_FILE, FAILURE)
        require(writer.getString("role") == "writer-armed" && failed.getString("role") == "reader-failed-unresolved")
        val reader = marker.getJSONObject("reader")
        NativeChatNotificationWire.keys(reader, setOf("pid", "nonce", "selectedAt", "completedAt", "savedStatePresent"))
        require(reader.isNull("completedAt") && reader.get("savedStatePresent") is Boolean)
        require(reader.getInt("pid") == failed.getInt("pid") && reader.getString("nonce") == failed.getString("processNonce"))
        require(plan.writerPid == writer.getInt("pid") && plan.writerNonce == writer.getString("processNonce") && failed.getInt("writerPid") == plan.writerPid && failed.getString("writerNonce") == plan.writerNonce)
        require(reader.getInt("pid") != Process.myPid() && reader.getString("nonce") != NativeChatNotificationColdStart.processNonce)
        val processes = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
        require(processes.filter { it.uid == Process.myUid() }.none { it.pid in setOf(reader.getInt("pid"), plan.writerPid) }) { "Original reader/writer must have exited; observer cannot stop them" }
        val details = failed.getJSONObject("details")
        require(details.getInt("defaultSelections") == 0 && details.getInt("activityEntries") == 1 && details.getString("openState") == "opened" && details.getInt("newIntentHandled") == 1)
        require(details.getInt("forbiddenCommands") == 0 && details.getInt("notificationCommands") == 0 && details.getString("selectedThread") == plan.originalThread)
        val expectedCursors = listOf("first") + (2..12).map { "cold-page-$it" }
        require(details.getJSONArray("locatorCursors").toString() == JSONArray(expectedCursors).toString())
        require(details.getString("draftHash") == NativeChatNotificationWire.hash("Owned new draft after original Intent outcome"))
        val storage = NativeSessionStorage(context, "P9-native-system-$LEASE")
        val receipt = NativeChatNotificationColdStart.originalReceipt(storage, plan)
        require(receipt.consumed && receipt.ticket == plan.ticket && receipt.event.target == plan.target && receipt.event.threadId == plan.originalThread && receipt.lease.credentialEpoch == plan.authority)
        require(NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(receipt.copy(consumed = false))) == plan.receiptHash)
        for(name in listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationForegroundStore.RECORD, NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationAuthorizations.RECORD)) require(storage.read(name) == null)
        val metadata = checkNotNull(storage.metadata()); val drafts = metadata.getJSONObject("drafts")
        require(drafts.keys().asSequence().map { drafts.getString(it) }.any { NativeChatNotificationWire.hash(it) == details.getString("draftHash") })
        require(originalsBefore == inventory("P9-native-system-$LEASE") && routerBefore == inventory(NativeChatNotificationColdRouterFiles.NAMESPACE))
        return JSONObject().put("version", 1).put("lease", LEASE).put("planHash", PLAN).put("originalMarkerRaw", rawMarker)
            .put("originalReceipt", NativeChatNotificationCodec.receipt(receipt)).put("originalEncryptedFiles", JSONObject(originalsBefore)).put("routerEncryptedFiles", JSONObject(routerBefore))
            .put("metadataHash", NativeChatRecoveryCanonical.hash(metadata)).put("writerProofSha256", WRITER).put("readerFailureSha256", FAILURE)
            .put("maintenanceAppSha256", apk(false)).put("maintenanceRunnerSha256", apk(true))
    }
    private fun save(name: String, value: JSONObject) {
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile == File(context.filesDir.canonicalFile, "acceptance/P9-notification-cold/$LEASE"))
        android.system.Os.chmod(folder.absolutePath, 448)
        val file = File(folder, name); require(file.createNewFile()) { "Existing maintenance intent preserved; inspect before any retry" }; android.system.Os.chmod(file.absolutePath, 384)
        val bytes = value.toString().toByteArray()
        FileOutputStream(file).use { out -> out.write(bytes); out.fd.sync() }
        // The directory entry must survive before removing the original router record.
        // This also seals the previously fsynced encrypted arm in the same directory.
        val directory = android.system.Os.open(folder.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
        require(file.readBytes().contentEquals(bytes))
    }
    @Test fun inspectOriginalFailureWithoutChangingArmOrOriginals() {
        val snapshot = snapshot(); val digest = NativeChatRecoveryCanonical.hash(snapshot)
        save("maintenance-inspected-${UUID.randomUUID()}.json", JSONObject().put("role", "failure-inspected-arm-retained").put("snapshotSha256", digest).put("snapshot", snapshot)
            .put("result", "Original reader FAILED, unchanged. Parent review required before separate seal/release."))
    }
    @Test fun sealReviewedFailureAndReleaseOnlyItsDebugRouter() {
        val reviewed = checkNotNull(InstrumentationRegistry.getArguments().getString("p9ColdFailureSeal")) { "Parent must review the read-only exported snapshot first" }; require(chatNoticeHash(reviewed))
        val snapshot = snapshot(); require(NativeChatRecoveryCanonical.hash(snapshot) == reviewed) { "Current arm/originals/candidate differ from reviewed snapshot; preserved" }
        // Preserve the exact original encrypted router bytes as well as decoded plan/receipt.
        val arm = File(File(context.noBackupFilesDir, NativeChatNotificationColdRouterFiles.NAMESPACE), NativeChatNotificationWire.hash(NativeChatNotificationColdRouterFiles.RECORD) + ".bin")
        val cipher = arm.readBytes(); require(hash(cipher) == snapshot.getJSONObject("routerEncryptedFiles").getString(arm.name))
        val sealedArm = File(folder, "maintenance-router-$reviewed.bin"); require(sealedArm.createNewFile()) { "Existing seal intent preserved; inspect interrupted release, never replay it" }
        android.system.Os.chmod(sealedArm.absolutePath, 384)
        FileOutputStream(sealedArm).use { out -> out.write(cipher); out.fd.sync() }
        require(hash(sealedArm.readBytes()) == hash(cipher))
        // Durable seal precedes release. An unknown interrupted release cannot be blindly replayed.
        save("maintenance-sealed-$reviewed.json", JSONObject().put("role", "failed-synthetic-lease-sealed").put("snapshotSha256", reviewed).put("snapshot", snapshot)
            .put("result", "Sealed original failure; releasing one debug router slot is maintenance only, never a route/business pass."))
        NativeSessionStorage(context, NativeChatNotificationColdRouterFiles.NAMESPACE).remove(NativeChatNotificationColdRouterFiles.RECORD)
        require(!NativeChatNotificationColdRouterFiles.hasReadableMarker(context.noBackupFilesDir))
        require(NativeChatRecoveryCanonical.value(JSONObject(inventory("P9-native-system-$LEASE"))) == NativeChatRecoveryCanonical.value(snapshot.getJSONObject("originalEncryptedFiles")))
        originalProof(WRITER_FILE, WRITER); originalProof(FAILURE_FILE, FAILURE)
        save("maintenance-released-$reviewed.json", JSONObject().put("role", "failed-lease-router-released").put("snapshotSha256", reviewed)
            .put("originalFailure", "retained; not rerun; no cold-route pass").put("originalEncryptedFiles", snapshot.getJSONObject("originalEncryptedFiles")))
    }
}
