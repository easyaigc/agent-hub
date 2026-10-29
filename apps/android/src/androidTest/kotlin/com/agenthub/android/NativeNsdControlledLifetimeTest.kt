package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.ArrayDeque

/** Controlled platform outcomes, runnable unchanged on JVM or Android. Never an SDK pass. */
class NativeNsdControlledLifetimeTest {
    private class Queue: NativeNsdQueue {
        val actions=ArrayDeque<()->Unit>()
        val timers=mutableListOf<Pair<()->Unit,BooleanArray>>()
        override fun post(action: ()->Unit){actions.add(action)}
        override fun after(milliseconds: Long,action: ()->Unit): ()->Unit {
            assertEquals(6000L,milliseconds);val cancelled=booleanArrayOf(false);timers.add(action to cancelled);return {cancelled[0]=true}
        }
        fun drain(){var count=0;while(actions.isNotEmpty()){check(++count<1000);actions.removeFirst()()}}
        fun expire(){val copy=timers.toList();timers.clear();copy.filter {!it.second[0]}.forEach {post(it.first)};drain()}
    }
    private class Platform(override val canStopResolution: Boolean=true): NativeNsdPlatform<String> {
        class Request: NativeNsdPlatform.Request {
            var starts=0;var stops=0;var startThrows=false;var stopThrows=false
            var onStart: ()->Unit={}
            override fun start(){starts++;onStart();if(startThrows)error("controlled start failure")}
            override fun stop(){stops++;if(stopThrows)error("controlled stop failure")}
        }
        val browse=Request();val net=Request();val requests=mutableListOf<Request>()
        var resolutionStartThrows=false
        lateinit var discovery: NativeNsdPlatform.Discovery<String>
        lateinit var changed: ()->Unit
        val resolutions=mutableListOf<NativeNsdPlatform.Resolution<String>>()
        override fun discovery(type: String,events: NativeNsdPlatform.Discovery<String>): NativeNsdPlatform.Request {discovery=events;return browse}
        override fun resolution(service: String,events: NativeNsdPlatform.Resolution<String>): NativeNsdPlatform.Request {resolutions.add(events);return Request().also {it.startThrows=resolutionStartThrows;requests.add(it)}}
        override fun network(changed: ()->Unit): NativeNsdPlatform.Request {this.changed=changed;return net}
    }
    private class Fixture(legacy: Boolean=false) {
        val queue=Queue();val platform=Platform(!legacy);val values=mutableListOf<String>();val events=mutableListOf<String>();var networkChanges=0
        val sdk=NativeNsdLifetime(platform,queue,"_controlled._tcp.",values::add,{values.add("lost:$it")},{networkChanges++},{name,_->events.add(name)})
        fun start(){sdk.start();queue.drain()}
        fun found(name: String="peer"){platform.discovery.found(name);queue.drain()}
        fun stop(){sdk.stop();queue.drain()}
        fun closed(){platform.discovery.stopped();queue.drain();assertTrue(sdk.isStopped)}
    }
    @Test fun stopRequestAndCancelledCoroutineAreNotDiscoveryCompletion()=runBlocking {
        val f=Fixture();f.start();f.stop();assertFalse(f.sdk.isStopped);assertEquals(1,f.platform.browse.stops);assertEquals(1,f.platform.net.stops)
        val waiting=async {f.sdk.awaitStopped()};yield();waiting.cancelAndJoin();assertFalse(f.sdk.isStopped)
        f.sdk.stop();f.queue.drain();assertEquals(1,f.platform.browse.stops);f.closed()
    }
    @Test fun actualStartFailureIsTerminalButStopFailureIsNot() {
        val start=Fixture();start.start();start.platform.discovery.startFailed(1);start.queue.drain();start.stop();assertTrue(start.sdk.isStopped);assertEquals(0,start.platform.browse.stops)
        val stop=Fixture();stop.start();stop.stop();stop.platform.discovery.stopFailed(2);stop.queue.drain();assertFalse(stop.sdk.isStopped);stop.closed()
    }
    @Test fun discoveryExceptionsDoNotManufactureCallbacks() {
        val f=Fixture();f.platform.browse.startThrows=true;f.platform.browse.stopThrows=true;f.start();f.stop();assertFalse(f.sdk.isStopped)
        assertTrue(f.events.containsAll(listOf("discovery-start-threw","discovery-stop-threw")));f.closed()
    }
    @Test fun legacyResolutionNeedsItsOwnNaturalTerminalAfterDiscoveryStops() {
        val f=Fixture(true);f.start();f.found();f.stop();f.platform.discovery.stopped();f.queue.drain();assertFalse(f.sdk.isStopped);assertEquals(0,f.platform.requests.single().stops)
        f.platform.resolutions.single().failed(3);f.queue.drain();assertTrue(f.sdk.isStopped);assertTrue(f.values.isEmpty())
    }
    @Test fun legacyExpirySuppressesProjectionButDoesNotReleaseSdkOwnership() {
        val f=Fixture(true);f.start();f.found();f.queue.expire();assertEquals(0,f.platform.requests.single().stops)
        f.stop();f.platform.discovery.stopped();f.queue.drain();assertFalse(f.sdk.isStopped)
        f.platform.resolutions.single().resolved("late");f.queue.drain();assertTrue(f.sdk.isStopped);assertTrue(f.values.isEmpty())
    }
    @Test fun modernStopNeedsResolutionCallbackAndStopFailureStaysPending() {
        val f=Fixture();f.start();f.found();f.stop();f.platform.discovery.stopped();f.queue.drain();assertEquals(1,f.platform.requests.single().stops);assertFalse(f.sdk.isStopped)
        f.platform.resolutions.single().stopFailed(4);f.queue.drain();assertFalse(f.sdk.isStopped)
        f.platform.resolutions.single().stopped();f.queue.drain();assertTrue(f.sdk.isStopped)
    }
    @Test fun resolutionStopExceptionRemainsPendingUntilObservedTerminal() {
        val f=Fixture();f.start();f.found();f.platform.requests.single().stopThrows=true;f.stop();f.platform.discovery.stopped();f.queue.drain();assertFalse(f.sdk.isStopped)
        f.platform.resolutions.single().failed(5);f.queue.drain();assertTrue(f.sdk.isStopped)
    }
    @Test fun resolutionStartExceptionAlsoNeedsAnObservedTerminal() {
        val f=Fixture();f.platform.resolutionStartThrows=true;f.start();f.found();f.stop();f.platform.discovery.stopped();f.queue.drain();assertFalse(f.sdk.isStopped)
        assertTrue(f.events.contains("resolution-start-threw"));f.platform.resolutions.single().failed(8);f.queue.drain();assertTrue(f.sdk.isStopped)
    }
    @Test fun unknownNetworkRegistrationCanCloseOnlyAfterSynchronousUnregisterReturns() {
        val f=Fixture();f.platform.net.startThrows=true;f.start();assertTrue(f.events.contains("network-register-threw"));f.stop();f.closed()
        assertEquals(1,f.platform.net.stops);assertTrue(f.events.contains("network-unregistered"))
    }
    @Test fun activeNetworkAndLostCallbacksRemainLiveBeforeRetirement() {
        val f=Fixture();f.start();f.platform.changed();f.platform.discovery.lost("peer");f.queue.drain();assertEquals(1,f.networkChanges);assertEquals(listOf("lost:peer"),f.values)
        assertFalse(f.sdk.isStopped);f.stop();f.closed()
    }
    @Test fun lateOldResolutionCannotClearTheNextResolution() {
        val f=Fixture();f.start();f.found("first");f.found("second");val first=f.platform.resolutions.single()
        first.resolved("first");f.queue.drain();assertEquals(listOf("first"),f.values);assertEquals(2,f.platform.resolutions.size)
        f.stop();f.platform.discovery.stopped();first.stopped();first.failed(6);f.queue.drain();assertFalse(f.sdk.isStopped)
        f.platform.resolutions[1].stopped();f.queue.drain();assertTrue(f.sdk.isStopped);assertEquals(listOf("first"),f.values)
    }
    @Test fun queuedNetworkAndDiscoveryEventsCannotProjectAfterRetirement() {
        val f=Fixture();f.start();f.platform.changed();f.platform.discovery.found("queued");f.platform.discovery.lost("queued");f.sdk.stop();f.queue.drain()
        assertEquals(0,f.networkChanges);assertTrue(f.values.isEmpty());assertTrue(f.platform.resolutions.isEmpty());f.closed()
    }
    @Test fun synchronousNetworkUnregisterFailureCannotBeCalledClosed() {
        val f=Fixture();f.start();f.platform.net.stopThrows=true;f.stop();f.platform.discovery.stopped();f.queue.drain();assertFalse(f.sdk.isStopped)
        assertTrue(f.events.contains("network-unregister-threw"));assertFalse(f.events.contains("network-unregistered"))
    }
    @Test fun stopBeforeQueuedStartHasNoSdkOperationToWaitFor() {
        val f=Fixture();f.sdk.start();f.sdk.stop();f.queue.drain();assertTrue(f.sdk.isStopped);assertEquals(0,f.platform.browse.starts);assertEquals(0,f.platform.net.starts)
    }
    @Test fun retirementDuringDiscoveryInvocationDoesNotStartAnotherSdkRegistration() {
        val f=Fixture();f.platform.browse.onStart={f.sdk.stop()};f.start();assertEquals(0,f.platform.net.starts)
        assertEquals(1,f.platform.browse.stops);assertFalse(f.sdk.isStopped);f.closed()
    }
    @Test fun expiryStopsOnlyItsCapturedResolverAndAllowsNextOnlyAfterCallback() {
        val f=Fixture();f.start();f.found("one");f.found("two");f.queue.expire();assertEquals(1,f.platform.requests.single().stops);assertEquals(1,f.platform.resolutions.size)
        f.platform.resolutions[0].stopped();f.queue.drain();assertEquals(2,f.platform.resolutions.size);f.platform.resolutions[0].resolved("old");f.queue.drain();assertTrue(f.values.isEmpty())
        f.stop();f.platform.discovery.stopped();f.platform.resolutions[1].stopped();f.queue.drain();assertTrue(f.sdk.isStopped)
    }
    @Test fun sharedOwnerRemainsStoppingAfterCancelledWaitAndFailureCallback()=runBlocking {
        val registry=NativeEasyWorkMigrationOwners();val f=Fixture();f.start();registry.register({f.sdk.stop()},{f.sdk.awaitStopped()})
        registry.seal();val waiting=async {registry.stopAndJoin()};yield();f.queue.drain();f.platform.discovery.stopFailed(7);f.queue.drain();waiting.cancelAndJoin()
        assertTrue(registry.stopping);assertFalse(f.sdk.isStopped);assertTrue(runCatching {registry.reopen()}.isFailure)
        f.closed();withTimeout(2000){registry.stopAndJoin()};registry.reopen();assertFalse(registry.stopping)
    }
    @Test fun retiredGenerationCompletionCannotReleaseANewerSharedOwner()=runBlocking {
        val registry=NativeEasyWorkMigrationOwners();val old=Fixture();old.start();val oldOwner=registry.register({old.sdk.stop()},{old.sdk.awaitStopped()});oldOwner.retire();old.queue.drain()
        val next=Fixture();next.start();registry.register({next.sdk.stop()},{next.sdk.awaitStopped()});registry.seal();val waiting=async {registry.stopAndJoin()};yield();next.queue.drain()
        old.platform.discovery.found("old");old.closed();assertTrue(registry.stopping);assertTrue(old.values.isEmpty());assertFalse(next.sdk.isStopped)
        next.closed();withTimeout(2000){waiting.await()};registry.reopen()
    }
    @Test fun sdkCompletionAndCoroutineExitAreIndependentRequirements()=runBlocking {
        val entered=CompletableDeferred<Unit>();val exit=CompletableDeferred<Unit>()
        val work=launch(Dispatchers.Default){withContext(NonCancellable){entered.complete(Unit);exit.await()}};entered.await()
        val f=Fixture();f.start();val registry=NativeEasyWorkMigrationOwners();registry.register({work.cancel();f.sdk.stop()},{work.join();f.sdk.awaitStopped()})
        val waiting=async {registry.stopAndJoin()};yield();f.queue.drain();f.closed();assertTrue(registry.stopping);assertFalse(work.isCompleted);assertFalse(waiting.isCompleted)
        exit.complete(Unit);withTimeout(2000){waiting.await()};registry.reopen();assertFalse(registry.stopping)
    }
}
