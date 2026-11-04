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
SHARED_SDK_PROFILE = "p7-main-p8-short-ui-v1"
READINESS_SDK_PROFILE = "p7-main-p8-short-ui-v2"
SAF_SDK_PROFILE = "p7-main-saf-v1"
SAF_READINESS_SDK_PROFILE = "p7-main-saf-v2"
OWNER_METADATA_SDK_PROFILE = "p8-window-owner-metadata-v1"
ACTIVITY_DECOR_SDK_PROFILE = "p8-activity-decor-owner-v1"
FRAMEWORK_SDK_PROFILE = "p7-main-saf-framework-v4"
PLATFORM_PARENT_SDK_PROFILE = "p7-main-saf-framework-parent-v1"
TEST_PROCESS_SDK_PROFILE = "p7-main-saf-framework-test-process-v1"
OBSERVERS_IMPORT_SDK_PROFILE = 'p7-main-saf-framework-observers-import-v1'
PLATFORM_ALIAS_AX_SDK_PROFILE = 'p7-main-saf-platform-alias-ax-v1'
OWNED_WINDOW_PURPOSE = "p8-owned-window-readiness-v1"
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
    # Build purpose is explicit and finite; Native admission retains the original P8 identity.
    sdk_profile = args.sdk_profile
    require(args.readiness_purpose in (None, OWNED_WINDOW_PURPOSE), "Closed original-window readiness purpose")
    require((args.readiness_purpose == OWNED_WINDOW_PURPOSE) == (sdk_profile in (SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE)), "Original-window proof and newly reviewed SAF-v2 SDK must be selected together")
    require(sdk_profile in (PROFILE, SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE), "Closed P8 SDK purpose")
    if sdk_profile in (SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE):
        # Root supplies both pins from immutable external intent, never from this bundle.
        first.sha(args.expected_sdk_seal_sha256)
        first.sha(args.expected_workspace_review_sha256)
        bundle = Path(args.build_bundle)
        seal_raw = capture.private_bytes(bundle / "capture-seal.json", 512 * 1024)
        review_raw = capture.private_bytes(bundle / "workspace-review.json", 131072)
        require(sha(seal_raw) == args.expected_sdk_seal_sha256 and
            sha(review_raw) == args.expected_workspace_review_sha256, "Externally approved SDK/review bytes required")
        seal = capture.parse_json(seal_raw)
        require(type(seal) is dict and seal.get("reviewedStateSha256") == args.expected_workspace_review_sha256,
            "External review pin must be the sealed workspace review")
    else:
        require(args.expected_sdk_seal_sha256 is None and args.expected_workspace_review_sha256 is None,
            "External shared SDK pins are unsupported for the legacy P8 purpose")
    build = capture.verify_build_bundle(args.build_bundle, first, repo, sdk_profile)
    guard = first.read_guard(Path(args.build_bundle) / "source-before.json")
    if sdk_profile in (SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE):
        require(build["sealSha256"] == args.expected_sdk_seal_sha256, "Approved SDK seal changed during typed verification")
        first.exact(guard, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
        if sdk_profile == PLATFORM_ALIAS_AX_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_PLATFORM_ALIAS_AX_OVERLAY_PATHS), "Exact platform-alias-AX shared60 source contract")
            require(first.same(guard, capture.p7_platform_alias_ax_source_guard(first, repo, guard["sourceOverlay"])), "Platform-alias-AX shared905 source drift")
        elif sdk_profile == OBSERVERS_IMPORT_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS), "Exact framework-observers-import shared60 source contract")
            require(first.same(guard, capture.p7_framework_observers_import_source_guard(first, repo, guard["sourceOverlay"])), "Framework-observers-import shared905 source drift")
        elif sdk_profile == TEST_PROCESS_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_FRAMEWORK_TEST_PROCESS_OVERLAY_PATHS), "Exact framework-test-process shared59 source contract")
            require(first.same(guard, capture.p7_framework_test_process_source_guard(first, repo, guard["sourceOverlay"])), "Framework-test-process shared904 source drift")
        elif sdk_profile == PLATFORM_PARENT_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_FRAMEWORK_PARENT_OVERLAY_PATHS), "Exact framework-parent shared59 source contract")
            require(first.same(guard, capture.p7_framework_parent_source_guard(first, repo, guard["sourceOverlay"])), "Framework-parent shared904 source drift")
        elif sdk_profile == FRAMEWORK_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_FRAMEWORK_OVERLAY_PATHS), "Exact framework-v4 shared59 source contract")
            require(first.same(guard, capture.p7_framework_source_guard(first, repo, guard["sourceOverlay"])), "Framework-v4 shared904 source drift")
        elif sdk_profile == ACTIVITY_DECOR_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P8_ACTIVITY_DECOR_OVERLAY_PATHS), "Exact Activity decor owner SDK source contract")
            require(first.same(guard, capture.p8_activity_decor_source_guard(first, repo, guard["sourceOverlay"])), "Activity decor owner SDK source drift")
        elif sdk_profile == OWNER_METADATA_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P8_OWNER_METADATA_OVERLAY_PATHS), "Exact owned-window metadata SDK source contract")
            require(first.same(guard, capture.p8_owner_metadata_source_guard(first, repo, guard["sourceOverlay"])), "Owned-window metadata SDK source drift")
        elif sdk_profile == SAF_READINESS_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_MAIN_SAF_V2_OVERLAY_PATHS), "Exact SAF-v2 shared SDK source contract")
            require(first.same(guard, capture.main_saf_v2_source_guard(first, repo, guard["sourceOverlay"])), "SAF-v2 shared SDK source drift")
        elif sdk_profile == SAF_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.P7_MAIN_SAF_OVERLAY_PATHS), "Exact SAF shared SDK source contract")
            require(first.same(guard, capture.main_saf_source_guard(first, repo, guard["sourceOverlay"])), "SAF shared SDK source drift")
        elif sdk_profile == READINESS_SDK_PROFILE:
            require(set(guard["sourceOverlay"]) == set(capture.MAIN_UI_READINESS_OVERLAY_PATHS), "Exact readiness Main UI source contract")
            require(first.same(guard, capture.main_ui_readiness_source_guard(first, repo, guard["sourceOverlay"])), "Readiness Main UI source drift")
        else:
            require(set(guard["sourceOverlay"]) == set(capture.MAIN_UI_SHARED_OVERLAY_PATHS), "Exact shared Main UI source contract")
            require(first.same(guard, capture.main_ui_shared_source_guard(first, repo, guard["sourceOverlay"])), "Shared Main UI source drift")
    else:
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



# The v1 proof shape/phase set stays closed. This v2-only read validates count-only diagnostics
# carried by the existing owner finalizer, including independent fresh-root sample numbers.
AX_FIELDS = ("easywork-name",) + tuple(name for item in ("agent", "authority", "subject", "host", "workspace", "runtime")
    for name in ("easywork-target-label-" + item, "easywork-target-" + item)) + ("easywork-manual-address",)


def readiness_proof(expected, events, facts):
    finalizers = [event["body"] for event in events if event["phase"] == "owners-confirmed-or-retained-before-scope-cleanup"]
    require(len(finalizers) == 2, "Readiness v2 requires exactly one finalizer per original case")
    for index, body in enumerate(finalizers):
        first.exact(body, {"ownedRun", "ownedScope", "ownedAlias", "pid", "uid", "processNonce", "component", "coverage", "defaultSelections", "details"})
        run = expected["cases"][index]["run"]
        first.integer(body["pid"], 1, 2147483647); first.integer(body["uid"], 1, 2147483647)
        require(body["ownedRun"] == run and body["pid"] == facts["pid"] and body["uid"] == facts["uid"] and
            body["processNonce"] == facts["processNonce"] and body["component"] == "com.agenthub.android.P8MainPreferenceActivity" and
            body["coverage"] == "Main lifecycle subclass; native assembly excluded; no shipping pixels" and
            type(body["defaultSelections"]) is int and body["defaultSelections"] == 0, "Readiness exact original owner/runtime required")
        alias = "p8-main-preference-" + run
        # Bind the two exact Android CE spellings, never an arbitrary absolute parent.
        # The original platform Context may report /data/user/0 while its canonical private
        # Context reports /data/data; both spellings must identify this same UID/package/case.
        user_id = facts["uid"] // 100000
        allowed_scopes = {"/data/user/" + str(user_id) + "/" + expected["package"] + "/no_backup/" + alias}
        if user_id == 0:
            allowed_scopes.add("/data/data/" + expected["package"] + "/no_backup/" + alias)
        acquired = [event["body"] for event in events if event["phase"] == "before-scope-acquisition" and event["body"].get("ownedRun") == run]
        created = [event["body"] for event in events if event["phase"] == "scope-created-before-key-and-Main" and event["body"].get("ownedRun") == run]
        require(len(acquired) == 1 and len(created) == 1, "Exactly one original scope acquisition and publication required")
        original = acquired[0]; canonical = created[0]
        first.exact(original, {"ownedRun", "expectedOwnedScope", "expectedOwnedAlias", "pid", "processNonce"})
        require(original["ownedRun"] == run and original["expectedOwnedScope"] in allowed_scopes and
            original["expectedOwnedAlias"] == alias and original["pid"] == facts["pid"] and
            original["processNonce"] == facts["processNonce"], "Readiness original acquisition scope/owner changed")
        first.exact(canonical, {"ownedRun", "ownedScope", "ownedAlias", "pid", "uid", "processNonce", "component", "coverage", "defaultSelections", "details"})
        require(canonical["ownedScope"] in allowed_scopes and canonical["ownedAlias"] == alias and
            canonical["pid"] == facts["pid"] and canonical["uid"] == facts["uid"] and
            canonical["processNonce"] == facts["processNonce"] and canonical["component"] == body["component"] and
            canonical["coverage"] == body["coverage"] and type(canonical["defaultSelections"]) is int and
            canonical["defaultSelections"] == 0, "Readiness published scope/owner changed")
        require(body["ownedScope"] == canonical["ownedScope"] and body["ownedAlias"] == alias,
            "Readiness finalizer differs from the original acquired private Context")
        for event in events:
            owner = event["body"]
            if owner.get("ownedRun") != run or "ownedScope" not in owner:
                continue
            require(owner["ownedScope"] == canonical["ownedScope"] and owner.get("ownedAlias") == alias and
                owner.get("pid") == facts["pid"] and owner.get("uid") == facts["uid"] and
                owner.get("processNonce") == facts["processNonce"] and owner.get("component") == body["component"],
                "Readiness owner stages must preserve the exact original canonical Context")
        require(type(body["details"]) is str and len(body["details"].encode("utf-8")) <= 32768, "Bounded count-only finalizer required")
        detail = capture.parse_json(body["details"].encode("utf-8"))
        first.exact(detail, {"ownerLedger", "entryLedgers", "activityLedgers", "activityInstances", "completeInstanceSet", "acquisitionsKnown",
            "ownersClosed", "stableFinalDefaultFamiliesConfirmed", "bodyPassed", "nativeAxReadiness"})
        require(all(detail[key] is True for key in ("completeInstanceSet", "acquisitionsKnown", "ownersClosed", "stableFinalDefaultFamiliesConfirmed", "bodyPassed")),
            "Readiness requires complete exact Activity/Session/Context acquisition and release")
        rows = detail["nativeAxReadiness"]
        require(type(rows) is list and len(rows) == 14, "Fourteen original native AX measurements required")
        anchors = set()
        for field, row in zip(AX_FIELDS, rows):
            first.exact(row, {"schema", "field", "expectedAnchor", "windowId", "samples", "freshRootRequests", "matchedSamples", "readySamplesTotal",
                "readySampleNumbers", "visitedNodes", "idExtraNodes", "anchorCount", "pendingCounts", "lastState", "stage", "timeout", "passed",
                "elapsedMs", "nodeCap", "sampleLimit", "deadlineMs", "intervalMs"})
            require(row["schema"] == "p8-native-ax-readiness-v2" and row["field"] == field and row["passed"] is True and
                row["timeout"] is False and row["lastState"] == "ready" and row["stage"] == "complete", "Only complete exact readiness v2 samples pass")
            first.integer(row["expectedAnchor"], 1, 2147483647); first.integer(row["windowId"], 0, 2147483647)
            require(row["expectedAnchor"] not in anchors, "One distinct original Compose anchor per field required"); anchors.add(row["expectedAnchor"])
            first.integer(row["samples"], 2, 64); first.integer(row["freshRootRequests"], 2, 64)
            first.integer(row["matchedSamples"], 2, 2); first.integer(row["readySamplesTotal"], 2, row["samples"])
            require(row["freshRootRequests"] == row["samples"] and
                first.same(row["readySampleNumbers"], [row["samples"] - 1, row["samples"]]), "Two consecutive independent fresh roots required")
            first.integer(row["visitedNodes"], 1, 2048); first.integer(row["idExtraNodes"], 1, row["visitedNodes"])
            first.integer(row["anchorCount"], 1, 1); first.integer(row["elapsedMs"], 0, 5000)
            first.exact(row["pendingCounts"], {"anchor-absent", "node-refresh-pending", "value-pending"})
            for count in row["pendingCounts"].values(): first.integer(count, 0, row["samples"] - 2)
            require(sum(row["pendingCounts"].values()) + row["readySamplesTotal"] == row["samples"], "Every attempt has one bounded outcome")
            require(all(type(row[key]) is int and row[key] == value for key, value in
                (("nodeCap", 2048), ("sampleLimit", 64), ("deadlineMs", 5000), ("intervalMs", 100))), "Exact v2 read-only readiness limits")


def owned_window_readiness_proof(expected, events, facts, *, activity_decor_owner=False):
    binding_ids = set()
    finalizers = [event["body"] for event in events if event["phase"] == "owners-confirmed-or-retained-before-scope-cleanup"]
    require(len(finalizers) == 2, "Original-window v3 requires exactly one finalizer per original case")
    for index, body in enumerate(finalizers):
        first.exact(body, {"ownedRun", "ownedScope", "ownedAlias", "pid", "uid", "processNonce", "component", "coverage", "defaultSelections", "details"})
        run = expected["cases"][index]["run"]
        first.integer(body["pid"], 1, 2147483647); first.integer(body["uid"], 1, 2147483647)
        require(body["ownedRun"] == run and body["pid"] == facts["pid"] and body["uid"] == facts["uid"] and
            body["processNonce"] == facts["processNonce"] and body["component"] == "com.agenthub.android.P8MainPreferenceActivity" and
            body["coverage"] == "Main lifecycle subclass; native assembly excluded; no shipping pixels" and
            type(body["defaultSelections"]) is int and body["defaultSelections"] == 0, "Readiness exact original owner/runtime required")
        alias = "p8-main-preference-" + run
        # Bind the two exact Android CE spellings, never an arbitrary absolute parent.
        # The original platform Context may report /data/user/0 while its canonical private
        # Context reports /data/data; both spellings must identify this same UID/package/case.
        user_id = facts["uid"] // 100000
        allowed_scopes = {"/data/user/" + str(user_id) + "/" + expected["package"] + "/no_backup/" + alias}
        if user_id == 0:
            allowed_scopes.add("/data/data/" + expected["package"] + "/no_backup/" + alias)
        acquired = [event["body"] for event in events if event["phase"] == "before-scope-acquisition" and event["body"].get("ownedRun") == run]
        created = [event["body"] for event in events if event["phase"] == "scope-created-before-key-and-Main" and event["body"].get("ownedRun") == run]
        require(len(acquired) == 1 and len(created) == 1, "Exactly one original scope acquisition and publication required")
        original = acquired[0]; canonical = created[0]
        first.exact(original, {"ownedRun", "expectedOwnedScope", "expectedOwnedAlias", "pid", "processNonce"})
        require(original["ownedRun"] == run and original["expectedOwnedScope"] in allowed_scopes and
            original["expectedOwnedAlias"] == alias and original["pid"] == facts["pid"] and
            original["processNonce"] == facts["processNonce"], "Readiness original acquisition scope/owner changed")
        first.exact(canonical, {"ownedRun", "ownedScope", "ownedAlias", "pid", "uid", "processNonce", "component", "coverage", "defaultSelections", "details"})
        require(canonical["ownedScope"] in allowed_scopes and canonical["ownedAlias"] == alias and
            canonical["pid"] == facts["pid"] and canonical["uid"] == facts["uid"] and
            canonical["processNonce"] == facts["processNonce"] and canonical["component"] == body["component"] and
            canonical["coverage"] == body["coverage"] and type(canonical["defaultSelections"]) is int and
            canonical["defaultSelections"] == 0, "Readiness published scope/owner changed")
        require(body["ownedScope"] == canonical["ownedScope"] and body["ownedAlias"] == alias,
            "Readiness finalizer differs from the original acquired private Context")
        for event in events:
            owner = event["body"]
            if owner.get("ownedRun") != run or "ownedScope" not in owner:
                continue
            require(owner["ownedScope"] == canonical["ownedScope"] and owner.get("ownedAlias") == alias and
                owner.get("pid") == facts["pid"] and owner.get("uid") == facts["uid"] and
                owner.get("processNonce") == facts["processNonce"] and owner.get("component") == body["component"],
                "Readiness owner stages must preserve the exact original canonical Context")
        require(type(body["details"]) is str and len(body["details"].encode("utf-8")) <= 32768, "Bounded count-only finalizer required")
        detail = capture.parse_json(body["details"].encode("utf-8"))
        first.exact(detail, {"ownerLedger", "entryLedgers", "activityLedgers", "activityInstances", "completeInstanceSet", "acquisitionsKnown",
            "ownersClosed", "stableFinalDefaultFamiliesConfirmed", "bodyPassed", "nativeAxReadiness", "nativeAxPreparation", "nativeWindowBindings"})
        require(all(detail[key] is True for key in ("completeInstanceSet", "acquisitionsKnown", "ownersClosed", "stableFinalDefaultFamiliesConfirmed", "bodyPassed")),
            "Readiness requires complete exact Activity/Session/Context acquisition and release")
        preparation = detail["nativeAxPreparation"]
        first.exact(preparation, {"schema", "flags", "quietMs", "globalMs", "budgetMs", "startedElapsedMs", "finishedElapsedMs", "elapsedMs", "beforeAnyActivity", "eventQuietObserved", "rootIdentityProven", "pid", "processNonce"})
        require(preparation["schema"] == "p8-original-ui-automation-preparation-v1" and preparation["pid"] == facts["pid"] and preparation["processNonce"] == facts["processNonce"] and
            preparation["beforeAnyActivity"] is True and preparation["eventQuietObserved"] is True and preparation["rootIdentityProven"] is False, "Preparation is only original flags=0 event quiet, never root identity")
        for key, value in (("flags", 0), ("quietMs", 100), ("globalMs", 1000), ("budgetMs", 1500)):
            require(type(preparation[key]) is int and preparation[key] == value, "Finite unchanged preparation flags/quiet limits")
        for key in ("startedElapsedMs", "finishedElapsedMs"): first.integer(preparation[key], 0, 9223372036854775807)
        first.integer(preparation["elapsedMs"], 0, 1499)
        require(preparation["finishedElapsedMs"] - preparation["startedElapsedMs"] == preparation["elapsedMs"], "Actual cooperative preparation duration")
        if index == 0:
            original_preparation = preparation
        else:
            require(first.same(preparation, original_preparation), "Both cases use the same original prepared UiAutomation connection")
        bindings = detail["nativeWindowBindings"]
        require(type(bindings) is list and len(bindings) == 1, "One original form window binding before this case's field input")
        binding = bindings[0]
        binding_keys = {"schema", "bindingId", "ownedRun", "pid", "processNonce", "launchOrdinal", "windowId", "source", "attachedOwnerCount", "startedElapsedMs", "boundElapsedMs", "elapsedMs", "requests", "requestLimit", "deadlineMs", "beforeFirstFieldInput", "inputOperations"}
        if activity_decor_owner:
            binding_keys |= {"ownerPolicy", "originalActivityDecorRetained", "originalActivityTokenRetained"}
            require(binding.get("ownerPolicy") == "retained-activity-decor-and-exact-dialog-token-v1" and
                binding.get("originalActivityDecorRetained") is True and binding.get("originalActivityTokenRetained") is True,
                "Exact retained original Activity decor and token policy required")
        first.exact(binding, binding_keys)
        first.canonical_uuid(binding["bindingId"]); first.integer(binding["windowId"], 0, 2147483647)
        require(binding["bindingId"] not in binding_ids, "Each original case owns its own window binding"); binding_ids.add(binding["bindingId"])
        require(binding["schema"] == ("p8-owned-focused-window-binding-v2" if activity_decor_owner else "p8-owned-focused-window-binding-v1") and binding["ownedRun"] == run and binding["pid"] == facts["pid"] and binding["processNonce"] == facts["processNonce"] and
            type(binding["launchOrdinal"]) is int and binding["launchOrdinal"] == 0 and binding["source"] == "WindowInspector.getGlobalWindowViews" and binding["beforeFirstFieldInput"] is True, "Original process/Activity/decor binding only")
        first.integer(binding["attachedOwnerCount"], 1, 4); first.integer(binding["requests"], 1, 64); first.integer(binding["elapsedMs"], 0, 4999)
        require(type(binding["requestLimit"]) is int and binding["requestLimit"] == 64 and type(binding["deadlineMs"]) is int and binding["deadlineMs"] == 5000, "Finite read-only pre-input binding limits")
        for key in ("startedElapsedMs", "boundElapsedMs"): first.integer(binding[key], 0, 9223372036854775807)
        require(preparation["finishedElapsedMs"] <= binding["startedElapsedMs"] <= binding["boundElapsedMs"] and binding["boundElapsedMs"] - binding["startedElapsedMs"] == binding["elapsedMs"], "Preparation and actual pre-input binding order")
        inputs = binding["inputOperations"]; names = ("name-replacement", "name-ime", "address-replacement", "address-ime")
        require(type(inputs) is list and len(inputs) == len(names), "Four original field operations, without replay")
        after = binding["boundElapsedMs"]
        for name, operation in zip(names, inputs):
            first.exact(operation, {"name", "startedElapsedMs", "deadlineElapsedMs", "deadlineMs", "beforeElapsedMs", "inputCallElapsedMs", "afterElapsedMs", "outcome"})
            for key in ("startedElapsedMs", "deadlineElapsedMs", "beforeElapsedMs", "inputCallElapsedMs", "afterElapsedMs"): first.integer(operation[key], 0, 9223372036854775807)
            require(type(operation["deadlineMs"]) is int and operation["deadlineMs"] == 5000 and operation["deadlineElapsedMs"] == operation["startedElapsedMs"] + 5000, "One finite original input budget, never reset")
            require(operation["name"] == name and operation["outcome"] == "CONFIRMED" and after <= operation["beforeElapsedMs"] and
                operation["startedElapsedMs"] <= operation["beforeElapsedMs"] <= operation["inputCallElapsedMs"] <= operation["afterElapsedMs"] < operation["deadlineElapsedMs"], "Original ordered input was gated after preparation and completed inside the same budget")
            if name == "name-replacement":
                require(operation["startedElapsedMs"] == binding["startedElapsedMs"], "First input budget includes the original pre-input window binding")
            else:
                require(after <= operation["startedElapsedMs"], "Each later input starts its own budget before any window/whole preparation")
            after = operation["afterElapsedMs"]
        rows = detail["nativeAxReadiness"]
        require(type(rows) is list and len(rows) == 14, "Fourteen original native AX measurements required")
        anchors = set()
        for field, row in zip(AX_FIELDS, rows):
            first.exact(row, {"schema", "field", "windowBindingId", "expectedAnchor", "windowId", "samples", "freshRootRequests", "matchedSamples", "readySamplesTotal",
                "readySampleNumbers", "visitedNodes", "idExtraNodes", "anchorCount", "pendingCounts", "lastState", "stage", "timeout", "passed",
                "elapsedMs", "nodeCap", "sampleLimit", "deadlineMs", "intervalMs"})
            require(row["schema"] == "p8-native-ax-readiness-v3" and row["windowBindingId"] == binding["bindingId"] and row["windowId"] == binding["windowId"] and row["field"] == field and row["passed"] is True and
                row["timeout"] is False and row["lastState"] == "ready" and row["stage"] == "complete", "Only complete exact original-window v3 samples pass")
            first.integer(row["expectedAnchor"], 1, 2147483647); first.integer(row["windowId"], 0, 2147483647)
            require(row["expectedAnchor"] not in anchors, "One distinct original Compose anchor per field required"); anchors.add(row["expectedAnchor"])
            first.integer(row["samples"], 2, 64); first.integer(row["freshRootRequests"], 2, 64)
            first.integer(row["matchedSamples"], 2, 2); first.integer(row["readySamplesTotal"], 2, row["samples"])
            require(row["freshRootRequests"] == row["samples"] and
                first.same(row["readySampleNumbers"], [row["samples"] - 1, row["samples"]]), "Two consecutive independent fresh roots required")
            first.integer(row["visitedNodes"], 1, 2048); first.integer(row["idExtraNodes"], 1, row["visitedNodes"])
            first.integer(row["anchorCount"], 1, 1); first.integer(row["elapsedMs"], 0, 5000)
            first.exact(row["pendingCounts"], {"root-pending", "anchor-absent", "node-refresh-pending", "value-pending"})
            for count in row["pendingCounts"].values(): first.integer(count, 0, row["samples"] - 2)
            require(sum(row["pendingCounts"].values()) + row["readySamplesTotal"] == row["samples"], "Every attempt has one bounded outcome")
            require(all(type(row[key]) is int and row[key] == value for key, value in
                (("nodeCap", 2048), ("sampleLimit", 64), ("deadlineMs", 5000), ("intervalMs", 100))), "Exact v3 read-only readiness limits")


def native_proof(expected, events, observed, *, sdk_profile=PROFILE, readiness_purpose=None):
    require(sdk_profile in (PROFILE, SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE), "Closed P8 proof purpose")
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
        first.exact(case, {"run", "language", "appearance", "choices", "restoredPreference", "protectedOriginalsUnchanged", "stableFinalDefaultsConfirmed", "scopeRemoved", "aliasRemoved", "stress", "activityOwnership"})
        require(all(first.same(case[key], expected["cases"][index][key]) for key in ("run", "language", "appearance")) and
            case["choices"] == ["lan-only", "relay-only", "automatic", "relay-only"] and case["restoredPreference"] == "relay-only" and
            all(case[key] is True for key in ("protectedOriginalsUnchanged", "stableFinalDefaultsConfirmed", "scopeRemoved", "aliasRemoved")), "Native menu/restoration/cleanup case mismatch")
        ownership = case["activityOwnership"]
        first.exact(ownership, {"schema", "launches", "activities", "bindings", "entries", "completeInstanceSet", "allActivitiesClosed"})
        require(ownership["schema"] == "p8-finite-activity-ownership-v1" and
            all(type(ownership[key]) is int and ownership[key] == count for key, count in
                (("launches", 2), ("activities", 2), ("bindings", 2), ("entries", 3))) and
            ownership["completeInstanceSet"] is True and ownership["allActivitiesClosed"] is True,
            "Exactly two known Activity/bound Session owners and seed, closed without recreation")
        stress = case["stress"]
        first.exact(stress, {"fontScale", "orientation", "windowWidthDp", "windowHeightDp", "sheetHeightDp", "longNameLength",
            "manualAddressLength", "longTargetFields", "nameAutosaved", "targetValuesExact", "manualAddressSemanticsExact",
            "scrollReachedRuntime", "popupBackDismissed", "sheetBackDismissed", "composeFontScale", "multilineTargetFields", "noTargetEllipsis", "nativeAxReadback", "nativeAxFields", "manualAddressSubmitted", "talkBackExecuted", "nameRestoredInNewActivity"})
        require(type(stress["fontScale"]) in (int, float) and stress["fontScale"] == 2 and type(stress["composeFontScale"]) is int and stress["composeFontScale"] == 2 and stress["orientation"] == "landscape", "Exact per-Activity large font/orientation")
        require(type(stress["windowHeightDp"]) is int and 180 <= stress["windowHeightDp"] <= 400 and
            type(stress["windowWidthDp"]) is int and stress["windowWidthDp"] > stress["windowHeightDp"] and
            type(stress["sheetHeightDp"]) in (int, float) and 120 <= stress["sheetHeightDp"] <= 400, "Measured real short viewport required")
        require(type(stress["longNameLength"]) is int and 128 <= stress["longNameLength"] <= 256 and
            type(stress["manualAddressLength"]) is int and 128 <= stress["manualAddressLength"] <= 512 and
            type(stress["longTargetFields"]) is int and stress["longTargetFields"] == 6 and type(stress["multilineTargetFields"]) is int and stress["multilineTargetFields"] == 5 and type(stress["nativeAxFields"]) is int and stress["nativeAxFields"] == 14, "Bounded complete long-field fixture required")
        require(all(stress[key] is True for key in ("nameAutosaved", "targetValuesExact", "manualAddressSemanticsExact", "scrollReachedRuntime",
            "popupBackDismissed", "sheetBackDismissed", "nameRestoredInNewActivity", "nativeAxReadback", "noTargetEllipsis")) and stress["manualAddressSubmitted"] is False and
            stress["talkBackExecuted"] is False, "Exact limited semantics/restore/Back scope")
    case_ids = {row["run"] for row in expected["cases"]}
    for event in events[1:-1]:
        body = event["body"]; require(body.get("ownedRun") in case_ids, "Foreign fixture scope evidence")
        require(body.get("pid") == facts["pid"] and body.get("processNonce") == facts["processNonce"], "Evidence process changed")
    require(readiness_purpose in (None, OWNED_WINDOW_PURPOSE), "Closed Native proof purpose")
    require((readiness_purpose == OWNED_WINDOW_PURPOSE) == (sdk_profile in (SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE)), "Original-window proof requires the explicit SAF-v2 purpose")
    if readiness_purpose == OWNED_WINDOW_PURPOSE:
        require(sdk_profile in (SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE), "Only explicitly reviewed original-window SDK owns this proof")
        owned_window_readiness_proof(expected, events, facts, activity_decor_owner=sdk_profile in (ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE))
    elif sdk_profile in (READINESS_SDK_PROFILE, SAF_SDK_PROFILE):
        readiness_proof(expected, events, facts)
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
    if args.readiness_purpose == OWNED_WINDOW_PURPOSE:
        invocation["schema"] = "p8-main-owned-window-one-shot-invocation-v1"
        invocation["readinessPurpose"] = OWNED_WINDOW_PURPOSE
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
    parsed, events = junit(result["stdout"], value); proof = native_proof(value, events, observed, sdk_profile=args.sdk_profile, readiness_purpose=args.readiness_purpose)
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
    if args.sdk_profile in (SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE):
        # The v2 result also requires its strict per-case/owner/readiness evidence above.
        review["schema"] = ("p8-main-subclass-saf-shared-sdk-host-review-v1" if args.sdk_profile == SAF_SDK_PROFILE
            else ("p8-main-subclass-readiness-sdk-host-review-v2" if args.sdk_profile == READINESS_SDK_PROFILE
                else "p8-main-subclass-shared-sdk-host-review-v1"))
        review["sdkProfile"] = args.sdk_profile
        review["expectedSdkSealSha256"] = args.expected_sdk_seal_sha256
        review["expectedWorkspaceReviewSha256"] = args.expected_workspace_review_sha256
    if args.readiness_purpose == OWNED_WINDOW_PURPOSE:
        review["schema"] = "p8-main-owned-window-readiness-host-review-v1"
        review["readinessPurpose"] = OWNED_WINDOW_PURPOSE
    first.write_new(output / "host-review.json", review)
    print(json.dumps({"schema": review["schema"], "run": args.run, "status": review["status"], "reviewSha256": first.digest(output / "host-review.json")}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "output-dir", "app-apk", "test-apk", "adb", "serial", "run", "boot-uuid", "build-bundle"):
        parser.add_argument("--" + name, required=True)
    for name in ("uid", "test-uid"): parser.add_argument("--" + name, type=int, required=True)
    parser.add_argument("--sdk-profile", choices=(PROFILE, SHARED_SDK_PROFILE, READINESS_SDK_PROFILE, SAF_SDK_PROFILE, SAF_READINESS_SDK_PROFILE, OWNER_METADATA_SDK_PROFILE, ACTIVITY_DECOR_SDK_PROFILE, FRAMEWORK_SDK_PROFILE, PLATFORM_PARENT_SDK_PROFILE, TEST_PROCESS_SDK_PROFILE, OBSERVERS_IMPORT_SDK_PROFILE, PLATFORM_ALIAS_AX_SDK_PROFILE), default=PROFILE,
        help="Explicit captured SDK purpose; default retains the legacy P8 contract")
    parser.add_argument("--readiness-purpose", choices=(OWNED_WINDOW_PURPOSE,), default=None,
        help="Explicit new original-window v3 proof; old readiness proof contracts remain closed")
    parser.add_argument("--expected-sdk-seal-sha256", default=None,
        help="Root external immutable approval; required only with the shared SDK purpose")
    parser.add_argument("--expected-workspace-review-sha256", default=None,
        help="Root external immutable approval; required only with the shared SDK purpose")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
