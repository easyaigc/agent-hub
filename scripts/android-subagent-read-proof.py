#!/usr/bin/env python3
"""Hash an exact, privately frozen subagent source; never fetch, launch or send.

The input stays outside Git. A descriptor is an expected-content witness, not
proof that a phone displayed it or that the original child owner executed it.
"""
import argparse
import hashlib
import json
import os
import stat
from pathlib import Path


LIMIT = 8 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def private_json(path):
    path = Path(path).absolute()
    require(path.resolve() == path, "Input path must not traverse a symlink")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1,
                "Input must be a single regular private file")
        require(before.st_uid == os.getuid() and before.st_mode & 0o077 == 0,
                "Input must be private to its owner")
        require(0 < before.st_size <= LIMIT, "Explicit 8MiB input cap exceeded")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            raw = stream.read(LIMIT + 1)
        after = os.fstat(fd)
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
                "Input changed while reading")
        require(len(raw) == before.st_size, "Input size changed")
        return json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()
    finally:
        os.close(fd)


def digest(text):
    return hashlib.sha256(text.encode("utf-8", errors="strict")).hexdigest()


def target(value):
    require(isinstance(value, dict), "Complete target required")
    require(value.get("agentId") in ("codex", "easywork"), "Unsupported provider")
    for key in ("hostId", "workspaceId", "runtimeId"):
        require(isinstance(value.get(key), str) and value[key], "Incomplete target")
    if value["agentId"] == "easywork":
        require(isinstance(value.get("account"), dict), "EasyWork account binding required")
        for key in ("authority", "subject"):
            require(isinstance(value["account"].get(key), str) and value["account"][key],
                    "Incomplete EasyWork account binding")
    return value


def descriptor(source, source_sha):
    require(source.get("sourceAuthority") in
            ("original-electron", "official-host-projection", "production-adapter"),
            "Explicit source authority required")
    scope = target(source["target"])
    body = source["detail"]
    require(isinstance(body, str) and body, "Nonempty exact body required")
    points = list(body)
    width = min(512, len(points))
    middle = (len(points) - width) // 2
    result = {"target": scope, "sourceAuthority": source["sourceAuthority"],
              "sourceFileSha256": source_sha,
              "detailUtf8Bytes": len(body.encode("utf-8", errors="strict")),
              "detailUtf16Units": len(body.encode("utf-16-le", errors="strict")) // 2,
              "detailSha256": digest(body), "prefixSha256": digest(body[:width]),
              "middleSha256": digest(body[middle:middle + width]),
              "suffixSha256": digest(body[len(points) - width:]),
              "nativeUIVerified": False, "independentChildOwnerExecutionVerified": False}
    for key in ("threadId", "messageId", "rootRunId", "childId", "status"):
        require(isinstance(source.get(key), str) and source[key], "Missing scoped identity/status")
        result[key] = source[key]
    # Null means the authority supplied no version. Do not invent one for Codex.
    for key in ("historyRevision", "epoch", "cursor", "branchId", "branchRevision",
                "ownerThreadId", "ownerTurnId", "ownerEvidenceSha256", "modelOperationId"):
        result[key] = source.get(key)
    for key in ("historyRevision", "cursor", "branchRevision"):
        value = result[key]
        require(value is None or type(value) is int and 0 <= value <= 9007199254740991,
                "Invalid authority revision/cursor")
    return result


def write_private(path, value):
    path = Path(path).absolute()
    require(path.parent.resolve() == path.parent and path.parent.is_dir(),
            "Create the exact private output parent first")
    mode = path.parent.stat().st_mode
    require(mode & 0o077 == 0, "Output directory must be private")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="Private exact source JSON; no credentials")
    parser.add_argument("--output", required=True, help="New private descriptor JSON; never overwritten")
    args = parser.parse_args()
    try:
        source, sha = private_json(args.source)
        result = descriptor(source, sha)
        write_private(args.output, result)
        print(json.dumps({"descriptorWritten": True, "detailSha256": result["detailSha256"],
                          "detailUtf8Bytes": result["detailUtf8Bytes"],
                          "nativeUIVerified": False, "independentChildOwnerExecutionVerified": False}))
    except (OSError, ValueError, KeyError, TypeError):
        # Never echo raw JSON/parser exceptions: they can quote original content.
        parser.exit(2, "Source/identity/private-path validation failed; no device or network action.\n")


if __name__ == "__main__":
    main()
