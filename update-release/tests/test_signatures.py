"""Cryptographic tests use disposable keys only, never production credentials."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import signatures

MINISIGN = os.environ.get("JARVIS_TEST_MINISIGN") or shutil.which("minisign")


@unittest.skipUnless(MINISIGN, "install minisign to run cryptographic fixtures")
class SignatureTests(unittest.TestCase):
    def test_exact_payload_and_key_are_required(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(signatures, "MINISIGN", MINISIGN):
            root = Path(temporary)
            private, public = root / "test.key", root / "test.pub"
            subprocess.run([MINISIGN, "-G", "-W", "-s", str(private), "-p", str(public)],
                           check=True, capture_output=True)
            payload = root / "artifact"
            payload.write_bytes(b"test-only updater payload")
            document = root / "artifact.minisig"
            subprocess.run([MINISIGN, "-S", "-s", str(private), "-m", str(payload), "-x", str(document)],
                           check=True, capture_output=True)
            key = base64.b64encode(public.read_bytes()).decode()
            signature = base64.b64encode(document.read_bytes()).decode()
            signatures.verify(payload, signature, key)
            payload.write_bytes(b"tampered updater payload")
            with self.assertRaisesRegex(ValueError, "verification failed"):
                signatures.verify(payload, signature, key)
            payload.write_bytes(b"test-only updater payload")
            for invalid in ("", "invalid base64", base64.b64encode(b"malformed signature").decode()):
                with self.assertRaises(ValueError):
                    signatures.verify(payload, invalid, key)
            alternate_private, alternate_public = root / "other.key", root / "other.pub"
            subprocess.run([MINISIGN, "-G", "-W", "-s", str(alternate_private), "-p", str(alternate_public)],
                           check=True, capture_output=True)
            with self.assertRaisesRegex(ValueError, "verification failed"):
                signatures.verify(payload, signature, base64.b64encode(alternate_public.read_bytes()).decode())
            alias = root / "alias"
            alias.symlink_to(payload)
            with self.assertRaisesRegex(ValueError, "unsafe"):
                signatures.verify(alias, signature, key)


if __name__ == "__main__":
    unittest.main()
