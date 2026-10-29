# Three native preference choices and saved selection

Operation `parent-p8-source47-native-pass-next-exclusive-menu-20261029-1`.
Read-only tuple review confirms Source47's original Chinese method passed on
API37, actual portrait, light/font1. Native checkable=true/checked=true/raw
selected=false and the exact role-null owner/text/window/full guards passed.
That result keeps its original source/App/runner; Source46 stays failed. These
facts do not pass either new method.

[Exact test patch](test.patch) applies only to the full Source47 test base:

| Test     | SHA-256                                                            |
| -------- | ------------------------------------------------------------------ |
| Base     | `419a593b11a51947cdeaaa3079c5545bbb43ed9ed2ed54c4d656e2d4c039b69d` |
| Proposed | `46a4703e2573f890e03e51102c15a088ff2ba439abee2c6c22f38a0866ccfe93` |

Check [test-overlay.json](test-overlay.json). There is no production/Page/Sheet/
Session or central-file change. All four original method bodies, field/fullText/
nativeText helpers, native root/flags, Popup budget/geometry and the existing
closed role oracle remain byte-for-byte unchanged. The selected helper keeps
its exact ID/tag/label/path/native flags/actions/full bounds, accepts an explicit
expected boolean/deadline, and returns raw identity/state only after guards.
Its old callers keep expected=true and their original5s budget.

Two new actual-portrait methods cover Chinese/light and English/dark separately:
`chineseLightPreferenceOptionsStayNativeExclusiveAndPersist` and
`englishDarkPreferenceOptionsStayNativeExclusiveAndPersist`. Expected literal
labels and the known sequence automatic→lan-only→relay-only→automatic are
scripted independently. Compose/native selected flags never generate expected
values. Each open menu checks all3 actual native options: exactly1 checked and2
unchecked under the same non-Tab/no-Role/no-ToggleableState mapping. Inactive
choices still require checkable=true, raw selected=false and every original
native owner/text/action/full-boundary guard.

After each real touch, the test asserts menu semantics closed, reads the actual
model and serialized saved preference, closes the edit form using its product
footer, reopens the exact fixture client from the clients page, reopens its menu
and checks all3 native options again. Every round asserts the exact persisted
client ID. Storage reads wait for actual queued writes and decrypt/read the
actual AtomicFile metadata; only hashes and the safe enum value are saved as
proof. There is no direct setPreference call, expected-value write or model/peer
activation. This proves same-process close/reopen and stored-value consistency
when executed; process death/relaunch and imported-client behavior are not claimed.

Each triplet shares one original5-second deadline across its before/after saved
reads and all3 strict native helper invocations. No per-option timeout reset or
retry-until-desired-state is added. No input is allowed between the3 observations;
all rows must share one real native window with distinct exact owner/text IDs,
correct known tag/value/independent label hashes and strict paths. Observations
are sequential stable samples, not an atomic all-node snapshot. Ordinary
synchronous Android IPC/AtomicFile reads are unchanged and not universally
interruptible real-time operations.

The triplet aggregator rejects duplicates, mixed windows, wrong labels/paths,
invalid roles/states and expectations derived from observed checked flags.
[vector-plan.json](vector-plan.json) records36 positives (both languages, all3
expected choices, all6 row permutations),45 negatives and8 gate-removal mutations.
The81 actual extracted-helper JVM vectors passed; all8 mutations were rejected.
[reproduce-vectors.py](reproduce-vectors.py) regenerates the exact vectors from
this test, using `GRADLE_USER_HOME` and a new `P8_VECTOR_OUTPUT` directory. Run
from the checkout with the existing Java17/Kotlin2.4.21 dependencies. It creates
and removes only its own compiler scratch. These are auxiliary checks and do
not exercise native UI or storage. The first harness compile failed because its
public wrapper exposed an internal type; the wrapper was corrected to internal,
with the original failure/source retained outside the checkout.

All745 frozen Source47 input bytes and aggregate were verified. The final test
compiled successfully against that exact source set:
`36f83aa1b792d1974a2cf559d937bc0085f650147c86baab15ee087a468783dd`. Inputs were rechecked and temporary own-tree shadows
restored; parent stayed read only. An earlier auxiliary compile before the
case-specific coverage-metadata adjustment also passed and is superseded.
No new APK/native execution was produced. New methods report no injected-busy,
field-editability, IME or TalkBack coverage; existing method metadata is preserved.

Parent must freeze/build/run each new named method with a fresh matching tuple.
The other language/appearance combinations, OS font/landscape/physical lanes,
API26 WindowInspector, iOS actual menu attributes, imported cases and process
restart remain pending. [checkpoint.json](checkpoint.json) and
[parity-delta.json](parity-delta.json) retain all open gates; seven groups are
not sealed. Parent owns central registration, integration and devices. Business
projects/A6 remain paused; original unknown operations are never replayed.
A5 stays false.

The repository vector recipe independently reproduced all81 vectors/8 rejected
mutations with the same expanded-vector hash; its duplicate proof directory was
removed. `pnpm check` passed format,5 sync tests and lint, then stopped at the
existing `apps/android/localization/native-copy.json` feature-record gap; parent
owns central registration and later checks did not run. Gradle clean passed. Own
build/Gradle caches, auxiliary classes, superseded successful compile records and
duplicate recipe proof were removed. Unique failure evidence is preserved;
static logs/base were losslessly compressed and read back.
