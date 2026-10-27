package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Fixed viewport HTTP/native regression, with synthetic requests and no real model input. */
class MobileApprovalViewportTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val folder get()=File(i.targetContext.filesDir,"acceptance/P4-approval-viewport/${args.getString("viewportRun")!!}").apply { mkdirs() }
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation")!!},{folder},{"fixture"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private var owned: OwnedControlAcceptance?=null
    private val session get()=checkNotNull(owned).session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String="approval") {
        i.waitForIdleSync();val window=ui.activity.window;var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try { i.runOnMainSync { android.view.PixelCopy.request(window,image,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) };assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result);File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }finally{image.recycle()}
    }
    @After fun protect() { if(owned!=null) { main {session.leaveForeground();owned!!.verifyDefault()};File(owned!!.folder,"default-storage-proof.json").copyTo(File(folder,"default-storage-proof.json")) } }
    @Test fun originalApprovalHeaderAndChoicesRemainVisible() {
        assertEquals(if(args.getString("viewportOrientation")=="landscape")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,ui.activity.resources.configuration.orientation)
        ui.waitUntil(30_000) { !ui.activity.session.restoring };ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"approval-viewport");session.resetAcceptance()}
        ExecutionFixture().use { f ->
            f.state="running";f.turn="viewport-original-turn"
            val choices=if(args.getString("viewportChoices")=="all") listOf("accept","acceptForSession","decline","cancel") else listOf("accept","cancel")
            val theme=args.getString("viewportTheme") ?: "light"
            ui.runOnIdle {session.changeAppearance(theme)}
            File(folder,"profile.json").writeText(JSONObject().put("theme",session.appearance).put("fontScale",ui.density.fontScale).put("choices",JSONArray(choices)).toString())
            f.approvals=JSONArray().put(JSONObject().put("id","viewport-approval").put("threadId","fixture-thread").put("turnId",f.turn).put("kind","command").put("title","/bin/zsh -lc \"printf 'ANDROID_P4_VIEWPORT'\"").put("detail","验证安卓原请求审批").put("questions",JSONArray()).put("decisions",JSONArray(choices)))
            main {session.connect("synthetic viewport",f.server.endpoint,"")};ui.waitUntil(15_000){session.hasClient&&!session.loading}
            ui.runOnIdle {session.selectThread(session.threads.first())};ui.waitUntil(15_000){!session.historyLoading&&session.runtime?.approvals?.size==1}
            ui.runOnIdle {session.text="Independent approval draft"}
            val a=session.runtime!!.approvals.single()
            try { verifyApprovalViewport(ui,a,folder,"original") {shot(it)} }finally {shot();File(folder,"writes.json").writeText(JSONObject().put("posts",f.server.requests.count {it.method=="POST"}).put("draftProtected",session.text=="Independent approval draft").toString())}
            if(args.getString("viewportHit")=="1") {
                ui.onNodeWithTag("message-list").performScrollToIndex(0);ui.waitForIdle()
                val visible=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().isNotEmpty()
                if(visible) {
                    ui.onNodeWithTag("scroll-latest").assertIsDisplayed().performTouchInput {click(center)}
                    ui.waitUntil(10_000) {ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().isEmpty()}
                }
                assertTrue(f.server.requests.none {it.method=="POST"});assertEquals("Independent approval draft",session.text)
                File(folder,"scroll-latest-hit.json").writeText(JSONObject().put("coordinateActivated",visible).put("noPost",true).put("draftProtected",true).put("scope",if(visible)"owned native coordinate tap returned to the end" else "no overflow at this fixed viewport").toString())
            }
            val submit=args.getString("viewportSubmit")
            if(submit.isNullOrEmpty()) assertTrue(f.server.requests.none {it.method=="POST"}) else {
                assertTrue(submit in choices);main {allowSyntheticRegressionSharing(session)}
                ui.onNodeWithTag("approval-$submit").performScrollTo().performTouchInput {click(center)}
                ui.waitUntil(15_000) {f.server.requests.any {it.method=="POST"&&it.path=="/v1/approvals"}&&!session.isAnswering(a)}
                val posts=f.server.requests.filter {it.method=="POST"};assertEquals(1,posts.size)
                val body=JSONObject(String(posts.single().body));assertEquals("/v1/approvals",posts.single().path);assertEquals(a.id,body.getString("approvalId"));assertEquals(a.threadId,body.getString("threadId"));assertEquals(a.turnId,body.getString("turnId"));assertEquals(submit,body.getString("decision"));assertEquals("Independent approval draft",session.text)
                File(folder,"writes.json").writeText(JSONObject().put("posts",posts.size).put("draftProtected",session.text=="Independent approval draft").toString())
                File(folder,"decision-proof.json").writeText(JSONObject().put("activation","Compose.performTouchInput.coordinate").put("decision",submit).put("posts",posts.size).put("exactTargetTurnRequest",true).put("independentDraftProtected",true).put("scope","synthetic HTTP/native only, no real command or permission grant").toString())
            }
        }
    }
}
