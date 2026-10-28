package com.agenthub.android

import com.agenthub.core.TargetScope

/** Immutable render/authority key. No credentials or account-login gates; connection is an opaque
 * source identity hash, and credentialRevision is advanced by the existing saved-connection owner. */
data class NativePanelNoticeKey(val targetKey: String,val uiTargetKey: String,val readerId: String,val connection: String,
    val selection: Long,val navigation: Long,val credentialRevision: Long,val recoveryEpoch: Long,
    val renderRevision: Long,val approvals: List<MobileApproval>,val plan: MobileProposedPlan?)
data class NativePanelNoticePresentation(val target: TargetScope,val threadId: String,val key: NativePanelNoticeKey,
    val authorityGuard: NativeNoticeReadGuard?=null)

/** One revocable Session frame, never an unread ledger. Revoke before navigation/credential/render
 * mutation; the guard is held only for the existing ledger's short in-memory mutation. */
internal class NativePanelNoticeSessionFence {
    class Proof internal constructor(private val owner: NativePanelNoticeSessionFence,private val generation: Long): NativeNoticeReadGuard {
        fun isCurrent()=owner.current(generation)
        override fun <T> ifCurrent(block: ()->T)=owner.ifCurrent(generation,block)
    }
    private var generation=0L
    private var active=false
    @Synchronized fun open(): Proof { generation++;active=true;return Proof(this,generation) }
    @Synchronized fun revoke() { generation++;active=false }
    @Synchronized private fun current(value: Long)=active && generation==value
    @Synchronized private fun <T> ifCurrent(value: Long,block: ()->T): T?=if(current(value))block() else null
}

internal class NativeCombinedNoticeGuard(private val session: NativeNoticeReadGuard,private val view: NativeNoticeReadGuard): NativeNoticeReadGuard {
    override fun <T> ifCurrent(block: ()->T): T?=session.ifCurrent { view.ifCurrent(block) }
}

/** Implemented by the real Session overlay; tests can require this implementation without exposing
 * a second ledger or a notification event-ingress shortcut. */
interface NativePanelNoticeSession {
    val panelNoticeLedger: NativeReadingLedger?
    val panelNoticeLifecycle: NativeReadingLifecycle
    val hasPanelNoticeBinding: Boolean
    fun capturePanelNoticePresentation(): NativePanelNoticePresentation?
    fun presentPanelNotices(presentation: NativePanelNoticePresentation?,uncovered: Boolean)
    fun coverPanelNotices()
    suspend fun awaitPanelNoticeReading()
}
