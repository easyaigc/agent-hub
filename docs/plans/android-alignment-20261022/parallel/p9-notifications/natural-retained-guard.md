# P9 natural retained reconstruction and producer inventory delta

Operations `parent-p9-natural-retained-guard-review-20261029-1` and `parent-p9-producer-default-family-proof-20261029-1`, following `bed48006`. Parent still has not adopted the natural startup proposal. Source34's reported actual API26 store5 all-pass result remains valid for that component fingerprint; native33's original failed attempt remains retained. This workline changes no parent source, device, Host, ProbeService or shared Inventory implementation.

## Source-reachable retained counterexample

In `55cef40e`/`bed48006`, natural Main could finish constructing its Session and return `Retained` on configuration reconstruction. The next Activity saw `ColdStart.active != null` and skipped `NativeNaturalLaunch`; ColdStart then accepted the same retained Session through its legacy active branch. The next Activity had no natural owner-map entry, making `owns(this)` false and allowing ordinary incoming Intent dispatch. A second Activity count making a proof fail would not preserve the ingress guard. This is a source/timing counterexample, not a device reproduction.

The minimal proposal now **rejects natural active reconstruction before Session selection**. ColdStart exposes a read-only natural-mode flag; legacy active readers remain distinguishable. The debug facade uses the same pure lifecycle policy as the auxiliary checks. An active legacy reader returns to its existing path and may retain normally. An active natural process installs a blocking owner, displays a fixed acceptance refusal, never calls the Main continuation or ColdStart selection, and appends at most one `recreation-rejected.json` marker under the original entry fence. It neither claims again nor opens a default/retained Session. The original proof/ciphertext/receipt remains unchanged. This is explicit refusal, not configuration-resume support.

Main's proposed `onRetainCustomNonConfigurationInstance` consults the facade and returns no retained Session for a natural owner. Proposed `onNewIntent` consults the facade's ingress policy before any Session access. Ownership also recognizes the process's existing natural active binding when an Activity map entry is missing, so that map loss cannot restore ordinary incoming dispatch. Pending/destroyed preparation still leaves its terminal entry fence; a later attempt cannot resume it. The release facade keeps ordinary ready-state behavior and introduces no acceptance storage access.

The five source32-base patches are one atomic proposal with their debug/release facades and unchanged scanner relocation. **Main and ColdStart patch proposed hashes changed**; the Storage and two manifest patches remain byte-identical to `55cef40e`. Parent must review/rebase the complete set; do not combine the new facade with an old Main/ColdStart patch or overwrite parent source34. [natural-os-hooks.json](natural-os-hooks.json) records each exact base/proposed hash and relocation. Neither this checkout's common source/manifests nor the parent checkout is edited.

## Producer default-family proof

The prior producer's fixture `baseFiles` and `defaultSelections=0` did not cover default-family changes during seeding/posting; the consumer's later baseline could not supply that earlier evidence. The producer now scans all original `mobile-v1*` families **before seeding** and again in `finally`, on IO, using the byte-identical relocated `NativeNaturalFamilyInventory` and its original 128-file/256-entry/depth8/32MiB bounds. No limit is raised or data cleared.

A complete content-hash baseline is required before seed. Finally emits `familiesBefore`, `familiesAfter`, `defaultInventoryComplete`, `defaultUnchanged` and `defaultInventoryFailureType`, containing only counts/digests/reason metadata. `passed=true` requires both complete content-hash scans and the same non-null digest as well as the existing producer checks. An incomplete baseline stops before seed; an incomplete/changed final scan fails the JUnit method even after an otherwise successful post. Its original publication/permit and any prior failure stay retained. When another failure already exists, its type is preserved while the inventory failure is recorded separately. `defaultSelections` remains diagnostic and never substitutes for the inventory.

The execution checklist now requires these producer fields. This is a source and compile change; no native producer default-family proof is claimed until the parent executes the new integrated fingerprint.

## Auxiliary and actual scope

Fourteen checks run against the actual proposal entry fence and the same pure lifecycle policy used by the debug facade. The original eight filesystem/sync-failure/symlink/race checks remain. Six lifecycle counterexamples cover ineligible no-probe startup, ordinary no-material startup, legacy active retained compatibility, natural active reconstruction after owner loss, ready/pending ingress/retention isolation, and cancellation before Session followed by an original-fence restart refusal. The reconstruction sequence verifies no continuation/ordinary dispatch is admitted and that the actual original entry file is unchanged. These are JVM policy/filesystem checks with modeled callbacks, not Android Activity, retained Session or SystemUI execution.

Private combination App/runner/examiner debug and release Kotlin compilation is repeated after the delta. The source32-base combination is independent of parent source34's Binder fix; it must not replace the parent's integrated source. Exact new compile/source/package fingerprints and restoration records are delivered outside the worktree. Natural API31+ execution, minAPI26 natural coverage, actual configuration refusal, SDK duplicate, post-exit authentication, physical/OEM and vendor/Host/model results remain unverified. A6 and task/project work remain paused.

| Historical 0b7455b4 private proposal tuple   | SHA-256                                                            |
| -------------------------------------------- | ------------------------------------------------------------------ |
| Atomic five-patch/base/proposed/source tuple | `74a38f732f1cf93adaf747c99563d721cd035d432c8394b49cbc705a31deb428` |
| Effective 726-file source map                | `ea16c835b4e5fa95ec0bba288c1d63ec83439dc70a2676c36c1afe41dfe00ad8` |
| Debug App                                    | `db4aebb483ce8d614d7237d82b94735bccc99d559095fd8cce7772b90195d7f2` |
| Debug runner                                 | `930de298019dd05646bc886adca39a5d29f83d70abacec3f16515216ef8105c7` |
| Self-target examiner                         | `be09f2dc120f08f48f7a477822e1c0a608efe276734e348aa18a318494c8a850` |

All five patches were reversed to their exact declared source32 base bytes and reapplied together to their exact proposed hashes. The tuple digest is SHA-256 over compact sorted-key JSON containing `source32`, `patches`, `patchSha256`, `proposalSources` and `unchangedRelocation` from the hooks index. All three APK ZIPs/v2 signatures and same-signer relationship pass. These remain private proposal outputs; parent must integrate with its current source34 code and record a new runtime fingerprint.

The later [positive visibility witness delta](natural-process-visibility.md) leaves these five common patch bytes unchanged and adds an examiner parser dependency plus ordered live-writer custody acknowledgement. Use the current hooks source tuple for adoption.
