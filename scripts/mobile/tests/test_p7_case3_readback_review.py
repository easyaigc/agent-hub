import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("case3_review", Path(__file__).parents[1] / "p7-case3-readback-review.py")
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class Case3OfflineReviewTest(unittest.TestCase):
    def test_duplicate_nested_json_and_non_json_numbers_are_rejected(self):
        for value in (b'{"x":1,"x":2}', b'{"x":{"a":1,"a":2}}', b'{"x":NaN}', b'\xff'):
            with self.subTest(value=value), self.assertRaises((ValueError, UnicodeError)):
                review.strict_json(value)

    def test_type_sensitive_full_json_comparison(self):
        self.assertTrue(review.same_json({"a": [True, 1, "whole"]}, {"a": [True, 1, "whole"]}))
        for value in ({"a": [1, 1, "whole"]}, {"a": [True, 1.0, "whole"]}, {"a": [True, 1, "whol"]}):
            self.assertFalse(review.same_json({"a": [True, 1, "whole"]}, value))

    def test_fd_reader_rejects_links_wrong_modes_over_cap_and_preserves_bytes(self):
        with tempfile.TemporaryDirectory(prefix="p7-case3-host-test-") as directory:
            root = Path(directory)
            file = root / "sample"
            file.write_bytes(b"complete\x00bytes")
            file.chmod(0o600)
            self.assertEqual(b"complete\x00bytes", review.private_bytes(file, 14))
            with self.assertRaises(ValueError):
                review.private_bytes(file, 3)
            symlink = root / "symlink"
            symlink.symlink_to(file)
            with self.assertRaises(OSError):
                review.private_bytes(symlink, 14)
            hardlink = root / "hardlink"
            os.link(file, hardlink)
            with self.assertRaises(ValueError):
                review.private_bytes(file, 14)
            hardlink.unlink()
            file.chmod(0o644)
            with self.assertRaises(ValueError):
                review.private_bytes(file, 14)
            self.assertEqual(b"complete\x00bytes", file.read_bytes())

    def test_unapproved_source_bytes_fail_before_any_archive_or_decryption(self):
        with self.assertRaisesRegex(ValueError, "fixed Source55"):
            review.source(b"not an approved tar", b"{}")


if __name__ == "__main__":
    unittest.main()
