"""Fixed P9 Native review port. No executor, device access, lease or UUID factory.

Root keeps its original Call, common fence FD, once intent and worker. Its
in-process attestor must bind these to the exact SDK/device/source and immutable
arguments/streams; constructing this port or importing JSON is not authority.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable
import re

from fixed_parent_collector import (Refused, baseline, canonical_uuid, closed,
                                    digest, json_bytes, need, sha)

PACKAGE = "com.easyai.agenthub.android.acceptance"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
ALIAS = "agent-hub-mobile-v1"
OBSERVER = "com.agenthub.android.NativeDefaultAliasObservationTest#observesOnlyDefaultAliasPresenceWithoutInitializingStorage"
CASES = {
    "12": "com.agenthub.android.NativeChatNotificationOsProviderTest#installedScopedProviderReturnsExactReadOnlyNormalizedBytes",
    "18": "com.agenthub.android.NativeChatNotificationSoundTest#encryptedImportAndColdRoundtripKeepIdAndBytes",
    "19": "com.agenthub.android.NativeChatNotificationSoundTest#deleteResetsDefaultAndMatchingCategoriesPreservingOriginalReceipt",
    "30": "com.agenthub.android.NativeChatNotificationSoundTest#actualM4aUsesInstalledCodecWithoutPlaying",
}
PAYLOAD_KEY = "p9DefaultAliasObservation"
CAP = 262144


def native_arguments(selector, *, observation_run=None, lease=None):
    """Only the five fixed original Call suffixes; never executes them."""
    need(selector in (OBSERVER, *CASES.values()), "fixed observer or first-four selector")
    args = ("shell", "-T", "am", "instrument", "-w", "-r", "-e", "class", selector)
    if selector == OBSERVER:
        canonical_uuid(observation_run)
        need(lease is None, "observer accepts no fixture lease")
        args += ("-e", "observationRun", observation_run)
    else:
        need(observation_run is None, "case does not consume observationRun")
        if selector == CASES["12"]:
            canonical_uuid(lease)
            args += ("-e", "p9SystemLease", lease, "-e", "p9SystemPermission", "unchanged")
        else:
            need(lease is None, "sound case accepts no extra fixture arguments")
    return args + (RUNNER,)


def single_method(out, err, selector, exit_code):
    """Finite status grammar, not a substring search for OK or a payload key.

    Stream/stack continuation is allowed only inside its submitted status or
    final result field. Any foreign/duplicate/unclosed lifecycle is unknown.
    Payload validity is separate from a known selected JUnit failure.
    """
    need(selector in (OBSERVER, *CASES.values()), "fixed selector")
    need(type(out) is bytes and type(err) is bytes and 0 < len(out) <= CAP and len(err) <= CAP,
         "bounded original independent streams")
    text = out.decode("utf-8", "strict")
    err.decode("utf-8", "strict")
    need(text.endswith("\n") and "\r" not in text and "\0" not in text, "complete LF Native lines")
    # Only LF frames protocol records. Other separators, even followed by a
    # quoted instrumentation prefix, remain field data and are never split.
    clazz, method = selector.split("#")
    expected = {"class": clazz, "test": method, "id": "AndroidJUnitRunner", "current": "1", "numtests": "1"}
    block, state, last, result_stream = {}, "before", None, None
    terminal = overall = None
    payloads = []
    for line in text.split("\n"):
        field = re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z][A-Za-z0-9_]*)=(.*)", line)
        if field:
            key, value = field.groups()
            need(overall is None and result_stream is None and key not in block, "duplicate/late status field")
            need(key in (*expected, "stream", "stack", PAYLOAD_KEY), "unsupported status field")
            if key in expected:
                need(value == expected[key], "foreign or contradictory status identity")
            block[key] = value
            last = key
            continue
        status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (0|1|2|-1|-2|-3|-4)", line)
        if status:
            need(overall is None and result_stream is None, "late status code")
            code = int(status[1])
            paired = block.get("class") == clazz and block.get("test") == method
            if code == 1:
                need(state == "before" and paired and set(expected) <= block.keys() and PAYLOAD_KEY not in block, "one selected start")
                state = "started"
            elif code == 2:
                need(state == "started" and (not ({"class", "test"} & block.keys()) or paired), "in-flight selected progress only")
                if PAYLOAD_KEY in block:
                    payloads.append(block[PAYLOAD_KEY])
            else:
                need(state == "started" and paired and set(expected) <= block.keys() and PAYLOAD_KEY not in block, "one paired selected method terminal")
                state, terminal = "terminated", code
            block, last = {}, None
            continue
        if line == "INSTRUMENTATION_RESULT: stream=":
            need(state == "terminated" and not block and result_stream is None and overall is None, "one final result stream")
            result_stream, last = [], "result-stream"
            continue
        run = re.fullmatch(r"INSTRUMENTATION_CODE: (-1|0)", line)
        if run:
            need(state == "terminated" and not block and result_stream is not None and overall is None, "one final run terminal")
            overall, last = int(run[1]), None
            continue
        need(not line.startswith("INSTRUMENTATION_"), "malformed or unsupported instrumentation record")
        if overall is not None:
            need(not line, "nonempty tail after final run terminal")
        elif last == "result-stream":
            result_stream.append(line)
        elif last in ("stream", "stack"):
            block[last] += "\n" + line
        else:
            need(not line, "unowned output or unsubmitted status field")
    need(state == "terminated" and not block and terminal is not None and overall is not None,
         "complete single selected lifecycle required")
    summary = [line for line in result_stream if re.match(r"^OK(?:\s|$)", line)]
    diagnostic = bool(re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORT|Process crashed|Fatal signal|FATAL EXCEPTION|There (?:was|were) [1-9].*(?:failure|error)|(?:Failures|Errors):\s*[1-9]", text, re.IGNORECASE))
    passed = (terminal == 0 and overall == -1 and type(exit_code) is int and exit_code == 0
              and not err and not diagnostic and summary == ["OK (1 test)"])
    return {"knownTerminal": True, "methodPassed": passed, "methodCode": terminal,
            "overallCode": overall, "payloads": payloads}


def observation_payload(raw, target):
    """Exact implementation of the pinned closed report schema, no coercion."""
    closed(target, {"observationRun", "packageName", "appUid", "pid", "sdk"})
    canonical_uuid(target["observationRun"])
    need(target["packageName"] == PACKAGE, "fixed target package")
    for key, minimum in (("appUid", 10000), ("pid", 1), ("sdk", 26)):
        need(type(target[key]) is int and minimum <= target[key] <= 2147483647, "actual typed target " + key)
    need(type(raw) is str and 0 < len(raw.encode("utf-8")) <= 4096, "single bounded JSON status value")
    report = json_bytes(raw.encode("utf-8"))
    closed(report, {"version", "kind", "observationRun", "nonce", "packageName", "appUid", "pid", "sdk",
                    "alias", "presentBefore", "presentAfter", "consistent"})
    need(type(report["version"]) is int and report["version"] == 1 and
         report["kind"] == "p9-default-alias-presence-v1" and report["alias"] == ALIAS, "fixed schema/version/alias")
    canonical_uuid(report["nonce"])
    for key in target:
        need(type(report[key]) is type(target[key]) and report[key] == target[key], "exact submitted/actual target " + key)
    need(all(type(report[k]) is bool for k in ("presentBefore", "presentAfter", "consistent")), "boolean observations only")
    need(report["consistent"] == (report["presentBefore"] == report["presentAfter"]), "schema consistency truth table")
    return report


@dataclass(frozen=True)
class OriginalCallPort:
    """Root-adapted ORIGINAL in-process Call only, never a receipt JSON loader.

    attest(port) must read the original Call and existing owner: exact argv,
    source/device/UID/PID/SDK, durable raw hashes, actual EOF/exit/readers/deadline,
    common flock and immutable once/binding. No attestor implementation is
    supplied here. It must neither execute a command nor clear/rotate ownership.
    physical_settled=False includes a crossed original deadline even after a late
    local exit. A callback or these fields alone cannot authenticate Native.
    """
    original_call: object
    arguments: tuple
    binding_sha256: str
    invocation_id: str
    stdout: bytes
    stderr: bytes
    exit_code: int | None
    physical_settled: bool
    attest: Callable[["OriginalCallPort"], None]


# Unknown reviews retain the original object in this process. Root must also
# keep its actual worker, sockets/readers, original fence FD and durable pending
# intent alive; this reference cannot enforce supervisor/process lifetime.
_SEALED = []


def review_original_call(port, selector, expected_binding_sha256, expected_invocation_id,
                         *, target=None, lease=None):
    """Read-only join using the collector's strict scalar/hash/JSON helpers.

    Never settles OnceAdmission, releases a lock, retries, spawns or starts the
    next method. Even a good review awaits full original protection and Root.
    """
    need(type(port) is OriginalCallPort and port.original_call is not None, "original in-process Call port")
    result = {"knownTerminal": False, "methodPassed": False, "observationAccepted": False,
              "presence": None, "report": None, "holdOriginalWorker": True,
              "nextCaseAllowed": False, "nativePassed": False, "selector": selector,
              "bindingSha256": expected_binding_sha256, "invocationId": expected_invocation_id,
              "releaseOwnershipAllowed": False, "allowedFollowup": "none-retain-original"}
    try:
        digest(expected_binding_sha256)
        canonical_uuid(expected_invocation_id)
        need(port.binding_sha256 == expected_binding_sha256 and port.invocation_id == expected_invocation_id,
             "original collector/Call immutable binding and invocation")
        run = target["observationRun"] if selector == OBSERVER and type(target) is dict else None
        need(type(port.arguments) is tuple and port.arguments == native_arguments(selector, observation_run=run, lease=lease),
             "exact fixed original native Call suffix")
        need(callable(port.attest), "Root actual original-Call/ownership attestor required")
        need(port.attest(port) is None, "attestor must verify or raise; false is not verification")
        need(type(port.physical_settled) is bool and port.physical_settled and type(port.exit_code) is int,
             "original EOF/exit/readers/deadline unsettled; retain ownership")
        terminal = single_method(port.stdout, port.stderr, selector, port.exit_code)
        result.update(knownTerminal=True, methodPassed=terminal["methodPassed"], holdOriginalWorker=False,
                      stdoutSha256=sha(port.stdout), stderrSha256=sha(port.stderr),
                      classification="known-selected-terminal" if terminal["methodPassed"] else "known-selected-failure",
                      allowedFollowup="fixed-readonly-protection-under-original-owner-only")
        if selector == OBSERVER:
            try:
                need(len(terminal["payloads"]) == 1, "exactly one code2 observer payload")
                report = observation_payload(terminal["payloads"][0], target)
                result["report"] = report
                need(report["consistent"] is True and terminal["methodPassed"], "consistent observation and successful selected method required")
                result.update(observationAccepted=True, presence=report["presentAfter"], classification="fixed-alias-metadata-only")
            except (ValueError, TypeError, UnicodeError, RecursionError) as error:
                result.update(classification="known-observation-refused", reason=str(error))
        else:
            need(not terminal["payloads"] and target is None, "no observer status/target in first-four method")
        # Recheck actual original handles and bytes after the entire review.
        need(port.attest(port) is None, "attestor must verify or raise; false is not verification")
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError) as error:
        result.update(knownTerminal=False, methodPassed=False, observationAccepted=False, presence=None,
                      report=None, holdOriginalWorker=True, allowedFollowup="none-retain-original",
                      classification="unknown-retain-original", reason=str(error))
    except BaseException:
        result["holdOriginalWorker"] = True
        raise
    finally:
        # Unexpected failures/interruptions also retain the original; propagate
        # them rather than converting a programming fault into alias absence.
        if result["holdOriginalWorker"] and not any(p is port for p in _SEALED):
            _SEALED.append(port)
    return result


def first_four_delta(case, case_review, before_observation, after_observation, originals_before, originals_after,
                     *, initialization_intent_sha256=None):
    """Structural policy gate, not authentication or an execution/cleanup permit.

    originals_* are Root's full immutable B0/O0 projection, including original
    paths/bytes/known alias metadata, using the existing collector baseline.
    New exact test-owned paths are inventoried separately, never subtracted by
    a prefix. Hash equality alone cannot prove provenance/completeness.
    """
    need(case in CASES, "first-four case only")
    for original in (originals_before, originals_after):
        baseline(original)
    need(originals_before == originals_after, "complete B0/O0 original projection changed")
    for observed in (before_observation, after_observation):
        need(type(observed) is dict and observed.get("observationAccepted") is True and
             type(observed.get("presence")) is bool, "completed authenticated-by-Root observation required; missing is not false")
    need(type(case_review) is dict and case_review.get("selector") == CASES[case] and
         case_review.get("knownTerminal") is True and case_review.get("methodPassed") is True,
         "known failure/unknown cannot advance first-four case review")
    binding = digest(case_review.get("bindingSha256"))
    need(before_observation.get("bindingSha256") == after_observation.get("bindingSha256") == binding,
         "same original parent stage binding required")
    ids = [canonical_uuid(r.get("invocationId")) for r in (before_observation, case_review, after_observation)]
    need(len(set(ids)) == 3, "preflight/case/post-observer require three distinct original Calls")
    need(before_observation["report"]["observationRun"] != after_observation["report"]["observationRun"] and
         before_observation["report"]["nonce"] != after_observation["report"]["nonce"], "reused observer run/nonce is not a post observation")
    for key in ("packageName", "appUid", "sdk"):
        need(before_observation["report"][key] == after_observation["report"][key], "observer target drift")
    before, after = before_observation["presence"], after_observation["presence"]
    initialized = case == "12" and not before and after
    if initialized:
        digest(initialization_intent_sha256)
    else:
        need(initialization_intent_sha256 is None, "initialization exception only for measured12 absent-to-present")
        need(before == after, "unapproved alias presence transition")
    if case in ("12", "18", "19"):
        need(after and (case == "12" or before), "12 initializes once;18/19 use that retained installation resource")
    # 30 codec decoding permits absent->absent and present->present: no old-key gate.
    return {"case": case, "originalProjectionEqual": True, "aliasInitialized": initialized,
            "initializationIntentSha256": initialization_intent_sha256,
            "state": "new-test-installation-K1" if initialized else "presence-endpoints-unchanged",
            "keyIdentityProven": False, "nativePassed": False, "nextCaseAllowed": False}
