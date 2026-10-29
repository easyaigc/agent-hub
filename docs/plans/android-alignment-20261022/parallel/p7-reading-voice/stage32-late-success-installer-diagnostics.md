# P7 stage32: fixture repairs, actual late success and installer observations

Two independent commits cover the panel fixture and the unchanged real installer's diagnostic harness. The child runs SDK/pure-host checks only. Parent owns current-source integration, new candidate fingerprints, device leases and native execution. A6/task/project business remain paused. No device, Host, microphone, model download, preload or model-tree copy is performed here.

## Panel fixture and honest coverage

The parent ran Source44's original five UI methods and observed five failures. They remain failures. The fixture originally saved a `synthetic-certificate` while the actual VoiceDraft certificate was null, so strict restore did not find the recording. Creation now uses `draft.certificate`; setup requires exact restored original ID and `canInspectSavedFile`, rather than treating any FAILED state as recovery. The production certificate guard is unchanged.

Cleanup previously zeroed the saved WAV baseline and the UI After then compared against it again. Cleanup now performs its last complete original/row/WAV/metadata comparison, returns that proof, and only then zeroes the baseline in finally. It never repopulates expected bytes from a current read. The UI After consumes the returned proof once, attempts cleanup for every fixture, and records primary test failures and cleanup failures separately. The actual overflow guard is retained; immutable real TextLayoutResult diagnostics are written before assertions. The earlier unmatched-record status overflow is not claimed fixed by the certificate correction or by compilation; the restored status needs a new native measurement.

The existing checkpoint/error gate still proves cancellation/join/no result. Its historical `...RejectLateSyntheticText` name does not establish a successful late return. Two additional native UI methods require a real synthetic SavedVoiceText return and a wrapper that actually receives it before forwarding it to the production controller:

- `actualSuccessfulEngineReturnAfterOwnerInvalidationCannotStageOrFill`
- `actualSuccessfulEngineReturnAfterEndWaitingCannotStageOrFill`

The gate deliberately omits its checkpoint in this success branch. The production post-return owner or generation/cancellation guard must reject staging and fill. Busy locale changes remain rejected; the test does not mutate private state to create an impossible locale transition. Zero successful or forwarded returns fails either new vector. Chinese/English × light/dark, actual OS fontScale2, exact original/row/all WAV bytes/metadata, permissions and zero Host/microphone factories remain required. The original five and additional two are separate native gates; two pure-host coroutine checks establish only the synthetic gate contract.

## What the real installer actually does

The inspected unchanged `VoskSavedVoiceSpeech.install` obtains the process-wide model lock, checks actual native availability, validates its fixed manifest entry, reclaims only owned staging, and checks disk space. It uses the fixed official HTTPS URL with redirects disabled, 15-second connect/read timeouts, exact-or-unknown Content-Length followed by exact final archive byte count, and bounded 64KiB reads. The same 180-second timeout covers download, archive SHA, ZIP allowlist/size checks, extraction, expanded per-file SHA verification and atomic publication. Stage cleanup is in finally. The model lock excludes live decoders while replacing the cache; this cache path holds neither original audio nor credentials.

The parent's Source42 evidence reports two API26 time-budget failures, at 180329ms and 180645ms, with actual worker/resource/original safety preserved. Their last phase was only `explicit-download-before`; whole-UID receive deltas of 2688414/3497974 are not exact ZIP progress. They do not isolate download, hashing, extraction or publication as the blocking subphase. API37 official install/engine cases and API26 independently verified model preloads are separate evidence; preloads do not establish API26 installer acceptance. The observed page size is 4096, not a 16KiB-runtime pass. API26 IPv6 denial/privacy remains open. No observed linkage/API exception or confirmed API26 source defect has been established, so no production fix is proposed and no network/SDK fault is guessed.

## Minimal owned diagnostic, without changing the installer

The new finite method is:

`com.agenthub.android.NativeRealSavedVoiceSpeechTest#explicitOfficialInstallRecordsActualPhasesAndByteOffsets`

It claims the new closed `install-trace` case in the existing three-argument lease contract. Parent must use a new candidate-bound canonical lease, add `install-trace` to allowedCases, provide the same declared TTS input/original prerequisites and start with an empty private model cache. The real default Vosk/JNA factory, fixed official URL/SHA, no-redirect policy, bounds, cancellation, original checks, model lock, FD/map proof and 180-second timeout remain unchanged. No ZIP/model preload or arbitrary URL/path/method is accepted. A successful method requires actual install return, real production probe and every pinned installed file; it is still separate from overall privacy/full DEV.

The observer opens neither a URL nor model/ZIP/audio content. It records a bounded JSONL journal under the already owned case folder, at source-site changes and roughly one-second samples:

- Actual self-FD target/access/position from `/proc/self/fd` and fdinfo. A target must match before and after reading its position. Close/reuse races are not attributed.
- Current ZIP-file length and expanded file count/bytes, separately from whole-UID traffic counters. Unsupported UID counters stay -1.
- Actual worker source frames, monotonic elapsed observation timestamps, observation cost and checkpoint-observer overhead.
- Archive write scope, expanded write scope, expanded read/verify scope, and archive hash when the observed stack resolves hashFile. Archive reads without that stack remain hash-versus-ZIP-index unresolved.

These are real I/O-scope observations, not instrumented enter/exit hooks. Snapshots are not atomic filesystem transactions. Phase times are observed points/brackets; brief/unobservable transitions, publication versus cleanup between the last checkpoint and actual return, and proc/FD permission failures remain explicitly unknown. A missing FD observation is never changed to zero. Final publication is established separately by actual API return and the production probe/pinned inventory. This local observer cannot diagnose DNS/TLS/server throughput by itself or attest HTTP/upload/microphone absence.

The observer has one owned daemon thread and a maximum 512 events/2MiB; it is joined after actual installer worker exit, including failure/cancellation. Budget exhaustion or observer error is recorded and makes diagnostic acceptance fail after the actual worker exits; it does not replace the installer timeout or prematurely abort a download. Failed installs retain the journal, summary and explicit after-API-exit UID sample. The existing timeout's failure remains visible. No archive contents are retained or copied by this harness; only the installer's temporary owned ZIP exists until its unchanged finally cleanup. Parent keeps its existing sole verified ZIP per language and does not substitute it for the official-install gate.

Run the method once per fresh API/locale lease; never run a whole class/package or replay an old claim. Preserve original inputs, exact failure proof and bounded journal before any lease-runtime release. Final model-cache release uses the existing active-lease selector only after actual API/FD/map exit. Parent updates the overall parity/acceptance tables and separately reruns the repaired UI, real installer, privacy and full DEV gates. This source delivery does not reclassify any Source42/44 failure as passed.
