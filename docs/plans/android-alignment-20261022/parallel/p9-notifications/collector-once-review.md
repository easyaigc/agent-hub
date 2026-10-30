# Root fence and bounded-reader amendment

Base: `9ca7c3300407c3f6f8e387c4d9c85c06fcacab26`. This addresses Root review
P9-S65-01 and the source portion of P9-S65-02. Live activation remains closed;
P9-S65-03's server/device authority and trusted construction path are not supplied.

The final component of `pinned_file` now opens with `O_NONBLOCK | O_NOFOLLOW`
relative to its verified directory FD. It rejects every non-regular file before
reading, retaining size, single-link, identity and hash checks. A real FIFO vector
runs in a bounded local Python worker so a regression cannot hang the executor.

## Narrow Root integration

`FixedCollector` now requires an actual `OnceAdmission`. Root must pass its
existing common target-fence FD and filename in the existing private 0700
directory. The helper duplicates that same FD and checks its inode/path/owner/
mode. It never creates an alternative target lock. Root must not substitute a
new task directory, copied lock, newly generated invocation or synthetic attestor.

The intended future call sequence is:

```python
admission = OnceAdmission.acquire(
    root_worker.private_directory,
    lease.serial,
    kind.name,
    original_invocation_id,
    immutable_binding_sha256,
    root_worker.common_target_fence_name,
    root_worker.lock_fd,
)
collector = FixedCollector(lease, admission)
result = collector.capture(kind, original_absolute_deadline)
```

No values or live factory for this sequence are included. All future SDK, server,
device, tuple, cfg, nonce, lease, baseline and fence bindings remain null.
The helper is host-local ownership code, not server/device/Native authority.

Admission takes a nonblocking kernel flock through the inherited FD, rejects
another active same-process owner, writes the immutable pending marker first,
then the per-invocation once intent, and fsyncs each file/directory before any
network request. Both records bind the exact serial, diagnostic, fixed command,
existing invocation ID and full immutable binding hash. Existing pending, once
or settled records refuse admission; there is no reset/recovery/replay API.

On observed physical settlement, the helper records the exact bound capture
receipt durably, removes only its own matching pending marker and releases only
its duplicated FD. The original Root FD remains Root-owned and locked until its
existing lifecycle permits release. The once intent and settlement receipt stay.

Unknown/incomplete paths keep the actual collector, decoder, socket objects,
admission and both process-local/physical lock ownership in the original process.
The same-open-description flock is reentrant, so an additional serialized
same-process ownership check prevents a second admission through a duplicate FD.
Known nonzero remote service failure remains known even if settlement is pending.

An injected post-unlink directory-fsync failure exposed a second-admission bug.
The corrected code attempts to restore the **same** local immutable pending
marker and retains the original worker/FD/once intent even if that restoration
also fails. It never retries the command. The original failed test receipt is
preserved separately from the subsequent passing evidence.

Root's supervisor must still keep an unresolved original worker alive and must
not unlock its original fence, exit the worker, rotate state directories or start
a replacement collector. The module cannot guarantee supervisor/process lifetime
or repair a failing filesystem. A pending sentinel cannot replace live handles.
Those live integration gates require Root implementation and independent review.

`ownershipSettled` is independent of remote exit and socket settlement. The
read-only Native join now requires a settled ownership receipt as well. It still
returns `nativePassed=false`; no clean remote per-pipe EOF, diagnostic caller
credential acquisition, full global AM grammar or authenticated Native source has
been invented or enabled.

## API33 scheduling inventory

`api33-independent-native-cases.json` accounts for all 53 annotated methods in
twelve reviewed classes at Root `144cce4a` / Source64r2. Its 23 source-file pins and
two audio assets match Root's existing compiled-source map. APK/signer values are
recorded Root attestation metadata; no APK or installed device was re-attested.

There are 42 independent methods, two fresh paired-v2 permission-cycle methods,
three legacy/original-material methods, and six deferred cold-process methods.
The companion Markdown lists each selector. Each JSON row resolves API,
arguments, fixture mutations/restoration, cleanup, UI coverage, iOS counterpart
and the existing P9 gap rows. No serial, lease or execution UUID is allocated.

No ADB, install, instrumentation, Native, GUI, notification, Host or business-model
operation is performed here. Root retains device scheduling. Old5570, unknown5566,
original-sound5572 and the busy phone remain protected; A6 and task/project/
business scenarios remain paused. All 21 P9 gap rows and P7's one reading ledger
remain intact; no central parity table or Root source is changed.
