package com.agenthub.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

/** Platform path signals only. The session owns read recovery and immutable commands. */
internal class NativeReadNetworkMonitor(context: Context,private val changed: (String,Boolean)->Unit) {
    private val connectivity=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private var generation=0L
    private var callback: ConnectivityManager.NetworkCallback?=null
    internal val observing get()=callback!=null
    private fun signature(): String {
        val paths=connectivity.allNetworks.sortedBy { it.networkHandle }.take(32).map { network ->
            val caps=connectivity.getNetworkCapabilities(network)
            val links=connectivity.getLinkProperties(network)
            listOf(network.networkHandle.toString(),(0..4).filter { caps?.hasTransport(it)==true }.joinToString(","),
                (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true).toString(),
                (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true).toString(),
                (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)==true).toString(),links?.interfaceName.orEmpty(),
                links?.linkAddresses?.map { it.toString() }?.sorted()?.take(32)?.joinToString(",").orEmpty(),
                links?.routes?.map { it.toString() }?.sorted()?.take(64)?.joinToString(",").orEmpty(),
                links?.dnsServers?.map { it.hostAddress.orEmpty() }?.sorted()?.take(16)?.joinToString(",").orEmpty()).joinToString(";")
        }
        val value=connectivity.activeNetwork?.networkHandle.toString()+":"+connectivity.isActiveNetworkMetered+":"+paths.joinToString("|")
        // Addresses and interface details remain transient, never logged/persisted.
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun start() {
        stop();val epoch=generation
        runCatching { signature() }.getOrNull()?.let { changed(it,true) }
        fun signal() { main.post { if(generation==epoch&&callback!=null)runCatching { signature() }.getOrNull()?.let { changed(it,false) } } }
        val listener=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)=signal()
            override fun onLost(network: Network)=signal()
            override fun onLinkPropertiesChanged(network: Network,links: LinkProperties)=signal()
            override fun onCapabilitiesChanged(network: Network,caps: NetworkCapabilities)=signal()
        }
        callback=listener
        // Observe trusted app-visible VPN paths too; request no new permissions or network binding.
        try { connectivity.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),listener) }
        catch(_: Exception) { callback=null }
    }
    fun stop() {
        generation++
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } };callback=null
    }
}
