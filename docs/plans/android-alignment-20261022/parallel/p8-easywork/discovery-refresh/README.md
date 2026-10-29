# Discovery candidate publication after credential IO

This stage delivers a narrow production `MobileSession` patch for one confirmed
Android race. The parent owns shared-source integration, App/runner builds and native
execution. No ADB, device, Host, model or cross-chat action was performed by this leaf.
Task/project and A6 remain paused. No unknown operation is replayed.

## Confirmed source difference

`EasyWorkDiscovery.scan` performs pinned target-bound reads, then calls
`MobileSession.updateEasyWorkCandidates`. The Session re-reads a saved client and
captures its connection, then suspends while the encrypted credential is read on IO.
On return it checks the saved binding, but originally published the pre-read client
and connection. Main can process user changes during that suspension.

Consequences include re-adding a deleted client (`replaceClient` adds missing rows),
restoring an obsolete credential reference or preference, and overwriting a newer
name/online state or another candidate repair. A preference change and return to the
same value must not revive the old probe. The discovery generation check before the
suspending Session call does not close this separate writeback window.

The actual iOS `@MainActor SessionStore.updateEasyWorkCandidates` path in
`EasyWorkDiscovery.swift` reads CredentialVault synchronously and publishes metadata
before its first await. Its later Session-pool update also checks target/reference/
preference. That specific suspended-before-publication window is absent in the
reviewed iOS path; this does not certify all iOS discovery or authorization behavior.
The reviewed iOS/Android files and used codec slices match accepted commit `cd3c9a12`.
Whole Android HostReads/CodexCommands files contain unrelated newer changes; every
codec slice used by this harness was separately checked against that accepted commit.
No iOS code or behavior is changed.

## Shared-source patch contract

Apply `parent-session.patch` only after checking `session-overlay.json`:

| Input                        | SHA-256                                                            |
| ---------------------------- | ------------------------------------------------------------------ |
| Parent full Session base     | `e9c38fe9e2d87f7df5e67f4534e1087f83a2440ec7c21e79554693a1977094d1` |
| Parent full Session proposed | `91ad035f2a2c1913185f39c646daaea384ded11b9b56e7af14ed0e5f4f8808f1` |
| Original method              | `652d4eaee475c192aec198f6fa79c1a4284b6393031c2ff30c58b9458b42e99f` |
| Proposed method              | `95667c00d4790b234a1ad353b89d9e7586a0a74ac3508c92561d08f1635c8720` |

The base is the immutable accepted candidate20 frozen Session, also present in
`cd3c9a12`. A current working checkout must be checked independently; no shared file
is overwritten by this leaf. `own-session.patch` is the corresponding child-base
patch. Full base/proposed source and compile shadows are retained privately outside
the worktree. A changed parent base requires a reviewed merge and new proposed hash.

The patch captures the existing per-client revision before IO. After IO/binding
validation it re-reads the live client and refuses an absent client, changed revision,
full target, credential reference or preference. It merges candidates from the live
connection and copies the latest client, preserving unrelated current display fields.
There is no suspension between this second check and the existing metadata publication
on the original Main caller. The existing candidate deduplication/32-route limit,
full-binding check, credential reads and publication path remain in place.

No new lock, storage record, credential write, identity, claim, refresh, request,
transport, target switch or command is introduced. No Main/P9/Voice change is included.
This patch closes a local stale-metadata writeback boundary when integrated; it does
not close authorization/transport/UI or all seven A5 groups.

## Meaningful source-level counterexample

`run-source-jvm.py` compiles the exact original/proposed update body and actual
`replaceClient`, MobileClient, AgentConnection/EasyWork model, core identity/schema
and target codec source. Only the outer mutable Session state, held credential store,
unused platform holder types and presentation callbacks are test doubles. The
Session method is extracted unchanged and bound by SHA-256; the race policy is not
reimplemented in the test. The credential read captures its original bytes and yields
until Main-side changes are applied. This is a host JVM source-slice harness, not a
MobileSession, Android secure-store or native-client pass.

The unchanged original source compiles and fails8 of12 assertions. The proposed
source compiles and passes all12. Cases cover removal, replacement pointer, preference
change/round trip, all six target coordinates, pointer change without generation,
concurrent rename/online/candidate repair, normal/noop repair, missing/mismatched
credential and cancellation. Original synthetic journal/grant bytes and an independent
draft/busy state remain unchanged. Schema/identity rules come from actual production
sources. JDK JSON20250517 is pinned; it is not Android's JSON implementation.

Run each variant in a new private output directory using the exact private full
Session source and cached dependencies; no dependency installation is performed:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 <stage>/run-source-jvm.py \
  --repo <repository> --session-source <exact-base-or-proposed.kt> \
  --variant <base-or-proposed> --output <new-private-output> \
  --gradle-cache <modules-2/files-2.1> --android-jar <platform/android.jar> \
  --java <java-binary> --build-slot <parent-build-slot.py>
```

Base exits1 with `Tests run: 12, Failures: 8`; proposed exits0 with `OK (12 tests)`.
Initial harness assembly failures (incorrect SDK path and a missing existing JSONArray
helper) are retained separately and are not behavioral counterexamples. No source,
credential or candidate is downloaded from a live service.

## Prepared genuine local native regression

`NativeEasyWorkCandidateRefreshTest` contains5 methods against actual MobileSession,
Android Keystore/NativeSessionStorage and metadata. It uses the existing fair storage
IO lock to queue the actual credential read before a controlled Main mutation; no
production test hook is added. Removal/preference changes use original Session APIs.
The rotated pointer and concurrent route/online changes use explicitly test-owned
metadata state standing in for an already-published change, not a second pairing UI.

Each method owns a fresh namespace and typed synthetic unknown journal; it leaves the
original grant-like protected record and rotated credential untouched. The active
separate draft/busy state is preserved. Complete default families, including `.new`/
`.bak`, must fit64 roots/1024files/262144bytes or setup fails. Main is not launched,
no runtime Session exists at checkpoints and these methods do not test network/model
acceptance. No fake transport/client is used to claim a real network result.

The parent must freeze a new self-consistent source/App/runner and supply exactly
`p8CandidateRefreshBuild` with sourceSha256/appSha256/runnerSha256. Embedded acceptance
package and actual installed App/runner hashes are checked before owned data creation.
The five methods are a separate native stage, not new roles in the strict cold
controller. Parent owns the external bounded runner/device lease and result handling.
Do not reuse old failed UUIDs or mix an old App with a new-source claim.

All AndroidTest Kotlin sources compile with the existing child-only shared shadows
and14-key resource additions:484 inputs, source-set fingerprint
`8218c837c56714ed3994e2099c26830fcc0df5a0b0752737db0ca78562015750`.
This is SHA-256 of sorted path-to-hash JSON, not the parent's aggregate native candidate
hash. Every final input was checked after compilation. An earlier successful build
whose observation fields changed during the interval is retained but not counted.
No APK was produced and none of these five native methods ran in this leaf. Parent
must validate the real fair-lock ordering and native state assertions on its target.

## Existing pass and independent remaining gates

`accepted-cold-reference.json` preserves the parent's `cd3c9a12` record: source090df0cc,
Appc07a5405, runner95b40e4f on its exact API37 environment; writer intentionally
interrupted, one new-process reader method passed, no new claim/read/submit and owned
cleanup completed. That original pass remains tied to its original fingerprints. It
is not a pass for this proposed source/App/runner or the notification worker30/source24.

`matrix-delta.json` selects this concrete gap within the seven groups and carries
real issuer/Desktop, system-trusted Relay/TLS, camera/platform UI, both actual paths,
real model/resources, and physical full-default scale as independent open gates.
The phone's complete family-files inventory still exceeds its original cap; no
reduced snapshot, larger cap, data deletion or real EasyWork environment is used here.
Central product/design/acceptance merge registration stays parent-owned through the
parity delta. A5Complete remains false and A6 remains paused.
