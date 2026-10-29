package com.agenthub.android

/** Release facade: no acceptance storage probing, key handling, coroutine or default-path changes. */
internal object NativeNaturalLaunch {
    fun deferIfRequired(activity: MainActivity,restored: Boolean,resume: ()->Unit)=false
    fun rejectedIngress(activity: MainActivity) {}
    fun owns(activity: MainActivity)=false
    fun blocksIngress(activity: MainActivity,ready: Boolean)=!ready
    fun canRetain(activity: MainActivity,ready: Boolean)=ready
    fun created(activity: MainActivity) {}
    fun destroyed(activity: MainActivity) {}
}
