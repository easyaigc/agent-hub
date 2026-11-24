package com.agenthub.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated local HTTP fixture; no real Host, model, credentials or production profile. */
class Life01TerminalReadTest {
    @get:Rule val foreground=org.junit.rules.TestRule { statement,description ->
        object:org.junit.runners.model.Statement() {
            override fun evaluate() {
                requireIsolatedProfile()
                NativeTestForeground().apply(statement,description).evaluate()
            }
        }
    }
    @get:Rule val ui=createEmptyComposeRule()
    private suspend fun waitFor(ms:Long=15_000,p:()->Boolean)=withTimeout(ms){while(!withContext(Dispatchers.Main){p()})delay(20)}
    private fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use{it.readText()}
    private fun requireIsolatedProfile() { org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("life01SyntheticTerminal")=="1");check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance");val metadata=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).read("metadata");check(metadata==null || JSONObject(metadata).optJSONArray("clients")?.length()==0) {"Use a fresh dedicated profile"} }
    private class Switch { @Volatile var historyDenied=false; @Volatile var denied=false; @Volatile var hold=false; val began=CountDownLatch(1);val release=CountDownLatch(1) }
    @Test fun terminal401StopsReadersThroughHomeAndExplicitRetryRevalidates()=runBlocking {
        requireIsolatedProfile()
        ChatManagementFixture().use { base ->
            val target=base.target;val flag=Switch()
            val workspace=base.workspace().put("capabilities",JSONArray(listOf("events","commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
            val history=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","running").put("turnId","terminal-turn").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
            CommandFixture {r -> when(r.path) {
                "/host"->WireResponse(body=workspace.toString())
                "/v1/workspace","/v1/history","/v1/events" -> if(flag.denied)WireResponse(401,"{\"error\":\"unauthorized\"}") else {
                    if(r.path=="/v1/workspace"&&flag.hold){flag.began.countDown();check(flag.release.await(25,TimeUnit.SECONDS))}
                    WireResponse(body=when(r.path){"/v1/workspace"->workspace.toString();"/v1/history"->history.toString();else->JSONObject().put("hostId",target.hostId).put("epoch","terminal").put("cursor",0).put("reset",false).put("ready",true).put("events",JSONArray()).toString()})
                }
                "/v1/models"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
                else->WireResponse(404)
            }}.use {server ->
                val c=InstrumentationRegistry.getInstrumentation().targetContext
                val s=withContext(Dispatchers.Main){MobileSession(NativeSessionStorage(c,"terminal-read-"+UUID.randomUUID()),NativeHostReads {""}).also{it.restore()}}
                waitFor{!s.restoring};val scenario=ActivityScenario.launch<MainActivity>(Intent(c,MainActivity::class.java))
                fun count(path:String)=server.requests.count{it.path==path}
                try {
                    scenario.onActivity{it.adoptSessionForAcceptance(s)}
                    withContext(Dispatchers.Main){s.connect("terminal fixture",server.endpoint,"");s.selectThread(s.threads.first{it.id==ChatManagementFixture.first})}
                    waitFor{!s.loading&&!s.historyLoading&&s.canStop&&count("/v1/events")>0&&count("/v1/history")>0}
                    flag.denied=true;withContext(Dispatchers.Main){s.refresh(userInitiated=true)}
                    waitFor{s.selectedClient.connectionPhase==ConnectionPhase.FAILED};delay(1_000)
                    val events=count("/v1/events");val histories=count("/v1/history");assertFalse(s.canStop);delay(5_000);assertEquals(events,count("/v1/events"));assertEquals(histories,count("/v1/history"))
                    shell("input keyevent KEYCODE_HOME");waitFor{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).filterIsInstance<MainActivity>().any{it.session===s}}
                    shell("am start -W -n ${c.packageName}/com.agenthub.android.MainActivity");delay(5_000);assertEquals(events,count("/v1/events"));assertEquals(histories,count("/v1/history"));assertEquals(ConnectionPhase.FAILED,s.selectedClient.connectionPhase);assertFalse(s.canStop)
                    flag.denied=false;flag.hold=true;withContext(Dispatchers.Main){s.refresh(userInitiated=true)};assertTrue(withContext(Dispatchers.IO){flag.began.await(5,TimeUnit.SECONDS)});assertFalse(s.canStop)
                    flag.hold=false;flag.release.countDown();try {waitFor(20_000){count("/v1/events")>events&&count("/v1/history")>histories&&s.canStop&&s.selectedClient.connectionPhase!=ConnectionPhase.FAILED}}
                    finally {android.util.Log.i("Life01Terminal", "beforeEvents=$events beforeHistories=$histories afterEvents=${count("/v1/events")} afterHistories=${count("/v1/history")} workspaces=${count("/v1/workspace")} phase=${s.selectedClient.connectionPhase} online=${s.selectedClient.online} canStop=${s.canStop} loading=${s.loading} historyLoading=${s.historyLoading}")}
                    assertTrue(server.requests.none{it.method=="POST"})
                } finally {flag.release.countDown();scenario.close()}
            }
        }
    }
    @Test fun visibleHistoryRetryAfter401ResumesReaders()=runBlocking {
        requireIsolatedProfile()
        ChatManagementFixture().use {base ->
            val target=base.target;val flag=Switch()
            val workspace=base.workspace().put("capabilities",JSONArray(listOf("events","commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
            val history=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray()).put("runtime",JSONObject().put("state","running").put("turnId","terminal-turn").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
            CommandFixture {r -> when(r.path) {
                "/host","/v1/workspace"->WireResponse(body=workspace.toString())
                "/v1/history"->if(flag.historyDenied)WireResponse(401,"{\"error\":\"unauthorized\"}")else WireResponse(body=history.toString())
                "/v1/events"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("epoch","terminal").put("cursor",0).put("reset",false).put("ready",true).put("events",JSONArray()).toString())
                "/v1/models"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
                else->WireResponse(404)
            }}.use {server ->
                val c=InstrumentationRegistry.getInstrumentation().targetContext;val s=withContext(Dispatchers.Main){MobileSession(NativeSessionStorage(c,"terminal-ui-"+UUID.randomUUID()),NativeHostReads {""}).also{it.restore()}}
                waitFor{!s.restoring};val scenario=ActivityScenario.launch<MainActivity>(Intent(c,MainActivity::class.java));fun count(path:String)=server.requests.count{it.path==path}
                try {
                    scenario.onActivity{it.adoptSessionForAcceptance(s)};withContext(Dispatchers.Main){s.connect("terminal UI fixture",server.endpoint,"");s.selectThread(s.threads.first{it.id==ChatManagementFixture.first})}
                    waitFor{!s.loading&&!s.historyLoading&&s.canStop&&count("/v1/events")>0};flag.historyDenied=true;withContext(Dispatchers.Main){s.loadHistory(userInitiated=true)};waitFor{s.selectedClient.connectionPhase==ConnectionPhase.FAILED&&!s.historyLoading};delay(500)
                    ui.waitUntil(10_000){ui.onAllNodesWithTag("conversation-sync-retry").fetchSemanticsNodes().isNotEmpty()};val beforeHistory=count("/v1/history");val beforeEvents=count("/v1/events");flag.historyDenied=false
                    ui.onNodeWithTag("conversation-sync-retry").assertIsEnabled().performClick()
                    try {waitFor(20_000){count("/v1/history")>beforeHistory&&count("/v1/events")>beforeEvents&&s.canStop&&s.selectedClient.connectionPhase!=ConnectionPhase.FAILED}}
                    finally {android.util.Log.i("Life01TerminalUI","beforeHistory=$beforeHistory afterHistory=${count("/v1/history")} beforeEvents=$beforeEvents afterEvents=${count("/v1/events")} phase=${s.selectedClient.connectionPhase} online=${s.selectedClient.online} canStop=${s.canStop}")}
                    assertTrue(server.requests.none{it.method=="POST"})
                } finally {scenario.close()}
            }
        }
    }

}
