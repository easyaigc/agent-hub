# Canonical member names: Android slash encoding correction

The parent source b1f49d99 / App f76011fa / runner 4ae1a20a refused four positive
methods at P8CommitAcceptance sourceGraphSha256, before case creation or owned files.
The original source graph and its 76c85c26c22aa9ef0cf1025e5ca58ef52d1dbbbb234fe58894ab0fbb345b3852
hash remain unchanged. The fixture, graph comparison, crypto framing, package check and
case IDs are not weakened or regenerated. The failed UUIDs must not be reused.

AOSP JSONObject.quote delegates to JSONStringer, whose string switch escapes slash.
The Android encoder already removed that escape for String values, but object member
names called JSONObject.quote directly. Consequently the exact failed four-file graph
produced 55f8ef8980d40a7fbf09667581f1bdc734a22ce34fea1afba482a4a0c6257ca2
on the old Android path. The production correction is one expression: encode a key
through easyCanonical(String), the same existing scalar path used for its value.
There is no alternate hash, accepted legacy encoding or changed comparison.

The installed host org.json:json:20250517 does not reproduce Android's plain-slash
escaping. Auxiliary checks therefore compile six byte-exact AOSP implementation sources
at de876a01b29230b877c9f408348c38a90ee724a5, pinned by URL/SHA in aosp-source-lock.json.
Only compile-time annotation definitions are supplied; no AOSP implementation is edited.
The runtime test asserts the actual quote result and its aosp-json.jar origin, preventing
a silent fallback to the host replacement or Android stub jar. This is host execution of
pinned Android source, not a device run or a claim that this commit identifies the device.
[AOSP source](https://android.googlesource.com/platform/libcore/+/de876a01b29230b877c9f408348c38a90ee724a5/json/src/main/java/org/json/JSONStringer.java).

## Independent bytes and falsifiable checks

The single versioned input is AndroidTest asset easywork-canonical-keys-v1.json.
vectors.mjs checks 12 slash, quote, Chinese, supplementary, combining, control,
line-separator, nesting, sorting, exact source graph and request-hash examples against
both the existing Node fixture and the delivered protocol canonical function extracted
from implementation.patch (only its TypeScript signature is erased). It also verifies a
fixed public-only JWE with an unsorted/slash-escaped protected header: original bytes
open, reserialization of the protected header fails authentication. Existing vectors,
production identities and original exported operation files are never regenerated.

swift-check.mjs executes the exact current easyWorkJSON function extracted from the
unchanged iOS source with macOS Foundation/CryptoKit. All12 expected UTF-8 bytes and
SHA-256 values match. This is a host Swift check, not iOS build/device evidence; iOS and
A6 remain paused. The scope is member/string escaping and current non-numeric protocol
keys, without introducing a general RFC8785/number-format contract.

The old production expression is a negative control: five of seven new JVM methods
fail, while all10 existing Session/crypto boundary methods pass. With the one-line fix,
all17 pass. Session recovery uses the independent Node request hash containing slash
and quote/Unicode keys; it emits one synthetic write and one status read, accepts the
correct original receipt and rejects the old escaped hash without replay. The public
JWE still authenticates its ORIGINAL protected segment; changing only its encoding
fails. Previously valid declared-key fingerprints remain identical.

Four new NativeEasyWorkCanonicalTest methods compile for actual Android: all12 vectors
and actual platform quote observation, strict graph tampering, original JWE AAD, and
actual encrypted unknown-journal reopen. The last checks exact original intent/hash
and rejects semantically equivalent reserialization without persisting a new hash.
Every method requires the exact acceptance package/build hashes, takes a read-only
snapshot of default encrypted files and uses only a fresh synthetic namespace if disk
is needed. It does not create a Session, network client or submission. These four are
prepared, not run here. Parent owns rebuilt combined app/runner and device leases.

## Reproduction and source ownership

Run prepare-aosp.mjs with a new private output folder, then pass its aosp-json.jar to
validation.init.gradle via p8AndroidJsonJar. A previously downloaded source directory
may be supplied with --sources; all six lock hashes are still mandatory. The network
fetch attempt was a Node connection timeout; offline recipe output was independently
checked to contain the same eight org.json class files as the tested input. Run Android Gradle through the existing
parent build slot, with the already reviewed child-only Session/Main/resource overlays;
select P8CanonicalTest and P8EasyWorkBoundaryTest. The existing boundaries initializer
still compiles its original test class without the new suite. swift-check.mjs takes a
new private output folder. Normal vectors.mjs verifies the frozen asset; --write is an
explicit synthetic fixture-generation action, not a repair of original data.

The shared five source files, parent checkout, Host, devices, sidecars and original
unknown operation are untouched. This stage adds no runtime fallback or retry, changes
no numeric handling and emits no real command/model input. All actual native results
retain their own source/package/environment fingerprints. Parent-reported viewport4,
IME2, voice4 and fresh-API37 Main2 passes belong to its earlier candidate; the original
phone Main guard remains separate. Positive4/cold2 and the rebuilt canonical gates are
still parent owned. Product/design/acceptance and architecture/protocol deltas accompany
this leaf for merge, rather than overwriting parent-owned central documents.
