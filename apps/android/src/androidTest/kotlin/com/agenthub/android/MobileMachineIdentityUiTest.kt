package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.MobileRules
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real foreground native UI and encrypted upgrade data; synthetic HTTP, no model execution. */
@RunWith(AndroidJUnit4::class)
class MobileMachineIdentityUiTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val timeout=Timeout.seconds(150)

    @Test fun oldDraftConflictAndAccountlessHostRestoreThroughNativeControls() {
        val i=InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
            "am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var activity: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };activity!=null }
        val main=activity!!
        val oldScope=TargetScope("codex","https://accounts.invalid","old-account","fixture-host","fixture-workspace","fixture-runtime")
        val secondScope=TargetScope("codex","https://accounts.invalid","second-account","fixture-host","fixture-workspace","fixture-runtime")
        val current=TargetScope("codex",null,null,"fixture-host","fixture-workspace","fixture-runtime")
        val root=MobileRules.cacheKey("fixture-host",oldScope,null,null)
        val a=MobileRules.cacheKey("fixture-host",oldScope,"fixture-thread",null)
        val b=MobileRules.cacheKey("fixture-host",secondScope,"fixture-thread",null)
        val canonical=RuntimeIdentity.cacheKey("fixture-host",current,"fixture-thread",null)
        val workspace=JSONObject().put("hostId","fixture-host").put("name","机器身份验收").put("connectionMode","history")
            .put("readTarget",encodeTarget(current)).put("capabilities",JSONArray(listOf("threads:read")))
            .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","fixture-thread").put("hostId","fixture-host").put("projectId",JSONObject.NULL).put("title","原聊天").put("state","idle")))
        val history=JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("readTarget",encodeTarget(current))
            .put("messages",JSONArray().put(JSONObject().put("id","answer").put("role","assistant").put("text","原电脑当前历史")))
        var session: MobileSession?=null
        CommandFixture { request -> WireResponse(body=if(request.path=="/v1/workspace") workspace.toString() else history.toString()) }.use { server ->
            val client=MobileClient("fixture-host","机器身份验收",oldScope,server.endpoint,false,"history",false,readerId="old-reader-id",requiresReadTarget=true)
            val store=NativeSessionStorage(i.targetContext,"machine-ui-"+UUID.randomUUID())
            val capturedClient=decodeClient(encodeClient(client).apply { remove("storageIdentity") })
            val originalIntent=ReplyIntent(client.id,"fixture-thread",UUID.randomUUID().toString(),"已提交的原始正文",emptyList(),"next-turn")
            val journal=NativeOperationJournal(store)
            val original=runBlocking {
                val record=journal.prepare(capturedClient,"fixture-thread","reply",originalIntent.body(),a,"original-version")
                journal.markSubmitting(record.target,record.id)
                journal.uncertain(record.target,record.id,"原结果未知")
            }
            val oldMetadata=JSONObject().put("version",1).put("appearance","light").put("demo",false).put("selected",client.id)
                .put("clients",JSONArray().put(encodeClient(client).apply { remove("storageIdentity") }))
                .put("drafts",JSONObject().put(a,"第一份原草稿").put(b,"第二份原草稿").put(canonical,"当前的新草稿"))
                .put("selectedThreads",JSONObject().put(root,"fixture-thread"))
                .put("expanded",JSONObject()).put("workspaces",JSONObject().put(root,workspace))
            store.write("metadata",oldMetadata.toString())
            fun showRestoredSession() {
                i.runOnMainSync {
                    session?.leaveForeground();main.session.leaveForeground()
                    session=MobileSession(store,NativeHostReads { "" })
                    main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    main.setContent { AgentHubApp(session!!) }
                    session!!.restore()
                }
            }
            fun shot(name: String) {
                ui.waitForIdle();i.uiAutomation.waitForIdle(200,5000)
                val run=InstrumentationRegistry.getArguments().getString("migrationRunId") ?: "standalone"
                require(run.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/UM01/$run").apply { mkdirs() }
                val image=i.uiAutomation.takeScreenshot()
                try { File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
            }
            fun selectSource(key: String, expectedText: String) {
                val options=session!!.codexLegacyStorageChoices
                assertEquals(3,options.size)
                ui.onNodeWithTag("storage-identity-choices").assertIsDisplayed().performClick()
                ui.onNodeWithTag("storage-identity-choice-${options.indexOf(key)}").performClick()
                ui.waitUntil(20_000) { session!!.text==expectedText }
                ui.onNodeWithTag("message-input").assertTextEquals(expectedText)
                ui.onNodeWithTag("storage-identity-choices").assertIsDisplayed()
                ui.onNodeWithTag("send-message").assertIsNotEnabled()
            }
            try {
                showRestoredSession()
                ui.waitUntil(30_000) { ui.onAllNodesWithTag("storage-identity-conflict").fetchSemanticsNodes().size==1 && session!!.selectedClient.online }
                ui.onNodeWithTag("storage-identity-conflict").assertIsDisplayed()
                ui.onNodeWithTag("message-input").assertTextEquals("当前的新草稿")
                ui.onNodeWithTag("send-message").assertIsNotEnabled()
                shot("legacy-drafts-conflict-preserved")
                selectSource(a,"第一份原草稿")
                ui.waitUntil(20_000) { ui.onAllNodesWithTag("storage-identity-conflict").fetchSemanticsNodes().isEmpty() && session!!.messages.any { it.text=="原电脑当前历史" } }
                ui.onNodeWithTag("message-input").assertTextEquals("第一份原草稿")
                ui.onNodeWithTag("send-message").assertIsNotEnabled()
                selectSource(b,"第二份原草稿")
                selectSource(canonical,"当前的新草稿")
                selectSource(a,"第一份原草稿")
                shot("legacy-draft-selected-accountless-host")
                runBlocking { withTimeout(20_000) { while(store.metadata()?.getJSONArray("clients")?.getJSONObject(0)?.optJSONObject("storageIdentity")==null) delay(50) } }
                showRestoredSession()
                ui.waitUntil(30_000) { session!!.selectedClient.online && session!!.text=="第一份原草稿" && session!!.messages.any { it.text=="原电脑当前历史" } }
                ui.onNodeWithTag("message-input").assertTextEquals("第一份原草稿")
                assertEquals("old-reader-id",session!!.selectedClient.readerId)
                assertTrue(sameScope(session!!.selectedClient.scope,current))
                // Re-adding the same physical Host after an account change must
                // keep its selected old namespace, including the root workspace.
                runBlocking { withContext(Dispatchers.Main) {
                    val reconnected=session!!.connect("机器身份验收",server.endpoint,"")
                    assertEquals(root,reconnected.cacheKey(null))
                    assertEquals(a,reconnected.cacheKey("fixture-thread"))
                    assertEquals("old-reader-id",reconnected.readerId)
                    assertTrue(sameScope(current,session!!.workspace!!.scope))
                    assertNull(session!!.workspace!!.scope!!.accountSubject)
                    assertEquals("第一份原草稿",session!!.text)
                } }
                selectSource(b,"第二份原草稿")
                selectSource(canonical,"当前的新草稿")
                selectSource(a,"第一份原草稿")
                val saved=store.metadata()!!.getJSONObject("drafts")
                assertEquals("第一份原草稿",saved.getString(a));assertEquals("第二份原草稿",saved.getString(b))
                assertEquals("当前的新草稿",saved.getString(canonical))
                val retained=runBlocking { journal.records(original.target).single() }
                assertEquals(original.intentSha256,retained.intentSha256)
                assertEquals(original.intent,retained.intent)
                assertTrue(retained.submissionAttempted)
                assertTrue(server.requests.isNotEmpty());assertTrue(server.requests.all { it.method=="GET" })
                shot("legacy-choice-encrypted-session-restored")
            } finally {
                i.runOnMainSync {
                    session?.leaveForeground();main.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if(!main.isDestroyed) main.setContent { AgentHubApp(main.session) }
                }
            }
        }
    }
}
