#!/usr/bin/env python3
"""Finite nonempty notification Controller first + same-boot existing-only cold. No install/build/kill/retry."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


first = module("foreground_first_sources", "android-nonempty-device-first.py")
pair = module("foreground_pair_workers", "android-nonempty-device-pair.py")
controller = module("foreground_controller_contract", "android-nonempty-pristine-first.py")
capture = module("foreground_sdk_capture", "android-sdk-capture.py")
require = first.require
encoded = first.encoded
sha = lambda raw: hashlib.sha256(raw).hexdigest()
TOOL = "scripts/mobile/android-nonempty-foreground-pair.py"
METHOD = "nonemptyForegroundControllerProducesPortableArchives"
COLD_METHOD = "nonemptyForegroundReadOnlyColdAfterExactOriginalExit"
COLD_SCHEMA = "p7-nonempty-foreground-readonly-cold-v1"
PREFIX = "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#"


def arguments(run, bound, *, child_originals=False, existing_originals=False):
    method = controller.EXISTING_METHOD if existing_originals else controller.CHILD_METHOD if child_originals else METHOD
    return pair.expected_first_arguments(run, bound) | {"class": PREFIX + method,
        "nonemptyMode": "existing-originals-first" if existing_originals else "child-originals-first" if child_originals else "foreground-first"}


def cold_arguments(original_arguments, admission, *, child_originals=False, existing_originals=False):
    raw = encoded(admission); require(len(raw) <= 8192, "Cold admission argument bound")
    return original_arguments | {"class": PREFIX + (controller.EXISTING_COLD_METHOD if existing_originals else "childOriginalsReadOnlyColdAfterExactOriginalExit" if child_originals else COLD_METHOD),
        "nonemptyMode": "existing-originals-cold" if existing_originals else "child-originals-cold" if child_originals else "foreground-cold",
        "nonemptyFirstIntentSha256": admission["firstIntentSha256"], "nonemptyFirstProofSha256": admission["firstProofSha256"],
        "nonemptyExitAdmissionSha256": sha(raw), "nonemptyExitAdmissionJson": raw.decode("utf-8")}


def completed_worker(args, phase, expected_arguments, admission_sha, method):
    output = Path(args.output_dir)
    invocation = first.read_json(output / (phase + "-invocation.json"))
    pair.checked_invocation(invocation, args.run, phase, expected_arguments, admission_sha,
        first.digest(args.adb), sha(args.serial.encode()))
    return pair.worker_receipt(output / (phase + "-worker.json"), invocation,
        output / (phase + "-runner.stdout.bin"), output / (phase + "-junit.json"), phase, args.repo,
        selected_method=method)


def verify_cold(args, first_proof, admission, intent_raw, proof_raw, observed, *, child_originals=False, existing_originals=False):
    cold_schema = "p7-existing-child-originals-readonly-cold-v1" if existing_originals else "p7-child-originals-readonly-cold-v1" if child_originals else COLD_SCHEMA
    cold_method = controller.EXISTING_COLD_METHOD if existing_originals else "childOriginalsReadOnlyColdAfterExactOriginalExit" if child_originals else COLD_METHOD
    intent = capture.parse_json(intent_raw); proof = capture.parse_json(proof_raw)
    common = {"schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor", "firstIntentSha256",
        "firstProofSha256", "exitAdmissionSha256", "firstPid", "firstStartTicks"}
    first.exact(intent, common)
    first.exact(proof, common | {"clearFramingSha256", "sealedArchiveSha256", "generation", "draftKey", "draftTextSha256",
        "activeTextSha256", "activeBinarySha256", "protectedDataSha256", "fixtureAliasesSha256", "readerCloseReturns",
        "protectedDataUnchanged", "fixtureAliasesUnchanged"})
    require(all(first.same(intent[key], proof[key]) for key in common), "Cold intent/proof changed")
    require(proof["schema"] == cold_schema and proof["selectedMethod"] == cold_method and proof["run"] == args.run and
        first.same(proof["binding"], first_proof["binding"]) and first.same(proof["anchor"], first_proof["anchor"]), "Exact foreground cold profile/binding/anchor")
    for name in ("firstIntentSha256", "firstProofSha256"):
        require(proof[name] == admission[name], "Original immutable document changed")
    require(proof["exitAdmissionSha256"] == sha(encoded(admission)) and type(proof["firstPid"]) is int and
        proof["firstPid"] == first_proof["pid"] and proof["firstStartTicks"] == first_proof["startTicks"], "Original tuple/admission join")
    pair.process(observed, first_proof["binding"])
    require(observed["intentSha256"] == sha(intent_raw) and type(proof["pid"]) is int and proof["pid"] == observed["pid"] and
        proof["startTicks"] == observed["startTicks"] and proof["pid"] != first_proof["pid"] and
        proof["startTicks"] != first_proof["startTicks"], "Fresh independently observed same-boot cold tuple")
    pins = {"clearFramingSha256": first_proof["receipt"]["sha256"], "sealedArchiveSha256": first_proof["archiveSha256"],
        "generation": first_proof["generation"], "draftKey": first_proof["draftKey"], "draftTextSha256": first_proof["draftTextSha256"],
        "activeTextSha256": first_proof["activeTextSha256"], "activeBinarySha256": first_proof["activeBinarySha256"]}
    for name, expected in pins.items():
        require(first.same(proof[name], expected), "Cold persisted pin changed: " + name)
    require(type(proof["readerCloseReturns"]) is int and proof["readerCloseReturns"] == 2 and
        proof["protectedDataUnchanged"] is True and proof["fixtureAliasesUnchanged"] is True, "Actual reader close/protected-data gate")
    for name in ("protectedDataSha256", "fixtureAliasesSha256"): first.sha(proof[name])
    return proof


def run_pair(args, *, child_originals=False, existing_originals=False):
    require(type(child_originals) is bool and type(existing_originals) is bool and (not existing_originals or child_originals), "Closed shared Controller pair profile")
    method = controller.EXISTING_METHOD if existing_originals else controller.CHILD_METHOD if child_originals else METHOD
    cold_method = controller.EXISTING_COLD_METHOD if existing_originals else "childOriginalsReadOnlyColdAfterExactOriginalExit" if child_originals else COLD_METHOD
    os.umask(0o077)
    require(Path(__file__).resolve() == Path(args.repo).resolve() / TOOL, "Run only reviewed in-repo foreground adapter")
    output = capture.private_directory(args.output_dir, empty=True)
    guard, admission, build = controller.admitted(args, foreground=not child_originals, child_originals=child_originals, existing_originals=existing_originals)
    device = pair.Device(args); require(device.boot() == args.boot_uuid, "Admitted boot differs")
    installed = {first.PACKAGE: admission["appApkSha256"], first.TEST_PACKAGE: admission["testApkSha256"]}
    require(device.installed() == installed, "Installed App/Test differ from captured SDK artifacts")
    sdk = device.success(["getprop", "ro.build.version.sdk"]).strip(); require(re.fullmatch(rb"[0-9]{1,3}", sdk), "Actual SDK version")
    first.integer(int(sdk), 26, 1000)
    adb_version = device.call(["version"], 8192)
    require(adb_version.returncode == 0 and not adb_version.stderr and adb_version.stdout.startswith(b"Android Debug Bridge version"), "Actual captured ADB version")
    first.write_new(output / "sdk-observation.json", {"schema": "p7-existing-child-originals-sdk-observation-v1" if existing_originals else "p7-child-originals-sdk-observation-v1" if child_originals else "p7-foreground-sdk-observation-v1", "deviceApi": int(sdk),
        "adbVersion": adb_version.stdout.decode(), "buildSealSha256": build["sealSha256"]})
    first.write_guard(output / "source-guard.json", guard); first.write_new(output / "source-admission.json", admission)
    admission_sha = first.digest(output / "source-admission.json"); bound = first.binding(admission, admission_sha)
    native_arguments = arguments(args.run, bound, child_originals=child_originals, existing_originals=existing_originals)
    first.write_new(output / "instrumentation-arguments.json", native_arguments)
    original = {}; pair.worker(args, device, "first", native_arguments, admission_sha, original, selected_method=method)
    documents = {}
    for name in ("first-intent", "first-proof"):
        result = device.document(name); require(result.returncode == 0 and not result.stderr, "Missing original Native document")
        pair.write_native(output / (name + ".json"), result.stdout); documents[name] = result.stdout
    proof = controller.verify_native(documents["first-intent"], documents["first-proof"], args, admission, original,
        foreground=not child_originals, child_originals=child_originals, existing_originals=existing_originals)
    worker, worker_sha = completed_worker(args, "first", native_arguments, admission_sha, method)
    deadline = time.monotonic() + 30; exited = None
    while time.monotonic() < deadline:
        exited = device.exited(original)
        if exited is not None: break
        time.sleep(0.1)
    require(exited is not None, "Original process has not naturally exited; retain exact scope, never kill/retry")
    pair.exit_observation(exited, original); first.write_new(output / "exit-process.json", exited)

    def stable():
        current_guard, current_admission, current_build = controller.admitted(args, foreground=not child_originals, child_originals=child_originals, existing_originals=existing_originals)
        require(first.same(current_guard, guard) and first.same(current_admission, admission) and
            current_build["sealSha256"] == build["sealSha256"] and device.installed() == installed and
            device.success(["getprop", "ro.build.version.sdk"]).strip() == sdk and
            device.call(["version"], 8192).stdout == adb_version.stdout and device.boot() == args.boot_uuid, "Source/APK/tool/SDK/same-boot drift")
    stable()
    exit_admission = {"schema": first.EXIT_SCHEMA, "run": args.run, "binding": bound,
        "firstIntentSha256": sha(documents["first-intent"]), "firstProofSha256": sha(documents["first-proof"]),
        "firstPid": original["pid"], "firstStartTicks": original["startTicks"], "firstWorkerReceiptSha256": worker_sha,
        "firstRawSha256": worker["rawSha256"], "firstJUnitSha256": worker["junitSha256"],
        "sourceGuardSha256": bound["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"],
        "adbSha256": first.digest(args.adb), "deviceSerialSha256": sha(args.serial.encode()),
        "sdkVersionSha256": first.digest(output / "sdk-observation.json"), "exitKind": exited["kind"],
        "exitObservationSha256": first.digest(output / "exit-process.json")}
    first.write_new(output / "cold-admission.json", exit_admission)
    cold_args = cold_arguments(native_arguments, exit_admission, child_originals=child_originals, existing_originals=existing_originals)
    first.write_new(output / "cold-instrumentation-arguments.json", cold_args)
    fresh = {}; pair.worker(args, device, "readonly-cold", cold_args, sha(encoded(exit_admission)), fresh, selected_method=cold_method)
    cold_documents = {}
    for name in ("cold-intent", "cold-proof"):
        result = device.document(name); require(result.returncode == 0 and not result.stderr, "Missing cold Native document")
        pair.write_native(output / (name + ".json"), result.stdout); cold_documents[name] = result.stdout
    verify_cold(args, proof, exit_admission, cold_documents["cold-intent"], cold_documents["cold-proof"], fresh, child_originals=child_originals, existing_originals=existing_originals)
    _, cold_worker_sha = completed_worker(args, "readonly-cold", cold_args, sha(encoded(exit_admission)), cold_method)
    final_exit = device.exited(original); require(final_exit is not None, "Original process exit no longer established")
    pair.exit_observation(final_exit, original); first.write_new(output / "final-exit-process.json", final_exit)
    stable()
    # Re-read immutable control evidence after cold; no normalization or old first proof is accepted.
    for name, raw in documents.items():
        result = device.document(name); require(result.returncode == 0 and not result.stderr and result.stdout == raw, "Original document changed after cold")
    result = {"schema": "p7-existing-child-originals-paired-host-review-v1" if existing_originals else "p7-child-originals-paired-host-review-v1" if child_originals else "p7-nonempty-foreground-paired-host-review-v1", "run": args.run,
        "selectedMethod": method, "coldMethod": cold_method, "sourceRecords": 33 if child_originals else 13,
        "firstProofSha256": sha(documents["first-proof"]), "coldProofSha256": sha(cold_documents["cold-proof"]),
        "exitAdmissionSha256": sha(encoded(exit_admission)), "firstWorkerReceiptSha256": worker_sha,
        "coldWorkerReceiptSha256": cold_worker_sha, "buildSealSha256": build["sealSha256"],
        "sourceGuardSha256": bound["sourceGuardSha256"], "sourceAdmissionSha256": admission_sha,
        "pairedReadonlyColdAccepted": True, "sameBootEstablished": True, "explicitFreshEnrollmentRequired": not child_originals,
        "sourceOriginalProtocols": ["external-inbox-v2", "document-journal-v2", "photo-journal-v2", "selection-payloads-v2"] if child_originals else ["identity-v1", "foreground-v1", "recovery-v2", "authorizations-v1"],
        "protectedDataScope": ["run/no_backup", "run/cache", "run/files", "run/first-intent.json", "run/first-proof.json"],
        "newControlFiles": ["run/cold-intent.json", "run/cold-proof.json"],
        "safPickerAccepted": False, "uiAccepted": False, "full24Accepted": False, "physicalDeviceClaimed": False,
        "notificationDeliveryClaimed": False, "keystoreInternalDiskUnchangedClaimed": False, "fdCountClaimed": False,
        "controllerStrictGraphClaimed": False, "fixtureRetainedForRootReconciliation": True}
    if existing_originals:
        result["destinationSourceRecords"] = 24; result["activeRecords"] = 56
        result["sameInternalRequestIdsPreservedInSeparateArchives"] = True
        result["delegatedDestinationChildRetirements"] = proof["delegatedDestinationChildRetirements"]
    first.write_new(output / "paired-host-review.json", result); print(json.dumps(result))


def main(*, child_originals=False, existing_originals=False):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("run-pair")
    for name in ("repo", "output-dir", "app-apk", "test-apk", "adb", "serial", "run", "boot-uuid", "build-bundle"):
        command.add_argument("--" + name, required=True)
    command.add_argument("--uid", required=True, type=int)
    run_pair(parser.parse_args(), child_originals=child_originals, existing_originals=existing_originals)


if __name__ == "__main__":
    main()
