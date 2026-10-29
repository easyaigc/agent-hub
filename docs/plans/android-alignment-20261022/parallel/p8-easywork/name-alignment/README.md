# Preserve the existing EasyWork name like iOS

Parent operation `parent-p8-source35-native-feedback-next-20261029-1` requests the
minimal correction to the source difference found in the
[initial presentation audit](../presentation/README.md). iOS
`SessionStore.renameAgentClient` rejects trimmed-empty input but saves the original
string. Android's existing rename removes leading/trailing whitespace. For an
existing connection named `  Computer 中文 😀 %@  `, the proposal saves that complete
string while continuing to reject whitespace-only input.

## Exact shared-file proposal

Apply [session.patch](session.patch) only after checking
[session-overlay.json](session-overlay.json). Parent owns the shared Session merge;
this leaf did not edit the shared production file or parent checkout.

| Input                                     | SHA-256                                                            |
| ----------------------------------------- | ------------------------------------------------------------------ |
| Frozen Source35 Session                   | `20aacbc4a7947f5c9c71dbebb1df52ec30d66b7a80ac602fc1f3871fa8d59bd8` |
| Proposed Session                          | `a6d0bb7d0af7a8026c3c9436847d871ec65e75ce6043172b654bb43d16771133` |
| iOS EasyWorkPairing.swift reviewed source | `1c443b52ac637b22fccec2df78fb6bb40290655953cf2c0471abe3ca8d5ae616` |

The sole changed expression is `it.copy(name=name.trim())` → `it.copy(name=name)`.
The existing blank and256-character guards, current client lookup, normal/imported
eligibility, `replaceClient` and `persistMetadata` remain. The operation still
updates the latest live row rather than the editing snapshot. Target, candidate
routes, saved preference, credential pointer, marker, identity and immutable inputs
are untouched. The existing Android256-character limit has no matching explicit
guard in the reviewed iOS rename; this narrow patch preserves that existing limit
and does not claim complete validation parity. First-time pairing-name behavior
is outside this existing-name edit correction.

Candidate writeback after credential IO already merges from the latest client.
That source remains byte-for-byte unchanged, so a late discovery candidate refresh
does not replace the accepted complete name with its earlier snapshot. No metadata
format or target/account/permission policy changes.

## Boundary and race evidence

The source-slice JVM harness compiles the exact rename, replace and candidate-update
methods plus actual models, schema and imported marker. Its outer state, held
credential store and metadata-name witness are synthetic. It passes17 tests with
the proposed method; the original method compiles but fails4 tests. This verifies
the name correction against counterexamples, not Android storage or native UI.

The cases retain the prior candidate race guards and cover accepted ASCII/Unicode
boundary whitespace, empty and whitespace-only refusal, accepted256/rejected257
lengths, imported marker preservation, stale editor versus current fields, removed
or ineligible clients, and a rename while credential IO is blocked followed by
late candidate publication. Original unknown-input/grant bytes and the draft stay
unchanged. Persisted-name observations in this harness are a witness to calls;
actual encrypted metadata serialization remains a separate native assertion.

Reproduce both variants using the exact frozen inputs and cached dependencies:

```sh
python3 <stage>/run-source-jvm.py --repo <frozen-Source35> \
  --session-source <exact-base-or-proposed-MobileSession.kt> \
  --output <fresh-private-output> --gradle-cache <modules-2/files-2.1> \
  --android-jar <installed-API37-android.jar> --java <jdk17-java> \
  --build-slot <parent-build-slot.py> --variant <base-or-proposed>
```

## Four native presentation methods prepared

The existing Chinese/light test now expects the raw saved string, paired with the
actual production proposal. A dedicated fourth method,
`boundaryWhitespaceRenameKeepsLastSavedRawNameForNormalAndImported`, navigates the
actual App's normal and imported editors, preserves complete boundary whitespace,
rejects empty/ASCII/Unicode whitespace-only edits, checks live and actual metadata
names, and closes/reopens the product editor to verify the last accepted raw name.
The private fixture, original journal/credentials/draft, readonly targets and
default-family bounds remain unchanged. This is not a fixture-only expectation
change against the old trimming production path.

The other three groups retain the
[presentation execution contract](../presentation/README.md). All original
overflow, complete UTF-16, glyph and actual clip checks remain. Labels and fixed
short `easywork` do not require a wrap; the controlled short-window case requires
real wrapping of its five long target identities. Existing parent-owned discovery
UI tests and long-name wrapping checks are untouched.

Native AX reads now wait up to the same5-second Compose readiness budget for the
exact target App root, recording null/other-package sample counts. They never
inspect another App subtree or issue input to make a root ready. The final root
still must be the target package; readonly/editable/selected assertions remain.
This addresses asynchronous AX availability without a synthetic owner or pass.

All707 frozen Source35 input bytes and aggregate were verified. With the exact
Session proposal and two exclusive presentation files, all709 inputs passed
AndroidTest Kotlin compilation and postcompile verification:
`88163ecbe6ba0e69d9971db60217ce664ac4f62186eabfc30ceacc9e54ed1f2c`.
Temporary own-tree overlays were restored byte-for-byte. The earlier successful
compile before the wrap-condition correction is preserved separately. No new APK
or native execution was produced. All four methods remain **unexecuted** and
require a new integrated source/App/runner tuple; Source35's existing APKs do not
contain these cases or the rename correction.

Parent reports Source35 old UI5/5 and store5/5 on actual API26. Parent's API37 NSD
results remain distinct: actual discovery stop passed, while owned service
registration timed out within its original12-second budget before resolution;
advertisement cleanup joined and original permissions were restored. No leaf
rerun or substituted result is claimed. Exact reported fingerprints are retained
in [parity-delta.json](parity-delta.json).

Parent retains central registration, shared Main/Session/Page/old tests, integration
and device scheduling. No device, ADB, Host, permission, pairing, model, independent
DEV or business action ran here. API37 actual landscape, physical UI, full
font/accessibility matrix, actual action busy/error/pending, MainActivity lifecycle
and existing issuer/Desktop/TLS/default-scale/model gates remain open. A5 is false;
A6 remains paused. Root-check and stage-cleanup outcomes are recorded separately.

Root `pnpm check` passed format, the five sync-rule tests and lint, then stopped at
the existing `native-copy.json` central feature-record gap; later checks did not
run. Gradle clean, unoccupied own `.build`/`.gradle` and generated JVM classes/sources
were removed. Source/hash, failure and compile logs remain compressed outside the
worktree with read-back verification.
