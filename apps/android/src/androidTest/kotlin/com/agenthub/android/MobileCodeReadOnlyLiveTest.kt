package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Opt-in GET-only original completed model reply. This class never creates or sends a task. */
class MobileCodeReadOnlyLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun sha(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    @Test fun originalCompletedModelCodeCopyWithoutSubmittingAnything() {
        val name=InstrumentationRegistry.getArguments().getString("codeReadFixture")
        Assume.assumeTrue("Dedicated original-code read fixture required",name!=null)
        require(name!!.matches(Regex("code-read-[a-f0-9]{8}\\.json")))
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity
        val fixture=JSONObject(File(activity.filesDir,"acceptance/"+name).readText())
        lateinit var owned: OwnedControlAcceptance
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"actual-code-read") }
        val session=owned.session
        lateinit var clipboard: ClipboardManager;var previous: ClipData?=null;var touched=false
        try {
            main {
                session.resetAcceptance(NativeHostReads { fixture.getString("token") },NativeCodexCommands { fixture.getString("token") })
                session.changeLanguage("en");session.changeAppearance("light")
                clipboard=activity.getSystemService(ClipboardManager::class.java);previous=clipboard.primaryClip
                session.connect("Owned original code read",fixture.getString("endpoint"),fixture.getString("token"))
            }
            val thread=fixture.getString("threadId");val id=fixture.getString("messageId")
            ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==thread } }
            main { session.selectThread(session.threads.single { it.id==thread }) }
            ui.waitUntil(30_000) { !session.historyLoading&&session.messages.any { it.id==id } }
            assertEquals(fixture.getString("hostId"),session.selectedClient.id)
            assertTrue(sameScope(HostReadCodec.target(fixture.getJSONObject("target")),session.selectedClient.scope))
            val original=session.messages.single { it.id==id };assertEquals("assistant",original.role)
            assertEquals(fixture.getString("replySha256"),sha(original.text))
            main { session.text="Unsent independent code read "+fixture.getString("nonce") }
            val draft=session.text;val files=session.attachments
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-"+id))
            val tag="markdown-$id-0-copy-code"
            ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            val button=ui.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
            var priorBounds: String?=null;var stableSince=0L
            ui.waitUntil(8_000) {
                val bounds=button.fetchSemanticsNode().boundsInRoot.toString();val now=android.os.SystemClock.uptimeMillis()
                if(bounds!=priorBounds) { priorBounds=bounds;stableSince=now };now-stableSince>=700
            }
            val bounds=button.fetchSemanticsNode().boundsInRoot
            val latest=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            assertFalse("Original model code copy touch must stay outside floating latest",latest?.contains(bounds.center)==true)
            val eligible=previous!=null||android.os.Build.VERSION.SDK_INT>=28
            if(eligible) {
                button.performTouchInput { click(center) };touched=true
                ui.waitUntil(5_000) { var value: String?=null;ui.runOnUiThread { value=clipboard.primaryClip?.getItemAt(0)?.text?.toString() };value==fixture.getString("body") }
                main { assertEquals(fixture.getString("bodySha256"),sha(clipboard.primaryClip!!.getItemAt(0).text.toString())) }
            }
            owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-original-code-copy")
            assertEquals(draft,session.text);assertEquals(files,session.attachments)
            assertEquals(original,session.messages.single { it.id==id });assertTrue(session.operationRecords.isEmpty())
            File(owned.folder,"actual-original-code-read-proof.json").writeText(JSONObject().put("originalMessageAndTargetMatched",true)
                .put("originalReplySha256",sha(original.text)).put("sdkCopyAttempted",eligible).put("systemClipboardMatched",eligible)
                .put("api26WithoutPriorClipNotAttempted",!eligible).put("floatingLatestOverlapsCopy",false)
                .put("originalDraftAndFilesPreserved",true).put("newModelInputs",0).put("newNativeOperations",0).toString())
        } finally {
            main { if(touched) { if(previous!=null)clipboard.setPrimaryClip(previous!!) else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() } }
            ui.runOnIdle { session.leaveForeground();owned.verifyDefault() }
        }
    }
}
