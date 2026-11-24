package com.agenthub.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Four distinct targets; real session reads against loopback, no model writes. */
class Life01T03MultiClientTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private suspend fun waitFor(predicate: ()->Boolean)=withTimeout(15_000) { while(!withContext(Dispatchers.Main) { predicate() })delay(20) }
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private class Host(val id: String): java.io.Closeable {
        val scope=TargetScope("codex",null,null,id,"workspace-$id","runtime-$id")
        @Volatile var unauthorized=false
        @Volatile var hold=false
        val workspaceStart=CountDownLatch(1);val historyStart=CountDownLatch(1);val release=CountDownLatch(1)
        fun workspace()=JSONObject().put("hostId",id).put("name",id).put("readTarget",CodexCommandCodec.target(scope))
            .put("connectionMode","desktop-runtime").put("runtimeConnected",true)
            .put("capabilities",JSONArray(listOf("commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
            .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","thread-$id").put("hostId",id).put("title",id).put("state","running")))
        fun history()=JSONObject().put("hostId",id).put("threadId","thread-$id").put("readTarget",CodexCommandCodec.target(scope)).put("messages",JSONArray())
            .put("runtime",JSONObject().put("state","running").put("turnId","turn-$id").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
        val server=CommandFixture { request -> when(request.path) {
            "/host" -> WireResponse(body=workspace().toString())
            "/v1/workspace","/v1/history" -> if(unauthorized) WireResponse(401,"{\"error\":\"unauthorized\"}") else {
                if(hold) { (if(request.path=="/v1/workspace")workspaceStart else historyStart).countDown();release.await(20,TimeUnit.SECONDS) }
                WireResponse(body=(if(request.path=="/v1/workspace")workspace() else history()).toString())
            }
            "/v1/models" -> WireResponse(body=JSONObject().put("hostId",id).put("models",JSONArray()).toString())
            else -> WireResponse(404)
        } }
        override fun close(){release.countDown();server.close()}
    }
    @Test fun backgroundRefreshesSelectedOnlyAndPreservesOtherStates()=runBlocking {
        Host("matrix-current").use { current -> Host("matrix-healthy").use { healthy -> Host("matrix-failed").use { failed ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val store=NativeSessionStorage(context,"life01-t03-"+UUID.randomUUID())
            val scope=TargetScope("easywork","https://matrix.invalid","synthetic-subject","matrix-migrated","matrix-workspace","matrix-runtime")
            val marker=MigratedEasyWorkConnection.decode(JSONObject().put("version",1).put("provider","easywork").put("archiveId","9".repeat(64))
                .put("target",CodexCommandCodec.target(scope)).put("requiresPairing",true).put("credentialState","none").put("preference","lan-only").put("candidateOrigins",JSONArray()))
            val migrated=MobileClient("matrix-migrated","migration",scope,"http://127.0.0.1:1",false,"easywork-runtime",false,requiresReadTarget=true,migrationEasyWork=marker)
            val metadata=JSONObject().put("version",1).put("appearance","system").put("language","system").put("demo",false).put("selected",migrated.id)
                .put("clients",JSONArray().put(encodeClient(migrated))).put("drafts",JSONObject()).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
            store.write("metadata",metadata.toString())
            val session=withContext(Dispatchers.Main) { MobileSession(store,NativeHostReads { "" }).also { it.restore() } }
            waitFor { !session.restoring && session.clients.any { it.id==migrated.id } }
            val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
            try {
                scenario.onActivity { it.adoptSessionForAcceptance(session) }
                suspend fun connect(host: Host) { withContext(Dispatchers.Main) { session.connect(host.id,host.server.endpoint,"");session.selectThread(session.threads.first { it.id=="thread-${host.id}" }) };waitFor { session.canStop && !session.loading && !session.historyLoading } }
                connect(healthy);connect(failed)
                failed.unauthorized=true
                withContext(Dispatchers.Main) { session.refresh(userInitiated=true) }
                waitFor { session.clients.single { it.id==failed.id }.connectionPhase==ConnectionPhase.FAILED }
                connect(current)
                val before=withContext(Dispatchers.Main) { session.clients.associateBy { it.id } }
                assertTrue(before.getValue(current.id).online);assertTrue(before.getValue(healthy.id).online)
                assertFalse(before.getValue(failed.id).online);assertEquals(ConnectionPhase.FAILED,before.getValue(failed.id).connectionPhase)
                assertEquals(marker,before.getValue(migrated.id).migrationEasyWork)
                val healthyCount=healthy.server.requests.size;val failedCount=failed.server.requests.size
                withContext(Dispatchers.Main) { session.text="retained t03 draft" }
                shell("input keyevent KEYCODE_HOME")
                waitFor { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).filterIsInstance<MainActivity>().any { it.session===session } }
                current.hold=true
                assertTrue(shell("am start -n ${context.packageName}/com.agenthub.android.MainActivity").contains("Starting:"))
                assertTrue(withContext(Dispatchers.IO) { current.workspaceStart.await(8,TimeUnit.SECONDS) })
                assertTrue(withContext(Dispatchers.IO) { current.historyStart.await(8,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    assertTrue(session.selectedClient.online);assertFalse(session.canStop);assertFalse(session.canCreateProjectless)
                    session.stopTask();session.performAction();assertFalse(session.newChat());assertEquals("retained t03 draft",session.text)
                    val after=session.clients.associateBy { it.id }
                    assertTrue(after.getValue(healthy.id).online);assertEquals(before.getValue(healthy.id).connectionPhase,after.getValue(healthy.id).connectionPhase)
                    assertFalse(after.getValue(failed.id).online);assertEquals(ConnectionPhase.FAILED,after.getValue(failed.id).connectionPhase)
                    assertEquals(marker,after.getValue(migrated.id).migrationEasyWork);assertFalse(after.getValue(migrated.id).online)
                }
                assertEquals(healthyCount,healthy.server.requests.size);assertEquals(failedCount,failed.server.requests.size)
                assertTrue(listOf(current,healthy,failed).all { host->host.server.requests.none { it.method=="POST" } })
                current.release.countDown();waitFor { session.canStop && session.canCreateProjectless }
            } finally {current.release.countDown();scenario.close()}
        } } }
    }
}
