"""Unified Jarvis client release identity and artifact validation.

This module is deliberately credential-free. Platform jobs produce and verify
signed artifacts; this code binds their exact bytes and public signing identities
into one complete desktop/Android downloadable release manifest. The iOS
client is source-and-CI only and is intentionally absent from release assets.
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
SHA256 = re.compile(r"[0-9a-f]{64}", re.ASCII)
DESKTOP_TARGETS = (
    ("linux", "x86_64", ".AppImage"),
    ("windows", "x86_64", ".exe"),
    ("macos", "arm64", ".app.tar.gz"),
)
MAX_ARTIFACT_BYTES = 2 * 1024**3


def _positive_integer(value: str | int, label: str, maximum: int = 2_100_000_000) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} is invalid")
    text = str(value)
    if not re.fullmatch(r"[1-9][0-9]*", text):
        raise ValueError(f"{label} must be a positive integer")
    parsed = int(text)
    if parsed > maximum:
        raise ValueError(f"{label} exceeds its supported range")
    return parsed


def _desktop_versions(root: Path, version: str) -> str:
    desktop = root / "desktop"
    cargo = tomllib.loads((desktop / "src-tauri/Cargo.toml").read_text())
    npm_lock = json.loads((desktop / "package-lock.json").read_text())
    actual_versions = (
        cargo["package"]["version"],
        json.loads((desktop / "package.json").read_text())["version"],
        npm_lock["version"],
        npm_lock["packages"][""]["version"],
        json.loads((desktop / "src-tauri/tauri.conf.json").read_text())["version"],
    )
    if any(actual != version for actual in actual_versions):
        raise ValueError("desktop npm, Cargo and Tauri versions must match the application version")
    dependency = cargo["dependencies"]["jarvis-client-core"]
    if (
        set(dependency) != {"git", "rev"}
        or dependency["git"] != "https://github.com/HawkeyNL/PersonalJarvis.git"
        or not REVISION.fullmatch(dependency["rev"])
    ):
        raise ValueError("client-core must use the authoritative repository at an exact revision")
    lock = tomllib.loads((desktop / "src-tauri/Cargo.lock").read_text())
    matches = [package for package in lock["package"] if package["name"] == "jarvis-client-core"]
    expected = f"git+{dependency['git']}?rev={dependency['rev']}#{dependency['rev']}"
    if len(matches) != 1 or matches[0].get("source") != expected:
        raise ValueError("client-core lockfile does not match the immutable pin")

    def inspect_table(table: dict) -> None:
        for key, value in table.items():
            if key in ("patch", "replace", "workspace"):
                raise ValueError("desktop dependencies cannot be redirected by workspace or patch configuration")
            if key in ("dependencies", "build-dependencies", "dev-dependencies"):
                if any(isinstance(item, dict) and "path" in item for item in value.values()):
                    raise ValueError("desktop dependencies cannot use local paths")
            elif isinstance(value, dict):
                inspect_table(value)

    inspect_table(cargo)
    for base in (desktop, desktop / "src-tauri"):
        for name in ("config", "config.toml"):
            config = base / ".cargo" / name
            if config.exists():
                settings = tomllib.loads(config.read_text())
                if any(key in settings for key in ("source", "paths", "patch")):
                    raise ValueError("desktop Cargo config cannot redirect dependency sources")
    return dependency["rev"]


def _mobile_versions(root: Path, version: str) -> tuple[int, int]:
    gradle = (root / "android/app/build.gradle.kts").read_text()
    android_version = re.search(r'JARVIS_APP_VERSION"\)\.orNull \?: "([^"]+)"', gradle)
    android_code = re.search(
        r'JARVIS_ANDROID_VERSION_CODE"\)\.orNull\?\.toIntOrNull\(\) \?: ([0-9]+)', gradle
    )
    if android_version is None or android_version.group(1) != version or android_code is None:
        raise ValueError("Android default versionName must match the application version")

    xcode = (root / "ios/Jarvis.xcodeproj/project.pbxproj").read_text()
    marketing_versions = set(re.findall(r"MARKETING_VERSION = ([^;]+);", xcode))
    build_numbers = {int(value) for value in re.findall(r"CURRENT_PROJECT_VERSION = ([0-9]+);", xcode)}
    if marketing_versions != {version}:
        raise ValueError("iOS MARKETING_VERSION must match the application version")
    if len(build_numbers) != 1:
        raise ValueError("iOS checked-in build number must be consistent")
    ios_build = next(iter(build_numbers))
    project = (root / "ios/project.yml").read_text()
    if (
        len(re.findall(rf'^\s*MARKETING_VERSION:\s*["\']?{re.escape(version)}["\']?\s*$', project, re.MULTILINE)) != 1
        or len(re.findall(rf'^\s*CURRENT_PROJECT_VERSION:\s*["\']?{ios_build}["\']?\s*$', project, re.MULTILINE)) != 1
    ):
        raise ValueError("iOS project.yml version/build must match the checked-in Xcode project")
    plist = (root / "ios/Jarvis/Info.plist").read_text()
    if "$(MARKETING_VERSION)" not in plist or "$(CURRENT_PROJECT_VERSION)" not in plist:
        raise ValueError("iOS Info.plist must inherit the validated build settings")
    return int(android_code.group(1)), ios_build


def validate_source(
    root: Path,
    version: str,
    android_version_code: str | int | None = None,
) -> str:
    if not VERSION.fullmatch(version):
        raise ValueError("version must be MAJOR.MINOR.PATCH")
    revision = _desktop_versions(root, version)
    checked_android, checked_ios = _mobile_versions(root, version)
    _positive_integer(checked_ios, "iOS checked-in build number")
    if android_version_code is not None:
        requested = _positive_integer(android_version_code, "Android versionCode")
        if requested < checked_android:
            raise ValueError("Android versionCode must not move behind its checked-in value")
    return revision


def _version_key(value: str) -> tuple[int, int, int]:
    if not VERSION.fullmatch(value):
        raise ValueError("stable application version is invalid")
    major, minor, patch = value.split(".")
    return int(major), int(minor), int(patch)


def validate_progression(previous: dict, version: str, android_version_code: int) -> None:
    previous = validate_manifest(previous)
    if previous["release"].get("product") != "clients":
        raise ValueError("previous release is not a unified client release")
    if _version_key(version) <= _version_key(previous["release"]["version"]):
        raise ValueError("application SemVer must advance")
    android = next(entry for entry in previous["artifacts"] if entry["platform"] == "android")
    if android_version_code <= android["metadata"]["version_code"]:
        raise ValueError("Android versionCode must advance")


def expected_assets(version: str) -> set[str]:
    if not VERSION.fullmatch(version):
        raise ValueError("invalid version")
    desktop = {
        f"Jarvis_{version}_{platform}_{architecture}{suffix}"
        for platform, architecture, suffix in DESKTOP_TARGETS
    }
    return (
        desktop
        | {name + ".sig" for name in desktop}
        | {
            f"Jarvis_{version}_macos_arm64.dmg",
            f"Jarvis_{version}_android_universal.apk",
            f"Jarvis_{version}_android_universal.aab",
        }
    )


def _regular_asset(path: Path) -> None:
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_ARTIFACT_BYTES:
        raise ValueError("release assets must be bounded regular nonempty files")


def _artifact(path: Path, version: str, platform: str, architecture: str) -> dict:
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "path": f"releases/v{version}/{platform}-{architecture}/{path.name}",
        "size": path.stat().st_size,
        "sha256": digest,
    }


def build(
    assets: Path,
    version: str,
    revision: str,
    released_at: str,
    android_version_code: int,
    android_signer: str,
) -> dict:
    if not REVISION.fullmatch(revision):
        raise ValueError("source revision must be an exact SHA")
    android_version_code = _positive_integer(android_version_code, "Android versionCode")
    if not SHA256.fullmatch(android_signer):
        raise ValueError("Android signing certificate identity must be a lowercase SHA-256 digest")
    expected = expected_assets(version)
    actual = {path.name for path in assets.iterdir()}
    if actual != expected:
        raise ValueError(f"incomplete/unexpected assets: missing={sorted(expected-actual)}, extra={sorted(actual-expected)}")
    for name in expected:
        _regular_asset(assets / name)

    entries = []
    for platform, architecture, suffix in DESKTOP_TARGETS:
        name = f"Jarvis_{version}_{platform}_{architecture}{suffix}"
        signature = assets / f"{name}.sig"
        if signature.stat().st_size > 16_384:
            raise ValueError("Tauri signature exceeds its size limit")
        entries.append(
            {
                "platform": platform,
                "architecture": architecture,
                "distribution": "home-node-updater",
                "artifact": _artifact(assets / name, version, platform, architecture),
                "signature": {"scheme": "tauri-minisign", "value": signature.read_text().strip()},
            }
        )

    apk_name = f"Jarvis_{version}_android_universal.apk"
    entries.append(
        {
            "platform": "android",
            "architecture": "universal",
            "distribution": "home-node-apk",
            "artifact": _artifact(assets / apk_name, version, "android", "universal"),
            "signature": {
                "scheme": "android-apk-signing-certificate-sha256",
                "value": android_signer,
            },
            "metadata": {"version_code": android_version_code},
        }
    )
    dmg = assets / f"Jarvis_{version}_macos_arm64.dmg"
    aab = assets / f"Jarvis_{version}_android_universal.aab"
    return validate_manifest(
        {
            "schema_version": 1,
            "installers": [
                {
                    "platform": "macos",
                    "architecture": "arm64",
                    "distribution": "home-node-installer",
                    "artifact": _artifact(dmg, version, "macos", "arm64"),
                },
                {
                    "platform": "android",
                    "architecture": "universal",
                    "distribution": "app-store-bundle",
                    "artifact": _artifact(aab, version, "android", "universal"),
                },
            ],
            "release": {
                "version": version,
                "tag": f"app-v{version}",
                "source_revision": revision,
                "product": "clients",
                "client_protocol": 1,
                "minimum_client_protocol": 1,
                "channel": "stable",
                "released_at": released_at,
            },
            "artifacts": entries,
        }
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--android-version-code")
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--revision")
    parser.add_argument("--released-at")
    parser.add_argument("--android-signer")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    validate_source(root, args.version, args.android_version_code)
    if args.previous:
        if args.android_version_code is None:
            raise ValueError("previous-release validation requires the Android build identity")
        validate_progression(
            json.loads(args.previous.read_bytes()),
            args.version,
            _positive_integer(args.android_version_code, "Android versionCode"),
        )
    if args.assets:
        if None in (args.android_version_code, args.android_signer):
            raise ValueError("complete release generation requires the Android identity")
        value = build(
            args.assets,
            args.version,
            args.revision or "",
            args.released_at or "",
            _positive_integer(args.android_version_code, "Android versionCode"),
            args.android_signer or "",
        )
        if not args.output or args.output.parent.resolve() == args.assets.resolve():
            raise ValueError("write manifest outside the artifact directory")
        args.output.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
