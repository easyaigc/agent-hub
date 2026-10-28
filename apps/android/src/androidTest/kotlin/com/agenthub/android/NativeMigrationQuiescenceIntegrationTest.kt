package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Actual coordinator storage work remains blocked while the real migration Cancel control runs on Main. */
class NativeMigrationQuiescenceIntegrationTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"migration-held-io");owned.session.restore()}
        ui.waitUntil(30_000){!owned.session.restoring&&owned.session.phoneReading.ready}
        ui.runOnIdle {previous=ui.activity.adoptSessionForAcceptance(owned.session);owned.session.bindStoredVoicePreferencesIfReady();owned.session.text="original unsaved draft for cancellation"}
        ui.waitUntil(30_000){owned.session.migrationReady&&owned.session.voiceSettings.persisted&&owned.session.chatNotifications?.migrationInteractionReady==true}
        ui.runOnIdle {ui.activity.migration.show()}
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.IDLE}
        runBlocking {owned.storage.awaitQueuedWrites()}
    }
    @After fun finish(){if(::previous.isInitialized)ui.runOnIdle{ui.activity.restoreSessionForAcceptance(previous);owned.verifyDefault()}}
    @Test fun actualHeldCoordinatorIoCannotPublishAndCancelKeepsMainAndOriginalDraftAvailable()=runBlocking {
        val actual=ui.activity.session;val coordinator=requireNotNull(actual.chatNotifications)
        val owner=coordinator.javaClass.getDeclaredField("ownerJob").apply {isAccessible=true}.get(coordinator) as Job
        fun field(value: Any,name: String)=value.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(value)
        val coordinatorScope=field(coordinator,"scope") as CoroutineScope
        val enrollment=requireNotNull(field(coordinator,"migrationEnrollment"))
        val enrollmentScope=field(enrollment,"scope") as CoroutineScope
        assertSame("Enrollment must capture this coordinator's owned scope",coordinatorScope,enrollmentScope)
        assertSame("Enrollment must be parented by this coordinator's owner Job",owner,enrollmentScope.coroutineContext[Job])
        assertFalse("Coordinator owner is live before quiescence",owner.isCompleted)
        val previousEnrollment=field(enrollment,"job") as Job?
        assertTrue("Initial enrollment finished before holding IO",previousEnrollment?.isCompleted==true)
        val disk=NativeSessionStorage::class.java.getDeclaredField("ioLock").apply {isAccessible=true}.get(owned.storage) as ReentrantLock
        val payload=MigrationActivationFixtures.payload().getJSONObject("records")
        val name=payload.keys().asSequence().single {it.startsWith("operations:")};val unknown=payload.getString(name)
        owned.storage.write(name,unknown)
        val originalCiphertext=MigrationActivationFixtures.tree(owned.storage).getValue(MigrationActivationFixtures.sha(name.toByteArray())+".bin")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val held=launch(Dispatchers.IO){owned.storage.operationTransaction {entered.countDown();check(release.await(15,TimeUnit.SECONDS))}}
        try {
            withContext(Dispatchers.IO){check(entered.await(5,TimeUnit.SECONDS))}
            ui.runOnIdle {coordinator.preferencesSaved()}
            val enrollmentJob=requireNotNull(field(enrollment,"job") as Job?)
            assertNotSame(previousEnrollment,enrollmentJob)
            assertTrue("The actual refresh belongs to this owner",owner.children.any {it===enrollmentJob})
            var heldEnrollmentThread: Thread?=null
            ui.waitUntil(5_000){
                heldEnrollmentThread=Thread.getAllStackTraces().entries.singleOrNull {(thread,stack)->
                    val enrollmentFrame=stack.any {it.className.startsWith("com.agenthub.android.NativeMigrationNotificationEnrollment\$refresh\$")}
                    val storageFrame=stack.any {it.className=="com.agenthub.android.NativeSessionStorage"&&it.methodName=="snapshotTransaction"}
                    enrollmentFrame&&(disk.hasQueuedThread(thread)||thread.state==Thread.State.BLOCKED&&storageFrame)
                }?.key
                enrollmentJob.isActive&&enrollmentJob.children.any {it.isActive}&&heldEnrollmentThread!=null
            }
            val heldThread=requireNotNull(heldEnrollmentThread)
            assertSame(enrollmentJob,field(enrollment,"job"))
            assertTrue("The held enrollment IO is still a child of this exact Job",enrollmentJob.children.any {it.isActive})
            java.io.File(owned.folder,"held-enrollment-owner.json").writeText(org.json.JSONObject()
                .put("coordinator",System.identityHashCode(coordinator)).put("owner",System.identityHashCode(owner))
                .put("gateJob",System.identityHashCode(enrollmentJob)).put("heldThreadId",heldThread.id)
                .put("heldThreadName",heldThread.name).put("gateParentIsCoordinator",true)
                .put("directlyQueuedOnRootIoLock",disk.hasQueuedThread(heldThread)).put("threadState",heldThread.state.name)
                .put("stack",org.json.JSONArray(heldThread.stackTrace.take(24).map {it.toString()})).toString())
            ui.runOnIdle {ui.activity.migration.password="owned actual cancel password";ui.activity.migration.export()}
            ui.waitUntil(5_000){actual.migrationPaused&&ui.activity.migration.phase==NativeMigrationPhase.PREPARING_EXPORT}
            assertFalse("Held actual coordinator owner cannot finish",owner.isCompleted)
            assertFalse("Held actual gate job cannot finish",enrollmentJob.isCompleted)
            assertTrue("Held exact enrollment thread remains blocked",heldThread.state in setOf(Thread.State.BLOCKED,Thread.State.WAITING,Thread.State.TIMED_WAITING))
            ui.onNodeWithTag("migration-cancel").performScrollTo().performClick()
            ui.waitUntil(5_000){!actual.migrationPaused&&ui.activity.migration.result==NativeMigrationResult.CANCELLED}
            ui.runOnIdle {assertSame(actual,ui.activity.session);assertEquals("original unsaved draft for cancellation",actual.text);actual.text="draft edited after actual cancellation"}
            assertFalse(held.isCompleted);assertFalse(owner.isCompleted);assertFalse(enrollmentJob.isCompleted)
            assertTrue("Held exact enrollment thread remains blocked",heldThread.state in setOf(Thread.State.BLOCKED,Thread.State.WAITING,Thread.State.TIMED_WAITING))
            ui.runOnIdle {assertFalse(actual.migrationReady);ui.activity.migration.password="owned second attempt stays gated";ui.activity.migration.export();assertFalse(actual.migrationPaused)}
        }finally {release.countDown();held.join()}
        withTimeout(10_000){owner.join();owned.storage.awaitQueuedWrites()}
        ui.waitUntil(10_000){actual.migrationReady}
        ui.runOnIdle {assertSame(actual,ui.activity.session);assertEquals("draft edited after actual cancellation",actual.text)}
        assertNull(owned.storage.migrationPublicationReceipt());assertEquals(unknown,owned.storage.read(name))
        assertEquals(originalCiphertext,MigrationActivationFixtures.tree(owned.storage).getValue(MigrationActivationFixtures.sha(name.toByteArray())+".bin"))
    }
}
