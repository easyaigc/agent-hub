"""V2 producer report admission. Reuses V1 scalar/AX policy, never its old build pin."""
from pathlib import Path
import hashlib
import importlib.util

_source = Path(__file__).parent.parent / "api26-composite/composite_contract.py"
if hashlib.sha256(_source.read_bytes()).hexdigest() != "4268f7db3de6567849fb42c14a268f0e2f957f5458085b2e1269e58cf05ad05d":
    raise ValueError("Reviewed V1 scalar/AX policy changed")
_spec = importlib.util.spec_from_file_location("p8_v1_scalar_policy", _source)
c = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(c)

KIND = "uiautomation-display-composite-popup-diagnostic-v2"
METHODS = {"api26ChineseLightCompositePreferenceDiagnostic": ("zh-Hans", False),
           "api26EnglishDarkCompositePreferenceDiagnostic": ("en", True)}
PROVENANCE = dict(c.PROVENANCE, occlusionAndUnknownContentExclusion="not-proved-by-interactive-window-samples")


def typed_equal(actual, expected):
    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return actual.keys() == expected.keys() and all(typed_equal(actual[key], value) for key, value in expected.items())
    if type(expected) is list:
        return len(actual) == len(expected) and all(typed_equal(a, b) for a, b in zip(actual, expected))
    return actual == expected


def validate_report(case, images, expected_build, decode_png):
    c.closed(case, ("kind", "method", "locale", "dark", "build", "captures", "status", "nativeAcceptanceEligible",
                    "sameSurfaceProof", "screenshotUnresolved", "commonTraceValidation", "limits", "provenance"), "V2 report")
    c.require(case["kind"] == KIND and case["method"] in METHODS, "distinct diagnostic method/kind")
    c.require(type(case["dark"]) is bool and (case["locale"], case["dark"]) == METHODS[case["method"]], "literal locale/theme")
    c.require(type(expected_build) is dict and typed_equal(case["build"], expected_build) and
              c.integer(expected_build.get("expectedSdk")) and expected_build["expectedSdk"] == 26, "external complete build pins and SDK26")
    for key in ("sourceSha256", "appSha256", "runnerSha256"):
        value = expected_build.get(key)
        c.require(type(value) is str and len(value) == 64 and set(value) <= set("0123456789abcdef"), "external source/app/runner hashes")
    c.require(case["status"] == "diagnostic-collected-not-native-accepted" and
              all(case[key] is False for key in ("nativeAcceptanceEligible", "sameSurfaceProof", "screenshotUnresolved")), "no native PASS or unknown IPC")
    c.require(case["commonTraceValidation"] == "separate original triplet/saved/touch/protection records; not certified here", "common trace not certified by candidate")
    c.require(case["provenance"] == PROVENANCE and case["limits"] == c.LIMITS and
              all(type(value) is int for value in case["limits"].values()), "honest limitations and unchanged bounds")
    names = ["composite-preference-" + phase + ".png" for phase in c.PHASES]
    c.require(type(images) is dict and set(images) == set(names) and type(case["captures"]) is list and len(case["captures"]) == 4, "four crops only")
    processed = crop_pixels = png_bytes = 0
    for index, (capture, phase, expected, name) in enumerate(zip(case["captures"], c.PHASES, c.CHOICES, names)):
        c.closed(capture, ("phase", "expected", "before", "after", "cropRectScreen", "windowProjectionRect", "bitmap", "png", "clock", "inputWitness", "counters"), "V2 capture")
        c.require((capture["phase"], capture["expected"]) == (phase, expected), "ordered independent scripted state")
        png = images[name]; c.require(type(png) is bytes and len(png) <= 2_097_152, "bounded PNG")
        width, height = decode_png(png, 2_097_152, 1_048_576)
        for side in ("before", "after"):
            state = capture[side]
            c.closed(state, ("origin", "popup", "options", "liveChoice", "reportedWindows", "sameRetainedViewAndToken", "globalUniqueOwnerProved", "allCompositedLayersObserved"), "V2 snapshot")
            c.require(state["sameRetainedViewAndToken"] is True and state["globalUniqueOwnerProved"] is False and state["allCompositedLayersObserved"] is False, "actual local episode, no global proof")
            origin, bounds = c.snapshot({key: state[key] for key in ("origin", "popup", "options", "liveChoice")}, expected, case["locale"], width, height)
            windows = state["reportedWindows"]
            c.require(type(windows) is list and 1 <= len(windows) <= 16, "bounded reported windows")
            ids, ordering, node_count = [], [], 0
            for window in windows:
                c.closed(window, ("id", "type", "layer", "active", "focused", "boundsScreen", "allReportedNodesTargetPackage", "visitedNodes"), "reported window")
                c.require(all(c.integer(window[key]) for key in ("id", "type", "layer", "visitedNodes")) and 1 <= window["visitedNodes"] <= 2048, "actual reported window scalars")
                c.require(type(window["active"]) is bool and type(window["focused"]) is bool and window["allReportedNodesTargetPackage"] is True, "only reported owned content admitted")
                c.rectangle(window["boundsScreen"]); ids.append(window["id"]); ordering.append((-window["layer"], window["id"])); node_count += window["visitedNodes"]
            c.require(len(set(ids)) == len(ids) and ordering == sorted(ordering) and node_count <= 2048, "bounded unique ordered window witness")
            own = [window for window in windows if window["id"] == state["popup"]["windowId"]]
            c.require(len(own) == 1 and own[0]["type"] == 1 and own[0]["active"] and own[0]["focused"] and c.inside(bounds, own[0]["boundsScreen"]), "same actual AX application window covers Popup")
            for window in windows:
                if window["id"] != own[0]["id"] and window["layer"] >= own[0]["layer"]:
                    a, b = window["boundsScreen"], bounds
                    c.require(a["left"] >= b["right"] or a["right"] <= b["left"] or a["top"] >= b["bottom"] or a["bottom"] <= b["top"], "no reported upper overlap")
        before = capture["before"]; origin = before["origin"]
        c.require(before == capture["after"] and c.rectangle(capture["cropRectScreen"]) == before["popup"]["boundsScreen"] and
                  c.rectangle(capture["windowProjectionRect"]) == c.projected(origin["windowX"], origin["windowY"], width, height), "full before/after equality and independent projections")
        bitmap = capture["bitmap"]
        c.closed(bitmap, ("screenWidth", "screenHeight", "screenAllocationBytes", "cropAllocationBytes", "config", "distinctObjects", "screenRecycledBeforeEncoding", "cropRecycledAfterPersistence"), "actual bitmap ownership")
        c.require(all(c.integer(bitmap[key]) for key in ("screenWidth", "screenHeight", "screenAllocationBytes", "cropAllocationBytes")) and
                  (bitmap["screenWidth"], bitmap["screenHeight"]) == (origin["displayWidth"], origin["displayHeight"]), "actual screenshot dimensions")
        pixels = bitmap["screenWidth"] * bitmap["screenHeight"] + width * height
        c.require(pixels <= 1_048_576 and bitmap["screenAllocationBytes"] >= bitmap["screenWidth"] * bitmap["screenHeight"] * 4 and bitmap["cropAllocationBytes"] >= width * height * 4, "full screen plus crop charged")
        allocation = bitmap["screenAllocationBytes"] + bitmap["cropAllocationBytes"]
        c.require(allocation <= 4_194_304 and allocation + 3 * len(png) <= 6_291_456, "bitmap plus conservative encoding charge")
        c.require(bitmap["config"] == "ARGB_8888" and all(bitmap[key] is True for key in ("distinctObjects", "screenRecycledBeforeEncoding", "cropRecycledAfterPersistence")), "distinct/recycled source and crop")
        c.closed(capture["png"], ("file", "bytes", "sha256"), "PNG binding")
        c.require(capture["png"] == {"file": name, "bytes": len(png), "sha256": hashlib.sha256(png).hexdigest()} and type(capture["png"]["bytes"]) is int, "original crop PNG binding")
        c.closed(capture["clock"], ("elapsedMs", "sourceReturned"), "observed clock")
        c.require(c.integer(capture["clock"]["elapsedMs"], 0, 3000) and capture["clock"]["sourceReturned"] is True, "known timely return; no cancel claim")
        c.closed(capture["inputWitness"], ("beforeTouches", "afterTouches", "dispatchedInputs"), "actual input counts")
        c.require(capture["inputWitness"] == {"beforeTouches": 3 + index * 4, "afterTouches": 3 + index * 4, "dispatchedInputs": 0} and all(type(value) is int for value in capture["inputWitness"].values()), "original actual no-input witness")
        processed += pixels; crop_pixels += width * height; png_bytes += len(png)
        c.require(processed <= 4_194_304 and png_bytes <= 8_388_608, "case caps")
        c.require(capture["counters"] == {"stateIndex": index + 1, "caseProcessedPixels": processed, "caseCropPixels": crop_pixels, "casePngBytes": png_bytes} and all(type(value) is int for value in capture["counters"].values()), "actual cumulative counters")
    return {"structureValid": True, "nativeAcceptanceEligible": False, "sameSurfaceProof": False, "evidenceClass": KIND,
            "actualProducerExecutionVerified": False, "commonTraceValidation": "external native review still required"}
