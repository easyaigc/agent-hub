package com.agenthub.android

import kotlinx.coroutines.CompletableDeferred
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Only the named SDK operations; platform callback identities belong to each handle. */
internal interface NativeNsdPlatform<S> {
    val canStopResolution: Boolean
    interface Request { fun start(); fun stop() }
    interface Discovery<S> {
        fun started(); fun stopped(); fun startFailed(code: Int); fun stopFailed(code: Int)
        fun found(service: S); fun lost(service: S)
    }
    interface Resolution<S> {
        fun resolved(service: S); fun failed(code: Int); fun stopped(); fun stopFailed(code: Int)
    }
    fun discovery(type: String,events: Discovery<S>): Request
    fun resolution(service: S,events: Resolution<S>): Request
    fun network(changed: ()->Unit): Request
}

/** Always enqueue (never invoke inline) on one serial queue. Timers use that queue.
 * Removing a local timer is not an SDK completion signal. */
internal interface NativeNsdQueue {
    fun post(action: ()->Unit)
    fun after(milliseconds: Long,action: ()->Unit): ()->Unit
}

/** One generation. Cancellation of an await never cancels its unparented completion.
 * All mutable SDK state is queue-confined; retirement fences projection immediately.
 * A thrown SDK call or stop-failure callback records uncertainty, never termination.
 */
internal class NativeNsdLifetime<S>(
    private val platform: NativeNsdPlatform<S>,private val queue: NativeNsdQueue,
    private val type: String,private val resolved: (S)->Unit,private val lost: (S)->Unit,
    private val networkChanged: ()->Unit,private val observe: (String,Int?)->Unit={_,_->}
) {
    private val startRequested=AtomicBoolean()
    private val retiring=AtomicBoolean()
    private val finished=CompletableDeferred<Unit>()
    val isRetiring get()=retiring.get()
    val isStopped get()=finished.isCompleted
    private var discovery: NativeNsdPlatform.Request?=null
    private var discoveryPending=false
    private var discoveryStopSent=false
    private var network: NativeNsdPlatform.Request?=null
    private var networkPending=false
    private var networkStopSent=false
    private val pending=ArrayDeque<S>()
    private var resolving: Resolution?=null
    private inner class Resolution {
        lateinit var request: NativeNsdPlatform.Request
        var terminal=false
        var expired=false
        var stopSent=false
        var cancelTimer: (()->Unit)?=null
    }
    private fun event(name: String,code: Int?=null) {observe(name,code)}
    fun start() {if(startRequested.compareAndSet(false,true))queue.post {startOnQueue()}}
    private fun startOnQueue() {
        if(retiring.get()){finishIfStopped();return}
        discoveryPending=true
        val listener=object: NativeNsdPlatform.Discovery<S> {
            override fun started()=queue.post {if(discoveryPending)event("discovery-started")}
            override fun stopped()=queue.post {if(discoveryPending){discoveryPending=false;event("discovery-stopped");finishIfStopped()}}
            override fun startFailed(code: Int)=queue.post {if(discoveryPending){discoveryPending=false;event("discovery-start-failed",code);finishIfStopped()}}
            override fun stopFailed(code: Int)=queue.post {if(discoveryPending)event("discovery-stop-failed",code)}
            override fun found(service: S)=queue.post {if(!retiring.get()&&discoveryPending&&pending.size<32){pending.add(service);pump()}}
            override fun lost(service: S)=queue.post {if(!retiring.get()&&discoveryPending)this@NativeNsdLifetime.lost(service)}
        }
        // Handle creation precedes invocation. Even an invocation throwing after dispatch
        // remains pending until a real SDK terminal callback proves what happened.
        try {discovery=platform.discovery(type,listener);discovery!!.start()}
        catch(_: Exception){event("discovery-start-threw")}
        if(retiring.get())return // Stop may arrive from another thread during SDK start.
        networkPending=true
        try {
            network=platform.network {queue.post {if(!retiring.get())networkChanged()}}
            network!!.start()
        }catch(_: Exception){event("network-register-threw")}
    }
    fun stop() {if(retiring.compareAndSet(false,true))queue.post {stopOnQueue()}}
    suspend fun awaitStopped() {stop();finished.await()}
    private fun stopOnQueue() {
        pending.clear() // These entries never started an SDK operation.
        if(discoveryPending&&!discoveryStopSent) {
            discoveryStopSent=true
            try {discovery?.stop()?:error("No discovery handle");event("discovery-stop-requested")}
            catch(_: Exception){event("discovery-stop-threw")}
        }
        resolving?.let {it.expired=true;it.cancelTimer?.invoke();it.cancelTimer=null;stopResolution(it)}
        if(networkPending&&!networkStopSent) {
            networkStopSent=true
            try {network?.stop()?:error("No network handle");networkPending=false;event("network-unregistered")}
            catch(_: Exception){event("network-unregister-threw")}
        }
        finishIfStopped()
    }
    private fun stopResolution(slot: Resolution) {
        if(slot.terminal||slot.stopSent||!platform.canStopResolution)return
        slot.stopSent=true
        try {slot.request.stop();event("resolution-stop-requested")}
        catch(_: Exception){event("resolution-stop-threw")}
    }
    private fun pump() {
        if(retiring.get()||resolving!=null||pending.isEmpty())return
        val service=pending.removeFirst();val slot=Resolution();resolving=slot
        fun terminal(name: String,code: Int?=null,value: S?=null) {
            if(slot.terminal)return
            slot.terminal=true;slot.cancelTimer?.invoke();slot.cancelTimer=null
            event(name,code)
            if(resolving===slot) {
                resolving=null
                if(value!=null&&!retiring.get()&&!slot.expired)resolved(value)
                pump()
            }
            finishIfStopped()
        }
        val listener=object: NativeNsdPlatform.Resolution<S> {
            override fun resolved(service: S)=queue.post {terminal("resolution-resolved",value=service)}
            override fun failed(code: Int)=queue.post {terminal("resolution-failed",code)}
            override fun stopped()=queue.post {terminal("resolution-stopped")}
            override fun stopFailed(code: Int)=queue.post {if(!slot.terminal)event("resolution-stop-failed",code)}
        }
        try {slot.request=platform.resolution(service,listener);slot.request.start();event("resolution-start-requested")}
        catch(_: Exception){event("resolution-start-threw")}
        if(!slot.terminal)slot.cancelTimer=queue.after(6_000) {
            if(resolving===slot&&!slot.terminal) {slot.expired=true;event("resolution-expired");stopResolution(slot)}
        }
    }
    private fun finishIfStopped() {
        if(retiring.get()&&!discoveryPending&&!networkPending&&resolving==null)finished.complete(Unit)
    }
}
