#!/usr/bin/env python3
"""One-shot API33 P8 Main-subclass menu gate. No build/install/kill/retry or arbitrary class/extras."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import uuid

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


first = module("p8_main_source", "android-nonempty-device-first.py")
pair = module("p8_main_read_helpers", "android-nonempty-device-pair.py")
capture = module("p8_main_sdk_capture", "android-sdk-capture.py")
require = first.require
encoded = first.encoded
sha = lambda raw: hashlib.sha256(raw).hexdigest()
TOOL = "scripts/mobile/android-main-preference.py"
PROFILE = "p8-main-preference"
MODE = "main-subclass-menu"
CLASS = "com.agenthub.android.NativeP8MainPreferenceTest"
METHOD = "savedPreferenceThroughMainSubclassMenuPersistsWithoutNativeRuntime"
SELECTOR = CLASS + "#" + METHOD
ROOT = "apps/android/src/"
TEST = ROOT + "androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt"
CONTRACT = ROOT + "androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt"
ACTIVITY = ROOT + "debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt"
SCOPE = ROOT + "androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt"
PHASES = {"admission-accepted", "before-scope-acquisition", "scope-created-before-key-and-Main", "inherited-Main-restored",
    "typed-port-proof-before-owner-release", "owners-confirmed-or-retained-before-scope-cleanup",
    "complete-bounded-scope-inventory-before-delete", "retained-to-original-process-exit", "complete"}


def admitted(args):
    repo = Path(args.repo).resolve()
    require(Path(__file__).resolve() == repo / TOOL, "Reviewed in-repo P8 adapter required")
    first.canonical_uuid(args.run); first.canonical_uuid(args.boot_uuid)
    first.integer(args.uid, 1, 2147483647); first.integer(args.test_uid, 1, 2147483647)
    build = capture.verify_build_bundle(args.build_bundle, first, repo, PROFILE)
    guard = first.read_guard(Path(args.build_bundle) / "source-before.json")
    require(guard.get("reviewedProfile") == PROFILE and set(guard["sourceOverlay"]) == set(first.P8_MAIN_OVERLAY_PATHS), "Exact P8 static source contract")
    require(first.same(guard, first.source_guard(repo, guard["sourceOverlay"], reviewed_profile=PROFILE)), "P8 source drift")
    for role, value in (("app", args.app_apk), ("test", args.test_apk)):
        pin = build["artifacts"][role]
        require(Path(value).resolve() == Path(pin["path"]).resolve() and first.digest(value) == pin["sha256"], "Exact captured APKs required")
    require(Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve() and first.digest(args.adb) == build["tools"]["adb"]["sha256"], "Actual captured ADB required")
    require(first.digest(__file__) == guard["sources"][TOOL], "Adapter bytes differ")
    return guard, build


def package_uid(raw, package):
    # Android pm filters by substring: the app query also returns its test package.
    # Validate bounded complete rows, then require one exact target, never a prefix.
    require(package in (first.PACKAGE, first.TEST_PACKAGE) and type(raw) is bytes and 0 < len(raw) <= 16384,
        "Bounded fixed package UID response required")
    lines = raw.decode("utf-8").splitlines()
    require(0 < len(lines) <= 64, "Bounded package UID rows required")
    values = {}
    for line in lines:
        match = re.fullmatch(r"package:([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+) uid:([0-9]{1,10})", line)
        require(match is not None, "Complete package UID row required")
        name, digits = match.groups()
        require(package in name and name not in values, "Unique pm substring result required")
        value = int(digits); first.integer(value, 1, 2147483647)
        require(str(value) == digits, "Canonical package UID required")
        values[name] = value
    require(package in values, "Exactly one independently installed target package UID required")
    return values[package]


def uids(device):
    return {package: package_uid(device.success(["pm", "list", "packages", "-U", package]), package)
        for package in (first.PACKAGE, first.TEST_PACKAGE)}


def snapshot(args, device, phase, guard, build):
    current_guard, current_build = admitted(args)
    require(first.same(current_guard, guard) and current_build["sealSha256"] == build["sealSha256"], "Source/build seal changed")
    installed = device.installed()
    require(installed == {first.PACKAGE: build["artifacts"]["app"]["sha256"], first.TEST_PACKAGE: build["artifacts"]["test"]["sha256"]}, "Installed bytes differ from certificate-verified captured APKs")
    identities = uids(device)
    require(identities == {first.PACKAGE: args.uid, first.TEST_PACKAGE: args.test_uid}, "Installed UID changed")
    require(device.boot() == args.boot_uuid and device.success(["getprop", "ro.build.version.sdk"]).strip() == b"33", "Exact API33/same boot gate")
    value = {"schema": "p8-main-device-snapshot-v1", "phase": phase, "sourceGuardSha256": sha(encoded(guard)),
        "sdkSealSha256": build["sealSha256"], "installedApks": installed, "installedUids": identities,
        "bootUuid": args.boot_uuid, "deviceApi": 33, "certificateSha256": build["artifacts"]["app"]["certificateSha256"]}
    first.write_new(Path(args.output_dir) / (phase + "-snapshot.json"), value)


def admission(args, guard, build):
    # Generated exactly once, persisted before Popen, then immutable; child case IDs are part of that same intent.
    cases = [{"run": str(uuid.uuid4()), "language": language, "appearance": appearance}
        for language, appearance in (("zh-Hans", "light"), ("en", "dark"))]
    sources = guard["sources"]; tools = build["tools"]
    value = {"schema": "p8-main-preference-admission-v1", "profile": PROFILE, "mode": MODE, "run": args.run,
        "selector": SELECTOR, "package": first.PACKAGE, "testPackage": first.TEST_PACKAGE,
        "uid": args.uid, "testUid": args.test_uid, "bootUuid": args.boot_uuid, "deviceApi": 33,
        "cases": cases, "nativeRuntime": False, "shippingMainCovered": False,
        "sourceGuardSha256": sha(encoded(guard)), "reviewedStateSha256": capture.load_json(Path(args.build_bundle) / "capture-seal.json")["reviewedStateSha256"],
        "sdkSealSha256": build["sealSha256"], "adapterSourceSha256": sources[TOOL],
        "captureToolSha256": tools["captureTool"]["sha256"], "sourceToolSha256": tools["sourceTool"]["sha256"],
        "testSourceSha256": sources[TEST], "contractSourceSha256": sources[CONTRACT],
        "activitySourceSha256": sources[ACTIVITY], "scopeBoundarySourceSha256": sources[SCOPE],
        "appApkSha256": build["artifacts"]["app"]["sha256"], "testApkSha256": build["artifacts"]["test"]["sha256"],
        "certificateSha256": build["artifacts"]["app"]["certificateSha256"],
        "adbSha256": tools["adb"]["sha256"], "serialSha256": sha(args.serial.encode())}
    require(len(encoded(value)) <= 8192, "Finite Native admission argument")
    return value


def junit(raw, expected):
    # P7's fixed selector/parser is unchanged. P8's separate finite parser accepts only its named
    # JUnit case plus the structured P8 evidence blocks, which must each be joined to this admission.
    require(1 <= len(raw) <= capture.CAP, "Bounded original JUnit stdout")
    text = raw.decode("utf-8", errors="strict")
    require(not any(token in text for token in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")), "Native runner failure")
    codes = []; events = []; current = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            require(sep and key not in current, "Duplicate/malformed runner field"); current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
            require(re.fullmatch(r"-?[0-9]+", code), "Runner status primitive")
            if "p8Evidence" in current:
                require(set(current) == {"p8Evidence"} and code == "0" and len(events) < 128, "Closed P8 evidence status")
                value = capture.parse_json(current["p8Evidence"].encode())
                first.exact(value, {"schema", "rootRun", "admissionSha256", "phase", "body"})
                require(value["schema"] == "p8-main-preference-evidence-v1" and value["rootRun"] == expected["run"] and
                    value["admissionSha256"] == sha(encoded(expected)) and value["phase"] in PHASES and type(value["body"]) is dict, "Wrong Native evidence binding")
                require(value["phase"] != "retained-to-original-process-exit", "Native ownership is unconfirmed; preserve original evidence")
                events.append(value)
            else:
                require(set(current) <= {"id", "stream", "numtests", "class", "test", "current", "stack"} and
                    current.get("class") == CLASS and current.get("test") == METHOD and current.get("numtests") == "1" and
                    current.get("current") == "1" and "stack" not in current, "Unique exact P8 JUnit case required")
                codes.append(int(code))
            current = {}
    final = [line.removeprefix("INSTRUMENTATION_CODE: ") for line in text.splitlines() if line.startswith("INSTRUMENTATION_CODE: ")]
    require(not current and codes == [1, 0] and final == ["-1"] and re.search(r"OK \(1 test\)", text), "Exactly one passing named P8 case")
    require(events and events[0]["phase"] == "admission-accepted" and events[-1]["phase"] == "complete" and
        sum(row["phase"] == "admission-accepted" for row in events) == 1 and sum(row["phase"] == "complete" for row in events) == 1, "One original Native admission/completion")
    return {"schema": "p8-main-junit-v1", "class": CLASS, "method": METHOD, "rawSha256": sha(raw), "statuses": codes, "passed": True}, events


def native_proof(expected, events, observed):
    accepted = events[0]["body"]; first.exact(accepted, {"binding", "nativeFacts"})
    require(first.same(accepted["binding"], expected), "Native did not accept the original immutable admission")
    facts = accepted["nativeFacts"]
    first.exact(facts, {"package", "uid", "testUid", "bootUuid", "pid", "startTicks", "argvSha256", "processNonce", "deviceApi", "appApkSha256", "testApkSha256", "certificateSha256"})
    for key in ("package", "uid", "testUid", "bootUuid", "deviceApi", "appApkSha256", "testApkSha256", "certificateSha256"):
        require(first.same(facts[key], expected[key]), "Actual Native installed/runtime fact differs: " + key)
    require(type(facts["pid"]) is int and facts["pid"] == observed["pid"] and facts["startTicks"] == observed["startTicks"] and
        facts["argvSha256"] == observed["cmdlineSha256"] and facts["uid"] == observed["uid"] and facts["bootUuid"] == observed["bootUuid"], "Native/original independent /proc observation join")
    first.canonical_uuid(facts["processNonce"])
    proof = events[-1]["body"]; first.exact(proof, {"nativeFacts", "cases", "nativeRuntime", "shippingMainCovered"})
    require(first.same(proof["nativeFacts"], facts) and proof["nativeRuntime"] is False and proof["shippingMainCovered"] is False, "Native final tuple/APK/cert drift or coverage inflation")
    require(type(proof["cases"]) is list and len(proof["cases"]) == 2, "Exact two presentation cases")
    for index, case in enumerate(proof["cases"]):
        first.exact(case, {"run", "language", "appearance", "choices", "restoredPreference", "protectedOriginalsUnchanged", "stableFinalDefaultsConfirmed", "scopeRemoved", "aliasRemoved"})
        require(all(first.same(case[key], expected["cases"][index][key]) for key in ("run", "language", "appearance")) and
            case["choices"] == ["lan-only", "relay-only", "automatic", "relay-only"] and case["restoredPreference"] == "relay-only" and
            all(case[key] is True for key in ("protectedOriginalsUnchanged", "stableFinalDefaultsConfirmed", "scopeRemoved", "aliasRemoved")), "Native menu/restoration/cleanup case mismatch")
    case_ids = {row["run"] for row in expected["cases"]}
    for event in events[1:-1]:
        body = event["body"]; require(body.get("ownedRun") in case_ids, "Foreign fixture scope evidence")
        require(body.get("pid") == facts["pid"] and body.get("processNonce") == facts["processNonce"], "Evidence process changed")
    return proof


def run(args):
    os.umask(0o077); output = capture.private_directory(args.output_dir, empty=True)
    guard, build = admitted(args); device = pair.Device(args)
    snapshot(args, device, "before", guard, build)
    absent = device.shell(["pidof", first.PACKAGE])
    require(absent.returncode == 1 and not absent.stdout and not absent.stderr, "Fresh absent target PID required; never force-stop or reuse")
    first.write_new(output / "source-guard.json", guard, first.GUARD_CAP)
    value = admission(args, guard, build); first.write_new(output / "native-admission.json", value)
    admission_sha = sha(encoded(value))
    arguments = {"class": SELECTOR, "p8Mode": MODE, "p8Run": args.run,
        "p8AdmissionSha256": admission_sha, "p8AdmissionJson": encoded(value).decode()}
    first.write_new(output / "instrumentation-arguments.json", arguments)
    native = ["am", "instrument", "-w", "-r"]
    for key, text in sorted(arguments.items()): native += ["-e", key, text]
    native += [first.RUNNER]
    argv = device.prefix + ["shell", "-T", shlex.join(native)]
    invocation = {"schema": "p8-main-one-shot-invocation-v1", "run": args.run, "mode": MODE, "selector": SELECTOR,
        "package": first.PACKAGE, "runner": first.RUNNER, "arguments": arguments,
        "admissionSha256": admission_sha, "adbSha256": first.digest(args.adb), "serialSha256": sha(args.serial.encode())}
    first.write_new(output / "native-invocation.json", invocation)
    observed = {}
    def tick():
        if observed: return
        pid = device.shell(["pidof", first.PACKAGE])
        if pid.returncode == 1 and not pid.stdout and not pid.stderr: return
        require(pid.returncode == 0 and not pid.stderr, "Original process read failed")
        live = device.live(); live["intentSha256"] = sha(encoded(invocation))
        pair.process(live, {"uid": args.uid, "package": first.PACKAGE, "bootUuid": args.boot_uuid})
        first.write_new(output / "original-process.json", live); observed.update(live)
        snapshot(args, device, "live", guard, build)
    # Reuse the actual shared Popen owner, dual streams, UNKNOWN policy and original wait.
    # Deadline/error never introduces a replacement worker or a signal to the target/instrumentation.
    result = capture.run_process(output, "p8-runner", argv, Path(args.repo).resolve(), environment={},
        timeout=300, child_umask=0o077, tick=tick)
    require(observed and not result["stderr"], "Original observed process and clean separate stderr required")
    parsed, events = junit(result["stdout"], value); proof = native_proof(value, events, observed)
    first.write_new(output / "junit.json", parsed); first.write_new(output / "native-evidence.json", events, capture.CAP)
    wait = capture.verify_process(output, "p8-runner", argv=argv, cwd=Path(args.repo).resolve(), environment={}, child_umask=0o077)
    require(wait["intent"]["timeoutSeconds"] == 300 and wait["stdout"] == result["stdout"] and not wait["stderr"], "Actual original wait/raw association")
    snapshot(args, device, "after", guard, build)
    require(capture.private_bytes(output / "native-admission.json", 8192) == encoded(value) and
        first.same(capture.load_json(output / "native-invocation.json"), invocation), "Immutable submission evidence changed")
    review = {"schema": "p8-main-subclass-host-review-v1", "run": args.run, "profile": PROFILE, "mode": MODE,
        "selector": SELECTOR, "sdkSealSha256": build["sealSha256"], "sourceGuardSha256": value["sourceGuardSha256"],
        "admissionSha256": admission_sha, "invocationSha256": sha(encoded(invocation)),
        "captureOperationId": wait["intent"]["operationId"], "captureWaitSha256": wait["receiptSha256"],
        "hostWorkerPid": wait["receipt"]["childPid"], "actualWaitReturned": wait["receipt"]["actualWaitReturned"],
        "rawSha256": sha(wait["stdout"]), "stderrSha256": sha(wait["stderr"]),
        "junitSha256": first.digest(output / "junit.json"), "nativeEvidenceSha256": first.digest(output / "native-evidence.json"),
        "originalProcessSha256": first.digest(output / "original-process.json"),
        "snapshotSha256": {phase: first.digest(output / (phase + "-snapshot.json")) for phase in ("before", "live", "after")},
        "caseCount": len(proof["cases"]), "nativeRuntime": False, "shippingMainCovered": False,
        "networkIssuerDesktopPhysicalApi26Covered": False, "allA5Covered": False, "status": "passed"}
    first.write_new(output / "host-review.json", review)
    print(json.dumps({"schema": review["schema"], "run": args.run, "status": review["status"], "reviewSha256": first.digest(output / "host-review.json")}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "output-dir", "app-apk", "test-apk", "adb", "serial", "run", "boot-uuid", "build-bundle"):
        parser.add_argument("--" + name, required=True)
    for name in ("uid", "test-uid"): parser.add_argument("--" + name, type=int, required=True)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
