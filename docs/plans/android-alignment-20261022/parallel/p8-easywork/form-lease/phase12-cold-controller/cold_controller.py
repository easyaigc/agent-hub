#!/usr/bin/env python3
"""Default CLI is static preflight. Device execution requires an exact plan and fresh parent lease."""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path
from cold_contract import (
    ASSET, BASE, CLASS, COLD_SOURCE, COMPONENT, KIND, MODULES, PACKAGE, READER, RUNNER, WRITER,
    android_json, canonical, case_id, contained, digest, exact, file_sha, fixture_snapshot,
    integer, json_once, lineage_paths, loads, need, sha, validate_config, validate_reader,
    validate_stats, validate_writer, verify_candidate, write_once,
)


def preflight(args, metadata=None):
    candidate_path = Path(args.candidate).resolve()
    need(file_sha(candidate_path) == digest(args.candidate_sha256), "Candidate manifest is not the selected immutable input")
    targets_path = Path(args.targets).resolve()
    policy = loads(targets_path.read_bytes())
    exact(policy, ["version", "taskId", "targets"])
    need(type(policy["version"]) is int and policy["version"] == 1 and 1 <= len(policy["targets"]) <= 16 and args.target in policy["targets"], "Target not explicitly allowed")
    target = policy["targets"][args.target]
    exact(target, ["kind", "avd"])
    need(re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", args.target), "Unsafe serial")
    need(target["kind"] in ["physical", "emulator"], "Unknown target kind")
    need((target["kind"] == "emulator") == args.target.startswith("emulator-"), "Target kind mismatch")
    need((target["kind"] == "physical" and target["avd"] is None) or isinstance(target["avd"], str) and target["avd"], "Exact emulator AVD required")
    kwargs = {"metadata": metadata} if metadata else {}
    candidate = verify_candidate(candidate_path, args.frozen_source, args.app, args.runner, args.aapt, args.apksigner, **kwargs)
    output = Path(args.output).resolve()
    output.mkdir(mode=0o700)
    config = fixture_snapshot(args.fixture_root, output / "fixture-source", loads(candidate_path.read_bytes())["files"][ASSET])
    tools = {name: {"path": str(Path(getattr(args, name)).resolve()), "sha256": file_sha(getattr(args, name))} for name in ["aapt", "apksigner", "node"]}
    plan = {"version": 1, "kind": "p8-cold-execution-plan-v1", "caseId": str(uuid.uuid4()), "taskId": policy["taskId"],
            "target": {"serial": args.target, **target}, "targetPolicyPath": str(targets_path), "targetPolicySha256": file_sha(targets_path),
            "candidatePath": str(candidate_path), "candidateManifestSha256": args.candidate_sha256,
            "frozenSource": str(Path(args.frozen_source).resolve()), "candidate": candidate, "fixtureContract": config,
            "fixtureSource": str(output / "fixture-source"), "tools": tools,
            "package": PACKAGE, "runnerPackage": RUNNER, "instrumentation": COMPONENT,
            "roles": {"writer": CLASS + "#" + WRITER, "reader": CLASS + "#" + READER},
            "writerReadyRelative": "files/acceptance/p8-positive-commit/{}/writer-ready.json".format("RESERVED"),
            "deviceVerified": False, "reservedOnly": True, "deviceCommands": 0}
    plan["writerReadyRelative"] = "files/acceptance/p8-positive-commit/" + plan["caseId"] + "/writer-ready.json"
    json_once(output / "plan.json", plan)
    result = {"status": "preflight-only", "caseId": plan["caseId"], "candidate": candidate, "fixtureContract": config,
              "planSha256": file_sha(output / "plan.json"), "target": plan["target"], "deviceCommands": 0,
              "realCaseStarted": False, "runtimeGatesPending": ["fresh exclusive parent lease", "device/boot/API/AVD", "installed APK bytes", "no active instrumentation/process", "owned sidecar and exact reverse mappings", "writer/reader PID/time/key/data checks"]}
    json_once(output / "preflight.json", result)
    return result


def revalidate_plan(plan_path):
    path = Path(plan_path).resolve()
    plan = loads(path.read_bytes())
    need(plan["kind"] == "p8-cold-execution-plan-v1" and plan["version"] == 1, "Unknown plan")
    case_id(plan["caseId"])
    need(plan["package"] == PACKAGE and plan["runnerPackage"] == RUNNER and plan["instrumentation"] == COMPONENT, "Package identity changed")
    need(plan["roles"] == {"writer": CLASS + "#" + WRITER, "reader": CLASS + "#" + READER}, "Only two reviewed native roles")
    need(file_sha(plan["targetPolicyPath"]) == plan["targetPolicySha256"], "Target policy changed")
    policy = loads(Path(plan["targetPolicyPath"]).read_bytes())
    serial = plan["target"]["serial"]
    need(serial in policy["targets"] and plan["target"] == {"serial": serial, **policy["targets"][serial]} and plan["taskId"] == policy["taskId"], "Plan target differs from explicit allowlist")
    need(plan["writerReadyRelative"] == "files/acceptance/p8-positive-commit/" + plan["caseId"] + "/writer-ready.json", "Only the exact reserved UUID proof directory")
    need(file_sha(plan["candidatePath"]) == plan["candidateManifestSha256"], "Candidate manifest changed")
    facts = verify_candidate(plan["candidatePath"], plan["frozenSource"], plan["candidate"]["artifacts"]["app"]["path"], plan["candidate"]["artifacts"]["runner"]["path"], plan["tools"]["aapt"]["path"], plan["tools"]["apksigner"]["path"])
    need(facts == plan["candidate"], "Candidate facts changed")
    for tool in plan["tools"].values():
        need(file_sha(tool["path"]) == tool["sha256"], "Selected local tool changed")
    for name, expected in plan["fixtureContract"]["sourceGraph"].items():
        need(file_sha(contained(plan["fixtureSource"], BASE + name)) == expected, "Fixture source changed")
    need(file_sha(contained(plan["fixtureSource"], ASSET)) == plan["fixtureContract"]["sourceFixtureSha256"], "Fixture vector changed")
    return plan


def validate_lease(lease, plan, plan_sha, now=None):
    import datetime
    exact(lease, ["version", "taskId", "leaseId", "owner", "serial", "bootId", "api", "planSha256", "exclusiveAcceptanceProcess", "expiresAt", "adb", "adbSha256", "lockFile"])
    need(lease["version"] == 1 and lease["taskId"] == plan["taskId"] and lease["owner"], "Wrong parent lease owner/task")
    case_id(lease["leaseId"])
    need(lease["serial"] == plan["target"]["serial"] and lease["planSha256"] == plan_sha and lease["exclusiveAcceptanceProcess"] is True, "Lease does not bind this exact target/plan")
    need(26 <= integer(lease["api"], 26) <= 37, "Unsupported explicitly leased API")
    need(str(uuid.UUID(lease["bootId"])) == lease["bootId"], "Exact device boot identity required")
    expires = datetime.datetime.fromisoformat(lease["expiresAt"].replace("Z", "+00:00"))
    need(expires.tzinfo is not None, "Lease timezone required")
    now = now or datetime.datetime.now(datetime.timezone.utc)
    need(300 <= (expires - now).total_seconds() <= 3600, "Lease expired or over one hour")
    need(Path(lease["adb"]).is_file() and file_sha(lease["adb"]) == digest(lease["adbSha256"]), "ADB binary changed")
    lock = Path(lease["lockFile"])
    need(lock.is_absolute() and lock.parent.is_dir() and not lock.is_symlink() and lock.name == "p8-cold-" + lease["serial"] + "-" + lease["leaseId"] + ".lock", "Exact parent-owned lock path required")


def checkpoint(output, name, data):
    json_once(Path(output) / (name + ".json"), data)


def execute(plan, driver, output):
    """Hardware-independent state machine; self-checks inject a driver with no ADB capability."""
    output = Path(output)
    output.mkdir(mode=0o700)  # Exclusive consumption: no retry/re-entry of an old plan.
    checkpoint(output, "execution-intent", {"caseId": plan["caseId"], "planCanonicalSha256": sha(canonical(plan)), "target": plan["target"], "build": plan["candidate"]["build"]})
    phase = "prepare"
    writer_raw = None
    result = None
    try:
        runtime = driver.prepare(plan)
        config = runtime["config"]
        validate_config(config, plan["fixtureContract"])
        checkpoint(output, "runtime-intent", {"caseId": plan["caseId"], "config": config, "baselineDefaults": runtime["defaults"], "resources": runtime["resources"]})
        phase = "writer"
        driver.launch_writer(config, plan)
        writer_raw = driver.await_writer_ready(plan, 40)
        writer = validate_writer(writer_raw, plan, config, runtime["defaults"])
        held = driver.held_snapshot(writer)
        need(held["identity"]["pid"] == writer["pid"] and held["identity"]["startElapsedMs"] <= writer["readyElapsed"] and abs(held["identity"]["startElapsedMs"] - writer["processStartElapsed"]) <= 2000, "External writer PID/timeline mismatch")
        need(set(held["cipherHashes"]) == set(lineage_paths(writer)), "Missing exact six ciphertext files")
        validate_stats(held["stats"], writer)
        stop = driver.interrupt_owned_writer(writer, held)
        need(stop["pid"] == writer["pid"] and stop["pidGone"] is True and stop["identityUnchanged"] is True and stop["writerJUnitPassed"] is False, "Writer was not an interrupted owned checkpoint")
        need(0 <= stop["stoppedElapsedMs"] - writer["readyElapsed"] + 20 < 18000, "Writer stop exceeded18s checkpoint window")
        write_once(output / "writer-ready.json", writer_raw)
        checkpoint(output, "writer-interrupted", {"result": "interrupted-checkpoint; never JUnit pass", "stop": stop, "cipherHashes": held["cipherHashes"], "writerReadySha256": sha(writer_raw)})
        phase = "before-reader"
        driver.verify_stopped_state(writer, writer_raw, held, runtime["defaults"])
        phase = "reader"
        reader_result = driver.run_reader(config, plan, writer_raw)
        need(reader_result["junitPassed"] is True and reader_result["nativeMethods"] == 1, "Reader native method did not pass exactly once")
        reader = validate_reader(reader_result["proof"], writer_raw, plan, config, reader_result["identity"])
        need(reader_result["identity"]["uid"] == held["identity"]["uid"] and reader_result["identity"]["bootId"] == held["identity"]["bootId"] and reader_result["identity"]["startTicks"] > held["identity"]["startTicks"], "Different boot or reused process lineage")
        write_once(output / "reader-proof.json", reader_result["proof"])
        final = driver.verify_reader_state(writer, writer_raw, held, runtime["defaults"])
        need(final["closedCase"]["caseId"] == plan["caseId"], "Missing exact own closed case")
        closed = final["closedCase"]
        # Reader ends the original case but must not release the held response or add requests.
        validate_stats(closed, writer)
        checkpoint(output, "passed", {"status": "cold-boundary-passed", "writer": "interrupted-checkpoint", "readerNativeMethods": 1, "readerPid": reader["pid"], "writerPid": writer["pid"], "preservedCipherHashes": held["cipherHashes"], "realHostModelInputs": 0, "a5Complete": False})
        result = {"status": "cold-boundary-passed", "caseId": plan["caseId"], "a5Complete": False}
    except BaseException as error:
        sealed = {"status": "failed-sealed", "phase": phase, "caseId": plan["caseId"], "errorType": type(error).__name__, "error": str(error), "retryAllowed": False, "readerAutoRetry": False}
        try:
            sealed["ownedSnapshot"] = driver.seal_failure()
        except Exception as collect:
            sealed["collectionError"] = type(collect).__name__
        if writer_raw is not None and not (output / "writer-ready.json").exists():
            write_once(output / "rejected-writer-ready.bin", writer_raw)
        checkpoint(output, "failed-sealed", sealed)
        result = sealed
    finally:
        # Never end/reset/release an active case here, clear app data, or stop a reader.
        try:
            cleanup = driver.cleanup()
        except BaseException as error:
            cleanup = {"complete": False, "errorType": type(error).__name__, "onlyOwnedResources": True}
        checkpoint(output, "resource-cleanup", cleanup)
        if cleanup.get("complete") is not True:
            result["resourceCleanupIncomplete"] = True
            if result["status"] == "cold-boundary-passed":
                result["status"] = "native-pass-cleanup-incomplete"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("preflight", help="Static files and offline APK tools only; zero ADB/Node listener")
    for name in ["candidate", "candidate-sha256", "frozen-source", "app", "runner", "fixture-root", "targets", "target", "output", "aapt", "apksigner", "node"]:
        p.add_argument("--" + name, required=True)
    e = sub.add_parser("execute", help="Parent only: explicit exclusive fresh lease, preinstalled exact APKs")
    e.add_argument("--plan", required=True)
    e.add_argument("--lease", required=True)
    args = parser.parse_args()
    if args.mode == "preflight":
        print(json.dumps(preflight(args)))
        return 0
    plan = revalidate_plan(args.plan)
    lease = loads(Path(args.lease).read_bytes())
    validate_lease(lease, plan, file_sha(args.plan))
    from cold_device import AdbDevice
    # Import/creation cannot occur in static preflight or controller self-checks.
    driver = AdbDevice(plan, lease, Path(args.plan).resolve().parent)
    result = execute(plan, driver, Path(args.plan).resolve().parent / "execution")
    print(json.dumps(result))
    return 0 if result["status"] == "cold-boundary-passed" else 1


if __name__ == "__main__":
    sys.exit(main())
