"""Synthetic report/PNG boundary checks only. Never executes the Android producer."""
from pathlib import Path
import copy
import gzip
import hashlib
import importlib.util
import json
import os
import struct
import sys
import zlib
sys.dont_write_bytecode = True
import producer_contract as p
c = p.c
reference = Path(os.environ["P8_REFERENCE_PNG_DECODER"])
assert hashlib.sha256(reference.read_bytes()).hexdigest() == "f8f68a6d98466bee97e3acfb42dd479658dde10d432c16352eaf14b7ca2476d6"
spec = importlib.util.spec_from_file_location("p8_reviewed_png", reference)
decoder = importlib.util.module_from_spec(spec); spec.loader.exec_module(decoder)
build = {"sourceSha256": "a" * 64, "appSha256": "b" * 64, "runnerSha256": "c" * 64, "expectedSdk": 26,
         "leaseId": None, "futureTuple": None, "futureBaseline": None, "futureRunUuid": None}


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 200, 200, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress((b"\0" + bytes([16, 32, 48, 255]) * 200) * 200)) + chunk(b"IEND", b"")


def fixture(method):
    locale, dark = p.METHODS[method]
    case = {"kind": p.KIND, "method": method, "locale": locale, "dark": dark, "build": copy.deepcopy(build), "captures": [],
            "status": "diagnostic-collected-not-native-accepted", "nativeAcceptanceEligible": False, "sameSurfaceProof": False,
            "screenshotUnresolved": False, "commonTraceValidation": "separate original triplet/saved/touch/protection records; not certified here",
            "limits": copy.deepcopy(c.LIMITS), "provenance": copy.deepcopy(p.PROVENANCE)}
    images = {}
    for index, (phase, expected) in enumerate(zip(c.PHASES, c.CHOICES)):
        origin = {"screenX": 30, "screenY": 100, "windowX": 7, "windowY": 11, "width": 200, "height": 200, "displayId": 0, "displayWidth": 600, "displayHeight": 1000, "rotation": 0}
        bounds = c.projected(30, 100, 200, 200)
        popup = {"class": "androidx.compose.ui.window.PopupLayout", "lpType": 1002, "windowId": 47 + index, "targetPackage": True, "attached": True, "shown": True, "focused": True, "boundsScreen": bounds}
        options = []
        for k, (value, label) in enumerate(zip(c.VALUES, c.LABELS[locale])):
            owner, text = 10 + k * 20, 11 + k * 20
            options.append({"value": value, "ownerId": owner, "textId": text, "windowId": 47 + index, "path": [text, owner], "actualText": label,
                            "nativeClass": "android.view.View", "textClass": "android.widget.TextView", "checkable": True, "checked": value == expected,
                            "selected": False, "enabled": True, "visible": True, "clickable": True, "actionIds": [1, 16, 64],
                            "ownerBoundsScreen": c.projected(40, 110 + k * 50, 100, 45), "textBoundsScreen": c.projected(45, 120 + k * 50, 80, 25)})
        window = {"id": 47 + index, "type": 1, "layer": 3, "active": True, "focused": True, "boundsScreen": bounds,
                  "allReportedNodesTargetPackage": True, "visitedNodes": 12}
        state = {"origin": origin, "popup": popup, "options": options, "liveChoice": expected, "reportedWindows": [window],
                 "sameRetainedViewAndToken": True, "globalUniqueOwnerProved": False, "allCompositedLayersObserved": False}
        name = "composite-preference-" + phase + ".png"; images[name] = png
        case["captures"].append({"phase": phase, "expected": expected, "before": copy.deepcopy(state), "after": copy.deepcopy(state),
                                 "cropRectScreen": bounds, "windowProjectionRect": c.projected(7, 11, 200, 200),
                                 "bitmap": {"screenWidth": 600, "screenHeight": 1000, "screenAllocationBytes": 2_400_000, "cropAllocationBytes": 160_000,
                                            "config": "ARGB_8888", "distinctObjects": True, "screenRecycledBeforeEncoding": True, "cropRecycledAfterPersistence": True},
                                 "png": {"file": name, "bytes": len(png), "sha256": hashlib.sha256(png).hexdigest()},
                                 "clock": {"elapsedMs": 200, "sourceReturned": True}, "inputWitness": {"beforeTouches": 3 + index * 4, "afterTouches": 3 + index * 4, "dispatchedInputs": 0},
                                 "counters": {"stateIndex": index + 1, "caseProcessedPixels": 640000 * (index + 1), "caseCropPixels": 40000 * (index + 1), "casePngBytes": len(png) * (index + 1)}})
    return case, images


cases = []


def run(name, change=lambda case, images: None, accepted=False, method=next(iter(p.METHODS))):
    case, images = fixture(method); change(case, images)
    try:
        result = p.validate_report(case, images, build, decoder.validate_png)
        actual, reason = True, None
        assert result["nativeAcceptanceEligible"] is False and result["sameSurfaceProof"] is False and result["actualProducerExecutionVerified"] is False
    except ValueError as failure:
        actual, reason = False, str(failure)
    assert actual is accepted, (name, actual, reason)
    cases.append({"name": name, "structureAccepted": actual, "nativePassed": False, "reason": reason})


for method in p.METHODS:
    run("bounded synthetic V2 " + method, accepted=True, method=method)
for key in ("nativeAcceptanceEligible", "sameSurfaceProof", "screenshotUnresolved"):
    run("false success/unknown rejected " + key, lambda q, i, k=key: q.update({k: True}))
run("legacy same-Surface kind rejected", lambda q, i: q.update(kind="actual-owned-popup-PixelCopy"))
run("legacy JUnit method cannot count diagnostic as pass", lambda q, i: q.update(method="chineseLightPreferenceOptionsStayNativeExclusiveAndPersist"))
run("incomplete status", lambda q, i: q.update(status="diagnostic-incomplete"))
run("changed external build hash", lambda q, i: q["build"].update(appSha256="f" * 64))
run("SDK37 rejected", lambda q, i: q["build"].update(expectedSdk=37))
run("float SDK is not a pinned integer", lambda q, i: q["build"].update(expectedSdk=26.0))
run("invented global proof", lambda q, i: q["captures"][0]["before"].update(globalUniqueOwnerProved=True))
run("invented compositor coverage", lambda q, i: q["captures"][0]["before"].update(allCompositedLayersObserved=True))
run("lost View/token episode", lambda q, i: q["captures"][0]["after"].update(sameRetainedViewAndToken=False))
for side in ("before", "after"):
    run("unapproved reported content " + side, lambda q, i, s=side: q["captures"][0][s]["reportedWindows"][0].update(allReportedNodesTargetPackage=False))
    run("wrong Popup window " + side, lambda q, i, s=side: q["captures"][0][s]["reportedWindows"][0].update(id=999))
    run("empty window list " + side, lambda q, i, s=side: q["captures"][0][s].update(reportedWindows=[]))
    run("AX type is not LayoutParams " + side, lambda q, i, s=side: q["captures"][0][s]["popup"].update(lpType=1))
    run("wrong native origin " + side, lambda q, i, s=side: q["captures"][0][s]["origin"].update(windowX=8))
    run("wrong actual choice " + side, lambda q, i, s=side: q["captures"][0][s]["options"][0].update(checked=False))
    run("wrong label " + side, lambda q, i, s=side: q["captures"][0][s]["options"][0].update(actualText="foreign"))
run("window traversal cap", lambda q, i: q["captures"][0]["before"]["reportedWindows"][0].update(visitedNodes=2049))


def upper_window(case, images):
    for side in ("before", "after"):
        windows = case["captures"][0][side]["reportedWindows"]
        other = copy.deepcopy(windows[0]); other.update(id=999, layer=4, active=False, focused=False); windows.insert(0, other)


run("reported upper overlap", upper_window)
run("screen crop shift", lambda q, i: q["captures"][0].update(cropRectScreen=c.projected(31, 100, 200, 200)))
run("window crop shift", lambda q, i: q["captures"][0].update(windowProjectionRect=c.projected(8, 11, 200, 200)))
run("changed input count", lambda q, i: q["captures"][0]["inputWitness"].update(afterTouches=4, dispatchedInputs=1))
run("wrong PNG bytes", lambda q, i: q["captures"][0]["png"].update(bytes=1))
run("wrong PNG hash", lambda q, i: q["captures"][0]["png"].update(sha256="f" * 64))
run("full-screen PNG forbidden", lambda q, i: i.update(fullScreen=png))
run("PNG CRC corruption", lambda q, i: i.update({next(iter(i)): png[:-1] + b"x"}))
run("unreturned screenshot", lambda q, i: q["captures"][0]["clock"].update(sourceReturned=False))
run("capture deadline", lambda q, i: q["captures"][0]["clock"].update(elapsedMs=3001))
run("undercharged screen memory", lambda q, i: q["captures"][0]["bitmap"].update(screenAllocationBytes=1))
run("aliased bitmap", lambda q, i: q["captures"][0]["bitmap"].update(distinctObjects=False))
run("unrecycled source", lambda q, i: q["captures"][0]["bitmap"].update(screenRecycledBeforeEncoding=False))
run("unrecycled crop", lambda q, i: q["captures"][0]["bitmap"].update(cropRecycledAfterPersistence=False))
run("counter mismatch", lambda q, i: q["captures"][0]["counters"].update(caseProcessedPixels=40000))
run("missing phase", lambda q, i: q["captures"].pop())
run("out of order", lambda q, i: q["captures"].reverse())
run("increased budget", lambda q, i: q["limits"].update(maxStatePixels=4_194_304))
run("hidden allocation claim", lambda q, i: q["provenance"].update(frameworkPeakAllocation="proved"))
run("invented cancellation", lambda q, i: q["provenance"].update(callCancellation="guaranteed"))
output = Path(os.environ["P8_PRODUCER_CHECK_OUTPUT"]); output.mkdir(mode=0o700, exist_ok=False)
raw = json.dumps(cases, ensure_ascii=False, indent=2).encode(); compressed = gzip.compress(raw, mtime=0)
(output / "cases.json.gz").write_bytes(compressed); assert gzip.decompress(compressed) == raw
report = {"passed": True, "cases": len(cases), "diagnosticStructures": 2, "nativePasses": 0, "actualProducerExecuted": False,
          "sourceModuleSha256": hashlib.sha256((Path(__file__).parent / "producer_contract.py").read_bytes()).hexdigest(),
          "scope": "Synthetic candidate metadata/PNG/classification only; no actual screenshot, Bitmap, Compose, worker, SDK or Native execution"}
(output / "result.json").write_text(json.dumps(report, indent=2) + "\n"); print(json.dumps(report))
