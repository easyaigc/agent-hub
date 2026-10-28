# Permission-cycle synthetic backend continuity

The parent's API37 run reported twelve of fourteen original OS methods passing, with the channel visibility assumption and permission-cycle grant reader failing. The original denial writer ran once and passed; the first grant reader returned `mutation-unknown-original-query`, with zero required-reading calls. Original failure proofs, encrypted cycle data and permission-restoration evidence stay preserved. This workline read the supplied logs/proofs only and did not execute any device, permission, Host or model action.

## Why the restored reader stays unknown

`NativeChatNotificationOsBase.cycle` derives the same fixture epoch from the cycle UUID in both processes. `RecoveryNativeFixture.receipts`, however, is a process-local map initialized empty. The original writer generates an applied synthetic response and the shipping journal records that response. A fresh reader fixture has no corresponding backend cache entry. The shipping controller always queries the exact original receipt again, including for a locally applied operation; an absent result yields unknown without resubmission or feed processing. This guard remains unchanged.

The bounded JVM regression recreates this exact lifetime split with two fixture instances and the shipping controller. It requires the unknown result, no submit/feed/delivery, unchanged journal bytes and cursor one. This is auxiliary evidence for the fixture defect, not proof of the original device's private journal. Epoch is preserved; the synthetic receipt map is lost. The old native result remains failed.

## Read-only original-cycle inspection

Parent may select only `NativeChatNotificationOsCycleInspectionTest#inspectLegacyOriginalBytesWithoutQueryReplayOrJournalWrite` with its original `p9SystemLease` and exact `p9SystemCycle`. It works under the current permission state. It requires the exact debug acceptance package, existing owned directory, existing AES handle, bounded regular encrypted files and no partial AtomicFile/unknown material. It reads only the original cycle marker, identity bytes and journal, validates the shipping schema and records hashes, operation/response stage, epoch comparison, cursor and terminal outcome. It never loads/creates an identity, calls the controller/commands, writes the original store, changes permission or touches system notification resources.

Physical encrypted-file hashes must be identical before and after. Output is a new unique app-private file under `files/acceptance/P9-native-system-inspection/<lease>/`; it contains hashes and state only, no private key or full response. A partial/unknown original fails preflight and stays untouched. The original raw journal was not available to this workline for decryption; this native inspection remains not-run. A recorded synthetic response is not independent real Host confirmation. Inspection does not repair or rerun the old cycle, including the separate physical phone cycle.

## Independent recorded-response case

The two new native selectors are separate from the original failed methods:

1. `NativeChatNotificationOsDeniedTest#durableFixtureCycleDenialRecordsActualSyntheticResponseForNewGrantCase`
2. `NativeChatNotificationOsGrantedTest#durableFixtureCycleRecoveryUsesRecordedResponseWithoutOriginalResubmit`

Parent must allocate a fresh canonical `p9SystemCycle` UUID and use the same explicit `p9SystemLease` across these two selectors. Never use an existing API37/physical/unknown cycle or rerun a writer. Do not run whole classes. Parent owns permission denial/grant/restoration and separate native processes. Reader rejects the writer PID/process nonce, foreign lease, changed epoch/identity, missing or changed original/response, unknown fields and a legacy marker. The independent result cannot replace the old failure.

After the new denial writer's actual synthetic submit/response completes, it captures the existing fixture response object from the command cache. It requires exact equality with the full response recorded by the shipping journal and seals its canonical bytes/hash, epoch, immutable original hash, identity hash, cycle/lease and writer process in a v2 marker. It never constructs a replacement response from a local applied flag. The new reader restores only that recorded response into its test backend query cache; the normal shipping controller then queries it. Fresh event delivery, old terminal `system-unavailable`, cursor one-to-two, original operation/identity/response integrity and zero new submit remain mandatory assertions. Marker bytes stay unchanged; native OS cleanup retains private cycle evidence for parent review.

No production controller, recovery journal, target/credential guard, shared five-file hook, default data or key policy changes. The original fourteen OS selectors remain; two independent native selectors and one inspection selector are additional and unverified here. New helper/tests compile locally and five additional JVM cases pass, including tamper rejection and refusal to create an applied marker from an unknown operation. Real Android process/permission execution, system UI, actual Host/runtime/model and complete DEV remain separate. Cold first-Activity work stays in its own commit and exact parent overlay; iOS A6 stays paused.

F52/F53, UI11, S15/S16: recovery requires a response for the immutable original; local applied state never substitutes for backend confirmation. iOS source and evidence are unchanged. The supplied parity delta records implementation and auxiliary checks separately from native outcomes.
