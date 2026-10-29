# OEM runtime inventory and exact default-file hashes

The parent adopted the original controller and ran a new candidate20 preflight. Its
first execution failed in prepare because Xiaomi API33 returned exit0 for
`dumpsys activity instrumentation` with `Unknown command: instrumentation` and
`Bad activity command`. This was a correctly sealed format rejection: no native
method, case, listener, reverse mapping or reader started. The original reserved UUID
`a9d81a9f-b9e1-43c9-bf4a-e9ba5018426d` stays sealed. Any later execution needs a new
preflight directory, plan and UUID; there is no continuation/retry of that attempt.

The parent's saved observation reports that full `dumpsys activity` exited0 with
699491bytes,17 recognized Activity Manager section headers and no active instrumentation
marker. Its existing native wrapper uses that full runtime command. The committed
`oem-inventory-observed.json` preserves this exact small observation: it is a summary,
not the full device dump. No device serial, private state, live PID or credentials are
included. Tests derive a full-header projection with explicitly synthetic bodies and a
699491-byte sized variant. These auxiliary tests do not claim actual full-device body
validation or a new device pass.

## Runtime inventory semantics

Before this correction, `_instrumentation` required the short subcommand's dedicated
header. It now reads only full `dumpsys activity`, with a hard2MiB output cap and the existing5s
per-command deadline. A nonzero command exit, timeout, cap breach, empty text, invalid
encoding/control characters, explicit command/permission/dump error or unknown format
blocks before starting any instrumentation. Installed runner metadata from `pm` is
never used as evidence of runtime idleness.

The recognized full structure requires unique SETTINGS, ACTIVITIES, RUNNING PROCESSES,
USERS and LOOPERS headers in that order. SETTINGS must be the first recognized full
section and LOOPERS the last; Activities, Running Processes and Loopers require
nonempty bodies. Other full sections from the observed OEM output are retained in
the scan and cannot duplicate. CRLF and LF are supported without changing state
values. A case-insensitive `Active instrumentation:` marker anywhere in the recognized
full dump reports busy, preserving any existing runner. No stop or steer fallback
exists. Unknown or incomplete variants continue to block; API26/API37 compatibility
and the actual API33 full-body parse still require parent native verification.

This unchanged5s inventory budget is separate from the unchanged native writer stop window.
No full dump is introduced between native writer ready and its identity-checked
interruption. The18s limit,20ms margin, existing writer/reader bounds, PID/UID/boot/
process-generation checks, immutable config/build/sourceGraph and original ciphertext/
proof/default hashes remain unchanged.

## Default collection semantics

The original failed execution collection recorded only `defaultsReadError: ValueError`; there
is no saved failing command/stdout, so a SHA separator mismatch is a hypothesis rather
than a confirmed cause. The previous parser accepted exactly two spaces after the
hash. The corrected parser accepts one or more spaces/tabs as separators while
requiring the same lowercase64-digit SHA and exact requested filename. It does not
trim/rewrite paths, accept binary markers, ignore errors or silently drop missing,
duplicate, extra or substituted `.new`/`.bak` entries. The same parser also verifies
installed APK hashes and the original six ciphertext hashes.

The later parent-only read-only check of the adopted code passed full AMS parsing
(`activeInstrumentation: false`, target PID absent), without a plan, UUID, App launch,
case, sidecar or reverse. Its default collection failed at family-names because an
unrestricted root `ls` exceeded262144bytes before filtering. The preserved
`oem-defaults-observed.json` binds that result to its actual source hashes. That
failure is a confirmed discovery blocker; it does not establish an SHA-format cause.
The successful read-only full parse belongs to the prior source fingerprint, not a
new writer/reader or final-controller native pass.

Current discovery uses one fixed root-only predicate and retains the256KiB cap/5s
budget:

```sh
find no_backup -mindepth 1 -maxdepth 1 \( -name mobile-v1 -o -name 'mobile-v1-*' \) -print
```

No unrestricted `ls` or recursive search of unrelated namespaces occurs. Only matching
root paths are emitted. Exact `no_backup/mobile-v1` or safe `no_backup/mobile-v1-*`
paths are accepted, with at most64 unique families. Unexpected paths, malformed names,
duplicates, excess matches and failed discovery never become an empty default map.
A legitimate empty match set is allowed. Before file discovery, a separate bounded
metadata-only `find` on those exact families rejects every entry that is neither a
directory nor a regular file, including root/nested symlinks. Links are not followed.
Only then are regular files found and hashed; at most1024 unique exact paths are
accepted. Descendant/traversal/foreign-path rejection is unchanged.

The native relative-key format stays unchanged. Existing original, `.new` and `.bak`
files are all hashed and retained; no restoration, deletion, rename, write, Main
launch or default selection occurs. Each find keeps a256KiB cap and5s timeout.
Hashing batches at128paths retains the512000-byte cap/5s timeout. The six original
lineage files still use one command during the writer checkpoint. Empty legitimate
default storage remains an empty map; failed reads are never converted to empty
values. The number/size of unrelated root entries does not consume the selected-family
output budget; enumeration itself still has the same5s command deadline.

Failure snapshots retain the legacy `defaultsReadError` type and add bounded
`defaultsReadDetails` identifying existence, bounded family discovery, family type validation, file find or hash-batch
stage. Diagnostics contain no file/stdout contents. The next parent run can therefore
distinguish command failure from path/format rejection without changing original data.

## Validation and next native gate

The complete offline suite now has62 passing tests: the original42 plus16 OEM/default
boundary tests and4 bounded-discovery tests, including all previous timing, byte limits, cleanup and no-reentry
cases. Adapter operations are mocked; local subprocesses are bounded Python transports and
a host-only `find` integration against1200 temporary synthetic namespaces. That
synthetic name set exceeds the previous output cap while only the selected default
family is emitted; a selected symlink blocks before any hash. Temporary files are
removed by the test.
This leaf performed zero ADB queries, device operations, listeners, native methods,
Host requests or model inputs, and generated no APK. Fixture sourceGraph, cold Kotlin
source, production Android/iOS code, UI/copy/tokens and parent checkout are unchanged.
Product/design/acceptance merge registration remains parent-owned via parity-delta.

Parent must adopt the exact final controller files and first verify only the named
read-only inventory/PID/default discovery on its device. Only after those checks pass
may it preflight the current selected candidate with a fresh UUID and grant a fresh
exclusive execution lease before any native role starts. It then owns the existing
writer-interrupted/reader-new-process sequence and resource cleanup. The old sealed
UUID and all real unknown operations remain untouched. Task/project work and iOS
stay paused, A5 is open and A6 remains paused.
