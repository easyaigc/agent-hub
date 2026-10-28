package com.agenthub.android

import com.agenthub.core.TargetScope

/** UI input from the one existing ledger. This is not an event consumer or a persistence adapter.
 * Keep one stable binding per target/thread/render epoch; a new binding revokes old reports. */
internal class NativePanelNoticeVisibility(val target: TargetScope,val threadId: String,
    notices: List<NativeReadingNotice>,val foreground: Boolean,val uncovered: Boolean,
    val renderEpoch: Any,val changed: (NativeNoticeVisibilityReport)->Unit,val renderedKey: String?=null) {
    val notices=notices.toList()
    init { ThreadReadingIdentity.key(target,threadId) }
    fun forTarget(value: TargetScope?,thread: String): NativePanelNoticeVisibility? =
        takeIf { value!=null && threadId==thread && runCatching { ThreadReadingIdentity.key(target,threadId)==ThreadReadingIdentity.key(value,thread) }.getOrDefault(false) }
}

/** The actual immutable model being rendered, not the generic count/title of a closed disclosure.
 * References are view-owned and ephemeral; never serialize/log question or plan contents. */
internal sealed interface NativeNoticePanelOriginal {
    val kind: String
    val runId: String
    val requestId: String
    data class Approval(val value: MobileApproval): NativeNoticePanelOriginal {
        override val kind get()=if(value.kind=="question")"question" else "approval"
        override val runId get()=value.turnId
        override val requestId get()=value.notificationRequestId ?: value.id
    }
    data class Plan(val value: MobileProposedPlan): NativeNoticePanelOriginal {
        override val kind get()="plan"
        override val runId get()=value.turnId
        override val requestId get()=value.id
    }
}

internal object NativeNoticeVisibilityMatch {
    fun notices(binding: NativePanelNoticeVisibility,original: NativeNoticePanelOriginal): List<NativeReadingNotice> {
        if(original.runId.isEmpty() || original.requestId.isEmpty())return emptyList()
        if(original is NativeNoticePanelOriginal.Approval && original.value.threadId!=binding.threadId)return emptyList()
        // Host approval/question/plan events have no operationId. Never guess an operation from a run/request.
        val matching=binding.notices.filter { it.id.isNotEmpty() && it.id.length<=512 && !it.seen && it.kind==original.kind && it.runId==original.runId &&
            it.requestId==original.requestId && it.operationId==null }
        // Conflicting duplicate IDs are not an authority to acknowledge either intent.
        return matching.filter { notice -> binding.notices.filter { it.id==notice.id }.distinct().size==1 }.distinct()
    }
}

internal data class NativeNoticeVisibilityReport(val target: TargetScope,val threadId: String,
    val notice: NativeReadingNotice,val original: NativeNoticePanelOriginal,val renderEpoch: Any,
    val visible: Boolean,val proof: NativeNoticeVisibilityFence.Proof)

/** Run only the ledger's in-memory mutation in this guard, never encryption, disk I/O or suspending work. */
interface NativeNoticeReadGuard { fun <T> ifCurrent(block: ()->T): T? }

/** A revocable UI-only proof. The shared Session adapter must retract its NativeReadingLifecycle
 * notice lease synchronously on false, and recheck the same target/original at the ledger commit. */
internal class NativeNoticeVisibilityFence(private val binding: NativePanelNoticeVisibility,
    private val original: NativeNoticePanelOriginal,private val notice: NativeReadingNotice) {
    class Proof internal constructor(private val owner: NativeNoticeVisibilityFence,private val revision: Long): NativeNoticeReadGuard {
        fun isCurrent()=owner.current(this,revision)
        override fun <T> ifCurrent(block: ()->T): T?=owner.ifCurrent(this,revision,block)
    }
    private var revision=0L
    private var active=false
    private var closed=false
    private var proof: Proof?=null
    @Synchronized private fun current(value: Proof,generation: Long)=!closed && active && revision==generation && proof===value
    @Synchronized private fun <T> ifCurrent(value: Proof,generation: Long,block: ()->T): T?=
        if(current(value,generation))block() else null
    fun update(value: Boolean) {
        val report=synchronized(this) {
            val next=value && binding.foreground && binding.uncovered && !closed
            if(next==active)return
            active=next;revision++
            val token=Proof(this,revision);proof=token
            NativeNoticeVisibilityReport(binding.target,binding.threadId,notice,original,binding.renderEpoch,next,token)
        }
        binding.changed(report)
    }
    fun close() { try { update(false) } finally { synchronized(this) { closed=true;revision++;active=false;proof=null } } }
}
