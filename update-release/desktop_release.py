"""Desktop-only release identity and complete artifact validation.

No network or signing credentials are used here. Signing is a separate step.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import tomllib

from manifest import validate_manifest

VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", re.ASCII)
REVISION = re.compile(r"[0-9a-f]{40}", re.ASCII)
TARGETS = (("linux", "x86_64", ".AppImage"), ("windows", "x86_64", ".exe"), ("macos", "arm64", ".app.tar.gz"))


def validate_source(root: Path, version: str) -> str:
    if not VERSION.fullmatch(version):
        raise ValueError("version must be MAJOR.MINOR.PATCH")
    cargo = tomllib.loads((root / "src-tauri/Cargo.toml").read_text())
    npm_lock = json.loads((root / "package-lock.json").read_text())
    for actual in (cargo["package"]["version"], json.loads((root / "package.json").read_text())["version"],
                   npm_lock["version"], npm_lock["packages"][""]["version"],
                   json.loads((root / "src-tauri/tauri.conf.json").read_text())["version"]):
        if actual != version:
            raise ValueError("npm, Cargo and Tauri product versions must match")
    dependency = cargo["dependencies"]["jarvis-client-core"]
    if set(dependency) != {"git", "rev"} or dependency["git"] != "https://github.com/HawkeyNL/PersonalJarvis.git" or not REVISION.fullmatch(dependency["rev"]):
        raise ValueError("client-core must use the authoritative repository at an exact revision")
    lock = tomllib.loads((root / "src-tauri/Cargo.lock").read_text())
    matches = [p for p in lock["package"] if p["name"] == "jarvis-client-core"]
    expected = f"git+{dependency['git']}?rev={dependency['rev']}#{dependency['rev']}"
    if len(matches) != 1 or matches[0].get("source") != expected:
        raise ValueError("client-core lockfile does not match the immutable pin")
    def inspect_table(table):
        for key, value in table.items():
            if key in ("patch", "replace", "workspace"):
                raise ValueError("standalone desktop dependencies cannot be overridden by a workspace or patch")
            if key in ("dependencies", "build-dependencies", "dev-dependencies"):
                if any(isinstance(d, dict) and "path" in d for d in value.values()):
                    raise ValueError("desktop dependencies cannot use local paths")
            elif isinstance(value, dict):
                inspect_table(value)
    inspect_table(cargo)
    for base in (root, root / "src-tauri"):
        for name in ("config", "config.toml"):
            config = base / ".cargo" / name
            if config.exists():
                settings = tomllib.loads(config.read_text())
                if any(key in settings for key in ("source", "paths", "patch")):
                    raise ValueError("desktop Cargo config cannot redirect dependency sources")
    return dependency["rev"]


def expected_assets(version: str) -> set[str]:
    if not VERSION.fullmatch(version):
        raise ValueError("invalid version")
    names = {f"Jarvis_{version}_{p}_{a}{suffix}" for p, a, suffix in TARGETS}
    return names | {name + ".sig" for name in names} | {f"Jarvis_{version}_macos_arm64.dmg"}


def build(assets: Path, version: str, revision: str, released_at: str) -> dict:
    if not REVISION.fullmatch(revision):
        raise ValueError("source revision must be an exact SHA")
    expected = expected_assets(version)
    actual = {p.name for p in assets.iterdir()}
    if actual != expected:
        raise ValueError(f"incomplete/unexpected assets: missing={sorted(expected-actual)}, extra={sorted(actual-expected)}")
    for name in expected:
        path = assets / name
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 2 * 1024**3:
            raise ValueError("release assets must be bounded regular nonempty files")
    entries = []
    for platform, architecture, suffix in TARGETS:
        name = f"Jarvis_{version}_{platform}_{architecture}{suffix}"
        digest = hashlib.sha256()
        with (assets / name).open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        signature_path = assets / (name + ".sig")
        if signature_path.stat().st_size > 16384:
            raise ValueError("signature exceeds size limit")
        entries.append({"platform": platform, "architecture": architecture, "distribution": "home-node-updater",
                        "artifact": {"path": f"releases/v{version}/{platform}-{architecture}/{name}",
                                     "size": (assets / name).stat().st_size, "sha256": digest.hexdigest()},
                        "signature": {"scheme": "tauri-minisign", "value": signature_path.read_text().strip()}})
    return validate_manifest({"schema_version": 1, "release": {
        "version": version, "tag": f"app-v{version}", "source_revision": revision,
        "product": "desktop", "client_protocol": 1, "minimum_client_protocol": 1,
        "channel": "stable", "released_at": released_at}, "artifacts": entries})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--revision")
    parser.add_argument("--released-at")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    validate_source(Path(__file__).resolve().parents[1], args.version)
    if args.assets:
        value = build(args.assets, args.version, args.revision or "", args.released_at or "")
        if not args.output or args.output.parent.resolve() == args.assets.resolve():
            raise ValueError("write manifest outside the artifact directory")
        args.output.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
