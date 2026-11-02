#!/usr/bin/env python3
"""Closed first-case admission/verification only. Does not start adb, activate, retry or admit cold."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import uuid

SCHEMA = "p7-nonempty-device-session-v1"
GUARD_SCHEMA = "p7-nonempty-device-source-guard-v1"
ADMISSION_SCHEMA = "p7-nonempty-device-first-admission-v1"
PACKAGE = "com.easyai.agenthub.android.acceptance"
METHOD = "nonemptyDeviceProducersRestoreActualSession"
SELECTOR = "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + METHOD
CASE = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt"
WRAPPER = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt"
TOOL = "scripts/mobile/android-nonempty-device-first.py"
PRODUCTION = "apps/android/src/main/kotlin/com/agenthub/android/"
TEST = "apps/android/src/androidTest/kotlin/com/agenthub/android/"
# Pinned source bytes, not a claim that the current branch still has this HEAD.
BASELINE_COMMIT = "7685fff35cf74f5bbe0cef11edeeaac5599e41aa"
BASELINE = {
    PRODUCTION + "ThreadReading.kt": "08269bf48dea76f82d209ff251aa1ea3f1304893ed350c881ee94babfe99f280",
    PRODUCTION + "MobileSession.kt": "82a70e353839fe75836c02edb68d5d13b52e70894c1ddfb18917b3943bd30862",
    PRODUCTION + "NativeSessionStorage.kt": "7248a18e7999cddc03c64ab418ce8e9636028a6dfe7d5da75d36d4bdc8d96a0f",
    PRODUCTION + "NativeMigrationLifecycle.kt": "4a37504cca06a536376e9b52aa829e02834537630d676b03beac886100005b24",
    PRODUCTION + "NativeMigrationExport.kt": "277480a5ceb7440957f61447d11d0964a5c7807f5f361c2cffe83f3c29a285bf",
    PRODUCTION + "NativeMigrationActivation.kt": "9f8239e40506ace7686675f5e40aa700ae27c475e991fd326d33b37d0d4c93b1",
    PRODUCTION + "NativeMigrationCandidate.kt": "393c8aa81b1bacdedfb6c886f4f8712a2602b9ee9fa17372ea33a090861c5844",
    PRODUCTION + "NativeMigrationStreamCandidate.kt": "d551830a6b095ff8bf55c345dfd46c7e0cc146f19dccdac74849b464486b9b96",
    PRODUCTION + "NativeMigrationExistingView.kt": "43b58fcc543e76c19aaea6504e077340ce8ba42a63d6a2b1535a41b6f613e528",
    PRODUCTION + "NativeMigrationOriginals.kt": "e51b94da6a9f1361f74ecc6785159140e5805214584a0dea8499a308e938efb8",
    PRODUCTION + "NativeMigrationDeviceState.kt": "b956ba404125d7d021327fbe75c972f17296fbbd2654dbed1c450736cc90cd8e",
    PRODUCTION + "NativeReadingLedger.kt": "1bfc01e3ff33fc21e3ac4a0b590bfd5a007b6390d09b8dc5920918b4b2480e09",
    PRODUCTION + "NativeChatNotificationStore.kt": "992d804fb4d2d2a4f814ee1944c01a38bd27fcc14e02e2a967c8be8e2019838b",
    PRODUCTION + "NativeChatNotificationSoundLibrary.kt": "45c32f934d01137fc7c1b9f50b9cefac2587282e847ac51d61b0a268d18d57b0",
    PRODUCTION + "NativeVoiceRawOwnership.kt": "e3bfe8cb5d1e714e51886fb9f5b86011e5b6561210b0d047e8e309da46fa320e",
    PRODUCTION + "NativeMigrationReadLifetime.kt": "b47f8c1c723e67378203e54ada4302d1a71d889a69c54b14f37c677718b3baaf",
    TEST + "NativeMigrationCandidateTest.kt": "114fa084593fc0f62da6863d1b3788fd8a102ae837848d2a30e154039dee0fdf",
    TEST + "NativeMigrationActivationTest.kt": "b840ac5c48011b4414c483c4ebb76010060959a5276eaa6066b8b4140a9a67a8",
    TEST + "NativeMigrationPostPointerStrictHandoffTest.kt": "d76fba1665925a9241029f8b2eb348ffa5aaa134940fb3a2489ac39b3830e6cc",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file_identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mode, info.st_uid,
            info.st_nlink, info.st_mtime_ns, info.st_ctime_ns)


def digest(path, cap=256 * 1024 * 1024):
    path = Path(path); before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 1 <= before.st_size <= cap, "Unsafe/oversized file")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        opened = os.fstat(descriptor)
        require(file_identity(opened) == file_identity(before), "Hash open changed")
        sha = hashlib.sha256(); total = 0
        while True:
            require(file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash input changed before read")
            chunk = os.read(descriptor, min(65536, cap - total + 1))
            total += len(chunk)
            require(total <= cap, "Hash cumulative byte cap")
            require(file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash input changed during read")
            if not chunk:
                break
            sha.update(chunk)
        require(total == opened.st_size and file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash final descriptor changed")
        calculated = sha.hexdigest()
    finally:
        os.close(descriptor)
    require(file_identity(path.lstat()) == file_identity(before), "Hash final pathname changed")
    return calculated


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    path = Path(path); before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and
            stat.S_IMODE(before.st_mode) == 0o600 and before.st_nlink == 1 and 1 <= before.st_size <= 131072,
            "Private regular proof/admission required")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        opened = os.fstat(source.fileno())
        require(file_identity(opened) == file_identity(before), "JSON open changed")
        raw = source.read(131073)
        require(len(raw) == before.st_size and file_identity(os.fstat(source.fileno())) == file_identity(opened), "JSON changed during read")
    require(file_identity(path.lstat()) == file_identity(before), "JSON pathname changed")
    return json.loads(raw, object_pairs_hook=no_duplicates,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")))


def exact(value, keys):
    require(type(value) is dict and set(value) == set(keys), "Closed object keys")


def integer(value, low, high):
    require(type(value) is int and low <= value <= high, "Integer range")


def sha(value):
    require(type(value) is str and re.fullmatch(r"[a-f0-9]{64}", value), "SHA-256 type")


def canonical_uuid(value):
    require(type(value) is str and str(uuid.UUID(value)) == value, "Canonical UUID")


def digests(value, cap):
    require(type(value) is dict and 1 <= len(value) <= cap, "Digest map size")
    for key, value_sha in value.items():
        require(type(key) is str and 1 <= len(key) <= 512 and not any(ord(c) < 32 for c in key), "Digest name")
        sha(value_sha)


def write_new(path, value):
    path = Path(path); parent = path.parent
    info = parent.lstat()
    require(stat.S_ISDIR(info.st_mode) and parent.resolve() == parent.absolute() and
            info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "Private output directory required")
    raw = encoded(value); require(1 <= len(raw) <= 131072, "Output size")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(raw); output.flush(); os.fsync(output.fileno())


def source_guard(repo):
    repo = Path(repo).resolve()
    actual = {name: digest(repo / name, 1024 * 1024) for name in BASELINE}
    require(actual == BASELINE, "Pinned baseline source changed; review a new contract instead of widening this admission")
    for name in (CASE, WRAPPER, TOOL):
        actual[name] = digest(repo / name, 1024 * 1024)
    return {"schema": GUARD_SCHEMA, "baselineCommit": BASELINE_COMMIT, "sources": actual}


def admission(args):
    canonical_uuid(args.run); canonical_uuid(args.boot_uuid); integer(args.uid, 1, 2147483647)
    guard = source_guard(args.repo)
    value = {"schema": ADMISSION_SCHEMA, "caseSchema": SCHEMA, "run": args.run, "selectedMethod": METHOD,
             "selector": SELECTOR, "mode": "first", "package": PACKAGE, "uid": args.uid, "bootUuid": args.boot_uuid,
             "appApkSha256": digest(args.app_apk), "testApkSha256": digest(args.test_apk),
             "caseSourceSha256": guard["sources"][CASE], "wrapperSourceSha256": guard["sources"][WRAPPER],
             "sourceGuardSha256": hashlib.sha256(encoded(guard)).hexdigest()}
    return guard, value


def binding(value, admission_sha):
    return {key: value[key] for key in ("appApkSha256", "testApkSha256", "caseSourceSha256", "wrapperSourceSha256",
                                      "sourceGuardSha256", "bootUuid", "package", "uid")} | {"sourceAdmissionSha256": admission_sha}


def verify_first(args):
    guard, expected = admission(args)
    require(read_json(args.source_guard) == guard and digest(args.source_guard) == expected["sourceGuardSha256"], "Source guard mismatch")
    require(read_json(args.admission) == expected, "Admission mismatch")
    bound = binding(expected, digest(args.admission))
    intent = read_json(args.first_intent); proof = read_json(args.first_proof)
    shared = {"schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor"}
    exact(intent, shared)
    exact(proof, shared | {"receipt", "generation", "archiveSha256", "sourceCiphertext", "originalCandidateCiphertext",
          "destinationCiphertext", "originalTextSha256", "originalBinarySha256", "draftKey", "acquiredStores", "acquiredSessions",
          "actualSessionJoinReturns", "actualWriterRetireReturns", "actualLeaseReleaseReturns", "unknownAcquisitions", "forbiddenPortAttempts"})
    for key in shared:
        require(proof[key] == intent[key], "Original intent/proof binding changed")
    require(proof["schema"] == SCHEMA and proof["run"] == args.run and proof["binding"] == bound and proof["selectedMethod"] == METHOD, "Wrong target/mode")
    integer(args.observed_pid, 1, 2147483647)
    require(proof["pid"] == args.observed_pid and type(proof["pid"]) is int, "Observed original PID mismatch")
    require(type(proof["startTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", proof["startTicks"]) and
            proof["startTicks"] == args.observed_start_ticks, "Observed original start mismatch")
    anchor = proof["anchor"]
    require(type(anchor) is dict and 12 <= len(anchor) <= 128, "Anchor size")
    fixed = {"package", "uid", "run", "filesRoot", "dataScopeIdentity", "runDirectory", "no_backup", "cache", "files"}
    require(fixed <= set(anchor) and "ancestor:/" in anchor and all(key in fixed or key.startswith(("ancestor:/", "observed-mount:/")) for key in anchor), "Anchor names")
    for key, value in anchor.items():
        require(type(key) is str and len(key) <= 512 and type(value) is str and 1 <= len(value) <= 16384, "Anchor type/cap")
    require(anchor["package"] == PACKAGE and anchor["uid"] == str(args.uid) and anchor["run"] == args.run, "Anchor target")
    require(anchor["filesRoot"].startswith("/data/") and anchor["filesRoot"].endswith("/" + PACKAGE + "/files"), "Application files anchor")
    for key in ("runDirectory", "no_backup", "cache", "files", "dataScopeIdentity"):
        fields = anchor[key].split(":")
        require(len(fields) == 4 and all(re.fullmatch(r"[0-9]{1,24}", field) for field in fields), "Directory identity")
        _, _, owner, mode = map(int, fields)
        require(owner == args.uid and stat.S_ISDIR(mode) and stat.S_IMODE(mode) in
                ({0o700, 0o771} if key == "dataScopeIdentity" else {0o700}), "Directory owner/mode")
    exact(proof["receipt"], {"candidateId", "sha256", "records"})
    canonical_uuid(proof["receipt"]["candidateId"]); canonical_uuid(proof["generation"])
    sha(proof["receipt"]["sha256"]); sha(proof["archiveSha256"])
    for name, cap in (("sourceCiphertext", 64), ("originalCandidateCiphertext", 64), ("destinationCiphertext", 128),
                      ("originalTextSha256", 16), ("originalBinarySha256", 1)):
        digests(proof[name], cap)
    text = proof["originalTextSha256"]
    fixed_text = {"metadata", "codex-private-connections-v1", "mobile-reading-ledger-v1", "native-chat-notifications-v1", "native-chat-notification-sounds-v1"}
    dynamic = [key for key in text if key.startswith(("operations:", "submission:", "draft-file:"))]
    require(fixed_text <= set(text) and len(dynamic) == 3 and all(sum(key.startswith(prefix) for key in dynamic) == 1 for prefix in ("operations:", "submission:", "draft-file:")), "Nonempty record names")
    require(set(text) == fixed_text | set(dynamic) or set(text) == fixed_text | set(dynamic) | {"native-voice-preference-v1"}, "Unexpected record")
    binary = proof["originalBinarySha256"]
    sound = next(iter(binary)); require(sound.startswith("native-chat-notification-sound-v1:") and sound.endswith(".caf"), "Normalized sound name")
    canonical_uuid(sound.removeprefix("native-chat-notification-sound-v1:").removesuffix(".caf"))
    require(type(proof["receipt"]["records"]) is int and proof["receipt"]["records"] == len(text) + len(binary), "Source record count")
    for name, wanted in {"acquiredStores": 27, "acquiredSessions": 4, "actualSessionJoinReturns": 4,
                         "actualWriterRetireReturns": 25, "actualLeaseReleaseReturns": 2, "unknownAcquisitions": 0, "forbiddenPortAttempts": 0}.items():
        require(type(proof[name]) is int and proof[name] == wanted, "Owner/port evidence mismatch")
    require(type(proof["draftKey"]) is str and proof["draftKey"].startswith("codex-machine-v2:") and len(proof["draftKey"]) <= 512, "Independent draft key")
    return {"schema": "p7-nonempty-device-first-host-review-v1", "run": args.run, "selectedMethod": METHOD,
            "firstProofSha256": digest(args.first_proof), "firstIntentSha256": digest(args.first_intent),
            "sourceGuardSha256": expected["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"],
            "nativeAssertionsReportedComplete": True, "originalPidObserved": True, "coldAccepted": False,
            "fdCountClaimed": False, "controllerStrictGraphClaimed": False, "physicalDeviceClaimed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("admit-first", "verify-first"):
        sub = subparsers.add_parser(command)
        for option in ("repo", "app-apk", "test-apk", "run", "boot-uuid", "output-dir"):
            sub.add_argument("--" + option, required=True)
        sub.add_argument("--uid", required=True, type=int)
        if command == "verify-first":
            for option in ("source-guard", "admission", "first-intent", "first-proof", "observed-start-ticks"):
                sub.add_argument("--" + option, required=True)
            sub.add_argument("--observed-pid", required=True, type=int)
    args = parser.parse_args(); output = Path(args.output_dir)
    if args.command == "admit-first":
        guard, value = admission(args)
        write_new(output / "source-guard.json", guard); write_new(output / "source-admission.json", value)
        bound = binding(value, digest(output / "source-admission.json"))
        mapping = {"appApkSha256": "nonemptyAppApkSha256", "testApkSha256": "nonemptyTestApkSha256",
                   "caseSourceSha256": "nonemptyCaseSourceSha256", "wrapperSourceSha256": "nonemptyWrapperSourceSha256",
                   "sourceGuardSha256": "nonemptySourceGuardSha256", "sourceAdmissionSha256": "nonemptySourceAdmissionSha256",
                   "bootUuid": "nonemptyBootUuid", "uid": "nonemptyUid"}
        arguments = {destination: str(bound[source]) for source, destination in mapping.items()}
        arguments |= {"class": SELECTOR, "nonemptyMode": "first", "nonemptyRun": args.run}
        write_new(output / "instrumentation-arguments.json", arguments)
        print(json.dumps({"schema": ADMISSION_SCHEMA, "run": args.run, "selector": SELECTOR,
                          "sourceGuardSha256": value["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"], "coldAccepted": False}))
    else:
        result = verify_first(args); write_new(output / "first-host-review.json", result); print(json.dumps(result))


if __name__ == "__main__":
    main()
