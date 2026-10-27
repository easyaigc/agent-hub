package com.agenthub.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual source-APK UID/Provider -> Android intent -> actual recipient Activity -> isolated local draft. */
class MobileExternalReceiverTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private var prior: MainActivity.AcceptanceSessionState?=null
    private var server: CommandFixture?=null
    private val sources=mutableListOf<String>()
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"external-receiver");session.resetAcceptance();session.changeLanguage("en");session.changeAppearance("light") }
        val target=JSONObject().put("agentId","codex").put("hostId","receiver-host").put("workspaceId","receiver-workspace").put("runtimeId","receiver-runtime")
        val fixture=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","receiver-host").put("name","Owned receiver target").put("connectionMode","desktop-runtime").put("readTarget",target)
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","original-thread").put("hostId","receiver-host").put("title","Original thread").put("state","idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","receiver-host").put("threadId","original-thread").put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("Owned receiver target",fixture.endpoint,"");session.selectThread(session.threads.single());session.text="原接收草稿 100% %@" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null };assertTrue(session.canCompose)
        main { prior=activity.adoptSessionForAcceptance(session) }
        ui.waitUntil(10_000) { session.externalInbox?.loading==false }
    }
    private fun launch(nonce: String,mode: String) {
        val sourcePackage=InstrumentationRegistry.getInstrumentation().context.packageName
        val intent=Intent().setComponent(ComponentName(sourcePackage,"com.agenthub.android.OwnedExternalSourceActivity"))
            .putExtra("nonce",nonce).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main { activity.startActivity(intent) }
    }
    @After fun finish() {
        try {
            for(nonce in sources) { launch(nonce,"cleanup");ui.waitForIdle() }
            main { prior?.let { activity.restoreSessionForAcceptance(it) };session.leaveForeground() }
            owned.verifyDefault();assertTrue(server?.requests.orEmpty().all { it.method=="GET" })
        } finally { server?.close() }
    }
    private fun received(mode: String) {
        val nonce=UUID.randomUUID().toString().replace("-","");sources+=nonce
        val inbox=session.externalInbox!!;val originalActivity=activity;val key=session.targetKey;val draft=session.text
        launch(nonce,mode)
        ui.waitUntil(20_000) { inbox.presentation?.batches?.any { batch -> batch.readyFiles.any { it.name.startsWith("inbox-source-$nonce-") } }==true }
        assertTrue(ui.activity===originalActivity);assertTrue(ui.activity.session===session)
        ui.onNodeWithTag("sheet-title").assertTextEquals("Files from other apps");assertTrue(session.attachments.isEmpty())
        val sourcePackage=InstrumentationRegistry.getInstrumentation().context.packageName
        val reportUri=DocumentsContract.buildDocumentUri(sourcePackage+".owned-documents","inbox-source-$nonce-report.txt")
        val reportBytes=activity.contentResolver.openInputStream(reportUri)!!.use { it.readBytes() };val report=JSONObject(String(reportBytes))
        assertEquals(sourcePackage,report.getString("sourcePackage"));assertEquals(activity.packageManager.getApplicationInfo(sourcePackage,0).uid,report.getInt("sourceUid"))
        assertNotEquals(Process.myUid(),report.getInt("sourceUid"));assertNotEquals(Process.myPid(),report.getInt("sourcePid"))
        val expected=report.getJSONArray("files");assertEquals(if(mode=="multiple")2 else 1,expected.length())
        for(n in 0 until expected.length()) {
            val row=expected.getJSONObject(n);val uri=Uri.parse(row.getString("uri"))
            assertEquals(PackageManager.PERMISSION_GRANTED,activity.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals(PackageManager.PERMISSION_DENIED,activity.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            ui.onNodeWithText(row.getString("name")).assertExists()
        }
        File(owned.folder,"$mode-independent-source.json").writeBytes(reportBytes);owned.dialogShot("$mode-system-received-files")
        ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(20_000) { !inbox.adding&&inbox.pending.isEmpty()&&session.attachments.size==expected.length() }
        assertEquals((0 until expected.length()).map { expected.getJSONObject(it).getString("name") },session.attachments.map { it.manifest.name })
        for(n in 0 until expected.length()) {
            val row=expected.getJSONObject(n);val file=session.attachments.single { it.manifest.name==row.getString("name") }
            val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file.manifest) }
            assertEquals(row.getString("sha256"),sha(bytes));assertEquals(row.getInt("size"),bytes.size)
        }
        assertEquals(key,session.targetKey);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty());assertTrue(server!!.requests.all { it.method=="GET" })
        File(owned.folder,"$mode-receiver-proof.json").writeText(JSONObject().put("actualIndependentSourceUid",true).put("actualIntentReceiver",true)
            .put("sameWarmActivityAndOriginalSession",true).put("readOnlyUriGrant",true).put("localOriginalDraftBytesAndNamesMatched",true)
            .put("newModelInputs",0).put("uploadOrSubmit",false).put("sourceReportSha256",sha(reportBytes)).toString())
    }
    @Test fun independentSingleFileShareUsesRealReceiverAndOnlyLocalOriginalDraft()=received("send")
    @Test fun independentMultipleFilesKeepOrderNamesHashesAndReadOnlyGrants()=received("multiple")
    @Test fun independentOpenWithContentUriUsesTheSameOriginalDraftBoundary()=received("view")
}
