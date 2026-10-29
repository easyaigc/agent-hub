package com.agenthub.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class AndroidNsdQueue(private val main: Handler=Handler(Looper.getMainLooper())): NativeNsdQueue {
    override fun post(action: ()->Unit) {check(main.post {action()})}
    override fun after(milliseconds: Long,action: ()->Unit): ()->Unit {
        val work=Runnable {action()};check(main.postDelayed(work,milliseconds));return {main.removeCallbacks(work)}
    }
}

/** Each handle captures exactly one SDK listener. No current/global listener lookup. */
internal class AndroidNsdPlatform(context: Context): NativeNsdPlatform<NsdServiceInfo> {
    private val nsd=requireNotNull(context.applicationContext.getSystemService(NsdManager::class.java))
    private val connectivity=requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java))
    override val canStopResolution get()=Build.VERSION.SDK_INT>=34
    override fun discovery(type: String,events: NativeNsdPlatform.Discovery<NsdServiceInfo>): NativeNsdPlatform.Request {
        val listener=object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String)=events.started()
            override fun onDiscoveryStopped(type: String)=events.stopped()
            override fun onStartDiscoveryFailed(type: String,code: Int)=events.startFailed(code)
            override fun onStopDiscoveryFailed(type: String,code: Int)=events.stopFailed(code)
            override fun onServiceFound(info: NsdServiceInfo)=events.found(info)
            override fun onServiceLost(info: NsdServiceInfo)=events.lost(info)
        }
        return object: NativeNsdPlatform.Request {
            override fun start()=nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
            override fun stop()=nsd.stopServiceDiscovery(listener)
        }
    }
    @Suppress("DEPRECATION") override fun resolution(service: NsdServiceInfo,events: NativeNsdPlatform.Resolution<NsdServiceInfo>): NativeNsdPlatform.Request {
        val listener=object: NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo)=events.resolved(info)
            override fun onResolveFailed(info: NsdServiceInfo,code: Int)=events.failed(code)
            override fun onResolutionStopped(info: NsdServiceInfo)=events.stopped()
            override fun onStopResolutionFailed(info: NsdServiceInfo,code: Int)=events.stopFailed(code)
        }
        return object: NativeNsdPlatform.Request {
            override fun start()=nsd.resolveService(service,listener)
            override fun stop() {check(canStopResolution);nsd.stopServiceResolution(listener)}
        }
    }
    override fun network(changed: ()->Unit): NativeNsdPlatform.Request {
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)=changed()
            override fun onLost(network: Network)=changed()
            override fun onLinkPropertiesChanged(network: Network,properties: android.net.LinkProperties)=changed()
        }
        return object: NativeNsdPlatform.Request {
            override fun start()=connectivity.registerNetworkCallback(NetworkRequest.Builder().build(),callback)
            override fun stop()=connectivity.unregisterNetworkCallback(callback)
        }
    }
}
