package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs

/** Synthetic HTTP + native scrolling; no model input, command or task/project mutation. */
class MobileApprovalHistoryAnchorTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val folder get()=File(i.targetContext.filesDir,"acceptance/P4-approval-history/${args.getString("viewportRun")!!}").apply {mkdirs()}
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation")!!},{folder},{"history"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private var owned: OwnedControlAcceptance?=null
    private val session get()=checkNotNull(owned).session
    private fun main(block: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main){block()}}
    private fun shot(name: String) {
        i.waitForIdleSync();val window=ui.activity.window;var bitmap: Bitmap?=null
        i.runOnMainSync {assertTrue(window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)}
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {i.runOnMainSync {android.view.PixelCopy.request(window,image,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))};assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result);File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    @After fun protect() {if(owned!=null){shot("final-state");main {session.leaveForeground();owned!!.verifyDefault()};File(owned!!.folder,"default-storage-proof.json").copyTo(File(folder,"default-storage-proof.json"))}}
    @Test fun readingAnchorSurvivesApprovalReplacementAppendRemovalAndPaging() {
        assertEquals(if(args.getString("viewportOrientation")=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,ui.activity.resources.configuration.orientation)
        File(folder,"profile.json").writeText(JSONObject().put("theme",args.getString("viewportTheme") ?: "dark").put("fontScale",ui.density.fontScale).toString())
        ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"approval-history");session.resetAcceptance();session.changeAppearance(args.getString("viewportTheme") ?: "dark")}
        ExecutionFixture().use {f ->
            fun request(id: String,turn: String,title: String)=JSONObject().put("id",id).put("threadId","fixture-thread").put("turnId",turn).put("kind","command").put("title",title).put("detail","Synthetic original request").put("questions",JSONArray()).put("decisions",JSONArray(listOf("decline","cancel")))
            f.state="running";f.turn="history-turn-1";f.approvals=JSONArray().put(request("same-id",f.turn!!,"Original approval"))
            f.planMessages=JSONArray((0 until 300).map {n->JSONObject().put("id","history-$n").put("role",if(n%2==0)"user"else"assistant").put("text","History $n\nIndependent reading anchor $n")})
            main {session.connect("Synthetic history anchor",f.server.endpoint,"")};ui.waitUntil(20_000){session.hasClient&&!session.loading};ui.runOnIdle {session.selectThread(session.threads.first())};ui.waitUntil(20_000){!session.historyLoading&&session.messages.size==302&&session.runtime?.approvals?.size==1}
            ui.runOnIdle {session.text="Independent history draft"}
            val old=session.runtime!!.approvals.single();val destination=session.approvalActionDestination();val list=ui.onNodeWithTag("message-list")
            // Track the first visible reading row. A bottom-edge row may leave
            // a portrait viewport when independent execution panels grow.
            val row=session.timeline.rows.indexOfFirst {it.kind=="message"&&it.id=="history-290"};assertTrue(row>=0)
            list.performScrollToIndex(row+1);val anchor=ui.onNodeWithTag("message-history-290");anchor.assertIsDisplayed();val before=anchor.getUnclippedBoundsInRoot().top.value;val proofs=JSONArray()
            fun retained(label: String) {ui.waitForIdle();anchor.assertIsDisplayed();val after=anchor.getUnclippedBoundsInRoot().top.value;proofs.put(JSONObject().put("state",label).put("beforeTopDp",before).put("afterTopDp",after));File(folder,"anchor-progress.json").writeText(proofs.toString());assertTrue("Original reading anchor moved after $label: $before vs $after",abs(after-before)<=1f);assertEquals("Independent history draft",session.text);shot(label)}
            fun read() {main {session.loadHistory(userInitiated=true)?.join()};ui.waitUntil(20_000){!session.historyLoading}}
            f.turn="history-turn-2";f.approvals=JSONArray().put(request("same-id",f.turn!!,"Replacement approval"));read();assertEquals("history-turn-2",session.runtime!!.approvals.single().turnId);retained("same-id-new-turn")
            f.planMessages.put(JSONObject().put("id","history-appended").put("role","assistant").put("text","New tail while reading older history"));read();assertEquals(303,session.messages.size);retained("append-while-reading")
            f.approvals=JSONArray();read();assertTrue(session.runtime!!.approvals.isEmpty());retained("remove-current-approval")
            f.turn="history-turn-3";f.approvals=JSONArray().put(request("same-id",f.turn!!,"Third approval")).put(request("second-id",f.turn!!,"Second current approval"));read();assertEquals(2,session.runtime!!.approvals.size);retained("two-current-approvals")
            ui.runOnIdle {session.answer(old,"decline",emptyMap(),destination);assertFalse(session.isAnswering(old))};assertTrue(f.server.requests.none {it.method=="POST"})
            list.performScrollToNode(hasTestTag("history-local-more"));val boundary=session.timeline.rows.first {it.kind=="message"}.id;val limit=session.visibleMessageLimit
            ui.onNodeWithTag("history-local-more").performTouchInput {click(center)};ui.waitUntil(20_000){!session.historyLoading&&session.visibleMessageLimit>limit};ui.waitUntil(10_000){runCatching{ui.onNodeWithTag("message-$boundary").assertIsDisplayed()}.isSuccess};shot("earlier-page-original-boundary")
            ui.onNodeWithTag("scroll-latest").assertIsDisplayed().performTouchInput {click(center)};ui.waitUntil(10_000){ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().isEmpty()};ui.onNodeWithTag("history-bottom").assertExists();val end=list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange];assertEquals("True end of native list",end.maxValue(),end.value(),.01f);shot("true-bottom-two-approvals")
            assertEquals("Independent history draft",session.text);assertTrue(session.operationRecords.isEmpty());assertTrue(f.server.requests.all {it.method=="GET"})
            File(folder,"native-proof.json").writeText(JSONObject().put("synthetic",true).put("messages",session.messages.size).put("anchorChanges",proofs).put("sameIdNewTurnProtected",true).put("oldCallbackNoPost",true).put("localPagingBoundaryPreserved",true).put("trueBottomCoordinate",true).put("draftProtected",true).put("posts",0).toString())
        }
    }
}
