# Exact preference menu selection on each native platform

Operation `parent-p8-source46-exact-menu-owner-platform-state-20261029-1`.
Source46 proves one exact Popup/window/owner209/text211 relationship and records
role=null, Compose Selected=true, native selected=false/checked=true. The old
case failed and stays failed. Native isCheckable was not recorded, so it remains
unknown. Post-selected native guards did not execute; numeric bounds review is
separate from their actual execution.

The locked Compose1.12.1 official source and installed AAR bytecode agree:
Selected writes native isSelected for Tab; for other roles it writes isChecked,
and getInfoIsCheckable is true when non-Tab Selected is present. ToggleableState
has its own path. Hashes/line ranges/classes are in
[selection-source-audit.json](selection-source-audit.json). Thus a universal
owner.isSelected=true is the wrong oracle for the measured role-null menu.
This alone does not demonstrate a product semantics defect.

[The exact test patch](test.patch) applies to Source46/27af:

| Test     | SHA-256                                                            |
| -------- | ------------------------------------------------------------------ |
| Base     | `96c4cb13ef2d6c1bad649d80508d34aefb5ce463eadeb7aba0e4e4a39542cb7c` |
| Proposed | `419a593b11a51947cdeaaa3079c5545bbb43ed9ed2ed54c4d656e2d4c039b69d` |

Check [test-overlay.json](test-overlay.json). No product/Page/padding/Sheet change
is proposed. No Tab role or static accessibility state is injected.

Before guards, the test now records actual native isCheckable for owner and text,
actual Compose Role/Selected/ToggleableState presence/value, and the full actual
owner/text identity/path/window/raw flags. It then uses this narrowly pinned
profile only: a known connection-preference option tag, absent Role/key, absent
ToggleableState, present Selected equal to an independently expected choice,
actual native android.view.View, native checkable=true, checked=expected and raw
selected=false. An unknown checkable value cannot pass. There is no OR fallback
to a convenient checked/selected node, no projection chosen from its native flags,
and no waiting for desired state. Unsupported roles fail and require review.

The pure oracle supports selected=true and unselected=false as independent
expectations.29 JVM vectors include both states for all3 known tags and23
negative cases: missing checkable/Selected, unrelated roles, toggle state,
text/checkbox classes, unknown tags, contradictory flags and the old incomplete
Source46 record. All pass;6 gate-removal mutations are rejected. These are actual
extracted-oracle auxiliary checks, not native passes. Vectors are in
[selection-vectors.json](selection-vectors.json). One initial standalone compiler
invocation lacked its annotations execution dependency; the corrected harness
passed without oracle changes, and that auxiliary failure remains recorded.

Every four test body, field/fullText/nativeText helper and Popup deadline/view/
geometry helper is byte-for-byte unchanged. Only declared diagnostics, retained
final anchor and the single projection oracle change. The exact owner ID/tag,
independent unique text and strict parent-ID path, original5s deadline, native
enabled/clickable/visible/Click action and whole owner/text/Compose bounds stay
required. The patch adds no native invocation or timeout. The full source reverts
to the exact base when only these declared changes are reversed.

The common assertion is an exact native choice consistent with the saved/bound
preference, truthful control role and unique visible actionable option. iOS
source uses a native [SwiftUI Picker](https://developer.apple.com/documentation/swiftui/picker)
bound to the saved preference and tagged options. That preference Picker has no
explicit style; only the separate connection-method Picker explicitly uses menu.
Apple provides selected traits
and an XCUI selected attribute, but no current iOS menu-owner projection was
measured here. They do not prove which attributes this specific menu exports.
Keep that native assertion pending; do not transfer Android's checked profile to
iOS or force Tab semantics on either platform. iOS source/behavior is unchanged.

All743 frozen Source46 input bytes/aggregate were verified. Compilation of those
inputs with only this owned test overlay passed:
`c42c8fdea2dd29dcd5a41b6a54db064eda74e581dbca01b7d5d3316df649dd0c`. Final bytes matched and temporary own-tree shadows were
restored; parent stayed read only. New APKs/native executions are zero. Parent
must run a fresh matching source/App/runner to measure checkable and confirm the
entire profile, saved choice and later guards. Source46 failure is not upgraded.
API26 WindowInspector remains explicitly unsupported/pending. Full iOS/Android
native/physical/AX/font and issuer/Desktop/TLS/model/DEV gates remain open.
[parent registration delta](parity-delta.json) and [checkpoint](checkpoint.json)
retain the distinction. Business projects/A6 remain paused; unknown operations
are never replayed. A5 stays false.

`pnpm check` passed format,5 sync tests and lint, then stopped at the existing
`apps/android/localization/native-copy.json` feature-record gap. Parent owns
central registration; later checks did not run. Gradle clean passed and own
build/Gradle caches, inspection JAR and auxiliary JVM classes are absent. Static
logs/base/excerpts were losslessly compressed and read back.
