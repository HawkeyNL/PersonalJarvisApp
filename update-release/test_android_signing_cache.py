"""Execute the real release signing guard twice with Gradle's strict cache.

Only a synthetic boolean is used. No Android SDK, keystore or signing secret is
read; temporary fixture projects and their configuration caches are discarded.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SigningCacheTests(unittest.TestCase):
    def test_guard_stores_reuses_and_invalidates_cache_without_script_capture(self):
        source = (ROOT / 'android/app/build.gradle.kts').read_text()
        start = source.index('tasks.matching { it.name == "assembleRelease"')
        guard = source[start:source.index('\ndependencies {', start)]
        environment = {key: value for key, value in os.environ.items()
                       if key in ('PATH', 'HOME', 'JAVA_HOME', 'GRADLE_USER_HOME', 'LANG', 'TMPDIR')}
        with tempfile.TemporaryDirectory(prefix='jarvis-signing-cache-') as directory:
            project = Path(directory)
            (project / 'settings.gradle.kts').write_text('rootProject.name = "signing-guard-fixture"\n')
            (project / 'build.gradle.kts').write_text(
                'val releaseSigningConfigured = providers.gradleProperty("fixtureSigningConfigured").orNull == "true"\n'
                'tasks.register("assembleRelease")\n'
                'tasks.register("bundleRelease")\n' + guard)

            def run(configured, *tasks):
                return subprocess.run(
                    [str(ROOT / 'android/gradlew'), '-p', directory, *tasks,
                     f'-PfixtureSigningConfigured={str(configured).lower()}',
                     '--offline', '--no-daemon', '--console=plain',
                     '--configuration-cache', '--configuration-cache-problems=fail'],
                    env=environment, text=True, capture_output=True, timeout=180)

            for attempt in range(2):
                result = run(True, 'assembleRelease', 'bundleRelease')
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode, 0, output)
                self.assertIn('BUILD SUCCESSFUL', output)
                self.assertIn('Reusing configuration cache.' if attempt else
                              'Configuration cache entry stored.', output)

            # Changing the boolean must invalidate the previously successful
            # cache. Each entry point still refuses missing signing material.
            for task in ('assembleRelease', 'bundleRelease'):
                result = run(False, task)
                output = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, output)
                self.assertIn('Release APK/AAB signing is not configured', output)
                self.assertNotIn('cannot serialize Gradle script object references', output)


if __name__ == '__main__':
    unittest.main()
