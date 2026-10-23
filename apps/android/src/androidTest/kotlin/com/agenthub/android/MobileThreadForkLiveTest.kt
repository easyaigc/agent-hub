package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Opt-in real original Electron + native forks. Missing private configuration fails. */
@RunWith(AndroidJUnit4::class)
class MobileThreadForkLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(900)
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val fixture by lazy { JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"thread-fork-live.json").readText()) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String) {
        require(route in listOf("restart-and-release","observe"))
        val uri=URI(fixture.getString("control"));require(uri.scheme=="http" && uri.host=="127.0.0.1")
        val http=uri.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try { http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=90_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.doOutput=true
            http.outputStream.use { it.write("{}".toByteArray()) };assertEquals(200,http.responseCode);http.inputStream.close()
        } finally { http.disconnect() }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/UM03-live").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot();try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun source() {
        ui.runOnIdle { session.selectThread(session.threads.first { it.id==fixture.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.messages.any { it.runId==fixture.getString("turnId") && it.replyPhase=="final_answer" } }
    }
    private fun allowOwnedTestSharing(): Boolean {
        val request=session.aiSharingRequest ?: return false
        assertEquals(fixture.getString("hostId"),session.selectedClient.id)
        assertEquals(fixture.getString("hostId"),request.scope.target.hostId)
        val draft=session.text;val prior=session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) }
        shot("native-sharing-before-explicit-action")
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest==null && !session.aiSharing.busy }
        assertEquals(draft,session.text)
        assertEquals(prior,session.operationRecords.map { Triple(it.target,it.id,it.intentSha256) })
        return true
    }
    private fun menu(environment: String,afterPermission: Boolean=false) {
        val reply=session.messages.first { it.role=="assistant" && it.runId==fixture.getString("turnId") && it.replyPhase=="final_answer" }
        // Smaller devices keep the earlier reply outside the composed viewport.
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("reply-actions-${reply.id}"))
        ui.onNodeWithTag("reply-actions-${reply.id}").onChildren().filter(hasTestTag("fork-reply")).onFirst().performClick()
        ui.waitUntil(20_000) { session.forkReason(fixture.getString("turnId"),environment)==null }
        ui.onNodeWithTag("fork-$environment").assertIsEnabled().performClick()
        if(allowOwnedTestSharing()) { check(!afterPermission);menu(environment,true) }
    }
    @Test fun exactOriginalOwnerForksAndRestartNeverReplays() {
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("UM03 original desktop",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.threads.any { it.id==fixture.getString("threadId") } }
        assertEquals(fixture.getString("hostId"),session.selectedClient.id)
        for(environment in listOf("same-directory","worktree")) {
            source();val draft="source-draft-$environment";ui.runOnIdle { session.text=draft };menu(environment)
            if(environment=="same-directory") {
                ui.waitUntil(45_000) { !session.forkBusy && session.forkRecords.any { it.phase=="uncertain" || it.phase=="outcome-unknown" } }
                val original=session.forkRecords.last();ui.onNodeWithTag("detach-fork-${original.id}").performClick()
                ui.waitUntil(10_000) { session.forkRecords.any { it.id==original.id && it.detached } }
                ui.runOnIdle { session.text="new draft after uncertain fork" };shot("unknown-detached-before-host-restart")
                control("restart-and-release")
                ui.onNodeWithTag("query-fork-${original.id}").performClick()
                ui.waitUntil(45_000) { !session.forkBusy && session.forkRecords.any { it.id==original.id && it.phase=="confirmed" } }
                assertEquals(fixture.getString("threadId"),session.selectedThread);assertEquals("new draft after uncertain fork",session.text)
                shot("restart-confirmed-with-new-draft");ui.onNodeWithTag("query-fork-${original.id}").performClick()
            }
            ui.waitUntil(60_000) { session.selectedThread!=fixture.getString("threadId") && !session.forkBusy && !session.historyLoading && session.runtime!=null }
            assertFalse(session.messages.any { it.text.contains(fixture.getString("laterMarker")) })
            assertEquals(1,session.messages.filter { it.role=="user" }.size)
            val marker=fixture.getString(if(environment=="same-directory") "sameMarker" else "worktreeMarker")
            val text="What is the FIRST marker you remember? Reply with that marker and $marker only. Do not use tools."
            ui.runOnIdle { session.configure(GenerationSettings(effort="xhigh")) }
            ui.waitUntil(30_000) { session.canCompose && session.transferStatus==null }
            ui.onNodeWithTag("message-input").performTextReplacement(text);ui.onNodeWithTag("send-message").performClick()
            if(allowOwnedTestSharing()) ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            ui.waitUntil(180_000) { !session.running && session.transferStatus==null && session.messages.any { it.role=="assistant" && it.replyPhase=="final_answer" && it.text.contains(marker) && it.text.contains(fixture.getString("firstMarker")) } }
            shot("real-$environment-original-owner-response")
            source();assertEquals(if(environment=="same-directory") "new draft after uncertain fork" else draft,session.text)
        }
        control("observe")
    }
}
