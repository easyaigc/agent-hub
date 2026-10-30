package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Main/Session/photo actors with an explicit query/permission source double. No OS prompt/DEV. */
class NativeMigrationPhotoLocalOwnerTest {
    private lateinit var f: MigrationInboxFixture
    private lateinit var context: Context
    private lateinit var voice: NativeSavedVoiceSessionFixture
    private val source=Source()
    private val ownerFailure=AtomicReference<Throwable?>()
    private class Source:NativeRecentPhotoSource {
        @Volatile var hold: CountDownLatch?=null
        val entered=CountDownLatch(1)
        val closed=AtomicBoolean(false)
        val unsubscribed=AtomicInteger()
        val snapshots=AtomicInteger()
        val heldSnapshots=AtomicInteger()
        val observations=AtomicInteger()
        @Volatile var observing=false
        override fun access()=NativePhotoAccess.FULL
        override fun permissions()=arrayOf("android.permission.READ_MEDIA_IMAGES")
        override fun observe(changed: ()->Unit): ()->Unit {
            observations.incrementAndGet();observing=true
            return {observing=false;unsubscribed.incrementAndGet();Unit}
        }
        override suspend fun snapshot(limit: Int): List<NativeRecentPhoto> {
            snapshots.incrementAndGet()
            hold?.let {barrier->withContext(NonCancellable+Dispatchers.IO){heldSnapshots.incrementAndGet();entered.countDown();try{check(barrier.await(30,TimeUnit.SECONDS))}finally{closed.set(true)}}}
            return emptyList()
        }
        override suspend fun thumbnail(photo: NativeRecentPhoto): Bitmap?=null
        override suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput=error("No original media reads in this fixture")
    }
    @get:Rule(order=0)val installation: org.junit.rules.TestRule=NativeMigrationPrivateActivityRule {
        f=MigrationInboxFixture().also {it.fresh()};context=object:ContextWrapper(f.context("source")){override fun getApplicationContext(): Context=this}
        voice=NativeSavedVoiceSessionFixture(context,f.storage("source"))
        withContext(Dispatchers.Main.immediate){voice.connect();voice.edit(false,NativeSavedVoiceSessionFixture.ORIGINAL);voice.session.configureRecentPhotos(context,source)}
        voice.storage.awaitQueuedWrites();Triple(f.run,voice.session,context)
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    @Before fun ready(){ui.waitUntil(30_000){ui.activity.session.migrationReady};assertSame(source,ui.activity.session.recentPhotos!!.source)}
    @After fun finish()=runBlocking {
        source.hold?.countDown();ui.runOnIdle {ui.activity.migration.cancel()}
        ui.waitUntil(30_000){!ui.activity.migration.busy&&!ui.activity.migration.needsOwnerRestore&&ui.activity.session.migrationReady}
        withContext(Dispatchers.Main.immediate) {
            val current=ui.activity.session;val operation=UUID.randomUUID().toString();val lease=current.quiesceForMigration(operation)
            withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()}
            current.retireForMigration(operation);current.voice?.retireForMigration();current.questionVoice?.retireForMigration()
        }
        assertEquals(0,voice.voiceHostCalls.get());assertEquals(0,voice.microphoneCalls.get());assertNull(ownerFailure.get())
    }
    private fun observe(block: suspend (NativeMigrationOwnerLease)->Unit={}) {
        val controller=ui.activity.migration;val field=NativeMigrationController::class.java.getDeclaredField("host").apply {isAccessible=true};val real=field.get(controller) as NativeMigrationLifecycle
        val owner=object:NativeMigrationLifecycle by real {
            override suspend fun quiesce(operationId: String)=real.quiesce(operationId).also {try{block(it)}catch(error: Throwable){ownerFailure.set(error)}}
            override suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean){real.restore(lease,requiresExplicitEnrollment);claimOwnedMigrationPickerAfterRestore(controller)}
        }
        ui.runOnIdle {controller.attach(owner)}
    }
    @Test fun realPermissionClaimBlocksAdmissionAndExactBookkeepingStreamsToOnlyInertFreshArchives()=runBlocking {
        val oldAuth=ui.activity.session.photoAuthorization!!;val oldRecent=ui.activity.session.recentPhotos!!
        withContext(Dispatchers.Main.immediate){assertTrue(oldAuth.claim("generated-original",false));assertTrue(oldAuth.busy)}
        ui.runOnIdle {assertFalse(ui.activity.session.migrationReady);ui.activity.migration.password=f.password;ui.activity.migration.export();assertEquals(NativeMigrationPhase.IDLE,ui.activity.migration.phase);oldAuth.complete();oldRecent.markImported(NativeRecentPhoto(Uri.parse("content://media/external/images/media/7")))}
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
        val permission=oldAuth.migrationStorage.read("attempt-images")!!;val recent=oldRecent.migrationStorage.read("preferences")!!
        observe {held->
            withContext(Dispatchers.IO) {
                for(kind in NativeMigrationPhotoLocalKind.values())held.requireLocalPhotoWriters(kind,voice.storage.migrationPhotoLocalKey(kind))
                assertTrue(runCatching {NativeInboxOwnership.writing(oldRecent.migrationStorage){error("Frozen write must not run")}}.exceptionOrNull() is NativeInboxOwnership.Busy)
            }
            assertFalse(oldAuth.claim("sealed-replacement",false));oldAuth.requestLaunch("sealed-replacement");assertNull(oldAuth.takeLaunch("sealed-replacement"))
            val independent=NativePhotoAuthorization(oldAuth.migrationStorage,source.permissions());assertFalse(independent.claim("non-owner-writer",false));assertTrue(independent.failed)
            val independentLibrary=NativeRecentPhotoLibrary(source,oldRecent.migrationStorage)
            withTimeout(10_000){while(!independentLibrary.preferencesFailed)delay(10)}
            assertTrue(oldRecent.ready);assertFalse(independentLibrary.ready)
            val jobs=NativeRecentPhotoLibrary::class.java.getDeclaredField("ownedJobs").apply {isAccessible=true}.get(independentLibrary) as Set<*>
            withTimeout(10_000){while(jobs.isNotEmpty())delay(10)}
            (NativeRecentPhotoLibrary::class.java.getDeclaredField("tasks").apply {isAccessible=true}.get(independentLibrary) as CoroutineScope).cancel()
        }
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){!ui.activity.migration.busy&&ui.activity.migration.phase==NativeMigrationPhase.CHOOSE_EXPORT}
        assertNull(ownerFailure.get());assertNotSame(oldRecent,ui.activity.session.recentPhotos);assertNotSame(oldAuth,ui.activity.session.photoAuthorization)
        withContext(Dispatchers.Main.immediate){assertFalse(oldAuth.claim("retired-owner",false))}
        val file=NativeMigrationController::class.java.getDeclaredField("exportFile").apply {isAccessible=true}.get(ui.activity.migration) as File
        withContext(Dispatchers.IO) {
            val destination=f.storage("destination");val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),file,f.password)
            NativeMigrationActivation(f.context("destination"),destination).activate(receipt);val cold=destination.reopen(f.context("destination"))
            for((kind,expected)in mapOf(NativeMigrationPhotoLocalKind.RECENTS to recent,NativeMigrationPhotoLocalKind.PERMISSION to permission)) {
                val original=NativeMigrationPhotoLocals.read(cold,kind).single();assertEquals(1,original.entries.size);assertEquals(expected,NativeMigrationPhotoLocals.text(cold,kind,original,0));assertFalse(File(cold.migrationPhotoLocalKey(kind)).exists())
            }
            cold.retireWriter();destination.retireWriter()
        }
        assertEquals(permission,ui.activity.session.photoAuthorization!!.migrationStorage.read("attempt-images"));assertEquals(recent,ui.activity.session.recentPhotos!!.migrationStorage.read("preferences"))
    }
    @Test fun noncancellableOriginalQueryMustActuallyExitBeforeAnyMigrationLeaseIsIssued() {
        val oldRecent=ui.activity.session.recentPhotos!!
        var triggerState=""
        ui.runOnIdle {
            assertTrue("Held-query fixture requires the real Activity to be RESUMED",ui.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            source.hold=CountDownLatch(1)
            oldRecent.setActive(true)
            oldRecent.refresh() // Same explicit port used by NativeRecentPhotoLifecycle.ON_RESUME; setActive(true) alone is idempotent.
            triggerState="lifecycle=${ui.activity.lifecycle.currentState}, ready=${oldRecent.ready}, access=${oldRecent.access}, loading=${oldRecent.loading}"
        }
        try{ui.waitUntil(30_000){source.entered.count==0L}}
        catch(failure:Exception){throw AssertionError("Original query did not enter: $triggerState; snapshots=${source.snapshots.get()}, heldSnapshots=${source.heldSnapshots.get()}, observations=${source.observations.get()}, observing=${source.observing}, unsubscribed=${source.unsubscribed.get()}, enteredRemaining=${source.entered.count}, closed=${source.closed.get()}",failure)}
        val unsubscribedBeforeMigration=source.unsubscribed.get()
        val leaseIssued=AtomicBoolean(false)
        observe {leaseIssued.set(true);assertTrue("Original NonCancellable query must actually exit before a lease is returned",source.closed.get())}
        ui.runOnIdle {ui.activity.migration.password=f.password;ui.activity.migration.export()}
        ui.waitUntil(30_000){source.unsubscribed.get()>unsubscribedBeforeMigration}
        assertFalse("Actual owner lease must not return while the original query is held",leaseIssued.get())
        assertFalse(source.closed.get());assertEquals(NativeMigrationPhase.PREPARING_EXPORT,ui.activity.migration.phase);assertSame(voice.session,ui.activity.session)
        source.hold!!.countDown();ui.waitUntil(30_000){!ui.activity.migration.busy&&ui.activity.migration.phase==NativeMigrationPhase.CHOOSE_EXPORT}
        assertTrue(source.closed.get())
        val callbackFailure=ownerFailure.get()
        if(callbackFailure!=null)throw AssertionError("Original photo-query lease observer failed; expected ownerFailure=null",callbackFailure)
        assertNull(callbackFailure);assertTrue("Actual owner lease observer must run after query exit",leaseIssued.get());assertNotSame(oldRecent,ui.activity.session.recentPhotos)
    }
}
