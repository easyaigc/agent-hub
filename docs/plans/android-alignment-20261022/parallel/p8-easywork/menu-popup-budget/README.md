# Menu Popup acquisition within the original budget

Operation `parent-p8-menu-popup-original-budget-review-fix-20261029-1` continues
the P8 task. Parent found that9de's initial Espresso root query could enter an
independent long wait before first evidence/try. Parent had not adopted or
executed9de. This patch corrects that initial root-budget guarantee; no native
result is upgraded.

Apply [test.patch](test.patch) after the previous Source43 menu-owner patch,
checking [test-overlay.json](test-overlay.json). The exact9de test base is
`b0107d5f57770ff1a23da368939c8b829e64acc54fd7b6e8b99db6757e6e70c4`; proposed is
`96c4cb13ef2d6c1bad649d80508d34aefb5ce463eadeb7aba0e4e4a39542cb7c`. No Page/padding patch is repeated.

Initial evidence is atomically saved inside the failure-recording try before
Popup or UiAutomation acquisition. A missing/unsupported/blocked root retains
its initial SDK/API probe and failure class without saving body text.

The public [WindowInspector API](https://developer.android.com/reference/android/view/inspector/WindowInspector) takes one read-only snapshot of attached
current-process windows. The helper requires one exact own PopupLayout root,
root-view equality, attachment and real TYPE_APPLICATION_SUB_PANEL, with64 roots
maximum. Zero/multiple candidates fail. There is no dialog, arbitrary parent or
flag-based root fallback. Geometry rechecks that retained view's identity and
actual native window ID before the preserved UiAutomation same-window checks.

Main-thread reads use a scoped FutureTask with timed get of only the remaining
original5-second deadline. Pending callbacks are removed/cancelled on every
exit. An already-running synchronous read may finish later; it only builds a
private return value. Its cancelled future discards that value and cannot write
probes or input, while the caller does not wait for completion. Popup acquisition
and geometry no longer enter Espresso root selection or main-sync idle waiting.
No global timeout change, listener or new retry is introduced. Other synchronous
framework/native IPC is unchanged; this is not a universal interruptible real-time
guarantee for Android IPC.

WindowInspector is API29+. Below29 this diagnostic explicitly fails after saving
initial evidence. The production minSdk26 is unchanged. API26-28 diagnostic/native
coverage stays unverified; no alternate root or skipped guard counts as a pass.
[window-inspector-audit.json](window-inspector-audit.json) records local SDK37
source/API fingerprints.

The exact extracted main-read helper passed8 auxiliary JVM concurrency cases
using Handler/Looper/clock stubs: success, original failure, stalled queue,
cancelled late queued execution, already-running blocked read, expired deadline,
rejected queue and main-caller rejection. This is not Android window/device
verification. [checkpoint.json](checkpoint.json) records the helper hash/result.

All734 frozen Source43 inputs plus the final owned test compiled successfully:
`dbf997e02da2ef51c9303cf00f91293427465855e8212357027858ec10edfe2c`. Input bytes were rechecked, own-tree shadows restored,
and parent sources stayed read only. New APKs/native executions are zero. Parent
must combine both exact patches and run a fresh matching source/App/runner.
Source43's old landscape/OS2 short passes keep their original fingerprints;
its Chinese menu failure remains failed.

The complete field/fullText/nativeText helpers, Compose owner identity and all
four test bodies remain exact. All native owner ID/tag, independent unique text,
strict parent-ID path, selected/enabled/clickable/visible, actual Click action and
full native/Compose bounds remain exact; only geometry receives the deadline.
Compose selected and native checked do not substitute. Actual owner selected is
still unmeasured, so no product fix is claimed. Parent owns devices, integration
and central registration; [parity-delta.json](parity-delta.json) supplies its
update. Business projects and A6 remain paused; unknown inputs are not replayed.
A5 remains false.

`pnpm check` passed format,5 sync tests and lint, then stopped at the existing
`apps/android/localization/native-copy.json` feature-record gap. Parent owns the
central update; later checks did not run. Gradle clean passed; the owned build/
Gradle caches and JVM auxiliary classes were removed after open-handle checks.
Static logs/base were losslessly compressed and read back.
