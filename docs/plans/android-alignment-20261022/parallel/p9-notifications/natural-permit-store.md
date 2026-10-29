# P9 natural permit store: first implementation stage

The later [existing-key precondition preparation](natural-bootstrap-preconditions.md) adds an explicit empty-install normal product write selector and outer evidence for failures before a fixture body. It keeps the store, key factories, production source and manifest unchanged; native execution is still separate.

Implemented: a debug acceptance-only authenticated, append-only permit/claim store, with cross-process exclusion and sync/readback before a committed claim returns. The original canonical model from `5b9b402c` is moved once into `apps/android/src/debug/kotlin/com/agenthub/android/`; its rule behavior is unchanged. There is no natural-startup caller, Activity override or default-store fallback. MainActivity, MobileSession and NativeSessionStorage are unchanged. Existing instrumented-reader OS source `8c648123` remains a separate pending parent scenario.

The implementation and five native selectors compile against a frozen copy of merged PR51 commit `d1cc8aed09db0a5ebd51506d642c734f1eadfcf8`. The actual parent checkout is not modified. Native/KeyStore/device execution and natural OS process creation are not run here. The old PAR21 failure and other lines' CI failures are not rerun or relabelled.

## Files and authority

`NativeChatNotificationNaturalFiles` implements the bounded filesystem/crypto operations. `NativeChatNotificationNaturalStore` supplies the Android existing-key source, real installed binding validation and directory fsync. Both are debug-only. All operations and installed APK hashing reject Main-thread use; later cold integration must prepare asynchronously before Main selects any storage. The old synchronous cold hook is not silently extended.

The Android factory accepts only the exact debuggable acceptance package. It verifies the actual UID, single unsplit app and runner APK SHA-256 values, current app/runner signer sets and Android signature match. It only reads `agent-hub-mobile-v1` from AndroidKeyStore. There is no KeyGenerator, key import, alternate identity, NativeSessionStorage construction or alias fallback. A missing key fails before creating even a directory or lock. Invalidated/authentication-constrained keys can fail during cipher use; partial evidence is retained, and no committed claim returns.

The original plan, target, authority, token, ticket, receipt, install and OS-marker hashes are authenticated data supplied by the authorized writer. This stage authenticates their stored bytes and checks installed identity. It does not itself establish that a backend event, OS post, permission or strict Intent observation occurred. A future natural caller must independently obtain those observations and the same original cold receipt. Native component fixtures deliberately use synthetic original/OS flags while their key, APK/UID, filesystem and process boundaries are real.

The fixed live namespace is `P9-notification-natural-permit-v1`. The separate test factory derives only `P9-natural-store-fixture-<canonical run UUID>`. No namespace, key alias, method, endpoint or business body is read from a product Intent. The tests never address a live latch. An existing directory, even empty, cannot be armed again. There is no reset, deletion, failed-attempt disarm or rearm API.

## Durable record protocol

Only these exact names are recognized in an owner-only directory. Files must be regular, owner-only, singly linked and bounded; symlinks, unknown names, sidecars, missing material and malformed/authentication failures are preserved and rejected.

| Record                 | Content and lifetime                                                                    |
| ---------------------- | --------------------------------------------------------------------------------------- |
| `owner.lock`           | Empty existing lock inode; opened without CREATE for later operations                   |
| `device-challenge.bin` | One random 32-byte fixture challenge, encrypted with the existing installation key      |
| `permit.bin`           | Immutable original canonical ARMED state, encrypted once                                |
| `claim.bin`            | One write-ahead claim fence and then encrypted CLAIMED state; never replaced or removed |
| `complete.bin`         | Optional one-time encrypted COMPLETE state; original permit/claim remain                |

AES-GCM uses a fresh provider IV and a 128-bit authentication tag. Its fixed AAD binds the format, namespace name, canonical original lease and exact record role. The decrypted challenge must match the digest inside the authenticated original. Moving ciphertext to another role, root or lease does not authorize it. The original token is represented only by its hash; keys and real credentials are never exported.

Arm first validates the schema, original challenge and actual install, and obtains the existing key. It atomically creates a new private directory and lock, syncs them, then appends the sealed challenge and permit. An interrupted arm leaves inspection-only partial material, not a clean slot. The store's filesystem never restores AtomicFile sidecars.

Later operations validate the existing inventory, open that exact lock without creation and call [FileChannel.tryLock](https://developer.android.com/reference/java/nio/channels/FileChannel). Contention, including another instance in the same process, returns a refusal immediately. There is no spin, wait queue or second claim attempt. Inside the lock it repeats validation, authenticates the original and challenge, validates the current installation and applies the original model rules.

A claim uses CREATE_NEW for `claim.bin`. It syncs the directory **before writing claim contents**, so a durable empty/partial file is already a non-replay fence. It writes the sealed CLAIMED state, forces the file, syncs the directory again and authenticates/readbacks the full state under the same lock. Only exact equality returns `CommittedClaim`. Any failure returns no committed result. A later reader sees the persisted claim or an unresolved partial file; it cannot infer ARMED from that failure. Completion follows the same append-only principle, with no change to original ciphertext.

Android directory synchronization uses public [Os.open, fstat and fsync](https://developer.android.com/reference/android/system/Os), checking the opened descriptor is a directory and rejecting symbolic-link traversal. The current public SDK does not expose `O_DIRECTORY`; the corrected implementation uses `fstat` instead. Sync errors abort. JVM tests exercise the same file ordering with a JVM directory-sync adapter. Compilation and injected failures do not constitute physical power-loss or Android filesystem certification. Whole private-directory rollback/deletion by an external privileged actor is outside this store's durability proof.

## Fixed second-PID fixture

`NativeNaturalStoreProbeService` is debug-only, not exported, in `:p9NaturalStore`, and verifies the acceptance package, exact explicit binding Intent and same calling UID. Its only bound input is one canonical `p9NaturalStoreRun`; its three named fixture transactions are hold lock, release lock and claim that existing fixture. Transaction bodies contain no path, original override, key alias, method name or arbitrary payload. It cannot address the live store. Unbind/destruction closes a held fixture lock; it does not stop another process, delete files or replay a claim.

The debug manifest already has a sound-witness visibility query in the frozen parent. `natural-permit-store-manifest.patch` preserves it and adds only this Service. Review `natural-permit-store-hooks.json` for exact base/proposed hashes. Apply that narrow patch when the parent already has its manifest, then add the independent Kotlin/test files; do not overwrite the parent manifest with this older branch's added-file representation. A changed manifest base requires a fresh merge/review. There is no Main/Session/NativeSessionStorage patch.

## Checks and native queue

Run from this checkout, using its private toolchain file and existing dependency cache:

```sh
GRADLE_USER_HOME=<existing-private-cache> node scripts/mobile/check-chat-notification-natural-permit.mjs --suite model
GRADLE_USER_HOME=<existing-private-cache> node scripts/mobile/check-chat-notification-natural-permit.mjs --suite store
```

Model64 passes. Store17 passes with actual JVM AES-GCM, file creation/permissions/link checks, file/directory sync and FileLock across two real child PIDs. One child commits, the other refuses; after both exit a fresh reader sees the original consumer and cannot claim again. Other checks cover missing/wrong key seams, interrupted arm, copied ciphertext, permission refusal, all three claim failure boundaries, unknown/sidecar/symlink/hardlink/tampered/partial files and same-process contention. JVM key/install/OS observations are explicit synthetic seams. They do not certify AndroidKeyStore or a native process.

Parent schedules each method of `com.agenthub.android.NativeChatNotificationNaturalStoreTest` separately with canonical `p9NaturalStoreLease` and a **fresh `p9NaturalStoreRun` per selector**. Missing arguments produce an assumption skip and must not count as acceptance. Require the exact independent acceptance app/runner pair, existing installation AES key, no Main Activity, no incomplete old cold router and no live natural permit namespace. No old/unknown lease is modified to satisfy preconditions.

| Native selector                                     | Actual boundary intended                                                                                                                            |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `existingKeySealsClaimAndRetainsOriginalCiphertext` | Existing AndroidKeyStore key, actual installed pair/UID, authenticated readback, same-process duplicate refusal, original encrypted files unchanged |
| `missingKeyNeverCreatesAliasOrPermitDirectory`      | A fresh test-only absent alias is never created; no fixture directory/lock; original installation alias is untouched                                |
| `interruptedClaimAndReadbackNeverRearmOriginal`     | Fault after empty claim fence, after file/directory sync and before readback; all leave original files and refuse a fresh claimant                  |
| `unknownAndTamperedFilesRemainUnchangedOnRefusal`   | Own fresh unknown/tampered fixtures remain byte-identical after rejection                                                                           |
| `otherPidLockAndSingleClaimKeepOriginal`            | Real other-PID/same-UID bound Service holds the lock; contender refuses; only the other PID commits; subsequent original claimant refuses           |

Each method records bounded private proof under `files/acceptance/P9-natural-store/<lease>/`, with run/case, PID, API, failure type and fixture result. A bounded raw-hash inventory verifies the default `mobile-v1` directory remains unchanged. No Main/default storage, OS post/click, permission prompt/mutation, Host/model, new identity or input is invoked. Test-only interruption changes only newly seeded owned fixtures and leaves their encrypted evidence for the parent. The absent alias is never generated/deleted; the original AES alias is never replaced. The Service binding is released in finally, without killing its process.

## Integration and recovery index

This stage provides callable storage, typed committed readback and native tests, but installs no natural permit and does not make the old cold hook accept an OS-created process. Remaining gates are trusted original/OS arm integration, asynchronous pre-selection claiming, actual post-restore completion proof, independent UID SystemUI/token/absent-process observation and parent native execution. The existing instrumented-reader OS three roles remain pending with their original first-Activity scope.

Tree-external delivery contains `handoff.json`, exact parent/overlay input hashes, compile/APK fingerprints, incremental `source.bundle`, standalone changed-source archive, frozen source restoration index, compressed failed/pass logs and cleanup/retention receipts. Source recovery starts from `d1cc8aed` plus the verified overlays; the branch recovery bundle requires `5b9b402c`. No build cache is needed as acceptance evidence.

F52/F53, UI11 and S15/S16 remain the mappings. This is Android debug acceptance infrastructure; iOS source and verification are unchanged. A6 and task/project work remain paused. Full DEV, natural OS creation, physical OEM behavior and actual permission integration are open.

Selected driver lint, document formatting and diff checks pass. This older preparation branch's `pnpm check` passes format, GitHub sync5 and lint, then stops at its existing `synthetic-tone.m4a` central coverage gap; later checks are not counted. The parent's merged PR51 CI belongs to its own source and does not replace this result.
