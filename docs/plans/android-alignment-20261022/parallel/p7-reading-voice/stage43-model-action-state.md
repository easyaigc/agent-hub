# Saved-model action and failure state

This optional narrow patch follows stage42 and uses Root `3f9b7fae6d48a7acf7bbd05590738f3d0e0ce55d`. The companion contract carries exact old/new hashes for the Root controller, eight appended resource keys per language and test paths. The child branch retains its previously delivered F64 fences, so a whole child controller/resource replacement is not an adoption method. Root remains unchanged here.

## Confirmed source problem

The production installer's internal 180s timeout throws TimeoutCancellationException. The controller previously rethrew every CancellationException, then only changed busy/phase in finally. A nested installer timeout could therefore leave no explicit installation failure message. An older missing/corrupt probe also stayed cached across that attempt. The single busy message described saved-recording recognition even while a model was downloading. These are source/state findings; they do not establish the cause of the Source45 slow transfer, complete API26 installation, or prove a native UI pass.

## Behavior

The existing panel distinguishes checking a model, downloading/verifying a model and recognizing the saved original. These are phase descriptions, not numeric byte progress. Its unchanged closing message and actual worker join remain the authority for ending a wait.

A nested operation timeout becomes a visible action-specific failure only while the original caller remains active. Explicit user/parent cancellation still propagates. Cancellation also rejects an uncooperative late success or late IOException. The original generation/target/draft checkpoints and durable result stage/accept/applied body remain intact; no new action is automatically submitted.

An explicit install clears the old availability before starting. A failed install/probe leaves availability unknown and displays the failure, so the user explicitly checks model status before another download. This covers publication completing just as a timeout is delivered: a fresh probe determines installed/missing/corrupt state rather than automatically reinstalling. Existing recorded audio, staged text, immutable receipts and operation IDs are retained. Recognition itself remains separately explicit and is never started by successful model installation.

The original 180000ms installer budget, model specifications and stage42 transport/integrity path remain unchanged. Memory/unsupported errors keep their existing handling; a failed probe/install cannot retain a stale available model after an ordinary failure. The controller's existing generation and actual-close join still prevent late work from claiming a new draft/target.

## Auxiliary versus pending native evidence

Four host methods execute the actual production attempt boundary: an inner timeout leaves its caller usable; a parent timeout escapes; an ordinary failure does not replay until another explicit call; and cancelled callers reject late success/IOException after actual worker exit. These checks are independent of Android or real model transport.

Three added native controller methods compile with the real encrypted synthetic recording fixture: timeout and explicit recheck including a possibly installed result, cancellation with a deliberately held installer exit and no late probe, and ordinary partial-install failure followed by an explicit successful retry with no recognition. They assert the parent's existing exact canonical original JSON, complete persisted row and WAV bytes, plus no fill or decoder invocation. These methods have not run here. The child fixture's older preservation helper is aligned to the stronger already-existing Root helper; original Root method bodies and preservation assertions are retained.

SDK compilation and Root patch application/readback are auxiliary evidence only. The nine stage42 transport/copy methods use matching unchanged source, so they are not repeated merely for this controller/copy change. Fresh Root candidates still need actual panel layout/AX, languages, actual worker cleanup and API26 install completion. Any Source45 original failure stays sealed. No official model ZIP, WAV, database, unknown operation, Host or device is read or mutated by these auxiliary tests.

## Parity and remaining gaps

F64-C09 adds a shared product assertion: distinguish waiting for a model from recognition and present recoverable failure without losing the original or silently retrying. Android implements its existing panel's app-owned model states here. iOS retains system AssetInventory preparation inside recognition; its native asset wait/failure/cancellation behavior requires its own evidence. No iOS implementation or past verified status is changed.

This patch adds no independent voice-settings model manager, numeric progress, cache-delete UI, automatic asset download or microphone work. The preceding settings audit found those standalone app-owned model actions do not exist. Those product/design gaps and the per-layer slow-download diagnostic remain explicit parent-owned follow-up scope. The aggregate product/design/acceptance tables remain parent-owned; this contract supplies new copy, common assertions, platform impacts and pending acceptance association.
