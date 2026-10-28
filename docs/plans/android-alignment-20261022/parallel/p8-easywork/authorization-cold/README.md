# P8 authorization: Android process cold writer / reader

This checkpoint adds test preparation only. Production authorization/storage code and the
frozen 16/12/19/12 method classes are unchanged from `eb09a56b`. No device, real issuer,
Host, desktop or model has been used here. The four Node tests use fake PIDs and validate
only the sidecar contract; compilation does not establish Android cold-process behavior.

The parent reported that its combined API 33 build passed the old 18 core authorization
methods plus the new 12 fence methods. The exact reported source/app/runner hashes are in
`checkpoint.json`. The original baseline failure remains immutable and separate. Those
30 passes do not establish process-death recovery and cannot be reused as this scenario.

## Fixed scope

Use one physical-device lease and only `com.easyai.agenthub.android.acceptance` with
`com.easyai.agenthub.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner`.
Both APKs must remain identical through both invocations. This child does not acquire a
lease, run ADB, install, start a sidecar or contact any real service.

The writer creates one fresh case namespace and its own Android Keystore AES/DPoP keys,
pairs with the synthetic issuer, holds one actual HTTP refresh, then submits a revoke.
The issuer executes the DELETE but drops its response. The writer freezes the persisted
pending fence and the immutable original revoke ID/body/token fingerprint. The original
refresh attempt is encrypted in the same native storage. Public proof contains hashes and
public binding metadata only; it contains no raw token, private key or storage-root path.

The reader requires that same directory, AES key, DPoP key and identity. It checks the
writer receipt, acknowledgement, fence, saved record and refresh-attempt hashes before
any mutation. Missing/mismatched data aborts; the reader cannot seed, pair, reset a
fixture, recreate keys or substitute another authorization ID. It refuses fresh headers,
opens the exact held encrypted reply using native crypto, and probes the production
save fence with the writer-captured attempt. It resumes only the original named DELETE
with the same ID/body/token hash/signer and a fresh DPoP jti. Pending and confirmed stale
saves must be refused and the grant must remain absent.

The stale-save probe rehydrates the persisted attempt after native decryption. It is
explicitly **not a revived dead thread or a post-death callback**. The original refresh
socket must already be closed. This scenario does not establish cross-process concurrent
file locking, system-trusted HTTPS, live issuer compatibility or business input replay.

## One sidecar lifetime

From the exact integrated source, start once in a new private directory:

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-cold/fixture.mjs --start <new-private-case-directory>
```

Retain `config.json`, sidecar PID/start time and the source graph. The service/control
origins are different ephemeral `127.0.0.1` ports. The lease owner creates only the two
exact same-port device reverse mappings, then supplies **the same complete JSON** as
`p8ColdConfig` to both invocations. Freeze the installed source/app/runner SHA-256 set as
`p8ColdBuild = {"sourceSha256":"<64 hex>","appSha256":"<64 hex>","runnerSha256":"<64 hex>"}`.
The test hashes both actual installed APK files; the source hash is the parent's frozen
build-input assertion. Recompute the source set and both packages for the parent's
combined build instead of copying this child's package values.

Only `/health`, `/stats`, `/freeze-writer`, `/reader-begin`, `/late-delivery` and
`/finish-reader` exist on the control listener; requests other than `/health` require
`X-P8-Fixture: <instanceId>`. There is no reset, begin-case, replay, reconstruction or
resume-from-disk interface. A stopped sidecar cannot satisfy the original case. The
reader phase accepts only the exact original DELETE on the service listener. Totals
must be four writer requests and one reader DELETE, zero fixture errors, zero real
Desktop requests and zero real model inputs. The sidecar retains the writer request
snapshot and exact original JWE in memory. It never rebuilds them for the reader.

## Leased controller sequence

1. Freeze the physical device identity, integrated commit/source set, both installed
   package hashes and signer certificates, runner target, sidecar PID/start/instance,
   config hash and both exact reverse mappings. Do not run the whole test class.
2. Start only
   `com.agenthub.android.NativeEasyWorkAuthorizationColdTest#writeFrozenUnknownRevokeForColdRestart`
   with `p8ColdPhase=writer`, `p8ColdConfig` and `p8ColdBuild`. Run instrumentation
   asynchronously so the controller can read its checkpoint while it is alive. Setup
   has a 12-second coroutine budget; the method has a 60-second JUnit limit and holds
   the original process for at most 45 additional seconds after setup. The outer
   controller deadline is at most 240 seconds. A hold timeout is a failed attempt.
3. Pull the exact public files under the leased target's private files directory:
   `files/acceptance/p8-auth-cold/<caseId>/writer-proof.json` and `writer-ready.json`.
   Wait for **both** and the sidecar's `writer-frozen.json` / `writer-frozen` phase.
   The ready marker is written only after the encrypted freeze acknowledgement.
   Verify the canonical writer hash is identical in all three records; retain the
   original raw writer proof unchanged. Confirm its exact PID is still alive with
   the target package and matches the independent process-start observation.
4. While that writer invocation is still waiting, force-stop only the leased
   acceptance target. Record the command/result and independently prove that exact
   old PID is gone. An absent PID before the command, normal writer return, timeout,
   unexplained runner death or missing proof is insufficient to claim force-stop
   killed the writer. The writer is intentionally interrupted: its JUnit process
   termination is expected only when independently attributed to this exact
   controller action. Do not count it as an ordinary passing JUnit method.
5. Preserve the immutable old proof first. Do not reinstall, clear data, replace
   identity/keys, restart/reseed the sidecar, change reverse mappings or rerun the
   writer. Retain a sidecar stats snapshot showing the original socket closed and
   the same PID/start/instance plus four original requests. Complete both phases
   promptly within the synthetic access-token validity (10 minutes); delay is a
   failed/incomplete attempt, never grounds for silently renewing the grant.
6. Start only
   `com.agenthub.android.NativeEasyWorkAuthorizationColdTest#readOriginalFenceAndResumeOnlyAfterNewProcessStarts`
   with `p8ColdPhase=reader`, the original `p8ColdConfig`/`p8ColdBuild` and the exact
   original `writer-proof.json` JSON as `p8ColdWriterProof`. Use structured argument
   handling to preserve JSON. The reader has a 12-second coroutine budget,
   20-second JUnit limit and at most 240-second outer deadline. Its actual PID must
   differ and `Process.getStartElapsedRealtime()` must be later; the controller also
   independently checks the new package/PID/start. PID reuse is a refusal.
7. Require exactly one reader JUnit pass, its immutable public `reader-proof.json`,
   the sidecar's `reader-begin.json` and `reader-complete.json`, complete phase,
   unchanged writer hash, identical original DELETE intent fingerprints, distinct
   DPoP jti and final grant absence. A reader-start/new object without process
   observations does not establish cold recovery. Correlate native and sidecar
   proofs by case/instance/source graph/config and installed fingerprints.
8. Freeze evidence before cleanup. Stop only this case's sidecar, release its two
   exact reverse mappings and finish the target process under the lease. Preserve
   the case's encrypted records/keys until evidence review; do not clear the app's
   other data. The writer leaves a test-owned held request intentionally and has
   no cleanup/reset endpoint. Never leave that listener or request alive after
   the leased attempt, successful or failed.

## Evidence and failure handling

Record writer checkpoint/forced termination and reader result separately, including
wall-clock timestamps, PID/start observations before/after force-stop, package/signer
checks before both invocations, exact sidecar lifetime, source fingerprints, request
hashes and bounded runner output. Keep the raw old writer proof immutable when adding
reader observations. Only the parent performs those device actions and records results.

A missing key/record, changed fingerprint, sidecar restart, timeout, unknown operation
result, additional request or late delivery failure leaves this scenario failed or
unverified. Preserve the original evidence and ID. Do not rerun/reinitialize the same
case or claim the original input was cancelled. A separately authorized fresh attempt
needs a new directory/case and keeps the failed attempt as an independent record.

`parity-delta.json` is a parent integration input for F43/F44/F46. Central product,
design, acceptance and manifest registration remain parent-owned. Android UI and iOS
code are unchanged. A5 has no new group closure and A6 remains paused.
