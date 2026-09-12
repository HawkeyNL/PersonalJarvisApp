from __future__ import annotations

from pathlib import Path
import re
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY / ".github/workflows/release.yml"
CI_WORKFLOW = REPOSITORY / ".github/workflows/ci.yml"


class PrivateReleaseWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.ci_workflow = CI_WORKFLOW.read_text(encoding="utf-8")

    def test_release_requires_main_source_and_a_protected_environment(self) -> None:
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotIn("pull_request:", self.workflow)
        self.assertIn("github.ref == 'refs/heads/main'", self.workflow)
        self.assertIn("environment: application-release", self.workflow)
        self.assertIn("tags: ['app-v*']", self.workflow)
        self.assertIn("git merge-base --is-ancestor HEAD origin/main", self.workflow)
        self.assertIn("needs.validate.outputs.app_version", self.workflow)

    def test_github_never_receives_home_node_deployment_coordinates(self) -> None:
        for forbidden in (
            "HOME_NODE_HOST",
            "HOME_NODE_IP",
            "HOME_NODE_DNS",
            "HOME_NODE_SSH",
            "HOME_NODE_USER",
        ):
            self.assertNotIn(forbidden, self.workflow)

    def test_macos_release_requires_both_os_and_updater_trust_layers(self) -> None:
        for required in (
            "MACOS_DEVELOPER_ID_CERTIFICATE_P12_BASE64",
            "APPLE_SIGNING_IDENTITY",
            "APPLE_API_KEY_PATH",
            "codesign --verify --deep --strict",
            "flags=.*runtime",
            'xcrun notarytool submit "$dmg"',
            'xcrun stapler staple "$dmg"',
            'xcrun stapler validate "$app"',
            'xcrun stapler validate "$dmg"',
            "TAURI_SIGNING_PRIVATE_KEY",
            "verify_update_signature",
        ):
            self.assertIn(required, self.workflow)

    def test_manifest_publication_waits_for_every_distributed_platform(self) -> None:
        self.assertIn("needs: [validate, desktop, android]", self.workflow)
        publish = self.workflow.index("publish:")
        upload_manifest = self.workflow.index('gh release upload "$RELEASE_TAG"', publish)
        publish_draft = self.workflow.index("--draft=false --latest", upload_manifest)
        self.assertLess(upload_manifest, publish_draft)

    def test_release_actions_are_immutably_pinned(self) -> None:
        actions = re.findall(r"uses:\s+([^\s#]+)", self.workflow)
        self.assertTrue(actions)
        for action in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_job_environment_never_exposes_release_secrets(self) -> None:
        for job in ("validate", "desktop", "android", "publish"):
            start = self.workflow.index(f"  {job}:")
            steps = self.workflow.index("    steps:", start)
            with self.subTest(job=job):
                self.assertNotIn("secrets.", self.workflow[start:steps])

    def test_distributed_clients_release_from_this_repository(self) -> None:
        self.assertNotIn("PRIVATE_RELEASE_REPO", self.workflow)
        self.assertIn("  android:", self.workflow)
        self.assertIn("assembleRelease bundleRelease", self.workflow)
        self.assertIn("GH_REPO: ${{ github.repository }}", self.workflow)

    def test_ios_distribution_is_deliberately_outside_release_ci(self) -> None:
        for forbidden in (
            "  ios:",
            "TestFlight",
            "App Store Connect",
            "APPLE_DISTRIBUTION_CERTIFICATE_P12_BASE64",
            "APPLE_DISTRIBUTION_CERTIFICATE_PASSWORD",
            "APPLE_APP_STORE_PROVISIONING_PROFILE_BASE64",
            "APPLE_TEAM_ID",
            "APP_STORE_CONNECT_API_ISSUER_ID",
            "APP_STORE_CONNECT_API_KEY_ID",
            "APP_STORE_CONNECT_API_PRIVATE_KEY_BASE64",
            "ios_build_number",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.workflow)

    def test_ios_ci_validates_simulator_and_unsigned_device_packaging(self) -> None:
        for required in (
            "iOS simulator",
            "-sdk iphonesimulator",
            "CODE_SIGNING_ALLOWED=NO",
            "CODE_SIGNING_REQUIRED=NO",
            "build",
            "test",
            "-sdk iphoneos",
            "ios_sideload.py",
        ):
            self.assertIn(required, self.ci_workflow)
        for forbidden in (
            "secrets.",
            "-exportArchive",
            "TestFlight",
            "App Store Connect",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.ci_workflow)

    def test_final_draft_is_revalidated_before_publish(self) -> None:
        section = self.workflow[self.workflow.index("  publish:"):]
        self.assertIn("verify_published.py", section)
        self.assertIn("signatures.py final", section)
        self.assertLess(section.index("verify_published.py"), section.index("--draft=false --latest"))
