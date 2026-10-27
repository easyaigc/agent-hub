package com.agenthub.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Real source APK -> media album -> system picker -> actual Activity and encrypted original draft. Host is synthetic GET-only. */
class MobilePhotoPositiveTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun knownIndependentPhotoReturnsThroughActualSystemPickerToOriginalDraft() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity;val owned=OwnedControlAcceptance(activity,"photo-positive");val session=owned.session
        val target=JSONObject().put("agentId","codex").put("hostId","photo-positive-host").put("workspaceId","photo-positive-workspace").put("runtimeId","photo-positive-runtime")
        val server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","photo-positive-host").put("name","Owned photo positive target").put("connectionMode","desktop-runtime").put("readTarget",target)
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","original-photo-thread").put("hostId","photo-positive-host").put("title","Original photo draft").put("state","idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","photo-positive-host").put("threadId","original-photo-thread").put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
        var prior: MainActivity.AcceptanceSessionState?=null;var photo: OwnedPhotoSelection?=null
        try {
            main { session.resetAcceptance();session.changeLanguage("en");session.changeAppearance("light");prior=activity.adoptSessionForAcceptance(session);session.connect("Owned photo positive target",server.endpoint,"") }
            ui.waitUntil(20_000) { session.threads.isNotEmpty()&&!session.loading }
            main { session.selectThread(session.threads.single());session.text="系统选图原草稿 100% %@" };ui.waitUntil(15_000) { session.canCompose&&!session.historyLoading }
            photo=OwnedPhotoSelection(activity,ui,owned);photo.prepare();val selected=photo.choose();assertEquals(1,session.attachments.size)
            photo.cleanup();photo=null
            assertEquals(selected,session.attachments.single());assertEquals("系统选图原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
        }finally {
            try { photo?.cleanup() }finally { main { session.leaveForeground();prior?.let(activity::restoreSessionForAcceptance) };owned.verifyDefault();assertTrue(server.requests.all { it.method=="GET" });server.close() }
        }
    }
}
