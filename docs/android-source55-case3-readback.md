# Source55 case3: original WAV readback and exact cleanup

Status: AndroidTest source tooling only. No device action, key access, WAV capture, cleanup or new Native acceptance is claimed by this change. Product behavior and UI are unchanged. Root owns aggregate P7/product/design acceptance records and candidate scheduling.

The fixed `source55-case3-original-v1` profile names one synthetic fixture from the retained method `unresolvedDiscardKeepsModelActionsGatedUntilExplicitReceiptRecovery`. It binds all 15 retained members, 16,641 regular-file bytes, exact API26 UID/device/inode/mode/link/time metadata, every regular-file hash, and the complete original strings. Source archive SHA-256 is `b9c3b45463fc836a251a35bf419eef84ce294a8f95b6197e0e6ca961be1728e1`; index SHA-256 is `a2d3c7f59988aa22e34e3ba7f64bcfe1acf354a5a496150b6196e20cc51c97f4`. Paths, aliases, namespaces, recording IDs and decryption names cannot be supplied as parameters.

The source proposal was reviewed against Root's `NativeSessionStorage.ExistingReader` at source hash `c4fbda6e31dc16d0dd5b4fda66ee599ae5dddf565ac78823aeeddc475eef089e`. Root's current P7/P9 composition is `c62749593ac907a43149b37e4cc4d7a8d2a18cad7d79ef740afb06a17a4074ed`; it preserves the fixed existing-only `open`, `text` and `binary` entry points. Full Source62 SDK compilation now passes for this composition; actual Native readback, parent capture and cleanup acceptance remain pending. The P7 branch does not contain this separate Root implementation. This patch adds no production reader, key creation path or generic debug API. Root must compile the adopted patch against its exact source candidate before scheduling it. API-signature compilation is auxiliary evidence only.

## Preconditions and opt-in

Root first independently confirms the original worker actually exited, the platform worker/lock state is resolved, and no original store or migration owner is active. A Java exclusive advisory lock on the existing zero-byte raw-writer lock excludes cooperating raw writers. Its descriptor is opened RW without CREATE, truncation or writes; every source data descriptor is read-only. This is not protection against arbitrary hostile code running as the same UID. The independent quiescence prerequisite must not be replaced with a runtime timeout or persisted-history observation.

Only the acceptance application and its exact test package, API26 arm64 fingerprint and UID 10076 are accepted. Root supplies and independently reviews the current candidate APK and runner hashes; the tool reads the actual installed APK bytes and compares them. These hashes are not the old Source55 APK pair. The full SDK/candidate/environment fingerprint remains Root's responsibility.

There are two individually selected JUnit methods in `NativeCase3OriginalReadbackTest`. Normal suite execution skips them. Root must select exactly one method through the standard instrumentation `class` argument and provide these exact custom keys:

| Key                       | Value                                                         |
| ------------------------- | ------------------------------------------------------------- |
| `p7Case3Phase`            | `read` or `cleanup`, matching the selected method             |
| `p7Case3AppSha256`        | Root-reviewed current installed APK SHA-256                   |
| `p7Case3RunnerSha256`     | Root-reviewed current installed test APK SHA-256              |
| `p7Case3ParentQuiescent`  | `original-worker-exited-no-active-store`                      |
| `p7Case3CleanupAckBase64` | Cleanup only: canonical Base64 of the explicit Root ACK below |

No operational configuration, lease, operation ID, Native command or ACK is produced as part of this source delivery. Root alone decides whether/when to prepare the next candidate and execute a method. Existing unknown operations and their workers/locks remain outside this tool's scope.

## Step 1: `readOnlyOriginal`

The method creates a fixed private diagnostic sibling directory `files/acceptance/P7-source55-case3-original-v1` and writes `read-intent.json` with CREATE_NEW semantics before reading the source. Any previous attempt blocks replay. The exact Android framework files root accepts only0700/0771 inside the pinned strictly private app data enclosure; every submitted private child keeps its original0700 requirement. No permissions are changed. The source method tree is inventoried through read-only no-follow FDs, full EOF reads, complete byte hashes, FD/path before/after fences, exact directory members and private ancestor identity checks. Extra files, `.bak`/`.new` state, changed links, a missing key or active lock cause refusal.

Only the existing reader and existing Keystore alias are used. No ordinary store, AtomicFile recovery, key generation/export, controller, URI/grant, Host, microphone, model, rerecord or discard flow is invoked. Logical reads are the fixed original row, metadata, index, catalog, original WAV and one catalog-derived audit whose hashed name must equal the fixed approved audit filename. Production codecs bind the audit, full DTO, manifest, complete draft strings and discard fingerprint. The catalog is observed; its discard operation is never repeated.

Successful output is exactly `read-intent.json`, `original.wav` and `read-receipt.json`, each 0600 in a 0700 directory. The original WAV is 3,244 bytes with SHA-256 `9d926e59a8259999491bddd13cd65ad6ff3f63916a9dd10a86d8594169ad9550`. The full deterministic receipt includes all original strings, full catalog/audit/index, exact source inventory and current APK hashes. Source fixture and key are retained. This proves Native existing-key readback when actually run; it does not itself prove parent capture or independent parent Keystore decryption.

Root waits for confirmed worker exit, then independently captures every output byte outside the device. The read-only offline verifier `scripts/mobile/p7-case3-readback-review.py` accepts the fixed original archive/index, the three captured output files and the candidate hashes. It reads all source/output bytes, rejects non-private files, duplicates, links, schema drift and integer/Boolean substitutions, compares all original strings, and compares every WAV byte to the published synthetic PCM input. It never contacts a device, uses a key, generates an ACK or performs cleanup. Its result is parent-collected Native readback, with `parentIndependentDecryption=false`. Save the captured WAV, original source archive/index, verifier result and their hashes before considering cleanup.

## Step 2: `cleanupAfterRootFullCaptureAck`

Root explicitly types this ordered ASCII contract, with one final LF, after reviewing the complete saved capture. Every key is mandatory; unknown/duplicate/reordered fields, CRLF, aliases and paths are rejected. `readReceiptSha256` hashes the exact complete captured receipt bytes. `inventorySha256` hashes each fixed inventory line plus LF, in the reviewed index order. `rootCaptureSha256` identifies Root's saved independent capture/review proof. The verifier reports the first two hashes but deliberately does not generate or authorize this ACK.

```text
schema=1
profile=source55-case3-original-v1
readReceiptSha256=<exact full read receipt SHA-256>
inventorySha256=<exact fixed inventory SHA-256>
sourceArchiveSha256=b9c3b45463fc836a251a35bf419eef84ce294a8f95b6197e0e6ca961be1728e1
sourceIndexSha256=a2d3c7f59988aa22e34e3ba7f64bcfe1acf354a5a496150b6196e20cc51c97f4
originalProofSha256=ed6fedee4d955fb2a6af9d469b7a5d9636d123dc0668cde7c07bdda04c81aed4
wavSha256=9d926e59a8259999491bddd13cd65ad6ff3f63916a9dd10a86d8594169ad9550
wavBytes=3244
rootCaptureSha256=<Root's saved capture/review proof SHA-256>
rootFullWavCaptured=true
rootAllOriginalStringsCompared=true
rootSourceCipherArchiveCaptured=true
rootIndependentDecryption=false
rootWorkerExitConfirmed=true
rootAuthorizesExactFixtureAndKeyCleanup=true
```

Cleanup validates the exact three read outputs, current APK hashes and typed ACK, then writes an immutable `cleanup-intent.json` before source/key access. It reacquires the existing lock, rechecks the complete source inventory and authenticates/decrypts again. Its complete deterministic receipt must byte-match the first receipt. All source preflight checks pass before the first deletion.

Deletion is a fixed exact file list followed by empty directories, never recursive deletion or a caller-supplied path. It removes only the named synthetic fixture; the original method's `intent.json` and `result.json` remain. Only after fixture removal does it delete the exact Keystore alias and independently check absence. The complete readback output and cleanup intent/receipt remain for Root capture. There is no automatic deletion of these evidence files.

A partial deletion/key failure is a failed cleanup, not success. Missing terminal receipt, timeout, disconnect or unknown execution outcome leaves Root's worker/lock hold in place. Do not query, poll, recreate a key, clear app data, switch source paths, rerun either method, or infer that the operation was cancelled. Root reconciles the original worker and actual outcome using its existing ownership procedure; any manual recovery is a separate review of the preserved immutable intent. The tool has no automatic retry or outcome-query path.

## Acceptance boundaries

This addendum supplies source and auxiliary contract checks for P7 original evidence handling. It does not add Android/iOS product parity, UI/pixel, ASR, real microphone, Host or phone proof. Earlier Native component passes and their original-WAV gap retain their original scope. Root records a new readback pass only after actual execution, source/candidate/environment matching and independent complete output capture; cleanup is recorded separately after explicit ACK and actual completion. The unread and lifecycle scope remains open under Root's existing schedule.
