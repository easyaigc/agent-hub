# Saved discovery rows and connection controls

This stage fixes actual Android presentation differences found by reviewing the
existing iOS discovery, address and preference form. Parent operation
`parent-p8-discovery-reviewed-ui-next-20261029-1` owns integration, installation and
native execution. This leaf performs no device, ADB, Host, real business, model or
cross-chat action. A6 and task/project work remain paused.

| Interaction                         | Reviewed iOS behavior                                                                                                                | Android result in this change                                                                                                                                                          |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Rename an existing connection       | Name changes call `renameAgentClient`; discovery rows project `client.name` from current saved clients, preserving discovered routes | Fix old scanned name overriding a current saved rename. Both initial stale rows and later discovery updates use the saved name                                                         |
| Connection preference               | Native Picker derives selection from the current saved client                                                                        | Add visible checkmarks and selected semantics to all three menu options; current saved preference remains authoritative                                                                |
| Connection method                   | Four-option menu Picker marks its current selection                                                                                  | Add the same visible/accessible current-option indication to the existing four methods                                                                                                 |
| Busy form                           | Entire form disabled during connection work                                                                                          | Existing action guards now also disable the two SettingsRow click semantics and menu items; guard retained inside callbacks                                                            |
| Remove saved connection             | System destructive swipe action removes the connection; no extra app confirmation dialog in the reviewed source                      | Preserve existing Android remove button plus confirmation dialog. New native case checks cancel, confirm and old known rows after removal; platform interaction difference is explicit |
| Manual address                      | Native single-line TextField, no capitalization/autocorrection; existing saved-computer action                                       | Existing Android URI field and action retained. New short-viewport case enters a long synthetic address, changes methods and returns to discovery without submitting it                |
| Long discovery name/target metadata | Native text layout and Form scrolling                                                                                                | New case checks the actual Android row and all six target fields in a 320×380dp component at fontScale1.6, including original glyph/line coverage against the visible scroll clip      |

The reviewed files are `apps/ios/Sources/EasyWorkPairing.swift` and `Sheets.swift`,
and Android `EasyWorkConnectionPage.kt`, `NativeSheets.kt` and `EasyWorkDiscovery.kt`.
The known-row name bug is concrete: Android previously returned the stored discovery
row unchanged, so renaming the saved client did not update its displayed name until
discovery rebuilt that row. The fix copies only the live saved name onto the observed
row. Discovery endpoint/reachability/network kind, target identity, credential pointer,
candidate lists, preference, connection commands and authorization are unchanged.
Demo rows are excluded consistently with the iOS saved-discovery projection.

`SettingsRow` gains one default-true enabled parameter. All other callers retain their
behavior. The two EasyWork controls pass their existing busy state. No new copy,
design token, transport, storage rule or Session/Main behavior is introduced.

## Narrow merge contract

`source-overlay.json` and the two patches bind exact observed parent bases and
proposed sources. The parent ConnectionPage matched the child's committed base.
The parent's NativeSheets also contains unrelated migration, notification, voice and
unread work; its two-line patch preserves those additions. Apply the narrow hunks,
or cherry-pick the implementation diff after reviewing conflicts. A changed parent
base needs a reviewed merge and new proposed hash. No parent file was written or
compiled. No shared Session/Main patch is added in this stage.

| Source         | Observed parent base SHA-256                                       | Proposed SHA-256                                                   |
| -------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------ |
| ConnectionPage | `da2fdf885cfb982a9d8f2f846672c4b976ac98b0fe7141ff4c2e0e2e789d02e5` | `511ca7687db8b9c8e5569cbb4d726cf9d3b020217256a62b81a4b6874ec730cd` |
| NativeSheets   | `ad0f57c112b54837c959410c6c633aa2db2ece9539e35082002aae71da0c861f` | `04e86bf72049eb952eb78d53b079f54037810fd1e22cd34c4e5b8a8aa3d90f93` |

## Prepared native UI acceptance

`NativeEasyWorkDiscoveryUiTest` contains four methods:

1. Edit through the real client button, rename through the real text field, prove
   the discovery snapshot still carries the old name while the UI shows the saved
   name, deliver another discovery result and reopen the editor.
2. Use the actual preference menus to switch automatic → relay-only → LAN-only →
   automatic while inactive discovery receives a late synthetic snapshot. Check
   current selected semantics, persisted preference, original routes/reference and
   editor round trip.
3. Use the real remove button, cancel then confirm the actual confirmation dialog,
   return to the real Add form and prove a retained/late known discovery row does
   not reappear as a selectable saved connection.
4. Render the production ConnectionPage in the constrained component, check a long
   saved row and all six complete target fields with the existing glyph-coverage
   verifier, operate preference/method menus and enter a long address.

The first three use the production NativeSheetHost and original Session mutation
APIs with private synthetic saved metadata. The fourth uses the production form,
not copied test UI. Each method owns fresh storage/Keystore and evidence UUIDs;
the default encrypted families must fit the existing64-root/1024-file/262144-byte
caps or setup fails. Actual App/runner hashes and acceptance package IDs are checked
before owned data creation. The independent original draft, protected synthetic
grant and empty operation records are checked afterwards. No NSD start, rescan,
pairing submission, credential probe or runtime Session is expected. Pool/selection
assertions catch unintended connection activation. Late results here are UI snapshots
from inactive `offerVPN`, not a claim that real discovery/network/credential IO passed.

Parent supplies `p8DiscoveryUiBuild` containing `sourceSha256`, `appSha256` and
`runnerSha256`, freezes the complete candidate, and runs each named method under its
own bounded native runner/device lease. These methods do not authorize a device run
by this leaf. Retain failure artifacts and use fresh UUIDs for a subsequent run.

All AndroidTest Kotlin sources compiled successfully with the existing child-only
Session/Main shadows and14-key resource overlay:485 inputs, source-set SHA-256
`af1d184cb2d79d48cf68b30d1a62db2673d2a3fc759b9526d3a1f6a6f8dc49e6`.
Every final compile input was independently checked after the build. The first
attempt failed because the new test chained an IME action after a Unit-returning
Compose test API; it was corrected and the final frozen rerun passed. No APK was
produced. **All four methods are unexecuted**, so no geometry, touch, menu, Keystore
or native parity pass is claimed here. Parent must freeze/rebuild its integrated
candidate and verify actual platform behavior and UI geometry.

`pnpm check` passed format, GitHub-sync checks and lint, then stopped at the existing
leaf central-ledger gap: `Feature change record missing or stale for
apps/android/localization/native-copy.json`. Later root checks did not run. Parent
owns that central merge; this stage does not bypass or relabel the failed check.

## Independent open gates

The previous5 storage-race methods and Session e9c38fe9→91ad035f proposal remain
parent-owned; this stage neither reruns them nor labels them passed. The accepted
source090df0cc/Appc07a5405/runner95b40e4f cold pass remains separate. Phone complete
default inventory at its original cap, real issuer/Desktop DEV, system-trusted Relay,
real model/resources and the remaining physical matrix stay open. No data is trimmed,
no cap is raised and no unknown original operation is replayed. A5Complete is false.

One additional source difference is left for the parent who owns client selection
and the active business graph: iOS selects/dismisses an already-online saved row;
Android currently invokes `connectKnownEasyWork` using its displayed direct endpoint.
This stage does not change that dispatch or claim online relay-only row selection
parity. It requires a separately reviewed current-target/read/selection scenario.

`parity-delta.json` carries the product/design/acceptance merge registration for the
parent. It retains platform-specific removal interaction and every unexecuted/open
item; central shared ledgers are not overwritten by this leaf.
