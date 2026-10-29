"""Explicit execution adapter. Imported only by `execute`; no import-time device commands."""
import fcntl
import json
import logging
import logging.handlers
import os
import re
import shlex
import selectors
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from cold_contract import (
    BASE, COMPONENT, PACKAGE, RUNNER, canonical, case_id, digest, file_sha, json_once,
    lineage_paths, loads, need, safe_relative, sha, validate_config, validate_stats,
)


INVENTORY_LIMIT = 2 * 1024 * 1024
INVENTORY_TIMEOUT = 60
COMMON_INVENTORY = [
    ("SETTINGS", "settings"), ("ALLOWED ASSOCIATION STATE", "allowed-associations"),
    ("PENDING INTENTS", "intents"), ("BROADCAST STATE", "broadcasts"),
    ("CONTENT PROVIDERS", "providers"), ("URI PERMISSIONS", "permissions"),
    ("SERVICES", "services"), ("RECENT TASKS", "recents"), ("LAST ANR", "lastanr"),
    ("STARTER", "starter"), ("CONTAINERS", "containers"), ("ACTIVITIES", "activities"),
    ("LMK KILLS", "lmk"), ("RUNNING PROCESSES", "processes"), ("USERS", "users"),
    ("COMPONENT-ALIAS", "component-alias"),
]
INVENTORY_PROFILES = {
    33: {"id": "api33-loopers", "kind": "physical", "headers": COMMON_INVENTORY + [("LOOPERS", "loopers")], "tailMinimumBytes": 1},
    37: {"id": "api37-component-alias", "kind": "emulator", "headers": COMMON_INVENTORY, "tailMinimumBytes": 25},
}


def instrumentation_active(raw, api):
    """Only an observed SDK profile can establish idle; no generic-tail fallback."""
    need(type(api) is int and api in INVENTORY_PROFILES, "Unobserved runtime SDK; no start")
    profile = INVENTORY_PROFILES[api]
    need(0 < len(raw) <= INVENTORY_LIMIT, "Empty/oversized runtime inventory; no start")
    text = raw.decode("utf-8")
    need(not re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", text), "Invalid runtime inventory text; no start")
    need(not re.search(r"(?mi)^[ \t]*(?:Unknown command:|Bad activity command|Permission Denial:|Error:|Can't find service:)|DUMP TIMEOUT|DUMP FAILED", text), "Runtime inventory command rejected/incomplete; no start")
    pattern = re.compile(r"(?m)^[ \t]*ACTIVITY MANAGER ([A-Z -]+) \(dumpsys activity ([a-z-]+)\)(?:[^\r\n]*)\r?$")
    headers = list(pattern.finditer(text))
    identities = [(m[1], m[2]) for m in headers]
    need(identities == profile["headers"], "Unknown/partial/wrong-SDK full runtime inventory; no start")
    required_bodies = [("SETTINGS", "settings"), ("ACTIVITIES", "activities"),
                       ("RUNNING PROCESSES", "processes"), ("USERS", "users"), profile["headers"][-1]]
    for identity in required_bodies:
        index = identities.index(identity)
        end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
        body = text[headers[index].end():end]
        need(body.strip(), "Empty/truncated runtime state section; no start")
        if index == len(headers) - 1:
            need(len(body.encode()) >= profile["tailMinimumBytes"], "Truncated observed runtime trailer; no start")
    return bool(re.search(r"Active instrumentation\s*:", text, re.I))


def parse_hashes(raw, paths):
    """OEM one-space/tab and coreutils two-space separators; exact paths stay strict."""
    need(len(raw) <= 512000, "Requested hash output bound")
    result = {}
    for line in raw.decode("utf-8").splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})[ \t]+(.+)", line)
        need(match and match[2] in paths and match[2] not in result, "Unidentified/duplicate requested file hash")
        result[match[2]] = match[1]
    need(set(result) == set(paths), "Missing exact requested file hashes")
    return result


def bounded_call(argv, timeout, stdout_limit):
    """Hard output/deadline cap. Only kills its own local transport child on violation."""
    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    selector = selectors.DefaultSelector()
    buffers = {"stdout": bytearray(), "stderr": bytearray()}
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    deadline = time.monotonic() + timeout
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            need(remaining > 0, "Named local transport deadline exceeded")
            for key, _ in selector.select(min(remaining, 0.1)):
                raw = os.read(key.fd, 8192)
                if not raw:
                    selector.unregister(key.fileobj)
                    continue
                buffers[key.data].extend(raw)
                cap = stdout_limit if key.data == "stdout" else 65536
                need(len(buffers[key.data]) <= cap, "Bounded device output exceeded")
        process.wait(timeout=max(0.01, deadline - time.monotonic()))
        return subprocess.CompletedProcess(argv, process.returncode, bytes(buffers["stdout"]), bytes(buffers["stderr"]))
    finally:
        selector.close()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=2)
        process.stdout.close()
        process.stderr.close()


class Capture:
    def __init__(self, argv, log):
        self.argv = list(argv)
        self.process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.tail = b""
        self.failed = False
        self.ok = False
        self.lock = threading.Lock()
        self.logger = logging.Logger("p8-owned-" + str(self.process.pid))
        self.handler = logging.handlers.RotatingFileHandler(log, maxBytes=2 * 1024 * 1024, backupCount=3)
        os.chmod(log, 0o600)
        self.logger.addHandler(self.handler)
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        while True:
            raw = self.process.stdout.read1(4096)
            if not raw:
                break
            self.logger.warning(raw.decode(errors="replace").rstrip())
            with self.lock:
                self.tail = (self.tail + raw)[-131072:]
                self.ok |= bool(re.search(rb"\bOK \(\d+ tests?\)", self.tail))
                self.failed |= bool(re.search(rb"FAILURES!!!|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]", self.tail))

    def finish_client(self, timeout=3):
        try:
            self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Only this local child transport, never an Android process or another task.
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.thread.join(timeout=2)
        self.handler.close()


class AdbDevice:
    def __init__(self, plan, lease, root):
        self.plan, self.lease, self.root = plan, lease, Path(root)
        self.serial = plan["target"]["serial"]
        self.adb = lease["adb"]
        self.runtime = self.root / "owned-sidecar"
        self.config = None
        self.node = None
        self.writer = None
        self.reader = None
        self.added = []
        self.original_reverse = None
        self.writer_identity = None
        self.writer_stopped = False
        self.reader_started = False
        self.lock = None
        self.deadline = None
        self.uid = None
        self.hz = None
        self.writer_proof = None
        self.runtime_profile = None
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, message, headers, newurl):
                return None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def _call(self, tokens, timeout=5, limit=262144, check=True):
        if self.deadline is not None:
            remaining = self.deadline - time.monotonic()
            need(remaining > 0, "Writer stop deadline exhausted; no further critical command")
            timeout = min(timeout, remaining)
        result = bounded_call([self.adb, "-s", self.serial, *tokens], timeout, limit)
        if check:
            need(result.returncode == 0, "Named ADB operation failed: " + str(tokens[:3]))
        return result

    def _shell(self, *tokens, **kwargs):
        return self._call(["shell", "-T", shlex.join(tokens)], **kwargs).stdout

    def _run_as(self, *tokens, **kwargs):
        return self._shell("run-as", PACKAGE, *tokens, **kwargs)

    def _uptime(self):
        raw = self._shell("cat", "/proc/uptime", timeout=2).decode().split()[0]
        need(re.fullmatch(r"\d+\.\d+", raw), "External elapsed clock unavailable")
        return int(float(raw) * 1000)

    def _pid(self):
        result = self._call(["shell", "-T", shlex.join(["pidof", PACKAGE])], check=False, timeout=2)
        text = result.stdout.decode().strip()
        if not text:
            need(result.returncode in [0, 1], "PID query failed")
            return None
        need(re.fullmatch(r"[1-9]\d*", text), "Expected one exact target process")
        return int(text)

    def _verify_runtime_profile(self, deadline):
        """Read-only binding to the actual leased boot/SDK/user/AVD; no app/process mutation."""
        self.runtime_profile = None
        def remaining():
            value = deadline - time.monotonic()
            need(value > 0, "Runtime inventory60s deadline exhausted; no start")
            return min(5, value)
        need(self._call(["get-state"], timeout=remaining()).stdout.strip() == b"device", "Target unavailable")
        boot = self._shell("cat", "/proc/sys/kernel/random/boot_id", timeout=remaining()).decode().strip()
        need(boot == self.lease["bootId"], "Another boot/device")
        api = int(self._shell("getprop", "ro.build.version.sdk", timeout=remaining()))
        need(type(self.lease["api"]) is int and api == self.lease["api"] and api in INVENTORY_PROFILES, "Unobserved/mismatched leased SDK; no start")
        need(self._shell("am", "get-current-user", timeout=remaining()).strip() == b"0", "Only explicitly leased primary Android user0")
        target = self.plan["target"]
        profile = INVENTORY_PROFILES[api]
        need(target["kind"] == profile["kind"], "Unobserved SDK/target-kind profile; no start")
        avd = None
        if target["kind"] == "emulator":
            names = self._call(["emu", "avd", "name"], timeout=remaining()).stdout.decode().splitlines()
            need(names and isinstance(target["avd"], str) and target["avd"] and names[0].strip() == target["avd"], "Wrong leased AVD")
            avd = names[0].strip()
        else:
            need(target["avd"] is None, "Physical target has unexpected AVD")
        self.runtime_profile = {"id": profile["id"], "api": api, "kind": target["kind"], "avd": avd, "bootId": boot}
        return self.runtime_profile

    def _instrumentation(self):
        # This same read-only method can be checked by the parent before allocating a plan/case.
        until = time.monotonic() + INVENTORY_TIMEOUT
        profile = self._verify_runtime_profile(until)
        remaining = until - time.monotonic()
        need(remaining > 0, "Runtime inventory60s deadline exhausted; no start")
        raw = self._shell("dumpsys", "activity", timeout=remaining, limit=INVENTORY_LIMIT)
        active = instrumentation_active(raw, profile["api"])
        need(time.monotonic() <= until, "Runtime inventory60s deadline exhausted; no start")
        return active

    def _installed(self, package):
        raw = self._shell("pm", "path", package).decode().strip().splitlines()
        need(len(raw) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_+=~./-]+/base\.apk", raw[0]), "Exact full installed APK required; no split replacement")
        path = raw[0][8:]
        return self._hash_paths([path], run_as=False)[path]

    def _hash_paths(self, paths, run_as=True):
        need(1 <= len(paths) <= 2048 and len(paths) == len(set(paths)), "Hash list bound/duplicates")
        for path in paths:
            if path.startswith("/"):
                need(not run_as and re.fullmatch(r"/data/app/[A-Za-z0-9_+=~./-]+/base\.apk", path), "Unsafe installed APK path")
            else:
                safe_relative(path)
        call = self._run_as if run_as else self._shell
        raw = call("sha256sum", *paths, timeout=5, limit=512000)
        return parse_hashes(raw, paths)

    def _defaults(self):
        # Fixed root-only name predicates: unrelated namespaces are never emitted/traversed.
        stage = "existence"
        try:
            if self._absent("no_backup"):
                return {}
            stage = "family-names"
            prefixes = self._run_as("find", "no_backup", "-mindepth", "1", "-maxdepth", "1",
                                    "(", "-name", "mobile-v1", "-o", "-name", "mobile-v1-*", ")", "-print",
                                    timeout=5, limit=262144).decode().splitlines()
            need(len(prefixes) <= 64 and len(set(prefixes)) == len(prefixes), "Default family inventory bound/duplicates")
            need(all(re.fullmatch(r"no_backup/mobile-v1(?:-[A-Za-z0-9_.-]+)?", name) for name in prefixes), "Unsafe/outside default family")
            if not prefixes:
                return {}
            prefixes.sort()
            stage = "family-types"
            unusual = self._run_as("find", *prefixes, "!", "-type", "d", "!", "-type", "f", "-print", timeout=5, limit=262144)
            need(unusual == b"", "Symlink or special entry in exact default families")
            stage = "family-files"
            paths = self._run_as("find", *prefixes, "-type", "f", timeout=5, limit=262144).decode().splitlines()
            need(len(paths) <= 1024 and len(paths) == len(set(paths)), "Default snapshot bound/duplicates")
            for path in paths:
                safe_relative(path)
                need(all(part not in ["", ".", ".."] for part in path.split("/")) and any(path == root or path.startswith(root + "/") for root in prefixes), "File outside exact default families")
            result = {}
            for offset in range(0, len(paths), 128):
                stage = "hash-batch-" + str(offset // 128)
                result.update(self._hash_paths(sorted(paths)[offset:offset + 128]))
            return {name[len("no_backup/"):]: value for name, value in result.items()}
        except Exception as error:
            # No stdout/file contents in diagnostics; keep the bounded failing stage for parent review.
            raise ValueError("Default snapshot stage=" + stage + ": " + type(error).__name__ + ": " + str(error)[:256]) from error

    def _read_exact(self, relative, optional=False):
        safe_relative(relative)
        result = self._call(["shell", "-T", shlex.join(["run-as", PACKAGE, "cat", relative])], check=False, timeout=2, limit=65536)
        if result.returncode != 0:
            need(optional and result.returncode == 1 and b"No such file or directory" in result.stderr + result.stdout, "Exact own proof read failed")
            return None
        return result.stdout

    def _absent(self, relative):
        safe_relative(relative)
        result = self._call(["shell", "-T", shlex.join(["run-as", PACKAGE, "test", "-e", relative])], check=False, timeout=2)
        need(result.returncode in [0, 1], "Own path existence query failed")
        return result.returncode == 1

    def _identity(self, pid):
        need(type(pid) is int and pid > 0, "Invalid external PID")
        path = "/proc/" + str(pid)
        raw = self._run_as("cat", path + "/cmdline", path + "/stat", path + "/status", timeout=2)
        cmdline, remainder = raw.split(b"\0", 1)
        need(cmdline.decode() == PACKAGE, "PID is not the selected package process")
        stat_raw, status_raw = remainder.lstrip(b"\0").split(b"\n", 1)
        status = status_raw.decode()
        uid = re.search(r"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", status, re.M)
        need(uid and {int(v) for v in uid.groups()} == {self.uid}, "Foreign process UID")
        stat = stat_raw.decode().rsplit(") ", 1)[1].split()
        ticks = int(stat[19])  # /proc/PID/stat field22, with field3 at index0.
        boot = self._shell("cat", "/proc/sys/kernel/random/boot_id", timeout=2).decode().strip()
        need(boot == self.lease["bootId"], "Device rebooted during process observation")
        return {"pid": pid, "uid": self.uid, "startTicks": ticks, "startElapsedMs": ticks * 1000 // self.hz, "bootId": boot}

    def _same_identity(self, a, b):
        return all(a[k] == b[k] for k in ["pid", "uid", "startTicks", "bootId"])

    def _api(self, path, optional=False):
        need(path in ["/health", "/stats"], "Controller cannot begin/end/reset/resolve/release a case")
        request = urllib.request.Request(self.config["controlOrigin"] + path, headers={"X-P8-Fixture": self.config["instanceId"]})
        try:
            with self.opener.open(request, timeout=1.5) as response:
                raw = response.read(262145)
                need(len(raw) <= 262144, "Fixture observation bound")
                return loads(raw)
        except urllib.error.HTTPError as error:
            if optional and path == "/stats" and error.code == 400:
                return None
            raise

    def _reverse(self):
        rows = []
        for line in self._call(["reverse", "--list"]).stdout.decode().splitlines():
            if not line.strip():
                continue
            values = line.split()
            need(len(values) == 3 and all(len(value) <= 4096 for value in values), "Unrecognized reverse row; preserve it")
            rows.append(values)
        return rows

    def prepare(self, plan):
        self.lock = open(self.lease["lockFile"], "a+b")
        os.chmod(self.lease["lockFile"], 0o600)
        fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        need(not self._instrumentation() and self._pid() is None, "Target has existing instrumentation/process; no takeover")
        for role, package in [("app", PACKAGE), ("runner", RUNNER)]:
            need(self._installed(package) == plan["candidate"]["artifacts"][role]["sha256"], "Install exact candidate with parent's checked installer first; no install/data reset here")
        self.uid = int(self._run_as("id", "-u").strip())
        self.hz = int(self._shell("getconf", "CLK_TCK").strip())
        need(1 <= self.hz <= 10000, "External process clock unavailable")
        self.baseline = self._defaults()
        case = plan["caseId"]
        need(self._absent("files/acceptance/p8-positive-commit/" + case) and self._absent("no_backup/p8-positive-commit-" + case), "Reserved UUID already exists; never reuse it")
        self.node = Capture([plan["tools"]["node"]["path"], str(Path(plan["fixtureSource"]) / (BASE + "form-lease/phase7/fixture.mjs")), "--start", str(self.runtime)], self.root / "sidecar.log")
        until = time.monotonic() + 15
        while not (self.runtime / "config.json").exists():
            need(self.node.process.poll() is None and time.monotonic() < until, "Owned fixture startup failed/timed out")
            time.sleep(0.05)
        self.config = loads((self.runtime / "config.json").read_bytes())
        ports = validate_config(self.config, plan["fixtureContract"])
        need(self.node.process.poll() is None and self.config["sidecarPid"] == self.node.process.pid, "Foreign/ended sidecar PID")
        health = self._api("/health")
        need(all(health[k] == v for k, v in self.config.items()) and health["onlyLoopback"] is True and health["realDesktop"] is health["realModel"] is False, "Foreign/non-synthetic health")
        need(self._api("/stats", optional=True) is None, "Fixture already owns another case")
        self.original_reverse = self._reverse()
        for port in ports:
            source = "tcp:" + str(port)
            need(not any(row[1] == source for row in self.original_reverse), "Existing reverse source is owned elsewhere")
        for port in ports:
            value = "tcp:" + str(port)
            self._call(["reverse", value, value])
            self.added.append(value)
        need(not self._instrumentation() and self._pid() is None, "Device became busy before writer; do not start")
        return {"config": self.config, "defaults": self.baseline, "resources": {"sidecarPid": self.node.process.pid, "ownReverse": self.added, "beforeReverse": self.original_reverse, "runtimeProfile": self.runtime_profile}}

    def _launch(self, role, config, plan, writer_raw=None):
        args = ["am", "instrument", "-w", "-r", "-e", "class", plan["roles"][role],
                "-e", "p8CommitColdPhase", role, "-e", "p8CommitColdCaseId", plan["caseId"],
                "-e", "p8CommitConfig", canonical(config).decode(), "-e", "p8CommitBuild", canonical(plan["candidate"]["build"]).decode()]
        if writer_raw is not None:
            args += ["-e", "p8CommitWriterProof", writer_raw.decode()]
        args.append(COMPONENT)
        return Capture([self.adb, "-s", self.serial, "shell", "-T", shlex.join(args)], self.root / (role + ".log"))

    def launch_writer(self, config, plan):
        need(not self._instrumentation() and self._pid() is None, "Existing target owner; no writer start")
        self.launch_elapsed = self._uptime()
        self.writer = self._launch("writer", config, plan)

    def await_writer_ready(self, plan, timeout):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            need(self.writer.process.poll() is None and not self.writer.ok, "Writer completed; it is never a JUnit pass")
            pid = self._pid()
            if pid is not None and self.writer_identity is None:
                identity = self._identity(pid)
                need(identity["startElapsedMs"] + 20 >= self.launch_elapsed, "Writer is an older process")
                self.writer_identity = identity
            raw = self._read_exact(plan["writerReadyRelative"], optional=True)
            if raw is not None:
                need(self.writer_identity is not None, "No externally observed writer")
                return raw
            time.sleep(0.1)
        raise TimeoutError("No original writer-ready checkpoint")

    def held_snapshot(self, writer):
        self.writer_proof = writer
        need(self.writer_identity["pid"] == writer["pid"], "Writer proof belongs to another PID")
        # Critical18s begins at the native ready time, not at host observation time.
        age = self._uptime() - writer["readyElapsed"]
        self.deadline = time.monotonic() + max(0, (18000 - age - 20) / 1000)
        need(0 <= age < 15000, "Writer checkpoint already too old; fail without reader")
        identity = self._identity(writer["pid"])
        need(self._same_identity(identity, self.writer_identity) and self._pid() == writer["pid"], "Writer PID was reused")
        hashes = self._hash_paths(lineage_paths(writer))  # One read-only multi-file command before stop.
        stats = self._api("/stats")
        validate_stats(stats, writer)
        return {"identity": identity, "cipherHashes": hashes, "stats": stats}

    def interrupt_owned_writer(self, writer, held):
        need(self.writer.process.poll() is None and not self.writer.ok and not self.writer.failed, "Writer ended/failed before interruption")
        need(self._same_identity(self._identity(writer["pid"]), held["identity"]) and self._pid() == writer["pid"], "Writer ownership changed; no stop")
        self._shell("am", "force-stop", "--user", "0", PACKAGE, timeout=3)
        while self._pid() is not None:
            need(time.monotonic() < self.deadline, "Writer did not stop within18s")
            time.sleep(0.05)
        stopped = self._uptime()
        self.deadline = None
        self.writer_stopped = True
        self.writer.finish_client()
        return {"pid": writer["pid"], "pidGone": True, "identityUnchanged": True,
                "stoppedElapsedMs": stopped, "writerJUnitPassed": self.writer.ok}

    def verify_stopped_state(self, writer, raw, held, defaults):
        need(self._pid() is None and not self._instrumentation(), "Another active target owner before reader")
        need(self._read_exact(self.plan["writerReadyRelative"]) == raw, "Original writer-ready changed")
        need(self._defaults() == defaults and self._hash_paths(lineage_paths(writer)) == held["cipherHashes"], "Original bytes changed after stop")
        validate_stats(self._api("/stats"), writer)

    def run_reader(self, config, plan, writer_raw):
        need(self._pid() is None and not self._instrumentation(), "Target is no longer idle; reader not started")
        self.reader_started = True
        self.reader = self._launch("reader", config, plan, writer_raw)
        until = time.monotonic() + 35
        identity = None
        while self.reader.process.poll() is None:
            if identity is None:
                pid = self._pid()
                if pid is not None:
                    identity = self._identity(pid)
            need(time.monotonic() < until, "Reader timeout; seal without target force-stop/retry")
            time.sleep(0.05)
        self.reader.finish_client()
        need(identity is not None, "Reader completed without external PID lineage evidence")
        text = self.reader.tail.decode(errors="replace")
        passed = self.reader.process.returncode == 0 and self.reader.ok and not self.reader.failed and bool(re.search(r"OK \(1 tests?\)", text))
        need(set(re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", text)) == {"1"}, "Reader ran unexpected test count")
        raw = self._read_exact("files/acceptance/p8-positive-commit/" + plan["caseId"] + "/reader-proof.json") if passed else None
        return {"junitPassed": passed, "nativeMethods": 1 if passed else 0, "identity": identity, "proof": raw}

    def verify_reader_state(self, writer, raw, held, defaults):
        need(self._read_exact(self.plan["writerReadyRelative"]) == raw, "Writer proof altered by reader")
        need(self._defaults() == defaults and self._hash_paths(lineage_paths(writer)) == held["cipherHashes"], "Reader changed original encrypted bytes")
        need(self._api("/stats", optional=True) is None, "Reader did not end its original case")
        closed = loads((self.runtime / (self.plan["caseId"] + ".json")).read_bytes())
        return {"closedCase": closed}

    def seal_failure(self):
        self.deadline = None
        result = {"caseId": self.plan["caseId"], "retryAllowed": False, "readerStarted": self.reader_started}
        if self.config is not None:
            result["activeCase"] = self._api("/stats", optional=True)
            closed = self.runtime / (self.plan["caseId"] + ".json")
            if closed.exists():
                result["closedCase"] = loads(closed.read_bytes())
        for name, operation in [("defaults", self._defaults), ("targetPid", self._pid)]:
            try:
                result[name] = operation()
            except Exception as error:
                result[name + "ReadError"] = type(error).__name__
                result[name + "ReadDetails"] = str(error)[:384]
        if self.writer_proof is not None:
            try:
                result["cipherHashes"] = self._hash_paths(lineage_paths(self.writer_proof))
            except Exception as error:
                result["cipherReadError"] = type(error).__name__
        return result

    def cleanup(self):
        self.deadline = None
        result = {"onlyOwnedResources": True, "readerForceStopped": False, "dataDeleted": False, "caseEndedByController": False}
        # Failure cleanup never force-stops a device process. Parent owns any recovery.
        result["writerMayStillRun"] = self.writer is not None and not self.writer_stopped
        result["readerMayStillRun"] = self.reader is not None and self.reader.process.poll() is None
        for child in [self.writer, self.reader]:
            if child:
                child.finish_client(timeout=1)
        if self.node:
            # Popen handle owns the exact child. Never terminate a PID supplied by a config file.
            if self.node.process.poll() is None:
                self.node.process.terminate()
            self.node.finish_client(timeout=3)
            result["ownSidecarStopped"] = self.node.process.poll() is not None
        removals = []
        for value in self.added:
            try:
                rows = [row for row in self._reverse() if row[1] == value]
                if len(rows) == 1 and rows[0][2] == value:
                    self._call(["reverse", "--remove", value])
                    gone = not any(row[1] == value and row[2] == value for row in self._reverse())
                    removals.append({"source": value, "removed": gone, **({} if gone else {"error": "Own mapping still present after remove"})})
                else:
                    removals.append({"source": value, "removed": False, "reason": "Exact own mapping absent/changed; preserved"})
            except Exception as error:
                removals.append({"source": value, "removed": False, "error": type(error).__name__})
        result["ownReverseRemovals"] = removals
        result["complete"] = (self.node is None or result.get("ownSidecarStopped") is True) and not result["readerMayStillRun"] and not result["writerMayStillRun"] and not any("error" in row for row in removals)
        if self.lock:
            self.lock.close()
        return result
