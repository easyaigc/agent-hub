"""Run the independent EasyWork XCTest over an existing paired Wi-Fi connection.

The App uses its normal authenticated transport. Only the private examiner's
three HTTP endpoints may use the Runner's paired-device data container.
"""

import argparse
import asyncio
import base64
import contextlib
import fcntl
import hashlib
import importlib.metadata
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


APP = "com.easyai.agenthub.easyworkacceptance"
RUNNER = APP + ".uitests.xctrunner"
SELECTOR = "CodexManageUITests/testEasyWorkRealDEVDeviceGrant"
METHOD = SELECTOR.split("/")[1]
MAX_RESPONSE = 24 * 1024 * 1024 - 4096  # Leave room for the 32 MiB Native envelope.


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def private_write(path, data, exclusive=False):
    flags = os.O_WRONLY | os.O_CREAT | (os.O_EXCL if exclusive else os.O_TRUNC)
    with os.fdopen(os.open(path, flags, 0o600), "wb") as file:
        file.write(data)
        file.flush()
        os.fsync(file.fileno())


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Examiner:
    """Validate, durably seal once, then deliver the same cached response."""

    def __init__(self, config, directory, session_nonce):
        self.config = config
        assert str(uuid.UUID(session_nonce)).upper() == session_nonce
        self.session_nonce = session_nonce
        assert isinstance(config["authorization"], str) and config["authorization"]
        self.directory = directory
        self.directory.mkdir(mode=0o700)
        self.observer = urllib.parse.urlsplit(
            config.get("observerBaseURL", f"http://127.0.0.1:{config['observerPort']}")
        )
        assert self.observer.scheme == "http" and self.observer.hostname == "127.0.0.1"
        assert self.observer.path in ("", "/") and not self.observer.query
        assert not self.observer.username and not self.observer.password and not self.observer.fragment
        self.checkpoint = config.get("managementCheckpointURL")
        if self.checkpoint:
            assert isinstance(config["managementCheckpointAuthorization"], str) and config["managementCheckpointAuthorization"]
            endpoint = urllib.parse.urlsplit(self.checkpoint)
            assert endpoint.scheme == "http" and endpoint.hostname == "127.0.0.1"
            assert endpoint.path == "/checkpoint" and not endpoint.query and not endpoint.fragment
            assert not endpoint.username and not endpoint.password

    def validate(self, raw):
        envelope = json.loads(raw)
        request_id = envelope["requestId"]
        assert str(uuid.UUID(request_id)).upper() == request_id
        payload = base64.b64decode(envelope["payloadBase64"], validate=True)
        assert len(payload) < 1024 * 1024 and envelope["version"] == 1
        assert sha(payload) == envelope["sha256"]
        value = json.loads(payload)
        assert value.get("sessionNonce") == self.session_nonce, "A stale Native session cannot dispatch an examiner request"
        url = urllib.parse.urlsplit(value["url"])
        assert url.scheme == "http" and url.hostname == "127.0.0.1"
        assert not url.username and not url.password and not url.fragment
        headers = {key.lower(): item for key, item in value["headers"].items()}
        assert set(headers) <= {"content-type", "x-easywork-acceptance"}
        method = value["method"]
        body = base64.b64decode(value["bodyBase64"], validate=True)
        if url.path == "/checkpoint":
            assert value["url"] == self.checkpoint and method == "POST"
            assert headers.get("x-easywork-acceptance") == self.config["managementCheckpointAuthorization"]
        else:
            assert url.port == self.observer.port
            assert headers.get("x-easywork-acceptance") == self.config["authorization"]
            if url.path == "/evidence":
                assert method == "GET" and not body
                query = urllib.parse.parse_qs(url.query, keep_blank_values=True)
                assert set(query) <= {"native", "threadId"} and all(len(v) == 1 for v in query.values())
            else:
                assert url.path == "/control" and method == "POST" and not url.query
                operation = json.loads(body)
                assert set(operation) <= {"operation", "bindingId"}
                assert operation["operation"] in {
                    "desktop.device-offer-create", "desktop.device-claim-approve", "desktop.device-binding-revoke"
                }
        return request_id, envelope["sha256"], value, body

    def response(self, raw):
        request_id, digest, value, body = self.validate(raw)
        intent = self.directory / (request_id + "-intent.json")
        reply = self.directory / (request_id + "-response.json")
        if intent.exists():
            assert intent.read_bytes() == raw, "An examiner ID cannot change its original intent"
            if reply.exists():
                return request_id, digest, reply
            # A previous dispatch may have executed. Seal unknown, never reissue.
            status, data = 503, encoded({"error": "Original examiner outcome unknown; not replayed"})
        else:
            private_write(intent, raw, exclusive=True)
            try:
                request = urllib.request.Request(
                    value["url"], data=body if value["method"] == "POST" else None,
                    headers=value["headers"], method=value["method"]
                )
                opener = urllib.request.build_opener(NoRedirect())
                try:
                    response = opener.open(request, timeout=45)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    status, data = response.code, response.read(MAX_RESPONSE + 1)
                assert len(data) <= MAX_RESPONSE
            except Exception as error:
                status, data = 503, encoded({"error": "Original examiner outcome unknown; not replayed", "type": type(error).__name__})
        private_write(reply, encoded({"version": 1, "requestId": request_id, "sha256": digest,
            "status": status, "bodyBase64": base64.b64encode(data).decode()}), exclusive=True)
        return request_id, digest, reply


class NativeConsole(logging.Handler):
    """Keep actual own-process XCTest output; never log a DTX launch environment."""

    def __init__(self, path):
        super().__init__()
        self.pid = None
        self.lines = []
        self.path = path

    def emit(self, record):
        if record.name != "pymobiledevice3.services.dvt.testmanaged.dtx_services":
            return
        message = record.getMessage()
        matched = re.match(r"\[process:([0-9]+)\] (.*)", message, re.S)
        if matched and (self.pid is None or int(matched[1]) == self.pid):
            self.lines.append((int(matched[1]), message))
            if self.pid is not None:
                private_write(self.path, self.text().encode())

    def text(self):
        # One DTX stdout record can contain several XCTest lines. Its actual
        # process owner applies to every line, including the selected suite.
        # Never infer ownership from an anonymous line in another log source.
        return "\n".join(f"[process:{pid}] {line}" for pid, message in self.lines if pid == self.pid
            for line in re.sub(r"^\[process:[0-9]+\] ", "", message).splitlines())

    def result(self):
        text = self.text()
        prefix = re.escape(f"[process:{self.pid}] Test Case '-[CodexManageUITests.CodexManageUITests {METHOD}]'")
        starts = len(re.findall(prefix + r" started\.", text))
        passes = re.findall(prefix + r" passed \(([0-9.]+) seconds\)\.", text)
        failures = len(re.findall(prefix + r" failed \(", text))
        suite = f"[process:{self.pid}] Test Suite 'Selected tests' passed" in text
        initialization = next((line.split("Failed to initialize for UI testing:", 1)[1].strip()[:1024]
            for line in text.splitlines() if "Failed to initialize for UI testing:" in line), None)
        return {"nativeBodies": starts, "nativePassedBodies": len(passes), "nativeFailedBodies": failures,
            "selectedSuitePassed": suite, "duration": float(passes[0]) if len(passes) == 1 else None,
            "nativeInitializationFailure": initialization,
            "strictConsolePassed": starts == len(passes) == 1 and failures == 0 and suite and initialization is None}


async def command(*arguments, timeout=25):
    process = await asyncio.create_subprocess_exec(*arguments,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        stdout, stderr = await asyncio.wait_for(process.communicate(), timeout)
    except BaseException:
        process.kill()
        await process.communicate()
        raise
    return process.returncode, stdout, stderr


class PhysicalRun:
    def __init__(self, arguments, config):
        self.args, self.config = arguments, config
        self.out = arguments.output
        self.run = arguments.run_id
        self.console = NativeConsole(self.out / "native-console.log")
        self.pid = None
        self.rsd = None
        self.conflict = False
        self.session_nonce = str(uuid.uuid4()).upper()

    def save(self, name, value):
        private_write(self.out / name, encoded(value))

    async def info(self, kind, name):
        destination = self.out / name
        code, _, error = await command("xcrun", "devicectl", "device", "info", kind,
            "--device", self.args.device, "--timeout", "20", "--json-output", str(destination))
        assert code == 0, "CoreDevice read failed: " + error.decode(errors="replace")
        return json.loads(destination.read_bytes())["result"]

    def exact_device(self, details):
        connection = details["connectionProperties"]
        assert details["hardwareProperties"]["udid"] == self.args.udid
        assert connection["pairingState"] == "paired" and connection["transportType"] == "localNetwork"
        assert connection["tunnelState"] == "connected"
        return connection

    @staticmethod
    def runners(processes, own=None):
        return [p for p in processes if p.get("processIdentifier") != own and
            any(name in p.get("executable", "").lower() for name in ("uitests-runner", "xctrunner", "webdriveragent"))]

    async def copy(self, direction, source, destination):
        return await command("xcrun", "devicectl", "device", "copy", direction,
            "--device", self.args.device, "--domain-type", "appDataContainer",
            "--domain-identifier", RUNNER, "--source", str(source), "--destination", str(destination), timeout=20)

    async def deliver(self, source, destination, name):
        for attempt in range(3):
            try:
                code, stdout, stderr = await self.copy("to", source, destination)
                private_write(self.out / f"{name}-{attempt}.log", stdout + stderr)
                if code == 0:
                    return
            except asyncio.TimeoutError:
                self.save(f"{name}-{attempt}.json", {"copyTimedOut": True, "originalHTTPNotReplayed": True})
            await asyncio.sleep(.5)
        raise RuntimeError("Cached examiner file delivery failed; original HTTP is never replayed")

    async def file_examiner(self):
        examiner = Examiner(self.config, self.out / "examiner", self.session_nonce)
        folder = f"Documents/easywork-examiner/{self.run}"
        incoming = examiner.directory / "incoming.json"
        while True:
            code, _, _ = await self.copy("from", folder + "/request.json", incoming)
            if code == 0:
                raw = incoming.read_bytes()
                # A copied input may be incomplete; validate before any dispatch.
                try:
                    examiner.validate(raw)
                except (ValueError, KeyError, AssertionError):
                    await asyncio.sleep(.5)
                    continue
                request_id, digest, reply = await asyncio.to_thread(examiner.response, raw)
                await self.deliver(reply, folder + f"/response-{request_id}.json", request_id + "-response-copy")
                data = reply.read_bytes()
                marker = examiner.directory / (request_id + "-ready.json")
                marker_data = encoded({"version": 1, "requestId": request_id, "requestSha256": digest,
                    "responseBytes": len(data), "responseSha256": sha(data)})
                if marker.exists():
                    assert marker.read_bytes() == marker_data
                else:
                    private_write(marker, marker_data, exclusive=True)
                await self.deliver(marker, folder + f"/ready-{request_id}.json", request_id + "-ready-copy")
            await asyncio.sleep(.75)

    async def stop_own(self, reason):
        if self.pid is None:
            return
        processes = (await self.info("processes", "before-own-stop.json"))["runningProcesses"]
        exact = [p for p in processes if p.get("processIdentifier") == self.pid]
        if exact and "uitests-runner" in exact[0].get("executable", "").lower():
            code, _, _ = await command("xcrun", "devicectl", "device", "process", "terminate",
                "--device", self.args.device, "--pid", str(self.pid))
            self.save("own-stop.json", {"pid": self.pid, "reason": reason, "terminated": code == 0})

    async def execute(self):
        from bpylist2 import archiver
        from pymobiledevice3.dtx import NSUUID
        from pymobiledevice3.remote.native_tunnel import parse_nettop_tcp_csv
        from pymobiledevice3.remote.remote_service_discovery import RemoteServiceDiscoveryService
        from pymobiledevice3.remote.remotexpc import remoted_handshake_uuid
        from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
        from pymobiledevice3.services.dvt.testmanaged.xcuitest import ProcessControlChannel, TestConfig, _generate_launch_args

        details = await self.info("details", "before-device.json")
        connection = self.exact_device(details)
        deadline = time.monotonic() + self.args.wait_idle
        while True:
            processes = (await self.info("processes", "before-processes.json"))["runningProcesses"]
            foreign = self.runners(processes)
            if not foreign:
                break
            self.save("waiting-for-native-idle.json", {"foreign": foreign, "nativeStarted": False})
            if time.monotonic() >= deadline:
                raise RuntimeError("Another native Runner remains present; no test launched")
            await asyncio.sleep(10)
        code, stdout, _ = await command("/usr/bin/nettop", "-n", "-x", "-L", "1", "-m", "tcp", "-J", "interface,state")
        assert code == 0
        ports = []
        for pid, address, port in parse_nettop_tcp_csv(stdout.decode()):
            if address != connection["tunnelIPAddress"]:
                continue
            code, name, _ = await command("/bin/ps", "-p", str(pid), "-o", "comm=")
            if code == 0 and name.decode().strip() == "/usr/libexec/remoted":
                ports.append(port)
        assert len(set(ports)) == 1, "Require one existing paired remoted endpoint"
        self.rsd = RemoteServiceDiscoveryService((connection["tunnelIPAddress"], ports[0]),
            handshake_uuid=await asyncio.to_thread(remoted_handshake_uuid))
        await asyncio.wait_for(self.rsd.connect(), 25)
        assert self.rsd.udid == self.args.udid
        self.save("actual-rsd-identity.json", {"udid": self.rsd.udid,
            "productType": self.rsd.product_type, "productVersion": self.rsd.product_version})
        config = await asyncio.wait_for(TestConfig.create_for(self.rsd, RUNNER, APP), 20)
        assert config.runner_app_info["CFBundleIdentifier"] == RUNNER
        assert config.target_app_info["CFBundleIdentifier"] == APP
        config.tests_to_run = [SELECTOR]
        config.target_app_args = ["-ui-testing", "-restore-testing"]
        session = NSUUID.uuid4()
        native = config.to_xctestconfiguration(session, self.rsd)
        native._config.update(reportResultsToIDE=False, testsDrivenByIDE=False, reportActivities=False, emitOSLogs=True)
        archive = archiver.archive(native)
        self.save("native-intent.json", {"app": APP, "runner": RUNNER, "testsToRun": [SELECTOR],
            "configurationSha256": sha(archive), "configSha256": sha(encoded(self.config)),
            "examinerSessionNonceSha256": sha(self.session_nonce.encode()), "codexTests": False})
        arguments, environment, options = _generate_launch_args(27, session, config.runner_app_info,
            "/tmp/unused.xctestconfiguration", None, None)
        environment.pop("XCTestSessionIdentifier", None)
        environment.update(XCTestConfigurationEncodedValueEnvironmentKey=base64.b64encode(archive).decode(),
            XCTestConfigurationFilePath="", EASYWORK_GRANT_EXAMINER_CONFIG_JSON=json.dumps(self.config),
            EASYWORK_NATIVE_ARTIFACT_RUN=self.run, EASYWORK_EXAMINER_FILE_BRIDGE_RUN=self.run,
            EASYWORK_EXAMINER_SESSION_NONCE=self.session_nonce)
        logging.getLogger("pymobiledevice3.services.dvt.testmanaged.dtx_services").setLevel(logging.DEBUG)
        logging.getLogger().addHandler(self.console)
        worker = asyncio.create_task(self.file_examiner())
        try:
            async with DvtProvider(self.rsd) as dvt:
                async with ProcessControlChannel(dvt) as channel:
                    # Close the gap between initial preflight and actual launch.
                    assert not self.runners((await self.info("processes", "launch-preflight.json"))["runningProcesses"])
                    self.pid = await channel.service.launch_suspended_process("", RUNNER, environment, arguments, options)
                    self.console.pid = self.pid
                    self.save("own-runner.json", {"pid": self.pid, "bundle": RUNNER})
                    deadline = time.monotonic() + self.args.timeout
                    while time.monotonic() < deadline:
                        await asyncio.sleep(3)
                        if worker.done():
                            await worker  # Preserve observer failure; never silently wait it out.
                        if self.console.result()["strictConsolePassed"]:
                            break
                        processes = (await self.info("processes", "during-processes.json"))["runningProcesses"]
                        foreign = self.runners(processes, self.pid)
                        if foreign:
                            self.conflict = True
                            self.save("native-conflict.json", {"foreign": foreign, "ownPid": self.pid})
                            await self.stop_own("A different native Runner appeared; original operations preserved")
                            break
                        if not any(p.get("processIdentifier") == self.pid for p in processes):
                            break
                    else:
                        await self.stop_own("Bounded native execution deadline")
        finally:
            worker.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await worker
            logging.getLogger().removeHandler(self.console)

    async def finish(self):
        if self.rsd:
            await asyncio.wait_for(self.rsd.close(), 10)
        if self.pid:
            code, stdout, stderr = await self.copy("from", f"Documents/easywork-acceptance/{self.run}", self.out / "native")
            private_write(self.out / "native-copy.log", stdout + stderr)
            assert code == 0, "Actual Native observations/artifacts were not retrieved"


async def run(arguments, config):
    execution = PhysicalRun(arguments, config)
    error = None
    try:
        await execution.execute()
    except Exception as caught:
        error = type(caught).__name__ + ": " + str(caught)
        try:
            await execution.stop_own("Examiner failed; do not replay original operation")
        except Exception as stop_error:
            execution.save("own-stop-error.json", {"type": type(stop_error).__name__})
    artifacts_retrieved = False
    try:
        await execution.finish()
        artifacts_retrieved = True
    except Exception as caught:
        error = error or type(caught).__name__ + ": " + str(caught)
    # Missing Native artifacts cannot be evidence that the paired device disconnected.
    connected = False
    try:
        execution.exact_device(await execution.info("details", "after-device.json"))
        connected = True
    except Exception as caught:
        error = error or type(caught).__name__ + ": " + str(caught)
    summary = execution.console.result()
    if summary.get("nativeInitializationFailure"):
        summary["nativeEvidenceError"] = error
        error = "Native UI initialization failed: " + summary["nativeInitializationFailure"]
    observed = execution.out / "native/native-xctest-events.json"
    events = json.loads(observed.read_bytes()) if observed.exists() else []
    finishes = [event for event in events if event.get("kind") == "case-finish"]
    observation_passed = len(finishes) == 1 and finishes[0].get("passed") is True
    passed = summary["strictConsolePassed"] and observation_passed and artifacts_retrieved and connected and not error and not execution.conflict
    summary.update(status="passed-native-scoped" if passed else "failed-or-not-executed",
        nativeObservationPassed=observation_passed, originalCoreDeviceConnected=connected,
        nativeArtifactsRetrieved=artifacts_retrieved,
        conflict=execution.conflict, error=error, fullFeaturePassed=False, codexTests=False,
        businessVerification="Required separately: exact desktop receipts, model inputs, SQL and protected original data")
    execution.save("native-summary.json", summary)
    print(json.dumps({key: value for key, value in summary.items() if key != "error"}))
    return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Exact paired CoreDevice identifier")
    parser.add_argument("--udid", required=True, help="Independent expected hardware UDID")
    parser.add_argument("--config", type=Path, required=True, help="Private examiner JSON, never printed")
    parser.add_argument("--run-id", required=True, help="Fresh safe run ID; never reuse an unknown run")
    parser.add_argument("--output", type=Path, required=True, help="New private evidence directory")
    parser.add_argument("--timeout", type=int, default=1200, choices=range(30, 1201), metavar="30..1200")
    parser.add_argument("--wait-idle", type=int, default=300, choices=range(0, 601), metavar="0..600")
    args = parser.parse_args()
    assert sys.platform == "darwin" and sys.version_info >= (3, 12)
    assert importlib.metadata.version("pymobiledevice3") == "11.43.1"
    assert re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", args.run_id)
    assert args.config.is_file() and args.config.stat().st_mode & 0o077 == 0, "Config must be private (0600)"
    config = json.loads(args.config.read_bytes())
    args.output = args.output.resolve()
    args.output.mkdir(mode=0o700)  # Existing output is never overwritten or replayed.
    lease_dir = Path.home() / ".cache/agent-hub/ios-physical-examiner"
    lease_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(lease_dir, 0o700)
    lease = os.open(lease_dir / (sha(args.udid.encode()) + ".lock"), os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
        logging.basicConfig(level=logging.CRITICAL)
        os.umask(0o077)
        return asyncio.run(run(args, config))
    finally:
        os.close(lease)


if __name__ == "__main__":
    raise SystemExit(main())
