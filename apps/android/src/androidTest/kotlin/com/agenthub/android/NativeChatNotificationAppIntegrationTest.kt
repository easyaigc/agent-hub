package com.agenthub.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Real Activity dispatcher, retained Session and actual settings; default-off and invalid-ticket paths only. */
class NativeChatNotificationAppIntegrationTest {
    @get:Rule(order=0)val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2)val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private var previous: MainActivity.AcceptanceSessionState?=null
    private val names=setOf(NativeChatNotificationStore.RECORD,NativeChatNotificationIdentity.RECORD,
        NativeChatNotificationForegroundStore.RECORD,NativeChatNotificationRecoveryStore.RECORD,NativeChatNotificationAuthorizations.RECORD)
    private var original=emptyMap<String,String>()
    private val draft="Owned original draft 中文 😀 100% %@"
    private fun resumedActivity(excluding:MainActivity?=null):MainActivity {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var found:MainActivity?=null
        ui.waitUntil(20_000){
            instrumentation.runOnMainSync {
                found=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull {
                        it!==excluding&&it.packageName==instrumentation.targetContext.packageName
                    }
            }
            found!=null
        }
        return found!!
    }
    @Before fun setup() {
        activity=resumedActivity()
        // The foreground rule may activate an existing Activity before the empty Compose rule.
        // Give this rule a newly created real root while preserving its retained Session.
        val activated=activity
        ui.runOnIdle{activated.recreate()}
        activity=resumedActivity(excluding=activated)
        assertTrue(activated.isDestroyed)
        ui.waitUntil(30_000){!activity.session.restoring}
        original=NativeSessionStorage(activity).migrationReadTextRecords(names)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(activity,"chat-notification-app");owned.session.resetAcceptance();owned.session.text=draft
            previous=activity.adoptSessionForAcceptance(owned.session)
        }
        ui.waitUntil(15_000){owned.session.chatNotifications!=null}
    }
    @After fun finish() {
        if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault();assertEquals(draft,owned.session.text);assertTrue(owned.session.operationRecords.isEmpty())}
        previous?.let {old->ui.runOnIdle{activity.restoreSessionForAcceptance(old)}}
        assertEquals(original,NativeSessionStorage(activity).migrationReadTextRecords(names))
    }
    private fun untouched() {
        assertTrue(owned.storage.migrationReadTextRecords(names).isEmpty())
        assertEquals(draft,owned.session.text);assertTrue(owned.session.operationRecords.isEmpty())
    }
    @Test fun actualSettingsEntryKeepsDefaultOffWithoutMakingIdentityJournalOrAuthority() {
        ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithTag("settings-chat-notifications").performScrollTo().performClick()
        ui.waitUntil(15_000){ui.onAllNodesWithTag("chat-notifications-enabled").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("chat-notifications-enabled").assertIsOff()
        untouched()
    }
    @Test fun actualWarmDispatcherConsumesInvalidNotificationBeforeAnyPairingOrAttachmentRoute() {
        val malformed=Intent(activity,MainActivity::class.java).setAction(NativeChatNotificationPlatform.ACTION)
            .setData(Uri.parse("agenthub://pairing/synthetic-invalid-notification"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ui.runOnIdle{activity.startActivity(malformed)}
        ui.waitUntil(15_000){owned.session.chatNotifications?.openState=="unavailable"}
        assertEquals(NativeChatNotificationPlatform.ACTION,activity.intent.action)
        ui.onNodeWithTag("chat-notification-open-status").assertIsDisplayed()
        assertNull(owned.session.incomingEasyWorkLink);assertNull(owned.session.error);untouched()
    }
    @Test fun actualUnknownOpaqueTicketAndActivityRecreationKeepTheSameCoordinatorAndDraft() {
        val ticket=UUID.randomUUID().toString();val coordinator=owned.session.chatNotifications
        ui.runOnIdle{activity.startActivity(NativeChatNotificationPlatform.openIntent(activity,ticket))}
        ui.waitUntil(15_000){coordinator?.openState=="unavailable"}
        assertEquals(ticket,activity.intent.getStringExtra(NativeChatNotificationPlatform.TICKET));untouched()
        // ActivityScenario 1.7 matches lifecycle events against its original launch action/data.
        // onNewIntent intentionally changes both, so observe the real resumed replacement instead.
        val before=activity
        ui.runOnIdle{before.recreate()}
        activity=resumedActivity(excluding=before)
        assertTrue(before.isDestroyed)
        assertEquals(ticket,activity.intent.getStringExtra(NativeChatNotificationPlatform.TICKET))
        ui.waitUntil(20_000){activity.session===owned.session&&owned.session.chatNotifications?.openState=="unavailable"}
        assertSame(coordinator,owned.session.chatNotifications);assertFalse(coordinator!!.opening);untouched()
    }
}
