package com.agenthub.android

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.util.UUID

/** Explicit fresh-install preparation. Exactly one normal product storage write may create its key.
 * Permit factories never generate a key. No Activity/Host/network/notification/permission/input. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationNaturalBootstrapTest {
    @get:Rule(order = 0) internal val evidence = NativeNaturalPreflightEvidence("bootstrap")
    @get:Rule(order = 1) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val id get() = checkNotNull(evidence.lease)
    private val root get() = File(context.noBackupFilesDir.canonicalFile, "P9-natural-bootstrap-v1")
    private val record = "p9-native-local-bootstrap-v1"
    @Before fun explicitOwnedInstall() {
        Assume.assumeTrue("Explicit bootstrap UUID/action required; skip is not initialization", args.getString("p9NaturalBootstrapId") != null && args.getString("p9NaturalBootstrapAction") != null)
        evidence.stage = "ids"; val value = checkNotNull(args.getString("p9NaturalBootstrapId")); require(UUID.fromString(value).toString() == value)
        evidence.lease = value; evidence.run = value
        evidence.stage = "scope"; NativeChatNotificationNaturalStore.guard(context)
        evidence.stage = "no-activity"
        InstrumentationRegistry.getInstrumentation().runOnMainSync { check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
    }
    private fun scan() = NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile, true)
    private fun absentKey() { val keys = NativeNaturalPreflightEvidence.keyCounts(); require(keys.getInt("aliasCount") == 0 && !keys.getBoolean("installationAliasPresent")) { "Existing installation key material is inspection-only" } }
    private fun fresh(report: NativeNaturalFamilyInventory.Report) {
        require(report.emptyRootsOnly) { "All mobile-v1 families must be completely empty" }; absentKey()
        require(NativeNaturalPreflightEvidence.queueCount(context) == 0) { "Existing cold/natural material preserved" }
        val processes = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses).filter { it.uid == Process.myUid() }
        require(processes.size == 1 && processes.single().pid == Process.myPid()) { "Bootstrap requires its sole installation process" }
    }
    @Test fun initializeEmptyDefaultThroughProductWriteOnce(): Unit = runBlocking { withContext(Dispatchers.IO) {
        evidence.stage = "explicit-initialize-action"; require(args.getString("p9NaturalBootstrapAction") == "initialize-empty-default")
        evidence.stage = "bootstrap-fence-absence"; require(Files.notExists(root.toPath(), NOFOLLOW_LINKS)) { "Bootstrap already attempted; no repeated write" }
        evidence.stage = "empty-family-preflight"; val before = scan(); evidence.before = before; fresh(before)
        val installed = NativeNaturalPreflightEvidence.installed(context)
        val intent = JSONObject().put("version", 1).put("bootstrapId", id).put("phase", "normal-write-intent")
            .put("pid", Process.myPid()).put("nonce", UUID.randomUUID().toString()).put("installed", installed)
            .put("familiesBefore", JSONObject(before.summary())).put("keyCountsBefore", NativeNaturalPreflightEvidence.keyCounts())
            .put("coldNaturalQueueRoots", 0).put("normalProductWritesPlanned", 1).put("scope", "Parent-owned empty acceptance install; ordinary local cache write only")
            .toString().toByteArray()
        evidence.stage = "durable-write-intent"
        NativeNaturalBootstrapFence(root, NativeNaturalPreflightEvidence::sync).initialize(intent) {
            evidence.stage = "recheck-empty-before-normal-write"; val checked = scan(); require(checked.digest == before.digest); fresh(checked)
            evidence.bodyEntered = true
            evidence.stage = "normal-storage-construction"; val storage = NativeSessionStorage(context)
            val local = JSONObject().put("version", 1).put("purpose", "p9-native-key-bootstrap").put("bootstrapId", id).toString()
            evidence.stage = "normal-product-write"; evidence.normalWriteAttempted = true
            storage.write(record, local) // The ONLY authorized product write and key-creation path.
            evidence.stage = "normal-product-readback"; require(storage.read(record) == local)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            require(keys.getKey(NativeChatNotificationNaturalStore.KEY, null) is javax.crypto.SecretKey)
            require(keys.aliases().asSequence().count() == 1)
            evidence.stage = "complete-family-after-write"; val after = scan(); evidence.after = after
            val filename = NativeChatNotificationNaturalFiles.sha(record.toByteArray()) + ".bin"
            require(after.complete && after.families == before.families + "mobile-v1" && after.directories == after.families.size)
            require(after.filePaths == setOf("mobile-v1/$filename") && after.files == 1 && after.bytes in 29..8192)
            require(NativeNaturalPreflightEvidence.queueCount(context) == 0)
            JSONObject().put("version", 1).put("bootstrapId", id).put("phase", "complete")
                .put("intentSha256", NativeChatNotificationNaturalFiles.sha(intent)).put("pid", Process.myPid()).put("installed", installed)
                .put("familiesBefore", JSONObject(before.summary())).put("familiesAfter", JSONObject(after.summary()))
                .put("keyCountsAfter", NativeNaturalPreflightEvidence.keyCounts()).put("normalProductWrites", 1)
                .put("localRecordNameSha256", NativeChatNotificationNaturalFiles.sha(record.toByteArray()))
                .put("boundary", "One synchronous NativeSessionStorage write/read; no Session/metadata/client/token/command/post/permission; original retained")
                .toString().toByteArray()
        }
        evidence.stage = "bootstrap-complete"; evidence.details = JSONObject().put("normalProductWrites", 1).put("keyCreatedBy", "normal NativeSessionStorage write")
        Unit
    } }
    @Test fun verifyOriginalBootstrapReadOnly(): Unit = runBlocking { withContext(Dispatchers.IO) {
        evidence.stage = "explicit-verify-action"; require(args.getString("p9NaturalBootstrapAction") == "verify-original")
        evidence.bodyEntered = true
        evidence.stage = "read-original-receipts"; val (intentBytes, completedBytes) = NativeNaturalBootstrapFence(root, NativeNaturalPreflightEvidence::sync).readCompleted()
        val intent = JSONObject(intentBytes.toString(Charsets.UTF_8)); val completed = JSONObject(completedBytes.toString(Charsets.UTF_8))
        require(intent.getInt("version") == 1 && completed.getInt("version") == 1 && intent.getString("bootstrapId") == id && completed.getString("bootstrapId") == id)
        require(intent.getString("phase") == "normal-write-intent" && completed.getString("phase") == "complete")
        require(completed.getString("intentSha256") == NativeChatNotificationNaturalFiles.sha(intentBytes) && completed.getInt("normalProductWrites") == 1)
        val actual = NativeNaturalPreflightEvidence.installed(context)
        for(key in listOf("uid", "appSha256", "runnerSha256", "signerSetSha256"))require(actual.get(key) == completed.getJSONObject("installed").get(key) && actual.get(key) == intent.getJSONObject("installed").get(key))
        evidence.stage = "read-only-key-check"; val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(keys.getKey(NativeChatNotificationNaturalStore.KEY, null) is javax.crypto.SecretKey && keys.aliases().asSequence().count() == 1)
        evidence.stage = "unchanged-bootstrap-families"; val report = scan(); evidence.before = report; evidence.after = report
        require(report.complete && report.digest == completed.getJSONObject("familiesAfter").getString("familyDigest"))
        evidence.stage = "bootstrap-verified-read-only"; evidence.details = JSONObject().put("normalProductWrites", 0).put("originalReceiptSha256", NativeChatNotificationNaturalFiles.sha(completedBytes))
        Unit // No NativeSessionStorage constructor/read/write is used by verification.
    } }
}
