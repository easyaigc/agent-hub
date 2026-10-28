package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Actual shared encrypted root: P7 visibility and P9 delivery remain separate authorities. */
class NativeChatNotificationClaimTest {
    @Test fun readingBetweenAdmissionAndClaimSuppressesOriginalWithoutConsumingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        val namespace = "P9-reading-claim-${UUID.randomUUID()}"
        val storage = NativeSessionStorage(context, namespace)
        val ledger = NativeReadingLedger(NativeSessionStorage(context, namespace))
        val store = NativeChatNotificationStore(storage)
        val target = TargetScope("codex", null, null, "host", "workspace", "runtime")
        val position = ThreadReadPosition(1, "message", ThreadReadPosition.contentVersion("message", "reply", "completed"))
        val notice = NativeReadingEvent("a".repeat(64), target, "thread", "completed", position)
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", position, true)))
        assertTrue(ledger.mayPresent(notice))
        store.update { it.copy(enabled = true) }
        val lease = store.bind(NativeChatNoticeTarget.from(target), "credential-1")
        val now = System.currentTimeMillis()
        val event = NativeChatNotificationEvent(notice.id, lease.target, NativeChatNoticeKind.COMPLETED,
            Instant.ofEpochMilli(now).toString(), notice.threadId, readTarget = lease.target,
            readPosition = NativeChatNoticeReadPosition(position.revision, position.messageId, position.contentVersion))
        // Simulate the user seeing the reply while notification channel preparation was pending.
        val view = NativeReadingLifecycle(); view.foreground(true); view.uncovered(true)
        view.present(target, "thread", position)
        val visible = view.visible(target, "thread", "message", "reply", "completed", true)!!
        ledger.readVisible(target, "thread", "b".repeat(64), view, visible)
        val before = storage.read(NativeChatNotificationStore.RECORD)
        assertNull(store.claim(event, lease, now) { ledger.mayPresent(notice) })
        assertEquals(before, storage.read(NativeChatNotificationStore.RECORD))
        assertEquals(0, ledger.snapshot().count)
        // A newer unread reply cannot resurrect the original read event.
        val next = ThreadReadPosition(2, "message-2", ThreadReadPosition.contentVersion("message-2", "new", "completed"))
        ledger.reconcile(listOf(target), listOf(NativeReadingSnapshot(target, "thread", next, true)))
        assertEquals(1, ledger.snapshot().count)
        assertNull(store.claim(event, lease, now + 1) { ledger.mayPresent(notice) })
        assertEquals(before, storage.read(NativeChatNotificationStore.RECORD))
    }
}
