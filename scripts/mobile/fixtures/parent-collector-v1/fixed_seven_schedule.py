"""Closed Source67 B/C candidate: 70 live-protection Calls + one mutation +70.

No admission/SDK loader or CLI. Root must create and review the real original
owner first. The immutable claim binds THIS derived source and exact plan; r3's
file/default160 and original Runtime.Call are never modified.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
import ast
import fcntl
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import stat
import subprocess
import sys
import threading
import types

import fixed_actual_observer as actual
import fixed_parent_native as native
from fixed_parent_collector import Refused, closed, digest, json_bytes, need, pinned_file, sha

MIB = 1024 * 1024
RUNTIME_TAIL_CAP = 65536
STDERR_CAP = 2 * MIB  # unchanged pinned Runtime.Call LOG_CAP
SNAPSHOT_CALLS = 70
PHASE_CALLS = 141
DEFAULT_R3_CALLS = 160
STAT_FIELDS = ("mode", "uid", "gid", "nlink", "bytes", "device", "inode", "mtime", "ctime")
_RETAINED = []


def _typed(value):
    """Freeze JSON admission/config values without Python bool/int coercion."""
    kind = type(value)
    if value is None or kind in (bool, int, str, bytes): return (kind, value)
    if kind is float: return (kind, value.hex())
    if isinstance(value, Path): return (kind, str(value))
    if kind is re.Pattern:
        need(type(value.pattern) in (str, bytes) and type(value.flags) is int, "closed original regex constant")
        return (kind, _typed(value.pattern), _typed(value.flags))
    if kind is dict:
        need(all(type(k) is str for k in value), "literal typed admission keys")
        return (kind, tuple((k, _typed(value[k])) for k in sorted(value)))
    if kind in (list, tuple): return (kind, tuple(_typed(x) for x in value))
    if kind in (set, frozenset): return (kind, frozenset(_typed(x) for x in value))
    need(False, "unsupported original admission/config value")


def _namespace(target):
    # Keep the actual functions AND their code/globals: replacing __code__ on
    # the same function must not pass a mere function-identity comparison.
    return tuple((k, v, getattr(v, "__code__", None), getattr(v, "__globals__", None))
                 for k, v in vars(target).items() if k != "__builtins__")


class _OwnerSeal:
    """Same construction-time owner for BOTH phases; never constructs C early."""
    def __init__(self, schedule):
        w, s, d = schedule.worker, schedule.support, schedule.device
        self.schedule, self.worker, self.support = schedule, w, s
        self.refusal = Refused
        self.runtime, self.device, self.call_class = w.runtime, d, w.runtime.Call
        self.pid, self.phase = os.getpid(), w.phase
        self.binding, self.binding_value = w.binding, _typed(w.binding)
        self.steps, self.plan = schedule.steps, _typed([step.__dict__ for step in schedule.steps])
        self.registry, self.registry_value = schedule.registry, _typed(schedule.registry)
        self.source_path, self.source_sha, self.plan_sha = schedule.source_path, schedule.source_sha, schedule.plan_sha
        self.folder, self.once, self.fd = w.folder, w.once, w.lock_fd
        self.calls, self.streams, self.derived_calls = w.calls, w.streams, schedule.calls
        self.device_fields = _typed((d.serial, d.sdk, d.avd))
        # Freeze source constants recursively as well as their global objects:
        # package/UID/APK dictionaries must not drift in place between Calls.
        self.constants = tuple((obj, tuple((k, _typed(v)) for k, v in vars(obj).items()
                                          if k.isupper() and not k.startswith("_")))
                               for obj in (s, w.runtime, sys.modules[__name__], actual, native))
        self.namespaces = tuple((obj, _namespace(obj)) for obj in
                                (s, w.runtime, sys.modules[__name__], actual, native,
                                 type(w), type(d), self.call_class, type(schedule), _OwnerSeal))
        self.methods = tuple((obj, name, getattr(obj, name).__func__) for obj in (w, d, schedule)
                             for name, value in vars(type(obj)).items() if type(value) is types.FunctionType)
        self.standard = ((w.runtime, "threading", threading), (w.runtime, "subprocess", subprocess),
                         (threading, "Thread", threading.Thread), (threading, "Event", threading.Event),
                         (subprocess, "Popen", subprocess.Popen))
        need(all(getattr(obj, name) is value for obj, name, value in self.standard), "original runtime standard globals")
        self.sources = tuple((path, actual.identity(path)) for path in schedule.source_files)
        self.folder_identity = self._folder_identity()
        self.once_identity = self._private_file_identity(w.once)
        self.claim_identity = self._private_file_identity(w.folder / "ownership.json")

    def _folder_identity(self):
        value = os.stat(self.folder, follow_symlinks=False)
        need(stat.S_ISDIR(value.st_mode) and value.st_uid == os.geteuid() and
             stat.S_IMODE(value.st_mode) == 0o700, "owned0700 original private folder")
        return value.st_dev, value.st_ino, value.st_uid, value.st_mode

    def _private_file_identity(self, path):
        value = os.stat(path, follow_symlinks=False)
        need(value.st_uid == os.geteuid() and stat.S_IMODE(value.st_mode) == 0o600, "owned0600 original private claim")
        return actual.identity(path)

    def verify(self):
        def require(condition, message):
            # Raising locally also catches a changed code object on the same
            # global need() function; the changed helper cannot approve itself.
            if not condition: raise self.refusal(message)
        for obj, rows in self.namespaces:
            current = vars(obj)
            require(set(current) - {"__builtins__"} == {row[0] for row in rows} and
                 all(current[k] is value and getattr(value, "__code__", None) is code and
                     getattr(value, "__globals__", None) is globals_ for k, value, code, globals_ in rows),
                 "original class/method/global identities changed")
        require(all(getattr(obj, name) is value for obj, name, value in self.standard), "original runtime standard globals changed")
        w, s, q, d = self.worker, self.support, self.schedule, self.device
        require(all(type(getattr(obj, name)) is types.MethodType and getattr(obj, name).__self__ is obj and
                    getattr(obj, name).__func__ is function for obj, name, function in self.methods),
                "original bound worker/Device/schedule methods replaced")
        require(q.steps is self.steps and _typed([step.__dict__ for step in q.steps]) == self.plan and
                q.registry is self.registry and _typed(q.registry) == self.registry_value and q.roots is q.registry["roots"] and
                q.source_path is self.source_path and q.source_sha == self.source_sha and q.plan_sha == self.plan_sha,
                "original closed registry/plan/source binding changed")
        require(os.getpid() == self.pid and q.worker is w and q.support is s and type(w) is s._Worker and
             w.uncertain is False and w.lock_acquired is True and w.phase == self.phase and type(w.phase) is str and w.binding is self.binding and
             _typed(w.binding) == self.binding_value, "original typed phase/binding/owner changed")
        require(w.runtime is self.runtime and w.runtime.Call is self.call_class and w.device is d and q.device is d and
             type(d) is FixedScheduleDevice and d.owner is w and d.schedule is q and
             _typed((d.serial, d.sdk, d.avd)) == self.device_fields and
             d.folder is self.folder and d.calls is self.calls and
             (d.uid is None if "before-packages-runas-app" not in q.values else
              type(d.uid) is int and d.uid == s.OLD_UID["app"]), "original runtime/Call/Device changed")
        require(all(_typed(getattr(obj, k)) == value for obj, rows in self.constants for k, value in rows),
             "original typed target/source globals changed")
        require(w.folder is self.folder and w.once is self.once and type(w.lock_fd) is int and w.lock_fd == self.fd and
             w.calls is self.calls and w.streams is self.streams and q.calls is self.derived_calls and
             len(w.calls) == len(q.calls) and all(a is b and type(a) is self.call_class for a, b in zip(w.calls, q.calls)),
             "original folder/once/fence/Call ledger replaced")
        require(self._folder_identity() == self.folder_identity and self._private_file_identity(w.once) == self.once_identity and
             self._private_file_identity(w.folder / "ownership.json") == self.claim_identity and
             all(actual.identity(path) == value for path, value in self.sources), "original private folder/claim/source identity changed")
        s.private_directory(w.folder)
        require(w.device is q.device and type(q.device) is FixedScheduleDevice and q.device.owner is w and q.device.schedule is q,
             "different actual derived Device/owner")
        require(w.folder == q.initial_folder and w.lock_fd == q.initial_fd and w.lock_acquired and
             not w.uncertain and not os.path.lexists(s.UNRESOLVED) and len(w.calls) == len(q.calls), "same original owner, no hidden/unknown Calls")
        actual.held_fence(w.lock_fd, Path(s.LOCK))
        require(s.read_regular(w.once, 32768) == q.owner_raw == s.read_regular(w.folder / "ownership.json", 32768), "original immutable once/owner changed")
        require(sha(s.read_regular(q.source_path, 2 * MIB)) == q.source_sha and
             sha(actual.encoded([x.__dict__ for x in q.steps])) == q.plan_sha and
             all(getattr(q, k).__func__ is v for k, v in q.original_methods.items()), "derived source/plan/method changed")


@dataclass(frozen=True)
class Step:
    label: str
    operation: str
    stdout_cap: int
    timeout: int = 45
    root: str | None = None


def require_original_stats(observed, expected):
    need(type(observed) is dict and set(observed) == set(expected), "all original stat members required")
    for path, row in observed.items():
        need(type(row) is dict and set(row) == set(STAT_FIELDS) and all(type(x) is int for x in row.values()) and
             row == expected[path], "complete exact nine-field original stat, no bool/omission")


def validate_registry(registry):
    rows = registry["roots"]
    need(len(rows) == 7 and len({r["root"] for r in rows}) == 7, "exact seven originals")
    roots = [r["root"] for r in rows]
    for root in roots:
        need(re.fullmatch(r"files/acceptance/P7-private-claims-[0-9a-f-]{36}", root), "literal original root")
        need(not any(root != other and root.startswith(other + "/") for other in roots), "overlapping original roots")
    members = [m for r in rows for m in r["projection"]["members"]]
    need(len(members) == 166 and len({m["path"] for m in members}) == 166, "all166 original members, no omitted root")
    for row in rows:
        need(1 <= len(row["projection"]["members"]) <= 64, "one fixed metadata batch per original")
        for m in row["projection"]["members"]:
            need(all(k in m for k in STAT_FIELDS) and m["path"] == str(PurePosixPath(m["path"])) and
                 (m["path"] == row["root"] or m["path"].startswith(row["root"] + "/")), "complete nine stat fields and exact member scope")
    return rows


def steps_for(phase, registry):
    need(phase in ("B", "C"), "only fixed Test-upgrade or alias-observer phase")
    roots = validate_registry(registry)
    out = []
    for sample in ("before", "after"):
        def add(name, operation, cap=16384, root=None):
            out.append(Step(sample + "-" + name, operation, cap, root=root))
        for suffix in ("identity-before",):
            for key in ("avd", "completed", "sdk", "abi", "boot", "count", "fingerprint"):
                add(suffix + "-" + key, "identity:" + key)
        for kind in ("full", "processes"):
            add("am-before-" + kind, "am:" + kind, 8 * MIB)
        add("packages-full-list", "pm-list", 2 * MIB)
        for role in ("app", "test"):
            for method, cap in (("path", 16384), ("sha", 16384), ("dump", 2 * MIB), ("runas", 16384)):
                add("packages-" + method + "-" + role, method + ":" + role, cap)
        add("ps", "ps", 2 * MIB)
        add("microphone-appops", "appops:RECORD_AUDIO", 65536)
        add("notification-appops", "appops:POST_NOTIFICATION", 65536)
        add("default-before", "default", 65536)
        add("seven-presence", "presence")
        add("seven-parents-before", "parents")
        for i, row in enumerate(roots):
            root = row["root"]
            names = row["projection"]["members"]
            find_cap = sum(len(m["path"].encode("ascii")) + 1 for m in names)
            # Retain each original tar independently, including its exact raw
            # archive hash, all headers/body/padding and nine original stats.
            for method, cap in (("members-before", find_cap), ("stat-before", 16384),
                                ("tar", row["archiveBytes"]), ("members-after", find_cap), ("stat-after", 16384)):
                add("root-%d-" % i + method, method, cap, root)
        add("seven-parents-after", "parents")
        add("default-after", "default", 65536)
        for kind in ("full", "processes"):
            add("am-after-" + kind, "am:" + kind, 8 * MIB)
        for key in ("avd", "completed", "sdk", "abi", "boot", "count", "fingerprint"):
            add("identity-after-" + key, "identity:" + key)
        if sample == "before":
            out.append(Step("only-test-upgrade" if phase == "B" else "only-default-alias-observer",
                            "install-test" if phase == "B" else "observer", 65536, 150 if phase == "B" else 45))
    need(len(out) == PHASE_CALLS and out[70].operation in ("install-test", "observer"), "closed phase count/order")
    return tuple(out)


def budget(phase, registry):
    steps = steps_for(phase, registry)
    return {"phase": phase, "calls": len(steps), "snapshotCalls": SNAPSHOT_CALLS,
            "stdoutAcceptedBytes": sum(s.stdout_cap for s in steps),
            "stderrAcceptedBytes": len(steps) * STDERR_CAP,
            "stdoutPersistedMaxBytes": sum(s.stdout_cap for s in steps),
            "stderrPersistedMaxBytes": len(steps) * (STDERR_CAP + RUNTIME_TAIL_CAP),
            "stderrDiagnosticTailSafetyBytes": len(steps) * RUNTIME_TAIL_CAP,
            "scope": "accepted/persisted bytes; unknown original drain may continue, no finite network-byte claim",
            "steps": [{"ordinal": i + 1, **s.__dict__, "stderr_cap": STDERR_CAP, "stderr_tail_cap": RUNTIME_TAIL_CAP} for i, s in enumerate(steps)]}


def permission_projection(raw, name):
    text = raw.decode("utf-8", "strict")
    need(text.endswith("\n") and "User 0:" in text and "android.permission." + name in text, "complete declared target permission dump")
    lines = [line.strip() for line in text.splitlines() if "android.permission." + name + ":" in line]
    need(len(lines) <= 1, "ambiguous permission/user flags")
    if lines:
        need(re.search(r"\bgranted=(true|false)\b", lines[0]), "permission granted/flags line")
    return {"permissionLine": lines[0] if lines else None, "runtimeEntryAbsent": not lines}


class FixedScheduleDevice:
    """New source-bound target view; all Calls go through the closed ledger."""
    def __init__(self, owner, support, schedule):
        self.owner, self.schedule = owner, schedule
        self.calls, self.folder = owner.calls, owner.folder
        self.serial, self.sdk, self.avd, self.uid = support.SERIAL, support.SDK, support.AVD, None

    def call(self, *args, **kwargs):
        need(False, "Device does not expose an arbitrary Call entry; use fixed phase")


class FixedSevenSchedule:
    """Actual existing owner + derived closed capture port, not tunable budget.

    No arbitrary argv/cap or JSON admission. Root's original Runtime.Call stays
    pinned; only this explicitly fingerprinted capture/control implementation
    routes the finite plan. Unknown keeps original Calls and original r3 hold.
    """
    def __init__(self, support, worker, registry_path):
        need(__debug__ and sys.flags.optimize == 0, "optimized Python cannot attest actual source control flow")
        need(type(support) is types.ModuleType and type(worker) is support._Worker and
             worker.binding.get("executeEnabled") is True, "actual Root admission missing/default off")
        need(worker.phase in ("B", "C") and worker.sequence == 0 and not worker.calls and
             worker.pending_mutation is None and not worker.uncertain, "new exact phase owner; no prior/unaccounted Calls")
        self.support, self.worker = support, worker
        self.source_files = {}
        def pin(path, expected, cap):
            path = Path(path)
            raw = pinned_file(path, expected, cap)
            self.source_files[path] = actual.identity(path)
            return raw
        closed(worker.binding, actual.BINDING_KEYS)
        if worker.phase == "B": need(worker.binding["observationRun"] is None, "B does not allocate an observer run")
        else: native.canonical_uuid(worker.binding["observationRun"])
        need(Path(support.__file__) == support.P / "source66-api33-upgrade-warm-source-r3/support.py" and
             Path(worker.runtime.__file__) == support.RUNTIME and support.RUNTIME_SHA == actual.RUNTIME_SHA,
             "exact original private support/runtime source")
        support_raw = pin(Path(support.__file__), actual.R3_SHA, 2 * MIB)
        root_code = actual.compile_original(support_raw, support.__file__)
        for name in ("__init__", "_call", "settlement", "hold", "finish_known", "has_unsettled", "fixture_stat_rows", "fixture_tar"):
            actual.require_code(getattr(worker, name), actual.code_at(root_code, "_Worker", name))
            need(getattr(worker, name).__func__.__globals__ is support.__dict__, "original worker method globals required")
        for name in ("read_regular", "immutable", "private_directory", "one_line", "package_table", "process_table"):
            actual.require_code(getattr(support, name), actual.code_at(root_code, name))
            need(getattr(support, name).__globals__ is support.__dict__, "original helper globals required")
        for node in ast.parse(support_raw).body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and node.targets[0].id in ("ADB", "SERIAL", "SDK", "PKG", "AVD", "BOOT", "BOOT_COUNT", "ABI", "FINGERPRINT", "OLD_UID"):
                need(_typed(getattr(support, node.targets[0].id)) == _typed(ast.literal_eval(node.value)), "fixed typed original target globals changed")
        runtime_raw = pin(Path(worker.runtime.__file__), actual.RUNTIME_SHA, 2 * MIB)
        actual.require_code(worker.runtime.Call.__init__, actual.code_at(actual.compile_original(runtime_raw, worker.runtime.__file__), "Call", "__init__"))
        need(worker.runtime.Call.__init__.__globals__ is worker.runtime.__dict__, "original Call runtime globals required")
        self.registry_raw = pin(Path(registry_path), actual.ORIGINALS_SHA, 128 * 1024)
        self.registry = json_bytes(self.registry_raw)
        self.roots = validate_registry(self.registry)
        self.steps = steps_for(worker.phase, self.registry)
        self.cursor, self.started = 0, False
        self.before_admitted, self.mutation = False, None
        self.calls, self.values, self.samples = [], {}, {}
        self.source_path = Path(__file__).absolute()
        self.source_sha = sha(support.read_regular(self.source_path, 2 * MIB))
        for path in (self.source_path, Path(actual.__file__), Path(native.__file__)):
            self.source_files[path] = actual.identity(path)
        self.plan_sha = sha(actual.encoded([s.__dict__ for s in self.steps]))
        compiled = actual.compile_original(support.read_regular(self.source_path, 2 * MIB), str(self.source_path))
        for name in ("_typed", "_namespace"):
            function = globals()[name]
            actual.require_code(function, actual.code_at(compiled, name))
            need(function.__globals__ is globals(), "derived helper globals required")
        for cls in (_OwnerSeal, FixedScheduleDevice, FixedSevenSchedule):
            for name, function in vars(cls).items():
                if type(function) is types.FunctionType:
                    actual.require_code(function, actual.code_at(compiled, cls.__name__, name))
                    need(function.__globals__ is globals(), "derived class method globals required")
        self.original_methods = {name: getattr(self, name).__func__ for name, function in vars(FixedSevenSchedule).items()
                                 if type(function) is types.FunctionType}
        self.owner_raw = support.read_regular(worker.once, 32768)
        owner = json_bytes(self.owner_raw)
        need(worker.once == support.P / ("source67-seven-originals-" + worker.phase + "-once.json") and
             worker.folder.parent.parent == support.P / "source67-seven-originals-execution" and
             worker.folder.parent.name == support.SERIAL and worker.folder.name == worker.phase + "-" + worker.binding["lease"],
             "fixed original phase folder/once; no alternate replay entry")
        support.private_directory(worker.folder)
        need(owner["schema"] == "source67-seven-originals-owner-v1" and owner["owner"] == "/root" and
             owner["taskId"] == support.TASK_ID and owner["phase"] == worker.phase and
             _typed(owner["binding"]) == _typed(worker.binding) and type(owner["workerPid"]) is int and owner["workerPid"] == os.getpid() and
             owner["derivedPort"] == self.fingerprint(), "Root immutable claim must explicitly bind new derived source/plan")
        b = worker.binding
        for k in ("lease", "invocationId"):
            native.canonical_uuid(b[k])
        need(b["lease"] != b["invocationId"] and support.SERIAL == "emulator-5574" and support.SDK == 33 and support.PKG == native.PACKAGE,
             "fixed explicit Root phase target/IDs")
        t = b["tuple"]
        closed(t, {"serial", "avd", "sdk", "abi", "bootId", "bootCount", "fingerprint", "sourceSha256", "expandedSourceSha256", "apkSha256"})
        need(_typed(t) == _typed({"serial": support.SERIAL, "avd": support.AVD, "sdk": 33, "abi": support.ABI,
                   "bootId": support.BOOT, "bootCount": support.BOOT_COUNT, "fingerprint": support.FINGERPRINT,
                   "sourceSha256": actual.SOURCE_SHA, "expandedSourceSha256": actual.EXPANDED_SHA, "apkSha256": actual.APK_SHA}),
             "exact known Root device/boot/Source67 tuple; background pins are not execution binding")
        pin(Path(support.ADB), b["adbBinarySha256"], 128 * MIB)
        pin(support.P / "source67-root-sdk-review.json", actual.SDK_REVIEW_SHA, 65536)
        pin(support.P / "source67-default-alias-native/candidate.json", actual.CANDIDATE_SHA, 2 * MIB)
        for role, expected in actual.APK_SHA.items():
            pin(support.P / ("source67-default-alias-native/artifacts/" + role + ".apk"), expected, 128 * MIB)
        root_review = json_bytes(pin(Path(b["rootReviewPath"]), b["rootReviewSha256"], 128 * 1024))
        need(root_review["derivedPort"] == self.fingerprint() and root_review["lease"] == b["lease"] and
             root_review["invocationId"] == b["invocationId"] and root_review["tuple"] == b["tuple"] and
             root_review["baselineSha256"] == b["baselineSha256"] and root_review["appInstallationAllowed"] is False and
             root_review["examinerInstallationAllowed"] is False and root_review["nextCaseAllowed"] is False,
             "Root review must explicitly admit this closed derived source/phase")
        need(root_review["schema"] == ("source67-seven-originals-B-root-review-v1" if worker.phase == "B" else "source67-api33-one-observer-root-review-v1"), "correct explicit B/C Root admission")
        self.owner_sha = sha(self.owner_raw)
        self.initial_folder, self.initial_fd = worker.folder, worker.lock_fd
        self.paths = {}
        self.observer = None
        self.device = FixedScheduleDevice(worker, support, self)
        worker.device = self.device
        self._seal = _OwnerSeal(self)
        verify = self._seal.verify
        self._owner_check = (self._seal, verify, verify.__func__, verify.__func__.__code__)
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()

    def fingerprint(self):
        return {"kind": "source67-seven-originals-fixed141-v1", "sourceSha256": self.source_sha,
                "planSha256": self.plan_sha, "phase": self.worker.phase, "maxCalls": PHASE_CALLS,
                "r3SourceSha256": actual.R3_SHA, "r3DefaultLimit": DEFAULT_R3_CALLS,
                "runtimeSha256": actual.RUNTIME_SHA,
                "attestorSourceSha256": sha(self.support.read_regular(Path(actual.__file__), 2 * MIB)),
                "parserSourceSha256": sha(self.support.read_regular(Path(native.__file__), 2 * MIB))}

    def verify_owner(self):
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()

    def expected_args(self, step):
        s, op = self.support, step.operation
        shell = lambda command: ["shell", "-T", command]
        if op.startswith("identity:"):
            key = op.split(":")[1]
            commands = {"completed": "getprop sys.boot_completed", "sdk": "getprop ro.build.version.sdk",
                        "abi": "getprop ro.product.cpu.abi", "boot": "cat /proc/sys/kernel/random/boot_id",
                        "count": "settings get global boot_count", "fingerprint": "getprop ro.build.fingerprint"}
            return ["emu", "avd", "name"] if key == "avd" else shell(commands[key])
        if op.startswith("am:"):
            return shell("dumpsys activity" + (" processes" if op.endswith("processes") else ""))
        if op == "pm-list": return shell("pm list packages -U --user 0")
        if ":" in op:
            method, role = op.split(":")
            if method == "appops": return shell("cmd appops get " + s.PKG + " " + role)
            package = s.PACKAGES[role]
            if method == "path": return shell("pm path " + package)
            if method == "dump": return shell("dumpsys package " + package)
            if method == "runas": return shell("run-as " + package + " id -u")
            if method == "sha":
                # Derived ONLY from this sample's actual preceding pm-path Call.
                sample = step.label.split("-", 1)[0]
                raw = self.values[sample + "-packages-path-" + role][0]
                line = s.one_line(raw, "original-installed-path")
                need(line.startswith("package:") and re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", line[8:]), "original full single APK path")
                return shell("sha256sum " + shlex.quote(line[8:]))
        if op == "ps": return shell("ps -A -n -o UID,PID,PPID,NAME,ARGS")
        root_names = [row["root"] for row in self.roots]
        if op == "default":
            body = ('set -e\n[ ! -L no_backup ] || exit 61\nif [ ! -e no_backup ]; then printf "ABSENT\\0"; '
                    'else [ -d no_backup ] && [ -r no_backup ] && [ -x no_backup ] || exit 62; '
                    'printf "PRESENT\\0"; find no_backup -print0; fi')
            return shell("run-as " + s.PKG + " sh -c " + shlex.quote(body))
        if op == "presence":
            body = 'set -e\nfor p in ' + " ".join(map(shlex.quote, root_names)) + '; do [ ! -L "$p" ] && [ -d "$p" ] && [ -r "$p" ] && [ -x "$p" ] || exit 71; printf "PRESENT\\0"; done'
            return shell("run-as " + s.PKG + " sh -c " + shlex.quote(body))
        if op == "parents":
            paths = [".", "files", "files/acceptance", *root_names]
            body = ('set -e\ndata=$(pwd -P)\ncase "$data" in /data/data/' + s.PKG + '|/data/user/0/' + s.PKG + ') ;; *) exit 70;; esac\n'
                    'for p in ' + " ".join(map(shlex.quote, paths)) + '; do [ ! -L "$p" ] && [ -d "$p" ] && [ -r "$p" ] && [ -x "$p" ] || exit 71; '
                    'if [ "$p" = . ]; then expected="$data"; else expected="$data/$p"; fi; [ "$(cd "$p" && pwd -P)" = "$expected" ] || exit 72; '
                    'stat -c "%f|%u|%g|%h|%s|%d|%i|%Y|%Z" "$p"; printf "\\0"; done')
            return shell("run-as " + s.PKG + " sh -c " + shlex.quote(body))
        if op.startswith("members-"): return ["exec-out", "run-as", s.PKG, "find", step.root, "-print0"]
        if op.startswith("stat-"):
            names = next(r for r in self.roots if r["root"] == step.root)["projection"]["members"]
            body = 'set -e\nfor p in ' + " ".join(shlex.quote(m["path"]) for m in names) + '; do stat -c "%f|%u|%g|%h|%s|%d|%i|%Y|%Z" "$p"; printf "\\0"; done'
            return shell("run-as " + s.PKG + " sh -c " + shlex.quote(body))
        if op == "tar": return ["exec-out", "run-as", s.PKG, "tar", "-cf", "-", step.root]
        if op == "install-test": return ["install", "-r", "-t", str(s.P / "source67-default-alias-native/artifacts/test.apk")]
        if op == "observer": return list(native.native_arguments(native.OBSERVER, observation_run=self.worker.binding["observationRun"]))
        need(False, "operation outside closed fixed plan")

    def invoke(self, expected_operation=None):
        need(getattr(self, "started", False), "only fixed run entry admits Calls")
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        need(self.cursor < PHASE_CALLS and not self.worker.pending_mutation, "exhausted/unclassified phase; no replay")
        step = self.steps[self.cursor]
        need(expected_operation is None or step.operation == expected_operation, "cannot skip/reorder fixed plan")
        if step.operation in ("install-test", "observer"):
            need(self.before_admitted and "before" in self.samples, "no mutation without full admitted live sample")
        if self.cursor >= 71:
            need(self.mutation is not None and self.mutation.get("knownTerminal") is True, "no post sampling after unknown/unclassified mutation")
        args = self.expected_args(step)
        self.cursor += 1  # original attempt is consumed before any I/O
        return self.capture(step, args)

    def capture(self, step, args):
        """Derived r3 capture, exact per-Step cap instead of arbitrary cap/160.

        Preserve original registration-before-constructor, dual streams,
        original completion/deadline and unknown same-process hold. This is NEW
        source provenance, not claimed to be the old _call code object.
        """
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        w, s = self.worker, self.support
        need(step is self.steps[self.cursor - 1] and args == self.expected_args(step), "capture is only the exact consumed fixed slot/argv")
        need(w.sequence == len(self.calls) == self.cursor - 1 and self.cursor <= PHASE_CALLS, "fixed ledger/count/worker drift")
        w.sequence += 1
        label = "%04d-%s" % (w.sequence, step.label)
        stdout = w.folder / (label + "-stdout.bin")
        need(not os.path.lexists(stdout), "original capture exists; no replay")
        call = w.runtime.Call.__new__(w.runtime.Call)
        w.calls.append(call); self.calls.append(call)
        mutation = step.operation in ("install-test", "observer")
        if mutation: w.pending_mutation = {"kind": "install" if step.operation == "install-test" else "native", "label": label, "call": call, "terminalClassified": False}
        try:
            w.runtime.Call.__init__(call, w.folder, label, [s.ADB, "-s", s.SERIAL, *args], timeout=step.timeout, capture=stdout, cap=step.stdout_cap)
            need(w.settlement(call)["settled"], "original missing EOF/exit/deadline")
            out = s.read_regular(stdout, step.stdout_cap)
            err = s.read_regular(w.folder / (label + "-stderr.log"), STDERR_CAP)
            need(not call.reader_errors and not call.observer_error and call.count == [len(out), len(err)] and
                 [h.hexdigest() for h in call.hashes] == [sha(out), sha(err)], "actual complete original dual stream hashes/caps")
            completion = json_bytes(s.read_regular(w.folder / (label + "-completed.json"), 16384))
            intent = json_bytes(s.read_regular(w.folder / (label + "-intent.json"), 16384))
            actual.join_original_observer(call, intent)
            anchor = getattr(self, "_owner_check", None)
            if anchor is None: raise Refused("construction-time original owner verifier required")
            seal, verify, function, code = anchor
            if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                    function.__code__ is not code):
                raise seal.refusal("original captured owner verifier identity/code changed")
            verify()
            need(completion["stdoutSha256"] == sha(out) and completion["stderrSha256"] == sha(err) and
                 completion["exitCode"] == call.returncode and completion["localAdbPid"] == call.proc.pid and
                 completion["capExceeded"] is False and completion["lateAfterCallerDeadline"] is False and completion["readerErrors"] == [] and
                 0 <= completion["finishedAtMs"] - completion["startedAtMs"] <= step.timeout * 1000, "original completion/timeout")
            w.streams[id(call)] = (out, err)
            self.values[step.label] = (out, err, call)
            self.paths[step.label] = stdout
            if not mutation: self.validate_increment(step, out)
            if not mutation: need(call.returncode == 0 and not err, "known complete read failed; no continuation")
            s.immutable(w.folder / (label + "-derived-receipt.json"), {"derivedPort": self.fingerprint(), "ordinal": self.cursor,
                        "argv": call.argv, "stdoutBytes": len(out), "stderrBytes": len(err), "stdoutSha256": sha(out),
                        "stderrSha256": sha(err), "exitCode": call.returncode, "originalLocalPid": call.proc.pid,
                        "bothStreamsComplete": True, "originalObserverExited": True, "retries": 0})
            if not mutation: self.retire_known_pipes(call)
            return call
        except BaseException as failure:
            w.uncertain = True
            _RETAINED.append(self)
            w.hold(failure, "fixed-seven-original-call-incomplete-or-unclassified")

    def retire_known_pipes(self, call):
        need(any(c is call for c in self.calls) and call.done.is_set() and call.unknown is False and
             not call.thread.is_alive() and call.returncode is not None, "never close unknown/original active pipes")
        call.proc.stdout.close(); call.proc.stderr.close()

    def verify_device(self, observer):
        need(observer.worker is self.worker and observer.device is self.worker.device and self.worker.phase == "C", "different actual worker/device")
        need(type(self.worker.device) is FixedScheduleDevice and self.worker.device.owner is self.worker and
             self.worker.device.schedule is self and self.worker.device.calls is self.worker.calls,
             "actual source-bound derived Device calls relationship")

    def attest_owner(self, observer):
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        self.verify_device(observer)
        need(self.observer is observer and self.cursor == 71 and self.calls[-1] is observer.call,
             "original observer is not exact derived slot71")

    def observer_call(self, observer):
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        need(self.worker.phase == "C" and self.cursor == 70 and self.observer is None, "one observer after complete before sample")
        self.observer = observer
        return self.invoke("observer")

    def capture_sample(self, sample, expected_apks):
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        start = 0 if sample == "before" else 71
        need(self.cursor == start, "full fixed sample cannot be shortened/replayed")
        self.expected_apks = expected_apks
        for _ in range(SNAPSHOT_CALLS): self.invoke()
        value = self.validate_sample(sample, expected_apks)
        self.samples[sample] = value
        self.support.immutable(self.worker.folder / (sample + "-seven-protection.json"), value)
        return value

    def validate_increment(self, step, out):
        """Refuse changed universes/stats BEFORE reading any tar body."""
        s, w, op = self.support, self.worker, step.operation
        if op.startswith("members-") or op.startswith("stat-") or op == "tar":
            row = next(r for r in self.roots if r["root"] == step.root)
            members = row["projection"]["members"]; names = [m["path"] for m in members]
            if op.startswith("members-"):
                need(out.endswith(b"\0") and sorted(x.decode("ascii") for x in out.split(b"\0")[:-1]) == names,
                     "changed/omitted original member universe; no further capture")
            elif op.startswith("stat-"):
                expected = {m["path"]: {k: m[k] for k in STAT_FIELDS} for m in members}
                require_original_stats(s._Worker.fixture_stat_rows(w, names, out), expected)
            else:
                need(len(out) == row["archiveBytes"] and sha(out) == row["archiveSha256"], "original tar body/header/padding changed")
        if op == "presence": need(out == b"PRESENT\0" * 7, "missing original root; stop before tar")
        if op == "default": need(out == b"ABSENT\0", "default missing/empty/content changed; no continuation")
        if op == "parents":
            paths = [".", "files", "files/acceptance", *[r["root"] for r in self.roots]]
            rows = s._Worker.fixture_stat_rows(w, paths, out)
            for name, value in rows.items():
                need(value["uid"] == value["gid"] == s.OLD_UID["app"] and value["device"] == rows["."]["device"] and
                     value["mode"] in ((0o40700, 0o40771) if name == "files" else (0o40700,)), "parent UID/mode/mount")
                if name not in (".", "files", "files/acceptance"):
                    m = next(m for r in self.roots for m in r["projection"]["members"] if m["path"] == name)
                    need(value == {k: m[k] for k in STAT_FIELDS}, "original root parent stat changed")
        if op.startswith("identity:"):
            key = op.split(":")[1]
            if key == "avd": need(out.decode("ascii").splitlines() == [s.AVD, "OK"], "wrong actual AVD")
            else:
                expected = {"completed": "1", "sdk": "33", "abi": s.ABI, "boot": s.BOOT, "count": str(s.BOOT_COUNT), "fingerprint": s.FINGERPRINT}
                need(s.one_line(out, key) == expected[key], "wrong live target/boot before further reads")
        if op.startswith("am:"):
            text = out.decode("utf-8", "strict")
            need(text.endswith("\n") and "ACTIVITY MANAGER" in text and not re.search(r"ActiveInstrumentation\{|InstrumentationRecord\{|mActiveInstrumentation\s*=\s*(?!null\b|\[\s*\])\S", text), "active/ambiguous instrumentation")
        if op == "ps":
            rows = s.process_table(out)
            need(not any(r["uid"] in s.OLD_UID.values() or any(t == p or t.startswith(p + ":") for p in s.PACKAGES.values() for t in r["args"].split()) for r in rows.values()), "product process present; no kill")
        if op.startswith("dump:"):
            role = op.split(":")[1]; text = out.decode("utf-8", "strict")
            need({int(x) for x in re.findall(r"\b(?:userId|appId)=(\d+)\b", text)} == {s.OLD_UID[role]}, "wrong package dump UID")
            if role == "app":
                permission_projection(out, "RECORD_AUDIO"); permission_projection(out, "POST_NOTIFICATIONS")
        if op == "pm-list":
            table = s.package_table(out)
            need(s.PACKAGES["examiner"] not in table and all(table[s.PACKAGES[r]] == s.OLD_UID[r] for r in ("app", "test")), "package/UID set changed")
        if op.startswith("path:"):
            line = s.one_line(out, "APK path")
            need(line.startswith("package:") and re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", line[8:]), "unknown/split installed package path")
        if op.startswith("sha:"):
            role = op.split(":")[1]; sample = step.label.split("-", 1)[0]
            path = s.one_line(self.values[sample + "-packages-path-" + role][0], role)[8:]
            need(s.one_line(out, "APK SHA") == self.expected_apks[role] + "  " + path, "installed source bytes changed")
        if op.startswith("runas:"):
            role = op.split(":")[1]
            need(s.one_line(out, "actual UID") == str(s.OLD_UID[role]), "actual UID changed")
            if role == "app": self.device.uid = int(s.one_line(out, "actual UID"))

    def validate_sample(self, sample, expected_apks):
        s, w = self.support, self.worker
        get = lambda label: self.values[sample + "-" + label][0]
        identity = {}
        for side in ("before", "after"):
            values = {k: s.one_line(get("identity-" + side + "-" + k), k) for k in ("completed", "sdk", "abi", "boot", "count", "fingerprint")}
            need(get("identity-" + side + "-avd").decode("ascii").splitlines() == [s.AVD, "OK"] and
                 values == {"completed": "1", "sdk": "33", "abi": s.ABI, "boot": s.BOOT, "count": str(s.BOOT_COUNT), "fingerprint": s.FINGERPRINT}, "live identity/boot/target")
            identity[side] = values
        need(identity["before"] == identity["after"], "identity changed within sample")
        for side in ("before", "after"):
            for kind in ("full", "processes"):
                text = get("am-" + side + "-" + kind).decode("utf-8", "strict")
                need(text.endswith("\n") and "ACTIVITY MANAGER" in text and not re.search(r"ActiveInstrumentation\{|InstrumentationRecord\{|mActiveInstrumentation\s*=\s*(?!null\b|\[\s*\])\S", text), "active/ambiguous AM; no global absence claim")
        table = s.package_table(get("packages-full-list")); packages = {}
        need(s.PACKAGES["examiner"] not in table, "examiner must stay absent")
        for role in ("app", "test"):
            uid = s.OLD_UID[role]; package = s.PACKAGES[role]
            dump = get("packages-dump-" + role).decode("utf-8", "strict")
            need(table[package] == uid and {int(x) for x in re.findall(r"\b(?:userId|appId)=(\d+)\b", dump)} == {uid} and
                 s.one_line(get("packages-runas-" + role), role) == str(uid), "full pm/dump/runas original UID")
            path = s.one_line(get("packages-path-" + role), role)[8:]
            need(s.one_line(get("packages-sha-" + role), role) == expected_apks[role] + "  " + path, "actual whole installed APK bytes")
            packages[role] = {"package": package, "path": path, "sha256": expected_apks[role], "uid": uid, "runAsUid": uid}
        packages["examiner"] = None; w.device.uid = s.OLD_UID["app"]
        ps = s.process_table(get("ps"))
        need(not any(r["uid"] in s.OLD_UID.values() or any(t == p or t.startswith(p + ":") for p in s.PACKAGES.values() for t in r["args"].split()) for r in ps.values()), "product process still present; no kill or absence proof")
        dump = get("packages-dump-app")
        permissions = {}
        for name, key in (("RECORD_AUDIO", "microphone"), ("POST_NOTIFICATIONS", "notification")):
            value = permission_projection(dump, name)
            value.update(appOpsRaw=get(key + "-appops").decode("utf-8", "strict"))
            permissions[key] = value
        # Missing and present-empty are distinct. Current frozen originals have
        # no_backup ABSENT; ANY appearance refuses, never normalized to absence.
        defaults = [get("default-" + side).split(b"\0") for side in ("before", "after")]
        need(defaults[0] == defaults[1] == [b"ABSENT", b""], "default no_backup missing/empty/content state changed")
        need(get("seven-presence") == b"PRESENT\0" * 7, "one original missing; no subset capture")
        paths = [".", "files", "files/acceptance", *[r["root"] for r in self.roots]]
        parents = [s._Worker.fixture_stat_rows(w, paths, get("seven-parents-" + side)) for side in ("before", "after")]
        need(parents[0] == parents[1], "parent/root metadata changed within sample")
        for path, row in parents[0].items():
            need(row["mode"] in ((0o40700, 0o40771) if path == "files" else (0o40700,)) and
                 row["uid"] == row["gid"] == s.OLD_UID["app"] and row["device"] == parents[0]["."]["device"], "complete same-device parent chain")
        fixtures = {}
        for i, original in enumerate(self.roots):
            root = original["root"]; members = original["projection"]["members"]; names = [m["path"] for m in members]
            expected_stats = {m["path"]: {k: m[k] for k in STAT_FIELDS} for m in members}
            for side in ("before", "after"):
                raw = get("root-%d-members-" % i + side)
                need(raw.endswith(b"\0") and sorted(x.decode("ascii") for x in raw.split(b"\0")[:-1]) == names, "full original member universe changed")
                require_original_stats(s._Worker.fixture_stat_rows(w, names, get("root-%d-stat-" % i + side)), expected_stats)
            need(parents[0][root] == expected_stats[root], "merged parent root differs from same member sample")
            tar_path = self.paths[sample + "-root-%d-tar" % i]
            raw = get("root-%d-tar" % i)
            need(len(raw) == original["archiveBytes"] and sha(raw) == original["archiveSha256"], "entire original tar body/header/padding changed")
            rows, _ = s._Worker.fixture_tar(w, tar_path, root, expected_stats)
            need(rows == members, "complete original file bytes/stat/tar comparison")
            fixtures[root] = original["projection"]
        return {"identity": {"serial": s.SERIAL, "avd": s.AVD, "sdk": 33, "abi": s.ABI, "bootId": s.BOOT, "bootCount": s.BOOT_COUNT, "fingerprint": s.FINGERPRINT},
                "packages": packages, "microphone": permissions["microphone"], "notificationPermission": permissions["notification"],
                "defaultFamily": {"noBackupState": "ABSENT", "roots": [], "members": [], "uid": s.OLD_UID["app"]},
                "originalFixtures": fixtures, "parents": parents[0], "source": "live-fixed-derived141", "samplingAtomic": False,
                "globalInstrumentationAbsenceProven": False, "keyIdentityProven": False}

    def finish(self, before, after, mutation):
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        need(before is getattr(self, "samples", {}).get("before") and after is getattr(self, "samples", {}).get("after") and
             mutation is getattr(self, "mutation", None) and mutation is not None, "release requires original live sample/result objects")
        need(self.cursor == PHASE_CALLS and len(self.calls) == PHASE_CALLS and not self.worker.pending_mutation and
             not self.worker.has_unsettled(), "wrong/early/unclassified release")
        need(all(before[k] == after[k] for k in before if k != "packages"), "full live original protection changed")
        for role in ("app", "test", "examiner"):
            if self.worker.phase == "B" and role == "test" and mutation.get("success") is True:
                need(all(before["packages"][role][k] == after["packages"][role][k] for k in ("package", "uid", "runAsUid")), "Test UID identity changed")
            else: need(before["packages"][role] == after["packages"][role], "App/examiner/untouched installed package changed")
        self.support.immutable(self.worker.folder / "derived-seven-final-review.json", {"derivedPort": self.fingerprint(), "calls": self.cursor,
                               "allSevenAndDefaultPermissionsUidBootPreserved": True, "mutation": mutation, "nativePassed": False,
                               "automaticNextPhase": False})
        if self.worker.phase == "B" and mutation.get("success") is True:
            keys = ("identity", "packages", "microphone", "notificationPermission", "defaultFamily")
            self.support.immutable(self.worker.folder / "baseline.json", {
                "schema": "source67-api33-test-only-protected-baseline-v1", "tuple": self.worker.binding["tuple"],
                "originalsRegistrySha256": actual.ORIGINALS_SHA, "originalFixtures": after["originalFixtures"],
                "originalFixturesBefore": before["originalFixtures"], "beforeSnapshot": {k: before[k] for k in keys},
                "snapshot": {k: after[k] for k in keys}, "parents": after["parents"], "testInstallOnly": True})
        # Release uses this NEW source-bound finalizer, not r3's Source66 result
        # schema/global tuple. No device Call and no next phase are introduced.
        result = {"schema": "source67-seven-originals-phase-result-v1", "phase": self.worker.phase,
                  "tuple": self.worker.binding["tuple"], "lease": self.worker.binding["lease"],
                  "derivedPort": self.fingerprint(), "calls": self.cursor, "nativePassed": False,
                  "mutation": mutation, "allSevenOriginalFilesAndDefaultPermissionsUidBootPreserved": True, "keyIdentityProven": False, "automaticNextPhase": False}
        self.support.immutable(self.worker.folder / "result.json", result)
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        fd = self.worker.lock_fd
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except BaseException as failure:
            self.worker.hold(failure, "local-unlock-result-unconfirmed-retain-original")
        self.worker.lock_acquired = False
        try:
            os.close(fd)
            self.worker.lock_fd = None
        except BaseException as failure:
            _RETAINED.append(self)
            return {**result, "lockReleased": True, "originalFdCloseUnconfirmed": True,
                    "releaseReceiptSaved": False, "localFailure": type(failure).__name__, "noAutomaticRetry": True}
        try:
            self.support.immutable(self.worker.folder / "known-release-receipt.json", {**result, "lockReleased": True,
                                   "pendingMutation": False, "allStartedCallsSettledWithBothEof": True})
        except BaseException as failure:
            _RETAINED.append(self)
            return {**result, "lockReleased": True, "releaseReceiptSaved": False,
                    "localFailure": type(failure).__name__, "noAutomaticRetry": True}
        return {**result, "lockReleased": True, "releaseReceiptSaved": True}

    def run(self):
        need(not self.started, "one phase, immutable once, no replay")
        anchor = getattr(self, "_owner_check", None)
        if anchor is None: raise Refused("construction-time original owner verifier required")
        seal, verify, function, code = anchor
        if (seal is not self._seal or verify.__self__ is not seal or verify.__func__ is not function or
                function.__code__ is not code):
            raise seal.refusal("original captured owner verifier identity/code changed")
        verify()
        self.started = True
        old = {**actual.APK_SHA, "test": "c78a1cff89dd958c5d0e400a29d553d6acd2653451c1fde0a9055e70544fb325"}
        try:
            before = self.capture_sample("before", old if self.worker.phase == "B" else actual.APK_SHA)
            if self.worker.phase == "C":
                b = self.worker.binding
                baseline = json_bytes(pinned_file(Path(b["baselinePath"]), b["baselineSha256"], 8 * MIB))
                keys = ("identity", "packages", "microphone", "notificationPermission", "defaultFamily")
                need({k: before[k] for k in keys} == baseline["snapshot"] and before["originalFixtures"] == baseline["originalFixtures"] and
                     before["parents"] == baseline["parents"], "fresh complete live C sample differs from accepted B; old baseline cannot substitute")
            else:
                b = self.worker.binding
                baseline = json_bytes(pinned_file(Path(b["baselinePath"]), b["baselineSha256"], 8 * MIB))
                old_snapshot = baseline["snapshot"]
                need(before["identity"] == old_snapshot["identity"] and before["packages"] == old_snapshot["packages"] and
                     old_snapshot["defaultFamily"]["noBackupState"] == "ABSENT" and not old_snapshot["defaultFamily"]["members"] and
                     all(before["microphone"][k] == old_snapshot["microphone"][k] for k in ("permissionLine", "runtimeEntryAbsent", "appOpsRaw")),
                     "live before B does not preserve original Source66 identity/package/default/microphone")
            self.before_admitted = True
            if self.worker.phase == "B":
                call = self.invoke("install-test"); out, err = self.worker.streams[id(call)]
                # Fixed Source67 path, not r3's old Source66 rejection-path literal.
                text = out.decode("utf-8", "strict"); errors = err.decode("utf-8", "strict")
                lines = [x.strip() for x in (text + "\n" + errors).splitlines() if x.strip()]
                progress = ("Performing Streamed Install", "Performing Push Install")
                success = call.returncode == 0 and not err and lines.count("Success") == 1 and lines[-1] == "Success" and all(x in (*progress, "Success") for x in lines)
                fixed_path = str(self.support.P / "source67-default-alias-native/artifacts/test.apk")
                pattern = re.compile(r"^(?:adb: failed to install " + re.escape(fixed_path) + r": )?Failure \[(INSTALL_FAILED_[A-Z0-9_]+)(?:: [^\r\n]*)?\]$")
                rejected = call.returncode > 0 and sum(bool(pattern.fullmatch(x)) for x in lines) == 1 and all(x in progress or pattern.fullmatch(x) for x in lines)
                need(success or rejected, "original Test install terminal unknown")
                self.worker.pending_mutation = None
                self.retire_known_pipes(call)
                mutation = {"knownTerminal": True, "success": success, "role": "test", "replayAllowed": False}
                self.mutation = mutation
                after = self.capture_sample("after", actual.APK_SHA if success else old)
            else:
                self.observer = None
                observer = actual.FixedActualObserver(self.support, self.worker, fixed_schedule=self)
                mutation = observer.run_once()
                self.retire_known_pipes(observer.call)
                self.mutation = mutation
                after = self.capture_sample("after", actual.APK_SHA)
            return self.finish(before, after, mutation)
        except BaseException as failure:
            _RETAINED.append(self)
            self.worker.hold(failure, "fixed-seven-phase-unknown-or-protection-unclassified")
