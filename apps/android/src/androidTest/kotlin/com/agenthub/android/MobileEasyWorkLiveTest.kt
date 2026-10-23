package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Requires an isolated real Electron Host; fixture responses cannot satisfy these assertions. */
@RunWith(AndroidJUnit4::class)
class MobileEasyWorkLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(2400)
    private fun execute(path: String) {
        val i=InstrumentationRegistry.getInstrumentation()
        val config=JSONObject(File(InstrumentationRegistry.getArguments().getString("easyWorkConfig")?:error("Real Host examiner required")).readText())
        var witnessSession: MobileSession?=null
        fun evidence(): JSONObject {
            val thread=witnessSession?.selectedThread.orEmpty()
            val connection=URL("http://127.0.0.1:${config.getInt("observerPort")}/evidence?native=1&threadId="+java.net.URLEncoder.encode(thread,"UTF-8")).openConnection() as HttpURLConnection
            connection.connectTimeout=3000;connection.readTimeout=10_000
            return try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
        }
        val initial=evidence();require(initial.optString("status")!="error")
        require(initial.optBoolean("desktopOwnerAlive",true)) { "Owned original Electron is not running" }
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var activity: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };activity!=null }
        val main=activity!!;val session=main.session
        val keptAwake=main.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0
        i.runOnMainSync { main.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        witnessSession=session
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A5-live-$path").apply { mkdirs() }
        fun shot(name: String) {
            if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().size==1)ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
            i.runOnMainSync { androidx.core.view.WindowInsetsControllerCompat(main.window,main.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitForIdle();i.uiAutomation.waitForIdle(300,10_000)
            val bitmap=i.uiAutomation.takeScreenshot()
            try { File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        }
        try {
            i.runOnMainSync { session.resetAcceptance(); session.enterForeground() }
            var discovered: DiscoveredEasyWorkHost?=null
            if(path=="lan"||config.optString("pairingProtocol")=="EWDAP/1") {
                ui.onNodeWithTag("onboarding-add").performClick();ui.onNodeWithTag("agent-provider-easywork").performClick()
                if(config.optBoolean("requireMdnsDiscovery")) {
                    val expected=initial.getJSONObject("lan").getJSONObject("target").getString("desktopDeviceId")
                    ui.runOnIdle { session.easyWorkDiscovery!!.start() }
                    ui.waitUntil(90_000) { discovered=session.easyWorkDiscovery!!.hosts.firstOrNull { !it.known&&it.deviceId==expected };discovered!=null }
                    ui.onNodeWithTag("easywork-discovered-${discovered!!.id}").performScrollTo().assertIsDisplayed()
                    shot("native-easywork-fresh-mdns-discovery")
                    ui.onNodeWithTag("easywork-discovered-${discovered!!.id}").performClick()
                    ui.onNodeWithTag("easywork-qr-description").assertIsDisplayed()
                    assertTrue("Discovery must not authorize an unpaired device",!session.hasClient)
                }
                val grant=config.optString("pairingProtocol")=="EWDAP/1"
                val codeMethod=grant&&config.optString("pairingPresentation")=="code"
                ui.onNodeWithTag("easywork-connection-method").performClick()
                val pairButton=if(codeMethod) {
                    val offer=EasyWorkDeviceOffer.parse(initial.getJSONObject("deviceOffer").getString("deepLink"))
                    ui.onNodeWithTag("easywork-method-通过中继连接").performClick()
                    ui.onNodeWithTag("easywork-relay-url").performTextReplacement(offer.origin)
                    ui.onNodeWithTag("easywork-relay-code").performTextReplacement(offer.code)
                    ui.onNodeWithTag("easywork-relay-code").performImeAction()
                    "easywork-pair-relay"
                } else {
                    ui.onNodeWithTag("easywork-method-扫码连接").performClick()
                    ui.onNodeWithTag("easywork-qr-description").performTextReplacement(if(grant)initial.getJSONObject("deviceOffer").getString("deepLink") else initial.getJSONObject("lan").getString("pairingUri"))
                    ui.onNodeWithTag("easywork-qr-description").performImeAction()
                    "easywork-pair-lan"
                }
                ui.waitForIdle();ui.onNodeWithTag(pairButton).performScrollTo().assertIsDisplayed()
                shot(if(codeMethod)"code-native-form" else "qr-native-form")
                ui.onNodeWithTag(pairButton).performClick()
                if(grant) {
                    ui.waitUntil(60_000) { check(session.error==null);ui.onAllNodesWithTag("easywork-connecting").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { text->text.text.contains("请在电脑")||text.text.contains("请在已登录") } } }
                    assertTrue("Phone must not expose an account login action",ui.onAllNodesWithTag("easywork-login").fetchSemanticsNodes().isEmpty())
                    shot("native-device-authorization-pending")
                    val http=URL("http://127.0.0.1:${config.getInt("observerPort")}/control").openConnection() as HttpURLConnection
                    http.requestMethod="POST";http.doOutput=true;http.connectTimeout=3000;http.readTimeout=40_000
                    http.setRequestProperty("content-type","application/json");http.setRequestProperty("x-easywork-acceptance",config.getString("authorization"))
                    try {
                        http.outputStream.use { it.write(JSONObject().put("operation","desktop.device-claim-approve").toString().toByteArray()) }
                        assertTrue("Original Desktop decision must be acknowledged",JSONObject(http.inputStream.bufferedReader().use { it.readText() }).getBoolean("accepted"))
                    } finally { http.disconnect() }
                }
                ui.waitUntil(90_000) { session.hasClient&&session.selectedClient.online }
                ui.onNodeWithTag("sheet-done").performClick()
                if(grant)ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,if(path=="relay")"relay-only" else "lan-only") }
                if(discovered!=null) {
                    // Exercise the real foreground lifecycle with the newly saved
                    // binding, then require the production pinned discovery read.
                    ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use { it.readBytes() }
                    ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
                    ui.waitUntil(20_000) { var resumed=false;i.runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it===main } };resumed }
                    ui.waitUntil(60_000) { session.canCreateProjectless&&!session.loading }
                    val connection=session.selectedClient.agentConnection!!
                    val stored=EasyWorkCredential.decode(JSONObject(NativeSessionStorage(i.targetContext).read("easywork-credential:${connection.credentialRef}")!!))
                    val candidate=connection.candidates.first { it.transportId=="lan-wss" }
                    val descriptor=runBlocking { withContext(Dispatchers.Main) { EasyWorkPairing.readHost(candidate.endpoint,candidate.peerFingerprint,stored.binding.target,stored.binding.desktopPublicKey) } }
                    assertEquals(stored.binding.target,descriptor.target)
                    assertEquals(stored.binding.desktopPublicKey,descriptor.publicKey)
                    File(folder,"saved-discovery-pinned-probe.json").writeText(JSONObject().put("pinnedDescriptorRead",true).put("targetMatchesBinding",true).put("publicKeyMatchesBinding",true).put("mainScannerReadsPass",true).toString())
                    ui.waitUntil(90_000) { session.easyWorkDiscovery!!.hosts.any { it.known&&it.id==session.selectedClient.id&&it.reachable } }
                    assertEquals(discovered!!.deviceId,session.selectedClient.agentConnection!!.target.hostId)
                    val knownId=session.selectedClient.id
                    ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("add-client").performClick();ui.onNodeWithTag("agent-provider-easywork").performClick()
                    ui.onNodeWithTag("easywork-discovered-$knownId").performScrollTo().assertIsDisplayed()
                    shot("native-easywork-authenticated-saved-discovery")
                    ui.onNodeWithTag("easywork-discovered-$knownId").performClick()
                    ui.waitUntil(60_000) { session.selectedClient.id==knownId&&session.selectedClient.online&&session.canCreateProjectless }
                    ui.waitUntil(60_000) { check(session.error==null);ui.onAllNodesWithTag("sheet-clients").fetchSemanticsNodes().isNotEmpty() }
                    ui.onNodeWithTag("sheet-done").performClick()
                    ui.onNodeWithTag("message-input").assertIsDisplayed()
                }
            } else {
                val identity=runBlocking { EasyWorkIdentity.load(NativeSessionStorage(i.targetContext)) }
                // Existing loopback DEV verifier only; this is not an OIDC token or public login proof.
                val token="easyai-ios-simulator-v1."+easyBase64(easyCanonical(JSONObject().put("deviceId",identity.deviceId).put("publicKey",identity.publicKey)).toByteArray())
                runBlocking { withContext(Dispatchers.Main) { session.connectEasyWorkRelay("EasyWork 中继验收","http://127.0.0.1:${config.getInt("relayPort")}",initial.getJSONObject("relayOffer").getString("pairingCode"),token) } }
                ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"relay-only") }
            }
            ui.waitUntil(60_000) { check(session.error==null) { "Native operation failed: ${session.error}" };session.canCreateProjectless&&session.models.isNotEmpty()&&!session.loading }
            ui.runOnIdle { session.newChat();session.configure(GenerationSettings(model="easyai/GLM-5.2",effort="high",permissionMode="allow_all",collaborationMode="default")) }
            val marker="A5_${path}_${UUID.randomUUID().toString().take(8)}"
            val text="安卓真实验收：只回复 $marker，不调用工具，不修改文件。"
            ui.onNodeWithTag("message-input").performTextReplacement(text);ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(180_000) { check(session.error==null) { "Native operation failed: ${session.error}" };!session.running&&session.transferStatus==null&&session.messages.any { it.role=="assistant"&&it.text.trim()==marker } }
            val thread=session.selectedThread;assertTrue(thread.isNotEmpty());val message=session.messages.single { it.role=="assistant"&&it.text.trim()==marker };assertFalse(message.runId.isNullOrBlank())
            if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));shot("real-completed-reply")
            val nativeOperations=session.operationRecords.filter { it.kind=="reply"&&JSONObject(it.intent).optString("text")==text };assertEquals(1,nativeOperations.size)
            var observed: JSONObject?=null
            ui.waitUntil(30_000) { observed=evidence();observed!!.getJSONObject("independentDesktop").getJSONArray("executions").objects().any { it.getString("id")==message.runId&&it.getJSONObject("run").optString("status")=="completed" } }
            val source=observed!!.getJSONObject("independentDesktop").getJSONArray("executions").objects().single { it.getString("id")==message.runId }
            assertTrue("Actual model provider usage required",source.getJSONArray("providerUsage").length()>0)
            val writes=observed!!.getJSONArray("requests").objects().filter { val r=it.getJSONObject("request");r.getString("method")=="turn/start"&&r.getJSONObject("params").optString("clientRequestId")==nativeOperations.single().id }
            assertEquals("One explicit model submission",1,writes.size);assertEquals(if(path=="lan")"lan-wss" else "relay-http",writes.single().getString("transportId"))
            val proof=JSONObject().put("path",path).put("scenario",config.optString("scenario","baseline")).put("threadId",thread).put("runId",message.runId).put("operationId",nativeOperations.single().id).put("marker",marker).put("nativeInput",text).put("modelUsageVerified",true).put("independentDesktopVerified",true).put("api",android.os.Build.VERSION.SDK_INT)
            if(config.optString("pairingProtocol")=="EWDAP/1")proof.put("pairingProtocol","EWDAP/1").put("phoneAccountLoginRequired",false).put("pairingPresentation",config.optString("pairingPresentation","qr"))
            discovered?.let { proof.put("mdnsDiscovery",JSONObject().put("serviceId",it.id).put("endpoint",it.endpoint).put("deviceId",it.deviceId).put("unpairedRowVisible",true).put("firstConnectionRequiresPairing",true).put("authenticatedSavedRouteReachable",true)) }
            File(folder,"baseline-proof.json").writeText(proof.toString())
            val scenarios=EasyWorkLiveScenarios(ui,session,config,folder,::evidence,::shot)
            when(config.optString("scenario","baseline")) {
                "receive"->scenarios.receive()
                "observability"->scenarios.observability()
                "observability-read"->scenarios.observabilityRead()
                "resources"->scenarios.resourcesAndProject()
                "questions"->scenarios.questions()
                "controls"->scenarios.controls()
                "plans"->scenarios.plans()
                "recovery"->scenarios.recovery()
                "approvals"->scenarios.approvals()
                "history"->scenarios.history()
                "history-read"->scenarios.historyRead()
                "device-grants"->scenarios.deviceGrants()
                "device-security"->scenarios.deviceSecurity()
                "full"-> {
                    scenarios.resourcesAndProject();scenarios.questions();scenarios.controls()
                    scenarios.plans();scenarios.approvals();scenarios.historyRead();scenarios.recovery()
                }
                "baseline"->Unit
                else->error("Unknown real native scenario")
            }
            File(folder,"native-proof.json").writeText(proof.put("scenarios",scenarios.passed).toString())
        } catch(failure: Throwable) {
            runCatching { File(folder,"discovery-diagnostic.json").writeText(JSONObject().put("selectedClientId",session.selectedClient.id).put("online",session.selectedClient.online).put("canCreate",session.canCreateProjectless).put("needsDiscoveryPermission",session.easyWorkDiscovery?.needsPermission).put("hosts",org.json.JSONArray(session.easyWorkDiscovery?.hosts.orEmpty().map { h->JSONObject().put("id",h.id).put("endpoint",h.endpoint).put("deviceId",h.deviceId).put("known",h.known).put("reachable",h.reachable) })).put("candidates",org.json.JSONArray(session.selectedClient.agentConnection?.candidates.orEmpty().map { c->JSONObject().put("transport",c.transportId).put("endpoint",c.endpoint).put("pin",c.peerFingerprint) })).toString()) }
            runCatching { shot("failed-native") };throw failure
        }
        finally { i.runOnMainSync { session.leaveForeground();if(!keptAwake)main.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    }
    @Test fun realLanPairingCreateAndModel()=execute("lan")
    @Test fun realRelayPairingCreateAndModel()=execute("relay")
}
