from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "update-release"))
from release_identity import resolve


class ReleaseIdentityTests(unittest.TestCase):
    def test_stable_tag_uses_checked_in_android_code(self):
        self.assertEqual(resolve(ROOT, "push", "refs/tags/app-v0.1.0"),
                         {"app_version": "0.1.0", "android_version_code": "1"})

    def test_manual_main_remains_supported(self):
        self.assertEqual(resolve(ROOT, "workflow_dispatch", "refs/heads/main", "0.1.0", "1"),
                         resolve(ROOT, "push", "refs/tags/app-v0.1.0"))

    def test_unsafe_or_mismatching_tags_are_rejected(self):
        for ref in ("refs/heads/main", "refs/tags/v0.1.0", "refs/tags/app-v1.2.3",
                    "refs/tags/app-v0.1.0-rc.1", "refs/tags/app-v0.1.0;echo bad",
                    "refs/tags/app-v0.01.0", "refs/tags/app-v0.1.0\n"):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                resolve(ROOT, "push", ref)
        with self.assertRaises(ValueError):
            resolve(ROOT, "workflow_dispatch", "refs/heads/unreviewed", "0.1.0", "1")
