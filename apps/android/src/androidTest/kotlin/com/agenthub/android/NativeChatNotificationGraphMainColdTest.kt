package com.agenthub.android

import android.content.Intent
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Explicit, separate writer/reader invocations. The reader launches the first real Main
 * in an instrumented process; neither OS process creation nor a SystemUI click is claimed.
 */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationGraphMainColdTest {
    @get:Rule(order = 0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(180)
    @get:Rule(order = 1) val ui = createEmptyComposeRule()
    @get:Rule(order = 2) val failure = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // Keep the original intent/armed/selected and data. No disarm, fresh key or retry.
            if (arguments.getString("p9GraphPhase") != "observer") runCatching {
                NativeChatNotificationGraphColdStart.recordFailure(context, lease(), operation(), error.javaClass.name)
            }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun lease() = NativeChatNotificationColdPlan.uuid(checkNotNull(arguments.getString("p9GraphLease")))
    private fun operation() = NativeChatNotificationColdPlan.uuid(checkNotNull(arguments.getString("p9GraphOperation")))
    private fun <T> io(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.IO) { action() } }
    private fun screenshot(screen: NativeChatNotificationGraphColdStart.Screen) {
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        try { NativeChatNotificationGraphColdStart.screenshot(context, screen, bitmap) } finally { bitmap.recycle() }
    }
    @Before fun explicitParentOwnedInvocation() {
        require(BuildConfig.DEBUG && context.packageName == "com.easyai.agenthub.android.acceptance" &&
            context.applicationInfo.uid == Process.myUid() && instrumentation.context.packageName == context.packageName + ".test")
        lease(); operation(); NativeChatNotificationColdStart.requireHooks(context)
        NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(context)
        instrumentation.runOnMainSync {
            check(Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.none { it is MainActivity })
        }
    }
    private fun cleanupCounters(binding: NativeChatNotificationColdStart.Binding) = listOf(
        NativeChatNotificationColdStart.activityEntries.get(), NativeChatNotificationColdStart.defaultSelections.get(),
        binding.newIntentHandled.get(), binding.ports.forbiddenCommands.get(), binding.ports.notificationCommands.get())

    /** Hash only this admitted synthetic graph's existing notification ciphertext, never another namespace. */
    private fun notificationCipherHash(binding: NativeChatNotificationColdStart.Binding, graph: NativeChatNotificationGraphColdStart.Plan): String = io {
        binding.storage.awaitQueuedWrites()
        val folder = File(context.noBackupFilesDir.canonicalFile, "P9-native-system-" + NativeChatNotificationColdPlan.uuid(graph.base.lease))
        val directory = Os.lstat(folder.path)
        require(folder.canonicalFile == folder && OsConstants.S_ISDIR(directory.st_mode) &&
            directory.st_uid == Process.myUid() && directory.st_mode and 0x1ff == 0x1c0)
        val file = File(folder, NativeChatNotificationWire.hash(NativeChatNotificationStore.RECORD) + ".bin")
        fun stamp(value: StructStat) = listOf(value.st_dev, value.st_ino, value.st_mode.toLong(), value.st_nlink,
            value.st_uid.toLong(), value.st_gid.toLong(), value.st_size, value.st_mtime, value.st_ctime)
        val before = Os.lstat(file.path)
        require(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() && before.st_nlink == 1L &&
            before.st_mode and 0x1ff == 0x180 && before.st_size in 1..(512 * 1024).toLong())
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(fd).use { input ->
            require(stamp(Os.fstat(input.fd)) == stamp(before))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= 512 * 1024 && total <= before.st_size)
                digest.update(buffer, 0, count)
            }
            require(stamp(Os.fstat(input.fd)) == stamp(before))
        }
        require(total == before.st_size && stamp(Os.lstat(file.path)) == stamp(before) &&
            stamp(Os.lstat(folder.path)).take(6) == stamp(directory).take(6))
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun writerSeedsNewVersionedPendingTerminalAndUnknownGraphWithoutMain() {
        require(arguments.getString("p9GraphPhase") == "writer")
        val graph = io { NativeChatNotificationGraphColdStart.seedWriter(context, lease(), operation()) }
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get())
        assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
        assertNull(NativeChatNotificationColdStart.active)
        assertEquals(graph.fingerprint, NativeChatNotificationGraphColdStart.Plan.decode(JSONObject(graph.json().toString())).fingerprint)
        // Meaningful closed-schema boundaries. These operate on local copies, never the original files.
        assertTrue(runCatching {
            NativeChatNotificationGraphColdStart.Plan.decode(JSONObject(graph.json().toString()).put("namespace", "foreign"))
        }.isFailure)
        assertTrue(runCatching {
            NativeChatNotificationGraphColdStart.Plan.decode(JSONObject(graph.json().toString()).put("version", 2))
        }.isFailure)
        assertTrue(runCatching {
            NativeChatNotificationGraphColdStart.Plan.decode(JSONObject(graph.json().toString()).put("entryMode", "natural-os"))
        }.isFailure)
        assertTrue(runCatching {
            NativeChatNotificationGraphColdStart.Plan.decode(JSONObject(graph.json().toString()).put("terminalTicket", graph.base.ticket))
        }.isFailure)
    }
    @Test fun firstMainResumesOriginalNonceAcknowledgesOnceAndKeepsTerminalUnknownAndNewDraft() {
        require(arguments.getString("p9GraphPhase") == "reader")
        val graph = io { NativeChatNotificationGraphColdStart.prepareReader(context, lease(), operation()) }
        val original = NativeChatNotificationPlatform.openIntent(context, graph.base.ticket)
        assertTrue(runCatching {
            NativeChatNotificationColdStart.strictTicket(context, Intent(original).putExtra("lease", lease()))
        }.isFailure)
        lateinit var binding: NativeChatNotificationColdStart.Binding
        val cleanupEvents = CopyOnWriteArrayList<Pair<LifecycleOwner, Lifecycle.Event>>()
        val cleanupObserver = LifecycleEventObserver { owner, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) cleanupEvents.add(owner to event)
        }
        var cleanupActivity: MainActivity? = null
        lateinit var beforeCleanupCounters: List<Int>
        lateinit var beforeCleanupCipherHash: String
        ActivityScenario.launch<MainActivity>(original).use { scenario ->
            // core1.7 retains original by reference. Copy its now-launched filter only for harness cleanup.
            val cleanupLaunchIntent = Intent(original)
            var bodyFailure: Throwable? = null
            try {
                ui.waitUntil(25_000) {
                    NativeChatNotificationColdStart.active?.coordinator?.openState in setOf("opened", "unavailable")
                }
                binding = checkNotNull(NativeChatNotificationColdStart.active)
                val coordinator = checkNotNull(binding.coordinator)
                scenario.onActivity {
                    assertEquals(MainActivity::class.java, it.javaClass)
                    assertEquals("opened", coordinator.openState)
                    assertFalse(coordinator.openReceiptPending)
                    assertEquals(graph.base.originalThread, it.session.selectedThread)
                    assertEquals(graph.base.originalDraft, it.session.text)
                    assertEquals(graph.base.target, NativeChatNoticeTarget.from(checkNotNull(it.session.selectedClient.scope)))
                    assertEquals(1, NativeChatNotificationColdStart.activityEntries.get())
                    assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
                    assertEquals(12, binding.ports.locatorCursors.size)
                    assertTrue(it.session.operationRecords.isEmpty())
                }
                ui.onNodeWithTag("product-surface").assertIsDisplayed()
                ui.onNodeWithTag("message-input").assertTextContains(graph.base.originalDraft)
                screenshot(NativeChatNotificationGraphColdStart.Screen.FIRST_MAIN)
                // Restore the actual catalogue before adopting another exact row; do not make a
                // standalone locator response into Session authority.
                scenario.onActivity { it.session.refresh(userInitiated = true) }
                ui.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { ready = !it.session.loading && it.session.threads.any { row ->
                        row.id == graph.base.otherThread && row.hostId == graph.base.target.hostId
                    } }
                    ready
                }
                scenario.onActivity {
                    coordinator.navigationWillChange()
                    it.session.selectThread(it.session.threads.single { row ->
                        row.id == graph.base.otherThread && row.hostId == graph.base.target.hostId
                    })
                    assertEquals(graph.base.otherDraft, it.session.text)
                }
                ui.onNodeWithTag("message-input").assertTextContains(graph.base.otherDraft)
                ui.onNodeWithTag("message-input").performTextReplacement(graph.laterDraft)
                ui.waitForIdle()
                // A duplicate original traverses the actual onNewIntent dispatcher. No new claim.
                scenario.onActivity { it.startActivity(NativeChatNotificationPlatform.openIntent(it, graph.base.ticket)) }
                ui.waitUntil(10_000) { binding.newIntentHandled.get() == 1 }
                scenario.onActivity {
                    assertEquals(graph.base.otherThread, it.session.selectedThread)
                    assertEquals(graph.laterDraft, it.session.text)
                    assertEquals(graph.base.target, NativeChatNoticeTarget.from(checkNotNull(it.session.selectedClient.scope)))
                }
                // The original terminal ACK has no pending nonce and must not navigate/ACK again.
                scenario.onActivity { it.startActivity(NativeChatNotificationPlatform.openIntent(it, graph.terminalTicket)) }
                ui.waitUntil(10_000) {
                    var delivered = false
                    scenario.onActivity { delivered = it.intent.getStringExtra(NativeChatNotificationPlatform.TICKET) == graph.terminalTicket &&
                        coordinator.openState == "unavailable" && !coordinator.opening }
                    delivered
                }
                scenario.onActivity {
                    assertEquals(graph.base.otherThread, it.session.selectedThread)
                    assertEquals(graph.laterDraft, it.session.text)
                    assertEquals(1, NativeChatNotificationColdStart.activityEntries.get())
                    assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
                    assertEquals(0, binding.ports.forbiddenCommands.get())
                    assertEquals(0, binding.ports.notificationCommands.get())
                    assertEquals(12, binding.ports.locatorCursors.size)
                    assertTrue(it.session.operationRecords.isEmpty())
                }
                ui.onNodeWithTag("message-input").assertTextContains(graph.laterDraft)
                screenshot(NativeChatNotificationGraphColdStart.Screen.LATER_DRAFT)
                // Both actual UI screenshots and every business assertion precede this cleanup snapshot.
                beforeCleanupCounters = cleanupCounters(binding)
                beforeCleanupCipherHash = notificationCipherHash(binding, graph)
            } catch (failure: Throwable) {
                bodyFailure = failure
                throw failure
            } finally {
                try {
                    scenario.onActivity { activity ->
                        cleanupActivity = activity
                        activity.lifecycle.addObserver(cleanupObserver)
                        // Public setter only: no onNewIntent/receive/ticket dispatch and no mutation of original.
                        activity.setIntent(Intent(cleanupLaunchIntent))
                        assertTrue(cleanupLaunchIntent.filterEquals(activity.intent))
                        if (bodyFailure == null) {
                            assertEquals(beforeCleanupCounters, cleanupCounters(binding))
                            assertEquals(graph.base.otherThread, activity.session.selectedThread)
                            assertEquals(graph.laterDraft, activity.session.text)
                        }
                    }
                } catch (cleanupFailure: Throwable) {
                    val primary = bodyFailure
                    if (primary == null) throw cleanupFailure
                    if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                }
            }
            assertEquals(beforeCleanupCipherHash, notificationCipherHash(binding, graph))
        }
        val destroyedActivity = checkNotNull(cleanupActivity)
        assertEquals(listOf(Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY), cleanupEvents.map { it.second })
        assertTrue(cleanupEvents.all { it.first === destroyedActivity })
        instrumentation.runOnMainSync {
            assertTrue(destroyedActivity.isDestroyed)
            assertEquals(Lifecycle.State.DESTROYED, destroyedActivity.lifecycle.currentState)
            destroyedActivity.lifecycle.removeObserver(cleanupObserver)
        }
        assertEquals(beforeCleanupCounters, cleanupCounters(binding))
        assertEquals(beforeCleanupCipherHash, notificationCipherHash(binding, graph))
        // Complete only after ActivityScenario closes and all queued original storage writes settle.
        val proof = io { NativeChatNotificationGraphColdStart.completeReader(context, binding) }
        assertTrue(proof.getBoolean("unknownRecoveryUnchanged"))
        assertFalse(proof.getBoolean("OsProcessCreationProven"))
        assertFalse(proof.getBoolean("SystemUiNotificationClickUsed"))
    }
    @Test fun observerArchivesOnlyReviewedCompletedGraphAfterOriginalReaderExit() {
        require(arguments.getString("p9GraphPhase") == "observer")
        val reviewed = checkNotNull(arguments.getString("p9GraphCompleteSha256"))
        require(chatNoticeHash(reviewed))
        val proof = io {
            NativeChatNotificationGraphColdStart.releaseCompleted(context, lease(), operation(), reviewed)
        }
        assertEquals("released", proof.getString("phase"))
        assertTrue(proof.getBoolean("archiveVerifiedPresent"))
        assertFalse(proof.getBoolean("unknownOperationQueriedOrReplayed"))
        assertEquals(0, NativeChatNotificationColdStart.activityEntries.get())
        assertEquals(0, NativeChatNotificationColdStart.defaultSelections.get())
    }
}
