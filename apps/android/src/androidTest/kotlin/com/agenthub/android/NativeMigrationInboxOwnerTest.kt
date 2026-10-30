package com.agenthub.android

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Claims the owned picker request before composition launches it. This selector is not SAF acceptance. */
internal fun claimOwnedMigrationPickerAfterRestore(controller: NativeMigrationController) {
    val field=NativeMigrationController::class.java.getDeclaredField("afterRestore").apply {isAccessible=true}
    @Suppress("UNCHECKED_CAST") val original=field.get(controller) as? (() -> Unit) ?: return
    field.set(controller,{original();controller.claimPicker();Unit})
}

/** Actual Activity/controller/Session/NativeExternalAttachments; local Provider/fake Host, no upload/model. */
class NativeMigrationInboxOwnerTest {
    @get:Rule(order=0)val privateLaunch: org.junit.rules.TestRule=NativeMigrationPrivateActivityRule {
        f=MigrationInboxFixture();f.fresh();voice=NativeSavedVoiceSessionFixture(f.context("source"),f.storage("source"))
        withContext(Dispatchers.Main.immediate){voice.connect();voice.edit(false,NativeSavedVoiceSessionFixture.ORIGINAL)}
        voice.storage.awaitQueuedWrites();Triple(f.run,voice.session,f.context("source"))
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    private lateinit var f: MigrationInboxFixture
    private lateinit var voice: NativeSavedVoiceSessionFixture
    private var scenarioLaunch: Pair<NativeMigrationInboxOwnerActivity,Intent>?=null
    private val releases=mutableListOf<CountDownLatch>()
    private val threads=mutableListOf<Thread>()
    private val providers=mutableListOf<File>()
    private val threadFailures=mutableListOf<AtomicReference<Throwable?>>()
    private val observedFailure=AtomicReference<Throwable?>()
    private fun main(block: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main.immediate){block()}}
    @Before fun setup() {
        val activity=ui.activity;scenarioLaunch=activity to Intent(activity.intent)
        assertTrue(ui.activity.applicationContext is android.app.Application)
        assertSame(f.app.applicationContext,ui.activity.applicationContext)
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
        assertSame(voice.session,ui.activity.session)
    }
    @After fun finish()=runBlocking {
        var primaryFailure: Throwable?=null
        try {
            releases.forEach {it.countDown()};threads.forEach {it.join(10_000);assertFalse(it.isAlive)};threadFailures.forEach {assertNull(it.get())}
            if(::voice.isInitialized) {
                ui.runOnIdle {ui.activity.migration.completePicker(NativeMigrationPickerKind.EXPORT,null);ui.activity.migration.cancel()}
                ui.waitUntil(30_000){ui.activity.session.externalInbox?.migrationIdle!=false}
                val ending=ui.activity.session
                check(!ending.migrationPaused) {"Actual owner restoration is pending; retain the private fixture and receipt instead of forcing replacement"}
                ui.waitUntil(30_000){ending.migrationReady}
                withContext(Dispatchers.Main.immediate) {
                    if(!ending.migrationPaused) {
                        val operation=UUID.randomUUID().toString();val lease=ending.quiesceForMigration(operation)
                        withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()};ending.retireForMigration(operation);ending.voice?.retireForMigration();ending.questionVoice?.retireForMigration()
                    }
                }
            }
            providers.sortedByDescending {it.path.length}.forEach {assertTrue(!it.exists()||it.delete())}
        }catch(error: Throwable){primaryFailure=error;throw error}
        finally {
            // Match lifecycle events to the original launch only after all real ACTION_SEND
            // assertions and cleanup. This local test-handle reset dispatches no new input.
            try {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    scenarioLaunch?.let {(activity,launch)->activity.intent=Intent(launch)}
                }
            }catch(restore: Throwable) {
                val original=primaryFailure
                if(original==null)throw restore
                if(original!==restore)original.addSuppressed(restore)
            }
        }
    }

    private fun observe(quiesced: suspend (NativeMigrationOwnerLease)->Unit={}) {
        val controller=ui.activity.migration
        val field=NativeMigrationController::class.java.getDeclaredField("host").apply {isAccessible=true}
        val real=field.get(controller) as NativeMigrationLifecycle
        val observed=object: NativeMigrationLifecycle by real {
            override suspend fun quiesce(operationId: String)=real.quiesce(operationId).also {held->try{quiesced(held)}catch(error: Throwable){observedFailure.set(error)}}
            override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean) {
                real.restore(lease,requiresExplicitEnrollment);claimOwnedMigrationPickerAfterRestore(controller)
            }
        }
        ui.runOnIdle {controller.attach(observed)}
    }
    private fun source(name: String,create: Boolean=true): Pair<File,Intent> {
        val folder=File(f.app.cacheDir,"resources/inbox-migration-${f.run}-$name");check(folder.mkdirs());providers+=folder
        val file=File(folder,"original-$name.txt");providers+=file;if(create)file.writeText("original $name 😀")
        val uri=FileProvider.getUriForFile(f.app,f.app.packageName+".resources",file)
        return file to Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,uri).setType("text/plain")
    }
    private fun gate(child: NativeSessionStorage): CountDownLatch {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val failure=AtomicReference<Throwable?>();releases+=release;threadFailures+=failure
        val worker=Thread {try{child.operationTransaction {entered.countDown();check(release.await(20,TimeUnit.SECONDS))}}catch(error: Throwable){failure.set(error)}}
        threads+=worker;worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));assertNull(failure.get());return release
    }
    @Test fun actualControllerPreservesPartialCleanupAndTransfersOriginalInboxWithoutNewStartupCleanup()=runBlocking {
        f.seedChild(voice.storage);val before=f.childTree(voice.storage);val original=voice.session.externalInbox!!
        val child=voice.storage.child(f.context("source"),"external-inbox")
        var checked=false
        observe {held->
            withContext(Dispatchers.IO){held.requireInboxWriters(child.coordinationKey)}
            val failed=runCatching {NativeAttachmentInboxStore(child).discard(InboxContractFixture.id("failed-batch"))}.exceptionOrNull()
            assertTrue(failed is NativeInboxOwnership.Busy)
            withContext(Dispatchers.IO){assertEquals(before,f.childTree(voice.storage))};checked=true
        }
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.CHOOSE_EXPORT||ui.activity.migration.result!=null&&!ui.activity.migration.busy}
        assertEquals(NativeMigrationPhase.CHOOSE_EXPORT,ui.activity.migration.phase);assertNull(observedFailure.get());assertTrue(checked)
        assertNotSame(voice.session,ui.activity.session);assertSame(original,ui.activity.session.externalInbox)
        assertEquals(before,f.childTree(ui.activity.session.migrationStorage))
        val field=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true};val envelope=field.get(ui.activity.migration) as File
        val destination=f.storage("destination");val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),envelope,f.password)
        NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt){records->
            val graph=NativeMigrationInbox.validate(records.text,records.binary.filterKeys {it.startsWith(NativeMigrationInbox.BINARY)})
            val vector=InboxContractFixture.all();val originalArchive=graph.originals.single()
            originalArchive.entries.forEachIndexed {index,entry->
                if(entry.kind=="text")assertEquals(vector.text.getValue(entry.name),records.text.getValue(originalArchive.alias(index)))
                else {val out=java.io.ByteArrayOutputStream();records.binary.getValue(originalArchive.alias(index)).verifiedCopy(out);assertArrayEquals(vector.bytes.getValue(entry.name),out.toByteArray())}
            }
        }
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    @Test fun actualStartupCleanupAndRefreshKeepWritingAfterControllerRejectsBusyKernelOwner() {
        val child=voice.storage.child(f.context("source"),"external-inbox");val release=gate(child)
        lateinit var second: NativeExternalAttachments
        ui.runOnIdle {second=NativeExternalAttachments(f.context("source"),child){true}}
        assertFalse(second.migrationIdle);val old=ui.activity.session
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.migration.result!=null}
        assertEquals(NativeMigrationResult.UNSUPPORTED,ui.activity.migration.result);assertSame(old,ui.activity.session);assertFalse(old.migrationPaused)
        assertFalse(second.migrationIdle);release.countDown()
        ui.waitUntil(30_000){second.migrationIdle}
        ui.runOnIdle {second.retireForMigration()}
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    @Test fun actualRetryAndConsumeWaitForProviderAndMetadataIoBeforeMigrationAdmission() {
        val inbox=ui.activity.session.externalInbox!!;val child=voice.storage.child(f.context("source"),"external-inbox")
        val (file,intent)=source("retry",false);val batch=UUID.randomUUID().toString()
        main {assertTrue(inbox.receive(intent,batch))}
        ui.waitUntil(30_000){inbox.migrationIdle&&inbox.pending.singleOrNull()?.phase=="failed"}
        ui.waitUntil(10_000){inbox.presentation?.batches?.singleOrNull()?.phase=="failed"};file.writeText("original retry 😀")
        val readRelease=gate(child)
        ui.onNodeWithTag("external-attachment-retry").performScrollTo().performClick()
        ui.waitUntil(10_000){inbox.receiving};assertFalse(ui.activity.session.migrationReady)
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        assertEquals(NativeMigrationPhase.IDLE,ui.activity.migration.phase);assertFalse(voice.session.migrationPaused)
        readRelease.countDown();ui.waitUntil(30_000){inbox.migrationIdle&&inbox.pending.singleOrNull()?.phase=="pending"}
        ui.waitUntil(10_000){inbox.presentation?.batches?.singleOrNull()?.phase=="pending"}
        val entered=CountDownLatch(1);val writeRelease=CountDownLatch(1);releases+=writeRelease
        voice.storage.enqueueSnapshot("owned-inbox-migration-confirm",{entered.countDown();check(writeRelease.await(20,TimeUnit.SECONDS))},{throw AssertionError(it)})
        assertTrue(entered.await(5,TimeUnit.SECONDS));ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
        ui.waitUntil(20_000){inbox.adding&&voice.session.attachments.isNotEmpty()}
        assertFalse(voice.session.migrationReady)
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        assertEquals(NativeMigrationPhase.IDLE,ui.activity.migration.phase);assertFalse(voice.session.migrationPaused)
        writeRelease.countDown();ui.waitUntil(30_000){inbox.migrationIdle&&inbox.pending.isEmpty()}
        assertEquals("consumed",runBlocking {NativeAttachmentInboxStore(child).snapshot()}.records.single {it.id==batch}.phase)
        assertEquals(1,voice.session.attachments.size);assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    @Test fun intentDuringOwnershipAcquisitionRefusesBeforePauseAndDrainsOnceOnOriginalOwner() {
        val inbox=voice.session.externalInbox!!;val release=gate(voice.storage);val incoming=source("during-acquire")
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(10_000){voice.session.migrationIngressPaused}
        var operation: String?=null
        ui.runOnIdle {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(ui.activity,incoming.second)
            val field=MainActivity::class.java.getDeclaredField("externalOperationId").apply {isAccessible=true};operation=field.get(ui.activity) as String
        }
        assertFalse(voice.session.migrationPaused);assertFalse(inbox.receiving);release.countDown()
        ui.waitUntil(30_000){ui.activity.migration.result!=null&&inbox.migrationIdle&&inbox.pending.size==1}
        assertSame(voice.session,ui.activity.session);assertSame(inbox,ui.activity.session.externalInbox);assertFalse(voice.session.migrationPaused)
        assertEquals(operation,inbox.pending.single().id);assertEquals(NativeMigrationResult.SAVE_FAILED,ui.activity.migration.result)
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
    @Test fun lateActivityIntentInvalidatesFrozenExportThenReachesTransferredSourceInboxOnce() {
        val inbox=voice.session.externalInbox!!;val first=source("first");val second=source("late");val firstId=UUID.randomUUID().toString()
        main {assertTrue(inbox.receive(first.second,firstId))};ui.waitUntil(30_000){inbox.migrationIdle&&inbox.pending.size==1}
        val original=runBlocking {NativeAttachmentInboxStore(voice.storage.child(f.context("source"),"external-inbox")).snapshot()}.records.single()
        var lateId: String?=null
        observe {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(ui.activity,second.second)
            val field=MainActivity::class.java.getDeclaredField("externalOperationId").apply {isAccessible=true};lateId=field.get(ui.activity) as String
            assertEquals(1,inbox.pending.size);assertFalse(inbox.receiving)
        }
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){ui.activity.session!==voice.session&&ui.activity.session.externalInbox?.let {it.migrationIdle&&it.pending.size==2}==true}
        assertSame(inbox,ui.activity.session.externalInbox);assertNotNull(lateId);assertNull(observedFailure.get())
        val rows=runBlocking {NativeAttachmentInboxStore(ui.activity.session.migrationStorage.child(f.context("source"),"external-inbox")).snapshot()}.records
        assertEquals(2,rows.size);assertEquals(original,rows.single {it.id==firstId});assertEquals(1,rows.count {it.id==lateId})
        assertEquals(NativeMigrationResult.SAVE_FAILED,ui.activity.migration.result)
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get())
    }
}
