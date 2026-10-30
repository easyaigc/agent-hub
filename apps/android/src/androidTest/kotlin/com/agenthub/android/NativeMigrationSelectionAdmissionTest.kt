package com.agenthub.android

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual registry/sealed owner fault selectors. Platform launch is a double; no OS/provider replay. */
class NativeMigrationSelectionAdmissionTest {
    private lateinit var f: MigrationInboxFixture
    private lateinit var context: Context
    private lateinit var voice: NativeSavedVoiceSessionFixture
    private var lease: NativeMigrationOwnerLease?=null
    private var archiveExpected=false
    private val registry=Registry()
    private lateinit var request: NativePhotoRequest
    @get:Rule(order=0)val installation: org.junit.rules.TestRule=NativeMigrationPrivateActivityRule {
        f=MigrationInboxFixture().also {it.fresh()}
        context=object:ContextWrapper(f.context("source")){override fun getApplicationContext(): Context=this}
        voice=NativeSavedVoiceSessionFixture(context,f.storage("source"))
        withContext(Dispatchers.Main.immediate){voice.connect();voice.edit(false,NativeSavedVoiceSessionFixture.ORIGINAL)}
        voice.storage.awaitQueuedWrites();Triple(f.run,voice.session,context)
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    private class Registry:ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?){launches+=requestCode}
    }
    private val recovery get()=checkNotNull(ui.activity.session.documentPicker!!.recovery)
    @Before fun prepare()=runBlocking {
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
        withContext(Dispatchers.Main.immediate) {
            val picker=ui.activity.session.documentPicker!!;picker.recovery!!.attach(registry,registry)
            request=NativePhotoRequest(voice.session.attachmentImportDestination(),voice.session.attachmentErrorGeneration,false)
            assertTrue(picker.begin(request));picker.recovery.launch(request,NativeL10n(context,voice.session.language))
        }
        assertEquals(1,registry.launches.size)
        NativeDocumentJournal(voice.storage.child(context,"document-results")).update(request.id){it.copy(phase="failed")}
        ui.runOnIdle {ui.activity.session.documentPicker!!.finish(request.id);recovery.retryRestore()}
        ui.waitUntil(30_000){ui.activity.session.migrationReady&&recovery.failures.any {it.id==request.id}}
        val operation=UUID.randomUUID().toString()
        lease=withContext(Dispatchers.Main.immediate){val raw=voice.session.freezeVoiceForMigration(operation);voice.session.quiesceForMigration(operation,raw)}
    }
    @After fun finish()=runBlocking {
        val held=lease?:return@runBlocking
        withContext(NonCancellable+Dispatchers.IO){held.releaseRawWriters()}
        withContext(NonCancellable+Dispatchers.Main.immediate) {
            if(archiveExpected){voice.session.retireForMigration(held.operationId);voice.session.voice?.retireForMigration();voice.session.questionVoice?.retireForMigration()}
            else {
                // Bounds refusal deliberately has no durable audit/retirement proof. Keep this
                // generated private fixture paused; do not clear or replay its original callback.
                assertTrue(recovery.migrationReturnPending)
                assertTrue(runCatching {recovery.requireRetirableForMigration(held.operationId)}.isFailure)
            }
        }
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
        f.publish("admission-result",org.json.JSONObject().put("requestId",request.id).put("archived",archiveExpected).put("pending",!archiveExpected)
            .put("failure",recovery.migrationReturnFailure?:org.json.JSONObject.NULL).put("modelTasks",0).put("kernelLeasesReleased",true))
    }
    private fun deliver(uris: List<Uri>) {
        val clip=ClipData.newRawUri("generated-original",uris.first());uris.drop(1).forEach {clip.addItem(ClipData.Item(it))}
        ui.runOnIdle {
            assertTrue(registry.dispatchResult(registry.launches.single(),Activity.RESULT_OK,Intent().apply {clipData=clip}))
            assertTrue(recovery.migrationReturnPending);assertTrue(runCatching {lease!!.requireCurrent()}.isFailure)
        }
    }
    @Suppress("UNCHECKED_CAST") private fun retainedSources(): List<String> {
        val map=NativeSelectionRecovery::class.java.getDeclaredField("deferredReturns").apply {isAccessible=true}.get(recovery) as Map<*,*>
        val value=map.values.single()!!
        return value.javaClass.getDeclaredField("sources").apply {isAccessible=true}.get(value) as List<String>
    }
    private fun verifyRefused(reason: String,original: List<Uri>) {
        ui.waitUntil(30_000){recovery.migrationReturnFailure!=null}
        assertEquals(reason,recovery.migrationReturnFailure);assertEquals(original.map(Uri::toString),retainedSources())
        deliver(listOf(Uri.parse("content://owned.invalid/late-replacement")))
        assertEquals(original.map(Uri::toString),retainedSources())
        assertNull(voice.storage.read(NativeSelectionReturnAudit.RECORD));assertTrue(recovery.failed)
        assertTrue(runCatching {recovery.requireRetirableForMigration(lease!!.operationId)}.isFailure)
    }
    @Test fun registryCountOverflowInvalidatesBeforeCaptureAndFirstIntentSurvivesReplacement() {
        val original=List(8193){Uri.parse("content://owned.invalid/original-$it")};deliver(original);verifyRefused("source-count",original)
    }
    @Test fun registryUtf8OverflowStaysUnarchivedWithoutTruncationOrSynchronousException() {
        val original=listOf(Uri.parse("content://owned.invalid/"+"中".repeat(NativeMigrationTextFrames.MAX_RECORD_BYTES/3+1)))
        deliver(original);verifyRefused("source-bytes",original)
    }
    @Test fun captureContractFailureStaysPendingAndCannotRetireOrRestoreAuthority() {
        ui.runOnIdle {recovery.receivePermission(request.id,true);assertTrue(recovery.migrationReturnPending);assertTrue(runCatching {lease!!.requireCurrent()}.isFailure)}
        ui.waitUntil(30_000){recovery.migrationReturnFailure!=null}
        assertEquals("capture-failed",recovery.migrationReturnFailure);assertNull(voice.storage.read(NativeSelectionReturnAudit.RECORD))
    }
    @Test fun intentSnapshotFailureRemainsIdentifiedAndNeverArchivesLaterReplacement() {
        val inaccessible=object:AbstractList<Uri>() {
            override val size=1
            override fun get(index: Int): Uri=throw IllegalStateException("generated immutable snapshot failure")
        }
        ui.runOnIdle {recovery.receive(request.id,inaccessible);assertTrue(recovery.migrationReturnPending);assertTrue(runCatching {lease!!.requireCurrent()}.isFailure)}
        assertEquals("intent-capture-failed",recovery.migrationReturnFailure);assertEquals(request.id,recovery.migrationUncapturedRequestId)
        deliver(listOf(Uri.parse("content://owned.invalid/late-replacement")))
        assertEquals(request.id,recovery.migrationUncapturedRequestId);assertNull(voice.storage.read(NativeSelectionReturnAudit.RECORD))
    }
    @Test fun pendingAuditWritePreservesFirstIntentAndSameWriteReconciliationNeverRetriesProvider()=runBlocking {
        val record=File(voice.storage.coordinationKey,NativeMigrationInertArchive.sha(NativeSelectionReturnAudit.RECORD.toByteArray())+".bin")
        val sidecar=File(record.path+".new");sidecar.writeText("owned pending write fixture");android.system.Os.chmod(sidecar.path,0x180)
        val original=listOf(Uri.parse("content://owned.invalid/first-return"));deliver(original)
        ui.waitUntil(30_000){recovery.migrationReturnFailure=="write-failed"};val before=sidecar.readBytes()
        deliver(listOf(Uri.parse("content://owned.invalid/late-replacement")));assertEquals(original.map(Uri::toString),retainedSources());assertArrayEquals(before,sidecar.readBytes())
        check(sidecar.delete()) // Only this exact test's synthetic pending sidecar is removed.
        withContext(Dispatchers.Main.immediate){recovery.awaitMigrationReturnAudits(lease!!.operationId)}
        val audit=NativeSelectionReturnAudit.read(voice.storage.read(NativeSelectionReturnAudit.RECORD)).single()
        assertEquals(original.map(Uri::toString),audit.sources);assertNull(recovery.migrationReturnFailure);assertTrue(recovery.migrationReturnPending)
        assertTrue(runCatching {lease!!.requireCurrent()}.isFailure);archiveExpected=true
    }
}
