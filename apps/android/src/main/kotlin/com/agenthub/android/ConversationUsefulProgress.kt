package com.agenthub.android

import java.time.Instant

/** Monotonic scheduling evidence is separate from cached content and execution authority. */
internal class ConversationUsefulProgress(private val clock: ()->Long=android.os.SystemClock::elapsedRealtime) {
    private data class Progress(val usefulAt: Long?,val checkedAt: Instant?)
    private val progress=linkedMapOf<String,Progress>()
    fun accepted(key: String,historyFallback: Boolean,checkedAt: String?,unavailable: Boolean,proofIsFresh: Boolean=true) {
        if(!proofIsFresh)return
        val previous=progress[key]
        val checked=if(historyFallback)try { checkedAt?.let(Instant::parse) } catch(_: java.time.DateTimeException) { null } else null
        if(historyFallback) {
            if(checked==null || previous?.checkedAt?.let { checked<=it }==true)return
        } else if(unavailable)return
        progress.remove(key);progress[key]=Progress(clock(),checked ?: previous?.checkedAt)
        while(progress.size>128)progress.remove(progress.keys.first())
    }
    fun lastUsefulAt(key: String): Long?=progress[key]?.usefulAt
    fun shouldCatchUp(key: String,active: Boolean,graceUntil: Long=0): Boolean {
        val now=clock();return now>=graceUntil&&(progress[key]?.usefulAt?.let { now-it>=(if(active)2_000 else 10_000) } ?: true)
    }
    /** A path changed, but cached checkedAt still cannot become new progress. */
    fun invalidateUsefulClock(key: String) { progress[key]?.let { progress[key]=it.copy(usefulAt=null) } }
    fun clear() { progress.clear() }
}
