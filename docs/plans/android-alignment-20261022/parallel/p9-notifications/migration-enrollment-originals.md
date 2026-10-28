# P14 lifecycle and P9 original-record review

The exact parent P14 twelve-file overlay was read without mutation. MainActivity is 27c5ad58, MobileSession e9c38fe9 and coordinator 4f99503e. The proposed single-file coordinator patch is recorded with all twelve base/proposed fingerprints. P7 core and parent files remain unchanged here. This review adds no native or real Host/runtime/model success.

## Finding and proposed boundary

A migrated installation with notification preferences off initializes enrollment successfully but keeps allowed=false until durable explicit enable. The supplied coordinator uses that same availability guard before local original inventory, queryOriginals and explicit off callbacks. A locally retained unknown revoke therefore disappears from inventory or cannot be queried after restart unless notification enrollment is released first. This is distinct from the correctly inert source-installation archives.

The supplied original bytes reproduce the hidden inventory in the auxiliary test. The proposed patch separates initialized, locally ready owner access from automatic enrollment. While enrollment is closed, it permits inventory of active local original records and exact retained-authority original queries. A closed off callback only queries existing originals; it never reserves a new revoke, creates an identity, substitutes a current credential or declares an unknown operation cancelled. New registration, synchronization/feed, new revoke, original-ticket navigation and gap/reset still use the existing enrollment guard. Uninitialized/failed validation, foreground loss and disposed owners remain closed. The full original target/authority/operation/receipt and archive record names are unchanged.

The patch touches only the supplied coordinator. It does not edit the P7 enrollment validator, IO tracker, journal, archive graph or Main/Session. Parent integration and native verification remain required; committing this patch file does not apply it to the parent.

## Cold ticket and owner lifecycle review

- Main still calls the closed cold selector before the default Session branch. Retained.session is extracted explicitly; recreation retains the same Session/coordinator and does not redispatch the Activity's old Intent.
- onNewIntent preserves P8 authority invalidation first, P7 cover and the actual dispatcher before the cold diagnostic. A recognized duplicate returns through observedTickets before a new navigation lookup. A bound cold Session cannot enter migration: migrationReady and therefore the migration controller ready boundary reject it.
- Notification ports still use the same Session storage and phoneReading.ledger. The actual page-twelve row and full target/credential checks precede consumption; source preference/lease/expiry rules remain. The parent store's added tombstone guard is read-only and a fresh cold namespace has no migration audit. ticket/consume code is unchanged.
- The isolated cold coordinator deliberately uses the existing acceptance constructor with no production enrollment gate and blocks polling through the Session port. Cold5 evidence therefore cannot certify production enrollment or archive validation. A fresh normal Session uses create(... migrationEnrollmentCheck=true) and starts closed.
- The new coordinator owns a child Job and its own IO tracker. dispose closes new producers and invalidates presentation/original reads; stopAndJoin waits for the child and actual tracked work before migration proceeds. Migration restores a fresh storage/Session after retirement and refuses a second migration while an old cleanup owner remains. The auxiliary exercises a held structured IO query and parent-scope responsiveness; executor/disconnect and native device quiescence still need the parent's separate tests.

MigrationController construction may create/chmod its app-private migration cache directory. The cold default-store counter does not claim zero cache-directory lifecycle effects. It still proves the selected Session did not take the default encrypted-storage branch. No SAF picker, import/export, migration activation or private-key replacement is invoked by the cold role.

## Auxiliary checks and limits

Eight coordinator/enrollment cases cover local inventory, exact revoke receipt query, closed off as query-only, rotated-credential refusal, no identity/connection or gap/reset before durable enable, failed-validation closure plus explicit retry, no new operation around a still-unknown revoke after enable, and stop/join of held IO without cancelling the parent scope. Existing shipping recovery auxiliaries remain 95 passed.

The driver compiles the supplied coordinator, NativeMigrationNotificationEnrollment and NativeMigrationIoWork bytes. It uses the P9 component core, in-memory storage, an explicit JVM Main/Looper, a display-value DTO and a fixed archive-validation-result seam. It does not validate the real P7 archive graph, tombstone parser, Android snapshot/UI/thread behavior, KeyStore, device IO cancellation, TLS or actual backend receipts. The parent store differs from the P9 core by the reviewed tombstone claim guard; these query-only cases do not exercise that claim path. Initial compiler/seam setup failures are retained separately from the reproduced baseline behavior.

Run the reusable auxiliary after component assembly, with the existing cached dependencies:

```sh
node scripts/mobile/check-chat-notification-enrollment.mjs --source-root <P14-source-root> --coordinator <reviewed-coordinator.kt> --label reviewed
```

The unmodified captured baseline fails at retained inventory. The proposed exact coordinator passes eight auxiliary cases. Full parent native migration, archive/coordinator and fresh cold5 results must stay bound to their new source/app/runner fingerprints; old cycles, unknown inputs and failed leases are never replayed. iOS A6 remains paused.
