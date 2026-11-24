package com.agenthub.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
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
import java.util.concurrent.atomic.AtomicInteger

class Life01ForegroundOwnerTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private suspend fun waitFor(timeout:Long=10_000,predicate:()->Boolean)=withTimeout(timeout){while(!withContext(Dispatchers.Main){predicate()})delay(20)}
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
    private class Control { @Volatile var held=false;val ws=AtomicInteger();val hs=AtomicInteger();val started=CountDownLatch(2);val restarted=CountDownLatch(2);val old=CountDownLatch(1);val fresh=CountDownLatch(1) }
    @Test fun backgroundCancelsReadWaitersAndOldResponsesCannotClearNewOwners()=runBlocking {
        ChatManagementFixture().use {base->
            val control=Control();val target=base.target
            fun history()=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","running").put("turnId","owner-turn").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
            CommandFixture {r->when(r.path){
                "/host","/v1/workspace","/v1/history"->{
                    if(control.held&&r.path!="/host") {
                        val n=(if(r.path=="/v1/workspace")control.ws else control.hs).incrementAndGet()
                        if(n==1){control.started.countDown();control.old.await(30,TimeUnit.SECONDS)}else {control.restarted.countDown();control.fresh.await(30,TimeUnit.SECONDS)}
                    }
                    val body=if(r.path=="/v1/history")history() else base.workspace().put("capabilities",JSONArray(listOf("commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
                    WireResponse(body=body.toString())
                }
                "/v1/models"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
                else->WireResponse(404)
            }}.use {server->
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val session=withContext(Dispatchers.Main){MobileSession(NativeSessionStorage(context,"owner-"+UUID.randomUUID()),NativeHostReads{""}).also{it.restore()}}
                waitFor{!session.restoring};val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
                try {
                    scenario.onActivity{it.adoptSessionForAcceptance(session)}
                    withContext(Dispatchers.Main){session.connect("owner fixture",server.endpoint,"");session.selectThread(session.threads.first{it.id==ChatManagementFixture.first})}
                    waitFor{session.canStop&&session.canCreateProjectless&&!session.loading&&!session.historyLoading}
                    withContext(Dispatchers.Main){session.text="owner draft";control.held=true;session.refresh();session.loadHistory()}
                    assertTrue(withContext(Dispatchers.IO){control.started.await(5,TimeUnit.SECONDS)})
                    shell("input keyevent KEYCODE_HOME")
                    waitFor{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).filterIsInstance<MainActivity>().any{it.session===session}}
                    waitFor(3_000){!session.loading&&!session.historyLoading}
                    assertTrue(shell("am start -n ${context.packageName}/com.agenthub.android.MainActivity").contains("Starting:"))
                    assertTrue("new reads start while old responses held",withContext(Dispatchers.IO){control.restarted.await(5,TimeUnit.SECONDS)})
                    control.old.countDown();delay(500)
                    withContext(Dispatchers.Main){assertTrue(session.loading);assertTrue(session.historyLoading);assertFalse(session.canStop);assertFalse(session.canCreateProjectless);assertEquals("owner draft",session.text)}
                    control.fresh.countDown();waitFor{session.canStop&&session.canCreateProjectless&&!session.loading&&!session.historyLoading}
                    assertTrue(server.requests.none{it.method=="POST"})
                } finally {control.old.countDown();control.fresh.countDown();scenario.close()}
            }
        }
    }
}
