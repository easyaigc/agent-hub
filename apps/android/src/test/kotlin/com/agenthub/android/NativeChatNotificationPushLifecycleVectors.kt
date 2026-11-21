package com.agenthub.android

/** Pure lifecycle policy vectors. No SDK, credentials, model task or notification service. */
fun main() {
    check(NativeChatPushLifecycle.mayMigrate(false, false, false, false)) // Confirmed, quiescent journal is migratable.
    check(!NativeChatPushLifecycle.mayMigrate(true, false, false, false)) // v1 unknown is not a confirmed registration.
    check(!NativeChatPushLifecycle.mayMigrate(false, true, false, false)) // Finish captured delivery before changing modes.
    check(!NativeChatPushLifecycle.mayMigrate(false, false, true, false)) // Unknown v2 must query original ID.
    check(NativeChatPushLifecycle.mayMigrate(true, true, true, true)) // Independent explicit revoke may fence older work.
    for (terminal in listOf("push-registered", "disabled", "push-wait-ended-original-retained", "push-legacy-original-unconfirmed",
        "push-original-recovery-host-upgrade-required", "epoch-recovery-required", "subscription-rejected")) {
        check(!NativeChatPushLifecycle.retryBackground(terminal))
    }
    for (transient in listOf("push-registration-pending", "push-mutation-unknown-original-query", "stale-target-or-generation")) {
        check(NativeChatPushLifecycle.retryBackground(transient))
    }
    check(!NativeChatPushLifecycle.retryBackground(null)) // Old server is not turned into a busy network retry loop.
    check(NativeChatPushLifecycle.owner(1, 0) == NativeChatPushOwner.CODEX)
    check(NativeChatPushLifecycle.owner(0, 1) == NativeChatPushOwner.EASYWORK)
    check(NativeChatPushLifecycle.owner(0, 0) == NativeChatPushOwner.NONE)
    check(NativeChatPushLifecycle.owner(1, 1) == NativeChatPushOwner.NONE)
    check(NativeChatPushLifecycle.owner(2, 0) == NativeChatPushOwner.NONE)
    println("PASS migration / original-ID-only retries / explicit-revoke / ended-wait lifecycle policy")
}
