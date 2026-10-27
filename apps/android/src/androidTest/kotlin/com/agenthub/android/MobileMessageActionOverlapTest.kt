package com.agenthub.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** SDK touch targets must stay outside the floating latest control; copies preserve original payloads. */
@Suppress("DEPRECATION")
class MobileMessageActionOverlapTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var server: CommandFixture?=null
    private val copied=java.util.concurrent.CopyOnWriteArrayList<String>()
    private var sharedReply=java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val recorder=object: ClipboardManager {
        private var value: AnnotatedString?=null
        override fun setText(annotatedString: AnnotatedString) { value=annotatedString;copied.add(annotatedString.text) }
        override fun getText(): AnnotatedString?=value
    }
    private val monitor=object: android.app.Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent?): android.app.Instrumentation.ActivityResult? {
            if(intent?.action !in listOf(Intent.ACTION_CHOOSER,"miui.intent.action.MIUI_CHOOSER"))return null
            @Suppress("DEPRECATION") val send=intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
            if(send.action!=Intent.ACTION_SEND||send.type!="text/plain")return null
            assertFalse(send.hasExtra(android.provider.Browser.EXTRA_HEADERS));sharedReply.set(send.getStringExtra(Intent.EXTRA_TEXT))
            return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
        }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"action-hit");session.resetAcceptance()
            session.changeLanguage("en");session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light")
            activity.setContent { CompositionLocalProvider(LocalClipboardManager provides recorder) { AgentHubApp(session) } }
        }
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);server?.close() }
    }
    private fun connect(messages: JSONArray,running: Boolean=false) {
        val target=JSONObject().put("agentId","codex").put("hostId","action-hit-host").put("workspaceId","action-hit-workspace").put("runtimeId","action-hit-runtime")
        val fixture=CommandFixture { request->when(request.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","action-hit-host").put("name","原电脑 100% %@")
                .put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","queue","queue:execute","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","original-thread").put("hostId","action-hit-host").put("title","原聊天 100% %@").put("state",if(running)"running" else "idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","action-hit-host").put("threadId","original-thread").put("readTarget",target)
                .put("messages",messages)
                .put("runtime",JSONObject().put("state",if(running)"running" else "idle").put("turnId",if(running)"original-run" else JSONObject.NULL)
                    .put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("原电脑 100% %@",fixture.endpoint,"") };ui.waitUntil(10_000) { !session.loading&&session.threads.size==1 }
        main { session.selectThread(session.threads.single());session.text="原草稿 100% %@" };ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null }
    }
    private fun message(id: String,role: String,text: String,kind: String,run: String="original-run")=JSONObject()
        .put("id",id).put("role",role).put("text",text).put("kind",kind).put("status","completed").put("runId",run)
    private fun longText(prefix: String)=(1..60).joinToString("\n\n") { "$prefix $it 100% %@" }
    private fun position(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val viewport=ui.onNodeWithTag("message-list")
        viewport.performScrollToNode(hasTestTag(tag))
        val node=ui.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
        val desiredY=viewport.fetchSemanticsNode().boundsInRoot.bottom-34f*activity.resources.displayMetrics.density
        val delta=node.fetchSemanticsNode().boundsInRoot.center.y-desiredY
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scroll->assertTrue(scroll(0f,delta)) }
        var before: String?=null;var since=0L
        ui.waitUntil(8_000) {
            val bounds=node.fetchSemanticsNode().boundsInRoot.toString();val now=android.os.SystemClock.uptimeMillis()
            if(bounds!=before) { before=bounds;since=now };now-since>=700
        }
        val bounds=node.fetchSemanticsNode().boundsInRoot
        val latest=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        assertNotNull("Exercise an actually visible floating latest control",latest)
        val covered=latest!!.contains(bounds.center)
        File(owned.folder,"hit-region-$tag.json").writeText(JSONObject().put("control",tag).put("bounds",bounds.toString())
            .put("latestBounds",latest.toString()).put("centerCovered",covered).put("modelInputs",0).toString())
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"hit-region-$tag")
        assertFalse("Floating latest covers the native touch center of $tag",covered)
        val density=activity.resources.displayMetrics.density
        assertTrue("Native action at least48dp wide",bounds.width/density>=47.5f)
        assertTrue("Native action at least48dp high",bounds.height/density>=47.5f)
        return node
    }
    private fun verify(name: String,original: List<MobileMessage>,expectedCopies: List<String>,reply: String?=null) {
        assertEquals(original,session.messages);assertEquals(expectedCopies,copied.toList());assertEquals(reply,sharedReply.get())
        assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty());assertTrue(server!!.requests.all { it.method=="GET" })
        File(owned.folder,"$name-proof.json").writeText(JSONObject().put("sdkTouch",true).put("originalPayloadMatched",true)
            .put("injectedClipboardRecorder",true).put("systemClipboardTouched",false).put("chooserIntercepted",reply!=null).put("newModelInputs",0).toString())
    }
    @Test fun toolCopyRemainsTouchableBesideFloatingLatestAndKeepsRawDetail() {
        val detail="原输出 100% %@\r\n"+(1..30).joinToString("\r\n") { "owned output $it" }
        connect(JSONArray().put(message("before","user",longText("Original before"),"userMessage"))
            .put(message("tool","tool","Provider · 原活动 100% %@","mcpToolCall").put("detail",detail))
            .put(message("after","user",longText("Original after"),"userMessage","next-run")))
        val original=session.messages
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-toggle-tool"));ui.onNodeWithTag("tool-toggle-tool").performClick()
        position("tool-copy-tool").performTouchInput { click(center) };ui.waitUntil(5_000) { copied.size==1 }
        verify("tool-copy",original,listOf(detail))
    }
    @Test fun codeCopyRemainsTouchableBesideFloatingLatestAndKeepsOriginalUnicode() {
        val code="val original = \"代码 100% %@\"\nprintln(original)\n"
        val body=longText("Original before")+"\n\n```kotlin\n"+code+"```"
        connect(JSONArray().put(message("code","assistant",body,"agentMessage").put("replyPhase","final_answer"))
            .put(message("after","user",longText("Original after"),"userMessage","next-run")))
        val original=session.messages
        val tag="markdown-code-60-copy-code"
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-code"))
        ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        position(tag).performTouchInput { click(center) };ui.waitUntil(5_000) { copied.size==1 }
        verify("code-copy",original,listOf(code))
    }
    @Test fun longOriginalLanguageLabelPreservesCopyWidthAndPayload() {
        val info="原语言 100% %@ ".repeat(16).trimEnd();val code="original Unicode 代码 100% %@\n"
        val body=longText("Original before")+"\n\n```"+info+"\n"+code+"```"
        connect(JSONArray().put(message("long-code","assistant",body,"agentMessage").put("replyPhase","final_answer"))
            .put(message("after","user",longText("Original after"),"userMessage","next-run")))
        val original=session.messages;val tag="markdown-long-code-60-copy-code"
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-long-code"))
        ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(info).assertExists()
        position(tag).performTouchInput { click(center) };ui.waitUntil(5_000) { copied.size==1 }
        verify("long-language-copy",original,listOf(code))
    }
    @Test fun replyCopyAndShareStayReachableAndPreserveTheWholeOriginalBody() {
        val body="原回复 100% %@\n\n"+longText("Original whole reply")
        connect(JSONArray().put(message("reply","assistant",body,"agentMessage").put("replyPhase","final_answer"))
            .put(message("after","user",longText("Original after"),"userMessage","next-run")))
        val original=session.messages
        position("copy-reply-reply").performTouchInput { click(center) };ui.waitUntil(5_000) { copied.size==1 }
        position("share-reply-reply").performTouchInput { click(center) };ui.waitUntil(5_000) { sharedReply.get()!=null }
        verify("reply-copy-share",original,listOf(body),body)
    }
}
