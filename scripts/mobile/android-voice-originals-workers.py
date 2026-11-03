#!/usr/bin/env python3
"""Independent finite voice worker/JUnit; old nine/foreground worker contracts remain unchanged."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import stat
import subprocess
import sys
import time
from types import SimpleNamespace

sys.dont_write_bytecode = True
FIRST_FILE = Path(__file__).with_name("android-nonempty-device-first.py")
spec = importlib.util.spec_from_file_location("p7_first", FIRST_FILE)
first = importlib.util.module_from_spec(spec)
spec.loader.exec_module(first)
capture_spec=importlib.util.spec_from_file_location("android_sdk_capture",Path(__file__).with_name("android-sdk-capture.py"))
capture=importlib.util.module_from_spec(capture_spec); capture_spec.loader.exec_module(capture)
require = first.require
encoded = first.encoded
sha = lambda value: hashlib.sha256(value).hexdigest()
WORKER_SCHEMA = "p7-nonempty-worker-wait-v1"
OBS_SCHEMA = "p7-nonempty-independent-process-v1"
EXIT_OBS_SCHEMA = "p7-nonempty-independent-exit-v1"


def private_bytes(path, cap):
    path=Path(path); before=path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid==os.getuid() and stat.S_IMODE(before.st_mode)==0o600 and before.st_nlink==1 and 1<=before.st_size<=cap,"Private bounded raw evidence required")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    with os.fdopen(descriptor,"rb") as source:
        opened=os.fstat(source.fileno()); require(first.file_identity(opened)==first.file_identity(before),"Raw open changed")
        raw=source.read(cap+1); require(len(raw)==before.st_size and first.file_identity(os.fstat(source.fileno()))==first.file_identity(before),"Raw changed")
    require(first.file_identity(path.lstat())==first.file_identity(before),"Raw pathname changed"); return raw


def expected_first_arguments(run, bound):
    mapping={"appApkSha256":"nonemptyAppApkSha256","testApkSha256":"nonemptyTestApkSha256","caseSourceSha256":"nonemptyCaseSourceSha256","wrapperSourceSha256":"nonemptyWrapperSourceSha256","sourceGuardSha256":"nonemptySourceGuardSha256","sourceAdmissionSha256":"nonemptySourceAdmissionSha256","bootUuid":"nonemptyBootUuid","uid":"nonemptyUid"}
    return {target:str(bound[source]) for source,target in mapping.items()}|{"class":"com.agenthub.android.NativeMigrationVoiceOriginalsDeviceSessionTest#voiceOriginalsControllerProducesPortableArchives","nonemptyMode":"voice-first","nonemptyRun":run}


def checked_invocation(value, run, mode, arguments, admission_sha, adb_sha, serial_sha):
    first.exact(value,{"schema","run","mode","package","runner","arguments","admissionSha256","adbSha256","deviceSerialSha256"})
    require(first.same(value,{"schema":"p7-nonempty-one-shot-invocation-v1","run":run,"mode":mode,"package":first.PACKAGE,"runner":first.RUNNER,"arguments":arguments,"admissionSha256":admission_sha,"adbSha256":adb_sha,"deviceSerialSha256":serial_sha}),"Invocation selector/arguments/target mismatch")


def process(value, bound):
    first.exact(value, {"schema", "bootUuid", "package", "uid", "pid", "startTicks", "statSha256", "statusSha256", "cmdlineSha256", "intentSha256", "observedLive"})
    require(value["schema"] == OBS_SCHEMA and value["observedLive"] is True, "Independent live observation required")
    require(type(value["uid"]) is int and value["uid"] == bound["uid"] and value["package"] == bound["package"] and value["bootUuid"] == bound["bootUuid"], "Process target mismatch")
    first.integer(value["pid"], 1, 2147483647)
    require(type(value["startTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", value["startTicks"]), "Process start type")
    for name in ("statSha256", "statusSha256", "cmdlineSha256", "intentSha256"): first.sha(value[name])


def junit(raw, method):
    text = raw.decode("utf-8", errors="strict")
    require(len(raw) <= 2 * 1024 * 1024 and "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text and "Process crashed" not in text, "Runner failure")
    rows = []; current = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            require(sep and key not in current, "Duplicate/malformed JUnit field"); current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
            require(re.fullmatch(r"-?[0-9]+", code), "JUnit status code")
            require(set(current) <= {"id","stream","numtests","class","test","current","stack"} and current.get("class") == "com.agenthub.android.NativeMigrationVoiceOriginalsDeviceSessionTest" and current.get("test") == method and current.get("numtests") == "1" and current.get("current") == "1", "Unique named JUnit case required")
            rows.append(int(code))
            current = {}
    final_codes=[line.removeprefix("INSTRUMENTATION_CODE: ") for line in text.splitlines() if line.startswith("INSTRUMENTATION_CODE: ")]
    require(not current and rows == [1, 0] and final_codes == ["-1"] and re.search(r"OK \(1 test\)", text), "Exact one passing JUnit case required")
    return {"schema": "p7-nonempty-junit-parse-v1", "method": method, "rawSha256": sha(raw), "namedCaseCount": 1, "statuses": rows, "passed": True}


def worker_receipt(path, invocation, raw_path, junit_path, mode, repo, *, selected_method=None):
    selected_method = worker_method(mode, invocation["arguments"], selected_method)
    output=Path(path).parent; value_raw,value=first.read_document(path)
    first.exact(value,{"schema","run","mode","invocationSha256","hostWorkerPid","actualWaitReturned","returnCode","rawSha256","junitSha256","rawBytes","status","stderrSha256","stderrBytes","captureWaitSha256"})
    prefix=mode+"-runner"
    captured=capture.verify_process(output,prefix,cwd=repo,environment={},child_umask=0o077)
    require(captured["intent"]["timeoutSeconds"]==300,"Wrong instrumentation deadline")
    actual_argv=captured["intent"]["argv"]
    require(len(actual_argv)==6 and actual_argv[1]=="-s" and actual_argv[3:5]==["shell","-T"] and
        first.digest(actual_argv[0])==invocation["adbSha256"] and sha(actual_argv[2].encode())==invocation["deviceSerialSha256"],"Actual worker executable/device target join")
    native=["am","instrument","-w","-r"]
    for key,text in sorted(invocation["arguments"].items()): native += ["-e",key,text]
    native += [first.RUNNER]
    require(actual_argv[5]==shlex.join(native),"Actual native selector/arguments differ")
    require(Path(raw_path).resolve()==(output/(prefix+".stdout.bin")).resolve(),"Wrong original stdout stream")
    require(value["schema"]==WORKER_SCHEMA and value["run"]==invocation["run"] and value["mode"]==mode and value["status"]=="passed" and value["actualWaitReturned"] is True and type(value["returnCode"]) is int and value["returnCode"]==0,"Completed original worker required")
    require(type(value["hostWorkerPid"]) is int and value["hostWorkerPid"]==captured["receipt"]["childPid"] and value["invocationSha256"]==sha(encoded(invocation)) and value["captureWaitSha256"]==captured["receiptSha256"],"Original actual wait association")
    raw=captured["stdout"]; stderr=captured["stderr"]
    require(not stderr and type(value["rawBytes"]) is int and value["rawBytes"]==len(raw) and value["rawSha256"]==sha(raw) and type(value["stderrBytes"]) is int and value["stderrBytes"]==0 and value["stderrSha256"]==sha(stderr),"Original dual stream join")
    junit_raw=capture.private_bytes(junit_path,131072); parsed=capture.parse_json(junit_raw)
    require(value["junitSha256"]==sha(junit_raw) and first.same(parsed,junit(raw,selected_method)),"Exact named JUnit/raw join")
    return value,sha(value_raw)


def exit_observation(value, original):
    first.exact(value, {"schema", "bootUuid", "package", "uid", "pid", "startTicks", "kind", "statResultSha256", "directoryResultSha256", "replacementStartTicks"})
    require(value["schema"] == EXIT_OBS_SCHEMA and all(value[key] == original[key] and type(value[key]) is type(original[key]) for key in ("bootUuid", "package", "uid", "pid", "startTicks")), "Original exit tuple changed")
    require(value["kind"] in {"proc-enoent-and-directory-absent", "pid-start-replaced"}, "Exact original exit proof required")
    for name in ("statResultSha256", "directoryResultSha256"): first.sha(value[name])
    if value["kind"] == "pid-start-replaced":
        require(type(value["replacementStartTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", value["replacementStartTicks"]) and value["replacementStartTicks"] != original["startTicks"], "Replacement tuple required")
    else: require(value["replacementStartTicks"] is None, "Absent process cannot have replacement ticks")


def invocation(args, mode, arguments, admission_sha):
    return {"schema": "p7-nonempty-one-shot-invocation-v1", "run": args.run, "mode": mode, "package": first.PACKAGE,
            "runner": first.RUNNER, "arguments": arguments, "admissionSha256": admission_sha,
            "adbSha256": first.digest(args.adb), "deviceSerialSha256": sha(args.serial.encode("utf-8"))}


def exact_package_cmdline(raw, package):
    # Android can zero-pad argv[0] after replacing the zygote command line.
    # Accept only this one exact package followed exclusively by NUL bytes.
    return type(raw) is bytes and type(package) is str and raw.endswith(b"\0") and raw.rstrip(b"\0")==package.encode()


device_spec=importlib.util.spec_from_file_location("voice_shared_read_device",Path(__file__).with_name("android-nonempty-device-pair.py"))
shared_device=importlib.util.module_from_spec(device_spec); device_spec.loader.exec_module(shared_device)
Device = shared_device.Device  # Shared read-only I/O; voice worker selectors and parsers stay distinct.


def worker_method(mode, arguments, selected_method=None):
    require(mode in {"first", "readonly-cold"}, "Closed voice worker phase")
    expected = "voiceOriginalsControllerProducesPortableArchives" if mode == "first" else "voiceOriginalsReadOnlyColdAfterExactOriginalExit"
    require(selected_method == expected and arguments.get("class") == "com.agenthub.android.NativeMigrationVoiceOriginalsDeviceSessionTest#" + expected,
        "Closed voice worker class/method")
    require(arguments.get("nonemptyMode") == ("voice-first" if mode == "first" else "voice-cold"), "Closed voice mode")
    return expected


def worker(args, device, mode, arguments, admission_sha, observed, *, selected_method=None):
    selected_method = worker_method(mode, arguments, selected_method)
    output=Path(args.output_dir); intent=invocation(args,mode,arguments,admission_sha)
    first.write_new(output/f"{mode}-invocation.json",intent)
    native=["am","instrument","-w","-r"]
    for key,text in sorted(arguments.items()): native += ["-e",key,text]
    native += [first.RUNNER]
    observation={}
    def tick():
        if observation:return
        value=device.document("first-intent" if mode=="first" else "cold-intent")
        if value.returncode!=0:
            require(b"No such file or directory" in value.stdout+value.stderr,"Intent read failure")
            return
        body=capture.parse_json(value.stdout)
        write_native(output/f"{mode}-intent-observed.json",value.stdout)
        live=device.live(body["pid"]); live["intentSha256"]=sha(value.stdout)
        require(type(body["pid"]) is int and body["startTicks"]==live["startTicks"],"Intent/live tuple mismatch")
        first.write_new(output/f"{mode}-process.json",live); observation.update(live)
    # The shared engine owns the exact Popen before every fallible evidence/observation operation.
    # Any UNKNOWN remains permanently failed and nonescaping until original actual wait returns.
    result=capture.run_process(output,mode+"-runner",device.prefix+["shell","-T",shlex.join(native)],
        Path(args.repo).resolve(),environment={},timeout=300,child_umask=0o077,tick=tick)
    require(observation and not result["stderr"],"Live original tuple and separate clean stderr required")
    raw=result["stdout"]; parsed=junit(raw,selected_method)
    first.write_new(output/f"{mode}-junit.json",parsed)
    receipt={"schema":WORKER_SCHEMA,"run":args.run,"mode":mode,"invocationSha256":sha(encoded(intent)),
        "hostWorkerPid":result["receipt"]["childPid"],"actualWaitReturned":True,"returnCode":result["receipt"]["returnCode"],
        "rawSha256":sha(raw),"junitSha256":first.digest(output/f"{mode}-junit.json"),"rawBytes":len(raw),"status":"passed",
        "stderrSha256":sha(result["stderr"]),"stderrBytes":len(result["stderr"]),"captureWaitSha256":first.digest(output/(mode+"-runner-wait.json"))}
    first.write_new(output/f"{mode}-worker.json",receipt)
    observed.update(observation); return observation


def write_native(path, raw):
    require(1 <= len(raw) <= 131072, "Bounded native document")
    json.loads(raw, object_pairs_hook=first.no_duplicates, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")))
    parent=path.parent.lstat()
    require(stat.S_ISDIR(parent.st_mode) and parent.st_uid==os.getuid() and stat.S_IMODE(parent.st_mode)==0o700 and path.parent.resolve()==path.parent.absolute(),"Private native output parent")
    descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(descriptor,"wb") as output:
        output.write(raw); output.flush(); os.fsync(output.fileno())
