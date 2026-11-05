"""Examiner boundaries only; no device, Codex, or model execution."""

import base64
import asyncio
import importlib.util
import json
import logging
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import uuid
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("examiner", Path(__file__).with_name("ios-physical-easywork.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class ExaminerBoundary(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.calls = []
        calls = self.calls

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                calls.append((self.path, self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"accepted":true}')

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.config = {"observerPort": self.server.server_port, "authorization": "synthetic-examiner"}
        self.nonce = str(uuid.uuid4()).upper()
        self.examiner = driver.Examiner(self.config, Path(self.directory.name) / "examiner", self.nonce)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, *, url=None, operation="desktop.device-offer-create", request_id=None):
        payload = driver.encoded({"url": url or f"http://127.0.0.1:{self.server.server_port}/control",
            "method": "POST", "headers": {"x-easywork-acceptance": "synthetic-examiner"},
            "bodyBase64": base64.b64encode(driver.encoded({"operation": operation})).decode(), "sessionNonce": self.nonce})
        return driver.encoded({"version": 1, "requestId": request_id or str(uuid.uuid4()).upper(),
            "sha256": driver.sha(payload), "payloadBase64": base64.b64encode(payload).decode()})

    def test_cached_delivery_never_repeats_the_original_http(self):
        raw = self.request()
        first = self.examiner.response(raw)
        second = self.examiner.response(raw)
        self.assertEqual(first, second)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(json.loads(first[2].read_bytes())["status"], 200)

    def test_previously_sealed_dispatch_without_reply_is_unknown_not_replayed(self):
        raw = self.request()
        request_id = json.loads(raw)["requestId"]
        driver.private_write(self.examiner.directory / (request_id + "-intent.json"), raw, exclusive=True)
        result = self.examiner.response(raw)
        self.assertEqual(json.loads(result[2].read_bytes())["status"], 503)
        self.assertEqual(self.calls, [])

    def test_same_id_cannot_change_its_original_operation(self):
        raw = self.request()
        self.examiner.response(raw)
        different = self.request(operation="desktop.device-claim-approve", request_id=json.loads(raw)["requestId"])
        with self.assertRaises(AssertionError):
            self.examiner.response(different)
        self.assertEqual(len(self.calls), 1)

    def test_stale_native_request_is_not_dispatched_by_a_new_examiner(self):
        raw = self.request()
        self.examiner.response(raw)
        fresh = driver.Examiner(self.config, Path(self.directory.name) / "fresh-examiner", str(uuid.uuid4()).upper())
        with self.assertRaises(AssertionError):
            fresh.response(raw)
        self.assertEqual(len(self.calls), 1)

    def test_reject_arbitrary_host_path_shell_and_truncated_envelope_before_dispatch(self):
        invalid = [self.request(url="http://example.invalid/control"),
            self.request(url=f"http://127.0.0.1:{self.server.server_port}/shell"),
            self.request(operation="shell"), self.request()[:-5]]
        for raw in invalid:
            with self.assertRaises((AssertionError, ValueError)):
                self.examiner.response(raw)
        self.assertEqual(self.calls, [])


class NativeResultBoundary(unittest.TestCase):
    def test_own_initialization_failure_never_becomes_a_pass_from_other_output(self):
        with tempfile.TemporaryDirectory() as directory:
            recorder = driver.NativeConsole(Path(directory) / "console.log")
            recorder.pid = 23
            def emit(pid, text):
                recorder.emit(logging.LogRecord("pymobiledevice3.services.dvt.testmanaged.dtx_services",
                    logging.DEBUG, "", 0, f"[process:{pid}] {text}", (), None))
            emit(24, "Failed to initialize for UI testing: another process")
            self.assertIsNone(recorder.result()["nativeInitializationFailure"])
            emit(23, "Failed to initialize for UI testing: Timed out while enabling automation mode.")
            self.assertEqual(recorder.result()["nativeInitializationFailure"], "Timed out while enabling automation mode.")
            self.assertFalse(recorder.result()["strictConsolePassed"])

    def test_missing_native_artifacts_does_not_skip_the_independent_device_check(self):
        with tempfile.TemporaryDirectory() as directory:
            summaries = []
            checked = []
            class Run:
                conflict = False
                def __init__(self, *_):
                    self.out = Path(directory)
                    self.console = driver.NativeConsole(self.out / "console.log")
                    self.console.pid = 23
                async def execute(self):
                    pass
                async def finish(self):
                    raise AssertionError("Actual Native observations/artifacts were not retrieved")
                async def info(self, kind, name):
                    checked.append((kind, name))
                    return {"same-paired-device": True}
                def exact_device(self, value):
                    assert value == {"same-paired-device": True}
                def save(self, name, value):
                    summaries.append(value)
            with patch.object(driver, "PhysicalRun", Run):
                self.assertEqual(asyncio.run(driver.run(None, None)), 1)
            self.assertEqual(checked, [("details", "after-device.json")])
            self.assertTrue(summaries[-1]["originalCoreDeviceConnected"])
            self.assertFalse(summaries[-1]["nativeArtifactsRetrieved"])
            self.assertFalse(summaries[-1]["strictConsolePassed"])

    def test_other_process_pass_or_zero_bodies_never_means_native_success(self):
        with tempfile.TemporaryDirectory() as directory:
            recorder = driver.NativeConsole(Path(directory) / "console.log")
            recorder.pid = 23

            def emit(pid, line):
                recorder.emit(logging.LogRecord("pymobiledevice3.services.dvt.testmanaged.dtx_services",
                    logging.DEBUG, "", 0, f"[process:{pid}] {line}", (), None))

            case = "Test Case '-[CodexManageUITests.CodexManageUITests testEasyWorkRealDEVDeviceGrant]'"
            emit(24, case + " started.")
            emit(24, case + " passed (1.000 seconds).")
            emit(24, "Test Suite 'Selected tests' passed")
            self.assertFalse(recorder.result()["strictConsolePassed"])
            emit(23, "Test Suite 'Selected tests' passed")
            self.assertFalse(recorder.result()["strictConsolePassed"])
            emit(23, case + " started.")
            emit(23, case + " passed (2.461 seconds).")
            self.assertTrue(recorder.result()["strictConsolePassed"])
            recorder.emit(logging.LogRecord("pymobiledevice3.dtx.connection", logging.DEBUG,
                "", 0, "launch environment must never be retained", (), None))
            self.assertNotIn("launch environment", recorder.path.read_text())

    def test_multiline_suite_output_keeps_its_actual_process_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            recorder = driver.NativeConsole(Path(directory) / "console.log")
            recorder.pid = 23
            case = "Test Case '-[CodexManageUITests.CodexManageUITests testEasyWorkRealDEVDeviceGrant]'"
            message = f"[process:23] {case} started.\n{case} passed (1.000 seconds).\nTest Suite 'Selected tests' passed"
            recorder.emit(logging.LogRecord("pymobiledevice3.services.dvt.testmanaged.dtx_services",
                logging.DEBUG, "", 0, message, (), None))
            self.assertTrue(recorder.result()["strictConsolePassed"])


if __name__ == "__main__":
    unittest.main()
