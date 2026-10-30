"""Root-only fixed diagnostic source. Importing/CLI use never opens ADB or a device.

No production integration or runtime binding is supplied. The raw smart-socket
transport is necessary: the stock CLI consumes shell-v2's exit packet. A future
Root harness must attest the official SDK server lease in-process; JSON cannot
mint that authority. This module never starts/stops a server or diagnostic twice.
"""
from __future__ import annotations

import dataclasses
import enum
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
import threading
import time
import uuid
from typing import Callable
from fixed_parent_ownership import OnceAdmission, AdmissionRefused


class Refused(ValueError):
    pass


def need(value, message):
    if not value:
        raise Refused(message)


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_uuid(value):
    need(type(value) is str and str(uuid.UUID(value)) == value, "canonical UUID")
    return value


def digest(value):
    need(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value), "SHA-256")
    return value


def number(value, positive=False):
    need(type(value) is str and re.fullmatch(r"[1-9][0-9]{0,9}" if positive else r"0|[1-9][0-9]{0,9}", value), "canonical integer")
    n = int(value)
    need(n <= 2147483647, "integer bound")
    return n


def closed(value, keys):
    need(type(value) is dict and set(value) == set(keys), "closed object: " + ",".join(sorted(keys)))


class Diagnostic(enum.Enum):
    PS = (b"/system/bin/ps -A -n -o UID,PID,NAME", 262144)
    AM = (b"/system/bin/dumpsys activity processes", 1048576)

    @property
    def command(self):
        return self.value[0]

    @property
    def cap(self):
        return self.value[1]


MAX_CAPTURE_SECONDS = 5.0
# No serial, UUID, nonce, APK, server address, lease or baseline is generated.
UNBOUND = dict.fromkeys(("sdkAdbAbsolutePath", "sdkAdbSha256", "serverLease",
                        "serial", "device", "sourceApkSignerTuple", "roleUids",
                        "cfgUuid", "nativeNonce", "immutableIntent", "protectionBaseline",
                        "positiveWitness", "rootCommonTargetFence", "rootSharedPrivateOnceDirectory"))


class ShellV2:
    """Incremental exact-byte decoder. Server sends only stdout/stderr/exit.

    There are no per-stream EOF packets in this protocol. Each EOF below means
    the common transport EOF was observed at a packet boundary, not an inferred
    empty stream. An exit packet alone never sets EOF or host settlement.
    """
    def __init__(self, kind: Diagnostic):
        need(type(kind) is Diagnostic, "closed diagnostic")
        self.kind = kind
        self.stdout = bytearray()
        self.stderr = bytearray()
        self.pending = bytearray()
        self.exit_status = None
        self.transport_eof = False
        self.protocol_error = None
        self.packet_count = 0

    def feed(self, chunk: bytes):
        need(type(chunk) is bytes and 0 < len(chunk) <= 4096, "bounded raw packet chunk")
        need(not self.transport_eof and self.protocol_error is None, "sealed decoder")
        self.pending.extend(chunk)
        try:
            while len(self.pending) >= 5:
                channel, size = struct.unpack_from("<BI", self.pending)
                need(channel in (1, 2, 3), "unexpected shell-v2 channel")
                need(self.exit_status is None, "packet after terminal exit")
                need(size == 1 if channel == 3 else 0 < size <= self.kind.cap, "invalid shell-v2 length")
                need(channel == 3 or len(self.stdout) + len(self.stderr) + size <= self.kind.cap, "combined stream cap")
                if len(self.pending) < 5 + size:
                    return
                payload = self.pending[5:5 + size]
                del self.pending[:5 + size]
                self.packet_count += 1
                need(self.packet_count <= self.kind.cap + 1, "packet bound")
                if channel == 3:
                    self.exit_status = payload[0]
                else:
                    (self.stdout if channel == 1 else self.stderr).extend(payload)
        except Refused as error:
            self.protocol_error = str(error)
            raise

    def eof(self):
        need(not self.transport_eof, "duplicate transport EOF")
        self.transport_eof = True
        if self.pending:
            self.protocol_error = "partial shell-v2 packet at EOF"

    def result(self, host_settled: bool, reason=None):
        streams_complete = self.transport_eof and not self.pending and self.protocol_error is None
        # Known remote failure survives a later timeout/host-transport problem.
        if self.exit_status is not None and self.exit_status != 0:
            # This includes adbd's 126 launch failure; it does not claim ps ran.
            outcome = "known-shell-service-failure"
        elif not streams_complete or self.exit_status is None or not host_settled:
            outcome = "unknown-incomplete"
        elif self.stderr:
            outcome = "known-diagnostic-failure"
        else:
            outcome = "capture-complete-unverified"
        return Capture(self.kind, bytes(self.stdout), bytes(self.stderr), streams_complete,
                       streams_complete, self.exit_status, host_settled, outcome,
                       self.protocol_error or reason)


@dataclasses.dataclass(frozen=True)
class Capture:
    kind: Diagnostic
    stdout: bytes
    stderr: bytes
    stdout_eof: bool
    stderr_eof: bool
    remote_exit: int | None
    host_settled: bool
    outcome: str
    reason: str | None
    binding_sha256: str | None = None
    invocation_id: str | None = None
    ownership_settled: bool | None = None

    def receipt(self):
        return {"kind": self.kind.name, "argv": self.kind.command.decode().split(" "),
                "stdoutBytes": len(self.stdout), "stdoutSha256": sha(self.stdout),
                "stderrBytes": len(self.stderr), "stderrSha256": sha(self.stderr),
                "stdoutTransportEof": self.stdout_eof, "stderrTransportEof": self.stderr_eof,
                "remoteStdoutPipeEof": None, "remoteStderrPipeEof": None,
                "remoteExitPacketStatus": self.remote_exit,
                "hostTransportSettled": self.host_settled, "outcome": self.outcome,
                "reason": self.reason, "bindingSha256": self.binding_sha256,
                "invocationId": self.invocation_id,
                "ownershipSettled": self.ownership_settled,
                "remoteDiagnosticCaller": None,
                "hostChildProcess": "none; direct socket client, no CLI exit status",
                "nativePassed": False}


@dataclasses.dataclass(frozen=True)
class RootLease:
    """Future in-process Root lease; deliberately no JSON loader/CLI factory.

    attest() must independently verify the live server endpoint/process, its
    PID+starttime, executable inode/hash, serial/transport and boot identity.
    Checking just this dataclass or an HTTP response does not implement attest.
    No implementation of that authority is shipped in this source candidate.
    """
    sdk_adb: Path
    sdk_adb_sha256: str
    serial: str
    server_address: tuple[str, int]
    immutable_binding_sha256: str
    invocation_ids: tuple[str, str]
    wire_byte_order: str
    attest: Callable[["RootLease"], None]

    def check(self):
        need(self.sdk_adb.is_absolute(), "official SDK absolute executable")
        digest(self.sdk_adb_sha256)
        digest(self.immutable_binding_sha256)
        # AOSP uses native uint32_t memcpy, not a portable endian conversion.
        # This source implements only the independently attested little-endian
        # SDK/adbd pair; it never guesses the device ABI or defaults a binding.
        need(self.wire_byte_order == "little", "Root-attested matching little-endian SDK/adbd required")
        need(type(self.invocation_ids) is tuple and len(self.invocation_ids) == 2 and
             len(set(canonical_uuid(v) for v in self.invocation_ids)) == 2, "future separate invocation IDs")
        need(re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", self.serial), "exact serial data")
        need(self.server_address[0] == "127.0.0.1" and type(self.server_address[1]) is int and 1 <= self.server_address[1] <= 65535, "Root-attested loopback server only")
        raw = pinned_file(self.sdk_adb, self.sdk_adb_sha256, 67108864)
        need(bool(raw), "official SDK executable bytes")
        self.attest(self)


class FixedCollector:
    """One-shot owner of actual sockets and a process-local lock.

    No retry/resume/kill/force-close/reset entry point exists. On incomplete
    capture keep this object, sockets and lock alive in the SAME process.
    Its receipt is not a continuation token for a new process.
    """
    _owner = threading.Lock()
    _sealed = []  # strong ownership even if a caller drops its local reference

    def __init__(self, lease: RootLease, admission: OnceAdmission):
        need(type(admission) is OnceAdmission, "Root must acquire exact target flock and durable once admission")
        self.lease = lease
        self.admission = admission
        self.handles = []
        self.started = False
        self.unresolved = False
        self.decoder = None
        self.host_client_pid = os.getpid()  # host provenance ONLY

    def _remaining(self, deadline):
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("original capture deadline")
        return left

    def _read(self, stream, size, deadline):
        output = bytearray()
        while len(output) < size:
            stream.settimeout(self._remaining(deadline))
            chunk = stream.recv(size - len(output))
            if not chunk:
                raise EOFError("incomplete ADB handshake")
            output.extend(chunk)
        return bytes(output)

    def _request(self, stream, service, deadline):
        need(type(service) is bytes and 0 < len(service) <= 1024, "bounded internal service")
        stream.settimeout(self._remaining(deadline))
        stream.sendall(f"{len(service):04x}".encode() + service)
        status = self._read(stream, 4, deadline)
        if status == b"FAIL":
            length_raw = self._read(stream, 4, deadline)
            need(re.fullmatch(b"[0-9a-fA-F]{4}", length_raw), "ADB FAIL length")
            length = int(length_raw, 16)
            need(length <= 4096, "ADB FAIL bound")
            message = self._read(stream, length, deadline)
            raise Refused("ADB service refused (not a remote exit): " + repr(message))
        need(status == b"OKAY", "ADB service status")

    def _connect(self, deadline):
        stream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # Save the real handle BEFORE any operation that may fail.
        self.handles.append(stream)
        stream.settimeout(self._remaining(deadline))
        stream.connect(self.lease.server_address)
        return stream

    def capture(self, kind: Diagnostic, deadline: float) -> Capture:
        need(type(kind) is Diagnostic, "closed fixed diagnostic")
        need(type(deadline) in (float, int) and 0 < deadline - time.monotonic() <= MAX_CAPTURE_SECONDS, "unchanged absolute capture deadline <=5s")
        need(not self.started, "one-shot collector; no replay")
        need(self._owner.acquire(blocking=False), "collector owner busy or sealed")
        self.started = True
        self.decoder = ShellV2(kind)
        settled = False
        ownership_settled = False
        reason = None
        try:
            self.admission.require_capture(self.lease.serial, kind.name,
                                           self.lease.invocation_ids[0 if kind is Diagnostic.PS else 1],
                                           self.lease.immutable_binding_sha256)
            self.lease.check()
            features = self._connect(deadline)
            self._request(features, b"host-serial:" + self.lease.serial.encode() + b":features", deadline)
            length_raw = self._read(features, 4, deadline)
            need(re.fullmatch(b"[0-9a-fA-F]{4}", length_raw), "feature response length")
            size = int(length_raw, 16)
            need(size <= 4096, "feature response bound")
            names = self._read(features, size, deadline).split(b",")
            need(b"shell_v2" in names, "shell-v2 required; no fallback")
            features.close()
            self.lease.check()
            stream = self._connect(deadline)
            self._request(stream, b"host:transport:" + self.lease.serial.encode(), deadline)
            self._request(stream, b"shell,v2,raw:" + kind.command, deadline)
            # Close remote stdin at protocol level; no script/stdin bytes/PTY/-x.
            stream.settimeout(self._remaining(deadline))
            stream.sendall(struct.pack("<BI", 4, 0))
            while True:
                stream.settimeout(self._remaining(deadline))
                chunk = stream.recv(4096)
                if not chunk:
                    self.decoder.eof()
                    break
                self.decoder.feed(chunk)
            self.lease.check()
            self._remaining(deadline)
            if self.decoder.protocol_error is None and self.decoder.exit_status is not None:
                stream.close()
                settled = True
                physical = dataclasses.replace(self.decoder.result(True),
                    binding_sha256=self.lease.immutable_binding_sha256,
                    invocation_id=self.lease.invocation_ids[0 if kind is Diagnostic.PS else 1])
                self.admission.settle(physical.receipt())
                ownership_settled = True
        except (OSError, EOFError, Refused, AdmissionRefused, TimeoutError) as error:
            reason = type(error).__name__ + ": " + str(error)
        finally:
            # Also preserve ownership on unexpected exceptions/interruptions.
            if ownership_settled:
                self._owner.release()
            else:
                self.unresolved = True
                self._sealed.append(self)
        result = self.decoder.result(settled, reason)
        if not ownership_settled and result.outcome == "capture-complete-unverified":
            result = dataclasses.replace(result, outcome="unknown-incomplete")
        return dataclasses.replace(result, binding_sha256=self.lease.immutable_binding_sha256,
                                   invocation_id=self.lease.invocation_ids[0 if kind is Diagnostic.PS else 1],
                                   ownership_settled=ownership_settled)


def complete_text(capture: Capture, kind: Diagnostic):
    need(capture.kind is kind and capture.outcome == "capture-complete-unverified" and
         capture.stdout_eof and capture.stderr_eof and capture.host_settled and
         capture.remote_exit == 0 and not capture.stderr, "complete independent streams and remote exit")
    text = capture.stdout.decode("utf-8", errors="strict")
    need(0 < len(capture.stdout) <= kind.cap and text.endswith("\n") and not any(c in text for c in ("\r", "\0")), "complete raw lines")
    need(not any(s in text for s in ("Permission Denial", "DUMP TIMEOUT", "Failure dumping", "Error dumping", "...")), "restricted/truncated diagnostic")
    return text


def parse_ps(capture: Capture, witness_uid: int, witness_pid: int, witness_name: str):
    text = complete_text(capture, Diagnostic.PS)
    lines = text.splitlines()
    need(len(lines) >= 4 and re.fullmatch(r"\s*UID\s+PID\s+NAME\s*", lines[0]), "full PS header/positive rows")
    rows = {}
    for line in lines[1:]:
        cells = line.strip().split(None, 2)
        need(len(cells) == 3, "PS row columns")
        uid, pid, name = number(cells[0]), number(cells[1], True), cells[2]
        need(0 < len(name) <= 512 and not any(ord(c) < 32 or ord(c) == 127 for c in name), "PS name")
        need(pid not in rows, "duplicate PS PID")
        rows[pid] = (uid, name)
    need(rows.get(1) == (0, "init"), "positive init missing; pending, never manufacture witness")
    system = [pid for pid, row in rows.items() if row == (1000, "system_server")]
    need(len(system) == 1, "positive system_server missing/ambiguous")
    need(type(witness_uid) is int and witness_uid >= 10000 and type(witness_pid) is int and witness_pid > 1 and
         rows.get(witness_pid) == (witness_uid, witness_name), "specified naturally existing test-UID witness missing")
    return rows


def one(text, pattern):
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    need(len(matches) == 1, "missing/duplicate AM field: " + pattern)
    return matches[0]


def parse_am(capture: Capture, ps_rows, witness_uid, witness_pid, witness_package, witness_component):
    """Pinned Android AM instrumentation grammar; unknown shapes fail closed.

    Require a naturally existing positive witness instrumentation and full global
    process listing. A missing/empty instrumentation section cannot prove absence.
    Every instrumentation record is parsed, including other owners.
    """
    raw = complete_text(capture, Diagnostic.AM)
    header = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
    need(raw.startswith(header), "global AM header")
    active = one(raw, r"^  Active instrumentation:$")
    # Require real process records outside instrumentation; header/title-only fails.
    prefix = raw[len(header):active.start()]
    need(prefix.startswith("  All known processes:\n"), "full known-process section required")
    listed = list(re.finditer(r"ProcessRecord\{[0-9a-f]+ ([0-9]+):([^/}\s]+)/([^}\s]+)\}", prefix))
    need(listed and any(int(m[1]) == witness_pid and m[2] == witness_package for m in listed), "AM positive global process witness")
    for match in listed:
        need(int(match[1]) in ps_rows, "AM/PS process disappeared during read; pending")
    tail = raw[active.end() + 1:]
    boundary = re.search(r"(?m)^  [^ \n]", tail)
    section = tail[:boundary.start()] if boundary else tail
    blocks = re.split(r"(?m)(?=^    Instrumentation #)", section)
    owners, witness_seen, count = [], False, 0
    for block in blocks:
        if not block.strip():
            continue
        summary = one(block, r"^    Instrumentation #([0-9]+): ActiveInstrumentation\{[0-9a-f]+ \{([^/} ]+)/([^} ]+)\}( FINISHED)? ([0-9]+) procs\}$")
        need(block.startswith(summary[0]) and number(summary[1]) == count and count < 256, "AM record prefix/index")
        count += 1
        clazz = one(block, r"^      mClass=ComponentInfo\{([^/} ]+)/([^} ]+)\} mFinished=(true|false)$")
        name = clazz[1] + clazz[2] if clazz[2].startswith(".") else clazz[2]
        summary_name = summary[2] + summary[3] if summary[3].startswith(".") else summary[3]
        need(clazz[1] == summary[2] and name == summary_name and (summary[4] is not None) == (clazz[3] == "true"), "AM component agreement")
        running = one(block, r"^      mRunningProcesses:$")
        targets = one(block, r"^      mTargetProcesses=\[([^\r\n]*)\]$")
        info = one(block, r"^      mTargetInfo=ApplicationInfo\{[0-9a-f]+ ([^} ]+)\}$")
        uid = one(block, r"^        uid=([0-9]+)(?: .*)?$")
        args = one(block, r"^      mArguments=.*$")
        need(clazz.start() < running.start() < targets.start() < info.start() < uid.start() < args.start() and block.rstrip().endswith(args[0]), "AM complete record order/tail")
        owner = number(uid[1])
        rows = block[running.end()+1:targets.start()].splitlines()
        process_count, own = 0, False
        for row in rows:
            if not row:
                continue
            p = re.fullmatch(r"        #([0-9]+): ProcessRecord\{[0-9a-f]+ ([0-9]+):([^/} ]+)/([^} ]+)\}", row)
            need(p is not None and number(p[1]) == process_count, "AM running row/index")
            process_count += 1
            pid = number(p[2], True)
            need(pid in ps_rows and ps_rows[pid][0] == owner, "AM process vanished/owner mismatch; pending")
            app = owner % 100000
            label = f"u{owner // 100000}a{app - 10000}" if 10000 <= app < 20000 else str(owner)
            need(p[4] == label, "AM running UID label")
            if pid == witness_pid and p[3] == witness_package:
                own = True
        need(process_count == number(summary[5]), "AM declared process count")
        if owner == witness_uid and clazz[1] == witness_package and name == witness_component:
            need(not witness_seen and own and process_count == 1 and clazz[3] == "false" and info[1] == witness_package and targets[1] == witness_package, "positive current witness instrumentation")
            witness_seen = True
        owners.append((owner, info[1]))
    need(count > 0 and witness_seen, "missing positive instrumentation witness; pending")
    return owners


def parse_proc_identity(status: bytes, before_stat: bytes, after_stat: bytes, context: bytes, mountinfo: bytes, expected_pid: int):
    """Parse raw REMOTE proc samples; acquisition/diagnostic-PID link is separate.

    A disappearing PID, missing sample or changed starttime raises Refused. This
    function never substitutes a previous observation or claims a command caller.
    """
    for raw in (status, before_stat, after_stat, context, mountinfo):
        need(type(raw) is bytes and 0 < len(raw) <= 65536, "all remote proc samples required")
    def ticks(raw):
        text = raw.decode("utf-8", errors="strict")
        match = re.fullmatch(r"([1-9][0-9]*) \((.*)\) (\S+) (.*)\n", text)
        need(match is not None and int(match[1]) == expected_pid, "remote stat PID")
        fields = [match[3]] + match[4].split()
        need(len(fields) >= 20 and re.fullmatch(r"[1-9][0-9]*", fields[19]), "remote stat starttime")
        return int(fields[19])
    start = ticks(before_stat)
    need(ticks(after_stat) == start, "remote PID reuse/disappeared; pending")
    text = status.decode("utf-8", errors="strict")
    pid = one(text, r"^Pid:\s+([0-9]+)$")
    uids = one(text, r"^Uid:\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)$")
    gids = one(text, r"^Gid:\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)$")
    groups = one(text, r"^Groups:\s*([0-9 ]*)$")
    need(int(pid[1]) == expected_pid, "remote status/stat PID")
    label = context.decode("ascii", errors="strict").rstrip("\n")
    need(re.fullmatch(r"[A-Za-z0-9_:.,-]{1,256}", label), "SELinux context")
    mounts = []
    for line in mountinfo.decode("utf-8", errors="strict").splitlines():
        fields = line.split()
        need("-" in fields and len(fields) >= 10, "complete mountinfo rows")
        sep = fields.index("-")
        need(sep >= 6 and len(fields) >= sep + 4, "mountinfo structure")
        if fields[4] == "/proc" and fields[sep + 1] == "proc":
            mounts.append(line)
    need(len(mounts) == 1, "unique actual remote proc mount")
    return {"remotePid": expected_pid, "starttimeTicks": start,
            "uids": [number(uids[i]) for i in range(1, 5)],
            "gids": [number(gids[i]) for i in range(1, 5)],
            "groups": [number(v) for v in groups[1].split()],
            "selinux": label, "procMount": mounts[0],
            "rawSha256": [sha(v) for v in (status, before_stat, after_stat, context, mountinfo)],
            "diagnosticCallerBound": False, "nativePassed": False}


def pinned_file(path: Path, expected_sha, maximum):
    """Read only; no symlink components, hardlinks, TOCTOU or unbounded JSON."""
    digest(expected_sha)
    need(path.is_absolute() and ".." not in path.parts, "absolute pinned path")
    directories = []
    def identity(s):
        return (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns, s.st_nlink, s.st_mode)
    try:
        directories.append(os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
        for part in path.parts[1:-1]:
            directories.append(os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directories[-1]))
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directories[-1])
        with os.fdopen(fd, "rb") as stream:
            a = os.fstat(stream.fileno())
            need(stat.S_ISREG(a.st_mode) and a.st_nlink == 1 and 0 < a.st_size <= maximum, "bounded single-link regular file")
            raw = stream.read(maximum + 1)
            b = os.fstat(stream.fileno())
            need(identity(a) == identity(b) and len(raw) == a.st_size and sha(raw) == expected_sha, "pinned file changed/hash mismatch")
            c = os.stat(path.name, dir_fd=directories[-1], follow_symlinks=False)
            need(identity(c) == identity(b), "pinned path replaced")
            return raw
    except OSError as error:
        raise Refused("pinned file unavailable or linked path") from error
    finally:
        for directory in reversed(directories):
            os.close(directory)


def json_bytes(raw):
    def pairs(items):
        value = {}
        for key, item in items:
            need(key not in value, "duplicate JSON key")
            value[key] = item
        return value
    return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(Refused("nonfinite JSON")))


JOIN_KEYS = {"device", "sourceApkSignerTuple", "roleUids", "cfgUuid", "nativeNonce",
             "immutableIntent", "protectionBaseline", "captureInvocationIds", "positiveWitness"}


def readonly_join(expected, capture_binding, native_binding, native_raw: bytes, native_sha256: str,
                  ps_capture: Capture, am_capture: Capture):
    """Strict structural join for Root review. It grants NO native authority.

    All three binding objects must independently originate at their named source;
    equality/hash pinning cannot authenticate externally imported JSON. There is
    deliberately no API returning passed=True or minting a native receipt.
    """
    for value in (expected, capture_binding, native_binding):
        closed(value, JOIN_KEYS)
        need(all(v is not None for v in value.values()), "unbound future tuple remains pending")
        canonical_uuid(value["cfgUuid"])
        canonical_uuid(value["nativeNonce"])
        d = value["device"]
        closed(d, {"serial", "avd", "api", "abi", "bootId", "bootCount", "buildFingerprint"})
        need(type(d["api"]) is int and d["api"] in (31, 32, 33) and type(d["bootCount"]) is int and d["bootCount"] >= 0, "exact API/boot count")
        canonical_uuid(d["bootId"])
        need(all(type(d[k]) is str and 0 < len(d[k]) <= 512 for k in ("serial", "avd", "abi", "buildFingerprint")), "complete device binding")
        closed(value["sourceApkSignerTuple"], {"sourceSha256", "appSha256", "runnerSha256", "examinerSha256", "signerSetSha256"})
        for item in value["sourceApkSignerTuple"].values():
            digest(item)
        closed(value["roleUids"], {"app", "runnerPackage", "examiner", "systemUi", "runnerExecution"})
        roles = value["roleUids"]
        need(all(type(v) is int and v >= 1000 for v in roles.values()) and roles["app"] >= 10000 and roles["runnerExecution"] == roles["app"] and roles["examiner"] != roles["app"], "package versus execution UID roles")
        witness = value["positiveWitness"]
        closed(witness, {"uid", "pid", "name", "package", "component"})
        need(witness["uid"] == roles["examiner"] and type(witness["uid"]) is int and witness["uid"] >= 10000 and type(witness["pid"]) is int and witness["pid"] > 1 and all(type(witness[k]) is str and witness[k] for k in ("name", "package", "component")), "exact naturally existing positive witness")
        intent = value["immutableIntent"]
        closed(intent, {"lease", "attempt", "ticket", "target", "thread", "operationId", "argv", "firstProofSha256", "newDraftProofSha256", "planSha256", "receiptSha256"})
        for key in ("lease", "attempt", "ticket"):
            canonical_uuid(intent[key])
        for key in ("firstProofSha256", "newDraftProofSha256", "planSha256", "receiptSha256"):
            digest(intent[key])
        closed(intent["target"], {"agentId", "hostId", "workspaceId", "runtimeId"})
        need(all(type(v) is str and v for v in intent["target"].values()) and all(type(intent[k]) is str and intent[k] for k in ("thread", "operationId")), "complete original target/operation")
        need(intent["argv"] == {k.name: k.command.decode().split(" ") for k in Diagnostic}, "exact fixed argv")
        closed(value["captureInvocationIds"], {"PS", "AM"})
        ids = [canonical_uuid(v) for v in value["captureInvocationIds"].values()]
        need(len(set(ids)) == 2, "separate invocation IDs")
        baseline(value["protectionBaseline"])
    need(expected == capture_binding == native_binding, "device/boot/build/APK/signer/role/cfg/intent/argv/full-baseline drift")
    bound_sha = sha(json.dumps(expected, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode())
    for captured, kind in ((ps_capture, Diagnostic.PS), (am_capture, Diagnostic.AM)):
        complete_text(captured, kind)
        need(captured.ownership_settled is True, "target flock/once journal not settled")
        need(captured.binding_sha256 == bound_sha and captured.invocation_id == expected["captureInvocationIds"][kind.name], "actual capture binding/invocation mismatch")
    review = visibility_review(ps_capture, am_capture, expected["positiveWitness"], expected["roleUids"]["app"])
    need(0 < len(native_raw) <= 65536 and sha(native_raw) == digest(native_sha256), "original native proof bytes pinned")
    native = json_bytes(native_raw)
    closed(native, {"version", "source", "binding", "parentCaptureReceipts", "protectionBaselineBefore", "protectionBaselineAfter", "allOriginalBytesUnchanged", "transportCalls", "mutationAttempts", "passed"})
    need(native["source"] == "native-postconsumer-readonly-v2-full-parent-binding", "new native acquisition contract required")
    need(type(native) is dict and native.get("version") == 2 and type(native.get("version")) is int, "native v2 proof")
    need(native.get("binding") == expected, "native proof embeds entire exact binding, not adjacent JSON")
    need(native.get("parentCaptureReceipts") == {"PS": ps_capture.receipt(), "AM": am_capture.receipt()}, "native proof pins both original captures, EOF and actual exit")
    need(native["protectionBaselineBefore"] == native["protectionBaselineAfter"] == expected["protectionBaseline"], "full original protection before/after baseline")
    need(native.get("allOriginalBytesUnchanged") is True and native.get("transportCalls") == 0 and type(native.get("transportCalls")) is int and native.get("mutationAttempts") == 0 and type(native.get("mutationAttempts")) is int, "read-only native proof assertions")
    need(native.get("passed") is True, "native result not passed")
    return {"structuralJoin": "consistent-unverified", "nativeProofSha256": sha(native_raw),
            "nativePassed": False, "runtimeBindings": None, "visibilityReview": review,
            "blockers": ["Root authenticated native evidence acquisition not wired",
                         "Exact remote diagnostic PID/starttime/kernel-credential acquisition not wired",
                         "Full real protection and natural-exit scenario not executed"]}


def baseline(value):
    closed(value, {"families", "roots", "keys", "leaseAttemptTokenClaim", "inventory"})
    for key in ("families", "roots", "keys", "leaseAttemptTokenClaim"):
        digest(value[key])
    inv = value["inventory"]
    closed(inv, {"complete", "countsAreLowerBounds", "kind", "familyCount", "directoryCount", "fileCount", "byteCount", "entryCount", "maximumDepth", "fullEntriesSha256"})
    need(inv["complete"] is True and inv["countsAreLowerBounds"] is False and inv["kind"] == "content-hash", "full inventory, no sampled/prefix/lower bound")
    for key in ("familyCount", "directoryCount", "fileCount", "byteCount", "entryCount", "maximumDepth"):
        need(type(inv[key]) is int and inv[key] >= 0, "typed inventory counts")
    need(inv["entryCount"] == inv["directoryCount"] + inv["fileCount"] and inv["familyCount"] <= inv["directoryCount"] and inv["fileCount"] <= 128 and inv["entryCount"] <= 256 and inv["byteCount"] <= 33554432 and inv["maximumDepth"] <= 8, "original full inventory bounds")
    digest(inv["fullEntriesSha256"])


def visibility_review(ps: Capture, am: Capture, witness, target_uid):
    """No absence decision unless ALL positive/complete parsing succeeds.

    Even then the result is a structural observation, not a natural-exit claim:
    remote caller provenance, earlier positive original PID/starttime and Native
    protected-state authentication remain independent required gates.
    """
    closed(witness, {"uid", "pid", "name", "package", "component"})
    need(type(target_uid) is int and target_uid >= 10000 and target_uid != witness["uid"], "independent specified witness")
    rows = parse_ps(ps, witness["uid"], witness["pid"], witness["name"])
    owners = parse_am(am, rows, witness["uid"], witness["pid"], witness["package"], witness["component"])
    return {"allTargetUidPids": sorted(pid for pid, row in rows.items() if row[0] == target_uid),
            "targetInstrumentationOwners": sum(uid == target_uid for uid, _ in owners),
            "psRowsAndInstrumentationParsed": True,
            "globalAmStructureVerified": False, "structuralReadComplete": False,
            "absenceVerified": False,
            "naturalExitVerified": False, "nativePassed": False,
            "reason": "Full AM nested-property grammar and remote-caller/native/protected-baseline acquisition remain unbound"}


if __name__ == "__main__":
    # No flags to load a lease, serial, command, runtime tuple or imported success.
    print(json.dumps({"version": 1, "kind": "unbound-source-contract",
                      "bindings": UNBOUND, "nativePassed": False,
                      "commands": {k.name: k.command.decode() for k in Diagnostic}}, sort_keys=True))
