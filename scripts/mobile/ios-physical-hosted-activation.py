"""Activate only an already-running, exactly identified acceptance App process."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import unquote, urlparse

APP = "com.easyai.agenthub.easyworkacceptance"


def verify_current_process(apps, processes, pid):
    exact = [row for row in apps if row.get("bundleIdentifier") == APP]
    running = [row for row in processes if row.get("processIdentifier") == pid]
    if len(exact) != 1 or not exact[0].get("containerAccessible") or len(running) != 1:
        raise ValueError("The exact installed acceptance App and native PID are required")
    root = Path(unquote(urlparse(exact[0]["url"]).path))
    executable = Path(unquote(urlparse(running[0]["executable"]).path))
    if not root.is_absolute() or executable.parent != root:
        raise ValueError("The recorded PID is not the current acceptance App main process")
    return {"bundleIdentifier": APP, "nativeProcessId": pid,
            "currentInstalledAppURL": exact[0]["url"], "executable": str(executable)}


def activate(device, udid, pid, output):
    output.mkdir(mode=0o700)
    def info(kind, *args):
        path = output / (kind + ".json")
        subprocess.run(["xcrun", "devicectl", "device", "info", kind,
                        "--device", device, "--quiet", "--timeout", "30",
                        "--json-output", str(path), *args], check=True, timeout=40)
        path.chmod(0o600)
        return json.loads(path.read_bytes())["result"]
    details = info("details")
    connection = details["connectionProperties"]
    if (details["hardwareProperties"]["udid"] != udid or
            connection["pairingState"] != "paired" or
            connection["transportType"] != "localNetwork" or
            connection["tunnelState"] != "connected"):
        raise ValueError("The original paired Wi-Fi iPhone must still be connected")
    identity = verify_current_process(info("apps", "--include-all-apps")["apps"],
                                      info("processes")["runningProcesses"], pid)
    intent = output / "exact-existing-process-intent.json"
    intent.write_text(json.dumps(identity, indent=2) + "\n"); intent.chmod(0o600)
    receipt = output / "activation.json"
    # No terminate-existing, install, arguments, or new process fallback.
    subprocess.run(["xcrun", "devicectl", "device", "process", "launch",
                    "--device", device, "--timeout", "30",
                    "--json-output", str(receipt), APP], check=True, timeout=40,
                   stdout=subprocess.DEVNULL)
    receipt.chmod(0o600)
    if json.loads(receipt.read_bytes())["result"]["process"]["processIdentifier"] != pid:
        raise ValueError("Activation did not return the verified existing native PID")
    result = {"status": "activated-exact-existing-native-process", "bundleIdentifier": APP,
              "nativeProcessId": pid, "physicalUDID": udid,
              "terminatedOrInstalled": False, "newModelInputs": 0,
              "codexFunctionalTests": False}
    path = output / "summary.json"
    path.write_text(json.dumps(result, indent=2) + "\n"); path.chmod(0o600)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--udid", required=True)
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(); os.umask(0o077)
    print(json.dumps(activate(args.device, args.udid, args.pid, args.output.resolve())))
