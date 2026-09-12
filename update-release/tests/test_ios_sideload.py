from pathlib import Path
import hashlib
import json
import plistlib
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import ios_sideload


class IOSSideLoadTests(unittest.TestCase):
    def test_candidate_workflow_has_no_public_upload_or_signing_credentials(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/ios-candidate.yml').read_text()
        for forbidden in ('secrets.', 'actions/upload-artifact', 'gh release', 'TestFlight',
                          'APPLE_TEAM_ID', 'contents: write', '--plain-http', '--insecure'):
            self.assertNotIn(forbidden, workflow)
        for required in ('environment: application-release', "github.ref == 'refs/heads/main'",
                         'CODE_SIGNING_ALLOWED=NO', '-sdk iphoneos',
                         'oras pull', 'cmp ios-candidate.json', '= private', '--password-stdin'):
            self.assertIn(required, workflow)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / 'Jarvis.app'
        self.app.mkdir()
        self.info = {
            'CFBundleIdentifier': 'com.hawkeynl.jarvis',
            'CFBundleShortVersionString': '0.1.0', 'CFBundleVersion': '1',
            'CFBundleExecutable': 'Jarvis', 'CFBundleSupportedPlatforms': ['iPhoneOS'],
        }
        self.write_info()
        self.binary(2)

    def write_info(self):
        (self.app / 'Info.plist').write_bytes(plistlib.dumps(self.info))

    def binary(self, platform):
        binary = self.app / 'Jarvis'
        binary.write_bytes(struct.pack('<8I', 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
                           + struct.pack('<6I', 0x32, 24, platform, 0, 0, 0))
        binary.chmod(0o755)

    def package(self, **kwargs):
        return ios_sideload.package(self.app, self.root / 'out',
                                   kwargs.get('version', '0.1.0'), kwargs.get('revision', 'a' * 40))

    def test_payload_identity_hash_and_explicit_manual_signing(self):
        ipa = self.package()
        descriptor = json.loads((ipa.parent / 'ios-candidate.json').read_text())
        self.assertFalse(descriptor['apple_signed'])
        self.assertFalse(descriptor['automatic_install'])
        self.assertEqual(descriptor['distribution'], 'manual-owner-signing')
        self.assertEqual(descriptor['artifact']['sha256'], hashlib.sha256(ipa.read_bytes()).hexdigest())
        with zipfile.ZipFile(ipa) as archive:
            self.assertEqual(set(archive.namelist()), {'Payload/Jarvis.app/Info.plist', 'Payload/Jarvis.app/Jarvis'})
            self.assertTrue(archive.getinfo('Payload/Jarvis.app/Jarvis').external_attr >> 16 & 0o111)

    def test_simulator_binary_rejected_even_with_device_plist(self):
        self.binary(7)
        with self.assertRaisesRegex(ValueError, 'physical iOS'):
            self.package()

    def test_symlink_rejected(self):
        (self.app / 'escape').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'links'):
            self.package()

    def test_version_and_revision_validated(self):
        for kwargs in ({'version': '../bad'}, {'revision': 'main'}, {'version': '0.2.0'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.package(**kwargs)

    def test_wrong_identity_rejected(self):
        self.info['CFBundleIdentifier'] = 'com.example.other'
        self.write_info()
        with self.assertRaises(ValueError):
            self.package()

    def test_provisioning_and_keys_not_packaged(self):
        for name in ('embedded.mobileprovision', 'signing.p12', 'key.pem'):
            path = self.app / name
            path.write_text('fixture only')
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.package()
            path.unlink()

    def test_size_and_file_bounds(self):
        for setting in ('MAX_BYTES', 'MAX_FILES'):
            with patch.object(ios_sideload, setting, 1), self.assertRaises(ValueError):
                self.package()

    def test_truncated_binary_rejected(self):
        (self.app / 'Jarvis').write_bytes(b'bad')
        with self.assertRaises(ValueError):
            self.package()

    def test_existing_output_is_not_overwritten(self):
        ipa = self.package()
        before = ipa.read_bytes()
        with self.assertRaises(FileExistsError):
            self.package()
        self.assertEqual(ipa.read_bytes(), before)
