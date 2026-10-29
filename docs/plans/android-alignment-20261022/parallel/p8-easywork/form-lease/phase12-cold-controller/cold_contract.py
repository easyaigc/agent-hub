"""Static inputs and immutable cold proof contract. Importing this module never starts a process."""
import hashlib
import json
import re
import subprocess
import uuid
import zipfile
from pathlib import Path

PACKAGE = "com.easyai.agenthub.android.acceptance"
RUNNER = PACKAGE + ".test"
COMPONENT = RUNNER + "/androidx.test.runner.AndroidJUnitRunner"
CLASS = "com.agenthub.android.NativeEasyWorkCommitColdTest"
WRITER = "writeUnactivatedCandidateAtFinalReadForColdRestart"
READER = "newProcessRestoreKeepsUnreferencedCandidateInactiveWithoutClaimOrSubmit"
COLD_SOURCE = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkCommitColdTest.kt"
COLD_SOURCE_SHA = "40fb86f23f98945fbd539e7a496417f15fcdacf1b677fbe44a4b2cf14ff82f9a"
BASE = "docs/plans/android-alignment-20261022/parallel/p8-easywork/"
MODULES = ["form-lease/phase7/fixture.mjs", "pairing-ui/fixture.mjs", "authorization-fence/phase2/fixture.mjs", "authorization-boundaries/fixture.mjs"]
ASSET = "apps/android/src/androidTest/assets/easywork-crypto-vector.json"
KIND = "p8-synthetic-positive-commit-v1"
HEX = re.compile(r"[a-f0-9]{64}\Z")
SAFE_PATH = re.compile(r"[A-Za-z0-9_.\-/]+\Z")


def need(value, message):
    if not value:
        raise ValueError(message)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def _pairs(items):
    result = {}
    for key, value in items:
        need(key not in result, "Duplicate JSON member")
        result[key] = value
    return result


def loads(raw):
    return json.loads(raw, object_pairs_hook=_pairs)


def canonical(value):
    # All contract member names are ASCII; JSON strings retain slash and Unicode exactly.
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


def android_json(value):
    # AOSP JSONArray/JSONObject.toString preserves parsed insertion order and escapes every slash.
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).replace("/", "\\/").encode()


def integer(value, minimum=0):
    need(type(value) is int and value >= minimum, "Invalid integer")
    return value


def digest(value):
    need(isinstance(value, str) and HEX.fullmatch(value), "Invalid SHA-256")
    return value


def case_id(value):
    need(isinstance(value, str) and str(uuid.UUID(value)) == value and uuid.UUID(value).version == 4, "Fresh UUIDv4 required")
    return value


def exact(value, keys):
    need(isinstance(value, dict) and set(value) == set(keys), "Unexpected contract members")


def safe_relative(value):
    need(isinstance(value, str) and SAFE_PATH.fullmatch(value) and not value.startswith("/") and ".." not in value.split("/"), "Unsafe relative path")
    return value


def contained(root, relative):
    safe_relative(relative)
    root = Path(root).resolve()
    current = root
    for part in relative.split("/"):
        current = current / part
        need(not current.is_symlink(), "Symlink input refused")
    need(current.is_file() and current.resolve().is_relative_to(root), "Missing/outside immutable input")
    return current


def write_once(path, data):
    path = Path(path)
    with path.open("xb") as stream:
        path.chmod(0o600)
        stream.write(data)
        stream.flush()
        import os
        os.fsync(stream.fileno())


def json_once(path, value):
    write_once(path, canonical(value) + b"\n")


def apk_metadata(path, aapt, apksigner):
    def run(tool, args):
        result = subprocess.run([str(tool), *args], capture_output=True, timeout=30)
        need(result.returncode == 0 and len(result.stdout) < 2 * 1024 * 1024, "Offline APK tool refused candidate")
        return result.stdout.decode()
    badging = run(aapt, ["dump", "badging", str(path)])
    match = re.search(r"^package: name='([^']+)'", badging, re.M)
    need(match, "Missing embedded package")
    xml = run(aapt, ["dump", "xmltree", str(path), "AndroidManifest.xml"])
    section = xml.split("E: instrumentation", 1)[1] if "E: instrumentation" in xml else ""
    target = re.search(r'android:targetPackage[^=]*="([^"]+)"', section)
    instrumentation = re.search(r'android:name[^=]*="([^"]+)"', section)
    signs = set(re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})", run(apksigner, ["verify", "--print-certs", str(path)])))
    need(len(signs) == 1, "One verified APK signing identity required")
    return {"package": match[1], "debuggable": "application-debuggable" in badging,
            "targetPackage": target[1] if target else None, "instrumentationName": instrumentation[1] if instrumentation else None, "signerSha256": signs.pop().lower()}


def verify_candidate(candidate_path, frozen, app, runner, aapt, apksigner, metadata=apk_metadata):
    candidate = loads(Path(candidate_path).read_bytes())
    need(len(candidate["files"]) in range(1, 2049), "Candidate source bound exceeded")
    combined = hashlib.sha256()
    for name in sorted(candidate["files"], key=lambda s: s.encode("utf-16-be")):
        path = contained(frozen, name)
        need(path.stat().st_size <= 32 * 1024 * 1024, "Oversized source input")
        raw = path.read_bytes()
        need(sha(raw) == digest(candidate["files"][name]), "Frozen source changed: " + name)
        combined.update(name.encode() + b"\0" + raw + b"\0")
    need(combined.hexdigest() == digest(candidate["sourceSha256"]), "Candidate source graph mismatch")
    need(candidate["files"].get(COLD_SOURCE) == COLD_SOURCE_SHA, "Unreviewed native cold contract")
    expected = {"app": (PACKAGE, app, "appSha256"), "runner": (RUNNER, runner, "testSha256")}
    artifacts = {}
    for role, (package, path, key) in expected.items():
        path = Path(path).resolve()
        need(path.is_file() and file_sha(path) == digest(candidate[key]), "Candidate APK changed")
        facts = metadata(path, aapt, apksigner)
        need(facts["package"] == package and facts["debuggable"], "Wrong embedded acceptance identity")
        need(role != "runner" or facts["targetPackage"] == PACKAGE and facts["instrumentationName"] == "androidx.test.runner.AndroidJUnitRunner", "Runner targets another package/component")
        artifacts[role] = {"path": str(path), "sha256": candidate[key], **facts}
    need(artifacts["app"]["signerSha256"] == artifacts["runner"]["signerSha256"], "App/runner signer mismatch")
    with zipfile.ZipFile(runner) as archive:
        dex = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
        need(len(dex) <= 64 * 1024 * 1024 and all(name.encode() in dex for name in [WRITER, READER]), "Native cold roles missing from runner")
    return {"sourceSha256": candidate["sourceSha256"], "sourceFiles": len(candidate["files"]), "artifacts": artifacts,
            "build": {"sourceSha256": candidate["sourceSha256"], "appSha256": candidate["appSha256"], "runnerSha256": candidate["testSha256"]}}


def fixture_snapshot(root, output, expected_asset):
    graph = {}
    files = {}
    for name in MODULES:
        path = contained(root, BASE + name)
        raw = path.read_bytes()
        graph[name] = sha(raw)
        files[BASE + name] = raw
    raw = contained(root, ASSET).read_bytes()
    need(sha(raw) == expected_asset, "Public vector differs from frozen native asset")
    files[ASSET] = raw
    lock = loads((Path(__file__).parent / "fixture-source-lock.json").read_bytes())
    need(graph == lock["sourceGraph"] and sha(canonical(graph)) == lock["sourceGraphSha256"] and expected_asset == lock["sourceFixtureSha256"], "Unreviewed fixture source graph")
    for name, raw in files.items():
        target = Path(output) / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        write_once(target, raw)
    return {"version": 1, "fixture": KIND, "sourceFixtureSha256": expected_asset,
            "sourceGraph": graph, "sourceGraphSha256": sha(canonical(graph))}


def validate_config(config, contract):
    import urllib.parse
    exact(config, ["version", "fixture", "instanceId", "sidecarPid", "startedAt", "sourceFixtureSha256", "sourceGraph", "sourceGraphSha256", "authOrigin", "controlOrigin"])
    for key in contract:
        need(config[key] == contract[key], "Runtime fixture contract changed: " + key)
    case_id(config["instanceId"])
    integer(config["sidecarPid"], 1)
    ports = []
    for key in ["authOrigin", "controlOrigin"]:
        uri = urllib.parse.urlsplit(config[key])
        need(uri.scheme == "http" and uri.hostname == "127.0.0.1" and uri.port and uri.path == uri.query == uri.fragment == "" and uri.username is None, "Only exact owned loopback origins")
        ports.append(uri.port)
    need(ports[0] != ports[1], "Distinct fixture ports required")
    need(sha(canonical(config["sourceGraph"])) == config["sourceGraphSha256"], "Strict sourceGraph mismatch")
    return ports


COMMON = {"caseId", "namespace", "keyAlias", "pid", "processStartElapsed", "configSha256", "sourceGraphSha256", "build", "syntheticOnly"}
WRITER_FIELDS = COMMON | {"phase", "readyElapsed", "candidateRef", "candidateSha256", "metadataSha256", "identitySha256", "grantRef", "grantSha256", "claimKey", "claimSha256", "journalKey", "journalSha256", "originalTargetKey", "originalDraftSha256", "defaults", "claimRequestsSha256", "namedReadsSha256", "originalOperationUnchanged", "candidateUnreferenced", "savedCallbacks", "mustForceStopWithinSeconds"}
READER_FIELDS = COMMON | {"phase", "writerPid", "writerProofSha256", "candidateStillUnreferenced", "candidateGrantClaimJournalMetadataUnchanged", "defaultEncryptedFilesUnchanged", "newClaimOrProtocolReadOrSubmit", "realProcessReopen"}


def common_proof(proof, plan, config):
    need(proof["caseId"] == plan["caseId"] and proof["namespace"] == proof["keyAlias"] == "p8-positive-commit-" + plan["caseId"], "Foreign case/namespace/key alias")
    need(proof["build"] == plan["candidate"]["build"] and proof["configSha256"] == sha(canonical(config)) and proof["sourceGraphSha256"] == config["sourceGraphSha256"], "Build/config/sourceGraph changed")
    need(proof["syntheticOnly"] is True, "Synthetic lane required")
    integer(proof["pid"], 1)
    integer(proof["processStartElapsed"], 1)


def validate_writer(raw, plan, config, baseline):
    need(len(raw) <= 65536, "Writer checkpoint size bound")
    proof = loads(raw)
    exact(proof, WRITER_FIELDS)
    common_proof(proof, plan, config)
    need(raw == canonical(proof), "Original writer-ready bytes must be canonical and complete")
    need(proof["phase"] == "writer-held-final-read" and proof["candidateUnreferenced"] is True and proof["originalOperationUnchanged"] is True and proof["savedCallbacks"] == 0 and proof["mustForceStopWithinSeconds"] == 18, "Not the interrupted checkpoint contract")
    need(integer(proof["readyElapsed"], 1) >= proof["processStartElapsed"], "Invalid writer timeline")
    for key in ["candidateSha256", "metadataSha256", "identitySha256", "grantSha256", "claimSha256", "journalSha256", "originalDraftSha256", "claimRequestsSha256", "namedReadsSha256"]:
        digest(proof[key])
    need(proof["defaults"] == baseline, "Original default files changed before ready")
    need(re.fullmatch(r"agent-easywork-[a-f0-9]{64}-[a-f0-9-]{36}", proof["candidateRef"]), "Unexpected candidate reference")
    need(all(isinstance(proof[k], str) and 1 <= len(proof[k]) <= 2048 for k in ["grantRef", "claimKey", "journalKey", "originalTargetKey"]), "Invalid original reference")
    return proof


def lineage_paths(proof):
    keys = ["easywork-device-identity", proof["grantRef"], proof["claimKey"], proof["journalKey"], "easywork-credential:" + proof["candidateRef"], "metadata"]
    need(len(set(keys)) == 6, "Lineage references overlap")
    return ["no_backup/" + proof["namespace"] + "/" + sha(key.encode()) + ".bin" for key in keys]


def validate_stats(stats, proof):
    need(stats["caseId"] == proof["caseId"] and stats["held"] == 1 and stats["released"] is False, "Original final read no longer held")
    need(stats["errors"] == [] and stats["realDesktopRequests"] == stats["realModelInputs"] == 0, "Fixture boundary failed")
    need(sha(android_json(stats["requests"])) == proof["claimRequestsSha256"] and sha(android_json(stats["reads"])) == proof["namedReadsSha256"], "New claim or named protocol read")
    need(sum(r["method"] == "POST" for r in stats["requests"]) == 1, "Exactly one original claim POST required")
    need(all(r["method"] in ["runtime/context", "project/list", "thread/list"] for r in stats["reads"]), "Unexpected protocol method")


def validate_reader(raw, writer_raw, plan, config, external):
    need(len(raw) <= 65536, "Reader proof size bound")
    proof, writer = loads(raw), loads(writer_raw)
    exact(proof, READER_FIELDS)
    common_proof(proof, plan, config)
    need(raw == canonical(proof), "Reader proof bytes incomplete")
    need(proof["phase"] == "reader-complete" and proof["writerPid"] == writer["pid"] and proof["pid"] != writer["pid"] and proof["processStartElapsed"] > writer["readyElapsed"], "Not a new reader process")
    need(external["pid"] == proof["pid"] and external["startElapsedMs"] > writer["readyElapsed"] and abs(external["startElapsedMs"] - proof["processStartElapsed"]) <= 2000, "External reader lineage mismatch")
    need(proof["writerProofSha256"] == sha(canonical(writer)), "Original writer proof changed")
    for key in ["candidateStillUnreferenced", "candidateGrantClaimJournalMetadataUnchanged", "defaultEncryptedFilesUnchanged", "realProcessReopen"]:
        need(proof[key] is True, "Reader protection failed: " + key)
    need(proof["newClaimOrProtocolReadOrSubmit"] is False, "Reader performed extra work")
    return proof
