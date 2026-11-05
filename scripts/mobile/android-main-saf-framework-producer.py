#!/usr/bin/env python3
"""One separately Root-admitted Native ciphertext producer; never dispatch IMPORT.

The legacy and APP-files-v2 producers have separate finite Root/runtime/SDK
purposes and Native bindings, without migrationMainSafFrameworkV4. Framework-v4
continues rejecting PRODUCER. Only
completed original Native output/owner exit/read waits can publish the closed
14-file source ledger consumed by android-main-saf-framework-import.py. Root must
review that ledger and separately admit IMPORT. No install, clear, fallback,
controller preparation, old-scope adoption, retry, or automatic second phase.
"""
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
import uuid

sys.dont_write_bytecode = True
TOOL = "scripts/mobile/android-main-saf-framework-producer.py"
IMPORT_TOOL = "scripts/mobile/android-main-saf-framework-import.py"
EXPORT_TOOL = "scripts/mobile/android-main-saf-framework.py"
SDK_PROFILE = "p7-main-saf-explicit-fd-v1"
PURPOSE = "p7-main-saf-independent-producer-v1"
ROOT_SCHEMA = "p7-independent-producer-root-approval-v1"
APP_FILES_ROOT_SCHEMA = "p7-independent-producer-app-files-root-approval-v2"
APP_FILES_PURPOSE = "p7-main-saf-independent-producer-app-files-v2"
NATIVE_PROFILE = "p7-main-saf-v2"
NATIVE_SELECTOR = "com.agenthub.android.NativeMigrationMainSafProducerTest#produceOnlySyntheticImportCiphertext"
APP = "com.easyai.agenthub.android.acceptance"
TEST = APP + ".test"
CAP, PAYLOAD_CAP, STDERR_CAP = 16384, 2 * 1024 * 1024, 2 * 1024 * 1024
ROOT_KEYS = {
    "schema", "kind", "operationId", "producerOperationId", "run", "bootUuid",
    "uid", "testUid", "docsUid", "docsPackage", "serialSha256", "adbSha256", "adbVersionSha256",
    "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256", "profile", "profileSha256",
    "expiresWallMillis", "transportContractReviewSha256", "runtimeAdmissionSha256",
    "captureSelfSha256", "hostToolSha256", "hostWorkspaceStatusSha256", "importHelperSha256", "exportHelperSha256",
    "sourceApiSha256", "readHelperSha256", "head", "appApkSha256", "testApkSha256", "certificateSha256",
}
# This entry never reuses the completed Source87 run or either original operation.
SOURCE87_IDS = frozenset((
    "2b400f42-3e37-49fb-90eb-469846cd4d41",
    "9afd6f96-15ae-4f0a-801c-53b62ec848ae",
    "39fe4fc4-582a-47f8-9142-3ac3379fdeed",
))
b = None
i = None


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


def private_bytes(path, maximum, *, allow_empty=False):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path.absolute(), "Canonical input")
    parent = path.parent.lstat()
    require(stat.S_ISDIR(parent.st_mode) and parent.st_uid == os.getuid() and
        stat.S_IMODE(parent.st_mode) == 0o700, "Private input parent")
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and before.st_nlink == 1 and
        stat.S_IMODE(before.st_mode) == 0o600 and
        (0 <= before.st_size <= maximum if allow_empty else 0 < before.st_size <= maximum), "Private bounded input")
    identity = lambda s: (s.st_dev, s.st_ino, s.st_uid, s.st_mode, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        require(identity(os.fstat(fd)) == identity(before), "Input open raced")
        raw = bytearray()
        while True:
            part = os.read(fd, min(65536, maximum + 1 - len(raw)))
            if not part:
                break
            raw.extend(part)
            require(len(raw) <= maximum, "Input cap")
        require(len(raw) == before.st_size and identity(os.fstat(fd)) == identity(before) == identity(path.lstat()),
            "Input changed")
        after_parent = path.parent.lstat()
        require((parent.st_dev, parent.st_ino, parent.st_uid, parent.st_mode) ==
            (after_parent.st_dev, after_parent.st_ino, after_parent.st_uid, after_parent.st_mode), "Input parent changed")
        return bytes(raw)
    finally:
        os.close(fd)


def source_module(repo, relative, pin, name):
    # Only the five fixed local helpers below may be loaded, never a CLI method/path.
    require(relative in (
        IMPORT_TOOL, EXPORT_TOOL, "scripts/mobile/android-nonempty-device-first.py",
        "scripts/mobile/android-sdk-capture.py", "scripts/mobile/android-nonempty-device-pair.py",
    ), "Closed module source")
    path = repo / relative
    before = path.lstat()
    require(path.resolve() == path and stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and
        before.st_size <= 2 * 1024 * 1024 and digest(path.read_bytes()) == pin, "Exact helper source pin")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    after = path.lstat()
    require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
        (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns) and
        digest(path.read_bytes()) == pin, "Helper changed at import")
    return module



def producer_contract(root):
    if root["schema"] == ROOT_SCHEMA:
        return (SDK_PROFILE, "p7_explicit_fd_source_guard", PURPOSE,
            "p7-main-saf-admission-v1", NATIVE_PROFILE, "files/p7-main-saf-producer-v1")
    require(root["schema"] == APP_FILES_ROOT_SCHEMA, "Closed producer Root purpose")
    return (i.APP_FILES_SDK_PROFILE, i.APP_FILES_GUARD_FUNCTION, APP_FILES_PURPOSE,
        i.APP_FILES_NATIVE_SCHEMA, i.APP_FILES_NATIVE_PROFILE, i.APP_FILES_NAMESPACE)


def producer_source_guard(repo, overlay, root):
    function = producer_contract(root)[1]
    require(hasattr(b._capture, function), "Independently pinned producer source guard required")
    return getattr(b._capture, function)(b._first, repo, overlay)

def root_approval(args):
    global b, i
    raw = private_bytes(Path(args.root_approval), CAP)
    sha(args.root_approval_sha256)
    require(digest(raw) == args.root_approval_sha256, "Externally pinned immutable Root approval")
    root = exact_json(raw)
    require(type(root) is dict and set(root) == ROOT_KEYS and
        root["schema"] in (ROOT_SCHEMA, APP_FILES_ROOT_SCHEMA) and root["kind"] == "PRODUCER", "Exact independent producer Root schema")
    repo = Path(args.repo).resolve()
    require(Path(args.repo).is_absolute() and Path(args.repo) == repo and
        Path(__file__).resolve() == repo / TOOL, "Exact in-repo producer entry")
    for key in root:
        if key.endswith("Sha256"):
            sha(root[key])
    self_stat = Path(__file__).lstat()
    require(stat.S_ISREG(self_stat.st_mode) and self_stat.st_nlink == 1 and
        digest(Path(__file__).read_bytes()) == root["hostToolSha256"], "Root separately pins this Host tool")
    b = source_module(repo, EXPORT_TOOL, root["exportHelperSha256"], "p7_independent_producer_shared")
    i = source_module(repo, IMPORT_TOOL, root["importHelperSha256"], "p7_independent_producer_import_reader")
    i.b = b
    b._first = source_module(repo, "scripts/mobile/android-nonempty-device-first.py", root["sourceApiSha256"], "p7_independent_producer_first")
    b._capture = source_module(repo, "scripts/mobile/android-sdk-capture.py", root["captureSelfSha256"], "p7_independent_producer_capture")
    b._pair = source_module(repo, "scripts/mobile/android-nonempty-device-pair.py", root["readHelperSha256"], "p7_independent_producer_pair")
    require(i.PRODUCER_SELECTOR == NATIVE_SELECTOR and i.SDK_PROFILE == SDK_PROFILE and
        i.APP == b.APP == APP and i.TEST == b.TEST == TEST and
        i.CAP == b.CAP == CAP and i.PAYLOAD_CAP == b.PAYLOAD_CAP == PAYLOAD_CAP and
        i.STDERR_CAP == b.STDERR_CAP == STDERR_CAP, "Existing finite producer/import contracts")
    contract = producer_contract(root)
    protected = b.PROTECTED_IDS | i.PROTECTED_IDS | SOURCE87_IDS
    ids = [root[k] for k in ("run", "operationId", "producerOperationId")]
    for value in ids + [root["bootUuid"]]:
        b.canonical_uuid(value)
        require(value not in protected, "Protected original operation/run")
    require(len(set(ids)) == len(ids), "Distinct Root/producer/run identifiers")
    require(type(root["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", root["head"]), "Pinned HEAD")
    for key in ("uid", "testUid", "docsUid"):
        require(type(root[key]) is int and 0 < root[key] < 100000, "Exact user0 UID")
    require(root["uid"] >= 10000 and root["testUid"] >= 10000 and
        len({root[k] for k in ("uid", "testUid", "docsUid")}) == 3, "Distinct App/Test/system UIDs")
    require(root["docsPackage"] == "com.google.android.documentsui" and
        re.fullmatch(r"emulator-[0-9]{4,5}", args.serial) and Path(args.adb).is_absolute() and
        digest(args.serial.encode()) == root["serialSha256"] and
        digest(Path(args.adb).read_bytes()) == root["adbSha256"], "Closed owned emulator/ADB target")
    b.lease_now(root)
    runtime_raw = private_bytes(Path(args.runtime_admission), CAP)
    require(digest(runtime_raw) == root["runtimeAdmissionSha256"], "Original independent runtime pin")
    runtime = exact_json(runtime_raw)
    require(set(runtime) == b.RUNTIME_KEYS and runtime["schema"] == "p7-framework-api33-runtime-admission-v1" and
        runtime["purpose"] == contract[2] and runtime["api"] == 33 and type(runtime["api"]) is int and
        runtime["ready"] is True, "Independent producer runtime purpose")
    b.canonical_uuid(runtime["ownedRuntimeId"])
    require(runtime["ownedRuntimeId"] not in protected | set(ids), "Fresh independent runtime ID")
    for key in b.RUNTIME_KEYS - {"schema", "purpose", "ownedRuntimeId", "api", "ready"}:
        require(root[key] == runtime[key] and type(root[key]) is type(runtime[key]), "Root/runtime target drift")
    b.profile_check(root["profile"], root)
    return raw, root, runtime_raw


def admitted(args, root):
    repo = Path(args.repo).resolve()
    bundle = Path(args.build_bundle)
    seal_raw = b._capture.private_bytes(bundle / "capture-seal.json", 512 * 1024)
    review_raw = b._capture.private_bytes(bundle / "workspace-review.json", 131072)
    require(digest(seal_raw) == root["sdkSealSha256"] and
        digest(review_raw) == root["workspaceReviewSha256"] and
        b._capture.parse_json(review_raw)["head"] == root["head"], "Exact SDK/review/HEAD, no old-head substitution")
    build = b._capture.verify_build_bundle(bundle, b._first, repo, producer_contract(root)[0])
    guard = b._first.read_guard(bundle / "source-before.json")
    require(build["sealSha256"] == root["sdkSealSha256"] and digest(canonical(guard)) == root["sourceGuardSha256"] and
        b._first.same(guard, producer_source_guard(repo, guard["sourceOverlay"], root)) and
        len(guard["sourceOverlay"]) == 62 and len(guard["sources"]) == len(guard["inventory"]) == 905,
        "Exact separately selected compiled input closure")
    # A Host-only tool is not a Gradle input. Keep the selected complete SDK closure
    # intact; the new runtime tool and both frozen readers have separate Root pins.
    require(TOOL not in guard["sources"] and TOOL not in b._first.EXTRA_INPUTS and
        not any(TOOL.startswith(path + "/") for path in b._first.INPUT_ROOTS), "Host tool must stay outside compiled roots")
    for path, key in (
        (IMPORT_TOOL, "importHelperSha256"), (EXPORT_TOOL, "exportHelperSha256"),
        ("scripts/mobile/android-sdk-capture.py", "captureSelfSha256"),
        ("scripts/mobile/android-nonempty-device-first.py", "sourceApiSha256"),
        ("scripts/mobile/android-nonempty-device-pair.py", "readHelperSha256"),
    ):
        require(guard["sources"][path] == root[key], "Root helper must be the sealed implementation")
    require(digest(b._capture.private_bytes(bundle / "adb-version.stdout.bin", CAP)) == root["adbVersionSha256"] and
        Path(build["tools"]["adb"]["path"]).resolve() == Path(args.adb).resolve() and
        build["tools"]["adb"]["sha256"] == root["adbSha256"], "Exact captured ADB")
    require(set(build["artifacts"]) == {"app", "test"}, "Two-APK producer source purpose")
    for role, key in (("app", "appApkSha256"), ("test", "testApkSha256")):
        require(build["artifacts"][role]["sha256"] == root[key] and
            build["artifacts"][role]["certificateSha256"] == root["certificateSha256"], "Captured APK/certificate")
    output = Path(args.output_dir)
    head = b.owned_call(output, "git-head", ["git", "rev-parse", "HEAD"], repo)
    unmerged = b.owned_call(output, "git-unmerged", ["git", "diff", "--name-only", "--diff-filter=U"], repo)
    status = b.owned_call(output, "git-status", ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], repo)
    require(head.returncode == unmerged.returncode == status.returncode == 0 and not head.stderr and not unmerged.stderr and not status.stderr and
        head.stdout.decode().strip() == root["head"] and not unmerged.stdout and digest(status.stdout) == root["hostWorkspaceStatusSha256"],
        "Actual current HEAD/no conflicts/independently reviewed Host workspace")
    return guard, build


def binding_for(root):
    # The Root schema selects exactly one standalone Native producer binding.
    # Do NOT add migrationMainSafFrameworkV4 or controller/writer authority.
    keys = (
        "run", "uid", "testUid", "docsUid", "bootUuid", "appApkSha256", "testApkSha256",
        "certificateSha256", "sdkSealSha256", "workspaceReviewSha256", "sourceGuardSha256",
        "profileSha256", "expiresWallMillis", "transportContractReviewSha256",
    )
    return {
        "schema": producer_contract(root)[3], "profile": producer_contract(root)[4], "phase": "PRODUCER",
        "operationId": root["producerOperationId"], "selector": NATIVE_SELECTOR,
        **{key: root[key] for key in keys}, "inputBytes": None, "inputSha256": None,
    }


def fresh_guest_scope(device, run):
    # Inspect only the exact new directory name. No namespace/old-run enumeration.
    path = producer_contract(device.root)[5] + "/" + run
    for _ in range(2):
        result = device.shell(["run-as", APP, "ls", "-d", path])
        raw = result.stdout + result.stderr
        require(result.returncode != 0 and path.encode() in raw and len(raw) <= 1024 and
            b"No such file or directory" in raw and b"Permission denied" not in raw, "Exact fresh producer run required")
    absent = device.shell(["pidof", APP])
    require(absent.returncode == 1 and not absent.stdout and not absent.stderr, "Original App absent; no stop/adoption")


def native_producer(args, root, device, binding):
    output = Path(args.output_dir)
    deadline = b.finite_deadline(180)
    observed = {}
    last_tick = [0.0]
    def tick():
        now = time.monotonic()
        if observed or now - last_tick[0] < 0.2:
            return
        last_tick[0] = now
        device.outer_deadline = deadline
        try:
            live = device.app_live()
            if live is not None:
                observed.update(live)
                b.persist_json(output / "producer-process.json", live)
        finally:
            device.outer_deadline = None
    command = i.native_command(binding, producer=True)
    require(command[:4] == ["am", "instrument", "-w", "-r"] and
        "migrationMainSafFrameworkV4" not in command, "Only standalone original producer selector")
    b.lease_now(root)
    b.persist_json(output / "producer-once.claim", {
        "run": root["run"], "producerOperationId": root["producerOperationId"],
        "selector": NATIVE_SELECTOR, "bindingSha256": digest(canonical(binding)),
    })
    result = b.owned_call(output, "producer", device.prefix + ["shell", "-T", shlex.join(command)],
        Path(args.repo).resolve(), cap=STDERR_CAP, deadline=deadline, tick=tick)
    require(result.returncode == 0 and not result.stderr and observed, "Original producer actual wait/EOF/live identity")
    # Reuse exactly the consumer's strict parser and original wait checks.
    i.original_wait(private_bytes(output / "producer-wait.json", CAP),
        private_bytes(output / "producer-intent.json", CAP), result.stdout, result.stderr,
        device.prefix + ["shell", "-T", shlex.join(command)], Path(args.repo).resolve())
    accepted, receipt = i.parse_producer(result.stdout, binding)
    facts = accepted["nativeFacts"]
    require(set(facts) == {"pid", "uid", "startTicks", "bootUuid", "argvSha256"}, "Exact original producer facts")
    for key in facts:
        require(facts[key] == observed[key] and type(facts[key]) is type(observed[key]), "Independent original producer PID differs")
    require(set(receipt) == {
        "schema", "run", "admission", "bytes", "sha256", "ciphertextFdClosed", "directorySynced",
        "sessionOwnersAcquired", "storageOwnersAcquired", "processExitVerified",
    } and receipt["schema"] == "p7-main-saf-producer-v1" and receipt["run"] == root["run"] and
        type(receipt["bytes"]) is int and 0 < receipt["bytes"] <= PAYLOAD_CAP and
        receipt["ciphertextFdClosed"] is True and receipt["directorySynced"] is True and
        type(receipt["sessionOwnersAcquired"]) is type(receipt["storageOwnersAcquired"]) is int and
        receipt["sessionOwnersAcquired"] == receipt["storageOwnersAcquired"] == 0 and
        receipt["processExitVerified"] is False, "Actual producer receipt, never rewrite exit claims")
    sha(receipt["sha256"])
    b.persist_json(output / "producer-receipt.json", receipt)
    # Instrumentation completing does not itself prove the original App process exited.
    # At most five seconds of exact-process reads; never stop/restart/launch another case.
    exit_deadline = b.finite_deadline(5)
    previous_outer_deadline = device.outer_deadline
    bounded_exit_deadline = exit_deadline if previous_outer_deadline is None else min(previous_outer_deadline, exit_deadline)
    device.outer_deadline = bounded_exit_deadline
    try:
        while True:
            require(time.monotonic() < bounded_exit_deadline, "Producer exit observation deadline elapsed; retain/no replay")
            live = device.app_live()
            require(time.monotonic() < bounded_exit_deadline, "Producer exit observation exceeded deadline; retain/no replay")
            if live is None or any(live[key] != observed[key] for key in ("pid", "startTicks")):
                break
            time.sleep(min(0.2, max(0.0, bounded_exit_deadline - time.monotonic())))
        native_facts = {key: facts[key] for key in ("pid", "uid", "startTicks", "bootUuid", "argvSha256")}
        require(time.monotonic() < bounded_exit_deadline, "Producer final exit deadline elapsed; retain/no replay")
        exited = device.app_exit(native_facts)
        require(time.monotonic() < bounded_exit_deadline, "Producer final exit exceeded deadline; retain/no replay")
    finally:
        device.outer_deadline = previous_outer_deadline
    b.persist_json(output / "producer-exit.json", exited)
    return accepted, receipt


def source_file_stat(device, run, expected_bytes):
    path = producer_contract(device.root)[5] + "/" + run + "/input.bin"
    raw = device.success(["run-as", APP, "stat", "-c", "%u:%g:%a:%F:%h:%s:%d:%i:%Y", path])
    match = re.fullmatch(rb"([0-9]{1,10}):([0-9]{1,10}):600:regular file:1:([0-9]{1,10}):([0-9]{1,20}):([1-9][0-9]{0,19}):([0-9]{1,20})\n", raw)
    require(match is not None and int(match[1]) == int(match[2]) == device.root["uid"] and
        int(match[3]) == expected_bytes, "Exact producer-owned bounded ciphertext inode")
    return raw


def publish_source(args, root, device, binding, accepted, receipt):
    output = Path(args.output_dir)
    prefix = producer_contract(root)[5] + "/" + root["run"] + "/"
    # Join persisted original producer metadata to the actual Native status, twice.
    recorded = []
    for index in (1, 2):
        original_intent = device.success(["run-as", APP, "cat", prefix + "intent.json"])
        original_receipt = device.success(["run-as", APP, "cat", prefix + "producer.json"])
        require(exact_json(original_intent) == accepted and exact_json(original_receipt) == receipt,
            "Persisted original producer metadata differs")
        recorded.append({"intentSha256": digest(original_intent), "receiptSha256": digest(original_receipt)})
    require(recorded[0] == recorded[1], "Original producer metadata unstable")
    b.persist_json(output / "producer-metadata-stable.json", {
        "schema": "p7-independent-producer-metadata-observation-v1",
        "run": root["run"], "twoMatchingReads": True, "raw": recorded,
        "ordinarySessionMetadataSnapshotClaimed": False,
    })
    before = source_file_stat(device, root["run"], receipt["bytes"])
    source_argv = device.prefix + ["shell", "-T", shlex.join(["run-as", APP, "cat", prefix + "input.bin"])]
    result = b.owned_call(output, "source-read", source_argv, Path(args.repo).resolve(), cap=PAYLOAD_CAP, seconds=30)
    require(result.returncode == 0 and not result.stderr and len(result.stdout) == receipt["bytes"] and
        digest(result.stdout) == receipt["sha256"], "Original complete ciphertext read differs")
    i.original_wait(private_bytes(output / "source-read-wait.json", CAP),
        private_bytes(output / "source-read-intent.json", CAP), result.stdout, result.stderr,
        source_argv, Path(args.repo).resolve())
    after = source_file_stat(device, root["run"], receipt["bytes"])
    require(before == after and device.boot() == root["bootUuid"], "Original source changed during read")
    b.persist_json(output / "source-inode-stable.json", {
        "schema": "p7-independent-producer-source-inode-v1", "run": root["run"],
        "statBeforeSha256": digest(before), "statAfterSha256": digest(after), "sameObservedFields": True,
        "readBytes": len(result.stdout), "readSha256": digest(result.stdout),
        "remoteSourceFdObserved": False,
    })
    ledger = output / "source-ledger"
    ledger.mkdir(mode=0o700)  # new only; a partial ledger is never an admitted source
    selected = {
        "producer-binding.json": canonical(binding),
        "producer-receipt.json": canonical(receipt),
        "producer-process.json": private_bytes(output / "producer-process.json", CAP),
        "producer-exit.json": private_bytes(output / "producer-exit.json", CAP),
        "producer-intent.json": private_bytes(output / "producer-intent.json", CAP),
        "producer-wait.json": private_bytes(output / "producer-wait.json", CAP),
        "producer.stdout.bin": private_bytes(output / "producer.stdout.bin", STDERR_CAP, allow_empty=True),
        "producer.stderr.bin": private_bytes(output / "producer.stderr.bin", STDERR_CAP, allow_empty=True),
        "source-read-intent.json": private_bytes(output / "source-read-intent.json", CAP),
        "source-read-wait.json": private_bytes(output / "source-read-wait.json", CAP),
        "payload.bin": result.stdout,
        "source-read.stderr.bin": result.stderr,
    }
    # b.persist closes each exact new file and syncs it. Sync the ledger explicitly
    # before publishing its final admission, without changing any original wait.
    for name, raw in selected.items():
        b.persist(ledger / name, raw)
    sync_directory(ledger)
    publication = {
        "schema": "p7-framework-source-local-publication-v1",
        "bytes": len(result.stdout), "sha256": digest(result.stdout),
        "sourceReadWaitSha256": digest(selected["source-read-wait.json"]),
        "fileSynced": True, "directorySynced": True, "outputFdClosed": True, "remoteSourceFdObserved": False,
    }
    b.persist_json(ledger / "local-publication.json", publication)
    sync_directory(ledger)
    require(set(selected) | {"local-publication.json"} == set(i.SOURCE_FILES), "Exact thirteen original source materials")
    materials = {
        name: {"bytes": (ledger / name).stat().st_size, "sha256": digest((ledger / name).read_bytes())}
        for name in sorted(i.SOURCE_FILES)
    }
    admission = {
        "schema": "p7-framework-import-source-admission-v1", "kind": "completed-native-synthetic-import-v1",
        "run": root["run"], "producerOperationId": root["producerOperationId"], "producerSelector": NATIVE_SELECTOR,
        "payloadBytes": len(result.stdout), "payloadSha256": digest(result.stdout), "materials": materials,
    }
    b.persist_json(ledger / "source-admission.json", admission)
    sync_directory(ledger)
    # Exactly the existing IMPORT reader, using a local verification view only.
    # This view is NOT an approval: keep kind=PRODUCER and never persist/submit it.
    # Only the reader's finite schema discriminator changes. Original Root bytes,
    # operation IDs, runtime purpose and all source/target pins stay immutable.
    validation_root = dict(root)
    validation_root["schema"] = {
        ROOT_SCHEMA: "p7-framework-import-explicit-fd-root-approval-v1",
        APP_FILES_ROOT_SCHEMA: "p7-framework-import-app-files-root-approval-v2",
    }[root["schema"]]
    consumer_contract = i.import_contract(validation_root)
    original_contract = producer_contract(root)
    require(validation_root["kind"] == "PRODUCER" and consumer_contract[:2] == original_contract[:2] and
        consumer_contract[3:] == original_contract[3:], "Same finite local reader SDK/native/source contract")
    validation_root.update(sourceProducerProofSha256=digest(canonical(admission)),
        sourcePayloadBytes=len(result.stdout), sourcePayloadSha256=digest(result.stdout))
    source_args = argparse.Namespace(source_ledger=str(ledger), adb=args.adb, serial=args.serial, repo=args.repo)
    snapshot, raw = i.source_snapshot(source_args, validation_root)
    require(snapshot.payload == result.stdout and raw == canonical(admission), "Consumer source reader did not accept exact published ledger")
    return admission


def sync_directory(path):
    # Explicit owner of the exact raw directory FD; no borrowing stream or retry.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def run_producer(args):
    os.umask(0o077)
    raw, root, runtime_raw = root_approval(args)
    output = b._capture.private_directory(args.output_dir, empty=True)
    require(output.resolve() == Path(args.output_dir).absolute(), "Precreate exact empty private output")
    b.persist(output / "root-approval.json", raw)
    b.persist(output / "runtime-admission.json", runtime_raw)
    guard, build = admitted(args, root)
    b.persist_json(output / "source-guard.json", guard)
    device = b.Device(args, root)
    transport = device.snapshot(build)
    transport["sdkProfile"] = producer_contract(root)[0]
    b.persist_json(output / "transport.json", transport)
    # The later IMPORT requires fresh original providers. Refuse before producer
    # if another framework run still owns these processes; never kill/adopt it.
    device.process("controller", absent=True, phase="producer-preflight")
    device.process("documents", absent=True, phase="producer-preflight")
    fresh_guest_scope(device, root["run"])
    binding = binding_for(root)
    b.persist_json(output / "producer-binding.json", binding)
    accepted, receipt = native_producer(args, root, device, binding)
    require(b._first.same(guard, producer_source_guard(Path(args.repo), guard["sourceOverlay"], root)),
        "Source changed after original producer; retain/no replay")
    require(digest(Path(__file__).read_bytes()) == root["hostToolSha256"], "Host tool changed during original producer")
    status_after = b.owned_call(output, "git-status-after", ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], Path(args.repo))
    head_after = b.owned_call(output, "git-head-after", ["git", "rev-parse", "HEAD"], Path(args.repo))
    require(status_after.returncode == head_after.returncode == 0 and not status_after.stderr and not head_after.stderr and
        digest(status_after.stdout) == root["hostWorkspaceStatusSha256"] and head_after.stdout.decode().strip() == root["head"], "Host workspace changed during original producer")
    admission = publish_source(args, root, device, binding, accepted, receipt)
    b.persist_json(output / "producer-host-proof.json", {
        "schema": "p7-independent-producer-host-proof-v1", "run": root["run"],
        "producerOperationId": root["producerOperationId"], "status": "completed-source-ledger-awaiting-separate-Root-review",
        "sourceLedgerSha256": digest(canonical(admission)), "payloadBytes": admission["payloadBytes"],
        "payloadSha256": admission["payloadSha256"], "originalProducerExitIndependentlyJoined": True,
        "importDispatched": False, "controllerPrepared": False, "fullP7Accepted": False,
        "remoteExitFrameObserved": False, "originalReceiptExitFieldRewritten": False,
    })
    print(json.dumps({"status": "completed-source-ledger-awaiting-separate-Root-review", "run": root["run"],
        "sourceLedgerSha256": digest(canonical(admission)), "importDispatched": False, "fullP7Accepted": False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "output-dir", "adb", "serial", "build-bundle", "root-approval",
                 "root-approval-sha256", "runtime-admission"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        run_producer(args)
    except BaseException as error:
        print(json.dumps({"schema": "p7-independent-producer-failure-v1", "status": "UNKNOWN",
            "failureClass": type(error).__name__, "operationReplayed": False, "importDispatched": False,
            "retainedOriginalClients": len(b._OWNERS) if b is not None else 0}), flush=True)
        if b is not None:
            b.retain_unknown_owners()
        raise SystemExit(50)


if __name__ == "__main__":
    main()
