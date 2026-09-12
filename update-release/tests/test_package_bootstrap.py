"""Guard the non-release GHCR bootstrap boundary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PackageBootstrapTests(unittest.TestCase):
    def test_public_release_publisher_is_disabled_during_migration(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('if: false &&', workflow)
        bootstrap = (ROOT / '.github/workflows/package-bootstrap.yml').read_text()
        self.assertIn('branches: [main]', bootstrap)
        self.assertNotIn('feat/tagged-client-release', bootstrap)

    def test_bootstrap_is_private_empty_and_uses_ephemeral_token(self):
        workflow = (ROOT / '.github/workflows/package-bootstrap.yml').read_text()
        for expected in ('packages: write', 'environment: application-release',
                         'FROM scratch', '.visibility == "private"',
                         '--password-stdin', '--header @-',
                         'bootstrap-$GITHUB_RUN_ID'):
            self.assertIn(expected, workflow)
        for forbidden in ('secrets.', 'gh release create', 'gh release upload',
                          'docker build .', 'actions/upload-artifact', ':latest'):
            self.assertNotIn(forbidden, workflow)

    def test_normal_ci_does_not_upload_application_builds(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertNotIn('actions/upload-artifact', workflow)
        self.assertNotIn('packages: write', workflow)
