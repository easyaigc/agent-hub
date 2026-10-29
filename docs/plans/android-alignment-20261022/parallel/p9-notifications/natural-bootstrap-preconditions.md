# P9 existing-key preparation and pre-body failure evidence

This is an independent test-only addition after `b6d060e5`. MainActivity, MobileSession, NativeSessionStorage, permit/key factories, shared manifest and production routing are unchanged. The permit store still only obtains an existing key. No device, ADB or Host action is performed here. Actual natural Main/OS startup remains unconnected.

Parent source30 `801157e0` / App `b0eb048c` / runner `8a7c22d6` has two retained precondition failures: fresh API26 stops at the existing-key requirement before a store fixture; the old API26 stops at default inventory's 128-file bound before its fixture body. The other four methods were not started. These are failures, not native store passes. This change does not edit their original logs, data or receipts.

The existing real-key binary/owned-control tests launch Main or populate other fixture domains and do not enforce this empty-install boundary. The new selector therefore uses the narrower normal product API directly: one synchronous `NativeSessionStorage.write` and immediate readback. It writes one fixed local acceptance cache record containing only version, purpose and the explicit bootstrap UUID. It does not construct a Session, save clients/metadata/tokens, submit input, connect, post, request permissions or initialize a permit. The encrypted record remains part of the protected default baseline.

## Explicit native queue

Use one exact independently installed acceptance app/runner pair throughout initialization, the five component methods and read-only verification. Parent owns the fresh API26 installation, no-user-data provenance, device lease and native execution.

| Selector                                                                                                        | Required arguments                                                                                    | Result intended                                                                                                   |
| --------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `com.agenthub.android.NativeChatNotificationNaturalBootstrapTest#initializeEmptyDefaultThroughProductWriteOnce` | `p9NaturalBootstrapId=<fresh canonical UUID>` and `p9NaturalBootstrapAction=initialize-empty-default` | One authorized normal product local write supplies the installation key and leaves a completed original receipt   |
| Existing five `NativeChatNotificationNaturalStoreTest` selectors                                                | Canonical `p9NaturalStoreLease`, fresh `p9NaturalStoreRun` per selector                               | Each obtains keys through existing-key-only paths and preserves the complete default-family baseline              |
| `com.agenthub.android.NativeChatNotificationNaturalBootstrapTest#verifyOriginalBootstrapReadOnly`               | Same original bootstrap UUID and `p9NaturalBootstrapAction=verify-original`                           | Only checks the original receipts, actual installed fingerprint, existing key and unchanged default-family digest |

The five method names remain `existingKeySealsClaimAndRetainsOriginalCiphertext`, `missingKeyNeverCreatesAliasOrPermitDirectory`, `interruptedClaimAndReadbackNeverRearmOriginal`, `unknownAndTamperedFilesRemainUnchangedOnRefusal`, and `otherPidLockAndSingleClaimKeepOriginal`. No whole-class bootstrap, implicit parameter or skipped method counts as initialization/acceptance.

Initialization requires the exact debug acceptance package and matching installed app/runner signer; no Main Activity; exactly its own installation PID; complete `mobile-v1*` inventory consisting only of absent or empty family-root directories; no existing App Keystore aliases, including the installation key; and no cold/natural queue material. The alias test is deliberately all-alias: existing unrelated keys are reported only as a count and are never borrowed, copied or removed. Empty nested directories, unknown/partial family material, existing queues, another PID or exceeded bounds also refuse. Do not repair eligibility by clearing data, copying/deleting keys, raising a limit or adapting an old lease.

## One-time normal write

The fixed private latch is `noBackupFilesDir/P9-natural-bootstrap-v1`. Its immutable files are `owner.lock`, `write-intent.json` and, only after success, `completed.json`. It is separate from all default families and the permit/cold routers. Existing material, even an empty/partial latch or a different requested UUID, blocks a new initialization. No removal/reset/rearm operation is added.

After preflight, the initializer atomically creates a fresh private latch, obtains its cross-process lock, writes the original intent with file/directory sync and exact readback, then rechecks all empty-install conditions. Only then does it construct the normal default NativeSessionStorage and call its ordinary write once. Its product key-generation path is the only allowed creator. There is no KeyGenerator/import/fallback added to a permit factory or test key provider. No snapshot callback is registered or queued; this is a synchronous local write/read, so it does not depend on a new migration writer API.

The success boundary requires exactly one resulting encrypted file in `mobile-v1`, with the fixed record-name hash, bounded size and successful normal product readback. Every pre-existing empty family remains; there are no nested/new side families or other files. The only App alias is now the existing installation AES key. The completed receipt binds the original intent SHA-256, UUID, executing PID, installed UID/app/runner/signer fingerprints, full family summaries before/after, key counts and exactly one normal product write. No key bytes, aliases, cached content or user paths are exported.

If any step fails, the original intent/partial files and observed default/key state remain. A failure after a possible write is not a clean slot. Do not retry with another UUID or replay the normal write. `verify-original` requires a completed original receipt and the same installed pair, then checks it without constructing NativeSessionStorage or decrypting through a potentially key-creating fallback. It only uses existing-key lookup and raw family hashing. Repeated initialization refuses; verification and failure diagnostics append only new private diagnostic proof, never rewrite bootstrap/default/key material.

## Complete family guard, same limits

`NativeNaturalFamilyInventory` enumerates every `mobile-v1*` family and uses canonical digests of paths/types/sizes and encrypted bytes. It does not restore AtomicFile sidecars, decrypt records or open normal storage. The limits remain 128 files, 256 entries, depth 8 and 32 MiB, plus a bounded parent-directory scan. No cap is increased. A 129th observed file produces `complete=false`, `reason=file-limit`, `fileCountObserved=129` and a lower-bound label, not a complete inventory or a new accepted cap.

Public proof contains family/directory/file/byte/entry counts, completeness/reason and opaque digest. It contains no file/family names or cached text. A partial scan has no complete digest and cannot become an unchanged-baseline assertion. Non-family fixture creation is excluded from the default digest, so the five P9 fixtures can add their own directories without changing this baseline. The guard before/after every fixture now covers all families, rather than only `mobile-v1`.

## Evidence before a body starts

`NativeNaturalPreflightEvidence` is an outer JUnit rule around both `@Before` and method execution. Stages identify IDs, scope, existing key, router checks, Activity check, default-family preflight, fixture body and comparison. Both the missing-key and inventory-limit failures now produce version2 proof with `bodyEntered=false`, failure type, observed family counts, key counts, execution PID/process nonce and installed fingerprints. No normal-product write is attempted by a store case. When a bootstrap write fails, its after-summary is captured if possible; an incomplete observation stays incomplete.

Proof is appended privately under `files/acceptance/P9-natural-store/<lease>/` or `files/acceptance/P9-natural-bootstrap/<bootstrap UUID>/`. Files are newly named, synced and directory-checked without following symbolic links. A custom `p9NaturalEvidence` progress record uses status code **2**, never AndroidJUnit's passing result code0. Only the final AndroidJUnit result determines pass/skip/failure counts. Reporting failure preserves the original error, with the reporting error suppressed; it cannot turn a precondition failure into a pass. Invalid/missing IDs cannot become file paths, and an assumption skip is labelled skipped.

The historical failed source30 runs did not have this outer rule. Their proof directories are not fabricated retroactively; their original instrumentation logs remain the evidence. The new rule applies only to new scheduled runs.

## Auxiliary validation and recovery

`node scripts/mobile/check-chat-notification-natural-permit.mjs --suite bootstrap`, with the existing private Gradle cache, passes 15 JVM checks. They exercise real filesystem/fence sync, two competing child PIDs with one callback, unknown/partial-result refusal, complete-family hashing, file129/depth/byte/symlink guards, privacy of summaries, and real JUnit `@Before`/inventory failure propagation without entering a store body. Product storage/AndroidKeyStore/native initialization is not run by that driver.

The combined debug app/runner compiles 82 Android tasks using the independently restored prior permit-store source plus these test overlays. Actual device success, normal product key creation and all five store component results remain parent-owned and unverified here. Model64/store17 are prior auxiliary evidence and are not upgraded by this preparation. Source/archive/APK hashes, frozen restoration, exact test delta and compressed logs are in the private `handoff.json` recovery index.

F52/F53, UI11 and S15/S16 remain the mapping. UI/copy, iOS, Host and product protocol are unchanged; A6 and task/project work remain paused. Existing instrumented-reader OS three roles and natural OS process creation retain their separate unverified scope.

Selected driver lint, document formatting and diff checks pass. This preparation branch's `pnpm check` passes format, GitHub sync5 and lint, then stops at the existing `synthetic-tone.m4a` central coverage gap; later checks are not counted. Its App APK is byte-identical to the prior permit-store compile; only the runner changes. This does not replace the parent's separate source30 or integrated candidate fingerprint.
