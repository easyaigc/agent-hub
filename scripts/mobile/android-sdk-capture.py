#!/usr/bin/env python3
"""Controlled local Android SDK capture. Only the p7/p9 build profiles are exposed by CLI."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import shutil
import stat
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
CAP = 2 * 1024 * 1024
APK_CAP = 256 * 1024 * 1024
_LIVE = {}  # Exact Popen owners are retained before any fallible owner/marker/raw operation.
APP = "com.easyai.agenthub.android.acceptance"
PROFILES = {
    "p7-pristine": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p9": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest", ":android-inbox-examiner:assembleDebug"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test"),
        "examiner": (".build/native/android-inbox-examiner/outputs/apk/debug/android-inbox-examiner-debug.apk", APP + ".coldexaminer")}},
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def private_directory(path, empty=False):
    path = Path(path).absolute(); info = path.lstat()
    require(path.resolve() == path and stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "Private canonical directory required")
    if empty:
        require(not any(path.iterdir()), "Fresh empty capture directory required")
    return path


def private_bytes(path, maximum, empty=False):
    path = Path(path); private_directory(path.parent); before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and stat.S_IMODE(before.st_mode) == 0o600 and before.st_nlink == 1 and (0 if empty else 1) <= before.st_size <= maximum, "Private bounded evidence required")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        require(identity(os.fstat(fd)) == identity(before), "Evidence open changed")
        chunks = []; size = 0
        while True:
            chunk = os.read(fd, min(65536, maximum - size + 1)); size += len(chunk)
            require(size <= maximum, "Evidence byte quota")
            if not chunk:
                break
            chunks.append(chunk)
        require(size == before.st_size and identity(os.fstat(fd)) == identity(before), "Evidence descriptor changed")
    finally:
        os.close(fd)
    require(identity(path.lstat()) == identity(before), "Evidence path changed")
    return b"".join(chunks)


def same(left,right):
    if type(left) is not type(right):return False
    if type(left) is dict:return set(left)==set(right) and all(same(left[key],right[key]) for key in left)
    if type(left) is list:return len(left)==len(right) and all(same(a,b) for a,b in zip(left,right))
    return left==right


def write_bytes(path, raw):
    path = Path(path); private_directory(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        at = 0
        while at < len(raw):
            count = os.write(fd, raw[at:]); require(count > 0, "Evidence write stalled"); at += count
        os.fsync(fd)
    finally:
        os.close(fd)


def write_json(path, value):
    raw = encoded(value); require(len(raw) <= 512 * 1024, "Capture JSON quota"); write_bytes(path, raw)


def parse_json(raw):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, "Duplicate capture JSON key"); result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def load_json(path, maximum=512 * 1024):
    return parse_json(private_bytes(path,maximum))


def private_digest(path, maximum):
    path=Path(path); private_directory(path.parent); before=path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid==os.getuid() and stat.S_IMODE(before.st_mode)==0o600 and before.st_nlink==1 and 0<=before.st_size<=maximum,"Private digest input")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK); digest=hashlib.sha256(); total=0
    try:
        require(identity(os.fstat(fd))==identity(before),"Private digest open changed")
        while True:
            chunk=os.read(fd,65536)
            if not chunk:break
            total+=len(chunk);require(total<=maximum,"Private digest quota");digest.update(chunk)
        require(total==before.st_size and identity(os.fstat(fd))==identity(before),"Private digest FD changed")
    finally:os.close(fd)
    require(identity(path.lstat())==identity(before),"Private digest path changed")
    return {"sha256":digest.hexdigest(),"bytes":total}


def owner_start(child):
    # ps is only an independent read helper. A fast already-exited command needs no live recovery stamp.
    if child.poll() is not None:
        return None
    value = subprocess.run(["/bin/ps", "-p", str(child.pid), "-o", "lstart=", "-o", "uid="], stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=2, check=False)
    require(len(value.stdout) + len(value.stderr) <= 1024, "Owner ps quota")
    if child.poll() is not None:
        return None
    require(value.returncode == 0 and not value.stderr and value.stdout.strip(), "Live owner start observation failed")
    return value.stdout.decode("utf-8").strip()


class CaptureFailure(RuntimeError):
    def __init__(self, owner):
        super().__init__("Capture is permanently UNKNOWN; actual worker wait has returned; inspect owner/error evidence")
        self.owner = owner


class Owner:
    def __init__(self, child, output, prefix, intent, raw_fds):
        self.child = child; self.output = output; self.prefix = prefix; self.intent = intent; self.raw_fds = raw_fds
        self.waited = False; self.code = None; self.primary = None; self.errors = []; self.error_count = 0
        self.owner_start = None; self.marker_attempted = False; self.owner_saved = False; self.marker_saved = False
        self.buffers = {name: bytearray() for name in raw_fds}; self.disk_bytes = {name: 0 for name in raw_fds}
        self.raw_ok = {name: True for name in raw_fds}; self.eof = set(); self.nonblocking = set()

    def fail(self, error, phase):
        if self.primary is None:
            self.primary = error
        self.error_count += 1
        note = {"phase": phase, "type": type(error).__name__, "errno": getattr(error, "errno", None)}
        if len(self.errors) < 16:
            self.errors.append(note)
            if error is not self.primary:
                try:
                    self.primary.add_note("P7 capture secondary failure: " + json.dumps(note, sort_keys=True))
                except BaseException:
                    pass
        # Storage failure must be visible even if neither marker nor receipt can be persisted.
        # This never claims that a failed evidence write succeeded.
        if self.error_count <= 16:
            try:
                os.write(2, encoded({"status": "UNKNOWN", "hostOwnerPid": os.getpid(), "childPid": self.child.pid,
                    "operationId": self.intent["operationId"], "failure": note, "ownerSaved": self.owner_saved,
                    "unknownMarkerSaved": self.marker_saved}) + b"\n")
            except BaseException:
                pass  # _LIVE still strongly owns the exact child; no IO is a precondition to wait.

    def announce(self):
        if self.marker_attempted:
            return
        self.marker_attempted = True
        try:
            write_json(self.output / (self.prefix + "-UNKNOWN.json"), {"schema": "android-capture-held-owner-v1",
                "operationId": self.intent["operationId"], "invocationSha256": sha(encoded(self.intent)),
                "hostOwnerPid": os.getpid(), "childPid": self.child.pid, "childStartObservation": self.owner_start,
                "status": "UNKNOWN", "lateResultCannotUpgrade": True, "rawMayBeIncomplete": True})
            self.marker_saved = True
        except BaseException as error:
            self.fail(error, "UNKNOWN-publication")

    def read_ready(self):
        pipes = {name: pipe for name, pipe in (("stdout", self.child.stdout), ("stderr", self.child.stderr)) if name not in self.eof}
        try:
            ready, _, _ = select.select(list(pipes.values()), [], [], 0.1)
        except BaseException as error:
            self.fail(error, "select")
            ready = [pipe for name, pipe in pipes.items() if name in self.nonblocking]
        for name, pipe in pipes.items():
            if pipe not in ready:
                continue
            try:
                chunk = os.read(pipe.fileno(), 65536)
            except BlockingIOError:
                continue
            except BaseException as error:
                self.fail(error, "read-" + name); continue
            if not chunk:
                self.eof.add(name); continue
            remaining = CAP - len(self.buffers[name]); retained = chunk[:max(0, remaining)]
            self.buffers[name].extend(retained)
            if len(retained) != len(chunk):
                if self.raw_ok[name]:
                    self.fail(ValueError("Raw stream cap exceeded"), "cap-" + name)
                self.raw_ok[name] = False
            if self.raw_ok[name]:
                at = 0
                try:
                    while at < len(retained):
                        count = os.write(self.raw_fds[name], retained[at:]); require(count > 0, "Raw write stalled")
                        at += count; self.disk_bytes[name] += count
                except BaseException as error:
                    self.raw_ok[name] = False; self.fail(error, "raw-write-" + name)

    def wait_once(self):
        if self.waited:
            return
        try:
            self.code = self.child.wait(timeout=0.1); self.waited = True
        except subprocess.TimeoutExpired:
            pass
        except BaseException as error:
            self.fail(error, "actual-wait")


def run_process(output, prefix, argv, cwd, env=None, environment=None, timeout=60, child_umask=0o022, tick=None):
    """Internal fixed-call-site primitive. Every role retains its exact child; no instrumentation signal path."""
    output=private_directory(output); require(re.fullmatch(r"[a-z0-9-]{1,64}",prefix),"Capture prefix")
    intent={"schema":"android-capture-invocation-v1","operationId":str(uuid.uuid4()),"prefix":prefix,
        "argv":list(argv),"cwd":str(Path(cwd).resolve()),"environment":environment or {},"childUmask":child_umask,
        "timeoutSeconds":timeout,"hostOwnerPid":os.getpid(),"createdWallNs":time.time_ns()}
    write_json(output/(prefix+"-invocation.json"),intent)
    raw_fds={}; child=None; owner=None; acquisition_error=None; actual_waited=False; actual_code=None
    try:
        for name in ("stdout","stderr"):
            raw_fds[name]=os.open(output/(prefix+"."+name+".bin"),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        # All Owner allocation and registry insertion happen BEFORE any actual child exists.
        # The whole acquisition/attach sequence is enclosed by the local-Popen finally below.
        owner=Owner(None,output,prefix,intent,raw_fds)
        _LIVE[intent["operationId"]]=owner
        child=subprocess.Popen(list(argv),cwd=cwd,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,umask=child_umask)
        owner.child=child
        try:
            owner.owner_start=owner_start(child)
            write_json(output/(prefix+"-owner.json"),{"schema":"android-capture-owner-v1","operationId":intent["operationId"],
                "invocationSha256":sha(encoded(intent)),"hostOwnerPid":os.getpid(),"childPid":child.pid,"childStartObservation":owner.owner_start})
            owner.owner_saved=True
        except BaseException as error:
            owner.fail(error,"owner-publication")
        for name,pipe in (("stdout",child.stdout),("stderr",child.stderr)):
            try:
                os.set_blocking(pipe.fileno(),False); owner.nonblocking.add(name)
            except BaseException as error:
                owner.fail(error,"nonblocking-"+name)
        deadline=time.monotonic()+timeout
        while not owner.waited or len(owner.eof)!=2:
            if owner.primary is None and time.monotonic()>=deadline:
                owner.fail(TimeoutError("Capture deadline reached; retain original child"),"deadline")
            if owner.primary is not None:owner.announce()
            owner.read_ready(); owner.wait_once()
            if owner.primary is None and tick is not None and not owner.waited:
                try:tick()
                except BaseException as error:owner.fail(error,"observation")
            if owner.waited and owner.primary is not None:break
    except BaseException as error:
        acquisition_error=error
        if owner is not None:
            try:owner.fail(error,"acquire-attach-or-loop")
            except BaseException:pass
    finally:
        # The independent local `child` is the fallback owner, even if attach/registry/metadata failed.
        # No fallible marker, selector, read or bookkeeping operation can bypass its actual wait.
        if child is not None:
            try:
                if owner is not None:
                    try:owner.child=child
                    except BaseException as error:
                        acquisition_error=acquisition_error or error
                while not actual_waited:
                    try:
                        if owner is not None and owner.child is child:
                            if owner.primary is not None:owner.announce()
                            owner.read_ready()
                        else:
                            # Attach failed: discard bounded chunks, retaining the real local Popen.
                            for pipe in (child.stdout,child.stderr):
                                try:
                                    os.set_blocking(pipe.fileno(),False); os.read(pipe.fileno(),65536)
                                except BlockingIOError:pass
                    except BaseException as error:
                        if owner is not None:
                            try:owner.fail(error,"retention-read")
                            except BaseException:pass
                    finally:
                        try:
                            actual_code=child.wait(timeout=0.1); actual_waited=True
                        except subprocess.TimeoutExpired:pass
                        except BaseException as error:
                            if owner is not None:
                                try:owner.fail(error,"retention-actual-wait")
                                except BaseException:pass
            finally:
                # An interruption in the fallback itself still cannot return with our child unconfirmed.
                while not actual_waited:
                    try:
                        for pipe in (child.stdout,child.stderr):
                            try:
                                os.set_blocking(pipe.fileno(),False); os.read(pipe.fileno(),65536)
                            except BlockingIOError:pass
                    except BaseException:pass
                    try:
                        actual_code=child.wait(timeout=0.1); actual_waited=True
                    except subprocess.TimeoutExpired:pass
                    except BaseException as error:
                        acquisition_error=acquisition_error or error
            if owner is not None:
                owner.waited=actual_waited; owner.code=actual_code
        for name,fd in raw_fds.items():
            try:os.fsync(fd)
            except BaseException as error:
                acquisition_error=acquisition_error or error
                if owner is not None:
                    owner.raw_ok[name]=False; owner.fail(error,"fsync-"+name)
            try:os.close(fd)
            except BaseException as error:
                acquisition_error=acquisition_error or error
                if owner is not None:
                    owner.raw_ok[name]=False; owner.fail(error,"close-raw-"+name)
        if child is not None:
            for name,pipe in (("stdout",child.stdout),("stderr",child.stderr)):
                try:pipe.close()
                except BaseException as error:
                    acquisition_error=acquisition_error or error
                    if owner is not None:owner.fail(error,"close-pipe-"+name)
        if owner is not None and child is not None:
            if acquisition_error is not None and owner.primary is None:owner.fail(acquisition_error,"acquisition")
            if owner.code!=0 and owner.primary is None:owner.fail(ValueError("Original command returned nonzero"),"return-code")
            if (len(owner.eof)!=2 or not all(owner.raw_ok.values())) and owner.primary is None:
                owner.fail(ValueError("Incomplete original dual streams"),"raw-completion")
            receipt={"schema":"android-capture-wait-v1","operationId":intent["operationId"],"invocationSha256":sha(encoded(intent)),
                "hostOwnerPid":os.getpid(),"childPid":child.pid,"childStartObservation":owner.owner_start,
                "actualWaitReturned":actual_waited,"returnCode":actual_code,"status":"passed" if owner.primary is None else "UNKNOWN",
                "stdoutSha256":sha(bytes(owner.buffers["stdout"])),"stderrSha256":sha(bytes(owner.buffers["stderr"])),
                "stdoutBytes":len(owner.buffers["stdout"]),"stderrBytes":len(owner.buffers["stderr"]),
                "rawComplete":owner.primary is None,"ownerSaved":owner.owner_saved,"unknownMarkerSaved":owner.marker_saved,
                "failureCount":owner.error_count,"failures":owner.errors}
            try:write_json(output/(prefix+"-wait.json"),receipt)
            except BaseException as error:owner.fail(error,"wait-receipt-publication")
        _LIVE.pop(intent["operationId"],None) # No child, or original local actual wait is now confirmed.
    if acquisition_error is not None and owner is None:raise acquisition_error
    if owner is None or child is None:
        raise acquisition_error or RuntimeError("No child was acquired; invocation remains sealed")
    if owner.primary is not None:raise CaptureFailure(owner) from owner.primary
    return {"intent":intent,"receipt":receipt,"stdout":bytes(owner.buffers["stdout"]),"stderr":bytes(owner.buffers["stderr"])}


def verify_process(output, prefix, argv=None, cwd=None, environment=None, child_umask=None, pins=None):
    output=private_directory(output)
    def read(name,maximum=512*1024,empty=False):
        raw=private_bytes(output/name,maximum,empty)
        if pins is not None:require(pins.get(name)=={"sha256":sha(raw),"bytes":len(raw)},"Sealed capture read changed")
        return raw
    intent_raw=read(prefix+"-invocation.json"); receipt_raw=read(prefix+"-wait.json")
    intent=parse_json(intent_raw); receipt=parse_json(receipt_raw)
    require(set(intent) == {"schema", "operationId", "prefix", "argv", "cwd", "environment", "childUmask", "timeoutSeconds", "hostOwnerPid", "createdWallNs"}, "Closed capture invocation")
    require(intent["schema"] == "android-capture-invocation-v1" and intent["prefix"] == prefix and str(uuid.UUID(intent["operationId"])) == intent["operationId"], "Capture invocation identity")
    require(type(intent["hostOwnerPid"]) is int and intent["hostOwnerPid"] > 0 and type(intent["createdWallNs"]) is int and type(intent["childUmask"]) is int and type(intent["timeoutSeconds"]) is int and 1<=intent["timeoutSeconds"]<=3600, "Capture primitive types")
    require(type(intent["argv"]) is list and all(type(atom) is str and "\0" not in atom for atom in intent["argv"]), "Capture argv types")
    if argv is not None:
        require(intent["argv"] == list(argv), "Wrong captured command")
    if cwd is not None:
        require(intent["cwd"] == str(Path(cwd).resolve()), "Wrong captured cwd")
    if environment is not None:
        require(intent["environment"] == environment, "Wrong captured tool environment")
    if child_umask is not None:
        require(intent["childUmask"] == child_umask, "Wrong child umask")
    expected = {"schema", "operationId", "invocationSha256", "hostOwnerPid", "childPid", "childStartObservation", "actualWaitReturned", "returnCode", "status", "stdoutSha256", "stderrSha256", "stdoutBytes", "stderrBytes", "rawComplete", "ownerSaved", "unknownMarkerSaved", "failureCount", "failures"}
    require(set(receipt) == expected and receipt["schema"] == "android-capture-wait-v1" and receipt["operationId"] == intent["operationId"] and receipt["invocationSha256"] == sha(encoded(intent)), "Capture receipt join")
    require(receipt["actualWaitReturned"] is True and receipt["rawComplete"] is True and receipt["ownerSaved"] is True and receipt["unknownMarkerSaved"] is False and type(receipt["returnCode"]) is int and receipt["returnCode"] == 0 and receipt["status"] == "passed" and type(receipt["failureCount"]) is int and receipt["failureCount"] == 0 and receipt["failures"] == [], "Only completed original successful capture accepted")
    require(receipt["childStartObservation"] is None or type(receipt["childStartObservation"]) is str and 1<=len(receipt["childStartObservation"])<=1024,"Owner start type")
    require(type(receipt["childPid"]) is int and receipt["childPid"] > 0 and receipt["hostOwnerPid"] == intent["hostOwnerPid"] and type(receipt["hostOwnerPid"]) is int, "Owner process join")
    owner = parse_json(read(prefix + "-owner.json"))
    require(same(owner, {"schema": "android-capture-owner-v1", "operationId": intent["operationId"], "invocationSha256": receipt["invocationSha256"], "hostOwnerPid": receipt["hostOwnerPid"], "childPid": receipt["childPid"], "childStartObservation": receipt["childStartObservation"]}), "Actual owner join")
    streams = {}
    for name in ("stdout", "stderr"):
        raw = read(prefix + "." + name + ".bin",CAP,empty=True)
        require(type(receipt[name + "Bytes"]) is int and receipt[name + "Bytes"] == len(raw) and receipt[name + "Sha256"] == sha(raw), "Dual raw stream join")
        streams[name] = raw
    require(not (output / (prefix + "-UNKNOWN.json")).exists(), "A sealed UNKNOWN cannot upgrade after late completion")
    return {"intent":intent,"receipt":receipt,"receiptSha256":sha(receipt_raw),"invocationSha256":sha(intent_raw),**streams}


def _tool(api, path, maximum=256 * 1024 * 1024):
    path = Path(path).resolve(); info = path.lstat()
    require(stat.S_ISREG(info.st_mode), "Actual tool file required")
    return {"path": str(path), "sha256": api.digest(path, maximum), "bytes": info.st_size}


def _environment(args):
    safe = {"JAVA_HOME": str(Path(args.java_home).resolve()), "ANDROID_HOME": str(Path(args.android_sdk).resolve()),
        "ANDROID_SDK_ROOT": str(Path(args.android_sdk).resolve()), "GRADLE_USER_HOME": str(Path(args.gradle_home).resolve()),
        "KOTLIN_NATIVE_HOME": "", "PATH": str(Path(args.java_home).resolve() / "bin") + os.pathsep + str(Path(args.node).resolve().parent) + os.pathsep + "/usr/bin:/bin:/usr/sbin:/sbin"}
    env = dict(os.environ)
    for key in list(env):
        if key in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS", "GRADLE_OPTS", "JAVA_OPTS"} or key.startswith("ORG_GRADLE_PROJECT_"):
            env.pop(key)
    env.update(safe); return env, safe


def _review(api, path, expected_sha, profile):
    raw = private_bytes(path, 131072); require(sha(raw) == expected_sha, "Root-reviewed-state fingerprint required")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == "p7-build-workspace-review-v1", "Reviewed state schema")
    api.canonical_uuid(value["reviewId"]); require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}",value["head"]),"Reviewed Git HEAD"); api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]; require(type(rows) is dict and len(rows) <= 64, "Reviewed overlay bound")
    if profile == "p7":
        require(set(rows) == set(api.DYNAMIC_SOURCES), "P7 allows only the six reviewed test/tool overlays")
    elif profile == "p7-pristine":
        require(set(rows) == set(api.DYNAMIC_SOURCES) | {"scripts/mobile/android-nonempty-pristine-first.py"},
            "Pristine P7 allows exactly six existing overlays plus its closed first-only adapter")
    else:
        # The exact Root-approved P9 list is data, never inferred from dirty status or a broad glob.
        # Product main/Core/build entries remain immutable; the authorized debug/examiner/tests are finite.
        require(1 <= len(rows) <= 16 and all(name == "apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt" or
            name.startswith("apps/android-inbox-examiner/src/") or name.startswith("apps/android/src/androidTest/") or
            name.startswith("scripts/mobile/") for name in rows), "P9 overlay outside reviewed debug/examiner/test scope")
    overlay = {}
    for name, pins in rows.items():
        require(type(name) is str and type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit before/after overlay pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Overlay before pin must match declared M baseline")
        if pins["beforeSha256"] is not None:
            api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(type(value["allowedDirtyPaths"]) is list and value["allowedDirtyPaths"] == sorted(set(value["allowedDirtyPaths"])) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in value["allowedDirtyPaths"]), "Exact reviewed dirty paths")
    return raw, value, overlay


def _git(output, suffix, repo, env, safe, review):
    commands = {"root": ["git", "rev-parse", "--show-toplevel"], "head": ["git", "rev-parse", "HEAD"],
        "status": ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], "unmerged": ["git", "diff", "--name-only", "--diff-filter=U"]}
    results = {name: run_process(output, "git-" + name + "-" + suffix, argv, repo, env, safe) for name, argv in commands.items()}
    require(results["root"]["stdout"].decode().strip() == str(repo) and results["head"]["stdout"].decode().strip() == review["head"] and not results["unmerged"]["stdout"], "Reviewed worktree/HEAD/unmerged state mismatch")
    raw = results["status"]["stdout"]; require(sha(raw) == review["statusSha256"], "Actual Git state is not Root-reviewed state")
    entries = raw.decode("utf-8").split("\0"); require(entries[-1] == "", "Status framing")
    paths = []
    for entry in entries[:-1]:
        require(len(entry) >= 4 and entry[2] == " " and not any(c in entry[:2] for c in "RCU"), "Rename/copy/conflict requires separate review")
        paths.append(entry[3:])
    require(sorted(paths) == review["allowedDirtyPaths"], "Unapproved dirty paths")
    return commands


def _gradle_home(path):
    path = Path(path).resolve(); require(path.is_dir(), "Existing task-owned Gradle cache required")
    require(not (path / "gradle.properties").exists(), "Unreviewed user Gradle properties")
    for name in ("init.gradle","init.gradle.kts"):
        require(not os.path.lexists(path/name),"Unreviewed top-level Gradle init entry")
    init = path / "init.d"; require(not init.exists() or not any(init.iterdir()), "Unreviewed Gradle init scripts")


def _copy_apk(api, source, destination, created_wall_ns):
    before = source.lstat(); require(stat.S_ISREG(before.st_mode) and not source.is_symlink() and 1 <= before.st_size <= APK_CAP and before.st_mtime_ns >= (created_wall_ns // 1_000_000_000) * 1_000_000_000, "SDK did not produce a fresh bounded APK")
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW); out = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        require(identity(os.fstat(fd)) == identity(before), "APK changed before copy")
        total = 0
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            total += len(chunk); require(total <= APK_CAP, "APK byte cap")
            at = 0
            while at < len(chunk):
                n = os.write(out, chunk[at:]); require(n > 0, "APK copy stalled"); at += n
        require(total == before.st_size and identity(os.fstat(fd)) == identity(before) and identity(source.lstat()) == identity(before), "APK changed during copy")
        os.fsync(out)
    finally:
        os.close(out); os.close(fd)
    return api.digest(destination, APK_CAP)


def _apk(role, recipe, result_cert, result_manifest, path, api):
    raw = result_cert["stdout"].decode("utf-8") + result_cert["stderr"].decode("utf-8")
    certs = re.findall(r"^(?:Signer #1|V(?:1|2|3(?:\.1)?|4) Signer):? certificate SHA-256 digest: ([a-fA-F0-9]{64})$", raw, re.M)
    require(re.findall(r"^Number of signers: ([0-9]+)$", raw, re.M) == ["1"] and bool(certs) and len({value.lower() for value in certs}) == 1 and not re.search(r"^Signer #(?:[2-9]|[1-9][0-9]+) certificate", raw, re.M), "Exactly one actual signer certificate required")
    badging = result_manifest["stdout"].decode("utf-8"); lines = [line for line in badging.splitlines() if line.startswith("package: ")]
    require(len(lines) == 1, "Actual APK package metadata required")
    fields = re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", lines[0]); values = dict(fields)
    require(len(fields) == len(values) and values.get("name") == recipe[1], "Wrong or duplicate built APK package fields")
    version = values.get("versionCode"); require(version in (None, "") or version.isdigit(), "APK version primitive")
    return {"path": str(path), "sourceRelative": recipe[0], "bytes": path.stat().st_size, "sha256": api.digest(path, APK_CAP),
        "package": recipe[1], "certificateSha256": certs[0].lower(), "versionCode": int(version) if version else None,
        "versionName": values.get("versionName") or None}


def capture_build(args, source_api):
    api = source_api; require(args.profile in PROFILES, "Closed SDK capture profile")
    os.umask(0o077); output = private_directory(args.output_dir, empty=True); repo = Path(args.repo).resolve()
    raw_review, review, overlay = _review(api, args.reviewed_state, args.reviewed_state_sha256, args.profile)
    require(review["head"] == args.expected_head, "Root-reviewed HEAD mismatch")
    _gradle_home(args.gradle_home); env, safe = _environment(args)
    require(shutil.disk_usage(repo).free >= 10 * 1024**3, "Insufficient free space for controlled SDK build")
    write_bytes(output / "workspace-review.json", raw_review)
    before = api.source_guard(repo, overlay); api.write_guard(output / "source-before.json", before)
    git_commands = _git(output, "before", repo, env, safe, review)
    sdk = Path(args.android_sdk).resolve(); java = Path(args.java_home).resolve(); bt = sdk / "build-tools" / args.build_tools
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+)?", args.build_tools), "Explicit build tools version")
    tools = {"node": _tool(api, args.node), "java": _tool(api, java / "bin/java"), "javaModules": _tool(api, java / "lib/modules", 512 * 1024 * 1024),
        "gradleWrapper": _tool(api, repo / "gradlew"), "gradleEntry": _tool(api, repo / "scripts/mobile/gradle.mjs"),
        "androidJar": _tool(api, sdk / "platforms/android-37.0/android.jar"), "androidPlatform": _tool(api, sdk / "platforms/android-37.0/source.properties"),
        "buildToolsSource": _tool(api, bt / "source.properties"), "aapt2": _tool(api, bt / "aapt2"), "adb": _tool(api, sdk / "platform-tools/adb"),
        "apksigner": _tool(api, bt / "apksigner"), "apksignerJar": _tool(api, bt / "lib/apksigner.jar"),
        "captureTool": _tool(api, __file__), "sourceTool": _tool(api, api.__file__)}
    local = repo / ".local/mobile-toolchain.json"; local_pin = api.digest(local, 131072) if local.exists() else None
    metadata = {"schema": "android-sdk-tools-v1", "profile": args.profile, "repo": str(repo), "tools": tools, "environment": safe,
        "localToolchainSha256": local_pin, "buildTools": args.build_tools, "compileSdk": 37}
    write_json(output / "tools-before.json", metadata)
    commands = {"node-version": [tools["node"]["path"], "--version"], "java-version": [tools["java"]["path"], "-version"],
        "gradle-version": [tools["gradleWrapper"]["path"], "--no-daemon", "--version"], "adb-version": [tools["adb"]["path"], "version"]}
    for name, argv in commands.items():
        run_process(output, name, argv, repo, env, safe)
    commands["build"] = [tools["node"]["path"], "scripts/mobile/gradle.mjs", "-PagentHubAcceptance=true", "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--console=plain"] + PROFILES[args.profile]["tasks"]
    build = run_process(output, "build", commands["build"], repo, env, safe, timeout=3600, child_umask=0o022)
    require(b"BUILD SUCCESSFUL" in build["stdout"] + build["stderr"], "Actual Gradle successful result absent")
    after = api.source_guard(repo, overlay); require(api.same(before, after), "Build source/input inventory changed")
    api.write_guard(output / "source-after.json", after); _git(output, "after", repo, env, safe, review); _gradle_home(args.gradle_home)
    require((api.digest(local, 131072) if local.exists() else None) == local_pin, "Private effective local toolchain config changed")
    expected_local = ("sdk.dir=" + str(sdk).replace("\\", "\\\\").replace(":", "\\:") + "\n").encode()
    require(api.digest(repo / "local.properties", 131072) == sha(expected_local), "Official entry did not select admitted SDK")
    for item in tools.values():
        require(api.digest(item["path"], max(APK_CAP, item["bytes"])) == item["sha256"], "Actual SDK/compiler/capture tool changed")
    write_json(output / "tools-after.json", metadata | {"localPropertiesSha256": sha(expected_local)})
    apks = output / "apks"; apks.mkdir(mode=0o700); artifacts = {}
    for role, recipe in PROFILES[args.profile]["artifacts"].items():
        path = apks / (role + ".apk"); _copy_apk(api, repo / recipe[0], path, build["intent"]["createdWallNs"])
        commands["cert-" + role] = [tools["apksigner"]["path"], "verify", "--verbose", "--print-certs", str(path)]
        commands["manifest-" + role] = [tools["aapt2"]["path"], "dump", "badging", str(path)]
        cert = run_process(output, "cert-" + role, commands["cert-" + role], repo, env, safe)
        manifest = run_process(output, "manifest-" + role, commands["manifest-" + role], repo, env, safe)
        artifacts[role] = _apk(role, recipe, cert, manifest, path, api)
    require(len({item["certificateSha256"] for item in artifacts.values()}) == 1, "App/Test/examiner signer mismatch")
    files = {}
    for path in sorted(output.rglob("*")):
        if path.is_file():
            files[path.relative_to(output).as_posix()] = private_digest(path,APK_CAP)
    seal = {"schema": "android-sdk-capture-seal-v1", "captureId": str(uuid.uuid4()), "profile": args.profile, "repo": str(repo),
        "sourceGuardSha256": sha(encoded(before)), "reviewedStateSha256": args.reviewed_state_sha256,
        "commands": commands, "gitCommands": git_commands, "artifacts": artifacts, "files": files}
    write_json(output / "capture-seal.json", seal)
    result = verify_build_bundle(output, api, repo, args.profile, before)
    print(json.dumps({"schema": seal["schema"], "captureId": seal["captureId"], "sealSha256": result["sealSha256"], "profile": args.profile, "sourceGuardSha256": result["sourceGuardSha256"]}))
    return result


def verify_build_bundle(bundle_dir, source_api, repo, profile, expected_guard=None):
    api=source_api; output=private_directory(bundle_dir); repo=Path(repo).resolve()
    seal_raw=private_bytes(output/"capture-seal.json",512*1024); seal=parse_json(seal_raw)
    def read_sealed(name,maximum=512*1024):
        raw=private_bytes(output/name,maximum)
        require(seal["files"].get(name)=={"sha256":sha(raw),"bytes":len(raw)},"Sealed document changed")
        return parse_json(raw)
    api.exact(seal, {"schema", "captureId", "profile", "repo", "sourceGuardSha256", "reviewedStateSha256", "commands", "gitCommands", "artifacts", "files"})
    require(profile in PROFILES and seal["schema"] == "android-sdk-capture-seal-v1" and seal["profile"] == profile and seal["repo"] == str(repo), "Captured SDK profile/checkout differs")
    api.canonical_uuid(seal["captureId"]); api.sha(seal["sourceGuardSha256"])
    raw_review, review, overlay = _review(api, output / "workspace-review.json", seal["reviewedStateSha256"], profile)
    require(seal["files"].get("workspace-review.json")=={"sha256":sha(raw_review),"bytes":len(raw_review)},"Sealed reviewed-state differs")
    before=read_sealed("source-before.json"); after=read_sealed("source-after.json")
    require(api.same(before, after) and sha(encoded(before)) == seal["sourceGuardSha256"] and api.same(before, api.source_guard(repo, overlay)), "Actual build source/inventory chain differs")
    if expected_guard is not None:
        require(api.same(before, expected_guard), "Build was not from the admitted runtime sources")
    entries=list(output.rglob("*"))
    require(all(not path.is_symlink() and (path.is_file() or path.relative_to(output).as_posix()=="apks") for path in entries),"Unknown/symlink bundle entry")
    inventory = {path.relative_to(output).as_posix() for path in entries if path.is_file()}
    require(inventory == set(seal["files"]) | {"capture-seal.json"} and len(inventory) <= 256, "Captured bundle has added/missing evidence")
    for name, pin in seal["files"].items():
        require(type(name) is str and not name.startswith("/") and ".." not in name.split("/") and type(pin) is dict and set(pin) == {"sha256", "bytes"}, "Closed capture file pin")
        api.sha(pin["sha256"]); api.integer(pin["bytes"], 0, APK_CAP)
        path = output / name; private_directory(path.parent)
        require(not path.is_symlink() and path.stat().st_size == pin["bytes"], "Capture evidence identity")
        # Empty dual streams are legitimate; digest() intentionally rejects empty ordinary inputs.
        require(private_digest(path,APK_CAP)==pin,"Captured evidence bytes changed")
    tools=read_sealed("tools-before.json"); tool_after=read_sealed("tools-after.json")
    api.exact(tools, {"schema", "profile", "repo", "tools", "environment", "localToolchainSha256", "buildTools", "compileSdk"})
    require(tools["schema"] == "android-sdk-tools-v1" and tools["profile"] == profile and tools["repo"] == str(repo) and type(tools["compileSdk"]) is int and tools["compileSdk"] == 37, "Actual SDK metadata")
    require(api.same(tool_after, tools | {"localPropertiesSha256": tool_after.get("localPropertiesSha256")}), "SDK after metadata changed")
    api.sha(tool_after["localPropertiesSha256"])
    table = tools["tools"]; expected_tools = {"node", "java", "javaModules", "gradleWrapper", "gradleEntry", "androidJar", "androidPlatform", "buildToolsSource", "aapt2", "adb", "apksigner", "apksignerJar", "captureTool", "sourceTool"}
    api.exact(table, expected_tools)
    for name, item in table.items():
        api.exact(item, {"path", "sha256", "bytes"}); api.sha(item["sha256"]); api.integer(item["bytes"], 1, 512 * 1024 * 1024)
        require(Path(item["path"]).is_absolute() and api.digest(item["path"], max(APK_CAP, item["bytes"])) == item["sha256"], "Admitted actual SDK/tool fingerprint changed")
    require(Path(table["captureTool"]["path"]).resolve() == Path(__file__).resolve() and Path(table["sourceTool"]["path"]).resolve() == Path(api.__file__).resolve(), "Different capture/verifier implementation")
    safe = tools["environment"]; require(type(safe) is dict and set(safe) == {"JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "KOTLIN_NATIVE_HOME", "PATH"} and all(type(value) is str for value in safe.values()), "Closed effective SDK environment")
    require(safe["ANDROID_HOME"] == safe["ANDROID_SDK_ROOT"] and safe["KOTLIN_NATIVE_HOME"] == "" and Path(table["java"]["path"]).resolve() == (Path(safe["JAVA_HOME"]) / "bin/java").resolve() and Path(table["androidJar"]["path"]).resolve() == (Path(safe["ANDROID_HOME"]) / "platforms/android-37.0/android.jar").resolve(), "SDK/JVM environment and actual tools disagree")
    require(Path(table["gradleWrapper"]["path"]).resolve()==(repo/"gradlew").resolve() and Path(table["gradleEntry"]["path"]).resolve()==(repo/"scripts/mobile/gradle.mjs").resolve(),"Official build entry location")
    expected_local=("sdk.dir="+safe["ANDROID_HOME"].replace("\\","\\\\").replace(":","\\:")+"\n").encode()
    require(tool_after["localPropertiesSha256"]==sha(expected_local),"Effective official SDK config hash")
    fixed = {"node-version": [table["node"]["path"], "--version"], "java-version": [table["java"]["path"], "-version"], "gradle-version": [table["gradleWrapper"]["path"], "--no-daemon", "--version"], "adb-version": [table["adb"]["path"], "version"],
        "build": [table["node"]["path"], "scripts/mobile/gradle.mjs", "-PagentHubAcceptance=true", "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--console=plain"] + PROFILES[profile]["tasks"]}
    artifacts = {}
    for role, recipe in PROFILES[profile]["artifacts"].items():
        path = output / "apks" / (role + ".apk")
        fixed["cert-" + role] = [table["apksigner"]["path"], "verify", "--verbose", "--print-certs", str(path)]
        fixed["manifest-" + role] = [table["aapt2"]["path"], "dump", "badging", str(path)]
        cert = verify_process(output, "cert-" + role, fixed["cert-" + role], repo, safe, 0o022,pins=seal["files"])
        manifest = verify_process(output, "manifest-" + role, fixed["manifest-" + role], repo, safe, 0o022,pins=seal["files"])
        artifacts[role] = _apk(role, recipe, cert, manifest, path, api)
    require(api.same(artifacts, seal["artifacts"]) and len({item["certificateSha256"] for item in artifacts.values()}) == 1 and api.same(fixed, seal["commands"]), "Captured APK package/certificate/output association differs")
    captures = {name: verify_process(output, name, argv, repo, safe, 0o022,pins=seal["files"]) for name, argv in fixed.items()}
    require(all(value["intent"]["timeoutSeconds"]==(3600 if name=="build" else 60) for name,value in captures.items()),"Unexpected capture deadline")
    require(b"BUILD SUCCESSFUL" in captures["build"]["stdout"] + captures["build"]["stderr"] and captures["node-version"]["stdout"].startswith(b"v") and b"version" in captures["java-version"]["stdout"] + captures["java-version"]["stderr"] and b"Gradle " in captures["gradle-version"]["stdout"] and b"Android Debug Bridge version" in captures["adb-version"]["stdout"], "Actual compiler/SDK command results missing")
    git_fixed = {"root": ["git", "rev-parse", "--show-toplevel"], "head": ["git", "rev-parse", "HEAD"], "status": ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], "unmerged": ["git", "diff", "--name-only", "--diff-filter=U"]}
    require(api.same(git_fixed, seal["gitCommands"]), "Unexpected workspace command")
    for suffix in ("before", "after"):
        g = {name: verify_process(output, "git-" + name + "-" + suffix, argv, repo, safe, 0o022,pins=seal["files"]) for name, argv in git_fixed.items()}
        require(g["root"]["stdout"].decode().strip() == str(repo) and g["head"]["stdout"].decode().strip() == review["head"] and sha(g["status"]["stdout"]) == review["statusSha256"] and not g["unmerged"]["stdout"], "Actual reviewed workspace capture chain differs")
    return {"schema": seal["schema"], "captureId": seal["captureId"], "profile": profile, "sourceGuardSha256": seal["sourceGuardSha256"],
        "artifacts": artifacts, "tools": table, "sealSha256": sha(seal_raw)}


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("capture-build")
    for name in ("repo", "output-dir", "node", "java-home", "android-sdk", "build-tools", "gradle-home", "reviewed-state", "reviewed-state-sha256", "expected-head"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--profile", required=True, choices=sorted(PROFILES)); args = parser.parse_args()
    location = Path(__file__).with_name("android-nonempty-device-first.py")
    spec = importlib.util.spec_from_file_location("capture_sources", location); api = importlib.util.module_from_spec(spec); spec.loader.exec_module(api)
    capture_build(args, api)


if __name__ == "__main__":
    main()
