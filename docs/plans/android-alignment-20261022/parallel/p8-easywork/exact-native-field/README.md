# Source42 exact native field identity and full control bounds

Parent operation `parent-p8-source42-exact-native-field-identity-20261029-1`
continues P8. This stage supplies an owned-test revision and an exact narrow Page
proposal. Parent owns product application, shared files, devices, integration and
central registration. No device, Host, model, microphone or protected EasyWork
environment was accessed by this leaf. Business tasks, A6 and original unknown
operations remain paused/read-only; no input is replayed.

## Actual Source42 finding

Source `0abb800077b072b945281bc2f35bea9368259d787113c5d94c60fac40b242bf6`,
App `bdce71293f6039d0c2bf94b9527952cb862acffb2b5df1d5ed3f907746e533a5`,
runner `7dcd209389477f0af7dc8431f4b067a2d422e1ddedc9861b9ca83e0b587a7443`.
The three proof archives were read and filtered by the complete tuple. Their
native field failures are preserved. The stable editable failure comes from
selecting the description node; it is not evidence of insufficient waiting.

| Scenario               | Compose field ID | Unique native description ID | Native description / linked editor                 |
| ---------------------- | ---------------- | ---------------------------- | -------------------------------------------------- |
| Chinese name           | 124              | 2000000124                   | View/editable=false; parent EditText/editable=true |
| Actual landscape Relay | 208              | 2000000208                   | Same distinct-node relationship                    |
| Actual OS2 short QR    | 159              | 2000000159                   | Same distinct-node relationship                    |

The existing description selector correctly found a unique label but incorrectly
treated that generated node as the input control. Source42's refreshed node/window
identities and stable observations establish that distinction. The old failure
is retained; new sources require their own native results.

Landscape also has a separate reachability issue: the inner rectangle ends at
screen1017, while the real native126px control spans922..1048. The visible frame
ends at1017, leaving31px of declared touch extent below it. Chinese's62px inner
rectangle similarly differs from its126px native control, though that control is
inside the frame. The OS2 QR inner/native heights are both324px. These are actual
different geometries, not interchangeable bounds. See
[identity-and-bounds-evidence.json](identity-and-bounds-evidence.json).

## Exact selector

Apply [test.patch](test.patch) using [test-overlay.json](test-overlay.json):

| Test                   | SHA-256                                                            |
| ---------------------- | ------------------------------------------------------------------ |
| Complete Source42 base | `6d5eae29e50331567ef6c45541f213d2e54d4810c40371e15aad65d01b116b90` |
| Proposed               | `37337d458f734d2dede47548f96a75b549749857d7cc4bca038186c9f5c12f42` |

The selector anchors the current merged/unmerged Compose field ID, scans the
exact App/dialog native tree and queries the locked delegate's advertised public
semantic-ID extra data. It independently counts exact-ID controls and exact-label
nodes. The unique control must also return its actual existing test tag. Enabled,
editable or class properties are never used to choose a convenient candidate.

Label association is checked separately. A direct self-label must be the same
native node and ID. The generated-label case must have the actual version-locked
description ID, a refreshed direct parent equal to the **independently selected**
field with its exact semantic ID, and exactly one matching direct description
child in that field. This is not an ancestor fallback. Any wrong count, ID, tag or
relationship remains a failure.

The actual selected EditText must be enabled, editable and visible, expose native
SetText and SetSelection actions, and have positive complete bounds. Actions are
checked without invoking them. The unique label must remain enabled/visible with
positive complete bounds and be contained in its linked control. Both must fit
the actual display frame, dialog, Page viewport and visible IME boundary. The
existing whole-Compose-clip and native-frame guards remain. A full native control
cannot pass by showing only its inner text.

Measurements precede boundary assertions and record the actual field/label IDs,
native control, inner Compose rectangle, current clip and exact required scroll
delta. No automatic retry scroll, rectangle substitution, synthetic editable,
fake SDK provider or flags-until-true wait is introduced. Root acquisition, all
public ID/refresh queries and observed100ms stability share the original5-second
deadline. Queries check that deadline during traversal. The existing2048-node,
two-detailed-match, latest-eight-observation and2MiB body-free evidence limits
remain. Null/obsolete/identity-inconsistent observations cannot become a pass.

`fullText`, `nativeTextAx` and **all four test bodies** remain byte-for-byte as
Source42. Thus all original text/glyph/clip checks, actual OS font2, IME8s,
full-disabled-submit, actual IME-top/Page-clip, keyboard-Cancel and real Done-hide
guards remain. Three product scenarios use the revised field selector; boundary
rename remains the fourth class method.

## Narrow product proposal

[page.patch](page.patch) moves the existing16dp horizontal/12dp vertical padding
from the BasicTextField caller modifier into its existing decoration Box.
[page-overlay.json](page-overlay.json) binds the current Page:

| Page          | SHA-256                                                            |
| ------------- | ------------------------------------------------------------------ |
| Source42 base | `f721c28c3a43deb4c3815d4e04f0bbe58f8b4e3875540e762e976eee1adff224` |
| Proposed      | `d841c6683fe3cce529c458b01caa76b81169bf5e8972583131e224b4df1a536a` |

The reviewed locked CoreTextField appends its merging semantics after the caller
modifier. Caller padding therefore places that semantic/scroll rectangle inside
the padding. The measured inner/native mismatch is consistent with that layout
and the minimum touch expansion. Decoration padding lets the root semantic and
scroll target include the same full control, so the existing `performScrollTo`
can target its complete declared region. Padding values, colors, typography,
single-line input, placeholder, callbacks, draft/form owner, keyboard actions and
all parent header/short-window/Sheet behavior remain. No editable flag or SDK
field is injected. Only the connection-form helper is proposed to change.

This is a measured narrow proposal, **not a native pass or proven visual
equivalence**. Parent must compare exact new native identities, full bounds,
layout and all original assertions in Chinese, actual landscape and real OS2
short-window scenes. If content still cannot fit or scroll into the actual clip,
retain the product failure and use the newly recorded extent/range. Do not remove
the full-boundary guard or widen the Sheet change.

## Auxiliary checks and remaining work

All729 frozen Source42 input bytes and aggregate were verified. Both combinations
pass AndroidTest Kotlin compilation, with all729 inputs checked afterward:

| Combination                                  | Aggregate SHA-256                                                  |
| -------------------------------------------- | ------------------------------------------------------------------ |
| Exact selector, unchanged Page               | `ee3a22723da3479ed3e1a2a5e3381deb4ec21a188d3b4da809d1c2591dc637cb` |
| Exact selector plus full-field Page proposal | `7a9f9009b142a50cfe07d9d880df1901278ea673f75b34e20e9f81b3aa2c12ac` |

Temporary own-tree source shadows were restored byte-for-byte; parent source was
read only. There are zero new APKs, SDK simulations and native executions here.
Parent must apply the exact overlays, freeze a consistent source/App/runner and
run its three actual scenarios. Existing Source41 root-check success and Source42
native failures stay associated with their own sources. Neither is a pass for
these proposals. [parity-delta.json](parity-delta.json) supplies parent-owned
product/design/acceptance registration. iOS is unchanged; full accessibility and
native/physical/API/font coverage plus original issuer/Desktop/TLS/model/DEV gates
remain open. A5 remains false.

On the restored leaf checkout, `pnpm check` passed format, the five sync-rule tests
and lint, then stopped at the existing `native-copy.json` central feature-record
gap; later checks did not run. This differs from the parent Source41 full-check
record and the two successful frozen compilations. Gradle clean and unoccupied
own build/cache removal completed; superseded successful auxiliary snapshots
were removed and final logs were compressed with read-back hashes.
