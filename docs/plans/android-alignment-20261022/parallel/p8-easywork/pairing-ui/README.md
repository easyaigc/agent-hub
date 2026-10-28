# P8 A5-1/2: native pairing form preparation

This delivery prepares executable native UI scenarios and narrow Android fixes. It has
not used a device, ADB, camera, real issuer, Host, original Desktop or model. The parent
owns review, integration, central parity registration and the exact device/sidecar lease.
A5 groups are not closed; A6 remains paused. The independently occupied
`.acceptance.easywork` package and the main checkout are protected.

## Actual product entry and iOS comparison

Android's `ConnectionPage` in `EasyWorkConnectionPage.kt` renders the real Provider tabs,
then the real EasyWork name, discovery, QR text, manual-known-computer and device-code
forms. Native `MobileSession.connectEasyWorkQR/Code` calls the actual authorization and
pinned LAN pairing implementations. The tests use that Compose entry, not a parallel
mock screen or a replacement client. `PairingUiIsolation` creates a fresh encrypted
namespace and AES alias, and streams hashes of every existing default encrypted record
before/after the scenario. A blank native ComponentActivity hosts the real product
form; MainActivity/default connection restore and real Host monitors are never started.
No camera/intake entry is operated. No acceptance package is installed or launched by this child.

The parent iOS reference is `Sheets.swift: AgentConfigurationViews/AddClientView` and
`EasyWorkPairing.swift: EasyWorkConnectionForm`. It has Codex/EasyWork selection,
discovery/QR/manual/relay methods, QR text input, a 16-character device code and desktop
confirmation status. Its camera sheet is a separate capability. The exact read-only
reference hashes are in `checkpoint.json`; no iOS source was modified. iOS does not
currently render the added complete-target summary. Its matching display and the
corresponding draft/late-result behavior remain explicit parity work, not an iOS pass.

## Narrow Android changes

- Keep EasyWork name, method, QR text, manual address, relay address and code in an
  ephemeral draft owned by `ConnectionPage`, so the Provider subtree can be replaced
  without destroying that form's input. This is same-form Provider switching, not a
  claim of process-death or rotation persistence.
- Freeze action inputs and the incoming-link presentation at submission. Disable
  Provider/fields/submission while busy; defer applying a new incoming QR until the
  current action exits. A superseded action cannot close the new form or replace its
  visible error/status with a late result. Unknown authorization and business intents
  remain in their existing journals; neither draft switching nor an error retries them.
- Show an explicit native failure and reviewed Chinese/English confirmation/error
  copy. The narrow presentation file maps known local failures and actual certificate
  causes; arbitrary peer error text is not echoed or translated. It does not reclassify
  the transport's submission certainty or remove any receipt.
- In an existing EasyWork edit form, render the complete immutable Agent, account
  authority/subject, Host, workspace and runtime from the connection's domain target.
  The test target is public synthetic data, not evidence of a live desktop identity.
- Give LAN descriptor/desktop-key mismatches an explicit domain message. Pin validation,
  endpoint restrictions, binding verification and transport policy are unchanged.

## Required shared-session overlay

`MobileSession.kt` belongs to the parent integration surface. It is **not directly
committed or left changed** here. `session-hook-overlay.json` binds both own and frozen
parent bases to exact proposed hashes; the corresponding patches contain only additive
presentation checks. Verify the actual base SHA before applying; if it changed, rebase
these hunks and preserve all parent/P9/voice/discard changes. Never replace the file with
this child's copy. `NativeSheets.kt` and all P9 entry points are untouched.

The hooks capture the original incoming-link presentation in QR/code/discovery flows,
check it before saving, and check again after `runtime/context` and the workspace read
before committing/selecting a client. If a new link arrives during workspace validation,
the existing catch restores the prior credential. The original authorization record
remains available; no original input is cancelled or replayed. This shared hook is
required for `newerIncomingQrSurvivesOldApprovalWithoutDispatchingRuntime` and for a
complete stale-result fix. UI callback suppression alone is insufficient.

The compiled pair recorded in this checkpoint includes the **own-base proposed hook**.
The shared file was temporarily substituted only in this isolated tree for compilation,
then read back at its exact original hash. The recorded source manifest contains the
actual substituted bytes. The ordinary branch does not silently claim the shared hook
has been integrated. The parent must apply/rebase the parent hook, recompute its combined
source/APK fingerprints and build again before native acceptance.

## Fixture and leased native execution

The six pure Node checks exercise the synthetic issuer and negative boundaries. They
open no listener and establish no native UI, TLS or camera result. A controller may start
one new private fixture directory from the exact integrated source:

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/pairing-ui/fixture.mjs --start <new-private-directory>
```

It creates three distinct loopback origins: real HTTPS LAN descriptor/claim, HTTP device
DPoP authorization and HTTP control. Its temporary certificate belongs only to this
fixture. The parent owns all three exact same-port device reverse mappings and checks
actual PID, private source/certificate hashes and installed package fingerprints before
any input. The fixture exposes only health/begin-case/stats/offer/resolve/end-case on the
control listener, with the instance header required except health. One active case is
allowed; every native method has a new UUID and immutable end-case snapshot. No real
service or arbitrary method is proxied. Synthetic approval/rejection is the controller's
`resolve` operation; it is not an original Desktop click.

Pass complete `config.json` as `p8PairingUiConfig`, plus
`p8PairingUiBuild={"sourceSha256":"<64 hex>","appSha256":"<64 hex>","runnerSha256":"<64 hex>"}`.
The native test checks actual installed app/runner bytes. The source hash is the parent's
independently frozen input assertion. Only the exact `.acceptance` app/test pair is
allowed. The parent controls opt-in `p8UiForeground` and must never use it until its
physical-device/package lease is free. An occupied independent package is not stopped.

Select `com.agenthub.android.MobileEasyWorkPairingUiTest` methods using the four groups
in `checkpoint.json`. Every method has a 45-second JUnit limit; each group has at most
four methods and a 240-second controller deadline. Do not run beside the cold scenario
or another UI/sidecar lease. The parent must retain original runner status, separate
failed attempts, exact installed hashes, sidecar stats and app-owned screenshots. The
fixture timeout or unexpected request must remain a failed/unverified result.

Coverage prepared:

1. Provider/form reachability, retained unsent inputs and unchanged uncertain original
   business journal; Chinese/light and English/dark. Complete-target edit rows are
   exercised separately in Chinese/dark and English/light.
2. Native device-code pending status, disabled duplicate action, explicit rejection and
   expiry; invalid code/QR rejected without issuer traffic.
3. QR **text import**, expired LAN offer before network, wrong pin via actual TLS,
   changed pinned descriptor before claim, and mismatched returned binding.
4. A dropped claim remains one original attempt when switching Provider. A correctly
   authorized synthetic grant followed by unavailable runtime cannot silently call the
   success callback. A newer incoming QR survives late approval with zero runtime
   dispatch when the required session hook is present.

The original draft/client and uncertain reply journal are seeded public synthetic test
state. Tests assert actual production state/journal preservation and no business send;
they do not manufacture a real Desktop conversation or model result. The fixture
rejects runtime relay traffic with 503, so a full successful workspace connection is
**not** established. Certificate/binding results require the parent to actually run the
native methods; pure Node and compilation alone cannot establish them.

After each method the owned form is disposed, its session leaves foreground, default
storage proofs and synthetic request snapshots are preserved, then its case ends. After
the group the parent closes only this fixture and its three exact reverse mappings.
Keep original unknown claim IDs/body fingerprints and failed evidence; do not clear app
data, rerun business input, or silently substitute another target to obtain a pass.

Still external/unverified: true camera scanning/permission outcomes, real discovery,
real issuer/desktop confirmation, successful original runtime/workspace handoff,
client-to-client original desktop drafts/receipts, actual model tasks, both-platform
native results and the separate cold writer/reader lease. Central tables remain parent
owned; the exact F43/F44/F46 source and design deltas are supplied separately.
