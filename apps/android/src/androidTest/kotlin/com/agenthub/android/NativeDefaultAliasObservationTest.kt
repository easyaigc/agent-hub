package com.agenthub.android

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Explicitly scheduled metadata observation only; no installation or business-store initialization. */
@RunWith(AndroidJUnit4::class)
class NativeDefaultAliasObservationTest {
    companion object {
        private const val PACKAGE = "com.easyai.agenthub.android.acceptance"
        private const val ALIAS = "agent-hub-mobile-v1"
        private const val ARGUMENT = "observationRun"
        private const val STATUS_KEY = "p9DefaultAliasObservation"
    }

    private fun present(): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        return store.containsAlias(ALIAS)
    }

    @Test
    fun observesOnlyDefaultAliasPresenceWithoutInitializingStorage() {
        // Refuse before context or Keystore access; no assumption/skip or generated run fallback.
        val run = InstrumentationRegistry.getArguments().getString(ARGUMENT)
        require(run != null && run.length == 36 && UUID.fromString(run).toString() == run) {
            "Exact parent observationRun canonical UUID required"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val packageName = target.packageName
        val appUid = target.applicationInfo.uid
        val pid = Process.myPid()
        val sdk = Build.VERSION.SDK_INT
        require(BuildConfig.DEBUG && packageName == PACKAGE && appUid == Process.myUid() && appUid >= 10000 && pid > 0) {
            "Exact debug acceptance package and target process required"
        }

        // Generated only inside this admitted Native invocation, never by the source preparation.
        val nonce = UUID.randomUUID().toString()
        val before = present()
        val after = present()
        require(target.packageName == packageName && target.applicationInfo.uid == appUid &&
            Process.myUid() == appUid && Process.myPid() == pid && Build.VERSION.SDK_INT == sdk) {
            "Observation process identity changed"
        }
        val consistent = before == after
        val report = JSONObject()
            .put("version", 1)
            .put("kind", "p9-default-alias-presence-v1")
            .put("observationRun", run)
            .put("nonce", nonce)
            .put("packageName", packageName)
            .put("appUid", appUid)
            .put("pid", pid)
            .put("sdk", sdk)
            .put("alias", ALIAS)
            .put("presentBefore", before)
            .put("presentAfter", after)
            .put("consistent", consistent)
        instrumentation.sendStatus(2, Bundle().apply { putString(STATUS_KEY, report.toString()) })
        // Preserve both real observations before a known mismatch fails the method.
        check(consistent) { "Default alias presence changed; preserve this observation, do not replay" }
    }
}
