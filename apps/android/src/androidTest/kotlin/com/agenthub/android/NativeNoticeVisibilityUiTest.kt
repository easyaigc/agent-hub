package com.agenthub.android

import android.graphics.Bitmap
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Prepared native UI/ledger boundary scenarios, not the missing production Session adapter.
 * One isolated existing ledger receives fixture events, checks visible reads and old-reminder policy. */
class NativeNoticeVisibilityUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(150)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private lateinit var ledger: NativeReadingLedger
    private val target=TargetScope("codex",null,null,"notice-host","workspace","runtime")
    private val reader="a".repeat(64)
    private val lifecycle=NativeReadingLifecycle()
    private var binding by mutableStateOf<NativePanelNoticeVisibility?>(null)
    private var covered by mutableStateOf(false)
    private var version=0
    private var mode="approval"
    private var longCard=false
    private var commandsCalled=0
    private val reports=Collections.synchronizedList(mutableListOf<NativeNoticeVisibilityReport>())
    private val leases=Collections.synchronizedMap(mutableMapOf<NativeNoticeVisibilityFence.Proof,NativeReadingLifecycle.Lease>())
    private fun event(which: String="one",kind: String="approval",run: String="run-$version",request: String="request-$which-$version")=
        NativeReadingEvent("event-$kind-$which-$version",target,"thread",kind,runId=run,requestId=request)
    private fun approvals()=JSONArray(if(mode=="plan")emptyList<JSONObject>() else listOf("one","two","three").map { id ->
        JSONObject().put("id",id).put("threadId","thread").put("turnId","run-$version").put("kind",mode)
            .put("notificationRequestId","request-$id-$version").put("title","Original $id version $version").put("detail",if(longCard && id=="one")"Synthetic long detail. ".repeat(150) else "")
            .put("decisions",JSONArray(listOf("accept","decline"))).put("questions",JSONArray().apply { if(mode=="question")put(JSONObject().put("id","q").put("question","Actual $id question").put("custom",false).put("isSecret",false).put("options",JSONArray())) })
    })
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
            .put("hostId","notice-host").put("name","Fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
            .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("hostId","notice-host").put("id","thread").put("title","Thread").put("state","idle")))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
            .put("hostId","notice-host").put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
            .put("runtime",JSONObject().put("state","idle").put("turnId","run-$version").put("plan",JSONArray()).put("error",JSONObject.NULL)
                .put("approvals",approvals()).put("receipts",JSONArray()).put("canControl",true)
                .apply { if(mode=="plan")put("proposedPlan",JSONObject().put("id","plan-$version").put("turnId","run-$version").put("text","Actual original plan text.")) }).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({commandsCalled++;error("No command during notice reading")}) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun history() { main { session.loadHistory()?.join() };ui.waitForIdle() }
    private fun report(value: NativeNoticeVisibilityReport) {
        reports+=value
        // Fixture bridge only. Production needs the atomic guard hook documented with this stage.
        lifecycle.present(target,"thread",null,ledger.notices(target,"thread").map { it.id }.toSet())
        if(value.visible)lifecycle.visibleNotices(target,"thread",setOf(value.notice.id))?.let { leases[value.proof]=it }
    }
    private fun bind(foreground: Boolean=true,uncovered: Boolean=true,targetOverride: TargetScope=target) {
        ui.runOnIdle { binding=NativePanelNoticeVisibility(targetOverride,"thread",ledger.notices(target,"thread"),foreground,uncovered,Any(),::report) }
        ui.waitForIdle()
    }
    private fun current(id: String)=synchronized(reports) { reports.lastOrNull { it.visible && it.notice.id==id && it.proof.isCurrent() } }
    private fun visible(id: String): NativeNoticeVisibilityReport { ui.waitUntil(10_000) { current(id)!=null };return current(id)!! }
    private fun mark(value: NativeNoticeVisibilityReport): NativeUnreadSnapshot? {
        if(!value.proof.isCurrent())return null
        return ledger.readVisible(target,"thread",reader,lifecycle,checkNotNull(leases[value.proof]))
    }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"notice-visibility")
            ledger=NativeReadingLedger(owned.storage.child(ui.activity,"notice-fixture"));ledger.reconcile(listOf(target),emptyList())
            lifecycle.foreground(true);lifecycle.uncovered(true)
            session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands)
            ui.activity.setContent { ProductTextProvider("en") { MaterialTheme { Surface {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.width(330.dp).height(240.dp).testTag("notice-fixture-viewport")) { ExecutionPanels(session,maxHeight=240.dp,autoPresentQuestions=false,noticeVisibility=binding) }
                    if(covered)Dialog(onDismissRequest={}) { Surface { Text("Owned overlay",Modifier.padding(30.dp).testTag("notice-owned-overlay")) } }
                }
            } } } }
        }
        main { session.connect("Fixture","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single()) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
    }
    @After fun finish() { if(::session.isInitialized)ui.runOnIdle { binding=null;covered=false;session.leaveForeground() };if(::owned.isInitialized)ui.runOnIdle { owned.session.leaveForeground();owned.verifyDefault() };assertEquals(0,commandsCalled) }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();val frames=CountDownLatch(1)
        i.runOnMainSync { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertEquals("com.easyai.agenthub.android.acceptance",ui.activity.packageName);assertTrue(ui.activity.window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=bitmap!!;val done=CountDownLatch(1);var status=-1
        try {
            i.runOnMainSync { PixelCopy.request(ui.activity.window,image,{status=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) };assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
            File(owned.folder,"$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val active=synchronized(reports) { reports.filter { it.visible && it.proof.isCurrent() }.map { it.notice.id } }
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-activity-window").put("package",ui.activity.packageName).put("width",image.width).put("height",image.height).put("visibleNoticeIds",JSONArray(active)).put("fullSystemComposite",false).toString())
        }finally { image.recycle() }
    }
    @Test fun selectingAThreadWithNoExplicitPortDoesNotMarkItsNotices() {
        val e=event();ledger.receive(e);ui.waitForIdle();ui.onNodeWithTag("approval-one").assertIsDisplayed()
        assertTrue(reports.isEmpty());assertTrue(ledger.mayPresent(e));assertNull(binding)
    }
    @Test fun firstAndLastPanelsRequireActualGeometryAndOldLeasesCannotClearLaterNotices() {
        val first=event();val last=event("three");ledger.receive(first);ledger.receive(last);bind()
        val old=visible(first.id);assertNull(current(last.id));shot("first-panel")
        ui.onNodeWithTag("approval-three").performScrollTo().assertIsDisplayed();val newest=visible(last.id)
        ui.waitUntil(10_000) { !old.proof.isCurrent() };assertNull(ledger.readVisible(target,"thread",reader,lifecycle,leases[old.proof]!!))
        assertNotNull(mark(newest));assertTrue(ledger.mayPresent(first));assertFalse(ledger.mayPresent(last));shot("last-panel")
    }
    @Test fun explicitForegroundAndCoverRetractionRevokeOldCallbacks() {
        val e=event();ledger.receive(e);bind();val first=visible(e.id)
        bind(foreground=false);assertFalse(first.proof.isCurrent());assertNull(mark(first));assertTrue(ledger.mayPresent(e))
        bind();val second=visible(e.id);bind(uncovered=false);assertFalse(second.proof.isCurrent());assertTrue(ledger.mayPresent(e))
        bind();val third=visible(e.id);ui.runOnIdle { covered=true };ui.onNodeWithTag("notice-owned-overlay").assertIsDisplayed()
        ui.waitUntil(10_000) { !third.proof.isCurrent() };assertNull(mark(third));assertTrue(ledger.mayPresent(e))
        ui.runOnIdle { covered=false };visible(e.id)
    }
    @Test fun nativeActivityPauseAndResumeRequireANewViewProof() {
        val e=event();ledger.receive(e);bind();val old=visible(e.id)
        ui.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        try { assertFalse(old.proof.isCurrent());assertTrue(ledger.mayPresent(e)) }
        finally { ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        val next=visible(e.id);assertNotSame(old.proof,next.proof)
    }
    @Test fun replacementAndForeignTargetNeverReuseTheOldOriginal() {
        val oldEvent=event();ledger.receive(oldEvent);bind();val old=visible(oldEvent.id)
        version++;history();assertFalse(old.proof.isCurrent());assertTrue(ledger.mayPresent(oldEvent));assertNull(mark(old))
        val next=event();ledger.receive(next);bind(targetOverride=TargetScope("codex",null,null,"notice-host","workspace","foreign"));assertNull(current(next.id))
        bind();assertNotNull(mark(visible(next.id)));assertTrue(ledger.mayPresent(oldEvent));assertFalse(ledger.mayPresent(next))
    }
    @Test fun lateEventsJoinTheMountedQuestionOnlyByOriginalRequestAndRun() {
        mode="question";history();bind();assertTrue(reports.isEmpty())
        val wrong=event("wrong",kind="question",request="ui-id-one");ledger.receive(wrong);bind();assertNull(current(wrong.id))
        val exact=event(kind="question");ledger.receive(exact);bind();assertNotNull(mark(visible(exact.id)))
        assertTrue(ledger.mayPresent(wrong));assertFalse(ledger.mayPresent(exact));shot("question-exact-original")
    }
    @Test fun aCollapsedPlanPromptDoesNotReadTheOriginalPlanText() {
        mode="plan";history();val e=event(kind="plan",request="plan-$version");ledger.receive(e);bind()
        ui.onNodeWithTag("review-plan-toggle").performScrollTo().assertIsDisplayed();assertNull(current(e.id));assertTrue(ledger.mayPresent(e))
        ui.onNodeWithTag("review-plan-toggle").performClick();assertNotNull(mark(visible(e.id)));assertFalse(ledger.mayPresent(e));shot("expanded-original-plan")
    }
    @Test fun anOversizedPartiallyClippedCardStaysUnread() {
        longCard=true;history();val e=event();ledger.receive(e);bind()
        ui.onNodeWithTag("approval-one").assertIsDisplayed();assertNull(current(e.id));assertTrue(ledger.mayPresent(e));shot("oversized-card-unread")
    }
    @Test fun noticeReadingDoesNotReadTheReplyAndTheSameLedgerSuppressesOldReminders() {
        val position=ThreadReadPosition(1,"reply",ThreadReadPosition.contentVersion("reply","Body","completed"))
        ledger.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,"thread",position,true)))
        val reply=NativeReadingEvent("reply-event",target,"thread","completed",position);ledger.receive(reply)
        val notice=event();ledger.receive(notice);bind();assertNotNull(mark(visible(notice.id)));assertFalse(ledger.mayPresent(notice));assertTrue(ledger.mayPresent(reply))
        lifecycle.present(target,"thread",position);val lease=lifecycle.visible(target,"thread","reply","Body","completed",true)!!
        assertNotNull(ledger.readVisible(target,"thread",reader,lifecycle,lease));assertFalse(ledger.mayPresent(reply));assertFalse(ledger.mayPresent(notice))
    }
}
