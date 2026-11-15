# Historical rows through the original-owner transition

Scope: F17/F31, UI05, S02/S11. This is a Host projection repair shared by Android and iOS. It changes neither model submission nor desktop history files.

## Proven cause and repair

The persisted reader qualifies reconstructed ordinal IDs as `history-fallback:<turn>:item-1`. The original owner can also expose reconstructed `item-1`, but live projection previously kept it unqualified. `retainedDesktopHistory` only reconciled provisional-to-stable or stable-to-provisional content aliases; two provisional forms were retained together. In addition, the live projection index used the bare ID, so reused ordinals from separate turns could overwrite each other.

The Host now uses the existing Turn-qualified identity in full live projection, complete-history projection, bounded observations, initialization exclusion and reading-head projection. Retention compares these exact qualified identities, including previously cached raw aliases. Equal text is not an identity rule: different item IDs, different turns, stable IDs and unmatched tools/resources keep their existing preservation rules.

## Regression evidence

The new `desktop-known-owner-recovery.test.mjs` case invokes production `RuntimeSession.open`, original-owner IPC framing and projection with a synthetic owner. It seeds six qualified historical rows across two completed turns; each turn has two equal assistant bodies and reuses ordinal IDs. Before the repair, the same test fails with nine rows rather than six. After the repair it passes, also checking an exact item update, preserved same-text multiplicity and repair of a pre-existing raw/qualified pair. All 30 owner-recovery, live-window and stream-generation tests pass. The extended identity smoke checks cover both alias directions, missing Turn evidence, changed content at the same exact ID, separate ordinals, tools and existing resource boundaries.

## Physical Android read-only evidence

On a USB-attached Redmi Note 8 Pro running Android 11, the separate acceptance app ran `NativeHistoryTransitionLiveTest`. The scenario uses a dedicated existing test chat and the actual original-desktop reader transcript. An isolated Host is seeded in memory with that real transcript, then uses the real original owner. The native first read is gated to show the persisted transcript before releasing real Host events; three subsequent history refreshes use the normal reader. This explicitly controls the transition and does not claim the owner was freshly unloaded.

The seeded run passed: four canonical rows in their original order and with unchanged text, each once, after transition and after three refreshes. No command or model input was submitted. An independent original-desktop follower confirmed the user-input count remained two; a separate Host read confirmed all four original IDs and text digests. Native screenshots were inspected. The app APK SHA-256 is `64544110f71e086d15f4bf7dad586f94bfdf6e2460af0a4413f28347dfd6cd08`; the updated instrumentation APK is `96fd956d820a21a3052a8923cb0f0860105abd6f2219e51a4642d3f70e688943`. Product Android code did not change.

An initial attempt without priming the isolated Host failed when the event stream supplied control metadata with zero transcript rows. That failed setup record is retained privately; it is not reported as a passing run or erased by the later seeded result. A separate unseeded but explicitly primed read-only run also passed.

`NativeColdThreadLiveTest` was strengthened to assert every prior row survives exactly once and that the final count increases only by the new prompt and reply. That new send scenario was compiled but not rerun: the previous real cold-chat execution remains historical evidence, and this repair's true-device validation is read-only.

## Delivery boundaries

A later combined-candidate PR3 follow-up rebuilt the instrumentation and reran this four-row real-history transition on the same physical phone, using a primed isolated Host and the already-loaded owner. The three-refresh case passed again, and an independent desktop read confirmed unchanged IDs/text and two original user inputs. Its instrumentation APK is `535a69c9d7f5138c06acb3050710a508c3540491ea32bb00268cd61495715c15`. The same run also passed 17 physical synthetic-provider PR3 regressions and one real running-task observation. Exact scope and limitations are in [the PR3 follow-up](thread-filtered-events-20261113.md#pr3-physical-follow-up-with-the-identity-repair-2026-11-14); this primed rerun does not claim to repeat the earlier seeded Host-retention test.

Repository-wide `pnpm check` was executed. Its default parity baseline is absent in this public-mirror clone; the initial rerun with the actual `upstream/main` baseline passed formatting, ESLint, parity generation/contracts, workspace type checks/build, history boundary checks and runtime smoke checks, then stopped in `runtime-reconnect-diagnostics.test.mjs` because the synthetic desktop-runtime HTTP case had no fixed official CLI configured for the Windows metadata preparer. That original failure occurred during service construction, before history projection, and is retained privately.

### Windows CLI-path follow-up, 2026-11-14

The installed official CLI path was verified to exist and passed only to the test child through `CODEX_EXECUTABLE`; no product source, test assertion, formal Host configuration or service was changed. The entire reconnect-diagnostics test file then passed seven tests, failed zero and skipped three. The skips are the POSIX public-log permission case, a symlink case the current Windows user cannot create, and a Mac IPC recovery fixture. This synthetic HTTP test does not establish actual CLI/model execution or cold-owner recovery.

A fresh full `pnpm check`, using the same explicit mirror baseline and process-local CLI path, also passed that reconnect-diagnostics file. After 112 seconds it stopped later at `scripts/file-transfers-smoke.mjs:98`: Windows rejected creation of the test's file symlink with `EPERM`, before the corresponding upload assertion. No permission was elevated and no security assertion was removed. The complete suite still has not passed; subsequent commands in its chained script were not executed. Eight scoped implementation/test source hashes matched the recorded in-run and post-run snapshots. Separate targeted and full logs/results, plus both earlier failures, are retained privately. This follow-up changes the status of the original CLI-path blocker only, not the physical-device or delivery scope above.

Private proof includes source fingerprints, the real-reader seed, original-owner before/after checks, native screenshots and the initial failure. No credentials or real conversation export are committed. The isolated Host and USB forwarding are removed after testing; the formal Host configuration and original Codex processes remain unchanged. This is local source and Android DEV validation, not a production deployment, PR, cellular/Tailscale acceptance or iOS execution. Windows cannot run the iOS Xcode checks.
