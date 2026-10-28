# Independent stage validation

2026-10-28; candidate from `1e908bea`, workline `p7-reading-voice`.

- `pnpm install --frozen-lockfile --offline`: passed; no dependency/lockfile changes, independent node_modules.
- Through the shared two-slot build wrapper: `pnpm exec node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest :mobile-core:jvmTest -PagentHubAcceptance=true --console=plain`: passed on the candidate. KMP JVM XML reports 93 tests, zero failures/errors/skips. The package contains the native tests; no Android instrumentation run occurred in this workline.
- Production `ThreadReading.kt` and event predicate in `NativeReadingLedger.kt` with compiled `NativeReadingLifecycleTest` / `NativeReadingPresentationTest` on host JVM/JUnit: 8 tests passed. This checks exact text/status/projection, generation fencing and the P9 old-event/seen-tombstone counterexamples; it is host auxiliary evidence, not Android native execution.
- Initial test-runner compile failed because `androidx.test.rule.GrantPermissionRule` is not a dependency. The tests now use a simulated permission callback with fake recognizers and never grant a device permission themselves. The failed build log is preserved; later candidate build passed.
- `pnpm check`: formatting/GitHub-sync checks/lint passed, then stopped at the required central parity guard: `Feature change record missing or stale for .../VoiceInputController.kt`. No downstream checks are claimed. The coordinator must integrate this workline's parity delta and regenerate the central manifest before rerunning the complete check. The gate was not weakened.
- `git diff --check`: passed.

Private evidence contains per-file source inventory, Gradle logs, root/parity failed-check logs, KMP XML, host-JVM log, copied APK and runner plus SHA-256, recovery bundle and remote receipt. Paths are supplied in the chat delivery receipt rather than committed machine-local examples. Build APK/runner fingerprints and source hashes must be checked after any integration; this standalone package does not prove shared UI registration or notification delivery.

No ADB, install, force-stop, am instrument, Xcode, desktop owner operations, new model input, FCM/site work or business F65-F67/UI14-UI19/UM06-UM08 operations were performed. Unknown submission `863ec69e-e647-4f5e-bdd0-5b294b9823ac` was not queried or replayed. iOS A6 remains paused. No PR/main merge was created.
