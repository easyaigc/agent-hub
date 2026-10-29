# Observed API37 synchronous transport metadata

Operation `parent-p9-api37-observed-flags18-narrow-fix-20261029-1`. Parent's Source43 receipt now identifies the actual first `extra-int` error: caller flags0, server `P9_NATURAL_PROBE_FLAGS code=1 flags=18 pos=0 size=112 avail=112`, the preceding same-UID guard passed, with complete unchanged fixture inventory. Thus this transaction failed before interface/payload inspection; no extra payload was accepted. The old Source42 and Source43 store5 results remain failed. Parent reports its new Source43 upgrade read-only selector passed with preserved original bootstrap/default/UID/certificate/boot; that proof belongs to the Source43 tuple, not to the new candidate.

## Exact metadata decision

The locked API37 SDK source hashes are preserved in the index. The official [BinderProxy](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/BinderProxy.java) conditionally adds the [IBinder AppOps metadata bit](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/IBinder.java)0x2 to a synchronous transaction; [Binder](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/Binder.java) forwards those flags to `onTransact`. Native [IPCThreadState](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/libs/binder/IPCThreadState.cpp) adds `TF_ACCEPT_FDS`0x10. The earlier fixed-caller observation of0x12 is now consistent with both layers. Moving upstream URLs are explanatory references; the local locked SDK and Source43 actual receipt are separately fingerprinted. The cited native branch is not claimed as the emulator binary identity.

The pure debug helper admits **exact values**, with no bit clearing, normalization or subset mask:

| Runtime API  | Incoming flags admitted               |
| ------------ | ------------------------------------- |
| Below26      | none; unsupported profile             |
| 26+ except37 | existing0 and0x10                     |
| 37           | existing0,0x10 and newly observed0x12 |

Standalone incoming0x2, every value carrying oneway, unknown application bits, extra composites and0x12 on API30–36 or38+ refuse. This phase does not extrapolate a verified API37 receipt into broader native compatibility. `TF_ACCEPT_FDS` is transport metadata, not a grant to carry an FD: after the unchanged same-UID/flags/nonnull-reply checks, the service now explicitly refuses `data.hasFileDescriptors()` with guard `FDS`, before interface parsing or any store operation.

Flags do not attest who introduced an individual bit. The service remains a non-exported, same-UID, acceptance-only debug fixture with a closed action, descriptor, code and empty application payload. Ordinary synchronous test calls still explicitly send flags0; the only additional caller flags are fixed negative test inputs. No arbitrary flag argument is exposed to an external API. The old descriptor check, code whitelist, zero-extra-payload check, lock ownership, one claim and original inventory assertions remain.

## Precise patches and native refusals

The hooks index gives exact Source43 base/proposed/patch hashes for the service, StoreTest and diagnostic helper. Add `NativeNaturalProbeTransportFlags.kt` only to the debug source set. The diagnostic helper change admits the fixed `FDS` reason when displaying an actual exception; it changes no acceptance assertion. The workline fixture mirror matches that proposed helper hash. No parent or actual shared Android file is edited by this workline.

All five original synchronous refusal calls and their exact type/`expectedGuard`/inventory assertions remain. Two new fixed synchronous application-bit inputs0x100 and0x40000000 must produce FLAGS refusal. A short-lived, no-data pipe contributes one real Parcel FD; it must produce FDS refusal and both descriptors close with nested `use`. No FD is read by the service. These are additional component cases inside the existing store5 selector, not extra product commands. A complete successful case now carries **eight** synchronous refusal observations: PAYLOAD×3, INTERFACE, OPERATION, FLAGS×2 and FDS. The parent checker must retain the original five exact kinds and also require these three added results; it must not silently trim back to five.

The original separate oneway input remains diagnostic-only with no remote acknowledgement or execution-success claim. The JVM policy checks reject all incoming values carrying its bit. The normal HOLD/Busy/RELEASE/one CLAIM sequence, other PID/nonce join, single committed claim and original file comparisons remain unchanged. No new timeout, grace, retry or operation is introduced: the existing120-second case and15-second bind limit remain. All observations are saved before the existing refusal assertions, so an unexpected lower-level transport failure is retained rather than relabelled a successful server guard.

## Verification and next native phase

`check-natural-probe-flags.mjs` compiles the **actual debug helper** with the cached locked Kotlin/JDK toolchain. Its host JVM run checks983,190 exhaustive/edge decisions across API boundaries, all16-bit values and signed/high-bit variants, plus focused cases for legacy0/16, API37-only18, standalone2 and oneway/unknown composites. It does not load Binder, Parcel, an Android service, a Host Agent or a device. Native FD, descriptor, payload, UID/reply, process-lock and once-claim results still require the parent's controlled runtime.

The private build overlays the pinned Source43 inputs, applies only the three exact patches and adds the one debug helper. Debug App/runner/examiner plus release Kotlin compilation and exact source/recovery readback are checked separately below. This is auxiliary evidence, not a claim that store5 now passed. The private rebuild has its own hashes; it never changes the parent's existing installed tuple or original intent.

Parent's next phase must freeze its own unified current source/APK/signature tuple, upgrade preserving data, run a fresh upgrade-read-only plan and then use a fresh store lease/run for all five cases. The plan's **original** tuple remains the Source42 bootstrap writer from the immutable original receipt; Source43's intermediate reconciliation is not a replacement original. Current hashes and a fresh upgrade-run identify the new candidate. Pin the original device proof and check actual bootId/count/serial before and after. Do not replay either failed store sample, initialize another bootstrap, clear data, modify old intent/receipts or consume old natural IDs. Only after these gates may parent create a new natural lease/attempt and perform the first original SDK/SystemUI click. No native/device/ADB/Host/model/notification action was performed here. Low-API strong observer coverage and full P9 remain open; task/project/A6 remain paused.

Final auxiliary evidence: all122 requested Gradle tasks pass; the754-input map matches recovery readback, with exactly four native changes versus the753-file Source43 compile base (all743 expanded candidate inputs match). The incremental archive stores nine changed files against the previously verified Source42-derived proposal, so no continuing dependency on the parent snapshot is required. All three independent APK ZIPs/v2 signatures and the common signer verify. `pnpm check` passes format, sync5 and lint, then stops at the existing `synthetic-tone.m4a` feature-coverage gap; later checks are not counted. Independent compile-only APKs are retired after checks; exact metadata and source recovery remain outside the worktree.

| Identity                        | SHA-256                                                            |
| ------------------------------- | ------------------------------------------------------------------ |
| Atomic proposal                 | `abe601ce517bad89fa9ab66a1bd6b526d6da00b43c10f1f55e694965554de8e9` |
| Full compile source map         | `15f56827ad0e83f880b8e924faf42175f370d86c523de9fae5bfb0e16fb4afcf` |
| api37-flags18-service.patch     | `dc14db1b9067397b2014d36fb268b444827fdb768492b8bddf879f8ba155d8ce` |
| api37-flags18-test.patch        | `f7166c745360b3e820c0de47351c3cad64766d8fc003c60206d41783cb5e57ad` |
| api37-flags18-diagnostics.patch | `a60c901b1656e8849b3915f38a8a651310e97f242776d1131c33422595b8878e` |
