# Bounded full AM capture after Source45 early failure

Operation `parent-p9-source45-observer-am-cap-real-failure-20261029-1`. Source45's original observer failed before ready with `combined diagnostic budget`. Parent did not start its producer, publish a notice, click SystemUI or read target private proof. Preserve that failed lease/attempt; it is not retried. A separate later read-only environment command produced401177 bytes of global AM output. That number is **not** the failed attempt's captured body and is not native proof of this candidate.

## Two exact capacities, same complete commands

The narrow parser/observer patches are against the unchanged Source45 examiner hashes in the index. The actual RWE capture, completion validator and parser agree on the closed diagnostic kind:

| Kind | Combined stdout + stderr cap | Command                      |
| ---- | ---------------------------- | ---------------------------- |
| PS   | 262144 bytes, unchanged      | `ps -A -n -o UID,PID,NAME`   |
| AM   | 1048576 bytes                | `dumpsys activity processes` |

Both include the terminal marker. The parent collector's2097152-byte hard cap is unchanged. AM has explicit room for the observed environment size without allowing an unbounded buffer. There is no grep, UID filter, row drop, partial-section capture, other diagnostic method or fallback. Complete global header/Active instrumentation parsing, all record/index/owner/process checks, positive self/system/writer controls, full target-UID PS set and exact original custody/first-click joins remain unchanged. The original five production hooks and passed eight refusal cases are untouched.

The two RWE output descriptors still drain independently and concurrently. A shared synchronized `ReadBudget` selects the kind cap, counts each at-most4096-byte read before buffering it, and refuses immediately when **combined** observed bytes exceed that cap. Completion order still observes the first failing drain so an oversized stderr cannot strand the stdout waiter. All descriptors and drain resources close independently. The completion validator separately checks the same kind cap; PS and AM parsers enforce their respective full-body caps. The historical `BUDGET` constant remains a PS alias for old auxiliary consumers; production captures use `budget(kind)`.

No native timeout, retry, grace or phase window changes. The original custody/producer and post-custody/UI/proof timing code remains byte-for-byte except for capture plumbing and evidence. The original per-command fresh canonical nonce, unique **last** kind/nonce/status0 footer, strict UTF-8, complete line/record structure, both actual EOFs and empty **independent RWE stderr** remain mandatory. No merged output is called stderr-empty, and no body is exported to parent logs.

## Byte/cap evidence on success and refusal

`diagnosticCaptures` records the latest PS and AM invocation separately: kind, command nonce, capBytes, stdout/stderr/combined observed byte counts, each EOF flag, independent-stderr availability and completion verification. Both EOF flags and byte-array/count agreement are checked before constructing a completed capture. `completionVerified` becomes true only after the original final-marker/status/stderr validator succeeds; it does not independently claim structural parsing passed.

The same ledger enters ready/custody/click/result visibility and early failure results. If reading stops on overflow or another failure before both EOFs, `byteCountsAreLowerBounds=true`; observed bytes are never reported as the unknown full command length. The read that detects overflow can account for its bounded buffer, then stops without storing it. A count beyond cap on a refused operation is not an accepted oversized capture. Only successful complete captures can support target absence.

## Counterexamples and compile scope

The auxiliary driver applies both patches in a temporary source copy and verifies base/proposed hashes. It reruns66 existing parser/completion/fake-host-pipe checks and32 new synthetic capacity/counter checks. Cases cover401177-byte and exactly1MiB AM captures with the complete self section; exact256KiB PS and one-byte overflow; a late target/writer record after a400KiB self arguments line; full AM truncation; duplicate/fake/nonzero/noncanonical footers; trailing bytes; invalid UTF-8; stderr newline/NUL/nonzero bytes both within and beyond the combined limit; typed cross-kind rejection; exact shared-counter boundaries, concurrent drains/overflow and incomplete EOF statistics. PS rows still obey the original512-character name rule; no parser assertion was relaxed to make a boundary fixture pass.

These are synthetic JVM checks, including the original fixed-fake-command host pipes. They are not an actual AM dump, Android transport, process-absence or natural-first-click result. The private Source45-aligned examiner build and full source-map recovery are recorded in the index; App/runner and their runtime roles are not rebuilt or exercised here. The parent must review the exact patches, freeze its own next source/APK tuple and run one new observer lease/attempt. A ready failure must not trigger a producer. Start one new original producer only after ready and the parent's original gates; do not re-dispatch the failed Source45 attempt.

RWE still requires API34+. API26–33 strong compatibility is separately pending; the unadopted older compatibility transport's uniform capacity must be rebased before reuse. This patch claims no low-API coverage or full P9 result. Task/project/A6 remain paused.

Final auxiliary result: examiner debug compile succeeds with33 Gradle tasks; the two changed native inputs are the parser and observer only. All750 pinned Source45 inputs match in the760-file compile base. Full760-input recovery readback matches compiled sources; a13-file incremental archive against the prior verified source chain also preserves the intervening Source45 input updates. Examiner ZIP/v2 signature and matching parent debug certificate verify. Its APK is independent compile-only evidence and is retired after checks. `pnpm check` passes format, sync5 and lint, then stops at the existing `synthetic-tone.m4a` feature-coverage gap; later checks are not counted.

| Identity                         | SHA-256                                                            |
| -------------------------------- | ------------------------------------------------------------------ |
| Atomic proposal                  | `5976c55330e80249d3b5beecc1eede86a62f8182ae0cc9dce46580b4e5a701d1` |
| Full source map                  | `d64910a149cd4dd85b3627446e8933e1b495a11b2ea6f7d248e1bb3c5a4b1a8e` |
| api37-am-capacity-parser.patch   | `28fdc0241b70ec03edefe291cfdd88a5a2f551af8c7d63af5c0e39701725600e` |
| api37-am-capacity-observer.patch | `9654f06ce42394a1665dcd7703f78ab4180ba8708f1afd095f38e95c30eb2236` |
