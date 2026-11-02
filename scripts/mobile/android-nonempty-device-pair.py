#!/usr/bin/env python3
"""Closed capture-build and P7 paired first/cold. run-pair never builds/installs/kills App/retries."""
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
    return {target:str(bound[source]) for source,target in mapping.items()}|{"class":first.SELECTOR,"nonemptyMode":"first","nonemptyRun":run}


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
            require(set(current) <= {"id","stream","numtests","class","test","current","stack"} and current.get("class") == "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest" and current.get("test") == method and current.get("numtests") == "1" and current.get("current") == "1", "Unique named JUnit case required")
            rows.append(int(code))
            current = {}
    final_codes=[line.removeprefix("INSTRUMENTATION_CODE: ") for line in text.splitlines() if line.startswith("INSTRUMENTATION_CODE: ")]
    require(not current and rows == [1, 0] and final_codes == ["-1"] and re.search(r"OK \(1 test\)", text), "Exact one passing JUnit case required")
    return {"schema": "p7-nonempty-junit-parse-v1", "method": method, "rawSha256": sha(raw), "namedCaseCount": 1, "statuses": rows, "passed": True}


def worker_receipt(path, invocation, raw_path, junit_path, mode, repo):
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
    require(value["junitSha256"]==sha(junit_raw) and first.same(parsed,junit(raw,first.METHOD if mode=="first" else first.COLD_METHOD)),"Exact named JUnit/raw join")
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


def build_association(args, admission, guard):
    build=capture.verify_build_bundle(args.build_bundle,first,args.repo,"p7",guard)
    for role,key,path in (("app","appApkSha256",args.app_apk),("test","testApkSha256",args.test_apk)):
        require(Path(path).resolve()==Path(build["artifacts"][role]["path"]).resolve() and admission[key]==build["artifacts"][role]["sha256"],"Admitted APK is not the controlled captured output")
    return build


def sdk_observation(args,build):
    raw=capture.private_bytes(args.sdk_observation,131072); value=capture.parse_json(raw)
    first.exact(value,{"schema","adbVersion","deviceApi","buildProvenanceSha256"})
    require(value["schema"]=="p7-nonempty-sdk-observation-v1" and type(value["adbVersion"]) is str and 1<=len(value["adbVersion"])<=8192 and value["adbVersion"].startswith("Android Debug Bridge version"),"SDK version observation")
    first.integer(value["deviceApi"],26,1000)
    require(value["buildProvenanceSha256"]==build["sealSha256"] and args.sdk_version_sha256==sha(raw),"Actual captured SDK/build bundle mismatch")


def admit_cold(args, include_first=False):
    review,proof=first.verify_first(args,include_documents=True); bound=proof["binding"]
    guard, source_admission=first.admission(args)
    build=build_association(args,source_admission,guard); sdk_observation(args,build)
    first.sha(args.adb_sha256)
    require(args.adb_sha256==build["tools"]["adb"]["sha256"],"Original worker ADB differs from captured SDK")
    observed = first.read_json(args.first_observation); process(observed, bound)
    require(observed["intentSha256"] == review["firstIntentSha256"], "Observed intent bytes changed")
    require(observed["pid"] == proof["pid"] and observed["startTicks"] == proof["startTicks"], "Original independent process join")
    first_invocation = first.read_json(args.first_invocation)
    checked_invocation(first_invocation,args.run,"first",expected_first_arguments(args.run,bound),bound["sourceAdmissionSha256"],args.adb_sha256,args.device_serial_sha256)
    worker,worker_sha=worker_receipt(args.first_worker,first_invocation,args.first_raw,args.first_junit,"first",args.repo)
    exit_raw,exited=first.read_document(args.exit_observation); exit_observation(exited,observed)
    for name in ("adb_sha256", "device_serial_sha256", "sdk_version_sha256"): first.sha(getattr(args,name))
    result={"schema": first.EXIT_SCHEMA, "run": args.run, "binding": bound,
            "firstIntentSha256": review["firstIntentSha256"], "firstProofSha256": review["firstProofSha256"],
            "firstPid": observed["pid"], "firstStartTicks": observed["startTicks"], "firstWorkerReceiptSha256": worker_sha,
            "firstRawSha256": worker["rawSha256"], "firstJUnitSha256": worker["junitSha256"],
            "sourceGuardSha256": bound["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"],
            "adbSha256": args.adb_sha256, "deviceSerialSha256": args.device_serial_sha256, "sdkVersionSha256": args.sdk_version_sha256,
            "exitKind": exited["kind"], "exitObservationSha256": sha(exit_raw)}
    return (result,proof) if include_first else result


def cold_arguments(first_arguments, admission):
    raw = encoded(admission); require(len(raw) <= 8192, "Cold admission argument bound")
    return first_arguments | {"class": first.COLD_SELECTOR, "nonemptyMode": "readonly-cold",
        "nonemptyFirstIntentSha256": admission["firstIntentSha256"], "nonemptyFirstProofSha256": admission["firstProofSha256"],
        "nonemptyExitAdmissionSha256": sha(raw), "nonemptyExitAdmissionJson": raw.decode("utf-8")}


def verify_pair(args):
    expected,original=admit_cold(args,include_first=True)
    admission_raw,admission_value=first.read_document(args.cold_admission)
    require(first.same(admission_value,expected) and admission_raw==encoded(expected),"Cold admission mismatch")
    intent_raw,intent=first.read_document(args.cold_intent); proof_raw,proof=first.read_document(args.cold_proof)
    common = {"schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor", "firstIntentSha256", "firstProofSha256", "exitAdmissionSha256", "firstPid", "firstStartTicks"}
    first.exact(intent, common)
    first.exact(proof, common | {"clearFramingSha256", "sealedArchiveSha256", "generation", "draftKey", "draftTextSha256", "activeTextSha256", "activeBinarySha256", "protectedDataSha256", "fixtureAliasesSha256", "readerCloseReturns", "protectedDataUnchanged", "fixtureAliasesUnchanged"})
    require(all(first.same(intent[key], proof[key]) for key in common), "Cold intent/proof changed")
    require(proof["schema"] == first.COLD_SCHEMA and proof["run"] == args.run and first.same(proof["binding"], original["binding"]) and first.same(proof["anchor"], original["anchor"]) and proof["selectedMethod"] == first.COLD_METHOD, "Wrong cold target")
    for name in ("firstIntentSha256", "firstProofSha256"): require(proof[name] == expected[name], "Original document mismatch")
    require(proof["exitAdmissionSha256"] == sha(admission_raw) and type(proof["firstPid"]) is int and proof["firstPid"] == original["pid"] and proof["firstStartTicks"] == original["startTicks"], "Original cold tuple mismatch")
    observed = first.read_json(args.cold_observation); process(observed, original["binding"])
    require(observed["intentSha256"] == sha(intent_raw), "Observed cold intent bytes changed")
    require(type(proof["pid"]) is int and proof["pid"] == observed["pid"] and proof["startTicks"] == observed["startTicks"] and proof["pid"] != original["pid"] and proof["startTicks"] != original["startTicks"], "Fresh independently live cold PID/start required")
    for cold, prior in {"clearFramingSha256": original["receipt"]["sha256"], "sealedArchiveSha256": original["archiveSha256"], "generation": original["generation"], "draftKey": original["draftKey"], "draftTextSha256": original["draftTextSha256"], "activeTextSha256": original["activeTextSha256"], "activeBinarySha256": original["activeBinarySha256"]}.items():
        require(proof[cold] == prior and type(proof[cold]) is type(prior), "Cold data pin changed")
    require(type(proof["readerCloseReturns"]) is int and proof["readerCloseReturns"] == 2 and proof["protectedDataUnchanged"] is True and proof["fixtureAliasesUnchanged"] is True, "Readonly/close gate")
    for name in ("protectedDataSha256", "fixtureAliasesSha256"): first.sha(proof[name])
    cold_invocation=first.read_json(args.cold_invocation)
    checked_invocation(cold_invocation,args.run,"readonly-cold",cold_arguments(expected_first_arguments(args.run,original["binding"]),expected),sha(admission_raw),args.adb_sha256,args.device_serial_sha256)
    cold_worker,cold_worker_sha=worker_receipt(args.cold_worker,cold_invocation,args.cold_raw,args.cold_junit,"readonly-cold",args.repo)
    final_exit = first.read_json(args.final_exit_observation); exit_observation(final_exit, first.read_json(args.first_observation))
    return {"schema": "p7-nonempty-paired-host-review-v1", "run": args.run, "firstProofSha256": expected["firstProofSha256"],
            "coldProofSha256": sha(proof_raw), "exitAdmissionSha256": sha(admission_raw),
            "firstWorkerReceiptSha256": expected["firstWorkerReceiptSha256"], "coldWorkerReceiptSha256": cold_worker_sha,
            "sourceGuardSha256": original["binding"]["sourceGuardSha256"], "pairedReadonlyColdAccepted": True,
            "protectedDataScope": ["run/no_backup", "run/cache", "run/files", "run/first-intent.json", "run/first-proof.json"],
            "newControlFiles": ["run/cold-intent.json", "run/cold-proof.json"], "keystoreInternalDiskUnchangedClaimed": False,
            "fdCountClaimed": False, "controllerStrictGraphClaimed": False, "physicalDeviceClaimed": False}


def exact_package_cmdline(raw, package):
    # Android can zero-pad argv[0] after replacing the zygote command line.
    # Accept only this one exact package followed exclusively by NUL bytes.
    return type(raw) is bytes and type(package) is str and raw.endswith(b"\0") and raw.rstrip(b"\0")==package.encode()


class Device:
    def __init__(self, args):
        self.args = args
        require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}",args.serial), "Explicit single device selector required")
        self.prefix = [str(Path(args.adb).resolve()), "-s", args.serial]
    def call(self, argv, cap=131072):
        # This role is only a fixed read helper. Its exact own client may be stopped, never instrumentation.
        child=None; selected=None; values={"stdout":bytearray(),"stderr":bytearray()}; primary=None
        try:
            child=subprocess.Popen(self.prefix+argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            selected=selectors.DefaultSelector()
            selected.register(child.stdout,selectors.EVENT_READ,"stdout"); selected.register(child.stderr,selectors.EVENT_READ,"stderr")
            for stream in (child.stdout,child.stderr):os.set_blocking(stream.fileno(),False)
            deadline=time.monotonic()+30
            while selected.get_map():
                require(time.monotonic()<deadline,"Bounded device read timed out")
                for key,_ in selected.select(0.1):
                    chunk=os.read(key.fd,min(65536,cap+1))
                    if not chunk:selected.unregister(key.fileobj);continue
                    values[key.data].extend(chunk)
                    require(sum(map(len,values.values()))<=cap,"Bounded device output")
            code=child.wait(timeout=30)
            return SimpleNamespace(returncode=code,stdout=bytes(values["stdout"]),stderr=bytes(values["stderr"]))
        except BaseException as error:
            primary=error;raise
        finally:
            try:
                if selected is not None:selected.close()
            except BaseException as error:
                if primary is None:primary=error
            finally:
                if child is not None:
                    try:
                        if child.poll() is None:child.terminate()
                    except BaseException as error:
                        if primary is None:primary=error
                    finally:
                        waited=False
                        while not waited:
                            try:child.wait(timeout=30);waited=True
                            except subprocess.TimeoutExpired:
                                try:child.kill() # Exact read helper only; this branch never owns instrumentation.
                                except BaseException as error:
                                    if primary is None:primary=error
                            except BaseException as error:
                                if primary is None:primary=error
                        for stream in (child.stdout,child.stderr):
                            try:
                                if stream is not None:stream.close()
                            except BaseException as error:
                                if primary is None:primary=error
            if primary is not None:raise primary
    def shell(self, argv, cap=131072):
        return self.call(["shell", "-T", shlex.join(argv)], cap)
    def success(self, argv, cap=131072):
        value=self.shell(argv,cap); require(value.returncode==0 and not value.stderr, "Device read failed; not exit evidence")
        return value.stdout
    def boot(self):
        value=self.success(["cat","/proc/sys/kernel/random/boot_id"]).decode().strip(); first.canonical_uuid(value); return value
    def document(self, name):
        require(name in {"first-intent","first-proof","cold-intent","cold-proof"}, "Closed device document")
        return self.shell(["run-as",first.PACKAGE,"cat",f"files/P7-nonempty-{self.args.run}/{name}.json"])
    def live(self, expected_pid=None):
        boot=self.boot()
        pids=self.success(["pidof",first.PACKAGE]).decode().split(); require(len(pids)==1 and pids[0].isdigit(),"One exact package PID required")
        pid=int(pids[0]); require(expected_pid is None or pid==expected_pid,"Observed wrong PID")
        a=self.success(["run-as",first.PACKAGE,"cat",f"/proc/{pid}/stat"])
        status=self.success(["run-as",first.PACKAGE,"cat",f"/proc/{pid}/status"])
        cmdline=self.success(["run-as",first.PACKAGE,"cat",f"/proc/{pid}/cmdline"])
        b=self.success(["run-as",first.PACKAGE,"cat",f"/proc/{pid}/stat"])
        boot_after=self.boot()
        # Preserve the exact independent read inputs before any identity assertion.
        first.write_new(Path(self.args.output_dir)/f"live-{pid}-raw.json", {
            "schema":"p7-nonempty-live-raw-v1", "pid":pid, "expectedUid":self.args.uid,
            "bootBefore":boot, "bootAfter":boot_after, "statBeforeHex":a.hex(),
            "statusHex":status.hex(), "cmdlineHex":cmdline.hex(), "statAfterHex":b.hex()}, maximum=2*1024*1024)
        ticks=lambda raw: raw.decode().rsplit(") ",1)[1].split()[19]
        require(a.startswith(f"{pid} (".encode()) and b.startswith(f"{pid} (".encode()) and ticks(a)==ticks(b) and re.fullmatch(r"[0-9]{1,24}",ticks(a)),"Racing/partial proc stat")
        uid=re.findall(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$",status,re.M)
        require(len(uid)==1 and len(set(uid[0]))==1 and int(uid[0][0])==self.args.uid,"Live process UID")
        require(exact_package_cmdline(cmdline,first.PACKAGE),"Live process cmdline")
        require(boot_after==boot==self.args.boot_uuid,"Live process boot")
        return {"schema":OBS_SCHEMA,"bootUuid":boot,"package":first.PACKAGE,"uid":self.args.uid,"pid":pid,"startTicks":ticks(a),"statSha256":sha(a),"statusSha256":sha(status),"cmdlineSha256":sha(cmdline),"observedLive":True}
    def exited(self, original):
        require(self.boot()==original["bootUuid"],"Boot changed; original exit unknown")
        pid=original["pid"]
        a=self.shell(["run-as",first.PACKAGE,"cat",f"/proc/{pid}/stat"])
        b=self.shell(["run-as",first.PACKAGE,"ls","-d",f"/proc/{pid}"])
        combined=lambda value: encoded({"returnCode":value.returncode,"stdoutSha256":sha(value.stdout),"stderrSha256":sha(value.stderr)})
        replaced=None
        if a.returncode==0:
            require(not a.stderr and a.stdout.startswith(f"{pid} (".encode()),"Partial process exit read")
            replaced=a.stdout.decode().rsplit(") ",1)[1].split()[19]
            require(re.fullmatch(r"[0-9]{1,24}",replaced),"Replacement stat")
            if replaced==original["startTicks"]: return None
            require(b.returncode==0 and b.stdout.decode().strip()==f"/proc/{pid}" and not b.stderr,"Replacement directory race")
            kind="pid-start-replaced"
        else:
            def enoent(value,path):
                raw=value.stdout+value.stderr
                return value.returncode!=0 and path.encode() in raw and b"No such file or directory" in raw and b"Permission denied" not in raw and len(raw)<=1024
            require(enoent(a,f"/proc/{pid}/stat") and enoent(b,f"/proc/{pid}"),"ENOENT plus independent proc directory absence required")
            kind="proc-enoent-and-directory-absent"
        require(self.boot()==original["bootUuid"],"Boot changed during exit observation")
        return {"schema":EXIT_OBS_SCHEMA,**{key:original[key] for key in ("bootUuid","package","uid","pid","startTicks")},"kind":kind,"statResultSha256":sha(combined(a)),"directoryResultSha256":sha(combined(b)),"replacementStartTicks":replaced}
    def installed(self):
        result={}
        for package in (first.PACKAGE, first.TEST_PACKAGE):
            lines=self.success(["pm","path",package]).decode().splitlines()
            require(len(lines)==1 and lines[0].startswith("package:/data/app/") and lines[0].endswith("/base.apk") and re.fullmatch(r"package:/[A-Za-z0-9_./+=~-]+",lines[0]),"Exactly one installed base APK")
            path=lines[0].removeprefix("package:")
            hashed=self.success(["sha256sum",path]).decode().strip().split()
            require(len(hashed)==2 and hashed[1]==path,"Installed APK path changed"); first.sha(hashed[0]); result[package]=hashed[0]
        return result


def worker(args, device, mode, arguments, admission_sha, observed):
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
    raw=result["stdout"]; parsed=junit(raw,first.METHOD if mode=="first" else first.COLD_METHOD)
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


def run_pair(args):
    os.umask(0o077)
    output=Path(args.output_dir); require(not any(output.iterdir()),"Fresh private output directory required; never reuse an attempted run")
    device=Device(args)
    require(device.boot()==args.boot_uuid,"Admitted boot differs")
    guard,admission=first.admission(args); guard_sha=sha(encoded(guard))
    build=build_association(args,admission,guard)
    require(Path(args.adb).resolve()==Path(build["tools"]["adb"]["path"]).resolve(),"Device tool differs from captured SDK")
    installed={first.PACKAGE:admission["appApkSha256"],first.TEST_PACKAGE:admission["testApkSha256"]}
    require(device.installed()==installed,"Installed App/Test differ from admitted final SDK artifacts")
    sdk=device.success(["getprop","ro.build.version.sdk"]).strip(); require(re.fullmatch(rb"[0-9]{1,3}",sdk),"Device SDK version")
    adb_version=device.call(["version"],8192)
    require(adb_version.returncode==0 and not adb_version.stderr and b"Android Debug Bridge version" in adb_version.stdout,"Actual adb version required")
    build_sha=build["sealSha256"]
    sdk_observation={"schema":"p7-nonempty-sdk-observation-v1","adbVersion":adb_version.stdout.decode("utf-8"),"deviceApi":int(sdk),"buildProvenanceSha256":build_sha}
    first.write_new(output/"sdk-observation.json",sdk_observation)
    first.write_guard(output/"source-guard.json",guard); first.write_new(output/"source-admission.json",admission)
    bound=first.binding(admission,first.digest(output/"source-admission.json"))
    mapping={"appApkSha256":"nonemptyAppApkSha256","testApkSha256":"nonemptyTestApkSha256","caseSourceSha256":"nonemptyCaseSourceSha256","wrapperSourceSha256":"nonemptyWrapperSourceSha256","sourceGuardSha256":"nonemptySourceGuardSha256","sourceAdmissionSha256":"nonemptySourceAdmissionSha256","bootUuid":"nonemptyBootUuid","uid":"nonemptyUid"}
    arguments={target:str(bound[source]) for source,target in mapping.items()}|{"class":first.SELECTOR,"nonemptyMode":"first","nonemptyRun":args.run}
    first.write_new(output/"instrumentation-arguments.json",arguments)
    initial_adb_sha=first.digest(args.adb)
    original={}; worker(args,device,"first",arguments,bound["sourceAdmissionSha256"],original)
    for name in ("first-intent","first-proof"):
        raw=device.document(name); require(raw.returncode==0,"Missing original first document")
        write_native(output/f"{name}.json",raw.stdout)
    base=vars(args)|{"source_guard":output/"source-guard.json","admission":output/"source-admission.json","first_intent":output/"first-intent.json","first_proof":output/"first-proof.json","observed_pid":original["pid"],"observed_start_ticks":original["startTicks"]}
    verify=SimpleNamespace(**base); first.write_new(output/"first-host-review.json",first.verify_first(verify))
    deadline=time.monotonic()+30; exited=None
    while time.monotonic()<deadline:
        exited=device.exited(original)
        if exited is not None: break
        time.sleep(0.1)
    require(exited is not None,"Original App did not naturally exit; cold not admitted, do not kill/retry")
    first.write_new(output/"exit-process.json",exited)
    require(first.source_guard(args.repo)==guard and device.installed()==installed and first.digest(args.adb)==initial_adb_sha,"Source/APK/tool changed before cold")
    base |= {"first_observation":output/"first-process.json","first_invocation":output/"first-invocation.json","first_worker":output/"first-worker.json","first_raw":output/"first-runner.stdout.bin","first_junit":output/"first-junit.json","exit_observation":output/"exit-process.json","adb_sha256":first.digest(args.adb),"device_serial_sha256":sha(args.serial.encode()),"sdk_version_sha256":first.digest(output/"sdk-observation.json"),"sdk_observation":output/"sdk-observation.json"}
    exit_admission=admit_cold(SimpleNamespace(**base)); first.write_new(output/"cold-admission.json",exit_admission)
    cold_args=cold_arguments(arguments,exit_admission); first.write_new(output/"cold-instrumentation-arguments.json",cold_args)
    fresh={}; worker(args,device,"readonly-cold",cold_args,first.digest(output/"cold-admission.json"),fresh)
    for name in ("cold-intent","cold-proof"):
        raw=device.document(name); require(raw.returncode==0,"Missing cold document")
        write_native(output/f"{name}.json",raw.stdout)
    final_exit=device.exited(original); require(final_exit is not None,"Original process unexpectedly live after cold")
    first.write_new(output/"final-exit-process.json",final_exit)
    require(first.source_guard(args.repo)==guard and device.installed()==installed and first.digest(args.adb)==initial_adb_sha and device.success(["getprop","ro.build.version.sdk"]).strip()==sdk and device.call(["version"],8192).stdout==adb_version.stdout and build_association(args,admission,guard)["sealSha256"]==build_sha,"Source/APK/tool/SDK changed after cold")
    base |= {"cold_admission":output/"cold-admission.json","cold_intent":output/"cold-intent.json","cold_proof":output/"cold-proof.json","cold_observation":output/"readonly-cold-process.json","cold_invocation":output/"readonly-cold-invocation.json","cold_worker":output/"readonly-cold-worker.json","cold_raw":output/"readonly-cold-runner.stdout.bin","cold_junit":output/"readonly-cold-junit.json","final_exit_observation":output/"final-exit-process.json"}
    result=verify_pair(SimpleNamespace(**base)); result["sdkBuildProvenanceSha256"]=build_sha
    first.write_new(output/"paired-host-review.json",result); print(json.dumps(result))


def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest="command",required=True)
    cap=sub.add_parser("capture-build")
    for name in ("repo","output-dir","node","java-home","android-sdk","build-tools","gradle-home","reviewed-state","reviewed-state-sha256","expected-head"):
        cap.add_argument("--"+name,required=True)
    cap.add_argument("--profile",choices=["p7"],default="p7")
    common=("repo","app-apk","test-apk","run","boot-uuid","output-dir")
    first_files=("source-guard","admission","first-intent","first-proof","observed-start-ticks","first-observation","first-invocation","first-worker","first-raw","first-junit","exit-observation","adb-sha256","device-serial-sha256","sdk-version-sha256","sdk-observation","build-bundle")
    for command in ("run-pair","admit-cold","verify-pair"):
        p=sub.add_parser(command)
        for name in common: p.add_argument("--"+name,required=True)
        p.add_argument("--uid",required=True,type=int)
        if command=="run-pair":
            for name in ("adb","serial","build-bundle"): p.add_argument("--"+name,required=True)
        else:
            for name in first_files: p.add_argument("--"+name,required=True)
            p.add_argument("--observed-pid",required=True,type=int)
            if command=="verify-pair":
                for name in ("cold-admission","cold-intent","cold-proof","cold-observation","cold-invocation","cold-worker","cold-raw","cold-junit","final-exit-observation"): p.add_argument("--"+name,required=True)
    args=parser.parse_args()
    if args.command=="capture-build": capture.capture_build(args,first); return
    first.canonical_uuid(args.run); first.canonical_uuid(args.boot_uuid); first.integer(args.uid,1,2147483647)
    if args.command=="run-pair": run_pair(args)
    elif args.command=="admit-cold":
        value=admit_cold(args); first.write_new(Path(args.output_dir)/"cold-admission.json",value)
        first.write_new(Path(args.output_dir)/"cold-instrumentation-arguments.json",cold_arguments(first.read_json(Path(args.admission).with_name("instrumentation-arguments.json")),value))
    else: first.write_new(Path(args.output_dir)/"paired-host-review.json",verify_pair(args))


if __name__=="__main__": main()
