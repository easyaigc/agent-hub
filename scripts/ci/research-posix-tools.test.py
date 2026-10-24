import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).with_name('research-posix-tools.py')


class ResearchMetadataTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ci-research-metadata-')
        self.root = Path(self.temporary.name)
        self.executable = self.root / 'Fixture.app/Contents/MacOS/Fixture'
        self.executable.parent.mkdir(parents=True)
        self.executable.symlink_to(sys.executable)
        self.plist = self.executable.parent.parent / 'Info.plist'
        self.plist.write_bytes(plistlib.dumps({'CFBundleShortVersionString': '9.8.7'}))
        self.profile = self.root / 'profile.json'
        self.profile.write_text(json.dumps({'kind': 'transport-poc',
                                           'desktopExecutable': str(self.executable),
                                           'desktopVersion': '0.0.0-expected-is-not-the-answer'}))
        self.profile.chmod(0o600)
        self.env = {**os.environ, 'AGENT_HUB_CI_RESEARCH_TOOLS': '1',
                    'GITHUB_REPOSITORY': 'BCAI/agent-hub-app',
                    'CODEX_MANAGE_STDIO_PROFILE': str(self.profile)}

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, tool='PlistBuddy', args=None, env=None):
        return subprocess.run([sys.executable, str(HELPER), tool,
                               *(args if args is not None else
                                 ['-c', 'Print :CFBundleShortVersionString', str(self.plist)])],
                              env=env or self.env, capture_output=True, text=True)

    def test_actual_xml_version_not_profile_expectation(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '9.8.7')

    def test_actual_binary_plist(self):
        self.plist.write_bytes(plistlib.dumps({'CFBundleShortVersionString': '2.3.4'},
                                             fmt=plistlib.FMT_BINARY))
        self.assertEqual(self.invoke().stdout.strip(), '2.3.4')

    @unittest.skipUnless(sys.platform == 'linux', 'requires real Linux /proc')
    def test_actual_kernel_process_path(self):
        result = self.invoke('ps', ['-p', str(os.getpid()), '-o', 'comm='])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(os.path.realpath(result.stdout.strip()), os.path.realpath(sys.executable))

    @unittest.skipUnless(sys.platform == 'linux', 'requires real Linux /proc')
    def test_missing_process_is_not_fabricated(self):
        result = self.invoke('ps', ['-p', '2147483647', '-o', 'comm='])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    @unittest.skipUnless(sys.platform == 'linux', 'requires installed Linux vendor ps')
    def test_other_ps_arguments_use_vendor(self):
        args = ['-p', str(os.getpid()), '-o', 'pid=']
        vendor = subprocess.run(['/usr/local/libexec/agent-hub-ps.real', *args],
                                capture_output=True, text=True)
        actual = self.invoke('ps', args)
        self.assertEqual(actual.returncode, vendor.returncode)
        self.assertEqual(actual.stdout, vendor.stdout)

    @unittest.skipUnless(sys.platform == 'linux', 'requires installed Linux vendor ps')
    def test_ps_without_research_profile_uses_vendor(self):
        args = ['-p', str(os.getpid()), '-o', 'comm=']
        vendor = subprocess.run(['/usr/local/libexec/agent-hub-ps.real', *args],
                                capture_output=True, text=True)
        actual = self.invoke('ps', args, {**self.env, 'CODEX_MANAGE_STDIO_PROFILE': ''})
        self.assertEqual(actual.returncode, vendor.returncode)
        self.assertEqual(actual.stdout, vendor.stdout)

    def test_other_repository_refused(self):
        self.assertNotEqual(self.invoke(env={**self.env, 'GITHUB_REPOSITORY': 'other/repo'}).returncode, 0)

    def test_missing_opt_in_refused(self):
        self.assertNotEqual(self.invoke(env={**self.env, 'AGENT_HUB_CI_RESEARCH_TOOLS': ''}).returncode, 0)

    def test_public_or_symlink_profile_refused(self):
        self.profile.chmod(0o644)
        self.assertNotEqual(self.invoke().returncode, 0)
        self.profile.chmod(0o600)
        link = self.root / 'link.json'
        link.symlink_to(self.profile)
        self.assertNotEqual(self.invoke(env={**self.env, 'CODEX_MANAGE_STDIO_PROFILE': str(link)}).returncode, 0)

    def test_foreign_plist_and_write_operations_refused(self):
        result = self.invoke(args=['-c', 'Print :CFBundleShortVersionString', str(self.root / 'other.plist')])
        self.assertNotEqual(result.returncode, 0)
        result = self.invoke(args=['-c', 'Set :CFBundleShortVersionString changed', str(self.plist)])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(plistlib.loads(self.plist.read_bytes())['CFBundleShortVersionString'], '9.8.7')

    def test_malformed_plist_is_failure(self):
        self.plist.write_bytes(b'invalid')
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
