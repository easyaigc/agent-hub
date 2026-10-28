package com.agenthub.android

import com.agenthub.core.TargetScope
import org.junit.Assert.*
import org.junit.Test

/** Pure production event predicate: also executable on host JVM, with no storage/OS shims. */
class NativeReadingPresentationTest {
    private val target = TargetScope("codex",null,null,"host","workspace","runtime")
    private fun position(revision: Long,text: String="reply-$revision") = ThreadReadPosition(revision,"message-$revision",
        ThreadReadPosition.contentVersion("message-$revision",text,"completed"))
    private fun event(position: ThreadReadPosition) = NativeReadingEvent("event-${position.revision}",target,"thread","completed",position)
    @Test fun unreadNewReplyCannotAuthorizeOldCompletedEvent() {
        val old = position(1); val current = position(2)
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(old),current,old,true,null))
        assertTrue(NativeReadingPresentationPolicy.mayPresent(event(current),current,old,true,null))
    }
    @Test fun equalRevisionWithDifferentContentNeverPresents() {
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(position(2,"conflict")),position(2),null,true,null))
    }
    @Test fun readWatermarkAndReplyUnreadMustBothAllowPresentation() {
        val current = position(2)
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(current),current,current,true,null))
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(current),current,position(3),true,null))
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(current),current,null,false,null))
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event(current),null,null,true,null))
    }
    @Test fun seenNoticeCannotBorrowUnreadFromAnotherReplyOrNotice() {
        val event = NativeReadingEvent("approval",target,"thread","approval",runId="run",requestId="request")
        val notice = NativeReadingNotice(event.id,event.kind,event.runId,event.requestId,event.operationId)
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event,position(2),position(1),true,notice.copy(seen=true)))
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event,position(2),position(1),true,notice.copy(id="another")))
        assertTrue(NativeReadingPresentationPolicy.mayPresent(event,position(2),position(1),true,notice))
    }
    @Test fun noticeOriginalIntentMustMatchEvenWhenIdIsTheSame() {
        val event = NativeReadingEvent("approval",target,"thread","approval",runId="run",requestId="request")
        val notice = NativeReadingNotice(event.id,event.kind,event.runId,event.requestId,event.operationId)
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event,null,null,false,notice.copy(requestId="other")))
        assertFalse(NativeReadingPresentationPolicy.mayPresent(event,null,null,false,null))
    }
}
