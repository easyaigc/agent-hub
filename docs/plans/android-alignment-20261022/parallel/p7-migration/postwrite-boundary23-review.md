# Post-write boundaries 2 and 3: source review and parent execution contract

This is preparation, not native acceptance. The existing postwrite Kotlin fixture already implements both boundaries; no production or native test change is needed. Its two files and the old three-file Session suite remain unchanged. The parent owns candidate selection, installation, device lease, privileged executor, termination and native results. This stage does not operate a device, Host, recognizer or microphone. P7 remains open; task/project work and iOS A6 remain paused.

## Persisted and volatile state

| Fact at the held point                         | Boundary 2                                                                                        | Boundary 3                                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Real encrypted `metadata` write                | Complete                                                                                          | Complete                                                                               |
| Product exact metadata readback                | Complete inside the storage executor, before its completion/digest returns                        | Complete, digest checked by the destination                                            |
| Accepted text and new revision on disk         | Present                                                                                           | Present                                                                                |
| Actual Session memory                          | Original text/revision; publication has not run                                                   | Accepted text/revision; `owner.publish` succeeded                                      |
| Destination receipt                            | Plan contains a receipt in memory, but it has not returned to the controller; observation is null | Actual validated receipt returned by destination, preserved in the private observation |
| Original row, whole original JSON and full WAV | Unchanged                                                                                         | Unchanged                                                                              |
| Local-result catalog and original action       | Same pre-staged action, `applied=false`, destination=null                                         | Same pre-staged action, `applied=false`, destination=null                              |
| Catalog applied write                          | Not reached                                                                                       | Concrete encrypted result port has not been invoked                                    |
| Controller completion/clear                    | Not reached; busy and active                                                                      | Not reached; busy and active                                                           |

The source chain is `VoiceInputController.savedFileOperation` → `NativeEncryptedVoiceDraftDestinationPort.accept` → `NativeSessionStorage.writeVoiceDestinationMetadata` → `MobileSession.voiceDraftDestination.publish` → controller `owner.applied` → `NativeEncryptedVoiceLocalResultPort.applied`.

Boundary 2 holds storage checkpoint 2 after equality readback, while the serial worker still owns its I/O lock. It is not a readback failure or an acknowledgement already returned. Boundary 3 delegates pending/stage to the real result port, then intercepts its applied call before the concrete implementation. The genuine destination has already published Session memory and returned its receipt. This is not an interruption halfway through an encrypted catalog write or proof of a natural crash. The gate observes synchronous reentrant storage reads; it must not suspend onto another reader while holding that lock.

The fixture starts from fresh synthetic original recordings and stages an existing synthetic transcript before the destination attempt. It does not recognize acoustic input. Its “old original” means the same complete pre-action recording kept across this controlled run. It does not cover arbitrary historical production recordings or replay their attempted/submitted operations.

## Allowed readers and complete assertions

The first reader opens only the same existing namespace/key and constructs no Session. Its source access is read-only: it compares the entire ciphertext tree to the writer observation and before/after, exact accepted metadata bytes/hash/revision, full persistent original row, canonical whole original JSON and every WAV byte. It validates the original manifest, fingerprint, owner, staged action, locale/model and catalog; boundary 3 also strictly decodes and validates the real receipt's action/original/owner/source revision/accepted revision/text and metadata hashes. It writes its own immutable claim/completion and private evidence, so “read-only” describes source storage, not the whole instrumentation process.

The second reader performs an existing-key-only read preflight, then restores actual Sessions using those same keys. Session restoration/configuration and deliberate editing make this reader non-read-only. It must show accepted text without another probe/recognition/append or catalog terminal. Its private unmatched-original begin helper preserves actual Session admission, queued-write and FAILED-presentation waits. The accepted or later independent draft differs from the original text; exact scope/certificate/key/text matching must reject restoring that old recording. No assertion may edit the draft back to the original to manufacture a second apply.

Both owners must retain:

- The original namespace, key alias, original ID, draft/target identity, original text, complete original JSON (including historical account, attempts and receipts), exact stored row and complete WAV bytes/manifest. No ignored field subset substitutes for whole-record equality.
- The same staged result/action/fingerprint and catalog bytes, `applied=false` and destination=null. An observed boundary-3 receipt must never be injected into a product write to manufacture `applied=true`.
- Exact current metadata and revision across each explicit no-match action, then the later deliberately edited draft and its revision across another explicit action. No stale append or overwrite is allowed.
- Zero destination checkpoints during guarded recovery actions, zero saved-file probes/decodes, zero Host voice requests, zero answer sends and zero microphone calls. No download, recognition, automatic retry, original replay or new model task.
- Complete default metadata equality/hash across all three native roles, default ciphertext inventory equality at parent checkpoints, and exact microphone permission/AppOps. Empty defaults only prove empty-to-empty equality.

The inspector's source ciphertext equality ends before the continuation. The continuation is allowed to persist its intentional newer draft and normal Session state; it cannot claim the whole source tree stayed unchanged. Original/audio/catalog bytes and exact metadata around each guarded action still must remain stable.

Already-applied catalog entries and unknown outcomes are not “pending work to apply.” Existing production `pending` rejects an applied original; `restore` excludes resolved/discarded originals and requires exact text. This fixture is limited to its staged pre-terminal state. Any contradictory applied entry, missing original/key, new scope/certificate/version, claim without completion or caller-unknown result requires reconciliation of the same intent and receipts. Do not restart a writer/reader or repeat the original action.

## iOS assertion relationship

Read-only source inspection of `apps/ios/Sources/VoiceInput.swift` shows pending restoration requires the same target, scope and original text, retains the recording operation ID, and exposes an explicit retry without automatic submission. `apps/ios/Sources/RootView.swift` protects composer and non-secret question publication with target and original-text checks; question publication additionally requires editable approval authority. The existing original-recording DEV test in `apps/ios/Tests/CodexManageUITests.swift` checks original unsent text across restart and explicit restored-recording controls.

These support the shared assertions of stable original identity, no automatic resubmission and no overwrite of a changed draft. They are source-level comparisons, not new iOS acceptance or equivalent Android process-death proof. The inspected iOS pending-store restore path can prune expired/orphan files; it is not a byte-preserving read-only inspector. No equivalent two-phase encrypted local-result receipt/catalog protocol was established by this narrow inspection. Do not infer iOS boundary2/3 parity, real acoustic recovery or cross-platform migration from it.

## Actual v4 capability audit

The immutable parent v4 driver SHA-256 is `3627ef9cc5e3fe789bf0b812120e3bde668e46b83c86a7e5fd5f7c3bed44bbf5`. Both argparse choices and `run` reject boundaries 2/3; its final success label hardcodes boundary1. Its `verify_ready` already handles receipt absence at 1/2 and presence at 3, and passes the requested boundary through all three native arguments. The Kotlin fixture does not need another implementation or selector.

Its `default_scope_proof` can compare nonempty inventories and present hashes. Actual execution is narrower: pinned V2/V3 reconciliation requires the historical empty default inventory, source and boot, and `run` compares the live preflight against those inventories. V4's successful boundary1 outcome also has zero files and null native metadata hashes. Thus nonempty input is rejected before a writer; helper vectors must not be reported as nonempty native acceptance. Do not seed, erase, copy or restore default data as part of this preparation.

The parent v4 success establishes one controlled DEBUG API26 emulator termination and two normally passing readers. Prior V2/V3 harness failures remain failures. The reviewed source/App/runner pins and original private evidence are recorded separately; no native result is upgraded by this preparation.

## Minimal new executor proposal

The private archive contains an unapplied `executor-v4-to-v5.patch`, full `execute.py`, offline vectors and exact hash records. Proposed driver SHA-256: `ba1801082cc5e14baef85a7412a8ac80815275e150bbe2d382858fe0db94922a`. Patch SHA-256: `40cfc8d409535a9e416b862551738e5726a6989e8f7b06aac7d8d3c8f6b16b5b`.

The proposal only admits explicit boundary 2/3, labels the actual selected boundary, blocks all named historical/forbidden runs and consumes the exact successful v4 boundary1 outcome as another immutable predecessor. It retains the original failed-run receipts and requires the current candidate/default/microphone/boot to match. Twelve existing guard/executor/proof functions remain byte-identical. No PID, four-column UID, command-line, starttime, package, certificate, boot, ownership lock, expiry or clock threshold is relaxed. No timeout-driven signal, additional kill, writer/reader retry or result-unknown override is added.

The executor remains Source46-only, on its exact dedicated DEBUG API26 ranchu emulator and privileged same-AppUID `su <UID> sh` context. Its ability to read `/proc/uptime` and signal the exact process is not ordinary App-only SELinux permission, OEM compatibility or natural lifecycle proof. Source47 or another unified candidate is intentionally rejected by the pinned predecessor/runtime/candidate checks. The parent must separately prepare and review candidate/receipt repinning before using another source; do not substitute fingerprints in this archive or weaken historical checks. The proposed script expects the same relative archive depth as v4 and must have a new ledger/attestation directory.

Nonempty default acceptance requires a separate parent-approved, dedicated baseline: stable existing nonempty encrypted default content, a native complete-metadata/hash proof, full inventory and microphone/AppOps/boot/candidate attestation before writer, and a new baseline receipt distinct from historical empty failure reconciliation. Compare historical failed receipts against their historical candidate, and the new baseline against the actual new candidate; do not relabel or alter old receipts. That extension and native seeding are not implemented here.

## One-time parent plan

Two new UUID plans are saved privately, one for each boundary, in PREPARED_NOT_SUBMITTED state. They contain no executable authorization here and no automatic sequence. The parent selects a reviewed candidate and dedicated released emulator; the physical Android remains assigned to EasyWork. Existing writer, namespace, claim, intent or completion rejects reuse. In particular, the previously forbidden run remains forbidden and must not be rebuilt or replayed.

For each boundary independently:

1. Pin exact source/expanded source, App/runner bytes and signatures, executor/runtime hashes, package/UID, AVD/SDK/ABI/hardware, boot, active-instrumentation absence, baseline and lease. Resolve every prior unknown run before creating a new immutable execution intent. No install or cleanup is performed by this child.
2. Start the writer once with that plan's fresh canonical UUID and selected boundary. Both owners must be held, active and busy. Boundary2 memory must still be original; boundary3 memory must be accepted. Require no writer-ended marker and verify input/intent/observation hashes, complete default proof and one shared PID.
3. Preserve the original final command guard: same exact PID/comm/starttime, all four target UID columns, command-line binding, same boot/source/APKs/certificate, heartbeat at most 3 seconds old, ready at most 20 seconds old, and before earliest expiry. Python's tighter pre-signal checks remain unchanged. Do not increase any allowance. Save one immutable kill intent, issue at most one exact-PID signal, then prove original process disappearance and actual writer instrumentation exit. No force-stop and no normal writer JUnit-pass classification.
4. Preserve data and keys. Run the read-only-source inspector once, then continuation once only after successful inspection. Each must run under a different PID from the writer and produce one normal passing method. Preserve claims/completions, native raw outputs and all full-byte proofs; repeat environment/default/microphone checks. The two readers may share a PID only if the execution model permits, but both must differ from the writer; record actual identities rather than infer them from relaunch.
5. On expiry, timeout, normal-ended marker, signal refusal, PID mismatch, missing completion, applied/unknown catalog or failed reader, preserve that run as failed/unknown and reconcile its original evidence. Never replay a used plan, source operation, reader claim or signal. A fresh boundary3 plan is not permission to bypass unresolved boundary2 work.

Boundary2/3 results are separate from boundary1, natural crashes, full physical DEV and acoustic/JNI gates. No full P7 closure follows from this preparation.

## Auxiliary verification

The private review records 11 exact Kotlin bodies matching the previously compiled frozen candidate, including both postwrite files, old three fixtures and the actual production path. Matching reuse is auxiliary compilation evidence only; no repeat build, APK or Source47-wide compile is claimed. Three iOS source hashes are recorded for source comparison only.

The proposed driver passes 128 host-only vectors: the original 99 guard/JSON/POSIX/default/readiness/reconciliation vectors plus 29 admission/predecessor/CLI/boundary vectors. The latter include a fake-Device orchestration check proving nonempty defaults reject before any capability/writer call and an unresolved local outcome blocks another boundary before Device creation. Receipt-shape host vectors do not replace native strict receipt validation. No real `su`, device, signal, native method or model ran. Existing v4 and run artifacts remain unchanged.
