# API26 actual composite producer: source implemented, Native pending

This continues the separate diagnostic contract from [V1](../api26-composite/README.md).
The producer is real Android test source in
[P8Api26CompositeProducer.kt](../../../../../../apps/android/src/androidTest/kotlin/com/agenthub/android/P8Api26CompositeProducer.kt).
It calls public `UiAutomation.takeScreenshot()`, creates an actual native-coordinate
Bitmap crop, persists its original PNG bytes and SHA, and recycles its bitmaps.
It does not build, run, or enable itself during this source-only delivery.

Apply [the narrow wiring patch](root-native-wiring.patch) only to the exact Root
Native SHA `53604086eb775886e2e331d8947e4f77d48c2713bac6a59c3b522cefd05e776c`,
and add the producer file. This is an increment on the previously integrated
API34 helper isolation, not a whole-file replacement or the old composite patch.
The complete API34 helper block remains byte-identical. The two original API37
methods keep their original names, scenario, Surface PixelCopy and assertions.
They never select this producer, even if the new diagnostic opt-in is supplied.

## Independent default-off wiring

Only `api26ChineseLightCompositePreferenceDiagnostic` and
`api26EnglishDarkCompositePreferenceDiagnostic` can enter the new path. An outer
JUnit rule checks the exact opt-in `p8Api26CompositeDiagnostic=true` and SDK26
before the existing guard, Activity rule or fixture setup runs. With no opt-in,
these methods are skipped. Existing build/lease/device/package/hash admission
still applies; the source does not create a future lease or permit a device.
All future tuple, baseline, lease and run UUID fields in this delivery are null.

The original four scripted choices and original 5s triplet, native owner/text/AX
checks, saved reads, touch restrictions and protection records are reused. The
actual Popup View and windowToken are retained **before** each original triplet.
Every subsequent lookup and capture sample checks those same object references.
The test result uses distinct method names and the V2 diagnostic kind, explicitly
`nativeAcceptanceEligible=false` and `sameSurfaceProof=false`; a passed diagnostic
JUnit method cannot become an old same-Surface/native gate result.

## Actual owner and pixel reads

The three real menu SemanticsNodes must resolve through the cached Compose1.12.1
public testing API `SemanticsNode.root` and `ViewRootForTest.view` to one backing
View and a real attached PopupLayout root. The root's actual class, package,
LayoutParams1002, token, focus, display and AX windowId are checked. If the
interface/root mapping is absent or differs, the producer fails; it never falls
back to an Activity/Dialog or creates a fake root. This proves only the selected
nodes' observed association, not complete global unique Popup ownership.

Screen and window coordinates are separate actual native reads. The before and
after snapshots re-read the native IDs, real three labels, path, checked flags,
classes, actions, whole bounds, live preference and input counts. No desired crop
or fixture coordinate is used as a native origin. Screenshot dimensions/config
and observed allocations must match the actual default Display and rotation0.
The full display plus crop are charged against the unchanged1,048,576-state and
4,194,304-case pixel limits;1080×1920 fails before screenshot. The observable
ARGB cap stays4MiB, PNG stays2MiB/state and8MiB/case. Encoding reserves a bounded
buffer/copy allowance before compression and keeps the conservative combined
6MiB admission cap. Framework and total managed-heap peaks remain unproved.

The source Bitmap is never persisted. It is recycled before crop encoding; a
distinct crop is recycled after persistence. An alias is rejected and recycled
once. The persisted bytes, actual file length and SHA are checked. The metadata
is bounded to256KiB and stored atomically as `composite-diagnostic.json` alongside
only `composite-preference-<phase>.png` crops. Original measurements, result and
edit-open PNG remain separate common evidence; this diagnostic adds a member,
so it does not satisfy the old strict seven-member native envelope by itself.

## Unknown results and privacy limits

There is one process-wide screenshot slot and one worker-owned result. The
worker checks the deadline before the single synchronous screenshot and never
writes probes/files. On timeout the result owner is closed, further input and
capture are blocked, and any late Bitmap is recycled. The diagnostic test waits
for actual worker release **before** JUnit UI teardown. This wait can be
unbounded. It is not screenshot cancellation, successful completion, an automatic
retry, or permission for Root to release the controlled device lease early.

Before screenshot, the public reported interactive windows must be nonempty,
bounded, rooted entirely in the acceptance package, and have one matching active
focused Popup AX window. Missing roots, foreign content, incomplete traversal,
reported overlapping upper windows, changes, or traversal budget exhaustion
cause refusal before capture. This conservative rule may reject ordinary system
bars/IME; it does not whitelist them to make a scenario pass. System composition
still transiently exists in memory. Public AX windows can omit visually present
layers, and pre/post samples are not atomic with screenshot. Consequently this
source cannot guarantee complete layer coverage, no unknown user content,
no occlusion, a hardware frame commit, or the Popup's own Surface. Root must
provide an independently controlled synthetic-only scene before any future run;
there is no general-user-content permission in this delivery.

## Auxiliary checks and remaining Native admission

[producer_contract.py](producer_contract.py) validates the separate V2 report,
actual-byte PNG integrity, independently measured projections, AX/window witness,
input counts, budgets, status and immutable caller build pins. It reuses only
the exact reviewed V1 scalar/AX policy, not V1's old2d7 build pin or native gate.
[check_producer_contract.py](check_producer_contract.py) uses in-memory synthetic
PNGs; its result is auxiliary metadata/PNG evidence, not producer execution.

```sh
P8_REFERENCE_PNG_DECODER='<reviewed-source50-proofs.py>' \
P8_PRODUCER_CHECK_OUTPUT='<fresh-private-output>' \
python3 -B docs/plans/android-alignment-20261022/parallel/p8-easywork/api26-composite-producer/check_producer_contract.py
```

Root's minimum future checks remain: review/add this exact source and patch;
build and bind a new implementation/App/Test tuple; audit generated declarations
and actual API26 reflective discovery; admit a controlled synthetic-only scene
with the unchanged screen budget; verify both real diagnostic methods/four crops,
native origin/token/AX/input/file bindings and failure/late-result lifetimes;
independently inspect pixel labels/checkmark/occlusion and protected common traces;
and rerun the original applicable API37 methods/eight-PNG checks. These steps are
not authorized or executed by this source-only stage. The API26 platform jar
dictionary remains a later missing-input check. All A5 acceptance, existing
consumer/unknown/mobile busy states and the human iOS A6 pause remain unchanged.

Official public references: [UiAutomation screenshot and windows](https://developer.android.com/reference/android/app/UiAutomation), [SemanticsNode.root](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsNode), and [ViewRootForTest.view](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ViewRootForTest). The separate Terra/medium research checked the exact cached Compose1.12.1 AAR (SHA `3f1ae17e237d9dc0a2a0f42cb04b85b8de6e12f67adc5f5c12173fe31e501cb2`) and retained bounded AOSP26 excerpts outside the worktree. Applied-settings journal confirms priority; the spawn interface exposes no speed parameter. No source-retention annotation requirement, Kotlin compile, actual Popup root mapping or Native pass is inferred from those JVM signatures.

The small contract check passed49 synthetic cases and two diagnostic structures,
with zero native passes. Zero-fuzz application/reversal and byte equality of the
entire13,686-byte API34 helper, original two methods/scenario and old document
prefixes were checked. The first source review found an early worker-slot
release; that finding is retained and the implementation now joins/checks
TERMINATED before release. This remains source-only lifecycle reasoning, not an
executed screenshot-thread or Bitmap test.
