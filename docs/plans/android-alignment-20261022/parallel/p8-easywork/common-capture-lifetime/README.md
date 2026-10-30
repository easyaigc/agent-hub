# Common Window PixelCopy destination lifetime: second source patch

The old `capture(label)` is shared by `edit-open` in the original methods and the
new API26 diagnostic methods. Its unconditional finally/recycle could run after
the caller's three-second wait while PixelCopy still held the destination.
The separate composite producer did not own this Bitmap.

Add [the actual ownership class](../../../../../../apps/android/src/androidTest/java/com/agenthub/android/P8WindowPixelCopyDestination.java)
and apply [this second narrow Native patch](second-root-native-lifetime.patch)
**after** the first composite wiring patch. Its exact input is Native SHA
`3c689cf584f66407f72d8bcaefcbe298f662a0cf682e16de293cdb2c3217fcc8`, not the
different live leaf or Root's earlier536 input. [The manifest](overlay-manifest.json)
binds input/output, patch and Java owner hashes. No Root file is overwritten here.

## Ownership and failure boundaries

The real destination is borrowed for compression only after a genuine callback
and SUCCESS result. The owner then releases it after the caller finishes. After
timeout/interruption/request failure, closing the caller releases only a
never-dispatched Bitmap or a Bitmap with known callback completion. A dispatched
destination without a callback stays in a strong process-local registry.
The callback later releases that same destination once, regardless of success or
error, without writing a PNG/probe or issuing another request. The owner never
replaces a pending request with a new frame.

| Boundary                                                                                       | Destination disposition                                                                                                     |
| ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Local failure before dispatch entry, including callback/Handler construction or idle admission | Release exactly once; no request was invoked                                                                                |
| Request entered, then request/idle wrapper throws                                              | Conservatively retain until real completion; a generic throw is not proof of no write                                       |
| Caller wait times out or is interrupted                                                        | Retain; timeout is not completion or cancellation                                                                           |
| Callback arrives before caller finishes consuming success pixels                               | Keep Bitmap until caller's finally releases its borrow                                                                      |
| Late success or error after caller abandoned acceptance                                        | Release once at actual callback completion; no file, probe, input, or retry                                                 |
| Duplicate callback or repeated close                                                           | Preserve first status and avoid double disposal                                                                             |
| Callback never arrives or Handler drops it                                                     | No finite timer proves safe disposal; retain and report until actual callback or independently verified process termination |
| Disposer itself fails                                                                          | Retain destination and recorded error; block later admission and do not claim release                                       |

The common test rule, `touch` and common `capture` refuse new operations while
this suite has a retained destination. They do not stop unrelated apps, original
consumers, or old unknown operations. A failure-only probe takes one atomic owner
snapshot and records dispatch/callback/release/retention state with
`requestCancelled=false` and `retryDispatched=false`. Original exceptions are
rethrown; release or failure-recording errors are suppressed onto the original
failure. A later callback can change retained state after that snapshot; the
file is not a live status claim and is never rewritten by a callback.

## Preserved behavior and factual basis

The single Window PixelCopy request, original destination/crop, three-second
await, SUCCESS assertion, PNG path/format/bytes, and successful probe are retained.
The entire13,686-byte API34 helper, original API37 preference methods/scenario,
and previous API26 composite producer remain byte-identical. Existing memory
criteria, device properties and protection originals are not changed. No shared
parity table is edited in this phase; this is test-resource ownership only, with
no Android/iOS product or UI change and no change to the iOS A6 pause.

The [public PixelCopy listener contract](https://developer.android.com/reference/android/view/PixelCopy.OnPixelCopyFinishedListener)
marks success/failure completion. Named [AOSP8 r1](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/graphics/java/android/view/PixelCopy.java)
calls `ThreadedRenderer.copySurfaceInto` before posting its listener; named
[AOSP17 r1](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/graphics/java/android/view/PixelCopy.java)
uses asynchronous renderer completion before posting. Both ignore the post
return value. [Handler.post](<https://developer.android.com/reference/android/os/Handler#post(java.lang.Runnable)>)
does not guarantee delivery after a Looper exits. These are versioned source
observations and a conservative lifetime policy, not a claim about every vendor
runtime. Exact source hashes/excerpts and actual Terra/medium/priority research
settings are retained outside the worktree.

## Bounded offline evidence and later Native checks

[The standalone checks](P8WindowPixelCopyDestinationChecks.java) compile and run
the **delivered Java owner itself** using the existing JDK and Java8 language/API
target, with no Android SDK, Gradle, Kotlin compiler or external dependencies.
They cover callback-before-close, late completion, timeout/exception ownership,
duplicate completion, borrowed lifetime, concurrent close/completion, publication,
and disposer failure. This tests ownership with dummy Java resources, not Android
Bitmap, actual callback/native writer timing, screenshot pixels, or the Kotlin
binding. Tests run in one bounded disposable JVM; its final retained-error fixture
is an ordinary dummy object and ends with that JVM.

Root must compile/integrate this exact second patch and owner, bind a new tuple,
and test actual normal/late/error/no-callback behavior and cleanup against the
real callback handler/native writer. No SDK/Native/device/ADB/Host/GUI operation
or future run ID is created here. All future tuple/baseline/lease/run UUID fields
remain null. Original failures remain evidence; no original frame is retried or
substituted. Existing composite `nativeAcceptanceEligible=false`, same-Surface
limitations and all pending A5 results remain unchanged.

Rollback is the exact reverse of this second patch only, returning its proposed
Native file to3c689 while leaving the previously reviewed producer/helper wiring
in place. Remove the new Java source only after confirming no other integrated
code depends on it. Neither rollback nor a process restart is executed here.

The delivered pure-JDK owner passed15 scenarios and1,000 callback/close race
iterations in a disposable Java process. The Java source is unchanged from that
run. Initial second-patch verification covered its df3073 intermediate; a later
narrow hardening ensures even suppression/logging failures cannot replace the
original Throwable. The final patch and its2af517 proposed Native hash passed zero-fuzz apply,
reverse, and source verification. Android/Kotlin
SDK compilation and real PixelCopy/Bitmap/handler execution remain unperformed.
