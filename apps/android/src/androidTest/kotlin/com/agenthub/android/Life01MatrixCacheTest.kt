package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Authentication and target failures after valid cached state. */
class Life01MatrixCacheTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private class Mode { @Volatile var rejected=false }
    private suspend fun waitFor(predicate: ()->Boolean)=withTimeout(12_000) { while(!withContext(Dispatchers.Main) { predicate() }) delay(20) }
    @Test fun cachedProofCannotSurviveUnauthorizedOrWrongTarget()=runBlocking {
        for(wrongTarget in listOf(false,true)) ChatManagementFixture().use { base ->
            val mode=Mode();val target=base.target
            fun workspace()=base.workspace().put("capabilities",JSONArray(listOf("commands:target-bound:v1","reply","stop","desktop:creations","desktop:creations:projectless")))
            fun history()=JSONObject().put("hostId",target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(target))
                .put("messages",JSONArray()).put("runtime",JSONObject().put("state","running").put("turnId","matrix-turn").put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL))
            CommandFixture { request -> when(request.path) {
                "/host" -> WireResponse(body=workspace().toString())
                "/v1/workspace","/v1/history" -> if(mode.rejected&&!wrongTarget) WireResponse(401,"{\"error\":\"unauthorized\"}") else {
                    val body=if(request.path=="/v1/workspace")workspace() else history()
                    if(mode.rejected)body.getJSONObject("readTarget").put("runtimeId","unrelated-runtime")
                    WireResponse(body=body.toString())
                }
                "/v1/models" -> WireResponse(body=JSONObject().put("hostId",target.hostId).put("models",JSONArray()).toString())
                else -> WireResponse(404)
            } }.use { server ->
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val session=withContext(Dispatchers.Main) { MobileSession(NativeSessionStorage(context,"matrix-cache-"+UUID.randomUUID()),NativeHostReads { "" }).also { it.restore() } }
                try {
                    waitFor { !session.restoring }
                    withContext(Dispatchers.Main) { session.enterForeground();session.connect("matrix",server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
                    waitFor { session.canStop && session.canCreateProjectless && !session.loading && !session.historyLoading }
                    withContext(Dispatchers.Main) { session.text="cached draft";session.leaveForeground();mode.rejected=true;session.enterForeground() }
                    waitFor { !session.selectedClient.online && !session.loading && !session.historyLoading }
                    withContext(Dispatchers.Main) {
                        assertFalse(session.canStop);assertFalse(session.canCreateProjectless)
                        session.stopTask();session.performAction();assertFalse(session.newChat())
                        assertEquals("cached draft",session.text)
                    }
                    assertTrue(server.requests.none { it.method=="POST" })
                } finally {withContext(Dispatchers.Main) { session.leaveForeground() }}
            }
        }
    }
}
