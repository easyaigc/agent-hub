#!/usr/bin/env python3
"""Closed voice originals Controller proof library. Reuses captured SDK/source guard/Popen; never builds, installs or kills App."""
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


first = module("voice_originals_sources", "android-nonempty-device-first.py")
pair = module("voice_originals_device", "android-voice-originals-workers.py")
capture = module("voice_originals_capture", "android-sdk-capture.py")
require = first.require
encoded = first.encoded
sha = lambda raw: hashlib.sha256(raw).hexdigest()
TOOL = "scripts/mobile/android-voice-originals-controller.py"
OVERLAYS = ['apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py']
PROFILE = "p7-voice-originals"
SCHEMA = "p7-voice-originals-controller-v1"
ADMISSION_SCHEMA = "p7-voice-originals-first-admission-v1"
METHOD = "voiceOriginalsControllerProducesPortableArchives"
SELECTOR = "com.agenthub.android.NativeMigrationVoiceOriginalsDeviceSessionTest#" + METHOD
MODE = "voice-first"
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
        "rootNamespace": "installation", "sourceNamespace": "source", "sourceInstallation": "voice-source-installation", "safPickerCovered": False, "uiCovered": False, "coldProcessCovered": False}


def admitted(args):
    profile = PROFILE
    tool = "scripts/mobile/android-voice-originals-pair.py"
    method = METHOD; schema = SCHEMA; mode = MODE
    first.canonical_uuid(args.run); first.canonical_uuid(args.boot_uuid); first.integer(args.uid, 1, 2147483647)
    repo = Path(args.repo).resolve(); require(Path(__file__).resolve() == repo / TOOL, "Run only the reviewed in-repo adapter")
    # _review and verify_build_bundle enforce the finite twenty-four-entry Root before/after overlay,
    # full source inventory, real build waits, original tools, artifacts, certificates and seal.
    build = capture.verify_build_bundle(args.build_bundle, first, repo, profile)
    bundle = Path(args.build_bundle)
    guard = first.read_guard(bundle / "source-before.json")
    require(set(guard["sourceOverlay"]) == set(OVERLAYS), "Explicit Controller profile overlay inventory required")
    require(first.same(guard, first.source_guard(repo, guard["sourceOverlay"])), "Voice sources drifted")
    for role, path in (("app", args.app_apk), ("test", args.test_apk)):
        require(Path(path).resolve() == Path(build["artifacts"][role]["path"]).resolve() and first.digest(path) == build["artifacts"][role]["sha256"], "Only exact controlled output APKs admitted")
    require(Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve() and first.digest(args.adb) == build["tools"]["adb"]["sha256"], "Admitted SDK ADB only")
    value = {"schema": ADMISSION_SCHEMA,
        "caseSchema": schema, "run": args.run, "selectedMethod": method,
        "selector": "com.agenthub.android.NativeMigrationVoiceOriginalsDeviceSessionTest#" + method,
        "mode": mode, "package": first.PACKAGE, "uid": args.uid, "bootUuid": args.boot_uuid,
        "appApkSha256": build["artifacts"]["app"]["sha256"], "testApkSha256": build["artifacts"]["test"]["sha256"],
        "caseSourceSha256": guard["sources"]["apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt"], "wrapperSourceSha256": guard["sources"]["apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt"],
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
    fixed = {"package", "uid", "run", "filesRoot", "dataScopeIdentity", "runDirectory", "no_backup", "cache", "files", "voice_source_no_backup"}
    require(fixed <= set(value) and "ancestor:/" in value and all(name in fixed or name.startswith(("ancestor:/", "observed-mount:/")) for name in value), "Anchor names")
    require(all(type(name) is str and len(name) <= 512 and type(text) is str and 1 <= len(text) <= 16384 for name, text in value.items()), "Anchor scalar quota")
    require(value["package"] == first.PACKAGE and value["uid"] == str(uid) and value["run"] == run and
        value["filesRoot"].startswith("/data/") and value["filesRoot"].endswith("/" + first.PACKAGE + "/files"), "Owned package files anchor")
    for name in ("dataScopeIdentity", "runDirectory", "no_backup", "cache", "files", "voice_source_no_backup"):
        parts = value[name].split(":"); require(len(parts) == 4 and all(re.fullmatch(r"[0-9]{1,24}", part) for part in parts), "Anchor directory tuple")
        _, _, owner, mode = map(int, parts)
        require(owner == uid and stat.S_ISDIR(mode) and stat.S_IMODE(mode) in ({0o700, 0o771} if name == "dataScopeIdentity" else {0o700}), "Anchor directory owner/mode")


def verify_native(intent_raw, proof_raw, args, admission, observed):
    schema = SCHEMA; method = METHOD
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
    require(type(proof["receipt"]["records"]) is int and proof["receipt"]["records"] == 16, "Closed source record count")
    for name, cap in (("sourceCiphertext", 64), ("originalCandidateCiphertext", 64), ("destinationCiphertext", 128),
        ("originalTextSha256", 11), ("originalBinarySha256", 5), ("sourceTextSha256", 32), ("sourceBinarySha256", 3),
        ("activeTextSha256", 32), ("activeBinarySha256", 5)):
        first.digests(proof[name], cap)
    text = proof["originalTextSha256"]
    fixed = {"metadata", "codex-private-connections-v1", "mobile-reading-ledger-v1", "native-chat-notifications-v1", "native-chat-notification-sounds-v1"}
    voice_text = {name for name in text if name in {"voice-index-v1", "migration-provisional-voice-v1"} or name.startswith("voice-recording:")}
    sealed_rows = {name for name in voice_text if name.startswith("voice-recording:")}
    require(len(text) == 11 and len(voice_text) == 3 and len(sealed_rows) == 1, "Closed eleven-text voice profile")
    sealed = next(iter(sealed_rows)).removeprefix("voice-recording:"); first.canonical_uuid(sealed)
    base_text = set(text) - voice_text; extra = base_text - fixed
    require(fixed <= base_text and len(base_text) == 8 and len(extra) == 3 and all(sum(name.startswith(prefix) for name in extra) == 1 for prefix in ("operations:", "submission:", "draft-file:")), "Eight unchanged base records")
    binaries = proof["originalBinarySha256"]; source_binary = proof["sourceBinarySha256"]
    raw_prefix = "migration-provisional-raw-v1:"; committed_prefix = "migration-provisional-audio-v2:"
    raw_names = {name for name in binaries if name.startswith(raw_prefix)}
    committed_names = {name for name in binaries if name.startswith(committed_prefix)}
    require(len(binaries) == 5 and len(raw_names) == 2 and len(committed_names) == 1 and "voice-audio:" + sealed in binaries, "Five exact portable binaries")
    raw_ids = {name.removeprefix(raw_prefix) for name in raw_names}
    for value in raw_ids: first.canonical_uuid(value)
    interrupted = next(iter(committed_names)).removeprefix(committed_prefix)
    require(interrupted in raw_ids and sealed not in raw_ids, "Interrupted and sealed original identities")
    sound_names = set(binaries) - raw_names - committed_names - {"voice-audio:" + sealed}
    require(len(sound_names) == 1, "One original normalized notification CAF")
    sound = next(iter(sound_names)); require(sound.startswith("native-chat-notification-sound-v1:") and sound.endswith(".caf"), "Closed CAF name")
    first.canonical_uuid(sound.removeprefix("native-chat-notification-sound-v1:").removesuffix(".caf"))
    require(set(source_binary) == {sound, "voice-audio:" + sealed, "voice-audio:" + interrupted} and
        source_binary[sound] == binaries[sound] and source_binary["voice-audio:" + sealed] == binaries["voice-audio:" + sealed] and
        source_binary["voice-audio:" + interrupted] == binaries[committed_prefix + interrupted] == binaries[raw_prefix + interrupted] and
        binaries == proof["activeBinarySha256"], "Original source encrypted bytes and portable raw/uncommitted identities")
    source_rows = {name for name in proof["sourceTextSha256"] if name.startswith("voice-recording:")}
    require(source_rows == {"voice-recording:" + value for value in raw_ids | {sealed}} and "voice-index-v1" in proof["sourceTextSha256"] and
        not raw_ids.intersection({name.removeprefix("voice-recording:") for name in proof["activeTextSha256"] if name.startswith("voice-recording:")}), "No imported provisional active recorder")
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
