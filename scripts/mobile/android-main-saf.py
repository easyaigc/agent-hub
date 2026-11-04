#!/usr/bin/env python3
"""Finite local P7 Main SAF CLI. No install/build/kill/remote shell API or retry of any input."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import shlex
import stat
import subprocess
import sys
import time
import uuid
sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


first = module("p7_saf_source", "android-nonempty-device-first.py")
pair = module("p7_saf_reads", "android-nonempty-device-pair.py")
capture = module("p7_saf_sdk", "android-sdk-capture.py")
require = first.require
encoded = first.encoded
sha = lambda raw: hashlib.sha256(raw).hexdigest()
PROFILE = "p7-main-saf-v2"
TOOL = "scripts/mobile/android-main-saf.py"
APP = first.PACKAGE
TEST = first.TEST_PACKAGE
BASE = "com.agenthub.android."
SELECTORS = {"PRODUCER": BASE + "NativeMigrationMainSafProducerTest#produceOnlySyntheticImportCiphertext",
    "IMPORT": BASE + "NativeMigrationMainUiTest#actualMainSafOpenStagesOnlyOwnedEncryptedDocument",
    "EXPORT": BASE + "NativeMigrationMainUiTest#actualMainSafCreateWritesOnlyOwnedEncryptedDocument"}
HELPER = BASE + "OwnedMainMigrationDocuments$LocalLeaseFiles"
PROVIDER = TEST + ":p7MainSaf"
TRANSPORT = "adb-shell-v2-no-pty"
NAMESPACE = "files/p7-main-saf-owned-v1"
CAP = 16384
_HANDOFF_LIVE = {}  # Strong local owners remain reachable until both actual waits and FD closes.


def profile_v2(p, root):
    first.exact(p, {"schema", "package", "systemUid", "systemApkSha256", "api", "controlObservationSha256", "navigation", "directoryTitleId", "fileNameId", "saveId", "openButton", "owned"})
    require(p["package"] == root["docsPackage"] == "com.google.android.documentsui" and type(p["api"]) is int and p["api"] == 33, "Exact observed Google API33 DocsUI purpose")
    require(type(p["systemUid"]) is int and p["systemUid"] == root["docsUid"], "Independent system UID binding")
    first.sha(p["systemApkSha256"]); first.sha(p["controlObservationSha256"])
    require(p["directoryTitleId"] == p["package"] + ":id/breadcrumb_text" and p["fileNameId"] == "android:id/title" and p["saveId"] == "android:id/button1", "Only independently observed control IDs")
    nav = p["navigation"]; first.exact(nav, {"state", "role", "buttonBounds", "toolbarBounds"})
    require(nav["state"] == "OBSERVED" and nav["role"] == "toolbar-show-roots-en-v1", "Only observed fixed Show roots toolbar role")
    for key in ("buttonBounds", "toolbarBounds"):
        a = nav[key]; require(type(a) is list and len(a) == 4 and all(type(x) is int and 0 <= x <= 4096 for x in a) and a[0] < a[2] and a[1] < a[3], "Finite observed screen rectangle")
    b, t = nav["buttonBounds"], nav["toolbarBounds"]
    require(b[0] == t[0] and b[1] == t[1] and b[3] == t[3] and b[2] < t[2], "Navigation is the left toolbar child")
    first.exact(p["openButton"], {"state", "resourceId"})
    require(p["openButton"] == {"state": "UNKNOWN", "resourceId": None}, "Unobserved Open remains callback-only UNKNOWN")
    first.exact(p["owned"], {"rootTitle", "documentTitle"})
    require(all(row == {"state": "RUNTIME_REQUIRED", "resourceId": None} for row in p["owned"].values()), "Owned root/document selectors are never pre-observed or guessed")


def approval(args):
    raw = capture.private_bytes(args.root_approval, CAP)
    require(sha(raw) == args.root_approval_sha256, "Independent immutable Root approval required")
    value = capture.parse_json(raw)
    first.exact(value, {"schema", "operationId", "run", "kind", "bootUuid", "uid", "testUid", "docsUid", "docsPackage",
        "serialSha256", "adbSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profile", "profileSha256", "expiresWallMillis", "transportContractReviewSha256"})
    require(value["schema"] == "p7-main-saf-root-approval-v1" and value["kind"] in ("IMPORT", "EXPORT"), "Closed Root purpose")
    for key in ("operationId", "run", "bootUuid"): first.canonical_uuid(value[key])
    for key in ("uid", "testUid", "docsUid"): first.integer(value[key], 1, 2147483647)
    require(len({value[key] for key in ("uid", "testUid", "docsUid")}) == 3, "Exact independent UIDs")
    for key in ("serialSha256", "adbSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "transportContractReviewSha256"): first.sha(value[key])
    require(value["serialSha256"] == sha(args.serial.encode()) and value["adbSha256"] == first.digest(args.adb), "Original local device/adb binding")
    p = value["profile"]
    if type(p) is dict and p.get("schema") == "p7-main-saf-docsui-role-profile-v2":
        profile_v2(p, value)
    else:
        require(type(p) is dict and set(p) in ({"package", "rootsButtonId", "rootTitleId", "directoryTitleId", "documentTitleId", "fileNameId", "saveId"},
            {"package", "rootsButtonId", "rootTitleId", "directoryTitleId", "documentTitleId", "fileNameId", "saveId", "openButtonId"}), "Finite observed AX profile")
        require(p["package"] == value["docsPackage"] and p["package"] in ("com.android.documentsui", "com.google.android.documentsui"), "Exact system DocumentsUI")
        require(all(type(v) is str and re.fullmatch(r"(?:" + re.escape(p["package"]) + r"|android):id/[A-Za-z0-9_]+", v) for k, v in p.items() if k != "package"), "Observed resource IDs required")
    require(sha(encoded(p)) == value["profileSha256"], "AX profile was not independently pinned")
    require(type(value["expiresWallMillis"]) is int and 0 < value["expiresWallMillis"] - int(time.time() * 1000) <= 600000, "Root lease must be current and finite")
    return raw, value


def admitted(args, root):
    repo = Path(args.repo).resolve()
    require(Path(__file__).resolve() == repo / TOOL, "Reviewed in-repo adapter required")
    seal_raw = capture.private_bytes(Path(args.build_bundle) / "capture-seal.json", 512 * 1024)
    review_raw = capture.private_bytes(Path(args.build_bundle) / "workspace-review.json", 131072)
    require(sha(seal_raw) == root["sdkSealSha256"] and sha(review_raw) == root["workspaceReviewSha256"], "Root SDK/review pin differs")
    build = capture.verify_build_bundle(args.build_bundle, first, repo, PROFILE)
    guard = first.read_guard(Path(args.build_bundle) / "source-before.json")
    require(build["sealSha256"] == root["sdkSealSha256"] and sha(encoded(guard)) == root["sourceGuardSha256"], "Root source/build guard differs")
    require(first.same(guard, capture.main_saf_v2_source_guard(first, repo, guard["sourceOverlay"])), "Exact current SAF sources differ")
    require(first.digest(__file__) == guard["sources"][TOOL] and first.digest(args.adb) == build["tools"]["adb"]["sha256"] and
        Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve(), "Actual captured adapter/adb required")
    return guard, build


class Device(pair.Device):
    def uid(self, package):
        require(package in (APP, TEST, self.args.docs_package), "Finite UID target")
        raw = self.success(["pm", "list", "packages", "-U", package], CAP).decode()
        rows = {}
        for line in raw.splitlines():
            match = re.fullmatch(r"package:([A-Za-z0-9_.]+) uid:([0-9]{1,10})", line)
            require(match and package in match[1] and match[1] not in rows, "Complete unique pm rows")
            rows[match[1]] = int(match[2])
        require(package in rows, "Exact package UID absent")
        return rows[package]

    def test_apk(self):
        raw = self.success(["pm", "path", TEST], CAP).decode().strip()
        require(re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~-]+/base.apk", raw), "Exactly one test APK path")
        return raw.removeprefix("package:")

    def snapshot(self, root, build):
        require(self.boot() == root["bootUuid"] and self.success(["getprop", "ro.build.version.sdk"]).strip() == b"33", "Same API33 boot required")
        require(self.installed() == {APP: build["artifacts"]["app"]["sha256"], TEST: build["artifacts"]["test"]["sha256"]}, "Installed APK mismatch")
        require({p: self.uid(p) for p in (APP, TEST, root["docsPackage"])} == {APP: root["uid"], TEST: root["testUid"], root["docsPackage"]: root["docsUid"]}, "Installed UID drift")
        if root["profile"].get("schema") == "p7-main-saf-docsui-role-profile-v2":
            path = self.success(["pm", "path", root["docsPackage"]], CAP).decode().strip()
            require(re.fullmatch(r"package:/(?:system|system_ext|product|data/app)/[A-Za-z0-9_./+=~-]+\.apk", path), "One exact installed system DocsUI APK path")
            path = path.removeprefix("package:")
            digest = self.success(["sha256sum", path], CAP).decode().strip()
            require(re.fullmatch(root["profile"]["systemApkSha256"] + r"\s+" + re.escape(path), digest), "Installed DocsUI APK drift")
        action = "android.intent.action.OPEN_DOCUMENT" if root["kind"] == "IMPORT" else "android.intent.action.CREATE_DOCUMENT"
        resolved = self.success(["cmd", "package", "resolve-activity", "--brief", "-a", action, "-c", "android.intent.category.OPENABLE", "-t", "application/octet-stream"], CAP).decode().splitlines()
        require(resolved and re.fullmatch(re.escape(root["docsPackage"]) + r"/[A-Za-z0-9_.$]+", resolved[-1]), "Exact system action owner changed")

    def exit_owner(self, package, original):
        require(package in (APP, TEST) and original["uid"] == (self.args.uid if package == APP else self.args.test_uid), "Exact local process owner")
        first.integer(original["pid"], 1, 2147483647)
        require(re.fullmatch(r"[0-9]{1,24}", original["startTicks"]) and self.boot() == original["bootUuid"], "Same-boot original process identity")
        pid = original["pid"]
        a = self.shell(["run-as", package, "cat", f"/proc/{pid}/stat"])
        b = self.shell(["run-as", package, "ls", "-d", f"/proc/{pid}"])
        if a.returncode == 0:
            require(not a.stderr and a.stdout.startswith(f"{pid} (".encode()), "Complete proc stat")
            ticks = a.stdout.decode().rsplit(") ", 1)[1].split()[19]
            require(re.fullmatch(r"[0-9]{1,24}", ticks), "Canonical process start")
            if ticks == original["startTicks"]:
                status = self.success(["run-as", package, "cat", f"/proc/{pid}/status"], CAP)
                argv = self.success(["run-as", package, "cat", f"/proc/{pid}/cmdline"], CAP)
                uids = re.findall(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$", status, re.M)
                require(len(uids) == 1 and len(set(uids[0])) == 1 and int(uids[0][0]) == original["uid"] and sha(argv) == original["argvSha256"], "Original live UID/argv changed; never signal it")
                return None
            require(b.returncode == 0 and not b.stderr and b.stdout.strip() == f"/proc/{pid}".encode(), "Replacement directory race")
            kind = "pid-start-replaced"
        else:
            for result, path in ((a, f"/proc/{pid}/stat"), (b, f"/proc/{pid}")):
                raw = result.stdout + result.stderr
                require(result.returncode != 0 and path.encode() in raw and b"No such file or directory" in raw and b"Permission denied" not in raw and len(raw) <= 1024, "Independent ENOENT required; read failure is unknown")
            kind = "proc-enoent-and-directory-absent"
        require(self.boot() == original["bootUuid"], "Boot changed during exit read")
        return {"process": original, "kind": kind, "statReadSha256": sha(a.stdout + a.stderr), "directoryReadSha256": sha(b.stdout + b.stderr)}

    def require_shell_v2(self, root):
        # Expected contract only in this source candidate. Root must approve its independently
        # checked ADB contract digest before a future run; no -x/-n/PTY or transport fallback.
        raw = self.call(["features"], CAP)
        require(raw.returncode == 0 and not raw.stderr, "Cannot establish shell_v2 support")
        # The pinned adb CLI prints one feature per line; its wire format is not CLI output.
        features = raw.stdout.decode().strip().splitlines()
        require(1 <= len(features) <= 128 and len(set(features)) == len(features) and
            all(re.fullmatch(r"[a-z0-9_]+", item) for item in features) and "shell_v2" in features,
            "shell_v2 must be advertised; never fall back to exec/legacy shell")
        first.sha(root["transportContractReviewSha256"])
        return {"transport": TRANSPORT, "effectiveFeaturesSha256": sha(raw.stdout),
            "rootContractReviewSha256": root["transportContractReviewSha256"]}

    def provider_observation(self, output, phase, root, absent_required=False):
        require(phase in ("before-any-side-effect", "before-handoff", "before-main", "owned-provider"), "Finite provider observation")
        result = {"schema": "p7-main-saf-provider-observation-v1", "phase": phase, "expectedName": PROVIDER,
            "expectedUid": root["testUid"], "bootUuid": root["bootUuid"], "run": root["run"], "operationId": root["operationId"], "status": "UNKNOWN"}
        try:
            require(self.boot() == root["bootUuid"], "Provider preflight boot changed")
            first_pid = self.shell(["pidof", PROVIDER], CAP)
            result["pidofSha256"] = sha(encoded({"code": first_pid.returncode, "stdoutSha256": sha(first_pid.stdout), "stderrSha256": sha(first_pid.stderr)}))
            if first_pid.returncode == 1 and not first_pid.stdout and not first_pid.stderr:
                second_pid = self.shell(["pidof", PROVIDER], CAP)
                require(second_pid.returncode == 1 and not second_pid.stdout and not second_pid.stderr and self.boot() == root["bootUuid"], "Provider absence changed; retain")
                result.update({"status": "absent", "twoExactPidofAbsences": True, "process": None})
            else:
                require(first_pid.returncode == 0 and not first_pid.stderr and re.fullmatch(rb"[1-9][0-9]{0,9}\n?", first_pid.stdout), "Provider owner unknown/multiple")
                pid = int(first_pid.stdout)
                a = self.success(["run-as", TEST, "cat", f"/proc/{pid}/stat"], CAP)
                status = self.success(["run-as", TEST, "cat", f"/proc/{pid}/status"], CAP)
                argv = self.success(["run-as", TEST, "cat", f"/proc/{pid}/cmdline"], CAP)
                b = self.success(["run-as", TEST, "cat", f"/proc/{pid}/stat"], CAP)
                ticks = lambda raw: raw.decode().rsplit(") ", 1)[1].split()[19]
                uid = re.findall(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$", status, re.M)
                require(a.startswith(f"{pid} (".encode()) and b.startswith(f"{pid} (".encode()) and ticks(a) == ticks(b) and re.fullmatch(r"[0-9]{1,24}", ticks(a)), "Racing provider start")
                require(len(uid) == 1 and len(set(uid[0])) == 1 and int(uid[0][0]) == root["testUid"], "Actual provider UID differs")
                require(pair.exact_package_cmdline(argv, PROVIDER) and self.boot() == root["bootUuid"], "Exact provider raw argv/boot required")
                result.update({"status": "present", "process": {"pid": pid, "uid": root["testUid"], "startTicks": ticks(a),
                    "bootUuid": root["bootUuid"], "argvSha256": sha(argv), "argvExactName": PROVIDER},
                    "rawSha256": {"statBefore": sha(a), "statAfter": sha(b), "status": sha(status), "argv": sha(argv)},
                    "argvBytes": len(argv), "trailingNulBytes": len(argv) - len(argv.rstrip(b"\0"))})
        except BaseException as error:
            result["failureType"] = type(error).__name__
            capture.write_json(output / (phase + "-provider.json"), result)
            raise
        capture.write_json(output / (phase + "-provider.json"), result)
        if absent_required: require(result["status"] == "absent", "Preexisting provider is retained; reject all new task writes, never stop/adopt it")
        return result

    def helper_argv(self, root, command, extra=()):
        require(command in ("prepare-import", "prepare-export", "audit-import", "audit-export"), "Finite helper command")
        argv = ["run-as", TEST, "/system/bin/app_process", "-Djava.class.path=" + self.test_apk(), "/system/bin", HELPER,
            command, root["run"], str(root["testUid"]), str(root["uid"]), str(root["docsUid"]), *extra]
        return self.prefix + ["shell", "-T", shlex.join(argv)]


def lease_now(root):
    require(0 < root["expiresWallMillis"] - int(time.time() * 1000) <= 600000, "Original short lease expired; retain same intent, no new command")


def pipeline(output, prefix, source, sink, operation, repo, command, root):
    """Expected shell_v2 contract: binary source stdout -> sink stdin, separate stderr/exit.
    Both fixed shell -T clients remain owned through waits/EOF/FD closes. The ciphertext is
    never decoded, logged or stored on Host. Any transport/JSON/exit error is sticky UNKNOWN.
    """
    require(command in ("prepare-import", "prepare-export", "audit-import", "audit-export"), "Finite helper command")
    lease_now(root)
    intent = {"schema": "p7-main-saf-handoff-intent-v2", "operationId": operation, "sourceArgv": source,
        "sinkArgv": sink, "command": command, "transport": TRANSPORT, "rootContractReviewSha256": root["transportContractReviewSha256"],
        "timeoutSeconds": 60, "createdWallNs": time.time_ns()}
    capture.write_json(output / (prefix + "-intent.json"), intent)
    owners = []; pipes = {}; buffers = {}; eof = set(); codes = {}; primary = None; parent_source_closed = source is None
    deadline = time.monotonic() + 60; starts = {}; result = None
    _HANDOFF_LIVE[operation] = owners
    try:
        if source is not None:
            child = subprocess.Popen(source, cwd=repo, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, umask=0o077)
            owners.append(("source", child)); pipes["source-stderr"] = child.stderr
            starts["source"] = capture.owner_start(child)
        child = subprocess.Popen(sink, cwd=repo, stdin=(owners[0][1].stdout if source is not None else subprocess.DEVNULL), stdout=subprocess.PIPE, stderr=subprocess.PIPE, umask=0o077)
        owners.append(("sink", child)); pipes["sink-stdout"] = child.stdout; pipes["sink-stderr"] = child.stderr
        starts["sink"] = capture.owner_start(child)
        if source is not None: owners[0][1].stdout.close(); parent_source_closed = True
    except BaseException as error: primary = error
    finally:
        if source is not None and owners and not parent_source_closed:
            try: owners[0][1].stdout.close(); parent_source_closed = True
            except BaseException as error: primary = primary or error
        for key, pipe in pipes.items():
            buffers[key] = bytearray()
            try: os.set_blocking(pipe.fileno(), False)
            except BaseException as error: primary = primary or error
        while len(codes) != len(owners) or len(eof) != len(pipes):
            if time.monotonic() >= deadline: primary = primary or TimeoutError("Original transport unknown; no fallback/recopy")
            active = {k: v for k, v in pipes.items() if k not in eof}
            try: ready, _, _ = select.select(list(active.values()), [], [], 0.1)
            except BaseException as error: primary = primary or error; ready = list(active.values())
            for key, pipe in active.items():
                if pipe not in ready: continue
                try:
                    raw = os.read(pipe.fileno(), 65536)
                    if not raw: eof.add(key); continue
                    if len(buffers[key]) + len(raw) > CAP: primary = primary or ValueError("Finite helper summary exceeded")
                    buffers[key].extend(raw[:max(0, CAP - len(buffers[key]))])
                except BlockingIOError: pass
                except BaseException as error: primary = primary or error
            for role, child in owners:
                if role in codes: continue
                try: codes[role] = child.wait(timeout=0.1)
                except subprocess.TimeoutExpired: pass
                except BaseException as error: primary = primary or error
        for _, child in owners:
            for stream in (child.stdout, child.stderr):
                try:
                    if stream is not None: stream.close()
                except BaseException as error: primary = primary or error
        if primary is None:
            try:
                require(len(owners) == (2 if source is not None else 1) and all(code == 0 for code in codes.values()) and
                    all(not raw for key, raw in buffers.items() if key.endswith("stderr")), "shell_v2 command/remote exit failed; preserve transportUnknown")
                result = capture.parse_json(bytes(buffers["sink-stdout"]))
                require(result["schema"] == "p7-main-saf-local-lease-v1" and result["run"] == root["run"] and result["kind"] == root["kind"] and
                    result["command"] == command and result["operationId"] == operation and result["helperOutcome"] == "complete" and
                    result["stdinEof"] is True and result["stdinClosed"] is True, "Exact completed named helper/EOF receipt required")
                facts = result["helperProcess"]; first.integer(facts["pid"], 1, 2147483647); first.sha(facts["argvSha256"])
                require(facts["uid"] == root["testUid"] and facts["bootUuid"] == root["bootUuid"] and re.fullmatch(r"[0-9]{1,24}", facts["startTicks"]), "Actual remote helper self identity differs")
            except BaseException as error: primary = error
        receipt = {"schema": "p7-main-saf-handoff-wait-v2", "operationId": operation, "intentSha256": sha(encoded(intent)), "transport": TRANSPORT,
            "owners": [{"role": role, "pid": child.pid, "childStartObservation": starts.get(role), "localAdbReturnCode": codes.get(role), "actualWaitReturned": role in codes} for role, child in owners],
            "parentSourceFdClosed": parent_source_closed, "allReadPipeEof": len(eof) == len(pipes), "allHostPipeFdsClosed": all(p.closed for p in pipes.values()),
            "streamSha256": {k: sha(bytes(v)) for k, v in buffers.items()}, "helperReceiptSha256": sha(encoded(result)) if result else None,
            "transportUnknown": primary is not None, "remoteExitFrameObserved": False,
            "successfulExitCorrelatedWithNamedReceipt": primary is None, "nonzeroOrDisconnectedExitIsUnknown": True, "independentHelperExitVerified": False,
            "status": "UNKNOWN" if primary else "shell-v2-exit-and-named-receipt-observed"}
        capture.write_json(output / (prefix + "-wait.json"), receipt)
        if all(role in codes for role, child in owners) and all(p.closed for p in pipes.values()): _HANDOFF_LIVE.pop(operation)
    if primary: raise primary
    capture.write_json(output / (prefix + "-summary.json"), result)
    return result, receipt


def native(device, args, root, build, phase, input_pin=None):
    output = Path(args.output_dir); operation = str(uuid.uuid4())
    binding = {"schema": "p7-main-saf-admission-v1", "profile": PROFILE, "run": root["run"], "phase": phase,
        "operationId": operation, "selector": SELECTORS[phase], **{key: root[key] for key in ("uid", "testUid", "docsUid", "bootUuid", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "expiresWallMillis", "transportContractReviewSha256")},
        "appApkSha256": build["artifacts"]["app"]["sha256"], "testApkSha256": build["artifacts"]["test"]["sha256"], "certificateSha256": build["artifacts"]["app"]["certificateSha256"], "inputBytes": input_pin["bytes"] if input_pin else None, "inputSha256": input_pin["sha256"] if input_pin else None}
    prefix = phase.lower(); capture.write_json(output / (prefix + "-admission.json"), binding)
    arguments = {"class": SELECTORS[phase], "migrationInboxRun": root["run"], "migrationMainSafAdmissionJson": encoded(binding).decode(), "migrationMainSafAdmissionSha256": sha(encoded(binding))}
    if phase != "PRODUCER":
        arguments.update({"migrationMainSafOwnedLease": "1", "migrationMainSafRun": root["run"], "migrationMainSafProviderPrepared": "1", "migrationMainSafProfile": encoded(root["profile"]).decode()})
        if phase == "IMPORT": arguments.update({"migrationMainSafInputBytes": str(input_pin["bytes"]), "migrationMainSafInputSha256": input_pin["sha256"]})
    capture.write_json(output / (prefix + "-arguments.json"), arguments)
    absent = device.shell(["pidof", APP]); require(absent.returncode == 1 and not absent.stdout and not absent.stderr, "Fresh absent original process required; never force-stop")
    command = ["am", "instrument", "-w", "-r"]
    for key, text in sorted(arguments.items()): command.extend(["-e", key, text])
    command.append(first.RUNNER); argv = device.prefix + ["shell", "-T", shlex.join(command)]
    if phase != "PRODUCER": device.provider_observation(output, "before-main", root, absent_required=True)
    observed = {}; provider_observed = {}
    def tick():
        if not observed:
            p = device.shell(["pidof", APP])
            if p.returncode == 1 and not p.stdout and not p.stderr: return
            require(p.returncode == 0 and not p.stderr, "Original live observation failed")
            live = device.live(); observed.update(live)
            capture.write_json(output / (prefix + "-original-process.json"), live)
        if phase != "PRODUCER" and not provider_observed:
            p = device.shell(["pidof", PROVIDER], CAP)
            if p.returncode == 1 and not p.stdout and not p.stderr: return
            provider_observed.update(device.provider_observation(output, "owned-provider", root))
            require(provider_observed["status"] == "present", "Original provider observation changed")
    lease_now(root)
    result = capture.run_process(output, prefix + "-runner", argv, Path(args.repo).resolve(), environment={}, timeout=180, child_umask=0o077, tick=tick)
    wait = capture.verify_process(output, prefix + "-runner", argv=argv, cwd=Path(args.repo).resolve(), environment={}, child_umask=0o077)
    require(observed and wait["stdout"] == result["stdout"] and not wait["stderr"], "Actual original wait/dual EOF required")
    text = result["stdout"].decode(); require("OK (1 test)" in text and "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text and "Process crashed" not in text, "Exactly one successful original JUnit selector required")
    expected_class, expected_method = SELECTORS[phase].split("#"); codes = []; admissions = []; cleanup = []; producers = []; fields = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            require(sep and key not in fields, "Unique instrumentation status field"); fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
            special = set(fields) & {"p7MainSafAdmission", "p7MainUiCleanup", "p7MainSafProducer"}
            if special:
                require(len(special) == 1 and set(fields) == special and code == "0", "Closed P7 status")
                key = next(iter(special)); value = capture.parse_json(fields[key].encode())
                {"p7MainSafAdmission": admissions, "p7MainUiCleanup": cleanup, "p7MainSafProducer": producers}[key].append(value)
            else:
                require(set(fields) <= {"id", "stream", "numtests", "class", "test", "current", "stack"} and fields.get("class") == expected_class and fields.get("test") == expected_method and fields.get("numtests") == "1" and fields.get("current") == "1" and "stack" not in fields, "Exact named JUnit case")
                codes.append(code)
            fields = {}
    require(not fields and codes == ["1", "0"] and text.count("INSTRUMENTATION_CODE: -1") == 1 and len(admissions) == 1, "One original Native admission/completion")
    accepted = admissions[0]; require(first.same(accepted["binding"], binding) and accepted["admissionSha256"] == sha(encoded(binding)), "Native admission differs")
    facts = accepted["nativeFacts"]
    for nk, hk in (("pid", "pid"), ("uid", "uid"), ("startTicks", "startTicks"), ("bootUuid", "bootUuid"), ("argvSha256", "cmdlineSha256")):
        require(facts[nk] == observed[hk], "Native actual process differs from independent live fact")
    exit_proof = device.exit_owner(APP, facts); require(exit_proof is not None, "Original Native process still alive; no next phase")
    capture.write_json(output / (prefix + "-exit.json"), exit_proof)
    if phase == "PRODUCER":
        require(len(producers) == 1 and not cleanup, "One producer-only receipt required")
        proof = producers[0]; relative = f"files/p7-main-saf-producer-v1/{root['run']}/producer.json"
        require(proof["schema"] == "p7-main-saf-producer-v1" and proof["run"] == root["run"] and first.same(proof["admission"], accepted) and
            proof["ciphertextFdClosed"] is True and proof["directorySynced"] is True and proof["sessionOwnersAcquired"] == proof["storageOwnersAcquired"] == 0, "Producer owner/FD proof missing")
    else:
        require(len(cleanup) == 1 and not producers, "One Main cleanup receipt required")
        proof = cleanup[0]; relative = f"files/acceptance/P7-inbox-{root['run']}/main-ui-result.json"
        if root["profile"].get("schema") == "p7-main-saf-docsui-role-profile-v2":
            require(first.same(proof["saf"]["admission"], accepted), "Runtime role proof must belong to the actual admitted Native process")
    persisted = capture.parse_json(device.success(["run-as", APP, "cat", relative], CAP))
    require(first.same(persisted, proof), "Independent exact private receipt differs")
    capture.write_json(output / (prefix + "-native-proof.json"), proof)
    if phase != "PRODUCER" and not provider_observed:
        provider_observed.update(device.provider_observation(output, "owned-provider", root))
        require(provider_observed["status"] == "present", "No independent exact new provider owner; retain unknown")
    return proof, exit_proof, provider_observed


def join(root, proof, audits, original_exit, provider_observed, preflight):
    require(all(proof.get(key) is True for key in ("completed", "storageWriterRetired", "privateSessionRetired", "activityClosed", "installationReleased", "postJoinProviderCheckPassed", "safCallbackCompleted")) and
        proof["testFailed"] is False and proof["cleanupFailed"] is False and proof["safAccepted"] is False, "Actual Main owners/callback not joined")
    saf = proof["saf"]; require(saf["run"] == root["run"] and saf["kind"] == root["kind"] and saf["callbackAndGrantObserved"] is True and saf["providerAuditVerified"] is False, "Native exact OS callback/grant required")
    if root["profile"].get("schema") == "p7-main-saf-docsui-role-profile-v2":
        roles = saf["runtimeOwnedRoles"]; first.exact(roles, {"rootTitle", "documentTitle", "directoryWindowId", "directoryTextSha256", "profileSha256"})
        first.integer(roles["directoryWindowId"], 0, 2147483647)
        title = "AH_P7_" + root["run"].replace("-", "")
        filename = "agent-hub-p7-" + root["kind"].lower() + "-" + root["run"] + ".codexmigration"
        require(saf["filename"] == filename and roles["profileSha256"] == root["profileSha256"] and roles["directoryTextSha256"] == sha(title.encode()), "Exact admitted profile and observed owned UUID breadcrumb required")
        steps = ["CHOOSE_ROOT", "CHOOSE_DOCUMENT"] if root["kind"] == "IMPORT" else ["CHOOSE_ROOT", "SET_FILENAME", "SAVE"]
        require(saf["pickerActionSteps"] in (steps, ["SHOW_ROOTS"] + steps), "One ordered action per observed role; no unobserved Open or replay")
        for key in ("rootTitle", "documentTitle"):
            row = roles[key]; first.exact(row, {"state", "resourceId", "resourceIdObserved", "className", "windowId", "observedTextSha256", "finalFreshPathChecked"})
            if key == "documentTitle" and root["kind"] == "EXPORT":
                require(row == {"state": "NOT_APPLICABLE", "resourceId": None, "resourceIdObserved": False, "className": None, "windowId": None, "observedTextSha256": None, "finalFreshPathChecked": False}, "Export never claims an observed existing document")
            else:
                first.integer(row["windowId"], 0, 2147483647)
                require(row["state"] == "BOUND" and row["resourceIdObserved"] is True and row["className"] == "android.widget.TextView" and row["windowId"] == roles["directoryWindowId"] and row["observedTextSha256"] == sha((title if key == "rootTitle" else filename).encode()) and row["finalFreshPathChecked"] is True, "Runtime owned UUID selector/root/window/final path remains UNKNOWN")
                require(row["resourceId"] is None or type(row["resourceId"]) is str and re.fullmatch(r"(?:" + re.escape(root["docsPackage"]) + r"|android):id/[A-Za-z0-9_]+", row["resourceId"]), "Only actual observed ID or actual observed absence")
    first.canonical_uuid(saf["pickerId"]); require(saf["providerRelativeDirectory"] == NAMESPACE + "/" + root["run"], "Exact UUID provider directory binding")
    for audit in audits:
        require(audit["run"] == root["run"] and audit["kind"] == root["kind"] and audit["auditReadFdsClosed"] is True, "Foreign provider audit")
    a, b = audits; require(first.same(a["files"], b["files"]) and first.same(a["documents"], b["documents"]), "Provider audit/payload not stable")
    docs = b["documents"]; scope = docs["scope.json"]
    require(scope == {"schema": 1, "run": root["run"], "kind": root["kind"], "appUid": root["uid"], "docsUid": root["docsUid"]}, "Actual five-field provider scope differs")
    imported = root["kind"] == "IMPORT"; payload = "input.bin" if imported else "output.bin"
    require(b["files"][payload] == {"bytes": saf["expectedBytes"], "sha256": saf["expectedSha256"]}, "Actual provider bytes differ from Main callback/producer")
    required = ("open.json", "close.json") if imported else ("create.json", "open.json", "close.json")
    owner = docs["open.json"]["providerProcess"]
    require(preflight["status"] == "absent" and preflight["twoExactPidofAbsences"] is True and provider_observed["status"] == "present" and
        owner["uid"] == root["testUid"] and owner["bootUuid"] == root["bootUuid"] and owner["argvExactName"] == PROVIDER and
        first.same(owner, provider_observed["process"]), "Exact provider must join initial absence and independent actual padded-argv observation")
    for name in required:
        record = docs[name]
        require(record["run"] == root["run"] and record["kind"] == root["kind"] and record["documentId"] == saf["documentId"] and record["authority"] == saf["authority"] and first.same(record["providerProcess"], owner), "Exact stable provider owner/document required")
    opened = docs["open.json"]; closed = docs["close.json"]
    require(opened["callerUid"] == root["uid"] and opened["callerPid"] == original_exit["process"]["pid"] and opened["mode"] == ("r" if imported else "wt") and
        closed["success"] is True and closed["size"] == saf["expectedBytes"] and closed["sha256"] == saf["expectedSha256"], "Provider real FD owner/close/fingerprint differs")
    if not imported:
        created = docs["create.json"]; require(created["callerUid"] == root["docsUid"] and created["displayName"] == saf["filename"] and created["mimeType"] == "application/octet-stream", "Exact system create required")
    else:
        handoff = docs["handoff.json"]; require(handoff["bytes"] == saf["expectedBytes"] and handoff["sha256"] == saf["expectedSha256"], "Imported ciphertext changed after handoff")
    return owner


def run(args):
    os.umask(0o077); output = capture.private_directory(args.output_dir, empty=True)
    raw, root = approval(args); guard, build = admitted(args, root)
    args.uid = root["uid"]; args.test_uid = root["testUid"]; args.boot_uuid = root["bootUuid"]; args.docs_package = root["docsPackage"]
    device = Device(args); device.snapshot(root, build)
    transport = device.require_shell_v2(root)
    capture.write_bytes(output / "root-approval.json", raw); capture.write_json(output / "source-guard.json", guard)
    capture.write_json(output / "transport-preflight.json", transport)
    # Before the first device side effect: record a precise owner or two same-boot absences.
    preflight = device.provider_observation(output, "before-any-side-effect", root, absent_required=True)
    absent = device.shell(["run-as", TEST, "ls", "-d", NAMESPACE])
    require(absent.returncode != 0 and b"No such file or directory" in absent.stdout + absent.stderr and b"Permission denied" not in absent.stdout + absent.stderr, "Any prior provider namespace is retained")
    input_pin = None; source = None
    if root["kind"] == "IMPORT":
        producer, exited, _ = native(device, args, root, build, "PRODUCER")
        first.integer(producer["bytes"], 1, 2 * 1024 * 1024); first.sha(producer["sha256"])
        input_pin = {key: producer[key] for key in ("bytes", "sha256")}
        source = device.prefix + ["shell", "-T", shlex.join(["run-as", APP, "cat", f"files/p7-main-saf-producer-v1/{root['run']}/input.bin"])]
        extra = [root["operationId"], sha(encoded(exited)), input_pin["sha256"], str(input_pin["bytes"])]
    else: extra = [root["operationId"], sha(raw)]
    # Complete current source/device verification before the one immutable transfer intent.
    admitted(args, root); device.snapshot(root, build); device.require_shell_v2(root)
    device.provider_observation(output, "before-handoff", root, absent_required=True)
    command = "prepare-" + root["kind"].lower()
    handoff, waited = pipeline(output, "handoff", source, device.helper_argv(root, command, extra), root["operationId"], Path(args.repo).resolve(), command, root)
    require(handoff["scopePublished"] is True and handoff["directorySynced"] is True and handoff["operationId"] == root["operationId"], "Scope publication unknown; never recopy")
    if input_pin: require(handoff["bytes"] == input_pin["bytes"] and handoff["sha256"] == input_pin["sha256"] and handoff["sourceEof"] is True and handoff["payloadFdClosed"] is True, "Original transfer incomplete")
    helper_exit = device.exit_owner(TEST, handoff["helperProcess"]); require(helper_exit is not None, "Preparation helper exit unknown")
    capture.write_json(output / "handoff-helper-exit.json", {"operationId": root["operationId"], "pipelineWaitSha256": first.digest(output / "handoff-wait.json"), "exit": helper_exit})
    proof, original_exit, provider_observed = native(device, args, root, build, root["kind"], input_pin)
    audits = []
    for index in range(2):
        if index: time.sleep(0.25)
        command = "audit-" + root["kind"].lower(); operation = str(uuid.uuid4())
        audit, _ = pipeline(output, "audit-" + str(index), None, device.helper_argv(root, command, [operation]), operation, Path(args.repo).resolve(), command, root)
        ended = device.exit_owner(TEST, audit["helperProcess"]); require(ended is not None, "Audit helper exit unknown")
        capture.write_json(output / ("audit-" + str(index) + "-helper-exit.json"), {"operationId": operation, "pipelineWaitSha256": first.digest(output / ("audit-" + str(index) + "-wait.json")), "exit": ended}); audits.append(audit)
    stored_handoff = dict(handoff)
    for key in ("scopePublished", "directorySynced", "helperOutcome", "stdinEof", "stdinClosed"): stored_handoff.pop(key)
    require(all(first.same(audit["documents"]["handoff.json"], stored_handoff) for audit in audits), "Immutable handoff receipt changed")
    owner = join(root, proof, audits, original_exit, provider_observed, preflight)
    provider_exit = device.exit_owner(TEST, owner)
    admitted(args, root); device.snapshot(root, build)
    result = {"schema": "p7-main-saf-host-review-v1", "run": root["run"], "kind": root["kind"], "operationId": root["operationId"],
        "rootApprovalSha256": sha(raw), "sdkSealSha256": root["sdkSealSha256"], "sourceGuardSha256": root["sourceGuardSha256"],
        "transport": transport, "transportUnknown": False, "providerPreflightSha256": sha(encoded(preflight)), "providerObservationSha256": sha(encoded(provider_observed)),
        "actualMainCallbackGrantAndProviderIoJoined": True, "originalAppExit": original_exit, "providerProcess": owner,
        "providerExit": provider_exit, "providerAuditStable": True, "safAccepted": provider_exit is not None,
        "status": "limited-passed" if provider_exit else "awaiting-provider-exit", "physicalDeviceAccepted": False,
        "shippingTransportAccepted": False, "migrationActivationAfterSafAccepted": False,
        "retained": [NAMESPACE + "/" + root["run"], f"files/acceptance/P7-inbox-{root['run']}"] + ([f"files/p7-main-saf-producer-v1/{root['run']}"] if input_pin else []),
        "releaseCondition": "Root joins actual provider/VM exit and transient grant termination, preserves minimal receipt, then removes only exact new task resources"}
    capture.write_json(output / "host-review.json", result)
    print(json.dumps({"schema": result["schema"], "run": root["run"], "status": result["status"], "reviewSha256": first.digest(output / "host-review.json")}))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "output-dir", "adb", "serial", "build-bundle", "root-approval", "root-approval-sha256"): p.add_argument("--" + name, required=True)
    run(p.parse_args())


if __name__ == "__main__": main()
