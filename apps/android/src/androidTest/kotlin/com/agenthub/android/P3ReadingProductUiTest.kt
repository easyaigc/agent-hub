package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Actual private Main/Compose navigation and Activity stop/start. Controlled read-only snapshots;
 * no direct foreground/cover/leave calls perform the tested transitions. One fresh UUID per method. */
class P3ReadingProductUiTest {
    private lateinit var fixture: MigrationInboxFixture
    private lateinit var store: NativeSessionStorage
    private lateinit var session: MobileSession
    private var gate: HeadGate?=null
    private var revision=1L
    private val target=TargetScope("codex",null,null,"p3-reading-host","p3-workspace","p3-runtime")
    private val acks=CopyOnWriteArrayList<NativePendingRead>()
    private fun position(n: Long)=ThreadReadPosition(n,"message-$n",ThreadReadPosition.contentVersion("message-$n","reply-$n","completed"))
    private fun thread(id: String)=JSONObject().put("hostId",target.hostId).put("id",id).put("title",id).put("state","idle")
        .put("hasUnreadTurn",true).put("readState",ThreadReadState(position(revision),null,null).json())
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(
            JSONObject().put("hostId",target.hostId).put("name","P3 owned reading").put("readTarget",CodexCommandCodec.target(target))
                .put("connectionMode","history-only").put("capabilities",JSONArray().put("threads:read-state"))
                .put("projects",JSONArray()).put("threads",JSONArray().put(thread("thread")).put(thread("other-thread"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(
            JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target))
                .put("thread",thread(threadId)).put("messages",JSONArray().put(JSONObject().put("id","message-$revision")
                    .put("role","assistant").put("text","reply-$revision").put("status","completed"))).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({error("No unrelated command")}) {
        override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead): JSONObject {
            acks.add(request)
            return JSONObject().put("hostId",client.id).put("threadId",request.threadId).put("target",CodexCommandCodec.target(request.target))
                .put("readerId",request.readerId).put("read",request.position.json())
        }
    }
    private fun main(action: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main.immediate){action()}}
    @get:Rule(order=0) val privateMain=TestRule {base,_->object:Statement(){override fun evaluate(){
        fixture=MigrationInboxFixture();fixture.fresh();val context=fixture.context("source")
        store=fixture.storage("source");session=MobileSession(store,reads,commands)
        main {session.resetAcceptance(reads,commands)}
        NativeMigrationInboxOwnerActivity.prepare(fixture.run,context,session,discoveryEnabled=false,
            sessionConfiguration=NativeMigrationPrivateSessionConfiguration {value->check(value===session);value.resetAcceptance(reads,commands)})
        try {base.evaluate()} finally {
            gate?.close()
            // The Activity rule has closed the exact private Main before owner retirement.
            main {
                withTimeout(30_000){while(!session.migrationReady)delay(20)}
                val operation=java.util.UUID.randomUUID().toString();val lease=session.quiesceForMigration(operation)
                withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()}
                session.retireForMigration(operation);session.voice?.retireForMigration();session.questionVoice?.retireForMigration()
            }
            NativeMigrationInboxOwnerActivity.release(fixture.run)
        }
    }}}
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    @Before fun connect() {
        ui.waitUntil(30_000){!session.restoring&&session.migrationReady}
        main {session.connect("P3 owned reading","https://fixture.invalid","")}
        ui.waitUntil(10_000){!session.loading&&!session.phoneReading.busy}
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("recent-thread-thread").performScrollTo().performClick()
        ui.waitUntil(10_000){acks.any {it.threadId=="thread"&&it.position==position(1)}}
        assertSame(session,ui.activity.session)
    }
    @Test fun queuedHeadCannotReadBehindRealSettingsPage()=queued("settings")
    @Test fun queuedHeadCannotReadAfterActualActivityStop()=queued("background")
    @Test fun queuedHeadCannotReadAfterRealChatSwitch()=queued("chat")
    private fun queued(mode: String) {
        // Hold the exact private storage lock, then prove the head reconciliation itself queued.
        val tasks=MobileSession::class.java.getDeclaredField("tasks").apply {isAccessible=true}.get(session) as CoroutineScope
        val prior=runBlocking {withContext(Dispatchers.Main.immediate){tasks.coroutineContext[Job]!!.children.toSet()}}
        val held=HeadGate(store).also {gate=it};held.hold()
        revision=2
        main {session.loadHistory()}
        ui.waitUntil(10_000){!session.historyLoading&&session.messages.lastOrNull()?.text=="reply-2"}
        ui.onNodeWithTag("phone-reading-content-end").assertIsDisplayed()
        held.awaitHeadWriter()
        ui.waitUntil(10_000){
            val field=MobileSession::class.java.getDeclaredField("attemptedPhoneReading").apply {isAccessible=true}
            (field.get(session) as? NativeVisiblePhoneReading)?.position==position(2)&&session.phoneReading.busy
        }
        val originalJobs=runBlocking {withContext(Dispatchers.Main.immediate){tasks.coroutineContext[Job]!!.children.filter {it !in prior}.toList()}}
        check(originalJobs.isNotEmpty())
        when(mode) {
            "settings"->{ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("open-settings").performClick();ui.onNodeWithTag("settings-done").assertExists()}
            "background"->ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            "chat"->{ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("recent-thread-other-thread").performScrollTo().performClick()}
        }
        held.close();gate=null
        main {withTimeout(15_000){originalJobs.forEach {it.join()}};session.phoneReading.awaitReconciliation()}
        assertFalse(acks.any {it.threadId=="thread"&&it.position==position(2)})
        assertTrue(runBlocking {withContext(Dispatchers.IO){session.phoneReading.ledger!!.snapshot().isUnread(target,"thread")}})
        File(fixture.folder,"p3-reading-$mode.json").writeText(JSONObject().put("mode",mode).put("actualHeadQueued",true)
            .put("actualPageAttempted",true).put("originalUnreadWhileHidden",true).put("originalHeadAckWhileHidden",false).toString())
        when(mode) {
            "settings"->ui.onNodeWithTag("settings-done").performClick()
            "background"->ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            "chat"->{ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("recent-thread-thread").performScrollTo().performClick()}
        }
        // A fresh current geometry callback is legitimate; it must not be confused with the old lease.
        ui.waitUntil(15_000){acks.any {it.threadId=="thread"&&it.position==position(2)}}
        assertEquals(1,acks.count {it.threadId=="thread"&&it.position==position(2)})
    }
    private class HeadGate(storage: NativeSessionStorage): Closeable {
        private val lock=NativeSessionStorage::class.java.getDeclaredField("ioLock").apply {isAccessible=true}.get(storage) as ReentrantLock
        private val queue=ReentrantLock::class.java.getDeclaredMethod("getQueuedThreads").apply {isAccessible=true}
        private val entered=CountDownLatch(1);private val release=CountDownLatch(1)
        private val executor=Executors.newSingleThreadExecutor();private var closed=false
        private val holder=executor.submit {lock.lock();try {entered.countDown();check(release.await(45,TimeUnit.SECONDS))}finally{lock.unlock()}}
        fun hold(){check(entered.await(10,TimeUnit.SECONDS))}
        fun awaitHeadWriter() {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(true) {
                check(!holder.isDone&&release.count==1L)
                @Suppress("UNCHECKED_CAST") val threads=queue.invoke(lock) as Collection<Thread>
                if(threads.any {lock.hasQueuedThread(it)&&it.stackTrace.any {frame->frame.className.contains("NativeReadingLedger")&&frame.methodName.contains("reconcileOwned")}})return
                check(System.nanoTime()<deadline){"Exact head writer not observed on private lock"};Thread.yield()
            }
        }
        override fun close(){if(closed)return;closed=true;release.countDown();executor.shutdown();holder.get(10,TimeUnit.SECONDS);check(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
}
