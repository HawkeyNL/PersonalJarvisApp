"""A draft's downloaded bytes must equal the locally tested release exactly."""
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from client_release import build, expected_assets
from verify_published import verify


class PublishedBytesTests(unittest.TestCase):
    def fixture(self, root):
        staged, downloaded = root / "staged", root / "downloaded"
        staged.mkdir()
        downloaded.mkdir()
        for name in expected_assets("0.1.0"):
            (staged / name).write_bytes(b"fixture bytes")
            shutil.copyfile(staged / name, downloaded / name)
        manifest = root / "latest.json"
        manifest.write_text(json.dumps(build(
            staged, "0.1.0", "a" * 40, "2026-09-01T00:00:00Z", 1, "c" * 64
        )))
        (root / "latest.json.sig").write_bytes(b"separate signature verifier checks cryptography")
        for name in ("latest.json", "latest.json.sig"):
            shutil.copyfile(root / name, downloaded / name)
        return staged, downloaded, manifest

    def test_complete_exact_download_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            staged, downloaded, manifest = self.fixture(Path(temporary))
            verify(downloaded, staged, manifest, "0.1.0", "a" * 40)

    def test_incomplete_extra_tampered_or_symlink_download_is_rejected(self):
        for failure in ("missing", "extra", "tamper", "symlink", "manifest", "signature", "revision"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                staged, downloaded, manifest = self.fixture(Path(temporary))
                name = "Jarvis_0.1.0_linux_x86_64.AppImage"
                if failure == "missing":
                    (downloaded / name).unlink()
                elif failure == "extra":
                    (downloaded / "unexpected").write_bytes(b"extra")
                elif failure == "tamper":
                    (downloaded / name).write_bytes(b"changed bytes")
                elif failure == "symlink":
                    (downloaded / name).unlink()
                    (downloaded / name).symlink_to(staged / name)
                elif failure in ("manifest", "signature"):
                    target = "latest.json" if failure == "manifest" else "latest.json.sig"
                    (downloaded / target).write_bytes(b"changed metadata")
                revision = "b" * 40 if failure == "revision" else "a" * 40
                with self.assertRaises(ValueError):
                    verify(downloaded, staged, manifest, "0.1.0", revision)


if __name__ == "__main__":
    unittest.main()
