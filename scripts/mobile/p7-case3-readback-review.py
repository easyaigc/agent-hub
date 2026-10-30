#!/usr/bin/env python3
"""Offline parent byte review only. No Android, key, network, cleanup or ACK submission API."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
import tarfile

PROFILE = "source55-case3-original-v1"
FIXTURE = "8f76fb48-6e8a-4920-a26d-08bd121724c1"
RECORDING = "a9e05d2a-b339-4453-8806-1bc0bf0c8bf9"
METHOD = "unresolvedDiscardKeepsModelActionsGatedUntilExplicitReceiptRecovery"
ROOT = "files/acceptance/P7-voice-interaction/23395eea-62fc-43cf-90d7-328c0e2fcaf0/" + METHOD
ARCHIVE_SHA = "b9c3b45463fc836a251a35bf419eef84ce294a8f95b6197e0e6ca961be1728e1"
INDEX_SHA = "a2d3c7f59988aa22e34e3ba7f64bcfe1acf354a5a496150b6196e20cc51c97f4"
PROOF_SHA = "ed6fedee4d955fb2a6af9d469b7a5d9636d123dc0668cde7c07bdda04c81aed4"
WAV_SHA = "9d926e59a8259999491bddd13cd65ad6ff3f63916a9dd10a86d8594169ad9550"
HASH = re.compile(r"[a-f0-9]{64}\Z")


def need(condition, message):
    if not condition:
        raise ValueError(message)


def same_json(a, b):
    if type(a) is not type(b):
        return False
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(same_json(a[k], b[k]) for k in a)
    if isinstance(a, list):
        return len(a) == len(b) and all(same_json(x, y) for x, y in zip(a, b))
    return a == b


def sha(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(data):
    def pairs(rows):
        result = {}
        for key, value in rows:
            need(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    def invalid_constant(_):
        raise ValueError("non-JSON constant")
    return json.loads(data.decode("utf-8", "strict"), object_pairs_hook=pairs, parse_constant=invalid_constant)


def verify_original_audit(audit, persisted_row, original_sha):
    """Audit stores the complete persisted row, including storage and recovery fields."""
    need(type(audit) is str and type(persisted_row) is str, "complete original audit")
    data = audit.encode("utf-8")
    need(audit == persisted_row and sha(data) == original_sha
         and same_json(strict_json(data), strict_json(persisted_row.encode("utf-8"))), "complete original audit")


def private_bytes(path, maximum):
    """No symlinks/hardlinks; full FD bytes and unchanged FD/path stat, including mode/UID."""
    path = Path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_uid == os.getuid()
             and stat.S_IMODE(before.st_mode) == 0o600 and 0 < before.st_size <= maximum, "private file fence")
        chunks = []
        count = 0
        while True:
            chunk = os.read(fd, min(65536, maximum + 1 - count))
            if not chunk:
                break
            chunks.append(chunk)
            count += len(chunk)
            need(count <= maximum, "file cap")
        fields = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid, s.st_nlink,
                            s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        need(count == before.st_size and fields(before) == fields(os.fstat(fd))
             == fields(os.lstat(path)), "changed file")
        return b"".join(chunks)
    finally:
        os.close(fd)


def source(archive, index):
    need(sha(archive) == ARCHIVE_SHA and sha(index) == INDEX_SHA, "fixed Source55 archive/index")
    ix = strict_json(index)
    rows = ix["members"]
    need(len(rows) == 15 and len({r["path"] for r in rows}) == 15, "inventory size")
    expected = {r["path"]: r for r in rows}
    contents = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = tar.getmembers()
        need(len(members) == 15 and {m.name.rstrip("/") for m in members} == set(expected), "complete tar members")
        for member in members:
            row = expected[member.name.rstrip("/")]
            directory = stat.S_ISDIR(row["mode"])
            need(member.isdir() if directory else member.isfile(), "no tar links or special members")
            need(member.uid == row["uid"] == 10076 and member.gid == row["gid"] == 10076
                 and member.mode == stat.S_IMODE(row["mode"]), "tar ownership/mode")
            if not directory:
                need(member.size == row["bytes"] <= 65536, "source member cap")
                data = tar.extractfile(member).read()
                need(len(data) == row["bytes"] and sha(data) == row["sha256"], "full source member bytes")
                contents[member.name] = data
    need(sum(len(v) for v in contents.values()) == 16641, "full payload byte count")
    proof_data = contents[ROOT + "/" + FIXTURE + "/original.json"]
    need(sha(proof_data) == PROOF_SHA, "original proof binding")
    lines = []
    for row in rows:
        relative = row["path"][len(ROOT):].lstrip("/")
        lines.append("\t".join(str(x) for x in (relative, row["mode"], row["uid"], row["gid"], row["nlink"],
                     row["bytes"], row["device"], row["inode"], row["mtime"], row["ctime"], row.get("sha256", ""))))
    return strict_json(proof_data), lines, sha(("\n".join(lines) + "\n").encode())


def review(archive, index, intent_bytes, wav, receipt_bytes, app_sha, runner_sha):
    need(bool(HASH.fullmatch(app_sha)) and bool(HASH.fullmatch(runner_sha)), "candidate hashes")
    proof, inventory, inventory_sha = source(archive, index)
    receipt = strict_json(receipt_bytes)
    base = dict(schema=1, profile=PROFILE, package="com.easyai.agenthub.android.acceptance", uid=10076, sdk=26,
                appSha256=app_sha, runnerSha256=runner_sha, sourceArchiveSha256=ARCHIVE_SHA,
                sourceIndexSha256=INDEX_SHA, inventorySha256=inventory_sha, originalProofSha256=PROOF_SHA)
    need(same_json(strict_json(intent_bytes), dict(base, phase="read", sourceMutationAuthorized=False)), "read intent")
    facts = dict(phase="read-complete", wavBytes=3244, wavSha256=WAV_SHA, original=proof, inventory=inventory,
                 inventoryUnchanged=True, readOnlyExistingKey=True, sourceFileWrites=0, sourceDeletes=0, keysCreated=0,
                 originalRawWavStillPresent=False, nativeReadbackWavProduced=True, parentFullWavCaptured=False,
                 parentIndependentDecryption=False, hostCalls=0, microphoneCalls=0, modelCalls=0, discardReplays=0)
    need(set(receipt) == set(base) | set(facts) | {"index", "catalog", "audit", "catalogMarker", "discardFingerprint"}, "receipt fields")
    # type-sensitive JSON comparison: Python bool must not accept an integer claim, or vice versa.
    for key, value in dict(base, **facts).items():
        need(same_json(receipt[key], value), "receipt binding: " + key)
    current = strict_json(proof["canonicalOriginal"].encode())
    row = strict_json(proof["persistedRow"].encode())
    need(all(same_json(row[k], v) for k, v in current.items()), "complete original DTO")
    need(same_json(strict_json(receipt["index"].encode()), [RECORDING]), "original index")
    catalog = strict_json(receipt["catalog"].encode())
    need(set(catalog) == {"version", "entries"} and type(catalog["version"]) is int and catalog["version"] == 1 and type(catalog["entries"]) is list and len(catalog["entries"]) == 1, "catalog")
    marker = catalog["entries"][0]
    need(set(marker) == {"recordingId", "fingerprint", "manifestKnown", "auditId", "originalSha256", "discardedAt", "evidence"}, "marker fields")
    need(same_json(marker, receipt["catalogMarker"]) and marker["recordingId"] == RECORDING and marker["manifestKnown"] is True
         and marker["evidence"] == {"kind": "sealed"} and marker["fingerprint"] == receipt["discardFingerprint"]
         and bool(HASH.fullmatch(marker["fingerprint"])), "fixed original marker")
    audit_name = "voice-discard-original-v1:" + RECORDING + ":" + marker["auditId"]
    need(sha(audit_name.encode()) == "747d72f24a82e98411d210dc16d9e713b79ca92b2c02b3c3c21eba4d20ca15b4", "only approved audit logical name")
    verify_original_audit(receipt["audit"], proof["persistedRow"], marker["originalSha256"])
    manifest = current["manifest"]
    need(manifest["operationId"] == RECORDING and manifest["sha256"] == WAV_SHA
         and manifest["sizeBytes"] == 3244 and len(wav) == 3244 and sha(wav) == WAV_SHA, "full captured WAV manifest")
    # Independent complete comparison with the published synthetic PCM input, not a decryption claim.
    pcm = bytes(i % 127 for i in range(3200))
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 3236, b"WAVE", b"fmt ", 16, 1, 1, 16000, 32000, 2, 16, b"data", 3200)
    need(wav == header + pcm, "every synthetic WAV byte")
    return dict(schema=1, profile=PROFILE, readReceiptSha256=sha(receipt_bytes), inventorySha256=inventory_sha,
                wavSha256=sha(wav), wavBytes=len(wav), parentFullWavCaptured=True,
                parentAllOriginalStringsCompared=True, parentSourceCipherArchiveCaptured=True,
                parentIndependentDecryption=False, cleanupAuthorized=False,
                next="Root independently reviews worker exit and saved capture proof, then explicitly types the ACK. No ACK is generated or sent here.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("archive", "index", "readback-dir", "app-sha256", "runner-sha256"):
        parser.add_argument("--" + flag, required=True)
    args = parser.parse_args()
    root = Path(args.readback_dir)
    need(not root.is_symlink() and root.is_dir() and stat.S_IMODE(os.lstat(root).st_mode) == 0o700 and os.lstat(root).st_uid == os.getuid(), "private captured directory")
    need(set(os.listdir(root)) == {"read-intent.json", "original.wav", "read-receipt.json"}, "exact captured read outputs")
    result = review(private_bytes(args.archive, 65536), private_bytes(args.index, 32768),
                    private_bytes(root / "read-intent.json", 8192), private_bytes(root / "original.wav", 3244),
                    private_bytes(root / "read-receipt.json", 65536), args.app_sha256, args.runner_sha256)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
