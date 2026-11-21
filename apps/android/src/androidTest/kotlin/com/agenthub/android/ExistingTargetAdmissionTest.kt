package com.agenthub.android

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Run this exact method only after an independently recorded original-data baseline and lease.
 * No Activity, fixture/reset, persistent output, network, grant refresh or signing operation.
 */
@RunWith(AndroidJUnit4::class)
class ExistingTargetAdmissionTest {
    @Test fun inspectOriginalTargetsWithoutActivityOrWrites() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("readExistingTargets") == "true")
        require(args.getString("nativeForeground") == "0" && args.getString("nativeThreadSnapshots") == "0")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val expected = requireNotNull(args.getString("expectedAppSha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected) { "App fingerprint differs from the admitted candidate" }
        val result = ExistingTargetAdmission.inspect(context).put("appSha256", actual)
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 524_288) { "Admission metadata exceeds the status bound" }
        instrumentation.sendStatus(0, Bundle().apply { putString("existingTargetAdmission", result.toString()) })
        assertEquals("saved-metadata-read", result.getString("status"))
    }

    /** Installation-only Permit input; does not inspect existing targets or open App storage. */
    @Test fun inspectInstallationFingerprintWithoutActivityOrDataAccess() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("readInstallationFingerprint") == "true")
        require(args.getString("nativeForeground") == "0" && args.getString("nativeThreadSnapshots") == "0")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        require(instrumentation.context.packageName == "com.easyai.agenthub.android.acceptance.test")
        require(Process.myUid() == context.applicationInfo.uid)
        val expected = requireNotNull(args.getString("expectedAppSha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected) { "App fingerprint differs from the admitted candidate" }
        val androidId = requireNotNull(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)) {
            "Target-context installation identifier unavailable"
        }
        require(androidId.isNotEmpty()) { "Target-context installation identifier unavailable" }
        val androidIdSha256 = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val configuration = context.resources.configuration
        val result = JSONObject()
            .put("api", Build.VERSION.SDK_INT)
            .put("uid", Process.myUid())
            .put("package", context.packageName)
            .put("appSha256", actual)
            .put("installationAndroidIdSha256", androidIdSha256)
            .put("languageTag", configuration.locales[0].toLanguageTag())
            .put("orientation", configuration.orientation)
            .put("fontScale", configuration.fontScale.toDouble())
        instrumentation.sendStatus(0, Bundle().apply { putString("installationFingerprint", result.toString()) })
    }
}
