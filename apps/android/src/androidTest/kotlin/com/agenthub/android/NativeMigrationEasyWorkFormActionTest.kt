package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production form bridge/registry and actual authorization coordinator; JVM-compatible auxiliary.
 * This adapter holds a real install transaction before storage acquisition. Native tests use Keystore.
 */
class NativeMigrationEasyWorkFormActionTest {
    private fun action()=EasyWorkConnectionAuthority().form {EasyWorkConnectionOrigin("target","navigation","revision","digest")}.begin(EasyWorkConnectionInput("code","synthetic","https://relay.invalid","0123456789ABCDEF"))
    @Test(timeout=15_000) fun leavingCallerDoesNotReleaseTheActualAuthorizationInstallOrCancelledMigrationWait()=runBlocking {
        val io=NativeMigrationIoWork();val owners=NativeEasyWorkMigrationOwners();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val caller=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CountDownLatch(1);val release=CountDownLatch(1);val written=AtomicInteger();val values=ConcurrentHashMap<String,String>()
        val ref="easywork-device-grant:synthetic-form";val grant=JSONObject().put("grantId","synthetic-form-grant").put("binding",JSONObject().put("bindingId","synthetic-binding"))
        val coordinator=EasyWorkAuthorizationCoordinator(object: EasyWorkAuthorizationRecords {
            override val root="synthetic-form-owned-root"
            override fun <T> transaction(block: ()->T): T {entered.countDown();while(true)try{release.await();break}catch(_: InterruptedException){};return synchronized(values){block()}}
            override fun read(key: String)=values[key]
            override fun write(key: String,value: String){values[key]=value;if(key==ref)written.incrementAndGet()}
            override fun remove(key: String){values.remove(key)}
        })
        val lease=action();val running=caller.launch {NativeEasyWorkFormAction.run(scope,io+owners,lease){withContext(Dispatchers.IO){coordinator.install(ref,"https://relay.invalid",grant)}}}
        try {
            withContext(Dispatchers.IO){check(entered.await(5,TimeUnit.SECONDS))};lease.form.close();caller.cancel();running.join()
            assertEquals(0,written.get());assertFalse(lease.currentForUi());assertTrue(scope.coroutineContext[Job]!!.children.any())
            val first=async(Dispatchers.Default){owners.stopAndJoin();io.awaitIdle()};assertNull(withTimeoutOrNull(100){first.await();true});first.cancelAndJoin()
            assertTrue(owners.stopping);val second=async(Dispatchers.Default){owners.stopAndJoin();io.awaitIdle()}
            assertNull(withTimeoutOrNull(100){second.await();true});assertEquals(0,written.get())
            release.countDown();withTimeout(5_000){second.await()};assertEquals(1,written.get())
            assertEquals("synthetic-form-grant",JSONObject(values.getValue(ref)).getJSONObject("grant").getString("grantId"))
            owners.reopen();assertFalse(owners.stopping)
        }finally {release.countDown();caller.cancel();scope.cancel()}
    }
    @Test(timeout=5_000) fun sealedOwnerRejectsTheWholeFormBeforeAnyAuthorizationWorkStarts()=runBlocking {
        val io=NativeMigrationIoWork();val owners=NativeEasyWorkMigrationOwners();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var started=0
        try {owners.seal();assertTrue(runCatching {NativeEasyWorkFormAction.run(scope,io+owners,action()){started++}}.isFailure);assertEquals(0,started);owners.stopAndJoin();io.awaitIdle()}
        finally {scope.cancel()}
    }
    @Test(timeout=5_000) fun completedActionKeepsTheOriginalLeaseContextAndDoesNotCancelTheCallerOrSession()=runBlocking {
        val io=NativeMigrationIoWork();val owners=NativeEasyWorkMigrationOwners();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val lease=action();val caller=currentCoroutineContext()[Job]
        try {
            assertEquals("original result",NativeEasyWorkFormAction.run(scope,io+owners,lease){assertSame(lease,currentCoroutineContext()[EasyWorkConnectionActionLease]);assertSame(io,currentCoroutineContext()[NativeMigrationIoWork]);"original result"})
            assertTrue(caller!!.isActive);assertTrue(scope.coroutineContext[Job]!!.isActive);owners.stopAndJoin();io.awaitIdle()
        }finally {scope.cancel();lease.releaseBusy()}
    }
}
