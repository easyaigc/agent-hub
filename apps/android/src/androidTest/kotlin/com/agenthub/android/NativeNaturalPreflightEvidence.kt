package com.agenthub.android

import android.content.Context
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.util.UUID

/** Outer rule covers @Before and before-body inventory failures. No content/path/alias names in proof. */
internal class NativeNaturalPreflightEvidence(private val suite: String): TestRule {
    @Volatile var stage = "opt-in"
    @Volatile var bodyEntered = false
    @Volatile var normalWriteAttempted = false
    @Volatile var before: NativeNaturalFamilyInventory.Report? = null
    @Volatile var after: NativeNaturalFamilyInventory.Report? = null
    @Volatile var details = JSONObject()
    @Volatile var lease: String? = null
    @Volatile var run: String? = null
    private val nonce = UUID.randomUUID().toString()
    companion object {
        fun keyCounts(): JSONObject {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val count = keys.aliases().asSequence().count()
            return JSONObject().put("aliasCount", count).put("installationAliasPresent", keys.containsAlias(NativeChatNotificationNaturalStore.KEY))
        }
        fun queueCount(context: Context): Int {
            var count = 0; var entries = 0
            Files.newDirectoryStream(context.noBackupFilesDir.toPath()).use { stream -> for(path in stream) {
                require(++entries <= NativeNaturalFamilyInventory.ENTRIES)
                val name = path.fileName.toString()
                if(name.startsWith("P9-notification-cold-") || name == "P9-notification-natural-permit-v1" || name.startsWith("P9-natural-store-fixture-") || name.startsWith("P9-native-system-"))++count
            } }
            return count
        }
        fun installed(context: Context): JSONObject { val value = NativeChatNotificationNaturalStore.installed(context)
            return JSONObject().put("uid", value.uid).put("appSha256", value.appHash).put("runnerSha256", value.runnerHash).put("signerSetSha256", value.signerSetHash) }
        fun sync(directory: File) { val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
            try { require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)); android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) } }
    }
    override fun apply(base: Statement, description: Description) = object: Statement() {
        override fun evaluate() {
            NativeNaturalEvidenceBoundary.evaluate({ base.evaluate() }) { failure ->
                runBlocking { withContext(Dispatchers.IO) { emit(description.methodName, failure) } }
            }
        }
    }
    private fun emit(method: String, failure: Throwable?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        NativeChatNotificationNaturalStore.guard(context)
        if(before != null && after == null && (bodyEntered || normalWriteAttempted))after = NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile, true)
        val counts = before ?: NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile, false)
        val comparable = before?.complete == true && after?.complete == true && before?.contentHashes == true && after?.contentHashes == true
        val value = JSONObject().put("version", 2).put("suite", suite).put("case", method.take(120))
            .put("lease", lease ?: JSONObject.NULL).put("run", run ?: JSONObject.NULL).put("api", Build.VERSION.SDK_INT)
            .put("pid", Process.myPid()).put("processNonce", nonce).put("stage", stage).put("bodyEntered", bodyEntered)
            .put("ownUidProcessCount", context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses?.count { it.uid == Process.myUid() } ?: JSONObject.NULL)
            .put("normalProductWriteAttempted", normalWriteAttempted).put("passed", failure == null)
            .put("status", if(failure == null) "passed" else if(failure is AssumptionViolatedException) "skipped" else "failed")
            .put("failureType", failure?.javaClass?.name ?: JSONObject.NULL).put("familiesBefore", JSONObject(counts.summary()))
            .put("familiesAfter", after?.let { JSONObject(it.summary()) } ?: JSONObject.NULL)
            .put("defaultUnchanged", if(comparable)before!!.digest == after!!.digest else JSONObject.NULL)
            .put("keyCounts", keyCounts()).put("installed", installed(context)).put("details", details)
            .put("boundary", "Test preconditions/component proof only; no OS natural launch. Counts/digests contain no cached content, filenames or aliases.")
        // Custom progress, never AndroidJUnit's result code0; a precondition failure stays zero passed cases.
        instrumentation.sendStatus(2, Bundle().apply { putString("p9NaturalEvidence", value.toString()) })
        val safeLease = lease?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return
        val safeRun = run?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return
        var folder = context.filesDir.canonicalFile
        for(name in listOf("acceptance", if(suite == "store") "P9-natural-store" else "P9-natural-bootstrap", safeLease)) {
            val next = File(folder, name)
            if(Files.notExists(next.toPath(), NOFOLLOW_LINKS))Files.createDirectory(next.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(next.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(next.toPath()) && next.canonicalFile.parentFile == folder)
            folder = next
        }
        val file = File(folder, "$safeRun-${UUID.randomUUID()}.json"); require(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 384)
        java.io.FileOutputStream(file).use { out -> out.write(value.toString().toByteArray()); out.fd.sync() }; sync(folder)
    }
}
