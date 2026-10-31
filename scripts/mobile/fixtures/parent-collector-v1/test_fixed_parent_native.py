"""Offline boundary vectors only: fake Call/attestor, no SDK/device authority."""
import copy
import dataclasses
import json
import unittest

import fixed_parent_native as n
from fixed_parent_collector import Refused

U = "00000000-0000-0000-0000-000000000001"
V = "00000000-0000-0000-0000-000000000002"
H = "a" * 64
TARGET = {"observationRun": U, "packageName": n.PACKAGE, "appUid": 10001, "pid": 123, "sdk": 33}


def report(before=False, after=False):
    return {"version": 1, "kind": "p9-default-alias-presence-v1", **TARGET, "nonce": V,
            "alias": n.ALIAS, "presentBefore": before, "presentAfter": after, "consistent": before == after}


def block(selector, code):
    clazz, method = selector.split("#")
    return (f"INSTRUMENTATION_STATUS: class={clazz}\nINSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: current=1\n"
            f"INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: {code}\n")


def log(payloads=(), selector=n.OBSERVER, code=0, summary="OK (1 test)"):
    progress = "".join("INSTRUMENTATION_STATUS: p9DefaultAliasObservation=" + p +
                       "\nINSTRUMENTATION_STATUS_CODE: 2\n" for p in payloads)
    return (block(selector, 1) + progress + block(selector, code) +
            "INSTRUMENTATION_RESULT: stream=\nTime: 0.01\n\n" + summary +
            "\n\nINSTRUMENTATION_CODE: -1\n").encode()


def original_baseline():
    return {**dict.fromkeys(("families", "roots", "keys", "leaseAttemptTokenClaim"), H),
            "inventory": {"complete": True, "countsAreLowerBounds": False, "kind": "content-hash",
                          **dict.fromkeys(("familyCount", "directoryCount", "fileCount", "byteCount", "entryCount", "maximumDepth"), 0),
                          "fullEntriesSha256": H}}


class BoundaryVectors(unittest.TestCase):
    def setUp(self):
        self.saved = list(n._SEALED)
        self.attested = []
        self.vector_number = 100

    def tearDown(self):
        # Only these offline fake ports; no actual Call, flock or worker exists.
        n._SEALED[:] = self.saved

    def port(self, raw, *, selector=n.OBSERVER, **changes):
        args = n.native_arguments(selector, observation_run=U if selector == n.OBSERVER else None,
                                  lease=U if selector == n.CASES["12"] else None)
        p = n.OriginalCallPort(object(), args, H, V, raw, b"", 0, True, self.attested.append)
        return dataclasses.replace(p, **changes)

    def review(self, p, selector=n.OBSERVER):
        return n.review_original_call(p, selector, H, V, target=TARGET if selector == n.OBSERVER else None,
                                      lease=U if selector == n.CASES["12"] else None)

    def vector_uuid(self):
        # Deterministic offline input constants, never runtime UUID allocation.
        self.vector_number += 1
        return "00000000-0000-0000-0000-%012d" % self.vector_number

    def observed(self, present):
        value = report(present, present)
        value["observationRun"], value["nonce"] = self.vector_uuid(), self.vector_uuid()
        target = {**TARGET, "observationRun": value["observationRun"]}
        ident = self.vector_uuid()
        p = self.port(log([json.dumps(value)]), invocation_id=ident,
                      arguments=n.native_arguments(n.OBSERVER, observation_run=target["observationRun"]))
        return n.review_original_call(p, n.OBSERVER, H, ident, target=target)

    def case_review(self, case):
        s = n.CASES[case]
        ident = self.vector_uuid()
        p = self.port(log(selector=s), selector=s, invocation_id=ident)
        return n.review_original_call(p, s, H, ident, lease=U if case == "12" else None)

    def test_exact_presence_and_absence_are_metadata_only(self):
        for present in (False, True):
            r = self.observed(present)
            self.assertTrue(r["knownTerminal"] and r["observationAccepted"])
            self.assertIs(r["presence"], present)
            self.assertFalse(r["holdOriginalWorker"] or r["nextCaseAllowed"] or r["nativePassed"])
        self.assertEqual(len(self.attested), 4)

    def test_schema_truth_table_and_known_changed_presence(self):
        for before in (False, True):
            for after in (False, True):
                value = report(before, after)
                self.assertEqual(n.observation_payload(json.dumps(value), TARGET), value)
                p = self.port(log([json.dumps(value)], code=0 if before == after else -2,
                                  summary="OK (1 test)" if before == after else "FAILURES!!!"))
                r = self.review(p)
                self.assertTrue(r["knownTerminal"])
                self.assertEqual(r["report"], value)
                self.assertEqual(r["observationAccepted"], before == after)
                if before != after:
                    self.assertIsNone(r["presence"])
                    self.assertEqual(r["classification"], "known-observation-refused")

    def test_missing_duplicate_wrong_code_never_means_absent(self):
        raw = json.dumps(report())
        for source in (log(), log([raw, raw]), log([raw]).replace(b"STATUS_CODE: 2", b"STATUS_CODE: 0", 1)):
            r = self.review(self.port(source))
            self.assertFalse(r["observationAccepted"])
            self.assertIsNone(r["presence"])
        self.assertTrue(self.review(self.port(log()))["knownTerminal"])

    def test_closed_schema_target_types_and_duplicate_json_keys(self):
        faults = [(key, value) for key, value in (("version", True), ("version", 1.0), ("extra", 1),
                  ("kind", "other"), ("alias", "other"), ("observationRun", V), ("packageName", "other"),
                  ("appUid", True), ("appUid", 10002), ("pid", 124), ("sdk", 32), ("nonce", "bad"),
                  ("presentBefore", 0), ("consistent", False))]
        raws = []
        for key, value in faults:
            changed = report(); changed[key] = value; raws.append(json.dumps(changed))
        missing = report(); del missing["nonce"]; raws.append(json.dumps(missing))
        raws.extend(['{"version":1,' + json.dumps(report())[1:], '{"x":NaN}', '[]', 'null'])
        for raw in raws:
            with self.subTest(raw=raw):
                r = self.review(self.port(log([raw])))
                self.assertTrue(r["knownTerminal"])
                self.assertFalse(r["observationAccepted"])
                self.assertIsNone(r["presence"])

    def test_lifecycle_fail_closed(self):
        valid = log([json.dumps(report())])
        foreign = block(n.CASES["30"], 1).encode()
        malformed = [valid + b"INSTRUMENTATION_STATUS: class=foreign\n", valid + b"tail\n", valid[:-1],
                     valid.replace(block(n.OBSERVER, 1).encode(), b"", 1), foreign + valid,
                     valid.replace(b"STATUS_CODE: 2", b"STATUS_CODE: 02", 1),
                     valid.replace(b"INSTRUMENTATION_CODE: -1\n", b"INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n"),
                     valid.replace(b"INSTRUMENTATION_STATUS_CODE: 1\n", b"", 1),
                     valid.replace(b"INSTRUMENTATION_STATUS: numtests=1\n", b"", 1),
                     valid.replace(b"INSTRUMENTATION_STATUS: current=1\n", b"INSTRUMENTATION_STATUS: current=2\n", 1),
                     valid.replace(b"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n", b"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n", 1),
                     valid.replace(b"INSTRUMENTATION_RESULT: stream=\n", b"INSTRUMENTATION_STATUS: stream=unclosed\nINSTRUMENTATION_RESULT: stream=\n", 1)]
        for raw in malformed:
            with self.subTest(raw=raw):
                p = self.port(raw); r = self.review(p)
                self.assertFalse(r["knownTerminal"] or r["observationAccepted"])
                self.assertTrue(r["holdOriginalWorker"])
                self.assertTrue(any(p is sealed for sealed in n._SEALED))

    def test_non_lf_separators_cannot_frame_status_even_with_final_lf(self):
        raw = log([json.dumps(report())])
        text = raw.decode()
        for separator in ("\v", "\f", "\x1c", "\x1d", "\x1e", "\x85", "\u2028", "\u2029"):
            forged = (text.rstrip("\n").replace("\n", separator) + "\n").encode()
            r = self.review(self.port(forged))
            self.assertTrue(r["holdOriginalWorker"])
            self.assertFalse(r["knownTerminal"] or r["observationAccepted"])

    def test_unicode_field_data_keeps_selected_failure_known(self):
        for separator in ("\x85", "\u2028", "\u2029"):
            for field in ("stream", "stack", "payload", "result-stream"):
                with self.subTest(separator=repr(separator), field=field):
                    diagnostic = "legitimate Unicode " + separator + " diagnostic"
                    payload = json.dumps({"diagnostic": diagnostic}, ensure_ascii=False)
                    raw = log([payload] if field == "payload" else (), code=-2, summary="FAILURES!!!").decode()
                    if field in ("stream", "stack"):
                        raw = raw.replace("INSTRUMENTATION_STATUS_CODE: -2\n",
                                          "INSTRUMENTATION_STATUS: " + field + "=" + diagnostic +
                                          "\ncontinued " + diagnostic + "\nINSTRUMENTATION_STATUS_CODE: -2\n")
                    elif field == "result-stream":
                        raw = raw.replace("FAILURES!!!", "FAILURES!!!\n" + diagnostic)
                    r = self.review(self.port(raw.encode()))
                    self.assertTrue(r["knownTerminal"])
                    self.assertFalse(r["methodPassed"] or r["observationAccepted"] or r["holdOriginalWorker"])
                    self.assertEqual(r["allowedFollowup"], "fixed-readonly-protection-under-original-owner-only")

    def test_unicode_protocol_prefix_quotes_are_lf_framed_field_data(self):
        prefixes = ("INSTRUMENTATION_STATUS: class=foreign", "INSTRUMENTATION_STATUS_CODE: 0",
                    "INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_CODE: -1")
        for separator in ("\v", "\f", "\x1c", "\x1d", "\x1e", "\x85", "\u2028", "\u2029"):
            for prefix in prefixes:
                for field in ("stream", "stack", "payload", "result-stream"):
                    with self.subTest(separator=repr(separator), prefix=prefix, field=field):
                        quote = "quoted data " + separator + prefix
                        payload = json.dumps({"diagnostic": quote}, ensure_ascii=False)
                        raw = log([payload] if field == "payload" else (), code=-2, summary="FAILURES!!!").decode()
                        if field in ("stream", "stack"):
                            raw = raw.replace("INSTRUMENTATION_STATUS_CODE: -2\n",
                                              "INSTRUMENTATION_STATUS: " + field + "=" + quote +
                                              "\nINSTRUMENTATION_STATUS_CODE: -2\n")
                        elif field == "result-stream":
                            raw = raw.replace("FAILURES!!!", "FAILURES!!!\n" + quote)
                        r = self.review(self.port(raw.encode()))
                        self.assertTrue(r["knownTerminal"])
                        self.assertFalse(r["methodPassed"] or r["observationAccepted"] or r["holdOriginalWorker"])
                        self.assertEqual(r["allowedFollowup"], "fixed-readonly-protection-under-original-owner-only")

    def test_non_lf_joined_required_protocol_record_remains_unknown(self):
        required = ("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2",
                    "INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_CODE: -1")
        for separator in ("\v", "\f", "\x1c", "\x1d", "\x1e", "\x85", "\u2028", "\u2029"):
            for record in required:
                with self.subTest(separator=repr(separator), record=record):
                    raw = log(code=-2, summary="FAILURES!!!").decode().replace(
                        "\n" + record, separator + record, 1)
                    r = self.review(self.port(raw.encode()))
                    self.assertFalse(r["knownTerminal"] or r["observationAccepted"])
                    self.assertTrue(r["holdOriginalWorker"])

    def test_payload_outside_inflight_status_two_is_unknown(self):
        p = "INSTRUMENTATION_STATUS: p9DefaultAliasObservation=" + json.dumps(report()) + "\nINSTRUMENTATION_STATUS_CODE: 2\n"
        for raw in ((p + log().decode()).encode(), (log().decode() + p).encode()):
            r = self.review(self.port(raw))
            self.assertTrue(r["holdOriginalWorker"])
            self.assertFalse(r["knownTerminal"] or r["observationAccepted"])

    def test_fail_skip_stderr_and_summary_cannot_pass(self):
        for code in (-1, -2, -3, -4):
            r = self.review(self.port(log([json.dumps(report())], code=code, summary="FAILURES!!!")))
            self.assertTrue(r["knownTerminal"])
            self.assertFalse(r["methodPassed"] or r["observationAccepted"])
        for p in (self.port(log([json.dumps(report())]), stderr=b"warning\n"),
                  self.port(log([json.dumps(report())]), exit_code=1),
                  self.port(log([json.dumps(report())], summary="OK (2 tests)")),
                  self.port(log([json.dumps(report())], summary="Time only"))):
            r = self.review(p)
            self.assertTrue(r["knownTerminal"])
            self.assertFalse(r["methodPassed"] or r["observationAccepted"])

    def test_partial_transport_bad_binding_and_attestation_seal(self):
        valid = log([json.dumps(report())])
        def changed_guard(p):
            raise Refused("actual original tuple drift")
        for p in (self.port(valid, physical_settled=False), self.port(valid, binding_sha256="b" * 64),
                  self.port(valid, invocation_id=U), self.port(valid, arguments=("shell", "other")),
                  self.port(valid, attest=changed_guard), self.port(valid, attest=lambda p: False), self.port(valid + b"\xff\n")):
            r = self.review(p)
            self.assertTrue(r["holdOriginalWorker"])
            self.assertFalse(r["knownTerminal"] or r["observationAccepted"])
            self.assertIsNone(r["presence"])

    def test_post_parse_attestation_drift_revokes_metadata(self):
        calls = []
        def late_guard(p):
            calls.append(p)
            if len(calls) == 2:
                raise Refused("actual original source drifted after parsing")
        p = self.port(log([json.dumps(report())]), attest=late_guard)
        r = self.review(p)
        self.assertEqual(len(calls), 2)
        self.assertFalse(r["knownTerminal"] or r["observationAccepted"])
        self.assertIsNone(r["presence"])
        self.assertTrue(r["holdOriginalWorker"])
        self.assertTrue(any(sealed is p for sealed in n._SEALED))

    def test_unexpected_fault_propagates_and_retains_original(self):
        def fault(p):
            raise RuntimeError("offline injected attestor fault")
        p = self.port(log(), attest=fault)
        with self.assertRaises(RuntimeError):
            self.review(p)
        self.assertTrue(any(sealed is p for sealed in n._SEALED))

    def test_only_fixed_arguments_and_no_automatic_uuid(self):
        for selector in (n.OBSERVER, n.CASES["12"], "arbitrary#method"):
            with self.assertRaises((ValueError, TypeError)):
                n.native_arguments(selector)
        for case in ("18", "19", "30"):
            self.assertEqual(n.native_arguments(n.CASES[case])[-1], n.RUNNER)
        with self.assertRaises(Refused):
            n.native_arguments(n.CASES["30"], lease=U)
        with self.assertRaises(ValueError):
            n.native_arguments(n.OBSERVER, observation_run=U.upper().replace("000000000001", "ABCDEFABCDEF"))

    def test_first_four_explicit_init_and_codec_without_existing_key(self):
        absent, present, b = self.observed(False), self.observed(True), original_baseline()
        r = n.first_four_delta("12", self.case_review("12"), absent, present, b, b, initialization_intent_sha256=H)
        self.assertTrue(r["aliasInitialized"])
        self.assertEqual(r["state"], "new-test-installation-K1")
        for case in ("12", "18", "19", "30"):
            r = n.first_four_delta(case, self.case_review(case), present, self.observed(True), b, b)
            self.assertFalse(r["aliasInitialized"] or r["keyIdentityProven"] or r["nativePassed"] or r["nextCaseAllowed"])
        r = n.first_four_delta("30", self.case_review("30"), absent, self.observed(False), b, b)
        self.assertFalse(r["aliasInitialized"])

    def test_reused_preflight_receipt_is_not_post_observation(self):
        p, b = self.observed(True), original_baseline()
        with self.assertRaises(Refused):
            n.first_four_delta("30", self.case_review("30"), p, p, b, b)
        changed = {**self.observed(True), "report": p["report"]}
        with self.assertRaises(Refused):
            n.first_four_delta("30", self.case_review("30"), p, changed, b, b)

    def test_original_drift_or_unknown_blocks_case_gate(self):
        a, p, b = self.observed(False), self.observed(True), original_baseline()
        changed = copy.deepcopy(b); changed["roots"] = "b" * 64
        faults = [("12", self.case_review("12"), a, p, b, b, None),
                  ("18", self.case_review("18"), a, p, b, b, H),
                  ("30", self.case_review("30"), a, p, b, b, None),
                  ("30", self.case_review("30"), p, p, b, changed, None),
                  ("30", self.case_review("30"), {"observationAccepted": False}, p, b, b, None),
                  ("30", {**self.case_review("30"), "methodPassed": False}, p, p, b, b, None),
                  ("30", self.case_review("30"), {**p, "bindingSha256": "b" * 64}, p, b, b, None)]
        for case, review, before, after, ob, oa, init in faults:
            with self.assertRaises((ValueError, TypeError)):
                n.first_four_delta(case, review, before, after, ob, oa, initialization_intent_sha256=init)


if __name__ == "__main__":
    unittest.main()
