package com.agenthub.android

/** Test-only observation. It never reads or writes clipboard contents. */
internal data class Q02ClipDescription(
    val timestamp: Long,
    val labelSha256: String,
    val mimeTypes: List<String>,
    val classification: Int,
    val styled: Boolean,
) {
    fun epoch()=Q02ClipEpoch(timestamp,labelSha256,mimeTypes,styled)
}
internal data class Q02ClipEpoch(val timestamp: Long,val labelSha256: String,val mimeTypes: List<String>,val styled: Boolean)
internal data class Q02ClipMetadata(val elapsed: Long,val wall: Long,val focused: Boolean,val description: Q02ClipDescription?,val error: String?=null,val afterOwnClearRequest: Boolean=false,val validationCheckpoint: Boolean=false,val validationAccepted: Boolean?=null,val origin: String="description",val emptyObserved: Boolean?=null)
internal data class Q02ClipPresence(
    val elapsed: Long,val wall: Long,val focused: Boolean,
    val hasBefore: Boolean?,val hasAfter: Boolean?,val description: Q02ClipDescription?,val error: String?=null,
) {
    fun knownState()=focused&&error==null&&hasBefore!=null&&hasAfter!=null&&hasBefore==hasAfter&&hasBefore==(description!=null)
    fun knownEmpty()=knownState()&&hasBefore==false
}
internal class Q02ClipboardEpochGuard {
    private val lock=Any()
    private val samples=mutableListOf<Q02ClipMetadata>()
    private var notifications=0
    private var ownClearRequested=false
    private var checkedNotifications=0
    private val checkpoints=mutableListOf<Q02ClipMetadata>()
    private val validationAttempts=mutableListOf<Q02ClipMetadata>()
    private var armedWall: Long?=null
    private var armedElapsed: Long?=null
    fun count()=synchronized(lock) {notifications}
    fun clearRequested()=synchronized(lock) {ownClearRequested}
    fun trace()=synchronized(lock) {(samples+validationAttempts).sortedBy {it.elapsed}}
    fun arm(wall: Long,elapsed: Long)=synchronized(lock) {
        check(armedWall==null&&notifications==0) {"Clipboard changed before the exact Copy fence"}
        armedWall=wall;armedElapsed=elapsed
    }
    fun record(value: Q02ClipMetadata)=synchronized(lock) {
        notifications++
        if(samples.size<8)samples+=value.copy(afterOwnClearRequest=ownClearRequested)
    }
    fun markOwnClearRequest()=synchronized(lock) {check(!ownClearRequested);ownClearRequested=true}
    fun requireClosedEmpty(immediate: Q02ClipPresence?,beforeRemoval: Q02ClipPresence?,afterRemoval: Q02ClipPresence?,
        observerRemoved: Boolean,clearPerformed: Boolean)=synchronized(lock) {
        check(observerRemoved) {"Clipboard observer removal is not verified"}
        check(beforeRemoval!=null&&afterRemoval!=null&&beforeRemoval.knownEmpty()&&afterRemoval.knownEmpty()) {
            "Clipboard empty state at observer close is unknown or changed"
        }
        check(beforeRemoval.elapsed<=afterRemoval.elapsed) {"Clipboard close observations are not ordered"}
        check(notifications<=8&&samples.size==notifications) {"Clipboard close callback trace overflow"}
        if(clearPerformed) {
            check(ownClearRequested&&immediate!=null&&immediate.knownEmpty()) {"Immediate empty state after own clear is not verified"}
            check(immediate.elapsed<=beforeRemoval.elapsed) {"Clipboard close predates own clear observation"}
            // A new/unknown clip observed after the clear request cannot be erased or
            // dismissed merely because a later public snapshot happens to be empty.
            check(samples.filter {it.afterOwnClearRequest}.all {
                it.focused&&it.error==null&&it.description==null&&it.emptyObserved==true
            }) {"Nonempty or unknown clipboard observed after the own clear request"}
        } else {
            check(!ownClearRequested&&notifications==0) {"Untouched original empty clipboard is not proved"}
        }
    }
    fun requireEpoch(current: Q02ClipMetadata,expected: Q02ClipEpoch?=null): Q02ClipEpoch=synchronized(lock) {
        var accepted=false
        try {
        check(validationAttempts.size<8) {"Clipboard metadata validation trace bound exceeded"}
        check(!ownClearRequested) {"No clipboard ownership read after the clear request"}
        val wall=checkNotNull(armedWall) {"No exact SDK Copy fence"}
        val elapsed=checkNotNull(armedElapsed)
        check(notifications in 1..8&&samples.size==notifications) {"Clipboard metadata absent or trace overflow"}
        check(checkpoints.size<8) {"Clipboard metadata checkpoint bound exceeded"}
        val previous=checkpoints.lastOrNull()
        val freshRows=samples.drop(checkedNotifications)+current
        val continuation=listOfNotNull(previous)+freshRows
        val rows=samples+current
        check(rows.all {it.focused&&it.error==null&&it.description!=null}) {"Clipboard metadata unavailable or owner focus lost"}
        check(rows.all {it.elapsed>=elapsed&&it.wall>=wall}) {"Clipboard metadata predates the exact Copy fence"}
        check(rows.zipWithNext().all {(a,b)->a.elapsed<=b.elapsed}) {"Clipboard callback timeline is not ordered"}
        val values=rows.map {checkNotNull(it.description)}
        val epoch=values.first().epoch()
        check(epoch.timestamp>0&&epoch.timestamp in wall..wall+5000&&epoch.timestamp<=current.wall&&epoch.labelSha256.matches(Regex("[a-f0-9]{64}"))&&
            epoch.mimeTypes==listOf("text/plain")&&!epoch.styled) {"Clipboard description does not identify the admitted fresh plain-text epoch"}
        check(expected==null||epoch==expected) {"Clipboard epoch changed after ownership checkpoint"}
        check(values.all {it.epoch()==epoch}) {"Clipboard timestamp or stable description fields changed"}
        check(values.all {it.classification in 1..3}&&values.zipWithNext().all {(a,b)->
            a.classification==b.classification||(a.classification==1&&b.classification==3)
        }) {"Unsupported clipboard classification trajectory"}
        // Remember validation snapshots as well as notifications: a classification
        // regression between payload read and finally must never be forgotten.
        check(previous==null||previous.description?.epoch()==epoch) {"Clipboard epoch changed between validation checkpoints"}
        check(continuation.zipWithNext().all {(a,b)->a.elapsed<=b.elapsed}) {"Clipboard checkpoint timeline is not ordered"}
        val states=continuation.map {checkNotNull(it.description).classification}
        check(states.zipWithNext().all {(a,b)->a==b||(a==1&&b==3)}) {"Clipboard classification regressed between checkpoints"}
        checkedNotifications=notifications
        checkpoints+=current.copy(validationCheckpoint=true)
        accepted=true
        epoch
        } finally {
            if(validationAttempts.size<8)validationAttempts+=current.copy(validationCheckpoint=true,validationAccepted=accepted)
        }
    }
}
/** Cleanup-only ownership evidence. It does not make the exact selected-range assertion pass. */
internal fun q02UniqueMarkerSlice(source: String,marker: String,value: String): Boolean {
    if(!marker.matches(Regex("CHILD_[A-Za-z0-9_]{8,96}"))||value.isEmpty()||value.length>source.length||!value.contains(marker))return false
    val first=source.indexOf(value)
    return first>=0&&source.indexOf(value,first+1)<0
}
