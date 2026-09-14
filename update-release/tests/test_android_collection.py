"""Exercise the real workflow collection block with a synthetic SDK signer."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AndroidCollectionTests(unittest.TestCase):
    def collect(self, available=True, matching=True):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        block = workflow.split('      - name: Verify and collect signed Android packages\n', 1)[1]
        script = textwrap.dedent(block.split('        run: |\n', 1)[1].split('      - uses:', 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in ('app/build/outputs/apk/release/app-release.apk',
                         'app/build/outputs/bundle/release/app-release.aab'):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b'fixture, not a signed package')
            signer = root / 'sdk/build-tools/36.0.0/apksigner'
            if available:
                signer.parent.mkdir(parents=True)
                signer.write_text('#!/bin/sh\n[ "$1" = verify ] || exit 1\n'
                                  'if [ "$2" = --print-certs ]; then\n'
                                  '  printf "Signer #1 certificate SHA-256 digest: %s\\n" '
                                  + 'a' * 64 + '\nfi\n')
                signer.chmod(0o755)
            env = {'PATH': os.defpath, 'ANDROID_HOME': str(root / 'sdk'),
                   'RUNNER_TEMP': directory, 'APP_VERSION': '0.1.0',
                   'EXPECTED_ANDROID_SIGNER': ('a' if matching else 'b') * 64}
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=10)
            collected = sorted(path.suffix for path in (root / 'android-assets').glob('*'))
            return result, collected

    def test_sdk_signer_does_not_require_build_tools_on_path(self):
        result, collected = self.collect()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(collected, ['.aab', '.apk'])

    def test_missing_sdk_signer_refuses_collection(self):
        result, collected = self.collect(available=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('apksigner is unavailable', result.stderr)
        self.assertEqual(collected, [])

    def test_wrong_certificate_refuses_collection(self):
        result, collected = self.collect(matching=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('does not match the pinned release identity', result.stderr)
        self.assertEqual(collected, [])
