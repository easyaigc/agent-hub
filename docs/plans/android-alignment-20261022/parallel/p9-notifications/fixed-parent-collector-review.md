# Fixed parent collector source for Root review

This stage adds executable, unbound Python source in
`scripts/mobile/fixtures/parent-collector-v1`. It changes no App, Host, Native
observer, shared reading ledger or runtime configuration. Every future serial,
APK/signer/source tuple, cfg UUID, native nonce, intent, lease and baseline remains
null in `fixed-parent-collector-v1.json`. The previous disabled proposal and
7330ee9 receipts retain their original meaning.

The module's ordinary entry point only prints that unbound contract:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/mobile/fixtures/parent-collector-v1/fixed_parent_collector.py
```

There is no command/config loader or production endpoint. A future Root harness
would have to supply an in-process `RootLease.attest` implementation before using
the fixed transport. This candidate supplies no live attestor, server startup,
server shutdown, discovery or automatic fallback. Its fixed services are exactly
PS and AM; no user argv, script, stdin, PTY, `-x` or stderr merge is accepted.

## Implemented behavior

`FixedCollector.capture` negotiates `shell_v2` for the exact assigned serial on an
independently attested existing official SDK adb server, selects that transport,
opens the fixed `shell,v2,raw:` service and closes remote stdin. It preserves raw
stdout and stderr separately, with the existing combined caps (262144/1048576)
and the original absolute deadline, at most five seconds. It does not invoke the
adb CLI. Each collector instance submits at most one diagnostic.

`ShellV2` checks channel, length, cap, packet boundary and terminal ordering before
accepting a one-byte exit status. A nonzero exit is a known shell-service failure,
including the daemon's 126 start failure; it does not imply that ps itself ran.
A later host failure does not erase that known failure. Missing/malformed frames,
overflow, timeout and unobserved exit remain incomplete. Unknown ownership retains
the actual sockets, decoder and lock by strong reference in the same process.
There is no replay, reset, timeout kill or new-process continuation API.

Receipt fields distinguish observed transport EOF, actual remote exit and local
socket settlement. There is no CLI child process to settle. ADB shell-v2 has no
independent clean pipe-EOF frames: `remoteStdoutPipeEof` and
`remoteStderrPipeEof` therefore remain null. The exact raw streams are never
modified to add completion markers or fill empty stderr.

The source implements little-endian framing only. AOSP copies native `uint32_t`
lengths; a future Root attestor must verify the actual SDK/adbd pair's byte order.
The lease also requires the official SDK executable's absolute path/hash and
independent live endpoint/PID/starttime/executable/device checks before and after
capture. A path/hash or a device feature response alone is insufficient.

## Conservative parsing and provenance

PS parses every row, rejects malformed or duplicate PIDs, and requires positive
init, unique system_server and the exact naturally existing specified test-UID
witness. A missing witness stays pending. It never creates a process, polls to
make a witness appear, kills anything, starts a consumer or substitutes an old
PID. Instrumentation parsing requires complete records, owner/process agreement
and the positive witness. Missing sections, title-only output and unfamiliar
shapes are rejected. Cross-read process disappearance remains pending.

The AM parser intentionally does **not** claim full nested global-AM grammar or
absence. Android's global known-process list has no declared count, and the
instrumentation section is conditional. `visibility_review` reports
`globalAmStructureVerified=false`, `structuralReadComplete=false` and
`absenceVerified=false`; its parsed matches cannot grant a natural-exit result.
Root must implement/review a full fail-closed grammar before accepting absence.

`parse_proc_identity` checks separate raw remote status/stat/context/mountinfo
samples, UID/GID/groups and matching PID/starttime before/after the read. Its
result always has `diagnosticCallerBound=false`: shell-v2 contains no diagnostic
PID/starttime, mount-namespace or credential provenance. No subsequent shell,
host client PID, DUMP permission or public UiAutomation return is promoted into
the actual remote reader's identity. A correlated acquisition source is still
required and is not fabricated by this parser.

## Read-only Native join

`readonly_join` requires closed, non-null, identical device serial/AVD/API/ABI/
boot/build, source/APK/signer, package versus execution UID roles, cfg UUID,
native nonce, original intent/target/operation/argv, invocation IDs and complete
protection inventory bindings. It joins actual completed PS/AM `Capture` objects
and their receipts to the raw pinned Native proof bytes. It rejects drift,
sampled/lower-bound inventories, missing Native binding and mismatched captures.
The file reader is bounded and read-only, rejects symlinks/hardlinks, checks
before/after inode/size/timestamps and content hash, and JSON rejects duplicate
keys and nonfinite numbers.

Even complete structural agreement returns `consistent-unverified` and
`nativePassed=false`. External JSON, adjacent binding files, an HTTP success or
a manually asserted `passed` flag cannot supply authenticated Native acquisition.
The current Native v2 result does not embed this complete new binding/receipt
contract; no existing proof is upgraded or existing Native parser modified.

Root still needs the live server lease attestor, correlated diagnostic caller
provenance, clean remote stream-completion evidence, full AM grammar,
authenticated Native acquisition and the actual protection/natural-exit scenario.
These gates are explicit implementation limitations, not native test passes.

## Review and acceptance scope

The companion source map pins this stage's files, official source findings and a
fresh read-only subset of Root inputs. It is not a complete implementation or
environment fingerprint. The parity delta preserves all 21 gap rows and P7's
single reading/unread ledger ownership; central parity/acceptance files remain
with Root. iOS/UI tokens/copy are unchanged.

Small stdlib vectors exercise framing, malformed/partial/known/unknown outcomes,
actual handle retention using in-memory fakes, parser refusal, tuple drift and
read-only pinning. They are auxiliary evidence only. No ADB invocation, device,
AVD, Native, GUI, notification, Host or model task is performed by this stage.
API33 5574 remains a retained environment, not an assigned test device. Protected
consumers and unknown operations remain untouched; A6 stays paused.

## Official source basis

All findings are source review of `android-13.0.0_r1`, not a runtime claim:

- [ADB client](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/client/commandline.cpp),
  lines 289–314: CLI consumes protocol packets and can retain its default zero
  when no exit packet is received.
- [Shell protocol implementation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/shell_service_protocol.cpp),
  lines 32–61, and [header](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/shell_protocol.h),
  lines 43–57, 95–104: channel/header layout and native byte-order copying.
- [Shell daemon](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/daemon/shell_service.cpp),
  lines 391–393, 555–596, 696–705, 739–821: fixed text runs through shell `-c`,
  stream forwarding, close-stdin and exit/launch-failure behavior.
- [ADB services](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/SERVICES.TXT),
  lines 41–45, 63–67, and [client handshake](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-13.0.0_r1/client/adb_client.cpp),
  lines 114–195, 408–432: serial selection, response framing and feature query.
- [Process list](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/am/ProcessList.java),
  lines 3988–4039, and [ActivityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java),
  lines 9578–9586, 9893–9915: global dump and conditional instrumentation section.
