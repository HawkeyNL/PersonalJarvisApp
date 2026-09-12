import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import private_registry as registry


class PrivateRegistryTests(unittest.TestCase):
    def fixture(self):
        names = registry.names('0.1.0', 'release')
        doc = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
               'artifactType': registry.RELEASE_TYPE,
               'annotations': {'org.opencontainers.image.source': registry.SOURCE,
                               'org.opencontainers.image.revision': 'b' * 40},
               'layers': [{'mediaType': 'application/json' if name == 'latest.json' else 'application/octet-stream',
                           'digest': 'sha256:' + hashlib.sha256(b'fixture').hexdigest(), 'size': 7,
                           'annotations': {'org.opencontainers.image.title': name}} for name in sorted(names)]}
        return names, doc

    def validate(self, doc, names):
        raw = json.dumps(doc).encode()
        return registry.validate_oci(raw, 'sha256:' + hashlib.sha256(raw).hexdigest(), names, 'b' * 40, registry.RELEASE_TYPE)

    def test_complete_matrix_and_unexpected_layers(self):
        names, doc = self.fixture()
        self.assertEqual(len(self.validate(doc, names)), 12)
        for unsafe in ('../secret', '/absolute', 'latest.json/extra', 'fixture.env'):
            _, altered = self.fixture()
            altered['layers'][0]['annotations']['org.opencontainers.image.title'] = unsafe
            with self.assertRaises(ValueError):
                self.validate(altered, names)

    def test_duplicate_missing_oversize_provenance_and_extractable_layer(self):
        for case in ('duplicate', 'missing', 'oversize', 'revision', 'extract', 'annotation'):
            names, doc = self.fixture()
            if case == 'duplicate': doc['layers'][1] = doc['layers'][0]
            if case == 'missing': doc['layers'].pop()
            if case == 'oversize': doc['layers'][0]['size'] = 2 * 1024**3 + 1
            if case == 'revision': doc['annotations']['org.opencontainers.image.revision'] = 'c' * 40
            if case == 'extract': doc['layers'][0]['mediaType'] = 'application/vnd.oci.image.layer.v1.tar'
            if case == 'annotation': doc['layers'][0]['annotations']['io.deis.oras.content.unpack'] = 'true'
            with self.subTest(case=case), self.assertRaises(ValueError): self.validate(doc, names)

    def test_inventory_checks_types_and_exact_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'test').write_bytes(b'fixture')
            self.assertEqual(registry.inventory(root, {'test'})['test'][0], 7)
            with self.assertRaises(ValueError): registry.inventory(root, {'other'})
            (root / 'link').symlink_to(root / 'test')
            with self.assertRaises(ValueError): registry.inventory(root, {'test', 'link'})

    def test_private_visibility_fails_closed(self):
        for visibility in (b'public', b'internal', b''):
            with patch.object(registry, 'run', return_value=visibility), self.assertRaises(ValueError):
                registry.private_package()

    def test_oras_123_flags_follow_subcommand_and_no_secret_argv(self):
        with patch.object(registry.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'ok')) as call:
            registry.run(['oras', '--registry-config', '/tmp/fixture-auth.json', 'manifest', 'fetch', 'fixture'])
            argv = call.call_args.args[0]
            self.assertEqual(argv[:3], ['oras', 'manifest', 'fetch'])
            self.assertEqual(argv[-2:], ['--registry-config', '/tmp/fixture-auth.json'])

    @unittest.skipUnless(shutil.which('oras'), 'ORAS required for real OCI transport fixture')
    def test_actual_oras_oci_roundtrip(self):
        # Real ORAS, local OCI layout, no registry token or network. Validates
        # exactly the manifest format emitted by pinned production tooling.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            assets = root / 'assets'; assets.mkdir()
            expected = registry.names('0.1.0', 'release')
            for name in expected: (assets / name).write_bytes(b'fixture-not-installable')
            layout = root / 'layout'
            args = ['oras', 'push', '--oci-layout', '--format', 'json', '--artifact-type', registry.RELEASE_TYPE,
                    '--annotation', f'org.opencontainers.image.source={registry.SOURCE}',
                    '--annotation', 'org.opencontainers.image.revision=' + 'b' * 40,
                    str(layout) + ':fixture']
            args += [f'{name}:application/json' if name == 'latest.json' else f'{name}:application/octet-stream' for name in sorted(expected)]
            result = subprocess.run(args, cwd=assets, check=True, capture_output=True)
            digest = json.loads(result.stdout)['digest']
            raw = subprocess.run(['oras', 'manifest', 'fetch', '--oci-layout', str(layout) + '@' + digest], check=True, capture_output=True).stdout
            approved = registry.validate_oci(raw, digest, expected, 'b' * 40, registry.RELEASE_TYPE)
            output = root / 'output'
            subprocess.run(['oras', 'pull', '--oci-layout', str(layout) + '@' + digest, '--output', str(output)], check=True, capture_output=True)
            self.assertEqual(registry.inventory(output, expected), approved)


if __name__ == '__main__': unittest.main()
