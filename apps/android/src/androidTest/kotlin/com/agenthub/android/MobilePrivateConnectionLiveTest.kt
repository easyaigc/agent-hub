package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
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
import java.security.MessageDigest
import java.util.UUID

/** Opt-in real private HTTPS/original Electron acceptance. No Host token/QR/manual connection is supplied. */
@RunWith(AndroidJUnit4::class)
class MobilePrivateConnectionLiveTest {
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            val p=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity")
            assertTrue(android.os.ParcelFileDescriptor.AutoCloseInputStream(p).bufferedReader().use { it.readText() }.contains("Status: ok"))
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    private val args get()=InstrumentationRegistry.getArguments()
    private val control get()=args.getString("privateLiveControl")!!
    private val holder=mutableStateOf<MobileSession?>(null)
    private val session get()=holder.value!!
    private lateinit var store: NativeSessionStorage
    private fun read(path: String): JSONObject=URL(control+"/um04/"+path).openConnection().apply { connectTimeout=5000;readTimeout=90_000 }.getInputStream().bufferedReader().use { JSONObject(it.readText()) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(200,5000)
        val deadline=System.currentTimeMillis()+5000
        while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!=i.targetContext.packageName && System.currentTimeMillis()<deadline)Thread.sleep(40)
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=args.getString("privateLiveRun")!!;require(run.matches(Regex("[a-f0-9-]+")))
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/UM04-live/$run").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot();try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun contextProof(name: String) {
        val s=holder.value?:return
        fun field(key: String)=MobileSession::class.java.getDeclaredField(key).apply { isAccessible=true }.get(s)
        val proof=JSONObject().put("selectedThread",s.selectedThread).put("targetKey",s.targetKey)
            .put("canCompose",s.canCompose).put("loading",s.loading).put("historyLoading",s.historyLoading)
            .put("runtimeControl",s.runtime?.canControl).put("text",s.text).put("error",s.error)
            .put("endpoint",s.selectedClient.endpoint).put("routes",s.selectedClient.codexDirect?.let { encodeCodexDirect(it) })
        for(key in listOf("selectionRevision","revisions","draftVersions","freshWorkspaces","freshHistories","foreground")) proof.put(key,field(key).toString())
        val run=args.getString("privateLiveRun")!!
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"acceptance/UM04-live/$run").apply { mkdirs() }.resolve("$name.json").writeText(proof.toString(2))
    }
    @After fun stop() { holder.value?.let { main { contextProof("final-context");it.discovery?.stop();it.leaveForeground() } } }
    @Test fun explicitNativeConnectionUsesIndependentCredentialAndOriginalDesktopOnce() {
        val fixture=read("fixture");val id=fixture.getString("hostId");val thread=fixture.getString("threadId");val marker=fixture.getString("marker")
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"acceptance/UM04-live/"+args.getString("privateLiveRun")!!).apply { mkdirs() }
        try {
            val checked=runBlocking { CodexDiscoveryAPI.discover(fixture.getString("endpoint")) }
            assertEquals(id,checked.target.hostId)
            File(folder,"public-preflight.json").writeText(JSONObject().put("endpoint",fixture.getString("endpoint")).put("target",encodeTarget(checked.target)).put("actualPublicGET",true).toString(2))
        } catch(failure: Exception) {
            File(folder,"public-preflight-error.txt").writeText(failure.stackTraceToString());throw failure
        }
        store=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"private-live-"+UUID.randomUUID())
        holder.value=MobileSession(store)
        ui.setContent { key(holder.value) { AgentHubApp(session) } }
        ui.runOnIdle { session.resetAcceptance();session.discovery=CodexHostDiscovery(InstrumentationRegistry.getInstrumentation().targetContext,session,store);session.discovery!!.start() }
        ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("add-client").performClick()
        var bonjour=true
        try { ui.waitUntil(25_000) { session.discovery!!.hosts.any { it.id==id && it.reachable } } }
        catch(failure: ComposeTimeoutException) {
            require(args.getString("privateLiveAllowHint")=="1") { "The actual LAN Host did not appear through native NSD" }
            bonjour=false;ui.runOnIdle { session.discovery!!.offerHint(fixture.getString("endpoint"),id,fixture.getString("name")) }
        }
        val row="codex-discovered-$id";ui.waitUntil(25_000) { session.discovery!!.hosts.any { it.id==id && it.reachable } && ui.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        val discovered=session.discovery!!.hosts.first { it.id==id }
        File(folder,"discovery.json").writeText(JSONObject().put("endpoint",discovered.endpoint).put("actualBonjour",bonjour).put("reachable",discovered.reachable).toString(2))
        assertFalse(session.hasClient);assertEquals(0,read("stats").getInt("claimPosts"));shot("real-discovery-before-explicit-click")
        ui.onNodeWithTag(row).performClick()
        assertEquals(0,read("stats").getInt("claimPosts"))
        ui.onNodeWithTag("codex-host-key").performScrollTo().performTextInput(fixture.getString("hostKey"))
        ui.onNodeWithTag("codex-authorize-connect").performScrollTo().performClick()
        ui.waitUntil(90_000) { session.hasClient && !session.connectionBusy && session.threads.any { it.id==thread } }
        assertFalse(session.selectedClient.isDemo);assertEquals(id,session.selectedClient.id);assertNotNull(session.selectedClient.codexDirect)
        assertEquals(1,read("stats").getInt("claimPosts"));assertTrue(store.token(id).matches(Regex("[A-Za-z0-9_-]{43}")))
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-clients").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-done").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-clients").fetchSemanticsNodes().isEmpty() }
        ui.runOnIdle { session.selectThread(session.threads.first { it.id==thread }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.canCompose && session.runtime!=null }
        ui.runOnIdle { session.configure(GenerationSettings(model=fixture.getString("model"),effort="xhigh")) }
        val prompt="Reply only $marker. Do not use tools."
        ui.onNodeWithTag("message-input").performTextReplacement(prompt)
        ui.runOnIdle { contextProof("before-send") }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { (!session.running && session.transferStatus==null && session.messages.any { it.role=="assistant" && it.replyPhase=="final_answer" && it.text.contains(marker) }) || session.operationRecords.any { it.phase=="failed" } }
        assertTrue("Actual reply must complete; a failed guard is preserved without re-sending",session.messages.any { it.role=="assistant" && it.replyPhase=="final_answer" && it.text.contains(marker) })
        assertEquals(1,session.messages.count { it.role=="user" && it.text==prompt });shot("private-credential-real-original-owner-reply")
        val tokenHash=MessageDigest.getInstance("SHA-256").digest(store.token(id).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        ui.runOnIdle { session.text="UM04_REOPEN_DRAFT_$marker" }
        ui.waitUntil(10_000) { store.metadata()?.optJSONObject("drafts")?.toString()?.contains("UM04_REOPEN_DRAFT_$marker")==true }
        val c=URL(control+"/um04/restart").openConnection() as HttpURLConnection
        try { c.requestMethod="POST";c.doOutput=true;c.outputStream.use { it.write("{}".toByteArray()) };assertEquals(200,c.responseCode);c.inputStream.close() } finally { c.disconnect() }
        val restarted=read("fixture")
        ui.runOnIdle { session.discovery?.stop();session.leaveForeground();holder.value=MobileSession(store).also { it.restore() } }
        ui.waitUntil(20_000) { !session.restoring && session.hasClient }
        assertEquals("UM04_REOPEN_DRAFT_$marker",session.text)
        ui.runOnIdle { session.discovery=CodexHostDiscovery(InstrumentationRegistry.getInstrumentation().targetContext,session,store);session.discovery!!.start() }
        if(!bonjour) ui.runOnIdle { session.discovery!!.offerHint(restarted.getString("endpoint"),id,fixture.getString("name")) }
        ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("add-client").performClick()
        ui.waitUntil(45_000) { session.discovery!!.hosts.any { it.id==id && it.reachable } }
        ui.onNodeWithTag(row).performClick();ui.waitUntil(60_000) { !session.connectionBusy && session.selectedClient.online && !session.historyLoading }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-clients").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals("UM04_REOPEN_DRAFT_$marker",session.text);assertEquals(1,read("stats").getInt("claimPosts"))
        assertTrue(session.messages.any { it.role=="assistant" && it.text.contains(marker) });shot("host-and-app-restart-retain-private-credential")
        val proof=JSONObject().put("hostId",id).put("threadId",thread).put("marker",marker).put("actualBonjour",bonjour)
            .put("endpoint",session.selectedClient.endpoint).put("target",encodeTarget(session.selectedClient.scope!!)).put("fingerprint",session.selectedClient.codexDirect!!.fingerprint)
            .put("tokenSha256",tokenHash).put("claimPosts",1).put("privateStorageNamespace",true).put("hostAndSessionRecreation",true)
        val run=args.getString("privateLiveRun")!!
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"acceptance/UM04-live/$run/proof.json").writeText(proof.toString(2))
    }
}
