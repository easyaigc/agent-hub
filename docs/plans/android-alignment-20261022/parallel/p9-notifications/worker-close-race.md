# P9 worker retirement and closed wake signal

The parent API26 failure is retained at source `e7b7d0bf`/App `26602313`/runner `d2f3432c`. Its permission-denial/recovery case reports an unhandled Main `ClosedReceiveChannelException` at the supplied coordinator's `withTimeoutOrNull { wake.receive() }`, line 190. P7 framed-text passing cases and historical closed-query/cold results do not certify this worker boundary. No original device case or input has been rerun here.

The exact shared base is `ee4eef9771fc56c182826c1ece367697746ece7e72edc0bc11a834fea77ebb4a`; proposed is `aa07dc1029e1ad1300fe608fc8898ec0c3304bd26b57500b1152d2ae25a10134`. `worker-close-race.patch` changes only that supplied coordinator. It has not been applied to the parent or this branch's older standalone coordinator. MainActivity, MobileSession, monitor/controller/journal code, P14 IO tracker and production settings remain unchanged.

## Finding and change

A worker is inserted into `workers` before assigning `worker.job = scope.launch(Main.immediate)`. The child can run synchronously before that assignment. Synchronous retirement then sees a null Job, removes the worker and closes its wake channel without cancelling the actual child.

There is a second failure path: even when the Job was registered and cancelled, `synchronize` can return from its NonCancellable cleanup to the retired worker. The old code then starts an undispatched timeout block and calls receive on the already closed channel. The parent's raw stack establishes the closed receive, not the precise assignment state. Deterministic checks below establish both source-level paths independently rather than assuming every native occurrence used a null Job.

The proposed change:

- Creates the child with `CoroutineStart.LAZY`, assigns its Job, publishes the worker, then immediately starts it on the same Main dispatcher. There is no added delay/yield or deferred initial poll.
- Rechecks active Job, availability, reading readiness and exact worker owner immediately after synchronization, before report publication/wait. A stale presentation lease still suppresses only that report; it does not retire a current worker that must consume an explicit preferences wake.
- Uses `receiveCatching` so a normal closed channel exits the loop. An exceptional close cause is rethrown; no general exception is swallowed. Timeout remains `pollMillis`, and explicit conflated wake still triggers the next poll.

It does not restart a retired worker, negotiate another registration, retry a submitted operation, change target/authority/fence semantics or alter owner/IO retirement. The original monitor cancellation and stopWorkers removal/close remain.

## Deterministic auxiliary cases

The fixed test-only field probes are `workers`, `job` and `wake`. No production hook or arbitrary dispatcher/method is added. A test-only exception handler records failures and the checks reject any recorded exception; it is not a production suppression mechanism.

1. `entry-reentry`: a Coroutine ThreadContextElement observes the first worker entry and synchronously calls the real `connectionsWillChange`. It checks the registered Job is the actual entering child. The base deterministically exposes a null Job and leaves that child uncancelled; the proposed child is registered/cancelled before its first synchronize. No identity, capabilities, submit or feed is created, and the exact retained authorization remains.
2. `normal-close`: a synchronous first-report Snapshot callback closes the private wake signal normally, isolating signal closure from registration/cancellation timing. The base records `ClosedReceiveChannelException`; the proposed loop completes normally. One original synthetic register/feed remains, no second call or automatic restart occurs.
3. `sync-return-retire`: a synthetic register is already durably unknown before the coordinator starts. With local preferences off, the real Delivery.cancel callback on NonCancellable Main synchronously retires the worker. The base records the same closed-receive exception even with a registered/cancelled Job. The proposed worker exits without entering that wait; the original encoded journal/identity read back unchanged (raw ciphertext equality is not asserted), with no query, resubmit, feed or new revoke.

The JVM driver compiles the exact supplied coordinator bytes and existing monitor/controller logic with explicit JVM Main/Looper, memory-storage and archive-result seams. It is not Android/KeyStore, HTTP executor, real Host or full DEV proof. Existing eight enrollment/original-query/owner-retirement vectors run separately against the same proposed bytes.

From a built combined source root containing these fixture sources and cached dependencies, use the driver in the checkout:

```sh
node <checkout>/scripts/mobile/check-chat-notification-enrollment.mjs \
  --source-root <combined-root> --coordinator <exact-coordinator.kt> \
  --suite worker-close --case all --label reviewed-worker
```

The working directory is the built combined root so its app/core jars are used. `GRADLE_USER_HOME` selects the existing private cache. Run baseline cases individually because the first expected failure stops `all`. The finite `--case` values are `all`, `entry-reentry`, `normal-close` and `sync-return-retire`. Default `--suite enrollment` preserves the existing eight-vector workflow.

## Minimal native follow-up

`worker-close-race-native-tests.patch` adds the current standalone fixture and native wrapper to androidTest only after the reviewed coordinator patch. The standalone sources are under `scripts/mobile/fixtures/native/`, avoiding a knowingly incompatible dependency on P14 methods in this branch's older default app source.

Parent schedules each of these selectors with explicit canonical `p9WorkerCloseLease` and a **fresh `p9WorkerCloseRun` for each selector**:

- `NativeChatNotificationWorkerCloseTest#synchronousEntryReentryFindsRegisteredJobAndCancelsBeforeFirstSync`
- `NativeChatNotificationWorkerCloseTest#synchronousRetirementDuringCancelReturnsWithoutClosedWaitOrReplay`

These use real Android Main/Looper, existing installation AES key, actual isolated encrypted storage and the supplied coordinator. They use local synthetic backend/system boundaries, never launch MainActivity, post notifications, change permissions, open default storage or contact a Host/model. An incomplete cold router blocks this separate queue. Fixtures are preflighted fresh before storage creation; owner stop/join precedes writer retirement. Encrypted originals and private bounded proofs under `files/acceptance/P9-worker-close/<lease>/` remain for parent review. Native execution remains parent-owned and not run here. Do not rerun the old source21 failure to replace its evidence.

The actual HTTP/executor retirement and the full existing native regression remain separate parent verification. Native worker passing must precede adopting this code in a new OS cold candidate. The already delivered OS TestSpec `8c648123` stays source-identical, but its old coordinator binary must not be treated as the fixed candidate.

Feature mappings are F52/F53, UI11 and S15/S16. Android lifetime correction only; no iOS source/result or paused A6/task/project expansion.

Validation here: all three exact-base checks fail deterministically (unregistered Job, normal closed receive, cancelled-worker closed receive); the proposed bytes pass all three and the existing eight enrollment/original-owner auxiliary vectors. The PR51-frozen combination with the proposal, both new native selectors and unchanged OS TestSpec compiles all 82 Android tasks. These remain auxiliary results; device/native execution is not run here. `pnpm check` passes format, GitHub sync5 and lint, then stops at the existing central `synthetic-tone.m4a` parity coverage gap.
