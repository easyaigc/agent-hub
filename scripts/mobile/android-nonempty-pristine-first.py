#!/usr/bin/env python3
"""Closed pristine Controller first run. Reuses captured SDK/source guard/Popen; never builds, installs or kills App."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import stat
import sys
import time

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


first = module("pristine_first_sources", "android-nonempty-device-first.py")
pair = module("pristine_first_device", "android-nonempty-device-pair.py")
capture = module("pristine_first_capture", "android-sdk-capture.py")
require = first.require
encoded = first.encoded
sha = lambda raw: hashlib.sha256(raw).hexdigest()
TOOL = "scripts/mobile/android-nonempty-pristine-first.py"
PROFILE = "p7-pristine"
SCHEMA = "p7-nonempty-pristine-controller-v1"
ADMISSION_SCHEMA = "p7-nonempty-pristine-first-admission-v1"
METHOD = "pristineNonemptyControllerImportsWithoutPrecreatedRootKey"
SELECTOR = "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + METHOD
MODE = "pristine-first"
WINDOWS = ["BEFORE_CONSTRUCTION", "AFTER_EMPTY_SESSION_RESTORE", "AFTER_CANDIDATE_STAGE",
           "AFTER_CONTROLLER_PREVIEW", "BEFORE_CONFIRM_IMPORT", "AFTER_CONTROLLER_DONE"]
RESTORE_POINTS = ["PREPARE_RETURNED", "ENROLLMENT_AUDIT_OPENED", "ENROLLMENT_AUDIT_RETIRE_RETURNED", "CANDIDATE_CACHE_PREPARED"]
SHARED = {"schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor", "scenario"}
PIN_KEYS = {"receipt", "generation", "archiveSha256", "sourceCiphertext", "originalCandidateCiphertext", "destinationCiphertext",
    "originalTextSha256", "originalBinarySha256", "draftKey", "draftTextSha256", "sourceTextSha256", "sourceBinarySha256",
    "activeTextSha256", "activeBinarySha256", "acquiredStores", "acquiredSessions", "actualSessionJoinReturns",
    "actualWriterRetireReturns", "actualLeaseReleaseReturns", "unknownAcquisitions", "forbiddenPortAttempts"}
CONTROLLER_KEYS = {"keyWindows", "restorePoints", "controllerQuiesceReturns", "controllerRestoreReturns", "newSessionPublished",
    "freshReady", "controllerReady", "explicitEnrollmentRequired", "phase", "result", "preSessionResetObserved",
    "directKeyGeneratorObserved", "controllerWorkerJoinClaimed", "physicalDescriptorExitClaimed"}


def scenario(run):
    alias = "p7-nonempty-" + run
    return {"entryPath": "already-staged-production-candidate-controller", "rootAlias": alias, "sourceAlias": alias + "-source",
        "rootNamespace": "installation", "sourceNamespace": "source", "safPickerCovered": False, "uiCovered": False, "coldProcessCovered": False}


def admitted(args, *, foreground=False):
    require(type(foreground) is bool, "Closed P7 Controller profile")
    profile = "p7-foreground" if foreground else PROFILE
    tool = "scripts/mobile/android-nonempty-foreground-pair.py" if foreground else TOOL
    method = "nonemptyForegroundControllerProducesPortableArchives" if foreground else METHOD
    schema = "p7-nonempty-foreground-controller-v1" if foreground else SCHEMA
    mode = "foreground-first" if foreground else MODE
    first.canonical_uuid(args.run); first.canonical_uuid(args.boot_uuid); first.integer(args.uid, 1, 2147483647)
    repo = Path(args.repo).resolve(); require(Path(__file__).resolve() == repo / TOOL, "Run only the reviewed in-repo adapter")
    # _review and verify_build_bundle enforce the finite seven-entry Root before/after overlay,
    # full source inventory, real build waits, original tools, artifacts, certificates and seal.
    build = capture.verify_build_bundle(args.build_bundle, first, repo, profile)
    bundle = Path(args.build_bundle)
    guard = first.read_guard(bundle / "source-before.json")
    require(set(guard["sourceOverlay"]) == set(first.DYNAMIC_SOURCES) | {TOOL, tool}, "Explicit Controller profile overlay inventory required")
    require(first.same(guard, first.source_guard(repo, guard["sourceOverlay"])), "Pristine sources drifted")
    for role, path in (("app", args.app_apk), ("test", args.test_apk)):
        require(Path(path).resolve() == Path(build["artifacts"][role]["path"]).resolve() and first.digest(path) == build["artifacts"][role]["sha256"], "Only exact controlled output APKs admitted")
    require(Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve() and first.digest(args.adb) == build["tools"]["adb"]["sha256"], "Admitted SDK ADB only")
    value = {"schema": "p7-nonempty-foreground-first-admission-v1" if foreground else ADMISSION_SCHEMA,
        "caseSchema": schema, "run": args.run, "selectedMethod": method,
        "selector": "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + method,
        "mode": mode, "package": first.PACKAGE, "uid": args.uid, "bootUuid": args.boot_uuid,
        "appApkSha256": build["artifacts"]["app"]["sha256"], "testApkSha256": build["artifacts"]["test"]["sha256"],
        "caseSourceSha256": guard["sources"][first.CASE], "wrapperSourceSha256": guard["sources"][first.WRAPPER],
        "sourceGuardSha256": sha(encoded(guard)), "buildSealSha256": build["sealSha256"],
        "adapterSourceSha256": guard["sources"][tool], "adbSha256": first.digest(args.adb), "deviceSerialSha256": sha(args.serial.encode())}
    return guard, value, build


def arguments(run, bound):
    mapping = {"appApkSha256": "nonemptyAppApkSha256", "testApkSha256": "nonemptyTestApkSha256",
        "caseSourceSha256": "nonemptyCaseSourceSha256", "wrapperSourceSha256": "nonemptyWrapperSourceSha256",
        "sourceGuardSha256": "nonemptySourceGuardSha256", "sourceAdmissionSha256": "nonemptySourceAdmissionSha256",
        "bootUuid": "nonemptyBootUuid", "uid": "nonemptyUid"}
    return {target: str(bound[source]) for source, target in mapping.items()} | {"class": SELECTOR, "nonemptyMode": MODE, "nonemptyRun": run}


def anchor(value, run, uid):
    require(type(value) is dict and 12 <= len(value) <= 128, "Anchor quota")
    fixed = {"package", "uid", "run", "filesRoot", "dataScopeIdentity", "runDirectory", "no_backup", "cache", "files"}
    require(fixed <= set(value) and "ancestor:/" in value and all(name in fixed or name.startswith(("ancestor:/", "observed-mount:/")) for name in value), "Anchor names")
    require(all(type(name) is str and len(name) <= 512 and type(text) is str and 1 <= len(text) <= 16384 for name, text in value.items()), "Anchor scalar quota")
    require(value["package"] == first.PACKAGE and value["uid"] == str(uid) and value["run"] == run and
        value["filesRoot"].startswith("/data/") and value["filesRoot"].endswith("/" + first.PACKAGE + "/files"), "Owned package files anchor")
    for name in ("dataScopeIdentity", "runDirectory", "no_backup", "cache", "files"):
        parts = value[name].split(":"); require(len(parts) == 4 and all(re.fullmatch(r"[0-9]{1,24}", part) for part in parts), "Anchor directory tuple")
        _, _, owner, mode = map(int, parts)
        require(owner == uid and stat.S_ISDIR(mode) and stat.S_IMODE(mode) in ({0o700, 0o771} if name == "dataScopeIdentity" else {0o700}), "Anchor directory owner/mode")


def verify_native(intent_raw, proof_raw, args, admission, observed, *, foreground=False):
    require(type(foreground) is bool, "Closed P7 Controller proof profile")
    schema = "p7-nonempty-foreground-controller-v1" if foreground else SCHEMA
    method = "nonemptyForegroundControllerProducesPortableArchives" if foreground else METHOD
    intent = capture.parse_json(intent_raw); proof = capture.parse_json(proof_raw)
    first.exact(intent, SHARED); first.exact(proof, SHARED | PIN_KEYS | CONTROLLER_KEYS)
    require(all(first.same(intent[name], proof[name]) for name in SHARED), "Immutable intent/proof changed")
    bound = first.binding(admission, sha(encoded(admission)))
    require(proof["schema"] == schema and proof["selectedMethod"] == method and proof["run"] == args.run and
        first.same(proof["binding"], bound) and first.same(proof["scenario"], scenario(args.run)), "Pristine target/mode/binding")
    pair.process(observed, bound)
    require(type(proof["pid"]) is int and proof["pid"] == observed["pid"] and type(proof["startTicks"]) is str and
        proof["startTicks"] == observed["startTicks"] and observed["intentSha256"] == sha(intent_raw), "Original live process tuple join")
    anchor(proof["anchor"], args.run, args.uid)
    first.exact(proof["receipt"], {"candidateId", "sha256", "records"}); first.canonical_uuid(proof["receipt"]["candidateId"])
    first.sha(proof["receipt"]["sha256"]); first.canonical_uuid(proof["generation"]); first.sha(proof["archiveSha256"])
    require(type(proof["receipt"]["records"]) is int and proof["receipt"]["records"] == (13 if foreground else 9), "Closed source record count")
    for name, cap in (("sourceCiphertext", 64), ("originalCandidateCiphertext", 64), ("destinationCiphertext", 128),
        ("originalTextSha256", 12 if foreground else 8), ("originalBinarySha256", 1), ("sourceTextSha256", 32), ("sourceBinarySha256", 1),
        ("activeTextSha256", 32), ("activeBinarySha256", 1)):
        first.digests(proof[name], cap)
    text = proof["originalTextSha256"]
    fixed = {"metadata", "codex-private-connections-v1", "mobile-reading-ledger-v1", "native-chat-notifications-v1", "native-chat-notification-sounds-v1"}
    archives = {name for name in text if name == "migration-notification-archives-v1" or name.startswith("migration-notification-original-v1:")}
    if foreground:
        require(len(archives) == 4 and "migration-notification-archives-v1" in archives and len(text) == 12, "Finite nonempty notification archive profile")
        originals = archives - {"migration-notification-archives-v1"}
        require({name.rsplit(":", 1)[-1] for name in originals} == {"foreground-v1", "recovery-v2", "authorizations-v1"}, "Three exact archived original kinds")
        identities = {name.removeprefix("migration-notification-original-v1:").split(":")[0] for name in originals}
        require(len(identities) == 1 and all(re.fullmatch(r"migration-notification-original-v1:[a-f0-9]{64}:(foreground-v1|recovery-v2|authorizations-v1)", name) for name in originals), "One archive installation")
        active = {"native-chat-notification-identity-v1", "native-chat-notification-foreground-v1", "native-chat-notification-recovery-v2", "native-chat-notification-authorizations-v1"}
        require(active <= set(proof["sourceTextSha256"]) and not active.intersection(text) and not active.intersection(proof["activeTextSha256"]), "Source active identity/authority remain excluded from destination")
        require(proof["explicitEnrollmentRequired"] is True, "Fresh enrollment remains mandatory")
    else:
        require(not archives, "Original nine-record profile cannot contain notification archives")
    base_text = set(text) - archives
    extra = base_text - fixed
    require(fixed <= base_text and len(base_text) == 8 and len(extra) == 3 and all(sum(name.startswith(prefix) for name in extra) == 1 for prefix in ("operations:", "submission:", "draft-file:")), "Eight exact base text records")
    sound = next(iter(proof["originalBinarySha256"]))
    require(sound.startswith("native-chat-notification-sound-v1:") and sound.endswith(".caf"), "Normalized CAF record")
    first.canonical_uuid(sound.removeprefix("native-chat-notification-sound-v1:").removesuffix(".caf"))
    require(proof["originalBinarySha256"] == proof["sourceBinarySha256"] == proof["activeBinarySha256"], "Original binary preserved")
    # Two initial roots/two initial caches; two actual restore roots + ten production children;
    # one fresh Controller candidate cache. The two production-retired roots are separate returns.
    expected_stores = 4 + 2 * (1 + 10) + 1
    for name, count in {"acquiredStores": expected_stores, "acquiredSessions": 4, "actualSessionJoinReturns": 4,
        "actualWriterRetireReturns": expected_stores - 2, "actualLeaseReleaseReturns": 2, "unknownAcquisitions": 0,
        "forbiddenPortAttempts": 0, "controllerQuiesceReturns": 1, "controllerRestoreReturns": 1}.items():
        require(type(proof[name]) is int and proof[name] == count, "Actual owner return/count mismatch: " + name)
    for name in ("newSessionPublished", "freshReady", "controllerReady"):
        require(proof[name] is True, "Completed fresh Session/Controller readiness required")
    require(type(proof["explicitEnrollmentRequired"]) is bool and proof["phase"] == "DONE" and proof["result"] == "IMPORTED" and
        proof["restorePoints"] == RESTORE_POINTS, "Controller restore chain required")
    for name in ("preSessionResetObserved", "directKeyGeneratorObserved", "controllerWorkerJoinClaimed", "physicalDescriptorExitClaimed"):
        require(proof[name] is False, "Unsupported stronger claim")
    first.sha(proof["draftTextSha256"])
    require(type(proof["draftKey"]) is str and proof["draftKey"].startswith("codex-machine-v2:") and len(proof["draftKey"]) <= 512, "Independent draft target")
    windows = proof["keyWindows"]; require(type(windows) is list and len(windows) == len(WINDOWS), "Exact KEY observation windows")
    for index, window in enumerate(windows):
        first.exact(window, {"point", "rootAliasPresent", "rootCiphertextCount", "destinationCiphertext"})
        require(window["point"] == WINDOWS[index] and window["rootAliasPresent"] is (index == len(WINDOWS) - 1), "Root KEY absence/presence window")
        first.integer(window["rootCiphertextCount"], 0, 128)
        pins = window["destinationCiphertext"]; require(type(pins) is dict and len(pins) <= 256, "Scoped ciphertext quota")
        if pins: first.digests(pins, 256)
        for name in pins:
            parts = name.split("/")
            require(len(parts) in (2, 3) and re.fullmatch(r"installation(?:-[a-zA-Z0-9_-]+)?", parts[0]) and
                re.fullmatch(r"[a-f0-9]{64}\.bin", parts[-1]), "Closed destination inventory path")
            if len(parts) == 3:
                require(parts[0] == "installation" and parts[1].startswith("migration-generation-"), "Generation directory path")
                first.canonical_uuid(parts[1].removeprefix("migration-generation-"))
        root_count = sum(name.startswith("installation/") and name.count("/") == 1 for name in pins)
        require(window["rootCiphertextCount"] == root_count and (root_count > 0 if index == 5 else root_count == 0), "Root ciphertext timing")
        if index < 2: require(not pins, "Empty destination before staging")
        else:
            candidate = {name.removeprefix("installation-migration-candidates/"): value for name, value in pins.items() if name.startswith("installation-migration-candidates/")}
            require(candidate == proof["originalCandidateCiphertext"], "Original candidate ciphertext changed")
    require(windows[2]["destinationCiphertext"] == windows[3]["destinationCiphertext"] == windows[4]["destinationCiphertext"], "Preview cannot bootstrap destination")
    return proof


def worker(args, device, native_arguments, admission_sha, bound):
    output = Path(args.output_dir)
    invocation = {"schema": "p7-pristine-one-shot-invocation-v1", "run": args.run, "mode": MODE, "package": first.PACKAGE,
        "runner": first.RUNNER, "arguments": native_arguments, "admissionSha256": admission_sha,
        "adbSha256": first.digest(args.adb), "deviceSerialSha256": sha(args.serial.encode())}
    first.write_new(output / "first-invocation.json", invocation)
    native = ["am", "instrument", "-w", "-r"]
    for key, text in sorted(native_arguments.items()): native += ["-e", key, text]
    native += [first.RUNNER]
    observation = {}
    def tick():
        if observation: return
        reply = device.document("first-intent")
        if reply.returncode != 0:
            require(b"No such file or directory" in reply.stdout + reply.stderr and b"Permission denied" not in reply.stdout + reply.stderr, "Intent read failure")
            return
        body = capture.parse_json(reply.stdout); first.exact(body, SHARED)
        require(body["schema"] == SCHEMA and body["run"] == args.run and body["selectedMethod"] == METHOD and
            first.same(body["binding"], bound) and first.same(body["scenario"], scenario(args.run)), "Wrong original Native intent")
        pair.write_native(output / "first-intent-observed.json", reply.stdout)
        live = device.live(body["pid"]); live["intentSha256"] = sha(reply.stdout)
        require(type(body["pid"]) is int and body["startTicks"] == live["startTicks"], "Native/live tuple mismatch")
        pair.process(live, bound); first.write_new(output / "first-process.json", live); observation.update(live)
    # The original shared engine retains exact Popen ownership and really waits. No exception-path
    # timeout/cancellation can turn unknown work into an accepted result or spawn a replacement.
    result = capture.run_process(output, "first-runner", device.prefix + ["shell", "-T", shlex.join(native)],
        Path(args.repo).resolve(), environment={}, timeout=300, child_umask=0o077, tick=tick)
    require(observation and not result["stderr"], "Original tuple and clean separate stderr required")
    parsed = pair.junit(result["stdout"], METHOD); first.write_new(output / "first-junit.json", parsed)
    wait = capture.verify_process(output, "first-runner", argv=device.prefix + ["shell", "-T", shlex.join(native)],
        cwd=Path(args.repo).resolve(), environment={}, child_umask=0o077)
    require(wait["intent"]["timeoutSeconds"] == 300 and wait["stdout"] == result["stdout"] and not wait["stderr"], "Actual original wait/raw join")
    first.write_new(output / "first-worker.json", {"schema": "p7-pristine-worker-wait-v1", "run": args.run,
        "mode": MODE, "invocationSha256": sha(encoded(invocation)), "captureWaitSha256": wait["receiptSha256"],
        "hostWorkerPid": wait["receipt"]["childPid"], "actualWaitReturned": wait["receipt"]["actualWaitReturned"],
        "returnCode": wait["receipt"]["returnCode"], "rawSha256": sha(wait["stdout"]), "stderrSha256": sha(wait["stderr"]),
        "junitSha256": first.digest(output / "first-junit.json"), "status": "passed"})
    return observation


def run_first(args):
    os.umask(0o077); output = capture.private_directory(args.output_dir, empty=True)
    guard, admission, build = admitted(args)
    device = pair.Device(args); require(device.boot() == args.boot_uuid, "Admitted boot differs")
    installed = {first.PACKAGE: admission["appApkSha256"], first.TEST_PACKAGE: admission["testApkSha256"]}
    require(device.installed() == installed, "Installed App/Test differ from actual captured SDK outputs")
    sdk = device.success(["getprop", "ro.build.version.sdk"]).strip(); require(re.fullmatch(rb"[0-9]{1,3}", sdk), "Device SDK")
    first.integer(int(sdk), 26, 1000)
    adb_version = device.call(["version"], 8192)
    require(adb_version.returncode == 0 and not adb_version.stderr and adb_version.stdout.startswith(b"Android Debug Bridge version"), "Actual captured ADB version")
    first.write_new(output / "sdk-observation.json", {"schema": "p7-pristine-sdk-observation-v1", "deviceApi": int(sdk),
        "adbVersion": adb_version.stdout.decode(), "buildSealSha256": build["sealSha256"]})
    first.write_guard(output / "source-guard.json", guard); first.write_new(output / "source-admission.json", admission)
    admission_sha = first.digest(output / "source-admission.json")
    bound = first.binding(admission, admission_sha); native_arguments = arguments(args.run, bound)
    first.write_new(output / "instrumentation-arguments.json", native_arguments)
    observed = worker(args, device, native_arguments, admission_sha, bound)
    documents = {}
    for name in ("first-intent", "first-proof"):
        reply = device.document(name); require(reply.returncode == 0 and not reply.stderr, "Missing original Native document")
        pair.write_native(output / (name + ".json"), reply.stdout); documents[name] = reply.stdout
    proof = verify_native(documents["first-intent"], documents["first-proof"], args, admission, observed)
    deadline = time.monotonic() + 30; exited = None
    while time.monotonic() < deadline:
        exited = device.exited(observed)
        if exited is not None: break
        time.sleep(0.1)
    require(exited is not None, "Original process has not naturally exited; retain exact scope, never kill/retry")
    pair.exit_observation(exited, observed); first.write_new(output / "first-exit-process.json", exited)
    final_guard, final_admission, final_build = admitted(args)
    require(first.same(final_guard, guard) and first.same(final_admission, admission) and final_build["sealSha256"] == build["sealSha256"] and
        device.installed() == installed and device.success(["getprop", "ro.build.version.sdk"]).strip() == sdk and
        device.call(["version"], 8192).stdout == adb_version.stdout and device.boot() == args.boot_uuid, "Final source/APK/SDK/tool/boot drift")
    result = {"schema": "p7-nonempty-pristine-first-host-review-v1", "run": args.run, "selectedMethod": METHOD,
        "firstIntentSha256": sha(documents["first-intent"]), "firstProofSha256": sha(documents["first-proof"]),
        "sourceGuardSha256": admission["sourceGuardSha256"], "sourceAdmissionSha256": admission_sha,
        "buildSealSha256": build["sealSha256"], "workerReceiptSha256": first.digest(output / "first-worker.json"),
        "originalProcessExitSha256": first.digest(output / "first-exit-process.json"), "originalPidObserved": True,
        "originalPidExitObserved": True, "nativeAssertionsReportedComplete": True, "rootKeyAbsentBeforeConfirmAndPresentAfterDoneObserved": True,
        "actualOwnerGraph": {name: proof[name] for name in ("acquiredStores", "acquiredSessions", "actualSessionJoinReturns", "actualWriterRetireReturns", "actualLeaseReleaseReturns")},
        "coldAccepted": False, "safPickerAccepted": False, "uiAccepted": False, "full24Accepted": False,
        "physicalDeviceClaimed": False, "controllerWorkerJoinClaimed": False, "physicalDescriptorExitClaimed": False,
        "fixtureRetainedForRootReconciliation": True}
    first.write_new(output / "pristine-first-host-review.json", result); print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("run-first")
    for name in ("repo", "output-dir", "app-apk", "test-apk", "adb", "serial", "run", "boot-uuid", "build-bundle"):
        command.add_argument("--" + name, required=True)
    command.add_argument("--uid", required=True, type=int)
    run_first(parser.parse_args())


if __name__ == "__main__":
    main()
