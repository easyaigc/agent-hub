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
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import javax.crypto.SecretKey

/** Fresh owned empty AVD only. One normal encrypted write in an exact synthetic namespace.
 * No default-family content, key alias enumeration, Activity, notification, permission or Host call.
 * Any old intent is inspection-only, including failed or unknown attempts. */
@RunWith(AndroidJUnit4::class)
class NativeP9SystemUiScopedBootstrapTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(60)
    @Test fun initializeScopedOwnedInstallOnce(): Unit = runBlocking { withContext(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit fresh owned lease required; skipped is not passed", args.getString("p9NaturalOsLease") != null)
        require(args.getString("p9Stage66OwnedInstall") == "fresh-owned-empty-avd")
        val lease = NativeChatNotificationColdPlan.uuid(checkNotNull(args.getString("p9NaturalOsLease")))
        val context = instrumentation.targetContext
        NativeNaturalOsContract.guard(context)
        NativeChatNotificationColdStart.requireHooks(context)
        NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(context)
        instrumentation.runOnMainSync {
            require(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity })
        }
        val own = checkNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses).filter { it.uid == Process.myUid() }
        require(own.size == 1 && own.single().pid == Process.myPid())
        val parent = context.noBackupFilesDir.canonicalFile
        require(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent.toPath()))
        // Counts-only rejects any default-family root before content can be opened.
        val before = NativeNaturalFamilyInventory.scan(parent, false)
        require(before.complete && before.families.isEmpty()) { "Existing default families are inspection-only" }
        var entries = 0
        Files.newDirectoryStream(parent.toPath()).use { paths -> for (path in paths) {
            require(++entries <= NativeNaturalFamilyInventory.ENTRIES)
            require(!path.fileName.toString().startsWith("P9-")) { "Existing P9 material preserved; new owned install required" }
        } }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        require(!keys.containsAlias(NativeChatNotificationNaturalStore.KEY)) { "Exact installation key already exists; never replace/import it" }
        val namespace = "P9-stage66-bootstrap-$lease"
        val evidence = File(context.filesDir.canonicalFile, namespace)
        require(Files.notExists(evidence.toPath(), NOFOLLOW_LINKS))
        Files.createDirectory(evidence.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        NativeNaturalOsContract.sync(context.filesDir.canonicalFile)
        val installed = NativeChatNotificationNaturalStore.installed(context)
        val binding = JSONObject().put("lease", lease).put("uid", installed.uid)
            .put("appSha256", installed.appHash).put("runnerSha256", installed.runnerHash).put("signerSetSha256", installed.signerSetHash)
            .put("pid", Process.myPid()).put("bootCount", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT))
            .put("namespace", namespace).put("defaultFamiliesAbsent", true).put("keyAliasEnumerated", false)
        NativeNaturalOsContract.append(File(evidence, "intent.json"), JSONObject(binding.toString()).put("phase", "one-scoped-product-write-intent"))
        val record = "p9-stage66-scoped-bootstrap-v1"
        val value = JSONObject().put("version", 1).put("lease", lease).put("kind", "owned-scoped-bootstrap").toString()
        val storage = NativeSessionStorage(context, namespace)
        storage.write(record, value) // Sole key-creating operation: existing normal encrypted storage implementation.
        require(storage.read(record) == value && storage.storedBytes(record) in 29..8192)
        require(keys.getKey(NativeChatNotificationNaturalStore.KEY, null) is SecretKey)
        val after = NativeNaturalFamilyInventory.scan(parent, false)
        require(after.complete && after.families.isEmpty() && after.digest == before.digest)
        NativeNaturalOsContract.append(File(evidence, "complete.json"), JSONObject(binding.toString())
            .put("phase", "scoped-bootstrap-complete").put("normalProductWrites", 1).put("defaultFamiliesAbsentAfter", true)
            .put("recordCiphertextBytes", storage.storedBytes(record)).put("keyCreatedByNormalStorage", true))
        Unit
    } }
}
