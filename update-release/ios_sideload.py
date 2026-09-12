"""Package a reviewed iPhone build for owner-side signing, never auto-install.

This candidate descriptor is NOT the authenticated updater's latest.json. It
cannot authorize an update or claim Apple signing. No signing key is used here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import stat
import struct
import zipfile

MAX_BYTES = 2 * 1024**3
MAX_FILES = 10000


def iphone_executable(path: Path) -> None:
    """Require a thin arm64 executable built for iOS, not the simulator."""
    with path.open('rb') as source:
        header = source.read(32)
        if len(header) != 32:
            raise ValueError('missing Mach-O header')
        magic, cpu, _, kind, count, size, _, _ = struct.unpack('<8I', header)
        if (magic, cpu, kind) != (0xFEEDFACF, 0x0100000C, 2):
            raise ValueError('expected thin arm64 Mach-O executable')
        if not 1 <= count <= 4096 or not 8 <= size <= 1024 * 1024:
            raise ValueError('invalid Mach-O load commands')
        commands = source.read(size)
        if len(commands) != size:
            raise ValueError('truncated Mach-O load commands')
        offset = 0
        platforms = []
        for _ in range(count):
            if offset + 8 > size:
                raise ValueError('truncated load command')
            command, length = struct.unpack_from('<II', commands, offset)
            if length < 8 or length % 8 or offset + length > size:
                raise ValueError('invalid load command length')
            if command == 0x32:  # LC_BUILD_VERSION
                if length < 24:
                    raise ValueError('invalid build version')
                platforms.append(struct.unpack_from('<I', commands, offset + 8)[0])
            offset += length
        if offset != size or platforms != [2]:  # PLATFORM_IOS, not IOSSIMULATOR
            raise ValueError('executable was not built for physical iOS')


def package(app: Path, output: Path, version: str, revision: str) -> Path:
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('invalid stable version')
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('invalid source revision')
    if app.is_symlink() or not app.is_dir() or app.name != 'Jarvis.app':
        raise ValueError('expected a regular Jarvis.app directory')
    files = []
    total = 0
    for path in sorted(app.rglob('*')):
        mode = path.lstat().st_mode
        relative = path.relative_to(app)
        if stat.S_ISLNK(mode) or not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)):
            raise ValueError('links and special files are forbidden')
        if any(ord(char) < 32 or ord(char) == 127 for char in str(relative)) or '\\' in str(relative):
            raise ValueError('unsafe bundle path')
        if any(part == '_CodeSignature' for part in relative.parts) or path.suffix.lower() in (
            '.mobileprovision', '.p12', '.p8', '.pem', '.key', '.env',
        ):
            raise ValueError('signing material or unexpected signature in unsigned bundle')
        if stat.S_ISREG(mode):
            total += path.stat().st_size
            files.append(path)
            if total > MAX_BYTES or len(files) > MAX_FILES:
                raise ValueError('bundle exceeds limits')
    info_path = app / 'Info.plist'
    if info_path not in files or info_path.stat().st_size > 1024 * 1024:
        raise ValueError('missing or oversized Info.plist')
    info = plistlib.loads(info_path.read_bytes())
    if (info.get('CFBundleIdentifier') != 'com.hawkeynl.jarvis'
            or info.get('CFBundleShortVersionString') != version
            or info.get('CFBundleExecutable') != 'Jarvis'
            or info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']):
        raise ValueError('bundle identity/version/platform mismatch')
    build = info.get('CFBundleVersion')
    if not isinstance(build, str) or not re.fullmatch(r'[1-9][0-9]{0,8}', build):
        raise ValueError('invalid iOS build number')
    binary = app / 'Jarvis'
    if binary not in files or not binary.stat().st_mode & 0o111:
        raise ValueError('missing executable')
    iphone_executable(binary)
    output.mkdir(parents=True, exist_ok=False)
    ipa = output / f'Jarvis_{version}_ios_arm64_unsigned.ipa'
    try:
        with zipfile.ZipFile(ipa, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                entry = zipfile.ZipInfo('Payload/Jarvis.app/' + path.relative_to(app).as_posix(),
                                        date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.compress_type = zipfile.ZIP_DEFLATED
                mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
                entry.external_attr = (stat.S_IFREG | mode) << 16
                with path.open('rb') as source, archive.open(entry, 'w') as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
        with ipa.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        descriptor = {
            'schema_version': 1, 'version': version, 'source_revision': revision,
            'platform': 'ios', 'architecture': 'arm64', 'build_number': int(build),
            'distribution': 'manual-owner-signing', 'apple_signed': False,
            'automatic_install': False,
            'artifact': {'name': ipa.name, 'size': ipa.stat().st_size, 'sha256': digest},
        }
        (output / 'ios-candidate.json').write_text(json.dumps(descriptor, indent=2) + '\n')
        return ipa
    except Exception:
        ipa.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    package(args.app, args.output, args.version, args.revision)
