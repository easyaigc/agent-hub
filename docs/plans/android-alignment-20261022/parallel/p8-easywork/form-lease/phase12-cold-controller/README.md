# Parent-owned cold writer/reader controller

This controller prepares and, only when separately invoked by the parent, runs the
existing `NativeEasyWorkCommitColdTest` writer/reader boundary. This leaf delivered
62 host self-checks and an offline preflight. No ADB command, device query, app launch,
installation, Node listener, real case, Host request or model input was performed.
The execution adapter is implemented but has not been verified on a device. A5 stays
open; A6, Task/project and iOS remain paused.

## Inputs and preflight

Use Python3.10+ on the parent Mac. No Python dependencies are required. The parent
selects one immutable candidate manifest, its SHA-256, frozen source tree, full App
and runner APKs, reviewed fixture source root, and exact private target policy. The
controller never discovers a device or supplies a default serial. Example policy:

```json
{
  "version": 1,
  "taskId": "<parent-task-id>",
  "targets": {
    "<explicit-serial>": { "kind": "physical", "avd": null }
  }
}
```

For an emulator use its exact `emulator-<port>` serial, `kind: "emulator"` and nonempty
AVD name. The parent creates this private policy from its actual permitted targets;
this example grants no device lease. Keep all inputs and output outside worktrees,
with private directory/file permissions. Every output directory must be new.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 <controller>/cold_controller.py preflight \
  --candidate <candidate.json> --candidate-sha256 <manifest-sha256> \
  --frozen-source <frozen-source> --app <app.apk> --runner <test.apk> \
  --fixture-root <reviewed-source-root> --targets <private-targets.json> \
  --target <explicit-serial> --output <new-private-output> \
  --aapt <build-tools/aapt> --apksigner <build-tools/apksigner> --node <node-binary>
```

Preflight only runs offline `aapt`/`apksigner` tools. It verifies every selected source
file and the aggregate source hash, APK bytes, embedded acceptance identities,
AndroidJUnitRunner target/component, debuggable flags, verified matching signing
certificates, and the two existing method names in runner DEX. The cold Kotlin source
is pinned to its reviewed SHA-256. Symlink/traversal source inputs and duplicate JSON
members fail. The candidate manifest is the parent's source/APK association record;
DEX name presence is not an independent source-to-binary reproducibility proof.

The fixed package is `com.easyai.agenthub.android.acceptance`; the runner package is
that name plus `.test`. The only component is
`com.easyai.agenthub.android.acceptance.test/androidx.test.runner.AndroidJUnitRunner`.
Production/DEV packages are never accepted as substitutes.

Five small public fixture files are copied to the private output and hashed. The four
MJS hashes must equal `fixture-source-lock.json`; the public vector must match the
frozen runner source asset. No APK, full source tree or private runtime state is
copied. `plan.json` and `preflight.json` are created exclusively, with a fresh UUIDv4
reserved locally. `deviceVerified: false`, `deviceCommands: 0` and
`realCaseStarted: false` are explicit. The reserved UUID is not a passed native case.

The offline Stage18 check covered source `28bfde2b`, App `c07a5405`, runner `0f6d794d`,
654 source files and matching signer `fc12e68f`. Its private output records the full
hashes. This precedes the separate positive-test scope-value correction; it proves
only static cold-controller compatibility. A later static revalidation correctly
rejected that old plan because its selected App/runner files were absent; the original
plan/proof was preserved and never executed. The parent must select/rebuild its current
combined candidate and preflight that exact candidate before execution. No old failed
positive case or sealed API37 debug lease may be reused.

## Parent runtime lease and one execution

The parent retains global device ownership, installation, process scheduling and
native result adjudication. First use its checked bounded installer, if needed, to
install the exact selected acceptance APK pair. This controller only verifies the
installed full APK hashes; it does not install, uninstall, clear data, launch Main,
restore defaults, alter settings or request permissions.

For execution, the parent supplies a new private lease JSON with exactly these fields:

```json
{
  "version": 1,
  "taskId": "<same-parent-task-id>",
  "leaseId": "<new-uuid-v4>",
  "owner": "<parent-owner>",
  "serial": "<exact-plan-serial>",
  "bootId": "<parent-observed-boot-uuid>",
  "api": 37,
  "planSha256": "<SHA-256-of-plan.json-file-bytes>",
  "exclusiveAcceptanceProcess": true,
  "expiresAt": "<timezone-qualified-time-5-to-60-minutes-ahead>",
  "adb": "<absolute-selected-adb-binary>",
  "adbSha256": "<selected-binary-sha256>",
  "lockFile": "<private-directory>/p8-cold-<serial>-<leaseId>.lock"
}
```

The named lock is an additional controller lock, not a replacement for the parent's
global device lease. The parent must hold its global lease until execution and
cleanup finish. The initial remaining lease must be5–60minutes. API26–37 and primary
Android user0 are the explicit implementation range. No API/AVD support is claimed
from these offline checks.

Only the parent invokes the following after granting the exact runtime lease:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 <controller>/cold_controller.py execute \
  --plan <new-private-output>/plan.json --lease <private-execution-lease.json>
```

Execution rehashes the original candidate, tools, frozen sources and fixture before
importing the device adapter. The execution directory is exclusive: an existing
attempt fails before any driver call. Runtime preparation verifies exact serial,
boot, API/AVD, primary user, installed APK bytes and an idle acceptance package with
no active instrumentation. Unknown instrumentation inventory format blocks the run.
The bounded full `dumpsys activity` inventory parser is still a native validation
gate; see [OEM inventory correction](OEM-inventory.md). No other runner is stopped or taken over.

One owned Node child starts the snapshotted fixture with fresh loopback ports. Its
PID/config/health/sourceGraph must agree; existing active cases block. Only its two
new exact reverse mappings are added, preserving previous mappings. The controller's
HTTP observations are limited to `/health` and `/stats`; no controller begin, end,
reset, resolve or release route exists. Native writer/reader own their original case.

## Strict writer and reader sequence

1. Start exactly
   `NativeEasyWorkCommitColdTest#writeUnactivatedCandidateAtFinalReadForColdRestart`.
   Read only `files/acceptance/p8-positive-commit/<reserved-UUID>/writer-ready.json`.
   Validate canonical original bytes, all strict fields, build/config/sourceGraph,
   same namespace/key alias, zero saved callbacks, held final read, original request
   and read hashes, candidate/grant/claim/journal/metadata/identity/default hashes.
2. Independently observe writer PID, UID, boot and `/proc` generation/start time.
   Hash the exact six existing encrypted lineage files. Check current held stats.
   After rechecking the same process identity, force-stop only this owned writer.
   Prove its PID is gone less than18s after native ready time, with a20ms safety
   margin. An old checkpoint, ownership change, completed writer or JUnit success
   fails. The writer result is **interrupted checkpoint**, never a JUnit pass.
3. Before reader, recheck exact original ready bytes, default files, six ciphertext
   hashes, no active instrumentation/process and the unchanged held case. Then start
   exactly
   `NativeEasyWorkCommitColdTest#newProcessRestoreKeepsUnreferencedCandidateInactiveWithoutClaimOrSubmit`
   with the same config/build/UUID and original writer proof. No identity, key,
   candidate, grant, claim or new case is created by the controller.
4. Require exactly one reader method to pass, a different PID, newer external process
   generation, same boot/UID and native/external start-time agreement. Validate the
   original writer proof hash and every reader protection flag. Require unchanged
   ready/cipher/default bytes and the exact original closed case, unchanged claim and
   protocol-read hashes, held response unreleased and zero real Host/model input.

AOSP raw stats-array hashes intentionally use insertion order and escaped slashes;
they are distinct from sorted canonical config/proof hashes. Self-checks cover this
boundary. Device output has hard size/deadline limits; logs rotate2MiB×4, with128KiB
in-memory tails. Only the controller's local transport child is killed on a transport
limit. That event does not mean the device operation was cancelled.

## Failure evidence and cleanup

Any writer/reader/proof/deadline failure seals the original UUID and observations in
`execution/failed-sealed.json`, retaining rejected raw proof when available. There is
no retry, reset, lease/identity replacement, old-case replay or original data deletion.
A failed case may still be held when its own sidecar exits; its active snapshot and
logs are evidence, not a successful native close. Recovery requires parent review.

Cleanup stops only the owned local Node/transport children and removes the two own
reverse mappings if each still matches exactly, rechecking removal. Changed/foreign
mappings are preserved. Cleanup never force-stops a writer/reader on its failure path.
An unverified writer interruption or unfinished reader transport is reported as
incomplete for parent review. Completion of the reader transport does not claim
termination of every Android process.
The sole device force-stop is the verified writer interruption in step2. Defaults,
including existing `.new`/`.bak` files, and original phone/Main state remain intact.

`writer-interrupted.json`, original `writer-ready.json`, `reader-proof.json`, native
`passed.json`, and `resource-cleanup.json` are separate records. Cleanup failure
changes the CLI result to `native-pass-cleanup-incomplete` and nonzero exit status;
a native passed checkpoint alone is not overall success. Exit0 requires the complete
cold boundary and owned-resource cleanup, while `a5Complete` always remains false.

## Host self-checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s <controller-directory> -p 'test_*.py' -q
```

62 tests use injected fake drivers, static synthetic APKs/tool metadata, adapter
command construction, bounded local Python children, and a host-only `find` integration
over a temporary synthetic namespace tree. They cover successful
ordering, fresh/exact inputs, deadline/PID/UID/boot/generation mismatches, changed
config/default/cipher/lineage/proof, extra claim/read, no reentry, shell argument
preservation, target/lease rejection and precise cleanup. Static preflight poisons
the device-module import and does not construct a device adapter. Adapter tests
replace all device operations; local Python subprocess tests exercise hard output
and timeout limits only. These are auxiliary checks, not Android native acceptance.
