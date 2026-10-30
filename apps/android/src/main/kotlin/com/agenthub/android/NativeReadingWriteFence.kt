package com.agenthub.android

/** Session restore/reset ownership only. Unread decisions remain in the shared ReadingPolicy/ledger.
 * The guard is checked after obtaining the storage lock. Only in-memory mutation holds this monitor;
 * parsing, encryption and disk I/O never prevent Main from invalidating an old owner. */
internal class NativeReadingWriteFence {
    private val monitor=Any()
    private var token=Any()
    internal class Lease internal constructor(private val owner: NativeReadingWriteFence,private val token: Any) {
        internal fun <T> ifCurrent(action: ()->T): T?=owner.ifCurrent(token,action)
    }
    fun capture(): Lease=synchronized(monitor) { Lease(this,token) }
    fun invalidate() { synchronized(monitor) { token=Any() } }
    private fun <T> ifCurrent(original: Any,action: ()->T): T?=synchronized(monitor) {
        if(original===token)action()else null
    }
}
