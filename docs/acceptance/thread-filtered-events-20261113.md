# Thread-filtered event cursors and stable conversation controls

## PR3 physical follow-up with the identity repair, 2026-11-14

The public PR `easyaigc/agent-hub#3` was read directly: it remains open and unmerged, with head `527b800f73a85356cf486b7008d9b80e11db3068` on `codex/fix-empty-thread-event-reloads`. That is the local committed base; the Host identity repair is an uncommitted addition on the same branch, not a second branch requiring a merge. No remote PR, branch or production service was updated in this follow-up. Repository guidance now names Gitea as the development upstream; the historical GitHub PR is not silently migrated or pushed around that rule.

The current candidate was rebuilt and tested on the USB-attached Redmi Note 8 Pro, Android 11/API 30, with the separate acceptance application and preserved default storage:

| Original requirement / follow-up                                                      | This physical run                                                            | Result   |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | -------- |
| Filtered empty batches, real readiness loss and reset recovery                        | `MobileFilteredEventsTest`, synthetic Host providers                         | 3 passed |
| Concurrent response ordering, completion, legacy versions and current/stale readiness | `MobileRunningSnapshotOrderTest`, synthetic Host providers                   | 8 passed |
| Earlier entry during latest refresh, queued paging, target/foreground changes         | `MobileEarlierLoadingUiTest`, synthetic Host providers                       | 6 passed |
| Already-running original desktop task through real concurrent reads                   | `NativePr3RunningReadLiveTest`, real isolated Host and original owner        | 1 passed |
| Real historical transcript followed by live events and three fresh reads              | `NativeHistoryTransitionLiveTest`, real primed Host and already-loaded owner | 1 passed |

Total: 19 executed physical tests, no failures. The active-task observation lasted 42,978 ms and received 13 history reads plus 24 event batches (22 snapshots and two cursor-only batches). A continuous native state observer recorded zero invalidations of stop capability, write readiness or earlier-data availability. Native semantics asserted both controls remained enabled, and clicking the local earlier entry expanded the visible limit. Independent original-desktop reads before and after confirmed the same in-progress turn, identical user-input digests, and no new input. Before/after app-owned screenshots were inspected. This is evidence for the sampled controls and time interval, not a pixel-by-pixel proof against every rendering defect.

The history-transition rerun preserved the four canonical rows and their text once through three refreshes, with zero new operations; an independent desktop witness confirmed the user count remained two. This rerun uses a primed already-loaded owner and does not replace the separately documented seeded Host-retention test or claim another cold-owner recovery. See [the identity repair record](history-owner-identity-20261114.md).

The application APK SHA-256 is `64544110f71e086d15f4bf7dad586f94bfdf6e2460af0a4413f28347dfd6cd08`; the instrumentation APK is `535a69c9d7f5138c06acb3050710a508c3540491ea32bb00268cd61495715c15`. Private evidence contains exact source fingerprints, native logs, screenshots and desktop witnesses. The isolated Host was stopped and the USB forwarding and private phone fixtures removed. Formal configuration and Codex processes were preserved.

Limits remain: USB does not establish cellular/Tailscale behavior; Windows does not establish iOS compilation or execution. Deliberately delayed responses and completion transitions are covered by synthetic-provider native tests, while the real running-task test is read-only. A later test-process-only CLI-path correction passed the previously failing Windows metadata-preparer HTTP fixture, both alone and in `pnpm check`; the complete check then stopped at a separate file-transfer symlink fixture with Windows `EPERM`. See [the follow-up record](history-owner-identity-20261114.md#windows-cli-path-follow-up-2026-11-14). This is not a full-suite pass or production release. Earlier sections below are historical evidence with their original limitations, superseded only within this follow-up's explicit scope.

2026-11-14 follow-up: the separately observed duplicate historical rows are now traced to reader/owner ordinal identity mismatch and repaired at the Host projection boundary. The original observation below is preserved. Current regression and physical read-only evidence, including its exact limitations, are in [the identity repair record](history-owner-identity-20261114.md).

## Physical Android follow-up, 2026-11-14

Source `527b800f73a85356cf486b7008d9b80e11db3068` was built and tested on a USB-attached Redmi Note 8 Pro running Android 11 (API 30), using the separate acceptance application identity. All 139 shared-core JVM tests passed. Physical instrumentation passed `MobileFilteredEventsTest` (3), `MobileRunningSnapshotOrderTest` (8), `MobileEarlierLoadingUiTest` (6), and `NativeNewChatDestinationTest` (5): 22 tests, no failures. These four classes use synthetic Host read providers; they verify native state and controls rather than actual model execution.

Two separate real-desktop scenarios also passed through an isolated Windows Host and USB port forwarding: the native project/folder picker created one original-desktop chat and received a real model reply; a dedicated older chat with no original owner before the test was opened and continued once from the native composer. Independent desktop followers confirmed one new input per scenario and completed replies. The old chat retained its two canonical prior rows unchanged and ended idle with control available. The cold-chat case is opt-in through `NativeColdThreadLiveTest` and requires a private fixture; it does not target an ordinary user work chat.

The new-chat case used the same app APK as the 22-test regression run. Adding the cold-chat test changed only the instrumentation APK. Private logs, APK digests, independent desktop readbacks and native screenshots were retained locally. No production Host or Codex process was restarted or upgraded. The temporary Host was stopped and its USB forwarding removed afterward.

This does not establish cellular/Tailscale behavior, iOS execution, or an absence of every visual defect. In particular, the cold-chat screenshot showed the prior message pair twice after the live transition, while the independent desktop had only one prior user input and the canonical history preserved two prior rows. That is a separate rendering observation; historical-row deduplication was not an assertion in this connection test and its cause remains unverified. The result must not be reported as full release acceptance.

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

## Additional active-task investigation

The reporter separately described the earlier-message entry and stop-task button flickering during execution. That scenario was investigated separately rather than assumed to be covered by the empty-heartbeat repair.

A new read-only sample against the running desktop collected 13 history responses and 20 event responses, including 14 event snapshots. All 33 requests returned HTTP 200. All history responses and snapshots reported `running`, `canControl=true`, the same turn, and a 100-message window. This sample does **not** reproduce phone rendering or prove the additional cause of the reporter's visual symptom. No model input was submitted for sampling.

Code inspection found a separate response-ordering defect: both native clients accepted a delayed successful history response after publishing a newer event snapshot. Conversely, an already-pending event response could replace newer history. For example, history captured at cursor 10 can report idle with five rows; a cursor-11 event can publish running with 120 rows; the cursor-10 response can then replace both runtime and rows. The composer changes from stop to send and the local earlier-message entry disappears, until another current snapshot arrives. A delayed running response can also undo a completed-task display. This is a deterministic code counterexample, not a claim that those exact packets were observed on the phone.

Both clients now use `ConversationSnapshotFence` for latest-history and event publication, bound to the complete selected target and navigation/connection generation. Comparable same-epoch cursors cannot go backwards. Equal/newer checkpoints still apply, including genuine completion and loss of control. Missing versions and different epochs use the local read-start revision; a concurrent unprovable response is skipped, while a subsequent bounded read can establish the next checkpoint. Epoch counters are never compared across epochs. Earlier pages retain the current runtime and do not advance the latest checkpoint. Android fences readiness/capability updates too: stale unready batches cannot revoke newer proof, while genuine current readiness loss still revokes control and prevents an older success from re-enabling it.

### Additional regression evidence

- `ConversationSnapshotFenceTest`: eight executed JVM cases cover 28 delayed-response cycles with the actual shared composer and timeline rules, delayed running events after completion, newer history completion during an event read, equal/lower cursors, epoch restart, legacy unversioned responses, independent fences, and genuine current control loss/recovery.
- Replacing only the fence acceptance rule with the old unconditional-publication behavior causes five of those eight cases to fail. Restoring it passes all **139** shared-core JVM tests, including the prior filtered-heartbeat regressions.
- `MobileRunningSnapshotOrderTest`: eight Android native session/UI cases exercise both response orders, completion, legacy versions, an earlier page arriving during live work, stale versus current readiness loss, and refusal to restore control from a prefix page after readiness was lost. They use a separate acceptance storage namespace and synthetic read providers; they never submit a turn or reset the user's default storage.

Android application and instrumentation Kotlin compilation passed, including the new cases. Instrumentation requires a device/emulator to execute. The source and compile checks are auxiliary evidence; physical-phone rendering and end-to-end visual acceptance remain pending. iOS uses the same executed ordering rule, but its native build/runtime validation remains pending on this Windows host.
