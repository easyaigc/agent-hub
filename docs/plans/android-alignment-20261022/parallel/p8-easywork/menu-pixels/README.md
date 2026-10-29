# Four exact Popup states: actual Surface pixels

Operation `parent-p8-source48-two-passed-menu-pixels-api26-next-20261029-1`.
Source48's two API37 cases are read-only reviewed as passed: each has4 native
states,12 native traces,11 actual live/AtomicFile reads and15 product touches.
Each case has only edit-open.png. Neither has four-state menu pixel evidence;
those old passes keep their original tuple and Source46 stays failed.

[Exact test patch](test.patch) applies to the full Source48 test:

| Test     | SHA-256                                                            |
| -------- | ------------------------------------------------------------------ |
| Base     | `46a4703e2573f890e03e51102c15a088ff2ba439abee2c6c22f38a0866ccfe93` |
| Proposed | `4fff6f0e8271979d7d4f1cdd15385e4e178818658e61e5d17efc8cc7409b0d93` |

Check [test-overlay.json](test-overlay.json). All6 existing method bodies,
independent expected labels/script, old role-closed/exclusive oracles and native
owner/label/path/actions/whole-bounds helpers remain exact. The original triplet
body is unchanged except a call after its successful try/catch. The original5s
native budget is untouched; capture uses a separate3s deadline after success.
No product or central-file changes are made.

## Actual pixel source

The locked ui-test1.12.1 source and installed AAR bytecode were both reviewed.
Its captureToImage() Popup path uses a full-device UiAutomation screenshot and
screen crop. Its ordinary Window path uses PixelCopy with fixed redraw waits/
retry behavior. These APIs produce native pixels, but their unscoped behavior
and intermediate Bitmap ownership do not meet this precise helper's bounds.
[pixel-source-audit.json](pixel-source-audit.json) binds exact artifacts/classes.

The new API37 helper uses public API34 PixelCopy.Request.Builder.ofWindow(View)
with the same currently verified PopupLayout View, never an Activity or lower
Dialog window. SDK37 resolves that View's actual attached Surface and insets.
The exact window-local crop and actual returned dimensions/config are recorded.
This copies Android-rendered Surface buffers; there is no View.draw, offscreen
UI reconstruction, AI image or full-device screenshot. It proves Popup Surface
pixels, not whole-display occlusion or foreign-window contents.

Each independent state has one capture: initial automatic, direct only, relay
only and returned automatic. Before and after copying, a single bounded native
snapshot checks the same unique own Popup View/class/type/package/window,
focus/shown/geometry, saved-script expected live choice, all3 owner/text IDs,
actual tags, labels, exact parent-ID paths and closed native checked/checkable
mapping. The original full guard evidence remains attached through the triplet
phase. No input occurs between these snapshots. A changed state fails; no
retry-until-correct-image or rewritten expectation is allowed.

One owned hardware-frame commit callback plus invalidate renders the same UI
state before one PixelCopy request. No state/model/input change is performed.
Capture then checks identical snapshots, encodes into bounded memory and writes
a new PNG with SHA-256 and exact source/build/phase binding. Current synchronous
Android IPC/PNG/file operations have deadline checks; this is not a universal
interruptible real-time guarantee. A late encoding result does not get written.

## Resource and callback boundaries

One state is at most1048576 pixels/4194304 ARGB bytes/2097152 PNG bytes. The case
allows4 unique known phases,4194304 pixels and8388608 PNG bytes. Crop dimensions
are checked before requesting platform allocation; returned dimensions/config/
allocation are checked. A bounded OutputStream stops excess encoded bytes.
No image is downscaled to silently fit the budget.

The framework owns the auto-allocated destination until the copy completes.
The callback only offers a result and signals a latch; it never writes files,
probes, input or UI. Synchronized single-result ownership disposes a late
completed Bitmap after timeout/close; the caller recycles a consumed Bitmap in
finally. No in-flight user destination is prematurely recycled. A queued cleanup
removes only this owned one-shot frame listener after any in-progress
registration. There is no dedicated executor/service/thread left running.

[42 extracted-helper JVM boundary checks](pixel-boundary-plan.json) and500
callback/timeout/consumer race rounds passed, including late/duplicate delivery,
exclusive ownership, pixel/byte caps and encoding deadline failures. These use
a resource stand-in, not Android Bitmap or native PixelCopy.
[reproduce-pixel-boundaries.py](reproduce-pixel-boundaries.py) runs from the
checkout with existing Java17/Kotlin2.4.21 dependencies and GRADLE_USER_HOME;
provide a fresh P8_PIXEL_BOUNDARY_OUTPUT directory. It removes its own classes.

## API26 boundary

WindowInspector starts at29; the Popup View PixelCopy builder starts at34.
Public UiAutomation/AccessibilityWindowInfo can bind native root/window IDs,
package, bounds and node extras on26, but its TYPE_APPLICATION is coarse and
cannot replace actual LayoutParams.TYPE_APPLICATION_SUB_PANEL=1002 or provide
the owned Popup View/Surface. Copying the lower Dialog would be false evidence.

[api26-known-owner-bridge.kt](api26-known-owner-bridge.kt) is a narrow candidate
using public supplied SemanticsNode.root/ViewRootForTest.view/rootView to obtain
the real Popup View and check actual class/package/attachment/params. It compiled
against locked Compose1.12.1/local SDK37 public APIs. It is not installed or run
on26: bounded initial fresh unique owner acquisition and global same-App Popup
uniqueness remain unresolved. The public test fetch can enter global idle/root
waits. The reserved root-created registry callback is deliberately untouched.
[api26-public-boundary.json](api26-public-boundary.json) records exact APIs and
missing equivalence. Official captureToImage's26 full-device screenshot path
does not by itself close those bounds/lifecycle gaps. API26 stays pending and
fails its existing getter guard; no missing field or skipped image counts as pass.

## Current verification

All749 frozen Source48 inputs/aggregate were verified. Final compilation passed:
`70f0e086bce693104841ea87be4a66b718ddb5fcb874c8b274c0e73ce7793872`. Final input bytes matched, temporary own-tree shadows
were restored and parent remained read only. The first successful compile was
superseded by additional source-crop/allocation/file-intent evidence and removed.
New APKs, native calls and menu images are zero. Parent must build a fresh tuple,
run the two named methods, verify4 PNGs each and perform actual visual review.
The old two passes do not pass this new pixel implementation.

[checkpoint.json](checkpoint.json) and [parity-delta.json](parity-delta.json)
retain all pending native/callback/pixel/API26/iOS and fullDEV gates. Seven groups
and A5 remain open. Business projects/A6 stay paused; unknown old operations are
never replayed. Parent alone owns devices, central registration and integration.

The repository boundary recipe independently reproduced42 checks/500 races with
the exact helper hash. Its duplicate proof directory was removed. pnpm check
passed format,5 sync tests and lint, then stopped at the existing localization
feature-record gap; parent owns that central update and later checks did not run.
Gradle clean passed; owned build/Gradle caches, bridge/JVM classes and inspection
JAR are absent. Static logs/base were losslessly compressed and read back.

## Root Source50 adoption and actual acceptance

The earlier compilation-only checkpoint above remains historical. Root Source50 built the exact test4fff6f0e and executed the two API37 portrait methods in a new lease: both passed, with eight four-state same-Popup menu PNGs. Root actually viewed all eight: complete localized labels and one correct checkmark at each automatic/direct/relay/automatic state; saved reads and reopen remain joined. The exact seven-member archives, complete protection and original terminal receipts were independently read back. See [current native record](../../fiftieth-stage-p8-menu-pixels-native.json). Pixel sourceRect origin is not independently bound to a separate native origin measurement; this is a pending auxiliary proof gap. These results do not close API26, TalkBack, issuer/TLS, physical, full A5 or current iOS visual acceptance. No product change is introduced by this test capture.
