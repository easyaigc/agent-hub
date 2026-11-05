"""Native ownership boundaries only; no physical device or Codex execution."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("preflight", Path(__file__).with_name("ios-physical-native-preflight.py"))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class NativeOwnerBoundary(unittest.TestCase):
    def test_foreign_runner_remains_visible_when_no_local_xcodebuild_is_running(self):
        processes = [{"processIdentifier": 41, "executable": "file:///apps/foreign/Runner.app/CodexManageUITests-Runner"}]
        owners = preflight.native_owners(processes, [{"url": "file:///apps/foreign/Runner.app/", "bundleIdentifier": "example.foreign.uitests.xctrunner"}])
        self.assertEqual([value["pid"] for value in owners], [41])
        self.assertEqual(owners[0]["bundleIdentifier"], "example.foreign.uitests.xctrunner")

    def test_regular_flutter_runner_and_own_app_are_not_misidentified(self):
        apps = [{"url": "file:///apps/flutter/Runner.app/", "bundleIdentifier": "example.proxy"},
                {"url": "file:///apps/current/App.app/", "bundleIdentifier": preflight.APP}]
        processes = [{"processIdentifier": 42, "executable": "file:///apps/flutter/Runner.app/Runner"},
                     {"processIdentifier": 43, "executable": "file:///apps/current/App.app/App"}]
        self.assertEqual(preflight.native_owners(processes, apps), [])

    def test_generic_xctest_runner_uses_bundle_identity(self):
        processes = [{"processIdentifier": 44, "executable": "file:///apps/native/Runner.app/Runner"}]
        apps = [{"url": "file:///apps/native/Runner.app/", "bundleIdentifier": "example.native.xctrunner"}]
        self.assertTrue(preflight.native_owners(processes, apps)[0]["identityResolved"])

    def test_stale_install_path_cannot_identify_an_unknown_runner(self):
        processes = [{"processIdentifier": 45, "executable": "file:///apps/new/Runner.app/Runner"}]
        apps = [{"url": "file:///apps/old/Runner.app/", "bundleIdentifier": "example.regular"}]
        self.assertFalse(preflight.native_owners(processes, apps)[0]["identityResolved"])


class WirelessBoundary(unittest.TestCase):
    def details(self, **connection):
        return {"hardwareProperties": {"udid": "own-physical", "reality": "physical"},
                "deviceProperties": {"developerModeStatus": "enabled"},
                "connectionProperties": {"pairingState": "paired", "tunnelState": "connected",
                                         "transportType": "localNetwork", **connection}}

    def test_paired_unavailable_device_without_transport_is_a_service_block(self):
        details = self.details(tunnelState="unavailable")
        del details["connectionProperties"]["transportType"]
        status, facts = preflight.wireless_readiness(details, "own-physical")
        self.assertEqual(status, "native-preflight-blocked-wireless-services")
        self.assertIsNone(facts["transportType"])
        self.assertNotIn("locked", facts)

    def test_connected_usb_does_not_pass_the_physical_wifi_requirement(self):
        self.assertEqual(preflight.wireless_readiness(self.details(transportType="wired"), "own-physical")[0],
                         "native-preflight-blocked-wifi-transport")

    def test_same_udid_simulator_cannot_supply_physical_evidence(self):
        details = self.details()
        details["hardwareProperties"]["reality"] = "simulated"
        with self.assertRaises(ValueError):
            preflight.wireless_readiness(details, "own-physical")

    def test_wrong_identity_is_rejected_before_connection_classification(self):
        with self.assertRaises(ValueError):
            preflight.wireless_readiness(self.details(tunnelState="unavailable"), "different")

    def test_only_paired_enabled_real_wifi_tunnel_is_ready(self):
        self.assertEqual(preflight.wireless_readiness(self.details(), "own-physical")[0], "native-preflight-ready")
        self.assertEqual(preflight.wireless_readiness(self.details(pairingState="unpaired"), "own-physical")[0],
                         "native-preflight-blocked-original-pairing")
        details = self.details()
        details["deviceProperties"]["developerModeStatus"] = "disabled"
        self.assertEqual(preflight.wireless_readiness(details, "own-physical")[0],
                         "native-preflight-blocked-developer-mode")


if __name__ == "__main__":
    unittest.main()
