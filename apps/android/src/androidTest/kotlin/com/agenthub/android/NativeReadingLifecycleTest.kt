package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test

class NativeReadingLifecycleTest {
    private val target = TargetScope("codex", null, null, "host", "workspace", "runtime")
    private val position = ThreadReadPosition(1, "message", ThreadReadPosition.contentVersion("message", "中文 😀", "completed"))
    @Test fun actualLatestContentAndStatusMustMatchBeforeRead() {
        val lifecycle = NativeReadingLifecycle(); lifecycle.foreground(true); lifecycle.uncovered(true); lifecycle.present(target, "thread", position)
        assertNull(lifecycle.visible(target, "thread", "message", "中文 😀", "completed", false))
        assertNull(lifecycle.visible(target, "thread", "message", "older", "completed", true))
        assertNull(lifecycle.visible(target, "thread", "message", "中文 😀", "running", true))
        assertNull(lifecycle.visible(target, "other", "message", "中文 😀", "completed", true))
        assertNotNull(lifecycle.visible(target, "thread", "message", "中文 😀", "completed", true))
    }
    @Test fun backgroundCoverNavigationAndOtherViewInvalidateCapturedLease() {
        for (change in listOf<(NativeReadingLifecycle) -> Unit>({ it.foreground(false); it.foreground(true) },
            { it.uncovered(false); it.uncovered(true) }, { it.leave(); it.present(target, "thread", position) },
            { it.present(target, "other", position) })) {
            val lifecycle = NativeReadingLifecycle(); lifecycle.foreground(true); lifecycle.uncovered(true); lifecycle.present(target, "thread", position)
            val lease = lifecycle.visible(target, "thread", "message", "中文 😀", "completed", true)!!
            assertEquals(true, lifecycle.ifCurrent(lease) { true }); change(lifecycle)
            assertNull(lifecycle.ifCurrent(lease) { true })
            assertNull(NativeReadingLifecycle().ifCurrent(lease) { true })
        }
    }
    @Test fun projectedVisibleReplyMustMatchItsDisplayVersion() {
        val display = ThreadReadPosition.contentVersion("message", "projection", "completed")
        val lifecycle = NativeReadingLifecycle(); lifecycle.foreground(true); lifecycle.uncovered(true); lifecycle.present(target, "thread", position)
        assertNotNull(lifecycle.visible(target, "thread", "message", "projection", "completed", true, ReadContentProjection(position.contentVersion, display)))
        assertNull(lifecycle.visible(target, "thread", "message", "different projection", "completed", true, ReadContentProjection(position.contentVersion, display)))
    }
}
