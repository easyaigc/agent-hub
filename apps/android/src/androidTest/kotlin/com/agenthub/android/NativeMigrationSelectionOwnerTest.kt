package com.agenthub.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Activity/Session/recovery/IO; registry platform launch is a double, never a real OS/DEV claim. */
class NativeMigrationSelectionOwnerTest {
    private lateinit var f: MigrationInboxFixture
    private lateinit var voice: NativeSavedVoiceSessionFixture
    private lateinit var context: Context
    private val revokes=AtomicInteger()
    private val worker=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private val files=mutableListOf<File>()
    private val stopWriters=mutableListOf<CountDownLatch>()
    private val writers=mutableListOf<Thread>()
    private val writerFailures=mutableListOf<AtomicReference<Throwable?>>()
    private val ownerFailure=AtomicReference<Throwable?>()
    @get:Rule(order=0)val installation: org.junit.rules.TestRule=NativeMigrationPrivateActivityRule {
        f=MigrationInboxFixture();f.fresh()
        context=object: ContextWrapper(f.context("source")) {
            override fun getApplicationContext(): Context=this
            override fun revokeUriPermission(uri: Uri,modeFlags: Int){revokes.incrementAndGet();super.revokeUriPermission(uri,modeFlags)}
        }
        voice=NativeSavedVoiceSessionFixture(context,f.storage("source"))
        withContext(Dispatchers.Main.immediate){voice.connect();voice.edit(false,NativeSavedVoiceSessionFixture.ORIGINAL)}
        voice.storage.awaitQueuedWrites();Triple(f.run,voice.session,context)
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    @Before fun ready(){ui.waitUntil(30_000){ui.activity.session.migrationReady};assertSame(voice.session,ui.activity.session)}
    @After fun finish()=runBlocking {
        releases.forEach {it.complete(Unit)};stopWriters.forEach {it.countDown()}
        writers.forEach {it.join(10_000);assertFalse(it.isAlive)};writerFailures.forEach {assertNull(it.get())}
        ui.runOnIdle {
            listOfNotNull(ui.activity.session.documentPicker?.recovery,ui.activity.session.photoPicker?.recovery,ui.activity.session.cameraCapture?.recovery).forEach {it.beforeRefreshForAcceptance=null}
            ui.activity.migration.cancel()
        }
        ui.waitUntil(30_000){!ui.activity.migration.busy&&!ui.activity.migration.needsOwnerRestore&&ui.activity.session.migrationReady}
        withContext(Dispatchers.Main.immediate) {
            val current=ui.activity.session;val operation=UUID.randomUUID().toString();val lease=current.quiesceForMigration(operation)
            withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()}
            current.retireForMigration(operation);current.voice?.retireForMigration();current.questionVoice?.retireForMigration()
        }
        worker.cancel();files.sortedByDescending {it.path.length}.forEach {assertTrue(!it.exists()||it.delete())}
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    private class Registry: ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?){launches+=requestCode}
    }
    private fun source(name: String,create: Boolean): Pair<File,Uri> {
        val folder=File(f.app.cacheDir,"resources/selection-owner-${f.run}-$name");check(folder.mkdirs());files+=folder
        val file=File(folder,"original.txt");files+=file;if(create)file.writeText("original selection 😀")
        return file to FileProvider.getUriForFile(f.app,f.app.packageName+".resources",file)
    }
    private fun start(registry: Registry): String {
        var id="";lateinit var job: Job
        ui.runOnIdle {
            val picker=ui.activity.session.documentPicker!!;picker.recovery!!.attach(registry,registry)
            val request=NativePhotoRequest(ui.activity.session.attachmentImportDestination(),ui.activity.session.attachmentErrorGeneration,false)
            id=request.id;assertTrue(picker.begin(request));job=worker.launch {picker.recovery.launch(request,NativeL10n(context,ui.activity.session.language))}
        }
        ui.waitUntil(30_000){job.isCompleted&&registry.launches.size==1};return id
    }
    private fun deliver(registry: Registry,uri: Uri) {
        ui.runOnIdle {assertTrue(registry.dispatchResult(registry.launches.single(),Activity.RESULT_OK,Intent().setData(uri)))}
    }
    private fun journal()=voice.storage.child(context,"document-results")
    private fun observe(block: suspend (NativeMigrationOwnerLease)->Unit={}) {
        val controller=ui.activity.migration;val field=NativeMigrationController::class.java.getDeclaredField("host").apply {isAccessible=true}
        val real=field.get(controller) as NativeMigrationLifecycle
        val owner=object:NativeMigrationLifecycle by real {
            override suspend fun quiesce(operationId: String)=real.quiesce(operationId).also {lease->try{block(lease)}catch(error: Throwable){ownerFailure.set(error)}}
            override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean){real.restore(lease,requiresExplicitEnrollment);claimOwnedMigrationPickerAfterRestore(controller)}
        }
        ui.runOnIdle {controller.attach(owner)}
    }
    @Test fun emptySelectionFreezeControlsDoNotBecomeUnsupportedDataAndOriginalPickersTransfer() {
        val document=ui.activity.session.documentPicker;val photo=ui.activity.session.photoPicker;val camera=ui.activity.session.cameraCapture
        observe {lease->withContext(Dispatchers.IO){for(kind in NativeMigrationSelectionJournal.Kind.entries)lease.requireSelectionWriters(kind)}}
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.CHOOSE_EXPORT||ui.activity.migration.result!=null&&!ui.activity.migration.busy}
        assertNull(ownerFailure.get());assertEquals(NativeMigrationPhase.CHOOSE_EXPORT,ui.activity.migration.phase)
        assertNotSame(voice.session,ui.activity.session);assertSame(document,ui.activity.session.documentPicker);assertSame(photo,ui.activity.session.photoPicker);assertSame(camera,ui.activity.session.cameraCapture)
        val field=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true};assertTrue((field.get(ui.activity.migration) as File).length()>0)
    }
    @Test fun actualFinalRefreshIsOwnedAfterUiBusyClearsAndCannotAdmitMigration() {
        val registry=Registry();val source=source("final-refresh",true);start(registry)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();releases+=release
        val recovery=ui.activity.session.documentPicker!!.recovery!!
        ui.runOnIdle {recovery.beforeRefreshForAcceptance={if(!recovery.busy){entered.complete(Unit);release.await()}}}
        deliver(registry,source.second);ui.waitUntil(30_000){entered.isCompleted}
        ui.runOnIdle {
            assertFalse(recovery.busy);assertFalse(recovery.loading);assertFalse(recovery.migrationIdle);assertFalse(ui.activity.session.migrationReady)
            ui.activity.migration.password=f.password;ui.activity.migration.export();assertEquals(NativeMigrationPhase.IDLE,ui.activity.migration.phase)
        }
        release.complete(Unit);ui.waitUntil(30_000){recovery.migrationIdle&&ui.activity.session.migrationReady}
        assertEquals(1,ui.activity.session.attachments.size);assertEquals(1,registry.launches.size)
    }
    @Test fun actualOwnerKernelFreezeRejectsStoreIoAndExportsExactInertOriginalWhilePickersTransfer()=runBlocking {
        val registry=Registry();val original=ui.activity.session.documentPicker!!;val recovery=original.recovery!!
        val request=withContext(Dispatchers.Main.immediate){NativePhotoRequest(voice.session.attachmentImportDestination(),voice.session.attachmentErrorGeneration,false)}
        NativeDocumentJournal(journal()).prepare(request);NativeDocumentJournal(journal()).update(request.id){it.copy(phase="failed")}
        ui.runOnIdle {recovery.attach(registry,registry);recovery.retryRestore()};ui.waitUntil(30_000){recovery.migrationIdle}
        val before=MigrationActivationFixtures.tree(journal());val originalJournal=journal().read(NativeMigrationSelectionJournal.RECORD);var checked=false
        observe {held->withContext(Dispatchers.IO){
            for(kind in NativeMigrationSelectionJournal.Kind.entries)held.requireSelectionWriters(kind)
            assertTrue(runCatching {NativeDocumentJournal(journal()).records()}.exceptionOrNull() is NativeInboxOwnership.Busy)
            val payload=journal().child(context,"payloads")
            assertTrue(runCatching {NativeAttachmentInboxStore(payload).snapshot()}.exceptionOrNull() is NativeInboxOwnership.Busy)
            val output=voice.storage.child(context,"camera-results").child(context,"outputs")
            assertTrue(runCatching {NativeCameraOutputs(context,output).prepare(UUID.randomUUID().toString())}.exceptionOrNull() is NativeInboxOwnership.Busy)
            checked=true
        }}
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){!ui.activity.migration.busy&&ui.activity.session!==voice.session}
        assertNull(ownerFailure.get());assertTrue(checked);assertEquals(NativeMigrationPhase.CHOOSE_EXPORT,ui.activity.migration.phase)
        assertSame(original,ui.activity.session.documentPicker);assertSame(recovery,ui.activity.session.documentPicker!!.recovery);assertEquals(before,MigrationActivationFixtures.tree(journal()))
        val file=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true}.get(ui.activity.migration) as File
        withContext(Dispatchers.IO) {
            val destination=f.storage("destination");val candidate=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),file,f.password)
            NativeMigrationActivation(f.context("destination"),destination).activate(candidate)
            val cold=destination.reopen(f.context("destination"));val kind=NativeMigrationSelectionJournal.Kind.DOCUMENT
            val archived=NativeMigrationSelections.read(cold,kind).single()
            val index=archived.entries.indexOfFirst {it.name=="journal:"+NativeMigrationSelectionJournal.RECORD};assertTrue(index>=0)
            assertEquals(originalJournal,NativeMigrationSelections.text(cold,kind,archived,index))
            for(selected in NativeMigrationSelectionJournal.Kind.entries)cold.migrationSelectionKeys(selected).forEach {assertFalse(File(it).exists())}
            assertFalse(File(cold.migrationInboxKey()).exists());cold.retireWriter();destination.retireWriter()
        }
    }
    @Test fun actualProviderPreparedBytesAndConsumedSelectionReceiptRemainInertAfterFreshActivation()=runBlocking {
        val registry=Registry();val source=source("consumed-export",true);val id=start(registry);deliver(registry,source.second)
        val recovery=ui.activity.session.documentPicker!!.recovery!!
        ui.waitUntil(30_000){recovery.migrationIdle&&ui.activity.session.migrationReady&&ui.activity.session.attachments.size==1}
        val originalJournal=journal().read(NativeMigrationSelectionJournal.RECORD)!!
        val receipt=journal().child(context,"payloads").read(NativeMigrationInboxGraph.INDEX)!!
        assertEquals("consumed",org.json.JSONObject(receipt).getJSONArray("records").objects().single {it.getString("id")==id}.getString("phase"))
        val originalFiles=ui.activity.session.attachments.toList();observe()
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){!ui.activity.migration.busy&&ui.activity.migration.phase==NativeMigrationPhase.CHOOSE_EXPORT}
        assertNull(ownerFailure.get());assertEquals(originalFiles,ui.activity.session.attachments.toList())
        val file=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true}.get(ui.activity.migration) as File
        withContext(Dispatchers.IO) {
            val destination=f.storage("destination");val candidate=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),file,f.password)
            NativeMigrationActivation(f.context("destination"),destination).activate(candidate)
            val cold=destination.reopen(f.context("destination"));val kind=NativeMigrationSelectionJournal.Kind.DOCUMENT;val archived=NativeMigrationSelections.read(cold,kind).single()
            for((name,raw)in mapOf("journal:"+NativeMigrationSelectionJournal.RECORD to originalJournal,"payloads:"+NativeMigrationInboxGraph.INDEX to receipt)) {
                val n=archived.entries.indexOfFirst {it.name==name};assertTrue(n>=0);assertEquals(raw,NativeMigrationSelections.text(cold,kind,archived,n))
            }
            val metadata=cold.metadata()!!;val files=metadata.getJSONObject("draftFiles").getJSONArray(voice.key(false))
            assertEquals(originalFiles.map {it.manifest.id},files.objects().map {it.getString("id")})
            assertArrayEquals(source.first.readBytes(),NativeDraftFiles(cold).bytes(originalFiles.single().manifest))
            for(selected in NativeMigrationSelectionJournal.Kind.entries)cold.migrationSelectionKeys(selected).forEach {assertFalse(File(it).exists())}
            cold.retireWriter();destination.retireWriter()
        }
    }
    @Test fun realRegistryLateDuplicateInvalidatesLeaseAndNeverRetriesProviderOrImportsIntoAnotherOwner() {
        val registry=Registry();val source=source("late-duplicate",false);val id=start(registry);deliver(registry,source.second)
        val original=ui.activity.session.documentPicker!!;val recovery=original.recovery!!
        ui.waitUntil(30_000){recovery.migrationIdle&&recovery.failures.any {it.id==id}}
        val before=runBlocking {NativeDocumentJournal(journal()).records().single {it.id==id}};source.first.writeText("provider becomes available later")
        observe {lease->
            assertTrue("Raw dispatch must recognize the original registered request code",registry.dispatchResult(registry.launches.single(),Activity.RESULT_OK,Intent().setData(source.second)))
            assertTrue("Expected an actually delivered late callback to mark migrationReturnPending; raw dispatch=true is not callback proof",recovery.migrationReturnPending)
            assertFalse("An actual late return must close migration admission",ui.activity.session.migrationReady)
            assertTrue("An actual late return must invalidate the original owner lease",runCatching {lease.requireCurrent()}.isFailure)
        }
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.session!==voice.session&&recovery.migrationIdle&&!ui.activity.migration.busy}
        val callbackFailure=ownerFailure.get()
        if(callbackFailure!=null)throw AssertionError("Original owner late-return observer failed; expected ownerFailure=null",callbackFailure)
        assertNull(callbackFailure);assertSame(original,ui.activity.session.documentPicker);assertEquals(1,registry.launches.size)
        assertEquals(before,runBlocking {NativeDocumentJournal(journal()).records().single {it.id==id}})
        assertTrue(ui.activity.session.attachments.isEmpty());assertEquals(NativeMigrationResult.SAVE_FAILED,ui.activity.migration.result)
        val audit=NativeSelectionReturnAudit.read(ui.activity.session.migrationStorage.read(NativeSelectionReturnAudit.RECORD)).single {it.requestId==id}
        assertEquals(listOf(source.second.toString()),audit.sources);assertEquals(NativeMigrationSelectionJournal.Kind.DOCUMENT,audit.kind)
        assertEquals(before.phase,org.json.JSONObject(audit.raw).getJSONObject("request").getString("phase"))
    }
    @Test fun noncooperatingCameraRawWriterContinuesWhenFreezeRefusesWithoutRevocationOrCleanup()=runBlocking {
        val outputStore=voice.storage.child(context,"camera-results").child(context,"outputs");val outputs=NativeCameraOutputs(context,outputStore)
        val id=UUID.randomUUID().toString();val output=outputs.prepare(id);val claim=outputStore.read("output-v1-$id")!!
        val entered=CountDownLatch(1);val stop=CountDownLatch(1);stopWriters+=stop;val writes=AtomicInteger();val failure=AtomicReference<Throwable?>();writerFailures+=failure
        val thread=Thread {try{RandomAccessFile(output.file,"rw").use {file->entered.countDown();while(!stop.await(1,TimeUnit.MILLISECONDS)){file.seek(file.length());file.write(7);writes.incrementAndGet()}}}catch(error: Throwable){failure.set(error)}}
        writers+=thread;thread.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()};ui.waitUntil(30_000){ui.activity.migration.result!=null}
        assertEquals(NativeMigrationResult.UNSUPPORTED,ui.activity.migration.result);assertSame(voice.session,ui.activity.session);assertFalse(voice.session.migrationPaused)
        val before=writes.get();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(writes.get()<=before&&System.nanoTime()<deadline)Thread.yield()
        assertTrue(writes.get()>before);assertTrue(thread.isAlive);assertEquals(claim,outputStore.read("output-v1-$id"));assertEquals(0,revokes.get());assertNull(failure.get())
        stop.countDown();thread.join(10_000);assertFalse(thread.isAlive);outputs.cleanup(id)
    }
}
