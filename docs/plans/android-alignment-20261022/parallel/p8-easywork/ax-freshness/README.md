# Source38 native field accessibility diagnostics

Parent operation `parent-p8-source38-native-ax-diagnostic-20261029-1` requests a
read-only diagnosis before changing product semantics. This stage adopts the
complete frozen Source38 test and changes only the owned test. Parent owns shared
product files, central registration, integration, devices and DEV. No device,
Host, model, microphone or protected EasyWork environment was accessed by this
leaf. A5 remains false; task/project business and A6 remain paused.

## What the current evidence establishes

Source38 is `65044bcc0dce8c9f8ab0004a58648b6119ff2e39e1abf53bd20c6366ea06d119`,
App `dfd7c072980617b33bc05226e20bde257675189a55cbe0b9caac423dde0f65c5`,
runner `68f23114ca7032af17b6626bdf7521410fa3494fde929cb9add8b6016c6ebc44`.
Parent reports that Chinese target layout/glyph checks pass and actual landscape
passes the original full-submit, IME-top/Page-clip, keyboard-Cancel and Done-hide
guards before the native field assertion fails. The original enabled/editable/
visible assertions share a line and save their field record only afterward, so
the failing property remains unknown.

The three proof archives contain earlier builds and repeated case files. The
read-only review filters **all three source/App/runner hashes**, then deduplicates
case path plus content hash. At read time, four exact-tuple case records remain:
boundary passes; Chinese, actual landscape and actual-system-font2 short cases
fail. Sixteen historical/duplicate entries are excluded. No native field flags
were saved before their guard. The short case's precise failure point is not
inferred from its failure class. These are observed existing case records, not
new leaf calls or a replacement for the parent's native-call ledger.

Source37's outer Compose font2 did not reach the actual dialog TextLayout, which
remained font1. It cannot count as large-font coverage. Source38's **actual OS
font2 requirement and every TextLayout font assertion remain unchanged** here.
The measured label-width correction and responsive Sheet/header proposal already
in Source38 are parent-owned; this stage does not alter them.

## Version-locked source conclusions

The exact frozen Gradle context resolves UI and Foundation to1.12.1 from BOM
2026.09.00. The official versioned
[UI source artifact](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1-sources.jar)
and [Foundation source artifact](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.12.1/foundation-android-1.12.1-sources.jar)
were read alongside the installed AARs' actual delegate/legacy-field bytecode.
[compose-delegate-audit.json](compose-delegate-audit.json) records source entries,
artifact/class hashes, SDK37 refresh source and the limits of each conclusion.

The delegate derives native editable from the actual unmerged `IsEditable`
property. The String-based CoreTextField sets that property from enabled and
read-only state. Enabled and visible have separate paths; visibility includes
semantic hiding and a flag-controlled empty offscreen-bounds check. A SetText
action or a Compose editable observation does not substitute for native true.

The locked semantics implementation can generate a separate content-description
node when a merging node has description and children. Thus an exact unique label
match alone does not establish that the matched node is the editable parent.
This is a **conditional source path, not a diagnosed Source38 failure**. The new
record observes native semantic ID, optional test tag and bounded parent/child
context to distinguish it from stale/window/scroll cases. It never substitutes
the parent or manufactures editable.

The provider rebuilds invalidated semantic-node state when queried. Public
`refresh()` can query a node again; false indicates an obsolete/unavailable node.
True is not an atomic snapshot of the entire tree. API26+ public extra-data
queries expose the locked delegate's advertised semantic-ID/test-tag keys. The
diagnostic uses these read-only public queries, without hidden APIs or action
execution. It records missing advertisements and query failures separately.

## Exact owned-test overlay

Apply [test.patch](test.patch) only after checking
[test-overlay.json](test-overlay.json). Do not overwrite Source38 with the older
aa1 test.

| Test                   | SHA-256                                                            |
| ---------------------- | ------------------------------------------------------------------ |
| Complete Source38 base | `4ce89061d9059f9eb7a07ac16c61872a570a20a519a69468b1a8eb90ffd8d1f4` |
| Proposed diagnostic    | `6d5eae29e50331567ef6c45541f213d2e54d4810c40371e15aad65d01b116b90` |

The change map and protected-section hashes establish that `fullText`,
`nativeTextAx` and all four test bodies remain unchanged except for three native
field calls supplying their existing field tags. Every parent full-button,
actual-IME-top/Page-clip, keyboard-Cancel, real Done-hide and actual OS font2 guard
remains. The fixture, target, immutable input, draft and default-family limits
remain unchanged. No product, Session, Page, Sheet, Main, old discovery test,
manifest or central ledger is edited.

Before original field assertions, the private atomic record contains:

- The actual owned dialog window ID, initial/current window/IME/frame state,
  current merged/unmerged Compose semantic IDs and flags, without their text.
- Root flags/geometry before and after refresh, actual window matching, exact
  label-match count and current node bounds/actions/flags before and after refresh
  and advertised extra-data queries. Action IDs are recorded; action labels and
  text are not.
- A refreshed own-package parent and at most eight child flag/geometry contexts.
  Child context is explicitly marked unrefreshed and is not used as field truth.
- Refresh outcomes, current source identities, bounded observation timestamps,
  stable duration, remaining budget and exact failed assertion class. Original
  enabled, editable and visible assertions now have distinct diagnostic messages.

Public UiAutomation is initialized before the first owned-decor window-ID read.
Each refreshed sample re-reads that actual ID within the same deadline; an initial
unregistered `-1` cannot become a permanently cached expected owner. Nonnegative
ID and exact root/target window matching remain mandatory.

One monotonic deadline starts before dialog/AX acquisition. Root readiness,
refresh queries and field observations share the **original5-second total
budget**. Root-null/other-package counters are atomically persisted even when root
readiness times out. At least two consistent refreshed observations spanning100ms
are required; fresh root/target and successful advertised extra queries are
required. That observation is not a linearizability guarantee. Stable false
enabled/editable/visible flags are **not** readiness predicates: they proceed to
the unchanged native assertions and fail. There is no wait-until-true loop, extra
timeout, permanent retry or new input.

The first observation and latest eight are retained; only two matching nodes
receive detailed context, while the full match count still must equal one.
Traversal retains its2048-node limit. Action IDs are capped at64 with truncation
reported; eight child contexts and the existing2MiB global scalar cap bound the
record. No foreign subtree, body, exception message or node `toString()` is saved.
The actual native flags and positive bounds remain mandatory. Whole Compose clip
and native visible-display-frame guards also reject partially visible fields.

## Validation and next gate

All709 Source38 bytes and aggregate were verified. The diagnostic's709 inputs
compile and match after compilation:
`a5f18f273ebe96d9778f87f9e5e9e0fda435b53d42917ec0058aa2256191da99`.
The exact same frozen overlay resolves the inspected1.12.1 runtime dependencies.
Temporary own-tree source overlays were restored byte-for-byte; parent was read
only. This is auxiliary compilation, with **zero new APKs and zero native
executions**. The new source must use a fresh integrated source/App/runner under
the existing `p8PresentationBuild` contract.

No product patch is justified by the old missing-flags record. Parent's named
native run must first identify which property fails, the actual native/Compose
node relation, refresh results and current geometry. A stable native failure
stays failed; its measured cause can then support a narrow product proposal.
Full API26/37/physical/accessibility/font coverage, iOS differences, MainActivity
lifecycle and existing issuer/Desktop/TLS/default-scale/model DEV gates remain
open. [parity-delta.json](parity-delta.json) supplies parent-owned registration
without replacing old failures or counting compilation as a native pass.

Root `pnpm check` passed format, the five sync-rule tests and lint, then stopped at
the existing `apps/android/localization/native-copy.json` feature-record gap;
later root checks did not run. Gradle clean and unoccupied own build/cache removal
completed. Superseded successful auxiliary records and temporary class jars were
removed; final source/compile proof, original failures and licensed source audits
remain outside the tree, compressed with read-back verification.
