package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Fresh reader process: the first actual MainActivity selects the owned store before default construction. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationColdReaderTest {
    @get:Rule(order = 0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(120)
    @get:Rule(order = 1) val ui = createEmptyComposeRule()
    @get:Rule(order = 2) val failureProof: TestWatcher = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runBlocking { NativeChatNotificationColdStart.recordReaderFailure(InstrumentationRegistry.getInstrumentation().targetContext) }
        }
    }
    private fun lease(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("p9ColdLease"))
    @Before fun explicitLeaseAndHooks() {
        Assume.assumeTrue("Explicit parent cold lease required; skip is not acceptance", InstrumentationRegistry.getArguments().getString("p9ColdLease") != null)
        NativeChatNotificationColdStart.requireHooks(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @Test fun firstActivityRoutesOnlyTheOriginalAndDuplicateIntentKeepsNewDraft() {
        val i = InstrumentationRegistry.getInstrumentation(); val context = i.targetContext
        i.runOnMainSync { assertTrue(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
        val plan = runBlocking { withContext(Dispatchers.IO) { NativeChatNotificationColdStart.prepareReader(context, lease()) } }
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get()); assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
        val intent = NativeChatNotificationPlatform.openIntent(context, plan.ticket)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            ui.waitUntil(20_000) { NativeChatNotificationColdStart.active?.coordinator?.openState in setOf("opened", "unavailable") }
            val binding = checkNotNull(NativeChatNotificationColdStart.active); val coordinator = checkNotNull(binding.coordinator)
            scenario.onActivity { activity ->
                assertSame(binding.session, activity.session); assertFalse(activity.session.restoring); assertFalse(activity.session.localRecoveryRequired)
                assertEquals(1, NativeChatNotificationColdStart.activityEntries.get()); assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
                assertEquals(plan.ticket, NativeChatNotificationColdStart.strictTicket(context, activity.intent))
                assertTrue(sameScope(activity.session.selectedClient.scope, binding.ports.scope)); assertTrue(activity.session.operationRecords.isEmpty())
                if(plan.case == NativeChatNotificationColdCase.ORIGINAL) {
                    assertEquals("opened", coordinator.openState); assertEquals(plan.originalThread, activity.session.selectedThread); assertEquals(plan.originalDraft, activity.session.text)
                    val page = checkNotNull(binding.ports.lastLocatorPage)
                    assertEquals(plan.target, NativeChatNoticeTarget.from(checkNotNull(page.scope)))
                    assertEquals(plan.originalThread, page.threads.single().id); assertEquals(12, binding.ports.locatorCursors.size)

                } else {
                    assertEquals("unavailable", coordinator.openState); assertEquals(plan.otherThread, activity.session.selectedThread); assertEquals(plan.otherDraft, activity.session.text)
                    assertEquals(if(plan.case == NativeChatNotificationColdCase.MISSING_THREAD) 12 else 0, binding.ports.locatorCursors.size)
                }
            }
            ui.onNodeWithTag("product-surface").assertIsDisplayed()
            ui.onNodeWithTag("message-input").assertTextContains(if(plan.case == NativeChatNotificationColdCase.ORIGINAL) plan.originalDraft else plan.otherDraft)
            scenario.onActivity { activity ->
                activity.session.text = "Owned new draft after original Intent outcome"
                activity.startActivity(NativeChatNotificationPlatform.openIntent(activity, plan.ticket))
            }
            ui.waitUntil(10_000) { binding.newIntentHandled.get() == 1 }
            scenario.onActivity {
                assertEquals(if(plan.case == NativeChatNotificationColdCase.ORIGINAL) plan.originalThread else plan.otherThread, it.session.selectedThread)
                assertEquals("Owned new draft after original Intent outcome", it.session.text)
                assertEquals(if(plan.case in setOf(NativeChatNotificationColdCase.ORIGINAL, NativeChatNotificationColdCase.MISSING_THREAD)) 12 else 0, binding.ports.locatorCursors.size)
                assertEquals(1, NativeChatNotificationColdStart.activityEntries.get())
                assertTrue(it.session.operationRecords.isEmpty())
            }
            ui.onNodeWithTag("message-input").assertTextContains("Owned new draft after original Intent outcome")
            if(plan.case == NativeChatNotificationColdCase.ORIGINAL) {
                // Notification location adopts only its exact page-twelve response. A standalone
                // reader response is not Session authority for another row: refresh the real catalog.
                scenario.onActivity { it.session.refresh(userInitiated = true) }
                ui.waitUntil(10_000) {
                    var published = false
                    scenario.onActivity { published = !it.session.loading && it.session.threads.any { row -> row.id == plan.otherThread && row.hostId == plan.target.hostId } }
                    published
                }
                scenario.onActivity {
                    assertEquals(plan.originalThread, it.session.selectedThread); assertEquals("Owned new draft after original Intent outcome", it.session.text)
                    assertEquals(12, binding.ports.locatorCursors.size); assertEquals(1, NativeChatNotificationColdStart.activityEntries.get())
                    val other = it.session.threads.single { row -> row.id == plan.otherThread && row.hostId == plan.target.hostId }
                    coordinator.navigationWillChange(); it.session.selectThread(other)
                    assertEquals(plan.otherDraft, it.session.text)
                }
                ui.onNodeWithTag("message-input").assertTextContains(plan.otherDraft)
            }
            runBlocking { withContext(Dispatchers.IO) {
                val receipt = NativeChatNotificationColdStart.originalReceipt(binding.storage, plan)
                assertEquals(plan.case == NativeChatNotificationColdCase.ORIGINAL, receipt.consumed)
                assertEquals(plan.receiptHash, NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(receipt.copy(consumed = false))))
                assertEquals(plan.authority, receipt.lease.credentialEpoch)
                for(record in listOf(NativeChatNotificationIdentity.RECORD, NativeChatNotificationForegroundStore.RECORD, NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationAuthorizations.RECORD))assertNull(binding.storage.read(record))
            } }
            assertEquals(0, binding.ports.forbiddenCommands.get()); assertEquals(0, binding.ports.notificationCommands.get())
            runBlocking { withContext(Dispatchers.IO) { NativeChatNotificationColdStart.completeReader(context, binding) } }
        }
    }
    /** Maintenance observer role; never counted as a cold route result. Parent stops the reader first. */
    @Test fun disarmCompletedLeaseFromAnotherProcessWithoutOpeningDefaultApp() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { assertTrue(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity }) }
        runBlocking { withContext(Dispatchers.IO) { NativeChatNotificationColdStart.disarmCompleted(i.targetContext, lease()) } }
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get()); assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
    }
}
