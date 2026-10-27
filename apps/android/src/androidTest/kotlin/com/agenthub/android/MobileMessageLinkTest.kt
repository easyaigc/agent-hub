package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class MobileMessageLinkTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val intents=mutableListOf<android.content.Intent>()
    private val monitor=object: android.app.Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: android.content.Intent?): android.app.Instrumentation.ActivityResult? {
            if(intent?.action==android.content.Intent.ACTION_VIEW&&intent.data?.toString() in listOf("https://example.com/owned-link?case=links","mailto:owned@example.com")) {
                intents.add(android.content.Intent(intent))
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
            return null
        }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"message-links")
            session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light")
        }
    }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } }
    }
    private fun connect(f: ExecutionFixture,reference: String) {
        f.streamText="[Owned link]("+reference+")"
        main { session.connect("Owned links computer",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.id=="old-assistant" } }
        main { session.text="Original unsent link draft 100% %@" }
    }
    private fun tapLink() {
        val node=ui.onNodeWithTag("markdown-old-assistant-0").performScrollTo().assertIsDisplayed()
        var layout: TextLayoutResult?=null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get->val result=mutableListOf<TextLayoutResult>();get(result);layout=result.single() }
        node.performTouchInput { click(checkNotNull(layout).getBoundingBox(2).center) }
    }
    @Test fun nativeParsersShareAllVersionedLinkVectors() {
        val r=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("message-links-v1.json").bufferedReader().use { it.readText() })
        assertEquals(1,r.getInt("schemaVersion"))
        for(row in r.getJSONArray("cases").objects()) {
            val actual=MessageLinkDestination.resolve(row.getString("reference"))
            assertEquals(row.getString("name"),row.getString("expected"),actual.kind)
            if(actual.kind=="attachment")assertEquals(row.getString("attachmentId"),actual.attachmentId)
        }
        assertEquals("computerFile",MessageLinkDestination.resolve("C:\\owned\\file.txt").kind)
        assertEquals("unsupported",MessageLinkDestination.resolve("https://[bad").kind)
        fun inline(reference: String)=NativeMarkdown.inline(NativeMarkdown.parse("[Owned]("+reference+")"),androidx.compose.ui.graphics.Color.Blue)
        assertEquals(1,inline("https://example.com/").getLinkAnnotations(0,5).size)
        assertEquals(1,inline("mailto:owned@example.com").getLinkAnnotations(0,5).size)
        for(reference in listOf("http://localhost:3000","https://user:secret@example.com","javascript:alert%281%29"))
            assertTrue(inline(reference).getLinkAnnotations(0,5).isEmpty())
    }
    @Test fun blockedComputerAndUnsafeLinksShowNativeErrorsWithoutOutboundLaunch() {
        for((reference,key) in listOf("http://localhost:4567/private" to "此网址指向电脑的本机地址，手机无法直接访问。请使用手机可连接的局域网或 VPN 网址。",
            "javascript:alert%281%29" to "手机暂不支持此类链接，可复制后在对应应用中打开。",
            "https://user:secret@example.com/owned" to "手机暂不支持此类链接，可复制后在对应应用中打开。")) {
            ExecutionFixture().use { f->connect(f,reference);tapLink()
                ui.onNodeWithTag("message-link-error").assertExists()
                ui.onNodeWithText(NativeL10n(ui.activity,session.language).tr(key)).assertIsDisplayed()
                owned.dialogShot(if(reference.startsWith("http://localhost"))"computer-web" else if(reference.startsWith("javascript"))"unsupported" else "credentials")
                ui.onNodeWithTag("message-link-dismiss").performClick()
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("message-link-error").fetchSemanticsNodes().isEmpty() }
                assertTrue(intents.isEmpty());assertTrue(f.server.requests.none { it.method=="POST" });assertEquals("Original unsent link draft 100% %@",session.text)
            }
        }
    }
    @Test fun publicAndExternalLinksUseNamedSystemIntentsWithoutHostCredentials() {
        for(reference in listOf("https://example.com/owned-link?case=links","mailto:owned@example.com")) {
            ExecutionFixture().use { f->connect(f,reference);val before=intents.size;tapLink()
                ui.waitUntil(5_000) { intents.size==before+1 }
                val intent=intents.last()
                assertEquals(reference,intent.dataString);assertEquals(android.content.Intent.ACTION_VIEW,intent.action)
                val headers=intent.getBundleExtra(android.provider.Browser.EXTRA_HEADERS)
                assertTrue(headers==null || headers.keySet().all { it.equals("Accept-Language",ignoreCase=true) })
                assertFalse(intent.hasExtra("Authorization"))
                @Suppress("DEPRECATION") val referrer=intent.getParcelableExtra<android.net.Uri>("android.intent.extra.REFERRER")
                if(referrer!=null) { assertEquals("android-app",referrer.scheme);assertEquals(ui.activity.packageName,referrer.host) }
                if(reference.startsWith("https"))assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"))
                assertTrue(f.server.requests.none { it.method=="POST" });assertEquals("Original unsent link draft 100% %@",session.text)
            }
        }
    }
    @Test fun streamingMessageRevisionKeepsExistingCodeScrollOwnership() {
        ExecutionFixture().use { f->connect(f,"https://example.com/owned-link?case=links")
            val fence=96.toChar().toString().repeat(3)
            val code=fence+"text\n"+("owned original line 100% %@ ".repeat(90))+"\n"+fence
            f.streamText=code;main { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.id=="old-assistant"&&it.text==code } }
            val scroll=ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                hasAnyAncestor(hasTestTag("markdown-old-assistant-0-code-body")),useUnmergedTree=true)
            ui.waitUntil(10_000) { runCatching { scroll.fetchSemanticsNode() }.isSuccess }
            scroll.performTouchInput { swipeLeft() }
            val before=scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
            assertTrue("Original code must actually be scrolled",before>0f)
            f.streamText=code+"\n\nAn independently parsed streaming tail."
            main { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.text==f.streamText } }
            ui.onNodeWithText("An independently parsed streaming tail.").assertExists()
            val after=scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
            assertEquals("Message revision retires link callbacks without recreating existing code scroll",before,after,1f)
            assertEquals("Original unsent link draft 100% %@",session.text);assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun navigationAndMessageRevisionInvalidateCapturedLinkOwner() {
        ExecutionFixture().use { f->connect(f,"http://localhost:4567/private")
            lateinit var owner: MobileSession.MessageLinkContext
            main { owner=session.messageLinkContext(session.messages.single { it.id=="old-assistant" }) }
            assertTrue(session.messageLinkCurrent(owner))
            tapLink();ui.onNodeWithTag("message-link-error").assertExists()
            main { session.newChat() }
            ui.onNodeWithTag("message-link-error").assertDoesNotExist();assertFalse(session.messageLinkCurrent(owner))
            main { session.selectThread(session.threads.first()) }
            ui.waitUntil(10_000) { !session.historyLoading }
            assertFalse(session.messageLinkCurrent(owner))
            main { owner=session.messageLinkContext(session.messages.single { it.id=="old-assistant" }) }
            f.streamText="[Changed original](http://localhost:4567/private)"
            main { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.text.startsWith("[Changed original]") } }
            assertFalse(session.messageLinkCurrent(owner))
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
}
