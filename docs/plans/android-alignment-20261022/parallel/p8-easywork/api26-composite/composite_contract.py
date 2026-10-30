"""Integrity checks for diagnostic system-composite candidates; never a native PASS."""
import hashlib
import json

NATIVE_BASE = "2d7e90b94a1fe0edd294330dc0e20a5cd57fcaa108c34866c942cf28d5c1c5eb"
KIND = "uiautomation-display-composite-popup-candidate-v1"
PHASES = ("known-initial-automatic", "after-click-and-form-reopen-lan-only", "after-click-and-form-reopen-relay-only", "after-click-and-form-reopen-automatic")
CHOICES = ("automatic", "lan-only", "relay-only", "automatic")
VALUES = ("automatic", "lan-only", "relay-only")
METHODS = {"chineseLightPreferenceOptionsStayNativeExclusiveAndPersist": ("zh-Hans", False), "englishDarkPreferenceOptionsStayNativeExclusiveAndPersist": ("en", True)}
LABELS = {"zh-Hans": ("自动", "仅直连", "仅中继"), "en": ("Automatic", "Direct only", "Relay only")}
LIMITS = {"maxStatePixels": 1_048_576, "maxCaseProcessedPixels": 4_194_304, "maxStatePngBytes": 2_097_152, "maxCasePngBytes": 8_388_608, "maxLiveArgbBytes": 4_194_304, "maxArgbPlusPngBytes": 6_291_456, "captureBudgetMs": 3000, "originalTripletBudgetMs": 5000}
PROVENANCE = {"source": "UiAutomation.takeScreenshot()", "pixelScope": "default-display-system-composite-crop", "surfaceBinding": "not-proved", "frameCommitProof": "not-proved", "uniqueOwnedPopupAcquisition": "not-proved", "frameworkPeakAllocation": "not-proved", "callCancellation": "not-guaranteed"}


def require(value, message):
    if not value:
        raise ValueError(message)


def integer(value, minimum=0, maximum=2**31 - 1):
    return type(value) is int and minimum <= value <= maximum


def closed(value, fields, label):
    require(type(value) is dict and set(value) == set(fields), "closed " + label)


def strict_json(raw):
    require(type(raw) is bytes and len(raw) <= 262144, "bounded metadata bytes")
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, "duplicate JSON member")
            result[key] = value
        return result
    def invalid(value):
        raise ValueError("nonfinite JSON")
    return json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=pairs, parse_constant=invalid)


def rectangle(value):
    closed(value, ("left", "top", "right", "bottom"), "rectangle")
    require(all(integer(v) for v in value.values()) and value["right"] > value["left"] and value["bottom"] > value["top"], "positive int32 rectangle")
    return value


def projected(x, y, width, height):
    require(all(integer(v) for v in (x, y, width, height)) and x + width <= 2**31 - 1 and y + height <= 2**31 - 1, "projection overflow")
    return {"left": x, "top": y, "right": x + width, "bottom": y + height}


def inside(a, b):
    return a["left"] >= b["left"] and a["top"] >= b["top"] and a["right"] <= b["right"] and a["bottom"] <= b["bottom"]


def snapshot(value, expected, locale, width, height):
    closed(value, ("origin", "popup", "options", "liveChoice"), "snapshot")
    origin = value["origin"]
    closed(origin, ("screenX", "screenY", "windowX", "windowY", "width", "height", "displayId", "displayWidth", "displayHeight", "rotation"), "independent native origin")
    require(all(integer(v) for v in origin.values()) and origin["displayId"] == 0 and origin["rotation"] == 0, "default display and sampled rotation0")
    require((origin["width"], origin["height"]) == (width, height) and all(1 <= origin[k] <= 4096 for k in ("displayWidth", "displayHeight")), "native dimensions")
    bounds = projected(origin["screenX"], origin["screenY"], width, height)
    require(inside(bounds, projected(0, 0, origin["displayWidth"], origin["displayHeight"])), "whole Popup lies in screenshot, never clamp")
    popup = value["popup"]
    closed(popup, ("class", "lpType", "windowId", "targetPackage", "attached", "shown", "focused", "boundsScreen"), "Popup")
    require(popup["class"] == "androidx.compose.ui.window.PopupLayout" and type(popup["lpType"]) is int and popup["lpType"] == 1002 and integer(popup["windowId"]), "actual Popup class/LayoutParams/window")
    require(all(popup[k] is True for k in ("targetPackage", "attached", "shown", "focused")) and rectangle(popup["boundsScreen"]) == bounds, "actual attached focused same bounds")
    require(value["liveChoice"] == expected, "actual live scripted choice")
    options = value["options"]
    require(type(options) is list and len(options) == 3 and all(type(row) is dict for row in options) and tuple(row.get("value") for row in options) == VALUES, "three ordered options")
    ids = []
    for row, choice, label in zip(options, VALUES, LABELS[locale]):
        closed(row, ("value", "ownerId", "textId", "windowId", "path", "actualText", "nativeClass", "textClass", "checkable", "checked", "selected", "enabled", "visible", "clickable", "actionIds", "ownerBoundsScreen", "textBoundsScreen"), "option")
        require(all(integer(row[k]) for k in ("ownerId", "textId", "windowId")) and row["windowId"] == popup["windowId"], "native IDs and window")
        ids.extend((row["ownerId"], row["textId"]))
        path = row["path"]
        require(type(path) is list and 2 <= len(path) <= 16 and all(integer(v) for v in path) and len(set(path)) == len(path) and path[0] == row["textId"] and path[-1] == row["ownerId"], "actual acyclic text-owner path")
        require(row["actualText"] == label and row["nativeClass"] == "android.view.View" and row["textClass"] == "android.widget.TextView", "literal locale text and native classes")
        require(all(row[k] is True for k in ("checkable", "enabled", "visible", "clickable")) and row["checked"] is (choice == expected) and row["selected"] is False, "actual native exclusive flags")
        actions = row["actionIds"]
        require(type(actions) is list and all(integer(v, 1) for v in actions) and len(actions) == len(set(actions)) <= 64 and 16 in actions, "actual complete click actions")
        owner, text = rectangle(row["ownerBoundsScreen"]), rectangle(row["textBoundsScreen"])
        require(inside(owner, bounds) and inside(text, owner), "whole native option/text bounds")
    require(len(set(ids)) == 6 and all(not any(other["ownerId"] in row["path"][:-1] for other in options) for row in options), "unique actual IDs and no sibling owner in path")
    return origin, bounds


def validate_candidate(case, images, expected_payload, decode_png):
    closed(case, ("kind", "method", "locale", "dark", "payload", "limits", "captures"), "case")
    require(case["kind"] == KIND and type(case["method"]) is str and case["method"] in METHODS, "independent composite kind/method")
    require((case["locale"], case["dark"]) == METHODS[case["method"]] and type(case["dark"]) is bool, "literal locale/theme")
    closed(expected_payload, ("sourceSha256", "appSha256", "runnerSha256", "nativeBaseSha256", "expectedSdk"), "expected payload")
    require(all(type(expected_payload[k]) is str and len(expected_payload[k]) == 64 and set(expected_payload[k]) <= set("0123456789abcdef") for k in expected_payload if k.endswith("Sha256")), "payload hashes")
    closed(case["payload"], expected_payload, "actual payload")
    require(all(type(case["payload"][k]) is type(expected_payload[k]) for k in expected_payload), "actual payload exact scalar types")
    require(expected_payload["nativeBaseSha256"] == NATIVE_BASE and type(expected_payload["expectedSdk"]) is int and expected_payload["expectedSdk"] == 26 and case["payload"] == expected_payload, "actual caller pins; no Source62 attestation invented")
    require(case["limits"] == LIMITS and all(type(v) is int for v in case["limits"].values()), "original resource/time caps")
    files = tuple("composite-preference-" + phase + ".png" for phase in PHASES)
    require(type(images) is dict and set(images) == set(files) and type(case["captures"]) is list and len(case["captures"]) == 4, "four distinct composite PNGs")
    processed = crop_pixels = png_bytes = 0
    for index, (capture, phase, expected, name) in enumerate(zip(case["captures"], PHASES, CHOICES, files)):
        closed(capture, ("phase", "expected", "before", "after", "cropRectScreen", "windowProjectionRect", "bitmap", "png", "clock", "inputWitness", "provenance", "counters"), "capture")
        require(capture["phase"] == phase and capture["expected"] == expected and capture["provenance"] == PROVENANCE, "scripted phase and honest source limitations")
        png = images[name]
        require(type(png) is bytes and len(png) <= LIMITS["maxStatePngBytes"], "bounded actual crop PNG bytes")
        width, height = decode_png(png, LIMITS["maxStatePngBytes"], LIMITS["maxStatePixels"])
        require(integer(width, 1, 4096) and integer(height, 1, 4096) and width * height <= LIMITS["maxStatePixels"], "decoded dimensions exact bounded integers")
        origin, bounds = snapshot(capture["before"], expected, case["locale"], width, height)
        snapshot(capture["after"], expected, case["locale"], width, height)
        require(capture["before"] == capture["after"] and rectangle(capture["cropRectScreen"]) == bounds and rectangle(capture["windowProjectionRect"]) == projected(origin["windowX"], origin["windowY"], width, height), "same full state and independent screen/window projections")
        bitmap = capture["bitmap"]
        closed(bitmap, ("screenWidth", "screenHeight", "screenAllocationBytes", "cropAllocationBytes", "config", "distinctObjects", "screenRecycledBeforeEncoding", "cropRecycledAfterPersistence"), "bitmap ownership")
        require(all(integer(bitmap[k]) for k in ("screenWidth", "screenHeight", "screenAllocationBytes", "cropAllocationBytes")) and (bitmap["screenWidth"], bitmap["screenHeight"]) == (origin["displayWidth"], origin["displayHeight"]), "actual full-screen dimensions/allocation")
        pixels = bitmap["screenWidth"] * bitmap["screenHeight"] + width * height
        require(pixels <= LIMITS["maxStatePixels"] and bitmap["screenAllocationBytes"] >= bitmap["screenWidth"] * bitmap["screenHeight"] * 4 and bitmap["cropAllocationBytes"] >= width * height * 4, "full-screen plus crop pixels charged before proposal admission")
        require(bitmap["config"] == "ARGB_8888" and all(bitmap[k] is True for k in ("distinctObjects", "screenRecycledBeforeEncoding", "cropRecycledAfterPersistence")), "narrow distinct-bitmap ownership; alias unsupported")
        allocation = bitmap["screenAllocationBytes"] + bitmap["cropAllocationBytes"]
        require(allocation <= LIMITS["maxLiveArgbBytes"] and allocation + len(png) <= LIMITS["maxArgbPlusPngBytes"], "observable app bitmap/encoding budget; framework peak remains unproved")
        closed(capture["png"], ("file", "bytes", "sha256"), "PNG binding")
        require(capture["png"] == {"file": name, "bytes": len(png), "sha256": hashlib.sha256(png).hexdigest()} and type(capture["png"]["bytes"]) is int, "actual crop PNG SHA/bytes/name")
        clock = capture["clock"]
        closed(clock, ("elapsedMs", "sourceReturned"), "observed time")
        require(integer(clock["elapsedMs"], 0, 3000) and clock["sourceReturned"] is True, "known return within original3s; not a cancellation guarantee")
        witness = capture["inputWitness"]
        closed(witness, ("beforeTouches", "afterTouches", "dispatchedInputs"), "input witness")
        require(all(type(v) is int for v in witness.values()) and witness == {"beforeTouches": 3 + index * 4, "afterTouches": 3 + index * 4, "dispatchedInputs": 0}, "no scripted input during capture")
        processed += pixels
        crop_pixels += width * height
        png_bytes += len(png)
        require(processed <= LIMITS["maxCaseProcessedPixels"] and png_bytes <= LIMITS["maxCasePngBytes"], "full-screen plus crop cumulative budget")
        require(capture["counters"] == {"stateIndex": index + 1, "caseProcessedPixels": processed, "caseCropPixels": crop_pixels, "casePngBytes": png_bytes} and all(type(v) is int for v in capture["counters"].values()), "recomputed cumulative counters")
    return {"evidenceClass": KIND, "structureValid": True, "nativeAcceptanceEligible": False, "sameSurfaceProof": False, "commonTraceValidation": "not-performed-by-this-candidate-parser", "pending": ["complete bounded unique Popup acquisition", "same live View/token across episode", "actual producer and protected native/common12row11read15touch evidence", "screen IPC/hidden allocation lifetime validation", "independent image/occlusion review"]}


def require_native_acceptance(*args, **kwargs):
    raise ValueError("Composite candidate cannot pass the PixelCopy/native gate")
