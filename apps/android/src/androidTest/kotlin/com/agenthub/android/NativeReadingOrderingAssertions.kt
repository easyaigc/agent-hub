package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Actual Store/ledger/lifecycle; only Main scheduling is controlled. A host runner may supply
 * synthetic transactional storage, which is auxiliary and does not verify Android encryption. */
internal object NativeReadingOrderingAssertions {
    private class Driver: CoroutineDispatcher() {
        private val queue=LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext,block: Runnable) { queue.add(block) }
        fun take()=checkNotNull(queue.poll(5,TimeUnit.SECONDS)) { "Missing owned continuation" }
        fun drain() { while(true) (queue.poll(250,TimeUnit.MILLISECONDS) ?: return).run() }
        fun finish(vararg jobs: Job) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(jobs.any { !it.isCompleted }) {
                check(System.nanoTime()<deadline) { "Owned reading jobs did not finish" }
                queue.poll(100,TimeUnit.MILLISECONDS)?.run()
            }
            drain()
        }
    }
    fun run(storage: NativeSessionStorage,target: TargetScope) {
        for(mode in listOf("current","notice","covered","background","target","chat-return")) {
            val thread="reading-order-$mode";val reader="a".repeat(64)
            fun position(n: Long)=ThreadReadPosition(n,"message-$n",ThreadReadPosition.contentVersion("message-$n","reply-$n","completed"))
            val driver=Driver();val parent=SupervisorJob();val scope=CoroutineScope(parent+driver)
            var changes=0
            val phone=NativePhoneReadingStore(storage,scope) { changes++ }
            val ledger=checkNotNull(phone.ledger)
            val before=ledger.snapshot().count
            val seeded=ledger.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,thread,position(1),true)))
            val notice=NativeReadingEvent("ordering-question",target,thread,"question",requestId="original-question")
            if(mode=="notice")ledger.receive(notice)
            phone.publish(seeded);phone.lifecycle.foreground(true);phone.lifecycle.uncovered(true)
            var heldWriter: Runnable?=null
            try {
                phone.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,thread,position(2),true)),emptyList())
                // Hold the writer before its coroutine starts. Start the single original geometry
                // callback first; without the wait it reaches the ledger's old latest and finishes.
                val writer=driver.take();heldWriter=writer
                val visible=checkNotNull(phone.visible(NativeVisiblePhoneReading(MobileClient(target.hostId,"Ordering fixture",target),
                    ThreadReadingIdentity.key(target,thread),thread,1,1,1,position(2),NativeReadingBody("message-2","reply-2","completed")),reader))
                driver.take().run();driver.drain()
                println("OBSERVE reading-order-$mode writerHeld=true visibleCompleted=${visible.isCompleted} unread=${ledger.snapshot().isUnread(target,thread)} pending=${ledger.pendingReads().count { it.threadId==thread }}")
                assertTrue("Visible attempt must wait for its queued head ($mode)",visible.isActive)
                assertTrue(ledger.pendingReads().none { it.threadId==thread })
                when(mode) {
                    "covered" -> { phone.lifecycle.uncovered(false);phone.lifecycle.uncovered(true) }
                    "background" -> { phone.lifecycle.foreground(false);phone.lifecycle.foreground(true) }
                    "target" -> phone.lifecycle.leave()
                    "chat-return" -> {
                        phone.lifecycle.presentBody(target,"other-thread",position(2))
                        phone.lifecycle.presentBody(target,thread,position(2))
                    }
                }
                writer.run();heldWriter=null;driver.finish(visible)
                assertFalse(phone.busy);assertNull(phone.failure);assertTrue(phone.ready)
                val read=mode=="current" || mode=="notice"
                val unread=mode!="current"
                assertEquals(unread,phone.snapshot.isUnread(target,thread))
                assertEquals(before+if(unread)1 else 0,phone.snapshot.count)
                assertEquals(0,phone.snapshot.badge(false))
                assertEquals(ledger.snapshot(),phone.snapshot)
                val pending=ledger.pendingReads().filter { it.threadId==thread }
                if(read)assertEquals(position(2),pending.single().position) else assertTrue(pending.isEmpty())
                if(mode=="notice") {
                    assertEquals(listOf(notice.id),ledger.notices(target,thread).map { it.id })
                    assertTrue(ledger.mayPresent(notice)) // reading did not answer or consume the question
                }
                if(mode=="current") {
                    val original=pending.single()
                    assertFalse(ledger.receive(NativeReadingEvent("late-completed",target,thread,"completed",position(2))).isUnread(target,thread))
                    assertTrue(ledger.receive(NativeReadingEvent("new-completed",target,thread,"completed",position(3))).isUnread(target,thread))
                    assertTrue(ledger.settleStaleAcknowledgement(original).isUnread(target,thread))
                }
                assertTrue(changes>=1)
                println("PASS reading-order-$mode")
            } finally {
                phone.reset();parent.cancel();heldWriter?.run();driver.finish(parent)
                check(parent.children.none()) { "Owned reading children remain" }
            }
        }
    }
}
