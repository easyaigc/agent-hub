"""One fixed observer on Root's live Source66-r3 Call/Device ownership ports.

No loader, SDK discovery, owner acquisition, install, CLI, UUID allocation or
reconciliation API. Root must first implement/admit the closed Source67 B/C
preservation plan; background SDK pins never activate this module.
"""
from __future__ import annotations

import ast
import errno
import fcntl
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import sys
import threading
import time
import types

import fixed_parent_native as native
from fixed_parent_collector import canonical_uuid, closed, digest, json_bytes, need, pinned_file, sha

R3_SHA = "861f4889944d690360d58f046d87b1262fa25106ce38976f92bbff92af031ed4"
RUNTIME_SHA = "137e5f1b2ef0f7bf4ca41f95e120737aa9f509c2a6e0ce9271a543a9238b7d14"
SDK_REVIEW_SHA = "d050f4da413204649e86a17baba4ff586698497bc32e2edd8d1d0f9455100c35"
CANDIDATE_SHA = "cfa7eee05cf0fe2f422f162cd731cc1803ad8a48061c264e40f58e744503317a"
ORIGINALS_SHA = "f9229c1cef919ff85502670cd7b0a2fc1d1ecf8ff62ad9dd1d4f825c5a88c8a0"
NATIVE_SHA = "f2a37ecc8ae3a93ceb5014297d58a80f12cf9499c29441c01abbcb8e26d64aaf"
APK_SHA = {"app": "9aaaf5a9126a3d0fb5ffaf75be00d6030c249dc56b0b0cb58bcfe435d2fa8b83",
           "test": "f966d170c95ee30ae4c844a82c09aac3f47ca1fe6049296ddb63e19156abe832",
           "examiner": "f6c813b5a10329ec1b97e7cbf009c71502837f794adf831b2a79cb4ec15c256a"}
SOURCE_SHA = "ea24c6f8986c5fa4aca48a1ed3310a346b13db9f7cbaff73de6b39812e3ab4ad"
EXPANDED_SHA = "bd690f19440b5e38aa90f7ec0087f0f72324bdc09cdbc9a2eda7e9770c59768c"
NATIVE_PATH = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeDefaultAliasObservationTest.kt"
BINDING_KEYS = {"executeEnabled", "lease", "observationRun", "invocationId", "adbBinarySha256", "tuple",
                "baselinePath", "baselineSha256", "rootReviewPath", "rootReviewSha256", "originalsRegistryPath"}
_RETAINED = []


def encoded(value):
    import json
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def identity(path):
    s = os.stat(path, follow_symlinks=False)
    need(stat.S_ISREG(s.st_mode) and s.st_nlink == 1, "single-link regular source/evidence")
    return s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns


def compile_original(raw, filename):
    # This module enables future annotations. Original r3/runtime files do not;
    # inherit neither our flags nor an executor's optimization setting.
    return compile(raw, filename, "exec", dont_inherit=True, optimize=0)


def code_at(parent, *names):
    for name in names:
        matches = [c for c in parent.co_consts if isinstance(c, types.CodeType) and c.co_name == name]
        need(len(matches) == 1, "one pinned code object " + name)
        parent = matches[0]
    return parent


def _code_identity(value):
    """Exact typed code fields; marshal reference/intern flags are not semantics."""
    kind = type(value)
    if kind is types.CodeType:
        return ("code", tuple((name, _code_identity(getattr(value, name)))
                              for name in sorted(dir(value))
                              if name.startswith("co_") and not callable(getattr(value, name))))
    if kind is tuple:
        return ("tuple", tuple(_code_identity(item) for item in value))
    if kind is frozenset:
        return ("frozenset", tuple(sorted((_code_identity(item) for item in value), key=repr)))
    if kind in (type(None), type(Ellipsis), bool, int, str, bytes):
        return (kind.__name__, value)
    if kind is float:
        return ("float", value.hex())
    if kind is complex:
        return ("complex", value.real.hex(), value.imag.hex())
    raise ValueError("unsupported pinned code constant type")


def require_code(function, expected):
    actual = getattr(function, "__func__", function)
    need(type(actual) is types.FunctionType and type(expected) is types.CodeType and
         _code_identity(actual.__code__) == _code_identity(expected),
         "actual method is not the pinned original code")


def held_fence(fd, path):
    """Probe ONLY the same existing inode, never create an alternative lock.

    FD/inode and original-owner control flow bind this process; a conflicting
    independent open confirms kernel exclusion. If unlocked, close only this
    probe's accidental acquisition and refuse; never repair the original lock.
    """
    need(type(fd) is int and fd >= 0 and isinstance(path, Path), "actual existing fence")
    original = os.fstat(fd)
    need(stat.S_ISREG(original.st_mode) and original.st_uid == os.geteuid() and original.st_nlink == 1
         and original.st_size == 0 and stat.S_IMODE(original.st_mode) == 0o600, "owned0600 original fence FD")
    probe = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        other = os.fstat(probe)
        need((original.st_dev, original.st_ino) == (other.st_dev, other.st_ino), "original fence pathname/inode changed")
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as failure:
            need(failure.errno in (errno.EAGAIN, errno.EWOULDBLOCK), "kernel fence probe failure")
        else:
            fcntl.flock(probe, fcntl.LOCK_UN)
            need(False, "original fence no longer excludes another open; no reacquire/repair")
    finally:
        os.close(probe)


def join_original_observer(call, intent):
    """Bounded LOCAL join of the same completed observer, within old deadline."""
    need(type(call.thread) is threading.Thread and type(call.done) is type(threading.Event()) and
         call.done.is_set() and call.unknown is False, "original done/Thread/deadline required")
    need(type(intent["startedAtMs"]) is int and type(intent["timeoutSeconds"]) is int and
         intent["timeoutSeconds"] in (45, 150), "original fixed deadline")
    if call.thread.is_alive():
        remaining = (intent["startedAtMs"] + intent["timeoutSeconds"] * 1000 - time.time() * 1000) / 1000
        call.thread.join(timeout=min(0.25, max(0.0, remaining)))
    need(not call.thread.is_alive(), "original observer did not exit inside local/original deadline; retain context")


class FixedActualObserver:
    """Requires a REAL already-admitted Root worker; no JSON-only constructor.

    The exact r3 worker methods, runtime Call constructor and inner PhaseDevice
    call code are checked against compiled pinned source, not class names.
    Root's NEW B/C admission is a separate prerequisite, not supplied here.
    """
    def __init__(self, support, worker, fixed_schedule=None):
        need(__debug__ and sys.flags.optimize == 0, "optimized Python cannot attest actual source control flow")
        need(type(support) is types.ModuleType and type(worker) is support._Worker, "actual r3 module/worker required")
        self.support, self.worker = support, worker
        self.fixed_schedule = fixed_schedule
        self.pid, self.device, self.runtime = os.getpid(), worker.device, worker.runtime
        self.folder, self.calls, self.lock_fd = worker.folder, worker.calls, worker.lock_fd
        self.started, self.call, self.port = False, None, None
        self.failure, self.native_scope = None, None
        self.source_files = {}
        self.binding_raw = encoded(worker.binding)
        b = worker.binding
        closed(b, BINDING_KEYS)
        need(b["executeEnabled"] is True, "Root Source67 B/C admission is unbound")
        ids = [canonical_uuid(b[k]) for k in ("lease", "observationRun", "invocationId")]
        need(len(set(ids)) == 3, "three distinct original Root IDs, never allocate here")
        for key in ("adbBinarySha256", "baselineSha256", "rootReviewSha256"):
            digest(b[key])
        need(worker.phase == "C" and self.device is not None and self.runtime is not None, "live admitted C owner/device/runtime")
        self.support_path = Path(support.__file__)
        need(self.support_path == support.P / "source66-api33-upgrade-warm-source-r3/support.py", "exact API33 r3 support, not Source62 reader")
        support_raw = self.pin(self.support_path, R3_SHA, 2 * 1024 * 1024)
        root_code = compile_original(support_raw, str(self.support_path))
        constants = {node.targets[0].id: ast.literal_eval(node.value) for node in ast.parse(support_raw).body
                     if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                     and node.targets[0].id in {"ADB", "SERIAL", "SDK", "PKG", "AVD", "BOOT", "BOOT_COUNT", "ABI", "FINGERPRINT", "RUNTIME_SHA", "OLD_UID"}}
        need(all(getattr(support, key) == value for key, value in constants.items()), "original fixed r3 target/SDK globals changed")
        self.original_constants = constants
        self.original_helpers = {}
        self.original_methods = {}
        for name in ("__init__", "_call", "settlement", "has_unsettled", "hold"):
            require_code(getattr(worker, name), code_at(root_code, "_Worker", name))
            self.original_methods[name] = getattr(worker, name).__func__
            need(self.original_methods[name].__globals__ is support.__dict__, "original worker method globals")
        for name in ("read_regular", "immutable", "no_links", "private_directory", "require", "sha", "json_bytes"):
            require_code(getattr(support, name), code_at(root_code, name))
            need(getattr(support, name).__globals__ is support.__dict__, "original support globals required")
            self.original_helpers[name] = getattr(support, name)
        self.runtime_path = Path(self.runtime.__file__)
        need(self.runtime_path == support.RUNTIME and support.RUNTIME_SHA == RUNTIME_SHA, "exact original runtime source")
        raw_runtime = self.pin(self.runtime_path, RUNTIME_SHA, 2 * 1024 * 1024)
        runtime_code = compile_original(raw_runtime, str(self.runtime_path))
        self.call_code = code_at(runtime_code, "Call", "__init__")
        require_code(self.runtime.Call.__init__, self.call_code)
        need(self.runtime.Call.__init__.__globals__ is self.runtime.__dict__ and
             self.runtime.threading is threading and self.runtime.subprocess is subprocess,
             "original private runtime globals/standard threading/process modules")
        if fixed_schedule is None:
            require_code(self.device.call, code_at(root_code, "_Worker", "admit", "PhaseDevice", "call"))
            self.original_call_init, self.original_device_call = self.runtime.Call.__init__, self.device.call.__func__
            need(self.device.calls is worker.calls and self.device.folder == worker.folder and
                 self.device.serial == support.SERIAL == "emulator-5574" and self.device.sdk == support.SDK == 33,
                 "actual original Device/worker/target relationship")
            closure = dict(zip(self.device.call.__func__.__code__.co_freevars,
                               (c.cell_contents for c in self.device.call.__func__.__closure__)))
            need(closure.get("worker") is worker, "actual original PhaseDevice closure must own this worker")
            need(worker.once == support.P / "source67-api33-observer-C-once.json" and
                 worker.folder.parent.parent == support.P / "source67-api33-observer-execution" and
                 worker.folder.parent.name == "emulator-5574" and worker.folder.name == "C-" + b["lease"],
                 "exact future Root C owner and once location; no alternative state directory")
        else:
            from fixed_seven_schedule import FixedSevenSchedule
            need(type(fixed_schedule) is FixedSevenSchedule, "concrete derived schedule, not callback")
            fixed_schedule.verify_device(self)
            self.original_call_init, self.original_device_call = self.runtime.Call.__init__, self.device.call.__func__
        support.private_directory(worker.folder)
        self.device_signature = (self.device.serial, self.device.sdk, self.device.avd, self.device.uid)
        self._source67()
        self._owner()
        self.claim_path = worker.once
        self.claim_raw = self.read(self.claim_path, 32768)
        need(self.claim_raw == self.read(worker.folder / "ownership.json", 32768), "same immutable once/owner bytes")
        claim = json_bytes(self.claim_raw)
        need(claim["schema"] == ("source67-api33-observer-ownership-v1" if self.fixed_schedule is None else "source67-seven-originals-owner-v1") and claim["owner"] == "/root" and
             claim["taskId"] == support.TASK_ID and claim["workerPid"] == self.pid and claim["phase"] == "C" and
             claim["lease"] == b["lease"] and claim["binding"] == b and claim["tuple"] == b["tuple"] and
             claim["executionFolder"] == str(worker.folder) and claim["onceClaimPath"] == str(worker.once) and
             claim["lockPath"] == str(support.LOCK) and claim["runtimeSha256"] == RUNTIME_SHA,
             "immutable owner/once does not bind this actual original worker")
        self.claim_sha = sha(self.claim_raw)
        self.stage_sha = sha(self.binding_raw)
        self.invocation_path = worker.folder / "p9-fixed-observer-intent.json"
        need(not os.path.lexists(self.invocation_path), "observer original intent already exists; never replay")
        self.expected_arguments = native.native_arguments(native.OBSERVER, observation_run=b["observationRun"])
        self.expected_argv = [support.ADB, "-s", support.SERIAL, *self.expected_arguments]

    def read(self, path, cap):
        # r3 reader rejects symlinks at every ancestor and reads full bounded bytes.
        raw = self.support.read_regular(Path(path), cap)
        mode = os.stat(path, follow_symlinks=False)
        need(mode.st_uid == os.geteuid() and stat.S_IMODE(mode.st_mode) == 0o600, "owned0600 original private evidence")
        need(identity(path)[2] == len(raw), "complete original local evidence")
        return raw

    def pin(self, path, expected_sha, cap):
        raw = pinned_file(Path(path), expected_sha, cap)
        self.source_files[Path(path)] = identity(path)
        return raw

    def _source67(self):
        s, b = self.support, self.worker.binding
        candidate_path = s.P / "source67-default-alias-native/candidate.json"
        candidate = json_bytes(self.pin(candidate_path, CANDIDATE_SHA, 2 * 1024 * 1024))
        self.pin(s.P / "source67-root-sdk-review.json", SDK_REVIEW_SHA, 65536)
        need(candidate["sourceSha256"] == SOURCE_SHA and candidate["expandedSourceSha256"] == EXPANDED_SHA and
             len(candidate["files"]) == 842 and len(candidate["expandedFiles"]) == 855 and
             candidate["files"][NATIVE_PATH] == NATIVE_SHA, "fixed Source67 Native source fingerprint")
        for role, expected in APK_SHA.items():
            need(candidate[role + "Sha256"] == expected, "Source67 package metadata drift")
            self.pin(candidate_path.parent / "artifacts" / (role + ".apk"), expected, 128 * 1024 * 1024)
        self.pin(candidate_path.parent / "frozen-source" / NATIVE_PATH, NATIVE_SHA, 16384)
        self.pin(Path(s.ADB), b["adbBinarySha256"], 128 * 1024 * 1024)
        t = b["tuple"]
        closed(t, {"serial", "avd", "sdk", "abi", "bootId", "bootCount", "fingerprint", "sourceSha256", "expandedSourceSha256", "apkSha256"})
        need(t["serial"] == s.SERIAL and t["sdk"] == 33 and t["abi"] == s.ABI and t["avd"] == s.AVD and
             t["bootId"] == s.BOOT and t["bootCount"] == s.BOOT_COUNT and t["fingerprint"] == s.FINGERPRINT and
             t["sourceSha256"] == SOURCE_SHA and t["expandedSourceSha256"] == EXPANDED_SHA and t["apkSha256"] == APK_SHA,
             "explicit actual Root source/device tuple required; background review is not binding")
        registry = json_bytes(self.pin(Path(b["originalsRegistryPath"]), ORIGINALS_SHA, 128 * 1024))
        need(len(registry["roots"]) == 7 and len({r["root"] for r in registry["roots"]}) == 7, "all seven exact original fixtures")
        for row in registry["roots"]:
            self.pin(s.P / row["index"], row["indexSha256"], 128 * 1024)
            self.pin(s.P / row["archive"], row["archiveSha256"], 32 * 1024 * 1024)
        baseline = json_bytes(self.pin(Path(b["baselinePath"]), b["baselineSha256"], 8 * 1024 * 1024))
        review = json_bytes(self.pin(Path(b["rootReviewPath"]), b["rootReviewSha256"], 128 * 1024))
        need(baseline["schema"] == "source67-api33-test-only-protected-baseline-v1" and baseline["tuple"] == t and
             baseline["originalsRegistrySha256"] == ORIGINALS_SHA and len(baseline["originalFixtures"]) == 7,
             "Root B Test-only upgrade preservation baseline required")
        expected_originals = {r["root"]: r["projection"] for r in registry["roots"]}
        need(baseline["originalFixtures"] == baseline["originalFixturesBefore"] == expected_originals, "full original seven tree identity before/after Test upgrade")
        before, after = baseline["beforeSnapshot"], baseline["snapshot"]
        for snapshot in (before, after):
            closed(snapshot, {"identity", "packages", "microphone", "notificationPermission", "defaultFamily"})
            need(snapshot["identity"] == {k: t[k] for k in ("serial", "avd", "sdk", "abi", "bootId", "bootCount", "fingerprint")}, "actual Root B boot/identity snapshot")
            need(snapshot["packages"]["examiner"] is None, "examiner must remain absent")
            permission = snapshot["notificationPermission"]
            closed(permission, {"permissionLine", "appOpsRaw"} if self.fixed_schedule is None else {"permissionLine", "runtimeEntryAbsent", "appOpsRaw"})
            need((permission["permissionLine"] is None or type(permission["permissionLine"]) is str) and
                 type(permission["appOpsRaw"]) is str and 0 < len(permission["appOpsRaw"]) <= 65536,
                 "actual complete notification permission flags/AppOps projection required")
        need(all(before[k] == after[k] for k in ("identity", "microphone", "notificationPermission", "defaultFamily")) and
             before["packages"]["app"] == after["packages"]["app"] and
             after["packages"]["app"]["sha256"] == APK_SHA["app"], "App/default family/permission/boot must remain exact")
        for role in ("app", "test"):
            need(all(before["packages"][role][k] == after["packages"][role][k] for k in ("package", "uid", "runAsUid")) and
                 after["packages"][role]["uid"] == s.OLD_UID[role], "original package UID and run-as identity")
        need(before["packages"]["test"]["sha256"] == "c78a1cff89dd958c5d0e400a29d553d6acd2653451c1fde0a9055e70544fb325" and
             after["packages"]["test"]["sha256"] == APK_SHA["test"], "only exact Source66-to67 Test bytes may change")

        expected_review = {"schema": "source67-api33-one-observer-root-review-v1", "lease": b["lease"],
             "observationRun": b["observationRun"], "invocationId": b["invocationId"], "tuple": t,
             "baselineSha256": b["baselineSha256"], "adbBinarySha256": b["adbBinarySha256"], "originalsRegistrySha256": ORIGINALS_SHA,
             "selector": native.OBSERVER, "admitOneObserverOnly": True, "appInstallationAllowed": False,
             "examinerInstallationAllowed": False, "nextCaseAllowed": False}
        if self.fixed_schedule is not None:
            expected_review["derivedPort"] = self.fixed_schedule.fingerprint()
        need(review == expected_review, "exact Root one-observer C review and derived source required")
        self.baseline = baseline

    def _owner(self):
        w, s = self.worker, self.support
        if self.fixed_schedule is not None and self.call is not None:
            self.fixed_schedule.attest_owner(self)
        need(os.getpid() == self.pid and w.device is self.device and w.runtime is self.runtime and
             encoded(w.binding) == self.binding_raw and not w.uncertain and w.lock_acquired is True,
             "original same-process owner/context changed or unresolved")
        need(all(getattr(s, key) == value for key, value in self.original_constants.items()) and
             all(getattr(s, key) is value for key, value in self.original_helpers.items()) and
             (self.device.serial, self.device.sdk, self.device.avd, self.device.uid) == self.device_signature,
             "original fixed globals/helpers/Device fields changed")
        need(all(getattr(w, name).__func__ is original for name, original in self.original_methods.items()) and
             self.runtime.Call.__init__ is self.original_call_init and self.device.call.__func__ is self.original_device_call,
             "actual original method identities replaced")
        need(w.calls is self.calls and w.folder == self.folder and w.lock_fd == self.lock_fd,
             "original calls/folder/fence FD replaced")
        need(w.device.calls is w.calls and w.device.uid == s.OLD_UID["app"] and w.folder == w.device.folder,
             "actual original app UID/Device relationship changed")
        need(not os.path.lexists(s.UNRESOLVED) and not os.path.lexists(w.folder / "known-release-receipt.json"),
             "unresolved/released original owner cannot admit/review")
        s.no_links(Path(s.LOCK))
        held_fence(w.lock_fd, Path(s.LOCK))
        for path, old in self.source_files.items():
            need(identity(path) == old, "pinned source/evidence identity changed")
        if hasattr(self, "claim_raw"):
            need(w.once == self.claim_path, "original once path replaced")
            need(self.read(self.claim_path, 32768) == self.claim_raw == self.read(w.folder / "ownership.json", 32768),
                 "same immutable once and owner intent changed")

    def attest(self, port):
        self._owner()
        w, call = self.worker, self.call
        need(port is self.port and type(call) is self.runtime.Call and port.original_call is call and
             sum(c is call for c in w.calls) == 1 and call.argv == self.expected_argv and call.proc.args == self.expected_argv and
             call.folder == w.folder and call.capture == w.folder / (call.label + "-stdout.bin"), "actual original Call identity/argv/capture")
        require_code(type(call).__init__, self.call_code)
        need(stat.S_ISFIFO(os.fstat(call.proc.stdout.fileno()).st_mode) and
             stat.S_ISFIFO(os.fstat(call.proc.stderr.fileno()).st_mode), "actual original independent process pipes")
        original_intent = json_bytes(self.read(w.folder / (call.label + "-intent.json"), 16384))
        join_original_observer(call, original_intent)
        need(type(call.proc) is subprocess.Popen and type(call.thread) is threading.Thread and
             type(call.done) is type(threading.Event()) and call.done.is_set() and not call.thread.daemon and
             call.thread.ident is not None and not call.thread.is_alive(), "actual original observer thread must have exited")
        # Pinned observe() joins BOTH actual drain threads before wait/completion
        # and done.set(). Dead original observer + clean errors is the causal EOF
        # witness. r3 does not retain reader Thread objects: no independent reader
        # object inventory or background-exception traceback claim is invented.
        need(call.unknown is False and call.reader_errors == [] and call.observer_error is None and
             type(call.returncode) is int and call.proc.returncode == call.returncode and
             call.cap == (self.support.CAP if self.fixed_schedule is None else 65536) and all(0 <= c <= native.CAP for c in call.count), "original full streams/deadline/exit/caps")
        need(w.pending_mutation is not None and w.pending_mutation["call"] is call and
             w.pending_mutation["kind"] == "native" and w.pending_mutation["terminalClassified"] is False,
             "original pending Native identity; not a copied receipt")
        out = self.read(call.capture, native.CAP)
        err = self.read(w.folder / (call.label + "-stderr.log"), native.CAP)
        need(w.streams.get(id(call)) == (out, err) == (port.stdout, port.stderr) and
             call.count == [len(out), len(err)] and [h.hexdigest() for h in call.hashes] == [sha(out), sha(err)],
             "actual in-memory and complete durable double stream hashes")
        completed = json_bytes(self.read(w.folder / (call.label + "-completed.json"), 16384))
        intent = json_bytes(self.read(w.folder / (call.label + "-intent.json"), 16384))
        need(intent["argv"] == self.expected_argv and intent["timeoutSeconds"] == 45 and
             intent["stdoutCap"] == (self.support.CAP if self.fixed_schedule is None else 65536) and intent["stderrCap"] == self.support.CAP and intent["capture"] == str(call.capture) and
             intent["noKillOrRetryOnTimeout"] is True, "original bounded Call intent")
        need(all(type(completed[k]) is int for k in ("exitCode", "stdoutBytes", "stderrBytes", "localAdbPid", "startedAtMs", "finishedAtMs")) and
             type(intent["timeoutSeconds"]) is int, "actual typed original completion values, no bool coercion")
        need(completed["exitCode"] == call.returncode and completed["stdoutBytes"] == len(out) and completed["stderrBytes"] == len(err) and
             completed["stdoutSha256"] == sha(out) and completed["stderrSha256"] == sha(err) and
             completed["lateAfterCallerDeadline"] is False and completed["capExceeded"] is False and completed["readerErrors"] == [] and
             completed["localAdbPid"] == call.proc.pid and completed["startedAtMs"] == intent["startedAtMs"] and
             0 <= completed["finishedAtMs"] - intent["startedAtMs"] <= 45000,
             "original physical completion/EOF/exit and deadline, not CLI boolean")
        need(self.read(self.invocation_path, 32768) == self.invocation_raw and
             port.invocation_id == w.binding["invocationId"] and port.binding_sha256 == self.stage_sha and
             port.arguments == self.expected_arguments and port.exit_code == call.returncode and port.physical_settled is True,
             "immutable original operation/port binding")
        # No proc/device query here. Native PID is extracted ONLY from these
        # authenticated-by-source/original-Call bytes, never supplied as an input.
        target = self.target_from_original(out, err, call.returncode)
        need(target == self.target, "original Native process metadata drift")

    def target_from_original(self, out, err, code):
        terminal = native.single_method(out, err, native.OBSERVER, code)
        target = {"observationRun": self.worker.binding["observationRun"], "packageName": native.PACKAGE,
                  "appUid": self.device.uid, "pid": None, "sdk": 33}
        if len(terminal["payloads"]) == 1:
            try:
                observed = json_bytes(terminal["payloads"][0].encode())
                candidate = {**target, "pid": observed.get("pid")}
                observed = native.observation_payload(terminal["payloads"][0], candidate)
            except (ValueError, TypeError, AttributeError):
                return target  # known refused metadata; absent PID is never invented
            target = candidate
            self.native_scope = {"bindingSha256": self.stage_sha, "invocationId": self.worker.binding["invocationId"],
                                 "observationRun": observed["observationRun"], "nonce": observed["nonce"],
                                 "pid": observed["pid"], "appUid": observed["appUid"], "sdk": observed["sdk"], "packageName": observed["packageName"],
                                 "sourceSha256": SOURCE_SHA, "nativeSourceSha256": NATIVE_SHA,
                                 "testApkSha256": APK_SHA["test"], "originalStdoutSha256": sha(out),
                                 "originalOwnerIntentSha256": self.claim_sha, "originalInvocationSha256": sha(self.invocation_raw),
                                 "independentKernelPidQuery": False}
        return target

    def run_once(self):
        """After actual Root B/C admission, ONE observer; no install/next-case.

        Unknown enters the ORIGINAL r3 worker's indefinite same-process hold.
        Known selected failure can return only for Root's fixed read-only post
        preservation schedule. This method never releases Root ownership.
        """
        need(not self.started, "single original observer; no retry/review replacement")
        self._owner()
        need(self.worker.pending_mutation is None and not self.worker.has_unsettled(), "existing unresolved operation blocks observer")
        self.started = True
        try:
            intent = {"schema": "p9-fixed-observer-original-invocation-v1", "bindingSha256": self.stage_sha,
                      "invocationId": self.worker.binding["invocationId"], "observationRun": self.worker.binding["observationRun"],
                      "ownerIntentSha256": self.claim_sha, "argv": self.expected_argv, "selector": native.OBSERVER,
                      "sourceSha256": SOURCE_SHA, "nativeSourceSha256": NATIVE_SHA, "testApkSha256": APK_SHA["test"],
                      "timeoutSeconds": 45, "nextCaseAllowed": False, "replayAllowed": False}
            self.support.immutable(self.invocation_path, intent)
            self.invocation_raw = self.read(self.invocation_path, 32768)
            if self.fixed_schedule is None:
                self.call = self.worker._call("p9-fixed-default-alias", list(self.expected_arguments), 45)
            else:
                self.call = self.fixed_schedule.observer_call(self)
            out, err = self.worker.streams[id(self.call)]
            self.target = self.target_from_original(out, err, self.call.returncode)
            self.port = native.OriginalCallPort(self.call, self.expected_arguments, self.stage_sha,
                         self.worker.binding["invocationId"], out, err, self.call.returncode, True, self.attest)
            result = native.review_original_call(self.port, native.OBSERVER, self.stage_sha,
                         self.worker.binding["invocationId"], target=self.target)
            need(result["knownTerminal"], "unknown original observer result; retain real context")
            # Only this exact known selected terminal is classified. Root still
            # owns the flock/once and must complete its fixed post preservation.
            need(self.worker.pending_mutation["call"] is self.call, "pending original changed")
            self.worker.pending_mutation = None
            self.support.immutable(self.worker.folder / "p9-fixed-observer-review.json", {**result, "nativeScope": self.native_scope,
                                   "rootPostProtectionStillRequired": True, "ownershipReleased": False})
            return result
        except BaseException as failure:
            self.failure = (failure, failure.__traceback__)
            _RETAINED.append(self)
            # Includes constructor failures: original r3 _call registered the
            # actual original Call before __init__ and kept causal foreground state.
            self.worker.hold(failure, "p9-original-observer-unresolved-or-unclassified")
