package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real production policy/lifetime/key-choice with fake resources, not Android/Keystore evidence. */
class NativeMigrationColdPortPolicyTest {
    private val operation="00000000-0000-4000-8000-000000000001"
    private val generation="00000000-0000-4000-8000-000000000002"
    private fun refuses(block: ()->Unit){assertTrue(runCatching(block).isFailure)}
    @Test fun missingExistingKeyNeverInvokesCreation() {
        val creates=AtomicInteger()
        refuses {NativeMigrationKeyChoice.resolve<Any>(null,true,create={creates.incrementAndGet();Any()})}
        assertEquals(0,creates.get())
    }
    @Test fun existingKeyIsTheExactObjectAndDoesNotCreate() {
        val original=Any();val creates=AtomicInteger();val captured=AtomicReference<Any>()
        assertSame(original,NativeMigrationKeyChoice.resolve(original,true,capture={captured.set(it)},create={creates.incrementAndGet();Any()}))
        assertSame(original,captured.get());assertEquals(0,creates.get())
    }
    @Test fun lostAuthorityBeforeCreationCreatesNothing() {
        val creates=AtomicInteger()
        refuses {NativeMigrationKeyChoice.resolve<Any>(null,false,validate={error("owner invalid")},create={creates.incrementAndGet();Any()})}
        assertEquals(0,creates.get())
    }
    @Test fun exactGeneratedKeyIsCapturedBeforeFinalAuthorityFailure() {
        val key=Any();val captured=AtomicReference<Any>();val checks=AtomicInteger();val creates=AtomicInteger()
        refuses {NativeMigrationKeyChoice.resolve<Any>(null,false,validate={if(checks.incrementAndGet()==2)error("owner invalid after create")},capture={captured.set(it)},create={creates.incrementAndGet();key})}
        assertSame(key,captured.get());assertEquals(1,creates.get());assertEquals(2,checks.get())
    }
    private inner class Flow(old: String?=null) {
        val root=Any();val owner=Any();val lease=Any();val policy=NativeMigrationExistingPolicy(root,old)
        var raw=true;var oldLive=true;var newLive=true;var unchanged=true
        init{policy.bindSession(root,root,owner,{check(oldLive)})}
        fun authorize(){policy.authorize(root,owner,lease,operation,{check(raw&&oldLive)},{check(oldLive)})}
        fun publish(){authorize();policy.beforeGeneration(root,generation);policy.publication(root,generation);policy.confirmPublication(root,generation)}
        fun ticket()=policy.prepareRestore(root,lease,operation,generation)
        fun bind(r: NativeMigrationExistingPolicy.Restore,newRoot: Any=Any(),session: Any=Any()): Pair<Any,Any> {
            policy.attachRoot(r,newRoot);policy.bindSession(r,newRoot,session,{check(newLive)},{check(newLive&&unchanged)})
            return newRoot to session
        }
        fun all(r: NativeMigrationExistingPolicy.Restore){NativeMigrationExistingPolicy.paths.forEach {policy.requireNewChild(root,r,generation,it);policy.childPrepared(r,it)}}
    }
    @Test fun generationRequiresExactActualStorageOwnerAndLease() {
        val f=Flow();refuses {f.policy.beforeGeneration(f.root,generation)}
        refuses {f.policy.authorize(Any(),f.owner,f.lease,operation,{})}
        refuses {f.policy.authorize(f.root,Any(),f.lease,operation,{})}
        f.authorize();refuses {f.policy.authorize(f.root,f.owner,Any(),operation,{})}
        refuses {f.policy.beforeGeneration(Any(),generation)}
        f.policy.beforeGeneration(f.root,generation);f.policy.publication(f.root,generation)
    }
    @Test fun invalidatedOwnerCannotCreateOrPublish() {
        val f=Flow();f.authorize();f.raw=false;refuses {f.policy.beforeGeneration(f.root,generation)}
        f.raw=true;f.policy.beforeGeneration(f.root,generation);f.raw=false;refuses {f.policy.publication(f.root,generation)}
    }
    @Test fun oldGenerationOrSecondPublicationCannotBorrowTheGrant() {
        val old="00000000-0000-4000-8000-000000000003";val f=Flow(old);f.authorize()
        refuses {f.policy.beforeGeneration(f.root,old)};f.policy.beforeGeneration(f.root,generation)
        refuses {f.policy.beforeGeneration(f.root,"00000000-0000-4000-8000-000000000004")}
        refuses {f.policy.publication(f.root,old)}
    }
    @Test fun onlyNamedChildrenInTheExactNewGenerationAreAuthorized() {
        val f=Flow();f.authorize();f.policy.beforeGeneration(f.root,generation)
        f.policy.requireNewChild(f.root,f.root,generation,"external-inbox")
        f.policy.requireNewChild(f.root,f.root,generation,"camera-results/outputs")
        refuses {f.policy.requireNewChild(f.root,f.root,generation,"migration-candidates")}
        refuses {f.policy.requireNewChild(f.root,f.root,generation,"arbitrary")}
        refuses {f.policy.requireNewChild(f.root,f.root,operation,"external-inbox")}
        f.raw=false;refuses {f.policy.requireNewChild(f.root,f.root,generation,"external-inbox")}
    }
    @Test fun releasedRawLeaseThenFullPreparationThenSessionCommitIsTheOnlyNormalWritePath() {
        val f=Flow();f.publish();val r=f.ticket()
        refuses {f.policy.attachRoot(r,Any())};refuses {f.policy.requireWrite(f.root,null,"old draft")}
        f.raw=false;f.policy.released(r,f.lease);val(root,session)=f.bind(r)
        f.policy.requireNewChild(f.root,r,generation,"external-inbox");f.policy.childPrepared(r,"external-inbox")
        refuses {f.policy.complete(r,root,session,f.lease)};refuses {f.policy.requireWrite(r,null,"draft")}
        f.all(r);f.policy.verifyPrepared(r,root,f.lease,generation);f.policy.complete(r,root,session,f.lease)
        f.policy.requireWrite(r,null,"draft");refuses {f.policy.requireWrite(f.root,"external-inbox","stale child")}
    }
    @Test fun cancellationAndUncommittedPublicationCannotPrepareNormalRestore() {
        val f=Flow();f.authorize();refuses {f.ticket()};f.policy.beforeGeneration(f.root,generation)
        refuses {f.ticket()};f.policy.revoke(f.owner);refuses {f.policy.confirmPublication(f.root,generation)}
        refuses {f.ticket()}
    }
    @Test fun restoredGrantCannotChangeLeaseOperationRootGenerationOrSession() {
        val f=Flow();f.publish()
        refuses {f.policy.prepareRestore(f.root,Any(),operation,generation)}
        refuses {f.policy.prepareRestore(f.root,f.lease,generation,generation)}
        refuses {f.policy.prepareRestore(f.root,f.lease,operation,operation)}
        val r=f.ticket();refuses {f.policy.released(r,Any())};f.raw=false;f.policy.released(r,f.lease)
        val root=Any();f.policy.attachRoot(r,root)
        refuses {f.policy.bindSession(r,root,f.owner,{})}
        val session=Any();f.policy.bindSession(r,root,session,{})
        refuses {f.policy.bindSession(r,root,Any(),{})};f.all(r)
        refuses {f.policy.verifyPrepared(r,Any(),f.lease,generation)}
        refuses {f.policy.complete(r,root,Any(),f.lease)}
    }
    @Test fun newRequestOnOriginalSessionRevokesPendingChildAndNormalWriteAuthority() {
        val f=Flow();f.publish();val r=f.ticket();f.policy.released(r,f.lease);val(root,session)=f.bind(r)
        f.all(r);f.policy.invalidate(f.owner)
        refuses {f.policy.requireNewChild(f.root,r,generation,"external-inbox")}
        refuses {f.policy.complete(r,root,session,f.lease)};refuses {f.policy.requireWrite(r,null,"draft")}
    }
    @Test fun freshSessionChangeOrFailureCannotCompleteOrWriteWithCapturedChild() {
        val f=Flow();f.publish();val r=f.ticket();f.policy.released(r,f.lease);val(root,session)=f.bind(r);f.all(r)
        f.unchanged=false;refuses {f.policy.verifyPrepared(r,root,f.lease,generation)}
        refuses {f.policy.requireNewChild(f.root,r,generation,"external-inbox")}
        f.policy.revoke(session);refuses {f.policy.complete(r,root,session,f.lease)}
        refuses {f.policy.requireWrite(r,"external-inbox","captured child")}
    }
    @Test fun completedFreshSessionAllowsNormalEditsButNeverRegrantsOldSessionOrTicket() {
        val f=Flow();f.publish();val r=f.ticket();f.policy.released(r,f.lease);val(root,session)=f.bind(r);f.all(r)
        f.policy.complete(r,root,session,f.lease);f.unchanged=false
        f.policy.invalidate(f.owner);f.policy.revoke(f.owner);f.policy.requireWrite(r,"external-inbox","normal edit")
        f.policy.requireNewChild(f.root,r,generation,"migration-candidates")
        refuses {f.policy.attachRoot(r,Any())};refuses {f.policy.authorize(f.root,f.owner,Any(),operation,{})}
        refuses {f.policy.requireWrite(f.root,null,"old Session")}
        f.policy.revoke(session);refuses {f.policy.requireWrite(r,"external-inbox","captured child")}
    }
    @Test fun originalReadFailureSurvivesNormalClose() {
        val original=AssertionError("read first");val resource=Any();val closes=AtomicInteger()
        val error=runCatching {NativeMigrationReadLifetime().use({resource},{throw original},{assertSame(resource,it);closes.incrementAndGet();Unit})}.exceptionOrNull()
        assertSame(original,error);assertEquals(1,closes.get())
    }
    @Test fun partialAcquisitionClosesOnlyActualObjectsOnce() {
        val close=AtomicInteger();val lifetime=NativeMigrationReadLifetime();val object1=Any();val first=AssertionError("next open failed")
        lifetime.own({object1},{assertSame(object1,it);close.incrementAndGet();Unit})
        refuses {lifetime.own<Any>({throw first},{close.incrementAndGet();Unit})}
        lifetime.closeOwned(first);lifetime.closeOwned();assertEquals(1,close.get())
    }
    @Test fun unknownCloseKeepsExactResourceFirstErrorAndLiveContextAcrossInterrupts() {
        val resource=Any();val original=AssertionError("read failure first");val close=IllegalStateException("close unconfirmed")
        val started=CountDownLatch(1);val holdEntered=CountDownLatch(1);val observed=AtomicReference<NativeMigrationReadLifetime.Failure>()
        val closes=AtomicInteger();val escaped=AtomicReference<Throwable>()
        // Fake-only daemon context dies with this JVM; the Native caller is its actual guarded body/IO.
        val worker=Thread {
            try{started.countDown();NativeMigrationReadLifetime(onHold={observed.set(it);holdEntered.countDown()}).use({resource},{throw original},{closes.incrementAndGet();throw close})}
            catch(error: Throwable){escaped.set(error)}
        }.apply {isDaemon=true}
        worker.start();assertTrue(started.await(5,TimeUnit.SECONDS))
        assertTrue(holdEntered.await(5,TimeUnit.SECONDS));val held=observed.get()
        assertTrue(held.waitEntered.await(5,TimeUnit.SECONDS));assertSame(original,held.primary)
        assertTrue(original.suppressed.any {it===close})
        worker.interrupt();assertTrue(held.firstInterrupted.await(5,TimeUnit.SECONDS))
        worker.interrupt();assertTrue(held.twiceInterrupted.await(5,TimeUnit.SECONDS))
        assertTrue(worker.isAlive);assertNull(escaped.get());assertEquals(1,closes.get());assertSame(worker,held.owner)
    }
}
