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
from desktop_release import build, expected_assets, validate_source
from manifest import validate_manifest


class DesktopReleaseTests(unittest.TestCase):
    def fixture(self, root):
        for name in expected_assets("0.1.0"):
            (root / name).write_bytes(b"fixture signature" if name.endswith(".sig") else b"fixture executable")
        return build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z")

    def test_checked_in_versions_and_immutable_git_lock(self):
        self.assertEqual(validate_source(ROOT, "0.1.0"), "89372c9c5b157361881b79b583c309c91c6f5646")

    def test_standalone_source_rejects_overrides_and_nested_path_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "src-tauri").mkdir()
            for name in ("package.json", "package-lock.json", "src-tauri/Cargo.toml", "src-tauri/Cargo.lock", "src-tauri/tauri.conf.json"):
                shutil.copyfile(ROOT / name, root / name)
            cargo = (root / "src-tauri/Cargo.toml").read_text()
            for override in (
                '\n[target.\'cfg(test)\'.dependencies]\nescape = { path = "../../outside" }\n',
                '\n[patch.crates-io]\nescape = { path = "../../outside" }\n',
            ):
                (root / "src-tauri/Cargo.toml").write_text(cargo + override)
                with self.assertRaises(ValueError):
                    validate_source(root, "0.1.0")
            (root / "src-tauri/Cargo.toml").write_text(cargo)
            (root / ".cargo").mkdir()
            (root / ".cargo/config.toml").write_text('[source.local]\ndirectory = "../../outside"\n')
            with self.assertRaises(ValueError):
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

    def test_three_platforms_and_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = self.fixture(Path(temporary))
            self.assertEqual(len(value["artifacts"]), 3)
            self.assertEqual(value["release"]["tag"], "app-v0.1.0")

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
                build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z")
            (root / "extra").unlink()
            name = next(iter(expected_assets("0.1.0")))
            (root / name).unlink()
            (root / name).symlink_to(root / "missing")
            with self.assertRaises(ValueError):
                build(root, "0.1.0", "a"*40, "2026-09-01T12:00:00Z")

    def test_production_configuration_has_no_default_origin(self):
        import json
        configuration = json.loads((ROOT / "src-tauri/tauri.conf.json").read_text())
        self.assertNotIn("endpoints", configuration.get("plugins", {}).get("updater", {}))
        self.assertNotIn("home_node_origin", configuration)
