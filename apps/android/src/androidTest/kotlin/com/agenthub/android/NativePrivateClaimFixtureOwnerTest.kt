package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Controlled fake-resource tests of the real coordinator/rule. No Android, Keystore or Native pass. */
class NativePrivateClaimFixtureOwnerTest {
    private class Root
    private class Lease {val frozen=AtomicBoolean(true)}
    private class Session {var internalLease: Lease?=null}
    private class ObservedRetention: RuntimeException()
    private class Fixture(val main: CoroutineDispatcher=Dispatchers.Unconfined,resourceKey: String?=null) {
        val root=Root();val session=Session();val lease=Lease()
        val token=PrivateClaimFixtureToken("fake-resource-jvm",Thread.currentThread())
        val record=PrivateClaimFixtureRecord<Session,Lease,Root>(token,root,"fake-resource-jvm",resourceKey)
        val retires=AtomicInteger();val releases=AtomicInteger();val abandons=AtomicInteger()
        var create: ()->Session={session}
        var restore: (Session)->Unit={}
        var awaitRestore: suspend (Session)->Unit={}
        var acquire: suspend (Session)->Lease={it.internalLease=lease;lease}
        var retire: (Session,Lease)->Unit={_,_->retires.incrementAndGet()}
        var abandon: suspend (Session)->Unit={abandons.incrementAndGet()}
        var release: (Lease)->Unit={releases.incrementAndGet();it.frozen.set(false)}
        var verify: ()->Unit={}
        // Failed fakes keep their exact references in the real registry until this JVM exits.
        // Only the fake test wait throws; Native fixtures always use the untimed real wait.
        fun lifecycle()=PrivateClaimFixtureLifecycle(record,main,
            create={create()},restore={restore(it)},awaitRestore={awaitRestore(it)},acquire={acquire(it)},
            retire={s,l->retire(s,l)},abandon={abandon(it)},release={release(it)},verify={verify()},
            waitForConfirmation={throw ObservedRetention()})
    }
    private suspend fun outcome(f: Fixture,body: suspend (Lease)->String={"body value"}): Throwable?=
        supervisorScope {runCatching {f.lifecycle().run(body)}.exceptionOrNull()}
    private fun retained(f: Fixture) {
        assertTrue(PrivateClaimFixtureOwners.retainedRecords().any {it===f.record})
        assertSame(f.root,f.record.storage);assertSame(f.session,f.record.session)
        assertFalse(f.record.cleanupConfirmed)
    }
    @Test fun successReturnsOnlyAfterNormalRetirementAndRelease()=runBlocking {
        val f=Fixture();assertEquals("body value",f.lifecycle().run {assertSame(f.lease,it);"body value"})
        assertSame(f.session,f.record.session);assertSame(f.lease,f.record.lease)
        assertTrue(f.record.retirementConfirmed&&f.record.releaseConfirmed&&f.record.cleanupConfirmed)
        assertEquals(1,f.retires.get());assertEquals(1,f.releases.get());assertEquals(0,f.abandons.get())
        assertFalse(f.lease.frozen.get());assertFalse(PrivateClaimFixtureOwners.retainedRecords().any {it===f.record})
    }
    @Test fun promptCancellationAfterMainCreateStillAbandonsExactRegisteredSession()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val dispatcher=executor.asCoroutineDispatcher()
        try {
            val f=Fixture(dispatcher);val caller=AtomicReference<Job>()
            f.restore={assertSame(f.session,f.record.session);caller.get().cancel()}
            val task=launch(start=CoroutineStart.LAZY){f.lifecycle().run {"unexpected body"}}
            caller.set(task);task.start();withTimeout(5000){task.join()}
            assertSame(f.session,f.record.session);assertNull(f.record.lease)
            assertTrue(f.record.cleanupConfirmed);assertEquals(1,f.abandons.get());assertEquals(0,f.releases.get())
        }finally {dispatcher.close();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun promptCancellationAfterMainAcquireCannotLoseExactLease()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val dispatcher=executor.asCoroutineDispatcher()
        try {
            val f=Fixture(dispatcher);val caller=AtomicReference<Job>()
            f.acquire={it.internalLease=f.lease;caller.get().cancel();f.lease}
            val task=launch(start=CoroutineStart.LAZY){f.lifecycle().run {"unexpected body"}}
            caller.set(task);task.start();withTimeout(5000){task.join()}
            assertSame(f.lease,f.record.lease);assertTrue(f.record.retirementConfirmed&&f.record.releaseConfirmed)
            assertEquals(1,f.retires.get());assertEquals(1,f.releases.get());assertEquals(0,f.abandons.get())
        }finally {dispatcher.close();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun suspendedMigrationRestoreHasExactSessionBeforeWorkAndCompletesBeforeAcquire()=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        val restored=AtomicBoolean();val acquisitions=AtomicInteger()
        f.awaitRestore={
            assertSame(f.session,it);assertSame(it,f.record.session)
            entered.complete(Unit);resume.await();restored.set(true)
        }
        f.acquire={assertTrue(restored.get());acquisitions.incrementAndGet();f.lease}
        val task=async{f.lifecycle().run {"migration restored"}}
        try {
            withTimeout(5000){entered.await()}
            assertSame(f.session,f.record.session);assertFalse(f.record.acquisitionEntered)
            assertEquals(0,acquisitions.get());assertNull(f.record.lease)
            resume.complete(Unit);assertEquals("migration restored",withTimeout(5000){task.await()})
            assertEquals(1,acquisitions.get());assertTrue(f.record.cleanupConfirmed)
            assertTrue(f.record.coordinator!!.isCompleted&&f.record.coordinatorCompletionConfirmed)
        }finally{resume.complete(Unit);task.cancelAndJoin()}
    }
    @Test fun cancellationDuringSuspendedMigrationRestoreAbandonsExactSessionBeforeAcquire()=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>();val blocked=CompletableDeferred<Unit>();val acquisitions=AtomicInteger()
        f.awaitRestore={assertSame(f.session,f.record.session);entered.complete(Unit);blocked.await()}
        f.acquire={acquisitions.incrementAndGet();f.lease}
        val task=launch{f.lifecycle().run<Unit>{error("unexpected body")}}
        try {
            withTimeout(5000){entered.await()};task.cancel();withTimeout(5000){task.join()}
            assertSame(f.session,f.record.session);assertFalse(f.record.acquisitionEntered);assertNull(f.record.lease)
            assertEquals(0,acquisitions.get());assertEquals(1,f.abandons.get());assertEquals(0,f.releases.get())
            assertTrue(f.record.cleanupConfirmed);assertTrue(f.record.coordinator!!.isCompleted&&f.record.coordinatorCompletionConfirmed)
        }finally{blocked.complete(Unit);task.cancelAndJoin()}
    }
    @Test fun failedRetirementRetainsRealReferencesAndFrozenFakeWithoutRelease()=runBlocking {
        val f=Fixture();val body=AssertionError("first body failure");val retire=IllegalStateException("partial retirement")
        f.retire={_,_->f.retires.incrementAndGet();throw retire}
        assertTrue(outcome(f){throw body} is ObservedRetention)
        retained(f);assertSame(f.lease,f.record.lease);assertSame(body,f.record.primary)
        assertTrue(body.suppressed.any {it===retire});assertEquals(1,f.retires.get());assertEquals(0,f.releases.get())
        assertFalse(f.record.retirementConfirmed);assertTrue(f.lease.frozen.get())
    }
    @Test fun releaseFailureIsNotRetriedOrReportedAsReleased()=runBlocking {
        val f=Fixture();val body=AssertionError("original assertion");val close=IllegalStateException("partial raw close")
        f.release={f.releases.incrementAndGet();throw close}
        assertTrue(outcome(f){throw body} is ObservedRetention)
        retained(f);assertSame(body,f.record.primary);assertTrue(body.suppressed.any {it===close})
        assertTrue(f.record.retirementConfirmed);assertFalse(f.record.releaseConfirmed)
        assertEquals(1,f.retires.get());assertEquals(1,f.releases.get())
    }
    @Test fun acquireFailureWithoutReturnedLeaseKeepsSessionInternalOwnership()=runBlocking {
        val f=Fixture();val failure=IllegalStateException("unknown acquire return")
        f.acquire={it.internalLease=f.lease;throw failure}
        assertTrue(outcome(f) is ObservedRetention)
        retained(f);assertNull(f.record.lease);assertSame(f.lease,f.record.session!!.internalLease)
        assertSame(failure,f.record.primary);assertEquals(0,f.abandons.get());assertEquals(0,f.retires.get());assertEquals(0,f.releases.get())
    }
    @Test fun failedRestoreAbandonsBeforeAcquisitionAndPreservesOriginalError()=runBlocking {
        val f=Fixture();val first=AssertionError("restore failed")
        f.restore={throw first}
        assertSame(first,outcome(f));assertTrue(f.record.cleanupConfirmed)
        assertEquals(1,f.abandons.get());assertEquals(0,f.retires.get());assertEquals(0,f.releases.get())
    }
    @Test fun failedAbandonmentKeepsSessionAndBothErrorStacks()=runBlocking {
        val f=Fixture();val first=AssertionError("restore first");val secondary=IllegalStateException("abandon unconfirmed")
        f.restore={throw first};f.abandon={f.abandons.incrementAndGet();throw secondary}
        assertTrue(outcome(f) is ObservedRetention)
        retained(f);assertSame(first,f.record.primary);assertTrue(first.suppressed.any {it===secondary});assertEquals(1,f.abandons.get());assertEquals(0,f.releases.get())
    }
    @Test fun constructorEnteringWithoutReturningAnObjectCannotBeDeclaredClean()=runBlocking {
        val f=Fixture();val failure=IllegalStateException("constructor did not return")
        f.create={throw failure}
        assertTrue(outcome(f) is ObservedRetention)
        assertSame(f.root,f.record.storage);assertNull(f.record.session);assertNull(f.record.lease)
        assertSame(failure,f.record.primary);assertFalse(f.record.cleanupConfirmed)
        assertTrue(PrivateClaimFixtureOwners.retainedRecords().any {it===f.record});assertEquals(0,f.releases.get())
    }
    @Test fun retainedStorageCannotStartAReplacementSessionUnderAnotherToken()=runBlocking {
        val f=Fixture(resourceKey="jvm-only-retained-source");f.retire={_,_->error("retirement unknown")}
        assertTrue(outcome(f) is ObservedRetention)
        val replacement=Fixture(resourceKey="jvm-only-retained-source");val creations=AtomicInteger()
        replacement.create={creations.incrementAndGet();replacement.session}
        assertTrue(outcome(replacement) is IllegalStateException)
        assertEquals(0,creations.get());assertNull(replacement.record.session);assertTrue(replacement.record.cleanupConfirmed)
        retained(f);assertSame(f.lease,f.record.lease);assertTrue(f.lease.frozen.get());assertEquals(0,f.releases.get())
    }
    @Test fun verificationFailureCannotMaskBodyFailureAfterConfirmedCleanup()=runBlocking {
        val f=Fixture();val first=AssertionError("consume first");val secondary=AssertionError("verification second")
        f.verify={throw secondary}
        assertSame(first,outcome(f){throw first});assertTrue(first.suppressed.any {it===secondary})
        assertTrue(f.record.cleanupConfirmed);assertEquals(1,f.releases.get())
    }
    @Test fun normalRetireReturnStaysConfirmedWhenCallerIsCancelledInsideIt()=runBlocking {
        val f=Fixture();val caller=AtomicReference<Job>()
        f.retire={_,_->f.retires.incrementAndGet();caller.get().cancel()}
        val task=launch(start=CoroutineStart.LAZY){f.lifecycle().run {"completed body"}}
        caller.set(task);task.start();withTimeout(5000){task.join()}
        assertTrue(f.record.retirementConfirmed&&f.record.releaseConfirmed&&f.record.cleanupConfirmed)
        assertEquals(1,f.retires.get());assertEquals(1,f.releases.get())
    }
    @Test fun junitThreadIgnoresFurtherInterruptsUntilActualCleanupConfirms() {
        val executor=Executors.newSingleThreadExecutor();val dispatcher=executor.asCoroutineDispatcher()
        val retireEntered=CountDownLatch(1);val allowRetire=CountDownLatch(1);val waiting=CountDownLatch(1);val ignored=CountDownLatch(1)
        val holder=AtomicReference<PrivateClaimFixtureRecord<Session,Lease,Root>>()
        val original=AssertionError("original body before interruption");val thrown=AtomicReference<Throwable>();val releaseCalls=AtomicInteger()
        val rule=NativePrivateClaimFixtureOwnerRule()
        val statement=rule.apply(object: Statement(){override fun evaluate()=runBlocking {
            val session=Session();val lease=Lease();val record=PrivateClaimFixtureRecord<Session,Lease,Root>(PrivateClaimFixtureOwners.current(),Root(),"fake-rule-wait") {
                waiting.countDown();if(it.interruptions>=2)ignored.countDown()
            }
            holder.set(record)
            PrivateClaimFixtureLifecycle(record,dispatcher,create={session},restore={},awaitRestore={},acquire={lease},
                retire={_,_->retireEntered.countDown();allowRetire.await()},abandon={},release={releaseCalls.incrementAndGet();it.frozen.set(false)}
            ).run<String>{throw original}
            Unit
        }},Description.createTestDescription(javaClass,"controlled-junit-owner-wait"))
        val junit=Thread {try{statement.evaluate()}catch(error: Throwable){thrown.set(error)}}
        try {
            junit.start();assertTrue(retireEntered.await(5,TimeUnit.SECONDS));junit.interrupt()
            assertTrue(waiting.await(5,TimeUnit.SECONDS))
            // A second interrupt is observed only after the real rule has entered its untimed wait.
            junit.interrupt()
            assertTrue(ignored.await(5,TimeUnit.SECONDS))
            assertTrue(junit.isAlive);assertFalse(holder.get().cleanupConfirmed);assertEquals(0,releaseCalls.get())
            assertTrue(PrivateClaimFixtureOwners.retainedRecords().any {it===holder.get()})
            allowRetire.countDown();junit.join(5000)
            assertFalse(junit.isAlive);assertSame(original,thrown.get())
            assertTrue(holder.get().retirementConfirmed&&holder.get().releaseConfirmed);assertEquals(1,releaseCalls.get())
            assertTrue(original.suppressed.any {it is InterruptedException})
        }finally {allowRetire.countDown();if(junit.isAlive)junit.join(5000);dispatcher.close();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun confirmedCleanupStillRetainsOriginalFailureAndJUnitUntilActualCoordinatorExits() {
        val signalEntered=CountDownLatch(1);val allowSignal=CountDownLatch(1);val signalExited=CountDownLatch(1)
        val guardInterrupted=CountDownLatch(1);val guardInterruptedAgain=CountDownLatch(1);val signalHeld=AtomicBoolean();val signalCalls=AtomicInteger()
        val holder=AtomicReference<PrivateClaimFixtureRecord<Session,Lease,Root>>()
        val original=AssertionError("body failed before final signal");val thrown=AtomicReference<Throwable>()
        val retires=AtomicInteger();val releases=AtomicInteger()
        val bodyBlocked=CountDownLatch(1);val callerScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val caller=AtomicReference<Job>();val callerFailure=AtomicReference<Throwable>()
        val statement=NativePrivateClaimFixtureOwnerRule().apply(object: Statement(){override fun evaluate() {
            val record=PrivateClaimFixtureRecord<Session,Lease,Root>(PrivateClaimFixtureOwners.current(),Root(),"confirmed-cleanup-pending-signal") {
                if(it.cleanupConfirmed&&Thread.currentThread()!==it.token.junitThread) {
                    signalCalls.incrementAndGet()
                    if(signalHeld.compareAndSet(false,true)) {
                        signalEntered.countDown()
                        try{allowSignal.await()}finally{signalExited.countDown()}
                    }
                }
                if(it.interruptions>0)guardInterrupted.countDown()
                if(it.interruptions>1)guardInterruptedAgain.countDown()
            }
            holder.set(record)
            // The exact real coordinator can outlive this explicitly interruptible Statement.
            // Do not depend on when a particular runBlocking implementation returns on interrupt.
            caller.set(callerScope.launch {
                supervisorScope {
                    try{PrivateClaimFixtureLifecycle(record,Dispatchers.Unconfined,create={Session()},restore={},awaitRestore={},acquire={Lease()},
                        retire={_,_->retires.incrementAndGet()},abandon={},release={releases.incrementAndGet();it.frozen.set(false)}
                    ).run<String>{throw original}}catch(error: Throwable){callerFailure.set(error)}
                }
            })
            bodyBlocked.await()
        }},Description.createTestDescription(javaClass,"confirmed-cleanup-pending-coordinator"))
        val junit=Thread {try{statement.evaluate()}catch(error: Throwable){thrown.set(error)}}
        try {
            junit.start();assertTrue(signalEntered.await(5,TimeUnit.SECONDS))
            val record=holder.get();val job=requireNotNull(record.coordinator)
            assertTrue(record.cleanupConfirmed&&record.releaseConfirmed);assertFalse(record.outcomeDelivered)
            assertFalse(job.isCompleted);assertSame(original,record.primary)
            assertTrue(PrivateClaimFixtureOwners.retainedRecords().any {it===record})
            junit.interrupt();assertTrue(guardInterrupted.await(5,TimeUnit.SECONDS))
            val body=requireNotNull(record.token.bodyThread);body.join(5000);assertFalse(body.isAlive)
            assertTrue(record.coordinatorWaitEntered.await(5,TimeUnit.SECONDS))
            junit.interrupt();assertTrue(guardInterruptedAgain.await(5,TimeUnit.SECONDS))
            assertTrue(junit.isAlive);assertFalse(job.isCompleted);assertFalse(record.coordinatorCompletionConfirmed)
            assertSame(original,record.token.failureBeforeInterruption);assertSame(original,record.primary)
            assertTrue(PrivateClaimFixtureOwners.retainedRecords().any {it===record})
            assertEquals(1,retires.get());assertEquals(1,releases.get());assertEquals(1,signalCalls.get())
            assertEquals(1L,signalExited.count)
            allowSignal.countDown();junit.join(5000)
            assertFalse(junit.isAlive);assertSame(original,thrown.get())
            assertEquals(0L,signalExited.count);assertTrue(job.isCompleted&&record.coordinatorCompletionConfirmed)
            assertFalse(PrivateClaimFixtureOwners.retainedRecords().any {it===record})
            assertTrue(original.suppressed.any {it is InterruptedException})
            assertEquals(1,retires.get());assertEquals(1,releases.get());assertEquals(1,signalCalls.get())
            runBlocking{withTimeout(5000){caller.get().join()}};assertSame(original,callerFailure.get())
        }finally{
            bodyBlocked.countDown();allowSignal.countDown();if(junit.isAlive)junit.join(5000)
            caller.get()?.let {runBlocking{withTimeout(5000){it.join()}}};callerScope.cancel()
        }
    }
    @Test fun alreadyDeliveredAndHandledFailureIsNotPromotedByLaterGuardInterruption() {
        val handled=CountDownLatch(1);val block=CountDownLatch(1);val token=AtomicReference<PrivateClaimFixtureToken>()
        val original=AssertionError("already delivered and handled");val thrown=AtomicReference<Throwable>()
        val statement=NativePrivateClaimFixtureOwnerRule().apply(object: Statement(){override fun evaluate() {
            val owner=PrivateClaimFixtureOwners.current();token.set(owner)
            runBlocking {
                val record=PrivateClaimFixtureRecord<Session,Lease,Root>(owner,Root(),"handled-old-failure")
                supervisorScope {
                    val result=runCatching {PrivateClaimFixtureLifecycle(record,Dispatchers.Unconfined,
                        create={Session()},restore={},awaitRestore={},acquire={Lease()},retire={_,_->},abandon={},release={it.frozen.set(false)}
                    ).run<String>{throw original}}
                    assertSame(original,result.exceptionOrNull())
                }
                assertTrue(record.outcomeDelivered&&record.cleanupConfirmed)
            }
            handled.countDown();block.await()
        }},Description.createTestDescription(javaClass,"handled-failure-before-later-interruption"))
        val junit=Thread {try{statement.evaluate()}catch(error: Throwable){thrown.set(error)}}
        try {
            junit.start();assertTrue(handled.await(5,TimeUnit.SECONDS));junit.interrupt();junit.join(5000)
            assertFalse(junit.isAlive);assertSame(token.get().firstInterruption,thrown.get())
            assertNull(token.get().failureBeforeInterruption);assertFalse(thrown.get()===original)
        }finally{block.countDown();if(junit.isAlive)junit.join(5000)}
    }
    @Test fun firstGuardInterruptionPrecedesLaterBodyFailureBeforeAnySessionExists() {
        val entered=CountDownLatch(1);val blocked=CountDownLatch(1);val token=AtomicReference<PrivateClaimFixtureToken>()
        val secondary=IllegalStateException("body observed cancellation later");val thrown=AtomicReference<Throwable>()
        val statement=NativePrivateClaimFixtureOwnerRule().apply(object: Statement(){override fun evaluate() {
            token.set(PrivateClaimFixtureOwners.current());entered.countDown()
            try{blocked.await()}catch(_: InterruptedException){throw secondary}
        }},Description.createTestDescription(javaClass,"first-interruption-without-session"))
        val junit=Thread {try{statement.evaluate()}catch(error: Throwable){thrown.set(error)}}
        try {
            junit.start();assertTrue(entered.await(5,TimeUnit.SECONDS));junit.interrupt();junit.join(5000)
            assertFalse(junit.isAlive);assertSame(token.get().firstInterruption,thrown.get())
            assertTrue(thrown.get().suppressed.any {it===secondary});assertTrue(token.get().records.isEmpty())
        }finally {blocked.countDown();if(junit.isAlive)junit.join(5000)}
    }
}
