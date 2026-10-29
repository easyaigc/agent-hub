# P9 fixed-command completion witness

Operation `parent-p9-command-completion-witness-20261029-1`, following `85c478e1`. Parent's review identified that positive self/system rows or a complete OWN instrumentation record could still be a transport prefix ending at a valid line boundary. Clean EOF alone did not prove the original command finished. No natural first click has been consumed by this workline, and this change does not rerun an old original.

## Framed completion before parsing

Each diagnostic now gets a fresh canonical UUID **per invocation**, distinct from the immutable notification lease/attempt. The only admitted diagnostic operations remain `PS` (`ps -A -n -o UID,PID,NAME`) and `AM` (`dumpsys activity processes`). A fixed `/system/bin/sh` process receives a script generated exclusively from that closed enum and the validated UUID. The script runs one fixed read-only command, captures its immediate exit status, prints exactly one terminal line `P9_DIAGNOSTIC_END_V1:<kind>:<nonce>:<status>`, then exits with that same status. There is no caller-supplied command, argument, shell body, path or environment and no arbitrary method proxy.

The observer drains stdout and stderr concurrently through `UiAutomation.executeShellCommandRwe`; stdin is closed after the fixed script. Both output channels **and the terminal marker together** share the existing 262,144-byte cap. Whichever drain finishes/fails first is observed first, so an over-cap stderr cannot leave the code waiting on a blocked stdout pipe. All returned descriptors and drain resources are closed independently on refusal. There is no new command deadline, configurable grace, reset or extension of the existing 120-second custody/producer and 30-second post-custody windows, and no command retry on an unknown result.

Capture acceptance requires strict UTF-8, no stderr bytes, a unique marker prefix anywhere in stdout, the exact invocation kind/nonce, canonical status `0`, and that exact marker as the last bytes. Missing/duplicate/stale/wrong-kind markers, nonzero or noncanonical status, any bytes after the marker, partial marker, budget overflow or diagnostic errors refuse. The native code never treats an absent marker as an empty table. Status0 is the fixed command's shell status; it is not represented as a cryptographic attestation of the operating system's process model.

`NaturalProcessVisibility.Completed` has a private constructor. Only the completion validator can construct it, and both `ps` and `am` now require that object with the correct diagnostic kind instead of an unchecked string. Thus the existing positive parsers cannot accidentally consume an unverified complete-line/record prefix. All self/system/writer checks remain after completion validation.

## AM section end and existing joins

The active-instrumentation section ends either at the next global AM section or at the **verified successful whole-command end**. The latter is explicitly recorded as `verified-command-end`; raw EOF at `mArguments` has no such authority. If the actual command had a later target record but transport stopped after OWN, it cannot provide that invocation's final marker and is refused before target absence is evaluated. Existing malformed/incomplete instrumentation-record, target-owner and current self component/PID/UID checks remain.

Ready/custody/click/result visibility evidence adds both command nonces, `commandExitStatus=0`, `uniqueLastCompletionMarkers`, `stderrEmpty`, and the AM section boundary. Raw global process/AM tables are still not exported. The original fixed single ordered custody and live-writer-before-acknowledgement witness, all exact install/lease/attempt/PID/nonce/boot/receipt/draftHash/claim joins, one SystemUI click and no replay/force-stop behavior remain unchanged. The five common patches, other debug/release facade sources, and original scanner relocation/caps remain byte-identical to `85c478e1`; parent still owns Main/Storage and its source34 Binder repair.

## API boundary

Separate stderr through [executeShellCommandRwe](<https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String)>) is public from **API34**. Therefore this strengthened observer and its producer selector explicitly require **API34+**. There is no weaker fallback on API31–33. The App's earlier API31 acceptance proposal and five common patches are unchanged; a complete strong natural proof on API31–33 remains unavailable here. API26 natural coverage also remains open. Parent's proposed fresh API37 is within this harness boundary, but has not been executed here.

## Auxiliary evidence only

`node scripts/mobile/check-natural-process-visibility.mjs` now passes 66 checks: the prior 43 parser vectors, 19 completion/type-boundary cases, and four host POSIX-shell pipe checks using fixed fake `ps`/`dumpsys` functions. No real host process enumeration, Android diagnostic, device, product Host, model or microphone is used. The pipe checks run the actual generated script against synthetic output to confirm status0 delivery, nonzero refusal and stderr refusal; they do not exercise Android's UiAutomation transport.

New cases include complete self/system line-boundary and complete OWN record-boundary truncation of a longer original; missing/duplicate/wrong nonce or kind marker; missing final newline, trailing bytes, nonzero/noncanonical status, stderr and combined cap; malformed nonce input; typed cross-kind rejection; and both explicit AM section-end modes. A passing synthetic capture never counts as native command completion, process visibility or natural first-open evidence.

The private combination rebuild and exact source/package hashes are delivered outside the worktree. Actual API37 command completion, ordered handoff, SystemUI/native process proof, after-consumer-exit authenticated inspector, supplier/physical DEV and API26/API31–33 strong natural coverage remain pending. A6 and task/project business remain paused. No devices, Host, other chats or original notification inputs are operated by this workline.

| Verified private proposal artifact        | SHA-256                                                            |
| ----------------------------------------- | ------------------------------------------------------------------ |
| Atomic tuple, with unchanged five patches | `9852bd84d3e4fb50fb8431fcd6fe493f30289278e5d9e5f3d627011a8618f3d0` |
| Effective 727-file source map             | `f497fb0b13465fdcaae1fb382c2e2db603ddda653c5f573ad152a8a7c116cedc` |
| Debug App                                 | `867b9d00cc7ea4ad23a5475db2060e38c40db60a156d8227f0942e299df4e6c8` |
| Debug runner                              | `1e9b99c2508813ac997c3e28674b9b84fd3449cea738d1b9bbf2c49a3470515b` |
| Self-target examiner                      | `a6223766bb8dfddd2b486acbbd25be765bc18d848ae65525b4b3d0f634b68e8b` |

Debug App/runner/examiner and release Kotlin compilation pass with 122 Gradle tasks. All three APK ZIPs/v2 signatures and same-signer checks pass. Only the completion parser, observer transport and producer SDK guard differ among native inputs; exact three-file delta/readback and the prior restoration chain are retained. These are independent source32-based proposal packages, not a rebuild or replacement of the parent's current `5a6288e3`/source34 runtime. `pnpm check` passes format, sync5 and lint, then stops at the pre-existing `synthetic-tone.m4a` parity coverage gap; later checks are not counted.
