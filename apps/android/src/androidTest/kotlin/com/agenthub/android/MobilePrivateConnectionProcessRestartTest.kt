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
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** Two separate instrumentation processes reopen one existing real binding/input. No send or claim action. */
@RunWith(AndroidJUnit4::class)
class MobilePrivateConnectionProcessRestartTest {
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            val i=InstrumentationRegistry.getInstrumentation()
            for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard","am start -W -n com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity")) {
                val fd=i.uiAutomation.executeShellCommand(command)
                val out=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
                if(command.startsWith("am ")) assertTrue(out.contains("Status: ok"))
            }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val holder=mutableStateOf<MobileSession?>(null)
    private val session get()=holder.value!!
    private fun fixture()=URL(args.getString("privateReopenControl")+"/um04/reopen").openConnection().apply { connectTimeout=5000;readTimeout=10000 }.getInputStream().bufferedReader().use { JSONObject(it.readText()) }
    @After fun stop() { holder.value?.let { s -> runBlocking { withContext(Dispatchers.Main) { s.discovery?.stop();s.leaveForeground() } } } }
    private fun exercise(phase: String) {
        val f=fixture();assertEquals(phase,f.getString("phase"));val id=f.getString("hostId");val thread=f.getString("threadId");val marker=f.getString("marker")
        val requested=f.optional("namespace")
        val names=if(requested!=null) listOf(requested) else context.noBackupFilesDir.listFiles().orEmpty().filter { it.isDirectory&&it.name.startsWith("private-live-") }.map { it.name }.take(256)
        require(names.all { it.matches(Regex("private-live-[a-f0-9-]+")) })
        val matching=names.mapNotNull { name ->
            try { val store=NativeSessionStorage(context,name);if(CodexConnectionVault(store).clients().any { it.id==id }) name to store else null } catch(_: Exception) { null }
        }
        assertEquals("Exactly one prior isolated binding must match; no replacement credential",1,matching.size)
        val (namespace,store)=matching.single()
        ui.setContent { holder.value?.let { AgentHubApp(it) } }
        ui.runOnIdle { holder.value=MobileSession(store).also { it.restore() } }
        ui.waitUntil(90_000) { !session.restoring && session.hasClient && !session.loading && session.selectedClient.id==id && session.threads.any { it.id==thread } }
        if(session.selectedThread!=thread) ui.runOnIdle { session.selectThread(session.threads.first { it.id==thread }) }
        ui.waitUntil(90_000) { !session.historyLoading && session.canCompose && session.runtime?.canControl==true && session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text==marker } }
        assertEquals(1,session.messages.count { it.role=="user"&&it.text=="Reply only $marker. Do not use tools." })
        val tokenSha=MessageDigest.getInstance("SHA-256").digest(store.token(id).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(f.getString("expectedTokenSha256"),tokenSha)
        val draft=f.getString("expectedDraft")
        if(phase=="capture") {
            ui.onNodeWithTag("message-input").performTextReplacement(draft)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.waitUntil(10_000) { store.metadata()?.optJSONObject("drafts")?.toString()?.contains(draft)==true }
        } else { assertEquals(draft,session.text);ui.onNodeWithTag("message-input").assertTextEquals(draft) }
        ui.onNodeWithTag("client-selector").performClick();ui.onNodeWithTag("add-client").performClick()
        ui.onNodeWithTag("codex-discovered-$id-connected",useUnmergedTree=true).assertExists()
        val run=args.getString("privateReopenRun")!!;require(run.matches(Regex("[a-f0-9-]+")))
        val dir=File(context.getExternalFilesDir(null),"acceptance/UM04-reopen/$run").apply { mkdirs() }
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(200,5000)
        assertEquals(context.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=i.uiAutomation.takeScreenshot();try { File(dir,"$phase-connected.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
        val proof=JSONObject().put("phase",phase).put("pid",android.os.Process.myPid()).put("namespace",namespace)
            .put("tokenSha256",tokenSha).put("originalUserInputs",1).put("newClaims",fixture().getInt("newClaims"))
            .put("draft",session.text).put("target",encodeTarget(session.selectedClient.scope!!)).put("endpoint",session.selectedClient.endpoint)
        File(dir,"$phase.json").writeText(proof.toString(2))
    }
    @Test fun captureExistingBindingAndDraft()=exercise("capture")
    @Test fun reopenOriginalBindingAfterRealProcessRestart()=exercise("verify")
}
