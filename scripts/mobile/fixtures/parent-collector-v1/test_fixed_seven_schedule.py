"""Host-only finite schedule and refusal vectors; no Root admission or device."""
import ast
import copy
import re
from contextlib import contextmanager
import fcntl
import os
import threading
import tempfile
from pathlib import Path
import types
import unittest
from unittest.mock import patch

import fixed_seven_schedule as p
from fixed_parent_collector import Refused


def registry():
    roots = []
    for i, count in enumerate((14, 45, 20, 16, 14, 31, 26)):
        root = "files/acceptance/P7-private-claims-00000000-0000-0000-0000-%012d" % (i + 1)
        members = []
        for n in range(count):
            members.append({"path": root if n == 0 else root + "/file-%02d" % n,
                            **dict.fromkeys(p.STAT_FIELDS, 1), "type": "directory" if n == 0 else "file"})
        roots.append({"root": root, "archiveBytes": (41472, 102400, 34816, 34816, 25600, 70656, 61440)[i],
                      "projection": {"members": members}})
    return {"roots": roots}


@contextmanager
def offline_sealed_schedule(phase="B", *, final=False):
    """Exercise the real seal/entry guards, not Root admission or a fake permit.

    Only local empty files/fence and inert classes are used. No Call constructor
    may be reached by a refusal vector; this is not real-owner attestation.
    """
    with tempfile.TemporaryDirectory(prefix="p9-owner-seal-") as tmp:
        root = Path(tmp)
        support = types.ModuleType("offline_support")
        runtime = types.ModuleType("offline_runtime")
        class Call:
            attempts = []
            def __init__(self, *args, **kwargs):
                type(self).attempts.append((args, kwargs))
                raise AssertionError("refusal must precede every Call constructor")
        class Worker:
            def has_unsettled(self): return False
            def hold(self, *args): raise Refused("offline hold")
        support._Worker = Worker
        runtime.Call, runtime.threading, runtime.subprocess = Call, p.threading, p.subprocess
        support.P, support.RUNTIME = root, root / "runtime.py"
        support.__file__, runtime.__file__ = str(root / "support.py"), str(support.RUNTIME)
        for path in (Path(support.__file__), support.RUNTIME):
            path.write_bytes(b"offline inert source\n"); path.chmod(0o600)
        support.SERIAL, support.SDK, support.AVD = "emulator-5574", 33, "offline"
        support.ADB, support.PKG = "offline-adb-never-executed", p.native.PACKAGE
        support.ABI, support.BOOT, support.BOOT_COUNT, support.FINGERPRINT = "x86_64", "offline", 1, "offline"
        support.OLD_UID, support.PACKAGES = {"app": 10001, "test": 10002}, {"app": support.PKG, "test": support.PKG + ".test"}
        support.RUNTIME_SHA, support.TASK_ID, support.CAP = p.actual.RUNTIME_SHA, "offline", p.STDERR_CAP
        support.LOCK, support.UNRESOLVED = root / "fence", root / "unresolved"
        support.read_regular = lambda path, cap: Path(path).read_bytes()
        support.private_directory = lambda path: None
        writes = []
        def immutable(*args):
            writes.append(args)
            raise AssertionError("refusal must precede finalization writes/release")
        support.immutable = immutable
        fd = os.open(support.LOCK, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            q = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule)
            w = Worker()
            q.support, q.worker = support, w
            w.runtime, w.phase = runtime, phase
            w.binding = {"executeEnabled": True, "lease": "original-lease", "invocationId": "original-invocation",
                         "tuple": {"serial": support.SERIAL, "sdk": 33, "apkSha256": {"app": "a" * 64}}}
            w.folder, w.once = root / "execution", root / "once.json"
            w.folder.mkdir(mode=0o700)
            w.lock_fd, w.lock_acquired, w.uncertain, w.pending_mutation = fd, True, False, None
            w.calls, w.streams, w.sequence = [], {}, 0
            q.calls, q.values, q.paths, q.samples = [], {}, {}, {}
            q.initial_folder, q.initial_fd = w.folder, fd
            q.owner_raw = b'{"offline":true}\n'
            for path in (w.once, w.folder / "ownership.json"):
                path.write_bytes(q.owner_raw); path.chmod(0o600)
            q.device = p.FixedScheduleDevice(w, support, q); w.device = q.device
            q.source_path = Path(p.__file__).absolute()
            q.source_sha = p.sha(q.source_path.read_bytes())
            q.source_files = {path: p.actual.identity(path) for path in
                              (Path(support.__file__), support.RUNTIME, q.source_path, Path(p.actual.__file__), Path(p.native.__file__))}
            q.registry = registry(); q.roots = q.registry["roots"]
            q.steps = p.steps_for(phase, q.registry)
            q.plan_sha = p.sha(p.actual.encoded([x.__dict__ for x in q.steps]))
            q.original_methods = {name: getattr(q, name).__func__ for name in
                                  ("invoke", "capture", "capture_sample", "validate_increment", "validate_sample", "finish", "run")}
            q.cursor, q.started = 0, True
            q.before_admitted, q.mutation = False, None
            q._seal = p._OwnerSeal(q)
            verify = q._seal.verify
            q._owner_check = (q._seal, verify, verify.__func__, verify.__func__.__code__)
            if final:
                calls = [Call.__new__(Call) for _ in range(141)]
                q.calls.extend(calls); w.calls.extend(calls); w.sequence = q.cursor = 141
                q.device.uid = support.OLD_UID["app"]
                q.values["before-packages-runas-app"] = (b"10001\n", b"", calls[0])
                q.samples = {sample: {"packages": {role: {} for role in ("app", "test", "examiner")}}
                             for sample in ("before", "after")}
                q.mutation = {"knownTerminal": True, "success": False}
            q.verify_owner()  # Positive unchanged control before each mutation.
            yield q, Call.attempts, writes
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)


class FixedScheduleNegatives(unittest.TestCase):

    def test_compiled_regex_freeze_preserves_pattern_type_and_flags(self):
        text = re.compile("fixed-[0-9]+", re.ASCII)
        binary = re.compile(b"fixed-[0-9]+", re.ASCII)
        self.assertIs(type(text.flags), int)
        self.assertIs(type(binary.flags), int)
        self.assertEqual(text.flags, binary.flags)
        self.assertNotEqual(p._typed(text), p._typed(binary))
        self.assertNotEqual(p._typed(text), p._typed(re.compile("fixed-[0-9]+", re.ASCII | re.IGNORECASE)))
        self.assertEqual(p._typed(text), p._typed(re.compile(text.pattern, text.flags)))
        class RegexLike:
            pattern, flags = text.pattern, text.flags
        with self.assertRaises(Refused):
            p._typed(RegexLike())
        with self.assertRaises(Refused):
            p._typed(object())

    def test_pinned_support_runtime_regex_declaration_shapes_can_freeze(self):
        # Exact regex declarations from pinned r3 support and runtime. Only AST
        # literals are read; neither original module nor any owner is loaded.
        declarations = (
            'DIGEST = re.compile(r"[a-f0-9]{64}")\n'
            'UUID = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")\n'
            'SHA = re.compile(r"[a-f0-9]{64}")\n'
        )
        constants = {}
        for declaration in ast.parse(declarations).body:
            self.assertIs(type(declaration), ast.Assign)
            call = declaration.value
            self.assertEqual((call.func.value.id, call.func.attr), ("re", "compile"))
            self.assertEqual(len(call.args), 1)
            self.assertEqual(call.keywords, [])
            constants[declaration.targets[0].id] = re.compile(ast.literal_eval(call.args[0]))
        self.assertEqual(set(constants), {"DIGEST", "UUID", "SHA"})
        frozen = p._typed(constants)
        self.assertEqual(p._typed(constants["DIGEST"]), p._typed(constants["SHA"]))
        self.assertNotEqual(p._typed(constants["DIGEST"]), p._typed(constants["UUID"]))
        changed = dict(constants, SHA=re.compile(constants["SHA"].pattern, re.IGNORECASE))
        self.assertNotEqual(frozen, p._typed(changed))

    def test_full_phase_budget_has_all_additional_calls_and_exact_mutation(self):
        for phase, mutation in (("B", "install-test"), ("C", "observer")):
            b = p.budget(phase, registry())
            self.assertEqual(b["calls"], 141)
            self.assertEqual(len(b["steps"][:70]), 70)
            self.assertEqual(b["steps"][70]["operation"], mutation)
            self.assertEqual(len(b["steps"][71:]), 70)
            self.assertEqual(sum(x["operation"] == "tar" for x in b["steps"]), 14)
            self.assertEqual(sum(x["operation"] == "parents" for x in b["steps"]), 4)
            self.assertEqual(sum(x["operation"].startswith("appops:") for x in b["steps"]), 4)
            self.assertEqual(b["stderrAcceptedBytes"], 141 * 2 * 1024 * 1024)
            self.assertLess(b["stdoutAcceptedBytes"] + b["stderrAcceptedBytes"], 1024 ** 3)

    def test_omitted_original_or_swapped_root_refuses(self):
        for r in (registry(), registry()):
            if len(r["roots"]) == 7:
                r["roots"].pop()
            with self.assertRaises(Refused): p.steps_for("B", r)
        r = registry(); r["roots"][0]["root"] = r["roots"][1]["root"]
        with self.assertRaises(Refused): p.steps_for("C", r)
        r = registry(); r["roots"][0]["root"] = r["roots"][0]["root"].replace("000000000001", "000000000099")
        with self.assertRaises(Refused): p.steps_for("B", r)

    def test_missing_ninth_stat_and_changed_member_refuse(self):
        r = registry(); del r["roots"][0]["projection"]["members"][0]["ctime"]
        with self.assertRaises(Refused): p.steps_for("C", r)
        expected = {"one": dict.fromkeys(p.STAT_FIELDS, 1)}
        p.require_original_stats(copy.deepcopy(expected), expected)
        for key in p.STAT_FIELDS:
            changed = copy.deepcopy(expected); del changed["one"][key]
            with self.assertRaises(Refused): p.require_original_stats(changed, expected)
        changed = copy.deepcopy(expected); changed["one"]["uid"] = True
        with self.assertRaises(Refused): p.require_original_stats(changed, expected)

    def test_exhausted_and_replay_never_capture(self):
        context = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule)
        context.verify_owner = lambda: None
        context.worker = types.SimpleNamespace(pending_mutation=None)
        context.cursor = 141; context.started = True
        context.capture = lambda *a: self.fail("must not capture after exhaustion")
        with self.assertRaises(Refused): context.invoke()
        context.started = True
        with self.assertRaises(Refused): context.run()

    def test_unknown_and_different_worker_refuse_before_fence_or_capture(self):
        context = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule)
        worker = types.SimpleNamespace(folder=Path("offline"), lock_fd=1, calls=[], uncertain=True, lock_acquired=True)
        context.worker = worker
        context.support = types.SimpleNamespace(UNRESOLVED=Path("offline-unresolved"))
        context.initial_folder = worker.folder; context.initial_fd = 1; context.calls = []
        context.device = p.FixedScheduleDevice(worker, types.SimpleNamespace(SERIAL="fake", SDK=33, AVD="fake"), context)
        worker.device = context.device
        with patch.object(p.actual, "held_fence", side_effect=AssertionError("unknown must not reach fence probe")):
            with self.assertRaises(Refused): context.verify_owner()
            context.worker = types.SimpleNamespace(device=context.device)
            with self.assertRaises(Refused): context.verify_owner()

    def test_early_or_unclassified_release_refuses(self):
        context = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule)
        context.verify_owner = lambda: None
        context.worker = types.SimpleNamespace(pending_mutation=None, has_unsettled=lambda: False)
        before, after, mutation = {}, {}, {"knownTerminal": True}
        context.samples = {"before": before, "after": after}; context.mutation = mutation
        context.cursor = 140; context.calls = [object()] * 140
        with patch.object(p.os, "close", side_effect=AssertionError("must not close")):
            with self.assertRaises(Refused): context.finish(before, after, mutation)
        context.cursor = 141; context.calls.append(object()); context.worker.pending_mutation = {"kind": "native"}
        with self.assertRaises(Refused): context.finish(before, after, mutation)

    def test_mutation_slot_requires_complete_admitted_live_before_sample(self):
        context = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule)
        context.verify_owner = lambda: None
        context.started = True; context.cursor = 70; context.steps = p.steps_for("C", registry())
        context.worker = types.SimpleNamespace(pending_mutation=None)
        context.before_admitted = False; context.samples = {}
        context.capture = lambda *a: self.fail("must not mutate before live protection admission")
        with self.assertRaises(Refused): context.invoke("observer")

    def test_many_completed_call_pipes_retire_but_unknown_pipes_stay_open(self):
        context = p.FixedSevenSchedule.__new__(p.FixedSevenSchedule); context.calls = []
        done = threading.Event(); done.set()
        exited = threading.Thread(target=lambda: None); exited.start(); exited.join(1)
        for _ in range(141):
            read_fd, write_fd = os.pipe()
            stdout, stderr = os.fdopen(read_fd, "rb"), os.fdopen(write_fd, "wb")
            call = types.SimpleNamespace(done=done, unknown=False, thread=exited, returncode=0,
                                         proc=types.SimpleNamespace(stdout=stdout, stderr=stderr))
            context.calls.append(call)
            try:
                context.retire_known_pipes(call)
                self.assertTrue(stdout.closed and stderr.closed)
            finally:
                stdout.close(); stderr.close()
        read_fd, write_fd = os.pipe()
        stdout, stderr = os.fdopen(read_fd, "rb"), os.fdopen(write_fd, "wb")
        call = types.SimpleNamespace(done=done, unknown=True, thread=exited, returncode=0,
                                     proc=types.SimpleNamespace(stdout=stdout, stderr=stderr))
        context.calls.append(call)
        try:
            with self.assertRaises(Refused): context.retire_known_pipes(call)
            self.assertFalse(stdout.closed or stderr.closed)
        finally:
            stdout.close(); stderr.close()

    def test_permission_reuse_preserves_flags_and_ambiguity_refuses(self):
        raw = b"User 0:\nandroid.permission.RECORD_AUDIO\n android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET ]\n"
        value = p.permission_projection(raw, "RECORD_AUDIO")
        self.assertIn("USER_SET", value["permissionLine"])
        with self.assertRaises(Refused): p.permission_projection(raw + raw, "RECORD_AUDIO")


class ConstructionOwnerSealVectors(unittest.TestCase):
    def assert_drift_refused(self, mutate):
        for phase in ("B", "C"):
            for final in (False, True):
                with self.subTest(phase=phase, final=final):
                    with offline_sealed_schedule(phase, final=final) as (q, attempts, writes):
                        mutate(q)
                        with self.assertRaises(Refused):
                            if final: q.finish(q.samples["before"], q.samples["after"], q.mutation)
                            else: q.invoke()
                        self.assertEqual(attempts, [])
                        self.assertEqual(writes, [])
                        self.assertTrue(q.worker.lock_acquired)
                        self.assertFalse(q.worker.uncertain)

    def test_binding_and_typed_nested_binding_drift_never_calls_or_finalizes(self):
        for mutate in (lambda q: q.worker.binding.__setitem__("lease", "relabeled-lease"),
                       lambda q: q.worker.binding.__setitem__("invocationId", "relabeled-invocation"),
                       lambda q: q.worker.binding["tuple"].__setitem__("sdk", 33.0),
                       lambda q: q.worker.binding["tuple"]["apkSha256"].__setitem__("app", "b" * 64),
                       lambda q: setattr(q.worker, "binding", copy.deepcopy(q.worker.binding))):
            self.assert_drift_refused(mutate)

    def test_target_and_source_constants_drift_never_retargets_b_to_api35(self):
        for mutate in (lambda q: setattr(q.support, "SERIAL", "emulator-335"),
                       lambda q: setattr(q.support, "SDK", 33.0),
                       lambda q: q.support.PACKAGES.__setitem__("app", "foreign.package"),
                       lambda q: q.support.OLD_UID.__setitem__("app", 10009),
                       lambda q: setattr(q.device, "sdk", 33.0),
                       lambda q: setattr(q.support, "P", q.support.P / "alternate")):
            self.assert_drift_refused(mutate)

    def test_runtime_class_helpers_and_method_code_drift_never_calls_or_finalizes(self):
        def changed(self, *args, **kwargs): pass
        for mutate in (lambda q: setattr(q.worker, "runtime", types.SimpleNamespace(Call=q.worker.runtime.Call)),
                       lambda q: setattr(q.worker.runtime, "Call", type("ForeignCall", (), {})),
                       lambda q: setattr(q.worker.runtime.Call.__init__, "__code__", changed.__code__),
                       lambda q: setattr(q.support, "read_regular", lambda *args: b"forged"),
                       lambda q: setattr(q.worker, "device", types.SimpleNamespace()),
                       lambda q: setattr(q.worker, "has_unsettled", lambda: False),
                       lambda q: setattr(q, "verify_owner", lambda: None)):
            self.assert_drift_refused(mutate)
        # Same code with foreign globals is still a different helper identity.
        self.assert_drift_refused(lambda q: setattr(q.support, "read_regular", types.FunctionType(
            q.support.read_regular.__code__, {"Path": Path})))

    def test_source_private_folder_once_and_ledger_identity_drift_refuse(self):
        for mutate in (lambda q: Path(q.support.__file__).write_bytes(b"changed inert source\n"),
                       lambda q: setattr(q.worker, "folder", Path(str(q.worker.folder))),
                       lambda q: setattr(q.worker, "once", Path(str(q.worker.once))),
                       lambda q: setattr(q.worker, "lock_fd", q.worker.lock_fd + 100000),
                       lambda q: setattr(q.worker, "calls", list(q.worker.calls)),
                       lambda q: setattr(q.worker, "streams", dict(q.worker.streams)),
                       lambda q: q.worker.once.chmod(0o644)):
            self.assert_drift_refused(mutate)

    def test_derived_method_and_runtime_standard_global_replacement_refuse(self):
        for final in (False, True):
            with offline_sealed_schedule(final=final) as (q, attempts, writes):
                with patch.object(p.FixedSevenSchedule, "expected_args", lambda *args: []):
                    with self.assertRaises(Refused):
                        if final: q.finish(q.samples["before"], q.samples["after"], q.mutation)
                        else: q.invoke()
                self.assertEqual(attempts, []); self.assertEqual(writes, [])
            with offline_sealed_schedule(final=final) as (q, attempts, writes):
                with patch.object(p.subprocess, "Popen", lambda *args, **kwargs: None):
                    with self.assertRaises(Refused):
                        if final: q.finish(q.samples["before"], q.samples["after"], q.mutation)
                        else: q.invoke()
                self.assertEqual(attempts, []); self.assertEqual(writes, [])

    def test_changed_global_refusal_helper_cannot_approve_itself(self):
        for final in (False, True):
            with offline_sealed_schedule(final=final) as (q, attempts, writes):
                with patch.object(p, "need", lambda *args: None):
                    with self.assertRaises(Refused):
                        if final: q.finish(q.samples["before"], q.samples["after"], q.mutation)
                        else: q.invoke()
                self.assertEqual(attempts, []); self.assertEqual(writes, [])
            with offline_sealed_schedule(final=final) as (q, attempts, writes):
                original = p.need.__code__
                try:
                    p.need.__code__ = (lambda *args: None).__code__
                    with self.assertRaises(Refused):
                        if final: q.finish(q.samples["before"], q.samples["after"], q.mutation)
                        else: q.invoke()
                finally:
                    p.need.__code__ = original
                self.assertEqual(attempts, []); self.assertEqual(writes, [])

    def test_replaced_global_schedule_or_seal_verify_cannot_bypass_old_fixed_entries(self):
        original_class = p.FixedSevenSchedule
        class NoopSchedule:
            @staticmethod
            def verify_owner(*args): pass
        for entry in ("invoke", "capture", "finish"):
            for replacement in ("schedule-global", "seal-verify", "seal-code"):
                for drift in ("binding", "serial"):
                    with self.subTest(entry=entry, replacement=replacement, drift=drift):
                        with offline_sealed_schedule(final=entry == "finish") as (q, attempts, writes):
                            # Capture the actual old entry before profile/global replacement.
                            execute = getattr(q, entry)
                            args = ()
                            if entry == "capture":
                                step = q.steps[0]; args = (step, q.expected_args(step)); q.cursor = 1
                            elif entry == "finish":
                                args = (q.samples["before"], q.samples["after"], q.mutation)
                            if drift == "binding": q.worker.binding["lease"] = "relabeled"
                            else: q.support.SERIAL = "emulator-335"
                            if replacement == "schedule-global":
                                with patch.object(p, "FixedSevenSchedule", NoopSchedule):
                                    with self.assertRaises(Refused): execute(*args)
                            elif replacement == "seal-verify":
                                with patch.object(p._OwnerSeal, "verify", lambda *args: None):
                                    with self.assertRaises(Refused): execute(*args)
                            else:
                                function = q._owner_check[2]; code = function.__code__
                                try:
                                    function.__code__ = (lambda *args: None).__code__
                                    with self.assertRaises(Refused): execute(*args)
                                finally:
                                    function.__code__ = code
                            self.assertIs(type(q), original_class)
                            self.assertEqual(attempts, []); self.assertEqual(writes, [])
                            self.assertTrue(q.worker.lock_acquired)
                            self.assertFalse(q.worker.uncertain)

    def test_direct_capture_guard_precedes_constructor_too(self):
        with offline_sealed_schedule() as (q, attempts, writes):
            step = q.steps[0]; args = q.expected_args(step); q.cursor = 1
            q.support.SERIAL = "emulator-335"
            with self.assertRaises(Refused): q.capture(step, args)
            self.assertEqual(attempts, []); self.assertEqual(writes, [])


if __name__ == "__main__":
    unittest.main()
