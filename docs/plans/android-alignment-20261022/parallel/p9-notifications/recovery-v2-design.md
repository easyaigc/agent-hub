# P9 recovery v2 design checkpoint

Status: proposed contract checkpoint, **not implemented or advertised**. Baseline `160b51ec`; parent integrated the prior work as `09bc7ae4`/`66b12bd8`. Authorization: parent operation `parent-p9-recovery-contract-v2-next-20261028-1`. No real Host/device/model/deployment action is in this workline.

## Boundary and compatibility

Host owns notification metadata, receipts and local generations. No Codex execution, arbitrary route/IPC, task/project operation, iOS product change or new vendor delivery guarantee is introduced. V1 registration/preferences/unregister/status/events stay wire-compatible for devices that have not adopted v2. Android uses v2 only after a closed, exact-target capability response; 404, unknown version and malformed capability never imply support. Health is not extended in the design stage.

Fixed proposed routes under the existing authenticated notification boundary:

| Route                                        | Method | Purpose                                                                                                |
| -------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------ |
| `/v1/notifications/recovery-v2/capabilities` | GET    | Exact target, persistent store epoch, supported version/operations, feed/storage bounds                |
| `/v1/notifications/recovery-v2/operations`   | POST   | Submit one complete immutable intent                                                                   |
| same                                         | GET    | Query the original device/operation ID, including absent/epoch-mismatch without claiming non-execution |
| `/v1/notifications/recovery-v2/feed`         | GET    | Bounded ordered live or reset-snapshot page                                                            |

Every request requires the exact host and complete machine target, the existing authentication/TLS policy and a specific device. Existing optional owner restrictions remain; this does not invent an account gate for Codex or equate credential strings with new owners.

## Immutable operations and receipts

The v2 intent contains `version=2`, canonical operation UUID, store epoch, device ID, complete original target, real X25519 public key, full v1 preferences, monotonic client subscription generation, kind (`register`, `preferences`, `revoke`, `feed-reset`) and SHA-256 fingerprint. `feed-reset` additionally binds the observed gap/old cursor and an explicit local gap-acceptance ID. Revoke carries the full disabled preferences and original public key, including when no registration is known. Codex authority comparison is accountless; any metadata that was actually submitted remains immutable intent data.

Fingerprint input is the whole intent except `fingerprint`, using a specified canonical JSON representation: recursively sorted UTF-16 keys, stable array order, safe integers only, valid Unicode (no lone surrogates), JSON minimal escaping and UTF-8. Kotlin and Node use shared frozen cross-language vectors, including Unicode, shuffled keys and one-field mutations. Existing v1 local hashes are unchanged.

Client persists the complete operation and an attempted/unknown state before opening a request. Server durably reserves the same ID/full intent before applying it. Same ID/same fingerprint returns the existing receipt without applying again; same ID/different bytes is a conflict. A reservation surviving a crash is completed or rejected from its saved original intent during server recovery, never reconstructed from current preferences. Device changes and the final receipt publish atomically in the same state file. A higher generation can reject a stale reservation without reviving it.

Receipt binds store epoch, target/device/key, original full intent/fingerprint, operation ID, generation and stage (`reserved`, `applied`, `rejected`) with a closed reason/status. A direct reply or original-ID query may settle only that exact saved intent. An absent ID, capacity failure, lost response, changed server epoch, or unknown receipt version remains unknown after an attempted submission. The Android automatic path queries only; it does not resend the POST or switch IDs even when the server supports idempotent duplicate submission.

V1 unknown writes have no remote operation ID and stay v1 unknown. A v2 status/revision is never retroactive proof. A human's later explicit off action may create a **separate** new v2 revocation intent, retaining the original unknown receipt unchanged. This is not a replacement registration or replay.

## Subscription tombstones and downgrade fencing

Per exact target/device, persist highest reserved generation, applied generation, revocation floor, adopted-v2/legacy-write fence and optional owner. All three mutation kinds require a greater generation than prior reservations; `preferences` also needs a known v2 registration. Generation reuse by a different operation is rejected. Revoke creates a tombstone even when the device has never existed. Registration at/below its floor cannot become active, before or after process restart. Old v1 register/preferences/unregister are rejected for an adopted device, so late generationless writes cannot revive or disable a newer v2 subscription. V1 devices with other IDs are unaffected.

Client generation is separate from existing relay subscription generation. Existing in-flight v1 relay results still check their original device object; explicit v2 revocation also retains the existing relay tombstone/cancellation flow where applicable. An applied local revoke proves local fencing, not vendor acceptance or removal of an already delivered card. V2 foreground registration must not silently replace an existing APNs binding: it returns a visible conflict until an explicit revocation completes.

The optional v2 journal shares the same atomic Host state as devices and tombstones; a sidecar cannot provide this atomicity. Once v2 is activated, the enclosing disk format advances to a version an older writer rejects. A new writer reads old v1 files and keeps v1 wire behavior. Unknown/corrupt v2 disk data blocks and preserves the file; it is never dropped to recover a v1 writer. Rollback/restoration of external backups is not claimed tamper-proof; epoch/absence cannot certify a missing operation.

## Bounded feed, epoch and explicit reset

Maintain a persistent store/feed epoch and ordered append sequence for original observed event payloads. Restart preserves epoch and order. Event IDs remain original; same ID with different content is rejected. The bounded v2 log is independent of v1's retained-last-512/actionable pruning. Pruning advances an explicit retention floor; it never edits an original event or silently changes an existing cursor. Page bounds apply to count **and UTF-8 bytes**, and a single oversized event produces an explicit capacity result.

A live page binds target/device, public-key/preferences fingerprints, subscription generation, epoch, original `after`, fixed `through` high-water mark, next cursor and ordered original items. Items include original event/fingerprint and an explicit Host disposition (eligible, inactive, expired/stale or preference-filtered). Filtered/scanned originals receive durable client outcomes too; the server must not advance past an unrepresented original merely because it was filtered. Final active IDs are bounded and authoritative for the matching target; overflow fails instead of truncating them.

The client persists a whole page before processing, then every original result before committing `next`. It rejects gaps, duplicate sequence numbers/IDs with changed payload, out-of-order pages, changed high-water mark, foreign target/generation/epoch and stale callbacks. Original-event outcomes survive preference and connection changes. A started event without a final result remains no-replay unknown; pending unstarted work can continue only under its captured valid page.

Epoch mismatch or cursor below the retained floor returns `reset-required` with old/actual epoch, floor/head and reason, without advancing. A reset is a named `feed-reset` operation, **not an automatic cursor assignment**. Before requesting it, the client must preserve a durable gap record and explicit local acceptance ID. The server prepares a bounded immutable current snapshot with its hash, original events, active IDs, high-water mark and lifetime, tied to that operation and current subscription generation. Snapshot pages survive restart while retained; expiry/capacity/unknown snapshot requires a visible new decision, not a hidden restart at the head. Only after all snapshot originals have durable results can the client commit its live baseline. Existing P7 ledger/read tombstones and unknown mutation receipts remain intact.

A reset cannot prove the contents of already pruned history. Its receipt says exactly which gap was acknowledged and which known current snapshot was prepared; no synthetic original event or claim of lossless history recovery is allowed. Until the parent supplies an explicit reset/off interaction, the monitor exposes a blocked state and keeps its cursor. This is a deliberate unresolved product integration boundary, not weakened no-replay semantics.

## Proposed bounds and verification stages

Initial limits: 128 device-generation slots, 1024 immutable operation receipts, 2048 log originals/4 MiB, four reset snapshots/2 MiB each, 16 MiB total v2 extension, 32 events/128 KiB per page, 2048 active IDs, five-minute snapshot retention. Final constants and capacity behavior must be checked against current event/preferences maxima before implementation; completed receipts/tombstones are not silently evicted to admit writes. Full capacity rejects a new intent before effects while preserving original-ID queries.

1. This reviewable design/contract checkpoint only.
2. Narrow protocol/runtime/fixed-route implementation and meaningful Node tests: lost reply, crash after reservation, concurrent/out-of-order generations, absent-device revoke, late v1 write, restart/downgrade, old v1/iOS compatibility, byte/count pages, floor/epoch/reset, capacity/corruption and immutable originals. No real service.
3. Android strict wire/commands and encrypted journal migration/monitor, cross-language hash/page vectors and native auxiliary scenarios compiled. V1 unknown stays unknown; separate explicit off/reset APIs have no default automatic invocation.
4. `pnpm build`, required `pnpm check` (central parity gate remains parent-owned), Android app/runner build and source/contract/parity/migration/localization deltas. Parent alone leases real Host/device/model and shared app integration.
