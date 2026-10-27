package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native UI, HTTP and Keystore storage with synthetic computer responses. */
@RunWith(AndroidJUnit4::class)
class MobileExecutionTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() { ui.runOnIdle { session.resetAcceptance() } }
    private fun connect(fixture: ExecutionFixture,thread: Boolean=true) {
        main { session.connect("执行验收电脑",fixture.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading && session.hasClient }; ui.waitUntil(10_000) { session.operationRecords.isEmpty() }; hideKeyboard()
        if(thread) { ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(10_000) { !session.historyLoading && session.runtime!=null } }
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun shot(name: String,keepKeyboard: Boolean=false) {
        if(!keepKeyboard) hideKeyboard()
        ui.waitForIdle(); val i=InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync(); i.uiAutomation.waitForIdle(1000,10_000)
        val frames=CountDownLatch(1); ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS))
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4").apply { mkdirs() }; val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun hideKeyboard() {
        // Modal questions own a separate window; close the actual focused IME.
        ui.waitForIdle()
        if(androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
        ui.waitForIdle()
    }
    private fun sent(f: ExecutionFixture)=f.server.requests.filter { it.path=="/v1/replies" }.map { JSONObject(String(it.body,Charsets.UTF_8)) }

    @Test fun nativeSendPreservesSettingsAndClearsOnlyItsDraft() {
        ExecutionFixture().use { f -> connect(f)
            ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","guardian-approvals","fast","plan")); session.text="  中文 😀\n" }
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null && session.text.isEmpty() }
            val request=sent(f).single(); assertEquals("  中文 😀\n",request.getString("text")); assertEquals("high",request.getString("effort")); assertEquals("guardian-approvals",request.getString("permissionMode")); assertEquals("plan",request.getString("collaborationMode")); assertEquals("next-turn",request.getString("mode"))
            assertTrue(f.server.requests.all { it.method=="GET" || it.path=="/v1/replies" })
            shot("native-send-settings")
        }
    }
    @Test fun confirmedOwnerSendDoesNotWaitForUnavailableBackgroundReads() {
        ExecutionFixture().use { fixture ->
            val native=NativeHostReads { "" };var embargo=false
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val catalogsBeforePost=java.util.concurrent.atomic.AtomicInteger();val historiesBeforePost=java.util.concurrent.atomic.AtomicInteger()
            val reader=object: HostReads by native {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    if(embargo&&sent(fixture).isEmpty()) { catalogsBeforePost.incrementAndGet();throw HostReadError("Synthetic catalog read unavailable",transient=true) }
                    return native.workspace(client,projectCursor,threadCursor)
                }
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                    if(embargo&&sent(fixture).isEmpty()) { historiesBeforePost.incrementAndGet();started.complete(Unit);release.await() }
                    return native.history(client,threadId,cursor)
                }
            }
            try {
                ui.runOnIdle { session.resetAcceptance(reader) };connect(fixture)
                ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","read-only"));embargo=true;session.loadHistory() }
                runBlocking { withTimeout(5_000) { started.await() } }
                ui.onNodeWithTag("message-input").performTextReplacement("one immutable reply during background read")
                ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
                try { ui.waitUntil(5_000) { sent(fixture).size==1&&session.transferStatus==null&&session.operationRecords.any { it.id==sent(fixture).single().getString("clientMessageId")&&it.kind=="reply" } } }
                catch(failure: Exception) {
                    println("CONFIRMED_OWNER_FACTS "+JSONObject().put("posts",sent(fixture).size).put("catalogsBeforePost",catalogsBeforePost.get()).put("historiesBeforePost",historiesBeforePost.get()).put("transfer",session.transferStatus).put("error",session.error).put("recordPhases",JSONArray(session.operationRecords.map { it.kind+":"+it.phase })))
                    throw failure
                }
                val intent=sent(fixture).single();assertEquals("one immutable reply during background read",intent.getString("text"));assertEquals("read-only",intent.getString("permissionMode"))
                assertEquals(0,catalogsBeforePost.get());assertEquals(1,historiesBeforePost.get())
                assertEquals(0,fixture.server.requests.count { it.method=="POST"&&it.path=="/v1/desktop/open" })
                assertTrue(session.operationRecords.any { it.id==intent.getString("clientMessageId")&&it.kind=="reply" })
            } finally { release.complete(Unit);main { session.leaveForeground() } }
        }
    }
    @Test fun newNavigationCannotReuseConfirmedOwnerWhileInitialReadsArePending() {
        ExecutionFixture().use { fixture ->
            val native=NativeHostReads { "" };val release=CompletableDeferred<Unit>();var hold=false
            val reader=object: HostReads by native {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    if(hold)release.await()
                    return native.workspace(client,projectCursor,threadCursor)
                }
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                    if(hold)release.await()
                    return native.history(client,threadId,cursor)
                }
            }
            try {
                ui.runOnIdle { session.resetAcceptance(reader) };connect(fixture)
                ui.onNodeWithTag("message-input").performTextReplacement("draft remains after navigation generation change")
                ui.onNodeWithTag("send-message").assertIsEnabled()
                ui.runOnIdle { hold=true;session.select(session.selectedClient) }
                ui.waitUntil(5_000) { session.historyLoading }
                ui.onAllNodes(hasTestTag("send-message") and isEnabled()).assertCountEquals(0);assertTrue(sent(fixture).isEmpty())
                assertEquals("draft remains after navigation generation change",session.text)
            } finally { release.complete(Unit);main { session.leaveForeground() } }
        }
    }
    @Test fun selectingThreadDuringWorkspaceRefreshKeepsSameClientControlReady() {
        ExecutionFixture().use { fixture ->
            val native=NativeHostReads { "" }
            val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            var calls=0
            val reader=object: HostReads by native {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    calls++
                    if(calls==2) { started.complete(Unit);release.await() }
                    return native.workspace(client,projectCursor,threadCursor)
                }
            }
            ui.runOnIdle { session.resetAcceptance(reader) }
            try {
                main { session.connect("workspace selection race",fixture.server.endpoint,"") }
                ui.runOnIdle { session.refresh() }
                runBlocking { withTimeout(10_000) { started.await() } }
                ui.runOnIdle { session.selectThread(session.threads.first()) }
                release.complete(Unit)
                ui.waitUntil(10_000) { !session.loading && !session.historyLoading && session.runtime?.canControl==true }
                assertTrue("A same-client workspace remains fresh after selecting its thread",session.canCompose)
                assertEquals(2,calls)
                assertTrue(fixture.server.requests.all { it.method=="GET" })
            } finally { release.complete(Unit) }
        }
    }
    @Test fun readyComposerStaysEnabledDuringBackgroundHistoryRead()=composerDuringRead(false,false)
    @Test fun explicitHistoryRefreshKeepsAnAlreadyReadyComposerEnabled()=composerDuringRead(true,false)
    @Test fun firstReadOfANewNavigationStillBlocksCachedComposerReadiness()=composerDuringRead(false,true)
    private fun composerDuringRead(userInitiated: Boolean,newNavigation: Boolean) {
        ExecutionFixture().use { fixture ->
            val native=NativeHostReads { "" };val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var hold=false
            val reader=object: HostReads by native {
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                    if(hold) { started.complete(Unit);release.await() }
                    return native.history(client,threadId,cursor)
                }
            }
            ui.runOnIdle { session.resetAcceptance(reader) };connect(fixture)
            try {
                ui.runOnIdle { session.text="ready background draft";hold=true;if(newNavigation)session.select(session.selectedClient) else session.loadHistory(userInitiated=userInitiated) }
                runBlocking { withTimeout(5_000) { started.await() } }
                assertTrue(session.historyLoading);assertTrue(session.runtime?.canControl==true)
                if(newNavigation)ui.onNodeWithTag("send-message").assertIsNotEnabled() else ui.onNodeWithTag("send-message").assertIsEnabled()
                assertEquals("ready background draft",session.text);assertTrue(sent(fixture).isEmpty())
                release.complete(Unit);ui.waitUntil(5_000) { !session.historyLoading };ui.onNodeWithTag("send-message").assertIsEnabled()
            } finally { release.complete(Unit) }
        }
    }
    @Test fun networkReadRecoveryKeepsOriginalPostAndEditedDraftWithoutReplay() {
        ExecutionFixture(streaming=true).use { fixture ->
            val native=NativeCodexCommands { "" };val completed=java.util.concurrent.atomic.AtomicBoolean();val cancelled=java.util.concurrent.atomic.AtomicBoolean()
            val commands=object: CodexCommands by native {
                override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt = try {
                    native.send(client,intent).also { completed.set(true) }
                } catch(cancel: CancellationException) { cancelled.set(true);throw cancel }
            }
            ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(NativeHostReads { "" },commands) };connect(fixture)
            fixture.holdReplyResponse=true
            try {
                ui.runOnIdle { session.text="original network intent" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(fixture.replyStarted.await(5,TimeUnit.SECONDS));val original=sent(fixture).single();val id=original.getString("clientMessageId")
                val catalogs=fixture.server.requests.count { it.path=="/v1/workspace" };val histories=fixture.server.requests.count { it.path=="/v1/history" }
                ui.runOnIdle { session.text="new edited network draft";session.observeNetworkReadPath("post-path",true);session.observeNetworkReadPath("post-path-new") }
                ui.waitUntil(5_000) { fixture.server.requests.count { it.path=="/v1/workspace" }>catalogs&&fixture.server.requests.count { it.path=="/v1/history" }>histories }
                assertFalse("Only read jobs are replaced",cancelled.get());assertFalse(completed.get());assertEquals(1,sent(fixture).size)
                fixture.replyRelease.countDown();ui.waitUntil(10_000) { completed.get()&&session.transferStatus==null }
                assertFalse(cancelled.get());assertEquals("new edited network draft",session.text);assertEquals(id,sent(fixture).single().getString("clientMessageId"))
                assertEquals("original network intent",sent(fixture).single().getString("text"));assertTrue(session.operationRecords.any { it.id==id&&it.kind=="reply" })
            } finally { fixture.replyRelease.countDown() }
        }
    }
    @Test fun networkReadRecoveryQueriesOriginalUnknownReplyWithoutReplay() {
        ExecutionFixture().use { fixture ->
            fixture.loseReply=true;fixture.queryState="uncertain";connect(fixture)
            ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.text="unknown network original" };ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="reply"&&it.phase=="uncertain" }&&session.transferStatus==null }
            val id=session.operationRecords.first { it.kind=="reply" }.id
            ui.runOnIdle { session.text="unknown reply newer draft";fixture.queryState="submitted";session.observeNetworkReadPath("unknown-path",true);session.observeNetworkReadPath("unknown-new-path") }
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==id&&it.phase=="submitted" } }
            assertEquals(1,sent(fixture).size);assertEquals(id,sent(fixture).single().getString("clientMessageId"));assertEquals("unknown reply newer draft",session.text)
            val queries=fixture.server.requests.filter { it.path=="/v1/receipts" };assertTrue(queries.isNotEmpty());assertTrue(queries.all { it.query["id"]==id })
        }
    }
    @Test fun networkReadRecoveryRetainsBackgroundOriginalReceiptObserver() {
        ExecutionFixture().use { fixture ->
            fixture.additionalThread="other-thread";fixture.loseReply=true;fixture.queryState="uncertain";fixture.holdReplyReceipt=true;connect(fixture)
            ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.text="background original input" };ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="reply"&&it.phase=="uncertain" }&&session.transferStatus==null }
            val id=session.operationRecords.first { it.kind=="reply" }.id
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="other-thread" });session.text="other chat draft" }
            ui.waitUntil(5_000) { !session.historyLoading&&session.selectedThread=="other-thread" }
            assertTrue(fixture.replyQueryStarted.await(5,TimeUnit.SECONDS))
            ui.runOnIdle { fixture.holdReplyReceipt=false;fixture.queryState="submitted";session.observeNetworkReadPath("background-path",true);session.observeNetworkReadPath("background-path-new") }
            ui.waitUntil(6_000) { fixture.server.requests.count { it.path=="/v1/receipts" }>=2 }
            assertEquals("other-thread",session.selectedThread);assertEquals("other chat draft",session.text);assertEquals(1,sent(fixture).size)
            val queries=fixture.server.requests.filter { it.path=="/v1/receipts" };assertTrue(queries.all { it.query["id"]==id&&it.query["threadId"]=="fixture-thread" })
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="fixture-thread" }) }
            ui.waitUntil(5_000) { session.operationRecords.any { it.id==id&&it.phase=="submitted" } }
        }
    }
    @Test fun stagedProjectlessCreationUploadsExactMixedBytesOnce() {
        ExecutionFixture(staged=true).use { f -> connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="首条消息" }
            val one="中文\n".toByteArray(); val two=byteArrayOf(0,1,2,127,-1)
            main { session.addAttachment("说明.txt","text/plain",one); session.addAttachment("内容.bin","application/octet-stream",two) }
            shot("staged-mixed-draft")
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.selectedThread=="created-thread" && session.transferStatus==null && session.text.isEmpty() }
            val prepare=f.server.requests.single { it.path=="/v1/desktop/creations" && it.method=="POST" }
            val intent=JSONObject(String(prepare.body,Charsets.UTF_8)); assertTrue(intent.isNull("projectId")); assertEquals("首条消息",intent.getString("text")); assertEquals(2,intent.getJSONArray("attachments").length())
            val uploads=f.server.requests.filter { it.path=="/v1/desktop/creation-attachments" }; assertEquals(2,uploads.size); assertArrayEquals(one,uploads[0].body); assertArrayEquals(two,uploads[1].body)
            assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); assertTrue(sent(f).isEmpty()); shot("staged-mixed-confirmed")
        }
    }
    @Test fun acceptedOriginalReceiptReleasesOnlyItsWaitBeforeHeldHttpReturns() {
        ExecutionFixture().use { f ->
            val completed=java.util.concurrent.atomic.AtomicBoolean();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native {
                override suspend fun send(client: MobileClient,intent: ReplyIntent)=native.send(client,intent).also { completed.set(true) }
            }
            ui.runOnIdle { session.resetAcceptance(NativeHostReads { "" },commands) };connect(f)
            f.holdReplyResponse=true
            try {
                ui.runOnIdle { session.text="one original held HTTP input" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.runOnIdle { session.text="new independently edited draft" }
                ui.waitUntil(8_000) { session.operationRecords.any { it.id==id&&it.phase=="submitted" }&&session.transferStatus==null }
                assertFalse("Original HTTP is still pending when authoritative receipt releases this wait",completed.get())
                assertEquals("new independently edited draft",session.text);ui.onNodeWithTag("send-message").assertIsEnabled()
                assertEquals(1,sent(f).size);assertTrue(f.server.requests.filter { it.path=="/v1/receipts" }.all { it.query["id"]==id })
                f.replyRelease.countDown();ui.waitUntil(5_000) { completed.get() }
                assertEquals("new independently edited draft",session.text);assertNull(session.transferStatus);assertEquals(1,sent(f).size)
            } finally { f.replyRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun receivedAcknowledgementKeepsOriginalDraftAndWaitUntilExplicitDetach() {
        ExecutionFixture().use { f ->
            val completed=java.util.concurrent.atomic.AtomicBoolean();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native { override suspend fun send(client: MobileClient,intent: ReplyIntent)=native.send(client,intent).also { completed.set(true) } }
            ui.runOnIdle { session.resetAcceptance(NativeHostReads { "" },commands) }
            f.queryState="received";connect(f);f.holdReplyResponse=true
            try {
                ui.runOnIdle { session.text="original unaccepted held input" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.waitUntil(8_000) { session.operationRecords.any { it.id==id&&it.phase=="received" } }
                assertEquals("original unaccepted held input",session.text);assertNotNull(session.transferStatus)
                ui.onNodeWithTag("detach-operation-$id").performScrollTo().performClick()
                ui.waitUntil(5_000) { session.transferStatus==null&&session.operationRecords.any { it.id==id&&it.detached } }
                ui.runOnIdle { session.text="new draft after ending only local wait" }
                f.replyRelease.countDown();ui.waitUntil(5_000) { completed.get()&&!session.historyLoading }
                assertEquals("new draft after ending only local wait",session.text);assertEquals(1,sent(f).size)
            } finally { f.replyRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun originalFailedReceiptReleasesWaitAndPreservesItsUnsentDraft() {
        ExecutionFixture().use { f ->
            val completed=java.util.concurrent.atomic.AtomicBoolean();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native { override suspend fun send(client: MobileClient,intent: ReplyIntent)=native.send(client,intent).also { completed.set(true) } }
            ui.runOnIdle { session.resetAcceptance(NativeHostReads { "" },commands) }
            f.queryState="failed";connect(f);f.holdReplyResponse=true
            try {
                ui.runOnIdle { session.text="failed original held input" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.waitUntil(8_000) { session.operationRecords.any { it.id==id&&it.phase=="failed" }&&session.transferStatus==null }
                assertEquals("failed original held input",session.text);assertEquals(1,sent(f).size)
                f.replyRelease.countDown();ui.waitUntil(5_000) { completed.get()&&!session.historyLoading }
                assertEquals("failed original held input",session.text)
            } finally { f.replyRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun transportUnknownAfterReceivedKeepsOriginalDraftWithoutWrongTargetError() {
        ExecutionFixture().use { f ->
            val completed=java.util.concurrent.atomic.AtomicBoolean();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native {
                override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt = try { native.send(client,intent) } finally { completed.set(true) }
            }
            ui.runOnIdle { session.resetAcceptance(NativeHostReads { "" },commands) }
            f.queryState="received";connect(f);f.holdReplyResponse=true;f.loseReply=true
            try {
                ui.runOnIdle { session.text="received then transport unknown original" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.waitUntil(8_000) { session.operationRecords.any { it.id==id&&it.phase=="received" } }
                assertEquals("received then transport unknown original",session.text);assertFalse(completed.get())
                f.replyRelease.countDown();ui.waitUntil(5_000) { completed.get()&&session.transferStatus==null }
                assertNull("Local transport uncertainty may retain a valid earlier receipt; it is not a target mismatch",session.error)
                assertEquals("received then transport unknown original",session.text);assertEquals(1,sent(f).size)
                assertTrue(session.operationRecords.any { it.id==id&&it.phase in listOf("received","uncertain") })
            } finally { f.replyRelease.countDown();main { session.error=null;session.leaveForeground() } }
        }
    }
    @Test fun originalReceipt404RecoversIndependentlyWhileHistoryAndEventsAreStalled() {
        ExecutionFixture().use { f ->
            val event=CompletableDeferred<EventBatch>();val completed=java.util.concurrent.atomic.AtomicBoolean();val native=NativeCodexCommands { "" }
            val commands=object: CodexCommands by native {
                override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=event.await()
                override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt = try { native.send(client,intent) } finally { completed.set(true) }
            }
            ui.runOnIdle { session.resetAcceptance(NativeHostReads { "" },commands) };connect(f)
            f.missingReceipts.set(1);f.holdReplyResponse=true
            try {
                ui.runOnIdle { session.text="one original before receipt absence" };ui.onNodeWithTag("send-message").performClick()
                assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS));val id=sent(f).single().getString("clientMessageId")
                ui.runOnIdle { f.holdHistoryResponse=true;session.text="new draft during independent original query";session.loadHistory() }
                assertTrue(f.historyStarted.await(5,TimeUnit.SECONDS))
                ui.waitUntil(5_000) { f.missingReceiptResponses.get()==1 }
                ui.waitUntil(8_000) { session.operationRecords.any { it.id==id&&it.phase=="submitted" }&&session.transferStatus==null }
                ui.waitUntil(5_000) { session.canCompose&&!session.loading }
                assertTrue(session.historyLoading);assertFalse(completed.get());assertEquals("new draft during independent original query",session.text)
                ui.onNodeWithTag("send-message").assertIsEnabled()
                val queries=f.server.requests.filter { it.path=="/v1/receipts" }
                assertTrue(queries.size>=2);assertTrue(queries.all { it.query["id"]==id });assertEquals(1,sent(f).size)
                assertTrue(f.server.requests.none { it.path=="/v1/events" })
                f.replyRelease.countDown();f.historyRelease.countDown();ui.waitUntil(5_000) { completed.get()&&!session.historyLoading }
                assertEquals("new draft during independent original query",session.text)
            } finally { f.replyRelease.countDown();f.historyRelease.countDown();event.cancel();main { session.leaveForeground() } }
        }
    }
    @Test fun uncertainReplyQueryDoesNotReplayOrClearEditedBackDraft() {
        ExecutionFixture().use { f -> f.loseReply=true; f.queryState="uncertain"; connect(f)
            ui.runOnIdle { session.text="原内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="reply" && it.phase=="uncertain" } && session.transferStatus==null }
            val record=session.operationRecords.first { it.kind=="reply" }; val id=record.id
            ui.runOnIdle { session.queryOperation(record) }
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==id && it.phase=="uncertain" && it.result!=null } && session.transferStatus==null }
            assertFalse("A reply receipt cannot offer creation continuation",session.mayContinueCreation(session.operationRecords.first { it.id==id }))
            ui.runOnIdle { session.text="修改内容"; session.text="原内容" }; shot("reply-uncertain-preserved")
            f.queryState="submitted"
            ui.runOnIdle { session.queryOperation(record) }; ui.waitUntil(10_000) { session.operationRecords.any { it.id==id && it.phase=="submitted" } && session.transferStatus==null }
            assertEquals("原内容",session.text); assertEquals(1,sent(f).size); assertTrue(f.server.requests.filter { it.path=="/v1/receipts" }.all { it.query["id"]==id }); shot("late-reply-keeps-new-version")
        }
    }

    private fun openReplyRecords() {
        hideKeyboard();ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("reply-delivery-records").performClick()
        ui.onNodeWithTag("reply-record-list").assertIsDisplayed()
    }
    private fun recordShot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val run=InstrumentationRegistry.getArguments().getString("managementRunId") ?: error("Missing record acceptance run")
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val folder=File(i.targetContext.filesDir,"acceptance/P4-reply-records/$run").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
    }
    @Test fun detachedReplyRecordsShowOriginalBodyAttachmentAndIndependentReadOnlyQuery() {
        ExecutionFixture().use { f -> f.loseReply=true;f.queryState="uncertain";connect(f)
            val text="  原消息 😀\n不可变  ";val bytes="original attachment bytes".toByteArray()
            main { session.addAttachment("原文件 😀.txt","text/plain",bytes);session.text=text }
            val file=session.attachments.single().manifest
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null&&session.operationRecords.any { it.kind=="reply"&&it.phase=="uncertain" } }
            val original=session.operationRecords.single { it.kind=="reply" }
            ui.onNodeWithTag("detach-operation-${original.id}").performClick();ui.waitUntil(5_000) { session.operationRecords.any { it.id==original.id&&it.detached } }
            main { session.text="new draft before original record query" };openReplyRecords()
            ui.onNodeWithTag("reply-record-text-${original.id}").assertTextEquals(text)
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("reply-record-file-${original.id}-${file.id}").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("reply-record-file-${original.id}-${file.id}").assertTextEquals(file.name)
            f.holdReplyReceipt=true;f.queryState="submitted";val queries=f.server.requests.count { it.path=="/v1/receipts" }
            try {
                ui.onNodeWithTag("query-detached-reply-${original.id}").performClick();assertTrue(f.replyQueryStarted.await(5,TimeUnit.SECONDS))
                ui.waitUntil(5_000) { session.replyRecordQuerying(original) }
                main { session.queryReplyRecord(original,session.targetKey);session.text="edited while original GET is held" }
                assertNull(session.transferStatus);assertTrue(session.canCompose);assertEquals(queries+1,f.server.requests.count { it.path=="/v1/receipts" })
                ui.onNodeWithTag("query-detached-reply-${original.id}").assertIsNotEnabled();recordShot("original-record-query-held")
                f.replyQueryRelease.countDown();ui.waitUntil(5_000) { session.operationRecords.any { it.id==original.id&&it.phase=="submitted" }&&!session.replyRecordQuerying(original) }
                ui.onNodeWithTag("reply-record-state-${original.id}").assertTextEquals("已提交");recordShot("original-record-accepted")
                val current=session.operationRecords.single { it.id==original.id };assertTrue(current.detached);assertEquals(original.intentSha256,current.intentSha256)
                assertEquals("edited while original GET is held",session.text);assertEquals(1,sent(f).size)
                val upload=f.server.requests.single { it.path=="/v1/attachments" };assertArrayEquals(bytes,upload.body)
                ui.onNodeWithTag("sheet-done").performClick();ui.onNodeWithTag("send-message").assertIsEnabled()
                assertEquals(file.id,session.attachments.single().manifest.id)
            }finally { f.replyQueryRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun detachedReplyRecordReadErrorsStayWithOriginalRecordAndDoNotLockNewDraft() {
        ExecutionFixture().use { f -> f.loseReply=true;f.queryState="uncertain";connect(f)
            main { session.text="original query error record" };ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="reply"&&it.phase=="uncertain" }&&session.transferStatus==null }
            val original=session.operationRecords.single { it.kind=="reply" };main { session.detachOperation(original) }
            ui.waitUntil(5_000) { session.operationRecords.any { it.id==original.id&&it.detached } };main { session.text="new draft remains available" }
            openReplyRecords();f.replyQueryFailureStatus=503
            ui.onNodeWithTag("query-detached-reply-${original.id}").performClick()
            ui.waitUntil(5_000) { session.replyRecordQueryError(original)!=null&&!session.replyRecordQuerying(original) }
            ui.onNodeWithTag("reply-record-query-error-${original.id}").assertIsDisplayed();assertNull(session.error);assertNull(session.transferStatus)
            assertEquals("new draft remains available",session.text);recordShot("record-only-read-error")
            f.holdReplyReceipt=true
            try {
                ui.onNodeWithTag("query-detached-reply-${original.id}").performClick();assertTrue(f.replyQueryStarted.await(5,TimeUnit.SECONDS))
                main { session.newChat();session.text="draft at a different target" }
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("reply-record-list").fetchSemanticsNodes().isEmpty() }
                f.replyQueryRelease.countDown();ui.waitUntil(5_000) { !session.replyRecordQuerying(original) }
                assertNull(session.error);assertEquals("draft at a different target",session.text);assertEquals(1,sent(f).size)
                assertTrue(f.server.requests.none { it.path in listOf("/v1/desktop/creations","/v1/threads")&&it.method=="POST" })
            }finally { f.replyQueryRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun detachedReplyRecordListRetainsOldestBeyondTwelveAndQueriesOnlyItsOriginalId() {
        ExecutionFixture().use { f -> f.loseReply=true;f.queryState="uncertain";connect(f)
            val originals=mutableListOf<SavedOperation>()
            repeat(13) { index ->
                val text="ORIGINAL_RECORD_$index";main { session.text=text };ui.onNodeWithTag("send-message").performClick()
                ui.waitUntil(10_000) { sent(f).size==index+1&&session.transferStatus==null&&session.operationRecords.any { !it.detached&&it.kind=="reply"&&it.phase=="uncertain" } }
                val record=session.operationRecords.single { !it.detached&&it.kind=="reply" };originals.add(record);main { session.detachOperation(record) }
                ui.waitUntil(5_000) { session.operationRecords.any { it.id==record.id&&it.detached } }
            }
            val first=originals.first();assertEquals(13,session.detachedReplies.size);main { session.text="independent draft after thirteen originals" }
            openReplyRecords();ui.onNodeWithTag("reply-record-list").performScrollToNode(hasTestTag("query-detached-reply-${first.id}"))
            ui.onNodeWithTag("reply-record-text-${first.id}").assertTextEquals("ORIGINAL_RECORD_0")
            val before=f.server.requests.count { it.path=="/v1/receipts" };f.queryState="completed"
            ui.onNodeWithTag("query-detached-reply-${first.id}").performClick()
            ui.waitUntil(5_000) { session.operationRecords.any { it.id==first.id&&it.phase=="completed" }&&!session.replyRecordQuerying(first) }
            val queries=f.server.requests.filter { it.path=="/v1/receipts" }.drop(before);assertEquals(1,queries.size);assertEquals(first.id,queries.single().query["id"])
            assertEquals(13,sent(f).size);assertEquals("independent draft after thirteen originals",session.text);recordShot("oldest-of-thirteen-original-records")
            assertTrue(originals.all { original -> session.operationRecords.any { it.id==original.id&&it.intentSha256==original.intentSha256&&it.detached } })
            main { session.leaveForeground() }
        }
    }

    @Test fun detachedReplyRecordStatusMatchesOriginalReceiptAndFailureTurn() {
        ExecutionFixture().use { f -> f.loseReply=true;connect(f)
            val states=listOf(Triple("queued",null,"已排队"),Triple("submitted",null,"已提交"),Triple("failed",null,"消息提交失败"),
                Triple("failed","original-failed-turn","任务执行失败"),Triple("cancelled","original-cancelled-turn","已停止"),
                Triple("interrupted","original-interrupted-turn","已停止"),Triple("completed","original-completed-turn","已完成"))
            val originals=mutableListOf<SavedOperation>()
            states.forEachIndexed { index,(state,turn,label) ->
                f.queryState="uncertain";f.turn=null;main { session.text="original receipt status labels $index" }
                ui.onNodeWithTag("send-message").performClick()
                ui.waitUntil(10_000) { sent(f).size==index+1&&session.operationRecords.any { it.kind=="reply"&&!it.detached&&it.phase=="uncertain" }&&session.transferStatus==null }
                val original=session.operationRecords.single { it.kind=="reply"&&!it.detached };originals.add(original);main { session.detachOperation(original) }
                ui.waitUntil(5_000) { session.operationRecords.any { it.id==original.id&&it.detached } };openReplyRecords()
                f.queryState=state;f.turn=turn;val before=f.server.requests.count { it.path=="/v1/receipts" }
                ui.onNodeWithTag("query-detached-reply-${original.id}").performClick()
                ui.waitUntil(5_000) { session.operationRecords.any { it.id==original.id&&it.phase==state }&&!session.replyRecordQuerying(original) }
                ui.onNodeWithTag("reply-record-state-${original.id}").assertTextEquals(label)
                val queries=f.server.requests.filter { it.path=="/v1/receipts" }.drop(before);assertEquals(1,queries.size);assertEquals(original.id,queries.single().query["id"])
                if(label=="任务执行失败")recordShot("record-failed-turn-title")
                ui.onNodeWithTag("sheet-done").performClick()
            }
            assertEquals(7,sent(f).size);assertTrue(originals.all { original -> session.operationRecords.any { it.id==original.id&&it.detached&&it.intentSha256==original.intentSha256 } })
            main { session.leaveForeground() }
        }
    }

    private fun composerShot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val run=InstrumentationRegistry.getArguments().getString("managementRunId") ?: error("Missing composer acceptance run")
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val folder=File(i.targetContext.filesDir,"acceptance/P4-composer/$run").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
    }
    @Test fun runningComposerUsesActualTextAndAttachmentsWithoutWriting() {
        ExecutionFixture().use { f -> f.state="running";f.turn="composer-original-run";connect(f)
            ui.onNodeWithTag("reply-mode").assertDoesNotExist()
            main { session.text="\u00a0\u2003\n\t" };ui.onNodeWithTag("reply-mode").assertDoesNotExist()
            main { session.text="中文 😀 running input" };ui.onNodeWithTag("reply-mode").assertIsDisplayed();composerShot("composer-text-modes")
            main { session.text="";session.addAttachment("file-only.txt","text/plain","retained only".toByteArray()) }
            ui.onNodeWithTag("reply-mode").assertIsDisplayed();composerShot("composer-file-only-modes")
            val file=session.attachments.single().manifest.id;main { session.removeAttachment(file) };ui.onNodeWithTag("reply-mode").assertDoesNotExist()
            main { session.text="same-target draft" };f.state="idle";main { session.loadHistory() }
            ui.waitUntil(5_000) { !session.historyLoading&&session.runtime?.state=="idle" };ui.onNodeWithTag("reply-mode").assertDoesNotExist()
            f.state="running";f.turn=null;main { session.loadHistory() }
            ui.waitUntil(5_000) { !session.historyLoading&&session.runtime?.state=="running"&&session.runtime?.turnId==null }
            ui.onNodeWithTag("reply-mode").assertDoesNotExist();assertEquals("same-target draft",session.text)
            assertTrue(f.server.requests.none { it.method=="POST" });main { session.leaveForeground() }
        }
    }
    @Test fun unavailableClientQueueKeepsIntentUntilExplicitOriginalTurnSteerChoice() {
        ExecutionFixture().use { f -> f.state="running";f.turn="composer-original-run";f.queueAvailable=false;connect(f)
            main { session.text="explicit original turn addition" }
            assertEquals("queue",session.replyMode);ui.onNodeWithTag("send-message").assertIsNotEnabled()
            ui.onNodeWithTag("client-queue-unavailable").assertTextEquals("客户端队列不可用，消息未提交。可保留草稿，或自行选择立即追加。")
            ui.onNodeWithTag("reply-mode-queue").assertIsSelected().assertIsNotEnabled()
            assertTrue(f.server.requests.none { it.method=="POST" });composerShot("composer-queue-unavailable")
            ui.onNodeWithTag("reply-mode-steer").performClick();assertEquals("steer",session.replyMode)
            ui.onNodeWithTag("reply-mode-steer").assertIsSelected();ui.onNodeWithTag("client-queue-unavailable").assertDoesNotExist()
            ui.onNodeWithTag("send-message").assertIsEnabled();assertTrue(f.server.requests.none { it.method=="POST" });composerShot("composer-explicit-steer")
            ui.onNodeWithTag("send-message").performClick();ui.waitUntil(5_000) { sent(f).size==1&&session.transferStatus==null }
            val request=sent(f).single();assertEquals("steer",request.getString("mode"));assertEquals("composer-original-run",request.getString("expectedTurnId"))
            assertEquals("explicit original turn addition",request.getString("text"));main { session.leaveForeground() }
        }
    }

    @Test fun replyModeCapabilityLossAndStaleTargetCallbackNeverRemapDraftIntent() {
        ExecutionFixture().use { f -> f.state="running";f.turn="composer-original-run";connect(f)
            main { session.text="kept captured mode draft" };ui.onNodeWithTag("reply-mode-steer").performClick();assertEquals("steer",session.replyMode)
            f.steerAvailable=false;main { session.refresh();session.loadHistory() }
            ui.waitUntil(5_000) { !session.loading&&!session.historyLoading&&session.replyModes==listOf("queue") }
            assertEquals("steer",session.replyMode);ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("reply-mode-steer").assertDoesNotExist()
            ui.onNodeWithTag("reply-mode-queue").assertIsNotSelected();composerShot("composer-no-intent-remap")
            ui.onNodeWithTag("reply-mode-queue").performClick();assertEquals("queue",session.replyMode);ui.onNodeWithTag("send-message").assertIsEnabled()
            f.steerAvailable=true;main { session.refresh() };ui.waitUntil(5_000) { !session.loading&&"steer" in session.replyModes }
            val origin=session.targetKey;val thread=session.threads.first()
            main { session.newChat();session.text="new target draft";session.setReplyMode("steer",origin) }
            assertNotEquals(origin,session.targetKey);assertEquals("queue",session.replyMode);assertEquals("new target draft",session.text)
            main { session.selectThread(thread) };ui.waitUntil(5_000) { !session.historyLoading&&session.targetKey==origin }
            assertEquals("queue",session.replyMode);assertEquals("kept captured mode draft",session.text)
            assertTrue(f.server.requests.none { it.method=="POST" });main { session.leaveForeground() }
        }
    }
    @Test fun pendingOriginalDisablesModeEditingAndKeepsCapturedMode() {
        ExecutionFixture().use { f -> f.state="running";f.turn="composer-original-run";f.holdReplyResponse=true;f.queryState="received";connect(f)
            main { session.text="immutable steer original" };ui.onNodeWithTag("reply-mode-steer").performClick();val origin=session.targetKey
            try {
                ui.onNodeWithTag("send-message").performClick();assertTrue(f.replyStarted.await(5,TimeUnit.SECONDS))
                ui.onNodeWithTag("reply-mode-steer").assertIsSelected().assertIsNotEnabled();ui.onNodeWithTag("reply-mode-queue").assertIsNotEnabled()
                main { session.setReplyMode("queue",origin) };assertEquals("steer",session.replyMode);composerShot("composer-pending-mode-disabled")
                f.replyRelease.countDown();ui.waitUntil(5_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="reply"&&it.phase=="submitted" } }
                val request=sent(f).single();assertEquals("steer",request.getString("mode"));assertEquals("composer-original-run",request.getString("expectedTurnId"))
                main { session.text="new editable draft" };ui.onNodeWithTag("reply-mode-queue").performClick()
                assertEquals("queue",session.replyMode);assertEquals("steer",sent(f).single().getString("mode"));assertEquals(1,sent(f).size)
            }finally { f.replyRelease.countDown();main { session.leaveForeground() } }
        }
    }
    @Test fun detachedLateCreationKeepsNewDraftAndOriginalManifest() {
        ExecutionFixture(staged=true).use { f -> f.submitPhase="outcome-unknown"; connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="第一份内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="creation" && it.phase=="outcome-unknown" } && session.transferStatus==null }
            val original=session.operationRecords.first { it.kind=="creation" }
            ui.runOnIdle { session.detachOperation(original) }; ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.detached } }
            ui.runOnIdle { session.text="新的可编辑草稿" }; f.submitPhase="confirmed"
            ui.runOnIdle { session.queryOperation(session.operationRecords.first { it.id==original.id }) }
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.phase=="confirmed" } && session.transferStatus==null }
            assertEquals("",session.selectedThread); assertEquals("新的可编辑草稿",session.text); assertEquals("第一份内容",JSONObject(session.operationRecords.first { it.id==original.id }.intent).getString("text")); assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" })
            val beforeQueries=f.server.requests.count { it.path=="/v1/desktop/creation-receipts" }
            ui.onNodeWithTag("operation-${original.id}").assertDoesNotExist();ui.onNodeWithTag("chat-menu").performClick()
            ui.onNodeWithTag("query-detached-${original.id}").performScrollTo().assertIsDisplayed().performClick()
            ui.waitUntil(10_000) { f.server.requests.count { it.path=="/v1/desktop/creation-receipts" }>beforeQueries&&session.transferStatus==null }
            assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" });shot("detached-creation-keeps-draft")
            ui.runOnIdle { session.selectThread(session.threads.first { it.id=="fixture-thread" }) };ui.waitUntil(10_000) { !session.historyLoading }
            assertTrue(session.detachedCreations.isEmpty());ui.onNodeWithTag("operation-${original.id}").assertDoesNotExist()
        }
    }
    @Test fun desktopOwnerIsOpenedBeforeReplyAndHistoryCannotPretendControl() {
        ExecutionFixture(controlled=false).use { f -> f.openingQueries=3; connect(f)
            assertFalse(session.runtime!!.canControl==true); ui.runOnIdle { session.text="主动打开后回复" }; ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            val paths=f.server.requests.filter { it.method=="POST" }.map { it.path }; assertEquals(listOf("/v1/desktop/open","/v1/replies"),paths)
            assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/open" })
            val status=f.server.requests.filter { it.path=="/v1/desktop/open-status" }
            assertEquals(3,status.size); assertEquals(1,status.map { it.query["id"] }.distinct().size)
            assertTrue(status.all { it.method=="GET" && it.query["threadId"]=="fixture-thread" }); shot("desktop-open-before-reply")
            ui.runOnIdle { session.leaveForeground(); session.text="离线草稿" }; ui.onNodeWithTag("send-message").assertIsNotEnabled()
            assertEquals(1,sent(f).size); shot("cached-control-cannot-write")
        }
    }
    @Test fun queueSteerStopAndCancellationUseExactCurrentTurn() {
        ExecutionFixture(streaming=true).use { f -> f.state="running"; f.turn="fixture-turn"; connect(f)
            ui.runOnIdle { session.text="排队内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }; assertEquals("queue",sent(f)[0].getString("mode")); assertEquals("fixture-turn",sent(f)[0].getString("expectedTurnId"))
            ui.waitUntil(10_000) { session.queuedReplies.isNotEmpty() && !session.historyLoading }; val queued=session.queuedReplies.first(); ui.runOnIdle { session.cancelQueue(queued,session.targetKey) }; ui.waitUntil(10_000) { session.transferStatus==null && f.server.requests.any { it.path=="/v1/queue/cancel" } }; assertEquals(sent(f)[0].getString("clientMessageId"),JSONObject(String(f.server.requests.first { it.path=="/v1/queue/cancel" }.body)).getString("clientMessageId"))
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==queued.operationId && it.phase=="cancelled" } && session.canCompose }
            assertTrue(session.activeOperationRecords.none { it.id==queued.operationId });assertTrue(session.selectedClient.online)
            ui.runOnIdle { session.setReplyMode("steer"); session.text="调整当前任务" }; ui.waitUntil(10_000) { session.decision.enabled && session.decision.action=="send" }; ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==2 && session.transferStatus==null }; assertEquals("steer",sent(f)[1].getString("mode")); assertFalse(sent(f)[1].has("model")); assertFalse(session.canConfigureGeneration)
            ui.runOnIdle { session.text="" }; ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { f.server.requests.any { it.path=="/v1/stop" } && !session.running }; assertEquals("fixture-turn",JSONObject(String(f.server.requests.first { it.path=="/v1/stop" }.body)).getString("turnId"))
            val interruptedId=sent(f)[1].getString("clientMessageId")
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==interruptedId && it.phase=="interrupted" } }
            assertTrue(session.activeOperationRecords.none { it.id==interruptedId });ui.onNodeWithTag("operation-$interruptedId").assertDoesNotExist()
            shot("queue-steer-stop")
        }
    }
    @Test fun asyncQuestionRequiresAnAnswerAndSupportsSecretCustomReply() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); connect(f)
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("question-option-q-One").performClick()
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("合成秘密回复")
            shot("async-secret-question",keepKeyboard=true)
            val field=ui.onNodeWithTag("question-custom-q").fetchSemanticsNode().boundsInRoot
            val screen=android.util.DisplayMetrics().also { ui.activity.windowManager.defaultDisplay.getRealMetrics(it) }
            val ime=androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            assertTrue("Focused question must stay fully above the native keyboard",field.bottom<=screen.heightPixels-ime+2)
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { f.server.requests.any { it.path=="/v1/approvals" } }
            val request=JSONObject(String(f.server.requests.first { it.path=="/v1/approvals" }.body)); assertEquals("approval-id",request.getString("approvalId")); assertEquals(listOf("合成秘密回复"),request.getJSONObject("answers").getJSONArray("q").strings())
            assertEquals("fixture-thread",request.getString("threadId")); assertEquals("approval-turn",request.getString("turnId"))
        }
    }
    @Test fun asyncDeckPreservesDraftsAndAdvancesOnlyTheResolvedQuestion() {
        ExecutionFixture().use { f ->
            f.approvals=JSONArray().put(question("first","q1",false)).put(question("second","q2",false)).put(question("third","q3",false)); connect(f)
            ui.onNodeWithTag("async-question-sheet").assertIsDisplayed()
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 3")
            ui.onNodeWithTag("async-question-prev").assertIsNotEnabled()
            ui.onNodeWithTag("question-option-q1-One").performClick()
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("question-custom-q2").performScrollTo().performTextInput("第二题草稿")
            hideKeyboard(); ui.onNodeWithTag("async-question-prev").performClick()
            ui.onNodeWithTag("question-option-q1-One").assertIsSelected()
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.onNodeWithTag("open-async-questions").performClick()
            ui.onNodeWithTag("async-question-next").performClick()
            ui.onNodeWithTag("question-custom-q2").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("第二题草稿")))
            shot("async-deck-retained-draft")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.size==2 && !session.isAnswering(session.runtime!!.approvals.first()) }
            ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
            ui.onNodeWithTag("question-title-q3").assertIsDisplayed(); shot("async-deck-advanced-after-answer")
            ui.onNodeWithTag("skip-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.size==1 }
            ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 1")
            ui.onNodeWithTag("question-option-q1-One").assertIsSelected()
            ui.onNodeWithTag("answer-question").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.runtime?.approvals?.isEmpty()==true }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            val requests=f.server.requests.filter { it.path=="/v1/approvals" }.map { JSONObject(String(it.body)) }
            assertEquals(listOf("second","third","first"),requests.map { it.getString("approvalId") })
            assertEquals(listOf("第二题草稿"),requests[0].getJSONObject("answers").getJSONArray("q2").strings())
            assertEquals("skip",requests[1].getString("decision"))
            assertEquals(listOf("One"),requests[2].getJSONObject("answers").getJSONArray("q1").strings())
        }
    }
    @Test fun asyncDraftCannotSurviveAnApprovalTurnOrTargetReplacement() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); connect(f)
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("旧任务秘密")
            hideKeyboard(); f.approvals=JSONArray().put(question().put("turnId","new-approval-turn"))
            ui.runOnIdle { session.loadHistory() }; ui.waitUntil(10_000) { session.runtime?.approvals?.singleOrNull()?.turnId=="new-approval-turn" }
            ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("新任务草稿")
            hideKeyboard(); ui.runOnIdle { session.newChat() }
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
            ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(10_000) { !session.historyLoading && session.runtime?.approvals?.isNotEmpty()==true }
            ui.onNodeWithTag("question-custom-q").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            ui.runOnIdle { session.changeAppearance("dark") }
            assertTrue(f.server.requests.none { it.path=="/v1/approvals" }); shot("async-deck-target-and-turn-isolated")
        }
    }
    @Test fun planActionCarriesExpectedPlanWithoutClearingComposerDraft() {
        ExecutionFixture().use { f -> f.proposed=JSONObject().put("id","plan-id").put("turnId","old-turn").put("text","合成计划正文"); connect(f)
            ui.runOnIdle { session.text="另一份草稿" }
            ui.onNodeWithTag("implement-plan").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            assertEquals("PLEASE IMPLEMENT THIS PLAN:\n合成计划正文",sent(f).single().getString("text")); assertEquals("plan-id",sent(f).single().getString("expectedPlanId")); assertEquals("default",sent(f).single().getString("collaborationMode")); assertEquals("另一份草稿",session.text); shot("plan-preserves-composer-draft")
        }
    }
    @Test fun runtimeChangeDuringFreshAuthorityCheckProducesNoCommand() {
        ExecutionFixture().use { f -> connect(f)
            val original=checkNotNull(session.selectedClient.scope);f.runtimeId="different-runtime"
            ui.runOnIdle { session.text="目标已变化" };ui.onNodeWithTag("send-message").performClick()
            // A still-current foreground owner proof can dispatch its original scoped HTTP request.
            // The Host must reject that old target before any execution; background reads may fence it first.
            ui.waitUntil(10_000) { (session.error!=null || !session.selectedClient.online) && session.transferStatus==null }
            if(!session.selectedClient.online)assertFalse(session.canCompose);assertEquals(original,session.selectedClient.scope)
            val writes=f.server.requests.filter { it.method=="POST" };assertTrue(writes.size<=1)
            writes.forEach { assertEquals(CodexCommandCodec.target(original).toString(),JSONObject(it.query.getValue("target")).toString()) }
            assertEquals(writes.size,f.rejectedTargetWrites.get());assertEquals(0,f.acceptedReplyCount)
            assertEquals("目标已变化",session.text);shot("runtime-change-refuses-command")
        }
    }
    @Test fun endingActiveQueryDoesNotReleaseOrReplaceANewerSubmit() {
        ExecutionFixture(staged=true).use { f -> f.submitPhase="outcome-unknown"; connect(f,false)
            ui.runOnIdle { session.newChat(); session.text="旧提交" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.operationRecords.any { it.kind=="creation" && it.phase=="outcome-unknown" } && session.transferStatus==null }
            val original=session.operationRecords.first { it.kind=="creation" }; f.blockedOperation=original.id
            ui.runOnIdle { session.queryOperation(original) }; assertTrue(f.queryStarted.await(5,TimeUnit.SECONDS))
            ui.runOnIdle { session.detachOperation(original) }; ui.waitUntil(10_000) { session.transferStatus==null && session.operationRecords.any { it.id==original.id && it.detached } }
            ui.runOnIdle { session.text="新提交" }; ui.waitUntil(10_000) { session.decision.enabled }; ui.onNodeWithTag("send-message").performClick()
            assertTrue(f.secondSubmitStarted.await(5,TimeUnit.SECONDS)); f.queryRelease.countDown()
            ui.waitUntil(10_000) { session.operationRecords.any { it.id==original.id && it.detached && it.phase=="confirmed" } }
            assertNotNull(session.transferStatus); assertEquals("新提交",session.text); assertEquals("",session.selectedThread); assertNull(session.error)
            assertEquals(2,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); shot("late-query-cannot-release-new-submit")
            f.secondSubmitRelease.countDown(); ui.waitUntil(10_000) { session.transferStatus==null }
        }
    }
    @Test fun generationSheetDefaultsFollowRuntimeAndStayWithinFullClient() {
        ExecutionFixture().use { f -> connect(f)
            ui.waitUntil(10_000) { session.models.isNotEmpty() }
            ui.onNodeWithTag("generation-settings").assertIsDisplayed().performClick()
            ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("sheet-generation").assertIsDisplayed();true }.getOrDefault(false) }
            ui.onNodeWithTag("model-fixture-model").performClick()
            ui.onNodeWithTag("effort-high").performScrollTo().performClick()
            ui.onNodeWithTag("speed-fast").performScrollTo().performClick()
            ui.onNodeWithTag("permission-guardian-approvals").performScrollTo().performClick()
            shot("generation-settings-permissions")
            ui.onNodeWithTag("sheet-done").performClick()
            ui.runOnIdle { session.newChat() }
            assertEquals(GenerationSettings("fixture-model","high","guardian-approvals","fast"),session.generationSettings)
            ui.runOnIdle { session.text=" "; session.newChat() }
            assertEquals(" ",session.text)
            assertEquals("fixture-model",session.generationSettings.model)
            ExecutionFixture().use { other -> connect(other,false); ui.runOnIdle { session.newChat() }; assertEquals(GenerationSettings(),session.generationSettings) }
            shot("generation-defaults-isolated")
        }
    }
    @Test fun delayedPickerResultCannotAttachToAnotherSelection() {
        ExecutionFixture().use { f -> connect(f)
            lateinit var original: AttachmentImportDestination;main { original=session.attachmentImportDestination() }
            ui.runOnIdle { session.newChat(); session.text="新位置草稿" }
            main { session.addAttachment("旧选择.txt","text/plain","old".toByteArray(),original) }
            assertTrue(session.attachments.isEmpty()); assertEquals("新位置草稿",session.text)
            lateinit var current: AttachmentImportDestination;main { current=session.attachmentImportDestination() }; ui.runOnIdle { session.text="当前编辑" }
            main { session.addAttachment("正确选择.txt","text/plain","exact".toByteArray(),current) }
            assertEquals("正确选择.txt",session.attachments.single().manifest.name); assertEquals("当前编辑",session.text); shot("picker-target-isolated")
        }
    }
    @Test fun cachedQuestionAndPlanCannotSendAfterConnectionBecomesStale() {
        ExecutionFixture().use { f -> f.approvals=JSONArray().put(question()); f.proposed=JSONObject().put("id","plan-id").put("turnId","old-turn").put("text","旧计划"); connect(f)
            ui.runOnIdle { session.leaveForeground() }
            assertFalse(session.canAnswer(session.runtime!!.approvals.single())); assertFalse(session.canRespondToPlan(session.runtime!!.proposedPlan!!))
            ui.onNodeWithTag("answer-question").assertIsNotEnabled()
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("implement-plan").assertIsNotEnabled()
            assertTrue(f.server.requests.none { it.method=="POST" }); shot("cached-approval-plan-disabled")
        }
    }
    @Test fun eventsStreamRepairAndReadinessNeverGrantCachedControl() {
        ExecutionFixture(streaming=true).use { f -> connect(f)
            f.streamText="第一段流式正文"; f.eventCursor=1
            ui.waitUntil(10_000) { session.messages.any { it.text=="第一段流式正文" } }; shot("streaming-partial-native")
            f.streamText="完整流式正文"; f.eventCursor=2
            ui.waitUntil(10_000) { session.messages.any { it.text=="完整流式正文" } }; shot("streaming-complete-native")
            f.eventReady=false
            ui.waitUntil(10_000) { !session.canCompose }; ui.runOnIdle { session.text="断线草稿" }; ui.onNodeWithTag("send-message").assertIsNotEnabled()
            f.eventReady=true; f.eventCursor=3
            ui.waitUntil(10_000) { f.server.requests.count { it.path=="/v1/events" }>=4 }
            assertFalse(session.canCompose)
            val historyReads=f.server.requests.count { it.path=="/v1/history" }
            f.streamText="重置后的准确快照";f.eventEpoch="restarted-epoch"; f.eventCursor=1
            ui.waitUntil(10_000) { session.canCompose && !session.historyLoading && session.messages.any { it.text=="重置后的准确快照" } }
            assertEquals("Authoritative reset snapshot does not need another history GET",historyReads,f.server.requests.count { it.path=="/v1/history" })
            assertEquals("断线草稿",session.text); assertTrue(f.server.requests.none { it.method=="POST" }); shot("event-repair-preserves-draft")
            ui.runOnIdle { session.leaveForeground() }; assertFalse(session.canCompose)
        }
    }
    @Test fun emptyProjectFirstInputRetainsExactProjectAndSettings() {
        ExecutionFixture(staged=true).use { f -> connect(f,false)
            ui.runOnIdle { session.newChat("empty-project"); session.text="空项目首条"; session.configure(GenerationSettings("fixture-model","high","read-only","standard")) }
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(10_000) { session.selectedThread=="created-thread" && session.transferStatus==null }
            val data=JSONObject(String(f.server.requests.single { it.path=="/v1/desktop/creations" && it.method=="POST" }.body))
            assertEquals("empty-project",data.getString("projectId")); assertEquals("high",data.getString("effort")); assertEquals("空项目首条",data.getString("text"))
            assertEquals("empty-project",session.threads.first { it.id==session.selectedThread }.projectId); assertEquals(1,f.server.requests.count { it.path=="/v1/desktop/creations/submit" }); shot("empty-project-first-input")
        }
    }
    @Test fun explicitRestartUsesNewOperationAndPreservesComposer() {
        ExecutionFixture().use { f -> f.state="interrupted"; connect(f)
            ui.onNodeWithTag("resume-task").performTouchInput { longClick() }; ui.onNodeWithTag("action-restart").performClick()
            ui.waitUntil(10_000) { sent(f).size==1 && session.transferStatus==null }
            assertEquals(com.agenthub.core.MobileRules.runAgainText(true),sent(f).single().getString("text")); assertEquals("next-turn",sent(f).single().getString("mode")); assertFalse(sent(f).single().has("model"))
            f.state="idle";ui.runOnIdle { session.loadHistory(userInitiated=true) }
            ui.waitUntil(10_000) { !session.historyLoading && session.decision.action=="send" && session.canRunAgain }
            ui.onNodeWithTag("send-message").assertIsNotEnabled()
            ui.onNodeWithTag("send-message").performTouchInput { longClick() };ui.onNodeWithTag("action-restart").assertIsDisplayed().performClick()
            ui.waitUntil(10_000) { sent(f).size==2 && session.transferStatus==null }
            assertNotEquals(sent(f)[0].getString("clientMessageId"),sent(f)[1].getString("clientMessageId"))
            assertEquals(com.agenthub.core.MobileRules.runAgainText(true),sent(f)[1].getString("text"))
            shot("explicit-restart-new-operation")
        }
    }
    private fun question(id: String="approval-id",questionId: String="q",secret: Boolean=true)=JSONObject().put("id",id).put("threadId","fixture-thread").put("turnId","approval-turn").put("kind","question").put("title","合成问题").put("detail","").put("interactionMode","async").put("decisions",JSONArray()).put("questions",JSONArray().put(JSONObject().put("id",questionId).put("question","请选择或输入回复").put("custom",true).put("isSecret",secret).put("options",JSONArray().put(JSONObject().put("label","One").put("description","选项说明")))))
}

internal class ExecutionFixture(val staged: Boolean=false,controlled: Boolean=true,val streaming: Boolean=false,val applicationProtocol: Boolean=false): java.io.Closeable {
    @Volatile var controlled=controlled
    @Volatile var openingQueries=0
    private var opening: JSONObject?=null
    @Volatile var runtimeId="fixture-runtime-"+UUID.randomUUID().toString()
    @Volatile var state="idle"
    @Volatile var computerName="合成电脑"
    @Volatile var projectName="空项目"
    @Volatile var threadTitle="合成聊天"
    @Volatile var modelName="合成模型"
    @Volatile var queueAvailable=true
    @Volatile var steerAvailable=true
    @Volatile var turn: String?=null
    @Volatile var additionalThread: String?=null
    @Volatile var holdReplyReceipt=false
    @Volatile var replyQueryFailureStatus: Int?=null
    private val originalReplies=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
    val rejectedTargetWrites=java.util.concurrent.atomic.AtomicInteger()
    val acceptedReplyCount get()=originalReplies.size
    val replyQueryStarted=CountDownLatch(1);val replyQueryRelease=CountDownLatch(1)
    @Volatile var loseReply=false
    @Volatile var holdApprovalResponse=false
    @Volatile var approvalFailureStatus: Int?=null
    val approvalStarted=CountDownLatch(1);val approvalRelease=CountDownLatch(1)
    @Volatile var holdHistoryResponse=false
    val historyStarted=CountDownLatch(1);val historyRelease=CountDownLatch(1)
    val missingReceipts=java.util.concurrent.atomic.AtomicInteger();val missingReceiptResponses=java.util.concurrent.atomic.AtomicInteger()
    @Volatile var holdReplyResponse=false
    val replyStarted=CountDownLatch(1);val replyRelease=CountDownLatch(1)
    @Volatile var queryState="submitted"
    @Volatile var submitPhase="confirmed"
    @Volatile var approvals=JSONArray()
    @Volatile var proposed: JSONObject?=null
    @Volatile var planSteps=JSONArray()
    @Volatile var planMessages=JSONArray()
    @Volatile var streamText: String?=null
    @Volatile var eventEpoch="fixture-epoch"
    @Volatile var eventCursor=0L
    @Volatile var eventReady=true
    private var reply: JSONObject?=null
    private var cancelledReply: JSONObject?=null
    @Volatile private var creation: JSONObject?=null
    private val creations=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
    @Volatile var blockedOperation: String?=null
    val queryStarted=CountDownLatch(1); val queryRelease=CountDownLatch(1)
    val secondSubmitStarted=CountDownLatch(1); val secondSubmitRelease=CountDownLatch(1)
    private val uploaded=mutableListOf<String>()
    val server=CommandFixture(::response)
    private fun target()=JSONObject().put("agentId","codex").put("hostId","fixture-host").put("workspaceId","fixture-workspace").put("runtimeId",runtimeId)
    private fun thread(id: String="fixture-thread")=JSONObject().put("id",id).put("hostId","fixture-host").put("title",if(id=="created-thread") "新建合成聊天" else threadTitle).put("state",state).put("projectId",if(id=="created-thread") creation?.get("projectId") ?: JSONObject.NULL else JSONObject.NULL)
    private fun workspace()=JSONObject().put("hostId","fixture-host").put("name",computerName).put("connectionMode","desktop-runtime").put("readTarget",target()).put("projects",JSONArray().put(JSONObject().put("id","empty-project").put("hostId","fixture-host").put("name",projectName).put("roots",JSONArray()))).put("threads",JSONArray().put(thread()).apply { additionalThread?.let { put(thread(it)) };if(creations.isNotEmpty() && submitPhase=="confirmed") put(thread("created-thread")) }).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","desktop:open","desktop:creations","desktop:creations:projectless","commands:target-bound:v1","queue","steer","stop","approvals","plan-mode","generation-settings","generation-speed","models:read").filter { (it!="queue"||queueAvailable)&&(it!="steer"||steerAvailable) }+if(streaming) listOf("events") else emptyList()))
    private fun receipt(data: JSONObject,phase: String=queryState)=JSONObject().put("hostId","fixture-host").put("threadId",data.getString("threadId")).put("clientMessageId",data.getString("clientMessageId")).put("state",phase).put("turnId",turn ?: JSONObject.NULL).put("error",JSONObject.NULL).put("text",data.getString("text")).put("attachmentIds",data.getJSONArray("attachmentIds"))
    private fun history(id: String)=JSONObject().put("hostId","fixture-host").put("threadId",id).put("readTarget",target()).put("epoch",eventEpoch).put("cursor",eventCursor).put("messages",JSONArray().put(JSONObject().put("id","old-user").put("role","user").put("text","先前任务")).put(JSONObject().put("id","old-assistant").put("role","assistant").put("text",streamText ?: "先前任务完成").put("status",if(streamText=="第一段流式正文") "streaming" else "completed")).apply { planMessages.objects().forEach { put(it) } }).put("runtime",JSONObject().put("state",state).put("turnId",turn ?: JSONObject.NULL).put("plan",planSteps).put("error",JSONObject.NULL).put("approvals",approvals).put("receipts",JSONArray().apply { cancelledReply?.takeIf { it.getString("threadId")==id }?.let { put(receipt(it,"cancelled")) }; reply?.takeIf { it.getString("threadId")==id }?.let { put(receipt(it,if(state=="running") { if(it.optString("mode")=="queue") "queued" else "submitted" } else if(state=="interrupted") "interrupted" else queryState)) } }).put("canControl",controlled).put("proposedPlan",proposed ?: JSONObject.NULL))
    private fun creationResult(phase: String,data: JSONObject=creation!!)=JSONObject().put("hostId","fixture-host").put("clientOperationId",data.getString("clientOperationId")).put("projectId",data.get("projectId")).put("phase",phase).put("threadId",if(phase=="confirmed") "created-thread" else JSONObject.NULL).put("error",JSONObject.NULL).put("uploaded",JSONArray(uploaded)).put("thread",if(phase=="confirmed") thread("created-thread") else JSONObject.NULL)
    internal fun resourceUiResponse(request: WireRequest)=response(request)
    private fun response(request: WireRequest): WireResponse {
        val query=request.query
        // Match runtime-server verifyCommandTarget before any command/upload side effect.
        query["target"]?.let { encoded ->
            val claimed=JSONObject(encoded);val expected=target()
            if(listOf("agentId","hostId","workspaceId","runtimeId").any { claimed.optString(it)!=expected.optString(it) }) {
                if(request.method=="POST")rejectedTargetWrites.incrementAndGet()
                return WireResponse(status=409,body="{\"error\":\"运行目标已变化，请刷新连接\"}")
            }
        }
        val result=when(request.path) {
            "/v1/workspace"->workspace()
            "/v1/history"->{ if(holdHistoryResponse) { historyStarted.countDown();check(historyRelease.await(20,TimeUnit.SECONDS)) };history(query.getValue("threadId")) }
            "/v1/events"->JSONObject().put("hostId","fixture-host").put("epoch",eventEpoch).put("cursor",eventCursor).put("reset",false).put("ready",eventReady).put("events",JSONArray().apply { if(eventCursor>0) put(JSONObject().put("threadId",query.getValue("threadId")).put("snapshot",history(query.getValue("threadId")))) })
            "/v1/attachments"->JSONObject().put("hostId","fixture-host").put("threadId",query.getValue("threadId")).put("id","uploaded-original-file")
                .put("name",query.getValue("name")).put("mediaType",request.headers.entries.first { it.key.equals("content-type",true) }.value)
                .put("sizeBytes",request.body.size).put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(request.body).joinToString("") { "%02x".format(it) })
            "/v1/models"->JSONObject().put("models",JSONArray().put(JSONObject().put("id","fixture-model").put("name",modelName).put("isDefault",true).put("defaultEffort","medium").put("supportedEfforts",JSONArray(listOf("low","medium","high"))).put("defaultSpeed","standard").put("supportedSpeeds",JSONArray(listOf("standard","fast","ultrafast")))))
            "/v1/replies"->{ reply=JSONObject(String(request.body,Charsets.UTF_8));originalReplies[reply!!.getString("clientMessageId")]=JSONObject(reply.toString());if(holdReplyResponse) { replyStarted.countDown();check(replyRelease.await(20,TimeUnit.SECONDS)) }; if(loseReply) return WireResponse(disconnect=true); receipt(reply!!,if(state=="running"&&reply!!.optString("mode")=="queue") "queued" else "submitted") }
            "/v1/receipts"->{ if(missingReceipts.getAndUpdate { if(it>0)it-1 else 0 }>0) { missingReceiptResponses.incrementAndGet();return WireResponse(status=404,body="{\"error\":\"Original receipt temporarily absent\"}") };val value=receipt(originalReplies.getValue(query.getValue("id")));if(holdReplyReceipt) { replyQueryStarted.countDown();check(replyQueryRelease.await(20,TimeUnit.SECONDS)) };replyQueryFailureStatus?.let { return WireResponse(status=it,body="{\"error\":\"Original receipt read unavailable\"}") };value }
            "/v1/desktop/open"->{ controlled=openingQueries==0; val body=JSONObject(String(request.body)); JSONObject().put("hostId","fixture-host").put("threadId",body.getString("threadId")).put("clientOperationId",body.getString("clientOperationId")).put("phase",if(controlled) "opened" else "opening").put("error",JSONObject.NULL).also { opening=it } }
            "/v1/desktop/open-status"->{
                check(query["id"]==opening!!.getString("clientOperationId") && query["threadId"]==opening!!.getString("threadId"))
                openingQueries--; controlled=openingQueries<=0
                JSONObject(opening.toString()).put("phase",if(controlled) "opened" else "opening")
            }
            "/v1/desktop/creations"->{ creation=JSONObject(String(request.body)); creations[creation!!.getString("clientOperationId")]=creation!!; creationResult("prepared") }
            "/v1/desktop/creation-receipts"->{
                val id=query.getValue("id"); val data=creations.getValue(id)
                if(id==blockedOperation) { val result=creationResult("confirmed",data); queryStarted.countDown(); check(queryRelease.await(20,TimeUnit.SECONDS)); result }
                else creationResult(submitPhase,data)
            }
            "/v1/desktop/creation-attachments"->{ val id=query.getValue("attachmentId"); val file=creation!!.getJSONArray("attachments").objects().first { it.getString("id")==id }; uploaded.add(id); JSONObject(file.toString()).put("hostId","fixture-host").put("clientOperationId",query.getValue("id")) }
            "/v1/desktop/creations/submit"->{ val data=JSONObject(String(request.body)); val id=data.getString("clientOperationId")
                if(blockedOperation!=null && id!=blockedOperation) { secondSubmitStarted.countDown(); check(secondSubmitRelease.await(20,TimeUnit.SECONDS)) }
                creationResult(submitPhase,creations.getValue(id))
            }
            "/v1/desktop/creations/detach"->creationResult(submitPhase).put("detached",true)
            "/v1/queue/cancel"->{ cancelledReply=reply; reply=null; eventCursor++; JSONObject().put("ok",true) }
            "/v1/stop"->{ state="interrupted"; turn=null; eventCursor++; JSONObject().put("ok",true) }
            "/v1/approvals"->{
                val body=JSONObject(String(request.body)); val id=body.getString("approvalId")
                val approval=approvals.objects().single { it.getString("id")==id }
                check(body.getString("threadId")==approval.getString("threadId") && body.getString("turnId")==approval.getString("turnId"))
                val original=approval.toString()
                if(holdApprovalResponse) { approvalStarted.countDown();check(approvalRelease.await(20,TimeUnit.SECONDS)) }
                approvalFailureStatus?.let { return WireResponse(status=it,body=JSONObject().put("error","Synthetic application response unavailable").toString()) }
                if(!holdApprovalResponse || approvals.objects().any { it.getString("id")==id && it.toString()==original })
                    approvals=JSONArray(approvals.objects().filter { it.getString("id")!=id })
                JSONObject().put("ok",true)
            }
            else->return WireResponse(status=404,body="{\"error\":\"Unknown synthetic route\"}")
        }
        // Model runtime-server's compatibility projection without mutating canonical fixture requests.
        val projected=if(applicationProtocol && request.headers["x-agent-hub-approvals"]!="mcp-v1") {
            JSONObject(result.toString()).apply {
                optJSONObject("runtime")?.optJSONArray("approvals")?.objects()?.forEach { row ->
                    if(row.has("decisionLabels"))row.put("decisions",JSONArray(listOf("decline")))
                }
            }
        }else result
        return WireResponse(body=projected.toString())
    }
    override fun close() { approvalRelease.countDown();historyRelease.countDown();replyQueryRelease.countDown();replyRelease.countDown();queryRelease.countDown(); secondSubmitRelease.countDown(); server.close() }
}
private val WireRequest.query get()=pathWithQuery.substringAfter('?').split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
