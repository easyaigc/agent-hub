# Nonblocking pairing revocation and unactivated credential writes

The previous `lease.write -> authority.checkpoint(block)` held the revocation monitor
across `NativeSessionStorage.operationTransaction`, encryption and AtomicFile/fsync.
MainActivity Intent intake, backgrounding and form input/close could wait for that IO.
The old JVM assertion explicitly permitted this blocking and is superseded. Its original
source/log/recovery history remains in c126 and the previous form-lease archive.

`lease.write` now performs short pre/post checks with no monitor around its callback.
The actual native credential wrapper runs the production `EasyWorkConnectionWriteGate`
inside the storage transaction. It captures actual prior bytes, records ownership under
a short separate monitor, performs IO without either monitor held, then rechecks the
capability. Late/failing writes roll back only their owned value. An obsolete rollback
cannot replace a newer same-value write or a foreign changed value. Native completion
uses a receipt lookup by storage object/action/ref; even canonicalPath remains off Main.
The compatibility rollback parameter is not trusted as a prior-byte snapshot.

The Session overlay also writes each candidate to an existing credential record type
under a per-action unique ref. Existing client metadata and old credential refs remain
unchanged during validation. Only the final capability commit, after runtime/workspace
checks and receipt ownership release, publishes/selects the new client ref. That commit
contains in-memory state and queued metadata persistence, not a synchronous credential
write or storage transaction. A process crash before metadata activation leaves an
unreferenced candidate; no scan, promotion, retry or replay is introduced. Original
authorization grant/claim/unknown business journal records are not moved or revoked.
This does not claim metadata durability before its existing asynchronous save completes.

The final full Session overlay and incremental upgrade from phase2 are both provided;
check their exact base hashes. Main remains the phase3 hunk; preserve all parent P7 cover,
P9 dispatcher, voice and discard logic. Other shared files are not edited here.

Four JVM tests exercise this production write gate, including a held writer after save,
revocation/new draft returning before IO release, postcheck rollback, newer identical
write ownership, transactional baseline and write failure. The revised previous lease
case also refuses blocking. Native preparation adds two bounded actual encrypted-storage
methods: the real wrapper waiting on a held transaction, and the production gate held
after an actual encrypted write. The native methods run revocation/draft edits on Android
Main and retain the original grant/claim/unknown journal. They do not launch an Activity
or call an issuer/Host/model; prior actual form/network11 and actual Main2 lanes stay
separate. The prior native same-value rollback method continues to apply.

JVM/compilation/negative control are auxiliary evidence. Native methods are prepared only;
parent owns actual device/package scheduling. A5 is not closed and A6 remains paused.
