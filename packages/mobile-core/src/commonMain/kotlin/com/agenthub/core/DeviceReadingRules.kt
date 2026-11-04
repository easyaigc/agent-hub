package com.agenthub.core

data class DeviceReadingVersion(val revision: Long,val messageId: String,val contentVersion: String)
data class DeviceReadingSnapshot(val authoritative: Boolean,val counter: Long,val latest: DeviceReadingVersion?,
    val read: DeviceReadingVersion?,val unread: Boolean,val pending: Boolean)
data class DeviceReadingMerge(val snapshot: DeviceReadingSnapshot,val error: String?=null)

/** Shared pure reconciliation. Provider authentication, digesting, visibility and storage stay native. */
object DeviceReadingRules {
    private const val maximum=9_007_199_254_740_991L
    private fun valid(value: DeviceReadingVersion?)=value==null || value.revision in 1..maximum &&
        value.messageId.length in 1..512 && value.contentVersion.matches(Regex("[a-f0-9]{64}"))
    private fun sameBody(first: DeviceReadingVersion?,second: DeviceReadingVersion?)=first!=null && second!=null &&
        first.messageId==second.messageId && first.contentVersion==second.contentVersion
    fun mergeAuthority(old: DeviceReadingSnapshot,counter: Long,latest: DeviceReadingVersion?,remoteRead: DeviceReadingVersion?): DeviceReadingMerge {
        fun invalid()=DeviceReadingMerge(old,"reading_version_conflict")
        if(counter !in 0..maximum || !valid(latest) || !valid(remoteRead) ||
            latest!=null && latest.revision!=counter || remoteRead!=null && remoteRead.revision>counter ||
            latest!=null && remoteRead?.revision==latest.revision && remoteRead!=latest)return invalid()
        if(old.authoritative && counter<old.counter)return DeviceReadingMerge(old)
        if(old.authoritative && counter==old.counter && old.latest!=latest)return invalid()
        var read=if(old.authoritative)old.read else old.read?.takeIf { sameBody(it,latest) }?.let { latest }
        if(remoteRead!=null) {
            if(read!=null && read.revision==remoteRead.revision && read!=remoteRead)return invalid()
            if(read==null || remoteRead.revision>=read.revision)read=remoteRead
        }
        val settled=latest!=null && read==latest
        // Once an authenticated per-device head exists, only this device's watermark can settle it.
        // A desktop flag, first catalog visit or previously seeded false baseline is not a read.
        val unread=latest!=null && !settled
        return DeviceReadingMerge(DeviceReadingSnapshot(true,counter,latest,read,unread,settled && read!=remoteRead))
    }
}
