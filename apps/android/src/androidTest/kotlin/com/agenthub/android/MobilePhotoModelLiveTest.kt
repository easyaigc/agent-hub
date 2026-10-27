package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Opt-in one new owned projectless image task. A separate Host witness leases one immutable input, no replay. */
class MobilePhotoModelLiveTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun actualSystemPhotoIsReadOnceByOriginalDesktopModel() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val name=checkNotNull(InstrumentationRegistry.getArguments().getString("photoLiveFixture"));require(name.matches(Regex("photo-live-[a-f0-9]{8}\\.json")))
        val fixture=JSONObject(File(i.targetContext.filesDir,"acceptance/$name").readText())
        fun control(route: String,value: JSONObject): JSONObject {
            require(route in listOf("claim-input","case-complete"))
            val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
            val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
            try {
                http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=30_000
                http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
                val bytes=value.put("nonce",fixture.getString("nonce")).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) };assertEquals("One immutable original image lease",200,http.responseCode)
                return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
            }finally { http.disconnect() }
        }
        fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity;val owned=OwnedControlAcceptance(activity,"photo-model-live");val session=owned.session
        var prior: MainActivity.AcceptanceSessionState?=null;var photo: OwnedPhotoSelection?=null
        try {
            main { session.resetAcceptance();session.changeLanguage("en");session.changeAppearance("light");prior=activity.adoptSessionForAcceptance(session);session.connect("Owned photo model DEV",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { !session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.canCreateProjectless }
            main { session.newChat() };ui.waitUntil(30_000) { session.canConfigureGeneration&&session.models.isNotEmpty()&&session.canCompose }
            ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only");ui.onNodeWithTag("sheet-done").performClick()
            val text=fixture.getString("text");ui.onNodeWithTag("message-input").performTextReplacement(text)
            val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick();ui.waitUntil(10_000) { session.aiSharingRequest!=null }
            assertTrue(session.operationRecords.isEmpty());assertNull(session.transferStatus)
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            assertEquals(text,session.text);assertTrue(session.operationRecords.isEmpty());assertTrue(session.aiSharing.permits(scope))
            if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
            ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
            photo=OwnedPhotoSelection(activity,ui,owned);photo.prepare();val selected=photo.choose();val marker=photo.report.getString("marker")
            assertFalse(text.contains(marker,true));assertFalse(selected.manifest.name.contains(marker,true));assertEquals(1,session.attachments.size)
            val destination=session.attachmentDestination();val claim=JSONObject().put("text",text).put("sourceKey",session.targetKey).put("file",selected.manifest.body())
                .put("expectedMarker",marker).put("sourcePhoto",photo.report).put("settings",session.generationSettings.append(JSONObject()))
            File(owned.folder,"immutable-preflight.json").writeText(claim.toString());control("claim-input",claim)
            assertTrue(session.isCurrent(destination));assertEquals(selected,session.attachments.single());assertTrue(session.operationRecords.isEmpty())
            // Exactly one send after the witness sealed original bytes/settings. Never retry or query by a new operation ID.
            ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            try {
                ui.waitUntil(240_000) { session.selectedThread.isNotBlank()&&session.transferStatus==null&&session.operationRecords.any { it.kind=="creation"&&it.submissionAttempted }&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker } }
            }catch(failure: Throwable) {
                File(owned.folder,"unresolved-original.json").writeText(JSONObject().put("threadId",session.selectedThread).put("error",session.error).put("transfer",session.transferStatus)
                    .put("records",org.json.JSONArray(session.operationRecords.map { it.body() })).toString());throw failure
            }
            val record=session.operationRecords.single { it.kind=="creation" };assertTrue(record.submissionAttempted);assertEquals(text,JSONObject(record.intent).getString("text"))
            assertEquals(selected.manifest.body().toString(),JSONObject(record.intent).getJSONArray("attachments").getJSONObject(0).toString())
            assertEquals(1,session.messages.count { it.role=="user"&&it.text==text });assertEquals(1,session.messages.count { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker })
            assertTrue(session.attachments.isEmpty());assertEquals("",session.text)
            owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-model-image-answer")
            val proof=JSONObject().put("threadId",session.selectedThread).put("record",record.body()).put("systemPositivePhoto",true).put("pixelMarkerOnly",true).put("singleNativeSend",true)
                .put("expectedMarker",marker).put("localFile",selected.manifest.body()).put("selectedModel",session.generationSettings.model).put("effort",session.generationSettings.effort).put("sourceUid",photo.report.getInt("sourceUid"))
            File(owned.folder,"actual-model-photo-proof.json").writeText(proof.toString());control("case-complete",proof)
        }finally {
            try { photo?.cleanup() }finally { main { session.leaveForeground();prior?.let(activity::restoreSessionForAcceptance) };owned.verifyDefault() }
        }
    }
}
