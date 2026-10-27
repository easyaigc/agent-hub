package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Production Compose/session/TLS with a public address hint, not real LAN/VPN or desktop/model proof. */
@RunWith(AndroidJUnit4::class)
class MobilePrivateConnectionUiTest {
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            if(InstrumentationRegistry.getArguments().getString("privateForeground")=="1") {
                val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity")
                val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                assertTrue(result.contains("Status: ok"))
            }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    private val args get()=InstrumentationRegistry.getArguments()
    private val endpoint get()=args.getString("privateEndpoint")!!
    private val control get()=args.getString("privateControl")!!
    private val holder=mutableStateOf<MobileSession?>(null)
    private val session get()=holder.value!!
    private lateinit var store: NativeSessionStorage
    private val id="um04-private-fixture"
    private val tag get()="codex-discovered-$id"
    @Before fun prepare() {
        store=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"private-ui-"+UUID.randomUUID())
        holder.value=MobileSession(store)
        mode("normal")
    }
    @After fun stop() { holder.value?.let { s -> runBlocking { withContext(Dispatchers.Main) { s.discovery?.stop();s.leaveForeground() } } } }
    private fun mode(value: String) {
        val c=URL(control+"/mode").openConnection() as HttpURLConnection
        try { c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write(JSONObject().put("mode",value).toString().toByteArray()) };assertEquals(200,c.responseCode);c.inputStream.close() } finally { c.disconnect() }
    }
    private fun stats()=URL(control+"/stats").openStream().bufferedReader().use { JSONObject(it.readText()) }
    private fun postCount()=stats().getJSONArray("requests").objects().count { it.getString("method")=="POST" }
    private fun show() {
        ui.setContent { key(holder.value) { AgentHubApp(session) } }
        ui.runOnIdle { session.resetAcceptance();session.discovery=CodexHostDiscovery(InstrumentationRegistry.getInstrumentation().targetContext,session,store);session.discovery!!.start() }
    }
    private fun form() { ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("add-client").performClick() }
    private fun offer() {
        ui.runOnIdle { session.discovery!!.offerHint(endpoint,id,"UM04 isolated computer") }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitAuthorization() {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag("codex-host-key").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun authorize() {
        awaitAuthorization()
        ui.onNodeWithTag("codex-host-key").performScrollTo().performTextInput("fixture-host-key-".repeat(3))
        ui.onNodeWithTag("codex-authorize-connect").performScrollTo().performClick()
    }
    @Test fun selectingAndCancellingDoesNotAuthorizeOrSaveAKey() {
        show();form();offer();ui.onNodeWithTag(tag).performClick();awaitAuthorization()
        ui.onNodeWithTag("codex-authorize-connect").assertIsNotEnabled()
        assertEquals(0,postCount());assertFalse(session.hasClient)
        ui.onNodeWithTag("codex-host-key").performTextInput("discard-this-key")
        ui.onNodeWithTag("codex-authorize-cancel").performScrollTo().performClick()
        ui.onNodeWithTag(tag).performScrollTo().performClick();awaitAuthorization()
        ui.onNodeWithTag("codex-host-key").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        assertEquals(0,postCount());assertFalse(session.hasClient)
    }
    private fun shot(name: String, expectedSurface: Int? = null) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(200,5000)
        val run=args.getString("privateRun")!!;require(run.matches(Regex("[a-f0-9-]+")))
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/UM04/$run").apply { mkdirs() }
        val deadline=System.currentTimeMillis()+5000
        while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!=i.targetContext.packageName && System.currentTimeMillis()<deadline) Thread.sleep(40)
        assertEquals("Capture only the actual visible native App",i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        var image=i.uiAutomation.takeScreenshot()
        val renderedDeadline=System.currentTimeMillis()+5000
        while(expectedSurface!=null && image.getPixel(image.width/2,image.height*3/5)!=expectedSurface && System.currentTimeMillis()<renderedDeadline) {
            image.recycle();Thread.sleep(40);image=i.uiAutomation.takeScreenshot()
        }
        try {
            if(expectedSurface!=null) assertEquals("The actual frame must contain this appearance's opaque surface",expectedSurface,image.getPixel(image.width/2,image.height*3/5))
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { image.recycle() }
    }
    @Test fun discoveredRowDoesNotClaimUntilExplicitClickAndShowsConnectedCheck() {
        show();form();offer();ui.onNodeWithTag("$tag-icon",useUnmergedTree=true).assertExists()
        ui.onNodeWithTag(tag).assertIsEnabled();assertEquals(0,postCount());assertFalse(session.hasClient);shot("discovered-before-explicit-click")
        ui.onNodeWithTag(tag).performClick();authorize();ui.waitUntil(20_000) { session.hasClient && !session.connectionBusy }
        assertEquals(1,postCount());assertNotNull(session.selectedClient.codexDirect)
        ui.onNodeWithTag("add-client").performClick();ui.onNodeWithTag("$tag-connected",useUnmergedTree=true).assertExists()
        ui.onNodeWithText("已连接").assertExists();assertEquals(1,postCount());shot("explicit-connected-check")
    }
    @Test fun pendingClaimDisablesRepeatedClickAndCancel() {
        show();form();offer();mode("hold-claim");ui.onNodeWithTag(tag).performClick();authorize()
        ui.waitUntil(10_000) { session.connectionBusy };ui.onNodeWithTag("codex-authorize-connect").assertIsNotEnabled()
        ui.onNodeWithTag("cancel-client-form").assertIsNotEnabled();ui.onNodeWithTag("codex-discovery-connecting").assertExists();shot("claim-progress-disabled")
        ui.waitUntil(20_000) { !session.connectionBusy };assertEquals(1,postCount());assertTrue(session.hasClient)
    }
    @Test fun failedClaimKeepsDiscoveryAndOriginalDraftAndCredential() {
        show();ui.runOnIdle { session.text="保留原草稿" };store.setToken(id,"fixture-legacy-token-".repeat(2));form();offer();mode("bad-tag")
        ui.onNodeWithTag(tag).performClick();authorize();ui.waitUntil(20_000) { !session.connectionBusy && ui.onAllNodesWithTag("client-form-error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("codex-host-key").assertExists();ui.onNodeWithTag("codex-scan-qr").assertDoesNotExist()
        ui.onNodeWithText("电脑凭据校验失败，原连接已保留").assertExists()
        assertFalse(session.hasClient);assertEquals("保留原草稿",session.text);assertEquals("fixture-legacy-token-".repeat(2),store.token(id));assertEquals(1,postCount());shot("failed-keeps-form-draft-and-key")
    }
    @Test fun encryptedConnectionReopensWithoutNewClaim() {
        show();form();offer();ui.onNodeWithTag(tag).performClick();authorize();ui.waitUntil(20_000) { session.hasClient && !session.connectionBusy }
        ui.runOnIdle { session.text="重开保留的草稿" };ui.waitUntil(10_000) { store.metadata()?.optJSONObject("drafts")?.toString()?.contains("重开保留的草稿")==true }
        ui.runOnIdle { session.discovery?.stop();session.leaveForeground();holder.value=MobileSession(store).also { it.restore() } }
        ui.waitUntil(15_000) { !session.restoring && session.hasClient }
        assertEquals("重开保留的草稿",session.text);assertEquals(1,postCount());ui.onNodeWithTag("message-input").assertTextEquals("重开保留的草稿");shot("connection-and-draft-reopened")
    }
    @Test fun foregroundGenerationDropsTransientRowsAndKeepsVerifiedClientWithoutClaim() {
        show();form();offer();ui.onNodeWithTag(tag).performClick();authorize();ui.waitUntil(20_000) { session.hasClient && !session.connectionBusy }
        val key=store.token(id);ui.onNodeWithTag("add-client").performClick()
        ui.runOnIdle {
            session.discovery!!.offerHint(endpoint,"untrusted-transient-host","Transient address hint")
            assertTrue(session.discovery!!.hosts.any { it.id=="untrusted-transient-host" })
            session.discovery!!.stop()
            assertFalse(session.discovery!!.hosts.any { it.id=="untrusted-transient-host" })
            assertTrue(session.discovery!!.hosts.any { it.id==id && it.known })
            session.discovery!!.start()
        }
        ui.onNodeWithTag(tag).assertExists();assertEquals(key,store.token(id));assertEquals(1,postCount());shot("foreground-generation-retains-only-saved-connection")
    }
    @Test fun easyWorkUnpairedRowKeepsAutomaticFormAndDraftWhenLegacyHostRejects() {
        show();ui.runOnIdle { session.text="EasyWork 连接前原草稿";session.easyWorkDiscovery=EasyWorkDiscovery(InstrumentationRegistry.getInstrumentation().targetContext,session,store) }
        form();ui.onNodeWithTag("agent-provider-easywork").performClick();mode("easywork-legacy")
        ui.runOnIdle { session.easyWorkDiscovery!!.offerVPN(listOf(DiscoveredEasyWorkHost("um04-ew-ui","UM04 EasyWork",endpoint,"um04-easywork-desktop",networkKind="vpn"))) }
        ui.onNodeWithTag("easywork-discovered-um04-ew-ui").assertExists();assertEquals(0,postCount())
        ui.onNodeWithTag("easywork-discovered-um04-ew-ui").performClick()
        ui.waitUntil(15_000) { !session.connectionBusy && ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("easywork-discovery-rescan").assertExists();ui.onNodeWithTag("easywork-qr-description").assertDoesNotExist()
        assertEquals("EasyWork 连接前原草稿",session.text);assertFalse(session.hasClient);assertEquals(0,postCount());shot("easywork-explicit-discovery-rejection-keeps-original")
    }
    @Test fun sharedProviderRowGeometryShowsVPNAndGreenCheckInBothAppearances() {
        val dark=mutableStateOf(false)
        ui.setContent { MaterialTheme(colorScheme=if(dark.value) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) { Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { DiscoveredComputerRow("同一台电脑","VPN · 已连接","vpn-row",true,false) {} } }
        } }
        ui.onNodeWithTag("vpn-row").assertIsNotEnabled();ui.onNodeWithText("VPN · 已连接").assertExists()
        val icon=ui.onNodeWithTag("vpn-row-icon",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val name=ui.onNodeWithText("同一台电脑",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val check=ui.onNodeWithTag("vpn-row-connected",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.right<name.left && name.right<check.left);shot("shared-vpn-row-light",lightColorScheme().surface.toArgb())
        ui.runOnIdle { dark.value=true };shot("shared-vpn-row-dark",darkColorScheme().surface.toArgb())
    }
}
