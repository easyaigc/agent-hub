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
    private lateinit var noticeStorage: NativeSessionStorage
    private val target=TargetScope("codex",null,null,"notice-host","workspace","runtime")
    private val reader="a".repeat(64)
    private val lifecycle=NativeReadingLifecycle()
    private var binding by mutableStateOf<NativePanelNoticeVisibility?>(null)
    private var covered by mutableStateOf(false)
    private var version=0
    private var mode="approval"
    private var language by mutableStateOf("en")
    private var longCard=false
    private var unicodeCard=false
    private var longPlan=false
    private var codePlan=false
    private var customQuestion=false
    private var viewportWidth by mutableStateOf(330.dp)
    private fun planText()=when {
        codePlan -> "Original plan heading.\n\n```text\n"+"abcdefghijklmnopqrstuvwxyz ".repeat(40)+"\n```\n\nOriginal plan tail."
        longPlan -> (0..25).joinToString("\n\n") { "## Original block $it\n"+"Original paragraph $it. ".repeat(8) }
        else -> "Actual original plan text."
    }
    private var commandsCalled=0
    private var blockedReportIo=false
    private var diagnostic=0
    private val reports=Collections.synchronizedList(mutableListOf<NativeNoticeVisibilityReport>())
    private val leases=Collections.synchronizedMap(mutableMapOf<NativeNoticeVisibilityFence.Proof,NativeReadingLifecycle.Lease>())
    private fun event(which: String="one",kind: String="approval",run: String="run-$version",request: String="request-$which-$version")=
        NativeReadingEvent("event-$kind-$which-$version",target,"thread",kind,runId=run,requestId=request)
    private fun approvals()=JSONArray(if(mode=="plan")emptyList<JSONObject>() else listOf("one","two","three").map { id ->
        JSONObject().put("id",id).put("threadId","thread").put("turnId","run-$version").put("kind",mode)
            .put("notificationRequestId","request-$id-$version").put("title","Original $id version $version").put("detail",if(longCard && id=="one") (if(unicodeCard)"原始字段😀、100% %@؛ أصل الطلب. ".repeat(100) else "Synthetic long detail. ".repeat(150)) else "")
            .put("decisions",JSONArray(listOf("accept","decline"))).put("questions",JSONArray().apply { if(mode=="question")put(JSONObject().put("id","q").put("question","Actual $id question").put("custom",customQuestion).put("isSecret",false).put("options",JSONArray())) })
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
                .apply { if(mode=="plan")put("proposedPlan",JSONObject().put("id","plan-$version").put("turnId","run-$version").put("text",planText())) }).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({commandsCalled++;error("No command during notice reading")}) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun history() { main { session.loadHistory()?.join() };ui.waitForIdle() }
    private fun report(value: NativeNoticeVisibilityReport) {
        reports+=value
        if(blockedReportIo)return
        // Fixture bridge only. Production needs the atomic guard hook documented with this stage.
        lifecycle.present(target,"thread",null,ledger.notices(target,"thread").map { it.id }.toSet())
        if(value.visible)lifecycle.visibleNotices(target,"thread",setOf(value.notice.id),value.proof)?.let { leases[value.proof]=it }
    }
    private fun bind(foreground: Boolean=true,uncovered: Boolean=true,targetOverride: TargetScope=target,retainFrame: Boolean=false) {
        ui.runOnIdle { binding=NativePanelNoticeVisibility(targetOverride,"thread",ledger.notices(target,"thread"),foreground,uncovered,if(retainFrame)binding?.renderEpoch ?: Any() else Any(),::report) }
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
            noticeStorage=owned.storage.child(ui.activity,"notice-fixture");ledger=NativeReadingLedger(noticeStorage);ledger.reconcile(listOf(target),emptyList())
            lifecycle.foreground(true);lifecycle.uncovered(true)
            session=MobileSession(owned.storage,reads,commands);session.resetAcceptance(reads,commands)
            ui.activity.setContent { ProductTextProvider(language) { MaterialTheme(colorScheme=lightColorScheme()) { Surface {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.width(viewportWidth).height(240.dp).testTag("notice-fixture-viewport")) { ExecutionPanels(session,maxHeight=240.dp,autoPresentQuestions=false,noticeVisibility=binding) }
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
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-activity-window").put("package",ui.activity.packageName).put("width",image.width).put("height",image.height).put("visibleNoticeIds",JSONArray(active)).put("language",language).put("composedTheme","light").put("viewportWidthDp",viewportWidth.value).put("systemFontScale",ui.activity.resources.configuration.fontScale).put("wrapperThemeIsNotFixtureTheme",true).put("fullSystemComposite",false).toString())
        }finally { image.recycle() }
    }
    private fun scrollBy(tag: String="execution-panels-scroll",x: Float=0f,y: Float) {
        ui.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(x,y) };ui.waitForIdle()
    }
    private fun top()=scrollBy(y=-1_000_000f)
    private fun coverageStatus(node: SemanticsNodeInteraction): Map<String,Any> {
        val read=node.fetchSemanticsNode().config[NativeNoticeReadStatusKey]
        lateinit var value: Map<String,Any>;ui.runOnIdle { value=read() };return value
    }
    private fun coverageDiagnostic() {
        val nodes=ui.onAllNodes(SemanticsMatcher.keyIsDefined(NativeNoticeReadStatusKey),useUnmergedTree=true).fetchSemanticsNodes()
        lateinit var values: List<Map<String,Any>>;ui.runOnIdle { values=nodes.map { it.config[NativeNoticeReadStatusKey].invoke() } }
        File(owned.folder,"coverage-${diagnostic++}.json").writeText(JSONObject().put("fixtureTheme","light")
            .put("viewportWidthDp",viewportWidth.value).put("parts",JSONArray(values.map(::JSONObject))).toString())
    }
    private fun scan(e: NativeReadingEvent,limit: Int=100): NativeNoticeVisibilityReport? {
        val scroller=ui.onNodeWithTag("execution-panels-scroll")
        repeat(limit) {
            current(e.id)?.let { return it }
            val node=scroller.fetchSemanticsNode();val range=node.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            if(range.value()>=range.maxValue()) { coverageDiagnostic();return current(e.id) }
            scrollBy(y=node.boundsInRoot.height*.35f)
        }
        coverageDiagnostic();return current(e.id)
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
    @Test fun planToggleUsesTheExistingSharedCatalogInEnglishAndChinese() {
        mode="plan";history()
        for(locale in listOf("en","zh-Hans")) {
            ui.runOnIdle { language=locale };ui.waitForIdle()
            ui.onNodeWithTag("review-plan-toggle").performScrollTo().assertTextEquals(if(locale=="en")"View plan" else "查看计划").assertHasClickAction().performClick()
            ui.onNodeWithTag("proposed-plan-text").performScrollTo().assertIsDisplayed()
            assertEquals("Actual original plan text.",session.runtime!!.proposedPlan!!.text);assertEquals(0,commandsCalled)
            ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick()
        }
    }
    @Test fun noticeReadingDoesNotReadTheReplyAndTheSameLedgerSuppressesOldReminders() {
        val position=ThreadReadPosition(1,"reply",ThreadReadPosition.contentVersion("reply","Body","completed"))
        ledger.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,"thread",position,true)))
        val reply=NativeReadingEvent("reply-event",target,"thread","completed",position);ledger.receive(reply)
        val notice=event();ledger.receive(notice);bind();assertNotNull(mark(visible(notice.id)));assertFalse(ledger.mayPresent(notice));assertTrue(ledger.mayPresent(reply))
        lifecycle.present(target,"thread",position);val lease=lifecycle.visible(target,"thread","reply","Body","completed",true)!!
        assertNotNull(ledger.readVisible(target,"thread",reader,lifecycle,lease));assertFalse(ledger.mayPresent(reply));assertFalse(ledger.mayPresent(notice))
    }
    @Test fun longApprovalRequiresEveryActuallyVisibleTextRangeRatherThanTheBottom() {
        longCard=true;history();val e=event();ledger.receive(e);bind()
        scrollBy(y=1_000_000f);assertNull(current(e.id));assertTrue(ledger.mayPresent(e))
        top();val proof=scan(e);assertNotNull("A continuous overlapping native viewport scan must cover the original",proof)
        assertNotNull(mark(proof!!));assertFalse(ledger.mayPresent(e));shot("long-approval-covered")
    }
    @Test fun longPlanMissingMiddleAndCollapsedDisclosureCannotBorrowCoverage() {
        mode="plan";longPlan=true;history();val e=event(kind="plan",request="plan-$version");ledger.receive(e);bind()
        ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick();ui.waitForIdle()
        scrollBy(y=1_000_000f);assertNull(current(e.id));assertTrue(ledger.mayPresent(e))
        top();val old=checkNotNull(scan(e)) // Keep the fixture write pending after a complete proof.
        top();ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick();ui.waitForIdle()
        assertFalse(old.proof.isCurrent());assertNull(current(e.id));ui.onNodeWithTag("review-plan-toggle").performClick();ui.waitForIdle()
        scrollBy(y=1_000_000f);assertNull(current(e.id));assertTrue(ledger.mayPresent(e));top()
        val proof=scan(e);assertNotNull(proof);assertNotNull(mark(proof!!));assertFalse(ledger.mayPresent(e));shot("long-plan-covered")
    }
    @Test fun horizontalCodeNeedsActualOriginalGlyphsBeyondTheInitialClip() {
        mode="plan";codePlan=true;history();val e=event(kind="plan",request="plan-$version");ledger.receive(e);bind()
        ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick();ui.waitForIdle();scan(e)
        assertNull(current(e.id));assertTrue(ledger.mayPresent(e))
        ui.onNodeWithTag("proposed-plan-text-1-code-scroll").performScrollTo();ui.waitForIdle()
        val code=ui.onNodeWithTag("proposed-plan-text-1-code-scroll");var turns=0
        while(turns++<180) {
            val node=code.fetchSemanticsNode();val range=node.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
            if(range.value()>=range.maxValue())break
            scrollBy("proposed-plan-text-1-code-scroll",x=node.boundsInRoot.width*.4f,y=0f)
        }
        val proof=current(e.id) ?: scan(e);assertNotNull(proof);assertNotNull(mark(proof!!));assertFalse(ledger.mayPresent(e))
    }
    @Test fun longQuestionKeepsDraftAndNativeFocusWhileOriginalRangesAreReadUnderIme() {
        mode="question";customQuestion=true;longCard=true;history();val e=event(kind="question");ledger.receive(e);bind()
        val field=ui.onAllNodesWithTag("question-custom-q").onFirst().performScrollTo().performClick()
        field.performTextInput("Original unsent answer 100% %@");ui.waitForIdle();field.assertIsFocused()
        assertNull(current(e.id));top();val proof=scan(e);assertNotNull(proof)
        field.performScrollTo().assertTextEquals("Original unsent answer 100% %@").assertIsFocused()
        val currentProof=current(e.id) ?: scan(e);assertNotNull(mark(checkNotNull(currentProof)));assertFalse(ledger.mayPresent(e));assertEquals(0,commandsCalled)
    }
    @Test fun layoutReflowCannotUseRangesFromTheEarlierWidth() {
        longCard=true;history();val e=event();ledger.receive(e);bind();val old=checkNotNull(scan(e))
        ui.runOnIdle { viewportWidth=260.dp };ui.waitForIdle();scrollBy(y=1_000_000f)
        assertFalse(old.proof.isCurrent());assertNull(current(e.id));assertTrue(ledger.mayPresent(e));top();assertNotNull(mark(checkNotNull(scan(e))))
    }
    @Test fun aDifferentNativeWindowClearsEarlierPartialCoverage() {
        longCard=true;history();val e=event();ledger.receive(e);bind();val old=checkNotNull(scan(e))
        ui.runOnIdle { covered=true };ui.onNodeWithTag("notice-owned-overlay").assertIsDisplayed();ui.waitForIdle()
        ui.runOnIdle { covered=false };ui.waitForIdle();scrollBy(y=1_000_000f)
        assertFalse(old.proof.isCurrent());assertNull(current(e.id));assertTrue(ledger.mayPresent(e));top();assertNotNull(mark(checkNotNull(scan(e))))
    }
    @Test fun originalReplacementCannotBorrowAnEarlierLongRequestCoverage() {
        longCard=true;history();val old=event();ledger.receive(old);bind();val previous=checkNotNull(scan(old))
        version++;history();val next=event();ledger.receive(next);bind();scrollBy(y=1_000_000f)
        assertFalse(previous.proof.isCurrent());assertNull(current(next.id));assertTrue(ledger.mayPresent(old));assertTrue(ledger.mayPresent(next))
        top();assertNotNull(mark(checkNotNull(scan(next))));assertTrue(ledger.mayPresent(old));assertFalse(ledger.mayPresent(next))
    }
    @Test fun delayedOriginalLedgerWriteCannotOutliveTheLongPanelWindowProof() {
        longCard=true;history();val e=event();ledger.receive(e);bind();val proof=checkNotNull(scan(e));val lease=checkNotNull(leases[proof.proof])
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2);val locked=CountDownLatch(1);val release=CountDownLatch(1);val started=CountDownLatch(1)
        ui.runOnIdle { blockedReportIo=true }
        try {
            val hold=pool.submit { noticeStorage.operationTransaction { locked.countDown();check(release.await(15,TimeUnit.SECONDS)) } }
            assertTrue(locked.await(5,TimeUnit.SECONDS))
            val read=pool.submit<NativeUnreadSnapshot?> { started.countDown();ledger.readVisible(target,"thread",reader,lifecycle,lease) }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            // Suppress fixture callback I/O while the lock is deliberately held. The native guard
            // itself must still revoke before the delayed ledger mutation.
            ui.runOnIdle { covered=true };ui.onNodeWithTag("notice-owned-overlay").assertIsDisplayed()
            ui.waitUntil(5_000) { !proof.proof.isCurrent() };release.countDown();hold.get(10,TimeUnit.SECONDS)
            assertNull(read.get(10,TimeUnit.SECONDS));assertTrue(ledger.mayPresent(e))
        }finally { release.countDown();pool.shutdownNow();ui.runOnIdle { blockedReportIo=false;covered=false } }
    }

    @Test fun unicodeAndBidirectionalLongOriginalUsesActualUtf16GlyphRanges() {
        longCard=true;unicodeCard=true;history();val e=event();ledger.receive(e);bind()
        scrollBy(y=1_000_000f);assertNull(current(e.id));assertTrue(ledger.mayPresent(e))
        top();assertNotNull(mark(checkNotNull(scan(e))));assertFalse(ledger.mayPresent(e))
    }

    @Test fun lateNoticeMetadataRetainsOnlyTheSameOriginalAndNativeSceneCoverage() {
        longCard=true;history();bind()
        val first=ui.onNode(SemanticsMatcher.keyIsDefined(NativeNoticeReadStatusKey) and hasAnyDescendant(hasTestTag("approval-one")),useUnmergedTree=true)
        val scroller=ui.onNodeWithTag("execution-panels-scroll")
        fun complete()=coverageStatus(first)["complete"]==true
        repeat(100) { if(!complete())scrollBy(y=scroller.fetchSemanticsNode().boundsInRoot.height*.35f) }
        assertTrue(complete());assertTrue(reports.isEmpty())
        val e=event();ledger.receive(e);bind(retainFrame=true)
        assertNotNull(mark(visible(e.id)));assertFalse(ledger.mayPresent(e))
    }

    @Test fun aFullyOffscreenOriginalKeepsEarlierCoverageButRevokesTheLiveReadProof() {
        longCard=true;history();val e=event();ledger.receive(e);bind();val old=checkNotNull(scan(e))
        scrollBy(y=1_000_000f);assertFalse(old.proof.isCurrent());assertNull(current(e.id));assertNull(mark(old))
        val first=ui.onNode(SemanticsMatcher.keyIsDefined(NativeNoticeReadStatusKey) and hasAnyDescendant(hasTestTag("approval-one")),useUnmergedTree=true)
        val status=coverageStatus(first)
        assertEquals(true,status["complete"]);assertEquals(status["characters"],status["coveredCharacters"])
        top();val fresh=visible(e.id);assertNotSame(old.proof,fresh.proof)
        assertNotNull(mark(fresh));assertFalse(ledger.mayPresent(e));assertEquals(0,commandsCalled)
    }

}
