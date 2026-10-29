import argparse
import copy
import json
import sys
import tempfile
import unittest
import uuid
import zipfile
from pathlib import Path
from unittest.mock import patch
from cold_contract import *
from cold_controller import execute, preflight, validate_lease

ROOT = Path(__file__).resolve().parents[7]


def example():
    lock = loads((Path(__file__).parent / "fixture-source-lock.json").read_bytes())
    contract = {"version": 1, "fixture": KIND, **{k: lock[k] for k in ["sourceFixtureSha256", "sourceGraph", "sourceGraphSha256"]}}
    plan = {"caseId": str(uuid.uuid4()), "target": {"serial": "owned-test", "kind": "physical", "avd": None}, "candidate": {"build": {"sourceSha256": "a" * 64, "appSha256": "b" * 64, "runnerSha256": "c" * 64}}, "fixtureContract": contract}
    config = {**contract, "instanceId": str(uuid.uuid4()), "sidecarPid": 777, "startedAt": "2026-10-29T00:00:00Z", "authOrigin": "http://127.0.0.1:30001", "controlOrigin": "http://127.0.0.1:30002"}
    return plan, config


class FakeDriver:
    """No subprocess, ADB, HTTP or Node listener exists in this test driver."""
    def __init__(self, plan, config):
        self.plan, self.config = plan, config
        self.calls = []
        self.defaults = {"mobile-v1/original.bin": "d" * 64}
        self.stats = {"caseId": plan["caseId"], "held": 1, "released": False, "errors": [], "realDesktopRequests": 0, "realModelInputs": 0,
                      "requests": [{"method": "POST", "path": "/v2/remote-access/pairing/claims"}, {"method": "GET", "path": "/v2/remote-access/pairing/claims/owned"}],
                      "reads": [{"method": "runtime/context"}, {"method": "project/list"}, {"method": "thread/list"}]}
        self.writer = {"caseId": plan["caseId"], "namespace": "p8-positive-commit-" + plan["caseId"], "keyAlias": "p8-positive-commit-" + plan["caseId"], "pid": 111, "processStartElapsed": 9000,
                       "configSha256": sha(canonical(config)), "sourceGraphSha256": config["sourceGraphSha256"], "build": plan["candidate"]["build"], "syntheticOnly": True,
                       "phase": "writer-held-final-read", "readyElapsed": 10000, "candidateRef": "agent-easywork-" + "a" * 64 + "-" + str(uuid.uuid4()), "grantRef": "synthetic-grant", "claimKey": "synthetic-claim", "journalKey": "synthetic-journal", "originalTargetKey": "synthetic-target",
                       "defaults": self.defaults, "claimRequestsSha256": sha(android_json(self.stats["requests"])), "namedReadsSha256": sha(android_json(self.stats["reads"])), "originalOperationUnchanged": True, "candidateUnreferenced": True, "savedCallbacks": 0, "mustForceStopWithinSeconds": 18}
        for name in ["candidateSha256", "metadataSha256", "identitySha256", "grantSha256", "claimSha256", "journalSha256", "originalDraftSha256"]:
            self.writer[name] = sha(name.encode())
        self.writer_identity = {"pid": 111, "uid": 12345, "startTicks": 900, "startElapsedMs": 9000, "bootId": "same-boot"}
        self.reader_identity = {"pid": 222, "uid": 12345, "startTicks": 1300, "startElapsedMs": 13000, "bootId": "same-boot"}
        self.stop = {"pid": 111, "pidGone": True, "identityUnchanged": True, "stoppedElapsedMs": 12000, "writerJUnitPassed": False}
        self.reader_junit = True
        self.reader_changes = {}
        self.final_change = None
        self.cleanup_complete = True
        self.stopped_error = None
        self.raw = None

    def prepare(self, plan):
        self.calls.append("prepare")
        return {"config": self.config, "defaults": self.defaults, "resources": {"ownReverse": [30001, 30002]}}

    def launch_writer(self, config, plan):
        self.calls.append("writer-once")

    def await_writer_ready(self, plan, timeout):
        self.calls.append("read-own-exact-writer-ready")
        self.raw = canonical(self.writer)
        return self.raw

    def held_snapshot(self, writer):
        self.calls.append("held-snapshot")
        return {"identity": self.writer_identity, "cipherHashes": {p: sha(p.encode()) for p in lineage_paths(writer)}, "stats": self.stats}

    def interrupt_owned_writer(self, writer, held):
        self.calls.append("stop-own-writer")
        return self.stop

    def verify_stopped_state(self, writer, raw, held, defaults):
        self.calls.append("verify-stop-before-reader")
        if self.stopped_error:
            raise ValueError(self.stopped_error)

    def run_reader(self, config, plan, writer_raw):
        self.calls.append("reader-once")
        reader = {k: copy.deepcopy(self.writer[k]) for k in COMMON}
        reader.update({"pid": self.reader_identity["pid"], "processStartElapsed": self.reader_identity["startElapsedMs"], "phase": "reader-complete", "writerPid": 111,
                       "writerProofSha256": sha(canonical(loads(writer_raw))), "candidateStillUnreferenced": True, "candidateGrantClaimJournalMetadataUnchanged": True,
                       "defaultEncryptedFilesUnchanged": True, "newClaimOrProtocolReadOrSubmit": False, "realProcessReopen": True})
        reader.update(self.reader_changes)
        return {"junitPassed": self.reader_junit, "nativeMethods": 1 if self.reader_junit else 0, "identity": self.reader_identity, "proof": canonical(reader)}

    def verify_reader_state(self, writer, raw, held, defaults):
        self.calls.append("verify-original-files-and-closed-case")
        result = copy.deepcopy(self.stats)
        if self.final_change:
            self.final_change(result)
        return {"closedCase": result}

    def seal_failure(self):
        self.calls.append("seal-no-reset-no-retry")
        return {"originalCase": copy.deepcopy(self.stats), "retryAllowed": False}

    def cleanup(self):
        self.calls.append("own-sidecar-and-reverse-only")
        return {"complete": self.cleanup_complete, "onlyOwnedResources": True, "dataDeleted": False}


class ControllerTests(unittest.TestCase):
    def run_case(self, change=None):
        plan, config = example()
        driver = FakeDriver(plan, config)
        if change:
            change(driver)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "execution"
            result = execute(plan, driver, output)
            evidence = {p.name: p.read_bytes() for p in output.iterdir()}
        return result, driver, evidence

    def assert_sealed(self, change, reader_started=False):
        result, driver, evidence = self.run_case(change)
        self.assertEqual("failed-sealed", result["status"])
        self.assertEqual(reader_started, "reader-once" in driver.calls)
        self.assertIn("failed-sealed.json", evidence)
        self.assertNotIn("passed.json", evidence)
        self.assertEqual(driver.calls[-1], "own-sidecar-and-reverse-only")
        self.assertNotIn("begin-case", driver.calls)
        self.assertNotIn("reset-case", driver.calls)
        return driver

    def test_real_cold_order_requires_external_stop_before_one_reader(self):
        result, driver, evidence = self.run_case()
        self.assertEqual("cold-boundary-passed", result["status"])
        self.assertFalse(result["a5Complete"])
        self.assertLess(driver.calls.index("stop-own-writer"), driver.calls.index("reader-once"))
        self.assertEqual(1, driver.calls.count("writer-once"))
        self.assertEqual(1, driver.calls.count("reader-once"))
        self.assertEqual(driver.raw, evidence["writer-ready.json"])
        self.assertIn(b"interrupted-checkpoint", evidence["writer-interrupted.json"])

    def test_same_pid_reader_is_refused(self):
        self.assert_sealed(lambda d: d.reader_identity.update(pid=111), True)

    def test_reader_before_writer_checkpoint_time_is_refused(self):
        self.assert_sealed(lambda d: d.reader_identity.update(startElapsedMs=9999), True)

    def test_device_reboot_is_not_process_cold_acceptance(self):
        self.assert_sealed(lambda d: d.reader_identity.update(bootId="another-boot"), True)

    def test_pid_reuse_generation_is_refused(self):
        self.assert_sealed(lambda d: d.reader_identity.update(startTicks=900), True)

    def test_writer_native_start_must_match_external_generation(self):
        self.assert_sealed(lambda d: d.writer.update(processStartElapsed=1000))

    def test_reader_same_package_uid_is_required(self):
        self.assert_sealed(lambda d: d.reader_identity.update(uid=54321), True)

    def test_wrong_writer_pid_stops_before_any_reader(self):
        self.assert_sealed(lambda d: d.writer_identity.update(pid=777))

    def test_writer_junit_success_cannot_count_as_checkpoint(self):
        self.assert_sealed(lambda d: d.stop.update(writerJUnitPassed=True))

    def test_writer_stop_at_or_over_18_seconds_is_failure(self):
        for elapsed in [27980, 28000, 30000]:
            with self.subTest(elapsed=elapsed):
                self.assert_sealed(lambda d: d.stop.update(stoppedElapsedMs=elapsed))

    def test_no_reader_when_writer_is_not_actually_gone(self):
        self.assert_sealed(lambda d: d.stop.update(pidGone=False))

    def test_foreign_case_proof_is_sealed_not_retried(self):
        self.assert_sealed(lambda d: d.writer.update(caseId=str(uuid.uuid4())))

    def test_config_or_source_graph_change_refused(self):
        for field in ["configSha256", "sourceGraphSha256"]:
            self.assert_sealed(lambda d, f=field: d.writer.update({f: "0" * 64}))

    def test_missing_or_new_default_file_refused_before_reader(self):
        self.assert_sealed(lambda d: d.writer.update(defaults={}))

    def test_missing_exact_ciphertext_file_refused(self):
        class Missing(FakeDriver):
            def held_snapshot(self, writer):
                r = super().held_snapshot(writer)
                r["cipherHashes"].pop(next(iter(r["cipherHashes"])))
                return r
        plan, config = example()
        with tempfile.TemporaryDirectory() as tmp:
            d = Missing(plan, config)
            self.assertEqual("failed-sealed", execute(plan, d, Path(tmp) / "out")["status"])
            self.assertNotIn("reader-once", d.calls)

    def test_post_stop_changed_originals_do_not_start_reader(self):
        self.assert_sealed(lambda d: setattr(d, "stopped_error", "cipher/default/ready bytes changed"))

    def test_reader_failure_never_resubmits_or_ends_original_case(self):
        driver = self.assert_sealed(lambda d: setattr(d, "reader_junit", False), True)
        self.assertEqual(1, driver.calls.count("reader-once"))

    def test_reader_cannot_create_claim_or_read_protocol(self):
        self.assert_sealed(lambda d: d.reader_changes.update(newClaimOrProtocolReadOrSubmit=True), True)

    def test_reader_original_lineage_flags_are_all_required(self):
        for field in ["candidateStillUnreferenced", "candidateGrantClaimJournalMetadataUnchanged", "defaultEncryptedFilesUnchanged", "realProcessReopen"]:
            self.assert_sealed(lambda d, f=field: d.reader_changes.update({f: False}), True)

    def test_reader_proof_cannot_replace_writer_hash(self):
        self.assert_sealed(lambda d: d.reader_changes.update(writerProofSha256="0" * 64), True)

    def test_new_claim_after_reader_is_not_accepted(self):
        self.assert_sealed(lambda d: setattr(d, "final_change", lambda v: v["requests"].append({"method": "POST", "path": "/claims"})), True)

    def test_new_named_read_after_reader_is_not_accepted(self):
        self.assert_sealed(lambda d: setattr(d, "final_change", lambda v: v["reads"].append({"method": "thread/list"})), True)

    def test_cleanup_incomplete_is_not_overall_pass(self):
        result, _, evidence = self.run_case(lambda d: setattr(d, "cleanup_complete", False))
        self.assertEqual("native-pass-cleanup-incomplete", result["status"])
        self.assertTrue(result["resourceCleanupIncomplete"])
        self.assertIn("resource-cleanup.json", evidence)

    def test_second_attempt_cannot_reenter_same_reserved_case(self):
        plan, config = example()
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            execute(plan, FakeDriver(plan, config), output)
            again = FakeDriver(plan, config)
            with self.assertRaises(FileExistsError):
                execute(plan, again, output)
            self.assertEqual([], again.calls)

    def test_android_stats_hash_is_not_sorted_unescaped_canonical(self):
        data = [{"z/path": "value/path", "a": None}]
        self.assertNotEqual(sha(android_json(data)), sha(canonical(data)))
        self.assertEqual(b'[{"z\\/path":"value\\/path","a":null}]', android_json(data))

    def test_duplicate_json_members_are_never_normalized(self):
        with self.assertRaises(ValueError):
            loads('{"pid":1,"pid":2}')


class StaticPreflightTests(unittest.TestCase):
    def setup_inputs(self, temp):
        root = Path(temp)
        frozen = root / "frozen"
        values = {COLD_SOURCE: (ROOT / COLD_SOURCE).read_bytes(), ASSET: (ROOT / ASSET).read_bytes()}
        digestor = hashlib.sha256()
        files = {}
        for name in sorted(values):
            target = frozen / name
            target.parent.mkdir(parents=True)
            target.write_bytes(values[name])
            files[name] = sha(values[name])
            digestor.update(name.encode() + b"\0" + values[name] + b"\0")
        app, runner = root / "app.apk", root / "test.apk"
        with zipfile.ZipFile(app, "w") as z:
            z.writestr("AndroidManifest.xml", "synthetic-only")
        with zipfile.ZipFile(runner, "w") as z:
            z.writestr("classes.dex", (WRITER + READER).encode())
        candidate = {"sourceSha256": digestor.hexdigest(), "files": files, "appSha256": file_sha(app), "testSha256": file_sha(runner)}
        cp = root / "candidate.json"
        cp.write_bytes(canonical(candidate))
        targets = root / "targets.json"
        targets.write_bytes(canonical({"version": 1, "taskId": "synthetic-controller-selfcheck", "targets": {"owned-target": {"kind": "physical", "avd": None}}}))
        tools = {}
        for name in ["aapt", "apksigner", "node"]:
            tools[name] = str(root / name)
            Path(tools[name]).write_bytes(b"offline-tool-placeholder")
        args = argparse.Namespace(candidate=str(cp), candidate_sha256=file_sha(cp), frozen_source=str(frozen), app=str(app), runner=str(runner), fixture_root=str(ROOT), targets=str(targets), target="owned-target", output=str(root / "preflight"), **tools)
        def metadata(path, *_):
            return {"package": RUNNER if Path(path).resolve() == runner.resolve() else PACKAGE, "debuggable": True, "targetPackage": PACKAGE if Path(path).resolve() == runner.resolve() else None, "instrumentationName": "androidx.test.runner.AndroidJUnitRunner" if Path(path).resolve() == runner.resolve() else None, "signerSha256": "f" * 64}
        return args, metadata

    def test_static_preflight_allocates_only_local_uuid_without_device_import(self):
        with tempfile.TemporaryDirectory() as temp:
            args, metadata = self.setup_inputs(temp)
            with patch.dict(sys.modules, {"cold_device": None}):
                result = preflight(args, metadata)
            self.assertEqual(0, result["deviceCommands"])
            self.assertFalse(result["realCaseStarted"])
            plan = loads((Path(args.output) / "plan.json").read_bytes())
            self.assertEqual(result["caseId"], case_id(plan["caseId"]))
            self.assertFalse(plan["deviceVerified"])
            self.assertFalse((Path(args.output) / "execution").exists())

    def test_candidate_hash_mutation_refused_without_output_or_device(self):
        with tempfile.TemporaryDirectory() as temp:
            args, metadata = self.setup_inputs(temp)
            Path(args.app).write_bytes(b"changed")
            with self.assertRaises(ValueError):
                preflight(args, metadata)
            self.assertFalse(Path(args.output).exists())

    def test_wrong_runner_target_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            args, metadata = self.setup_inputs(temp)
            def changed(*a):
                facts = metadata(*a)
                if facts["package"] == RUNNER:
                    facts["targetPackage"] = "other.package"
                return facts
            with self.assertRaises(ValueError):
                preflight(args, changed)

    def test_changed_source_tree_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            args, metadata = self.setup_inputs(temp)
            (Path(args.frozen_source) / COLD_SOURCE).write_bytes(b"changed")
            with self.assertRaises(ValueError):
                preflight(args, metadata)

    def test_wrong_signer_and_wrong_embedded_package_are_refused(self):
        for field, value in [("signerSha256", "e" * 64), ("package", "other.package")]:
            with tempfile.TemporaryDirectory() as temp:
                args, metadata = self.setup_inputs(temp)
                def changed(*a):
                    facts = metadata(*a)
                    if facts["package"] == RUNNER:
                        facts[field] = value
                    return facts
                with self.assertRaises(ValueError):
                    preflight(args, changed)

    def test_target_outside_explicit_allowlist_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            args, metadata = self.setup_inputs(temp)
            args.target = "other-target"
            with self.assertRaises(ValueError):
                preflight(args, metadata)

    def test_source_traversal_and_symlinks_are_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "inside").mkdir()
            (root / "outside").write_text("outside")
            (root / "inside/link").symlink_to(root / "outside")
            for relative in ["../outside", "link"]:
                with self.assertRaises(ValueError):
                    contained(root / "inside", relative)


class AdapterConstructionTests(unittest.TestCase):
    def test_instrument_command_is_exact_one_role_and_quotes_original_json(self):
        import shlex
        import cold_device
        plan, config = example()
        plan["roles"] = {"writer": CLASS + "#" + WRITER, "reader": CLASS + "#" + READER}
        commands = []
        class CaptureOnly:
            def __init__(self, argv, log):
                commands.append(argv)
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            proof = '{"text":"quote \' ` $(not_executed) 中文"}'.encode()
            with patch.object(cold_device, "Capture", CaptureOnly):
                device._launch("reader", config, plan, proof)
        self.assertEqual(["/not-executed/adb", "-s", "owned-test", "shell", "-T"], commands[0][:-1])
        tokens = shlex.split(commands[0][-1])
        self.assertEqual(["am", "instrument", "-w", "-r"], tokens[:4])
        self.assertEqual(COMPONENT, tokens[-1])
        self.assertIn(CLASS + "#" + READER, tokens)
        index = tokens.index("p8CommitWriterProof")
        self.assertEqual(proof.decode(), tokens[index + 1])
        self.assertNotIn(WRITER, tokens)

    def test_only_exact_owned_reverse_is_removed_and_verified(self):
        import cold_device
        plan, _ = example()
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            device.added = ["tcp:30001", "tcp:30002"]
            rows = [["target", "tcp:30001", "tcp:30001"], ["target", "tcp:30002", "tcp:9999"], ["target", "tcp:40000", "tcp:40000"]]
            commands = []
            device._reverse = lambda: copy.deepcopy(rows)
            def call(tokens, **kwargs):
                commands.append(tokens)
                rows[:] = [r for r in rows if r[1] != tokens[-1]]
            device._call = call
            result = device.cleanup()
        self.assertEqual([["reverse", "--remove", "tcp:30001"]], commands)
        self.assertEqual([["target", "tcp:30002", "tcp:9999"], ["target", "tcp:40000", "tcp:40000"]], rows)
        self.assertTrue(result["complete"])
        self.assertFalse(result["dataDeleted"])
        self.assertFalse(result["readerForceStopped"])

    def test_successful_remove_receipt_is_not_enough_if_mapping_remains(self):
        import cold_device
        plan, _ = example()
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            device.added = ["tcp:30001"]
            device._reverse = lambda: [["target", "tcp:30001", "tcp:30001"]]
            device._call = lambda *a, **k: None
            result = device.cleanup()
        self.assertFalse(result["complete"])
        self.assertFalse(result["ownReverseRemovals"][0]["removed"])


class OemRuntimeTests(unittest.TestCase):
    @staticmethod
    def projection():
        # Actual saved headers; bodies below are synthetic, not a claimed full device capture.
        observed = loads((Path(__file__).parent / "oem-inventory-observed.json").read_bytes())
        return "\n".join(header + "\n  synthetic state body" for header in observed["recognizedHeaders"]) + "\n"

    def test_actual_oem_short_exit_zero_rejection_is_never_idle(self):
        from cold_device import instrumentation_active
        observed = loads((Path(__file__).parent / "oem-inventory-observed.json").read_bytes())
        self.assertEqual(0, observed["commandExit"])
        with self.assertRaises(ValueError):
            instrumentation_active(observed["shortCommandOutput"].encode(), 33)

    def test_observed_full_header_projection_accepts_idle_and_active(self):
        from cold_device import instrumentation_active
        full = self.projection()
        self.assertFalse(instrumentation_active(full.encode(), 33))
        self.assertFalse(instrumentation_active(full.replace("\n", "\r\n").encode(), 33))
        for marker in ["Active instrumentation:", "Active Instrumentation:"]:
            busy = full.replace("ACTIVITY MANAGER USERS", "  " + marker + "\n    Instrumentation #0: synthetic.foreign.runner\nACTIVITY MANAGER USERS")
            self.assertTrue(instrumentation_active(busy.encode(), 33))

    def test_full_dump_size_matches_observed_oem_without_unbounded_capture(self):
        from cold_device import instrumentation_active
        observed = loads((Path(__file__).parent / "oem-inventory-observed.json").read_bytes())
        full = self.projection()
        padding = "x" * (observed["fullBytes"] - len(full.encode()))
        sized = full.replace("  synthetic state body", "  synthetic state body" + padding, 1).encode()
        self.assertEqual(699491, len(sized))
        self.assertFalse(instrumentation_active(sized, 33))

    def test_empty_unknown_pm_list_and_partial_dump_are_not_idle(self):
        from cold_device import instrumentation_active
        full = self.projection()
        for raw in [b"", b"\n", b"unknown", b"instrumentation:synthetic.runner/androidx.test.runner.AndroidJUnitRunner (target=synthetic.app)", full.split("ACTIVITY MANAGER RUNNING PROCESSES")[0].encode(), b"ACTIVITY MANAGER INSTRUMENTATION (dumpsys activity instrumentation)\n"]:
            with self.subTest(raw=raw[:100]), self.assertRaises(ValueError):
                instrumentation_active(raw, 33)

    def test_required_full_sections_order_body_and_uniqueness_are_strict(self):
        from cold_device import instrumentation_active
        full = self.projection()
        headers = loads((Path(__file__).parent / "oem-inventory-observed.json").read_bytes())["recognizedHeaders"]
        for header in [h for h in headers if any("(" + "dumpsys activity " + c + ")" in h for c in ["settings", "activities", "processes", "users", "loopers"])]:
            for changed in [full.replace(header + "\n  synthetic state body\n", ""), full + header + "\n  synthetic state body\n"]:
                with self.subTest(header=header), self.assertRaises(ValueError):
                    instrumentation_active(changed.encode(), 33)
        for changed in [full.rsplit("  synthetic state body", 1)[0], full.replace("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n  synthetic state body", "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)"), "ACTIVITY MANAGER UNKNOWN (dumpsys activity unknown)\nstate\n" + full]:
            with self.assertRaises(ValueError):
                instrumentation_active(changed.encode(), 33)

    def test_error_timeout_bad_encoding_and_size_block_full_dump(self):
        from cold_device import instrumentation_active, INVENTORY_LIMIT
        for raw in [b"Permission Denial: not allowed\n", b"DUMP TIMEOUT\n", b"DUMP FAILED\n", b"\x00", b"\xff"]:
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                instrumentation_active(self.projection().encode() + raw, 33)
        with self.assertRaises(ValueError):
            instrumentation_active(b"x" * (INVENTORY_LIMIT + 1), 33)

    def test_adapter_uses_full_runtime_dump_with_explicit_bounds(self):
        import cold_device
        plan, _ = example()
        calls = []
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            def shell(*args, **kwargs):
                calls.append((args, kwargs))
                return self.projection().encode()
            device._shell = shell
            device._verify_runtime_profile = lambda deadline: {"api": 33}
            with patch.object(cold_device.time, "monotonic", return_value=100):
                self.assertFalse(device._instrumentation())
        self.assertEqual([(("dumpsys", "activity"), {"timeout": 60, "limit": cold_device.INVENTORY_LIMIT})], calls)

    def test_busy_inventory_never_starts_writer_or_reader(self):
        import cold_device
        plan, config = example()
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            device._instrumentation = lambda: True
            device._launch = lambda *a, **k: self.fail("No runner may be launched")
            with self.assertRaises(ValueError):
                device.launch_writer(config, plan)
            device._pid = lambda: None
            with self.assertRaises(ValueError):
                device.run_reader(config, plan, b"original")

    def test_failed_prepare_stays_sealed_and_cannot_reenter_original_case(self):
        class Unknown(FakeDriver):
            def prepare(self, plan):
                self.calls.append("prepare")
                raise ValueError("Unknown/partial full runtime inventory; no start")
        plan, config = example()
        with tempfile.TemporaryDirectory() as temp:
            device = Unknown(plan, config)
            output = Path(temp) / "execution"
            result = execute(plan, device, output)
            self.assertEqual("failed-sealed", result["status"])
            self.assertFalse(result["retryAllowed"])
            self.assertNotIn("writer-once", device.calls)
            second = FakeDriver(plan, config)
            with self.assertRaises(FileExistsError):
                execute(plan, second, output)
            self.assertEqual([], second.calls)


class RuntimeProfileTests(unittest.TestCase):
    @staticmethod
    def api37_projection():
        observed = loads((Path(__file__).parent / "api37-inventory-observed.json").read_bytes())
        # Actual header/body-length summary, with wholly synthetic body bytes.
        full = "".join(row["header"] + "\n" + "x" * (row["bodyBytes"] - 2) + "\n" for row in observed["headers"])
        # The summary leaves81 bytes outside header/body accounting; pad a synthetic prelude.
        gap = observed["fullBytes"] - len(full.encode())
        return ("p" * (gap - 1) + "\n" if gap else "") + full

    def test_observed_api37_component_alias_profile_and_active_runner(self):
        from cold_device import instrumentation_active
        full = self.api37_projection()
        self.assertEqual(501139, len(full.encode()))
        self.assertFalse(instrumentation_active(full.encode(), 37))
        self.assertFalse(instrumentation_active(full.replace("\n", "\r\n").encode(), 37))
        busy = full.replace("ACTIVITY MANAGER USERS", "  Active instrumentation:\n    synthetic.foreign.runner\nACTIVITY MANAGER USERS")
        self.assertTrue(instrumentation_active(busy.encode(), 37))

    def test_wrong_sdk_and_unknown_sdk_never_fall_back_to_a_known_tail(self):
        from cold_device import instrumentation_active
        for raw, sdk in [(OemRuntimeTests.projection().encode(), 37), (self.api37_projection().encode(), 33), (self.api37_projection().encode(), 26), (self.api37_projection().encode(), 38), (self.api37_projection().encode(), True)]:
            with self.subTest(sdk=sdk), self.assertRaises(ValueError):
                instrumentation_active(raw, sdk)

    def test_api37_missing_any_observed_section_or_extra_tail_is_rejected(self):
        from cold_device import instrumentation_active
        full = self.api37_projection()
        observed = loads((Path(__file__).parent / "api37-inventory-observed.json").read_bytes())
        for row in observed["headers"]:
            changed = full.replace(row["header"], "UNRECOGNIZED HEADER", 1)
            with self.subTest(header=row["header"]), self.assertRaises(ValueError):
                instrumentation_active(changed.encode(), 37)
        with self.assertRaises(ValueError):
            instrumentation_active((full + "ACTIVITY MANAGER LOOPERS (dumpsys activity loopers)\n synthetic\n").encode(), 37)

    def test_api37_empty_or_truncated_trailer_and_section_order_are_rejected(self):
        from cold_device import instrumentation_active
        full = self.api37_projection()
        observed = loads((Path(__file__).parent / "api37-inventory-observed.json").read_bytes())
        final_header = observed["headers"][-1]["header"]
        for body in ["", "\n", "\npartial\n", "\nx" * 12]:
            changed = full.split(final_header)[0] + final_header + body
            with self.subTest(body=len(body)), self.assertRaises(ValueError):
                instrumentation_active(changed.encode(), 37)
        h1, h2 = observed["headers"][11]["header"], observed["headers"][13]["header"]
        changed = full.replace(h1, "SWAP-HEADER").replace(h2, h1).replace("SWAP-HEADER", h2)
        with self.assertRaises(ValueError):
            instrumentation_active(changed.encode(), 37)

    def mock_device(self, api=37, lease_api=None, avd="synthetic-avd", actual_avd=None, boot="synthetic-boot", actual_boot=None):
        import cold_device
        import types
        plan, _ = example()
        plan["target"] = {"serial": "owned-test", "kind": "emulator" if api == 37 else "physical", "avd": avd if api == 37 else None}
        lease = {"adb": "/not-executed/adb", "api": api if lease_api is None else lease_api, "bootId": boot}
        device = cold_device.AdbDevice(plan, lease, "/not-created")
        calls = []
        def call(args, **kwargs):
            calls.append((tuple(args), kwargs))
            self.assertIn(tuple(args), [("get-state",), ("emu", "avd", "name")])
            return types.SimpleNamespace(stdout=b"device\n" if args == ["get-state"] else ((avd if actual_avd is None else actual_avd) + "\nOK\n").encode())
        def shell(*args, **kwargs):
            calls.append((args, kwargs))
            if args == ("cat", "/proc/sys/kernel/random/boot_id"):
                return (boot if actual_boot is None else actual_boot).encode()
            if args == ("getprop", "ro.build.version.sdk"):
                return str(api).encode()
            if args == ("am", "get-current-user"):
                return b"0"
            self.assertEqual(("dumpsys", "activity"), args)
            return (self.api37_projection() if api == 37 else OemRuntimeTests.projection()).encode()
        device._call, device._shell = call, shell
        return device, calls

    def test_adapter_binds_actual_lease_sdk_boot_and_avd_before_full_inventory(self):
        for sdk in [33, 37]:
            device, calls = self.mock_device(api=sdk)
            self.assertFalse(device._instrumentation())
            self.assertEqual(sdk, device.runtime_profile["api"])
            self.assertEqual(("dumpsys", "activity"), calls[-1][0])
            self.assertTrue(all(0 < args[1]["timeout"] <= 60 for args in calls))
            if sdk == 37:
                self.assertIn(("emu", "avd", "name"), [args[0] for args in calls[:-1]])

    def test_wrong_actual_sdk_boot_or_avd_blocks_before_inventory_or_runner(self):
        for kwargs in [{"lease_api": 33}, {"actual_avd": "foreign-avd"}, {"actual_boot": "foreign-boot"}]:
            device, calls = self.mock_device(**kwargs)
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                device._instrumentation()
            self.assertNotIn(("dumpsys", "activity"), [args[0] for args in calls])
            self.assertIsNone(device.runtime_profile)

    def test_runtime_verification_timeout_cannot_start_full_inventory(self):
        import cold_device
        device, _ = self.mock_device()
        device._verify_runtime_profile = lambda deadline: {"api": 37}
        device._shell = lambda *a, **k: self.fail("No full dump after exhausted60s budget")
        with patch.object(cold_device.time, "monotonic", side_effect=[100, 161]):
            with self.assertRaises(ValueError):
                device._instrumentation()

    def test_runtime_parse_finishing_after60s_is_not_idle(self):
        import cold_device
        device, _ = self.mock_device()
        device._verify_runtime_profile = lambda deadline: {"api": 37}
        full = self.api37_projection().encode()
        device._shell = lambda *a, **k: full
        with patch.object(cold_device.time, "monotonic", side_effect=[100, 101, 161]):
            with self.assertRaises(ValueError):
                device._instrumentation()


class OemDefaultHashTests(unittest.TestCase):
    def test_whitespace_compatibility_keeps_exact_hash_and_path(self):
        from cold_device import parse_hashes
        path = "no_backup/mobile-v1/original.bin.new"
        for separator in [" ", "  ", "\t", " \t"]:
            for newline in ["\n", "\r\n"]:
                self.assertEqual({path: "a" * 64}, parse_hashes(("a" * 64 + separator + path + newline).encode(), [path]))

    def test_hash_errors_missing_duplicate_foreign_and_changed_names_rejected(self):
        from cold_device import parse_hashes
        path = "no_backup/mobile-v1/original.bin"
        good = "a" * 64 + " " + path + "\n"
        for raw in ["", "sha256sum: Permission denied\n", good + good, good.replace(path, path + ".new"), good.replace("a" * 64, "a" * 63), good + "b" * 64 + " no_backup/foreign/file\n", good.replace(path, "*" + path), good.rstrip() + " \n"]:
            with self.subTest(raw=raw[:100]), self.assertRaises(ValueError):
                parse_hashes(raw.encode(), [path])

    def snapshot(self, root_names=None, paths=None, fail_hash=False):
        import cold_device
        plan, _ = example()
        device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, "/not-created")
        commands = []
        device._absent = lambda _: False
        root_names = root_names if root_names is not None else ["mobile-v1", "mobile-v1-account", "p8-positive-commit-foreign", "mobile-v1x"]
        paths = paths if paths is not None else ["no_backup/mobile-v1/original.bin", "no_backup/mobile-v1/original.bin.new", "no_backup/mobile-v1/original.bin.bak", "no_backup/mobile-v1-account/original.bin"]
        def run_as(*args, **kwargs):
            commands.append(args)
            if args[:2] == ("find", "no_backup"):
                self.assertEqual(("find", "no_backup", "-mindepth", "1", "-maxdepth", "1", "(", "-name", "mobile-v1", "-o", "-name", "mobile-v1-*", ")", "-print"), args)
                selected = ["no_backup/" + name for name in root_names if name == "mobile-v1" or name.startswith("mobile-v1-")]
                return ("\n".join(selected) + "\n" if selected else "").encode()
            if args[0] == "find":
                prefixes = ["no_backup/" + r for r in sorted(root_names) if r == "mobile-v1" or r.startswith("mobile-v1-")]
                if "!" in args:
                    self.assertEqual(("find", *prefixes, "!", "-type", "d", "!", "-type", "f", "-print"), args)
                    return b""
                self.assertEqual(("find", *prefixes, "-type", "f"), args)
                return ("\n".join(paths) + "\n" if paths else "").encode()
            self.assertEqual("sha256sum", args[0])
            if fail_hash:
                return b"sha256sum: synthetic error\n"
            return "".join("a" * 64 + " " + path + "\n" for path in args[1:]).encode()
        device._run_as = run_as
        return device, commands

    def test_defaults_only_find_and_hash_original_families_preserving_new_bak(self):
        device, commands = self.snapshot()
        result = device._defaults()
        self.assertEqual(4, len(result))
        self.assertIn("mobile-v1/original.bin.new", result)
        self.assertIn("mobile-v1/original.bin.bak", result)
        self.assertTrue(all(args[0] in ["find", "sha256sum"] for args in commands))
        self.assertFalse(any("p8-positive-commit-foreign" in arg or "mobile-v1x" in arg for args in commands for arg in args))

    def test_no_default_family_does_not_find_or_hash_other_storage(self):
        device, commands = self.snapshot(root_names=["foreign"])
        self.assertEqual({}, device._defaults())
        self.assertEqual(1, len(commands))
        self.assertEqual(("find", "no_backup", "-mindepth", "1", "-maxdepth", "1"), commands[0][:6])

    def test_unsafe_duplicate_foreign_or_excess_files_fail_before_hash(self):
        cases = [{"root_names": ["mobile-v1-../../foreign"]}, {"paths": ["no_backup/mobile-v1/../foreign"]}, {"paths": ["no_backup/foreign/file"]}, {"paths": ["no_backup/mobile-v1/a"] * 2}, {"paths": ["no_backup/mobile-v1/" + str(i) for i in range(1025)]}]
        for kwargs in cases:
            device, commands = self.snapshot(**kwargs)
            with self.subTest(kwargs=list(kwargs)), self.assertRaises(ValueError):
                device._defaults()
            self.assertFalse(any(args[0] == "sha256sum" for args in commands))

    def test_default_hashes_use_bounded_batches(self):
        paths = ["no_backup/mobile-v1/" + str(i) for i in range(129)]
        device, commands = self.snapshot(paths=paths)
        self.assertEqual(129, len(device._defaults()))
        self.assertEqual([128, 1], [len(args) - 1 for args in commands if args[0] == "sha256sum"])

    def test_failed_collection_preserves_bounded_stage_without_raw_output(self):
        device, _ = self.snapshot(fail_hash=True)
        device._pid = lambda: None
        result = device.seal_failure()
        self.assertEqual("ValueError", result["defaultsReadError"])
        self.assertIn("stage=hash-batch-0", result["defaultsReadDetails"])
        self.assertNotIn("synthetic error", result["defaultsReadDetails"])
        self.assertFalse(result["retryAllowed"])


class BoundedFamilyDiscoveryTests(unittest.TestCase):
    def test_large_unrelated_namespace_inventory_never_reaches_parser(self):
        case = OemDefaultHashTests()
        names = ["unrelated-" + str(i) + "-" + "x" * 230 for i in range(1200)]
        self.assertGreater(len("\n".join(names).encode()), 262144)
        device, commands = case.snapshot(root_names=names + ["mobile-v1", "mobile-v1-account"])
        self.assertEqual(4, len(device._defaults()))
        self.assertFalse(any(args[0] == "ls" for args in commands))
        self.assertTrue(all(not any("unrelated-" in arg for arg in args) for args in commands))

    def test_abnormal_duplicate_and_excess_selected_families_block_before_read(self):
        case = OemDefaultHashTests()
        for names in [["mobile-v1"] * 2, ["mobile-v1-" + str(i) for i in range(65)], ["mobile-v1-unsafe space"], ["mobile-v1-../../foreign"]]:
            device, commands = case.snapshot(root_names=names)
            with self.subTest(names=names[:2]), self.assertRaises(ValueError):
                device._defaults()
            self.assertEqual(1, len(commands))

    def test_root_or_nested_symlink_blocks_before_hash_or_file_walk(self):
        case = OemDefaultHashTests()
        for path in [b"no_backup/mobile-v1\n", b"no_backup/mobile-v1/original.bin.new\n"]:
            device, commands = case.snapshot()
            original = device._run_as
            def run_as(*args, **kwargs):
                if "!" in args:
                    commands.append(args)
                    return path
                return original(*args, **kwargs)
            device._run_as = run_as
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Symlink or special"):
                device._defaults()
            self.assertEqual(2, len(commands))
            self.assertFalse(any(args[0] == "sha256sum" for args in commands))

    def test_local_find_root_only_patterns_with_large_unrelated_tree_and_symlink(self):
        # Host-only integration with synthetic files; no ADB, device, listener or real defaults.
        import subprocess
        import cold_device
        plan, _ = example()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "no_backup"
            root.mkdir()
            for i in range(1200):
                (root / ("unrelated-" + str(i) + "-" + "x" * 230)).mkdir()
            default = root / "mobile-v1"
            default.mkdir()
            (default / "original.bin.new").write_bytes(b"synthetic-original")
            nested = root / "unrelated-nested/mobile-v1"
            nested.mkdir(parents=True)
            (nested / "never-read").write_bytes(b"synthetic-unrelated")
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            device._absent = lambda _: False
            calls = []
            def run_as(*args, **kwargs):
                calls.append(args)
                if args[0] == "sha256sum":
                    return "".join("a" * 64 + " " + path + "\n" for path in args[1:]).encode()
                self.assertEqual("find", args[0])
                completed = subprocess.run(list(args), cwd=temp, capture_output=True, timeout=kwargs["timeout"])
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertLessEqual(len(completed.stdout), kwargs["limit"])
                return completed.stdout
            device._run_as = run_as
            self.assertEqual({"mobile-v1/original.bin.new": "a" * 64}, device._defaults())
            self.assertTrue(all("unrelated" not in arg for args in calls for arg in args))
            link = root / "mobile-v1-link"
            link.symlink_to(nested, target_is_directory=True)
            calls.clear()
            with self.assertRaisesRegex(ValueError, "Symlink or special"):
                device._defaults()
            self.assertFalse(any(args[0] == "sha256sum" for args in calls))


class LeaseTests(unittest.TestCase):
    def test_parent_lease_binds_target_plan_and_short_expiry(self):
        import datetime
        now = datetime.datetime(2026, 10, 29, tzinfo=datetime.timezone.utc)
        with tempfile.TemporaryDirectory() as temp:
            adb = Path(temp) / "not-executed-adb"
            adb.write_bytes(b"hash-only")
            plan, _ = example()
            plan["taskId"] = "synthetic-controller-selfcheck"
            lease_id = str(uuid.uuid4())
            lease = {"version": 1, "taskId": plan["taskId"], "leaseId": lease_id, "owner": "parent-selfcheck", "serial": "owned-test", "bootId": str(uuid.uuid4()), "api": 37, "planSha256": "f" * 64, "exclusiveAcceptanceProcess": True, "expiresAt": (now + datetime.timedelta(minutes=15)).isoformat(), "adb": str(adb), "adbSha256": file_sha(adb), "lockFile": str(Path(temp) / ("p8-cold-owned-test-" + lease_id + ".lock"))}
            validate_lease(lease, plan, "f" * 64, now)
            cases = [("serial", "another-target"), ("planSha256", "e" * 64), ("taskId", "another-task"), ("exclusiveAcceptanceProcess", False), ("api", 38), ("adbSha256", "e" * 64), ("expiresAt", (now - datetime.timedelta(seconds=1)).isoformat()), ("expiresAt", (now + datetime.timedelta(hours=2)).isoformat()), ("lockFile", str(Path(temp) / "foreign.lock"))]
            for key, value in cases:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_lease({**lease, key: value}, plan, "f" * 64, now)
            self.assertFalse(Path(lease["lockFile"]).exists())


class BoundedTransportTests(unittest.TestCase):
    """Only local Python children; no ADB, Node, HTTP or device access."""
    def test_small_local_output_is_exact(self):
        from cold_device import bounded_call
        result = bounded_call([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'exact'); sys.stderr.write('err')"], 2, 32)
        self.assertEqual((0, b"exact", b"err"), (result.returncode, result.stdout, result.stderr))

    def test_stdout_hard_cap_stops_only_local_child(self):
        from cold_device import bounded_call
        with self.assertRaisesRegex(ValueError, "output exceeded"):
            bounded_call([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x'*8192)"], 2, 1024)

    def test_stderr_hard_cap_stops_only_local_child(self):
        from cold_device import bounded_call
        with self.assertRaisesRegex(ValueError, "output exceeded"):
            bounded_call([sys.executable, "-c", "import sys; sys.stderr.buffer.write(b'x'*100000)"], 2, 1024)

    def test_deadline_stops_only_local_child(self):
        from cold_device import bounded_call
        with self.assertRaisesRegex(ValueError, "deadline exceeded"):
            bounded_call([sys.executable, "-c", "import time; time.sleep(10)"], 0.05, 1024)

    def test_failed_writer_is_preserved_without_device_cleanup_call(self):
        import cold_device
        plan, _ = example()
        class LocalTransport:
            def __init__(self):
                self.process = type("Process", (), {"poll": lambda self: None})()
                self.finished = False
            def finish_client(self, **kwargs):
                self.finished = True
        with tempfile.TemporaryDirectory() as temp:
            device = cold_device.AdbDevice(plan, {"adb": "/not-executed/adb"}, temp)
            device.writer = LocalTransport()
            device.writer_identity = {"pid": 123}
            device._call = lambda *a, **k: self.fail("Failure cleanup cannot call device")
            result = device.cleanup()
            self.assertTrue(device.writer.finished)
            self.assertFalse(result["complete"])
            self.assertTrue(result["writerMayStillRun"])
            self.assertFalse(result["readerForceStopped"])


if __name__ == "__main__":
    unittest.main()
