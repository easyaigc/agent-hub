"""Read-only CoreDevice checks before an EasyWork App-hosted XCTest run."""

import argparse
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import unquote, urlparse

APP = "com.easyai.agenthub.easyworkacceptance"


def native_owners(processes, apps):
    """Resolve installed identity; Flutter's ordinary Runner is not an XCTest."""
    owners = []
    installed = {unquote(urlparse(a["url"]).path).rstrip("/"): a["bundleIdentifier"]
                 for a in apps if a.get("url") and a.get("bundleIdentifier")}
    for process in processes:
        path = unquote(urlparse(process.get("executable", "")).path)
        bundle = next((value for root, value in installed.items()
                       if path.startswith(root + "/")), None)
        name = Path(path).name.lower()
        known = any(value in name for value in ("uitests-runner", "xctrunner", "webdriveragent"))
        unresolved_runner = name == "runner" and "/Runner.app/" in path and bundle is None
        if known or (bundle and bundle.endswith(".xctrunner")) or unresolved_runner:
            owners.append({"pid": process["processIdentifier"], "bundleIdentifier": bundle,
                           "executable": path, "identityResolved": bundle is not None})
    return owners


def wireless_readiness(details, udid):
    """Classify only reported device facts; an absent tunnel says nothing about lock state."""
    hardware = details["hardwareProperties"]
    if hardware["udid"] != udid or hardware.get("reality") != "physical":
        raise ValueError("CoreDevice returned a different or nonphysical device")
    connection = details["connectionProperties"]
    facts = {key: connection.get(key) for key in
             ("pairingState", "transportType", "tunnelState")}
    facts["developerModeStatus"] = details.get("deviceProperties", {}).get("developerModeStatus")
    if facts["pairingState"] != "paired":
        return "native-preflight-blocked-original-pairing", facts
    if facts["developerModeStatus"] != "enabled":
        return "native-preflight-blocked-developer-mode", facts
    if facts["tunnelState"] != "connected":
        return "native-preflight-blocked-wireless-services", facts
    if facts["transportType"] != "localNetwork":
        return "native-preflight-blocked-wifi-transport", facts
    return "native-preflight-ready", facts


def inspect(device, udid, output):
    output.mkdir(mode=0o700)  # Never overwrite an earlier read or test intent.
    def info(kind, *arguments):
        target = output / (kind + ".json")
        subprocess.run(["xcrun", "devicectl", "device", "info", kind,
                        "--device", device, "--quiet", "--timeout", "30",
                        "--json-output", str(target), *arguments], check=True,
                       timeout=40, stdout=subprocess.DEVNULL)
        target.chmod(0o600)
        return json.loads(target.read_bytes())["result"]

    details = info("details")
    status, connection = wireless_readiness(details, udid)
    if status != "native-preflight-ready":
        result = {"status": status, "physicalUDID": udid, "bundleIdentifier": APP,
                  "connection": connection, "newNativeRuns": 0, "newRemoteCommands": 0,
                  "newModels": 0, "codexTests": False,
                  "lockStateNotInferred": True, "noPairingOrDeviceSettingsChanged": True,
                  "noAppActivationInstallOrProcessControl": True}
        target = output / "summary.json"
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        target.chmod(0o600)
        return result
    apps = info("apps", "--include-all-apps")["apps"]
    exact = [value for value in apps if value["bundleIdentifier"] == APP]
    if len(exact) != 1 or not exact[0].get("containerAccessible"):
        raise ValueError("The exact independently identified EasyWork App is unavailable")
    processes = info("processes")["runningProcesses"]
    foreign = native_owners(processes, apps)
    result = {"status": "native-runner-present-no-test-launched" if foreign else "native-preflight-ready",
              "physicalUDID": udid, "bundleIdentifier": APP, "currentInstalledAppURL": exact[0]["url"],
              "nativeOwners": foreign, "newNativeRuns": 0, "newRemoteCommands": 0,
              "newModels": 0, "codexTests": False,
              "exclusiveOwnershipNotGuaranteed": True}
    target = output / "summary.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    target.chmod(0o600)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--udid", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    result = inspect(args.device, args.udid, args.output.resolve())
    print(json.dumps({key: result[key] for key in ["status", "newNativeRuns", "newModels", "codexTests"]}))
    return 0 if result["status"] == "native-preflight-ready" else 2


if __name__ == "__main__":
    raise SystemExit(main())
