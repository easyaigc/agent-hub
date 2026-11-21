package com.agenthub.android

/** Transport-independent lifecycle decisions; status text cannot authorize a mutation or replay. */
internal enum class NativeChatPushOwner { CODEX, EASYWORK, NONE }
internal object NativeChatPushLifecycle {
    fun owner(codex: Int, easyWork: Int): NativeChatPushOwner {
        require(codex >= 0 && easyWork >= 0)
        return if (codex + easyWork != 1) NativeChatPushOwner.NONE else if (codex == 1) NativeChatPushOwner.CODEX else NativeChatPushOwner.EASYWORK
    }
    fun retryBackground(state: String?): Boolean = state != null && state !in setOf(
        "push-registered", "disabled", "push-wait-ended-original-retained", "subscription-rejected",
        "push-original-recovery-host-upgrade-required", "push-legacy-original-unconfirmed", "original-credential-recovery-required",
        "original-credential-revocation-required", "epoch-recovery-required", "reenable-preferences-required",
        "push-not-configured", "push-invalid-configuration", "push-no-pending-token")
    fun mayMigrate(legacyUnknown: Boolean, capturedBatch: Boolean, unresolvedV2: Boolean, explicitRevoke: Boolean): Boolean =
        explicitRevoke || !legacyUnknown && !capturedBatch && !unresolvedV2
}
