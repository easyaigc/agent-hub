import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("activation", Path(__file__).with_name("ios-physical-hosted-activation.py"))
activation = importlib.util.module_from_spec(spec); spec.loader.exec_module(activation)


class CurrentProcessIdentity(unittest.TestCase):
    def test_current_installation_is_authoritative_after_xcode_install(self):
        apps = [{"bundleIdentifier": activation.APP, "containerAccessible": True,
                 "url": "file:///private/App/NEW/Acceptance.app"}]
        current = {"processIdentifier": 42, "executable": "file:///private/App/NEW/Acceptance.app/CodexManage"}
        self.assertEqual(activation.verify_current_process(apps, [current], 42)["nativeProcessId"], 42)
        old = {**current, "executable": "file:///private/App/OLD/Acceptance.app/CodexManage"}
        with self.assertRaises(ValueError): activation.verify_current_process(apps, [old], 42)

    def test_foreign_app_extension_missing_or_duplicate_pid_is_refused(self):
        apps = [{"bundleIdentifier": activation.APP, "containerAccessible": True,
                 "url": "file:///private/App/NEW/Acceptance.app"}]
        for path in ["/private/App/OTHER/Formal.app/CodexManage",
                     "/private/App/NEW/Acceptance.app/PlugIns/Content.appex/Content"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                activation.verify_current_process(apps, [{"processIdentifier": 42, "executable": path}], 42)
        with self.assertRaises(ValueError): activation.verify_current_process(apps, [], 42)
        row = {"processIdentifier": 42, "executable": "/private/App/NEW/Acceptance.app/CodexManage"}
        with self.assertRaises(ValueError): activation.verify_current_process(apps, [row, row], 42)


if __name__ == "__main__": unittest.main()
