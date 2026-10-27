package com.agenthub.core

/** Only advertised capabilities grant access; cached history cannot grant control. */
class SessionAccess(
    val demo: Boolean, val online: Boolean, val runtimeConnected: Boolean?,
    val capabilities: List<String>, val threadSelected: Boolean,
    val hasProject: Boolean, val projectlessDraft: Boolean, val canControl: Boolean,
    val testing: Boolean,
)

class SubmissionState(
    val historyLoading: Boolean, val sending: Boolean, val stopping: Boolean,
    val replyPending: Boolean, val creationPending: Boolean, val importing: Boolean,
    val running: Boolean, val preferredReplyMode: String,
)

class ReceiptState(val id: String, val state: String, val supersededByTurnId: String?, val queueSyncError: String?=null)

/** Queue ownership stays at the original client; old Host records never form a new FIFO. */
object QueueRules {
    fun legacy(state: String, source: String?, disposition: String?) =
        state=="queued" && (source=="host" || disposition=="legacy-review")
    fun active(state: String, source: String?, present: Boolean?, disposition: String?) =
        state=="queued" && !legacy(state,source,disposition) && present!=false
    fun hasSyncError(error: String?) = !error.isNullOrEmpty()
    /** Exact target, current membership and run identity are checked by the native owner. */
    fun actionAllowed(state: String, source: String?, present: Boolean?, disposition: String?,
        online: Boolean, writable: Boolean, pending: Boolean, execute: Boolean,
        supportsExecute: Boolean, canExecuteNow: Boolean?) =
        active(state,source,present,disposition) && online && writable && !pending &&
            (!execute || supportsExecute && canExecuteNow!=false)
}
class GenerationChoice(val effort: String?, val speed: String?)
class GenerationDisplay(val model: String?, val effort: String?)

object SessionPolicies {
    /** Existing conversations show owner values; draft overrides describe the next input only.
     * A model catalogue's recommended defaults are never evidence of thread settings.
     */
    fun generationDisplay(threadSelected: Boolean, draftModel: String?, draftEffort: String?,
        runtimeModel: String?, runtimeEffort: String?): GenerationDisplay =
        if (threadSelected) GenerationDisplay(runtimeModel, runtimeEffort)
        else GenerationDisplay(draftModel, draftEffort)

    /** Codex desktop requirements do not grant creation/open/control capabilities. */
    fun codexCapabilities(capabilities: List<String>,connectionMode: String?): List<String> =
        if(connectionMode=="desktop-runtime") capabilities+(listOf("runtime:control-required","threads:create:staged").filter { it !in capabilities }) else capabilities

    fun supports(access: SessionAccess, capability: String): Boolean =
        access.demo || capability in access.capabilities

    fun desktop(access: SessionAccess, capability: String): Boolean =
        capability in access.capabilities || (access.testing &&
            (if (capability == "desktop:open") "desktop:open:testing" else "desktop:creations:testing") in access.capabilities)

    fun writable(access: SessionAccess): Boolean {
        if (access.runtimeConnected == false) return false
        if ("runtime:control-required" in access.capabilities) {
            return access.online && if (access.threadSelected) access.canControl else desktop(access, "desktop:creations")
        }
        return supports(access, "reply")
    }

    fun connectForReply(access: SessionAccess): Boolean = access.threadSelected && access.online &&
        "runtime:control-required" in access.capabilities && "reply" in access.capabilities && desktop(access, "desktop:open")

    fun compose(access: SessionAccess): Boolean = (writable(access) || connectForReply(access)) &&
        (access.hasProject || access.projectlessDraft || access.threadSelected)

    fun createThread(access: SessionAccess): Boolean = supports(access, "threads:create") || desktop(access, "desktop:creations")
    fun createProjectless(access: SessionAccess): Boolean = supports(access, "threads:create:projectless") || desktop(access, "desktop:creations:projectless")
    fun replyModes(access: SessionAccess): List<String> = listOf("queue", "steer").filter { supports(access, it) }
    // Capability availability gates submission; it cannot change the user's captured intent.
    @Suppress("UNUSED_PARAMETER") // Retain the shared native/Android facade signature.
    fun activeReplyMode(access: SessionAccess, preferred: String): String = preferred

    fun submit(access: SessionAccess, state: SubmissionState, text: String, attachmentCount: Int): Boolean =
        compose(access) && access.online && !state.historyLoading && !state.sending && !state.stopping &&
        !state.replyPending && !state.creationPending && !state.importing &&
        (!state.running || activeReplyMode(access, state.preferredReplyMode) in replyModes(access)) &&
        MobileRules.hasContent(text, attachmentCount)

    fun configureGeneration(access: SessionAccess, state: SubmissionState): Boolean =
        supports(access, "generation-settings") && compose(access) && !state.sending &&
        !state.replyPending && !state.creationPending &&
        !(state.running && activeReplyMode(access, state.preferredReplyMode) == "steer")

    fun selectPlan(access: SessionAccess, state: SubmissionState): Boolean =
        supports(access, "plan-mode") && compose(access) && !state.sending &&
        !state.replyPending && !state.creationPending &&
        (!state.running || activeReplyMode(access, state.preferredReplyMode) == "queue")

    fun running(turnId: String?, state: String?): Boolean = !turnId.isNullOrEmpty() &&
        state in listOf("running", "waiting-for-approval")

    fun stop(running: Boolean, writable: Boolean, online: Boolean, stopping: Boolean): Boolean =
        running && writable && online && !stopping

    fun recoveryPrerequisites(threadSelected: Boolean, running: Boolean, runtimeState: String?, runtimePresent: Boolean): Boolean =
        threadSelected && !running && runtimePresent && runtimeState != "unknown"

    fun respondToPlan(currentPlanId: String?, expectedPlanId: String, running: Boolean,
        supportsPlan: Boolean, threadSelected: Boolean, submittable: Boolean): Boolean =
        planPrerequisites(currentPlanId, expectedPlanId, running, supportsPlan, threadSelected) && submittable

    fun planPrerequisites(currentPlanId: String?, expectedPlanId: String, running: Boolean,
        supportsPlan: Boolean, threadSelected: Boolean): Boolean =
        currentPlanId == expectedPlanId && !running && supportsPlan && threadSelected

    // Do not trim: explicit whitespace belongs to an existing editable draft.
    fun inheritDefaults(threadSelected: Boolean, replyPending: Boolean, creationPending: Boolean,
        text: String, attachmentCount: Int, operationId: String?): Boolean =
        !threadSelected && !replyPending && !creationPending && text.isEmpty() && attachmentCount == 0 && operationId == null

    fun changeModel(priorEffort: String?, draftSpeed: String?, supportedEfforts: List<String>,
        defaultEffort: String?, supportedSpeeds: List<String>): GenerationChoice =
        GenerationChoice(if (priorEffort in supportedEfforts) priorEffort else defaultEffort,
            if (draftSpeed != null && draftSpeed !in supportedSpeeds) "standard" else draftSpeed)

    fun noticeIndices(receipts: List<ReceiptState>, dismissed: List<String>, pendingId: String?): List<Int> {
        val lastAccepted = receipts.indexOfLast { it.state in listOf("submitted", "completed", "interrupted") }
        return receipts.indices.filter { index ->
            val receipt = receipts[index]
            QueueRules.hasSyncError(receipt.queueSyncError) || receipt.state == "uncertain" || (receipt.state == "failed" && receipt.id !in dismissed &&
                (receipt.id == pendingId || (receipt.supersededByTurnId.isNullOrEmpty() && index > lastAccepted)))
        }
    }
}
