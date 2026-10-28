# P8 pairing copy/catalog alignment (phase 2)

This phase removes the production language table and all language conditionals from the
pairing message/target-summary presentation. Known local failures map to reviewed local
keys. Status/error components call `productText`, backed by the existing `NativeL10n`
resource lookup and App language resolver. Raw peer errors stay unmapped/hidden. Target
identifiers and names remain literal values, independent of app-owned label lookup.

VERIFY is one normal `%@` catalog argument. A valid verification code is passed as the
argument; it is never concatenated into a lookup key. An invalid/untrusted status falls
back to the fixed pending key. There is no new resolver or third runtime language table.

The source change appends 14 reviewed Android-native entries to
`apps/android/localization/native-copy.json`; the existing shared `账号` key is reused.
`native-copy-additions.json` is the exact by-key integration delta. The parent catalog
base differs from this older isolated tree; `copy-source-overlay.json` and
`parent-native-copy.patch` bind its exact base/proposed SHA and preserve every preceding
entry. Merge additions by key or rebase narrow hunks; do not replace the parent's file.

The central generated product resources and `localization-catalog.json` asset are not
edited. After merging the source, the parent runs the existing
`node scripts/mobile/generate-localization.mjs --write` and its normal checks. Local
compilation uses only the additions, rendered through the existing `androidCatalog()`
function into an explicit private resource source set. The checkpoint binds those exact
resource bytes. This is an auxiliary build variant, not a claim that normal parent
catalog generation or full root checks have passed.

The added `p8-pairing-copy-additions.json` androidTest asset is independent reviewed test
input; it is not a central generated file or production lookup table. Native assertions
compare installed resources/UI to that approved source and the existing shared catalog
asset. They do not calculate the expected English text using the same runtime fallback
lookup as the UI. A missing English resource therefore fails rather than passing with
Chinese on both sides.

`NativeEasyWorkPairingCopyTest` adds five native component/resolver/AX scenarios:
explicit Chinese despite English system preference, explicit English despite Chinese,
follow-system Chinese and English, and actual English fallback for an unsupported
system preference. The scoped `Configuration` feeds the existing resolver; the tests do
not change the device's global language. They mount the actual status/error components
and target/provider form on a blank ComponentActivity, using a synthetic target/status.
They check polite live regions, actual text/Provider tab AX semantics, formatted VERIFY,
non-recursive argument handling, hidden peer text and unmodified raw target identifiers.
They do not establish real Desktop confirmation, real camera scanning or a successful
runtime/workspace connection.

The previous 14 button methods retain their selectors. Their error expectation now uses
the normal translation adapter. All three pairing test classes validate the exact app
and runner package in rule order 0, before the Activity rule can start; the check is
mandatory even without opt-in foreground activation. `.acceptance.easywork`, MainActivity
and default connection restore remain protected. Supply exact `p8PairingUiBuild` hashes;
copy tests do not need a sidecar. Run the explicit-language pair and the three system
cases as separate groups, each with a 240-second controller deadline and 25 seconds per
method. Original encrypted default files are streamed/hashed before and after each case.

For the existing 14 and the new 11 lease methods, keep using the unchanged pairing-ui
synthetic loopback sidecar with its exact config and three parent-owned reverse mappings.
The parent alone acquires device/process/package leases and performs actual native runs.
A test configuration/fixture/cancellation/timeout failure stays failed or unverified;
preserve its original operation and raw proof. No business input is replayed.

The final phase-2 Session overlay supersedes the phase-1 proposal. It also binds the
existing full draft revision (not only text) before network/write and after an authorized
selection. It was rebased over the latest parallel P7 panel/notice hooks; those hooks
and the foreground/voice/discard logic remain intact. Exact bases/proposals and the
preserved panel region hash are recorded separately. The parent applies/rebases it without losing notification,
voice/discard or other incoming hooks, then generates catalogs and rebuilds the combined
source/APK fingerprints. iOS copy/lease parity, true issuer/Desktop/camera/model work,
actual background/device behavior and A5 closure remain unverified. A6 remains paused.
