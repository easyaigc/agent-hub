# Parent-owned documentation delta

Product F04/F06/F07/F09/F43/F44/F46, UI03, S01/S18: Android canonical object member
names now use the same slash-preserving scalar string encoder as values, matching
current EasyWork Node/iOS contract bytes. Credential/target identity, immutable submitted
input, exact operation IDs, receipt hashes, sourceGraph and crypto checks remain strict.
There is no UI/token/copy/layout change, no iOS production change and no A5 closure.

Architecture/protocol: member names are sorted with the existing comparator and encoded
through the existing canonical String path. Slash in names and values has identical
unescaped representation. The schema/version is unchanged. There is no legacy-hash
fallback or numeric/comparator redesign. Native transport AAD uses an array of fixed
fields, so its existing string framing remains unchanged. DPoP JWK member names and
normal target/identity field names are fixed ASCII and their previously valid hashes
remain unchanged. New arbitrary-key payload hashes now match the already implemented
Node contract. A wrong old escaped receipt hash remains a refusal.

Original-data audit: NativeOperationJournal hashes its stored original intent String,
validates that exact hash while decoding, and performs no easyCanonical migration.
Claim preparation persists the exact body String before submission; resumed request
code reads that original String rather than rebuilding it. DeviceDelivery authenticates
the original compact JWE protected segment parts[0], never a parsed/reserialized header.
The correction adds no write/read-repair, no fingerprint update, no session refresh,
no operation retry or network call. Existing original files, headers and receipts stay
under their original owners; opaque intent bytes are not normalized.

Feasibility/acceptance: pinned unmodified AOSP JSON sources on JVM reproduce the parent
sourceGraph failure. Old expression: five new failures, ten existing boundaries pass;
fixed expression: seventeen JVM methods pass. Twelve Node protocol/fixture and twelve
host Swift vectors match. The test-only Base64 bridge remains explicitly host-only.
Four new actual-Android checks compile but require the parent's native execution; host
Swift does not count as iOS device/build verification. Preserve original parent queue,
config, cleanup and failure evidence; new positive leases use fresh UUIDs, without
querying or replaying the unrelated original unknown operation. iOS/Task/project/A6
remain paused, and all broader A5 results retain their exact original fingerprints.
