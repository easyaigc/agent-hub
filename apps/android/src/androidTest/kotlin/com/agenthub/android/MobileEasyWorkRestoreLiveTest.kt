package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Runs in a newly launched process after the runner explicitly stops this test app. */
@RunWith(AndroidJUnit4::class)
class MobileEasyWorkRestoreLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(180)
    @Test fun encryptedStateAndLiveConnectionAfterProcessRestart() {
        val i=InstrumentationRegistry.getInstrumentation()
        val config=JSONObject(File(InstrumentationRegistry.getArguments().getString("easyWorkConfig")!!).readText())
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A5-live-${config.optString("testPath","lan")}")
        val input=JSONObject(File(folder,"restart-input.json").readText())
        val storage=NativeSessionStorage(i.targetContext)
        val expiredToken=if(input.has("grantRef")) {
            val grant=EasyWorkDeviceGrant(JSONObject(storage.read(input.getString("grantRef"))!!).getJSONObject("grant").toString())
            assertTrue("Access must expire by actual wall clock while the app was stopped",grant.accessExpiry<java.time.Instant.now())
            val identity=runBlocking { EasyWorkIdentity.load(storage) };val signer=EasyWorkDPoP.load(identity)
            val url="http://127.0.0.1:${config.getInt("relayPort")}/v2/remote-access/bindings"
            val client=EasyWorkHttp.client(url)
            try {
                val failure=runCatching { runBlocking { EasyWorkHttp.request(client,url,method="GET",headers=mapOf("Authorization" to "DPoP ${grant.accessToken}","DPoP" to signer.proof("GET",url,grant.accessToken))) } }.exceptionOrNull()
                assertTrue("Real expired access must be denied by the actual service",failure is EasyWorkDeviceAuthorizationError&&failure.status==401)
            } finally { EasyWorkHttp.dispose(client) }
            grant.accessToken
        } else null
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var main: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { main=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };main!=null }
        val session=main!!.session
        ui.waitUntil(120_000) { check(session.error==null) { "Restore failed: ${session.error}" };session.selectedClient.scope?.agentId=="easywork"&&session.selectedClient.online&&session.selectedThread==input.getString("threadId")&&!session.historyLoading&&!session.loading&&session.canCompose&&session.models.isNotEmpty() }
        assertEquals(easyCanonical(input.getJSONObject("target")),easyCanonical(CodexCommandCodec.target(session.selectedClient.scope!!)))
        assertEquals(input.getString("draft"),session.text)
        assertTrue(session.operationRecords.any { it.id==input.getString("operationId") })
        ui.onNodeWithTag("message-input").assertTextEquals(input.getString("draft"))
        val http=URL("http://127.0.0.1:${config.getInt("observerPort")}/evidence?native=1&threadId="+session.selectedThread).openConnection() as HttpURLConnection
        http.connectTimeout=3000;http.readTimeout=10_000
        val desktop=try { JSONObject(http.inputStream.bufferedReader().use { it.readText() }) } finally { http.disconnect() }
        val runs=desktop.getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        assertEquals(input.getJSONArray("runs").strings().toSet(),runs)
        assertTrue(desktop.getJSONArray("requests").objects().any { val r=it.getJSONObject("request");r.getString("method")=="runtime/context"&&it.getJSONObject("response").optBoolean("ok") })
        val bitmap=i.uiAutomation.takeScreenshot()
        try { File(folder,"native-process-restored.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        val proof=JSONObject().put("status","passed").put("api",android.os.Build.VERSION.SDK_INT).put("threadId",session.selectedThread).put("draftPreserved",true).put("operationPreserved",true).put("liveConnectionVerified",true).put("modelRunsUnchanged",true)
        if(expiredToken!=null) {
            val grant=EasyWorkDeviceGrant(JSONObject(storage.read(input.getString("grantRef"))!!).getJSONObject("grant").toString())
            assertNotEquals(expiredToken,grant.accessToken);assertTrue(grant.accessExpiry>java.time.Instant.now())
            proof.put("realExpiredAccessDenied",true).put("deviceGrantRefreshedWithoutAccountLogin",true)
        }
        File(folder,"restore-proof.json").writeText(proof.toString())
        ui.runOnIdle { session.leaveForeground() }
    }
}
