package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** GET-only completed image reply after the same owned Host restarts. Never invokes commands or sends. */
class MobilePhotoReplyReadLiveTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun sameOriginalImageReplySettlesAfterReadOnlyHostRestart() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val name=checkNotNull(InstrumentationRegistry.getArguments().getString("photoReadFixture"));require(name.matches(Regex("photo-read-[a-f0-9]{8}\\.json")))
        val f=JSONObject(File(i.targetContext.filesDir,"acceptance/$name").readText());ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val activity=ui.activity;val owned=OwnedControlAcceptance(activity,"photo-reply-read");val session=owned.session;var prior: MainActivity.AcceptanceSessionState?=null
        fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
        try {
            main { session.resetAcceptance();session.changeLanguage("en");session.changeAppearance("light");prior=activity.adoptSessionForAcceptance(session);session.connect("Owned original photo read",f.getString("endpoint"),f.getString("token")) }
            ui.waitUntil(45_000) { !session.loading&&session.workspace?.hostId==f.getString("hostId")&&session.threads.any { it.id==f.getString("threadId") } }
            main { session.selectThread(session.threads.single { it.id==f.getString("threadId") }) }
            ui.waitUntil(45_000) { !session.historyLoading&&session.runtimeState=="idle"&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==f.getString("expectedMarker") } }
            assertEquals(f.getString("threadId"),session.selectedThread);assertTrue(session.canCompose);assertTrue(session.operationRecords.isEmpty());assertNull(session.transferStatus);assertNull(session.error)
            if(f.has("expectedModel")) {
                val expectedEffort=f.optional("expectedEffort")
                ui.waitUntil(20_000) { session.generationDisplay.model==f.getString("expectedModel")&&session.displayedEffort==expectedEffort }
                val label=ui.onNodeWithTag("generation-settings").assertIsDisplayed().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" ")
                val modelName=session.models.firstOrNull { it.id==f.getString("expectedModel") }?.name ?: f.getString("expectedModel")
                assertTrue(label.contains(modelName))
                File(owned.folder,"original-owner-generation-proof.json").writeText(JSONObject().put("model",session.generationDisplay.model)
                    .put("effort",session.displayedEffort ?: JSONObject.NULL).put("matchesIndependentOwnerRead",true).put("actualControlLabel",label).toString())
            }
            val userCount=session.messages.count { it.role=="user"&&it.text==f.getString("text") };val finalCount=session.messages.count { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==f.getString("expectedMarker") }
            File(owned.folder,"same-original-photo-read-diagnostic.json").writeText(JSONObject().put("userCount",userCount).put("finalCount",finalCount).put("runtime",session.runtimeState)
                .put("messages",org.json.JSONArray(session.messages.map { m->JSONObject().put("id",m.id).put("role",m.role).put("kind",m.kind).put("replyPhase",m.replyPhase).put("textSha256",java.security.MessageDigest.getInstance("SHA-256").digest(m.text.toByteArray()).joinToString("") { "%02x".format(it) }) })).toString())
            assertEquals("One original user input",1,userCount);assertEquals("One original final reply",1,finalCount)
            assertNotEquals("stop",session.decision.action);assertEquals("",session.text);assertTrue(session.attachments.isEmpty())
            owned.nodeShot(ui.onNodeWithTag("product-surface"),"same-original-photo-reply-idle")
            File(owned.folder,"same-original-photo-read-proof.json").writeText(JSONObject().put("originalThreadMatched",true).put("runtimeIdle",true).put("completedPixelMarkerReplyMatched",true)
                .put("nativeControlNotStop",true).put("draftEmptyPreserved",true).put("operationRecordsEmpty",true).put("newInputs",0).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid()).toString())
        }finally { main { session.leaveForeground();prior?.let(activity::restoreSessionForAcceptance) };owned.verifyDefault() }
    }
}
