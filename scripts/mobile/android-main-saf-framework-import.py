#!/usr/bin/env python3
"""Source proposal: one separately admitted framework-v4 IMPORT only.

The producer is a completed, separately admitted original Native operation. This entry
never launches producer/EXPORT, prepares a replacement source, installs, clears, retries,
or replays. Existing EXPORT/private scopes are not adopted. SDK/profile/source and every
producer material must be independently pinned by Root before the new prepare is issued.
Consumer FD-close is distinct from decrypt/preview assertions and full-P7 acceptance.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
import time
from types import SimpleNamespace
import uuid
sys.dont_write_bytecode = True

TOOL = "scripts/mobile/android-main-saf-framework-import.py"
EXPORT_HELPER = "scripts/mobile/android-main-saf-framework.py"
SDK_PROFILE = 'p7-main-saf-framework-observers-import-v1'
SOURCE_GUARD_FUNCTION = 'p7_framework_observers_import_source_guard'
PROFILE = "p7-main-saf-framework-v4"
APP = "com.easyai.agenthub.android.acceptance"
TEST = APP + ".test"
CONTROL = TEST + ".p7-main-saf-control-v4"
DOCUMENTS = TEST + ".p7-main-saf-v3"
DOCUMENT_PROCESS = TEST + ":p7MainSafV3"
NAMESPACE = "files/p7-main-saf-owned-v3"
NATIVE_SELECTOR = "com.agenthub.android.NativeMigrationMainUiTest#actualMainSafOpenStagesOnlyOwnedEncryptedDocument"
PRODUCER_SELECTOR = "com.agenthub.android.NativeMigrationMainSafProducerTest#produceOnlySyntheticImportCiphertext"
CAP, PAYLOAD_CAP, STDERR_CAP = 16384, 2 * 1024 * 1024, 2 * 1024 * 1024
ZERO = "0" * 64
b = None
PROTECTED_IDS = frozenset((
    "fab4fe68-408f-4308-8157-a95fedc1a97f", "863ec69e-e647-4f5e-bdd0-5b294b9823ac",
    "ece33c64-7446-4fb6-8d69-deb971dd91da", "01a17d28-2dfd-74c2-99fd-70e58e239086",
    "c6090df6-a664-4f57-af56-ebfbc16549aa", "73d684dd-9135-457f-a966-f5e9da9c2256",
    "1419f7b0-5eea-4d13-ac85-970b92e3679a",
    "15b2a558-ac1e-406c-b801-d22c34db8acb", "17cbb017-0113-4765-ad44-ae477b38b9be",
    "bc14c713-91a7-484c-bfa7-dbab06ac9098"))
ROOT_EXTRA_KEYS = {"exportHelperSha256", "producerOperationId", "sourceProducerProofSha256",
    "sourcePayloadBytes", "sourcePayloadSha256"}
SOURCE_FILES = frozenset(("producer-binding.json", "producer-receipt.json", "producer-process.json",
    "producer-exit.json", "producer-intent.json", "producer-wait.json", "producer.stdout.bin",
    "producer.stderr.bin", "source-read-intent.json", "source-read-wait.json",
    "payload.bin", "source-read.stderr.bin", "local-publication.json"))

def require(value, message):
    if not value:
        raise ValueError(message)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")

def sha(value):
    require(type(value) is str and re.fullmatch(r"[a-f0-9]{64}", value), "Exact SHA-256")

def exact_json(raw):
    require(type(raw) is bytes and 0 < len(raw) <= CAP, "Bounded metadata")
    def pairs(rows):
        value = {}
        for key, item in rows:
            require(key not in value, "Duplicate JSON key")
            value[key] = item
        return value
    return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))

def read_private(path, cap, allow_empty=False):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path.absolute(), "Exact canonical private input")
    parent = path.parent.lstat()
    require(stat.S_ISDIR(parent.st_mode) and parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700,
        "Exact private input parent")
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and before.st_nlink == 1 and
        stat.S_IMODE(before.st_mode) == 0o600 and (0 <= before.st_size <= cap if allow_empty else 0 < before.st_size <= cap),
        "Private bounded regular input")
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    keys = lambda s: (s.st_dev, s.st_ino, s.st_uid, s.st_mode, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
    try:
        require(keys(os.fstat(fd)) == keys(before), "Private input open raced")
        raw = bytearray()
        while True:
            data = os.read(fd, min(65536, cap + 1 - len(raw)))
            if not data:
                break
            raw.extend(data)
            require(len(raw) <= cap, "Private input byte cap")
        require(len(raw) == before.st_size and keys(os.fstat(fd)) == keys(before) == keys(path.lstat()), "Private input changed")
        after_parent = path.parent.lstat()
        require((parent.st_dev, parent.st_ino, parent.st_mode, parent.st_uid) ==
            (after_parent.st_dev, after_parent.st_ino, after_parent.st_mode, after_parent.st_uid), "Private parent changed")
        return bytes(raw)
    finally:
        os.close(fd)

def load_base(args):
    global b
    raw = read_private(args.root_approval, CAP)
    sha(args.root_approval_sha256)
    require(digest(raw) == args.root_approval_sha256, "External Root approval pin")
    root = exact_json(raw)
    require(type(root) is dict and "exportHelperSha256" in root, "Root must pin the exact shared helper")
    sha(root["exportHelperSha256"])
    repo = Path(args.repo).resolve()
    helper = repo / EXPORT_HELPER
    st = helper.lstat()
    require(helper.resolve() == helper and stat.S_ISREG(st.st_mode) and st.st_nlink == 1 and
        digest(helper.read_bytes()) == root["exportHelperSha256"], "Shared helper identity/pin")
    spec = importlib.util.spec_from_file_location("p7_framework_import_shared", helper)
    b = importlib.util.module_from_spec(spec); sys.modules[spec.name] = b; spec.loader.exec_module(b)
    require(all(getattr(b, key) == globals()[key] for key in
        ("PROFILE", "APP", "TEST", "CONTROL", "DOCUMENTS", "DOCUMENT_PROCESS", "NAMESPACE", "CAP", "PAYLOAD_CAP", "STDERR_CAP", "ZERO")),
        "Closed shared protocol constants changed")
    return raw, root

def root_approval(args):
    raw, root = load_base(args)
    require(set(root) == b.ROOT_KEYS | ROOT_EXTRA_KEYS and root["schema"] == "p7-framework-import-root-approval-v1" and root["kind"] == "IMPORT",
        "Exact separately admitted IMPORT Root schema")
    ids = [root[k] for k in ("run", "operationId", "nativeOperationId", "producerOperationId")]
    for value in ids + [root["bootUuid"]]:
        b.canonical_uuid(value); require(value not in PROTECTED_IDS, "Protected unknown operation/run forbidden")
    require(len(set(ids)) == len(ids), "Distinct producer/controller/Native/run identifiers")
    for key in root:
        if key.endswith("Sha256"): sha(root[key])
    require(type(root["sourcePayloadBytes"]) is int and 0 < root["sourcePayloadBytes"] <= PAYLOAD_CAP, "Bounded exact source length")
    require(root["sourceProducerProofSha256"] != ZERO and root["sourcePayloadSha256"] != ZERO, "Actual producer/payload pins required")
    require(type(root["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", root["head"]), "Exact Root HEAD")
    for key in ("uid", "testUid", "docsUid"):
        require(type(root[key]) is int and 0 < root[key] < 100000, "Exact user0 UID")
    require(root["uid"] >= 10000 and root["testUid"] >= 10000 and len({root[k] for k in ("uid", "testUid", "docsUid")}) == 3,
        "Distinct App/Test/Docs UIDs")
    require(root["docsPackage"] == "com.google.android.documentsui" and type(args.serial) is str and re.fullmatch(r"emulator-[0-9]{4,5}", args.serial),
        "Only separately admitted owned API33 emulator")
    require(digest(args.serial.encode()) == root["serialSha256"] and Path(args.adb).is_absolute() and
        digest(Path(args.adb).read_bytes()) == root["adbSha256"], "Exact serial/ADB pin")
    repo = Path(args.repo).resolve()
    require(Path(__file__).resolve() == repo / TOOL and digest(Path(__file__).read_bytes()) == root["hostToolSha256"], "Exact in-repo IMPORT entry")
    for filename, key in (("android-sdk-capture.py", "captureSelfSha256"), ("android-nonempty-device-first.py", "sourceApiSha256"), ("android-nonempty-device-pair.py", "readHelperSha256")):
        require(digest(Path(__file__).with_name(filename).read_bytes()) == root[key], "Exact source helper pin before import")
    b.lease_now(root)
    runtime_raw = read_private(args.runtime_admission, CAP)
    require(digest(runtime_raw) == root["runtimeAdmissionSha256"], "Exact runtime admission pin")
    runtime = exact_json(runtime_raw)
    require(set(runtime) == b.RUNTIME_KEYS and runtime["schema"] == "p7-framework-api33-runtime-admission-v1" and
        runtime["purpose"] == "p7-main-saf-framework-import-v1" and runtime["api"] == 33 and type(runtime["api"]) is int and runtime["ready"] is True,
        "Closed IMPORT runtime purpose")
    b.canonical_uuid(runtime["ownedRuntimeId"])
    for key in b.RUNTIME_KEYS - {"schema", "purpose", "ownedRuntimeId", "api", "ready"}:
        require(runtime[key] == root[key] and type(runtime[key]) is type(root[key]), "Runtime/Root fingerprint drift")
    return raw, root, runtime_raw, runtime

def source_guard(repo, overlay):
    # SDK integration must define this new closed60/905 guard; never fall back to an old purpose.
    require(hasattr(b._capture, SOURCE_GUARD_FUNCTION), "UNBOUND: independently reviewed IMPORT source guard missing")
    value = getattr(b._capture, SOURCE_GUARD_FUNCTION)(b._first, repo, overlay)
    require(len(value["sourceOverlay"]) == 60 and len(value["inventory"]) == len(value["sources"]) == 905,
        "Exact new IMPORT source inventory60/905")
    return value

def admitted(args, root):
    repo = Path(args.repo).resolve(); bundle = Path(args.build_bundle)
    seal_raw = b._capture.private_bytes(bundle / "capture-seal.json", 512 * 1024)
    review_raw = b._capture.private_bytes(bundle / "workspace-review.json", 131072)
    require(digest(seal_raw) == root["sdkSealSha256"] and digest(review_raw) == root["workspaceReviewSha256"], "Root-pinned SDK/review")
    require(b._capture.parse_json(review_raw)["head"] == root["head"], "SDK reviewed HEAD")
    build = b._capture.verify_build_bundle(bundle, b._first, repo, SDK_PROFILE)
    guard = b._first.read_guard(bundle / "source-before.json")
    require(build["sealSha256"] == root["sdkSealSha256"] and digest(canonical(guard)) == root["sourceGuardSha256"] and
        b._first.same(guard, source_guard(repo, guard["sourceOverlay"])), "Exact reviewed IMPORT source chain")
    require(guard["sources"][TOOL] == root["hostToolSha256"] and guard["sources"][EXPORT_HELPER] == root["exportHelperSha256"] and
        guard["sources"]["scripts/mobile/android-sdk-capture.py"] == root["captureSelfSha256"], "Host/helper/capture source pins")
    require(digest(b._capture.private_bytes(bundle / "adb-version.stdout.bin", CAP)) == root["adbVersionSha256"], "Actual captured ADB version")
    require(Path(args.adb).resolve() == Path(build["tools"]["adb"]["path"]).resolve() and
        build["tools"]["adb"]["sha256"] == root["adbSha256"], "Captured actual ADB")
    require(set(build["artifacts"]) == {"app", "test"}, "IMPORT two-APK purpose only")
    for role, key in (("app", "appApkSha256"), ("test", "testApkSha256")):
        require(build["artifacts"][role]["sha256"] == root[key] and
            build["artifacts"][role]["certificateSha256"] == root["certificateSha256"], "Exact captured APK/certificate")
    b.profile_check(root["profile"], root)
    head = b.owned_call(Path(args.output_dir), "git-head", ["git", "rev-parse", "HEAD"], repo)
    unmerged = b.owned_call(Path(args.output_dir), "git-unmerged", ["git", "diff", "--name-only", "--diff-filter=U"], repo)
    require(head.returncode == unmerged.returncode == 0 and not head.stderr and not unmerged.stderr and
        head.stdout.decode().strip() == root["head"] and not unmerged.stdout, "Current exact HEAD/no conflict")
    return guard, build

def native_command(binding, producer=False):
    selector = PRODUCER_SELECTOR if producer else NATIVE_SELECTOR
    arguments = {"class": selector, "migrationInboxRun": binding["run"],
        "migrationMainSafAdmissionJson": canonical(binding).decode(),
        "migrationMainSafAdmissionSha256": digest(canonical(binding))}
    command = ["am", "instrument", "-w", "-r"]
    for key, value in sorted(arguments.items()): command += ["-e", key, value]
    command.append(b._first.RUNNER)
    return command

def original_wait(raw, intent_raw, stdout, stderr, expected_argv, repo):
    wait, intent = exact_json(raw), exact_json(intent_raw)
    require(canonical(intent) == intent_raw, "Original canonical invocation intent bytes")
    require(set(wait) == {"schema", "operationId", "intentSha256", "status", "actualWaitReturned", "remoteExitFrameObserved",
        "remoteEffectConfirmed", "hostPid", "childPid", "stdout", "stderr", "returnCode", "stdinClosed", "overflow",
        "childStartObservation", "startObservationKind", "allHostPipeFdsClosed"}, "Closed original wait schema")
    require(set(intent) == {"schema", "operationId", "argv", "cwd", "stdoutCap", "stderrCap", "createdWallNs",
        "deadlineMonotonic", "remoteExitFrameObserved"} and intent["schema"] == "p7-framework-original-child-intent-v4" and
        intent["argv"] == expected_argv and intent["cwd"] == str(repo) and intent["remoteExitFrameObserved"] is False and
        intent["stdoutCap"] in (CAP, STDERR_CAP) and intent["stderrCap"] == STDERR_CAP,
        "Exact original producer/read intent")
    b.canonical_uuid(intent["operationId"])
    require(type(intent["createdWallNs"]) is int and intent["createdWallNs"] > 0 and
        type(intent["deadlineMonotonic"]) is float and math.isfinite(intent["deadlineMonotonic"]) and intent["deadlineMonotonic"] > 0 and
        len(stdout) <= intent["stdoutCap"] and len(stderr) <= intent["stderrCap"], "Bounded original invocation/streams")
    require(wait["schema"] == "p7-framework-original-child-wait-v4" and wait["operationId"] == intent["operationId"] and
        wait["intentSha256"] == digest(intent_raw) and wait["status"] == "LOCAL_CLOSED" and wait["actualWaitReturned"] is True and
        type(wait["returnCode"]) is int and wait["returnCode"] == 0 and wait["stdinClosed"] is True and
        wait["overflow"] is False and wait["allHostPipeFdsClosed"] is True and wait["remoteExitFrameObserved"] is False,
        "Original wait/EOF/FD close; no remote-exit inference")
    require(type(wait["childPid"]) is int and wait["childPid"] > 0 and type(wait["hostPid"]) is int and wait["hostPid"] > 0,
        "Original local producer/read identity")
    for label, payload in (("stdout", stdout), ("stderr", stderr)):
        item = wait[label]
        require(set(item) == {"rawFileSha256", "retainedBytes", "observedBytes", "fullStreamSha256", "EOF", "truncated"}, "Closed original stream metadata")
        require(item["EOF"] is True and item["truncated"] is False and type(item["retainedBytes"]) is type(item["observedBytes"]) is int and item["retainedBytes"] == item["observedBytes"] == len(payload) and
            item["rawFileSha256"] == item["fullStreamSha256"] == digest(payload), "Exact original raw wait bytes")
    require(not stderr, "Original producer/read stderr retained as failure, never pass")
    return wait

def parse_producer(stdout, binding):
    text = stdout.decode("utf-8", errors="strict")
    require("OK (1 test)" in text and not any(x in text for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")),
        "One successful separately admitted producer Native selector")
    fields, codes, admissions, producers = {}, [], [], []
    expected_class, expected_method = PRODUCER_SELECTOR.split("#")
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            require(sep and key not in fields, "Unique producer Native field"); fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):]
            special = set(fields) & {"p7MainSafAdmission", "p7MainSafProducer", "p7MainUiCleanup"}
            if special:
                require(len(special) == 1 and set(fields) == special and code == "0" and "p7MainUiCleanup" not in special,
                    "Producer cannot claim Main/consumer completion")
                key = next(iter(special)); value = exact_json(fields[key].encode())
                (admissions if key == "p7MainSafAdmission" else producers).append(value)
            else:
                require(set(fields) <= {"id", "stream", "numtests", "class", "test", "current", "stack"} and
                    fields.get("class") == expected_class and fields.get("test") == expected_method and
                    fields.get("numtests") == fields.get("current") == "1" and "stack" not in fields, "Exact producer case")
                codes.append(code)
            fields = {}
    require(not fields and codes == ["1", "0"] and text.count("INSTRUMENTATION_CODE: -1") == 1 and
        len(admissions) == len(producers) == 1, "One original producer admission/result")
    accepted = admissions[0]
    require(accepted["binding"] == binding and accepted["admissionSha256"] == digest(canonical(binding)), "Producer admission fingerprint")
    require(producers[0]["admission"] == accepted, "Producer result admission differs")
    return accepted, producers[0]

def source_snapshot(args, root):
    directory = Path(args.source_ledger)
    require(directory.is_absolute() and directory.resolve() == directory.absolute() and directory.is_dir(), "Exact source ledger directory")
    names = {p.name for p in directory.iterdir()}
    require(names == SOURCE_FILES | {"source-admission.json"} and not any(p.is_dir() for p in directory.iterdir()), "Closed source ledger inventory")
    admission_raw = read_private(directory / "source-admission.json", CAP)
    require(digest(admission_raw) == root["sourceProducerProofSha256"], "Root-pinned original producer ledger")
    admission = exact_json(admission_raw)
    require(set(admission) == {"schema", "kind", "run", "producerOperationId", "producerSelector", "payloadBytes", "payloadSha256", "materials"} and
        admission["schema"] == "p7-framework-import-source-admission-v1" and admission["kind"] == "completed-native-synthetic-import-v1" and
        admission["run"] == root["run"] and admission["producerOperationId"] == root["producerOperationId"] and
        admission["producerSelector"] == PRODUCER_SELECTOR and type(admission["payloadBytes"]) is int and admission["payloadBytes"] == root["sourcePayloadBytes"] and
        admission["payloadSha256"] == root["sourcePayloadSha256"] and set(admission["materials"]) == SOURCE_FILES,
        "Exact original producer ledger purpose")
    materials = {}
    for name in sorted(SOURCE_FILES):
        pin = admission["materials"][name]
        require(type(pin) is dict and set(pin) == {"bytes", "sha256"} and type(pin["bytes"]) is int and pin["bytes"] >= 0, "Exact producer material pin")
        sha(pin["sha256"])
        cap = PAYLOAD_CAP if name == "payload.bin" else STDERR_CAP if name.endswith(".bin") else CAP
        raw = read_private(directory / name, cap, allow_empty=name.endswith(".bin"))
        require(len(raw) == pin["bytes"] and digest(raw) == pin["sha256"], "Original producer material bytes differ")
        materials[name] = raw
    binding = exact_json(materials["producer-binding.json"])
    legacy_keys = {"schema", "profile", "run", "phase", "operationId", "selector", "uid", "testUid", "docsUid", "bootUuid",
        "appApkSha256", "testApkSha256", "certificateSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256",
        "profileSha256", "expiresWallMillis", "inputBytes", "inputSha256", "transportContractReviewSha256"}
    require(set(binding) == legacy_keys and binding["schema"] == "p7-main-saf-admission-v1" and binding["profile"] == "p7-main-saf-v2" and
        binding["phase"] == "PRODUCER" and binding["selector"] == PRODUCER_SELECTOR and
        binding["run"] == root["run"] and binding["operationId"] == root["producerOperationId"] and
        binding["inputBytes"] is None and binding["inputSha256"] is None and
        type(binding["expiresWallMillis"]) is int and 0 < binding["expiresWallMillis"] < 2**63,
        "Separately admitted real-crypto producer, never fallback")
    for key in legacy_keys - {"schema", "profile", "run", "phase", "operationId", "selector", "expiresWallMillis", "inputBytes", "inputSha256"}:
        require(binding[key] == root[key] and type(binding[key]) is type(root[key]), "Producer SDK/source/target mismatch")
    argv = [str(Path(args.adb).resolve()), "-s", args.serial, "shell", "-T", shlex.join(native_command(binding, producer=True))]
    original_wait(materials["producer-wait.json"], materials["producer-intent.json"], materials["producer.stdout.bin"],
        materials["producer.stderr.bin"], argv, Path(args.repo).resolve())
    accepted, receipt = parse_producer(materials["producer.stdout.bin"], binding)
    require(exact_json(materials["producer-receipt.json"]) == receipt and set(receipt) ==
        {"schema", "run", "admission", "bytes", "sha256", "ciphertextFdClosed", "directorySynced", "sessionOwnersAcquired", "storageOwnersAcquired", "processExitVerified"} and
        receipt["schema"] == "p7-main-saf-producer-v1" and receipt["run"] == root["run"] and
        type(receipt["bytes"]) is int and receipt["bytes"] == root["sourcePayloadBytes"] and receipt["sha256"] == root["sourcePayloadSha256"] and
        receipt["ciphertextFdClosed"] is True and receipt["directorySynced"] is True and
        type(receipt["sessionOwnersAcquired"]) is type(receipt["storageOwnersAcquired"]) is int and receipt["sessionOwnersAcquired"] == receipt["storageOwnersAcquired"] == 0 and receipt["processExitVerified"] is False,
        "Original real-crypto producer receipt; do not rewrite its exit field")
    observed = exact_json(materials["producer-process.json"])
    facts = accepted["nativeFacts"]
    require(set(facts) == {"pid", "uid", "startTicks", "bootUuid", "argvSha256"} and type(facts["pid"]) is int and facts["pid"] > 0 and
        type(facts["startTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", facts["startTicks"]), "Closed original Native producer facts")
    for key in ("argvSha256", "statBeforeSha256", "statAfterSha256", "statusSha256"): sha(observed[key])
    require(set(observed) == {"pid", "uid", "startTicks", "bootUuid", "argvSha256", "statBeforeSha256", "statAfterSha256", "statusSha256"},
        "Independent original App process observation")
    for key in ("pid", "uid", "startTicks", "bootUuid", "argvSha256"):
        require(facts[key] == observed[key] and type(facts[key]) is type(observed[key]), "Producer original Native facts differ")
    require(facts["uid"] == root["uid"] and facts["bootUuid"] == root["bootUuid"], "Original producer target/boot")
    exited = exact_json(materials["producer-exit.json"])
    require(set(exited) == {"process", "kind", "statReadSha256", "directoryReadSha256"} and exited["process"] == facts and
        exited["kind"] in ("pid-start-replaced", "proc-enoent-and-directory-absent"), "Original producer exit independently required")
    sha(exited["statReadSha256"]); sha(exited["directoryReadSha256"])
    source_argv = [str(Path(args.adb).resolve()), "-s", args.serial, "shell", "-T",
        shlex.join(["run-as", APP, "cat", f"files/p7-main-saf-producer-v1/{root['run']}/input.bin"])]
    original_wait(materials["source-read-wait.json"], materials["source-read-intent.json"], materials["payload.bin"],
        materials["source-read.stderr.bin"], source_argv, Path(args.repo).resolve())
    publication = exact_json(materials["local-publication.json"])
    require(canonical(publication) == canonical({"schema": "p7-framework-source-local-publication-v1", "bytes": root["sourcePayloadBytes"],
        "sha256": root["sourcePayloadSha256"], "sourceReadWaitSha256": digest(materials["source-read-wait.json"]),
        "fileSynced": True, "directorySynced": True, "outputFdClosed": True, "remoteSourceFdObserved": False}),
        "Original local publication receipt, not an invented remote FD observation")
    require({p.name for p in directory.iterdir()} == names, "Source ledger inventory changed")
    snapshot = b.SourceSnapshot.freeze(str(directory / "payload.bin"), admission_raw, root["sourcePayloadBytes"],
        root["sourcePayloadSha256"], independently_checked_producer=True)
    # The True above follows the raw wait/result/process/exit/material joins, never a user boolean.
    return snapshot, admission_raw

class ImportDevice:
    """Delegate the closed read API, adding exact OPEN resolver to its CREATE check."""
    def __init__(self, args, root):
        self._base = b.Device(args, root)
    def __getattr__(self, name):
        return getattr(self._base, name)
    @property
    def outer_deadline(self):
        return self._base.outer_deadline
    @outer_deadline.setter
    def outer_deadline(self, value):
        self._base.outer_deadline = value
    def snapshot(self, build):
        result = self._base.snapshot(build)
        rows = self.success(["cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.OPEN_DOCUMENT",
            "-c", "android.intent.category.OPENABLE", "-t", "application/octet-stream"]).decode().splitlines()
        require(rows and re.fullmatch(re.escape(self.root["docsPackage"]) + r"/[A-Za-z0-9_.$]+", rows[-1]), "Exact system OPEN owner changed")
        return result

def content_operation(device, intent, action, prefix):
    require(action in ("prepare-import", "read-original-receipt", "read-payload-ack", "audit"), "Closed IMPORT protocol surface")
    b.lease_now(device.root)
    result = b.owned_call(Path(device.args.output_dir), prefix, intent.shell_argv(device.args.adb, device.args.serial, action),
        Path(device.args.repo).resolve(), seconds=30)
    require(result.returncode == 0 and not result.stderr, "Original content client unknown; never replay")
    return result

def owned_input(device, intent, source):
    """Acquire and send once to the original Popen; no generic argv or stdin CLI."""
    require(intent.value()["kind"] == "IMPORT" and type(source) is b.SourceSnapshot, "Exact admitted IMPORT source")
    output, prefix = Path(device.args.output_dir), "controller-input"
    argv = intent.shell_argv(device.args.adb, device.args.serial, "receive-input")
    deadline = b.finite_deadline(60)
    value = {"schema": "p7-framework-original-child-intent-v4", "operationId": str(uuid.uuid4()), "argv": argv,
        "cwd": str(Path(device.args.repo).resolve()), "stdoutCap": CAP, "stderrCap": STDERR_CAP,
        "createdWallNs": time.time_ns(), "deadlineMonotonic": deadline, "remoteExitFrameObserved": False}
    b.persist_json(output / (prefix + "-intent.json"), value)
    acquisition = {"output": output, "prefix": prefix, "operationId": value["operationId"],
        "intentSha256": digest(canonical(value)), "child": None, "owner": None}
    b._OWNERS[value["operationId"]] = acquisition
    try:
        acquisition["child"] = subprocess.Popen(argv, cwd=str(Path(device.args.repo).resolve()), stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, umask=0o077)
        child = acquisition["child"]
        acquisition["owner"] = b.OriginalChild(child, tuple(argv), "start-observation-pending", source.payload, deadline, stdout_limit=CAP)
        observed = b._capture.owner_start(child)
        acquisition["startObservationKind"] = "measured-live-ps" if observed is not None else "already-exited-before-ps"
        acquisition["owner"].start_observation = observed or "original-Popen-already-exited-before-ps"
        b.persist_json(output / (prefix + "-owner.json"), {"schema": "p7-framework-local-owner-v4",
            "operationId": value["operationId"], "intentSha256": acquisition["intentSha256"], "pid": child.pid,
            "startObservation": observed, "startObservationKind": acquisition["startObservationKind"], "hostPid": os.getpid()})
        acquisition["owner"].drain_once()
        wait = b.owner_snapshot(acquisition, "")
        acquisition["owner"].require_local_closed(argv)
        require(acquisition["owner"].sent_bytes == len(source.payload) and acquisition["owner"].stdout_bytes == 0,
            "Original exact payload stdin; unexpected stdout is failure")
        b._OWNERS.pop(value["operationId"])
        return SimpleNamespace(owner=acquisition["owner"], wait=wait)
    except BaseException as error:
        acquisition["failureClass"] = type(error).__name__
        child, owner = acquisition.get("child"), acquisition.get("owner")
        # End only this local sender's remaining input; never send a suffix/replacement.
        # Closing this original stdin is not a remote cancel or proof of receiver failure.
        if child is not None and child.stdin is not None and not child.stdin.closed:
            child.stdin.close()
            if owner is not None: owner.stdin_closed = True
            b.persist_json(output / (prefix + "-unknown-input-closed.json"), {
                "operationId": value["operationId"], "sentBytes": owner.sent_bytes if owner is not None else None,
                "expectedBytes": len(source.payload), "originalHostStdinClosed": True,
                "remoteEffect": "UNKNOWN", "remoteCancelled": False, "replacementSent": False})
        if not (output / (prefix + "-wait.json")).exists(): b.owner_snapshot(acquisition, "")
        if child is None: b._OWNERS.pop(value["operationId"], None)
        raise b.CaptureUnknown("Original input UNKNOWN; no replay") from error


def native_import(device, args, root, build, intent, prepared_raw, controller, source, ack_raw):
    output = Path(args.output_dir)
    binding = {"schema": "p7-main-saf-admission-v4", "profile": PROFILE, "run": root["run"], "phase": "IMPORT",
        "operationId": root["nativeOperationId"], "selector": NATIVE_SELECTOR,
        **{key: root[key] for key in ("uid", "testUid", "docsUid", "bootUuid", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "expiresWallMillis", "transportContractReviewSha256", "appApkSha256", "testApkSha256", "certificateSha256")},
        "inputBytes": len(source.payload), "inputSha256": digest(source.payload), "controllerOperationId": root["operationId"], "controllerIntentSha256": digest(intent.raw),
        "controllerReceiptSha256": digest(prepared_raw), "controllerPayloadAckSha256": digest(ack_raw), "sourceProducerProofSha256": source.producer_proof_sha256}
    arguments = {"class": NATIVE_SELECTOR, "migrationInboxRun": root["run"], "migrationMainSafAdmissionJson": canonical(binding).decode(),
        "migrationMainSafAdmissionSha256": digest(canonical(binding)), "migrationMainSafOwnedLease": "1", "migrationMainSafRun": root["run"],
        "migrationMainSafProviderPrepared": "1", "migrationMainSafFrameworkV4": "1", "migrationMainSafProfile": canonical(root["profile"]).decode(),
        "migrationMainSafInputBytes": str(len(source.payload)), "migrationMainSafInputSha256": digest(source.payload)}
    b.persist_json(output / "native-admission.json", binding); b.persist_json(output / "native-arguments.json", arguments)
    absent = device.shell(["pidof", APP]); require(absent.returncode == 1 and not absent.stdout and not absent.stderr, "Original App must be absent; no force-stop")
    device.process("documents", absent=True, phase="before-native")
    # Recheck the exact original controller after confirmed preparation, before Native.
    before_controller = device.process("controller", phase="before-native")
    require(before_controller["status"] == "present" and before_controller["process"] == controller, "Original controller replaced before Native; no replay")
    observed, docs_live = {}, {}
    last_tick = [0.0]
    native_deadline = b.finite_deadline(180)
    def tick():
        now = time.monotonic()
        if now - last_tick[0] < 0.2: return
        last_tick[0] = now; device.outer_deadline = native_deadline
        try:
            if not observed:
                live = device.app_live()
                if live is not None:
                    observed.update(live); b.persist_json(output / "native-original-process.json", live)
            if not docs_live:
                probe = device.shell(["pidof", DOCUMENT_PROCESS])
                if probe.returncode == 1 and not probe.stdout and not probe.stderr: return
                require(probe.returncode == 0 and not probe.stderr, "Original document provider live probe failed")
                doc = device.process("documents", witness=False, phase="native-live")
                require(doc["status"] == "present", "Exact new document provider unknown")
                docs_live.update(doc["process"])
        finally:
            device.outer_deadline = None
    command = ["am", "instrument", "-w", "-r"]
    for key, value in sorted(arguments.items()): command += ["-e", key, value]
    command.append(b._first.RUNNER)
    b.lease_now(root)
    result = b.owned_call(output, "native-import", device.prefix + ["shell", "-T", shlex.join(command)], Path(args.repo).resolve(), cap=STDERR_CAP, deadline=native_deadline, tick=tick)
    require(result.returncode == 0 and not result.stderr and observed, "Actual original Native wait/dualEOF/stderr/identity gate")
    text = result.stdout.decode("utf-8", errors="strict")
    require("OK (1 test)" in text and not any(x in text for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")), "One successful original JUnit selector")
    expected_class, expected_method = NATIVE_SELECTOR.split("#")
    codes, admissions, cleanup, fields = [], [], [], {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            require(sep and key not in fields, "Unique original instrumentation field"); fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):]
            special = set(fields) & {"p7MainSafAdmission", "p7MainUiCleanup", "p7MainSafProducer"}
            if special:
                require(len(special) == 1 and set(fields) == special and code == "0" and "p7MainSafProducer" not in special, "Closed IMPORT status only")
                key = next(iter(special)); value = exact_json(fields[key].encode())
                (admissions if key == "p7MainSafAdmission" else cleanup).append(value)
            else:
                require(set(fields) <= {"id", "stream", "numtests", "class", "test", "current", "stack"} and fields.get("class") == expected_class and
                    fields.get("test") == expected_method and fields.get("numtests") == "1" and fields.get("current") == "1" and "stack" not in fields, "Exact original named case")
                codes.append(code)
            fields = {}
    require(not fields and codes == ["1", "0"] and text.count("INSTRUMENTATION_CODE: -1") == 1 and len(admissions) == len(cleanup) == 1, "One actual Native admission/completion")
    accepted = admissions[0]
    require(accepted["binding"] == binding and accepted["admissionSha256"] == digest(canonical(binding)), "Original Native admission differs")
    facts = accepted["nativeFacts"]
    for key in ("pid", "uid", "startTicks", "bootUuid", "argvSha256"):
        require(facts[key] == observed[key] and type(facts[key]) is type(observed[key]), "Native identity differs from independent original process")
    proof = cleanup[0]
    require(proof["case"] == expected_method and all(proof.get(k) is True for k in ("completed", "storageWriterRetired", "privateSessionRetired", "activityClosed", "installationReleased", "postJoinProviderCheckPassed", "safCallbackCompleted")) and
        proof["testFailed"] is False and proof["cleanupFailed"] is False and proof["safAccepted"] is False, "Actual Main close/result/owner gates incomplete")
    saf = proof["saf"]
    require(saf["admission"] == accepted and saf["run"] == root["run"] and saf["kind"] == "IMPORT" and saf["callbackAndGrantObserved"] is True and
        saf["providerAuditVerified"] is False and saf["protocolVersion"] == PROFILE and saf["controllerAuthority"] == CONTROL and saf["authority"] == DOCUMENTS and
        saf["providerRelativeDirectory"] == NAMESPACE + "/" + root["run"] and saf["documentId"] == "p7-input-" + root["run"], "Original Main callback/grant/URI/protocol mismatch")
    require(type(saf["expectedBytes"]) is int and saf["expectedBytes"] == len(source.payload) and saf["expectedSha256"] == digest(source.payload), "Exact original producer/input fingerprint")
    original_exit = device.app_exit(facts)
    b.persist_json(output / "native-original-exit.json", original_exit)
    persisted = exact_json(device.success(["run-as", APP, "cat", f"files/acceptance/P7-inbox-{root['run']}/main-ui-result.json"]))
    require(persisted == proof, "Independently read exact original Main result differs")
    # The outer legacy safProviderDirectory display field is not used as a v4 target.
    b.persist_json(output / "native-proof.json", proof)
    documents = device.process("documents", phase="after-native")
    require(documents["status"] == "present", "Original document process missing/unknown")
    if docs_live:
        require(all(documents["process"][k] == v and type(documents["process"][k]) is type(v) for k, v in docs_live.items()), "Document provider replaced during original Native")
    return proof, original_exit, documents["process"]

def native_role_join(root, proof):
    saf = proof["saf"]; roles = saf["runtimeOwnedRoles"]
    require(set(roles) == {"rootTitle", "documentTitle", "directoryWindowId", "directoryTextSha256", "profileSha256"}, "Closed original runtime roles")
    require(type(roles["directoryWindowId"]) is int and roles["directoryWindowId"] >= 0, "Original directory AX window")
    title = "AH_P7_" + root["run"].replace("-", "")
    filename = "agent-hub-p7-import-" + root["run"] + ".codexmigration"
    require(saf["filename"] == filename and roles["profileSha256"] == root["profileSha256"] and
        roles["directoryTextSha256"] == digest(title.encode()), "Original UUID breadcrumb/profile")
    steps = ["CHOOSE_ROOT", "CHOOSE_DOCUMENT"]
    # Current Native profile leaves OPEN button unknown. Only direct return is admissible;
    # observing a required OPEN button needs a separately reviewed profile/SDK revision.
    require(saf["pickerActionSteps"] in (steps, ["SHOW_ROOTS"] + steps), "One known ordered system action per original role")
    for key, text in (("rootTitle", title), ("documentTitle", filename)):
        row = roles[key]
        require(set(row) == {"state", "resourceId", "resourceIdObserved", "className", "windowId", "observedTextSha256", "finalFreshPathChecked"} and
            row["state"] == "BOUND" and row["resourceIdObserved"] is True and row["className"] == "android.widget.TextView" and
            row["windowId"] == roles["directoryWindowId"] and row["observedTextSha256"] == digest(text.encode()) and row["finalFreshPathChecked"] is True and
            (row["resourceId"] is None or type(row["resourceId"]) is str and
                re.fullmatch(r"(?:" + re.escape(root["docsPackage"]) + r"|android):id/[A-Za-z0-9_]+", row["resourceId"])),
            "Runtime exact UUID role/window/path unknown")
    b.canonical_uuid(saf["pickerId"])

def run_import(args):
    os.umask(0o077)
    raw, root, runtime_raw, runtime = root_approval(args)
    b._first = b.module("p7_framework_import_source", "android-nonempty-device-first.py")
    b._capture = b.module("p7_framework_import_capture", "android-sdk-capture.py")
    b._pair = b.module("p7_framework_import_proc_reads", "android-nonempty-device-pair.py")
    output = b._capture.private_directory(args.output_dir, empty=True)
    require(output.resolve() == Path(args.output_dir).absolute(), "Exact private output directory")
    b.persist(output / "root-approval.json", raw); b.persist(output / "runtime-admission.json", runtime_raw)
    guard, build = admitted(args, root)
    source, source_admission_raw = source_snapshot(args, root)
    b.persist(output / "source-admission.json", source_admission_raw)
    b.persist_json(output / "source-freeze.json", {"schema": "p7-framework-import-source-freeze-v1",
        "producerProofSha256": source.producer_proof_sha256, "bytes": len(source.payload), "sha256": digest(source.payload),
        "localOriginalFdClosed": True, "remoteSourceFdObserved": False})
    b.persist_json(output / "source-guard.json", guard)
    device = ImportDevice(args, root); transport = device.snapshot(build)
    transport["sdkProfile"] = SDK_PROFILE; transport["platformContainer"] = device.platform_container()
    b.persist_json(output / "transport.json", transport)
    device.process("controller", absent=True, phase="before-prepare")
    device.process("documents", absent=True, phase="before-prepare")
    absent = device.shell(["run-as", TEST, "ls", "-d", f"{NAMESPACE}/{root['run']}"])
    absent_raw = absent.stdout + absent.stderr
    require(absent.returncode != 0 and len(absent_raw) <= 1024 and b"No such file or directory" in absent_raw and
        b"Permission denied" not in absent_raw and f"{NAMESPACE}/{root['run']}".encode() in absent_raw,
        "New exact import run absent; retain all prior scopes")
    app_absent = device.shell(["pidof", APP])
    require(app_absent.returncode == 1 and not app_absent.stdout and not app_absent.stderr, "Original producer exited/App absent; no stop")
    value = {"schema": "p7-main-saf-controller-intent-v4", "profile": PROFILE, "run": root["run"],
        "operationId": root["operationId"], "kind": "IMPORT", "appUid": root["uid"], "testUid": root["testUid"],
        "docsUid": root["docsUid"], "docsPackage": root["docsPackage"], "bootUuid": root["bootUuid"],
        **{k: root[k] for k in ("appApkSha256", "testApkSha256", "certificateSha256", "sdkSealSha256",
            "workspaceReviewSha256", "sourceGuardSha256", "profileSha256", "transportContractReviewSha256",
            "serialSha256", "adbSha256", "expiresWallMillis")},
        "rootApprovalSha256": digest(raw), "expectedPayloadBytes": len(source.payload), "expectedPayloadSha256": digest(source.payload),
        "sourceProducerProofSha256": source.producer_proof_sha256}
    intent = b.Intent.admit_metadata(value, source)
    b.persist(output / "controller-intent.json", intent.raw)
    b.persist_json(output / "prepare-once.claim", {"run": root["run"], "operationId": root["operationId"], "intentSha256": digest(intent.raw)})
    preparation = content_operation(device, intent, "prepare-import", "controller-prepare")
    prepared = content_operation(device, intent, "read-original-receipt", "controller-prepared-read")
    controller_status = device.process("controller", phase="after-prepare")
    require(controller_status["status"] == "present", "Original controller unknown; no input")
    controller = controller_status["process"]
    b.receipt(intent, prepared.stdout, controller)
    b.persist_json(output / "prepare-effect-confirmed.json", {"schema": "p7-framework-prepared-effect-v4", "run": root["run"],
        "operationId": root["operationId"], "intentSha256": digest(intent.raw), "receiptSha256": digest(prepared.stdout),
        "controllerProcess": controller, "remoteEffectConfirmed": True, "remoteExitFrameObserved": False,
        "nonceIndependentRuntimeMeasurement": False})
    require(b._first.same(guard, source_guard(Path(args.repo).resolve(), guard["sourceOverlay"])), "Source changed after prepare")
    device.snapshot(build); b.lease_now(root)
    b.persist_json(output / "input-once.claim", {"run": root["run"], "operationId": root["operationId"],
        "sourceProducerProofSha256": source.producer_proof_sha256, "bytes": len(source.payload), "sha256": digest(source.payload)})
    written = owned_input(device, intent, source)
    def read_ack(command, deadline):
        require(tuple(command) == tuple(intent.shell_argv(args.adb, args.serial, "read-payload-ack")), "Only original exact ACK observation")
        read_ack.counter += 1
        return b.owned_call(output, "input-ack-%02d" % read_ack.counter, list(command), Path(args.repo).resolve(), deadline=deadline).owner
    read_ack.counter = 0
    ack, ack_owners = b.await_original_payload_ack(intent, args.adb, args.serial, read_ack,
        controller, controller, b.finite_deadline(15))
    ack_raw = bytes(ack_owners[-1].stdout)
    b.join_original_import(intent, args.adb, args.serial, preparation.owner, prepared.owner, written.owner,
        ack_owners[-1], controller, controller)
    before_audits = [content_operation(device, intent, "audit", "staging-audit-%d" % i).stdout for i in (1, 2)]
    b.stable_audits(intent, before_audits[0], before_audits[1], controller, controller)
    b.persist_json(output / "native-once.claim", {"run": root["run"], "operationId": root["nativeOperationId"], "selector": NATIVE_SELECTOR})
    proof, original_exit, document_owner = native_import(device, args, root, build, intent, prepared.stdout, controller, source, ack_raw)
    native_role_join(root, proof)
    audits = [content_operation(device, intent, "audit", "consumer-audit-%d" % i).stdout for i in (1, 2)]
    joined = b.stable_audits(intent, audits[0], audits[1], controller, controller, consumer_closed=True,
        document_provider_observed=document_owner)
    final_controller = device.process("controller", phase="final-audit")
    final_documents = device.process("documents", phase="final-audit")
    require(final_controller["status"] == final_documents["status"] == "present" and
        final_controller["process"] == controller and final_documents["process"] == document_owner, "Original provider owner replaced")
    require(b._first.same(guard, source_guard(Path(args.repo).resolve(), guard["sourceOverlay"])), "Final source drift")
    result = {"schema": "p7-framework-import-auxiliary-proof-v1", "status": "passed-auxiliary-API33-import-preview",
        "run": root["run"], "operationId": root["operationId"], "nativeOperationId": root["nativeOperationId"],
        "producerOperationId": root["producerOperationId"], "sourceProducerProofSha256": source.producer_proof_sha256,
        "rootApprovalSha256": digest(raw), "runtimeAdmissionSha256": digest(runtime_raw), "sdkSealSha256": root["sdkSealSha256"],
        "sourceGuardSha256": root["sourceGuardSha256"], "nativeResultSha256": digest(canonical(proof)),
        "payloadAckSha256": digest(ack_raw), "stableAudits": joined, "originalNativeExit": original_exit,
        "remoteEffectConfirmed": True, "remoteExitFrameObserved": False, "nonceIndependentRuntimeMeasurement": False,
        "previewOnly": True, "activationAccepted": False, "fullP7Accepted": False, "physicalDeviceAccepted": False,
        "shippingTransportAccepted": False, "scopesAndKeysRetained": True}
    b.persist_json(output / "framework-import-proof.json", result)
    print(json.dumps({"schema": result["schema"], "status": result["status"], "run": root["run"], "fullP7Accepted": False}))
    return result

def main():
    parser = argparse.ArgumentParser(description="One new separately admitted IMPORT preview; no producer dispatch/fallback/replay")
    for name in ("repo", "output-dir", "adb", "serial", "build-bundle", "root-approval", "root-approval-sha256",
        "runtime-admission", "source-ledger"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        run_import(args)
    except BaseException as error:
        print(json.dumps({"schema": "p7-framework-import-failure-v1", "status": "UNKNOWN",
            "failureClass": type(error).__name__, "remoteExitFrameObserved": False, "operationReplayed": False}), flush=True)
        if b is not None: b.retain_unknown_owners()
        raise SystemExit(50)

if __name__ == "__main__":
    main()
