package com.agenthub.android

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Proposal only. One blank framework Activity; no product Session or Clipboard payload API. */
@RunWith(AndroidJUnit4::class)
class ClipboardMetadataReadOnlyTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val packageName = "com.easyai.agenthub.android.acceptance"
    private val appSha = "06d34c3735e0f561dc2c8d82b0e2075dc5a3a30db1a0a04b03172d6abad80b99"
    private val signerSha = "fc12e68fdbe401e9dbea6c0ae9bb45e59f9d59517f9f19ac01663c04f737adf1"
    private val method = "com.agenthub.android.ClipboardMetadataReadOnlyTest#inspectCurrentClipboardMetadataWithoutReadingOrClearing"
    private val hex = Regex("[0-9a-f]{64}")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun <T> main(block: () -> T): T {
        var answer: T? = null
        instrument.runOnMainSync { answer = block() }
        @Suppress("UNCHECKED_CAST") return answer as T
    }
    private fun readPrivate(file: File, expectedSha: String): JSONObject {
        check(hex.matches(expectedSha)) { "INVALID_SOURCE_SHA" }
        check(file.absoluteFile == file.canonicalFile) { "NON_CANONICAL_INPUT" }
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val bytes = FileInputStream(fd).use { stream ->
            val stat = Os.fstat(fd)
            check(stat.st_uid == 10249 && stat.st_nlink == 1L &&
                OsConstants.S_ISREG(stat.st_mode) && stat.st_mode and 511 == 384 &&
                stat.st_size in 1L..65536L) { "PRIVATE_INPUT_METADATA" }
            stream.readBytes().also { check(it.size.toLong() == stat.st_size) }
        }
        check(sha(bytes) == expectedSha) { "SOURCE_SHA_MISMATCH" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun publish(file: File, value: JSONObject) {
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        FileOutputStream(fd).use { stream -> stream.write(value.toString().toByteArray()); stream.fd.sync() }
    }
    private fun installation(plan: JSONObject) {
        check(context.packageName == packageName && Process.myUid() == 10249 && Build.VERSION.SDK_INT == 33)
        check(instrument.context.packageName == "$packageName.test" && instrument.context.applicationInfo.uid == 10257)
        check(context.applicationContext.javaClass.name == "android.app.Application") { "CUSTOM_APPLICATION" }
        check(hashFile(File(context.applicationInfo.sourceDir)) == appSha) { "APP_CHANGED" }
        val expectedRunner = plan.getString("runnerSha256")
        check(hex.matches(expectedRunner) && hashFile(File(instrument.context.applicationInfo.sourceDir)) == expectedRunner) { "RUNNER_CHANGED" }
        for (pkg in listOf(packageName, "$packageName.test")) {
            val signatures = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners ?: error("SIGNER_UNAVAILABLE")
            check(signatures.size == 1 && sha(signatures.single().toByteArray()) == signerSha) { "SIGNER_CHANGED" }
        }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        check(androidId != null && sha(androidId.toByteArray()) == plan.getString("androidIdSha256")) { "INSTALLATION_ID_CHANGED" }
    }

    @Test
    fun inspectCurrentClipboardMetadataWithoutReadingOrClearing() {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 20_000L
        fun alive() { check(SystemClock.elapsedRealtime() <= deadline) { "MONOTONIC_DEADLINE" } }
        val args = InstrumentationRegistry.getArguments()
        val run = checkNotNull(args.getString("metadataRun"))
        check(UUID.fromString(run).toString() == run)
        val leaseId = checkNotNull(args.getString("metadataLeaseId"))
        check(UUID.fromString(leaseId).toString() == leaseId)
        val folder = File(context.filesDir, "acceptance/clipboard-metadata-observer/$run")
        check(folder.absoluteFile == folder.canonicalFile && folder.isDirectory)
        val planSha = checkNotNull(args.getString("metadataPlanSha256"))
        val leaseSha = checkNotNull(args.getString("metadataLeaseSha256"))
        val plan = readPrivate(File(folder, "plan.json"), planSha)
        val lease = readPrivate(File(folder, "root-lease.json"), leaseSha)
        check(plan.getInt("version") == 1 && plan.getString("kind") == "clipboard-metadata-plan-v1")
        check(lease.getInt("version") == 1 && lease.getString("kind") == "root-clipboard-metadata-observer-v1")
        for (value in listOf(plan, lease)) {
            check(value.getString("taskId") == "android-parallel-20261112-01a1cd5e")
            check(value.getString("run") == run && value.getString("leaseId") == leaseId)
            check(value.getString("serial") == "d3c0b38d" && value.getInt("uid") == 10249 && value.getInt("runnerUid") == 10257)
            check(value.getString("allowedMethod") == method)
            check(value.getString("activityClass") == ComponentActivity::class.java.name)
            check(value.getBoolean("executionAuthorized") && value.getBoolean("deviceLeaseActive") &&
                value.getBoolean("instrumentationAuthorized") && value.getBoolean("activityLaunchAuthorized") &&
                value.getBoolean("clipboardMetadataReadAuthorized"))
            for (flag in listOf("clipboardContentReadAuthorized", "clipboardWriteAuthorized", "businessNativeAuthorized",
                "modelInputAuthorized", "profileGrantAuthorized", "installAuthorized")) check(!value.getBoolean(flag))
        }
        check(UUID.fromString(lease.getString("rootOperationId")).toString() == lease.getString("rootOperationId"))
        check(lease.getString("ownerThreadId") == "01a1cda2-1dac-7623-944e-e21e2b252261")
        check(lease.getString("rootThreadId") == "01a1cd5e-b7d5-7721-8588-0d5552dae8af")
        check(lease.getString("nativePlanSha256") == planSha)
        check(lease.getString("runnerSha256") == plan.getString("runnerSha256"))
        installation(plan)
        // Root consumer checks its UTC lease immediately before the one launch, with >=90s left
        // and a 45s host process budget. Native uses only this 20s monotonic lifetime, never PhoneUTC-HostUTC.
        main {
            check(Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.isEmpty()) { "EXISTING_ACTIVITY_OWNER" }
        }
        publish(File(folder, "method-started.json"), JSONObject().put("run", run).put("leaseId", leaseId)
            .put("planSha256", planSha).put("deviceElapsedStartedMs", start))
        val result = JSONObject().put("version", 1).put("run", run).put("leaseId", leaseId)
            .put("planSha256", planSha).put("rootLeaseSha256", leaseSha).put("classification", "unknown")
            .put("scope", "current-foreground-clipboard-metadata-only")
            .put("clipboardContentRead", false).put("clipboardWrite", false)
            .put("clipboardRestorationVerified", JSONObject.NULL).put("businessAcceptance", false)
        val guards = JSONArray()
        val callbacks = AtomicInteger(0)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { callbacks.incrementAndGet() }
        var scenario: ActivityScenario<ComponentActivity>? = null
        var clipboard: ClipboardManager? = null
        var listenerAdded = false
        var failure: Throwable? = null
        try {
            alive()
            val opened = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java))
            scenario = opened
            var own: ComponentActivity? = null
            opened.onActivity { check(it.javaClass == ComponentActivity::class.java); own = it }
            val activity = checkNotNull(own)
            fun eligibility(stage: String): JSONObject {
                alive()
                val local = main {
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
                    val keyguard = activity.getSystemService(KeyguardManager::class.java)
                    val ops = activity.getSystemService(AppOpsManager::class.java)
                    @Suppress("DEPRECATION")
                    val raw = ops.unsafeCheckOpRawNoThrow("android:read_clipboard", Process.myUid(), packageName)
                    @Suppress("DEPRECATION")
                    val evaluated = ops.unsafeCheckOpNoThrow("android:read_clipboard", Process.myUid(), packageName)
                    check(resumed.size == 1 && resumed.single() === activity && activity.hasWindowFocus() &&
                        activity.window.decorView.isShown && activity.window.decorView.windowToken != null && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
                        process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) { "FOREGROUND_FOCUS_UNVERIFIED" }
                    check(raw in listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_FOREGROUND) &&
                        evaluated == AppOpsManager.MODE_ALLOWED) { "READ_CLIPBOARD_APP_OP_UNVERIFIED" }
                    JSONObject().put("stage", stage).put("deviceElapsedMs", SystemClock.elapsedRealtime())
                        .put("uniqueResumed", true).put("hasWindowFocus", true).put("processImportance", process.importance)
                        .put("keyguardLocked", false).put("rawReadClipboardMode", raw).put("evaluatedReadClipboardMode", evaluated)
                        .put("decorWindowTokenPresent", true)
                }
                val root = instrument.uiAutomation.rootInActiveWindow ?: error("ACTIVE_ROOT_UNAVAILABLE")
                try {
                    val window = root.window ?: error("ACTIVE_WINDOW_UNAVAILABLE")
                    try {
                        check(root.packageName?.toString() == packageName &&
                            root.windowId == window.id && window.isActive && window.isFocused &&
                            window.type == AccessibilityWindowInfo.TYPE_APPLICATION) { "OWN_ACTIVE_WINDOW_UNVERIFIED" }
                        main {
                            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            check(resumed.size == 1 && resumed.single() === activity && activity.hasWindowFocus() &&
                                activity.window.decorView.windowToken != null) { "FOCUS_CHANGED_DURING_WINDOW_PROBE" }
                        }
                        return local.put("activeWindowId", root.windowId).put("ownApplicationActiveWindow", true)
                    } finally { window.recycle() }
                } finally { root.recycle() }
            }
            val readyDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 8_000)
            while (true) {
                try { guards.put(eligibility("ready")); break }
                catch (e: IllegalStateException) {
                    if (SystemClock.elapsedRealtime() >= readyDeadline) throw e
                    Thread.sleep(50)
                }
            }
            val observedClipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard = observedClipboard
            main { observedClipboard.addPrimaryClipChangedListener(listener) }; listenerAdded = true
            fun sample(stage: String): JSONObject {
                guards.put(eligibility("$stage-before-has"))
                val present = main { observedClipboard.hasPrimaryClip() }
                guards.put(eligibility("$stage-before-description"))
                val description = main { observedClipboard.primaryClipDescription }
                guards.put(eligibility("$stage-after-description"))
                // No label, extras, item, text, URI or ClipData object is read or logged.
                val metadata = description?.let {
                    JSONObject().put("timestamp", it.timestamp).put("mimeTypeCount", it.mimeTypeCount)
                        .put("mimeTypesSha256", sha((0 until it.mimeTypeCount).map(it::getMimeType)
                            .sorted().joinToString("\u0000").toByteArray()))
                }
                return JSONObject().put("hasPrimaryClip", present).put("descriptionAvailable", description != null)
                    .put("descriptionMetadata", metadata ?: JSONObject.NULL)
            }
            val first = sample("first")
            Thread.sleep(200)
            val second = sample("second")
            result.put("first", first).put("second", second)
            check(callbacks.get() == 0 && first.toString() == second.toString()) { "METADATA_CHANGED_DURING_OBSERVATION" }
            check(!second.getBoolean("hasPrimaryClip") || second.getBoolean("descriptionAvailable")) { "PRESENT_DESCRIPTION_UNAVAILABLE" }
            check(second.getBoolean("hasPrimaryClip") || !second.getBoolean("descriptionAvailable")) { "INCONSISTENT_DESCRIPTION" }
            // Null description is never the empty witness: only two qualified has=false reads qualify.
            guards.put(eligibility("before-listener-close"))
            result.put("classification", if (second.getBoolean("hasPrimaryClip")) "present" else "empty")
        } catch (e: Throwable) {
            failure = e
            result.put("classification", "unknown").put("failureType", e.javaClass.name)
        } finally {
            result.put("guards", guards).put("callbackCount", callbacks.get())
            if (listenerAdded) {
                try { main { clipboard!!.removePrimaryClipChangedListener(listener) }; result.put("listenerRemoved", true) }
                catch (e: Throwable) { result.put("listenerRemoved", false); if (failure == null) failure = e }
            } else result.put("listenerRemoved", JSONObject.NULL)
            if (scenario != null) {
                try {
                    scenario.close()
                    check(main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).none { it.javaClass == ComponentActivity::class.java } })
                    result.put("scenarioClosed", true)
                } catch (e: Throwable) { result.put("scenarioClosed", false); if (failure == null) failure = e }
            } else result.put("scenarioClosed", JSONObject.NULL)
            if (callbacks.get() != 0 && failure == null) failure = IllegalStateException("METADATA_CHANGED_DURING_OBSERVATION")
            if (SystemClock.elapsedRealtime() > deadline && failure == null) failure = IllegalStateException("MONOTONIC_DEADLINE")
            if (failure != null) result.put("classification", "unknown")
            result.put("callbackCount", callbacks.get()).put("deviceElapsedCompletedMs", SystemClock.elapsedRealtime())
            publish(File(folder, "metadata-result.json"), result)
        }
        if (failure != null) throw AssertionError("Clipboard metadata remains unknown; no Clipboard write attempted", failure)
    }
}
