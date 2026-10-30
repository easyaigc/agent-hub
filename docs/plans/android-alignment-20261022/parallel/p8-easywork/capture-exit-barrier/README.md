# Inline capture exit barrier: replacement second patch

The prior `122d8803` owner retained the destination but allowed `capture` to return
on an unknown callback. That permitted JUnit `@After` to clear content/restore
windows and the Activity rule to exit. The formal P1 review is preserved outside
this tree; the previous delivery and failed boundary are not rewritten as passed.

Use [this replacement second patch](replacement-second-root-native-lifetime.patch)
on exact Native SHA `3c689cf584f66407f72d8bcaefcbe298f662a0cf682e16de293cdb2c3217fcc8`,
after the first composite wiring patch. **Do not apply the old second patch first.**
Also use the complete updated
[P8WindowPixelCopyDestination.java](../../../../../../apps/android/src/androidTest/java/com/agenthub/android/P8WindowPixelCopyDestination.java).
[The manifest](overlay-manifest.json) pins the input, replacement output, patch
and full helper. No Root/shared table or different whole Native file is written.

## Where exit is blocked

The capture now checks it is off Android Main **before** allocating/dispatching.
It passes the actual request Window into the strongly held owner. On timeout,
interrupt or uncertain dispatch, its own failure `finally` first abandons pixel
consumption, records the original error and then waits inline with that exact
request's callback latch and owner. This code runs before the capture method
returns/rethrows, not in an outer Rule after `@After` could already have run.
The Window, Bitmap/owner, first Throwable and original call/scene stay live.

The await performs no blocking while holding the owner lock. Main remains free
to execute the original callback. A premature/unrelated latch does not suffice:
the owner must have observed its own real callback and a completed disposal or
recorded disposer failure. Conversely, owner completion does not replace the
exact callback-finally latch. Spurious wakes and later interruptions loop back
to the same wait. Observed later InterruptedExceptions are suppressed onto the
first error; initial interrupt state/counts are recorded, and interrupt status
is restored only after the request settles. No replacement failure, cancellation,
retry, synthetic callback or new frame is created.

With no callback there is no finite exit deadline: the original capture remains
pending, and JUnit/Activity teardown is not granted. This is a deliberate unknown
result, not successful completion. A local rejection before dispatch is distinct:
caller closure prevents any later queued dispatch from starting, so its untouched
destination may be released without waiting for a nonexistent callback. A generic
exception after entering the real request remains unknown.

After a late real callback, the same original destination is released once.
The failure path then rethrows the original error; it never returns to PNG
compression or product input. A disposer failure can settle the native-write
barrier but remains strongly retained and blocks subsequent suite admission;
it does not become a successful release. External forced process termination,
OS window loss, or runner process death cannot be prevented by this source, and
none is invoked here. Root must keep its original lease/context while pending.

## Preserved boundaries and source evidence

The original one Window PixelCopy request, source/destination/crop,3s wait and
SUCCESS requirement, success PNG/probe and scenario remain. The entire13,686-byte
API34 helper and earlier API26 composite producer remain unchanged. The only
Native changes are common `capture` and the same two suite-admission guards.
Failure probes distinguish before-inline-wait from settled/no-dispatch exit,
remain snapshots, and cannot replace the first failure if recording fails.

The public callback and Handler-loss research from the prior phase is reused by
exact source hashes. Neither a3s caller wait, a request return, a request throw,
nor Handler post absence proves a completed/cancelled write. See the
[prior contract and boundaries](../common-capture-lifetime/README.md); its missing
inline-exit requirement is the reason for this replacement, not a prior Native
pass. Previous15 dummy-resource checks did not cover Kotlin/JUnit teardown.

## Limited offline verification and remaining Root work

The [new checks](P8CaptureExitBarrierChecks.java) execute the delivered Java helper
with dummy context/resources and a separate callback executor. They test a live
capture-shaped frame remaining pending without callback, later interruptions,
exact Window-context identity, a free callback executor, wrong/premature latch,
original-error identity, no later product-input marker, and controlled late/error
completion. They also reject waiting on the callback thread. The prior15 owner
scenarios/1,000 close-completion races run against this updated helper in a
separate disposable JVM. No test reset/force-free method is added to production
source. Dummy retained-error fixtures end with their isolated test process.

These are pure JDK tests and source-placement checks, **not** Android Bitmap,
PixelCopy, Main Looper, actual JUnit/Activity lifecycle, Kotlin/SDK compilation,
or Native execution. Root's prior837/850 type build does not attest this final
source. Root must build/bind this exact new tuple and test the real original
callback, Main progress, interrupted waits, no-callback retention, teardown
ordering, first-error propagation and protected48MiB/device scenarios. No such
lease/run UUID is created here; future tuple/baseline/lease/run UUID remain null.
No older consumer/unknown/mobile state, shared table, project pause or iOS A6
state is changed. Composite eligibility and full A5 Native remain pending.

This phase's finite JDK run passed the previous15 scenarios/1,000 races and the
new10 barrier scenarios/500 races in separate processes, using the actual updated
Java helper. Zero-fuzz replacement-patch application and reversal matched the
pinned0b01bf proposed source and3c689 input. Source-placement review preserved the
entire API34 helper and all Native code outside common capture/two guards.
Original check receipts, including the prior P1 gap, remain immutable. Applied
settings and turn context independently confirm Terra/medium; applied settings
record priority, while turn context has no separate service-tier value and the
spawn interface has no speed parameter. No fast/ultrafast activation is claimed.
