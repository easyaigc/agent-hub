package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.agenthub.core.FailureCopy
import com.agenthub.core.MobileConnectionFailureKind
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.net.SocketTimeoutException
import java.util.UUID

/** Product native controls with synthetic reads; no model or original-owner writes. */
class MobileLocalizedControlsTest {
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val target=TargetScope("codex",null,null,"localized-controls","workspace",UUID.randomUUID().toString())
    private val rawModel="设置 100% %@ GPT-LONG-原模型"
    private var failure: Exception?=null
    private var easyWork=false
    private var writes=0
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",target.hostId).put("name","Synthetic localized controls").put("connectionMode","desktop-runtime")
                .put("readTarget",CodexCommandCodec.target(target)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","models:read","generation-settings","generation-speed","commands:target-bound:v1")))
                .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","controls").put("hostId",target.hostId).put("title","设置").put("state","idle"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                failure?.let { throw it }
                return HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target))
                    .put("messages",JSONArray().put(JSONObject().put("id","original").put("role","assistant").put("text","复制 100% %@")))
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
            }
        }
        val native=NativeCodexCommands({ "" })
        val commands=object: CodexCommands by native {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("raw-model",rawModel,true,"low",listOf("low","xhigh"),"standard",listOf("standard","fast","ultrafast")))
            override fun permissionOptions(client: MobileClient)=(if(easyWork) EasyWorkAgentClient(EasyWorkChannels { error("No channel permitted") }).permissionOptions(client) else native.permissionOptions(client))+
                AgentPermissionOption("raw-permission","设置权限 100% %@","复制 100% %@")
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt { writes++;error("Display acceptance must never send") }
        }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"localized-controls");session.resetAcceptance(reader,commands)
            session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light");ui.activity.readNetworkMonitor.stop()
            java.io.File(owned.folder,"actual-profile.json").writeText(JSONObject().put("appearance",session.appearance)
                .put("fontScale",ui.density.fontScale).put("orientation",ui.activity.resources.configuration.orientation).toString())
        }
        main { session.connect("Synthetic localized controls","https://fixture.invalid","") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.size==1 }
        main { session.selectThread(session.threads.single()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose&&session.models.isNotEmpty() }
        main { session.text="设置 100% %@ 原草稿" }
    }
    @After fun finish() {
        if(::owned.isInitialized) {
            main { session.leaveForeground() };assertEquals(0,writes);assertTrue(session.operationRecords.isEmpty());owned.verifyDefault()
        }
    }
    private fun tap(tag: String) { ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick();ui.waitForIdle() }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();var window: android.view.Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(i.targetContext.packageName,view.context.packageName)
                fun find(v: android.view.View) {
                    if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                    if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))
                };find(view.rootView);assertTrue(window!!.decorView.rootView===view.rootView)
            }
        val w=checkNotNull(window);var image: android.graphics.Bitmap?=null
        val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync { w.decorView.postOnAnimation { w.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS))
        i.runOnMainSync { assertTrue(w.decorView.hasWindowFocus());image=android.graphics.Bitmap.createBitmap(w.decorView.width,w.decorView.height,android.graphics.Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(w,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            java.io.File(owned.folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            java.io.File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-dialog-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).toString())
        }finally { bitmap.recycle() }
    }
    private fun openGeneration() {
        ui.onNodeWithTag("generation-settings").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("sheet-title").fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("sheet-title").assertTextEquals("Model, reasoning, and permissions")
    }
    private fun snapshotDraft()=main { session.suggestionDraft }
    private fun assertDraft(expected: com.agenthub.core.SuggestionDraft) {
        val actual=snapshotDraft();assertEquals(expected.targetKey,actual.targetKey)
        assertEquals(expected.text,actual.text);assertEquals(expected.revision,actual.revision)
    }
    @Test fun translatedGenerationMenusKeepWireIdsAndFullOriginalModelAccessibility() {
        val draft=snapshotDraft();openGeneration()
        tap("model-picker");ui.onNodeWithTag("model-raw-model").assertTextContains(rawModel+" · Recommended").performClick()
        ui.onNodeWithTag("effort-slider").assertContentDescriptionEquals(NativeL10n(ui.activity,"en").tr("推理强度"))
        tap("effort-picker");ui.onNodeWithTag("effort-xhigh").assertTextContains("Extra high").performClick()
        tap("speed-picker");ui.onNodeWithTag("speed-ultrafast").assertTextContains("Extra fast").performClick()
        tap("permission-full-access");ui.onNodeWithTag("permission-full-access").assertTextContains("Full access")
        shot("english-generation-permissions")
        ui.onNodeWithTag("sheet-done").performClick();ui.waitForIdle()
        ui.onNodeWithTag("generation-settings").assertContentDescriptionEquals("Model, "+rawModel+", reasoning effort, Extra high, speed, Extra fast")
        assertEquals("raw-model",session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("ultrafast",session.generationSettings.speed);assertEquals("full-access",session.generationSettings.permissionMode)
        main { session.changeLanguage("zh-Hans") };ui.waitForIdle()
        ui.onNodeWithTag("generation-settings").assertContentDescriptionEquals("模型，"+rawModel+"，推理强度，极高，速度，极速")
        assertDraft(draft);assertEquals("设置",session.threads.single().title)
    }
    @Test fun providerPermissionTitleAndDetailAreLiteralEvenWhenTheyMatchCatalogKeys() {
        openGeneration();tap("permission-raw-permission")
        ui.onNodeWithTag("permission-raw-permission").assertTextContains("设置权限 100% %@").assertTextContains("复制 100% %@")
        shot("english-provider-permission-raw")
        ui.onNodeWithTag("sheet-done").performClick();ui.waitForIdle()
        ui.onNodeWithTag("permission-settings").assertContentDescriptionEquals("Permission mode, 设置权限 100% %@")
        assertEquals("raw-permission",session.generationSettings.permissionMode)
    }
    @Test fun compiledEasyWorkPermissionCopyIsEnglishWithoutOpeningAChannel() {
        main { easyWork=true };openGeneration();tap("permission-confirm_high_risk")
        val english=NativeL10n(ui.activity,"en")
        val option=session.permissionOptions.single { it.id=="confirm_high_risk" }
        ui.onNodeWithTag("permission-confirm_high_risk").assertTextContains(english.tr(option.title)).assertTextContains(english.tr(option.detail))
        assertEquals("confirm_high_risk",session.generationSettings.permissionMode)
        shot("english-easywork-risk-copy")
    }
    private fun failRead(next: Exception) {
        main { session.leaveForeground();failure=next;session.loadHistory(userInitiated=true)?.join() }
        ui.waitUntil(5_000) { session.error!=null }
    }
    @Test fun typedNetworkCopyChangesLanguageWithoutRewritingOriginalErrorOrDraft() {
        val draft=snapshotDraft();val messages=session.messages.toList()
        failRead(HostReadError("原网络错误 100% %@",SocketTimeoutException("raw timeout"),true))
        val raw=session.error
        val en=NativeL10n(ui.activity,"en")
        ui.onNodeWithTag("session-error").assertTextEquals(en.tr(FailureCopy.message(MobileConnectionFailureKind.TIMEOUT)))
        ui.onNodeWithText(en.tr(FailureCopy.connectionTitle)).assertExists()
        shot("english-typed-timeout")
        main { session.changeLanguage("zh-Hans") };ui.waitForIdle()
        ui.onNodeWithTag("session-error").assertTextEquals(FailureCopy.message(MobileConnectionFailureKind.TIMEOUT))
        assertEquals(raw,session.error);assertEquals(messages,session.messages);assertDraft(draft)
    }
    @Test fun laterProviderFailureCannotInheritConnectionTranslation() {
        failRead(HostReadError("raw timeout",SocketTimeoutException(),true))
        main { session.error="设置" };ui.waitForIdle()
        ui.onNodeWithTag("session-error").assertTextEquals("设置")
        ui.onNodeWithText(NativeL10n(ui.activity,"en").tr(FailureCopy.defaultTitle)).assertExists();assertNull(session.errorProductKey)
        ui.onNodeWithTag("dismiss-error").performClick();assertNull(session.error)
    }
    @Test fun eachKnownPlatformFailureUsesTheCurrentCatalogAndUnknownProviderBodyStaysRaw() {
        val en=NativeL10n(ui.activity,"en");val draft=snapshotDraft()
        val failures=listOf(
            javax.net.ssl.SSLHandshakeException("tls").apply { initCause(java.security.cert.CertificateException("certificate")) } to MobileConnectionFailureKind.CERTIFICATE,
            javax.net.ssl.SSLException("tls") to MobileConnectionFailureKind.SECURE_CONNECTION,
            java.net.SocketException("network").apply { initCause(android.system.ErrnoException("connect",android.system.OsConstants.ENETUNREACH)) } to MobileConnectionFailureKind.NO_NETWORK,
            SocketTimeoutException("timeout") to MobileConnectionFailureKind.TIMEOUT,
            java.net.UnknownHostException("host") to MobileConnectionFailureKind.UNREACHABLE,
            java.net.SocketException("reset") to MobileConnectionFailureKind.LOST)
        for((cause,kind) in failures) {
            failRead(HostReadError("provider raw",cause,true))
            ui.onNodeWithTag("session-error").assertTextEquals(en.tr(FailureCopy.message(kind)))
            ui.onNodeWithTag("dismiss-error").performClick()
        }
        failRead(HostReadError("设置 100% %@"))
        ui.onNodeWithTag("session-error").assertTextEquals(session.error!!)
        assertTrue(session.error!!.endsWith("设置 100% %@"))
        assertNull(session.errorProductKey);assertDraft(draft)
    }
}
