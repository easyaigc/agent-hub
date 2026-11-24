package com.agenthub.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires a dedicated empty acceptance profile; contacts only a synthetic loopback server. */
class Life01ActivityOwnerRegressionTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private fun activity():MainActivity { var a:MainActivity?=null;i.runOnMainSync { a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };return checkNotNull(a) }
    private fun requireIsolatedProfile() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("life01SyntheticOwner")=="1")
        check(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val metadata=NativeSessionStorage(i.targetContext).read("metadata")
        check(metadata==null || JSONObject(metadata).optJSONArray("clients")?.length()==0) {"Use a fresh dedicated profile"}
    }
    private fun start():MainActivity { ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use {it.readBytes()};ui.waitUntil(30_000) {runCatching {activity()}.isSuccess};return activity() }
    @Test fun capturedWriteSurvivesNaturalFinishWithoutResend()=runBlocking {
        requireIsolatedProfile()
        ChatManagementFixture().use { base ->
            base.writableHistory=true;base.state="idle"
            val started=CountDownLatch(1);val release=CountDownLatch(1);val posted=java.util.Collections.synchronizedList(mutableListOf<String>())
            CommandFixture {r -> when(r.path) {
                "/host","/v1/workspace" -> WireResponse(body=base.workspace().toString())
                "/v1/history" -> WireResponse(body=JSONObject().put("hostId",base.target.hostId).put("threadId",ChatManagementFixture.first).put("readTarget",CodexCommandCodec.target(base.target)).put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true).put("proposedPlan",JSONObject.NULL)).toString())
                "/v1/models" -> WireResponse(body=JSONObject().put("hostId",base.target.hostId).put("models",JSONArray()).toString())
                "/v1/replies" -> { val body=String(r.body);posted.add(body);started.countDown();check(release.await(30,TimeUnit.SECONDS));val v=JSONObject(body);WireResponse(body=JSONObject().put("hostId",base.target.hostId).put("threadId",ChatManagementFixture.first).put("clientMessageId",v.getString("clientMessageId")).put("state","submitted").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL).toString()) }
                else -> WireResponse(404)
            }}.use { server ->
                try {
                    val firstActivity=start();val s=firstActivity.session;ui.waitUntil(30_000){!s.restoring};check(s.clients.isEmpty()&&s.operationRecords.isEmpty()) {"Use a fresh dedicated profile"}
                    withContext(Dispatchers.Main) {s.connect("synthetic owner",server.endpoint,"")};ui.waitUntil(30_000){s.threads.any{it.id==ChatManagementFixture.first}&&!s.loading};i.runOnMainSync {s.selectThread(s.threads.first{it.id==ChatManagementFixture.first});s.text="Synthetic owner captured input"}
                    ui.waitUntil(30_000){!s.historyLoading&&s.decision.enabled};i.runOnMainSync {s.performAction()};ui.waitUntil(10_000){s.aiSharingRequest!=null};i.runOnMainSync {s.allowAISharing()};ui.waitUntil(10_000){s.aiSharingRequest==null&&!s.aiSharing.busy};i.runOnMainSync {s.performAction()}
                    assertTrue(withContext(Dispatchers.IO){started.await(20,TimeUnit.SECONDS)});assertEquals(1,posted.size);val original=posted.single();val body=JSONObject(original);assertEquals("Synthetic owner captured input",body.getString("text"));val id=body.getString("clientMessageId")
                    i.runOnMainSync {firstActivity.finish()};delay(500);val second=start();assertNotSame(firstActivity,second);assertSame(s,second.session);assertEquals(1,posted.size)
                    release.countDown();ui.waitUntil(30_000){s.operationRecords.any{it.id==id&&it.phase=="submitted"}};assertEquals(1,posted.size);assertEquals(original,posted.single());assertEquals(id,s.operationRecords.single{it.id==id}.id)
                } finally { release.countDown() }
            }
        }
    }
    @Test fun twentyNaturalReopensRetainSession()=runBlocking {
        requireIsolatedProfile()
        val first=start();val session=first.session;ui.waitUntil(30_000){!session.restoring};check(session.clients.isEmpty()&&session.operationRecords.isEmpty()) {"Use a fresh dedicated profile"};var previous=first
        repeat(20) {i.runOnMainSync {previous.finish()};delay(250);val next=start();assertNotSame(previous,next);assertSame(session,next.session);previous=next}
    }
    @Test fun freshShareAfterFinishAndRotationDispatchesOnce()=runBlocking {
        requireIsolatedProfile()
        val c=i.targetContext;val session=start().session;ui.waitUntil(30_000){!session.restoring};check(session.clients.isEmpty()&&session.operationRecords.isEmpty()) {"Use a fresh dedicated profile"};val directory=java.io.File(c.cacheDir,"resources/life01-owner-share").apply {mkdirs()};val file=java.io.File(directory,"synthetic.txt").apply {writeText("LIFE01 isolated share fixture")};val uri=androidx.core.content.FileProvider.getUriForFile(c,c.packageName+".resources",file)
        ui.waitUntil(30_000){session.externalInbox?.loading==false};val before=session.externalInbox!!.pending.map{it.id}.toSet();val a=activity();i.runOnMainSync {a.finish()};delay(500)
        val intent=Intent(Intent.ACTION_SEND).setClass(c,MainActivity::class.java).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.runOnMainSync {c.startActivity(intent)};ui.waitUntil(30_000){runCatching {activity()!==a}.getOrDefault(false)};val fresh=activity();assertSame(session,fresh.session)
        ui.waitUntil(30_000){session.externalInbox!!.pending.map{it.id}.toSet().minus(before).size==1};val added=session.externalInbox!!.pending.map{it.id}.toSet().minus(before);assertEquals(1,added.size)
        i.runOnMainSync {fresh.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};ui.waitUntil(30_000){runCatching {activity()!==fresh}.getOrDefault(false)};delay(1500);val rotated=activity();assertSame(session,rotated.session);assertEquals(added,session.externalInbox!!.pending.map{it.id}.toSet().minus(before));i.runOnMainSync {rotated.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
    }

}
