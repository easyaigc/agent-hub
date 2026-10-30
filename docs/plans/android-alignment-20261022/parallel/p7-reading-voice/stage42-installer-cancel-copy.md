# API26 installer source boundary

This optional narrow change is based on Root `3f9b7fae6d48a7acf7bbd05590738f3d0e0ce55d`. The old/new hashes and common assertion map are in the companion contract. Root owns adoption, candidate identity and all actual native/network validation. No device, official model request/copy, old lease, Host operation, default-family record or WAV is touched here.

## Evidence and limits

The sealed Source45 official-installer traces show growing ZIPs: English 3,849,987 of 41,205,931 bytes and Chinese 2,375,427 of 43,898,754 bytes at the unchanged 180000ms timeout. Both had zero expanded files. The source frame most often points at line115, which combines checkpoint, read and write in one line. Those samples identify the download-copy phase but do not distinguish wire/TLS/input read, storage write or scheduling. UID byte totals are process totals. Observer checkpoint and sample durations overlap and must not be added. There is no established JNI/inference failure or network root cause, and no throughput improvement is claimed by this patch.

Root's next real diagnostic should distinguish connection/response wait, each input read, output write, authority checkpoint including main dispatch, and sync/hash/extraction/publication. It should record bounded aggregate timings and byte progress, preserving the actual source/candidate identity and observer overhead. A source-only or fake-stream check cannot supply that platform attribution. The two existing official ZIPs remain Root-owned inputs and are not copied or substituted for the default download.

## Confirmed cancellation gap and proposed change

The old HttpURLConnection has coroutine checks around reads but calls disconnect only from the same worker's finally. While a read is blocked, cancellation alone cannot reach that finally; cleanup waits for transport progress/timeout. The original 15s read/connect timeouts and 180s total installer budget remain. The new dedicated OkHttp client uses the already declared dependency, pinned HTTPS model URL, no redirects, no automatic connection retries, no authentication/cookies or Host client, and no partial resume.

A structured child waits for cancellation and invokes this call's cancel hook while the owner can still be blocked in execute/read. The scope waits for both the transport watcher and actual owner finally/close. It does not detach a worker or claim idle because the hook ran. If transport abort produces an IOException during cancellation, the original coroutine cancellation cause is restored. DNS, TLS and API26 cancellation latency remain actual-platform questions; call cancellation is not a hard wall-clock guarantee for every platform operation.

The fresh-archive copy checks authority/cancellation again after a read and before writing its returned bytes. It rejects oversized, short, zero-progress and complete-but-wrong-digest input. The original on-disk full hash, ZIP entry/path/size checks, per-file hashes, final verify, atomic publication and stage finally cleanup are byte-identical. No model metadata, SHA, source authorization, expanded limit or original recording code changes.

The structured ownership relies on the documented [coroutineScope child-completion contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/coroutine-scope.html). OkHttp's [call lifecycle](https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Call.kt) describes response resource ownership. Exact runtime behavior still needs the new Root candidate.

## Auxiliary boundaries

Nine host methods exercise the same production ownership/copy functions with tiny in-memory data: exact pinned success, partial/oversized rejection, wrong hash, a fresh full retry after partial failure, zero-progress rejection, cancellation/owner loss between read and write, aborting a blocked fake transport while cleanup is deliberately held, preserving nested timeout cause after IOException, and watcher release on success/failure. No mock result is counted as default installer success, ZIP extraction proof, native cancellation latency or API26 completion. SDK compilation additionally checks the actual OkHttp and Android integration.

## Product and iOS correspondence

F64-C06 retains one explicit attempt and no automatic original-recording transcription/upload. F64-C07 requires actual worker/resource close before leaving busy. F64-C08 requires a complete trusted model before availability/publication. Android implements the last with pinned archive and file hashes; iOS delegates asset integrity/installation to the system AssetInventory path. The iOS implementation remains unchanged and its native validation is separate.

The source audit found no existing independent Vosk predownload/download-progress/retry/delete manager in Android voice settings. That page selects a provider and inspects the separate Android system speech service. Its explanatory text routes saved-original recognition to the recovery panel, where Vosk has explicit probe/download/transcribe controls. iOS prepares/downloads system assets inside recognition and exposes no app-owned ZIP manager. An independent model manager therefore remains a product gap to design, not an existing settings action claimed as wired or verified. The next source substage addresses the existing panel's download/timeout/failure state; it does not silently add settings downloads or microphone work.

Aggregate product/design/acceptance tables remain parent-owned; the companion contract supplies additive assertions, platform impact and pending native association. All original Source45 failures remain sealed.
