package com.agenthub.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey

/** Only debug acceptance factories. No caller from Main/Session/cold router and no generated/imported key. */
internal class NativeChatNotificationNaturalStore private constructor(private val files: NativeChatNotificationNaturalFiles, private val fixture: Boolean) {
    data class Installed(val uid: Int, val appHash: String, val runnerHash: String, val signerSetHash: String)
    companion object {
        const val KEY = "agent-hub-mobile-v1"
        private const val ROOT = "P9-notification-natural-permit-v1"
        private const val FIXTURE = "P9-natural-store-fixture-"
        private fun requireIo() { check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Natural permit IO must precede Main selection asynchronously" } }
        internal fun guard(context: Context) { require(BuildConfig.DEBUG && context.packageName == NativeChatNotificationNaturalPermit.PACKAGE && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) }
        private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
        private fun source(alias: String): SecretKey = checkNotNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey) { "Existing device key unavailable; no key is created" }
        @Suppress("DEPRECATION")
        fun installed(context: Context): Installed {
            guard(context); requireIo(); val pm = context.packageManager; val app = context.packageName; val runner = "$app.test"
            fun signatures(name: String): List<String> {
                val info = pm.getPackageInfo(name, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
                val values = if (Build.VERSION.SDK_INT >= 28) checkNotNull(info.signingInfo).apkContentsSigners else checkNotNull(info.signatures)
                require(values.isNotEmpty()); return values.map { NativeChatNotificationNaturalFiles.sha(it.toByteArray()) }.sorted()
            }
            val certificates = signatures(app); require(certificates == signatures(runner) && pm.checkSignatures(app, runner) == PackageManager.SIGNATURE_MATCH)
            fun apk(name: String): String {
                val info = pm.getApplicationInfo(name, 0); require(info.splitSourceDirs.isNullOrEmpty())
                val file = File(info.sourceDir); require(file.isFile && file.length() in 1..128L * 1024 * 1024)
                return file.inputStream().use { input -> val hash = java.security.MessageDigest.getInstance("SHA-256"); val b = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(b); if (n < 0) break; total += n; require(total <= 128L * 1024 * 1024); hash.update(b, 0, n) }; hash.digest().joinToString("") { "%02x".format(it) } }
            }
            require(context.applicationInfo.uid == Process.myUid())
            return Installed(Process.myUid(), apk(app), apk(runner), NativeChatNotificationNaturalFiles.sha(certificates.joinToString("\n").toByteArray()))
        }
        private fun create(context: Context, lease: String, fixture: Boolean, missing: Boolean, fault: NativeNaturalFault?): NativeChatNotificationNaturalStore {
            guard(context); requireIo(); uuid(lease)
            val parent = context.noBackupFilesDir.canonicalFile
            val name = if (fixture) FIXTURE + lease else ROOT
            val alias = if (missing) "p9-natural-absent-$lease" else KEY
            require(!missing || fixture)
            val engine = NativeChatNotificationNaturalFiles(File(parent, name), lease, { source(alias) }, { directory ->
                val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                try { require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
            }, { original ->
                val actual = installed(context)
                require(original.uid == actual.uid && original.appHash == actual.appHash && original.runnerHash == actual.runnerHash && original.signerSetHash == actual.signerSetHash)
            }, fault)
            return NativeChatNotificationNaturalStore(engine, fixture)
        }
        fun forAcceptance(context: Context, lease: String) = create(context, lease, false, false, null)
        fun forNativeFixture(context: Context, run: String, fault: NativeNaturalFault? = null) = create(context, run, true, false, fault)
        fun missingKeyNativeFixture(context: Context, run: String) = create(context, run, true, true, null)
        fun nativeFixtureRoot(context: Context, run: String): File { guard(context); return File(context.noBackupFilesDir.canonicalFile, FIXTURE + uuid(run)) }
    }
    private fun io() = requireIo()
    fun arm(original: NativeChatNotificationNaturalPermit.Original, challenge: ByteArray): NativeChatNotificationNaturalPermit.State { io(); return files.arm(original, challenge) }
    fun inspect(): NativeChatNotificationNaturalPermit.State { io(); return files.inspect() }
    fun claim(observed: NativeChatNotificationNaturalPermit.Observation): NativeChatNotificationNaturalFiles.CommittedClaim { io(); return files.claim(observed) }
    fun complete(result: NativeChatNotificationNaturalPermit.Completion): NativeChatNotificationNaturalPermit.State { io(); return files.complete(result) }
    internal fun holdNativeFixtureLock(): Closeable { io(); require(fixture); return files.holdFixtureLock() }
}
