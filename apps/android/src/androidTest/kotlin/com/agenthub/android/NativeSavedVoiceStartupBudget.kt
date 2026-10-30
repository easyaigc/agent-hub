package com.agenthub.android

internal fun nativeSavedVoiceStartupMismatches(identity: Boolean,ended: Boolean,osFont: Float,targetFont: Float,activityFont: Float,orientation: Int,expected: Int): List<String> = buildList {
    if(!identity)add("activity-identity")
    if(ended)add("activity-ended")
    fun font(value: Float)=value.isFinite()&&kotlin.math.abs(value-2f)<.01f
    if(!font(osFont))add("os-font-scale")
    if(!font(targetFont))add("target-font-scale")
    if(!font(activityFont))add("activity-font-scale")
    if(orientation!=expected)add("activity-orientation")
}

/** One monotonic budget for every poll and wait; no reset on mismatch or unavailable sample. */
internal data class NativeSavedVoiceStartupObservation<T>(val value: T,val startedMs: Long,val finishedMs: Long,val withinDeadline: Boolean)
internal data class NativeSavedVoiceStartupResult<T>(val ready: Boolean,val reason: String,val elapsedMs: Long,val samples: List<NativeSavedVoiceStartupObservation<T>>)
internal fun <T> awaitNativeSavedVoiceStartup(
    now: ()->Long,
    sample: (remainingMs: Long)->T?,
    matches: (T)->Boolean,
    pause: (Long)->Unit,
): NativeSavedVoiceStartupResult<T> {
    val start=now();val samples=mutableListOf<NativeSavedVoiceStartupObservation<T>>()
    fun remaining()=maxOf(0L,5000L-(now()-start))
    fun result(ready: Boolean,reason: String)=NativeSavedVoiceStartupResult(ready,reason,maxOf(0,now()-start),samples.toList())
    while(remaining()>0&&samples.size<101) {
        val before=now();val value=sample(remaining());val after=now()
        val within=after-start<5000&&after>=before&&before>=start
        if(value==null)return result(false,if(remaining()==0L)"deadline-exceeded"else"sample-unavailable")
        samples.add(NativeSavedVoiceStartupObservation(value,before-start,after-start,within))
        if(!within)return result(false,"deadline-exceeded")
        if(matches(value))return result(true,"actual-configuration-matched")
        val wait=minOf(50L,remaining());if(wait>0)pause(wait)
    }
    return result(false,if(remaining()==0L)"deadline-exceeded"else"sample-limit")
}
