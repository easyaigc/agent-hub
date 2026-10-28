package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android Main/Looper/KeyStore, actual combined coordinator and fixed synchronous callbacks.
 * Component-only: no Activity, OS posting, permission mutation, Host/model or default store. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationWorkerCloseTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun explicitFreshOwner() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Parent worker-close lease and fresh run required; skip is not acceptance", args.getString("p9WorkerCloseLease") != null && args.getString("p9WorkerCloseRun") != null)
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance")
        for(name in listOf("p9WorkerCloseLease", "p9WorkerCloseRun"))check(UUID.fromString(args.getString(name)).toString() == args.getString(name))
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("agent-hub-mobile-v1", null) is javax.crypto.SecretKey)
        check(!NativeChatNotificationColdRouterFiles.hasReadableMarker(context.noBackupFilesDir)) { "Incomplete cold lease preserved; do not mix native queues" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
    }
    @Test fun synchronousEntryReentryFindsRegisteredJobAndCancelsBeforeFirstSync() = runCase("entry-reentry")
    @Test fun synchronousRetirementDuringCancelReturnsWithoutClosedWaitOrReplay() = runCase("sync-return-retire")
    private fun runCase(case: String): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); val run = checkNotNull(args.getString("p9WorkerCloseRun")); val lease = checkNotNull(args.getString("p9WorkerCloseLease"))
        withContext(Dispatchers.IO) { NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, run) }
        val storage = NativeChatNotificationAcceptanceScope.create(context, run).storage(context)
        val fixture = withContext(Dispatchers.IO) { NativeChatNotificationWorkerCloseFixture(storage) }; var failure: Throwable? = null
        try { fixture.run(case) } catch(error: Throwable) { failure = error }
        finally {
            try { withContext(Dispatchers.IO) {
                storage.awaitQueuedWrites(); storage.retireWriter()
                val folder = File(context.filesDir, "acceptance/P9-worker-close/$lease").apply { check(mkdirs() || isDirectory); android.system.Os.chmod(absolutePath, 448) }
                check(folder.canonicalFile == File(context.filesDir.canonicalFile, "acceptance/P9-worker-close/$lease"))
                val file = File(folder, "$run-${UUID.randomUUID()}.json"); check(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 384)
                file.writeText(JSONObject().put("version", 1).put("lease", lease).put("run", run).put("case", case).put("api", android.os.Build.VERSION.SDK_INT)
                    .put("passed", failure == null).put("failureType", failure?.javaClass?.name?.take(128) ?: JSONObject.NULL).put("result", fixture.result)
                    .put("boundary", "Real Android Main/storage; fixed local synthetic transport/system. No Activity/Host/model/OS notification proof; encrypted fixture retained.").toString())
            } } catch(error: Throwable) { if(failure != null)failure!!.addSuppressed(error) else failure = error }
        }
        failure?.let { throw it }; Unit
    }
}
