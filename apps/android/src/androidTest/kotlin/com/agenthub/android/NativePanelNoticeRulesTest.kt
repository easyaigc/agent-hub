package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Test
import org.junit.Assert.*

/** Pure lifecycle rules used by the real Session writer; no Android runtime or storage substitute. */
class NativePanelNoticeRulesTest {
    private val target=TargetScope("codex",null,null,"host","workspace","runtime")
    private val position=ThreadReadPosition(1,"body",ThreadReadPosition.contentVersion("body","Reply","completed"))
    private fun lifecycle()=NativeReadingLifecycle().apply { foreground(true);uncovered(true);present(target,"thread",position,setOf("notice")) }
    @Test fun bodyLeaseCannotConsumeNoticesAndBodyReplacementKeepsNoticeLease() {
        val lifecycle=lifecycle();val body=lifecycle.visible(target,"thread","body","Reply","completed",true)!!
        assertTrue(body.noticeIds.isEmpty());assertEquals(position,body.position)
        val notice=lifecycle.visibleNotices(target,"thread",setOf("notice"))!!
        lifecycle.presentBody(target,"thread",position);lifecycle.hideBody()
        assertNull(lifecycle.ifCurrent(body) { true });assertEquals(true,lifecycle.ifCurrent(notice) { true })
    }
    @Test fun focusedQuestionSurfaceCanRemainVisibleWhileMainBodyWindowIsUnfocused() {
        val lifecycle=lifecycle();lifecycle.noticeSurface(true);lifecycle.uncovered(false)
        assertNull(lifecycle.visible(target,"thread","body","Reply","completed",true))
        val notice=lifecycle.visibleNotices(target,"thread",setOf("notice"))!!
        assertEquals(true,lifecycle.ifCurrent(notice) { true })
        lifecycle.noticeSurface(false);assertNull(lifecycle.ifCurrent(notice) { true })
    }
    @Test fun sessionAndViewMustBothAuthorizeInsideTheMutationAndOldFrameNeverRevives() {
        val lifecycle=lifecycle();val session=NativePanelNoticeSessionFence();val frame=session.open()
        var viewCurrent=true;var inside=0
        val view=object: NativeNoticeReadGuard { override fun <T> ifCurrent(block: ()->T): T?=if(viewCurrent)block() else null }
        val lease=lifecycle.visibleNotices(target,"thread",setOf("notice"),NativeCombinedNoticeGuard(frame,view))!!
        viewCurrent=false;assertNull(lifecycle.ifCurrent(lease) { ++inside })
        viewCurrent=true;assertEquals(1,lifecycle.ifCurrent(lease) { ++inside })
        session.revoke();session.open();assertNull(lifecycle.ifCurrent(lease) { ++inside });assertEquals(1,inside)
    }
    @Test fun navigationForegroundAndNoticeReplacementInvalidateCapturedLease() {
        for(change in listOf<(NativeReadingLifecycle)->Unit>({it.leave()},{it.foreground(false)},
            {it.presentNotices(target,"other",setOf("notice"))},{it.invalidateNotices()})) {
            val lifecycle=lifecycle();val lease=lifecycle.visibleNotices(target,"thread",setOf("notice"))!!
            change(lifecycle);assertNull(lifecycle.ifCurrent(lease) { true })
        }
    }
}
