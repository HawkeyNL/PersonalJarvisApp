import base64
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('tauri_release_config', ROOT / 'update-release/tauri_release_config.py')
config_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config_module)


class ReleaseConfigTests(unittest.TestCase):
    def test_bundler_receives_public_key_without_default_update_origin(self):
        # Synthetic public-key structure only; never a production signing key.
        key = base64.b64encode(b'untrusted comment: fixture public key\n' + base64.b64encode(b'Ed' + bytes(40)) + b'\n').decode()
        overlay = config_module.release_config(key)
        self.assertEqual(overlay['plugins']['updater'], {'pubkey': key, 'endpoints': []})
        self.assertEqual(overlay['build'], {'beforeBuildCommand': ''})
        self.assertEqual(json.loads(json.dumps(overlay)), overlay)
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('config=$(python3 update-release/tauri_release_config.py)', workflow)
        self.assertEqual(workflow.count('--config "$TAURI_RELEASE_CONFIG"'), 2)

    def test_missing_malformed_and_secret_key_shapes_fail_without_echoing_input(self):
        for key in ['', 'bad-input', 'x' * 16385, base64.b64encode(b'untrusted comment: secret key\nnot-a-public-key').decode()]:
            with self.subTest(length=len(key)), self.assertRaisesRegex(ValueError, '^A valid Tauri signing PUBLIC key is required$'):
                config_module.release_config(key)

    def test_android_release_uses_the_same_sdk_as_successful_ci(self):
        command = 'sdkmanager "platforms;android-37.0" "build-tools;36.0.0"'
        for name in ['ci.yml', 'release.yml']:
            self.assertIn(command, (ROOT / '.github/workflows' / name).read_text())
