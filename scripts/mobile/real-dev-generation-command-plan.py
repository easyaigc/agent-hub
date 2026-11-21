#!/usr/bin/env python3
"""Offline lease/intent validation only. Never connects, launches, pairs or sends."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import time
import uuid

SLOTS = {"q03": ("q03",), "q04": ("origin", "queueCancel", "queueExecute", "steer", "stopOrigin")}
HEX = re.compile(r"[0-9a-f]{64}\Z")
COMMAND = 'python3 -c "import time; time.sleep(40)"'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity(value):
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, "canonical UUID required")


def validate(p, now_ms=None):
    now = int(time.time() * 1000) if now_ms is None else now_ms
    require(p.get("version") == 1, "version")
    require(p.get("scenario") in SLOTS, "scenario")
    for key in ("run", "leaseId", "controlHostId"):
        identity(p[key])
    require(p.get("startupMode") == "new-isolated-installation", "fresh isolated profile only")
    for key in ("isolatedTargetApproved", "newBindingAuthorized", "modelBudgetApproved", "nativeAiSharingApproved"):
        require(p.get(key) is True, key)
    require(now + 60_000 <= p["expiresAtEpochMs"] <= now + 1_800_000, "bounded current lease")
    require(p.get("nativeOperationIdMode") == "journal-generated-once", "production UI assigns its ID once; no spoofed UUID")
    require(p.get("onUnknown") == "query-original-only", "unknown must not replay")
    target = p["target"]
    require(target.get("agentId") in ("codex", "easywork"), "provider")
    require(p["provider"] == target["agentId"], "provider mismatch")
    for key in ("hostId", "workspaceId", "runtimeId"):
        require(isinstance(target.get(key), str) and bool(target[key].strip()), "complete target")
    if target["agentId"] == "codex":
        require(target["runtimeId"] == "desktop-runtime", "original Codex runtime only; locality is independently mapped")
    else:
        require(all(target.get("account", {}).get(k) for k in ("authority", "subject")), "EasyWork account scope")
        require(HEX.fullmatch(p["desktopPublicKeySha256"]), "desktop public key")
    excluded = p["excludedCodingThreadIds"]
    require(len(excluded) == len(set(excluded)) == 6, "exact six excluded coding/control chats")
    require(p["threadId"] and p["threadId"] not in excluded, "dedicated business chat required")
    require(p["businessChatAuthorized"] is True, "business chat authority")
    baseline = p["baselineTurnIds"]
    require(isinstance(baseline, list) and len(baseline) == len(set(baseline)) and all(isinstance(x, str) and x for x in baseline), "frozen baseline Turns")
    require(len(baseline) == 0 if p["scenario"] == "q03" else len(baseline) <= 1, "new task business history only")
    for key in ("installationAndroidIdSha256", "appSha256", "runnerSha256", "signerSha256", "localMappingSha256",
                "certificateSha256", "pairingFileSha256", "pairingIntentSha256", "desktopRuntimeFingerprint", "desktopExecutableSha256", "productDirectoryProofSha256", "w2InstallationSourceSha256"):
        require(HEX.fullmatch(p[key]), key)
    require(p["endpoint"] in p["allowedEndpoints"] and len(p["allowedEndpoints"]) == len(set(p["allowedEndpoints"])), "exact routes")
    for endpoint in p["allowedEndpoints"]:
        from urllib.parse import urlsplit
        u = urlsplit(endpoint)
        require(u.scheme == "https" and u.hostname and not u.username and not u.password and u.path in ("", "/") and not u.query and not u.fragment, "HTTPS origin only")
    require(p["settings"]["model"] and p["settings"]["effort"] == "xhigh", "explicit model/xhigh")
    require(not any(k in p["settings"] for k in ("speed", "serviceTier", "service_tier")), "Host speed injection forbidden")
    if target["agentId"] == "codex":
        require(p["settings"]["model"] == "gpt-6.1-sol" and p["settings"]["permissionMode"] == "full-access", "requested Codex selection")
    speed = p.get("nativeSpeedSelection")
    require(speed is None or speed in ("standard", "fast", "ultrafast"), "native UI speed only")
    slots = p["slots"]
    require(tuple(slots) == SLOTS[p["scenario"]] or set(slots) == set(SLOTS[p["scenario"]]), "exact input budget")
    ids = []
    for name, slot in slots.items():
        identity(slot["slotId"])
        ids.append(slot["slotId"])
        require(slot.get("attachments") == [] and slot.get("text"), "unique text / zero attachments")
        require(digest(slot["text"].encode()) == slot["textSha256"], "immutable body digest")
        require(not any(k in slot for k in ("operationId", "clientMessageId")), "do not pretend slot UUID is native operation ID")
    require(len(ids) == len(set(ids)), "duplicate slot")
    require(len({x["textSha256"] for x in slots.values()}) == len(slots), "slot bodies must be distinguishable")
    require(p["maxTextSubmissions"] == len(slots), "text budget mismatch")
    if p["scenario"] == "q04":
        require(p["maxChildStarts"] == 0 and 40 <= p["maxToolSeconds"] <= 45, "bounded independent control scene")
        directory = p["modelToolWorkingDirectory"]
        require(isinstance(directory, str) and directory.startswith("/") and str(Path(directory)) == directory and "/../" not in directory, "frozen absolute empty task workdir")
        for name in ("origin", "stopOrigin"):
            require(COMMAND in slots[name]["text"] and "sleep(90)" not in slots[name]["text"], "one bounded 40-second tool")
            require(slots[name].get("toolWorkdir") == directory and directory in slots[name]["text"], "explicit frozen tool workdir in each origin intent")
        require(p["toolCommandSha256"] == digest(COMMAND.encode()), "tool fingerprint")
    else:
        require(p["maxChildStarts"] in (0, 1), "one optional official local child")
        require(p["maxChildStarts"] == 0 or target["agentId"] == "codex", "combined child candidate is Codex-only")
    require(p["expectedFinalMarker"] and len(p["expectedFinalMarker"]) <= 120 and not any(x in p["expectedFinalMarker"] for x in "<>"), "unique bound final marker, not a template")
    return p


def validate_mapping(p, mapping):
    require(mapping.get("version") == 1 and mapping.get("sourceAuthority") == "independent-local-desktop-and-host", "independent mapping source")
    require(mapping.get("controlHostId") == p["controlHostId"] and mapping.get("threadId") == p["threadId"], "control/chat mapping")
    require(mapping.get("actualTarget") == p["target"] and mapping.get("endpoint") in p["allowedEndpoints"], "actual Provider target/routes mapping")
    env = mapping["environment"]; identity = env["identity"]
    require(env.get("processAndInstallVerified") is True and env.get("originalOwnerVerified") is True and env.get("threadId") == p["threadId"], "actual local original environment")
    require(identity.get("hostId") == p["target"]["hostId"] and identity.get("runtimeId") == p["target"]["runtimeId"], "actual Provider identity")
    require(identity.get("executableSha256") == p["desktopExecutableSha256"] and identity.get("executable") == p["desktopExecutable"], "actual executable digest")
    keys = ("hostId", "runtimeId", "pid", "uid", "startedAtText", "executable", "device", "inode", "size", "executableSha256", "socket", "socketDevice", "socketInode")
    fingerprint = digest(json.dumps({key: identity[key] for key in keys}, ensure_ascii=False, separators=(",", ":")).encode())
    require(env.get("fingerprint") == fingerprint == p["desktopRuntimeFingerprint"], "actual composite runtime fingerprint")
    require(mapping["expiresAtEpochMs"] == p["expiresAtEpochMs"], "mapping lease")
    return mapping


def budget(combined=False):
    marker = "<controller-owned-marker>"
    q03 = f"Reply exactly Q03_DONE_{marker}. Do not call tools, read or write files, access external networks, contact chats or delegate."
    if combined:
        q03 = (f"Create exactly one official LOCAL child agent using gpt-6.1-sol/xhigh if actually supported; no recursive delegation. "
               f"Child must not use tools/files/network or contact other chats; ask it to return 96 numbered lines, each with CHILD_{marker} and 48 repeated ASCII letters. "
               f"Wait for that same child once; never create a replacement on failure. Then reply exactly Q03_DONE_{marker}.")
    return {"candidateOnly": True, "executeNow": False, "q03": {"text": q03, "parentStarts": 1, "childStarts": int(combined),
            "note": "Combined body is a NEW intent; old no-tool marker intent stays immutable. Q02 UI/live-child proof remains separate."},
            "q04": {"textSubmissions": 5, "intendedIndependentParentStarts": 2, "conservativeParentStartsCeiling": 5, "queueTexts": 2, "directSteer": 1,
                    "cancelMax": 1, "executeOriginalMax": 1, "stopMax": 1, "toolSecondsPerOrigin": 40,
                    "maxToolSeconds": 45, "resumeRestartReplay": 0, "command": COMMAND,
                    "terminalApiPlan": {"start": "exec_command once", "yieldTimeMs": 30000, "poll": "write_stdin same original session only",
                                        "maxPolls": 1, "pollMaxWaitMs": 10000, "overallDeadlineMs": 45000,
                                        "pollAssociationProved": False, "newProcessOnTimeout": False}},
            "allOtherPlatformProviderCellsBudgeted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    b = sub.add_parser("budget"); b.add_argument("--combined-child", action="store_true")
    for action in ("validate", "freeze"):
        s = sub.add_parser(action); s.add_argument("--input", required=True); s.add_argument("--mapping", required=True)
        if action == "freeze": s.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.action == "budget":
        print(json.dumps(budget(args.combined_child), indent=2)); return
    source = Path(args.input)
    require(source.is_file() and not source.is_symlink() and source.stat().st_size <= 65536 and source.stat().st_uid == os.getuid() and source.stat().st_mode & 0o077 == 0 and source.stat().st_nlink == 1, "private bounded input")
    p = validate(json.loads(source.read_text()))
    mapping_file = Path(args.mapping)
    require(mapping_file.is_file() and not mapping_file.is_symlink() and mapping_file.stat().st_uid == os.getuid() and mapping_file.stat().st_mode & 0o077 == 0 and mapping_file.stat().st_size <= 65536, "private mapping input")
    mapping_bytes = mapping_file.read_bytes()
    require(digest(mapping_bytes) == p["localMappingSha256"], "frozen local mapping digest")
    validate_mapping(p, json.loads(mapping_bytes))
    data = (json.dumps(p, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
    if args.action == "freeze":
        parent = Path(args.output).parent
        require(parent.resolve() == parent.absolute() and parent.is_dir() and not parent.is_symlink() and parent.stat().st_uid == os.getuid() and parent.stat().st_mode & 0o077 == 0, "private output directory")
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        print(json.dumps({"sha256": digest(data), "executed": False}))
    else:
        print(json.dumps({"valid": True, "executed": False, "sourceSha256": digest(source.read_bytes())}))


if __name__ == "__main__":
    main()
