package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled native fixture evidence; does not represent Host or Electron interoperation. */
class Life01ForegroundSessionTest {
    @get:Rule val foreground: org.junit.rules.TestRule = NativeTestForeground()
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
    }

    @Test fun returningPreservesDraftAndThreadProofDoesNotWaitForWorkspace() = runBlocking {
        ChatManagementFixture().use { fixture ->
            fixture.writableHistory = true
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = withContext(Dispatchers.Main) {
                MobileSession(NativeSessionStorage(context,"life01-" + UUID.randomUUID()), NativeHostReads { "" }).also { it.restore() }
            }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                waitFor { !session.restoring }
                withContext(Dispatchers.Main) {
                    session.enterForeground()
                    session.connect("life01 fixture", fixture.server.endpoint, "")
                    session.selectThread(session.threads.first { it.id == ChatManagementFixture.first })
                }
                waitFor { session.canStop && !session.historyLoading && !session.loading }
                withContext(Dispatchers.Main) {
                    session.text = "life01 draft"
                    session.leaveForeground()
                    fixture.workspaceStarted = started
                    fixture.workspaceRelease = release
                    session.enterForeground()
                }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                waitFor { session.canStop && !session.historyLoading }
                withContext(Dispatchers.Main) {
                    assertTrue(session.selectedClient.online)
                    assertEquals(ChatManagementFixture.first, session.selectedThread)
                    assertEquals("life01 draft", session.text)
                    assertFalse(session.canCreateThread)
                    assertFalse(session.canCreateProjectless)
                }
                assertTrue(fixture.posts.isEmpty())
                release.countDown()
                waitFor { !session.loading }
                withContext(Dispatchers.Main) { assertEquals("life01 draft",session.text) }
            } finally {
                release.countDown()
                withContext(Dispatchers.Main) { session.leaveForeground() }
            }
        }
    }
}

private class Life01ProofFixture : java.io.Closeable {
    private val base = ChatManagementFixture()
    @Volatile var holdWorkspace: CountDownLatch? = null
    @Volatile var holdHistory: CountDownLatch? = null
    val workspaceStarted = CountDownLatch(1)
    val historyStarted = CountDownLatch(1)
    private val target = base.target
    val server = CommandFixture { request ->
        when (request.path) {
            "/host", "/v1/workspace" -> {
                if (request.path == "/v1/workspace") holdWorkspace?.let { workspaceStarted.countDown(); it.await(20, TimeUnit.SECONDS) }
                WireResponse(body = base.workspace().put("capabilities", org.json.JSONArray(listOf(
                    "reply", "commands:target-bound:v1", "threads:create", "threads:create:projectless",
                    "desktop:creations", "desktop:creations:projectless", "stop", "queue", "queue:execute", "approvals"
                ))).toString())
            }
            "/v1/history" -> {
                holdHistory?.let { historyStarted.countDown(); it.await(20, TimeUnit.SECONDS) }
                WireResponse(body = org.json.JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first)
                    .put("readTarget",CodexCommandCodec.target(target)).put("state","running").put("messages",org.json.JSONArray())
                    .put("runtime",org.json.JSONObject().put("state","running").put("turnId","life01-turn")
                        .put("plan",org.json.JSONArray()).put("error",org.json.JSONObject.NULL)
                        .put("approvals",org.json.JSONArray().put(org.json.JSONObject().put("id","life01-approval")
                            .put("threadId",ChatManagementFixture.first).put("turnId","life01-turn").put("kind","command")
                            .put("title","Synthetic approval").put("detail","Controlled fixture").put("decisions",org.json.JSONArray(listOf("decline"))).put("questions",org.json.JSONArray())))
                        .put("receipts",org.json.JSONArray().put(org.json.JSONObject().put("hostId",target.hostId)
                            .put("threadId",ChatManagementFixture.first).put("clientMessageId","life01-queue")
                            .put("state","queued").put("turnId",org.json.JSONObject.NULL).put("error",org.json.JSONObject.NULL)
                            .put("queueSource","desktop").put("queuePresent",true).put("canExecuteNow",true)))
                        .put("canControl",true).put("proposedPlan",org.json.JSONObject.NULL)).toString())
            }
            "/v1/models" -> WireResponse(body=org.json.JSONObject().put("hostId",target.hostId).put("models",org.json.JSONArray()).toString())
            else -> WireResponse(404)
        }
    }
    override fun close() { holdWorkspace?.countDown(); holdHistory?.countDown(); server.close(); base.close() }
}

class Life01ProofWindowTest {
    @get:Rule val foreground: org.junit.rules.TestRule = NativeTestForeground()
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
    }
    private suspend fun exercise(workspaceFirst: Boolean) {
        Life01ProofFixture().use { fixture ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = withContext(Dispatchers.Main) {
                MobileSession(NativeSessionStorage(context,"life01-proof-"+UUID.randomUUID()),NativeHostReads { "" }).also { it.restore() }
            }
            val workspaceRelease=CountDownLatch(1); val historyRelease=CountDownLatch(1)
            try {
                waitFor { !session.restoring }
                withContext(Dispatchers.Main) {
                    session.enterForeground(); session.connect("life01 proof",fixture.server.endpoint,"")
                    session.selectThread(session.threads.first { it.id==ChatManagementFixture.first })
                }
                waitFor { session.canStop && session.canCreateProjectless && !session.loading && !session.historyLoading &&
                    session.runtime?.approvals?.isNotEmpty()==true && session.queuedReplies.isNotEmpty() }
                val approval=withContext(Dispatchers.Main) { session.runtime!!.approvals.single() }
                val receipt=withContext(Dispatchers.Main) { session.queuedReplies.single() }
                val target=withContext(Dispatchers.Main) { session.targetKey }
                withContext(Dispatchers.Main) {
                    assertTrue(session.canAnswer(approval));assertTrue(session.canExecuteQueue(receipt,target,"life01-turn"))
                }
                withContext(Dispatchers.Main) {
                    session.text="retained draft"; session.leaveForeground()
                    fixture.holdWorkspace=workspaceRelease;fixture.holdHistory=historyRelease;session.enterForeground()
                }
                assertTrue(withContext(Dispatchers.IO) { fixture.workspaceStarted.await(5,TimeUnit.SECONDS) })
                assertTrue(withContext(Dispatchers.IO) { fixture.historyStarted.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    assertTrue(session.selectedClient.online);assertFalse(session.canStop);assertFalse(session.canCreateProjectless)
                    session.performAction();session.stopTask();assertFalse(session.newChat())
                    assertFalse(session.canAnswer(approval));assertFalse(session.canExecuteQueue(receipt,target,"life01-turn"))
                    session.answer(approval,"decline",emptyMap(),session.approvalActionDestination())
                    session.executeQueue(receipt,target,"life01-turn")
                    assertEquals("retained draft",session.text)
                }
                assertTrue(fixture.server.requests.none { it.method=="POST" })
                if(workspaceFirst) {
                    fixture.holdWorkspace=null;workspaceRelease.countDown()
                    waitFor { !session.loading }
                    withContext(Dispatchers.Main) { assertTrue("workspace proof must allow creation independently",session.canCreateProjectless);assertFalse(session.canStop) }
                } else {
                    fixture.holdHistory=null;historyRelease.countDown()
                    waitFor { session.canStop }
                    withContext(Dispatchers.Main) {
                        assertFalse(session.canCreateProjectless)
                        assertTrue(session.canAnswer(approval));assertTrue(session.canExecuteQueue(receipt,target,"life01-turn"))
                    }
                }
                workspaceRelease.countDown();historyRelease.countDown()
                waitFor { session.canStop && session.canCreateProjectless }
                withContext(Dispatchers.Main) { assertEquals("retained draft",session.text) }
                assertTrue(fixture.server.requests.none { it.method=="POST" })
            } finally {
                workspaceRelease.countDown();historyRelease.countDown()
                withContext(Dispatchers.Main) { session.leaveForeground() }
            }
        }
    }
    @Test fun workspaceFirst() = runBlocking { exercise(true) }
    @Test fun threadFirst() = runBlocking { exercise(false) }
}
