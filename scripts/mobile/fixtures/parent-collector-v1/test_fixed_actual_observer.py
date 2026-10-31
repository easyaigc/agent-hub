"""Local source/ownership refusal vectors; no real worker admission or Native."""
import fcntl
import threading
import time
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

import fixed_actual_observer as a
from fixed_parent_collector import Refused


class ActualBoundaryRefusals(unittest.TestCase):
    def test_dto_and_null_admission_cannot_construct_actual_context(self):
        with self.assertRaises(Refused):
            a.FixedActualObserver(types.SimpleNamespace(), types.SimpleNamespace())
        # An object with copied JSON fields has no actual original worker type.
        module = types.ModuleType("offline")
        class Original:
            pass
        module._Worker = Original
        with self.assertRaises(Refused):
            a.FixedActualObserver(module, types.SimpleNamespace(binding={k: None for k in a.BINDING_KEYS}))
        worker = Original()
        worker.binding = {k: None for k in a.BINDING_KEYS}
        worker.device = worker.runtime = worker.folder = worker.lock_fd = None
        worker.calls = []
        with patch.object(a.subprocess, "Popen", side_effect=AssertionError("no subprocess permitted")), \
             patch.object(a, "pinned_file", side_effect=AssertionError("unbound must refuse before source/SDK reads")):
            with self.assertRaises(Refused):
                a.FixedActualObserver(module, worker)

    def test_done_before_observer_return_is_locally_joined(self):
        done, leave = threading.Event(), threading.Event()
        thread = threading.Thread(target=lambda: (done.set(), leave.wait()))
        thread.start(); done.wait(1)
        call = types.SimpleNamespace(thread=thread, done=done, unknown=False)
        release = threading.Timer(0.02, leave.set); release.start()
        try:
            a.join_original_observer(call, {"startedAtMs": int(time.time() * 1000), "timeoutSeconds": 45})
            self.assertFalse(thread.is_alive())
        finally:
            leave.set(); thread.join(1); release.join(1)

    def test_live_observer_after_original_deadline_refuses_without_kill(self):
        done, leave = threading.Event(), threading.Event()
        thread = threading.Thread(target=lambda: (done.set(), leave.wait()))
        thread.start(); done.wait(1)
        call = types.SimpleNamespace(thread=thread, done=done, unknown=False)
        try:
            with self.assertRaises(Refused):
                a.join_original_observer(call, {"startedAtMs": int(time.time() * 1000) - 46000, "timeoutSeconds": 45})
            self.assertTrue(thread.is_alive())
        finally:
            leave.set(); thread.join(1)

    def test_actual_kernel_fence_probe_does_not_release_original(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "root-fence"
            fd = os.open(p, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                a.held_fence(fd, p)
                other = os.open(p, os.O_RDONLY)
                try:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                finally:
                    os.close(other)
            finally:
                os.close(fd)

    def test_unlocked_fence_refuses_without_repairing_original(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "root-fence"
            fd = os.open(p, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with self.assertRaises(Refused):
                    a.held_fence(fd, p)
                # A second open still succeeds: the helper did not acquire or
                # retain the original FD's missing ownership as a silent repair.
                other = os.open(p, os.O_RDONLY)
                try:
                    fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                finally:
                    os.close(other)
            finally:
                os.close(fd)

    def test_replaced_or_symlink_fence_is_not_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); p = root / "root-fence"; old = root / "saved-original"
            fd = os.open(p, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                p.rename(old)
                p.write_bytes(b""); p.chmod(0o600)
                with self.assertRaises(Refused):
                    a.held_fence(fd, p)
                p.unlink(); p.symlink_to(old)
                with self.assertRaises(OSError):
                    a.held_fence(fd, p)
            finally:
                os.close(fd)

    def test_original_compilation_does_not_inherit_attestor_future_flags(self):
        raw = "def fixed():\n return 1\n"
        expected = compile(raw, "offline-original", "exec", dont_inherit=True, optimize=0)
        observed = a.compile_original(raw, "offline-original")
        namespace = {}; exec(observed, namespace)
        a.require_code(namespace["fixed"], a.code_at(expected, "fixed"))
        self.assertEqual(a.code_at(observed, "fixed").co_flags, a.code_at(expected, "fixed").co_flags)

    def test_same_named_function_with_changed_code_is_rejected(self):
        source = compile("def fixed():\n return 1\n", "offline-source", "exec")
        namespace = {}; exec(source, namespace)
        a.require_code(namespace["fixed"], a.code_at(source, "fixed"))
        independent = {}; exec(compile("def fixed():\n return 1\n", "offline-source", "exec"), independent)
        a.require_code(independent["fixed"], a.code_at(source, "fixed"))
        changed = {}; exec(compile("def fixed():\n return 2\n", "offline-source", "exec"), changed)
        with self.assertRaises(Refused):
            a.require_code(changed["fixed"], a.code_at(source, "fixed"))


    def test_live_module_code_matches_separate_source_compilations(self):
        # Actual public functions, not an actor/Worker or a copied code whitelist.
        path = Path(a.__file__)
        raw = path.read_bytes()
        first = a.compile_original(raw, str(path))
        second = a.compile_original(raw, str(path))
        for name in ("_code_identity", "require_code", "held_fence", "join_original_observer"):
            with self.subTest(function=name):
                function = getattr(a, name)
                # Field access must not change the meaning of the source check.
                for field in dir(function.__code__):
                    if field.startswith("co_") and not callable(getattr(function.__code__, field)):
                        getattr(function.__code__, field)
                a.require_code(function, a.code_at(first, name))
                a.require_code(function, a.code_at(second, name))

    def test_real_code_field_drift_including_nested_body_is_rejected(self):
        raw = b"def fixed(value):\n    def inner():\n        return 'nested'\n    return value, 'outer', inner()\n"
        compiled = a.compile_original(raw, "offline-code-fields")
        namespace = {}
        exec(compiled, namespace)
        expected = a.code_at(compiled, "fixed")
        original = namespace["fixed"]
        a.require_code(original, expected)
        child = a.code_at(expected, "inner")
        changed_child = child.replace(co_consts=tuple("changed" if item == "nested" else item for item in child.co_consts))
        changes = {
            "filename": {"co_filename": "other-source"},
            "flags": {"co_flags": expected.co_flags ^ 0x1000000},
            "constant": {"co_consts": tuple("changed" if item == "outer" else item for item in expected.co_consts)},
            "nested": {"co_consts": tuple(changed_child if item is child else item for item in expected.co_consts)},
            "arguments": {"co_argcount": 0},
            "firstlineno": {"co_firstlineno": expected.co_firstlineno + 1},
        }
        line_field = "co_linetable" if hasattr(expected, "co_linetable") else "co_lnotab"
        changes["line-table"] = {line_field: b"" if getattr(expected, line_field) else b"\x00\x01"}
        for label, replacement in changes.items():
            with self.subTest(field=label):
                changed = types.FunctionType(expected.replace(**replacement), namespace)
                with self.assertRaises(Refused):
                    a.require_code(changed, expected)
        # Every negative above leaves the actual public positive boundary usable.
        a.require_code(original, expected)

    def test_code_identity_keeps_constant_types_and_rejects_unknown_values(self):
        self.assertNotEqual(a._code_identity(True), a._code_identity(1))
        self.assertNotEqual(a._code_identity(("same",)), a._code_identity(frozenset({"same"})))
        with self.assertRaises(ValueError):
            a._code_identity(object())
        expected = a.code_at(a.compile_original(b"def fixed():\n    return 1\n", "offline-code-types"), "fixed")
        with self.assertRaises(Refused):
            a.require_code(object(), expected)

    def test_unexpected_driver_error_retains_same_real_context_and_holds(self):
        # Exercise only the error route before any original Call construction.
        # The fake hold raises a local sentinel instead of starting an infinite
        # Root hold; this is not evidence that actual Root supervision works.
        class Held(Exception):
            pass
        failures = []
        worker = types.SimpleNamespace(pending_mutation=None, has_unsettled=lambda: False)
        def hold(failure, classification):
            failures.append((failure, classification)); raise Held()
        worker.hold = hold
        worker.binding = {"invocationId": "00000000-0000-0000-0000-000000000001",
                          "observationRun": "00000000-0000-0000-0000-000000000002"}
        context = a.FixedActualObserver.__new__(a.FixedActualObserver)
        context.worker = worker; context.started = False; context.stage_sha = "a" * 64
        context.claim_sha = "b" * 64; context.expected_argv = []; context._owner = lambda: None
        context.invocation_path = Path("offline-not-created")
        def fail(*args):
            raise OSError("injected immutable intent persistence failure")
        context.support = types.SimpleNamespace(immutable=fail)
        before = list(a._RETAINED)
        try:
            with self.assertRaises(Held):
                context.run_once()
            self.assertIs(a._RETAINED[-1], context)
            self.assertIs(context.worker, worker)
            self.assertIs(failures[0][0], context.failure[0])
            self.assertTrue(context.started)
            self.assertFalse(context.invocation_path.exists())
            with self.assertRaises(Refused):
                context.run_once()
        finally:
            a._RETAINED[:] = before


if __name__ == "__main__":
    unittest.main()
