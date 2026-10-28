package com.agenthub.android

import com.agenthub.core.TargetScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic positive grant + actual encrypted relay + production Session commit.
 * Two real-button/callback methods and two separately labelled Session-boundary probes.
 */
class NativeEasyWorkPositiveCommitTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource(){override fun before(){P8CommitAcceptance.guard();val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("p8UiForeground")=="1"){val fd=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity");assertTrue(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}.contains("Status: ok"))}}}
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private lateinit var f: P8CommitAcceptance
    private lateinit var draft: EasyWorkConnectionDraft
    private val visible=mutableStateOf(true)
    private val saved=AtomicInteger()
    private val touchProbe=P8FormTouchProbe()
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var job: Deferred<Result<MobileClient>>?=null
    @Before fun setup(){f=P8CommitAcceptance();f.start();draft=EasyWorkConnectionDraft("Synthetic positive",f.origin).apply {method="通过中继连接";code="0123456789ABCDEF"};ui.runOnIdle {ui.activity.setContent {MaterialTheme {Surface(Modifier.fillMaxSize().then(touchProbe.modifier),color=MaterialTheme.colorScheme.background) {ProductTextProvider("zh-Hans") {if(visible.value)ConnectionPage(f.session,null,draft) {saved.incrementAndGet()}}}}}};ui.onNodeWithTag("agent-provider-easywork").performScrollTo().performClick()}
    private fun assertScopeValue(expected: TargetScope?,actual: TargetScope?) {
        assertNotNull("Expected complete target",expected);assertNotNull("Actual complete target",actual)
        val e=expected!!;val a=actual!!
        assertEquals("agentId",e.agentId,a.agentId)
        assertEquals("accountAuthority",e.accountAuthority,a.accountAuthority)
        assertEquals("accountSubject",e.accountSubject,a.accountSubject)
        assertEquals("hostId",e.hostId,a.hostId)
        assertEquals("workspaceId",e.workspaceId,a.workspaceId)
        assertEquals("runtimeId",e.runtimeId,a.runtimeId)
    }
    private fun files(): Map<String,String> {val root=File(f.i.targetContext.noBackupFilesDir,f.namespace);return root.listFiles().orEmpty().filter {it.isFile}.associate {it.name to P8CommitAcceptance.hash(it)}}
    @After fun finish(){
        if(::f.isInitialized&&f.started){if(job?.isCompleted==false)runCatching {f.release("error")};ui.runOnIdle {visible.value=false;ui.activity.setContent {};f.session.leaveForeground()};runBlocking {withTimeoutOrNull(15_000){job?.join()}};tasks.cancel();f.flush();assertEquals(f.defaults,f.defaults());val stats=f.stats();f.proof("final-observations",f.common().put("stats",stats).put("savedCallbacks",saved.get()).put("defaultEncryptedFilesUnchanged",true).put("observationsOnly",true));f.api("/end-case",JSONObject().put("caseId",f.id))}
    }
    private fun button(){
        val activity=ui.activity
        val observation=f.common().put("lane","one actual pointer tap; no semantic-button click or retry").put("waitBudgetMsPerPhase",12_000)
        val waits=org.json.JSONArray();observation.put("waitPhases",waits)
        fun stamp()=f.main {val view=activity.window.decorView;val insets=androidx.core.view.ViewCompat.getRootWindowInsets(view)
            JSONObject().put("elapsedMs",android.os.SystemClock.elapsedRealtime()).put("clockMs",ui.mainClock.currentTime).put("autoAdvance",ui.mainClock.autoAdvance)
                .put("activityIdentity",System.identityHashCode(activity)).put("windowIdentity",System.identityHashCode(activity.window)).put("activityClass",activity.javaClass.name).put("package",activity.packageName)
                .put("lifecycle",activity.lifecycle.currentState.name).put("windowFocus",view.hasWindowFocus()).put("decorShown",view.isShown).put("focusedViewClass",activity.currentFocus?.javaClass?.name)
                .put("busy",f.session.connectionBusy).put("formOwnerIdentity",draft.formOwner?.let {System.identityHashCode(it)}).put("savedCallbacks",saved.get())
                .put("imeVisible",insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true).put("imeBottomPx",insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0)
                .put("systemBarsBottomPx",insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom?:0)}
        fun rect(r: androidx.compose.ui.geometry.Rect)=JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom)
        var phase="prepare"
        val lifecycleObserver=androidx.lifecycle.LifecycleEventObserver {_,event->touchProbe.lifecycle(event.name)}
        val focusObserver=android.view.ViewTreeObserver.OnWindowFocusChangeListener {focused->touchProbe.focus(focused)}
        f.main {activity.lifecycle.addObserver(lifecycleObserver);activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusObserver)}
        try {
            ui.onNodeWithTag("agent-provider-easywork").assertIsSelected()
            val n=ui.onNodeWithTag("easywork-pair-relay").performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            ui.waitForIdle();assertTrue("Actual form waits require the rule-owned advancing Compose clock",ui.mainClock.autoAdvance)
            val node=n.fetchSemanticsNode();val button=node.boundsInRoot;val raw=n.getUnclippedBoundsInRoot();val clip=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot
            observation.put("buttonRoot",rect(button)).put("buttonWindow",rect(node.boundsInWindow)).put("scrollClipRoot",rect(clip)).put("expectedCenterRoot",JSONObject().put("x",button.center.x).put("y",button.center.y)).put("density",ui.density.density)
                .put("unclippedDp",JSONObject().put("left",raw.left.value).put("top",raw.top.value).put("right",raw.right.value).put("bottom",raw.bottom.value))
            assertTrue("Whole original touch target width must be visible",button.width>=(raw.right.value-raw.left.value)*ui.density.density-1.5f)
            assertTrue("Whole original touch target height must be visible",button.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
            assertTrue("Whole target must lie inside actual scroll viewport",button.left>=clip.left-1&&button.right<=clip.right+1&&button.top>=clip.top-1&&button.bottom<=clip.bottom+1)
            val originalOwner=ui.runOnIdle {checkNotNull(draft.formOwner)}
            val before=stamp();observation.put("beforeTap",before)
            assertEquals("RESUMED",before.getString("lifecycle"));assertTrue("Exact test Activity must own focus before tap",before.getBoolean("windowFocus")&&before.getBoolean("decorShown"));assertEquals(0,saved.get())
            f.main {val view=activity.window.decorView;val screen=android.graphics.Rect();view.getWindowVisibleDisplayFrame(screen);val origin=IntArray(2);view.getLocationOnScreen(origin)
                observation.put("visibleScreenFrame",JSONObject().put("left",screen.left).put("top",screen.top).put("right",screen.right).put("bottom",screen.bottom)).put("decorScreenX",origin[0]).put("decorScreenY",origin[1])
                val window=node.boundsInWindow;assertTrue("Whole button must be inside actual system-visible window",window.left>=screen.left-origin[0]-1&&window.right<=screen.right-origin[0]+1&&window.top>=screen.top-origin[1]-1&&window.bottom<=screen.bottom-origin[1]+1)}
            touchProbe.reset();phase="tap"
            n.performTouchInput {click(center)}
            ui.waitForIdle();touchProbe.assertOneActualTapInside(button);observation.put("afterTap",stamp())
            phase="pending"
            // Drive the real form coroutine scheduler during BOTH unchanged 12-second phases.
            f.pending {condition->
                val wait=JSONObject().put("phase",if(waits.length()==0)"claim-pending" else "final-read-held").put("before",stamp());waits.put(wait);var polls=0;var complete=false
                try {ui.waitUntil(12_000) {polls++;condition().also {complete=it}}}
                finally {wait.put("after",stamp()).put("polls",polls).put("conditionSatisfied",complete)}
            }
            phase="held"
            touchProbe.assertOneActualTapInside(button)
            ui.runOnIdle {assertSame("Wait must retain the original rule Activity",activity,ui.activity);assertSame("Wait must retain the original mounted form",originalOwner,draft.formOwner)}
            val after=stamp();observation.put("afterPending",after);assertEquals(before.getInt("activityIdentity"),after.getInt("activityIdentity"));assertEquals(before.getInt("windowIdentity"),after.getInt("windowIdentity"));assertEquals("RESUMED",after.getString("lifecycle"));assertTrue(after.getBoolean("windowFocus"))
            assertEquals(0,saved.get());f.inactive()
        } finally {
            f.main {activity.lifecycle.removeObserver(lifecycleObserver);val observer=activity.window.decorView.viewTreeObserver;if(observer.isAlive)observer.removeOnWindowFocusChangeListener(focusObserver)}
            observation.put("lastPhase",phase).put("final",stamp()).put("pointerRoute",touchProbe.snapshot())
            f.proof("button-observations",observation)
        }
    }
    @Test(timeout=50_000) fun correctGrantActivatesActualCandidateAtFinalCommitAndOriginalFormCompletesOnce(){
        button();val heldFiles=files();val before=f.metadata();assertEquals(0,before.getJSONArray("clients").length());f.release()
        ui.waitUntil(15_000){saved.get()==1&&!f.main {f.session.connectionBusy}};val client=f.main {f.session.selectedClient};assertScopeValue(f.binding.target.scope(),client.scope);assertTrue(client.online)
        val ref=client.agentConnection!!.credentialRef;assertTrue(ref.startsWith("agent-${client.id}-"));f.candidatePresent(ref)
        assertTrue("The exact final credential record existed while metadata was still unactivated",heldFiles.containsKey(easyHash("easywork-credential:$ref".toByteArray())+".bin"))
        val metadata=f.metadata();assertEquals(client.id,metadata.getString("selected"));val persisted=metadata.getJSONArray("clients").objects().single();assertEquals(ref,persisted.getJSONObject("agentConnection").getString("credentialRef"));assertEquals(1,saved.get());f.unchanged()
        ui.runOnIdle {visible.value=false};ui.waitForIdle();assertEquals(1,saved.get())
        // Fresh storage + Session restore in this same process is auxiliary reopen evidence.
        // The separate cold writer/reader class requires an externally verified new PID.
        val restored=f.main {MobileSession(f.storage.reopen(f.i.targetContext)).also {it.leaveForeground();it.restore()}};f.waitFor {!f.main {restored.restoring}}
        f.main {assertFalse(restored.localRecoveryRequired);assertScopeValue(f.binding.target.scope(),restored.clients.single().scope);assertEquals(ref,restored.clients.single().agentConnection!!.credentialRef);restored.leaveForeground()}
        assertEquals(f.originalText,f.storage.metadata()!!.getJSONObject("drafts").getString(f.originalTarget));f.unchanged()
        f.main {f.session.leaveForeground();f.session.select(f.original)};assertEquals(f.originalText,f.main {f.session.text});assertScopeValue(f.original.scope,f.main {f.session.selectedClient.scope});f.flush();f.unchanged()
        f.proof("positive-commit",f.common().put("lane","actual Provider form button + actual saved callback").put("candidateRef",ref).put("candidateExistedBeforeMetadataActivation",true).put("metadataActivatedOnlyAfterFinalRead",true).put("selectedCompleteGrantTarget",true).put("originalDraftAndUnknownPreserved",true).put("sameOriginalGrantAndClaim",true).put("savedCallbacks",saved.get()).put("reopen","fresh store/Session same process; separate cold PID gate required"))
    }
    @Test(timeout=50_000) fun closedActualFormAfterCandidateWriteCannotActivateOrCompleteOldCallback(){
        val baseline=files();button();val held=files();val persistent=listOf("easywork-device-identity","metadata",f.journalKey,f.claimKey,f.grantRef,EasyWorkAuthorizationCoordinator.fenceKey(f.grantRef)).map {easyHash(it.toByteArray())+".bin"}.toSet()
        val candidates=held.keys-baseline.keys-persistent;assertEquals("Exactly one unreferenced credential candidate in the bounded owned namespace",1,candidates.size)
        ui.runOnIdle {visible.value=false};ui.waitForIdle();f.release();ui.waitUntil(12_000){!f.main {f.session.connectionBusy}};ui.waitUntil(12_000){candidates.none {it in files()}}
        assertEquals(0,saved.get());f.inactive();f.unchanged();f.reopenWithoutPromotion(null);f.proof("closed-before-commit",f.common().put("lane","actual mounted form disposed at last workspace read").put("oldCallbackAbsent",true).put("ownedCandidateRolledBack",true).put("grantClaimUnknownPreserved",true))
    }
    private fun boundary(changeDraft: Boolean){
        val input=EasyWorkConnectionInput("code",draft.name,draft.relay,draft.code);val lease=ui.runOnIdle {draft.formOwner!!.begin(input) {EasyWorkConnectionInput("code",draft.name,draft.relay,draft.code)}}
        job=tasks.async {withContext(NonCancellable+lease){withTimeout(20_000){runCatching {f.session.connectEasyWorkCode(input.name,input.endpoint,input.value)}}}}
        f.pending();val ref=f.candidate(lease.id);f.candidatePresent(ref);f.inactive()
        val expectedText=if(changeDraft)"New independent draft survives old final-read failure" else f.originalText
        if(changeDraft)f.main {f.session.text=expectedText}
        f.release("error");val result=runBlocking {withTimeout(12_000){job!!.await()}};val error=result.exceptionOrNull();assertNotNull(error)
        if(changeDraft){assertTrue(error is EasyWorkConnectionAbandoned);assertTrue(generateSequence(error){it.cause}.any {it is EasyWorkRejection})}else assertTrue(error is EasyWorkRejection)
        assertNull(f.storage.read("easywork-credential:$ref"));assertEquals(0,f.metadata().getJSONArray("clients").length());assertEquals(0,saved.get());assertFalse(lease.committed)
        f.main {assertEquals(f.original.scope,f.session.selectedClient.scope);assertEquals(expectedText,f.session.text);assertTrue(f.session.clients.isEmpty());lease.releaseBusy()};f.unchanged();f.reopenWithoutPromotion(null,expectedText=expectedText)
        f.proof(if(changeDraft)"draft-and-failure" else "final-read-failure",f.common().put("lane","actual mounted owner + bounded test-owned NonCancellable Session call; not a button").put("lastReadFailure",true).put("newDraftChanged",changeDraft).put("oldCandidateAbsent",true).put("oldResultNotSelected",true).put("savedCallbacks",0).put("grantClaimUnknownPreserved",true))
    }
    @Test(timeout=50_000) fun lastWorkspaceReadFailureRollsBackCandidateWithoutActivatingOrReclaimingGrant()=boundary(false)
    @Test(timeout=50_000) fun changedOriginalDraftPlusLastReadFailureCannotCommitOrClearNewDraft()=boundary(true)
}
