"""Private OCI transport only. Signing/manifest validation stays in release tooling.

No Actions artifacts, GitHub Releases or Home Node coordinates. Authentication
uses an ephemeral registry config and stdin, never a token command argument.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import tempfile

from client_release import build, expected_assets, VERSION, REVISION, validate_progression
from manifest import validate_manifest
from signatures import verify

IMAGE = 'ghcr.io/hawkeynl/jarvis-client-artifacts'
SOURCE = 'https://github.com/HawkeyNL/PersonalJarvisApp'
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
BUILD_TYPE = 'application/vnd.jarvis.client-build.v1'
RELEASE_TYPE = 'application/vnd.jarvis.client-release.v1'


def names(version: str, target: str) -> set[str]:
    assets = expected_assets(version)
    if target == 'release':
        return assets | {'latest.json', 'latest.json.sig'}
    if target not in ('linux', 'windows', 'macos', 'android', 'ios'):
        raise ValueError('unsupported build target')
    return {name for name in assets if f'_{target}_' in name}


def inventory(directory: Path, expected: set[str]) -> dict[str, tuple[int, str]]:
    if {path.name for path in directory.iterdir()} != expected:
        raise ValueError('incomplete or unexpected private artifact inventory')
    result = {}
    for name in expected:
        path = directory / name
        limit = 16384 if name.endswith('.sig') else (1024**2 if name == 'latest.json' else 2 * 1024**3)
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
            raise ValueError('unsafe private artifact file')
        with path.open('rb') as stream:
            result[name] = (path.stat().st_size, hashlib.file_digest(stream, 'sha256').hexdigest())
    return result


def validate_oci(raw: bytes, digest: str, expected: set[str], revision: str, kind: str) -> dict:
    if len(raw) > 1024**2 or not DIGEST.fullmatch(digest) or hashlib.sha256(raw).hexdigest() != digest[7:]:
        raise ValueError('invalid pinned OCI metadata')
    doc = json.loads(raw)
    annotations = doc.get('annotations', {})
    if (doc.get('schemaVersion') != 2 or doc.get('mediaType') != 'application/vnd.oci.image.manifest.v1+json'
            or doc.get('artifactType') != kind or annotations.get('org.opencontainers.image.source') != SOURCE
            or annotations.get('org.opencontainers.image.revision') != revision
            or not isinstance(doc.get('layers'), list) or len(doc['layers']) != len(expected)):
        raise ValueError('private build provenance or matrix mismatch')
    seen = {}
    for layer in doc['layers']:
        annotations = layer.get('annotations', {})
        name = annotations.get('org.opencontainers.image.title')
        maximum = 16384 if isinstance(name, str) and name.endswith('.sig') else (1024**2 if name == 'latest.json' else 2 * 1024**3)
        media = 'application/json' if name == 'latest.json' else 'application/octet-stream'
        if (set(annotations) != {'org.opencontainers.image.title'} or name not in expected or name in seen
                or layer.get('mediaType') != media or not DIGEST.fullmatch(layer.get('digest', ''))
                or type(layer.get('size')) is not int or not 0 < layer['size'] <= maximum):
            raise ValueError('unsafe or unexpected OCI layer')
        seen[name] = (layer['size'], layer['digest'][7:])
    return seen


def run(args: list[str], *, data: bytes | None = None, cwd: Path | None = None) -> bytes:
    # No credential-bearing stderr is ever printed. ORAS/gh limits are also
    # bounded by workflow timeouts; metadata is checked before use.
    if len(args) > 3 and args[0] == 'oras' and args[1] == '--registry-config':
        # ORAS 1.2.3 registry flags belong to subcommands, not the root command.
        args = [args[0], *args[3:], *args[1:3]]
    result = subprocess.run(args, input=data, cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=1800, check=False)
    if result.returncode or len(result.stdout) > 2 * 1024**2:
        raise ValueError('private registry operation failed; check package access/signing job status')
    return result.stdout


def private_package() -> None:
    value = run(['gh', 'api', '/users/HawkeyNL/packages/container/jarvis-client-artifacts', '--jq', '.visibility'])
    if value.strip() != b'private':
        raise ValueError('artifact package must already exist and be private')


@contextmanager
def registry():
    private_package()
    with tempfile.TemporaryDirectory(prefix='jarvis-registry-') as temporary:
        config = str(Path(temporary) / 'auth.json')
        token = os.environ.get('GH_TOKEN', '')
        actor = os.environ.get('GITHUB_ACTOR', '')
        if not token or not actor:
            raise ValueError('scoped workflow package credentials required')
        run(['oras', 'login', '--registry-config', config, 'ghcr.io', '--username', actor, '--password-stdin'], data=token.encode())
        del token
        try:
            yield ['oras', '--registry-config', config]
        finally:
            # Temp directory removes the credential file even when logout fails.
            pass


def pull(oras: list[str], reference: str, output: Path, expected: set[str], revision: str, kind: str) -> str:
    digest = run(oras + ['resolve', reference]).decode().strip()
    if not DIGEST.fullmatch(digest):
        raise ValueError('invalid OCI digest')
    raw = run(oras + ['manifest', 'fetch', f'{IMAGE}@{digest}'])
    approved = validate_oci(raw, digest, expected, revision, kind)
    output.mkdir()
    # Only safe, exact filenames and non-extractable octet-stream layers have
    # been approved above. Pull by immutable digest, never by changing tag.
    run(oras + ['pull', f'{IMAGE}@{digest}', '--output', str(output)])
    if inventory(output, expected) != approved:
        raise ValueError('redownloaded private artifact integrity mismatch')
    return digest


def push(oras: list[str], tag: str, directory: Path, expected: set[str], revision: str, kind: str, version: str) -> str:
    original = inventory(directory, expected)
    args = ['push', '--format', 'json', '--artifact-type', kind,
            '--annotation', f'org.opencontainers.image.source={SOURCE}',
            '--annotation', f'org.opencontainers.image.version={version}',
            '--annotation', f'org.opencontainers.image.revision={revision}', f'{IMAGE}:{tag}']
    args.extend(f'{name}:application/json' if name == 'latest.json' else f'{name}:application/octet-stream' for name in sorted(expected))
    reply = json.loads(run(oras + args, cwd=directory))
    digest = reply.get('digest', '')
    if not DIGEST.fullmatch(digest):
        raise ValueError('invalid uploaded OCI digest')
    with tempfile.TemporaryDirectory(prefix='jarvis-redownload-') as temporary:
        output = Path(temporary) / 'assets'
        pull(oras, f'{IMAGE}@{digest}', output, expected, revision, kind)
        if inventory(output, expected) != original:
            raise ValueError('published bytes differ from reviewed build')
    private_package()
    return digest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('push-part', 'pull-parts', 'publish'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--target', choices=('linux', 'windows', 'macos', 'android', 'ios'))
    args = parser.parse_args()
    revision = os.environ.get('GITHUB_SHA', '')
    run_id = os.environ.get('GITHUB_RUN_ID', '')
    attempt = os.environ.get('GITHUB_RUN_ATTEMPT', '')
    if not VERSION.fullmatch(args.version) or not REVISION.fullmatch(revision) or not re.fullmatch('[1-9][0-9]*', run_id) or not re.fullmatch('[1-9][0-9]*', attempt):
        raise ValueError('invalid private release identity')
    directory = args.directory.resolve()
    with registry() as oras:
        prefix = f'build-{run_id}-{attempt}'
        if args.operation == 'push-part':
            if args.target is None:
                raise ValueError('build target required')
            push(oras, f'{prefix}-{args.target}', directory, names(args.version, args.target), revision, BUILD_TYPE, args.version)
        elif args.operation == 'pull-parts':
            directory.mkdir()
            for target in ('linux', 'windows', 'macos', 'android', 'ios'):
                with tempfile.TemporaryDirectory(prefix='jarvis-part-') as temporary:
                    part = Path(temporary) / 'assets'
                    pull(oras, f'{IMAGE}:{prefix}-{target}', part, names(args.version, target), revision, BUILD_TYPE)
                    for path in part.iterdir():
                        shutil.copyfile(path, directory / path.name)
            inventory(directory, expected_assets(args.version))
        else:
            # Version tags are immutable by policy. A successful tags listing
            # distinguishes absence from authentication/network failure.
            tags = run(oras + ['repo', 'tags', IMAGE]).decode().splitlines()
            tag = f'app-v{args.version}'
            if tag in tags:
                raise ValueError('private application version already exists; never overwrite')
            document = validate_manifest(json.loads((directory / 'latest.json').read_bytes()))
            if document['release']['version'] != args.version or document['release']['source_revision'] != revision:
                raise ValueError('release identity mismatch')
            android = next(e for e in document['artifacts'] if e['platform'] == 'android')
            with tempfile.TemporaryDirectory(prefix='jarvis-release-inventory-', dir=directory.parent) as temporary:
                assets = Path(temporary)
                # Do not copy gigabytes just for validation. Hardlinks are local
                # runner-only and removed immediately; Home Node never uses them.
                for name in expected_assets(args.version):
                    os.link(directory / name, assets / name)
                rebuilt = build(assets, args.version, revision, document['release']['released_at'],
                                android['metadata']['version_code'], android['signature']['value'])
                if rebuilt != document:
                    raise ValueError('signed metadata does not bind this exact artifact inventory')
            public_key = os.environ['JARVIS_TAURI_UPDATER_PUBKEY']
            for signature in directory.glob('*.sig'):
                verify(Path(str(signature)[:-4]), signature.read_text(), public_key)
            if 'stable' in tags:
                # Inspect prior provenance, then authenticate its metadata; no
                # trust in a mutable registry tag or unverified version string.
                prior_digest = run(oras + ['resolve', f'{IMAGE}:stable']).decode().strip()
                if not DIGEST.fullmatch(prior_digest):
                    raise ValueError('invalid previous release digest')
                raw = run(oras + ['manifest', 'fetch', f'{IMAGE}@{prior_digest}'])
                metadata = json.loads(raw)
                previous_revision = metadata.get('annotations', {}).get('org.opencontainers.image.revision', '')
                with tempfile.TemporaryDirectory(prefix='jarvis-previous-') as temporary:
                    # Fetch metadata blobs directly: do not download every old
                    # installer merely to enforce monotonically increasing IDs.
                    expected = {'latest.json', 'latest.json.sig'}
                    previous_files = {}
                    for layer in metadata.get('layers', []):
                        name = layer.get('annotations', {}).get('org.opencontainers.image.title')
                        if name in expected:
                            digest = layer.get('digest', '')
                            if name in previous_files or not DIGEST.fullmatch(digest):
                                raise ValueError('invalid previous metadata layer')
                            data = run(oras + ['blob', 'fetch', f'{IMAGE}@{digest}', '--output', '-'])
                            if len(data) > 1024**2 or hashlib.sha256(data).hexdigest() != digest[7:]:
                                raise ValueError('previous metadata checksum mismatch')
                            previous_files[name] = data
                    if set(previous_files) != expected:
                        raise ValueError('previous release metadata missing')
                    manifest = Path(temporary) / 'latest.json'
                    manifest.write_bytes(previous_files['latest.json'])
                    verify(manifest, previous_files['latest.json.sig'].decode(), public_key)
                    previous = validate_manifest(json.loads(previous_files['latest.json']))
                    if previous['release']['source_revision'] != previous_revision:
                        raise ValueError('previous OCI source does not match signed metadata')
                    validate_oci(raw, prior_digest, names(previous['release']['version'], 'release'), previous_revision, RELEASE_TYPE)
                    android = next(e for e in document['artifacts'] if e['platform'] == 'android')
                    validate_progression(previous, args.version, android['metadata']['version_code'])
            digest = push(oras, f'candidate-{run_id}-{attempt}', directory, names(args.version, 'release'), revision, RELEASE_TYPE, args.version)
            # Caller has verified all platform signatures + signed latest.json.
            # Only now expose immutable version and automatic-discovery pointer.
            run(oras + ['tag', f'{IMAGE}@{digest}', tag])
            run(oras + ['tag', f'{IMAGE}@{digest}', 'stable'])
            private_package()
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write(f'Verified private client release: `{IMAGE}@{digest}` ({tag}).\n')


if __name__ == '__main__':
    main()
