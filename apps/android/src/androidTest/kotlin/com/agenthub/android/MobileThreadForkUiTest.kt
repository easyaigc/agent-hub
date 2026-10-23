package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Native Compose UI/HTTP/persistence with a synthetic Host; real desktop is a separate gate. */
@RunWith(AndroidJUnit4::class)
class MobileThreadForkUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    @Before fun reset()=ui.runOnIdle { session.resetAcceptance() }
    private fun connect(f: ForkFixture) {
        runBlocking { withContext(Dispatchers.Main) { session.connect("Fork computer",f.server.endpoint,"") } }
        ui.waitUntil(15_000) { session.hasClient && !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first { it.id=="source" }) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
        ui.onNodeWithText("FIRST_COMPLETED_REPLY").assertExists()
        runBlocking { withContext(Dispatchers.Main) { allowSyntheticRegressionSharing(session) } }
    }
    private fun menu() { ui.onAllNodesWithTag("fork-reply").onFirst().performClick();ui.waitUntil(5000) { session.forkReason("earlier-terminal-turn","same-directory")!= "正在读取分支选项…" } }
    private fun waitFork() { ui.waitUntil(15_000) { !session.forkBusy && session.selectedThread.startsWith("fork-") } }
    private fun original()=session.operationRecords.first { it.kind=="fork" }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/UM03").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot();try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun earlierReplyMenuOrderAndSameDirectoryKeepSourceDraft() {
        ForkFixture().use { f -> connect(f);ui.runOnIdle { session.text="source original draft" }
            val copy=ui.onAllNodesWithTag("copy-reply-first-reply").onFirst().fetchSemanticsNode().boundsInRoot
            val fork=ui.onAllNodesWithTag("fork-reply").onFirst().fetchSemanticsNode().boundsInRoot
            val share=ui.onAllNodesWithTag("share-reply-first-reply").onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue(copy.center.x<fork.center.x && fork.center.x<share.center.x)
            menu();ui.onNodeWithTag("fork-same-directory").assertIsEnabled();ui.onNodeWithTag("fork-worktree").assertIsEnabled();shot("menu-both-environments")
            ui.onNodeWithTag("fork-same-directory").performClick();waitFork()
            assertEquals("earlier-terminal-turn",JSONObject(String(f.posts.single().body)).getString("turnId"))
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="source" }) };assertEquals("source original draft",session.text)
            ui.waitUntil(10_000) { session.forkRecords.any { it.phase=="confirmed" } };shot("same-directory-confirmed-receipt")
        }
    }
    @Test fun worktreeUsesSameVisibleTurnAndOpensConfirmedThread() {
        ForkFixture().use { f -> connect(f);menu();ui.onNodeWithTag("fork-worktree").performClick();waitFork()
            assertEquals("worktree",JSONObject(String(f.posts.single().body)).getString("environment"));shot("worktree-opened")
        }
    }
    @Test fun nonGitExplainsDisabledWorktreeAndKeepsSameDirectory() {
        ForkFixture().use { f -> f.mode="non-git";connect(f);menu();ui.onNodeWithTag("fork-worktree").assertIsNotEnabled();ui.onNodeWithTag("fork-same-directory").assertIsEnabled()
            ui.onNodeWithText("此对话不在 Git 项目中，不能创建工作树").assertExists();assertTrue(f.posts.isEmpty());shot("non-git-disabled-reason")
        }
    }
    @Test fun pendingResultCannotTakeNewVersionOfSourceDraft() {
        ForkFixture().use { f -> f.phase="outcome-unknown";connect(f);menu();ui.onNodeWithTag("fork-same-directory").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.any { it.phase=="outcome-unknown" } };val record=original()
            ui.onAllNodesWithTag("fork-reply-progress").onFirst().assertExists()
            ui.runOnIdle { session.text="edited after submit" };f.phase="confirmed"
            ui.waitUntil(15_000) { session.forkRecords.any { it.phase=="confirmed" } };assertEquals("source",session.selectedThread);assertEquals("edited after submit",session.text);ui.onAllNodesWithTag("fork-reply-progress").assertCountEquals(0);shot("late-confirmed-preserves-new-draft")
            ui.onNodeWithTag("query-fork-${record.id}").performClick();waitFork();assertEquals(1,f.posts.size)
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="source" }) };assertEquals("edited after submit",session.text)
        }
    }
    @Test fun detachThenQueryKeepsNewDraftAndExplicitOpenCanNavigate() {
        ForkFixture().use { f -> f.phase="outcome-unknown";connect(f);menu();ui.onNodeWithTag("fork-same-directory").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.isNotEmpty() };val record=original()
            ui.onNodeWithTag("detach-fork-${record.id}").performClick();ui.waitUntil(10_000) { session.forkRecords.any { it.detached } }
            ui.runOnIdle { session.text="new detached draft" };f.phase="confirmed";ui.onNodeWithTag("query-fork-${record.id}").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.any { it.phase=="confirmed" } };assertEquals("source",session.selectedThread);assertEquals("new detached draft",session.text);shot("detached-keeps-draft-and-receipt")
            ui.onNodeWithTag("query-fork-${record.id}").performClick();waitFork();assertEquals(1,f.posts.size)
        }
    }
    @Test fun changingChatDuringUnknownCannotBeOverwrittenByLateFork() {
        ForkFixture().use { f -> f.phase="outcome-unknown";connect(f);menu();ui.onNodeWithTag("fork-worktree").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.isNotEmpty() };ui.runOnIdle { session.newChat();session.text="new chat draft" };f.phase="confirmed"
            ui.waitUntil(15_000) { session.threads.any { it.id.startsWith("fork-") } };assertEquals("",session.selectedThread);assertEquals("new chat draft",session.text);assertEquals(1,f.posts.size);shot("late-fork-keeps-new-chat")
        }
    }
    @Test fun activityRecreationRetainsUnknownReceiptAndDraftWithoutPostReplay() {
        ForkFixture().use { f -> f.phase="outcome-unknown";connect(f);menu();ui.onNodeWithTag("fork-same-directory").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.any { it.phase=="outcome-unknown" } }
            val id=original().id;ui.runOnIdle { session.text="draft survives activity recreation" };shot("pending-before-recreation")
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15_000) { !session.restoring && session.forkRecords.any { it.id==id } && !session.loading }
            assertEquals("source",session.selectedThread);assertEquals("draft survives activity recreation",session.text);assertEquals(1,f.posts.size)
            f.phase="confirmed";ui.onNodeWithTag("query-fork-$id").performClick();waitFork();assertEquals(1,f.posts.size);shot("recreated-original-receipt-opened")
        }
    }
    @Test fun forkNavigationAndComposerResizeScheduleListLayoutSafely() {
        ForkFixture().use { f -> connect(f);menu();ui.onNodeWithTag("fork-worktree").performClick();waitFork()
            val fork=session.selectedThread
            repeat(8) { index ->
                ui.runOnIdle { session.selectThread(session.threads.first { it.id=="source" });session.text="long source draft $index\n".repeat(24) }
                ui.waitUntil(10_000) { !session.historyLoading }
                ui.runOnIdle { session.selectThread(session.threads.first { it.id==fork });session.text="fork draft $index" }
                ui.waitUntil(10_000) { !session.historyLoading }
            }
            assertEquals(fork,session.selectedThread);assertEquals("fork draft 7",session.text);assertEquals(1,f.posts.size);shot("fork-navigation-and-composer-resize")
        }
    }
    @Test fun rejectedReceiptIsVisibleAndDoesNotNavigate() {
        ForkFixture().use { f -> f.phase="rejected";connect(f);menu();ui.onNodeWithTag("fork-same-directory").performClick()
            ui.waitUntil(10_000) { !session.forkBusy && session.forkRecords.any { it.phase=="rejected" } };assertEquals("source",session.selectedThread)
            ui.onNodeWithText("分支未创建").assertExists();ui.onNodeWithText("电脑拒绝了分支").assertExists();assertEquals(1,f.posts.size);shot("rejected-original-receipt")
        }
    }
}
