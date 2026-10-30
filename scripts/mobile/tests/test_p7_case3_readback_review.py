import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("case3_review", Path(__file__).parents[1] / "p7-case3-readback-review.py")
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class Case3OfflineReviewTest(unittest.TestCase):
    @staticmethod
    def persisted_original():
        return dict(version=1, id="synthetic-recording", scope={"version": 1, "hostId": "synthetic-host"},
                    certificate=None, draftKey="draft-key", draftId="draft-id", threadId="thread-id",
                    originalText="complete synthetic original", createdAt=1,
                    manifest={"sizeBytes": 3244, "sha256": "a" * 64}, prepareAttempted=True,
                    audioAttempted=False, audioStorage="binary-chunks-v1", phase="outcome-unknown",
                    expiresAt="2030-01-01T00:00:00Z")

    def test_audit_accepts_complete_persisted_row_and_rejects_projected_dto(self):
        row = self.persisted_original()
        raw = json.dumps(row, separators=(",", ":"))
        review.verify_original_audit(raw, raw, review.sha(raw.encode()))
        dto = json.dumps({k: v for k, v in row.items() if k not in {"phase", "audioStorage", "expiresAt"}})
        with self.assertRaisesRegex(ValueError, "complete original audit"):
            review.verify_original_audit(dto, raw, review.sha(dto.encode()))

    def test_audit_rejects_missing_extra_changed_or_retyped_persisted_fields(self):
        row = self.persisted_original()
        raw = json.dumps(row, separators=(",", ":"))
        variants = [("missing " + key, {k: v for k, v in row.items() if k != key}) for key in row]
        variants += [("extra", dict(row, unexpected="not ignored"))]
        for key, value in (("phase", "completed"), ("phase", 1), ("expiresAt", "2031-01-01T00:00:00Z"),
                           ("expiresAt", None), ("audioStorage", "inline"), ("audioStorage", True),
                           ("createdAt", 1.0), ("prepareAttempted", 1), ("audioAttempted", 0),
                           ("manifest", {"sizeBytes": 3244.0, "sha256": "a" * 64})):
            variants.append(("changed/retyped " + key, dict(row, **{key: value})))
        for name, changed in variants:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "complete original audit"):
                audit = json.dumps(changed, separators=(",", ":"))
                # Even a recomputed attacker-controlled marker cannot replace the frozen row.
                review.verify_original_audit(audit, raw, review.sha(audit.encode()))

    def test_audit_requires_original_bytes_and_marker_hash_not_just_equal_json(self):
        row = self.persisted_original()
        raw = json.dumps(row, separators=(",", ":"))
        reformatted = json.dumps(row, sort_keys=True, indent=2)
        self.assertTrue(review.same_json(review.strict_json(raw.encode()), review.strict_json(reformatted.encode())))
        with self.assertRaisesRegex(ValueError, "complete original audit"):
            review.verify_original_audit(reformatted, raw, review.sha(reformatted.encode()))
        with self.assertRaisesRegex(ValueError, "complete original audit"):
            review.verify_original_audit(raw, raw, "0" * 64)

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
