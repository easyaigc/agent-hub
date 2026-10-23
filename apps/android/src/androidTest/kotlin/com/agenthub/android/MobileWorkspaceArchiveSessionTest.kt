package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileWorkspaceArchiveSessionTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun ready(fixture: ChatManagementFixture,name: String): MobileSession {
        val session=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,name),NativeHostReads { "" }).also { it.restore() } }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
        withContext(Dispatchers.Main) {
            session.enterForeground()
            session.connect("management fixture",fixture.server.endpoint,"")
            session.selectThread(session.threads.first { it.id==ChatManagementFixture.first })
        }
        wait { session.currentChat!=null&&session.canManageChat(session.currentChat)&&!session.historyLoading }
        return session
    }
    private suspend fun wait(predicate: ()->Boolean)=withTimeout(5_000) { while(!withContext(Dispatchers.Main) { predicate() })delay(10) }
    @Test fun singleAndBatchPendingBlockOnlyTheirOriginalChatsWithoutNewWrites()=runBlocking {
        ChatManagementFixture().use { f ->
            f.batchSupported=true;f.phase="outcome-unknown";f.batchPostStatus=500;f.extraThreads=listOf("thread-actions-fresh")
            val session=ready(f,"batch-conflict-"+UUID.randomUUID())
            try {
                withContext(Dispatchers.Main) {
                    session.text="UNSENT_CONFLICT_DRAFT";session.manageChat("pin")
                    assertFalse(session.canBatchArchive(session.currentChat!!))
                    assertNull(session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(ChatManagementFixture.first)))
                }
                wait { session.pendingChatActions(ChatManagementFixture.first).singleOrNull()?.phase=="outcome-unknown" }
                val originalSingle=session.pendingChatActions(ChatManagementFixture.first).single()
                withContext(Dispatchers.Main) {
                    val first=session.threads.first { it.id==ChatManagementFixture.first }
                    assertFalse(session.canBatchArchive(first));assertFalse(session.canManageChat(first))
                    assertNull(session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(first.id)))
                }
                assertEquals(1,f.posts.size)
                withContext(Dispatchers.Main) { session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(ChatManagementFixture.other))?.join() }
                wait { session.archiveBatchRecords.singleOrNull()?.phase=="uncertain"&&!session.archiveBatchBusy }
                val originalBatch=session.archiveBatchRecords.single()
                withContext(Dispatchers.Main) {
                    val other=session.threads.first { it.id==ChatManagementFixture.other }
                    assertFalse(session.canManageChat(other));assertFalse(session.canBatchArchive(other))
                    session.selectThread(other);session.manageChat("delete")
                    assertNull(session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(other.id)))
                    val fresh=session.threads.first { it.id=="thread-actions-fresh" }
                    assertTrue(session.canManageChat(fresh));assertTrue(session.canBatchArchive(fresh))
                }
                f.batchGetStatus=404;withContext(Dispatchers.Main) { session.queryWorkspaceArchive(originalBatch)?.join() }
                assertEquals(2,f.posts.size);assertEquals(originalBatch.intent,session.archiveBatchRecords.single().intent)
                assertEquals(originalSingle.intent,session.pendingChatActions(ChatManagementFixture.first).single().intent)
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
                assertEquals("UNSENT_CONFLICT_DRAFT",session.text)
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun unknownBatchRestoresWithIndependentDraftAndOnlyOriginalQuery()=runBlocking {
        ChatManagementFixture().use { f ->
            f.batchSupported=true;f.batchPostStatus=500
            val name="batch-session-"+UUID.randomUUID();val session=ready(f,name)
            try {
                withContext(Dispatchers.Main) { session.text="original batch draft";session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(ChatManagementFixture.first,ChatManagementFixture.other))?.join() }
                wait { session.archiveBatchRecords.singleOrNull()?.phase=="uncertain"&&!session.archiveBatchBusy }
                val record=session.archiveBatchRecords.single()
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="other saved draft" }
                withTimeout(5_000) { while(!NativeSessionStorage(context,name).metadata().toString().contains("other saved draft"))delay(10) }
                withContext(Dispatchers.Main) { session.leaveForeground() }
                val restored=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,name),NativeHostReads { "" }).also { it.restore() } }
                try {
                    wait { !restored.restoring&&restored.archiveBatchRecords.isNotEmpty() }
                    withContext(Dispatchers.Main) { restored.enterForeground() };f.batchGetStatus=404
                    withContext(Dispatchers.Main) { restored.queryWorkspaceArchive(restored.archiveBatchRecords.single())?.join() }
                    assertEquals("uncertain",restored.archiveBatchRecords.single().phase);assertEquals(1,f.posts.size)
                    f.batchGetStatus=200;f.batchStates[ChatManagementFixture.other]="rejected"
                    withContext(Dispatchers.Main) { restored.error=null;restored.queryWorkspaceArchive(restored.archiveBatchRecords.single())?.join() }
                    wait { restored.archiveBatchRecords.single().phase=="completed"&&!restored.loading }
                    assertEquals("other saved draft",restored.text);assertEquals(ChatManagementFixture.other,restored.selectedThread)
                    assertEquals(record.intent,restored.archiveBatchRecords.single().intent);assertEquals(1,f.posts.size)
                    assertTrue(restored.threads.first { it.id==ChatManagementFixture.first }.archived==true)
                } finally { withContext(Dispatchers.Main) { restored.leaveForeground() } }
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun lateBatchKeepsAnotherChatDraftAndDuplicateSelectionCannotSubmitTwice()=runBlocking {
        ChatManagementFixture().use { f ->
            f.batchSupported=true;f.started=CountDownLatch(1);f.release=CountDownLatch(1)
            val session=ready(f,"batch-session-"+UUID.randomUUID())
            try {
                withContext(Dispatchers.Main) {
                    val context=session.captureWorkspaceArchive();session.archiveWorkspace(context,listOf(ChatManagementFixture.first));session.archiveWorkspace(context,listOf(ChatManagementFixture.first))
                }
                assertTrue(withContext(Dispatchers.IO) { f.started!!.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { assertFalse(session.canManageChat(session.currentChat));session.manageChat("delete") }
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="new untouched draft" }
                f.release!!.countDown();wait { session.archiveBatchRecords.any { it.phase=="completed" }&&!session.archiveBatchBusy&&!session.loading }
                assertEquals(1,f.posts.size);assertEquals("new untouched draft",session.text);assertEquals(ChatManagementFixture.other,session.selectedThread)
            } finally { f.release!!.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun capturedClientChangeAndInvalidSelectionsCannotSend()=runBlocking {
        ChatManagementFixture().use { f ->
            f.batchSupported=true;val session=ready(f,"batch-session-"+UUID.randomUUID())
            try {
                withContext(Dispatchers.Main) {
                    val captured=session.captureWorkspaceArchive()
                    assertNull(session.archiveWorkspace(captured,emptyList()));assertNull(session.archiveWorkspace(captured,listOf("same","same")))
                    assertNull(session.archiveWorkspace(captured,(1..101).map { "chat-$it" }))
                    assertNull(session.archiveWorkspace(captured,listOf("foreign-chat")))
                }
                val captured=withContext(Dispatchers.Main) { session.captureWorkspaceArchive() }
                f.batchSupported=false;withContext(Dispatchers.Main) { session.refresh(userInitiated=true) };wait { !session.loading&&!session.supportsBatchArchive }
                withContext(Dispatchers.Main) { assertNull(session.archiveWorkspace(captured,listOf(ChatManagementFixture.first))) }
                assertTrue(f.posts.isEmpty());assertTrue(session.archiveBatchRecords.isEmpty())
            } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun olderCatalogCannotResurrectCompletedBatchAndCurrentChatExitsWithoutLosingDraft()=runBlocking {
        ChatManagementFixture().use { f ->
            f.batchSupported=true;val session=ready(f,"batch-session-"+UUID.randomUUID());val started=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                f.workspaceStarted=started;f.workspaceRelease=release
                withContext(Dispatchers.Main) { session.text="retained archived draft";session.refresh(userInitiated=true) }
                assertTrue(withContext(Dispatchers.IO) { started.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(ChatManagementFixture.first)) }
                wait { session.archiveBatchRecords.any { it.phase=="completed" }&&session.selectedThread.isEmpty()&&!session.loading&&f.workspaceReadsAfterHold>0 }
                release.countDown();delay(100)
                assertTrue(session.threads.first { it.id==ChatManagementFixture.first }.archived==true)
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
                assertEquals("retained archived draft",session.text);assertEquals(1,f.posts.size)
            } finally { release.countDown();withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
}
