import copy
import json
import shutil
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "update-release"))
from client_release import build, expected_assets, validate_progression, validate_source
from manifest import validate_manifest


class ClientReleaseTests(unittest.TestCase):
    def fixture(self, root):
        for name in expected_assets("0.1.0"):
            (root / name).write_bytes(b"fixture signature" if name.endswith(".sig") else b"fixture executable")
        return build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z", 1, "c" * 64)

    def test_checked_in_versions_and_immutable_git_lock(self):
        self.assertEqual(validate_source(ROOT, "0.1.0"), "89372c9c5b157361881b79b583c309c91c6f5646")

    def test_standalone_source_rejects_overrides_and_nested_path_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copytree(ROOT / "desktop", root / "desktop")
            shutil.copytree(ROOT / "android", root / "android")
            shutil.copytree(ROOT / "ios", root / "ios")
            cargo = (root / "desktop/src-tauri/Cargo.toml").read_text()
            for override in (
                '\n[target.\'cfg(test)\'.dependencies]\nescape = { path = "../../outside" }\n',
                '\n[patch.crates-io]\nescape = { path = "../../outside" }\n',
            ):
                (root / "desktop/src-tauri/Cargo.toml").write_text(cargo + override)
                with self.assertRaises(ValueError):
                    validate_source(root, "0.1.0")
            (root / "desktop/src-tauri/Cargo.toml").write_text(cargo)
            (root / "desktop/.cargo").mkdir()
            (root / "desktop/.cargo/config.toml").write_text('[source.local]\ndirectory = "../../outside"\n')
            with self.assertRaises(ValueError):
                validate_source(root, "0.1.0")

    def test_ios_generated_project_and_plist_must_share_the_version_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copytree(ROOT / "desktop", root / "desktop")
            shutil.copytree(ROOT / "android", root / "android")
            shutil.copytree(ROOT / "ios", root / "ios")
            project = root / "ios/project.yml"
            project.write_text(project.read_text().replace('MARKETING_VERSION: "0.1.0"', 'MARKETING_VERSION: "9.9.9"'))
            with self.assertRaisesRegex(ValueError, "project.yml"):
                validate_source(root, "0.1.0")
            project.write_text((ROOT / "ios/project.yml").read_text())
            plist = root / "ios/Jarvis/Info.plist"
            plist.write_text(plist.read_text().replace("$(MARKETING_VERSION)", "0.1.0"))
            with self.assertRaisesRegex(ValueError, "Info.plist"):
                validate_source(root, "0.1.0")

    def test_npm_root_package_version_cannot_drift(self):
        from unittest.mock import patch
        original = Path.read_text
        def read(path, *args, **kwargs):
            text = original(path, *args, **kwargs)
            if path.name == "package-lock.json":
                value = json.loads(text)
                value["packages"][""]["version"] = "99.0.0"
                return json.dumps(value)
            return text
        with patch.object(Path, "read_text", read), self.assertRaises(ValueError):
            validate_source(ROOT, "0.1.0")

    def test_complete_client_matrix_and_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = self.fixture(Path(temporary))
            self.assertEqual(len(value["artifacts"]), 4)
            self.assertEqual(value["release"]["tag"], "app-v0.1.0")
            self.assertEqual(len(value["installers"]), 2)
            self.assertEqual(
                {Path(item["artifact"]["path"]).suffix for item in value["installers"]},
                {".dmg", ".aab"},
            )

    def test_malformed_records_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = self.fixture(Path(temporary))
            mutations = [
                lambda v: v["release"].update(version="1.2;touch x"),
                lambda v: v["release"].update(source_revision="main"),
                lambda v: v["release"].update(tag="v0.1.0"),
                lambda v: v["artifacts"][0].update(platform="unknown"),
                lambda v: v["artifacts"][0]["artifact"].update(path="../outside"),
                lambda v: v["artifacts"][0]["artifact"].update(sha256="bad"),
                lambda v: v["artifacts"][0]["signature"].update(value=""),
                lambda v: v["artifacts"].append(v["artifacts"][0]),
                lambda v: v["artifacts"].pop(),
                lambda v: v["installers"][0]["artifact"].update(path="../installer.dmg"),
                lambda v: v["installers"][0]["artifact"].update(sha256="bad"),
                lambda v: v["installers"][0]["artifact"].update(size=-1),
                lambda v: v["installers"].append(v["installers"][0]),
            ]
            for mutation in mutations:
                invalid = copy.deepcopy(value)
                mutation(invalid)
                with self.assertRaises(ValueError):
                    validate_manifest(invalid)

    def test_extra_or_symlink_asset_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            (root / "extra").write_bytes(b"extra")
            with self.assertRaises(ValueError):
                build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z", 1, "c" * 64)
            (root / "extra").unlink()
            name = next(iter(expected_assets("0.1.0")))
            (root / name).unlink()
            (root / name).symlink_to(root / "missing")
            with self.assertRaises(ValueError):
                build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z", 1, "c" * 64)

    def test_production_configuration_has_no_default_origin(self):
        import json
        configuration = json.loads((ROOT / "desktop/src-tauri/tauri.conf.json").read_text())
        self.assertNotIn("endpoints", configuration.get("plugins", {}).get("updater", {}))
        self.assertNotIn("home_node_origin", configuration)

    def test_one_semver_and_independent_android_build_number(self):
        self.assertEqual(validate_source(ROOT, "0.1.0", 1), "89372c9c5b157361881b79b583c309c91c6f5646")
        for android_code in (0, "01", "1.0"):
            with self.subTest(android_code=android_code), self.assertRaises(ValueError):
                validate_source(ROOT, "0.1.0", android_code)

    def test_manifest_covers_all_clients_and_progression_is_monotonic(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = self.fixture(Path(temporary))
        self.assertEqual(value["release"]["product"], "clients")
        self.assertEqual({entry["platform"] for entry in value["artifacts"]}, {"linux", "windows", "macos", "android"})
        self.assertEqual({entry["platform"] for entry in value["installers"]}, {"macos", "android"})
        validate_progression(value, "0.1.1", 2)
        for version, android_code in (("0.1.0", 2), ("0.1.1", 1)):
            with self.subTest(version=version), self.assertRaises(ValueError):
                validate_progression(value, version, android_code)
