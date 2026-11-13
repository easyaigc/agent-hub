# Thread-filtered event cursors and stable conversation controls

Scope: F17/F31, UI05, S02/S11. This repairs conversation synchronization and Android earlier-message controls. It does not repair a missing desktop runtime owner, replay inputs, or change Host permissions.

## Failure and repair

The Host event sequence is global. A subscription with a `threadId` can return an empty batch and a higher cursor when a different thread changes. The old shared policy treated that response as missing history. Android then set `freshHistories=false`, refreshed the workspace, and read history again. This repeatedly revoked/restored send/stop readiness and also disabled the earlier-message button.

The shared policy now has an explicit `threadScoped` input. Android and iOS pass `true` for their existing selected-thread subscriptions. Cursor-only filtered batches advance the checkpoint without requesting a history repair. Unfiltered callers retain their existing empty-gap behavior. Reset, epoch changes, invalid/stale cursors, readiness failures, snapshot validation, and independent bounded history catch-up retain their existing roles. An empty ready heartbeat does not itself restore control after readiness was lost.

Android earlier-message busy state belongs to the user request. Already-loaded rows can expand during a latest-history refresh. A remote page waits for an in-flight latest read, then rechecks the original target, navigation selection, connection revision, foreground state, and online state before proceeding. Existing earlier reads are joined; taps/pulls are blocked while the user request is pending. Drafts and history identity checks are preserved.

## Regression evidence

- `FilteredEventPolicyTest`: 28 successive empty filtered batches advance the cursor without requesting reload. Additional cases retain unfiltered gap repair, reset/epoch repair, stale/negative cursor rejection, unready reset handling, snapshot application, and the active/idle catch-up budgets.
- Before/after control: restoring only the previous empty-batch reload expression (with the new signature retained so the same tests compile) causes 2 of the 6 new tests to fail. Restoring the repair passes all 131 shared-core JVM tests, including all 6 new tests.
- `MobileFilteredEventsTest`: isolated Android session/control tests cover cursor-only batches, readiness loss/recovery, an empty reset, retained draft/history, and bounded catch-up without an event-driven reload loop. These are synthetic Host tests, with no user-input POSTs.
- `MobileEarlierLoadingUiTest`: native control cases cover interactive local expansion during background refresh, one queued remote earlier page, genuine page loading, and rejection of a queued page after switching threads or leaving/reentering the foreground.

The JVM run uses the repository's pinned Gradle wrapper and JDK 17. Windows required an ASCII junction plus the local `android.overridePathCheck` property; no machine-specific path workaround is part of the product change.

## Verification boundary

Shared-core JVM tests passed. Android application and instrumentation Kotlin compilation passed using the isolated acceptance application ID; no Android device/emulator was attached, so instrumentation execution and visual acceptance remain unverified. Mobile parity checks passed with `MOBILE_PARITY_BASE_SHA` set to the public upstream base: the manifest's historical Gitea baseline is not present in this GitHub clone. Changed Markdown formatting and `git diff --check` passed. Existing unrelated manifest formatting is preserved.

This Windows environment cannot compile or run iOS with Xcode. No repaired APK has been installed on the user's phone and no production Host or Codex process has been restarted by this change. Author review, merge, packaging, and device verification remain separate steps.
