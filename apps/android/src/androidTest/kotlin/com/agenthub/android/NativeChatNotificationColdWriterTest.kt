package com.agenthub.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Writer process only: no Activity, default Session, OS post or remote operation. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationColdWriterTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    @Test fun armOriginalInFreshOwnedStorageWithoutLaunchingActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
        val lease = args.getString("p9ColdLease"); Assume.assumeTrue("Explicit parent cold lease required; skip is not acceptance", lease != null)
        val case = NativeChatNotificationColdCase.decode(checkNotNull(args.getString("p9ColdCase")))
        val context = instrumentation.targetContext; NativeChatNotificationColdStart.requireHooks(context)
        instrumentation.runOnMainSync {
            assertTrue(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity })
        }
        val plan = runBlocking { withContext(Dispatchers.IO) { NativeChatNotificationColdStart.seedWriter(context, checkNotNull(lease), case) } }
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get()); assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
        val original = NativeChatNotificationPlatform.openIntent(context, plan.ticket)
        assertEquals(plan.ticket, NativeChatNotificationColdStart.strictTicket(context, original))
        for (bad in listOf(Intent(original).putExtra("lease", plan.lease), Intent(original).setComponent(null), Intent(original).setData(android.net.Uri.parse("agenthub-chat-notice://${context.packageName}/wrong"))))
            assertTrue(runCatching { NativeChatNotificationColdStart.strictTicket(context, bad) }.isFailure)
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get())
    }
}
