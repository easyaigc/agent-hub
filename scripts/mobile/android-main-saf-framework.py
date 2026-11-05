#!/usr/bin/env python3
"""Closed framework-v4 EXPORT CLI, admitted only by fresh Root runtime/source/SDK pins.

One controller preparation, exact original receipt/owner join, one original Native export,
provider receive ACK and stable audit join. The distinct parent-v1 SDK admits only the
exact protected platform files container and new namespace-v4 locked publication;
Native protocol stays framework-v4. Fresh explicit-FD Root/SDK/runtime purposes
exclude old v3 UNKNOWN scopes. IMPORT
execution has no CLI/admission path.
Local CLI actual waits/dual EOF are mandatory; raw remote shell-v2 exit frames are not
exposed by stock adb and are reported unobserved. Immutable original provider receipts
establish their exact effects, never local rc0 alone. No old helper/fallback/replay exists.
"""
from dataclasses import dataclass, field
import hashlib
import math
import json
import os
import re
import selectors
import shlex
import stat
import subprocess
import time
import uuid
import argparse
import importlib.util
import math
import sys
from pathlib import Path
from types import SimpleNamespace
sys.dont_write_bytecode = True
from typing import Optional

PROFILE = "p7-main-saf-framework-v4"
SDK_PROFILE = 'p7-main-saf-explicit-fd-v1'
APP = "com.easyai.agenthub.android.acceptance"
TEST = APP + ".test"
CONTROL = TEST + ".p7-main-saf-control-v4"
DOCUMENTS = TEST + ".p7-main-saf-v3"
CONTROLLER_PROCESS = TEST + ":p7MainSafControlV4"
DOCUMENT_PROCESS = TEST + ":p7MainSafV3"
NAMESPACE = "files/p7-main-saf-owned-v4"
CAP, PAYLOAD_CAP, STDERR_CAP = 16384, 2 * 1024 * 1024, 2 * 1024 * 1024
ZERO = "0" * 64
INTENT_KEYS = frozenset(("schema", "profile", "run", "operationId", "kind", "appUid", "testUid", "docsUid", "docsPackage", "bootUuid",
    "appApkSha256", "testApkSha256", "certificateSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256",
    "profileSha256", "rootApprovalSha256", "transportContractReviewSha256", "serialSha256", "adbSha256", "expiresWallMillis",
    "expectedPayloadBytes", "expectedPayloadSha256", "sourceProducerProofSha256"))
EVENT_KEYS = frozenset(("schema", "run", "kind", "operationId", "documentId", "authority", "intentSha256", "providerProcess"))
ACK_KEYS = EVENT_KEYS | frozenset(("status", "size", "sha256", "eofObserved", "receiverFdClosed", "sinkFdClosed", "fileSynced",
    "directorySynced", "payloadPublished", "expectedFingerprintBound", "peerStatusIsAcknowledgement"))


def require(ok, message):
    if not ok:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def sha(value):
    require(type(value) is str and re.fullmatch(r"[a-f0-9]{64}", value), "Exact SHA-256")


def canonical_uuid(value):
    require(type(value) is str and str(uuid.UUID(value)) == value, "Canonical UUID required")


def exact_json(raw):
    require(type(raw) is bytes and 0 < len(raw) <= CAP, "Bounded raw PFD JSON required")
    def pairs(rows):
        value = {}
        for key, item in rows:
            require(key not in value, "Duplicate JSON key")
            value[key] = item
        return value
    return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


@dataclass(frozen=True)
class SourceSnapshot:
    payload: bytes = field(repr=False)
    producer_proof_sha256: str

    @classmethod
    def freeze(cls, path, producer_proof_raw, expected_bytes, expected_sha256, independently_checked_producer):
        """Actual producer waits/EOF/closed output must be checked by the new admission.
        A caller's assertion is not evidence; the proof bytes and independent source ledger
        are joined by Root. This function additionally freezes one bounded local file.
        """
        require(independently_checked_producer is True, "Original producer exit/FD proof not admitted")
        require(type(producer_proof_raw) is bytes and 0 < len(producer_proof_raw) <= CAP, "Bounded immutable producer proof")
        require(type(expected_bytes) is int and 0 < expected_bytes <= PAYLOAD_CAP, "Original producer length required")
        sha(expected_sha256)
        path = os.fspath(path)
        require(os.path.isabs(path) and path == os.path.realpath(path) == os.path.abspath(path), "Exact canonical producer path")
        parent_path, basename = os.path.split(path)
        parent_fd = os.open(parent_path, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
        fd = None
        try:
            parent_before = os.fstat(parent_fd)
            require(stat.S_ISDIR(parent_before.st_mode) and parent_before.st_uid == os.getuid() and stat.S_IMODE(parent_before.st_mode) == 0o700,
                "Private original producer parent")
            fd = os.open(basename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd)
        except BaseException:
            os.close(parent_fd)
            raise
        try:
            before = os.fstat(fd)
            require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and before.st_nlink == 1 and
                stat.S_IMODE(before.st_mode) == 0o600 and 0 < before.st_size <= PAYLOAD_CAP, "Private bounded source file")
            raw = bytearray()
            while True:
                chunk = os.read(fd, min(65536, PAYLOAD_CAP + 1 - len(raw)))
                if not chunk:
                    break
                raw.extend(chunk)
                require(len(raw) <= PAYLOAD_CAP, "Source bound")
            after = os.fstat(fd)
            current = os.lstat(path)
            keys = lambda s: (s.st_dev, s.st_ino, s.st_uid, s.st_mode, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            require(keys(before) == keys(after) == keys(current) and len(raw) == before.st_size, "Source changed during freeze")
            parent_after = os.fstat(parent_fd)
            parent_current = os.lstat(parent_path)
            require((parent_before.st_dev, parent_before.st_ino, parent_before.st_uid, parent_before.st_mode) ==
                (parent_after.st_dev, parent_after.st_ino, parent_after.st_uid, parent_after.st_mode) ==
                (parent_current.st_dev, parent_current.st_ino, parent_current.st_uid, parent_current.st_mode), "Producer parent changed")
            require(len(raw) == expected_bytes and digest(raw) == expected_sha256, "Original producer fingerprint differs")
            return cls(bytes(raw), digest(producer_proof_raw))
        finally:
            try:
                os.close(fd)
            finally:
                os.close(parent_fd)


@dataclass(frozen=True)
class Intent:
    raw: bytes

    @classmethod
    def admit_metadata(cls, value, source=None):
        require(type(value) is dict and set(value) == INTENT_KEYS, "Exact controller intent keys")
        require(value["schema"] == "p7-main-saf-controller-intent-v4" and value["profile"] == PROFILE and value["kind"] in ("EXPORT", "IMPORT"), "Closed framework purpose")
        for key in ("run", "operationId", "bootUuid"):
            canonical_uuid(value[key])
        for key in ("appUid", "testUid", "docsUid"):
            require(type(value[key]) is int and 0 < value[key] < 100000, "Exact user0 UID")
        require(value["appUid"] >= 10000 and value["testUid"] >= 10000 and len({value[k] for k in ("appUid", "testUid", "docsUid")}) == 3, "Distinct actual App/Test/Docs UIDs")
        require(value["docsPackage"] == "com.google.android.documentsui", "Only separately admitted API33 system profile")
        require(type(value["expiresWallMillis"]) is int and 0 < value["expiresWallMillis"] < 2**63, "Bounded expiry")
        for key in INTENT_KEYS:
            if key.endswith("Sha256"):
                sha(value[key])
        require(type(value["expectedPayloadBytes"]) is int, "Exact payload length type")
        if value["kind"] == "IMPORT":
            require(type(source) is SourceSnapshot and 0 < len(source.payload) <= PAYLOAD_CAP, "Original exited producer snapshot required")
            require(value["expectedPayloadBytes"] == len(source.payload) and value["expectedPayloadSha256"] == digest(source.payload) and
                value["sourceProducerProofSha256"] == source.producer_proof_sha256 != ZERO, "Immutable source fingerprint/producer binding")
        else:
            require(source is None and value["expectedPayloadBytes"] == 0 and value["expectedPayloadSha256"] == value["sourceProducerProofSha256"] == ZERO,
                "Export fingerprint is independently joined after original Native writes")
        raw = canonical(value)
        require(len(raw) <= CAP, "Bounded metadata")
        return cls(raw)

    def value(self):
        return exact_json(self.raw)

    def uri(self, action):
        require(action in ("read-original-receipt", "read-payload-ack", "receive-input", "audit"), "Closed exact URI")
        v = self.value()
        require(action != "receive-input" or v["kind"] == "IMPORT", "Only import receiver")
        return f"content://{CONTROL}/v4/{v['run']}/{v['operationId']}/{v['kind']}/{action}"

    def command(self, action):
        v = self.value()
        if action in ("prepare-export", "prepare-import"):
            require(action == "prepare-" + v["kind"].lower(), "Exact preparation kind")
            result = ["content", "call", "--user", "0", "--uri", "content://" + CONTROL, "--method", action, "--arg", v["operationId"]]
            for key in sorted(INTENT_KEYS):
                kind = "i" if key.endswith("Uid") else "l" if key in ("expiresWallMillis", "expectedPayloadBytes") else "s"
                result += ["--extra", f"{key}:{kind}:{v[key]}"]
            return result
        return ["content", "write" if action == "receive-input" else "read", "--user", "0", "--uri", self.uri(action)]

    def shell_argv(self, adb, serial, action):
        require(type(adb) is str and adb.startswith("/") and "\0" not in adb, "Pinned absolute adb")
        require(type(serial) is str and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial), "Exact explicit serial")
        return [adb, "-s", serial, "shell", "-T", shlex.join(self.command(action))]


@dataclass
class OriginalChild:
    """Future launcher's strong Popen owner. This class does not spawn a process.
    Own exact stdin/stdout/stderr and actual Popen object; no new child/operation on resume.
    A capture deadline/overflow leaves the same object UNKNOWN with live FDs retained.
    """
    child: subprocess.Popen
    command: tuple
    start_observation: str
    input_payload: bytes = field(repr=False)
    deadline: float
    stdout_limit: int = CAP
    started_capture: bool = False
    state: str = "UNKNOWN"
    stdout: bytearray = field(default_factory=bytearray, repr=False)
    stderr: bytearray = field(default_factory=bytearray, repr=False)
    stdout_hash: object = field(default_factory=hashlib.sha256, repr=False)
    stderr_hash: object = field(default_factory=hashlib.sha256, repr=False)
    stdout_bytes: int = 0
    stderr_bytes: int = 0
    sent_bytes: int = 0
    stdout_eof: bool = False
    stderr_eof: bool = False
    stdin_closed: bool = False
    actual_wait_returned: bool = False
    return_code: Optional[int] = None
    overflow: bool = False
    selector: object = field(default=None, repr=False)

    def drain_once(self, tick=None):
        require(not self.started_capture, "Original capture cannot replay/re-enter; retain UNKNOWN owner")
        require(tuple(self.child.args) == self.command and self.child.pid > 0 and bool(self.start_observation), "Exact original child identity")
        require(type(self.input_payload) is bytes and len(self.input_payload) <= PAYLOAD_CAP, "Bounded original stdin")
        require(self.child.stdout is not None and self.child.stderr is not None, "Owned separate pipes required")
        self.started_capture = True
        # Freeze one finite original deadline before any descriptor or selector I/O.
        # A rejected capture remains UNKNOWN and cannot re-enter with a replacement limit.
        capture_deadline = self.deadline
        now = time.monotonic()
        require(type(capture_deadline) is float and math.isfinite(capture_deadline) and
            0.0 < capture_deadline - now <= 300.0, "Finite original capture deadline within300seconds required")
        require(type(self.stdout_limit) is int and self.stdout_limit in (CAP, STDERR_CAP), "Fixed stdout capture cap")
        self.selector = selectors.DefaultSelector()
        for name, pipe in (("stdout", self.child.stdout), ("stderr", self.child.stderr)):
            os.set_blocking(pipe.fileno(), False)
            self.selector.register(pipe, selectors.EVENT_READ, name)
        if self.child.stdin is not None:
            os.set_blocking(self.child.stdin.fileno(), False)
            if self.input_payload:
                self.selector.register(self.child.stdin, selectors.EVENT_WRITE, "stdin")
            else:
                self.child.stdin.close(); self.stdin_closed = True
        else:
            require(not self.input_payload, "Original import stdin unavailable")
            self.stdin_closed = True
        try:
            while True:
                remaining = capture_deadline - time.monotonic()
                if remaining <= 0:
                    return self  # UNKNOWN; no terminate/replay/descriptor ownership transfer.
                for key, mask in self.selector.select(min(0.1, remaining)):
                    pipe, name = key.fileobj, key.data
                    if name == "stdin":
                        try:
                            n = os.write(pipe.fileno(), self.input_payload[self.sent_bytes:self.sent_bytes + 65536])
                        except (BlockingIOError, InterruptedError):
                            continue
                        except BrokenPipeError:
                            self.selector.unregister(pipe); pipe.close(); self.stdin_closed = True
                            return self
                        self.sent_bytes += n
                        if self.sent_bytes == len(self.input_payload):
                            self.selector.unregister(pipe); pipe.close(); self.stdin_closed = True
                        continue
                    try:
                        raw = os.read(pipe.fileno(), 65536)
                    except (BlockingIOError, InterruptedError):
                        continue
                    if not raw:
                        self.selector.unregister(pipe); pipe.close(); setattr(self, name + "_eof", True)
                        continue
                    setattr(self, name + "_bytes", getattr(self, name + "_bytes") + len(raw))
                    getattr(self, name + "_hash").update(raw)
                    limit = self.stdout_limit if name == "stdout" else STDERR_CAP
                    target = getattr(self, name)
                    available = max(0, limit - len(target))
                    target.extend(raw[:available])
                    if len(raw) > available:
                        self.overflow = True  # Continue draining and hashing, never insert/truncate bytes silently.
                if tick is not None and not (self.stdout_eof and self.stderr_eof):
                    require(time.monotonic() < capture_deadline, "Original native observer deadline")
                    tick()
                if self.stdin_closed and self.stdout_eof and self.stderr_eof and self.child.poll() is not None:
                    self.return_code = self.child.wait(timeout=max(0.001, capture_deadline - time.monotonic()))
                    self.actual_wait_returned = True
                    completed_on_time = time.monotonic() < capture_deadline
                    self.state = "LOCAL_CLOSED" if completed_on_time and not self.overflow and self.sent_bytes == len(self.input_payload) else "UNKNOWN"
                    self.selector.close()
                    return self
        except Exception:
            # Keep the exact Popen and remaining descriptors/selector strongly held for
            # original-owner inspection/closure. No fabricated wait, EOF or operation receipt.
            raise

    def require_local_closed(self, expected_command):
        require(self.command == tuple(expected_command) and self.state == "LOCAL_CLOSED" and self.actual_wait_returned and self.return_code == 0 and
            self.stdout_eof and self.stderr_eof and self.stdin_closed and not self.overflow, "UNKNOWN: original child waits/EOF/FD close incomplete")
        require(self.child.stdout.closed and self.child.stderr.closed and (self.child.stdin is None or self.child.stdin.closed) and self.child.returncode == self.return_code,
            "UNKNOWN: owned descriptor/process facts not closed")
        require(self.stderr_bytes == 0, "UNKNOWN: preserve stderr bytes/hash/category before receipt join")
        # This gate alone never establishes remote effect. No remote exit frame inferred.


def same_process(self_report, independently_observed, expected_name, test_uid, boot_uuid):
    keys = {"pid", "uid", "startTicks", "bootUuid", "argvSha256", "argvExactName", "processNonce"}
    require(type(self_report) is dict and set(self_report) == keys and self_report == independently_observed, "Independent exact process identity required")
    require(self_report["uid"] == test_uid and self_report["bootUuid"] == boot_uuid and self_report["argvExactName"] == expected_name, "Wrong actual provider")
    require(type(self_report["pid"]) is int and self_report["pid"] > 0 and type(self_report["startTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", self_report["startTicks"]), "Exact process facts")
    sha(self_report["argvSha256"])
    canonical_uuid(self_report["processNonce"])


def receipt(intent, raw, controller_observed):
    v, result = intent.value(), exact_json(raw)
    require(set(result) == {"schema", "run", "operationId", "kind", "intentSha256", "controllerProcess", "status", "scopePublished", "directorySynced", "payloadAccepted", "payloadFdClosed"}, "Exact preparation receipt")
    require(result["schema"] == "p7-main-saf-controller-receipt-v4" and result["run"] == v["run"] and result["operationId"] == v["operationId"] and result["kind"] == v["kind"] and
        result["intentSha256"] == digest(intent.raw), "Original receipt binding differs")
    require(result["status"] == "PREPARED" and result["scopePublished"] is True and result["directorySynced"] is True and result["payloadAccepted"] is False and
        result["payloadFdClosed"] is True, "Preparation is not acknowledged")
    same_process(result["controllerProcess"], controller_observed, CONTROLLER_PROCESS, v["testUid"], v["bootUuid"])
    return result


def payload_ack(intent, raw, independent_receiver, expected_native_output=None):
    v, result = intent.value(), exact_json(raw)
    require(set(result) == ACK_KEYS, "Exact payload ACK")
    require(result["schema"] == "p7-main-saf-document-event-v4" and result["run"] == v["run"] and result["operationId"] == v["operationId"] and result["kind"] == v["kind"] and
        result["documentId"] == "p7-" + ("input" if v["kind"] == "IMPORT" else "output") + "-" + v["run"] and result["authority"] == DOCUMENTS and
        result["intentSha256"] == digest(intent.raw) and result["status"] == "PAYLOAD_COMMITTED", "Original payload ACK differs")
    expected_process = CONTROLLER_PROCESS if v["kind"] == "IMPORT" else DOCUMENT_PROCESS
    same_process(result["providerProcess"], independent_receiver, expected_process, v["testUid"], v["bootUuid"])
    for key in ("eofObserved", "receiverFdClosed", "sinkFdClosed", "fileSynced", "directorySynced", "payloadPublished"):
        require(result[key] is True, "Payload receipt requires EOF/close/fsync/publication")
    require(result["peerStatusIsAcknowledgement"] is False and result["expectedFingerprintBound"] is (v["kind"] == "IMPORT"), "Peer status cannot stand for ACK")
    require(type(result["size"]) is int and 0 < result["size"] <= PAYLOAD_CAP, "Bounded payload size")
    sha(result["sha256"])
    expected = (v["expectedPayloadBytes"], v["expectedPayloadSha256"]) if v["kind"] == "IMPORT" else expected_native_output
    require(type(expected) is tuple and len(expected) == 2 and type(expected[0]) is int and type(expected[1]) is str and result["size"] == expected[0] and result["sha256"] == expected[1], "Original producer/Native fingerprint differs")
    return result


def await_original_payload_ack(intent, adb, serial, read_original_once, controller_observed, receiver_observed, deadline, expected_native_output=None):
    """A future admitted launcher supplies one NEW read-only child per observation.
    No write/preparation/Native method is dispatched. Every read is separately owned and
    actually waited; a PENDING JSON is an observation, never an immutable acknowledgement.
    Returned owners retain evidence. Unknown child/receipt failures stop without retry.
    """
    original_deadline = deadline
    require(type(original_deadline) is float and math.isfinite(original_deadline) and
        0 < original_deadline - time.monotonic() <= 300, "Original finite ACK observation deadline")
    v, owners = intent.value(), []
    command = intent.shell_argv(adb, serial, "read-payload-ack")
    for index in range(16):
        require(time.monotonic() < original_deadline, "UNKNOWN: original payload ACK deadline")
        owner = read_original_once(tuple(command), original_deadline)
        owners.append(owner)
        require(type(owner) is OriginalChild, "Original owned read child required")
        owner.require_local_closed(command)
        result = exact_json(bytes(owner.stdout))
        if result.get("schema") == "p7-main-saf-ack-observation-v4":
            require(set(result) == {"schema", "run", "operationId", "kind", "intentSha256", "controllerProcess", "status"} and
                result["run"] == v["run"] and result["operationId"] == v["operationId"] and result["kind"] == v["kind"] and
                result["intentSha256"] == digest(intent.raw) and result["status"] == "PENDING", "Malformed pending observation")
            same_process(result["controllerProcess"], controller_observed, CONTROLLER_PROCESS, v["testUid"], v["bootUuid"])
            remaining = original_deadline - time.monotonic()
            require(remaining > 0, "UNKNOWN: original payload ACK deadline")
            time.sleep(min(0.1, remaining))
            continue
        # FAILED, malformed, truncated and foreign receipts are rejected. Their original
        # operation/scope is retained; never issue another copy, prepare or model input.
        return payload_ack(intent, bytes(owner.stdout), receiver_observed, expected_native_output), tuple(owners)
    raise ValueError("UNKNOWN: original payload ACK read-count bound")


def join_original_import(intent, adb, serial, preparation, preparation_read, original_write, final_ack_read,
                         controller_observed, receiver_observed):
    """Join existing owners only; never launches/resends. Actual original local waits/EOF plus receipts and exact SDK/producer/Native
    admission remain mandatory; raw shell-v2 exit frames are reported unobserved.
    This join is auxiliary, not a full-P7 or remote-lifecycle proof.
    """
    v = intent.value()
    require(v["kind"] == "IMPORT", "Closed import join")
    for owner, action in ((preparation, "prepare-import"), (preparation_read, "read-original-receipt"),
                          (original_write, "receive-input"), (final_ack_read, "read-payload-ack")):
        require(type(owner) is OriginalChild, "Actual original Popen owner required")
        owner.require_local_closed(intent.shell_argv(adb, serial, action))
    receipt(intent, bytes(preparation_read.stdout), controller_observed)
    require(original_write.sent_bytes == v["expectedPayloadBytes"] and digest(original_write.input_payload) == v["expectedPayloadSha256"] and
        original_write.stdout_bytes == 0, "Exact original input bytes; CLI parser output cannot pass")
    return payload_ack(intent, bytes(final_ack_read.stdout), receiver_observed)


def stable_audits(intent, raw_a, raw_b, controller_observed, receiver_observed, expected_native_output=None, consumer_closed=False, document_provider_observed=None):
    v = intent.value()
    require(type(consumer_closed) is bool and (not consumer_closed or v["kind"] == "IMPORT"), "Closed audit stage")
    name = "input.bin" if v["kind"] == "IMPORT" else "output.bin"
    expected_files = {"publication.lock", "controller-owner.json", "scope.claimed", "intent.json", "scope.json", "receipt.json", "payload.claimed", name, "payload-ack.claimed", "payload-ack.json"}
    if v["kind"] == "EXPORT":
        expected_files |= {"document-owner.json", "create.claimed", "create.json", "open.claimed", "open.json"}
    if consumer_closed:
        expected_files |= {"document-owner.json", "read-open.claimed", "read-open.json", "read-close.claimed", "read-close.json"}
    values = [exact_json(raw) for raw in (raw_a, raw_b)]
    for result in values:
        require(set(result) == {"schema", "run", "operationId", "kind", "intentSha256", "controllerProcess", "status", "files", "documents", "auditReadFdsClosed"}, "Exact audit keys")
        require(result["schema"] == "p7-main-saf-controller-audit-v4" and result["run"] == v["run"] and result["operationId"] == v["operationId"] and
            result["kind"] == v["kind"] and result["intentSha256"] == digest(intent.raw) and result["auditReadFdsClosed"] is True and
            result["status"] == ("CONSUMER_FD_CLOSED" if consumer_closed else "STAGING_COMMITTED"), "Original audit stage incomplete")
        same_process(result["controllerProcess"], controller_observed, CONTROLLER_PROCESS, v["testUid"], v["bootUuid"])
        require(set(result["files"]) == expected_files and set(result["documents"]) == {n for n in expected_files if n.endswith(".json")}, "Exact completed files")
        for filename, pin in result["files"].items():
            require(set(pin) == {"bytes", "sha256"} and type(pin["bytes"]) is int and 0 <= pin["bytes"] <= (PAYLOAD_CAP if filename == name else CAP), "Bounded audit file")
            sha(pin["sha256"])
            if filename.endswith(".claimed") or filename == "publication.lock":
                require(pin == {"bytes": 0, "sha256": digest(b"")}, "Empty exclusive event claim or stable publication lock")
        require(result["documents"]["intent.json"] == v and result["files"]["intent.json"] == {"bytes": len(intent.raw), "sha256": digest(intent.raw)}, "Immutable intent bytes")
        require(result["documents"]["scope.json"] == {"schema": 1, "run": v["run"], "kind": v["kind"], "appUid": v["appUid"], "docsUid": v["docsUid"]}, "Exact original scope")
        receipt(intent, canonical(result["documents"]["receipt.json"]), controller_observed)
        require(result["documents"]["controller-owner.json"] == controller_observed, "Original controller owner witness differs")
        if v["kind"] == "EXPORT" or consumer_closed:
            require(result["documents"]["document-owner.json"] == document_provider_observed, "Original document owner witness differs")
        ack = payload_ack(intent, canonical(result["documents"]["payload-ack.json"]), receiver_observed, expected_native_output)
        require(result["files"][name] == {"bytes": ack["size"], "sha256": ack["sha256"]}, "Audit payload differs from ACK")
        def document_event(name, extras):
            event = result["documents"][name + ".json"]
            require(set(event) == EVENT_KEYS | extras and event["schema"] == "p7-main-saf-document-event-v4" and
                event["run"] == v["run"] and event["operationId"] == v["operationId"] and event["kind"] == v["kind"] and
                event["documentId"] == "p7-" + ("input" if v["kind"] == "IMPORT" else "output") + "-" + v["run"] and
                event["authority"] == DOCUMENTS and event["intentSha256"] == digest(intent.raw), "Exact original document event")
            same_process(event["providerProcess"], document_provider_observed, DOCUMENT_PROCESS, v["testUid"], v["bootUuid"])
            return event
        if v["kind"] == "EXPORT":
            created = document_event("create", {"callerUid", "callerPid", "displayName", "mimeType"})
            opened = document_event("open", {"callerUid", "callerPid", "mode"})
            require(created["callerUid"] == v["docsUid"] and type(created["callerPid"]) is int and created["callerPid"] > 0 and
                created["displayName"] == f"agent-hub-p7-export-{v['run']}.codexmigration" and created["mimeType"] == "application/octet-stream" and
                opened["callerUid"] == v["appUid"] and type(opened["callerPid"]) is int and opened["callerPid"] > 0 and opened["mode"] == "wt", "Exact system create/App write callers")
        if consumer_closed:
            opened = document_event("read-open", {"callerUid", "callerPid", "mode"})
            require(opened["callerUid"] == v["appUid"] and type(opened["callerPid"]) is int and opened["callerPid"] > 0 and opened["mode"] == "r", "Exact original consumer")
            close = document_event("read-close", {"success", "failureClass", "fdCloseOnly", "fullPayloadReadProven"})
            require(set(close) == EVENT_KEYS | {"success", "failureClass", "fdCloseOnly", "fullPayloadReadProven"} and close["success"] is True and
                close["failureClass"] == "None" and close["fdCloseOnly"] is True and close["fullPayloadReadProven"] is False, "Only consumer FD close is asserted")
    require(values[0]["files"] == values[1]["files"] and values[0]["documents"] == values[1]["documents"], "Two audit snapshots changed")
    return {"schema": "p7-main-saf-framework-auxiliary-join-v4", "run": v["run"], "operationId": v["operationId"],
        "stableAudit": True, "fullP7Accepted": False, "businessAcceptanceProven": False}


# Closed runnable EXPORT admission. IMPORT command builders above are library-only;
# this CLI has no IMPORT selector or input-source option.
TOOL = "scripts/mobile/android-main-saf-framework.py"
NATIVE_SELECTOR = "com.agenthub.android.NativeMigrationMainUiTest#actualMainSafCreateWritesOnlyOwnedEncryptedDocument"
_OWNERS = {}  # Acquisition owners are inserted before any Popen exists.


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


def sync_directory(path):
    fd = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def persist(path, raw):
    require(type(raw) is bytes and len(raw) <= STDERR_CAP, "Bounded evidence file")
    path = Path(path)
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        at = 0
        while at < len(raw):
            n = os.write(fd, raw[at:]); require(n > 0, "Evidence write stalled"); at += n
        os.fsync(fd)
    finally:
        os.close(fd)
    sync_directory(path.parent)


def persist_json(path, value):
    persist(path, canonical(value))


def finite_deadline(seconds):
    require(type(seconds) in (int, float) and not isinstance(seconds, bool) and math.isfinite(seconds) and 0 < seconds <= 300,
        "Original finite operation budget")
    return time.monotonic() + float(seconds)


class CaptureUnknown(RuntimeError):
    pass


def owner_snapshot(acquisition, suffix):
    output, prefix = acquisition["output"], acquisition["prefix"]
    owner, child = acquisition.get("owner"), acquisition.get("child")
    value = {"schema": "p7-framework-original-child-wait-v4", "operationId": acquisition["operationId"],
        "intentSha256": acquisition["intentSha256"], "status": "UNKNOWN", "actualWaitReturned": False,
        "remoteExitFrameObserved": False, "remoteEffectConfirmed": False,
        "hostPid": os.getpid(), "childPid": child.pid if child is not None else None}
    if owner is not None:
        for name in ("stdout", "stderr"):
            raw = bytes(getattr(owner, name))
            persist(output / (prefix + suffix + "." + name + ".bin"), raw)
            value[name] = {"rawFileSha256": digest(raw), "retainedBytes": len(raw),
                "observedBytes": getattr(owner, name + "_bytes"), "fullStreamSha256": getattr(owner, name + "_hash").hexdigest(),
                "EOF": getattr(owner, name + "_eof"), "truncated": len(raw) != getattr(owner, name + "_bytes")}
        value.update({"status": owner.state, "actualWaitReturned": owner.actual_wait_returned,
            "returnCode": owner.return_code, "stdinClosed": owner.stdin_closed, "overflow": owner.overflow,
            "childStartObservation": owner.start_observation,
            "startObservationKind": acquisition.get("startObservationKind"),
            "allHostPipeFdsClosed": all(p is None or p.closed for p in (child.stdin, child.stdout, child.stderr))})
    persist_json(output / (prefix + suffix + "-wait.json"), value)
    return value


def owned_call(output, prefix, argv, repo, *, cap=CAP, seconds=15, tick=None, deadline=None):
    """Internal fixed-call-site only; never accepts argv from CLI/payload/remote text."""
    require(type(prefix) is str and re.fullmatch(r"[a-z0-9-]{1,48}", prefix), "Closed capture label")
    require(cap in (CAP, STDERR_CAP), "Closed output cap")
    original_deadline = finite_deadline(seconds) if deadline is None else deadline
    require(type(original_deadline) is float and math.isfinite(original_deadline) and
        0 < original_deadline - time.monotonic() <= 300, "Freeze original capture deadline before spawn")
    intent = {"schema": "p7-framework-original-child-intent-v4", "operationId": str(uuid.uuid4()), "argv": list(argv),
        "cwd": str(repo), "stdoutCap": cap, "stderrCap": STDERR_CAP, "createdWallNs": time.time_ns(),
        "deadlineMonotonic": original_deadline, "remoteExitFrameObserved": False}
    # Exclusive invocation is the once ledger, even if acquisition/owner publication fails.
    persist_json(output / (prefix + "-intent.json"), intent)
    acquisition = {"output": output, "prefix": prefix, "operationId": intent["operationId"], "intentSha256": digest(canonical(intent)), "child": None, "owner": None}
    _OWNERS[intent["operationId"]] = acquisition
    try:
        acquisition["child"] = subprocess.Popen(list(argv), cwd=str(repo), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, umask=0o077)
        child = acquisition["child"]
        # The exact Popen remains strongly registered before any fallible observation/write.
        acquisition["owner"] = OriginalChild(child, tuple(argv), "start-observation-pending", b"", original_deadline, stdout_limit=cap)
        observed = _capture.owner_start(child)  # Existing bounded exact-own ps helper, no device side effect.
        acquisition["startObservationKind"] = "measured-live-ps" if observed is not None else "already-exited-before-ps"
        acquisition["owner"].start_observation = observed or "original-Popen-already-exited-before-ps"
        persist_json(output / (prefix + "-owner.json"), {"schema": "p7-framework-local-owner-v4", "operationId": intent["operationId"],
            "intentSha256": acquisition["intentSha256"], "pid": child.pid, "startObservation": observed,
            "startObservationKind": acquisition["startObservationKind"], "hostPid": os.getpid()})
        acquisition["owner"].drain_once(tick=tick)
        value = owner_snapshot(acquisition, "")
        require(value["status"] == "LOCAL_CLOSED" and value["actualWaitReturned"] and value["allHostPipeFdsClosed"] and
            value["stdout"]["EOF"] and value["stderr"]["EOF"] and not value["overflow"], "Original capture incomplete")
        _OWNERS.pop(intent["operationId"])
        return SimpleNamespace(returncode=value["returnCode"], stdout=bytes(acquisition["owner"].stdout),
            stderr=bytes(acquisition["owner"].stderr), owner=acquisition["owner"], wait=value,
            waitSha256=digest(canonical(value)))
    except BaseException as error:
        acquisition["failureClass"] = type(error).__name__
        if not (output / (prefix + "-wait.json")).exists():
            owner_snapshot(acquisition, "")
        if acquisition["child"] is None:
            _OWNERS.pop(intent["operationId"], None)
        raise CaptureUnknown("Original operation UNKNOWN; retain owner, no replay: " + prefix) from error


def retain_unknown_owners():
    """No new command/input is sent. Keep this CLI and exact existing clients alive until
    their actual wait/EOF/FD closure. Each observation is nonblocking; no kill or replay.
    A capture deadline stops dependent work, not the remote operation. The retention
    process may remain pending and must be listed as a retained task resource by Root.
    """
    while _OWNERS:
        for key, acquisition in list(_OWNERS.items()):
            owner, child = acquisition.get("owner"), acquisition.get("child")
            if child is None:
                _OWNERS.pop(key); continue
            if owner is None:
                owner = OriginalChild(child, tuple(child.args), "acquisition-failed-original-Popen", b"", finite_deadline(1), stdout_limit=STDERR_CAP)
                acquisition["owner"] = owner
            for name, pipe in (("stdout", child.stdout), ("stderr", child.stderr)):
                if getattr(owner, name + "_eof") or pipe is None or pipe.closed:
                    continue
                try:
                    os.set_blocking(pipe.fileno(), False)
                    raw = os.read(pipe.fileno(), 65536)
                    if not raw:
                        pipe.close(); setattr(owner, name + "_eof", True); continue
                    getattr(owner, name + "_hash").update(raw)
                    setattr(owner, name + "_bytes", getattr(owner, name + "_bytes") + len(raw))
                    target = getattr(owner, name); limit = owner.stdout_limit if name == "stdout" else STDERR_CAP
                    available = max(0, limit - len(target)); target.extend(raw[:available])
                    if len(raw) > available: owner.overflow = True
                except (BlockingIOError, InterruptedError):
                    pass
                except BaseException as failure:
                    acquisition["retentionFailureClass"] = type(failure).__name__
                    # Only this original client's own read end is released after a read
                    # failure. This is not EOF, remote cancellation or a successful stage.
                    try: pipe.close()
                    except BaseException: pass
            owner.stdin_closed = child.stdin is None or child.stdin.closed
            closed_or_eof = all(p is None or p.closed or getattr(owner, name + "_eof") for name, p in (("stdout", child.stdout), ("stderr", child.stderr)))
            if closed_or_eof and child.poll() is not None:
                owner.return_code = child.wait(timeout=0.01); owner.actual_wait_returned = True
                if owner.selector is not None: owner.selector.close()
                # Sticky UNKNOWN: late closure does not turn the already failed stage into pass.
                owner.state = "UNKNOWN"
                owner_snapshot(acquisition, "-retained-closure")
                _OWNERS.pop(key)
        if _OWNERS: time.sleep(0.1)

class Device:
    def __init__(self, args, root):
        self.args, self.root = args, root
        self.prefix = [str(Path(args.adb).resolve()), "-s", args.serial]
        self.counter = 0
        self.outer_deadline = None

    def call(self, argv, cap=CAP):
        require(type(argv) is list and all(type(x) is str for x in argv), "Internal fixed read argv")
        self.counter += 1; require(self.counter <= 160, "Finite device-read count")
        deadline = finite_deadline(15)
        if self.outer_deadline is not None: deadline = min(deadline, self.outer_deadline)
        return owned_call(Path(self.args.output_dir), "read-%03d" % self.counter, self.prefix + argv, Path(self.args.repo).resolve(), cap=cap, deadline=deadline)

    def shell(self, argv, cap=CAP):
        return self.call(["shell", "-T", shlex.join(argv)], cap)

    def success(self, argv, cap=CAP):
        result = self.shell(argv, cap)
        require(result.returncode == 0 and not result.stderr, "Exact read incomplete; not absence or effect evidence")
        return result.stdout

    def boot(self):
        value = self.success(["cat", "/proc/sys/kernel/random/boot_id"]).decode().strip(); canonical_uuid(value); return value

    def uid(self, package):
        require(package in (APP, TEST, self.root["docsPackage"]), "Finite UID target")
        raw = self.success(["pm", "list", "packages", "-U", package]).decode()
        rows = {}
        for line in raw.splitlines():
            match = re.fullmatch(r"package:([A-Za-z0-9_.]+) uid:([0-9]{1,10})", line)
            require(match and package in match[1] and match[1] not in rows, "Complete unique pm rows")
            rows[match[1]] = int(match[2])
        require(package in rows, "Exact package UID absent"); return rows[package]

    def installed(self):
        result = {}
        for package in (APP, TEST):
            rows = self.success(["pm", "path", package]).decode().splitlines()
            require(len(rows) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~-]+/base.apk", rows[0]), "One installed base APK only")
            path = rows[0][len("package:"):]
            hashed = self.success(["sha256sum", path]).decode().strip().split()
            require(len(hashed) == 2 and hashed[1] == path, "Installed APK path changed"); sha(hashed[0]); result[package] = hashed[0]
        return result

    def snapshot(self, build):
        r = self.root
        require(self.boot() == r["bootUuid"] and self.success(["getprop", "ro.build.version.sdk"]).strip() == b"33", "Same API33 boot")
        require(self.installed() == {APP: build["artifacts"]["app"]["sha256"], TEST: build["artifacts"]["test"]["sha256"]}, "Installed APK differs from captured build")
        require({p: self.uid(p) for p in (APP, TEST, r["docsPackage"])} == {APP: r["uid"], TEST: r["testUid"], r["docsPackage"]: r["docsUid"]}, "Installed UID changed")
        path = self.success(["pm", "path", r["docsPackage"]]).decode().strip()
        require(re.fullmatch(r"package:/(?:system|system_ext|product|data/app)/[A-Za-z0-9_./+=~-]+\.apk", path), "Exact single system DocsUI APK")
        path = path[len("package:"):]
        observed = self.success(["sha256sum", path]).decode().strip()
        require(re.fullmatch(r["profile"]["systemApkSha256"] + r"\s+" + re.escape(path), observed), "System DocsUI APK drift")
        resolved = self.success(["cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.CREATE_DOCUMENT", "-c", "android.intent.category.OPENABLE", "-t", "application/octet-stream"]).decode().splitlines()
        require(resolved and re.fullmatch(re.escape(r["docsPackage"]) + r"/[A-Za-z0-9_.$]+", resolved[-1]), "Exact system create owner changed")
        version = self.call(["version"])
        require(version.returncode == 0 and not version.stderr and digest(version.stdout) == r["adbVersionSha256"] and
            version.stdout.startswith(b"Android Debug Bridge version"), "Actual captured ADB version drift")
        features = self.call(["features"])
        rows = features.stdout.decode().strip().splitlines()
        require(features.returncode == 0 and not features.stderr and 1 <= len(rows) <= 128 and len(set(rows)) == len(rows) and
            all(re.fullmatch(r"[a-z0-9_]+", x) for x in rows) and "shell_v2" in rows, "Pinned no-PTY shell_v2 transport required")
        return {"transport": "adb-shell-v2-no-pty", "remoteExitFrameObserved": False,
            "featuresSha256": digest(features.stdout), "adbVersionSha256": digest(version.stdout), "effectEvidence": "original immutable provider receipt, never local rc0"}

    def platform_container(self):
        """Bounded metadata only for the exact platform dataDir/files pair. Never chmods,
        chooses another directory or lists a namespace. The protected data ancestor is700;
        only its canonical files child may use Android's observed771 mode.
        """
        expected_data = "/data/user/0/" + TEST
        expected_files = expected_data + "/files"
        boot = self.boot()
        require(boot == self.root["bootUuid"], "Platform preflight original boot")
        def snapshot():
            cwd_raw = self.success(["run-as", TEST, "pwd", "-P"])
            data_raw = self.success(["run-as", TEST, "readlink", "-f", "."])
            files_raw = self.success(["run-as", TEST, "readlink", "-f", "files"])
            require(cwd_raw == data_raw == (expected_data + "\n").encode() and files_raw == (expected_files + "\n").encode(),
                "Exact canonical platform package dataDir/files required; no alias/retarget")
            raw_data = self.success(["run-as", TEST, "stat", "-c", "%u:%g:%a:%F:%d:%i", "."])
            raw_files = self.success(["run-as", TEST, "stat", "-c", "%u:%g:%a:%F:%d:%i", "files"])
            def metadata(raw, child):
                match = re.fullmatch(rb"([1-9][0-9]{0,9}):([1-9][0-9]{0,9}):(700|771):directory:([0-9]{1,20}):([1-9][0-9]{0,19})\n", raw)
                require(match is not None, "Exact bounded platform stat metadata")
                uid, gid, mode, dev, ino = (int(x) for x in match.groups())
                require(uid == gid == self.root["testUid"] and (mode in (700, 771) if child else mode == 700) and
                    0 <= dev < 2**64 and 0 < ino < 2**64, "Platform owner/group/mode/device/inode constraint")
                return {"uid": uid, "gid": gid, "modeOctal": match[3].decode("ascii"), "device": dev, "inode": ino, "type": "directory"}
            parent = metadata(raw_data, False); child = metadata(raw_files, True)
            require(parent["device"] == child["device"], "Platform files must stay on protected dataDir device")
            return {"dataDir": expected_data, "files": expected_files, "dataStat": parent, "filesStat": child,
                "rawSha256": {"cwd": digest(cwd_raw), "dataCanonical": digest(data_raw), "filesCanonical": digest(files_raw),
                    "dataStat": digest(raw_data), "filesStat": digest(raw_files)}}
        before = snapshot(); after = snapshot()
        require(before == after and self.boot() == boot, "Platform container metadata/canonical path changed")
        return {"schema": "p7-framework-platform-container-observation-v1", "bootUuid": boot, "stablePrePost": True,
            "platformDirectoriesModified": False, "namespaceEnumerated": False, "observation": before}

    def process(self, role, *, absent=False, witness=True, phase):
        require(role in ("controller", "documents"), "Closed framework process role")
        r = self.root; name = CONTROLLER_PROCESS if role == "controller" else DOCUMENT_PROCESS
        require(self.boot() == r["bootUuid"], "Original boot changed")
        one = self.shell(["pidof", name])
        if one.returncode == 1 and not one.stdout and not one.stderr:
            two = self.shell(["pidof", name])
            require(two.returncode == 1 and not two.stdout and not two.stderr and self.boot() == r["bootUuid"], "Two same-boot absences required")
            result = {"status": "absent", "twoExactPidofAbsences": True, "process": None, "role": role}
        else:
            require(not absent and one.returncode == 0 and not one.stderr and re.fullmatch(rb"[1-9][0-9]{0,9}\n?", one.stdout), "Original process absent/multiple/unknown")
            pid = int(one.stdout)
            before = self.success(["run-as", TEST, "cat", f"/proc/{pid}/stat"])
            status = self.success(["run-as", TEST, "cat", f"/proc/{pid}/status"])
            argv = self.success(["run-as", TEST, "cat", f"/proc/{pid}/cmdline"])
            after = self.success(["run-as", TEST, "cat", f"/proc/{pid}/stat"])
            ticks = lambda raw: raw.decode().rsplit(") ", 1)[1].split()[19]
            uids = re.findall(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$", status, re.M)
            require(before.startswith(f"{pid} (".encode()) and after.startswith(f"{pid} (".encode()) and ticks(before) == ticks(after) and
                re.fullmatch(r"[0-9]{1,24}", ticks(before)), "Original process raced")
            require(len(uids) == 1 and len(set(uids[0])) == 1 and int(uids[0][0]) == r["testUid"] and _pair.exact_package_cmdline(argv, name), "Actual Test UID/argv differs")
            proc = {"pid": pid, "uid": r["testUid"], "startTicks": ticks(before), "bootUuid": r["bootUuid"], "argvSha256": digest(argv), "argvExactName": name}
            if witness:
                filename = "controller-owner.json" if role == "controller" else "document-owner.json"
                raw = self.success(["run-as", TEST, "cat", f"{NAMESPACE}/{r['run']}/{filename}"])
                saved = exact_json(raw)
                require(set(saved) == set(proc) | {"processNonce"} and all(saved[k] == v and type(saved[k]) is type(v) for k, v in proc.items()), "Original immutable owner witness differs")
                canonical_uuid(saved["processNonce"]); proc["processNonce"] = saved["processNonce"]
            require(self.boot() == r["bootUuid"], "Original boot raced")
            result = {"status": "present", "role": role, "process": proc, "independentProcFields": ["pid", "uid", "startTicks", "bootUuid", "argvSha256", "argvExactName"],
                "nonceProvenance": "source-generated nonce consistency joined through separately read immutable provider witness; not independently measured" if witness else "not read during initial process-only observation",
                "rawSha256": {"statBefore": digest(before), "statAfter": digest(after), "status": digest(status), "argv": digest(argv)}}
        persist_json(Path(self.args.output_dir) / (phase + "-" + role + ".json"), result)
        require(not absent or result["status"] == "absent", "Preexisting framework process retained; never adopt or stop it")
        return result

    def app_live(self):
        r = self.root; boot = self.boot()
        one = self.shell(["pidof", APP])
        if one.returncode == 1 and not one.stdout and not one.stderr: return None
        require(one.returncode == 0 and not one.stderr and re.fullmatch(rb"[1-9][0-9]{0,9}\n?", one.stdout), "One original App PID")
        pid = int(one.stdout)
        before = self.success(["run-as", APP, "cat", f"/proc/{pid}/stat"])
        status = self.success(["run-as", APP, "cat", f"/proc/{pid}/status"])
        argv = self.success(["run-as", APP, "cat", f"/proc/{pid}/cmdline"])
        after = self.success(["run-as", APP, "cat", f"/proc/{pid}/stat"])
        ticks = lambda raw: raw.decode().rsplit(") ", 1)[1].split()[19]
        uids = re.findall(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$", status, re.M)
        require(before.startswith(f"{pid} (".encode()) and after.startswith(f"{pid} (".encode()) and ticks(before) == ticks(after) and re.fullmatch(r"[0-9]{1,24}", ticks(before)), "Original App process raced")
        require(len(uids) == 1 and len(set(uids[0])) == 1 and int(uids[0][0]) == r["uid"] and _pair.exact_package_cmdline(argv, APP) and self.boot() == boot == r["bootUuid"], "Original App identity mismatch")
        return {"pid": pid, "uid": r["uid"], "startTicks": ticks(before), "bootUuid": boot, "argvSha256": digest(argv),
            "statBeforeSha256": digest(before), "statAfterSha256": digest(after), "statusSha256": digest(status)}

    def app_exit(self, original):
        r = self.root; require(self.boot() == original["bootUuid"] == r["bootUuid"], "Original boot changed")
        pid = original["pid"]
        a = self.shell(["run-as", APP, "cat", f"/proc/{pid}/stat"])
        b = self.shell(["run-as", APP, "ls", "-d", f"/proc/{pid}"])
        if a.returncode == 0:
            require(not a.stderr and a.stdout.startswith(f"{pid} (".encode()), "Exact original stat read")
            ticks = a.stdout.decode().rsplit(") ", 1)[1].split()[19]
            require(re.fullmatch(r"[0-9]{1,24}", ticks) and ticks != original["startTicks"] and b.returncode == 0 and not b.stderr and b.stdout.strip() == f"/proc/{pid}".encode(), "Original process still alive/unknown; retain")
            kind = "pid-start-replaced"
        else:
            for result, path in ((a, f"/proc/{pid}/stat"), (b, f"/proc/{pid}")):
                raw = result.stdout + result.stderr
                require(result.returncode != 0 and path.encode() in raw and b"No such file or directory" in raw and b"Permission denied" not in raw and len(raw) <= 1024, "Independent ENOENT required; permission failure unknown")
            kind = "proc-enoent-and-directory-absent"
        require(self.boot() == r["bootUuid"], "Boot changed during original exit proof")
        return {"process": original, "kind": kind, "statReadSha256": digest(a.stdout + a.stderr), "directoryReadSha256": digest(b.stdout + b.stderr)}

ROOT_KEYS = {"schema", "kind", "operationId", "nativeOperationId", "run", "bootUuid", "uid", "testUid", "docsUid", "docsPackage", "serialSha256", "adbSha256", "adbVersionSha256",
    "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profile", "profileSha256", "expiresWallMillis", "transportContractReviewSha256",
    "runtimeAdmissionSha256", "captureSelfSha256", "hostToolSha256", "sourceApiSha256", "readHelperSha256", "head", "appApkSha256", "testApkSha256", "certificateSha256"}
RUNTIME_KEYS = {"schema", "purpose", "ownedRuntimeId", "run", "bootUuid", "serialSha256", "api", "ready", "head", "uid", "testUid", "docsUid", "appApkSha256", "testApkSha256", "certificateSha256",
    "sourceGuardSha256", "sdkSealSha256", "adbSha256", "adbVersionSha256", "expiresWallMillis"}
PROTECTED_IDS = frozenset(("fab4fe68-408f-4308-8157-a95fedc1a97f", "863ec69e-e647-4f5e-bdd0-5b294b9823ac", "ece33c64-7446-4fb6-8d69-deb971dd91da", "01a17d28-2dfd-74c2-99fd-70e58e239086"))

# Preserve the current source84 unknown preparation and never-dispatched Native intent.
# New storage/purpose is not permission to reuse or query an old operation/run.
PROTECTED_IDS = PROTECTED_IDS | frozenset(("8e4d4440-8931-4c96-95a6-5e6ae7818980",
    "8f9b8227-b102-470b-aba6-fddc411a7497", "9201e7bd-e9db-42c9-9c83-f9b9bdcd6eff"))


# Preserve Source85 prepared-but-failed picker and its original immutable intent.
# A filename phase fix only admits a fresh run; never resume or replay these IDs.
PROTECTED_IDS = PROTECTED_IDS | frozenset(("835a58e0-d421-4f51-bcf2-82467fa68ba8",
    "d6e02b7b-6313-4a83-8ad3-64315bd2a76c", "13543e28-9178-4439-83d7-7352ee05a4f0"))


# Preserve Source86's original written payload, ACK and failed Main/owner result.
# Fresh explicit-FD admission must never resume or replay these operations.
PROTECTED_IDS = PROTECTED_IDS | frozenset(("0b2dd747-2a70-45fe-b348-772f6d634a12",
    "880e07d9-42cf-48da-ae90-399d92cf98ce", "e6cdc5a7-b83e-4846-8299-e68f1ff399cf"))


def private_bytes(path, cap):
    path = Path(path); before = path.lstat()
    require(path.is_absolute() and path.resolve() == path.absolute() and stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and
        before.st_nlink == 1 and stat.S_IMODE(before.st_mode) == 0o600 and 0 < before.st_size <= cap, "Private canonical bounded input")
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        opened = os.fstat(fd)
        keys = lambda s: (s.st_dev, s.st_ino, s.st_uid, s.st_mode, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        require(keys(opened) == keys(before), "Input open changed")
        data = bytearray()
        while True:
            raw = os.read(fd, min(65536, cap + 1 - len(data)))
            if not raw: break
            data.extend(raw); require(len(data) <= cap, "Input bound")
        require(len(data) == before.st_size and keys(os.fstat(fd)) == keys(before) == keys(path.lstat()), "Input changed")
        return bytes(data)
    finally:
        os.close(fd)


def lease_now(root):
    require(type(root["expiresWallMillis"]) is int and 0 < root["expiresWallMillis"] - int(time.time() * 1000) <= 600000,
        "Original finite Root lease expired; retain/no replay")


def root_approval(args):
    raw = private_bytes(Path(args.root_approval), CAP); sha(args.root_approval_sha256)
    require(digest(raw) == args.root_approval_sha256, "External immutable Root approval differs")
    root = exact_json(raw); require(type(root) is dict and set(root) == ROOT_KEYS, "Exact framework EXPORT Root schema")
    require(root["schema"] == "p7-framework-export-explicit-fd-root-approval-v1" and root["kind"] == "EXPORT", "EXPORT only; IMPORT lacks producer admission")
    for key in ("operationId", "nativeOperationId", "run", "bootUuid"):
        canonical_uuid(root[key]); require(root[key] not in PROTECTED_IDS, "Protected original scope/operation forbidden")
    require(root["operationId"] != root["nativeOperationId"] != root["run"] and root["operationId"] != root["run"], "Distinct original operations/run")
    for key in ROOT_KEYS:
        if key.endswith("Sha256"): sha(root[key])
    require(type(root["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", root["head"]), "Pinned Root HEAD")
    for key in ("uid", "testUid", "docsUid"):
        require(type(root[key]) is int and 0 < root[key] < 100000, "Exact user0 UID")
    require(root["uid"] >= 10000 and root["testUid"] >= 10000 and len({root[k] for k in ("uid", "testUid", "docsUid")}) == 3, "Distinct App/Test/system UIDs")
    require(root["docsPackage"] == "com.google.android.documentsui" and type(args.serial) is str and re.fullmatch(r"emulator-[0-9]{4,5}", args.serial), "Closed independently owned API33 emulator target")
    require(digest(args.serial.encode()) == root["serialSha256"] and Path(args.adb).is_absolute() and digest(Path(args.adb).read_bytes()) == root["adbSha256"], "Original serial/ADB fingerprint")
    repo = Path(args.repo).resolve(); require(Path(__file__).resolve() == repo / TOOL and digest(Path(__file__).read_bytes()) == root["hostToolSha256"], "Exact reviewed in-repo Host entry")
    for filename, key in (("android-sdk-capture.py", "captureSelfSha256"), ("android-nonempty-device-first.py", "sourceApiSha256"), ("android-nonempty-device-pair.py", "readHelperSha256")):
        require(digest(Path(__file__).with_name(filename).read_bytes()) == root[key], "Exact source helper pin before import")
    lease_now(root)
    runtime_raw = private_bytes(Path(args.runtime_admission), CAP)
    require(digest(runtime_raw) == root["runtimeAdmissionSha256"], "Root-owned actual runtime admission pin")
    runtime = exact_json(runtime_raw)
    require(set(runtime) == RUNTIME_KEYS and runtime["schema"] == "p7-framework-api33-runtime-admission-v1" and
        runtime["purpose"] == "p7-main-saf-framework-export-explicit-fd-v1" and runtime["api"] == 33 and type(runtime["api"]) is int and runtime["ready"] is True,
        "Exact owned API33 runtime purpose/readiness")
    canonical_uuid(runtime["ownedRuntimeId"])
    for key in RUNTIME_KEYS - {"schema", "purpose", "ownedRuntimeId", "api", "ready"}:
        require(runtime[key] == root[key] and type(runtime[key]) is type(root[key]), "Runtime/root target or fingerprint drift")
    return raw, root, runtime_raw, runtime


def profile_check(profile, root):
    _first.exact(profile, {"schema", "package", "systemUid", "systemApkSha256", "api", "controlObservationSha256", "navigation", "directoryTitleId", "fileNameId", "saveId", "openButton", "owned"})
    require(profile["schema"] == "p7-main-saf-docsui-framework-profile-v4" and profile["package"] == root["docsPackage"] and profile["api"] == 33 and
        type(profile["api"]) is int and profile["systemUid"] == root["docsUid"] and type(profile["systemUid"]) is int, "Exact independently observed DocsUI purpose")
    sha(profile["systemApkSha256"]); sha(profile["controlObservationSha256"])
    require(profile["directoryTitleId"] == root["docsPackage"] + ":id/breadcrumb_text" and profile["fileNameId"] == "android:id/title" and profile["saveId"] == "android:id/button1", "Only actual observed fixed system controls")
    nav = profile["navigation"]; _first.exact(nav, {"state", "role", "buttonBounds", "toolbarBounds"})
    require(nav["state"] == "OBSERVED" and nav["role"] == "toolbar-show-roots-en-v1", "Only observed fixed toolbar navigation role")
    for key in ("buttonBounds", "toolbarBounds"):
        a = nav[key]; require(type(a) is list and len(a) == 4 and all(type(x) is int and 0 <= x <= 4096 for x in a) and a[0] < a[2] and a[1] < a[3], "Observed finite control geometry")
    b, t = nav["buttonBounds"], nav["toolbarBounds"]
    require(b[0] == t[0] and b[1] == t[1] and b[3] == t[3] and b[2] < t[2], "Observed left toolbar child")
    require(profile["openButton"] == {"state": "UNKNOWN", "resourceId": None} and profile["owned"] == {
        "rootTitle": {"state": "RUNTIME_REQUIRED", "resourceId": None}, "documentTitle": {"state": "RUNTIME_REQUIRED", "resourceId": None}}, "Unobserved original UUID selectors remain runtime-bound")
    require(digest(canonical(profile)) == root["profileSha256"], "Actual observed profile was not independently pinned")


def admitted(args, root):
    repo = Path(args.repo).resolve(); bundle = Path(args.build_bundle)
    seal_raw = _capture.private_bytes(bundle / "capture-seal.json", 512 * 1024)
    review_raw = _capture.private_bytes(bundle / "workspace-review.json", 131072)
    require(digest(seal_raw) == root["sdkSealSha256"] and digest(review_raw) == root["workspaceReviewSha256"], "Externally approved exact SDK/review bytes")
    review = _capture.parse_json(review_raw); require(review["head"] == root["head"], "SDK reviewed HEAD differs")
    require(digest(_capture.private_bytes(bundle / "adb-version.stdout.bin", CAP)) == root["adbVersionSha256"], "Root ADB version must match the actual sealed SDK observation")
    build = _capture.verify_build_bundle(bundle, _first, repo, SDK_PROFILE)
    guard = _first.read_guard(bundle / "source-before.json")
    require(build["sealSha256"] == root["sdkSealSha256"] and digest(canonical(guard)) == root["sourceGuardSha256"] and
        _first.same(guard, _capture.p7_explicit_fd_source_guard(_first, repo, guard["sourceOverlay"])), "Exact62/905 source chain")
    require(len(guard["inventory"]) == len(guard["sources"]) == 905 and len(guard["sourceOverlay"]) == 62 and
        guard["sources"][TOOL] == root["hostToolSha256"] and guard["sources"]["scripts/mobile/android-sdk-capture.py"] == root["captureSelfSha256"], "No old SDK or broad source overlay")
    require(Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve() and build["tools"]["adb"]["sha256"] == root["adbSha256"], "Captured actual ADB")
    for role, key in (("app", "appApkSha256"), ("test", "testApkSha256")):
        require(build["artifacts"][role]["sha256"] == root[key] and build["artifacts"][role]["certificateSha256"] == root["certificateSha256"], "Exact captured APK/cert")
    require(set(build["artifacts"]) == {"app", "test"}, "Two-APK purpose; P9 examiner not admitted")
    profile_check(root["profile"], root)
    head = owned_call(Path(args.output_dir), "git-head", ["git", "rev-parse", "HEAD"], repo)
    unmerged = owned_call(Path(args.output_dir), "git-unmerged", ["git", "diff", "--name-only", "--diff-filter=U"], repo)
    require(head.returncode == unmerged.returncode == 0 and not head.stderr and not unmerged.stderr and head.stdout.decode().strip() == root["head"] and not unmerged.stdout,
        "Current exact HEAD/no-conflict gate")
    return guard, build


def native_export(device, args, root, build, intent, prepared_raw, controller):
    output = Path(args.output_dir)
    binding = {"schema": "p7-main-saf-admission-v4", "profile": PROFILE, "run": root["run"], "phase": "EXPORT",
        "operationId": root["nativeOperationId"], "selector": NATIVE_SELECTOR,
        **{key: root[key] for key in ("uid", "testUid", "docsUid", "bootUuid", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "expiresWallMillis", "transportContractReviewSha256", "appApkSha256", "testApkSha256", "certificateSha256")},
        "inputBytes": None, "inputSha256": None, "controllerOperationId": root["operationId"], "controllerIntentSha256": digest(intent.raw),
        "controllerReceiptSha256": digest(prepared_raw), "controllerPayloadAckSha256": ZERO, "sourceProducerProofSha256": ZERO}
    arguments = {"class": NATIVE_SELECTOR, "migrationInboxRun": root["run"], "migrationMainSafAdmissionJson": canonical(binding).decode(),
        "migrationMainSafAdmissionSha256": digest(canonical(binding)), "migrationMainSafOwnedLease": "1", "migrationMainSafRun": root["run"],
        "migrationMainSafProviderPrepared": "1", "migrationMainSafFrameworkV4": "1", "migrationMainSafProfile": canonical(root["profile"]).decode()}
    persist_json(output / "native-admission.json", binding); persist_json(output / "native-arguments.json", arguments)
    absent = device.shell(["pidof", APP]); require(absent.returncode == 1 and not absent.stdout and not absent.stderr, "Original App must be absent; no force-stop")
    device.process("documents", absent=True, phase="before-native")
    # Recheck the exact original controller after confirmed preparation, before Native.
    before_controller = device.process("controller", phase="before-native")
    require(before_controller["status"] == "present" and before_controller["process"] == controller, "Original controller replaced before Native; no replay")
    observed, docs_live = {}, {}
    last_tick = [0.0]
    native_deadline = finite_deadline(180)
    def tick():
        now = time.monotonic()
        if now - last_tick[0] < 0.2: return
        last_tick[0] = now; device.outer_deadline = native_deadline
        try:
            if not observed:
                live = device.app_live()
                if live is not None:
                    observed.update(live); persist_json(output / "native-original-process.json", live)
            if not docs_live:
                probe = device.shell(["pidof", DOCUMENT_PROCESS])
                if probe.returncode == 1 and not probe.stdout and not probe.stderr: return
                require(probe.returncode == 0 and not probe.stderr, "Original document provider live probe failed")
                doc = device.process("documents", witness=False, phase="native-live")
                require(doc["status"] == "present", "Exact new document provider unknown")
                docs_live.update(doc["process"])
        finally:
            device.outer_deadline = None
    command = ["am", "instrument", "-w", "-r"]
    for key, value in sorted(arguments.items()): command += ["-e", key, value]
    command.append(_first.RUNNER)
    lease_now(root)
    result = owned_call(output, "native-export", device.prefix + ["shell", "-T", shlex.join(command)], Path(args.repo).resolve(), cap=STDERR_CAP, deadline=native_deadline, tick=tick)
    require(result.returncode == 0 and not result.stderr and observed, "Actual original Native wait/dualEOF/stderr/identity gate")
    text = result.stdout.decode("utf-8", errors="strict")
    require("OK (1 test)" in text and not any(x in text for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")), "One successful original JUnit selector")
    expected_class, expected_method = NATIVE_SELECTOR.split("#")
    codes, admissions, cleanup, fields = [], [], [], {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            require(sep and key not in fields, "Unique original instrumentation field"); fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):]
            special = set(fields) & {"p7MainSafAdmission", "p7MainUiCleanup", "p7MainSafProducer"}
            if special:
                require(len(special) == 1 and set(fields) == special and code == "0" and "p7MainSafProducer" not in special, "Closed EXPORT status only")
                key = next(iter(special)); value = exact_json(fields[key].encode())
                (admissions if key == "p7MainSafAdmission" else cleanup).append(value)
            else:
                require(set(fields) <= {"id", "stream", "numtests", "class", "test", "current", "stack"} and fields.get("class") == expected_class and
                    fields.get("test") == expected_method and fields.get("numtests") == "1" and fields.get("current") == "1" and "stack" not in fields, "Exact original named case")
                codes.append(code)
            fields = {}
    require(not fields and codes == ["1", "0"] and text.count("INSTRUMENTATION_CODE: -1") == 1 and len(admissions) == len(cleanup) == 1, "One actual Native admission/completion")
    accepted = admissions[0]
    require(accepted["binding"] == binding and accepted["admissionSha256"] == digest(canonical(binding)), "Original Native admission differs")
    facts = accepted["nativeFacts"]
    for key in ("pid", "uid", "startTicks", "bootUuid", "argvSha256"):
        require(facts[key] == observed[key] and type(facts[key]) is type(observed[key]), "Native identity differs from independent original process")
    proof = cleanup[0]
    require(proof["case"] == expected_method and all(proof.get(k) is True for k in ("completed", "storageWriterRetired", "privateSessionRetired", "activityClosed", "installationReleased", "postJoinProviderCheckPassed", "safCallbackCompleted")) and
        proof["testFailed"] is False and proof["cleanupFailed"] is False and proof["safAccepted"] is False, "Actual Main close/result/owner gates incomplete")
    saf = proof["saf"]
    require(saf["admission"] == accepted and saf["run"] == root["run"] and saf["kind"] == "EXPORT" and saf["callbackAndGrantObserved"] is True and
        saf["providerAuditVerified"] is False and saf["protocolVersion"] == PROFILE and saf["controllerAuthority"] == CONTROL and saf["authority"] == DOCUMENTS and
        saf["providerRelativeDirectory"] == NAMESPACE + "/" + root["run"] and saf["documentId"] == "p7-output-" + root["run"], "Original Main callback/grant/URI/protocol mismatch")
    require(type(saf["expectedBytes"]) is int and 0 < saf["expectedBytes"] <= PAYLOAD_CAP, "Bounded original Native output"); sha(saf["expectedSha256"])
    original_exit = device.app_exit(facts)
    persist_json(output / "native-original-exit.json", original_exit)
    persisted = exact_json(device.success(["run-as", APP, "cat", f"files/acceptance/P7-inbox-{root['run']}/main-ui-result.json"]))
    require(persisted == proof, "Independently read exact original Main result differs")
    # The display field is not an execution target; the bound saf object supplies the actual v4 target.
    persist_json(output / "native-proof.json", proof)
    documents = device.process("documents", phase="after-native")
    require(documents["status"] == "present", "Original document process missing/unknown")
    if docs_live:
        require(all(documents["process"][k] == v and type(documents["process"][k]) is type(v) for k, v in docs_live.items()), "Document provider replaced during original Native")
    return proof, original_exit, documents["process"]


def native_role_join(root, proof):
    saf = proof["saf"]; roles = saf["runtimeOwnedRoles"]
    require(set(roles) == {"rootTitle", "documentTitle", "directoryWindowId", "directoryTextSha256", "profileSha256"}, "Closed original runtime roles")
    require(type(roles["directoryWindowId"]) is int and roles["directoryWindowId"] >= 0, "Observed original directory AX window")
    title = "AH_P7_" + root["run"].replace("-", "")
    filename = "agent-hub-p7-export-" + root["run"] + ".codexmigration"
    require(saf["filename"] == filename and roles["profileSha256"] == root["profileSha256"] and roles["directoryTextSha256"] == digest(title.encode()), "Actual original UUID breadcrumb/profile")
    steps = ["CHOOSE_ROOT", "SET_FILENAME", "SAVE"]
    require(saf["pickerActionSteps"] in (steps, ["SHOW_ROOTS"] + steps), "One original ordered system action per role")
    for key in ("rootTitle", "documentTitle"):
        row = roles[key]
        require(set(row) == {"state", "resourceId", "resourceIdObserved", "className", "windowId", "observedTextSha256", "finalFreshPathChecked"}, "Closed observed role shape")
        if key == "documentTitle":
            require(row == {"state": "NOT_APPLICABLE", "resourceId": None, "resourceIdObserved": False, "className": None, "windowId": None, "observedTextSha256": None, "finalFreshPathChecked": False}, "Export never claims an existing document row")
        else:
            require(row["state"] == "BOUND" and row["resourceIdObserved"] is True and row["className"] == "android.widget.TextView" and
                row["windowId"] == roles["directoryWindowId"] and row["observedTextSha256"] == digest(title.encode()) and row["finalFreshPathChecked"] is True and
                (row["resourceId"] is None or type(row["resourceId"]) is str and re.fullmatch(r"(?:" + re.escape(root["docsPackage"]) + r"|android):id/[A-Za-z0-9_]+", row["resourceId"])), "Runtime exact UUID role/window/path unknown")
    canonical_uuid(saf["pickerId"])


def content_operation(device, intent, action, prefix):
    require(action in ("prepare-export", "read-original-receipt", "read-payload-ack", "audit"), "Closed EXPORT protocol surface")
    lease_now(device.root)
    result = owned_call(Path(device.args.output_dir), prefix, intent.shell_argv(device.args.adb, device.args.serial, action), Path(device.args.repo).resolve(), seconds=30)
    require(result.returncode == 0 and not result.stderr, "Original content client failed; unknown effect/receipt, never replay")
    return result


def run_export(args):
    os.umask(0o077)
    raw, root, runtime_raw, runtime = root_approval(args)
    global _first, _capture, _pair
    _first = module("p7_framework_source", "android-nonempty-device-first.py")
    _capture = module("p7_framework_capture", "android-sdk-capture.py")
    _pair = module("p7_framework_proc_reads", "android-nonempty-device-pair.py")
    output = _capture.private_directory(args.output_dir, empty=True)
    require(output.resolve() == Path(args.output_dir).absolute(), "Exact private output directory")
    persist(output / "root-approval.json", raw); persist(output / "runtime-admission.json", runtime_raw)
    guard, build = admitted(args, root)
    persist_json(output / "source-guard.json", guard)
    device = Device(args, root); transport = device.snapshot(build)
    transport["sdkProfile"] = SDK_PROFILE
    transport["platformContainer"] = device.platform_container()
    persist_json(output / "transport.json", transport)
    device.process("controller", absent=True, phase="before-prepare")
    device.process("documents", absent=True, phase="before-prepare")
    absent = device.shell(["run-as", TEST, "ls", "-d", f"{NAMESPACE}/{root['run']}"])
    absent_raw = absent.stdout + absent.stderr
    require(absent.returncode != 0 and len(absent_raw) <= 1024 and b"No such file or directory" in absent_raw and b"Permission denied" not in absent_raw and
        f"{NAMESPACE}/{root['run']}".encode() in absent_raw, "Exact new run must be absent; retain all existing scopes")
    current_app = device.shell(["pidof", APP]); require(current_app.returncode == 1 and not current_app.stdout and not current_app.stderr, "Fresh absent original App; no stop/clear")
    intent_value = {"schema": "p7-main-saf-controller-intent-v4", "profile": PROFILE, "run": root["run"], "operationId": root["operationId"], "kind": "EXPORT",
        "appUid": root["uid"], "testUid": root["testUid"], "docsUid": root["docsUid"], "docsPackage": root["docsPackage"], "bootUuid": root["bootUuid"],
        **{k: root[k] for k in ("appApkSha256", "testApkSha256", "certificateSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "transportContractReviewSha256", "serialSha256", "adbSha256", "expiresWallMillis")},
        "rootApprovalSha256": digest(raw), "expectedPayloadBytes": 0, "expectedPayloadSha256": ZERO, "sourceProducerProofSha256": ZERO}
    intent = Intent.admit_metadata(intent_value)
    persist(output / "controller-intent.json", intent.raw)
    persist_json(output / "prepare-once.claim", {"run": root["run"], "operationId": root["operationId"], "intentSha256": digest(intent.raw)})
    # Exactly one preparation. A local rc0 never advances this gate by itself.
    content_operation(device, intent, "prepare-export", "controller-prepare")
    prepared = content_operation(device, intent, "read-original-receipt", "controller-prepared-read")
    controller_observation = device.process("controller", phase="after-prepare")
    require(controller_observation["status"] == "present", "Original controller unknown; no Native input")
    controller = controller_observation["process"]
    receipt(intent, prepared.stdout, controller)
    persist_json(output / "prepare-effect-confirmed.json", {"schema": "p7-framework-prepared-effect-v4", "run": root["run"], "operationId": root["operationId"],
        "intentSha256": digest(intent.raw), "receiptSha256": digest(prepared.stdout), "controllerProcess": controller, "remoteEffectConfirmed": True,
        "remoteExitFrameObserved": False, "nonceIndependentRuntimeMeasurement": False})
    # Recheck actual target/source/installed bytes after remote effect and before original Native.
    require(_first.same(guard, _capture.p7_explicit_fd_source_guard(_first, Path(args.repo).resolve(), guard["sourceOverlay"])), "Source changed after preparation; no Native input")
    device.snapshot(build); lease_now(root)
    persist_json(output / "native-once.claim", {"run": root["run"], "operationId": root["nativeOperationId"], "selector": NATIVE_SELECTOR})
    proof, original_exit, document_owner = native_export(device, args, root, build, intent, prepared.stdout, controller)
    native_role_join(root, proof)
    saf = proof["saf"]; expected = (saf["expectedBytes"], saf["expectedSha256"])
    def read_ack(command, deadline):
        require(tuple(command) == tuple(intent.shell_argv(args.adb, args.serial, "read-payload-ack")), "Only exact original read callback")
        read_ack.counter += 1
        value = owned_call(output, "payload-ack-%02d" % read_ack.counter, list(command), Path(args.repo).resolve(), deadline=deadline)
        return value.owner
    read_ack.counter = 0
    ack_deadline = finite_deadline(15)
    ack, ack_owners = await_original_payload_ack(intent, args.adb, args.serial, read_ack, controller, document_owner, ack_deadline, expected)
    audits = [content_operation(device, intent, "audit", "audit-%d" % index).stdout for index in (1, 2)]
    joined = stable_audits(intent, audits[0], audits[1], controller, document_owner, expected_native_output=expected, document_provider_observed=document_owner)
    parsed = exact_json(audits[1]); created, opened = parsed["documents"]["create.json"], parsed["documents"]["open.json"]
    require(opened["callerPid"] == original_exit["process"]["pid"] and opened["callerUid"] == root["uid"] and created["callerUid"] == root["docsUid"], "Original Native/App/system FD callers differ")
    final_controller = device.process("controller", phase="final")
    final_documents = device.process("documents", phase="final")
    require(final_controller["status"] == final_documents["status"] == "present" and final_controller["process"] == controller and final_documents["process"] == document_owner,
        "Persistent original provider replaced; do not invent natural exit")
    require(_first.same(guard, _capture.p7_explicit_fd_source_guard(_first, Path(args.repo).resolve(), guard["sourceOverlay"])), "Final source drift")
    result = {"schema": "p7-framework-export-auxiliary-proof-v4", "status": "passed-auxiliary-API33-export", "run": root["run"], "operationId": root["operationId"],
        "nativeOperationId": root["nativeOperationId"], "rootApprovalSha256": digest(raw), "runtimeAdmissionSha256": digest(runtime_raw),
        "sdkSealSha256": root["sdkSealSha256"], "sourceGuardSha256": root["sourceGuardSha256"], "prepareRemoteEffectConfirmed": True,
        "providerReceiveAckJoined": True, "receiveAckIsWriterCompletion": False, "originalNativeCloseResultIdentityJoined": True,
        "actualCallbackGrantObserved": True, "grantTerminationIndependentlyVerified": False, "originalAppExit": original_exit,
        "persistentController": controller, "persistentDocuments": document_owner, "providerNaturalExitClaimed": False,
        "remoteExitFrameObserved": False, "nonceIndependentRuntimeMeasurement": False, "nativeResultSha256": digest(canonical(proof)),
        "payloadAckSha256": digest(canonical(ack)), "stableAudits": joined, "fullP7Accepted": False, "physicalDeviceAccepted": False,
        "shippingTransportAccepted": False, "importRunnerImplemented": False, "scopesAndKeysRetained": True}
    persist_json(output / "framework-export-proof.json", result)
    print(json.dumps({"schema": result["schema"], "status": result["status"], "run": root["run"], "fullP7Accepted": False}))
    return result


def main():
    parser = argparse.ArgumentParser(description="One new framework-v4 EXPORT only; no install/build/clear/kill/import/retry")
    for name in ("repo", "output-dir", "adb", "serial", "build-bundle", "root-approval", "root-approval-sha256", "runtime-admission"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        run_export(args)
    except BaseException as error:
        # Public failure output is class-only; scoped raw evidence holds exact bounded bytes.
        print(json.dumps({"schema": "p7-framework-export-failure-v4", "status": "UNKNOWN", "failureClass": type(error).__name__,
            "retainedOriginalClients": len(_OWNERS), "remoteExitFrameObserved": False, "operationReplayed": False}), flush=True)
        retain_unknown_owners()
        raise SystemExit(50)


if __name__ == "__main__":
    main()
